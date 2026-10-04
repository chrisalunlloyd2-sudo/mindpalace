#!/bin/bash
# MindPalace Maven wrapper
export JAVA_HOME="C:/Program Files/Java/jdk-17"
export M2_HOME="C:/ProgramData/chocolatey/lib/maven/apache-maven-3.9.16"
exec "$JAVA_HOME/bin/java" \
  -cp "$M2_HOME/boot/plexus-classworlds-2.11.0.jar" \
  -Dclassworlds.conf="$M2_HOME/bin/m2.conf" \
  -Dmaven.home="$M2_HOME" \
  -Dmaven.multiModuleProjectDirectory="$(pwd -W 2>/dev/null || pwd)" \
  org.codehaus.plexus.classworlds.launcher.Launcher \
  "$@"
