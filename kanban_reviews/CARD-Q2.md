# CARD-Q2 — World readability at 1080p (review)

Reviewer: Claude · 2026-09-25 · scope: door prompt + TODO crystal render paths,
the two "known dark" E2E waypoints (DEV_SETUP.md §6). No source changed.

## VERDICT

**PASS-WITH-FIXES.** Both "dark shot" bugs are mostly **camera-framing bugs in the
E2E tour script**, not renderer bugs — the waypoints point the camera at a wall /
ceiling. Underneath them sit two real player-facing affordance gaps: the door
prompt ignores fog-of-war, and crystals are small, unlit-looking, and live
behind walls. All fixes below are constants / conditionals — zero new geometry.

## FINDINGS

**F1 — Crystal waypoint camera is inside the wall and looking at the ceiling (HIGH, root cause of 07_todo_crystals).**
`engine/GameEngine.java:4943-4944` places the camera at `x = -2.0` with `pitch = 20`.
`WorldBuilder.HALLWAY_WIDTH = 3.5f` (`world/WorldBuilder.java:29`) → hall half-width
is 1.75, so x=-2.0 is 0.25m *past* the side-0 wall line (doors sit at x=-1.75).
`render/Camera.java:40` sets `front.y = sin(pitch)` → positive pitch looks **up**.
Result: a frame of wall-interior / ceiling. "Crystal visibility timing" in the doc
is a misdiagnosis.

**F2 — Crystals are never in the hallway at all (HIGH, readability).**
Crystal positions are book world-positions or room centers
(`GameEngine.java:762-766`, `:506`, `:4795`, `entity/AgentNPC.java:312`) or the
mansion front (`:782`). All room ones are behind room walls/closed doors, so a
hallway waypoint can't see them regardless of framing. Plus the 20m distance cull
(`GameEngine.java:1954`) drops anything down the hall.

**F3 — Crystals are thin and low-contrast (MED).**
`GameEngine.java:1958-1959`: 0.15×h×0.15 cube, h ∈ [0.15, 0.6]
(`world/TodoCrystal.java:27-29`) with `TEX_NEON_GREEN` (0.1,1.0,0.3 —
`render/Renderer.java:94`) — same texture as LabDevice PASSING, AnimationSystem,
outside LEDs, WorldBuilder signage (`WorldBuilder.java:372,527,771,788`). A 15cm
green stick sitting on a book shelf is ~8-20px tall at 1080p from 3m and reads as
"another green thing". Label is 0.03 billboard (`:1966`) — ~half the size of NPC
labels (0.05, `:1943`); 24-char truncation (`TodoCrystal.java:44`) is fine.

**F4 — Door-prompt waypoint frames a closed door at 1.5m (HIGH, root cause of 13_door_prompt).**
`GameEngine.java:4989-5012` stands 1.5m from the door and aims **at the door
center** (`door.y = hy+1.0`, `WorldBuilder.java:1250`; eye `door.y+0.7`) → pitch
≈ -25°, the door/wall fills essentially the whole frame. The prompt itself is fine
(depth test off, 3m anchor — `ui/InteractionPromptSystem.java:222-249`) but it's
one 0.065-size magenta line over a dark close-up, so the luminance-based "non-black"
check sees a dark frame.

**F5 — Waypoint 12 may pick a fogged room (MED, same shot).**
It takes the first room with a non-null door (`GameEngine.java:4994-4996`) without
checking `room.isFogged() && !fogOfWar.isRoomRevealed(room)`. WorldBuilder skips
drawing those doors (`world/WorldBuilder.java:671,701,723-724`) → the shot is a
plain wall with a prompt floating over it.

**F6 — Door prompt is fog-unaware (MED, real player bug).**
`InteractionPromptSystem.select()` door loop (`ui/InteractionPromptSystem.java:117-133`)
has no fog check; neither does `entity/Player` (no `isFogged` reference in
entity/ or ui/). Player sees "ENTER Open door: <repo>" at a blank wall.
Contradicts the fog affordance ("Fogged rooms have no visible door", WorldBuilder:670).

**F7 — Prompt has no backing plate (LOW).**
`PROMPT_COLOR = (1.0, 0.2, 0.9)` (`InteractionPromptSystem.java:60`) is legible on
dark walls but goes under bloom glare next to neon signs; no shadow/plate.

**F8 — Screenshot is taken in update, not render (LOW, latent).**
`captureLabeled` runs from `updateE2ETour` ← `updateAutodrive` (`GameEngine.java:853,
4836`), i.e. *after* the last `glfwSwapBuffers` (`:1819`). `Screenshot.java:16`
itself says "call BEFORE glfwSwapBuffers" and reads `GL_BACK` (`:21`), whose contents
are undefined post-swap. It works today because the driver preserves the buffer
and the pose is static for 3s — but on another driver it yields black/garbage shots.

**F9 — Doc drift (LOW).** DEV_SETUP.md:74 names `12_door_prompt`; the code
labels it `13_door_prompt` (`GameEngine.java:5012`). Waypoint `case 2` comment
says "look down" with `pitch 50f` (`:4922-4924`) — that's up (see F1); worth a
glance while touching the tour.

## RECOMMENDATIONS (cheapest first)

1. **Fix the crystal waypoint** (F1): `x = 0f`, `pitch = -20f`. 2 constants.
   Better: pick a room whose crystal exists and stand in its doorway/interior
   (reuse the `case 15` room-interior pose), or aim at the 4 mansion briefing
   crystals (`:782`), which are guaranteed to exist and are outdoors.
2. **Fix the door waypoint framing** (F4): stand 2.5m out (still < `DOOR_RANGE`
   3.0), aim at `door.y + 0.6` (plaque height) so hall + door + prompt share the
   frame. FACING_DOT 0.75 still passes.
3. **Skip fogged rooms in waypoint 12** (F5): add the same
   `isFogged && !isRoomRevealed` guard WorldBuilder uses. One `if`.
4. **Fog-gate the door prompt** (F6): same guard in `select()`'s door loop, and
   in `Player.findDoor` so Enter matches the prompt. Needs WorldBuilder to expose
   its `FogOfWar` (getter) — check whether one already exists first.
5. **Crystal material** (F3): give crystals a dedicated emissive color (e.g.
   white-cyan or amber, not shared green) via a new `Texture(r,g,b)` slot — one
   line in `Renderer.java` next to `:94`; bump footprint to 0.25 and min height
   to 0.3; label 0.03 → 0.045. Material/scale only, no new geometry.
6. **Prompt plate** (F7): draw a dark translucent quad behind the prompt text
   if FontRenderer already has a background-quad path; otherwise a 1px black
   drop-shadow (render text twice, offset). Skip if it needs new shaders.
7. **Move E2E capture into render()** (F8): set a `pendingShotLabel` in
   updateE2ETour and call `captureLabeled` just before `bloom.end()`/swap at
   `GameEngine.java:1818-1819`. Removes a driver-dependent flake.
8. **Update DEV_SETUP.md §6** once 1-3 land: drop the "known dark" baseline
   excuse and fix the 12→13 label.

## PERF-NOTE (Intel HD 510 / GeForce 945M, GL 3.3)

- Items 1-4, 7, 8: zero per-frame cost (constants, one boolean check per room
  already iterated; fog check is a grid lookup).
- Item 5: no draw-call change — crystals already one `drawCube` each; texture
  slot is a 1×1 color. Label size change is fill-rate-neutral.
- Item 6: +1 text draw per frame (only when a prompt is active). Negligible.
- Watch-out (existing, not new): `renderCrystals()` rebuilds `proj`/`view`
  matrices **per crystal** (`GameEngine.java:1963-1964`) and copies the list each
  frame (`:1951`). Fine at today's cap; if crystal count grows, hoist the two
  matrices out of the loop — free win on HD 510's weak CPU side.
- Do NOT fix F2 with point lights or particles around crystals; bloom already
  picks up emissive-bright colors, which is the cheap "glow".
