package com.mindpalace.agent.sims;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * QuorumTaskSpawner — TASK_0125 implementation
 * When quorum votes APPROVED, spawn local TASK files for Hermes execution
 * Logs to task_watch.log for monitoring
 */
public class QuorumTaskSpawner {
    private static Path TASK_DIR;
    private static Path WATCH_LOG;
    private static Path QUORUM_LEDGER;

    static {
        String aigenHome = System.getProperty("aigen.home", System.getenv("AIGEN_HOME"));
        if (aigenHome == null) {
            aigenHome = System.getProperty("user.home") + "/AIGEN_SYS";
        }
        Path base = Paths.get(aigenHome);
        // CARD-Q1 F10: quorum-spawned files go in their OWN inbox — writing
        // TASK_*.md into the live queue broke the one-TASK-at-a-time rule
        // (AGENTS.md hard rule 4) and raced the hourly TASK sweep.
        TASK_DIR = base.resolve("todo_management/todo_files/mindpalace/quorum_inbox");
        WATCH_LOG = base.resolve("todo_management/task_watch.log");
        QUORUM_LEDGER = base.resolve("quorum_ledger.jsonl");
    }

    // Agent capability mappings (TASK_0126)
    private static final Map<String, List<String>> AGENT_CAPABILITIES = Map.ofEntries(
        Map.entry("llama3.2:1b", List.of("run_memory_optimization", "test_sandboxing", "verify_genetics_sandbox", "check_agent_health")),
        Map.entry("qwen2.5:0.5b", List.of("audit_code_quality", "check_compliance", "profile_performance", "lint_codebase")),
        Map.entry("deepseek-r1:1.5b", List.of("debug_anomalies", "prove_correctness", "optimize_algorithms", "trace_execution"))
    );

    private QuorumTaskSpawner() {}

    // t_740fa7c6: test-only spawn redirect. --selftest must exercise
    // handleApprovedProposal end-to-end but must NOT pollute the live
    // quorum_inbox, task_watch.log, or the ledger. When armed, spawns land
    // in the given scratch dir and watch/ledger writes are suppressed.
    private static volatile Path taskDirOverride = null;
    private static volatile boolean suppressDurableLogs = false;

    /** Test hook: redirect spawns to {@code dir} and silence watch/ledger writes. Pass null to disarm. */
    public static void useTestSpawnTarget(Path dir) {
        taskDirOverride = dir;
        suppressDurableLogs = (dir != null);
    }

    public static void handleApprovedProposal(WeightedQuorumVote.QuorumResult result) {
        if (!"APPROVED".equals(result.status)) return;

        try {
            // t_740fa7c6: monotonic numbering. The old scheme hashed the
            // proposal id into the 1000+ band (1000 + floorMod(hash, 1000)),
            // which is non-monotonic and collides — TASK_1196 spawned after
            // TASK_1980, and hash collisions needed TRUNCATE_EXISTING as a
            // band-aid (CARD-Q1 F10). Number sequentially above every number
            // already used in the inbox; archives are counted too in live
            // mode so a post-archive spawn cannot reuse a retired number.
            Path tasksDir = taskDirOverride != null ? taskDirOverride : TASK_DIR;
            int taskNum = nextTaskNumber(tasksDir);
            String taskName = String.format("TASK_%04d_quorum_approved_%s_%s.md",
                taskNum, result.proposalType, result.proposalId.substring(0, Math.min(16, result.proposalId.length())));

            // Collect agent suggestions
            Map<String, List<String>> suggestions = new HashMap<>();
            for (String agent : result.visibleModels) {
                if (AGENT_CAPABILITIES.containsKey(agent)) {
                    suggestions.put(agent, AGENT_CAPABILITIES.get(agent).stream().limit(2).toList());
                }
            }
            result.suggestedActions.putAll(suggestions);
            // (CARD-Q1 F10: result now OWNS a copy of the map, so extending it
            // here no longer mutates the live Proposal.)

            // Build TASK content
            String taskContent = buildTaskContent(result, taskNum, suggestions);

            // Write TASK file — UTF-8 explicitly (t_740fa7c6: the default-
            // charset getBytes() mangled non-ASCII on Windows cp1252).
            // CREATE+TRUNCATE retained: defensive only now that numbering is
            // sequential and collision-free.
            Path taskPath = tasksDir.resolve(taskName);
            Files.createDirectories(tasksDir);
            Files.write(taskPath, taskContent.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);

            // Log spawning
            logSpawning(result, taskName, taskNum);

            // Write to ledger (TASK_0131: persistence)
            ledgerAppend(result, taskName);

        } catch (Exception e) {
            logError("handleApprovedProposal failed: " + e.getMessage());
        }
    }

    /**
     * Next free quorum TASK number (t_740fa7c6): scan {@code dir} for
     * TASK_NNNN_* names and return (max+1). In live mode the resolved/
     * archive sibling is scanned too, so numbering stays monotonic across
     * archival sweeps. Starts from 2001 so the first spawn sits above the
     * highest hash-band number ever handed out (1999) — no reuse, no
     * collision with archived files.
     */
    static int nextTaskNumber(Path dir) {
        int max = 2000;
        max = scanMaxTaskNumber(dir, max);
        if (taskDirOverride == null) {
            Path live = Paths.get(aigenBase().resolve("todo_management/todo_files/mindpalace/resolved").toString());
            max = scanMaxTaskNumber(live, max);
        }
        return max + 1;
    }

    private static int scanMaxTaskNumber(Path dir, int currentMax) {
        if (dir == null || !Files.isDirectory(dir)) return currentMax;
        try (var files = Files.list(dir)) {
            for (Path p : (Iterable<Path>) files::iterator) {
                String name = p.getFileName().toString();
                if (name.startsWith("TASK_") && name.length() >= 9) {
                    try { currentMax = Math.max(currentMax, Integer.parseInt(name.substring(5, 9))); }
                    catch (NumberFormatException ignored) { }
                }
            }
        } catch (IOException ignored) { }
        return currentMax;
    }

    private static Path aigenBase() {
        String aigenHome = System.getProperty("aigen.home", System.getenv("AIGEN_HOME"));
        if (aigenHome == null) {
            aigenHome = System.getProperty("user.home") + "/AIGEN_SYS";
        }
        return Paths.get(aigenHome);
    }

    private static String buildTaskContent(WeightedQuorumVote.QuorumResult result, int taskNum, Map<String, List<String>> suggestions) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("# TASK_%04d: Quorum Approved - %s%n%n", taskNum, result.proposalType));
        sb.append(String.format("**Created:** %s\n", Instant.now()));
        sb.append(String.format("**Quorum Cycle:** %s\n", result.proposalId));
        sb.append(String.format("**Outcome:** APPROVED\n"));
        sb.append(String.format("**Priority:** HIGH\n\n"));

        sb.append("## Voting Details\n\n");
        sb.append(String.format("| Field | Value |\n|-------|-------|\n"));
        sb.append(String.format("| Vote Tally | ok:%d no:%d blind:%d |%n", result.approve, result.reject, result.blind));
        sb.append(String.format("| Agents | %s |\n", String.join(", ", result.visibleModels)));
        sb.append(String.format("| Weight | %.2f |\n", result.weightedApprove));
        sb.append(String.format("| Pulse | %.2f |\n", result.avgPulsePhase));
        sb.append(String.format("| Timestamp | %s |\n\n", Instant.now().toEpochMilli()));

        sb.append("## Proposal\n\n");
        sb.append(String.format("**Type:** %s\n\n", result.proposalType));
        sb.append(String.format("**Description:** %s\n\n", result.text));

        // Agent suggestions (TASK_0126)
        if (!suggestions.isEmpty()) {
            sb.append("## Agent Suggestions\n\n");
            for (var entry : suggestions.entrySet()) {
                sb.append(String.format("**%s:**\n", entry.getKey()));
                for (String action : entry.getValue()) {
                    sb.append(String.format("- [ ] %s\n", action));
                }
                sb.append("\n");
            }
        }

        sb.append("## Action\n\n");
        sb.append("This proposal was approved by quorum with strong consensus.\n\n");
        sb.append("1. Review agent suggestions above\n");
        sb.append("2. Execute applicable actions\n");
        sb.append("3. Log result: \"QUORUM_TASK_" + result.proposalId + ": ACTION COMPLETED / SKIPPED\"\n\n");
        sb.append("---\n\n");
        sb.append(String.format("Spawned by QuorumTaskSpawner (TASK_0125) on %s\n", Instant.now()));

        return sb.toString();
    }

    private static void logSpawning(WeightedQuorumVote.QuorumResult result, String taskName, int taskNum) throws IOException {
        // t_740fa7c6: ASCII arrow — the "→" glyph mangled to "?" in
        // task_watch.log (cp1252 console/file default).
        String logLine = String.format("%s QUORUM_SPAWN: %s -> %s (weight=%.2f)%n",
            java.time.LocalDateTime.now(), result.proposalId, taskName, result.weightedApprove);

        if (!suppressDurableLogs) {
            Files.write(WATCH_LOG, logLine.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        System.out.println(logLine.strip());
    }

    private static void ledgerAppend(WeightedQuorumVote.QuorumResult result, String taskName) throws IOException {
        if (suppressDurableLogs) return; // t_740fa7c6: test spawns never touch the ledger
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("cycle_id", result.proposalId);
        record.put("timestamp", System.currentTimeMillis());
        record.put("timestamp_iso", Instant.now().toString());
        record.put("proposal_type", result.proposalType);
        record.put("agents", result.visibleModels);
        record.put("votes", Map.of("approve", result.approve, "reject", result.reject, "blind", result.blind));
        record.put("outcome", result.status);
        record.put("weight", result.weightedApprove);
        record.put("pulse", result.avgPulsePhase);
        record.put("task_spawned_id", taskName);

        String json = serializeJson(record);
        Files.write(QUORUM_LEDGER, (json + "\n").getBytes(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static String serializeJson(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (var e : map.entrySet()) {
            if (!first) sb.append(",");
            sb.append("\"").append(e.getKey()).append("\":");

            Object v = e.getValue();
            if (v instanceof String) {
                sb.append("\"").append(v).append("\"");
            } else if (v instanceof Number || v instanceof Boolean) {
                sb.append(v);
            } else if (v instanceof java.util.Collection) {
                sb.append("[");
                java.util.Collection<?> col = (java.util.Collection<?>) v;
                boolean cfirst = true;
                for (Object item : col) {
                    if (!cfirst) sb.append(",");
                    sb.append("\"").append(item).append("\"");
                    cfirst = false;
                }
                sb.append("]");
            } else if (v instanceof Map) {
                sb.append(serializeJson((Map<String, Object>) v));
            } else {
                sb.append("\"").append(v).append("\"");
            }
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }

    private static void logError(String msg) {
        try {
            String logLine = String.format("%s ERROR: %s%n", java.time.LocalDateTime.now(), msg);
            if (!suppressDurableLogs) {
                Files.write(WATCH_LOG, logLine.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
            System.err.println(logLine.strip());
        } catch (IOException e) {
            System.err.println("[LEDGER] Error writing log: " + e);
        }
    }
}
