# 09 — Compte rendu du TP : résultats mesurés

Ce document rassemble ce qui a été **réellement observé** en réalisant le TP de bout en bout
le 24/11/2026 sur un PC Windows 11 (Docker Desktop 29.0.1, Compose v2.40, image `storm:3.2.0`).
Les valeurs varient d'une exécution à l'autre (génération aléatoire), mais les ordres de grandeur
sont ceux-ci.

## 1. Déroulé et résultat de chaque étape

| Étape | Commande | Résultat |
|---|---|---|
| Validation du compose | `docker compose config` | ✅ valide |
| Démarrage du cluster | `docker compose up -d --scale supervisor=2` | ✅ 5 conteneurs `Up` |
| Version | `docker compose exec nimbus storm version` | ✅ `Storm 3.2.0` |
| Compilation | `./scripts/build.sh` / `.\scripts\build.ps1` | ✅ `target/logstorm-1.0.jar` (~14 Ko) — après contournement de l'antivirus (voir § 3) |
| Mode local | `./scripts/local.sh 100` | ✅ 8 `[RAPPORT]`, 10 `[ALERTE]`, 0 rejeu |
| Déploiement | `./scripts/deploy.sh` / `.\scripts\deploy.ps1` | ✅ `logstorm ACTIVE`, 11 tasks, 2 workers |
| Logs du cluster | `./scripts/logs.sh` (50 s) | ✅ 6 `[RAPPORT]`, 7 `[ALERTE]` |
| Rebalance | `storm rebalance logstorm -w 5 -n 3 -e parse=4` | ✅ 3 workers, 4 executors `parse` en ~30 s |
| Panne | `docker stop logstorm-supervisor-2` | ✅ réassignation complète en ~1 min 40, rapports continus |
| Retour | `docker start logstorm-supervisor-2` | ✅ 2 supervisors |
| Script de panne | `./scripts/chaos.sh 10` | ✅ stop → attente → start |
| Doublon | redéployer alors que `logstorm` tourne | ✅ erreur attendue : `Topology name logstorm is either not allowed or it already exists on the cluster` |
| Arrêt | `storm kill logstorm -w 5` puis `docker compose down` | ✅ `No topologies running.` en ~10 s, 0 conteneur |
| Makefile | `make ...` | ⚠️ non testé : `make` n'est pas installé sur le PC Windows utilisé (les cibles appellent les mêmes scripts et commandes) |

## 2. Chiffres à citer pendant la présentation

| Mesure | Valeur | Commentaire |
|---|---|---|
| Débit du spout | **~175 lignes/s** | `Utils.sleep(5)` + coût d'émission |
| Requêtes par page et par fenêtre | **~200 à 260** | 8 pages tirées uniformément |
| Latence moyenne normale / pendant une rafale | **~140 ms / ~700 ms** | les erreurs sont lentes (800-2800 ms) |
| Erreurs 5xx en 10 s hors rafale | **31 à 46** | 2 % du trafic |
| Erreurs 5xx en 10 s pendant une rafale | **275 à 565** | 35 % du trafic |
| *Complete latency* du spout | **~15 s** | les tuples attendent la fermeture de la fenêtre de 10 s |
| *Capacity* des bolts | **≤ 0,03** | aucun bolt saturé : on pourrait traiter bien plus |
| Cluster après déploiement | 2 supervisors, 8 slots, **2 utilisés**, 9 executors, 11 tasks | executors et tasks incluent les 2 `__acker` |
| Placement de `page-count` | 2 executors sur **2 supervisors différents** | vu dans la page du composant |
| Panne : retrait du supervisor dans l'UI | **~2 s** | arrêt propre : le supervisor se désinscrit |
| Panne : réassignation de tous les workers | **~1 min 40** | Nimbus attend l'expiration des heartbeats des workers |
| Tuples échoués puis rejoués après la panne | *Failed* ≈ **4 900**, **~5 000** `Rejeu du message` | preuve de l'**at-least-once** |

## 3. Problèmes rencontrés et corrections

### a) L'antivirus bloque Maven (`PKIX path building failed`)
Avast analyse le HTTPS et signe les certificats avec sa propre autorité, inconnue de Java dans le
conteneur. Solution ajoutée aux scripts : option `EXTRA_CA` (Bash) / `-ExtraCa` (PowerShell) qui
ajoute le certificat racine de l'antivirus au magasin de confiance **le temps de la compilation**.
Détails : [08-depannage.md](08-depannage.md#antivirus--pkix-path-building-failed).

### b) `maxSpoutPending(1000)` étrangle une topologie fenêtrée
Première version : `setMaxSpoutPending(1000)`. Résultat : débit tombé à **~45 lignes/s** et
comptes incohérents (~110 pour certaines pages, ~20 pour d'autres dans le même rapport).

Cause : un `BaseWindowedBolt` n'acke ses tuples qu'à la **fermeture** de la fenêtre. Le spout
atteint donc 1000 tuples en vol en quelques secondes et s'arrête jusqu'à la fermeture suivante :
il émet par à-coups. **Correction : `setMaxSpoutPending(5000)`** (> ~200 lignes/s × ~20 s).

👉 C'est une excellente illustration du lien entre **contre-pression**, **acking** et **fenêtrage**.

### c) Rapports incomplets
Première version de `ReportBolt` : cumul des comptes reçus puis remise à zéro à chaque tick.
Résultat : des rapports avec 3 à 6 pages seulement et des comptes partiels, car les 2 tasks
`page-count` ne ferment pas leurs fenêtres au même instant que le tick.
**Correction** : `ReportBolt` garde, pour chaque page, le résultat de sa **dernière fenêtre
complète** (`put` au lieu de `merge` + `clear`). Chaque rapport affiche les 8 pages.

### d) Seuil d'alerte (conservé à 15)
Avec ~40 erreurs « normales » en 10 s, le seuil de 15 est dépassé en permanence : une alerte
part toutes les 10 s (limite du cooldown). Le seuil a été **gardé à 15** pour rester cohérent
avec le cours ; en démo, on montre la **valeur** de l'alerte (~40 hors rafale contre ~300-560
pendant une rafale). Exercice : régler `THRESHOLD` à ~100 pour n'alerter que pendant les rafales.

### e) Git Bash réécrit les chemins
`docker compose exec nimbus ls /jars` sous Git Bash → `cannot access 'C:/Program Files/Git/jars'`.
Solution : `export MSYS_NO_PATHCONV=1` (déjà dans les scripts).

## 4. Bruit normal dans les logs (à ignorer)

- `WARN ... pacemaker.* is a deprecated config` : avertissements internes de Storm 3.2.0.
- `ERROR ... BlobStoreUtils - Could not download the blob ...` au démarrage de `storm local` :
  course au démarrage du Nimbus local, la topologie s'active juste après.
