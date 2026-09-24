package com.mindpalace.integration;

import java.io.*;
import java.net.http.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import com.google.gson.*;

/**
 * GitHub issue poller → local TASK file bridge.
 *
 * The cloud routine (360 oversight) files GitHub issues labeled 'hermes-queue'
 * every 6 hours. This bridge polls those issues and converts them to local
 * TASK files that Hermes can execute. Closes the gap between cloud analysis
 * and local task queue.
 *
 * Flow:
 *   Cloud routine: analyzes repo → files issues (hermes-queue label)
 *   GitHub: issues persist with all metadata
 *   Local bridge: polls every 30 min → converts to TASK files
 *   Hermes: executes TASK files as normal
 */
public class GitHubPollBridge {
    private static final String GITHUB_API = "https://api.github.com";
    private static final String HERMES_QUEUE_LABEL = "hermes-queue";

    private final String repoOwner;
    private final String repoName;
    private final String githubToken;  // GitHub Personal Access Token
    private final Path taskDir;
    private final Path ledgerFile;
    private final long pollIntervalMs;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private final HttpClient httpClient = HttpClient.newHttpClient();

    // Track processed issues (deduplication)
    private final Set<Integer> processedIssueNumbers = ConcurrentHashMap.newKeySet();

    public GitHubPollBridge(String repoOwner, String repoName, String githubToken,
                            String taskDirPath, String ledgerPath, long pollIntervalMs) {
        this.repoOwner = repoOwner;
        this.repoName = repoName;
        this.githubToken = githubToken;
        this.taskDir = Paths.get(taskDirPath);
        this.ledgerFile = Paths.get(ledgerPath);
        this.pollIntervalMs = pollIntervalMs;
        loadLedger();
    }

    /**
     * Start polling GitHub for hermes-queue issues.
     */
    public void startPolling() {
        scheduler.scheduleAtFixedRate(this::pollAndCreateTasks, 0, pollIntervalMs, TimeUnit.MILLISECONDS);
        log("GitHub poll bridge started: polling every " + pollIntervalMs + "ms");
    }

    /**
     * Fetch issues labeled 'hermes-queue' and convert to TASK files.
     */
    private void pollAndCreateTasks() {
        try {
            List<GitHubIssue> issues = fetchHermesQueueIssues();
            int newTasks = 0;

            for (GitHubIssue issue : issues) {
                if (processedIssueNumbers.contains(issue.number)) {
                    continue; // Already converted
                }

                // Create TASK file from issue
                boolean created = createTaskFile(issue);
                if (created) {
                    processedIssueNumbers.add(issue.number);
                    newTasks++;
                    log("Converted GitHub issue #" + issue.number + " → TASK file");
                }
            }

            if (newTasks > 0) {
                log("Polled GitHub: " + newTasks + " new tasks created");
                saveLedger();
            }
        } catch (Exception e) {
            log("ERROR polling GitHub: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Fetch all open issues with 'hermes-queue' label.
     */
    private List<GitHubIssue> fetchHermesQueueIssues() throws Exception {
        String url = String.format(
            "%s/repos/%s/%s/issues?labels=%s&state=open&per_page=100",
            GITHUB_API, repoOwner, repoName, HERMES_QUEUE_LABEL
        );

        var request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Accept", "application/vnd.github.v3+json")
            .header("Authorization", "token " + githubToken)
            .GET()
            .build();

        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("GitHub API error: " + response.statusCode() + " " + response.body());
        }

        return parseIssuesFromJson(response.body());
    }

    /**
     * Parse GitHub issues from JSON response.
     */
    private List<GitHubIssue> parseIssuesFromJson(String json) throws Exception {
        List<GitHubIssue> issues = new ArrayList<>();
        JsonArray arr = JsonParser.parseString(json).getAsJsonArray();

        for (JsonElement elem : arr) {
            JsonObject obj = elem.getAsJsonObject();
            int number = obj.get("number").getAsInt();
            String title = obj.get("title").getAsString();
            String body = obj.has("body") && !obj.get("body").isJsonNull()
                ? obj.get("body").getAsString()
                : "";
            String author = obj.getAsJsonObject("user").get("login").getAsString();
            String createdAt = obj.get("created_at").getAsString();
            String htmlUrl = obj.get("html_url").getAsString();

            issues.add(new GitHubIssue(number, title, body, author, createdAt, htmlUrl));
        }

        return issues;
    }

    /**
     * Create a TASK file from a GitHub issue.
     * Naming: TASK_NNNN_github_issue_NUMBER_SLUG.md
     */
    private boolean createTaskFile(GitHubIssue issue) {
        try {
            taskDir.toFile().mkdirs();

            // Generate task number (hash-based, 4-digit)
            int taskNum = 1000 + Math.abs(issue.number % 10000);

            // Slug from title
            String slug = issue.title.toLowerCase()
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_|_$", "")
                .substring(0, Math.min(30, issue.title.length() - 1));

            String filename = String.format("TASK_%04d_github_%d_%s.md", taskNum, issue.number, slug);
            Path taskPath = taskDir.resolve(filename);

            // Build task content
            String content = buildTaskContent(issue);
            Files.writeString(taskPath, content, StandardCharsets.UTF_8);

            return true;
        } catch (Exception e) {
            log("ERROR creating TASK file for issue #" + issue.number + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Build task file content from GitHub issue.
     */
    private String buildTaskContent(GitHubIssue issue) {
        return "---\n" +
               "title: " + issue.title + "\n" +
               "source: github_issue_#" + issue.number + "\n" +
               "author: " + issue.author + "\n" +
               "created: " + issue.createdAt + "\n" +
               "url: " + issue.htmlUrl + "\n" +
               "---\n\n" +
               "# GitHub Issue #" + issue.number + "\n\n" +
               "**Title**: " + issue.title + "\n\n" +
               "**Author**: @" + issue.author + "\n\n" +
               "**Source**: [GitHub Issue #" + issue.number + "](" + issue.htmlUrl + ")\n\n" +
               "**Created**: " + issue.createdAt + "\n\n" +
               "## Description\n\n" +
               (issue.body.isEmpty() ? "(No description provided)" : issue.body) + "\n\n" +
               "## Execution Notes\n\n" +
               "- [ ] Read full issue context at: " + issue.htmlUrl + "\n" +
               "- [ ] Verify requirements with issue author\n" +
               "- [ ] Implement solution\n" +
               "- [ ] Post update as comment on GitHub issue\n" +
               "- [ ] Close issue when complete\n\n" +
               "---\n" +
               "*Auto-generated by GitHubPollBridge from hermes-queue label*\n" +
               "*Converted: " + Instant.now() + "\n";
    }

    private void loadLedger() {
        if (!ledgerFile.toFile().exists()) return;
        try (BufferedReader br = new BufferedReader(new FileReader(ledgerFile.toFile()))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#")) {
                    try {
                        int issueNum = Integer.parseInt(line);
                        processedIssueNumbers.add(issueNum);
                    } catch (NumberFormatException e) {
                        // Skip malformed lines
                    }
                }
            }
            log("Loaded ledger: " + processedIssueNumbers.size() + " processed issues");
        } catch (Exception e) {
            log("ERROR loading ledger: " + e.getMessage());
        }
    }

    private void saveLedger() {
        try {
            ledgerFile.getParent().toFile().mkdirs();
            try (PrintWriter pw = new PrintWriter(new FileWriter(ledgerFile.toFile()))) {
                pw.println("# GitHub-to-TASK ledger (append-only)");
                pw.println("# Updated: " + Instant.now());
                pw.println("# Format: issue_number (one per line)");
                for (Integer num : processedIssueNumbers) {
                    pw.println(num);
                }
            }
        } catch (Exception e) {
            log("ERROR saving ledger: " + e.getMessage());
        }
    }

    public void shutdown() {
        scheduler.shutdownNow();
        log("GitHub poll bridge stopped");
    }

    private void log(String msg) {
        System.out.println("[GitHubPollBridge] " + Instant.now() + " " + msg);
    }

    // ── Data class ──

    public static class GitHubIssue {
        public final int number;
        public final String title;
        public final String body;
        public final String author;
        public final String createdAt;
        public final String htmlUrl;

        GitHubIssue(int number, String title, String body, String author, String createdAt, String htmlUrl) {
            this.number = number;
            this.title = title;
            this.body = body;
            this.author = author;
            this.createdAt = createdAt;
            this.htmlUrl = htmlUrl;
        }

        @Override
        public String toString() {
            return String.format("GitHubIssue[#%d by @%s]: %s", number, author, title);
        }
    }
}
