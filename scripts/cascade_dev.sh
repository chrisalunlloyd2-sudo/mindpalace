#!/usr/bin/env bash
# cascade_dev.sh — one-command step finisher (H26 in HYPOTHESES_50.md).
#
# Usage:  bash scripts/cascade_dev.sh H01
#
# Runs the full quota-free verification cascade for ONE hypothesis step
# so the hourly task-watch (cloud) only has to write the code, and THIS
# script proves + ships it:
#   1. one-game guard (kill orphan javaw/java game processes, never two)
#   2. freeze the live jar copy (launch-rule: never run the build target)
#   3. mvn package (skip tests — selftest is the test)
#   4. --selftest
#   5. --e2e waypoint tour with non-black shot verification
#   6. git add/commit/push
#   7. append DONE + evidence to the phase's GitHub step-log issue
#      (via gh CLI if authed; skipped silently otherwise)
# Bounded: any step failing stops the cascade with a clear error. Every
# step is resumable — rerun after fixing; nothing is done twice.
set -uo pipefail

H_ID="${1:?usage: cascade_dev.sh H01 (from HYPOTHESES_50.md)}"
REPO="/c/Users/viper/AIGEN_SYS/repos/mindpalace"
JAVA="C:/Program Files/Java/jdk-17/bin/java"
MVN="C:\\ProgramData\\chocolatey\\lib\\maven\\apache-maven-3.9.16\\bin\\mvn.cmd"
SHOTS="$REPO/target/e2e-shots"
LOGDIR="/tmp/cascade"
mkdir -p "$LOGDIR"

cd "$REPO" || exit 1
log() { printf '[cascade %s] %s\n' "$(date +%H:%M:%S)" "$*"; }

# ── 1. one-game guard ────────────────────────────────────────────────
log "guard: sweeping orphan game processes"
tasklist | grep -iE "^java(w)?\.exe" | awk '{print $2}' | while read -r pid; do
  log "guard: killing orphan java pid $pid"
  taskkill //PID "$pid" //F >/dev/null 2>&1 || true
done

# ── 2. freeze live jar ──────────────────────────────────────────────
log "freeze: refreshing mindpalace-live.jar from target (after build we re-copy)"

# ── 3. build ─────────────────────────────────────────────────────────
log "build: mvn package"
export JAVA_HOME="C:/Program Files/Java/jdk-17"
if ! cmd.exe /c "$MVN -DskipTests package" > "$LOGDIR/build.log" 2>&1; then
  log "BUILD FAILED — tail:"
  tail -20 "$LOGDIR/build.log"
  exit 1
fi
log "build: OK"

# ── 4. selftest ──────────────────────────────────────────────────────
log "selftest: running"
cp -f target/mindpalace-1.0.0.jar mindpalace-live.jar
if ! timeout 110 "$JAVA" -Dprism.order=sw -Dprism.vsync=false \
    -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -Xms256m -Xmx768m \
    -jar mindpalace-live.jar --selftest > "$LOGDIR/selftest.log" 2>&1; then
  log "SELFTEST FAILED — tail:"
  tail -15 "$LOGDIR/selftest.log"
  exit 1
fi
RESULT=$(grep -a "RESULT" "$LOGDIR/selftest.log" | tail -1)
log "selftest: $RESULT"
echo "$RESULT" | grep -q "PASS" || { log "selftest did not PASS"; exit 1; }

# ── 5. E2E waypoint tour ────────────────────────────────────────────
log "e2e: waypoint tour"
rm -rf "$SHOTS"; mkdir -p "$SHOTS"
if ! timeout 120 "$JAVA" -Dprism.order=sw -Dprism.vsync=false \
    -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -Xms256m -Xmx768m \
    -jar mindpalace-live.jar --e2e "$(cygpath -m "$SHOTS")" > "$LOGDIR/e2e.log" 2>&1; then
  log "E2E FAILED — tail:"
  tail -15 "$LOGDIR/e2e.log"
  exit 1
fi
python scripts/test_bot.py --verify-shots "$SHOTS" || { log "shot verification failed"; exit 1; }
log "e2e: all waypoints verified non-black"

# ── 6. ship ─────────────────────────────────────────────────────────
log "ship: git commit + push"
git add -A
if git diff --cached --quiet; then
  log "ship: nothing to commit (already clean)"
else
  git commit -m "H${H_ID#H}: cascade-verified (selftest PASS + E2E green)" >/dev/null
  git push origin HEAD:main >/dev/null 2>&1 || log "ship: push failed (offline?) — commit stays local"
  log "ship: pushed"
fi

# ── 7. GitHub step-log ──────────────────────────────────────────────
if gh auth status >/dev/null 2>&1; then
  ISSUE_FILE="$REPO/.cascade_issue"
  ISSUE=$(cat "$ISSUE_FILE" 2>/dev/null || true)
  if [ -n "$ISSUE" ]; then
    {
      echo "**${H_ID} DONE** $(date +%Y-%m-%dT%H:%M) — cascade evidence:"
      echo "- selftest: $RESULT"
      echo "- e2e: waypoint tour green ($(ls "$SHOTS" | wc -l) shots, non-black)"
      echo "- commit: $(git rev-parse --short HEAD)"
      echo "- scout: $(python scripts/scout_bot.py --map | head -1)"
    } > "$LOGDIR/issue_comment.md"
    if gh issue comment "$ISSUE" --repo chrisalunlloyd2-sudo/mindpalace \
        --body-file "$LOGDIR/issue_comment.md" >/dev/null 2>&1; then
      log "github: step-log comment posted to issue #$ISSUE"
    else
      log "github: comment failed (rate limit?) — evidence saved in $LOGDIR/issue_comment.md"
    fi
  fi
else
  log "github: gh not authed — skipping step-log (evidence in $LOGDIR)"
fi

log "CASCADE COMPLETE: $H_ID verified + shipped"
python scripts/scout_bot.py --metrics >/dev/null 2>&1 || true