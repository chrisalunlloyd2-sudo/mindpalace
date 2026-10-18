package com.mindpalace.tailscale;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Transport abstraction over the tailnet (plan T1): every private game service
 * binds its HTTP surface here instead of hand-rolling a server, so the
 * security invariants live in exactly one place:
 *
 * <ul>
 *   <li><b>Bind, never Funnel.</b> Servers bind to the node's 100.x address
 *       via {@link #onTailnet(java.net.InetAddress)}; the loopback form is for
 *       hermetic selftests only. Nothing here calls {@code tailscale funnel}
 *       or opens a WAN-listening port.</li>
 *   <li><b>Identity from the mesh, not the request.</b> Every exchange is
 *       resolved through a {@link WhoIsResolver} and authorized through
 *       {@link TailnetRoles} before any route logic runs; failures are 403
 *       without detail.</li>
 *   <li><b>Role gates per route.</b> Routes declare the minimum capability
 *       they need; spectators can read public routes but never reach VOTE/
 *       BUILD/ADMIN routes.</li>
 * </ul>
 *
 * <p>T2 (quorum vote transport) and T3 (admin API + spectator HUD) mount
 * their routes here; T1 owns the plumbing.</p>
 */
public final class TailnetTransport {

    /** Route handle recorded by {@link Router#mount}. */
    private record Route(String path, TailnetRoles.Capability required) {}

    /** Minimal handler contract for routes. */
    @FunctionalInterface
    public interface Handler {
        void handle(HttpExchange exchange, TailnetIdentity peer,
                    Set<TailnetRoles.Capability> capabilities) throws IOException;
    }

    /** Router declared before {@code start()}; immutable afterwards. */
    public static final class Router {
        private final Map<String, Route> routes = new LinkedHashMap<>();
        private final Map<String, Handler> handlers = new LinkedHashMap<>();

        /** Public read route (spectator-visible). */
        public Router get(String path, Handler handler) {
            return mount(path, TailnetRoles.Capability.READ_PUBLIC, handler);
        }

        /** Route requiring {@code READ_PRIVATE} or above (voter/builder/architect). */
        public Router getPrivate(String path, Handler handler) {
            return mount(path, TailnetRoles.Capability.READ_PRIVATE, handler);
        }

        /** Route requiring {@code VOTE} (T2 quorum transport). */
        public Router vote(String path, Handler handler) {
            return mount(path, TailnetRoles.Capability.VOTE, handler);
        }

        /** Route requiring {@code BUILD} (build farm / world mutation). */
        public Router build(String path, Handler handler) {
            return mount(path, TailnetRoles.Capability.BUILD, handler);
        }

        /** Route requiring {@code ADMIN} (T3 world control). */
        public Router admin(String path, Handler handler) {
            return mount(path, TailnetRoles.Capability.ADMIN, handler);
        }

        private Router mount(String path, TailnetRoles.Capability required, Handler handler) {
            Objects.requireNonNull(path, "path");
            if (!path.startsWith("/") || !path.equals(path.trim()) || path.length() > 256) {
                throw new IllegalArgumentException("route path must start with '/': " + path);
            }
            Objects.requireNonNull(handler, "handler");
            if (routes.containsKey(path)) {
                throw new IllegalStateException("route already mounted: " + path);
            }
            routes.put(path, new Route(path, required));
            handlers.put(path, handler);
            return this;
        }

        public Set<String> paths() {
            return Collections.unmodifiableSet(routes.keySet());
        }
    }

    private final InetAddress bindAddress;
    private final int requestedPort;
    private final WhoIsResolver identityResolver;
    private final TailnetRoles.Scope scope;
    private final Router router;
    private HttpServer server;
    private ExecutorService executor;
    private boolean started;

    private TailnetTransport(InetAddress bindAddress, int requestedPort,
                             WhoIsResolver identityResolver, TailnetRoles.Scope scope,
                             Router router) {
        this.bindAddress = bindAddress;
        this.requestedPort = requestedPort;
        this.identityResolver = identityResolver;
        this.scope = scope;
        this.router = router;
    }

    /**
     * Bind to this node's tailnet address. Fails fast when the node is
     * off-mesh — private services must not silently fall back to a public
     * interface.
     */
    public static TailnetTransport onTailnet(WhoIsResolver resolver,
                                             TailnetRoles.Scope scope,
                                             Router router) throws IOException {
        TailscaleCli cli = TailscaleCli.defaultCli();
        return new TailnetTransport(
            InetAddress.getByName(cli.ip()), 0, resolver, scope, router);
    }

    /**
     * Loopback transport for tests and hermetic selftests: same filter chain,
     * no tailnet interface required. Callers inject a stub resolver so no
     * subprocess or mesh connection is needed.
     */
    public static TailnetTransport forLoopbackTest(int port, WhoIsResolver resolver,
                                                   TailnetRoles.Scope scope,
                                                   Router router) {
        try {
            return new TailnetTransport(
                InetAddress.getByName("127.0.0.1"), port, resolver, scope, router);
        } catch (IOException e) {
            throw new IllegalArgumentException("bad loopback address", e);
        }
    }

    public synchronized void start() throws IOException {
        if (started) return;
        server = HttpServer.create(new InetSocketAddress(bindAddress, requestedPort), 32);
        ThreadFactory threads = new ThreadFactory() {
            private final AtomicInteger n = new AtomicInteger();
            @Override
            public Thread newThread(Runnable task) {
                Thread thread = new Thread(task,
                    "mindpalace-tailnet-http-" + n.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }
        };
        executor = Executors.newFixedThreadPool(4, threads);
        server.setExecutor(executor);
        server.createContext("/", this::dispatch);
        server.start();
        started = true;
    }

    /** Bound port (resolution of port 0 happens at create time). */
    public synchronized int port() {
        return server == null ? requestedPort : server.getAddress().getPort();
    }

    public synchronized void close() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        started = false;
    }

    private void dispatch(HttpExchange exchange) {
        String path = exchange.getRequestURI().getPath();
        try {
            Route route = router.routes.get(path);
            if (route == null) {
                respond(exchange, 404, "not found");
                return;
            }
            if (!"GET".equals(exchange.getRequestMethod())
                && !"POST".equals(exchange.getRequestMethod())) {
                respond(exchange, 405, "method not allowed");
                return;
            }

            TailnetIdentity peer;
            try {
                peer = identityResolver.resolve(exchange.getRemoteAddress().getAddress());
            } catch (Exception denied) {
                respond(exchange, 403, "forbidden");
                return;
            }
            Set<TailnetRoles.Capability> caps = TailnetRoles.capabilities(peer, scope);
            if (!caps.contains(route.required())) {
                respond(exchange, 403, "forbidden");
                return;
            }
            router.handlers.get(path).handle(exchange, peer, caps);
        } catch (Exception e) {
            try {
                respond(exchange, 500, "transport error");
            } catch (IOException ignored) {
                // response already committed by the handler; nothing to do
            }
        } finally {
            exchange.close();
        }
    }

    private static void respond(HttpExchange exchange, int status, String message)
        throws IOException {
        byte[] body = (message + "\n").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}