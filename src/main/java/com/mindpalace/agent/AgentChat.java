package com.mindpalace.agent;

import com.mindpalace.render.Renderer;
import com.mindpalace.render.FontRenderer;
import com.mindpalace.render.Camera;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import java.util.*;

/**
 * Always-on chat feed — renders the agent conversation at the top of the screen.
 * Press Enter to open a typing cursor and chat back; Enter again to send.
 * Messages auto-prune to a rolling window (theta curve) so the feed never grows.
 */
public class AgentChat {
    private final List<String> messages = new ArrayList<>();
    private static final int MAX_MESSAGES = 12;   // rolling window
    private static final int MAX_LEN = 96;        // truncate long lines

    private boolean typing;          // cursor is up, capturing input
    private final StringBuilder input = new StringBuilder();

    public AgentChat() {}

    public void addMessage(String msg) {
        if (msg == null || msg.isEmpty()) return;
        // Truncate + wrap long messages
        String clean = msg.replace('\n', ' ').replace('\r', ' ');
        while (clean.length() > MAX_LEN) {
            messages.add(clean.substring(0, MAX_LEN));
            clean = clean.substring(MAX_LEN);
        }
        messages.add(clean);
        while (messages.size() > MAX_MESSAGES) messages.remove(0);
        System.out.println("[AgentChat] " + msg);
    }

    /** Toggle typing mode. */
    public void toggleTyping() {
        typing = !typing;
        if (!typing) input.setLength(0);
    }

    public boolean isTyping() { return typing; }

    /** Append typed characters (from Input char callback). */
    public void appendInput(String chars) {
        if (typing) input.append(chars);
    }

    /** Backspace. */
    public void backspace() {
        if (typing && input.length() > 0) input.setLength(input.length() - 1);
    }

    /** Commit the typed message (returns it, or null if empty). */
    public String commitInput() {
        String s = input.toString().trim();
        input.setLength(0);
        typing = false;
        return s.isEmpty() ? null : s;
    }

    public String getInputText() { return input.toString(); }

    /**
     * Render the chat feed at the top of the screen (always visible).
     * Uses billboard text anchored to the camera's view.
     */
    public void render(Renderer r, FontRenderer font, Camera cam, int width, int height) {
        if (font == null || !font.isReady()) return;

        Matrix4f proj = cam.getProjectionMatrix((float) width / height);
        Matrix4f view = cam.getViewMatrix();
        Vector3f camPos = cam.getPosition();
        Vector3f front = cam.getFront();
        Vector3f right = new Vector3f(front).cross(new Vector3f(0, 1, 0)).normalize();
        Vector3f up = new Vector3f(right).cross(front).normalize();

        // Anchor at top of view, 2.5m in front
        Vector3f anchor = new Vector3f(camPos)
            .add(front.x * 2.5f, front.y * 2.5f, front.z * 2.5f)
            .add(up.x * 0.9f, up.y * 0.9f, up.z * 0.9f);

        // Show last N messages, newest at bottom
        int start = Math.max(0, messages.size() - 8);
        float lineH = 0.09f;
        float y = anchor.y;
        for (int i = start; i < messages.size(); i++) {
            String line = messages.get(i);
            Vector3f pos = new Vector3f(anchor.x, y, anchor.z);
            Vector3f color = line.startsWith("[Critic]") ? new Vector3f(1.0f, 0.7f, 0.2f)
                          : line.startsWith("[Tool") || line.startsWith("[Auto") ? new Vector3f(0.2f, 0.9f, 1.0f)
                          : line.startsWith("[You]") ? new Vector3f(0.4f, 1.0f, 0.4f)
                          : new Vector3f(0.8f, 0.8f, 0.8f);
            font.renderBillboard(line, pos, 0.045f, color, proj, view, camPos);
            y -= lineH;
        }

        // Typing cursor / input line
        if (typing) {
            String prompt = "> " + input.toString() + "_";
            Vector3f pos = new Vector3f(anchor.x, y - 0.05f, anchor.z);
            font.renderBillboard(prompt, pos, 0.05f, new Vector3f(0.4f, 1.0f, 0.4f), proj, view, camPos);
        }
    }

    public boolean isOpen() { return true; } // always on
    public List<String> getMessages() { return messages; }
}
