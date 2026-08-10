package com.mindpalace;

import com.mindpalace.engine.GameEngine;

/**
 * MindPalace — 3D First-Person GitHub Repository Explorer
 * 
 * A Doom-style walkthrough of your code universe.
 * 123 GitHub repos, 49 local repos, ViperAI_Notes — all as rooms in a grand hallway.
 */
public class Main {
    public static void main(String[] args) {
        GameEngine engine = new GameEngine();
        engine.run();
    }
}
