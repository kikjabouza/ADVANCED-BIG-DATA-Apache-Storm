# 🚀 LANCEMENT — Utiliser et comprendre LogStorm

Ce fichier explique **comment lancer le projet** une fois qu'il est installé, **ce que fait
chaque commande**, **ce qui se passe dans le cluster** et **comment lire les résultats**.

> Première fois ? Installez d'abord Docker Desktop : [docs/01-installation.md](docs/01-installation.md).
> Vous voulez construire le projet vous-même depuis zéro ? [docs/00-realisation-pas-a-pas.md](docs/00-realisation-pas-a-pas.md).

---

## 1. En 30 secondes : ce que fait le projet

Un faux site e-commerce produit ~175 à 200 lignes de log par seconde. Une **topologie Storm** les
analyse **au fil de l'eau** et :

- affiche toutes les **10 s** le **classement des pages** les plus visitées (`[RAPPORT]`) ;
- lève une **alerte** dès qu'il y a plus de **15 erreurs 5xx en 10 s** (`[ALERTE]`).

Le tout tourne sur un **vrai cluster Storm** (ZooKeeper, Nimbus, 2 Supervisors, UI) lancé sur
votre PC avec Docker.

```
log-spout ──shuffle──► parse ──fields(url)──► page-count (10 s) ──global──► report   → [RAPPORT]
                         └──stream "errors"──global──► error-alert                    → [ALERTE]
```

---

## 2. Avant de commencer : où taper les commandes ?

Ouvrez un terminal **dans le dossier `logstorm/`** (celui qui contient `docker-compose.yml`).

| Vous avez... | Utilisez | Exemple |
|---|---|---|
| Windows + PowerShell | les scripts `.ps1` | `.\scripts\deploy.ps1` |
| Windows + Git Bash | les scripts `.sh` | `bash scripts/deploy.sh` |
| macOS / Linux (ou `make` installé) | le Makefile | `make deploy` |

Vérifiez que **Docker Desktop est ouvert** et affiche **Engine running**.

---

## 3. Lancer le projet : les 6 étapes

### Étape 1 — Démarrer le cluster

| make | PowerShell / Git Bash |
|---|---|
| `make up2` | `docker compose up -d --scale supervisor=2` |

Puis vérifiez : `make ps` ou `docker compose ps` → **5 conteneurs** `Up`.

**Ce qui se passe :** Docker démarre 5 conteneurs sur un réseau privé :
`zookeeper` (coordination), `nimbus` (maître), `supervisor-1` et `supervisor-2` (machines de
travail, 4 slots chacune) et `ui` (site web). Les supervisors s'enregistrent auprès de ZooKeeper
et envoient des **heartbeats** (« je suis vivant ») toutes les quelques secondes.

⏱️ Comptez 20 à 30 s avant que tout soit prêt.

### Étape 2 — Ouvrir Storm UI

Navigateur → **<http://localhost:8080>**

Vous devez voir : *Supervisors* = **2**, *Free slots* = **8**, aucune topologie.
Guide de l'interface : [docs/03-storm-ui.md](docs/03-storm-ui.md).

### Étape 3 — Compiler le programme

| make | PowerShell | Git Bash |
|---|---|---|
| `make build` | `.\scripts\build.ps1` | `bash scripts/build.sh` |

Résultat : `target/logstorm-1.0.jar`.

**Ce qui se passe :** un conteneur `maven` (qui contient Java 25) compile les 6 classes Java et
les emballe dans un jar. Comme le dossier `target/` est **monté** dans le conteneur nimbus, le
jar y est immédiatement visible sous `/jars/logstorm-1.0.jar`.

> ❌ Erreur `PKIX path building failed` ? C'est votre antivirus : voir
> [docs/08-depannage.md](docs/08-depannage.md#antivirus--pkix-path-building-failed).

### Étape 4 — Tester en mode local (facultatif mais conseillé)

| make | PowerShell | Git Bash |
|---|---|---|
| `make local` | `.\scripts\local.ps1` | `bash scripts/local.sh` |

**Ce qui se passe :** la commande `storm local` lance **toute la topologie dans une seule JVM**,
pendant 60 s, puis s'arrête. Les `[RAPPORT]` et `[ALERTE]` s'affichent directement dans le
terminal, au milieu des logs de démarrage. C'est le moyen le plus rapide de vérifier que le code
fonctionne avant de le déployer.

### Étape 5 — Déployer sur le cluster

| make | PowerShell | Git Bash |
|---|---|---|
| `make deploy` | `.\scripts\deploy.ps1` | `bash scripts/deploy.sh` |

Le script termine par `storm list` → `logstorm   ACTIVE`.

**Ce qui se passe, dans l'ordre :**

1. `storm jar` exécute `LogStormTopology.main()`, qui construit le graphe et appelle
   `StormSubmitter.submitTopology(...)`.
2. Le jar et le graphe sont envoyés à **Nimbus**.
3. Nimbus décide du placement (« le spout et parse #1 dans le worker 1 sur supervisor-1... ») et
   l'écrit dans **ZooKeeper**.
4. Chaque **supervisor** lit ce qui lui est assigné, télécharge le jar et démarre ses
   **workers** (des JVM, une par slot utilisé).
5. Les tuples se mettent à circuler **directement d'un worker à l'autre**.

La topologie tourne ensuite **indéfiniment**, jusqu'à ce que vous la tuiez.

### Étape 6 — Voir les résultats

| make | Git Bash | PowerShell |
|---|---|---|
| `make logs` | `bash scripts/logs.sh` | voir la commande ci-dessous |

PowerShell (une commande par supervisor, dans deux fenêtres) :
```powershell
docker compose exec supervisor sh -c "tail -n 50 -F /logs/workers-artifacts/*/*/worker.log" | Select-String "RAPPORT|ALERTE| req "
docker compose exec --index 2 supervisor sh -c "tail -n 50 -F /logs/workers-artifacts/*/*/worker.log" | Select-String "RAPPORT|ALERTE| req "
```

⏱️ Attendez ~30 s après le déploiement (démarrage des workers). `Ctrl+C` pour quitter.

---

## 4. Comprendre ce que vous voyez

### Un bloc `[RAPPORT]` (toutes les 10 s)

```
[RAPPORT] Requêtes par page (dernière fenêtre de 10 s)
  /panier          247 req   latence moy.  134 ms
  /paiement        243 req   latence moy.  132 ms
  /api/stock       230 req   latence moy.  134 ms
  ...
```

| Élément | D'où ça vient |
|---|---|
| la liste des pages | `ParseBolt` a extrait l'URL de chaque ligne (sans `?q=storm`) |
| `241 req` | `PageCountBolt` a compté cette URL dans sa **fenêtre tumbling de 10 s** |
| `latence moy.` | moyenne des latences de la fenêtre, calculée par `PageCountBolt` |
| le tri décroissant et l'affichage toutes les 10 s | `ReportBolt`, réveillé par un **tick tuple** ; il garde pour chaque page le résultat de sa **dernière fenêtre complète** |

Mesuré : ~175 lignes/s × 10 s ≈ 1 750 lignes réparties sur 8 pages → environ **220 requêtes par page**.
Les chiffres sont proches car les pages sont tirées au hasard de façon uniforme.

### Une ligne `[ALERTE]`

```
[ALERTE] 39 erreurs 5xx en 10 s (dernière sur /api/stock, statut 500)     <- hors rafale
[ALERTE] 340 erreurs 5xx en 10 s (dernière sur /, statut 500)             <- pendant une rafale
```

- `ParseBolt` envoie chaque erreur 500/503 sur un **second stream** nommé `errors`.
- `ErrorAlertBolt` garde les heures des erreurs des **10 dernières secondes** (fenêtre glissante).
- Au-delà de **15**, il alerte — au maximum une fois toutes les 10 s.

> ℹ️ Avec 2 % d'erreurs « normales », on dépasse déjà 15 erreurs en 10 s : vous verrez donc
> une alerte environ **toutes les 10 s** (mesuré : ~30 à 45 erreurs hors rafale). Pendant les
> **rafales** (10 s toutes les 40 s, 35 % d'erreurs), le nombre affiché monte à **~300 à 560** :
> c'est ce chiffre qu'il faut montrer en démo. La latence moyenne du `[RAPPORT]` passe aussi de
> ~140 ms à ~700 ms pendant la rafale. Pour n'alerter **que** pendant les
> rafales, augmentez `THRESHOLD` (≈ 100) dans `ErrorAlertBolt.java`, recompilez, redéployez.

### Une ligne `Rejeu du message ...`

Un tuple n'a pas été entièrement traité en 30 s (ou un worker est tombé) : le spout le
**réémet**. C'est la garantie **at-least-once** de Storm : rien n'est perdu.

---

## 5. Jouer avec le cluster

| Action | make | Commande brute | Ce qu'on observe |
|---|---|---|---|
| **Scaler à chaud** | `make rebalance` | `docker compose exec nimbus storm rebalance logstorm -w 5 -n 3 -e parse=4` | UI : 3 workers, 4 executors pour `parse` — sans redéployer |
| **Panne d'une machine** | `make chaos` | `docker stop logstorm-supervisor-2` | UI : 1 supervisor au bout de quelques secondes ; workers réassignés en ~1 min 30 ; les rapports continuent ; *Failed* augmente et des milliers de `Rejeu du message` apparaissent |
| **Retour de la machine** | `make heal` | `docker start logstorm-supervisor-2` | UI : 2 supervisors |
| **Pause / reprise** | — | `docker compose exec nimbus storm deactivate logstorm` / `activate logstorm` | les `[RAPPORT]` s'arrêtent puis reprennent |
| **Ajouter une 3e machine** | — | `docker compose up -d --scale supervisor=3` | UI : *Free slots* passe à 12 |

**Pourquoi la panne ne casse rien :** le supervisor arrêté n'envoie plus de heartbeats à
ZooKeeper ; Nimbus le remarque et redémarre ses workers sur la machine restante. Les tuples
perdus pendant la bascule ne sont jamais ackés : après 30 s, le spout les **rejoue**.

---

## 6. Modifier le code et relancer

Le cycle de développement à retenir :

```
modifier le code → make build → make local (vérifier) → make kill → make deploy
```

`make kill` est nécessaire car on ne peut pas déployer deux topologies du même nom
(`Topology name logstorm is either not allowed or it already exists on the cluster`). Attendez ~10 s entre `kill` et `deploy`.

Idées de modifications : [docs/06-demo.md § Exercices](docs/06-demo.md#exercices-pour-aller-plus-loin-tp).

---

## 7. Tout arrêter

| make | Commande brute | Effet |
|---|---|---|
| `make kill` | `docker compose exec nimbus storm kill logstorm -w 5` | arrête la topologie (le cluster reste) |
| `make down` | `docker compose down` | arrête et supprime les 5 conteneurs |
| `make clean` | `rm -rf target` (PowerShell : `Remove-Item -Recurse target`) | supprime le jar compilé |

Les images Docker restent sur le disque : le prochain `make up2` sera instantané.

---

## 8. Mémo de session (à imprimer)

```
DÉMARRER   make up2      → http://localhost:8080
COMPILER   make build
TESTER     make local
DÉPLOYER   make deploy
REGARDER   make logs                  (Ctrl+C pour quitter)
SCALER     make rebalance
CASSER     make chaos
ARRÊTER    make kill  puis  make down
```
Sans make : [docs/07-commandes.md](docs/07-commandes.md). Un problème : [docs/08-depannage.md](docs/08-depannage.md).
