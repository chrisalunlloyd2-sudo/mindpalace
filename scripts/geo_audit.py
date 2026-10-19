#!/usr/bin/env python
"""geo_audit.py — GL-free geometry audit over GeoAudit dumps (QUAL-1).

Reads <dir>/<label>.jsonl blocks written by GeoAudit (one file per waypoint,
header {"wp":"<label>"} then one JSON record per emitted primitive):
    {"k":"cube","c":[x,y,z],"s":[sx,sy,sz],"yaw":<float|null>,"tex":<int|null>,"rgb":[r,g,b]|null}
    {"k":"line","m":[x,y,z],"d":[dx,dy,dz],"t":th,"len":L,"rgb":[...]}
    {"k":"cs","<kind>":"count"}   — collapse trailer per (kind) bucket

Checks (all data-level, no GL context):
  A  degenerate   — zero/near-zero scale on any axis; NaN/Inf coords
  B  underground  — center below y=0 (floor plane) on world-geometry kinds
  C  floaters     — cube bottom far above y=0 OR fully below y top of hallway
                    gap (corridor floor gap = 5.0 m: HALLWAY_HEIGHT + 1.0)
  D  stacking     — exact-duplicate cube pairs (same kind, eps on all coords)
  E  walls        — hallway wall segments: expect contiguous runs along the
                    corridor axis; gaps within [gap_min,gap_max] = missing-wall
                    candidates, gaps > max_gap = intentional (door holes)
  F  spheres      — sphere scale non-uniformity > 3% (squashed world)

Usage:
  python scripts/geo_audit.py <dump-dir> [--report out.md]
  python scripts/geo_audit.py --selftest            # synthetic fixtures
Exit: 0 clean (no defects), 1 defects found, 2 usage/selftest failure.
"""
import json
import math
import os
import sys
from collections import defaultdict, Counter

# World constants mirrored from source (grep'd, not invented):
#  Room.java / CorridorLayout.java / WorldBuilder.java
HALLWAY_WIDTH = 6.0
HALLWAY_HEIGHT = 4.0
GAP_CORRIDOR = HALLWAY_HEIGHT + 1.0   # 5.0 m floor-gap between rooms (WorldBuilder)
WALL_THICKNESS = 0.2                  # Room.WALL_THICKNESS
DEGENERATE_EPS = 0.01                 # meters; anything below = zero-volume
WALL_GAP_MIN = 0.3                    # corridor-axis gap smaller than this ...
WALL_GAP_MAX = 4.0                    # ... or bigger = intentional door/crossing
SPHERE_SQUASH = 0.03                  # 3% non-uniformity tolerance
EPS_POS = 0.001                       # duplicate-position tolerance
FLOATER_MARGIN = 0.05                 # cube bottom above y=0 threshold

WORLD_KINDS = {"cube", "cubeYaw", "cubeColorYaw", "cubeColor", "hologram",
               "sphere", "sphereColor", "hexPrism", "hexPrismColor", "line"}


class Finding:
    __slots__ = ("bucket", "wp", "kind", "detail", "pos")

    def __init__(self, bucket, wp, kind, detail, pos=None):
        self.bucket = bucket
        self.wp = wp
        self.kind = kind
        self.detail = detail
        self.pos = pos

    def line(self):
        p = ""
        if self.pos:
            p = " @ (%.2f, %.2f, %.2f)" % tuple(self.pos)
        return "[%s] %-12s %-14s%s %s" % (self.bucket, self.wp, self.kind, p, self.detail)


def _f(v, default=None):
    if v is None or v == "null":
        return default
    try:
        return float(v)
    except (TypeError, ValueError):
        return default


def _vec(v, n=3):
    if not isinstance(v, list) or len(v) != n:
        return None
    try:
        return [float(x) for x in v]
    except (TypeError, ValueError):
        return None


def parse_dump(dirpath):
    """-> {wp_label: {kind: [records...]}}.

    Java writes ONE file (geo_records.jsonl) with a {"wp":...} header line
    starting each waypoint block; legacy one-file-per-wp dirs also parse.
    """
    dumps = {}
    for fn in sorted(os.listdir(dirpath)):
        if not fn.endswith(".jsonl"):
            continue
        file_wp = fn[:-6]
        buckets = defaultdict(list)
        block_wp = file_wp
        with open(os.path.join(dirpath, fn), encoding="utf-8", errors="replace") as fh:
            for raw in fh:
                raw = raw.strip()
                if not raw:
                    continue
                try:
                    rec = json.loads(raw)
                except json.JSONDecodeError:
                    continue
                if not isinstance(rec, dict):
                    continue
                if "wp" in rec:
                    block_wp = rec["wp"]
                    buckets = buckets if block_wp in dumps else defaultdict(list)
                    dumps[block_wp] = buckets
                    continue
                k = rec.get("k")
                if k == "cs":
                    continue          # trailer embedded in record itself
                if k in WORLD_KINDS:
                    buckets[k].append(rec)
        if buckets and file_wp not in dumps:
            dumps[file_wp] = dict(buckets)
    return {wp: dict(b) for wp, b in dumps.items()}


def check_degenerate(recs, wp, out):
    """A: zero-volume geometry / NaN coords."""
    for rec in recs:
        k = rec.get("k", "?")
        s = _vec(rec.get("s"), 3)
        c = _vec(rec.get("c"), 3)
        loc = c or _vec(rec.get("m"), 3) or [0, 0, 0]
        if s:
            if any(v != v or abs(v) == math.inf for v in s):
                out.append(Finding("A_degenerate", wp, k, "scale has NaN/Inf",
                                   loc))
            elif any(0 < abs(v) < DEGENERATE_EPS for v in s):
                out.append(Finding("A_degenerate", wp, k,
                                   "near-zero scale %s" % s, loc))
            elif any(v == 0 for v in s):
                out.append(Finding("A_degenerate", wp, k, "zero scale %s" % s, loc))
        if c:
            if any(v != v or abs(v) == math.inf for v in c):
                out.append(Finding("A_degenerate", wp, k, "position NaN/Inf", loc))


def check_underground(recs, wp, out):
    """B: world geometry sunk below the floor plane."""
    for rec in recs:
        k = rec.get("k", "?")
        c = _vec(rec.get("c"), 3)
        if not c:
            continue
        s = _vec(rec.get("s"), 3) or [1, 1, 1]
        half = (max(abs(s[1]), 0.5) / 2.0)
        if c[1] + half < -DEGENERATE_EPS:
            out.append(Finding("B_underground", wp, k,
                               "center %.2f (top %.2f) below floor" % (c[1], c[1] + half),
                               c))


def check_floaters(recs, wp, out):
    """C: free-standing props hovering with no support, on the palace ground
    plane only (y 0..HALLWAY_HEIGHT; above that = 2nd storey / ceiling)."""
    for rec in recs:
        k = rec.get("k", "?")
        if k not in ("cube", "cubeColor", "hologram", "hexPrismColor", "hexPrism"):
            continue
        cs = rec.get("cs", "")
        if cs.startswith("OutsideWorld"):
            continue              # terrain sits on its own ground plane; no
                                  # shared y=0 reference in the dump
        c = _vec(rec.get("c"), 3)
        s = _vec(rec.get("s"), 3)
        if not c or not s:
            continue
        bottom = c[1] - abs(s[1]) / 2.0
        if not (FLOATER_MARGIN < bottom <= HALLWAY_HEIGHT + 0.05):
            continue              # floor-standing, or above hallway top
        adims = sorted(abs(v) for v in s)
        if adims[0] <= WALL_THICKNESS * 1.5:
            continue              # thin slab: wall segment / poster / sign
        # flat plate (y-dim smallest): shelf top / roof / tabletop; rests on
        # other geometry by design, not a hovering prop
        if abs(s[1]) < min(abs(s[0]), abs(s[2])):
            continue
        if k == "hologram":
            continue              # holo draws hover intentionally
        out.append(Finding("C_floater", wp, k,
                           "bottom %.2f above floor (size %s) call %s"
                           % (bottom, [round(v, 2) for v in s], cs), c))


def _key(rec, fields, eps):
    p = _vec(rec.get("c"), 3)
    s = _vec(rec.get("s"), 3)
    if not p or not s:
        return None
    key = []
    for v in p + s:
        key.append(int(math.floor(v / eps)))
    return tuple(key)


def check_dups(recs, wp, out):
    """D: exact-stacked duplicates (same kind, position AND size within eps)."""
    seen = {}
    for rec in recs:
        k = rec.get("k", "?")
        key = _key(rec, None, EPS_POS)
        if key is None:
            continue
        sk = (k, key)
        if sk in seen:
            prev = seen[sk]
            out.append(Finding("D_stacking", wp, k,
                               "exact duplicate of earlier cube @ (%.2f,%.2f,%.2f)"
                               % tuple(prev), _vec(rec.get("c"), 3)))
        else:
            seen[sk] = _vec(rec.get("c"), 3)


def check_walls(recs, wp, out):
    """E: hallway wall continuity along the corridor axis.

    Wall segments are thin slabs (min dim ~ WALL_THICKNESS) arranged in runs
    along the long hallway axis. Gaps in [WALL_GAP_MIN, WALL_GAP_MAX] with no
    door-hole evidence are missing-wall candidates.
    """
    for rec in recs:
        pass  # walls are analyzed per-hallway in check_wall_runs


def check_wall_runs(recs, wp, out):
    """E real: cluster thin vertical slabs by wall line, then find gaps.

    A gap is a defect only if no slab spans over it (lintel / header above
    the opening) AND the gap is inside [WALL_GAP_MIN, WALL_GAP_MAX] —
    anything wider is an intentional doorway/crossing by construction.
    """
    slabs = []
    for rec in recs:
        k = rec.get("k", "?")
        if k not in ("cube", "cubeYaw", "cubeColorYaw"):
            continue
        c = _vec(rec.get("c"), 3)
        s = _vec(rec.get("s"), 3)
        if not c or not s:
            continue
        dims = sorted(range(3), key=lambda i: abs(s[i]))
        if abs(s[dims[0]]) > WALL_THICKNESS * 1.5:
            continue                      # not a thin slab
        slabs.append((c, s, dims))
    # group by orientation+line: axis of the thin dim and its position.
    # Wall-run audit wants actual WALL slabs (~0.25 thick), not the thin
    # trim that shares the wall plane (posters 0.04-0.06, signs 0.02-0.04,
    # door-frame 0.1). Segments formed from mixed trim produce fake gaps
    # (a poster-to-poster spacing is not a hole in a wall), so require the
    # run axis extent AND the thin dim to look like wall masonry.
    groups = defaultdict(list)
    for c, s, dims in slabs:
        thin, mid, long_ = dims
        st, sm, sl = abs(s[thin]), abs(s[mid]), abs(s[long_])
        if st < WALL_THICKNESS * 0.5:
            continue                      # trim/poster/sign thin < 0.1
        if sm * sl < 1.2:
            continue                      # small props, not wall panels
        # wall panel: runs tall (h≈3-4) and stretches along the run axis
        if not (sm >= 1.0 or sl >= 3.0):
            continue                      # (0.25,1.2,1.6) door-frame heads kept
        axis_pos = round(c[thin], 2)
        groups[(thin, axis_pos)].append((c, s, long_))
    for (thin, axp), lst in groups.items():
        # run axis = the horizontal axis that is NOT the thin one (walls are
        # tall in y, so the vertical axis is never the run axis)
        horiz = [i for i in (0, 2) if i != thin]
        if not horiz:
            continue
        axis_h = horiz[0]
        spans = sorted((c[axis_h] - abs(s[axis_h]) / 2.0,
                        c[axis_h] + abs(s[axis_h]) / 2.0)
                       for c, s, l_ in lst)
        for i in range(1, len(spans)):
            lo_prev, hi_prev = spans[i - 1]
            lo_cur, hi_cur = spans[i]
            gap = lo_cur - hi_prev
            if not (WALL_GAP_MIN <= gap <= WALL_GAP_MAX):
                continue
            # lintel evidence: another slab on this line spans across the gap
            bridged = any(not (b <= hi_prev or a >= hi_cur)
                          and (b - a) >= gap * 0.6
                          and a <= hi_prev + 0.01 and b >= lo_cur - 0.01
                          for a, b in spans if (a, b) not in
                          ((lo_prev, hi_prev), (lo_cur, hi_cur)))
            if bridged:
                continue              # header/lintel over the opening
            out.append(Finding(
                "E_walls", wp, "wall_run",
                "gap %.2f m on axis-%d line %.2f between %.2f..%.2f "
                "(no door-hole evidence in dump)" % (gap, axis_h, axp,
                                                     hi_prev, lo_cur),
                [axp if axis_h == 0 else (hi_prev + lo_cur) / 2.0,
                 0.0,
                 (hi_prev + lo_cur) / 2.0 if axis_h == 2 else 0.0]))


def check_spheres(recs, wp, out):
    """F: squashed spheres."""
    for rec in recs:
        k = rec.get("k", "?")
        if k not in ("sphere", "sphereColor"):
            continue
        s = _vec(rec.get("s"), 3)
        if not s:
            continue
        mx, mn = max(map(abs, s)), min(map(abs, s))
        if mn > 0 and (mx - mn) / mn > SPHERE_SQUASH:
            out.append(Finding("F_squash", wp, k,
                               "non-uniform scale %s (%.0f%%)"
                               % ([round(v, 3) for v in s], (mx - mn) / mn * 100),
                               _vec(rec.get("c"), 3)))


CHECKS = [
    (check_degenerate, "A_degenerate"),
    (check_underground, "B_underground"),
    (check_floaters, "C_floater"),
    (check_dups, "D_stacking"),
    (check_wall_runs, "E_walls"),
    (check_spheres, "F_squash"),
]


def analyze(dumps):
    out = []
    for wp, buckets in dumps.items():
        recs = [r for kind, rl in buckets.items() for r in rl]
        for fn, _b in CHECKS:
            fn(recs, wp, out)
    return out


def report(dumps, findings, path):
    lines = ["# geo_audit findings", ""]
    lines.append("waypoints analyzed: %d" % len(dumps))
    for wp in sorted(dumps):
        buckets = dumps[wp]
        counts = ", ".join("%s=%d" % (k, len(v)) for k, v in sorted(buckets.items()))
        lines.append("  %-20s %s" % (wp, counts or "(empty)"))
    lines.append("")
    lines.append("findings: %d" % len(findings))
    by = defaultdict(list)
    for f in findings:
        by[f.bucket].append(f)
    for b in sorted(by):
        lines.append("")
        lines.append("## %s (%d)" % (b, len(by[b])))
        for f in by[b][:80]:
            lines.append("  " + f.line())
    with open(path, "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines) + "\n")


# ---------------------------------------------------------------- selftest
def _mk(tmp, wp, recs):
    with open(os.path.join(tmp, wp + ".jsonl"), "w", encoding="utf-8") as fh:
        fh.write(json.dumps({"wp": wp}) + "\n")
        for r in recs:
            fh.write(json.dumps(r) + "\n")


def selftest():
    tmp = os.path.join(os.environ.get("TEMP", "/tmp"), "geo_audit_selftest")
    os.makedirs(tmp, exist_ok=True)
    ok = True

    def expect(cond, msg):
        nonlocal ok
        print(("PASS " if cond else "FAIL ") + msg)
        ok = ok and cond

    # -- fixture 1: every check triggered
    _mk(tmp, "w1", [
        {"k": "cube", "c": [0, 2, 0], "s": [1, 0.001, 1], "tex": 3},     # A
        {"k": "cube", "c": [5, -8, 0], "s": [2, 2, 2], "tex": 1},        # B
        {"k": "cube", "c": [3, 3.9, 3], "s": [0.4, 0.8, 0.4], "tex": 2}, # C bottom 3.5
        {"k": "cube", "c": [0, 1, 1], "s": [1, 1, 1], "tex": 2},         # D
        {"k": "cube", "c": [0, 1.0005, 1], "s": [1, 1, 1], "tex": 2},    # D dup
        # E: wall run along z on line x=-3 with a 2m gap
        {"k": "cubeYaw", "c": [-3, 2, -4], "s": [0.2, 4, 2], "tex": 1},
        {"k": "cubeYaw", "c": [-3, 2, -6], "s": [0.2, 4, 2], "tex": 1},
        {"k": "cubeYaw", "c": [-3, 2, -9.0], "s": [0.2, 4, 2], "tex": 1},
        # F
        {"k": "sphere", "c": [0, 5, 0], "s": [2, 1.5, 2], "tex": 1},
    ])
    # -- fixture 2: clean
    _mk(tmp, "w2", [{"k": "cube", "c": [0, 1, 0], "s": [1, 2, 1], "tex": 1}])
    dumps = parse_dump(tmp)
    expect(len(dumps) == 2, "parse_dump found 2 files (w3 from earlier run stays)")
    expect(len(dumps.get("w1", {}).get("cube", [])) == 5, "w1 cube count")
    f = analyze(dumps)
    buckets = Counter(x.bucket for x in f)
    for b in ("A_degenerate", "B_underground", "C_floater", "D_stacking",
              "E_walls", "F_squash"):
        expect(buckets.get(b, 0) >= 1, "fixture1 triggers " + b)
    # w2 must be clean
    expect(not any(x.wp == "w2" for x in f), "fixture2 clean")

    # -- fixture 3: door hole (gap > WALL_GAP_MAX) NOT flagged
    _mk(tmp, "w3", [
        {"k": "cubeYaw", "c": [-3, 2, -4], "s": [0.2, 4, 2], "tex": 1},
        {"k": "cubeYaw", "c": [-3, 2, -7.5], "s": [0.2, 4, 2], "tex": 1},
    ])  # 3.5m gap -> intentional (within max), just below 4m — flagged? no: 3.5<=4.0
    # edge-to-edge gap: slabs at -4 / -7.5 => 1.5m gap, inside [0.3, 4.0] ->
    # flagged (w3 is also the fixture1 leftover? no: fixture3 rewrites w3).
    # Then slabs at -4 / -10.5 => 4.5m edge gap > WALL_GAP_MAX = intentional
    # opening, must NOT be flagged.
    with open(os.path.join(tmp, "w3.jsonl"), "w", encoding="utf-8") as fh:
        fh.write(json.dumps({"wp": "w3"}) + "\n")
        fh.write(json.dumps({"k": "cubeYaw", "c": [-3, 2, -4],
                             "s": [0.2, 4, 2], "tex": 1}) + "\n")
        fh.write(json.dumps({"k": "cubeYaw", "c": [-3, 2, -10.5],
                             "s": [0.2, 4, 2], "tex": 1}) + "\n")
    f3 = analyze(parse_dump(tmp))
    expect(not any(x.wp == "w3" and x.bucket == "E_walls" for x in f3),
           "w3 4.5m edge gap = intentional, not flagged")

    return 0 if ok else 1


def main():
    args = sys.argv[1:]
    if "--selftest" in args:
        sys.exit(selftest())
    if not args:
        print(__doc__)
        sys.exit(2)
    d = args[0]
    out = None
    if "--report" in args:
        out = args[args.index("--report") + 1]
    dumps = parse_dump(d)
    f = analyze(dumps)
    if out:
        report(dumps, f, out)
        print("report -> " + out)
    for x in f:
        print(x.line())
    print("findings: %d over %d waypoints" % (len(f), len(dumps)))
    sys.exit(1 if f else 0)


if __name__ == "__main__":
    main()