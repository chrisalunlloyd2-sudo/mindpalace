# MindPalace — UNFINISHED BUSINESS

Full code review, correlated against Architect's asks. Every item below is backed by
reading the actual source (SIMS1337 + MindPalace), not assumption. Grouped by severity.

Legend: [BROKEN] = doesn't work at all · [STUB] = defined but not wired · [MISSING] = not built

═══════════════════════════════════════════════════════════════════════
1. EMAIL — PARTIAL (himalaya live; morning digest not wired)
═══════════════════════════════════════════════════════════════════════
[RECONCILED 2026-10-21 (#294)] himalaya IS installed (`~/bin/himalaya`) and
          config.toml exists with an imap/smtp account block — the "not
          installed / empty config / no creds" lines below-became-stale; creds
          live in the credential store. Sending needs Architect's send-go.
[BROKEN] `~/sent_emails/` is EMPTY → nothing to harvest from sent mail.
[PLANNED] dream_job.py / dream_correlation_job were spec'd but never landed
          in-repo (no git history; absent from AIGEN_SYS/scripts/ — only the
          unrelated KG dream_loop.py exists [ACTIVE]). The digest pipeline
          below is designed, not started.

Fix path: wire a real morning-digest job (harvest himalaya sent mail →
correlate → email suggestions + code-mind log).

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
10. MULTIPLAYER — LANDED ADDITIVE (#138), disabled by default
═══════════════════════════════════════════════════════════════════════
[RECONCILED 2026-10-21 (#294)] MultiplayerHub + MultiplayerClient exist
          (com.mindpalace.network/, commit #138 batch): RFC6455 WebSocket
          accept-key, port validation, OFF unless started with the flag
          (off-by-default, selftest-gated — "multiplayer." SimsHardening100
          cases green). Not yet a shipped gameplay feature (no lobby flow),
          but "No networking" is no longer true.

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
[DONE] Phase G: teleporter graphics upgrade — particle swirl, glow ring, animated pad all shipped (ROADMAP.md Phase G all [x]; ROADMAP.md sweep 2026-10-18).

═══════════════════════════════════════════════════════════════════════
PRIORITY ORDER (recommended)
═══════════════════════════════════════════════════════════════════════
1. ~Email (broken end-to-end)~ ✅ DONE — himalaya CLI wired; morning digest cron 850b399c206e (scripts/feedback_digest.py → ~/AppData/Local/hermes/scripts/morning_digest.py)
2. ~Hook in the game engine (TOOLS execution loop)~ ✅ DONE — ToolExecutor + AstGate wired in engine/; §8 [DONE]
3. ~Doors: open-on-Enter, no auto-close~ ✅ DONE — exitRoom no-closeDoor (Player.java; ec0ef46)
4. ~Per-day chat logs → private GitHub repo~ ✅ DONE — mindpalace-sync.sh push path (#69)
5. SIMS1337 parity: ModelRouter + LoRA + voting schema + FOW gate
6. Code editor: 20-language toggle + LoRA + KG nodes
7. ~Memory/HDD fencing~ ✅ DONE — ModelScheduler fences (§8)
8. ~Morning email digest~ ✅ DONE — crond 850b399c206e
9. ~Windows installer (Phase H)~ ✅ DONE — java-windows-installer .exe build (#72 batch)
10. ~USB backup~ ✅ DONE — backup-to-usb.sh (§11)
11. Multiplayer / outside world (Phase F/G)
