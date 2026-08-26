package com.mindpalace.genetics;

/**
 * SonicFitness — scores a rendered audio buffer for "does it sound good".
 *
 * Pure signal metrics, no dependency on the synth engine. The score combines
 * weighted terms into a single 0..1 fitness value (higher = better):
 *
 *   + loudness   — RMS energy, penalized if too quiet OR clipping.
 *   - centroid   — spectral centroid (FFT). High centroid = harsh/buzzy
 *                  high-frequency content; low = dull. Rewards a warm mid.
 *   + steadiness — low onset density (a flowing pad, not a machine-gun arp).
 *   + novelty    — distance from a reference buffer (novelty search).
 *   + target     — similarity to a desired target sound (target matching).
 *
 * The harshness term is now a real FFT spectral centroid (per the genetic-audio
 * recipe) instead of the old zero-crossing proxy — it correctly penalizes the
 * "fast oscillating high-pitch buzz" bug class AND the dull low-end collapse.
 */
public final class SonicFitness {

    // Weights (sum to ~1.0 for a normalized score).
    private float wLoudness = 0.30f;
    private float wCentroid = 0.35f;
    private float wSteadiness = 0.20f;
    private float wNovelty = 0.15f;
    private float wTarget = 0.0f;   // 0 unless target matching is requested

    /** Sample rate of the buffers being scored (for the FFT centroid). */
    private int sampleRate = 8000;

    public SonicFitness() {}

    public SonicFitness(float wLoud, float wCentroid, float wSteady, float wNovel) {
        this.wLoudness = wLoud; this.wCentroid = wCentroid;
        this.wSteadiness = wSteady; this.wNovelty = wNovel;
    }

    public void setSampleRate(int sr) { this.sampleRate = sr; }

    /** Enable target matching with the given weight (0 disables it). */
    public void setTargetWeight(float w) { this.wTarget = w; }

    // ── Live controls (step 13) ──

    public void setLoudnessWeight(float w) { this.wLoudness = w; }
    public void setCentroidWeight(float w) { this.wCentroid = w; }
    public void setSteadinessWeight(float w) { this.wSteadiness = w; }
    public void setNoveltyWeight(float w) { this.wNovelty = w; }
    public float loudnessWeight() { return wLoudness; }
    public float centroidWeight() { return wCentroid; }
    public float steadinessWeight() { return wSteadiness; }
    public float noveltyWeight() { return wNovelty; }
    public float targetWeight() { return wTarget; }

    /** Score a buffer in isolation (novelty + target terms = 0). */
    public float score(float[] samples) {
        return score(samples, null, null);
    }

    /** Score a buffer against a reference (novelty) and/or a target (similarity). */
    public float score(float[] samples, float[] reference, float[] target) {
        if (samples == null || samples.length == 0) return 0f;

        // ── Loudness: RMS, with a sweet-spot (not silent, not clipping) ──
        double sumSq = 0.0;
        double peak = 0.0;
        for (float s : samples) {
            sumSq += (double) s * s;
            double a = Math.abs(s);
            if (a > peak) peak = a;
        }
        double rms = Math.sqrt(sumSq / samples.length);
        double loud = 1.0 - Math.abs(rms - 0.15) / 0.15;
        if (peak > 0.95) loud *= 0.3; // clipping penalty
        loud = clamp01(loud);

        // ── Centroid: FFT spectral centroid (warm mid = good, buzz = bad) ──
        float centroid = FFT.centroid(samples, sampleRate);
        // Ideal centroid ~800 Hz (warm, melodic). Buzz >3 kHz, dull <150 Hz.
        double cent = 1.0 - Math.abs(centroid - 800.0) / 800.0;
        cent = clamp01(cent);

        // ── Steadiness: onset density (how many sharp attacks per window) ──
        int onsets = 0;
        for (int i = 1; i < samples.length; i++) {
            if (samples[i] - samples[i - 1] > 0.25) onsets++;
        }
        double onsetRate = (double) onsets / samples.length;
        double steady = 1.0 - clamp01(onsetRate / 0.10);

        // ── Novelty: mean absolute difference from reference (reward different) ──
        double novelty = 0.0;
        if (reference != null && reference.length == samples.length) {
            double diff = 0.0;
            for (int i = 0; i < samples.length; i++) {
                diff += Math.abs(samples[i] - reference[i]);
            }
            novelty = clamp01(diff / samples.length / 0.5);
        }

        // ── Target similarity: reward being CLOSE to a desired sound ──
        double targetSim = 0.0;
        if (target != null && target.length == samples.length) {
            double diff = 0.0;
            for (int i = 0; i < samples.length; i++) {
                diff += Math.abs(samples[i] - target[i]);
            }
            targetSim = 1.0 - clamp01(diff / samples.length / 0.5);
        }

        return (float) clamp01(
            wLoudness * loud + wCentroid * cent + wSteadiness * steady
            + wNovelty * novelty + wTarget * targetSim);
    }

    /** Convenience: score against a reference for novelty only. */
    public float score(float[] samples, float[] reference) {
        return score(samples, reference, null);
    }

    private static double clamp01(double v) { return Math.max(0.0, Math.min(1.0, v)); }
}
