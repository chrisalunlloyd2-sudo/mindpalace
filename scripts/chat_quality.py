#!/usr/bin/env python3
"""chat_quality.py — per-day chat-log quality stats (P1-B, no LLM).

Reads chat_logs/chat-YYYY-MM-DD.jsonl (the production AgentChat JSONL lines,
{"ts","msg"} legacy shape and {"ts","bot","kind","text"} structured shape),
groups per day and prints a table + writes reports/chat_quality.json with:

  n            number of msg/text records
  unique_pct   share of exact-text-unique records (rolling-window echo detector)
  code_pct     share containing code markers (def/class/import/(){};= etc.)
  meta_pct     share of meta-filler (selftest fixture texts, probes, "fills the
               rolling window", "round-trip probe", window/schema chatter)
  median_len   median text length in chars
  by_bot       per-bot breakdown when the line carries a "bot" field
  by_kind      per-kind breakdown when the line carries a "kind" field

Usage:
    python scripts/chat_quality.py                 # default: <repo>/chat_logs
    python scripts/chat_quality.py --dir PATH     # another log root
    python scripts/chat_quality.py --days 14      # limit to last N days in name
    python scripts/chat_quality.py --out PATH     # override report path

The report lands in reports/chat_quality.json relative to CWD by default.
Exit codes: 0 = report written; 2 = no chat logs found/parsable.
"""
from __future__ import annotations

import argparse
import json
import re
import statistics
import sys
from collections import Counter, defaultdict
from datetime import datetime
from pathlib import Path

# Code-bearing markers: real agent chatter mentions code constructs.
CODE_MARKERS = (
    "def ", "class ", "import ", "public ", "private ", "void ", "return ",
    "()", "{}", "->", "==", "!=", " && ", " || ", ";", "=", "null", "int ",
    "float ", "string", "String", "try {", "catch", "throw", ".get(", ".set(",
    "mvn", "git ", "commit", "compile", "build", "selftest", "exception",
    "error", "stack", "trace", "json", "http", "api",
)

# Meta-filler: fixture/probe/window chatter that measures nothing about bot
# output quality — count it separately so real msg stats stay visible.
META_MARKERS = (
    "fills the rolling window fast",   # selftest thread-N fixture (AgentChat 36c)
    "schema round-trip probe",         # selftest schema probe (AgentChat 47)
    "thread-",                         # fixture prefix
)

LINE_RE = re.compile(r'"(?:msg|text)":"(.*?)"(?:,|\})')
DAY_RE = re.compile(r"chat-(\d{4}-\d{2}-\d{2})\.jsonl$")


def classify(records: list[dict]) -> dict:
    texts = [r["text"] for r in records]
    n = len(texts)
    if n == 0:
        return {"n": 0}

    lower = [t.lower() for t in texts]
    unique = len(set(texts))
    code = sum(1 for t in lower if any(m in t for m in CODE_MARKERS))
    meta = sum(1 for t in lower
               if any(m in t for m in META_MARKERS)
               or t.startswith("thread-"))
    lens = [len(t) for t in texts]

    bots = Counter(r.get("bot", "(none)") for r in records)
    kinds = Counter(r.get("kind", "(none)") for r in records)

    return {
        "n": n,
        "unique_pct": round(100.0 * unique / n, 1),
        "code_pct": round(100.0 * code / n, 1),
        "meta_pct": round(100.0 * meta / n, 1),
        "median_len": statistics.median(lens),
        "by_bot": dict(bots.most_common()),
        "by_kind": dict(kinds.most_common()),
    }


def load_day(path: Path) -> list[dict]:
    records = []
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            obj = json.loads(line)
        except json.JSONDecodeError:
            # Fallback regex for hand-written/legacy lines.
            m = LINE_RE.search(line)
            if m:
                obj = {"msg": m.group(1)}
            else:
                continue
        text = obj.get("msg") or obj.get("text") or ""
        if not text:
            continue
        rec = {"text": text}
        if "bot" in obj:
            rec["bot"] = str(obj["bot"])
        if "kind" in obj:
            rec["kind"] = str(obj["kind"])
        records.append(rec)
    return records


def main() -> int:
    repo_root = Path(__file__).resolve().parent.parent
    ap = argparse.ArgumentParser(description=__doc__)
    default_logs = repo_root / "chat_logs"
    ap.add_argument("--dir", default=str(default_logs), help="chat-log root dir")
    ap.add_argument("--days", type=int, default=0,
                    help="only days >= N days back (0 = all)")
    ap.add_argument("--out", default=str(repo_root / "reports" / "chat_quality.json"),
                    help="report output path")
    ap.add_argument("--per-record", action="store_true",
                    help="also split msg/text kinds per day")
    args = ap.parse_args()

    log_dir = Path(args.dir)
    if not log_dir.is_dir():
        print(f"chat_quality: no chat-log dir at {log_dir}", file=sys.stderr)
        return 2

    per_day: dict[str, dict] = {}
    for path in sorted(log_dir.glob("chat-*.jsonl")):
        m = DAY_RE.search(path.name)
        if not m:
            continue
        day = m.group(1)
        records = load_day(path)
        if not records:
            continue
        entry = classify(records)
        if args.per_record:
            # Split legacy msg-lines (no bot field) from structured bot events.
            msg_recs = [r for r in records if "bot" not in r]
            text_recs = [r for r in records if "bot" in r]
            entry["msg"] = classify(msg_recs)
            entry["text"] = classify(text_recs)
        per_day[day] = entry

    if not per_day:
        print("chat_quality: no parsable chat-*.jsonl records", file=sys.stderr)
        return 2

    if args.days > 0:
        days = sorted(per_day)[-args.days:]
        per_day = {d: per_day[d] for d in days}

    # Print a table.
    header = f"{'day':<12}{'n':>5}{'uni%':>7}{'code%':>7}{'meta%':>7}{'med_len':>9}"
    print(header)
    print("-" * len(header))
    for day in sorted(per_day):
        e = per_day[day]
        if e["n"] == 0:
            continue
        print(f"{day:<12}{e['n']:>5}{e['unique_pct']:>7}{e['code_pct']:>7}"
              f"{e['meta_pct']:>7}{e['median_len']:>9}")

    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    report = {
        "generated": datetime.now().isoformat(timespec="seconds"),
        "source": str(log_dir),
        "days": per_day,
    }
    out.write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(f"\nwrote {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
