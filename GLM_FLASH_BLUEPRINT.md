# GLM_FLASH_BLUEPRINT — Fast-tier model routing to save quota

## Context (verified 2026-09-06)
- Local Ollama (localhost:11434) has 11 models; smallest are
  qwen2.5:0.5b (379MB), tinyllama:1.1b, llama3.2:1b (1.3GB).
- MindPalace ModelConfig: TOOL=llama3.2:1b, CRITIC=qwen2.5:0.5b,
  TIE=deepseek-r1:1.5b, CHAT=llama3.2:3b, EMBED=nomic-embed-text.
- The Hermes agent (this session) runs on ollama-cloud glm-5.3 with a
  weekly cap — the 2026-09-01 quota wall proved LLM cron jobs die when
  it hits. Every quota-free alternative must be default-on.

## Principle
"GLM flash" = the tier concept, not one model: the cheapest competent
model for the job, run LOCALLY, so the cloud quota is spent only where
local models provably can't cope. The game already proves 0.5–1b models
loop without proper prompting — so flash-tier calls get engineered
prompts (Phase A fixes) and tiny token budgets.

## The tiers (H43–H46 in HYPOTHESES_50.md)
| Role | Model | Where | Budget | Used for |
|------|-------|-------|--------|----------|
| flash | qwen2.5:0.5b | local | ≤200 tok out | quorum topic titles, scout report formatting, chat echo, meta-labels |
| tool | llama3.2:1b | local | ≤600 tok out | read/patch file loop (post-H05 patch format) |
| critic | qwen2.5:0.5b | local | ≤300 tok out | diff review (post-H01 sees real work) |
| deep | deepseek-r1:1.5b | local | ≤500 tok out | tie-break + risky-diff review (H11) |
| chat | llama3.2:3b | local | ≤500 tok out | player conversation only |
| cloud-burst | glm flash (ollama-cloud) | cloud | ≤800 tok out, OFF by default | only when a task fails N local attempts (H45) |

## Why this saves quota
1. The 5-minute autonomous cycle's chatter (today: 82% meta-filler)
   burns local tokens only — it was never the cloud spend, but H08's
   meta-gate + H39's compute tax make even local loops self-limiting.
2. ollama-cloud is touched ONLY by explicit burst escalation, capped at
   one call per failed local attempt chain, logged per-call (H46), and
   reported in the morning digest. A quota wall can never be hit by
   accident again.
3. The task-watch cron (hourly, on this repo) is the ONLY sanctioned
   cloud consumer: it executes one H# task file per run with bounded
   context (AGENTS.md injected, one-task rule from BACKLOG's 45-min
   lesson). 50 hypotheses ÷ ~1/hour with verification = the program
   completes in days, not weeks, at minimal quota.

## Wiring plan (code) — status synced 2026-11-18 (issue #407)

Shipped:
- `ModelConfig.FLASH_MODEL = "qwen2.5:0.5b"` + `FLASH_BUDGET = 500`
  (0f6545f, #134 — budget landed at 500, not the 200 first sketched).
- `ModelRouter.selectFlash`: FLASH complexity lane, one-way clamp
  (VERY_LOW/LOW route to flash, never escalate above it) — same commit.
- Flash cap reaches the wire as a **lane flag**, not a model-tag test:
  `OllamaClient.optionsFor(tag, flashLane)` sends
  `num_predict = FLASH_BUDGET` only when the routed call is flash
  (4bc471a #384 after the tag-test false positive, then 7fc0b2f
  #400/#401 rides the lane cap through the routed tool call).
- Temperature: global tuning via `-Dmindpalace.ollama.temperature` /
  `MP_OLLAMA_TEMPERATURE` (no request path passes raw per-call
  temperature — see OllamaClient.java optionsFor).

Still open (intentionally unbuilt):
- `CloudBurstClient` — remains unshipped by design (OFF by default,
  the only quota-spending class, single auditable file). No cloud keys
  in the repo; read from env (`MIND_PALACE_AGENT_TOKEN` env var, the
  way GitHubClient does — Credential-Manager path removed, #333).
- Per-call (max_tokens, temperature) tuples on `chat()` — superseded
  by the lane-cap + global-property design above.

## Success metrics (scout_bot tracks, H12)
- flash calls/min: expect 10–20 during active cycles.
- cloud calls/day: target 0 unless burst enabled; hard alert at >20.
- meta-chatter %: target <30% by H08, <15% by H12.
- code-bearing messages: target ≥25% of agent output by end of Phase A.