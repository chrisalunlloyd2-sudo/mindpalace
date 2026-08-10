package com.mindpalace.render;

import org.lwjgl.opengl.GL30;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

/**
 * OpenGL texture loaded via STB.
 */
public class Texture {
    private int id;
    private int width, height;

    public Texture(String path) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer w = stack.mallocInt(1);
            IntBuffer h = stack.mallocInt(1);
            IntBuffer comp = stack.mallocInt(1);

            STBImage.stbi_set_flip_vertically_on_load(true);
            ByteBuffer data = STBImage.stbi_load(path, w, h, comp, 4); // force RGBA

            if (data == null) {
                throw new RuntimeException("Failed to load texture: " + path + " — " + STBImage.stbi_failure_reason());
            }

            width = w.get(0);
            height = h.get(0);

            id = GL30.glGenTextures();
            GL30.glBindTexture(GL30.GL_TEXTURE_2D, id);

            GL30.glTexParameteri(GL30.GL_TEXTURE_2D, GL30.GL_TEXTURE_WRAP_S, GL30.GL_REPEAT);
            GL30.glTexParameteri(GL30.GL_TEXTURE_2D, GL30.GL_TEXTURE_WRAP_T, GL30.GL_REPEAT);
            GL30.glTexParameteri(GL30.GL_TEXTURE_2D, GL30.GL_TEXTURE_MIN_FILTER, GL30.GL_LINEAR_MIPMAP_LINEAR);
            GL30.glTexParameteri(GL30.GL_TEXTURE_2D, GL30.GL_TEXTURE_MAG_FILTER, GL30.GL_LINEAR);

            GL30.glTexImage2D(GL30.GL_TEXTURE_2D, 0, GL30.GL_RGBA, width, height, 0,
                GL30.GL_RGBA, GL30.GL_UNSIGNED_BYTE, data);
            GL30.glGenerateMipmap(GL30.GL_TEXTURE_2D);

            STBImage.stbi_image_free(data);
        }
    }

    /**
     * Create a solid-color 1x1 texture (for untextured geometry).
     */
    public Texture(float r, float g, float b) {
        id = GL30.glGenTextures();
        GL30.glBindTexture(GL30.GL_TEXTURE_2D, id);

        ByteBuffer data = MemoryUtil.memAlloc(4);
        data.put((byte) (r * 255));
        data.put((byte) (g * 255));
        data.put((byte) (b * 255));
        data.put((byte) 255);
        data.flip();

        GL30.glTexParameteri(GL30.GL_TEXTURE_2D, GL30.GL_TEXTURE_WRAP_S, GL30.GL_REPEAT);
        GL30.glTexParameteri(GL30.GL_TEXTURE_2D, GL30.GL_TEXTURE_WRAP_T, GL30.GL_REPEAT);
        GL30.glTexParameteri(GL30.GL_TEXTURE_2D, GL30.GL_TEXTURE_MIN_FILTER, GL30.GL_NEAREST);
        GL30.glTexParameteri(GL30.GL_TEXTURE_2D, GL30.GL_TEXTURE_MAG_FILTER, GL30.GL_NEAREST);

        GL30.glTexImage2D(GL30.GL_TEXTURE_2D, 0, GL30.GL_RGBA, 1, 1, 0,
            GL30.GL_RGBA, GL30.GL_UNSIGNED_BYTE, data);
        MemoryUtil.memFree(data);

        width = 1;
        height = 1;
    }

    public void bind(int unit) {
        GL30.glActiveTexture(GL30.GL_TEXTURE0 + unit);
        GL30.glBindTexture(GL30.GL_TEXTURE_2D, id);
    }

    public void cleanup() {
        GL30.glDeleteTextures(id);
    }

    public int getId() { return id; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
}
