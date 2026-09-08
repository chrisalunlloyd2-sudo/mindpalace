# CONTRIBUTING.md — MindPalace

Welcome to the palace. Every room is a GitHub repo, every book is a file,
and the walls are solid now. Here's how to work on it without stepping on
landmines.

> **First contribution ever?** Skip this file for now — [FIRST_PR.md](FIRST_PR.md)
> walks one real PR end to end (no Java needed). Come back here when you
> want the full rules.

## Quick start (5 minutes)

1. **Prereqs**: Java 17 (JDK), Maven 3.9, Windows 10/11, OpenGL 3.3 GPU.
   Optional: [Ollama](https://ollama.com) for the SLM agents.
2. **Build**: `mvn -DskipTests package` → `BUILD SUCCESS` gate.
3. **Verify before you ship**: `java -jar mindpalace-live.jar --selftest`
   → must print `40 passed, 0 failed`.
4. **Run**: `java -jar target/mindpalace-1.0.0.jar` (see DEV_SETUP.md for
   the frozen-jar rule if you also run the live game).
5. **See the world prove itself**: `--e2e <dir>` writes 13 labeled
   waypoints; `python scripts/test_bot.py --verify-shots <dir>` decodes
   them and asserts non-black frames.

## The rules (enforced by review + cascade)

1. **Every change ships through the cascade**: build → selftest → E2E →
   commit → push → step-log comment (issue #9). `scripts/cascade_dev.sh`
   does all of it for one hypothesis step.
2. **Machine-verified evidence, not claims** — if selftest or shot
   verification fails, nothing ships. No "should work".
3. **Never twice** — identical code or mistakes are refused by the memory
   manager; check `git log` before reinventing.
4. **One game instance** — sweep orphan java processes before launching
   (`scripts/bot_swarm.sh launch` enforces it).
5. **New outdoor geometry = new collider** (`addBox`) or players walk
   through it (pre-H13b bug).
6. **Agent-thread vs render-thread collections** — synchronizedList +
   snapshot at every iteration site (the CME rule). One CME froze the
   game for hours; don't be the second.

## Code style

- Java 17, LWJGL 3 + joml; no new dependencies without discussion.
- One class = one concern; world geometry in `world/`, pixels in
  `render/`, minds in `agent/` (see ARCHITECTURE.md's table).
- Determinism for anything procedural: use `DeterministicSeed` with a
  fixed recipe string — E2E waypoints must be frame-stable.
- Comments explain *why*, not *what*. The code already says what.

## Testing

- `--selftest` (40 checks) is the gate; add a check for any new system
  (see `GameEngine.runSelfTest` — each check is one boolean).
- `--e2e` waypoint tour is the visual gate; add a waypoint when you add a
  feature that must be visible (label it `14_your_feature`).
- `scripts/test_bot.py --stress 3` hunts CME-class regressions.
- BDI_FSM_AGENT sibling repo: keep its suite green (`uv run pytest tests -q`)
  if you touch the bridge contract.

## PRs

- One hypothesis/step per PR, title `Hxx: <what>` (matches NEXT_100_STEPS.md).
- The step-log comment (evidence) is part of the PR, not an afterthought.
- Agent-authored changes land on `agent/<step>` branches; a human merges
  (push-gate step 96 will automate with quorum approval).

## Where things live

- Roadmap: `NEXT_100_STEPS.md` (steps 51–150) + `HYPOTHESES_50.md` (1–50)
- Evidence log: GitHub issue #9 (append-only)
- Data: `todo_management/todo_files/mindpalace/` (TASK files, one at a time)
- Docs: ARCHITECTURE.md (module map), DEV_SETUP.md (environment)