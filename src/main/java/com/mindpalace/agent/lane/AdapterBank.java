package com.mindpalace.agent.lane;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mindpalace.agent.sims.AdapterType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * AdapterBank — the recipe each AdapterType runs with: which adapters, and how strongly. Kept in a small JSON file so a
 * recipe can be changed, mixed or A/B tested without a rebuild:
 *
 *   {"recipes": {"CODE": {"probe_b": 1.0}, "ANALYSIS": {"probe_a": 0.02, "probe_b": 1.0}}}
 *
 * An adapter is named by its file name without ".gguf". A type with no recipe runs the plain base model (every scale 0).
 * Loading is strict: an unknown type or a scale outside 0..MAX_SCALE refuses the whole file, so a typo cannot quietly
 * turn an adapter off.
 */
public final class AdapterBank {

    public static final double MAX_SCALE = 2.0;

    private final Map<AdapterType, Map<String, Double>> recipes = new EnumMap<>(AdapterType.class);

    private AdapterBank() {}

    public static AdapterBank of(Map<AdapterType, Map<String, Double>> recipes) {
        AdapterBank b = new AdapterBank();
        for (Map.Entry<AdapterType, Map<String, Double>> e : recipes.entrySet()) {
            Map<String, Double> r = new TreeMap<>();
            for (Map.Entry<String, Double> s : e.getValue().entrySet()) {
                double v = s.getValue() == null ? Double.NaN : s.getValue();
                if (!(v >= 0 && v <= MAX_SCALE))
                    throw new IllegalArgumentException("scale for " + s.getKey() + " must be 0.." + MAX_SCALE + ", got " + s.getValue());
                r.put(s.getKey(), v);
            }
            b.recipes.put(e.getKey(), r);
        }
        return b;
    }

    /** A missing file is an empty bank (every type runs the base model). A file that does not parse is refused. */
    public static AdapterBank load(Path file) throws IOException {
        if (file == null || !Files.isRegularFile(file)) return new AdapterBank();
        Map<AdapterType, Map<String, Double>> m = new EnumMap<>(AdapterType.class);
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("recipes").entrySet()) {
                Map<String, Double> r = new LinkedHashMap<>();
                for (Map.Entry<String, JsonElement> s : e.getValue().getAsJsonObject().entrySet()) r.put(s.getKey(), s.getValue().getAsDouble());
                m.put(AdapterType.valueOf(e.getKey()), r);
            }
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("bad adapter bank " + file.getFileName() + ": " + e.getMessage());
        }
        return of(m);
    }

    /** The scale for every adapter the lane has loaded (adapter id -> scale): the recipe's value, 0 for the rest. */
    public Map<Integer, Double> vector(AdapterType type, List<LaneClient.Adapter> loaded) {
        Map<String, Double> r = recipes.getOrDefault(type, Map.of());
        Map<Integer, Double> out = new TreeMap<>();
        int found = 0;
        for (LaneClient.Adapter a : loaded) {
            Double s = r.get(a.name());
            if (s != null) found++;
            out.put(a.id(), s == null ? 0.0 : s);
        }
        // running the base model while the ledger says an adapter was on would be a lie: refuse the turn instead
        if (found != r.size()) throw new IllegalStateException("recipe for " + type + " names an adapter the lane has not loaded: " + r.keySet());
        return out;
    }

    /** "probe_a=0.02,probe_b=1.0", or "base" when the type has no recipe. Goes into the swap log. */
    public String describe(AdapterType type) {
        Map<String, Double> r = recipes.getOrDefault(type, Map.of());
        if (r.isEmpty()) return "base";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Double> e : r.entrySet()) sb.append(sb.length() == 0 ? "" : ",").append(e.getKey()).append('=').append(e.getValue());
        return sb.toString();
    }
}
