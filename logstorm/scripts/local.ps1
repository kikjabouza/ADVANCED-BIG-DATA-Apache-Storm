# Mode local : toute la topologie dans une seule JVM pendant 60 s (PowerShell).
# Usage : .\scripts\local.ps1 [secondes]
param([int]$Ttl = 60)
Set-Location (Join-Path $PSScriptRoot "..")

docker compose exec -T nimbus storm local --local-ttl $Ttl /jars/logstorm-1.0.jar com.bigdata.logstorm.LogStormTopology
