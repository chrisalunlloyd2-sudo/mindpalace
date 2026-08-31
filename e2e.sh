#!/usr/bin/env bash
# e2e.sh — the amazing test flow: build → selftest → E2E waypoint tour → verify.
# Usage: bash e2e.sh [shotdir]   (default: target/e2e-shots)
set -u
REPO="/c/Users/viper/AIGEN_SYS/repos/mindpalace"
JAVA_HOME="C:/Program Files/Java/jdk-17"
MVN="C:\\ProgramData\\chocolatey\\lib\\maven\\apache-maven-3.9.16\\bin\\mvn.cmd"
DIR="${1:-$REPO/target/e2e-shots}"
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
labels = ["01_spawn_view","02_rotor_rings","03_turing_tape","04_main_hall","05_room_doorway",
          "06_todo_crystals","07_hall_lookback","08_agents"]
fail = 0
for lbl in labels:
    files = [f for f in os.listdir(d) if f.startswith(lbl)] if os.path.isdir(d) else []
    if not files:
        print(f"MISSING {lbl}"); fail += 1; continue
    path = os.path.join(d, files[0])
    # PNG brightness check without deps: parse IHDR, decompress a sample of IDAT,
    # compute mean luminance of the first rows.
    with open(path, "rb") as f:
        data = f.read()
    w, h = struct.unpack(">II", data[16:24])
    bitdepth, ctype = data[24], data[25]
    # gather IDAT chunks
    idat = b""
    i = 8
    while i < len(data):
        ln = struct.unpack(">I", data[i:i+4])[0]
        typ = data[i+4:i+8]
        if typ == b"IDAT": idat += data[i+8:i+8+ln]
        i += 12 + ln
    raw = zlib.decompress(idat)
    stride = 1 + w * 4  # assume RGBA8 output of LWJGL Screenshot
    n = 0; total = 0
    for row in range(0, min(h, 40)):
        base = row * stride + 1
        for x in range(0, w, 16):
            px = base + x * 4
            if px + 2 < len(raw):
                total += raw[px] + raw[px+1] + raw[px+2]
                n += 2
    mean = total / max(n, 1)
    status = "OK" if mean > 6.0 else "TOO-DARK"
    if mean <= 6.0: fail += 1
    print(f"{lbl}: {files[0]} {w}x{h} brightness={mean:.1f} {status}")
print("VERIFY-PASS" if fail == 0 else f"VERIFY-FAIL ({fail})")
sys.exit(0 if fail == 0 else 1)
PYEOF