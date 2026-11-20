#!/usr/bin/env bash
# backup-to-usb.sh — mirror the critical AIGEN_SYS state to the USB drive (D:).
#
# Rebuilt 2026-11-20 (LIVE-4, task t_9bc9210e) after nightly cron timeouts:
# on 2026-11-19/20 the pure-bash `cp -ru` stage-1 pass spent minutes per tree
# stat'ing the target on a 100%-full HDD (D: 32G/32G) while Ollama ran CPU
# inference at ~10 tok/s, driving the run over the 1800s script_timeout.
#
# Design now:
#   - copy engine = robocopy /XO (newer-only, merge-only, never deletes)
#   - heavy trees that live in git anyway (repos' .git, worktrees, node_modules,
#     target/, __pycache__, telemetry WAL/SHM) are EXCLUDED — the off-site
#     copy is GitHub (push = backup for everything under repos/)
#   - stage-1 (AIGEN_SYS minus repos) first so the single biggest tree cannot
#     starve the rest of the schedule again.
#   - exit code is HONEST: 1 only when a stage's robocopy really failed (>=8);
#     stage failures do not abort the run (best effort) but are reported.
#
# Idempotent: only copies newer files. Never deletes on the target (merge-only,
# per Architect's "never delete, only add/merge" rule).
set -uo pipefail

USB="/d/mindpalace_backup"
SRC_HOME="/c/Users/viper"
LOG="$SRC_HOME/cron_logs/usb_backup.log"

mkdir -p "$USB" "$SRC_HOME/cron_logs"

log() { echo "[$(date '+%F %T')] $1" | tee -a "$LOG"; }

# Detect the USB drive; bail cleanly if absent.
if [ ! -d "/d" ] || ! df -h /d >/dev/null 2>&1; then
    log "USB drive D: not present — skipping backup"
    exit 0
fi

# Free-space gate: bail cleanly with an explained skip when < 5MB free. The
# drive is expected to be ~full; a merge-only mirror can't free space itself.
FREE_KB=$(df -k /d | awk 'NR==2{print $4}')
if [ "${FREE_KB:-0}" -lt 5120 ]; then
    log "FATAL: D: effectively full (${FREE_KB}KB free) — merge-only mirror needs operator pruning; skipping backup (explained skip)"
    exit 0
fi

ROBO="/c/Windows/System32/Robocopy.exe"
[ -x "$ROBO" ] || ROBO="$(command -v robocopy.exe)"

# robocopy exit codes: 0-7 ok, >=8 real failure.
rc_report() {
    case $1 in
        0|1) echo "ok" ;;
        2|3) echo "ok (extra files on target)" ;;
        4|5|6|7) echo "ok (mismatches)" ;;
        *) echo "FAILED rc=$1" ;;
    esac
}

# stage <label> <src> <dst> <extra args...>
stage() {
    local label="$1" src="$2" dst="$3"; shift 3
    local t0=$(date +%s)
    log "$label: robocopy → $dst"
    MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL="*" "$ROBO" \
        "$(cygpath -w "$src")" "$(cygpath -w "$dst")" \
        /XO /NFL /NDL /NJH /R:0 /W:0 "$@" >/dev/null 2>&1
    local rc=$?
    local t1=$(date +%s)
    log "$label: $(rc_report $rc) in $((t1-t0))s"
    if [ "$rc" -ge 8 ]; then
        FAILED=1   # record real robocopy failure; don't abort (best effort), honest summary at the end
    fi
}

FAILED=0

log "USB backup started → $USB"

# 1. AIGEN_SYS minus repos (KG, dashboards, todo_management, memory, reports).
#    repos/ is excluded here and mirrored with its own pass.
AIG_SRC="$SRC_HOME/AIGEN_SYS"
AIG_DST="$USB/C/AIGEN_SYS"
if [ -d "$AIG_SRC" ]; then
    mkdir -p "$AIG_DST/repos"
    stage "aigen (minus repos)" "$AIG_SRC" "$AIG_DST" \
        /XD repos .git node_modules __pycache__ \
            target worktrees dist venv .venv site-packages \
        /XF "*.pyc" "*.tmp" "*.db-wal" "*.db-shm"
fi

# 2. repos: content only — .git and build/dependency dirs are excluded; the
#    push-based GitHub copy is the real backup for git history.
if [ -d "$AIG_SRC/repos" ]; then
    stage "repos (content)" "$AIG_SRC/repos" "$AIG_DST/repos" \
        /S /XD .git node_modules __pycache__ target worktrees \
            dist venv .venv site-packages .gradle build \
        /XF "*.pyc" "*.tmp" "*.db-wal" "*.db-shm"
fi

# 3. Hermes config + scripts + skills (the agent's own brain) — exclude the
#    bundled git toolchain (large binary blob), caches and audio_cache.
HERM_SRC="$SRC_HOME/AppData/Local/hermes"
HERM_DST="$USB/C/AppData/Local/hermes"
if [ -d "$HERM_SRC" ]; then
    stage "hermes" "$HERM_SRC" "$HERM_DST" \
        /XD git git-cache __pycache__ audio_cache .git \
        /XF "*.pyc" "*.tmp" "*.log" "*.wav" "*.db-wal" "*.db-shm"
fi

# 4. Databases (keyword/kg/code) + cron_logs + Desktop task lists (CHRISSTEPS)
for pair in "databases" "cron_logs" "Desktop"; do
    stage "$pair" "$SRC_HOME/$pair" "$USB/C/$pair" \
        /XF "*.db-wal" "*.db-shm" "*.tmp"
done

# Honest summary: a stage that failed marks the run FAILED → non-zero exit so
# cron_status.json records an error instead of a silent partial backup.
if [ "$FAILED" -ne 0 ]; then
    log "USB backup COMPLETE WITH ERRORS — see $LOG"
    exit 1
fi

log "USB backup complete"
