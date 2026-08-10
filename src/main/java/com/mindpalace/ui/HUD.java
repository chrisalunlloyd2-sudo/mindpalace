package com.mindpalace.ui;

import com.mindpalace.render.Renderer;
import com.mindpalace.entity.Player;
import com.mindpalace.world.Room;
import com.mindpalace.world.WorldBuilder;
import org.joml.Vector3f;

/**
 * Heads-up display — crosshair, interaction prompts, room info.
 * Rendered as 2D overlay after 3D scene.
 */
public class HUD {
    private float crosshairSize = 8.0f;
    private float interactionRange = 2.5f;

    public void render(Renderer renderer, Player player, WorldBuilder world) {
        // Crosshair is drawn via OpenGL lines in screen space
        // For now, we'll use the window title for info

        // Find room player is in
        Room currentRoom = world.findRoomAt(player.getPosition());
        if (currentRoom != null) {
            // Room info is shown in window title (set in GameEngine)
        }

        // Interaction raycast — check if looking at a door or book
        Vector3f lookDir = player.getLookDirection();
        Vector3f lookOrigin = player.getPosition();

        // Simple AABB raycast against room doors
        for (Room room : world.getRooms()) {
            Vector3f doorPos = room.getDoorPosition();
            if (doorPos == null) continue;

            float dist = lookOrigin.distance(doorPos);
            if (dist < interactionRange) {
                // Check if player is looking roughly at the door
                Vector3f toDoor = new Vector3f(doorPos).sub(lookOrigin).normalize();
                float dot = lookDir.dot(toDoor);
                if (dot > 0.85f) {
                    // Show interaction prompt
                    System.out.print("\r[E] Enter: " + room.getDisplayLabel() + "                    ");
                }
            }
        }
    }
}
