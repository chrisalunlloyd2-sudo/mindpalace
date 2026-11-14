package com.mindpalace.agent.team;

import com.mindpalace.agent.ModelScheduler;
import com.mindpalace.agent.sims.LoRASwitcher;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * TeamRunner — runs a team one turn at a time. Before an LLM turn it rotates the LoRA adapter to that member's (only when
 * it differs, so same-adapter turns cost nothing); a bot turn touches no model. The swap and the call run as ONE job on the
 * lane, so no other model call can land between them. A member that throws ends the run as a failure: a team can fail, it
 * can never take the game down. Hard cap on turns so a chatty pair cannot loop forever.
 */
public final class TeamRunner {

    public static final int MAX_TURNS = 32;

    /** What a run produced. */
    public record Result(boolean success, String answer, Board board, int turns, int adapterSwaps, String why) {}

    /** Where a model turn runs. Bot turns never use it. */
    @FunctionalInterface
    public interface Lane { String run(Supplier<String> turn); }

    /** Runs the turn on the calling thread: tests, and tools that own the model themselves. */
    public static final Lane INLINE = Supplier::get;

    /**
     * The game's lane: each model turn is one job on the ModelScheduler's single worker, so it is serialized with every other
     * autonomous call, spaced and resource-fenced like them. Never call run() from the scheduler's own worker (it would wait
     * on itself). A turn that times out is abandoned: when its job finally comes up it does nothing.
     */
    public static Lane scheduled(ModelScheduler scheduler, long timeoutMs) {
        return turn -> {
            CompletableFuture<String> f = new CompletableFuture<>();
            AtomicBoolean abandoned = new AtomicBoolean();
            scheduler.submitToolRound(() -> {
                if (abandoned.get()) return;
                try { f.complete(turn.get()); } catch (Throwable t) { f.completeExceptionally(t); }
            });
            try {
                return f.get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                abandoned.set(true);
                throw new IllegalStateException("turn timed out after " + timeoutMs + " ms");
            } catch (ExecutionException e) {
                Throwable c = e.getCause();
                throw c instanceof RuntimeException r ? r : new IllegalStateException(String.valueOf(c));
            } catch (InterruptedException e) {
                abandoned.set(true);
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted");
            }
        };
    }

    private final LoRASwitcher lora;
    private final Lane lane;

    public TeamRunner(LoRASwitcher lora) { this(lora, INLINE); }

    public TeamRunner(LoRASwitcher lora, Lane lane) { this.lora = lora; this.lane = lane == null ? INLINE : lane; }

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
        turns[0]++;
        String out;
        if (m.kind() == Member.Kind.BOT) {
            out = m.act(b);
        } else {
            out = lane.run(() -> {
                if (m.adapter() != null && lora != null && lora.currentType() != m.adapter()) {
                    lora.switchAdapter(m.adapter());
                    swaps[0]++;
                }
                return m.act(b);
            });
        }
        b.post(m.name(), out);
        return out == null ? "" : out;
    }

    private static final class TurnCap extends RuntimeException {}
}
