package com.mindpalace.agent;

import java.util.List;
import java.util.Locale;

/**
 * What the autonomous tool loop may touch. Deterministic, deny by default, and checked BEFORE the quorum vote:
 * models vote on whether a change is good, these rules decide whether it is allowed at all.
 *
 * The rules live in code on purpose. A rules file in the repo would be a file the agents could propose to edit, and
 * this class is itself off limits to them (rule GATES). Each refusal goes back to the model as its tool result, so the
 * text says what was refused, why, and what to do instead: a descriptive rule teaches, a bare "denied" gets retried.
 *
 * Limit: this stops the tool loop inside the game. It cannot stop other code in the same JVM from using the token, so
 * the token handed to the game is the real outer wall and should be fine-grained, scoped to this one repo.
 */
public final class AgentWriteRules {

    /** The only repo agents work in. */
    public static final String REPO = "mindpalace";

    private AgentWriteRules() {}

    /** A rule's verdict. allowed == false carries the rule id and the text the model reads. */
    public record Verdict(boolean allowed, String rule, String reason) {
        static final Verdict OK = new Verdict(true, "", "");
        static Verdict no(String rule, String reason) { return new Verdict(false, rule, reason); }
        /** The tool result for a refusal. */
        public String message(String verb, String path) {
            return verb + " refused by rule " + rule + ": " + path + ". " + reason;
        }
    }

    /** Where agents may write. Everything else is refused, including paths nobody thought of. */
    static final List<String> WRITE_ROOTS = List.of("src/main/java/", "src/main/resources/", "docs/", "drafts/");

    /** The gates themselves: an agent that could edit these could remove them. Lower case. */
    static final List<String> GATE_FILES = List.of(
        "src/main/java/com/mindpalace/agent/agentwriterules.java",
        "src/main/java/com/mindpalace/agent/agentmanager.java",
        "src/main/java/com/mindpalace/engine/gameengine.java");
    static final List<String> GATE_DIRS = List.of("src/main/java/com/mindpalace/github/");
    /** Any file named like this inside an allowed root is still a gate. Lower case. */
    static final List<String> GATE_NAME_PARTS = List.of("quorum");

    /** File types the box or CI would run directly, wherever they sit. */
    static final List<String> RUNNABLE_ENDINGS = List.of(
        ".sh", ".bash", ".ps1", ".psm1", ".bat", ".cmd", ".exe", ".dll", ".jar", ".py", ".vbs", ".lnk", ".iss", ".yml", ".yaml");

    static final List<String> SECRET_NAME_PARTS = List.of(
        "secret", "credential", "password", "passwd", "token", ".env", ".pem", ".key", ".p12", ".pfx", "id_rsa", "id_ed25519");

    /** Rule REPO: applies to reads and writes. */
    public static Verdict checkRepo(String repo) {
        if (REPO.equals(repo)) return Verdict.OK;
        return Verdict.no("REPO", "Agents work only in the " + REPO + " repo; this room belongs to '" + repo
            + "'. Leave this room alone and take a task from a " + REPO + " room.");
    }

    /** Reads: the repo rule, a sane path, no secrets. */
    public static Verdict checkRead(String repo, String path) {
        Verdict v = checkRepo(repo);
        if (!v.allowed()) return v;
        String p = normalize(path);
        if (p == null) return badPath();
        return checkSecret(p);
    }

    /** Writes (edit, create, delete): every rule, in order. The first refusal wins. */
    public static Verdict checkWrite(String repo, String path) {
        Verdict v = checkRepo(repo);
        if (!v.allowed()) return v;
        String p = normalize(path);
        if (p == null) return badPath();
        v = checkSecret(p);
        if (!v.allowed()) return v;
        if (p.startsWith(".") || p.contains("/.")) {
            return Verdict.no("HIDDEN", "Dot files and dot folders hold CI, git and tool settings: .github workflows run"
                + " with the repo's secrets, .gitattributes changes how files are read. Agents do not edit them."
                + " Describe the change you need in a TODO instead.");
        }
        for (String ending : RUNNABLE_ENDINGS) {
            if (p.endsWith(ending)) {
                return Verdict.no("RUNNABLE", "A " + ending + " file can run on the box or in CI without passing the"
                    + " game's selftest, so agents do not write them. Write the logic in Java under src/main/java,"
                    + " where the selftest covers it.");
            }
        }
        if (GATE_FILES.contains(p) || GATE_DIRS.stream().anyMatch(p::startsWith)
                || (p.startsWith("src/") && GATE_NAME_PARTS.stream().anyMatch(fileName(p)::contains))) {
            return Verdict.no("GATES", "This file is part of what checks agent work (these rules, the tool loop, the"
                + " selftest, the GitHub client or the quorum). A gate cannot be changed by the work it gates."
                + " Describe the change you want in a TODO and it will be reviewed outside the loop.");
        }
        for (String root : WRITE_ROOTS) {
            if (p.startsWith(root)) return Verdict.OK;
        }
        return Verdict.no("ROOTS", "Agents write only under " + String.join(", ", WRITE_ROOTS)
            + ". Build files, scripts, tools, and top-level docs like AGENTS.md set the rules or run on the box."
            + " Put your change under one of those folders, or describe it in a TODO.");
    }

    private static Verdict checkSecret(String p) {
        String name = fileName(p);
        for (String part : SECRET_NAME_PARTS) {
            if (name.contains(part)) {
                return Verdict.no("SECRETS", "The name of this file suggests a secret ('" + part + "'). Agents neither"
                    + " read nor write secrets, and their tool output is logged. Do not retry under another name.");
            }
        }
        return Verdict.OK;
    }

    private static Verdict badPath() {
        return Verdict.no("PATH", "Use a plain path relative to the repo root with forward slashes, such as"
            + " src/main/java/com/mindpalace/world/Room.java. No '..', drive letters, ':' streams, or names ending"
            + " in a dot or space.");
    }

    /**
     * PURE. The canonical lower-case form every rule compares, or null when the path is not a plain repo-relative path.
     * Windows treats names case-insensitively and ignores trailing dots and spaces, and ':' opens an alternate data
     * stream, so all of those could otherwise slip a protected file past a string compare.
     */
    static String normalize(String path) {
        if (path == null || path.isBlank()) return null;
        String p = path.replace('\\', '/');
        while (p.startsWith("./")) p = p.substring(2);
        if (p.isEmpty() || p.startsWith("/") || p.indexOf(':') >= 0 || p.endsWith("/")) return null;
        for (int i = 0; i < p.length(); i++) {
            if (p.charAt(i) < 0x20) return null;
        }
        for (String part : p.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) return null;
            if (part.endsWith(".") || part.endsWith(" ")) return null;
        }
        return p.toLowerCase(Locale.ROOT);
    }

    private static String fileName(String p) {
        return p.substring(p.lastIndexOf('/') + 1);
    }
}
