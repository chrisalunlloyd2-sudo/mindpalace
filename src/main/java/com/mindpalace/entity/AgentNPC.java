package com.mindpalace.entity;

import com.mindpalace.agent.KVTree;
import com.mindpalace.agent.KnowledgeGraph;
import com.mindpalace.world.Room;
import com.mindpalace.world.Book;
import com.mindpalace.world.TodoCrystal;
import org.joml.Vector3f;

import java.util.*;

/**
 * An agent NPC — a coding agent with a body and behaviors.
 *
 * Explorer (tool agent): walks into rooms it wants to refactor, reads books
 *   (files), places new books (generated code), carries TODO crystals.
 * Critic (critic agent): patrols, marks rooms with risk, adds warning glyphs.
 *
 * Behavior is driven by its KVTree (what it notices/does) and KnowledgeGraph
 * (where it walks). Deterministic per role via seeded RNG.
 */
public class AgentNPC {
    public enum Role { EXPLORER, CRITIC }
    public enum State { IDLE, WALKING, READING, PLACING, CARRYING, MARKING, GOSSIPING }

    private final String name;
    private final Role role;
    private final KVTree kv;
    private final KnowledgeGraph kg;
    private final Random rand;

    private Vector3f position;
    private Vector3f target;
    private State state = State.IDLE;
    private float stateTimer;
    private float speed = 2.5f;

    private Room currentRoom;
    private Book currentBook;
    private TodoCrystal carriedCrystal;
    private final List<String> gossipLog = new ArrayList<>();

    // Visual
    private float bobPhase;

    public AgentNPC(String name, Role role, long seed, KnowledgeGraph kg) {
        this.name = name;
        this.role = role;
        this.kv = new KVTree(role.name(), seed);
        this.kg = kg;
        this.rand = new Random(seed);
        this.position = new Vector3f(0, 1.0f, 0);
        this.target = new Vector3f(position);
    }

    public void update(float dt, List<Room> rooms) {
        stateTimer -= dt;

        // Move toward target
        if (state == State.WALKING || state == State.CARRYING) {
            Vector3f to = new Vector3f(target).sub(position);
            float dist = to.length();
            if (dist < 0.3f) {
                arrive(rooms);
            } else {
                to.normalize().mul(speed * dt);
                position.add(to);
                bobPhase += dt * 8f;
            }
        }

        // State machine timeout → decide next action
        if (stateTimer <= 0) {
            decide(rooms);
        }
    }

    private void arrive(List<Room> rooms) {
        // Reached a room — do the role-specific thing
        if (currentRoom != null) {
            if (role == Role.EXPLORER) {
                if (kv.roll("curiosity") && !currentRoom.getBooks().isEmpty()) {
                    currentBook = currentRoom.getBooks().get(rand.nextInt(currentRoom.getBooks().size()));
                    state = State.READING;
                    stateTimer = 2f + rand.nextFloat() * 3f;
                    return;
                }
            } else {
                // Critic marks the room with a risk symbol
                state = State.MARKING;
                stateTimer = 1.5f;
                return;
            }
        }
        state = State.IDLE;
        stateTimer = 1f + rand.nextFloat() * 2f;
    }

    private void decide(List<Room> rooms) {
        if (rooms.isEmpty()) { state = State.IDLE; stateTimer = 2f; return; }

        // Gossip: occasionally emit a line (driven by KV gossip)
        if (kv.roll("gossip") && !gossipLog.isEmpty()) {
            state = State.GOSSIPING;
            stateTimer = 1f;
            return;
        }

        // Pick a destination using the KG (navigation routes)
        Room dest = pickDestination(rooms);
        if (dest != null && dest.getRoomCenter() != null) {
            currentRoom = dest;
            target = new Vector3f(dest.getRoomCenter());
            target.y = 1.0f;
            state = State.WALKING;
            stateTimer = 8f; // max walk time before re-deciding
        } else {
            state = State.IDLE;
            stateTimer = 2f;
        }
    }

    private Room pickDestination(List<Room> rooms) {
        // Explorer: wander toward rooms it's curious about (KV curiosity)
        // Critic: patrol — walk to adjacent rooms (KG neighbors)
        if (role == Role.CRITIC && currentRoom != null) {
            List<Room> nbrs = kg.neighbors(currentRoom);
            if (!nbrs.isEmpty() && kv.roll("wanderlust")) {
                return nbrs.get(rand.nextInt(nbrs.size()));
            }
        }
        // District clustering: prefer same district (KG)
        if (currentRoom != null && kv.roll("focus")) {
            Room same = kg.randomRoomInDistrict(currentRoom, rand);
            if (same != currentRoom) return same;
        }
        // Fallback: random room
        return rooms.get(rand.nextInt(rooms.size()));
    }

    /** Pick up a TODO crystal (Explorer carries tasks between rooms). */
    public void pickUpCrystal(TodoCrystal c) {
        if (role != Role.EXPLORER || carriedCrystal != null) return;
        carriedCrystal = c;
        c.setCarried(true, name);
        state = State.CARRYING;
        stateTimer = 6f;
    }

    /** Drop the carried crystal in the current room. */
    public TodoCrystal dropCrystal() {
        TodoCrystal c = carriedCrystal;
        if (c != null) {
            c.setCarried(false, null);
            if (currentRoom != null && currentRoom.getRoomCenter() != null) {
                c.setPosition(new Vector3f(currentRoom.getRoomCenter()).add(0, 0.2f, 0));
            }
            carriedCrystal = null;
        }
        return c;
    }

    /** Add a gossip line (spread between agents). */
    public void gossip(String line) {
        gossipLog.add(line);
        if (gossipLog.size() > 20) gossipLog.remove(0);
    }

    /** Attract this agent toward a newly-appeared room (curiosity-driven). */
    public void attractTo(Room room) {
        if (room == null || room.getRoomCenter() == null) return;
        // Only move if curious enough (KV-driven) — otherwise keep current path
        if (!kv.roll("curiosity")) return;
        currentRoom = room;
        target = new Vector3f(room.getRoomCenter());
        target.y = 1.0f;
        state = State.WALKING;
        stateTimer = 10f;
    }

    public String getLatestGossip() {
        return gossipLog.isEmpty() ? null : gossipLog.get(gossipLog.size() - 1);
    }

    // ── Getters ──
    public String getName() { return name; }
    public Role getRole() { return role; }
    public KVTree getKV() { return kv; }
    public Vector3f getPosition() { return position; }
    public void setPosition(Vector3f p) { this.position = new Vector3f(p); }
    public State getState() { return state; }
    public Room getCurrentRoom() { return currentRoom; }
    public Book getCurrentBook() { return currentBook; }
    public TodoCrystal getCarriedCrystal() { return carriedCrystal; }
    public float getBobPhase() { return bobPhase; }

    /** Body color — Explorer cyan, Critic amber. */
    public int getBodyTexture() {
        return role == Role.EXPLORER ? com.mindpalace.render.Renderer.TEX_NEON_CYAN
                                    : com.mindpalace.render.Renderer.TEX_NEON_AMBER;
    }
}
