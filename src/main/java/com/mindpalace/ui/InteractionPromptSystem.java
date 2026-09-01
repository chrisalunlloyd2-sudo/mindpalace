package com.mindpalace.ui;

import com.mindpalace.entity.Player;
import com.mindpalace.render.FontRenderer;
import com.mindpalace.world.Book;
import com.mindpalace.world.Room;
import com.mindpalace.world.WorldBuilder;
import com.mindpalace.world.OutsideWorld;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * InteractionPromptSystem — ONE unified "nearby interactable" prompt (TASK 0002).
 *
 * The Architect's ask: "in game vr pop-ups... need to follow the player
 * everywhere like tooltips but telling which button can be pressed at that
 * moment to activate a nearby item or action."
 *
 * Every frame this scans ALL interactable types near the player — doors,
 * books, teleporter pads, the planet return pad, model shops, the mansion
 * door — picks the single best candidate (closest + roughly in view), and
 * renders ONE consistent prompt anchored to the screen: a billboard pinned
 * 3m in front of the camera (same HUD geometry as renderScreenHUD), so it
 * always sits in the same HUD-relative position and follows the player
 * everywhere. It is NOT pinned to the world object. Hidden cleanly when
 * nothing is interactable.
 *
 * Reuses the existing per-type detection semantics (door proximity from the
 * old HUD, the pad flags from Player.update, nearestShopIndex for shops) —
 * this centralizes prompt SELECTION + RENDERING, not the underlying
 * interactable logic. The in-world book content tooltip
 * (GameEngine.renderBookTooltip) is deliberately separate and stays: that
 * shows WHAT a book is; this shows WHICH BUTTON does what.
 *
 * Performance: O(n) over already-nearby lists only — rooms with a door
 * within DOOR_RANGE, placed books in the current room within BOOK_RANGE,
 * at most a handful of pads/shops. A few small vector allocations, no
 * logging except on text change.
 */
public class InteractionPromptSystem {

    // ── Tunables (match the semantics of the checks they replace) ──
    private static final float DOOR_RANGE     = 3.0f;  // Player.findDoor reach
    private static final float FACING_DOT     = 0.75f; // ~41° half-angle, forgiving like findDoor
    private static final float BOOK_RANGE     = 3.5f;  // generous shelf reach (books are small)
    private static final float BOOK_FACING_DOT = 0.35f; // near view is enough; crosshair not required
    private static final float SHOP_RANGE     = 5.0f;  // nearestShopIndex range used by update()
    private static final float MANSION_RANGE  = 3.0f;   // |dx|<3 && |dz|<3 box used in update()

    // HUD anchor — screen-space position, same geometry family as renderScreenHUD.
    // Slightly ABOVE view center: below the room-info line, above the hotkeys,
    // and above the horizon so it never fights the floor/HUD for attention.
    private static final float PROMPT_DIST = 3.0f;   // meters in front of the camera
    private static final float PROMPT_RISE = 0.15f;  // above view center

    // Palette — magenta, the ONLY magenta text in the HUD (wallet line is gold
    // (1.0,0.85,0.3), fact toast amber (1.0,0.9,0.4), room info cyan, hotkeys
    // grey). Magenta = "you can act" and is trivially hue-assertable against a
    // control shot — no other element or toast uses it.
    private static final Vector3f PROMPT_COLOR = new Vector3f(1.0f, 0.2f, 0.9f);

    /** The currently selected prompt — null when nothing nearby. */
    private Prompt current;
    /** Last rendered text — log transitions only, not every frame. */
    private String lastText = "";

    /** A selected interactable: button + action label + source position. */
    public static final class Prompt {
        public final String button;   // e.g. "ENTER"
        public final String action;   // e.g. "Open door: mindpalace (Java)"
        public final Vector3f pos;    // world position of the interactable
        Prompt(String button, String action, Vector3f pos) {
            this.button = button;
            this.action = action;
            this.pos = pos;
        }
        public String text() { return "[" + button + "] " + action; }
    }

    /**
     * Scan every interactable type and pick the single best candidate.
     * Pure selection — safe to call from selftest without drawing.
     * O(n) over small already-nearby lists; no full-world scan.
     */
    public Prompt select(Player player, WorldBuilder world, boolean chatTyping) {
        Vector3f origin = player.getPosition();
        Vector3f front  = player.getLookDirection();

        // Chat typing suppresses interaction (same rule as Player doors).
        if (chatTyping) { current = null; return null; }

        Prompt best = null;
        float bestDist = Float.MAX_VALUE;

        // ── Hallway teleporter pads — prompt when standing on one ──
        // (Player.update derives padFloor from the real pad geometry; index
        //  == floor per WorldBuilder.getTeleporterPads.)
        if (player.getPadFloor() >= 0) {
            int padFloor = player.getPadFloor();
            java.util.List<Vector3f> pads = world.getTeleporterPads();
            Vector3f pad = (padFloor < pads.size()) ? pads.get(padFloor)
                : new Vector3f(origin.x, origin.y - 1.6f, origin.z);
            best = new Prompt("ENTER", "Use teleporter (Floor " + (padFloor + 1) + " pad)", pad);
            bestDist = 0f; // standing on it — strongest candidate
        }

        // ── Planet return pad — always prompt when standing on it ──
        if (player.isOnPlanetPad()) {
            Vector3f pp = world.getPlanetPad();
            float d = origin.distance(pp);
            if (d < bestDist) {
                best = new Prompt("ENTER", "Use teleporter (planet pad)", pp);
                bestDist = d;
            }
        }

        // ── Room doors — nearest door within reach, roughly in view ──
        if (player.getCurrentRoom() == null) {
            for (Room room : world.getRooms()) {
                Vector3f dp = room.getDoorPosition();
                if (dp == null) continue;
                float d = origin.distance(dp);
                if (d > DOOR_RANGE || d >= bestDist) continue;
                Vector3f to = new Vector3f(dp).sub(origin).normalize();
                if (front.dot(to) < FACING_DOT) continue;
                StringBuilder sb = new StringBuilder("Open door: ").append(room.getDisplayLabel());
                if (room.getLastCommit() != null && !room.getLastCommit().isEmpty()) {
                    String lc = room.getLastCommit();
                    sb.append(" | last: ").append(lc.length() > 40 ? lc.substring(0, 38) + ".." : lc);
                }
                best = new Prompt("ENTER", sb.toString(), dp);
                bestDist = d;
            }
        }

        // ── Books — nearest placed book in the current room, near view ──
        // (The crosshair tooltip/highlight stay separate — those need a direct
        //  look; the prompt only needs the book to be nearby and in view.)
        Room room = player.getCurrentRoom();
        if (room != null) {
            for (Book book : room.getBooks()) {
                if (!book.isPlaced()) continue;
                Vector3f bp = new Vector3f(book.getWorldX(), book.getWorldY(), book.getWorldZ());
                float d = origin.distance(bp);
                if (d > BOOK_RANGE || d >= bestDist) continue;
                Vector3f to = new Vector3f(bp).sub(origin).normalize();
                if (front.dot(to) < BOOK_FACING_DOT) continue;
                String fn = book.getFilename();
                best = new Prompt("CLICK", "Read book: "
                    + (fn.length() > 28 ? fn.substring(0, 26) + ".." : fn), bp);
                bestDist = d;
            }
        }

        // ── Model shops — nearest stall within range (outside world) ──
        if (world.getOutsideWorld() != null && player.getCurrentRoom() == null) {
            int si = world.getOutsideWorld().nearestShopIndex(origin.x, origin.z, SHOP_RANGE);
            if (si >= 0) {
                OutsideWorld.Shop shop = world.getOutsideWorld().getShops()[si];
                float d = origin.distance(new Vector3f(shop.pos.x, origin.y, shop.pos.z));
                if (d < bestDist) {
                    best = new Prompt("ENTER", "Browse shop: " + shop.name
                        + " (" + (int) shop.cost + " credits)", shop.pos);
                    bestDist = d;
                }
            }
        }

        // ── Mansion door — the Enter toggle box near the mansion ──
        if (world.getOutsideWorld() != null && player.getCurrentRoom() == null) {
            Vector3f m = world.getOutsideWorld().getMansionPos();
            float dx = Math.abs(origin.x - m.x);
            float dz = Math.abs(origin.z - (m.z - 9f));
            if (dx < MANSION_RANGE && dz < MANSION_RANGE) {
                float d = Math.min(dx, dz); // effectively at the door
                if (d < bestDist) {
                    best = new Prompt("ENTER", "Enter/leave mansion", m);
                    bestDist = d;
                }
            }
        }

        current = best;
        return best;
    }

    /** The most recent selection (or null) — for engine-side logging/tests. */
    public Prompt current() { return current; }

    /**
     * Render the selected prompt as a HUD-anchored billboard: pinned 3m in
     * front of the camera slightly above view center (same geometry family
     * as renderScreenHUD), so it sits in a fixed screen position whenever
     * something is interactable — "follows the player everywhere", never
     * world-pinned. No-op when nothing is selected.
     */
    public void render(FontRenderer fontRenderer, Matrix4f proj, Matrix4f view,
                       Vector3f camPos, Vector3f camFront) {
        if (fontRenderer == null || !fontRenderer.isReady() || current == null) return;

        Vector3f anchor = new Vector3f(camPos).add(
            camFront.x * PROMPT_DIST,
            camFront.y * PROMPT_DIST + PROMPT_RISE,
            camFront.z * PROMPT_DIST);

        fontRenderer.renderBillboard(current.text(), anchor, 0.065f, PROMPT_COLOR, proj, view, camPos);

        String t = current.text();
        if (!t.equals(lastText)) {
            lastText = t;
            System.out.println("[Prompt] " + t);
        }
    }
}