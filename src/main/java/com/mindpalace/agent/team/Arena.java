package com.mindpalace.agent.team;

import java.util.ArrayList;
import java.util.List;

/**
 * Arena — compete mode. Several teams try the same task, one after another (never two at once), and a deterministic judge
 * scores each finished run. The winner is the highest score; a tie goes to the team that used fewer turns, then to the one
 * listed first. A team that failed scores -1 and can never win. Every run is recorded on the scoreboard, so the combos
 * that keep winning are the ones picked next time.
 */
public final class Arena {

    /** Scores a run that succeeded: 0 or more, higher is better. Must be deterministic (a bot, not a model). */
    @FunctionalInterface
    public interface Judge { int score(String task, TeamRunner.Result result); }

    public record Entry(Team team, TeamRunner.Result result, int score) {}

    /** winner is null when no team succeeded. */
    public record Outcome(Entry winner, List<Entry> entries) {}

    private final TeamRunner runner;
    private final Scoreboard scoreboard;

    public Arena(TeamRunner runner, Scoreboard scoreboard) { this.runner = runner; this.scoreboard = scoreboard; }

    public Outcome compete(List<Team> teams, String task, Judge judge) {
        List<Entry> entries = new ArrayList<>();
        Entry best = null;
        for (Team t : teams) {
            TeamRunner.Result r = runner.run(t, task);
            int score = -1;
            if (r.success()) {
                try { score = Math.max(0, judge.score(task, r)); } catch (RuntimeException e) { score = -1; }
            }
            Entry e = new Entry(t, r, score);
            entries.add(e);
            if (score >= 0 && (best == null || score > best.score()
                || (score == best.score() && r.turns() < best.result().turns()))) best = e;
        }
        if (scoreboard != null) for (Entry e : entries) scoreboard.record(e.team().name(), e == best);
        return new Outcome(best, List.copyOf(entries));
    }
}
