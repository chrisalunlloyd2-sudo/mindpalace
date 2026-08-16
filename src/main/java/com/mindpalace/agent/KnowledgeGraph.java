package com.mindpalace.agent;

import com.mindpalace.world.Room;
import java.util.*;

/**
 * Knowledge Graph — the agent's mental map of the world. Nodes are rooms
 * (repos); edges are adjacency (same floor, neighboring doors). Determines
 * navigation routes, room adjacency, and district clustering.
 */
public class KnowledgeGraph {
    private final Map<String, Room> nodes = new HashMap<>();
    private final Map<String, Set<String>> edges = new HashMap<>();
    private final Map<String, String> district = new HashMap<>(); // room -> district label

    /** Build the KG from the current world layout. */
    public void build(List<Room> rooms) {
        nodes.clear();
        edges.clear();
        district.clear();

        for (Room r : rooms) {
            nodes.put(r.getRepoName(), r);
            edges.put(r.getRepoName(), new HashSet<>());
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
                district.put(a.getRepoName(), a.getHallwaySide() == 0 ? "West" : "East");
                if (i + 1 < floorRooms.size()) {
                    Room b = floorRooms.get(i + 1);
                    edges.get(a.getRepoName()).add(b.getRepoName());
                    edges.get(b.getRepoName()).add(a.getRepoName());
                }
            }
        }
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
