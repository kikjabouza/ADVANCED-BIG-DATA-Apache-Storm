# Architecture de LogStorm

Deux vues : l'**infrastructure** (les conteneurs du cluster) et la **topologie** (le traitement).

## 1. Le cluster Storm dans Docker

```mermaid
flowchart LR
    subgraph PC["Votre PC — Docker Desktop (réseau logstorm_default)"]
        ZK[("zookeeper<br/>zookeeper:3.9<br/>:2181 interne")]
        N["nimbus<br/>storm nimbus<br/>:6627"]
        UI["ui<br/>storm ui<br/>:8080"]
        subgraph S1["supervisor-1"]
            W1["slots 6700-6703<br/>(workers JVM)"]
        end
        subgraph S2["supervisor-2"]
            W2["slots 6700-6703<br/>(workers JVM)"]
        end
    end
    DEV["Terminal<br/>storm jar / list / kill"] -->|docker compose exec| N
    NAV["Navigateur<br/>localhost:8080"] --> UI
    JAR["./target/logstorm-1.0.jar"] -.->|volume /jars| N
    N <-->|état, assignations| ZK
    S1 <-->|heartbeats| ZK
    S2 <-->|heartbeats| ZK
    UI -->|Thrift| N
    N -.->|télécharge le code| S1
    N -.->|télécharge le code| S2
```

- **Nimbus** ne parle jamais directement aux workers pendant le traitement : tout l'état
  partagé (qui fait quoi, qui est vivant) passe par **ZooKeeper**.
- Si un supervisor disparaît, ses heartbeats s'arrêtent ; Nimbus le voit dans ZooKeeper et
  réassigne ses workers ailleurs (démo `make chaos`).
- Les tuples, eux, circulent **directement de worker à worker** (réseau Netty), sans passer par Nimbus.

## 2. Le DAG de la topologie

```mermaid
flowchart LR
    SP["log-spout<br/>LogSpout<br/>1 executor"]
    P["parse<br/>ParseBolt<br/>2 executors / 4 tasks"]
    PC["page-count<br/>PageCountBolt<br/>fenêtre tumbling 10 s<br/>2 executors"]
    R["report<br/>ReportBolt<br/>tick 10 s<br/>1 executor"]
    A["error-alert<br/>ErrorAlertBolt<br/>&gt;15 erreurs / 10 s<br/>1 executor"]

    SP -- "shuffle<br/>(line)" --> P
    P -- "fields(url)<br/>(url, status, latency)" --> PC
    PC -- "global<br/>(url, count, avgLatency)" --> R
    P -- "stream errors · global<br/>(url, status, ts)" --> A
    R --> LOG1[/"[RAPPORT] dans worker.log"/]
    A --> LOG2[/"[ALERTE] dans worker.log"/]
```

| Arête | Grouping | Pourquoi |
|---|---|---|
| log-spout → parse | `shuffleGrouping` | parser une ligne ne dépend d'aucune clé : on équilibre la charge |
| parse → page-count | `fieldsGrouping("url")` | toutes les requêtes d'une même page doivent être comptées par la même task |
| page-count → report | `globalGrouping` | un seul classement final, donc une seule task |
| parse (`errors`) → error-alert | `globalGrouping` | un seul compteur d'erreurs pour tout le site |

## 3. Le parallélisme sur le cluster

Avec `setNumWorkers(2)` et 2 supervisors, le scheduler répartit les executors sur 2 JVM :

```mermaid
flowchart TB
    subgraph W1["Worker 1 (JVM, ex. supervisor-1:6700)"]
        e1["log-spout"]; e2["parse #1"]; e3["page-count #1"]; e4["report"]; ack1["__acker"]
    end
    subgraph W2["Worker 2 (JVM, ex. supervisor-2:6700)"]
        e5["parse #2"]; e6["page-count #2"]; e7["error-alert"]; ack2["__acker"]
    end
```

La répartition exacte change à chaque déploiement : regardez la page de la topologie dans
Storm UI (colonnes *Host* et *Port* d'un composant) pour savoir où tourne quoi.
