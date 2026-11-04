package io.github.sx2xoox.mcpl.game.siege.tower;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** The solid shape of the 공성전 buildings and the pure geometry that keeps bodies out of it. */
class FootprintTest {

    @Test
    void theLayersBecomeTheBlocksAroundABlockCentredBase() {
        Footprint tower = ModelTowerLook.SPECS.get(ModelTowerLook.TOWER).footprint();
        List<int[]> blocks = Footprint.blocks(tower.layers(false), 10.5, 64, -3.5);
        assertEquals(5 * 5 * 11 + 3 * 3 * 2, blocks.size(), "5 × 5 up to the roof, then a 3 × 3 step");
        for (int[] b : blocks) {
            assertTrue(b[0] >= 8 && b[0] <= 12 && b[2] >= -6 && b[2] <= -2 && b[1] >= 64 && b[1] < 77);
        }
        assertEquals(2.5, tower.halfWidth(false), 1e-9);
        assertEquals(5 * 5 * 2, Footprint.blocks(tower.layers(true), 0.5, 64, 0.5).size(), "the ruin's stub");
        Footprint core = ModelTowerLook.SPECS.get(ModelTowerLook.CORE).footprint();
        assertEquals(7.5, core.halfWidth(false), 1e-9, "the music box ±7, its lid ±7.5");
        assertEquals(7.5, core.within(10).halfWidth(false), 1e-9, "the 장난감 왕국 keeps 10 clear");
        assertEquals(4.5, core.within(5).halfWidth(false), 1e-9, "a small map's core: cut to its room");
        assertEquals(3, core.within(5).standing().size(), "the box, keep and spire: the turrets do not fit");
        // the tower's hitbox eyes (Mannequin 1.62 × scale) stay out of the walls: a hitbox in a block suffocates
        assertTrue(tower.height(false) <= 1.62 * 8.75);
    }

    @Test
    void aBodyWalkingAtABuildingGoesRoundIt() {
        Footprint.Box box = new Footprint.Box(-2.5, -2.5, 2.5, 2.5).grow(0.35);
        double[][] goals = {{10, 0}, {10, 0.4}, {10, -3}, {0, 10}, {-10, 0}, {6, 6}};
        double[][] starts = {{-10, 0}, {-10, -0.4}, {-10, 2}, {0, -10}, {10, 0.01}, {-6, -6}};
        for (int n = 0; n < goals.length; n++) {
            double x = starts[n][0];
            double z = starts[n][1];
            int steps = 0;
            while ((Math.abs(x - goals[n][0]) > 1.0E-6 || Math.abs(z - goals[n][1]) > 1.0E-6) && steps < 400) {
                double[] next = box.step(x, z, goals[n][0], goals[n][1], 0.25);
                assertFalse(box.contains(next[0], next[1]), "walked into the building at " + next[0] + ", "
                        + next[1]);
                assertFalse(box.crosses(x, z, next[0], next[1]), "through a corner");
                x = next[0];
                z = next[1];
                steps++;
            }
            assertTrue(steps < 400, "reached " + goals[n][0] + ", " + goals[n][1] + " from the start " + n);
        }
    }

    @Test
    void goalsInsideAreMovedOutToTheNearestSide() {
        Footprint.Box box = new Footprint.Box(-2.5, -2.5, 2.5, 2.5);
        double[] out = Footprint.clear(List.of(box), 2.0, 0.3);
        assertFalse(box.contains(out[0], out[1]));
        assertEquals(2.55, out[0], 1e-9);
        assertEquals(0.3, out[1], 1e-9);
        double[] clear = Footprint.clear(List.of(box), 5, 5);
        assertEquals(5, clear[0], 1e-9);
        assertEquals(0, box.distance(2.5, 1), 1e-9);
        assertEquals(1.5, box.distance(4, 0), 1e-9);
    }

    @Test
    void aPathThroughBuildingsGoesRoundThemTheShortWayAndOnlyThere() {
        Footprint.Box a = new Footprint.Box(-3, -3, 3, 3);
        Footprint.Box b = new Footprint.Box(17, 1, 23, 7);
        // a lane straight through the centre of a, then through the edge of b, then on
        List<double[]> path = List.of(new double[] {-20, 0}, new double[] {-10, 0}, new double[] {0, 0},
                new double[] {10, 0}, new double[] {20, 2}, new double[] {40, 4});
        List<double[]> round = Footprint.detour(path, List.of(a, b));
        assertEquals(-20, round.getFirst()[0], 1e-9);
        assertEquals(40, round.getLast()[0], 1e-9);
        double length = 0;
        for (int i = 1; i < round.size(); i++) {
            double[] p = round.get(i - 1);
            double[] q = round.get(i);
            assertFalse(a.crosses(p[0], p[1], q[0], q[1]), "segment " + i + " through a");
            assertFalse(b.crosses(p[0], p[1], q[0], q[1]), "segment " + i + " through b");
            assertFalse(a.contains(q[0], q[1]) || b.contains(q[0], q[1]));
            length += Math.hypot(q[0] - p[0], q[1] - p[1]);
        }
        // pulled taut: round a's two corners on one side, past b's corner that is in the way
        assertTrue(length < 60.5 + 2 * 3.2 + 2, "no long way round: " + length);
        assertEquals(7, round.size(), "-20, -10, a's two corners, 10, b's corner, 40");
        double lowest = 0;
        for (double[] p : round) {
            lowest = Math.min(lowest, p[1]);
        }
        assertEquals(-3, lowest, 1e-9, "a's side at z -3 (one way for both walking directions)");
        assertTrue(round.stream().anyMatch(p -> p[0] == 23 && p[1] == 1), "b's corner");
        // a path clear of the boxes stays as it is; one ending inside a box ends at its edge
        assertEquals(2, Footprint.detour(List.of(new double[] {-20, 10}, new double[] {20, 10}), List.of(a)).size());
        List<double[]> into = Footprint.detour(List.of(new double[] {-20, 0}, new double[] {0, 0}), List.of(a));
        assertEquals(-3, into.getLast()[0], 1e-9);
        assertEquals(0, into.getLast()[1], 1e-9);
    }
}
