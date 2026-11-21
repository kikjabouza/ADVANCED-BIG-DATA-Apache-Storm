#!/usr/bin/env bash
# Compile le projet SANS Java ni Maven installés : Maven tourne dans un conteneur.
# Résultat : target/logstorm-1.0.jar
#
# Option : si un antivirus (Avast, Kaspersky...) ou un proxy intercepte le HTTPS,
# Maven échoue avec "PKIX path building failed". Exportez son certificat racine
# (voir docs/08-depannage.md) puis : EXTRA_CA=chemin/vers/racine.cer ./scripts/build.sh
set -euo pipefail
cd "$(dirname "$0")/.."

export MSYS_NO_PATHCONV=1            # Git Bash (Windows) : ne pas réécrire /app en C:/...
SRC="$PWD"; M2="$HOME/.m2"; CA="${EXTRA_CA:-}"
if command -v cygpath >/dev/null 2>&1; then   # Git Bash : chemins Windows pour Docker
  SRC="$(cygpath -w "$SRC")"; M2="$(cygpath -w "$M2")"
  [ -n "$CA" ] && CA="$(cygpath -w "$CA")"
fi
mkdir -p "$HOME/.m2"

IMAGE=maven:3.9-eclipse-temurin-25
echo ">> Compilation Maven dans le conteneur $IMAGE..."
if [ -z "$CA" ]; then
  docker run --rm -v "$SRC":/app -v "$M2":/root/.m2 -w /app $IMAGE mvn -q package
else
  echo ">> Certificat supplémentaire : $CA"
  docker run --rm -v "$SRC":/app -v "$M2":/root/.m2 -v "$CA":/extra-ca.cer:ro -w /app $IMAGE sh -c '
    cp "$JAVA_HOME/lib/security/cacerts" /tmp/ts &&
    keytool -importcert -noprompt -alias extra -file /extra-ca.cer -keystore /tmp/ts -storepass changeit >/dev/null &&
    mvn -q -Djavax.net.ssl.trustStore=/tmp/ts -Djavax.net.ssl.trustStorePassword=changeit package'
fi

ls -l target/logstorm-1.0.jar
echo ">> OK : target/logstorm-1.0.jar"
