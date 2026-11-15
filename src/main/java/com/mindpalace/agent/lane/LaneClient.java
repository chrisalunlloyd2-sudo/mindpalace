package com.mindpalace.agent.lane;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

/**
 * LaneClient — talks to the lane: a local llama.cpp server that holds ONE base model plus its LoRA adapters. Ollama no
 * longer loads adapters, so this is where a LoRA swap is real. The adapter scales travel inside each request (0 = off,
 * 1 = on, between = a mix), so a swap and its call cannot be split by another caller and there is no shared state to race
 * on. Measured on this PC (2026-11-14, qwen2.5:0.5b, CPU only): a swap costs 1-130 ms, and scale 0 gives the base
 * model's answer back byte for byte.
 *
 * Every chat sends a scale for EVERY loaded adapter: the server starts with all of them at 1.0, so a request that named
 * none would run them all. Answers are deterministic (temperature 0, fixed seed): the same recipe and prompt always give
 * the same text, which is what makes an A/B between two recipes mean something.
 *
 * Loopback only: the constructor refuses any host that is not this machine (the local-models law).
 */
public final class LaneClient {

    /** -Dmindpalace.lane.url=http://127.0.0.1:18080 turns the lane seats on. Unset = no lane. */
    public static final String URL_PROP = "mindpalace.lane.url";
    private static final int SEED = 7;

    /** One adapter the server has loaded; name is its file name without ".gguf". */
    public record Adapter(int id, String name, double scale) {}

    public record Reply(String text, long ms) {}

    /** The wire. body is null for a GET. Returns the response body, or throws when the server did not answer 2xx. */
    @FunctionalInterface
    public interface Transport { String send(String path, String body) throws Exception; }

    private final Transport wire;

    public LaneClient(String baseUrl) { this(http(baseUrl)); }

    /** Tests pass a fake wire; the game uses {@link #LaneClient(String)}. */
    public LaneClient(Transport wire) { this.wire = wire; }

    /** Throws unless url is plain http on this machine. */
    public static void assertLoopback(String url) {
        URI u;
        try { u = URI.create(url == null ? "" : url.trim()); } catch (IllegalArgumentException e) { u = null; }
        String h = u == null ? null : u.getHost();
        boolean local = h != null && (h.equals("127.0.0.1") || h.equalsIgnoreCase("localhost") || h.equals("[::1]"));
        if (!local || !"http".equalsIgnoreCase(u.getScheme()))
            throw new IllegalArgumentException("quota firewall: the lane must be http on this machine, not " + url);
    }

    private static Transport http(String baseUrl) {
        assertLoopback(baseUrl);
        String base = baseUrl.trim().replaceAll("/+$", "");
        OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(Long.getLong("mindpalace.lane.readTimeoutSec", 600L), TimeUnit.SECONDS)
            .build();
        return (path, body) -> {
            Request.Builder r = new Request.Builder().url(base + path);
            if (body != null) r.post(RequestBody.create(body, MediaType.parse("application/json")));
            try (Response resp = http.newCall(r.build()).execute()) {
                String s = resp.body() == null ? "" : resp.body().string();
                if (!resp.isSuccessful()) throw new IllegalStateException("lane HTTP " + resp.code());
                return s;
            }
        };
    }

    /** True when the server is up and its model has finished loading (it answers 503 while loading). */
    public boolean healthy() {
        try { return wire.send("/health", null).contains("\"ok\""); } catch (Exception e) { return false; }
    }

    /** The adapters the server loaded at start, in id order. */
    public List<Adapter> adapters() {
        List<Adapter> out = new ArrayList<>();
        for (JsonElement e : JsonParser.parseString(call("/lora-adapters", null)).getAsJsonArray()) {
            JsonObject o = e.getAsJsonObject();
            String path = o.get("path").getAsString().replace('\\', '/');
            String name = path.substring(path.lastIndexOf('/') + 1).replaceFirst("(?i)\\.gguf$", "");
            out.add(new Adapter(o.get("id").getAsInt(), name, o.get("scale").getAsDouble()));
        }
        return out;
    }

    /**
     * One chat turn with the given adapter scales (adapter id -> scale). scales must name every loaded adapter; build it
     * with {@link AdapterBank#vector}. system may be empty.
     */
    public Reply chat(String system, String user, Map<Integer, Double> scales, int maxTokens) {
        JsonObject b = new JsonObject();
        JsonArray msgs = new JsonArray();
        if (system != null && !system.isBlank()) msgs.add(message("system", system));
        msgs.add(message("user", user == null ? "" : user));
        b.add("messages", msgs);
        b.addProperty("max_tokens", maxTokens);
        b.addProperty("temperature", 0);
        b.addProperty("seed", SEED);
        b.addProperty("cache_prompt", false);
        JsonArray lora = new JsonArray();
        for (Map.Entry<Integer, Double> e : new TreeMap<>(scales).entrySet()) {
            JsonObject a = new JsonObject();
            a.addProperty("id", e.getKey());
            a.addProperty("scale", e.getValue());
            lora.add(a);
        }
        b.add("lora", lora);
        long t0 = System.nanoTime();
        JsonObject resp = JsonParser.parseString(call("/v1/chat/completions", b.toString())).getAsJsonObject();
        String text = resp.getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message").get("content").getAsString();
        return new Reply(text, (System.nanoTime() - t0) / 1_000_000);
    }

    private static JsonObject message(String role, String content) {
        JsonObject m = new JsonObject();
        m.addProperty("role", role);
        m.addProperty("content", content);
        return m;
    }

    private String call(String path, String body) {
        try { return wire.send(path, body); }
        catch (RuntimeException e) { throw e; }
        catch (Exception e) { throw new IllegalStateException("lane unreachable: " + e.getClass().getSimpleName()); }
    }
}
