# MindPalace — BUILD PLAN (for the local DeepSeek agent)

*Written by Kernel/Aegis 2026-08-17. Work top-down; every step has a headless verify — you have no 360 view, so prove each change with compile + unit test + `--test` mode output, never by eyeballing the VR render.*

## PHASE A — The 3 pain points (do these FIRST)

### A1. CR / text-display fix + English everywhere
**Problem:** repo names and file text render with `\r` garbage (CR chars from GitHub's `\r\n` line endings hit the 96-glyph bitmap atlas → boxes/garbage).
**Do:**
1. New `util/TextSanitizer.java`: `stripCR(String)` (replace `\r\n`→`\n`, drop bare `\r`), `asciiSafe(String)` (map non-atlas chars to `?` or skip).
2. Apply at EVERY read boundary: `GitHubClient` file contents, `RepoScanner` repo names, `Book` load, door label text, `HUD` strings, `BookEditor` edit buffer, `FontRenderer` draw calls (skip `\r` glyphs).
3. English pass: make sure all UI strings are English literals (no locale-dependent output).
**Verify (no VR):** JUnit test `TextSanitizerTest` (CRLF/CR/LF cases); headless world-dump (see A2) asserts zero `\r` bytes in every rendered label.

### A2. Non-VR test mode (your eyes)
**Problem:** you can't see the 3D view, so you need a machine-checkable render.
**Do:**
1. New `--test` / `--headless` CLI flag in `Main`: fixed camera, run N frames, dump world state JSON (`rooms/books/labels`), write 1-2 screenshot PNGs via `glReadPixels`, exit 0.
2. `GameEngine` skips input/audio when headless; `WorldBuilder` loads the same data.
**Verify:** `java -jar target/mindpalace.jar --test` exits 0, prints world JSON, PNGs exist. Put this in CI later (B9).

### A3. Books → game code editor with qwen2.5:0.5b suggestions
**Problem:** books must open as real files, editable, with an SLM suggesting code.
**Do:**
1. `ModelConfig`: add `CODE_MODEL = "qwen2.5:0.5b"` (already the critic — reuse for suggestions).
2. `BookEditor` SUGGEST mode: prompt = `file path + current content + line under cursor` → `OllamaClient.chat(CODE_MODEL, ...)` → insert suggestion at cursor. Add "suggest at cursor" (Alt+S) in addition to `:s` full-file suggest.
3. Books open as files: `Book.getFilePath()` must be the REAL local path when the repo is cloned locally; `Desktop.open(file)` button in editor; Save writes BOTH local file (Files.write) and GitHub (`GitHubClient` PUT contents).
4. Wire Phase 1.10 (see B1) so suggestions use ModelLifespan context + RAG, not a bare stateless prompt.
**Verify:** JUnit `SuggestionPromptTest` (prompt structure); mock `OllamaClient` for deterministic test; file round-trip test on a temp repo file (read→edit→save→GitHub PUT).

### A4. Live updates — GTA Vice City style (mostly exists, polish it)
**Problem:** updates must appear with animation, no restart. `LiveUpdateManager` already does 15s poll + construction animation + `addRoom()` — extend it.
**Do:**
1. Delta detection beyond new rooms: book CONTENT edits, new books, new floors → fire `onNewBook`/`onNewFloor`.
2. HUD toast ("⬡ NEW REPO: X — Floor 3") + sound cue via existing `AudioEngine`.
3. Ensure construction animation never blocks input (run on its own thread/timer).
**Verify:** JUnit with a fake `GitHubClient` that returns a new repo on 2nd poll → assert `onNewRoom` fires exactly once; log toast text headlessly.

## PHASE B — Full game (ROADMAP gaps, in order)

- **B1. Phase 1.10 Model Lifespan for Code** — feed real book content into ModelLifespan; code-drift correctors (re-anchor on actual file when it hallucinates APIs); RAG over code snippets; check the never-make-code-twice DB before proposing. This is the brain behind A3.
- **B2. Book content preview on hover (2.1)** + player stats (2.3: repos visited, crystals collected).
- **B3. Elevator animation between floors (2.4)** + organize floors by language category.
- **B4. Lab functions (3.2)** — "Create Repo" terminal (POST /user/repos + spawn room), fork station, code-analysis microscope, CI/CD pipeline viz.
- **B5. Courtyard safe (4.2)** — AES-256 combo lock, visual dial.
- **B6. Installer polish (9)** — `src/installer/mindpalace.iss` exists: jpackage build, auto-updater (reuse LiveUpdateManager pattern), settings persistence (settings.json), real textures, gamepad, OpenXR VR later.
- **B7. Multiplayer gateway (5.2) then full multiplayer (8)** — QR portal, WebSocket sync. Biggest lift; do last.
- **B8. Curie the cat (Phase 6)** — separate repo `chrisalunlloyd2-sudo/Curie`, calico model, roaming AI. Good morale + separate deliverable.
- **B9. Tech debt** — GitHub Actions CI (compile + headless `--test` on every push), checkpoint releases, test suite growth.

## Definition of done for each step
1. `mvn -q compile` passes.
2. New/updated JUnit tests pass (`mvn -q test`).
3. `--test` mode output shows the change (world dump / PNG / log line).
4. Commit message names the step (e.g. `A3: book editor qwen suggestions`).
5. Never delete existing code — only add/modify. Push to origin/main.
