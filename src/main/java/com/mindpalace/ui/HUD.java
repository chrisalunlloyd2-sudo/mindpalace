package com.mindpalace.ui;

import com.mindpalace.render.Renderer;
import com.mindpalace.entity.Player;
import com.mindpalace.world.Room;
import com.mindpalace.world.WorldBuilder;
import org.joml.Vector3f;

/**
 * HUD — crosshair dot, interaction prompts.
 */
public class HUD {
    private static final float INTERACT_RANGE = 2.5f;
    private String lastPrompt = "";

    public void render(Renderer renderer, Player player, WorldBuilder world) {
        // Crosshair dot — tiny cube 0.4m in front of camera
        Vector3f camPos = player.getPosition();
        Vector3f front = player.getLookDirection();
        Vector3f dotPos = new Vector3f(camPos).add(
            front.x * 0.4f, front.y * 0.4f, front.z * 0.4f);
        renderer.drawCube(dotPos, new Vector3f(0.015f, 0.015f, 0.015f), Renderer.TEX_CROSSHAIR);

        // Door interaction prompt
        Vector3f origin = player.getPosition();
        String prompt = "";
        for (Room room : world.getRooms()) {
            Vector3f dp = room.getDoorPosition();
            if (dp == null) continue;
            if (origin.distance(dp) < INTERACT_RANGE) {
                Vector3f to = new Vector3f(dp).sub(origin).normalize();
                if (front.dot(to) > 0.85f) {
                    prompt = "[E] " + room.getDisplayLabel();
                    break;
                }
            }
        }

        if (!prompt.equals(lastPrompt)) {
            lastPrompt = prompt;
            if (!prompt.isEmpty()) System.out.println(prompt);
        }
    }
}
