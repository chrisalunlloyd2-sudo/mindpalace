# LOCAL_GATEWAY.md — the one path to Ollama (UNIFY-1 / UNIFY-2)

**Status: live proof 2026-10-23.** Every local-model caller in the estate now
talks to `127.0.0.1:11435` (the orchestrator gateway). Nothing calls Ollama's
`:11434` directly except the gateway itself.

## The gateway

`SIMS1337/scripts/ollama_orchestrator.py` — stdlib HTTP server, **loopback-only
bind** (`127.0.0.1:11435`), in front of Ollama `127.0.0.1:11434`.

- Priority queues: **game > crew > batch** (hard ordering; worker drains in
  that order). Queue caps: game 8, crew 8, batch 16; overflow -> 429
  `queue_full`.
- **One model resident at a time**; gateway stamps `keep_alive: 5m` on every
  job (1m on the embed lane). Client keep_alive values are ignored on the
  generate/chat lanes.
- Hard caps: `num_ctx` 8192, `num_thread` clamped 1..16, per-model
  `max_tokens`/`timeout_s` from `C:\Users\viper\AIGEN_SYS\config\model_policy.json`
  (job timeout upper bound 300 s).
- Admission policy (`model_decision`): cloud-name patterns ALWAYS denied
  (PRIMARY DIRECTIVE); unknown model names denied (default deny); tiers
  S (any time) / M (needs headroom, +1000 MB when the game runs) /
  L (exclusive: sole resident + game idle) / embed / restricted / banned.
- Swap pacing 1.5 s between model swaps.

### Endpoints

| Endpoint | Shape | Notes |
|---|---|---|
| `GET /health` | native | ollama_up, queue depths, game_running, free RAM, loaded models |
| `GET /stats` | native | per-caller counters + 20-event ring |
| `POST /v1/run` | native single-flight | `wait:false` -> 202 + `/v1/status` polling |
| `POST /v1/embed` | native embed lane | separate single-flight |
| `POST /api/generate` | ollama compat | maps onto /v1/run |
| `POST /api/embeddings` | ollama compat | maps onto /v1/embed |
| `POST /api/chat` | ollama NATIVE passthrough | full body (tools, format) rides verbatim; tool_calls preserved; **stream=true -> 400** |
| `POST /v1/chat/completions` | OpenAI compat | messages folded into prompt lane; stream -> one-shot SSE |
| `POST /api/show` | ollama passthrough | model metadata |
| `GET /api/tags`, `/v1/models`, `/api/version` | passthrough/compat | |

### Caller identity

`X-MP-Caller: game|crew|batch` header wins; else body `"caller"`; else
`batch`. Unknown values drop to batch.

## Who calls the gateway (migrated 2026-10-23)

| Estate | Files | Anchor commits |
|---|---|---|
| SIMS1337 ModelBus (game/agents/crew) | `ModelBus.java` -> only path | 002fb40 |
| mindpalace game client | `agent/OllamaClient.java` (BASE :11435, X-MP-Caller: game, eviction removed — residency is gateway-owned) | 5c72b37 |
| mindpalace tooling | `tools/slm_api_gate.py` | 5c72b37 |
| viper_scripts (12+2 callers) | chat_test, code_assist, factory_stations, fix_block, git_diff_ai, local_llm(fallback), moe_images, qwen_chat, gateway.py backend(VIPER_OLLAMA), dashboard probe, verify_orchestrator, gan_integration probe | 1bfa102 + 2d725d8 |
| AIGEN_SYS/scripts (12, untracked dir) | ai_prioritize_todos, generate_code_from_todo, google_bot_skill, heartbeat_deep_check, monitor_mindpalace, ollama_client, orthogonal_run_cron, orthogonal_thinking ×3, review_system, system_maintenance | on-disk edits (no git) |
| SIMS1337 tooling | sims.ps1, web/server.js, chat_server.py, download_gguf.py, apply_ollama_env_restart.ps1 | 7289999 |

Out of scope by design (Architect, 2026-10-11): Hermes' own reasoning +
Hermes cron jobs (they may use the Hermes-side cloud route); Sophia is clean.
Everything that RUNS INSIDE THE GAME is local-only via this gateway.

## Proof (2026-10-23)

1. **Mixed load** (`/v1/run`, all `llama3.2:1b`): wave1 game+batch+batch ->
   completion order game(3.8s) > batch(8.3s) > batch(9.5s); wave2 crew+game+batch
   -> game(2.6s) > crew(5.3s) > batch(7.9s). **Game finished first in both
   waves; zero starvation.** Completions strictly serialized (one resident
   model). Gateway counters: done=8 (game 4 / crew 1 / batch 3), reject_queue=0,
   errors=0.
2. **Loopback bind**: `netstat -ano | grep 11435` -> `127.0.0.1:11435 LISTENING`
   only (see netstat step below for the full capture).

## Operations

```
python scripts/ollama_orchestrator.py           # in SIMS1337; writes stdout log
curl http://127.0.0.1:11435/health              # ollama_up + queue depths
curl http://127.0.0.1:11435/stats               # counters + 20-event ring
```

Never point anything new at `:11434`. If you add a caller, give it an
X-MP-Caller class and route it through :11435.
