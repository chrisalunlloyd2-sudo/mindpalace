package com.mindpalace.agent;

import com.google.gson.*;
import okhttp3.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Ollama HTTP API client.
 * Chat completions, model listing, embedding generation.
 */
public class OllamaClient {
    private static final String BASE = "http://localhost:11434/api";
    private final OkHttpClient http;
    private final Gson gson = new Gson();

    // ONE-MODEL GATE: cold-shot policy means exactly one model may be in flight
    // at any moment. The scheduler already serializes, but this lock makes the
    // invariant physical — a second caller blocks until the first model unloads.
    // #108 (F14): in ONE-HOT mode the user-chat lane serializes on its OWN gate
    // (userGate) so a user reply can never block behind an in-flight
    // autonomous/vote call for up to the 120s read timeout. Default
    // MULTI-RESIDENT bypasses the gates entirely (server-side parallelism).
    private final Object modelGate = new Object();
    private final Object userGate = new Object();
    private static final ThreadLocal<Boolean> USER_LANE = ThreadLocal.withInitial(() -> false);

    /** #108 (F14): marks the calling thread as the USER-CHAT lane. Set once by
     * ModelScheduler.drainImmediateLoop so withGate routes user replies to
     * userGate instead of modelGate (one-hot mode). */
    public static void markUserLane(boolean on) { USER_LANE.set(on); }

    // Keep-alive window for the one resident model. "0" restores the old cold-shot
    // (unload after every reply). Measured 2026-10-03: unload-per-call costs 3.8-5.6 s
    // per call vs ~0.5 s hot (first disk-cold load up to 86 s). Override with
    // -Dmindpalace.ollama.keepAlive=... or env MP_OLLAMA_KEEP_ALIVE (e.g. "0", "2m", "5m").
    private static final String KEEP_ALIVE = resolveKeepAlive();
    // The one hot model; guarded by modelGate. A different model evicts it first,
    // so at most one model stays resident regardless of Ollama's own limits.
    private String hotModel;

    private static String resolveKeepAlive() {
        String v = System.getProperty("mindpalace.ollama.keepAlive", System.getenv("MP_OLLAMA_KEEP_ALIVE"));
        return (v == null || v.isBlank()) ? "5m" : v.trim();
    }

    // MULTI-RESIDENT MODE (default ON, t_e29e1f81): stop force-unloading the
    // previous model on every switch — Ollama's own OLLAMA_MAX_LOADED_MODELS
    // cap + LRU govern residency. Found 2026-10-16: one-hot evict sent
    // keep_alive:0 to the previous model on EVERY /chat, so the 3-model vote
    // roster (llama -> qwen -> deepseek, a switch every round) ping-ponged all
    // three out and every real ballot timed out at 30s against a 46-146s cold
    // load. Opt-out (restores one-hot): -Dmindpalace.ollama.multiResident=false
    // or env MP_OLLAMA_MULTI_RESIDENT=false.
    private static final boolean MULTI_RESIDENT = resolveMultiResident();

    private static boolean resolveMultiResident() {
        String v = System.getProperty("mindpalace.ollama.multiResident", System.getenv("MP_OLLAMA_MULTI_RESIDENT"));
        if (v == null || v.isBlank()) return true;
        v = v.trim().toLowerCase();
        return !(v.equals("false") || v.equals("0") || v.equals("off"));
    }

    // While multi-resident, pin every used model (keep_alive=-1 = never
    // idle-unloaded) so a vote roster assembled minutes apart stays hot.
    // Ollama still frees the LRU model when a new load exceeds
    // MAX_LOADED_MODELS, so RAM stays bounded. One-hot keeps its own
    // KEEP_ALIVE (default 5m).
    private static final String EFFECTIVE_KEEP_ALIVE = MULTI_RESIDENT ? "-1" : KEEP_ALIVE;

    // model -> does Ollama report native tool-calling for it (cached; #144)
    private final java.util.concurrent.ConcurrentHashMap<String, Boolean> toolSupport = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * True when Ollama's /api/show lists "tools" in the model's capabilities
     * (e.g. llama3.2:1b yes, phi3:mini no). A model without it silently ignores
     * the tools payload, so tool rounds must not be routed to it. If Ollama is
     * unreachable or too old to report capabilities, the answer is not cached
     * and only the configured TOOL_MODEL is assumed capable.
     */
    public boolean supportsTools(String model) {
        Boolean cached = toolSupport.get(model);
        if (cached != null) return cached;
        try {
            JsonObject b = new JsonObject();
            b.addProperty("model", model);
            Request r = new Request.Builder().url(BASE + "/show")
                .post(RequestBody.create(b.toString(), MediaType.parse("application/json"))).build();
            try (Response resp = http.newCall(r).execute()) {
                if (resp.isSuccessful() && resp.body() != null) {
                    JsonObject o = gson.fromJson(resp.body().string(), JsonObject.class);
                    if (o != null && o.has("capabilities") && o.get("capabilities").isJsonArray()) {
                        boolean yes = false;
                        for (JsonElement e : o.getAsJsonArray("capabilities")) {
                            if ("tools".equals(e.getAsString())) { yes = true; break; }
                        }
                        toolSupport.put(model, yes);
                        return yes;
                    }
                }
            }
        } catch (IOException | RuntimeException ignored) { /* fall through to the safe default */ }
        return ModelConfig.TOOL_MODEL.equals(model);
    }

    // Context window requested per call. Without it Ollama applies ITS default, which on this machine was
    // 131072 tokens: a 1.2B model loaded at 6.4 GB (KV cache ~5 GB) instead of ~1.5 GB, with multi-second
    // loads and ~1 GB RAM left free (observed 2026-10-06 via /api/ps context_length). Tool budget is 4000
    // tokens, so 8192 is ample. Override: -Dmindpalace.ollama.numCtx=... or env MP_OLLAMA_NUM_CTX.
    private static final int NUM_CTX = resolveNumCtx();

    private static int resolveNumCtx() {
        String v = System.getProperty("mindpalace.ollama.numCtx", System.getenv("MP_OLLAMA_NUM_CTX"));
        try {
            int n = (v == null || v.isBlank()) ? 8192 : Integer.parseInt(v.trim());
            return Math.max(2048, Math.min(n, 131072));
        } catch (NumberFormatException e) {
            return 8192;
        }
    }

    private static void applyOptions(JsonObject body) {
        JsonObject opts = body.has("options") && body.get("options").isJsonObject() ? body.getAsJsonObject("options") : new JsonObject();
        opts.addProperty("num_ctx", NUM_CTX);
        body.add("options", opts);
    }

    private static void applyKeepAlive(JsonObject body) {
        // t_e29e1f81 layer 3: bare "-1" STRING → Ollama 0.40 400 "time: missing unit
        // in duration \"-1\"" in MULTI_RESIDENT mode → every ballot's chat() returned
        // null → abstain_empty. Accept the negative-numeric form too so "keep_alive":
        // -1 (JSON number, pin-forever) goes out as designed.
        if (EFFECTIVE_KEEP_ALIVE.matches("-?\\d+")) body.addProperty("keep_alive", Long.parseLong(EFFECTIVE_KEEP_ALIVE));
        else body.addProperty("keep_alive", EFFECTIVE_KEEP_ALIVE);
    }

    /**
     * MULTI-RESIDENT (t_e29e1f81): the one-model gate is bypassed — vote ballots
     * and agent/user calls may overlap server-side (each model keeps its own
     * runner up to OLLAMA_MAX_LOADED_MODELS; NumParallel=1 only queues
     * same-model requests). Serializing the HTTP call through modelGate would
     * make a ballot wait up to a full agent generation (~120s) and blow its
     * 30s future — observed pre-patch. One-hot mode keeps the physical
     * single-runner invariant.
     */
    private <T> T withGate(java.util.function.Supplier<T> work) {
        if (MULTI_RESIDENT) return work.get();
        // #108 (F14): the user-chat lane (marked via ThreadLocal by the
        // scheduler's drainImmediateLoop) takes its OWN gate so a user reply
        // is never starved behind an in-flight autonomous/vote HTTP call.
        if (USER_LANE.get()) { synchronized (userGate) { return work.get(); } }
        synchronized (modelGate) { return work.get(); }
    }

    /** Caller must hold modelGate. Unloads the previously hot model when switching. */
    private void evictPreviousModelLocked(String model) {
        // MULTI-RESIDENT: never force-unload — LRU + OLLAMA_MAX_LOADED_MODELS
        // govern residency (see field docs). Still tracks hotModel for parity.
        if (!MULTI_RESIDENT) {
            String p = hotModel;
            hotModel = model;
            if (p == null || p.equals(model) || "0".equals(KEEP_ALIVE)) return;
            try {
                JsonObject b = new JsonObject();
                b.addProperty("model", p);
                b.addProperty("prompt", "");
                b.addProperty("stream", false);
                b.addProperty("keep_alive", 0);
                Request r = new Request.Builder().url(BASE + "/generate")
                    .post(RequestBody.create(b.toString(), MediaType.parse("application/json"))).build();
                try (Response resp = http.newCall(r).execute()) { /* best effort */ }
            } catch (IOException ignored) { /* eviction is best effort; the next load still works */ }
        } else {
            hotModel = model;
        }
    }

    public OllamaClient() {
        http = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build();
    }

    /** Check if Ollama is reachable. */
    public boolean isAvailable() {
        try {
            Request r = new Request.Builder().url(BASE + "/tags").get().build();
            try (Response resp = http.newCall(r).execute()) {
                return resp.isSuccessful();
            }
        } catch (IOException e) {
            return false;
        }
    }

    /** List available models. */
    public List<String> listModels() {
        List<String> models = new ArrayList<>();
        try {
            Request r = new Request.Builder().url(BASE + "/tags").get().build();
            try (Response resp = http.newCall(r).execute()) {
                if (resp.body() == null) return models;
                JsonObject obj = gson.fromJson(resp.body().string(), JsonObject.class);
                for (JsonElement m : obj.getAsJsonArray("models")) {
                    models.add(m.getAsJsonObject().get("name").getAsString());
                }
            }
        } catch (Exception e) {
            // network error or malformed JSON (JsonSyntaxException is a RuntimeException,
            // not an IOException) — return whatever we collected so far
        }
        return models;
    }

    /**
     * Send a chat completion request.
     * @param model  e.g. "tinyllama:1.1b", "phi3:mini"
     * @param messages  conversation history
     * @param tools  optional tool definitions (JSON schema)
     * @return  assistant's reply text, or null on failure
     */
    public String chat(String model, List<Map<String, String>> messages, List<JsonObject> tools) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("stream", false);
        // ONE-HOT: the model stays resident for KEEP_ALIVE (default 5m); a different
        // model evicts it (see evictPreviousModelLocked). "0" = old cold-shot policy.
        applyKeepAlive(body);
        applyOptions(body);

        JsonArray msgs = new JsonArray();
        for (Map<String, String> m : messages) {
            JsonObject msg = new JsonObject();
            msg.addProperty("role", m.get("role"));
            msg.addProperty("content", m.get("content"));
            msgs.add(msg);
        }
        body.add("messages", msgs);

        if (tools != null && !tools.isEmpty()) {
            JsonArray tarr = new JsonArray();
            for (JsonObject t : tools) tarr.add(t);
            body.add("tools", tarr);
        }

        try {
            return withGate(() -> {
                evictPreviousModelLocked(model);
                Request r = new Request.Builder()
                    .url(BASE + "/chat")
                    .post(RequestBody.create(body.toString(), MediaType.parse("application/json")))
                    .build();
                try (Response resp = http.newCall(r).execute()) {
                    if (!resp.isSuccessful() || resp.body() == null) return null;
                    JsonObject result = gson.fromJson(resp.body().string(), JsonObject.class);
                    // CARD-Q1 F13: bodies with no `message` object (or a
                    // malformed JSON) NPE / throw JsonSyntaxException — both
                    // are RuntimeExceptions and escaped the IOException catch.
                    if (result == null || result.getAsJsonObject("message") == null) return null;
                    JsonObject msg = result.getAsJsonObject("message");
                    if (msg.has("content") && !msg.get("content").isJsonNull()) return msg.get("content").getAsString();
                    return "";
                } catch (IOException e) {
                    // Supplier lambdas cannot throw checked exceptions — mirror
                    // the old outer catch here instead.
                    return null;
                }
            });
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Simple completion without tools. */
    public String chat(String model, String systemPrompt, String userMessage) {
        List<Map<String, String>> msgs = new ArrayList<>();
        msgs.add(Map.of("role", "system", "content", systemPrompt));
        msgs.add(Map.of("role", "user", "content", userMessage));
        return chat(model, msgs, null);
    }

    /**
     * A single tool-calling round-trip. Returns the assistant's text reply AND
     * any tool_calls it requested (name + JSON arguments). This is the missing
     * half of the tool loop — `chat()` only ever read `message.content` and
     * silently dropped `message.tool_calls`, so the tool agent could propose
     * but never actually invoke read_file/edit_file/create_file/delete_file.
     */
    public ToolResult chatWithTools(String model, List<Map<String, String>> messages, List<JsonObject> tools) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("stream", false);
        // Same one-hot keep-alive policy as chat().
        applyKeepAlive(body);
        applyOptions(body);

        JsonArray msgs = new JsonArray();
        for (Map<String, String> m : messages) {
            JsonObject msg = new JsonObject();
            msg.addProperty("role", m.get("role"));
            msg.addProperty("content", m.get("content"));
            // H03: assistant turns may carry tool_calls (the model's own
            // prior actions); tool turns carry their results. Ollama's chat
            // API accepts both — this is how the model SEES what it did,
            // instead of a user-message summary of it.
            if (m.containsKey("tool_calls") && !m.get("tool_calls").isEmpty()) {
                try {
                    msg.add("tool_calls", gson.fromJson(m.get("tool_calls"), JsonArray.class));
                } catch (Exception ignored) { /* malformed — omit */ }
            }
            msgs.add(msg);
        }
        body.add("messages", msgs);

        if (tools != null && !tools.isEmpty()) {
            JsonArray tarr = new JsonArray();
            for (JsonObject t : tools) tarr.add(t);
            body.add("tools", tarr);
        }

        try {
            return withGate(() -> {
                evictPreviousModelLocked(model);
                Request r = new Request.Builder()
                    .url(BASE + "/chat")
                    .post(RequestBody.create(body.toString(), MediaType.parse("application/json")))
                    .build();
                try (Response resp = http.newCall(r).execute()) {
                    if (!resp.isSuccessful() || resp.body() == null) return new ToolResult(null, List.of());
                    JsonObject result = gson.fromJson(resp.body().string(), JsonObject.class);
                    JsonObject msg = result.getAsJsonObject("message");
                    String content = (msg.has("content") && !msg.get("content").isJsonNull()) ? msg.get("content").getAsString() : "";
                    List<ToolCall> calls = new ArrayList<>();
                    if (msg.has("tool_calls")) {
                        for (JsonElement el : msg.getAsJsonArray("tool_calls")) {
                            JsonObject tc = el.getAsJsonObject();
                            JsonObject fn = tc.getAsJsonObject("function");
                            String name = fn.get("name").getAsString();
                            // CARD-Q1 F1: Ollama returns arguments as a JSON
                            // OBJECT; getAsString() throws and killed every
                            // tool round. Accept both shapes.
                            JsonElement a = fn.has("arguments") ? fn.get("arguments") : null;
                            String args = (a == null || a.isJsonNull()) ? "{}"
                                : a.isJsonPrimitive() ? a.getAsString() : a.toString();
                            calls.add(new ToolCall(name, args));
                        }
                    }
                    return new ToolResult(content, calls);
                } catch (IOException e) {
                    // Supplier lambdas cannot throw checked exceptions — mirror
                    // the old outer catch here instead.
                    return new ToolResult(null, List.of());
                }
            });
        } catch (RuntimeException e) {
            // CARD-Q1 F1: RuntimeException (UnsupportedOperationException,
            // NPE, JsonSyntaxException) must not silently kill the round.
            return new ToolResult(null, List.of());
        }
    }

    /** A tool call the model requested: a function name + JSON arguments. */
    public static class ToolCall {
        public final String name;
        public final String arguments;
        public ToolCall(String name, String arguments) { this.name = name; this.arguments = arguments; }
    }

    /** Result of a tool-calling round: text reply + requested tool calls. */
    public static class ToolResult {
        public final String content;
        public final List<ToolCall> toolCalls;
        public ToolResult(String content, List<ToolCall> toolCalls) {
            this.content = content; this.toolCalls = toolCalls;
        }
    }

    /**
     * Generate an embedding vector for a text (for drift detection / retrieval).
     * Uses nomic-embed-text. Returns null on failure.
     */
    public float[] embed(String text) {
        if (text == null || text.isEmpty()) return null;
        JsonObject body = new JsonObject();
        body.addProperty("model", "nomic-embed-text");
        body.addProperty("prompt", text);
        // MULTI-RESIDENT: nomic is a 4th model beyond the 3-slot vote roster.
        // Server-side OLLAMA_KEEP_ALIVE=-1 would pin it forever and starve a
        // vote slot, so give it an explicit idle window instead (it reloads in
        // ~1s when needed). One-hot mode keeps the old no-field behavior.
        if (MULTI_RESIDENT) body.addProperty("keep_alive", "5m");
        try {
            Request r = new Request.Builder()
                .url(BASE + "/embeddings")
                .post(RequestBody.create(body.toString(), MediaType.parse("application/json")))
                .build();
            try (Response resp = http.newCall(r).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) return null;
                JsonObject result = gson.fromJson(resp.body().string(), JsonObject.class);
                JsonArray arr = result.getAsJsonArray("embedding");
                float[] vec = new float[arr.size()];
                for (int i = 0; i < arr.size(); i++) vec[i] = arr.get(i).getAsFloat();
                return vec;
            }
        } catch (IOException e) {
            return null;
        }
    }

    /** Cosine similarity between two vectors. */
    public static float cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return 0f;
        float dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) return 0f;
        return dot / (float) (Math.sqrt(na) * Math.sqrt(nb));
    }
}
