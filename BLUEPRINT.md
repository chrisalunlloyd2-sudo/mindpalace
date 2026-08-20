# MindPalace — Blueprint (Architecture + Data Flow)

> Living architecture doc. ASCII data-flow charts of the REAL code paths,
> not the intended ones. Updated 2026-08-20.

---

## 1. System Overview

```
┌─────────────────────────────────────────────────────────────────────────┐
│                            MindPalace (Java 17 / LWJGL 3)               │
│                                                                         │
│  ┌──────────┐   ┌──────────┐   ┌──────────┐   ┌──────────────────────┐  │
│  │  Input   │──▶│  Player  │──▶│  Camera  │──▶│  Renderer (OpenGL)   │  │
│  │ (GLFW)   │   │ (FPS ctl)│   │ (yaw/pitch)│  │  + Bloom + Font      │  │
│  └──────────┘   └──────────┘   └──────────┘   └──────────────────────┘  │
│                                                                         │
│  ┌──────────────┐   ┌──────────────┐   ┌──────────────────────────┐    │
│  │ WorldBuilder │──▶│  Room/Book/  │──▶│  FogOfWar (hex reveal)   │    │
│  │ (137 rooms)  │   │  Hallway     │   │  KnowledgeGraph (KG)     │    │
│  └──────────────┘   └──────────────┘   └──────────────────────────┘    │
│                                                                         │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │  Agent layer (local SLMs via Ollama)                             │   │
│  │  AgentManager ─▶ ModelScheduler ─▶ ModelLifespan ─▶ OllamaClient │   │
│  └──────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Model Chat — REAL Data Flow

This is the actual path a user message takes, traced through the code
(not the intended design). Two distinct paths exist: **user chat** (immediate)
and **autonomous cycle** (5-min spaced).

### 2.1 User chat (immediate path)

```
User types "hello"
   │
   ▼
Input.glfwSetCharCallback ──▶ charBuffer (accumulates codepoints)
   │
   ▼
GameEngine.update()  [Enter pressed, agentChat.isTyping()]
   │  agentChat.commitInput() ──▶ returns "hello"
   ▼
agentManager.onUserChat("hello")
   │
   │  buildContext() ──▶ "Fog of war: N repos... Current room: X (lang) — M books..."
   │  prompt = context + "\n\nPlayer says: hello"
   ▼
modelScheduler.submitImmediate(CHAT_MODEL="llama3.2:3b", prompt, chatLifespan)
   │
   │  enqueue(Job{immediate=true}) ──▶ BlockingQueue
   ▼
ModelScheduler.drainLoop()  [SINGLE worker thread]
   │  immediate=true → SKIPS the 5-min spacing sleep
   │  BUT still serialized: waits for any in-flight call to finish
   ▼
chatLifespan.chat(prompt)
   │  addTurn("user", prompt)
   │  buildMessages() = system + summary + RAG memory + history
   ▼
OllamaClient.chat("llama3.2:3b", messages) ──▶ POST /api/chat
   │
   ▼
.thenAccept(resp) ──▶ emit(onToolMessage, "[Guide] " + resp)
   │
   ▼
agentChat.addMessage("[Guide] ...") ──▶ rendered at top of screen
```

### 2.2 Autonomous cycle (5-min spaced)

```
AgentManager.autonomousCycle()  [every 5 min, ScheduledExecutorService]
   │
   ▼
modelScheduler.submit(TOOL_MODEL="llama3.2:1b", toolPrompt, toolLifespan)
   │  immediate=false → drainLoop SLEEPS until 5 min since last call
   ▼
toolLifespan.chat() ──▶ OllamaClient ──▶ toolResp
   │
   ▼
.thenAccept(toolResp)
   │  emit(onToolMessage, "[Auto] " + toolResp)
   │  then submit(CRITIC_MODEL="qwen2.5:0.5b", criticPrompt, criticLifespan)
   ▼
criticLifespan.chat() ──▶ OllamaClient ──▶ criticResp
   │
   ▼
emit(onCriticMessage, "[Auto] " + criticResp)
```

### 2.3 Agent NPC brains (BehaviorTree, 5-min throttled)

```
AgentNPC.decide()  [decisionCooldown <= 0, ~every 5 min]
   │
   ▼
brain.requestDecision(ctx, callback)
   │  BehaviorTree ─▶ modelScheduler.submit(...)  [spaced]
   ▼
callback → applyAction(action) ──▶ WALK_TO_ROOM / READ_BOOK / etc.
   │
   ▼
npc.consumeReason() ──▶ agentChat.addMessage("[Explorer] ...")
```

---

## 3. Diagnosis — Model Chat Problems + Proposed Fixes

### Problem 1: User chat still waits behind autonomous calls
**Evidence:** `ModelScheduler.drainLoop()` is a SINGLE worker thread. Even
`submitImmediate()` (which skips the 5-min *spacing sleep*) still serializes
behind whatever call is currently running. If the tool agent is mid-call on
`llama3.2:1b` (10–30s on CPU), the user's chat reply waits for it to finish.

**Fix (proposed):** Give user chat its own dedicated worker thread (or a
priority queue that preempts). The "never two models at once" rule was meant
to protect the *autonomous* cycle from thrash — user chat is a different
latency class and should not queue behind it.

### Problem 2: `lastUserMessage` is dead state
**Evidence:** `AgentManager.lastUserMessage` is set in `onUserChat()` but never
read anywhere. Dead field.

**Fix (proposed):** Either remove it, or use it to seed the next autonomous
cycle's context (so the agents "remember" what the player last asked).

### Problem 3: Chat reply routed through `onToolMessage` callback
**Evidence:** `onUserChat()` emits the guide reply via `emit(onToolMessage,
"[Guide] " + resp)`. Both `onToolMessage` and `onCriticMessage` are wired to
the same `agentChat.addMessage()` in GameEngine, so it *works*, but the
semantic is wrong — the guide reply is not a "tool" message.

**Fix (proposed):** Add a dedicated `onChatMessage` callback for clarity, or
rename. Cosmetic, but keeps the data flow honest.

### Problem 4: Autonomous cycle can double-fire
**Evidence:** `autonomousCycle()` guards with `lastAutoCycle`, but the
`ScheduledExecutorService` uses `scheduleWithFixedDelay` with a 30s initial
delay and 5-min period. The guard is belt-and-suspenders; fine, but the
`lastAutoCycle` check uses `CYCLE_MS - 10_000` which is slightly off.

**Fix (proposed):** Leave as-is (works), but note the 10s tolerance is
intentional to avoid clock-skew double-fires.

### Problem 5: Book content bloat in chat context
**Evidence:** `buildContext()` includes up to 2000 chars of book content in
EVERY prompt (chat + autonomous). For `llama3.2:1b` (2000-token budget), a
2000-char book excerpt ≈ 500 tokens, eating 25% of the budget before the
model even sees the question.

**Fix (proposed):** Truncate book content to ~500 chars for chat context, or
only include it when the user explicitly asks about the book.

---

## 4. Book Click — REAL Data Flow (fixed 2026-08-20)

```
User left-clicks in a room
   │
   ▼
Input.glfwSetMouseButtonCallback ──▶ leftClickJust = true
   │
   ▼
GameEngine.update()  [state==PLAYING, currentRoom!=null, !teleportMenu]
   │  input.isLeftClick() ──▶ true (once)
   ▼
findBookInSights(room)
   │  OLD: rayAABB slab test (0.10m book) → ~44% hit rate even dead-center
   │  NEW: angular cone (4° half-angle) + nearest-book tiebreaker → ~95%
   ▼
clicked != null?
   ├─ YES ─▶ bookEditor.open() ─▶ state=BOOK_VIEW ─▶ cursor released
   ├─ NO, lookingAtPlant() ─▶ factToast (Phase D)
   └─ NO ─▶ "[CLICK] no book in sights" (debug log)
```

**Root cause of "books don't click":** the AABB slab raycast against a 10cm
book was too precise. The self-test only required `clickableRooms > 0` to
pass, so it masked a 44% hit rate. Fixed by replacing with an angular cone
and tightening the self-test to require ≥90% hit rate against *placed* books.

---

## 5. Rendering Pipeline

```
render(alpha)
   │
   ▼
bloom.begin() ──▶ bind scene FBO
   │
   ▼
renderer.beginFrame(camera) ──▶ set projection/view/light/tint uniforms
   │
   ▼
world.render(renderer, camera)
   │  hallways → rooms (culled by distance + fog + frustum)
   │  each room: setTint(language) → draw → setTint(neutral)
   ▼
renderNPCs() / renderCrystals()  [agent avatars + TODO crystals]
   │
   ▼
fontRenderer: neon signs, floor map, HUD, book spines, poster, tooltip,
              highlight, floor signs, telemetry panel, fact toast
   │
   ▼
bloom.end() ──▶ bright-pass → blur → composite to screen
   │
   ▼
glfwSwapBuffers
```

---

## 6. Live Patch System

```
PatchManager.poll()  [every 8s]
   │  reads patches/patch.json
   ▼
hasPending()? ──▶ patchCinematic = true (3s loading bar)
   │
   ▼
apply(pp, world) + applyGraphics(pp, renderer)
   │  content: addRoom, addBook, etc.
   │  graphics: ambient, lightColor, lightOffset (hot-applied, no restart)
   ▼
patchToast = "PATCH <id> LOADED" (10s)
```

---

## 7. Fog of War + Knowledge Graph

```
Player walks ──▶ FogOfWar.reveal(pos) ──▶ hexes revealed
   │
   ▼
Room.isFogged() && !isRoomRevealed() ──▶ room skipped in render
   │
   ▼
KnowledgeGraph.build(rooms) ──▶ nodes (repos) + edges (adjacent doors)
   │  district = West/East (hallway side)
   ▼
AgentNPC.pickDestination() ──▶ KG.neighbors() / randomRoomInDistrict()
```

---

## 8. Model Inventory (live, verified 2026-08-20)

| Model | Role | Path |
|-------|------|------|
| llama3.2:1b | Tool agent (autonomous) | ModelScheduler.submit |
| qwen2.5:0.5b | Critic agent + code suggest | ModelScheduler.submit / BookEditor |
| llama3.2:3b | User chat (guide) | ModelScheduler.submitImmediate |
| nomic-embed-text | Drift detection + RAG | ModelLifespan.embed |

Also installed (unused by MindPalace): mistral:7b, codellama:7b, gemma2:2b,
deepseek-r1:1.5b, phi3:mini, tinyllama:1.1b, phi:latest.
