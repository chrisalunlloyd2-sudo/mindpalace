package com.mindpalace.agent.lane;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

/**
 * LaneLifecycle — the lane lives for a round and is let go after it (cellular mic: models fire in series, and an idle
 * resident model is a dead one). Before a call it touches the "wanted" file and, when the lane is down, runs the keeper
 * (which starts it, guarded by RAM and a breaker) and waits until the model has loaded. After a call it schedules one more
 * keeper pass just past the idle window, and the keeper stops a lane nobody has wanted for that long.
 *
 * The keeper is a separate script (~/.aria/engine/lane_keeper.py tick); this class only asks and waits.
 */
public final class LaneLifecycle implements LaneClient.Lifecycle {

    /** -Dmindpalace.lane.wake="python3 -I C:/Users/viper/.aria/engine/lane_keeper.py tick" turns the wake on. Unset = the lane must already be up. */
    public static final String WAKE_PROP = "mindpalace.lane.wake";
    static final long POLL_MS = 5_000, DEFAULT_WAIT_MS = 300_000, IDLE_TICK_MS = 130_000;

    private final Path want;
    private final Runnable tick;
    private final long waitMs, idleTickMs;
    private final LongSupplier clock;
    private final LongConsumer sleeper;
    private final ScheduledExecutorService timer;
    private ScheduledFuture<?> pending;

    public LaneLifecycle(Path want, Runnable tick, long waitMs, long idleTickMs, LongSupplier clock, LongConsumer sleeper) {
        this.want = want;
        this.tick = tick;
        this.waitMs = waitMs;
        this.idleTickMs = idleTickMs;
        this.clock = clock;
        this.sleeper = sleeper;
        this.timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "lane-release");
            t.setDaemon(true);
            return t;
        });
    }

    /** The game's wiring: tick = run the keeper command, do not wait for it. */
    public static LaneLifecycle forCommand(Path want, List<String> keeperCmd) {
        Runnable tick = () -> {
            try {
                new ProcessBuilder(keeperCmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            } catch (IOException e) {
                // the keeper is optional: a lane that is not up simply fails the turn
            }
        };
        return new LaneLifecycle(want, tick, Long.getLong("mindpalace.lane.wakeWaitMs", DEFAULT_WAIT_MS), IDLE_TICK_MS,
            System::currentTimeMillis, ms -> {
                try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("interrupted"); }
            });
    }

    @Override
    public synchronized void before(LaneClient lane) {
        touch();
        if (pending != null) pending.cancel(false);   // a call is starting: the release timer starts again after it
        if (lane.healthy()) return;
        tick.run();
        long deadline = clock.getAsLong() + waitMs;
        while (!lane.healthy()) {
            if (clock.getAsLong() >= deadline) throw new IllegalStateException("lane did not come up within " + waitMs / 1000 + " s");
            sleeper.accept(POLL_MS);
        }
    }

    @Override
    public synchronized void after() {
        if (pending != null) pending.cancel(false);
        pending = timer.schedule(tick, idleTickMs, TimeUnit.MILLISECONDS);
    }

    private void touch() {
        try {
            Files.createDirectories(want.toAbsolutePath().getParent());
            Files.write(want, new byte[0], StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            Files.setLastModifiedTime(want, FileTime.fromMillis(System.currentTimeMillis()));
        } catch (IOException e) {
            throw new IllegalStateException("cannot ask for the lane: " + e.getClass().getSimpleName());
        }
    }
}
