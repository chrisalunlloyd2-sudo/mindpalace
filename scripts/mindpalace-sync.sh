#!/usr/bin/env bash
# mindpalace-sync.sh — 30-min auto-sync: git pull/commit/push + rebuild + release binary.
# Idempotent and quiet when nothing changed. Only rebuilds (and briefly restarts
# the game) when there are actual source changes to ship.
set -u
REPO="/c/Users/viper/AIGEN_SYS/repos/mindpalace"
JAVA_HOME="C:/Program Files/Java/jdk-17"
M2_HOME="C:/ProgramData/chocolatey/lib/maven/apache-maven-3.9.16"
RELEASE_ID="372054705"
cd "$REPO" || exit 1

# 6b as a function so BOTH the nothing-to-ship early exit and the
# after-push path can run it. Pushes model chat logs to the PRIVATE
# archive repo (never public). Per-day files (chat-YYYY-MM-DD.jsonl) +
# legacy chat.jsonl. Uses a PERSISTENT path under AIGEN_SYS (NOT /tmp,
# which is wiped on reboot and silently dropped 4 days of logs). Uses
# token auth + branch -M main so the push always targets the repo's
# real default branch.
sync_chat_logs() {
    CHAT_REPO="/c/Users/viper/AIGEN_SYS/mindpalace-chat-logs"
    if [ -d "chat_logs" ] && [ -n "$(ls chat_logs/*.jsonl 2>/dev/null)" ]; then
        mkdir -p "$CHAT_REPO"
        cp chat_logs/*.jsonl "$CHAT_REPO/" 2>/dev/null
        if [ ! -d "$CHAT_REPO/.git" ]; then
            git -C "$CHAT_REPO" init -q 2>/dev/null
            git -C "$CHAT_REPO" remote add origin "https://github.com/chrisalunlloyd2-sudo/mindpalace-chat-logs.git" 2>/dev/null
            git -C "$CHAT_REPO" pull -q origin main 2>/dev/null
        fi
        git -C "$CHAT_REPO" branch -M main 2>/dev/null
        git -C "$CHAT_REPO" add -A 2>/dev/null
        COMMITTED=0
        if ! git -C "$CHAT_REPO" diff --cached --quiet 2>/dev/null; then
            git -C "$CHAT_REPO" commit -q -m "auto-sync chat logs $(date '+%Y-%m-%d %H:%M')" 2>/dev/null && COMMITTED=1
        fi
        # Token-authenticated push so it can't fail on credential prompt / wrong
        # branch. Push when there is new data OR when an earlier push failed and
        # commits are still pending (catch-up). Stay watchdog-silent otherwise —
        # a silent tick means nothing changed and nothing is pending.
        TOKEN=$(printf "protocol=https\nhost=github.com\n\n" | \
          "C:/Users/viper/AppData/Local/hermes/git/mingw64/bin/git-credential-manager.exe" get 2>/dev/null | \
          grep -E "^password=" | cut -d= -f2-)
        NEED_PUSH=$COMMITTED
        if [ "$(git -C "$CHAT_REPO" rev-list --count origin/main..main 2>/dev/null)" != "0" ]; then
            NEED_PUSH=1
        fi
        if [ -n "$TOKEN" ] && [ "$NEED_PUSH" = "1" ]; then
            if git -C "$CHAT_REPO" push -q "https://chrisalunlloyd2-sudo:$TOKEN@github.com/chrisalunlloyd2-sudo/mindpalace-chat-logs.git" main 2>/dev/null; then
                [ "$COMMITTED" = "1" ] && echo "mindpalace-sync: chat logs pushed ($(ls chat_logs/*.jsonl 2>/dev/null | wc -l) files)"
            else
                echo "mindpalace-sync: chat log push FAILED"
            fi
        fi
    fi
    # Explicit rc: the trailing `[ ... ] && echo` pattern would otherwise leak
    # a 1 on catch-up pushes with no new commit. Both call sites tolerate it,
    # but the function should not have a surprise contract.
    return 0
}

# 1. Pre-flight sweep (nothing lives forever / one game at a time).
#    The early-exit path below used to leave stray java alive for hours:
#    2026-09-27 a hung --selftest java sat for 3h — blocked relaunch, froze
#    telemetry, fooled the monitor's game_running check.
#    Reap any java older than 30 min EXCEPT the real live game
#    (mindpalace-live.jar / mindpalace-*.jar on its command line) — killing
#    the game here would cycle the world every 30 min on the early-exit
#    path where step 8 never runs. Strays die; the game lives.
for img in java.exe javaw.exe; do
    wmic process where "name='$img'" get processid,creationdate,commandline 2>/dev/null \
      | grep -E "20[0-9]{12}" \
      | grep -ivE "mindpalace" \
      | while read -r cdate pid; do
            # wmic CreationDate: 20260927074420.xxx-420 → compare as epoch
            y=${cdate:0:4}; mo=${cdate:4:2}; d=${cdate:6:2}
            h=${cdate:8:2}; mi=${cdate:10:2}; s=${cdate:12:2}
            cepoch=$(date -d "$y-$mo-$d $h:$mi:$s" +%s 2>/dev/null) || continue
            now=$(date +%s)
            age=$(( now - cepoch ))
            if [ "$age" -gt 1800 ]; then
                taskkill //F //PID "$pid" 2>/dev/null \
                  && echo "mindpalace-sync: reaped stale $img pid=$pid (age ${age}s)"
            fi
        done
done
sleep 2

# 1b. Self-heal: if the world is dark (no real mindpalace game process),
#     relaunch the live jar BEFORE the early-exit test. The game may have
#     crashed hours ago while ticks kept taking the nothing-to-ship exit —
#     2026-09-27 the world sat dark all morning because only the rebuild
#     path relaunched it. Slow is fine; dark forever is not.
GAME_PID=$(wmic process where "name='javaw.exe' or name='java.exe'" get processid,commandline,threadcount 2>/dev/null \
  | grep -i "mindpalace" | awk '$NF==1{next} {print $(NF-1)}' | head -1)
if [ -z "$GAME_PID" ] && [ -f "mindpalace-live.jar" ]; then
    # Windows javaw cannot open MSYS paths (/c/Users/...) — pass the jar
    # RELATIVE (cwd is $REPO). 2026-09-27: every relaunch used "$REPO/..."
    # → 'Unable to access jarfile' → 1-thread javaw error dialog that sat
    # there forever AND fooled game_running. The world was never dark
    # because of a crash — it was dark because relaunch always failed.
    JAVA_HOME="$JAVA_HOME" nohup "$JAVA_HOME/bin/javaw.exe" -jar mindpalace-live.jar \
        >> "$REPO/game_console.log" 2>&1 &
    disown
    echo "mindpalace-sync: world was dark — relaunched live game (self-heal)"
    sleep 5
fi

# 1c. Pull remote (fast-forward only, never clobber local work)
git pull --ff-only origin main >/dev/null 2>&1

# 2. Any local changes to ship?
if [ -z "$(git status --porcelain)" ] && [ -z "$(git log --oneline origin/main..HEAD 2>/dev/null)" ]; then
    # Nothing to ship — but chat logs still sync on their own schedule
    # (fix 2026-09-25: early exit used to skip step 6b entirely, so logs
    # only landed on GitHub on days when source code also changed).
    sync_chat_logs
    exit 0
fi

# 3. Commit any uncommitted changes
if [ -n "$(git status --porcelain)" ]; then
    git add -A
    git commit -q -m "auto-sync: $(date '+%Y-%m-%d %H:%M')" 2>/dev/null
fi

# 4. Rebuild (kill running game to release the jar lock).
#    Must cover javaw.exe too: step 8 launches the game with javaw, and a
#    survivor keeps running while `clean package` rewrites the jar under it
#    -> lazy class loads fail (NoClassDefFoundError kotlin/okhttp, gist-wall-fetch).
for img in java.exe javaw.exe; do
    wmic process where "name='$img'" get processid 2>/dev/null | grep -E "[0-9]" | while read p; do taskkill //F //PID $p 2>/dev/null; done
done
sleep 2
BUILD_OUT=$("$JAVA_HOME/bin/java" -cp "$M2_HOME/boot/plexus-classworlds-2.11.0.jar" \
  "-Dclassworlds.conf=$M2_HOME/bin/m2.conf" \
  "-Dmaven.home=$M2_HOME" \
  "-Dmaven.multiModuleProjectDirectory=$REPO" \
  org.codehaus.plexus.classworlds.launcher.Launcher clean package 2>&1)
if ! echo "$BUILD_OUT" | grep -q "BUILD SUCCESS"; then
    echo "mindpalace-sync: BUILD FAILED — not pushing"
    exit 1
fi

# Resolve the built jar by glob: the pom version moved 1.0.0 -> 1.1.0-beta1
# and the hardcoded name silently broke selftest/release/relaunch.
BUILD_JAR=$(ls target/mindpalace-*.jar 2>/dev/null | grep -vE 'original-|-shaded' | head -1)
if [ -z "$BUILD_JAR" ]; then
    echo "mindpalace-sync: no built jar in target/ — not pushing"
    exit 1
fi

# 5. Self-test gate — HARD TIMEOUT. A hung selftest used to wedge this
#    pipeline forever (2026-09-27: zombie java for 3h, telemetry frozen,
#    real game never relaunched). 420s ≈ 3x the measured normal run
#    (2m16s on 2026-09-27); `timeout` kills the process tree on hang.
#    Slow is fine — but nothing runs forever.
SELFTEST=$(timeout 420 "$JAVA_HOME/bin/java" -jar "$BUILD_JAR" --selftest 2>&1)
if ! echo "$SELFTEST" | grep -q "0 failed"; then
    echo "mindpalace-sync: SELFTEST FAILED — not pushing"
    exit 1
fi

# 6. Push source
git push origin main >/dev/null 2>&1

# 6b. Push model chat logs to the PRIVATE archive repo (same logic as the
#     early-exit path — see sync_chat_logs() at top).
sync_chat_logs

# 7. Refresh release binary (delete old asset, upload new)
TOKEN=$(printf "protocol=https\nhost=github.com\n\n" | \
  "C:/Users/viper/AppData/Local/hermes/git/mingw64/bin/git-credential-manager.exe" get 2>/dev/null | \
  grep -E "^password=" | cut -d= -f2-)
if [ -n "$TOKEN" ]; then
    # find + delete existing jar asset
    ASSET_ID=$(curl -s -H "Authorization: token $TOKEN" \
      "https://api.github.com/repos/chrisalunlloyd2-sudo/mindpalace/releases/$RELEASE_ID/assets" \
      | grep -oE '"id": [0-9]+' | head -1 | grep -oE '[0-9]+')
    if [ -n "$ASSET_ID" ]; then
        curl -s -X DELETE -H "Authorization: token $TOKEN" \
          "https://api.github.com/repos/chrisalunlloyd2-sudo/mindpalace/releases/assets/$ASSET_ID" -o /dev/null
    fi
    curl -s -X POST -H "Authorization: token $TOKEN" \
      -H "Content-Type: application/java-archive" \
      --data-binary "@$BUILD_JAR" \
      "https://uploads.github.com/repos/chrisalunlloyd2-sudo/mindpalace/releases/$RELEASE_ID/assets?name=$(basename "$BUILD_JAR")" -o /dev/null
fi

echo "mindpalace-sync: pushed + release binary refreshed ($(date '+%H:%M'))"

# 8. Relaunch the live game if nothing's running it. Step 4 killed every
#    java.exe before rebuilding, and step 5's --selftest run has already
#    exited by this point (captured via command substitution above) — so
#    any java.exe still alive here is stale. Without this, a rebuild leaves
#    the world dark (no live AgentManager/quorum loop) until someone
#    notices and launches it by hand.
#    Frozen-jar rule: run a COPY, never target/ (next rebuild would swap it).
#    Gate on a REAL game process (mindpalace jar on the command line), not
#    bare tasklist: a stray java (selftest, build helper) used to count as
#    "game running" and silently suppressed relaunch for hours.
GAME_PID=$(wmic process where "name='javaw.exe' or name='java.exe'" get processid,commandline,threadcount 2>/dev/null \
  | grep -i "mindpalace" | awk '$NF==1{next} {print $(NF-1)}' | head -1)
if [ -z "$GAME_PID" ]; then
    cp -f "$BUILD_JAR" mindpalace-live.jar
    # jar path RELATIVE (Windows javaw can't open MSYS /c/... paths — see step 1b)
    JAVA_HOME="$JAVA_HOME" nohup "$JAVA_HOME/bin/javaw.exe" -jar mindpalace-live.jar \
        > "$REPO/game_console.log" 2>&1 &
    disown
    echo "mindpalace-sync: relaunched live game (was not running)"
fi
