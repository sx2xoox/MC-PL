package io.github.sx2xoox.mcpl.game.augment.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.bukkit.Material;

/**
 * Static description of an augment: identity, tier, kind, the card text and the tunable numbers.
 *
 * <p>Every balance number must be declared with {@link Builder#param(String, double)} so it appears in
 * {@code augments.yml} and {@code docs/augments.md}. The description may reference params as {@code {key}}; they are
 * replaced with the configured values on the card ({@link Params#render}).
 *
 * @param id          stable lowercase id, e.g. {@code "iron_body"} (config, commands)
 * @param name        Korean name, e.g. {@code "강철 체력"}
 * @param tier        the tier whose rounds offer it
 * @param kind        what it is about
 * @param icon        item of its card
 * @param description player-facing text (plain or MiniMessage, may contain {@code {param}})
 * @param params      declared tunables with their defaults, in declaration order
 */
public record AugmentInfo(String id, String name, AugmentTier tier, AugmentKind kind, Material icon,
                          String description, Map<String, Double> params) {

    public AugmentInfo {
        Objects.requireNonNull(id, "id");
        if (!Params.KEY.matcher(id).matches()) {
            throw new IllegalArgumentException("Invalid augment id: " + id);
        }
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(tier, "tier");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(icon, "icon");
        Objects.requireNonNull(description, "description");
        params = Collections.unmodifiableMap(new LinkedHashMap<>(params));
    }

    /** Params with every value at its declared default. */
    public Params defaults() {
        return new Params(id, params, Map.of());
    }

    public static Builder builder(String id, String name, AugmentTier tier, AugmentKind kind, Material icon) {
        return new Builder(id, name, tier, kind, icon);
    }

    public static final class Builder {
        private final String id;
        private final String name;
        private final AugmentTier tier;
        private final AugmentKind kind;
        private final Material icon;
        private String description = "";
        private final Map<String, Double> params = new LinkedHashMap<>();

        private Builder(String id, String name, AugmentTier tier, AugmentKind kind, Material icon) {
            this.id = id;
            this.name = name;
            this.tier = tier;
            this.kind = kind;
            this.icon = icon;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        /** Declares a tunable number with its default value. */
        public Builder param(String key, double defaultValue) {
            if (!Params.KEY.matcher(key).matches()) {
                throw new IllegalArgumentException("Invalid param key: " + key);
            }
            params.put(key, defaultValue);
            return this;
        }

        public AugmentInfo build() {
            return new AugmentInfo(id, name, tier, kind, icon, description, params);
        }
    }
}
