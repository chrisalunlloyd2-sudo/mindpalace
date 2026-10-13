package com.mindpalace.world;

import java.io.File;
import java.util.List;
import java.util.ArrayList;

/**
 * Scans local repos and GitHub API to build the room list.
 * Maps each repo to a Room with metadata.
 */
public class RepoMapper {
    private static final String AIGEN_SYS = "C:/Users/viper/AIGEN_SYS/repos";
    private static final String VIPER_NOTES = "C:/Users/viper/OneDrive/ViperAI_Notes";

    public void scanRepos(List<Room> rooms) {
        // Scan local repos dir — override with MIND_PALACE_REPOS_DIR env var
        // or -Dmindpalace.repos=... (falls back to the historical AIGEN_SYS path)
        String reposDir = System.getenv("MIND_PALACE_REPOS_DIR");
        if (reposDir == null || reposDir.isEmpty()) reposDir = System.getProperty("mindpalace.repos", AIGEN_SYS);
        File aigenDir = new File(reposDir);
        if (aigenDir.exists() && aigenDir.isDirectory()) {
            File[] repos = aigenDir.listFiles(File::isDirectory);
            if (repos != null) {
                // Metadata lookups are independent per repo and mostly wait on git process startup, so
                // they run 3 at a time (measured startup: ~70 repos x 2 git calls). Rooms are still
                // added in directory order, so the world layout is unchanged.
                List<Room> found = new ArrayList<>();
                List<File> foundDirs = new ArrayList<>();
                for (File repoDir : repos) {
                    File gitDir = new File(repoDir, ".git");
                    if (gitDir.exists()) {
                        Room room = new Room(repoDir.getName());
                        room.setLocalPath(repoDir.getAbsolutePath());
                        found.add(room);
                        foundDirs.add(repoDir);
                    }
                }
                detectMetaParallel(found, foundDirs);
                rooms.addAll(found);
            }
        }

        // Add ViperAI_Notes as a special room (override with MIND_PALACE_NOTES_DIR)
        String notesPath = System.getenv("MIND_PALACE_NOTES_DIR");
        if (notesPath == null || notesPath.isEmpty()) notesPath = VIPER_NOTES;
        File notesDir = new File(notesPath);
        if (notesDir.exists()) {
            Room notesRoom = new Room("ViperAI_Notes");
            notesRoom.setLocalPath(notesPath);
            notesRoom.setLanguage("Markdown");
            notesRoom.setPrivate(true);
            notesRoom.setRepoDescription("Personal AI notes and knowledge base");
            rooms.add(notesRoom);
        }

        System.out.println("[RepoMapper] Found " + rooms.size() + " repos locally");
    }


    /**
     * Demo mode (step 112): build rooms from the bundled fixture manifest
     * instead of the local/GitHub scan. Fixtures are REAL directories under
     * data/demo_repos/<name>/ (committed) so populateRoom finds files and
     * the editor's full CRUD works offline. Zero auth, zero network.
     */
    public void scanDemoRepos(List<Room> rooms) {
        java.nio.file.Path manifest = java.nio.file.Path.of("data", "demo_repos.json");
        if (!java.nio.file.Files.isRegularFile(manifest)) {
            System.err.println("[RepoMapper] demo manifest missing — falling back to local scan");
            scanRepos(rooms);
            return;
        }
        try {
            String json = java.nio.file.Files.readString(manifest);
            // Minimal parse: names + metadata via regex (no JSON dep in this package)
            // \\042 = double-quote (octal); braces literal (no quantifier context)
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "\\{\\s*\\042name\\042:\\s*\\042([^\\042]+)\\042,\\s*\\042language\\042:\\s*\\042([^\\042]+)\\042,"
              + "\\s*\\042description\\042:\\s*\\042([^\\042]+)\\042,\\s*\\042private\\042:\\s*(true|false)"
              + ",\\s*\\042path\\042:\\s*\\042([^\\042]+)\\042\\s*\\}").matcher(json);
            while (m.find()) {
                Room room = new Room(m.group(1));
                room.setLocalPath(java.nio.file.Paths.get(m.group(5)).toAbsolutePath().toString());
                room.setLanguage(m.group(2));
                room.setRepoDescription(m.group(3));
                room.setPrivate(Boolean.parseBoolean(m.group(4)));
                // H37 (#122): fixtures have no .git — a deterministic seed
                // ledger keeps demo/selftest/e2e hermetic while still
                // exercising the engraving path. Newest (10) first, like git log.
                StringBuilder lb = new StringBuilder();
                for (int ci = 10; ci >= 1; ci--) {
                    String sha = String.format("%07x",
                        Math.abs((m.group(1) + ci).hashCode()) & 0xFFFFFFF);
                    if (lb.length() > 0) lb.append('\n');
                    lb.append(sha, 0, 7).append(" seed commit ").append(ci);
                }
                room.setCommitLedger(lb.toString());
                rooms.add(room);
            }
        } catch (Exception e) {
            System.err.println("[RepoMapper] demo manifest parse failed: " + e.getMessage());
            scanRepos(rooms);
            return;
        }
        System.out.println("[RepoMapper] Demo fixtures loaded: " + rooms.size() + " rooms (no auth, no network)");
    }

    /**
     * Extract the canonical repo name from a git remote URL.
     * Handles: https://github.com/user/repo.git, git@github.com:user/repo.git,
     * ssh://git@github.com/user/repo.git, and bare paths.
     */
    private String extractRepoName(String url) {
        if (url == null) return null;
        String u = url.trim();
        // Strip trailing .git
        if (u.endsWith(".git")) u = u.substring(0, u.length() - 4);
        // Strip trailing slash
        if (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        // Take the last path segment
        int slash = u.lastIndexOf('/');
        if (slash >= 0) u = u.substring(slash + 1);
        // Handle scp-like git@host:user/repo
        int colon = u.lastIndexOf(':');
        if (colon >= 0) u = u.substring(colon + 1);
        return u;
    }

    private static final int META_THREADS = 3;

    /** Run detectRepoMeta for every room, META_THREADS at a time; each room is touched by one thread only. */
    private void detectMetaParallel(List<Room> found, List<File> dirs) {
        java.util.concurrent.ExecutorService ex = java.util.concurrent.Executors.newFixedThreadPool(META_THREADS, r -> {
            Thread t = new Thread(r, "repo-meta");
            t.setDaemon(true);
            return t;
        });
        try {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < found.size(); i++) {
                final Room room = found.get(i);
                final File dir = dirs.get(i);
                futures.add(ex.submit(() -> detectRepoMeta(room, dir)));
            }
            for (java.util.concurrent.Future<?> f : futures) {
                try {
                    f.get();   // also publishes the room's fields to this thread
                } catch (Exception e) {
                    System.err.println("[RepoMapper] metadata lookup failed: " + e);
                }
            }
        } finally {
            ex.shutdown();
        }
    }

    private void detectRepoMeta(Room room, File repoDir) {
        // Startup cost (measured: ~85 s frozen window with 70 local repos): this used to spawn FOUR git
        // processes per repo on the main thread. Now: origin URL is read from .git/config (no process,
        // git is only the fallback), and last commit + ledger share ONE git log call.
        String url = readOriginUrl(repoDir);
        if (url == null) url = runGit(repoDir, "remote", "get-url", "origin");
        if (url != null && !url.isEmpty()) {
            room.setRemoteUrl(url);
            // Extract the REAL GitHub repo name from the remote URL — this is
            // the canonical name (fixes local-folder case/hyphen/underscore
            // variants like sims1337backend vs SIMS1337-BACKEND).
            String realName = extractRepoName(url);
            if (realName != null && !realName.isEmpty()) {
                room.setRepoName(realName);
            }
        }

        // One bounded git call yields BOTH the last commit ("<subject> (<age>)", same text as the old
        // `log -1 --format=%s (%ar)`) and the 10-line commit ledger for the door plaque (H37, #122).
        // Fields are separated by \u001f so a subject containing a tab or space cannot split wrongly.
        String log10 = runGit(repoDir, "log", "-10", "--format=%h%x1f%s%x1f%ar");
        if (log10 != null && !log10.isEmpty()) {
            StringBuilder ledger = new StringBuilder();
            String lastCommit = null;
            for (String line : log10.split("\n")) {
                String[] f = line.split("\u001f", 3);
                if (f.length < 3) continue;
                if (lastCommit == null) lastCommit = f[1] + " (" + f[2] + ")";
                if (ledger.length() > 0) ledger.append('\n');
                ledger.append(f[0]).append(' ').append(f[1]);
            }
            if (lastCommit != null && !lastCommit.isEmpty()) room.setLastCommit(lastCommit);
            if (ledger.length() > 0) room.setCommitLedger(capLedgerLines(ledger.toString()));
        }

        // Heatmap activity (M3 step 123): commits in the last 30 days. rev-list --count prints one
        // number instead of listing every commit hash.
        String cnt = runGit(repoDir, "rev-list", "--count", "--since=30.days", "HEAD");
        if (cnt != null && cnt.matches("\\d+")) {
            int n = Integer.parseInt(cnt);
            if (n > 0) room.setActivity30d(n);
        }

        // Detect primary language by file extensions (recursive, bounded depth + file
        // cap). A top-level-only scan mislabels most repos as "Markdown" — README.md
        // is often the only recognized file at the root, while the real source lives
        // in src/main/java, src/main/python, etc.
        int[] c = new int[6];  // py, java, js, html, md, totalScanned
        countExtensions(repoDir, 5, c);
        int py = c[0], java = c[1], js = c[2], html = c[3], md = c[4];
        if (java > py && java > js) room.setLanguage("Java");
        else if (py > js) room.setLanguage("Python");
        else if (js > 0) room.setLanguage("JavaScript");
        else if (html > 0) room.setLanguage("HTML");
        else if (md > 0) room.setLanguage("Markdown");
    }

    /** origin URL from .git/config without spawning git; null when not a plain repo dir or no origin. */
    static String readOriginUrl(File repoDir) {
        try {
            File cfg = new File(new File(repoDir, ".git"), "config");
            if (!cfg.isFile()) return null;   // worktree/submodule .git files: let git resolve it
            boolean inOrigin = false;
            for (String raw : java.nio.file.Files.readAllLines(cfg.toPath(), java.nio.charset.StandardCharsets.UTF_8)) {
                String line = raw.trim();
                if (line.startsWith("[")) {
                    inOrigin = line.replace(" ", "").equalsIgnoreCase("[remote\"origin\"]");
                } else if (inOrigin) {
                    int eq = line.indexOf('=');
                    if (eq > 0 && line.substring(0, eq).trim().equalsIgnoreCase("url")) {
                        String u = line.substring(eq + 1).trim();
                        return u.isEmpty() ? null : u;
                    }
                }
            }
        } catch (Exception ignored) { /* fall back to git */ }
        return null;
    }

    /** H37 (#122): cap each ledger line to a 7-char sha + 26 subject chars —
     *  the plaque is ~3.4m wide at engraving scale and long subjects would
     *  overrun the wall segment between doors. */
    private String capLedgerLines(String ledger) {
        StringBuilder out = new StringBuilder();
        for (String line : ledger.split("\n")) {
            if (line.isEmpty()) continue;
            int sp = line.indexOf(' ');
            String sha = sp > 0 ? line.substring(0, sp) : line;
            if (sha.length() > 7) sha = sha.substring(0, 7);
            String subject = sp > 0 && line.length() > sp + 1 ? line.substring(sp + 1) : "";
            if (subject.length() > 26) subject = subject.substring(0, 24) + "..";
            if (out.length() > 0) out.append('\n');
            out.append(sha).append(' ').append(subject);
        }
        return out.toString();
    }

    /** Public bounded-timeout git runner — shared with TimeMachine (M3). */
    public interface GitRunner { String run(File repoDir, String... args); }

    /** Run a git command with a bounded timeout; returns trimmed stdout, or null on
     *  failure/timeout — never hangs the room scan on a stalled git or a credential
     *  prompt (the classic readAllBytes-without-waitFor deadlock). */
    private String runGit(File repoDir, String... args) {
        return SHARED_GIT.run(repoDir, args);
    }

    /** The shared runner instance (TimeMachine uses this via its ctor). */
    public static final GitRunner SHARED_GIT = new GitRunner() {
        @Override
        public String run(File repoDir, String... args) {
        try {
            ProcessBuilder pb = new ProcessBuilder();
            List<String> cmd = new ArrayList<>();
            cmd.add("git");
            cmd.add("-C");
            cmd.add(repoDir.getAbsolutePath());
            for (String a : args) cmd.add(a);
            pb.command(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            if (!p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;   // timed out
            }
            if (p.exitValue() != 0) return null;
            return new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            return null;
        }
        }
    };

    /** Recursively count file extensions (bounded depth + file cap), skipping VCS/build dirs. */
    private void countExtensions(File dir, int depth, int[] c) {
        if (depth < 0 || c[5] > 500) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                String n = f.getName();
                if (n.equals(".git") || n.equals("node_modules") || n.equals("target") || n.equals("__pycache__")) continue;
                countExtensions(f, depth - 1, c);
                continue;
            }
            c[5]++;
            String name = f.getName().toLowerCase();
            if (name.endsWith(".py")) c[0]++;
            else if (name.endsWith(".java")) c[1]++;
            else if (name.endsWith(".js")) c[2]++;
            else if (name.endsWith(".html")) c[3]++;
            else if (name.endsWith(".md")) c[4]++;
        }
    }
}
