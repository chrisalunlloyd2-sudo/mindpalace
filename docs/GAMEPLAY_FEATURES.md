# Feature Usage Guide — everything you can do in the palace

One page per feature group: what it does, how to use it, what to expect.

## Movement & camera
- **WASD** move, **Shift** sprint, **Space** jump.
- **Mouse** look; **F1** help overlay; **Tab** full map (walls included);
  **M** minimap.
- **F7** dressing room (avatar customization, Cortana preset start).
- **Noclip** free-fly exists for testing (`Player.noclip`).

## Doors & rooms
- Every hallway door = a GitHub repo (cyan = public, pink = private).
- **Enter** near a door (≤3m, no aim needed) → door slides up, you step in.
- Inside a room: three walls of bookcases, every book = a file.
- **Enter** again to exit (doors stay open — Architect's rule).

## Books (files)
- **Click** a book → retro terminal editor opens (view/edit/create/delete).
- Hover shows name, language, size. Spines color-coded by language.
- Full GitHub CRUD — edits write through to the real repo.

## The agents (Explorer + Critic)
- Two SLMs (llama3.2:1b tool, qwen2.5:0.5b critic) walk the halls on a
  5-minute autonomous cycle: claim jobs, read code, propose changes —
  the critic reviews their CONCRETE actions (H01).
- Chat HUD shows their reasoning live; **Enter to chat** replays through
  the immediate path (no 5-minute wait).
- They earn DePIN credits per completed job; chatter is priced + gated.

## The courtyard (outside the mansion)
- **Rotor rings** — Enigma odometer ticking every second; carries sound
  (tick/chime/bell). Same second = same rotor state, always.
- **Music rotor-clock** — chord progression turns with the rings.
- **Nash fountain** — equilibrium made physical, fed by the live GA.
- **Banburismus gauge + Turing tape** — live deciban needle and event tape.
- **Forest** — 660 trees, parkland near the mansion, wild further out.
  Wind rises as you leave; seasons/weather are real calendar-driven.
- **Towns + shops** — 15 houses (solid walls, enter through doors), model
  shops sell DePIN upgrades with credits.

## Mansion (player home)
- **Enter** at the mansion door toggles inside/outside.
- Spawn point + crystals + the palace's physical heart.

## Beats StudioLab
- **B** toggles the 16-step sequencer; per-channel toggles, tempo, moods.
- Procedural music evolves via genetic audio — fittest patch becomes the
  world's score (also feeds the Banburismus gauge).

## Governance (SIMS1337 parity)
- Every cycle = quorum vote (3 voters, FOW-gated, tie-breaker deepseek).
- Complexity router picks the model per task (LOW→0.5b ... CRITICAL→phi3).
- LoRA adapter rides every call via ContextKit.

## Search & map
- **/** — jump to any repo by name.
- **Tab** — full map with hallway floors; minimap **M** for local.

## Options (Esc)
- Volume, sound toggle, render distance, bloom intensity/threshold, FOV,
  sensitivity, fullscreen, invert-Y.
