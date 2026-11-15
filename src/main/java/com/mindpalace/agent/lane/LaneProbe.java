package com.mindpalace.agent.lane;

import com.google.gson.GsonBuilder;
import com.mindpalace.agent.sims.AdapterType;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * LaneProbe — the proof that a LoRA swap on the lane is real, run through the game's own client. It is the gate the game
 * restart waits on: no lane seat goes live until this passes on the machine.
 *
 *   1. the base model answers the same prompt twice with the same text      (the lane is deterministic)
 *   2. every recipe in the bank gives a text that is NOT the base text      (the adapter really changed the weights)
 *   3. the base model, asked again afterwards, still gives the first text   (a recipe leaves nothing behind)
 *
 *   java -cp ... com.mindpalace.agent.lane.LaneProbe http://127.0.0.1:18080 ~/AIGEN_SYS/lora/bank.json [proof.json]
 *
 * Exit 0 = proven, 1 = a check failed, 2 = the lane is not up or the arguments are wrong.
 */
public final class LaneProbe {

    static final String PROMPT = "Write a plan of 3 numbered steps to repair a broken elevator door.";

    public record Line(String what, String recipe, String sha, long ms, boolean ok, String note) {}

    public record Report(boolean pass, List<Line> lines) {}

    private LaneProbe() {}

    /** True when the newest lane_probe_*.json in dir says pass and is no older than maxAgeMs. No proof, a failed one or a stale one = false. */
    public static boolean freshProof(Path dir, long maxAgeMs, long nowMs) {
        try (java.util.stream.Stream<Path> s = Files.list(dir)) {
            Path newest = s.filter(p -> p.getFileName().toString().matches("lane_probe_\\d{8}_\\d{6}\\.json"))
                .max(java.util.Comparator.comparing(p -> p.getFileName().toString())).orElse(null);
            if (newest == null || nowMs - Files.getLastModifiedTime(newest).toMillis() > maxAgeMs) return false;
            return com.google.gson.JsonParser.parseString(Files.readString(newest, StandardCharsets.UTF_8)).getAsJsonObject().get("pass").getAsBoolean();
        } catch (Exception e) {
            return false;
        }
    }

    public static Report run(LaneClient lane, AdapterBank bank, String prompt, int maxTokens) {
        List<Line> lines = new ArrayList<>();
        List<LaneClient.Adapter> loaded = lane.adapters();
        if (loaded.isEmpty()) return new Report(false, List.of(new Line("adapters", "", "", 0, false, "the lane has no adapter loaded")));
        Map<Integer, Double> off = new TreeMap<>();
        for (LaneClient.Adapter a : loaded) off.put(a.id(), 0.0);

        LaneClient.Reply base = lane.chat("", prompt, off, maxTokens);
        lines.add(new Line("base", "base", sha(base.text()), base.ms(), !base.text().isBlank(), base.text().isBlank() ? "empty answer" : ""));
        LaneClient.Reply again = lane.chat("", prompt, off, maxTokens);
        boolean same = again.text().equals(base.text());
        lines.add(new Line("base again", "base", sha(again.text()), again.ms(), same, same ? "" : "the base model gave two different answers"));

        Map<String, AdapterType> recipes = new LinkedHashMap<>();   // two types with one recipe are one test
        for (AdapterType t : AdapterType.values()) if (!bank.describe(t).equals("base")) recipes.putIfAbsent(bank.describe(t), t);
        if (recipes.isEmpty()) lines.add(new Line("recipes", "", "", 0, false, "the bank has no recipe to prove"));
        for (Map.Entry<String, AdapterType> r : recipes.entrySet()) {
            try {
                LaneClient.Reply got = lane.chat("", prompt, bank.vector(r.getValue(), loaded), maxTokens);
                boolean moved = !got.text().isBlank() && !got.text().equals(base.text());
                lines.add(new Line(r.getValue().name(), r.getKey(), sha(got.text()), got.ms(), moved,
                    moved ? "" : got.text().isBlank() ? "empty answer" : "same text as the base model: the adapter changed nothing"));
            } catch (IllegalStateException e) {
                lines.add(new Line(r.getValue().name(), r.getKey(), "", 0, false, e.getMessage()));
            }
        }

        LaneClient.Reply after = lane.chat("", prompt, off, maxTokens);
        boolean clean = after.text().equals(base.text());
        lines.add(new Line("base after", "base", sha(after.text()), after.ms(), clean, clean ? "" : "a recipe left something behind"));
        return new Report(lines.stream().allMatch(Line::ok), lines);
    }

    static String sha(String text) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: LaneProbe <lane url> <bank.json> [proof.json]");
            System.exit(2);
        }
        LaneClient lane = new LaneClient(args[0]);
        if (!lane.healthy()) {
            System.err.println("LANE PROBE: the lane at " + args[0] + " is not up");
            System.exit(2);
        }
        Report r = run(lane, AdapterBank.load(Paths.get(args[1])), PROMPT, Integer.getInteger("mindpalace.lane.probeTokens", 48));
        for (Line l : r.lines())
            System.out.printf("%-4s %-10s %-28s %s %6d ms %s%n", l.ok() ? "PASS" : "FAIL", l.what(), l.recipe(), l.sha(), l.ms(), l.note());
        System.out.println(r.pass() ? "LANE PROBE PASS: the swap is real" : "LANE PROBE FAIL");
        if (args.length > 2) {
            Path out = Paths.get(args[2]);
            Files.writeString(out, new GsonBuilder().setPrettyPrinting().create().toJson(r), StandardCharsets.UTF_8);
        }
        System.exit(r.pass() ? 0 : 1);
    }
}
