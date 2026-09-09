# DEV_SETUP.md — MindPalace development environment

Everything needed to go from clean machine → running palace, plus the
gotchas that cost us hours. (Windows 10/11 is the dev+target platform.)

## Prerequisites

| Tool | Version | Notes |
|------|---------|-------|
| JDK | 17 (LTS) | `C:/Program Files/Java/jdk-17` on this machine; set `JAVA_HOME` |
| Maven | 3.9.x | choco-installed at `C:/ProgramData/chocolatey/lib/maven/apache-maven-3.9.16` |
| GPU | OpenGL 3.3+ | Intel HD 510 works (software-first: `-Dprism.order=sw`) |
| Git | any | Credential Manager holds the GitHub PAT (never hardcode tokens) |
| Ollama | any | optional — 11 SLMs local; game runs fine without (agents idle) |
| Python | 3.11 + Pillow | only for `scripts/test_bot.py` shot verification |

## First build

```bash
export JAVA_HOME="C:/Program Files/Java/jdk-17"
mvn -DskipTests package          # → BUILD SUCCESS gate
java -jar target/mindpalace-1.0.0.jar --selftest   # → 40 passed, 0 failed
```

## Run

```bash
java -Dprism.order=sw -Dprism.vsync=false \
  -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -Xms256m -Xmx768m \
  -jar target/mindpalace-1.0.0.jar
```

Flags matter: `prism.order=sw` keeps Intel HD 510 stable; the G1GC floor
(256m/768m) matches the memory contract. Ollama must be running
(`localhost:11434`) for agents to speak; everything else works without it.

## Environment / auth

- **GitHub PAT**: loaded automatically from Windows Credential Manager
  (`git credential-manager get` → github.com). Never put tokens in files.
- No env vars required. Optional: `GH_TOKEN` for the `gh` CLI when posting
  step-log comments manually.

## The frozen-jar rule (gotcha #1)

The hourly auto-sync cron rebuilds `target/` and restarts the game. If you
launch the game from `target/`, a mid-session rebuild swaps the jar under
you → silent freeze. Always:

```bash
cp target/mindpalace-1.0.0.jar mindpalace-live.jar
java -jar mindpalace-live.jar   # run this
```

`scripts/bot_swarm.sh launch` enforces the whole ritual (one-instance
guard + frozen copy).

## Common gotchas

1. **Build fails with jar lock** — a java process still holds target/;
   sweep first (`scripts/bot_swarm.sh sweep`).
2. **Selftest hangs on "WorldBuilder"** — another JVM holds the Ollama
   model gate; only one model call in flight system-wide (cold-shot).
   Kill the other java first.
3. **MSYS vs Windows paths** — bash tools need `/c/...`; jpackage, java.exe
   and native tools need `C:/...` (never `C:\c\...`). This bit e2e.sh and
   build-installer.sh before; both now normalize ONCE.
4. **E2E shots land in `C:\c\...`** — if a run "verifies" against stale
   shots, you passed an MSYS path to a Windows exe; use `cygpath -m`.
5. **Console log `?` characters** — the game writes UTF-8 arrows through a
   non-UTF8 codepage; parsers must match literal `?` (scout_bot handles it).
6. **Two dark E2E waypoints are KNOWN** — `07_todo_crystals` (crystal
   visibility timing) and `12_door_prompt` (HUD door-prompt bug). They are
   baseline artifacts, not your regression — the A/B proof lives in the
   step-log.

## One-command dev loop

The Maven analog of `npm run dev` (this is a Maven project — there is no npm):

    bash scripts/dev.sh            # build + run live mode
    bash scripts/dev.sh demo       # build + run --demo (zero auth/network)
    bash scripts/dev.sh selftest   # build + selftest only, exit code = gate

Windows cmd: `scripts\dev.bat` with the same arguments. Verified end-to-end:
`dev.sh selftest` -> build -> frozen-jar copy -> `Demo fixtures loaded: 12 rooms`
-> `RESULT: 40 passed, 0 failed`, EXIT 0.

## Verify your change (the cascade)

```bash
mvn -DskipTests package
java -jar mindpalace-live.jar --selftest
java -jar mindpalace-live.jar --e2e target/e2e-shots
python scripts/test_bot.py --verify-shots target/e2e-shots
bash scripts/cascade_dev.sh Hxx   # ships it + posts evidence
```

## Cron/swarm context (why things restart on their own)

- `MindPalace Auto-Sync` (30m): pull → commit → build → selftest → push,
  relaunches the game on success.
- `MindPalace Health Monitor` (15m): disk/telemetry/process snapshot.
- `MindPalace Scout Bot` (30m): chat-quality metrics + game-down alerts.
- `AIGEN task watch` (hourly): executes ONE `TASK_Hxx_*.md` from
  `todo_management/todo_files/mindpalace/` — the Autonomous Finishing
  Program's step pipeline.