#!/bin/bash
# P1-A build wrapper: capture full mvn output so a gate-window run is never wasted.
export JAVA_HOME="C:\\Program Files\\Java\\jdk-17"
export PATH="$JAVA_HOME/bin:$PATH"
cd "C:/Users/viper/AIGEN_SYS/repos/mindpalace/.worktrees/t_d9067d75"
export OLLAMA_NO_CLOUD=1
{
  echo "=== build $(date +%F' '%T) ==="
  mvn.cmd clean package -DskipTests
  rc=$?
  echo "=== exit=$rc jar=$(ls target/mindpalace-*.jar 2>/dev/null | grep -vE 'original-|-shaded' | head -1) ==="
  if [ $rc -eq 0 ] && compgen -G 'target/mindpalace-*.jar' >/dev/null; then
    jarpath=$(ls target/mindpalace-*.jar 2>/dev/null | grep -vE 'original-|-shaded' | head -1)
    java -jar "$jarpath" --selftest 2>&1 | tail -60
    echo "=== selftest_exit=$? ==="
  fi
} > "C:/Users/viper/AIGEN_SYS/todo_management/handoff/t_d9067d75_build.log" 2>&1
echo "wrapper done rc=$rc"
