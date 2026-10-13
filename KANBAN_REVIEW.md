# MindPalace — Claude kanban review board

Persistent review cards for Claude Code sessions. Each card = one focused
quality discussion on the live game. Protocol:

1. Pick ONE card. Read the referenced source first — verify claims, find
   root causes. BLACKBOARD/roadmap docs lag shipped code; the source is truth.
2. Write findings to kanban_reviews/<card-id>.md with sections:
   VERDICT (ship/block), FINDINGS (with file:line), RECOMMENDATIONS
   (ranked, concrete), PERF-NOTE (Intel HD 510 budget).
3. Be adversarial about quality but additive in spirit ("never delete,
   only merge"). One card per session.
4. When a card's recommendations land as a commit, tick its DONE line here.

## Cards

### CARD-Q1 — Agent loop coherence
Review src/main/java/com/mindpalace/agent/ (AgentManager, AgentChat, BdiBridge,
ModelRouter) for: dead paths, thread-safety (CME class bugs: synchronizedList
must snapshot at EVERY read site), proposal->vote->act loop coherence.
DONE: (open)

### CARD-Q2 — World readability at 1080p
Review world/ room + HUD rendering against screenshots/ — is the player's
affordance set readable? Signage, door prompts, crystal visibility (the
07_todo_crystals + 13_door_prompt dark-shot bugs). Cheapest-first fixes.
DONE: (open)

### CARD-Q3 — Economy + quorum balance
Review economics/ + genetics/ + quorum paths: are credit flows/retirement
tuned so the bot swarm can't stagnate or hyperinflate? Check wallet events
vs HUD.
DONE: (open)

### CARD-Q4 — First-run experience
FIRST_PR.md + --demo path + installer: can a stranger go zero->palace in
15 min with zero auth? Where do they fall over?
DONE: (open)
