# 06 — La démonstration (4 minutes) et le plan B

## Avant la présentation (la veille et 15 min avant)

- [ ] `docker compose pull` et `docker pull maven:3.9-eclipse-temurin-25` faits **chez soi**
- [ ] `make build` (ou `.\scripts\build.ps1`) → `target/logstorm-1.0.jar` présent
- [ ] Docker Desktop ouvert, **Engine running**
- [ ] `make up2` lancé **10 min avant** (le temps que tout démarre), `make ps` → 5 conteneurs
- [ ] Aucune topologie active : `make list` → *No topologies running* (sinon `make kill`)
- [ ] Navigateur ouvert sur <http://localhost:8080>, zoom 125 % pour la salle
- [ ] **Deux terminaux** côte à côte, police agrandie (Ctrl + molette), placés dans `logstorm/`
  - terminal A : les commandes
  - terminal B : réservé à `make logs`
- [ ] Mode **Ne pas déranger** activé, notifications coupées
- [ ] Plan B ouvert dans un onglet (captures / vidéo, voir plus bas)

> Sans `make` (Windows) : remplacez `make deploy` par `.\scripts\deploy.ps1`, `make logs` par
> `bash scripts/logs.sh` (Git Bash), etc. — correspondances dans [07-commandes.md](07-commandes.md).

## Déroulé minute par minute

| Temps | Action | Où | Ce que vous dites |
|---|---|---|---|
| 0:00 | `make ps` | terminal A | « Le cluster tourne : ZooKeeper, Nimbus, deux supervisors et l'UI, chacun dans un conteneur. » |
| 0:20 | Montrer **Cluster Summary** puis **Supervisor Summary** | Storm UI | « Deux machines, huit slots libres, aucune topologie. » |
| 0:45 | `make deploy` | terminal A | « J'envoie mon jar à Nimbus, qui va répartir le travail. » (`storm list` → ACTIVE) |
| 1:00 | **F5**, clic **logstorm**, **Show Visualization** | Storm UI | « Voilà notre DAG, vivant : un spout, quatre bolts, deux streams. » |
| 1:30 | `make logs` | terminal B | « Toutes les 10 secondes, le classement des pages de la dernière fenêtre. » |
| 2:00 | Attendre un `[ALERTE]` (≤ 40 s ; pendant une rafale on voit le compteur exploser) | terminal B | « Une rafale d'erreurs 500 : l'alerte part en temps réel, sans attendre un batch. » |
| 2:40 | `make rebalance` | terminal A | « Je passe à 3 workers et 4 executors de parsing, **sans redéployer**. » |
| 3:10 | `make chaos` | terminal A | « Je débranche une machine. » |
| 3:40 | F5 sur la page d'accueil (**Supervisor Summary**) puis la topologie | Storm UI | « Nimbus a vu la panne dans ZooKeeper et a réassigné les workers ; les rapports continuent. » |
| 4:00 | fin du compte à rebours de `make chaos` (`docker start`) | terminal A | « Et la machine revient dans le cluster. » |

Après la démo : `make kill` puis `make down`.

> ⏱️ `make chaos` attend 60 s par défaut. Pour coller au minutage : `bash scripts/chaos.sh 50`.
>
> 📝 Pendant le rebalance, la topologie est **réorganisée** pendant ~15 s : quelques secondes
> sans `[RAPPORT]` est normal. Après la panne, *Failed* peut augmenter : ce sont les tuples
> **rejoués** (at-least-once) — profitez-en pour le dire.

## Ce que vous devez voir (exemple)

```
[supervisor-1] 2026-11-21 10:00:10.004 c.b.l.b.ReportBolt ... [INFO]
[supervisor-1] [RAPPORT] Requêtes par page (10 dernières secondes)
[supervisor-1]   /produits        241 req   latence moy.  139 ms
[supervisor-1]   /panier          236 req   latence moy.  127 ms
[supervisor-1]   /recherche       230 req   latence moy.  131 ms
...
[supervisor-2] 2026-11-21 10:00:33.512 c.b.l.b.ErrorAlertBolt ... [WARN] [ALERTE] 16 erreurs 5xx en 10 s (dernière sur /paiement, statut 503)
```

> Les chiffres varient (génération aléatoire). `ReportBolt` et `ErrorAlertBolt` peuvent être
> sur des supervisors différents : `make logs` suit les deux.

## Variante : mode local (si le cluster pose problème)

```bash
make local        # ou .\scripts\local.ps1
```
Toute la topologie tourne dans **une seule JVM** pendant 60 s, et les `[RAPPORT]` / `[ALERTE]`
s'affichent **directement** dans le terminal (au milieu des logs de démarrage). Il faut tout
de même que le conteneur `nimbus` tourne (c'est lui qui fournit la commande `storm`).

## Plan B (rien ne marche le jour J)

1. Pendant la **répétition**, enregistrez :
   - une capture de Storm UI (page d'accueil, page `logstorm`, *Show Visualization*,
     Supervisor Summary après la panne) ;
   - une **vidéo de 2-3 min** de l'écran (Windows : **Win + Alt + R** avec la Xbox Game Bar ;
     macOS : **Cmd + Shift + 5**).
2. Rangez-les dans un dossier `demo/` (non versionné si trop lourd) et ouvrez-les dans un onglet.
3. Le jour J, si Docker ne démarre pas ou si le réseau tombe : « Voici la démo enregistrée
   hier », et commentez avec le même script.

## Exercices pour aller plus loin (TP)

1. **Réglage d'alerte** : avec 2 % d'erreurs à ~200 lignes/s, combien d'erreurs en 10 s hors
   rafale ? Ajustez `THRESHOLD` dans `ErrorAlertBolt` pour n'alerter **que** pendant les rafales.
2. **Fenêtre glissante** : remplacez `withTumblingWindow(10 s)` par
   `withWindow(Duration.seconds(30), Duration.seconds(10))` et observez le classement.
3. **Grouping** : remplacez le `fieldsGrouping` par un `shuffleGrouping` (voir
   [04-concepts.md](04-concepts.md)) et observez *Emitted* de `page-count`.
4. **Rejeu** : dans `ParseBolt`, appelez `collector.fail(input)` pour 1 ligne sur 100 et
   observez *Failed* et les lignes `Rejeu du message`.
5. **Scale out** : `docker compose up -d --scale supervisor=3`, puis
   `storm rebalance logstorm -w 5 -n 4` : que devient *Free slots* ?

➡️ Suite : [07-commandes.md](07-commandes.md)
