package io.github.sx2xoox.mcpl.game.siege.bot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * What a 공성전 봇전 bot does next (pure, no Bukkit; docs/siege-v2.md §9). One brain per bot; the controller hands it a
 * {@link View} of what the bot sees every reaction time and carries out the {@link Plan}. In order:
 *
 * <ol>
 *     <li>hurt at the base: wait there until healed;</li>
 *     <li>low health ({@link SiegeBotSettings.Profile#retreatAt()}): recall when no enemy is close, no tower aims at
 *     it and nothing hit it lately, else retreat down the lane;</li>
 *     <li>enemy players or bots in reach: fight when the fight looks good (health of both groups, aggression; a nearly
 *     dead enemy is always worth it), focusing the lowest health (by difficulty), else back off;</li>
 *     <li>standing where an enemy tower would shoot it without its own minions there to take the shots: back off;</li>
 *     <li>an own structure under attack nearby: defend it;</li>
 *     <li>enemy minions close: farm them (by difficulty aiming at the one its next hit kills);</li>
 *     <li>an enemy structure in reach that is not shielded and safe to hit (own minions tank it, or a core, which does
 *     not shoot): hit it;</li>
 *     <li>a quiet lane (no minions of either side near): now and then a jungle camp of its side no other bot goes for,
 *     or the dragon when allies are around it; a camp not reached in {@link #CAMP_GIVE_UP_TICKS} (or not taken in
 *     three times that) is given up;</li>
 *     <li>else push: walk behind the front of its own minions, or wait in front of its own outer tower.</li>
 * </ol>
 *
 * It never chooses a target or a place inside an enemy tower's range that its own minions do not tank.
 */
public final class SiegeBotBrain {

    /** How the job fights: where it stands behind its minions and whether it heals. */
    public enum Role { MELEE, RANGED, SUPPORT }

    public enum Action {
        /** Stand still (healing at the base). */
        WAIT,
        /** Walk to the goal along the lane: behind the own minions' front. */
        PUSH,
        /** Attack the enemy minion {@link Plan#target()}. */
        FARM,
        /** Attack the enemy player or bot {@link Plan#target()}. */
        FIGHT,
        /** Attack the enemy structure {@link Plan#structure()}. */
        SIEGE,
        /** Walk to the own structure under attack. */
        DEFEND,
        /** Walk back to the goal (away from danger). */
        RETREAT,
        /** Channel the recall to the base. */
        RECALL,
        /** Take the jungle camp (or the dragon) {@link Plan#camp()} at the goal. */
        JUNGLE
    }

    /** What a {@link Unit} is. */
    public enum Kind { CHAMPION, MINION, MONSTER }

    /** A place on the ground plane. */
    public record Vec(double x, double z) {
        public double distance(Vec other) {
            return Math.hypot(x - other.x, z - other.z);
        }
    }

    /** Something the bot sees: a player or bot ({@link Kind#CHAMPION}), a minion or a jungle monster. */
    public record Unit(UUID id, Kind kind, Vec at, double health, double maxHealth, boolean enemy) {
        public double fraction() {
            return maxHealth <= 0 ? 0 : Math.max(0, Math.min(1, health / maxHealth));
        }
    }

    /** A standing tower or core; {@code underAttack}: it was hit lately. */
    public record Structure(String id, Vec at, boolean enemy, boolean shielded, boolean core, boolean underAttack) {
    }

    /**
     * A jungle camp that is up: {@code allies}/{@code enemies} are the players and bots near it (the dragon is taken
     * together); {@code claimed}: another bot of the side already goes for it.
     */
    public record Camp(String id, Vec at, boolean dragon, int allies, int enemies, boolean claimed) {
    }

    /** The bot itself; {@code hurt}: something hit it within the last seconds (no recall then). */
    public record Self(UUID id, Vec at, double health, double maxHealth, double damage, double range, Role role,
                       boolean atBase, boolean hurt) {
        public double fraction() {
            return maxHealth <= 0 ? 0 : Math.max(0, Math.min(1, health / maxHealth));
        }
    }

    /**
     * What the bot sees: itself, its lane (from its base to the enemy base), its base, the units within
     * {@link #SEE} blocks, the standing structures, the camps it could take, and the towers' shooting range.
     */
    public record View(long tick, Self self, LanePath lane, Vec base, List<Unit> units, List<Structure> structures,
                       List<Camp> camps, double towerRange) {
    }

    /** What to do: an action, where to go, and its target (a unit, a structure or a camp). */
    public record Plan(Action action, Vec goal, @Nullable UUID target, @Nullable String structure,
                       @Nullable String camp) {

        static Plan go(Action action, Vec goal) {
            return new Plan(action, goal, null, null, null);
        }
    }

    /** Units farther than this are not part of a view. */
    public static final double SEE = 40;
    /** Enemy players and bots within this are a fight to consider. */
    public static final double ENGAGE = 12;
    /** Minions within this are farmed. */
    public static final double FARM = 12;
    /** Own structures under attack within this are defended. */
    public static final double DEFEND = 60;
    /** Extra blocks kept out of an enemy tower's range. */
    public static final double MARGIN = 1.5;
    /** Recall only with no enemy closer than this. */
    public static final double RECALL_SAFE = 10;
    /** Camps of its side farther than this are not considered. */
    public static final double JUNGLE_REACH = 45;
    /** How often a quiet bot thinks about the jungle. */
    public static final int JUNGLE_ROLL_TICKS = 100;
    /** Players and bots of a side near the dragon (the bot included) before it goes for it. */
    public static final int DRAGON_ALLIES = 2;
    /** Health share it waits for at the base. */
    public static final double HEALED = 0.95;
    /** A camp not reached within this long is given up (it may be out of reach); not taken in 3× this, too. */
    public static final int CAMP_GIVE_UP_TICKS = 600;
    /** After giving up (or taking) a camp, no jungle for this long. */
    public static final int JUNGLE_REST_TICKS = 1200;

    private final SiegeBotSettings.Profile profile;
    private final Random random;
    private @Nullable UUID lastTarget;
    private @Nullable String camp;
    private long campSince;
    private long nextJungleRoll;

    public SiegeBotBrain(SiegeBotSettings.Profile profile, Random random) {
        this.profile = profile;
        this.random = random;
    }

    /** The camp it went for (null: none). */
    public @Nullable String camp() {
        return camp;
    }

    /** Gives up the camp it went for (out of reach): no jungle for {@link #JUNGLE_REST_TICKS}. */
    public void giveUpCamp(long tick) {
        camp = null;
        nextJungleRoll = tick + JUNGLE_REST_TICKS;
    }

    /** Forgets plans (a death, a new round). */
    public void reset() {
        lastTarget = null;
        camp = null;
        nextJungleRoll = 0;
    }

    public Plan decide(View v) {
        Self me = v.self();
        double hp = me.fraction();
        List<Unit> foes = champions(v, true);
        List<Unit> friends = champions(v, false);
        double closest = closest(me.at(), foes);
        if (me.atBase() && hp < HEALED && closest > 8) {
            camp = null;
            return Plan.go(Action.WAIT, me.at());
        }
        Structure threat = unsafe(v, me.at());
        if (hp <= profile.retreatAt()) {
            camp = null;
            if (closest > RECALL_SAFE && threat == null && !me.hurt()) {
                return Plan.go(Action.RECALL, me.at());
            }
            return Plan.go(Action.RETREAT, retreatPoint(v));
        }
        Unit target = pickChampion(v, foes);
        if (target != null) {
            double mine = hp;
            for (Unit friend : friends) {
                mine += friend.fraction();
            }
            double theirs = 0;
            for (Unit foe : foes) {
                theirs += foe.fraction();
            }
            boolean good = mine * (0.6 + profile.aggression()) >= theirs
                    || target.fraction() < 0.3 && hp > 0.45;
            if (good) {
                lastTarget = target.id();
                return new Plan(Action.FIGHT, target.at(), target.id(), null, null);
            }
        }
        if (!foes.isEmpty() && closest < 7) {
            camp = null;
            return Plan.go(Action.RETREAT, retreatPoint(v));
        }
        lastTarget = null;
        if (threat != null) {
            return Plan.go(Action.RETREAT, retreatPoint(v));
        }
        Structure attacked = attackedStructure(v);
        if (attacked != null) {
            camp = null;
            return new Plan(Action.DEFEND, attacked.at(), null, attacked.id(), null);
        }
        Camp committed = committedCamp(v);
        long committedFor = v.tick() - campSince;
        if (committed != null && (committedFor > CAMP_GIVE_UP_TICKS && committed.at().distance(me.at()) > 10
                || committedFor > 3L * CAMP_GIVE_UP_TICKS)) {
            // not there in time (out of reach?), or fighting it far too long
            giveUpCamp(v.tick());
            committed = null;
        }
        if (committed != null) {
            return new Plan(Action.JUNGLE, committed.at(), null, null, committed.id());
        }
        List<Unit> minions = minions(v);
        Structure siege = siegeTarget(v);
        boolean close = false;
        for (Unit minion : minions) {
            close |= minion.at().distance(me.at()) <= me.range() + 3;
        }
        if (!minions.isEmpty() && (close || siege == null)) {
            Unit minion = pickMinion(me, minions);
            return new Plan(Action.FARM, minion.at(), minion.id(), null, null);
        }
        if (siege != null) {
            return new Plan(Action.SIEGE, siege.at(), null, siege.id(), null);
        }
        if (foes.isEmpty() && hp > 0.6 && v.tick() >= nextJungleRoll && !alliedMinionsNear(v)) {
            nextJungleRoll = v.tick() + JUNGLE_ROLL_TICKS;
            Camp pick = pickCamp(v);
            if (pick != null && random.nextDouble() < Math.min(1, profile.jungleChance() * (pick.dragon() ? 2 : 1))) {
                camp = pick.id();
                campSince = v.tick();
                return new Plan(Action.JUNGLE, pick.at(), null, null, pick.id());
            }
        }
        return Plan.go(Action.PUSH, pushPoint(v));
    }

    // ------------------------------------------------------------------ choices

    private @Nullable Unit pickChampion(View v, List<Unit> foes) {
        List<Unit> safe = new ArrayList<>();
        for (Unit foe : foes) {
            if (unsafe(v, foe.at()) == null) {
                safe.add(foe);
            }
        }
        if (safe.isEmpty()) {
            return null;
        }
        Unit kept = null;
        for (Unit foe : safe) {
            if (foe.id().equals(lastTarget)) {
                kept = foe;
            }
        }
        Unit lowest = safe.stream().min(Comparator.comparingDouble(Unit::health)).orElseThrow();
        if (kept != null && !(lowest.fraction() < 0.25 && lowest != kept && profile.focusLowest() >= 0.5)) {
            return kept;
        }
        if (random.nextDouble() < profile.focusLowest()) {
            return lowest;
        }
        Vec at = v.self().at();
        return safe.stream().min(Comparator.comparingDouble(foe -> foe.at().distance(at))).orElseThrow();
    }

    private Unit pickMinion(Self me, List<Unit> minions) {
        if (random.nextDouble() < profile.lastHit()) {
            Unit killable = null;
            for (Unit minion : minions) {
                if (minion.health() <= me.damage() && (killable == null || minion.health() < killable.health())) {
                    killable = minion;
                }
            }
            if (killable != null) {
                return killable;
            }
            return minions.stream().min(Comparator.comparingDouble(Unit::health)).orElseThrow();
        }
        return minions.stream().min(Comparator.comparingDouble(m -> m.at().distance(me.at()))).orElseThrow();
    }

    /** An enemy structure in reach it may hit now: not shielded, and a core or tanked by its own minions. */
    private @Nullable Structure siegeTarget(View v) {
        Structure best = null;
        double bestDistance = ENGAGE + 6;
        for (Structure structure : v.structures()) {
            if (!structure.enemy() || structure.shielded()) {
                continue;
            }
            double distance = structure.at().distance(v.self().at());
            if (distance <= bestDistance && (structure.core() || tankers(v, structure) > 0)) {
                best = structure;
                bestDistance = distance;
            }
        }
        return best;
    }

    private @Nullable Structure attackedStructure(View v) {
        Structure best = null;
        double bestDistance = DEFEND;
        for (Structure structure : v.structures()) {
            if (structure.enemy() || !structure.underAttack()) {
                continue;
            }
            double distance = structure.at().distance(v.self().at());
            boolean mine = structure.core() || v.lane().distance(structure.at()) < 14;
            if (mine && distance > 8 && distance < bestDistance) {
                best = structure;
                bestDistance = distance;
            }
        }
        return best;
    }

    private @Nullable Camp committedCamp(View v) {
        if (camp == null) {
            return null;
        }
        for (Camp up : v.camps()) {
            if (up.id().equals(camp)) {
                return up;
            }
        }
        camp = null;
        return null;
    }

    /** The dragon when enough allies are around it and no enemy, else the nearest camp of its own side. */
    private @Nullable Camp pickCamp(View v) {
        Vec at = v.self().at();
        Camp best = null;
        double bestDistance = JUNGLE_REACH;
        for (Camp up : v.camps()) {
            double distance = up.at().distance(at);
            if (up.dragon()) {
                if (up.allies() + 1 >= DRAGON_ALLIES && up.enemies() == 0 && v.self().fraction() > 0.75) {
                    return up;
                }
                continue;
            }
            if (up.enemies() == 0 && !up.claimed() && distance < bestDistance) {
                best = up;
                bestDistance = distance;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ places

    /** Behind the front of its own minions on the lane, or in front of its own outer tower; never in tower range. */
    private Vec pushPoint(View v) {
        LanePath lane = v.lane();
        double front = -1;
        for (Unit unit : v.units()) {
            if (unit.kind() == Kind.MINION && !unit.enemy() && lane.distance(unit.at()) < 10) {
                front = Math.max(front, lane.progress(unit.at()));
            }
        }
        double goal;
        if (front >= 0) {
            goal = front - switch (v.self().role()) {
                case MELEE -> 1.5;
                case RANGED -> 4;
                case SUPPORT -> 6;
            };
        } else {
            goal = holdProgress(v);
        }
        goal = Math.min(goal, lane.length());
        while (goal > 0 && unsafe(v, lane.pointAt(goal)) != null) {
            goal -= 2;
        }
        return lane.pointAt(Math.max(0, goal));
    }

    /** A little in front of its side's frontmost standing tower on its lane. */
    private double holdProgress(View v) {
        LanePath lane = v.lane();
        double hold = -1;
        for (Structure structure : v.structures()) {
            if (!structure.enemy() && !structure.core() && lane.distance(structure.at()) < 12) {
                hold = Math.max(hold, lane.progress(structure.at()));
            }
        }
        return hold < 0 ? 15 : hold + 6;
    }

    /** Back down its lane, out of every enemy tower's range (its base when near it). */
    private Vec retreatPoint(View v) {
        LanePath lane = v.lane();
        double at = lane.progress(v.self().at()) - 14;
        if (at <= 6 || lane.distance(v.self().at()) > 25) {
            return v.base();
        }
        while (at > 0 && unsafe(v, lane.pointAt(at)) != null) {
            at -= 2;
        }
        return at <= 6 ? v.base() : lane.pointAt(at);
    }

    /**
     * The enemy tower that would shoot at {@code at} without its own minions there to take the shots (cores do not
     * shoot), or null when it is safe.
     */
    @Nullable Structure unsafe(View v, Vec at) {
        double reach = v.towerRange() + MARGIN;
        for (Structure structure : v.structures()) {
            if (structure.enemy() && !structure.core() && structure.at().distance(at) <= reach
                    && tankers(v, structure) == 0) {
                return structure;
            }
        }
        return null;
    }

    /** Its own minions inside the range of the enemy tower {@code structure}. */
    private static int tankers(View v, Structure structure) {
        int count = 0;
        for (Unit unit : v.units()) {
            if (unit.kind() == Kind.MINION && !unit.enemy() && unit.at().distance(structure.at()) <= v.towerRange()) {
                count++;
            }
        }
        return count;
    }

    // ------------------------------------------------------------------ helpers

    private static List<Unit> champions(View v, boolean enemy) {
        List<Unit> out = new ArrayList<>();
        for (Unit unit : v.units()) {
            if (unit.kind() == Kind.CHAMPION && unit.enemy() == enemy && !unit.id().equals(v.self().id())
                    && unit.at().distance(v.self().at()) <= ENGAGE) {
                out.add(unit);
            }
        }
        return out;
    }

    /** Enemy minions near it that stand where it may go. */
    private List<Unit> minions(View v) {
        List<Unit> out = new ArrayList<>();
        for (Unit unit : v.units()) {
            if (unit.kind() == Kind.MINION && unit.enemy() && unit.at().distance(v.self().at()) <= FARM
                    && unsafe(v, unit.at()) == null) {
                out.add(unit);
            }
        }
        return out;
    }

    /** Its own minions on the lane near it: a wave to walk with, no time for the jungle. */
    private static boolean alliedMinionsNear(View v) {
        for (Unit unit : v.units()) {
            if (unit.kind() == Kind.MINION && !unit.enemy() && unit.at().distance(v.self().at()) <= FARM + 6) {
                return true;
            }
        }
        return false;
    }

    private static double closest(Vec at, List<Unit> units) {
        double best = Double.MAX_VALUE;
        for (Unit unit : units) {
            best = Math.min(best, unit.at().distance(at));
        }
        return best;
    }
}
