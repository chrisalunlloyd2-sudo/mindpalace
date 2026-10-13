package com.mindpalace.blocks;

import java.util.ArrayList;
import java.util.List;

/** Standalone proof harness (run with plain java, no LWJGL). Runs every
 * block's selfTest() and prints one line per block. Exit 0 = all alive. */
public final class SelfTestProbe {
    public static void main(String[] args) {
        List<Block> made = new ArrayList<>();
        ArrayList<FireBlock.Anchor> anchors = new ArrayList<>();
        anchors.add(new FireBlock.Anchor(2f, -14f, "courtyard-forge"));
        anchors.add(new FireBlock.Anchor(-6f, -22f, "forest-camp"));
        made.add(FireBlock.seedDefault());
        made.add(new WeatherBlock());
        made.add(new FrostBlock());
        made.add(new com.mindpalace.blocks.FireflyBlock());
        int passed = 0;
        for (Block b : made) {
            boolean ok;
            try { ok = b.selfTest(); }
            catch (Throwable t) { System.out.println("[probe] " + b.id() + " EXCEPTION " + t); ok = false; }
            System.out.println((ok ? "PASS" : "FAIL") + " " + b.id());
            if (ok) passed++;
        }
        System.out.println("PROBE_RESULT " + passed + "/" + made.size());
        if (passed != made.size()) System.exit(1);
    }
}
