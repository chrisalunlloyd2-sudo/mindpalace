#!/usr/bin/env python3
"""feedback_digest.py — weekly early-user feedback digest (script-only, no LLM).

Reads GitHub issues + discussions, buckets signals, prints the digest:
  - new feedback items since last run (issues/discussions opened)
  - funnel health (good-first-issues open, unlabeled-highs)
Usage: python scripts/feedback_digest.py [--days 7]
Exit 0 always (digest is informational); appends to
C:/Users/viper/AppData/Local/Temp/mindpalace_feedback_digest.log for cron.
"""
import json, os, subprocess, sys, time, datetime, argparse
from pathlib import Path

GIT_BASH = r"C:/Users/viper/AppData/Local/hermes/git/usr/bin/bash.exe"
LOG = Path(r"C:/Users/viper/AppData/Local/Temp/mindpalace_feedback_digest.log")
OWNER_REPO = "chrisalunlloyd2-sudo/mindpalace"

def token():
    r = subprocess.run([GIT_BASH, "-c",
        "printf 'protocol=https\\nhost=github.com\\n\\n' | git credential-manager get 2>/dev/null | grep '^password=' | cut -d= -f2"],
        capture_output=True, text=True, timeout=180)
    return r.stdout.strip()

def gh(args, env, tries=2):
    for k in range(tries):
        r = subprocess.run(["gh"] + args, capture_output=True, text=True, timeout=200, env=env)
        if r.returncode == 0:
            return r
        time.sleep(8)
    return r

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--days", type=int, default=7)
    a = ap.parse_args()
    since = (datetime.datetime.now(datetime.timezone.utc) - datetime.timedelta(days=a.days)).strftime("%Y-%m-%dT%H:%M:%SZ")
    tok = token()
    env = dict(os.environ, GH_TOKEN=tok) if tok else os.environ.copy()

    lines = [f"=== FEEDBACK DIGEST {datetime.datetime.now():%Y-%m-%d %H:%M} (window {a.days}d) ==="]

    # issues opened in window
    r = gh(["issue", "list", "--repo", OWNER_REPO, "--state", "all", "--limit", "50",
            "--json", "number,title,author,state,createdAt"], env)
    try:
        issues = json.loads(r.stdout or "[]")
    except Exception:
        issues = []
    recent = [i for i in issues if i.get("createdAt", "") >= since]
    lines.append(f"issues opened (all states): {len(recent)}")
    for i in recent:
        lines.append(f"  #{i['number']} [{i['state']}] {i['title'][:60]} - by {i['author']['login']}")

    # discussions in window
    q = ('{ repository(owner: "chrisalunlloyd2-sudo", name: "mindpalace") '
         '{ discussions(first: 20) { nodes { number title createdAt '
         'comments(first: 30) { totalCount } } } } }')
    r = gh(["api", "graphql", "-f", "query=" + q], env)
    try:
        nodes = json.loads(r.stdout)["data"]["repository"]["discussions"]["nodes"]
    except Exception:
        nodes = []
    recent_d = [d for d in nodes if (d.get("createdAt") or "") >= since]
    lines.append(f"discussions opened: {len(recent_d)}")
    for d in recent_d:
        lines.append(f"  #{d['number']} {d['title'][:60]} - {d['comments']['totalCount']} comments")

    # funnel health
    r = gh(["issue", "list", "--repo", OWNER_REPO, "--label", "good first issue",
            "--state", "open", "--json", "number"], env)
    try:
        funnel = len(json.loads(r.stdout or "[]"))
    except Exception:
        funnel = -1
    lines.append(f"funnel (open good-first-issues): {funnel}  target>=6")

    r = gh(["issue", "list", "--repo", OWNER_REPO, "--label", "high",
            "--state", "open", "--json", "number,title,labels"], env)
    try:
        highs = json.loads(r.stdout or "[]")
    except Exception:
        highs = []
    unlabeled = [i for i in highs if not any(l["name"].startswith("area/") for l in i["labels"])]
    lines.append(f"open highs: {len(highs)}  unlabeled-area: {len(unlabeled)}  (process bug if >0)")

    text = "\n".join(lines)
    print(text)
    with open(LOG, "a", encoding="utf-8") as f:
        f.write(text + "\n")

if __name__ == "__main__":
    main()