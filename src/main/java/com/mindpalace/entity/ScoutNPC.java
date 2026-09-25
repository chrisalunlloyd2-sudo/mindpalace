package com.mindpalace.entity;

import com.mindpalace.agent.KnowledgeGraph;
import com.mindpalace.world.Room;
import org.joml.Vector3f;
import java.util.List;
import java.util.Random;

/**
 * ScoutNPC (H24, step 79) — a firefly bot that patrols the palace visiting
 * rooms and reporting its visits as chat lines.
 *
 * Doctrine: unlike Explorer/Critic, the Scout is DETERMINISTIC — no SLM brain,
 * no KV decisions, no scheduler. It walks room-to-room in a fixed rotation
 * (start offset seeded once at spawn), emits one chat line per arrival
 * ("VISIT <room> <book>" — the caller prefixes the [Scout] tag), and never
 * mutates world state (reads only). This keeps its cycle reproducible for the
 * selftest (check 47) and costs zero model quota.
 *
 * It IS an AgentNPC subclass so the existing render/minimap/update paths draw
 * and tick it without edits; the patrol ledger advances on the game thread
 * only (step-81 drain rule — no cross-thread mutation).
 */
public class ScoutNPC extends AgentNPC {

    private final List<Room> visitOrder;   // fixed rotation (deterministic)
    private int visitIndex = 0;
    private float emitCooldown;            // seconds until the next VISIT line
    private static final float DWELL = 6f; // seconds per room

    /** Build the visit rotation from the room list. The seed only offsets the
     *  starting index (same seed -> same start, same cycle thereafter). */
    public ScoutNPC(String name, long seed, List<Room> rooms, KnowledgeGraph kg) {
        super(name, Role.EXPLORER, seed, kg);
        this.baseSeed = seed;
        this.visitOrder = rooms;
        Random rng = new Random(seed);
        this.visitIndex = rooms.isEmpty() ? 0 : rng.nextInt(rooms.size());
        this.emitCooldown = DWELL;
    }

    /** One deterministic patrol tick. Returns the chat line to emit, or null.
     *  NOT thread-safe — call from the game thread only. */
    public String patrolTick(float dt) {
        if (visitOrder.isEmpty()) return null;
        emitCooldown -= dt;
        if (emitCooldown > 0f) return null;
        emitCooldown = DWELL;

        Room target = visitOrder.get(visitIndex % visitOrder.size());
        visitIndex++;

        // Arrive at the room center (deterministic ledger tick — the body's
        // walk animation rides the inherited AgentNPC.update state machine).
        if (target.getRoomCenter() != null) {
            setPosition(new Vector3f(target.getRoomCenter()).add(0f, 1.0f, 0f));
        }
        String book = target.getBooks().isEmpty()
            ? "-"
            : target.getBooks().get(0).getFilename();
        return "VISIT " + target.getRepoName() + " " + book;
    }

    /** Deterministic visit-ledger read (selftest bridge). */
    public Room peekNextRoom() {
        return visitOrder.isEmpty() ? null : visitOrder.get(visitIndex % visitOrder.size());
    }

    // ── H30 (step 84): scout economy — DePIN credits for UNIQUE visits ─────
    // Never-twice dedupe: a room pays only on its FIRST visit. Repeat
    // visits patrol but earn nothing (patrol ≠ exploration). Thread-safe:
    // only mutated on the game thread (updateNPCs).
    private final java.util.Set<String> creditedRooms = new java.util.HashSet<>();

    /** True if this room's repo was unvisited (credits awarded), false on
     *  repeat visits. Pure — no wallet side effects here. */
    public boolean awardUniqueVisit(Room room) {
        if (room == null || room.getRepoName() == null) return false;
        return creditedRooms.add(room.getRepoName());
    }

    /** Rooms credited so far (ledger read). */
    public int uniqueRoomsCredited() {
        return creditedRooms.size();
    }

    public float getEmitCooldown() { return emitCooldown; }

    // ── H31 (step 85): bot lifecycle — TTL, graceful retire, fresh respawn ─
    // A bot is not immortal: after BOT_TTL_SECONDS of patrol it retires
    // gracefully (one farewell line, wallet kept, never-twice ledger KEPT —
    // credits survive the body), and a fresh-seeded successor spawns so the
    // palace always has exactly one scout. Generation counter tracks lineage;
    // the new seed = baseSeed + generation*1000 keeps patrol deterministic
    // per generation while guaranteeing each generation starts somewhere new.
    public static final float BOT_TTL_SECONDS = 180f; // 3 minutes of duty

    private final long baseSeed;   // the seed given at first spawn
    private int generation = 0;    // 0 = original, 1 = first respawn, ...
    private float ageSeconds = 0f; // duty time this generation
    private boolean retired = false; // graceful-retire flag (drains patrol)

    /** Advance the duty clock. Call once per game-thread tick BEFORE
     *  patrolTick. Returns true the tick this bot gracefully retires
     *  (caller: emit farewell + spawn successor with the next generation). */
    public boolean ageTick(float dt) {
        if (retired) return false;
        ageSeconds += dt;
        if (ageSeconds >= BOT_TTL_SECONDS) {
            retired = true;
            return true;
        }
        return false;
    }

    /** True after ageTick() reported retirement — the NPC stops patrolling
     *  but stays in the world one frame so the farewell line can render. */
    public boolean isRetired() { return retired; }

    /** The successor seed for this bot's next generation (deterministic). */
    public long successorSeed() { return baseSeed + (generation + 1) * 1000L; }

    /** Promote this instance to the next generation: new patrol start, duty
     *  clock reset, retire flag cleared, lineage +1. The unique-visit ledger
     *  is INTENTIONALLY kept — an economy memory must survive respawns. */
    public void respawn() {
        generation++;
        Random rng = new Random(baseSeed + generation * 1000L);
        this.visitIndex = visitOrder.isEmpty() ? 0 : rng.nextInt(visitOrder.size());
        this.emitCooldown = DWELL;
        this.ageSeconds = 0f;
        this.retired = false;
    }

    public int getGeneration() { return generation; }
    public float getAgeSeconds() { return ageSeconds; }
    public float getTtlSeconds() { return BOT_TTL_SECONDS; }

    // ── Step 80 (H25): scout → quorum ──────────────────────────────────────
    // Each visit also becomes a quorum proposal ("scout suggests room X as a
    // work target"). The proposal rides the house quorum like any other; an
    // APPROVED verdict lets GameEngine spawn a TODO crystal at that room.
    // Deterministic: proposals are keyed visit-scope + room, auto-voted in
    // selftest mode (no cloud), live-voted in play.

    /** Wrap the last visit into a quorum proposal id (stable per visit). */
    public String proposalIdFor(Room room) {
        return "scout-" + Integer.toHexString(room.getRepoName().hashCode()) + "-" + visitIndex;
    }
}