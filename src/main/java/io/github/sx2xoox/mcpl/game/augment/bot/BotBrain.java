package io.github.sx2xoox.mcpl.game.augment.bot;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
        FARM("파밍", false),
        /** Farming phase: build its base. */
        BUILD_BASE("기지 건설", false),
        /** Nobody known: move through the safe zone looking for someone. */
        SCOUT("정찰", false),
        /** Fight the target in melee. */
        ENGAGE("교전", true),
        /** Keep distance and shoot the target with the bow. */
        KITE("활 견제", true),
        /** Break line of sight, eat a golden apple, come back. */
        RETREAT_HEAL("후퇴·회복", true),
        /** Wall itself in (or pillar up) where it stands to heal. */
        FORTIFY("진지 구축", true),
        /** Fall back to its base and hold it. */
        DEFEND_BASE("기지 방어", true),
        /** Late game: hold a spot well inside the final zone. */
        HOLD_ZONE("구역 지키기", false),
        /** Wait still on high ground or in cover for the approaching target, then strike. */
        AMBUSH("매복", true),
        /** Two others are fighting: wait nearby until one is weak, then take both. */
        THIRD_PARTY("어부지리", true),
        /** Outgeared or outnumbered: keep away from the target. */
        AVOID("회피", false),
        /** Outside or at the edge of the closing border: get inside. */
        FLEE_BORDER("보더 탈출", false),
        /** 팀전: too far from its team's leader: back to the team. */
        REGROUP("합류", false);

        private final String koreanName;
        private final boolean notable;

        Behaviour(String koreanName, boolean notable) {
            this.koreanName = koreanName;
            this.notable = notable;
        }

        /** The Korean name spectators see (sidebar, behaviour feed). */
        public String koreanName() {
            return koreanName;
        }

        /** Worth a line in the spectators' chat when a bot switches to it (fights, retreats, shelters). */
        public boolean notable() {
            return notable;
        }
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
     * @param grudge        it hurt this bot or killed someone near it (an avenger hunts it down)
     * @param trapped       stuck in a cobweb right now (easy to hit, cannot get away)
     */
    public record Contact(UUID id, double distance, double health, double maxHealth, double gear, boolean visible,
                          boolean fightingOther, boolean approaching, boolean hasBow, boolean grudge,
                          boolean trapped) {

        /** A contact that is not stuck in a web. */
        public Contact(UUID id, double distance, double health, double maxHealth, double gear, boolean visible,
                       boolean fightingOther, boolean approaching, boolean hasBow, boolean grudge) {
            this(id, distance, health, maxHealth, gear, visible, fightingOther, approaching, hasBow, grudge, false);
        }

        /** A contact the bot holds no grudge against. */
        public Contact(UUID id, double distance, double health, double maxHealth, double gear, boolean visible,
                       boolean fightingOther, boolean approaching, boolean hasBow) {
            this(id, distance, health, maxHealth, gear, visible, fightingOther, approaching, hasBow, false);
        }
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
     * @param pressure         0..1, how late the fight is (fight time and the closing border): the later, the less
     *                         anyone waits, avoids or hides, so a match always comes to an end
     * @param finale           the border is (nearly) at its final size: nowhere left to go
     * @param rivalsLeft       enemies still in play (known or not; the sidebar shows it to everyone)
     * @param idle             0..1, how long it has gone without a fight (1: a minute or more): an idle bot goes
     *                         looking for one
     */
    public record Situation(boolean fight, double farmingLeft, double health, double absorption, double maxHealth,
                            int apples, boolean appleReady, double gear, boolean hasBow, int blocks,
                            double borderMargin, boolean borderShrinking, double zoneDistance, double zoneRadius,
                            boolean baseBuilt, boolean baseDone, double baseDistance, boolean baseSafe,
                            boolean highGround, @Nullable Behaviour current, @Nullable UUID currentTarget,
                            List<Contact> contacts, double pressure, boolean finale, int rivalsLeft, double idle) {

        public Situation {
            contacts = List.copyOf(contacts);
            pressure = Double.isFinite(pressure) ? Math.max(0, Math.min(1, pressure)) : 0;
            idle = Double.isFinite(idle) ? Math.max(0, Math.min(1, idle)) : 0;
            rivalsLeft = Math.max(rivalsLeft, 0);
        }

        /** A situation outside the final zone, many rivals left and just out of a fight. */
        public Situation(boolean fight, double farmingLeft, double health, double absorption, double maxHealth,
                         int apples, boolean appleReady, double gear, boolean hasBow, int blocks,
                         double borderMargin, boolean borderShrinking, double zoneDistance, double zoneRadius,
                         boolean baseBuilt, boolean baseDone, double baseDistance, boolean baseSafe,
                         boolean highGround, @Nullable Behaviour current, @Nullable UUID currentTarget,
                         List<Contact> contacts, double pressure) {
            this(fight, farmingLeft, health, absorption, maxHealth, apples, appleReady, gear, hasBow, blocks,
                    borderMargin, borderShrinking, zoneDistance, zoneRadius, baseBuilt, baseDone, baseDistance,
                    baseSafe, highGround, current, currentTarget, contacts, pressure, false, Integer.MAX_VALUE, 0);
        }

        /** The last few in the final zone: they have to fight it out. */
        public boolean showdown() {
            return fight && finale && rivalsLeft <= SHOWDOWN_RIVALS;
        }

        /** A situation at the start of the fight (no pressure yet). */
        public Situation(boolean fight, double farmingLeft, double health, double absorption, double maxHealth,
                         int apples, boolean appleReady, double gear, boolean hasBow, int blocks,
                         double borderMargin, boolean borderShrinking, double zoneDistance, double zoneRadius,
                         boolean baseBuilt, boolean baseDone, double baseDistance, boolean baseSafe,
                         boolean highGround, @Nullable Behaviour current, @Nullable UUID currentTarget,
                         List<Contact> contacts) {
            this(fight, farmingLeft, health, absorption, maxHealth, apples, appleReady, gear, hasBow, blocks,
                    borderMargin, borderShrinking, zoneDistance, zoneRadius, baseBuilt, baseDone, baseDistance,
                    baseSafe, highGround, current, currentTarget, contacts, 0);
        }
    }

    /**
     * What a bot of a team knows about its team (봇전 팀전), or null in free-for-all.
     *
     * @param alliesNear     teammates in play within {@link #NEAR} blocks (they fight with it)
     * @param leaderDistance blocks to its team's leader (0 if it leads)
     * @param cohesion       how far from the leader it may be (blocks, {@code bots.team.cohesion-radius})
     * @param leads          it is the leader (nobody to follow)
     * @param focus          the enemy its team is hitting (focus fire), or null
     * @param peel           an enemy hitting a teammate in trouble, or null
     * @param peelForHuman   that teammate is a human (a protector bot cares even more)
     */
    public record TeamView(int alliesNear, double leaderDistance, double cohesion, boolean leads,
                           @Nullable UUID focus, @Nullable UUID peel, boolean peelForHuman) {

        public TeamView {
            alliesNear = Math.max(0, alliesNear);
            leaderDistance = Double.isFinite(leaderDistance) ? Math.max(0, leaderDistance) : 0;
        }

        /** A teammate with no team news. */
        public static TeamView alone(double cohesion) {
            return new TeamView(0, 0, cohesion, true, null, null, false);
        }
    }

    /** The chosen option. */
    public record Decision(Behaviour behaviour, @Nullable UUID target, double score) {
    }

    /** Range in which enemies count as "near" (1v2 avoidance). */
    static final double NEAR = 18;
    /** Score bonus that keeps the current behaviour and target (no flip-flopping). */
    static final double STICKY = 6;
    /** The bonus of staying in a fight it committed to (a player does not walk away from a duel on a whim). */
    static final double COMMITTED = 15;
    /** A visible enemy this close with this little health is a sure kill (worth staying outside the border for). */
    static final double SURE_KILL_REACH = 4;
    static final double SURE_KILL_HEALTH = 4;
    /** In the final zone with at most this many enemies left, nobody hides, waits or runs any more. */
    static final int SHOWDOWN_RIVALS = 3;
    /** How much more a survivor wants to stay sealed in its burrow while more than {@link #SHOWDOWN_RIVALS} are left. */
    static final double SURVIVOR_HIDE = 45;

    private final BotProfile profile;
    private final BotPersonality.Traits traits;
    private final java.util.Random random;
    /** A gambler's current mood: a random swing per behaviour, re-rolled now and then (not every decision). */
    private final java.util.EnumMap<Behaviour, Double> mood = new java.util.EnumMap<>(Behaviour.class);

    /** The plain utility AI (no personality). */
    public BotBrain(BotProfile profile) {
        this(profile, BotPersonality.Traits.NEUTRAL, new java.util.Random(0));
    }

    /** A mind with a personality: its traits multiply the scores and tune a few thresholds. */
    public BotBrain(BotProfile profile, BotPersonality.Traits traits, java.util.Random random) {
        this.profile = profile;
        this.traits = traits;
        this.random = random;
    }

    public BotPersonality.Traits traits() {
        return traits;
    }

    /** The health at or below which it heals: the difficulty's, times its personality's caution. */
    double healAt() {
        return profile.healAt() * traits.healFactor();
    }

    /** Its bow range: the difficulty's plus its personality's (0 stays 0: that difficulty never shoots). */
    double bowRange() {
        double range = profile.bowRange();
        return range <= 0 ? 0 : Math.max(Math.min(8, range), range + traits.bowRangeBonus());
    }

    double aggression() {
        return clamp(profile.aggression() + traits.aggressionShift(), 0, 1);
    }

    /** Every option with its score, best first (for tests and debugging). */
    public List<Decision> options(Situation s) {
        return scored(s, 0);
    }

    /** The options when {@code allies} teammates fight next to it: they take on that many of the enemies near. */
    private List<Decision> scored(Situation s, int allies) {
        List<Decision> out = new ArrayList<>();
        double hp = s.health() + s.absorption();
        boolean low = s.health() <= healAt();
        double danger = edgeMargin(s.borderShrinking(), s.zoneRadius());
        if (s.borderMargin() < danger) {
            // outside (or about to be): the border hurts every second, more the further out; only a sure kill
            // (below) beats getting back in
            // (outside beats any fight, even the last few's showdown; at the edge a fight still goes on, inside)
            double urgency = s.borderMargin() < 0 ? 175 + Math.min(40, -s.borderMargin() * 3) : 115;
            out.add(new Decision(Behaviour.FLEE_BORDER, null, urgency + (danger - Math.max(0, s.borderMargin())) * 3));
        }
        Set<UUID> sureKills = sureKills(s);
        for (UUID id : sureKills) {
            out.add(new Decision(Behaviour.ENGAGE, id, 200)); // a finishing blow is worth a moment more outside
        }
        if (!s.fight()) {
            // No PvP yet: a smart player farms and builds, it does not chase anyone. A builder sticks to its base
            // until it is closed (a base left half built is no base when the fight starts); the rest farm.
            if (!s.baseDone() && traits.builds()) {
                out.add(new Decision(Behaviour.BUILD_BASE, null, 60 + 10 * s.farmingLeft()));
            } else {
                out.add(new Decision(Behaviour.FARM, null, 40));
            }
            return sorted(weigh(out, 0), s);
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
        // the last few in the final zone fight it out: no hiding, waiting or running any more
        boolean showdown = s.showdown();
        double p = showdown ? 1 : s.pressure();
        // late in the fight everyone grows bolder (nobody can wait for the others forever), and so does a bot that has
        // not fought for a long while
        double baseAggression = aggression();
        double aggression = showdown ? 1 : baseAggression + (1 - baseAggression)
                * Math.min(1, 0.6 * p + 0.4 * s.idle() * Math.max(p, 0.5));
        double maxHp = Math.max(1, s.maxHealth());
        BotPersonality who = traits.personality();
        // a homebody holds its base: it fights from behind its walls and leaves them only for the border, a breach (the
        // controller then calls the base unsafe) or a fight it is clearly winning right outside
        double homebody = traits.homebody();
        boolean home = homebody > 0 && !showdown && s.baseBuilt() && s.baseSafe() && s.baseDistance() < 64;
        boolean grudgeKnown = false;
        for (Contact c : s.contacts()) {
            grudgeKnown |= c.grudge() && traits.grudges();
        }
        boolean inBowRange = false; // a sniper knows someone it could shoot: no waiting in the zone
        for (Contact c : s.contacts()) {
            double advantage = myPower / Math.max(1, power(c.health(), perceived(c.gear())));
            double hurtRatio = c.health() / Math.max(1, c.maxHealth());
            int others = Math.max(0, near - (c.distance() <= NEAR ? 1 : 0) - allies);
            if (c.fightingOther()) {
                if (hurtRatio > 0.5 && !low && !showdown) {
                    // let them wear each other down first (less patient late in the fight)
                    out.add(new Decision(Behaviour.THIRD_PARTY, c.id(), 62 + 10 * aggression - 20 * p));
                    out.add(new Decision(Behaviour.ENGAGE, c.id(), 38 + 30 * aggression + 40 * p));
                } else {
                    out.add(new Decision(Behaviour.ENGAGE, c.id(),
                            55 + 30 * aggression + 20 * (1 - hurtRatio) + 10 * Math.min(1, advantage - 1)));
                }
                continue;
            }
            // a healthy bot that knows of someone it can fight fights them (a weakened one most of all: it chases
            // them down); it backs off only when it is really bad (see AVOID and the low-health options below)
            double engage = 36 + 40 * aggression + 30 * clamp(advantage - 1, -1.5, 1) + 25 * (1 - hurtRatio)
                    - 22 * Math.max(0, others) * (1 - 0.6 * p) - (c.visible() ? 0 : 6)
                    - Math.max(0, c.distance() - 30) * 0.4 * (1 - 0.5 * p) + 15 * p;
            if (low) {
                engage -= 30;
            }
            if (showdown) {
                engage += 25;
            }
            if (c.grudge()) {
                engage += traits.grudges() ? 30 : 8; // payback
            }
            if (traits.sneaks() && hurtRatio < 0.5) {
                engage += 18; // an assassin strikes the weak
            }
            if (traits.personality() == BotPersonality.NOMAD && others == 0 && hurtRatio < 0.8) {
                engage += 10; // a loner is easy prey
            }
            // the signature styles: strong enough that two personalities in one spot visibly act differently
            if (who != null && !showdown) {
                switch (who) {
                    case ARCHER -> engage -= s.hasBow() && c.distance() > 5 ? 22 : 0; // the bow, not the sword
                    case BERSERKER -> engage += 15 + Math.min(10, c.distance() * 0.4); // straight in, whatever the range
                    case HUNTER -> engage += !c.approaching() && c.distance() > 4 ? 18 : 6; // runs down whoever runs
                    case ASSASSIN -> {
                        if (!c.fightingOther() && hurtRatio > 0.6 && c.distance() > 10) {
                            // never a fair fight from afar: it stalks until someone else starts one or it is close
                            // enough to come in from behind
                            engage -= 25;
                            out.add(new Decision(Behaviour.THIRD_PARTY, c.id(), 56 - 20 * p));
                        }
                    }
                    case SURVIVOR -> engage -= s.rivalsLeft() > SHOWDOWN_RIVALS && hurtRatio > 0.35 ? 25 : 0;
                    case OPPORTUNIST -> {
                        if (c.visible() && c.distance() < 40) {
                            out.add(new Decision(Behaviour.THIRD_PARTY, c.id(), 50 - 20 * p)); // shadows from afar
                        }
                    }
                    case AVENGER -> engage -= grudgeKnown && !c.grudge() ? 30 : 0; // nobody else matters
                    default -> {
                    }
                }
            }
            if (c.trapped()) {
                engage += traits.traps() ? 30 : 15; // stuck in a web: punish it
            }
            if (home) {
                if (c.distance() < 6 && hurtRatio < 0.4 && advantage > 1.2) {
                    engage += 25 * homebody; // a weak one right outside its walls: out and finish it
                } else {
                    // it would rather meet them at its walls: the farther they are, the less it goes out
                    engage -= homebody * (20 + Math.max(0, c.distance() - 8));
                }
            }
            out.add(new Decision(Behaviour.ENGAGE, c.id(), engage));
            if (traits.sneaks() && !showdown && hp < maxHp * 0.55 && c.distance() < 10) {
                // the strike failed: an assassin gets out fast
                out.add(new Decision(Behaviour.AVOID, c.id(), 70 - 25 * p));
            }
            if (traits.personality() == BotPersonality.NOMAD && !showdown && others >= 1) {
                out.add(new Decision(Behaviour.AVOID, c.id(), 66 - 25 * p)); // never gets cornered
            }
            if (s.hasBow() && bowRange() > 0 && c.distance() > 7 && c.distance() <= bowRange()
                    && c.visible()) {
                double kite = 42 + 25 * clamp(1 - advantage, 0, 1) + (c.hasBow() ? -8 : 6);
                if (who == BotPersonality.ARCHER || who == BotPersonality.NOMAD) {
                    kite += 30; // the bow is how it fights
                }
                if (c.trapped()) {
                    kite += 15;
                }
                if (grudgeKnown && !c.grudge()) {
                    kite -= 20;
                }
                out.add(new Decision(Behaviour.KITE, c.id(), kite));
            }
            if (who == BotPersonality.ARCHER && s.hasBow() && bowRange() > 0 && !c.visible() && c.distance() > 7
                    && c.distance() <= bowRange() && !(grudgeKnown && !c.grudge())) {
                // a sniper that knows someone in range moves for a line of fire (another slit, down from its nest)
                inBowRange = true;
                out.add(new Decision(Behaviour.KITE, c.id(), 56 + 25 * clamp(1 - advantage, 0, 1)));
            } else if (who == BotPersonality.ARCHER && s.hasBow() && c.distance() <= bowRange()) {
                inBowRange = true;
            }
            // an even match (advantage around 1) alone is fought, not avoided by both sides
            if (!showdown && (advantage < 0.75 - 0.25 * p || others >= 1 && advantage < 1.6 - 0.5 * p)) {
                out.add(new Decision(Behaviour.AVOID, c.id(), (58 + 40 * clamp(0.9 - advantage, 0, 1)
                        + 12 * Math.max(0, others)) * (1 - 0.4 * p)));
            }
            if (!showdown && c.approaching() && c.distance() >= 8 && c.distance() <= 26 && advantage >= 0.85
                    && (s.highGround() || s.baseBuilt() && s.baseDistance() < 4)) {
                out.add(new Decision(Behaviour.AMBUSH, c.id(), 52 + 10 * aggression - 15 * p));
            }
        }
        if (low && showdown) {
            // only to eat an apple, and only inside the zone (the controller keeps every step in it)
            if (s.apples() > 0 && s.appleReady()) {
                out.add(new Decision(Behaviour.RETREAT_HEAL, null, 60 + (threatened ? 4 : 0)));
            }
        } else if (low) {
            if (threatened && s.blocks() >= 8 && (s.apples() == 0 || !s.appleReady()
                    || nearestThreat.distance() < 8)) {
                out.add(new Decision(Behaviour.FORTIFY, null, (s.apples() == 0 ? 86 : 78) - 20 * p));
            }
            out.add(new Decision(Behaviour.RETREAT_HEAL, null, (s.apples() > 0 ? 80 : 64 - 25 * p)
                    + (threatened ? 4 : 0)));
        }
        if (home) {
            // its base is where it fights: back to it when the fight starts, when anyone shows up, to heal; it waits
            // there less as the fight drags on and when nobody comes for a long while
            double score = 45 + 40 * homebody - 25 * p - 20 * s.idle() - s.baseDistance() * 0.15
                    + (s.contacts().isEmpty() ? -10 : 8) + (low ? 20 : 0) + (near >= 2 ? 10 : 0);
            if (who == BotPersonality.SURVIVOR && s.rivalsLeft() > SHOWDOWN_RIVALS) {
                // a survivor sits sealed in its burrow while many are left (the border or a breach gets it out);
                // with the last few left it comes out to ambush
                score += SURVIVOR_HIDE;
            }
            out.add(new Decision(Behaviour.DEFEND_BASE, null, score));
        } else if (!showdown && s.baseBuilt() && s.baseSafe() && s.baseDistance() < 48
                && (low || !s.contacts().isEmpty())) {
            // the base is a place to fall back to when hunted or hurt, not a place to sit out the match in
            // an enemy walking up to its walls is fought from behind them (the slit, the doorway)
            boolean comingClose = nearestThreat != null && nearestThreat.approaching() && s.baseDistance() < 12
                    && nearestThreat.distance() < 30;
            double wantBase = (low ? 30 : 0) + (near >= 2 ? 20 : 0) + (comingClose ? 18 : 0)
                    + (comingClose && s.hasBow() ? 8 : 0);
            out.add(new Decision(Behaviour.DEFEND_BASE, null, 30 + wantBase - s.baseDistance() * 0.3 - 20 * p));
        }
        // a bot that loves its base rebuilds it where the zone will be once the border is about to take the old one
        if (!showdown && s.contacts().isEmpty() && traits.builds() && traits.weight(Behaviour.DEFEND_BASE) >= 1.3
                && s.blocks() >= 60 && p < 0.6 && s.baseBuilt() && !s.baseSafe()) {
            out.add(new Decision(Behaviour.BUILD_BASE, null, 40));
        }
        if (s.borderShrinking() && !showdown) {
            // early in the shrink: get to the zone in time; later, sitting in it waiting is no plan
            double outside = Math.max(0, s.zoneDistance() - s.zoneRadius() * 0.6);
            out.add(new Decision(Behaviour.HOLD_ZONE, null, 38 + Math.min(20, outside * 0.5) - 15 * p
                    - (s.contacts().isEmpty() ? 0 : 12) - (inBowRange ? 25 : 0)));
        }
        // nobody known: go looking (the controller heads for the last known or heard rival, else the zone); a bot that
        // has not fought for a long while and the last few in the final zone go looking hardest
        out.add(new Decision(Behaviour.SCOUT, null, showdown ? (s.contacts().isEmpty() ? 70 : 40)
                : 25 + 15 * p + 25 * s.idle() * p));
        return borderFirst(sorted(weigh(out, showdown ? 1 : p), s), sureKills);
    }

    /**
     * The enemies it could finish right now (visible, within {@link #SURE_KILL_REACH}, at most
     * {@link #SURE_KILL_HEALTH} health, and it is not low itself): the only reason to stay outside the border a moment.
     * Only these are exempt from {@link #borderFirst}; an option's score never makes a sure kill (a capped score
     * can be 200 or more when getting back in is urgent).
     */
    Set<UUID> sureKills(Situation s) {
        Set<UUID> out = new HashSet<>();
        if (!s.fight() || s.health() <= healAt()) {
            return out;
        }
        for (Contact c : s.contacts()) {
            if (c.visible() && c.distance() <= SURE_KILL_REACH && c.health() <= SURE_KILL_HEALTH) {
                out.add(c.id());
            }
        }
        return out;
    }

    /**
     * Getting back inside the border beats everything but a sure kill: no personality weight, base, hold, ambush or
     * shadowing (however strongly weighted, sticky or committed) may keep a bot where the closing border will be
     * before it could walk in. Every other option is capped just under {@link Behaviour#FLEE_BORDER}.
     */
    static List<Decision> borderFirst(List<Decision> sorted, Set<UUID> sureKills) {
        Decision flee = null;
        for (Decision d : sorted) {
            if (d.behaviour() == Behaviour.FLEE_BORDER) {
                flee = d;
                break;
            }
        }
        if (flee == null || sorted.getFirst() == flee) {
            return sorted;
        }
        List<Decision> out = new ArrayList<>(sorted.size());
        for (Decision d : sorted) {
            boolean sureKill = d.behaviour() == Behaviour.ENGAGE && sureKills.contains(d.target());
            out.add(d == flee || sureKill || d.score() < flee.score() ? d
                    : new Decision(d.behaviour(), d.target(), flee.score() - 1));
        }
        out.sort((a, b) -> Double.compare(b.score(), a.score()));
        return out;
    }

    /**
     * The personality's multipliers on every option (not on getting back inside the border or a sure kill), fading
     * towards 1 as the fight gets late (everyone has to fight in the end), plus a gambler's mood.
     */
    private List<Decision> weigh(List<Decision> options, double pressure) {
        if (traits.volatility() > 0 && (mood.isEmpty() || random.nextDouble() < 0.12)) {
            for (Behaviour behaviour : Behaviour.values()) {
                mood.put(behaviour, (random.nextDouble() * 2 - 1) * traits.volatility());
            }
        }
        List<Decision> out = new ArrayList<>(options.size());
        for (Decision d : options) {
            if (d.behaviour() == Behaviour.FLEE_BORDER || d.score() >= 200) {
                out.add(d);
                continue;
            }
            double w = traits.weight(d.behaviour());
            if (w <= 0.01) {
                continue; // never (a nomad's base, a berserker's bow)
            }
            if (d.behaviour() == Behaviour.DEFEND_BASE && traits.homebody() > 0) {
                w = 1; // a homebody's base score is its own already
            }
            // the personality counts a lot (two personalities in one spot act differently), fading a little as the
            // fight gets late
            double m = 1 + (Math.pow(w, 1.5) - 1) * (1 - 0.4 * pressure);
            double swing = mood.getOrDefault(d.behaviour(), 0.0) * (1 - pressure);
            out.add(new Decision(d.behaviour(), d.target(), d.score() * m + swing));
        }
        return out;
    }

    /**
     * How close to the border's edge a bot may be before getting back in is its first concern: the margin shrinks with
     * the final zone (in a 24-block final zone 10 blocks would be everywhere); the controller keeps every destination
     * a block further inside.
     */
    static double edgeMargin(boolean shrinking, double zoneRadius) {
        return shrinking ? Math.max(2, Math.min(10, zoneRadius * 0.35)) : 3;
    }

    /** The best option. */
    public Decision decide(Situation s) {
        return options(s).getFirst();
    }

    /** The best option for a bot of a team (null {@code team}: free-for-all, as {@link #decide(Situation)}). */
    public Decision decide(Situation s, @Nullable TeamView team) {
        return options(s, team).getFirst();
    }

    /** Engage bonus for the enemy the team is hitting (focus fire). */
    static final double FOCUS_BONUS = 26;
    /** Engage bonus for the enemy hitting a teammate in trouble (peel). */
    static final double PEEL_BONUS = 34;
    /** Engage bonus per teammate close by (numbers win fights). */
    static final double ALLY_BONUS = 7;

    /**
     * {@link #options(Situation)} for a bot of a team: the enemies its teammates fight do not count against it (no
     * "1v2" when it is 2v2), it prefers the target its team hits and the one hurting a teammate in trouble (a
     * protector, the tank and the fortress builder, most of all for a human teammate), it never waits for a teammate's
     * fight to wear the enemy down (that is its own fight) and it goes back to the team when it strays farther than
     * the cohesion radius. The border still beats everything ({@link #borderFirst}).
     */
    public List<Decision> options(Situation s, @Nullable TeamView team) {
        if (team == null) {
            return options(s);
        }
        List<Decision> base = scored(s, s.fight() ? team.alliesNear() : 0);
        List<Decision> out = new ArrayList<>(base.size() + 2);
        BotPersonality who = traits.personality();
        boolean protector = who == BotPersonality.TANK || who == BotPersonality.FORTRESS
                || who == BotPersonality.AVENGER;
        boolean loner = who == BotPersonality.NOMAD || who == BotPersonality.ASSASSIN;
        // the same sure kills as the free-for-all options, never "any ENGAGE scored 200 or more": an ENGAGE capped
        // just under an urgent FLEE_BORDER scores that much, and the team bonuses would then lift it over the border
        Set<UUID> sureKills = sureKills(s);
        for (Decision d : base) {
            double score = d.score();
            if (s.fight() && d.target() != null) {
                boolean teamFight = d.target().equals(team.focus()) || d.target().equals(team.peel());
                if (d.behaviour() == Behaviour.THIRD_PARTY && teamFight) {
                    continue; // its teammate's fight is its own fight
                }
                if (d.behaviour() == Behaviour.AVOID && team.alliesNear() > 0) {
                    score -= 18 * team.alliesNear();
                }
                if (d.behaviour() == Behaviour.ENGAGE || d.behaviour() == Behaviour.KITE) {
                    if (d.target().equals(team.focus())) {
                        score += d.behaviour() == Behaviour.ENGAGE ? FOCUS_BONUS : FOCUS_BONUS * 0.6;
                    }
                    if (d.target().equals(team.peel())) {
                        score += PEEL_BONUS * (protector ? 1.5 : 1) * (team.peelForHuman() ? 1.3 : 1);
                    }
                    score += ALLY_BONUS * Math.min(3, team.alliesNear());
                }
            }
            out.add(score == d.score() ? d : new Decision(d.behaviour(), d.target(), score));
        }
        if (s.fight() && team.peel() != null && contactOf(s, team.peel()) == null) {
            // a teammate in trouble against someone it has not seen itself: it goes to help (its teammate saw them)
            out.add(new Decision(Behaviour.REGROUP, null, 48 + (protector ? 18 : 0) + (team.peelForHuman() ? 10 : 0)));
        }
        if (!team.leads() && team.leaderDistance() > team.cohesion()) {
            double stray = team.leaderDistance() - team.cohesion();
            double score = 34 + Math.min(42, stray * 1.4);
            if (!s.fight()) {
                score -= 10; // farming: loosely around the team (a builder keeps building, see below)
            }
            if (loner) {
                score -= 12; // a lone wolf comes along, grudgingly
            }
            for (Contact c : s.contacts()) {
                if (c.visible() && c.distance() < 12) {
                    score -= 25; // fight first, regroup after
                    break;
                }
            }
            if (s.health() <= healAt()) {
                score += 12; // hurt: back to the team
            }
            out.add(new Decision(Behaviour.REGROUP, null, score));
        }
        if (!s.fight()) {
            // a builder that has not closed its base keeps building (the team base is the team's)
            for (Decision d : out) {
                if (d.behaviour() == Behaviour.BUILD_BASE) {
                    out.removeIf(o -> o.behaviour() == Behaviour.REGROUP);
                    break;
                }
            }
        }
        out.sort((a, b) -> Double.compare(b.score(), a.score()));
        return borderFirst(out, sureKills); // no team bonus passes the way back inside the border
    }

    private static @Nullable Contact contactOf(Situation s, UUID id) {
        for (Contact c : s.contacts()) {
            if (c.id().equals(id)) {
                return c;
            }
        }
        return null;
    }


    private List<Decision> sorted(List<Decision> options, Situation s) {
        List<Decision> out = new ArrayList<>(options.size());
        for (Decision d : options) {
            boolean same = d.behaviour() == s.current()
                    && (d.target() == null || d.target().equals(s.currentTarget()));
            boolean committed = same && d.behaviour() == Behaviour.ENGAGE && d.target() != null;
            out.add(same ? new Decision(d.behaviour(), d.target(), d.score() + (committed ? COMMITTED : STICKY)) : d);
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
