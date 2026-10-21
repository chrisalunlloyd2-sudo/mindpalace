package com.mindpalace.network;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * #138 (H49, step 108): the client half of the multiplayer contract —
 * connects to a {@link MultiplayerHub}, sends presence/chat as masked
 * RFC6455 text frames, and delivers received text frames (typed JSON:
 * presence / chat / roster) to a registered listener on a reader thread.
 * Zero new dependencies; daemon threads only (never pins the JVM);
 * all network behavior inert until dialed — off-by-default contract
 * lives in the engine wiring, this class is pure capability.
 */
public final class MultiplayerClient implements AutoCloseable {

    private Socket socket;
    private DataInputStream in;
    private OutputStream out;
    private Thread reader;
    private volatile Consumer<String> listener = m -> { };
    private volatile boolean closed;

    /** Register the received-frame listener (single channel, latest wins). */
    public void onMessage(Consumer<String> l) { this.listener = (l == null ? m -> { } : l); }

    /** Dial + RFC6455 handshake. Returns actual server accept header check. */
    public void connect(String host, int port, String playerName) throws IOException {
        socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), 5_000);
        socket.setTcpNoDelay(true);
        out = socket.getOutputStream();
        InputStream raw = socket.getInputStream();

        byte[] nonce = new byte[16];
        new SecureRandom().nextBytes(nonce);
        String key = Base64.getEncoder().encodeToString(nonce);
        String req = "GET / HTTP/1.1\r\n"
            + "Host: " + host + ":" + port + "\r\n"
            + "Upgrade: websocket\r\n"
            + "Connection: Upgrade\r\n"
            + "Sec-WebSocket-Key: " + key + "\r\n"
            + "Sec-WebSocket-Version: 13\r\n\r\n";
        out.write(req.getBytes(StandardCharsets.US_ASCII));
        out.flush();

        // Minimal client-side handshake verify: 101 + matching accept key.
        String status = readLine(raw);
        if (status == null || !status.contains("101")) {
            throw new IOException("handshake refused: " + status);
        }
        String expected = MultiplayerHub.acceptKey(key);
        while (true) {
            String h = readLine(raw);
            if (h == null || h.isEmpty()) break;
            if (h.toLowerCase(java.util.Locale.ROOT).startsWith("sec-websocket-accept:")
                && !h.substring(h.indexOf(':') + 1).trim().equals(expected)) {
                throw new IOException("accept-key mismatch");
            }
        }

        in = new DataInputStream(raw);
        reader = new Thread(this::readLoop, "mindpalace-mp-reader-" + playerName);
        reader.setDaemon(true);
        reader.start();
    }

    /** True once the handshake completed and the reader thread is live. */
    public boolean isConnected() {
        return socket != null && socket.isConnected() && !socket.isClosed() && !closed;
    }

    /** Send a presence line (who + world coords). */
    public void sendPresence(String who, double x, double y, double z) throws IOException {
        sendText("{\"type\":\"presence\",\"who\":\"" + esc(who)
            + "\",\"x\":" + x + ",\"y\":" + y + ",\"z\":" + z + "}");
    }

    /** Send a chat line (who + text). */
    public void sendChat(String who, String text) throws IOException {
        sendText("{\"type\":\"chat\",\"who\":\"" + esc(who)
            + "\",\"text\":\"" + esc(text) + "\"}");
    }

    /** Masked client text frame (RFC6455 §5.3: clients MUST mask). */
    public synchronized void sendText(String text) throws IOException {
        if (out == null || closed) throw new IOException("not connected");
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        byte maskKey0 = (byte) (Math.random() * 256);
        byte maskKey1 = (byte) (Math.random() * 256);
        byte maskKey2 = (byte) (Math.random() * 256);
        byte maskKey3 = (byte) (Math.random() * 256);
        out.write((byte) 0x81); // FIN + text
        int n = payload.length;
        if (n < 126) {
            out.write((byte) (0x80 | n));
        } else if (n < 65_536) {
            out.write((byte) (0x80 | 126));
            out.write((byte) ((n >> 8) & 0xFF));
            out.write((byte) (n & 0xFF));
        } else {
            out.write((byte) (0x80 | 127));
            for (int shift = 56; shift >= 0; shift -= 8)
                out.write((byte) ((n >> shift) & 0xFF));
        }
        out.write(maskKey0); out.write(maskKey1);
        out.write(maskKey2); out.write(maskKey3);
        for (int i = 0; i < n; i++)
            payload[i] ^= (i % 4 == 0 ? maskKey0 : i % 4 == 1 ? maskKey1
                : i % 4 == 2 ? maskKey2 : maskKey3);
        out.write(payload);
        out.flush();
    }

    /** Reader loop: unmask server frames, dispatch to listener. */
    private void readLoop() {
        try {
            while (!closed) {
                int b0 = in.readUnsignedByte();
                int b1 = in.readUnsignedByte();
                int op = b0 & 0x0F;
                long len = b1 & 0x7FL;
                if (len == 126) len = in.readUnsignedShort();
                else if (len == 127) len = in.readLong();
                if ((b1 & 0x80) != 0) { // server frames are unmasked, but be lenient
                    byte[] skipMask = new byte[4];
                    in.readFully(skipMask);
                }
                byte[] payload = new byte[(int) len];
                in.readFully(payload);
                if (op == 1) listener.accept(new String(payload, StandardCharsets.UTF_8));
                if (op == 8) return; // server closed
            }
        } catch (IOException ignored) {
            // socket dropped — listener sees silence, close() cleans up
        }
    }

    /** CRLF line reader for the handshake phase (ASCII). */
    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder(80);
        int prev = -1, c;
        while ((c = in.read()) != -1) {
            if (prev == '\r' && c == '\n') return sb.substring(0, sb.length() - 1);
            sb.append((char) c);
            prev = c;
            if (sb.length() > 8192) return null;
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @Override public synchronized void close() {
        closed = true;
        try { if (socket != null) socket.close(); } catch (IOException ignored) { }
    }

    /** Collected messages helper for tests/demos (bounded). */
    public static List<String> drain(List<String> sink) {
        List<String> out = new java.util.ArrayList<>(sink);
        sink.clear();
        return out;
    }
}