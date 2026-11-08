# MindPalace knowledge base

The one page to read first. It points to the real sources and records the
lessons that are not obvious from the code. Add to it; never erase (wrong
entries get a dated correction under them).

## Where things live
| What | Where |
|---|---|
| Agent rules, module map | `AGENTS.md` |
| Dev setup, Ollama env | `DEV_SETUP.md`, `scripts/check_ollama_capacity.py` |
| Roadmap, steps 51–150 | `NEXT_100_STEPS.md` |
| Steps 151–250, phases H–L, Hermes lane | `NEXT_STEPS_151_250.md` |
| Open work, honest gaps | `UNFINISHED_BUSINESS.md` |
| Hints for the next model (Sonnet) | `docs/HANDOFF_SONNET.md` |
| Live game | `AIGEN_SYS/repos/mindpalace/mindpalace-live.jar`, log `game_console.log` |
| Watchdog (outside the repo) | `~/.aria/engine/watchdog.py`, `ops_health.py`; reports in `AIGEN_SYS/reports/` |
| Heavy-job governor | `~/.aria/engine/budget.py run --job NAME --class heavy -- argv` (exit 75 = wait) |
| Hermes law | `~/AppData/Local/hermes/SOUL.md` (MACHINE LAW block) |

## Machine laws (12 GB RAM, spinning HDD, CPU-only Ollama)
1. At most **2 resident models**. The settings live only in the user environment (HKCU):
   `OLLAMA_MAX_LOADED_MODELS=2 OLLAMA_NUM_PARALLEL=1 OLLAMA_KEEP_ALIVE=10m
   OLLAMA_FLASH_ATTENTION=1 OLLAMA_KV_CACHE_TYPE=q8_0 OLLAMA_CONTEXT_LENGTH=8192 OLLAMA_NO_CLOUD=1`.
2. Never start `ollama serve` by hand with an env block. The watchdog cold-starts it,
   waits for it to come up (a grace window), then keeps it alive through its 5-min cycle.
3. **Three small voters** on this box (`llama3.2:1b, qwen2.5:0.5b, deepseek-r1:1.5b`, ~2.8 GB).
   They stay in the OS file cache. The 5-voter roster rotates ~9 GB through 2 slots
   and saturates the disk. `-Dmindpalace.voters=` (#344) sets the roster; #347 (merged)
   makes the small roster automatic under 16 GB.
4. One heavy job at a time, through `budget.py`.
5. LOCAL MODELS ONLY inside the game. Cloud is for Hermes' own outside work only.

## Lessons 2026-11-07 (measured, not guessed)
- **Thrash had two layers.** RAM first: MAX=4, KEEP_ALIVE=-1, PAR=2 and orphan runners left
  2.6 GB free. Then the disk: 5 voters through 2 slots gave a disk queue of 33 and
  21 MB/s of page faults. The fix for each was a setting, not more hardware.
- **Read the stack, not the theory.** The watchdog "launcher hang" was really four
  stalls in a row, each found by the faulthandler dump:
  1. the `bin\pythonw.exe` launcher shim;
  2. `urllib.urlopen` building an SSL context for plain-http loopback;
  3. Task Scheduler Priority 7 (low CPU, disk and memory priority);
  4. psutil's per-process `ppid()`, which takes a full process snapshot each call.
- **Python under Task Scheduler:** point tasks at the real interpreter, use
  `http.client` for loopback, raise to normal priority at startup, and put
  `faulthandler.dump_traceback_later` on line 1.
- **Chat kit A/B.**
  - qwen2.5:0.5b, 20 prompts per arm, character breaks: raw tags 15/20, role line 5/20, plain words 0/20.
  - llama3.2:3b, 10 per arm: 0 refusals in either arm; kit echoed in tags 3/10, words 2/10.
  - Plain words (#346) helps small models a lot and the 3b model slightly.
- **Hermes followed the docs.** It hand-started the heavy serve because `DEV_SETUP.md`
  called anything lighter DRIFT. Docs are instructions to agents, so write them per machine (#342).

## Correction log
- 2026-11-07: "the shim hangs the watchdog" was incomplete; see the lessons above.
