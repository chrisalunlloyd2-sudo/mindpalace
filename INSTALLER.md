# MindPalace — Windows Installer (Phase H)

## Two installers, two tiers

| | `build-installer.sh` | `build-warm-installer.sh` |
|---|---|---|
| Tool | jpackage + WiX | jpackage + Inno Setup |
| Output | `MindPalace-1.1.0-beta1.exe` | `MindPalace-Setup-1.1.0-beta1.exe` |
| Look | plain | **warm, branded welcome page** |
| File-location chooser | yes (`--win-dir-chooser`) | yes (default `{autopf}\MindPalace`) |
| Accessory picker (Ollama + models) | no | **yes** (opt-in tasks) |
| Start Menu + desktop shortcut | Start Menu only | **both** |
| Uninstaller | yes | yes |

## The warm installer (recommended)

`build-warm-installer.sh` runs the full pipeline:

1. **jar** — Maven build (if missing)
2. **app-image** — jpackage bundles a JRE + launcher (no Java needed on target)
3. **wizard banner** — `gen-wizard-bmp.py` renders a warm gradient + "MIND PALACE" title
4. **Inno Setup** — `MindPalace.iss` produces the branded `.exe`

The `.iss` script adds:
- a warm welcome page (gradient banner + title)
- a file-location chooser (defaults to `C:\Program Files\MindPalace`)
- an **accessory picker**: optional Ollama install (`winget`) + the 4 MindPalace
  models (`llama3.2:1b`, `qwen2.5:0.5b`, `llama3.2:3b`, `nomic-embed-text`)
- Start Menu + desktop shortcuts, uninstaller, "launch now" checkbox

Build it:

```bash
bash build-warm-installer.sh
# → installer/MindPalace-Setup-1.1.0-beta1.exe
```

## Release/versioning workflow

Installer naming/version stamps are managed by the release pipeline in
`scripts/release_phase.sh`:
- `--dry-run` previews version/stamp changes only
- `--rehearse` runs full release gates locally and reverts version edits
- `--cut` performs the real release (commit/tag/push + GitHub release)

Run the release script from repo root and use `--dry-run` before
`--rehearse`/`--cut`. See `AGENTS.md` for the canonical release invocation
sequence used by sessions.

## Accessories (Ollama + models)

The installer's post-install step runs only if the user ticks the boxes:
- `winget install --id Ollama.Ollama` (silent)
- `ollama pull` for each of the 4 models (~4.8 GB total)

## Intro / options screens

The in-game ESC menu already has pages (video/controls/audio/music/agents). An
**intro splash screen** (title + "press any key") is a separate small feature —
not yet built. Options screens are largely done via the ESC menu.

## Clean-machine install test (step 110, #140)

Docs-only QA procedure, following the skeleton shape from closed issue #13
("what a verified install looks like"). Goal: prove the installer carries
everything it needs and the app degrades gracefully on a bare machine.

**Preconditions — the test box must have NONE of:**
- a JDK/JRE on `PATH` or `JAVA_HOME` (installer bundles its own runtime)
- Ollama installed or running on `127.0.0.1:11434`
- any prior MindPalace install (`C:\Program Files\MindPalace`, Start Menu
  entries, `%USERPROFILE%\.mindpalace\`)

**Procedure:**

1. On a clean Windows 10/11 VM (or a throwaway user profile), run
   `installer/MindPalace-Setup-<version>.exe` (warm tier, recommended).
   Accept the default install dir; leave the Ollama accessory boxes **unticked**
   for this first pass (test the no-accessory path).
2. Launch from the Finish dialog / Start Menu shortcut. Verify:
   - app window reaches the boot screen and finishes loading (no console
     crash, no missing-class / missing-JRE errors — this exercises the
     bundled `runtime\`),
   - first boot shows the **first-run welcome banner**
     (`[FirstRun] Welcome to Mind Palace!` + controls line) and writes
     `%USERPROFILE%\.mindpalace\first_run_done` (step-109 wizard; second
     launch must NOT re-print it),
   - the monitor log shows `[update] up to date (<version>)` or a fail-soft
     `[update] unknown (...)` line — never a crash if the network is blocked.
3. Launch the app with `--demo` (fixture palace) and walk into a portal pad:
   palettes render, ESC menu shows the **Theme** page (7) and rotation
   persists to `%USERPROFILE%\.mindpalace\theme.properties` (#139).
4. Re-run the installer over the existing install (upgrade path): version
   stamp advances, old files replaced, `%USERPROFILE%` data survives.
5. Uninstall from Start Menu; confirm `C:\Program Files\MindPalace` is removed
   and `%USERPROFILE%\.mindpalace\` is left intact (user data preservation).
6. Repeat 1–2 with the Ollama accessory **ticked** on a second clean VM:
   winget installs Ollama, `ollama pull` lands the 4 models, and the app's
   model status shows them (requires network + winget available).

**Pass criteria:** steps 1–5 green with zero dev prerequisites present; app
never hard-crashes from missing Ollama — it degrades to the fixture/`--demo`
palace and surfaces the gap; first-run banner fires exactly once; the MSI tier
(`build-installer.sh` section 3, #139) installs per-user without admin.

Record results (VM snapshot ID / date / pass-fail per step) in this file below
or in the release notes of the `scripts/release_phase.sh --cut` run.
