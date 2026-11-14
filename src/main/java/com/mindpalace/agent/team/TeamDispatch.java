package com.mindpalace.agent.team;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mindpalace.agent.ModelConfig;
import com.mindpalace.agent.ModelScheduler;
import com.mindpalace.agent.OllamaClient;
import com.mindpalace.agent.sims.AdapterType;
import com.mindpalace.agent.sims.LoRASwitcher;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * TeamDispatch — gives a quorum-approved proposal to agent teams. Two teams from the roster (the scoreboard's pick) each
 * write a short plan for it, one after the other; the deterministic PlanCheck judges; the winner's plan is appended to
 * team_ledger.jsonl with the proposal's cycle id, and the scoreboard is saved.
 *
 * Off unless -Dmindpalace.teams=true. One proposal at a time: offer() refuses while a job runs, it never queues. Every
 * model turn is a job on the ModelScheduler, so teams obey the same one-call-at-a-time and spacing rules as the agents.
 */
public final class TeamDispatch {

    public static final String FLAG = "mindpalace.teams";
    private static final long TURN_TIMEOUT_MS = Long.getLong("mindpalace.teams.turnTimeoutMs", 20 * 60 * 1000L);
    private static final int TEAMS_PER_TASK = 2;

    private static final String PLAN_SYSTEM = "You plan work on a codebase. Reply with 2 to 5 numbered steps (1. 2. 3.) and nothing else. "
        + "Each step names one concrete action. If the log shows a FIX line, apply it.";
    private static final String CRITIC_SYSTEM = "You review a plan. If every step is a concrete action, reply with the single word PASS. "
        + "Otherwise reply with one line saying what to fix.";

    public static boolean enabled() { return Boolean.getBoolean(FLAG); }

    private final Arena arena;
    private final Scoreboard scoreboard;
    private final List<Team> roster;
    private final Path dir;
    private final Consumer<String> log;
    private final AtomicBoolean busy = new AtomicBoolean();
    private final ExecutorService lane = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "team-lane");
        t.setDaemon(true);
        return t;
    });

    /** dir holds team_ledger.jsonl and team_scores.tsv. */
    public TeamDispatch(TeamRunner runner, List<Team> roster, Path dir, Consumer<String> log) {
        this.roster = List.copyOf(roster);
        this.dir = dir;
        this.log = log == null ? s -> { } : log;
        this.scoreboard = Scoreboard.load(dir.resolve("team_scores.tsv"));
        this.arena = new Arena(runner, scoreboard);
    }

    /** The game's wiring: local models, the shared LoRA switcher, every turn through the scheduler. */
    public static TeamDispatch forGame(ModelScheduler scheduler, OllamaClient ollama, LoRASwitcher lora, Consumer<String> log) {
        String home = System.getProperty("aigen.home", System.getenv("AIGEN_HOME"));
        if (home == null) home = System.getProperty("user.home") + "/AIGEN_SYS";
        TeamRunner runner = new TeamRunner(lora, TeamRunner.scheduled(scheduler, TURN_TIMEOUT_MS));
        return new TeamDispatch(runner, defaultRoster(Member.local(ollama)), Paths.get(home), log);
    }

    /** The combos the game starts with: LLM + bot (two different models) and LLM + bot + LLM critic. */
    public static List<Team> defaultRoster(Member.Llm llm) {
        Member planner = Member.llm("planner", ModelConfig.TOOL_MODEL, AdapterType.ANALYSIS, PLAN_SYSTEM, llm);
        Member coder = Member.llm("coder", ModelConfig.CODE_MODEL, AdapterType.CODE, PLAN_SYSTEM, llm);
        Member critic = Member.llm("critic", ModelConfig.CRITIC_MODEL, AdapterType.ANALYSIS, CRITIC_SYSTEM, llm);
        return List.of(
            new Team("planner+bot", Team.Pattern.DRAFT_CHECK, List.of(planner, PlanCheck.bot("planner")), 2),
            new Team("coder+bot", Team.Pattern.DRAFT_CHECK, List.of(coder, PlanCheck.bot("coder")), 2),
            // the bot goes first: a draft it rejects never costs a critic turn
            new Team("planner+bot+critic", Team.Pattern.DRAFT_CHECK, List.of(planner, PlanCheck.bot("planner"), critic), 2));
    }

    /** Hands a proposal to the team lane. False when a job is already running (the proposal is skipped, not queued). */
    public boolean offer(String cycleId, String proposalType, String text) {
        if (!busy.compareAndSet(false, true)) return false;
        try {
            lane.execute(() -> {
                try { runOnce(cycleId, proposalType, text); }
                catch (Throwable t) { log.accept("[Teams] job failed: " + t); }
                finally { busy.set(false); }
            });
        } catch (RuntimeException e) {
            busy.set(false);
            return false;
        }
        return true;
    }

    /** Runs the competition on the calling thread and returns the ledger row it wrote. */
    public JsonObject runOnce(String cycleId, String proposalType, String text) {
        String task = "Proposal (" + proposalType + "): " + text + "\nWrite a plan of 2 to 5 numbered steps to carry it out.";
        Arena.Outcome out = arena.compete(scoreboard.pick(roster, TEAMS_PER_TASK), task, PlanCheck::score);

        JsonObject row = new JsonObject();
        row.addProperty("cycle_id", cycleId);
        row.addProperty("timestamp_iso", Instant.now().toString());
        row.addProperty("proposal_type", proposalType);
        JsonArray teams = new JsonArray();
        for (Arena.Entry e : out.entries()) {
            JsonObject t = new JsonObject();
            t.addProperty("team", e.team().name());
            t.addProperty("success", e.result().success());
            t.addProperty("score", e.score());
            t.addProperty("turns", e.result().turns());
            t.addProperty("adapter_swaps", e.result().adapterSwaps());
            t.addProperty("why", e.result().why());
            teams.add(t);
        }
        row.add("teams", teams);
        row.addProperty("winner", out.winner() == null ? null : out.winner().team().name());
        row.addProperty("plan", out.winner() == null ? null : out.winner().result().answer());
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("team_ledger.jsonl"), row + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            scoreboard.save(dir.resolve("team_scores.tsv"));
        } catch (Exception e) {
            log.accept("[Teams] could not write the ledger: " + e.getMessage());
        }
        log.accept("[Teams] " + cycleId + ": " + (out.winner() == null ? "no team produced a plan" : "winner " + out.winner().team().name()
            + " (score " + out.winner().score() + ")"));
        return row;
    }

    public boolean busy() { return busy.get(); }
}
