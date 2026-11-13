#!/usr/bin/env bash
# local_ci.sh -- the merge gate, run on this machine instead of GitHub Actions (Actions was locked for billing 2026-11-10,
# and a runner that cannot start tells nobody anything). Same checks as .github/workflows/ci.yml, deterministic, no network.
#
#   bash scripts/local_ci.sh            fast tier  (~1-2 min): instruction inventory, compile, 138-case hardening suite (headless)
#   bash scripts/local_ci.sh --full     + the demo selftest (needs a window; software GL; one JVM; refuses when RAM is short)
#   bash scripts/local_ci.sh --post     after the run, post the result as the "local-ci" commit status on HEAD (what a bot or the
#                                       merger reads instead of the Actions check)
#   bash scripts/local_ci.sh --no-build skip the compile when target/classes is already current
#
# Exit: 0 green, 1 a check failed, 75 box too busy for the --full tier (retry later; never a failure).
# Every run writes reports under $LOCAL_CI_DIR (default ~/AIGEN_SYS/reports/local_ci/<sha>.log) so a bot can read why.
set -uo pipefail

FULL=0; POST=0; BUILD=1
for a in "$@"; do
  case "$a" in
    --full) FULL=1 ;; --post) POST=1 ;; --no-build) BUILD=0 ;;
    *) echo "usage: local_ci.sh [--full] [--post] [--no-build]"; exit 2 ;;
  esac
done

cd "$(dirname "${BASH_SOURCE[0]}")/.." || exit 2
ROOT="$(pwd)"
SHA="$(git rev-parse --short HEAD 2>/dev/null || echo nogit)"
OUT="${LOCAL_CI_DIR:-$HOME/AIGEN_SYS/reports/local_ci}"
mkdir -p "$OUT"
LOG="$OUT/$SHA.log"
JAVA="${JAVA:-C:/Program Files/Java/jdk-17/bin/java}"
FAILED=()
: > "$LOG"

say()  { printf '[local-ci %s] %s\n' "$(date +%H:%M:%S)" "$*" | tee -a "$LOG"; }
step() { # step <name> <cmd...>   runs, logs, records a failure but keeps going so one run shows every problem
  local name="$1"; shift
  say "RUN  $name"
  if "$@" >> "$LOG" 2>&1; then say "PASS $name"; else say "FAIL $name"; FAILED+=("$name"); fi
}

# 1. documented keys == handled keys (strict)
step "instruction-inventory" python3 tools/instruction_inventory.py --strict

# 2. compile
if [ "$BUILD" = 1 ]; then step "compile" mvn -B -q -o -DskipTests compile; fi

# 3. dependency classpath, cached (resolving it costs ~20 s on the HDD)
CPF="target/local_ci.cp"
if [ ! -s "$CPF" ] || [ pom.xml -nt "$CPF" ]; then
  mvn -B -q -o dependency:build-classpath -Dmdep.outputFile="$CPF" >> "$LOG" 2>&1 || say "WARN classpath resolve failed"
fi
DEPS="$(cat "$CPF" 2>/dev/null)"
CP="target/classes${DEPS:+;$DEPS}"

# 4. the hardening suite: headless, deterministic, run from the repo root (some cases read repo files)
if [ -z "${FAILED[*]:-}" ] || [ "$BUILD" = 0 ]; then
  step "hardening-cases" "$JAVA" -Xmx256m -cp "$CP" com.mindpalace.agent.sims.SimsHardening100
fi

# 5. (--full) the demo selftest, same assertions as the Actions job
if [ "$FULL" = 1 ]; then
  FREE_MB="$(python3 -c 'import psutil;print(int(psutil.virtual_memory().available/1048576))' 2>/dev/null || echo 0)"
  if [ "$FREE_MB" -lt 2500 ]; then
    say "BUSY only ${FREE_MB} MB free (< 2500): skipping --full, retry when the box is calm"
    [ "$POST" = 1 ] && say "NOT posting a status for a skipped tier"
    exit 75
  fi
  JAR="$(ls target/mindpalace-*.jar 2>/dev/null | grep -v original | head -n 1)"
  if [ -z "$JAR" ]; then step "package" mvn -B -q -o -DskipTests package; JAR="$(ls target/mindpalace-*.jar | grep -v original | head -n 1)"; fi
  run_selftest() {
    "$JAVA" -Dprism.order=sw -Dprism.vsync=false -Xmx768m -Dmindpalace.glTrace=true -jar "$JAR" --demo --selftest > "$OUT/selftest-$1.log" 2>&1
    local rc=$?
    local n; n="$(grep -E 'RESULT:[[:space:]]*[0-9]+ passed, 0 failed' "$OUT/selftest-$1.log" | tail -n 1 | sed -E 's/.*RESULT:[[:space:]]*([0-9]+).*/\1/')"
    [ "$rc" -eq 0 ] && [ -n "$n" ] && [ "$n" -ge 5 ] \
      && grep -Fq "Demo fixtures loaded: 13 rooms" "$OUT/selftest-$1.log" \
      && grep -Fq "PASS demo hermeticity" "$OUT/selftest-$1.log" \
      && ! grep -Eq '^\[GlTrace\] GL_' "$OUT/selftest-$1.log"
  }
  say "RUN  demo-selftest"
  if run_selftest 1; then say "PASS demo-selftest (stable)"
  elif run_selftest 2; then say "PASS demo-selftest (flaky: first attempt failed, retry passed)"
  else say "FAIL demo-selftest (both attempts; see $OUT/selftest-2.log)"; FAILED+=("demo-selftest"); fi
fi

if [ "${#FAILED[@]}" -eq 0 ]; then STATE=success; DESC="local-ci green ($([ "$FULL" = 1 ] && echo full || echo fast))"; RC=0
else STATE=failure; DESC="local-ci FAILED: ${FAILED[*]}"; RC=1; fi
say "$DESC  [log: $LOG]"

if [ "$POST" = 1 ]; then
  FULLSHA="$(git rev-parse HEAD)"
  env -u GH_TOKEN -u GITHUB_TOKEN gh api "repos/chrisalunlloyd2-sudo/mindpalace/statuses/$FULLSHA" \
    -f state="$STATE" -f context="local-ci" -f description="${DESC:0:140}" > /dev/null 2>&1 \
    && say "posted status $STATE on $SHA" || say "WARN could not post the status (offline?)"
fi
exit $RC
