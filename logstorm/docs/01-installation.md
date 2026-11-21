# 01 — Installation de l'environnement

Seul **Docker Desktop** (et Git) est nécessaire : Java, Maven, ZooKeeper et Storm tournent
dans des conteneurs. Un JDK 25 et Maven installés localement sont **optionnels**.

| Ressource | Minimum conseillé |
|---|---|
| RAM | 8 Go sur la machine, dont ≥ 4 Go alloués à Docker |
| Disque | ~3 Go (images storm, zookeeper, maven + dépendances) |
| Réseau | nécessaire au premier lancement (téléchargement des images) |

---

## Windows 10 / 11

1. Ouvrez <https://www.docker.com/products/docker-desktop/> et cliquez sur
   **Download for Windows – AMD64** (ou **ARM64** pour un PC ARM, ex. Surface Pro X).
2. Lancez `Docker Desktop Installer.exe`. Sur l'écran **Configuration**, laissez cochée
   **Use WSL 2 instead of Hyper-V (recommended)**, cliquez **OK**, puis **Close and restart**.
3. Après le redémarrage, Docker Desktop s'ouvre : cliquez **Accept** (conditions d'utilisation),
   puis **Skip** (ou *Continue without signing in*) pour la création de compte.
4. Si Docker affiche *« WSL needs updating »* : ouvrez **PowerShell** et tapez
   ```powershell
   wsl --update
   ```
   puis relancez Docker Desktop.
5. Attendez que l'icône de la **baleine** (barre des tâches, en bas à droite) indique
   **Engine running** (pastille verte en bas à gauche de la fenêtre Docker Desktop).
6. Installez Git depuis <https://git-scm.com/download/win> (**Next** partout). Utilisez
   **Git Bash** (clic droit dans un dossier → *Open Git Bash here*) pour les commandes `.sh`,
   ou **PowerShell** pour les scripts `.ps1`.

> **WSL 2 absent ou virtualisation désactivée ?** Dans PowerShell **en administrateur** :
> `wsl --install`, redémarrez. Si Docker se plaint encore de la virtualisation, activez
> *Intel VT-x* / *AMD SVM* dans le BIOS (voir [08-depannage.md](08-depannage.md)).

## macOS

1. Sur <https://www.docker.com/products/docker-desktop/>, choisissez **Mac with Apple Silicon**
   (M1/M2/M3/M4) ou **Mac with Intel chip** ( → *À propos de ce Mac* pour savoir).
2. Ouvrez le `.dmg`, glissez la baleine dans **Applications**, lancez **Docker** et cliquez **Accept**.
3. Git : tapez `git --version` dans le Terminal ; macOS propose d'installer les
   *Command Line Tools* → **Installer**.

## Linux (Ubuntu / Debian)

```bash
sudo apt update && sudo apt install -y docker.io docker-compose-v2 git make
sudo usermod -aG docker $USER     # puis se déconnecter / reconnecter
```

---

## Réglage conseillé

Docker Desktop → ⚙ **Settings** → **Resources** → **Memory** ≥ **4 GB** → **Apply & restart**.

## Vérifications

```bash
docker --version          # Docker version 2x.x.x
docker compose version    # Docker Compose version v2.x
git --version
docker run --rm hello-world   # doit afficher "Hello from Docker!"
```

## Récupérer le projet et pré-télécharger les images

```bash
git clone https://github.com/<pseudo>/logstorm.git
cd logstorm
docker compose pull                              # storm + zookeeper (~1 Go)
docker pull maven:3.9-eclipse-temurin-25         # pour compiler sans Java
```

> Faites ces téléchargements **avant** la présentation : le Wi-Fi d'une salle de cours est
> rarement fiable.

## Version de Storm

Le projet utilise **Apache Storm 3.2.0** (image officielle `storm:3.2.0`, Java 25).
La version est définie à deux endroits qui doivent rester alignés :

| Fichier | Clé |
|---|---|
| `.env` | `STORM_VERSION=3.2.0` (version du cluster) |
| `pom.xml` | `<storm.version>3.2.0</storm.version>` et `<java.release>25</java.release>` |

Pour passer à Storm 2.8.x : `STORM_VERSION=2.8.9`, `storm.version` 2.8.9 et `java.release` **17**.

➡️ Suite : [02-cluster.md](02-cluster.md)
