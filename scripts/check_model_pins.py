#!/usr/bin/env python3
"""MP-042 model-pin validator.

Reads config/model_pins.json and checks two things, so a cron job fails
loud instead of silently skipping when "the global model drifted"
(the MP-042/#187 symptom):

  1. in_game pins still match ModelConfig.java (catches the manifest and
     the real Java source drifting apart from each other).
  2. every pinned model (in_game + cron_jobs) is actually present in
     `ollama list` on this machine (catches the model itself going
     missing/renamed — e.g. an `ollama rm` cache clear, the exact
     landmine AGENTS.md already documents for a different script).

No network calls. `ollama list` is a local subprocess call only; if the
`ollama` binary isn't on PATH, that check is skipped (reported, not fatal)
rather than crashing a cron job that doesn't need it.

Usage:
    python scripts/check_model_pins.py [--pins config/model_pins.json] [--repo-dir .]
Exit 0 = all pins consistent and installed; 1 = drift or missing model found.
"""

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path

MODEL_CONFIG_JAVA = "src/main/java/com/mindpalace/agent/ModelConfig.java"

# Maps the Java constant name to the model_pins.json in_game key.
JAVA_FIELD_TO_PIN_KEY = {
    "TOOL_MODEL": "tool",
    "CRITIC_MODEL": "critic",
    "TIE_MODEL": "tie_breaker",
    "CODE_MODEL": "code_suggest",
    "CHAT_MODEL": "chat",
    "EMBED_MODEL": "embed",
}

FIELD_RE = re.compile(r'public static final String (\w+)\s*=\s*"([^"]+)"')


def parse_model_config_java(repo_dir):
    path = Path(repo_dir) / MODEL_CONFIG_JAVA
    if not path.is_file():
        return None, f"{MODEL_CONFIG_JAVA} not found under {repo_dir}"
    values = {}
    for match in FIELD_RE.finditer(path.read_text(encoding="utf-8")):
        values[match.group(1)] = match.group(2)
    return values, None


class OllamaUnavailable(Exception):
    """ollama isn't installed here at all — not every machine has it, so this is skip-worthy, not a failure."""


class OllamaCheckFailed(Exception):
    """ollama IS present but the listing call itself failed — that's a real problem, not something to wave through."""


def ollama_list():
    """Returns (names, warning). Raises OllamaUnavailable/OllamaCheckFailed on the two different failure modes."""
    try:
        result = subprocess.run(["ollama", "list"], capture_output=True, text=True, timeout=15)
    except FileNotFoundError:
        raise OllamaUnavailable("ollama binary not on PATH — skipping installed-model check")
    except Exception as e:
        raise OllamaCheckFailed(f"could not run `ollama list`: {e}")
    if result.returncode != 0:
        raise OllamaCheckFailed(f"`ollama list` exited {result.returncode}: {result.stderr.strip()}")
    # First column of each non-header line is the model name (tag included).
    names = set()
    for line in result.stdout.splitlines()[1:]:
        parts = line.split()
        if parts:
            names.add(parts[0])
    return names


def _model_installed(model, installed):
    """Match a pin against `ollama list` output, tolerating an implicit :latest tag on either side."""
    if model in installed:
        return True
    if ":" not in model and f"{model}:latest" in installed:
        return True
    if model.endswith(":latest") and model[: -len(":latest")] in installed:
        return True
    return False


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--pins", default="config/model_pins.json")
    parser.add_argument("--repo-dir", default=".")
    args = parser.parse_args()

    pins_path = Path(args.pins)
    if not pins_path.is_file():
        print(f"FAIL  {pins_path} not found")
        sys.exit(1)
    pins = json.loads(pins_path.read_text(encoding="utf-8"))

    problems = []

    # 1. in_game pins vs ModelConfig.java
    java_values, err = parse_model_config_java(args.repo_dir)
    if err:
        print(f"WARN  {err} (skipping drift check against ModelConfig.java)")
    else:
        for java_field, pin_key in JAVA_FIELD_TO_PIN_KEY.items():
            java_val = java_values.get(java_field)
            pin_val = pins.get("in_game", {}).get(pin_key)
            if java_val is None:
                problems.append(f"ModelConfig.java no longer defines {java_field} (manifest expects it)")
            elif pin_val != java_val:
                problems.append(
                    f"in_game.{pin_key} = '{pin_val}' in {pins_path.name} but "
                    f"ModelConfig.java {java_field} = '{java_val}' — update one to match the other"
                )

    # 2. every pinned model actually installed
    try:
        installed = ollama_list()
    except OllamaUnavailable as e:
        print(f"WARN  {e}")
    except OllamaCheckFailed as e:
        # ollama IS on this machine but the listing call itself is broken —
        # that's not "nothing to check", it's "couldn't check", so it must
        # fail the gate rather than silently PASS-ing (PR #281 review).
        problems.append(str(e))
    else:
        all_pins = dict(pins.get("in_game", {}))
        for job, spec in pins.get("cron_jobs", {}).items():
            all_pins[f"cron_jobs.{job}"] = spec.get("model")
        for role, model in all_pins.items():
            if model and not _model_installed(model, installed):
                problems.append(f"{role} is pinned to '{model}' but it is not in `ollama list` on this machine")

    if problems:
        print("FAIL  model pin check found drift:")
        for p in problems:
            print(f"      - {p}")
        sys.exit(1)

    print("PASS  model pins consistent with ModelConfig.java and (where checkable) installed locally")
    sys.exit(0)


if __name__ == "__main__":
    main()
