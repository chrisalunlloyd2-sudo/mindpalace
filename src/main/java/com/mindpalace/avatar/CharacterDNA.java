package com.mindpalace.avatar;

import java.util.Locale;

/**
 * CharacterDNA — the deterministic character generator, ported byte-for-byte from
 * avatar-creator's JS (`src/characters/generator.js` + `src/util/rng.js`).
 *
 * One seed => the SAME character in both the Three.js web model AND this Java
 * engine (the in-game Cortana hologram). Provenance: this is the "one DNA
 * reproduces everywhere" cross-pollinate win.
 *
 * Deterministic end-to-end: FNV-1a hash, mulberry32 PRNG, HSL->hex — all ported to
 * match JS bit-for-bit (Java `int` 32-bit multiply == JS `Math.imul`; `>>> 0` ==
 * `Integer.toUnsignedLong`).
 */
public final class CharacterDNA {

    private CharacterDNA() {}

    // ---------------- RNG + hash (mirrors src/util/rng.js) ----------------

    /** FNV-1a 32-bit (mirrors JS hashString). Returns the unsigned value in [0, 2^32). */
    public static long hashString(String s) {
        int h = 0x811c9dc5; // 2166136261 >>> 0
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h = h * 16777619; // Math.imul(h, 16777619)
        }
        return h & 0xFFFFFFFFL;
    }

    /** mulberry32 — the exact 32-bit stream used by avatar-creator. */
    public static final class Mulberry32 {
        private int a;
        public Mulberry32(long seed) { this.a = (int) (seed & 0xFFFFFFFFL); }
        /** Returns a double in [0, 1). */
        public double next() {
            a += 0x6D2B79F5;
            int t = a;
            t = (a ^ (a >>> 15)) * (a | 1);            // Math.imul
            t = (t + (t ^ (t >>> 7)) * (t | 61)) ^ t;  // Math.imul
            return Integer.toUnsignedLong(t ^ (t >>> 14)) / 4294967296.0;
        }
    }

    /** HSL -> "#rrggbb" (mirrors JS hslToHex). */
    public static String hslToHex(double h, double s, double l) {
        h = ((h % 360) + 360) % 360;
        double c = (1 - Math.abs(2 * l - 1)) * s;
        double x = c * (1 - Math.abs(((h / 60) % 2) - 1));
        double m = l - c / 2;
        double r, g, b;
        if (h < 60)       { r = c; g = x; b = 0; }
        else if (h < 120) { r = x; g = c; b = 0; }
        else if (h < 180) { r = 0; g = c; b = x; }
        else if (h < 240) { r = 0; g = x; b = c; }
        else if (h < 300) { r = x; g = 0; b = c; }
        else              { r = c; g = 0; b = x; }
        return "#" + hexByte(r + m) + hexByte(g + m) + hexByte(b + m);
    }

    private static String hexByte(double v) {
        int n = (int) Math.round(v * 255);
        if (n < 0) n = 0; else if (n > 255) n = 255;
        String s = Integer.toHexString(n);
        return s.length() == 1 ? "0" + s : s;
    }

    /** "#rrggbb" -> float[3] in [0,1] (for mapping into AvatarDescriptor). */
    public static float[] hexToRgb(String hex) {
        int n = 0;
        try { n = (int) Long.parseLong(hex.replace("#", ""), 16); } catch (Exception ignore) {}
        return new float[] { ((n >> 16) & 255) / 255f, ((n >> 8) & 255) / 255f, (n & 255) / 255f };
    }

    // ---------------- generation (mirrors src/characters/generator.js) ----------------

    public static final String[] HAIR_STYLES = { "ponytail", "down", "bun", "bob" };
    public static final String[] OUTFITS = { "sportsbra-yoga", "tshirt-shorts", "tank-skirt", "none" };
    public static final String[] ROLES = { "Agent", "Explorer", "Critic", "Scout", "Archivist", "Foreman" };

    /** A generated character — every field matches the JS `generateCharacter` return. */
    public static final class Character {
        public String id, name, role, sex, description;
        public String skinTone, circuitColor, hairColor, eyeColor, lipColor, clothingTop, clothingBottom;
        public double circuitIntensity, circuitPulse, skinRoughness, skinMetalness, hairLength, eyebrowArch, lipGloss;
        public int bust, waist, hips, height, bodyFat, muscleDef, strandCount;
        public String hairStyle, outfit;
        public Makeup makeup = new Makeup();
    }

    public static final class Makeup {
        public String eyeshadowColor, eyelinerColor, blushColor, lipstickColor;
        public double eyeshadowOpacity, eyelinerThickness, blushIntensity, lipstickOpacity, lipstickGloss;
    }

    public static Character generateCharacter(long seed, String name) {
        Mulberry32 rng = new Mulberry32(hashString(Long.toString(seed)));

        String sex = rng.next() < 0.5 ? "female" : "male";

        double baseHue = rng.next() * 360;
        double skinHue = baseHue;
        double hairHue = (baseHue + 137.5) % 360;
        double eyeHue = (baseHue + 275) % 360;
        double suitHue = (baseHue + 180) % 360;

        double skinSat = range(rng, 0.35, 0.75);
        double skinLight = range(rng, 0.45, 0.7);

        int bust, waist, hips;
        if ("female".equals(sex)) {
            bust = clampInt(rng, 32, 40);
            waist = clampInt(rng, 22, 28);
            hips = clampInt(rng, 33, 42);
        } else {
            bust = clampInt(rng, 36, 44);
            waist = clampInt(rng, 28, 36);
            hips = clampInt(rng, 34, 42);
        }

        String hairStyle = pick(rng, HAIR_STYLES);
        String outfit = pick(rng, OUTFITS);

        Character c = new Character();
        c.id = (name != null) ? name.toLowerCase().replaceAll("[^a-z0-9]+", "-")
                              : "gen-" + Long.toHexString(hashString(Long.toString(seed))).substring(0, 8);
        c.name = (name != null) ? name : ("Agent " + Long.toHexString(hashString(Long.toString(seed))).substring(0, 4).toUpperCase(Locale.US));
        c.role = pick(rng, ROLES);
        c.sex = sex;
        c.description = "Deterministically generated character (seed " + seed + ").";

        c.skinTone = hslToHex(skinHue, skinSat, skinLight);
        c.circuitColor = hslToHex(eyeHue, 0.8, 0.65);
        c.circuitIntensity = round2(range(rng, 0.1, 0.3));
        c.circuitPulse = round2(range(rng, 0.3, 0.7));
        c.skinRoughness = round2(range(rng, 0.35, 0.55));
        c.skinMetalness = round2(range(rng, 0.05, 0.25));

        c.bust = bust; c.waist = waist; c.hips = hips;
        c.height = clampInt(rng, 155, 190);
        c.bodyFat = clampInt(rng, 12, 28);
        c.muscleDef = clampInt(rng, 20, 60);

        c.hairStyle = hairStyle;
        c.hairColor = hslToHex(hairHue, range(rng, 0.4, 0.8), range(rng, 0.25, 0.5));
        c.hairLength = round2(range(rng, 0.25, 0.6));
        c.strandCount = 3000;
        c.eyebrowArch = round2(range(rng, 0.3, 0.8));

        c.eyeColor = hslToHex(eyeHue, 0.85, 0.6);
        c.lipColor = hslToHex((baseHue + 340) % 360, 0.6, 0.6);
        c.lipGloss = round2(range(rng, 0.3, 0.7));

        c.makeup.eyeshadowColor = hslToHex(eyeHue, 0.5, 0.55);
        c.makeup.eyeshadowOpacity = round2(range(rng, 0.15, 0.4));
        c.makeup.eyelinerColor = "#1a1a2e";
        c.makeup.eyelinerThickness = round2(range(rng, 0.4, 0.9));
        c.makeup.blushColor = hslToHex((baseHue + 340) % 360, 0.5, 0.7);
        c.makeup.blushIntensity = round2(range(rng, 0.15, 0.45));
        c.makeup.lipstickColor = hslToHex((baseHue + 340) % 360, 0.6, 0.6);
        c.makeup.lipstickOpacity = 0.8;
        c.makeup.lipstickGloss = 0.5;

        c.outfit = outfit;
        c.clothingTop = hslToHex(suitHue, 0.5, 0.45);
        c.clothingBottom = hslToHex(suitHue, 0.5, 0.3);

        return c;
    }

    // ---------------- helpers (mirror JS) ----------------
    private static double range(Mulberry32 rng, double lo, double hi) { return lo + rng.next() * (hi - lo); }
    private static int clampInt(Mulberry32 rng, int lo, int hi) { return (int) Math.round(range(rng, lo, hi)); }
    private static String pick(Mulberry32 rng, String[] arr) { return arr[(int) Math.floor(rng.next() * arr.length)]; }
    private static double round2(double v) { return Math.round(v * 100) / 100.0; }

    /** Canonical flat string — identical to the JS canonical() used in verification. */
    public static String canonical(Character c) {
        StringBuilder sb = new StringBuilder();
        sb.append(c.id).append('|').append(c.name).append('|').append(c.role).append('|').append(c.sex).append('|');
        sb.append(c.skinTone).append('|').append(c.circuitColor).append('|');
        sb.append(c.circuitIntensity).append('|').append(c.circuitPulse).append('|');
        sb.append(c.skinRoughness).append('|').append(c.skinMetalness).append('|');
        sb.append(c.bust).append('|').append(c.waist).append('|').append(c.hips).append('|');
        sb.append(c.height).append('|').append(c.bodyFat).append('|').append(c.muscleDef).append('|');
        sb.append(c.hairStyle).append('|').append(c.hairColor).append('|');
        sb.append(c.hairLength).append('|').append(c.strandCount).append('|').append(c.eyebrowArch).append('|');
        sb.append(c.eyeColor).append('|').append(c.lipColor).append('|').append(c.lipGloss).append('|');
        sb.append(c.makeup.eyeshadowColor).append('|').append(c.makeup.eyeshadowOpacity).append('|');
        sb.append(c.makeup.eyelinerColor).append('|').append(c.makeup.eyelinerThickness).append('|');
        sb.append(c.makeup.blushColor).append('|').append(c.makeup.blushIntensity).append('|');
        sb.append(c.makeup.lipstickColor).append('|').append(c.makeup.lipstickOpacity).append('|').append(c.makeup.lipstickGloss).append('|');
        sb.append(c.outfit).append('|').append(c.clothingTop).append('|').append(c.clothingBottom);
        return sb.toString();
    }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 1337;
        System.out.println(canonical(generateCharacter(seed, null)));
    }
}
