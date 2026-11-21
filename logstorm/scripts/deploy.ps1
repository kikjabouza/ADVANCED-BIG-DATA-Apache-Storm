# Mode cluster : envoie le jar à Nimbus, puis liste les topologies (PowerShell).
# Usage : .\scripts\deploy.ps1 [nom-topologie]
param([string]$Name = "logstorm")
Set-Location (Join-Path $PSScriptRoot "..")

docker compose exec -T nimbus storm jar /jars/logstorm-1.0.jar com.bigdata.logstorm.LogStormTopology $Name
if ($LASTEXITCODE -ne 0) { throw "Echec du deploiement" }

Write-Host ">> Topologies sur le cluster :"
docker compose exec -T nimbus storm list
