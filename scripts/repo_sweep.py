#!/usr/bin/env python3

"""

repo_sweep.py — MindPalace quota-free nightly repo sweep (H36a, issue #128).



Walks EVERY repo the token can see via the single REST list endpoint

(GET /user/repos — its items already carry open_issues_count and pushed_at,

so ~2 requests total for 143 repos). For repos that have a local clone under

AIGEN_SYS/repos it also counts TODO/FIXME/HACK markers cheaply (os.walk,

text read, capped per repo). Zero LLM calls, zero paid API — safe to cron.



Output (both files):

  mindpalace_memory/repo_sweep/sweep_YYYY-MM-DD.json   (dated history)

  mindpalace_memory/repo_sweep/latest.json             (game ingest point)



The Java side (com.mindpalace.github.RepoSweepData.ingest, called from

GameEngine at boot) reads latest.json and surfaces swept_issues /

swept_push_days as KnowledgeGraph attributes next to the #125 health

rollup. Additive only — RepoScanner.mergeRemoteRepos is untouched.



  --print        one-shot human summary to stdout (default when run directly)

  --out DIR      override output directory (tests)



Auth: GH_TOKEN / GITHUB_TOKEN env, else `git credential fill` (same channel

as scout_bot.py). Never prints the token.

"""

import json

import os

import re

import subprocess

import sys

import time

import urllib.request

from datetime import date

from pathlib import Path



for _s in (sys.stdout, sys.stderr):

    if hasattr(_s, "reconfigure"):

        _s.reconfigure(encoding="utf-8", errors="replace")



REPO = Path(r"C:/Users/viper/AIGEN_SYS/repos/mindpalace")

LOCAL_CLONES = Path(r"C:/Users/viper/AIGEN_SYS/repos")

MEMDIR = Path(r"C:/Users/viper/AIGEN_SYS/mindpalace_memory")

GITHUB_REPO = "chrisalunlloyd2-sudo/mindpalace"

API = "https://api.github.com"



TODO_RE = re.compile(r"\b(TODO|FIXME|HACK)\b[::]? (.+)")

SCAN_EXT = {".java", ".py", ".md", ".js", ".ts"}

SKIP_DIRS = {".git", "node_modules", "target", "dist", "__pycache__", "docs"}





def load_token():

    """GH_TOKEN env, else git credential fill. Never printed, never logged."""

    tok = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")

    if tok:

        return tok.strip()

    try:

        r = subprocess.run(

            ["git", "credential", "fill"],

            input="protocol=https\nhost=github.com\n\n",

            capture_output=True, text=True, timeout=30, cwd=str(REPO))

        for line in r.stdout.splitlines():

            if line.startswith("password="):

                return line.split("=", 1)[1].strip()

    except Exception as e:

        print("credential lookup failed:", str(e)[:120])

    return None





def gh_list_repos(token):

    """One paged walk of /user/repos. Returns raw JSON list objects."""

    out, page = [], 1

    while True:

        url = (API + "/user/repos?per_page=100&page=%d&sort=pushed" % page)

        req = urllib.request.Request(url, headers={

            "Authorization": "token " + token,

            "Accept": "application/vnd.github.v3+json",

            "User-Agent": "mindpalace-repo-sweep",

        })

        with urllib.request.urlopen(req, timeout=60) as resp:

            batch = json.loads(resp.read().decode("utf-8"))

        if not batch:

            break

        out.extend(batch)

        if len(batch) < 100:  # last page

            break

        page += 1

    return out





def count_todos(local_dir):

    """Cheap TODO/FIXME/HACK scan of a local clone. Capped; never walks .git."""

    hits = 0

    files = 0

    try:

        for root, dirs, names in os.walk(local_dir):

            dirs[:] = [d for d in dirs if d not in SKIP_DIRS]

            for n in names:

                if files >= 400:

                    return hits

                if os.path.splitext(n)[1].lower() not in SCAN_EXT:

                    continue

                files += 1

                try:

                    with open(os.path.join(root, n), "r",

                              encoding="utf-8", errors="replace") as f:

                        for line in f:

                            if TODO_RE.search(line):

                                hits += 1

                except OSError:

                    pass

    except OSError:

        pass

    return hits





def sweep(token, out_dir=None):

    """Run the sweep; write dated + latest JSON. Returns the record list."""

    raw = gh_list_repos(token)

    days_floor = int(time.time()) - 365 * 86400

    records = []

    for r in raw:

        name = r.get("name")

        if not name:

            continue

        pushed_at = r.get("pushed_at") or ""

        push_ts = 0

        if pushed_at:

            try:

                push_ts = int(time.mktime(time.strptime(

                    pushed_at, "%Y-%m-%dT%H:%M:%SZ")))

            except ValueError:

                push_ts = 0

        rec = {

            "repo": name,

            "open_issues": int(r.get("open_issues_count") or 0),

            "push_days": max(0, (int(time.time()) - push_ts) // 86400) if push_ts else -1,

            "stale_year": bool(not push_ts or push_ts < days_floor),

            "private": bool(r.get("private")),

        }

        local = LOCAL_CLONES / name

        if local.is_dir():

            rec["todos"] = count_todos(local)

            rec["local"] = True

        else:

            rec["todos"] = 0

            rec["local"] = False

        records.append(rec)



    dest = Path(out_dir) if out_dir else (MEMDIR / "repo_sweep")

    dest.mkdir(parents=True, exist_ok=True)

    payload = {

        "generated": date.today().isoformat(),

        "repos": len(records),

        "items": records,

    }

    (dest / ("sweep_%s.json" % date.today().isoformat())).write_text(

        json.dumps(payload, indent=1), encoding="utf-8")

    (dest / "latest.json").write_text(

        json.dumps(payload, indent=1), encoding="utf-8")

    return records





def main():

    """Main (function)."""
    tok = load_token()

    if not tok:

        print("no token (GH_TOKEN env or git credential fill) — abort")

        return 1

    out_dir = None

    if "--out" in sys.argv:

        out_dir = sys.argv[sys.argv.index("--out") + 1]

    recs = sweep(tok, out_dir)

    stale = [r for r in recs if r["stale_year"] or r["push_days"] >= 30]

    with_open = sum(1 for r in recs if r["open_issues"] > 0)

    todo_hot = sorted((r for r in recs if r["todos"] > 0),

                      key=lambda r: -r["todos"])[:5]

    print("repo sweep: %d repos, %d with open issues, %d stale(30d+)"

          % (len(recs), with_open, len(stale)))

    for r in todo_hot:

        print("  todos: %-28s %d" % (r["repo"], r["todos"]))

    print("wrote latest.json + sweep_%s.json" % date.today().isoformat())

    return 0





if __name__ == "__main__":

    sys.exit(main())