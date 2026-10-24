package com.mindpalace.agent;

import com.google.gson.*;
import com.mindpalace.agent.sims.*;
import com.mindpalace.integration.*;
import com.mindpalace.agent.sprites.SpriteProposalService;
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

    /**
     * ASCII-safe target name for quorum proposals and telemetry details
     * (t_740fa7c6). When no room is in focus, the old literal "?" leaked into
     * every proposal ("Act on ?"), every telemetry record, and every spawned
     * TASK file — 4291 APPROVED + 1215 REJECTED ledger rows, 100% "Act on ?".
     * Returns the repo name when known, an explicit world-wide fallback when
     * not. Pure ASCII so it survives cp1252 consoles and telemetry round-trips.
     */
    public static String proposalTargetName(Room room) {
        if (room != null && room.getRepoName() != null && !room.getRepoName().isBlank()) {
            return room.getRepoName();
        }
        return "[no room in focus - world-wide check]";
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

    // Current context — written by the game thread, read from agent/drain
    // threads: volatile scalars + concurrent collections (#106).
    private volatile Room currentRoom;
    private volatile Book currentBook;
    private volatile String lastUserMessage;
    /** Concurrent: written by the game thread (setContext), read/copied by agent threads (#106). */
    private final Set<String> discoveredRepos = ConcurrentHashMap.newKeySet();

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

    // ── GitHub → Local TASK bridge ────────────────────────────────────
    // Cloud oversight routine files GitHub issues with 'hermes-queue' label.
    // Local bridge polls those issues and converts to TASK files for Hermes.
    // This connects cloud analysis (360 oversight) to local execution.
    private GitHubPollBridge githubBridge;

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
    /** Copy-on-write: agent thread writes, game thread reads via getApprovedTopics (#106). */
    private final List<String> approvedTopics = new CopyOnWriteArrayList<>();  // topics the quorum approved
    // #103 (F7): approvedTopics re-accumulated every 60s re-cluster of the FULL
    // chat history → one duplicate GitHub issue per cycle, unbounded. Cap the
    // live list (oldest approved first, order preserved by addIfAbsent) and
    // keep a raised-once ledger so each topic is filed AT MOST ONCE, ever.
    private static final int APPROVED_TOPICS_CAP = 24;
    private final java.util.Set<String> raisedTopics = ConcurrentHashMap.newKeySet();
    private volatile long lastLexicalScan = 0;                                // throttle chat-log reads
    private static final long LEXICAL_SCAN_MS = 60_000;              // re-scan chat logs every 60s
    private static final Path CHAT_LOG_DIR = Paths.get("chat_logs");

    // Callbacks for tool execution
    private Consumer<String> onToolMessage;
    private Consumer<String> onChatMessage;   // #290: dedicated guide-reply channel
    private Consumer<String> onCriticMessage;
    private Consumer<String> onConsoleLog;
    private Consumer<List<Issue>> onIssues;   // fired when the lexical bridge finds issues

    // State — read from agent/drain threads while the game thread writes (#106).
    private volatile boolean running;
    private volatile boolean available;
    private volatile long lastAutoCycle;
    // TASK_0128 redo: -Dmindpalace.cycleMs=N speeds the autonomous cycle for
    // data collection (e.g. 30000 = 5 cycles/min). Live default stays 5 min.
    private static final long CYCLE_MS = Long.getLong("mindpalace.cycleMs", 5 * 60 * 1000L);
    private SpriteProposalService spriteProposalService;

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

    /** #290 selftest seam: fire the guide-reply routing EXACTLY as the
     *  onUserChat thenAccept block does (chat lane when wired, [Guide]
     *  fallback on the tool lane when legacy 3-arg wiring left it null). */
    public void emitChatForTest(String resp) {
        if (resp == null || resp.isEmpty()) return;
        if (onChatMessage != null) {
            emit(onChatMessage, resp);
        } else {
            emit(onToolMessage, "[Guide] " + resp);
        }
    }

    // ── Lifecycle ──

    public void start() {
        // SIMS1337 parity wiring is pure in-memory state: wire it
        // UNCONDITIONALLY before the availability gate so the selftest sees
        // FOW/quorum/LoRA populated even with no Ollama daemon reachable
        // (CI has none). Wiring must not depend on a live HTTP probe.
        initSimsParity();

        // MP-051 sprites are opt-in and only read the local KG. Their output is
        // appended to a separate review queue; no proposal is executed here.
        if (!selfTest) {
            spriteProposalService = SpriteProposalService.fromSystemProperties();
            if (spriteProposalService.isEnabled()) {
                scheduler.scheduleWithFixedDelay(() -> {
                    try {
                        int count = spriteProposalService.collectProposals();
                        if (count > 0) log("[Sprites] queued " + count + " read-only proposals");
                    } catch (Exception ex) {
                        log("[Sprites] KG proposal scan failed: " + ex.getMessage());
                    }
                }, 0, spriteProposalService.intervalSeconds(), TimeUnit.SECONDS);
            }
        }

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
        // Track discovery: when a fogged room is revealed, agents "discover" it.
        // ConcurrentSet.add is atomic — replaces the contains→add check-then-act (#106).
        // Null repo name guarded: ConcurrentHashMap key sets reject null (HashSet accepted it).
        if (room != null && room.getRepoName() != null && discoveredRepos.add(room.getRepoName())) {
            log("[AgentManager] Discovered repo: " + room.getRepoName()
                + (room.isFogged() ? " (fog lifted)" : ""));
        }
    }

    public void setCallbacks(Consumer<String> tool, Consumer<String> critic, Consumer<String> console) {
        this.onToolMessage = tool;
        this.onCriticMessage = critic;
        this.onConsoleLog = console;
    }

    /** #290: 4-arg form — chat consumer is threaded through from the last
     *  (previously null 4th) position so guide replies land on their own
     *  channel instead of piggybacking on the tool log. */
    public void setCallbacks(Consumer<String> tool, Consumer<String> critic,
                             Consumer<String> console, Consumer<String> chat) {
        setCallbacks(tool, critic, console);
        this.onChatMessage = chat;
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

    /** The add-only issue stream (read side wired by the engine; may be null). */
    public com.mindpalace.github.GitHubIssueStream getIssueStream() { return issueStream; }

    // ── Telemetry ──────────────────────────────────────────────────────

    private com.mindpalace.backup.Telemetry telemetry;

    /** Inject the unified telemetry ledger (append-only, paced). */
    public void setTelemetry(com.mindpalace.backup.Telemetry t) {
        this.telemetry = t;
        // TASK_0132: wire quorum vote recording — one telemetry row per cast
        // vote (real/strip/abstain/blind/sim vocabulary), null-safe so a game
        // run without telemetry never breaks voting.
        if (t != null && quorum != null) {
            quorum.setVoteSink(rec -> t.record(com.mindpalace.backup.Telemetry.QUORUM,
                "quorum_vote", rec.toDetail()));
            com.mindpalace.agent.sims.QuorumTaskSpawner.setTelemetrySink(
                (outcome, detail) -> t.record(com.mindpalace.backup.Telemetry.QUORUM,
                    outcome, detail));
        }
        // #130 (H39): every REAL quorum vote is an Ollama call — charge the
        // house participant ("Explorer", the same wallet #97/#130 tool rounds
        // bill) by chars/4. key arrives as model + ":" + prompt from the
        // WeightedQuorumVote hook; response drives the charge.
        if (quorum != null) {
            quorum.setComputeTaxFn((key, resp) -> {
                if (depin == null) return 0.0;
                return depin.chargeCompute("Explorer", key, resp);
            });
        }
    }

    // ── Context kits (per-model wrapper: LoRA + KG + KV, never mixed) ──

    /** Concurrent: game thread (onUserChat→ctx), agent thread (solveOne), drain worker
     *  (executeToolRound→ctx/kit) all race computeIfAbsent here (#106). */
    private final Map<String, ContextKit> kits = new ConcurrentHashMap<>();

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
        // Refresh the KG neighborhood when a room is present. Room snapshotted
        // once: a mid-call room change must not misattribute the neighborhood (#106).
        Room room = currentRoom;
        if (room != null && knowledgeGraph != null) {
            List<String> nodes = new ArrayList<>();
            for (Room nb : knowledgeGraph.neighbors(room)) nodes.add(nb.getRepoName());
            nodes.add(room.getRepoName());
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
        Room room = currentRoom; // snapshot once per call (#106)
        if (issueStream == null || approvedTopics.isEmpty()) return 0;
        if (room == null) return 0;
        String repo = room.getRepoName();
        int raised = 0;
        for (String topic : approvedTopics) {
            // #103 (F7): each topic is raised AT MOST ONCE, ever (raised-once
            // ledger, checked before the API hit — pacing failures don't burn
            // the topic).
            if (!raisedTopics.add(topic)) continue;
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
            } else if (n <= 0) {
                // #103: the raise did NOT happen (paced -2, API fail -1) →
                // release the ledger claim so a later cycle can retry it.
                raisedTopics.remove(topic);
            }
        }
        return raised;
    }

    // ── #127 (H35): issue-verdict pipeline (quorum → ADD-ONLY comment) ──

    /** #127: min ms between issue-verdict scans (paced like other GitHub work). */
    private static final long ISSUE_VERDICT_COOLDOWN_MS = 30 * 60 * 1000L;
    /** #127: max issue-verdict comments per scan (newest first). */
    static final int ISSUE_VERDICT_CAP = 3;
    private long lastIssueVerdictMs = 0L;
    /** #127 never-twice: "issueNum#STATUS" keys already posted this JVM run. */
    private final java.util.Set<String> postedVerdictKeys = new java.util.HashSet<>();
    /** #127 selftest seam: force the verdict status (skip the live vote round). */
    private volatile String issueVerdictOverride = null;
    public void setIssueVerdictOverride(String status) { this.issueVerdictOverride = status; }
    /** #127 selftest seam: recording poster (null → live GitHubClient POST). */
    private java.util.function.BiPredicate<Integer, String> verdictCommentPoster;
    public void setVerdictCommentPoster(java.util.function.BiPredicate<Integer, String> poster) {
        this.verdictCommentPoster = poster;
    }
    /** #127 selftest seam: replace the issue read (null → live listOpenIssues). */
    private java.util.function.Supplier<java.util.List<com.mindpalace.github.GitHubClient.OpenIssue>> openIssueSource;
    public void setOpenIssueSource(
            java.util.function.Supplier<java.util.List<com.mindpalace.github.GitHubClient.OpenIssue>> source) {
        this.openIssueSource = source;
    }

    /**
     * #127 (H35): quorum-vote the current room's open GitHub issues and post
     * each verdict as an ADD-ONLY issue comment. The comment endpoint can
     * never close or edit an issue, so add-only is structural, not a promise.
     * Paced (ISSUE_VERDICT_COOLDOWN_MS between scans) and never-twice per
     * (issue, verdict): the same status is never re-posted on the same issue
     * this JVM run; a CHANGED verdict posts once (an update, not a repeat).
     * Returns the number of verdict comments posted.
     */
    public int voteIssueVerdicts() {
        // The forced verdict exists ONLY in selftest, and selftest must also
        // inject its own issue source — a seamed-but-live combo can never
        // reach GitHub. Live mode (selfTest=false) ignores the override.
        String seamVerdict = selfTest ? issueVerdictOverride : null;
        if (selfTest && (openIssueSource == null || seamVerdict == null)) return 0;
        if (github == null && openIssueSource == null) return 0;
        Room room = currentRoom; // #106: snapshot once per call
        String repo = room == null ? null : room.getRepoName();
        if (repo == null && openIssueSource == null) return 0;
        long now = System.currentTimeMillis();
        // Cooldown is live-path pacing only; selftest seams bypass it.
        if (openIssueSource == null && now - lastIssueVerdictMs < ISSUE_VERDICT_COOLDOWN_MS) return 0;
        lastIssueVerdictMs = now;

        java.util.List<com.mindpalace.github.GitHubClient.OpenIssue> open;
        try {
            open = openIssueSource != null ? openIssueSource.get() : github.listOpenIssues(repo);
        } catch (Exception e) {
            log("[AgentManager] issue-verdict scan failed: " + e.getMessage());
            return 0;
        }
        if (open == null || open.isEmpty()) return 0;

        int posted = 0;
        for (com.mindpalace.github.GitHubClient.OpenIssue issue : open) {
            if (posted >= ISSUE_VERDICT_CAP) break;
            String id = "issv-" + issue.number() + "-" + now;
            String title = issue.title() == null ? "" : issue.title();
            String body = issue.body() == null ? "" : issue.body();
            String detail = body.length() > 200 ? body.substring(0, 200) : body;
            quorum.registerProposal(id,
                "[issue] #" + issue.number() + " " + asciiPlain(title + " - " + detail),
                new HexCoord(0, 0), "issue_verdict");
            quorum.advanceTimePulse(0.05);
            // NOTE: no finishCycle() here — it CLEARS the proposal registry
            // (WeightedQuorumVote:finishCycle → proposals.clear()), which
            // would kill the votes below with "unknown proposal". The #101
            // cap evicts the registry's oldest entries, and each normal
            // quorum cycle's own finishCycle sweeps stragglers.

            WeightedQuorumVote.QuorumResult r = null;
            if (seamVerdict == null && !selfTest) {
                r = quorum.actualVote(id, modelScheduler);
            } else if (selfTest && seamVerdict == null) {
                for (String voter : quorum.allModelsMap().keySet())
                    quorum.castVote(id, voter, WeightedQuorumVote.Vote.APPROVE);
                r = quorum.calculateQuorum(id);
            }
            String status = seamVerdict != null ? seamVerdict
                : (r == null ? null : r.status);
            if (!"APPROVED".equals(status) && !"REJECTED".equals(status)) {
                continue; // NO_QUORUM / BLINDED: no comment
            }

            String comment = "**Quorum " + status + "** on issue #" + issue.number() + " ("
                + (r == null ? "(seam verdict)" : String.format("ok:%d no:%d blind:%d abstain:%d",
                    r.approve, r.reject, r.blind, r.abstain))
                + ", weight " + (r == null ? "n/a" : String.format("%.2f", r.weightedApprove))
                + ", cycle " + id + ")\n\n"
                + "Proposed: " + asciiPlain(title) + "\n\n"
                + "The verdict is advisory and ADD-ONLY: the agents vote and comment,"
                + " but never close, edit or delete issues.";

            String key = issue.number() + "#" + status;
            if (!postedVerdictKeys.add(key)) continue; // never-twice
            boolean ok = false;
            try {
                ok = verdictCommentPoster != null
                    ? verdictCommentPoster.test(issue.number(), comment)
                    : github.addIssueComment(repo, issue.number(), comment);
            } catch (Exception e) {
                log("[AgentManager] issue-verdict post failed for #" + issue.number() + ": " + e.getMessage());
            }
            if (ok) {
                posted++;
                log("[AgentManager] issue verdict " + status + " posted as comment on #"
                    + issue.number() + " (" + repo + ")");
                if (telemetry != null) telemetry.record(com.mindpalace.backup.Telemetry.ISSUE,
                    "issue-verdict-" + status, "#" + issue.number());
            } else {
                postedVerdictKeys.remove(key); // #103 doctrine: failed post retries next cycle
            }
        }
        return posted;
    }

    /** #127: ASCII-plain rendering for voter/comment surfaces (cp1252 doctrine). */
    private static String asciiPlain(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x7F) { sb.append('?'); continue; }
            sb.append(Character.isWhitespace(c) ? ' ' : c); // #127: newline/CR/tab → space
        }
        return sb.toString();
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
                    // #290: guide replies route through the DEDICATED chat
                    // channel; fallback to onToolMessage when the 4-arg
                    // setter was never wired (legacy 3-arg callers).
                    if (onChatMessage != null) {
                        emit(onChatMessage, resp);
                    } else {
                        emit(onToolMessage, "[Guide] " + resp);
                    }
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

        log("[AgentManager] Auto-cycle — agents discussing " + proposalTargetName(currentRoom));

        // Telemetry: record the cycle itself (paced, append-only).
        if (telemetry != null) telemetry.record(com.mindpalace.backup.Telemetry.AGENT,
            "cycle", proposalTargetName(currentRoom));

        // Route the tool agent's model by task complexity (SIMS1337 ModelRouter).
        // #134 (H43): trivial cycles take the flash lane (cheapest model,
        // never escalated above flash); real work keeps its normal tier.
        Complexity cx = Complexity.estimate(context);
        routedModel = router.selectFlash(cx);
        lora.switchAdapter(AdapterType.CODE);
        log("[AgentManager] routed " + cx + " → " + routedModel + " (" + router.reason(cx) + ")");

        // Tool agent now runs a REAL tool-calling loop: it can read/edit/create/
        // delete files in the current repo, not just emit text. #102: the tool
        // round now runs AFTER a quorum verdict on "may this cycle write?" —
        // APPROVED runs full tools, otherwise read_file-only (proposal → vote →
        // act, as the architecture describes). The critic is invoked as a
        // CONTINUATION of the tool round (executeToolRound → runCritic) instead
        // of the old 2.5s sleep-and-poll, which nearly always reviewed null
        // because the round sat behind the scheduler spacing gate (F4/D9).
        final boolean toolRoundApproved = voteToolRoundPermission();
        runToolLoop(toolRoundApproved);

        // Critic review now rides the tool round itself (see runCritic); there
        // is nothing left to poll here — the sleeping thread (D9) is gone.

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

        // #127 (H35): quorum-vote the room's open GitHub issues and post each
        // verdict as an ADD-ONLY issue comment (cooldown + never-twice gate
        // the method's own; empty room/failures are no-ops by design).
        try {
            int verdicts = voteIssueVerdicts();
            if (verdicts > 0 && telemetry != null) telemetry.record(
                com.mindpalace.backup.Telemetry.ISSUE,
                "issue-verdicts", String.valueOf(verdicts));
        } catch (Exception e) {
            log("[AgentManager] issue-verdict cycle failed: " + e.getMessage());
        }
    }

    /**
     * #102 (kanban CARD-Q1 F5): register + vote the tool round's permission
     * proposal BEFORE submitting the tool loop. Selftest auto-approves (same
     * deterministic gate as runLexicalBridge — no live SLM round-trips).
     * Returns true only when the quorum APPROVES the write.
     */
    private boolean voteToolRoundPermission() {
        try {
            String id = "toolperm-" + System.currentTimeMillis();
            String target = proposalTargetName(currentRoom);
            quorum.registerProposal(id, "Agent may write files in " + target
                + " this cycle", new HexCoord(0, 0), "tool_permission");
            quorum.advanceTimePulse(0.1);
            if (selfTest) {
                for (String voter : quorum.allModelsMap().keySet())
                    quorum.castVote(id, voter, com.mindpalace.agent.sims.WeightedQuorumVote.Vote.APPROVE);
                return true;
            }
            WeightedQuorumVote.QuorumResult r = quorum.actualVote(id, modelScheduler);
            boolean approved = r != null && "APPROVED".equals(r.status);
            if (telemetry != null) telemetry.record(com.mindpalace.backup.Telemetry.QUORUM,
                r == null ? "(evicted)" : r.status, "tool-permission " + target);
            return approved;
        } catch (Exception e) {
            log("[AgentManager] tool-permission vote failed (defaults read-only): " + e.getMessage());
            return false; // fail-safe: no approval → no writes
        }
    }

    /** #126 test seam: force-REJECT the file-write quorum gate so the selftest
     *  can assert a vetoed diff never reaches disk (H38 check). */
    private volatile boolean writeGateVeto = false;
    public void setWriteGateVeto(boolean v) { this.writeGateVeto = v; }

    /**
     * #126 (H38): per-write quorum gate. An edit/create/delete lands on the
     * repo ONLY if the quorum APPROVEs the exact change description. Ballots
     * ride the dedicated vote-lane worker (ModelScheduler.submitVote), so a
     * call from inside a tool round on the main worker cannot deadlock.
     * Fail-safe: vote failure/eviction → deny.
     */
    public boolean voteFileWrite(String verb, String filename, String detail) {
        if (selfTest) return !writeGateVeto; // selftest: seam veto / auto-approve
        try {
            String id = "filewrite-" + System.currentTimeMillis();
            String target = proposalTargetName(currentRoom);
            String text = "Write " + verb + " " + target + "/" + filename
                + (detail == null || detail.isEmpty() ? "" : " — " + detail);
            quorum.registerProposal(id, text, new HexCoord(0, 0), "code_quality");
            quorum.advanceTimePulse(0.05);
            WeightedQuorumVote.QuorumResult r = quorum.actualVote(id, modelScheduler);
            boolean approved = r != null && "APPROVED".equals(r.status);
            if (telemetry != null) telemetry.record(com.mindpalace.backup.Telemetry.QUORUM,
                r == null ? "(evicted)" : r.status, "file-write " + verb + " " + filename);
            return approved;
        } catch (Exception e) {
            log("[AgentManager] file-write vote failed (gate deny): " + e.getMessage());
            return false;
        }
    }

    /** TASK_0128 redo: deterministic 5-type rotation over quorum cycles. */
    private final com.mindpalace.agent.sims.ProposalRotator proposalRotator =
        new com.mindpalace.agent.sims.ProposalRotator();

    /** Run a FOW-gated quorum vote on the current cycle's proposal. */
    private void runQuorumVote(String context) {
        try {
            String id = "cycle-" + System.currentTimeMillis();
            // t_740fa7c6: null-room fallback must be ASCII-plain. The old "Act on ?"
            // string was written into ALL 4291 telemetry APPROVED rows — a "?" is
            // indistinguishable from a mojibake glyph and hides which repo (or none)
            // the cycle targeted.
            // TASK_0128 redo: rotate through the 5 proposal types so the ledger
            // accrues per-type data instead of 100% system_health_check.
            String text = "Act on " + proposalTargetName(currentRoom);
            quorum.registerProposal(id, text, new HexCoord(0, 0), proposalRotator.next());
            // t_948a71be: real-work overlay — register the actual backlog
            // (kanban ready/triage cards, hermes-queue issues, failing checks)
            // BEFORE the voting round so the same model pass votes on all of
            // them and handleRealWorkVerdicts applies each result. Kept in a
            // finally so the source never survives a failed cycle in a
            // half-applied state.
            java.util.List<com.mindpalace.agent.sims.QuorumProposalSource.RealWorkProposal> realWork = null;
            try {
                realWork = realWorkSource.gather();
                for (var rw : realWork) {
                    quorum.registerProposal(rw.proposalId(), rw.voteText(),
                        new HexCoord(0, 0), rw.proposalType());
                }
            } catch (Exception e) {
                log("[AgentManager] real-work gather failed (synthetic cycle still ran): " + e.getMessage());
                realWork = null;
            }
            quorum.advanceTimePulse(0.1);
            // #101: vote ONLY this cycle's proposal (was actualVoteAll —
            // O(registry × models × 30s) and never shrank).
            WeightedQuorumVote.QuorumResult r = quorum.actualVote(id, modelScheduler);
            if (r != null) {
                log("[AgentManager] quorum: " + r);
                String voteQuality = com.mindpalace.agent.sims.VoteQualityLedger.summarize(r.voteOutcomes);
                if (telemetry != null) telemetry.record(com.mindpalace.backup.Telemetry.QUORUM,
                    r.status, text + " | " + voteQuality);
                // TASK_0128 redo: ledger EVERY cycle (any outcome) so the
                // diversity report can measure per-type approval rates.
                // APPROVED cycles are logged by QuorumTaskSpawner itself
                // (with task_spawned_id) — don't double-write those.
                if (!"APPROVED".equals(r.status)) {
                    try {
                        com.mindpalace.agent.sims.QuorumTaskSpawner.ledgerAppendAny(r);
                    } catch (Exception e) {
                        log("[AgentManager] ledger append failed: " + e.getMessage());
                    }
                }
                // H21 (step 67): ride the carry-event pattern — publish the
                // verdict to the game so the world flares (gold for APPROVED,
                // ice dip for REJECTED). The web-side CSS twins already exist.
                if (onQuorumVerdict != null) onQuorumVerdict.accept(r.status);
                // TASK_0125: Spawn TASK file for APPROVED proposals
                if ("APPROVED".equals(r.status)) {
                    try {
                        com.mindpalace.agent.sims.QuorumTaskSpawner.handleApprovedProposal(r);
                        log("[AgentManager] TASK spawn handled: " + r.proposalId);
                    } catch (Exception e) {
                        log("[AgentManager] TASK spawn FAILED: " + e);
                    }
                }
            }
            // t_948a71be: apply each real-work verdict (promote / comment /
            // create-triage) — the synthetic cycle's own handling above is
            // unaffected ("never delete, only merge").
            if (realWork != null && !realWork.isEmpty()) {
                try {
                    handleRealWorkVerdicts(realWork);
                } catch (Exception e) {
                    log("[AgentManager] real-work verdict application failed: " + e.getMessage());
                }
            }
            // t_948a71be: unconditional post-verdict sweep — also clears the
            // synthetic cycle-<ts> proposal when gather() returned empty
            // (otherwise dead synthetic ids pile up cycle over cycle).
            quorum.finishCycle();
        } catch (Exception e) {
            log("[AgentManager] quorum error: " + e.getMessage());
        }
    }

    /**
     * t_948a71be: calculate + apply a verdict per real-work proposal.
     * Voting happened in the shared actualVoteAll round above; this only
     * reads the results. The source's applyVerdict promotes approved
     * kanban cards, comments on rejected ones, and parks approved public
     * items in triage — never past it (Architect sign-off doctrine).
     */
    private void handleRealWorkVerdicts(
            java.util.List<com.mindpalace.agent.sims.QuorumProposalSource.RealWorkProposal> realWork) {
        for (var rw : realWork) {
            WeightedQuorumVote.QuorumResult wr;
            try {
                wr = quorum.calculateQuorum(rw.proposalId());
            } catch (Exception e) {
                log("[AgentManager] real-work quorum calc failed for " + rw.sourceId + ": " + e.getMessage());
                continue;
            }
            if (wr == null) continue;
            log("[AgentManager] real-work quorum: " + wr);
            if (telemetry != null) {
                telemetry.record(com.mindpalace.backup.Telemetry.QUORUM, wr.status, rw.voteText());
            }
            try {
                realWorkSource.applyVerdict(wr, rw);
            } catch (Exception e) {
                log("[AgentManager] real-work verdict failed for " + rw.sourceId + ": " + e.getMessage());
            }
        }
    }

    /** t_948a71be: source of real-work proposals (kanban + issues + checks). */
    private final com.mindpalace.agent.sims.QuorumProposalSource realWorkSource =
        new com.mindpalace.agent.sims.QuorumProposalSource();

    /** H21: game-side hook for quorum verdicts (set by GameEngine). */
    public void setQuorumVerdictCallback(java.util.function.Consumer<String> cb) {
        this.onQuorumVerdict = cb;
    }
    private java.util.function.Consumer<String> onQuorumVerdict;

    /** Agents autonomously claim and complete one DePIN job per cycle. */
    private void runDePINWork() {
        if (depin == null) return;
        try {
            // #131 (H40): decay idle skill BEFORE claim eligibility is read, so
            // agents that stopped completing jobs lose their hard-job rights
            // instead of sitting on a monotonic high-water-mark forever.
            depin.applyIdleDecay(System.currentTimeMillis());
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

    /** #130 (H39): charge `agent`'s DePIN wallet for one Ollama call
     *  (chars/4 ≈ tokens, prompt+response). Null/empty response (failed or
     *  empty call) is NEVER charged — spend only happens on real output. */
    private void chargeComputeTax(String agent, String prompt, String response) {
        if (depin == null || response == null || response.isEmpty()) return;
        try {
            double charged = depin.chargeCompute(agent, prompt, response);
            if (charged > 0 && telemetry != null) {
                telemetry.record(com.mindpalace.backup.Telemetry.DEPIN, agent,
                    "compute tax -" + fmt(charged));
            }
        } catch (Exception e) {
            log("[AgentManager] compute tax error: " + e.getMessage());
        }
    }

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
                // #103: same dedupe + cap as the live path.
                if (!approvedTopics.contains(topic)) {
                    approvedTopics.add(topic);
                    while (approvedTopics.size() > APPROVED_TOPICS_CAP)
                        approvedTopics.remove(0);
                }
                log("[AgentManager] lexical topic AUTO-APPROVED (selftest): \"" + topic + "\"");
            } else {
                // #101: per-proposal vote (was actualVoteAll over the whole registry).
                WeightedQuorumVote.QuorumResult r = quorum.actualVote(id, modelScheduler);
                if (r != null && "APPROVED".equals(r.status)) {
                    // #103: dedupe (addIfAbsent) + bounded registry (oldest drops).
                    if (!approvedTopics.contains(topic)) {
                        approvedTopics.add(topic);
                        while (approvedTopics.size() > APPROVED_TOPICS_CAP)
                            approvedTopics.remove(0);
                    }
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
    public List<String> getApprovedTopics() { return new ArrayList<>(approvedTopics); }

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
            // #101: per-proposal vote (was actualVoteAll over the whole registry).
            WeightedQuorumVote.QuorumResult r = quorum.actualVote(id, modelScheduler);
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
        // #99 path jail: normalize and require the resolved path to stay inside
        // the repo (same guard ToolExecutor.executeTool uses for agent writes).
        Path file = repoDir.resolve(issue.file).normalize();
        if (!file.startsWith(repoDir.normalize())) {
            log("[AgentManager] solveOne path-jailed: " + issue.file);
            return false;
        }
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
            // TOOL_MODEL pinned, NOT routedModel: #134 flash routing keeps
            // routedModel on the cheapest lane for trivial cycles, but a file
            // fix is real tool work (same coherence rationale as toolRoundModel).
            String fixed = ollama.chat(TOOL_MODEL, ctx(TOOL_MODEL) + "\n" + prompt, "");
            // #130: the fix attempt just burned real GPU time — tax it.
            chargeComputeTax("Explorer", prompt, fixed);
            if (fixed == null || fixed.isEmpty() || fixed.equals(content)) return false;

            // Strip any markdown fence the model may have wrapped the output in.
            fixed = stripFence(fixed);
            if (fixed.equals(content)) return false;
            // #99 shrink guard: a reply much shorter than the original is a
            // truncated/hallucinated rewrite, not a targeted fix.
            if (fixed.length() < content.length() / 2) {
                log("[AgentManager] solveOne shrink-guard: fix " + fixed.length()
                    + " < half of " + content.length() + " chars — dropped");
                return false;
            }
            // #99 AST gate: agent-rewritten Python must clear the same gate
            // ToolExecutor enforces for created/edited .py files.
            if (issue.file.endsWith(".py")) {
                String gate = com.mindpalace.engine.AstGate.scan(fixed);
                if (gate != null) {
                    log("[AgentManager] solveOne ast-gate blocked: " + gate);
                    return false;
                }
            }

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
    public void runToolLoop(boolean approvedOnly) {
        if (!available || !running || currentRoom == null) return;
        String context = buildContext();
        if (context.isEmpty()) return;

        // Run the tool round on the scheduler's single worker thread so it never
        // overlaps another model call. The round does its own chatWithTools call.
        // #102: the round inherits the quorum verdict — APPROVED gets the full
        // toolset, otherwise it is capped at read_file (cheaper and keeps the
        // loop alive while still gating writes).
        final boolean ok = approvedOnly;
        lastToolActions = null;  // stale from a previous cycle is worse than none
        modelScheduler.submitToolRound(() -> executeToolRound(context, ok));
    }

    /** Model for a tool round: the routed model when Ollama reports tool support, else TOOL_MODEL (#144). */
    private String toolRoundModel() {
        String m = routedModel;
        if (ollama.supportsTools(m)) return m;
        log("[AgentManager] " + m + " has no native tool calling; tool round uses " + TOOL_MODEL + " (#144)");
        return TOOL_MODEL;
    }

    /**
     * #102: one tool round-trip; writes (edit/create/delete) only when the
     * quorum approved the tool-permission proposal — otherwise read_file-only.
     * On completion it directly invokes the critic (runCritic) as a
     * continuation, replacing the old autonomousCycle 2.5s sleep-poll (F4/D9).
     */
    private void executeToolRound(String context, boolean writesApproved) {
        try {
            // #144: the router picks by complexity, but tiers like tinyllama/phi/phi3 have no
            // native tool calling and would silently no-op. Tool rounds use a model that
            // Ollama reports as tool-capable (the routed one if it is, else TOOL_MODEL).
            final String toolModel = toolRoundModel();
            final List<JsonObject> roundTools = writesApproved
                ? TOOLS
                : TOOLS.stream().filter(t -> {
                    JsonObject f = t.getAsJsonObject("function");
                    return f != null && "read_file".equals(f.get("name").getAsString());
                }).collect(java.util.stream.Collectors.toList());
            List<Map<String, String>> msgs = new ArrayList<>();
            // Static instructions FIRST, the per-turn context line LAST: Ollama reuses work on an unchanged
            // prompt prefix, and this order measured 13.2 s -> 2.7 s prompt eval per call (llama3.2:1b, 363 tokens).
            msgs.add(Map.of("role", "system", "content", TOOL_SYSTEM_PROMPT + "\n" + ctx(toolModel)));
            msgs.add(Map.of("role", "user", "content", context + "\n\nTake ONE concrete action using your tools."));

            OllamaClient.ToolResult tr = ollama.chatWithTools(toolModel, msgs, roundTools);
            // #130: the round just burned real GPU time — tax it (Explorer's
            // wallet, the same participant #97 awards tool rounds to).
            if (tr != null) {
                chargeComputeTax("Explorer", msgs.toString(), tr.content);
            }
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

            // #97: a completed tool round earns a reward-category credit in the
            // wired DePIN economy (folded from the dead AgentRewardMechanism).
            // The tool agent plays the "Explorer" participant (same identity
            // runDePINWork credits for job completions).
            if (depin != null && tr.toolCalls.size() > 0) {
                for (OllamaClient.ToolCall call : tr.toolCalls) {
                    if (depin.award("Explorer", 1.0, com.mindpalace.economy.DePIN.REASON_TOOL_ROUND)) {
                        emit(onToolMessage, "[DePIN] tool action award +1.0 (" + call.name + ")");
                    }
                }
            }

            // Synthesis round: model reacts to its own actions seen as real
            // history (assistant tool_calls + tool results), per H03.
            feedback.add(Map.of("role", "user", "content",
                "You just took the actions shown above. In ONE line: what did you "
                + "learn from the results, and what is the next concrete step?"));
            OllamaClient.ToolResult sr = ollama.chatWithTools(toolModel, feedback, List.<com.google.gson.JsonObject>of());
            // #130: the synthesis call is taxed like every other Ollama call.
            if (sr != null) {
                chargeComputeTax("Explorer", feedback.toString(), sr.content);
            }
            if (sr != null && sr.content != null && !sr.content.isEmpty()) {
                emit(onToolMessage, "[Tool] " + sr.content);
            }

            // #102 (F4/D9): the critic is invoked HERE, as a continuation of the
            // completed round — the actions are guaranteed published, no 2.5s
            // sleep-and-poll on a scheduler thread that almost never won the race.
            runCritic(lastToolActions);
        } catch (Exception e) {
            log("[AgentManager] tool loop error: " + e.getMessage());
            // Even a failed round ends with an empty action sheet — pass it on
            // so the critic decision ("skip, nothing to review") stays honest.
            runCritic(null);
        }
    }

    /**
     * #102: critic review extracted from autonomousCycle. H01: with nothing to
     * review, the critic is skipped entirely — a reviewer without work is what
     * produced the endless "Would you like to move forward?" hallucination
     * loops. H02: the ANTI-LOOP DIRECTIVE lets the model bow out with 'SILENT'
     * — never broadcast an empty bow.
     */
    private void runCritic(String actions) {
        if (actions == null || actions.isEmpty()) {
            log("[AgentManager] critic skipped — no tool actions to review this cycle");
            return;
        }
        String criticPrompt = ctx(CRITIC_MODEL)
            + "\nThe tool agent just did, in room " + proposalTargetName(currentRoom)
            + ":\n" + actions
            + "\nEvaluate these CONCRETE actions. Name the files touched. "
            + "Is the result correct for the language? Should we proceed, "
            + "and what is the single biggest risk? Be specific, no meta-talk.";
        final String reviewed = actions;
        modelScheduler.submit(CRITIC_MODEL, criticPrompt, criticLifespan)
            .thenAccept(criticResp -> {
                if (criticResp != null && !criticResp.isEmpty()
                        && !criticResp.trim().equalsIgnoreCase("SILENT")) {
                    emit(onCriticMessage, "[Critic] " + criticResp);
                    if (telemetry != null) telemetry.record(
                        com.mindpalace.backup.Telemetry.AGENT, "critic-reviewed", reviewed.length() + " chars");
                }
            });
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
        // Single read: currentRoom changing mid-call must not misattribute a
        // file op to the wrong repo (the issue's double-read race, #106).
        Room room = currentRoom;
        if (room == null) return "no room context";
        String repo = room.getRepoName();
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
                    if (room.getLocalPath() != null) {
                        java.nio.file.Path fp = jailedLocalPath(room, filename);
                        if (fp == null) return "path escapes repo: " + filename;
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
                        String current = readCurrentFile(room, repo, filename);
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
                    // #126 (H38): quorum gate ON THE EXACT DIFF — a write lands
                    // only if the vote APPROVEs this change; a rejected diff
                    // never reaches disk. Telemetry records the veto.
                    if (!voteFileWrite("edit", filename,
                            oldStr != null && !oldStr.isEmpty() ? "patch: " + oldStr.length() + "→"
                                + (args.has("new_string") ? args.get("new_string").getAsString() : "").length()
                                + " chars" : "rewrite " + finalContent.length() + " chars")) {
                        if (telemetry != null) telemetry.record(com.mindpalace.backup.Telemetry.CODE,
                            "write-vetoed", filename);
                        return "edit vetoed by quorum (#126): " + filename;
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
                    if (room.getLocalPath() != null) {
                        java.nio.file.Path fp = jailedLocalPath(room, filename);
                        if (fp == null) return "path escapes repo: " + filename;
                        java.nio.file.Files.writeString(fp, finalContent);
                        if (telemetry != null) telemetry.record(com.mindpalace.backup.Telemetry.CODE, "edit", filename);
                        return "edited " + filename + " (local)";
                    }
                    return "edit failed (no auth/local path)";
                }
                case "create_file": {
                    String content = args.has("content") ? args.get("content").getAsString() : "";
                    // #126 (H38): quorum gate before the create lands.
                    if (!voteFileWrite("create", filename, content.length() + " chars")) {
                        if (telemetry != null) telemetry.record(com.mindpalace.backup.Telemetry.CODE,
                            "write-vetoed", filename);
                        return "create vetoed by quorum (#126): " + filename;
                    }
                    if (memory != null && !memory.recordCode(content, langOf(filename))) {
                        return "never-twice: identical code already written";
                    }
                    if (github != null && github.isAuthenticated()) {
                        boolean ok = github.upsertFile(repo, filename, content, "MindPalace agent create: " + filename, null);
                        if (ok && telemetry != null) telemetry.record(com.mindpalace.backup.Telemetry.CODE, "create", filename);
                        return ok ? "created " + filename : "create failed";
                    }
                    if (room.getLocalPath() != null) {
                        java.nio.file.Path fp = jailedLocalPath(room, filename);
                        if (fp == null) return "path escapes repo: " + filename;
                        java.nio.file.Files.writeString(fp, content);
                        if (telemetry != null) telemetry.record(com.mindpalace.backup.Telemetry.CODE, "create", filename);
                        return "created " + filename + " (local)";
                    }
                    return "create failed (no auth/local path)";
                }
                case "delete_file": {
                    // #126 (H38): quorum gate before the delete lands.
                    if (!voteFileWrite("delete", filename, "")) {
                        if (telemetry != null) telemetry.record(com.mindpalace.backup.Telemetry.CODE,
                            "write-vetoed", filename);
                        return "delete vetoed by quorum (#126): " + filename;
                    }
                    if (github != null && github.isAuthenticated()) {
                        boolean ok = github.deleteFile(repo, filename, null, "MindPalace agent delete: " + filename);
                        if (ok && telemetry != null) telemetry.record(com.mindpalace.backup.Telemetry.CODE, "delete", filename);
                        return ok ? "deleted " + filename : "delete failed";
                    }
                    if (room.getLocalPath() != null) {
                        java.nio.file.Path fp = jailedLocalPath(room, filename);
                        if (fp == null) return "path escapes repo: " + filename;
                        boolean ok = java.nio.file.Files.deleteIfExists(fp);
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

    /** Synchronized: drain worker + userWorker (chat model) both emit concurrently (#106). */
    private final java.util.Set<String> rephraseAttempts = java.util.Collections.synchronizedSet(java.util.Collections.newSetFromMap(new java.util.LinkedHashMap<>() {
        protected boolean removeEldestEntry(java.util.Map.Entry<String, Boolean> e) { return size() > 24; }
    }));

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

    /**
     * CARD-Q1 F9 (path jail): resolve a model-supplied filename against the
     * current room's local checkout, refusing escapes. Mirrors ToolExecutor's
     * invariant: normalize() must stay inside the repo root; absolute paths
     * and {@code ../} traversals are rejected. Returns null when the filename
     * escapes (caller reports and refuses the operation).
     */
    private java.nio.file.Path jailedLocalPath(Room room, String filename) {
        if (filename == null || room == null || room.getLocalPath() == null) return null;
        try {
            java.nio.file.Path root = java.nio.file.Path.of(room.getLocalPath()).toAbsolutePath().normalize();
            java.nio.file.Path p = root.resolve(filename).normalize();
            return p.startsWith(root) ? p : null;
        } catch (Exception e) {
            return null; // invalid path (e.g. absolute with wrong root)
        }
    }

    /** H05: fetch current file content (github first, then local) for patch edits. */
    private String readCurrentFile(Room room, String repo, String filename) {
        if (github != null && github.isAuthenticated()) {
            try {
                return github.fetchFileContent(repo, filename);
            } catch (Exception ignored) { }
        }
        if (room != null && room.getLocalPath() != null) {
            try {
                java.nio.file.Path fp = jailedLocalPath(room, filename);
                if (fp != null && java.nio.file.Files.exists(fp)) return java.nio.file.Files.readString(fp);
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

    /** #136 (H46, step 105): the shared OllamaClient (quota sink wiring in GameEngine). */
    public OllamaClient getOllama() { return ollama; }

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
            }, state);
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

    /**
     * Initialize GitHub issue polling bridge. Polls GitHub for issues labeled
     * 'hermes-queue' (filed by the 360 oversight cloud routine) and converts
     * them to local TASK files for Hermes execution.
     *
     * Requires GitHub Personal Access Token with 'repo' scope.
     */
    public void initGitHub(String repoOwner, String repoName, String githubToken) {
        try {
            // Must match card_hygiene.py's _default_home() exactly (AIGEN_HOME
            // env var overrides user.home/AIGEN_SYS, same as card_hygiene.py's
            // os.environ.get("AIGEN_HOME", Path.home()/"AIGEN_SYS")), or the
            // one-task-at-a-time guard in GitHubPollBridge checks a different
            // directory than the real queue when AIGEN_HOME is set (PR #281 review).
            String aigenHome = System.getenv("AIGEN_HOME");
            if (aigenHome == null || aigenHome.isBlank()) {
                aigenHome = System.getProperty("user.home") != null
                    ? Paths.get(System.getProperty("user.home"), "AIGEN_SYS").toString()
                    : null;
            }
            String taskDir = aigenHome != null
                ? Paths.get(aigenHome, "todo_management", "todo_files", "mindpalace").toString()
                : "/tmp/tasks";
            String ledgerPath = aigenHome != null
                ? Paths.get(aigenHome, ".hermes", "github_ledger.txt").toString()
                : "/tmp/github_ledger.txt";

            githubBridge = new GitHubPollBridge(repoOwner, repoName, githubToken, taskDir, ledgerPath, 30 * 60 * 1000);
            githubBridge.startPolling();
            log("[GitHub] Bridge initialized: polling every 30 minutes");
            log("[GitHub] Repo: " + repoOwner + "/" + repoName);
        } catch (Exception e) {
            log("[GitHub] ERROR initializing bridge: " + e.getMessage());
        }
    }

    /**
     * #145: the single startup path for the Reddit and GitHub bridges. Both are OFF
     * unless explicitly enabled AND credentialed through the environment, because
     * they feed public-sourced input (GitHub issues -> TASK files, Reddit -> quorum)
     * and the public-feedback gate needs the Architect's sign-off first. Secrets are
     * only ever read from env and never logged. The caller (GameEngine) invokes it once at startup.
     *
     * GitHub: MP_ENABLE_GITHUB_BRIDGE=1, MP_GITHUB_BRIDGE_REPO=owner/name, MP_GITHUB_BRIDGE_TOKEN
     * Reddit: MP_ENABLE_REDDIT_BRIDGE=1, MP_REDDIT_CLIENT_ID, MP_REDDIT_CLIENT_SECRET, MP_REDDIT_SUBREDDIT
     *
     * @param env environment lookup (System::getenv in production, a stub in tests)
     * @return one-line status, e.g. "github=skipped(disabled) reddit=skipped(disabled)"
     */
    public String startBridgesIfConfigured(java.util.function.Function<String, String> env) {
        StringBuilder status = new StringBuilder();

        String gh;
        if (!"1".equals(env.apply("MP_ENABLE_GITHUB_BRIDGE"))) {
            gh = "skipped(disabled)";
        } else {
            String repo = env.apply("MP_GITHUB_BRIDGE_REPO");
            String token = env.apply("MP_GITHUB_BRIDGE_TOKEN");
            if (repo == null || !repo.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+") || token == null || token.isBlank()) {
                gh = "skipped(enabled but repo/token missing or malformed)";
            } else {
                String[] p = repo.split("/", 2);
                initGitHub(p[0], p[1], token);
                gh = "started(" + repo + ")";
            }
        }
        status.append("github=").append(gh);

        String rd;
        if (!"1".equals(env.apply("MP_ENABLE_REDDIT_BRIDGE"))) {
            rd = "skipped(disabled)";
        } else {
            String id = env.apply("MP_REDDIT_CLIENT_ID");
            String secret = env.apply("MP_REDDIT_CLIENT_SECRET");
            String sub = env.apply("MP_REDDIT_SUBREDDIT");
            if (id == null || id.isBlank() || secret == null || secret.isBlank() || sub == null || !sub.matches("[A-Za-z0-9_]{2,21}")) {
                rd = "skipped(enabled but credentials/subreddit missing or malformed)";
            } else {
                rd = initReddit(id, secret, sub) != null ? "awaiting-authorization" : "failed";
            }
        }
        status.append(" reddit=").append(rd);
        log("[AgentManager] bridges: " + status);
        return status.toString();
    }

    public GitHubPollBridge getGitHubBridge() { return githubBridge; }
}
