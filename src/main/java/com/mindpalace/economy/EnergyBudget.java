package com.mindpalace.economy;

/**
 * EnergyBudget — the daily energy ledger (H41, NEXT_100_STEPS step 101).
 *
 * AGENTS.md hard rule #5 sets a per-FRAME perf budget for HD 510/945M-class
 * hardware; this is the economy-side complement: a per-DAY allowance that the
 * expensive-but-optional visual/agent systems draw from. When the allowance
 * runs dry the engine DEGRADES GRACEFULLY instead of hard-disabling:
 *
 *   - bloom: region preset clamps to the lowest tier (BLOOM_HALL)
 *   - trees: the forest renders every 2nd tree (density ×0.5)
 *   - scouts: retired scouts stay retired (respawn refused)
 *
 * The next day rollover restores everything. Time is injected everywhere the
 * ledger is decided on, so the selftest can simulate days hermetically (no
 * real clock sleeps), and day rollover is a pure function of the epoch.
 */
public class EnergyBudget {
    /** Allowance for one day of full-fat rendering, in energy units. */
    public static final double ALLOWANCE_PER_DAY = 3600.0;
    /** Length of one energy day (ms). Pure dayIndex(now, DAY_MS) is testable. */
    public static final long DAY_MS = 24L * 60 * 60 * 1000;
    /** Cost of one scout respawn (charged from the same pot). */
    public static final double SCOUT_RESPAWN_COST = 30.0;

    private long dayIndex = -1;      // which energy-day the ledger belongs to
    private double spentToday = 0.0;
    private long lastStampMs = 0L;   // last drip's timestamp (for dt-scaled drips)
    private boolean wasDegraded = false;

    /** Which energy-day does an epoch time belong to? Pure. */
    public static long dayIndex(long nowMs, long dayMs) {
        return Math.floorDiv(nowMs, dayMs);
    }

    /** Roll the ledger on a new day (idempotent within a day). Returns true
     *  when a rollover happened (fresh allowance). */
    public boolean maybeRollover(long nowMs) {
        long today = dayIndex(nowMs, DAY_MS);
        if (today != dayIndex) {
            dayIndex = today;
            spentToday = 0.0;
            lastStampMs = nowMs;
            return true;
        }
        return false;
    }

    /** Advance the day clock WITHOUT spending (call once per frame before any
     *  degrade checks so first-frame rollovers still happen when idle). */
    public void tick(long nowMs) {
        maybeRollover(nowMs);
    }

    /** Spend `units` on `what` if the allowance allows; refused when it would
     *  push the day negative (callers treat refusal = degraded behavior). */
    public synchronized boolean spend(double units, String what) {
        if (units <= 0) return true;
        if (remaining() < units) return false;
        spentToday += units;
        return true;
    }

    /** Frame drip for continuous systems (bloom+trees+scouts). Scaled by dt
     *  so budget burn tracks real seconds of full-fat rendering. */
    public synchronized void drip(double unitsPerSecond, long nowMs) {
        if (lastStampMs == 0) lastStampMs = nowMs;
        long dms = Math.max(0, Math.min(250, nowMs - lastStampMs)); // clamp hitches
        lastStampMs = nowMs;
        spentToday += unitsPerSecond * (dms / 1000.0);
        spentToday = Math.min(spentToday, ALLOWANCE_PER_DAY);
    }

    /** Remaining allowance for the day. */
    public synchronized double remaining() {
        return Math.max(0.0, ALLOWANCE_PER_DAY - spentToday);
    }

    /** True = the day's allowance is spent — systems should degrade. */
    public synchronized boolean degraded() {
        boolean d = remaining() <= 0.0;
        wasDegraded = d;
        return d;
    }

    /** Transition detector (edge): true exactly when `degraded()` changed
     *  since the last call — for one-line telemetry, never per-frame spam. */
    public synchronized boolean degradeEdge() {
        boolean d = degraded();
        boolean e = (d != wasDegraded);
        wasDegraded = d;
        return e;
    }

    /** Scout respawn attempt: refused while degraded (stay retired), charged
     *  SCOUT_RESPAWN_COST otherwise. */
    public synchronized boolean tryRespawnScout() {
        return spend(SCOUT_RESPAWN_COST, "scout_respawn");
    }

    /** One-line ledger readout for HUD/logs. */
    public String line() {
        return String.format("energy %s: %.0f/%.0f units left%s",
            dayIndex < 0 ? "fresh" : ("day " + dayIndex),
            remaining(), ALLOWANCE_PER_DAY, degraded() ? " — DEGRADED" : "");
    }
}