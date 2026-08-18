package com.mindpalace.agent;

import com.mindpalace.world.Room;
import com.mindpalace.world.Book;
import com.mindpalace.world.TodoCrystal;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Behavior tree — the SLM brain that drives an agent NPC's body.
 *
 * Each decision tick, the agent's real small language model (phi3:mini for
 * Explorer, tinyllama:1.1b for Critic) is asked "what should you do next?"
 * given its current context (room, books, crystals, KV state). The reply is
 * parsed into a discrete Action, which the NPC body then executes.
 *
 * COHERENCE: decisions run through a ModelLifespan, so the model keeps a
 * bounded conversation history + rolling summary + drift correctors + RAG
 * memory. This is what makes the agents talk about a continuous thread
 * instead of a fresh, contextless reply every tick.
 *
 * Falls back to deterministic KV/KG behavior if Ollama is slow/unavailable,
 * so the world never freezes waiting on a model.
 */
public class BehaviorTree {
    public enum Action {
        WALK_TO_ROOM, READ_BOOK, PLACE_BOOK, CARRY_CRYSTAL,
        MARK_RISK, GOSSIP, IDLE
    }

    private final OllamaClient ollama;
    private final String model;
    private final String roleName;
    private final ModelLifespan lifespan;   // stateful brain (history + drift + RAG)
    private final ModelScheduler scheduler; // shared gate — one model call at a time
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private volatile boolean decisionPending;
    private volatile Action lastAction = Action.IDLE;
    private volatile String lastReason = "";

    public BehaviorTree(OllamaClient ollama, String model, String roleName, ModelScheduler scheduler) {
        this.ollama = ollama;
        this.model = model;
        this.roleName = roleName;
        this.scheduler = scheduler;
        // Token budget below the model's ceiling; drift threshold from ModelConfig
        int budget = model.equals(ModelConfig.TOOL_MODEL) ? ModelConfig.TOOL_BUDGET : ModelConfig.CRITIC_BUDGET;
        this.lifespan = new ModelLifespan(ollama, model, budget, ModelConfig.DRIFT_THRESHOLD);
        this.lifespan.setSystemPrompt(SYSTEM_PROMPT);
    }

    /**
     * Request a decision asynchronously. When the SLM replies, the callback
     * fires with the chosen action. Non-blocking — the NPC keeps its current
     * behavior until the decision lands.
     */
    public void requestDecision(String context, Consumer<Action> onDecide) {
        if (decisionPending) return;
        decisionPending = true;

        executor.submit(() -> {
            Action a = decide(context);
            lastAction = a;
            decisionPending = false;
            if (onDecide != null) onDecide.accept(a);
        });
    }

    /** Synchronous decision — used by the async worker. */
    private Action decide(String context) {
        String prompt = buildPrompt(context);
        // Stateful chat through ModelLifespan, gated by the shared scheduler
        // (one model call at a time, 5-min spacing — never two models at once)
        String reply;
        if (scheduler != null) {
            try {
                reply = scheduler.submit(model, prompt, lifespan).get(120, TimeUnit.SECONDS);
            } catch (Exception e) {
                reply = null;
            }
        } else {
            reply = lifespan.chat(prompt);
        }
        if (reply == null || reply.isEmpty()) {
            return fallback();
        }
        lastReason = reply;
        return parse(reply);
    }

    private String buildPrompt(String context) {
        return "Current situation:\n" + context + "\n\n"
            + "Choose ONE action from: WALK_TO_ROOM, READ_BOOK, PLACE_BOOK, "
            + "CARRY_CRYSTAL, MARK_RISK, GOSSIP, IDLE.\n"
            + "Reply with just the action word and a short reason.";
    }

    private Action parse(String reply) {
        String up = reply.toUpperCase();
        if (up.contains("WALK")) return Action.WALK_TO_ROOM;
        if (up.contains("READ")) return Action.READ_BOOK;
        if (up.contains("PLACE")) return Action.PLACE_BOOK;
        if (up.contains("CARRY") || up.contains("CRYSTAL")) return Action.CARRY_CRYSTAL;
        if (up.contains("MARK") || up.contains("RISK")) return Action.MARK_RISK;
        if (up.contains("GOSSIP")) return Action.GOSSIP;
        return Action.IDLE;
    }

    /** Deterministic fallback when the model is unavailable — prefer a useful
     *  default (read a book) over a random action, so agents stay productive. */
    private Action fallback() {
        return Action.READ_BOOK;
    }

    public Action getLastAction() { return lastAction; }
    public String getLastReason() { return lastReason; }
    public void clearReason() { lastReason = ""; }
    public boolean isDecisionPending() { return decisionPending; }
    public ModelLifespan getLifespan() { return lifespan; }

    public void shutdown() { executor.shutdownNow(); }

    private static final String SYSTEM_PROMPT =
        "You are an autonomous coding agent with a physical body in a 3D world. "
        + "You decide your next action based on your surroundings and your recent "
        + "history. Stay on a coherent thread: remember what you were doing and "
        + "continue it. Be decisive and concise.";
}
