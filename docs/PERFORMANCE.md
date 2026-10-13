# Performance Tuning — flags and knobs

Target machine for all numbers: Intel HD 510 (integrated), OpenGL 3.3,
1080p. The palace holds 30+ fps at every E2E waypoint with the defaults.

## The launch line (and why every flag is there)

    java -Dprism.order=sw \
         -Dprism.vsync=false \
         -XX:+UseG1GC -XX:MaxGCPauseMillis=200 \
         -Xms256m -Xmx768m \
         -jar mindpalace-live.jar

| Flag | Why |
|------|-----|
| `-Dprism.order=sw` | Intel HD 510 + Prism's GL pipeline = intermittent stalls; software rendering is frame-stable here and the 3D work is our own OpenGL anyway |
| `-Dprism.vsync=false` | double vsync (JavaFX + GLFW) adds latency; the game paces itself |
| `-XX:+UseG1GC` | low-pause collector; the world allocates small short-lived objects per frame |
| `-XX:MaxGCPauseMillis=200` | pauses stay under a frame-ish budget; spikes are rare |
| `-Xms256m -Xmx768m` | the memory contract — leaves RAM for Ollama's one-resident model |

## In-game knobs (Options panel, saved live)

| Knob | Range | Effect |
|------|-------|--------|
| Bloom intensity | 0.0–2.0 | post-process brightness bleed; 0 disables the bloom pass entirely |
| Bloom threshold | 0.0–1.0 | what counts as "bright"; higher = fewer pixels blurred |
| Render distance | menu | culls rooms beyond N meters (world uses 30m room cull + 55m chunk cull) |
| Sound volume | 0–100% | audio engine mix |

## Built-in performance systems (already on)

- **Frustum + facing culling** — rooms behind you and beyond 30m skip
  their whole render call.
- **Chunk streaming** — outside-world chunks culled beyond 55m.
- **Tree LOD** — near trees are ~46 cubes, far trees ~5; the single
  biggest draw-call win on this GPU.
- **Cold-shot models** — one SLM resident in RAM (keep_alive=0); frames
  never compete with an idle model's memory.
- **Instanced-ish batching** — shared cube/quad/sphere meshes; per-frame
  allocation limited to a handful of joml vectors.

## Measuring, not guessing

- Frame proof: `--e2e <dir>` writes 13 waypoints; decode them with
  `python scripts/test_bot.py --verify-shots <dir>` (mean luminance +
  unique colors per frame).
- The two known dark waypoints (07 crystal timing, 12 door prompt) are
  baseline artifacts — A/B proofs live in the step-log issue #9.
- Telemetry panel (F1 help overlay) shows KG stats + last model call.

## Budget rules (from WORLD_FINISHER_PLAN.md)

- New instanced geometry: ≤ 40 total draw calls for trees/floor/paths.
- Bloom stays 2-pass; presets only lerp uniforms, never add passes.
- No new textures — vertex-color + palette keeps uploads cheap on HD 510.

## E2E frame-time gate (MP-004)

`java -Dmindpalace.e2e.maxFrameMs=120 -jar mindpalace.jar --demo --e2e <dir>` ends the tour with `[PERF_OK]`, `[PERF_WARN]` or
`[PERF_FAIL]` (exit 2). It fails only when at least `-Dmindpalace.e2e.maxOverWaypoints` (default 3) waypoints have a worst
steady-state frame over the limit: one slow waypoint is normally the machine, a regression shows at several. Waypoint 1 is
excluded (startup). On a software rasteriser (llvmpipe, swrast) it only warns. Quiet-machine baseline (Intel HD 510, 2026-10-07):
39-59 FPS, worst frame 23-54 ms per waypoint, saved under `AIGEN_SYS/baselines/2026-10-07_final_quiet`. Run it with the live
game and Ollama stopped; a busy machine can show 0.5-1.7 s spikes that are not the game. `-Dmindpalace.frameProf=true` prints a
stage breakdown and stack histogram for any stall over 100 ms.
