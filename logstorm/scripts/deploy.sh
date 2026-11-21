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
