package com.mindpalace.integration;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Serves precomputed context artifacts to explicitly authorized Tailscale peers.
 * The service is opt-in and never performs inference or accepts a filesystem path
 * from a request.
 */
public final class ContextBroadcaster implements AutoCloseable {
    private static final int MAX_ARTIFACT_BYTES = 1_000_000;
    private static final int MAX_COMMAND_OUTPUT_BYTES = 32_768;
    private static final Map<String, String> ARTIFACTS = Map.of(
        "/v1/shuttle", "shuttle.json",
        "/v1/cards", "cards.json",
        "/v1/triplets", "triplets.json");

    @FunctionalInterface
    public interface PeerIdentityResolver {
        PeerIdentity resolve(InetAddress address) throws IOException;
    }

    public record PeerIdentity(String login, Set<String> tags) {
        public PeerIdentity {
            login = login == null ? "" : login.trim();
            tags = tags == null ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(tags));
        }
    }

    private final Path artifactRoot;
    private final InetAddress bindAddress;
    private final int requestedPort;
    private final Set<String> allowedUsers;
    private final Set<String> allowedTags;
    private final PeerIdentityResolver identityResolver;
    private HttpServer server;
    private ExecutorService executor;

    public ContextBroadcaster(Path artifactRoot, InetAddress bindAddress, int port,
                              Set<String> allowedUsers, Set<String> allowedTags,
                              PeerIdentityResolver identityResolver) throws IOException {
        if (port < 0 || port > 65535) throw new IllegalArgumentException("invalid port");
        if (bindAddress == null) throw new IllegalArgumentException("bind address is required");
        if (identityResolver == null) throw new IllegalArgumentException("WhoIs resolver is required");
        if (allowedUsers.isEmpty() && allowedTags.isEmpty())
            throw new IllegalArgumentException("at least one Tailscale user or tag must be allowed");
        this.artifactRoot = artifactRoot.toAbsolutePath().normalize();
        Files.createDirectories(this.artifactRoot);
        this.bindAddress = bindAddress;
        this.requestedPort = port;
        this.allowedUsers = normalizedSet(allowedUsers);
        this.allowedTags = normalizedSet(allowedTags);
        this.identityResolver = identityResolver;
    }

    /** Return null when not explicitly enabled; configuration is fail-closed. */
    public static ContextBroadcaster fromConfig() throws IOException {
        if (!Boolean.parseBoolean(config("mindpalace.context.enabled",
                "MP_CONTEXT_BROADCAST_ENABLED", "false"))) return null;

        Set<String> users = configSet("mindpalace.context.allowedUsers", "MP_CONTEXT_ALLOWED_USERS");
        Set<String> tags = configSet("mindpalace.context.allowedTags", "MP_CONTEXT_ALLOWED_TAGS");
        if (users.isEmpty() && tags.isEmpty())
            throw new IOException("configure MP_CONTEXT_ALLOWED_USERS and/or MP_CONTEXT_ALLOWED_TAGS");

        String home = System.getProperty("user.home", ".");
        Path root = Path.of(config("mindpalace.context.dir", "MP_CONTEXT_DIR",
            Path.of(home, "AIGEN_SYS", "context").toString()));
        int port;
        try {
            port = Integer.parseInt(config("mindpalace.context.port", "MP_CONTEXT_PORT", "8765"));
        } catch (NumberFormatException e) {
            throw new IOException("invalid context broadcaster port", e);
        }

        String executable = config("mindpalace.tailscale.bin", "MP_TAILSCALE_BIN", "tailscale");
        InetAddress tailnetAddress = findTailnetAddress(executable);
        PeerIdentityResolver resolver = address ->
            parseWhoIs(runCommand(List.of(executable, "whois", "--json", address.getHostAddress())));
        return new ContextBroadcaster(root, tailnetAddress, port, users, tags, resolver);
    }

    public synchronized void start() throws IOException {
        if (server != null) return;
        server = HttpServer.create(new InetSocketAddress(bindAddress, requestedPort), 32);
        ThreadFactory threads = task -> {
            Thread thread = new Thread(task, "mindpalace-context-http");
            thread.setDaemon(true);
            return thread;
        };
        executor = Executors.newFixedThreadPool(4, threads);
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
    }

    public synchronized int port() {
        return server == null ? requestedPort : server.getAddress().getPort();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            if (!"GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 405, "method not allowed");
                return;
            }

            PeerIdentity identity;
            try {
                InetAddress peer = exchange.getRemoteAddress().getAddress();
                identity = identityResolver.resolve(peer);
            } catch (Exception e) {
                respond(exchange, 403, "forbidden");
                return;
            }
            if (!isAuthorized(identity, allowedUsers, allowedTags)) {
                respond(exchange, 403, "forbidden");
                return;
            }

            String filename = ARTIFACTS.get(exchange.getRequestURI().getPath());
            if (filename == null) {
                respond(exchange, 404, "not found");
                return;
            }

            Path file = artifactRoot.resolve(filename).toRealPath();
            if (!file.startsWith(artifactRoot.toRealPath()) || !Files.isRegularFile(file)) {
                respond(exchange, 404, "not found");
                return;
            }
            long size = Files.size(file);
            if (size > MAX_ARTIFACT_BYTES) {
                respond(exchange, 413, "artifact too large");
                return;
            }
            byte[] content = Files.readAllBytes(file);
            if (content.length > MAX_ARTIFACT_BYTES) {
                respond(exchange, 413, "artifact too large");
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            exchange.sendResponseHeaders(200, content.length);
            exchange.getResponseBody().write(content);
        } catch (IOException e) {
            respond(exchange, 404, "not found");
        } finally {
            exchange.close();
        }
    }

    private static void respond(HttpExchange exchange, int status, String message) throws IOException {
        byte[] body = (message + "\n").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }

    public static boolean isAuthorized(PeerIdentity identity, Set<String> users, Set<String> tags) {
        if (identity == null) return false;
        String login = identity.login().toLowerCase(Locale.ROOT);
        for (String user : users) {
            if (!login.isEmpty() && login.equals(user.trim().toLowerCase(Locale.ROOT))) return true;
        }
        for (String peerTag : identity.tags()) {
            for (String allowedTag : tags) {
                if (peerTag.equalsIgnoreCase(allowedTag.trim())) return true;
            }
        }
        return false;
    }

    public static PeerIdentity parseWhoIs(String json) throws IOException {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            String login = stringAt(root, "UserProfile", "LoginName");
            if (login.isEmpty()) login = stringAt(root, "LoginName");
            Set<String> tags = new LinkedHashSet<>();
            JsonElement nodeElement = root.get("Node");
            if (nodeElement != null && nodeElement.isJsonObject()) {
                JsonElement tagsElement = nodeElement.getAsJsonObject().get("Tags");
                if (tagsElement != null && tagsElement.isJsonArray()) {
                    tagsElement.getAsJsonArray().forEach(tag -> {
                        if (tag.isJsonPrimitive()) tags.add(tag.getAsString());
                    });
                }
            }
            if (login.isEmpty() && tags.isEmpty()) throw new IOException("WhoIs has no peer identity");
            return new PeerIdentity(login, tags);
        } catch (RuntimeException e) {
            throw new IOException("invalid Tailscale WhoIs response", e);
        }
    }

    private static String stringAt(JsonObject object, String... path) {
        JsonElement element = object;
        for (String key : path) {
            if (element == null || !element.isJsonObject()) return "";
            element = element.getAsJsonObject().get(key);
        }
        return element != null && element.isJsonPrimitive() ? element.getAsString().trim() : "";
    }

    private static InetAddress findTailnetAddress(String executable) throws IOException {
        String output = runCommand(List.of(executable, "ip", "-4"));
        for (String line : output.split("\\R")) {
            byte[] address = parseIpv4(line.trim());
            if (address != null) return InetAddress.getByAddress(address);
        }
        throw new IOException("tailscale ip -4 returned no IPv4 address");
    }

    private static byte[] parseIpv4(String text) {
        String[] parts = text.split("\\.", -1);
        if (parts.length != 4) return null;
        byte[] address = new byte[4];
        try {
            for (int i = 0; i < parts.length; i++) {
                if (parts[i].isEmpty() || parts[i].length() > 3) return null;
                int value = Integer.parseInt(parts[i]);
                if (value < 0 || value > 255) return null;
                address[i] = (byte) value;
            }
            return address;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String runCommand(List<String> command) throws IOException {
        Process process = new ProcessBuilder(command).start();
        StreamCollector stdout = new StreamCollector(process.getInputStream());
        StreamCollector stderr = new StreamCollector(process.getErrorStream());
        stdout.start();
        stderr.start();
        try {
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("Tailscale command timed out");
            }
            stdout.join(1_000);
            stderr.join(1_000);
            if (process.exitValue() != 0)
                throw new IOException("Tailscale command failed: " + stderr.text());
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
            super("mindpalace-context-command-output");
            this.input = input;
            setDaemon(true);
        }

        @Override
        public void run() {
            byte[] buffer = new byte[1024];
            try (input) {
                int count;
                while ((count = input.read(buffer)) != -1) {
                    int stored = Math.min(count, MAX_COMMAND_OUTPUT_BYTES - output.size());
                    if (stored > 0) output.write(buffer, 0, stored);
                }
            } catch (IOException ignored) {}
        }

        private String text() {
            return output.toString(StandardCharsets.UTF_8);
        }
    }

    private static Set<String> configSet(String property, String env) {
        String value = config(property, env, "");
        Set<String> result = new LinkedHashSet<>();
        Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isEmpty()).forEach(result::add);
        return result;
    }

    private static Set<String> normalizedSet(Set<String> values) {
        Set<String> result = new LinkedHashSet<>();
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) result.add(value.trim());
        }
        return Collections.unmodifiableSet(result);
    }

    private static String config(String property, String env, String fallback) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) value = System.getenv(env);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    @Override
    public synchronized void close() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }
}
