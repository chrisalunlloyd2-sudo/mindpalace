# MP-008: Doorway Interior Preview (#154)

**Status:** IMPLEMENTED (commit 3bb759a)
**Issue:** #154 — "through the open doorway you should get a glimpse of the room beyond"
**Kills:** nothing — orthogonal to MP-006/MP-007; kill-switch `MP_DOOR_PREVIEW=0`
(or preview simply not baked yet) restores the byte-identical MP-007 rim sheet.

## What

When a door dissolves (MP-007 sheet fades in inside the aperture), the sheet
stops being a flat coloured rim pane and becomes a **window onto the room's
interior** — sampled from a pre-baked 256² texture of what that room looks
like from just inside its own doorway.

## Why pre-baked (design choice)

A true render-to-texture pass per doorway per frame would double the scene
draw cost and break the 1.3fps-co-resident budget the e2e tour already
fights (t_50d9d90b). Instead:

- **One shared 256² FBO** (RoomPreviewBank) is reused by ALL rooms.
- **One room baked per frame**, round-robin over the visible+revealed set —
  the same cull/fog gate the live pass uses. Steady-state cost: one small
  render per frame at 256², amortised.
- **State-hash re-bake**: room re-bakes only when health score, tint, or
  floor changes (Entry.hash). Static rooms bake once per session.
- **No light push during bake** — the bake renders with whatever phase
  light is live, so previews automatically match day/dusk/night.

## Bake geometry

The aperture is a thin-X cut in the hallway wall (⊥X plane), so the bake
camera parks INSIDE the room just behind the aperture wall:

- side 0 (hallway toward +X): position `dp + (−0.45, +0.2, 0)`, yaw −90 →
  looks −X across the interior (dp.x − 0.45·… wait: inward = −1 → x −0.45,
  looking −X INTO the room whose body spreads −X of the wall).
- side 1 mirrored (inward = +1, yaw +90).
- Aspect = sheet aspect (DOOR_WIDTH−0.08)/(DOOR_HEIGHT−0.08) ≈ 1.12/2.32 —
  the baked frame then fills the sheet with no letterboxing.
- Uniforms-only snapshot/restore (`setUniformsOnly`): the main pass's
  projection/view/viewPos are saved and restored around the bake, so the
  real camera continues seamlessly. Viewport restored to full size.

The bake renders `renderRoom` with `interiorOnly=true` — but the geometry
needs no skips at all: the near wall, leaf, and sheet are all BEHIND the
bake camera, so the frame naturally contains only through-the-door content.

## Sheet sampling (portal.frag)

Plane-2 span is Y/Z. The remap:

```
u = 0.5 − rel.z / (2·half.z)     // flipped: bake's inward yaw vs sheet +z
v = 0.5 + rel.y / (2·half.y)
```

- **Parallax**: lateral viewer offset along the wall (`viewPos.z −
  center.z`) shifts u like the pane sits 0.12 units behind the wall
  (clamped ±0.18 so edges never tear).
- **Shimmer**: `1.7·t` sinusoid × (1 − smoothstep(0.55..1, d)) — strongest
  at the pane centre, dead at the rim so the MP-007 rim glow stays pure.
- **Rim preserved**: `mix(rimCol, pane, 1 − smoothstep(0.82, 0.98, d))` —
  the outermost 15% of the sheet remains the glowing district-colour band.

Shimmer clock (WorldBuilder.sheetShimmerPhase) advances 0.016/frame only
when the bank is pumping — deterministic across e2e runs (no wall clock).

## Cost model

- Steady state: +1 draw-mesh set per frame at 256² (≈ one small room) —
  well under one full room's main-pass cost.
- Burst: N revealed rooms × 1 bake each on first reveal (N ≤ room count),
  1 per frame, so a fresh reveal pays its preview tax over N frames.
- FBO allocation: 1× 256² colour + 1× 24-bit depth RB, lifetime = session.

## Failure / kill-switch behaviour

- `MP_DOOR_PREVIEW=0` → doorPreviewEnabled false → sheet draws exactly the
  MP-007 rim path (previewTex=0 overload → identical uniforms).
- Room visible but not yet baked (first ~N frames after reveal) → rim sheet
  until its bake slot arrives; then the window fades in on next state hash.
- GL context loss → dispose() cleans FBO + textures; bank lazily re-inits.

## Evidence hooks

- `[PreviewBank]` bake log lines (bakeOne returns 1 →
  WorldBuilder.previewsBakedThisSession, exposed getPreviewsBaked()).
- Tour shot 30/31 analog: interior visible through the sheet in the
  door-dissolve waypoint at night (run #10).