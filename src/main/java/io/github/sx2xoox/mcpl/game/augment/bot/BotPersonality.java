package io.github.sx2xoox.mcpl.game.augment.bot;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

import io.github.sx2xoox.mcpl.game.augment.bot.BotBrain.Behaviour;
import org.jspecify.annotations.Nullable;

/**
 * A bot's character, chosen at spawn ({@code games.augment.bots.personalities}): it multiplies the {@link BotBrain}'s
 * utility scores (no scripts: the situation still decides) and tunes a few habits of the {@link BotController}
 * (base size and site, bow use, traps, sneaking, grudges). Difficulty stays separate: it changes how well the choice is
 * carried out. Each bot gets its own jittered copy ({@link #traits}), so two bots of one personality differ a little.
 */
public enum BotPersonality {
    /** Roams early, follows sounds, chases; rarely builds. */
    HUNTER("사냥꾼", "hunter", Map.of(Behaviour.BUILD_BASE, 0.45, Behaviour.SCOUT, 1.5, Behaviour.ENGAGE, 1.2,
            Behaviour.DEFEND_BASE, 0.4, Behaviour.HOLD_ZONE, 0.6, Behaviour.AVOID, 0.75, Behaviour.THIRD_PARTY, 0.75,
            Behaviour.AMBUSH, 0.8)),
    /** A strong base on high ground near the middle; defends and shoots from it. */
    FORTRESS("요새꾼", "fortress", Map.of(Behaviour.BUILD_BASE, 1.35, Behaviour.DEFEND_BASE, 2.2,
            Behaviour.AMBUSH, 1.4, Behaviour.ENGAGE, 0.85, Behaviour.SCOUT, 0.6, Behaviour.KITE, 1.2,
            Behaviour.HOLD_ZONE, 0.9)),
    /** Prefers the bow, keeps its distance, kites and takes high ground. */
    ARCHER("저격수", "archer", Map.of(Behaviour.KITE, 1.6, Behaviour.ENGAGE, 0.85, Behaviour.AVOID, 1.1,
            Behaviour.AMBUSH, 1.25, Behaviour.DEFEND_BASE, 1.1)),
    /** Avoids early fights, follows fights to third-party them, finishes the weakened. */
    OPPORTUNIST("어부지리형", "opportunist", Map.of(Behaviour.THIRD_PARTY, 1.6, Behaviour.ENGAGE, 0.8,
            Behaviour.AVOID, 1.3, Behaviour.HOLD_ZONE, 1.1, Behaviour.SCOUT, 0.9)),
    /** Very aggressive melee, chases relentlessly, hardly retreats. */
    BERSERKER("광전사", "berserker", Map.of(Behaviour.ENGAGE, 1.4, Behaviour.RETREAT_HEAL, 0.6,
            Behaviour.FORTIFY, 0.4, Behaviour.AVOID, 0.4, Behaviour.KITE, 0.55, Behaviour.DEFEND_BASE, 0.4,
            Behaviour.THIRD_PARTY, 0.6, Behaviour.AMBUSH, 0.6, Behaviour.SCOUT, 1.2)),
    /** Hides, moves with the zone, heals a lot and fights late. */
    SURVIVOR("생존왕", "survivor", Map.of(Behaviour.HOLD_ZONE, 1.5, Behaviour.AVOID, 1.3, Behaviour.RETREAT_HEAL,
            1.3, Behaviour.FORTIFY, 1.3, Behaviour.DEFEND_BASE, 1.4, Behaviour.ENGAGE, 0.75, Behaviour.SCOUT, 0.6,
            Behaviour.THIRD_PARTY, 1.1)),
    /** Cobwebs around its base and behind itself; lures chasers into them. */
    TRAPPER("트래퍼", "trapper", Map.of(Behaviour.BUILD_BASE, 1.2, Behaviour.DEFEND_BASE, 1.6, Behaviour.AMBUSH,
            1.5, Behaviour.ENGAGE, 0.9, Behaviour.AVOID, 1.1)),
    /** Sneaks up from behind or the flank, strikes the busy or the weak, gets out fast. */
    ASSASSIN("암살자", "assassin", Map.of(Behaviour.ENGAGE, 1.05, Behaviour.THIRD_PARTY, 1.2, Behaviour.AVOID, 1.2,
            Behaviour.KITE, 0.6, Behaviour.DEFEND_BASE, 0.5, Behaviour.BUILD_BASE, 0.7, Behaviour.SCOUT, 1.1)),
    /** Shield up, trades hits, holds doorways, eats apples early: slow but hard to kill. */
    TANK("탱커", "tank", Map.of(Behaviour.ENGAGE, 1.15, Behaviour.AVOID, 0.55, Behaviour.DEFEND_BASE, 1.3,
            Behaviour.KITE, 0.6, Behaviour.FORTIFY, 0.7, Behaviour.RETREAT_HEAL, 0.85)),
    /** Remembers who hurt it or killed near it and hunts that one down. */
    AVENGER("복수귀", "avenger", Map.of(Behaviour.ENGAGE, 1.05, Behaviour.SCOUT, 1.2, Behaviour.THIRD_PARTY, 0.9)),
    /** High variance: all-in dives, sudden bow duels, odd retreats; unpredictable. */
    GAMBLER("도박사", "gambler", Map.of(Behaviour.ENGAGE, 1.1, Behaviour.KITE, 1.1, Behaviour.RETREAT_HEAL, 0.9)),
    /** Never builds, keeps moving with the zone, kites with the bow, picks off loners, never gets cornered. */
    NOMAD("유목민", "nomad", Map.of(Behaviour.BUILD_BASE, 0.0, Behaviour.DEFEND_BASE, 0.0, Behaviour.FORTIFY, 0.6,
            Behaviour.KITE, 1.4, Behaviour.HOLD_ZONE, 1.35, Behaviour.SCOUT, 1.15, Behaviour.AVOID, 1.15));

    private final String koreanName;
    private final String key;
    private final Map<Behaviour, Double> weights;

    BotPersonality(String koreanName, String key, Map<Behaviour, Double> weights) {
        this.koreanName = koreanName;
        this.key = key;
        this.weights = Map.copyOf(weights);
    }

    public String koreanName() {
        return koreanName;
    }

    /** Config key ({@code hunter}, {@code fortress}, ...). */
    public String key() {
        return key;
    }

    /** The archetype's score multiplier of {@code behaviour} (1 if it does not care). */
    public double weight(Behaviour behaviour) {
        return weights.getOrDefault(behaviour, 1.0);
    }

    /** Parses {@code 사냥꾼}/{@code hunter}/{@code HUNTER}; null if unknown. */
    public static @Nullable BotPersonality parse(@Nullable String text) {
        if (text == null) {
            return null;
        }
        String wanted = text.trim().toLowerCase(Locale.ROOT);
        for (BotPersonality each : values()) {
            if (each.key.equals(wanted) || each.koreanName.equals(wanted) || each.name().equalsIgnoreCase(wanted)) {
                return each;
            }
        }
        return null;
    }

    /**
     * A bot's own copy of its personality: the archetype's numbers with a little random variation (so two hunters
     * still differ), plus the habits the controller reads.
     *
     * @param personality     the archetype, null for the neutral mind (tests)
     * @param weights         score multiplier per behaviour
     * @param aggressionShift added to the profile's aggression
     * @param healFactor      times the profile's heal-at health
     * @param bowRangeBonus   extra bow range (blocks)
     * @param baseSizeBonus   extra base side (blocks, even)
     * @param centerPull      0..1: how strongly its base site is drawn to the middle of the map
     * @param traps           lays cobwebs around its base and behind itself when chased
     * @param sneaks          crouch-walks near enemies (not heard from afar) and comes in from behind
     * @param grudges         hunts whoever hurt it or killed near it
     * @param volatility      random swing of every score (a gambler's unpredictability)
     * @param shieldBonus     added to the profile's shield chance
     * @param speedFactor     times its walking pace
     * @param roam            times how far it roams while farming
     */
    public record Traits(@Nullable BotPersonality personality, Map<Behaviour, Double> weights, double aggressionShift,
                         double healFactor, double bowRangeBonus, int baseSizeBonus, double centerPull, boolean traps,
                         boolean sneaks, boolean grudges, double volatility, double shieldBonus, double speedFactor,
                         double roam) {

        public Traits {
            weights = Map.copyOf(weights);
        }

        /** No personality: every multiplier 1 (the plain utility AI). */
        public static final Traits NEUTRAL = new Traits(null, Map.of(), 0, 1, 0, 0, 0.4, false, false, false, 0, 0,
                1, 1);

        public double weight(Behaviour behaviour) {
            return weights.getOrDefault(behaviour, 1.0);
        }

        /** True if the bot builds and uses a base at all. */
        public boolean builds() {
            return weight(Behaviour.BUILD_BASE) > 0.05;
        }

        /** The label next to its name ("사냥꾼"), empty for the neutral mind. */
        public String label() {
            return personality == null ? "" : personality.koreanName();
        }
    }

    /** This archetype's traits for one bot, jittered by {@code random} (±12 % per multiplier). */
    public Traits traits(Random random) {
        Map<Behaviour, Double> out = new EnumMap<>(Behaviour.class);
        for (Behaviour behaviour : Behaviour.values()) {
            double base = weight(behaviour);
            double jitter = base == 0 ? 0 : (random.nextDouble() * 2 - 1) * 0.12;
            out.put(behaviour, Math.max(0, base * (1 + jitter)));
        }
        double small = random.nextDouble() * 2 - 1;
        return switch (this) {
            case HUNTER -> new Traits(this, out, 0.15 + 0.05 * small, 0.85, 0, 0, 0.3, false, false, false, 0, 0,
                    1.0, 1.6);
            case FORTRESS -> new Traits(this, out, -0.05 + 0.05 * small, 1.1, 4, 2, 0.95, false, false, false, 0,
                    0.1, 1.0, 0.7);
            case ARCHER -> new Traits(this, out, 0.0 + 0.05 * small, 1.0, 10, 0, 0.6, false, false, false, 0, 0,
                    1.0, 1.0);
            case OPPORTUNIST -> new Traits(this, out, -0.12 + 0.05 * small, 1.15, 0, 0, 0.6, false, false, false, 0,
                    0, 1.0, 0.9);
            case BERSERKER -> new Traits(this, out, 0.3 + 0.05 * small, 0.6, -8, 0, 0.3, false, false, false, 0, 0,
                    1.03, 1.3);
            case SURVIVOR -> new Traits(this, out, -0.15 + 0.05 * small, 1.35, 0, 0, 0.9, false, false, false, 0,
                    0.05, 1.0, 0.6);
            case TRAPPER -> new Traits(this, out, -0.05 + 0.05 * small, 1.1, 0, 0, 0.75, true, false, false, 0, 0,
                    1.0, 0.8);
            case ASSASSIN -> new Traits(this, out, 0.05 + 0.05 * small, 1.05, -4, 0, 0.5, false, true, false, 0, 0,
                    1.0, 1.2);
            case TANK -> new Traits(this, out, 0.05 + 0.05 * small, 1.3, -6, 0, 0.6, false, false, false, 0, 0.25,
                    0.94, 0.8);
            case AVENGER -> new Traits(this, out, 0.05 * small, 1.0, 0, 0, 0.5, false, false, true, 0, 0, 1.0, 1.1);
            case GAMBLER -> new Traits(this, out, 0.1 * small, 0.9 + 0.2 * random.nextDouble(), 0, 0, 0.3, false,
                    false, false, 22, 0, 1.0, 1.2);
            case NOMAD -> new Traits(this, out, 0.0 + 0.05 * small, 1.1, 6, 0, 0, false, false, false, 0, 0, 1.0, 1.5);
        };
    }

    /**
     * Picks the personalities of {@code count} bots from {@code weights} (0 = disabled): weighted draws without
     * repeating an archetype until every enabled one has been used, so a lineup of 12 shows them all. With every
     * weight 0 each entry is null (no personality).
     */
    public static List<@Nullable BotPersonality> lineup(Map<BotPersonality, Double> weights, int count, Random random) {
        List<@Nullable BotPersonality> out = new ArrayList<>(count);
        Map<BotPersonality, Double> pool = new EnumMap<>(BotPersonality.class);
        while (out.size() < count) {
            if (pool.isEmpty()) {
                weights.forEach((personality, weight) -> {
                    if (weight != null && weight > 0) {
                        pool.put(personality, weight);
                    }
                });
                if (pool.isEmpty()) {
                    while (out.size() < count) {
                        out.add(null); // every weight 0: no personalities (the plain utility AI)
                    }
                    return out;
                }
            }
            double total = pool.values().stream().mapToDouble(Double::doubleValue).sum();
            double roll = random.nextDouble() * total;
            BotPersonality chosen = null;
            for (Map.Entry<BotPersonality, Double> entry : pool.entrySet()) {
                chosen = entry.getKey();
                roll -= entry.getValue();
                if (roll <= 0) {
                    break;
                }
            }
            out.add(chosen);
            pool.remove(chosen);
        }
        return out;
    }
}
