package com.mindpalace.genetics;

import com.google.gson.*;
import java.nio.file.*;

/**
 * GenomeControl — a file-based control channel for live GA tuning.
 *
 * A CLI (or any external tool) writes a JSON file; the running game polls it
 * each evolution tick and applies the requested changes. This is the "small
 * CLI to tweak these while the system runs" from the spec — no HTTP server,
 * no new deps, just a watched file.
 *
 * Shape of control.json:
 *   { "mutationRate": 0.2, "mutationSigma": 0.1,
 *     "loudness": 0.3, "harshness": 0.35, "steadiness": 0.2,
 *     "novelty": 0.15, "target": 0.0, "refresh": 3 }
 *
 * Any field may be omitted (leave unchanged). "refresh" = inject N random
 * newcomers. A "generation" field is written back by the game as an ack.
 */
public final class GenomeControl {

    /** The control file the game watches and the CLI writes. */
    public static final Path CONTROL_FILE = Path.of(
        System.getProperty("user.home"), "AIGEN_SYS", "mindpalace_memory", "evolution", "control.json");

    private final Gson gson = new Gson();

    /** Read the pending control request, or null if none/absent. */
    public JsonObject read() {
        try {
            if (!Files.exists(CONTROL_FILE)) return null;
            String s = Files.readString(CONTROL_FILE).trim();
            if (s.isEmpty()) return null;
            return JsonParser.parseString(s).getAsJsonObject();
        } catch (Exception ignored) {
            return null; // malformed → ignore, don't crash the game
        }
    }

    /** Ack a request by writing back the current generation, then clear it. */
    public void ack(int generation) {
        try {
            Files.createDirectories(CONTROL_FILE.getParent());
            JsonObject o = new JsonObject();
            o.addProperty("generation", generation);
            Files.writeString(CONTROL_FILE, gson.toJson(o));
        } catch (Exception ignored) {
        }
    }

    /** Clear the control file (no pending request). */
    public void clear() {
        try { Files.deleteIfExists(CONTROL_FILE); } catch (Exception ignored) {}
    }

    /** Apply a control request to the evolver + fitness. Returns a summary. */
    public String apply(JsonObject o, AudioEvolver ev, SonicFitness fit) {
        StringBuilder sb = new StringBuilder();
        if (o.has("mutationRate")) {
            ev.setMutationRate(o.get("mutationRate").getAsFloat());
            sb.append("rate=").append(String.format("%.2f", ev.mutationRate())).append(' ');
        }
        if (o.has("mutationSigma")) {
            ev.setMutationSigma(o.get("mutationSigma").getAsFloat());
            sb.append("sigma=").append(String.format("%.2f", ev.mutationSigma())).append(' ');
        }
        if (o.has("loudness")) { fit.setLoudnessWeight(o.get("loudness").getAsFloat()); sb.append("loud=").append(String.format("%.2f", fit.loudnessWeight())).append(' '); }
        if (o.has("harshness")) { fit.setHarshnessWeight(o.get("harshness").getAsFloat()); sb.append("harsh=").append(String.format("%.2f", fit.harshnessWeight())).append(' '); }
        if (o.has("steadiness")) { fit.setSteadinessWeight(o.get("steadiness").getAsFloat()); sb.append("steady=").append(String.format("%.2f", fit.steadinessWeight())).append(' '); }
        if (o.has("novelty")) { fit.setNoveltyWeight(o.get("novelty").getAsFloat()); sb.append("novel=").append(String.format("%.2f", fit.noveltyWeight())).append(' '); }
        if (o.has("target")) { fit.setTargetWeight(o.get("target").getAsFloat()); sb.append("target=").append(String.format("%.2f", fit.targetWeight())).append(' '); }
        if (o.has("refresh")) {
            int n = o.get("refresh").getAsInt();
            ev.refreshPopulation(n);
            sb.append("refresh=").append(n).append(' ');
        }
        return sb.toString().trim();
    }
}
