# 08 — Dépannage

| Symptôme | Cause probable | Solution |
|---|---|---|
| `manifest for storm:3.2.0 not found` | tag absent sur Docker Hub | ouvrir <https://hub.docker.com/_/storm> → onglet **Tags**, prendre le plus récent ; l'écrire dans `.env` (`STORM_VERSION`) **et** dans `pom.xml` (`storm.version`) ; `java.release` = 25 pour Storm 3.x, 17 pour 2.8.x |
| `UnsupportedClassVersionError ... class file version 69.0` | jar compilé pour Java 25, cluster en Java 17 (Storm 2.8.x) | aligner `java.release` sur la version Java du cluster (17 pour Storm 2.8.x) puis recompiler |
| `PKIX path building failed` / `unable to find valid certification path` pendant `build` | un **antivirus** (Avast, AVG, Kaspersky, ESET...) ou un proxy d'entreprise **intercepte le HTTPS** ; Java dans le conteneur ne connaît pas son certificat | voir [§ Antivirus](#antivirus--pkix-path-building-failed) ci-dessous |
| `Bind for 0.0.0.0:8080 failed: port is already allocated` | une autre appli utilise 8080 (Tomcat, Jenkins, XAMPP...) | dans `docker-compose.yml`, service `ui` : `"8081:8080"`, puis ouvrir <http://localhost:8081> |
| Cluster Summary : **Free slots = 0** / topologie sans workers | pas assez de slots ou supervisors pas démarrés | `docker compose up -d --scale supervisor=2` ; vérifier *Supervisor Summary* |
| `Topology with name logstorm already exists` | ancienne version encore active | `make kill`, attendre ~10 s (`make list` vide), redéployer |
| Pas de `[RAPPORT]` dans `make logs` | les workers ne sont pas encore démarrés (~20 s), ou topologie non déployée | attendre 30 s et relancer `make logs` ; `make list` doit afficher ACTIVE ; en commande brute, essayer `--index 2` |
| `the input device is not a TTY` (Git Bash) | `docker compose exec` veut un terminal interactif | ajouter **`-T`** après `exec` (les scripts le font déjà) |
| Git Bash : chemins bizarres `C:/Program Files/Git/app` | Git Bash réécrit les chemins Unix | `export MSYS_NO_PATHCONV=1` (déjà dans les scripts) |
| Docker Desktop ne démarre pas / *WSL 2 installation is incomplete* | WSL 2 ou virtualisation absents | PowerShell **admin** : `wsl --install` puis redémarrer ; si besoin `wsl --update` ; activer *Intel VT-x* / *AMD SVM* dans le BIOS |
| `error during connect ... dockerDesktopLinuxEngine` | Docker Desktop n'est pas lancé | ouvrir Docker Desktop et attendre **Engine running** |
| `/jars/logstorm-1.0.jar` introuvable | jar non compilé, ou cluster lancé depuis un autre dossier | `make build`, puis vérifier `ls target/` ; lancer `docker compose` depuis `logstorm/` |
| Conteneurs qui redémarrent en boucle | pas assez de mémoire | Docker Desktop → Settings → Resources → Memory ≥ 4 Go |
| `Failed` qui augmente dans l'UI | timeouts (bolt saturé) ou panne de worker | normal après `make chaos` (rejeux) ; sinon regarder *Capacity* et augmenter le parallélisme |
| PowerShell : *l'exécution de scripts est désactivée* | politique d'exécution | `Set-ExecutionPolicy -Scope CurrentUser RemoteSigned` |

---

## Antivirus : « PKIX path building failed »

Certains antivirus analysent le trafic HTTPS : ils se placent entre vous et le site et signent
les certificats avec **leur propre autorité**. Windows lui fait confiance, mais pas Java dans le
conteneur Maven, d'où l'échec du téléchargement de `storm-client`.

Pour confirmer (Git Bash) :
```bash
echo | openssl s_client -connect repo.maven.apache.org:443 2>/dev/null | openssl x509 -noout -issuer
# issuer=... Avast Web/Mail Shield Root   → c'est l'antivirus
```

**Solution 1 — la plus simple** : désactiver temporairement l'analyse HTTPS le temps de la
première compilation (les dépendances restent ensuite en cache dans `~/.m2`).
Avast : **Menu → Paramètres → Protection → Agent Web** → décocher **Activer l'analyse HTTPS**.

**Solution 2 — sans toucher à l'antivirus** : exporter son certificat racine et le passer au build.

PowerShell :
```powershell
$c = Get-ChildItem Cert:\LocalMachine\Root, Cert:\CurrentUser\Root |
     Where-Object { $_.Subject -like "*Avast*" } | Select-Object -First 1
"-----BEGIN CERTIFICATE-----`n$([Convert]::ToBase64String($c.RawData,'InsertLineBreaks'))`n-----END CERTIFICATE-----" |
     Out-File -Encoding ascii $env:USERPROFILE\antivirus-root.cer

.\scripts\build.ps1 -ExtraCa $env:USERPROFILE\antivirus-root.cer
```
Git Bash (après l'export ci-dessus) :
```bash
EXTRA_CA="$HOME/antivirus-root.cer" ./scripts/build.sh
```
(remplacer `*Avast*` par le nom de votre antivirus ; ne **jamais** versionner ce fichier `.cer`)

---

## Réinitialiser complètement

```bash
make kill            # si une topologie tourne
docker compose down  # supprime les conteneurs et le réseau
make clean           # supprime target/
# optionnel, pour tout retélécharger :
docker rmi storm:3.2.0 zookeeper:3.9 maven:3.9-eclipse-temurin-25
```
