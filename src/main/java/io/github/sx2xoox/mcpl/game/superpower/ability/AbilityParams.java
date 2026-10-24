package io.github.sx2xoox.mcpl.game.superpower.ability;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The effective tunable numbers of one ability: the defaults declared in {@link AbilityInfo} overridden by
 * {@code abilities.yml}. Only declared keys may be read; reading an undeclared key is a programming error.
 */
public final class AbilityParams {

    private final AbilityInfo info;
    private final Map<String, Double> values;

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

    /** The configured value of a declared param. */
    public double num(String key) {
        Double value = values.get(key);
        if (value == null) {
            throw new IllegalArgumentException("Ability " + info.id() + " has no param '" + key + "'");
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

    /** All effective values in declaration order. */
    public Map<String, Double> values() {
        return values;
    }
}
