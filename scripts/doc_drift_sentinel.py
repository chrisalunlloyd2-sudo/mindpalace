#!/usr/bin/env python3
"""
doc_drift_sentinel.py — MindPalace H48 doc-drift sentinel (step 107, #89).

Scans the living roadmap/design docs for status claims that name a concrete
artifact (file with extension, CamelCase class, snake_case symbol) and
cross-references each against the repo source tree. Polarity:

  [x] DONE / SHIPPED claim    -> drift when EVERY named artifact is ABSENT
  [MISSING] / "not built"     -> drift when ANY named artifact is PRESENT
  [STUB] / [BROKEN] claim     -> drift when EVERY named artifact is ABSENT
                                 (says exists-but-deficient; nothing does)

Skipped by design (would recreate the manual-triage cost this sentinel
exists to remove):
  - unchecked [ ] roadmap todos (aspirational, not status claims)
  - prose-only lines (no symbol-like token)
  - environment claims ("~/" paths — outside the repo's verifiable tree)
  - ALL-CAPS tokens without symbol shape (section headers, English words)

Where a claim and the tree disagree, files ONE GitHub issue per drifted
*file* (label hermes-queue,documentation) listing every drifted claim in
that file — never edits docs, never closes issues (AGENTS.md hard rule 2:
never delete, only merge).

Modes:
  --scan       one-shot drift report to stdout (default)
  --file       post one consolidated issue per drifted file (deduped by a
               marker grep against open issues)
  --strict     exit 1 when any drift is found (gate/CI wiring)

Quota-free: pure stdlib scanning + gh CLI calls. No LLM, no cloud.
"""
import json
import os
import re
import subprocess
import sys
from pathlib import Path

for _s in (sys.stdout, sys.stderr):
    if hasattr(_s, "reconfigure"):
        _s.reconfigure(encoding="utf-8", errors="replace")

REPO = Path(r"C:/Users/viper/AIGEN_SYS/repos/mindpalace")
GITHUB_REPO = "chrisalunlloyd2-sudo/mindpalace"

# Repeat-offender docs first (issue #89), then the rest of the living set.
TRACKED_DOCS = [
    "UNFINISHED_BUSINESS.md",
    "CROSS_CORRELATED_ROADMAP.md",
    "ROADMAP.md",
    "BLUEPRINT.md",
    "ARCHITECTURE.md",
    "NEXT_100_STEPS.md",
    "BLACKBOARD.md",
    "WORLD_FINISHER_PLAN.md",
    "INSTALLER.md",
    "AGENTS.md",
]

# A symbol-like token: explicit file ref, mixed-case class name, or
# snake_case identifier. Plain Capitalized/ALL-CAPS English words are
# prose, not symbols.
SYMBOL_RX = re.compile(
    r"(?:[\w./~@-]+\.(?:java|py|sh|iss|xml|md))"     # file with extension
    r"|\b[A-Z][a-z0-9]+[A-Z][A-Za-z0-9]*\b"          # MixedCase class name
    r"|\b[a-z][a-z0-9]*(?:_[a-z0-9]+)+\b")           # snake_case identifier

# Claim classes -----------------------------------------------
DONE_RX = re.compile(r"- \[[xX]\]", re.IGNORECASE)
ABSENT_CLAIM_RX = re.compile(
    r"\[(MISSING|NOT-STARTED|NOT STARTED)\]|"
    r"\bnot (?:yet )?(?:built|implemented|wired|present)\b|"
    r"\bnever been (?:started|built|added)\b|"
    r"\bno .{1,40} (?:anywhere|in the (?:repo|tree|source))\b",
    re.IGNORECASE)
DEFICIENT_CLAIM_RX = re.compile(
    r"\[(STUB|BROKEN|PARTIAL)\]|"
    r"\bnot yet (?:shipped|landed)\b|"
    r"\bis EMPTY\b", re.IGNORECASE)

# A "no <anything> anywhere in the repo" header word for prose-only
# absence sections: ALL-CAPS token, >= 6 chars, at line start-ish.
# Explicit denylist: the claim marker words themselves (legend lines,
# "[MISSING] jpackage ..." style entries must not self-trigger).
HEADER_WORD_RX = re.compile(r"(?<![\[\w])([A-Z]{6,})(?![\]\w])")
HEADER_DENYLIST = {"MISSING", "BROKEN", "STUB", "PARTIAL", "NOTSTARTED",
                  "STARTED", "BUILT", "WIRED", "PRESENT", "STATUS",
                  "PHASE", "LEGEND", "SEVERITY", "TODO", "DONE",
                  "SHIPPED", "CONVENTION"}


# Valid commit-SHA citation (7+ hex chars) — on [x]/done lines this outranks
# symbol presence (deeper SHA-vs-file correlation is #293/#86 territory).
SHA_RX = re.compile(r"\b[0-9a-f]{7,40}\b")

# Clause splitter: a claim only sees symbols inside its own sentence /
# parenthetical-free clause ("X, Y ported). Z NOT built" must not read X,Y).
CLAUSE_SPLIT_RX = re.compile(r"[.;:](?:\s|$)|\)\.?\s| — ")


def git_tracked():
    """Git tracked (function)."""
    out = subprocess.run(["git", "ls-files"], cwd=str(REPO),
                         capture_output=True, text=True, timeout=60)
    return set(out.stdout.replace("\\", "/").splitlines())


def source_corpus(tracked):
    """Map lowercase basename -> git path for all code artifacts."""
    corpus = {}
    for p in tracked:
        if re.search(r"\.(java|py|sh|iss|xml)$", p):
            corpus.setdefault(Path(p).name.lower(), p)
    return corpus


class Tree:
    """Presence checks against the repo tree, with a shared cache."""

    def __init__(self, corpus, tracked):
        """Init.

        Args: corpus, tracked.
        """
        self.corpus = corpus
        self.tracked = tracked
        self._cache = {}

    def exists(self, sym):
        """True when the symbol looks present in the repo tree."""
        s = sym.strip()
        if s in self._cache:
            return self._cache[s]
        result = self._probe(s)
        self._cache[s] = result
        return result

    def _probe(self, s):
        base = s.lower().lstrip("~/@")
        # 1. exact file basename in the corpus (with known extension)
        if base in self.corpus:
            return True
        stem = Path(base).stem
        if Path(base).suffix and stem in self.corpus:
            return True
        if f"{stem}.java" in self.corpus or f"{stem}.py" in self.corpus:
            return True
        # 2. identifier name inside the corpus (git grep over code only)
        try:
            out = subprocess.run(
                ["git", "grep", "-l", "-I", "--", s, "--",
                 "*.java", "*.py", "*.sh"],
                cwd=str(REPO), capture_output=True, text=True, timeout=60)
            if out.returncode == 0 and out.stdout.strip():
                return True
        except Exception:
            pass
        # 3. for ALL-CAPS header words: case-insensitive prefix hit
        #    (catches MULTIPLAYER section headers vs MultiplayerHub etc.)
        if s.isupper() and len(s) >= 6:
            try:
                out = subprocess.run(
                    ["git", "grep", "-l", "-I", "-i", "-e", s[:6], "--",
                     "*.java"],
                    cwd=str(REPO), capture_output=True, text=True, timeout=60)
                if out.returncode == 0 and out.stdout.strip():
                    return True
            except Exception:
                pass
        return False


def drift_for_doc(doc_text, tree):
    """Return list of (lineno, line, reason) for one doc."""
    findings = []
    for lineno, line in enumerate(doc_text.splitlines(), 1):
        # polarity precedence: a checked [x] line is a DONE claim outright —
        # parentheticals inside it ("polarity ([MISSING]/[ABSENT] flags…)")
        # are polarity prose, not the line's own claim.
        if DONE_RX.search(line):
            cls = "done-claim"
        elif ABSENT_CLAIM_RX.search(line):
            cls = "absent-claim"
        elif DEFICIENT_CLAIM_RX.search(line):
            cls = "deficient-claim"
        else:
            continue
        # environment claims (runtime paths outside the repo) are unverifiable
        if "~/" in line:
            continue
        # done-claims backed by a valid commit SHA: the SHA itself is the
        # evidence; do not second-guess symbol naming (#293 covers SHA/file
        # correlation as a separate check).
        if cls == "done-claim" and SHA_RX.search(line):
            continue
        # scope symbols to the claim's own clause — a preceding "X, Y ported)"
        # parenthetical must not satisfy a later "Z NOT built" assertion
        probe_line = line
        if cls == "absent-claim":
            clause = CLAUSE_SPLIT_RX.split(line)
            probe_line = next((c for c in clause
                               if ABSENT_CLAIM_RX.search(c)),
                              line)
        syms = []
        for m in SYMBOL_RX.finditer(probe_line):
            tok = m.group(0)
            if tok not in syms:
                syms.append(tok)
        if not syms:
            if cls == "absent-claim":
                # section-header fallback: MULTIPLAYER-style ALL-CAPS word
                hw = HEADER_WORD_RX.search(line)
                if (hw and hw.group(1) not in HEADER_DENYLIST
                        and tree.exists(hw.group(1))):
                    findings.append((lineno, line.strip()[:160],
                                     f"{hw.group(1).title()}-class code "
                                     f"present in tree"))
            continue
        states = [tree.exists(s) for s in syms[:8]]
        if cls == "absent-claim" and any(states):
            present = [s for s, ok in zip(syms, states) if ok]
            findings.append((lineno, line.strip()[:160],
                             f"claims absent but present: {present[:3]}"))
        elif cls == "deficient-claim" and not any(states):
            findings.append((lineno, line.strip()[:160],
                             f"claims stub/broken but absent: {syms[:3]}"))
        elif cls == "done-claim" and not any(states):
            findings.append((lineno, line.strip()[:160],
                             f"claims done but tree lacks all: {syms[:3]}"))
    return findings


def gh_open_issue_blob():
    """Gh open issue blob (function)."""
    try:
        out = subprocess.run(
            ["gh", "issue", "list", "--state", "open", "--limit", "200",
             "-R", GITHUB_REPO, "--json", "title,body"],
            capture_output=True, text=True, timeout=60)
        if out.returncode != 0:
            return ""
        data = json.loads(out.stdout or "[]")
        return " ".join((d.get("title", "") + " " + d.get("body", ""))
                        for d in data).lower()
    except Exception:
        return ""


def gh_credential_env():
    """Gh credential env (function)."""
    env = {**os.environ}
    if not env.get("GH_TOKEN") and not env.get("GITHUB_TOKEN"):
        try:
            r = subprocess.run(["git", "credential", "fill"],
                               input="protocol=https\nhost=github.com\n\n",
                               capture_output=True, text=True, timeout=30,
                               cwd=str(REPO))
            for line in r.stdout.splitlines():
                if line.startswith("password="):
                    env["GH_TOKEN"] = line.split("=", 1)[1].strip()
                    break
        except Exception as e:
            print("credential lookup failed:", str(e)[:80])
    return env


def gh_create(title, body):
    """Gh create.

    Args: title, body.
    """
    env = gh_credential_env()
    try:
        r = subprocess.run(
            ["gh", "issue", "create", "-R", GITHUB_REPO,
             "--title", title, "--body", body,
             "--label", "hermes-queue,documentation"],
            capture_output=True, text=True, timeout=60, env=env)
        return (r.returncode == 0), (r.stdout or r.stderr).strip()[:160]
    except FileNotFoundError:
        return False, "gh not on PATH"


def main():
    """Main (function)."""
    mode = "scan"
    strict = False
    for a in sys.argv[1:]:
        if a == "--file":
            mode = "file"
        elif a == "--strict":
            strict = True

    tracked = git_tracked()
    tree = Tree(source_corpus(tracked), tracked)

    total_drift = 0
    report = []
    per_file = {}
    for doc in TRACKED_DOCS:
        p = REPO / doc
        if not p.is_file() or doc not in tracked:
            continue
        text = p.read_text(encoding="utf-8", errors="replace")
        findings = drift_for_doc(text, tree)
        per_file[doc] = findings
        total_drift += len(findings)
        if findings:
            report.append(f"\n## {doc} — {len(findings)} drifted claim(s)")
            for lineno, line, why in findings[:40]:
                report.append(f"- L{lineno}: {line}\n  why: {why}")

    rev = subprocess.run(["git", "rev-parse", "--short", "HEAD"],
                         cwd=str(REPO), capture_output=True, text=True,
                         timeout=30).stdout.strip()
    n_files = sum(1 for v in per_file.values() if v)
    header = (f"H48 doc-drift sentinel @ {rev}: "
              f"{total_drift} drifted claim(s) across {n_files} file(s)")
    print(header)
    print("\n".join(report) if report else "(no drift detected)")

    if mode == "file":
        open_blob = gh_open_issue_blob()
        for doc, findings in per_file.items():
            if not findings:
                continue
            mark = f"doc-drift-sentinel:{doc}"
            if mark.lower() in open_blob:
                print(f"skip (open issue already filed for {doc})")
                continue
            body = [f"{mark}\n\nAuto-filed by `scripts/doc_drift_sentinel.py` "
                    f"(H48, step 107, issue #89) — status claims disagreeing "
                    f"with the current tree. Detection only; docs are "
                    f"intentionally NOT auto-edited (repo convention).\n"]
            for lineno, line, why in findings[:60]:
                body.append(f"- L{lineno}: {line}\n  why: {why}")
            ok, info = gh_create(
                f"Docs: {doc} — {len(findings)} status claim(s) disagree "
                f"with the source tree",
                "\n".join(body))
            print(("filed: " if ok else "create failed: ") + info)

    if strict and total_drift:
        sys.exit(1)
    return 0


if __name__ == "__main__":
    sys.exit(main())