package io.github.sx2xoox.mcpl.game.siege.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import io.github.sx2xoox.mcpl.game.siege.bot.SiegeBotBrain.Action;
import io.github.sx2xoox.mcpl.game.siege.bot.SiegeBotBrain.Camp;
import io.github.sx2xoox.mcpl.game.siege.bot.SiegeBotBrain.Kind;
import io.github.sx2xoox.mcpl.game.siege.bot.SiegeBotBrain.Plan;
import io.github.sx2xoox.mcpl.game.siege.bot.SiegeBotBrain.Role;
import io.github.sx2xoox.mcpl.game.siege.bot.SiegeBotBrain.Self;
import io.github.sx2xoox.mcpl.game.siege.bot.SiegeBotBrain.Structure;
import io.github.sx2xoox.mcpl.game.siege.bot.SiegeBotBrain.Unit;
import io.github.sx2xoox.mcpl.game.siege.bot.SiegeBotBrain.Vec;
import io.github.sx2xoox.mcpl.game.siege.bot.SiegeBotBrain.View;
import org.junit.jupiter.api.Test;

/** The 봇전 bots' decisions on a straight lane from x = 0 (own base side) to x = 100, towers shooting 10 blocks. */
class SiegeBotBrainTest {

    private static final LanePath LANE = new LanePath(List.of(new Vec(0, 0), new Vec(50, 0), new Vec(100, 0)));
    private static final Vec BASE = new Vec(-5, 0);
    private static final UUID ME = UUID.randomUUID();

    /** Picks the lowest health and the last hit always, goes to the jungle whenever it may. */
    private static final SiegeBotSettings.Profile SHARP = new SiegeBotSettings.Profile(4, 1, 1, 0.3, 0.6, 1, 1, 1, 1);
    /** Picks the nearest, never the jungle. */
    private static final SiegeBotSettings.Profile PLAIN = new SiegeBotSettings.Profile(4, 1, 1, 0.3, 0.6, 0, 0, 0, 1);

    private final List<Unit> units = new ArrayList<>();
    private final List<Structure> structures = new ArrayList<>();
    private final List<Camp> camps = new ArrayList<>();

    private static Self self(double x, double health) {
        return new Self(ME, new Vec(x, 0), health, 20, 4, 3, Role.MELEE, false, false);
    }

    private View view(Self self) {
        return new View(1000, self, LANE, BASE, units, structures, camps, 10);
    }

    private Unit unit(Kind kind, double x, double z, double health, boolean enemy) {
        Unit unit = new Unit(UUID.randomUUID(), kind, new Vec(x, z), health, 20, enemy);
        units.add(unit);
        return unit;
    }

    private Structure tower(String id, double x, boolean enemy) {
        Structure tower = new Structure(id, new Vec(x, 0), enemy, false, false, false);
        structures.add(tower);
        return tower;
    }

    private static Plan decide(SiegeBotSettings.Profile profile, View view) {
        return new SiegeBotBrain(profile, new Random(7)).decide(view);
    }

    @Test
    void lanePathMeasuresProgressAlongTheLane() {
        LanePath bent = new LanePath(List.of(new Vec(0, 0), new Vec(10, 0), new Vec(10, 10)));
        assertEquals(20, bent.length(), 1.0E-9);
        assertEquals(5, bent.progress(new Vec(5, 3)), 1.0E-9);
        assertEquals(3, bent.distance(new Vec(5, 3)), 1.0E-9);
        assertEquals(15, bent.progress(new Vec(12, 5)), 1.0E-9);
        assertEquals(new Vec(10, 5), bent.pointAt(15));
        assertEquals(new Vec(0, 0), bent.pointAt(-4));
        assertEquals(new Vec(10, 10), bent.pointAt(99));
    }

    @Test
    void lowHealthRecallsWhenSafeAndRetreatsWhenChased() {
        assertEquals(Action.RECALL, decide(SHARP, view(self(40, 4))).action());
        unit(Kind.CHAMPION, 44, 0, 20, true);
        Plan plan = decide(SHARP, view(self(40, 4)));
        assertEquals(Action.RETREAT, plan.action());
        assertTrue(LANE.progress(plan.goal()) < 40, "back down its own lane");
    }

    @Test
    void itDoesNotRecallWhileSomethingHitsIt() {
        Self hit = new Self(ME, new Vec(40, 0), 4, 20, 4, 3, Role.MELEE, false, true);
        assertEquals(Action.RETREAT, decide(SHARP, view(hit)).action(), "a minion or monster would break the recall");
    }

    @Test
    void aCampAnotherBotGoesForIsLeftToIt() {
        camps.add(new Camp("blue_jelly", new Vec(30, 20), false, 0, 0, true));
        assertNull(decide(SHARP, view(self(30, 20))).camp());
    }

    @Test
    void aCampThatIsNotTakenInTimeIsGivenUp() {
        SiegeBotBrain brain = new SiegeBotBrain(SHARP, new Random(3));
        camps.add(new Camp("blue_jelly", new Vec(30, 20), false, 0, 0, false));
        assertEquals(Action.JUNGLE, brain.decide(view(self(30, 20))).action());
        View late = new View(1000 + SiegeBotBrain.CAMP_GIVE_UP_TICKS + 1, self(30, 15), LANE, BASE, units, structures,
                camps, 10);
        assertEquals(Action.PUSH, brain.decide(late).action());
        assertNull(brain.camp());
    }

    @Test
    void itWalksWithItsWaveInsteadOfGoingToTheJungle() {
        camps.add(new Camp("blue_jelly", new Vec(30, 20), false, 0, 0, false));
        unit(Kind.MINION, 33, 0, 20, false);
        assertEquals(Action.PUSH, decide(SHARP, view(self(30, 20))).action());
    }

    @Test
    void hurtAtTheBaseItWaitsToHeal() {
        Self self = new Self(ME, BASE, 10, 20, 4, 3, Role.MELEE, true, false);
        assertEquals(Action.WAIT, decide(SHARP, view(self)).action());
        Self healed = new Self(ME, BASE, 20, 20, 4, 3, Role.MELEE, true, false);
        assertEquals(Action.PUSH, decide(PLAIN, view(healed)).action());
    }

    @Test
    void anEnemyTowerIsHitOnlyWhileOwnMinionsTankIt() {
        tower("enemy_outer", 60, true);
        Plan alone = decide(SHARP, view(self(52, 20)));
        assertNotEquals(Action.SIEGE, alone.action(), "no minions: the tower would shoot the bot");
        assertTrue(alone.goal().distance(new Vec(60, 0)) > 10 + SiegeBotBrain.MARGIN, "it keeps out of range");
        unit(Kind.MINION, 57, 1, 20, false);
        Plan tanked = decide(SHARP, view(self(52, 20)));
        assertEquals(Action.SIEGE, tanked.action());
        assertEquals("enemy_outer", tanked.structure());
    }

    @Test
    void aShieldedStructureIsNeverHit() {
        structures.add(new Structure("enemy_inner", new Vec(60, 0), true, true, false, false));
        unit(Kind.MINION, 57, 1, 20, false);
        assertNotEquals(Action.SIEGE, decide(SHARP, view(self(52, 20))).action());
    }

    @Test
    void aCoreDoesNotShootSoItIsHitWithoutMinions() {
        structures.add(new Structure("enemy_core", new Vec(95, 0), true, false, true, false));
        Plan plan = decide(SHARP, view(self(88, 20)));
        assertEquals(Action.SIEGE, plan.action());
        assertEquals("enemy_core", plan.structure());
    }

    @Test
    void itStandsBehindItsMinionsAndWaitsAtItsTowerWithoutThem() {
        tower("own_outer", 20, false);
        Plan waiting = decide(PLAIN, view(self(10, 20)));
        assertEquals(Action.PUSH, waiting.action());
        assertEquals(26, LANE.progress(waiting.goal()), 1.0E-6, "in front of its own outer tower");
        unit(Kind.MINION, 45, 0, 20, false);
        Plan pushing = decide(PLAIN, view(self(30, 20)));
        assertEquals(Action.PUSH, pushing.action());
        assertTrue(LANE.progress(pushing.goal()) < 45 && LANE.progress(pushing.goal()) > 40, "behind the front");
    }

    @Test
    void itFocusesTheEnemyWithTheLeastHealth() {
        Unit near = unit(Kind.CHAMPION, 42, 0, 18, true);
        Unit hurt = unit(Kind.CHAMPION, 47, 0, 5, true);
        unit(Kind.CHAMPION, 39, 0, 20, false);
        assertEquals(hurt.id(), decide(SHARP, view(self(40, 20))).target());
        assertEquals(near.id(), decide(PLAIN, view(self(40, 20))).target(), "an easy bot takes the nearest");
    }

    @Test
    void outnumberedItBacksOff() {
        unit(Kind.CHAMPION, 44, 0, 20, true);
        unit(Kind.CHAMPION, 45, 1, 20, true);
        unit(Kind.CHAMPION, 45, -1, 20, true);
        Plan plan = decide(PLAIN, view(self(40, 16)));
        assertEquals(Action.RETREAT, plan.action());
    }

    @Test
    void aNearlyDeadEnemyIsWorthChasing() {
        unit(Kind.CHAMPION, 44, 0, 20, true);
        Unit dying = unit(Kind.CHAMPION, 45, 1, 2, true);
        unit(Kind.CHAMPION, 45, -1, 20, true);
        Plan plan = decide(SHARP, view(self(40, 18)));
        assertEquals(Action.FIGHT, plan.action());
        assertEquals(dying.id(), plan.target());
    }

    @Test
    void itNeverChasesIntoAnUntankedTower() {
        tower("enemy_outer", 60, true);
        unit(Kind.CHAMPION, 55, 0, 3, true);
        Plan plan = decide(SHARP, view(self(46, 20)));
        assertNotEquals(Action.FIGHT, plan.action());
    }

    @Test
    void itTakesTheLastHit() {
        unit(Kind.MINION, 42, 0, 15, true);
        Unit low = unit(Kind.MINION, 44, 0, 3, true);
        Plan plan = decide(SHARP, view(self(40, 20)));
        assertEquals(Action.FARM, plan.action());
        assertEquals(low.id(), plan.target(), "the minion its next hit (4) kills");
    }

    @Test
    void itDefendsItsTowerUnderAttack() {
        structures.add(new Structure("own_inner", new Vec(10, 0), false, false, false, true));
        Plan plan = decide(PLAIN, view(self(45, 20)));
        assertEquals(Action.DEFEND, plan.action());
        assertEquals("own_inner", plan.structure());
    }

    @Test
    void aQuietLaneSendsItToTheJungleAndTheDragonNeedsAllies() {
        camps.add(new Camp("dragon", new Vec(40, 30), true, 0, 0, false));
        camps.add(new Camp("blue_jelly", new Vec(30, 20), false, 0, 0, false));
        Plan plan = decide(SHARP, view(self(30, 20)));
        assertEquals(Action.JUNGLE, plan.action());
        assertEquals("blue_jelly", plan.camp(), "alone it takes a small camp, not the dragon");
        camps.set(0, new Camp("dragon", new Vec(40, 30), true, 2, 0, false));
        assertEquals("dragon", decide(SHARP, view(self(30, 20))).camp(), "with allies the dragon");
        assertNull(decide(PLAIN, view(self(30, 20))).camp(), "a bot that never goes stays on its lane");
    }

    @Test
    void aCampItWentForIsKeptUntilItIsTaken() {
        SiegeBotBrain brain = new SiegeBotBrain(SHARP, new Random(3));
        camps.add(new Camp("blue_jelly", new Vec(30, 20), false, 0, 0, false));
        assertEquals(Action.JUNGLE, brain.decide(view(self(30, 20))).action());
        View later = new View(1010, self(30, 15), LANE, BASE, units, structures, camps, 10);
        assertEquals("blue_jelly", brain.decide(later).camp());
        camps.clear();
        View cleared = new View(1020, self(30, 15), LANE, BASE, units, structures, camps, 10);
        assertEquals(Action.PUSH, brain.decide(cleared).action(), "the camp is gone: back to the lane");
    }
}
