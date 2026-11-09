package io.github.sx2xoox.mcpl.game.augment.bot;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import io.github.sx2xoox.mcpl.game.augment.bot.BotBrain.Behaviour;
import io.github.sx2xoox.mcpl.game.augment.bot.BotBrain.Decision;
import io.github.sx2xoox.mcpl.game.augment.bot.BotBrain.Situation;
import org.jspecify.annotations.Nullable;

/**
 * The Claude bot's standing orders ({@link ClaudeCommander}): weights on the {@link BotBrain}'s utility scores, never a
 * replacement for them. The brain still scores every option from what the bot perceives; the orders add to the options
 * that serve the plan and take from the ones against it. Getting back inside the border and a sure kill stay above any
 * order ({@link BotBrain#borderFirst}; scores of 200 or more are never touched). Pure (no Bukkit).
 *
 * @param goal   the plan
 * @param target the enemy to go after (null: whoever the brain picks)
 * @param range  how it prefers to fight
 */
public record ClaudeOrders(Goal goal, @Nullable UUID target, Range range) {

    /** The plan. */
    public enum Goal {
        /** No preference: the brain decides. */
        AUTO,
        /** Farming: gear up instead of building (farming phase); no extra fights in the fight phase. */
        FARM,
        /** Go after the target (or any enemy without one). */
        ATTACK,
        /** Back off, heal, wall in. */
        RETREAT,
        /** Build, then hold its base. */
        BASE,
        /** Move into the (final) zone and hold a spot there. */
        ZONE;

        static Goal parse(@Nullable String value) {
            if (value == null) {
                return AUTO;
            }
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return AUTO;
            }
        }
    }

    /** How it prefers to fight. */
    public enum Range {
        AUTO, MELEE, BOW;

        static Range parse(@Nullable String value) {
            if (value == null) {
                return AUTO;
            }
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return AUTO;
            }
        }
    }

    /** No orders. */
    public static final ClaudeOrders NONE = new ClaudeOrders(Goal.AUTO, null, Range.AUTO);

    /** How much an order moves a score (a strong lean, about a personality's signature style). */
    static final double WEIGHT = 35;

    /** True when the orders change nothing. */
    public boolean none() {
        return goal == Goal.AUTO && target == null && range == Range.AUTO;
    }

    /**
     * The options re-scored by the orders, best first. Decisions scored 200 or more (sure kills, getting back inside
     * the border) and {@link Behaviour#FLEE_BORDER} are kept as they are; the caller applies
     * {@link BotBrain#borderFirst} again after this.
     */
    public List<Decision> weigh(List<Decision> options, Situation s) {
        List<Decision> out = new ArrayList<>(options.size() + 1);
        double best = Double.NEGATIVE_INFINITY;
        boolean hasFarm = false;
        for (Decision d : options) {
            hasFarm |= d.behaviour() == Behaviour.FARM;
            if (d.behaviour() == Behaviour.FLEE_BORDER || d.score() >= 200) {
                out.add(d);
                continue;
            }
            best = Math.max(best, d.score());
            double score = d.score() + bonus(d, s);
            out.add(score == d.score() ? d : new Decision(d.behaviour(), d.target(), score));
        }
        if (!s.fight() && goal == Goal.FARM && !hasFarm && Double.isFinite(best)) {
            // a builder told to farm: gearing up beats finishing its base this time
            out.add(new Decision(Behaviour.FARM, null, Math.max(40 + WEIGHT, best + 1)));
        }
        if (s.fight()) {
            // a plan the brain did not think of on its own (it offers these only in some situations)
            if (goal == Goal.RETREAT && !has(options, Behaviour.RETREAT_HEAL) && !has(options, Behaviour.AVOID)
                    && !has(options, Behaviour.FORTIFY)) {
                BotBrain.Contact nearest = nearest(s);
                if (s.apples() > 0) {
                    out.add(new Decision(Behaviour.RETREAT_HEAL, null, 60 + WEIGHT));
                } else if (nearest != null) {
                    out.add(new Decision(Behaviour.AVOID, nearest.id(), 60 + WEIGHT));
                }
            } else if (goal == Goal.ZONE && !has(options, Behaviour.HOLD_ZONE)) {
                out.add(new Decision(Behaviour.HOLD_ZONE, null, 40 + WEIGHT));
            } else if (goal == Goal.BASE && !has(options, Behaviour.DEFEND_BASE) && s.baseBuilt() && s.baseSafe()) {
                out.add(new Decision(Behaviour.DEFEND_BASE, null, 40 + WEIGHT));
            }
        }
        out.sort((a, b) -> Double.compare(b.score(), a.score()));
        return out;
    }

    private double bonus(Decision d, Situation s) {
        Behaviour b = d.behaviour();
        double bonus = 0;
        boolean fighting = b == Behaviour.ENGAGE || b == Behaviour.KITE;
        if (target != null && d.target() != null) {
            if (d.target().equals(target)) {
                bonus += fighting ? WEIGHT * 0.6 : b == Behaviour.THIRD_PARTY ? WEIGHT * 0.3
                        : b == Behaviour.AVOID ? -WEIGHT * 0.5 : 0;
            } else if (fighting && goal == Goal.ATTACK && contactKnown(s, target)) {
                bonus -= WEIGHT * 0.3; // the one it was told to hunt, not another
            }
        }
        switch (goal) {
            case FARM -> {
                if (!s.fight() && b == Behaviour.FARM) {
                    bonus += WEIGHT;
                } else if (s.fight() && (b == Behaviour.ENGAGE || b == Behaviour.THIRD_PARTY)) {
                    bonus -= WEIGHT * 0.3;
                }
            }
            case ATTACK -> {
                if (fighting && (target == null || !contactKnown(s, target))) {
                    bonus += WEIGHT * 0.5;
                } else if (fighting) {
                    bonus += WEIGHT * 0.4;
                } else if (b == Behaviour.SCOUT) {
                    bonus += WEIGHT * 0.4; // nobody in sight: go find them
                } else if (b == Behaviour.AVOID || b == Behaviour.DEFEND_BASE || b == Behaviour.HOLD_ZONE
                        || b == Behaviour.AMBUSH) {
                    bonus -= WEIGHT * 0.4;
                }
            }
            case RETREAT -> {
                if (b == Behaviour.RETREAT_HEAL || b == Behaviour.FORTIFY || b == Behaviour.AVOID
                        || b == Behaviour.DEFEND_BASE) {
                    bonus += WEIGHT;
                } else if (fighting || b == Behaviour.THIRD_PARTY || b == Behaviour.SCOUT) {
                    bonus -= WEIGHT * 0.5;
                }
            }
            case BASE -> {
                if (b == Behaviour.BUILD_BASE || b == Behaviour.DEFEND_BASE) {
                    bonus += WEIGHT;
                } else if (b == Behaviour.AMBUSH) {
                    bonus += WEIGHT * 0.3;
                } else if (b == Behaviour.SCOUT) {
                    bonus -= WEIGHT * 0.3;
                }
            }
            case ZONE -> {
                if (b == Behaviour.HOLD_ZONE) {
                    bonus += WEIGHT;
                } else if (b == Behaviour.AMBUSH) {
                    bonus += WEIGHT * 0.3;
                } else if (b == Behaviour.SCOUT) {
                    bonus += WEIGHT * 0.2;
                }
            }
            case AUTO -> {
            }
        }
        if (range == Range.MELEE) {
            bonus += b == Behaviour.ENGAGE ? WEIGHT * 0.4 : b == Behaviour.KITE ? -WEIGHT * 0.4 : 0;
        } else if (range == Range.BOW && s.hasBow()) {
            bonus += b == Behaviour.KITE ? WEIGHT * 0.8 : b == Behaviour.ENGAGE ? -WEIGHT * 0.4 : 0;
        }
        return bonus;
    }

    private static boolean has(List<Decision> options, Behaviour behaviour) {
        for (Decision d : options) {
            if (d.behaviour() == behaviour) {
                return true;
            }
        }
        return false;
    }

    private static BotBrain.@Nullable Contact nearest(Situation s) {
        BotBrain.Contact nearest = null;
        for (BotBrain.Contact c : s.contacts()) {
            if (nearest == null || c.distance() < nearest.distance()) {
                nearest = c;
            }
        }
        return nearest;
    }

    private static boolean contactKnown(Situation s, UUID id) {
        for (BotBrain.Contact c : s.contacts()) {
            if (c.id().equals(id)) {
                return true;
            }
        }
        return false;
    }
}
