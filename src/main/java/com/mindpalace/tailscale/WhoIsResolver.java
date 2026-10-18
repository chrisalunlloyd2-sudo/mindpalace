package com.mindpalace.tailscale;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Resolves a peer IP to a {@link TailnetIdentity} via
 * {@code tailscale whois --json <ip>} (plan use 2: identity equals tailnet
 * user). This is the only identity mechanism in the game — no request-supplied
 * name is ever trusted.
 *
 * <p>Implementations must be thread-safe: HTTP executors call these from
 * multiple worker threads.</p>
 */
@FunctionalInterface
public interface WhoIsResolver {

    /** Identity for {@code address}/port, or {@code null} when unresolvable. */
    TailnetIdentity resolve(String address, int port) throws IOException;

    /** Convenience for socket-handler call sites. */
    default TailnetIdentity resolve(java.net.InetAddress address) throws IOException {
        return resolve(address.getHostAddress(), 0);
    }

    /** Parse a {@code tailscale whois --json} payload into an identity. */
    static TailnetIdentity parseWhoIs(String json) throws IOException {
        JsonObject root;
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonObject()) throw new IllegalStateException("not an object");
            root = parsed.getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("invalid Tailscale WhoIs response", e);
        }

        String login = stringAt(root, "UserProfile", "LoginName");
        if (login.isEmpty()) login = stringAt(root, "LoginName");

        Set<String> tags = new LinkedHashSet<>();
        JsonElement nodeElement = root.get("Node");
        String deviceName = "";
        if (nodeElement != null && nodeElement.isJsonObject()) {
            JsonObject node = nodeElement.getAsJsonObject();
            deviceName = stringAt(node, "Name").replaceFirst("\\.$", "");
            JsonArray tagsArray = node.getAsJsonArray("Tags");
            if (tagsArray != null) {
                for (JsonElement tag : tagsArray) {
                    if (tag.isJsonPrimitive()) tags.add(tag.getAsString().trim());
                }
            }
        }
        if (deviceName.isEmpty()) deviceName = stringAt(root, "HostName");
        if (deviceName.isEmpty()) deviceName = stringAt(root, "DNSName");
        if (deviceName.isEmpty()) deviceName = stringAt(root, "NodeName");

        // A peer with neither login nor tags is unattributable — fail-closed.
        if (login.isEmpty() && tags.isEmpty()) {
            throw new IOException("WhoIs has no peer identity");
        }
        return new TailnetIdentity(login, MagicDns.hostPart(deviceName), tags);
    }

    /** Nested lookup helper, "" on any miss. */
    private static String stringAt(JsonObject object, String... path) {
        JsonElement element = object;
        for (String key : path) {
            if (element == null || !element.isJsonObject()) return "";
            element = element.getAsJsonObject().get(key);
        }
        return element != null && element.isJsonPrimitive()
            ? element.getAsString().trim() : "";
    }

    /** CLI-backed resolver: one whois subprocess per (uncached) lookup. */
    final class Cli implements WhoIsResolver {

        private final TailscaleCli cli;
        private final long cacheTtlMillis;
        private final Object lock = new Object();
        private java.util.Map<String, CacheEntry> cache = new java.util.HashMap<>();

        public Cli(TailscaleCli cli) {
            this(cli, 60_000);
        }

        /** Positive identity results are cached for {@code cacheTtlMillis}. */
        public Cli(TailscaleCli cli, long cacheTtlMillis) {
            this.cli = java.util.Objects.requireNonNull(cli, "cli");
            this.cacheTtlMillis = Math.max(0, cacheTtlMillis);
        }

        @Override
        public TailnetIdentity resolve(String address, int port) throws IOException {
            if (address == null || address.isBlank()) return null;
            String key = address.trim() + ":" + port;
            long now = System.currentTimeMillis();
            synchronized (lock) {
                CacheEntry hit = cache.get(key);
                if (hit != null && (cacheTtlMillis <= 0 || now - hit.atMillis < cacheTtlMillis)) {
                    return hit.identity;
                }
            }
            TailnetIdentity identity = parseWhoIs(cli.whoIsJson(address));
            synchronized (lock) {
                cache.put(key, new CacheEntry(identity, now));
            }
            return identity;
        }

        private record CacheEntry(TailnetIdentity identity, long atMillis) {}
    }
}