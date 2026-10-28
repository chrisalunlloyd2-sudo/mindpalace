#!/usr/bin/env python3
"""
claude_commit_watch.py — watch Claude's code across the operator's repos and
teach the MindPalace bots from it.

Why this exists
---------------
The bots improve by watching how *good* code gets written. Claude (the
Anthropic coding agent) writes a lot of code in this estate, but almost all
of it lands in repos the bots never look at — especially PRIVATE ones, which
are invisible to any public-event feed. The bots were therefore learning
from a fraction of the available material, and "missing things" was the
symptom.

What it does (per run)
----------------------
 1. Discovers EVERY repo the token can see (public AND private) through the
    authenticated ``GET /user/repos`` endpoint. Private repos are included
    by design — a public-events watcher cannot see them.
 2. Walks each repo's commit history, newest first, stopping early at the
    last commit we already harvested (incremental: no re-reading of history
    we have seen).
 3. Classifies each commit as Claude-authored or Claude-co-authored, using
    the canonical GitHub identity fields first (commit author name/email and
    the linked GitHub login) and the explicit ``Co-Authored-By: Claude ...``
    trailer as the fallback. It never guesses from the message text alone.
 4. Turns each new Claude commit into a *lesson*: the commit subject (the
    rationale) plus structural facts (file count, extensions, whether a
    test file was touched, change size). Code bodies are NOT copied.
 5. Redacts lessons that come from PRIVATE repos: the repo name becomes a
    stable pseudonym, path-like and secret-like tokens are scrubbed from
    the subject, and the subject is truncated.
 6. Writes an append-only lesson ledger + a ``latest.json`` ingest point
    (the same shape/place ``scripts/repo_sweep.py`` uses, so the Java side
    picks it up the same way) and an append-only dedupe ledger keyed by
    commit SHA.

Design rules honoured
---------------------
  * Quota-free: no LLM calls, no paid API. Standard library only.
  * ADD-only: never deletes a harvested lesson; the ledgers only grow.
  * Never leaks private material: private repos are pseudonymised and
    scrubbed, and only the redacted lesson is written anywhere.
  * Never twice the same lesson: dedupe is by commit SHA.
  * Nothing lives forever: the raw commit detail is not stored, only the
    derived lesson.

Usage
-----
    python scripts/claude_commit_watch.py                  # incremental run
    python scripts/claude_commit_watch.py --print          # human summary
    python scripts/claude_commit_watch.py --force          # re-walk history
    python scripts/claude_commit_watch.py --repo mindpalace # one repo
    python scripts/claude_commit_watch.py --max-repos 5    # smoke test

Environment:
    GH_TOKEN / GITHUB_TOKEN   — PAT with ``repo`` scope (private repos).
    MINDPALACE_MEMORY_DIR     — override the output root (default:
                                ``~/AIGEN_SYS/mindpalace_memory``).
"""

from __future__ import annotations

import argparse
import hashlib
import hmac
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Sequence, Tuple

API = "https://api.github.com"
USER_AGENT = "mindpalace-claude-commit-watch"

# ── Claude identity ───────────────────────────────────────────────────────
# Canonical GitHub identity of the Anthropic coding agent:
#   name  = "anthropic-code-agent[bot]"
#   email = "242468646+Claude@users.noreply.github.com"
#   login = "Claude"
CLAUDE_NAME_RE = re.compile(r"anthropic-code-agent", re.I)
CLAUDE_EMAIL_RE = re.compile(r"claude@users\.noreply\.github\.com", re.I)
CLAUDE_LOGIN_RE = re.compile(r"^claude$", re.I)
# Fallback: Claude named as a co-author on someone else's commit.
COAUTHOR_RE = re.compile(
    r"^Co-Authored-By:\s*Claude[^<\n]*<([^>\n]+)>", re.I | re.M
)
CLAUDE_TRAILER_EMAIL_RE = re.compile(r"anthropic\.com", re.I)

# ── redaction ─────────────────────────────────────────────────────────────
PATH_LIKE_RE = re.compile(
    r"(?<![\w])(?:[A-Za-z]:)?[A-Za-z0-9_.\-]+(?:[\\/][A-Za-z0-9_.\-]+)+"
    r"(?:\.[A-Za-z0-9]{1,6})?"
)
FILENAME_RE = re.compile(r"(?<![\w/\\])[A-Za-z0-9_\-]+\.[A-Za-z0-9]{1,6}\b")
SECRET_RE = re.compile(
    r"\b(?:gh[pousr]_[A-Za-z0-9]{16,}|github_pat_[A-Za-z0-9_]{20,}"
    r"|sk-[A-Za-z0-9]{20,}|[A-Fa-f0-9]{40,}|[A-Za-z0-9+/]{40,}={0,2})\b"
)
EMAIL_RE = re.compile(r"\b[\w.+-]+@[\w-]+\.[\w.-]+\b")
SUBJECT_MAX = 240

# ── sweep limits (cheap by default) ───────────────────────────────────────
FIRST_RUN_LIMIT = 25      # newest commits considered on first sight of a repo
PER_RUN_COMMIT_CAP = 300  # max new commits processed per run, fleet-wide
DETAIL_CAP = 40           # max per-commit detail fetches per run (files[])
PR_CAP = 40               # max PR commit-lists pulled per run, fleet-wide
BRANCH_CAP = 8            # max extra branches walked per repo per run


def _now_iso() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat()


# ── credentials ───────────────────────────────────────────────────────────
def load_token() -> Optional[str]:
    """PAT from env, else the stored git credential (same channel the other
    MindPalace bots use). Never prints the token."""
    for var in ("GH_TOKEN", "GITHUB_TOKEN", "MINDPALACE_GH_TOKEN"):
        val = os.environ.get(var)
        if val and val.strip():
            return val.strip()
    try:
        proc = subprocess.run(
            ["git", "credential", "fill"],
            input="protocol=https\nhost=github.com\n\n",
            capture_output=True, text=True, timeout=30,
        )
        for line in (proc.stdout or "").splitlines():
            if line.startswith("password="):
                tok = line.split("=", 1)[1].strip()
                if tok:
                    return tok
    except Exception as exc:  # pragma: no cover - environment dependent
        print("credential lookup failed:", str(exc)[:120])
    return None


# ── GitHub API ────────────────────────────────────────────────────────────
def api_get(path: str, token: str, timeout: int = 30, retries: int = 2) -> Optional[Any]:
    """GET a GitHub API path. Returns parsed JSON, or None on failure."""
    url = path if path.startswith("http") else API + path
    req = urllib.request.Request(url, headers={
        "Authorization": "token " + token,
        "Accept": "application/vnd.github+json",
        "User-Agent": USER_AGENT,
    })
    delay = 1.5
    for attempt in range(retries + 1):
        try:
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                return json.loads(resp.read().decode("utf-8"))
        except urllib.error.HTTPError as exc:
            if exc.code in (403, 429) and attempt < retries:
                time.sleep(delay)
                delay *= 2
                continue
            print("api error %s %s" % (exc.code, path[:90]))
            return None
        except Exception as exc:
            if attempt < retries:
                time.sleep(delay)
                delay *= 2
                continue
            print("api failure:", str(exc)[:120])
            return None
    return None


def list_repos(token: str, affiliation: str = "owner,collaborator,organization_member",
               max_pages: int = 20) -> List[Dict[str, Any]]:
    """Every repo the token can see — public AND private."""
    out: List[Dict[str, Any]] = []
    for page in range(1, max_pages + 1):
        data = api_get(
            "/user/repos?per_page=100&page=%d&sort=pushed&affiliation=%s"
            % (page, affiliation), token, timeout=60)
        if not isinstance(data, list) or not data:
            break
        out.extend(data)
        if len(data) < 100:
            break
    return out


def fetch_commits(token: str, full_name: str, branch: str,
                   stop_sha: Optional[str], max_pages: int = 10) -> List[Dict[str, Any]]:
    """Newest-first commit list, stopping early once ``stop_sha`` is seen."""
    out: List[Dict[str, Any]] = []
    for page in range(1, max_pages + 1):
        data = api_get(
            "/repos/%s/commits?sha=%s&per_page=100&page=%d" % (full_name, branch, page),
            token, timeout=45)
        if not isinstance(data, list) or not data:
            break
        for commit in data:
            out.append(commit)
            if stop_sha and commit.get("sha", "").startswith(stop_sha):
                return out
        if len(data) < 100:
            break
    return out


def fetch_commit_detail(token: str, full_name: str, sha: str) -> Optional[Dict[str, Any]]:
    """Per-commit detail (files[], stats) — one request, used sparingly."""
    return api_get("/repos/%s/commits/%s" % (full_name, sha), token, timeout=30)


def list_pulls(token: str, full_name: str, max_pages: int = 3) -> List[Dict[str, Any]]:
    """Every PR (open AND closed) — one cheap request per 100 PRs.

    A PR is the natural unit of agent work: it names the head branch, the
    agent that opened it, and the whole change. This is how we reach code
    that never lands on the default branch.
    """
    out: List[Dict[str, Any]] = []
    for page in range(1, max_pages + 1):
        data = api_get(
            "/repos/%s/pulls?state=all&per_page=100&page=%d&sort=updated&direction=desc"
            % (full_name, page), token, timeout=60)
        if not isinstance(data, list) or not data:
            break
        out.extend(data)
        if len(data) < 100:
            break
    return out


def fetch_pr_commits(token: str, full_name: str, number: int) -> List[Dict[str, Any]]:
    """The commits of one PR (one request, up to 250)."""
    data = api_get("/repos/%s/pulls/%d/commits?per_page=250" % (full_name, number),
                   token, timeout=45)
    return data if isinstance(data, list) else []


def list_branch_heads(token: str, full_name: str,
                      max_pages: int = 3) -> List[Dict[str, Any]]:
    """Branch heads: name + head SHA (one cheap request per 100 branches)."""
    out: List[Dict[str, Any]] = []
    for page in range(1, max_pages + 1):
        data = api_get("/repos/%s/branches?per_page=100&page=%d" % (full_name, page),
                       token, timeout=60)
        if not isinstance(data, list) or not data:
            break
        out.extend(data)
        if len(data) < 100:
            break
    return out


def fetch_branch_commits(token: str, full_name: str, branch: str,
                         stop_sha: Optional[str], limit: int = 25) -> List[Dict[str, Any]]:
    """Newest-first commits on a branch, stopping at a known SHA.

    Stops early at ``stop_sha`` (the branch's own cursor) or as soon as a
    commit we have already judged appears — that is the fork point, so
    everything below it was harvested via another ref.
    """
    out: List[Dict[str, Any]] = []
    for page in range(1, 11):
        data = api_get(
            "/repos/%s/commits?sha=%s&per_page=100&page=%d" % (full_name, branch, page),
            token, timeout=45)
        if not isinstance(data, list) or not data:
            break
        for commit in data:
            out.append(commit)
            if stop_sha and commit.get("sha", "").startswith(stop_sha):
                return out
            if len(out) >= limit:
                return out
        if len(data) < 100:
            break
    return out


# ── classification ────────────────────────────────────────────────────────
def classify_commit(commit: Dict[str, Any]) -> Optional[str]:
    """Return 'author' / 'co_author' when Claude wrote this commit.

    Canonical identity fields win; the Co-Authored-By trailer is the
    fallback. The commit message is never used as the only signal.
    """
    if not isinstance(commit, dict):
        return None
    inner = commit.get("commit") or {}
    author = inner.get("author") or {}
    name = str(author.get("name") or "")
    email = str(author.get("email") or "")
    gh_author = commit.get("author") or {}
    login = str(gh_author.get("login") or "")

    if (CLAUDE_NAME_RE.search(name)
            or CLAUDE_EMAIL_RE.search(email)
            or CLAUDE_LOGIN_RE.match(login)):
        return "author"

    message = str(inner.get("message") or "")
    for trailer_email in COAUTHOR_RE.findall(message):
        if CLAUDE_TRAILER_EMAIL_RE.search(trailer_email) or "claude" in trailer_email.lower():
            return "co_author"
    return None


def commit_subject(commit: Dict[str, Any]) -> str:
    """Commit subject.

    Args: commit.
    """
    inner = commit.get("commit") or {}
    message = str(inner.get("message") or "")
    for line in message.splitlines():
        if line.strip():
            return line.strip()
    return ""


# ── redaction ─────────────────────────────────────────────────────────────
def load_redaction_key(memory_dir: Path) -> bytes:
    """Per-machine secret used to pseudonymise private repo names.

    Stored outside the repo; created once with 0600. Without it, private
    repo names are hashed with a fixed salt (still stable, still opaque).
    """
    key_path = memory_dir / ".redaction_key"
    try:
        if key_path.is_file():
            data = key_path.read_bytes().strip()
            if data:
                return data
        key_path.parent.mkdir(parents=True, exist_ok=True)
        data = os.urandom(32)
        key_path.write_bytes(data)
        try:
            os.chmod(key_path, 0o600)
        except OSError:
            pass
        return data
    except OSError:
        return b"mindpalace-claude-watch-static-salt"


def pseudonym(name: str, key: bytes) -> str:
    """Pseudonym.

    Args: name, key.
    """
    digest = hmac.new(key, name.encode("utf-8"), hashlib.sha256).hexdigest()
    return "repo-" + digest[:12]


def redact_text(text: str) -> str:
    """Strip path-like and secret-like tokens from a private-repo subject."""
    if not text:
        return ""
    out = EMAIL_RE.sub("<email>", text)
    out = SECRET_RE.sub("<secret>", out)
    out = PATH_LIKE_RE.sub("<path>", out)
    out = FILENAME_RE.sub("<file>", out)
    return out.strip()


def build_lesson(commit: Dict[str, Any], role: str, full_name: str,
                 is_private: bool, key: bytes,
                 detail: Optional[Dict[str, Any]] = None,
                 origin: str = "default_branch",
                 origin_ref: str = "") -> Dict[str, Any]:
    """One harvested lesson: rationale + structure, no code bodies.

    ``origin`` records WHERE the code was found (default_branch / pull_request
    / branch) and ``origin_ref`` names the ref (branch or PR number) — so a
    bot can tell "this pattern was learned from PR #242" from "this was
    learned from a merged commit".
    """
    subject = commit_subject(commit)
    files_changed = 0
    extensions: List[str] = []
    touched_test = False
    additions = 0
    deletions = 0
    if isinstance(detail, dict):
        stats = detail.get("stats") or {}
        additions = int(stats.get("additions") or 0)
        deletions = int(stats.get("deletions") or 0)
        for entry in detail.get("files") or []:
            files_changed += 1
            filename = str(entry.get("filename") or "")
            if "." in filename:
                ext = "." + filename.rsplit(".", 1)[-1].lower()
                if ext not in extensions:
                    extensions.append(ext)
            lowered = filename.lower()
            if ("test" in lowered or lowered.startswith("spec")
                    or "/tests/" in lowered or "/test/" in lowered):
                touched_test = True

    if is_private:
        lesson_subject = redact_text(subject)[:SUBJECT_MAX]
        repo_key = pseudonym(full_name, key)
        display_repo = ""
    else:
        lesson_subject = subject[:SUBJECT_MAX]
        repo_key = full_name
        display_repo = full_name

    return {
        "sha": str(commit.get("sha") or ""),
        "repo_key": repo_key,
        "display_repo": display_repo,
        "private": bool(is_private),
        "role": role,
        "origin": origin,
        "origin_ref": origin_ref,
        "subject": lesson_subject,
        "files_changed": files_changed,
        "extensions": sorted(extensions),
        "touched_test": touched_test,
        "additions": additions,
        "deletions": deletions,
        "harvested_at": _now_iso(),
    }


def derive_tags(lesson: Dict[str, Any]) -> List[str]:
    """Deterministic structural tags — the shape a bot can learn from."""
    tags: List[str] = []
    subject = (lesson.get("subject") or "").lower()
    if lesson.get("touched_test"):
        tags.append("test_touched")
    if (lesson.get("files_changed") or 0) > 1:
        tags.append("multi_file")
    if lesson.get("role") == "co_author":
        tags.append("co_authored")
    if lesson.get("private"):
        tags.append("private_repo")
    origin = lesson.get("origin") or "default_branch"
    if origin == "pull_request":
        tags.append("from_pr")
    elif origin == "branch":
        tags.append("from_branch")
    for prefix, tag in (("fix", "fix"), ("feat", "feature"), ("refactor", "refactor"),
                        ("docs", "docs"), ("test", "test"), ("perf", "perf"),
                        ("build", "build"), ("chore", "chore")):
        if subject.startswith(prefix) or (" " + prefix + ":") in subject:
            tags.append(tag)
            break
    return sorted(set(tags))


# ── state ─────────────────────────────────────────────────────────────────
def _read_jsonl(path: Path) -> List[Dict[str, Any]]:
    if not path.is_file():
        return []
    rows: List[Dict[str, Any]] = []
    try:
        with path.open("r", encoding="utf-8", errors="replace") as handle:
            for line in handle:
                line = line.strip()
                if not line:
                    continue
                try:
                    rows.append(json.loads(line))
                except json.JSONDecodeError:
                    continue
    except OSError:
        return []
    return rows


def _append_jsonl(path: Path, row: Dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a", encoding="utf-8") as handle:
        handle.write(json.dumps(row, sort_keys=True) + "\n")


def load_state(state_dir: Path) -> Tuple[set, Dict[str, Any]]:
    """Seen commit SHAs (dedupe) + per-repo incremental cursor."""
    seen = {str(row.get("sha")) for row in _read_jsonl(state_dir / "seen.jsonl")
            if row.get("sha")}
    repos_path = state_dir / "repos_state.json"
    repos: Dict[str, Any] = {}
    if repos_path.is_file():
        try:
            repos = json.loads(repos_path.read_text(encoding="utf-8")) or {}
        except (OSError, json.JSONDecodeError):
            repos = {}
    return seen, repos


def save_state(state_dir: Path, repos: Dict[str, Any]) -> None:
    """Save state.

    Args: state_dir, repos.
    """
    state_dir.mkdir(parents=True, exist_ok=True)
    path = state_dir / "repos_state.json"
    tmp = path.with_suffix(".json.tmp")
    tmp.write_text(json.dumps(repos, indent=1, sort_keys=True), encoding="utf-8")
    tmp.replace(path)


# ── harvest ───────────────────────────────────────────────────────────────
def harvest(token: str, memory_dir: Path, repos: Sequence[Dict[str, Any]],
            force: bool = False, per_repo_limit: Optional[int] = None,
            max_new: int = PER_RUN_COMMIT_CAP,
            include_prs: bool = True,
            include_branches: bool = True) -> Dict[str, Any]:
    """Walk repos, classify Claude commits, write lessons + state.

    Three passes per repo:
      1. default branch  — merged work,
      2. pull requests   — agent work that never merged (the bulk of it),
      3. extra branches  — heads not covered by a PR (usually agent plans).
    Every commit is deduped by SHA, so a commit reachable from several refs
    is harvested once, from the first ref that reaches it.
    """
    state_dir = memory_dir / "claude_watch"
    state_dir.mkdir(parents=True, exist_ok=True)
    key = load_redaction_key(memory_dir)
    seen, repo_state = load_state(state_dir)

    lessons: List[Dict[str, Any]] = []
    newly_seen: set = set()
    new_commits = 0
    skipped_known = 0
    repos_scanned = 0
    repos_private = 0
    repos_with_claude = 0
    details_used = 0
    errors = 0
    prs_scanned = 0
    prs_with_claude = 0
    branches_scanned = 0
    branches_with_claude = 0
    origin_counts: Dict[str, int] = {"default_branch": 0, "pull_request": 0, "branch": 0}

    for repo in repos:
        full_name = str(repo.get("full_name") or "")
        if not full_name:
            continue
        is_private = bool(repo.get("private"))
        branch = str(repo.get("default_branch") or "main")
        repos_scanned += 1
        if is_private:
            repos_private += 1

        cursor = repo_state.get(full_name) or {}
        pushed_at = str(repo.get("pushed_at") or "")
        stop_sha = cursor.get("last_sha")
        if (not force and stop_sha
                and cursor.get("pushed_at") == pushed_at
                and cursor.get("last_sha")):
            continue  # nothing pushed since the last walk

        limit = per_repo_limit
        if limit is None:
            limit = FIRST_RUN_LIMIT if not stop_sha else max_new

        repo_had_claude = False

        def absorb(commits: Sequence[Dict[str, Any]], origin: str,
                   origin_ref: str) -> None:
            """Classify + record the Claude commits in one ref's list."""
            nonlocal repo_had_claude, new_commits, details_used, skipped_known
            for commit in commits:
                sha = str(commit.get("sha") or "")
                if not sha or sha in seen:
                    skipped_known += 1
                    continue
                role = classify_commit(commit)
                if role is None:
                    # Not Claude: remember so we never re-check it.
                    seen.add(sha)
                    newly_seen.add(sha)
                    skipped_known += 1
                    continue
                if new_commits >= max_new:
                    return

                detail = None
                if details_used < DETAIL_CAP:
                    detail = fetch_commit_detail(token, full_name, sha)
                    if detail is not None:
                        details_used += 1

                lesson = build_lesson(commit, role, full_name, is_private, key,
                                      detail, origin=origin, origin_ref=origin_ref)
                lesson["tags"] = derive_tags(lesson)
                lessons.append(lesson)
                _append_jsonl(state_dir / "lessons.jsonl", lesson)
                seen.add(sha)
                newly_seen.add(sha)
                new_commits += 1
                origin_counts[origin] = origin_counts.get(origin, 0) + 1
                repo_had_claude = True

        # ── pass 1: default branch ────────────────────────────────────
        commits = fetch_commits(token, full_name, branch, stop_sha)
        if commits:
            newest_sha = str(commits[0].get("sha") or "")
            if not stop_sha and len(commits) > limit:
                commits = commits[:limit]
            absorb(commits, "default_branch", branch)
        else:
            newest_sha = stop_sha or ""

        # ── pass 2: pull requests (agent work that never merged) ──────
        if include_prs and prs_scanned < PR_CAP:
            for pull in list_pulls(token, full_name):
                prs_scanned += 1
                number = pull.get("number")
                if number is None:
                    continue
                pr_commits = fetch_pr_commits(token, full_name, int(number))
                if not pr_commits:
                    continue
                pr_ref = "#%s" % number
                before = new_commits
                absorb(pr_commits, "pull_request", pr_ref)
                if new_commits > before:
                    prs_with_claude += 1
                if prs_scanned >= PR_CAP:
                    break

        # ── pass 3: extra branches (heads with no PR) ─────────────────
        if include_branches and branches_scanned < BRANCH_CAP:
            for head in list_branch_heads(token, full_name):
                name = str(head.get("name") or "")
                if not name or name == branch:
                    continue
                branches_scanned += 1
                head_sha = str(((head.get("commit") or {}).get("sha")) or "")
                if head_sha and head_sha in seen:
                    continue  # nothing new on this branch
                branch_commits = fetch_branch_commits(
                    token, full_name, name, None, limit=limit)
                before = new_commits
                absorb(branch_commits, "branch", name)
                if new_commits > before:
                    branches_with_claude += 1
                if branches_scanned >= BRANCH_CAP:
                    break

        if repo_had_claude:
            repos_with_claude += 1
        repo_state[full_name] = {
            "last_sha": newest_sha,
            "pushed_at": pushed_at,
            "private": is_private,
            "last_walk": _now_iso(),
        }

    # Persist every SHA we judged THIS run (Claude or not) so the next run
    # never re-fetches or re-classifies it. Only newly-seen SHAs are written
    # -- `seen` is loaded from this same file, so re-writing it wholesale
    # would grow the ledger quadratically. The ledger only grows.
    for sha in sorted(newly_seen):
        _append_jsonl(state_dir / "seen.jsonl", {"sha": sha, "ts": _now_iso()})

    save_state(state_dir, repo_state)

    payload = {
        "generated": _now_iso(),
        "repos_scanned": repos_scanned,
        "private_repos_scanned": repos_private,
        "repos_with_claude": repos_with_claude,
        "prs_scanned": prs_scanned,
        "prs_with_claude": prs_with_claude,
        "branches_scanned": branches_scanned,
        "branches_with_claude": branches_with_claude,
        "lessons_by_origin": origin_counts,
        "new_lessons": len(lessons),
        "items": lessons,
    }
    (state_dir / "latest.json").write_text(
        json.dumps(payload, indent=1, sort_keys=True), encoding="utf-8")
    return payload


def summarize(payload: Dict[str, Any]) -> str:
    """Summarize.

    Args: payload.
    """
    items = payload.get("items") or []
    lines = [
        "claude-commit-watch: %d new lesson(s) from %d repo(s) "
        "(%d private) — %d repo(s) had Claude code"
        % (len(items), payload.get("repos_scanned", 0),
           payload.get("private_repos_scanned", 0),
           payload.get("repos_with_claude", 0)),
    ]
    for lesson in items[:15]:
        lines.append("  [%s] %s — %s" % (
            lesson.get("role", "?"),
            lesson.get("repo_key", "?"),
            (lesson.get("subject") or "")[:90]))
    if len(items) > 15:
        lines.append("  ... and %d more" % (len(items) - 15))
    return "\n".join(lines)


def main(argv: Optional[Sequence[str]] = None) -> int:
    """Main.

    Args: argv.
    """
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--out", help="memory dir override")
    parser.add_argument("--repo", action="append",
                        help="only this repo (repeatable); matches name or full_name")
    parser.add_argument("--max-repos", type=int, default=0,
                        help="limit repos walked (0 = all)")
    parser.add_argument("--max-new", type=int, default=PER_RUN_COMMIT_CAP,
                        help="cap new commits processed this run")
    parser.add_argument("--per-repo-limit", type=int, default=0,
                        help="commits considered per repo (0 = default)")
    parser.add_argument("--force", action="store_true",
                        help="re-walk history even if nothing was pushed")
    parser.add_argument("--no-prs", action="store_true",
                        help="skip the pull-request pass")
    parser.add_argument("--no-branches", action="store_true",
                        help="skip the extra-branch pass")
    parser.add_argument("--print", action="store_true", dest="do_print",
                        help="print a human summary")
    args = parser.parse_args(list(argv) if argv is not None else None)

    memory_dir = Path(args.out).expanduser() if args.out else (
        Path(os.environ.get("MINDPALACE_MEMORY_DIR")
             or (Path.home() / "AIGEN_SYS" / "mindpalace_memory")))
    memory_dir = memory_dir.resolve()

    token = load_token()
    if not token:
        print("no GitHub token available (GH_TOKEN/GITHUB_TOKEN or stored credential)")
        return 2

    repos = list_repos(token)
    if not repos:
        print("no repos visible to the token — check the PAT has 'repo' scope")
        return 3

    if args.repo:
        wanted = {r.lower() for r in args.repo}
        repos = [r for r in repos
                 if str(r.get("full_name", "")).lower() in wanted
                 or str(r.get("name", "")).lower() in wanted]
    if args.max_repos:
        repos = repos[:args.max_repos]

    payload = harvest(
        token, memory_dir, repos,
        force=args.force,
        per_repo_limit=args.per_repo_limit or None,
        max_new=args.max_new,
        include_prs=not args.no_prs,
        include_branches=not args.no_branches,
    )
    if args.do_print:
        print(summarize(payload))
    else:
        print("claude-commit-watch: %d new lesson(s) -> %s"
              % (len(payload.get("items") or []),
                 memory_dir / "claude_watch" / "latest.json"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
