package com.mindpalace.render;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/**
 * Main OpenGL renderer — manages shaders, draws meshes, handles lighting.
 */
public class Renderer {
    private Shader basicShader;
    private Matrix4f projectionMatrix;
    private int width, height;

    // Default textures
    private Texture whiteTexture;
    private Texture wallTexture;
    private Texture floorTexture;
    private Texture doorTexture;

    public Renderer(int width, int height) {
        this.width = width;
        this.height = height;

        // OpenGL state
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glEnable(GL11.GL_CULL_FACE);
        GL11.glCullFace(GL11.GL_BACK);
        GL11.glEnable(org.lwjgl.opengl.GL13.GL_MULTISAMPLE);
        GL11.glClearColor(0.05f, 0.05f, 0.1f, 1.0f);

        // Load shaders
        basicShader = new Shader("shaders/basic.vert", "shaders/basic.frag");

        // Create default textures
        whiteTexture = new Texture(1, 1, 1);
        wallTexture = new Texture(0.4f, 0.3f, 0.25f);   // brown stone
        floorTexture = new Texture(0.2f, 0.15f, 0.1f);   // dark wood
        doorTexture = new Texture(0.35f, 0.2f, 0.1f);    // darker wood

        projectionMatrix = new Matrix4f();
    }

    public void beginFrame(Camera camera) {
        basicShader.bind();

        // Projection
        float aspect = (float) width / height;
        projectionMatrix = camera.getProjectionMatrix(aspect);
        basicShader.setUniform("projection", projectionMatrix);

        // View
        basicShader.setUniform("view", camera.getViewMatrix());

        // Lighting
        basicShader.setUniform("lightPos", new Vector3f(0, 10, 0));
        basicShader.setUniform("lightColor", new Vector3f(1.0f, 0.95f, 0.8f));
        basicShader.setUniform("ambientStrength", 0.3f);
        basicShader.setUniform("viewPos", camera.getPosition());
    }

    /**
     * Draw a mesh at a given world position with a model matrix.
     */
    public void drawMesh(Mesh mesh, Matrix4f model, Texture texture) {
        basicShader.setUniform("model", model);

        if (texture != null) {
            texture.bind(0);
            basicShader.setUniform("useTexture", 1);
        } else {
            whiteTexture.bind(0);
            basicShader.setUniform("useTexture", 0);
        }

        mesh.render();
    }

    /**
     * Draw a colored cube at position with size.
     */
    public void drawCube(Vector3f position, Vector3f size, Texture texture) {
        Matrix4f model = new Matrix4f()
            .translate(position)
            .scale(size);
        drawMesh(getCubeMesh(), model, texture);
    }

    /**
     * Draw a wall segment (thin box).
     */
    public void drawWall(float x1, float y1, float z1, float x2, float y2, float z2, float thickness) {
        float cx = (x1 + x2) / 2;
        float cy = (y1 + y2) / 2;
        float cz = (z1 + z2) / 2;
        float sx = Math.abs(x2 - x1) + thickness;
        float sy = Math.abs(y2 - y1);
        float sz = Math.abs(z2 - z1) + thickness;

        if (sx < thickness * 2) sx = thickness;
        if (sz < thickness * 2) sz = thickness;

        drawCube(new Vector3f(cx, cy, cz), new Vector3f(sx, sy, sz), wallTexture);
    }

    // Lazy cube mesh
    private Mesh cubeMesh;
    private Mesh getCubeMesh() {
        if (cubeMesh == null) {
            cubeMesh = createCubeMesh();
        }
        return cubeMesh;
    }

    private Mesh createCubeMesh() {
        // 8 vertices, each: pos(3) + normal(3) + tex(2) = 8 floats
        // 36 indices (6 faces * 2 triangles * 3 vertices)
        float s = 0.5f;
        float[] verts = {
            // Front face
            -s, -s,  s,  0, 0, 1,  0, 0,
             s, -s,  s,  0, 0, 1,  1, 0,
             s,  s,  s,  0, 0, 1,  1, 1,
            -s,  s,  s,  0, 0, 1,  0, 1,
            // Back face
             s, -s, -s,  0, 0,-1,  0, 0,
            -s, -s, -s,  0, 0,-1,  1, 0,
            -s,  s, -s,  0, 0,-1,  1, 1,
             s,  s, -s,  0, 0,-1,  0, 1,
            // Right face
             s, -s,  s,  1, 0, 0,  0, 0,
             s, -s, -s,  1, 0, 0,  1, 0,
             s,  s, -s,  1, 0, 0,  1, 1,
             s,  s,  s,  1, 0, 0,  0, 1,
            // Left face
            -s, -s, -s, -1, 0, 0,  0, 0,
            -s, -s,  s, -1, 0, 0,  1, 0,
            -s,  s,  s, -1, 0, 0,  1, 1,
            -s,  s, -s, -1, 0, 0,  0, 1,
            // Top face
            -s,  s,  s,  0, 1, 0,  0, 0,
             s,  s,  s,  0, 1, 0,  1, 0,
             s,  s, -s,  0, 1, 0,  1, 1,
            -s,  s, -s,  0, 1, 0,  0, 1,
            // Bottom face
            -s, -s, -s,  0,-1, 0,  0, 0,
             s, -s, -s,  0,-1, 0,  1, 0,
             s, -s,  s,  0,-1, 0,  1, 1,
            -s, -s,  s,  0,-1, 0,  0, 1,
        };

        int[] indices = {
            0,1,2, 0,2,3,       // front
            4,5,6, 4,6,7,       // back
            8,9,10, 8,10,11,    // right
            12,13,14, 12,14,15, // left
            16,17,18, 16,18,19, // top
            20,21,22, 20,22,23, // bottom
        };

        return new Mesh(verts, indices);
    }

    public void resize(int width, int height) {
        this.width = width;
        this.height = height;
        GL11.glViewport(0, 0, width, height);
    }

    public void cleanup() {
        basicShader.cleanup();
        if (cubeMesh != null) cubeMesh.cleanup();
        whiteTexture.cleanup();
        wallTexture.cleanup();
        floorTexture.cleanup();
        doorTexture.cleanup();
    }
}
