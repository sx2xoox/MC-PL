package io.github.sx2xoox.speedrunbot.core;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Phase2LogicTest {

    /** A memory stand-in: cells default to UNKNOWN. */
    static final class FakeGrid implements Frontier.Grid {
        final Map<Long, Frontier.Seen> cells = new HashMap<>();

        void set(int x, int y, int z, Frontier.Seen s) {
            cells.put(Cell.pack(x, y, z), s);
        }

        void box(int x0, int y0, int z0, int x1, int y1, int z1, Frontier.Seen s) {
            for (int x = x0; x <= x1; x++) {
                for (int y = y0; y <= y1; y++) {
                    for (int z = z0; z <= z1; z++) {
                        set(x, y, z, s);
                    }
                }
            }
        }

        @Override
        public Frontier.Seen at(int x, int y, int z) {
            return cells.getOrDefault(Cell.pack(x, y, z), Frontier.Seen.UNKNOWN);
        }
    }

    // ---------------------------------------------------------------- frontier exploration

    @Test
    void frontierPicksTheOpenEndOfACaveTunnel() {
        FakeGrid g = new FakeGrid();
        // a seen cave tunnel along +x at y=30 (feet), rock around it, its far end not seen yet
        g.box(-2, 28, -2, 20, 34, 2, Frontier.Seen.SOLID);
        g.box(0, 30, 0, 20, 31, 0, Frontier.Seen.AIR);
        g.box(16, 29, -2, 22, 34, 2, Frontier.Seen.UNKNOWN); // beyond x=15 nothing is known
        g.box(16, 30, 0, 22, 31, 0, Frontier.Seen.UNKNOWN);
        g.box(12, 30, 0, 15, 31, 0, Frontier.Seen.AIR);
        g.box(12, 29, 0, 15, 29, 0, Frontier.Seen.SOLID);
        Frontier f = new Frontier(g, new Frontier.Weights());
        Cell best = f.best(0, 30, 0, 30, null);
        assertNotNull(best);
        assertEquals(30, best.y);
        assertTrue(best.x >= 12, "the frontier is at the tunnel's unseen end, got " + best.x);
    }

    @Test
    void openSkyAndLavaAreNoFrontier() {
        FakeGrid g = new FakeGrid();
        g.box(-3, 60, -3, 3, 60, 3, Frontier.Seen.SOLID); // a floor
        g.box(-3, 61, -3, 3, 75, 3, Frontier.Seen.AIR);   // open sky above it
        Frontier f = new Frontier(g, new Frontier.Weights());
        assertNull(f.best(0, 61, 0, 61, null), "surface ground under open sky is not a cave frontier");

        FakeGrid h = new FakeGrid();
        h.box(-5, 20, -5, 5, 26, 5, Frontier.Seen.SOLID);
        h.box(-4, 21, 0, 4, 22, 0, Frontier.Seen.AIR);
        h.set(4, 21, 1, Frontier.Seen.LAVA);
        Frontier fh = new Frontier(h, new Frontier.Weights());
        assertFalse(fh.standable(4, 21, 0), "a cell touching lava is never a place to stand");
        assertTrue(fh.standable(0, 21, 0));
    }

    @Test
    void visitedCellsAreSkipped() {
        FakeGrid g = new FakeGrid();
        g.box(-2, 28, -2, 12, 34, 2, Frontier.Seen.SOLID);
        g.box(0, 30, 0, 8, 31, 0, Frontier.Seen.AIR);
        g.box(9, 29, -2, 12, 34, 2, Frontier.Seen.UNKNOWN);
        Frontier f = new Frontier(g, new Frontier.Weights());
        Cell first = f.best(0, 30, 0, 30, null);
        assertNotNull(first);
        Cell second = f.best(0, 30, 0, 30, p -> p == first.pack());
        assertTrue(second == null || !second.equals(first));
    }

    // ---------------------------------------------------------------- portal casting

    @Test
    void everyFrameCellHasAFaceToPourWaterAgainst() {
        for (Cell n : new Cell[]{new Cell(1, 0, 0), new Cell(0, 0, 1), new Cell(-1, 0, 0), new Cell(0, 0, -1)}) {
            Cell u = new Cell(-n.z, 0, n.x);
            PortalPlan p = new PortalPlan(new Cell(10, 20, 30), u, n);
            List<Cell> room = p.room();
            for (Cell c : p.frame()) {
                List<Cell> sup = p.frontSupports(c);
                assertFalse(sup.isEmpty(), "frame cell " + c + " has no support face for the water");
                for (Cell s : sup) {
                    assertFalse(room.contains(s));
                    assertTrue(p.solidBefore().contains(s) || p.frame().contains(s), "support " + s + " must be checked as rock");
                }
            }
            // the third-row fronts are visible from the standing row: their k=2 cells are part of the room
            assertTrue(room.contains(p.at(0, 3, 2)) && room.contains(p.at(3, 3, 2)));
        }
    }
}
