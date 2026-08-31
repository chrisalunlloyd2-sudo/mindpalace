package com.mindpalace.world;

import com.mindpalace.render.Renderer;
import org.joml.Vector3f;

/**
 * BanburismusGauge — the deciban meter, Turing's Bayesian log-odds unit made
 * physical. A wall-mounted dial in the main hall showing the genetic-audio
 * evolution's live evidence: fitness (0–1) is converted to DECIBANS the way
 * Bletchley Park scored Enigma candidates —
 *
 *     decibans = 10 * log10( p / (1 - p) )
 *
 * So 50% fitness = 0 db (no evidence), 90% ≈ +9.5 db (strong evidence),
 * 100% → +30 db (clamped; certainty). The needle swings toward the best
 * genome's score; a thin ghost needle tracks the population mean. When the
 * best score ties the peak, the gauge rim glows — "evidence at maximum."
 *
 * Performance: 1 dial quad, 1 needle line, 2 tick marks, 1 text-free glow —
 * LOD-culled beyond 35m. Nothing allocated per frame beyond small vectors.
 */
public final class BanburismusGauge {

    private static final float LOD_DIST = 35f;
    private static final float DIAL_R = 0.9f;     // dial radius (meters)

    private final Vector3f pos;        // dial center (wall-mounted height)
    private float bestDb = 0f;         // current best in decibans
    private float meanDb = 0f;         // population mean in decibans
    private float peakDb = 0f;         // all-time peak (rim glow when == best)

    public BanburismusGauge(Vector3f pos) {
        this.pos = new Vector3f(pos);
    }

    /** The Turing/Bletchley conversion: probability (0..1) → decibans. */
    public static float toDecibans(float p) {
        p = Math.max(0.02f, Math.min(0.995f, p));   // clamp before the log explodes
        return (float) (10.0 * Math.log10(p / (1.0f - p)));
    }

    /** Feed live scores (0..1 fitness). Cheap: two conversions + clamp. */
    public void update(float bestFitness, float meanFitness) {
        bestDb = toDecibans(bestFitness);
        meanDb = toDecibans(meanFitness);
        if (bestDb > peakDb) peakDb = bestDb;
    }

    /** Current readings (for tests / telemetry). */
    public float bestDecibans() { return bestDb; }
    public float meanDecibans() { return meanDb; }
    public float peakDecibans() { return peakDb; }

    /**
     * Render the dial. The needle maps decibans [-10, +30] to sweep [-60°, +60°]
     * around vertical-up. Gold needle = best; grey ghost = mean; rim glows
     * cyan when best == peak (evidence at maximum).
     */
    public void render(Renderer r, Vector3f camPos, double time) {
        if (camPos.distance(pos) > LOD_DIST) return;

        float sweep = 120f;                 // total needle sweep in degrees
        float dbMin = -10f, dbMax = 30f;    // deciban scale range
        float frac = Math.max(0f, Math.min(1f, (bestDb - dbMin) / (dbMax - dbMin)));
        float angle = -60f + frac * sweep;   // degrees from vertical-up
        float meanFrac = Math.max(0f, Math.min(1f, (meanDb - dbMin) / (dbMax - dbMin)));
        float meanAngle = -60f + meanFrac * sweep;

        // Dial face — dark disc
        r.drawCubeColor(pos, new Vector3f(DIAL_R * 2f, DIAL_R * 2f, 0.06f),
            0.05f, 0.05f, 0.08f);

        // Rim — cyan glow when best is at the historical peak
        boolean atPeak = Math.abs(bestDb - peakDb) < 0.05f;
        float rimGlow = atPeak ? 0.7f + 0.3f * (float) Math.sin(time * 4.0) : 0.35f;
        r.drawCubeColor(new Vector3f(pos.x, pos.y + DIAL_R * 0.95f, pos.z),
            new Vector3f(DIAL_R * 1.9f, 0.08f, 0.08f),
            atPeak ? 0.2f * rimGlow : 0.15f,
            atPeak ? 0.9f * rimGlow : 0.4f,
            atPeak ? 1.0f * rimGlow : 0.5f);

        // Needle (gold) — from dial center, pointing at the best decibans
        double rad = Math.toRadians(angle - 90f);   // -90 => vertical-up
        Vector3f tip = new Vector3f(
            pos.x + (float) Math.cos(rad) * DIAL_R * 0.85f,
            pos.y + (float) Math.sin(rad) * DIAL_R * 0.85f,
            pos.z + 0.05f);
        r.drawLine(new Vector3f(pos.x, pos.y, pos.z + 0.05f), tip,
            0.04f, 1.0f, 0.85f, 0.2f);

        // Ghost needle (grey) — population mean
        double mrad = Math.toRadians(meanAngle - 90f);
        Vector3f mTip = new Vector3f(
            pos.x + (float) Math.cos(mrad) * DIAL_R * 0.6f,
            pos.y + (float) Math.sin(mrad) * DIAL_R * 0.6f,
            pos.z + 0.04f);
        r.drawLine(new Vector3f(pos.x, pos.y, pos.z + 0.04f), mTip,
            0.02f, 0.5f, 0.5f, 0.55f);
    }

    public Vector3f position() { return new Vector3f(pos); }
}