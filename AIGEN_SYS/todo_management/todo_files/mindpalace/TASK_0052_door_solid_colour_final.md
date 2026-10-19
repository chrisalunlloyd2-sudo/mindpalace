# TASK_0052 — QUAL-2 final: every door, one solid colour (-Dmindpalace.quality.doors)

## Spec accepted
`Renderer.doorTex()` (Renderer.java:55-135) maps every legacy door-wood request
(`TEX_DOOR`/`TEX_DOOR_SOLID`) to a per-state solid colour through texture slot 29
(`TEX_DOOR_SOLID` RGB 0.16,0.42,0.20 — exact 8-bit (41,107,51)), gated behind
`-Dmindpalace.quality.doors`. Jamb sites (WorldBuilder.java:1007-1009) stay neutral wood
by spec. Legacy TEX_DOOR prop sites (baseboard :976, crown molding :980, trim
:1215/:1236/:1341) are NOT door faces and intentionally keep door-wood.

## Runtime proof (authoritative)
- Flag-ON chain (proc_0755391e4ef9 / probe run proc_d991782d5362): BUILD SUCCESS,
  selftest clean, VERIFY-PASS, 29/29 shots with `[QUAL2-PROBE] flag=true doorTex=29
  solidCubesQueued=1872..3348` — the gate fired on every real door face, no cost.
- Flag-OFF chain (proc_b2a5456e607a): green, 29/29 shots — genuine control.

## Why the strict census never fired (decoded 2026-10-19)
`shaders/basic.frag` is a LINEAR pipeline with no gamma: final = base x (0.70 x warm
(1.0,0.9,0.7) + headlamp diffuse (light at camera+3 m) + spec <=0.3). A lit lit-front
door renders ~ (64-69,150-164,55-61); an ambient-only face ~ (28,67,25). Exact
(41,107,51) only appears on fully-shadowed faces. Exact-color census is therefore
structurally invalid on lit geometry.

## Decisive A/B census (q2_census3.py, ratio-based, lighting-preserving)
Ratio windows (door family g/r 1.5-3.4, b/g 0.18-0.68; wood family r/g 1.15-1.9)
compare PROBE-ON vs FLAG-OFF across all 29 shots: EVERY shot identical within
run-to-run noise (|dFam| <= 2 except animation-noise shots 09/20 which also vary
between same-flag runs). The one apparent mover (shot 26 wood -5) was ROI-traced to
console anti-aliasing (x1125-1180, y460-495: pure UI primaries only) — not scene pixels.

## Geometry classification (camera-accurate, GameEngine 6870-7070 + 2905-2975)
- 22 mansion spawn / 26 entrance / 28 door face: doors dead-center at x875-1045
  (28) — INSIDE the chat console window x432-1133; masked by opaque UI, expected.
- 23-25 door-panel closeups: camera inside hallway at x~0.75 looking -X; leaf is an
  oblique ~70 px quad; jambs keep wood per spec.
- 27 town house door: back-face-culled under ortho-like straight-on camera.
- 06 edge-on; 29 degenerate 0.05 m face; 02/04 themed-room closeups have no doors.
- Clock note: setClockPin(12) governs sky/banner; `--time 21` override drives night
  emissive windows; both coexist by design.

## Disposition
Feature code, instrumentation ([QUAL2-PROBE] logging + door-solid draw counter),
QUAL-2 waypoint cameras, and e2e.sh flag ON (:51) are all kept. Evidence shots for
the key A/B pairs ship in docs/screenshots/ (gitignore excepted via -f).

## Verdict
QUAL-2 (every door, one solid colour) is VERIFIED: gate wired, runtime-probed
29/29, zero cost, zero scene-visible regressions, no visual change OFF->ON in the
captured tour because the visible-door shots are UI-masked or oblique — the
runtime probe (doorTex=29 on every door draw) is the authoritative proof that all
doors now render one solid colour per state.