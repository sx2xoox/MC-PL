package io.github.sx2xoox.mcpl.game.augment.api;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The effective tunable numbers of an augment or a bonus condition: the declared defaults overridden by
 * {@code augments.yml}. Only declared keys may be read; reading an undeclared key is a programming error.
 *
 * <p>Conventions: durations in seconds ({@link #ticks}), distances in blocks, health in health points (2 = one
 * heart), percentages as whole percent (25 = 25 %).
 */
public final class Params {

    static final Pattern KEY = Pattern.compile("[a-z][a-z0-9_]*");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z0-9_]+)}");

    private final String owner;
    private final Map<String, Double> values;

    /**
     * @param owner     id of the augment or condition (for error messages)
     * @param defaults  declared params with their defaults, in declaration order
     * @param overrides configured values; undeclared keys and non-finite values are ignored
     */
    public Params(String owner, Map<String, Double> defaults, Map<String, Double> overrides) {
        this.owner = owner;
        Map<String, Double> merged = new LinkedHashMap<>(defaults);
        overrides.forEach((key, value) -> {
            if (merged.containsKey(key) && value != null && Double.isFinite(value)) {
                merged.put(key, value);
            }
        });
        this.values = Collections.unmodifiableMap(merged);
    }

    public boolean has(String key) {
        return values.containsKey(key);
    }

    /** The value of a declared param. */
    public double num(String key) {
        Double value = values.get(key);
        if (value == null) {
            throw new IllegalArgumentException(owner + " has no param '" + key + "'");
        }
        return value;
    }

    /** {@link #num} rounded to the nearest int (counts). */
    public int integer(String key) {
        return (int) Math.round(num(key));
    }

    /** A param in seconds as server ticks (20 per second), at least 0. */
    public long ticks(String key) {
        return Math.max(0L, Math.round(num(key) * 20.0));
    }

    /** {@link #num} / 100 for a param in percent. */
    public double fraction(String key) {
        return num(key) / 100.0;
    }

    /** All values in declaration order. */
    public Map<String, Double> values() {
        return values;
    }

    /** {@code text} with every {@code {key}} replaced by the value (unknown placeholders stay). */
    public String render(String text) {
        Matcher matcher = PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            String replacement = has(key) ? format(num(key)) : matcher.group();
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** Whole numbers without decimals ({@code 4}), others with up to two decimals ({@code 1.5}). */
    public static String format(double value) {
        if (!Double.isFinite(value)) {
            return Double.toString(value);
        }
        if (Math.abs(value - Math.rint(value)) < 1.0E-9) {
            return Long.toString(Math.round(value));
        }
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }
}
