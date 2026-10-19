package com.mindpalace.tailscale;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * T3 (2026-09-30 plan use 9) — phone/laptop spectator HUD over the tailnet.
 *
 * Serves a self-refreshing read-only HTML page (no JS framework, no external
 * requests — the phone opens it on the tailnet and it just renders):
 *
 * <ul>
 *   <li>GET /v1/spectator       - HTML page (READ_PUBLIC: spectators may see)
 *   <li>GET /v1/spectator/data  - same data as JSON (READ_PUBLIC)</li>
 * </ul>
 *
 * Data comes from {@link DataSource}, implemented by GameEngine wiring from
 * the live world: rooms, agent names, open quorum proposals with vote
 * counts, recent telemetry events and the player position. Read-only by
 * construction — the HUD "may intersect down, never widen" (T1 roles): a
 * spectator-role peer sees exactly this page and nothing private.
 */
public final class SpectatorPage {

    /** Live read-only snapshot provider; implemented by GameEngine wiring. */
    public interface DataSource {
        /** [repoName, displayLabel, floor] per room. */
        List<String[]> rooms();

        /** Agent display names currently running in the world. */
        List<String> agents();

        /** [id, text, approve, reject, total] per open quorum proposal. */
        List<String[]> proposals();

        /** [ts, category, event, detail] recent telemetry — already capped. */
        List<String[]> events();

        /** One-line player state: position + floor + current room name. */
        String playerState();
    }

    private final DataSource data;

    private SpectatorPage(DataSource data) {
        this.data = data;
    }

    /** Mount the spectator routes on the Router (before transport start()). */
    public static SpectatorPage mount(TailnetTransport.Router router, DataSource data) {
        SpectatorPage page = new SpectatorPage(data);
        router.get("/v1/spectator", (ex, peer, caps) -> page.servePage(ex));
        router.get("/v1/spectator/data", (ex, peer, caps) -> page.serveData(ex));
        return page;
    }

    private void servePage(HttpExchange ex) throws IOException {
        byte[] bytes = renderHtml().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    private void serveData(HttpExchange ex) throws IOException {
        StringBuilder json = new StringBuilder("{");
        json.append("\"playerState\":\"").append(escapeJson(data.playerState())).append("\",");
        json.append("\"agents\":[");
        List<String> agents = data.agents();
        for (int i = 0; i < agents.size(); i++) {
            if (i > 0) json.append(',');
            json.append('"').append(escapeJson(agents.get(i))).append('"');
        }
        json.append("],\"rooms\":[");
        appendRows(json, data.rooms());
        json.append("],\"proposals\":[");
        appendRows(json, data.proposals());
        json.append("],\"events\":[");
        appendRows(json, data.events());
        json.append("]}");
        byte[] bytes = json.toString().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void appendRows(StringBuilder json, List<String[]> rows) {
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) json.append(',');
            json.append('[');
            String[] row = rows.get(i);
            for (int c = 0; c < row.length; c++) {
                if (c > 0) json.append(',');
                json.append('"').append(escapeJson(row[c])).append('"');
            }
            json.append(']');
        }
    }

    /** Static, no-dependency HTML — auto-refresh every 10 s. */
    private String renderHtml() {
        StringBuilder h = new StringBuilder(8192);
        h.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
            .append("<meta http-equiv=\"refresh\" content=\"10\">")
            .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
            .append("<title>MindPalace HUD</title><style>")
            .append("body{background:#0b0e14;color:#c9d1d9;font-family:monospace;margin:12px}")
            .append("h2{color:#58a6ff;font-size:1em;margin:14px 0 4px}")
            .append("table{border-collapse:collapse;width:100%}")
            .append("td,th{border:1px solid #21262d;padding:2px 6px;font-size:.8em;text-align:left}")
            .append("th{background:#161b22;color:#8b949e}")
            .append(".pos{color:#7ee787}.id{color:#79c0ff}.warn{color:#f85149}")
            .append("</style></head><body>");
        h.append("<h1>MIND PALACE</h1>");
        h.append("<p class=\"pos\">").append(esc(data.playerState())).append("</p>");

        h.append("<h2>AGENTS</h2><p>");
        List<String> agents = data.agents();
        h.append(agents.isEmpty() ? "(none running)" : esc(String.join(" &middot; ", agents)));
        h.append("</p>");

        h.append("<h2>OPEN QUORUM PROPOSALS</h2><table><tr><th>id</th><th>proposal</th>")
            .append("<th>ok</th><th>no</th><th>votes</th></tr>");
        List<String[]> proposals = data.proposals();
        if (proposals.isEmpty()) {
            h.append("<tr><td colspan=5>- none open -</td></tr>");
        }
        for (String[] p : proposals) {
            h.append("<tr><td class=\"id\">").append(esc(p[0]))
                .append("</td><td>").append(esc(p[1]));
            for (int c = 2; c < p.length; c++) {
                h.append("</td><td>").append(esc(p[c]));
            }
            h.append("</td></tr>");
        }
        h.append("</table>");

        h.append("<h2>ROOMS</h2><table><tr><th>repo</th><th>room</th><th>floor</th></tr>");
        for (String[] r : data.rooms()) {
            h.append("<tr><td>").append(esc(r[0])).append("</td><td>").append(esc(r[1]))
                .append("</td><td>").append(esc(r[2])).append("</td></tr>");
        }
        h.append("</table>");

        h.append("<h2>EVENTS</h2><table><tr><th>ts</th><th>cat</th><th>event</th><th>detail</th></tr>");
        List<String[]> events = data.events();
        for (String[] e : events) {
            h.append("<tr>");
            for (int c = 0; c < e.length; c++) {
                h.append(c == 3 ? "<td class=\"warn\">" : "<td>").append(esc(e[c])).append("</td>");
            }
            h.append("</tr>");
        }
        h.append("</table></body></html>");
        return h.toString();
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}