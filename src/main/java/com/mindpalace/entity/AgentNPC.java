package com.mindpalace.entity;

import com.mindpalace.agent.KVTree;
import com.mindpalace.agent.KnowledgeGraph;
import com.mindpalace.agent.BehaviorTree;
import com.mindpalace.agent.OllamaClient;
import com.mindpalace.agent.ModelConfig;
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
    private BehaviorTree brain;   // real SLM brain (phi3:mini / tinyllama:1.1b)
    private float decisionCooldown; // throttle SLM calls

    private Vector3f position;
    private Vector3f target;
    private Vector3f facing = new Vector3f(0, 0, 1); // movement direction (for body orientation)
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

    /** Attach a real SLM brain, gated by the shared model scheduler. */
    public void attachBrain(OllamaClient ollama, com.mindpalace.agent.ModelScheduler scheduler) {
        String model = role == Role.EXPLORER
            ? com.mindpalace.agent.ModelConfig.TOOL_MODEL
            : com.mindpalace.agent.ModelConfig.CRITIC_MODEL;
        this.brain = new BehaviorTree(ollama, model, name, scheduler);
    }

    public void update(float dt, List<Room> rooms) {
        stateTimer -= dt;
        decisionCooldown -= dt;  // track REAL time, not per-decision ticks

        // Move toward target
        if (state == State.WALKING || state == State.CARRYING) {
            Vector3f to = new Vector3f(target).sub(position);
            float dist = to.length();
            if (dist < 0.3f) {
                arrive(rooms);
            } else {
                to.normalize();
                // Track facing (horizontal only) so the body orients to movement
                facing.set(to.x, 0, to.z).normalize();
                to.mul(speed * dt);
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

        // Consult the real SLM brain (throttled to 5-min pacing) — overrides KV/KG
        if (brain != null && decisionCooldown <= 0 && !brain.isDecisionPending()) {
            decisionCooldown = 300f; // ask every ~5 minutes (matches scheduler spacing)
            String ctx = buildBrainContext();
            brain.requestDecision(ctx, action -> applyAction(action, rooms));
            // Keep current behavior while waiting; the callback will redirect
        }

        // Gossip: occasionally emit a line (driven by KV gossip)
        if (kv.roll("gossip") && !gossipLog.isEmpty()) {
            state = State.GOSSIPING;
            stateTimer = 1f;
            return;
        }

        // Deterministic fallback: pick a destination using the KG
        Room dest = pickDestination(rooms);
        if (dest != null && dest.getRoomCenter() != null) {
            currentRoom = dest;
            target = new Vector3f(dest.getRoomCenter());
            target.y = 1.0f;
            state = State.WALKING;
            stateTimer = 8f;
        } else {
            state = State.IDLE;
            stateTimer = 2f;
        }
    }

    /** Build a compact context string for the SLM to reason over. */
    private String buildBrainContext() {
        StringBuilder sb = new StringBuilder();
        sb.append("Role: ").append(role).append("\n");
        if (currentRoom != null) {
            sb.append("In room: ").append(currentRoom.getRepoName())
              .append(" (").append(currentRoom.getBooks().size()).append(" books)\n");
        }
        if (currentBook != null) {
            sb.append("Reading: ").append(currentBook.getFilename()).append("\n");
        }
        if (carriedCrystal != null) {
            sb.append("Carrying TODO crystal: ").append(carriedCrystal.getLabel()).append("\n");
        }
        sb.append("KV curiosity=").append(kv.get("curiosity"))
          .append(" risk=").append(kv.get("riskTolerance"))
          .append(" gossip=").append(kv.get("gossip")).append("\n");
        return sb.toString();
    }

    /** Apply an SLM-chosen action to the body. */
    private void applyAction(BehaviorTree.Action action, List<Room> rooms) {
        switch (action) {
            case WALK_TO_ROOM -> {
                Room dest = pickDestination(rooms);
                if (dest != null && dest.getRoomCenter() != null) {
                    currentRoom = dest;
                    target = new Vector3f(dest.getRoomCenter());
                    target.y = 1.0f;
                    state = State.WALKING;
                    stateTimer = 8f;
                }
            }
            case READ_BOOK -> {
                if (currentRoom != null && !currentRoom.getBooks().isEmpty()) {
                    currentBook = currentRoom.getBooks().get(rand.nextInt(currentRoom.getBooks().size()));
                    state = State.READING;
                    stateTimer = 3f;
                }
            }
            case PLACE_BOOK -> {
                // Explorer places a book (generated code) in the current room
                if (role == Role.EXPLORER && currentRoom != null) {
                    state = State.PLACING;
                    stateTimer = 2f;
                } else {
                    state = State.IDLE;
                    stateTimer = 2f;
                }
            }
            case CARRY_CRYSTAL -> {
                state = State.CARRYING;
                stateTimer = 6f;
            }
            case MARK_RISK -> {
                state = State.MARKING;
                stateTimer = 2f;
            }
            case GOSSIP -> {
                state = State.GOSSIPING;
                stateTimer = 1.5f;
            }
            default -> {
                state = State.IDLE;
                stateTimer = 2f;
            }
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

    /** The SLM's most recent reasoning (why it chose its last action). */
    public String getLastReason() {
        return brain != null ? brain.getLastReason() : null;
    }

    /** Consume the SLM's latest reasoning (returns + clears, for chat surfacing). */
    public String consumeReason() {
        if (brain == null) return null;
        String r = brain.getLastReason();
        if (r == null || r.isEmpty()) return null;
        brain.clearReason();
        return r;
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
    public Vector3f getFacing() { return facing; }

    /** Distinct per-agent body color palette (bright + a few muted) — B3. */
    private static final int[] BODY_PALETTE = {
        com.mindpalace.render.Renderer.TEX_NEON_CYAN,
        com.mindpalace.render.Renderer.TEX_NEON_PINK,
        com.mindpalace.render.Renderer.TEX_NEON_GREEN,
        com.mindpalace.render.Renderer.TEX_NEON_AMBER,
        com.mindpalace.render.Renderer.TEX_BOOK_BLUE,
        com.mindpalace.render.Renderer.TEX_BOOK_YELLOW,
        com.mindpalace.render.Renderer.TEX_BOOK_ORANGE,
        com.mindpalace.render.Renderer.TEX_BOOK_RED,
    };

    /** Distinct per-agent body color — deterministic hash of the name (B3). */
    public int getBodyTexture() {
        return BODY_PALETTE[Math.floorMod(name.hashCode(), BODY_PALETTE.length)];
    }

    /** Role accent (visor + label): Explorer cyan, Critic amber. */
    public int getRoleTexture() {
        return role == Role.EXPLORER ? com.mindpalace.render.Renderer.TEX_NEON_CYAN
                                     : com.mindpalace.render.Renderer.TEX_NEON_AMBER;
    }
}
