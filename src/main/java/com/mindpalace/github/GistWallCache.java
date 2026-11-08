package com.mindpalace.github;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * MP-014 (#160): last-good gist-wall cache — what the wall shows when GitHub
 * is unreachable or no token is set, plus the age label ("cached 3m ago").
 *
 * <p>Persisted as one small JSON file so the wall survives restarts; write is
 * best-effort and never blocks or throws into the render path (same doctrine
 * as {@code AudioSettings}/{@code PortalTheme} persistence).</p>
 */
public final class GistWallCache {

    /** Property override for the cache file location. */
    public static final String PROPERTY = "mindpalace.gist.cache";
    /** Default: alongside the other per-user state. */
    public static final String DEFAULT_FILE = ".mindpalace/gist_wall_cache.json";

    public final List<String> lines;
    public final long fetchedAtMs;

    public GistWallCache(List<String> lines, long fetchedAtMs) {
        this.lines = lines == null ? List.of() : List.copyOf(lines);
        this.fetchedAtMs = fetchedAtMs;
    }

    public boolean isEmpty() { return lines.isEmpty(); }

    /** "3m" / "2h" / "4d" — coarse and stable; sub-minute reads "now". */
    public String ageLabel(long nowMs) {
        long ms = Math.max(0L, nowMs - fetchedAtMs);
        long min = ms / 60_000L;
        if (min < 1) return "now";
        if (min < 60) return min + "m";
        long h = min / 60;
        if (h < 48) return h + "h";
        return (h / 24) + "d";
    }

    /** The cached lines with the age label appended as the last row. */
    public List<String> linesWithAge(long nowMs) {
        List<String> out = new ArrayList<>(lines);
        out.add("[cached " + ageLabel(nowMs) + " ago]");
        return out;
    }

    /** Best-effort persist; silent on failure (disk full / read-only home). */
    public void save(Path file) {
        if (file == null || lines.isEmpty()) return;
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            JsonObject o = new JsonObject();
            o.addProperty("fetchedAtMs", fetchedAtMs);
            JsonArray arr = new JsonArray();
            for (String l : lines) arr.add(l);
            o.add("lines", arr);
            Files.writeString(file, new Gson().toJson(o), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException ignored) {
            // cache is an optimization, never a failure path
        }
    }

    /** Load from the resolved default path; null when absent/unreadable. */
    public static GistWallCache load() {
        String prop = System.getProperty(PROPERTY);
        String path = (prop == null || prop.isBlank())
            ? Path.of(System.getProperty("user.home", "."), DEFAULT_FILE).toString()
            : prop;
        return load(Path.of(path));
    }

    /** Load from {@code file}; null when absent, unreadable or malformed. */
    public static GistWallCache load(Path file) {
        if (file == null || !Files.isRegularFile(file)) return null;
        try {
            JsonElement root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (root == null || !root.isJsonObject()) return null;
            JsonObject o = root.getAsJsonObject();
            long ts = o.has("fetchedAtMs") ? o.get("fetchedAtMs").getAsLong() : 0L;
            List<String> lines = new ArrayList<>();
            if (o.has("lines") && o.get("lines").isJsonArray()) {
                for (JsonElement e : o.getAsJsonArray("lines")) {
                    if (!e.isJsonNull()) lines.add(e.getAsString());
                }
            }
            return new GistWallCache(lines, ts);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
