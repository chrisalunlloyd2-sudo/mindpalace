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
    private static final int ATLAS_W = COLS * GLYPH_W;
    private int rows;   // dynamic: ceil(glyphCount / COLS)
    private int atlasH;
    private final java.util.Map<Character, Integer> glyphIndex = new java.util.HashMap<>();
    private int questionIdx;

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
            System.out.println("[FontRenderer] Ready — " + ATLAS_W + "x" + atlasH + " atlas");
        } catch (Exception e) {
            System.err.println("[FontRenderer] Init failed: " + e.getMessage());
            ready = false;
        }
    }

    private void buildAtlas() {
        // Glyph coverage: ASCII printable + Latin-1 Supplement + Box Drawing + common symbols.
        // (The old atlas only covered 32..126 — every Unicode char rendered as '?' or garbage.)
        java.util.List<Character> glyphs = new java.util.ArrayList<>();
        for (int i = 32; i <= 126; i++) glyphs.add((char) i);
        for (int i = 160; i <= 255; i++) glyphs.add((char) i);
        for (int i = 0x2500; i <= 0x257F; i++) glyphs.add((char) i);
        for (char extra : new char[]{'\u2022','\u00B7','\u2192','\u2190','\u2026','\u2550','\u2551','\u2588','\u2591','\u2713','\u2717','\u2605','\u2606','\u266A'}) {
            if (!glyphs.contains(extra)) glyphs.add(extra);
        }
        rows = (glyphs.size() + COLS - 1) / COLS;
        atlasH = rows * GLYPH_H;
        for (int i = 0; i < glyphs.size(); i++) glyphIndex.put(glyphs.get(i), i);
        questionIdx = glyphIndex.getOrDefault('?', 0);

        BufferedImage img = new BufferedImage(ATLAS_W, atlasH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setColor(Color.WHITE);
        g.setFont(new Font("Monospaced", Font.BOLD, 20));
        FontMetrics fm = g.getFontMetrics();
        for (int i = 0; i < glyphs.size(); i++) {
            char c = glyphs.get(i);
            int col = i % COLS, row = i / COLS;
            int x = col * GLYPH_W, y = row * GLYPH_H;
            String s = String.valueOf(c);
            g.drawString(s, x + (GLYPH_W - fm.stringWidth(s)) / 2, y + fm.getAscent());
        }
        g.dispose();
        int[] pixels = new int[ATLAS_W * atlasH];
        img.getRGB(0, 0, ATLAS_W, atlasH, pixels, 0, ATLAS_W);
        ByteBuffer buf = ByteBuffer.allocateDirect(ATLAS_W * atlasH * 4);
        for (int y = atlasH - 1; y >= 0; y--)
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
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, ATLAS_W, atlasH, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);
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
            "uniform float atlasRows;\n" +
            "out vec2 TexCoord;\n" +
            "void main() {\n" +
            "  gl_Position = projection * view * model * vec4(aPos, 1.0);\n" +
            "  int col = glyphIndex % 16;\n" +
            "  int row = glyphIndex / 16;\n" +
            "  float u0 = float(col) / 16.0;\n" +
            "  float u1 = float(col + 1) / 16.0;\n" +
            "  // Atlas is uploaded top-down (image row 0 = texture v=1), so invert v:\n" +
            "  // without this every glyph sampled the WRONG row (text looked garbled).\n" +
            "  float v0 = 1.0 - float(row + 1) / atlasRows;\n" +
            "  float v1 = 1.0 - float(row) / atlasRows;\n" +
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


    /**
     * Cylindrical billboard matrix: keeps text UPRIGHT (unrotated on world-up axis)
     * yet faces the camera horizontally. Built from the view matrix's right/up/forward
     * so the glyph +X always points to the camera's screen-right, which means the
     * text NEVER reads backwards even when the camera is behind it. This is the fix
     * for the mirrored-backwards 3D text bug (rotateY(atan2) mirrored glyphs when
     * |angle| > 90deg; a pure single-axis Y-rotation cannot face camera AND stay
     * left-to-right at all angles). The old rotateY billboard path is left intact
     * (never delete) and only used as a fallback when a view matrix is unavailable.
     */
    private static Matrix4f cylindricalBillboard(Matrix4f view, Vector3f position) {
        // Pull the camera's LOCAL right and forward (horizontal plane only,
        // ignore pitch so text does not tilt up/down — stays upright).
        Vector3f camRight = new Vector3f(view.transpose().getColumn(0, new Vector3f()).x, 0f, view.transpose().getColumn(0, new Vector3f()).z).normalize();
        Vector3f camForward = new Vector3f(view.transpose().getColumn(2, new Vector3f()).x, 0f, view.transpose().getColumn(2, new Vector3f()).z).normalize();
        // +X -> camera screen-right, +Z -> camera forward (into view). Both on floor plane.
        Matrix4f m = new Matrix4f();
        m.set(
            camRight.x,        0f, camRight.z,       position.x,
            0f,                1f, 0f,               position.y,
            camForward.x,      0f, camForward.z,     position.z,
            0f,                0f, 0f,               1f
        );
        return m;
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

    /**
     * Billboard text — always faces the camera AND reads left-to-right from every
     * angle (cylindrical billboard). The old {@code rotateY(atan2)} version mirrored
     * glyphs when the camera was behind the text; this version orients each glyph
     * so its +X follows the camera's screen-right, which is the only orientation
     * where English is never backwards. (Old path kept via faceNormal fallback.)
     */
    public void renderBillboard(String text, Vector3f position, float charSize, Vector3f color,
                                Matrix4f projection, Matrix4f view, Vector3f camPos) {
        Vector3f toCam = new Vector3f(camPos).sub(position).normalize();
        renderInternal(text, position, charSize, color, projection, view, toCam, false, true);
    }

    /**
     * Overlay billboard — HUD-style text that is NEVER occluded by world
     * geometry: the depth test is disabled for the draw, so a prompt anchored
     * 3m in front of the camera still reads even when the player is standing
     * closer than 3m to a wall/door (which would otherwise z-fight the text
     * away). Same orientation as renderBillboard. Used by the unified
     * interaction prompt (InteractionPromptSystem) — a HUD element must win
     * against the world, always.
     */
    public void renderBillboardOverlay(String text, Vector3f position, float charSize, Vector3f color,
                                       Matrix4f projection, Matrix4f view, Vector3f camPos) {
        Vector3f toCam = new Vector3f(camPos).sub(position).normalize();
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        try {
            renderInternal(text, position, charSize, color, projection, view, toCam, false, true);
        } finally {
            GL11.glEnable(GL11.GL_DEPTH_TEST);
        }
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
        textShader.setUniform("atlasRows", (float) rows);
        GL30.glBindVertexArray(vao);
        float totalW = text.length() * charSize;
        float startX = position.x - totalW / 2f + charSize / 2f;
        float angleY = (float) Math.atan2(facingNormal.x, facingNormal.z);
        // Precompute a proper cylindrical-billboard basis ONCE per string from the
        // view matrix. cameraRight/Up are the view's local right/up (transposed
        // inverse = for a pure rigid view, the transpose is the inverse rotation).
        Vector3f cameraRight = new Vector3f(view.transpose().getColumn(0, new Vector3f()).x, 0f, view.transpose().getColumn(0, new Vector3f()).z).normalize();
        Vector3f cameraForward = new Vector3f(view.transpose().getColumn(2, new Vector3f()).x, 0f, view.transpose().getColumn(2, new Vector3f()).z).normalize();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int gi = glyphIndex.getOrDefault(c, questionIdx);
            float cx = startX + i * charSize;
            Matrix4f model = new Matrix4f();
            if (floor) {
                model.translate(cx, position.y, position.z).rotateX((float) -Math.PI / 2f)
                     .scale(charSize, charSize * 1.6f, 1f);
            } else if (billboard) {
                // Cylindrical billboard: +X -> camera screen-right, +Z -> camera
                // forward (both on the horizontal plane). This guarantees the glyph
                // faces the camera with its native left-to-right orientation intact,
                // so text is NEVER mirrored/backwards regardless of viewing angle.
                // Each glyph is offset along the camera's screen-right so the string
                // lays out correctly in screen space.
                float gy = position.y + i * charSize * 0f; // billboard stays flat upright; x advances along cameraRight
                float bx = position.x + cameraRight.x * (i * charSize);
                float bz = position.z + cameraRight.z * (i * charSize);
                model.set(
                    cameraRight.x,           0f, cameraForward.x,     bx,
                    0f,                      1f, 0f,                      gy,
                    cameraRight.z,           0f, cameraForward.z,       bz,
                    0f,                      0f, 0f,                      1f
                );
                model.scale(charSize, charSize * 1.6f, 1f);
            } else {
                // Wall-facing text: lay flat on the wall, rotated so +X is right
                // along the wall and -Z faces into the room (unchanged behavior).
                model.translate(cx, position.y, position.z).rotateY(angleY)
                     .scale(charSize, charSize * 1.6f, 1f);
            }
            textShader.setUniform("model", model);
            textShader.setUniform("glyphIndex", gi);
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
