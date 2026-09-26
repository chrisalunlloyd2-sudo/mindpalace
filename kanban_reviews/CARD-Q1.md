# CARD-Q1 — Agent loop coherence (review)

Reviewed at `8f5d2e5` (2026-09-25). Scope: `src/main/java/com/mindpalace/agent/`
(AgentManager, AgentChat, BdiBridge, ModelScheduler, OllamaClient, ContextKit,
sims/ModelRouter, sims/WeightedQuorumVote, sims/QuorumTaskSpawner, sims/Complexity),
plus the GameEngine wiring sites that cross thread boundaries into this package.
No source modified. Line numbers are against `8f5d2e5`.

Evidence legend: **[code]** = proven by reading the source; **[runtime]** = backed by
an on-disk artifact; **[infer]** = strong inference, verify before fixing.
Caveat: `game_console.log` had no agent lines when I checked, so I couldn't confirm
the tool-loop findings (F1, F4) from logs. The shell tool was also down this
session, so nothing was executed.

---

## VERDICT: BLOCK

This is a block on the loop's claims, not on the game. Rendering and the main flow are
fine. But the autonomous loop does not do what its comments say:

- The tool agent almost certainly never runs a tool (F1).
- The critic therefore never reviews anything (F4).
- The quorum is effectively a random-number generator that also blocks the agent
  thread for 15+ minutes and gets slower every cycle (F2, F3).
- The vote runs *after* the action it's supposed to gate and gates nothing (F5).
- AgentChat is written from three non-game threads, which breaks the
  thread-confinement rule BdiBridge carefully follows (F6).
- One path spams GitHub with a duplicate issue every cycle (F7).

Each fix below is small and additive.

---

## FINDINGS

### Correctness: the proposal → vote → act loop

**F1 [code] Tool calls are parsed as strings, but Ollama returns an object, so every tool round throws.**
`OllamaClient.java:174` — `fn.get("arguments").getAsString()`. Ollama's `/api/chat`
returns `message.tool_calls[].function.arguments` as a JSON **object**, and Gson's
`JsonObject.getAsString()` throws `UnsupportedOperationException`. That's a
RuntimeException, so `catch (IOException)` at `:181` misses it. It lands in
`AgentManager.executeToolRound`'s `catch (Exception)` at `AgentManager.java:869-870`
and logs `tool loop error: JsonObject`.
Result: `executeTool` (`:895`) is unreachable in practice. No read, edit, create or
delete ever runs, and `lastToolActions` is never published.
The replay at `:833-834` (`append(c.arguments)`) would *also* be wrong if arguments
were ever a quoted string: it would inject a JSON string where an object is expected.
Verify with one grep for `tool loop error` in a live console log.

**F2 [code+runtime] The proposal registry is never pruned, and `actualVoteAll` re-votes every proposal ever made.**
- `WeightedQuorumVote.java:82`: `proposals` has no remove path.
- `:165-185`: `actualVoteAll` loops over **all** proposals × all models.
- It's called from `runQuorumVote` (`AgentManager.java:440`), once **per lexical
  cluster** in `runLexicalBridge` (`:559`), and once **per issue** in `solveIssues` (`:731`).

Each call blocks the `AgentManager.scheduler` thread for up to 30s per
(proposal, model) pair (`future.get(30, SECONDS)`, `:175`). Cost per cycle grows
O(P×3×30s) with every cycle and every chat cluster, so the loop degrades toward never
completing.
- Old verdicts get overwritten too: every re-vote replaces `p.votes`.
- The timed-out futures aren't cancelled. They stay in `ModelScheduler.queue` and still
  run later, at 5-minute spacing, so the queue only grows (see F3).

Runtime evidence, `AIGEN_SYS/quorum_ledger.jsonl`: proposal `cycle-1790251326662` was
registered at 12:02:06Z, but its verdict row is stamped 12:18:37Z. That's **16.5 minutes**
inside one `runQuorumVote`, which works out to about 33 timed-out waits of 30s, or
roughly 11 accumulated proposals × 3 voters.

**F3 [code] Almost every vote is `Math.random()`.**
`ModelScheduler.drainLoop` enforces `spacingMs` ≥ 5 minutes before **every** job
(`ModelScheduler.java:166-172`, `MIN_SPACING_MS` at `:26`). A vote waits at most 30s
(`WeightedQuorumVote.java:175`), so any vote queued behind another job times out.
Timeouts fall back to `Math.random() > 0.3 ? APPROVE : REJECT` (`:178-182`).

When a vote does reach a model, it's still mostly noise:
- The scheduler sends it as `ollama.chat(model, prompt, "")` (`ModelScheduler.java:206`).
  The three-argument overload treats the second argument as the **system** prompt
  (`OllamaClient.java:112-116`), so the voting prompt becomes the system message and
  the user turn is empty `""`.
- `parseVote` (`WeightedQuorumVote.java:194-200`) checks `contains("APPROVE")` first.
  deepseek-r1 emits a `<think>` block that usually mentions both words, so its votes
  lean APPROVE.
- Anything unparseable also falls back to RNG.

Resonance weighting is a no-op in practice:
- `advanceTimePulse` moves every model by the same delta (`:115-120`), so all voters
  always share one phase.
- All three voters are within `fowHop=1` of hex (0,0) (`AgentManager.java:180-182`),
  so BLIND never happens and FOW gating never engages.

With `approveMin=2` and weights of 1.0–1.5, any 2 approves pass. At 70% approve per
voter, about 78% of proposals pass on dice alone.

**F4 [code] The critic path is dead in practice.**
`autonomousCycle` sets `lastToolActions = null` (`AgentManager.java:363`), enqueues the
tool round (`:364`), then waits 2.5s (`:375`). The tool round sits behind the ≥5-minute
spacing gate, so it can't finish in 2.5s. By the time it does finish, the **next**
cycle's `:363` has already nulled the result.
Even without F1, the critic almost never sees work. With F1, it never does. The H01
comment's "one short grace window" assumption doesn't hold with 5-minute spacing.

**F5 [code] Act comes before vote, and the vote gates nothing.**
In `autonomousCycle` the tool round, which can edit, create or delete files, is
submitted at `:364` with **no** quorum gate. The cycle proposal is registered afterwards
at `:403`/`:438`. Its verdict only drives the world flare (`:449`) and
`QuorumTaskSpawner` (`:453`); it never feeds back into `executeTool`.
Only `solveIssues` is quorum-gated, and per F3 that gate is ~78% dice.
The "proposal → quorum-vote → act" loop the architecture describes doesn't exist for
the main actor.

**F7 [code] Duplicate GitHub issue spam — an external side effect.**
- `approvedTopics` (`AgentManager.java:105`) is append-only with no dedupe. Each 60s
  lexical scan re-clusters the **entire** chat history (`:531-566`) and re-appends the
  same topics.
- `raiseApprovedTopics` (`:291-310`) runs every cycle over the full list.
  `GitHubIssueStream.raise` has pacing only (`GitHubIssueStream.java:51-56`) — no dedupe
  and no per-repo cap, despite its javadoc (`:19-20`) saying there is one.
- Net effect when authenticated and not in demo mode: one new "Agent proposal: <topic>"
  issue per cycle, forever, filed on **whatever repo the player is standing in**
  (`:294`), even when the topic has nothing to do with that repo.

**F8 [code] `solveOne` bypasses the scheduler and has no write guard.**
- `AgentManager.java:759` calls `ollama.chat(...)` directly from the agent thread,
  outside the "single gate" `ModelScheduler`. `OllamaClient.modelGate` serializes the
  HTTP call, but spacing and resource fencing are skipped. The prompt again lands in the
  system slot with an empty user turn.
- It then does `Files.writeString(file, fixed)` (`:767`) with a sub-2B model's
  "corrected full file" for files up to 200 KB (`:750`). The only guard is
  `!fixed.equals(content)`.
- A truncated or hallucinated reply therefore overwrites real source in any non-legacy
  repo, and there's no AstGate / ToolExecutor check. Step 87 added AstGate to
  `ToolExecutor` for agent-written `.py`, but neither this path nor `executeTool`
  (`:957`, `:974`, `:987`) goes through ToolExecutor.

**F9 [code] Path traversal on the local write, create and delete paths.**
`Path.of(currentRoom.getLocalPath(), filename)` (`AgentManager.java:912, 957, 974, 987, 1094`)
never normalizes the path or checks that it stays inside the repo root. A model-supplied
`../../other_repo/x` or an absolute path escapes the repo. The same applies to
`solveOne` via `issue.file`, though that value is scanner-derived and lower risk. It's
latent while F1 stands, but becomes live the moment F1 is fixed. **Fix F9 before or
together with F1.**

**F10 [code] QuorumTaskSpawner breaks the one-task-at-a-time rule and can corrupt its own files.**
- `QuorumTaskSpawner.java:30,66`: it writes `TASK_1xxx_*.md` straight into
  `todo_files/mindpalace/`, which breaks AGENTS.md hard rule 4 (one TASK pair at a time).
  Per F3, it fires on ~78% of cycles.
- `:68` uses `CREATE, WRITE` without `TRUNCATE_EXISTING`. Task numbers are
  `1000 + hash % 1000`, so collisions happen. A shorter body written over a longer file
  leaves the old tail behind, which corrupts the task file.
- `:91`: the vote tally renders as literal `?` characters, a mojibake leftover
  (`?%d ?%d ?%d`).
- `QuorumResult.suggestedActions` aliases the live `Proposal.suggestedActions` map
  (`WeightedQuorumVote.java:221`), and the spawner mutates it (`:60`).

Runtime: only one `QUORUM_SPAWN` line so far in `task_watch.log`, and no `TASK_1*` file
exists now. The damage is small so far, but the path is live.

### Thread-safety (CME class)

**F6 [code] AgentChat.messages is a plain ArrayList mutated from three non-game threads.**
- `AgentChat.java:22` is a plain `ArrayList`. `renderMessage` does `add` and
  `remove(0)` (`:50-54`).
- `render()` index-iterates it on the game thread (`:192-196`), and `getMessages()`
  (`:216`) leaks the raw list.
- The callbacks wired at `GameEngine.java:491-493` (`msg -> agentChat.addMessage(msg)`)
  are invoked from:
  - the `ModelScheduler` drain worker (`executeToolRound` → `emit`, `AgentManager.java:818, 848, 867`);
  - the `userWorker` thread (the `onUserChat` `thenAccept`, `:326-329`);
  - the drain worker again (the critic `thenAccept`, `:389-398`).
- Result: an `IndexOutOfBoundsException` in `render()` (the size is read at `:192`, then
  the list shrinks) or corruption of the ArrayList's internals.
- `BdiBridge` (`BdiBridge.java:26-28, 115-137`) documents exactly this hazard and routes
  around it with a `ConcurrentLinkedQueue` inbox drained on the game thread. AgentManager
  doesn't.
- `persist()` also appends to the same per-day file from several threads at once, so
  JSONL lines can interleave.

**F11 [code] Unsynchronized shared collections and fields inside AgentManager.**
| Field | Writers | Readers | Hazard |
|---|---|---|---|
| `discoveredRepos` HashSet `:53` | game thread (`setContext` `:201-202`) | agent thread (`buildContext` `:1005`, `new HashSet<>(discoveredRepos)` `:616`) | CME during the copy |
| `approvedTopics` ArrayList `:105` | agent thread (`:556, :562`) | agent thread (`:292-296` foreach, `:571`); game thread in selftest; raw getter `:705` | CME if read off-thread; unbounded growth |
| `kits` LinkedHashMap `:253` | `computeIfAbsent` from the game thread (`onUserChat` → `ctx`), the agent thread (`solveOne`), and the drain worker (`executeToolRound`) | same | HashMap corruption; `ContextKit.kgNodes` `clear`/`addAll` race (`ContextKit.java:34-37`) |
| `rephraseAttempts` LinkedHashMap-backed set `:1049` | `emit` from the drain worker and the userWorker | same | unsynchronized access-ordered map |
| `currentRoom`, `currentBook`, `lastUserMessage`, `running`, `available`, `lastAutoCycle`, `lastLexicalScan` | game thread | agent + drain threads | not `volatile`, so stale reads are possible. `currentRoom` is read twice in `executeTool` (`:896`, then `:911`), so a room change mid-call can write into the wrong repo |
| `GameEngine.quorumFlare` float `:1613` | agent thread (the verdict callback, `:479, :483`) | render thread | not volatile; benign, but `audio.playQuorum*` also runs off-thread, so check AudioEngine |

**F12 [code] Crystal cap is check-then-act.** `GameEngine.java:499` (`size() >= 60`),
then `:510` (`add`). The render thread and the scout path (`:4734/4752`) add concurrently,
so the cap is soft. Snapshot discipline is otherwise **correct**: every crystal iteration
site (`:1951, :3055, :4765`) snapshots via `new ArrayList<>(crystals)`, which locks through
`SynchronizedCollection.toArray`. `npcs` is a COW list, and BdiBridge only iterates it. ✔

**F13 [code] If `drainLoop` throws, the future is never completed.**
`ModelScheduler.java:218-219` / `:151-152`: if `lifespan.chat` or `ollama.chat` throws a
RuntimeException, `job.future` never completes. For example, `OllamaClient.java:101-102`
NPEs when a body has no `message`, and `JsonSyntaxException` isn't an IOException.
Consequences:
- User-chat callers hang with no reply.
- Vote callers burn their full 30s.

Needs `job.future.completeExceptionally(e)` / `complete(null)` in the catch.

**F14 [code] "User chat never waits" is false at the HTTP layer.**
The userWorker holds no scheduler lock, but `OllamaClient.modelGate` (`:93`, `:158`) is
shared, so a user reply still blocks behind an in-flight autonomous call. That can be up
to 120s (read timeout, `:26`), or a cold load of deepseek-r1 with `keep_alive:0`. The
ModelScheduler javadoc (`:11-13, :96-100`) claims otherwise. This matches the "chat looks
dead" symptom it was trying to fix.

### Dead or misleading paths

- **D1** `toolLifespan` (`AgentManager.java:45, 157`): its system prompt is set, but it
  never carries a call. `executeToolRound` builds raw `msgs`. Only exposed via the getter
  at `:1242`.
- **D2** `FOWGate fow` (`:69, 172-175`): populated, but nothing in the vote or act path
  consults it. Quorum visibility uses `WeightedQuorumVote.isVisible`. Only the selftest
  reads counts (`GameEngine.java:3645`).
- **D3** `LoRASwitcher lora` (`:67`): `switchAdapter(CODE)` every cycle (`:356`), but
  `ContextKit` bakes `AdapterType.CODE` at construction (`:258`, `ContextKit.java:21`), so
  the switcher's state never reaches any prompt.
- **D4** `ModelRouter.selectWithLatency` / `estimateLatency` have no callers.
  `router.select` also routes the tool round to `tinyllama:1.1b` / `phi:latest`
  (`ModelRouter.java:14-15`), which don't support Ollama tool-calling. Those tiers make
  `chatWithTools` return non-2xx, which becomes an empty `ToolResult` and a silent no-op.
  - `Complexity.estimate` (`Complexity.java:32`) scores an ~800-char context plus signal
    words like "fix"/"bug", which are common in code excerpts, so MEDIUM and above are
    routine.
  - `TOOL_SYSTEM_PROMPT` states "You are running on llama3.2:1b" (`:1217`), which is
    false whenever the router picks another model.
- **D5** `TIE_MODEL` is labelled a "tie-breaker" (`ModelConfig.java:21`,
  `AgentManager.java:177-182`), but it's just a third unconditional voter. With 3 voters
  there are no 1-1 ties to break.
- **D6** Lexical bridge can't read structured events. `readChatLogs` only extracts
  `"msg":"…"` (`AgentManager.java:588`). H28's `addBotEvent` writes `"text":"…"`
  (`AgentChat.java:99-100`), whose javadoc (`:59-63`) says the change was *for* the
  lexical bridge. So BDI, Scout and Quorum events never reach it.
- **D7** `initReddit` / `initGitHub` (`:1261`, `:1298`) have no callers in `src/main`.
  The Reddit→quorum and GitHub-poll bridges are wired, but nothing starts them.
- **D8** `AgentChat.isOpen()` always returns `true` (`:215`).
- **D9** The cycle-level `Thread.sleep(2500)` (`:375`) blocks one of the two
  `scheduler` pool threads for no gain (see F4).

---

## RECOMMENDATIONS (ranked, all additive)

1. **Make the voting path bounded: fixes F2 and F3, and unblocks everything else.**
   - Add `actualVote(String proposalId, ModelScheduler)` to `WeightedQuorumVote` that
     votes only that proposal. Keep `actualVoteAll` for compatibility.
   - Switch the three call sites (`AgentManager.java:440, 559, 731`) to it.
   - Add a proposal TTL/cap: evict proposals that are decided or older than N cycles,
     keeping ~64.
   - Give votes their own path that skips the 5-minute spacing gate — a
     `submitVote(...)` that is fenced but not spaced. Or accept that votes cost one spaced
     slot and wait `spacing + 30s` instead of 30s.
   - Send the prompt as the **user** turn with a short system prompt.
   - Strip `<think>…</think>` before `parseVote`, and take the *last* APPROVE/REJECT token.
   - Record fallbacks as `ABSTAIN`/`BLIND`, not dice. Keep RNG only in `autoVoteAll` for
     the selftest.
   - Cost: ~40 lines. Selftest impact: none. It uses `autoVoteAll` and `castVote`.
2. **Fix tool-call argument parsing (F1) together with a path jail (F9).** In
   `OllamaClient.java:174`:
   `JsonElement a = fn.get("arguments"); args = a.isJsonPrimitive() ? a.getAsString() : a.toString();`
   and widen the catch at `:181` to `IOException | RuntimeException`. **In the same
   commit**, add `resolveInRepo(root, filename)`: `normalize()`, require
   `startsWith(root)`, reject absolute paths. Route `executeTool` and `solveOne` writes
   through `ToolExecutor`/AstGate so step 87's guard covers them. Add a selftest check
   that feeds an object-shaped `arguments` payload and a `../` filename.
3. **Marshal every AgentChat write onto the game thread (F6).** Copy the BdiBridge
   pattern: have AgentManager's callbacks push to a `ConcurrentLinkedQueue<String>`,
   drained in `GameEngine.update()` next to `bdiBridge.drain()`. Alternatively, make
   `AgentChat.messages` a `CopyOnWriteArrayList` (12 entries, so COW is effectively free)
   and snapshot once in `render()`. Also make `getMessages()` return an unmodifiable
   snapshot.
4. **Stop the issue spam (F7).**
   - Change `approvedTopics` to a bounded `LinkedHashSet` (dedupe), wrapped in
     `Collections.synchronizedSet`, with iteration via snapshot.
   - Keep a `raisedTopics` set, persisted beside `reddit_ledger.txt`, and raise each topic
     once.
   - Add the per-repo cap that the `GitHubIssueStream` javadoc already promises.
   - Re-cluster only new chat lines (a byte offset per file) instead of the whole history
     every 60s.
5. **Gate the act on the vote (F5).** Register the cycle proposal *before* `runToolLoop`,
   and only submit the tool round if it's APPROVED. Or run the tool round in read-only
   mode (`read_file` only) unless approved, which is cheaper and keeps the loop alive.
   Move the critic into the tool round's completion: call a continuation from the end of
   `executeToolRound` instead of polling `lastToolActions` after 2.5s. That removes F4 and
   D9 entirely.
6. **Complete futures on error (F13)** in both drain loops, and change `OllamaClient`'s
   `catch (IOException)` to also catch `RuntimeException` (NPE and JsonSyntax). Three
   lines total.
7. **Visibility hygiene (F11).** Mark `currentRoom`, `currentBook`, `lastUserMessage`,
   `running` and `available` `volatile`. Make `discoveredRepos` a
   `ConcurrentHashMap.newKeySet()`. Make `kits` a `ConcurrentHashMap`. Wrap
   `rephraseAttempts` in `Collections.synchronizedSet` or confine it to one thread. In
   `executeTool`, capture `Room room = currentRoom` once and use it throughout.
8. **Put QuorumTaskSpawner behind the TASK rule (F10).** Write to a
   `todo_files/mindpalace/quorum_inbox/` subfolder, or append to `BACKLOG.md`, instead of
   creating live TASK files. Add `TRUNCATE_EXISTING` (or `CREATE_NEW` with a unique
   suffix). Fix the `?` tally glyphs. Copy `suggestedActions` in `QuorumResult` instead of
   aliasing it.
9. **Router honesty (D4).** Restrict the tool round's routing to tool-capable models
   (llama3.2:1b/3b, qwen2.5). Keep the router for the chat and critic tiers. Render the
   real model name into the system prompt per call.
10. **D6.** Also extract `"text":"…"` in `readChatLogs`. That's one branch, and it
    closes H28's stated intent.
11. **Docs.** Fix the ModelScheduler javadoc claim (F14) and the GitHubIssueStream
    "per-repo cap" claim, or implement the cap (rec 4). Update AGENTS.md: "tool=llama3.2:1b"
    isn't what actually runs (the router overrides it).

---

## PERF-NOTE (Intel HD 510 / 945M class)

The GPU is never touched by this package. Everything here is CPU/IO, off the render
thread, except as noted below. The real perf risks are host-side:

- **Unbounded chat re-scan.** `readChatLogs` does `readAllLines` on **every** per-day
  JSONL, every 60s, then clusters with O(n²) cosine in `LexicalAnalyzer.cluster`. Cost
  grows linearly with days of play. Fix: rec 4's incremental offsets.
- **Repo walk.** `findIssuesForTopics` calls `Files.walk` over every non-legacy repo, up
  to 500 files each, reading up to 200 KB per file and uppercasing whole files, on every
  lexical tick that has approved topics. On a laptop HDD that's seconds of IO contention
  with texture and asset loads. Suggest caching per-file mtime, or a single
  `indexOf`-case-insensitive scan without `toUpperCase()` on the whole content.
- **Selftest on the game thread.** `runLexicalBridge()` is called directly from the
  selftest (`GameEngine.java:3693`) on the game thread, so the repo walk above can stall
  frames during `--selftest` and `e2e.sh`.
- **Cold-shot models.** `keep_alive:0` plus a vote for each of deepseek-r1:1.5b,
  llama3.2:1b and qwen2.5:0.5b means three model loads from disk per proposal. Once F2 is
  fixed (one proposal per vote pass) that's acceptable at 5-minute spacing. Today (P
  proposals × 3) it's the dominant RAM/disk churn on a 4–8 GB machine.
- **Allocation churn.** `ctx()` allocates each call, and `String.format` runs in
  `ContextKit.render`. It's trivial next to model latency; no action needed.
- **Recommendation cost.** Every recommendation above is O(1) memory or a *reduction* in
  work. None adds geometry, shaders or per-frame cost. Rec 3's per-frame queue drain is
  about 12 entries, so negligible.

---

**Summary:** Blocked. The tool loop can't run a tool because it parses arguments as a string when Ollama sends an object, so the critic has nothing to review. The quorum is mostly dice, re-votes every proposal ever made (16.5 min for one vote in the ledger), gates nothing, and can spam GitHub. AgentChat is written off the game thread. The fixes are bounded voting, an arguments-parse fix shipped with a path jail, and a game-thread chat inbox, all additive and cheap.
