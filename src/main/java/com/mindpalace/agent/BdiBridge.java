package com.mindpalace.agent;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.mindpalace.entity.AgentNPC;
import com.mindpalace.world.Room;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Step 81 (H27): BDI bridge v0 - polls the BDI_FSM_AGENT webui over HTTP and
 * renders agent state as [BDI] chat events (via AgentChat, game thread only)
 * plus optional NPC drives via the public attractTo(Room) surface.
 *
 * Hermeticity by construction (#39 poller + #47 backup mirror): never
 * constructed in demo mode (GameEngine gates construction on !demoMode).
 * The poll daemon only reads HTTP and hands immutable directives to the
 * game thread through a ConcurrentLinkedQueue - AgentChat.addMessage is
 * NOT thread-safe and must only run on the game thread.
 *
 * Config chain (RepoMapper mirror): env BDI_FSM_AGENT_URL -> property
 * mindpalace.bdi.url -> default http://localhost:8099.
 * Poll chain: env BDI_FSM_AGENT_POLL_SECONDS -> property mindpalace.bdi.pollSeconds -> 5.
 */
public class BdiBridge {

    private static final String DEFAULT_URL = "http://localhost:8099";
    private static final String CHAT_TAG = "[BDI] ";
    private static final int MAX_CHAT_CHARS = 160;
    private static final int DEGRADE_AFTER_MISSES = 3;

    /** Immutable handoff unit: composed on the poll thread, applied on the game thread. */
    private static final class Directive {
        final String chat;
        final String npcName;
        final Room room;
        Directive(String chat, String npcName, Room room) {
            this.chat = chat;
            this.npcName = npcName;
            this.room = room;
        }
    }

    private final AgentChat agentChat;
    private final List<AgentNPC> npcs;
    private final List<Room> rooms;
    private final String baseUrl;
    private final long intervalMs;
    private final OkHttpClient http;
    private final ConcurrentLinkedQueue<Directive> inbox = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread pollThread;

    public BdiBridge(AgentChat agentChat, List<AgentNPC> npcs, List<Room> rooms) {
        this.agentChat = agentChat;
        this.npcs = npcs;
        this.rooms = rooms;
        int resolved = 5;
        try {
            String pollRaw = System.getenv("BDI_FSM_AGENT_POLL_SECONDS");
            if (pollRaw == null || pollRaw.trim().isEmpty()) {
                pollRaw = System.getProperty("mindpalace.bdi.pollSeconds");
            }
            if (pollRaw != null && !pollRaw.trim().isEmpty()) {
                resolved = Integer.parseInt(pollRaw.trim());
            }
        } catch (NumberFormatException nfe) {
            resolved = 5;
        }
        String raw = System.getenv("BDI_FSM_AGENT_URL");
        if (raw == null || raw.trim().isEmpty()) {
            raw = System.getProperty("mindpalace.bdi.url");
        }
        this.baseUrl = (raw == null || raw.trim().isEmpty()) ? DEFAULT_URL : raw.trim();
        long seconds = Math.max(1, resolved);
        this.intervalMs = seconds * 1000L;
        this.http = new OkHttpClient().newBuilder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build();
    }

    /** Starts the poll daemon. Idempotent; daemon thread never touches GL/render state. */
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        pollThread = new Thread(this::pollLoop, "bdi-bridge-poller");
        pollThread.setDaemon(true);
        pollThread.start();
        System.out.println("[BDI] bridge polling " + baseUrl + "/state every " + intervalMs + "ms (step 81)");
    }

    /** Halts the poll daemon. Safe to call repeatedly; called from GameEngine.stop(). */
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        if (pollThread != null) {
            pollThread.interrupt();
        }
        System.out.println("[BDI] bridge stopped");
    }

    /**
     * Drains queued directives on the GAME thread: chat events via
     * AgentChat.addMessage (not thread-safe - must run here) and optional
     * NPC drives via the public attractTo surface. Called once per update.
     */
    public void drain() {
        Directive directive;
        while ((directive = inbox.poll()) != null) {
            if (directive.chat != null && agentChat != null) {
                agentChat.addMessage(directive.chat);
            }
            if (directive.room != null && directive.npcName != null && npcs != null) {
                for (AgentNPC npc : npcs) {
                    if (directive.npcName.equals(npc.getName())) {
                        npc.attractTo(directive.room);
                        break;
                    }
                }
            }
        }
    }

    private void pollLoop() {
        int consecutiveMisses = 0;
        boolean degradedAnnounced = false;
        while (running.get()) {
            String body = null;
            try {
                Request request = new Request.Builder().url(baseUrl + "/state").get().build();
                try (Response response = http.newCall(request).execute()) {
                    if (response.isSuccessful() && response.body() != null) {
                        body = response.body().string();
                    }
                }
            } catch (IOException | RuntimeException e) {
                body = null;
            }
            if (body == null) {
                consecutiveMisses++;
                if (consecutiveMisses >= DEGRADE_AFTER_MISSES && !degradedAnnounced) {
                    System.out.println("[BDI] agent unreachable at " + baseUrl
                        + " - pausing NPC drive, polling continues quietly");
                    degradedAnnounced = true;
                }
            } else {
                if (degradedAnnounced) {
                    System.out.println("[BDI] agent reachable again");
                }
                degradedAnnounced = false;
                consecutiveMisses = 0;
                handleState(body);
            }
            try {
                Thread.sleep(intervalMs);
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    /**
     * Tolerant parse (OllamaClient pattern): top-level "intention" string if
     * present, else the whole body trimmed to MAX_CHAT_CHARS. Malformed JSON
     * degrades to the body-trim fallback; no exception escapes the poller.
     */
    private void handleState(String body) {
        String agent = null;
        String intention = null;
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            if (root.has("agent") && !root.get("agent").isJsonNull()) {
                agent = root.get("agent").getAsString();
            }
            if (root.has("intention") && !root.get("intention").isJsonNull()) {
                intention = root.get("intention").getAsString();
            }
        } catch (RuntimeException e) {
            intention = null;
        }
        if (intention == null || intention.trim().isEmpty()) {
            intention = body.trim();
        }
        if (intention.length() > MAX_CHAT_CHARS) {
            intention = intention.substring(0, MAX_CHAT_CHARS) + "...";
        }
        String label = (agent == null || agent.trim().isEmpty()) ? "agent" : agent.trim();
        Room target = matchRoom(intention);
        String npcName = target == null ? null : findNpcName(label);
        if (target != null && npcName == null) {
            target = null;
        }
        inbox.add(new Directive(CHAT_TAG + label + ": " + intention, npcName, target));
    }

    /** v1 mapping: intention mentioning a room's repo name routes an NPC there (longest name wins). */
    private Room matchRoom(String intention) {
        if (rooms == null || intention == null) {
            return null;
        }
        String lower = intention.toLowerCase();
        Room best = null;
        int bestLen = 0;
        for (Room room : rooms) {
            String repoName = room.getRepoName();
            if (repoName == null || repoName.trim().isEmpty()) {
                continue;
            }
            String candidate = repoName.trim().toLowerCase();
            if (lower.contains(candidate) && candidate.length() > bestLen) {
                best = room;
                bestLen = candidate.length();
            }
        }
        return best;
    }

    /** Resolves an agent label to an NPC roster name (COW-safe read-only snapshot). */
    private String findNpcName(String label) {
        if (npcs == null) {
            return null;
        }
        for (AgentNPC npc : npcs) {
            if (npc.getName() != null && npc.getName().equalsIgnoreCase(label)) {
                return npc.getName();
            }
        }
        return null;
    }
}
