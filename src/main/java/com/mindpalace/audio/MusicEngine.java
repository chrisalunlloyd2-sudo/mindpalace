package com.mindpalace.audio;

import javax.sound.sampled.*;
import java.util.concurrent.atomic.*;

/**
 * Procedural music engine — generates an endless ambient soundtrack in real
 * time (no audio files, no MIDI assets). A step-sequencer drives a chord
 * progression with a soft pad, an arpeggio, a bass line, and an optional
 * beat. Key, scale, tempo, and mood are live-tunable from the Beats StudioLab.
 *
 * Synthesis is 16-bit / 44.1 kHz mono (higher fidelity than the 8-bit SFX
 * engine, since music is continuous and quality matters).
 */
public class MusicEngine {
    private static final int SAMPLE_RATE = 44100;
    private static final int STEPS_PER_BAR = 16;   // 16th-note grid

    // Live-tunable state (atomic so the audio thread reads a consistent view).
    private final AtomicInteger rootMidi = new AtomicInteger(57); // A3
    private final AtomicInteger tempoBpm = new AtomicInteger(72);
    private final AtomicBoolean beatEnabled = new AtomicBoolean(false);
    private final AtomicBoolean playing = new AtomicBoolean(false);
    private final AtomicBoolean enabled = new AtomicBoolean(true);
    private final AtomicInteger volumePct = new AtomicInteger(50);

    private volatile String scale = "minor";
    private volatile int[] progression = {0, 5, 2, 6}; // i–VI–III–VII (minor)

    private volatile Thread thread;
    private volatile SourceDataLine line;

    // ── Scale definitions (semitone offsets from root) ──
    private static final int[] MAJOR     = {0, 2, 4, 5, 7, 9, 11};
    private static final int[] MINOR     = {0, 2, 3, 5, 7, 8, 10};
    private static final int[] DORIAN    = {0, 2, 3, 5, 7, 9, 10};
    private static final int[] LYDIAN    = {0, 2, 4, 6, 7, 9, 11};
    private static final int[] MIXOLYDIAN= {0, 2, 4, 5, 7, 9, 10};

    public MusicEngine() {
        System.out.println("[Music] Procedural engine ready — key A minor, 72 BPM");
    }

    // ── Public API ──

    public void start() {
        if (playing.getAndSet(true)) return;
        thread = new Thread(this::renderLoop, "mindpalace-music");
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

    public void setKey(int midi) { rootMidi.set(midi); }
    public int getKey() { return rootMidi.get(); }

    public void setTempo(int bpm) { tempoBpm.set(Math.max(40, Math.min(180, bpm))); }
    public int getTempo() { return tempoBpm.get(); }

    public void setBeat(boolean b) { beatEnabled.set(b); }
    public boolean isBeat() { return beatEnabled.get(); }

    public void setScale(String s) {
        scale = s;
        // A sensible default progression per scale family.
        switch (s) {
            case "major":      progression = new int[]{0, 4, 5, 3}; break; // I–V–vi–IV
            case "dorian":     progression = new int[]{0, 3, 4, 1}; break;
            case "lydian":     progression = new int[]{0, 4, 1, 3}; break;
            case "mixolydian": progression = new int[]{0, 3, 4, 0}; break;
            default:           progression = new int[]{0, 5, 2, 6}; break; // minor
        }
    }
    public String getScale() { return scale; }

    /** Mood preset — one call sets tempo + scale + beat for a coherent feel. */
    public void setMood(String mood) {
        switch (mood) {
            case "calm":       setTempo(60); setScale("major");      setBeat(false); break;
            case "mysterious": setTempo(66); setScale("minor");      setBeat(false); break;
            case "energetic":  setTempo(120); setScale("dorian");    setBeat(true);  break;
            case "dreamy":     setTempo(80); setScale("lydian");     setBeat(false); break;
            default: break;
        }
    }

    // ── Synthesis ──

    private int[] scaleOffsets() {
        switch (scale) {
            case "major":      return MAJOR;
            case "dorian":     return DORIAN;
            case "lydian":     return LYDIAN;
            case "mixolydian": return MIXOLYDIAN;
            default:           return MINOR;
        }
    }

    private static double midiHz(int midi) { return 440.0 * Math.pow(2.0, (midi - 69) / 12.0); }

    private void renderLoop() {
        try {
            AudioFormat fmt = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, fmt);
            line = (SourceDataLine) AudioSystem.getLine(info);
            line.open(fmt, SAMPLE_RATE * 2);
            line.start();
        } catch (Exception e) {
            System.err.println("[Music] audio unavailable: " + e.getMessage());
            playing.set(false);
            return;
        }

        int[] scale = scaleOffsets();
        double stepsPerSec = tempoBpm.get() / 60.0 * 4.0; // 16th notes per second
        long step = 0;
        byte[] buf = new byte[2048]; // 1024 samples * 2 bytes

        while (playing.get()) {
            // Re-read live params each buffer (cheap, keeps changes instant).
            int root = rootMidi.get();
            int bpm = tempoBpm.get();
            stepsPerSec = bpm / 60.0 * 4.0;
            scale = scaleOffsets();
            int[] prog = progression;
            boolean beat = beatEnabled.get();
            float vol = (enabled.get() ? volumePct.get() : 0) / 100f;

            for (int i = 0; i < buf.length; i += 2) {
                double t = step / stepsPerSec; // seconds
                int bar = (int) (step / STEPS_PER_BAR);
                int stepInBar = (int) (step % STEPS_PER_BAR);
                int chordIdx = Math.floorMod(bar, prog.length);
                int chordRoot = root + scale[prog[chordIdx]];

                double s = 0.0;

                // Pad — sustained chord tones (root, 3rd, 5th) with slow LFO.
                for (int d = 0; d < 3; d++) {
                    int note = chordRoot + (d == 0 ? 0 : d == 1 ? scale[2] : scale[4]);
                    double hz = midiHz(note);
                    double lfo = 0.7 + 0.3 * Math.sin(2 * Math.PI * 0.1 * t + d);
                    s += Math.sin(2 * Math.PI * hz * t) * 0.10 * lfo;
                }

                // Arpeggio — pluck chord tones in an up-down pattern.
                int[] arpNotes = {chordRoot, chordRoot + scale[2], chordRoot + scale[4],
                                  chordRoot + scale[2] + 12};
                int arpIdx = stepInBar % arpNotes.length;
                double arpH = midiHz(arpNotes[arpIdx]);
                double arpEnv = Math.exp(-3.0 * (stepInBar % 4) / 4.0); // decay per 16th
                s += Math.sin(2 * Math.PI * arpH * t) * 0.12 * arpEnv;

                // Bass — root note an octave down, on the downbeats.
                if (stepInBar % 4 == 0) {
                    double bassH = midiHz(chordRoot - 12);
                    double bassEnv = Math.exp(-2.0 * (stepInBar % 4) / 4.0);
                    s += Math.sin(2 * Math.PI * bassH * t) * 0.18 * bassEnv;
                }

                // Beat — kick on quarters, hat on off-beats.
                if (beat) {
                    if (stepInBar % 4 == 0) {
                        double kickEnv = Math.exp(-18.0 * (stepInBar % 4) / 4.0);
                        s += Math.sin(2 * Math.PI * 55 * t) * 0.30 * kickEnv;
                    }
                    if (stepInBar % 4 == 2) {
                        double hatEnv = Math.exp(-40.0 * (stepInBar % 4) / 4.0);
                        s += (Math.random() - 0.5) * 0.10 * hatEnv;
                    }
                }

                // Soft clip + scale to 16-bit.
                double clamped = Math.max(-1.0, Math.min(1.0, s * vol));
                short sample = (short) (clamped * 32767);
                buf[i] = (byte) (sample & 0xFF);
                buf[i + 1] = (byte) ((sample >> 8) & 0xFF);
                step++;
            }
            // Null-guard: stop() closes/nulls `line` from another thread, so a
            // mid-write teardown would otherwise NPE (the "mindpalace-music"
            // daemon-thread race seen on --selftest shutdown).
            SourceDataLine l = line;
            if (l == null) break;
            l.write(buf, 0, buf.length);
        }
        SourceDataLine closing = line;
        if (closing != null) { closing.close(); line = null; }
    }

    public void cleanup() { stop(); }
}
