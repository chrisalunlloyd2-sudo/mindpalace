# TEST_PLAN.md — what to test, and how to tell us

> For testers. Every row is a real, shipped surface (step-log #9 has the
> evidence), with the exact way to exercise it and what "broken" looks
> like. File findings via the [bug template](../.github/ISSUE_TEMPLATE/bug_report.md)
> — its checklist routes past the known traps automatically.

## The 10-minute smoke pass (run this first, every fresh install)

| # | Surface | Do this | Broken looks like |
|---|---------|---------|-------------------|
| 1 | **Boot (live)** | `java -jar mindpalace-live.jar` → walk | crash before "World built"; > 2 min boot |
| 2 | **Demo boot** | `--demo` — no GitHub account needed | manifest error in console; rooms = 0 |
| 3 | **Doors** | Enter near 5 doors | door doesn't slide; teleport wrong floor |
| 4 | **Books** | open, edit, save, close 3 books | content mismatch; editor won't close |
| 5 | **Time slider** | inside a git repo: `[` then `]` ×5 | console shows no commit; editor shows wrong history |
| 6 | **Doors heatmap** | look across a hallway | all frames identical color (activity tiers gone) |
| 7 | **Search** | `/palace` + Enter | no jump; wrong door |
| 8 | **FPS overlay** | F4 indoors + outside | no readout; wild swings while standing still |
| 9 | **Rotor + audio** | stand in courtyard 70s | no tick/chime/bell; wind silent in forest |
| 10 | **Collision** | try walking through the mansion wall | you pass through (the pre-H13b bug class) |
| 11 | **Teleporters** | pad → list → confirm | stuck on pad; wrong destination |
| 12 | **Sequencer** | B toggle, steps, tempo | no sound; UI stuck |

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