# MP-007 (#153) — Door Dissolve: door → portal quad alpha/rim over ~0.5s

**Status**: SHIPPED — commit c5d3f1a, pushed 2026-10-23 (origin/main verified).
**Evidence**: e2e run #9 (selftest 87/0, VERIFY-PASS, 0 GL errors, hue gates OK).

## Spec
"door dissolve: door → portal quad alpha/rim over ~0.5s" — the door leaf
disappears and a translucent, rim-lit portal sheet takes its place, ramping
over ~0.5s. No auto-close (doors stay open).

## Root cause (the interesting part — pixel-proven)
The first implementation looked correct in code but photographed as a static
amber slab in EVERY door shot, mid-dissolve and open alike. Three layers were
stacked wrong:

1. **Wrong wall.** The MP-006 district leaf and MP-007 portal sheet were drawn
   inside `renderRoom` on the room's **z-plane "front wall"** at
   `z = doorZ ∓ ROOM_DEPTH/2` — ~3 m off the real doorway. Corridor rooms sit
   with their hallway-facing wall **⊥X** (door aperture cut by
   `renderWallWithDoors` at `x = ±HALLWAY_WIDTH/2`, corridor along Z). A z-plane
   quad at the far end of the room is exactly edge-on from any hallway camera:
   invisible. The leaf and the sheet had NEVER been seen from the hallway.
2. **An always-on occluder.** `renderDoorFrame` (hallway pass) draws an
   emissive **amber halo** — `(0.02 × dh+0.2 × dw+0.2)`, i.e. 1.4×2.6 m,
   opaque, proud of the wall face — behind the neon trim. At the tour camera
   (2.5 m, FOV 70 vertical → f_px ≈ 771) the 1.4 m halo projects ≈ 430 px
   (+bloom halo → the observed ~480 px amber slab with center (255,199,22)),
   and its aperture span matched the projected door aperture exactly
   (x∈[775,1145], y≈324–790 = world 0.89→2.4 m). It sat IN FRONT of the sheet
   plane and hid everything behind it — independent of draw order.
3. **A control experiment nailed it.** A door the tour never opens
   (waypoint 13, door state untouched, open=0) shows the IDENTICAL amber slab:
   (255,213→187) column scan, pink trim, same geometry. Same pixels from a
   never-dissolving door ⇒ the slab is not the leaf and not the sheet. Combined
   with the geometry math (leaf/sheet at z-plane = edge-on), the slab could only
   be the always-drawn halo.

## Fix (KISS, 4 files)
- **WorldBuilder.renderRoom**: leaf + sheet re-oriented onto the ACTUAL
  aperture — thin in X (`doorApertureX = room.getDoorPosition().x`, fallback
  `c.x ± (w/2 + WALL_THICKNESS)`), leaf at the room center z, sheet offset
  0.08 m toward the hallway (`hallDir`) so it draws in front of the leaf and
  the crossfade reads as one door, two states.
- **WorldBuilder.renderDoorFrame**: amber halo gated to closed doors
  (`doorOpenAmount <= 0`); once the door opens the sheet owns the aperture.
- **Renderer.drawPortalQuad**: new `portalPlane=2` — vertical doorway quad
  (thin X, rim band on the Y/Z edges). portal.frag gains the matching
  else-branch (d = max(|rel.y|/hy, |rel.z|/hz)).
- **Room**: `DOOR_ANIM_SPEED` 3.0 → 2.0 ⇒ full open/close = ~0.5s (spec).
- Sheet alpha: `min(0.85, open × 1.25)` — lit by ~80% of the ramp, capped so
  the doorway ghosts the room through it ("window into the room"), never a
  hole in geometry. `MP_DOOR_DISSOLVE=0` restores pure MP-006 behaviour.
- **GameEngine**: DOORPICK e2e probe (set door target inside the shoot tick
  only, one-tick-per-frame capture ⇒ shot 31 photographs amount=0.5), then
  full open; e2e.sh labels `31_door_mid_dissolve`, `32_door_portal_open`.

## Evidence (run #9, canonical Paeth-unfiltering sampler)
- Mid (shot 31): amber slab GONE. Upper aperture = blue translucent sheet
  under the lintel (probe row `RBBBBR` = pink posts flanking blue sheet),
  center (45,60,82) at alpha 0.62; lower aperture open (leaf slid).
- Open (shot 32): blue sheet (29,57,87) fills the whole aperture, brighter
  and more saturated than mid (dissolve visibly progressed); pink rim/posts
  at x=780 and x=1140 (lum 125) flanking blue body (lum 52-55).
- Closed control (shot 13): amber halo PRESENT (gate correct).
- f_px sanity: 1.2 m aperture at 2.5 m, FOV70-v ⇒ ~370 px; observed ~380 px.
- Gates: selftest 87/0 · VERIFY-PASS · 0 GL errors · HUD gate 0 ·
  portal-pad hue pair amber=1236/cerulean=427 OK · prompt magenta=118 OK.

## Lesson
Control-experiment before code changes: photograph an UNTOUCHED instance of
the same structure; if the artifact reproduces identically, the artifact
belongs to static geometry, not the feature under test. And every "where is
this drawn?" must be answered with BOTH the wall plane AND the camera
orientation — a correct draw on an edge-on plane is invisible.