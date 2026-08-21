# MindPalace — UNFINISHED BUSINESS

Full code review, correlated against Architect's asks. Every item below is backed by
reading the actual source (SIMS1337 + MindPalace), not assumption. Grouped by severity.

Legend: [BROKEN] = doesn't work at all · [STUB] = defined but not wired · [MISSING] = not built

═══════════════════════════════════════════════════════════════════════
1. EMAIL — BROKEN (root cause found)
═══════════════════════════════════════════════════════════════════════
[BROKEN] himalaya CLI is NOT installed (`which himalaya` → nothing).
[BROKEN] No email account config — `~/.config/himalaya/` is empty.
[BROKEN] No IMAP/SMTP credentials anywhere.
[BROKEN] `~/sent_emails/` is EMPTY → dream_job.py harvests zero emails.
[BROKEN] dream_correlation_job has `last_run_at: null` — never actually ran.
[STUB]  dream_job.py only tokenizes + bumps a KG node; it does NOT send a
        morning email, does NOT correlate with code, does NOT produce a
        "suggestions + code mind" digest.

Fix path: install himalaya → configure account (needs Architect's email creds)
→ wire a real morning-digest job that emails suggestions + code-mind log.

═══════════════════════════════════════════════════════════════════════
2. LOCAL MODELS — only 2 wired; SIMS1337 has the full setup
═══════════════════════════════════════════════════════════════════════
[MISSING] ModelRouter (complexity-based routing). SIMS1337 routes LOW→qwen2.5:0.5b,
          MEDIUM→tinyllama:1.1b, HIGH→phi:latest, CRITICAL→phi3:mini.
          MindPalace hardcodes 2 models (llama3.2:1b tool, qwen2.5:0.5b critic).
[MISSING] 4-tier ModelPool. MindPalace has 11 Ollama models installed but only
          uses 2. SIMS1337 keeps 4 tiers warm.
[MISSING] LoRA adapters + LoRASwitcher. SIMS1337 has 6 AdapterTypes (CHAT, CODE,
          PATHFIND, MOTIVES, CAREER, ANALYSIS) with <100ms weight switching.
          MindPalace has ZERO LoRA support.
[MISSING] AdapterRegistry voting/election (LoRA weights voted on, best wins).
[MISSING] WeightedQuorumVote — FOW-gated quorum voting with 4D time pulse.
          This is the "voting schema" Architect referenced. Not in MindPalace.
[MISSING] FOWGate — the organized fog-of-war pattern (agent→hex, model→agent,
          hop-limit visibility). MindPalace's "fog of war" is only a visual
          FogOfWar.java + a discoveredRepos Set — NOT the SIMS1337 pattern.

═══════════════════════════════════════════════════════════════════════
3. CODE EDITOR — supposed to be a full suite, is a basic terminal
═══════════════════════════════════════════════════════════════════════
[STUB]  BookEditor = VIEW/EDIT/CREATE/DELETE/SUGGEST only.
[MISSING] ~20 programming-language toggle. Book.detectLanguage() exists but the
          editor never uses it to switch modes/syntax.
[MISSING] LoRA weight toggling per language (SIMS1337 AdapterType per task).
[MISSING] KG node integration (editor should read/write KnowledgeGraph nodes).
[MISSING] Syntax highlighting, line numbers beyond a 40-line dump, undo/redo.
[MISSING] Multi-file project view (editor is single-book only).

═══════════════════════════════════════════════════════════════════════
4. GAME ENGINE — features defined but NOT hooked in
═══════════════════════════════════════════════════════════════════════
[STUB]  TOOLS (read_file/edit_file/create_file/delete_file) are DEFINED in
        AgentManager.buildTools() but NEVER EXECUTED. There is no tool_calls
        parsing, no executeTool(), no tool-result loop. The tool agent can
        "propose" but cannot actually read/write/delete a file.
[STUB]  rayAABB() is dead code (left in place per "never delete, only merge").
[STUB]  SettingsMenu.musicVolume field was dead (noted earlier).
[STUB]  AgentManager autonomous cycle only emits text — it never acts on the
        tool agent's proposal (no critic→tool→execute loop).

═══════════════════════════════════════════════════════════════════════
5. DOORS — cycling vs. open-on-Enter
═══════════════════════════════════════════════════════════════════════
[STUB]  Doors currently open on Enter (enterRoom→openDoor) AND close on exit
        (exitRoom→closeDoor). Architect wants: open on Enter, NO auto-close
        cycle. Fix = remove closeDoor() from exitRoom (or make close optional).

═══════════════════════════════════════════════════════════════════════
6. CHAT LOGS — single file, not per-day
═══════════════════════════════════════════════════════════════════════
[STUB]  AgentChat persists to ONE chat_logs/chat.jsonl. Architect wants a NEW
        text file per day, uploaded to GitHub (private repo).

═══════════════════════════════════════════════════════════════════════
7. MORNING EMAIL — suggestions + code-mind log
═══════════════════════════════════════════════════════════════════════
[MISSING] No job emails a daily digest of (a) all suggestions and (b) the
          code-mind log. dream_job.py is a placeholder that only bumps a KG node.

═══════════════════════════════════════════════════════════════════════
8. MEMORY / HDD FENCING — so local models don't weigh down the system
═══════════════════════════════════════════════════════════════════════
[MISSING] No explicit memory/HDD fencing. ModelScheduler enforces 5-min spacing
          (good) but there's no RAM cap, no disk-usage guard, no model unload
          policy. SIMS1337's LoRASwitcher unloads weights; MindPalace keeps
          nothing bounded except token budgets.

═══════════════════════════════════════════════════════════════════════
9. WINDOWS INSTALLER — Phase H, not started
═══════════════════════════════════════════════════════════════════════
[MISSING] jpackage native .exe installer.
[MISSING] Registers in Program Files + Start Menu.
[MISSING] Uninstall package.
[MISSING] Warm welcoming installer GUI (file-location chooser, accessories TBD
          like Ollama + models).
[MISSING] Intro screen + options screens (ESC menu exists; no intro splash).

═══════════════════════════════════════════════════════════════════════
10. MULTIPLAYER — not built
═══════════════════════════════════════════════════════════════════════
[MISSING] No networking, no second player, no lobby.

═══════════════════════════════════════════════════════════════════════
11. USB BACKUP — not done
═══════════════════════════════════════════════════════════════════════
[MISSING] No USB backup of AIGEN_SYS / repos / databases / chat logs.

═══════════════════════════════════════════════════════════════════════
12. OUTSIDE WORLD / PHASE F-G — deferred
═══════════════════════════════════════════════════════════════════════
[MISSING] Phase F: outside world streaming (small chunks).
[MISSING] Phase G: teleporter graphics upgrade (particle swirl, glow, animated pad).

═══════════════════════════════════════════════════════════════════════
PRIORITY ORDER (recommended)
═══════════════════════════════════════════════════════════════════════
1. Email (broken end-to-end — needs Architect's email creds to finish)
2. Hook in the game engine (TOOLS execution loop — biggest "not hooked in" gap)
3. Doors: open-on-Enter, no auto-close (quick win)
4. Per-day chat logs → private GitHub repo (quick win)
5. SIMS1337 parity: ModelRouter + LoRA + voting schema + FOW gate
6. Code editor: 20-language toggle + LoRA + KG nodes
7. Memory/HDD fencing
8. Morning email digest
9. Windows installer (Phase H)
10. USB backup
11. Multiplayer / outside world (Phase F/G)
