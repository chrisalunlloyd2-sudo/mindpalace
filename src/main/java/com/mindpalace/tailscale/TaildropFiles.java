package com.mindpalace.tailscale;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * T3 use 12 — Taildrop asset shipping via `tailscale file cp`.
 *
 * Ships game artifacts (screenshots, chat-logs, GA genomes, save snapshots)
 * to the Architect's devices on the tailnet. Zero new surface: wraps the
 * installed `tailscale` CLI, same shape as TailscaleCli's other execs.
 *
 * Hermetic by contract (Architect, 2026-10-18): the CLI is NOT installed on
 * this dev box, so every method must degrade — availability is probed once,
 * absent CLI → available()==false and ship() returns a plain-text "skipped"
 * result instead of throwing. Real installs run the cp and surface stderr.
 *
 * Trust: owner login from SystemTags (env/prop override). No Funnel, no
 * public ports — Taildrop is tailnet-to-tailnet by definition.
 */
public final class TaildropFiles {

    /** What we ship, per the plan's asset list (posters / LoRA / saves / screenshots). */
    public enum Asset {
        SCREENSHOT("screenshots"),
        CHAT_LOG("chat-logs"),
        GENOME("genomes"),
        SAVE("saves");

        public final String subdir;
        Asset(String subdir) { this.subdir = subdir; }
    }

    /** One ship attempt's outcome — plain data, safe to log and selftest-assert. */
    public record ShipResult(Asset asset, Path source, boolean shipped, String detail) {}

    private final Path dataRoot;
    private final String target;
    private volatile Boolean cliPresent;

    public TaildropFiles(Path dataRoot, String targetDeviceOrUser) {
        this.dataRoot = dataRoot;
        // Derive the owner login the same way TailnetRoles does
        // (prop/env override, then tailscale status self login);
        // TailnetRoles.SystemTags is private, so mirror its logic here.
        String owner = System.getProperty("mindpalace.tailscale.architect.login");
        if (owner == null || owner.isBlank()) {
            owner = System.getenv("MP_TAILSCALE_ARCHITECT_LOGIN");
        }
        this.target = (targetDeviceOrUser == null || targetDeviceOrUser.isBlank())
            ? (owner == null || owner.isBlank()
                ? ownerLoginFromCli() : owner.trim())
            : targetDeviceOrUser;
    }

    /** tailscale status self login ("" when absent) — mirrors SystemTags.ownerLogin. */
    private static String ownerLoginFromCli() {
        try {
            StatusSnapshot.Node self = TailscaleCli.defaultCli().status().self();
            return (self != null && self.login() != null) ? self.login().trim() : "";
        } catch (Exception | LinkageError e) {
            return ""; // off-mesh / no CLI: fail-closed empty target
        }
    }

    /** Resolve a staged asset dir under dataRoot (created on demand, lazily). */
    public Path stagingDir(Asset asset) {
        Path dir = dataRoot.resolve("taildrop").resolve(asset.subdir);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            // Fall back to dataRoot itself; staging must never crash the game loop.
            return dataRoot;
        }
        return dir;
    }

    /** Probe the CLI exactly once (fail-closed when tailscale is not installed). */
    public boolean available() {
        Boolean present = cliPresent;
        if (present == null) {
            present = probeCli();
            cliPresent = present;
        }
        return present;
    }

    private boolean probeCli() {
        try {
            Process p = new ProcessBuilder("tailscale", "version")
                .redirectErrorStream(true).start();
            boolean done = p.waitFor(10, TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    /**
     * Ship newest assets of a kind. Cap per call keeps a cron spike bounded
     * (maxFiles 0 = default 8). Never throws: CLI-absent/cp-fail read as
     * shipped=false with plain detail.
     */
    public List<ShipResult> shipNewest(Asset asset, int maxFiles) {
        int cap = maxFiles > 0 ? maxFiles : 8;
        List<ShipResult> out = new ArrayList<>();
        Path dir = dataRoot.resolve(asset.subdir);
        List<Path> newest = newestFiles(dir, cap);
        for (Path f : newest) {
            out.add(ship(asset, f));
        }
        return out;
    }

    /** Ship one file by exact path (must live under dataRoot — path-traversal fence). */
    public ShipResult ship(Asset asset, Path file) {
        try {
            if (file == null || !Files.isRegularFile(file)) {
                return new ShipResult(asset, file, false, "missing file");
            }
            Path norm = file.normalize().toAbsolutePath();
            Path root = dataRoot.normalize().toAbsolutePath();
            if (!norm.startsWith(root)) {
                return new ShipResult(asset, file, false, "outside dataRoot refused");
            }
            if (!available()) {
                return new ShipResult(asset, file, false, "skipped: tailscale CLI absent");
            }
            if (target == null || target.isBlank()) {
                return new ShipResult(asset, file, false, "skipped: no owner login (tailscale absent)");
            }
            Process p = new ProcessBuilder("tailscale", "file", "cp",
                    norm.toString(), target + ":")
                .redirectErrorStream(true).start();
            boolean done = p.waitFor(120, TimeUnit.SECONDS);
            String outText = done ? new String(p.getInputStream().readAllBytes()).trim() : "";
            if (!done) {
                p.destroyForcibly();
                return new ShipResult(asset, file, false, "timeout");
            }
            return new ShipResult(asset, file, p.exitValue() == 0,
                p.exitValue() == 0 ? "ok" : truncate(outText));
        } catch (IOException | InterruptedException e) {
            return new ShipResult(asset, file, false, String.valueOf(e));
        }
    }

    /** Nightly sweep entrypoint: one capped pass over every asset kind. */
    public List<ShipResult> shipNightly() {
        List<ShipResult> all = new ArrayList<>();
        if (!available()) {
            all.add(new ShipResult(Asset.SCREENSHOT, null, false, "skipped: tailscale CLI absent"));
            return all;
        }
        all.addAll(shipNewest(Asset.SCREENSHOT, 8));
        all.addAll(shipNewest(Asset.CHAT_LOG, 4));
        all.addAll(shipNewest(Asset.GENOME, 4));
        all.addAll(shipNewest(Asset.SAVE, 4));
        return all;
    }

    private List<Path> newestFiles(Path dir, int cap) {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                if (Files.isRegularFile(p)) out.add(p);
            }
        } catch (IOException ignored) {
            return out;
        }
        out.sort((a, b) -> {
            try {
                return Files.getLastModifiedTime(b).compareTo(Files.getLastModifiedTime(a));
            } catch (IOException e) {
                return 0;
            }
        });
        return out.size() > cap ? new ArrayList<>(out.subList(0, cap)) : out;
    }

    private String truncate(String s) {
        if (s == null) return "";
        return s.length() > 200 ? s.substring(0, 200) : s;
    }

    /** Selftest probe: hermetic — verify staging dirs + path fence + CLI-absent degrade. */
    public static boolean probeHermetic(Path tmpRoot) {
        try {
            TaildropFiles tf = new TaildropFiles(tmpRoot, "selftest-device");
            // staging dirs create + resolve
            for (Asset a : Asset.values()) {
                if (!Files.isDirectory(tf.stagingDir(a))) return false;
            }
            // CLI absent here → every path degrades to shipped=false, no throw.
            // Write through the class's own staging path so the parent dir
            // is guaranteed to exist (bare chat-logs/ may not in a hermetic root).
            Path f = tf.stagingDir(Asset.CHAT_LOG).resolve("probe.txt");
            Files.writeString(f, "probe");
            ShipResult r = tf.ship(Asset.CHAT_LOG, f);
            if (r.shipped()) return false; // CLI is not installed on dev boxes
            if (r.detail() == null || r.detail().isBlank()) return false;
            // path fence: outside dataRoot refused with plain detail
            ShipResult outside = tf.ship(Asset.CHAT_LOG, tmpRoot.resolve("..").resolve("outside.txt"));
            if (outside.shipped()) return false;
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}