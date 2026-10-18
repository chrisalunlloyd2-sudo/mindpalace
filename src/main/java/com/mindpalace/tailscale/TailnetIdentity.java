package com.mindpalace.tailscale;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Who is on the other end of a tailnet connection — resolved from
 * {@code tailscale whois --json <ip>}, never from a request body.
 *
 * <p>Immutable value. {@code login} is the tailnet user login (may be empty for
 * unauthenticated peers); {@code tags} are the ACL tags the node carries (may
 * be empty when the tailnet grants none). At least one of the two is non-empty
 * or the peer has no identity at all — callers should treat a null or blank
 * identity as unauthorized (fail-closed).</p>
 *
 * <p>T1 foundation: T2 quorum voting attributes votes to a real machine via
 * this record, and T3 admin API / spectator pages authorize against it.</p>
 */
public record TailnetIdentity(String login, String deviceName, Set<String> tags) {

    public TailnetIdentity {
        login = login == null ? "" : login.trim();
        deviceName = deviceName == null ? "" : deviceName.trim();
        Set<String> cleaned = new LinkedHashSet<>();
        if (tags != null) {
            for (String tag : tags) {
                if (tag != null && !tag.isBlank()) cleaned.add(tag.trim());
            }
        }
        tags = Collections.unmodifiableSet(cleaned);
    }

    /** True when neither a login nor any tag could be resolved. */
    public boolean isEmpty() {
        return login.isEmpty() && tags.isEmpty();
    }

    /** Lowercased tailnet login, or "" when unknown. */
    public String loginLower() {
        return login.toLowerCase(Locale.ROOT);
    }

    /** True when the node carries exactly {@code tag} (case-insensitive). */
    public boolean hasTag(String tag) {
        if (tag == null || tag.isBlank()) return false;
        for (String owned : tags) {
            if (owned.equalsIgnoreCase(tag.trim())) return true;
        }
        return false;
    }

    /** Stable human-readable label for logs and HUD name tags. */
    public String displayName() {
        if (!login.isEmpty()) return login;
        if (!deviceName.isEmpty()) return deviceName;
        return "anonymous-tailnet-peer";
    }

    @Override
    public String toString() {
        return "TailnetIdentity[login=" + login + ", device=" + deviceName
            + ", tags=" + tags + "]";
    }
}