#!/usr/bin/env python
"""shot_diff.py — screenshot diff vs baseline (QUAL-1 leg 2).

Compares <new_dir>/<label>_<n>.png against <base_dir>/<label>_<n>.png.
PIL-only (numpy absent on this box).

Metric per pair (worst-first ranking):
  mean_abs   : mean absolute RGB delta  (0..255) — brightening/fog shifts
  frac_px_50 : fraction of pixels with channel-delta > 50 — localized pops
                 (missing wall, black rect, z-fight shimmer)
Verdicts: OK (<2.0 mean AND <2% frac), SOFT (one of the two, review),
          HOT (mean >= 2.0 AND frac >= 2%) or mean >= 6.0 — defect candidate.

Usage:
  python scripts/shot_diff.py <new_dir> <base_dir> [--report out.md] [--pairs only,these]
  python scripts/shot_diff.py --selftest
Exit: 0 all OK, 1 HOT/SOFT found, 2 error/missing baseline files.
"""
import os
import sys
from PIL import Image, ImageChops, ImageStat


def pair_key(fn):
    """'05_main_hall_04.png' -> '05_main_hall'"""
    stem = os.path.splitext(fn)[0]
    return "_".join(stem.split("_")[:2])


def diff_pair(new_path, base_path):
    a = Image.open(new_path).convert("RGB")
    b = Image.open(base_path).convert("RGB")
    if a.size != b.size:
        return {"err": "size %s vs %s" % (a.size, b.size),
                "mean_abs": 999.0, "frac_px_50": 1.0}
    d = ImageChops.difference(a, b)
    st = ImageStat.Stat(d)
    mean_abs = sum(st.mean) / 3.0
    # frac of pixels exceeding 50 in any channel: histogram on thresholded diff
    thr = d.point(lambda v: 255 if v > 50 else 0)
    hist = thr.convert("L").histogram()
    hot = sum(hist[1:])
    total = a.size[0] * a.size[1]
    frac = hot / float(total)
    return {"mean_abs": mean_abs, "frac_px_50": frac}


def classify(m):
    if "err" in m:
        return "HOT"
    if m["mean_abs"] >= 2.0 and m["frac_px_50"] >= 0.02:
        return "HOT"
    if m["mean_abs"] >= 6.0:
        return "HOT"
    if m["mean_abs"] >= 2.0 or m["frac_px_50"] >= 0.02:
        return "SOFT"
    return "OK"


def run(new_dir, base_dir, only=None):
    rows = []
    missing = []
    new_files = sorted(f for f in os.listdir(new_dir) if f.endswith(".png"))
    for f in new_files:
        key = pair_key(f)
        if only and key not in only:
            continue
        base_f = f
        if not os.path.exists(os.path.join(base_dir, base_f)):
            # baseline may use a different waypoint index suffix — try key match
            cand = [g for g in os.listdir(base_dir)
                    if g.endswith(".png") and pair_key(g) == key]
            base_f = cand[0] if cand else None
            if not base_f:
                missing.append(f)
                continue
        m = diff_pair(os.path.join(new_dir, f),
                      os.path.join(base_dir, base_f))
        rows.append((key, f, base_f, classify(m), m))
    return rows, missing


def report(rows, missing, path):
    L = ["# shot diff vs baseline", ""]
    L.append("| new | verdict | mean_abs | frac>50 |")
    L.append("|---|---|---|---|")
    for key, f, base_f, v, m in sorted(rows, key=lambda r: (-r[4].get("mean_abs", 0))):
        L.append("| %s | %s | %.2f | %.1f%%" % (
            f, v, m.get("mean_abs", -1), m.get("frac_px_50", 0) * 100))
    if missing:
        L.append("")
        L.append("missing baseline for: " + ", ".join(missing))
    with open(path, "w", encoding="utf-8") as fh:
        fh.write("\n".join(L) + "\n")


def selftest():
    import tempfile
    tmp = tempfile.mkdtemp(prefix="shot_diff_")
    a = Image.new("RGB", (8, 8), (10, 10, 10))
    b = Image.new("RGB", (8, 8), (10, 10, 10))
    c = Image.new("RGB", (8, 8), (200, 200, 200))
    na, nb, nc = (os.path.join(tmp, n) for n in ("a.png", "b.png", "c.png"))
    a.save(na); b.save(nb); c.save(nc)
    m_same = diff_pair(na, nb)
    m_diff = diff_pair(na, nc)
    ok = (classify(m_same) == "OK" and m_same["mean_abs"] < 0.01
          and classify(m_diff) == "HOT" and m_diff["mean_abs"] > 100)
    print(("PASS" if ok else "FAIL") + " selftest same=%.2f diff=%.2f"
          % (m_same["mean_abs"], m_diff["mean_abs"]))
    return 0 if ok else 1


def main():
    args = sys.argv[1:]
    if "--selftest" in args:
        sys.exit(selftest())
    if len(args) < 2:
        print(__doc__)
        sys.exit(2)
    new_dir, base_dir = args[0], args[1]
    only = None
    if "--pairs" in args:
        only = set(args[args.index("--pairs") + 1].split(","))
    rows, missing = run(new_dir, base_dir, only)
    out = None
    if "--report" in args:
        out = args[args.index("--report") + 1]
        report(rows, missing, out)
        print("report -> " + out)
    hot = [r for r in rows if r[3] == "HOT"]
    soft = [r for r in rows if r[3] == "SOFT"]
    for key, f, base_f, v, m in sorted(rows, key=lambda r: -r[4].get("mean_abs", 0)):
        print("%-32s %-5s mean=%.2f frac=%.1f%%" % (
            f, v, m.get("mean_abs", -1), m.get("frac_px_50", 0) * 100))
    print("OK=%d SOFT=%d HOT=%d missing=%d" % (len(rows) - len(hot) - len(soft),
                                               len(soft), len(hot), len(missing)))
    sys.exit(1 if (hot or soft or missing) else 0)


if __name__ == "__main__":
    main()