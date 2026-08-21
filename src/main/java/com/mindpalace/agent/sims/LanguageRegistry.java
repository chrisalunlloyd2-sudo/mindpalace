package com.mindpalace.agent.sims;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LanguageRegistry — the ~20 programming languages the code editor can toggle.
 * Each language maps to a file extension and a LoRA AdapterType, so switching
 * language in the editor also switches the active LoRA weight set (SIMS1337
 * parity: language → LoRA adapter → KG node).
 */
public final class LanguageRegistry {
    private LanguageRegistry() {}

    /** name → (extension, AdapterType). Insertion order = toggle order. */
    public static final Map<String, Lang> LANGS = new LinkedHashMap<>();
    static {
        add("Java",       ".java",  AdapterType.CODE);
        add("Python",     ".py",    AdapterType.CODE);
        add("JavaScript", ".js",    AdapterType.CODE);
        add("TypeScript", ".ts",    AdapterType.CODE);
        add("C",          ".c",     AdapterType.CODE);
        add("C++",        ".cpp",   AdapterType.CODE);
        add("C#",         ".cs",    AdapterType.CODE);
        add("Go",         ".go",    AdapterType.CODE);
        add("Rust",       ".rs",    AdapterType.CODE);
        add("Kotlin",     ".kt",    AdapterType.CODE);
        add("Ruby",       ".rb",    AdapterType.CODE);
        add("PHP",        ".php",   AdapterType.CODE);
        add("Swift",      ".swift", AdapterType.CODE);
        add("SQL",        ".sql",   AdapterType.ANALYSIS);
        add("Shell",      ".sh",    AdapterType.CODE);
        add("HTML",       ".html",  AdapterType.CHAT);
        add("CSS",        ".css",   AdapterType.CHAT);
        add("JSON",       ".json",  AdapterType.ANALYSIS);
        add("YAML",       ".yaml",  AdapterType.ANALYSIS);
        add("Markdown",   ".md",    AdapterType.CHAT);
    }

    private static void add(String name, String ext, AdapterType adapter) {
        LANGS.put(name, new Lang(name, ext, adapter));
    }

    /** A single language entry. */
    public static final class Lang {
        public final String name;
        public final String extension;
        public final AdapterType adapter;
        Lang(String name, String ext, AdapterType adapter) {
            this.name = name; this.extension = ext; this.adapter = adapter;
        }
    }

    /** Look up a language by name (case-insensitive). */
    public static Lang byName(String name) {
        for (Lang l : LANGS.values()) if (l.name.equalsIgnoreCase(name)) return l;
        return null;
    }

    /** Look up a language by file extension (with or without the dot). */
    public static Lang byExtension(String ext) {
        if (ext == null) return null;
        String e = ext.startsWith(".") ? ext : "." + ext;
        for (Lang l : LANGS.values()) if (l.extension.equalsIgnoreCase(e)) return l;
        return null;
    }

    /** The language after `name` in toggle order (wraps around). */
    public static String next(String name) {
        String[] names = LANGS.keySet().toArray(new String[0]);
        for (int i = 0; i < names.length; i++)
            if (names[i].equalsIgnoreCase(name)) return names[(i + 1) % names.length];
        return names[0];
    }

    public static int count() { return LANGS.size(); }
}
