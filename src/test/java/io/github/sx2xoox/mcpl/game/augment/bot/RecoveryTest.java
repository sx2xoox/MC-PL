package io.github.sx2xoox.mcpl.game.augment.bot;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import io.github.sx2xoox.mcpl.game.augment.bot.Recovery.Kind;
import io.github.sx2xoox.mcpl.game.augment.bot.Recovery.Step;
import org.junit.jupiter.api.Test;

/** How a stuck bot makes its own way, on a synthetic block grid. */
class RecoveryTest {

    /** A grid of named cells: 's' solid, 'b' bedrock, 'w' water, 'l' lava, 'p' powder snow, 'm' magma; else air. */
    private static final class Grid implements Recovery.Grid {
        private final Map<String, Character> cells = new HashMap<>();

        Grid set(int x, int y, int z, char c) {
            cells.put(x + "," + y + "," + z, c);
            return this;
        }

        /** A floor of stone at y = 63 over x, z in [-10, 10]. */
        Grid floor() {
            for (int x = -10; x <= 10; x++) {
                for (int z = -10; z <= 10; z++) {
                    set(x, 63, z, 's');
                }
            }
            return this;
        }

        char at(int x, int y, int z) {
            return cells.getOrDefault(x + "," + y + "," + z, ' ');
        }

        @Override
        public boolean solid(int x, int y, int z) {
            char c = at(x, y, z);
            return c == 's' || c == 'b';
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
            return at(x, y, z) == 'p' || at(x, y, z) == 'm';
        }
    }

    @Test
    void anOpenWayIsWalked() {
        Step step = Recovery.next(new Grid().floor(), 0, 64, 0, 1, 0, 0, false, true);
        assertEquals(Kind.WALK, step.kind());
        assertArrayEquals(new int[] {1, 64, 0}, step.to());
    }

    @Test
    void aWallInTheWayIsTunnelledOneByTwo() {
        Grid g = new Grid().floor().set(1, 64, 0, 's').set(1, 65, 0, 's');
        Step step = Recovery.next(g, 0, 64, 0, 1, 0, 0, false, true);
        assertEquals(Kind.TUNNEL, step.kind());
        assertEquals(2, step.breaks().size());
        assertArrayEquals(new int[] {1, 64, 0}, step.to());
    }

    @Test
    void bedrockIsNeverBrokenAndLavaNeverLetIn() {
        Grid bedrock = new Grid().floor().set(1, 64, 0, 'b').set(1, 65, 0, 'b');
        assertEquals(Kind.NONE, Recovery.next(bedrock, 0, 64, 0, 1, 0, 0, false, true).kind());
        Grid lava = new Grid().floor().set(1, 64, 0, 's').set(1, 65, 0, 's').set(2, 64, 0, 'l');
        assertEquals(Kind.NONE, Recovery.next(lava, 0, 64, 0, 1, 0, 0, false, true).kind(),
                "breaking that block would let the lava in");
    }

    @Test
    void outOfACaveItDigsAStaircaseUp() {
        // in a cave: rock all around above the head
        Grid g = new Grid().floor();
        for (int x = -3; x <= 3; x++) {
            for (int y = 64; y <= 70; y++) {
                if (!(x == 0 && (y == 64 || y == 65))) {
                    g.set(x, y, 0, 's');
                }
            }
        }
        Step step = Recovery.next(g, 0, 64, 0, 1, 0, 0, true, true);
        assertEquals(Kind.STAIR_UP, step.kind(), "underground it climbs towards the surface");
        assertArrayEquals(new int[] {1, 65, 0}, step.to());
        assertTrue(step.breaks().stream().anyMatch(c -> c[0] == 0 && c[1] == 66), "headroom for the jump");
        assertTrue(step.breaks().stream().anyMatch(c -> c[0] == 1 && c[1] == 65));
        assertTrue(step.breaks().stream().anyMatch(c -> c[0] == 1 && c[1] == 66));
        assertTrue(step.breaks().stream().noneMatch(c -> c[0] == 1 && c[1] == 64), "the step itself stays");
    }

    @Test
    void anOpenCliffIsPillaredAndAOneBlockStepWalked() {
        Grid cliff = new Grid().floor();
        for (int y = 64; y <= 70; y++) {
            cliff.set(1, y, 0, 's');
        }
        Step step = Recovery.next(cliff, 0, 64, 0, 1, 0, 6, false, true);
        assertEquals(Kind.PILLAR, step.kind(), "under the open sky it pillars up next to the wall");
        assertArrayEquals(new int[] {0, 64, 0}, step.places().getFirst(), "the block goes under its feet");
        assertEquals(Kind.STAIR_UP, Recovery.next(cliff, 0, 64, 0, 1, 0, 6, false, false).kind(),
                "without blocks it digs a staircase instead");
        Grid ledge = new Grid().floor().set(1, 64, 0, 's');
        Step up = Recovery.next(ledge, 0, 64, 0, 1, 0, 4, false, true);
        assertEquals(Kind.WALK, up.kind());
        assertArrayEquals(new int[] {1, 65, 0}, up.to());
    }

    @Test
    void gapsWaterAndPowderSnowAreBridged() {
        Grid gap = new Grid().floor();
        for (int y = 50; y <= 63; y++) {
            gap.set(1, y, 0, ' ');
        }
        Step bridge = Recovery.next(gap, 0, 64, 0, 1, 0, 0, false, true);
        assertEquals(Kind.BRIDGE, bridge.kind(), "a deep gap is bridged, not jumped into");
        assertArrayEquals(new int[] {1, 63, 0}, bridge.places().getFirst());
        assertEquals(Kind.NONE, Recovery.next(gap, 0, 64, 0, 1, 0, 0, false, false).kind(), "no blocks: no bridge");
        Grid water = new Grid().floor().set(1, 63, 0, 'w');
        assertEquals(Kind.BRIDGE, Recovery.next(water, 0, 64, 0, 1, 0, 0, false, true).kind());
        Grid snow = new Grid().floor().set(1, 63, 0, 'p');
        Step overSnow = Recovery.next(snow, 0, 64, 0, 1, 0, 0, false, true);
        assertEquals(Kind.BRIDGE, overSnow.kind(), "powder snow underfoot is replaced by a block");
        assertTrue(overSnow.breaks().stream().anyMatch(c -> c[1] == 63));
        Grid snowWall = new Grid().floor().set(1, 64, 0, 'p').set(1, 65, 0, 'p');
        Step through = Recovery.next(snowWall, 0, 64, 0, 1, 0, 0, false, true);
        assertEquals(Kind.TUNNEL, through.kind(), "powder snow in the way is cleared, never walked into");
        Grid shallow = new Grid().floor().set(1, 63, 0, ' ').set(1, 62, 0, 's');
        assertEquals(Kind.WALK, Recovery.next(shallow, 0, 64, 0, 1, 0, 0, false, true).kind(), "a small dip");
    }

    @Test
    void aLongWayDownIsAStaircase() {
        Grid g = new Grid().floor().set(1, 63, 0, ' ').set(1, 62, 0, 's');
        Step step = Recovery.next(g, 0, 64, 0, 1, 0, -6, false, true);
        assertEquals(Kind.STAIR_DOWN, step.kind());
        assertArrayEquals(new int[] {1, 63, 0}, step.to());
    }

    @Test
    void directionsTurnToTheSidesAndBack() {
        assertArrayEquals(new int[] {1, 0}, Recovery.direction(0, 0, 10, 3, 0));
        assertArrayEquals(new int[] {0, 1}, Recovery.direction(0, 0, 10, 3, 1));
        assertArrayEquals(new int[] {0, -1}, Recovery.direction(0, 0, 10, 3, 2));
        assertArrayEquals(new int[] {-1, 0}, Recovery.direction(0, 0, 10, 3, 3));
        assertArrayEquals(new int[] {0, -1}, Recovery.direction(0, 0, 1, -9, 0));
    }

    @Test
    void stuckMeansNoProgressNotJustAMovingTarget() {
        assertTrue(Recovery.stuck(0.3, 0, 0, 20), "standing still far from its goal");
        assertTrue(Recovery.stuck(6, 0.5, 0, 30), "running along a cliff it cannot climb, getting no closer");
        assertFalse(Recovery.stuck(6, 0.5, 8, 30), "chasing someone who runs as fast");
        assertFalse(Recovery.stuck(0, 0, 0, 2), "already there");
        assertFalse(Recovery.stuck(5, 4, 0, 20), "getting there");
        assertNotEquals(0, Recovery.WINDOW);
    }
}
