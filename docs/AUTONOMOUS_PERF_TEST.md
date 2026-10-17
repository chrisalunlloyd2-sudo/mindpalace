# AUTONOMOUS_PERF_TEST — frame-locked, zero-human perf measurement design

PERF-A1 (t_61608b2f) deliverable. Goal (Architect, 2026-10-08): perf testing
with ZERO human involvement — nobody holds a mouse, presses a key, watches a
title bar or reads a log. The test launches, runs, collects, and exits by
itself, and produces comparable numbers between runs and machines.

This doc SETTLES the six design questions with evidence from the current code.
It does NOT change any default behaviour; every mechanism below is opt-in via
JVM properties / CLI flags, following the established pattern
(`-Dmindpalace.e2e.maxFrameMs`, `-Dmindpalace.glTrace`, `-Dmindpalace.frameProf`).

Implementation is split across the follow-up cards:
- PERF-A2 (t_5155f104): frame-lock + input track inside the game (D1, D2, D3, D4).
- PERF-A3 (t_993a201f): unattended harness script (launch, load fire, collect, exit; D5, D6, safety).
- PERF-A4 (t_24d503fb): the A/B matrix run on top of A2+A3.

## Why the old way was noisy (context)

C1 profiling needed a human to move the camera, FPS was read from the window
title every ~300 ms (`GameEngine.run()` fpsTimer block, lines ~1062-1073), and
profiler lines carried no timestamps. Under Ollama load (llama3.2:1b on CPU,
4 logical cores) generation drops 47→30 FPS with ~20 hitches >80 ms per run,
worst 843-1255 ms, stalls concentrated in frame-start (bloom.end + swap +
update + loop), not world.render. The existing fixed-step loop already clamps
wall time (`PHYSICS_DT = 1/120`, `MAX_FRAME_TIME = 0.25`, lines ~169-170,
1049-1057) but there is no mode that decouples sim time from wall time, and
screenshot PNG encoding runs inside the timed frame.

## D1 — Frame lock (deterministic sim time, wall time stays the metric)

The loop today accumulates REAL wall time (`accumulator += frameTime`, line
1050) and consumes it in fixed `PHYSICS_DT` steps. Identical inputs do NOT
produce identical camera paths because a hitched frame contributes up to 0.25 s
of extra sim time.

ADD `-Dmindpalace.frameLock=<fixedDtMs>` (A2): when set, per frame the loop
does `accumulator += fixedDt` (converted to seconds) INSTEAD of adding
frameTime, and skips the MAX_FRAME_TIME clamp entirely. Consequences:

- Camera position becomes a pure function of frame number → two runs follow
  the identical path; the only thing that varies is how long each frame took
  in wall time, which is exactly the metric.
- The fixed lockstep mode already exists in spirit in autodrive; frameLock is
  a ~5-line change in the loop head (lines 1029-1057) gated on the property.
- Optional companion `-Dmindpalace.perf.frames=N`: total frames to simulate
  then clean exit (same shape as the e2e tour's bounded run + System.exit).
- vsync note: with the existing `-Dprism.vsync=false` JVM flag (used by e2e.sh)
  frames are uncapped, so wall-frame ms measures real GPU+CPU cost. Frame-lock
  itself does not touch glfwSwapInterval.

What frame-lock does NOT fix: content of NPC behaviour that reads wall clocks.
For A/B comparability the perf mode should keep the world deterministic
enough: Scout patrol is game-thread tick driven (deterministic under frame
lock); SLM-brain background HTTP traffic is the wild card — see D5.

## D2 — Synthetic controller (input TRACK, injected at the input layer)

`Input` (engine/Input.java) already owns all key/mouse state the player reads
(`keys[]`, `accumDX/DY`, consumed via `isKeyDown`/`getMouseDX` in
Player.update, lines 154-174) and already has a synthetic hook
(`injectKeyPress` → `synthPress[]` for one-frame just-presses, line 98).

ADD (A2), all additive:

- `Input.injectKeyHold(int key, boolean down)` — sets/clears `keys[key]` for
  held-movement modelling (W/A/S/D/Shift/Space), consumed like real keyboard.
- `Input.injectMouseDelta(double dx, double dy)` — adds into `accumDX/DY`
  before Player.update drains them (turn/look input).
- `engine/InputTrack` — parses a JSON track file given by
  `-Dmindpalace.inputTrack=<file>`: a list of segments
  `{t0, t1, keys:[...], mouseDx, mouseDy}` (seconds; digital WASD is the
  subset; mouse rates are deg/s). Driven from the engine loop each frame
  BEFORE player.update: seek by SIM time (which is frame-locked under D1) →
  identical camera path every run.
- Default track resource `tracks/perf_track_09_agents.json`: walks the main
  hall centerline (x=0, the 09_agents framing holds yaw -45 pitch -5 facing
  the agents' room; tour sets that pose at GameEngine 6554-6557) forward at
  `MOVE_SPEED = 6.0 m/s` (Player.java line 24) for ~10 s, a 2 s two-key turn
  segment, then idles 3 s, then loops. Total ~15 s of motion per loop, at
  60 fps lock ≈ 900 frames/loop.

Injection happens in Java (the game reads its own Input object), NOT at the OS
level — no SendInput, no window focus requirements, works headless on CI.

## D3 — Snapshots without polluting timing

Today `Screenshot.capture` (render/Screenshot.java) does glReadPixels + a
row-flip + `stbi_write_png` synchronously inside the frame — PNG encode is the
expensive part and it HAS been counted as a hitch before. Split it (A2):

- GL thread (must stay on-context): `Screenshot.captureRaw` — glReadPixels +
  flip into a reusable ByteBuffer (1280x720 RGBA ≈ 3.7 MB, ~1-2 ms worst).
  Called only at configured snapshot frames.
- Worker thread (`PerfSnapshotWriter`, single daemon): takes the buffer,
  `stbi_write_png`, frees. Encode ms never lands inside the measured frame.
- The CSV row for a snapshot frame carries `snap=1`; the analyzer may exclude
  snapshot frames from percentile stats or report them separately (they only
  pay the 1-2 ms raw read).
- Snapshot frames come from the input track file (`snapshots:[frame...]`) so
  visual-regression captures are also frame-locked and comparable.

## D4 — Output: CSV + verdict + exit codes

ADD (A2) `render/PerfCsvLogger` — buffered writer on a worker thread (never
blocks the render thread), one row per frame:

```
frame,wall_ns,frame_ms,update_ms,stage_slowest_ms,stage_name,snap,hitch,note
2649,1728700000000,12.4,3.1,7.9,render,0,0,
2650,1728800000000,831.0,822.0,20.1,swap,0,1,ollama_window
```

- `frame_ms` = wall time around frame; `update_ms` from GlTrace.PROF's
  noteUpdate (already measured, GameEngine 1052-1058); stage breakdown from
  GlTrace stage names; `hitch` = frame_ms > HITCH_NS (80 ms, same threshold as
  HitchSampler); `note` marks known events (ollama fire window, snapshot).
- Verdict reuses the e2e perf-gate SHAPE (GameEngine tour exit block,
  ~6855-6870): `mindpalace.perf.maxFrameMs` (default 80), fail only when ≥
  `mindpalace.perf.maxOverFrames` (default 3) frames exceed it within the run;
  software rasterizer → `PERF_WARN` instead of fail (same llvmpipe/softpipe/
  swrast/microsoft basic render GL_RENDERER regex). Machine-readable final
  line: `PERF_RESULT: OK|WARN|FAIL hitches=N worstMs=X p50/p95/p99=...`
  Exit codes mirroring e2e: 0 pass, 1 setup/HUD-style failure, 2 perf FAIL.
- Clean exit: after `frames=N` (or track end) → `cleanup(); System.exit(code)`
  — window destroyed, no processes left owning the console.

## D5 — Ollama load injection at a fixed frame (harness-owned, not game-owned)

The game must NOT fire the load: the harness owns knobs (model, num_predict,
prompt text, request priority experiments) and must stay a pure client of a
127.0.0.1:11434 Ollama it does not configure.

- The game EXPOSES its frame counter: in perf mode print
  `[PERF-FRAME] <n>` to stdout every 16 frames (plus one at frame 0). The
  harness already tails the game's stdout for the readiness marker, so no new
  IPC channel is needed. Jitter of ±1 frame (stdout poll latency) is
  acceptable: the decode window spans ~20+ frames.
- The harness (A3) waits for the readiness marker, then for target frame F
  (default: 2 s of baseline walking after ready, i.e. frame ≈ 120-180),
  fires ONE `POST /api/generate` on `http://127.0.0.1:11434` — body:
  `{"model":"llama3.2:1b","prompt":"<run-varied text>","stream":false,
  "options":{"num_ctx":8192,"num_predict":64}}` — reads
  `load_duration`, `prompt_eval_duration`, `eval_duration`, `eval_count`
  from the JSON reply and records tok/s = eval_count / (eval_duration/1e9).
  Prompt text varies per run ordinal to defeat prefill caching (A4 found
  same-prompt runs were confounded).
- HERMETICITY: the game instance must contribute ZERO Ollama traffic of its
  own during the run — otherwise "generation vs baseline" is confounded by the
  agent cycle and NPC brains. Perf mode (A2) therefore runs NPCs on the
  KV/KG fallback the code already has (`"[NPC] Ollama unavailable — agents
  run on KV/KG fallback"` path, GameEngine ~868-870) and suspends the
  AgentManager autonomous cycle (`-Dmindpalace.perf.suspendAgent=true`).
  The game's own client stays bound to localhost (OllamaClient BASE,
  OllamaClient.java line 14) — and the harness is forbidden from any
  non-loopback Ollama binding. LOCAL MODELS ONLY (game-directive): the load
  request model is always a locally installed Ollama model.
- Ollama stays untouched as a service: no server settings are modified; the
  only state A3 may change is the OS process priority of ollama.exe for an
  arm, restored in its finally block.

## D6 — Reuse vs new

REUSE (unchanged):
- autodrive dispatch shell: `--perf <dir>` reuses `setAutodrive`-style wiring
  + `updateAutodrive(dt)` dispatch (GameEngine 1054, 6342) so NPC/world ticks
  run the real path; we add a third branch next to the e2e/orbit branches.
- GlTrace PROF framework (`-Dmindpalace.frameProf`, noteUpdate, stage names),
  HitchSampler 80 ms threshold and stack sampling for the outlier attribution
  (A4 needs "which stage, which thread" — HitchSampler's sampler already
  answers it when PROF is on).
- e2e gate semantics (maxFrameMs/maxOverWaypoints → copy as perf.maxFrameMs/
  maxOverFrames), [E2E-STARTUP] startup accounting pattern → [PERF-STARTUP].
- Screenshot.capture (reused by the new captureRaw split, not replaced).
- Existing JVM flags stay untouched; e2e.sh/selftest paths unchanged.

NEW (all opt-in):
- `-Dmindpalace.frameLock=<ms>`, `-Dmindpalace.perf.frames=N`,
  `-Dmindpalace.inputTrack=<file>`, `-Dmindpalace.perf.suspendAgent=true`,
  `--perf <dir>` CLI (Main parses; GameEngine.setPerf(dir)).
- Input.injectKeyHold/injectMouseDelta (~15 lines), engine/InputTrack,
  render/PerfCsvLogger, PerfSnapshotWriter worker,
  Screenshot.captureRaw split (capture kept calling the same core),
  tracks/perf_track_09_agents.json.

## Safety (binds A3)

1. Ollama is client-only: requests to `127.0.0.1:11434` from the harness; no
   host/port/binding changes, never 0.0.0.0. LOCAL models only.
2. The harness stops nothing it does not own. ONE-GAME rule: it launches its
   own hermetic jar copy with `--perf`, and at shutdown kills ONLY the PID it
   spawned (single-slash `taskkill /F /PID` on Windows — `//F` is a silent
   no-op in this Git Bash); it never touches the live game PID.
3. Any Ollama process-priority change is recorded and restored in `finally`.
4. The run is bounded: `timeout` on the JVM process + `perf.frames` cap, so a
   hang exits with a harness-level FAIL, never an orphan.
5. GPU noise honesty: `[PERF_WARN]` on software rasterizers; the harness
   records GL_RENDERER in the result JSON so arms are comparable.

## Acceptance criteria for follow-up cards

PERF-A2 (t_5155f104) — build frame-lock + input track (opt-in):
- With `-Dmindpalace.frameLock=8.3` + `-Dmindpalace.inputTrack=...`, two runs
  produce IDENTICAL camera positions at the same frame numbers (asserted by a
  new selftest check reading the CSV/player pose log); with the flags absent,
  default behaviour is byte-identical (selftest + e2e still pass).
- CSV + verdict line + exit codes per D4; snapshots per D3 (encode off-timed-
  window); no windows left open.

PERF-A3 (t_993a201f) — unattended harness (scripts/perf_harness.py):
- One command runs everything: build/jar check → hermetic `--perf` launch →
  wait on readiness marker (not a sleep) → fire Ollama request at fixed frame
  via stdout frame markers → collect CSV + snapshots + Ollama timings →
  per-window stats (baseline vs generation: frames >80 ms, worst, p50/p95/p99,
  hitch time sum) → verdict + exit non-zero on FAIL → own-process cleanup +
  priority restore, verified by a post-run process sweep. Knobs on CLI:
  model, num_predict, num_thread, OLLAMA_PRIORITY, TRACK, FRAMES, OUT dir.
- ACCEPTANCE: two back-to-back runs agree (p95 within 15%, hitch count within
  ±2), zero stray processes remain, evidence attached in complete --metadata.

PERF-A4 (t_24d503fb) — matrix run: ≥10 runs/arm interleaved, prompt text
varied per run, arms per its card (num_predict 32/64/128, num_thread 1/2/3,
priority on/off, plus off-main-thread agent-call arm only if the design card's
culprit hypothesis holds). Deliverable: results table + recommendation posted
on that card; remediation code in NEW cards for Architect sign-off.

## Known risks / open items

- ±1 frame jitter firing the Ollama request via stdout tailing — accepted;
  decode spans many frames. If tighter sync is ever needed, add an optional
  ACK file the game writes at exact frame F (`mindpalace.perf.signalFile`).
- GC pauses remain in `frame_ms` (that IS part of the workload's cost); G1
  settings can be an extra matrix arm later, not now.
- World content is repo-derived; if rooms change between runs the PATH is the
  same only if the checkout is the same — the harness records git SHA + jar
  fingerprint in the result JSON so cross-machine comparisons are honest.
## A3 build-out notes (t_993a201f, 2026-10-16) — deltas vs this spec
The A3 harness (scripts/perf_harness.py) implements this doc with these
code-verified deviations, confirmed during the smoke runs:
- The doc's PERF-READY / PERF-EXIT / perf.signalFile / ACK handshake does NOT
  exist in the merged A2 code (grep-verified). The harness treats the game as
  ready at the FIRST `[PERF-FRAME]` stdout marker and complete at the
  `PERF_RESULT:` line + process exit code (0 ok, 1 harness-abort, 2 perf
  FAIL). Update this doc if a real handshake lands later.
- A2 bug found and fixed in GameEngine.java during the first smoke run:
  `perfFrameStartNs` was seeded once at loop entry and never reset, so every
  CSV `frame_ms` row and the game's own PERF_RESULT were CUMULATIVE elapsed
  time since loop start (400 frames marched 1.28 s -> 11.47 s while the run
  really took ~11.5 s wall; verdict FAILed 400/400 at p50 6424 ms). The clock
  is now reset per frame; `frame_ms` is the true per-frame delta.
- Repeatability (D-series acceptance: p95 within 15 %, hitches within ±2):
  harness MECHANISM is fully deterministic — the 3-segment track is applied
  identically every run ([PERF-POSE] stdout lines are byte-identical across
  all runs, diff-verified A/B and C/D), CSV/snapshot/verdict artifacts are
  produced every run, and cleanup is proven each run. Wall-time p95 on THIS
  box is NOT repeatable within 15 % and cannot be while the live agents
  (elevated javaw PID 1740) keep generating on the same Ollama daemon:
  observed same-pair baseline p95 58.8 vs 208.8 ms when run B inherited
  run A's resident model (fixed via `keep_alive: 0` on the fire POST) and
  46.0 vs 108.8 ms across C/D from live-agent daemon churn between runs;
  decode window differs per run too (tok/s 12.26 vs 8.85 for the identical
  prompt). The honest reading: track determinism = game property (proven);
  wall p95 spread = box property (live agent + daemon contention), and the
  result JSON already records model/wall_s/tok/s per run so cross-run
  comparisons stay honest. Verdict gates stay config-tunable
  (mindpalace.perf.maxFrameMs / maxOverFrames) for quiet-box runs.
