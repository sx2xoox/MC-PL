package io.github.sx2xoox.mcpl.game.augment.bot;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * The danger map around a bot, pure over a {@link Recovery.Grid}: how bad the cell its feet are in is (lava or fire
 * in it or under it burns, magma under it or a berry bush around it hurts, lava flowing next to it is about to), and the nearest
 * cell to get to out of it (water first while burning, which puts the fire out, as a player jumps in a lake). The
 * escape is a short walk over cells a body can stand in (up one block, down at most {@link #SAFE_DROP}), so a bot
 * knocked or poured into lava steps straight out instead of dying in it, and never escapes over a cliff.
 */
public final class Danger {

    /** How bad a feet cell is. */
    public enum Level {
        /** Nothing within reach. */
        SAFE,
        /** Lava is within {@link #MARGIN} blocks (it flows, a knockback can put it in): get away from it. */
        NEAR,
        /** It hurts to stand there: magma underfoot, in a berry bush. */
        HURT,
        /** It burns: lava or fire in the feet or head cell or right under it. */
        BURN
    }

    /** Blocks around the feet in which lava counts as near (a step of flowing lava, a knockback). */
    public static final int MARGIN = 2;
    /** The deepest drop an escape walks off (a fall that hurts is no escape). */
    public static final int SAFE_DROP = 3;
    /** Burning cells an escape may wade through (out of the middle of a poured bucket). */
    static final int BURN_STEPS = 2;

    private Danger() {
    }

    /** How dangerous feet cell {@code (x, y, z)} is. */
    public static Level level(Recovery.Grid g, int x, int y, int z) {
        Level hurt = harm(g, x, y, z);
        if (hurt != Level.SAFE) {
            return hurt;
        }
        return lavaWithin(g, x, y, z, MARGIN) ? Level.NEAR : Level.SAFE;
    }

    /**
     * {@link Level#BURN} or {@link Level#HURT} if standing in {@code (x, y, z)} does harm now, else {@link Level#SAFE}
     * (without looking for lava around: the cheap check run every tick).
     */
    public static Level harm(Recovery.Grid g, int x, int y, int z) {
        if (g.lava(x, y, z) || g.lava(x, y + 1, z) || g.lava(x, y - 1, z) || burns(g, x, y, z)
                || burns(g, x, y + 1, z)) {
            return Level.BURN;
        }
        if (g instanceof Fires fires && fires.hot(x, y - 1, z) || sideHazard(g, x, y, z)) {
            return Level.HURT;
        }
        return Level.SAFE;
    }

    /** Fire, a campfire and the like in the cell (a hazard that is neither solid nor a liquid, not a web). */
    private static boolean burns(Recovery.Grid g, int x, int y, int z) {
        return g instanceof Fires fires && fires.fire(x, y, z);
    }

    /** In a sweet berry bush (it pricks whoever walks in it). */
    private static boolean sideHazard(Recovery.Grid g, int x, int y, int z) {
        return g instanceof Fires fires && (fires.prickly(x, y, z) || fires.prickly(x, y + 1, z));
    }

    /** True if there is lava within {@code radius} blocks (horizontally; one below to one above the head). */
    public static boolean lavaWithin(Recovery.Grid g, int x, int y, int z, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -1; dy <= 2; dy++) {
                    if (g.lava(x + dx, y + dy, z + dz)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** The grid's optional extras: fire in a cell, and what pricks whoever brushes against it. */
    public interface Fires {
        /** Fire or a campfire in the cell. */
        boolean fire(int x, int y, int z);

        /** A sweet berry bush in the cell (hurts whoever walks in it). */
        boolean prickly(int x, int y, int z);

        /** A magma block (hurts whoever stands on it). */
        boolean hot(int x, int y, int z);
    }

    /**
     * The nearest feet cell within {@code radius} steps of {@code (x, y, z)} that is {@link Level#SAFE} (with
     * {@code water}: a water cell, the fire goes out there, else safe), walking over cells a body can stand or swim in;
     * cells that burn are crossed only in the first {@link #BURN_STEPS} steps out. {x, y, z}, or null if none.
     */
    public static int @Nullable [] escape(Recovery.Grid g, int x, int y, int z, int radius, boolean water) {
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        Set<Long> seen = new HashSet<>();
        queue.add(new int[] {x, y, z, 0});
        seen.add(key(x, y, z));
        int[] fallback = null;
        int[][] sides = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!queue.isEmpty()) {
            int[] c = queue.poll();
            boolean start = c[3] == 0;
            if (!start) {
                Level level = level(g, c[0], c[1], c[2]);
                boolean wet = g.liquid(c[0], c[1], c[2]) && !g.lava(c[0], c[1], c[2]);
                if (water && wet && level != Level.BURN) {
                    return new int[] {c[0], c[1], c[2]};
                }
                if (level == Level.SAFE && !wet) {
                    if (!water) {
                        return new int[] {c[0], c[1], c[2]};
                    }
                    if (fallback == null) {
                        fallback = new int[] {c[0], c[1], c[2]};
                    }
                }
                if (level == Level.BURN && c[3] >= BURN_STEPS) {
                    continue; // only a step or two through lava or fire, never a walk through it
                }
            }
            if (c[3] >= radius) {
                continue;
            }
            for (int[] s : sides) {
                int nx = c[0] + s[0];
                int nz = c[2] + s[1];
                int ny = landing(g, nx, c[1], nz);
                if (ny == Integer.MIN_VALUE || !seen.add(key(nx, ny, nz))) {
                    continue;
                }
                queue.add(new int[] {nx, ny, nz, c[3] + 1});
            }
        }
        return fallback;
    }

    /**
     * Where feet stepping from height {@code y} into column {@code (x, z)} end up: the same height, one up (a step
     * with room above) or down to {@link #SAFE_DROP}; a liquid counts as something to stand in.
     * {@link Integer#MIN_VALUE} if blocked or a deeper drop.
     */
    static int landing(Recovery.Grid g, int x, int y, int z) {
        if (open(g, x, y, z)) {
            for (int fy = y; fy >= y - SAFE_DROP; fy--) {
                if (!open(g, x, fy, z)) {
                    return Integer.MIN_VALUE;
                }
                if (g.solid(x, fy - 1, z) || g.liquid(x, fy, z) || g.liquid(x, fy - 1, z)) {
                    return fy;
                }
            }
            return Integer.MIN_VALUE;
        }
        if (open(g, x, y + 1, z) && !g.solid(x, y + 2, z) && g.solid(x, y, z)) {
            return y + 1;
        }
        return Integer.MIN_VALUE;
    }

    /** Room for a body (feet and head cells not solid). */
    private static boolean open(Recovery.Grid g, int x, int y, int z) {
        return !g.solid(x, y, z) && !g.solid(x, y + 1, z);
    }

    private static long key(int x, int y, int z) {
        return ((long) x & 0x3FFFFFF) << 38 | ((long) z & 0x3FFFFFF) << 12 | (long) y & 0xFFF;
    }
}
