# MindPalace — Windows Installer (Phase H)

## Two installers, two tiers

| | `build-installer.sh` | `build-warm-installer.sh` |
|---|---|---|
| Tool | jpackage + WiX | jpackage + Inno Setup |
| Output | `MindPalace-1.0.0.exe` | `MindPalace-Setup-1.0.0.exe` |
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
# → installer/MindPalace-Setup-1.0.0.exe
```

## Accessories (Ollama + models)

The installer's post-install step runs only if the user ticks the boxes:
- `winget install --id Ollama.Ollama` (silent)
- `ollama pull` for each of the 4 models (~4.8 GB total)

## Intro / options screens

The in-game ESC menu already has pages (video/controls/audio/music/agents). An
**intro splash screen** (title + "press any key") is a separate small feature —
not yet built. Options screens are largely done via the ESC menu.
