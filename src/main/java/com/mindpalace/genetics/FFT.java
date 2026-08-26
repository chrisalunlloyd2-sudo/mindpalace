package com.mindpalace.genetics;

/**
 * FFT — a minimal radix-2 complex FFT (iterative Cooley-Tukey) used to compute
 * the spectral centroid for SonicFitness. No external deps; power-of-2 only.
 */
public final class FFT {

    private FFT() {}

    /** In-place radix-2 FFT. re/im are length N (power of 2). */
    public static void fft(float[] re, float[] im) {
        int n = re.length;
        // Bit-reversal permutation.
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                float tr = re[i]; re[i] = re[j]; re[j] = tr;
                float ti = im[i]; im[i] = im[j]; im[j] = ti;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2.0 * Math.PI / len;
            float wr = (float) Math.cos(ang), wi = (float) Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                float cr = 1f, ci = 0f;
                for (int k = 0; k < len / 2; k++) {
                    int a = i + k, b = i + k + len / 2;
                    float tr = re[b] * cr - im[b] * ci;
                    float ti = re[b] * ci + im[b] * cr;
                    re[b] = re[a] - tr; im[b] = im[a] - ti;
                    re[a] += tr; im[a] += ti;
                    float ncr = cr * wr - ci * wi;
                    ci = cr * wi + ci * wr;
                    cr = ncr;
                }
            }
        }
    }

    /**
     * Spectral centroid (Hz) of a mono buffer at the given sample rate.
     * Uses a Hann-windowed power-of-2 slice (up to 4096 samples) from the
     * buffer's midpoint. Returns 0 for silence.
     */
    public static float centroid(float[] samples, int sampleRate) {
        int n = samples.length;
        if (n < 8) return 0f;
        int fftN = 1;
        while (fftN * 2 <= n && fftN < 4096) fftN <<= 1;
        int start = (n - fftN) / 2;
        float[] re = new float[fftN];
        float[] im = new float[fftN];
        for (int i = 0; i < fftN; i++) {
            double w = 0.5 * (1.0 - Math.cos(2.0 * Math.PI * i / (fftN - 1)));
            re[i] = (float) (samples[start + i] * w);
        }
        fft(re, im);
        double num = 0.0, den = 0.0;
        for (int k = 1; k <= fftN / 2; k++) {
            double mag = re[k] * re[k] + im[k] * im[k];
            double freq = (double) k * sampleRate / fftN;
            num += freq * mag;
            den += mag;
        }
        return den < 1e-9 ? 0f : (float) (num / den);
    }
}
