package com.mindpalace.github;

import com.mindpalace.world.Room;

import java.io.IOException;
import java.util.List;

/**
 * Scans GitHub repos and merges with local repos.
 * Handles the PAT-based auto-population on first run.
 */
public class RepoScanner {
    private GitHubClient client;

    public RepoScanner(GitHubClient client) {
        this.client = client;
    }

    /**
     * Fetch all repos from GitHub and merge with local rooms.
     * GitHub repos that don't exist locally get added as remote-only rooms.
     */
    public void mergeRemoteRepos(List<Room> localRooms) throws IOException {
        if (!client.isAuthenticated()) {
            System.out.println("[RepoScanner] Not authenticated — skipping remote fetch");
            return;
        }

        List<Room> remoteRooms = client.fetchAllRepos();
        System.out.println("[RepoScanner] Remote: " + remoteRooms.size() + " repos");

        for (Room remote : remoteRooms) {
            boolean found = false;
            for (Room local : localRooms) {
                if (local.getRepoName().equalsIgnoreCase(remote.getRepoName())) {
                    // Merge remote metadata into local
                    if (local.getRemoteUrl() == null) local.setRemoteUrl(remote.getRemoteUrl());
                    if (local.getLanguage() == null) local.setLanguage(remote.getLanguage());
                    if (local.getRepoDescription() == null) local.setRepoDescription(remote.getRepoDescription());
                    local.setPrivate(remote.isPrivate());
                    local.setStarCount(remote.getStarCount());
                    found = true;
                    break;
                }
            }
            if (!found) {
                // Remote-only repo — add it, fogged until explored
                remote.setLocalPath(null); // no local copy
                remote.setFogged(true);
                localRooms.add(remote);
                System.out.println("[RepoScanner] Added remote-only (fogged): " + remote.getRepoName());
            } else {
                // Private repos that exist locally are also fogged until explored
                if (remote.isPrivate()) {
                    for (Room local : localRooms) {
                        if (local.getRepoName().equalsIgnoreCase(remote.getRepoName())) {
                            local.setFogged(true);
                            break;
                        }
                    }
                }
            }
        }
    }
}
