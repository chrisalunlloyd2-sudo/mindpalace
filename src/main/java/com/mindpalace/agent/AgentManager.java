package com.mindpalace.agent;

import com.google.gson.*;
import com.mindpalace.world.Book;
import com.mindpalace.world.Room;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Manages two LLM agents from SIMS1337:
 *   - Tool Agent (phi3:mini) — tool-calling, can read/edit/create/delete files
 *   - Critic Agent (tinyllama:1.1b) — actor-critic, reviews tool agent's actions
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

    // Current context
    private Room currentRoom;
    private Book currentBook;
    private String lastUserMessage;
    private final Set<String> discoveredRepos = new HashSet<>();

    // Callbacks for tool execution
    private Consumer<String> onToolMessage;
    private Consumer<String> onCriticMessage;
    private Consumer<String> onConsoleLog;

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

        // Start autonomous cycle
        scheduler.scheduleAtFixedRate(this::autonomousCycle, 30, CYCLE_MS / 1000, TimeUnit.SECONDS);
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

    // ── User chat ──

    /** User sends a message — agents respond immediately. */
    public void onUserChat(String message) {
        if (!available || !running) return;
        this.lastUserMessage = message;

        String context = buildContext();
        String prompt = context + "\n\nUser says: " + message + "\n\nRespond helpfully. If the user wants to modify code, propose specific changes.";

        // Tool agent responds first
        scheduler.execute(() -> {
            String toolResp = toolLifespan.chat(context + "\n\nUser says: " + message + "\n\nRespond helpfully. If the user wants to modify code, propose specific changes.");
            if (toolResp != null && !toolResp.isEmpty()) {
                emit(onToolMessage, "[Tool Agent] " + toolResp);
            }

            // Critic reviews
            String criticPrompt = "The user said: " + message + "\nThe tool agent responded: " + (toolResp != null ? toolResp : "(no response)") + "\nProvide your critique and suggestions.";
            String criticResp = criticLifespan.chat(criticPrompt);
            if (criticResp != null && !criticResp.isEmpty()) {
                emit(onCriticMessage, "[Critic] " + criticResp);
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

        // Tool agent proposes an action
        String toolPrompt = context + "\n\nIt's time for your 5-minute review. What should we do? Propose one concrete action.";
        String toolResp = toolLifespan.chat(toolPrompt);
        if (toolResp != null && !toolResp.isEmpty()) {
            emit(onToolMessage, "[Auto] " + toolResp);
        }

        // Critic reviews the proposal
        String criticPrompt = "The tool agent proposes: " + (toolResp != null ? toolResp : "(nothing)") + "\nEvaluate this proposal. Should we proceed? What risks or improvements?";
        String criticResp = criticLifespan.chat(criticPrompt);
        if (criticResp != null && !criticResp.isEmpty()) {
            emit(onCriticMessage, "[Auto] " + criticResp);
        }
    }

    // ── Helpers ──

    private String buildContext() {
        StringBuilder sb = new StringBuilder();
        sb.append("Fog of war: ").append(discoveredRepos.size())
          .append(" repos discovered so far. Hidden repos remain unexplored.\n");
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
                if (content.length() > 2000) content = content.substring(0, 2000) + "...";
                sb.append("Book content:\n```\n").append(content).append("\n```\n");
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
        "When the user asks to modify code, use your tools to propose changes. " +
        "Be concise and specific. When you want to use a tool, describe what you'd do. " +
        "You are running on " + TOOL_MODEL + " via Ollama. " +
        "The current room and book context will be provided before each message.";

    private static final String CRITIC_SYSTEM_PROMPT =
        "You are an actor-critic AI agent in MindPalace, a 3D GitHub repository explorer. " +
        "Your role is to review the tool agent's proposals and provide semantic feedback. " +
        "Evaluate: is the action correct? Are there risks? Could it be improved? " +
        "Be constructive and specific. You are running on " + CRITIC_MODEL + " via Ollama. " +
        "The current room and book context will be provided before each message.";

    // ── Getters ──

    public boolean isAvailable() { return available; }
    public boolean isRunning() { return running; }
    public String getToolModel() { return TOOL_MODEL; }
    public String getCriticModel() { return CRITIC_MODEL; }
    public ModelLifespan getToolLifespan() { return toolLifespan; }
    public ModelLifespan getCriticLifespan() { return criticLifespan; }
}
