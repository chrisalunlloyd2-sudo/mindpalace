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
