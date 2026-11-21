# Raccourcis pour LogStorm. Linux / macOS / Git Bash avec make.
# Sous Windows sans make : utiliser directement scripts/*.ps1 ou scripts/*.sh
# (voir docs/07-commandes.md).

TOPO   ?= logstorm
EXEC   := docker compose exec -T nimbus
export MSYS_NO_PATHCONV=1

.PHONY: help up up2 ps build local deploy list logs rebalance chaos heal kill down clean

help:        ## Affiche cette aide
	@grep -E '^[a-z0-9]+:.*##' $(MAKEFILE_LIST) | awk -F':.*## ' '{printf "  make %-10s %s\n", $$1, $$2}'

up:          ## Démarre le cluster (1 supervisor)
	docker compose up -d

up2:         ## Démarre le cluster avec 2 supervisors
	docker compose up -d --scale supervisor=2

ps:          ## État des conteneurs
	docker compose ps

build:       ## Compile target/logstorm-1.0.jar (Maven dans Docker)
	./scripts/build.sh

local:       ## Lance la topologie en mode local pendant 60 s
	./scripts/local.sh 60

deploy:      ## Déploie la topologie sur le cluster (storm jar + storm list)
	./scripts/deploy.sh $(TOPO)

list:        ## Liste les topologies (storm list)
	$(EXEC) storm list

logs:        ## Suit les [RAPPORT] / [ALERTE] des 2 supervisors
	./scripts/logs.sh

rebalance:   ## 3 workers et 4 executors pour parse, à chaud
	$(EXEC) storm rebalance $(TOPO) -w 5 -n 3 -e parse=4

chaos:       ## Panne de supervisor-2 pendant 60 s, puis retour
	./scripts/chaos.sh 60

heal:        ## Redémarre supervisor-2 (si chaos a été interrompu)
	docker start logstorm-supervisor-2

kill:        ## Arrête la topologie (après 5 s de drainage)
	$(EXEC) storm kill $(TOPO) -w 5

down:        ## Arrête et supprime les conteneurs
	docker compose down

clean:       ## Supprime target/
	rm -rf target
