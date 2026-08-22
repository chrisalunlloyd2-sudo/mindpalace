package com.mindpalace.render;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

/**
 * OpenGL renderer — shaders, cube mesh, texture atlas, lighting.
 * Public static texture IDs for WorldBuilder to reference.
 */
public class Renderer {
    private Shader basicShader;
    private Matrix4f projectionMatrix;
    private int width, height;

    // Live-tunable lighting (hot-applied by PatchManager without a restart)
    private float ambientStrength = 0.70f;
    private final Vector3f lightColor = new Vector3f(1.0f, 0.9f, 0.7f);
    private final Vector3f lightOffset = new Vector3f(0.0f, 3.0f, 0.0f);
    private final Vector3f tintColor = new Vector3f(1.0f, 1.0f, 1.0f); // per-room accent

    // Texture IDs — public so WorldBuilder can reference them
    public static final int TEX_WALL   = 0;
    public static final int TEX_FLOOR  = 1;
    public static final int TEX_CEILING = 2;
    public static final int TEX_DOOR   = 3;
    public static final int TEX_SHELF  = 4;
    public static final int TEX_BOOK   = 5;
    public static final int TEX_PLAQUE = 6;
    public static final int TEX_WHITE  = 7;
    public static final int TEX_CROSSHAIR = 8;
    public static final int TEX_BOOK_BLUE   = 9;
    public static final int TEX_BOOK_YELLOW = 10;
    public static final int TEX_BOOK_ORANGE = 11;
    public static final int TEX_BOOK_RED    = 12;
    public static final int TEX_BOOK_GREY   = 13;
    public static final int TEX_BOOK_WHITE  = 14;
    public static final int TEX_NEON_CYAN  = 15;
    public static final int TEX_NEON_PINK  = 16;
    public static final int TEX_NEON_GREEN = 17;
    public static final int TEX_NEON_AMBER = 18;
    public static final int TEX_HARDWOOD = 19;
    public static final int TEX_WALLPAPER = 20;
    public static final int TEX_METAL = 21;
    public static final int TEX_CONCRETE = 22;
    public static final int TEX_GRASS = 23;
    public static final int TEX_WATER = 24;
    public static final int TEX_WOOD = 25;
    private static final int TEX_COUNT = 26;

    private Texture[] textures = new Texture[TEX_COUNT];

    public Renderer(int width, int height) {
        this.width = width;
        this.height = height;

        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glEnable(GL11.GL_CULL_FACE);
        GL11.glCullFace(GL11.GL_BACK);
        GL11.glEnable(GL13.GL_MULTISAMPLE);
        GL11.glClearColor(0.02f, 0.02f, 0.05f, 1.0f);

        basicShader = new Shader("shaders/basic.vert", "shaders/basic.frag");

        // Create distinct solid-color textures for each surface
        textures[TEX_WALL]   = new Texture(0.55f, 0.40f, 0.28f);  // warm stone
        textures[TEX_FLOOR]  = new Texture(0.30f, 0.20f, 0.12f);  // rich wood
        textures[TEX_CEILING]= new Texture(0.22f, 0.22f, 0.28f);  // soft grey
        textures[TEX_DOOR]   = new Texture(0.40f, 0.25f, 0.14f);  // medium wood
        textures[TEX_SHELF]  = new Texture(0.45f, 0.30f, 0.18f);  // warm wood
        textures[TEX_BOOK]   = new Texture(0.15f, 0.40f, 0.25f);  // green (default)
        textures[TEX_PLAQUE] = new Texture(0.70f, 0.60f, 0.30f);  // brass/gold
        textures[TEX_WHITE]  = new Texture(1.0f, 1.0f, 1.0f);
        textures[TEX_CROSSHAIR] = new Texture(0.0f, 1.0f, 0.0f);
        textures[TEX_BOOK_BLUE]   = new Texture(0.15f, 0.25f, 0.55f);
        textures[TEX_BOOK_YELLOW] = new Texture(0.65f, 0.55f, 0.10f);
        textures[TEX_BOOK_ORANGE] = new Texture(0.70f, 0.35f, 0.10f);
        textures[TEX_BOOK_RED]    = new Texture(0.55f, 0.15f, 0.15f);
        textures[TEX_BOOK_GREY]   = new Texture(0.35f, 0.35f, 0.35f);
        textures[TEX_BOOK_WHITE]  = new Texture(0.85f, 0.82f, 0.75f);
        textures[TEX_NEON_CYAN]  = new Texture(0.0f, 0.9f, 1.0f);
        textures[TEX_NEON_PINK]  = new Texture(1.0f, 0.2f, 0.6f);
        textures[TEX_NEON_GREEN] = new Texture(0.1f, 1.0f, 0.3f);
        textures[TEX_NEON_AMBER] = new Texture(1.0f, 0.7f, 0.1f);
        textures[TEX_HARDWOOD] = new Texture(0.35f, 0.20f, 0.10f);
        textures[TEX_WALLPAPER] = new Texture(0.28f, 0.24f, 0.30f);
        textures[TEX_METAL] = new Texture(0.45f, 0.45f, 0.48f);
        textures[TEX_CONCRETE] = new Texture(0.35f, 0.33f, 0.30f);
        textures[TEX_GRASS] = new Texture(0.15f, 0.55f, 0.15f);
        textures[TEX_WATER] = new Texture(0.1f, 0.3f, 0.7f);
        textures[TEX_WOOD] = new Texture(0.4f, 0.25f, 0.12f);

        projectionMatrix = new Matrix4f();
    }

    public void beginFrame(Camera camera) {
        basicShader.bind();
        float aspect = (float) width / height;
        projectionMatrix = camera.getProjectionMatrix(aspect);
        basicShader.setUniform("projection", projectionMatrix);
        basicShader.setUniform("view", camera.getViewMatrix());
        // Headlamp: light follows the player so upper floors aren't pitch black
        // (the old fixed light at y=10 sat BELOW floors 1+ and left them dark).
        Vector3f camPos = camera.getPosition();
        basicShader.setUniform("lightPos", new Vector3f(camPos).add(lightOffset));
        basicShader.setUniform("lightColor", lightColor);
        basicShader.setUniform("ambientStrength", ambientStrength);
        basicShader.setUniform("viewPos", camPos);
        basicShader.setUniform("tintColor", tintColor);
    }

    // ── Live graphics tuning (hot-applied by PatchManager) ──
    public void setAmbient(float a) { ambientStrength = a; }
    public void setLightColor(float r, float g, float b) { lightColor.set(r, g, b); }
    public void setLightOffset(float x, float y, float z) { lightOffset.set(x, y, z); }
    public float getAmbient() { return ambientStrength; }
    /** Per-room language accent tint (1,1,1 = neutral). */
    public void setTint(float r, float g, float b) { tintColor.set(r, g, b); }

    public void drawMesh(Mesh mesh, Matrix4f model, int texId) {
        basicShader.setUniform("model", model);
        Texture tex = textures[texId];
        if (tex != null) {
            tex.bind(0);
            basicShader.setUniform("useTexture", 1);
        } else {
            textures[TEX_WHITE].bind(0);
            basicShader.setUniform("useTexture", 0);
        }
        mesh.render();
    }

    /** Draw a cube with a specific texture type. */
    public void drawCube(Vector3f position, Vector3f size, int texId) {
        Matrix4f model = new Matrix4f().translate(position).scale(size);
        drawMesh(getCubeMesh(), model, texId);
    }

    /** Draw a cube rotated around the Y axis (yaw in radians) — for oriented limbs. */
    public void drawCubeYaw(Vector3f position, Vector3f size, float yaw, int texId) {
        Matrix4f model = new Matrix4f().translate(position).rotateY(yaw).scale(size);
        drawMesh(getCubeMesh(), model, texId);
    }

    // Cache of solid-color textures keyed by quantized RGB, so gradient bands
    // (cosine-interpolated sky, water shimmer) can paint arbitrary colors
    // without a texture per shade.
    private final java.util.Map<Integer, Texture> colorCache = new java.util.HashMap<>();

    /** Draw a cube with an arbitrary RGB color (cached 1x1 texture). */
    public void drawCubeColor(Vector3f position, Vector3f size, float r, float g, float b) {
        int key = ((int)(r*255) << 16) | ((int)(g*255) << 8) | (int)(b*255);
        Texture tex = colorCache.get(key);
        if (tex == null) { tex = new Texture(r, g, b); colorCache.put(key, tex); }
        Matrix4f model = new Matrix4f().translate(position).scale(size);
        basicShader.setUniform("model", model);
        tex.bind(0);
        basicShader.setUniform("useTexture", 1);
        getCubeMesh().render();
    }

    /**
     * Draw a flat textured quad (an image poster) facing a given yaw.
     * Binds the supplied Texture directly (not the solid-color atlas), so real
     * repo images render on the wall posters. yaw=0 faces +Z, yaw=π faces -Z.
     */
    public void drawImageQuad(Texture tex, Vector3f center, float w, float h, float yaw) {
        if (tex == null) return;
        basicShader.setUniform("model", new Matrix4f()
            .translate(center).rotateY(yaw).scale(w, h, 1f));
        tex.bind(0);
        basicShader.setUniform("useTexture", 1);
        getQuadMesh().render();
    }

    /** Laser aim dot — small bright cube at fixed distance in front of camera. */
    public void drawLaserDot(Camera camera) {
        Vector3f pos = new Vector3f(camera.getPosition())
            .add(new Vector3f(camera.getFront()).mul(2.0f));
        drawCube(pos, new Vector3f(0.03f, 0.03f, 0.03f), TEX_WHITE);
    }

    private Mesh cubeMesh;
    private Mesh getCubeMesh() {
        if (cubeMesh == null) cubeMesh = createCubeMesh();
        return cubeMesh;
    }

    private Mesh quadMesh;
    private Mesh getQuadMesh() {
        if (quadMesh == null) quadMesh = createQuadMesh();
        return quadMesh;
    }

    /** A flat quad in the XY plane (normal +Z), unit size, centered at origin. */
    private Mesh createQuadMesh() {
        float s = 0.5f;
        float[] verts = {
            -s,-s, 0,  0,0,1,  0,1,   s,-s, 0,  0,0,1,  1,1,   s, s, 0,  0,0,1,  1,0,  -s, s, 0,  0,0,1,  0,0,
        };
        int[] indices = {0,1,2, 0,2,3};
        return new Mesh(verts, indices);
    }

    private Mesh createCubeMesh() {
        float s = 0.5f;
        float[] verts = {
            // Front  (+Z)
            -s,-s, s,  0,0,1,  0,0,   s,-s, s,  0,0,1,  1,0,   s, s, s,  0,0,1,  1,1,  -s, s, s,  0,0,1,  0,1,
            // Back   (-Z)
             s,-s,-s,  0,0,-1, 0,0,  -s,-s,-s,  0,0,-1, 1,0,  -s, s,-s,  0,0,-1, 1,1,   s, s,-s,  0,0,-1, 0,1,
            // Right  (+X)
             s,-s, s,  1,0,0,  0,0,   s,-s,-s,  1,0,0,  1,0,   s, s,-s,  1,0,0,  1,1,   s, s, s,  1,0,0,  0,1,
            // Left   (-X)
            -s,-s,-s, -1,0,0,  0,0,  -s,-s, s, -1,0,0,  1,0,  -s, s, s, -1,0,0,  1,1,  -s, s,-s, -1,0,0,  0,1,
            // Top    (+Y)
            -s, s, s,  0,1,0,  0,0,   s, s, s,  0,1,0,  1,0,   s, s,-s,  0,1,0,  1,1,  -s, s,-s,  0,1,0,  0,1,
            // Bottom (-Y)
            -s,-s,-s,  0,-1,0, 0,0,   s,-s,-s,  0,-1,0, 1,0,   s,-s, s,  0,-1,0, 1,1,  -s,-s, s,  0,-1,0, 0,1,
        };
        int[] indices = {
            0,1,2, 0,2,3, 4,5,6, 4,6,7, 8,9,10, 8,10,11,
            12,13,14, 12,14,15, 16,17,18, 16,18,19, 20,21,22, 20,22,23,
        };
        return new Mesh(verts, indices);
    }

    public void resize(int w, int h) {
        width = w; height = h;
        GL11.glViewport(0, 0, w, h);
    }

    public void cleanup() {
        basicShader.cleanup();
        if (cubeMesh != null) cubeMesh.cleanup();
        if (quadMesh != null) quadMesh.cleanup();
        for (Texture t : textures) if (t != null) t.cleanup();
    }
}
