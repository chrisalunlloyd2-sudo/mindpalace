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
 *     "loudness": 0.3, "centroid": 0.35, "steadiness": 0.2,
 *     "novelty": 0.15, "target": 0.0, "refresh": 3 }
 *
 * Any field may be omitted (leave unchanged). "refresh" = inject N random
 * newcomers. A "generation" field is written back by the game as an ack.
 */
public final class GenomeControl {

    /** Legacy canonical path (real mode). Instance ops route via <dataDir>/evolution/control.json (#55). */
    public static final Path CONTROL_FILE = Path.of(
        System.getProperty("user.home"), "AIGEN_SYS", "mindpalace_memory", "evolution", "control.json");

    private final Gson gson = new Gson();

    /** Data dir root; the control file lives under <dataDir>/evolution/. */
    private final Path dir;

    /** Real-mode default: canonical AIGEN_SYS location (compat for bare ctor). */
    public GenomeControl() {
        this(Path.of(System.getProperty("user.home"), "AIGEN_SYS", "mindpalace_memory"));
    }

    /** Hermetic/demo routing (#55): mirrors GenomeArchive - <dataDir>/evolution/. */
    public GenomeControl(Path dataDir) {
        this.dir = dataDir.resolve("evolution");
    }

    /** Read the pending control request, or null if none/absent. */
    public JsonObject read() {
        try {
            if (!Files.exists(dir.resolve("control.json"))) return null;
            String s = Files.readString(dir.resolve("control.json")).trim();
            if (s.isEmpty()) return null;
            return JsonParser.parseString(s).getAsJsonObject();
        } catch (Exception ignored) {
            return null; // malformed → ignore, don't crash the game
        }
    }

    /** Ack a request by writing back the current generation, then clear it. */
    public void ack(int generation) {
        try {
            Files.createDirectories(dir);
            JsonObject o = new JsonObject();
            o.addProperty("generation", generation);
            Files.writeString(dir.resolve("control.json"), gson.toJson(o));
        } catch (Exception ignored) {
        }
    }

    /** Clear the control file (no pending request). */
    public void clear() {
        try { Files.deleteIfExists(dir.resolve("control.json")); } catch (Exception ignored) {}
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
        if (o.has("centroid")) { fit.setCentroidWeight(o.get("centroid").getAsFloat()); sb.append("cent=").append(String.format("%.2f", fit.centroidWeight())).append(' '); }
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
