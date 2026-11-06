package io.github.sx2xoox.mcpl.game.superpower.runtime;

/**
 * A participant's ultimate gauge in {@code 0..100}. Shown on the experience bar: the level number is the whole
 * percentage and the bar is the fraction ({@link #level()}, {@link #progress()}).
 */
public final class UltimateGauge {

    public static final double MAX = 100.0;
    private static final double EPSILON = 1.0E-9;

    private double value;

    public double value() {
        return value;
    }

    public boolean isFull() {
        return value >= MAX - EPSILON;
    }

    /**
     * Adds {@code amount} (may be negative), clamped to {@code 0..100}; non-finite amounts are ignored.
     *
     * @return true if this call filled the gauge (it was below 100 and is full now)
     */
    public boolean add(double amount) {
        if (!Double.isFinite(amount) || amount == 0) {
            return false;
        }
        return set(value + amount);
    }

    /**
     * Sets the gauge, clamped to {@code 0..100}; non-finite values are ignored.
     *
     * @return true if this call filled the gauge
     */
    public boolean set(double newValue) {
        if (!Double.isFinite(newValue)) {
            return false;
        }
        boolean wasFull = isFull();
        value = Math.max(0.0, Math.min(MAX, newValue));
        if (value >= MAX - EPSILON) {
            value = MAX;
        }
        return !wasFull && isFull();
    }

    /** Empties the gauge (an ultimate was used). */
    public void reset() {
        value = 0.0;
    }

    /** The experience level shown: the whole percentage ({@code 0..100}). */
    public int level() {
        return (int) Math.floor(value + EPSILON);
    }

    /** The experience bar fraction ({@code 0..1}). */
    public float progress() {
        return (float) Math.max(0.0, Math.min(1.0, value / MAX));
    }
}
