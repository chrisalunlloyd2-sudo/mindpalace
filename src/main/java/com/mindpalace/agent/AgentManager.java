package com.mindpalace.agent;

import com.google.gson.*;
import com.mindpalace.agent.sims.*;
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
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final Gson gson = new Gson();
    private final ModelScheduler modelScheduler;  // serializes ALL model calls

    // Agent configs (centralized in ModelConfig)
    private static final String TOOL_MODEL = ModelConfig.TOOL_MODEL;
    private static final String CRITIC_MODEL = ModelConfig.CRITIC_MODEL;

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

    // ── Lifecycle ──

    public void start() {
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

        // Initialize SIMS1337 parity: pin agents to hexes, assign models, seed
        // the voting schema with the two agents as voters.
        initSimsParity();

        // Start autonomous cycle
        scheduler.scheduleWithFixedDelay(this::autonomousCycle, 30, CYCLE_MS / 1000, TimeUnit.SECONDS);
    }

    /** Wire the SIMS1337 pillars: FOW positions, quorum voters, LoRA context. */
    private void initSimsParity() {
        // FOW: pin the two agents to adjacent hexes, assign models to agents.
        fow.pinAgent("tool", new HexCoord(0, 0));
        fow.pinAgent("critic", new HexCoord(1, 0));
        fow.assignModel(TOOL_MODEL, "tool");
        fow.assignModel(CRITIC_MODEL, "critic");

        // Quorum: register both models as voters at their hex positions.
        quorum.setModelPosition(TOOL_MODEL, 0, 0);
        quorum.setModelPosition(CRITIC_MODEL, 1, 0);

        // LoRA: start on CODE (the tool agent's primary domain).
        lora.switchAdapter(AdapterType.CODE);

        log("[AgentManager] SIMS1337 parity wired — router + LoRA + quorum + FOW");
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

    /** Inject the GitHub client so the tool agent can actually execute tools. */
    public void setGitHubClient(com.mindpalace.github.GitHubClient github) {
        this.github = github;
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
        modelScheduler.submitImmediate(ModelConfig.CHAT_MODEL, prompt, chatLifespan)
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

        // Route the tool agent's model by task complexity (SIMS1337 ModelRouter).
        Complexity cx = Complexity.estimate(context);
        routedModel = router.select(cx);
        lora.switchAdapter(AdapterType.CODE);
        log("[AgentManager] routed " + cx + " → " + routedModel + " (" + router.reason(cx) + ")");

        // Tool agent now runs a REAL tool-calling loop: it can read/edit/create/
        // delete files in the current repo, not just emit text. The critic still
        // reviews the outcome afterward.
        runToolLoop();

        // Critic reviews the tool agent's work (scheduler spaces it 5 min)
        String criticPrompt = "The tool agent just acted on the current room. Evaluate its work. Should we proceed? What risks or improvements?";
        modelScheduler.submit(CRITIC_MODEL, criticPrompt, criticLifespan)
            .thenAccept(criticResp -> {
                if (criticResp != null && !criticResp.isEmpty()) {
                    emit(onCriticMessage, "[Auto] " + criticResp);
                }
            });

        // Quorum vote: register the cycle's proposal and let both models vote
        // (FOW-gated). The result is logged as the "voting schema" heartbeat.
        runQuorumVote(context);

        // Lexical bridge: chat logs → lexical vectors → quorum → TODO issues.
        // Runs on the same cycle; throttled internally to 60s. Approved topics
        // surface matching TODO/FIXME comments in non-legacy repos as issues.
        try {
            List<Issue> issues = runLexicalBridge();
            if (!issues.isEmpty() && onIssues != null) {
                log("[AgentManager] lexical bridge found " + issues.size() + " issues");
                onIssues.accept(issues);
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
            quorum.registerProposal(id, text, new HexCoord(0, 0));
            quorum.advanceTimePulse(0.1);
            quorum.autoVoteAll();
            WeightedQuorumVote.QuorumResult r = quorum.calculateQuorum(id);
            if (r != null) log("[AgentManager] quorum: " + r);
        } catch (Exception e) {
            log("[AgentManager] quorum error: " + e.getMessage());
        }
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
            quorum.registerProposal(id, topic, new HexCoord(0, 0));
            quorum.advanceTimePulse(0.05);
            quorum.autoVoteAll();
            WeightedQuorumVote.QuorumResult r = quorum.calculateQuorum(id);
            if (r != null && "APPROVED".equals(r.status)) {
                approvedTopics.add(topic);
                log("[AgentManager] lexical topic APPROVED: \"" + topic + "\"");
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

        for (String repo : allNames) {
            if (LegacyRepoClassifier.isLegacy(repo, allNames)) continue;
            // Find the local path for this repo (via the room list is not
            // available here; use the standard AIGEN_SYS path).
            Path repoDir = Paths.get("C:/Users/viper/AIGEN_SYS/repos", repo);
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
            msgs.add(Map.of("role", "system", "content", TOOL_SYSTEM_PROMPT));
            msgs.add(Map.of("role", "user", "content", context + "\n\nTake ONE concrete action using your tools."));

            OllamaClient.ToolResult tr = ollama.chatWithTools(routedModel, msgs, TOOLS);
            if (tr == null || tr.toolCalls.isEmpty()) {
                if (tr != null && tr.content != null && !tr.content.isEmpty()) {
                    emit(onToolMessage, "[Tool] " + tr.content);
                }
                return;
            }

            // Execute each requested tool call
            for (OllamaClient.ToolCall call : tr.toolCalls) {
                String result = executeTool(call);
                emit(onToolMessage, "[Tool] " + call.name + " → " + result);
            }
        } catch (Exception e) {
            log("[AgentManager] tool loop error: " + e.getMessage());
        }
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
                        return content != null ? "read " + filename + " (" + content.length() + " chars)" : "read failed";
                    }
                    // Local fallback
                    if (currentRoom.getLocalPath() != null) {
                        java.nio.file.Path fp = java.nio.file.Path.of(currentRoom.getLocalPath(), filename);
                        if (java.nio.file.Files.exists(fp)) {
                            String c = java.nio.file.Files.readString(fp);
                            return "read " + filename + " (" + c.length() + " chars)";
                        }
                    }
                    return "read failed (no auth/local path)";
                }
                case "edit_file": {
                    String content = args.has("content") ? args.get("content").getAsString() : "";
                    if (github != null && github.isAuthenticated()) {
                        boolean ok = github.upsertFile(repo, filename, content, "MindPalace agent edit: " + filename, null);
                        return ok ? "edited " + filename : "edit failed";
                    }
                    if (currentRoom.getLocalPath() != null) {
                        java.nio.file.Files.writeString(java.nio.file.Path.of(currentRoom.getLocalPath(), filename), content);
                        return "edited " + filename + " (local)";
                    }
                    return "edit failed (no auth/local path)";
                }
                case "create_file": {
                    String content = args.has("content") ? args.get("content").getAsString() : "";
                    if (github != null && github.isAuthenticated()) {
                        boolean ok = github.upsertFile(repo, filename, content, "MindPalace agent create: " + filename, null);
                        return ok ? "created " + filename : "create failed";
                    }
                    if (currentRoom.getLocalPath() != null) {
                        java.nio.file.Files.writeString(java.nio.file.Path.of(currentRoom.getLocalPath(), filename), content);
                        return "created " + filename + " (local)";
                    }
                    return "create failed (no auth/local path)";
                }
                case "delete_file": {
                    if (github != null && github.isAuthenticated()) {
                        boolean ok = github.deleteFile(repo, filename, null, "MindPalace agent delete: " + filename);
                        return ok ? "deleted " + filename : "delete failed";
                    }
                    if (currentRoom.getLocalPath() != null) {
                        java.nio.file.Files.deleteIfExists(java.nio.file.Path.of(currentRoom.getLocalPath(), filename));
                        return "deleted " + filename + " (local)";
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

    private void emit(Consumer<String> cb, String msg) {
        if (cb != null) cb.accept(msg);
        log(msg);
    }

    private void log(String msg) {
        if (onConsoleLog != null) onConsoleLog.accept(msg);
        System.out.println(msg);
    }

    // ── Tool definitions ──

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
        efFn.addProperty("description", "Edit a file in the current repo");
        JsonObject efParams = new JsonObject();
        efParams.addProperty("type", "object");
        JsonObject efProps = new JsonObject();
        efProps.add("filename", g.fromJson("{\"type\":\"string\",\"description\":\"Path to the file\"}", JsonObject.class));
        efProps.add("content", g.fromJson("{\"type\":\"string\",\"description\":\"New content for the file\"}", JsonObject.class));
        efParams.add("properties", efProps);
        efParams.add("required", g.fromJson("[\"filename\",\"content\"]", JsonArray.class));
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
}
