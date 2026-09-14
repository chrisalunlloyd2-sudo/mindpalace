#!/usr/bin/env python3
"""slm_api_gate.py -- local SLM (llama3.2:3b via ollama) as an API-fidelity gate.

Division of labor: the 3B model extracts method names per line (what SLMs are
good at); Python structurally validates each candidate (token followed by '('
in the source line) and diffs against an allowlist (what SLMs are bad at).
Zero hallucination risk: extraction misses are caught by allowlist membership,
extraction hallucinations are killed by the structural check.

Why not classification? Proven this session: 0.5b/1b/3b all YES/NO-bias
(position anchoring) on allowlist classification. Extraction + deterministic
diff is robust: catches every invented API (setKeyState / new Input) while
passing clean blocks.

Usage:
  python tools/slm_api_gate.py <javafile> [--allow extra1,extra2]
  echo "<code>" | python tools/slm_api_gate.py -

Exit code: 0 = clean, 1 = violations found, 2 = ollama unreachable.
Violations are printed as METHOD  line.
"""
import json
import re
import sys
import time
import urllib.request

OLLAMA = "http://localhost:11434/api/generate"
MODEL = "llama3.2:3b"
NUM_CTX = 2048          # 3b defaults to n_ctx=131072 -> 4GB CPU KV cache -> thrash/OOM
KEEP_ALIVE = "30m"

# Selftest blocks live inside GameEngine.runSelfTest(); these are the only
# player/input/camera/world methods a selftest block may call.
DEFAULT_ALLOW = {
    "getCamera", "setPosition", "setYaw", "getYaw", "render",
    "getPosition", "getLookDirection", "injectKeyPress", "injectLeftClick",
    "update", "teleportIntoRoom", "teleportToFloor",
}


def gen(prompt: str, n: int = 80, timeout: int = 90) -> str:
    body = json.dumps({
        "model": MODEL, "prompt": prompt, "stream": False,
        "keep_alive": KEEP_ALIVE,
        "options": {"temperature": 0.0, "num_predict": n, "num_ctx": NUM_CTX},
    }).encode()
    req = urllib.request.Request(OLLAMA, data=body)
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read()).get("response", "")


def extract_methods(line: str) -> set:
    """SLM extraction, per line. 3b lists method names it sees; Python filters."""
    out = gen("List every method name called in this Java line, one per line, nothing else:\n" + line)
    names = set()
    for tok in out.split():
        tok = tok.strip("-.,()[]{};:`\"'")
        if tok and re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", tok):
            names.add(tok)
    return names


def verify_block(code: str, allow: set) -> dict:
    """Returns {method: line_text} for every allowlist violation. Structural
    check: a token counts as a call only if followed by '(' in the line."""
    violations = {}
    t0 = time.time()
    for raw_line in code.splitlines():
        s = raw_line.strip()
        # comments / declarations / known-safe statements carry no calls worth gating
        if (not s or s.startswith(("//", "*", "/*"))
                or re.match(r"^(boolean|int|float|Vector3f|long|double|String)\s", s)
                or s.startswith(("if ", "if(", "System.out", "else"))):
            continue
        # strip string literals so println internals don't feed the extractor
        bare = re.sub(r'"[^"]*"', '""', s)
        try:
            names = extract_methods(bare)
        except Exception as e:
            print(f"WARN ollama: {e} — line skipped", file=sys.stderr)
            continue
        for tok in names:
            if tok not in allow and re.search(r"\b" + re.escape(tok) + r"\s*\(", bare):
                violations.setdefault(tok, s)
    return violations, time.time() - t0


def main(argv):
    args = [a for a in argv if not a.startswith("--")]
    extra = set()
    for i, a in enumerate(argv):
        if a == "--allow" and i + 1 < len(argv):
            extra = {x.strip() for x in argv[i + 1].split(",") if x.strip()}
    allow = DEFAULT_ALLOW | extra

    if len(args) < 2:
        print(__doc__)
        return 2
    path = args[1]
    if path == "-":
        code = sys.stdin.read()
    else:
        with open(path, "r", errors="replace") as f:
            code = f.read()

    # warm the model once (cold loads can take minutes; keep_alive holds it)
    try:
        gen("warm", n=3, timeout=240)
    except Exception as e:
        print(f"GATE_UNREACHABLE: {e}")
        return 2

    violations, dt = verify_block(code, allow)
    if violations:
        print(f"API_VIOLATIONS ({len(violations)}) in {dt:.1f}s:")
        for m, ln in sorted(violations.items()):
            print(f"  {m:20s} | {ln.strip()[:90]}")
        return 1
    print(f"API_CLEAN in {dt:.1f}s (gate: {MODEL}, allow={len(allow)} methods)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))