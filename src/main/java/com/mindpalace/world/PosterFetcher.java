package com.mindpalace.world;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * P1 (finding 1, .hermes/plans/2026-09-30_tailscale_mesh_and_depth_pass.md):
 * repo images reach the walls. Two image sources per repo:
 *
 *  1. LOCAL — RoomPopulator already picks an image file during the repo walk
 *     (the best-looking candidate, see its scoring); that is a path on disk
 *     and needs no fetch.
 *  2. REMOTE — for the ~90% of repos whose scan window holds no image, fetch
 *     the repo's GitHub social-preview PNG once and cache it on disk under
 *     {@code ~/.mindpalace/cache/posters/<repo>.png}. The social preview is
 *     served by GitHub's tokenless CDN (Camo), needs no auth and costs no
 *     API rate limit — one small GET per repo, run once per machine, then
 *     every boot reads the cache.
 *
 * The fetch is pure-strict here: one bounded HTTP GET, atomic temp-file
 * write, never touches the render thread, and callers decide when to run
 * (the game wires a background thread; the selftest runs it inline). Any
 * failure returns null and the room keeps the language-diagram poster —
 * the diagram fallback is never removed.
 */
public final class PosterFetcher {

    static final String CDN_BASE = "https://opengraph.githubassets.com/1/";
    /** One-shot fetch budget; the worker thread runs far from any frame. */
    private static final long MAX_BYTES = 2_000_000L;   // 2 MB sanity cap

    private final String owner;
    private final Path cacheDir;
    // Simple single-flight per JVM: repo -> result (SUCCESS / MISSING / ERROR),
    // so repeated queue drains and retries never re-download.
    private final java.util.concurrent.ConcurrentHashMap<String, String> attempted =
        new java.util.concurrent.ConcurrentHashMap<>();

    public enum Status { SUCCESS, MISSING, ERROR }

    public PosterFetcher(String owner, Path cacheDir) {
        this.owner = owner == null ? "" : owner.trim();
        this.cacheDir = cacheDir;
    }

    /** Default cache root: ~/.mindpalace/cache/posters (created on demand). */
    public static Path defaultCacheDir() {
        return Path.of(System.getProperty("user.home", "."), ".mindpalace", "cache", "posters");
    }

    /**
     * Cached poster path for a repo, fetching once if needed. Returns an
     * absolute image path on success (local disk), or null when the repo
     * has no cached image and none could be fetched. Cache hits never touch
     * the network.
     */
    public String posterFor(String repoName) {
        if (repoName == null || repoName.isBlank() || owner.isEmpty()) return null;
        String safe = safeFileName(repoName);
        Path dest = cacheDir.resolve(safe + ".png");
        if (Files.isRegularFile(dest) && cacheFileLooksLikeImage(dest)) {
            attempted.putIfAbsent(repoName, "SUCCESS");
            return dest.toAbsolutePath().toString();
        }
        if (attempted.containsKey(repoName)) return null;  // one shot per JVM run
        attempted.putIfAbsent(repoName, fetch(repoName, dest).name());
        if (Files.isRegularFile(dest)) return dest.toAbsolutePath().toString();
        return null;
    }

    /** Pure fetch+cache step — no repeat-guard, for tests and warm-ups. */
    public Status fetch(String repoName, Path dest) {
        String url = CDN_BASE + owner + "/" + repoName;
        Path tmp = null;
        try {
            java.net.http.HttpClient http = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(10))
                .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                .build();
            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(url))
                .timeout(java.time.Duration.ofSeconds(20))
                .header("Accept", "image/png,image/*")
                .header("User-Agent", "MindPalace-poster-fetch")
                .GET()
                .build();
            java.net.http.HttpResponse<byte[]> resp =
                http.send(req, java.net.http.HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() != 200) return Status.MISSING;
            byte[] body = resp.body();
            if (body == null || body.length < 64 || body.length > MAX_BYTES) return Status.MISSING;
            if (!looksLikePng(body)) return Status.MISSING;   // Camo error page, not an image
            Files.createDirectories(cacheDir);
            tmp = dest.resolveSibling(dest.getFileName() + ".part");
            Files.write(tmp, body);
            Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return Status.SUCCESS;
        } catch (java.net.http.HttpTimeoutException e) {
            deleteQuietly(tmp);
            return Status.ERROR;
        } catch (IOException | InterruptedException | IllegalArgumentException
                 | java.util.concurrent.CompletionException e) {
            deleteQuietly(tmp);
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return Status.ERROR;
        }
    }

    /** The CDN serves PNGs only; a text/JSON body means a Camo rejection page.
     *  Accepts the 8-byte signature alone (cache-hit probes) or a full body. */
    public static boolean looksLikePng(byte[] b) {
        return b != null && b.length >= 8
            && (b[0] & 0xFF) == 0x89 && (b[1] & 0xFF) == 0x50 && (b[2] & 0xFF) == 0x4E
            && (b[3] & 0xFF) == 0x47;
    }

    /** A cache hit must look decodable — stb will decode it later on the render thread. */
    public static boolean cacheFileLooksLikeImage(Path p) {
        try (var in = Files.newInputStream(p)) {
            byte[] head = in.readNBytes(8);
            return head.length == 8 && looksLikePng(head);
        } catch (IOException e) {
            return false;
        }
    }

    /** Repo name → filesystem-safe cache file component. */
    public static String safeFileName(String repoName) {
        String s = repoName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
        return s.length() > 100 ? s.substring(0, 100) : s;
    }

    private static void deleteQuietly(Path p) {
        if (p == null) return;
        try { Files.deleteIfExists(p); } catch (IOException ignored) {}
    }
}
