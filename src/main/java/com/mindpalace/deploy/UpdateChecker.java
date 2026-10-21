package com.mindpalace.deploy;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.util.concurrent.TimeUnit;

/**
 * #139 (H50, step 109): update check against the GitHub Releases API.
 *
 * Reads the game's own version from the jar manifest
 * (Implementation-Version, stamped by the maven-jar-plugin) and asks
 * GitHub for the latest published release tag. Pure informational —
 * result rendered as a HUD/console line; NO auto-download, NO restart,
 * no phoning home beyond one public GET.
 *
 * Failure-tolerant by contract (H0): any network/Auth error -> "unknown",
 * never a crash, never blocks boot (engine runs it on a daemon thread).
 */
public final class UpdateChecker {

    private static final String API_BASE = "https://api.github.com";
    private static final String REPO = "chrisalunlloyd2-sudo/mindpalace";

    /** Semver-ish compare: split on non-digits, compare numerically left→right. */
    public static int compareVersions(String a, String b) {
        if (a == null || b == null) return 0;
        String[] as = a.replaceAll("[^0-9.]", "").split("\\.");
        String[] bs = b.replaceAll("[^0-9.]", "").split("\\.");
        int n = Math.max(as.length, bs.length);
        for (int i = 0; i < n; i++) {
            int ai = i < as.length && !as[i].isEmpty() ? Integer.parseInt(as[i]) : 0;
            int bi = i < bs.length && !bs[i].isEmpty() ? Integer.parseInt(bs[i]) : 0;
            if (ai != bi) return Integer.compare(ai, bi);
        }
        return 0;
    }

    /** Current version from the manifest; empty + warning when not jar-loaded (IDE run). */
    public static String currentVersion() {
        String v = UpdateChecker.class.getPackage() != null
                ? UpdateChecker.class.getPackage().getImplementationVersion() : null;
        return v != null ? v : "";
    }

    /**
     * Fetch the latest release tag from the Releases API. Tokenless — the
     * repo is public; a PAT would only raise rate limits, which is fine at
     * one call per boot.
     */
    public String fetchLatestReleaseTag() throws Exception {
        OkHttpClient http = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build();
        Request req = new Request.Builder()
                .url(API_BASE + "/repos/" + REPO + "/releases/latest")
                .header("Accept", "application/vnd.github.v3+json")
                .build();
        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful()) throw new IllegalStateException("releases/latest HTTP " + resp.code());
            String body = resp.body() != null ? resp.body().string() : "";
            JsonObject o = JsonParser.parseString(body).getAsJsonObject();
            return o.get("tag_name").isJsonNull() ? null : o.get("tag_name").getAsString();
        }
    }

    /** One-shot check: "update available to vX" | "up to date" | "update: unknown (reason)". */
    public String check() {
        String current = currentVersion();
        if (current.isEmpty()) return "update: unknown (dev run, no manifest version)";
        try {
            String latest = fetchLatestReleaseTag();   // e.g. "v1.2.0-beta1"
            if (latest == null) return "update: unknown (no releases published)";
            String latestNum = latest.startsWith("v") ? latest.substring(1) : latest;
            int cmp = compareVersions(current, latestNum);
            if (cmp < 0) return "update available: " + latest + " (running " + current + ")";
            return "up to date (" + current + ")";
        } catch (Exception e) {
            return "update: unknown (" + e.getMessage() + ")";
        }
    }

    /**
     * #139 (H50, step 109): first-run wizard marker — true until the welcome
     * banner has been shown once. Marker file lives next to the theme store
     * ({user.home}/.mindpalace/first_run_done); any I/O problem reads as
     * "not first run" so a locked-down home dir never blocks or nags.
     */
    public static boolean isFirstRun() {
        try {
            return !java.nio.file.Files.exists(markerPath());
        } catch (Exception e) {
            return false;
        }
    }

    /** Persist the first-run marker (best-effort; failure only re-shows banner). */
    public static void markFirstRunDone() {
        try {
            java.nio.file.Files.createDirectories(markerPath().getParent());
            java.nio.file.Files.writeString(markerPath(),
                java.time.Instant.now().toString() + "\n");
        } catch (Exception ignored) { /* best-effort */ }
    }

    private static java.nio.file.Path markerPath() {
        return java.nio.file.Path.of(System.getProperty("user.home"),
            ".mindpalace", "first_run_done");
    }
}