package com.mindpalace.world;

import com.mindpalace.render.Renderer;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * The Einstein Campus: an open-air research district in the outside world, where a developer's and an
 * engineer's idea of a lab meets relativity. Six exhibits around a plaza:
 * <ul>
 *   <li><b>Collider</b> - a 36-magnet ring with two counter-rotating particle bunches that meet at two detectors,
 *       each collision flashing white-gold;</li>
 *   <li><b>Spacetime lattice</b> - a sheet of tiles sagging into a well around a glowing mass, with a planet
 *       following the curved sheet;</li>
 *   <li><b>Tesla coil</b> - a tapering tower, a toroid and flickering arcs;</li>
 *   <li><b>Interferometer</b> - two laser arms whose lengths breathe like a passing gravitational wave;</li>
 *   <li><b>Relativity clocks</b> - one at rest, one on a carousel at v = 0.866c, so its hand runs at 1/gamma = half speed;</li>
 *   <li><b>Cryostat</b> - a chandelier-shaped quantum computer with pulsing qubits.</li>
 * </ul>
 * Everything is a coloured primitive, so the geometry is a pure function of (centre, time, detail): {@link #build}
 * returns the pieces and {@link #render} draws them. That makes the layout testable without a GL context.
 * The brightest pieces are deliberately above the bloom bright-pass (luminance 0.9, threshold 0.6 + 0.3), so the
 * campus is where the bloom pass is most visible.
 */
public final class EinsteinCampus {
    /** Campus centre in the open world: clear of the mansion, hospital, factory, towns, lake and the path strips. */
    public static final float CX = 108f, CZ = -78f;
    /** Everything is built inside this radius of the centre. */
    public static final float RADIUS = 30f;
    /** Within this distance the full detail is drawn; beyond it only the silhouette (ring, beams, beacons). */
    public static final float NEAR_DIST = 48f;
    /** Bloom intensity preset used while the player is on the campus (the hall is 0.55, courtyard 0.95, forest 0.75). */
    public static final float BLOOM_CAMPUS = 1.4f;
    /** The moving clock travels at v = sqrt(3)/2 c, so gamma = 1 / sqrt(1 - v^2) = 2. */
    public static final float GAMMA = 2.0f;
    /** The rest clock's hand, radians per second. The moving clock's hand turns at REST_RATE / GAMMA. */
    public static final float REST_RATE = 1.2f;

    public static final String[] EXHIBITS = {"collider", "spacetime lattice", "tesla coil", "interferometer", "relativity clocks", "cryostat"};

    /** One coloured primitive. {@code emissive} marks the pieces meant to glow through the bloom pass. */
    public record Piece(String exhibit, boolean hex, float x, float y, float z, float sx, float sy, float sz, float yaw,
                        float r, float g, float b, boolean emissive) {
        public float luminance() { return 0.2126f * r + 0.7152f * g + 0.0722f * b; }
    }

    private EinsteinCampus() {}

    public static boolean contains(float x, float z) {
        float dx = x - CX, dz = z - CZ;
        return dx * dx + dz * dz <= RADIUS * RADIUS;
    }

    /** True when (x,z) is on the campus or within the bloom blend margin around it. */
    public static boolean nearCampus(float x, float z, float margin) {
        float dx = x - CX, dz = z - CZ, rr = RADIUS + margin;
        return dx * dx + dz * dz <= rr * rr;
    }

    // ── geometry ──────────────────────────────────────────────────────────

    /** Build every piece for this instant. {@code near} = full detail, else the distant silhouette only. */
    public static List<Piece> build(float floorY, float time, boolean near) {
        List<Piece> p = new ArrayList<>(near ? 420 : 130);
        plaza(p, floorY, near);
        collider(p, floorY, time, near);
        beacons(p, floorY, time);
        if (!near) return p;
        spacetime(p, floorY, time);
        tesla(p, floorY, time);
        interferometer(p, floorY, time);
        clocks(p, floorY, time);
        cryostat(p, floorY, time);
        return p;
    }

    private static void add(List<Piece> p, String ex, boolean hex, float x, float y, float z, float sx, float sy, float sz,
                            float yaw, float r, float g, float b, boolean glow) {
        p.add(new Piece(ex, hex, CX + x, y, CZ + z, sx, sy, sz, yaw, r, g, b, glow));
    }

    private static void plaza(List<Piece> p, float fy, boolean near) {
        // dark slab, the first piece drawn (a cube, so the colour shader is bound for the hex prisms after it)
        add(p, "plaza", false, 0, fy + 0.04f, 0, 2f * RADIUS - 2f, 0.08f, 2f * RADIUS - 2f, 0f, 0.13f, 0.14f, 0.17f, false);
        if (!near) return;
        // inlaid light lines radiating to the four exhibits and the diagonals
        for (int i = 0; i < 8; i++) {
            float a = i * (float) Math.PI / 4f;
            float len = 10f;
            float mx = (float) Math.cos(a) * (6f + len / 2f), mz = (float) Math.sin(a) * (6f + len / 2f);
            add(p, "plaza", false, mx, fy + 0.09f, mz, len, 0.02f, 0.12f, -a, 0.55f, 1.0f, 1.0f, true);
        }
    }

    /** Corner pylons: four white beacons that pulse out of phase, visible from far away. */
    private static void beacons(List<Piece> p, float fy, float time) {
        for (int i = 0; i < 4; i++) {
            float a = i * (float) Math.PI / 2f + (float) Math.PI / 4f;
            float bx = (float) Math.cos(a) * (RADIUS - 2f), bz = (float) Math.sin(a) * (RADIUS - 2f);
            add(p, "plaza", false, bx, fy + 3f, bz, 0.5f, 6f, 0.5f, 0f, 0.3f, 0.32f, 0.38f, false);
            float pulse = 0.75f + 0.25f * (float) Math.sin(time * 2f + i * 1.7f);
            add(p, "plaza", false, bx, fy + 6.4f, bz, 0.9f, 0.9f, 0.9f, 0f, pulse, pulse, 1.0f * pulse, true);
        }
    }

    // exhibit 1 - collider ---------------------------------------------------

    static final int MAGNETS = 36;
    static final float RING_R = 17f;

    private static void collider(List<Piece> p, float fy, float time, boolean near) {
        int step = near ? 1 : 2;
        for (int i = 0; i < MAGNETS; i += step) {
            float a = i * 2f * (float) Math.PI / MAGNETS;
            float x = (float) Math.cos(a) * RING_R, z = (float) Math.sin(a) * RING_R;
            float yaw = -(a + (float) Math.PI / 2f);                      // long side along the tangent
            float seg = step * 2f * (float) Math.PI * RING_R / MAGNETS;
            boolean coil = i % 3 == 0;
            // magnet housing
            add(p, "collider", false, x, fy + 0.7f, z, seg, 1.2f, 1.3f, yaw, 0.34f, 0.37f, 0.45f, false);
            if (coil) add(p, "collider", false, x, fy + 0.7f, z, seg * 0.3f, 1.5f, 1.55f, yaw, 1.0f, 0.92f, 0.45f, true);
            // beam tube glowing through the housing
            add(p, "collider", false, x, fy + 1.45f, z, seg, 0.12f, 0.12f, yaw, 0.55f, 1.0f, 1.0f, true);
        }
        // two counter-rotating bunches of three particles, one cyan-white, one magenta
        for (int k = 0; k < 3; k++) {
            float a1 = time * 1.8f - k * 0.12f, a2 = (float) Math.PI - time * 1.8f + k * 0.12f;
            add(p, "collider", false, (float) Math.cos(a1) * RING_R, fy + 1.55f, (float) Math.sin(a1) * RING_R, 0.55f, 0.4f, 0.4f, -a1, 1f, 1f, 1f, true);
            add(p, "collider", false, (float) Math.cos(a2) * RING_R, fy + 1.55f, (float) Math.sin(a2) * RING_R, 0.55f, 0.4f, 0.4f, -a2, 1f, 0.75f, 1f, true);
        }
        // two detectors at the collision points (angle 0 and pi) with a flash when a bunch is near
        for (int d = 0; d < 2; d++) {
            float da = d * (float) Math.PI;
            float dx = (float) Math.cos(da) * RING_R, dz = (float) Math.sin(da) * RING_R;
            add(p, "collider", false, dx, fy + 2.5f, dz, 4.5f, 5f, 4.5f, 0f, 0.22f, 0.24f, 0.3f, false);
            float lead = (float) Math.abs(Math.sin((time * 1.8f - da) / 2f));   // 0 when the bunch is at this detector
            float flash = Math.max(0f, 1f - lead * 3.5f);
            float g = 0.35f + 0.65f * flash;
            add(p, "collider", false, dx, fy + 2.5f, dz, 3.2f + 1.2f * flash, 3.2f + 1.2f * flash, 3.2f + 1.2f * flash, 0f, 1f, g, 0.2f + 0.5f * flash, true);
            if (near) add(p, "collider", true, dx, fy + 5.7f, dz, 2.2f, 0.5f, 2.2f, 0f, 0.8f, 0.9f, 1f, true);
        }
    }

    // exhibit 2 - spacetime lattice --------------------------------------------

    static final int GRID = 11;

    /** Height of the lattice sheet at distance rr from the mass: flat far away, sagging to -DEPTH under the mass. */
    static float sag(float rr) { return -2.4f / (1f + rr * rr * 0.55f); }

    private static void spacetime(List<Piece> p, float fy, float time) {
        float sheetY = fy + 3.1f, cell = 1.15f;
        for (int i = 0; i < GRID; i++) {
            for (int j = 0; j < GRID; j++) {
                float x = (i - GRID / 2) * cell, z = (j - GRID / 2) * cell;
                float rr = (float) Math.sqrt(x * x + z * z);
                float y = sheetY + sag(rr);
                float depth = -sag(rr) / 2.4f;                           // 0 flat .. 1 deepest
                add(p, "spacetime lattice", false, x, y, z, cell * 0.92f, 0.07f, cell * 0.92f, 0f,
                    0.15f + 0.85f * depth, 0.25f + 0.75f * depth * depth, 1.0f, depth > 0.45f);
            }
        }
        // the mass: a glowing core sitting in the well
        add(p, "spacetime lattice", true, 0, sheetY + sag(0) + 0.45f, 0, 0.9f, 0.9f, 0.9f, 0f, 1.0f, 0.88f, 0.4f, true);
        // a planet on the curved sheet, speeding up as it falls inward (r(t) breathes between 2.2 and 4.0)
        float orbit = time * 1.1f;
        float pr = 3.1f + 0.9f * (float) Math.sin(time * 0.5f);
        float px = (float) Math.cos(orbit) * pr, pz = (float) Math.sin(orbit) * pr;
        add(p, "spacetime lattice", false, px, sheetY + sag(pr) + 0.3f, pz, 0.35f, 0.35f, 0.35f, 0f, 0.4f, 1.0f, 0.6f, true);
        // support pillars at the four corners
        for (int i = 0; i < 4; i++) {
            float a = i * (float) Math.PI / 2f + (float) Math.PI / 4f;
            add(p, "spacetime lattice", false, (float) Math.cos(a) * 8f, fy + 1.55f, (float) Math.sin(a) * 8f, 0.25f, 3.1f, 0.25f, 0f, 0.3f, 0.33f, 0.4f, false);
        }
    }

    // exhibit 3 - tesla coil ---------------------------------------------------

    private static void tesla(List<Piece> p, float fy, float time) {
        float ox = -22f, oz = 0f;
        for (int i = 0; i < 5; i++) {
            float w = 1.9f - i * 0.28f;
            add(p, "tesla coil", true, ox, fy + 0.6f + i * 1.1f, oz, w, 1.1f, w, 0f, 0.7f - i * 0.04f, 0.45f, 0.2f, false);
        }
        // toroid
        for (int i = 0; i < 8; i++) {
            float a = i * (float) Math.PI / 4f;
            add(p, "tesla coil", false, ox + (float) Math.cos(a) * 1.0f, fy + 6.3f, oz + (float) Math.sin(a) * 1.0f, 0.55f, 0.5f, 0.55f, 0f, 0.7f, 0.72f, 0.8f, false);
        }
        // arcs: six bolts that flicker in length and brightness and wander in angle
        for (int i = 0; i < 6; i++) {
            float flick = 0.5f + 0.5f * (float) Math.sin(time * (9f + i * 2.3f) + i * 4.1f);
            float len = 2.5f + 4f * flick;
            float a = i * (float) Math.PI / 3f + 0.5f * (float) Math.sin(time * 1.3f + i);
            float mx = ox + (float) Math.cos(a) * (1.0f + len / 2f * 0.7f), mz = oz + (float) Math.sin(a) * (1.0f + len / 2f * 0.7f);
            add(p, "tesla coil", false, mx, fy + 6.3f - len * 0.25f, mz, len, 0.07f, 0.07f, -a, 0.85f, 0.7f + 0.3f * flick, 1.0f, flick > 0.2f);
        }
    }

    // exhibit 4 - interferometer -------------------------------------------------

    private static void interferometer(List<Piece> p, float fy, float time) {
        float ox = 19f, oz = 0f;
        float wave = 0.18f * (float) Math.sin(time * 3f);               // one arm stretches while the other squeezes
        float la = 9f + wave, lb = 9f - wave;
        add(p, "interferometer", false, ox, fy + 1.1f, oz, 0.9f, 0.5f, 0.9f, 0f, 0.7f, 0.9f, 1.0f, false);   // beam splitter
        add(p, "interferometer", false, ox + la / 2f, fy + 1.1f, oz, la, 0.1f, 0.1f, 0f, 1.0f, 0.62f, 0.55f, true);      // x arm laser
        add(p, "interferometer", false, ox, fy + 1.1f, oz + lb / 2f, 0.1f, 0.1f, lb, 0f, 1.0f, 0.62f, 0.55f, true);      // z arm laser
        add(p, "interferometer", false, ox + la + 0.2f, fy + 1.1f, oz, 0.3f, 1.2f, 1.2f, 0f, 0.8f, 0.85f, 0.95f, false); // end mirrors
        add(p, "interferometer", false, ox, fy + 1.1f, oz + lb + 0.2f, 1.2f, 1.2f, 0.3f, 0f, 0.8f, 0.85f, 0.95f, false);
        // light source and photodetector
        add(p, "interferometer", false, ox - 1.4f, fy + 1.1f, oz, 1.0f, 0.8f, 0.8f, 0f, 0.3f, 0.32f, 0.4f, false);
        float fringe = 0.5f + 0.5f * (float) Math.sin(time * 3f * 4f);
        add(p, "interferometer", false, ox - 1.2f, fy + 1.1f, oz + 1.6f, 0.6f, 0.6f, 0.6f, 0f, fringe, fringe, 0.2f + 0.8f * fringe, true);
        // vacuum tube sleeves along both arms
        for (int i = 1; i <= 3; i++) {
            add(p, "interferometer", false, ox + i * 2.4f, fy + 0.55f, oz, 0.3f, 1.1f, 0.3f, 0f, 0.3f, 0.33f, 0.4f, false);
            add(p, "interferometer", false, ox, fy + 0.55f, oz + i * 2.4f, 0.3f, 1.1f, 0.3f, 0f, 0.3f, 0.33f, 0.4f, false);
        }
    }

    // exhibit 5 - relativity clocks --------------------------------------------------

    /** Angle of the rest clock's hand at time t. */
    public static float restHand(float t) { return t * REST_RATE; }

    /** Angle of the moving clock's hand at time t: proper time runs 1/gamma as fast. */
    public static float movingHand(float t) { return t * REST_RATE / GAMMA; }

    private static void clocks(List<Piece> p, float fy, float time) {
        float oz = 22f;
        // rest clock, left; moving clock on a carousel, right
        clockFace(p, fy, -5f, oz, restHand(time), 0.55f, 1.0f, 1.0f);
        float car = time * 0.9f;                                         // the carousel the moving clock rides
        float cx = 5f + (float) Math.cos(car) * 2.2f, cz = oz + (float) Math.sin(car) * 2.2f;
        add(p, "relativity clocks", true, 5f, fy + 0.15f, oz, 6.4f, 0.3f, 6.4f, 0f, 0.2f, 0.22f, 0.28f, false);   // carousel disc
        clockFace(p, fy, cx, cz, movingHand(time), 1.0f, 0.92f, 0.45f);
        // tick marks: the rest clock has 12, laid out on a ring under it
        for (int i = 0; i < 12; i++) {
            float a = i * (float) Math.PI / 6f;
            add(p, "relativity clocks", false, -5f + (float) Math.cos(a) * 1.6f, fy + 2.2f, oz + (float) Math.sin(a) * 1.6f, 0.12f, 0.12f, 0.12f, 0f, 0.9f, 0.95f, 1.0f, true);
        }
    }

    private static void clockFace(List<Piece> p, float fy, float x, float z, float hand, float r, float g, float b) {
        add(p, "relativity clocks", true, x, fy + 1.0f, z, 0.5f, 2.0f, 0.5f, 0f, 0.4f, 0.42f, 0.5f, false);    // post
        add(p, "relativity clocks", true, x, fy + 2.4f, z, 3.0f, 0.3f, 3.0f, 0f, 0.12f, 0.13f, 0.17f, false);  // dial
        float hx = x + (float) Math.cos(hand) * 0.7f, hz = z + (float) Math.sin(hand) * 0.7f;
        add(p, "relativity clocks", false, hx, fy + 2.62f, hz, 1.4f, 0.08f, 0.1f, -hand, r, g, b, true);       // the hand
    }

    // exhibit 6 - cryostat ----------------------------------------------------------

    private static void cryostat(List<Piece> p, float fy, float time) {
        float oz = -22f;
        float[][] tier = {{3.2f, 1.00f, 0.82f, 0.30f}, {2.7f, 0.95f, 0.70f, 0.28f}, {2.2f, 0.88f, 0.58f, 0.28f},
                          {1.7f, 0.80f, 0.48f, 0.30f}, {1.2f, 0.72f, 0.40f, 0.30f}};
        for (int i = 0; i < tier.length; i++) {
            add(p, "cryostat", true, 0, fy + 6.2f - i * 1.1f, oz, tier[i][0], 0.5f, tier[i][0], 0f, tier[i][1], tier[i][2], tier[i][3], false);
            // qubit dots around each tier, pulsing out of phase
            int dots = 3 + i;
            for (int d = 0; d < dots; d++) {
                float a = d * 2f * (float) Math.PI / dots + i;
                float rr = tier[i][0] * 0.42f;
                float pulse = 0.55f + 0.45f * (float) Math.sin(time * 3.2f + d * 1.3f + i);
                add(p, "cryostat", false, (float) Math.cos(a) * rr, fy + 5.85f - i * 1.1f, oz + (float) Math.sin(a) * rr, 0.16f, 0.16f, 0.16f, 0f, 0.6f * pulse, pulse, 1.0f, pulse > 0.8f);
            }
        }
        // support rods and a frost base
        for (int i = 0; i < 4; i++) {
            float a = i * (float) Math.PI / 2f;
            add(p, "cryostat", false, (float) Math.cos(a) * 1.0f, fy + 3.9f, oz + (float) Math.sin(a) * 1.0f, 0.08f, 5.6f, 0.08f, 0f, 0.7f, 0.72f, 0.8f, false);
        }
        add(p, "cryostat", true, 0, fy + 0.5f, oz, 4.2f, 1.0f, 4.2f, 0f, 0.55f, 0.75f, 0.85f, false);
    }

    // ── drawing ───────────────────────────────────────────────────────────

    /** Draw the campus. {@code camX/camZ} choose the level of detail; far away nothing is drawn at all. */
    public static void render(Renderer r, float floorY, float time, float camX, float camZ) {
        float dx = camX - CX, dz = camZ - CZ;
        float d2 = dx * dx + dz * dz;
        if (d2 > FAR_DIST * FAR_DIST) return;
        boolean near = d2 <= NEAR_DIST * NEAR_DIST;
        for (Piece pc : build(floorY, time, near)) {
            Vector3f pos = new Vector3f(pc.x(), pc.y(), pc.z());
            Vector3f size = new Vector3f(pc.sx(), pc.sy(), pc.sz());
            if (pc.hex()) r.drawHexPrismColor(pos, size, pc.r(), pc.g(), pc.b());
            else r.drawCubeColorYaw(pos, size, pc.yaw(), pc.r(), pc.g(), pc.b());
        }
    }

    /** Beyond this distance from the centre the campus is skipped entirely (the beacons stay visible up to here). */
    static final float FAR_DIST = 130f;
}
