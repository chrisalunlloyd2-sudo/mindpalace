package com.mindpalace.agent;

import java.util.*;

/**
 * LexicalAnalyzer — deterministic term-frequency vectors over chat-log text.
 *
 * "Lexical" (not neural): tokenize → lowercase → strip stopwords → count term
 * frequencies → normalize to a unit vector. Cosine similarity between two
 * vectors measures topical overlap. This is the cheap, deterministic bridge
 * between the chat logs and the quorum voting schema — no embedding model, no
 * Ollama call, runs in microseconds.
 *
 * The user's spec: "extract lexical vectors to quorum voting" — i.e. turn the
 * words the agents actually said into a vector space, then let the quorum vote
 * on the dominant topics those vectors surface.
 */
public final class LexicalAnalyzer {

    private LexicalAnalyzer() {}

    /** English stopwords — the glue words that carry no topical signal. */
    private static final Set<String> STOPWORDS = Set.of(
        "the","a","an","and","or","but","if","then","else","for","while",
        "to","of","in","on","at","by","with","from","as","is","are","was","were",
        "be","been","being","have","has","had","does","did","will","would",
        "shall","should","can","could","may","might","must","it","its","this",
        "that","these","those","i","you","he","she","we","they","them","his",
        "her","their","our","your","my","me","us","not","no","yes","so","very",
        "too","just","about","into","over","under","again","once","here","there",
        "what","which","who","whom","when","where","why","how","all","any","both",
        "each","few","more","most","other","some","such","only","own","same",
        "than","also","because","until","during","before","after",
        "above","below","between","through","against","out","up","down","off",
        "am","im","youre","hes","shes","theyre","ive","youve",
        "dont","cant","wont","couldnt","shouldnt","wouldnt","isnt","arent",
        "wasnt","werent","hasnt","havent","hadnt","doesnt","didnt"
    );

    /** A normalized term-frequency vector: term → weight (0..1). */
    public static final class Vector {
        public final Map<String, Float> terms;   // term → normalized weight
        public final int rawTokenCount;

        Vector(Map<String, Float> terms, int rawTokenCount) {
            this.terms = terms;
            this.rawTokenCount = rawTokenCount;
        }

        public int size() { return terms.size(); }
        public boolean isEmpty() { return terms.isEmpty(); }

        /** Top-k terms by weight, descending. */
        public List<Map.Entry<String, Float>> top(int k) {
            List<Map.Entry<String, Float>> e = new ArrayList<>(terms.entrySet());
            e.sort((a, b) -> Float.compare(b.getValue(), a.getValue()));
            return e.subList(0, Math.min(k, e.size()));
        }

        @Override public String toString() {
            StringBuilder sb = new StringBuilder("Vector[");
            for (Map.Entry<String, Float> e : top(5)) {
                sb.append(e.getKey()).append(':').append(String.format("%.2f", e.getValue())).append(' ');
            }
            return sb.append(']').toString();
        }
    }

    /** Tokenize a string into lowercase, stopword-stripped terms. */
    public static List<String> tokenize(String text) {
        if (text == null || text.isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        // Split on non-alphanumeric (keep apostrophes inside words)
        for (String raw : text.toLowerCase().split("[^a-z0-9']+")) {
            String t = raw.replaceAll("'", "").trim();
            if (t.isEmpty() || t.length() < 2) continue;      // drop 1-char noise
            if (STOPWORDS.contains(t)) continue;
            out.add(t);
        }
        return out;
    }

    /** Build a normalized term-frequency vector from text. */
    public static Vector vectorize(String text) {
        List<String> tokens = tokenize(text);
        if (tokens.isEmpty()) return new Vector(Map.of(), 0);

        Map<String, Integer> counts = new HashMap<>();
        for (String t : tokens) counts.merge(t, 1, Integer::sum);

        // Normalize: weight = count / maxCount (relative, not absolute — so a
        // short message and a long one with the same topic align).
        int max = counts.values().stream().mapToInt(Integer::intValue).max().orElse(1);
        Map<String, Float> norm = new HashMap<>();
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            norm.put(e.getKey(), e.getValue() / (float) max);
        }
        return new Vector(norm, tokens.size());
    }

    /** Cosine similarity between two lexical vectors (0..1). */
    public static float cosine(Vector a, Vector b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return 0f;
        float dot = 0f;
        // Iterate the smaller map for speed
        Map<String, Float> small = a.terms.size() <= b.terms.size() ? a.terms : b.terms;
        Map<String, Float> large = small == a.terms ? b.terms : a.terms;
        for (Map.Entry<String, Float> e : small.entrySet()) {
            Float w = large.get(e.getKey());
            if (w != null) dot += e.getValue() * w;
        }
        float na = norm(a), nb = norm(b);
        if (na == 0f || nb == 0f) return 0f;
        return dot / (float) (Math.sqrt(na) * Math.sqrt(nb));
    }

    private static float norm(Vector v) {
        float s = 0f;
        for (float w : v.terms.values()) s += w * w;
        return s;
    }

    /**
     * Extract the dominant topic from a batch of messages: merge all tokens,
     * return the top-k terms as a single "topic signature" string. This is the
     * input to a quorum proposal — the words the agents are actually circling.
     */
    public static String dominantTopic(List<String> messages, int k) {
        if (messages == null || messages.isEmpty()) return "";
        StringBuilder all = new StringBuilder();
        for (String m : messages) all.append(m).append(' ');
        Vector v = vectorize(all.toString());
        if (v.isEmpty()) return "";
        StringBuilder topic = new StringBuilder();
        for (Map.Entry<String, Float> e : v.top(k)) {
            topic.append(e.getKey()).append(' ');
        }
        return topic.toString().trim();
    }

    /**
     * Cluster messages into topical groups by cosine similarity (greedy, single
     * pass). Returns a list of clusters, each a list of message indices. Used to
     * find the distinct threads in a chat log so each can become its own
     * quorum proposal instead of one giant blob.
     */
    public static List<List<Integer>> cluster(List<String> messages, float threshold) {
        List<List<Integer>> clusters = new ArrayList<>();
        List<Vector> vecs = new ArrayList<>();
        for (String m : messages) vecs.add(vectorize(m));

        for (int i = 0; i < vecs.size(); i++) {
            if (vecs.get(i).isEmpty()) continue;
            int best = -1;
            float bestSim = 0f;
            for (int c = 0; c < clusters.size(); c++) {
                // Compare against the cluster's centroid (first member's vector)
                float sim = cosine(vecs.get(i), vecs.get(clusters.get(c).get(0)));
                if (sim > bestSim) { bestSim = sim; best = c; }
            }
            if (best >= 0 && bestSim >= threshold) {
                clusters.get(best).add(i);
            } else {
                List<Integer> nc = new ArrayList<>();
                nc.add(i);
                clusters.add(nc);
            }
        }
        return clusters;
    }
}
