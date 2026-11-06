package io.github.sx2xoox.mcpl.game.augment.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.github.sx2xoox.mcpl.game.augment.bot.BotMotion.Gait;
import org.junit.jupiter.api.Test;

/** Bot speeds against a player's, the border's top speed under a time scale, and getting out of a block. */
class BotMotionTest {

    @Test
    void theLegsWalkAndSprintLikeAPlayer() {
        double walk = BotMotion.pathSpeed(BotMotion.WALK_BPS, BotMotion.REFERENCE_ATTRIBUTE);
        double sprint = BotMotion.pathSpeed(BotMotion.SPRINT_BPS, BotMotion.REFERENCE_ATTRIBUTE);
        assertEquals(4.317, BotMotion.groundSpeed(BotMotion.REFERENCE_ATTRIBUTE, walk), 1.0E-6);
        assertEquals(5.612, BotMotion.groundSpeed(BotMotion.REFERENCE_ATTRIBUTE, sprint), 1.0E-6);
        // measured on Paper 26.3: legs at 0.32 with modifier 1.0 cross flat ground at about 4.1-4.4 blocks a second
        assertEquals(4.4, BotMotion.groundSpeed(0.32, 1.0), 0.1);
        assertTrue(sprint > walk);
    }

    @Test
    void itSprintsWhenTravellingAndWalksWhenClose() {
        assertTrue(BotMotion.sprints(Gait.SPRINT, 30));
        assertFalse(BotMotion.sprints(Gait.SPRINT, 3), "lining up a hit");
        assertTrue(BotMotion.sprints(Gait.RUSH, 1), "out of the border every block counts");
        assertFalse(BotMotion.sprints(Gait.WALK, 50));
        assertEquals(BotMotion.SPRINT_BPS, BotMotion.blocksPerSecond(Gait.SPRINT, 30, 1), 1.0E-9);
        assertEquals(BotMotion.WALK_BPS, BotMotion.blocksPerSecond(Gait.SPRINT, 3, 1), 1.0E-9);
        assertEquals(BotMotion.SPRINT_BPS * 1.2, BotMotion.blocksPerSecond(Gait.RUSH, 3, 1.2), 1.0E-9,
                "speed augments count like a player's");
    }

    @Test
    void aFastClockNeverLetsTheBorderOutrunABot() {
        // match 1 of the report: radius 400, spectate shrink 360 s, x4: the edge moved 4.3 blocks a second
        long shrink = 360 * 20;
        assertTrue(BotMotion.borderEdgeSpeed(4, 800, 24, shrink) > BotMotion.SPRINT_BPS * 0.7, "was unwinnable");
        double rate = BotMotion.shrinkClockRate(4, 800, 24, shrink);
        assertTrue(rate < 4 && rate >= 1, "capped: " + rate);
        assertEquals(BotMotion.maxBorderEdgeSpeed(), BotMotion.borderEdgeSpeed(rate, 800, 24, shrink), 1.0E-9);
        assertTrue(BotMotion.maxBorderEdgeSpeed() <= 0.7 * BotMotion.SPRINT_BPS + 1.0E-9);
        // a small world stays at full speed (the edge is slow anyway)
        assertEquals(4, BotMotion.shrinkClockRate(4, 300, 24, shrink), 1.0E-9);
        // never slower than the configured speed itself
        assertEquals(1, BotMotion.shrinkClockRate(4, 2000, 24, 20 * 20), 1.0E-9);
        assertEquals(1, BotMotion.shrinkClockRate(1, 800, 24, shrink), 1.0E-9);
    }

    @Test
    void theNearestFreeSpotPrefersTheSameHeightThenUp() {
        Set<List<Integer>> solid = new HashSet<>();
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                solid.add(List.of(x, -1, z)); // the ground
            }
        }
        solid.add(List.of(0, 1, 0)); // a block on its head
        BotMotion.Space space = space(solid);
        int[] spot = BotMotion.freeSpot(space, 0, 0, 0, 2);
        assertTrue(spot != null && spot[1] == 0 && Math.abs(spot[0]) + Math.abs(spot[2]) == 1, "a step aside");
        // walled in on every side at both heights: climbs on top
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                if (x != 0 || z != 0) {
                    solid.add(List.of(x, 0, z));
                    solid.add(List.of(x, 1, z));
                }
            }
        }
        spot = BotMotion.freeSpot(space(solid), 0, 0, 0, 2);
        assertTrue(spot != null && spot[1] == 2 && Math.abs(spot[0]) <= 1 && Math.abs(spot[2]) <= 1, "on top");
        assertNull(BotMotion.freeSpot(new BotMotion.Space() {
            @Override
            public boolean free(int x, int y, int z) {
                return false;
            }

            @Override
            public boolean floor(int x, int y, int z) {
                return true;
            }
        }, 0, 0, 0, 2), "nowhere to go");
    }

    private static BotMotion.Space space(Set<List<Integer>> solid) {
        return new BotMotion.Space() {
            @Override
            public boolean free(int x, int y, int z) {
                return !solid.contains(List.of(x, y, z)) && !solid.contains(List.of(x, y + 1, z));
            }

            @Override
            public boolean floor(int x, int y, int z) {
                return solid.contains(List.of(x, y, z));
            }
        };
    }
}
