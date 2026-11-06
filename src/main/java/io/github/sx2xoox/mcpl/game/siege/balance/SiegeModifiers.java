package io.github.sx2xoox.mcpl.game.siege.balance;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The 공성전 stat modifiers other systems hand out per player: shop items (마법 구슬 "스킬 피해 +10%", 장난감 갑옷 "스킬 피해
 * −6%", 모래시계 "쿨타임 −10%"), jungle buffs, the dragon. Plain numbers, no Bukkit; one instance per match
 * ({@code SiegeMatch#modifiers()}), read by the hosted ability match on every ability hit and every cooldown.
 *
 * <p>Every modifier has a key chosen by its source ({@code "shop:orb"}, {@code "buff:rabbit"}); setting a key again
 * replaces it, so a source never stacks with itself. Per stat, the factor is
 * {@code (1 + sum of ADD amounts) × product of MULTIPLY amounts}, clamped to the stat's floor:
 * <ul>
 *     <li>{@link Stat#SKILL_DAMAGE}: ability damage the player deals ({@code ADD 0.10} = +10%);</li>
 *     <li>{@link Stat#SKILL_DAMAGE_TAKEN}: ability damage the player takes ({@code ADD -0.12} = −12%, armor);</li>
 *     <li>{@link Stat#COOLDOWN}: the active cooldown the player gets ({@code ADD -0.10} = −10%).</li>
 * </ul>
 * Remove a modifier with {@link #remove} when its source ends (a buff runs out, an item is sold, the round resets).
 */
public final class SiegeModifiers {

    /** What a modifier changes. */
    public enum Stat {
        /** Ability damage dealt (floor ×0.1). */
        SKILL_DAMAGE(0.1),
        /** Ability damage taken (floor ×0.2: armor never makes a player immune). */
        SKILL_DAMAGE_TAKEN(0.2),
        /** Active cooldown (floor ×0.4: at most −60%). */
        COOLDOWN(0.4);

        private final double floor;

        Stat(double floor) {
            this.floor = floor;
        }

        /** The lowest factor the stat can reach. */
        public double floor() {
            return floor;
        }
    }

    /** How a modifier's amount combines. */
    public enum Op {
        /** Added to the other ADD amounts: {@code 0.10} = +10%, {@code -0.06} = −6%. */
        ADD,
        /** Multiplies the result: {@code 1.1} = ×1.1. */
        MULTIPLY
    }

    /** One modifier. */
    public record Modifier(Stat stat, Op op, double amount) {
        public Modifier {
            Objects.requireNonNull(stat, "stat");
            Objects.requireNonNull(op, "op");
            if (!Double.isFinite(amount)) {
                throw new IllegalArgumentException("amount must be finite");
            }
        }
    }

    private final Map<UUID, Map<String, Modifier>> byPlayer = new HashMap<>();

    /** Sets (or replaces) the modifier {@code key} of {@code player}. */
    public void set(UUID player, String key, Stat stat, Op op, double amount) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(key, "key");
        byPlayer.computeIfAbsent(player, id -> new LinkedHashMap<>()).put(key, new Modifier(stat, op, amount));
    }

    /** Shorthand for an {@link Op#ADD} modifier: {@code percent} 10 = +10%, −6 = −6%. */
    public void addPercent(UUID player, String key, Stat stat, double percent) {
        set(player, key, stat, Op.ADD, percent / 100.0);
    }

    /** Removes the modifier {@code key} of {@code player}, if any. */
    public void remove(UUID player, String key) {
        Map<String, Modifier> modifiers = byPlayer.get(player);
        if (modifiers != null) {
            modifiers.remove(key);
            if (modifiers.isEmpty()) {
                byPlayer.remove(player);
            }
        }
    }

    /** Removes every modifier whose key starts with {@code prefix} from every player (e.g. {@code "buff:"}). */
    public void removeAll(String prefix) {
        byPlayer.values().forEach(modifiers -> modifiers.keySet().removeIf(key -> key.startsWith(prefix)));
        byPlayer.values().removeIf(Map::isEmpty);
    }

    /** Removes every modifier of {@code player}. */
    public void clear(UUID player) {
        byPlayer.remove(player);
    }

    /** Removes every modifier of everyone. */
    public void clearAll() {
        byPlayer.clear();
    }

    /** The modifiers of {@code player}, by key (read-only view of a copy). */
    public Map<String, Modifier> of(UUID player) {
        Map<String, Modifier> modifiers = byPlayer.get(player);
        return modifiers == null ? Map.of() : Map.copyOf(modifiers);
    }

    /** The factor of {@code stat} for {@code player}: 1.0 without modifiers, never below {@link Stat#floor()}. */
    public double factor(UUID player, Stat stat) {
        Map<String, Modifier> modifiers = byPlayer.get(player);
        if (modifiers == null) {
            return 1.0;
        }
        double add = 0;
        double multiply = 1;
        for (Modifier modifier : modifiers.values()) {
            if (modifier.stat() != stat) {
                continue;
            }
            if (modifier.op() == Op.ADD) {
                add += modifier.amount();
            } else {
                multiply *= modifier.amount();
            }
        }
        return Math.max(stat.floor(), (1 + add) * multiply);
    }
}
