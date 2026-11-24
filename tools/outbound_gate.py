#!/usr/bin/env python3
"""outbound_gate.py -- pre-send gate for outbound email from the agent fleet.

Why this exists: the send path is an LLM-backed tool. A body containing a long
high-entropy "cyber" string (a hex dump, a base64 blob, an encoded shell
payload) can trip a safety refusal in the sending model, and the mail then
silently never goes out. The same shapes also leak credentials when a git
remote (PAT embedded in the URL) is pasted by accident.

The gate is deterministic and offline. It never sends anything. It reads a
draft file (or stdin) and reports blockers per draft. A blocker means DO NOT
SEND. Warnings are advisory.

HUMAN APPROVAL (2026-11-08, #316). Content being clean is NOT permission to
send. Every draft must also match an explicit human approval record in
config/outreach_approvals.json keyed by the draft's sha256 (digest over
to+subject+body). No record = HELD (reason: no_human_approval). This closes
the 2026-10-26 incident in which three content-clean cold-outreach drafts
signed "Alice, Chris's business AI partner" were sent to strangers: the gate
said "clear to send" because it only judged content.

Print the digests an operator needs to approve with:
    python3 tools/outbound_gate.py drafts.md --print-digests

What it blocks:
  credential     PATs, API keys, private keys, Bearer tokens, PAT-in-URL remotes
  high_entropy   long hex/base64/encoded-payload runs and shell one-liners
  override       instruction-override / jailbreak markers aimed at an LLM
  structure      empty recipient/subject/body, absurd length, too many to:

Usage:
  python3 tools/outbound_gate.py drafts.md            # every draft in the file
  python3 tools/outbound_gate.py drafts.md --draft 2  # one draft
  python3 tools/outbound_gate.py - --json             # body on stdin
  cat body.txt | python3 tools/outbound_gate.py -

Exit: 0 = no blockers, 1 = blockers found, 2 = bad usage.
"""
import argparse
import hashlib
import json
import math
import os
import re
import sys
from collections import Counter
from pathlib import Path

# ---------------------------------------------------------------- categories

CREDENTIAL_PATTERNS = [
    ("github-fine-grained-pat", r"github_pat_[A-Za-z0-9_]{20,}"),
    ("github-pat", r"\bgh[pousr]_[A-Za-z0-9]{20,}"),
    ("github-app-token", r"\b(?:ghs|ghr)_[A-Za-z0-9]{20,}"),
    ("pat-in-remote-url", r"x-access-token:[^@\s/]+@"),
    ("openai-key", r"\bsk-[A-Za-z0-9]{20,}"),
    ("anthropic-key", r"\bsk-ant-[A-Za-z0-9_-]{20,}"),
    ("aws-access-key", r"\bAKIA[0-9A-Z]{16}\b"),
    ("google-api-key", r"\bAIza[0-9A-Za-z_-]{30,}"),
    ("gitlab-pat", r"\bglpat-[A-Za-z0-9_-]{16,}"),
    ("npm-token", r"\bnpm_[A-Za-z0-9]{30,}"),
    ("slack-token", r"\bxox[baprs]-[A-Za-z0-9-]{10,}"),
    ("bearer-token", r"\bBearer\s+[A-Za-z0-9._-]{20,}"),
    ("private-key-block", r"-----BEGIN [A-Z ]*PRIVATE KEY-----"),
    ("generic-api-secret", r"(?i)\b(?:api[_-]?key|secret|passwd|password|token)\s*[:=]\s*['\"][^'\"\s]{12,}['\"]"),
]

HIGH_ENTROPY_PATTERNS = [
    ("hex-run", r"(?<![A-Za-z0-9])[0-9a-fA-F]{28,}(?![A-Za-z0-9])"),
    ("hex-escape-run", r"(?:\\x[0-9a-fA-F]{2}){8,}"),
    ("c-style-hex-run", r"(?:0x[0-9a-fA-F]{2}\s*,\s*){8,}"),
    ("base64-run", r"(?<![A-Za-z0-9+/])[A-Za-z0-9+/]{44,}={0,2}(?![A-Za-z0-9+/=])"),
    ("encoded-command", r"(?i)-enc(?:odedcommand)?\b"),
    ("encoded-powershell", r"(?i)Invoke-Expression|FromBase64String|IEX\s*\("),
    ("certutil-decode", r"(?i)certutil\s+.*-decode"),
    ("piped-remote-shell", r"(?i)\b(?:curl|wget)\b[^\n|]{0,120}\|\s*(?:ba)?sh\b"),
    ("reverse-shell", r"(?i)nc\s+-[a-z]*e\b|/dev/tcp/"),
    ("destructive-oneliner", r"(?i);\s*rm\s+-rf\b|\bformat\s+c:\b"),
    ("null-byte", r"\\x00"),
]

# Network-recon solicitation (#418, FINDING channel: public GitHub issues).
# An agent must not ASK FOR or publish internal network topology / access
# material in a public channel: Tailscale hostnames, SSH login users, mesh
# node identifiers. This is the same failure class as #316 (rule 10) through
# the GitHub-comment channel — #397 auto-bumped 7x over 4 days asking for a
# node's HostName + SSH User before the Architect shut it down by hand.
NETWORK_SOLICITATION_PATTERNS = [
    ("ssh-config-solicit", r"(?i)\b(?:ssh|sshd)[^\n]{0,80}\b(?:user|login|config|hostname|host_name|host name|unblock|values?)\b"),
    ("ssh-key-identity-ask", r"(?i)\b(?:HostName|ssh user|login user|authorized[_ ]?keys?|id_rsa|id_ed25519)\b"),
    ("tailscale-node-solicit", r"(?i)\btailscale[^\n]{0,80}\b(?:hostname|ip|node|unblock|hostname \+ user|host_name)\b"),
    ("mesh-node-solicit", r"(?i)\b(?:mesh|tailnet|vpn)[^\n]{0,60}\b(?:node name|node names|internal (?:ip|address|dns))\b"),
    ("internal-network-probe", r"(?i)\b(?:100\.(?:64|65|66|67)\.\d{1,3}\.\d{1,3}|10\.\d{1,3}\.\d{1,3}\.\d{1,3}|192\.168\.\d{1,3}\.\d{1,3})\b"),
    ("solicit-for-agent", r"(?i)\b(?:values?|credentials?|config)\b[^\n]{0,40}\bfor\b[^\n]{0,40}\b(?:alice|agent|bot|sandbox)\b"),
]

OVERRIDE_PATTERNS = [
    ("ignore-previous", r"(?i)\b(?:ignore|disregard|forget)\s+(?:all\s+|any\s+)?(?:previous|prior|above|earlier)\s+(?:instructions?|prompts?|rules?|directions?)"),
    ("disregard-system-prompt", r"(?i)\bdisregard\s+(?:your|the)\s+(?:system\s+)?(?:prompt|instructions|guidelines)"),
    ("you-are-now", r"(?i)\byou\s+are\s+now\s+(?:a|an|the|in)\b"),
    ("dan-mode", r"(?i)\bDAN\s+mode\b|\bdo\s+anything\s+now\b"),
    ("developer-mode", r"(?i)\b(?:developer|god|jailbreak)\s+mode\s+(?:enabled|on)\b"),
    ("no-restrictions", r"(?i)\bwithout\s+any\s+(?:restrictions|limits|filters|censorship)\b"),
    ("pretend-to-be", r"(?i)\bpretend\s+(?:to\s+be|you\s+are)\b"),
    ("fake-system-prompt", r"(?i)(?:^|\n)\s*(?:system|assistant|developer)\s*:\s"),
    ("override-suffix", r"(?i)\bignore\s+(?:the\s+)?(?:above|previous)\s+and\s+(?:instead\s+)?(?:say|output|reply)\b"),
]

# Advisory only: legitimate in prose, but worth a human glance.
WARNING_PATTERNS = [
    ("raw-http-url", r"https?://[^\s\"']{60,}"),
    ("many-recipients", r"@"),  # handled structurally, placeholder only
]

MAX_BODY_CHARS = 20000
MAX_SUBJECT_CHARS = 250
MAX_RECIPIENTS = 20
MIN_TOKEN_ENTROPY = 4.2
MIN_TOKEN_LEN = 24


# ---------------------------------------------------------------- helpers

def shannon(data: str) -> float:
    """Shannon entropy in bits per character."""
    if not data:
        return 0.0
    counts = Counter(data)
    n = len(data)
    return -sum((c / n) * math.log2(c / n) for c in counts.values())


def _redact(text: str, keep: int = 4) -> str:
    """Show enough to locate the problem, not enough to use it."""
    text = text.strip()
    if len(text) <= keep * 2 + 3:
        return text
    return f"{text[:keep]}...{text[-keep:]}"


def _line_of(text: str, index: int) -> int:
    return text.count("\n", 0, index) + 1


# ---------------------------------------------------------------- scanning

def _findings_for_patterns(body: str, patterns, category: str, severity: str):
    out = []
    for name, pattern in patterns:
        for m in re.finditer(pattern, body):
            out.append({
                "category": category,
                "severity": severity,
                "rule": name,
                "line": _line_of(body, m.start()),
                "preview": _redact(m.group(0)),
                "length": len(m.group(0)),
            })
    return out


def _entropy_tokens(body: str):
    """Tokens that look like an encoded blob: long AND high-entropy."""
    out = []
    for m in re.finditer(r"(?<![A-Za-z0-9+/=_-])[A-Za-z0-9+/=_-]{24,}(?![A-Za-z0-9+/=_-])", body):
        tok = m.group(0)
        if not re.search(r"\d", tok):
            continue
        if not (re.search(r"[a-z]", tok) and re.search(r"[A-Z]", tok)) and "+/=" not in tok:
            continue
        ent = shannon(tok)
        if ent >= MIN_TOKEN_ENTROPY:
            out.append({
                "category": "high_entropy",
                "severity": "blocker",
                "rule": "encoded-token",
                "line": _line_of(body, m.start()),
                "preview": _redact(tok),
                "length": len(tok),
                "entropy": round(ent, 2),
            })
    return out


def scan_body(body: str, subject: str = "", to: str = ""):
    """Scan one email. Returns a list of finding dicts."""
    findings = []
    findings += _findings_for_patterns(body, CREDENTIAL_PATTERNS, "credential", "blocker")
    findings += _findings_for_patterns(body, HIGH_ENTROPY_PATTERNS, "high_entropy", "blocker")
    findings += _findings_for_patterns(body, OVERRIDE_PATTERNS, "override", "blocker")
    findings += _findings_for_patterns(body, NETWORK_SOLICITATION_PATTERNS, "network_recon", "blocker")
    findings += _entropy_tokens(body)
    findings += _findings_for_patterns(body, WARNING_PATTERNS, "advisory", "warning")

    stripped = body.strip()
    if not stripped:
        findings.append({"category": "structure", "severity": "blocker", "rule": "empty-body",
                         "line": 1, "preview": "", "length": 0})
    if len(body) > MAX_BODY_CHARS:
        findings.append({"category": "structure", "severity": "blocker", "rule": "body-too-long",
                         "line": 1, "preview": f"{len(body)} chars", "length": len(body)})
    if not subject.strip():
        findings.append({"category": "structure", "severity": "blocker", "rule": "empty-subject",
                         "line": 1, "preview": "", "length": 0})
    elif len(subject) > MAX_SUBJECT_CHARS:
        findings.append({"category": "structure", "severity": "blocker", "rule": "subject-too-long",
                         "line": 1, "preview": f"{len(subject)} chars", "length": len(subject)})
    if not to.strip():
        findings.append({"category": "structure", "severity": "blocker", "rule": "empty-recipient",
                         "line": 1, "preview": "", "length": 0})
    else:
        addrs = [a for a in re.split(r"[,;]", to) if a.strip()]
        if len(addrs) > MAX_RECIPIENTS:
            findings.append({"category": "structure", "severity": "blocker", "rule": "too-many-recipients",
                             "line": 1, "preview": f"{len(addrs)} recipients", "length": len(addrs)})
        for a in addrs:
            a = a.strip()
            if a and not re.match(r"^[^@\s]+@[^@\s]+\.[A-Za-z]{2,}$", a):
                findings.append({"category": "structure", "severity": "blocker", "rule": "malformed-recipient",
                                 "line": 1, "preview": _redact(a), "length": len(a)})
    return findings


APPROVALS_ENV = "MINDPALACE_OUTREACH_APPROVALS"
DEFAULT_APPROVALS = Path("config/outreach_approvals.json")


def approvals_path():
    """Approvals file: env override, else the repo default."""
    return Path(os.environ.get(APPROVALS_ENV) or DEFAULT_APPROVALS)


def load_approvals(path=None):
    """sha256 -> {"approvedBy", "date", "scope"}; missing/broken file = {}."""
    p = Path(path) if path else approvals_path()
    if not p.is_file():
        return {}
    try:
        doc = json.loads(p.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}
    out = {}
    for rec in (doc.get("approvals") or []):
        sha = str(rec.get("sha256", "")).strip().lower()
        if len(sha) == 64:
            out[sha] = rec
    return out


def draft_digest(draft):
    """Stable approval key: sha256 over normalised to+subject+body."""
    norm = "\n".join([
        (draft.get("to") or "").strip().lower(),
        (draft.get("subject") or "").strip(),
        " ".join((draft.get("body") or "").split()),
    ])
    return hashlib.sha256(norm.encode("utf-8")).hexdigest()


def verdict(findings, approved=False):
    """Summarise findings + human approval into a send/hold decision."""
    blockers = [f for f in findings if f["severity"] == "blocker"]
    warnings = [f for f in findings if f["severity"] == "warning"]
    hold = []
    if blockers:
        hold.append("content_blockers")
    if not approved:
        hold.append("no_human_approval")
    return {
        "send": not blockers and approved,
        "approved": bool(approved),
        "blockers": len(blockers),
        "warnings": len(warnings),
        "categories": sorted({f["category"] for f in blockers}),
        "hold_reasons": hold,
    }


# ---------------------------------------------------------------- bump-rate
# (#418) repeat-post guard: the same unresolved ask re-posted on a cadence is
# the #397 signature (7 auto-bumps over 4 days). An agent may not re-post the
# same ask to the same channel without a human in the loop after N repeats.
# Deterministic + offline: a small JSON journal at repo default (env
# overridable so tests never touch the real file), fail-closed like
# load_approvals — a missing/broken journal lets the count start at 1, but a
# REAL journal accumulates and gates. Never deletes entries.
BUMP_JOURNAL_ENV = "MINDPALACE_BUMP_JOURNAL"
DEFAULT_BUMP_JOURNAL = Path("config/bump_journal.json")
BUMP_MAX_REPEATS = 3


def bump_journal_path():
    """Journal file: env override, else the repo default."""
    return Path(os.environ.get(BUMP_JOURNAL_ENV) or DEFAULT_BUMP_JOURNAL)


def load_bump_journal(path=None):
    """{"posts": [{"key": <sha>, "when": <iso8601>}, ...]; missing/broken = {"posts": []}."""
    p = Path(path) if path else bump_journal_path()
    if not p.is_file():
        return {"posts": []}
    try:
        doc = json.loads(p.read_text(encoding="utf-8"))
        posts = doc.get("posts")
        return {"posts": posts if isinstance(posts, list) else []}
    except (OSError, ValueError):
        return {"posts": []}


def bump_key(channel: str, target: str, ask: str):
    """Stable key: sha256 over channel+target+normalised ask text."""
    norm = "\n".join([
        (channel or "").strip().lower(),
        (target or "").strip(),
        " ".join((ask or "").split()),
    ])
    return hashlib.sha256(norm.encode("utf-8")).hexdigest()


def bump_repeats(key: str, path=None):
    """How many times this key was already posted (0 for a first post)."""
    journal = load_bump_journal(path)
    return sum(1 for p in journal["posts"] if isinstance(p, dict) and p.get("key") == key)


def bump_blocker_if_over(key: str, path=None, max_repeats=BUMP_MAX_REPEATS):
    """Blocker finding when the ask was already posted >= max_repeats times.

    Fail-closed direction: repeat #4+ of the same unresolved ask needs the
    channel to change or a human to intervene — re-posting is exactly what
    made #397 run unattended for 4 days.
    """
    seen = bump_repeats(key, path)
    if seen < max_repeats:
        return None
    return {
        "category": "bump_rate",
        "severity": "blocker",
        "rule": "repeat-post-over-limit",
        "line": 1,
        "preview": _redact(key),
        "length": len(key),
        "previous_posts": seen,
        "limit": max_repeats,
    }


def bump_journal_append(key: str, path=None, when_iso=None):
    """Record one post in the journal (create-if-absent), never blocking."""
    p = Path(path) if path else bump_journal_path()
    journal = load_bump_journal(p)
    journal["posts"].append({
        "key": key,
        "when": when_iso or datetime_iso(),
    })
    try:
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(json.dumps(journal, indent=2, sort_keys=True), encoding="utf-8")
    except OSError:
        pass  # journal write failure must never crash the agent loop


def datetime_iso():
    """Datetime iso (function)."""
    import datetime
    return datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


# ---------------------------------------------------------------- drafts

# NOTE: the field lines use [^\n]* (not .*) because re.S makes . swallow the
# body: "Subject: x\nBody:\nhi" would match as one Subject line.
DRAFT_RE = re.compile(
    r"^##\s+Draft\s+(?P<num>\d+)\s*(?P<title>[^\n]*)\n"
    r"(?P<fields>(?:^(?:To|Subject):[^\n]*\n)+)"
    r"^Body:\n(?P<body>.*?)(?=^---\s*$|\Z)",
    re.M | re.S,
)


def parse_drafts(text: str):
    """Parse the draft file format: '## Draft N', 'To:', 'Subject:', 'Body:'."""
    drafts = []
    for m in DRAFT_RE.finditer(text):
        fields = m.group("fields")
        to = subject = ""
        for line in fields.splitlines():
            if line.lower().startswith("to:"):
                to = line.split(":", 1)[1].strip()
            elif line.lower().startswith("subject:"):
                subject = line.split(":", 1)[1].strip()
        drafts.append({
            "num": int(m.group("num")),
            "title": m.group("title").strip(),
            "to": to,
            "subject": subject,
            "body": m.group("body").strip(),
            # #429 (FINDING-R): channel/target for the bump-rate journal —
            # "email" drafts use the recipient as the target; the CLI --channel
            # / --target options (below) let a GitHub-comment caller identify
            # the channel and issue it is about to post to.
            "channel": os.environ.get("MINDPALACE_BUMP_CHANNEL", "email"),
            "target": to or os.environ.get("MINDPALACE_BUMP_TARGET", ""),
        })
    return drafts


# ---------------------------------------------------------------- cli

def _opt_value(argv, name):
    """Value of a `--name value` pair searched in the RAW argv (post-argv[0]):
    the value may be a flag-looking string or a bare word — both live in
    argv, not in a flag/word split. (#432/#438 FINDING-T: the dead-arg-filter
    bug class — an opts-only scan returns the NEXT FLAG, e.g. channel='--target'.)"""
    for i in range(1, len(argv) - 1):
        if argv[i] == name:
            return argv[i + 1]
    return None


def _report(draft, findings, verbose):
    v = verdict(findings)
    label = f"Draft {draft['num']}" + (f" ({draft['title']})" if draft.get("title") else "")
    status = "SEND" if v["send"] else "HOLD"
    print(f"[{status}] {label} -> {draft.get('to', '(no recipient)')}")
    if v["send"] and not findings:
        print("    clean: no blockers, no warnings")
    for f in findings:
        mark = "BLOCK" if f["severity"] == "blocker" else "warn "
        print(f"    {mark} [{f['category']}/{f['rule']}] line {f['line']}: {f['preview']!r} ({f['length']} chars)")
    return v


def main(argv):
    """Main.

    Args: argv.
    """
    args = [a for a in argv[1:] if not a.startswith("--")]
    opts = [a for a in argv[1:] if a.startswith("--")]

    path = args[0] if args else "-"
    only = None
    as_json = "--json" in opts
    # #429 (FINDING-R): channel+target identify WHAT is about to be posted —
    # the bump-rate journal keys on (channel, target, ask-body) and blocks the
    # 4th+ repeat. The email path derives them from the draft; external
    # callers (GitHub-comment posters per #428) pass them explicitly.
    # #432/#438 (FINDING-T): scan the RAW argv — values are only there.
    channel = _opt_value(argv, "--channel") or "email"
    target_opt = _opt_value(argv, "--target")
    for i, a in enumerate(argv):
        if a == "--draft" and i + 1 < len(argv):
            only = int(argv[i + 1])

    if path == "-":
        text = sys.stdin.read()
    else:
        p = Path(path)
        if not p.exists():
            print(f"no such file: {path}", file=sys.stderr)
            return 2
        text = p.read_text(encoding="utf-8")

    if only is not None:
        # single draft mode: treat the whole input as one body
        drafts = [{"num": only, "title": "", "to": "", "subject": "", "body": text.strip(),
                   "channel": channel, "target": target_opt or ""}]
    else:
        drafts = parse_drafts(text)
        if not drafts:
            drafts = [{"num": 1, "title": "", "to": "", "subject": "", "body": text.strip(),
                       "channel": channel, "target": target_opt or ""}]
        # CLI channel/target override the per-draft defaults (per-draft env
        # values already fill in when the caller passed none).
        if target_opt or channel != "email":
            for d in drafts:
                d["channel"] = channel
                if target_opt:
                    d["target"] = target_opt

    if "--print-digests" in opts:
        for d in drafts:
            print(f"draft {d['num']}: sha256={draft_digest(d)}  to={d['to']}")
        return 0

    approvals = load_approvals()
    results = []
    for d in drafts:
        findings = scan_body(d["body"], d["subject"], d["to"])
        digest = draft_digest(d)
        approved = digest in approvals
        # #429 (FINDING-R): the bump-rate guard is LIVE here — a repeat ask to
        # the same channel+target adds its blocker to the same list content
        # blockers go to, so approval cannot override it (verdict counts it).
        bump = bump_blocker_if_over(
            bump_key(d.get("channel", channel), d.get("target", ""), d["body"]))
        if bump:
            findings.append(bump)
        results.append({"draft": d, "findings": findings, "digest": digest,
                        "approved": approved, "verdict": verdict(findings, approved),
                        "bump_key": bump and bump_key(d.get("channel", channel), d.get("target", ""), d["body"])})

    if as_json:
        print(json.dumps([{
            "num": r["draft"]["num"], "title": r["draft"]["title"], "to": r["draft"]["to"],
            "subject": r["draft"]["subject"], "digest": r["digest"], "verdict": r["verdict"],
            "findings": r["findings"],
        } for r in results], indent=2))
    else:
        for r in results:
            _report(r["draft"], r["findings"], verbose=False)
            if not r["approved"]:
                print(f"  HELD draft {r['draft']['num']}: no human approval "
                      f"(add sha256={r['digest']} to {approvals_path()})")

    blocked = [r for r in results if not r["verdict"]["send"]]
    if not as_json:
        content_held = sum(1 for r in results if "content_blockers" in r["verdict"]["hold_reasons"])
        unapproved = sum(1 for r in results if "no_human_approval" in r["verdict"]["hold_reasons"])
        print()
        print(f"{len(results) - len(blocked)}/{len(results)} drafts clear to send, {len(blocked)} held"
              f" ({content_held} content, {unapproved} awaiting human approval)")
    # #429 (FINDING-R): journal-append the posts that CLEARED the gate (never
    # the held ones — a held draft is not a post). This is what makes the
    # bump-rate guard real: repeats land in the journal and the 4th is blocked.
    for r in results:
        if r["verdict"]["send"]:
            d = r["draft"]
            bump_journal_append(bump_key(d.get("channel", channel), d.get("target", ""), d["body"]))
    return 1 if blocked else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
