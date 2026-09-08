---
name: Feature request
about: A new room, tool, sound, or system for the palace
labels: ["enhancement"]
---
**Priority** (maintainer triages): `high` | `medium` | `low`

**Area** (pick exactly one — see docs/LABELS.md): `area/ux` | `area/perf` | `area/infra` | `area/world` | `area/agents` | `area/audio` | `area/docs`

**One-line pitch**:

**Which module does this belong in?** (ARCHITECTURE.md's "where do I add X" table):

**Which existing pattern does it copy?** (the table's "pattern to copy" column):

**Gameplay impact** — what will the player DO differently:

**Performance budget check** (docs/PERFORMANCE.md):
- [ ] ≤ 40 new instanced draw calls (if geometry)
- [ ] No new textures (palette/vertex-color)
- [ ] Deterministic (DeterministicSeed) if procedural
- [ ] New outdoor geometry has an `addBox` collider

**Ship plan**: which NEXT_100_STEPS.md step number, or new?

**Evidence you'll attach**: selftest RESULT + E2E waypoint (if visible)
