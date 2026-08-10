package com.mindpalace.render;

import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * GPU mesh — VAO + VBO + EBO.
 * Holds vertex data (position, normal, texcoord) and indices.
 */
public class Mesh {
    private int vao;
    private int vbo;
    private int ebo;
    private int vertexCount;
    private int indexCount;

    public Mesh(float[] vertices, int[] indices) {
        this.vertexCount = vertices.length / 8; // 3 pos + 3 normal + 2 tex
        this.indexCount = indices.length;

        vao = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(vao);

        // VBO
        vbo = GL30.glGenBuffers();
        FloatBuffer vertBuffer = MemoryUtil.memAllocFloat(vertices.length);
        vertBuffer.put(vertices).flip();
        GL30.glBindBuffer(GL30.GL_ARRAY_BUFFER, vbo);
        GL30.glBufferData(GL30.GL_ARRAY_BUFFER, vertBuffer, GL30.GL_STATIC_DRAW);
        MemoryUtil.memFree(vertBuffer);

        // EBO
        ebo = GL30.glGenBuffers();
        IntBuffer idxBuffer = MemoryUtil.memAllocInt(indices.length);
        idxBuffer.put(indices).flip();
        GL30.glBindBuffer(GL30.GL_ELEMENT_ARRAY_BUFFER, ebo);
        GL30.glBufferData(GL30.GL_ELEMENT_ARRAY_BUFFER, idxBuffer, GL30.GL_STATIC_DRAW);
        MemoryUtil.memFree(idxBuffer);

        // Vertex attributes: position (3), normal (3), texcoord (2) = 8 floats = 32 bytes stride
        int stride = 8 * Float.BYTES;

        // Position
        GL30.glVertexAttribPointer(0, 3, GL30.GL_FLOAT, false, stride, 0);
        GL30.glEnableVertexAttribArray(0);

        // Normal
        GL30.glVertexAttribPointer(1, 3, GL30.GL_FLOAT, false, stride, 3 * Float.BYTES);
        GL30.glEnableVertexAttribArray(1);

        // TexCoord
        GL30.glVertexAttribPointer(2, 2, GL30.GL_FLOAT, false, stride, 6 * Float.BYTES);
        GL30.glEnableVertexAttribArray(2);

        GL30.glBindVertexArray(0);
    }

    public void bind() {
        GL30.glBindVertexArray(vao);
    }

    public void unbind() {
        GL30.glBindVertexArray(0);
    }

    public void render() {
        bind();
        GL30.glDrawElements(GL30.GL_TRIANGLES, indexCount, GL30.GL_UNSIGNED_INT, 0);
        unbind();
    }

    public void cleanup() {
        GL30.glDeleteVertexArrays(vao);
        GL30.glDeleteBuffers(vbo);
        GL30.glDeleteBuffers(ebo);
    }

    public int getIndexCount() { return indexCount; }
}
