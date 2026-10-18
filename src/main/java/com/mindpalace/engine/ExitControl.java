package com.mindpalace.engine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Polite exit and restart handling (all time is injected, so it is unit-testable).
 *
 * <p><b>Esc x2 to exit.</b> {@link #onEsc} returns true when Esc is pressed twice within
 * {@link #DOUBLE_ESC_MS}; the caller then closes the window (the normal cleanup path runs).</p>
 *
 * <p><b>Restart countdown.</b> Tooling that wants a restart (a new build, a jar swap) does not kill
 * the game. It writes {@code restart.request} in the game's working directory (see
 * {@code tools/request_restart.py}). The first line may hold the countdown seconds, the rest is a
 * human-readable reason. The game then shows "Restarting in N s - Esc to cancel". Esc cancels,
 * writes {@code restart.cancelled}, and snoozes new requests for {@link #SNOOZE_MS} so restarts
 * stay rare. When the countdown ends the game closes cleanly and relaunches itself.</p>
 */
public final class ExitControl {

    public static final long DOUBLE_ESC_MS = 600L;
    public static final int DEFAULT_COUNTDOWN_S = 30;
    public static final int MIN_COUNTDOWN_S = 5;
    public static final int MAX_COUNTDOWN_S = 300;
    public static final long SNOOZE_MS = 10L * 60L * 1000L;
    private static final long POLL_EVERY_MS = 2000L;

    private final Path requestFile;
    private final Path cancelFile;

    private long lastEscMs = Long.MIN_VALUE / 2;
    private long lastPollMs = Long.MIN_VALUE / 2;
    private long snoozeUntilMs = Long.MIN_VALUE / 2;
    private long deadlineMs = -1L;
    private String reason = "";

    public ExitControl(Path workDir) {
        this.requestFile = workDir.resolve("restart.request");
        this.cancelFile = workDir.resolve("restart.cancelled");
    }

    /** Record an Esc press. True when this is the second one inside the double-press window. */
    public boolean onEsc(long nowMs) {
        boolean second = nowMs - lastEscMs <= DOUBLE_ESC_MS;
        lastEscMs = second ? Long.MIN_VALUE / 2 : nowMs;   // a third press starts a new pair
        return second;
    }

    /** Look for a restart request (at most every 2 s). Cheap enough to call every frame. */
    public void poll(long nowMs) {
        if (deadlineMs >= 0 || nowMs < snoozeUntilMs) return;
        if (nowMs - lastPollMs < POLL_EVERY_MS) return;
        lastPollMs = nowMs;
        try {
            if (!Files.isRegularFile(requestFile)) return;
            String text = new String(Files.readAllBytes(requestFile), StandardCharsets.UTF_8).trim();
            int seconds = DEFAULT_COUNTDOWN_S;
            String why = text;
            int nl = text.indexOf('\n');
            String first = nl < 0 ? text : text.substring(0, nl).trim();
            if (first.matches("\\d{1,4}")) {
                seconds = Integer.parseInt(first);
                why = nl < 0 ? "" : text.substring(nl + 1).trim();
            }
            seconds = Math.max(MIN_COUNTDOWN_S, Math.min(MAX_COUNTDOWN_S, seconds));
            reason = why.length() > 80 ? why.substring(0, 80) : why;
            deadlineMs = nowMs + seconds * 1000L;
            Files.deleteIfExists(cancelFile);
        } catch (IOException | RuntimeException ignored) {
            // unreadable request: ignore it, never crash the game over a restart file
        }
    }

    public boolean restartPending() { return deadlineMs >= 0; }

    public String reason() { return reason; }

    public int secondsLeft(long nowMs) {
        return deadlineMs < 0 ? 0 : (int) Math.max(0L, (deadlineMs - nowMs + 999L) / 1000L);
    }

    public boolean restartDue(long nowMs) { return deadlineMs >= 0 && nowMs >= deadlineMs; }

    /** Esc during a countdown cancels it. Returns true when it did (the Esc is consumed). */
    public boolean cancelRestart(long nowMs) {
        if (deadlineMs < 0) return false;
        deadlineMs = -1L;
        snoozeUntilMs = nowMs + SNOOZE_MS;
        try {
            Files.deleteIfExists(requestFile);
            Files.write(cancelFile, ("cancelled by player at " + java.time.Instant.now() + "\n")
                .getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) { /* best effort */ }
        return true;
    }

    /** The countdown ran out: clear the request so the relaunched game does not restart again. */
    public void consumeRequest() {
        deadlineMs = -1L;
        try { Files.deleteIfExists(requestFile); } catch (IOException ignored) { /* best effort */ }
    }
}
