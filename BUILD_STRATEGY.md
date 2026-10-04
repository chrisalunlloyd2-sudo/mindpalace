# MindPalace — Build, Bloom Testing & Release Strategy

## Build (Windows, git-bash)

```bash
cd /c/Users/viper/AIGEN_SYS/repos/mindpalace
export JAVA_HOME="C:/Program Files/Java/jdk-17"
export M2_HOME="C:/ProgramData/chocolatey/lib/maven/apache-maven-3.9.16"
"$JAVA_HOME/bin/java" -cp "$M2_HOME/boot/plexus-classworlds-2.11.0.jar" \
  "-Dclassworlds.conf=$M2_HOME/bin/m2.conf" \
  "-Dmaven.home=$M2_HOME" \
  "-Dmaven.multiModuleProjectDirectory=$PWD" \
  org.codehaus.plexus.classworlds.launcher.Launcher clean package
```

Output: `target/mindpalace-<version>.jar` (~21 MB, shaded with LWJGL natives) —
version moves (1.0.0 → 1.1.0-beta1), so resolve by glob:
`JAR="$(ls target/mindpalace-*.jar | grep -vE 'original-|-shaded' | head -1)"`.

**Jar lock rule:** the running game holds the jar. Before rebuild, kill java:
```bash
wmic process where "name='java.exe'" get processid | grep -E "[0-9]" | while read p; do taskkill /F /PID $p; done
```

## Self-test (the canonical gate)

```bash
JAR="$(ls target/mindpalace-*.jar | grep -vE 'original-|-shaded' | head -1)"
"$JAVA_HOME/bin/java" -jar "$JAR" --selftest
```

54 checks (run-report; the source organizes them under 49 numbered sections):
world build, book raycast, teleporter pads, agents, crystals, KG,
font, editor-open, teleporter destinations, ESC menu, bloom, map toggle,
immediate chat — and more covering mouse turn radius, room personality,
solve loop, DePIN economy, model shops, genetics/evolution, telemetry,
quorum tie-breaker, TimeMachine, layout determinism, spawn validation, H-series
(H04/H21/H24/H25/H28/H30/H31). Exit 0 = green. **Count lives in GameEngine.runSelfTest()** —
this file quotes it, not the other way round.

## Bloom testing (the hard-won procedure)

Bloom is the fragile part (Intel HD 510, OpenGL 3.3). The pipeline:
scene FBO (with depth renderbuffer) → bright pass → gaussian blur H+V ping-pong
→ composite. Two historical root causes, both now fixed:

1. **Composite drew into the last blur FBO** (`blurFboA`) instead of the screen
   because `renderPass` left it bound and composite never rebound framebuffer 0.
   Fix: `glBindFramebuffer(GL_FRAMEBUFFER, 0)` before the composite draw.
2. **`glReadPixels` returned 0.0** because the diagnostic used a heap
   `ByteBuffer.wrap(byte[])`; LWJGL requires a DIRECT buffer
   (`BufferUtils.createByteBuffer`), like `Screenshot.java` does.

**How to verify bloom after any change:**
- Run `--selftest`; check the bloom line prints `composite=` > 0.5 (non-black).
- The self-test reads back `debugSceneLuminance()` (scene FBO has content),
  `debugClearTest()` (clear works), and `debugCompositeLuminance()` (screen
  non-black). All three must be sane.
- Live-tune via ESC → Video: bloom intensity (0.0–2.0, default 0.7) and
  threshold (0.0–1.0). No restart needed.

## Release binary upload

Use the release automation script for phase cuts:

```bash
bash scripts/release_phase.sh --rehearse 1.2.0-beta1  # full local gate, no remote writes
bash scripts/release_phase.sh --cut 1.2.0-beta1       # tags + release on green
```

`--cut` performs the version-file bump, build/selftest/e2e/jpackage gates, then
creates the GitHub Release with both assets attached:
- `target/mindpalace-<version>.jar`
- `installer/MindPalace-<version>.exe`

Release notes are generated from commits since the previous version tag.

## Commit discipline

- Commit + push BEFORE any risky change when the build is green.
- `git push origin main` works via git-credential-manager (no gh CLI auth).
- `playtest*.log` and `target/` are gitignored.
