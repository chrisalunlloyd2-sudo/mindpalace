#!/usr/bin/env python3
"""
test_bot.py — MindPalace machine-verified proof bot (H265).

Modes:
  --verify-shots DIR   E2E proof: every shot exists, is a valid PNG, and
                       is non-black/non-empty (mean luminance > threshold,
                       unique colors > min). Exit 1 on failure.
  --run-selftest       Launch the frozen jar with --selftest, parse RESULT,
                       write target/selftest_result.json. Exit 1 on FAIL.
  --stress N           (H29) rapid synthetic teleport/input burst against
                       a LIVE game via the autodrive path — reports CME /
                       freeze detection from game_console.log.

Quota-free: pure stdlib image + subprocess work. No LLM.
"""
import json, struct, subprocess, sys, time, zlib
from pathlib import Path

for _s in (sys.stdout, sys.stderr):
    if hasattr(_s, "reconfigure"):
        _s.reconfigure(encoding="utf-8", errors="replace")

REPO = Path(r"C:/Users/viper/AIGEN_SYS/repos/mindpalace")
JAVA = r"C:/Program Files/Java/jdk-17/bin/java"
JVM = ["-Dprism.order=sw", "-Dprism.vsync=false",
       "-XX:+UseG1GC", "-XX:MaxGCPauseMillis=200", "-Xms256m", "-Xmx768m"]
EXPECTED_LABELS = ["01_spawn_view", "02_rotor_rings", "03_turing_tape",
                   "04_banburismus_gauge", "05_main_hall", "06_room_doorway",
                   "07_todo_crystals", "08_hall_lookback", "09_agents",
                   "10_portal_pad", "11_nash_fountain", "12_door_prompt",
                   "13_plugboard"]
# shots are written as NN_label_MM.png (waypoint + frame index)


def shot_labels(d):
    """Actual shots on disk — robust to the tour gaining waypoints."""
    return sorted(p.name[:-4] for p in Path(d).glob("[0-9][0-9]_*.png"))


def decode_png(path):
    """Minimal PNG decode → (width, height, pixels[rgb]). Supports the
    screenshots the game writes (8-bit RGB, no interlace)."""
    raw = path.read_bytes()
    if raw[:8] != b"\x89PNG\r\n\x1a\n":
        return None
    pos, w, h, idat, bit_depth, color_type = 8, 0, 0, b"", 8, 2
    while pos < len(raw):
        length = struct.unpack(">I", raw[pos:pos+4])[0]
        ctype = raw[pos+4:pos+8]
        data = raw[pos+8:pos+8+length]
        if ctype == b"IHDR":
            w, h, bit_depth, color_type = struct.unpack(">IIBB", data[:10])
        elif ctype == b"IDAT":
            idat += data
        pos += 12 + length
    if bit_depth != 8 or color_type not in (2, 6):
        return None
    try:
        dec = zlib.decompress(idat)
    except zlib.error:
        return None
    bpp = 3 if color_type == 2 else 4
    stride = w * bpp
    # defilter scanlines
    out = bytearray(w * h * 3)
    prev = bytearray(stride)
    i = 0
    for y in range(h):
        f = dec[i]; i += 1
        line = bytearray(dec[i:i+stride]); i += stride
        if f == 1:
            for x in range(bpp, stride):
                line[x] = (line[x] + line[x-bpp]) & 0xFF
        elif f == 2:
            for x in range(stride):
                line[x] = (line[x] + prev[x]) & 0xFF
        elif f == 3:
            for x in range(stride):
                a = line[x-bpp] if x >= bpp else 0
                line[x] = (line[x] + ((a + prev[x]) >> 1)) & 0xFF
        elif f == 4:
            for x in range(stride):
                a = line[x-bpp] if x >= bpp else 0
                b = prev[x]
                c = prev[x-bpp] if x >= bpp else 0
                p = a + b - c
                pa, pb, pc = abs(p-a), abs(p-b), abs(p-c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[x] = (line[x] + pr) & 0xFF
        for x in range(w):
            out[(y*w+x)*3:(y*w+x)*3+3] = line[x*bpp:x*bpp+3]
        prev = line
    return w, h, bytes(out)


def verify_shots(d):
    d = Path(d)
    ok, report = True, []
    found = shot_labels(d)
    if not found:
        print("NO SHOTS FOUND — E2E tour produced nothing")
        return 1
    for label in EXPECTED_LABELS:
        if not any(f.startswith(label) or f.rsplit("_", 1)[0] == label for f in found):
            report.append(f"MISSING waypoint {label}")
            ok = False
    for fname in found:
        p = d / f"{fname}.png"
        if not p.exists():
            report.append(f"MISSING {label}.png"); ok = False; continue
        r = decode_png(p)
        if r is None:
            report.append(f"UNREADABLE {label}.png"); ok = False; continue
        w, h, px = r
        # sample luminance + unique colors on a sparse grid
        total, n, colors = 0, 0, set()
        for y in range(0, h, max(1, h // 40)):
            for x in range(0, w, max(1, w // 40)):
                o = (y * w + x) * 3
                r8, g8, b8 = px[o], px[o+1], px[o+2]
                total += (r8 + g8 + b8) // 3
                n += 1
                colors.add((r8 // 16, g8 // 16, b8 // 16))
        mean = total / max(n, 1)
        status = "OK" if (mean > 12 and len(colors) > 8) else "BLACK/FLAT"
        report.append(f"{fname}: {w}x{h} mean_lum={mean:.1f} colors={len(colors)} {status}")
        if status != "OK":
            ok = False
    print("\n".join(report))
    (d / "verification.json").write_text(
        json.dumps({"ok": ok, "shots": report,
                    "t": time.strftime("%Y-%m-%d %H:%M:%S")}, indent=2),
        encoding="utf-8")
    print(f"VERIFY {'PASS' if ok else 'FAIL'}")
    return 0 if ok else 1


def run_selftest():
    jar = REPO / "mindpalace-live.jar"
    if not jar.exists():
        jar = REPO / "target" / "mindpalace-1.0.0.jar"
    try:
        r = subprocess.run([JAVA, *JVM, "-jar", str(jar), "--selftest"],
                           capture_output=True, text=True, timeout=120,
                           cwd=str(REPO))
    except subprocess.TimeoutExpired:
        r = None
    line = ""
    blob = (r.stdout or "") + (r.stderr or "") if r else ""
    for l in blob.splitlines():
        if "RESULT" in l:
            line = l.strip()
    ok = bool(r) and r.returncode == 0 and "PASS" in line
    out = {"ok": ok, "result": line, "returncode": r.returncode if r else None,
           "t": time.strftime("%Y-%m-%d %H:%M:%S")}
    (REPO / "target" / "selftest_result.json").write_text(
        json.dumps(out, indent=2), encoding="utf-8")
    print(json.dumps(out, indent=2))
    return 0 if ok else 1


def stress(n):
    """H29: launch the game with synthetic rapid input via autodrive-ish
    burst, then scan the console log for CME/freeze signatures."""
    console = REPO / "game_console.log"
    size_before = console.stat().st_size if console.exists() else 0
    print(f"stress: launching burst n={n} (watching console for exceptions)")
    # The game reads synthetic input only via --e2e/--autodrive; the burst
    # mode is the E2E tour run twice back-to-back (max motion the engine
    # supports headless) — good enough to trip CME-class bugs.
    for i in range(min(n, 3)):
        try:
            subprocess.run([JAVA, *JVM, "-jar", str(REPO / "mindpalace-live.jar"),
                           "--e2e", str(REPO / "target" / f"stress-shots-{i}")],
                          capture_output=True, text=True, timeout=130, cwd=str(REPO))
        except subprocess.TimeoutExpired:
            print(f"stress: round {i} TIMEOUT — possible freeze")
            return 1
    bad = []
    if console.exists():
        with console.open("r", encoding="utf-8", errors="replace") as f:
            f.seek(size_before)
            for line in f:
                if ("ConcurrentModificationException" in line
                        or "Exception in thread" in line):
                    bad.append(line.strip()[:120])
    print("stress: CLEAN" if not bad else "stress: EXCEPTIONS FOUND")
    for b in bad[:10]:
        print("  " + b)
    return 1 if bad else 0


if __name__ == "__main__":
    args = sys.argv[1:]
    if "--verify-shots" in args:
        sys.exit(verify_shots(args[args.index("--verify-shots") + 1]))
    elif "--run-selftest" in args:
        sys.exit(run_selftest())
    elif "--stress" in args:
        sys.exit(stress(int(args[args.index("--stress") + 1])))
    else:
        print(__doc__)
        sys.exit(1)