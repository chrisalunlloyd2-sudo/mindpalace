package com.mindpalace.agent.sims;

import java.io.IOException;
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
        TASK_DIR = base.resolve("todo_management/todo_files/mindpalace");
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

    public static void handleApprovedProposal(WeightedQuorumVote.QuorumResult result) {
        if (!"APPROVED".equals(result.status)) return;

        try {
            // Generate unique TASK number (1000+ band for quorum-spawned tasks)
            int taskNum = 1000 + Math.floorMod(result.proposalId.hashCode(), 1000);
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

            // Build TASK content
            String taskContent = buildTaskContent(result, taskNum, suggestions);

            // Write TASK file
            Path taskPath = TASK_DIR.resolve(taskName);
            Files.createDirectories(TASK_DIR);
            Files.write(taskPath, taskContent.getBytes(), StandardOpenOption.CREATE, StandardOpenOption.WRITE);

            // Log spawning
            logSpawning(result, taskName, taskNum);

            // Write to ledger (TASK_0131: persistence)
            ledgerAppend(result, taskName);

        } catch (Exception e) {
            logError("handleApprovedProposal failed: " + e.getMessage());
        }
    }

    private static String buildTaskContent(WeightedQuorumVote.QuorumResult result, int taskNum, Map<String, List<String>> suggestions) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("# TASK_%04d: Quorum Approved — %s\n\n", taskNum, result.proposalType));
        sb.append(String.format("**Created:** %s\n", Instant.now()));
        sb.append(String.format("**Quorum Cycle:** %s\n", result.proposalId));
        sb.append(String.format("**Outcome:** APPROVED\n"));
        sb.append(String.format("**Priority:** HIGH\n\n"));

        sb.append("## Voting Details\n\n");
        sb.append(String.format("| Field | Value |\n|-------|-------|\n"));
        sb.append(String.format("| Vote Tally | ?%d ?%d ?%d |\n", result.approve, result.reject, result.blind));
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
        String logLine = String.format("%s QUORUM_SPAWN: %s → %s (weight=%.2f)\n",
            java.time.LocalDateTime.now(), result.proposalId, taskName, result.weightedApprove);

        Files.write(WATCH_LOG, logLine.getBytes(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        System.out.println(logLine.strip());
    }

    private static void ledgerAppend(WeightedQuorumVote.QuorumResult result, String taskName) throws IOException {
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
            String logLine = String.format("%s ERROR: %s\n", java.time.LocalDateTime.now(), msg);
            Files.write(WATCH_LOG, logLine.getBytes(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            System.err.println(logLine.strip());
        } catch (IOException e) {
            System.err.println("[LEDGER] Error writing log: " + e);
        }
    }
}
