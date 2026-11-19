package io.github.sx2xoox.speedrunbot.core;

/**
 * Frontier exploration of caves from the bot's memory (no world access): a frontier is a known standable air cell
 * under a roof (a cave, not open sky) that borders unknown space. Walking to one and looking around reveals the cave
 * walls, where ore shows. The best frontier minimises distance plus a depth penalty (away from the wanted height)
 * and favours cells that border much unknown space.
 */
public final class Frontier {
    /** What the planner knows about a cell. */
    public enum Seen { UNKNOWN, AIR, SOLID, WATER, LAVA }

    /** Read-only view of the bot's memory. */
    public interface Grid {
        Seen at(int x, int y, int z);
    }

    /** Scoring weights (tunable through params). */
    public static final class Weights {
        public double perBlock = 1.0;
        public double perDepth = 0.6;
        public double perUnknown = 0.8;
        public int roofSearch = 12;
        public int radius = 32;
        public int minDistance = 3;
        /** Air cells needed around a frontier (a straight 1x2 tunnel has at most 10 in the box). */
        public int minAir = 14;
    }

    private final Grid g;
    private final Weights w;

    public Frontier(Grid g, Weights w) {
        this.g = g;
        this.w = w;
    }

    /** Feet cell is standable: air for feet and head, solid floor, no lava touching the body. */
    public boolean standable(int x, int y, int z) {
        if (g.at(x, y, z) != Seen.AIR || g.at(x, y + 1, z) != Seen.AIR || g.at(x, y - 1, z) != Seen.SOLID) {
            return false;
        }
        int[][] d = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] o : d) {
            for (int dy = -1; dy <= 1; dy++) {
                if (g.at(x + o[0], y + dy, z + o[1]) == Seen.LAVA) {
                    return false;
                }
            }
        }
        return g.at(x, y + 2, z) != Seen.LAVA;
    }

    /** Known solid somewhere above within roofSearch blocks: a cave or an overhang, not open sky. */
    public boolean roofed(int x, int y, int z) {
        for (int dy = 2; dy <= w.roofSearch; dy++) {
            Seen s = g.at(x, y + dy, z);
            if (s == Seen.SOLID) {
                return true;
            }
        }
        return false;
    }

    /** Air cells in the 5x3x5 box around the feet cell: a natural cave is wider than a dug 1x2 tunnel. */
    public int airAround(int x, int y, int z) {
        int n = 0;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = 0; dy <= 2; dy++) {
                    if (g.at(x + dx, y + dy, z + dz) == Seen.AIR) {
                        n++;
                    }
                }
            }
        }
        return n;
    }

    /** Unknown cells in the 5x3x5 box around the feet cell (how much there is still to see from here). */
    public int unknownAround(int x, int y, int z) {
        int n = 0;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = 0; dy <= 2; dy++) {
                    if (g.at(x + dx, y + dy, z + dz) == Seen.UNKNOWN) {
                        n++;
                    }
                }
            }
        }
        return n;
    }

    /** Score of a frontier candidate (lower is better), or +inf if it is not one. */
    public double score(int fx, int fy, int fz, int x, int y, int z, int targetY) {
        if (!standable(x, y, z) || !roofed(x, y, z)) {
            return Double.POSITIVE_INFINITY;
        }
        int unknown = unknownAround(x, y, z);
        if (unknown < 3 || airAround(x, y, z) < w.minAir) {
            return Double.POSITIVE_INFINITY;
        }
        double dx = x - fx, dy = y - fy, dz = z - fz;
        double d = Math.sqrt(dx * dx + dy * dy * 2 + dz * dz);
        if (d < w.minDistance) {
            return Double.POSITIVE_INFINITY;
        }
        return d * w.perBlock + Math.abs(y - targetY) * w.perDepth - Math.min(unknown, 30) * w.perUnknown;
    }

    /** Visited filter: cells the caller already explored (packed with {@link Cell#pack}). */
    public interface Visited {
        boolean contains(long packed);
    }

    /** Best frontier cell around the feet, or null. */
    public Cell best(int fx, int fy, int fz, int targetY, Visited visited) {
        return best(fx, fy, fz, targetY, Integer.MAX_VALUE, visited);
    }

    /** As {@link #best(int, int, int, int, Visited)}, only cells with feet at or below maxY (under the ground). */
    public Cell best(int fx, int fy, int fz, int targetY, int maxY, Visited visited) {
        Cell best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        int r = w.radius;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz > r * r) {
                    continue;
                }
                for (int dy = -12; dy <= 8; dy++) {
                    int x = fx + dx, y = fy + dy, z = fz + dz;
                    if (y < 6 || y > maxY) {
                        continue;
                    }
                    if (g.at(x, y, z) != Seen.AIR) {
                        continue;
                    }
                    if (visited != null && visited.contains(Cell.pack(x, y, z))) {
                        continue;
                    }
                    double s = score(fx, fy, fz, x, y, z, targetY);
                    if (s < bestScore) {
                        bestScore = s;
                        best = new Cell(x, y, z);
                    }
                }
            }
        }
        return best;
    }
}
