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
JAR="$REPO/target/mindpalace-1.0.0.jar"
APP_NAME="MindPalace"
APP_VERSION="1.0.0"
MAIN_CLASS="com.mindpalace.Main"
OUT_DIR="$REPO/installer"
OUT_DIR_WIN="C:\\Users\\viper\\AIGEN_SYS\\repos\\mindpalace\\installer"
ICON="$REPO/installer/MindPalace/MindPalace.ico"

cd "$REPO"

# 1. Ensure the jar is built
if [ ! -f "$JAR" ]; then
    echo "jar not found — building first..."
    export M2_HOME="C:/ProgramData/chocolatey/lib/maven/apache-maven-3.9.16"
    "$JAVA_HOME/bin/java" -cp "$M2_HOME/boot/plexus-classworlds-2.11.0.jar" \
      "-Dclassworlds.conf=$M2_HOME/bin/m2.conf" \
      "-Dmaven.home=$M2_HOME" \
      "-Dmaven.multiModuleProjectDirectory=$REPO" \
      org.codehaus.plexus.classworlds.launcher.Launcher clean package
fi

mkdir -p "$OUT_DIR"

# 2. Build the installer (EXE type, bundled runtime)
ICON_ARG=()
[ -f "$ICON" ] && ICON_ARG=(--icon "$ICON")

"$JPACKAGE" \
  --type exe \
  --name "$APP_NAME" \
  --app-version "$APP_VERSION" \
  --input "$REPO_WIN\\target" \
  --main-jar "mindpalace-1.0.0.jar" \
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
