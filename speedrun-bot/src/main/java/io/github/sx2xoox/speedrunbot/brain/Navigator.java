package io.github.sx2xoox.speedrunbot.brain;

import io.github.sx2xoox.speedrunbot.core.MiningTime;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;

/**
 * A* over the bot's {@link WorldMemory} (never the world). Unknown cells follow the free-space assumption used in
 * robot navigation: unknown body cells are passable and unknown floors hold, both with a small extra cost, and the
 * path is re-planned as perception fills in the truth. Moves: walk (8 directions), step up, drop down (up to 3),
 * staircase down, dig down, pillar up, swim; digging is allowed with its vanilla mining time as cost. Cost unit:
 * ticks.
 */
public final class Navigator {

    public enum Move { WALK, DIAG, UP, DOWN, DIG_DOWN, PILLAR, SWIM_UP }

    public static final class Step {
        public final BlockPos to;
        public final Move move;

        Step(BlockPos to, Move move) {
            this.to = to;
            this.move = move;
        }

        @Override
        public String toString() {
            return move + "->" + to.toString();
        }
    }

    public static final class Path {
        public final List<Step> steps;
        public final boolean complete;
        public final double cost;
        public final int expanded;

        Path(List<Step> steps, boolean complete, double cost, int expanded) {
            this.steps = steps;
            this.complete = complete;
            this.cost = cost;
            this.expanded = expanded;
        }
    }

    public interface Goal {
        boolean done(int x, int y, int z);

        double h(int x, int y, int z);

        /** Identity of the goal: two goals with the same key are the same target (the mover keeps its path). */
        default String key() {
            return toString();
        }
    }

    /** Feet at exactly this cell. */
    public static Goal at(BlockPos p) {
        return new Goal() {
            public String key() {
                return "at" + p.asLong();
            }

            public boolean done(int x, int y, int z) {
                return x == p.getX() && y == p.getY() && z == p.getZ();
            }

            public double h(int x, int y, int z) {
                return dist(x, y, z, p.getX(), p.getY(), p.getZ());
            }
        };
    }

    /** Eyes within reach of the block centre (and not standing in it). */
    public static Goal reach(BlockPos p, double reach) {
        return new Goal() {
            public String key() {
                return "reach" + p.asLong() + "/" + reach;
            }

            public boolean done(int x, int y, int z) {
                if ((x == p.getX() && z == p.getZ() && (y == p.getY() || y + 1 == p.getY()))) {
                    return false;
                }
                double dx = x + 0.5 - (p.getX() + 0.5), dy = y + 1.62 - (p.getY() + 0.5), dz = z + 0.5 - (p.getZ() + 0.5);
                return dx * dx + dy * dy + dz * dz <= reach * reach;
            }

            public double h(int x, int y, int z) {
                return Math.max(0, dist(x, y, z, p.getX(), p.getY(), p.getZ()) - reach);
            }
        };
    }

    /**
     * Standing at or above the block's level with the eyes within reach of its top face and nothing solid known in
     * between (to scoop a fluid source or look at a block's top).
     */
    public static Goal see(BlockPos p, double reach, WorldMemory mem) {
        return new Goal() {
            public String key() {
                return "see" + p.asLong() + "/" + reach;
            }

            public boolean done(int x, int y, int z) {
                if (y < p.getY() || y > p.getY() + 3 || (x == p.getX() && z == p.getZ())) {
                    return false;
                }
                double ex = x + 0.5, ey = y + 1.62, ez = z + 0.5;
                double tx = p.getX() + 0.5, ty = p.getY() + 0.6, tz = p.getZ() + 0.5;
                double dx = tx - ex, dy = ty - ey, dz = tz - ez;
                if (dx * dx + dy * dy + dz * dz > reach * reach) {
                    return false;
                }
                int steps = 16;
                for (int i = 1; i < steps; i++) {
                    double f = i / (double) steps;
                    int cx = (int) Math.floor(ex + dx * f), cy = (int) Math.floor(ey + dy * f), cz = (int) Math.floor(ez + dz * f);
                    if (cx == p.getX() && cy == p.getY() && cz == p.getZ()) {
                        break;
                    }
                    BlockState s = mem.get(cx, cy, cz);
                    if (s != null && Blocks2.pass(s) == Blocks2.Pass.SOLID) {
                        return false;
                    }
                }
                return true;
            }

            public double h(int x, int y, int z) {
                return Math.max(0, dist(x, y, z, p.getX(), p.getY(), p.getZ()) - reach);
            }
        };
    }

    /** Standing on dry ground (feet cell not water, floor solid and not water), within r blocks of p. */
    public static Goal dry(BlockPos p, double r, WorldMemory mem) {
        return new Goal() {
            public String key() {
                return "dry" + p.asLong();
            }

            public boolean done(int x, int y, int z) {
                double dx = x - p.getX(), dz = z - p.getZ();
                if (dx * dx + dz * dz > r * r) {
                    return false;
                }
                BlockState here = mem.get(x, y, z), floor = mem.get(x, y - 1, z);
                return here != null && floor != null && !Blocks2.isWater(here) && Blocks2.pass(here) == Blocks2.Pass.PASS
                        && Blocks2.standable(floor) && !Blocks2.isWater(floor);
            }

            public double h(int x, int y, int z) {
                return 0;
            }
        };
    }

    /** Feet at or above the given height (anywhere): back up to the surface. */
    public static Goal above(int y0) {
        return new Goal() {
            public String key() {
                return "above" + y0;
            }

            public boolean done(int x, int y, int z) {
                return y >= y0;
            }

            public double h(int x, int y, int z) {
                return Math.max(0, y0 - y);
            }
        };
    }

    /** Feet in the given block column at any height (digging down a map's X). */
    public static Goal column(int cx, int cz, int yHint) {
        return new Goal() {
            public String key() {
                return "col" + cx + "/" + cz;
            }

            public boolean done(int x, int y, int z) {
                return x == cx && z == cz;
            }

            public double h(int x, int y, int z) {
                double dx = x - cx, dz = z - cz;
                return Math.sqrt(dx * dx + dz * dz);
            }
        };
    }

    /** Within r blocks horizontally of the point (any height). */
    public static Goal near(BlockPos p, double r) {
        return new Goal() {
            public String key() {
                return "near" + p.asLong() + "/" + r;
            }

            public boolean done(int x, int y, int z) {
                double dx = x - p.getX(), dz = z - p.getZ();
                return dx * dx + dz * dz <= r * r && Math.abs(y - p.getY()) <= 4;
            }

            public double h(int x, int y, int z) {
                return Math.max(0, dist(x, y, z, p.getX(), p.getY(), p.getZ()) - r);
            }
        };
    }

    static double dist(int x, int y, int z, int a, int b, int c) {
        double dx = x - a, dy = y - b, dz = z - c;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static final class Node implements Comparable<Node> {
        final int x, y, z;
        double g, f;
        Node parent;
        Move move;
        boolean closed;

        Node(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @Override
        public int compareTo(Node o) {
            return Double.compare(f, o.f);
        }
    }

    private static final int[][] CARD = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final int[][] DIAG = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    public static final double WALK = 4.0;
    private static final double UNKNOWN_BODY = 0.6, UNKNOWN_FLOOR = 1.0;

    private final WorldMemory mem;
    private final PlayerInventory inv;
    private final boolean canPillar;
    private final boolean allowDig;
    private final LongOpenHashSet avoid;
    private final double heuristicWeight;
    private final Long2ObjectOpenHashMap<Double> digCache = new Long2ObjectOpenHashMap<>();

    public Navigator(WorldMemory mem, PlayerInventory inv, boolean canPillar, boolean allowDig, LongOpenHashSet avoid,
                     double heuristicWeight) {
        this.mem = mem;
        this.inv = inv;
        this.canPillar = canPillar;
        this.allowDig = allowDig;
        this.avoid = avoid;
        this.heuristicWeight = heuristicWeight;
    }

    // ------------------------------------------------------------------ cell costs

    /** Extra cost to make the body cell passable: 0 = free, infinite = impossible. */
    private double clearCost(int x, int y, int z, boolean digAllowed) {
        if (avoid != null && avoid.contains(BlockPos.asLong(x, y, z))) {
            return Double.POSITIVE_INFINITY;
        }
        BlockState s = mem.get(x, y, z);
        if (s == null) {
            return UNKNOWN_BODY;
        }
        switch (Blocks2.pass(s)) {
            case PASS:
                return 0;
            case WATER:
                return 2;
            case FORBID:
                return Double.POSITIVE_INFINITY;
            default:
                if (!digAllowed || !allowDig) {
                    return Double.POSITIVE_INFINITY;
                }
                return digCost(x, y, z, s);
        }
    }

    private double digCost(int x, int y, int z, BlockState s) {
        // never open lava; water next to the cell is expensive
        for (int[] d : new int[][]{{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, 1, 0}}) {
            BlockState n = mem.get(x + d[0], y + d[1], z + d[2]);
            if (n != null && Blocks2.isLava(n)) {
                return Double.POSITIVE_INFINITY;
            }
        }
        double extra = 0;
        BlockState above = mem.get(x, y + 1, z);
        if (above != null && Blocks2.isWater(above)) {
            extra += 40;
        }
        if (above != null && Blocks2.falls(above)) {
            extra += 15;
        }
        long key = net.minecraft.block.Block.getRawIdFromState(s);
        Double c = digCache.get(key);
        if (c == null) {
            int t = MiningTime.ticksWithCooldown(Blocks2.bestDelta(s, inv));
            c = t == Integer.MAX_VALUE || t > 200 ? Double.POSITIVE_INFINITY : t + 3.0;
            digCache.put(key, c);
        }
        return c + extra;
    }

    /** Cost of standing on the floor cell: 0 good, infinite = no floor. */
    private double floorCost(int x, int y, int z) {
        BlockState s = mem.get(x, y, z);
        if (s == null) {
            return UNKNOWN_FLOOR;
        }
        if (Blocks2.isWater(s)) {
            return 6;
        }
        if (Blocks2.isLava(s)) {
            return Double.POSITIVE_INFINITY;
        }
        return Blocks2.standable(s) ? 0 : Double.POSITIVE_INFINITY;
    }

    /**
     * Lava next to the body (feet or head level) or above the head can flow onto the player: such cells are never
     * stood in. Lava one level below a neighbour (a pool edge) only costs extra.
     */
    private double dangerCost(int x, int y, int z) {
        double c = 0;
        for (int[] d : CARD) {
            for (int dy = 0; dy <= 1; dy++) {
                BlockState n = mem.get(x + d[0], y + dy, z + d[1]);
                if (n != null && Blocks2.isLava(n)) {
                    return Double.POSITIVE_INFINITY;
                }
            }
            BlockState n = mem.get(x + d[0], y - 1, z + d[1]);
            if (n != null && Blocks2.isLava(n)) {
                c += 12;
            }
        }
        BlockState above = mem.get(x, y + 2, z);
        if (above != null && Blocks2.isLava(above)) {
            return Double.POSITIVE_INFINITY;
        }
        BlockState below = mem.get(x, y - 1, z);
        if (below != null && Blocks2.isLava(below)) {
            return Double.POSITIVE_INFINITY;
        }
        return c;
    }

    private boolean lavaAround(int x, int y, int z, int r) {
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    BlockState s = mem.get(x + dx, y + dy, z + dz);
                    if (s != null && Blocks2.isLava(s)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean isWaterAt(int x, int y, int z) {
        BlockState s = mem.get(x, y, z);
        return s != null && Blocks2.isWater(s);
    }

    // ------------------------------------------------------------------ search

    public Path find(BlockPos start, Goal goal, int maxNodes) {
        Long2ObjectOpenHashMap<Node> nodes = new Long2ObjectOpenHashMap<>();
        PriorityQueue<Node> open = new PriorityQueue<>();
        Node s = new Node(start.getX(), start.getY(), start.getZ());
        s.g = 0;
        s.f = goal.h(s.x, s.y, s.z) * WALK * heuristicWeight;
        nodes.put(BlockPos.asLong(s.x, s.y, s.z), s);
        open.add(s);
        Node best = s;
        double bestH = goal.h(s.x, s.y, s.z);
        int expanded = 0;
        while (!open.isEmpty() && expanded < maxNodes) {
            Node n = open.poll();
            if (n.closed) {
                continue;
            }
            n.closed = true;
            expanded++;
            if (goal.done(n.x, n.y, n.z)) {
                return build(n, true, expanded);
            }
            double h = goal.h(n.x, n.y, n.z);
            if (h < bestH) {
                bestH = h;
                best = n;
            }
            expand(n, nodes, open, goal);
        }
        return build(best, false, expanded);
    }

    private void relax(Node from, int x, int y, int z, double cost, Move move, Long2ObjectOpenHashMap<Node> nodes,
                       PriorityQueue<Node> open, Goal goal) {
        if (Double.isInfinite(cost) || Double.isNaN(cost) || y < 1 || y > 254) {
            return;
        }
        cost += dangerCost(x, y, z);
        long k = BlockPos.asLong(x, y, z);
        Node n = nodes.get(k);
        double g = from.g + cost;
        if (n == null) {
            n = new Node(x, y, z);
            nodes.put(k, n);
        } else if (n.closed || g >= n.g) {
            return;
        }
        n.g = g;
        n.parent = from;
        n.move = move;
        n.f = g + goal.h(x, y, z) * WALK * heuristicWeight;
        open.add(n);
    }

    private void expand(Node n, Long2ObjectOpenHashMap<Node> nodes, PriorityQueue<Node> open, Goal goal) {
        int x = n.x, y = n.y, z = n.z;
        boolean inWater = isWaterAt(x, y, z);
        for (int[] d : CARD) {
            int tx = x + d[0], tz = z + d[1];
            // walk
            double body = clearCost(tx, y, tz, true) + clearCost(tx, y + 1, tz, true);
            double floor = isWaterAt(tx, y, tz) ? 0 : floorCost(tx, y - 1, tz);
            relax(n, tx, y, tz, WALK + body + floor + (inWater ? 4 : 0), Move.WALK, nodes, open, goal);
            // step up (jump)
            double up = clearCost(x, y + 2, z, true) + clearCost(tx, y + 1, tz, true) + clearCost(tx, y + 2, tz, true);
            BlockState step = mem.get(tx, y, tz);
            double stepFloor = step == null ? UNKNOWN_FLOOR : (Blocks2.standable(step) ? 0 : Double.POSITIVE_INFINITY);
            relax(n, tx, y + 1, tz, WALK + 3 + up + stepFloor, Move.UP, nodes, open, goal);
            // drop down 1..3 (only the first may be dug: staircase)
            double col = clearCost(tx, y + 1, tz, true) + clearCost(tx, y, tz, true);
            for (int k = 1; k <= 3 && !Double.isInfinite(col); k++) {
                double c = clearCost(tx, y - k, tz, k == 1);
                if (Double.isInfinite(c)) {
                    break;
                }
                col += c;
                double fl = isWaterAt(tx, y - k, tz) ? 0 : floorCost(tx, y - k - 1, tz);
                relax(n, tx, y - k, tz, WALK + k + col + fl, Move.DOWN, nodes, open, goal);
                BlockState below = mem.get(tx, y - k - 1, tz);
                if (below == null || Blocks2.standable(below)) {
                    break; // landed
                }
            }
        }
        for (int[] d : DIAG) {
            int tx = x + d[0], tz = z + d[1];
            double c = clearCost(tx, y, tz, false) + clearCost(tx, y + 1, tz, false)
                    + clearCost(x + d[0], y, z, false) + clearCost(x + d[0], y + 1, z, false)
                    + clearCost(x, y, z + d[1], false) + clearCost(x, y + 1, z + d[1], false);
            double floor = floorCost(tx, y - 1, tz);
            relax(n, tx, y, tz, WALK * 1.414 + c + floor, Move.DIAG, nodes, open, goal);
        }
        // dig down
        double dd = clearCost(x, y - 1, z, true);
        if (!Double.isInfinite(dd) && dd > 0 && mem.get(x, y - 2, z) == null && lavaAround(x, y - 2, z, 3)) {
            dd = Double.POSITIVE_INFINITY; // digging blind into the floor next to a lava pool: what is under it may be lava
        }
        if (!Double.isInfinite(dd) && dd > 0) {
            relax(n, x, y - 1, z, 4 + dd + floorCost(x, y - 2, z), Move.DIG_DOWN, nodes, open, goal);
        }
        // swim up / pillar
        if (inWater) {
            relax(n, x, y + 1, z, 4 + clearCost(x, y + 2, z, false), Move.SWIM_UP, nodes, open, goal);
        } else if (canPillar) {
            relax(n, x, y + 1, z, 14 + clearCost(x, y + 2, z, true), Move.PILLAR, nodes, open, goal);
        }
    }

    private Path build(Node end, boolean complete, int expanded) {
        List<Step> steps = new ArrayList<>();
        Node n = end;
        while (n.parent != null) {
            steps.add(new Step(new BlockPos(n.x, n.y, n.z), n.move));
            n = n.parent;
        }
        Collections.reverse(steps);
        return new Path(steps, complete, end.g, expanded);
    }
}
