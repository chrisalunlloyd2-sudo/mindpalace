#!/usr/bin/env bash
# build-warm-installer.sh — build the warm-GUI installer (Inno Setup).
#
# Pipeline: jar → jpackage app-image → wizard banner → Inno Setup .exe.
# Produces installer/MindPalace-Setup-<version>.exe with a branded welcome page,
# file-location chooser, Ollama + models accessory picker, and shortcuts.
set -euo pipefail

REPO="/c/Users/viper/AIGEN_SYS/repos/mindpalace"
REPO_WIN="C:\\Users\\viper\\AIGEN_SYS\\repos\\mindpalace"
JAVA_HOME="C:/Program Files/Java/jdk-17"
JPACKAGE="$JAVA_HOME/bin/jpackage.exe"
ISCC="/c/Program Files (x86)/Inno Setup 6/ISCC.exe"
WIX="/c/Program Files (x86)/WiX Toolset v3.14/bin"

cd "$REPO"

# Resolve the built jar by glob — the pom version moves (1.0.0 -> 1.1.0-beta1
# -> ...) and hardcoded names silently break the installer (jar-swap bug
# class cbb33ad).
JAR=$(ls target/mindpalace-*.jar 2>/dev/null | grep -vE 'original-|-shaded' | head -1)

# 1. Build the jar if missing
if [ -z "$JAR" ]; then
    echo "jar missing — building..."
    export M2_HOME="C:/ProgramData/chocolatey/lib/maven/apache-maven-3.9.16"
    "$JAVA_HOME/bin/java" -cp "$M2_HOME/boot/plexus-classworlds-2.11.0.jar" \
      "-Dclassworlds.conf=$M2_HOME/bin/m2.conf" "-Dmaven.home=$M2_HOME" \
      "-Dmaven.multiModuleProjectDirectory=$REPO" \
      org.codehaus.plexus.classworlds.launcher.Launcher clean package
    JAR=$(ls target/mindpalace-*.jar 2>/dev/null | grep -vE 'original-|-shaded' | head -1)
fi
[ -n "$JAR" ] || { echo "build ran but no jar in target/ — aborting"; exit 1; }
JAR_NAME=$(basename "$JAR")
APP_VERSION="${JAR_NAME#mindpalace-}"; APP_VERSION="${APP_VERSION%.jar}"

# 2. jpackage app-image (bundled JRE + launcher)
export PATH="$WIX:$PATH"
"$JPACKAGE" --type app-image --name MindPalace --app-version "$APP_VERSION" \
  --input "$REPO_WIN\\target" --main-jar "$JAR_NAME" \
  --main-class com.mindpalace.Main --dest "$REPO_WIN\\installer" \
  --java-options "-Dprism.order=sw -Dprism.vsync=false -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -Xms256m -Xmx768m"

# 3. Warm welcome banner
python gen-wizard-bmp.py

# 4. Inno Setup warm-GUI installer — version injected so the installer name
#    always tracks the pom (MindPalace.iss keeps an #ifndef fallback).
"$ISCC" "/DMyAppVersion=$APP_VERSION" MindPalace.iss

echo ""
echo "Warm installer built: installer/MindPalace-Setup-$APP_VERSION.exe"