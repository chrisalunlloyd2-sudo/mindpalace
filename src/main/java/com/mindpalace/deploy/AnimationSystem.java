package com.mindpalace.deploy;

import com.mindpalace.render.Renderer;
import org.joml.Vector3f;
import java.util.*;

/**
 * Lightweight particle animation system for deployment effects.
 * Particles are billboarded colored dots rendered in 3D.
 */
public class AnimationSystem {
    private final List<Particle> particles = new ArrayList<>();
    private final List<BuildBlock> buildBlocks = new ArrayList<>();
    private final Random rand = new Random();
    private boolean active;
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

    /** Start a deploy animation at a world position. */
    public void startDeployAnimation(Vector3f worldPos) {
        active = true;
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
    public void startConstructionAnimation(Vector3f center, List<Vector3f> blockTargets,
                                           List<Vector3f> blockSizes, List<Integer> texIds) {
        active = true;
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

    /** Start a glow pulse on a neon sign. */
    public void startGlowPulse(Vector3f signPos) {
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

    /** Update all particles. */
    public void update(float dt) {
        if (!active) return;
        elapsed += dt;

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
    }

    /** Render all particles as colored cubes. */
    public void render(Renderer renderer) {
        // Construction blocks first (they're the "building" effect)
        for (BuildBlock b : buildBlocks) {
            renderer.drawCube(b.pos, b.size, b.texId);
        }
        for (Particle p : particles) {
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
