package com.mindpalace.github;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * MP-014 (#160): the gist wall's source configuration — gist id, fleet file
 * names, and the word-library location — read from a JSON file instead of
 * hard-coded constants.
 *
 * <p>Resolution order: explicit {@code -Dmindpalace.gist.config=path} →
 * {@code ./config/gist_wall.json} → built-in defaults. A missing, unreadable
 * or malformed file never breaks the wall: every field falls back to the
 * documented default and the load problem is reported in {@link #loadError}.</p>
 *
 * <p>Deterministic, no network, pure parsing — unit-tested by the selftest
 * case "gist wall config (#160)".</p>
 */
public final class GistWallConfig {

    /** Property override, mirrors the repo's other config flags. */
    public static final String PROPERTY = "mindpalace.gist.config";
    /** Default location, relative to the game's working directory. */
    public static final String DEFAULT_FILE = "config/gist_wall.json";

    public String gistId = "04debe0724a26fdee12a9e6d82c4eb56";
    public String apiBase = "https://api.github.com";
    public String statusFile = "fleet_status.jsonl";
    public String logitsFile = "workflow_logits.jsonl";
    public String wordLibRepo = "chrisalunlloyd2-sudo/BDI_FSM_DAGs";
    public String wordLibPath = "word_library/success.jsonl";

    /** Non-null when the file existed but could not be used (defaults applied). */
    public String loadError;

    public static GistWallConfig defaults() {
        return new GistWallConfig();
    }

    /** Load from the resolved path; never throws, never returns null. */
    public static GistWallConfig load() {
        String prop = System.getProperty(PROPERTY);
        String path = (prop == null || prop.isBlank()) ? DEFAULT_FILE : prop;
        return load(Path.of(path));
    }

    /** Load from {@code file}; missing file = silent defaults, bad JSON = defaults + loadError. */
    public static GistWallConfig load(Path file) {
        GistWallConfig cfg = new GistWallConfig();
        if (file == null || !Files.isRegularFile(file)) return cfg;
        try {
            String body = Files.readString(file, StandardCharsets.UTF_8);
            cfg.applyJson(body);
        } catch (IOException | RuntimeException e) {
            cfg.loadError = file + ": " + e.getClass().getSimpleName()
                + (e.getMessage() == null ? "" : " " + e.getMessage());
        }
        return cfg;
    }

    /** Parse a JSON object body; unknown fields ignored, absent fields keep defaults. */
    GistWallConfig applyJson(String body) {
        JsonElement rootEl = JsonParser.parseString(body);
        if (rootEl == null || !rootEl.isJsonObject()) {
            throw new IllegalArgumentException("root is not a JSON object");
        }
        JsonObject root = rootEl.getAsJsonObject();
        gistId = str(root, "gistId", gistId);
        apiBase = trimTrailingSlash(str(root, "apiBase", apiBase));
        statusFile = str(root, "statusFile", statusFile);
        logitsFile = str(root, "logitsFile", logitsFile);
        wordLibRepo = str(root, "wordLibRepo", wordLibRepo);
        wordLibPath = str(root, "wordLibPath", wordLibPath);
        if (gistId.isBlank()) throw new IllegalArgumentException("gistId is blank");
        return this;
    }

    private static String str(JsonObject o, String key, String dflt) {
        if (!o.has(key) || o.get(key).isJsonNull()) return dflt;
        String v = o.get(key).getAsString().trim();
        return v.isEmpty() ? dflt : v;
    }

    private static String trimTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    /** Debug/telemetry one-liner. */
    public String describe() {
        return "gist=" + gistId + " base=" + apiBase + " status=" + statusFile
            + " logits=" + logitsFile + " wordlib=" + wordLibRepo + "/" + wordLibPath
            + (loadError == null ? "" : " (loadError: " + loadError + ")");
    }

    /** Pretty JSON of the effective config (used by tests + a future config dump). */
    public String toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("gistId", gistId);
        o.addProperty("apiBase", apiBase);
        o.addProperty("statusFile", statusFile);
        o.addProperty("logitsFile", logitsFile);
        o.addProperty("wordLibRepo", wordLibRepo);
        o.addProperty("wordLibPath", wordLibPath);
        return new Gson().toJson(o);
    }
}
