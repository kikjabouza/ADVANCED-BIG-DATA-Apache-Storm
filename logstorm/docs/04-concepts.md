# 04 — Les concepts de Storm, reliés au code

Chaque concept est expliqué en une phrase, puis montré **là où il apparaît dans LogStorm**.

---

## Tuple
Le message de base : une **liste de valeurs nommées**.
```java
collector.emit(new Values(url, status, latency));   // ParseBolt : un tuple (url, status, latency)
```

## Stream
Une **suite infinie de tuples** qui partagent le même schéma (mêmes champs). Chaque composant
a un stream `default` et peut en déclarer d'autres.
```java
declarer.declare(new Fields("url", "status", "latency"));                 // stream "default"
declarer.declareStream(ERROR_STREAM, new Fields("url", "status", "ts"));  // stream "errors"
```
→ `ParseBolt` produit **deux streams** ; `error-alert` ne s'abonne qu'à `errors`.

## Spout
La **source** du flux. Storm appelle `nextTuple()` en boucle.
→ `LogSpout` simule ~200 lignes de log par seconde. En vrai, ce serait un `KafkaSpout`.

## Bolt
Une **étape de traitement** : filtrer, transformer, agréger, écrire en base.
→ `ParseBolt` (transformation), `PageCountBolt` (agrégation fenêtrée), `ErrorAlertBolt`
(détection), `ReportBolt` (affichage).

| Classe de base | Ack | Utilisée par |
|---|---|---|
| `BaseRichBolt` | **manuel** (`collector.ack`) + ancrage explicite | `ParseBolt` |
| `BaseBasicBolt` | **automatique** à la fin de `execute()` | `ErrorAlertBolt`, `ReportBolt` |
| `BaseWindowedBolt` | automatique à l'expiration de la fenêtre | `PageCountBolt` |

## Topologie
Le **graphe orienté acyclique (DAG)** spouts → bolts, construit avec `TopologyBuilder` et
soumis à Nimbus. Contrairement à un job MapReduce, **elle ne se termine jamais** : elle tourne
jusqu'à `storm kill`.
→ `LogStormTopology.main` (diagramme dans [architecture.md](architecture.md)).

## Worker, executor, task — les 3 niveaux de parallélisme

| Niveau | C'est quoi | Dans LogStorm |
|---|---|---|
| **Worker** | un **processus JVM** sur un supervisor (occupe un slot/port) | `conf.setNumWorkers(2)` |
| **Executor** | un **thread** dans un worker | `setBolt("parse", ..., 2)` → 2 threads |
| **Task** | une **instance** du spout/bolt ; un executor exécute 1 ou plusieurs tasks | `.setNumTasks(4)` |

Règle : `executors ≤ tasks`, et le **nombre de tasks est figé** au déploiement.
C'est pourquoi `parse` a 4 tasks pour 2 executors : on peut ensuite monter à 4 executors
**à chaud** avec `storm rebalance logstorm -e parse=4`.

> 🔎 **Question** — Pourquoi ne peut-on pas faire `-e page-count=4` ? (2 tasks seulement)

## Stream groupings
Le grouping dit **à quelle task** de l'aval chaque tuple est envoyé.

| Grouping | Règle | Dans LogStorm |
|---|---|---|
| `shuffleGrouping` | aléatoire, charge équilibrée | `log-spout → parse` |
| `fieldsGrouping(champs)` | `hash(champs) % nbTasks` : même clé → même task | `parse → page-count` sur `url` |
| `globalGrouping` | tout vers la task d'id le plus petit | `page-count → report`, `errors → error-alert` |
| `allGrouping` | copie vers toutes les tasks | non utilisé (diffusion de config) |
| `localOrShuffleGrouping` | préfère une task du même worker | non utilisé (optimisation réseau) |
| `directGrouping` | l'émetteur choisit la task | non utilisé |

> 🔎 **Expérience** — Remplacez `fieldsGrouping("parse", new Fields("url"))` par
> `shuffleGrouping("parse")`, recompilez, redéployez : chaque URL est maintenant comptée par
> les **deux** tasks `page-count`, qui émettent chacune un compte **partiel** (dans Storm UI,
> *Emitted* de `page-count` double : 16 lignes par fenêtre au lieu de 8). Ici `ReportBolt`
> additionne et masque le problème, mais tout bolt qui prendrait une décision par URL
> (« plus de 500 req → alerte ») se tromperait. C'est ce que le fields grouping évite.

## Acking et garantie *at-least-once*
Storm garantit que **chaque tuple du spout est entièrement traité, ou rejoué**.

1. Le spout émet avec un **msgId** : `collector.emit(new Values(line), msgId)`.
2. Chaque bolt **ancre** ses sorties sur l'entrée : `collector.emit(input, new Values(...))`.
   On obtient un **arbre de tuples** dont la racine est la ligne de log.
3. Chaque bolt **acke** son entrée : `collector.ack(input)`.
4. Les tasks **`__acker`** suivent chaque arbre avec un simple XOR de 64 bits : quand il retombe
   à 0, l'arbre est complet → `LogSpout.ack(msgId)`.
5. Si l'arbre n'est pas complet en `messageTimeoutSecs` (30 s) ou si un bolt appelle `fail` →
   `LogSpout.fail(msgId)` → **rejeu** de la ligne (« Rejeu du message ... » dans les logs).

Conséquence : **pas de perte**, mais des **doublons possibles** (un tuple rejoué peut avoir
été compté une première fois). Pour de l'*exactly-once* : Trident, ou des écritures idempotentes.

`maxSpoutPending(5000)` limite le nombre d'arbres en vol par task de spout : c'est le
mécanisme de **contre-pression** (backpressure) le plus simple.

> ⚠️ **Piège observé pendant le TP** : avec un bolt fenêtré, les tuples restent « en vol » jusqu'à la
> fermeture de la fenêtre. Avec `maxSpoutPending(1000)`, le spout était bloqué par à-coups (~45
> lignes/s au lieu de ~175) et les comptes par fenêtre devenaient incohérents (~110 pour certaines
> pages, ~20 pour d'autres). Règle : `maxSpoutPending` > débit × durée de rétention (~200 × 20 s).

**Preuve mesurée de l'at-least-once** : après l'arrêt brutal de `supervisor-2` (`make chaos`),
Storm UI affichait *Failed* ≈ 4 900 et le spout a loggué **~5 000 `Rejeu du message`** : les
tuples qui étaient dans les workers tués ont tous été réémis, aucun n'a été perdu.

## Fenêtrage
Un flux est infini : pour compter, on le découpe en **fenêtres**.

| Type | Définition | Dans LogStorm |
|---|---|---|
| **Tumbling** | tranches fixes **sans chevauchement** | `PageCountBolt.withTumblingWindow(Duration.seconds(10))` |
| **Sliding** | taille L, avance d'un pas S < L : chevauchement | `ErrorAlertBolt` fait une fenêtre glissante **à la main** (deque de 10 s) |
| Par nombre | `withTumblingWindow(Count.of(100))` | non utilisé |

Les tuples d'une fenêtre ne sont ackés qu'à son expiration : le `messageTimeoutSecs` (30 s)
doit donc être **supérieur** à la durée de la fenêtre (10 s), sinon tout serait rejoué.

## Tick tuple
Un tuple **système** envoyé par Storm à un bolt à intervalle fixe : une horloge.
```java
conf.put(Config.TOPOLOGY_TICK_TUPLE_FREQ_SECS, 10);   // ReportBolt.getComponentConfiguration()
if (TupleUtils.isTick(tuple)) { ... afficher le classement ... }
```

## Les démons du cluster (rappel)
| Démon | Rôle |
|---|---|
| **Nimbus** | maître : reçoit les jars, place les executors, réassigne en cas de panne |
| **Supervisor** | sur chaque machine : démarre/arrête les workers |
| **ZooKeeper** | état partagé, heartbeats, détection des pannes |
| **UI** | supervision web |
| Logviewer, DRPC | non utilisés ici |

➡️ Suite : [05-code-explique.md](05-code-explique.md)
