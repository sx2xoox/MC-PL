package io.github.sx2xoox.mcpl.game.superpower.ability;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Material;

/**
 * Static description of an ability type: identity, the three skills shown to players, and the tunable numbers.
 *
 * <p>Every number an ability uses for balance (damage, radius, duration, ...) must be declared here with
 * {@link Builder#param(String, double)} so that it appears in {@code abilities.yml} and can be tuned by server
 * owners. Descriptions may reference params as {@code {key}}; they are replaced with the configured value when
 * shown to players (see {@link #render(String, AbilityParams)}).
 *
 * <p>Param conventions: durations are in <b>seconds</b> (read them with {@link AbilityParams#ticks(String)}),
 * distances in blocks, damage in half-hearts (vanilla health points, 20 = full health).
 *
 * @param id                 stable lowercase id, e.g. {@code "pyro"} (used in config and commands)
 * @param name               Korean display name, e.g. {@code "화염술사"}
 * @param icon               item used for the ability item and menus
 * @param color              theme color for names/titles
 * @param difficulty         1 (easy) .. 3 (hard), shown as stars in menus
 * @param passive            always-on effect
 * @param active             right-click skill; its cooldown is the {@code cooldown} param
 * @param ultimate           F-key skill, usable when the ultimate gauge is full
 * @param ultimateStandalone true if {@link Ability#onUltimate()} works on a freshly bound instance that never
 *                           received {@link Ability#onGrant()}, ticks or event hooks (the Gambler borrows such
 *                           ultimates)
 * @param params             declared tunables with their default values, in declaration order
 */
public record AbilityInfo(
        String id,
        String name,
        Material icon,
        TextColor color,
        int difficulty,
        Skill passive,
        Skill active,
        Skill ultimate,
        boolean ultimateStandalone,
        Map<String, Double> params
) {

    /** The param key that holds the active skill cooldown in seconds. Always declared. */
    public static final String COOLDOWN = "cooldown";

    private static final Pattern ID_PATTERN = Pattern.compile("[a-z][a-z0-9_]*");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z0-9_]+)}");

    public AbilityInfo {
        Objects.requireNonNull(id, "id");
        if (!ID_PATTERN.matcher(id).matches()) {
            throw new IllegalArgumentException("Invalid ability id: " + id);
        }
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(icon, "icon");
        Objects.requireNonNull(color, "color");
        Objects.requireNonNull(passive, "passive");
        Objects.requireNonNull(active, "active");
        Objects.requireNonNull(ultimate, "ultimate");
        if (difficulty < 1 || difficulty > 3) {
            throw new IllegalArgumentException("difficulty must be 1..3");
        }
        params = Collections.unmodifiableMap(new LinkedHashMap<>(params));
        if (!params.containsKey(COOLDOWN)) {
            throw new IllegalArgumentException("Ability " + id + " must declare a cooldown");
        }
    }

    /**
     * Replaces {@code {key}} placeholders with the configured values from {@code params}. Unknown placeholders are
     * left untouched. Numbers are printed by {@link #formatNumber(double)}.
     */
    public static String render(String text, AbilityParams params) {
        Matcher matcher = PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            String replacement = params.has(key) ? formatNumber(params.num(key)) : matcher.group();
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /**
     * A param value for players: whole numbers without decimals ({@code 3}), others with up to two decimals and no
     * trailing zeros ({@code 1.5}, {@code 0.25}).
     */
    public static String formatNumber(double value) {
        if (Math.abs(value - Math.rint(value)) < 1.0E-9) {
            return Long.toString(Math.round(value));
        }
        if (!Double.isFinite(value)) {
            return Double.toString(value);
        }
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    public static Builder builder(String id, String name, Material icon) {
        return new Builder(id, name, icon);
    }

    /** A named skill with a player-facing description (plain text or MiniMessage, may contain {@code {param}}). */
    public record Skill(String name, String description) {
        public Skill {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(description, "description");
        }
    }

    public static final class Builder {
        private final String id;
        private final String name;
        private final Material icon;
        private TextColor color = NamedTextColor.WHITE;
        private int difficulty = 1;
        private Skill passive;
        private Skill active;
        private Skill ultimate;
        private boolean ultimateStandalone = true;
        private final Map<String, Double> params = new LinkedHashMap<>();

        private Builder(String id, String name, Material icon) {
            this.id = id;
            this.name = name;
            this.icon = icon;
        }

        public Builder color(TextColor color) {
            this.color = color;
            return this;
        }

        public Builder difficulty(int difficulty) {
            this.difficulty = difficulty;
            return this;
        }

        public Builder passive(String name, String description) {
            this.passive = new Skill(name, description);
            return this;
        }

        /** Declares the active skill and its default cooldown (stored as the {@code cooldown} param). */
        public Builder active(String name, String description, double cooldownSeconds) {
            this.active = new Skill(name, description);
            this.params.put(COOLDOWN, cooldownSeconds);
            return this;
        }

        public Builder ultimate(String name, String description) {
            this.ultimate = new Skill(name, description);
            return this;
        }

        /** Marks the ultimate as relying on grant/tick/hook state, so the Gambler will never borrow it. */
        public Builder ultimateNotStandalone() {
            this.ultimateStandalone = false;
            return this;
        }

        /** Declares a tunable number with its default value. */
        public Builder param(String key, double defaultValue) {
            if (!ID_PATTERN.matcher(key).matches()) {
                throw new IllegalArgumentException("Invalid param key: " + key);
            }
            this.params.put(key, defaultValue);
            return this;
        }

        public AbilityInfo build() {
            return new AbilityInfo(id, name, icon, color, difficulty, passive, active, ultimate,
                    ultimateStandalone, params);
        }
    }
}
