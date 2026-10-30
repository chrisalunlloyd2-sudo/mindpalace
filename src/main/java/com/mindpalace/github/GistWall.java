package com.mindpalace.github;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Fleet "gist wall" data source — surfaces Chris's shared fleet gist
 * (fleet_status.jsonl + workflow_logits.jsonl) and the word-library success
 * paths (BDI_FSM_DAGs word_library/success.jsonl) as short readable lines for an
 * in-game wall/panel.
 *
 * Deterministic + ADD-only + paced: the fetch runs on a background daemon thread
 * with a 60s cooldown, so it never blocks the render loop and "nothing runs for
 * free". If the network/token is unavailable the wall shows an offline line and
 * the game keeps running.
 */
public class GistWall {
    private static final String API_BASE = "https://api.github.com";
    private static final String FLEET_GIST_ID = "04debe0724a26fdee12a9e6d82c4eb56";
    private static final String WORDLIB_PATH = "word_library/success.jsonl";
    private static final long COOLDOWN_MS = 60_000L;
    // MP-012 (#158): deeper tails so the paged wall has something to page
    // through — 10 fleet status rows fill one full 11-row page.
    private static final int MAX_STATUS = 10;
    private static final int MAX_LOGIT = 6;
    private static final int MAX_WORDLIB = 6;
    /** MP-012: page quantum — renderers slice {@code lines} with pageSlice()
     *  in 11-row pages; the physical panel shows all 11 + a footer, the HUD
     *  fallback caps its rows but shares the same page index. */
    public static final int PAGE_ROWS = 11;
    /** MP-012: fixed-column grid geometry (formatStatusLine contract:
     *  agent 9ch, status 13ch; statusLevel() parses this exact slice). */
    private static final int AGENT_COL = 9;
    private static final int STATUS_COL = 13;

    private final OkHttpClient http;
    private String token;
    private List<String> lines = new ArrayList<>();
    private long lastFetchMs = 0L;
    private boolean fetching = false;
    private boolean fetched = false;
    private String lastError = null;

    public GistWall() {
        http = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build();
    }

    public void setToken(String t) { this.token = t; }
    public boolean isFetched() { return fetched; }
    public String getLastError() { return lastError; }

    // ---- MP-012 (#158): paging -------------------------------------------------
    // Page state lives here (not in the renderer) so the physical panel — the
    // primary surface — and the HUD fallback flip together with one index.
    private int page = 0;

    /** Seed the wall lines directly (offline demos / self-test); paging clamps at read. */
    public synchronized void setLines(List<String> lines) { this.lines = new ArrayList<>(lines); }
    public synchronized int getPage() { return page; }
    public synchronized void nextPage() { page = normalizePage(page + 1, pageCount(lines.size(), PAGE_ROWS)); }
    public synchronized void prevPage() { page = normalizePage(page - 1, pageCount(lines.size(), PAGE_ROWS)); }

    /** Pages of {@code total} rows at {@code rows} rows/page; ≥1 always. */
    public static int pageCount(int total, int rows) {
        return Math.max(1, (total + rows - 1) / rows);
    }

    /** Wrap {@code page} into [0, pages) — scrolling off one end re-enters the other. */
    public static int normalizePage(int page, int pages) {
        if (pages <= 1) return 0;
        int p = page % pages;
        return p < 0 ? p + pages : p;
    }

    /** The {@code page} window ({@code rows} per page) of {@code lines} — defensive copy. */
    public static List<String> pageSlice(List<String> lines, int page, int rows) {
        int from = Math.min(lines.size(), normalizePage(page, pageCount(lines.size(), rows)) * rows);
        return new ArrayList<>(lines.subList(from, Math.min(lines.size(), from + rows)));
    }

    /** "-- page x/y (PgUp/PgDn) --" footer, or null while there is a single page. */
    public synchronized String pageIndicator() {
        int pages = pageCount(lines.size(), PAGE_ROWS);
        if (pages <= 1) return null;
        return "-- page " + (normalizePage(page, pages) + 1) + "/" + pages + " (PgUp/PgDn) --";
    }

    // ---- MP-012 (#158): page-frame text builder (shared by both surfaces) ------

    /** MP-012: full page frame for {@code page} — wall header, the page's rows
     *  (blank-padded to a fixed frame height so renderers get a stable grid)
     *  and the page footer. Status/header colorization stays in the renderer
     *  (it knows the palette); here we only guarantee STRUCTURE and MARKERS:
     *  char 0 '<' = header/footer rows, '<@' = fixed-grid status row
     *  (colorable via statusChar()), plain = everything else. Section tracking
     *  walks the FULL list, so a page window that starts mid-section (page > 1)
     *  still classifies its rows correctly. */
    public static List<String> buildPageFrame(List<String> lines, int page, int rows) {
        int pages = pageCount(lines.size(), rows);
        int from = normalizePage(page, pages) * rows;
        int to = Math.min(lines.size(), from + rows);
        List<String> frame = new ArrayList<>(rows + 2);
        String wallHeader = "FLEET WALL";
        for (String l : lines) { if (l.startsWith("==")) { wallHeader = l; break; } }
        frame.add("<" + wallHeader.substring(2).trim());
        String section = "";
        for (int i = 0; i < to; i++) {
            String l = lines.get(i);
            if (l.startsWith("==")) { section = l.substring(2).trim(); }
            if (i < from) continue;
            boolean statusGrid = section.startsWith("FLEET")
                && l.length() >= AGENT_COL + STATUS_COL;
            frame.add(statusGrid ? "<@" + l : l);
        }
        while (frame.size() < rows + 1) frame.add("");
        frame.add("<" + (pages > 1
            ? ("page " + (normalizePage(page, pages) + 1) + "/" + pages + " (PgUp/PgDn)")
            : "end of wall"));
        return frame;
    }

    /** Instance convenience: the CURRENT page frame (paging clamped to actual content). */
    public synchronized List<String> pagedWallLines() {
        return buildPageFrame(new ArrayList<>(lines), normalizePage(page, pageCount(lines.size(), PAGE_ROWS)), PAGE_ROWS);
    }

    /** Snapshot of the current wall lines (header lines start with "=="). */
    public synchronized List<String> getLines() { return new ArrayList<>(lines); }

    /** Demo/self-test: seed deterministic fake wall lines (no network). */
    public static List<String> demoLines() {
        List<String> out = new ArrayList<>();
        out.add("== FLEET ==");
        String[] agents = {"alice", "bob", "carol", "dave", "eve", "frank", "ghost", "henry", "iris", "judge"};
        String[] stats  = {"green", "green", "amber-warn", "green", "red-down", "green", "heartbeat-ok", "green", "green", "ok"};
        String[] ago    = {"14s", "62s", "5m", "3m", "31s", "2m", "1m", "2h", "18m", "4d"};
        for (int i = 0; i < agents.length; i++) {
            out.add(pad(agents[i], AGENT_COL) + pad(stats[i], STATUS_COL) + ago[i]);
        }
        out.add("== LOGITS ==");
        out.add("mansion-restore" + " \u00B7 12 steps");
        out.add("word-sweep" + " \u00B7 3 steps");
        out.add("backup-vault" + " \u00B7 7 steps");
        out.add("sop-harvest" + " \u00B7 9 steps");
        out.add("issue-triage" + " \u00B7 21 steps");
        out.add("deploy-belt" + " \u00B7 5 steps");
        out.add("== WORD LIB ==");
        out.add("code_ask:fix null check in spawn loop (quality 0.93)");
        out.add("sop:mvn package then selftest then e2e tour (score 0.81)");
        out.add("event:backup encryption AES-GCM rotate key (complete)");
        out.add("code_ask:paginate gist wall with PgUp/PgDn (quality 0.88)");
        out.add("sop:screenshot every feature at 1920x1080 (score 0.9)");
        out.add("event:fleet heartbeat broadcast to gist (complete)");
        return out;
    }

    /**
     * Kick a fetch if the cooldown has elapsed. Safe to call every frame — the
     * actual network work happens on a daemon thread and only fires every 60s.
     */
    public synchronized void refresh() {
        if (token == null || token.length() < 20) {
            // No token — show an offline hint instead of a blank wall (matches javadoc).
            if (lines.isEmpty()) lines = java.util.Collections.singletonList("[gist wall: no token]");
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastFetchMs < COOLDOWN_MS || fetching) return;
        lastFetchMs = now;
        fetching = true;
        Thread t = new Thread(this::doFetch, "gist-wall-fetch");
        t.setDaemon(true);
        t.start();
    }

    private void doFetch() {
        try {
            List<String> next = new ArrayList<>();
            next.add("== FLEET ==");
            fetchGistLines("fleet_status.jsonl", next, MAX_STATUS, Source.STATUS);
            next.add("== LOGITS ==");
            fetchGistLines("workflow_logits.jsonl", next, MAX_LOGIT, Source.LOGITS);
            next.add("== WORD LIB ==");
            fetchWordLibrary(next, MAX_WORDLIB);
            synchronized (this) {
                lines = next;
                fetched = true;
                lastError = null;
            }
        } catch (Exception e) {
            synchronized (this) {
                lastError = e.getMessage();
                if (lines.isEmpty()) lines = java.util.Collections.singletonList("[gist wall offline]");
            }
        } finally {
            synchronized (this) { fetching = false; }
        }
    }

    /** MP-012: which section a raw line came from — drives the fixed-grid format. */
    enum Source { STATUS, LOGITS, WORDLIB }

    /** Fetch the last {@code max} lines of a named file in the fleet gist. */
    private void fetchGistLines(String filename, List<String> out, int max, Source src) throws IOException {
        String rawUrl = null;
        Request meta = new Request.Builder()
            .url(API_BASE + "/gists/" + FLEET_GIST_ID)
            .header("Authorization", "token " + token)
            .header("Accept", "application/vnd.github.v3+json")
            .build();
        try (Response resp = http.newCall(meta).execute()) {
            if (!resp.isSuccessful()) { out.add("[gist " + resp.code() + "]"); return; }
            JsonObject root = JsonParser.parseString(resp.body().string()).getAsJsonObject();
            JsonObject files = root.getAsJsonObject("files");
            if (files != null && files.has(filename)) {
                JsonObject f = files.getAsJsonObject(filename);
                if (f.has("raw_url") && !f.get("raw_url").isJsonNull()) {
                    rawUrl = f.get("raw_url").getAsString();
                }
            }
        }
        if (rawUrl == null) { out.add("[no " + filename + "]"); return; }

        Request raw = new Request.Builder()
            .url(rawUrl)
            .header("Authorization", "token " + token)
            .build();
        try (Response resp = http.newCall(raw).execute()) {
            if (!resp.isSuccessful()) { out.add("[raw " + resp.code() + "]"); return; }
            appendTail(resp.body().string(), out, max, src);
        }
    }

    /** Fetch the last {@code max} lines of the word-library success paths. */
    private void fetchWordLibrary(List<String> out, int max) throws IOException {
        String url = API_BASE + "/repos/chrisalunlloyd2-sudo/BDI_FSM_DAGs/contents/" + WORDLIB_PATH;
        Request req = new Request.Builder()
            .url(url)
            .header("Authorization", "token " + token)
            .header("Accept", "application/vnd.github.v3.raw")
            .build();
        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful()) { out.add("[wordlib " + resp.code() + "]"); return; }
            appendTail(resp.body().string(), out, max, Source.WORDLIB);
        }
    }

    /** Split content, keep the last {@code max} non-empty lines, format each. */
    private static void appendTail(String content, List<String> out, int max, Source src) {
        String[] all = content.split("\\n");
        int start = Math.max(0, all.length - max);
        for (int i = start; i < all.length; i++) {
            String line = all[i].trim();
            if (line.isEmpty()) continue;
            out.add(shorten(formatLine(line, src), 64));
        }
    }

    /** Extract a short human string from a JSONL line; fall back to the raw line. */
    private static String formatLine(String line, Source src) {
        try {
            JsonObject o = JsonParser.parseString(line).getAsJsonObject();
            if (src == Source.STATUS) return formatStatusLine(o);       // fixed grid
            if (src == Source.WORDLIB) return formatWordlibLine(o);     // MP-012: kind+score+text
            if (o.has("workflow")) return formatLogitLine(o);
            if (o.has("agent")) return formatStatusLine(o);             // STATUS-shaped line
        } catch (Exception ignored) { }
        return line;
    }

    /** MP-012: word-library row — verified schema from the BDI_FSM_AGENT
     *  producer (bdi_fsm/word_library.py): kind ∈ sop/code_ask/event/journal,
     *  text in 'question' | 'action' | 'pattern'(list) | 'contract', score
     *  in 'score' or 'quality'. Renders "kind:TEXT (quality N)" — never
     *  raw JSON on the wall. */
    static String formatWordlibLine(JsonObject o) {
        String kind = o.has("kind") ? o.get("kind").getAsString() : "path";
        String text = "";
        if (o.has("question") && !o.get("question").isJsonNull()) {
            text = o.get("question").getAsString();
        } else if (o.has("action") && !o.get("action").isJsonNull()) {
            text = o.get("action").getAsString();
        } else if (o.has("pattern") && o.get("pattern").isJsonArray()) {
            StringBuilder b = new StringBuilder();
            for (var w : o.getAsJsonArray("pattern")) {
                if (b.length() > 0) b.append(' ');
                b.append(w.getAsString());
            }
            text = b.toString();
        } else if (o.has("contract") && !o.get("contract").isJsonNull()) {
            text = o.get("contract").getAsString();
        } else if (o.has("words") && o.get("words").isJsonArray()) {
            StringBuilder b = new StringBuilder();
            for (var w : o.getAsJsonArray("words")) {
                if (b.length() > 0) b.append(' ');
                b.append(w.getAsString());
            }
            text = b.toString();
        }
        String score = "";
        if (o.has("quality") && !o.get("quality").isJsonNull()) {
            score = " (quality " + o.get("quality").getAsDouble() + ")";
        } else if (o.has("score") && !o.get("score").isJsonNull()) {
            score = " (score " + o.get("score").getAsDouble() + ")";
        }
        return kind + ":" + text + score;
    }

    /** MP-012: status classifier for color-by-status. GREEN/red/amber by
     *  keyword; unknown → AMBER (never silently green). */
    static StatusLevel statusLevel(String rawStatus) {
        String s = rawStatus == null ? "" : rawStatus.toLowerCase(java.util.Locale.ROOT);
        if (s.contains("green") || s.contains("ok") || s.contains("health") || s.contains("ready")
            || s.contains("pass") || s.contains("up") || s.contains("done") || s.contains("complete")) {
            return StatusLevel.GREEN;
        }
        if (s.contains("red") || s.contains("down") || s.contains("fail") || s.contains("error")
            || s.contains("crash") || s.contains("stuck") || s.contains("dead") || s.contains("offline")) {
            return StatusLevel.RED;
        }
        if (s.contains("amber") || s.contains("warn") || s.contains("degrad") || s.contains("retry")
            || s.contains("busy") || s.contains("pause") || s.contains("lag")) {
            return StatusLevel.AMBER;
        }
        return StatusLevel.AMBER;
    }

    public enum StatusLevel { GREEN, AMBER, RED }

    /** MP-012: public color-by-status token for renderers outside this
     *  package ('G'/'A'/'R' parsed from a fixed-grid row; unknown → amber). */
    public static char statusChar(String gridLine) {
        return switch (levelOfGridLine(gridLine)) {
            case GREEN -> 'G';
            case AMBER -> 'A';
            case RED -> 'R';
        };
    }

    /** The row's status level parsed BACK out of a fixed-grid line — renderers
     *  use this to color-by-status without touching the JSON. Green rows are
     *  the fixed grid's 14+13ch layout (longer lines are truncated rows). */
    static StatusLevel levelOfGridLine(String line) {
        if (line.length() >= AGENT_COL + STATUS_COL) {
            return statusLevel(line.substring(AGENT_COL, AGENT_COL + STATUS_COL));
        }
        return StatusLevel.GREEN;
    }

    /** MP-012: fixed-column grid row "agent …  status …  ts" (schema-verified:
     *  fleet_status.jsonl rows carry {ts, agent, status}; ts may be epoch secs
     *  or ISO — formatTs handles both). */
    static String formatStatusLine(JsonObject o) {
        String agent = o.has("agent") ? o.get("agent").getAsString() : "?";
        String status = o.has("status") ? o.get("status").getAsString() : "";
        String ts = o.has("ts") ? o.get("ts").getAsString() : "";
        String a = agent.length() > AGENT_COL ? agent.substring(0, AGENT_COL - 1) + "\u00B7" : pad(agent, AGENT_COL);
        String s = status.length() > STATUS_COL ? status.substring(0, STATUS_COL - 1) + "\u00B7" : pad(status, STATUS_COL);
        return a + s + formatTs(ts);
    }

    /** MP-012: build a status-grid row from plain strings (self-test / demo /
     *  any caller outside this package). Same fixed-grid contract as
     *  formatStatusLine: agent 9ch, status 13ch, then the raw ts text. */
    public static String formatGridRow(String agent, String status, String ts) {
        String a = agent.length() > AGENT_COL ? agent.substring(0, AGENT_COL - 1) + "\u00B7" : pad(agent, AGENT_COL);
        String s = status.length() > STATUS_COL ? status.substring(0, STATUS_COL - 1) + "\u00B7" : pad(status, STATUS_COL);
        return a + s + (ts == null ? "" : ts);
    }

    /** MP-012: logits row "workflow · steps" (workflow_logits.jsonl carries
     *  {ts, workflow, steps}). */
    static String formatLogitLine(JsonObject o) {
        String wf = o.has("workflow") ? o.get("workflow").getAsString() : "?";
        String steps = o.has("steps") && !o.get("steps").isJsonNull()
            ? String.valueOf(o.get("steps").getAsInt()) : "?";
        return wf + " \u00B7 " + steps + " steps";
    }

    /** "3s/14d ago"-style relative ts; blank when the row carries no timestamp. */
    static String formatTs(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        try {
            long epochMs;
            if ( Character.isDigit(raw.charAt(0)) && raw.indexOf('-') < 0 && raw.indexOf(':') < 0) {
                long v = Long.parseLong(raw.contains(".") ? raw.substring(0, raw.indexOf('.')) : raw);
                epochMs = v < 10_000_000_000L ? v * 1000L : v; // secs vs millis
            } else {
                epochMs = java.time.Instant.parse(raw.endsWith("Z") ? raw : raw + "Z").toEpochMilli();
            }
            long age = System.currentTimeMillis() - epochMs;
            if (age < 0) age = 0;
            if (age < 90_000L) return Math.max(1, age / 1000L) + "s";
            if (age < 3_600_000L) return (age / 60_000L) + "m";
            if (age < 86_400_000L) return (age / 3_600_000L) + "h";
            return (age / 86_400_000L) + "d";
        } catch (Exception e) {
            return raw.length() > 10 ? "" : raw; // unparseable → drop, don't lie
        }
    }

    /** Space-pad to {@code n} chars (no String.repeat on old toolchains). */
    private static String pad(String s, int n) {
        if (s.length() >= n) return s;
        StringBuilder b = new StringBuilder(s);
        while (b.length() < n) b.append(' ');
        return b.toString();
    }

    private static String shorten(String s, int max) {
        return s.length() > max ? s.substring(0, max - 1) + "\u2026" : s;
    }
}
