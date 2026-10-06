package com.mindpalace.render;

/**
 * MP-028 CPU half of the batch renderer: turns particles into camera-facing quads in one pre-allocated
 * interleaved vertex array (x,y,z, u,v, r,g,b,a = 9 floats per vertex, 4 vertices per quad).
 * Pure Java, no GL, zero allocation per frame, so the geometry is provable headless; ParticleBatch uploads it
 * and issues ONE draw call.
 */
public final class ParticleQuadBuffer {
    public static final int FLOATS_PER_VERTEX = 9;
    public static final int VERTS_PER_QUAD = 4;
    public static final int FLOATS_PER_QUAD = FLOATS_PER_VERTEX * VERTS_PER_QUAD;

    private final float[] data;
    private final int maxQuads;
    private int quads;
    private float rx, ry, rz, ux, uy, uz;

    public ParticleQuadBuffer(int maxQuads) {
        this.maxQuads = Math.max(0, maxQuads);
        this.data = new float[this.maxQuads * FLOATS_PER_QUAD];
    }

    public int maxQuads() { return maxQuads; }
    public int quadCount() { return quads; }
    public float[] data() { return data; }
    public int floatCount() { return quads * FLOATS_PER_QUAD; }

    /** Start a frame. (rx,ry,rz) and (ux,uy,uz) are the camera's right and up unit vectors in world space. */
    public void begin(float rightX, float rightY, float rightZ, float upX, float upY, float upZ) {
        quads = 0;
        rx = rightX; ry = rightY; rz = rightZ;
        ux = upX; uy = upY; uz = upZ;
    }

    /** Add one billboard of edge length `size` centred on (px,py,pz). @return false when the buffer is full. */
    public boolean add(float px, float py, float pz, float size, float r, float g, float b, float a) {
        if (quads >= maxQuads) return false;
        float h = size * 0.5f;
        float ax = rx * h, ay = ry * h, az = rz * h;          // half right
        float bx = ux * h, by = uy * h, bz = uz * h;          // half up
        int o = quads * FLOATS_PER_QUAD;
        o = vert(o, px - ax - bx, py - ay - by, pz - az - bz, 0f, 0f, r, g, b, a);
        o = vert(o, px + ax - bx, py + ay - by, pz + az - bz, 1f, 0f, r, g, b, a);
        o = vert(o, px + ax + bx, py + ay + by, pz + az + bz, 1f, 1f, r, g, b, a);
        vert(o, px - ax + bx, py - ay + by, pz - az + bz, 0f, 1f, r, g, b, a);
        quads++;
        return true;
    }

    /** Add every live particle of a pool, fading alpha with remaining life. @return quads added. */
    public int addPool(ParticlePool pool, float alphaScale) {
        int before = quads;
        int n = pool.count();
        for (int i = 0; i < n; i++) {
            if (!add(pool.x[i], pool.y[i], pool.z[i], pool.size[i], pool.r[i], pool.g[i], pool.b[i],
                    alphaScale * pool.lifeFraction(i))) break;
        }
        return quads - before;
    }

    private int vert(int o, float x, float y, float z, float u, float v, float r, float g, float b, float a) {
        data[o] = x; data[o + 1] = y; data[o + 2] = z; data[o + 3] = u; data[o + 4] = v;
        data[o + 5] = r; data[o + 6] = g; data[o + 7] = b; data[o + 8] = a;
        return o + FLOATS_PER_VERTEX;
    }
}
