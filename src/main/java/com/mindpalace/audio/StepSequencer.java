package com.mindpalace.audio;

import javax.sound.sampled.*;
import java.util.concurrent.atomic.*;

/**
 * StepSequencer — a 16-step drum/bass machine (the "FL-style" half of the
 * Beats StudioLab). A 4-channel × 16-step pattern grid you toggle live; a
 * playback thread steps through the grid in sync with the tempo and triggers
 * a short synthesized hit (kick / snare / hat / bass) on every active cell.
 *
 * Runs alongside MusicEngine (the ambient pad/arpeggio layer) so the player
 * can layer a beat on top of the soundscape, exactly like a step-sequencer
 * layered under a pad in a DAW.
 *
 * Synthesis is 16-bit / 44.1 kHz mono — same path as MusicEngine, kept
 * separate so the sequencer is a self-contained instrument.
 */
public class StepSequencer {
    public static final int CHANNELS = 4;
    public static final int STEPS = 16;

    /** Channel names, in row order (top → bottom in the grid). */
    public static final String[] CHANNEL_NAMES = {"Kick", "Snare", "Hat", "Bass"};

    private static final int SAMPLE_RATE = 44100;
    private static final int BUF_BYTES = 2048;        // 1024 samples

    private final boolean[][] grid = new boolean[CHANNELS][STEPS];
    private final AtomicInteger tempoBpm = new AtomicInteger(72);
    private final AtomicBoolean enabled = new AtomicBoolean(true);
    private final AtomicBoolean playing = new AtomicBoolean(false);
    private final AtomicInteger volumePct = new AtomicInteger(50);

    private volatile int playhead;                    // current step (read by UI)
    private volatile Thread thread;
    private volatile SourceDataLine line;

    public StepSequencer() {
        // Seed a sensible default pattern so it sounds alive out of the box:
        // kick on quarters, snare on 2 & 4, hats on the off-beats, bass on the root.
        for (int s = 0; s < STEPS; s++) {
            if (s % 4 == 0)                 grid[0][s] = true; // kick
            if (s % 8 == 4)                 grid[1][s] = true; // snare
            if (s % 2 == 1)                 grid[2][s] = true; // hat (off-beats)
            if (s % 4 == 0 || s % 4 == 3)   grid[3][s] = true; // bass
        }
        System.out.println("[Sequencer] 16-step machine ready — kick/snare/hat/bass");
    }

    // ── Public API ──

    public void start() {
        if (playing.getAndSet(true)) return;
        thread = new Thread(this::renderLoop, "mindpalace-seq");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        playing.set(false);
        if (line != null) { line.close(); line = null; }
    }

    public void setEnabled(boolean e) { enabled.set(e); }
    public boolean isEnabled() { return enabled.get(); }

    public void setVolume(float v) { volumePct.set((int) (Math.max(0, Math.min(1, v)) * 100)); }
    public float getVolume() { return volumePct.get() / 100f; }

    public void setTempo(int bpm) { tempoBpm.set(Math.max(40, Math.min(180, bpm))); }
    public int getTempo() { return tempoBpm.get(); }

    /** Toggle a cell (returns the new state). */
    public boolean toggle(int channel, int step) {
        boolean v = !grid[channel][step];
        grid[channel][step] = v;
        return v;
    }
    public boolean get(int channel, int step) { return grid[channel][step]; }

    /** Clear the whole pattern. */
    public void clear() {
        for (int c = 0; c < CHANNELS; c++)
            for (int s = 0; s < STEPS; s++) grid[c][s] = false;
    }

    /** Current playback step (0..15), for the UI playhead. */
    public int getPlayhead() { return playhead; }

    // ── Synthesis ──

    private void renderLoop() {
        try {
            AudioFormat fmt = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, fmt);
            line = (SourceDataLine) AudioSystem.getLine(info);
            line.open(fmt, SAMPLE_RATE * 2);
            line.start();
        } catch (Exception e) {
            System.err.println("[Sequencer] audio unavailable: " + e.getMessage());
            playing.set(false);
            return;
        }

        double t = 0.0;
        int lastStep = -1;
        // Trigger time (seconds) per channel, so a hit's envelope can decay.
        double[] hitStart = new double[CHANNELS];
        java.util.Arrays.fill(hitStart, -1.0);
        byte[] buf = new byte[BUF_BYTES];

        while (playing.get()) {
            int bpm = tempoBpm.get();
            double stepsPerSec = bpm / 60.0 * 4.0;       // 16th notes per second
            double stepDur = 1.0 / stepsPerSec;
            float vol = (enabled.get() ? volumePct.get() : 0) / 100f;

            for (int i = 0; i < buf.length; i += 2) {
                t += 1.0 / SAMPLE_RATE;
                int step = (int) (t * stepsPerSec);
                if (step != lastStep) {
                    // Step boundary: trigger any active cell in this step.
                    lastStep = step;
                    playhead = step % STEPS;
                    for (int c = 0; c < CHANNELS; c++)
                        if (grid[c][playhead]) hitStart[c] = t;
                }

                double s = 0.0;
                for (int c = 0; c < CHANNELS; c++) {
                    double dt = t - hitStart[c];
                    if (dt < 0 || dt > 0.30) continue;    // hit already decayed
                    s += synth(c, dt);
                }

                double clamped = Math.max(-1.0, Math.min(1.0, s * vol));
                short sample = (short) (clamped * 32767);
                buf[i] = (byte) (sample & 0xFF);
                buf[i + 1] = (byte) ((sample >> 8) & 0xFF);
            }
            SourceDataLine l = line;
            if (l == null) break;   // stop() tore down the line mid-loop
            l.write(buf, 0, buf.length);
        }
        SourceDataLine closing = line;
        if (closing != null) { closing.close(); line = null; }
    }

    /** A single channel's synthesized sample at time dt since trigger. */
    private double synth(int channel, double dt) {
        switch (channel) {
            case 0: { // Kick — sine sweep 90→45 Hz, fast decay
                double f = 45.0 + 45.0 * Math.exp(-30.0 * dt);
                return Math.sin(2 * Math.PI * f * dt) * 0.6 * Math.exp(-12.0 * dt);
            }
            case 1: { // Snare — noise burst + a mid tone thump
                double noise = (Math.random() - 0.5) * 0.6;
                double body = Math.sin(2 * Math.PI * 180 * dt) * 0.3;
                return (noise + body) * Math.exp(-20.0 * dt);
            }
            case 2: { // Hat — bright short noise
                return (Math.random() - 0.5) * 0.35 * Math.exp(-60.0 * dt);
            }
            case 3: { // Bass — low sine note (A1 ≈ 55 Hz), slower decay
                return Math.sin(2 * Math.PI * 55 * dt) * 0.5 * Math.exp(-8.0 * dt);
            }
            default:
                return 0.0;
        }
    }

    public void cleanup() { stop(); }
}
