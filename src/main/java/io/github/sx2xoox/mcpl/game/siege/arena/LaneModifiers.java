package io.github.sx2xoox.mcpl.game.siege.arena;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.github.sx2xoox.mcpl.game.siege.balance.SiegeModifiers;
import org.jspecify.annotations.Nullable;

/**
 * Additive per-player modifiers of 공성전 (shop items, jungle buffs, and whatever else wants to add one, e.g. the
 * balance rules): each source ({@code "shop:armor"}, {@code "buff:teddy"}, ...) sets its own value of a {@link Stat},
 * and readers ask for the sum. Shared by the shop, the jungle and the rules so nobody hard-codes another's effects:
 * the systems apply {@link Stat#SKILL_DAMAGE}, {@link Stat#SKILL_DAMAGE_TAKEN} and {@link Stat#STRUCTURE_DAMAGE}
 * through {@link SiegeSystems}' damage listener and {@link Stat#COOLDOWN} every second; {@link Stat#RESPAWN_SECONDS}
 * is read by the rules when a player dies ({@link #respawnSeconds}).
 *
 * <p>With the match's balance modifiers ({@link SiegeModifiers}, {@code SiegeMatch#modifiers()}) the ability stats
 * ({@link Stat#SKILL_DAMAGE}, {@link Stat#SKILL_DAMAGE_TAKEN}, {@link Stat#COOLDOWN}) are mirrored there under the same
 * source key (one ability stat per source) and the hosted ability match applies them on every hit and cooldown; this
 * class then reports them as {@link #bridged()} and the systems do not apply them again.
 */
public final class LaneModifiers {

    /** What a modifier changes; values add up across sources. */
    public enum Stat {
        /** Ability damage dealt: +0.10 = +10 %. */
        SKILL_DAMAGE,
        /** Ability damage taken: −0.06 = −6 %. */
        SKILL_DAMAGE_TAKEN,
        /** Damage dealt to towers and cores (any kind): +0.20 = +20 %. */
        STRUCTURE_DAMAGE,
        /** Ability cooldown: 0.10 = cooldowns 10 % shorter (capped at 0.5). */
        COOLDOWN,
        /** Respawn delay in seconds: −2 = 2 s sooner. */
        RESPAWN_SECONDS
    }

    /** Most cooldown reduction all sources together give. */
    public static final double MAX_COOLDOWN = 0.5;

    private final Map<UUID, Map<String, EnumMap<Stat, Double>>> values = new HashMap<>();
    private final @Nullable SiegeModifiers shared;

    /** Modifiers the lane systems apply themselves (tests, a match without balance). */
    public LaneModifiers() {
        this(null);
    }

    /** Modifiers whose ability stats go to the match's balance modifiers. */
    public LaneModifiers(@Nullable SiegeModifiers shared) {
        this.shared = shared;
    }

    /** Whether the ability stats are applied by the match's balance (not by the systems). */
    public boolean bridged() {
        return shared != null;
    }

    private static SiegeModifiers.@Nullable Stat sharedStat(Stat stat) {
        return switch (stat) {
            case SKILL_DAMAGE -> SiegeModifiers.Stat.SKILL_DAMAGE;
            case SKILL_DAMAGE_TAKEN -> SiegeModifiers.Stat.SKILL_DAMAGE_TAKEN;
            case COOLDOWN -> SiegeModifiers.Stat.COOLDOWN;
            case STRUCTURE_DAMAGE, RESPAWN_SECONDS -> null;
        };
    }

    /** Mirrors {@code source}'s ability stat of {@code player} to the shared modifiers (or removes it there). */
    private void mirror(UUID player, String source) {
        if (shared == null) {
            return;
        }
        shared.remove(player, source);
        Map<String, EnumMap<Stat, Double>> sources = values.get(player);
        EnumMap<Stat, Double> stats = sources == null ? null : sources.get(source);
        if (stats == null) {
            return;
        }
        for (Map.Entry<Stat, Double> entry : stats.entrySet()) {
            SiegeModifiers.Stat target = sharedStat(entry.getKey());
            if (target != null) {
                // Our cooldown is a reduction (0.10 = 10 % shorter), theirs a change (-10 %).
                double percent = entry.getValue() * 100 * (entry.getKey() == Stat.COOLDOWN ? -1 : 1);
                shared.addPercent(player, source, target, percent);
            }
        }
    }

    /** Sets (replaces) what {@code source} gives {@code player} for {@code stat}; 0 removes it. */
    public void set(UUID player, String source, Stat stat, double value) {
        if (value == 0) {
            Map<String, EnumMap<Stat, Double>> sources = values.get(player);
            EnumMap<Stat, Double> stats = sources == null ? null : sources.get(source);
            if (stats != null) {
                stats.remove(stat);
                if (stats.isEmpty()) {
                    sources.remove(source);
                }
            }
            mirror(player, source);
            return;
        }
        values.computeIfAbsent(player, id -> new HashMap<>())
                .computeIfAbsent(source, key -> new EnumMap<>(Stat.class)).put(stat, value);
        mirror(player, source);
    }

    /** Removes everything {@code source} gives {@code player}. */
    public void remove(UUID player, String source) {
        Map<String, EnumMap<Stat, Double>> sources = values.get(player);
        if (sources != null) {
            sources.remove(source);
        }
        mirror(player, source);
    }

    /** Removes {@code source} from every player. */
    public void removeEverywhere(String source) {
        for (UUID player : List.copyOf(values.keySet())) {
            remove(player, source);
        }
    }

    /** Removes every modifier of a player whose source starts with {@code prefix}. */
    public void removePrefix(UUID player, String prefix) {
        Map<String, EnumMap<Stat, Double>> sources = values.get(player);
        if (sources != null) {
            for (String key : List.copyOf(sources.keySet())) {
                if (key.startsWith(prefix)) {
                    remove(player, key);
                }
            }
        }
    }

    public boolean has(UUID player, String source) {
        Map<String, EnumMap<Stat, Double>> sources = values.get(player);
        return sources != null && sources.containsKey(source);
    }

    /** The sum of every source's value of {@code stat} for {@code player}. */
    public double total(@Nullable UUID player, Stat stat) {
        Map<String, EnumMap<Stat, Double>> sources = player == null ? null : values.get(player);
        if (sources == null) {
            return 0;
        }
        double sum = 0;
        for (EnumMap<Stat, Double> stats : sources.values()) {
            sum += stats.getOrDefault(stat, 0.0);
        }
        return sum;
    }

    /** The factor an ability hit of {@code attacker} on {@code victim} is multiplied by (never below 0.1). */
    public double skillDamageFactor(@Nullable UUID attacker, @Nullable UUID victim) {
        double factor = (1 + total(attacker, Stat.SKILL_DAMAGE)) * (1 + total(victim, Stat.SKILL_DAMAGE_TAKEN));
        return Math.max(0.1, factor);
    }

    /** The factor a hit of {@code attacker} on a tower or core is multiplied by. */
    public double structureDamageFactor(@Nullable UUID attacker) {
        return Math.max(0.1, 1 + total(attacker, Stat.STRUCTURE_DAMAGE));
    }

    /** Cooldown reduction of a player, 0..{@link #MAX_COOLDOWN}. */
    public double cooldownReduction(@Nullable UUID player) {
        return Math.clamp(total(player, Stat.COOLDOWN), 0, MAX_COOLDOWN);
    }

    /**
     * The respawn change in seconds (negative = sooner) the rules add to a death's delay (e.g. 부활 시계 −2).
     */
    public double respawnSeconds(@Nullable UUID player) {
        return total(player, Stat.RESPAWN_SECONDS);
    }

    public void clear(UUID player) {
        removePrefix(player, "");
        values.remove(player);
    }

    public void clear() {
        for (UUID player : List.copyOf(values.keySet())) {
            clear(player);
        }
    }
}
