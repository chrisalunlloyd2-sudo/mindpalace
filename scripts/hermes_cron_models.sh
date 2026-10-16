#!/usr/bin/env bash
# hermes_cron_models.sh — canonical model pins for MindPalace cron jobs.
#
# Purpose:
# - Prevent cron behavior from drifting when a global/default model changes.
# - Keep cron local-first by default; cloud spend must be explicitly approved.
#
# Usage:
#   bash scripts/hermes_cron_models.sh list
#   bash scripts/hermes_cron_models.sh model <job>
#   HERMES_CRON_ALLOW_CLOUD=1 HERMES_CRON_CLOUD_APPROVAL=MP-042 \
#     bash scripts/hermes_cron_models.sh model task-watch
set -euo pipefail

# Local-first defaults (override via env only when intentionally changing policy).
HERMES_CRON_TASK_WATCH_MODEL_LOCAL="${HERMES_CRON_TASK_WATCH_MODEL_LOCAL:-llama3.2:3b}"
HERMES_CRON_TASK_WATCH_MODEL_CLOUD="${HERMES_CRON_TASK_WATCH_MODEL_CLOUD:-gpt-5.4}"
HERMES_CRON_ALLOW_CLOUD="${HERMES_CRON_ALLOW_CLOUD:-0}"
HERMES_CRON_CLOUD_APPROVAL="${HERMES_CRON_CLOUD_APPROVAL:-}"

usage() {
    cat <<'EOF'
Usage:
  hermes_cron_models.sh list
  hermes_cron_models.sh model <job>

Jobs:
  auto-sync         -> none
  health-monitor    -> none
  scout-bot         -> none
  feedback-digest   -> none
  task-watch        -> pinned local model by default; cloud only with approval
EOF
}

task_watch_model() {
    if [ "$HERMES_CRON_ALLOW_CLOUD" = "1" ]; then
        if [ -z "$HERMES_CRON_CLOUD_APPROVAL" ]; then
            echo "error: HERMES_CRON_ALLOW_CLOUD=1 requires HERMES_CRON_CLOUD_APPROVAL=<ticket-or-decision-id>" >&2
            exit 2
        fi
        printf '%s\n' "$HERMES_CRON_TASK_WATCH_MODEL_CLOUD"
        return
    fi
    printf '%s\n' "$HERMES_CRON_TASK_WATCH_MODEL_LOCAL"
}

model_for_job() {
    case "${1:-}" in
        auto-sync|health-monitor|scout-bot|feedback-digest) printf 'none\n' ;;
        task-watch) task_watch_model ;;
        *) echo "unknown job: ${1:-}" >&2; usage; exit 1 ;;
    esac
}

list_jobs() {
    printf 'auto-sync %s\n' "none"
    printf 'health-monitor %s\n' "none"
    printf 'scout-bot %s\n' "none"
    printf 'feedback-digest %s\n' "none"
    printf 'task-watch %s\n' "$(task_watch_model)"
}

case "${1:-}" in
    list) list_jobs ;;
    model) model_for_job "${2:-}" ;;
    *) usage; exit 1 ;;
esac
