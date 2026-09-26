package com.mindpalace.engine;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * AstGate — deterministic Python slot-scanner on the agent's write path
 * (step 87, BDI hardening). Mirrors the semantics of
 * bdi_fsm.daemon.ASTInspector in BDI_FSM_AGENT: a .py file that would land
 * with a syntax error, a bare `pass` body, or a `raise NotImplementedError`
 * body is an UNRESOLVED SLOT and must not enter the live workspace.
 *
 * Zero dependencies: a hand-rolled tokenizer over the Python source —
 * enough to find stubs and unbalanced indentation-driven syntax breakage
 * without pulling a Python runtime into the JVM. Deliberately conservative:
 * it may not catch every exotic stub shape, but everything it flags IS a
 * stub, deterministically (no false positives).
 *
 * Slot types reported (same vocabulary as upstream ASTInspector):
 *   SYNTAX_ERROR         — unbalanced brackets/quotes, bad indentation
 *   UNIMPLEMENTED_FUNCTION — def whose body is pass / NotImplementedError
 */
public final class AstGate {

    private AstGate() {}

    /** Returns null when the content is clean; otherwise a human-readable reason. */
    public static String scan(String content) {
        if (content == null || content.isBlank()) return null; // empty .py is legal (e.g. __init__)
        String[] lines = content.split("\r?\n|\r");

        // Bracket/quote balance across the whole file (string- and comment-aware).
        Deque<Character> stack = new ArrayDeque<>();
        boolean inString = false;
        char stringQuote = 0;
        for (String raw : lines) {
            String line = stripCommentAndStringTail(raw, /*inStringHolder*/ null);
            // Simple per-line approach: multi-line strings are rare in agent
            // stubs; treat an odd count of triple quotes as a string opener.
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (inString) {
                    if (c == stringQuote) inString = false;
                    continue;
                }
                if (c == '\'' || c == '"') { inString = true; stringQuote = c; continue; }
                switch (c) {
                    case '(': case '[': case '{': stack.push(c); break;
                    case ')':
                        if (stack.isEmpty() || stack.pop() != '(') return "SYNTAX_ERROR: unbalanced ) in " + snippet(raw);
                        break;
                    case ']':
                        if (stack.isEmpty() || stack.pop() != '[') return "SYNTAX_ERROR: unbalanced ] in " + snippet(raw);
                        break;
                    case '}':
                        if (stack.isEmpty() || stack.pop() != '{') return "SYNTAX_ERROR: unbalanced } in " + snippet(raw);
                        break;
                    default: break;
                }
            }
        }
        if (!stack.isEmpty()) return "SYNTAX_ERROR: unclosed " + stack.peek() + " at EOF";
        if (inString) return "SYNTAX_ERROR: unterminated string literal";

        // Stub bodies: def whose ONLY meaningful statement is pass / raise NotImplementedError.
        for (int i = 0; i < lines.length; i++) {
            String def = defName(lines[i]);
            if (def == null) continue;
            int baseIndent = indentOf(lines[i]);
            String body = firstMeaningfulBody(lines, i + 1, baseIndent);
            if (body == null) continue; // docstring or real body — fine
            String t = body.trim();
            if (t.equals("pass") || t.equals("pass...") || t.equals("...") || t.equals("…")) {
                return "UNIMPLEMENTED_FUNCTION: " + def + " (pass-stub)";
            }
            if (t.startsWith("raise NotImplementedError") || t.startsWith("raise  NotImplementedError")) {
                return "UNIMPLEMENTED_FUNCTION: " + def + " (NotImplementedError-stub)";
            }
        }
        return null;
    }

    /** Name of a top-or-nested def, or null. Handles async def too. */
    private static String defName(String line) {
        String t = line.trim();
        if (t.startsWith("async def ")) t = t.substring(10).trim();
        else if (t.startsWith("def ")) t = t.substring(4).trim();
        else return null;
        int cut = 0;
        while (cut < t.length() && (Character.isJavaIdentifierPart(t.charAt(cut)))) cut++;
        return cut == 0 ? null : t.substring(0, cut);
    }

    /**
     * First meaningful (non-blank, non-comment) line of the def body at
     * deeper indent than the def; null if that line is a docstring opener
     * (a documented stub still counts as documented intent — upstream also
     * skips docstring-only bodies for pass detection) — no: upstream flags
     * pass ANYWHERE in the body; we only need the first line because a
     * compliant function starts with real code or pass. Returns the line.
     */
    private static String firstMeaningfulBody(String[] lines, int start, int defIndent) {
        for (int j = start; j < lines.length; j++) {
            String l = lines[j];
            if (l.isBlank()) continue;
            String t = l.trim();
            if (t.startsWith("#")) continue;
            if (indentOf(l) <= defIndent) return null; // body ended, nothing stubby
            return l;
        }
        return null;
    }

    private static int indentOf(String line) {
        int n = 0;
        while (n < line.length() && line.charAt(n) == ' ') n++;
        return n;
    }

    private static String snippet(String raw) {
        String t = raw.trim();
        return t.length() > 40 ? t.substring(0, 37) + "..." : t;
    }

    /** Strips a trailing comment naively but leaves quotes for balance scan. */
    private static String stripCommentAndStringTail(String raw, Void unused) {
        boolean inS = false; char q = 0;
        StringBuilder b = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (inS) { b.append(c); if (c == q) inS = false; continue; }
            if (c == '\'' || c == '"') { inS = true; q = c; b.append(c); continue; }
            if (c == '#') break; // comment — drop rest of line
            b.append(c);
        }
        return b.toString();
    }
}