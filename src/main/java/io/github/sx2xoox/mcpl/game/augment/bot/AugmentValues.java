package io.github.sx2xoox.mcpl.game.augment.bot;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.jspecify.annotations.Nullable;

/**
 * What the augments bots can own do in a fight, as numbers for the {@link CombatModel}, and how a bot picks one of
 * the cards it is offered: like a player who knows the cards, by how much the card makes it win a duel with what it
 * already owns (berserker's pact and a sharp edge multiply, life steal grows with damage, an executioner wants damage),
 * plus what its style wants (a sniper's arrows, a hunter's speed) and what wins between fights (regeneration, the
 * war god's stacks). Pure (no Bukkit): ids and params in, an id out ({@code AugmentValuesTest} measures the picks).
 */
public final class AugmentValues {

    /**
     * An augment's effect.
     *
     * @param maxHealth  max health it adds (attribute: already on the body)
     * @param armor      armor points (attribute)
     * @param toughness  armor toughness (attribute)
     * @param damage     flat melee damage per hit before armor (added to the hit in the damage event)
     * @param hitFactor  multiplier of its own share of swings that land
     * @param outgoing   multiplier of the damage it deals
     * @param taken      multiplier of the melee damage it takes
     * @param flatPerHit damage per hit after armor
     * @param trueDps    damage per second through armor (fire, poison)
     * @param lifesteal  share of its damage healed
     * @param thorns     share of melee damage taken dealt back
     * @param execute    multiplier against an enemy below 30 %
     * @param extraLife  health back once on a deadly hit
     * @param bow        multiplier of its arrows' worth (sniper, frost arrows)
     * @param speed      share of movement speed added (negative: slower)
     * @param between    worth outside a duel, in win chance (regeneration between fights, stacks over kills, revenge)
     */
    public record Effect(double maxHealth, double armor, double toughness, double damage, double hitFactor,
                         double outgoing, double taken, double flatPerHit, double trueDps, double lifesteal,
                         double thorns, double execute, double extraLife, double bow, double speed, double between) {

        static final Effect NONE = new Effect(0, 0, 0, 0, 1, 1, 1, 0, 0, 0, 0, 1, 0, 1, 0, 0);

        /** The part the body's attributes do not show (the controller reads those off the body). */
        public Effect eventOnly() {
            return new Effect(0, 0, 0, damage, hitFactor, outgoing, taken, flatPerHit, trueDps, lifesteal, thorns,
                    execute, extraLife, bow, 0, between);
        }

        /** Both effects together. */
        Effect plus(Effect o) {
            return new Effect(maxHealth + o.maxHealth, armor + o.armor, toughness + o.toughness, damage + o.damage,
                    hitFactor * o.hitFactor, outgoing * o.outgoing, taken * o.taken, flatPerHit + o.flatPerHit,
                    trueDps + o.trueDps, lifesteal + o.lifesteal, thorns + o.thorns, execute * o.execute,
                    extraLife + o.extraLife, bow * o.bow, speed + o.speed, between + o.between);
        }
    }

    private AugmentValues() {
    }

    /** The effect of augment {@code id} with {@code params} (its configured numbers); {@link Effect#NONE} if unknown. */
    public static Effect effect(String id, Map<String, Double> params) {
        Effect n = Effect.NONE;
        return switch (id) {
            case "iron_body" -> with(n, "maxHealth", num(params, "health", 4));
            case "gale" -> with(n, "speed", num(params, "speed", 15) / 100);
            case "sharp_edge" -> with(n, "damage", num(params, "damage", 1.5));
            case "thick_skin" -> with(n, "taken", 1 - num(params, "reduction", 10) / 100);
            // burning: 1 a second for its seconds, once per cooldown
            case "fire_blade" -> with(n, "trueDps", num(params, "seconds", 2) / Math.max(1, num(params, "cooldown", 4)));
            // poison II: about 1.6 a second for its seconds, once per cooldown (never the last health point)
            case "venom" -> with(n, "trueDps", 0.8 * num(params, "level", 2) * num(params, "seconds", 2)
                    / Math.max(1, num(params, "cooldown", 4)));
            case "feather_fall" -> with(n, "between", 0.01);
            case "bloodthirst" -> with(n, "lifesteal", num(params, "percent", 20) / 100);
            case "berserker_pact" -> with(with(n, "maxHealth", -num(params, "health", 4)), "outgoing",
                    1 + num(params, "damage", 25) / 100);
            case "executioner" -> with(n, "execute", 1 + num(params, "damage", 30) / 100);
            // every third hit on the same enemy within the window
            case "combo" -> with(n, "damage", num(params, "damage", 4) / Math.max(1, num(params, "hits", 3)));
            case "sniper" -> with(n, "bow", 1 + num(params, "damage", 50) / 100 * 0.6);
            case "thorns" -> with(n, "thorns", num(params, "percent", 20) / 100);
            // knocked further back, the enemy lands fewer of its swings (it acts as fewer of theirs landing)
            case "heavy_hitter" -> with(n, "taken", 1 - 0.04 * num(params, "knockback", 2));
            case "frost_arrow" -> with(n, "bow", 1 + 0.15 * num(params, "level", 2));
            case "phoenix_feather" -> with(with(n, "extraLife", num(params, "health", 10)
                    + 2 * num(params, "shield", 3)), "between", 0.02);
            // between fights: heal per second once unhurt for the delay (a full heal before the next fight)
            case "regen_heart" -> with(n, "between", 0.05 * num(params, "heal", 1) / Math.max(1, num(params, "delay", 5) / 5));
            case "giant" -> with(with(n, "maxHealth", num(params, "health", 8)), "speed",
                    -num(params, "slow", 10) / 100);
            // stacks with every kill: worth more the more kills it expects
            case "war_god" -> with(n, "between", 0.03 * Math.min(3, num(params, "max_stacks", 5))
                    * num(params, "damage", 1));
            case "fortress" -> with(with(n, "armor", num(params, "armor", 4)), "toughness",
                    num(params, "toughness", 4));
            case "vengeful" -> with(n, "between", 0.015);
            default -> n;
        };
    }

    /** The summed effect of everything in {@code owned} (id → params). */
    public static Effect total(Map<String, Map<String, Double>> owned) {
        Effect sum = Effect.NONE;
        for (Map.Entry<String, Map<String, Double>> e : owned.entrySet()) {
            sum = sum.plus(effect(e.getKey(), e.getValue()));
        }
        return sum;
    }

    /** A card a bot is offered: the augment's id and its configured numbers. */
    public record Card(String id, Map<String, Double> params) {
    }

    /**
     * How much {@code card} is worth to a bot whose duel numbers are {@code me} (gear, skill, without augments) and
     * that owns {@code owned}: the win chance it adds in a duel against a mirror of itself with the augments an
     * average opponent has by then ({@code rivalEffect}), plus its style's wants.
     *
     * @param bowShare  0..1, how much of its fighting is with the bow (a sniper's most of it)
     * @param mobility  0..1, how much it lives off its legs (a hunter, a nomad)
     * @param kills     kills it expects to make (aggression; the war god stacks)
     */
    public static double value(Card card, CombatModel.Stats me, Effect owned, Effect rivalEffect, double bowShare,
                               double mobility, double kills) {
        Effect add = effect(card.id(), card.params());
        CombatModel.Stats rival = me.with(rivalEffect);
        double before = CombatModel.winChance(me.with(owned), rival);
        double after = CombatModel.winChance(me.with(owned.plus(add)), rival);
        double melee = (after - before) * (1 - 0.6 * bowShare);
        double bow = (add.bow() - 1) * 0.12 * bowShare * owned.bow();
        double legs = add.speed() * (0.25 + 0.6 * mobility);
        double between = add.between() * (card.id().equals("war_god") ? Math.max(0.3, kills) : 1);
        return melee + bow + legs + between;
    }

    /**
     * The card a bot takes: the best by {@link #value}, or with {@code randomShare} chance (an easy bot does not read
     * the cards well) a random one. Null for no cards.
     */
    public static @Nullable String pick(List<Card> cards, CombatModel.Stats me, Map<String, Map<String, Double>> owned,
                                        Effect rivalEffect, double bowShare, double mobility, double kills,
                                        double randomShare, Random random) {
        if (cards.isEmpty()) {
            return null;
        }
        if (random.nextDouble() < randomShare) {
            return cards.get(random.nextInt(cards.size())).id();
        }
        Effect have = total(owned);
        String best = null;
        double bestValue = Double.NEGATIVE_INFINITY;
        for (Card card : cards) {
            double v = value(card, me, have, rivalEffect, bowShare, mobility, kills);
            if (v > bestValue) {
                bestValue = v;
                best = card.id();
            }
        }
        return best;
    }

    /** Ids among {@code ids} that this table knows (every augment bots can own should be here). */
    static List<String> known(Collection<String> ids) {
        List<String> out = new ArrayList<>();
        for (String id : ids) {
            if (effect(id, Map.of()) != Effect.NONE) {
                out.add(id);
            }
        }
        return out;
    }

    private static double num(Map<String, Double> params, String key, double fallback) {
        Double v = params.get(key);
        return v == null || !Double.isFinite(v) ? fallback : v;
    }

    private static Effect with(Effect e, String field, double v) {
        return switch (field) {
            case "maxHealth" -> new Effect(v, e.armor, e.toughness, e.damage, e.hitFactor, e.outgoing, e.taken,
                    e.flatPerHit, e.trueDps, e.lifesteal, e.thorns, e.execute, e.extraLife, e.bow, e.speed, e.between);
            case "armor" -> new Effect(e.maxHealth, v, e.toughness, e.damage, e.hitFactor, e.outgoing, e.taken,
                    e.flatPerHit, e.trueDps, e.lifesteal, e.thorns, e.execute, e.extraLife, e.bow, e.speed, e.between);
            case "toughness" -> new Effect(e.maxHealth, e.armor, v, e.damage, e.hitFactor, e.outgoing, e.taken,
                    e.flatPerHit, e.trueDps, e.lifesteal, e.thorns, e.execute, e.extraLife, e.bow, e.speed, e.between);
            case "damage" -> new Effect(e.maxHealth, e.armor, e.toughness, v, e.hitFactor, e.outgoing, e.taken,
                    e.flatPerHit, e.trueDps, e.lifesteal, e.thorns, e.execute, e.extraLife, e.bow, e.speed, e.between);
            case "outgoing" -> new Effect(e.maxHealth, e.armor, e.toughness, e.damage, e.hitFactor, v, e.taken,
                    e.flatPerHit, e.trueDps, e.lifesteal, e.thorns, e.execute, e.extraLife, e.bow, e.speed, e.between);
            case "taken" -> new Effect(e.maxHealth, e.armor, e.toughness, e.damage, e.hitFactor, e.outgoing, v,
                    e.flatPerHit, e.trueDps, e.lifesteal, e.thorns, e.execute, e.extraLife, e.bow, e.speed, e.between);
            case "trueDps" -> new Effect(e.maxHealth, e.armor, e.toughness, e.damage, e.hitFactor, e.outgoing, e.taken,
                    e.flatPerHit, v, e.lifesteal, e.thorns, e.execute, e.extraLife, e.bow, e.speed, e.between);
            case "lifesteal" -> new Effect(e.maxHealth, e.armor, e.toughness, e.damage, e.hitFactor, e.outgoing,
                    e.taken, e.flatPerHit, e.trueDps, v, e.thorns, e.execute, e.extraLife, e.bow, e.speed, e.between);
            case "thorns" -> new Effect(e.maxHealth, e.armor, e.toughness, e.damage, e.hitFactor, e.outgoing, e.taken,
                    e.flatPerHit, e.trueDps, e.lifesteal, v, e.execute, e.extraLife, e.bow, e.speed, e.between);
            case "execute" -> new Effect(e.maxHealth, e.armor, e.toughness, e.damage, e.hitFactor, e.outgoing,
                    e.taken, e.flatPerHit, e.trueDps, e.lifesteal, e.thorns, v, e.extraLife, e.bow, e.speed, e.between);
            case "extraLife" -> new Effect(e.maxHealth, e.armor, e.toughness, e.damage, e.hitFactor, e.outgoing,
                    e.taken, e.flatPerHit, e.trueDps, e.lifesteal, e.thorns, e.execute, v, e.bow, e.speed, e.between);
            case "bow" -> new Effect(e.maxHealth, e.armor, e.toughness, e.damage, e.hitFactor, e.outgoing, e.taken,
                    e.flatPerHit, e.trueDps, e.lifesteal, e.thorns, e.execute, e.extraLife, v, e.speed, e.between);
            case "speed" -> new Effect(e.maxHealth, e.armor, e.toughness, e.damage, e.hitFactor, e.outgoing, e.taken,
                    e.flatPerHit, e.trueDps, e.lifesteal, e.thorns, e.execute, e.extraLife, e.bow, v, e.between);
            case "between" -> new Effect(e.maxHealth, e.armor, e.toughness, e.damage, e.hitFactor, e.outgoing,
                    e.taken, e.flatPerHit, e.trueDps, e.lifesteal, e.thorns, e.execute, e.extraLife, e.bow, e.speed, v);
            default -> throw new IllegalArgumentException(field);
        };
    }
}
