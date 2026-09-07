# MindPalace — map for hermes

Read this before exploring. It exists so a session can start editing in
minutes instead of re-deriving the codebase via dozens of read_file calls —
every one of those costs turns and quota. If something here turns out wrong,
fix this file in the same commit as your actual work.

**What this is**: a first-person 3D game (Java 17, LWJGL/OpenGL 3.3) where
hallways = your GitHub repos, rooms = each repo, books = files, doors open
on Enter. A local-Ollama-backed AgentManager (SIMS1337-derived: ModelRouter,
LoRA, FOW-gated quorum voting) runs an autonomous NPC loop that reads/edits/
creates/deletes real files in the repos and votes on proposals. It's a live
system — the world is running right now, most hours of most days.

## Hard rules
1. **Verify before you build.** `BLACKBOARD.md`, `CROSS_CORRELATED_ROADMAP.md`,
   `UNFINISHED_BUSINESS.md` lag shipped code by a week or more — grep the
   actual source before trusting a doc's "still open" claim. Several things
   nearly got re-built that were already done.
2. **Never delete, only merge.** Additive changes; don't rip out working
   paths to make room for a new one.
3. **Commit early and often, not only at the end.** A long session that only
   verifies/commits on its last turn risks running out of budget with
   nothing landed (happened twice on TASK_0002). Get something real
   committed as soon as it passes `--selftest`, then keep going.
4. **One task at a time.** Only one `TASK_####_*.md`/`.json` pair lives in
   `AIGEN_SYS/todo_management/todo_files/mindpalace/` at once — see that
   folder's `BACKLOG.md` for the queue and its own operating rules.
5. **Perf budget**: target hardware is Intel HD 510 / GeForce 945M-class,
   OpenGL 3.3. Cheap adds only — material/color/text changes over new
   geometry or particle systems, unless a task says otherwise.

## Build & verify
```
mvn clean compile              # compile check
java -jar target/mindpalace-1.0.0.jar --selftest   # must report "0 failed"
bash e2e.sh                    # full build+selftest+waypoint-tour+screenshot verify
```
`e2e.sh` and the game's own self-test are the real gate — a clean `mvn
package` alone is not proof of anything working.

## Module map (`src/main/java/com/mindpalace/`)
- **engine/** — `GameEngine` (main loop, rendering, input dispatch),
  `Input`, `GameState`, `ToolExecutor` (file read/edit/create/delete for the
  agent loop).
- **entity/** — `Player`, `AgentNPC`.
- **world/** — room/hallway generation: `WorldBuilder`, `RoomPopulator`,
  `RepoMapper`, `Room`, `Hallway`, `Book`, `FogOfWar`, `PortalTheme`
  (teleporter color pairing), `OutsideWorld`. Themed rooms:
  `TuringTape`, `RotorRoom`, `BanburismusGauge`, `NashFountain`,
  `LabDevice`, `Constellation`, `TodoCrystal`.
- **ui/** — `HUD` (door prompts), `InteractionPromptSystem` (nearby-
  interactable prompts, in progress), `BookViewer`, `BookEditor`,
  `SettingsMenu`, `DressingRoom`.
- **render/** — `Renderer`, `Camera`, `Shader`, `Mesh`, `Texture`,
  `FontRenderer`, `BloomEffect`, `Screenshot`.
- **agent/** — `AgentManager` (the autonomous cycle), `OllamaClient`,
  `ModelScheduler`, `ModelConfig`, `ModelLifespan`, `KnowledgeGraph`,
  `LexicalAnalyzer`, `BehaviorTree`, `ContextKit`, `KVTree`, `AgentChat`,
  `IdleDetector`.
  - **agent/sims/** — SIMS1337 parity: `ModelRouter`, `LoRASwitcher`,
    `AdapterType`, `FOWGate`, `WeightedQuorumVote` (quorum backend — no
    in-game room/UI for it yet), `LanguageRegistry`, `HexCoord`,
    `Complexity`.
- **github/** — `GitHubClient`, `RepoScanner`, `GistWall`, `GitHubIssueStream`.
- **avatar/** — `AvatarDescriptor`, `AvatarLibrary`, `CharacterDNA`,
  `AvatarImporter`.
- **genetics/** — procedural audio GA: `AudioEvolver`, `AudioGenome`,
  `SonicFitness`, `FFT`, `PatchSynth`, `GenomeArchive`, `GeneticTimeline`,
  `DeterministicSeed`, `GenomeControl`.
- **audio/** — `AudioEngine`, `MusicEngine`, `StepSequencer`.
- **economy/** — `DePIN`, `Wallet`, `Blackboard`.
- **backup/** — `Telemetry` (event ledger), `MemoryManager`, `BackupManager`.
- **deploy/** — `LiveUpdateManager`, `PatchManager`, `DeployManager`,
  `AnimationSystem`.

## Where data lives
- `mindpalace_memory/telemetry.db` — SQLite, table `events(id, ts, category,
  event, detail)`, categories: agent/quorum/depin/system. Append-only ledger.
- `mindpalace_memory/memory.db`, `mindpalace_memory/evolution/` — GA archive
  (gen-N.json/.wav pairs), grows over time; not yet pruned.
- `chat_logs/chat-YYYY-MM-DD.jsonl` — per-day chat, already working, pushed
  to a private repo by `mindpalace-sync.sh`. Don't "fix" this, it's fine.
- `game_console.log` — live stdout/stderr of the running game.
- `AIGEN_SYS/todo_management/todo_files/mindpalace/monitor_log.jsonl` — a
  15-minute no-agent health snapshot (disk, telemetry counts, process
  alive, Ollama reachable, git sync state, chat-repetition check). Quota-
  free, keeps recording even when hermes can't run.
- `AIGEN_SYS/todo_management/task_watch.log` — append a `DONE [mindpalace]
  <task> — <summary>` line here when you finish a task; that's how status
  checks confirm completion.

## Local Ollama (already installed, don't `ollama pull` to check)
llama3.2:3b, llama3.2:1b, qwen2.5:0.5b, deepseek-r1:1.5b, phi3:mini,
tinyllama:1.1b, phi:latest, gemma2:2b, mistral:7b, codellama:7b,
nomic-embed-text. Game roles: tool=llama3.2:1b, critic=qwen2.5:0.5b,
tie-breaker=deepseek-r1:1.5b.

## Known landmine (fixed, but know about it)
`AIGEN_SYS/scripts/system_maintenance.py`'s `clear_ollama_cache()` used to
allowlist only `phi/starcoder/tinyllama` and would `ollama rm` everything
else — including the models above that the game actually depends on. Fixed
2026-09-01 to keep everything installed. If you ever touch that function
again, keep the allowlist matching reality, not a guess.

## Known race (mitigated, stay aware)
`mindpalace-sync.sh` (no-agent, every 30 min) kills every `java.exe` before
rebuilding. If your own session is mid-build/mid-selftest when that fires,
you'll see a spurious "BUILD FAILED" that isn't your code's fault — commit
what's good, and if a build fails for no reason you can find in your own
diff, suspect this race before suspecting your code.
