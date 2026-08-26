package com.mindpalace.genetics;

import java.util.*;

/**
 * AudioEvolver — the genetic-algorithm loop over AudioGenome patches.
 *
 * Population of N genomes → render + score each → truncation selection (top-K
 * parents) → blend crossover + gaussian mutation → next generation. The
 * "render" step is injected (a function from genome → float[] samples) so the
 * evolver stays decoupled from the synth; the game wires it to PatchSynth,
 * tests wire it to a stub.
 *
 * This is the "50 genomes, top-10 parents, 40 children" recipe: each
 * generation keeps the top {@code parentCount} unchanged (elite) and breeds
 * {@code populationSize - parentCount} children from random parent pairs.
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
    private final int parentCount;   // top-K kept as elite + breeding pool
    private float mutationSigma;     // strength: how far a mutated gene jumps (mutable)
    private float mutationRate;      // probability each gene mutates (mutable)

    private List<AudioGenome> population;
    private AudioGenome best;
    private float bestScore = -1f;
    private int generation = 0;
    private float meanScore = 0f;                       // avg fitness of current pop
    private final List<Float> bestHistory = new ArrayList<>(); // best score per generation

    public AudioEvolver(Random rng, SonicFitness fitness, Renderer renderer,
                        int populationSize, int parentCount, float mutationSigma, float mutationRate) {
        this.rng = rng;
        this.fitness = fitness;
        this.renderer = renderer;
        this.populationSize = populationSize;
        this.parentCount = Math.min(parentCount, populationSize);
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
    public int parentCount() { return parentCount; }
    public float mutationSigma() { return mutationSigma; }
    public float mutationRate() { return mutationRate; }
    /** Best fitness per generation (index 0 = gen 1). */
    public List<Float> bestHistory() { return bestHistory; }

    // ── Live controls (step 13) ──

    public void setMutationSigma(float s) { this.mutationSigma = Math.max(0f, Math.min(1f, s)); }
    public void setMutationRate(float r) { this.mutationRate = Math.max(0f, Math.min(1f, r)); }

    /**
     * Refresh the population with random newcomers to avoid stagnation: replace
     * the worst {@code count} genomes with fresh random ones (parents untouched).
     */
    public void refreshPopulation(int count) {
        if (count <= 0) return;
        float[] scores = evaluate();
        Integer[] order = sortDesc(scores);
        int replace = Math.min(count, population.size() - parentCount);
        for (int i = 0; i < replace; i++) {
            int worstIdx = order[population.size() - 1 - i];
            population.set(worstIdx, AudioGenome.random(rng));
        }
    }

    /** Run one generation: evaluate, select top-K, breed. Returns the new best. */
    public AudioGenome step() {
        float[] scores = evaluate();

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

        // Truncation selection: sort descending, top-K are the parents.
        Integer[] order = sortDesc(scores);
        List<AudioGenome> parents = new ArrayList<>(parentCount);
        for (int i = 0; i < parentCount; i++) parents.add(population.get(order[i]));

        // New population = top-K elites + (N-K) children from random parent pairs.
        List<AudioGenome> next = new ArrayList<>(populationSize);
        next.addAll(parents);
        while (next.size() < populationSize) {
            AudioGenome pa = parents.get(rng.nextInt(parentCount));
            AudioGenome pb = parents.get(rng.nextInt(parentCount));
            AudioGenome child = AudioGenome.crossover(pa, pb, rng).mutate(rng, mutationSigma, mutationRate);
            next.add(child);
        }

        population = next;
        generation++;
        return best;
    }

    /** Render + score every genome against the current best (novelty ref). */
    private float[] evaluate() {
        float[] scores = new float[population.size()];
        float[] ref = best == null ? null : renderer.render(best);
        for (int i = 0; i < population.size(); i++) {
            scores[i] = fitness.score(renderer.render(population.get(i)), ref);
        }
        return scores;
    }

    private Integer[] sortDesc(float[] scores) {
        Integer[] order = new Integer[scores.length];
        for (int i = 0; i < order.length; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> Float.compare(scores[b], scores[a]));
        return order;
    }
}
