package com.mindpalace.render;

import org.joml.Vector2f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * Bloom post-processing — makes neon signs, glowing door frames, teleporter
 * beams and crystals bleed light into their surroundings.
 *
 * Pipeline (all offscreen, then composited to the default framebuffer):
 *   1. scene  -> FBO color texture (the whole frame renders here)
 *   2. bright pass  -> keep only pixels above a luminance threshold
 *   3. gaussian blur (horizontal + vertical ping-pong)
 *   4. composite: scene + bloom * intensity
 *
 * The render loop calls begin() before drawing and end() after, so the UI
 * (neon sign text, HUD, menu) blooms too — those are the main light sources.
 */
public class BloomEffect {
    private int width, height;
    private int sceneFbo, sceneTex;
    private int blurFboA, blurTexA;
    private int blurFboB, blurTexB;
    private int quadVao, quadVbo, quadEbo;

    private Shader brightShader, blurShader, compositeShader;

    private float threshold = 0.6f;
    private float intensity = 0.7f;
    private boolean enabled = true;

    public BloomEffect(int width, int height) {
        this.width = width;
        this.height = height;
        buildQuad();
        buildShaders();
        buildTargets();
    }

    // ── Shaders ──

    private void buildShaders() {
        String fullscreenVert =
            "#version 330 core\n" +
            "layout(location=0) in vec2 aPos;\n" +
            "layout(location=1) in vec2 aUV;\n" +
            "out vec2 UV;\n" +
            "void main() { gl_Position = vec4(aPos, 0.0, 1.0); UV = aUV; }\n";

        String brightFrag =
            "#version 330 core\n" +
            "in vec2 UV;\n" +
            "out vec4 FragColor;\n" +
            "uniform sampler2D scene;\n" +
            "uniform float threshold;\n" +
            "void main() {\n" +
            "  vec3 c = texture(scene, UV).rgb;\n" +
            "  float l = dot(c, vec3(0.2126, 0.7152, 0.0722));\n" +
            "  float w = smoothstep(threshold, threshold + 0.3, l);\n" +
            "  FragColor = vec4(c * w, 1.0);\n" +
            "}\n";

        String blurFrag =
            "#version 330 core\n" +
            "in vec2 UV;\n" +
            "out vec4 FragColor;\n" +
            "uniform sampler2D image;\n" +
            "uniform vec2 direction;\n" +
            "void main() {\n" +
            "  vec2 off = direction;\n" +
            "  vec3 sum = texture(image, UV).rgb * 0.227027;\n" +
            "  sum += texture(image, UV + off * 1.384615).rgb * 0.316216;\n" +
            "  sum += texture(image, UV - off * 1.384615).rgb * 0.316216;\n" +
            "  sum += texture(image, UV + off * 3.230769).rgb * 0.070270;\n" +
            "  sum += texture(image, UV - off * 3.230769).rgb * 0.070270;\n" +
            "  FragColor = vec4(sum, 1.0);\n" +
            "}\n";

        String compositeFrag =
            "#version 330 core\n" +
            "in vec2 UV;\n" +
            "out vec4 FragColor;\n" +
            "uniform sampler2D scene;\n" +
            "uniform sampler2D bloom;\n" +
            "uniform float intensity;\n" +
            "void main() {\n" +
            "  vec3 c = texture(scene, UV).rgb;\n" +
            "  vec3 b = texture(bloom, UV).rgb;\n" +
            "  FragColor = vec4(c + b * intensity, 1.0);\n" +
            "}\n";

        brightShader = new Shader(fullscreenVert, brightFrag, true);
        blurShader = new Shader(fullscreenVert, blurFrag, true);
        compositeShader = new Shader(fullscreenVert, compositeFrag, true);
    }

    // ── Fullscreen quad ──

    private void buildQuad() {
        float[] verts = {
            -1, -1,  0, 0,
             1, -1,  1, 0,
             1,  1,  1, 1,
            -1,  1,  0, 1,
        };
        int[] indices = {0, 1, 2, 0, 2, 3};

        quadVao = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(quadVao);

        quadVbo = GL15.glGenBuffers();
        FloatBuffer vb = MemoryUtil.memAllocFloat(verts.length);
        vb.put(verts).flip();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, quadVbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, vb, GL15.GL_STATIC_DRAW);
        MemoryUtil.memFree(vb);

        quadEbo = GL15.glGenBuffers();
        IntBuffer ib = MemoryUtil.memAllocInt(indices.length);
        ib.put(indices).flip();
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, quadEbo);
        GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, ib, GL15.GL_STATIC_DRAW);
        MemoryUtil.memFree(ib);

        int stride = 4 * Float.BYTES;
        GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, stride, 0);
        GL20.glEnableVertexAttribArray(0);
        GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, stride, 2 * Float.BYTES);
        GL20.glEnableVertexAttribArray(1);

        GL30.glBindVertexArray(0);
    }

    // ── Offscreen targets ──

    private void buildTargets() {
        sceneTex = createTexture();
        sceneFbo = createFbo(sceneTex);

        blurTexA = createTexture();
        blurFboA = createFbo(blurTexA);
        blurTexB = createTexture();
        blurFboB = createFbo(blurTexB);
    }

    private int createTexture() {
        int tex = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width, height, 0,
            GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        return tex;
    }

    private int createFbo(int colorTex) {
        int fbo = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
            GL11.GL_TEXTURE_2D, colorTex, 0);
        int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            throw new RuntimeException("Bloom FBO incomplete: 0x" + Integer.toHexString(status));
        }
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        return fbo;
    }

    // ── Frame hooks ──

    /** Bind the scene FBO — everything drawn after this goes offscreen. */
    public void begin() {
        if (!enabled) return;
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, sceneFbo);
        GL11.glViewport(0, 0, width, height);
    }

    /** Composite scene + bloom to the default framebuffer. */
    public void end() {
        if (!enabled) return;
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        GL11.glViewport(0, 0, width, height);

        // 1. Bright pass: scene -> blurTexA
        renderPass(brightShader, sceneTex, blurFboA, blurTexA, null);

        // 2. Horizontal blur: blurTexA -> blurTexB
        renderPass(blurShader, blurTexA, blurFboB, blurTexB, new Vector2f(1.0f / width, 0f));

        // 3. Vertical blur: blurTexB -> blurTexA
        renderPass(blurShader, blurTexB, blurFboA, blurTexA, new Vector2f(0f, 1.0f / height));

        // 4. Composite: scene + blurTexA -> screen
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_CULL_FACE);
        compositeShader.bind();
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, sceneTex);
        compositeShader.setUniform("scene", 0);
        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, blurTexA);
        compositeShader.setUniform("bloom", 1);
        compositeShader.setUniform("intensity", intensity);
        drawQuad();
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glEnable(GL11.GL_CULL_FACE);
    }

    private void renderPass(Shader shader, int srcTex, int dstFbo, int dstTex, Vector2f dir) {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, dstFbo);
        GL11.glViewport(0, 0, width, height);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_CULL_FACE);
        shader.bind();
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, srcTex);
        if (shader == brightShader) {
            shader.setUniform("scene", 0);
            shader.setUniform("threshold", threshold);
        } else {
            shader.setUniform("image", 0);
            shader.setUniform("direction", dir);
        }
        drawQuad();
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glEnable(GL11.GL_CULL_FACE);
    }

    private void drawQuad() {
        GL30.glBindVertexArray(quadVao);
        GL11.glDrawElements(GL11.GL_TRIANGLES, 6, GL11.GL_UNSIGNED_INT, 0);
        GL30.glBindVertexArray(0);
    }

    // ── Tuning (hot-applied) ──

    public void setThreshold(float t) { threshold = t; }
    public void setIntensity(float i) { intensity = i; }
    public void setEnabled(boolean e) { enabled = e; }
    public float getThreshold() { return threshold; }
    public float getIntensity() { return intensity; }
    public boolean isEnabled() { return enabled; }

    public void resize(int w, int h) {
        width = w; height = h;
        cleanupTargets();
        buildTargets();
    }

    private void cleanupTargets() {
        GL11.glDeleteTextures(sceneTex);
        GL11.glDeleteTextures(blurTexA);
        GL11.glDeleteTextures(blurTexB);
        GL30.glDeleteFramebuffers(sceneFbo);
        GL30.glDeleteFramebuffers(blurFboA);
        GL30.glDeleteFramebuffers(blurFboB);
    }

    public void cleanup() {
        cleanupTargets();
        GL30.glDeleteVertexArrays(quadVao);
        GL15.glDeleteBuffers(quadVbo);
        GL15.glDeleteBuffers(quadEbo);
        brightShader.cleanup();
        blurShader.cleanup();
        compositeShader.cleanup();
    }
}
