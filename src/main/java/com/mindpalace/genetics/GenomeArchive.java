package com.mindpalace.genetics;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;

/**
 * GenomeArchive — persists the best genome + its rendered audio every
 * generation, so evolution is auditable and survives restarts.
 *
 * Each generation writes two files under <dataDir>/evolution/:
 *   gen-<N>.json  — the fittest genome (9 genes + fitness + generation)
 *   gen-<N>.wav   — a rendered clip of that genome (16-bit mono PCM)
 * plus a rolling best.json / best.wav for the current champion.
 *
 * WAV is written by hand (44-byte header + PCM samples) to avoid any audio
 * dependency — the same samples the SonicFitness scored.
 */
public final class GenomeArchive {

    private final Path dir;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public GenomeArchive(Path dataDir) {
        this.dir = dataDir.resolve("evolution");
    }

    /** Save the best genome + its rendered audio for a generation. */
    public synchronized void save(int generation, AudioGenome genome, float fitness, float[] audio) {
        try {
            Files.createDirectories(dir);
            String tag = "gen-" + generation;

            // Genome JSON.
            JsonObject o = new JsonObject();
            o.addProperty("generation", generation);
            o.addProperty("fitness", fitness);
            JsonArray genes = new JsonArray();
            for (float g : genome.genes) genes.add(g);
            o.add("genes", genes);
            Files.writeString(dir.resolve(tag + ".json"), gson.toJson(o));

            // Rendered audio WAV.
            if (audio != null && audio.length > 0) {
                Files.write(dir.resolve(tag + ".wav"), toWav(audio));
            }

            // Rolling champion.
            Files.writeString(dir.resolve("best.json"), gson.toJson(o));
            if (audio != null && audio.length > 0) {
                Files.write(dir.resolve("best.wav"), toWav(audio));
            }
        } catch (Exception ignored) {
            // Best-effort archive; evolution continues in memory regardless.
        }
    }

    /** Encode a mono float[] (-1..1) as a 16-bit PCM WAV (44.1 kHz). */
    private static byte[] toWav(float[] samples) {
        int dataLen = samples.length * 2;
        byte[] out = new byte[44 + dataLen];
        int sr = 44100;
        // RIFF header
        writeAscii(out, 0, "RIFF");
        writeIntLE(out, 4, 36 + dataLen);
        writeAscii(out, 8, "WAVE");
        // fmt chunk
        writeAscii(out, 12, "fmt ");
        writeIntLE(out, 16, 16);          // fmt chunk size
        writeShortLE(out, 20, 1);          // PCM
        writeShortLE(out, 22, 1);          // mono
        writeIntLE(out, 24, sr);           // sample rate
        writeIntLE(out, 28, sr * 2);       // byte rate
        writeShortLE(out, 32, 2);          // block align
        writeShortLE(out, 34, 16);         // bits per sample
        // data chunk
        writeAscii(out, 36, "data");
        writeIntLE(out, 40, dataLen);
        for (int i = 0; i < samples.length; i++) {
            float v = Math.max(-1f, Math.min(1f, samples[i]));
            short s = (short) (v * 32767);
            out[44 + i * 2] = (byte) (s & 0xFF);
            out[45 + i * 2] = (byte) ((s >> 8) & 0xFF);
        }
        return out;
    }

    private static void writeAscii(byte[] b, int off, String s) {
        for (int i = 0; i < s.length(); i++) b[off + i] = (byte) s.charAt(i);
    }
    private static void writeIntLE(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xFF); b[off+1] = (byte) ((v>>8)&0xFF);
        b[off+2] = (byte) ((v>>16)&0xFF); b[off+3] = (byte) ((v>>24)&0xFF);
    }
    private static void writeShortLE(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xFF); b[off+1] = (byte) ((v>>8)&0xFF);
    }
}
