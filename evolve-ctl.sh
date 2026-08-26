#!/usr/bin/env bash
# evolve-ctl — live CLI for the MindPalace genetic-audio GA.
# Writes a control.json the running game polls each evolution tick.
# Usage:
#   evolve-ctl rate 0.4            set mutation rate
#   evolve-ctl sigma 0.2           set mutation strength
#   evolve-ctl loud 0.3            set loudness weight
#   evolve-ctl harsh 0.35          set harshness weight
#   evolve-ctl steady 0.2          set steadiness weight
#   evolve-ctl novel 0.15          set novelty weight
#   evolve-ctl target 0.0          set target weight
#   evolve-ctl refresh 3           inject 3 random newcomers
#   evolve-ctl status              show current control file
#   evolve-ctl clear               clear pending control
# Multiple keys may be combined:  evolve-ctl rate 0.4 sigma 0.2
set -euo pipefail

CTL="$HOME/AIGEN_SYS/mindpalace_memory/evolution/control.json"

# Map CLI key -> JSON field name.
field() {
  case "$1" in
    rate)   echo mutationRate ;;
    sigma)  echo mutationSigma ;;
    loud)   echo loudness ;;
    harsh)  echo harshness ;;
    steady) echo steadiness ;;
    novel)  echo novelty ;;
    target) echo target ;;
    refresh) echo refresh ;;
    *) echo "" ;;
  esac
}

# ── status / clear ──
if [[ "${1:-}" == "status" ]]; then
  if [[ -f "$CTL" ]]; then cat "$CTL"; echo; else echo "(no pending control)"; fi
  exit 0
fi
if [[ "${1:-}" == "clear" ]]; then
  rm -f "$CTL"; echo "control cleared"; exit 0
fi

# ── build JSON from key/value pairs ──
[[ $# -ge 2 ]] || { echo "usage: evolve-ctl <key> <value> [<key> <value> ...]"; exit 1; }
mkdir -p "$(dirname "$CTL")"
pairs=()
while [[ $# -ge 2 ]]; do
  k="$1"; v="$2"; shift 2
  f="$(field "$k")"
  [[ -n "$f" ]] || { echo "unknown key: $k"; exit 1; }
  # numeric check (refresh is int, rest float)
  if [[ "$f" == "refresh" ]]; then
    [[ "$v" =~ ^[0-9]+$ ]] || { echo "refresh needs an integer"; exit 1; }
    pairs+=("\"$f\": $v")
  else
    [[ "$v" =~ ^[0-9]*\.?[0-9]+$ ]] || { echo "$k needs a number"; exit 1; }
    pairs+=("\"$f\": $v")
  fi
done

# Merge with any existing pending control (preserve unset fields).
if [[ -f "$CTL" ]]; then
  existing="$(cat "$CTL")"
  # strip braces
  existing="${existing#\{}"; existing="${existing%\}}"
  [[ -z "$existing" ]] || pairs=("$existing" "${pairs[@]}")
fi

printf '{\n  %s\n}\n' "$(IFS=','; echo "${pairs[*]}")" > "$CTL"
echo "control written -> $CTL"
cat "$CTL"
