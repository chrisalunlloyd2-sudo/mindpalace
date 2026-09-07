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

# 1. Pull remote (fast-forward only, never clobber local work)
git pull --ff-only origin main >/dev/null 2>&1

# 2. Any local changes to ship?
if [ -z "$(git status --porcelain)" ] && [ -z "$(git log --oneline origin/main..HEAD 2>/dev/null)" ]; then
    # Nothing to do — stay quiet (watchdog pattern: empty stdout = silent)
    exit 0
fi

# 3. Commit any uncommitted changes
if [ -n "$(git status --porcelain)" ]; then
    git add -A
    git commit -q -m "auto-sync: $(date '+%Y-%m-%d %H:%M')" 2>/dev/null
fi

# 4. Rebuild (kill running game to release the jar lock)
wmic process where "name='java.exe'" get processid 2>/dev/null | grep -E "[0-9]" | while read p; do taskkill /F /PID $p 2>/dev/null; done
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

# 5. Self-test gate
SELFTEST=$("$JAVA_HOME/bin/java" -jar target/mindpalace-1.0.0.jar --selftest 2>&1)
if ! echo "$SELFTEST" | grep -q "0 failed"; then
    echo "mindpalace-sync: SELFTEST FAILED — not pushing"
    exit 1
fi

# 6. Push source
git push origin main >/dev/null 2>&1

# 6b. Push model chat logs to the PRIVATE archive repo (never public).
#     Per-day files (chat-YYYY-MM-DD.jsonl) + legacy chat.jsonl.
#     Uses a PERSISTENT path under AIGEN_SYS (NOT /tmp, which is wiped on reboot
#     and silently dropped 4 days of logs). Uses token auth + branch -M main so
#     the push always targets the repo's real default branch.
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
    if ! git -C "$CHAT_REPO" diff --cached --quiet 2>/dev/null; then
        git -C "$CHAT_REPO" commit -q -m "auto-sync chat logs $(date '+%Y-%m-%d %H:%M')" 2>/dev/null
    fi
    # Token-authenticated push so it can't fail on credential prompt / wrong branch.
    TOKEN=$(printf "protocol=https\nhost=github.com\n\n" | \
      "C:/Users/viper/AppData/Local/hermes/git/mingw64/bin/git-credential-manager.exe" get 2>/dev/null | \
      grep -E "^password=" | cut -d= -f2-)
    if [ -n "$TOKEN" ]; then
        git -C "$CHAT_REPO" push -q "https://chrisalunlloyd2-sudo:$TOKEN@github.com/chrisalunlloyd2-sudo/mindpalace-chat-logs.git" main 2>/dev/null \
            && echo "mindpalace-sync: chat logs pushed ($(ls chat_logs/*.jsonl 2>/dev/null | wc -l) files)" \
            || echo "mindpalace-sync: chat log push FAILED"
    fi
fi

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
      --data-binary "@target/mindpalace-1.0.0.jar" \
      "https://uploads.github.com/repos/chrisalunlloyd2-sudo/mindpalace/releases/$RELEASE_ID/assets?name=mindpalace-1.0.0.jar" -o /dev/null
fi

echo "mindpalace-sync: pushed + release binary refreshed ($(date '+%H:%M'))"

# 8. Relaunch the live game if nothing's running it. Step 4 killed every
#    java.exe before rebuilding, and step 5's --selftest run has already
#    exited by this point (captured via command substitution above) — so
#    any java.exe still alive here is stale. Without this, a rebuild leaves
#    the world dark (no live AgentManager/quorum loop) until someone
#    notices and launches it by hand.
if ! tasklist //FI "IMAGENAME eq java.exe" 2>/dev/null | grep -q java.exe; then
    JAVA_HOME="$JAVA_HOME" nohup "$JAVA_HOME/bin/javaw.exe" -jar "$REPO/target/mindpalace-1.0.0.jar" \
        > "$REPO/game_console.log" 2>&1 &
    disown
    echo "mindpalace-sync: relaunched live game (was not running)"
fi
