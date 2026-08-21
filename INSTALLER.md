# MindPalace — Windows Installer (Phase H)

## What's done now

`build-installer.sh` produces a **working native .exe installer** via `jpackage`
(JDK 17 ships it). It gives you, out of the box:

- Installs to `C:\Program Files\MindPalace` (or a user-chosen dir via `--win-dir-chooser`)
- Start Menu shortcut + menu group (`--win-shortcut --win-menu`)
- Bundled JRE — the target machine needs **no Java installed**
- Uninstaller + Add/Remove Programs entry (jpackage generates it automatically)
- Per-user install (no admin prompt) via `--win-per-user-install`
- The game's tuned JVM flags baked in (`-Dprism.order=sw`, G1GC, 256m–768m heap)

Run it:

```bash
bash build-installer.sh
# → installer/MindPalace-1.0.0.exe
```

## What's still TBD (the "warm welcoming GUI")

jpackage's installer is functional but plain. The **warm, welcoming GUI** with a
file-location chooser, an accessory picker (Ollama + models), and a branded
welcome screen needs a richer installer toolkit. Two options:

1. **Inno Setup** (recommended, free) — `iscc` script with a custom wizard page
   that offers to install Ollama + pull the 4 MindPalace models as "accessories".
2. **WiX Toolset** — MSI with a custom UI, more control, steeper learning curve.

Neither is installed on this host yet (`iscc`/`candle` not found). To finish the
warm GUI I'd install Inno Setup and write the `.iss` script — say the word and
I'll do it.

## Accessories (Ollama + models)

The installer should optionally:
- Detect/install Ollama (`winget install Ollama.Ollama`)
- Pull the 4 MindPalace models: `llama3.2:1b`, `qwen2.5:0.5b`, `llama3.2:3b`, `nomic-embed-text`

## Intro / options screens

The in-game ESC menu already has pages (video/controls/audio/music/agents). An
**intro splash screen** (title + "press any key") is a separate small feature —
not yet built. Options screens are largely done via the ESC menu.
