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

    // Texture IDs — public so WorldBuilder can reference them
    public static final int TEX_WALL   = 0;
    public static final int TEX_FLOOR  = 1;
    public static final int TEX_CEILING = 2;
    public static final int TEX_DOOR   = 3;
    public static final int TEX_SHELF  = 4;
    public static final int TEX_BOOK   = 5;
    public static final int TEX_PLAQUE = 6;
    public static final int TEX_WHITE  = 7;
    private static final int TEX_COUNT = 8;

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
        textures[TEX_WALL]   = new Texture(0.45f, 0.32f, 0.22f);  // brown stone
        textures[TEX_FLOOR]  = new Texture(0.18f, 0.13f, 0.08f);  // dark wood
        textures[TEX_CEILING]= new Texture(0.15f, 0.15f, 0.18f);  // dark grey
        textures[TEX_DOOR]   = new Texture(0.30f, 0.18f, 0.10f);  // darker wood
        textures[TEX_SHELF]  = new Texture(0.35f, 0.22f, 0.14f);  // medium wood
        textures[TEX_BOOK]   = new Texture(0.15f, 0.40f, 0.25f);  // green (default)
        textures[TEX_PLAQUE] = new Texture(0.70f, 0.60f, 0.30f);  // brass/gold
        textures[TEX_WHITE]  = new Texture(1.0f, 1.0f, 1.0f);

        projectionMatrix = new Matrix4f();
    }

    public void beginFrame(Camera camera) {
        basicShader.bind();
        float aspect = (float) width / height;
        projectionMatrix = camera.getProjectionMatrix(aspect);
        basicShader.setUniform("projection", projectionMatrix);
        basicShader.setUniform("view", camera.getViewMatrix());
        basicShader.setUniform("lightPos", new Vector3f(0, 10, 0));
        basicShader.setUniform("lightColor", new Vector3f(1.0f, 0.95f, 0.8f));
        basicShader.setUniform("ambientStrength", 0.35f);
        basicShader.setUniform("viewPos", camera.getPosition());
    }

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
        for (Texture t : textures) if (t != null) t.cleanup();
    }
}
