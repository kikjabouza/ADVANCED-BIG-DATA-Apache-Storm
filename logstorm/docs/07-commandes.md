# 07 — Aide-mémoire des commandes

Toutes les commandes se lancent **depuis le dossier `logstorm/`**.
Les commandes `storm ...` s'exécutent **dans le conteneur nimbus** : on les préfixe par
`docker compose exec nimbus` (ajouter `-T` sous Git Bash ou dans un script).

## Correspondance Makefile / scripts / commande brute

| But | `make` | Script (Git Bash / PowerShell) | Commande brute |
|---|---|---|---|
| Démarrer (1 supervisor) | `make up` | — | `docker compose up -d` |
| Démarrer (2 supervisors) | `make up2` | — | `docker compose up -d --scale supervisor=2` |
| État des conteneurs | `make ps` | — | `docker compose ps` |
| Compiler | `make build` | `./scripts/build.sh` · `.\scripts\build.ps1` | `docker run --rm -v "$PWD":/app -v "$HOME/.m2":/root/.m2 -w /app maven:3.9-eclipse-temurin-25 mvn -q package` |
| Mode local 60 s | `make local` | `./scripts/local.sh [s]` · `.\scripts\local.ps1 [s]` | `docker compose exec nimbus storm local --local-ttl 60 /jars/logstorm-1.0.jar com.bigdata.logstorm.LogStormTopology` |
| Déployer | `make deploy` | `./scripts/deploy.sh [nom]` · `.\scripts\deploy.ps1 [nom]` | `docker compose exec nimbus storm jar /jars/logstorm-1.0.jar com.bigdata.logstorm.LogStormTopology logstorm` |
| Lister | `make list` | — | `docker compose exec nimbus storm list` |
| Logs [RAPPORT]/[ALERTE] | `make logs` | `./scripts/logs.sh` | `docker compose exec --index 1 supervisor sh -c 'tail -F /logs/workers-artifacts/*/*/worker.log'` (puis `--index 2`) |
| Scaler à chaud | `make rebalance` | — | `docker compose exec nimbus storm rebalance logstorm -w 5 -n 3 -e parse=4` |
| Panne + retour | `make chaos` | `./scripts/chaos.sh [s]` | `docker stop logstorm-supervisor-2` … `docker start logstorm-supervisor-2` |
| Retour seul | `make heal` | — | `docker start logstorm-supervisor-2` |
| Tuer la topologie | `make kill` | — | `docker compose exec nimbus storm kill logstorm -w 5` |
| Tout arrêter | `make down` | — | `docker compose down` |
| Nettoyer | `make clean` | — | `rm -rf target` |
| Aide | `make help` | — | — |

> **Windows sans `make`** : utilisez les scripts `.ps1` dans PowerShell, ou les `.sh` dans
> Git Bash (`bash scripts/logs.sh`). `make` est fourni sous Linux (`sudo apt install make`) et
> macOS (Command Line Tools).
>
> **PowerShell** : dans la commande de compilation brute, remplacez `"$PWD"` par `"${PWD}"` et
> `$HOME` par `$env:USERPROFILE` — ou utilisez simplement `.\scripts\build.ps1`.
>
> Si PowerShell refuse d'exécuter les scripts (*« l'exécution de scripts est désactivée »*) :
> `Set-ExecutionPolicy -Scope CurrentUser RemoteSigned`.

## Autres commandes `storm` utiles

| Commande | Effet |
|---|---|
| `storm version` | version du client Storm |
| `storm deactivate logstorm` | pause des spouts |
| `storm activate logstorm` | reprise |
| `storm rebalance logstorm -w 5 -n 2` | revenir à 2 workers |
| `storm kill logstorm -w 0` | arrêt immédiat |
| `storm set_log_level -l com.bigdata=DEBUG logstorm` | changer un niveau de log à chaud |
| `storm help` | toutes les commandes |

(à préfixer par `docker compose exec nimbus`)

## Docker

| Commande | Effet |
|---|---|
| `docker compose logs -f nimbus` | logs du démon Nimbus |
| `docker compose logs -f supervisor` | logs des supervisors (pas des workers) |
| `docker compose exec nimbus bash` | ouvrir un shell dans nimbus |
| `docker compose exec --index 2 supervisor ls /logs/workers-artifacts` | voir les topologies hébergées sur supervisor-2 |
| `docker compose up -d --scale supervisor=3` | ajouter un supervisor |
| `docker stats` | CPU / mémoire de chaque conteneur |

## Git

```bash
git init
git add .
git commit -m "LogStorm : topologie Apache Storm + cluster Docker + docs"
git branch -M main
git remote add origin https://github.com/<mon-pseudo>/logstorm.git
git push -u origin main
```
(créer d'abord le dépôt **vide** sur GitHub, sans README ni licence)

➡️ Suite : [08-depannage.md](08-depannage.md)
