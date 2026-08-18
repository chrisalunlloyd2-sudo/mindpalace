package com.mindpalace.deploy;

import com.mindpalace.world.Book;
import com.mindpalace.world.Room;
import com.mindpalace.world.WorldBuilder;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PatchManager — live game patches. Watches patches/patch.json. When a NEW
 * patch id appears (never applied), the engine plays the "GAME PATCH LOADING"
 * cinematic, then apply() ships new rooms/books/texts into the LIVE world.
 * Applied ids are recorded in patches/applied.json (ADD-only, never deleted).
 *
 * Ship a patch: drop a new patch.json into the patches dir — within one poll
 * (8s) the game shows the loading effect and applies it. Nothing restarts.
 */
public class PatchManager {
    public static class Patch {
        public String id, title, message;
        public final List<String> texts = new ArrayList<>();
        public final List<Map<String, String>> rooms = new ArrayList<>();
        public final List<Map<String, String>> books = new ArrayList<>();
        public boolean valid() { return id != null && !id.isBlank(); }
    }

    private final Path patchesDir;
    private final Set<String> applied = new HashSet<>();
    private volatile Patch pending;
    private final List<String> patchTexts = new ArrayList<>();

    public PatchManager(Path dir) {
        this.patchesDir = dir;
        loadApplied();
    }

    public static Path defaultDir() {
        String env = System.getenv("MIND_PALACE_PATCHES_DIR");
        return (env != null && !env.isBlank()) ? Paths.get(env) : Paths.get("patches");
    }

    private void loadApplied() {
        try {
            Path f = patchesDir.resolve("applied.json");
            if (Files.exists(f)) {
                String raw = Files.readString(f, StandardCharsets.UTF_8);
                for (String id : raw.replaceAll("[\\[\\]\"]", "").split(",")) {
                    if (!id.isBlank()) applied.add(id.trim());
                }
            }
        } catch (Exception e) {
            System.err.println("[Patch] applied.json read failed: " + e.getMessage());
        }
    }

    /** Poll the manifest — engine calls this every ~8 seconds. */
    public void poll() {
        try {
            Path f = patchesDir.resolve("patch.json");
            if (!Files.exists(f)) return;
            String raw = Files.readString(f, StandardCharsets.UTF_8);
            Patch p = parse(raw);
            if (p != null && p.valid() && !applied.contains(p.id) && pending == null) {
                pending = p;
                System.out.println("[Patch] NEW PATCH: " + p.id + " — " + p.title);
            }
        } catch (Exception e) {
            System.err.println("[Patch] poll error: " + e.getMessage());
        }
    }

    public boolean hasPending() { return pending != null; }
    public Patch peekPending() { return pending; }
    public Patch takePending() { Patch p = pending; pending = null; return p; }

    /** Apply a patch into the live world. ADD-only: never deletes anything. */
    public void apply(Patch p, WorldBuilder world) {
        applied.add(p.id);
        appendApplied(p.id);
        for (Map<String, String> spec : p.rooms) {
            String name = spec.getOrDefault("name", "Patch Room");
            Room room = new Room(name);
            room.setRepoName(name);
            room.setRepoDescription(spec.getOrDefault("desc", p.title != null ? p.title : ""));
            room.setLanguage(spec.getOrDefault("lang", "patch"));
            room.setFogged(false);
            for (Map<String, String> bspec : p.books) {
                Book b = new Book(bspec.getOrDefault("title", "Patch Notes"),
                    "patch/" + bspec.getOrDefault("title", "patch-notes").replace(' ', '-').toLowerCase());
                b.setContent(bspec.getOrDefault("content", p.message != null ? p.message : ""));
                b.setLanguage(spec.getOrDefault("lang", "patch"));
                room.addBook(b);
            }
            world.addRoom(room);
            System.out.println("[Patch] room added: " + name);
        }
        patchTexts.clear();
        patchTexts.addAll(p.texts);
        if (p.message != null && !p.message.isBlank()) patchTexts.add(p.message);
        System.out.println("[Patch] applied: " + p.id + " (" + p.rooms.size() + " rooms, "
            + p.books.size() + " books, " + patchTexts.size() + " texts)");
    }

    public List<String> getPatchTexts() { return patchTexts; }

    private void appendApplied(String id) {
        try {
            Files.createDirectories(patchesDir);
            Path f = patchesDir.resolve("applied.json");
            String existing = Files.exists(f)
                ? Files.readString(f, StandardCharsets.UTF_8).trim() : "";
            String entry = existing.isEmpty()
                ? "[\"" + id + "\"]"
                : existing.substring(0, existing.length() - 1) + ",\"" + id + "\"]";
            Files.writeString(f, entry, StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[Patch] applied append failed: " + e.getMessage());
        }
    }

    // --- tiny schema reader (no deps) ---
    private Patch parse(String raw) {
        Patch p = new Patch();
        p.id = grab(raw, "\"id\"\\s*:\\s*\"([^\"]+)\"");
        p.title = grab(raw, "\"title\"\\s*:\\s*\"([^\"]+)\"");
        p.message = grab(raw, "\"message\"\\s*:\\s*\"([^\"]+)\"");
        Matcher m = Pattern.compile("\"texts\"\\s*:\\s*\\[([^\\]]*)\\]").matcher(raw);
        if (m.find()) {
            Matcher tm = Pattern.compile("\"([^\"]+)\"").matcher(m.group(1));
            while (tm.find()) p.texts.add(tm.group(1));
        }
        Matcher rm = Pattern.compile("\\{\\s*\"name\"\\s*:\\s*\"([^\"]+)\""
            + "(?:[^}]*?\"desc\"\\s*:\\s*\"([^\"]+)\")?(?:[^}]*?\"lang\"\\s*:\\s*\"([^\"]+)\")?[^}]*\\}")
            .matcher(raw);
        while (rm.find()) {
            Map<String, String> r = new HashMap<>();
            r.put("name", rm.group(1));
            r.put("desc", rm.group(2) != null ? rm.group(2) : "");
            r.put("lang", rm.group(3) != null ? rm.group(3) : "patch");
            p.rooms.add(r);
        }
        Matcher bm = Pattern.compile("\\{\\s*\"title\"\\s*:\\s*\"([^\"]+)\""
            + "\\s*,\\s*\"content\"\\s*:\\s*\"([^\"]*)\"\\s*\\}").matcher(raw);
        while (bm.find()) {
            Map<String, String> b = new HashMap<>();
            b.put("title", bm.group(1));
            b.put("content", bm.group(2).replace("\\n", "\n"));
            p.books.add(b);
        }
        return p;
    }

    private String grab(String raw, String pat) {
        Matcher m = Pattern.compile(pat).matcher(raw);
        return m.find() ? m.group(1) : null;
    }
}
