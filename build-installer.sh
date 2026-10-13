#!/usr/bin/env bash
# build-installer.sh — build a native Windows installer for MindPalace via jpackage.
#
# Produces a .exe installer that:
#   - installs to C:\Program Files\MindPalace (or user-chosen dir)
#   - registers a Start Menu shortcut
#   - ships a bundled JRE (no Java needed on the target machine)
#   - includes an uninstaller (Add/Remove Programs entry)
#
# The "warm welcoming GUI" (file-location chooser, accessory picker for Ollama +
# models) is a Phase-H enhancement that needs Inno Setup or WiX — see
# INSTALLER.md. This script gives a fully working installer TODAY.
set -euo pipefail

REPO="/c/Users/viper/AIGEN_SYS/repos/mindpalace"
# jpackage is a native Windows exe — it needs Windows-style paths, not MSYS paths.
REPO_WIN="C:\\Users\\viper\\AIGEN_SYS\\repos\\mindpalace"
JAVA_HOME="C:/Program Files/Java/jdk-17"
JPACKAGE="$JAVA_HOME/bin/jpackage.exe"
# Resolve jar by glob — the pom version moves (1.0.0 -> 1.1.0-beta1 -> ...)
# and hardcoded names silently break the installer (same bug class as the
# jar-swap fix cbb33ad).
JAR=$(ls "$REPO"/target/mindpalace-*.jar 2>/dev/null | grep -vE 'original-|-shaded' | head -1)
if [ -z "$JAR" ]; then
    echo "no built jar in target/ (build step below produces it) — will retry after build"
    JAR=""
fi
APP_NAME="MindPalace"
# Derive the app version from the resolved jar name (mindpalace-<ver>.jar).
if [ -n "$JAR" ]; then
    APP_VERSION=$(basename "$JAR" .jar | sed 's/^mindpalace-//')
else
    APP_VERSION=$(sed -n 's|<version>\(.*\)</version>|\1|p' "$REPO/pom.xml" | head -1)
fi
MAIN_CLASS="com.mindpalace.Main"
OUT_DIR="$REPO/installer"
OUT_DIR_WIN="C:\\Users\\viper\\AIGEN_SYS\\repos\\mindpalace\\installer"
ICON="$REPO/MindPalace.ico"          # fallback probe
# jpackage is native Windows: icon must be a Windows path (C:/...), never MSYS /c/...
ICON_WIN="C:\\Users\\viper\\AIGEN_SYS\\repos\\mindpalace\\installer\\MindPalace\\MindPalace.ico"

cd "$REPO"

# 1. Ensure the jar is built
if [ -z "$JAR" ]; then
    echo "jar not found — building first..."
    export M2_HOME="C:/ProgramData/chocolatey/lib/maven/apache-maven-3.9.16"
    "$JAVA_HOME/bin/java" -cp "$M2_HOME/boot/plexus-classworlds-2.11.0.jar" \
      "-Dclassworlds.conf=$M2_HOME/bin/m2.conf" \
      "-Dmaven.home=$M2_HOME" \
      "-Dmaven.multiModuleProjectDirectory=$REPO" \
      org.codehaus.plexus.classworlds.launcher.Launcher clean package
    JAR=$(ls "$REPO"/target/mindpalace-*.jar 2>/dev/null | grep -vE 'original-|-shaded' | head -1)
    APP_VERSION=$(basename "$JAR" .jar | sed 's/^mindpalace-//')
fi
[ -n "$JAR" ] || { echo "build ran but no jar in target/ — aborting"; exit 1; }
JAR_NAME=$(basename "$JAR")

mkdir -p "$OUT_DIR"

# 2. Build the installer (EXE type, bundled runtime)
ICON_ARG=()
[ -f "$REPO/installer/MindPalace/MindPalace.ico" ] && ICON_ARG=(--icon "$ICON_WIN")

"$JPACKAGE" \
  --type exe \
  --name "$APP_NAME" \
  --app-version "$APP_VERSION" \
  --input "$REPO_WIN\\target" \
  --main-jar "$JAR_NAME" \
  --main-class "$MAIN_CLASS" \
  --dest "$OUT_DIR_WIN" \
  --win-shortcut \
  --win-menu \
  --win-menu-group "MindPalace" \
  --win-dir-chooser \
  --win-per-user-install \
  --vendor "AIGEN_SYS" \
  --description "3D First-Person GitHub Repository Explorer" \
  --java-options "-Dprism.order=sw -Dprism.vsync=false -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -Xms256m -Xmx768m" \
  "${ICON_ARG[@]}"

echo ""
echo "Installer built in: $OUT_DIR"
ls -la "$OUT_DIR"/*.exe 2>/dev/null
