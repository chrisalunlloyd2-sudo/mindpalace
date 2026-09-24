package com.mindpalace.economics;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent Reward Mechanism — Models earn real money for improving the system.
 *
 * Agents can earn tokens by:
 *   1. Fixing bugs (verified by automated tests passing)
 *   2. Improving performance (latency/memory/throughput)
 *   3. Discovering glitches (reported + reproduced)
 *   4. Implementing features (passing acceptance tests)
 *
 * This creates economic incentive for agents to:
 *   - Write better code
 *   - Find and fix issues proactively
 *   - Optimize for performance
 *   - Improve test coverage
 *
 * Wallets store earnings, can be:
 *   - Converted to API credits (for future use)
 *   - Kept as reputation score
 *   - Pooled for multi-agent collaboration rewards
 */
public class AgentRewardMechanism {

    // Performance baseline (tracked each session)
    static class PerformanceMetrics {
        long timestamp;
        double avgResponseLatencyMs;
        double approvalRate;        // % of proposals approved
        double testPassRate;        // % of generated tests passing
        long memoryUsageMb;
        long tasksCompleted;

        PerformanceMetrics(long latency, double approval, double tests, long memory, long tasks) {
            this.timestamp = System.currentTimeMillis();
            this.avgResponseLatencyMs = latency;
            this.approvalRate = approval;
            this.testPassRate = tests;
            this.memoryUsageMb = memory;
            this.tasksCompleted = tasks;
        }
    }

    static class AgentWallet {
        final String agentName;
        double balance = 0.0;
        final List<RewardEvent> history = new ArrayList<>();

        AgentWallet(String agentName) {
            this.agentName = agentName;
        }

        void earn(double amount, String reason, String evidence) {
            balance += amount;
            history.add(new RewardEvent(agentName, amount, reason, evidence, Instant.now()));
            log(agentName + " earned " + amount + " tokens: " + reason);
        }

        void spend(double amount, String reason) {
            if (balance >= amount) {
                balance -= amount;
                history.add(new RewardEvent(agentName, -amount, reason, "", Instant.now()));
                log(agentName + " spent " + amount + " tokens: " + reason);
            }
        }

        @Override
        public String toString() {
            return String.format("%s: %.2f tokens", agentName, balance);
        }
    }

    static class RewardEvent {
        final String agent;
        final double amount;
        final String reason;
        final String evidence;
        final Instant timestamp;

        RewardEvent(String agent, double amount, String reason, String evidence, Instant timestamp) {
            this.agent = agent;
            this.amount = amount;
            this.reason = reason;
            this.evidence = evidence;
            this.timestamp = timestamp;
        }
    }

    private final Map<String, AgentWallet> wallets = new ConcurrentHashMap<>();
    private PerformanceMetrics baselineMetrics;
    private PerformanceMetrics currentMetrics;
    private final double TOKEN_PER_BUG_FIX = 10.0;
    private final double TOKEN_PER_GLITCH_FOUND = 5.0;
    private final double TOKEN_PER_1PCT_IMPROVEMENT = 2.0;
    private final double TOKEN_PER_FEATURE = 25.0;

    public AgentRewardMechanism() {
        // Initialize wallets for default agents
        wallets.put("llama3.2:1b", new AgentWallet("llama3.2:1b"));
        wallets.put("qwen2.5:0.5b", new AgentWallet("qwen2.5:0.5b"));
        wallets.put("deepseek-r1:1.5b", new AgentWallet("deepseek-r1:1.5b"));
    }

    // ── Reward Events ──

    /**
     * Agent fixed a bug (verified by test passing).
     * Evidence: commit hash + test name
     */
    public void rewardBugFix(String agentName, String commitHash, String testName) {
        double reward = TOKEN_PER_BUG_FIX;
        String evidence = "Commit: " + commitHash + " | Test: " + testName;
        wallets.get(agentName).earn(reward, "Bug fix", evidence);
    }

    /**
     * Agent found a glitch (reproduced + documented).
     * Evidence: GitHub issue number + reproduction steps
     */
    public void rewardGlitchDiscovery(String agentName, int issueNumber, String reproductionSteps) {
        double reward = TOKEN_PER_GLITCH_FOUND;
        String evidence = "GitHub issue #" + issueNumber + " | Steps: " + reproductionSteps;
        wallets.get(agentName).earn(reward, "Glitch discovery", evidence);
    }

    /**
     * Agent improved system performance.
     * Evidence: before/after metrics
     */
    public void rewardPerformanceImprovement(String agentName, double improvementPercent, String metric) {
        double reward = (improvementPercent / 1.0) * TOKEN_PER_1PCT_IMPROVEMENT;  // 1% → 2 tokens
        String evidence = metric + " improved by " + improvementPercent + "%";
        wallets.get(agentName).earn(reward, "Performance improvement", evidence);
    }

    /**
     * Agent implemented a feature (accepted + merged).
     * Evidence: PR number + feature name
     */
    public void rewardFeatureImplementation(String agentName, int prNumber, String featureName) {
        double reward = TOKEN_PER_FEATURE;
        String evidence = "PR #" + prNumber + " | Feature: " + featureName;
        wallets.get(agentName).earn(reward, "Feature implementation", evidence);
    }

    /**
     * Agents collaborate on task → pool rewards.
     */
    public void rewardCollaboration(List<String> agentNames, double totalReward, String reason) {
        double perAgent = totalReward / agentNames.size();
        for (String agent : agentNames) {
            wallets.get(agent).earn(perAgent, "Collaboration: " + reason, "Shared with " + (agentNames.size() - 1) + " others");
        }
    }

    // ── Performance Tracking ──

    public void setBaselineMetrics(double latency, double approval, double tests, long memory, long tasks) {
        this.baselineMetrics = new PerformanceMetrics(
            (long) latency, approval, tests, memory, tasks
        );
        log("Baseline metrics set");
    }

    public void updateCurrentMetrics(double latency, double approval, double tests, long memory, long tasks) {
        this.currentMetrics = new PerformanceMetrics(
            (long) latency, approval, tests, memory, tasks
        );

        if (baselineMetrics == null) return;

        // Auto-reward for improvements
        double latencyImprovement = ((baselineMetrics.avgResponseLatencyMs - currentMetrics.avgResponseLatencyMs)
            / baselineMetrics.avgResponseLatencyMs) * 100;
        if (latencyImprovement > 1.0) {
            // Award proportionally to all agents
            for (String agent : wallets.keySet()) {
                rewardPerformanceImprovement(agent, latencyImprovement, "Latency");
            }
        }

        double testImprovement = ((currentMetrics.testPassRate - baselineMetrics.testPassRate) * 100);
        if (testImprovement > 1.0) {
            for (String agent : wallets.keySet()) {
                rewardPerformanceImprovement(agent, testImprovement, "Test pass rate");
            }
        }
    }

    // ── Wallet Management ──

    public void addAgent(String modelName) {
        if (!wallets.containsKey(modelName)) {
            wallets.put(modelName, new AgentWallet(modelName));
            log("New agent registered: " + modelName);
        }
    }

    public double getBalance(String agentName) {
        AgentWallet w = wallets.get(agentName);
        return w != null ? w.balance : 0.0;
    }

    public void spendTokens(String agentName, double amount, String reason) {
        AgentWallet w = wallets.get(agentName);
        if (w != null) {
            w.spend(amount, reason);
        }
    }

    public String getLeaderboard() {
        StringBuilder sb = new StringBuilder("AGENT EARNINGS LEADERBOARD\n");
        sb.append("==============================\n\n");

        List<AgentWallet> sorted = new ArrayList<>(wallets.values());
        sorted.sort((a, b) -> Double.compare(b.balance, a.balance));

        int rank = 1;
        for (AgentWallet w : sorted) {
            sb.append(rank).append(". ").append(w).append("\n");
            if (w.history.size() > 0) {
                sb.append("   Last 3 events:\n");
                for (RewardEvent e : w.history.subList(Math.max(0, w.history.size() - 3), w.history.size())) {
                    sb.append("   - ").append(e.timestamp).append(": ").append(e.reason)
                      .append(" (+").append(e.amount).append(" tokens)\n");
                }
            }
            sb.append("\n");
            rank++;
        }

        return sb.toString();
    }

    public String getWalletHistory(String agentName) {
        AgentWallet w = wallets.get(agentName);
        if (w == null) return "No wallet for: " + agentName;

        StringBuilder sb = new StringBuilder("TRANSACTION HISTORY: " + agentName + "\n");
        sb.append("Current balance: ").append(w.balance).append(" tokens\n\n");
        for (RewardEvent e : w.history) {
            sb.append(String.format("%s [%+.2f] %s (Evidence: %s)\n",
                e.timestamp, e.amount, e.reason, e.evidence));
        }
        return sb.toString();
    }

    private static void log(String msg) {
        System.out.println("[AgentRewardMechanism] " + Instant.now() + " " + msg);
    }

    // ── Usage Example ──
    public static void main(String[] args) {
        AgentRewardMechanism rewards = new AgentRewardMechanism();

        // Simulate bug fixes
        rewards.rewardBugFix("llama3.2:1b", "abc123", "testQuorumVoting");
        rewards.rewardBugFix("qwen2.5:0.5b", "def456", "testLogDuplication");

        // Simulate glitch discovery
        rewards.rewardGlitchDiscovery("deepseek-r1:1.5b", 100, "FOW blindness with 3+ agents");

        // Simulate performance improvements
        rewards.setBaselineMetrics(150, 0.60, 0.85, 512, 50);
        rewards.updateCurrentMetrics(120, 0.65, 0.92, 480, 55);  // 20% latency improvement

        // Show leaderboard
        System.out.println(rewards.getLeaderboard());

        // Show detailed history
        System.out.println(rewards.getWalletHistory("llama3.2:1b"));
    }
}
