package com.mindpalace.tailscale;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MeshServer (plan T2): the live unattended mesh layer riding
 * {@link TailnetTransport}. Binds 127.0.0.1 + tailnet IP only (never Funnel).
 *
 * <p>Identity for every route is the transport-resolved peer (tailscale
 * whois), never the request body; TailnetTransport already 403s peers
 * without the route's capability (owner-login = trust, plan 2026-10-17).
 * Quorum ballots are additionally REQUIRED to carry a per-device Ed25519
 * signature (anti-cheat, plan use 18): the device key is pinned
 * trust-on-first-use per device name, replay is blocked by a nonce set, and
 * the whole payload window is +/-90 seconds.</p>
 *
 * <p>Routes mounted here:
 * <ul>
 *   <li>GET /v1/mesh/ping - liveness</li>
 *   <li>GET /v1/mesh/status - device key + counters JSON</li>
 *   <li>GET|POST /v1/mesh/chat - recent chat ring fan-in (owner-gated)</li>
 *   <li>POST /v1/quorum/vote - signed mesh ballot; lands on the currently
 *       open proposal via {@link BallotSink}; 409 when none is open</li>
 * </ul></p>
 */
public final class MeshServer {

    /** Sink for validated mesh ballots; implemented by AgentManager wiring. */
    public interface BallotSink {
        /** Deliver one verified ballot. False when no proposal is open (409). */
        boolean onBallot(String proposalId, String deviceId, String vote, String nonce);
    }

    private static final long TS_WINDOW_MS = 90_000L;
    private static final int CHAT_RING = 100;
    private static final int MAX_NONCES = 4096;

    private final MeshCrypto crypto;
    private final BallotSink ballots;
    private final Gson gson = new Gson();
    private final Map<String, List<String>> chat = new ConcurrentHashMap<>();
    private final Map<String, Long> seenNonces = new ConcurrentHashMap<>();
    private long ballotsOk;
    private long ballotsRejected;

    private MeshServer(MeshCrypto crypto, BallotSink ballots) {
        this.crypto = crypto;
        this.ballots = ballots;
    }

    /** Mount all mesh routes on the transport router (before start()). */
    public static MeshServer mount(TailnetTransport.Router router, MeshCrypto crypto,
                                   BallotSink ballots) {
        MeshServer server = new MeshServer(crypto, ballots);
        router.get("/v1/mesh/ping", (ex, peer, caps) -> server.servePing(ex, peer));
        router.get("/v1/mesh/status", (ex, peer, caps) -> server.serveStatus(ex));
        router.getPrivate("/v1/mesh/chat",
            (ex, peer, caps) -> server.serveChat(ex, peer, ex.getRequestMethod()));
        router.vote("/v1/quorum/vote", (ex, peer, caps) -> server.serveVote(ex, peer));
        return server;
    }

    private void servePing(HttpExchange ex, com.mindpalace.tailscale.TailnetIdentity peer)
            throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("ok", true);
        body.addProperty("device", crypto.deviceName());
        body.addProperty("peer", peer.displayName());
        respond(ex, 200, body);
    }

    private void serveStatus(HttpExchange ex) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("device", crypto.deviceName());
        body.addProperty("publicKey", crypto.publicKeyBase64());
        body.addProperty("ballotsOk", ballotsOk);
        body.addProperty("ballotsRejected", ballotsRejected);
        body.addProperty("chatPeers", chat.size());
        respond(ex, 200, body);
    }

    private void serveChat(HttpExchange ex, com.mindpalace.tailscale.TailnetIdentity peer,
                           String method) throws IOException {
        if ("POST".equals(method)) {
            JsonObject req = readJson(ex);
            String text = optString(req, "text");
            if (text.isEmpty() || text.length() > 2000) {
                respond(ex, 400, err("bad text"));
                return;
            }
            if (peerDevice(peer) == null) {
                respond(ex, 403, err("no device identity"));
                return;
            }
            List<String> ring = chat.computeIfAbsent(peerDevice(peer), k -> new ArrayList<>());
            synchronized (ring) {
                ring.add(text);
                while (ring.size() > CHAT_RING) {
                    ring.remove(0);
                }
            }
            respond(ex, 200, ok("chat accepted"));
            return;
        }
        JsonObject body = new JsonObject();
        List<String> flat = new ArrayList<>();
        for (List<String> ring : chat.values()) {
            synchronized (ring) {
                flat.addAll(ring);
            }
        }
        body.addProperty("count", flat.size());
        body.add("messages", gson.toJsonTree(flat));
        respond(ex, 200, body);
    }

    /** Signed mesh ballot: pins, replays, verifies, then sinks. */
    private void serveVote(HttpExchange ex, com.mindpalace.tailscale.TailnetIdentity peer)
            throws IOException {
        String device = peerDevice(peer);
        if (device == null) {
            ballotsRejected++;
            respond(ex, 403, err("no device identity"));
            return;
        }
        JsonObject req = readJson(ex);
        String proposalId = optString(req, "proposalId");
        String vote = optString(req, "vote").toUpperCase(java.util.Locale.ROOT);
        String nonce = optString(req, "nonce");
        String sig = optString(req, "sig");
        String publicKey = optString(req, "publicKey");
        long ts = optLong(req, "ts");
        if (proposalId.isEmpty() || nonce.isEmpty() || sig.isEmpty() || ts <= 0
                || publicKey.isEmpty()) {
            ballotsRejected++;
            respond(ex, 400, err("bad ballot"));
            return;
        }
        long now = System.currentTimeMillis();
        if (Math.abs(now - ts) > TS_WINDOW_MS) {
            ballotsRejected++;
            respond(ex, 403, err("stale ballot"));
            return;
        }
        // Verify device key signature over the canonical payload.
        String payload = MeshCrypto.ballotPayload(proposalId, device, vote, nonce, ts);
        if (!crypto.pinMatches(device, publicKey)) {
            crypto.pinPeer(device, publicKey); // TOFU: owner already transport-gated
        }
        if (!MeshCrypto.verify(crypto.peerKey(device), payload, sig)) {
            ballotsRejected++;
            respond(ex, 403, err("bad signature"));
            return;
        }
        // Replay guard keyed on device|proposal|nonce.
        String replayKey = device + "|" + proposalId + "|" + nonce;
        if (seenNonces.size() > MAX_NONCES) {
            seenNonces.clear();
        }
        if (seenNonces.putIfAbsent(replayKey, ts) != null) {
            ballotsRejected++;
            respond(ex, 409, err("replayed ballot"));
            return;
        }
        boolean accepted;
        try {
            accepted = ballots.onBallot(proposalId, device, vote, nonce);
        } catch (RuntimeException e) {
            seenNonces.remove(replayKey);
            respond(ex, 500, err("sink error"));
            return;
        }
        if (!accepted) {
            ballotsRejected++;
            seenNonces.remove(replayKey);
            respond(ex, 409, err("no open proposal"));
            return;
        }
        ballotsOk++;
        respond(ex, 200, ok("ballot counted"));
    }

    /** Canonical, transport-visible device label; null when absent (fail closed). */
    private static String peerDevice(com.mindpalace.tailscale.TailnetIdentity peer) {
        return peer == null || peer.deviceName() == null || peer.deviceName().isBlank()
            ? null : peer.deviceName();
    }

    private JsonObject readJson(HttpExchange ex) throws IOException {
        byte[] raw = ex.getRequestBody().readAllBytes();
        if (raw.length > 16 * 1024) {
            return null;
        }
        try {
            return gson.fromJson(new String(raw, StandardCharsets.UTF_8), JsonObject.class);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String optString(JsonObject obj, String member) {
        if (obj == null || member == null || !obj.has(member) || obj.get(member).isJsonNull()) {
            return "";
        }
        try {
            return obj.get(member).getAsString();
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static long optLong(JsonObject obj, String member) {
        if (obj == null || !obj.has(member) || obj.get(member).isJsonNull()) {
            return 0L;
        }
        try {
            return obj.get(member).getAsLong();
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    private static JsonObject err(String message) {
        JsonObject o = new JsonObject();
        o.addProperty("error", message);
        return o;
    }

    private static JsonObject ok(String detail) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        o.addProperty("detail", detail);
        return o;
    }

    private void respond(HttpExchange ex, int status, JsonObject body) throws IOException {
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (java.io.OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }
}