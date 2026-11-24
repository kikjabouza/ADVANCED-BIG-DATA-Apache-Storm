# 05 — Le code expliqué, classe par classe

```
src/main/java/com/bigdata/logstorm/
├── LogStormTopology.java      le DAG + la configuration + la soumission
├── spout/LogSpout.java        source : génère les logs, émission fiable
└── bolt/
    ├── ParseBolt.java         ligne → (url, status, latency) + stream "errors"
    ├── PageCountBolt.java     comptage par URL, fenêtre tumbling 10 s
    ├── ErrorAlertBolt.java    > 15 erreurs 5xx / 10 s → [ALERTE]
    └── ReportBolt.java        classement toutes les 10 s → [RAPPORT]
```

Le `pom.xml` n'a **qu'une dépendance** : `storm-client`, en scope `provided` (le cluster la
fournit, on ne l'embarque pas). Le jar produit fait donc ~15 Ko.

---

## 1. `LogSpout` — la source

**Rôle** : simuler des serveurs web. Format d'une ligne (Apache Common Log + latence en ms) :
```
192.168.12.7 - - [21/Nov/2026:10:00:00 +0000] "GET /panier HTTP/1.1" 200 1534 87
```

| Bloc | Explication |
|---|---|
| Constantes `PAGES`, `METHODS`, taux | 8 pages ; 2 % d'erreurs 5xx normalement, **35 %** pendant une **rafale de 10 s toutes les 40 s** |
| Champs `collector`, `random`, `pending` | initialisés dans **`open()`**, pas dans le constructeur : le spout est **sérialisé** puis envoyé au worker, `open()` s'exécute sur le worker |
| `nextTuple()` | `Utils.sleep(5)` → au plus ~200 lignes/s (~175 mesurées) ; génère un **msgId UUID**, mémorise la ligne dans `pending`, puis `emit(new Values(line), msgId)` : le msgId rend le tuple **suivi** par les ackers |
| `inErrorBurst()` | vrai quand `secondes % 40 >= 30` → secondes 30 à 39 de chaque cycle |
| `buildLine()` | tire une IP, une page, un statut (500/503, sinon 404 à 8 %, sinon 200) ; les erreurs ont une latence de 800-2800 ms |
| `ack(msgId)` | l'arbre est complet : on retire la ligne de `pending` |
| `fail(msgId)` | échec ou timeout de 30 s : on **réémet la même ligne avec le même msgId** et on loggue `Rejeu du message ...` |
| `declareOutputFields` | un seul champ : `line` |

> ⚠️ Ne **jamais bloquer** dans `nextTuple()` (lecture réseau bloquante, `sleep` long) : le même
> thread appelle aussi `ack()`/`fail()`.

## 2. `ParseBolt` — le parsing (BaseRichBolt)

| Bloc | Explication |
|---|---|
| `ERROR_STREAM = "errors"` | constante **publique** : la topologie s'en sert pour s'abonner au même nom |
| `Pattern LOG_LINE` | regex compilée **une seule fois** (`static final`) ; groupes : IP, date, méthode, **url (4)**, **status (5)**, octets, **latence (7)** |
| `prepare()` | on garde le `OutputCollector` |
| ligne invalide | `LOG.warn` + **`ack`** : sinon elle expirerait et serait rejouée indéfiniment |
| nettoyage URL | `/recherche?q=storm` → `/recherche` (on coupe au `?`) |
| `emit(input, values)` | **ancrage** : le 1er argument relie le nouveau tuple à `input` dans l'arbre |
| `emit(ERROR_STREAM, input, ...)` | pour status ≥ 500, un 2e tuple **ancré** sur le stream `errors` : `(url, status, ts)` |
| `collector.ack(input)` | toujours, à la fin |
| `declareOutputFields` | deux streams : `default (url, status, latency)` et `errors (url, status, ts)` |

## 3. `PageCountBolt` — la fenêtre (BaseWindowedBolt)

| Bloc | Explication |
|---|---|
| `extends BaseWindowedBolt` | Storm met les tuples en mémoire et appelle `execute(TupleWindow)` **une fois par fenêtre** |
| taille de la fenêtre | **pas dans cette classe** : `.withTumblingWindow(Duration.seconds(10))` dans la topologie |
| `execute(TupleWindow)` | `window.get()` = tous les tuples des 10 s ; une map `url → [nombre, somme latences]` |
| émission | une ligne `(url, count, avgLatency)` par URL vue dans la fenêtre |
| ack | **automatique** à l'expiration de la fenêtre |

Grâce au `fieldsGrouping("url")`, chaque URL n'est comptée que par **une** des 2 tasks : chaque
compte est **complet**.

## 4. `ErrorAlertBolt` — la détection (BaseBasicBolt)

| Bloc | Explication |
|---|---|
| `THRESHOLD = 15`, `WINDOW_MS = 10 000`, `COOLDOWN_MS = 10 000` | > 15 erreurs en 10 s ; au plus une alerte toutes les 10 s |
| `Deque<Long> errors` | horodatages des erreurs ; `transient` + créé dans `prepare()` |
| `execute()` | ajoute `now`, retire en tête tout ce qui a plus de 10 s : **fenêtre glissante** faite main |
| déclenchement | si taille > 15 et dernière alerte > 10 s → `LOG.warn("[ALERTE] ...")` + `emit(alert, ts)` |
| `BaseBasicBolt` | ancrage et ack **automatiques** |

Pourquoi `globalGrouping` ? Il faut **un seul** compteur pour tout le site : toutes les erreurs
doivent arriver à la même task.

Pourquoi une alerte ? En temps normal : ~175-200 lignes/s × 2 % ≈ **~40 erreurs en 10 s** (mesuré : 31 à 46)...
> 🔎 **Question piège** — Avec ces chiffres, le seuil de 15 n'est-il pas dépassé en permanence ?
> Calculez : oui, ~40 erreurs/10 s même hors rafale. Le cooldown limite à **une alerte toutes
> les 10 s**, et pendant la rafale on monte à ~700 erreurs/10 s. Pour n'alerter **que** pendant
> les rafales, montez `THRESHOLD` à ~100 : bon exercice de réglage (voir [06-demo.md](06-demo.md)).

## 5. `ReportBolt` — l'affichage (BaseBasicBolt + tick tuple)

| Bloc | Explication |
|---|---|
| `getComponentConfiguration()` | config **propre à ce bolt** : `TOPOLOGY_TICK_TUPLE_FREQ_SECS = 10` |
| tuple normal | `latest.put(url, [count, avg])` : **remplace** la valeur de cette URL par le résultat de sa dernière fenêtre fermée |
| tick tuple (`TupleUtils.isTick`) | trie par nombre décroissant, construit le bloc `[RAPPORT]`, `LOG.info` (sans vider la map) |

> Pourquoi remplacer plutôt que cumuler puis vider ? Les 2 tasks `page-count` ne ferment pas
> leurs fenêtres au même instant que le tick de `ReportBolt`. Une première version qui cumulait
> puis vidait la map à chaque tick affichait des rapports **incomplets** (3 à 6 pages au lieu de 8,
> et des comptes partiels). En gardant la dernière fenêtre de chaque page, chaque rapport est complet.
| `declareOutputFields` vide | bolt **terminal** |

> Les logs `LOG.info/warn` vont dans le `worker.log` du worker qui héberge le bolt
> (`/logs/workers-artifacts/<topo>/<port>/worker.log` dans le conteneur supervisor), ou
> directement dans le terminal en mode `storm local`.

## 6. `LogStormTopology` — l'assemblage

```java
builder.setSpout("log-spout", new LogSpout(), 1);
builder.setBolt("parse", new ParseBolt(), 2).setNumTasks(4).shuffleGrouping("log-spout");
builder.setBolt("page-count", new PageCountBolt().withTumblingWindow(Duration.seconds(10)), 2)
       .fieldsGrouping("parse", new Fields("url"));
builder.setBolt("error-alert", new ErrorAlertBolt(), 1).globalGrouping("parse", ParseBolt.ERROR_STREAM);
builder.setBolt("report", new ReportBolt(), 1).globalGrouping("page-count");
```

| Ligne | Ce qu'il faut dire |
|---|---|
| `setSpout(id, objet, n)` | `id` = nom affiché dans l'UI ; `n` = nombre d'executors |
| `.setNumTasks(4)` | réserve de parallélisme pour `rebalance` |
| `.shuffleGrouping("log-spout")` | s'abonne au stream `default` de `log-spout` |
| `.globalGrouping("parse", ERROR_STREAM)` | s'abonne au stream **nommé** `errors` |
| `setNumWorkers(2)` | 2 JVM → réparties sur les 2 supervisors |
| `setMaxSpoutPending(5000)` | contre-pression ; doit dépasser débit × rétention de la fenêtre (voir [04-concepts.md](04-concepts.md)) |
| `setMessageTimeoutSecs(30)` | > fenêtre de 10 s, obligatoire |
| `StormSubmitter.submitTopology` | envoie au cluster ; en `storm local`, intercepté → LocalCluster |

Pas de `LocalCluster` dans le code : **le même jar** sert au mode local et au cluster.

➡️ Suite : [06-demo.md](06-demo.md)
