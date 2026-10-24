package com.mindpalace.world;

import com.mindpalace.render.Camera;
import com.mindpalace.render.Renderer;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * MP-008 (#154): baked interior previews shown on the portal sheet.
 *
 * ONE fixed 256x256 FBO is shared by all rooms; ONE room is re-rendered per
 * frame (amortized), round-robin over the rooms currently visible+revealed.
 * The bake camera is parked inside the room just behind the hallway-side
 * aperture wall (doorPosition.x), looking into the room with a frustum whose
 * aspect matches the portal sheet ((DOOR_WIDTH-0.08)/(DOOR_HEIGHT-0.08)) so
 * the sheet samples the bake 1:1. The sheet itself (door leaf/portal quad at
 * the wall plane) sits behind the bake camera and can never photobomb its
 * own preview, so no renderRoom geometry skipping is needed at all.
 *
 * Re-bakes only when the room's preview-relevant state changes (health
 * score / language tint). MP_DOOR_PREVIEW=0 disables the feature in
 * WorldBuilder (bank never constructed).
 */
public class RoomPreviewBank {

    /** Renders one room's interior into whatever framebuffer is bound. */
    public interface RoomBaker {
        void bakeRoom(Renderer r, Room room);
    }

    private static final int SIZE = 256;

    private final RoomBaker baker;
    private final Camera bakeCam = new Camera();

    private int fbo;
    private int depthRb;
    private boolean fboReady;

    private final Map<Room, Entry> entries = new IdentityHashMap<>();
    private int cursor;

    private static final class Entry {
        final int tex;
        long hash;
        Entry(int tex) { this.tex = tex; }
    }

    public RoomPreviewBank(RoomBaker baker) { this.baker = baker; }

    /**
     * Bake at most ONE room this frame. Scans the visible set starting at a
     * rotating cursor; the first room that is unseen or whose state hash
     * changed gets re-rendered. Returns 1 if a bake happened.
     */
    public int bakeOne(Renderer r, List<Room> visible) {
        if (!fboReady && !initFbo()) return 0;
        if (visible.isEmpty()) return 0;

        Room pick = null;
        int scan = visible.size();
        for (int i = 0; i < scan; i++) {
            Room cand = visible.get((cursor + i) % scan);
            long h = stateHash(cand);
            Entry e = entries.get(cand);
            if (e == null || e.hash != h) {
                if (e == null) {
                    entries.put(cand, e = new Entry(createTexture()));
                }
                e.hash = h;
                pick = cand;
                break; // one bake per frame — the rest catch the next frames
            }
        }
        cursor = (cursor + 1) % scan;
        if (pick == null) return 0;

        bake(r, pick);
        return 1;
    }

    /** GL texture id of the room's baked interior, or 0 if not baked yet. */
    public int getPreviewTexture(Room room) {
        Entry e = entries.get(room);
        return e == null ? 0 : e.tex;
    }

    private void bake(Renderer r, Room room) {
        // Save the main camera state (uniforms only — field objects untouched).
        Matrix4f savedProj = new Matrix4f(r.getProjectionMatrix());
        Matrix4f savedView = new Matrix4f(r.getViewMatrix());
        Vector3f savedViewPos = new Vector3f(r.getViewPos());

        // Park the bake camera inside the room, just behind the aperture wall,
        // looking into the interior with the sheet's aspect.
        int side = room.getHallwaySide();
        float inward = side == 0 ? -1f : 1f; // side 0: hallway toward +X
        Vector3f dp = room.getDoorPosition();
        bakeCam.setPosition(dp.x + inward * 0.45f, dp.y + 0.2f, dp.z);
        bakeCam.setYaw(90f * inward);        // -90 looks -X, +90 looks +X
        bakeCam.setPitch(0f);
        float aspect = (Room.DOOR_WIDTH - 0.08f) / (Room.DOOR_HEIGHT - 0.08f);

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
            GL11.GL_TEXTURE_2D, entries.get(room).tex, 0);
        GL11.glViewport(0, 0, SIZE, SIZE);
        GL11.glClearColor(0.03f, 0.03f, 0.05f, 1f);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);

        r.setUniformsOnly(bakeCam.getProjectionMatrix(aspect),
            bakeCam.getViewMatrix(), bakeCam.getPosition());
        baker.bakeRoom(r, room);

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        GL11.glViewport(0, 0, r.getWidth(), r.getHeight());
        r.setUniformsOnly(savedProj, savedView, savedViewPos);
    }

    /** Only preview-visible state: health glow + language tint. */
    private long stateHash(Room room) {
        long h = room.getHealthScore();
        float[] t = room.getTint();
        h = h * 1000003L + Float.floatToIntBits(t[0]) * 31L + Float.floatToIntBits(t[1]);
        h = h * 1000003L + room.getFloor();
        return h;
    }

    private boolean initFbo() {
        fbo = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        depthRb = GL30.glGenRenderbuffers();
        GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, depthRb);
        GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, GL30.GL_DEPTH_COMPONENT24, SIZE, SIZE);
        GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
            GL30.GL_RENDERBUFFER, depthRb);
        GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, 0);
        boolean ok = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER)
            == GL30.GL_FRAMEBUFFER_COMPLETE;
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        fboReady = ok;
        if (!ok) System.err.println("[PreviewBank] FBO incomplete — previews disabled");
        return ok;
    }

    private int createTexture() {
        int tex = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, SIZE, SIZE, 0,
            GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        return tex;
    }

    /** MP-008 (#154): free FBO + depth renderbuffer + every baked texture. */
    public void dispose() {
        for (Entry e : entries.values()) {
            if (e.tex > 0) GL11.glDeleteTextures(e.tex);
        }
        entries.clear();
        if (fbo > 0) {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
            GL30.glDeleteFramebuffers(fbo);
            fbo = 0;
        }
        if (depthRb > 0) {
            GL30.glDeleteRenderbuffers(depthRb);
            depthRb = 0;
        }
        fboReady = false;
    }

    /** Used only to build the visible list outside the hot loop. */
    public static List<Room> visibleRooms(List<Room> rooms,
                                          com.mindpalace.render.FrustumCuller frustum,
                                          Vector3f camPos,
                                          com.mindpalace.world.FogOfWar fogOfWar) {
        List<Room> out = new ArrayList<>();
        for (Room room : rooms) {
            Vector3f c = room.getRoomCenter();
            if (frustum != null && !frustum.intersectsAabb(c, Room.ROOM_WIDTH / 2f, 1.5f, Room.ROOM_DEPTH / 2f)) continue;
            if (room.isFogged() && (fogOfWar == null || !fogOfWar.isRoomRevealed(room))) continue;
            out.add(room);
        }
        return out;
    }
}