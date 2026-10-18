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
2. LOCAL MODELS — SHIPPED (was "only 2 wired"; SIMS1337 parity layer landed)
═══════════════════════════════════════════════════════════════════════
[DONE] ModelRouter (complexity-based routing) — wired, AgentManager routes by
       task complexity (see src/main/java/com/mindpalace/agent/sims/ModelRouter.java).
[DONE] 4-tier ModelPool — replaced by SIMS-parity ModelRouter tiers in AgentManager.
[DONE] LoRA adapters + LoRASwitcher — src/main/java/com/mindpalace/agent/sims/LoRASwitcher.java.
[DONE] AdapterRegistry voting/election — src/main/java/com/mindpalace/agent/sims/AdapterRegistry.java
       (shipped for issue #71; runtime ballot wiring from AgentManager is follow-up).
[DONE] WeightedQuorumVote — FOW-gated quorum voting with 4D time pulse, exercised
       at AgentManager quorum call sites; 100-case parity gate in SimsHardening100
       (selftest aggregate check).
[DONE] FOWGate — src/main/java/com/mindpalace/agent/sims/FOWGate.java (agent→hex,
       model→agent, hop-limit visibility).

═══════════════════════════════════════════════════════════════════════
3. CODE EDITOR — language toggle SHIPPED; remaining extras stay open
═══════════════════════════════════════════════════════════════════════
[DONE]  ~20 programming-language toggle — LanguageRegistry + editor wiring
        shipped (Book.detectLanguage() now feeds BookEditor mode switching).
[DONE]  LoRA weight toggling per language — AdapterType-per-task path in
        agent/sims (LanguageRegistry + LoRASwitcher), parity with SIMS1337.
[OPEN]  KG node integration, syntax highlighting beyond the 40-line dump,
        undo/redo, multi-file project view — genuine future work, keep here.

═══════════════════════════════════════════════════════════════════════
4. GAME ENGINE — features defined but NOT hooked in
═══════════════════════════════════════════════════════════════════════
[DONE]  TOOLS (read_file/edit_file/create_file/delete_file) — now fully wired:
        AgentManager.executeToolRound() -> OllamaClient.chatWithTools() ->
        executeTool() -> submitToolRound() (AgentManager.java:230-272). The tool
        agent can now read/edit/create/delete a file end-to-end.
[DONE]  AgentManager autonomous cycle now acts on tool proposals via the
        critic→tool→execute loop (executeToolRound).

═══════════════════════════════════════════════════════════════════════
5. DOORS — SHIPPED: open on Enter, no auto-close cycle
═══════════════════════════════════════════════════════════════════════
[DONE]  Doors open on Enter and STAY open — exitRoom() deliberately does not
        call closeDoor() (entity/Player.java exitRoom comment; ec0ef46).
        Matches CROSS_CORRELATED_ROADMAP.md §9 DONE.

═══════════════════════════════════════════════════════════════════════
6. CHAT LOGS — SHIPPED: per-day file pushed to private repo
═══════════════════════════════════════════════════════════════════════
[DONE]  AgentChat writes per-day chat_logs/chat-YYYY-MM-DD.jsonl
        (AgentChat.java) and scripts/mindpalace-sync.sh pushes them to the
        PRIVATE mindpalace-chat-logs repo. AGENTS.md "Where data lives"
        already blesses this layout — do not "fix" it.

═══════════════════════════════════════════════════════════════════════
7. MORNING EMAIL — SHIPPED: 7am digest cron (morning_digest.py)
═══════════════════════════════════════════════════════════════════════
[DONE]    Hermes cron job 850b399c206e runs morning_digest.py daily at 07:00,
          emailing the digest of suggestions + code-mind log
          (scripts/feedback_digest.py is the weekly variant).

═══════════════════════════════════════════════════════════════════════
8. MEMORY / HDD FENCING — so local models don't weigh down the system
═══════════════════════════════════════════════════════════════════════
[DONE]    Memory/HDD fencing shipped — ModelScheduler enforces MIN_FREE_HEAP_BYTES
          64MB RAM cap + MIN_FREE_DISK_BYTES 256MB disk guard in
          resourcesAvailable(); both drain loops re-queue + back off 30s when
          the fence trips; agent/sims/LoRASwitcher.unload() covers model unload
          (see UNFINISHED_BUSINESS evidence in issue #68).

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
11. USB BACKUP — SHIPPED: backup-to-usb.sh covers all four categories
═══════════════════════════════════════════════════════════════════════
[DONE]    backup-to-usb.sh (repo root) backs up AIGEN_SYS (repos/KG/dashboards),
          databases (keyword/kg/code), hermes config/scripts/skills + cron_logs,
          Desktop task lists; idempotent cp -ru, bails cleanly if D: absent,
          stamps LAST_BACKUP.txt. Chat logs covered by AIGEN_SYS copy + the
          separate private chat-logs repo push (section 6).

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
