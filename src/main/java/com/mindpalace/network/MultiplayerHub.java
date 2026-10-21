package com.mindpalace.network;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * #138 (H49, step 108): the multiplayer contract — player presence + chat
 * shared over ONE WebSocket, additive-only and OFF BY DEFAULT
 * (mindpalace.mp.enabled / MP_MP_ENABLED), mirroring the ContextBroadcaster
 * fail-closed pattern and the #39/#40 zero-network contract for
 * single-player.
 *
 * Footprint decision: JDK's own {@link ServerSocket} + a hand-rolled minimal
 * RFC6455 server (upgrade handshake + text frames) — ZERO new dependencies
 * and full control of the raw socket (an HttpServer-based upgrade would need
 * exchange-stream hacks; the socket path is simpler and testable).
 *
 * Wire protocol (one broadcast channel, JSON text frames):
 *   client → hub: {"type":"presence","who":...,"x":...,"y":...,"z":...}
 *                 {"type":"chat","who":...,"text":...}
 *   hub → all:    every received text line, plus roster lines
 *                 {"type":"roster","peers":[names]} after each join/leave.
 *
 * RFC6455 coverage: exactly what the loopback two-instance demo needs —
 * GET upgrade with Sec-WebSocket-Key/Accept (SHA-1 + magic GUID), masked
 * client frames, 7-bit + 16-bit + 64-bit payload lengths, FIN text frames
 * (no fragmentation), opcode 8 close (echoed), opcode 9 ping → pong.
 * Server-to-client frames are unmasked (RFC6455 §5.3: server MUST NOT mask).
 */
public final class MultiplayerHub implements AutoCloseable {

    public static final int DEFAULT_PORT = 11777;
    static final String WS_MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private ServerSocket server;
    private final int port;
    private final List<Peer> peers = new CopyOnWriteArrayList<>();
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "mindpalace-mp-peer");
        t.setDaemon(true);
        return t;
    });

    static final class Peer {
        final Socket socket;
        final OutputStream out;
        volatile String name = "?";
        Peer(Socket s, OutputStream o) { socket = s; out = o; }
    }

    public MultiplayerHub(int port) {
        if (port < 0 || port > 65535) throw new IllegalArgumentException("invalid port");
        this.port = port;
    }

    /** Bind + listen. Returns the ACTUAL bound port (useful with port 0). */
    public synchronized int start() throws IOException {
        if (server != null) return server.getLocalPort();
        server = new ServerSocket(port);
        Thread acceptor = new Thread(this::acceptLoop, "mindpalace-mp-accept");
        acceptor.setDaemon(true);
        acceptor.start();
        return server.getLocalPort();
    }

    /** The actually-bound port for clients to dial (-1 when not started). */
    public synchronized int boundPort() {
        return server == null ? -1 : server.getLocalPort();
    }

    /**
     * #138 gate: hub boots only when mindpalace.mp.enabled (or
     * MP_MP_ENABLED env) is "true"/"1". Default = OFF (fail-closed).
     */
    public static boolean enabled() {
        String v = System.getProperty("mindpalace.mp.enabled",
            System.getenv().getOrDefault("MP_MP_ENABLED", "false"));
        return "true".equalsIgnoreCase(v) || "1".equals(v);
    }

    /** Connected peer count (health lines + tests). */
    public synchronized int peerCount() { return peers.size(); }

    /** RFC6455 §4.2.2 server accept key (test-visible, deterministic). */
    public static String acceptKey(String secWebSocketKey) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] hash = sha1.digest(
                (secWebSocketKey + WS_MAGIC).getBytes(StandardCharsets.US_ASCII));
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new IllegalStateException("sha-1 unavailable", e);
        }
    }

    private void acceptLoop() {
        while (server != null && !server.isClosed()) {
            try {
                Socket s = server.accept();
                s.setTcpNoDelay(true);
                pool.submit(() -> {
                    Peer peer = null;
                    try {
                        peer = handshake(s);
                        if (peer == null) return; // not a websocket upgrade
                        peers.add(peer);
                        broadcastRoster();
                        servePeer(peer);
                    } catch (IOException ignored) {
                    } finally {
                        if (peer != null) peers.remove(peer);
                        try { s.close(); } catch (IOException ignored) { }
                        if (peer != null) broadcastRoster();
                    }
                });
            } catch (IOException ioe) {
                if (server == null || server.isClosed()) return; // normal close
            }
        }
    }

    /**
     * RFC6455 opening handshake over the raw socket. Returns the peer ready
     * for framed I/O, or null when the request is not a websocket upgrade.
     */
    Peer handshake(Socket s) throws IOException {
        InputStream in = s.getInputStream();
        String line = readLine(in);
        if (line == null || !line.toUpperCase(Locale.ROOT).startsWith("GET ")) return null;
        String key = null;
        boolean upgradeWs = false;
        while (true) {
            String h = readLine(in);
            if (h == null || h.isEmpty()) break; // blank line ends headers
            String lower = h.toLowerCase(Locale.ROOT);
            if (lower.startsWith("sec-websocket-key:"))
                key = h.substring(h.indexOf(':') + 1).trim();
            if (lower.startsWith("upgrade:") && lower.contains("websocket")) upgradeWs = true;
        }
        if (key == null || !upgradeWs) return null;
        OutputStream out = s.getOutputStream();
        String resp = "HTTP/1.1 101 Switching Protocols\r\n"
            + "Upgrade: websocket\r\n"
            + "Connection: Upgrade\r\n"
            + "Sec-WebSocket-Accept: " + acceptKey(key) + "\r\n\r\n";
        out.write(resp.getBytes(StandardCharsets.US_ASCII));
        out.flush();
        return new Peer(s, out);
    }

    /** Read one CRLF-terminated header line (ASCII). */
    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder(80);
        int prev = -1, c;
        while ((c = in.read()) != -1) {
            if (prev == '\r' && c == '\n') return sb.substring(0, sb.length() - 1);
            sb.append((char) c);
            prev = c;
            if (sb.length() > 8192) return null; // header abuse bound
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    /** Read loop for one peer: parse frames, broadcast text to everyone. */
    private void servePeer(Peer peer) {
        try {
            DataInputStream in = new DataInputStream(peer.socket.getInputStream());
            while (true) {
                int b0 = in.readUnsignedByte();
                int b1 = in.readUnsignedByte();
                int op = b0 & 0x0F;
                boolean masked = (b1 & 0x80) != 0;
                long len = b1 & 0x7FL;
                if (len == 126) len = in.readUnsignedShort();
                else if (len == 127) len = in.readLong();
                if (len > 65_536) { sendClose(peer); return; } // sanity bound
                byte[] mask = new byte[4];
                if (masked) in.readFully(mask);
                byte[] payload = new byte[(int) len];
                in.readFully(payload);
                if (masked) for (int k = 0; k < payload.length; k++)
                    payload[k] ^= mask[k % 4];
                if (op == 8) { sendClose(peer); return; }         // close
                if (op == 9) { writeFrame(peer, (byte) 0x8A, payload); continue; } // ping
                if (op == 1) {                                    // text
                    String text = new String(payload, StandardCharsets.UTF_8);
                    if (rememberName(peer, text)) broadcastRoster();
                    broadcast(text);
                }
                // other opcodes (binary/cont/pongs) ignored — demo scope
            }
        } catch (IOException ignored) {
            // peer dropped — handled by the accept-loop finally
        }
    }

    /**
     * Track display names from chat/presence lines (best-effort roster).
     * Returns true when the name CHANGED — callers re-broadcast the roster.
     */
    private static boolean rememberName(Peer peer, String text) {
        try {
            com.google.gson.JsonObject o = com.google.gson.JsonParser.parseString(text)
                .getAsJsonObject();
            if (o.has("who") && !o.get("who").isJsonNull()) {
                String next = o.get("who").getAsString();
                if (!next.equals(peer.name)) { peer.name = next; return true; }
            }
        } catch (Exception ignored) { /* non-JSON lines keep "?" */ }
        return false;
    }

    /** Broadcast one text message to every connected peer. */
    public synchronized void broadcast(String text) {
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        for (Peer p : peers) writeFrame(p, (byte) 0x81, payload);
    }

    /** Roster broadcast: current peer display names. */
    void broadcastRoster() {
        StringBuilder sb = new StringBuilder("{\"type\":\"roster\",\"peers\":[");
        boolean first = true;
        for (Peer p : peers) {
            if (!first) sb.append(',');
            sb.append('"').append(p.name.replace("\"", "")).append('"');
            first = false;
        }
        sb.append("]}");
        broadcast(sb.toString());
    }

    private void sendClose(Peer p) {
        writeFrame(p, (byte) 0x88, new byte[0]);
    }

    /** Minimal server frame: FIN + opcode, unmasked, 7-bit length. */
    static void writeFrame(Peer p, byte opcode, byte[] payload) {
        try {
            synchronized (p.out) {
                p.out.write(opcode);
                p.out.write(payload.length & 0x7F);
                p.out.write(payload);
                p.out.flush();
            }
        } catch (IOException ignored) { /* dead peer */ }
    }

    /** Snapshot of current peer names (tests + health lines). */
    public synchronized List<String> peerNames() {
        List<String> out = new ArrayList<>();
        for (Peer p : peers) out.add(p.name);
        return out;
    }

    @Override public synchronized void close() {
        if (server == null) return;
        try { server.close(); } catch (IOException ignored) { }
        server = null;
        for (Peer p : peers) {
            try { p.socket.close(); } catch (IOException ignored) { }
        }
        peers.clear();
        pool.shutdownNow();
    }
}