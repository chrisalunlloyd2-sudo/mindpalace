#!/usr/bin/env python3
"""MP-001: inventory of every player-facing instruction, cross-checked against the code that handles each key.

Sources compared:
  CODE    every GLFW_KEY_* the Java sources reference (file:line + enclosing method)
  HELP    the F1 overlay text in GameEngine.renderHelpOverlay
  README  the Controls table in README.md
Output: reports/instruction_inventory.md (one row per key) and a non-zero exit with --strict if a documented key has
no handler or a handled key is undocumented. Standard library only; read-only on the sources.
"""
import re
import sys
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "src" / "main" / "java"

# README / help spellings -> the GLFW key names they stand for
ALIASES = {
    "WASD": ["W", "A", "S", "D"], "ESC": ["ESCAPE"], "SHIFT": ["LEFT_SHIFT"], "/": ["SLASH"], "`/`": ["SLASH"],
    "ENTER": ["ENTER"], "SPACE": ["SPACE"], "TAB": ["TAB"], "F1": ["F1"], "F3": ["F3"], "F4": ["F4"], "F8": ["F8"],
    "F11": ["F11"], "F12": ["F12"], "M": ["M"], "1-9": list("123456789"), "F5": ["F5"], "F6": ["F6"],
    "[": ["LEFT_BRACKET"], "]": ["RIGHT_BRACKET"], "-": ["MINUS"], "=": ["EQUAL"], ",": ["COMMA"], ".": ["PERIOD"],
}
# keys that are mouse/text-entry/internal, not instructions a player needs documented as controls
NOT_CONTROLS = {"LAST", "BACKSPACE", "UP", "DOWN", "LEFT", "RIGHT", "PERIOD", "COMMA", "MINUS", "EQUAL",
                "LEFT_BRACKET", "RIGHT_BRACKET",
                "C"}      # menu navigation / text entry / step-sequencer clear (options > music page): listed, not counted as gaps


def code_keys():
    found = defaultdict(list)
    method = re.compile(r"^\s*(?:public|private|protected|static|final|synchronized|\s)+[\w<>\[\], ?]+\s+(\w+)\s*\([^;]*\)\s*(?:throws [\w, ]+)?\{?\s*$")
    for p in sorted(JAVA.rglob("*.java")):
        lines = p.read_text(encoding="utf-8", errors="replace").splitlines()
        current = "?"
        for i, line in enumerate(lines, 1):
            m = method.match(line)
            if m and not line.strip().startswith(("if", "for", "while", "switch", "else", "return", "new")):
                current = m.group(1)
            for k in re.findall(r"GLFW_KEY_([A-Z0-9_]+)", line):
                found[k].append(f"{p.relative_to(ROOT).as_posix()}:{i} ({current})")
    return found


def readme_keys():
    text = (ROOT / "README.md").read_text(encoding="utf-8", errors="replace")
    sec = text.split("## Controls", 1)[1].split("### Book Editor Commands", 1)[0]     # includes the Dressing Room table
    rows = {}
    for line in sec.splitlines():
        m = re.match(r"\|\s*([^|]+?)\s*\|\s*([^|]+?)\s*\|", line)
        if m and m.group(1) not in ("Key", "-----") and not set(m.group(1)) <= {"-"}:
            rows[m.group(1)] = m.group(2)
    return rows


def help_keys():
    src = (JAVA / "com/mindpalace/engine/GameEngine.java").read_text(encoding="utf-8", errors="replace")
    overlay = src.split("private void renderHelpOverlay", 1)[1]           # the real overlay, not an unrelated "CONTROLS" string
    block = overlay.split("=== CONTROLS ===", 1)[1].split("=== AGENTS ===", 1)[0]
    block = block.split(chr(10), 1)[1]       # drop the rest of the marker's own line (its closing quote)
    rows = {}
    for s in re.findall(r'"([^"]*)"', block):
        if not re.search(r"\w", s):
            continue
        for part in re.split(r"\s{2,}", s.strip()):
            if ":" in part:
                k, v = part.split(":", 1)
                rows[k.strip()] = v.strip()
    return rows


MOUSE_LABELS = {"MOUSE", "LEFT CLICK", "CLICK", "RIGHT CLICK", "MOUSE DRAG", "ARROW KEYS", ""}


def expand(label):
    if label.strip().strip("`").upper() in MOUSE_LABELS:
        return []                                  # mouse input has no GLFW_KEY_* handler to cross-check
    if label.strip().strip("`").upper() in ALIASES:
        return ALIASES[label.strip().strip("`").upper()]
    out = []
    for piece in re.split(r"\s*(?:/|,|\+)\s*", label) if label not in ("/", "`/`") else [label]:
        piece = piece.strip().strip("`").upper()
        if piece in MOUSE_LABELS:
            continue
        out += ALIASES.get(piece, ALIASES.get("`" + piece + "`", [piece]))
    return out


def main(argv):
    code = code_keys()
    if "1" in code and "9" in code:                 # `for (k = KEY_1; k <= KEY_9; k++)` names only the ends of 1..9
        for d in "23456789":
            code.setdefault(d, code["1"][:1])
    readme, helps = readme_keys(), help_keys()
    doc = defaultdict(lambda: {"readme": [], "help": []})
    for label, action in readme.items():
        for k in expand(label):
            doc[k]["readme"].append(f"{label}: {action}")
    for label, action in helps.items():
        for k in expand(label):
            doc[k]["help"].append(f"{label}: {action}")

    rows, undocumented, dead, drift = [], [], [], []
    for k in sorted(set(code) | set(doc), key=lambda x: (not x.startswith("F"), x)):
        c, d = code.get(k, []), doc.get(k)
        status = []
        if k in NOT_CONTROLS and not d:
            status.append("navigation/text-entry (not a documented control)")
        elif c and not d:
            status.append("UNDOCUMENTED"); undocumented.append(k)
        elif d and not c:
            status.append("NO HANDLER FOUND"); dead.append(k)
        elif d and (not d["readme"] or not d["help"]) and not all("ui/DressingRoom.java" in x for x in c):
            status.append("documented in only one place (%s)" % ("README" if d["readme"] else "help overlay"))
            drift.append(k)
        else:
            status.append("ok")
        rows.append((k, status[0], c, d))

    out = ["# Instruction inventory (MP-001)", "",
           "Generated by `tools/instruction_inventory.py`; re-run after changing any key handling or the help text.", "",
           f"- Keys referenced in code: {len(code)}",
           f"- Handled but undocumented: **{len(undocumented)}** {undocumented}",
           f"- Documented but no handler found: **{len(dead)}** {dead}",
           f"- Documented in only one of README/help: **{len(drift)}** {drift}", "",
           "| Key | Status | Documented as | Handled at |", "|---|---|---|---|"]
    for k, st, c, d in rows:
        doc_txt = "; ".join((d["readme"] + ["help: " + x for x in d["help"]])) if d else ""
        out.append(f"| {k} | {st} | {doc_txt[:110]} | {', '.join(c[:2])}{' ...' if len(c) > 2 else ''} |")
    shared = sorted(k for k, d in doc.items() if len({x.split(':', 1)[1].strip() for x in d["help"] + d["readme"]}) > 2)
    out += ["", "## Keys with several different documented meanings", ""]
    out += [f"- **{k}**: " + " | ".join(doc[k]["readme"] + ["help: " + x for x in doc[k]["help"]]) for k in shared] or ["(none)"]
    (ROOT / "reports").mkdir(exist_ok=True)
    (ROOT / "reports" / "instruction_inventory.md").write_text("\n".join(out) + "\n", encoding="utf-8")
    print(f"keys in code {len(code)}; undocumented {undocumented}; no handler {dead}; one-place docs {drift}")
    return 1 if ("--strict" in argv and (undocumented or dead)) else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
