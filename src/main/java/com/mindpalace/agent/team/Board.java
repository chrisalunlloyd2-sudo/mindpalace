package com.mindpalace.agent.team;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Board — the one shared message log a team talks through. Members never call each other: each turn reads the board and
 * appends one message, so the whole conversation is replayable and a bot can check it line by line.
 */
public final class Board {

    /** One message on the board. */
    public record Message(String from, String text, int round) {}

    private final String task;
    private final List<Message> log = new ArrayList<>();
    private int round;

    public Board(String task) { this.task = task == null ? "" : task; }

    public String task() { return task; }
    public int round() { return round; }
    void nextRound() { round++; }

    void post(String from, String text) { log.add(new Message(from, text == null ? "" : text, round)); }

    public List<Message> log() { return Collections.unmodifiableList(log); }

    /** The newest message, or null on an empty board. */
    public Message last() { return log.isEmpty() ? null : log.get(log.size() - 1); }

    /** The newest message from one member, or null. */
    public Message lastFrom(String name) {
        for (int i = log.size() - 1; i >= 0; i--) if (log.get(i).from().equals(name)) return log.get(i);
        return null;
    }

    /** The task plus the log as plain text: what an LLM member is shown on its turn. */
    public String transcript() {
        StringBuilder sb = new StringBuilder("TASK: ").append(task).append('\n');
        for (Message m : log) sb.append('[').append(m.from()).append("] ").append(m.text()).append('\n');
        return sb.toString();
    }
}
