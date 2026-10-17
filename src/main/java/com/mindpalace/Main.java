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
 *   --demo              fixture world from data/demo_repos.json — zero auth/network
 */
public class Main {
    public static void main(String[] args) {
        GameEngine engine = new GameEngine();
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--autodrive") && i + 1 < args.length) {
                engine.setAutodrive(args[i + 1]);
            } else if (args[i].equals("--e2e") && i + 1 < args.length) {
                engine.setE2E(args[i + 1]);
            } else if (args[i].equals("--selftest")) {
                engine.setSelfTest();
            } else if (args[i].equals("--demo")) {
                engine.setDemo();
            } else if (args[i].equals("--orbit") && i + 1 < args.length) {
                engine.setOrbit(args[i + 1]);
            } else if (args[i].equals("--perf") && i + 1 < args.length) {
                engine.setPerf(args[i + 1]);
            } else if (args[i].equals("--stress") && i + 1 < args.length) {
                engine.setStress(Integer.parseInt(args[i + 1]));
            }
        }
        // STEP 94v7: --time N pins the WHOLE world hour (sky/water/moon/stars)
        // for reproducible night/dusk QA captures; default = real clock.
        com.mindpalace.world.TimeClock.parseArg(args);
        engine.run();
    }
}
