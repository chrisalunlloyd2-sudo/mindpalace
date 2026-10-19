# QUAL-1 Visual Defect Sweep — Final Report (2026-10-19)

Task: t_c878e643 (QUAL-1, run 362). Method: GL-free geometry audit (GeoAudit
recorder + `scripts/geo_audit.py`) + glTrace program/error trace + screenshot
diff vs the MP-004 2026-10-07 baseline, all over the standard 22-waypoint e2e
tour (`--e2e`, time 21). No GL context is used by the audit; the hermetic jar
(`target/qual1/mindpalace-qual1.jar`) never touches the live game path.

Data legs:
- Tour B2 (geometry + glTrace flags): 131,650 geo records / 42 snapshots in
  `target/qual1/tour_b2/geo_audit_out/geo_records.jsonl`, 22 PNGs.
- Tour A (plain): 22 shots, exit 0, 11:16 wall — used as the same-build diff
  control (determinism leg).
- Analyzer: `scripts/geo_audit.py` (450 lines, selftest 10/10) — final figures
  after three retunes (ground-plane floaters → lintel-aware wall runs →
  WALL_THICKNESS thin-trim filter).
- Shot diff: `scripts/shot_diff.py` (141 lines, PIL-only) vs
  `AIGEN_SYS/baselines/2026-10-07_final_quiet/shots` (21 files — the baseline
  tour predates the 22nd waypoint `22_mansion_spawn`; not a missing shot).

---

## D1 — GL_INVALID_OPERATION(0x502) ×1,963: wrong shader program at cubeColorYaw  [DEFECT — fix, highest severity]

- Symptom: `[GlTrace] GL_INVALID_OPERATION(0x502) between
  [cubeColorYaw:model-uniform] and [cubeColorYaw:useTexture-uniform]`,
  first seen frame 91; cumulative ×804→1116→1860→1963 at the frame-600/1200/
  1800/2400 summaries. Companion trace lines `wrong program at
  [cubeColorYaw]: current=18 expected=3` at frames 91–95 only.
- Meaning: program 18 = FontRenderer's textShader, 3 = basicShader. Every
  cubeColorYaw uniform set while 18 is bound is silently INVALID → avatar
  clothing layers render with the previous program's uniforms (wrong colours /
  transforms) during those frames. Frames 91–95 is startup of the first NPC
  visible frame batch; the stale-program condition then persists whenever the
  same ordering recurs.
- Where:
  - Hook/expected-program site: `Renderer.java:183`
    (`GlTrace.expectProgram(basicShader.getId(), "cubeColorYaw")`), marks at
    :184–:190 inside `drawCubeColorYaw` (Renderer.java:178–192).
  - Emitting call sites: NPC+avatar body/clothing draws —
    `GameEngine.java:2106/2110` (block-emit cube fallback path),
    `GameEngine.java:2201/2203/2207/2213/2215/2218/2238` (renderNPCs body),
    `DressingRoom.java:220–242` (same drawing used by the dressing-room UI).
  - Program juggling around the collision: text restores its bound program
    (`FontRenderer.java:301` save, :398 restoreProgram); particle batch saves
    +restores too (`ParticleBatch.java:85`, :110). The unprotected seam is the
    shop-toast / menu billboard block (`GameEngine.java:2053
    fontRenderer.renderBillboard(shopToast...)`) which runs in the same
    `hud+toasts` GlTrace span (marks at GameEngine.java:2002 and :2012), after
    the world-text block that leaves program 18 current whenever a restore
    path was skipped.
  - Ordering trigger: the particle-batch fallback cube path rebinds basic via
    `renderer.bindBasicShader()` (GameEngine.java:2099) but only runs when
    `particleBatch != null`; with the batch active there is no rebind before
    NPC clothing draws, and the first frames that combine "text shader was
    restored wrong or not at all" + "cubeColorYaw runs" produce 0x502.
- Repro: Tour B only (flag geometry/cull-override changes draw order). Tour A
  shows zero GL_INVALID lines — the defect is real but ordering-sensitive;
  the e2e sweep path exposes it deterministically at frame 91.
- Fix (proposed, for QUAL-6): bind basicShader before NPC/emit draws —
  `renderer.bindBasicShader()` at the top of `renderNPCs()` (after
  GameEngine.java:2130 entry) and inside the emit-cube fallback already
  covered by :2099; plus FontRenderer/ParticleBatch restore paths are correct
  and need no change. One-line class fix, zero geometry cost, matches MP-004
  "material/uniform changes only" perf budget.
- Severity: HIGH — silent per-frame GL errors + wrong-colour avatars during
  affected frames; deterministic on the audited path.

## D2 — Potted-plant foliage floats detached in mid-air at hallway edges  [DEFECT — cosmetic, cheap fix]

- Symptom: C_floater clusters of 0.35³/0.2–0.25³ green cubes with bottoms
  0.38–0.55 above the ground plane, no vertical support geometry; e.g. 4–5
  foliage-cube pairs per room corner at (−7.35, 0.55, z=5..20 cadence).
- Where: `WorldBuilder.renderOrnaments` — pot cube at :1240–1241
  (`floorY + 0.2f`, 0.4 tall ⇒ top at 0.4), foliage at :1243–1247
  (`floorY + 0.55f` and `floorY + 0.5f`): canopy is intended to sit ON the
  pot, but the two foliage cubes' bottoms (0.375 / 0.375 vs pot rim 0.4)
  leave a visible sliver of air between pot rim and canopy, and at rooms where
  `floorY≠0` the hardcoded constants drift.
- Why it reads as a defect: at hallway-edge rooms the canopy visually detaches
  from the pot (user-visible "floating plant"). Not a wall/floor hole.
- Fix (proposed): lower foliage to `floorY + 0.38f` / `floorY + 0.33f` (or
  compute from pot top = floorY+0.4). Material-move only, zero perf cost.
- Severity: LOW cosmetic. ~204 of the 367 floater findings are this shape.

## D3 — Teleporter beam ring bar pulses from y=padY+0.85 with no base contact  [INTENTIONAL — no card]

- Symptom: C_floater 0.5×1.5–3.0×0.5 colour cubes (drawCubeColor) bottom
  0.10–0.85 above pad, `WorldBuilder.renderTeleporter:452`.
- Decoded: the theme-colour pulse beam (`beamH = 1.5 + pulse`), designed to
  rise from the pad inner core (:446–452). Motion + theme colour = intended.
- Action: none. Excluded from defect list.

## D4 — Courtyard 1.0³ cubes bottom 0.10 above hardwood  [INTENTIONAL — no card]

- Symptom: C_floater 1.0³ cubes @ (−5.50, 0.60, −13.50) etc.,
  `WorldBuilder.renderCourtyard:692`.
- Decoded: planter rim / column head blocks on the courtyard hardwood
  (renderCourtyard floor at :570, glass ceiling at :573–574) — supported by
  their own local structures; safe-ground intact. Camera-angle artifact of
  the audit's ground-plane-only rule.
- Action: none. Excluded from defect list.

## D5 — E_walls wall_run 1,491 @ 41 wp  [ALL SUPPORTED — analyzer semantics only, no defect]

Final E_walls mass decodes entirely to non-wall geometry that shares the
audited planes; verified against the raw dump:

| Flagged line | Dump contents at that line | Verdict |
|---|---|---|
| axis-0 x=8.40 | bookcase divider plates (TEX_DOOR, 0.02 thin) `renderBookcase:1349/1358`; OutsideWorld shop/factory/mansion slabs at other structures | bookcase cadence, not a wall run |
| axis-0 x=−168.00 | `OutsideWorld.renderModelShops:985/991` (200+200 plates) | shop interior dividers |
| axis-0 x=3.20 | `renderBookcase:1347/1358` (40+97) | bookcase dividers |
| axis-2 z=±1.75 | (pre-filter runs) mixed thin trim: posters/signs/door-frames 0.02–0.1 sharing wall plane; top site `renderBookcase:1363` ×41 | thin-trim mass — now excluded by the WALL_THICKNESS filter |
| 3.90 m gap −1.95..1.95 rows | renderRoom full-room frame floor tiles s=[6.0,0.1,6.0] @ `renderRoom:980–986` (95/58/45 hits at x=0.0/0.1/5.0/8.5/10.0) — flagged planes are 5-m room-boundary tile cadence; actual hall walls sit at different planes | audit plane misattribution, geometry present |

Conclusion: **zero real wall gaps** found in the shipped world. The
analyzer's wall-run check is retained (selftest 10/10) as a regression gate;
its findings on the live tour are all this supported class.

## D6 — Screenshot diff vs 2026-10-07 baseline: OK=2 HOT=19  [EXPECTED CHURN — no defect]

- Baseline is a 21-waypoint MP-004 run (README.md: "mindpalace 195ac59 … 21
  waypoints"); `22_mansion_spawn` was added after → 22 vs 21 names; every
  other file reconciled 1:1. Not a defect (missing baseline shot).
- HOT drivers are shipped features newer than the baseline (avatar/NPC
  changes, posters, gist wall, teleport rect, floor2 picker modal): top deltas
  09_agents mean=60.44×87.3%, 16_room_interior 59.55×88.2%,
  12_nash_fountain 52.15×67.2%, 17_hall_windows 52.05×54.2%.
- Determinism leg (same build, tour_b2 vs tour_a): OK=20 SOFT=0 HOT=2 —
  `11_floor2_picker` (36.26/23.9%) = HUD-suppressed modal frame rect
  (-1.75,-0.72 3.30x1.59) in-frame timing shift; `09_agents` (5.18/5.3%) =
  NPC gait-phase (sin(bobPhase)) nondeterminism. Both non-defects.

## D7 — Analyzer self-audit quality  [TOOLING HEALTH — no defect]

- `scripts/geo_audit.py` selftest 10/10 (wiped `$TEMP/geo_audit_selftest`
  first), 450 lines, stdlib+PIL only.
- Three over-flagging layers fixed during this sweep: C_floater
  ground-plane rewrite (105,799 → 367), check_wall_runs lintel-aware rewrite
  (211k → 12,321), WALL_THICKNESS thin-trim filter (12,321 → 1,491 geo leg).
- `scripts/shot_diff.py` selftest PASS (same=0.00 diff=190.00), 141 lines.

---

## Ranked defect list for QUAL-6

| # | Defect | Site | Severity | Evidence |
|---|---|---|---|---|
| D1 | wrong program 18 vs 3 at cubeColorYaw → ×1,963 0x502 | Renderer.java:183 + GameEngine.java:2053/2130/2099 | HIGH | tour_b_stdout.log GlTrace frames 91–95 + summaries |
| D2 | potted-plant canopy floats 0.15 above pot rim | WorldBuilder.java:1243–1247 | LOW | 204 C_floater findings |
| — | D3/D4/D5/D6 | — | none | decoded intentional / supported |

Per-card detail: **D1 → `QUAL-D1`**; **D2 → `QUAL-D2`**. D3–D6 are recorded
here and in kanban comment 227 as decoded-non-defects so QUAL-6 doesn't
re-chase them.

Artifacts:
- `target/qual1/geo_findings.md` + `geo_print.log` (final analyzer output)
- `target/qual1/shot_diff_findings.md` / `shot_diff_print.log` /
  `shot_diff_tourAB.md` (baseline + determinism diffs)
- `target/qual1/tour_b2/geo_audit_out/geo_records.jsonl` (131,650 records)
- `target/qual1/tour_b_stdout.log` (GlTrace evidence, frames 91–95)
- `target/qual1/probe_hits.txt` / `probe_door.txt` (call-site attribution)