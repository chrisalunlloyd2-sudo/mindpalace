#!/usr/bin/env bash
# lane.sh -- start, stop and prove the LoRA lane: one local llama.cpp server holding ONE base model plus every adapter in
# the adapters folder. Ollama refuses adapters, so this is where a LoRA swap is real. Loopback only, one instance only.
#
#   bash scripts/lane.sh run      start in the foreground (what a supervisor runs); refuses when one is up or RAM is short
#   bash scripts/lane.sh start    start in the background, wait until the model has loaded (about 3 min from the HDD)
#   bash scripts/lane.sh status   up or down, with the adapters it holds
#   bash scripts/lane.sh probe    prove the swap through the game's own client (LaneProbe); writes a proof file
#   bash scripts/lane.sh stop     stop the lane on this port (never an Ollama runner: it matches the port in the command line)
#
# The lane is a resident model: with it up, Ollama may keep only ONE model loaded (the law is two resident in total).
# Exit: 0 ok, 1 failed, 75 box too busy (retry later; never a failure).
set -uo pipefail

HOME_DIR="${AIGEN_HOME:-$HOME/AIGEN_SYS}"
LORA="$HOME_DIR/lora"
PORT="${LANE_PORT:-18080}"
URL="http://127.0.0.1:$PORT"
SERVER="${LANE_SERVER:-$LORA/llama.cpp/bin/llama-server.exe}"
MODEL_TAG="${LANE_MODEL_TAG:-qwen2.5/0.5b}"
MIN_FREE_MB="${LANE_MIN_FREE_MB:-900}"
LOG="$LORA/lane_server.log"
JAVA="${JAVA:-C:/Program Files/Java/jdk-17/bin/java}"

up() { curl -s -m 3 "$URL/health" 2>/dev/null | grep -q '"ok"'; }
# The server does not listen while it reads the model (about 3 min from the HDD), so "is the port open" cannot tell a
# loading lane from none. The process list can: the pid of the llama-server that was started on OUR port, or nothing.
lane_pid() {
  powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='llama-server.exe'\" | Where-Object { \$_.CommandLine -match '--port $PORT( |\$)' } | Select-Object -First 1 -ExpandProperty ProcessId" 2>/dev/null | tr -d '\r\n '
}

blob() { # the base model file Ollama already downloaded: no second copy on the small disk
  local man="$HOME/.ollama/models/manifests/registry.ollama.ai/library/$MODEL_TAG"
  [ -f "$man" ] || { echo "no Ollama manifest for $MODEL_TAG" >&2; return 1; }
  local d; d="$(python3 -I -c 'import json,sys
for l in json.load(open(sys.argv[1]))["layers"]:
    if l["mediaType"].endswith("image.model"): print(l["digest"].replace(":","-"))' "$(cygpath -m "$man" 2>/dev/null || echo "$man")")"
  [ -n "$d" ] && [ -f "$HOME/.ollama/models/blobs/$d" ] || { echo "model blob missing for $MODEL_TAG" >&2; return 1; }
  echo "$HOME/.ollama/models/blobs/$d"
}

args() { # prints the server arguments, one per line
  local m; m="$(blob)" || return 1
  echo "-m"; cygpath -m "$m" 2>/dev/null || echo "$m"
  local n=0 a
  for a in "$LORA"/adapters/*.gguf; do   # sorted by name: the adapter ids are stable from one start to the next
    [ -f "$a" ] || continue
    echo "--lora"; cygpath -m "$a" 2>/dev/null || echo "$a"; n=$((n+1))
  done
  [ "$n" -gt 0 ] || { echo "no adapter in $LORA/adapters" >&2; return 1; }
  # the scales travel in every request; this flag only makes a request that forgot them run the base model, where the server honours it
  printf '%s\n' --lora-init-without-apply --host 127.0.0.1 --port "$PORT" -c "${LANE_CTX:-1024}" -t "${LANE_THREADS:-2}" -np 1 --no-webui
}

guard() {
  [ -x "$SERVER" ] || { echo "lane: no server at $SERVER"; return 1; }
  local pid; pid="$(lane_pid)"
  if [ -n "$pid" ]; then echo "lane: one is already running for port $PORT (pid $pid)"; return 1; fi
  local free; free="$(python3 -I -c 'import psutil;print(int(psutil.virtual_memory().available/1048576))' 2>/dev/null || echo 0)"
  if [ "$free" -lt "$MIN_FREE_MB" ]; then echo "lane: BUSY only ${free} MB free (< $MIN_FREE_MB)"; return 75; fi
}

case "${1:-}" in
  run)
    guard || exit $?
    ARGS="$(args)" || exit 1
    mapfile -t A <<< "$ARGS"
    echo "$(date +%Y-%m-%dT%H:%M:%S) start ${A[*]}" >> "$LORA/lane_start.ts"
    exec "$SERVER" "${A[@]}" >> "$LOG" 2>&1
    ;;
  start)
    guard || exit $?
    ARGS="$(args)" || exit 1
    mapfile -t A <<< "$ARGS"
    echo "$(date +%Y-%m-%dT%H:%M:%S) start ${A[*]}" >> "$LORA/lane_start.ts"
    nohup "$SERVER" "${A[@]}" >> "$LOG" 2>&1 &
    for _ in $(seq 1 "${LANE_WAIT_TRIES:-80}"); do   # 80 x 5 s: the load from a spinning disk takes about 3 min
      up && { echo "lane: up on $URL (pid $(lane_pid))"; exit 0; }
      [ -e "/proc/$!" ] || { echo "lane: the server died while loading; see $LOG"; exit 1; }
      sleep 5
    done
    echo "lane: still loading after the wait; check: bash scripts/lane.sh status"; exit 1
    ;;
  status)
    if up; then echo "lane: up on $URL"; curl -s -m 5 "$URL/lora-adapters" | python3 -I -c 'import json,sys
for a in json.load(sys.stdin): print("  adapter %d %s" % (a["id"], a["path"].replace("\\","/").split("/")[-1]))'
    elif [ -n "$(lane_pid)" ]; then echo "lane: loading (pid $(lane_pid))"; exit 1
    else echo "lane: down"; exit 1; fi
    ;;
  probe)
    up || { echo "lane: down (bash scripts/lane.sh start)"; exit 1; }
    cd "$(dirname "${BASH_SOURCE[0]}")/.." || exit 1
    CP="target/classes;$(cat target/local_ci.cp 2>/dev/null)"
    OUT="$LORA/lane_probe_$(date +%Y%m%d_%H%M%S).json"
    "$JAVA" -Xmx128m -cp "$CP" com.mindpalace.agent.lane.LaneProbe "$URL" "$(cygpath -m "$LORA/bank.json")" "$(cygpath -m "$OUT")"
    rc=$?; echo "proof: $OUT"; exit $rc
    ;;
  stop)
    PID="$(lane_pid)"
    [ -n "$PID" ] || { echo "lane: not running"; exit 0; }
    taskkill //PID "$PID" //F > /dev/null 2>&1 && echo "lane: stopped (pid $PID)" || { echo "lane: could not stop pid $PID"; exit 1; }
    ;;
  *) echo "usage: lane.sh run|start|status|probe|stop"; exit 2 ;;
esac
