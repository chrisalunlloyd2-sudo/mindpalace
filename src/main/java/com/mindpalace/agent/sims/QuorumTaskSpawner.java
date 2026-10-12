package com.mindpalace.agent.sims;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * QuorumTaskSpawner — quorum approvals become hermes-kanban cards
 * (t_66a35d3f, unified from the TASK_*.md pipeline of TASK_0125/0131 with
 * the t_740fa7c6 fixes carried forward).
 *
 * Live mode (default): one approved cycle shells out to
 *   hermes kanban create --json --assignee default
 *     --idempotency-key quorum-&lt;cycleId&gt; --created-by mindpalace-quorum
 *     --body &lt;quorum dossier&gt; -- &lt;title&gt;
 * The returned kanban card id is durably recorded in quorum_ledger.jsonl
 * (UTF-8). The board assigns monotonic ids by construction, so the old
 * hash-band TASK numbering (non-monotonic: TASK_1196 born after TASK_1980,
 * collision-prone, needed TRUNCATE_EXISTING as a band-aid) is gone. Voting
 * metadata rides in the card body.
 *
 * Capture mode (tests): {@link #useTestSpawnTarget(Path)} arms it — the
 * would-be CLI invocation (argv list) + card body are written as a
 * quorum_kanban_capture_NNNN.json record into the given scratch dir and the
 * live effects (kanban board, task_watch.log, quorum_ledger.jsonl) are
 * never touched. Selftest 37a exercises the spawn contract end-to-end in
 * this mode without polluting the live board.
 */
public class QuorumTaskSpawner {
    private static Path WATCH_LOG;
    private static Path QUORUM_LEDGER;

    static {
        Path base = aigenBase();
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

    // t_740fa7c6: test-only spawn redirect, reinterpreted by t_66a35d3f as
    // capture mode. --selftest must exercise handleApprovedProposal
    // end-to-end but must NOT touch the live kanban board,
    // task_watch.log, or the ledger. When armed, the would-be invocation is
    // captured into the given scratch dir instead.
    private static volatile Path captureDirOverride = null;
    private static volatile boolean suppressDurableLogs = false;

    /** Test hook: capture spawns into {@code dir} and silence live effects. Pass null to disarm. */
    public static void useTestSpawnTarget(Path dir) {
        captureDirOverride = dir;
        suppressDurableLogs = (dir != null);
    }

    public static void handleApprovedProposal(WeightedQuorumVote.QuorumResult result) {
        if (!"APPROVED".equals(result.status)) return;

        try {
            // Collect agent suggestions (TASK_0126)
            Map<String, List<String>> suggestions = new HashMap<>();
            for (String agent : result.visibleModels) {
                if (AGENT_CAPABILITIES.containsKey(agent)) {
                    suggestions.put(agent, AGENT_CAPABILITIES.get(agent).stream().limit(2).toList());
                }
            }
            result.suggestedActions.putAll(suggestions);
            // (CARD-Q1 F10: result now OWNS a copy of the map, so extending it
            // here no longer mutates the live Proposal.)

            String title = buildCardTitle(result);
            String body = buildQuorumBody(result, suggestions);

            if (captureDirOverride != null) {
                captureSpawn(result, title, body, suggestions);
                return;
            }

            // Live: create the kanban card via the hermes CLI (idempotent on
            // the cycle id — a re-handled proposal returns the same card).
            SpawnOutcome outcome = spawnOnKanban(title, body, result.proposalId);
            String spawnedRef = outcome.ok ? outcome.kanbanId : "SPAWN_FAILED";

            // Log spawning — ASCII arrow and ASCII-plain target (t_740fa7c6).
            logSpawning(result, spawnedRef);

            // Write to ledger (TASK_0131 persistence, now with kanban id)
            ledgerAppend(result, spawnedRef, outcome);

        } catch (Exception e) {
            logError("handleApprovedProposal failed: " + e.getMessage());
        }
    }

    /**
     * Would-be title for the kanban card. ASCII-plain: no room name is in
     * scope here (the proposal text already carries the target via
     * AgentManager.proposalTargetName), and the old TASK filename scheme is
     * replaced by board-assigned card ids.
     */
    static String buildCardTitle(WeightedQuorumVote.QuorumResult result) {
        return String.format("Quorum APPROVED: %s (%s)",
            result.proposalType,
            result.proposalId.substring(0, Math.min(16, result.proposalId.length())));
    }

    /**
     * Card body: full voting dossier with the t_740fa7c6 ASCII tally and
     * UTF-8-safe content. Markdown — the board renders it on the card.
     */
    static String buildQuorumBody(WeightedQuorumVote.QuorumResult result, Map<String, List<String>> suggestions) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("**Quorum Cycle:** %s%n", result.proposalId));
        sb.append(String.format("**Outcome:** APPROVED%n"));

        sb.append("## Voting Details\n\n");
        sb.append(String.format("| Field | Value |%n|-------|-------|%n"));
        sb.append(String.format("| Vote Tally | ok:%d no:%d blind:%d |%n", result.approve, result.reject, result.blind));
        sb.append(String.format("| Agents | %s |%n", String.join(", ", result.visibleModels)));
        sb.append(String.format("| Weight | %.2f |%n", result.weightedApprove));
        sb.append(String.format("| Pulse | %.2f |%n", result.avgPulsePhase));
        sb.append(String.format("| Timestamp | %s |%n%n", Instant.now().toEpochMilli()));

sb.append("## Proposal\n");
        sb.append(String.format("**Type:** %s%n%n", result.proposalType));
        sb.append(String.format("**Description:** %s%n%n", result.text));

        // Agent suggestions (TASK_0126)
        if (!suggestions.isEmpty()) {
sb.append("## Agent Suggestions\n");
            for (var entry : suggestions.entrySet()) {
                sb.append(String.format("**%s:**%n", entry.getKey()));
                for (String action : entry.getValue()) {
                    sb.append(String.format("- [ ] %s%n", action));
                }
sb.append("");
            }
        }

sb.append("## Action\n");
sb.append("This proposal was approved by quorum with strong consensus.\n");
sb.append("1. Review agent suggestions above");
sb.append("2. Execute applicable actions");
sb.append("3. Log result: \"QUORUM_TASK_").append(result.proposalId).append(": ACTION COMPLETED / SKIPPED\"\n");
sb.append("---\n");
        sb.append(String.format("Spawned by QuorumTaskSpawner (t_66a35d3f, was TASK_0125) on %s via hermes kanban%n", Instant.now()));

        return sb.toString();
    }

    /** Live spawn via hermes CLI. Never invoked in capture mode. */
    private static SpawnOutcome spawnOnKanban(String title, String body, String cycleId) {
        List<String> argv = new ArrayList<>();
        argv.add(resolveHermesBinary());
        argv.add("kanban");
        argv.add("create");
        argv.add("--body");
        argv.add(body);
        argv.add("--assignee");
        argv.add("default");
        // Idempotent on the cycle id: a re-handled proposal returns the
        // existing card instead of a duplicate (board-level dedupe).
        argv.add("--idempotency-key");
        argv.add("quorum-" + cycleId);
        argv.add("--created-by");
        argv.add("mindpalace-quorum");
        argv.add("--json");
        argv.add(title);

        try {
            ProcessBuilder pb = new ProcessBuilder(argv);
            pb.redirectErrorStream(false);
            Process p = pb.start();
            boolean finished = p.waitFor(60, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return SpawnOutcome.of(argv, null, "hermes kanban create timed out after 60s");
            }
            String stdout = readAll(p.getInputStream());
            String stderr = readAll(p.getErrorStream());
            if (p.exitValue() != 0) {
                return SpawnOutcome.of(argv, null,
                    "hermes kanban create exit=" + p.exitValue() + tail(stderr, 400));
            }
            return parseKanbanId(argv, stdout, stderr);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return SpawnOutcome.of(argv, null, "spawn failed: " + e);
        }
    }

    /** Pull the card id out of `hermes kanban create --json` stdout. */
    private static SpawnOutcome parseKanbanId(List<String> argv, String stdout, String stderr) {
        // Defensive brace-slice: skip any non-JSON chatter before/after.
        int start = stdout.indexOf('{');
        int end = stdout.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return SpawnOutcome.of(argv, null, "no JSON in hermes output: " + tail(stdout, 400));
        }
        String json = stdout.substring(start, end + 1);
        Matcher m = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        if (!m.find()) {
            return SpawnOutcome.of(argv, null, "JSON lacks id field: " + tail(json, 400));
        }
        return SpawnOutcome.of(argv, m.group(1), null);
    }

    /**
     * hermes binary resolution: explicit override wins, then the well-known
     * per-user venv install (deterministic on this host), then bare "hermes"
     * and let CreateProcess/PATH find it.
     */
    static String resolveHermesBinary() {
        String override = System.getenv("HERMES_BIN");
        if (override != null && !override.isBlank()) return override;
        String localAppData = System.getenv("LOCALAPPDATA");
        Path known = localAppData != null
            ? Paths.get(localAppData, "hermes", "hermes-agent", "venv", "Scripts", "hermes.exe")
            : null;
        if (known != null && Files.isRegularFile(known)) return known.toString();
        return "hermes";
    }

    /** Capture-mode record: what WOULD have been sent to the board. */
    private static void captureSpawn(WeightedQuorumVote.QuorumResult result,
                                     String title, String body,
                                     Map<String, List<String>> suggestions) {
        try {
            Path dir = captureDirOverride;
            Files.createDirectories(dir);
            int n = nextCaptureNumber(dir);
            int taskNum = n;
            String spawnId = String.format("kanban-capture-%04d", taskNum);

            List<String> argv = new ArrayList<>();
            argv.add(resolveHermesBinary());
            argv.add("kanban");
            argv.add("create");
            argv.add("--body");
            argv.add("@body(omitted-in-capture)");
            argv.add("--assignee");
            argv.add("default");
            argv.add("--idempotency-key");
            argv.add("quorum-" + result.proposalId);
            argv.add("--created-by");
            argv.add("mindpalace-quorum");
            argv.add("--json");
            argv.add(title);

            Map<String, Object> record = new LinkedHashMap<>();
            record.put("capture", true);
            record.put("task_spawned_id", spawnId);
            record.put("spawn_backend", "hermes_kanban");
            record.put("idempotency_key", "quorum-" + result.proposalId);
            record.put("created_by", "mindpalace-quorum");
            record.put("assignee", "default");
            record.put("title", title);
            record.put("body", body);
            record.put("argv", argv);
            record.put("proposal_type", result.proposalType);
            record.put("cycle_id", result.proposalId);
            record.put("votes", Map.of("approve", result.approve, "reject", result.reject, "blind", result.blind));
            record.put("weight", result.weightedApprove);
            record.put("timestamp_iso", Instant.now().toString());

            Path capturePath = dir.resolve(String.format("quorum_kanban_capture_%04d.json", taskNum));
            Files.write(capturePath, serializeJson(record).getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);

            System.out.println(String.format("%s QUORUM_SPAWN(CAPTURE): %s -> %s (weight=%.2f)",
                java.time.LocalDateTime.now(), result.proposalId, spawnId, result.weightedApprove));
        } catch (IOException e) {
            logError("captureSpawn failed: " + e.getMessage());
        }
    }

    /**
     * Next capture record number (t_740fa7c6 carry-over). Scans the given
     * dir for quorum_kanban_capture_NNNN.json and returns (max+1) so
     * consecutive spawns in the same scratch dir are strictly increasing.
     */
    static int nextCaptureNumber(Path dir) {
        int max = 2000;
        if (dir != null && Files.isDirectory(dir)) {
            try (var files = Files.list(dir)) {
                for (Path p : (Iterable<Path>) files::iterator) {
                    String name = p.getFileName().toString();
                    Matcher m = Pattern.compile("^quorum_kanban_capture_(\\d{4})\\.json$").matcher(name);
                    if (m.matches()) {
                        try { max = Math.max(max, Integer.parseInt(m.group(1))); }
                        catch (NumberFormatException ignored) { }
                    }
                }
            } catch (IOException ignored) { }
        }
        return max + 1;
    }

    private static void logSpawning(WeightedQuorumVote.QuorumResult result, String spawnedRef) throws IOException {
        // t_740fa7c6: ASCII arrow — "→" mangled to "?" in task_watch.log
        // through cp1252. UTF-8 writes here; ASCII keeps it readable everywhere.
        String logLine = String.format("%s QUORUM_SPAWN: %s -> kanban:%s (weight=%.2f)%n",
            java.time.LocalDateTime.now(), result.proposalId, spawnedRef, result.weightedApprove);

        if (!suppressDurableLogs) {
            Files.write(WATCH_LOG, logLine.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        System.out.println(logLine.strip());
    }

    private static void ledgerAppend(WeightedQuorumVote.QuorumResult result, String spawnedRef, SpawnOutcome outcome) throws IOException {
        if (suppressDurableLogs) return; // t_740fa7c6: test captures never touch the ledger
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
        record.put("task_spawned_id", spawnedRef);
        record.put("kanban_card_id", outcome.ok ? outcome.kanbanId : null);
        record.put("spawn_backend", "hermes_kanban");
        if (!outcome.ok) record.put("spawn_error", outcome.error);

        String json = serializeJson(record);
        Files.write(QUORUM_LEDGER, (json + "\n").getBytes(StandardCharsets.UTF_8),
            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static String readAll(java.io.InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    private static String tail(String s, int max) {
        if (s == null) return "";
        String stripped = s.strip();
        return stripped.length() <= max ? stripped : "..." + stripped.substring(stripped.length() - max);
    }

    private static String serializeJson(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (var e : map.entrySet()) {
            if (!first) sb.append(",");
            sb.append("\"").append(e.getKey()).append("\":");

            Object v = e.getValue();
            if (v instanceof String) {
                sb.append("\"").append(escapeJson((String) v)).append("\"");
            } else if (v instanceof Number || v instanceof Boolean) {
                sb.append(v);
            } else if (v instanceof List) {
                sb.append("[");
                List<?> col = (List<?>) v;
                boolean cfirst = true;
                for (Object item : col) {
                    if (!cfirst) sb.append(",");
                    sb.append("\"").append(item instanceof String ? escapeJson((String) item) : item).append("\"");
                    cfirst = false;
                }
                sb.append("]");
            } else if (v instanceof Map) {
                sb.append(serializeJson(castMap(v)));
            } else {
                sb.append("\"").append(escapeJson(String.valueOf(v))).append("\"");
            }
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object o) { return (Map<String, Object>) o; }

    /**
     * JSON string escaping (t_66a35d3f): the hand-rolled serializer never
     * escaped control characters — a proposal text or body containing a
     * quote/newline would corrupt the ledger line and every line after it.
     */
    private static String escapeJson(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
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

    private static Path aigenBase() {
        String aigenHome = System.getProperty("aigen.home", System.getenv("AIGEN_HOME"));
        if (aigenHome == null) {
            aigenHome = System.getProperty("user.home") + "/AIGEN_SYS";
        }
        return Paths.get(aigenHome);
    }

    /** Result of a live (or would-be live) spawn attempt. */
    static final class SpawnOutcome {
        final List<String> argv;
        final String kanbanId;
        final String error;
        final boolean ok;

        private SpawnOutcome(List<String> argv, String kanbanId, String error) {
            this.argv = argv;
            this.kanbanId = kanbanId;
            this.error = error;
            this.ok = kanbanId != null && error == null;
        }

        static SpawnOutcome of(List<String> argv, String kanbanId, String error) {
            return new SpawnOutcome(argv, kanbanId, error);
        }
    }
}