package com.mindpalace.tailscale;

import com.mindpalace.backup.MemoryManager;
import com.mindpalace.backup.Telemetry;

import java.nio.file.Path;

/**
 * T3 integration (2026-09-30 plan, Section 4 wiring): binds all mesh + T3
 * services onto ONE live {@link TailnetTransport}. T2's mesh transport ran
 * only inside the hermetic selftest; this class makes the world actually
 * reachable from the Architect's phone/laptop while the game runs unattended
 * (kanban t_2c1fa168: live + unattended servers, Architect override).
 *
 * <p>Routes mounted here:</p>
 * <ul>
 *   <li>T2 mesh: /v1/mesh/ping, /v1/mesh/status, /v1/mesh/chat,
 *       /v1/quorum/vote (signed ballots {@code ->} BallotSink)</li>
 *   <li>T3 admin: /v1/admin/state (BUILD), /v1/admin/clock|weather|go (ADMIN)</li>
 *   <li>T3 spectator: /v1/spectator, /v1/spectator/data (READ_PUBLIC)</li>
 * </ul>
 *
 * <p>Gating contract (same doctrine as ContextBroadcaster): constructed and
 * started by {@code GameEngine} only when {@code !demoMode && !selfTest};
 * the selftest builds its own loopback transport directly and never touches
 * this class. When the node is off-mesh, {@code onTailnet} fails fast, the
 * catch closes everything and the world keeps running local — never a
 * public-interface fallback.</p>
 *
 * <p>Owner-login trust (Architect override, 2026-10-18): the scope is the
 * game-wide default whose me-to-me owner login (TailnetRoles.SystemTags)
 * makes every device on the Architect's tailnet login an architect role, so
 * the ADMIN/BUILD routes authorize without any ACL tag or admin step.</p>
 */
public final class TailnetServices {

    private TailnetTransport transport;
    private MeshServer mesh;
    private AdminApi admin;
    private SpectatorPage spectator;

    /**
     * Wire + start the live service set. Never throws: a failure is printed,
     * everything is closed and the game keeps running local-only.
     *
     * @param ballots   T2 ballot sink (normally agentManager::sinkMeshVote)
     * @param control   T3 admin mutators (GameEngine's queue-based implementor)
     * @param source    T3 spectator snapshot provider
     * @param telemetry audit trail for accepted admin mutations + mesh tamper
     * @param dataRoot  persistent data root (mesh keys + collected logs under it)
     * @param memory    Tree of Knowledge sink for shipped log lines (may be null)
     */
    public synchronized void start(MeshServer.BallotSink ballots,
                                   AdminApi.WorldControl control,
                                   SpectatorPage.DataSource source,
                                   Telemetry telemetry,
                                   Path dataRoot,
                                   MemoryManager memory) {
        try {
            WhoIsResolver resolver = new WhoIsResolver.Cli(TailscaleCli.defaultCli());
            TailnetRoles.Scope scope = TailnetRoles.Scope.defaultScope();
            TailnetTransport.Router router = new TailnetTransport.Router();

            MeshCrypto crypto = MeshCrypto.open(
                dataRoot.resolve("mesh_keys"), meshDeviceName(),
                reason -> {
                    if (telemetry != null) {
                        try {
                            telemetry.record(Telemetry.SYSTEM, "mesh_pin_tamper", reason);
                        } catch (Exception ignored) { /* breadcrumb only */ }
                    }
                });
            this.mesh = MeshServer.mount(router, crypto, ballots);

            // T3 log collector: its ingest lands behind /v1/log/ship below.
            LogCollector collector = new LogCollector(dataRoot, memory, telemetry);
            router.getPrivate("/v1/log/ship", (ex, peer, caps) -> {
                if (!"POST".equals(ex.getRequestMethod())) {
                    ex.sendResponseHeaders(405, -1);
                    return;
                }
                String body = new String(ex.getRequestBody().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
                int lines = 0;
                for (String line : body.split("\n")) {
                    if (collector.ingest(peer.displayName(), line)) lines++;
                }
                byte[] ack = ("{\"ok\":true,\"ingested\":" + lines + "}")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                ex.getResponseHeaders().set("Content-Type", "application/json");
                ex.sendResponseHeaders(200, ack.length);
                try (java.io.OutputStream out = ex.getResponseBody()) {
                    out.write(ack);
                }
            });

            this.admin = AdminApi.mount(router, control, telemetry);
            this.spectator = SpectatorPage.mount(router, source);

            this.transport = TailnetTransport.onTailnet(resolver, scope, router);
            transport.start();
            System.out.println("[Tailscale] mesh + admin + spectator + log services live on :"
                + transport.port());
        } catch (Exception | LinkageError e) {
            System.out.println("[Tailscale] live services not started: " + e.getMessage());
            close();
        }
    }

    /** Close everything (idempotent). GameEngine.cleanup() calls it. */
    public synchronized void close() {
        if (transport != null) {
            try {
                transport.close();
            } catch (Exception ignored) { /* closing must never break cleanup */ }
        }
        transport = null;
        mesh = null;
        admin = null;
        spectator = null;
    }

    /** True while the live transport is up. */
    public synchronized boolean isRunning() {
        return transport != null;
    }

    /** Live port (only valid while {@link #isRunning()}), else -1. */
    public synchronized int port() {
        return transport != null ? transport.port() : -1;
    }

    /** This device's stable mesh label: tailnet hostname when on-mesh, else "mindpalace". */
    private static String meshDeviceName() {
        try {
            StatusSnapshot.Node self = TailscaleCli.defaultCli().status().self();
            if (self != null && !self.hostName().isBlank()) return self.hostName();
        } catch (Exception | LinkageError ignored) { /* off-mesh: plain label */ }
        return "mindpalace";
    }

    /** Startable handle; construct plainly, then call {@link #start(...)}. */
    public TailnetServices() {
    }
}