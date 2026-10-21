package com.mindpalace.github;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * #137 (H47, step 106): the dedupe sentinel — a static scan that detects
 * DUPLICATED utility code across the player's repos.
 *
 * Why: #96/#97 were exactly the bugs this tool would have caught
 * automatically — AgentRewardMechanism duplicating economy/Wallet, and a
 * commit claiming deliverables that never landed. Both needed a manual
 * oversight pass. This sentinel makes duplication loud.
 *
 * Design (KISS, hermetic):
 *   - Pure static core: normalized 5-token shingles + Jaccard similarity.
 *     No I/O, no network — deterministic and ms-fast (fits the selftest).
 *   - Method-body extraction: brace-block scan from each signature line,
 *     normalized (identifiers → 'x', whitespace collapsed) so two copies
 *     of the same logic hash the same regardless of local names.
 *   - Pairs are flagged only across DIFFERENT files (copy-paste between
 *     files is the bug; private same-file helpers are idiomatic).
 *   - raise path: GitHubIssueStream.raise() — already paced (30s) +
 *     per-repo capped (#103), so scanning N repos files issues at a
 *     bounded, non-flooding rate. One issue per pair, add-only.
 */
public final class DedupeSentinel {

    private DedupeSentinel() { }

    /** Similarity threshold above which a pair is reported. */
    public static final double THRESHOLD = 0.85;

    /** Shingle width in normalized tokens. */
    static final int SHINGLE = 5;

    /** One flagged duplicate pair. */
    public record Pair(String repoA, String pathA, String sigA,
                       String repoB, String pathB, String sigB,
                       double similarity) {
        @Override public String toString() {
            return String.format(Locale.ROOT,
                "%.0f%% %s:%s::%s ~ %s:%s::%s",
                similarity * 100, repoA, pathA, sigA, repoB, pathB, sigB);
        }
    }

    /** One scanned method (a "unit" — comparable chunk of code). */
    record Unit(String repo, String path, String sig, Set<String> shingles) { }

    // ── normalization ───────────────────────────────────────────────────

    /**
     * Normalize source text for comparison: strip comments, collapse all
     * whitespace to single spaces, lowercase, and replace identifiers with
     * type-neutral 'x' EXCEPT keywords/numbers (so shape survives local
     * renaming, but string literals and keywords still differentiate).
     */
    static String normalize(String src) {
        if (src == null) return "";
        // strip // line comments and /* block */ comments (crude but stable)
        String s = src.replaceAll("//[^\n]*", " ")
                      .replaceAll("(?s)/\\*.*?\\*/", " ");
        StringBuilder out = new StringBuilder(s.length());
        boolean inStr = false;
        char q = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                if (c == q) inStr = false; // keep literal marker below
                else if (c == '\\' && i + 1 < s.length()) i++; // skip escape
                continue; // drop literal contents (shape-only comparison)
            }
            if (c == '"' || c == '\'') { inStr = true; q = c; continue; }
            out.append(c);
        }
        // whitespace → single space, lowercase
        String collapsed = out.toString().toLowerCase(Locale.ROOT)
            .replaceAll("[\\s]+", " ").trim();
        // identifiers → 'x' (keywords and numbers keep their shape)
        String[] parts = collapsed.split(" ");
        StringBuilder toks = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (isKeywordOrNumber(p)) toks.append(p).append(' ');
            else toks.append("x ");
        }
        return toks.toString().trim();
    }

    private static final Set<String> KEYWORDS = Set.of(
        "if", "else", "for", "while", "do", "return", "switch", "case",
        "break", "continue", "try", "catch", "finally", "throw", "throws",
        "new", "null", "true", "false", "this", "super", "instanceof",
        "int", "long", "double", "float", "boolean", "char", "byte",
        "short", "void", "static", "final", "public", "private", "protected",
        "class", "interface", "enum", "extends", "implements", "import",
        "package", "abstract", "synchronized", "volatile", "transient");

    /** A keyword, or a pure number (shape-bearing tokens). */
    private static boolean isKeywordOrNumber(String tok) {
        String bare = tok.replaceAll("[^A-Za-z0-9_.]", "");
        if (bare.matches("[0-9]+(\\.[0-9]+)?[lLfFdD]?")) return true;
        return KEYWORDS.contains(bare.replaceAll("[^a-z]", ""));
    }

    /** Token-shingles (width {@link #SHINGLE}) as a set — Jaccard input. */
    static Set<String> shingles(String normalized) {
        Set<String> out = new HashSet<>();
        if (normalized == null || normalized.isEmpty()) return out;
        String[] toks = normalized.split(" ");
        if (toks.length < SHINGLE) {
            out.add(normalized); // short block: whole text is one shingle
            return out;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i + SHINGLE <= toks.length; i++) {
            sb.setLength(0);
            for (int j = 0; j < SHINGLE; j++) {
                if (j > 0) sb.append(' ');
                sb.append(toks[i + j]);
            }
            out.add(sb.toString());
        }
        return out;
    }

    /** Jaccard similarity of two shingle sets: |A∩B| / |A∪B| ∈ [0,1]. */
    public static double similarity(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0.0;
        Set<String> small = a.size() <= b.size() ? a : b;
        Set<String> big = a.size() <= b.size() ? b : a;
        int inter = 0;
        for (String s : small) if (big.contains(s)) inter++;
        int union = a.size() + b.size() - inter;
        return union == 0 ? 0.0 : (double) inter / union;
    }

    // ── method-body extraction ──────────────────────────────────────────

    /**
     * Extract top-level (4-space-or-tab-indented, i.e. class-member) method
     * bodies from Java-ish source as (signature, body) pairs. Deliberately
     * crude and language-tolerant — it only needs STABLE shapes, not a real
     * parser: a line that looks like a signature followed by a balanced
     * brace block.
     */
    static List<Map.Entry<String, String>> extractMethods(String src) {
        List<Map.Entry<String, String>> out = new ArrayList<>();
        if (src == null || src.isEmpty()) return out;
        String[] lines = src.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (!looksLikeSignature(trimmed)) continue;
            // find the opening brace on this or a following line
            int braceLine = i;
            while (braceLine < lines.length
                && !lines[braceLine].contains("{")) braceLine++;
            if (braceLine >= lines.length) continue;
            // balanced-brace scan from the opening '{'
            int depth = 0;
            StringBuilder body = new StringBuilder();
            boolean opened = false;
            int end = braceLine;
            scan:
            for (int j = braceLine; j < lines.length; j++) {
                for (char c : lines[j].toCharArray()) {
                    if (c == '{') { depth++; opened = true; }
                    else if (c == '}') { depth--; }
                    if (opened && depth == 0) { end = j; break scan; }
                }
                body.append(lines[j]).append('\n');
                end = j;
            }
            if (!opened) continue; // signature without body (abstract/ifc)
            out.add(Map.entry(trimmed, body.toString()));
            i = end; // skip past this method
        }
        return out;
    }

    /** Heuristic: a class-member method signature line (not control flow). */
    private static boolean looksLikeSignature(String line) {
        if (line.isEmpty() || line.length() > 240) return false;
        if (line.startsWith("@") || line.startsWith("//")) return false;
        // control flow must not count as a method head
        for (String kw : new String[]{"if ", "for ", "while ", "switch ",
            "catch ", "else", "try ", "do ", "//"})
            if (line.startsWith(kw)) return false;
        boolean hasParens = line.contains("(") && line.contains(")");
        // class/enum/interface heads are not methods
        boolean typeHead = line.startsWith("class ") || line.startsWith("enum ")
            || line.startsWith("interface ") || line.startsWith("record ");
        return hasParens && !typeHead;
    }

    // ── the scan ────────────────────────────────────────────────────────

    /**
     * Scan multiple repos' file contents for duplicated method bodies.
     * Caller supplies ALREADY-FETCHED contents (pure function — no network
     * inside; network is in the engine runner below).
     *
     * @param files map: repo → (path → source text). Null/empty sources skip.
     * @return flagged pairs (different files, similarity ≥ THRESHOLD)
     */
    public static List<Pair> scan(Map<String, Map<String, String>> files) {
        List<Unit> units = new ArrayList<>();
        for (Map.Entry<String, Map<String, String>> e : files.entrySet()) {
            String repo = e.getKey();
            if (e.getValue() == null) continue;
            for (Map.Entry<String, String> f : e.getValue().entrySet()) {
                String path = f.getKey();
                String src = f.getValue();
                if (src == null || src.length() < 80) continue; // trivia
                for (Map.Entry<String, String> m : extractMethods(src)) {
                    String norm = normalize(m.getValue());
                    if (norm.split(" ").length < SHINGLE) continue; // too thin
                    units.add(new Unit(repo, path, m.getKey(), shingles(norm)));
                }
            }
        }
        List<Pair> out = new ArrayList<>();
        // O(n²) pairwise — bounded by cap below; repos are small (per-repo
        // method counts in the low hundreds → ≤ ~50k pairs, ms-fast).
        for (int i = 0; i < units.size(); i++) {
            Unit a = units.get(i);
            if (out.size() >= 64) break; // hard cap: sentinel reports ≤64/passes
            boolean selfFile = false; // pairs only across DIFFERENT files
            for (int j = i + 1; j < units.size(); j++) {
                Unit b = units.get(j);
                if (a.repo().equals(b.repo()) && a.path().equals(b.path())) {
                    selfFile = true; continue; // skip intra-file pairs
                }
                double sim = similarity(a.shingles(), b.shingles());
                if (sim >= THRESHOLD) {
                    out.add(new Pair(a.repo(), a.path(), a.sig(),
                        b.repo(), b.path(), b.sig(), sim));
                    if (out.size() >= 64) break;
                }
            }
            if (selfFile && out.size() >= 64) break; // cap check
        }
        return out;
    }

    /** The gate-friendly convenience: similarity of two raw snippets. */
    public static double snippetSimilarity(String a, String b) {
        return similarity(shingles(normalize(a)), shingles(normalize(b)));
    }
}