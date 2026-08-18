package com.mindpalace;

import com.mindpalace.engine.GameEngine;

/**
 * MindPalace — 3D First-Person GitHub Repository Explorer
 *
 * A Doom-style walkthrough of your code universe.
 * 123 GitHub repos, 49 local repos, ViperAI_Notes — all as rooms in a grand hallway.
 *
 * CLI flags:
 *   --autodrive <dir>   scripted walkthrough that captures PNG frames to <dir>
 *                       (lets the agent SEE the world without a human driving)
 */
public class Main {
    public static void main(String[] args) {
        GameEngine engine = new GameEngine();
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--autodrive") && i + 1 < args.length) {
                engine.setAutodrive(args[i + 1]);
            }
        }
        engine.run();
    }
}
