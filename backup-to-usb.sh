#!/usr/bin/env bash
# backup-to-usb.sh — mirror the critical AIGEN_SYS state to the USB drive (D:).
#
# Syncs (rsync-style, via cp -ru for portability):
#   - AIGEN_SYS (repos, KG, dashboards)
#   - hermes config + scripts + skills (AppData/Local/hermes)
#   - databases (keyword/kg/code)
#   - cron_logs
#   - Desktop task lists (CHRISSTEPS)
#
# Idempotent: only copies newer files. Never deletes on the target (merge-only,
# per Architect's "never delete, only add/merge" rule).
set -uo pipefail

USB="/d/mindpalace_backup"
SRC_HOME="/c/Users/viper"
STAMP="$(date '+%Y-%m-%d_%H%M%S')"
LOG="$SRC_HOME/cron_logs/usb_backup.log"

mkdir -p "$USB" "$SRC_HOME/cron_logs"

log() { echo "[$(date '+%F %T')] $1" | tee -a "$LOG"; }

# Detect the USB drive; bail cleanly if absent.
if [ ! -d "/d" ] || ! df -h /d >/dev/null 2>&1; then
    log "USB drive D: not present — skipping backup"
    exit 0
fi

log "USB backup started → $USB"

# 1. AIGEN_SYS (the big one — repos, KG, dashboards)
if [ -d "$SRC_HOME/AIGEN_SYS" ]; then
    mkdir -p "$USB/C/AIGEN_SYS"
    cp -ru "$SRC_HOME/AIGEN_SYS/." "$USB/C/AIGEN_SYS/" 2>/dev/null
    log "AIGEN_SYS synced"
fi

# 2. Hermes config + scripts + skills (the agent's own brain)
if [ -d "$SRC_HOME/AppData/Local/hermes" ]; then
    mkdir -p "$USB/C/AppData/Local/hermes"
    cp -ru "$SRC_HOME/AppData/Local/hermes/." "$USB/C/AppData/Local/hermes/" 2>/dev/null
    log "hermes config synced"
fi

# 3. Databases (keyword/kg/code)
if [ -d "$SRC_HOME/databases" ]; then
    mkdir -p "$USB/C/databases"
    cp -ru "$SRC_HOME/databases/." "$USB/C/databases/" 2>/dev/null
    log "databases synced"
fi

# 4. cron_logs
if [ -d "$SRC_HOME/cron_logs" ]; then
    mkdir -p "$USB/C/cron_logs"
    cp -ru "$SRC_HOME/cron_logs/." "$USB/C/cron_logs/" 2>/dev/null
    log "cron_logs synced"
fi

# 5. Desktop task lists (CHRISSTEPS)
if [ -d "$SRC_HOME/Desktop" ]; then
    mkdir -p "$USB/C/Desktop"
    cp -ru "$SRC_HOME/Desktop/." "$USB/C/Desktop/" 2>/dev/null
    log "Desktop synced"
fi

# 6. Write a manifest stamp
echo "backup completed $STAMP" > "$USB/LAST_BACKUP.txt"

log "USB backup complete"
