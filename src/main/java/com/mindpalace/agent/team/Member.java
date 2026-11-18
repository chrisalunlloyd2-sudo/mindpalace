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

    /** A TOOLS seat asks the model at most this many times per turn: tool calls, then the answer that uses their results. */
    int MAX_TOOL_ROUNDS = 3;
    /** Tool calls honoured in one reply; the rest get an ERR line. */
    int MAX_TOOL_CALLS = 4;
    /** Longest tool result put on the board. */
    int MAX_TOOL_RESULT = 1500;

    /** The local model call a member makes. Tests pass a fake; the game passes {@link #local} or {@link #onLane}. */
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

    /**
     * The real call: local Ollama only (assertLocalModel refuses a cloud tag before any request). It does not gate itself:
     * run the team on {@link TeamRunner#scheduled}, so every turn goes through the ModelScheduler like any other call.
     */
    static Llm local(com.mindpalace.agent.OllamaClient c) {
        return (model, system, user) -> {
            com.mindpalace.agent.OllamaClient.assertLocalModel(model);
            return c.chat(model, system, user);
        };
    }

    /**
     * The lane call: the local llama.cpp server, with this adapter type's recipe (its LoRA scales) inside the request.
     * This is the REAL adapter swap; the model tag is ignored because the lane holds one base model. packets hears one
     * line per call (recipe and time), for the swap log; it may be null.
     */
    static Llm onLane(com.mindpalace.agent.lane.LaneClient lane, com.mindpalace.agent.lane.AdapterBank bank, AdapterType adapter,
                      int maxTokens, java.util.function.Consumer<String> packets) {
        return (model, system, user) -> {
            // FINDING-K (#405): read the adapter list AFTER the wake. Java evaluates
            // chat()'s arguments BEFORE the call, so an adapters() probe here used to
            // hit a down lane outside chat()'s try — failed() never ran and the
            // breaker could never open. chat() runs life.before (the wake) itself and
            // computes the scales inside the call, after the lane is up; a failure
            // then lands where it is recorded.
            var reply = lane.chatWithRecipe(system, user, () -> bank.vector(adapter, lane.adapters()), maxTokens);
            if (packets != null) packets.accept("[Lane] " + adapter + " recipe " + bank.describe(adapter) + " " + reply.ms() + " ms");
            return reply.text();
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
     * deterministically and the reply gets "= result" under it. An unknown tool returns "= ERR unknown tool". When a reply
     * called tools, the model is asked again with the results in front of it (at most MAX_TOOL_ROUNDS asks per turn), so
     * its final message can use what the tools said. The whole turn is still one job on the lane.
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
            public String act(Board b) {
                String seen = b.transcript();
                String out = "";
                for (int i = 0; i < MAX_TOOL_ROUNDS; i++) {
                    String reply = llm.chat(model, sys, seen);
                    out = runToolLines(reply, ts);
                    if (!callsATool(reply)) break;
                    seen = seen + "[" + name + "] " + out + "\nThe lines starting with = are the tool results. Now give your final answer.\n";
                }
                return out;
            }
        };
    }

    static boolean callsATool(String reply) {
        for (String line : (reply == null ? "" : reply).split("\n", -1)) if (line.trim().startsWith("? ")) return true;
        return false;
    }

    /**
     * Runs every "? tool arg" line of a reply and puts "= result" under it. Pure, so a bot or a test can check it without a
     * model. A line the model wrote that starts with "=" is dropped: only a tool can write a result. At most MAX_TOOL_CALLS
     * calls run, and a result longer than MAX_TOOL_RESULT is cut.
     */
    static String runToolLines(String reply, Map<String, Function<String, String>> ts) {
        StringBuilder out = new StringBuilder();
        int calls = 0;
        for (String line : (reply == null ? "" : reply).split("\n", -1)) {
            String t = line.trim();
            if (t.startsWith("=")) continue;
            out.append(line).append('\n');
            if (!t.startsWith("? ")) continue;
            String[] p = t.substring(2).trim().split("\\s+", 2);
            Function<String, String> f = ts.get(p[0]);
            String r;
            if (++calls > MAX_TOOL_CALLS) r = "ERR too many tool calls in one reply (max " + MAX_TOOL_CALLS + ")";
            else {
                try { r = f == null ? "ERR unknown tool" : String.valueOf(f.apply(p.length > 1 ? p[1] : "")); }
                catch (RuntimeException e) { r = "ERR " + e.getClass().getSimpleName(); }
            }
            if (r.length() > MAX_TOOL_RESULT) r = r.substring(0, MAX_TOOL_RESULT) + " ...(cut)";
            // a result may have several lines: every one is marked, so none can pass for the model's own words
            out.append("= ").append(r.replace("\n", "\n= ")).append('\n');
        }
        return out.toString().stripTrailing();
    }
}
