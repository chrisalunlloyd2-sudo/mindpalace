#!/usr/bin/env bash
# release_phase.sh — H33/H33a (NEXT_100_STEPS steps 89-90) release automation.
#
# One script, three modes:
#   bash scripts/release_phase.sh --dry-run [VER]   # preview edits only, no writes
#   bash scripts/release_phase.sh --rehearse VER    # full pipeline, NO remote writes, edits reverted after
#   bash scripts/release_phase.sh --cut VER         # rehearse + commit/tag/push/gh release/step-log post
#
# What a run does (rehearse/cut identical up to the ship step):
#   1. validate VER (e.g. 1.2.0-beta1), refuse an already-existing tag
#   2. bump pom.xml <version>, stamp INSTALLER.md + README.md download line
#   3. mvn clean package (skip tests — selftest is the test, per cascade_dev.sh)
#   4. selftest via test_bot --run-selftest (H265 runner, timeout 500, one
#      retry for the hung-JVM flake class; gate greps the real verdict line)
#   5. e2e waypoint tour on the fresh target jar (timeout 120, sw renderer)
#   6. jpackage .exe — app-version gets the NUMERIC part only (jpackage rejects
#      4th components/beta suffixes; tag + asset name carry the full VER,
#      asset renamed MindPalace-<VER>.exe after build)
#      A BROKEN PHASE NEVER GETS TAGGED: any failed gate aborts before ship.
#   7. relaunch step (MP invariant): sweep ALL java/javaw, verify zero, then
#      swap the fresh jar → mindpalace-live.jar and launch THAT — the one
#      relaunch recipe from mindpalace-sync.sh step 8, verbatim.
#   8. --cut only: git add (pom/INSTALLER/README + exe is NOT committed —
#      installer/ is gitignored by design; jar/exe upload as Release assets),
#      commit, tag vX.Y.Z, push main + tag, gh release create with notes,
#      post the release URL to the step-log issue in .cascade_issue
#   9. rehearse: restore version files byte-exactly; cut: version commit
#      stays (it IS the release). No game is left dark in either mode.
#
# Version policy (NEXT_100_STEPS "Release/versioning policy"): 1.x betas,
# bumped on phase completion; the exe goes on GitHub Releases on green.
set -uo pipefail

MODE="${1:?usage: release_phase.sh --dry-run|--rehearse|--cut [VER]}"
VER="${2:-}"

REPO="/c/Users/viper/AIGEN_SYS/repos/mindpalace"
JAVA_HOME="C:/Program Files/Java/jdk-17"
JAVA="$JAVA_HOME/bin/java"
MVN="C:\\ProgramData\\chocolatey\\lib\\maven\\apache-maven-3.9.16\\bin\\mvn.cmd"
LOGDIR="/tmp/release"
GH_REPO="chrisalunlloyd2-sudo/mindpalace"
# Version files: the three edited by bump() + the shade-plugin-written
# dependency-reduced-pom.xml (tracked; a build rewrites it with the new
# version — it must revert on every fail path and join the --cut commit).
VFILES="pom.xml INSTALLER.md README.md dependency-reduced-pom.xml"
revert_versions() { git checkout -- $VFILES 2>/dev/null || true; }
mkdir -p "$LOGDIR"
cd "$REPO" || exit 1
log() { printf '[release %s] %s\n' "$(date +%H:%M:%S)" "$*"; }
die() { log "FATAL: $*"; exit 1; }

[ "$MODE" = "--dry-run" ] || [ "$MODE" = "--rehearse" ] || [ "$MODE" = "--cut" ] \
  || die "mode must be --dry-run, --rehearse or --cut"

if [ -z "$VER" ]; then
  CUR=$(sed -n 's|.*<version>\(.*\)</version>.*|\1|p' pom.xml | head -1)
  die "VER required (current pom: $CUR — suggested next: dry-run with no-arg check first)"
fi
echo "$VER" | grep -qE '^[0-9]+\.[0-9]+\.[0-9]+(-beta[0-9]+)?$' \
  || die "VER '$VER' must look like 1.2.0-beta1"
TAG="v$VER"
VER_NUM=$(echo "$VER" | cut -d- -f1)

git rev-parse -q --verify "refs/tags/$TAG" >/dev/null && die "tag $TAG exists locally"
if git ls-remote --tags origin "refs/tags/$TAG" 2>/dev/null | grep -q "$TAG"; then
  die "tag $TAG already exists on origin"
fi
git diff --quiet -- $VFILES 2>/dev/null || die "version files have uncommitted edits"

bump() {
  # ONLY the project version — the line right after <artifactId>mindpalace</artifactId>.
  # A blind s|<version>.*</version>|g would clobber ${lwjgl.version} deps
  # (caught by the first dry-run: 38 pom lines instead of 1).
  sed -i "/<artifactId>mindpalace<\/artifactId>/{n;s|<version>.*</version>|<version>$VER</version>|}" pom.xml
  sed -i "s|MindPalace-Setup-[0-9][0-9a-z.\-]*\.exe|MindPalace-Setup-$VER.exe|g; s|MindPalace-[0-9][0-9a-z.\-]*\.exe|MindPalace-$VER.exe|g" INSTALLER.md
  sed -i "s|\*\*v[0-9][0-9a-z.\-]*\*\*|**v$VER**|g" README.md
}

build_notes() {
  local notes_file="$1"
  local prev_tag=""
  prev_tag=$(git tag --sort=-version:refname | grep -vx "$TAG" | head -1 || true)
  {
    echo "MindPalace **$VER** — autonomous cascade release (steps 89-90)."
    echo ""
    echo "## Validation"
    echo "- selftest: PASS ($EVID_PASS/0) — target/selftest_result.json"
    echo "- e2e: waypoint tour green, all shots non-black"
    echo "- commit: $(git rev-parse --short HEAD)"
    echo "- installer: bundled JRE, no Java needed on target"
    echo ""
    echo "## Assets"
    echo "- jar: $(basename "$JAR")"
    echo "- installer: $(basename "$EXE")"
    echo ""
    echo "## Changelog (commits)"
    if [ -n "$prev_tag" ]; then
      echo "Range: \`$prev_tag..$TAG\`"
      git log --no-merges --pretty='- %s (%h)' "$prev_tag..HEAD"
    else
      echo "Range: initial history .. \`$TAG\` (latest 50 commits)"
      git log --no-merges --pretty='- %s (%h)' -n 50
    fi
  } > "$notes_file"
}

if [ "$MODE" = "--dry-run" ]; then
  log "--- DRY RUN: $VER (no files touched) ---"
  bump && git --no-pager diff --stat && git --no-pager diff -U1 -- pom.xml INSTALLER.md README.md | head -40
  revert_versions
  log "DRY RUN COMPLETE: edits above would be made, version files untouched."
  exit 0
fi

log "mode=$MODE VER=$VER tag=$TAG"
bump
git diff --stat | head -5
grep -q "<version>$VER</version>" pom.xml || { revert_versions; die "pom bump failed"; }

# ── 2b. sweep game processes (MP invariant: ONE game, never run the locked
#        live jar copy for gates; gates run the fresh target jar) ───────
sweep_java() {
  tasklist | grep -iE "^java(w)?\.exe" | awk '{print $2}' | while read -r pid; do
    log "sweep: killing java pid $pid"
    taskkill /PID "$pid" /F >/dev/null 2>&1 || true
  done
  # PID row vanishes from tasklist before the process fully tears down, so a
  # single pass can still race and count a dying proc (first rehearsal died
  # here right after killing 4772). KILL TWICE, then judge.
  sleep 3
  tasklist | grep -iE "^java(w)?\.exe" | awk '{print $2}' | while read -r pid; do
    log "sweep: second pass — killing java pid $pid"
    taskkill /PID "$pid" /F >/dev/null 2>&1 || true
  done
  sleep 3
  tasklist | grep -icE "^java(w)?\.exe" || true
}
log "sweep: killing ALL java/javaw (ONE-game invariant)"
LEFT=$(sweep_java)
[ "$LEFT" = "0" ] || { revert_versions; die "sweep failed — $LEFT java procs still alive (version edits reverted)"; }
log "sweep: zero java procs confirmed"

# ── 3. build ──────────────────────────────────────────────────────────
log "build: mvn clean package (skip tests)"
export JAVA_HOME
if ! timeout 300 cmd.exe /c "$MVN -q -DskipTests clean package" > "$LOGDIR/build.log" 2>&1; then
  tail -15 "$LOGDIR/build.log"; revert_versions
  die "build failed — version edits reverted"
fi
JAR=$(ls target/mindpalace-*.jar 2>/dev/null | grep -vE 'original-|-shaded' | head -1)
[ -n "$JAR" ] || { revert_versions; die "no jar after build"; }
echo "$JAR" | grep -q "mindpalace-$VER.jar" || { revert_versions; die "built jar is not $VER: $JAR"; }
log "build: OK ($JAR)"

# ── 4. selftest ───────────────────────────────────────────────────────
log "selftest: test_bot --run-selftest"
# Canonical budget (recipes + sync step 5): the real suite needs ~430s;
# 110 (cascade's quick-check value) SIGTERMed the suite mid-run in the
# first rehearsal — rc 143 at exactly 111s. A release gate gets 500.
if ! timeout 500 python scripts/test_bot.py --run-selftest > "$LOGDIR/selftest.log" 2>&1; then
  # Flake class seen live 2026-10-03: java --selftest hung the full internal
  # 120s (returncode null, empty result) on identical input that passed 45s
  # one run earlier — same class as the #120 hung-JVM docs. Retry ONCE;
  # a genuinely broken suite fails twice and dies here anyway.
  tail -5 "$LOGDIR/selftest.log"
  log "selftest: first run failed — retrying once (flake class: hung JVM)"
  if ! timeout 500 python scripts/test_bot.py --run-selftest > "$LOGDIR/selftest.log" 2>&1; then
    tail -10 "$LOGDIR/selftest.log"; revert_versions
    die "selftest failed twice — broken phase is NOT released"
  fi
fi
# Gate on the REAL result line (verified from a live run 2026-10-03):
#   "result": "===== RESULT: 55 passed, 0 failed ====" — my earlier
# '"result": "PASS"' pattern was a design assumption that never matched the
# actual JSON; attempt-3 rehearsal died on it despite a 55/0 suite.
grep -aq 'RESULT: [0-9]* passed, 0 failed' target/selftest_result.json 2>/dev/null \
  || { revert_versions; die "selftest result not PASS"; }
EVID_PASS=$(grep -ao 'RESULT: [0-9]* passed' target/selftest_result.json | head -1 | grep -o '[0-9]*')
log "selftest: PASS ($EVID_PASS/0)"

# ── 5. e2e ────────────────────────────────────────────────────────────
SHOTS="$REPO/target/e2e-shots-rel"; rm -rf "$SHOTS"; mkdir -p "$SHOTS"
log "e2e: waypoint tour on $JAR"
if ! timeout 120 "$JAVA" -Dprism.order=sw -Dprism.vsync=false \
    -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -Xms256m -Xmx768m \
    -jar "$JAR" --e2e "$(cygpath -m "$SHOTS")" > "$LOGDIR/e2e.log" 2>&1; then
  tail -10 "$LOGDIR/e2e.log"; revert_versions
  die "e2e failed — broken phase is NOT released"
fi
# Windows python can't read MSYS /c/... paths (raw arg = "NO SHOTS FOUND"
# despite 17 shots on disk; attempt-4). Same conversion the java arg gets.
python scripts/test_bot.py --verify-shots "$(cygpath -m "$SHOTS")" || { revert_versions; die "shot verification failed"; }
log "e2e: all waypoints non-black"

# ── 6. jpackage exe ───────────────────────────────────────────────────
log "jpackage: building installer (numeric app-version $VER_NUM)"
JPACKAGE="$JAVA_HOME/bin/jpackage.exe"
REPO_WIN="C:\\Users\\viper\\AIGEN_SYS\\repos\\mindpalace"
OUT_DIR_WIN="$REPO_WIN\\installer"
ICON_ARG=()
[ -f "$REPO/installer/MindPalace/MindPalace.ico" ] && ICON_ARG=(--icon "$REPO_WIN\\installer\\MindPalace\\MindPalace.ico")
if ! "$JPACKAGE" --type exe --name MindPalace --app-version "$VER_NUM" \
    --input "$REPO_WIN\\target" --main-jar "$(basename "$JAR")" \
    --main-class com.mindpalace.Main --dest "$OUT_DIR_WIN" \
    --win-shortcut --win-menu --win-menu-group MindPalace --win-dir-chooser \
    --win-per-user-install --vendor AIGEN_SYS \
    --description "3D First-Person GitHub Repository Explorer" \
    --java-options "-Dprism.order=sw -Dprism.vsync=false -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -Xms256m -Xmx768m" \
    "${ICON_ARG[@]}" > "$LOGDIR/jpackage.log" 2>&1; then
  tail -10 "$LOGDIR/jpackage.log"; revert_versions
  die "jpackage failed"
fi
EXE="$REPO/installer/MindPalace-$VER.exe"
[ -f "$REPO/installer/MindPalace-$VER_NUM.exe" ] && mv -f "$REPO/installer/MindPalace-$VER_NUM.exe" "$EXE"
[ -f "$EXE" ] || { revert_versions; die "expected exe missing: $EXE"; }
SIZE_MB=$(( $(stat -c %s "$EXE") / 1048576 ))
[ "$SIZE_MB" -ge 40 ] && [ "$SIZE_MB" -le 150 ] || { revert_versions; die "exe size ${SIZE_MB}MB outside 40-150 sanity"; }
log "exe: $EXE (${SIZE_MB} MB)"

# ── 6b. relaunch the ONE game (MP invariant: never leave the world dark) ─
LEFT2=$(sweep_java)
[ "$LEFT2" = "0" ] || { revert_versions; die "pre-relaunch sweep failed — $LEFT2 java procs alive (version edits reverted)"; }
cp -f "$JAR" mindpalace-live.jar
# jar path RELATIVE (Windows javaw can't open MSYS /c/... paths — sync step 1b)
JAVA_HOME="$JAVA_HOME" nohup "$JAVA_HOME/bin/javaw.exe" -jar mindpalace-live.jar \
    >> "$REPO/game_console.log" 2>&1 &
disown
sleep 8
GAME_PID=$(tasklist | grep -iE "^javaw\.exe" | awk '{print $2}' | head -1)
[ -n "$GAME_PID" ] || { revert_versions; die "relaunch failed — no javaw after launch"; }
log "relaunch: ONE game running (pid $GAME_PID, live jar → $VER)"

# ── 7. ship (cut only) ────────────────────────────────────────────────
if [ "$MODE" = "--cut" ]; then
  git add $VFILES
  git commit -m "release: $VER (steps 89-90 auto-bump + stamp)" >/dev/null || die "commit failed"
  git tag "$TAG"
  git push origin HEAD:main >/dev/null 2>&1 && git push origin "$TAG" >/dev/null 2>&1 || die "push failed — tag stays local, rerun --cut after resolving"
  NOTES="$LOGDIR/notes-$VER.md"
  build_notes "$NOTES"
  if gh release create "$TAG" "$EXE" "$JAR" --repo "$GH_REPO" --title "MindPalace v$VER" \
      --notes-file "$NOTES" > "$LOGDIR/gh_release.out" 2>&1; then
    RURL=$(head -1 "$LOGDIR/gh_release.out")
    log "release created: $RURL"
    ISSUE=$(cat .cascade_issue 2>/dev/null || true)
    [ -n "$ISSUE" ] && printf '%s: release **%s** cut on green (%s)\n' \
      "$(date +%Y-%m-%dT%H:%M)" "$TAG" "$RURL" \
      | gh issue comment "$ISSUE" --repo "$GH_REPO" --body-file - >/dev/null 2>&1 \
      && log "step-log: posted to #$ISSUE" || true
  else
    cat "$LOGDIR/gh_release.out"; die "gh release create failed — commit/tag pushed, asset local: $EXE"
  fi
  log "CUT COMPLETE: $TAG live, version commit left on main"
else
  revert_versions
  git diff --quiet -- $VFILES 2>/dev/null || die "revert check failed — inspect git diff"
  log "rehearsal: version edits reverted byte-exact, tree clean"
  log "REHEARSE COMPLETE: $VER pipeline green (build/selftest/e2e/jpackage) — no remote writes"
fi
