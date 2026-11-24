# 03 — Visite guidée de Storm UI

Storm UI est l'interface web de supervision du cluster. Une fois le cluster lancé
(`make up2` ou `docker compose up -d --scale supervisor=2`), ouvrez **<http://localhost:8080>**.

> Si la page ne répond pas, attendez 20-30 s : l'UI démarre après Nimbus. Rafraîchissez avec **F5**.

---

## 1. Page d'accueil (sans topologie)

La page est faite de blocs empilés, de haut en bas :

| # | Bloc | Ce qu'on y lit | Où cliquer / quoi montrer |
|---|---|---|---|
| 1 | **Cluster Summary** | Version (3.2.0), *Supervisors*, *Used slots*, *Free slots*, *Total slots*, *Executors*, *Tasks* | rien : montrer **Supervisors = 2** et **Free slots = 8** (2 × 4 slots) |
| 2 | **Nimbus Summary** | hôte, port **6627**, statut **Leader**, version, uptime | rien |
| 3 | **Topology Summary** | une ligne par topologie (vide pour l'instant) | le **nom** de la topologie → page de la topologie |
| 4 | **Supervisor Summary** | une ligne par machine : *Host*, *Uptime*, *Slots*, *Used slots*, mémoire | le **nom d'hôte** → page du supervisor (ses workers) |
| 5 | **Nimbus Configuration** | toute la configuration effective du cluster (repliée) | cliquer sur le **titre** pour déplier ; utiliser **Ctrl+F** (ex. `topology.message.timeout.secs`, `supervisor.slots.ports`) |

> 🔎 **À faire** — Notez la valeur de `Free slots`. Que devient-elle avec
> `docker compose up -d --scale supervisor=3` ? (réponse : 12)

---

## 2. Page d'une topologie

Après `make deploy`, rafraîchissez la page d'accueil puis cliquez sur **logstorm** dans
*Topology Summary*.

### Topology summary
*Name*, *Id* (ex. `logstorm-1-1763750000`), *Status* (**ACTIVE**), *Uptime*,
*Num workers* (2), *Num executors*, *Num tasks*.

> Les executors comptés incluent les **ackers** (`__acker`) ajoutés automatiquement par Storm.

### Topology actions (boutons)

| Bouton | Effet | Équivalent CLI |
|---|---|---|
| **Activate** | reprend l'appel à `nextTuple()` des spouts | `storm activate logstorm` |
| **Deactivate** | met les spouts en pause (les bolts finissent le travail en cours) | `storm deactivate logstorm` |
| **Rebalance** | redistribue les workers (demande un délai d'attente) | `storm rebalance logstorm -w 5` |
| **Kill** | arrête la topologie après le délai demandé | `storm kill logstorm -w 5` |
| **Debug** | échantillonne les tuples émis (event logger) | — |
| **Change Log Level** | change le niveau de log d'un logger à chaud | `storm set_log_level` |

> ⚠️ Ces boutons ouvrent une **boîte de confirmation** du navigateur : cliquez **OK**.

### Topology stats
Tableau des statistiques sur 4 fenêtres : **10m 0s**, **3h 0m 0s**, **1d 0h 0m 0s**, **All time**.
Cliquez sur **10m 0s** pour que les tableaux Spouts/Bolts affichent cette période.

### Tableau Spouts

| Colonne | Signification | Valeur attendue pour `log-spout` |
|---|---|---|
| *Executors / Tasks* | parallélisme | 1 / 1 |
| *Emitted* | tuples émis | augmente d'environ 200/s |
| *Transferred* | tuples envoyés à d'autres tasks | ≈ Emitted |
| *Complete latency (ms)* | temps moyen pour qu'un arbre de tuples soit entièrement acké | **~15 s** mesurées : la fenêtre de 10 s retient les tuples avant l'ack (d'où le timeout de 30 s) |
| *Acked* | arbres terminés avec succès | augmente, suit Emitted |
| *Failed* | arbres échoués ou en timeout (rejoués) | **0** en temps normal |

### Tableau Bolts

| Colonne | Signification | Signal d'alerte |
|---|---|---|
| *Emitted / Transferred* | tuples produits | — |
| *Capacity (last 10m)* | part du temps passé dans `execute()` | **proche de 1** → bolt saturé : augmenter son parallélisme |
| *Execute latency (ms)* | durée moyenne d'un `execute()` | en hausse |
| *Executed* | tuples traités | — |
| *Process latency (ms)* | temps entre réception et ack | — |
| *Acked / Failed* | tuples confirmés / échoués | *Failed* qui augmente |

> 🔎 **À faire** — Comparez *Executed* de `parse` et *Emitted* de `log-spout`. Puis comparez
> *Emitted* de `page-count` (quelques dizaines toutes les 10 s : une ligne par URL et par fenêtre)
> avec *Executed* de `page-count`. Que montre ce rapport ? (le fenêtrage compresse le flux)

### Show Visualization
Bouton **Show Visualization** (sous les statistiques) : dessine le **DAG en direct**.
Les cercles sont les composants, les flèches les streams ; la couleur d'un nœud reflète sa
*capacity* (vert → rouge). Le stream `errors` apparaît comme une flèche séparée vers `error-alert`.

### Page d'un composant
Cliquez sur un nom de bolt (ex. **page-count**) : on voit chaque **executor**, son **Host**
(le conteneur supervisor), son **Port** (= le worker, 6700…6703), et ses statistiques. C'est ici
qu'on vérifie que les 2 executors de `page-count` sont sur des workers différents.

> Les liens vers les fichiers de logs (port 8000) ne fonctionnent pas dans ce montage, car
> il n'y a pas de service `logviewer`. On lit les logs dans le terminal : `make logs`.

---

## 3. Ce qu'on observe pendant la démo

| Action | Où regarder | Ce qui change |
|---|---|---|
| `make deploy` | Topology Summary | une ligne `logstorm`, ACTIVE ; *Used slots* passe à 2 |
| `make rebalance` | Topology summary | *Num workers* 2 → 3 ; page `parse` : 4 executors (~30 s) ; les compteurs repartent de zéro |
| `make chaos` | Supervisor Summary | en quelques secondes : 1 seul supervisor ; ~1 min 30 plus tard tous les workers sont sur supervisor-1 ; *Failed* augmente (rejeux) |
| retour de supervisor-2 | Supervisor Summary | 2 supervisors à nouveau |

➡️ Suite : [04-concepts.md](04-concepts.md)
