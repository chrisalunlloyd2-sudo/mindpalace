package com.mindpalace.tailscale;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Parsed {@code tailscale status --json} snapshot: this node's identity plus
 * the peer map. Used for MagicDNS name resolution (use 19) and for role
 * bootstrap from node tags (use 4).
 *
 * <p>Parsing is lenient about missing fields but strict about shape — a
 * non-object root is a hard {@link IOException}, matching the fail-closed
 * rule everywhere else in this package.</p>
 */
public final class StatusSnapshot {

    /** One tailnet node (self or peer), with its 100.x address list. */
    public record Node(String hostName, String dnsName, String login,
                       Set<String> tags, boolean online, Set<String> addresses) {

        public Node {
            hostName = hostName == null ? "" : hostName.trim();
            dnsName = dnsName == null ? "" : dnsName.trim();
            login = login == null ? "" : login.trim();
            tags = tags == null ? Set.of() : Set.copyOf(tags);
            addresses = addresses == null ? Set.of() : Set.copyOf(addresses);
        }

        /** DNS name without the trailing dot: {@code host.tail<word>.ts.net}. */
        public String fqdn() {
            String name = dnsName;
            return name.endsWith(".") ? name.substring(0, name.length() - 1) : name;
        }

        public boolean hasTag(String tag) {
            for (String owned : tags) {
                if (owned.equalsIgnoreCase(tag)) return true;
            }
            return false;
        }

        /** True when {@code ip} is one of this node's tailnet addresses. */
        public boolean hasAddress(String ip) {
            return ip != null && addresses.contains(ip.trim());
        }
    }

    private final Node self;
    private final Map<String, Node> peersByHost;
    private final Map<String, Node> peersByIp;
    private final String magicDnsSuffix;

    private StatusSnapshot(Node self, Map<String, Node> peersByHost,
                           Map<String, Node> peersByIp, String magicDnsSuffix) {
        this.self = self;
        this.peersByHost = peersByHost;
        this.peersByIp = peersByIp;
        this.magicDnsSuffix = magicDnsSuffix;
    }

    /** This node, or null when the status carried no Self object. */
    public Node self() {
        return self;
    }

    /** Tailnet-wide MagicDNS suffix, e.g. {@code tail703209.ts.net} (no leading dot). */
    public String magicDnsSuffix() {
        return magicDnsSuffix;
    }

    /** Read-only view of peers keyed by 100.x address. */
    public Map<String, Node> peers() {
        return Map.copyOf(peersByIp);
    }

    /** Look up any node (self or peer) by 100.x address or FQDN. */
    public Node nodeByIp(String ip) {
        if (ip == null || ip.isBlank()) return null;
        String needle = ip.trim();
        if (self != null && self.hasAddress(needle)) return self;
        Node byIp = peersByIp.get(needle);
        if (byIp != null) return byIp;
        for (Node node : peersByIp.values()) {
            if (node.fqdn().equalsIgnoreCase(needle)) return node;
        }
        return null;
    }

    /** Look up any node by bare hostname or full MagicDNS FQDN (case-insensitive). */
    public Node nodeByName(String name) {
        if (name == null || name.isBlank()) return null;
        String needle = name.trim().toLowerCase(Locale.ROOT);
        if (self != null
            && (self.hostName().toLowerCase(Locale.ROOT).equals(needle)
                || self.fqdn().toLowerCase(Locale.ROOT).equals(needle))) {
            return self;
        }
        Node byHost = peersByHost.get(needle);
        if (byHost != null) return byHost;
        for (Node node : peersByHost.values()) {
            if (node.fqdn().toLowerCase(Locale.ROOT).equals(needle)) return node;
        }
        return null;
    }

    /** Parse the JSON emitted by {@code tailscale status --json}. */
    public static StatusSnapshot parse(String json) throws IOException {
        JsonObject root;
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonObject()) throw new IllegalStateException("not an object");
            root = parsed.getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("invalid tailscale status JSON", e);
        }

        String suffix = string(root, "MagicDNSSuffix");
        JsonObject currentTailnet = objectAt(root, "CurrentTailnet");
        if (suffix.isEmpty() && currentTailnet != null) {
            suffix = string(currentTailnet, "MagicDNSSuffix");
        }

        JsonObject users = objectAt(root, "User");
        Node self = parseNode(objectAt(root, "Self"), users);
        Map<String, Node> byHost = new LinkedHashMap<>();
        Map<String, Node> byIp = new LinkedHashMap<>();
        JsonObject peers = objectAt(root, "Peer");
        if (peers != null) {
            for (Map.Entry<String, JsonElement> entry : peers.entrySet()) {
                if (!entry.getValue().isJsonObject()) continue;
                Node node = parseNode(entry.getValue().getAsJsonObject(), users);
                if (node == null) continue;
                if (!node.hostName().isEmpty()) {
                    byHost.putIfAbsent(node.hostName().toLowerCase(Locale.ROOT), node);
                }
                if (!node.fqdn().isEmpty()) {
                    byHost.putIfAbsent(node.fqdn().toLowerCase(Locale.ROOT), node);
                }
                for (String address : node.addresses()) {
                    byIp.putIfAbsent(address, node);
                }
            }
        }
        return new StatusSnapshot(self, byHost, byIp, suffix);
    }

    private static Node parseNode(JsonObject obj, JsonObject users) {
        if (obj == null) return null;
        String hostName = string(obj, "HostName");
        String dnsName = string(obj, "DNSName");
        JsonObject profile = objectAt(obj, "UserProfile");
        String login = string(obj, "LoginName");
        if (login.isEmpty() && profile != null) login = string(profile, "LoginName");
        if (login.isEmpty() && users != null) {
            // Real `tailscale status --json` carries only UserID per node; the login lives
            // in the top-level "User" table.
            login = string(objectAt(users, string(obj, "UserID")), "LoginName");
        }
        JsonElement nodeObj = obj.get("Node");
        JsonObject tagsSource = nodeObj != null && nodeObj.isJsonObject()
            ? nodeObj.getAsJsonObject()
            : obj;
        Set<String> tags = new LinkedHashSet<>();
        JsonArray tagsArray = arrayAt(tagsSource, "Tags");
        if (tagsArray != null) {
            for (JsonElement tag : tagsArray) {
                if (tag.isJsonPrimitive()) tags.add(tag.getAsString().trim());
            }
        }
        boolean online = booleanAt(obj, "Online");
        Set<String> addresses = new LinkedHashSet<>();
        JsonArray ips = arrayAt(obj, "TailscaleIPs");
        if (ips == null) ips = arrayAt(obj, "Addresses");
        if (ips != null) {
            for (JsonElement ip : ips) {
                if (ip.isJsonPrimitive()) addresses.add(ip.getAsString().trim());
            }
        }
        if (hostName.isEmpty() && dnsName.isEmpty() && addresses.isEmpty()) return null;
        return new Node(hostName, dnsName, login, tags, online, addresses);
    }

    private static JsonArray arrayAt(JsonObject obj, String key) {
        JsonElement element = obj == null ? null : obj.get(key);
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : null;
    }

    private static JsonObject objectAt(JsonObject obj, String key) {
        JsonElement element = obj == null ? null : obj.get(key);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private static String string(JsonObject obj, String key) {
        JsonElement element = obj == null ? null : obj.get(key);
        return element != null && element.isJsonPrimitive()
            ? element.getAsString().trim() : "";
    }

    private static boolean booleanAt(JsonObject obj, String key) {
        JsonElement element = obj == null ? null : obj.get(key);
        return element != null && element.isJsonPrimitive() && element.getAsBoolean();
    }
}