package com.mindpalace.agent.sprites;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.Set;

/**
 * Local read-only KG scans, enabled with {@code -Dmindpalace.sprites.enabled=true}.
 * The quality tier, KG database, and proposal queue can be set with
 * {@code mindpalace.sprites.quality}, {@code mindpalace.kg.db}, and
 * {@code mindpalace.sprites.queue}.
 */
public final class SpriteProposalService {
    public enum Quality {
        LOW(1, 900),
        STANDARD(10, 300),
        HIGH(50, 60);

        private final int batchSize;
        private final long intervalSeconds;

        Quality(int batchSize, long intervalSeconds) {
            this.batchSize = batchSize;
            this.intervalSeconds = intervalSeconds;
        }

        public int batchSize() { return batchSize; }
        public long intervalSeconds() { return intervalSeconds; }

        public static Quality fromProperty(String value) {
            if (value == null) return STANDARD;
            try {
                return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                return STANDARD;
            }
        }
    }

    private static final Gson GSON = new Gson();
    private static final String[] SPRITES = {"Dispatcher", "Linkwarden", "Cartographer"};

    private final boolean enabled;
    private final Path kgPath;
    private final Path queuePath;
    private final Quality quality;
    private final Set<String> queuedIds = new HashSet<>();
    private boolean queueLoaded;

    public SpriteProposalService(boolean enabled, Path kgPath, Path queuePath, Quality quality) {
        this.enabled = enabled;
        this.kgPath = kgPath;
        this.queuePath = queuePath;
        this.quality = quality;
    }

    public static SpriteProposalService fromSystemProperties() {
        Path home = Path.of(System.getProperty("user.home"));
        return new SpriteProposalService(
            Boolean.getBoolean("mindpalace.sprites.enabled"),
            Path.of(System.getProperty("mindpalace.kg.db",
                home.resolve("AIGEN_SYS/db/kg_graph.db").toString())),
            Path.of(System.getProperty("mindpalace.sprites.queue",
                home.resolve("AIGEN_SYS/mindpalace_memory/sprite_proposals.jsonl").toString())),
            Quality.fromProperty(System.getProperty("mindpalace.sprites.quality")));
    }

    public boolean isEnabled() { return enabled; }
    public long intervalSeconds() { return quality.intervalSeconds(); }

    /**
     * Reads open TODOs from the real KG in read-only mode and appends proposals
     * to the separate queue. It never modifies the KG or executes proposals.
     */
    public synchronized int collectProposals() throws SQLException, IOException {
        if (!enabled || !Files.isRegularFile(kgPath)) return 0;

        loadQueuedIds();
        int appended = 0;
        Set<String> knownRepos = new HashSet<>();
        try (Connection connection = openReadOnly(kgPath);
             PreparedStatement statement = connection.prepareStatement(
                 "SELECT repo, todo_type, content FROM github_todos "
                     + "WHERE status='open' ORDER BY priority_score DESC LIMIT ?")) {
            statement.setInt(1, quality.batchSize());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String repo = valueOr(rows.getString("repo"), "unknown");
                    String type = valueOr(rows.getString("todo_type"), "TODO");
                    String content = valueOr(rows.getString("content"), "");
                    String rowKey = repo + '\n' + type + '\n' + content;
                    if (!knownRepos.add(rowKey)) continue;
                    for (String sprite : SPRITES) {
                        JsonObject proposal = proposal(sprite, repo, type, content);
                        String id = proposal.get("id").getAsString();
                        if (!queuedIds.contains(id)) {
                            append(proposal);
                            queuedIds.add(id);
                            appended++;
                        }
                    }
                }
            }
        }
        return appended;
    }

    private void loadQueuedIds() throws IOException {
        if (queueLoaded) {
            return;
        }
        queueLoaded = true;
        if (Files.isRegularFile(queuePath)) {
            for (String line : Files.readAllLines(queuePath, StandardCharsets.UTF_8)) {
                try {
                    JsonObject item = GSON.fromJson(line, JsonObject.class);
                    if (item != null && item.has("id")) queuedIds.add(item.get("id").getAsString());
                } catch (RuntimeException ignored) {
                    // Ignore malformed lines without discarding earlier proposals.
                }
            }
        }
    }

    private void append(JsonObject proposal) throws IOException {
        Path parent = queuePath.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        try (BufferedWriter writer = Files.newBufferedWriter(queuePath, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            writer.write(GSON.toJson(proposal));
            writer.newLine();
        }
    }

    private static Connection openReadOnly(Path path) throws SQLException {
        String uriPath = path.toAbsolutePath().toUri().toString();
        return DriverManager.getConnection("jdbc:sqlite:" + uriPath + "?mode=ro");
    }

    private static JsonObject proposal(String sprite, String repo, String type, String content) {
        String text = switch (sprite) {
            case "Dispatcher" -> "Route " + type + " in " + repo + " for triage";
            case "Linkwarden" -> "Link this " + type + " record to repository " + repo;
            default -> "Map repository " + repo + " as the owner of this " + type + " item";
        };
        if (!content.isBlank()) text += ": " + content.substring(0, Math.min(content.length(), 1000));

        JsonObject proposal = new JsonObject();
        proposal.addProperty("id", hash(sprite + '\n' + repo + '\n' + type + '\n' + content));
        proposal.addProperty("sprite", sprite);
        proposal.addProperty("source", "AIGEN_SYS.db.github_todos");
        proposal.addProperty("repo", repo);
        proposal.addProperty("type", type);
        proposal.addProperty("text", text);
        proposal.addProperty("status", "pending");
        proposal.addProperty("created_at", java.time.Instant.now().toString());
        return proposal;
    }

    private static String valueOr(String value, String fallback) {
        return value == null ? fallback : value;
    }

    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}
