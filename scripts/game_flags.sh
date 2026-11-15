#!/usr/bin/env bash
# game_flags.sh -- the ONE place the live game's JVM flags are defined (NEXT_STEPS_151_250 step 152).
# Source it from any script that launches the game:  . "$(dirname "${BASH_SOURCE[0]}")/game_flags.sh"
# A relaunch that forgets these used to run with the JVM default heap (a quarter of RAM) and the
# GPU renderer; the small-box voter roster is NOT here because ModelConfig picks it by RAM (#347).
#   -Dmindpalace.voters=a,b,c  overrides the roster, =all forces the full 5.
# Vote timeout: 180000 is the reconciled live value (#327, see mindpalace-sync.sh history).
# Override per call with GAME_VOTE_TIMEOUT_MS=300000 when a cold-load cycle is expected.
GAME_VOTE_TIMEOUT_MS="${GAME_VOTE_TIMEOUT_MS:-180000}"
GAME_JAVA_FLAGS="-Dprism.order=sw -Dprism.vsync=false -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -Xms256m -Xmx768m -Dmindpalace.vote.timeoutMs=${GAME_VOTE_TIMEOUT_MS}"
[ -n "${GAME_VOTERS:-}" ] && GAME_JAVA_FLAGS="$GAME_JAVA_FLAGS -Dmindpalace.voters=${GAME_VOTERS}"
export GAME_JAVA_FLAGS

# Agent teams (off unless asked): GAME_TEAMS=1 turns the Ollama teams on; GAME_LANE=1 adds the lane seats
# (llama.cpp LoRA lane on 127.0.0.1:18080, `bash scripts/lane.sh start`; seats join only after a passing `lane.sh probe` from the last 24 h).
# The lane is a resident model: with it up Ollama keeps ONE model (law: 2 resident in total).
# A relaunch by anyone (the self-heal script sources this file) keeps the lane: touch ~/AIGEN_SYS/lora/game_lane.on to make it the default,
# remove the file to go back. Without the file and without GAME_LANE the flags below stay off.
[ -f "${AIGEN_HOME:-$HOME/AIGEN_SYS}/lora/game_lane.on" ] && GAME_LANE="${GAME_LANE:-1}"
if [ "${GAME_TEAMS:-0}" = 1 ]; then GAME_JAVA_FLAGS="$GAME_JAVA_FLAGS -Dmindpalace.teams=true"; fi
if [ "${GAME_LANE:-0}" = 1 ]; then GAME_JAVA_FLAGS="$GAME_JAVA_FLAGS -Dmindpalace.teams=true -Dmindpalace.lane.url=http://127.0.0.1:${LANE_PORT:-18080} -Dmindpalace.lane.wake=$(cygpath -m "$HOME/.aria/engine/lane_tick.cmd" 2>/dev/null || echo "$HOME/.aria/engine/lane_tick.cmd")"; fi
export GAME_JAVA_FLAGS
