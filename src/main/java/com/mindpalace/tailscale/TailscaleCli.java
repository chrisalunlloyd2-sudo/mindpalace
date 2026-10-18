package com.mindpalace.tailscale;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Thin wrapper over the {@code tailscale} CLI — the single place this game
 * shells out to Tailscale. All commands are local, read-only queries (ip,
 * status, whois, dns status); nothing here mutates tailnet state, exposes
 * Funnel, or opens a public port.
 *
 * <p>The executable is resolved once from
 * {@code -Dmindpalace.tailscale.bin} / {@code MP_TAILSCALE_BIN}
 * (default {@code tailscale}) so every caller agrees on one binary.</p>
 */
public final class TailscaleCli {

    /** Default per-command timeout; the CLI is a local IPC call, not a network round trip. */
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(3);
    private static final int MAX_OUTPUT_BYTES = 1 << 20;

    private final String executable;
    private final Duration timeout;

    TailscaleCli(String executable, Duration timeout) {
        this.executable = executable;
        this.timeout = timeout;
    }

    /** Default CLI binding: {@code tailscale} on PATH, 3s timeout per command. */
    public static TailscaleCli defaultCli() {
        String bin = System.getProperty("mindpalace.tailscale.bin");
        if (bin == null || bin.isBlank()) bin = System.getenv("MP_TAILSCALE_BIN");
        if (bin == null || bin.isBlank()) {
            // The Windows installer does not put tailscale on PATH.
            java.io.File win = new java.io.File("C:\\Program Files\\Tailscale\\tailscale.exe");
            bin = win.isFile() ? win.getPath() : "tailscale";
        }
        return new TailscaleCli(bin.trim(), DEFAULT_TIMEOUT);
    }

    /** Test seam: explicit binary path and timeout. */
    public static TailscaleCli of(String executable, Duration timeout) {
        return new TailscaleCli(executable, timeout);
    }

    /** The IPv4 tailnet address of this node, or empty when off-mesh. */
    public String ip() throws IOException {
        String output = run(List.of(executable, "ip", "-4"));
        for (String line : output.split("\\R")) {
            String candidate = line.trim();
            if (!candidate.isEmpty()) return candidate;
        }
        throw new IOException("tailscale ip -4 returned no IPv4 address");
    }

    /**
     * Full peer map parsed from {@code tailscale status --json}, including
     * this node's own identity and DNS suffix.
     */
    public StatusSnapshot status() throws IOException {
        return StatusSnapshot.parse(
            run(List.of(executable, "status", "--json")));
    }

    /** Who is connected from {@code address} (a tailnet IP). */
    public String whoIsJson(String address) throws IOException {
        return run(List.of(executable, "whois", "--json", address));
    }

    /** Raw MagicDNS configuration text ({@code tailscale dns status}). */
    public String dnsStatus() throws IOException {
        return run(List.of(executable, "dns", "status"));
    }

    public String executable() {
        return executable;
    }

    /** Whether {@code host} is a syntactically valid untagged MagicDNS hostname. */
    public static boolean isValidMagicHostname(String host) {
        return MagicDns.isValidHostname(host);
    }

    private String run(List<String> command) throws IOException {
        Process process = new ProcessBuilder(command).start();
        StreamCollector stdout = new StreamCollector(process.getInputStream());
        StreamCollector stderr = new StreamCollector(process.getErrorStream());
        stdout.start();
        stderr.start();
        try {
            long millis = timeout.toMillis();
            if (!process.waitFor(millis, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new IOException("Tailscale command timed out: "
                    + command.get(0));
            }
            stdout.join(1_000);
            stderr.join(1_000);
            if (process.exitValue() != 0) {
                String err = stderr.text();
                throw new IOException("Tailscale command failed ("
                    + command.get(0) + "): "
                    + (err.isBlank() ? "exit " + process.exitValue() : err));
            }
            return stdout.text();
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while querying Tailscale", e);
        }
    }

    private static final class StreamCollector extends Thread {
        private final InputStream input;
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();

        private StreamCollector(InputStream input) {
            super("mindpalace-tailscale-cli");
            this.input = input;
            setDaemon(true);
        }

        @Override
        public void run() {
            byte[] buffer = new byte[4096];
            try (input) {
                int count;
                while ((count = input.read(buffer)) != -1) {
                    int stored = Math.min(count, MAX_OUTPUT_BYTES - output.size());
                    if (stored > 0) output.write(buffer, 0, stored);
                }
            } catch (IOException ignored) {}
        }

        private String text() {
            return output.toString(StandardCharsets.UTF_8);
        }
    }
}