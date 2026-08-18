package com.mindpalace.render;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.stb.STBImageWrite;

import java.nio.ByteBuffer;

/**
 * Framebuffer screenshot capture — glReadPixels → PNG via STB.
 * This is how the agent "sees" the game: dump the back buffer to a PNG,
 * then analyse it with PIL. Wired to F12 and the --autodrive walkthrough.
 */
public class Screenshot {
    /**
     * Capture the current framebuffer to a PNG. Call BEFORE glfwSwapBuffers
     * (reads GL_BACK). Returns the path written, or null on failure.
     */
    public static String capture(int width, int height, String path) {
        try {
            GL11.glReadBuffer(GL11.GL_BACK);
            ByteBuffer raw = BufferUtils.createByteBuffer(width * height * 4);
            GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, raw);

            // glReadPixels is bottom-up; STB wants top-down. Flip rows.
            ByteBuffer flipped = BufferUtils.createByteBuffer(width * height * 4);
            int rowBytes = width * 4;
            for (int y = height - 1; y >= 0; y--) {
                int srcOff = y * rowBytes;
                for (int x = 0; x < rowBytes; x++) {
                    flipped.put(raw.get(srcOff + x));
                }
            }
            flipped.flip();

            if (!STBImageWrite.stbi_write_png(path, width, height, 4, flipped, rowBytes)) {
                System.err.println("[Screenshot] stbi_write_png failed: " + path);
                return null;
            }
            return path;
        } catch (Exception e) {
            System.err.println("[Screenshot] capture failed: " + e.getMessage());
            return null;
        }
    }
}
