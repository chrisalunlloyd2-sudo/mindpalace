package com.mindpalace.tailscale;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mindpalace.backup.Telemetry;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

/**
 * T3 (2026-09-30 plan use 5) — remote world control for the Architect's
 * phone/laptop over the tailnet. Mounted on the T1 Router like MeshServer:
 * the Router already resolved the peer (WhoIs) and enforced the route
 * capability (architect only — the mutators mount with {@code ADMIN},
 * the state read with {@code BUILD}); handlers never see an unauthorized
 * peer and never call WhoIsResolver themselves.
 *
 * <ul>
 *   <li>GET  /v1/admin/state  - pinned clock / weather override / queue flag</li>
 *   <li>POST /v1/admin/clock  - {"hour":0..23} or {"clear":true}</li>
 *   <li>POST /v1/admin/weather - {"weather":"CLEAR|RAIN|SNOW"} or {"clear":true}</li>
 *   <li>POST /v1/admin/go     - {"dest":"room|floor|pad|mansion|outside|planet|palace","id":"..."}</li>
 * </ul>
 *
 * Every accepted mutation is audited into the telemetry ledger
 * (category=system, event=admin.*) so remote control is never silent.
 * Teleport requests are queued, not executed: the WorldControl implementor
 * (GameEngine) defers them to the game thread, because teleport bodies touch
 * OpenAL, which only has a context on the render thread.
 */
public final class AdminApi {

    /** Live-world hooks; implemented by GameEngine wiring, faked in selftest. */
    public interface WorldControl {
        /** Pin the world clock to hour (0–23); null clears the pin. Returns a human message. */
        String pinClock(Integer hour);

        /** Override weather to CLEAR|RAIN|SNOW; null clears the override. Returns a human message. */
        String setWeather(String weatherName);

        /** Queue a teleport; destination + optional argument (room id, 1-based floor/pad). */
        String teleport(String destination, String argument);

        /** Current control state, serialized member-for-member into GET /v1/admin/state. */
        Map<String, String> state();
    }

    private final WorldControl control;
    private final Telemetry telemetry; // nullable: selftest rigs may pass null
    private final Gson gson = new Gson();

    private AdminApi(WorldControl control, Telemetry telemetry) {
        this.control = control;
        this.telemetry = telemetry;
    }

    /** Mount admin routes on the Router (before transport start()). */
    public static AdminApi mount(TailnetTransport.Router router, WorldControl control,
                                 Telemetry telemetry) {
        AdminApi api = new AdminApi(control, telemetry);
        router.build("/v1/admin/state", (ex, peer, caps) -> api.serveState(ex));
        router.admin("/v1/admin/clock", (ex, peer, caps) -> api.serveClock(ex, peer));
        router.admin("/v1/admin/weather", (ex, peer, caps) -> api.serveWeather(ex, peer));
        router.admin("/v1/admin/go", (ex, peer, caps) -> api.serveGo(ex, peer));
        return api;
    }

    private void serveState(HttpExchange ex) throws IOException {
        if (!"GET".equals(ex.getRequestMethod())) {
            respond(ex, 405, error("use GET"));
            return;
        }
        JsonObject body = new JsonObject();
        body.addProperty("ok", true);
        for (Map.Entry<String, String> e : control.state().entrySet()) {
            body.addProperty(e.getKey(), e.getValue());
        }
        respond(ex, 200, body);
    }

    private void serveClock(HttpExchange ex, TailnetIdentity peer) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) {
            respond(ex, 405, error("use POST"));
            return;
        }
        JsonObject req = readJson(ex);
        if (req == null) {
            respond(ex, 400, error("invalid body"));
            return;
        }
        if (truthy(req, "clear")) {
            String message = control.pinClock(null);
            audited("admin.clock", "cleared by " + peer.displayName());
            respond(ex, 200, ok(message, "hour", ""));
            return;
        }
        Integer hour = optInt(req, "hour");
        if (hour == null || hour < 0 || hour > 23) {
            respond(ex, 400, error("hour must be 0..23"));
            return;
        }
        String message = control.pinClock(hour);
        audited("admin.clock", "pinned " + hour + "h by " + peer.displayName());
        respond(ex, 200, ok(message, "hour", String.valueOf(hour)));
    }

    private void serveWeather(HttpExchange ex, TailnetIdentity peer) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) {
            respond(ex, 405, error("use POST"));
            return;
        }
        JsonObject req = readJson(ex);
        if (req == null) {
            respond(ex, 400, error("invalid body"));
            return;
        }
        if (truthy(req, "clear")) {
            String message = control.setWeather(null);
            audited("admin.weather", "cleared by " + peer.displayName());
            respond(ex, 200, ok(message, "weather", ""));
            return;
        }
        String name = optString(req, "weather").toUpperCase();
        if (!name.equals("CLEAR") && !name.equals("RAIN") && !name.equals("SNOW")) {
            respond(ex, 400, error("weather must be CLEAR, RAIN or SNOW"));
            return;
        }
        String message = control.setWeather(name);
        audited("admin.weather", name + " by " + peer.displayName());
        respond(ex, 200, ok(message, "weather", name));
    }

    private void serveGo(HttpExchange ex, TailnetIdentity peer) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) {
            respond(ex, 405, error("use POST"));
            return;
        }
        JsonObject req = readJson(ex);
        if (req == null) {
            respond(ex, 400, error("invalid body"));
            return;
        }
        String dest = optString(req, "dest").toLowerCase();
        if (dest.isBlank()) {
            respond(ex, 400, error("dest required (room|floor|pad|mansion|outside|planet|palace)"));
            return;
        }
        Set<String> known = Set.of("room", "floor", "pad", "mansion", "outside", "planet", "palace");
        if (!known.contains(dest)) {
            respond(ex, 400, error("unknown dest: " + dest));
            return;
        }
        String id = optString(req, "id");
        String message = control.teleport(dest, id);
        audited("admin.go", dest + (id.isBlank() ? "" : " " + id) + " by " + peer.displayName());
        JsonObject body = ok(message, "dest", dest);
        body.addProperty("id", id);
        respond(ex, 200, body);
    }

    // ── shared JSON plumbing (same shape as MeshServer's) ──

    private JsonObject ok(String message, String member, String value) {
        JsonObject body = new JsonObject();
        body.addProperty("ok", true);
        body.addProperty("message", message);
        body.addProperty(member, value);
        return body;
    }

    private static JsonObject error(String message) {
        JsonObject body = new JsonObject();
        body.addProperty("ok", false);
        body.addProperty("message", message);
        return body;
    }

    private void audited(String event, String detail) {
        if (telemetry == null) return;
        try {
            telemetry.record(Telemetry.SYSTEM, event, detail);
        } catch (Exception ignored) {
            // an audit breadcrumb must never break the route
        }
    }

    private JsonObject readJson(HttpExchange ex) throws IOException {
        byte[] raw = ex.getRequestBody().readAllBytes();
        if (raw.length > 16 * 1024) return null;
        try {
            return gson.fromJson(new String(raw, StandardCharsets.UTF_8), JsonObject.class);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void respond(HttpExchange ex, int status, JsonObject body) throws IOException {
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static String optString(JsonObject obj, String member) {
        if (obj == null || !obj.has(member) || obj.get(member).isJsonNull()) return "";
        try {
            return obj.get(member).getAsString().trim();
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static Integer optInt(JsonObject obj, String member) {
        if (obj == null || !obj.has(member) || obj.get(member).isJsonNull()) return null;
        try {
            return obj.get(member).getAsInt();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean truthy(JsonObject obj, String member) {
        if (obj == null || !obj.has(member) || obj.get(member).isJsonNull()) return false;
        try {
            return obj.get(member).getAsBoolean();
        } catch (RuntimeException e) {
            return false;
        }
    }
}