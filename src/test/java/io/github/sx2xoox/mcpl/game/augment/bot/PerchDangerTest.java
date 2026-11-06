package io.github.sx2xoox.mcpl.game.augment.bot;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import io.github.sx2xoox.mcpl.game.augment.bot.Perch.Response;
import org.junit.jupiter.api.Test;

/**
 * The preview83 complaints: a player who pillars up and shoots down, and a player who pours lava. What a bot does about
 * an enemy up on a pillar ({@link Perch}) and how it reads and leaves dangerous ground ({@link Danger}), on synthetic
 * block grids.
 */
class PerchDangerTest {

    /**
     * Cells: 's' solid, 'b' bedrock, 'w' water, 'l' lava, 'm' magma (a hazard), 'f' fire, 'c' cactus, 'r' berry bush;
     * else air. The
     * ground is a stone floor at y = 63 over x, z in [-20, 20] (feet at y = 64).
     */
    private static final class Grid implements Recovery.Grid, Danger.Fires {
        private final Map<String, Character> cells = new HashMap<>();

        Grid set(int x, int y, int z, char c) {
            cells.put(x + "," + y + "," + z, c);
            return this;
        }

        Grid floor() {
            for (int x = -20; x <= 20; x++) {
                for (int z = -20; z <= 20; z++) {
                    set(x, 63, z, 's');
                }
            }
            return this;
        }

        /** A player's pillar: {@code height} blocks of dirt on the floor at (x, z); its top is at 63 + height. */
        Grid pillar(int x, int z, int height) {
            for (int y = 64; y < 64 + height; y++) {
                set(x, y, z, 's');
            }
            return this;
        }

        char at(int x, int y, int z) {
            return cells.getOrDefault(x + "," + y + "," + z, ' ');
        }

        @Override
        public boolean solid(int x, int y, int z) {
            char c = at(x, y, z);
            return c == 's' || c == 'b' || c == 'c';
        }

        @Override
        public boolean breakable(int x, int y, int z) {
            return at(x, y, z) != 'b';
        }

        @Override
        public boolean liquid(int x, int y, int z) {
            return at(x, y, z) == 'w' || at(x, y, z) == 'l';
        }

        @Override
        public boolean lava(int x, int y, int z) {
            return at(x, y, z) == 'l';
        }

        @Override
        public boolean hazard(int x, int y, int z) {
            char c = at(x, y, z);
            return c == 'm' || c == 'f' || c == 'c' || c == 'r';
        }

        @Override
        public boolean fire(int x, int y, int z) {
            return at(x, y, z) == 'f';
        }

        @Override
        public boolean prickly(int x, int y, int z) {
            return at(x, y, z) == 'r';
        }

        @Override
        public boolean hot(int x, int y, int z) {
            return at(x, y, z) == 'm';
        }
    }

    // ------------------------------------------------------------------ perched enemies

    @Test
    void aPlayersPillarIsAColumnButTheGroundIsNot() {
        Grid g = new Grid().floor().pillar(5, 0, 6);
        assertEquals(6, Perch.column(g, 5, 70, 0), "six blocks of dirt under their feet");
        assertEquals(0, Perch.column(g, 0, 64, 0), "the floor has neighbours: no column");
        Grid wall = new Grid().floor().pillar(5, 0, 6).pillar(6, 0, 6);
        assertEquals(0, Perch.column(wall, 5, 70, 0), "a two-wide wall is not a pillar");
    }

    @Test
    void aLowPillarIsChoppedFromTheGround() {
        Grid g = new Grid().floor().pillar(5, 0, 4); // feet at 68
        Perch.Plan plan = Perch.plan(g, 0, 64, 0, 5, 68, 0, 30, 64);
        assertEquals(Response.CHOP, plan.response());
        assertArrayEquals(new int[] {5, 67, 0}, plan.chop(), "the block right under their feet");
        assertNotNull(plan.stand());
        assertEquals(64, plan.stand()[1], "from the ground");
        assertTrue(Math.max(Math.abs(plan.stand()[0] - 5), Math.abs(plan.stand()[2])) <= 2, "beside the column");
        assertTrue(plan.stand()[0] < 5, "on its own side of the column");
        assertTrue(Perch.reaches(plan.stand()[0], plan.stand()[1], plan.stand()[2], plan.chop()));
    }

    @Test
    void aTallPillarIsClimbedBesideUntilTheChopIsInReach() {
        Grid g = new Grid().floor().pillar(5, 0, 9); // feet at 73
        Perch.Plan plan = Perch.plan(g, 0, 64, 0, 5, 73, 0, 30, 64);
        assertEquals(Response.CLIMB, plan.response(), "blocks to climb with beat a bow duel");
        assertNotNull(plan.stand());
        assertEquals(64, plan.stand()[1]);
        assertEquals(1, Math.abs(plan.stand()[0] - 5) + Math.abs(plan.stand()[2]), "right beside the column");
        assertTrue(plan.climb() > 0 && plan.climb() <= 9, "climbs " + plan.climb());
        assertTrue(Perch.reaches(plan.stand()[0], plan.stand()[1] + plan.climb(), plan.stand()[2], plan.chop()));
        assertFalse(Perch.reaches(plan.stand()[0], plan.stand()[1] + plan.climb() - 1, plan.stand()[2], plan.chop()),
                "no higher than it needs");
    }

    @Test
    void withoutBlocksItShootsBackAndWithoutABowItTakesCover() {
        Grid g = new Grid().floor().pillar(5, 0, 9);
        assertEquals(Response.SHOOT, Perch.plan(g, 0, 64, 0, 5, 73, 0, 30, 0).response());
        assertEquals(Response.COVER, Perch.plan(g, 0, 64, 0, 5, 73, 0, 0, 0).response(),
                "nothing to answer with: out of their line of fire, not standing under them");
        assertEquals(Response.CHOP, Perch.plan(new Grid().floor().pillar(5, 0, 4), 0, 64, 0, 5, 68, 0, 0, 0)
                .response(), "a low pillar needs neither");
    }

    @Test
    void bedrockOrLavaUnderTheirFeetIsNotChopped() {
        Grid bedrock = new Grid().floor().pillar(5, 0, 4).set(5, 67, 0, 'b');
        assertEquals(Response.SHOOT, Perch.plan(bedrock, 0, 64, 0, 5, 68, 0, 30, 64).response());
        Grid lava = new Grid().floor().pillar(5, 0, 4).set(6, 67, 0, 'l');
        assertEquals(Response.SHOOT, Perch.plan(lava, 0, 64, 0, 5, 68, 0, 30, 64).response(),
                "breaking it would let lava pour down on itself");
    }

    @Test
    void anEnemyAStepUpOrOnALedgeIsNoPillar() {
        Grid g = new Grid().floor().pillar(5, 0, 1);
        assertEquals(Response.NONE, Perch.plan(g, 0, 64, 0, 5, 65, 0, 30, 64).response(), "one block: walk up");
        Grid ledge = new Grid().floor();
        for (int x = 4; x <= 8; x++) {
            for (int z = -3; z <= 3; z++) {
                for (int y = 64; y <= 68; y++) {
                    ledge.set(x, y, z, 's');
                }
            }
        }
        assertEquals(Response.SHOOT, Perch.plan(ledge, 0, 64, 0, 5, 69, 0, 30, 64).response(),
                "on a cliff it cannot path up to: shoot back");
        assertEquals(Response.NONE, Perch.plan(ledge, 0, 64, 0, 5, 69, 0, 0, 64).response(),
                "no bow: its usual way up (stairs, pillar) handles a cliff");
        assertTrue(Perch.perched(5, 0, false));
        assertFalse(Perch.perched(5, 0, true), "a hill it can walk up is no perch");
        assertFalse(Perch.perched(2, 6, false), "within a sword's reach");
    }

    @Test
    void itShootsFromNineBlocksOutOnItsOwnSide() {
        double[] spot = Perch.shootSpot(2, 0, 5, 0, 9);
        assertEquals(-4, spot[0], 1.0E-9);
        assertEquals(0, spot[1], 1.0E-9);
    }

    // ------------------------------------------------------------------ danger map

    @Test
    void lavaFireMagmaAndBushesAreReadForWhatTheyAre() {
        Grid g = new Grid().floor();
        assertEquals(Danger.Level.SAFE, Danger.level(g, 0, 64, 0));
        assertEquals(Danger.Level.BURN, Danger.level(new Grid().floor().set(0, 64, 0, 'l'), 0, 64, 0), "in lava");
        assertEquals(Danger.Level.BURN, Danger.level(new Grid().floor().set(0, 65, 0, 'l'), 0, 64, 0),
                "lava poured on its head");
        assertEquals(Danger.Level.BURN, Danger.level(new Grid().floor().set(0, 64, 0, 'f'), 0, 64, 0), "in fire");
        assertEquals(Danger.Level.HURT, Danger.level(new Grid().floor().set(0, 63, 0, 'm'), 0, 64, 0), "on magma");
        assertEquals(Danger.Level.HURT, Danger.level(new Grid().floor().set(0, 64, 0, 'r'), 0, 64, 0),
                "in a berry bush");
        assertEquals(Danger.Level.SAFE, Danger.level(new Grid().floor().set(1, 64, 0, 'c'), 0, 64, 0),
                "beside a cactus, not touching it (the pathfinder keeps it off)");
        assertEquals(Danger.Level.NEAR, Danger.level(new Grid().floor().set(2, 64, 0, 'l'), 0, 64, 0),
                "two blocks from lava: one knockback away");
        assertEquals(Danger.Level.SAFE, Danger.level(new Grid().floor().set(3, 64, 0, 'l'), 0, 64, 0));
        assertEquals(Danger.Level.SAFE, Danger.harm(new Grid().floor().set(2, 64, 0, 'l'), 0, 64, 0),
                "the every-tick check only asks whether it hurts now");
    }

    @Test
    void outOfPouredLavaItStepsToTheNearestSafeCellAwayFromIt() {
        // a source poured on its feet, flowing one block around
        Grid g = new Grid().floor();
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                g.set(x, 64, z, 'l');
            }
        }
        int[] out = Danger.escape(g, 0, 64, 0, 6, false);
        assertNotNull(out);
        assertEquals(64, out[1]);
        assertEquals(Danger.Level.SAFE, Danger.level(g, out[0], out[1], out[2]));
        assertTrue(Math.max(Math.abs(out[0]), Math.abs(out[2])) <= 4, "a few steps, not across the map: " + out[0]
                + "," + out[2]);
    }

    @Test
    void burningItRunsIntoWaterNearby() {
        Grid g = new Grid().floor().set(0, 64, 0, 'f').set(3, 64, 0, 'w').set(3, 63, 0, 's');
        int[] out = Danger.escape(g, 0, 64, 0, 5, true);
        assertArrayEquals(new int[] {3, 64, 0}, out, "the water puts the fire out");
        int[] dry = Danger.escape(new Grid().floor().set(0, 64, 0, 'f'), 0, 64, 0, 5, true);
        assertNotNull(dry, "no water: at least out of the fire");
        assertFalse(Danger.level(g, dry[0], dry[1], dry[2]) == Danger.Level.BURN);
    }

    @Test
    void theEscapeNeverDropsOffACliff() {
        // a narrow ledge at y = 64 (floor 63) with a 10-deep drop to the east, lava on it
        Grid g = new Grid();
        for (int x = -6; x <= 0; x++) {
            for (int z = -1; z <= 1; z++) {
                g.set(x, 63, z, 's');
            }
        }
        for (int x = 1; x <= 6; x++) {
            for (int z = -1; z <= 1; z++) {
                g.set(x, 53, z, 's');
            }
        }
        g.set(0, 64, 0, 'l');
        int[] out = Danger.escape(g, 0, 64, 0, 6, false);
        assertNotNull(out);
        assertTrue(out[0] < 0, "back along the ledge, not over the edge: " + out[0]);
        assertEquals(64, out[1]);
    }

    @Test
    void boxedInByLavaThereIsNoWayOut() {
        Grid g = new Grid().floor();
        for (int x = -6; x <= 6; x++) {
            for (int z = -6; z <= 6; z++) {
                g.set(x, 64, z, 'l');
            }
        }
        assertNull(Danger.escape(g, 0, 64, 0, 3, false));
    }
}
