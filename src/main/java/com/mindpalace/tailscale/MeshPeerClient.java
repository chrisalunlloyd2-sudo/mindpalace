package com.mindpalace.tailscale;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.UUID;

/**
 * MeshPeerClient (plan T2): the peer side of the live mesh. A second device
 * (also on the tailnet, owner-login) POSTs signed ballots to the host running
 * MeshServer, and reads ping/status/chat.
 *
 * <p>Tailnet-internal only: connects to the host's 100.x magicDNS name or its
 * loopback form for hermetic selftests. Ballots are signed with this device's
 * MeshCrypto key over the shared canonical payload (MeshCrypto.ballotPayload);
 * the host pins our public key on first sight (TOFU) and rejects replays via
 * the (device, proposal, nonce) key.</p>
 */
public final class MeshPeerClient {

    private static final long CONNECT_TIMEOUT_MS = 4000L;
    private static final long READ_TIMEOUT_MS = 8000L;

    private final String baseUrl;
    private final MeshCrypto crypto;
    private final Gson gson = new Gson();

    /**
     * @param baseUrl like {@code http://100.x.y.z:port} (tailnet) or
     *                {@code http://127.0.0.1:62341} in selftests
     */
    public MeshPeerClient(String baseUrl, MeshCrypto crypto) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        this.crypto = crypto;
    }

    /**
     * Cast one signed ballot (plan use 3). Vote is APPROVE/REJECT (mesh
     * devices are unregistered in the quorum models map; see
     * WeightedQuorumVote.weightedApprove flat-1.0 handling).
     *
     * @return HTTP status (200 counted, 409 no open proposal / replay,
     *         403 signature or stale, 400 shape)
     */
    public int castBallot(String proposalId, String vote) throws IOException,
            GeneralSecurityException {
        String voteNorm = vote == null ? "" : vote.toUpperCase(java.util.Locale.ROOT);
        String nonce = UUID.randomUUID().toString();
        long ts = System.currentTimeMillis();
        String payload = MeshCrypto.ballotPayload(proposalId, crypto.deviceName(),
            voteNorm, nonce, ts);
        String sig = crypto.sign(payload);
        JsonObject body = new JsonObject();
        body.addProperty("proposalId", proposalId);
        body.addProperty("vote", voteNorm);
        body.addProperty("nonce", nonce);
        body.addProperty("ts", ts);
        body.addProperty("publicKey", crypto.publicKeyBase64());
        body.addProperty("sig", sig);
        return post("/v1/quorum/vote", body.toString());
    }

    /** Liveness: returns HTTP status. */
    public int ping() throws IOException {
        HttpURLConnection conn = open("GET", "/v1/mesh/ping");
        int status = conn.getResponseCode();
        drain(status == 200 ? conn.getInputStream() : conn.getErrorStream());
        conn.disconnect();
        return status;
    }

    /** Fetch the peer chat ring as JSON text. */
    public String chat() throws IOException {
        HttpURLConnection conn = open("GET", "/v1/mesh/chat");
        int status = conn.getResponseCode();
        String body = readStream(status == 200 ? conn.getInputStream()
            : conn.getErrorStream());
        conn.disconnect();
        return body;
    }

    /** POST a chat line (owner-gated route). */
    public int chatPost(String text) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("text", text);
        return post("/v1/mesh/chat", body.toString());
    }

    /** Server counters/status JSON (GET /v1/mesh/status). */
    public String status() throws IOException {
        HttpURLConnection conn = open("GET", "/v1/mesh/status");
        int status = conn.getResponseCode();
        String body = readStream(status == 200 ? conn.getInputStream()
            : conn.getErrorStream());
        conn.disconnect();
        return body;
    }

    private HttpURLConnection open(String method, String path) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(baseUrl + path)
            .toURL().openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout((int) CONNECT_TIMEOUT_MS);
        conn.setReadTimeout((int) READ_TIMEOUT_MS);
        return conn;
    }

    private int post(String path, String json) throws IOException {
        HttpURLConnection conn = open("POST", path);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        try (OutputStream out = conn.getOutputStream()) {
            out.write(json.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
        int status = conn.getResponseCode();
        drain(status == 200 ? conn.getInputStream() : conn.getErrorStream());
        conn.disconnect();
        return status;
    }

    private static void drain(InputStream in) throws IOException {
        if (in != null) {
            byte[] buf = new byte[1024];
            while (in.read(buf) != -1) {
                // drain
            }
            in.close();
        }
    }

    private static String readStream(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        byte[] all = in.readAllBytes();
        in.close();
        return new String(all, StandardCharsets.UTF_8);
    }
}