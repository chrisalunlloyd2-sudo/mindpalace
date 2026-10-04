package com.mindpalace.blocks;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** MP-20 registry + composer. Blocks are minted at boot (ADD-only), hidden
 * until their selfTest() fires true, then layered at the one bloom-apply seam.
 * The registry grows toward "enough blocks to populate anything in real time". */
public final class BlockRegistry {

    private final List<Block> blocks = new ArrayList<Block>();
    private final Set<String> ids = new HashSet<String>();
    private final Set<Block> active = new HashSet<Block>();

    /** ADD-only mint: duplicate ids are refused loudly (never mint twice). */
    public void register(Block b) {
        if (b == null) return;
        if (ids.contains(b.id())) {
            System.out.println("[Blocks] duplicate id refused: " + b.id());
            return;
        }
        ids.add(b.id());
        blocks.add(b);
        if (b.selfTest()) active.add(b);
        else System.out.println("[Blocks] " + b.id() + " self-test FAILED -- hidden (bool-false law)");
    }

    /** Re-run every block's self-probes; refresh the active set. */
    public boolean selfTestAll() {
        boolean ok = true;
        active.clear();
        for (Block b : blocks) {
            try {
                if (b.selfTest()) active.add(b); else ok = false;
            } catch (RuntimeException e) {
                ok = false;
                System.out.println("[Blocks] " + b.id() + " self-test threw: " + e);
            }
        }
        return ok;
    }

    public void updateAll(float dt, float playerX, float playerZ, float baseLuma) {
        for (Block b : blocks) {
            try {
                if (active.contains(b)) b.update(dt, playerX, playerZ, baseLuma);
            } catch (RuntimeException e) {
                System.out.println("[Blocks] " + b.id() + " update skipped: " + e);
            }
        }
    }

    /** The composition chain: hidden blocks are identity. Never throws. */
    public float applyLuma(float luma) {
        float out = luma;
        for (Block b : blocks) {
            if (!active.contains(b)) continue; // hidden blocks are identity (bool-false law)
            try {
                out = b.applyLuma(out);
            } catch (RuntimeException e) {
                System.out.println("[Blocks] " + b.id() + " luma skipped: " + e);
            }
        }
        return out;
    }

    public void cleanup() {
        for (Block b : blocks) {
            try { b.cleanup(); } catch (RuntimeException e) {
                System.out.println("[Blocks] " + b.id() + " cleanup threw: " + e);
            }
        }
    }

    public int size() { return blocks.size(); }
    public int activeCount() { return active.size(); }
}
