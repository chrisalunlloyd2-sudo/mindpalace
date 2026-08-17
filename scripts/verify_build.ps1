# ============================================================================
# mindpalace - VERIFY + BUILD + RUN   (PowerShell / Windows / Java 17)
# Written by Kernel (Aegis) 2026-08-17  -  ADD-only, safe to rerun
#
# CODE LOGIC - the two commits this verifies:
#
#   ddb684b  TextSanitizer REMOVED (Chris never asked for it)
#            - deleted util/TextSanitizer.java (46 lines)
#            - GitHubClient.fetchFileContent: unwrapped asciiSafe/stripCR -> raw body
#            - BookEditor: 5 call sites unwrapped (snippet, suggestion render,
#              editBuffer, setContent, line render) - byte-identical to pre-763ff49
#
#   14f1e12  STAIRWELL FIX (Chris could not traverse between levels)
#            root cause: every hallway rendered a SOLID END WALL at hw.getEnd().z
#            and stairs were drawn +2.0 BEHIND that wall -> invisible dead-end
#            - removed the end-wall drawCube (kept the start wall)
#            - STAIR_OFFSET 2.0 -> 0.0 (stairs now start at hallway end)
#            - renderStairwell uses the STAIR_OFFSET constant
#            effect: walk to any hallway end, stairs are visible + walkable
#            (the ramp getGroundHeight was already wired in Player:107)
# ============================================================================
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot            # repo root

Write-Host "== 1. GIT STATE ==" -ForegroundColor Cyan
git fetch origin
git status -sb
git log --oneline -4

Write-Host "`n== 2. SHOW THE TWO KERNEL COMMITS ==" -ForegroundColor Cyan
git show --stat ddb684b   # TextSanitizer removal
git show --stat 14f1e12   # stairwell fix

Write-Host "`n== 3. COMPILE (Java 17 via Maven wrapper) ==" -ForegroundColor Cyan
.\mvnw.cmd -q compile
if ($LASTEXITCODE -ne 0) { Write-Host "COMPILE FAILED" -ForegroundColor Red; exit 1 }
Write-Host "compile OK" -ForegroundColor Green

Write-Host "`n== 4. TESTS ==" -ForegroundColor Cyan
.\mvnw.cmd -q test
if ($LASTEXITCODE -ne 0) { Write-Host "TESTS FAILED" -ForegroundColor Red; exit 1 }
Write-Host "tests OK" -ForegroundColor Green

Write-Host "`n== 5. BUILD SHADED JAR ==" -ForegroundColor Cyan
.\mvnw.cmd -q package -DskipTests
if ($LASTEXITCODE -ne 0) { Write-Host "PACKAGE FAILED" -ForegroundColor Red; exit 1 }

Write-Host "`n== 6. LAUNCH THE GAME ==" -ForegroundColor Cyan
java -jar target\mindpalace-1.0.0.jar

# one-liners if you prefer:
#   compile+test only:  .\mvnw.cmd -q clean verify
#   build only:         .\mvnw.cmd -q package -DskipTests
#   run after build:    java -jar target\mindpalace-1.0.0.jar
