package com.mindpalace.genetics;

import java.util.Random;

/**
 * AudioGenome — a synth patch encoded as a 128-float gene vector.
 *
 * The genome is a 16-step sequence; each step carries 8 parameters, so every
 * float does real work (16 × 8 = 128). This is the "128-float vector
 * controlling a synth" representation from the genetic-audio recipe — a proper
 * high-dimensional patch space, not a handful of global knobs.
 *
 * Per-step layout (index within a step → meaning, all genes 0..1):
 *   0  note      — scale degree; <0.1 = rest, else floor(v*8) mod 8 (0..7)
 *   1  octave    — 0..2 (adds 12*octave semitones)
 *   2  cutoff    — one-pole lowpass cutoff (log 200..8000 Hz)
 *   3  resonance — filter brightness / Q
 *   4  attack    — 0.001..0.5 s
 *   5  decay     — 0.05..2.0 s
 *   6  detune    — 0..50 cents (second oscillator thickness)
 *   7  level     — amplitude 0..0.5
 *
 * Global synth params (root key, scale, tempo) are NOT in the genome — they
 * are fixed by the synth so the GA explores the patch space, not the tempo
 * (which previously drifted to the 240 BPM ceiling). "Slow & flowing" is
 * enforced by the fitness target instead.
 */
public final class AudioGenome {

    public static final int STEPS = 16;
    public static final int PARAMS_PER_STEP = 8;
    public static final int GENE_COUNT = STEPS * PARAMS_PER_STEP; // 128

    /** All genes are 0..1. */
    private static final float LO = 0f, HI = 1f;

    public final float[] genes;

    public AudioGenome(float[] genes) {
        if (genes.length != GENE_COUNT) throw new IllegalArgumentException("bad gene count");
        this.genes = genes.clone();
    }

    /** A sensible default patch: a slow, flowing minor arpeggio. */
    public static AudioGenome defaultPatch() {
        float[] g = new float[GENE_COUNT];
        // A gentle 8-note phrase over 16 steps (rests on the off-beats).
        int[] degrees = {0, 2, 4, 2, 3, 4, 7, 4, 0, 2, 4, 7, 4, 2, 0, -1};
        for (int s = 0; s < STEPS; s++) {
            int base = s * PARAMS_PER_STEP;
            int deg = degrees[s];
            // note 0.1..1.0 → degree 0..7; 0.0 = rest.
            g[base + 0] = deg < 0 ? 0.0f : 0.1f + (deg / 7f) * 0.9f; // note
            g[base + 1] = 0.5f;   // octave 1
            g[base + 2] = 0.6f;   // cutoff ~1.5 kHz
            g[base + 3] = 0.3f;   // resonance
            g[base + 4] = 0.2f;   // soft attack
            g[base + 5] = 0.5f;   // medium decay
            g[base + 6] = 0.2f;   // slight detune
            g[base + 7] = deg < 0 ? 0.0f : 0.4f; // level
        }
        return new AudioGenome(g);
    }

    /** A random genome (all genes uniform 0..1). */
    public static AudioGenome random(Random rng) {
        float[] g = new float[GENE_COUNT];
        for (int i = 0; i < GENE_COUNT; i++) g[i] = rng.nextFloat();
        return new AudioGenome(g);
    }

    /** Blend crossover: child = alpha*A + (1-alpha)*B, clamped to [0,1]. */
    public static AudioGenome crossover(AudioGenome a, AudioGenome b, Random rng) {
        float alpha = rng.nextFloat();
        float[] g = new float[GENE_COUNT];
        for (int i = 0; i < GENE_COUNT; i++) {
            g[i] = clamp(alpha * a.genes[i] + (1f - alpha) * b.genes[i], LO, HI);
        }
        return new AudioGenome(g);
    }

    /**
     * Gaussian mutation. Each gene is perturbed with probability {@code rate}
     * by N(0, sigma), then clamped to [0,1]. Rate = how many genes change
     * (exploration breadth); sigma = how far each jumps (strength).
     */
    public AudioGenome mutate(Random rng, float sigma, float rate) {
        float[] g = genes.clone();
        for (int i = 0; i < GENE_COUNT; i++) {
            if (rng.nextFloat() >= rate) continue;
            g[i] = clamp(g[i] + (float) rng.nextGaussian() * sigma, LO, HI);
        }
        return new AudioGenome(g);
    }

    private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }

    @Override
    public String toString() {
        // Summarize: count active steps + mean level + mean cutoff.
        int active = 0; float lvl = 0f, cut = 0f;
        for (int s = 0; s < STEPS; s++) {
            int b = s * PARAMS_PER_STEP;
            if (genes[b] >= 0.1f) active++;
            lvl += genes[b + 7]; cut += genes[b + 2];
        }
        return String.format("steps=%d/16 lvl=%.2f cut=%.2f", active, lvl / STEPS, cut / STEPS);
    }
}
