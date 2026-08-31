#!/usr/bin/env bash
# e2e.sh — the amazing test flow: build → selftest → E2E waypoint tour → verify.
# Usage: bash e2e.sh [shotdir]   (default: target/e2e-shots)
set -u
REPO="/c/Users/viper/AIGEN_SYS/repos/mindpalace"
JAVA_HOME="C:/Program Files/Java/jdk-17"
MVN="C:\\ProgramData\\chocolatey\\lib\\maven\\apache-maven-3.9.16\\bin\\mvn.cmd"
DIR="${1:-$REPO/target/e2e-shots}"
# Normalize ONCE to a mixed Windows path (C:/..., forward slashes): java.exe
# and Windows Python both resolve a literal "/c/..." as "C:\c\..." (current-
# drive-relative), so the tour used to write shots into a C:\c\ shadow dir that
# the rm -rf below never cleaned — stale shots from older runs could then
# satisfy the verify step. The mixed form works for bash tools (rm/mkdir),
# java.exe, and Windows Python alike, so writer and reader share one dir.
DIR=$(cygpath -m "$DIR" 2>/dev/null || echo "$DIR")
cd "$REPO"

echo "=== [1/4] BUILD ==="
export JAVA_HOME
cmd.exe /c "$MVN -DskipTests package" 2>&1 | grep -E "BUILD SUCCESS|BUILD FAILURE|ERROR" | tail -3
grep -q "BUILD FAILURE" /dev/null 2>/dev/null; # noop

echo "=== [2/4] SELFTEST ==="
timeout 110 "$JAVA_HOME/bin/java" -Dprism.order=sw -Dprism.vsync=false \
  -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -Xms256m -Xmx768m \
  -jar target/mindpalace-1.0.0.jar --selftest > /tmp/e2e_selftest.log 2>&1
grep -a "RESULT" /tmp/e2e_selftest.log | tail -1

echo "=== [3/4] E2E WAYPOINT TOUR ==="
rm -rf "$DIR"; mkdir -p "$DIR"
timeout 120 "$JAVA_HOME/bin/java" -Dprism.order=sw -Dprism.vsync=false \
  -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -Xms256m -Xmx768m \
  -jar target/mindpalace-1.0.0.jar --e2e "$DIR" > /tmp/e2e_tour.log 2>&1
grep -aE "\[E2E\]|\[E2E-SHOT\]" /tmp/e2e_tour.log

echo "=== [4/4] VERIFY SHOTS (exist + non-black) ==="
python - "$DIR" <<'PYEOF'
import sys, os, struct, zlib
d = sys.argv[1]
labels = ["01_spawn_view","02_rotor_rings","03_turing_tape","04_banburismus_gauge","05_main_hall",
          "06_room_doorway","07_todo_crystals","08_hall_lookback","09_agents","10_portal_pad"]
fail = 0
def load_png(path):
    with open(path, "rb") as f:
        data = f.read()
    w, h = struct.unpack(">II", data[16:24])
    idat = b""
    i = 8
    while i < len(data):
        ln = struct.unpack(">I", data[i:i+4])[0]
        typ = data[i+4:i+8]
        if typ == b"IDAT": idat += data[i+8:i+8+ln]
        i += 12 + ln
    raw = zlib.decompress(idat)
    stride = 1 + w * 4  # RGBA8 output of LWJGL Screenshot
    return w, h, raw, stride
def px_at(raw, stride, x, y):
    o = y * stride + 1 + x * 4
    return raw[o], raw[o+1], raw[o+2]
for lbl in labels:
    files = [f for f in os.listdir(d) if f.startswith(lbl)] if os.path.isdir(d) else []
    if not files:
        print(f"MISSING {lbl}"); fail += 1; continue
    w, h, raw, stride = load_png(os.path.join(d, files[0]))
    n = 0; total = 0
    # Full-frame sample (every 8th px, every 4th row) — the old top-40-rows
    # sample read the dark hallway ceiling/sky and flagged every shot
    # TOO-DARK (mean 0.3–2.3) even while features rendered fine.
    for row in range(0, h, 4):
        base = row * stride + 1
        for x in range(0, w, 8):
            o = base + x * 4
            if o + 2 < len(raw):
                total += raw[o] + raw[o+1] + raw[o+2]
                n += 2
    mean = total / max(n, 1)
    # Black-frame detector: a dead-GPU/failed render is ~0.0; the dimmest real
    # interior shot (07_todo_crystals) measures ~1.5 full-frame. 1.0 splits
    # them with margin. (The old 6.0 was calibrated for top-rows sampling of
    # bright scenes and flagged every dim hallway shot.)
    status = "OK" if mean > 1.0 else "TOO-DARK"
    if mean <= 1.0: fail += 1
    print(f"{lbl}: {files[0]} {w}x{h} brightness={mean:.1f} {status}")

# Portal color-pair hue assertion — floor 0's pad (Amber/Cerulean pair) must
# show BOTH hue families in the 10_portal_pad shot: the amber ring (warm,
# r-dominant) and the cerulean beam/pad (cool, b-dominant). Sampled in the
# middle band of the center third — the camera sits 6m from the pad aiming
# 12° down, so the ring lands bottom-center and the beam fills the middle.
# The cerulean window is relaxed on green (bloom blows the beam core toward
# white, leaving b-dominant fringes) but keeps b>r+40 so pure-blue noise
# (crystal glow) doesn't count.
portal = [f for f in os.listdir(d) if f.startswith("10_portal_pad")] if os.path.isdir(d) else []
if not portal:
    print("MISSING 10_portal_pad hue check"); fail += 1
else:
    w, h, raw, stride = load_png(os.path.join(d, portal[0]))
    amber = cerulean = 0
    for y in range(int(h*0.25), int(h*0.8), 2):
        for x in range(w//3, 2*w//3, 2):
            r, g, b = px_at(raw, stride, x, y)
            if r > 120 and r > g + 30 and r > b + 50: amber += 1        # warm ring
            if b > 120 and b > r + 40 and g > 50: cerulean += 1         # cool beam
    ok = amber > 100 and cerulean > 100
    print(f"portal color pair: amber={amber} cerulean={cerulean} "
          + ("OK" if ok else "HUE-FAIL"))
    if not ok: fail += 1
print("VERIFY-PASS" if fail == 0 else f"VERIFY-FAIL ({fail})")
sys.exit(0 if fail == 0 else 1)
PYEOF