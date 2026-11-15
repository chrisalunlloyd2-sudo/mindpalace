package com.mindpalace.agent.team;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Scoreboard — runs and wins per team name, kept across restarts in a small tab-separated file. pick() is how the game
 * gets better over time without a model deciding: the best win rates go again, plus the least-tried team so a new or
 * unlucky combo still gets its chance. No randomness: the same board always picks the same teams.
 */
public final class Scoreboard {

    private final Map<String, int[]> rows = new LinkedHashMap<>();   // name -> {runs, wins}

    public synchronized void record(String team, boolean won) {
        int[] r = rows.computeIfAbsent(team, k -> new int[2]);
        r[0]++;
        if (won) r[1]++;
    }

    public synchronized int runs(String team) { int[] r = rows.get(team); return r == null ? 0 : r[0]; }
    public synchronized int wins(String team) { int[] r = rows.get(team); return r == null ? 0 : r[1]; }

    /** Wins per run; a team that has never run counts as 0. */
    public synchronized double winRate(String team) { int n = runs(team); return n == 0 ? 0 : (double) wins(team) / n; }

    /** What pick() sorts by: (wins + 1) / (runs + 2), so one lucky win does not outrank a long good record. */
    private double steadyRate(String team) { return (wins(team) + 1.0) / (runs(team) + 2.0); }

    /** k teams from the roster: the k-1 best steady win rates, then the least-run team not already chosen. Ties keep roster order. */
    public synchronized List<Team> pick(List<Team> roster, int k) {
        if (k >= roster.size()) return List.copyOf(roster);
        if (k <= 0) return List.of();
        List<Team> byRate = new ArrayList<>(roster);
        byRate.sort(Comparator.comparingDouble((Team t) -> -steadyRate(t.name())));   // stable: ties keep roster order
        List<Team> out = new ArrayList<>(byRate.subList(0, k - 1));
        Team explore = null;
        for (Team t : roster) {
            if (out.contains(t)) continue;
            if (explore == null || runs(t.name()) < runs(explore.name())) explore = t;
        }
        out.add(explore);
        return out;
    }

    /** Writes the board to a temp file and moves it into place, so a crash mid-write cannot leave half a board. */
    public synchronized void save(Path file) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (var e : rows.entrySet())
            sb.append(e.getKey().replace('\t', ' ').replace('\n', ' ')).append('\t').append(e.getValue()[0]).append('\t').append(e.getValue()[1]).append('\n');
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }

    /** A missing file is an empty board; a line that does not parse is skipped. */
    public static Scoreboard load(Path file) {
        Scoreboard s = new Scoreboard();
        if (file == null || !Files.isRegularFile(file)) return s;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String[] p = line.split("\t");
                if (p.length != 3) continue;
                try {
                    int runs = Integer.parseInt(p[1].trim()), wins = Integer.parseInt(p[2].trim());
                    if (runs >= 0 && wins >= 0 && wins <= runs) s.rows.put(p[0], new int[]{runs, wins});
                } catch (NumberFormatException ignored) { }
            }
        } catch (IOException ignored) { }
        return s;
    }
}
