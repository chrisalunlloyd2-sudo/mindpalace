package com.mindpalace.ui;

import com.mindpalace.render.Renderer;
import com.mindpalace.entity.Player;
import com.mindpalace.world.Room;
import com.mindpalace.world.WorldBuilder;
import org.joml.Vector3f;

/**
 * HUD — door interaction prompts with repo name + last commit.
 */
public class HUD {
    private static final float INTERACT_RANGE = 2.5f;
    private String lastPrompt = "";

    public void render(Renderer renderer, Player player, WorldBuilder world) {
        Vector3f origin = player.getPosition();
        Vector3f front = player.getLookDirection();
        String prompt = "";

        for (Room room : world.getRooms()) {
            Vector3f dp = room.getDoorPosition();
            if (dp == null) continue;
            if (origin.distance(dp) < INTERACT_RANGE) {
                Vector3f to = new Vector3f(dp).sub(origin).normalize();
                if (front.dot(to) > 0.85f) {
                    StringBuilder sb = new StringBuilder();
                    sb.append("[ENTER] ").append(room.getDisplayLabel());
                    if (room.getLastCommit() != null && !room.getLastCommit().isEmpty()) {
                        sb.append(" | last: ").append(room.getLastCommit());
                    }
                    prompt = sb.toString();
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
