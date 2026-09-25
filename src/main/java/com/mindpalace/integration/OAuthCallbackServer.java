package com.mindpalace.integration;

import java.net.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Lightweight HTTP server for OAuth2 callback handling.
 * Runs on localhost:8899 (or configurable port) and handles the
 * Reddit OAuth flow: user clicks auth link → Reddit redirects here
 * → we exchange code for token → start polling Reddit.
 */
public class OAuthCallbackServer {
    private final int port;
    private final RedditOAuthClient reddit;
    private final Runnable onTokenReceived;
    private final String expectedState;
    private ServerSocket serverSocket;
    private volatile boolean running = false;
    private ExecutorService executor;

    public OAuthCallbackServer(int port, RedditOAuthClient reddit, Runnable onTokenReceived) {
        this(port, reddit, onTokenReceived, null);
    }

    public OAuthCallbackServer(int port, RedditOAuthClient reddit, Runnable onTokenReceived, String expectedState) {
        this.port = port;
        this.reddit = reddit;
        this.onTokenReceived = onTokenReceived;
        this.expectedState = expectedState;
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket(port);
        running = true;
        executor = Executors.newFixedThreadPool(2);
        executor.submit(this::acceptLoop);
        log("OAuth callback server listening on port " + port);
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket client = serverSocket.accept();
                executor.submit(() -> handleRequest(client));
            } catch (IOException e) {
                if (running) log("ERROR in accept loop: " + e.getMessage());
            }
        }
    }

    private void handleRequest(Socket client) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream()));
             PrintWriter writer = new PrintWriter(new OutputStreamWriter(client.getOutputStream()))) {

            String line = reader.readLine();
            if (line == null || !line.startsWith("GET")) {
                sendError(writer, "Invalid request");
                return;
            }

            // Parse query string from request line
            // GET /?code=XXX&state=YYY HTTP/1.1
            String[] parts = line.split(" ");
            String path = parts.length > 1 ? parts[1] : "";
            Map<String, String> params = parseQueryString(path);

            String code = params.get("code");
            String state = params.get("state");
            String error = params.get("error");

            if (error != null) {
                log("OAuth error from Reddit: " + error);
                sendError(writer, "OAuth error: " + error);
                return;
            }

            if (code == null || state == null) {
                log("Missing code or state in callback");
                sendError(writer, "Missing code or state");
                return;
            }

            // CSRF validation: callback state must equal the state issued with the auth URL, fail-closed, refs #93
            if (!state.equals(expectedState)) {
                log("OAuth state mismatch - possible CSRF, rejecting callback");
                sendError(writer, "Invalid state parameter");
                return;
            }

            // Exchange code for token
            try {
                reddit.exchangeCodeForToken(code);
                log("Successfully exchanged auth code for access token");
                sendSuccess(writer);
                if (onTokenReceived != null) {
                    onTokenReceived.run();
                }
            } catch (Exception e) {
                log("ERROR exchanging code for token: " + e.getMessage());
                sendError(writer, "Token exchange failed: " + e.getMessage());
            }

        } catch (IOException e) {
            log("ERROR handling request: " + e.getMessage());
        }
    }

    private Map<String, String> parseQueryString(String path) {
        Map<String, String> params = new HashMap<>();
        int qIndex = path.indexOf('?');
        if (qIndex < 0) return params;

        String queryString = path.substring(qIndex + 1);
        for (String param : queryString.split("&")) {
            String[] kv = param.split("=", 2);
            if (kv.length == 2) {
                try {
                    String key = URLDecoder.decode(kv[0], "UTF-8");
                    String value = URLDecoder.decode(kv[1], "UTF-8");
                    params.put(key, value);
                } catch (Exception e) {
                    // Skip malformed params
                }
            }
        }
        return params;
    }

    private void sendSuccess(PrintWriter writer) {
        writer.println("HTTP/1.1 200 OK");
        writer.println("Content-Type: text/html; charset=UTF-8");
        writer.println("Connection: close");
        writer.println();
        writer.println("<html><body>");
        writer.println("<h1>Success!</h1>");
        writer.println("<p>Authorization granted. MindPalace is now listening to Reddit suggestions.</p>");
        writer.println("<p>You can close this window.</p>");
        writer.println("</body></html>");
        writer.flush();
    }

    private void sendError(PrintWriter writer, String message) {
        writer.println("HTTP/1.1 400 Bad Request");
        writer.println("Content-Type: text/html; charset=UTF-8");
        writer.println("Connection: close");
        writer.println();
        writer.println("<html><body>");
        writer.println("<h1>Error</h1>");
        writer.println("<p>" + message + "</p>");
        writer.println("</body></html>");
        writer.flush();
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException e) {
            log("ERROR closing server: " + e.getMessage());
        }
        if (executor != null) executor.shutdownNow();
    }

    private void log(String msg) {
        System.out.println("[OAuthCallbackServer] " + msg);
    }
}
