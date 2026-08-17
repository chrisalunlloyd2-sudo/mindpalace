package com.mindpalace.render;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;

/**
 * Bitmap font renderer — generates a texture atlas from Java 2D, renders text as quads.
 * Supports wall-facing, floor, and billboard (always face camera) modes.
 */
public class FontRenderer {
    private static final int GLYPH_W = 16;
    private static final int GLYPH_H = 28;
    private static final int COLS = 16;
    private static final int ROWS = 6;
    private static final int ATLAS_W = COLS * GLYPH_W;
    private static final int ATLAS_H = ROWS * GLYPH_H;

    private int textureId;
    private int vao, vbo, ebo;
    private Shader textShader;
    private boolean ready;

    public FontRenderer() {
        try {
            buildAtlas();
            buildMesh();
            buildShader();
            ready = true;
            System.out.println("[FontRenderer] Ready — " + ATLAS_W + "x" + ATLAS_H + " atlas");
        } catch (Exception e) {
            System.err.println("[FontRenderer] Init failed: " + e.getMessage());
            ready = false;
        }
    }

    private void buildAtlas() {
        BufferedImage img = new BufferedImage(ATLAS_W, ATLAS_H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setColor(Color.WHITE);
        g.setFont(new Font("Monospaced", Font.BOLD, 20));
        FontMetrics fm = g.getFontMetrics();
        for (int i = 0; i < 95; i++) {
            char c = (char) (i + 32);
            int col = i % COLS, row = i / COLS;
            int x = col * GLYPH_W, y = row * GLYPH_H;
            String s = String.valueOf(c);
            g.drawString(s, x + (GLYPH_W - fm.stringWidth(s)) / 2, y + fm.getAscent());
        }
        g.dispose();
        int[] pixels = new int[ATLAS_W * ATLAS_H];
        img.getRGB(0, 0, ATLAS_W, ATLAS_H, pixels, 0, ATLAS_W);
        ByteBuffer buf = ByteBuffer.allocateDirect(ATLAS_W * ATLAS_H * 4);
        for (int y = ATLAS_H - 1; y >= 0; y--)
            for (int x = 0; x < ATLAS_W; x++) {
                int px = pixels[y * ATLAS_W + x];
                buf.put((byte) ((px >> 16) & 0xFF));
                buf.put((byte) ((px >> 8) & 0xFF));
                buf.put((byte) (px & 0xFF));
                buf.put((byte) ((px >> 24) & 0xFF));
            }
        buf.flip();
        textureId = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_CLAMP);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_CLAMP);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, ATLAS_W, ATLAS_H, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);
    }

    private void buildMesh() {
        float[] verts = {0,0,0, 0,0,1, 0,0, 1,0,0, 0,0,1, 1,0, 1,1,0, 0,0,1, 1,1, 0,1,0, 0,0,1, 0,1};
        int[] indices = {0,1,2, 0,2,3};
        vao = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(vao);
        vbo = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, verts, GL15.GL_STATIC_DRAW);
        ebo = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, ebo);
        GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, indices, GL15.GL_STATIC_DRAW);
        int stride = 8 * 4;
        GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, stride, 0);
        GL20.glEnableVertexAttribArray(0);
        GL20.glVertexAttribPointer(1, 3, GL11.GL_FLOAT, false, stride, 3 * 4);
        GL20.glEnableVertexAttribArray(1);
        GL20.glVertexAttribPointer(2, 2, GL11.GL_FLOAT, false, stride, 6 * 4);
        GL20.glEnableVertexAttribArray(2);
        GL30.glBindVertexArray(0);
    }

    private void buildShader() {
        String vertSrc =
            "#version 330 core\n" +
            "layout(location=0) in vec3 aPos;\n" +
            "layout(location=1) in vec3 aNormal;\n" +
            "layout(location=2) in vec2 aTexCoord;\n" +
            "uniform mat4 projection;\n" +
            "uniform mat4 view;\n" +
            "uniform mat4 model;\n" +
            "uniform int glyphIndex;\n" +
            "out vec2 TexCoord;\n" +
            "void main() {\n" +
            "  gl_Position = projection * view * model * vec4(aPos, 1.0);\n" +
            "  int col = glyphIndex % 16;\n" +
            "  int row = glyphIndex / 16;\n" +
            "  float u0 = float(col) / 16.0;\n" +
            "  float u1 = float(col + 1) / 16.0;\n" +
            "  float v0 = float(row) / 6.0;\n" +
            "  float v1 = float(row + 1) / 6.0;\n" +
            "  TexCoord = vec2(u0, v0) + aTexCoord * vec2(u1 - u0, v1 - v0);\n" +
            "}\n";
        String fragSrc =
            "#version 330 core\n" +
            "in vec2 TexCoord;\n" +
            "uniform sampler2D tex;\n" +
            "uniform vec4 textColor;\n" +
            "out vec4 FragColor;\n" +
            "void main() {\n" +
            "  float a = texture(tex, TexCoord).r;\n" +
            "  FragColor = vec4(textColor.rgb, textColor.a * a);\n" +
            "}\n";
        textShader = new Shader(vertSrc, fragSrc, true);
    }

    /** Wall-facing text. */
    public void renderText(String text, Vector3f position, float charSize, Vector3f color,
                           Matrix4f projection, Matrix4f view, Vector3f facingNormal) {
        renderInternal(text, position, charSize, color, projection, view, facingNormal, false, false);
    }

    /** Text flat on floor. */
    public void renderFloorText(String text, Vector3f position, float charSize, Vector3f color,
                                Matrix4f projection, Matrix4f view) {
        renderInternal(text, position, charSize, color, projection, view, new Vector3f(0, 1, 0), true, false);
    }

    /** Billboard text — always faces the camera. */
    public void renderBillboard(String text, Vector3f position, float charSize, Vector3f color,
                                Matrix4f projection, Matrix4f view, Vector3f camPos) {
        Vector3f toCam = new Vector3f(camPos).sub(position).normalize();
        renderInternal(text, position, charSize, color, projection, view, toCam, false, true);
    }

    private void renderInternal(String text, Vector3f position, float charSize, Vector3f color,
                                Matrix4f projection, Matrix4f view, Vector3f facingNormal,
                                boolean floor, boolean billboard) {
        if (!ready || text == null || text.isEmpty()) return;
        // Multi-line text: split and render line by line (also strips CR so
        // CRLF content from GitHub never reaches the glyph atlas).
        if (text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) {
            String[] lines = text.replace("\r", "").split("\n", -1);
            float lineH = charSize * 1.7f;
            for (int li = 0; li < lines.length; li++) {
                if (lines[li].isEmpty()) continue;
                Vector3f p = new Vector3f(position.x, position.y + li * lineH, position.z);
                renderInternal(lines[li], p, charSize, color, projection, view, facingNormal, floor, billboard);
            }
            return;
        }
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDisable(GL11.GL_CULL_FACE);
        textShader.bind();
        textShader.setUniform("projection", projection);
        textShader.setUniform("view", view);
        textShader.setUniform("textColor", new Vector4f(color.x, color.y, color.z, 1.0f));
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
        textShader.setUniform("tex", 0);
        GL30.glBindVertexArray(vao);
        float totalW = text.length() * charSize;
        float startX = position.x - totalW / 2f + charSize / 2f;
        float angleY = (float) Math.atan2(facingNormal.x, facingNormal.z);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 32 || c > 126) c = '?';
            float cx = startX + i * charSize;
            Matrix4f model = new Matrix4f();
            if (floor) {
                model.translate(cx, position.y, position.z).rotateX((float) -Math.PI / 2f)
                     .scale(charSize, charSize * 1.6f, 1f);
            } else if (billboard) {
                model.translate(cx, position.y, position.z).rotateY(angleY)
                     .scale(charSize, charSize * 1.6f, 1f);
            } else {
                model.translate(cx, position.y, position.z).rotateY(angleY)
                     .scale(charSize, charSize * 1.6f, 1f);
            }
            textShader.setUniform("model", model);
            textShader.setUniform("glyphIndex", c - 32);
            GL11.glDrawElements(GL11.GL_TRIANGLES, 6, GL11.GL_UNSIGNED_INT, 0);
        }
        GL30.glBindVertexArray(0);
        GL11.glEnable(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_BLEND);
    }

    public void cleanup() {
        if (textureId != 0) GL11.glDeleteTextures(textureId);
        if (vao != 0) GL30.glDeleteVertexArrays(vao);
        if (vbo != 0) GL15.glDeleteBuffers(vbo);
        if (ebo != 0) GL15.glDeleteBuffers(ebo);
        if (textShader != null) textShader.cleanup();
    }

    public boolean isReady() { return ready; }
}
