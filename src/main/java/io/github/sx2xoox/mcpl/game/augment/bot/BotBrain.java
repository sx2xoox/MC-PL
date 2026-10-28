package io.github.sx2xoox.mcpl.game.augment.bot;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * The bot's mind: a utility AI that scores every option from the {@link Situation} at each decision and picks the
 * best one. Pure (no Bukkit), so every judgement is unit-tested with scenarios.
 *
 * <p>All difficulties think with these same rules; the {@link BotProfile} only changes how the bot perceives
 * (how well it reads a target's gear), how eager it is ({@code aggression}), when it thinks it needs to heal and how
 * far it shoots. How well the chosen behaviour is executed (reaction, aim, crits, building speed) is
 * {@link BotController}'s part.
 */
public final class BotBrain {

    /** What a bot can do. */
    public enum Behaviour {
        /** Farming phase: roam and gather (gear improves on the schedule). */
        FARM,
        /** Farming phase: build its base. */
        BUILD_BASE,
        /** Nobody known: move through the safe zone looking for someone. */
        SCOUT,
        /** Fight the target in melee. */
        ENGAGE,
        /** Keep distance and shoot the target with the bow. */
        KITE,
        /** Break line of sight, eat a golden apple, come back. */
        RETREAT_HEAL,
        /** Wall itself in (or pillar up) where it stands to heal. */
        FORTIFY,
        /** Fall back to its base and hold it. */
        DEFEND_BASE,
        /** Late game: hold a spot well inside the final zone. */
        HOLD_ZONE,
        /** Wait still on high ground or in cover for the approaching target, then strike. */
        AMBUSH,
        /** Two others are fighting: wait nearby until one is weak, then take both. */
        THIRD_PARTY,
        /** Outgeared or outnumbered: keep away from the target. */
        AVOID,
        /** Outside or at the edge of the closing border: get inside. */
        FLEE_BORDER
    }

    /**
     * Someone the bot knows about: seen (line of sight) or heard nearby, or last seen within its memory.
     *
     * @param id            participant id
     * @param distance      blocks to their (last known) position
     * @param health        their health plus absorption
     * @param maxHealth     their max health
     * @param gear          their gear score as visible on them ({@link Gear#score})
     * @param visible       in line of sight right now
     * @param fightingOther hurt or hit by someone else (not this bot) in the last seconds
     * @param approaching   getting closer
     * @param hasBow        holds or carries a bow (visible in hand)
     */
    public record Contact(UUID id, double distance, double health, double maxHealth, double gear, boolean visible,
                          boolean fightingOther, boolean approaching, boolean hasBow) {
    }

    /**
     * What the bot knows about itself and the match at a decision.
     *
     * @param fight            PvP is on
     * @param farmingLeft      0..1 of the farming phase still to go (0 once fighting)
     * @param health           own health
     * @param absorption       own absorption
     * @param maxHealth        own max health
     * @param apples           golden apples left
     * @param appleReady       eating another apple now makes sense (not just eaten)
     * @param gear             own gear score
     * @param hasBow           has a bow and arrows
     * @param blocks           building blocks left
     * @param borderMargin     blocks from the border edge where the border is heading soon (negative = outside)
     * @param borderShrinking  the border is closing
     * @param zoneDistance     blocks to the center of the final zone
     * @param zoneRadius       half side of the final zone
     * @param baseBuilt        its base stands
     * @param baseDone         its base is finished (farming: nothing left to build)
     * @param baseDistance     blocks to its base (infinite without one)
     * @param baseSafe         its base stays inside the border for a while
     * @param highGround       stands clearly higher than the surroundings or on its lookout
     * @param current          what it is doing now (decisions stick a little)
     * @param currentTarget    whom it is dealing with now
     * @param contacts         known enemies
     */
    public record Situation(boolean fight, double farmingLeft, double health, double absorption, double maxHealth,
                            int apples, boolean appleReady, double gear, boolean hasBow, int blocks,
                            double borderMargin, boolean borderShrinking, double zoneDistance, double zoneRadius,
                            boolean baseBuilt, boolean baseDone, double baseDistance, boolean baseSafe,
                            boolean highGround, @Nullable Behaviour current, @Nullable UUID currentTarget,
                            List<Contact> contacts) {

        public Situation {
            contacts = List.copyOf(contacts);
        }
    }

    /** The chosen option. */
    public record Decision(Behaviour behaviour, @Nullable UUID target, double score) {
    }

    /** Range in which enemies count as "near" (1v2 avoidance). */
    static final double NEAR = 18;
    /** Score bonus that keeps the current behaviour and target (no flip-flopping). */
    static final double STICKY = 6;

    private final BotProfile profile;

    public BotBrain(BotProfile profile) {
        this.profile = profile;
    }

    /** Every option with its score, best first (for tests and debugging). */
    public List<Decision> options(Situation s) {
        List<Decision> out = new ArrayList<>();
        double hp = s.health() + s.absorption();
        boolean low = s.health() <= profile.healAt();
        double danger = s.borderShrinking() ? 10 : 3;
        if (s.borderMargin() < danger) {
            out.add(new Decision(Behaviour.FLEE_BORDER, null, 90 + (danger - s.borderMargin()) * 3));
        }
        if (!s.fight()) {
            // No PvP yet: a smart player farms and builds, it does not chase anyone.
            if (!s.baseDone()) {
                out.add(new Decision(Behaviour.BUILD_BASE, null, 60 + 10 * s.farmingLeft()));
            }
            out.add(new Decision(Behaviour.FARM, null, 40));
            return sorted(out, s);
        }
        double myPower = power(hp + (s.appleReady() ? Math.min(s.apples(), 2) * 4.0 : 0), s.gear());
        Contact nearestThreat = null;
        int near = 0;
        for (Contact c : s.contacts()) {
            if (c.distance() <= NEAR) {
                near++;
            }
            if (nearestThreat == null || c.distance() < nearestThreat.distance()) {
                nearestThreat = c;
            }
        }
        boolean threatened = nearestThreat != null && nearestThreat.distance() <= 24;
        for (Contact c : s.contacts()) {
            double advantage = myPower / Math.max(1, power(c.health(), perceived(c.gear())));
            double hurtRatio = c.health() / Math.max(1, c.maxHealth());
            int others = near - (c.distance() <= NEAR ? 1 : 0);
            if (c.fightingOther()) {
                if (hurtRatio > 0.5 && !low) {
                    // let them wear each other down first
                    out.add(new Decision(Behaviour.THIRD_PARTY, c.id(), 62 + 10 * profile.aggression()));
                } else {
                    out.add(new Decision(Behaviour.ENGAGE, c.id(),
                            55 + 30 * profile.aggression() + 20 * (1 - hurtRatio) + 10 * Math.min(1, advantage - 1)));
                }
                continue;
            }
            double engage = 30 + 40 * profile.aggression() + 30 * clamp(advantage - 1, -1.5, 1)
                    - 22 * Math.max(0, others) - (c.visible() ? 0 : 6) - Math.max(0, c.distance() - 30) * 0.4;
            if (low) {
                engage -= 30;
            }
            out.add(new Decision(Behaviour.ENGAGE, c.id(), engage));
            if (s.hasBow() && profile.bowRange() > 0 && c.distance() > 7 && c.distance() <= profile.bowRange()
                    && c.visible()) {
                double kite = 42 + 25 * clamp(1 - advantage, 0, 1) + (c.hasBow() ? -8 : 6);
                out.add(new Decision(Behaviour.KITE, c.id(), kite));
            }
            if (advantage < 0.75 || others >= 1 && advantage < 1.6) {
                out.add(new Decision(Behaviour.AVOID, c.id(), 50 + 40 * clamp(0.9 - advantage, 0, 1)
                        + 12 * Math.max(0, others)));
            }
            if (c.approaching() && c.distance() >= 8 && c.distance() <= 26 && advantage >= 0.85
                    && (s.highGround() || s.baseBuilt() && s.baseDistance() < 4)) {
                out.add(new Decision(Behaviour.AMBUSH, c.id(), 60 + 10 * profile.aggression()));
            }
        }
        if (low) {
            if (threatened && s.blocks() >= 8 && (s.apples() == 0 || !s.appleReady()
                    || nearestThreat.distance() < 8)) {
                out.add(new Decision(Behaviour.FORTIFY, null, s.apples() == 0 ? 86 : 78));
            }
            out.add(new Decision(Behaviour.RETREAT_HEAL, null, (s.apples() > 0 ? 80 : 64) + (threatened ? 4 : 0)));
        }
        if (s.baseBuilt() && s.baseSafe() && s.baseDistance() < 48) {
            double wantBase = (low ? 30 : 0) + (near >= 2 ? 20 : 0);
            out.add(new Decision(Behaviour.DEFEND_BASE, null, 30 + wantBase - s.baseDistance() * 0.3));
        }
        if (s.borderShrinking()) {
            double outside = Math.max(0, s.zoneDistance() - s.zoneRadius() * 0.6);
            out.add(new Decision(Behaviour.HOLD_ZONE, null, 38 + Math.min(20, outside * 0.5)));
        }
        out.add(new Decision(Behaviour.SCOUT, null, 25));
        return sorted(out, s);
    }

    /** The best option. */
    public Decision decide(Situation s) {
        return options(s).getFirst();
    }

    private List<Decision> sorted(List<Decision> options, Situation s) {
        List<Decision> out = new ArrayList<>(options.size());
        for (Decision d : options) {
            boolean same = d.behaviour() == s.current()
                    && (d.target() == null || d.target().equals(s.currentTarget()));
            out.add(same ? new Decision(d.behaviour(), d.target(), d.score() + STICKY) : d);
        }
        out.sort((a, b) -> Double.compare(b.score(), a.score()));
        return out;
    }

    /** How the bot sees someone's gear: exact with a perfect read, else blurred towards the average. */
    double perceived(double gear) {
        return gear * profile.gearRead() + Gear.AVERAGE * (1 - profile.gearRead());
    }

    /**
     * Fighting strength: effective health times a gear factor squared (armor makes you last longer and the weapon
     * hits harder, so gear counts twice: naked 1, average gear 4).
     */
    static double power(double health, double gear) {
        double factor = 1 + Math.max(0, gear - Gear.NAKED) / (Gear.AVERAGE - Gear.NAKED);
        return Math.max(0.5, health) * factor * factor;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
