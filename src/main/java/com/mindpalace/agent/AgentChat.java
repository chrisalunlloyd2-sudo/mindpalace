package com.mindpalace.agent;

import com.mindpalace.render.Renderer;
import org.joml.Vector3f;
import java.util.*;

/**
 * In-game chat panel for agent conversation.
 * Renders as a floating panel in 3D showing agent messages.
 * User types in console to chat with agents.
 */
public class AgentChat {
    private boolean open;
    private final List<String> messages = new ArrayList<>();
    private static final int MAX_MESSAGES = 20;
    private Vector3f panelPos = new Vector3f();
    private static final float PANEL_WIDTH = 4.0f;
    private static final float PANEL_HEIGHT = 2.5f;
    private static final float PANEL_DISTANCE = 3.0f;

    public AgentChat() {}

    public void open(Vector3f playerPos, Vector3f lookDir) {
        this.open = true;
        panelPos.set(playerPos).add(
            lookDir.x * PANEL_DISTANCE,
            lookDir.y * PANEL_DISTANCE + 0.3f,
            lookDir.z * PANEL_DISTANCE);
    }

    public void close() {
        open = false;
    }

    public void toggle(Vector3f playerPos, Vector3f lookDir) {
        if (open) close(); else open(playerPos, lookDir);
    }

    public void addMessage(String msg) {
        messages.add(msg);
        while (messages.size() > MAX_MESSAGES) messages.remove(0);
        System.out.println("[AgentChat] " + msg);
    }

    public void render(Renderer r) {
        if (!open) return;
        // Dark panel
        r.drawCube(panelPos, new Vector3f(PANEL_WIDTH, PANEL_HEIGHT, 0.05f), Renderer.TEX_BOOK_GREY);
        // Border
        float bt = 0.04f;
        float hw = PANEL_WIDTH / 2f, hh = PANEL_HEIGHT / 2f;
        r.drawCube(new Vector3f(panelPos.x - hw, panelPos.y, panelPos.z), new Vector3f(bt, PANEL_HEIGHT, bt), Renderer.TEX_DOOR);
        r.drawCube(new Vector3f(panelPos.x + hw, panelPos.y, panelPos.z), new Vector3f(bt, PANEL_HEIGHT, bt), Renderer.TEX_DOOR);
        r.drawCube(new Vector3f(panelPos.x, panelPos.y - hh, panelPos.z), new Vector3f(PANEL_WIDTH, bt, bt), Renderer.TEX_DOOR);
        r.drawCube(new Vector3f(panelPos.x, panelPos.y + hh, panelPos.z), new Vector3f(PANEL_WIDTH, bt, bt), Renderer.TEX_DOOR);
    }

    public boolean isOpen() { return open; }
    public List<String> getMessages() { return messages; }
}
