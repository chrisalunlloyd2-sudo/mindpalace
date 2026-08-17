# MindPalace — BUILD PLAN (for the local DeepSeek agent)

*Written by Kernel/Aegis 2026-08-17. Work top-down. Verification = read the code, compile, run JUnit. NO screenshots for debugging — screenshots are an automated test artifact only (Phase C, 12pm-4pm window).*

---

## ROOT CAUSES — why text is garbage and books don't work (already analysed)

| # | Bug | Evidence | Fix |
|---|-----|----------|-----|
| R1 | Font atlas can't address CR/box-chars | `FontRenderer` atlas = 95 glyphs (ASCII 32..126), `glyphIndex = c - 32` (drawText line ~191). `\r`(13) and `\n`(10) → NEGATIVE index; `╔║═│•` (>126) → out of bounds → garbage glyphs everywhere | Guard: `if (c < 32 \|\| c > 126) c = '?'`; strip `\r` before draw |
| R2 | CRLF never sanitized | `GitHubClient.fetchFileContent` uses `Accept: application/vnd.github.v3.raw` → returns raw bytes, CRLF intact. `BookEditor.printContent` splits on `\n` leaving `\r` on every line | TextSanitizer at every read boundary |
| R3 | Editor text goes to System.out, NOT the 3D panel | `BookEditor.render()` draws only cubes (panel + border). The terminal buffer (`getTerminal()`) is never drawn in-world; `println()` only appends to buffer + stdout | Render terminal text as quads on the panel (reuse FontRenderer billboard mode) |
| R4 | `bookEditor.open()` never wired | `GameEngine:486` book-click detection exists (`findBookInSights` + raycastBooks + rayAABB) but the pick result is never passed to `bookEditor.open()` — books can't open | Connect pick → `bookEditor.open(...)` |
| R5 | Raycast index math ≠ visual placement | `raycastBooks` maps hit → `startIdx + row*(perWall/3) + b` assuming uniform row-major layout; `WorldBuilder` places books grouped BY LANGUAGE with divider slots and 15-book cap → mismatch, wrong book or none | Replace index math with direct position lookup: iterate placed books, compare against `book.getWorldPosition()` AABB |
| R6 | `actionSuggest()` is fake | Prints hardcoded generic tips ("Add docstring", "Extract magic numbers"); never calls `OllamaClient`. `:a` (apply) not even handled (only `:q` in SUGGEST mode) | Real call: send file path + current content + cursor line to `ModelConfig.CODE_MODEL` (qwen2.5:0.5b), render suggestions, wire `:a` to insert |
| R7 | Repo discovery depends on hardcoded path | `RepoMapper.AIGEN_SYS = "C:/Users/viper/AIGEN_SYS/repos"` — if that path doesn't exist on this machine → 0 local rooms; remote merge needs PAT from Windows Credential Manager | Make paths configurable (settings.json + env var), add `--repos <dir>` CLI flag; verify token path |

---

## PHASE A — Prototype the 5 fixes (do FIRST, no screenshots)

Each step: `mvn -q compile` + new JUnit test + commit. Proof is the test, not a render.

- **A1. FontRenderer glyph guard (R1):** in `drawText`, clamp `c` to 32..126 (else `?`). Add `TextSanitizer.stripCR()` applied before drawing. Test: glyphIndex mapping for `\r`, `\n`, `╔`, `é` all → safe char.
- **A2. TextSanitizer (R2):** new `util/TextSanitizer.java`: `stripCR` (`\r\n`→`\n`, drop bare `\r`), `asciiSafe` (map box-drawing `╔║══╠╣╦╩╬─│┌┐└┘├┤┬┴•·` → `+-=|*`, non-ASCII → `?`). Apply in: `GitHubClient.fetchFileContent`, `Book` content set, `RepoMapper` repo names, door labels, HUD, `BookEditor` buffer. Test: CRLF sample → clean.
- **A3. Wire book open (R4+R5):** in `GameEngine` left-click handler, pass the picked book to `bookEditor.open(...)`; fix `raycastBooks` to use `book.getWorldPosition()` AABB list instead of index math. Test: unit-test raycast against a placed book's world position.
- **A4. Editor text in-world (R3):** render the terminal buffer onto the panel with FontRenderer billboard mode (lines wrapped to PANEL_WIDTH), scrollable. Keep System.out as a mirror. Test: terminal buffer → drawn line count matches.
- **A5. Real qwen suggestions (R6):** `actionSuggest()` → build prompt (file path + language + first 200 lines + "suggest concrete code improvements"), call `OllamaClient.chat(ModelConfig.CODE_MODEL, ...)`, show numbered suggestions; `:a <n>` inserts at cursor. Test: mock OllamaClient; prompt structure assertion; insert path.

## PHASE B — Full game (ROADMAP gaps, in order)

- B1. Phase 1.10 Model Lifespan for Code (feeds A5): book content → ModelLifespan context; code-drift correctors; RAG over code; never-make-code-twice check before proposing.
- B2. Book content preview on hover (2.1) + player stats (2.3).
- B3. Elevator animation (2.4) + floors by language category.
- B4. Lab: create-repo terminal, fork station, code-analysis microscope, CI/CD viz (3.2).
- B5. Courtyard safe, AES-256 combo (4.2).
- B6. Installer polish (9): jpackage, auto-updater (reuse LiveUpdateManager), settings persistence (R7's settings.json), gamepad, OpenXR later.
- B7. Multiplayer gateway (5.2) then full multiplayer (8).
- B8. Curie the cat (Phase 6) — separate repo `chrisalunlloyd2-sudo/Curie`.
- B9. Tech debt: GitHub Actions CI (compile + JUnit on push), checkpoint releases.

## PHASE C — Auto-drive + screenshot testing (AFTER Phase A prototypes)

**Policy (Chris, 2026-08-17):** screenshots ARE wanted — autonomous, lots of them, auto-driving the player. But ONLY **12pm–4pm local**, and NEVER when Chris is home/typing (privacy — screenshots of music player/Facebook/browsers are a credential-leak risk). No captures outside the window, no captures of non-game windows.

- **C1. Auto-drive harness:** new `--autodrive` mode: scripted waypoints (walk room→room, open doors, face bookcases, open books), deterministic seed. Runs the same code paths as a human player (input injection at the Input layer, not teleporting).
- **C2. Screenshot capture:** `--autodrive --screenshots <dir>`: capture a PNG every N frames + on each milestone (door opened, book opened, suggestion rendered) via `glReadPixels` → PNG (stb_image_write or manual). Save to `screenshots/` with timestamp + room name. LOCAL ONLY (never uploaded).
- **C3. Window guard (privacy):** before any capture, verify the GLFW window is focused and fullscreen/foreground; skip if the app is not the active window (Chris is doing something else). Capture ONLY the game framebuffer — never the desktop.
- **C4. Schedule:** a scheduler (or the fleet heartbeat) triggers `--autodrive` runs only 12:00–16:00 local. Outside the window: no captures at all. Log skipped runs (window guard) to `state/autodrive_log.jsonl`.
- **C5. Report:** after each run, append `{ts, rooms_visited, books_opened, suggestions_shown, screenshots_written, anomalies}` to `state/autodrive_log.jsonl`; surface in the daily digest. Reuse the fleet gist for cross-machine status (screenshots stay local).
- **C6. Apply to all game repos:** same harness pattern for any repo with a game (mindpalace, VR walkthroughs, etc.) — one shared `autodrive` module per repo, same 12-4 window, same privacy guard.

## DAILY RHYTHM (Chris's working day — the fleet should match it)

| Window (local) | What happens |
|---|---|
| 12pm–4pm | Autonomous: screenshots + auto-drive testing (Phase C) |
| 4pm–6pm | Chris + Aegis/Kernel: iron out deliverables, updates |
| 6pm–10pm | Local agent applies edits (the plan steps) |
| 10pm–12am | Dreaming + gossip, local agent work, contracts (BDI dream cycle + fleet sync) |

## Definition of done for each step
1. `mvn -q compile` passes.
2. New/updated JUnit tests pass (`mvn -q test`).
3. Step's log line appears in the appropriate state log.
4. Commit message names the step (e.g. `A3: wire book open`).
5. Never delete existing code — only add/modify. Push to origin/main.
