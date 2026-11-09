package io.github.sx2xoox.mcpl.game.augment.bot;

import org.jspecify.annotations.Nullable;

/**
 * What a bot does about an enemy up where its sword cannot reach (a player who pillars up and shoots down, the
 * classic way to farm a mob): pure, over a {@link Recovery.Grid}, so every answer is unit-tested.
 *
 * <p>A player answers in a few ways and so does the bot, best first:
 * <ol>
 *   <li>{@link Response#CHOP}: the block under their feet is within reach from a spot on the ground next to their
 *       column: break it and they drop (into its sword, taking the fall).</li>
 *   <li>{@link Response#CLIMB}: too high for that: pillar up right next to their column (a block under itself each
 *       jump) until the block under their feet is within reach, then break it.</li>
 *   <li>{@link Response#SHOOT}: no blocks to climb with: shoot back from a little way off (a still target on a
 *       pillar is easy to hit), zig-zagging while they draw.</li>
 *   <li>{@link Response#COVER}: neither: get out of their line of fire and range and wait for them to come down
 *       instead of standing under them.</li>
 * </ol>
 */
public final class Perch {

    /** What to do about a perched enemy. */
    public enum Response {
        /** Not perched (reachable, or not high enough to matter): fight as usual. */
        NONE,
        /** Break the block under their feet from a spot on the ground. */
        CHOP,
        /** Pillar up next to their column, then break the block under their feet. */
        CLIMB,
        /** Shoot back from a little way off. */
        SHOOT,
        /** Out of their line of fire; wait for them to come down. */
        COVER
    }

    /**
     * The answer.
     *
     * @param response what to do
     * @param stand    the feet cell to stand in (CHOP: on the ground; CLIMB: the foot of its own pillar), or null
     * @param chop     the block to break (under their feet), or null
     * @param climb    blocks it must pillar up before the chop is within reach (CLIMB)
     */
    public record Plan(Response response, int @Nullable [] stand, int @Nullable [] chop, int climb) {
        static final Plan NONE = new Plan(Response.NONE, null, null, 0);
    }

    /** Feet this many blocks above its own (at a sword's horizontal reach) are out of its sword's reach. */
    public static final double HIGH = 2.5;
    /** A player's block reach (eye to the middle of the block). */
    public static final double BLOCK_REACH = 4.5;
    /** Eye height of a standing body. */
    static final double EYE = 1.62;
    /** The tallest pillar it still climbs beside (higher: it shoots or takes cover). */
    static final int MAX_CLIMB = 12;
    /** Arrows it wants for a shoot-out. */
    static final int MIN_ARROWS = 4;

    private Perch() {
    }

    /**
     * How many blocks of a lone 1×1 column hold up feet in cell {@code (x, y, z)}: counted down from the block under
     * the feet while it is solid and none of its four sides is (a player's pillar, a tree trunk's top is not one
     * when leaves or blocks touch it). 0 if they do not stand on such a column.
     */
    public static int column(Recovery.Grid g, int x, int y, int z) {
        int height = 0;
        for (int cy = y - 1; cy > y - 1 - 64; cy--) {
            if (!g.solid(x, cy, z)) {
                return height;
            }
            if (g.solid(x + 1, cy, z) || g.solid(x - 1, cy, z) || g.solid(x, cy, z + 1) || g.solid(x, cy, z - 1)) {
                return height; // the ground (or a wall) beside it: the column ends here
            }
            height++;
        }
        return height;
    }

    /**
     * True if feet at {@code (tx, ty, tz)} (block coordinates of the feet, {@code ty} rounded down) are out of the
     * sword's reach of a bot whose feet are at {@code (bx, by, bz)}: well above it, and either on a column or not
     * reachable on foot ({@code reachable}: its pathfinder gets there).
     */
    public static boolean perched(double dy, int column, boolean reachable) {
        return dy >= HIGH && (column >= 2 || !reachable);
    }

    /**
     * The answer to an enemy whose feet are in cell {@code (tx, ty, tz)}, for a bot with its feet in {@code (bx, by,
     * bz)}, {@code arrows} arrows (0 without a bow) and {@code blocks} blocks to place.
     */
    public static Plan plan(Recovery.Grid g, int bx, int by, int bz, int tx, int ty, int tz, int arrows,
                            int blocks) {
        int column = column(g, tx, ty, tz);
        if (ty - by < HIGH) {
            return Plan.NONE;
        }
        if (column < 2) {
            // a ledge, a roof or a cliff it cannot path up to: shoot back, else its usual way up (stairs, pillar)
            return arrows >= MIN_ARROWS ? new Plan(Response.SHOOT, null, null, 0) : Plan.NONE;
        }
        int[] chop = {tx, ty - 1, tz};
        boolean choppable = Recovery.clearable(g, chop[0], chop[1], chop[2]);
        if (choppable) {
            // a spot on the ground beside the column from which the block under their feet is within reach
            int[] best = null;
            double bestCost = Double.POSITIVE_INFINITY;
            for (int r = 1; r <= 2; r++) {
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                            continue;
                        }
                        int sx = tx + dx;
                        int sz = tz + dz;
                        int sy = floor(g, sx, by, sz);
                        if (sy == Integer.MIN_VALUE || !reaches(sx, sy, sz, chop)) {
                            continue;
                        }
                        double cost = Math.hypot(sx - bx, sz - bz) + Math.abs(sy - by) * 2;
                        if (cost < bestCost) {
                            bestCost = cost;
                            best = new int[] {sx, sy, sz};
                        }
                    }
                }
            }
            if (best != null) {
                return new Plan(Response.CHOP, best, chop, 0);
            }
            // too high: pillar up beside the column (on its side facing the bot) until it is in reach
            int[] foot = climbFoot(g, bx, by, bz, tx, tz);
            if (foot != null && blocks > 0) {
                int need = 0;
                while (need <= MAX_CLIMB && !reaches(foot[0], foot[1] + need, foot[2], chop)) {
                    need++;
                }
                if (need <= MAX_CLIMB && need <= blocks && openAbove(g, foot, need + 2)) {
                    return new Plan(Response.CLIMB, foot, chop, need);
                }
            }
        }
        if (arrows >= MIN_ARROWS) {
            return new Plan(Response.SHOOT, null, choppable ? chop : null, 0);
        }
        return new Plan(Response.COVER, null, null, 0);
    }

    /** True if the middle of {@code block} is within {@link #BLOCK_REACH} of the eye of feet in {@code (x, y, z)}. */
    static boolean reaches(int x, double y, int z, int[] block) {
        double ex = x + 0.5;
        double ey = y + EYE;
        double ez = z + 0.5;
        double dx = block[0] + 0.5 - ex;
        double dy = block[1] + 0.5 - ey;
        double dz = block[2] + 0.5 - ez;
        return dx * dx + dy * dy + dz * dz <= BLOCK_REACH * BLOCK_REACH;
    }

    /**
     * The feet height of a standable spot in column {@code (x, z)} within 2 blocks of {@code y} (a solid, harmless
     * floor, two free cells), nearest first; {@link Integer#MIN_VALUE} if none.
     */
    static int floor(Recovery.Grid g, int x, int y, int z) {
        int[] order = {0, 1, -1, 2, -2};
        for (int d : order) {
            int fy = y + d;
            if (standable(g, x, fy, z)) {
                return fy;
            }
        }
        return Integer.MIN_VALUE;
    }

    /** A solid, harmless floor under {@code (x, y, z)} and room for a body there, with no lava next to the feet. */
    static boolean standable(Recovery.Grid g, int x, int y, int z) {
        return g.solid(x, y - 1, z) && !g.hazard(x, y - 1, z) && !g.lava(x, y - 1, z)
                && !g.solid(x, y, z) && !g.solid(x, y + 1, z) && !g.liquid(x, y, z) && !g.liquid(x, y + 1, z)
                && !g.hazard(x, y, z) && !g.hazard(x, y + 1, z);
    }

    /** The ground cell beside the column at {@code (tx, tz)} on the bot's side, where it starts its own pillar. */
    static int @Nullable [] climbFoot(Recovery.Grid g, int bx, int by, int bz, int tx, int tz) {
        int[] best = null;
        double bestCost = Double.POSITIVE_INFINITY;
        int[][] sides = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] s : sides) {
            int sx = tx + s[0];
            int sz = tz + s[1];
            int sy = floor(g, sx, by, sz);
            if (sy == Integer.MIN_VALUE) {
                continue;
            }
            double cost = Math.hypot(sx - bx, sz - bz) + Math.abs(sy - by) * 2;
            if (cost < bestCost) {
                bestCost = cost;
                best = new int[] {sx, sy, sz};
            }
        }
        return best;
    }

    /** Room to pillar {@code height} blocks up from feet cell {@code foot} (nothing solid above its head). */
    static boolean openAbove(Recovery.Grid g, int[] foot, int height) {
        for (int dy = 0; dy <= height; dy++) {
            if (g.solid(foot[0], foot[1] + dy, foot[2]) || g.liquid(foot[0], foot[1] + dy, foot[2])) {
                return false;
            }
        }
        return true;
    }

    /**
     * Where to stand for a shoot-out with an enemy perched at {@code (tx, tz)} seen from {@code (bx, bz)}: on the line
     * from them to the bot, {@code want} blocks out (far enough that the bow is not pointed straight up and their
     * arrows have to lead it, close enough to hit every shot). Returns {x, z}.
     */
    public static double[] shootSpot(double bx, double bz, double tx, double tz, double want) {
        double dx = bx - tx;
        double dz = bz - tz;
        double length = Math.hypot(dx, dz);
        if (length < 1.0E-6) {
            dx = 1;
            dz = 0;
            length = 1;
        }
        return new double[] {tx + dx / length * want, tz + dz / length * want};
    }
}
