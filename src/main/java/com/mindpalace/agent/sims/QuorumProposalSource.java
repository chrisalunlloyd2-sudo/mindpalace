package com.mindpalace.agent.sims;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * QuorumProposalSource — REAL work for the quorum vote (t_948a71be).
 *
 * Until now the quorum only ever voted on one synthetic proposal per cycle
 * ("Act on &lt;room&gt;", AgentManager.runQuorumVote). This source gathers the
 * actual work backlog into proposals so the voters reason about real items:
 *
 *   kanban  — hermes-kanban cards in 'ready' or 'triage'. Cards minted by
 *             mindpalace-quorum itself are EXCLUDED: an approval spawns a
 *             card, that card becomes a proposal, the next cycle approves
 *             its own output — a self-feeding loop. Same exclusion for
 *             cards the github poller bridge already minted from the
 *             issues this class would fetch (deduped by issue number).
 *   github  — open GitHub issues labeled 'hermes-queue' (the 360-oversight
 *             cloud routine's channel). PUBLIC-SOURCED: an approved issue
 *             can only ever land in 'triage' (hermes kanban create
 *             --triage) — it never promotes past triage without the
 *             Architect's sign-off. Read-only toward GitHub, add-only
 *             toward the board.
 *   checks  — failing local checks parsed from the last line of
 *             monitor_log.jsonl (chat repetition loop, unpushed commits,
 *             low disk). Local-only, deterministic, cheap.
 *
 * Verdict wiring (applyVerdict — t_948a71be):
 *   APPROVED kanban card      → hermes kanban promote &lt;card&gt; "quorum-APPROVED ..."
 *   APPROVED public issue     → hermes kanban create --triage --idempotency-key ...
 *   APPROVED failing check    → hermes kanban create --triage (parked for triage)
 *   REJECTED kanban card      → hermes kanban comment &lt;card&gt; --author mindpalace-quorum
 *   REJECTED public issue     → ledger only (GitHub stays add-only, no mutation)
 *   PENDING / BLINDED         → no action
 *
 * Every applied verdict is appended to quorum_realwork_ledger.jsonl
 * (AIGEN_SYS root, UTF-8, append-only) and consult-and-skip-on-restart keeps
 * a verdict from applying twice across game restarts. In capture mode
 * ({@link #useTestEffectsTarget}) the CLI argv is written as
 * realwork_verdict_NNNN.json records instead and NO live effect happens —
 * the same doctrine QuorumTaskSpawner.useTestSpawnTarget established for
 * spawns (selftest 37a), extended to verdict wiring (selftest 37b).
 */
public class QuorumProposalSource {

    // ── Public-source gates (card spec: never past triage without Architect) ──
    static final int KANBAN_CAP = 2;          // most concrete work first
    static final int CHECK_CAP = 2;           // local failures second
    static final int ISSUE_CAP = 2;           // public-sourced last, gated
    static final int DISK_MIN_GB = 5;         // below this, disk check fails
    static final int UNPUSHED_MIN = 3;        // sync lag threshold
    static final String QUORUM_CREATED_BY = "mindpalace-quorum";
    static final String HERMES_QUEUE_LABEL = "hermes-queue";

    /** The three real-work sources, each with a stable sourceId. */
    public static final class RealWorkProposal {
        public final String sourceId;      // kanban: card id; github: #num; check: check-<slug>
        public final String source;        // "kanban" | "github" | "check"
        public final boolean publicSourced; // github issues → triage gate
        public final String title;         // the work item itself
        public final String detail;        // summary (body prefix / check detail), may be ""
        public final String link;          // where the work lives (card id, issue URL, monitor log)
        public final String concreteAction; // names the file/command/check voters vote on

        RealWorkProposal(String sourceId, String source, boolean publicSourced,
                         String title, String detail, String link, String concreteAction) {
            this.sourceId = sourceId; this.source = source; this.publicSourced = publicSourced;
            this.title = title; this.detail = detail == null ? "" : detail;
            this.link = link == null ? "" : link; this.concreteAction = concreteAction == null ? "" : concreteAction;
        }

        /** Full voting text: source-tagged title + detail — what the voter models actually read. */
        public String voteText() {
            String d = detail.isBlank() ? "" : " — " + detail;
            return "[" + source + (publicSourced ? "/public" : "") + "] " + title + d;
        }

        @Override public String toString() {
            return source + ":" + sourceId + " " + title;
        }
    }

    // ── Runtime config ──
    private String ghRepoOwner, ghRepoName, ghToken;
    private Path monitorLogPath = defaultMonitorLogPath();
    private Path ledgerPath = defaultLedgerPath();
    private Path captureDir = null;                 // test capture mode (37b)
    private String testKanbanFeed = null;           // test-injected list JSON (37b)
    private String testMonitorLine = null;          // test-injected monitor line (37b)
    private String testIssueFeed = null;            // test-injected issues JSON (37b)
    private final Set<String> appliedVerdicts = new HashSet<>(); // sourceId#verdict, seeded from ledger

    public QuorumProposalSource() {
        seedFromLedger();
    }

    /** Called by AgentManager.initGitHub when the GitHub bridge is credentialed. */
    public void configureGithub(String owner, String repo, String token) {
        this.ghRepoOwner = owner; this.ghRepoName = repo; this.ghToken = token;
    }

    /** Test hook: capture all verdict CLI invocations into dir, touch no live surface. */
    public void useTestEffectsTarget(Path dir) {
        this.captureDir = dir;
    }

    /** Test hook: serve this JSON as the kanban list feed instead of the CLI. */
    public void useTestKanbanFeed(String json) { this.testKanbanFeed = json; }

    /** Test hook: serve this line as the monitor-log tail instead of the file. */
    public void useTestMonitorLine(String line) { this.testMonitorLine = line; }

    /** Test hook: serve this JSON as the hermes-queue issue feed instead of the API. */
    public void useTestIssueFeed(String json) { this.testIssueFeed = json; }

    // ── Gathering ──────────────────────────────────────────────────────────

    /** Gather real-work proposals for one quorum cycle (capped, deduped, priority-ordered). */
    public List<RealWorkProposal> gather() {
        List<RealWorkProposal> out = new ArrayList<>();
        out.addAll(gatherKanbanCards());
        out.addAll(gatherChecks());
        List<RealWorkProposal> issues = gatherHermesQueueIssues();
        // Dedupe: cards the poller bridge minted from hermes-queue issues are
        // titled "GitHub issue #<n>: ..." — drop the raw issue if its card stands.
        Set<String> covered = new HashSet<>();
        for (RealWorkProposal k : out) {
            Matcher m = Pattern.compile("GitHub issue #(\\d+)").matcher(k.title + " " + k.detail);
            if (m.find()) covered.add(m.group(1));
        }
        for (RealWorkProposal g : issues) {
            if (!covered.contains(g.sourceId.substring(1))) out.add(g);
        }
        return out;
    }

    /** Kanban cards in ready/triage (quorum-minted cards excluded — no self-feeding). */
    List<RealWorkProposal> gatherKanbanCards() {
        String json;
        if (testKanbanFeed != null) {
            json = testKanbanFeed;
        } else {
            json = runHermesList("ready");
            if (json == null) json = runHermesList("triage");
            if (json == null) return List.of();
        }
        // The test feed concatenates both statuses; the CLI path yields one.
        return parseKanbanCards(json, QUORUM_CREATED_BY, KANBAN_CAP);
    }

    /**
     * Pure parser (selftest 37b): kanban tasks array → one proposal per
     * card in status ready/triage whose created_by is not ours. Stable
     * sourceId = card id. Detail = first 120 chars of the body, newlines
     * stripped (ASCII-safe prompt text).
     */
    static List<RealWorkProposal> parseKanbanCards(String json, String selfCreatedBy, int cap) {
        List<RealWorkProposal> out = new ArrayList<>();
        if (json == null || json.isBlank()) return out;
        try {
            JsonElement root = JsonParser.parseString(json);
            JsonArray arr = root.isJsonArray() ? root.getAsJsonArray()
                : (root.isJsonObject() && root.getAsJsonObject().has("tasks")
                    ? root.getAsJsonObject().getAsJsonArray("tasks") : new JsonArray());
            for (JsonElement el : arr) {
                if (out.size() >= cap) break;
                if (!el.isJsonObject()) continue;
                JsonObject t = el.getAsJsonObject();
                String status = str(t, "status");
                if (!"ready".equals(status) && !"triage".equals(status)) continue;
                String created = str(t, "created_by");
                if (selfCreatedBy != null && selfCreatedBy.equals(created)) continue; // no self-feed
                String id = str(t, "id");
                String title = str(t, "title");
                if (id.isEmpty() || title.isEmpty()) continue;
                String body = str(t, "body").replace("\r", "").replace("\n", " ");
                String detail = body.length() > 120 ? body.substring(0, 120) : body;
                out.add(new RealWorkProposal(id, "kanban", false, title, detail, id,
                    "hermes kanban claim " + id + ", then execute the card body at the board"));
            }
        } catch (Exception ignored) { }
        return out;
    }

    /** Failing local checks from the monitor log tail. */
    List<RealWorkProposal> gatherChecks() {
        try {
            String line;
            if (testMonitorLine != null) {
                line = testMonitorLine;
            } else {
                Path p = monitorLogPath;
                if (p == null || !Files.isRegularFile(p)) return List.of();
                List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
                if (lines.isEmpty()) return List.of();
                // Last non-blank line = the freshest 15-minute health snapshot.
                line = null;
                for (int i = lines.size() - 1; i >= 0; i--) {
                    String l = lines.get(i).strip();
                    if (!l.isEmpty()) { line = l; break; }
                }
                if (line == null || line.isEmpty()) return List.of();
            }
            return parseChecks(line, CHECK_CAP);
        } catch (IOException e) {
            return List.of();
        }
    }

    /**
     * Pure parser (selftest 37b): one monitor_log.jsonl line → one proposal
     * per failing check. Checks (concrete, local, cheap to verify):
     *   chat_loop.looping true       → chat repetition loop (avg_similarity)
     *   git_unpushed_commits >= N    → sync lag
     *   disk_free_gb.C < DISK_MIN_GB → disk pressure
     */
    static List<RealWorkProposal> parseChecks(String monitorLine, int cap) {
        List<RealWorkProposal> out = new ArrayList<>();
        if (monitorLine == null || monitorLine.isBlank()) return out;
        JsonObject o;
        try {
            String s = monitorLine.strip();
            int start = s.indexOf('{');
            if (start < 0) return out;
            o = JsonParser.parseString(s.substring(start)).getAsJsonObject();
        } catch (Exception e) {
            return out;
        }

        // Chat repetition loop: the monitor's own repetition detector.
        if (o.has("chat_loop") && o.getAsJsonObject("chat_loop").has("looping")
            && o.getAsJsonObject("chat_loop").get("looping").getAsBoolean()) {
            double sim = o.getAsJsonObject("chat_loop").has("avg_similarity")
                && !o.getAsJsonObject("chat_loop").get("avg_similarity").isJsonNull()
                ? o.getAsJsonObject("chat_loop").get("avg_similarity").getAsDouble() : -1;
            int sample = o.getAsJsonObject("chat_loop").has("sample_size")
                ? o.getAsJsonObject("chat_loop").get("sample_size").getAsInt() : 0;
            out.add(new RealWorkProposal("check-chat-loop", "check", false,
                "Chat repetition loop detected by monitor",
                String.format("avg_similarity=%.2f over %d samples in chat_logs", sim, sample),
                "monitor_log.jsonl",
                "inspect newest chat_logs/chat-*.jsonl tail, then restart the agent loop"));
        }

        // Git sync lag.
        if (o.has("git_unpushed_commits") && !o.get("git_unpushed_commits").isJsonNull()
            && o.get("git_unpushed_commits").getAsInt() >= UNPUSHED_MIN) {
            int n = o.get("git_unpushed_commits").getAsInt();
            out.add(new RealWorkProposal("check-unpushed", "check", false,
                n + " unpushed commits on the mindpalace repo",
                "git_unpushed_commits=" + n + " in the monitor snapshot",
                "monitor_log.jsonl",
                "run scripts/mindpalace-sync.sh (or git push) to close the sync gap"));
        }

        // Disk pressure.
        if (o.has("disk_free_gb") && o.getAsJsonObject("disk_free_gb").has("C")
            && !o.getAsJsonObject("disk_free_gb").get("C").isJsonNull()) {
            double gb = o.getAsJsonObject("disk_free_gb").get("C").getAsDouble();
            if (gb < DISK_MIN_GB) {
                out.add(new RealWorkProposal("check-disk", "check", false,
                    String.format("Disk C: low (%.1f GB free, threshold %d GB)", gb, DISK_MIN_GB),
                    "disk_free_gb.C=" + gb,
                    "monitor_log.jsonl",
                    "prune target/ build dirs and old mindpalace_memory/evolution archives"));
            }
        }
        return out.size() > cap ? out.subList(0, cap) : out;
    }

    /** Open issues labeled 'hermes-queue' (public-sourced; needs credentials). */
    List<RealWorkProposal> gatherHermesQueueIssues() {
        String json;
        if (testIssueFeed != null) {
            json = testIssueFeed;
        } else {
            if (ghToken == null || ghToken.isBlank() || ghRepoOwner == null) return List.of();
            json = fetchHermesQueueIssues();
            if (json == null) return List.of();
        }
        return parseIssues(json, ISSUE_CAP);
    }

    /** GitHub REST: open issues with the hermes-queue label, hard 30s cap. */
    private String fetchHermesQueueIssues() {
        try {
            String url = String.format("%s/repos/%s/%s/issues?labels=%s&state=open&per_page=%d",
                "https://api.github.com", ghRepoOwner, ghRepoName, HERMES_QUEUE_LABEL, ISSUE_CAP);
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url))
                .header("Accept", "application/vnd.github.v3+json")
                .header("Authorization", "token " + ghToken)
                .timeout(Duration.ofSeconds(30))
                .GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return null;
            return resp.body();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return null;
        }
    }

    /**
     * Pure parser (selftest 37b): GitHub issues array → proposals flagged
     * publicSourced. sourceId = "#&lt;number&gt;" (leading # kept: cards
     * minted by the poller bridge are deduped against this id minus '#').
     */
    static List<RealWorkProposal> parseIssues(String json, int cap) {
        List<RealWorkProposal> out = new ArrayList<>();
        if (json == null || json.isBlank()) return out;
        try {
            JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
            for (JsonElement el : arr) {
                if (out.size() >= cap) break;
                if (!el.isJsonObject()) continue;
                JsonObject o = el.getAsJsonObject();
                if (!o.has("number") || o.get("number").isJsonNull()) continue;
                int num = o.get("number").getAsInt();
                String title = str(o, "title");
                if (title.isEmpty()) continue;
                String body = str(o, "body").replace("\r", "").replace("\n", " ");
                String detail = body.length() > 120 ? body.substring(0, 120) : body;
                String url = str(o, "html_url");
                out.add(new RealWorkProposal("#" + num, "github", true, title, detail, url,
                    "read " + url + " (public-sourced: any action parks in triage for Architect sign-off)"));
            }
        } catch (Exception ignored) { }
        return out;
    }

    // ── Verdict wiring ─────────────────────────────────────────────────────

    /**
     * Route one real-work verdict to its concrete action (see class doc).
     * Applies at most once per (sourceId, verdict) — in-JVM and, when not
     * in capture mode, durably via the realwork ledger. 37b captures the
     * argv instead of executing it.
     */
    public void applyVerdict(WeightedQuorumVote.QuorumResult r, RealWorkProposal item) {
        boolean approved = "APPROVED".equals(r.status);
        boolean rejected = "REJECTED".equals(r.status);
        if (!approved && !rejected) return; // PENDING/BLINDED: leave the board untouched

        String key = item.sourceId + "#" + r.status;
        if (!appliedVerdicts.add(key)) return; // already applied this JVM run

        String tally = String.format("ok:%d no:%d blind:%d", r.approve, r.reject, r.blind);
        String dossier = String.format("**Quorum %s** (%s) on real-work %s `%s`%n%n%s%n%n"
                + "| Tally | %s |%n| Weight | %.2f |%n| Cycle | %s |%n%n"
                + "Concrete: %s%n",
            r.status, tally, item.source, item.sourceId,
            item.voteText(), tally, r.weightedApprove, r.proposalId,
            item.concreteAction.isBlank() ? "(none recorded)" : item.concreteAction);

        List<String> argv = null;
        String idempotencyKey = null;
        String action;
        if ("kanban".equals(item.source)) {
            if (approved) {
                action = "promote";
                argv = new ArrayList<>(List.of(hermesArg(), "kanban", "promote", item.sourceId,
                    String.format("quorum-APPROVED %s (%s, weight=%.2f)", r.proposalId, tally, r.weightedApprove)));
            } else {
                action = "comment";
                argv = new ArrayList<>(List.of(hermesArg(), "kanban", "comment",
                    "--author", QUORUM_CREATED_BY, item.sourceId,
                    "Quorum REJECTED this work (" + tally + ", cycle " + r.proposalId + "). "
                        + "Proposal considered: " + truncate(item.voteText(), 200)));
            }
        } else if (approved) {
            // github / check sources: park the approval in triage. Public
            // items NEVER pass triage without the Architect's sign-off;
            // local check approvals park in triage too (the specifier is
            // the human gate before any auto-dispatch).
            action = "create-triage";
            idempotencyKey = "quorum-" + item.source + "-" + item.sourceId.replace("#", "");
            argv = new ArrayList<>(List.of(hermesArg(), "kanban", "create",
                "--triage",
                "--body", dossier,
                "--assignee", "default",
                "--idempotency-key", idempotencyKey,
                "--created-by", QUORUM_CREATED_BY,
                "--json",
                "Quorum APPROVED " + item.source + " " + item.sourceId + ": " + item.title));
        } else {
            // REJECTED public issue or rejected check: ledger only. GitHub
            // is read-only toward the outside world (add-only doctrine);
            // a local check needs no comment surface.
            action = "ledger-only";
        }

        boolean ran = runVerdict(action, argv, r, item, idempotencyKey);
        if (!ran) { appliedVerdicts.remove(key); return; } // allow retry next cycle
        ledgerAppend(r, item, action, idempotencyKey);
    }

    /**
     * Execute (or capture) one verdict CLI invocation. Returns false when a
     * live run failed (so the verdict can retry next cycle); capture mode
     * always succeeds after writing the record.
     */
    private boolean runVerdict(String action, List<String> argv, WeightedQuorumVote.QuorumResult r,
                               RealWorkProposal item, String idempotencyKey) {
        if (captureDir != null) {
            return captureVerdict(action, argv, r, item, idempotencyKey);
        }
        if (argv == null) return true; // ledger-only action
        try {
            ProcessBuilder pb = new ProcessBuilder(argv);
            pb.redirectErrorStream(false);
            Process p = pb.start();
            boolean finished = p.waitFor(60, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                System.err.println("[QuorumProposalSource] " + action + " timed out: " + item);
                return false;
            }
            if (p.exitValue() != 0) {
                System.err.println("[QuorumProposalSource] " + action + " exit=" + p.exitValue()
                    + " for " + item + " stderr=" + tailChars(readAll(p.getErrorStream()), 200));
                return false;
            }
            System.out.println("[QuorumProposalSource] " + action + " ok: " + item);
            return true;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            System.err.println("[QuorumProposalSource] " + action + " failed: " + e);
            return false;
        }
    }

    /** Capture-mode record: the argv a live run WOULD have executed. */
    private boolean captureVerdict(String action, List<String> argv, WeightedQuorumVote.QuorumResult r,
                                   RealWorkProposal item, String idempotencyKey) {
        try {
            Files.createDirectories(captureDir);
            int n = nextVerdictNumber(captureDir);
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("capture", true);
            record.put("record_id", String.format("realwork-verdict-%04d", n));
            record.put("action", action);
            record.put("argv", argv);
            record.put("idempotency_key", idempotencyKey);
            record.put("source", item.source);
            record.put("source_id", item.sourceId);
            record.put("public_sourced", item.publicSourced);
            record.put("proposal_id", r.proposalId);
            record.put("proposal_type", r.proposalType);
            record.put("verdict", r.status);
            record.put("votes", Map.of("approve", r.approve, "reject", r.reject, "blind", r.blind));
            record.put("weight", r.weightedApprove);
            record.put("timestamp_iso", Instant.now().toString());
            Path out = captureDir.resolve(String.format("realwork_verdict_%04d.json", n));
            Files.write(out, QuorumTaskSpawnerJson.serialize(record).getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            System.out.println(String.format("%s REALWORK_VERDICT(CAPTURE): %s %s -> %s",
                java.time.LocalDateTime.now(), r.status, item, out.getFileName()));
            return true;
        } catch (IOException e) {
            System.err.println("[QuorumProposalSource] captureVerdict failed: " + e.getMessage());
            return false;
        }
    }

    /** Next record number (max+1 over realwork_verdict_NNNN.json in dir). */
    static int nextVerdictNumber(Path dir) {
        int max = 0;
        if (dir != null && Files.isDirectory(dir)) {
            try (var files = Files.list(dir)) {
                for (Path p : (Iterable<Path>) files::iterator) {
                    Matcher m = Pattern.compile("^realwork_verdict_(\\d{4})\\.json$").matcher(p.getFileName().toString());
                    if (m.matches()) {
                        try { max = Math.max(max, Integer.parseInt(m.group(1))); }
                        catch (NumberFormatException ignored) { }
                    }
                }
            } catch (IOException ignored) { }
        }
        return max + 1;
    }

    // ── Durable ledger (cross-restart dedupe) ──────────────────────────────

    private void seedFromLedger() {
        Path p = ledgerPath;
        if (p == null || !Files.isRegularFile(p)) return;
        try {
            for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                Matcher m = Pattern.compile("\"source_id\":\\s*\"([^\"]*)\"[^}]*\"verdict\":\\s*\"([^\"]*)\"").matcher(line);
                if (m.find()) appliedVerdicts.add(m.group(1) + "#" + m.group(2));
            }
        } catch (IOException ignored) { }
    }

    private void ledgerAppend(WeightedQuorumVote.QuorumResult r, RealWorkProposal item,
                              String action, String idempotencyKey) {
        Path p = ledgerPath;
        if (p == null) return;
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("source_id", item.sourceId);
        rec.put("source", item.source);
        rec.put("verdict", r.status);
        rec.put("action", action);
        rec.put("proposal_id", r.proposalId);
        rec.put("idempotency_key", idempotencyKey);
        rec.put("timestamp_iso", Instant.now().toString());
        try {
            Files.createDirectories(p.getParent());
            Files.write(p, (QuorumTaskSpawnerJson.serialize(rec) + "\n").getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("[QuorumProposalSource] ledger append failed: " + e.getMessage());
        }
    }

    // ── CLI + JSON helpers (shared doctrine with QuorumTaskSpawner) ────────

    /** hermes binary resolution — same policy as the spawner (env, venv, PATH). */
    private static String hermesArg() {
        return QuorumTaskSpawner.resolveHermesBinary();
    }

    /** Real kanban list via CLI; null on any failure (source silently empty). */
    private String runHermesList(String status) {
        List<String> argv = List.of(hermesArg(), "kanban", "list", "--status", status, "--json");
        try {
            ProcessBuilder pb = new ProcessBuilder(argv);
            pb.redirectErrorStream(false);
            Process p = pb.start();
            boolean finished = p.waitFor(60, TimeUnit.SECONDS);
            if (!finished) { p.destroyForcibly(); return null; }
            if (p.exitValue() != 0) return null;
            return readAll(p.getInputStream());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return null;
        }
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private static String tailChars(String s, int max) {
        if (s == null) return "";
        String t = s.strip();
        return t.length() <= max ? t : "..." + t.substring(t.length() - max);
    }

    private static String readAll(java.io.InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
        }
        return sb.toString();
    }

    static Path defaultMonitorLogPath() {
        return Paths.get(aigenBase().toString(),
            "todo_management", "todo_files", "mindpalace", "monitor_log.jsonl");
    }

    static Path defaultLedgerPath() {
        return Paths.get(aigenBase().toString(), "quorum_realwork_ledger.jsonl");
    }

    private static Path aigenBase() {
        String aigenHome = System.getProperty("aigen.home", System.getenv("AIGEN_HOME"));
        if (aigenHome == null) {
            aigenHome = System.getProperty("user.home") + "/AIGEN_SYS";
        }
        return Paths.get(aigenHome);
    }

    /** Test seam: point the ledger somewhere scratch (37b keeps the real one untouched). */
    public void useTestLedgerPath(Path p) { this.ledgerPath = p; }

    /**
     * Shared JSON serializer for the realwork ledger + capture records.
     * Delegates to the spawner's escaping discipline (control chars escaped
     * — t_66a35d3f) by way of a thin static bridge so both files use one
     * implementation.
     */
    static final class QuorumTaskSpawnerJson {
        private QuorumTaskSpawnerJson() { }

        static String serialize(Map<String, Object> map) {
            // Local serializer with the SAME escaping rules as
            // QuorumTaskSpawner's private one — the ledger and capture
            // records must stay line-oriented JSON under any content.
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
                    boolean cfirst = true;
                    for (Object item : (List<?>) v) {
                        if (!cfirst) sb.append(",");
                        sb.append(item instanceof String
                            ? "\"" + escapeJson((String) item) + "\"" : item);
                        cfirst = false;
                    }
                    sb.append("]");
                } else if (v instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> nested = (Map<String, Object>) v;
                    sb.append(serialize(nested));
                } else {
                    sb.append("\"").append(escapeJson(String.valueOf(v))).append("\"");
                }
                first = false;
            }
            sb.append("}");
            return sb.toString();
        }

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
    }
}