package com.mindpalace.agent.team;

import com.mindpalace.agent.sims.LoRASwitcher;

/**
 * TeamRunner — runs a team one turn at a time on one thread. Before an LLM turn it rotates the LoRA adapter to that
 * member's (only when it differs, so same-adapter turns cost nothing); a bot turn touches no model. A member that throws
 * ends the run as a failure: a team can fail, it can never take the game down. Hard cap on turns so a chatty pair cannot
 * loop forever.
 */
public final class TeamRunner {

    public static final int MAX_TURNS = 32;

    /** What a run produced. */
    public record Result(boolean success, String answer, Board board, int turns, int adapterSwaps, String why) {}

    private final LoRASwitcher lora;

    public TeamRunner(LoRASwitcher lora) { this.lora = lora; }

    public Result run(Team team, String task) {
        Board b = new Board(task);
        int[] turns = {0}, swaps = {0};
        try {
            if (team.pattern() == Team.Pattern.PIPELINE) {
                for (Member m : team.members()) turn(m, b, turns, swaps);
                Board.Message last = b.last();
                return new Result(true, last == null ? "" : last.text(), b, turns[0], swaps[0], "pipeline done");
            }
            Member drafter = team.members().get(0);
            for (int r = 0; r < team.maxRounds(); r++, b.nextRound()) {
                String draft = turn(drafter, b, turns, swaps);
                boolean allPass = true;
                for (Member checker : team.members().subList(1, team.members().size())) {
                    if (!turn(checker, b, turns, swaps).trim().startsWith("PASS")) { allPass = false; break; }
                }
                if (allPass) return new Result(true, draft, b, turns[0], swaps[0], "accepted in round " + (r + 1));
            }
            return new Result(false, "", b, turns[0], swaps[0], "no draft passed in " + team.maxRounds() + " rounds");
        } catch (TurnCap e) {
            return new Result(false, "", b, turns[0], swaps[0], "turn cap " + MAX_TURNS + " hit");
        } catch (RuntimeException e) {
            return new Result(false, "", b, turns[0], swaps[0], "member failed: " + e.getClass().getSimpleName()
                + (e.getMessage() == null ? "" : " " + e.getMessage()));
        }
    }

    private String turn(Member m, Board b, int[] turns, int[] swaps) {
        if (turns[0] >= MAX_TURNS) throw new TurnCap();
        if (m.kind() != Member.Kind.BOT && m.adapter() != null && lora != null && lora.currentType() != m.adapter()) {
            lora.switchAdapter(m.adapter());
            swaps[0]++;
        }
        turns[0]++;
        String out = m.act(b);
        b.post(m.name(), out);
        return out == null ? "" : out;
    }

    private static final class TurnCap extends RuntimeException {}
}
