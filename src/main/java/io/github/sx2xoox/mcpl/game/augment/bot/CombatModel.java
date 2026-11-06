package io.github.sx2xoox.mcpl.game.augment.bot;

/**
 * A quick forecast of a melee duel (no Bukkit): each side's damage per second through the other's armor (vanilla's
 * armor and toughness formula, crits, the share of swings that land, augments that add or multiply damage, burn or
 * poison, steal life or hit back) against the other's effective health (health, absorption, the golden apples it can
 * eat meanwhile, a second life), so the bot fights the fights it wins and leaves the ones it loses. A top player reads
 * the same things off the enemy before they commit: the armor they wear, the sword in their hand, their hearts.
 *
 * <p>The forecast is the ratio of the times each side needs to kill the other ({@link #advantage}, 1 = even, 2 = it
 * kills twice as fast) and a win chance from it ({@link #winChance}). {@code CombatModelTest} checks the forecast
 * against a hit-by-hit simulation ({@link #simulate}) of many duels.
 */
public final class CombatModel {

    /** How steeply the win chance follows the kill-time ratio (fit to {@link #simulate}, see the test). */
    static final double STEEPNESS = 4.0;
    /** Health a golden apple is worth in a fight (absorption 4 and regeneration II for 5 s, part of it in time). */
    static final double APPLE_HEALTH = 10;
    /** Ticks between two apples a fighter eats (eating and the bot's own wait). */
    static final double APPLE_TICKS = 72;

    /**
     * One fighter's numbers.
     *
     * @param health       health plus absorption now
     * @param maxHealth    max health
     * @param armor        armor points (worn armor plus augments)
     * @param toughness    armor toughness
     * @param damage       damage of one plain melee hit before armor (the sword plus flat bonuses)
     * @param swingTicks   ticks between two fully charged swings
     * @param hitChance    share of its swings that land (aim, the target's footwork)
     * @param critChance   share of its landed hits that are jump crits (×1.5)
     * @param outgoing     multiplier of the damage it deals (berserker's pact)
     * @param taken        multiplier of the melee damage it takes (thick skin)
     * @param flatPerHit   extra damage per hit on average, after the multipliers (a combo's every third hit)
     * @param trueDps      damage per second that ignores armor (fire, poison)
     * @param lifesteal    share of the damage it deals that it heals
     * @param thorns       share of the melee damage it takes that it deals back
     * @param execute      damage multiplier against an enemy below 30 % health (1: none)
     * @param apples       golden apples it will eat in the fight
     * @param extraLives   health it gets back once on a deadly hit (phoenix feather; 0: none)
     */
    public record Stats(double health, double maxHealth, double armor, double toughness, double damage,
                        double swingTicks, double hitChance, double critChance, double outgoing, double taken,
                        double flatPerHit, double trueDps, double lifesteal, double thorns, double execute,
                        int apples, double extraLives) {

        public Stats {
            health = Math.max(0.5, finite(health, 20));
            maxHealth = Math.max(1, finite(maxHealth, 20));
            armor = Math.max(0, finite(armor, 0));
            toughness = Math.max(0, finite(toughness, 0));
            damage = Math.max(0.5, finite(damage, 1));
            swingTicks = Math.max(4, finite(swingTicks, 12));
            hitChance = clamp(finite(hitChance, 0.85), 0.05, 1);
            critChance = clamp(finite(critChance, 0.5), 0, 1);
            outgoing = Math.max(0, finite(outgoing, 1));
            taken = Math.max(0, finite(taken, 1));
            apples = Math.max(0, apples);
        }

        /** A plain fighter: health, armor, sword damage; average footwork, no augments, no apples. */
        public static Stats plain(double health, double maxHealth, double armor, double toughness, double damage) {
            return new Stats(health, maxHealth, armor, toughness, damage, 12, 0.85, 0.5, 1, 1, 0, 0, 0, 0, 1, 0, 0);
        }

        public Stats withHealth(double value) {
            return new Stats(value, maxHealth, armor, toughness, damage, swingTicks, hitChance, critChance, outgoing,
                    taken, flatPerHit, trueDps, lifesteal, thorns, execute, apples, extraLives);
        }

        public Stats withSkill(double swing, double hit, double crit) {
            return new Stats(health, maxHealth, armor, toughness, damage, swing, hit, crit, outgoing, taken,
                    flatPerHit, trueDps, lifesteal, thorns, execute, apples, extraLives);
        }

        public Stats withApples(int value) {
            return new Stats(health, maxHealth, armor, toughness, damage, swingTicks, hitChance, critChance, outgoing,
                    taken, flatPerHit, trueDps, lifesteal, thorns, execute, value, extraLives);
        }

        /** These numbers with an augment's effect ({@link AugmentValues}). */
        public Stats with(AugmentValues.Effect e) {
            return new Stats(health + e.maxHealth(), maxHealth + e.maxHealth(), armor + e.armor(),
                    toughness + e.toughness(), damage + e.damage(), swingTicks,
                    clamp(hitChance * e.hitFactor(), 0.05, 1), critChance, outgoing * e.outgoing(),
                    taken * e.taken(), flatPerHit + e.flatPerHit(), trueDps + e.trueDps(), lifesteal + e.lifesteal(),
                    thorns + e.thorns(), execute * e.execute(), apples, extraLives + e.extraLife());
        }
    }

    private CombatModel() {
    }

    /** Vanilla's damage after armor: {@code damage × (1 − min(20, max(armor/5, armor − 4·damage/(toughness+8)))/25)}. */
    public static double afterArmor(double damage, double armor, double toughness) {
        double points = Math.min(20, Math.max(armor / 5, armor - 4 * damage / (toughness + 8)));
        return damage * (1 - points / 25);
    }

    /** The damage per tick {@code a} deals to {@code b} in melee (through armor, with augments; not the thorns). */
    static double dpt(Stats a, Stats b) {
        double plain = a.damage() * a.outgoing() * b.taken();
        double hit = (1 - a.critChance()) * afterArmor(plain, b.armor(), b.toughness())
                + a.critChance() * afterArmor(plain * 1.5, b.armor(), b.toughness());
        hit += a.flatPerHit();
        return a.hitChance() * hit / a.swingTicks() + a.trueDps() / 20;
    }

    /** Health {@code b} gets to spend against {@code a}: health, its apples, a second life. */
    static double effective(Stats b, double fightTicks) {
        double usable = Math.min(b.apples(), 1 + Math.floor(fightTicks / APPLE_TICKS));
        return b.health() + usable * APPLE_HEALTH + b.extraLives();
    }

    /** Ticks {@code a} needs to kill {@code b} (infinite if it cannot). */
    static double timeToKill(Stats a, Stats b) {
        double dealt = dpt(a, b) + a.thorns() * dpt(b, a); // a's thorns hurt b while b hits a
        double healed = b.lifesteal() * dpt(b, a);
        double net = dealt - healed;
        if (net <= 1.0E-6) {
            return Double.POSITIVE_INFINITY;
        }
        // two rounds: the length of the fight decides how many apples get eaten
        double ticks = effective(b, 0) / net;
        ticks = effective(b, ticks) / net;
        if (a.execute() > 1) {
            // the last 30 % of its max health goes faster
            double low = Math.min(0.3 * b.maxHealth(), effective(b, ticks));
            double high = effective(b, ticks) - low;
            ticks = high / net + low / (net + dpt(a, b) * (a.execute() - 1));
        }
        return ticks;
    }

    /** Its kill-time advantage: the enemy's time to kill it over its time to kill the enemy (1 = even). */
    public static double advantage(Stats me, Stats enemy) {
        double mine = timeToKill(me, enemy);
        double theirs = timeToKill(enemy, me);
        if (Double.isInfinite(mine) && Double.isInfinite(theirs)) {
            return 1;
        }
        if (Double.isInfinite(mine)) {
            return 0.05;
        }
        if (Double.isInfinite(theirs)) {
            return 20;
        }
        return clamp(theirs / mine, 0.05, 20);
    }

    /** Its chance to win the duel, from {@link #advantage}. */
    public static double winChance(Stats me, Stats enemy) {
        return winChance(advantage(me, enemy));
    }

    /** The win chance for a kill-time advantage. */
    public static double winChance(double advantage) {
        double a = Math.pow(Math.max(1.0E-6, advantage), STEEPNESS);
        return a / (1 + a);
    }

    /** Ticks it needs to kill {@code enemy} (for "it dies in one or two hits"). */
    public static double ticksToKill(Stats me, Stats enemy) {
        return timeToKill(me, enemy);
    }

    /**
     * A hit-by-hit duel: both swing on their own clock (a random start), each swing lands with its hit chance and is a
     * crit with its crit chance, armor, augments, apples (eaten at 40 % health, {@link #APPLE_TICKS} apart) and a
     * second life apply. True if {@code a} wins (a draw after a minute counts for neither: false).
     */
    public static boolean simulate(Stats a, Stats b, java.util.Random random) {
        double[] hp = {a.health(), b.health()};
        int[] apples = {a.apples(), b.apples()};
        double[] lives = {a.extraLives(), b.extraLives()};
        double[] next = {random.nextDouble() * a.swingTicks(), random.nextDouble() * b.swingTicks()};
        double[] appleAt = {0, 0};
        Stats[] s = {a, b};
        for (int tick = 0; tick < 1200; tick++) {
            for (int i = 0; i < 2; i++) {
                Stats me = s[i];
                Stats them = s[1 - i];
                hp[1 - i] -= me.trueDps() / 20;
                if (tick >= next[i]) {
                    next[i] += me.swingTicks();
                    if (random.nextDouble() < me.hitChance()) {
                        double plain = me.damage() * me.outgoing() * them.taken()
                                * (random.nextDouble() < me.critChance() ? 1.5 : 1);
                        double dealt = afterArmor(plain, them.armor(), them.toughness());
                        if (hp[1 - i] < 0.3 * them.maxHealth()) {
                            dealt *= me.execute();
                        }
                        dealt += me.flatPerHit();
                        hp[1 - i] -= dealt;
                        hp[i] = Math.min(Math.max(me.maxHealth(), me.health()), hp[i] + dealt * me.lifesteal());
                        hp[i] -= dealt * them.thorns();
                    }
                }
            }
            for (int i = 0; i < 2; i++) {
                if (hp[i] <= 0 && lives[i] > 0) {
                    hp[i] = lives[i];
                    lives[i] = 0;
                }
                if (hp[i] > 0 && hp[i] < 0.4 * s[i].maxHealth() && apples[i] > 0 && tick >= appleAt[i]) {
                    apples[i]--;
                    appleAt[i] = tick + APPLE_TICKS;
                    hp[i] += APPLE_HEALTH;
                }
            }
            if (hp[1] <= 0 && hp[0] > 0) {
                return true;
            }
            if (hp[0] <= 0) {
                return false;
            }
        }
        return false;
    }

    private static double finite(double value, double fallback) {
        return Double.isFinite(value) ? value : fallback;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
