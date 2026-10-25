package com.mindpalace.deploy;

import com.mindpalace.render.Renderer;
import org.joml.Vector3f;
import java.util.*;

/**
 * Lightweight particle animation system for deployment effects.
 * Particles are billboarded colored dots rendered in 3D.
 */
public class AnimationSystem {
    // t_b2e9bc3f gate fix: LiveUpdate's poller thread mutates these lists
    // (startConstructionAnimation / startGlowPulse) while the engine loop
    // iterates them in update()/render() — that race crashed the e2e tour
    // with ConcurrentModificationException at AnimationSystem.java:181
    // (observed 2026-10-01, 7 live-added rooms colliding with render).
    // Doctrine (see memory / repo history): synchronized list + snapshot at
    // EVERY iterate site. Mutators and update() take `this`; render() works
    // from lock-scoped snapshots.
    private final List<Particle> particles =
        Collections.synchronizedList(new ArrayList<>());
    private final List<BuildBlock> buildBlocks =
        Collections.synchronizedList(new ArrayList<>());
    private final Random rand = new Random();
    private boolean active;
    private boolean swirlMode; // MP-009 (#155): one-shot transporter burst — no refill, drain-deactivates
    private Vector3f source;
    private float elapsed;
    private static final int MAX_PARTICLES = 200;

    /** A block in a "construction" animation — rises from floor and assembles. */
    public static class BuildBlock {
        Vector3f target;   // final resting position
        Vector3f pos;      // current position (starts below floor)
        Vector3f size;
        int texId;
        float delay;       // stagger delay before this block starts rising
        float riseTime;    // how long it takes to rise into place

        BuildBlock(Vector3f target, Vector3f size, int texId, float delay) {
            this.target = new Vector3f(target);
            this.size = new Vector3f(size);
            this.texId = texId;
            this.delay = delay;
            this.riseTime = 0.6f + (float) Math.random() * 0.6f;
            this.pos = new Vector3f(target.x, target.y - 4f, target.z); // start below
        }
    }

    public static class Particle {
        Vector3f pos, vel;
        float life, maxLife;
        Vector3f color;
        float size;

        Particle(Vector3f pos, Vector3f vel, float life, Vector3f color, float size) {
            this.pos = new Vector3f(pos);
            this.vel = new Vector3f(vel);
            this.life = life;
            this.maxLife = life;
            this.color = new Vector3f(color);
            this.size = size;
        }
    }

    /** Start a deploy animation at a world position. Takes `this` — same lock
     *  the engine loop's update()/render() hold while touching the lists. */
    public synchronized void startDeployAnimation(Vector3f worldPos) {
        active = true;
        swirlMode = false; // MP-009: a real deploy cancels any leftover swirl branch
        source = new Vector3f(worldPos);
        elapsed = 0;
        particles.clear();
        buildBlocks.clear();

        // Burst of particles
        for (int i = 0; i < 80; i++) {
            Vector3f vel = new Vector3f(
                (rand.nextFloat() - 0.5f) * 3f,
                rand.nextFloat() * 4f + 1f,
                (rand.nextFloat() - 0.5f) * 3f
            );
            Vector3f color = rand.nextFloat() < 0.5f
                ? new Vector3f(0.2f, 0.8f, 1.0f)  // cyan
                : new Vector3f(0.2f, 1.0f, 0.4f);  // green
            particles.add(new Particle(
                new Vector3f(source),
                vel,
                rand.nextFloat() * 2f + 1f,
                color,
                rand.nextFloat() * 0.08f + 0.03f
            ));
        }
    }

    /**
     * Start a "construction" animation — blocks rise from the floor and assemble
     * into a structure (GTA Vice City loading style). Used for live code updates.
     */
    public synchronized void startConstructionAnimation(Vector3f center, List<Vector3f> blockTargets,
                                           List<Vector3f> blockSizes, List<Integer> texIds) {
        active = true;
        swirlMode = false;
        source = new Vector3f(center);
        elapsed = 0;
        particles.clear();
        buildBlocks.clear();

        float delay = 0f;
        for (int i = 0; i < blockTargets.size(); i++) {
            buildBlocks.add(new BuildBlock(
                blockTargets.get(i), blockSizes.get(i), texIds.get(i), delay));
            delay += 0.05f; // stagger each block
        }
    }

    /** Start a glow pulse on a neon sign. Called from the LiveUpdate poller
     *  thread; synchronized against the engine loop like the other mutators. */
    public synchronized void startGlowPulse(Vector3f signPos) {
        swirlMode = false; // MP-009: glow pulses follow the deploy update path
        for (int i = 0; i < 30; i++) {
            Vector3f vel = new Vector3f(
                (rand.nextFloat() - 0.5f) * 0.5f,
                (rand.nextFloat() - 0.5f) * 0.5f,
                (rand.nextFloat() - 0.5f) * 0.5f
            );
            particles.add(new Particle(
                new Vector3f(signPos),
                vel,
                rand.nextFloat() * 1.5f + 0.5f,
                new Vector3f(1f, 0.8f, 0.2f), // amber glow
                rand.nextFloat() * 0.12f + 0.06f
            ));
        }
    }

    /**
     * MP-009 (#155): transporter effect — a short rising particle swirl and
     * glow fired as the player crosses a room threshold (door enter/exit).
     * One-shot burst (no continuous refill), accent = the room district's
     * door color, complement = its portal pad theme. Budget: 120 particles,
     * under this system's 200 cap so the burst can never crowd out a
     * concurrent deploy/construction animation (layered, not clobbered).
     * Deactivates itself once drained (swirlMode), unlike the 4s deploy
     * animations, so brief crossings yield brief effects.
     */
    public synchronized void startTransporterSwirl(Vector3f at, Vector3f accent, Vector3f complement) {
        if (at == null || accent == null || complement == null) return;
        active = true;
        swirlMode = true;
        source = new Vector3f(at);
        elapsed = 0;
        System.out.println("[SWIRL] threshold burst at " + at + " accent=" + accent + " complement=" + complement);
        // Layer on top of whatever is already airborne; only a leftover
        // swirl's residue is reset (the 200-cap holds the total budget).
        if (swirlMode && particles.size() > MAX_PARTICLES - 120) particles.clear();
        Random r = rand;
        for (int i = 0; i < 120; i++) {
            // Swirl: particles born on a small ring, tangential velocity +
            // inward pull + gentle rise — reads as a vortex column at the
            // threshold instead of a random burst.
            float ang = r.nextFloat() * (float) (Math.PI * 2);
            float rad = 0.3f + r.nextFloat() * 0.7f;
            float px = at.x + (float) Math.cos(ang) * rad;
            float py = at.y + r.nextFloat() * 1.6f;
            float pz = at.z + (float) Math.sin(ang) * rad;
            float swirlX = (float) -Math.sin(ang) * (1.2f + r.nextFloat());
            float swirlZ = (float)  Math.cos(ang) * (1.2f + r.nextFloat());
            float pull = 2.2f + r.nextFloat();
            Vector3f vel = new Vector3f(
                swirlX - (float) Math.cos(ang) * pull,
                0.6f + r.nextFloat() * 1.2f,
                swirlZ - (float) Math.sin(ang) * pull);
            Vector3f color = new Vector3f(
                r.nextFloat() < 0.55f ? accent : complement);
            color.mul(0.75f + r.nextFloat() * 0.25f); // brightness variance
            particles.add(new Particle(
                new Vector3f(px, py, pz),
                vel,
                0.5f + r.nextFloat() * 0.7f,
                color,
                r.nextFloat() * 0.12f + 0.08f)); // 0.08-0.20m — deploy-glow size band (0.06-0.18m proven visible at 2.5m framing)
        }
    }

    /** Update all particles. Engine loop thread only — but mutations are
     *  also serialized against the LiveUpdate poller thread via `this`. */
    public void update(float dt) {
        if (!active) return;
        synchronized (this) {
        elapsed += dt;

        if (swirlMode) { // MP-009: pure drain loop — no refill, no blocks
            Iterator<Particle> it = particles.iterator();
            while (it.hasNext()) {
                Particle p = it.next();
                p.life -= dt;
                if (p.life <= 0) { it.remove(); continue; }
                p.pos.add(p.vel.x * dt, p.vel.y * dt, p.vel.z * dt);
                // Swirl maintenance: re-impart tangential force around the
                // source while pulling inward, so the column stays a column.
                float dx = p.pos.x - source.x, dz = p.pos.z - source.z;
                float d2 = dx * dx + dz * dz;
                if (d2 > 1e-4f) {
                    float d = (float) Math.sqrt(d2);
                    p.vel.x += (-dz / d) * 3.5f * dt - (dx / d) * 1.6f * dt;
                    p.vel.z += ( dx / d) * 3.5f * dt - (dz / d) * 1.6f * dt;
                }
                p.vel.y += 0.9f * dt; // gentle rise
                p.size *= 0.995f;
            }
            if (particles.isEmpty()) { active = false; swirlMode = false; }
            return;
        }

        // Update construction blocks
        Iterator<BuildBlock> bit = buildBlocks.iterator();
        while (bit.hasNext()) {
            BuildBlock b = bit.next();
            if (elapsed < b.delay) continue; // not this block's turn yet
            float t = (elapsed - b.delay) / b.riseTime;
            if (t >= 1f) {
                b.pos.set(b.target);
                bit.remove();
                continue;
            }
            // Ease-out rise
            float e = 1f - (1f - t) * (1f - t);
            b.pos.y = b.target.y - 4f + (4f * e);
        }

        Iterator<Particle> it = particles.iterator();
        while (it.hasNext()) {
            Particle p = it.next();
            p.life -= dt;
            if (p.life <= 0) {
                it.remove();
                continue;
            }
            p.pos.add(p.vel.x * dt, p.vel.y * dt, p.vel.z * dt);
            p.vel.y += 0.5f * dt; // slight upward drift
            p.size *= 0.995f;      // shrink
        }

        // Continuous emission while active
        if (elapsed < 3f && particles.size() < MAX_PARTICLES) {
            for (int i = 0; i < 5; i++) {
                Vector3f vel = new Vector3f(
                    (rand.nextFloat() - 0.5f) * 2f,
                    rand.nextFloat() * 3f + 0.5f,
                    (rand.nextFloat() - 0.5f) * 2f
                );
                particles.add(new Particle(
                    new Vector3f(source),
                    vel,
                    rand.nextFloat() * 1.5f + 0.5f,
                    new Vector3f(0.3f, 0.9f, 1f),
                    rand.nextFloat() * 0.06f + 0.02f
                ));
            }
        }

        if (elapsed > 4f && particles.isEmpty() && buildBlocks.isEmpty()) {
            active = false;
        }
        } // synchronized (this)
    }

    /** Render all particles as colored cubes. Runs on the render thread while
     *  the LiveUpdate poller may mutate the lists — iterate lock-scoped
     *  snapshots, never the live lists (CME at the old line 181). */
    public void render(Renderer renderer) {
        final List<BuildBlock> blocksSnap;
        final List<Particle> partsSnap;
        synchronized (this) {
            blocksSnap = new ArrayList<>(buildBlocks);
            partsSnap = new ArrayList<>(particles);
        }
        // Construction blocks first (they're the "building" effect)
        // t_mp009-renderbind probe + fix: drawMesh() sets uniforms on
        // Shader.currentProgram() and never binds its own program
        // (Renderer.drawMesh :231). Any earlier draw that leaves a foreign
        // shader bound (e.g. renderInternalLeft never restores — text path
        // leak) makes uniform sets land on the wrong program
        // (GL_INVALID_OPERATION, silently ignored) and the cubes draw with
        // the previous program's matrices — invisible. Bind explicitly and
        // log the pre-bind program on burst frames as evidence.
        boolean hasDraws = !blocksSnap.isEmpty() || !partsSnap.isEmpty();
        if (hasDraws) {
            System.out.println("[SWIRL-RENDER] n=" + partsSnap.size() + " active=" + active
                + " preBindProg=" + com.mindpalace.render.Shader.currentProgram());
        }
        renderer.bindBasicShader();
        for (BuildBlock b : blocksSnap) {
            renderer.drawCube(b.pos, b.size, b.texId);
        }
        for (Particle p : partsSnap) {
            float alpha = p.life / p.maxLife;
            Vector3f faded = new Vector3f(
                p.color.x * alpha,
                p.color.y * alpha,
                p.color.z * alpha
            );
            // Use TEX_WHITE with color modulation — we'll use a simple approach:
            // draw small cubes with the particle color
            renderer.drawCube(p.pos, new Vector3f(p.size, p.size, p.size),
                getClosestTexture(faded));
        }
    }

    private int getClosestTexture(Vector3f color) {
        // Map to nearest solid-color texture
        if (color.x > 0.5f && color.y > 0.5f) return Renderer.TEX_NEON_AMBER;
        if (color.y > 0.5f) return Renderer.TEX_NEON_GREEN;
        if (color.x > 0.5f && color.z > 0.5f) return Renderer.TEX_NEON_CYAN;
        if (color.x > 0.5f) return Renderer.TEX_NEON_PINK;
        return Renderer.TEX_WHITE;
    }

    public boolean isActive() { return active; }
    public Vector3f getSource() { return source; }
    public float getElapsed() { return elapsed; }
}
