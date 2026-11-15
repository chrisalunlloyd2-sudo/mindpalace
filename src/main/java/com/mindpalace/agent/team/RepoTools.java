package com.mindpalace.agent.team;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * RepoTools — the toolset a TOOLS seat gets: look, never touch. Every tool is deterministic, read-only, confined to one
 * root folder and capped in size, so a model cannot read outside the repo, read a secret, or flood the board.
 *
 *   ? ls dir            the names in a folder (folders end with /)
 *   ? read file         the first lines of a text file
 *   ? grep word file    the lines that contain the word, with line numbers
 *
 * A tool never throws: anything it will not do comes back as one "ERR ..." line the model can read.
 */
public final class RepoTools {

    static final int MAX_NAMES = 60, MAX_LINES = 60, MAX_HITS = 20, MAX_LINE_CHARS = 200;
    static final long MAX_FILE_BYTES = 1_000_000;

    private RepoTools() {}

    public static Map<String, Function<String, String>> toolset(Path root) {
        Path r = root.toAbsolutePath().normalize();
        Map<String, Function<String, String>> ts = new LinkedHashMap<>();
        ts.put("ls", arg -> ls(r, arg));
        ts.put("read", arg -> read(r, arg));
        ts.put("grep", arg -> grep(r, arg));
        return ts;
    }

    /** The path inside root that arg names, or null when it leaves root, follows a link out of it, or names a secret. */
    static Path safe(Path root, String arg) {
        String a = arg == null ? "" : arg.trim().replace('\\', '/');
        if (a.startsWith("/") || a.contains(":") || a.contains("\0")) return null;
        try {
            Path p = root.resolve(a).normalize();
            if (!p.startsWith(root)) return null;
            for (Path seg : root.relativize(p)) if (secret(seg.toString())) return null;
            if (Files.exists(p) && !p.toRealPath().startsWith(root.toRealPath())) return null;
            return p;
        } catch (IOException | RuntimeException e) {   // a name the OS refuses (InvalidPathException) is just "not allowed"
            return null;
        }
    }

    private static boolean secret(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.equals(".git") || n.startsWith(".env") || n.contains("secret") || n.contains("credential") || n.contains("token")
            || n.contains("password") || n.endsWith(".key") || n.endsWith(".pem") || n.endsWith(".pfx") || n.startsWith("id_rsa");
    }

    private static String ls(Path root, String arg) {
        Path p = safe(root, arg);
        if (p == null) return "ERR not allowed";
        if (!Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) return "ERR not a folder";
        List<String> names = new ArrayList<>();
        try (Stream<Path> s = Files.list(p)) {
            s.forEach(c -> {
                String n = c.getFileName().toString();
                if (!secret(n)) names.add(Files.isDirectory(c, LinkOption.NOFOLLOW_LINKS) ? n + "/" : n);
            });
        } catch (IOException e) {
            return "ERR cannot list";
        }
        names.sort(null);   // the OS order is not stable; the tool must be
        int more = names.size() - MAX_NAMES;
        return String.join(" ", more > 0 ? names.subList(0, MAX_NAMES) : names) + (more > 0 ? " (+" + more + " more)" : "");
    }

    private static String read(Path root, String arg) {
        Path p = safe(root, arg);
        if (p == null) return "ERR not allowed";
        List<String> lines = lines(p, MAX_LINES);
        return lines == null ? "ERR not a readable text file" : String.join("\n", lines);
    }

    private static String grep(Path root, String arg) {
        String[] a = (arg == null ? "" : arg.trim()).split("\\s+", 2);
        if (a.length < 2 || a[0].isEmpty()) return "ERR use: grep word file";
        Path p = safe(root, a[1]);
        if (p == null) return "ERR not allowed";
        List<String> lines = lines(p, Integer.MAX_VALUE);
        if (lines == null) return "ERR not a readable text file";
        String word = a[0].toLowerCase(Locale.ROOT);
        List<String> hits = new ArrayList<>();
        for (int i = 0; i < lines.size() && hits.size() < MAX_HITS; i++)
            if (lines.get(i).toLowerCase(Locale.ROOT).contains(word)) hits.add((i + 1) + ": " + lines.get(i).trim());
        return hits.isEmpty() ? "no match" : String.join("\n", hits);
    }

    /** Up to max lines of a regular text file under the size cap, each line cut to MAX_LINE_CHARS; null when it is not one. */
    private static List<String> lines(Path p, int max) {
        try {
            if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS) || Files.size(p) > MAX_FILE_BYTES) return null;
            List<String> out = new ArrayList<>();
            try (BufferedReader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
                String line;
                while (out.size() < max && (line = r.readLine()) != null) {
                    if (line.indexOf('\0') >= 0) return null;
                    out.add(line.length() > MAX_LINE_CHARS ? line.substring(0, MAX_LINE_CHARS) : line);
                }
            }
            return out;
        } catch (IOException | RuntimeException e) {   // a binary file fails UTF-8 decoding: not a text file
            return null;
        }
    }
}
