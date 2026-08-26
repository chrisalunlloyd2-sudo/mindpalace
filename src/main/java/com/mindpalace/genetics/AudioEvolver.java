package com.mindpalace.genetics;

import java.util.*;

/**
 * AudioEvolver — the genetic-algorithm loop over AudioGenome patches.
 *
 * Population of N genomes → render + score each → tournament selection with an
 * elite set → blend crossover + gaussian mutation → next generation. The
 * "render" step is injected (a function from genome → float[] samples) so the
 * evolver stays decoupled from the MusicEngine; the game wires it to a real
 * synth render, tests wire it to a stub.
 *
 * This is the engine that turns the agents' autonomous work loop into a
 * background GA: each 5-minute cycle = one generation, and the fittest patch
 * becomes the next DePIN shop inventory item.
 */
public final class AudioEvolver {

    /** Renders a genome to a mono float[] buffer (injected). */
    public interface Renderer {
        float[] render(AudioGenome g);
    }

    private final Random rng;
    private final SonicFitness fitness;
    private final Renderer renderer;
    private final int populationSize;
    private final int eliteCount;
    private float mutationSigma;   // strength: how far a mutated gene jumps (mutable)
    private float mutationRate;    // probability each gene mutates (mutable)

    private List<AudioGenome> population;
    private AudioGenome best;
    private float bestScore = -1f;
    private int generation = 0;
    private float meanScore = 0f;                       // avg fitness of current pop
    private final List<Float> bestHistory = new ArrayList<>(); // best score per generation

    public AudioEvolver(Random rng, SonicFitness fitness, Renderer renderer,
                        int populationSize, int eliteCount, float mutationSigma, float mutationRate) {
        this.rng = rng;
        this.fitness = fitness;
        this.renderer = renderer;
        this.populationSize = populationSize;
        this.eliteCount = Math.min(eliteCount, populationSize);
        this.mutationSigma = mutationSigma;
        this.mutationRate = mutationRate;
        this.population = new ArrayList<>(populationSize);
        for (int i = 0; i < populationSize; i++) population.add(AudioGenome.random(rng));
    }

    public int generation() { return generation; }
    public AudioGenome best() { return best; }
    public float bestScore() { return bestScore; }
    public float meanScore() { return meanScore; }
    public int populationSize() { return populationSize; }
    public float mutationSigma() { return mutationSigma; }
    public float mutationRate() { return mutationRate; }
    /** Best fitness per generation (index 0 = gen 1). */
    public List<Float> bestHistory() { return bestHistory; }

    // ── Live controls (step 13) ──

    public void setMutationSigma(float s) { this.mutationSigma = Math.max(0f, Math.min(1f, s)); }
    public void setMutationRate(float r) { this.mutationRate = Math.max(0f, Math.min(1f, r)); }

    /**
     * Refresh the population with random newcomers to avoid stagnation: replace
     * the worst {@code count} genomes with fresh random ones (elite untouched).
     */
    public void refreshPopulation(int count) {
        if (count <= 0) return;
        // Sort current population by fitness (re-evaluate cheaply against best).
        float[] scores = new float[population.size()];
        float[] ref = best == null ? null : renderer.render(best);
        for (int i = 0; i < population.size(); i++) {
            scores[i] = fitness.score(renderer.render(population.get(i)), ref);
        }
        Integer[] order = new Integer[population.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> Float.compare(scores[b], scores[a]));
        // Replace the worst `count` (from the end), never the elite.
        int replace = Math.min(count, population.size() - eliteCount);
        for (int i = 0; i < replace; i++) {
            int worstIdx = order[population.size() - 1 - i];
            population.set(worstIdx, AudioGenome.random(rng));
        }
    }

    /** Run one generation: evaluate, select, breed. Returns the new best. */
    public AudioGenome step() {
        // Evaluate.
        float[] scores = new float[population.size()];
        float[] ref = best == null ? null : renderer.render(best);
        for (int i = 0; i < population.size(); i++) {
            float[] buf = renderer.render(population.get(i));
            scores[i] = fitness.score(buf, ref);
        }

        // Track best (elite) + population mean.
        int bestIdx = 0;
        float sum = 0f;
        for (int i = 0; i < scores.length; i++) {
            if (scores[i] > scores[bestIdx]) bestIdx = i;
            sum += scores[i];
        }
        meanScore = sum / scores.length;
        if (scores[bestIdx] > bestScore) {
            bestScore = scores[bestIdx];
            best = population.get(bestIdx);
        }
        bestHistory.add(bestScore);

        // Sort by score descending; keep elite set.
        Integer[] order = new Integer[population.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> Float.compare(scores[b], scores[a]));

        List<AudioGenome> next = new ArrayList<>(populationSize);
        for (int i = 0; i < eliteCount; i++) next.add(population.get(order[i]));

        // Breed the rest via tournament selection + crossover + mutation.
        while (next.size() < populationSize) {
            AudioGenome pa = tournament(population, scores, order);
            AudioGenome pb = tournament(population, scores, order);
            AudioGenome child = AudioGenome.crossover(pa, pb, rng).mutate(rng, mutationSigma, mutationRate);
            next.add(child);
        }

        population = next;
        generation++;
        return best;
    }

    /** Tournament selection: pick the fittest of k random candidates. */
    private AudioGenome tournament(List<AudioGenome> pop, float[] scores, Integer[] order) {
        int k = 3;
        int bestIdx = rng.nextInt(pop.size());
        for (int i = 1; i < k; i++) {
            int cand = rng.nextInt(pop.size());
            if (scores[cand] > scores[bestIdx]) bestIdx = cand;
        }
        return pop.get(bestIdx);
    }
}
