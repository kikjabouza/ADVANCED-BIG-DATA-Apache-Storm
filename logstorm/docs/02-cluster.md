# 02 — Le cluster Storm avec Docker Compose

Le fichier [`docker-compose.yml`](../docker-compose.yml) recrée sur un seul PC un vrai cluster
Storm : 1 ZooKeeper, 1 Nimbus, N Supervisors et l'interface web.

Schéma d'ensemble : voir le premier diagramme de [architecture.md](architecture.md).

## Les services un par un

### `zookeeper` — la mémoire partagée du cluster
```yaml
zookeeper:
  image: zookeeper:3.9
  restart: always
```
- Stocke l'état du cluster : topologies actives, assignations (quel executor sur quel
  supervisor/port), heartbeats des workers et supervisors.
- Port **2181**, uniquement sur le réseau interne Docker (pas besoin de l'exposer).
- **Le nom `zookeeper` est obligatoire** : le `storm.yaml` de l'image officielle contient
  `storm.zookeeper.servers: [zookeeper]`.
- En production : 3 ou 5 nœuds (quorum). Ici 1 suffit.

### `nimbus` — le maître
```yaml
nimbus:
  image: storm:${STORM_VERSION}
  command: storm nimbus
  depends_on: [zookeeper]
  restart: always
  ports: ["6627:6627"]
  volumes: ["./target:/jars"]
```
- Reçoit les `.jar` (`storm jar`), calcule le placement des executors (scheduler), écrit les
  assignations dans ZooKeeper, surveille les pannes et réassigne.
- Port **6627** : API Thrift utilisée par `storm jar`, `storm list`, l'UI...
- **Le nom `nimbus` est obligatoire** (`nimbus.seeds: [nimbus]` dans l'image).
- Le volume `./target:/jars` rend `target/logstorm-1.0.jar` visible dans le conteneur sous
  `/jars/logstorm-1.0.jar` : pas besoin de copier le jar après chaque compilation.
- C'est aussi dans ce conteneur qu'on tape toutes les commandes `storm ...` :
  `docker compose exec nimbus storm list`.

### `supervisor` — une machine de travail
```yaml
supervisor:
  image: storm:${STORM_VERSION}
  command: storm supervisor
  depends_on: [nimbus, zookeeper]
  restart: always
```
- Lit ses assignations dans ZooKeeper et démarre / arrête les **workers** (JVM).
- 4 **slots** par supervisor (ports 6700, 6701, 6702, 6703) = 4 workers au maximum.
- **Pas de `container_name`** : c'est ce qui permet `--scale supervisor=2`. Compose nomme
  alors les conteneurs `logstorm-supervisor-1`, `logstorm-supervisor-2`...
- Les logs des workers sont dans `/logs/workers-artifacts/<id-topologie>/<port>/worker.log`.

### `ui` — l'interface web
```yaml
ui:
  image: storm:${STORM_VERSION}
  command: storm ui
  depends_on: [nimbus]
  restart: always
  ports: ["8080:8080"]
```
- <http://localhost:8080> : état du cluster et des topologies (voir [03-storm-ui.md](03-storm-ui.md)).
- Interroge Nimbus en Thrift ; elle ne voit pas les tuples elle-même.

### Points communs
- `restart: always` : Storm est conçu *fail-fast* — un démon qui rencontre une erreur
  s'arrête et compte sur un superviseur de processus pour le relancer. Ici, c'est Docker.
- `name: logstorm` (en tête du fichier) fixe le préfixe des conteneurs.
- Tous les services sont sur le réseau `logstorm_default` et se trouvent par leur nom.

## Ports

| Port hôte | Service | Usage |
|---|---|---|
| 8080 | ui | Storm UI dans le navigateur |
| 6627 | nimbus | Thrift (soumission de topologies depuis l'hôte, optionnel ici) |
| — | zookeeper (2181) | interne uniquement |
| — | supervisor (6700-6703) | interne : communication entre workers |

## Démarrer, mettre à l'échelle, arrêter

```bash
docker compose up -d --scale supervisor=2   # ou : make up2
docker compose ps                           # 5 conteneurs "running"
docker compose exec nimbus storm version    # Storm 3.2.0
```

Résultat attendu de `docker compose ps` :

| Conteneur | Rôle | Port |
|---|---|---|
| `logstorm-zookeeper-1` | coordination | 2181 (interne) |
| `logstorm-nimbus-1` | maître, reçoit les .jar | 6627 |
| `logstorm-supervisor-1`, `-2` | lancent les workers (4 slots chacun) | interne |
| `logstorm-ui-1` | interface web | 8080 |

**`--scale`** change le nombre d'instances d'un service *sans modifier le fichier* :

```bash
docker compose up -d --scale supervisor=3   # ajouter une 3e machine à chaud
docker compose up -d --scale supervisor=1   # revenir à 1
```

> ⚠️ Un simple `docker compose up -d` (sans `--scale`) ramène à **1** supervisor.

Dans Docker Desktop : menu **Containers** → groupe **logstorm** → clic sur un conteneur →
onglet **Logs** (ou **Exec** pour ouvrir un terminal dedans).

Arrêt : `docker compose down` (ou `make down`). Les conteneurs sont supprimés, pas les images.

➡️ Suite : [03-storm-ui.md](03-storm-ui.md)
