#!/usr/bin/env bash
# Suit les worker.log des 2 supervisors et ne garde que les lignes [RAPPORT] / [ALERTE]
# (et les lignes du classement). Ctrl+C pour quitter.
# Les workers démarrent ~20 s après "storm jar" : relancer si "No such file".
set -uo pipefail
cd "$(dirname "$0")/.."
export MSYS_NO_PATHCONV=1

follow() {   # $1 = numéro du supervisor
  docker compose exec -T --index "$1" supervisor \
    sh -c 'tail -n 50 -F /logs/workers-artifacts/*/*/worker.log 2>/dev/null' \
  | grep --line-buffered -E "RAPPORT|ALERTE| req " \
  | sed -u "s/^/[supervisor-$1] /"
}

follow 1 &
follow 2 &
trap 'kill $(jobs -p) 2>/dev/null' INT TERM EXIT
wait
