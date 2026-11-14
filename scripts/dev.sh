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
# 386: never cp over a LIVE jar — the running JVM wedges (classloader freeze).
# If a game is up, stop here: the operator decides (kill it first, or skip deploy).
JAVA_UP=$(tasklist | grep -icE "^java(w)?\.exe" || true)
if [ "$JAVA_UP" != "0" ]; then
  echo "[dev] java process(es) alive ($JAVA_UP) — NOT copying mindpalace-live.jar over a live JVM."
  echo "[dev] stop the game first (or run dev.sh from an idle box); relaunch flags: see AGENTS.md deploy notes."
  exit 1
fi
cp -f "$(ls target/mindpalace-*.jar 2>/dev/null | grep -vE "original-|-shaded" | head -1)" mindpalace-live.jar   # frozen-jar rule

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