package com.mindpalace.audio;

import javax.sound.sampled.*;
import java.util.concurrent.*;

/**
 * Synthesized audio engine — generates simple waveform sounds.
 * No external audio files needed. Footsteps, door creak, ambient hum.
 */
public class AudioEngine {
    private boolean enabled = true;
    private float masterVolume = 0.5f;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private SourceDataLine ambientLine;
    private boolean ambientPlaying;
    private long lastFootstep;
    private static final long FOOTSTEP_MS = 400;

    private static final int SAMPLE_RATE = 22050;

    public AudioEngine() {
        System.out.println("[Audio] Engine initialized — synthesized sounds ready");
    }

    // ── Public API ──

    public void playFootstep() {
        if (!enabled) return;
        long now = System.currentTimeMillis();
        if (now - lastFootstep < FOOTSTEP_MS) return;
        lastFootstep = now;
        executor.submit(() -> playTone(80 + Math.random() * 40, 0.06f, 0.15f, genNoiseBurst()));
    }

    public void playDoorOpen() {
        if (!enabled) return;
        executor.submit(() -> playTone(60, 0.3f, 0.4f, genSweep(60, 180, 0.3f)));
    }

    public void playBookOpen() {
        if (!enabled) return;
        executor.submit(() -> playTone(300, 0.08f, 0.12f, genNoiseBurst()));
    }

    public void playAmbientStart() {
        if (!enabled || ambientPlaying) return;
        ambientPlaying = true;
        executor.submit(this::ambientLoop);
    }

    public void playAmbientStop() {
        ambientPlaying = false;
        if (ambientLine != null) { ambientLine.close(); ambientLine = null; }
    }

    // ── Synthesizer ──

    private void playTone(double baseHz, float duration, float volume, byte[] samples) {
        try {
            AudioFormat fmt = new AudioFormat(SAMPLE_RATE, 8, 1, true, false);
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, fmt);
            SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info);
            line.open(fmt, samples.length);
            line.start();
            line.write(samples, 0, samples.length);
            line.drain();
            line.close();
        } catch (Exception e) {
            // Audio unavailable — silently skip
        }
    }

    private void ambientLoop() {
        try {
            AudioFormat fmt = new AudioFormat(SAMPLE_RATE, 8, 1, true, false);
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, fmt);
            ambientLine = (SourceDataLine) AudioSystem.getLine(info);
            ambientLine.open(fmt, SAMPLE_RATE);
            ambientLine.start();

            double phase = 0;
            byte[] buf = new byte[1024];
            while (ambientPlaying) {
                for (int i = 0; i < buf.length; i++) {
                    // Quiet low hum — mix of 60Hz + 90Hz
                    double s = Math.sin(phase * 60) * 0.3 + Math.sin(phase * 90) * 0.2;
                    buf[i] = (byte) (s * 15 * masterVolume);
                    phase += 2 * Math.PI / SAMPLE_RATE;
                }
                ambientLine.write(buf, 0, buf.length);
            }
        } catch (Exception ignored) {}
        if (ambientLine != null) { ambientLine.close(); ambientLine = null; }
    }

    // ── Waveform generators ──

    private byte[] genSweep(double startHz, double endHz, float duration) {
        int len = (int) (SAMPLE_RATE * duration);
        byte[] buf = new byte[len];
        for (int i = 0; i < len; i++) {
            double t = (double) i / len;
            double hz = startHz + (endHz - startHz) * t;
            double phase = (i / (double) SAMPLE_RATE) * hz * 2 * Math.PI;
            double env = 1.0 - t; // fade out
            buf[i] = (byte) (Math.sin(phase) * 50 * env * masterVolume);
        }
        return buf;
    }

    private byte[] genNoiseBurst() {
        int len = (int) (SAMPLE_RATE * 0.06);
        byte[] buf = new byte[len];
        for (int i = 0; i < len; i++) {
            double env = 1.0 - (double) i / len;
            buf[i] = (byte) ((Math.random() - 0.5) * 80 * env * masterVolume);
        }
        return buf;
    }

    // ── Volume / state ──

    public void setMasterVolume(float v) { this.masterVolume = Math.max(0, Math.min(1, v)); }
    public void setEnabled(boolean e) { this.enabled = e; }

    public void cleanup() {
        ambientPlaying = false;
        if (ambientLine != null) { ambientLine.close(); ambientLine = null; }
        executor.shutdown();
    }
}
