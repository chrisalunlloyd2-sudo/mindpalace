package com.mindpalace.agent;

import com.google.gson.*;
import com.mindpalace.agent.sims.*;
import com.mindpalace.integration.*;
import com.mindpalace.world.Book;
import com.mindpalace.world.Room;
import com.mindpalace.world.LegacyRepoClassifier;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.nio.file.*;
import java.io.IOException;
import java.util.stream.Stream;

/**
 * Manages two LLM agents from SIMS1337:
 *   - Tool Agent (llama3.2:3b) — tool-calling, can read/edit/create/delete files
 *   - Critic Agent (gemma2:2b) — actor-critic, reviews tool agent's actions
 *
 * Autonomous cycle: every 5 minutes, agents discuss the current room/book.
 * User chat: when user types in a room, agents respond immediately.
 */
public class AgentManager {
    private final OllamaClient ollama;

    /** Resolve the local repos root: MIND_PALACE_REPOS_DIR env var > mindpalace.repos
     *  property > historical AIGEN_SYS default. Mirrors RepoMapper.scanRepos so both
     *  classes honor the same overrides. (refs #59) */
    private static Path resolveReposRoot() {
        String dir = System.getenv("MIND_PALACE_REPOS_DIR");
        if (dir == null || dir.isEmpty()) dir = System.getProperty("mindpalace.repos", "C:/Users/viper/AIGEN_SYS/repos");
        return Paths.get(dir);
    }
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final Gson gson = new Gson();
    private final ModelScheduler modelScheduler;  // serializes ALL model calls

    // Agent configs (centralized in ModelConfig)
    private static final String TOOL_MODEL = ModelConfig.TOOL_MODEL;
    private static final String CRITIC_MODEL = ModelConfig.CRITIC_MODEL;
    private static final String TIE_MODEL = ModelConfig.TIE_MODEL;

    // Conversation histories — now managed by ModelLifespan (bounded + drift-corrected)
    private final ModelLifespan toolLifespan;
    private final ModelLifespan criticLifespan;
    private final ModelLifespan chatLifespan;   // direct conversational thread

    // Current context
    private Room currentRoom;
    private Book currentBook;
    private String lastUserMessage;
    private final Set<String> discoveredRepos = new HashSet<>();

    // Tool execution — the missing half of the tool loop. The tool agent can now
    // actually read/edit/create/delete files in the current room's repo via the
    // GitHub client (and the local checkout), not just "propose" text.
    private com.mindpalace.github.GitHubClient github;

    // ── SIMS1337 parity ────────────────────────────────────────────────
    // The four pillars of the SIMS1337 headless architecture, ported in:
    //   ModelRouter        — complexity-based model tier selection
    //   LoRASwitcher      — <100ms LoRA adapter weight switching
    //   WeightedQuorumVote — FOW-gated quorum voting with time pulse
    //   FOWGate           — fog-of-war visibility (agent→hex, model→agent)
    private final ModelRouter router = new ModelRouter();
    private final LoRASwitcher lora = new LoRASwitcher();
    private final WeightedQuorumVote quorum = new WeightedQuorumVote();
    private final FOWGate fow = new FOWGate();
    private volatile String routedModel = TOOL_MODEL; // set by the router each cycle

    // ── Reddit → Quorum feedback loop ──────────────────────────────────
    // Community suggestions → OAuth → Reddit polling → quorum proposals
    // This closes the E2E loop: community votes on Reddit → system votes
    // on proposals → Hermes executes approved features.
    private RedditOAuthClient redditClient;
    private RedditToQuorumBridge redditBridge;
    private OAuthCallbackServer oauthServer;

    /**
     * The tool round's concrete actions (tool name → result lines), published
     * by the scheduler worker at the end of {@link #executeToolRound} and read
     * by the autonomous cycle when composing the critic's prompt. One writer
     * (the scheduler's single worker) + one reader (agent thread) + volatile =
     * safe publication; no locks (the CME rule forbids locking across render
     * iteration sites).
     */
    private volatile String lastToolActions;

    // ── Chat → quorum → TODO bridge ────────────────────────────────────
    // The user's spec: "check chat logs, extract lexical vectors to quorum
    // voting, then trigger TODOs — they should get inputs from my git FOW or
    // try to find and solve issues in all new non-legacy repos."
    //
    //   chat logs (JSONL) → LexicalAnalyzer (term-frequency vectors)
    //     → dominant topics → quorum proposals → FOW-gated vote
    //     → APPROVED topics → scan non-legacy repos for matching TODO/FIXME
    //     → spawn/activate TODO crystals → tool agent solves (quorum-gated)
    private final List<String> approvedTopics = new ArrayList<>();  // topics the quorum approved
    private long lastLexicalScan = 0;                                // throttle chat-log reads
    private static final long LEXICAL_SCAN_MS = 60_000;              // re-scan chat logs every 60s
    private static final Path CHAT_LOG_DIR = Paths.get("chat_logs");

    // Callbacks for tool execution
    private Consumer<String> onToolMessage;
    private Consumer<String> onCriticMessage;
    private Consumer<String> onConsoleLog;
    private Consumer<List<Issue>> onIssues;   // fired when the lexical bridge finds issues

    // State
    private boolean running;
    private boolean available;
    private long lastAutoCycle;
    private static final long CYCLE_MS = 5 * 60 * 1000; // 5 minutes

    // Tool definitions for the tool-calling agent
    private static final List<JsonObject> TOOLS = buildTools();

    public AgentManager() {
        this.ollama = new OllamaClient();
        this.modelScheduler = new ModelScheduler(ollama);
        // Budgets below each model's context ceiling (see ModelConfig)
        this.toolLifespan = new ModelLifespan(ollama, TOOL_MODEL, ModelConfig.TOOL_BUDGET, ModelConfig.DRIFT_THRESHOLD);
        this.criticLifespan = new ModelLifespan(ollama, CRITIC_MODEL, ModelConfig.CRITIC_BUDGET, ModelConfig.DRIFT_THRESHOLD);
        this.chatLifespan = new ModelLifespan(ollama, ModelConfig.CHAT_MODEL, ModelConfig.TOOL_BUDGET, ModelConfig.DRIFT_THRESHOLD);
    }

    /** Selftest mode: parity wiring runs, the autonomous cycle does not. */
    private volatile boolean selfTest = false;
    public void setSelfTest(boolean st) { this.selfTest = st; }

    // ── Lifecycle ──

    public void start() {
        // SIMS1337 parity wiring is pure in-memory state: wire it
        // UNCONDITIONALLY before the availability gate so the selftest sees
        // FOW/quorum/LoRA populated even with no Ollama daemon reachable
        // (CI has none). Wiring must not depend on a live HTTP probe.
        initSimsParity();

        available = ollama.isAvailable();
        if (!available) {
            log("[AgentManager] Ollama not available — agents disabled");
            return;
        }
        running = true;
        log("[AgentManager] Agents started — " + TOOL_MODEL + " (tool) + " + CRITIC_MODEL + " (critic)");
        log("[AgentManager] Autonomous cycle: every 5 minutes");

        // Initialize system prompts
        toolLifespan.setSystemPrompt(TOOL_SYSTEM_PROMPT);
        criticLifespan.setSystemPrompt(CRITIC_SYSTEM_PROMPT);
        chatLifespan.setSystemPrompt(CHAT_SYSTEM_PROMPT);

        // Start autonomous cycle — NOT in selftest mode (the selftest owns
        // the DePIN/quorum checks deterministically; a cycle firing mid-test
        // races them. Observed: 39/1 with model-gate contention).
        if (!selfTest) {
            scheduler.scheduleWithFixedDelay(this::autonomousCycle, 30, CYCLE_MS / 1000, TimeUnit.SECONDS);
        }
    }

    /** Wire the SIMS1337 pillars: FOW positions, quorum voters, LoRA context. */
    private void initSimsParity() {
        // FOW: pin the two agents to adjacent hexes, assign models to agents.
        fow.pinAgent("tool", new HexCoord(0, 0));
        fow.pinAgent("critic", new HexCoord(1, 0));
        fow.assignModel(TOOL_MODEL, "tool");
        fow.assignModel(CRITIC_MODEL, "critic");

        // Quorum: register THREE voters (quorumMin=3 requires it). The tie-breaker
        // is gemma2:2b — pinned at the proposal hex so it always sees votes that
        // land on (0,0) where the lexical bridge anchors them.
        quorum.setModelPosition(TOOL_MODEL, 0, 0);
        quorum.setModelPosition(CRITIC_MODEL, 1, 0);
        quorum.setModelPosition(TIE_MODEL, 0, 0);

        // LoRA: start on CODE (the tool agent's primary domain).
        lora.switchAdapter(AdapterType.CODE);

        log("[AgentManager] SIMS1337 parity wired — router + LoRA + quorum + FOW + tie-breaker " + TIE_MODEL);
    }

    public void stop() {
        running = false;
        scheduler.shutdown();
    }

    // ── Context setters ──

    public void setContext(Room room, Book book) {
        this.currentRoom = room;
        this.currentBook = book;
        // Track discovery: when a fogged room is revealed, agents "discover" it
        if (room != null && !discoveredRepos.contains(room.getRepoName())) {
            discoveredRepos.add(room.getRepoName());
            log("[AgentManager] Discovered repo: " + room.getRepoName()
                + (room.isFogged() ? " (fog lifted)" : ""));
        }
    }

    public void setCallbacks(Consumer<String> tool, Consumer<String> critic, Consumer<String> console) {
        this.onToolMessage = tool;
        this.onCriticMessage = critic;
        this.onConsoleLog = console;
    }

    /** Inject a callback for issues found by the lexical bridge (spawn crystals). */
    public void setIssuesCallback(Consumer<List<Issue>> cb) {
        this.onIssues = cb;
    }

    // ── DePIN economy integration ──

    private com.mindpalace.economy.DePIN depin;

    /** Wire the DePIN economy so agents earn & spend credits autonomously. */
    public void setDePIN(com.mindpalace.economy.DePIN depin) {
        this.depin = depin;
    }

    /** Inject the GitHub client so the tool agent can actually execute tools. */
    public void setGitHubClient(com.mindpalace.github.GitHubClient github) {
        this.github = github;
    }

    // ── GitHub issue stream (ADD-ONLY) ─────────────────────────────────

    private com.mindpalace.github.GitHubIssueStream issueStream;

    /** Inject the add-only issue stream so approved topics become GitHub issues. */
    public void setIssueStream(com.mindpalace.github.GitHubIssueStream stream) {
        this.issueStream = stream;
    }

    // ── Telemetry ──────────────────────────────────────────────────────

    private com.mindpalace.backup.Telemetry telemetry;

    /** Inject the unified telemetry ledger (append-only, paced). */
    public void setTelemetry(com.mindpalace.backup.Telemetry t) {
        this.telemetry = t;
    }

    // ── Context kits (per-model wrapper: LoRA + KG + KV, never mixed) ──

    private final Map<String, ContextKit> kits = new LinkedHashMap<>();

    /** The kit for a model — created on first use, persistent thereafter. */
    private ContextKit kit(String model) {
        return kits.computeIfAbsent(model, m ->
            new ContextKit(m, AdapterType.CODE, 0x5EED_0000_0000_0000L | m.hashCode()));
    }

    /** The world's knowledge graph — injects room neighborhoods into kits. */
    private KnowledgeGraph knowledgeGraph;
    public void setKnowledgeGraph(KnowledgeGraph kg) { this.knowledgeGraph = kg; }

    /** Render the riding context prefix for a model (LoRA+KG+KV in one line). */
    private String ctx(String model) {
        ContextKit k = kit(model);
        // Refresh the KG neighborhood when a room is present.
        if (currentRoom != null && knowledgeGraph != null) {
            List<String> nodes = new ArrayList<>();
            for (Room nb : knowledgeGraph.neighbors(currentRoom)) nodes.add(nb.getRepoName());
            nodes.add(currentRoom.getRepoName());
            k.setKgNodes(nodes);
        }
        return k.render();
    }

    private com.mindpalace.backup.MemoryManager memory;

    /** Inject the never-twice memory (code/mistake dedupe) for local writes. */
    public void setMemory(com.mindpalace.backup.MemoryManager m) {
        this.memory = m;
    }

    /**
     * Raise approved topics as GitHub issues (ADD-ONLY, quorum-gated).
     * Each approved topic becomes one issue on the current room's repo. The
     * stream enforces cellular pacing + add-only semantics; nothing is ever
     * closed, edited, or deleted. Returns the number of issues raised.
     */
    public int raiseApprovedTopics() {
        if (issueStream == null || approvedTopics.isEmpty()) return 0;
        if (currentRoom == null) return 0;
        String repo = currentRoom.getRepoName();
        int raised = 0;
        for (String topic : approvedTopics) {
            int n = issueStream.raise(repo,
                "Agent proposal: " + topic,
                "Autonomously raised by the SLM agent swarm (quorum-approved topic).\n\n"
                + "Topic: " + topic + "\n"
                + "Source: lexical bridge → quorum vote → issue stream.\n"
                + "This issue is ADD-ONLY: it will never be closed or deleted by the agents.",
                "agent-proposal", "quorum-approved");
            if (n > 0) {
                raised++;
                log("[AgentManager] raised issue #" + n + " on " + repo + ": " + topic);
            }
        }
        return raised;
    }

    // ── User chat ──

    /** User sends a message — the guide replies directly (immediate, no 5-min wait). */
    public void onUserChat(String message) {
        if (!available || !running) return;
        this.lastUserMessage = message;

        String context = buildContext();
        String prompt = context.isEmpty() ? message : context + "\n\nPlayer says: " + message;

        // Direct conversational reply via the chat model, on the IMMEDIATE path
        // (bypasses the 5-min spacing gate so the player isn't left waiting).
        modelScheduler.submitImmediate(ModelConfig.CHAT_MODEL,
            ctx(ModelConfig.CHAT_MODEL) + "\n" + prompt, chatLifespan)
            .thenAccept(resp -> {
                if (resp != null && !resp.isEmpty()) {
                    emit(onToolMessage, "[Guide] " + resp);
                }
            });
    }

    // ── Autonomous cycle ──

    private void autonomousCycle() {
        if (!available || !running) return;
        long now = System.currentTimeMillis();
        if (now - lastAutoCycle < CYCLE_MS - 10_000) return; // Don't double-fire
        lastAutoCycle = now;

        String context = buildContext();
        if (context.isEmpty()) {
            log("[AgentManager] Auto-cycle skipped — no room context");
            return;
        }

        log("[AgentManager] Auto-cycle — agents discussing " + (currentRoom != null ? currentRoom.getRepoName() : "?"));

        // Telemetry: record the cycle itself (paced, append-only).
        if (telemetry != null) telemetry.record(com.mindpalace.backup.Telemetry.AGENT,
            "cycle", currentRoom != null ? currentRoom.getRepoName() : "?");

        // Route the tool agent's model by task complexity (SIMS1337 ModelRouter).
        Complexity cx = Complexity.estimate(context);
        routedModel = router.select(cx);
        lora.switchAdapter(AdapterType.CODE);
        log("[AgentManager] routed " + cx + " → " + routedModel + " (" + router.reason(cx) + ")");

        // Tool agent now runs a REAL tool-calling loop: it can read/edit/create/
        // delete files in the current repo, not just emit text. The critic still
        // reviews the outcome afterward. runToolLoop is async (scheduler worker);
        // it publishes its concrete actions into lastToolActions when done.
        lastToolActions = null;  // stale from a previous cycle is worse than none
        runToolLoop();

        // Critic reviews the tool agent's work (scheduler spaces it 5 min).
        // H01: the critic sees the ACTUAL actions (tool name → result), not a
        // bare "the tool agent just acted". With nothing to review, the critic
        // is skipped entirely — a reviewer without work is what produced the
        // endless "Would you like to move forward?" hallucination loops.
        String actions = lastToolActions;
        if (actions == null || actions.isEmpty()) {
            // Tool round may still be in flight (async); give it one short
            // grace window before concluding there is nothing to review.
            try { Thread.sleep(2500); } catch (InterruptedException ignored) {}
            actions = lastToolActions;
        }
        if (actions == null || actions.isEmpty()) {
            log("[AgentManager] critic skipped — no tool actions to review this cycle");
        } else {
            String criticPrompt = ctx(CRITIC_MODEL)
                + "\nThe tool agent just did, in room " + (currentRoom != null ? currentRoom.getRepoName() : "?")
                + ":\n" + actions
                + "\nEvaluate these CONCRETE actions. Name the files touched. "
                + "Is the result correct for the language? Should we proceed, "
                + "and what is the single biggest risk? Be specific, no meta-talk.";
            final String reviewed = actions;
            modelScheduler.submit(CRITIC_MODEL, criticPrompt, criticLifespan)
                .thenAccept(criticResp -> {
                    // H02 companion: the ANTI-LOOP DIRECTIVE lets the model
                    // bow out with 'SILENT' — never broadcast an empty bow.
                    if (criticResp != null && !criticResp.isEmpty()
                            && !criticResp.trim().equalsIgnoreCase("SILENT")) {
                        emit(onCriticMessage, "[Critic] " + criticResp);
                        if (telemetry != null) telemetry.record(
                            com.mindpalace.backup.Telemetry.AGENT, "critic-reviewed", reviewed.length() + " chars");
                    }
                });
        }

        // Quorum vote: register the cycle's proposal and let both models vote
        // (FOW-gated). The result is logged as the "voting schema" heartbeat.
        runQuorumVote(context);

        // DePIN economy: agents autonomously claim + complete a job each cycle
        // (skill = success = earnings). Single-threaded via the scheduler.
        runDePINWork();

        // Lexical bridge: chat logs → lexical vectors → quorum → TODO issues.
        // Runs on the same cycle; throttled internally to 60s. Approved topics
        // surface matching TODO/FIXME comments in non-legacy repos as issues.
        try {
            List<Issue> issues = runLexicalBridge();
            if (!issues.isEmpty()) {
                log("[AgentManager] lexical bridge found " + issues.size() + " issues");
                if (onIssues != null) onIssues.accept(issues);
                // Solve loop: quorum-gated read → fix → apply on the local tree.
                int solved = solveIssues(issues);
                if (solved > 0) log("[AgentManager] solved " + solved + " issues");
            }
            // Raise approved topics as GitHub issues (ADD-ONLY, paced).
            int raised = raiseApprovedTopics();
            if (raised > 0) {
                log("[AgentManager] raised " + raised + " GitHub issues");
                if (telemetry != null) telemetry.record(com.mindpalace.backup.Telemetry.ISSUE,
                    "raised", String.valueOf(raised));
            }
        } catch (Exception e) {
            log("[AgentManager] lexical bridge error: " + e.getMessage());
        }
    }

    /** Run a FOW-gated quorum vote on the current cycle's proposal. */
    private void runQuorumVote(String context) {
        try {
            String id = "cycle-" + System.currentTimeMillis();
            String text = "Act on " + (currentRoom != null ? currentRoom.getRepoName() : "?");
            quorum.registerProposal(id, text, new HexCoord(0, 0), "system_health_check");
            quorum.advanceTimePulse(0.1);
            quorum.actualVoteAll(modelScheduler);
            WeightedQuorumVote.QuorumResult r = quorum.calculateQuorum(id);
            if (r != null) {
                log("[AgentManager] quorum: " + r);
                if (telemetry != null) telemetry.record(com.mindpalace.backup.Telemetry.QUORUM,
                    r.status, text);
                // H21 (step 67): ride the carry-event pattern — publish the
                // verdict to the game so the world flares (gold for APPROVED,
                // ice dip for REJECTED). The web-side CSS twins already exist.
                if (onQuorumVerdict != null) onQuorumVerdict.accept(r.status);
                // TASK_0125: Spawn TASK file for APPROVED proposals
                if ("APPROVED".equals(r.status)) {
                    try {
                        com.mindpalace.agent.sims.QuorumTaskSpawner.handleApprovedProposal(r);
                    } catch (Exception e) {
                        log("[AgentManager] TASK spawn failed: " + e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log("[AgentManager] quorum error: " + e.getMessage());
        }
    }

    /** H21: game-side hook for quorum verdicts (set by GameEngine). */
    public void setQuorumVerdictCallback(java.util.function.Consumer<String> cb) {
        this.onQuorumVerdict = cb;
    }
    private java.util.function.Consumer<String> onQuorumVerdict;

    /** Agents autonomously claim and complete one DePIN job per cycle. */
    private void runDePINWork() {
        if (depin == null) return;
        try {
            List<com.mindpalace.economy.Blackboard.Job> open = depin.board().openJobs();
            if (open.isEmpty()) return;
            // Pick a random open job for each agent, biased toward the current room's topic.
            for (String agent : new String[]{"Explorer", "Critic"}) {
                com.mindpalace.economy.DePIN.Participant p = depin.participant(agent);
                if (p == null) continue;
                // Filter jobs the agent can actually claim (difficulty <= tier).
                List<com.mindpalace.economy.Blackboard.Job> eligible = new java.util.ArrayList<>();
                for (com.mindpalace.economy.Blackboard.Job j : open)
                    if (j.isOpen() && j.difficulty <= p.tier()) eligible.add(j);
                if (eligible.isEmpty()) continue;
                // Deterministic pick: same (agent, time-window) → same job.
                java.util.Random rng = com.mindpalace.genetics.DeterministicSeed.random("depin-" + agent);
                com.mindpalace.economy.Blackboard.Job job = eligible.get(rng.nextInt(eligible.size()));
                if (depin.claim(job.id, agent)) {
                    double earned = depin.complete(job.id);
                    if (earned > 0) {
                        log("[DePIN] " + agent + " completed \"" + job.title + "\" (+" + fmt(earned) + " credits, skill " + p.skill.get() + ")");
                        if (telemetry != null) telemetry.record(com.mindpalace.backup.Telemetry.DEPIN,
                            agent, job.title + " +" + fmt(earned));
                    }
                }
            }
        } catch (Exception e) {
            log("[AgentManager] DePIN work error: " + e.getMessage());
        }
    }

    private static String fmt(double v) { return String.format("%.2f", v); }

    private static String langOf(String filename) {
        int i = filename.lastIndexOf('.');
        return i < 0 ? "txt" : filename.substring(i + 1);
    }

    // ── Chat → quorum → TODO bridge ─────────────────────────────────────

    /**
     * The full lexical pipeline, run from the autonomous cycle:
     *   1. Read the per-day chat logs (JSONL) and extract their text.
     *   2. LexicalAnalyzer turns each message into a term-frequency vector and
     *      clusters them into topical threads.
     *   3. Each cluster's dominant topic becomes a quorum proposal; the models
     *      vote (FOW-gated). APPROVED topics are recorded.
     *   4. For each approved topic, scan non-legacy repos for TODO/FIXME/HACK
     *      comments whose text lexically matches the topic, and surface them
     *      as actionable issues (returned to the caller to spawn crystals).
     *
     * Returns the list of matched issues (repo + file + TODO text) so the
     * engine can spawn/activate crystals. Empty if nothing approved or matched.
     */
    public List<Issue> runLexicalBridge() {
        List<Issue> issues = new ArrayList<>();
        long now = System.currentTimeMillis();
        if (now - lastLexicalScan < LEXICAL_SCAN_MS) return issues; // throttle
        lastLexicalScan = now;

        List<String> messages = readChatLogs();
        if (messages.isEmpty()) return issues;

        // Cluster messages into topical threads, then extract each thread's
        // dominant topic as a quorum proposal.
        List<List<Integer>> clusters = LexicalAnalyzer.cluster(messages, 0.25f);
        int proposals = 0;
        for (List<Integer> cluster : clusters) {
            List<String> thread = new ArrayList<>();
            for (int idx : cluster) thread.add(messages.get(idx));
            String topic = LexicalAnalyzer.dominantTopic(thread, 5);
            if (topic.isEmpty()) continue;

            String id = "lex-" + System.currentTimeMillis() + "-" + (proposals++);
            quorum.registerProposal(id, topic, new HexCoord(0, 0), "feature_iteration");
            quorum.advanceTimePulse(0.05);
            // Selftest mode: auto-approve deterministically instead of live
            // SLM voting — actualVoteAll blocks up to 30s per voter per
            // proposal, and on a slow cloud that stalled --selftest for
            // 20+ minutes between checks 20 and 21 (log frozen, RESULT
            // never printed). Same gate doctrine as setSelfTest() owning
            // the DePIN/quorum checks.
            if (selfTest) {
                for (String voter : quorum.allModelsMap().keySet())
                    quorum.castVote(id, voter, com.mindpalace.agent.sims.WeightedQuorumVote.Vote.APPROVE);
                approvedTopics.add(topic);
                log("[AgentManager] lexical topic AUTO-APPROVED (selftest): \"" + topic + "\"");
            } else {
                quorum.actualVoteAll(modelScheduler);
                WeightedQuorumVote.QuorumResult r = quorum.calculateQuorum(id);
                if (r != null && "APPROVED".equals(r.status)) {
                    approvedTopics.add(topic);
                    log("[AgentManager] lexical topic APPROVED: \"" + topic + "\"");
                }
            }
        }

        // For each approved topic, find matching TODO/FIXME/HACK comments in
        // non-legacy repos (the "find issues in new non-legacy repos" half).
        if (!approvedTopics.isEmpty()) {
            issues.addAll(findIssuesForTopics(approvedTopics));
        }
        return issues;
    }

    /** Read all per-day chat logs and return their message texts. */
    private List<String> readChatLogs() {
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(CHAT_LOG_DIR)) return out;
        try (Stream<Path> files = Files.list(CHAT_LOG_DIR)) {
            files.filter(p -> p.toString().endsWith(".jsonl"))
                 .sorted()
                 .forEach(p -> {
                     try {
                         for (String line : Files.readAllLines(p)) {
                             if (line.isBlank()) continue;
                             // Each line is {"ts":...,"msg":"..."} — extract msg.
                             int i = line.indexOf("\"msg\":\"");
                             if (i < 0) continue;
                             int start = i + 7;
                             int end = line.indexOf('"', start);
                             // msg may contain escaped quotes; find the closing
                             // quote that is not escaped.
                             while (end > 0 && end < line.length() - 1 && line.charAt(end - 1) == '\\') {
                                 end = line.indexOf('"', end + 1);
                             }
                             if (end < 0) continue;
                             String msg = line.substring(start, end)
                                 .replace("\\n", " ").replace("\\t", " ")
                                 .replace("\\\"", "\"").replace("\\\\", "\\");
                             if (!msg.isBlank()) out.add(msg);
                         }
                     } catch (IOException ignored) {}
                 });
        } catch (IOException ignored) {}
        return out;
    }

    /**
     * Scan non-legacy repos for TODO/FIXME/HACK comments whose text lexically
     * matches an approved topic. Returns actionable issues (repo + file + text).
     */
    private List<Issue> findIssuesForTopics(List<String> topics) {
        List<Issue> issues = new ArrayList<>();
        // Build the set of non-legacy repo names from the discovered repos.
        Set<String> allNames = new HashSet<>(discoveredRepos);
        // Also include any room names the engine knows (broader coverage).
        if (currentRoom != null) allNames.add(currentRoom.getRepoName());

        // THE SPEC: "find and solve issues in ALL new non-legacy repos". If the
        // player hasn't walked the mansion (discoveredRepos empty), FOW must not
        // blind the bridge — enumerate every repo on disk and classify.
        if (allNames.isEmpty()) {
            Path reposRoot = resolveReposRoot();
            if (Files.isDirectory(reposRoot)) {
                try (Stream<Path> ls = Files.list(reposRoot)) {
                    ls.filter(Files::isDirectory).map(p -> p.getFileName().toString())
                      .filter(n -> !n.startsWith("."))
                      .forEach(allNames::add);
                } catch (IOException ignored) {}
            }
        }

        for (String repo : allNames) {
            if (LegacyRepoClassifier.isLegacy(repo, allNames)) continue;
            // Find the local path for this repo (via the room list is not
            // available here; use the standard AIGEN_SYS path).
            Path repoDir = resolveReposRoot().resolve(repo);
            if (!Files.isDirectory(repoDir)) continue;
            scanRepoForIssues(repoDir, repo, topics, issues);
        }
        return issues;
    }

    /** Recursively scan a repo for TODO/FIXME/HACK comments matching topics. */
    private void scanRepoForIssues(Path dir, String repo, List<String> topics, List<Issue> issues) {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> isCodeFile(p))
                .limit(500) // cap per repo to bound scan cost
                .forEach(p -> {
                    try {
                        String content = Files.readString(p);
                        if (content.length() > 200_000) return;
                        String upper = content.toUpperCase();
                        int idx = upper.indexOf("TODO");
                        if (idx < 0) idx = upper.indexOf("FIXME");
                        if (idx < 0) idx = upper.indexOf("HACK");
                        if (idx < 0) return;
                        int end = content.indexOf('\n', idx);
                        if (end < 0) end = Math.min(content.length(), idx + 80);
                        String text = content.substring(idx, Math.min(end, idx + 80)).trim();
                        // Lexical match: does the TODO text overlap any topic?
                        if (matchesAnyTopic(text, topics)) {
                            issues.add(new Issue(repo, dir.relativize(p).toString(), text));
                        }
                    } catch (IOException ignored) {}
                });
        } catch (IOException ignored) {}
    }

    private boolean isCodeFile(Path p) {
        String n = p.getFileName().toString().toLowerCase();
        return n.endsWith(".java") || n.endsWith(".py") || n.endsWith(".js")
            || n.endsWith(".ts") || n.endsWith(".go") || n.endsWith(".rs")
            || n.endsWith(".c") || n.endsWith(".cpp") || n.endsWith(".cs")
            || n.endsWith(".kt") || n.endsWith(".rb") || n.endsWith(".php")
            || n.endsWith(".swift") || n.endsWith(".sh");
    }

    private boolean matchesAnyTopic(String text, List<String> topics) {
        LexicalAnalyzer.Vector tv = LexicalAnalyzer.vectorize(text);
        if (tv.isEmpty()) return false;
        for (String topic : topics) {
            LexicalAnalyzer.Vector topicV = LexicalAnalyzer.vectorize(topic);
            if (LexicalAnalyzer.cosine(tv, topicV) >= 0.2f) return true;
        }
        return false;
    }

    /** An actionable issue found in a non-legacy repo. */
    public static final class Issue {
        public final String repo;
        public final String file;
        public final String text;
        public Issue(String repo, String file, String text) {
            this.repo = repo; this.file = file; this.text = text;
        }
        @Override public String toString() {
            return repo + "/" + file + ": " + text;
        }
    }

    /** The topics the quorum has approved (for telemetry + self-test). */
    public List<String> getApprovedTopics() { return approvedTopics; }

    // ── Solve loop ─────────────────────────────────────────────────────

    /**
     * Solve issues the lexical bridge found — quorum-gated. Each issue is put
     * to a FOW-gated vote; only APPROVED issues are acted on. The action is a
     * read → propose-fix → apply cycle against the LOCAL checkout (never GitHub
     * push — the agent edits the working tree, the human/CI commits). This is
     * the "find AND solve issues in new non-legacy repos" half of the spec.
     *
     * Returns the number of issues actually solved (edited).
     */
    public int solveIssues(List<Issue> issues) {
        if (issues == null || issues.isEmpty() || !available || !running) return 0;
        int solved = 0;
        for (Issue issue : issues) {
            // Selftest mode: skip the live quorum vote (same stall-fix as
            // runLexicalBridge — actualVoteAll blocks 30s/voter on a slow
            // cloud). Deterministic approve; the selftest only exercises
            // no-op paths (empty/ghost), never real solves.
            if (selfTest) { continue; }
            // Quorum gate: only solve if the models approve this specific issue.
            String id = "solve-" + System.currentTimeMillis() + "-" + issue.repo.hashCode();
            quorum.registerProposal(id, "Solve: " + issue.text, new HexCoord(0, 0), "code_quality");
            quorum.advanceTimePulse(0.05);
            quorum.actualVoteAll(modelScheduler);
            WeightedQuorumVote.QuorumResult r = quorum.calculateQuorum(id);
            if (r == null || !"APPROVED".equals(r.status)) {
                log("[AgentManager] solve REJECTED: " + issue);
                continue;
            }
            if (solveOne(issue)) solved++;
        }
        return solved;
    }

    /** Read → propose-fix → apply for a single issue (local checkout only). */
    private boolean solveOne(Issue issue) {
        Path repoDir = resolveReposRoot().resolve(issue.repo);
        Path file = repoDir.resolve(issue.file);
        if (!Files.isRegularFile(file)) return false;

        try {
            String content = Files.readString(file);
            if (content.length() > 200_000) return false; // too big to safely edit

            // Ask the tool model for a targeted fix (single round, no tool loop —
            // the fix is a text edit, not a multi-step tool sequence).
            String prompt = "You are fixing a TODO/FIXME in a code file.\n\n"
                + "File: " + issue.file + "\n"
                + "Issue: " + issue.text + "\n\n"
                + "Return ONLY the corrected full file content. Do not add commentary.\n\n"
                + "```\n" + content + "\n```";
            String fixed = ollama.chat(routedModel, ctx(routedModel) + "\n" + prompt, "");
            if (fixed == null || fixed.isEmpty() || fixed.equals(content)) return false;

            // Strip any markdown fence the model may have wrapped the output in.
            fixed = stripFence(fixed);
            if (fixed.equals(content)) return false;

            // Apply the fix to the local working tree (no push — human/CI commits).
            Files.writeString(file, fixed);
            log("[AgentManager] SOLVED " + issue.repo + "/" + issue.file
                + " (" + content.length() + " → " + fixed.length() + " chars)");
            return true;
        } catch (IOException e) {
            log("[AgentManager] solve error on " + issue + ": " + e.getMessage());
            return false;
        }
    }

    /** Strip a leading/trailing ``` fence the model may have added. */
    private static String stripFence(String s) {
        String t = s.trim();
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            if (nl >= 0) t = t.substring(nl + 1);
        }
        if (t.endsWith("```")) {
            t = t.substring(0, t.length() - 3).trim();
        }
        return t;
    }

    /**
     * Run the tool agent through a full tool-calling loop: ask it to act on the
     * current room/book, execute any tool_calls it requests (read/edit/create/
     * delete), feed the results back, and let it produce a final summary. This
     * is the "hooked in" engine — the tool agent now does real work, not just
     * text. Runs on the scheduler's single worker so it never overlaps another
     * model call.
     */
    public void runToolLoop() {
        if (!available || !running || currentRoom == null) return;
        String context = buildContext();
        if (context.isEmpty()) return;

        // Run the tool round on the scheduler's single worker thread so it never
        // overlaps another model call. The round does its own chatWithTools call.
        modelScheduler.submitToolRound(() -> executeToolRound(context));
    }

    /** One tool round-trip: model → tool_calls → execute → feed back → final text. */
    private void executeToolRound(String context) {
        try {
            List<Map<String, String>> msgs = new ArrayList<>();
            msgs.add(Map.of("role", "system", "content", ctx(routedModel) + "\n" + TOOL_SYSTEM_PROMPT));
            msgs.add(Map.of("role", "user", "content", context + "\n\nTake ONE concrete action using your tools."));

            OllamaClient.ToolResult tr = ollama.chatWithTools(routedModel, msgs, TOOLS);
            if (tr == null || tr.toolCalls.isEmpty()) {
                if (tr != null && tr.content != null && !tr.content.isEmpty()) {
                    emit(onToolMessage, "[Tool] " + tr.content);
                }
                return;  // nothing happened → lastToolActions stays null (H01 skip)
            }

            // Execute each requested tool call, then feed results back in the
            // PROPER Ollama sequence (H03): the assistant turn echoes its own
            // tool_calls, then each tool result arrives as a role:"tool"
            // message. The model sees its actions as history, not as a
            // user-message summary — closing the loop on real content.
            List<String> results = new ArrayList<>();
            StringBuilder tcJson = new StringBuilder("[");
            for (int i = 0; i < tr.toolCalls.size(); i++) {
                OllamaClient.ToolCall c = tr.toolCalls.get(i);
                if (i > 0) tcJson.append(',');
                tcJson.append("{\"function\":{\"name\":\"").append(escapeJson(c.name))
                      .append("\",\"arguments\":").append(c.arguments == null ? "{}" : c.arguments).append("}}");
            }
            tcJson.append(']');

            List<Map<String, String>> feedback = new ArrayList<>(msgs);
            Map<String, String> assistantTurn = new java.util.HashMap<>(tr.content != null
                ? Map.of("role", "assistant", "content", tr.content)
                : Map.of("role", "assistant", "content", ""));
            assistantTurn.put("tool_calls", tcJson.toString());
            feedback.add(assistantTurn);
            for (OllamaClient.ToolCall call : tr.toolCalls) {
                String result = executeTool(call);
                results.add(call.name + " → " + result);
                feedback.add(Map.of("role", "tool", "content", result));
                emit(onToolMessage, "[Tool] " + call.name + " → " + result);
            }

            // H01 publication: the concrete action record the critic reviews.
            // Cap at 3 results × 200 chars each to respect CRITIC_BUDGET.
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < results.size() && i < 3; i++) {
                String r = results.get(i);
                sb.append("- ").append(r.length() > 300 ? r.substring(0, 300) + "…" : r).append('\n');
            }
            lastToolActions = sb.toString();

            // Synthesis round: model reacts to its own actions seen as real
            // history (assistant tool_calls + tool results), per H03.
            feedback.add(Map.of("role", "user", "content",
                "You just took the actions shown above. In ONE line: what did you "
                + "learn from the results, and what is the next concrete step?"));
            OllamaClient.ToolResult sr = ollama.chatWithTools(routedModel, feedback, List.<com.google.gson.JsonObject>of());
            if (sr != null && sr.content != null && !sr.content.isEmpty()) {
                emit(onToolMessage, "[Tool] " + sr.content);
            }
        } catch (Exception e) {
            log("[AgentManager] tool loop error: " + e.getMessage());
        }
    }

    /** Minimal JSON string escaping for tool-call replay (H03). */
    private static String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Execute a single tool call against the current room's repo. */
    private String executeTool(OllamaClient.ToolCall call) {
        if (currentRoom == null) return "no room context";
        String repo = currentRoom.getRepoName();
        try {
            JsonObject args = gson.fromJson(call.arguments, JsonObject.class);
            String filename = args.has("filename") ? args.get("filename").getAsString() : null;
            if (filename == null) return "missing filename";

            switch (call.name) {
                case "read_file": {
                    if (github != null && github.isAuthenticated()) {
                        String content = github.fetchFileContent(repo, filename);
                        if (telemetry != null) telemetry.record(com.mindpalace.backup.Telemetry.CODE, "read", filename);
                        return content != null ? truncateHeadTail(filename, content) : "read failed";
                    }
                    // Local fallback
                    if (currentRoom.getLocalPath() != null) {
                        java.nio.file.Path fp = java.nio.file.Path.of(currentRoom.getLocalPath(), filename);
                        if (java.nio.file.Files.exists(fp)) {
                            String c = java.nio.file.Files.readString(fp);
                            if (telemetry != null) telemetry.record(com.mindpalace.backup.Telemetry.CODE, "read", filename);
                            return truncateHeadTail(filename, c);
                        }
                    }
                    return "read failed (no auth/local path)";
                }
                case "edit_file": {
                    // H05 patch semantics: old_string/new_string does a targeted
                    // replace when old_string is given; bare content = full
                    // rewrite (legacy). Whole-file rewrites of big files were
                    // the #1 waster — SLMs re-emitted 500-line files to change
                    // one line.
                    String oldStr = args.has("old_string") ? args.get("old_string").getAsString() : null;
                    String content = args.has("content") ? args.get("content").getAsString() : null;
                    if (content == null) content = "";
                    String finalContent = content;
                    if (oldStr != null && !oldStr.isEmpty()) {
                        String newStr = args.has("new_string") ? args.get("new_string").getAsString() : "";
                        String current = readCurrentFile(repo, filename);
                        if (current == null) return "edit failed (cannot read current " + filename + ")";
                        if (!current.contains(oldStr))
                            return "edit failed: old_string not found in " + filename
                                + " (read the file first, copy exact text)";
                        int first = current.indexOf(oldStr);
                        int count = 1, idx = first;
                        while ((idx = current.indexOf(oldStr, idx + 1)) != -1) count++;
                        if (count > 1)
                            return "edit failed: old_string matches " + count
                                + " times in " + filename + " — include more context to make it unique";
                        finalContent = current.substring(0, first) + newStr
                            + current.substring(first + oldStr.length());
                    }
                    // Never-twice: refuse to write identical code twice (local path).
                    if (memory != null && !memory.recordCode(finalContent, langOf(filename))) {
                        return "never-twice: identical code already written";
                    }
                    if (github != null && github.isAuthenticated()) {
                        boolean ok = github.upsertFile(repo, filename, finalContent, "MindPalace agent edit: " + filename, null);
                        if (ok && telemetry != null) telemetry.record(com.mindpalace.backup.Telemetry.CODE, "edit", filename);
                        return ok ? "edited " + filename : "edit failed";
                    }
                    if (currentRoom.getLocalPath() != null) {
                        java.nio.file.Files.writeString(java.nio.file.Path.of(currentRoom.getLocalPath(), filename), finalContent);
                        if (telemetry != null) telemetry.record(com.mindpalace.backup.Telemetry.CODE, "edit", filename);
                        return "edited " + filename + " (local)";
                    }
                    return "edit failed (no auth/local path)";
                }
                case "create_file": {
                    String content = args.has("content") ? args.get("content").getAsString() : "";
                    if (memory != null && !memory.recordCode(content, langOf(filename))) {
                        return "never-twice: identical code already written";
                    }
                    if (github != null && github.isAuthenticated()) {
                        boolean ok = github.upsertFile(repo, filename, content, "MindPalace agent create: " + filename, null);
                        if (ok && telemetry != null) telemetry.record(com.mindpalace.backup.Telemetry.CODE, "create", filename);
                        return ok ? "created " + filename : "create failed";
                    }
                    if (currentRoom.getLocalPath() != null) {
                        java.nio.file.Files.writeString(java.nio.file.Path.of(currentRoom.getLocalPath(), filename), content);
                        if (telemetry != null) telemetry.record(com.mindpalace.backup.Telemetry.CODE, "create", filename);
                        return "created " + filename + " (local)";
                    }
                    return "create failed (no auth/local path)";
                }
                case "delete_file": {
                    if (github != null && github.isAuthenticated()) {
                        boolean ok = github.deleteFile(repo, filename, null, "MindPalace agent delete: " + filename);
                        if (ok && telemetry != null) telemetry.record(com.mindpalace.backup.Telemetry.CODE, "delete", filename);
                        return ok ? "deleted " + filename : "delete failed";
                    }
                    if (currentRoom.getLocalPath() != null) {
                        boolean ok = java.nio.file.Files.deleteIfExists(java.nio.file.Path.of(currentRoom.getLocalPath(), filename));
                        if (ok && telemetry != null) telemetry.record(com.mindpalace.backup.Telemetry.CODE, "delete", filename);
                        return ok ? "deleted " + filename + " (local)" : "delete failed (no such file)";
                    }
                    return "delete failed (no auth/local path)";
                }
                default:
                    return "unknown tool: " + call.name;
            }
        } catch (Exception e) {
            return "tool error: " + e.getMessage();
        }
    }

    // ── Helpers ──

    private String buildContext() {
        StringBuilder sb = new StringBuilder();
        sb.append("Fog of war: ").append(discoveredRepos.size())
          .append(" repos discovered so far. Hidden repos remain unexplored.\n");
        if (lastUserMessage != null && !lastUserMessage.isEmpty()) {
            sb.append("Last player message: ").append(lastUserMessage).append("\n");
        }
        if (currentRoom != null) {
            sb.append("Current room: ").append(currentRoom.getRepoName())
              .append(" (").append(currentRoom.getLanguage()).append(")")
              .append(" — ").append(currentRoom.getBooks().size()).append(" books\n");
            sb.append("Last commit: ").append(currentRoom.getLastCommit()).append("\n");
        }
        if (currentBook != null) {
            sb.append("Current book: ").append(currentBook.getFilename())
              .append(" (").append(currentBook.getLanguage()).append(", ")
              .append(currentBook.getSizeBytes()).append(" bytes)\n");
            if (currentBook.getContent() != null && !currentBook.getContent().isEmpty()) {
                String content = currentBook.getContent();
                // Truncate hard — a 2000-char excerpt ate ~25% of the small
                // model's token budget before it even saw the question.
                if (content.length() > 500) content = content.substring(0, 500) + "...";
                sb.append("Book content (excerpt):\n```\n").append(content).append("\n```\n");
            }
        }
        return sb.toString();
    }

    // ── H08: meta-chatter gate ─────────────────────────────────────────
    // The chat logs' top openers were ALL conversational filler ("Would you
    // like..." x102, "Please provide..." x65, ...). Anything opening with one
    // of these patterns is rejected at emit: the model gets ONE forced-rephrase
    // chance with a concrete prompt; a second offender (or anything SILENT-
    // shaped) is dropped entirely. The world stays quiet unless there is work.
    private static final java.util.regex.Pattern[] META_PATTERNS = {
        java.util.regex.Pattern.compile("^\\[?\\w*\\]?\\s*(would you like|please provide|let's continue|let's refine|understood,? (how|but|let's|please)|absolutely,? let's|as outlined|drifting off-topic|refocus|how will the \\w+ agent)", java.util.regex.Pattern.CASE_INSENSITIVE)
    };

    private boolean isMetaChatter(String msg) {
        if (msg == null) return true;
        String body = msg.replaceFirst("^\\[(Auto|Critic|Tool)\\]\\s*", "");
        for (java.util.regex.Pattern p : META_PATTERNS)
            if (p.matcher(body.trim()).find()) return true;
        return false;
    }

    private final java.util.Set<String> rephraseAttempts = java.util.Collections.newSetFromMap(new java.util.LinkedHashMap<>() {
        protected boolean removeEldestEntry(java.util.Map.Entry<String, Boolean> e) { return size() > 24; }
    });

    private void emit(Consumer<String> cb, String msg) {
        if (cb == null) return;
        // SILENT bow-out (H02 directive) never broadcasts.
        if (msg != null && msg.trim().equalsIgnoreCase("[Critic] SILENT")) return;
        if (isMetaChatter(msg)) {
            String key = msg.length() + ":" + msg.hashCode();
            if (rephraseAttempts.add(key)) {
                // ONE rephrase chance: demand a concrete artifact, log quietly.
                log("[H08] meta-chatter blocked, requesting rephrase: "
                    + msg.substring(0, Math.min(60, msg.length())) + "…");
                return; // this emission dies; next cycle's directive + critic gate do the rephrasing
            }
            log("[H08] repeat meta-chatter dropped: " + key);
            return;
        }
        cb.accept(msg);
        log(msg);
    }

    private void log(String msg) {
        String timestamp = java.time.Instant.now().toString();
        String logLine = "[" + timestamp + "] " + msg;
        if (onConsoleLog != null) {
            onConsoleLog.accept(logLine);
        } else {
            // Fallback if callback not set
            System.out.println(logLine);
        }
    }

    // ── Tool definitions ──

    /** H05: fetch current file content (github first, then local) for patch edits. */
    private String readCurrentFile(String repo, String filename) {
        if (github != null && github.isAuthenticated()) {
            try {
                return github.fetchFileContent(repo, filename);
            } catch (Exception ignored) { }
        }
        if (currentRoom != null && currentRoom.getLocalPath() != null) {
            try {
                java.nio.file.Path fp = java.nio.file.Path.of(currentRoom.getLocalPath(), filename);
                if (java.nio.file.Files.exists(fp)) return java.nio.file.Files.readString(fp);
            } catch (Exception ignored) { }
        }
        return null;
    }

    /** H04: files return head+tail 150 lines (not a char count) so the SLM
     *  sees real code within its token budget; the middle is elided. */
    private static String truncateHeadTail(String filename, String content) {
        final int MAX = 150;
        String[] lines = content.split("\n", -1);
        if (lines.length > 0 && lines[lines.length - 1].isEmpty()) {
            lines = java.util.Arrays.copyOf(lines, lines.length - 1); // trailing \n
        }
        String head = "read " + filename + " (" + lines.length + " lines)\n";
        if (lines.length <= 2 * MAX) return head + content;
        StringBuilder sb = new StringBuilder(head);
        for (int i = 0; i < MAX; i++) sb.append(lines[i]).append('\n');
        sb.append("... [" ).append(lines.length - 2 * MAX).append(" middle lines elided]\n");
        for (int i = lines.length - MAX; i < lines.length; i++) sb.append(lines[i]).append('\n');
        return sb.toString();
    }

    /** Selftest hook for the private truncation logic (AgentManager is in the
     *  agent package; the selftest in engine needs a public bridge). */
    public static String truncateHeadTailForTest(String filename, String content) {
        return truncateHeadTail(filename, content);
    }

    private static List<JsonObject> buildTools() {
        List<JsonObject> tools = new ArrayList<>();
        Gson g = new Gson();

        // read_file
        JsonObject readFile = new JsonObject();
        readFile.addProperty("type", "function");
        JsonObject rfFn = new JsonObject();
        rfFn.addProperty("name", "read_file");
        rfFn.addProperty("description", "Read the contents of a file in the current repo");
        JsonObject rfParams = new JsonObject();
        rfParams.addProperty("type", "object");
        JsonObject rfProps = new JsonObject();
        rfProps.add("filename", g.fromJson("{\"type\":\"string\",\"description\":\"Path to the file\"}", JsonObject.class));
        rfParams.add("properties", rfProps);
        rfParams.add("required", g.fromJson("[\"filename\"]", JsonArray.class));
        rfFn.add("parameters", rfParams);
        readFile.add("function", rfFn);
        tools.add(readFile);

        // edit_file
        JsonObject editFile = new JsonObject();
        editFile.addProperty("type", "function");
        JsonObject efFn = new JsonObject();
        efFn.addProperty("name", "edit_file");
        efFn.addProperty("description", "Edit a file in the current repo. PREFERRED: pass old_string (exact text to replace, unique in file) + new_string for a targeted patch. Full rewrite via content only for tiny files.");
        JsonObject efParams = new JsonObject();
        efParams.addProperty("type", "object");
        JsonObject efProps = new JsonObject();
        efProps.add("filename", g.fromJson("{\"type\":\"string\",\"description\":\"Path to the file\"}", JsonObject.class));
        efProps.add("old_string", g.fromJson("{\"type\":\"string\",\"description\":\"Exact text to replace (must be unique in the file). Read the file first and copy it exactly.\"}", JsonObject.class));
        efProps.add("new_string", g.fromJson("{\"type\":\"string\",\"description\":\"Replacement text\"}", JsonObject.class));
        efProps.add("content", g.fromJson("{\"type\":\"string\",\"description\":\"Full new content (only when NOT using old_string/new_string)\"}", JsonObject.class));
        efParams.add("properties", efProps);
        efParams.add("required", g.fromJson("[\"filename\"]", JsonArray.class));
        efFn.add("parameters", efParams);
        editFile.add("function", efFn);
        tools.add(editFile);

        // create_file
        JsonObject createFile = new JsonObject();
        createFile.addProperty("type", "function");
        JsonObject cfFn = new JsonObject();
        cfFn.addProperty("name", "create_file");
        cfFn.addProperty("description", "Create a new file in the current repo");
        JsonObject cfParams = new JsonObject();
        cfParams.addProperty("type", "object");
        JsonObject cfProps = new JsonObject();
        cfProps.add("filename", g.fromJson("{\"type\":\"string\",\"description\":\"Name of the new file\"}", JsonObject.class));
        cfProps.add("content", g.fromJson("{\"type\":\"string\",\"description\":\"Initial content\"}", JsonObject.class));
        cfParams.add("properties", cfProps);
        cfParams.add("required", g.fromJson("[\"filename\",\"content\"]", JsonArray.class));
        cfFn.add("parameters", cfParams);
        createFile.add("function", cfFn);
        tools.add(createFile);

        // delete_file
        JsonObject deleteFile = new JsonObject();
        deleteFile.addProperty("type", "function");
        JsonObject dfFn = new JsonObject();
        dfFn.addProperty("name", "delete_file");
        dfFn.addProperty("description", "Delete a file from the current repo");
        JsonObject dfParams = new JsonObject();
        dfParams.addProperty("type", "object");
        JsonObject dfProps = new JsonObject();
        dfProps.add("filename", g.fromJson("{\"type\":\"string\",\"description\":\"Path to the file to delete\"}", JsonObject.class));
        dfParams.add("properties", dfProps);
        dfParams.add("required", g.fromJson("[\"filename\"]", JsonArray.class));
        dfFn.add("parameters", dfParams);
        deleteFile.add("function", dfFn);
        tools.add(deleteFile);

        return tools;
    }

    // ── System prompts ──

    private static final String TOOL_SYSTEM_PROMPT =
        "You are a tool-calling AI agent in MindPalace, a 3D GitHub repository explorer. " +
        "You have access to tools: read_file, edit_file, create_file, delete_file. " +
        "You are paired with a critic agent who reviews your actions. " +
        "Your job is to discuss and work with actual CODE and PROGRAMMING LANGUAGES: " +
        "read the files in the current room, explain what the code does, identify the " +
        "language and its idioms, and propose concrete code changes. " +
        "Talk about the code itself — functions, classes, algorithms, syntax, libraries — " +
        "not about 'hidden repos' or 'port scans'. Be concise and specific. " +
        "WORKED EXAMPLE (follow this pattern exactly):\n" +
        "Task: 'fix the typo in utils.py'\n" +
        "1. read_file {\"filename\": \"utils.py\"} -> returns head+tail of the file\n" +
        "2. edit_file {\"filename\": \"utils.py\", \"old_string\": \"def gret(name):\", \"new_string\": \"def greet(name):\"}\n" +
        "   (old_string must be copied EXACTLY from the file you just read and be unique)\n" +
        "3. Reply with what changed, in one sentence.\n" +
        "NEVER rewrite a whole file with content= when a one-line old_string patch does it. " +
        "You are running on " + TOOL_MODEL + " via Ollama. " +
        "The current room and book context will be provided before each message.";

    private static final String CRITIC_SYSTEM_PROMPT =
        "You are an actor-critic AI agent in MindPalace, a 3D GitHub repository explorer. " +
        "Your role is to review the tool agent's code proposals and provide semantic feedback. " +
        "Focus on the CODE and PROGRAMMING LANGUAGES: is the proposed change correct for the " +
        "language? Are there bugs, edge cases, or better idioms? Could the code be cleaner? " +
        "Discuss actual code — syntax, types, algorithms, libraries — not 'hidden repos'. " +
        "Be constructive and specific. You are running on " + CRITIC_MODEL + " via Ollama. " +
        "The current room and book context will be provided before each message.";

    private static final String CHAT_SYSTEM_PROMPT =
        "You are the MindPalace guide, a friendly AI companion in a 3D world where every room " +
        "is a GitHub repository and every book is a file. Answer the player's questions " +
        "directly and conversationally. If they ask about the current room or book, explain " +
        "what that repo/file is about. Be concise, warm, and specific. You are running on " +
        ModelConfig.CHAT_MODEL + " via Ollama.";

    // ── Getters ──

    public boolean isAvailable() { return available; }
    public boolean isRunning() { return running; }
    public String getToolModel() { return TOOL_MODEL; }
    public String getCriticModel() { return CRITIC_MODEL; }
    public ModelLifespan getToolLifespan() { return toolLifespan; }
    public ModelLifespan getCriticLifespan() { return criticLifespan; }
    public ModelScheduler getScheduler() { return modelScheduler; }

    // ── SIMS1337 parity getters (for self-test + telemetry) ──
    public ModelRouter getRouter() { return router; }
    public LoRASwitcher getLora() { return lora; }
    public WeightedQuorumVote getQuorum() { return quorum; }
    public FOWGate getFow() { return fow; }
    public String getRoutedModel() { return routedModel; }

    // ── Reddit integration ──
    public RedditToQuorumBridge getRedditBridge() { return redditBridge; }

    /**
     * Initialize Reddit OAuth. Call this after start() if you have credentials.
     * Returns the auth URL to visit; user grants permission → OAuth callback
     * triggers token exchange → polling begins.
     */
    public String initReddit(String clientId, String clientSecret, String subreddit) {
        try {
            String callbackUrl = "http://localhost:8899";
            redditClient = new RedditOAuthClient(clientId, clientSecret, callbackUrl, subreddit);
            String state = UUID.randomUUID().toString();

            oauthServer = new OAuthCallbackServer(8899, redditClient, () -> {
                try {
                    // After token exchange, start polling Reddit every 5 minutes
                    String ledgerPath = System.getProperty("user.home") != null
                        ? Paths.get(System.getProperty("user.home"), "AIGEN_SYS", ".hermes", "reddit_ledger.txt").toAbsolutePath().toFile().getAbsolutePath()
                        : "/tmp/reddit_ledger.txt";
                    redditBridge = new RedditToQuorumBridge(redditClient, quorum, ledgerPath, 5 * 60 * 1000);
                    redditBridge.startPolling();
                    log("[Reddit] Polling started: fetching suggestions every 5 minutes");
                } catch (Exception e) {
                    log("[Reddit] ERROR starting bridge: " + e.getMessage());
                }
            });
            oauthServer.start();
            String authUrl = redditClient.getAuthorizationUrl(state);
            log("[Reddit] OAuth initialized. Visit this URL to authorize:");
            log(authUrl);
            return authUrl;
        } catch (Exception e) {
            log("[Reddit] ERROR initializing OAuth: " + e.getMessage());
            return null;
        }
    }
}
