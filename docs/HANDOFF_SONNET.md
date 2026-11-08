# Handoff for Sonnet (2026-11-07)

Read `docs/KNOWLEDGE_BASE.md` first, then `NEXT_STEPS_151_250.md`. Take steps
in order and one heavy job at a time. Every model run goes through `budget.py`.

## State at handoff
- main = 8d18aaa. Merged today: #342, #344, #345, #346.
- **#347 is open with CI green** (automatic small roster). It needs the Architect's merge.
- Live game: `--demo` with `-Dmindpalace.voters=llama3.2:1b,qwen2.5:0.5b,deepseek-r1:1.5b`,
  built from 8d18aaa. Previous jars: `mindpalace-live.jar.bak-20261104/-20261107`.
- Watchdog heartbeats every 5 min since 16:42. If they stop, read
  `AIGEN_SYS/reports/watchdog_boot.txt` and `watchdog_stall.txt` first.

## Bug-fix hints (from today's scan)
1. **Loopback `urlopen` stalls under Task Scheduler.** `urllib.request.urlopen` builds
   an SSL context even for `http://127.0.0.1`. Hits:
   - `scripts/monitor_mindpalace.py:84`
   - `scripts/system_maintenance.py:250` (AIGEN_SYS/scripts)
   - Hermes' `scripts/monitor_mindpalace.py`

   Fix: `http.client.HTTPConnection(host, port, timeout=…)`. Template: `~/.aria/engine/ops_health.py: local_get`.
2. **psutil `ppid()` per process is O(n) snapshots.** Use `psutil._psplatform.ppid_map()` once
   (see `ops_health.collect_system_psutil`). Avoid `Process.open_files()` on Windows: it hangs.
3. **Scheduled tasks run at Priority 7.** Low CPU, I/O and memory priority starve on the HDD.
   Re-register at Priority 4 (step 153). Changing scheduled tasks needs the Architect;
   auto mode blocks it.
4. **Relaunch scripts drop game flags.** See `mindpalace-sync.sh:162,169,278` and
   `release_phase.sh:226`. Step 152 adds one shared `GAME_JAVA_FLAGS`.
5. **MP-014 with 3 voters.** If ballots go NO_QUORUM, check the vote ledger for
   ABSTAIN_TIMEOUT on one model. The first suspect is `deepseek-r1:1.5b` with long `<think>`
   output, so cap `num_predict` for votes.
6. **The chat kit echo.** llama3.2:3b sometimes repeats "Rooms near you: …". Step 167.
7. **Line endings.** Repo files are CRLF, some engine files are LF. Patch scripts must
   detect `\r\n` and keep it. Never write Java or Python string escapes through a bash
   heredoc; use a file written by a tool with raw strings, and compile before writing.
8. **Git Bash path mangling.** Use `MSYS_NO_PATHCONV=1` for `tasklist /FO …` and `wmic`.
   Use `cygpath -w` for javac/java classpaths.

## Do not
- Start `ollama serve` by hand, or set OLLAMA_* on a command line.
- Use cloud models inside the game.
- Commit to main directly, or merge without a green `demo-selftest`.
- Print or store tokens. Use `env -u GH_TOKEN -u GITHUB_TOKEN gh …`.
- Work around a classifier denial. Stop and report it instead.
