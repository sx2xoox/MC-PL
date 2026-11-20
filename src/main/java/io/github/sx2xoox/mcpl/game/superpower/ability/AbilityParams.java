package io.github.sx2xoox.mcpl.game.superpower.ability;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * The effective tunable numbers of one ability: the defaults declared in {@link AbilityInfo} overridden by
 * {@code abilities.yml}. Only declared keys may be read; reading an undeclared key is a programming error.
 *
 * <p>The params a match binds to one owner's ability may carry an {@link Adjuster} ({@link #withAdjuster}): a hosting
 * mode changes the numbers that owner reads while it plays (공성전 level perks: "6레벨: 불길 범위 +1"). {@link #num}
 * and everything built on it ask the adjuster on every read, so abilities that read their params when they use them
 * follow level-ups at once; {@link #values()} stays the configured numbers (docs, item texts).
 */
public final class AbilityParams {

    /** Changes the value an owner reads for a declared param (see {@link #withAdjuster}). */
    @FunctionalInterface
    public interface Adjuster {
        /**
         * @param key   the declared param
         * @param value its configured value
         * @return the value to use now (non-finite results are ignored)
         */
        double adjust(String key, double value);
    }

    private final AbilityInfo info;
    private final Map<String, Double> values;
    private final @Nullable Adjuster adjuster;

    /**
     * @param info      the ability the params belong to
     * @param overrides configured values; keys that are not declared in {@code info} are ignored
     */
    public AbilityParams(AbilityInfo info, Map<String, Double> overrides) {
        this.info = info;
        Map<String, Double> merged = new LinkedHashMap<>(info.params());
        overrides.forEach((key, value) -> {
            if (merged.containsKey(key) && value != null && Double.isFinite(value)) {
                merged.put(key, value);
            }
        });
        this.values = Collections.unmodifiableMap(merged);
        this.adjuster = null;
    }

    private AbilityParams(AbilityInfo info, Map<String, Double> values, @Nullable Adjuster adjuster) {
        this.info = info;
        this.values = values;
        this.adjuster = adjuster;
    }

    /**
     * The same params read through {@code adjuster} (replacing any adjuster these params had): what a hosting mode
     * binds to one owner's ability. The configured {@link #values()} are shared, not copied.
     */
    public AbilityParams withAdjuster(@Nullable Adjuster adjuster) {
        return new AbilityParams(info, values, adjuster);
    }

    /** Params with every value at its declared default. */
    public static AbilityParams defaults(AbilityInfo info) {
        return new AbilityParams(info, Map.of());
    }

    public AbilityInfo info() {
        return info;
    }

    public boolean has(String key) {
        return values.containsKey(key);
    }

    /** The value of a declared param: the configured one, changed by the adjuster if these params have one. */
    public double num(String key) {
        Double value = values.get(key);
        if (value == null) {
            throw new IllegalArgumentException("Ability " + info.id() + " has no param '" + key + "'");
        }
        if (adjuster != null) {
            double adjusted = adjuster.adjust(key, value);
            if (Double.isFinite(adjusted)) {
                return adjusted;
            }
        }
        return value;
    }

    /** {@link #num(String)} rounded to the nearest int (for counts). */
    public int integer(String key) {
        return (int) Math.round(num(key));
    }

    /** A param declared in seconds, converted to server ticks (20 per second), at least 0. */
    public long ticks(String key) {
        return Math.max(0L, Math.round(num(key) * 20.0));
    }

    /** The active skill cooldown in seconds. */
    public double cooldownSeconds() {
        return num(AbilityInfo.COOLDOWN);
    }

    /** All configured values in declaration order (without the {@link #withAdjuster adjuster}). */
    public Map<String, Double> values() {
        return values;
    }
}
