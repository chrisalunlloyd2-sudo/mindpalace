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
            Request r = new Request.Builder()
                .url(BASE + "/chat")
                .post(RequestBody.create(body.toString(), MediaType.parse("application/json")))
                .build();
            try (Response resp = http.newCall(r).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) return null;
                JsonObject result = gson.fromJson(resp.body().string(), JsonObject.class);
                JsonObject msg = result.getAsJsonObject("message");
                if (msg.has("content") && !msg.get("content").isJsonNull()) return msg.get("content").getAsString();
                return "";
            }
        } catch (IOException e) {
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
                        String args = fn.has("arguments") ? fn.get("arguments").getAsString() : "{}";
                        calls.add(new ToolCall(name, args));
                    }
                }
                return new ToolResult(content, calls);
            }
        } catch (IOException e) {
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
