package com.mindpalace.genetics;

/**
 * PatchSynth — renders an {@link AudioGenome} (128 genes = 16 steps × 8 params)
 * to a mono float[] buffer. This is the "phenotype" of the genetic-audio
 * pipeline: the genome is the genotype, the rendered audio is the sound.
 *
 * Each step is a subtractive voice: two detuned oscillators → one-pole lowpass
 * (cutoff + resonance) → exponential envelope (attack/decay). Steps are
 * sequenced at a fixed tempo (slow & flowing, enforced here rather than left
 * to the GA, which previously drifted to the 240 BPM ceiling).
 *
 * Pure static synthesis — no audio line, no deps. Used both by the offline
 * fitness renderer and (optionally) the live engine.
 */
public final class PatchSynth {

    /** Fixed musical context (not part of the genome). */
    public static final int ROOT_MIDI = 57;      // A3
    public static final int TEMPO_BPM = 90;      // slow & flowing
    public static final int[] SCALE = {0, 2, 3, 5, 7, 8, 10}; // A minor

    private PatchSynth() {}

    /** Render a genome to {@code sampleCount} samples at {@code sampleRate}. */
    public static float[] render(AudioGenome g, int sampleRate, int sampleCount) {
        float[] out = new float[sampleCount];
        double stepsPerSec = TEMPO_BPM / 60.0 * 4.0; // 16th notes
        double stepDur = 1.0 / stepsPerSec;
        int samplesPerStep = Math.max(1, (int) (sampleRate * stepDur));

        for (int s = 0; s < AudioGenome.STEPS; s++) {
            int base = s * AudioGenome.PARAMS_PER_STEP;
            float note = g.genes[base + 0];
            if (note < 0.1f) continue; // rest
            // note 0.1..1.0 → degree 0..7 (0.1 = root, 1.0 = octave).
            int degree = Math.min(7, (int) ((note - 0.1f) / 0.9f * 8f));
            int octave = Math.min(2, (int) (g.genes[base + 1] * 3f));
            float cutoff = cutoffHz(g.genes[base + 2]);
            float reso = g.genes[base + 3];
            float attack = 0.001f + g.genes[base + 4] * 0.5f;
            float decay = 0.05f + g.genes[base + 5] * 1.95f;
            float detune = g.genes[base + 6] * 0.5f; // 0..50 cents
            float level = g.genes[base + 7] * 0.5f;

            // degree 7 = octave (root + 12); 0..6 index the scale.
            int semis = degree >= 7 ? 12 : SCALE[degree];
            int midi = ROOT_MIDI + semis + 12 * octave;
            double hz = 440.0 * Math.pow(2.0, (midi - 69) / 12.0);
            double hz2 = hz * Math.pow(2.0, detune / 1200.0);

            int start = s * samplesPerStep;
            int end = Math.min(sampleCount, start + samplesPerStep * 2); // let decay ring into next step
            renderVoice(out, start, end, sampleRate, hz, hz2, cutoff, reso, attack, decay, level);
        }
        return out;
    }

    /** One-pole lowpass cutoff: gene 0..1 → 200..8000 Hz (log). */
    private static float cutoffHz(float gene) {
        return (float) (200.0 * Math.pow(40.0, Math.max(0f, Math.min(1f, gene))));
    }

    private static void renderVoice(float[] out, int start, int end, int sr,
                                    double hz, double hz2, float cutoff, float reso,
                                    float attack, float decay, float level) {
        double a = 1.0 / (attack * sr);   // attack rate
        double d = 1.0 / (decay * sr);    // decay rate
        // One-pole lowpass coefficient (cutoff → alpha).
        double rc = 1.0 / (2.0 * Math.PI * cutoff);
        double alpha = 1.0 / (1.0 + rc * sr);
        double lp = 0.0;
        double phase = 0.0, phase2 = 0.0;
        double env = 0.0;

        for (int i = start; i < end; i++) {
            double t = (i - start) / (double) sr;
            // Envelope: linear attack, exponential decay.
            if (t < attack) env = t / attack;
            else env = Math.exp(-(t - attack) * d);
            if (t >= attack && env < 1e-4) break; // decayed to silence

            phase += 2.0 * Math.PI * hz / sr;
            phase2 += 2.0 * Math.PI * hz2 / sr;
            double osc = Math.sin(phase) + Math.sin(phase2);
            // One-pole lowpass with resonance (feedback).
            lp = lp + alpha * (osc - lp);
            double resoBoost = 1.0 + reso * 2.0;
            double v = (lp + reso * (osc - lp)) * resoBoost * 0.5;
            out[i] += (float) (v * env * level);
        }
    }
}
