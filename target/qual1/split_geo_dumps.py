#!/usr/bin/env python
"""split_geo_dumps.py — split GeoAudit's single geo_records.jsonl into
per-waypoint <label>.jsonl files so scripts/geo_audit.py's parse_dump
(one file per waypoint) can consume them. Uses each {"wp": "<label>"}
header line as the cut; writes into an output dir."""
import json
import os
import sys


def main(src, outdir):
    os.makedirs(outdir, exist_ok=True)
    # sanitize old outputs so stale labels can't leak into this split
    for stale in os.listdir(outdir):
        if stale.endswith(".jsonl"):
            os.remove(os.path.join(outdir, stale))
    cur_label = None
    fh = None
    n_files = 0
    n_recs = 0
    with open(src, encoding="utf-8", errors="replace") as inp:
        for raw in inp:
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
                label = str(rec["wp"])
                if fh is not None:
                    fh.close()
                fh = open(os.path.join(outdir, label + ".jsonl"), "w",
                          encoding="utf-8")
                n_files += 1
                cur_label = label
                fh.write(raw + "\n")
                continue
            if fh is None or cur_label is None:
                continue
            fh.write(raw + "\n")
            n_recs += 1
    if fh is not None:
        fh.close()
    print("split -> %d wp files, %d records into %s" % (n_files, n_recs, outdir))


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])