package com.mindpalace.render;

import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * MP-028 GL half of the batch renderer: uploads a ParticleQuadBuffer once and draws every quad in ONE call.
 * Replaces one cube draw (two Vector3f allocations, ~6 uniform sets) per particle. Must be created and used on
 * the render thread with a current GL context. Soft round dots are computed in the fragment shader (no texture).
 */
public final class ParticleBatch {
    private final ParticleQuadBuffer quads;
    private final Shader shader;
    private final int vao, vbo, ebo;
    private final FloatBuffer upload;          // direct, allocated once
    private int errorChecks = 5;               // glGetError only for the first draws: it can stall the pipeline
    private long drawCalls, quadsDrawn;
    private boolean closed;

    public ParticleBatch(int maxQuads) {
        quads = new ParticleQuadBuffer(maxQuads);
        shader = new Shader("shaders/particle.vert", "shaders/particle.frag");
        int n = quads.maxQuads();
        upload = MemoryUtil.memAllocFloat(Math.max(1, n * ParticleQuadBuffer.FLOATS_PER_QUAD));

        vao = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(vao);

        vbo = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, (long) n * ParticleQuadBuffer.FLOATS_PER_QUAD * Float.BYTES, GL15.GL_STREAM_DRAW);

        int stride = ParticleQuadBuffer.FLOATS_PER_VERTEX * Float.BYTES;
        GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, stride, 0L);
        GL20.glEnableVertexAttribArray(0);
        GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, stride, 3L * Float.BYTES);
        GL20.glEnableVertexAttribArray(1);
        GL20.glVertexAttribPointer(2, 4, GL11.GL_FLOAT, false, stride, 5L * Float.BYTES);
        GL20.glEnableVertexAttribArray(2);

        // Static index buffer for the maximum quad count: two triangles per quad, built once.
        IntBuffer idx = MemoryUtil.memAllocInt(Math.max(1, n * 6));
        for (int q = 0; q < n; q++) {
            int v = q * 4;
            idx.put(v).put(v + 1).put(v + 2).put(v + 2).put(v + 3).put(v);
        }
        idx.flip();
        ebo = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, ebo);
        GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, idx, GL15.GL_STATIC_DRAW);
        MemoryUtil.memFree(idx);

        GL30.glBindVertexArray(0);
    }

    /** The CPU buffer: call begin(), add()/addPool(), then end(). */
    public ParticleQuadBuffer quads() { return quads; }

    /** Upload and draw everything added since quads().begin(). One draw call; restores GL state afterwards. */
    public void end(Matrix4f projection, Matrix4f view) {
        int count = quads.quadCount();
        if (closed || count == 0) return;

        boolean probe = errorChecks > 0;
        if (probe) {
            errorChecks--;
            // Errors left over from OTHER code (nothing else in the game calls glGetError) are reported separately,
            // so they are never blamed on the batch.
            for (int e = GL11.glGetError(), n = 0; e != GL11.GL_NO_ERROR && n < 8; e = GL11.glGetError(), n++) {
                System.err.println("[Particles] pre-existing GL error 0x" + Integer.toHexString(e) + " before the batch draw (not from the batch)");
            }
        }

        upload.clear();
        upload.put(quads.data(), 0, quads.floatCount());
        upload.flip();

        final int previousProgram = Shader.currentProgram();   // callers must not have to remember to rebind theirs
        shader.bind();
        shader.setUniform("projection", projection);
        shader.setUniform("view", view);
        if (probe) probe("shader+uniforms", count);

        GL30.glBindVertexArray(vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0L, upload);
        if (probe) probe("buffer upload", count);

        // Additive glow, tested against the depth buffer but never written to it, no face culling (billboards).
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
        GL11.glDepthMask(false);
        GL11.glDisable(GL11.GL_CULL_FACE);
        if (probe) probe("blend/depth/cull state", count);

        GL11.glDrawElements(GL11.GL_TRIANGLES, count * 6, GL11.GL_UNSIGNED_INT, 0L);
        if (probe) probe("glDrawElements", count);

        GL11.glEnable(GL11.GL_CULL_FACE);
        GL11.glDepthMask(true);
        GL11.glDisable(GL11.GL_BLEND);
        GL30.glBindVertexArray(0);
        Shader.restoreProgram(previousProgram);
        if (probe) probe("state restore", count);

        drawCalls++;
        quadsDrawn += count;
    }

    private void probe(String step, int count) {
        int err = GL11.glGetError();
        if (err != GL11.GL_NO_ERROR) {
            System.err.println("[Particles] GL error 0x" + Integer.toHexString(err) + " at step '" + step + "' (" + count + " quads)");
        }
    }

    public long drawCalls() { return drawCalls; }
    public long quadsDrawn() { return quadsDrawn; }

    /** Idempotent. */
    public void cleanup() {
        if (closed) return;
        closed = true;
        GL15.glDeleteBuffers(vbo);
        GL15.glDeleteBuffers(ebo);
        GL30.glDeleteVertexArrays(vao);
        shader.cleanup();
        MemoryUtil.memFree(upload);
    }
}
