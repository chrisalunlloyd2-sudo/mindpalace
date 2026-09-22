package com.mindpalace.integration;

import java.net.http.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import com.google.gson.*;

/**
 * Reddit OAuth2 integration — fetches community suggestions and feeds
 * them into the quorum voting system. Uses server-side auth flow with
 * stored refresh token for automated polling.
 */
public class RedditOAuthClient {
    private static final String REDDIT_AUTH_URL = "https://www.reddit.com/api/v1/access_token";
    private static final String REDDIT_API_URL = "https://oauth.reddit.com";
    private static final String USER_AGENT = "MindPalace/1.0 (by chrisalunlloyd2)";

    private final String clientId;
    private final String clientSecret;
    private final String redirectUri;
    private final String subreddit;
    private String accessToken;
    private long tokenExpiresAt = 0;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    public RedditOAuthClient(String clientId, String clientSecret, String redirectUri, String subreddit) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.redirectUri = redirectUri;
        this.subreddit = subreddit;
    }

    /**
     * Get the authorization URL the user should visit to grant permissions.
     * State is a CSRF token; client should verify it matches in callback.
     */
    public String getAuthorizationUrl(String state) {
        return String.format(
            "https://www.reddit.com/api/v1/authorize?client_id=%s&response_type=code&state=%s&redirect_uri=%s&duration=permanent&scope=read",
            clientId, state, redirectUri
        );
    }

    /**
     * Exchange authorization code for access/refresh tokens. Call this
     * in the OAuth callback handler.
     */
    public void exchangeCodeForToken(String code) throws Exception {
        String body = String.format(
            "grant_type=authorization_code&code=%s&redirect_uri=%s",
            code, redirectUri
        );

        var request = HttpRequest.newBuilder()
            .uri(URI.create(REDDIT_AUTH_URL))
            .header("User-Agent", USER_AGENT)
            .header("Authorization", basicAuth(clientId, clientSecret))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();

        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        parseTokenResponse(response.body());
    }

    /**
     * Refresh the access token using the stored refresh token.
     * Call this periodically to keep the token fresh.
     */
    public void refreshAccessToken(String refreshToken) throws Exception {
        String body = String.format(
            "grant_type=refresh_token&refresh_token=%s",
            refreshToken
        );

        var request = HttpRequest.newBuilder()
            .uri(URI.create(REDDIT_AUTH_URL))
            .header("User-Agent", USER_AGENT)
            .header("Authorization", basicAuth(clientId, clientSecret))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();

        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        parseTokenResponse(response.body());
    }

    /**
     * Fetch the latest suggestions from the subreddit (top posts from last 24h).
     * Returns list of RedditSuggestion objects ready for quorum voting.
     */
    public List<RedditSuggestion> fetchSuggestions(int limit) throws Exception {
        if (accessToken == null || System.currentTimeMillis() > tokenExpiresAt) {
            throw new IllegalStateException("Access token not initialized or expired. Call refreshAccessToken first.");
        }

        String url = String.format(
            "%s/r/%s/top?t=day&limit=%d",
            REDDIT_API_URL, subreddit, limit
        );

        var request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("User-Agent", USER_AGENT)
            .header("Authorization", "Bearer " + accessToken)
            .GET()
            .build();

        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return parseRedditFeed(response.body());
    }

    private void parseTokenResponse(String json) throws Exception {
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        accessToken = obj.get("access_token").getAsString();
        long expiresIn = obj.get("expires_in").getAsLong();
        tokenExpiresAt = System.currentTimeMillis() + (expiresIn * 1000);
    }

    private List<RedditSuggestion> parseRedditFeed(String json) throws Exception {
        List<RedditSuggestion> suggestions = new ArrayList<>();
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonArray children = root.getAsJsonObject("data").getAsJsonArray("children");

        for (JsonElement child : children) {
            JsonObject post = child.getAsJsonObject().getAsJsonObject("data");
            String id = post.get("id").getAsString();
            String title = post.get("title").getAsString();
            String author = post.get("author").getAsString();
            int score = post.get("score").getAsInt();
            int numComments = post.get("num_comments").getAsInt();
            String url = post.get("url").getAsString();

            suggestions.add(new RedditSuggestion(id, title, author, score, numComments, url));
        }
        return suggestions;
    }

    private String basicAuth(String user, String pass) {
        String combined = user + ":" + pass;
        byte[] encoded = Base64.getEncoder().encode(combined.getBytes(StandardCharsets.UTF_8));
        return "Basic " + new String(encoded, StandardCharsets.UTF_8);
    }

    public static class RedditSuggestion {
        public final String id;
        public final String title;
        public final String author;
        public final int score;
        public final int numComments;
        public final String url;

        RedditSuggestion(String id, String title, String author, int score, int numComments, String url) {
            this.id = id;
            this.title = title;
            this.author = author;
            this.score = score;
            this.numComments = numComments;
            this.url = url;
        }

        @Override
        public String toString() {
            return String.format("RedditSuggestion[%s by %s | %d upvotes, %d comments]: %s",
                id, author, score, numComments, title);
        }
    }
}
