# TEST_PLAN.md — what to test, and how to tell us

> For testers. Every row is a real, shipped surface (step-log #9 has the
> evidence), with the exact way to exercise it and what "broken" looks
> like. File findings via the [bug template](../.github/ISSUE_TEMPLATE/bug_report.md)
> — its checklist routes past the known traps automatically.

## The 10-minute smoke pass (run this first, every fresh install)

| # | Surface | Do this | Expected | Broken looks like |
|---|---------|---------|----------|-------------------|
| 1 | Boot (live) | `java -jar mindpalace-live.jar` → walk | console: `World built: 9 hallways, ~145 rooms` → `Agents started`; walking within 2 min | crash before "World built"; > 2 min boot |
| 2 | Demo boot | `--demo` (no GitHub account needed) | `Demo fixtures loaded: 12 rooms (no auth, no network)`; NO remote-merge lines even with a stored token (zero-network contract, fixed a612fb3) | manifest error; room count ≠ 12; `[GitHub] Authenticated` followed by a merge |
| 3 | Doors | Enter near 5 doors | door slides up; console `[ENTER] <room label>`; interior has 3 bookcase walls | door doesn't slide; wrong room label |
| 4 | Books | open, edit, save, close 3 books | editor opens with the file's real content; save writes through (`[Deploy]` line); close restores view | content mismatch; editor won't close; no save line |
| 5 | Time slider | inside a git repo: `[` then `]` ×5 | one `[TimeMachine] <repo> @ <sha> <when> — <subject>` line per press; `]` past the newest prints `PRESENT (live files)`; editor at depth > 0 shows that commit's text | no console lines; editor content unchanged while scrubbed |
| 6 | Doors heatmap | look across a hallway | frames differ by activity: 30d-active repos read green/amber/red; inert repos stay cyan/pink | all frames identical color |
| 7 | Search | `/palace` + Enter | camera snaps beside the matching door; `[Search] Jumped to <label>`; unknown query prints `No repo matching '...'` | no jump; wrong door; no console line |
| 8 | FPS overlay | F4 indoors, then outside | `[DEBUG] <n> FPS (rooms, halls)` billboard in front of camera; steady reading while standing still | no readout; wild swings standing still |
| 9 | Rotor + audio | stand in courtyard 70s | tick every 1s, chime every 8s, bell every 64s; wind rises walking toward forest | no tick; chime/bell never arrive; wind silent in forest |
| 10 | Collision | try walking through the mansion wall | you slide along walls; houses enterable only via the front doorway strip | you pass through any wall (pre-H13b class) |
| 11 | Teleporters | pad → Enter → list → Enter | `[TELEPORT] Destination picker open` → `[TELEPORT] -> Pad N`; arrive on the chosen floor | stuck on pad; wrong floor; picker invisible |
| 12 | Sequencer | B toggle, toggle steps, change tempo | grid toggles; steps trigger audible hits; tempo change audible immediately | no sound; UI stuck |

## Known-good baseline (don't file these)

- E2E waypoints `07_todo_crystals` and `12_door_prompt` capture dark —
  baseline artifacts, A/B-proven (step-log). Everything else must be lit.
- Console shows `?` where UTF-8 arrows belong — codepage artifact, parsers handle it.
- Selftest prints `40 passed, 0 failed` — if yours differs, that IS worth filing.

## Environment matrix (what we especially need)

| Surface | Have coverage | Need |
|---------|--------------|------|
| Intel HD 510 (dev box) | yes | — |
| Other iGPU (AMD/Vega) | no | **boot + FPS numbers** |
| Discrete GPU (N/A) | no | confirm 60fps budget on medium repos |
| Windows 10 vs 11 | 10 only | 11 boot check |
| Demo mode on a clean machine | CI only | a human run of `--demo` |

## Reporting rules

1. **Evidence beats description**: console tail + RESULT line, or an E2E
   shot — the template asks for exactly these.
2. One report per symptom (duplicates get merged by maintainers).
3. If you measured an FPS/latency number, include machine spec — the
   budgets are machine-scoped (PERFORMANCE.md rules).
4. Testers who file 3+ accepted reports get `testers` credit in the
   release notes — say so in the issue and we'll add you.