package io.github.sx2xoox.mcpl.game.superpower.runtime;

import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jspecify.annotations.Nullable;

/**
 * Error isolation for ability code: every hook, skill and scheduled task of an ability runs through here, so one
 * buggy ability can never break the match or other players. Failures are logged with the ability id and hook; a hook
 * that keeps failing (e.g. every tick) is logged in full a few times and then summarised at most every 10 seconds.
 */
public final class AbilityGuard {

    private static final int FULL_TRACES = 3;
    private static final long SUMMARY_INTERVAL_MILLIS = 10_000L;

    private final Logger logger;
    private final LongSupplier clock;
    private final Map<String, Failures> failures = new HashMap<>();
    private int total;
    private int depth;

    public AbilityGuard(Logger logger) {
        this(logger, System::currentTimeMillis);
    }

    AbilityGuard(Logger logger, LongSupplier clock) {
        this.logger = logger;
        this.clock = clock;
    }

    private static final class Failures {
        int count;
        int suppressed;
        long lastSummary;
    }

    /** Runs {@code action}; returns false (after logging) if it threw. */
    public boolean run(String abilityId, String hook, Runnable action) {
        depth++;
        try {
            action.run();
            return true;
        } catch (RuntimeException | LinkageError | StackOverflowError | AssertionError e) {
            report(abilityId, hook, e);
            return false;
        } finally {
            depth--;
        }
    }

    /** Calls {@code action}; returns null (after logging) if it threw. */
    public <T> @Nullable T call(String abilityId, String hook, Supplier<T> action) {
        depth++;
        try {
            return action.get();
        } catch (RuntimeException | LinkageError | StackOverflowError | AssertionError e) {
            report(abilityId, hook, e);
            return null;
        } finally {
            depth--;
        }
    }

    /** True while ability code runs through this guard (used to catch entities abilities forget to track). */
    public boolean isRunningAbilityCode() {
        return depth > 0;
    }

    /** Logs a failure of ability code (throttled per ability and hook). */
    public void report(String abilityId, String hook, Throwable error) {
        total++;
        String key = abilityId + "#" + hook;
        Failures entry = failures.computeIfAbsent(key, k -> new Failures());
        entry.count++;
        if (entry.count <= FULL_TRACES) {
            logger.log(Level.WARNING, "Ability '" + abilityId + "' failed in " + hook
                    + " (the match continues)", error);
            entry.lastSummary = clock.getAsLong();
            return;
        }
        entry.suppressed++;
        long now = clock.getAsLong();
        if (now - entry.lastSummary >= SUMMARY_INTERVAL_MILLIS) {
            logger.warning("Ability '" + abilityId + "' failed in " + hook + " " + entry.suppressed
                    + " more time(s): " + error);
            entry.suppressed = 0;
            entry.lastSummary = now;
        }
    }

    /** Number of failures reported so far (for diagnostics and tests). */
    public int totalFailures() {
        return total;
    }

    /** Failures of one ability hook so far. */
    public int failures(String abilityId, String hook) {
        Failures entry = failures.get(abilityId + "#" + hook);
        return entry == null ? 0 : entry.count;
    }
}
