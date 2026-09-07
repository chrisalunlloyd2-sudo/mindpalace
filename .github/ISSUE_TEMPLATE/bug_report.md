---
name: Bug report
about: Something in the palace is broken
labels: bug
---
**What happened** (one line):

**Where** (room/waypoint/menu, or "outside world"):

**Steps to reproduce**:
1.
2.

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
