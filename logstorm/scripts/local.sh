#!/usr/bin/env bash
# Mode local : toute la topologie dans UNE seule JVM (dans le conteneur nimbus),
# pendant 60 s (ou la durée passée en argument), puis arrêt automatique.
set -euo pipefail
cd "$(dirname "$0")/.."
export MSYS_NO_PATHCONV=1
TTL="${1:-60}"

docker compose exec -T nimbus storm local --local-ttl "$TTL" \
  /jars/logstorm-1.0.jar com.bigdata.logstorm.LogStormTopology
