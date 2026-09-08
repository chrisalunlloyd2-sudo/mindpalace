---
name: Bug report
about: Something in the palace is broken
labels: ["bug"]
---
**Priority** (replace this line, keep exactly one): `high` (blocks a milestone / crash-class) | `medium` (next milestone) | `low` (polish)

**Area** (where the FIX lands — see docs/LABELS.md): `area/ux` | `area/perf` | `area/infra` | `area/world` | `area/agents` | `area/audio` | `area/docs`

**What happened** (one line):

**Expected behavior** (what you believe SHOULD happen — cite the doc/issue if known):

**Where** (room/waypoint/menu, or "outside world"):

**Reproduction** (the smaller the repro, the faster the fix):
1. State: fresh install / running session / demo mode / after N cycles
2. Exact keys or flags: (e.g. `--demo`, press `[` twice, walk forward)
3. What you observed vs what you expected, per step

**Evidence** (attach — this is what makes bugs fixable):
- Console log tail (`game_console.log`, last ~50 lines)
- If visual: an E2E shot (`--e2e` waypoint) or screenshot
- Selftest RESULT line (`--selftest`)

**Build info**: commit SHA + frozen-jar timestamp

**Checklist**:
- [ ] Reproduced with the frozen jar (`mindpalace-live.jar`), not target/
- [ ] No orphan java instances (`scripts/bot_swarm.sh sweep` first)
- [ ] Not one of the two known E2E artifacts (07 crystal timing, 12 door prompt)
- [ ] Read DEV_SETUP.md gotchas (model-gate contention, MSYS paths)
