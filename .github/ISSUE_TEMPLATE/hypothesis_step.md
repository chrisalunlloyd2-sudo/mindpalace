---
name: Hypothesis step (Autonomous Finishing Program)
about: Propose a step for the 50/150-step program
labels: autonomous-program
---
**Step number + title** (e.g. H14 / step 66: bloom presets):

**Phase** (A cognition / B world / C bots / D GitHub / E economy / F flash / G modules):

**Hypothesis** (falsifiable — what changes, what proves it worked):

**Files touched** (exact paths, from ARCHITECTURE.md's table):

**Verify plan** (machine-checked, per CONTRIBUTING.md):
- selftest: which check (existing or new)
- E2E: which waypoint proves it visible
- metric: which scout_bot number moves

**Cascade**: `bash scripts/cascade_dev.sh <id>` will ship it with evidence
to step-log issue #9.

**Checklist**:
- [ ] Checked NEXT_100_STEPS.md + git log (never-twice)
- [ ] Verified the planning docs aren't stale (grep the actual code first)
