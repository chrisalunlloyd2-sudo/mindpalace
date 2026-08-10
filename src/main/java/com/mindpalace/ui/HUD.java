package com.mindpalace.ui;

import com.mindpalace.render.Renderer;
import com.mindpalace.entity.Player;
import com.mindpalace.world.Room;
import com.mindpalace.world.WorldBuilder;
import org.joml.Vector3f;

/**
 * Heads-up display — crosshair, interaction prompts, room info.
 */
public class HUD {
    private float interactionRange = 2.5f;
    private String lastPrompt = "";
    private double promptTimer;

    public void render(Renderer renderer, Player player, WorldBuilder world) {
        Vector3f lookDir = player.getLookDirection();
        Vector3f lookOrigin = player.getPosition();

        String prompt = "";
        for (Room room : world.getRooms()) {
            Vector3f doorPos = room.getDoorPosition();
            if (doorPos == null) continue;

            float dist = lookOrigin.distance(doorPos);
            if (dist < interactionRange) {
                Vector3f toDoor = new Vector3f(doorPos).sub(lookOrigin).normalize();
                if (lookDir.dot(toDoor) > 0.85f) {
                    prompt = "[E] " + room.getDisplayLabel();
                    break;
                }
            }
        }

        // Only print when prompt changes (avoids spam)
        if (!prompt.equals(lastPrompt)) {
            lastPrompt = prompt;
            if (!prompt.isEmpty()) {
                System.out.println(prompt);
            }
        }
    }
}
