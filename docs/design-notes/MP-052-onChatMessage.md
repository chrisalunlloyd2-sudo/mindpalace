# MP-052 — Dedicated `onChatMessage` (#290)

## Ask
BLUEPRINT P3: guide (conversational chat) replies were routed through
`onToolMessage`, sharing one channel with tool-round log lines. Consumers had
to string-match `"[Guide] "`/`"[Tool] "` prefixes on a single stream.

## Design (KISS)
- New field `Consumer<String> onChatMessage` beside the three existing
  callbacks (`AgentManager.java:137`).
- New 4-arg overload `setCallbacks(tool, critic, console, chat)` delegating to
  the 3-arg form then setting the chat lane — Java has no default args, and an
  overload keeps every existing 3-arg caller compiling unchanged.
- Guide reply (`onUserChat` → `submitImmediate` → `thenAccept`) now emits on
  the chat lane when wired; when the 4-arg overload was never called
  (`onChatMessage == null`) it falls back to the legacy `emit(onToolMessage,
  "[Guide] " + resp)` so no consumer regressions.
- Wiring site (`GameEngine.java:736`) passes a 4th consumer that renders into
  `AgentChat.addMessage` — same renderer as the tool lane today, but the
  channel is now separable for future HUD styling ("agent is talking" vs
  "agent just edited a file").
- `emitChatForTest(String)` seam mirrors the routing decision exactly
  (chat-lane-or-fallback) so the selftest can prove both paths headless —
  the ModelScheduler path itself needs a live Ollama daemon, which CI lacks.

## Selftest
Case 45: 4-arg wiring → guide text lands ONLY on the chat lane (tool lane
empty); legacy 3-arg wiring → falls back to `[Guide] legacy fallback` on the
tool lane. Serial run: RESULT 88 passed, 0 failed.

## Not done (scope guard)
- No HUD styling change — the issue asks for channel separation, not a new
  visual treatment. Renderer stays `AgentChat.addMessage`.
- No CHANGELOG.md edit — generated artifact (#51 contract).