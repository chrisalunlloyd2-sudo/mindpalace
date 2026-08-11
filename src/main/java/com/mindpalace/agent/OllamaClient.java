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
        } catch (IOException ignored) {}
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
                if (msg.has("content")) return msg.get("content").getAsString();
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
}
