# WORLD_FINISHER_PLAN — Houses, Forest, Finishing, Bloom

Companion to HYPOTHESES_50.md Phase B (H13–H22). Ground rules first:

## Current state (verified 2026-09-06)
- `world/WorldBuilder.java` — builds 143 rooms / 9 hallways; rooms are
  floor slabs + crystal/book props, **no walls, no roofs** (the "houses are
  not finished" problem). Mansion + courtyard + outside world exist.
- `world/OutsideWorld.java` — the forest; sparse tree scattering, no
  floor detail, no paths, no audio.
- `render/BloomEffect.java` — exists, wired (GameEngine.java:290,1327),
  single intensity/threshold pair, no scene presets, no event hooks.
- Renderer palette: colors are ad-hoc per mesh today.

## Finishing standard (every H# in Phase B must meet ALL)
1. **Deterministic** — anything random uses DeterministicSeed with a
   fixed recipe string, so E2E waypoints are frame-stable.
2. **One draw call per type** — trees/rocks/grass are instanced or
   batched; Intel HD 510 target is ≥30 fps at every E2E waypoint.
3. **Palette-locked** — new geometry registers in Renderer's palette
   table; no magic numbers inline.
4. **E2E-visible** — each step adds or extends a waypoint shot proving
   the change is on-screen, not just in code.
5. **CME-safe** — world mutations happen on the render thread or via
   snapshot pattern (the known agent-thread CME class — see
   BUGS memory); every new list touched gets the synchronizedList +
   snapshot-at-iteration treatment.

## The work

### Houses (H13–H15)
- Wall perimeter per room: 4 walls with door gaps cut where hallways
  meet. Door gap = hallway opening width + 0.6m margin.
- Flat roof slab + parapet ring per room; courtyard and hallways stay
  open-sky (visual variety, cheap).
- Ceiling height 4.5m inside rooms, 6m in the main hall.
- Material: wall stone (palette: warm gray), roof slate (dark), parapet
  same stone. Rooms are houses — they should read as buildings from the
  outside and as interiors from within.

### Forest (H16–H19)
- Tree species ×3: pine (cone stack), oak (blob + trunk), deadwood
  (branching). Density: 3× current, falling off radially from the
  mansion so the approach reads as parkland → wild.
- Floor: grass tufts, fallen logs, rocks — all instanced.
- Cobblestone paths from each hallway exit to the forest ring.
- Wind-rustle audio layer, volume by distance-to-forest-centroid.

### Bloom & server effects (H20–H21)
- Per-scene presets: courtyard (warm 1.0/0.55), forest (cool 0.7/0.45),
  hall (neutral 0.8/0.6) — lerped on region change over 1.5s.
- Quorum events drive flares: APPROVED → gold flare (intensity +0.6,
  2s), REJECTED → cold dip (−0.3, 2s). The mansion physically reacts to
  governance. E2E shot mid-flare is the proof.

### Finishing sweep (H22)
- Pass over all new + existing meshes → single palette table in Renderer.
- HUD "region name" fades in on region change (cheap, big feel win).

## Build order inside Phase B
Walls → roofs → heights → trees → floor → paths → audio → presets →
quorum-hook → sweep. Each is one TASK file, each ends with the cascade
(build, selftest, E2E, commit, push, issue comment).

## Performance budget (hard numbers)
- Tree+floor+path draw calls ≤ 40 total (instancing).
- Bloom stays 2-pass (already is); presets only lerp uniforms.
- No new textures — vertex-color + palette only (keeps jar small,
  avoids texture-upload cost on Intel HD 510).