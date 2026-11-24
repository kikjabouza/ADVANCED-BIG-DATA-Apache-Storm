# 00 — Réaliser le projet LogStorm pas à pas (TP complet, à la main)

Ce guide part d'un **dossier vide** et vous fait construire **tout le projet vous-même** :
le cluster Storm, le projet Maven, les 6 classes Java, la compilation, le test local, le
déploiement, la mise à l'échelle et la panne simulée. Chaque fichier est donné **en entier**,
suivi de son explication et d'un **✅ point de contrôle** pour vérifier que tout va bien avant
de continuer.

| | |
|---|---|
| Durée | 2 h à 3 h la première fois |
| Niveau | bases de Java et de la ligne de commande |
| À installer | Docker Desktop + Git (rien d'autre : ni Java, ni Maven, ni Storm) |
| Système | Windows 10/11, macOS ou Linux |

> **Comment lire ce guide**
> - 📄 = un fichier à créer (copiez **tout** le bloc de code).
> - ⌨️ = une commande à taper.
> - ✅ = un point de contrôle : si le résultat n'est pas celui attendu, **arrêtez-vous** et
>   consultez [08-depannage.md](08-depannage.md).
> - 💡 = une explication de concept (détails dans [04-concepts.md](04-concepts.md)).

## Sommaire

| Partie | Étapes |
|---|---|
| A. Préparer l'environnement | 1. Installer Docker et Git · 2. Choisir son terminal · 3. Créer l'arborescence |
| B. Le cluster Storm | 4. `.env` et `.gitignore` · 5. `docker-compose.yml` · 6. Démarrer le cluster · 7. Découvrir Storm UI |
| C. Le projet Java | 8. `pom.xml` · 9. LogSpout · 10. ParseBolt · 11. PageCountBolt · 12. ErrorAlertBolt · 13. ReportBolt · 14. LogStormTopology |
| D. Exécuter | 15. Compiler · 16. Mode local · 17. Déployer · 18. Lire les logs · 19. Observer dans l'UI |
| E. Expérimenter | 20. Rebalance · 21. Panne d'une machine · 22. Arrêter proprement |
| F. Automatiser et publier | 23. Scripts · 24. Makefile · 25. README et docs · 26. Git et GitHub |

---

# Partie A — Préparer l'environnement

## Étape 1 — Installer Docker Desktop et Git

Suivez [01-installation.md](01-installation.md) (Windows, macOS ou Linux, avec où cliquer).
Résumé Windows : téléchargez Docker Desktop, gardez **Use WSL 2** coché, redémarrez, ouvrez
Docker Desktop jusqu'à **Engine running**, puis installez Git depuis git-scm.com.

⌨️ Vérifiez :
```bash
docker --version
docker compose version
git --version
docker run --rm hello-world
```

✅ La dernière commande affiche **`Hello from Docker!`**.

💡 **Pourquoi Docker ?** Un cluster Storm, c'est au moins 4 programmes (ZooKeeper, Nimbus,
Supervisor, UI) qui demandent Java 25 et Python. Docker fournit tout ça dans des **conteneurs**
prêts à l'emploi : chaque conteneur joue le rôle d'une machine du cluster.

## Étape 2 — Choisir son terminal

| Système | Terminal conseillé | Remarque |
|---|---|---|
| Windows | **PowerShell** (ou Windows Terminal) | les commandes sont données pour PowerShell **et** pour Git Bash |
| Windows | Git Bash | ajoutez `-T` après `docker compose exec`, et tapez une fois `export MSYS_NO_PATHCONV=1` |
| macOS / Linux | Terminal | utilisez les commandes « Bash » |

> Dans ce guide, quand une commande diffère, elle est donnée deux fois : **Bash** (Git Bash,
> macOS, Linux) et **PowerShell** (Windows).

## Étape 3 — Créer l'arborescence du projet

Placez-vous là où vous voulez créer le projet (par exemple votre Bureau), puis :

⌨️ **Bash**
```bash
mkdir -p logstorm/src/main/java/com/bigdata/logstorm/spout
mkdir -p logstorm/src/main/java/com/bigdata/logstorm/bolt
mkdir -p logstorm/scripts logstorm/docs
cd logstorm
```

⌨️ **PowerShell**
```powershell
New-Item -ItemType Directory -Force logstorm\src\main\java\com\bigdata\logstorm\spout
New-Item -ItemType Directory -Force logstorm\src\main\java\com\bigdata\logstorm\bolt
New-Item -ItemType Directory -Force logstorm\scripts, logstorm\docs
cd logstorm
```

Ouvrez ensuite le dossier dans votre éditeur (VS Code : `code .`).

✅ Vous êtes dans `logstorm/` et vous avez cette structure (vide pour l'instant) :
```
logstorm/
├── docs/
├── scripts/
└── src/main/java/com/bigdata/logstorm/
    ├── bolt/
    └── spout/
```

💡 **Pourquoi `com/bigdata/logstorm` ?** En Java, le nom du *package* (`com.bigdata.logstorm`)
doit correspondre aux dossiers. Les sous-packages `spout` et `bolt` rangent les deux types de
composants Storm.

> ⚠️ **Règle d'or pour tous les fichiers** : enregistrez-les en **UTF-8** (c'est le cas par
> défaut dans VS Code) et sans extension cachée (`.env` et non `.env.txt`).

---

# Partie B — Le cluster Storm

## Étape 4 — Les fichiers `.env` et `.gitignore`

📄 **`.env`** — la version de Storm, lue automatiquement par Docker Compose :
```text
STORM_VERSION=3.2.0
```

💡 Une seule ligne : on la change ici, et `docker-compose.yml` l'utilise via `${STORM_VERSION}`.
Avant de l'écrire, vérifiez sur <https://hub.docker.com/_/storm> (onglet **Tags**) que le tag
`3.2.0` existe ; sinon prenez le plus récent et notez-le, il faudra le reporter dans le `pom.xml`.

📄 **`.gitignore`** — ce que Git ne doit pas versionner :
```text
target/
.idea/
*.iml
.vscode/
.DS_Store
.m2/
*.cer
```

💡 `target/` contient le jar compilé (on le reconstruit), `.idea/`, `*.iml`, `.vscode/` sont des
fichiers d'éditeurs, `.DS_Store` vient de macOS, `*.cer` évite de publier un certificat (voir
étape 15).

## Étape 5 — Le fichier `docker-compose.yml`

📄 **`docker-compose.yml`** :
```yaml
# Cluster Apache Storm complet sur un seul PC.
# Démarrage : docker compose up -d --scale supervisor=2
# Version de Storm : définie dans le fichier .env (STORM_VERSION).

# Nom du projet Compose : fixe le préfixe des conteneurs (logstorm-nimbus-1,
# logstorm-supervisor-2...) même si le dossier est renommé.
name: logstorm

services:
  # Coordination distribuée : état du cluster, heartbeats, élection du leader.
  # Le nom "zookeeper" est OBLIGATOIRE : l'image storm s'y connecte par ce nom.
  zookeeper:
    image: zookeeper:3.9
    restart: always

  # Nœud maître : reçoit les .jar, planifie les workers sur les supervisors.
  # Le nom "nimbus" est OBLIGATOIRE (nimbus.seeds de l'image officielle).
  nimbus:
    image: storm:${STORM_VERSION}
    command: storm nimbus
    depends_on: [zookeeper]
    restart: always
    ports:
      - "6627:6627"          # port Thrift de Nimbus (soumission des topologies)
    volumes:
      - ./target:/jars       # le .jar compilé dans target/ est visible dans /jars

  # Machine de travail : lance les JVM workers (4 slots : ports 6700-6703).
  # Pas de container_name : on peut en lancer plusieurs avec --scale supervisor=2.
  supervisor:
    image: storm:${STORM_VERSION}
    command: storm supervisor
    depends_on: [nimbus, zookeeper]
    restart: always

  # Interface web de supervision : http://localhost:8080
  ui:
    image: storm:${STORM_VERSION}
    command: storm ui
    depends_on: [nimbus]
    restart: always
    ports:
      - "8080:8080"
```

💡 **Ce que décrit ce fichier** (détails dans [02-cluster.md](02-cluster.md)) :

| Service | Rôle dans Storm | À retenir |
|---|---|---|
| `zookeeper` | coordination : état du cluster, heartbeats | le nom **doit** être `zookeeper` |
| `nimbus` | le maître : reçoit les jars, répartit le travail | le nom **doit** être `nimbus` ; port 6627 ; `./target` est monté sur `/jars` |
| `supervisor` | une machine de travail (4 slots = 4 workers max) | **pas de `container_name`** pour pouvoir en lancer 2 |
| `ui` | l'interface web | port 8080 |

> ⚠️ En YAML, **l'indentation compte** (2 espaces, jamais de tabulation).

⌨️ Vérifiez la syntaxe :
```bash
docker compose config
```

✅ La commande affiche le fichier « résolu », avec `image: storm:3.2.0` (la variable du `.env`
a été remplacée). Pas de message d'erreur.

## Étape 6 — Télécharger les images et démarrer le cluster

⌨️
```bash
docker compose pull                          # télécharge storm:3.2.0 et zookeeper:3.9 (~1 Go)
docker compose up -d --scale supervisor=2    # démarre 5 conteneurs en arrière-plan
docker compose ps
```

✅ `docker compose ps` affiche **5 lignes** avec le statut `Up` :
```
logstorm-nimbus-1       Up ...   0.0.0.0:6627->6627/tcp
logstorm-supervisor-1   Up ...
logstorm-supervisor-2   Up ...
logstorm-ui-1           Up ...   0.0.0.0:8080->8080/tcp
logstorm-zookeeper-1    Up ...
```

⌨️ Vérifiez que Storm répond :
```bash
docker compose exec nimbus storm version
```
✅ La sortie contient `Storm 3.2.0`.

💡 `-d` = *detached* (en arrière-plan). `--scale supervisor=2` lance **deux** instances du
service `supervisor` : vous simulez un cluster de 2 machines. `docker compose exec nimbus ...`
exécute une commande **à l'intérieur** du conteneur nimbus, où l'outil `storm` est installé.

## Étape 7 — Découvrir Storm UI

Ouvrez **<http://localhost:8080>** (attendez 20-30 s si la page ne répond pas).

✅ Dans **Cluster Summary** : *Supervisors* = **2**, *Free slots* = **8**.
✅ Dans **Nimbus Summary** : un nimbus avec le statut **Leader**.
✅ **Topology Summary** est vide.

Visite détaillée : [03-storm-ui.md](03-storm-ui.md).

> Vous pouvez laisser le cluster tourner pendant toute la partie C, ou l'arrêter avec
> `docker compose down` et le relancer plus tard avec la commande de l'étape 6.

---

# Partie C — Le projet Java

On écrit maintenant le programme qui va tourner **sur** le cluster : une **topologie**.

```
log-spout --shuffle--> parse --fields(url)--> page-count (10 s) --global--> report
                         \--stream "errors", global--> error-alert
```

On écrit les classes dans l'ordre du flux : d'abord la source (spout), puis les bolts, et en
dernier la classe qui les assemble.

## Étape 8 — Le fichier `pom.xml` (projet Maven)

📄 **`pom.xml`** (à la racine de `logstorm/`) :
```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.bigdata</groupId>
    <artifactId>logstorm</artifactId>
    <version>1.0</version>
    <packaging>jar</packaging>
    <name>LogStorm</name>
    <description>Surveillance de logs web en temps réel avec Apache Storm (projet pédagogique)</description>

    <properties>
        <!-- Doit correspondre à STORM_VERSION dans .env (version du cluster) -->
        <storm.version>3.2.0</storm.version>
        <!-- Storm 3.x tourne sur Java 25 ; mettre 17 pour Storm 2.8.x -->
        <java.release>25</java.release>
        <maven.compiler.release>${java.release}</maven.compiler.release>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    </properties>

    <dependencies>
        <!-- API Storm. Scope "provided" : le cluster fournit déjà ces classes,
             on ne les embarque donc pas dans notre jar. -->
        <dependency>
            <groupId>org.apache.storm</groupId>
            <artifactId>storm-client</artifactId>
            <version>${storm.version}</version>
            <scope>provided</scope>
        </dependency>
    </dependencies>

    <build>
        <!-- Produit target/logstorm-1.0.jar -->
        <finalName>logstorm-1.0</finalName>
    </build>
</project>
```

💡 Points importants :
- **Une seule dépendance**, `storm-client`, en scope **`provided`** : le cluster contient déjà
  Storm, on ne l'embarque donc pas dans notre jar (qui ne fera que ~15 Ko).
- `storm.version` **doit** être égale à `STORM_VERSION` du `.env`.
- `java.release` = **25** pour Storm 3.x (17 pour Storm 2.8.x). Un jar compilé pour une Java
  plus récente que celle du cluster provoque `UnsupportedClassVersionError`.
- `finalName` = `logstorm-1.0` → le jar s'appellera `target/logstorm-1.0.jar`.

## Étape 9 — Le spout : `LogSpout.java`

📄 **`src/main/java/com/bigdata/logstorm/spout/LogSpout.java`** :
```java
package com.bigdata.logstorm.spout;

import org.apache.storm.spout.SpoutOutputCollector;
import org.apache.storm.task.TopologyContext;
import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.topology.base.BaseRichSpout;
import org.apache.storm.tuple.Fields;
import org.apache.storm.tuple.Values;
import org.apache.storm.utils.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Spout = source du flux. Simule des serveurs web : ~200 lignes de log par seconde
 * au format Apache Common Log, suivies de la latence en ms :
 * <pre>192.168.1.7 - - [21/Nov/2026:10:00:00 +0000] "GET /panier HTTP/1.1" 200 1534 87</pre>
 *
 * Émission FIABLE : chaque tuple porte un msgId ; Storm appelle ack() quand tout
 * l'arbre de tuples est traité, ou fail() en cas d'échec/timeout (=> rejeu).
 */
public class LogSpout extends BaseRichSpout {

    private static final Logger LOG = LoggerFactory.getLogger(LogSpout.class);

    private static final String[] PAGES = {"/", "/produits", "/produits/42", "/panier",
            "/paiement", "/compte", "/recherche?q=storm", "/api/stock"};
    private static final String[] METHODS = {"GET", "GET", "GET", "POST"};
    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("dd/MMM/yyyy:HH:mm:ss Z", Locale.ENGLISH);

    private static final double NORMAL_ERROR_RATE = 0.02;  // 2 % d'erreurs 5xx en temps normal
    private static final double BURST_ERROR_RATE = 0.35;   // 35 % pendant une rafale
    private static final int BURST_PERIOD_S = 40;          // une rafale toutes les 40 s...
    private static final int BURST_LENGTH_S = 10;          // ...qui dure 10 s

    // Ces champs sont initialisés dans open() et non dans le constructeur :
    // le spout est sérialisé puis envoyé au worker, open() s'exécute sur le worker.
    private SpoutOutputCollector collector;
    private Random random;
    private Map<String, String> pending;   // msgId -> ligne : ce qui n'est pas encore acké
    private long startMs;

    @Override
    public void open(Map<String, Object> conf, TopologyContext context, SpoutOutputCollector collector) {
        this.collector = collector;
        this.random = new Random();
        this.pending = new ConcurrentHashMap<>();
        this.startMs = System.currentTimeMillis();
    }

    /** Appelé en boucle par Storm. Ne jamais bloquer longtemps ici. */
    @Override
    public void nextTuple() {
        Utils.sleep(5);                                 // ~200 lignes/s
        String line = buildLine();
        String msgId = UUID.randomUUID().toString();    // identifiant unique => tuple suivi
        pending.put(msgId, line);                       // on le garde pour pouvoir le rejouer
        collector.emit(new Values(line), msgId);        // emit AVEC msgId = émission fiable
    }

    /** Vrai pendant les 10 dernières secondes de chaque cycle de 40 s. */
    private boolean inErrorBurst() {
        long s = (System.currentTimeMillis() - startMs) / 1000;
        return s % BURST_PERIOD_S >= BURST_PERIOD_S - BURST_LENGTH_S;
    }

    private String buildLine() {
        String ip = "192.168." + random.nextInt(256) + "." + random.nextInt(256);
        String page = PAGES[random.nextInt(PAGES.length)];
        double errorRate = inErrorBurst() ? BURST_ERROR_RATE : NORMAL_ERROR_RATE;
        int status = random.nextDouble() < errorRate
                ? (random.nextBoolean() ? 500 : 503)
                : (random.nextDouble() < 0.08 ? 404 : 200);
        int bytes = 200 + random.nextInt(5000);
        // les erreurs serveur sont aussi plus lentes
        int latency = status >= 500 ? 800 + random.nextInt(2000) : 10 + random.nextInt(200);
        return String.format("%s - - [%s] \"%s %s HTTP/1.1\" %d %d %d", ip,
                ZonedDateTime.now().format(FMT), METHODS[random.nextInt(METHODS.length)],
                page, status, bytes, latency);
    }

    /** Le tuple et tous ses descendants ont été traités : on l'oublie. */
    @Override
    public void ack(Object msgId) {
        pending.remove(msgId);
    }

    /** Échec ou timeout (30 s) : on réémet la même ligne => garantie at-least-once. */
    @Override
    public void fail(Object msgId) {
        String line = pending.get(msgId);
        if (line != null) {
            LOG.warn("Rejeu du message {}", msgId);
            collector.emit(new Values(line), msgId);
        }
    }

    /** Schéma du stream par défaut : un seul champ "line". */
    @Override
    public void declareOutputFields(OutputFieldsDeclarer declarer) {
        declarer.declare(new Fields("line"));
    }
}
```

💡 **Ce qu'il faut comprendre**

1. **Un spout est la source du flux.** Storm appelle `nextTuple()` en boucle ; à chaque appel,
   on émet une ligne de log. Le `Utils.sleep(5)` limite à ~200 lignes par seconde (~175 mesurées).
2. **`open()` au lieu du constructeur** : Storm crée l'objet sur votre machine, le
   **sérialise**, l'envoie au worker, puis appelle `open()` là-bas. Tout ce qui ne se sérialise
   pas (connexions, `Random`...) s'initialise donc dans `open()`.
3. **Émission fiable** : `emit(new Values(line), msgId)`. Le 2e argument, un identifiant unique,
   demande à Storm de **suivre** ce tuple. Storm rappellera :
   - `ack(msgId)` quand tout le traitement en aval est terminé → on oublie la ligne ;
   - `fail(msgId)` en cas d'échec ou après 30 s sans réponse → on **réémet** la ligne.
   C'est la garantie **at-least-once** : aucune ligne n'est perdue.
4. **La rafale d'erreurs** : `inErrorBurst()` renvoie vrai pendant les secondes 30 à 39 de chaque
   cycle de 40 s ; le taux d'erreurs passe alors de 2 % à 35 %.
5. `declareOutputFields` déclare le **schéma** des tuples émis : un seul champ, `line`.

## Étape 10 — Le bolt de parsing : `ParseBolt.java`

📄 **`src/main/java/com/bigdata/logstorm/bolt/ParseBolt.java`** :
```java
package com.bigdata.logstorm.bolt;

import org.apache.storm.task.OutputCollector;
import org.apache.storm.task.TopologyContext;
import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.topology.base.BaseRichBolt;
import org.apache.storm.tuple.Fields;
import org.apache.storm.tuple.Tuple;
import org.apache.storm.tuple.Values;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Découpe une ligne de log en (url, status, latency).
 * Les erreurs 5xx sont AUSSI envoyées sur un second stream nommé "errors".
 *
 * BaseRichBolt : on gère soi-même l'ancrage et l'ack (contrôle total).
 */
public class ParseBolt extends BaseRichBolt {

    /** Nom du second stream, réutilisé dans la topologie pour s'y abonner. */
    public static final String ERROR_STREAM = "errors";

    private static final Logger LOG = LoggerFactory.getLogger(ParseBolt.class);

    // Groupes : 1=IP 2=date 3=méthode 4=url 5=status 6=octets 7=latence
    private static final Pattern LOG_LINE = Pattern.compile(
            "^(\\S+) \\S+ \\S+ \\[([^\\]]+)\\] \"(\\S+) (\\S+) [^\"]*\" (\\d{3}) (\\d+) (\\d+)$");

    private OutputCollector collector;

    @Override
    public void prepare(Map<String, Object> topoConf, TopologyContext context, OutputCollector collector) {
        this.collector = collector;
    }

    @Override
    public void execute(Tuple input) {
        String line = input.getStringByField("line");
        Matcher m = LOG_LINE.matcher(line);
        if (!m.matches()) {
            LOG.warn("Ligne ignorée : {}", line);
            collector.ack(input);       // on ack quand même, sinon elle serait rejouée sans fin
            return;
        }

        String url = m.group(4);
        int q = url.indexOf('?');
        if (q >= 0) url = url.substring(0, q);          // /recherche?q=storm -> /recherche
        int status = Integer.parseInt(m.group(5));
        long latency = Long.parseLong(m.group(7));

        // Émission ANCRÉE (1er argument = tuple d'entrée) : le nouveau tuple devient
        // un enfant de "input" dans l'arbre de tuples suivi par l'acker.
        collector.emit(input, new Values(url, status, latency));

        // Stream nommé "errors" : seul error-alert y est abonné
        if (status >= 500) {
            collector.emit(ERROR_STREAM, input, new Values(url, status, System.currentTimeMillis()));
        }

        // On confirme le traitement du tuple d'entrée
        collector.ack(input);
    }

    /** Ce bolt déclare DEUX streams avec leurs schémas. */
    @Override
    public void declareOutputFields(OutputFieldsDeclarer declarer) {
        declarer.declare(new Fields("url", "status", "latency"));                 // stream "default"
        declarer.declareStream(ERROR_STREAM, new Fields("url", "status", "ts"));  // stream "errors"
    }
}
```

💡 **Ce qu'il faut comprendre**

1. **`BaseRichBolt`** : on gère soi-même l'**ancrage** et l'**ack** de chaque tuple.
2. **La regex** découpe la ligne ; on garde le groupe 4 (URL), 5 (statut) et 7 (latence). L'URL
   est nettoyée de sa *query string* : `/recherche?q=storm` → `/recherche`.
3. **Ancrage** : `collector.emit(input, new Values(...))`. Le 1er argument relie le nouveau tuple
   à celui qu'on vient de recevoir. Si un bolt en aval échoue, Storm sait remonter jusqu'à la
   ligne d'origine et la faire rejouer par le spout.
4. **Deux streams** : le stream `default` reçoit toutes les lignes ; le stream nommé `errors` ne
   reçoit que les 5xx. Chaque stream est déclaré avec son schéma dans `declareOutputFields`.
5. **Toujours `ack`** — même une ligne invalide, sinon elle expirerait et serait rejouée sans fin.

## Étape 11 — Le bolt fenêtré : `PageCountBolt.java`

📄 **`src/main/java/com/bigdata/logstorm/bolt/PageCountBolt.java`** :
```java
package com.bigdata.logstorm.bolt;

import org.apache.storm.task.OutputCollector;
import org.apache.storm.task.TopologyContext;
import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.topology.base.BaseWindowedBolt;
import org.apache.storm.tuple.Fields;
import org.apache.storm.tuple.Tuple;
import org.apache.storm.tuple.Values;
import org.apache.storm.windowing.TupleWindow;

import java.util.HashMap;
import java.util.Map;

/**
 * Compte les requêtes par URL et calcule la latence moyenne sur une fenêtre
 * tumbling de 10 s (la taille est réglée dans LogStormTopology avec withTumblingWindow).
 *
 * BaseWindowedBolt : Storm accumule les tuples et appelle execute(TupleWindow)
 * une fois par fenêtre, au lieu d'une fois par tuple.
 */
public class PageCountBolt extends BaseWindowedBolt {

    private OutputCollector collector;

    @Override
    public void prepare(Map<String, Object> topoConf, TopologyContext context, OutputCollector collector) {
        this.collector = collector;
    }

    @Override
    public void execute(TupleWindow window) {
        Map<String, long[]> stats = new HashMap<>();    // url -> [nombre, somme des latences]
        for (Tuple t : window.get()) {                  // tous les tuples des 10 dernières secondes
            long[] s = stats.computeIfAbsent(t.getStringByField("url"), k -> new long[2]);
            s[0]++;
            s[1] += t.getLongByField("latency");
        }
        // Un tuple de sortie par URL : (url, count, avgLatency)
        stats.forEach((url, s) -> collector.emit(new Values(url, s[0], s[1] / s[0])));
        // Pas d'ack manuel : les tuples de la fenêtre sont acquittés automatiquement
        // quand la fenêtre expire.
    }

    @Override
    public void declareOutputFields(OutputFieldsDeclarer declarer) {
        declarer.declare(new Fields("url", "count", "avgLatency"));
    }
}
```

💡 **Ce qu'il faut comprendre**

1. **`BaseWindowedBolt`** : Storm accumule les tuples et appelle `execute(TupleWindow)` **une fois
   par fenêtre** (et non une fois par tuple).
2. **La taille de la fenêtre n'est pas ici** : on la règle dans la topologie avec
   `.withTumblingWindow(Duration.seconds(10))`. *Tumbling* = tranches de 10 s **sans
   chevauchement**.
3. Pour chaque URL, on émet `(url, count, avgLatency)`. Les tuples de la fenêtre sont ackés
   **automatiquement** quand elle se ferme.

## Étape 12 — Le bolt d'alerte : `ErrorAlertBolt.java`

📄 **`src/main/java/com/bigdata/logstorm/bolt/ErrorAlertBolt.java`** :
```java
package com.bigdata.logstorm.bolt;

import org.apache.storm.task.TopologyContext;
import org.apache.storm.topology.BasicOutputCollector;
import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.topology.base.BaseBasicBolt;
import org.apache.storm.tuple.Fields;
import org.apache.storm.tuple.Tuple;
import org.apache.storm.tuple.Values;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;

/**
 * Lève une ALERTE quand il y a plus de 15 erreurs 5xx sur 10 secondes glissantes
 * (au plus une alerte toutes les 10 s pour ne pas inonder les logs).
 *
 * BaseBasicBolt : Storm ancre et ack automatiquement chaque tuple à la fin de execute().
 */
public class ErrorAlertBolt extends BaseBasicBolt {

    private static final Logger LOG = LoggerFactory.getLogger(ErrorAlertBolt.class);

    private static final int THRESHOLD = 15;            // seuil : > 15 erreurs...
    private static final long WINDOW_MS = 10_000;       // ...sur 10 s glissantes
    private static final long COOLDOWN_MS = 10_000;     // délai minimal entre deux alertes

    private transient Deque<Long> errors;               // horodatages des erreurs récentes
    private long lastAlert;

    @Override
    public void prepare(Map<String, Object> topoConf, TopologyContext context) {
        errors = new ArrayDeque<>();
    }

    @Override
    public void execute(Tuple tuple, BasicOutputCollector collector) {
        long now = System.currentTimeMillis();
        errors.addLast(now);
        // Fenêtre glissante "maison" : on retire les erreurs de plus de 10 s
        while (!errors.isEmpty() && now - errors.peekFirst() > WINDOW_MS) {
            errors.pollFirst();
        }

        if (errors.size() > THRESHOLD && now - lastAlert > COOLDOWN_MS) {
            lastAlert = now;
            String msg = String.format("%d erreurs 5xx en 10 s (dernière sur %s, statut %d)",
                    errors.size(), tuple.getStringByField("url"), tuple.getIntegerByField("status"));
            LOG.warn("[ALERTE] {}", msg);
            collector.emit(new Values(msg, now));   // un bolt aval pourrait envoyer un mail, Slack...
        }
    }

    @Override
    public void declareOutputFields(OutputFieldsDeclarer declarer) {
        declarer.declare(new Fields("alert", "ts"));
    }
}
```

💡 **Ce qu'il faut comprendre**

1. **`BaseBasicBolt`** : Storm ancre et acke **automatiquement** — plus simple que `BaseRichBolt`
   quand on émet au plus un tuple par entrée.
2. Une **fenêtre glissante faite main** : une file (`Deque`) d'horodatages ; à chaque erreur, on
   ajoute « maintenant » et on retire tout ce qui a plus de 10 s. La taille de la file est le
   nombre d'erreurs des 10 dernières secondes.
3. Au-delà de **15** erreurs, et si la dernière alerte date de plus de 10 s : `[ALERTE]` dans les
   logs, et émission d'un tuple `(alert, ts)` qu'un autre bolt pourrait envoyer par mail ou Slack.

> 🔎 **À méditer** : à ~200 lignes/s, 2 % d'erreurs donnent déjà ~40 erreurs en 10 s, donc plus
> que 15. Vous verrez une alerte environ toutes les 10 s, et pas seulement pendant les rafales.
> Exercice : quel `THRESHOLD` choisir pour n'alerter **que** pendant les rafales ? (≈ 100)

## Étape 13 — Le bolt de rapport : `ReportBolt.java`

📄 **`src/main/java/com/bigdata/logstorm/bolt/ReportBolt.java`** :
```java
package com.bigdata.logstorm.bolt;

import org.apache.storm.Config;
import org.apache.storm.topology.BasicOutputCollector;
import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.topology.base.BaseBasicBolt;
import org.apache.storm.tuple.Tuple;
import org.apache.storm.utils.TupleUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Reçoit les comptes (url, count, avgLatency) des tasks page-count et affiche
 * toutes les 10 s le classement des pages, grâce à un TICK TUPLE.
 *
 * Un tick tuple est un tuple système envoyé par Storm à intervalle régulier :
 * il sert d'horloge à l'intérieur d'un bolt.
 */
public class ReportBolt extends BaseBasicBolt {

    private static final Logger LOG = LoggerFactory.getLogger(ReportBolt.class);

    private final Map<String, long[]> latest = new HashMap<>();   // url -> [count, avgLatency]

    /** Configuration propre à ce bolt : un tick tuple toutes les 10 s. */
    @Override
    public Map<String, Object> getComponentConfiguration() {
        Map<String, Object> conf = new HashMap<>();
        conf.put(Config.TOPOLOGY_TICK_TUPLE_FREQ_SECS, 10);
        return conf;
    }

    @Override
    public void execute(Tuple tuple, BasicOutputCollector collector) {
        // 1) Tick tuple : on affiche le classement de toutes les pages connues
        if (TupleUtils.isTick(tuple)) {
            if (latest.isEmpty()) return;               // aucune fenêtre fermée pour l'instant
            StringBuilder sb = new StringBuilder("\n[RAPPORT] Requêtes par page (dernière fenêtre de 10 s)\n");
            latest.entrySet().stream()
                  .sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]))   // tri décroissant
                  .forEach(e -> sb.append(String.format("  %-14s %5d req   latence moy. %4d ms%n",
                          e.getKey(), e.getValue()[0], e.getValue()[1])));
            LOG.info(sb.toString());
            return;
        }

        // 2) Tuple normal : le compte d'une fenêtre fermée pour une URL.
        //    On REMPLACE l'ancienne valeur : la map contient toujours, pour chaque page,
        //    le résultat de sa dernière fenêtre complète. Les 2 tasks page-count ne ferment
        //    pas leurs fenêtres exactement au même instant que le tick : en gardant la
        //    dernière valeur (au lieu de cumuler puis vider), chaque rapport reste complet.
        latest.put(tuple.getStringByField("url"),
                new long[]{tuple.getLongByField("count"), tuple.getLongByField("avgLatency")});
    }

    /** Bolt terminal : il n'émet rien. */
    @Override
    public void declareOutputFields(OutputFieldsDeclarer declarer) { }
}
```

💡 **Ce qu'il faut comprendre**

1. **Tick tuple** : `getComponentConfiguration()` demande à Storm d'envoyer à ce bolt un tuple
   **système** toutes les 10 s. C'est une horloge interne.
2. `TupleUtils.isTick(tuple)` distingue le tick des tuples normaux :
   - tuple normal → on **remplace** la valeur de l'URL par le résultat de sa dernière fenêtre ;
   - tick → on trie par nombre de requêtes et on affiche `[RAPPORT]`.
   Remplacer (au lieu de cumuler puis vider) garantit des rapports complets : les 2 tasks
   `page-count` ne ferment pas leurs fenêtres pile au moment du tick.
3. Bolt **terminal** : `declareOutputFields` est vide, il n'émet rien.

## Étape 14 — L'assemblage : `LogStormTopology.java`

📄 **`src/main/java/com/bigdata/logstorm/LogStormTopology.java`** :
```java
package com.bigdata.logstorm;

import com.bigdata.logstorm.bolt.ErrorAlertBolt;
import com.bigdata.logstorm.bolt.PageCountBolt;
import com.bigdata.logstorm.bolt.ParseBolt;
import com.bigdata.logstorm.bolt.ReportBolt;
import com.bigdata.logstorm.spout.LogSpout;
import org.apache.storm.Config;
import org.apache.storm.StormSubmitter;
import org.apache.storm.topology.TopologyBuilder;
import org.apache.storm.topology.base.BaseWindowedBolt.Duration;
import org.apache.storm.tuple.Fields;

/**
 * Point d'entrée : construit le DAG (la topologie) et le soumet à Nimbus.
 *
 * <pre>
 * log-spout --shuffle--> parse --fields(url)--> page-count (10 s) --global--> report
 *                          \--stream "errors", global--> error-alert
 * </pre>
 *
 * Mode local  : storm local --local-ttl 60 logstorm-1.0.jar com.bigdata.logstorm.LogStormTopology
 * Mode cluster: storm jar logstorm-1.0.jar com.bigdata.logstorm.LogStormTopology logstorm
 */
public class LogStormTopology {

    public static void main(String[] args) throws Exception {
        // Nom de la topologie : 1er argument, sinon "logstorm"
        String name = args.length > 0 ? args[0] : "logstorm";

        // Le TopologyBuilder sert à déclarer les nœuds (spouts/bolts) et les arêtes (groupings)
        TopologyBuilder builder = new TopologyBuilder();

        // SOURCE : 1 executor qui génère les lignes de log
        builder.setSpout("log-spout", new LogSpout(), 1);

        // PARSING : 2 executors (threads) mais 4 tasks (instances).
        // Avoir plus de tasks que d'executors permet de monter jusqu'à 4 executors
        // à chaud avec "storm rebalance ... -e parse=4", sans redéployer.
        // shuffleGrouping : répartition aléatoire équilibrée (pas besoin de clé ici).
        builder.setBolt("parse", new ParseBolt(), 2)
               .setNumTasks(4)
               .shuffleGrouping("log-spout");

        // COMPTAGE : fenêtre tumbling de 10 s (tranches sans chevauchement).
        // fieldsGrouping sur "url" : une même URL arrive TOUJOURS sur la même task,
        // sinon chaque task n'aurait qu'un compte partiel.
        builder.setBolt("page-count",
                        new PageCountBolt().withTumblingWindow(Duration.seconds(10)), 2)
               .fieldsGrouping("parse", new Fields("url"));

        // ALERTE : abonné au stream nommé "errors" (et non au stream par défaut).
        // globalGrouping : tout arrive sur une seule task -> un seul compteur global.
        builder.setBolt("error-alert", new ErrorAlertBolt(), 1)
               .globalGrouping("parse", ParseBolt.ERROR_STREAM);

        // RAPPORT : rassemble les comptes des 2 tasks page-count en un seul classement
        builder.setBolt("report", new ReportBolt(), 1)
               .globalGrouping("page-count");

        Config conf = new Config();
        conf.setNumWorkers(2);           // 2 JVM workers (réparties sur les supervisors)
        // Max de tuples "en vol" (émis mais pas encore ackés) par task de spout.
        // ATTENTION avec un bolt fenêtré : ses tuples ne sont ackés qu'à la FERMETURE de la
        // fenêtre (10 s). Il faut donc pending > débit x durée de rétention
        // (~200/s x ~20 s = 4000). Avec 1000, le spout serait bloqué par à-coups
        // (~45 lignes/s au lieu de 200) et les comptes par fenêtre seraient faussés.
        conf.setMaxSpoutPending(5000);
        conf.setMessageTimeoutSecs(30);  // un tuple non acké en 30 s => fail() puis rejeu.
                                         // Doit être > durée de fenêtre (10 s) pour les bolts fenêtrés.

        // Soumission à Nimbus. Avec "storm local", cet appel est intercepté
        // et la topologie tourne dans un LocalCluster en mémoire (une seule JVM).
        StormSubmitter.submitTopology(name, conf, builder.createTopology());
    }
}
```

💡 **Ce qu'il faut comprendre** — c'est le fichier le plus important à présenter.

| Ligne | Signification |
|---|---|
| `setSpout("log-spout", new LogSpout(), 1)` | un nœud du graphe : nom affiché dans l'UI, objet, **nombre d'executors** (threads) |
| `setBolt("parse", ..., 2).setNumTasks(4)` | 2 threads, 4 instances : on pourra monter à 4 threads **à chaud** |
| `.shuffleGrouping("log-spout")` | arête : répartition **aléatoire** équilibrée |
| `.fieldsGrouping("parse", new Fields("url"))` | même URL → **même task** (sinon les comptes seraient partiels) |
| `.globalGrouping("parse", ParseBolt.ERROR_STREAM)` | s'abonne au stream **`errors`**, tout vers une seule task |
| `setNumWorkers(2)` | 2 JVM, réparties sur les 2 supervisors |
| `setMaxSpoutPending(5000)` | au plus 5000 tuples en cours de traitement : contre-pression. Doit dépasser débit × rétention de la fenêtre (avec 1000, le débit tombait à ~45 lignes/s) |
| `setMessageTimeoutSecs(30)` | délai avant `fail()` ; doit dépasser la fenêtre de 10 s |
| `StormSubmitter.submitTopology(...)` | envoie la topologie à Nimbus |

> Il n'y a **pas** de `LocalCluster` dans le code : la commande `storm local` intercepte
> `submitTopology` et lance tout en mémoire. **Le même jar** sert au test local et au cluster.

✅ Vous avez maintenant 6 fichiers Java :
```
src/main/java/com/bigdata/logstorm/
├── LogStormTopology.java
├── spout/LogSpout.java
└── bolt/ParseBolt.java, PageCountBolt.java, ErrorAlertBolt.java, ReportBolt.java
```

---

# Partie D — Exécuter

## Étape 15 — Compiler (sans installer Java)

Maven tourne dans un conteneur qui contient Java 25. On monte le dossier du projet sur `/app`
et le cache Maven (`.m2`) pour ne pas retélécharger les dépendances à chaque fois.

⌨️ **PowerShell**
```powershell
docker run --rm -v "${PWD}:/app" -v "$env:USERPROFILE\.m2:/root/.m2" -w /app maven:3.9-eclipse-temurin-25 mvn -q package
```

⌨️ **Bash** (macOS / Linux)
```bash
docker run --rm -v "$PWD":/app -v "$HOME/.m2":/root/.m2 -w /app maven:3.9-eclipse-temurin-25 mvn -q package
```

⌨️ **Git Bash** (Windows) : il faut donner à Docker des chemins Windows
```bash
export MSYS_NO_PATHCONV=1
docker run --rm -v "$(pwd -W)":/app -v "$(cygpath -w ~/.m2)":/root/.m2 -w /app maven:3.9-eclipse-temurin-25 mvn -q package
```

La première fois, l'image Maven (~500 Mo) et les dépendances sont téléchargées : 1 à 3 minutes.
`-q` (*quiet*) : Maven n'affiche rien si tout va bien.

✅ Le fichier `target/logstorm-1.0.jar` existe (~15 Ko) :
```bash
ls -l target/logstorm-1.0.jar        # PowerShell : Get-Item target\logstorm-1.0.jar
```

> ❌ **`PKIX path building failed`** → votre antivirus (Avast, AVG, Kaspersky...) intercepte le
> HTTPS. Solution pas à pas : [08-depannage.md § Antivirus](08-depannage.md#antivirus--pkix-path-building-failed).
>
> ❌ **`cannot find symbol` / `';' expected`** → une faute de frappe dans un fichier Java :
> Maven indique le fichier et la ligne. Relancez sans `-q` pour plus de détails.

💡 Grâce au volume `./target:/jars` du `docker-compose.yml`, le jar est **déjà visible** dans le
conteneur nimbus sous `/jars/logstorm-1.0.jar`. Vérifiez :
```bash
docker compose exec nimbus ls -l /jars
```

## Étape 16 — Tester en mode local (60 s)

Le mode local fait tourner **toute la topologie dans une seule JVM**, sans passer par les
supervisors. C'est la première chose à faire après chaque modification.

⌨️ (ajoutez `-T` après `exec` sous Git Bash)
```bash
docker compose exec nimbus storm local --local-ttl 60 /jars/logstorm-1.0.jar com.bigdata.logstorm.LogStormTopology
```

Beaucoup de logs de démarrage défilent ; au bout de ~20 s apparaissent nos messages.

✅ Toutes les 10 s, un bloc `[RAPPORT]` :
```
[RAPPORT] Requêtes par page (dernière fenêtre de 10 s)
  /produits        245 req   latence moy.  147 ms
  /api/stock       234 req   latence moy.  168 ms
  /                228 req   latence moy.  166 ms
  ...                                          (les 8 pages)
```
✅ Des lignes `[ALERTE]` : ~40 erreurs hors rafale, plusieurs centaines pendant une rafale :
```
WARN  c.b.l.b.ErrorAlertBolt - [ALERTE] 39 erreurs 5xx en 10 s (dernière sur /api/stock, statut 500)
WARN  c.b.l.b.ErrorAlertBolt - [ALERTE] 340 erreurs 5xx en 10 s (dernière sur /, statut 500)
```
(Sortie réelle relevée pendant le TP : 8 rapports et 10 alertes en 100 s.)
✅ Après 60 s, le programme s'arrête tout seul.

> Astuce pour ne voir que nos lignes (Bash) : ajoutez `| grep -E "RAPPORT|ALERTE| req "` à la fin.
> En PowerShell : `| Select-String "RAPPORT|ALERTE| req "`.

## Étape 17 — Déployer sur le cluster

⌨️
```bash
docker compose exec nimbus storm jar /jars/logstorm-1.0.jar com.bigdata.logstorm.LogStormTopology logstorm
docker compose exec nimbus storm list
```

✅ `storm list` affiche :
```
Topology_name        Status     Num_tasks  Num_workers  Uptime_secs  Topology_Id          Owner
----------------------------------------------------------------------------------------
logstorm             ACTIVE     11         2            11           logstorm-2-1795485296 storm
```

💡 **Ce qui vient de se passer** :
1. `storm jar` exécute notre `main` ; `StormSubmitter` envoie le jar et la description du graphe
   à **Nimbus** (port 6627).
2. Nimbus calcule le placement : quel executor dans quel worker, sur quel supervisor.
   Il écrit ces **assignations** dans **ZooKeeper**.
3. Chaque **supervisor** lit ses assignations, télécharge le jar et démarre ses **workers** (JVM).
4. Les tuples circulent ensuite **directement entre workers**, sans passer par Nimbus.

Contrairement au mode local, **la topologie ne s'arrête jamais** : elle tourne jusqu'à `storm kill`.

## Étape 18 — Lire les logs des workers

Sur le cluster, les `LOG.info(...)` vont dans le fichier `worker.log` de chaque worker, à
l'intérieur des conteneurs supervisor. Attendez ~30 s après le déploiement, puis :

⌨️ Supervisor 1 :
```bash
docker compose exec supervisor sh -c "tail -n 50 -F /logs/workers-artifacts/*/*/worker.log" | grep -E "RAPPORT|ALERTE| req "
```
⌨️ Supervisor 2 (dans un autre terminal) :
```bash
docker compose exec --index 2 supervisor sh -c "tail -n 50 -F /logs/workers-artifacts/*/*/worker.log" | grep -E "RAPPORT|ALERTE| req "
```
(PowerShell : remplacez `| grep -E "..."` par `| Select-String "RAPPORT|ALERTE| req "` ;
Git Bash : ajoutez `-T` après `exec`.)

✅ Les `[RAPPORT]` apparaissent sur **l'un** des deux supervisors (celui qui héberge `report`),
les `[ALERTE]` sur celui qui héberge `error-alert` — parfois le même. `Ctrl+C` pour quitter.

## Étape 19 — Observer dans Storm UI

<http://localhost:8080> → **F5** → cliquez **logstorm** :

1. **Topology summary** : *Status* ACTIVE, *Num workers* 2.
2. Cliquez **10m 0s** dans *Topology stats*.
3. Tableau **Spouts** : *Emitted* et *Acked* augmentent, *Failed* = 0.
4. Tableau **Bolts** : *Capacity* bien en dessous de 1 (aucun bolt saturé).
5. **Show Visualization** : le DAG en direct, avec la flèche du stream `errors`.
6. Cliquez **page-count** : 2 executors, avec leur *Host* et leur *Port* (= leur worker).

✅ Vous retrouvez dans l'UI exactement le graphe écrit dans `LogStormTopology`.

---

# Partie E — Expérimenter

## Étape 20 — Changer le parallélisme à chaud (rebalance)

⌨️
```bash
docker compose exec nimbus storm rebalance logstorm -w 5 -n 3 -e parse=4
```
- `-w 5` : attendre 5 s (le temps de vider les tuples en cours) ;
- `-n 3` : passer à **3 workers** ;
- `-e parse=4` : passer à **4 executors** pour `parse`.

✅ Après ~30 s (mesuré), dans l'UI : *Num workers* = 3, et la page `parse` montre 4 executors,
répartis sur les deux supervisors. Les compteurs (*Emitted*, *Acked*...) repartent de zéro.

💡 C'est possible **uniquement** parce que `parse` a été déclaré avec `.setNumTasks(4)` : le
nombre d'executors ne peut jamais dépasser le nombre de tasks, fixé au déploiement.
Essayez `-e page-count=4` : refusé ou sans effet, car `page-count` n'a que 2 tasks.

## Étape 21 — Simuler la panne d'une machine

⌨️
```bash
docker stop logstorm-supervisor-2
```
Rafraîchissez la page d'accueil de Storm UI : le supervisor disparaît en quelques secondes ;
la réassignation complète des workers prend ~1 min 30 (mesuré pendant le TP).

✅ *Supervisor Summary* n'affiche plus qu'**un** supervisor ; Nimbus a **réassigné** les workers
sur `supervisor-1` ; les `[RAPPORT]` continuent ; *Failed* augmente (≈ 4 900 pendant le TP) et
le spout loggue des milliers de `Rejeu du message ...` : les tuples perdus sont **rejoués**.

⌨️ Remettez la machine :
```bash
docker start logstorm-supervisor-2
```
✅ Après quelques secondes, 2 supervisors à nouveau.

💡 Nimbus a détecté la panne parce que les **heartbeats** du supervisor ne sont plus arrivés
dans **ZooKeeper**. C'est la tolérance aux pannes de Storm, sans aucune ligne de code de notre part.

## Étape 22 — Arrêter proprement

⌨️
```bash
docker compose exec nimbus storm kill logstorm -w 5     # arrête la topologie
docker compose exec nimbus storm list                   # "No topologies running."
docker compose down                                     # arrête et supprime les conteneurs
```

✅ Le projet complet fonctionne. Les parties suivantes servent à **automatiser** ces commandes
et à **publier** le projet.

---

# Partie F — Automatiser et publier

## Étape 23 — Les scripts

Taper ces longues commandes à chaque fois est source d'erreurs, surtout en démo. On les range
dans des scripts. Les `.sh` sont pour Bash (Git Bash, macOS, Linux), les `.ps1` pour PowerShell.

📄 **`scripts/build.sh`** — compilation via l'image Maven :
```bash
#!/usr/bin/env bash
# Compile le projet SANS Java ni Maven installés : Maven tourne dans un conteneur.
# Résultat : target/logstorm-1.0.jar
#
# Option : si un antivirus (Avast, Kaspersky...) ou un proxy intercepte le HTTPS,
# Maven échoue avec "PKIX path building failed". Exportez son certificat racine
# (voir docs/08-depannage.md) puis : EXTRA_CA=chemin/vers/racine.cer ./scripts/build.sh
set -euo pipefail
cd "$(dirname "$0")/.."

export MSYS_NO_PATHCONV=1            # Git Bash (Windows) : ne pas réécrire /app en C:/...
SRC="$PWD"; M2="$HOME/.m2"; CA="${EXTRA_CA:-}"
if command -v cygpath >/dev/null 2>&1; then   # Git Bash : chemins Windows pour Docker
  SRC="$(cygpath -w "$SRC")"; M2="$(cygpath -w "$M2")"
  [ -n "$CA" ] && CA="$(cygpath -w "$CA")"
fi
mkdir -p "$HOME/.m2"

IMAGE=maven:3.9-eclipse-temurin-25
echo ">> Compilation Maven dans le conteneur $IMAGE..."
if [ -z "$CA" ]; then
  docker run --rm -v "$SRC":/app -v "$M2":/root/.m2 -w /app $IMAGE mvn -q package
else
  echo ">> Certificat supplémentaire : $CA"
  docker run --rm -v "$SRC":/app -v "$M2":/root/.m2 -v "$CA":/extra-ca.cer:ro -w /app $IMAGE sh -c '
    cp "$JAVA_HOME/lib/security/cacerts" /tmp/ts &&
    keytool -importcert -noprompt -alias extra -file /extra-ca.cer -keystore /tmp/ts -storepass changeit >/dev/null &&
    mvn -q -Djavax.net.ssl.trustStore=/tmp/ts -Djavax.net.ssl.trustStorePassword=changeit package'
fi

ls -l target/logstorm-1.0.jar
echo ">> OK : target/logstorm-1.0.jar"
```

📄 **`scripts/build.ps1`** :
```powershell
# Compile le projet SANS Java ni Maven installés (PowerShell).
# Résultat : target\logstorm-1.0.jar
#
# Option : antivirus/proxy qui intercepte le HTTPS ("PKIX path building failed") :
#   .\scripts\build.ps1 -ExtraCa C:\chemin\racine.cer     (voir docs\08-depannage.md)
param([string]$ExtraCa = "")
$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")

$m2 = Join-Path $env:USERPROFILE ".m2"
New-Item -ItemType Directory -Force $m2 | Out-Null
$image = "maven:3.9-eclipse-temurin-25"

Write-Host ">> Compilation Maven dans le conteneur $image..."
if ($ExtraCa -eq "") {
    docker run --rm -v "${PWD}:/app" -v "${m2}:/root/.m2" -w /app $image mvn -q package
} else {
    $ca = (Resolve-Path $ExtraCa).Path
    $cmd = 'cp "$JAVA_HOME/lib/security/cacerts" /tmp/ts && keytool -importcert -noprompt -alias extra -file /extra-ca.cer -keystore /tmp/ts -storepass changeit >/dev/null && mvn -q -Djavax.net.ssl.trustStore=/tmp/ts -Djavax.net.ssl.trustStorePassword=changeit package'
    docker run --rm -v "${PWD}:/app" -v "${m2}:/root/.m2" -v "${ca}:/extra-ca.cer:ro" -w /app $image sh -c $cmd
}
if ($LASTEXITCODE -ne 0) { throw "Echec de la compilation" }

Get-Item target\logstorm-1.0.jar
Write-Host ">> OK : target\logstorm-1.0.jar"
```

📄 **`scripts/local.sh`** — mode local (60 s par défaut) :
```bash
#!/usr/bin/env bash
# Mode local : toute la topologie dans UNE seule JVM (dans le conteneur nimbus),
# pendant 60 s (ou la durée passée en argument), puis arrêt automatique.
set -euo pipefail
cd "$(dirname "$0")/.."
export MSYS_NO_PATHCONV=1
TTL="${1:-60}"

docker compose exec -T nimbus storm local --local-ttl "$TTL" \
  /jars/logstorm-1.0.jar com.bigdata.logstorm.LogStormTopology
```

📄 **`scripts/local.ps1`** :
```powershell
# Mode local : toute la topologie dans une seule JVM pendant 60 s (PowerShell).
# Usage : .\scripts\local.ps1 [secondes]
param([int]$Ttl = 60)
Set-Location (Join-Path $PSScriptRoot "..")

docker compose exec -T nimbus storm local --local-ttl $Ttl /jars/logstorm-1.0.jar com.bigdata.logstorm.LogStormTopology
```

📄 **`scripts/deploy.sh`** — `storm jar` puis `storm list` :
```bash
#!/usr/bin/env bash
# Mode cluster : envoie le jar à Nimbus qui répartit la topologie sur les supervisors.
# Usage : scripts/deploy.sh [nom-topologie]   (défaut : logstorm)
set -euo pipefail
cd "$(dirname "$0")/.."
export MSYS_NO_PATHCONV=1
NAME="${1:-logstorm}"

docker compose exec -T nimbus storm jar /jars/logstorm-1.0.jar \
  com.bigdata.logstorm.LogStormTopology "$NAME"

echo ">> Topologies sur le cluster :"
docker compose exec -T nimbus storm list
```

📄 **`scripts/deploy.ps1`** :
```powershell
# Mode cluster : envoie le jar à Nimbus, puis liste les topologies (PowerShell).
# Usage : .\scripts\deploy.ps1 [nom-topologie]
param([string]$Name = "logstorm")
Set-Location (Join-Path $PSScriptRoot "..")

docker compose exec -T nimbus storm jar /jars/logstorm-1.0.jar com.bigdata.logstorm.LogStormTopology $Name
if ($LASTEXITCODE -ne 0) { throw "Echec du deploiement" }

Write-Host ">> Topologies sur le cluster :"
docker compose exec -T nimbus storm list
```

📄 **`scripts/logs.sh`** — suit les logs des 2 supervisors en même temps, filtrés :
```bash
#!/usr/bin/env bash
# Suit les worker.log des 2 supervisors et ne garde que les lignes [RAPPORT] / [ALERTE]
# (et les lignes du classement). Ctrl+C pour quitter.
# Les workers démarrent ~20 s après "storm jar" : relancer si "No such file".
set -uo pipefail
cd "$(dirname "$0")/.."
export MSYS_NO_PATHCONV=1

follow() {   # $1 = numéro du supervisor
  docker compose exec -T --index "$1" supervisor \
    sh -c 'tail -n 50 -F /logs/workers-artifacts/*/*/worker.log 2>/dev/null' \
  | grep --line-buffered -E "RAPPORT|ALERTE| req " \
  | sed -u "s/^/[supervisor-$1] /"
}

follow 1 &
follow 2 &
trap 'kill $(jobs -p) 2>/dev/null' INT TERM EXIT
wait
```

📄 **`scripts/chaos.sh`** — panne de `supervisor-2`, attente, retour :
```bash
#!/usr/bin/env bash
# Simule la panne d'une machine : arrête supervisor-2, attend, puis le redémarre.
# Pendant l'attente, regarder Storm UI : Nimbus réassigne les workers sur supervisor-1.
# Usage : scripts/chaos.sh [secondes]   (défaut : 60)
set -euo pipefail
WAIT="${1:-60}"
NODE=logstorm-supervisor-2

echo ">> Panne : docker stop $NODE"
docker stop "$NODE"
echo ">> Attente ${WAIT}s : rafraîchir Supervisor Summary dans Storm UI (http://localhost:8080)"
for ((i = WAIT; i > 0; i -= 10)); do echo "   ... encore ${i}s"; sleep $(( i < 10 ? i : 10 )); done
echo ">> Retour de la machine : docker start $NODE"
docker start "$NODE"
```

⌨️ Rendez les scripts Bash exécutables (macOS / Linux / Git Bash) :
```bash
chmod +x scripts/*.sh
```

> PowerShell refuse d'exécuter les scripts ? `Set-ExecutionPolicy -Scope CurrentUser RemoteSigned`.
>
> ⚠️ Les fichiers `.sh` doivent avoir des fins de ligne **LF** (et non CRLF) : dans VS Code, en
> bas à droite, cliquez sur `CRLF` et choisissez `LF`. Sinon : `bad interpreter: /usr/bin/env: 'bash\r'`.

✅ Testez : `./scripts/build.sh` (ou `.\scripts\build.ps1`) affiche `>> OK : target/logstorm-1.0.jar`.

## Étape 24 — Le Makefile (raccourcis)

📄 **`Makefile`** :
```makefile
# Raccourcis pour LogStorm. Linux / macOS / Git Bash avec make.
# Sous Windows sans make : utiliser directement scripts/*.ps1 ou scripts/*.sh
# (voir docs/07-commandes.md).

TOPO   ?= logstorm
EXEC   := docker compose exec -T nimbus
export MSYS_NO_PATHCONV=1

.PHONY: help up up2 ps build local deploy list logs rebalance chaos heal kill down clean

help:        ## Affiche cette aide
	@grep -E '^[a-z0-9]+:.*##' $(MAKEFILE_LIST) | awk -F':.*## ' '{printf "  make %-10s %s\n", $$1, $$2}'

up:          ## Démarre le cluster (1 supervisor)
	docker compose up -d

up2:         ## Démarre le cluster avec 2 supervisors
	docker compose up -d --scale supervisor=2

ps:          ## État des conteneurs
	docker compose ps

build:       ## Compile target/logstorm-1.0.jar (Maven dans Docker)
	./scripts/build.sh

local:       ## Lance la topologie en mode local pendant 60 s
	./scripts/local.sh 60

deploy:      ## Déploie la topologie sur le cluster (storm jar + storm list)
	./scripts/deploy.sh $(TOPO)

list:        ## Liste les topologies (storm list)
	$(EXEC) storm list

logs:        ## Suit les [RAPPORT] / [ALERTE] des 2 supervisors
	./scripts/logs.sh

rebalance:   ## 3 workers et 4 executors pour parse, à chaud
	$(EXEC) storm rebalance $(TOPO) -w 5 -n 3 -e parse=4

chaos:       ## Panne de supervisor-2 pendant 60 s, puis retour
	./scripts/chaos.sh 60

heal:        ## Redémarre supervisor-2 (si chaos a été interrompu)
	docker start logstorm-supervisor-2

kill:        ## Arrête la topologie (après 5 s de drainage)
	$(EXEC) storm kill $(TOPO) -w 5

down:        ## Arrête et supprime les conteneurs
	docker compose down

clean:       ## Supprime target/
	rm -rf target
```

> ⚠️ Dans un Makefile, les lignes de commande sous une cible commencent par une **tabulation**,
> pas par des espaces (sinon : `missing separator`).
>
> `make` existe sous Linux et macOS. Sous Windows, il n'est pas installé par défaut : utilisez
> les scripts de l'étape 23.

✅ `make help` liste toutes les cibles ; `make up2 build deploy` refait les étapes 6, 15 et 17.

## Étape 25 — README, licence et documentation

- **`README.md`** : la page d'accueil du dépôt sur GitHub (voir celui du projet).
- **`LICENSE`** : texte de la licence Apache 2.0, téléchargeable sur
  <https://www.apache.org/licenses/LICENSE-2.0.txt>.
- **`LANCEMENT.md`** : le guide d'utilisation au quotidien.
- **`docs/`** : ce guide et les autres fiches (installation, cluster, UI, concepts, code, démo,
  commandes, dépannage, architecture), plus le support de cours PDF.

## Étape 26 — Versionner avec Git et publier sur GitHub

1. Sur <https://github.com> : **+** (en haut à droite) → **New repository** → nom `logstorm`,
   **Public**, ne cochez **rien** (pas de README, pas de licence) → **Create repository**.
2. ⌨️ Dans `logstorm/` :
```bash
git init
git add .
git commit -m "LogStorm : topologie Apache Storm + cluster Docker + docs"
git branch -M main
git remote add origin https://github.com/<votre-pseudo>/logstorm.git
git push -u origin main
```

✅ Le dépôt s'affiche sur GitHub avec le README et les diagrammes (GitHub affiche le Mermaid).

> Vérifiez avec `git status` **avant** le commit que `target/` n'apparaît pas : le `.gitignore` fait son travail.

---

## Récapitulatif

| Étape | Ce que vous avez appris |
|---|---|
| 5-7 | l'architecture d'un cluster Storm : ZooKeeper, Nimbus, Supervisors, UI |
| 9 | un spout fiable : msgId, ack, fail, rejeu |
| 10 | un bolt avec ancrage manuel et deux streams |
| 11 | le fenêtrage tumbling |
| 12-13 | une fenêtre glissante faite main, les tick tuples |
| 14 | la topologie : parallélisme (workers, executors, tasks) et groupings |
| 15-17 | le cycle compiler → tester en local → déployer |
| 19 | superviser avec Storm UI |
| 20-21 | scaler à chaud, tolérance aux pannes |

**Et maintenant ?** Les exercices de [06-demo.md](06-demo.md#exercices-pour-aller-plus-loin-tp)
(réglage du seuil d'alerte, fenêtre glissante, changement de grouping, rejeu forcé, 3 supervisors).
