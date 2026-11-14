package com.mindpalace.agent.team;

import com.mindpalace.agent.sims.AdapterType;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Member — one seat on a team: an LLM, a deterministic bot, or an LLM with a toolset. Members take turns (never two at
 * once), so only one model is resident at a time and the runner swaps the LoRA adapter to the member's own on its turn.
 */
public interface Member {

    enum Kind { LLM, BOT, TOOLS }

    /** The local model call a member makes. Tests pass a fake; the game passes {@link #ollama()}. */
    @FunctionalInterface
    interface Llm { String chat(String model, String system, String user); }

    String name();
    Kind kind();
    /** The adapter this member wants on its turn; null for a bot (a bot loads no model). */
    AdapterType adapter();
    /** The model tag; null for a bot. */
    String model();
    /** Take one turn: read the board, return one message. */
    String act(Board board);

    /** The real call: local Ollama only (assertLocalModel refuses a cloud tag before any request). */
    static Llm ollama() {
        com.mindpalace.agent.OllamaClient c = new com.mindpalace.agent.OllamaClient();
        return (model, system, user) -> {
            com.mindpalace.agent.OllamaClient.assertLocalModel(model);
            return c.chat(model, system, user);
        };
    }

    static Member llm(String name, String model, AdapterType adapter, String system, Llm llm) {
        return new Member() {
            public String name() { return name; }
            public Kind kind() { return Kind.LLM; }
            public AdapterType adapter() { return adapter; }
            public String model() { return model; }
            public String act(Board b) { return llm.chat(model, system, b.transcript()); }
        };
    }

    static Member bot(String name, Function<Board, String> rule) {
        return new Member() {
            public String name() { return name; }
            public Kind kind() { return Kind.BOT; }
            public AdapterType adapter() { return null; }
            public String model() { return null; }
            public String act(Board b) { return rule.apply(b); }
        };
    }

    /**
     * An LLM plus a toolset, using the ? tool loop: each line of the model's reply that reads "? tool arg" runs that tool
     * deterministically and the reply gets "= result" under it. An unknown tool returns "= ERR unknown tool".
     */
    static Member tools(String name, String model, AdapterType adapter, String system, Llm llm,
                        Map<String, Function<String, String>> toolset) {
        Map<String, Function<String, String>> ts = new LinkedHashMap<>(toolset);
        String sys = system + "\nTools (one per line as '? name arg'): " + String.join(", ", ts.keySet());
        return new Member() {
            public String name() { return name; }
            public Kind kind() { return Kind.TOOLS; }
            public AdapterType adapter() { return adapter; }
            public String model() { return model; }
            public String act(Board b) { return runToolLines(llm.chat(model, sys, b.transcript()), ts); }
        };
    }

    /** Runs every "? tool arg" line of a reply and puts "= result" under it. Pure, so a bot or a test can check it without a model. */
    static String runToolLines(String reply, Map<String, Function<String, String>> ts) {
        StringBuilder out = new StringBuilder();
        for (String line : (reply == null ? "" : reply).split("\n", -1)) {
            out.append(line).append('\n');
            String t = line.trim();
            if (!t.startsWith("? ")) continue;
            String[] p = t.substring(2).trim().split("\\s+", 2);
            Function<String, String> f = ts.get(p[0]);
            String r;
            try { r = f == null ? "ERR unknown tool" : String.valueOf(f.apply(p.length > 1 ? p[1] : "")); }
            catch (RuntimeException e) { r = "ERR " + e.getClass().getSimpleName(); }
            out.append("= ").append(r).append('\n');
        }
        return out.toString().stripTrailing();
    }
}
