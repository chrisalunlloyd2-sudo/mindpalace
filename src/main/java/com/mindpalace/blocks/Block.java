package com.mindpalace.blocks;

/** MP-20 block-assembly architecture: one Block = one self-contained visual
 * sector ("byte-code sector"), minted with a stable id, self-tested BEFORE it
 * may fire (the bool-false law: a component generates hidden, proves itself,
 * then reveals), and composed by BlockRegistry at the single bloom-apply seam.
 *
 * v1 contract is pure-stdlib + JOML-free so the whole package compiles under
 * any toolchain. Blocks that later need GL (billboards, particles) grow a
 * render() hook internally -- the registry stays lean.
 *
 * Composition law (never races): region/quorum set the base luma, then
 * applyLuma() lets each block layer its term ON TOP, capped [0,2] at the seam.
 * Nothing deleted, only advanced: a failed self-test HIDES a block (inactive),
 * it is never removed from the registry. */
public interface Block {

    /** Stable block id, e.g. "mp-032-fire". Never reused, never deleted. */
    String id();

    /** One-shot deterministic self-probes. false = the block stays hidden
     * (registered but inactive) until it is fixed -- bool-false init law. */
    boolean selfTest();

    /** Advance internal state. dt may be 0; NaN/negative dt must be ignored
     * (defensive: a block never blows up the frame it rides in). */
    void update(float dt, float playerX, float playerZ, float regionBaseLuma);

    /** Layer this block's luma contribution onto the chain value. */
    float applyLuma(float luma);

    /** Idempotent: releasing twice must be as safe as once (nothing-runs-forever). */
    void cleanup();
}
