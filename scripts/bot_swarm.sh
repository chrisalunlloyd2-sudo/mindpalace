#!/usr/bin/env bash
# bot_swarm.sh — quota-free bot orchestrator (H23/H26/H29/H31 conductor).
#
# Usage:
#   bash scripts/bot_swarm.sh launch    — one-game guard + launch the
#                                          frozen live jar (NEVER the build
#                                          target: hourly sync rebuilds it)
#   bash scripts/bot_swarm.sh sweep      — kill every java game process
#   bash scripts/bot_swarm.sh status     — alive? ram? jar frozen? scouts?
#   bash scripts/bot_swarm.sh cycle      — one full recon pass:
#                                          scout report + metrics + shot
#                                          verification of the last E2E run
#
# Rules enforced here (from memory):
#   - ONLY ONE game/GUI instance at a time — sweep before launch, always.
#   - ALWAYS run from the frozen copy (mindpalace-live.jar), because the
#     hourly sync rebuilds target/ mid-game → silent freeze rule.
set -uo pipefail

REPO="/c/Users/viper/AIGEN_SYS/repos/mindpalace"
JAVA="C:/Program Files/Java/jdk-17/bin/java"
LIVE="$REPO/mindpalace-live.jar"
LOG="$REPO/game_console.log"

case "${1:-status}" in

  sweep)
    tasklist | grep -iE "^java(w)?\.exe" | awk '{print $2}' | while read -r pid; do
      echo "[swarm] killing orphan java pid $pid"
      taskkill //PID "$pid" //F >/dev/null 2>&1 || true
    done
    echo "[swarm] sweep complete — no java game processes remain"
    ;;

  launch)
    # one-game guard
    if tasklist | grep -qiE "^java(w)?\.exe"; then
      echo "[swarm] game already running — refusing to double-launch (one-game rule)"
      exit 0
    fi
    if [ ! -f "$LIVE" ]; then
      echo "[swarm] no frozen jar — copying from target"
      cp -f "$REPO/target/mindpalace-1.0.0.jar" "$LIVE" || exit 1
    fi
    echo "[swarm] launching frozen jar: $LIVE"
    cd "$REPO" || exit 1
    nohup "$JAVA" -Dprism.order=sw -Dprism.vsync=false \
      -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -Xms256m -Xmx768m \
      -jar "$LIVE" > "$LOG" 2>&1 &
    echo "[swarm] launched pid $! — console → $LOG"
    ;;

  status)
    ALIVE=$(tasklist | grep -icE "^java(w)?\.exe" || true)
    echo "[swarm] java game processes: $ALIVE"
    if [ -f "$LIVE" ]; then
      echo "[swarm] frozen jar: $(date -r "$LIVE" '+%Y-%m-%d %H:%M:%S')"
    else
      echo "[swarm] frozen jar: MISSING"
    fi
    if [ -f "$LOG" ]; then
      echo "[swarm] console age: $(date -r "$LOG" '+%H:%M:%S') (now $(date '+%H:%M:%S'))"
    fi
    ;;

  cycle)
    echo "[swarm] recon cycle start"
    python "$REPO/scripts/scout_bot.py"
    python "$REPO/scripts/scout_bot.py" --metrics | tail -3
    ;;

  *)
    echo "usage: bot_swarm.sh {launch|sweep|status|cycle}"
    ;;
esac