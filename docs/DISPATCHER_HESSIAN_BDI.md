# Dispatcher Architecture: Hessian-Weighted BDI-FSM Ensemble

Status: design (Architect, 2026-10-17). Build is tracked on the Hermes `mindpalace` board as cards DISP-0 .. DISP-5.

## Why

On 2026-10-13 a failed Ollama Cloud payment (HTTP 402) made every Hermes worker die within seconds, and 46 cards
auto-blocked. Routing and work selection must not depend on a paid cloud endpoint. This design moves the *choice*
of where a task runs onto local nodes (this PC, Alice, the tailnet mesh) using small, deterministic, trainable parts.

Scope note: Hermes' own kanban dispatcher (claim / spawn / reclaim) is deterministic and never called a model. What
failed was the *model behind each spawned worker*. So this design is the routing layer in front of workers: given a
task, choose which local model / node runs it, and degrade safely when a node is down.

## Architecture

```
Incoming request / task
   |
   +--> Feature extraction (token count, context depth, AST/regex indicators, intent label, caller, latency budget)
   |
   +--> BDI-FSM agent pool
   |      Agent 1  Code / syntax specialist
   |      Agent 2  Fast heuristics / reflex agent
   |      Agent 3  Architecture / context synthesis
   |      Agent k  Cost / latency gatekeeper
   |          -> each emits a vote vector v_k in R^M over M routing targets
   |
   v
Hessian-trained voting gate:  s = softmax(W V),  W* = argmin L(W)  via Newton steps (H^-1 g)
   |
   v
argmax routing decision --> [local SLM / target node]   (static fallback table if confidence is low)
```

## Choice dataset (JSONL)

One line per dispatch event:

```json
{"x_features": [..], "target_node": "alice_qwen_coder", "loss_penalty": 0.02,
 "latency_ms": 840, "vram_mb": 4100, "success": true}
```

- `x`: token count, estimated context depth, code/AST indicators, intent label, caller metadata.
- `y`: the node/model that actually resolved the task.
- `c`: latency, VRAM footprint, success/failure -> folded into `loss_penalty`.

Real sources that already exist: `kanban.db` (`task_runs`: outcome, duration, assignee; `task_events`), the game's
quorum ledger, and `AIGEN_SYS/mindpalace_memory/godhand/hexflow_ledger.jsonl`. Extract from these first; no new
logging is needed to start.

## BDI-FSM agent pool

Each agent is a strict finite-state machine so its behavior is explainable and testable:

- Beliefs B: cluster state (node up/down, VRAM free, latency, context limits).
- Desires D: per-agent priorities (code precision; time-to-first-token < 150 ms; low cost).
- Intentions I: emit a preference vector v_k over the M targets.
- Transitions are rules, e.g. context > 8k tokens -> state DEEP_CONTEXT -> downvote small-context models.

## Voting gate and the Hessian step

Votes V = [v_1 .. v_K] (K agents x M targets). Scores s = W V with gating weights W; routing probabilities
p = softmax(s). Loss over a batch of N events:

```
L(W) = -(1/N) sum_n log p_n[y_n]  +  lambda * sum_n c_n * p_n[t]  +  (mu/2) ||W||^2
```

(cross-entropy + operational-cost penalty + L2 so the Hessian is always positive definite).

Newton-Raphson update with damping:

```
g = grad_W L          H = hessian_W L  (exact, or Gauss-Newton / Fisher)
W <- W - eta * (H + delta*I)^-1 g       (eta from backtracking line search)
```

With 4-10 agents voting on 3-5 routes the parameter count N is at most about 50, so H is N x N and a Cholesky solve is
instant on CPU. No GPU, no network.

## Runtime (fast path)

1. Features -> FSM pool -> votes V.
2. s = W* V, route = argmax.
3. Dispatch to the chosen local node over the tailnet.
4. If top-1 probability is below a threshold, or the node is unhealthy, use a static fallback table (never "ask the cloud").

W* is exported as a static matrix; the runtime is pure Python/Java with no remote API.

## Online tuning

Log every outcome (success / timeout / fallback). Update g and H incrementally per batch (online Newton with a decayed
Fisher estimate). Recursive least squares / Woodbury updates apply exactly to a squared-loss gate; for the softmax
gate use the incremental-Newton form above, or periodically re-fit on a sliding window. Keep the previous W* as a
rollback and only promote a new one if it does not regress on a held-out slice.

## Guardrails

- Local models only at runtime (primary directive).
- Weights are data, not code: versioned file, reviewed diff, rollback.
- A new W* is promoted only with evidence (held-out accuracy, no regression on the fallback cases).
- Public-sourced training signals stay in triage per the feedback gate.

## Build plan (Hermes cards)

| Card | Deliverable |
|---|---|
| DISP-0 | Immediate stopgap: Hermes worker profile falls back to a local Ollama model when the cloud returns 402; dispatcher pauses instead of burning retries |
| DISP-1 | Choice dataset extractor from kanban.db + ledgers -> JSONL, schema doc, sample stats |
| DISP-2 | BDI-FSM agent pool (4 agents), rule tests per state transition |
| DISP-3 | Newton / Gauss-Newton trainer (stdlib or numpy), loss with cost penalty, line search, unit tests on synthetic data |
| DISP-4 | Runtime router + static fallback table + health beliefs, replay test against the dataset |
| DISP-5 | Online tuning with rollback and held-out promotion gate |
