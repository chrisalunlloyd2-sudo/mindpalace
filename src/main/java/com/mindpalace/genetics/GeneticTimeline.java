package com.mindpalace.genetics;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;

/**
 * GeneticTimeline — the player's "genetic enhancement" ledger.
 *
 * Every DePIN shop purchase (and agent milestone) is recorded as a dated
 * "mutation" on a persistent timeline. The timeline is the player's genome:
 * a growing list of acquired modules, each with a real in-game effect. This
 * is the "players genetically enhancing the game with a timeline of added
 * modules" feature — the shop isn't a one-shot buy, it's a permanent splice
 * into the player's DNA, and the mansion wall shows the whole history.
 *
 * Persisted as JSON at <dataDir>/genome.json, so the timeline survives
 * restarts (unlike in-memory wallet state).
 */
public class GeneticTimeline {

    /** A single acquired module — one dated mutation on the genome. */
    public static final class Mutation {
        public final String module;      // e.g. "RAG", "KG Node", "LoRA"
        public final String effect;      // human-readable in-game effect
        public final double cost;        // credits paid
        public final long when;          // epoch millis
        public final int level;          // cumulative level for this module

        Mutation(String module, String effect, double cost, long when, int level) {
            this.module = module;
            this.effect = effect;
            this.cost = cost;
            this.when = when;
            this.level = level;
        }
    }

    private final Path saveFile;
    private final List<Mutation> mutations = new ArrayList<>();
    private final Map<String, Integer> levels = new LinkedHashMap<>();
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public GeneticTimeline(Path dataDir) {
        this.saveFile = dataDir.resolve("genome.json");
        load();
    }

    /** The ordered list of all mutations (oldest first). */
    public synchronized List<Mutation> all() { return new ArrayList<>(mutations); }

    /** The current level of a module (0 = not acquired). */
    public synchronized int levelOf(String module) {
        return levels.getOrDefault(module, 0);
    }

    /** Total number of distinct modules spliced in. */
    public synchronized int moduleCount() { return levels.size(); }

    /** Total number of mutations ever applied. */
    public synchronized int mutationCount() { return mutations.size(); }

    /** Apply a mutation (a shop purchase or milestone). Idempotent-free: each call adds one. */
    public synchronized Mutation mutate(String module, String effect, double cost) {
        int next = levels.getOrDefault(module, 0) + 1;
        levels.put(module, next);
        Mutation m = new Mutation(module, effect, cost, System.currentTimeMillis(), next);
        mutations.add(m);
        save();
        return m;
    }

    // ── Persistence ──

    private void load() {
        if (!Files.exists(saveFile)) return;
        try {
            JsonArray arr = JsonParser.parseString(Files.readString(saveFile)).getAsJsonArray();
            for (JsonElement e : arr) {
                JsonObject o = e.getAsJsonObject();
                String module = o.get("module").getAsString();
                String effect = o.get("effect").getAsString();
                double cost = o.get("cost").getAsDouble();
                long when = o.get("when").getAsLong();
                int level = o.get("level").getAsInt();
                mutations.add(new Mutation(module, effect, cost, when, level));
                levels.put(module, level);
            }
        } catch (Exception ignored) {
            // Corrupt/missing genome → start fresh rather than crash.
        }
    }

    private void save() {
        try {
            Files.createDirectories(saveFile.getParent());
            JsonArray arr = new JsonArray();
            for (Mutation m : mutations) {
                JsonObject o = new JsonObject();
                o.addProperty("module", m.module);
                o.addProperty("effect", m.effect);
                o.addProperty("cost", m.cost);
                o.addProperty("when", m.when);
                o.addProperty("level", m.level);
                arr.add(o);
            }
            Files.writeString(saveFile, gson.toJson(arr));
        } catch (Exception ignored) {
            // Best-effort persistence; the in-memory timeline still works.
        }
    }

    /** Human-readable date for a mutation (e.g. "Aug 24"). */
    public static String whenLabel(long when) {
        return java.time.LocalDate.ofEpochDay(when / 86_400_000L)
            .format(java.time.format.DateTimeFormatter.ofPattern("MMM d"));
    }
}
