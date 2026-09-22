package com.mindpalace.integration;

import com.mindpalace.agent.sims.*;
import java.io.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * Bridge between Reddit suggestions and the quorum voting system.
 * Periodically fetches top posts from /r/mindpalace and registers them
 * as quorum proposals. Community upvotes → proposal weight.
 *
 * This enables the feedback loop: Reddit community suggests → quorum votes →
 * Hermes executes approved features.
 */
public class RedditToQuorumBridge {
    private final RedditOAuthClient reddit;
    private final WeightedQuorumVote quorum;
    private final File ledgerFile;
    private final long pollIntervalMs;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private long proposalCounter = 0;

    // Ledger tracks which Reddit posts we've already proposed
    // (prevents duplicate proposals for the same post)
    private final Set<String> seenRedditIds = ConcurrentHashMap.newKeySet();

    public RedditToQuorumBridge(RedditOAuthClient reddit, WeightedQuorumVote quorum, String ledgerPath, long pollIntervalMs) {
        this.reddit = reddit;
        this.quorum = quorum;
        this.ledgerFile = new File(ledgerPath);
        this.pollIntervalMs = pollIntervalMs;
        loadLedger();
    }

    /**
     * Start polling Reddit for suggestions. Runs once every pollIntervalMs.
     */
    public void startPolling() {
        scheduler.scheduleAtFixedRate(this::pollAndPropose, 0, pollIntervalMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Fetch top posts from Reddit and register as quorum proposals.
     * Each post becomes a proposal; score and comment count influence initial weight.
     */
    private void pollAndPropose() {
        try {
            List<RedditOAuthClient.RedditSuggestion> suggestions = reddit.fetchSuggestions(20);
            int newProposals = 0;

            for (RedditOAuthClient.RedditSuggestion sug : suggestions) {
                if (seenRedditIds.contains(sug.id)) {
                    continue; // Already proposed
                }

                // Build proposal text from Reddit suggestion
                String proposalText = String.format(
                    "%s (by u/%s, %d upvotes, %d comments)",
                    sug.title, sug.author, sug.score, sug.numComments
                );

                // Register as a quorum proposal with type "community_suggestion"
                String proposalId = "reddit-" + sug.id + "-" + System.currentTimeMillis();
                quorum.registerProposal(proposalId, proposalText, new HexCoord(0, 0), "community_suggestion");

                // Weight the proposal by Reddit score (more upvotes = higher timeSlot preference)
                // Normalize score to 0.0-1.0 range (assume max 1000 upvotes)
                double timeSlot = Math.min(1.0, sug.score / 1000.0);
                var proposal = quorum.getProposal(proposalId);
                if (proposal != null) {
                    // Store the source URL in suggested actions for audit trail
                    proposal.suggestedActions.put("reddit_url", Collections.singletonList(sug.url));
                }

                seenRedditIds.add(sug.id);
                newProposals++;
                log("Registered Reddit suggestion: " + proposalText);
            }

            if (newProposals > 0) {
                log("Polled Reddit: " + newProposals + " new suggestions registered");
                saveLedger();
            }
        } catch (Exception e) {
            log("ERROR polling Reddit: " + e.getMessage());
        }
    }

    private void loadLedger() {
        if (!ledgerFile.exists()) return;
        try (BufferedReader br = new BufferedReader(new FileReader(ledgerFile))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#")) {
                    seenRedditIds.add(line);
                }
            }
            log("Loaded ledger: " + seenRedditIds.size() + " seen Reddit posts");
        } catch (Exception e) {
            log("ERROR loading ledger: " + e.getMessage());
        }
    }

    private void saveLedger() {
        try {
            ledgerFile.getParentFile().mkdirs();
            try (PrintWriter pw = new PrintWriter(new FileWriter(ledgerFile))) {
                pw.println("# Reddit-to-Quorum ledger (append-only)");
                pw.println("# Updated: " + Instant.now());
                for (String id : seenRedditIds) {
                    pw.println(id);
                }
            }
        } catch (Exception e) {
            log("ERROR saving ledger: " + e.getMessage());
        }
    }

    public void shutdown() {
        scheduler.shutdownNow();
    }

    private void log(String msg) {
        System.out.println("[RedditToQuorumBridge] " + Instant.now() + " " + msg);
    }
}
