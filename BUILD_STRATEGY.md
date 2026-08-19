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

Output: `target/mindpalace-1.0.0.jar` (~21 MB, shaded with LWJGL natives).

**Jar lock rule:** the running game holds the jar. Before rebuild, kill java:
```bash
wmic process where "name='java.exe'" get processid | grep -E "[0-9]" | while read p; do taskkill /F /PID $p; done
```

## Self-test (the canonical gate)

```bash
"$JAVA_HOME/bin/java" -jar target/mindpalace-1.0.0.jar --selftest
```

13 checks: world build, book raycast, teleporter pads, agents, crystals, KG,
font, editor-open, teleporter destinations, ESC menu, bloom, map toggle,
immediate chat. Exit 0 = green. **This is the source of truth** — the system
verification tracker is stale (replays a deleted `hermes-verify-raycast.py`).

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

The jar is NOT committed to git (`.gitignore` has `target/`). It ships as a
GitHub release asset on `v1.0.0` (release id 372054705). To refresh:

```bash
TOKEN=$(printf "protocol=https\nhost=github.com\n\n" | \
  "C:/Users/viper/AppData/Local/hermes/git/mingw64/bin/git-credential-manager.exe" get | \
  grep -E "^password=" | cut -d= -f2-)
# delete the existing asset, then:
curl -s -X POST -H "Authorization: token $TOKEN" \
  -H "Content-Type: application/java-archive" \
  --data-binary "@target/mindpalace-1.0.0.jar" \
  "https://uploads.github.com/repos/chrisalunlloyd2-sudo/mindpalace/releases/372054705/assets?name=mindpalace-1.0.0.jar"
```

GitHub rejects duplicate asset names (HTTP 422) — delete the old asset first.

## Commit discipline

- Commit + push BEFORE any risky change when the build is green.
- `git push origin main` works via git-credential-manager (no gh CLI auth).
- `playtest*.log` and `target/` are gitignored.
