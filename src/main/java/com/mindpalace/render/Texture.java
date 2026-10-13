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

    /** Private no-arg constructor for static factory methods (grass, etc.). */
    private Texture() {}

    /** Decoded RGBA pixels, ready for {@link #upload}. Decoding needs no GL context, so it can run on a worker thread. */
    public static final class Pixels {
        final ByteBuffer data;
        final int width, height;
        final boolean stbOwned;          // true: free with stbi_image_free; false: our own memAlloc (resized copy)
        Pixels(ByteBuffer data, int width, int height, boolean stbOwned) {
            this.data = data; this.width = width; this.height = height; this.stbOwned = stbOwned;
        }
        public int width() { return width; }
        public int height() { return height; }
        /** Release the pixels without uploading them (self-check / failed uploads). */
        public void free() { if (stbOwned) STBImage.stbi_image_free(data); else MemoryUtil.memFree(data); }
        /** First RGBA pixel, for round-trip checks. */
        public int firstPixel() { return data.getInt(0); }
    }

    /** Decode an image file to RGBA (flipped vertically, like the GL convention). Safe off the render thread:
     *  the flip is requested through stb's THREAD-local override, so it cannot race the global flag. */
    public static Pixels decode(String path) {
        return decode(path, Integer.MAX_VALUE);
    }

    /** As {@link #decode(String)}, but an image larger than maxDim on its long side is downscaled (aspect kept) on THIS
     *  thread, so the render thread only uploads a small texture. A 4000 px poster upload + mipmap chain stalled a frame. */
    public static Pixels decode(String path, int maxDim) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer w = stack.mallocInt(1);
            IntBuffer h = stack.mallocInt(1);
            IntBuffer comp = stack.mallocInt(1);
            STBImage.stbi_set_flip_vertically_on_load_thread(1);
            ByteBuffer data = STBImage.stbi_load(path, w, h, comp, 4); // force RGBA
            if (data == null) {
                throw new RuntimeException("Failed to load texture: " + path + " — " + STBImage.stbi_failure_reason());
            }
            int iw = w.get(0), ih = h.get(0);
            int longSide = Math.max(iw, ih);
            if (longSide > maxDim) {
                int ow = Math.max(1, Math.round(iw * (float) maxDim / longSide));
                int oh = Math.max(1, Math.round(ih * (float) maxDim / longSide));
                ByteBuffer out = MemoryUtil.memAlloc(ow * oh * 4);
                ByteBuffer ok = org.lwjgl.stb.STBImageResize.stbir_resize_uint8_srgb(
                    data, iw, ih, 0, out, ow, oh, 0, org.lwjgl.stb.STBImageResize.STBIR_RGBA);
                if (ok != null) {
                    STBImage.stbi_image_free(data);
                    return new Pixels(out, ow, oh, false);
                }
                MemoryUtil.memFree(out);          // resize failed: fall back to the full-size image
            }
            return new Pixels(data, iw, ih, true);
        }
    }

    /** Create the GL texture from decoded pixels and free them. Render thread only (needs the GL context). */
    public static Texture upload(Pixels p) {
        Texture t = new Texture();
        t.uploadPixels(p);
        return t;
    }

    public Texture(String path) {
        uploadPixels(decode(path));
    }

    private void uploadPixels(Pixels p) {
        {
            ByteBuffer data = p.data;
            width = p.width;
            height = p.height;

            id = GL30.glGenTextures();
            GL30.glBindTexture(GL30.GL_TEXTURE_2D, id);

            GL30.glTexParameteri(GL30.GL_TEXTURE_2D, GL30.GL_TEXTURE_WRAP_S, GL30.GL_REPEAT);
            GL30.glTexParameteri(GL30.GL_TEXTURE_2D, GL30.GL_TEXTURE_WRAP_T, GL30.GL_REPEAT);
            GL30.glTexParameteri(GL30.GL_TEXTURE_2D, GL30.GL_TEXTURE_MIN_FILTER, GL30.GL_LINEAR_MIPMAP_LINEAR);
            GL30.glTexParameteri(GL30.GL_TEXTURE_2D, GL30.GL_TEXTURE_MAG_FILTER, GL30.GL_LINEAR);

            GL30.glTexImage2D(GL30.GL_TEXTURE_2D, 0, GL30.GL_RGBA, width, height, 0,
                GL30.GL_RGBA, GL30.GL_UNSIGNED_BYTE, data);
            GL30.glGenerateMipmap(GL30.GL_TEXTURE_2D);

            if (p.stbOwned) STBImage.stbi_image_free(data); else MemoryUtil.memFree(data);
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

    /**
     * Create a vertical gradient texture (top color → bottom color), cosine-
     * interpolated. Used for the sky dome so the horizon fades smoothly.
     * v=0 is the top row, v=1 the bottom row.
     */
    public Texture(float[] top, float[] bottom, int h) {
        id = GL30.glGenTextures();
        GL30.glBindTexture(GL30.GL_TEXTURE_2D, id);

        int w = 4; // gradient is vertical; width can be tiny
        ByteBuffer data = MemoryUtil.memAlloc(w * h * 4);
        for (int y = 0; y < h; y++) {
            float t = y / (float) (h - 1);
            // cosine ease for a smooth, faint ramp
            float c = 0.5f - 0.5f * (float) Math.cos(t * (float) Math.PI);
            float r = top[0] + (bottom[0] - top[0]) * c;
            float g = top[1] + (bottom[1] - top[1]) * c;
            float b = top[2] + (bottom[2] - top[2]) * c;
            for (int x = 0; x < w; x++) {
                data.put((byte) (r * 255));
                data.put((byte) (g * 255));
                data.put((byte) (b * 255));
                data.put((byte) 255);
            }
        }
        data.flip();

        GL30.glTexParameteri(GL30.GL_TEXTURE_2D, GL30.GL_TEXTURE_WRAP_S, GL30.GL_CLAMP_TO_EDGE);
        GL30.glTexParameteri(GL30.GL_TEXTURE_2D, GL30.GL_TEXTURE_WRAP_T, GL30.GL_CLAMP_TO_EDGE);
        GL30.glTexParameteri(GL30.GL_TEXTURE_2D, GL30.GL_TEXTURE_MIN_FILTER, GL30.GL_LINEAR);
        GL30.glTexParameteri(GL30.GL_TEXTURE_2D, GL30.GL_TEXTURE_MAG_FILTER, GL30.GL_LINEAR);

        GL30.glTexImage2D(GL30.GL_TEXTURE_2D, 0, GL30.GL_RGBA, w, h, 0,
            GL30.GL_RGBA, GL30.GL_UNSIGNED_BYTE, data);
        MemoryUtil.memFree(data);

        width = w;
        height = h;
    }

    public void bind(int unit) {
        GL30.glActiveTexture(GL30.GL_TEXTURE0 + unit);
        GL30.glBindTexture(GL30.GL_TEXTURE_2D, id);
    }

    /**
     * Procedural grass polytexture — a mathematical blade pattern built from
     * layered sine waves (no image asset). Each texel's green is modulated by
     * a few incommensurate sines so the ground reads as textured turf, not a
     * flat color. Cheap: generated once at startup.
     */
    public static Texture grass(int size) {
        int id = GL30.glGenTextures();
        GL30.glBindTexture(GL30.GL_TEXTURE_2D, id);
        ByteBuffer data = MemoryUtil.memAlloc(size * size * 4);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                float u = x / (float) size, v = y / (float) size;
                // Layered sines → organic mottling (incommensurate frequencies)
                float n = (float) (Math.sin(u * 40.0) * Math.sin(v * 40.0)
                    + 0.5 * Math.sin((u + v) * 70.0)
                    + 0.3 * Math.sin(u * 130.0 - v * 90.0));
                n = 0.5f + 0.5f * n / 1.8f;   // normalize to ~0..1
                float g = 0.35f + 0.30f * n;  // green varies
                float r = 0.10f + 0.08f * n;
                float b = 0.08f + 0.06f * n;
                data.put((byte) (r * 255));
                data.put((byte) (g * 255));
                data.put((byte) (b * 255));
                data.put((byte) 255);
            }
        }
        data.flip();
        GL30.glTexParameteri(GL30.GL_TEXTURE_2D, GL30.GL_TEXTURE_WRAP_S, GL30.GL_REPEAT);
        GL30.glTexParameteri(GL30.GL_TEXTURE_2D, GL30.GL_TEXTURE_WRAP_T, GL30.GL_REPEAT);
        GL30.glTexParameteri(GL30.GL_TEXTURE_2D, GL30.GL_TEXTURE_MIN_FILTER, GL30.GL_LINEAR_MIPMAP_LINEAR);
        GL30.glTexParameteri(GL30.GL_TEXTURE_2D, GL30.GL_TEXTURE_MAG_FILTER, GL30.GL_LINEAR);
        GL30.glTexImage2D(GL30.GL_TEXTURE_2D, 0, GL30.GL_RGBA, size, size, 0,
            GL30.GL_RGBA, GL30.GL_UNSIGNED_BYTE, data);
        GL30.glGenerateMipmap(GL30.GL_TEXTURE_2D);
        MemoryUtil.memFree(data);
        Texture t = new Texture();
        t.id = id; t.width = size; t.height = size;
        return t;
    }

    public void cleanup() {
        GL30.glDeleteTextures(id);
    }

    public int getId() { return id; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
}
