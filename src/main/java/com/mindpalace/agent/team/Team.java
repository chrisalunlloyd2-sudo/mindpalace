package com.mindpalace.agent.team;

import com.mindpalace.agent.sims.AdapterType;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Team — members plus how they take turns.
 *   PIPELINE     each member acts once, in order; the answer is the last message.
 *   DRAFT_CHECK  member 0 drafts, the rest check; a check that starts with "PASS" accepts the draft, anything else is
 *                feedback the drafter sees next round, up to maxRounds.
 * The combos below are the common shapes; any list of members works.
 */
public record Team(String name, Pattern pattern, List<Member> members, int maxRounds) {

    public enum Pattern { PIPELINE, DRAFT_CHECK }

    public Team {
        if (members == null || members.isEmpty()) throw new IllegalArgumentException("a team needs members");
        if (pattern == Pattern.DRAFT_CHECK && members.size() < 2) throw new IllegalArgumentException("DRAFT_CHECK needs a checker");
        members = List.copyOf(members);
        maxRounds = Math.max(1, maxRounds);
    }

    /** An LLM drafts, a deterministic bot checks. The cheapest pair: one model, no second model ever loads. */
    public static Team llmPlusBot(Member llm, Member bot, int maxRounds) {
        return new Team(llm.name() + "+" + bot.name(), Pattern.DRAFT_CHECK, List.of(llm, bot), maxRounds);
    }

    /** Two LLMs (or one model under two adapters): a worker and a critic, taking turns. */
    public static Team twoLlms(Member worker, Member critic, int maxRounds) {
        return new Team(worker.name() + "+" + critic.name(), Pattern.DRAFT_CHECK, List.of(worker, critic), maxRounds);
    }

    /** One LLM with a toolset, checked by a bot. */
    public static Team llmWithTools(String name, String model, AdapterType adapter, String system, Member.Llm llm,
                                    Map<String, Function<String, String>> toolset, Member checker, int maxRounds) {
        return new Team(name, Pattern.DRAFT_CHECK,
            List.of(Member.tools(name, model, adapter, system, llm, toolset), checker), maxRounds);
    }
}
