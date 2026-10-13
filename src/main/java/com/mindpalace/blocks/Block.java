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

    /** Environmental feed (v1.1): engine hands ambient facts (weather name,
     * aux). Default no-op so v1 blocks stay source-compatible. Active only. */
    default void envUpdate(float dt, String weather, float aux) { }

    /** v1.2 render contract: blocks EMIT data rows (x,y,z,size,extra,KIND) into
     * `out` at (offsetRows + local)*6, up to maxRows, return rows written.
     * KIND selects the engine-side color ramp (0 = ember heat, 1 = firefly glow;
     * new kinds mint additively). Default = 0 rows (non-visual blocks). The
     * ENGINE owns the GL: it converts rows to draw calls at a single seam, so
     * the blocks package stays JOML-free and is provable under any toolchain. */
    default int emitRender(float[] out, int offsetRows, int maxRows) { return 0; }

    /** Layer this block's luma contribution onto the chain value. */
    float applyLuma(float luma);

    /** Idempotent: releasing twice must be as safe as once (nothing-runs-forever). */
    void cleanup();
}
