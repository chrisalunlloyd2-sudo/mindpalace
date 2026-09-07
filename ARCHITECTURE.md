# ARCHITECTURE.md — MindPalace module map

> One diagram, one paragraph per module, and a "where do I add X" table.
> Visual version: `docs/dag_architecture.png` — keep both in sync.

## The 30-second tour

```
┌────────────────────────── Main.java ──────────────────────────┐
│  boot → GameEngine.run() → [loop: input → update → render]    │
└───────────────────────────────────────────────────────────────┘
        │
   ┌────┴─────────────┬──────────────────┬───────────────────┐
   ▼                  ▼                  ▼                   ▼
 world/             render/           entity/            ui/
 WorldBuilder       Renderer          Player             HUD
  Room·Book         BloomEffect       NPC agents         Overlays
  Hallway           Font/Camera       (SLM brains live
  OutsideWorld                        in agent/, not here)
  RotorRoom·Nash
   │                  │                  │
   ▼                  ▼                  ▼
 agent/  ──────────► github/ ◄───────── economy/
 AgentManager        GitHubClient      DePIN·Blackboard
  ├ OllamaClient     GitHubIssueStream  (credits price
  ├ ModelLifespan      (add-only)       every AI action)
  ├ ContextKit
  └ sims/ (governance: Router·Quorum·LoRA·FOW)
        │
        ▼
 backup/ (Telemetry ledger — append-only, everything is priced)
```

Data rule of thumb: **github/ is the world's source of truth** (rooms =
repos, books = files), **agent/ is the brain**, **economy/ is the wallet**,
**backup/telemetry is the bill**, and **world/render/entity/ui** are the
body the player actually sees and touches.

## Modules

| Package | Owns | Key classes |
|---------|------|-------------|
| `engine` | game loop, state machine, courtyard coupling | `GameEngine`, `GameState`, `Input`, `ToolExecutor` |
| `world` | everything that exists as place | `WorldBuilder` (hallways/rooms/doors/layout), `Room`, `Book`, `Hallway`, `OutsideWorld` (mansion/towns/forest/shops + building colliders), `RotorRoom`, `TuringTape`, `BanburismusGauge`, `NashFountain`, `EnigmaPlugboard` |
| `render` | pixels | `Renderer` (cube/quad/sphere meshes, texture atlas), `BloomEffect` (2-pass), `Camera`, `FontRenderer`, `Mesh`, `Shader` |
| `entity` | bodies that move | `Player` (FPS controller, collision + resolveAxis), NPC avatars |
| `agent` | minds | `AgentManager` (5-min autonomous cycle), `OllamaClient` (chat + tools), `ModelLifespan` (context budget, drift + repetition), `ContextKit` (LoRA+KG+KV per turn), `ModelScheduler` (one-model cold-shot gate) |
| `agent.sims` | governance | `ModelRouter` (complexity→model), `WeightedQuorumVote` (FOW-gated votes), `LoRASwitcher`, `FOWGate` |
| `github` | the world's data source | `GitHubClient` (repo/file CRUD, Credential-Manager PAT), `GitHubIssueStream` (add-only) |
| `economy` | money | `DePIN`, `Blackboard` (jobs, wallets, credits) |
| `genetics` | evolving audio | `AudioEvolver`, `AudioGenome`, `PatchSynth` (fitness via `MusicEngine.renderOffline`) |
| `audio` | sound | `AudioEngine` (SFX, wind, rotor carries), `MusicEngine` (procedural score, rotor-clock), `StepSequencer` |
| `backup` | memory + billing | `Telemetry` (append-only ledger), `MemoryManager` (never-twice) |
| `ui` | overlays | `HUD`, map overlay, help |

## Where do I add…?

| I want to add… | Go here | Pattern to copy |
|----------------|---------|-----------------|
| A new building outside | `OutsideWorld`: `render*` + one `addBox(colliders…)` | `renderHospital` |
| A new room type inside | `WorldBuilder.renderRoom` + `Room` constants | existing room render |
| A new agent tool | `AgentManager.buildTools` + `executeTool` switch + `TOOL_SYSTEM_PROMPT` | `read_file` |
| A governance rule | `agent/sims/WeightedQuorumVote` or `ModelRouter` | quorum lifecycle |
| A new sound | `AudioEngine` `gen*` + `play*`; call from event site | `playRotorChime` |
| A HUD element | `ui/HUD` + `GameEngine.render*` hook | telemetry panel |
| A world event the mansion reacts to | emit from event site → `GameEngine.update` coupling block | rotor carries |
| An economy price | `economy/DePIN` + call site in `AgentManager` | compute tax (H39) |
| A program step | `NEXT_100_STEPS.md` → `TASK_Hxx_*.md` in `todo_management/todo_files/mindpalace/` | `TASK_H01` |

## Landmines (read before editing)

1. **CME rule** — collections touched by the agent thread while the render
   thread iterates must be `synchronizedList` + snapshot-at-iteration.
   This froze the game once already.
2. **One-model cold-shot** — `OllamaClient.modelGate` serializes ALL model
   calls, keep_alive=0 always. Never add a second in-flight path.
3. **Frozen jar rule** — never run `target/` for the live game; copy to
   `mindpalace-live.jar` first (hourly sync rebuilds target mid-game).
4. **Collision** — new outdoor geometry needs an `addBox` collider or it's
   walk-through (the pre-H13b bug).
5. **Add-only GitHub** — IssueStream never closes/edits; step-log issue #9
   is append-only evidence.

## Verification (every change ships through this)

```bash
mvn -DskipTests package                          # BUILD SUCCESS gate
java -jar mindpalace-live.jar --selftest         # 40 checks / 0 failed
java -jar mindpalace-live.jar --e2e <dir>        # 13 labeled waypoints
python scripts/test_bot.py --verify-shots <dir>  # non-black PNG proof
bash scripts/cascade_dev.sh Hxx                  # all of it + commit/push/step-log
```