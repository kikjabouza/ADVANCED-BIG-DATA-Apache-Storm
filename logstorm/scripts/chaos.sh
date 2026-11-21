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
