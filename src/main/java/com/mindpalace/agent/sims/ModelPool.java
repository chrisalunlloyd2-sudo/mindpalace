package com.mindpalace.agent.sims;

import java.util.*;

/**
 * ModelPool — the 4-tier model registry, ported from SIMS1337
 * (repos/SIMS1337 routing/ModelPool.java), adapted to MindPalace.
 *
 * <p>Adaptation contract (per docs/PARITY_BASELINE_SIMS1337.md §2/§4,
 * flagged per task t_8452f017):
 * <ul>
 *   <li>MindPalace keeps model identities as Strings and leaves residency to
 *       Ollama itself (one-hot keep-alive in OllamaClient) plus ModelLifespan
 *       RAM fencing — SIMS1337's in-JVM warm {@code SLMAgent} pool and its
 *       warmUp() probe (a real inference call) are deliberately NOT ported:
 *       a per-tier warmUp would violate MindPalace's 5-min model spacing gate
 *       (ModelScheduler.MIN_SPACING_MS). Availability is therefore read from
 *       {@code OllamaClient.listModels()} — the same installed-models snapshot
 *       the baseline doc records as "ollama_models_loaded: 11" — not from
 *       traffic.</li>
 *   <li>SPEC DEVIATION (SIMS1337 bug, not replicated): in the SIMS1337
 *       source, {@code initializeModels()} stores tiers under keys
 *       {@code fast_primary/balanced_primary/reasoning_primary/deep_primary}
 *       but every getter reads {@code qwen2_0_5b/tinyllama_1_1b/phi_latest/
 *       phi3_mini} — all four getters returned null and its own
 *       ModelPoolTest (asserting non-null) can never have passed; JaCoCo
 *       records routing.ModelPool at 0% coverage. MindPalace's port keys the
 *       pool by tier id ({@code fast..}, {@code balanced..}, …) so getters
 *       and puts agree. Flagged here rather than silently deviated.</li>
 * </ul>
 *
 * <p>Tier ladder (SIMS1337 sizes/latencies kept for reference):
 * <pre>
 *   fast      qwen2.5:0.5b   398 MB  <100 ms
 *   balanced  tinyllama:1.1b 638 MB  ~500 ms
 *   reasoning phi:latest     1.6 GB  2–5 s
 *   deep      phi3:mini      2.2 GB  5–10 s
 * </pre>
 */
public class ModelPool {

    /** One tier: canonical id, primary model, and the ordered fallback ladder. */
    public static final class Tier {
        public final String id;
        public final String primary;
        /** Degradation ladder: primary first, then progressively smaller models. */
        public final List<String> fallbacks;

        Tier(String id, String primary, List<String> fallbacks) {
            this.id = id;
            this.primary = primary;
            this.fallbacks = fallbacks;
        }

        /** The full candidate order for this tier: primary then fallbacks. */
        public List<String> candidates() {
            List<String> out = new ArrayList<>();
            out.add(primary);
            out.addAll(fallbacks);
            return out;
        }
    }

    private final Map<String, Tier> tiers = new LinkedHashMap<>();
    /** Available models as of the last probe (null = never probed / offline). */
    private volatile Set<String> available;

    public ModelPool() {
        // fast: primary + nothing smaller exists; balanced degrades to fast, etc.
        tiers.put("fast", new Tier("fast", ModelRouter.FAST,
            List.of()));
        tiers.put("balanced", new Tier("balanced", ModelRouter.BALANCED,
            List.of(ModelRouter.FAST)));
        tiers.put("reasoning", new Tier("reasoning", ModelRouter.REASONING,
            List.of(ModelRouter.BALANCED, ModelRouter.FAST)));
        tiers.put("deep", new Tier("deep", ModelRouter.DEEP,
            List.of(ModelRouter.REASONING, ModelRouter.BALANCED, ModelRouter.FAST)));
    }

    // ── Membership (SIMS1337 getters, renamed to tier ids — see header). ──

    public Tier tier(String id) { return tiers.get(id); }
    public String getModel(String tierId) {
        Tier t = tiers.get(tierId);
        return t != null ? t.primary : null;
    }

    /** The tier the router maps a complexity level to. */
    public Tier tierFor(Complexity c) {
        switch (c) {
            case VERY_LOW:
            case LOW:      return tiers.get("fast");
            case MEDIUM:   return tiers.get("balanced");
            case HIGH:     return tiers.get("reasoning");
            case VERY_HIGH:
            case CRITICAL: return tiers.get("deep");
            default:       return tiers.get("balanced");
        }
    }

    /** Tier count — always 4 (fast, balanced, reasoning, deep), like SIMS1337. */
    public int getTierCount() { return tiers.size(); }

    /** All tiers in laddering order (fast → deep). Read-only view. */
    public Collection<Tier> getAllTiers() {
        return Collections.unmodifiableCollection(tiers.values());
    }

    // ── Availability + fallback (SIMS1337 tryModel semantics, probe-free). ──

    /**
     * Probe which of the pool's models are installed. One Ollama HTTP list
     * call (no inference, no model load), so it respects the spacing gate.
     * Returns the snapshot; empty means Ollama is unreachable.
     */
    public Set<String> probeAvailable(com.mindpalace.agent.OllamaClient ollama) {
        Set<String> found = new LinkedHashSet<>();
        try {
            List<String> installed = ollama.listModels();
            for (String name : installed) {
                // Normalize: strip everything after the first whitespace; pool
                // entries are plain "model:tag" names and Ollama reports the
                // same form (never a digest on /api/tags).
                String base = name.trim();
                int sp = base.indexOf(' ');
                if (sp > 0) base = base.substring(0, sp);
                found.add(base);
            }
        } catch (RuntimeException e) {
            // Offline / malformed — leave whatever we found (possibly empty).
        }
        this.available = found;
        return found;
    }

    /**
     * The last probed availability snapshot (null = never probed).
     * Router logic treats null as "assume everything present" — the historical
     * behaviour — while an empty set means a probe ran and found Ollama down.
     */
    public Set<String> getAvailable() { return available; }

    /**
     * Resolve the model a request should use for a complexity level:
     * the tier's primary when available, else the first candidate on the
     * tier's fallback ladder that is available, else FAST (SIMS1337's
     * "fallback to smallest known model"). When nothing has been probed
     * (available == null, e.g. Ollama offline at startup), primaries are
     * returned unchanged — routing decisions stay deterministic offline.
     */
    public String selectAvailable(Complexity c) {
        Set<String> avail = available;
        Tier t = tierFor(c);
        if (avail == null) return t.primary;
        for (String candidate : t.candidates()) {
            if (avail.contains(candidate)) return candidate;
        }
        return ModelRouter.FAST; // smallest known model — SIMS1337 parity
    }

    /** True when every tier's primary is in the last availability snapshot. */
    public boolean allPrimariesAvailable() {
        Set<String> avail = available;
        if (avail == null) return false; // never probed — cannot claim all warm
        for (Tier t : tiers.values()) {
            if (!avail.contains(t.primary)) return false;
        }
        return true;
    }

    /** True when at least one tier primary is available (the router can work). */
    public boolean anyPrimaryAvailable() {
        Set<String> avail = available;
        if (avail == null) return true; // unprobed → assume usable (legacy path)
        for (Tier t : tiers.values()) {
            if (avail.contains(t.primary)) return true;
        }
        return false;
    }

    /**
     * Self-test hook: inject an availability snapshot without touching Ollama.
     * Mirrors what probeAvailable() would record, deterministically — the
     * fallback ladder is pure logic over this set, so the suite can drive it
     * offline (GameEngine self-test check 19b).
     */
    public void probeAvailableSnapshotForSelfTest(Set<String> installed) {
        this.available = new LinkedHashSet<>(installed);
    }
}
