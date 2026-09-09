#!/usr/bin/env bash
# dev.sh — one-command dev loop for MindPalace (the mvn analog of npm run dev)
# Usage: bash scripts/dev.sh            -> build + run live mode
#        bash scripts/dev.sh demo       -> build + run --demo (zero auth/network)
#        bash scripts/dev.sh selftest   -> build + selftest only (exit code = gate)
set -euo pipefail
cd "$(dirname "$0")/.."

JAVA_HOME="${JAVA_HOME:-C:/Program Files/Java/jdk-17}"
export JAVA_HOME
MVN="C:/ProgramData/chocolatey/lib/maven/apache-maven-3.9.16/bin/mvn.cmd"
FLAGS="-Dprism.order=sw -Dprism.vsync=false -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -Xms256m -Xmx768m"

echo "[dev] building..."
JAVA_HOME="$JAVA_HOME" "$MVN" -q -DskipTests package
cp -f target/mindpalace-1.0.0.jar mindpalace-live.jar   # frozen-jar rule

case "${1:-run}" in
  selftest)
    "$JAVA_HOME/bin/java" $FLAGS -jar mindpalace-live.jar --demo --selftest
    ;;
  demo)
    echo "[dev] launching demo mode..."
    "$JAVA_HOME/bin/java" $FLAGS -jar mindpalace-live.jar --demo
    ;;
  run|*)
    echo "[dev] launching live mode..."
    "$JAVA_HOME/bin/java" $FLAGS -jar mindpalace-live.jar
    ;;
esac