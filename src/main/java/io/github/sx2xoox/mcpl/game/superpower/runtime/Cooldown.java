package io.github.sx2xoox.mcpl.game.superpower.runtime;

/**
 * An active-skill cooldown measured in fight ticks (20 per second). The framework starts it with
 * {@code params.cooldownSeconds() * ability.cooldownMultiplier()} after a successful activation.
 */
public final class Cooldown {

    private long endTick;

    /**
     * Starts the cooldown.
     *
     * @param now        current fight tick
     * @param seconds    base cooldown in seconds (negative counts as 0)
     * @param multiplier cooldown multiplier; NaN, infinite or negative values count as 1
     * @return the cooldown length in ticks
     */
    public long start(long now, double seconds, double multiplier) {
        long ticks = ticks(seconds, multiplier);
        endTick = now + ticks;
        return ticks;
    }

    /** The cooldown length in ticks for a base time and multiplier (see {@link #start}). */
    public static long ticks(double seconds, double multiplier) {
        double base = Double.isFinite(seconds) ? Math.max(0.0, seconds) : 0.0;
        double factor = Double.isFinite(multiplier) && multiplier >= 0 ? multiplier : 1.0;
        return Math.round(base * factor * 20.0);
    }

    /**
     * Shortens the remaining cooldown by {@code seconds} (never below ready).
     *
     * @return the remaining ticks afterwards
     */
    public long reduce(long now, double seconds) {
        if (Double.isFinite(seconds) && seconds > 0) {
            endTick = Math.max(now, endTick - Math.round(seconds * 20.0));
        }
        return remainingTicks(now);
    }

    public long remainingTicks(long now) {
        return Math.max(0L, endTick - now);
    }

    public double remainingSeconds(long now) {
        return remainingTicks(now) / 20.0;
    }

    public boolean isReady(long now) {
        return remainingTicks(now) == 0;
    }

    public void reset() {
        endTick = 0;
    }
}
