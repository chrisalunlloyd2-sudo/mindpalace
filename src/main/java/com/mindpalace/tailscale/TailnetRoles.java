package com.mindpalace.tailscale;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * ACL-tag-driven game roles (plan use 4). Tags are assigned on the tailnet
 * admin side; this class is the single mapping from tag sets to in-game
 * capabilities, applied by every tailnet-facing server (broadcaster, T2 vote
 * transport, T3 admin/spectator).
 *
 * <p>Rules:</p>
 * <ul>
 *   <li><b>Fail-closed.</b> A peer with no identity, no matching tag and no
 *       allowlisted login has zero capabilities — never a default grant.</li>
 *   <li>Tags and logins are case-insensitive; the canonical tag strings are
 *       {@code tag:architect}, {@code tag:builder}, {@code tag:voter},
 *       {@code tag:spectator}.</li>
 *   <li>Role resolution is additive: a node with several role tags gets the
 *       union of capabilities.</li>
 * </ul>
 */
public final class TailnetRoles {

    public static final String TAG_ARCHITECT = "tag:architect";
    public static final String TAG_BUILDER = "tag:builder";
    public static final String TAG_VOTER = "tag:voter";
    public static final String TAG_SPECTATOR = "tag:spectator";

    /** Capabilities a peer holds. Enum set, cheap to test and copy. */
    public enum Capability { READ_PUBLIC, READ_PRIVATE, VOTE, BUILD, ADMIN }

    /**
     * Immutable allowlist bundle shared by one or more servers: who counts as
     * an architect by login, which untagged logins are allowed at all, and
     * which ACL tags carry roles. {@link #DEFAULT} is the game's standard
     * scope; servers may intersect it down (spectator HUD) but never widen it
     * beyond the tag roles.
     */
    public static final class Scope {
        private final Set<String> architectLogins;
        private final Set<String> allowUsers;
        private final Set<String> allowTags;

        public Scope(Set<String> architectLogins, Set<String> allowUsers,
                     Set<String> allowTags) {
            this.architectLogins = lower(architectLogins);
            this.allowUsers = lower(allowUsers);
            this.allowTags = lower(allowTags);
        }

        /** Named constructor mirroring the broadcaster's config order. */
        public static Scope of(Set<String> architectLogins, Set<String> allowUsers,
                               Set<String> allowTags) {
            return new Scope(architectLogins, allowUsers, allowTags);
        }

        /** The game-wide default scope: standard role tags + owner login allowlist. */
        public static Scope defaultScope() {
            Set<String> architectLogins = new HashSet<>();
            Set<String> allowUsers = new HashSet<>();
            Set<String> allowTags = new HashSet<>();
            SystemTags.applyDefaults(architectLogins, allowUsers, allowTags);
            return new Scope(architectLogins, allowUsers, allowTags);
        }

        public Set<String> architectLogins() { return architectLogins; }
        public Set<String> allowUsers() { return allowUsers; }
        public Set<String> allowTags() { return allowTags; }

        private static Set<String> lower(Set<String> values) {
            Set<String> out = new HashSet<>();
            if (values != null) {
                for (String value : values) {
                    if (value != null && !value.isBlank()) {
                        out.add(value.trim().toLowerCase(Locale.ROOT));
                    }
                }
            }
            return Set.copyOf(out);
        }
    }

    /**
     * Resolve capabilities for a peer under a scope. Never returns null; an
     * unknown peer gets the empty set (fail-closed).
     */
    public static Set<Capability> capabilities(TailnetIdentity identity, Scope scope) {
        if (identity == null || identity.isEmpty() || scope == null) return Set.of();
        Set<Capability> caps = new HashSet<>();

        boolean tagged = false;
        for (String tag : identity.tags()) {
            String lower = tag.toLowerCase(Locale.ROOT);
            if (!scope.allowTags().contains(lower)) continue;
            tagged = true;
            switch (lower) {
                case TAG_ARCHITECT -> {
                    caps.add(Capability.READ_PUBLIC);
                    caps.add(Capability.READ_PRIVATE);
                    caps.add(Capability.VOTE);
                    caps.add(Capability.BUILD);
                    caps.add(Capability.ADMIN);
                }
                case TAG_BUILDER -> {
                    caps.add(Capability.READ_PUBLIC);
                    caps.add(Capability.READ_PRIVATE);
                    caps.add(Capability.BUILD);
                }
                case TAG_VOTER -> {
                    caps.add(Capability.READ_PUBLIC);
                    caps.add(Capability.VOTE);
                }
                case TAG_SPECTATOR -> caps.add(Capability.READ_PUBLIC);
                default -> { }
            }
        }
        if (tagged) return Set.copyOf(caps);

        // Untagged peers: explicit login allowlist only (e.g. the owner login).
        String login = identity.loginLower();
        if (!scope.allowUsers().contains(login)) return Set.of();
        caps.add(Capability.READ_PUBLIC);
        if (scope.architectLogins().contains(login)) {
            caps.add(Capability.READ_PRIVATE);
            caps.add(Capability.VOTE);
            caps.add(Capability.BUILD);
            caps.add(Capability.ADMIN);
        }
        return Set.copyOf(caps);
    }

    /** True when the peer may proceed at all under the game default scope. */
    public static boolean isAuthorized(TailnetIdentity identity) {
        return !capabilities(identity, Scope.defaultScope()).isEmpty();
    }

    /** True when the peer may proceed at all under {@code scope}. */
    public static boolean isAuthorized(TailnetIdentity identity, Scope scope) {
        return !capabilities(identity, scope).isEmpty();
    }

    /**
     * System-level defaults read from game config properties, so test rig and
     * live game agree without code changes.
     */
    private static final class SystemTags {
        static void applyDefaults(Set<String> architectLogins, Set<String> allowUsers,
                                  Set<String> allowTags) {
            // Me-to-me trust: the tailnet owner is whoever this node is logged in as. Every
            // device on that login (PCs, phone, agents) is the Architect; no ACL tag or admin
            // console step is needed. An explicit property/env value overrides the derived one.
            String owner = prop("mindpalace.tailscale.architect.login",
                "MP_TAILSCALE_ARCHITECT_LOGIN", "");
            if (owner.isEmpty()) owner = ownerLogin();
            if (!owner.isEmpty()) {
                architectLogins.add(owner);
                allowUsers.add(owner);
            }
            String users = prop("mindpalace.tailscale.allow.users",
                "MP_TAILSCALE_ALLOW_USERS", "");
            for (String user : users.split(",")) {
                if (!user.isBlank()) allowUsers.add(user.trim());
            }
            String tags = prop("mindpalace.tailscale.allow.tags",
                "MP_TAILSCALE_ALLOW_TAGS",
                "tag:architect,tag:builder,tag:voter,tag:spectator");
            for (String tag : tags.split(",")) {
                if (!tag.isBlank()) allowTags.add(tag.trim().toLowerCase(Locale.ROOT));
            }
        }

        private static volatile String cachedOwner;
        private static volatile long ownerCheckedAt;

        /** This node's own tailnet login, or "" when tailscale is absent (fail-closed). Cached 60 s. */
        static String ownerLogin() {
            long now = System.currentTimeMillis();
            String cached = cachedOwner;
            if (cached != null && now - ownerCheckedAt < 60_000L) return cached;
            String login = "";
            try {
                StatusSnapshot.Node self = TailscaleCli.defaultCli().status().self();
                if (self != null && self.login() != null) login = self.login().trim();
            } catch (Exception | LinkageError ignored) { /* no tailscale: no owner, nobody is architect */ }
            cachedOwner = login;
            ownerCheckedAt = now;
            return login;
        }

        private static String prop(String systemKey, String envKey, String fallback) {
            String value = System.getProperty(systemKey);
            if (value == null || value.isBlank()) value = System.getenv(envKey);
            return value == null || value.isBlank() ? fallback : value.trim();
        }

        private SystemTags() {}
    }

    private TailnetRoles() {}
}