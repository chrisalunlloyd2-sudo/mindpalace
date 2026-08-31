# MindPalace — Cross-Correlated Master Roadmap

> Living index. Every Architect ask/position, cross-referenced against its source
> doc + current code status + next action. **Nothing here deletes a step — it
> MERGES all sources.** Maintained by Kernel + the local agent together.
>
> Sources:
> - `[chat]`  = latest asks (this conversation)
> - `[BB]`    = BLACKBOARD.md
> - `[UB]`    = UNFINISHED_BUSINESS.md
> - `[RM]`    = ROADMAP.md
> - `[BP]`    = BLUEPRINT.md
> - `[SPEC]`  = the 50-feature "Magical World Sphere" design doc (Parts I–V)
>
> Status legend: `DONE` · `PARTIAL` · `NOT-STARTED` · `BROKEN` (root cause found)
>
> Last updated: 2026-08-23

---

## 0. Position changes (do NOT throw away — record the supersession)

| Old position | New position | Resolution |
|---|---|---|
| `[BB]` "No VR. Non-VR mode only." | `[chat]` "VR works — save it as VR success. Keep doubles, add a 2D toggle." | Keep the VR path **untouched**. ADD a parallel 2D/readable path + an always-visible toggle. The "No VR" constraint is **superseded, not deleted** — it explains why the tunnel-view reads as VR today. |
| `[chat]` earlier "Taylor adds and mods" | (resolved 2026-08-15) | "Taylor" = autocorrect for **Turing**. Not a new agent. |
| `[chat]` "the bot" | (resolved 2026-08-15) | Bot name = **Kernel**. |

---

## 1. Readability — backwards 3D text in non-VR  (TOP PRIORITY)

**Ask:** `[chat]` "when not using vr I look at the text on screen it's backwards …
letters are not backwards when I'm looking at them." Also `[SPEC]` IV-41 (kinetic
readability), `[SPEC]` III-23 (holographic text viewers).

**Root cause (confirmed by reading source):** `FontRenderer.renderBillboard` rotates
text with `rotateY(atan2(facingNormal.x, facingNormal.z))`. When the camera is
behind the text (`|angle| > 90°`), `rotateY` flips the local +X axis, so glyphs read
right-to-left = **mirrored/backwards**. A pure Y-rotation cannot simultaneously face
the camera AND keep left-to-right reading when the camera is behind — it needs the
camera's screen-right vector.

**Status:** `DONE` — commit `0a96fa3` (cylindrical billboard: glyph +X follows the camera screen-right from the view matrix, so text reads left-to-right from every angle).

**Next action (minimal, no delete):**
1. Add a proper billboard matrix that aligns text `+X` with the camera's
   screen-right (cylindrical billboard using camera basis, not a single `rotateY`).
2. Add a mirror guard: when `|angleY| > 90°`, flip the glyph UV so it never reads
   backwards (this is the "doubles" trick — never removes the wall/floor path).
3. Keep `renderText` (wall), `renderFloorText`, `renderBillboard` all intact — only
   fix the billboard math. This is the "rotate to face me" ask.

---

## 2. VR ↔ 2D toggle (doubles, never delete VR)

**Ask:** `[chat]` "always visible vr toggle to take the 3d text and pin to walls or
books or an editor 2d interface… entire game flip between 2d and vr… live toggle all
text, graphics stay the same… save as vr success… need the doubles for the toggle."

**Status:** `NOT-STARTED` (no toggle exists; only the cropped-window "tunnel view"
comment at GameEngine:174).

**Next action:**
1. `ReadMode` enum `{ TUNNEL_VR, SCREEN_2D }`, persisted (in-game config file).
2. Always-visible HUD button (top-right) toggles it live.
3. `SCREEN_2D` path = HUD pinning: wall-pin / book-pin / editor-pin (the 2D surface).
4. VR path stays byte-for-byte. This is an ADD, not a rewrite.

---

## 3. Teleporters — link anywhere from anywhere

**Ask:** `[chat]` "teleporters need to link up so I can teleport anywhere from
anywhere." + `[SPEC]` II-11..20 (stencil portals, accretion disk, bezier conduits,
Alcubierre jump, gimbals, paired color themes).

**Status:** `DONE` for the core (local agent shipped: "teleporter network —
Diablo-style portal list, return pad on planet", "teleporter menu lists actual pads,
each routes to its exact pad", Phase G graphics: particle swirl + glow ring +
animated pad + shimmer). `[RM]` Phase G = DONE. **Paired complementary color
themes = `DONE`** (TASK_0001: `world/PortalTheme.java` — 6-pair palette rotated
by pad index, planet pad has its own Copper/Mint pair, destination picker shows
a two-block swatch per entry, selftest #38 + E2E waypoint 10 + hue assertion
in `e2e.sh`).

**Next action (extend, not redo):** `[SPEC]` II extras still open — bezier
lightning conduits between linked portals, Alcubierre FOV-punch camera warp,
particle ingest/egress, proximity acoustics. Add as a "portal polish" slice
on top of the existing network.

---

## 4. Avatars — better player + agent bodies

**Ask:** `[chat]` "the players need better avatars." + `[BB]` Phase B (90s polygon
aesthetic: low-poly limbs/head/arms, walk/arm-swing, idle bob, per-agent
color/role silhouette) + `[SPEC]` I-5 (Keplerian micro-particles), IV-40 (haptic
cursor).

**Status:** `DONE` — commit `f93243e` — B1/B2/B3 shipped: `AgentNPC.Sex` (Explorer→
FEMALE, Critic→MALE) + sex-dimorphic proportions (female: narrow shoulders 0.30 /
wide hips 0.38 / elongated legs 0.58; male: broad shoulders 0.44 / narrow hips 0.28);
walk/arm-swing + idle bob (B2, pre-existing); per-agent color (B3, `c922b16`); plus a
Cortana hologram material (`Renderer.drawHologramCube` + `shaders/hologram.*` — fresnel
rim + scrolling data-lines + scanlines) and tight-fit clothing as material layers
(`drawCubeColorYaw`): female = magenta bra (bust cups + band) + dark yoga pants on
legs/pelvis; male = bare hologram chest + dark trousers. Arms/head bare hologram; role
stays on the visor + label.

**Next action:** `[BB]` B1 → B2 → B3 in order: geometric polygon bodies, walk/arm
swing + idle bob, per-agent color/role silhouette. Keep first-person for player;
show agents only (B3 = Architect decision, hold).

---

## 5. World completeness + sky

**Ask:** `[chat]` "world needs to be more complete… sky needs to be better." +
`[SPEC]` I-1..10 (Rayleigh/Mie scattering, Fresnel rim, volumetric clouds, aurora,
day/night, magnetic field lines, parallax shells).

**Status:** `PARTIAL` — local agent shipped: "full sky dome (gradient sphere) — no
more black sky", "planet open world with radial gravity", "tree branches + bark,
procedural grass, water waves", "day/night cycle". `[RM]` Phase F day/night = DONE.

**Still open (`[RM]` Phase F unchecked):** outside-world *streaming* (render small
chunks, not all at once), Diablo/Zelda top-down view, local-weather sync. Plus
`[SPEC]` I-1..10 scattering/Fresnel/volumetric-clouds/aurora/parallax shells — hold
these for when a GPU budget is confirmed (BLACKBOARD constraint: Intel HD 510 /
OpenGL 3.3, low cost).

---

## 6. Physics / world logic

**Ask:** `[chat]` "code logic and physics on the world need to be better." +
`[SPEC]` IV-31 (spring-damper camera), IV-39 (6-DOF astral walk), IV-36 (click
shockwaves).

**Status:** `PARTIAL` — acceleration/friction + wall collision + noclip exist
(`[RM]` Phase 1.x). No spring-damper camera.

**Next action:** spring-damper orbital camera (mass/stiffness/damping) replacing the
rigid look; optional 6-DOF astral-walk mode (WASD + Space/Shift + mouse). Low GPU
cost (pure CPU math).

---

## 7. Chat + local models (BLACKBOARD bug #2 + BLUEPRINT diagnosis)

**Ask:** `[BB]` "chats not working" (5-min ModelScheduler gate). `[BP]` diagnosed 5
problems. `[chat]` "sims1337 will be the backend with the players and chat."

**Status:** `PARTIAL` — `[BP]` root causes found; doors/chat conflict fixed
("doors open on Enter, no chat conflict"); quorum voting + ModelRouter + LoRA +
FOWGate + LanguageRegistry PORTED from SIMS1337 into `agent/sims/`.

**Still open (`[BP]` problems + `[UB]` items):**
- `[BP]` P1: ✅ FIXED (`340dd2e`) — dedicated user-chat worker (`userWorker` +
  `drainImmediateLoop()`) so a reply never serializes behind a tool/agent call.
- `[BP]` P2: `lastUserMessage` dead field → use it to seed next autonomous cycle.
- `[BP]` P3: guide reply routed via `onToolMessage` → add dedicated `onChatMessage`.
- `[BP]` P5: book-content bloat (2000 chars every prompt) → truncate to ~500.
- `[UB]` 2: finish SIMS1337 parity — 4-tier ModelPool warm, LoRA switching wired to
  actual model calls, AdapterRegistry election.

---

## 8. Game engine TOOLS — defined but not executed  (`[UB]` 4)

**Ask:** the tool agent must actually read/write files (not just propose).

**Status:** `DONE` (verified 2026-08-23, wired by local agent) — `AgentManager`
has `executeToolRound()` + `executeTool()` (read_file/edit_file/create_file/delete_file
with GitHub + local-path fallback), driven by `modelScheduler.submitToolRound()` inside
`autonomousCycle()`. The earlier "no executeTool()" note was STALE.

**Next action:** implement `executeTool()` + tool-call parsing + result loop. This
is the biggest "not hooked in" gap (`[UB]` priority #2).

---

## 9. Doors — open-on-Enter, no auto-close  (`[UB]` 5)

**Status:** `DONE` — "doors open on Enter (no chat conflict), static frames, books
click on all walls" shipped. (If any auto-close remains, remove `closeDoor()` from
`exitRoom`.)

---

## 10. Map overlay (Tab)  (`[BB]` bug #1)

**Status:** `DONE` — `[BP]`/`[BB]` noted a missing GLFW_KEY_TAB handler; BUILD_STRATEGY
self-test now lists "map toggle" among its 13 checks.

---

## 11. Chat logs — per-day file → private GitHub  (`[UB]` 6)

**Ask:** `[UB]` "Architect wants a NEW text file per day, uploaded to GitHub (private
repo)." (Currently ONE `chat_logs/chat.jsonl`.)

**Status:** `STUB`.

**Next action:** per-day `chat_logs/YYYY-MM-DD.jsonl`, push to a private repo.

---

## 12. Email + morning digest  (`[UB]` 1 + 7)

**Ask:** working email + a daily "suggestions + code-mind log" digest.

**Status:** `BROKEN` — himalaya CLI not installed, no account config, no IMAP/SMTP
creds, `dream_job.py` only bumps a KG node (no real digest).

**Next action (needs Architect's email creds):** install himalaya → configure account
→ wire a real morning-digest job (suggestions + code-mind log) that emails out.
**This needs Chris's email credentials — flagged, not auto-buildable.**

---

## 13. Code editor → full suite  (`[UB]` 3)

**Ask:** full editor (language toggle, LoRA per language, KG nodes, syntax highlight,
line numbers, undo/redo, multi-file).

**Status:** `PARTIAL` — `LanguageRegistry` (20 languages) + LoRA mapping + editor
language toggle ARE wired (self-test check 20). Still missing: syntax highlighting
beyond a 40-line dump, undo/redo, multi-file project view, KG read/write.

**Next action:** syntax highlight + undo/redo + multi-file; then KG integration.

---

## 14. Memory / HDD fencing  (`[UB]` 8)

**Ask:** keep local models from weighing down the system (RAM cap, disk guard, model
unload policy).

**Status:** `NOT-STARTED` (only token budgets + 5-min spacing exist).

**Next action:** RAM cap + disk-usage guard + model unload (reuse SIMS1337
`LoRASwitcher` unload).

---

## 15. Windows installer  (`[UB]` 9 + `[RM]` Phase H)

**Status:** `PARTIAL` — Inno Setup script (`MindPalace.iss`), `build-installer.sh`,
`build-warm-installer.sh`, `INSTALLER.md` exist. jpackage `.exe` not yet done.

**Next action:** finish jpackage native `.exe` + Start Menu registration + uninstall.

---

## 16. USB backup  (`[UB]` 11)

**Status:** `NOT-STARTED`.

**Next action:** USB backup of AIGEN_SYS / repos / DBs / chat logs (`backup-to-usb.sh`
exists in repo — verify it covers all four).

---

## 17. Multiplayer + sims1337 backend  (`[UB]` 10 + `[chat]` + `[RM]` Phase 8)

**Ask:** `[chat]` "sims1337 will be the backend with the players and chat."

**Status:** `PARTIAL` — SIMS1337 *concepts* ported (ModelRouter, LoRA, FOWGate,
quorum, LanguageRegistry). Networking/lobby/second-player NOT built.

**Next action:** define the client contract (presence heartbeat + chat stream), stub
the adapter with graceful local-fallback, wire real keys when they arrive. Keep the
game fully functional offline.

---

## 18. Gist wall in-game  (`[chat]` "gist wall in game needs adds")

**Ask:** surface the fleet gist + word-library success paths as an in-game wall.

**Status:** `DONE` — commit `380c49c` — `com.mindpalace.github.GistWall` (OkHttp,
background daemon fetch, 60s cooldown) surfaces fleet_status.jsonl + workflow_logits.jsonl
+ word_library/success.jsonl as a right-side screen panel (`renderGistWall()`). Auth via
Credential Manager PAT or `MIND_PALACE_GITHUB_TOKEN`.

**Next action:** add a "Gist Wall" room/panel that reads the fleet gist
(`fleet_status.jsonl` + `workflow_logits.jsonl`) and word-library success paths, and
renders entries as readable billboards (reuse Section 1 fix). Then add new entries.

---

## 19. The 50-feature world-sphere spec — full index  (`[SPEC]`)

Cross-referenced to the above so nothing is lost. Hold GPU-heavy items until a
performance budget is confirmed (Intel HD 510 / OpenGL 3.3).

**Part I — World Sphere (1–10):** Rayleigh/Mie scattering (→ §5), Fresnel rim (→ §5),
volumetric clouds (→ §5), bioluminescent veins (→ §5), Dyson swarm particles (→ §4),
chromatic aberration/lensing (→ §5), aurora belts (→ §5), day/night city-lights (→ §5),
magnetic field lines (→ §5), parallax shells (→ §5). **→ ALL defer to §5.**

**Part II — Teleporters (11–20):** stencil portals (→ §3), accretion disk (→ §3),
bezier conduits (→ §3), Alcubierre jump (→ §3), vortex particles (→ §3), proximity
acoustics (→ §3), holographic preview (→ §3), gimbals (→ §3), paired colors (→ §3),
shatter FX (→ §3). **→ ALL defer to §3.**

**Part III — Memory nodes (21–30):** document shards, semantic constellations,
markdown viewers, memory palaces, doc archetypes, search beam, confidence heatmap,
temporal strata, OCR boxes, consolidation swarms. **→ new "Spatial memory" slice
(later; requires embeddings + UMAP in a worker).**

**Part IV — Kinematics (31–40):** spring camera (→ §6), DoF bokeh, audio-reactive,
micro-orbits, LOD morphing, click shockwaves (→ §6), bullet-time, constellation
drawing, astral walk (→ §6), haptic cursor (→ §4). **→ split across §4/§6.**

**Part V — Performance (41–50):** InstancedMesh, Octree/BVH, WebWorker UMAP, WebGPU
compute, quantized embedding viz, IndexedDB cache, streaming parse, occlusion
culling, Ollama stream interception, cartography export. **→ new "Performance"
slice (later; Java port where feasible, worker-offload the heavy math).**

---

## Consolidated priority order (nothing deleted — everything above is the backlog)

1. ~~§1 backwards text~~ ✅ DONE (`0a96fa3`)
2. ~~§2 VR ↔ 2D toggle~~ ✅ DONE (`160b4b9`)
3. §18 gist wall + new entries — **explicit ask**
4. §4 avatars (Phase B1→B2→B3)
5. §8 engine TOOLS execution loop — **biggest "not hooked in" gap**
6. §7 chat priority queue (user chat never waits behind autonomous)
7. ~~§3 portal polish: paired colors~~ ✅ DONE (TASK_0001, `PortalTheme.java`) — conduits + Alcubierre jump still open (→ §3)
8. §6 spring-damper camera + astral walk
9. §5 sky/outside streaming + local weather
10. §13 editor: syntax highlight + undo/redo + multi-file
11. §11 per-day chat logs → private repo
12. §14 memory/HDD fencing
13. §15 jpackage installer
14. §16 USB backup
15. §17 sims1337 multiplayer contract + fallback
16. §12 email + morning digest (blocked on Chris's email creds)
17. §19 spatial-memory + performance slices (embedding-backed, later)

---

*This file is ADD-only. When a step ships, mark it DONE with a commit hash — never
erase a past line. Kernel + local agent both update this single index.*

---

## 20. Architecture — "byte-code sectors as blocks" (Lego assembly)

**Directive (`[chat]` 2026-08-23):** "that's how bloom works — we keep byte code
sectors as blocks, then add the blocks like Lego on request. Soon we get enough
blocks we can populate anything in the game in real time and be like Neo."

**Meaning:** every effect / geometry / room / book / portal is a self-contained
**BLOCK** (a "byte-code sector") = stable id + definition + payload. The world is
assembled from blocks at runtime, never hardcoded. The bloom effect is already ONE
such block (scene FBO → bright pass → blur H/V ping-pong → composite). The more
blocks in the registry, the more the game populates in real time — no restart.

**Maps to existing code:**
- **Bloom** = the canonical seed block (`render/BloomEffect.java`).
- **LiveUpdateManager** = the existing "add a block on request" loop (rooms/books/repos).
- **Phase 10 (Live Everything)** = the bytecode/scripting path (GraalVM/JS) so *logic*
  blocks hot-reload — Java can't hot-swap classes, so scripting is the real path.
- **Phase 5.2 (block building, Lego-like)** = attribute-driven blocks morphing into materials.
- **`wip/effectmodule` branch** = my earlier proto-block system (`EffectManager` +
  pluggable `EffectModule`: Bloom/DistrictExpansion/TemporalDistortion) — the Update
  Method pattern, which is exactly "blocks assembled on request". Kept on a branch so
  it's not lost, not force-merged over the agent's `render/BloomEffect.java`.

**Concrete next step (proposed):** a `Block` interface + `BlockRegistry` +
`BlockAssembler`. Each block = { id, definition, payload }. Registry accumulates;
assembler composes into the world. Seed with 3 real blocks: **bloom** (render
sector), **portal** (teleporter sector), **text** (font sector). Then grow the
registry toward "enough blocks to populate anything."

**Status:** `NOT-STARTED` (vision only; bloom + live-add are the two existing seeds).
