package com.mindpalace.genetics;

import java.util.Random;

/**
 * AudioGenome — a synth patch encoded as a float gene vector.
 *
 * Each gene is a live-tunable MusicEngine parameter with a [min,max] range.
 * The genome is the "genotype"; rendering it through the MusicEngine produces
 * the "phenotype" (audible sound). This is the parameter-based branch of the
 * genetic-audio pipeline — no neural model, no WAV files, real-time synthesis.
 *
 * Gene layout (index → meaning):
 *   0  root key (MIDI)      48..72
 *   1  tempo (BPM)          30..240
 *   2  scale index          0..4  (minor/major/dorian/lydian/mixolydian)
 *   3  pad level            0..1
 *   4  melody level         0..1
 *   5  bass level           0..1
 *   6  beat on/off          0..1  (>=0.5 = on)
 *   7  envelope attack      0..1
 *   8  envelope decay       0..1
 */
public final class AudioGenome {

    public static final int GENE_COUNT = 9;

    /** Per-gene [min,max] bounds. */
    private static final float[][] RANGES = {
        {48f, 72f},   // root key
        {30f, 240f},  // tempo
        {0f, 4f},     // scale index
        {0f, 1f},     // pad
        {0f, 1f},     // melody
        {0f, 1f},     // bass
        {0f, 1f},     // beat
        {0f, 1f},     // attack
        {0f, 1f},     // decay
    };

    public final float[] genes;

    public AudioGenome(float[] genes) {
        if (genes.length != GENE_COUNT) throw new IllegalArgumentException("bad gene count");
        this.genes = genes.clone();
    }

    /** A sensible default patch (the current shipped sound). */
    public static AudioGenome defaultPatch() {
        return new AudioGenome(new float[]{57f, 120f, 0f, 0.07f, 0.20f, 0.15f, 0f, 0.5f, 0.5f});
    }

    /** A random genome within all bounds. */
    public static AudioGenome random(Random rng) {
        float[] g = new float[GENE_COUNT];
        for (int i = 0; i < GENE_COUNT; i++) {
            g[i] = RANGES[i][0] + rng.nextFloat() * (RANGES[i][1] - RANGES[i][0]);
        }
        return new AudioGenome(g);
    }

    /** Blend crossover: child = alpha*A + (1-alpha)*B, clamped to bounds. */
    public static AudioGenome crossover(AudioGenome a, AudioGenome b, Random rng) {
        float alpha = rng.nextFloat();
        float[] g = new float[GENE_COUNT];
        for (int i = 0; i < GENE_COUNT; i++) {
            g[i] = clamp(alpha * a.genes[i] + (1f - alpha) * b.genes[i], RANGES[i][0], RANGES[i][1]);
        }
        return new AudioGenome(g);
    }

    /**
     * Gaussian mutation. Each gene is perturbed with probability {@code rate}
     * (0..1) by N(0, sigma*range), then clamped to bounds. Rate controls how
     * many genes change per child (exploration); sigma controls how far each
     * changed gene jumps (strength). Low rate + low sigma = stable convergence;
     * high rate + high sigma = chaotic exploration.
     */
    public AudioGenome mutate(Random rng, float sigma, float rate) {
        float[] g = genes.clone();
        for (int i = 0; i < GENE_COUNT; i++) {
            if (rng.nextFloat() >= rate) continue; // leave this gene untouched
            float span = RANGES[i][1] - RANGES[i][0];
            g[i] = clamp(g[i] + (float) rng.nextGaussian() * sigma * span, RANGES[i][0], RANGES[i][1]);
        }
        return new AudioGenome(g);
    }

    private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }

    @Override
    public String toString() {
        return String.format("key=%.0f tempo=%.0f scale=%d pad=%.2f mel=%.2f bass=%.2f beat=%s",
            genes[0], genes[1], (int) genes[2], genes[3], genes[4], genes[5], genes[6] >= 0.5f);
    }
}
