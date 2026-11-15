package com.mindpalace.agent.team;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PlanCheck — the deterministic checker and judge for "write a short plan" tasks. No model: the same draft always gets
 * the same verdict. A team's bot seat uses check(); the Arena uses score() to pick between teams that passed.
 */
public final class PlanCheck {

    public static final int MAX_CHARS = 1200;
    private static final Pattern STEP = Pattern.compile("(?m)^\\s*\\d+[.)]\\s+\\S");
    private static final Pattern NOT_LOCAL = Pattern.compile("(?i)https?://|\\bopenai\\b|\\banthropic\\b|\\bgemini\\b|\\bcloud\\b|api[ _-]?key");
    private static final Pattern META = Pattern.compile("(?i)would you like|let's continue|as an ai\\b|i can(no|')t\\b");

    private PlanCheck() {}

    public static int steps(String draft) {
        int n = 0;
        Matcher m = STEP.matcher(draft == null ? "" : draft);
        while (m.find()) n++;
        return n;
    }

    /** "PASS", or "FIX: " plus the one thing to change (the drafter reads it next round). */
    public static String check(String draft) {
        if (draft == null || draft.isBlank()) return "FIX: the plan is empty";
        if (draft.length() > MAX_CHARS) return "FIX: too long, keep it under " + MAX_CHARS + " characters";
        int n = steps(draft);
        if (n < 2) return "FIX: write the plan as numbered steps (1. 2. 3.), at least two";
        if (n > 5) return "FIX: at most five steps";
        if (NOT_LOCAL.matcher(draft).find()) return "FIX: local only, no URLs, cloud services or keys";
        if (META.matcher(draft).find()) return "FIX: no questions or meta-talk, only the steps";
        return "PASS";
    }

    /** The bot seat: checks the newest draft from the named drafter. */
    public static Member bot(String drafter) {
        return Member.bot("plan-check", b -> {
            Board.Message d = b.lastFrom(drafter);
            return check(d == null ? null : d.text());
        });
    }

    private static final Pattern WORD = Pattern.compile("[a-z]{5,}");
    // words of the dispatch boilerplate, not of the proposal
    private static final java.util.Set<String> BOILERPLATE = java.util.Set.of("proposal", "write", "numbered", "steps", "carry");

    /** How many different long words of the task the plan uses (0..4): a plan about the task, not about anything. */
    static int taskWordsUsed(String task, String plan) {
        String p = plan == null ? "" : plan.toLowerCase(java.util.Locale.ROOT);
        java.util.Set<String> hit = new java.util.HashSet<>();
        Matcher m = WORD.matcher(task == null ? "" : task.toLowerCase(java.util.Locale.ROOT));
        while (m.find()) if (!BOILERPLATE.contains(m.group()) && p.contains(m.group())) hit.add(m.group());
        return Math.min(4, hit.size());
    }

    /** Judge for the Arena: fewer turns and a tighter plan score higher, and so does a plan that uses the task's own words. 0..115. */
    public static int score(String task, TeamRunner.Result r) {
        return Math.max(0, 100 - 5 * r.turns() - Math.min(24, r.answer().length() / 50) + 5 * taskWordsUsed(task, r.answer()));
    }
}
