package io.github.sx2xoox.mcpl.game.siege.minion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** The pure formation math of a minion wave (docs/siege-v2.md §2, 대열). */
class FormationTest {

    private static final Formation.Settings S = Formation.Settings.DEFAULTS;
    private static final double E = 1.0E-9;

    private static Formation.Member member(int id, MinionKind kind, double progress, double lateral) {
        return new Formation.Member(id, kind, new Formation.Frame(progress, lateral));
    }

    /** 3 melee, 2 ranged and a cannon as they spawn. */
    private static List<Formation.Member> wave() {
        List<Formation.Member> out = new ArrayList<>();
        out.add(member(0, MinionKind.MELEE, 0, 0));
        out.add(member(1, MinionKind.MELEE, 0, 0));
        out.add(member(2, MinionKind.MELEE, 0, 0));
        out.add(member(3, MinionKind.RANGED, 0, 0));
        out.add(member(4, MinionKind.RANGED, 0, 0));
        out.add(member(5, MinionKind.CANNON, 0, 0));
        return out;
    }

    @Test
    void meleeInFrontRangedBehindCannonLastOnEvenFiles() {
        Map<Integer, Formation.Slot> slots = Formation.assign(wave(), S);
        for (int id = 0; id < 3; id++) {
            assertEquals(0, slots.get(id).row(), "melee in front");
        }
        assertEquals(-2, slots.get(0).lateral(), E);
        assertEquals(0, slots.get(1).lateral(), E);
        assertEquals(2, slots.get(2).lateral(), E);
        assertEquals(1, slots.get(3).row(), "ranged behind");
        assertEquals(1, slots.get(4).row());
        assertEquals(-1, slots.get(3).lateral(), E);
        assertEquals(1, slots.get(4).lateral(), E);
        assertEquals(2, slots.get(5).row(), "the cannon last");
        assertEquals(0, slots.get(5).lateral(), E);
    }

    @Test
    void extraMeleeMakeASecondMeleeRowAndFilesStayInTheLane() {
        List<Formation.Member> members = new ArrayList<>(wave());
        members.add(member(6, MinionKind.MELEE, -1, 0));
        members.add(member(7, MinionKind.MELEE, -1, 0));
        Map<Integer, Formation.Slot> slots = Formation.assign(members, S);
        assertEquals(1, slots.get(6).row());
        assertEquals(1, slots.get(7).row());
        assertEquals(2, slots.get(3).row(), "the ranged row moves back");
        assertEquals(3, slots.get(5).row());
        Formation.Settings narrow = new Formation.Settings(1.6, 3, 5, 2, 3, 1, 0.85, 1.25, 1.4, 1, 0.6, 1.5, false, 6, 0.5);
        List<Formation.Member> five = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            five.add(member(i, MinionKind.MELEE, 0, i));
        }
        for (Formation.Slot slot : Formation.assign(five, narrow).values()) {
            assertTrue(Math.abs(slot.lateral()) <= 2 + E, "files within the lane: " + slot);
        }
    }

    @Test
    void whenTheFrontFallsTheNextRowBecomesTheFront() {
        List<Formation.Member> members = new ArrayList<>(wave());
        members.removeIf(m -> m.kind() == MinionKind.MELEE);
        Map<Integer, Formation.Slot> slots = Formation.assign(members, S);
        assertEquals(0, slots.get(3).row());
        assertEquals(0, slots.get(4).row());
        assertEquals(1, slots.get(5).row());
        // ... and on the march it hurries to the front's place.
        List<Formation.Member> marching = List.of(member(3, MinionKind.RANGED, 10, -0.75),
                member(4, MinionKind.RANGED, 10, 0.75), member(5, MinionKind.CANNON, 8.4, 0));
        double front = Formation.front(marching, slots, S);
        assertEquals(10, front, 1.0E-6);
        assertEquals(1, Formation.pace(8.4, 1, front, S), E);
    }

    @Test
    void filesFollowWhereTheMembersStandSoNobodyCrosses() {
        List<Formation.Member> members = List.of(member(0, MinionKind.MELEE, 5, 1.0),
                member(1, MinionKind.MELEE, 5, -2.0), member(2, MinionKind.MELEE, 5, 0.2));
        Map<Integer, Formation.Slot> slots = Formation.assign(members, S);
        assertEquals(-2, slots.get(1).lateral(), E);
        assertEquals(0, slots.get(2).lateral(), E);
        assertEquals(2, slots.get(0).lateral(), E);
    }

    @Test
    void marchersAheadOfTheirPlaceSlowDownAndThoseBehindHurry() {
        List<Formation.Member> members = wave();
        Map<Integer, Formation.Slot> slots = Formation.assign(members, S);
        List<Formation.Member> marching = List.of(member(0, MinionKind.MELEE, 20, -1.5),
                member(1, MinionKind.MELEE, 20, 0), member(2, MinionKind.MELEE, 20, 1.5),
                member(3, MinionKind.RANGED, 18.4, -0.75), member(4, MinionKind.RANGED, 18.4, 0.75),
                member(5, MinionKind.CANNON, 16.8, 0));
        double front = Formation.front(marching, slots, S);
        assertEquals(20, front, 1.0E-6);
        assertEquals(1, Formation.pace(20, 0, front, S), E, "in place");
        assertEquals(S.slowFactor(), Formation.pace(23, 0, front, S), E, "ahead");
        assertEquals(S.catchUpFactor(), Formation.pace(14, 2, front, S), E, "the cannon lags");
        assertEquals(1, Formation.pace(0, 0, Double.NaN, S), E, "nobody marching");
    }

    @Test
    void walkingUpToAnEnemyBrakesBeforeTheReachTheFasterTheyCloseIn() {
        // two minions closing in by 0.6 a tick: the brake starts 6 ticks (3.6 blocks) short of the reach
        assertEquals(1, Formation.approach(5, 0.6, S), E, "far off: full speed");
        assertEquals(0.5, Formation.approach(1.8, 0.6, S), E, "half way into the brake");
        assertEquals(S.brakeFactor(), Formation.approach(0.2, 0.6, S), E, "at the edge: the slowest");
        assertEquals(S.brakeFactor(), Formation.approach(0, 0.6, S), E, "in reach");
        // alone (the enemy stands): half the closing speed, half the braking distance
        assertEquals(1, Formation.approach(1.8, 0.3, S), E);
        assertEquals(1, Formation.approach(3, 0, S), E, "nothing closes in");
    }

    @Test
    void meleeStandJustInFrontOfTheTargetOnTheirFileWithinReach() {
        Formation.Frame target = new Formation.Frame(30, 0.5);
        Formation.Frame stand = Formation.standPoint(target, 0, -1.5, 2.2, false, S);
        assertEquals(30 - S.meleeStand(), stand.progress(), E, "in front of it, from the own side");
        double reach = Math.hypot(target.progress() - stand.progress(), target.lateral() - stand.lateral());
        assertTrue(reach <= 2.2 * 0.9 + E, "in reach: " + reach);
        assertTrue(stand.lateral() < target.lateral(), "towards its own file: " + stand);
        // On its own file when the target faces it.
        assertEquals(1.5, Formation.standPoint(new Formation.Frame(30, 1.5), 0, 1.5, 2.2, false, S).lateral(), E);
        // A structure: its half width more.
        assertEquals(30 - S.meleeStand() - 1.5,
                Formation.standPoint(target, 1.5, 0.5, 2.2, false, S).progress(), E);
    }

    @Test
    void rangedStandBackAtTheEndOfTheirRange() {
        Formation.Frame stand = Formation.standPoint(new Formation.Frame(30, 0), 0, 0.75, 7, true, S);
        assertEquals(30 - (7 - S.rangedMargin()), stand.progress(), E);
        assertEquals(0.75, stand.lateral(), E, "on its own file");
    }

    @Test
    void membersShareOutTheEnemiesFacingThem() {
        // Three enemies in a row across the lane, 2 blocks ahead.
        List<Formation.Candidate> enemies = List.of(
                new Formation.Candidate(10, new Formation.Frame(12, -1.5), 2.5, 0),
                new Formation.Candidate(11, new Formation.Frame(12, 0), 2.0, 0),
                new Formation.Candidate(12, new Formation.Frame(12, 1.5), 2.5, 0));
        assertEquals(0, Formation.pick(-1.5, enemies, S), "the one facing the left file");
        assertEquals(2, Formation.pick(1.5, enemies, S), "the one facing the right file");
        assertEquals(1, Formation.pick(0, enemies, S));
        // The middle one is taken twice already: the middle file takes another.
        List<Formation.Candidate> crowded = List.of(enemies.get(0),
                new Formation.Candidate(11, new Formation.Frame(12, 0), 2.0, 2), enemies.get(2));
        assertTrue(Formation.pick(0, crowded, S) != 1, "not a third on one enemy");
        assertEquals(-1, Formation.pick(0, List.of(), S));
    }

    @Test
    void laneFrameReadsProgressAndLateralOnABentLane() {
        // East 10, then north (−z) 10.
        Formation.Lane lane = new Formation.Lane(List.of(new Formation.Vec(0, 0), new Formation.Vec(10, 0),
                new Formation.Vec(10, -10)));
        assertEquals(20, lane.length(), E);
        Formation.Frame f = lane.frame(new Formation.Vec(4, -1), Double.NaN, 0);
        assertEquals(4, f.progress(), E);
        assertEquals(-1, f.lateral(), E, "walking east, the right is +z");
        Formation.Vec back = lane.at(4, -1);
        assertEquals(4, back.x(), E);
        assertEquals(-1, back.z(), E);
        Formation.Frame g = lane.frame(new Formation.Vec(11, -6), Double.NaN, 0);
        assertEquals(16, g.progress(), E);
        assertEquals(1, g.lateral(), E, "walking north, the right is +x");
        // Behind the start: negative progress along the first segment.
        assertEquals(-3, lane.frame(new Formation.Vec(-3, 0.5), Double.NaN, 0).progress(), E);
        assertEquals(1, lane.nextWaypoint(0, 2));
        assertEquals(2, lane.nextWaypoint(8.5, 2));
        assertEquals(3, lane.nextWaypoint(19, 2));
    }
}
