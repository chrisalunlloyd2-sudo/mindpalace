# HEX_WATER — QUAL-3 reusable hex lattice (t_ddf78c8d)

Status: **landed, proven**. Files: `src/main/java/com/mindpalace/world/HexCoord.java`,
`HexWaterGrid.java`, `HexWaterSelfCheck.java` (new), wired into
`GameEngine.runSelfTest()` after the MP-028 particle check.

This class is the shared substrate for QUAL-4 (t_9bcb8660): when
`render.hexWater` is enabled, lake, NashFountain pool and planet water are
rebuilt as hex tile meshes from THIS grid, so all water is seam-gap-free and
z-fight-free by construction instead of by per-feature tuning.

## 1. The lattice (pointy-top, axial (q,r))

Same geometry family as the live lake (STEP 92b pointy-top):

```
corner k of tile (q,r):   ring angle 30° + 60°·k   (vertex along +Z)
centre (q,r) in world:    x = xPitch·q + xPitch/2·r + originX
                          z = zPitch·r + originZ        (zPitch = 1.5·R)
tile area                 (3√3/2)·R²                    (AREA_K, double)
```

| const | value | source |
|---|---|---|
| `DEFAULT_TILE_R` | 1.2 (area 3.7412 u²) | live lake lattice pitch |
| `xPitch` | √3·R = 2.0784… | `SQRT3 * tileR` (double-prec product) |
| `zPitch` | 1.8 (1.5·R) | float product — **not** bitwise 1.8f, see §6 |
| `halfX` `halfZ` | xPitch/2, R/2 | half-unit lattice (exact: exponent-only) |
| `DM[]` `{1,0,-1,-1,0,1}` / `DN[]` {1,2,1,-1,-2,-1}` | corner offsets on the half-unit lattice | matches lake ring order |
| `NQ[]{1,1,0,-1,-1,0}` / `NR[]{0,-1,-1,0,1,1}` | the 6 axial neighbours | patch-fixed slot 3 |

The integer lattice is the weld: `corner k` lives at half-units
`(m,n) = (2q+r+DM[k], 3r+DN[k])`; world = `(originX + halfX·m, originZ + halfZ·n)`.
Shared corners between neighbouring tiles are the SAME node coords → shared
indices in the mesh → zero gaps/overlaps/z-fight between hexes, structurally.

## 2. Regions, ids, phases

- `setRegionRect(ox,oz,w,h)` — tile in iff its centre is within `R` of the
  rect (overscan-under-the-bank).
- `setRegionDisc(ox,oz,radius)` — tile in iff `centreDist ≤ radius + R`
  (same overscan rule; scan window `reach = radius+R`, exact in/out test).
  Shore discs extend under the bank model so the bank trim can hide the fringe.
- `idOf(q,r) = (q & 0xFFFF) | ((r & 0xFFFF) << 16)` — injective for
  |q|,|r| ≤ 32767; `(-1,-1) → 0xFFFFFFFF`.
- `phaseOf(q,r)` — splitmix64 finalizer on id, top 24 bits → [0,1):
  per-tile stable phase for QUAL-4's cosine bob (no neighbours pulsing
  together).
- `tileAt(x,z)` — exact (no division trick), allocates zero; `lookup(q,r)`
  requires a filled region (fresh grid returns nothing).
- `withTileArea(area)` → `sqrt(area/AREA_K)`.

## 3. Mesh builders

- `buildCapMesh()` — flat top-cap over all built tiles, verts welded by node
  lattice, ring winding identical to STEP 92b's proven cap order, one shared
  uv space (`uvScale` default 2R, world units per uv unit).
- `buildPondMesh(depth)` — cap section (bitwise the cap build) + bank skirt:
  one wall quad (4 verts, 2 outward-wound tris) per boundary EDGE. Disc(0,0,9):
  85 tiles, 289 cap verts (1530 cap idx = 18/tile·85), 66 boundary edges →
  4424 pond floats, 1926 pond idx = 1530 + 6·66. Winding: (aBot,aTop,bTop) has
  geometric normal (dz,−dx) = outward, same as per-vertex normals, so backface
  culling keeps both the surface and the skirt reading correctly.
- Boundary census in the selfcheck independently recomputes each edge's
  sharing count: interior edges are shared by exactly 2 built tiles, shore
  edges by exactly 1 (262 unique edges: 66×count-1 + 222×count-2, sum 510 = 6·85).

## 4. Zero-allocation contract

Query paths — `tileAt`, `lookup`, `fillNeighbors`, `phaseOf`, `idOf` — and the
selfcheck's own loops allocate ZERO. Measured: `com.sun.management`
per-thread allocated bytes across 300 warmup + 3000 hot query loops (MP-028
pattern); 0 bytes required for PASS. This is a hard constraint for the 2-core
Intel HD 510 budget: water queries run hot inside the QUAL-4 per-tile draw loop.

## 5. Selfcheck (GL-free, deterministic)

`HexWaterSelfCheck.run()` returns "" on pass, else the first failing check's
name; `main` prints `PASS HexWaterSelfCheck` / `FAIL ... -- <check>` and exits
0/1. Sections: default layout facts (near-compares 1e-6), corner geometry,
region fills vs a naive padded-superset census (exact-rule parity), exact
`tileAt`, neighbour symmetry (`NQ/NR` ↔ mirror of −NQ/−NR), stable id/phase,
sharing fixture (node (1,−1) is simultaneously (0,0) corner 5, (1,0) corner 3,
(1,−1) corner 1), cap mesh welds + winding + uv, pond skirt normals + intrinsic
winding, translation-by-lattice-pitch fill sanity, measured zero-alloc.
Wired into `GameEngine.runSelfTest()` (headless `--selftest` mode, no GL at all).

## 6. Decisions & lessons (the "why" trail)

- **Integer half-unit lattice over shared vertices** — the seam-gap-z-fight
  fix is structural (one vertex per node) rather than epsilon-tolerant; every
  tile's corner is literally the same float produced by the same one product
  `halfX·m`, so welds are bitwise, not `near()`.
- **Overscan shore** (`+R` reach on both region rules) mirrors how the live
  lake trims under banks; QUAL-4 can bank-trim the fringe without holes.
- **near() where floats are derived, == only where bits are exact**: `zPitch`
  is `1.5f * 1.2f` = 1.800000072… ≠ literal `1.8f` = 1.799999952… (the
  selfcheck's own first fast-check bug — a float-identity check that could
  never pass). `halfZ = 1.2f/2f = 0.6f` is exponent-only, so exact compares
  stay legal there. Rule: derived float products get `near(x, expected, 1e-6)`.
- **Bitwise set-equality across float-shifted centres is over-assertion**: the
  disc-shift check originally demanded the same tile set after
  `setRegionDisc` at a different centre; tiles exactly ON the `reach`
  threshold legitimately flip under float rounding. The correct property —
  every shifted tile self-identifies via `tileAt` at its own centre, and the
  census matches the exact rule recomputed at the shifted centre — is what
  ships.
- **Fill-before-lookup**: a fresh grid has no region; fixtures must call a
  `setRegion*` first (the selfcheck hit this — `lookup` on an empty grid
  silently returns nothing).
- **`buildCapMesh()` populates only cap arrays**; `buildPondMesh()` writes
  separate pond arrays. Pond-vs-cap accounting checks must build both (the
  wall-search check needed this baseline).
- **Census must mirror the builder bit-for-bit in float order** (same scan
  bounds as `Math.ceil/floor` windows, same double accumulators for
  `reach²`, `R²`), else parity is luck, not proof.
- **Fast-check loop = real gate**: running the standalone `main` via javac
  immediately after each edit caught 7 concrete defects in one session
  (zPitch identity, missing fill, over-asserted shift equality, wrong idOf
  expected constant, fixture slot typos ×2, missing cap baseline, wallSlot
  float-index/vertex-index unit confusion). Every failure was a real,
  evidence-backed fix — no assertion was loosened except the over-asserted
  shift equality, which was replaced by the correct physical property.

## 7. Windows fast-check recipe (this repo, git-bash)

This shell passes POSIX paths to java tools UNCONVERTED (no MSYS arg
translation) — javac sees drive-root `C:\tmp\...`, jshell can't find `/tmp`
scripts at all (probe files must live in AppData\Local\Temp, which is
/tmp here by mount, and jshell still fails on class-path resolution — use
single-source `java Prode.java` instead).

```
"/c/Program Files/Java/jdk-17/bin/javac" -d C:/tmp/hwchk \
  src/main/java/com/mindpalace/world/HexCoord.java \
  src/main/java/com/mindpalace/world/HexWaterGrid.java \
  src/main/java/com/mindpalace/world/HexWaterSelfCheck.java
"/c/Program Files/Java/jdk-17/bin/java" -cp 'C:\tmp\hwchk' com.mindpalace.world.HexWaterSelfCheck
```

The three world files compile standalone (no engine/GL import) — hex math is
GL-free by construction, which is exactly why the selftest can headless-prove
the lattice.

## 8. QUAL-4 integration (t_9bcb8660, MP-025) — flag `render.hexWater`

Landed 2026-10-19 behind `world/HexWaterFlags.HEX_WATER`
(`-Dmindpalace.render.hexWater`, OFF default = legacy paths byte-identical):

- `HexWaterSites` — per-site glue: `LAKE_HR=2.4f` (pitch ≡ legacy
  √3·0.5·hr / 0.75·hr), sand literals (ellipse 34.2/24.2, colors
  0.62/0.55/0.40) pinned to the legacy `renderLake` scan exactly, motion
  helpers `lakeWave`/`lakeBob`/`lakeShimmer`/`poolShimmer` (GL-free, unit
  checked by `HexWaterIntegrationSelfCheck`).
- `OutsideWorld.renderLakeHex/drawLakeCell` — flag-gated grid-driven tile
  loop (v4–v7 cell color math verbatim in the shared `drawLakeCell`); legacy
  branch reuses the SAME drawer with shimmer exactly `1f` (multiplicative
  no-op) so OFF is byte-identical, and reuses one scratch Vector3f pair.
- `NashFountain` — flag-ON pool = welded 7-tile rosette from
  `HexWaterSites.buildPoolGrid()` built AT THE LATTICE ORIGIN (base offset
  applied at draw time, so the rosette is symmetric whatever the base
  anchor is: corner max 0.346+0.212=0.558 < 0.6 stone inner face; ring-2
  excluded at 0.520 > 0.42); OFF = original square slab byte-identical.
- `HexWaterIntegrationSelfCheck` (GL-free, in `GameEngine.runSelfTest`) —
  flag-gate desync, site-constant pins, pool rosette, basin, wave-identity
  vs the legacy formulas, bob/shimmer motion bounds.
- MP-026 deferral: full per-tile time/view color field (v4–v7 depth tint,
  specular) is a follow-up card; v1 ships tile geometry + bob + stable
  per-tile phase shimmer.