# Model pins (MP-042)

`config/model_pins.json` is the declarative, versioned answer to "which
Ollama model should this job be using" — written so a cron job can fail
loud when the model it expects isn't there, instead of silently skipping
(the MP-042/#187 symptom: "several Hermes cron jobs skip because the
global model drifted").

`scripts/check_model_pins.py` validates it two ways:
1. The `in_game` block still matches `ModelConfig.java` (catches the
   manifest and the real Java source drifting apart).
2. Every pinned model (`in_game` + `cron_jobs`) is actually present in
   `ollama list` on the machine running the check.

## Wiring this into a cron job (outside this repo)

This repo can't reach the Windows Task Scheduler entries or Hermes'
own internal scheduler that actually run the cron jobs — those live on
the local machine, not in git. To make the pin real, point whatever
wrapper launches a cron job at:

```
python scripts/check_model_pins.py || exit 1   # before the job runs its Ollama call
```

so a drifted/missing model stops the job with a clear message instead of
it quietly doing nothing. `config/model_pins.json`'s `cron_jobs` block
currently has one proposed entry (`hermes_task_executor`) — it's a
default guess at what Hermes' own code-writing loop is invoking, not a
confirmed fact. If Hermes already pins itself to a specific model
somewhere, update this file to match that reality rather than the other
way around; if it doesn't, this is the place to start pinning it.

## Extending it

Add a new job under `cron_jobs` as `{"model": "...", "role": "...",
"status": "..."}`. Keep `status` honest — `"proposed default"` until a
human (or Hermes itself) confirms it's what's actually running.
