package com.mindpalace.github;

import com.google.gson.*;
import com.mindpalace.world.Book;
import com.mindpalace.world.Room;
import okhttp3.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * GitHub REST API client.
 * Uses PAT from Windows Credential Manager or settings.
 */
public class GitHubClient {
    private static final String API_BASE = "https://api.github.com";
    private OkHttpClient http;
    private String token;
    private String username = "chrisalunlloyd2-sudo";
    private boolean authenticated = false;

    public GitHubClient() {
        http = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();
    }

    /**
     * Try to get token from Windows Credential Manager.
     */
    public boolean loadTokenFromCredentialManager() {
        try {
            ProcessBuilder pb = new ProcessBuilder("cmdkey", "/list");
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes());
            if (output.contains("git:https://github.com") || output.contains("github.com")) {
                // Token exists in credential manager — we'll use git to fetch it
                ProcessBuilder pb2 = new ProcessBuilder(
                    "bash", "-c",
                    "echo 'protocol=https\nhost=github.com\n' | git credential-manager get 2>/dev/null | grep password | cut -d= -f2"
                );
                Process p2 = pb2.start();
                String tok = new String(p2.getInputStream().readAllBytes()).trim();
                if (!tok.isEmpty() && tok.length() >= 40) {
                    this.token = tok;
                    this.authenticated = true;
                    System.out.println("[GitHub] Authenticated via Windows Credential Manager");
                    return true;
                }
            }
        } catch (Exception e) {
            System.err.println("[GitHub] Failed to load token: " + e.getMessage());
        }
        return false;
    }

    public void setToken(String token) {
        this.token = token;
        this.authenticated = (token != null && token.length() >= 40);
    }

    public boolean isAuthenticated() { return authenticated; }

    /**
     * Fetch all repos for the authenticated user.
     */
    public List<Room> fetchAllRepos() throws IOException {
        List<Room> rooms = new ArrayList<>();
        int page = 1;

        while (true) {
            Request req = new Request.Builder()
                .url(API_BASE + "/user/repos?per_page=100&page=" + page + "&sort=updated")
                .header("Authorization", "token " + token)
                .header("Accept", "application/vnd.github.v3+json")
                .build();

            try (Response resp = http.newCall(req).execute()) {
                if (!resp.isSuccessful()) {
                    System.err.println("[GitHub] API error: " + resp.code());
                    break;
                }

                String body = resp.body().string();
                JsonArray arr = JsonParser.parseString(body).getAsJsonArray();
                if (arr.size() == 0) break;

                for (JsonElement el : arr) {
                    JsonObject obj = el.getAsJsonObject();
                    Room room = new Room(obj.get("name").getAsString());
                    room.setPrivate(obj.get("private").getAsBoolean());
                    room.setRemoteUrl(obj.get("clone_url").getAsString());

                    if (obj.has("description") && !obj.get("description").isJsonNull()) {
                        room.setRepoDescription(obj.get("description").getAsString());
                    }
                    if (obj.has("language") && !obj.get("language").isJsonNull()) {
                        room.setLanguage(obj.get("language").getAsString());
                    }
                    if (obj.has("stargazers_count")) {
                        room.setStarCount(obj.get("stargazers_count").getAsInt());
                    }

                    rooms.add(room);
                }
                page++;
            }
        }

        System.out.println("[GitHub] Fetched " + rooms.size() + " repos from GitHub");
        return rooms;
    }

    /**
     * Fetch file contents for a repo (top-level only for speed).
     */
    public List<Book> fetchRepoContents(String repoName) throws IOException {
        List<Book> books = new ArrayList<>();

        Request req = new Request.Builder()
            .url(API_BASE + "/repos/" + username + "/" + repoName + "/contents/")
            .header("Authorization", "token " + token)
            .header("Accept", "application/vnd.github.v3+json")
            .build();

        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful()) return books;

            String body = resp.body().string();
            JsonArray arr = JsonParser.parseString(body).getAsJsonArray();

            for (JsonElement el : arr) {
                JsonObject obj = el.getAsJsonObject();
                String type = obj.get("type").getAsString();
                String name = obj.get("name").getAsString();
                String path = obj.get("path").getAsString();

                if (type.equals("file")) {
                    Book book = new Book(name, path);
                    book.setLanguage(Book.detectLanguage(name));
                    if (obj.has("size")) book.setSizeBytes(obj.get("size").getAsLong());
                    if (obj.has("sha")) book.setSha(obj.get("sha").getAsString());
                    books.add(book);
                }
            }
        }

        return books;
    }

    /**
     * Fetch a single file's content.
     */
    public String fetchFileContent(String repoName, String filePath) throws IOException {
        Request req = new Request.Builder()
            .url(API_BASE + "/repos/" + username + "/" + repoName + "/contents/" + filePath)
            .header("Authorization", "token " + token)
            .header("Accept", "application/vnd.github.v3.raw")
            .build();

        try (Response resp = http.newCall(req).execute()) {
            if (resp.isSuccessful()) {
                return resp.body().string();
            }
        }
        return null;
    }

    /**
     * Create or update a file in a repo.
     */
    public boolean upsertFile(String repoName, String filePath, String content, String commitMessage, String sha) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("message", commitMessage);
        body.addProperty("content", java.util.Base64.getEncoder().encodeToString(content.getBytes()));
        if (sha != null) body.addProperty("sha", sha);

        RequestBody reqBody = RequestBody.create(body.toString(), MediaType.parse("application/json"));
        Request req = new Request.Builder()
            .url(API_BASE + "/repos/" + username + "/" + repoName + "/contents/" + filePath)
            .header("Authorization", "token " + token)
            .put(reqBody)
            .build();

        try (Response resp = http.newCall(req).execute()) {
            return resp.isSuccessful();
        }
    }

    /**
     * Delete a file from a repo.
     */
    public boolean deleteFile(String repoName, String filePath, String sha, String commitMessage) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("message", commitMessage);
        body.addProperty("sha", sha);

        RequestBody reqBody = RequestBody.create(body.toString(), MediaType.parse("application/json"));
        Request req = new Request.Builder()
            .url(API_BASE + "/repos/" + username + "/" + repoName + "/contents/" + filePath)
            .header("Authorization", "token " + token)
            .method("DELETE", reqBody)
            .build();

        try (Response resp = http.newCall(req).execute()) {
            return resp.isSuccessful();
        }
    }
}
