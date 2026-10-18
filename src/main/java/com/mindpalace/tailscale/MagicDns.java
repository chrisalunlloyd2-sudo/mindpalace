package com.mindpalace.tailscale;

import java.util.Locale;

/**
 * MagicDNS naming rules for this tailnet. Two jobs:
 *
 * <ul>
 *   <li><b>Validate</b> hostnames/FQDNs peers and configs may refer to
 *       (use 19: stable room/world addresses, deep links).</li>
 *   <li><b>Resolve</b> a bare host or full {@code host.tail<word>.ts.net} name
 *       to a node in a {@link StatusSnapshot} — the in-game source of truth for
 *       "which machine is that room on".</li>
 * </ul>
 *
 * <p>Every check is local string work; nothing queries DNS. Room-shareable
 * deep links are built by callers as {@code http://<host>.<suffix>/...} —
 * this class only supplies name pieces.</p>
 */
public final class MagicDns {

    /** RFC 952/1123 label: starts/ends alnum, middle may hyphen, 1-63 chars. */
    public static boolean isValidLabel(String label) {
        if (label == null || label.isEmpty() || label.length() > 63) return false;
        for (int i = 0; i < label.length(); i++) {
            char c = label.charAt(i);
            boolean alnum = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9');
            if (!alnum && c != '-') return false;
            if (c == '-' && (i == 0 || i == label.length() - 1)) return false;
        }
        return true;
    }

    /**
     * Valid untagged MagicDNS hostname — one or more valid labels joined by
     * dots, no trailing dot, 253 chars max. Accepts both bare hosts
     * ({@code muse}) and FQDNs ({@code muse.tail703209.ts.net}) so callers can
     * pass whatever a peer sent.
     */
    public static boolean isValidHostname(String host) {
        if (host == null || host.isBlank() || host.length() > 253) return false;
        String trimmed = host.trim();
        if (trimmed.endsWith(".")) return false;
        String[] labels = trimmed.split("\\.");
        for (String label : labels) {
            if (!isValidLabel(label)) return false;
        }
        return true;
    }

    /** Suffix-only form check: {@code tail703209.ts.net} or {@code ts.net}. */
    public static boolean isValidSuffix(String suffix) {
        return isValidHostname(suffix) && suffix != null && suffix.contains(".");
    }

    /**
     * Bare host part of a MagicDNS name: {@code muse.tail703209.ts.net} to
     * {@code muse}; {@code muse} stays {@code muse}. Trailing dot tolerated.
     */
    public static String hostPart(String name) {
        if (name == null || name.isBlank()) return "";
        String trimmed = name.trim();
        if (trimmed.endsWith(".")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        int dot = trimmed.indexOf('.');
        String host = dot < 0 ? trimmed : trimmed.substring(0, dot);
        return host.toLowerCase(Locale.ROOT);
    }

    /**
     * Full FQDN for a host on this tailnet: {@code muse} + suffix gives
     * {@code muse.tail703209.ts.net}. Pass-through when already qualified.
     */
    public static String fqdn(String host, String suffix) {
        if (host == null || host.isBlank()) return "";
        String trimmed = host.trim();
        if (trimmed.endsWith(".")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.indexOf('.') >= 0) return lower;
        if (suffix == null || suffix.isBlank()) return lower;
        return lower + "." + suffix.trim().toLowerCase(Locale.ROOT);
    }
}