# Compile le projet SANS Java ni Maven installés (PowerShell).
# Résultat : target\logstorm-1.0.jar
#
# Option : antivirus/proxy qui intercepte le HTTPS ("PKIX path building failed") :
#   .\scripts\build.ps1 -ExtraCa C:\chemin\racine.cer     (voir docs\08-depannage.md)
param([string]$ExtraCa = "")
$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")

$m2 = Join-Path $env:USERPROFILE ".m2"
New-Item -ItemType Directory -Force $m2 | Out-Null
$image = "maven:3.9-eclipse-temurin-25"

Write-Host ">> Compilation Maven dans le conteneur $image..."
if ($ExtraCa -eq "") {
    docker run --rm -v "${PWD}:/app" -v "${m2}:/root/.m2" -w /app $image mvn -q package
} else {
    $ca = (Resolve-Path $ExtraCa).Path
    $cmd = 'cp "$JAVA_HOME/lib/security/cacerts" /tmp/ts && keytool -importcert -noprompt -alias extra -file /extra-ca.cer -keystore /tmp/ts -storepass changeit >/dev/null && mvn -q -Djavax.net.ssl.trustStore=/tmp/ts -Djavax.net.ssl.trustStorePassword=changeit package'
    docker run --rm -v "${PWD}:/app" -v "${m2}:/root/.m2" -v "${ca}:/extra-ca.cer:ro" -w /app $image sh -c $cmd
}
if ($LASTEXITCODE -ne 0) { throw "Echec de la compilation" }

Get-Item target\logstorm-1.0.jar
Write-Host ">> OK : target\logstorm-1.0.jar"
