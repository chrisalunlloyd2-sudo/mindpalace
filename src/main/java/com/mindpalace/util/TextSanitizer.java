package com.mindpalace.util;

/**
 * Text sanitizer for the bitmap font — the atlas only covers ASCII 32..126.
 * Strips CR (GitHub CRLF line endings) and maps box-drawing/Unicode to ASCII
 * so repo text renders as readable English instead of garbage glyphs.
 */
public final class TextSanitizer {
    private TextSanitizer() {}

    /** Normalize CRLF/CR to LF. */
    public static String stripCR(String s) {
        if (s == null) return null;
        return s.replace("\r\n", "\n").replace('\r', '\n');
    }

    /** Map box-drawing/Unicode to ASCII equivalents; keeps \n and \t. */
    public static String asciiSafe(String s) {
        if (s == null) return null;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n' || c == '\t') { sb.append(c); continue; }
            if (c >= 32 && c <= 126) { sb.append(c); continue; }
            sb.append(boxToAscii(c));
        }
        return sb.toString();
    }

    private static char boxToAscii(char c) {
        switch (c) {
            case '\u2554': case '\u2557': case '\u255A': case '\u255D':
            case '\u2560': case '\u2563': case '\u2566': case '\u2569':
            case '\u256C': case '\u250C': case '\u2510': case '\u2514':
            case '\u2518': case '\u251C': case '\u2524': case '\u252C':
            case '\u2534': return '+';
            case '\u2550': case '\u2500': case '\u2501': case '\u254C': return '-';
            case '\u2551': case '\u2502': case '\u2503': return '|';
            case '\u2022': case '\u00B7': case '\u25CF': case '\u25E6': return '*';
            case '\u2192': case '\u279C': case '\u00BB': return '>';
            case '\u2190': case '\u00AB': return '<';
            case '\u2026': return '.';
            default: return '?';
        }
    }
}
