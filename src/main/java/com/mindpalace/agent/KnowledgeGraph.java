package com.mindpalace.agent;

import com.mindpalace.world.Room;
import java.util.*;

/**
 * Knowledge Graph — the agent's mental map of the world. Nodes are rooms
 * (repos); edges are adjacency (same floor, neighboring doors). Determines
 * navigation routes, room adjacency, and district clustering.
 */
public class KnowledgeGraph {
    // Snapshot-published fields (fix 2026-10-13, verifier run t_6dd98959):
    // build() runs on the LiveUpdate scheduler thread while the render thread
    // calls nodeCount()/edgeCount() EVERY FRAME (telemetry panel). The old
    // clear()-in-place maps made the game loop throw
    // ConcurrentModificationException (KnowledgeGraph.edgeCount ->
    // GameEngine.renderTelemetryPanel) and killed the e2e tour 2 shots in.
    // Now build() constructs fresh maps privately and publishes each complete
    // map through a volatile write: readers on other threads either see the
    // whole old graph or the whole new one — never a half-cleared map.
    private volatile Map<String, Room> nodes = new HashMap<>();
    private volatile Map<String, Set<String>> edges = new HashMap<>();
    private volatile Map<String, String> district = new HashMap<>(); // room -> district label

    // H36 (#125): generic per-repo attribute store, intentionally OUTSIDE the
    // build() swap — the graph gets rebuilt on every LiveUpdate room add, and
    // attributes written by the repo scan (e.g. health) must survive that.
    private final Map<String, Map<String, String>> attributes = new java.util.concurrent.ConcurrentHashMap<>();

    /** Set a per-repo attribute (keyed by repoName). Survives build() rebuilds. */
    public void setAttribute(String repoName, String key, String value) {
        if (repoName == null || key == null || value == null) return;
        attributes.computeIfAbsent(repoName, k -> new HashMap<>()).put(key, value);
    }

    /** Get a per-repo attribute; null when unset or value not parseable as an int. */
    public String getAttribute(String repoName, String key) { return getAttribute(repoName, key, null); }

    public String getAttribute(String repoName, String key, String def) {
        Map<String, String> m = attributes.get(repoName);
        return m == null ? def : m.getOrDefault(key, def);
    }

    /** Integer convenience (H36 health reads). */
    public int getIntAttribute(String repoName, String key, int def) {
        String v = getAttribute(repoName, key);
        if (v == null) return def;
        try { return Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return def; }
    }

    /** Build the KG from the current world layout. */
    public void build(List<Room> rooms) {
        Map<String, Room> newNodes = new HashMap<>();
        Map<String, Set<String>> newEdges = new HashMap<>();
        Map<String, String> newDistrict = new HashMap<>();

        for (Room r : rooms) {
            newNodes.put(r.getRepoName(), r);
            newEdges.put(r.getRepoName(), new HashSet<>());
        }

        // Edges: rooms on the same floor, adjacent by door Z-order
        Map<Integer, List<Room>> byFloor = new HashMap<>();
        for (Room r : rooms) byFloor.computeIfAbsent(r.getFloor(), k -> new ArrayList<>()).add(r);

        for (List<Room> floorRooms : byFloor.values()) {
            floorRooms.sort(Comparator.comparingDouble(r ->
                r.getDoorPosition() == null ? 0 : r.getDoorPosition().z));
            for (int i = 0; i < floorRooms.size(); i++) {
                Room a = floorRooms.get(i);
                // District: cluster by hallway side (left/right wing)
                newDistrict.put(a.getRepoName(), a.getHallwaySide() == 0 ? "West" : "East");
                if (i + 1 < floorRooms.size()) {
                    Room b = floorRooms.get(i + 1);
                    newEdges.get(a.getRepoName()).add(b.getRepoName());
                    newEdges.get(b.getRepoName()).add(a.getRepoName());
                }
            }
        }

        // Atomic publish: three complete maps swapped in. Readers never
        // observe a partially-built graph (see class comment above).
        nodes = newNodes;
        edges = newEdges;
        district = newDistrict;
    }

    /** Neighbors of a room (adjacent rooms the agent can walk to). */
    public List<Room> neighbors(Room room) {
        List<Room> out = new ArrayList<>();
        for (String name : edges.getOrDefault(room.getRepoName(), Set.of())) {
            Room n = nodes.get(name);
            if (n != null) out.add(n);
        }
        return out;
    }

    /** District label for a room. */
    public String districtOf(Room room) {
        return district.getOrDefault(room.getRepoName(), "Unknown");
    }

    /** A random room in the same district (for district clustering). */
    public Room randomRoomInDistrict(Room room, Random rand) {
        String d = districtOf(room);
        List<Room> same = new ArrayList<>();
        for (Map.Entry<String, String> e : district.entrySet())
            if (e.getValue().equals(d) && nodes.containsKey(e.getKey()))
                same.add(nodes.get(e.getKey()));
        if (same.isEmpty()) return room;
        return same.get(rand.nextInt(same.size()));
    }

    public int nodeCount() { return nodes.size(); }
    public int edgeCount() { return edges.values().stream().mapToInt(Set::size).sum() / 2; }
}
