package io.github.sx2xoox.mcpl.game.augment.bot;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * How a stuck bot gets on like a player, without Bukkit: one step at a time towards its goal it walks, tunnels
 * (breaks the 1×2 in front), digs a staircase up (out of a cave or a ravine, into a cliff) or down, pillars up (jumps
 * and places a block under itself) against an open wall, or bridges a gap, water or a hazard. It never breaks
 * unbreakable blocks, never opens a way into lava and never steps into powder snow, fire or the like (those are broken
 * or bridged over first).
 */
public final class Recovery {

    /** The blocks around the bot as the planner sees them. */
    public interface Grid {
        /** A body cannot pass through it (stone, dirt, logs, placed blocks; not plants, snow layers or air). */
        boolean solid(int x, int y, int z);

        /** It may be broken (not bedrock, barriers and the like). */
        boolean breakable(int x, int y, int z);

        /** Water or lava. */
        boolean liquid(int x, int y, int z);

        /** Lava (never opened up). */
        boolean lava(int x, int y, int z);

        /** Hurts or traps whoever is inside or on it: powder snow, fire, cactus, berry bushes, magma, cobwebs. */
        boolean hazard(int x, int y, int z);
    }

    /** What kind of step. */
    public enum Kind {
        /** Nothing to do from here in this direction (try another way). */
        NONE,
        /** The way is open: walk one block on. */
        WALK,
        /** Break the 1×2 in front and walk in. */
        TUNNEL,
        /** Dig a step up (the block above the head, the two in front one higher) and climb it. */
        STAIR_UP,
        /** Dig a step down. */
        STAIR_DOWN,
        /** Jump and place a block under itself. */
        PILLAR,
        /** Place a floor block in front (over a gap, water or a hazard) and walk on. */
        BRIDGE
    }

    /**
     * One step.
     *
     * @param kind   what it is
     * @param breaks blocks to break first, {x, y, z}
     * @param places blocks to place, {x, y, z} (for a pillar: under the feet once it has jumped)
     * @param to     the feet block to be in afterwards
     */
    public record Step(Kind kind, List<int[]> breaks, List<int[]> places, int @Nullable [] to) {

        public Step {
            breaks = List.copyOf(breaks);
            places = List.copyOf(places);
        }

        static final Step NONE = new Step(Kind.NONE, List.of(), List.of(), null);
    }

    /** Height difference (goal above the feet) from which it climbs instead of going level. */
    static final int CLIMB = 2;
    /** Depth of a gap (in blocks of air below the floor level) that is bridged rather than dropped into. */
    static final int SAFE_DROP = 3;

    private Recovery() {
    }

    /**
     * The next step from feet block {@code (x, y, z)} one block along {@code (dx, dz)} (a unit step on one axis) for a
     * goal {@code rise} blocks higher (negative: lower); {@code underground} if it has no sky above (a cave: it climbs
     * towards the surface), {@code canPlace} if it has blocks left.
     */
    public static Step next(Grid g, int x, int y, int z, int dx, int dz, int rise, boolean underground,
                            boolean canPlace) {
        int fx = x + dx;
        int fz = z + dz;
        // a hazard where the feet or head would go is cleared (powder snow), never walked into
        boolean up = rise >= CLIMB || underground && rise > -CLIMB;
        if (up) {
            Step climb = climb(g, x, y, z, fx, fz, underground, canPlace);
            if (climb.kind() != Kind.NONE) {
                return climb;
            }
        }
        if (rise <= -SAFE_DROP && !underground) {
            Step down = stairDown(g, x, y, z, fx, fz, canPlace);
            if (down.kind() != Kind.NONE) {
                return down;
            }
        }
        return level(g, x, y, z, fx, fz, canPlace);
    }

    /** Going on at the same height. */
    static Step level(Grid g, int x, int y, int z, int fx, int fz, boolean canPlace) {
        List<int[]> breaks = new ArrayList<>();
        for (int dy = 0; dy <= 1; dy++) {
            if (g.solid(fx, y + dy, fz) || g.hazard(fx, y + dy, fz)) {
                if (!clearable(g, fx, y + dy, fz)) {
                    return Step.NONE;
                }
                breaks.add(new int[] {fx, y + dy, fz});
            }
        }
        if (g.liquid(fx, y, fz) || g.liquid(fx, y + 1, fz)) {
            return Step.NONE; // swimming is the pathfinder's job
        }
        // the floor in front: solid and harmless, else bridge it (a gap, water, lava, powder snow, magma)
        boolean floorOk = g.solid(fx, y - 1, fz) && !g.hazard(fx, y - 1, fz);
        if (!floorOk) {
            boolean gap = !g.solid(fx, y - 1, fz) && !g.liquid(fx, y - 1, fz) && !g.hazard(fx, y - 1, fz);
            int depth = 0;
            while (gap && depth <= SAFE_DROP && !g.solid(fx, y - 2 - depth, fz) && !g.liquid(fx, y - 2 - depth, fz)) {
                depth++;
            }
            boolean shallow = gap && depth < SAFE_DROP && !g.hazard(fx, y - 2 - depth, fz)
                    && !g.liquid(fx, y - 2 - depth, fz);
            if (shallow) {
                return new Step(breaks.isEmpty() ? Kind.WALK : Kind.TUNNEL, breaks, List.of(),
                        new int[] {fx, y, fz}); // a small drop: just step down
            }
            if (!canPlace || g.hazard(fx, y - 1, fz) && !g.breakable(fx, y - 1, fz)) {
                return Step.NONE;
            }
            if (g.hazard(fx, y - 1, fz) || g.solid(fx, y - 1, fz)) {
                breaks.add(new int[] {fx, y - 1, fz}); // powder snow or magma underfoot: replaced by a block
            }
            return new Step(Kind.BRIDGE, breaks, List.<int[]>of(new int[] {fx, y - 1, fz}), new int[] {fx, y, fz});
        }
        return new Step(breaks.isEmpty() ? Kind.WALK : Kind.TUNNEL, breaks, List.of(), new int[] {fx, y, fz});
    }

    /** One block higher: step up if the way is open, else dig a step, else pillar (open sky only). */
    static Step climb(Grid g, int x, int y, int z, int fx, int fz, boolean underground, boolean canPlace) {
        boolean headroom = !g.solid(x, y + 2, z) && !g.hazard(x, y + 2, z);
        boolean frontOpen = !blocked(g, fx, y + 1, fz) && !blocked(g, fx, y + 2, fz);
        boolean frontFloor = g.solid(fx, y, fz) && !g.hazard(fx, y, fz);
        if (headroom && frontOpen && frontFloor) {
            return new Step(Kind.WALK, List.of(), List.of(), new int[] {fx, y + 1, fz});
        }
        if (headroom && !underground && canPlace && blocked(g, fx, y + 1, fz) && !g.solid(x, y + 3, z)
                && !g.hazard(x, y + 3, z) && g.solid(x, y - 1, z)) {
            // an open wall: pillar up next to it
            return new Step(Kind.PILLAR, List.of(), List.<int[]>of(new int[] {x, y, z}), new int[] {x, y + 1, z});
        }
        List<int[]> breaks = new ArrayList<>();
        int[][] cells = {{x, y + 2, z}, {fx, y + 1, fz}, {fx, y + 2, fz}};
        for (int[] c : cells) {
            if (g.solid(c[0], c[1], c[2]) || g.hazard(c[0], c[1], c[2])) {
                if (!clearable(g, c[0], c[1], c[2])) {
                    return Step.NONE;
                }
                breaks.add(c);
            }
            if (g.liquid(c[0], c[1], c[2])) {
                return Step.NONE;
            }
        }
        List<int[]> places = new ArrayList<>();
        if (!frontFloor) {
            if (!canPlace) {
                return Step.NONE;
            }
            if (g.hazard(fx, y, fz)) {
                if (!g.breakable(fx, y, fz)) {
                    return Step.NONE;
                }
                breaks.add(new int[] {fx, y, fz});
            }
            places.add(new int[] {fx, y, fz});
        }
        return new Step(Kind.STAIR_UP, breaks, places, new int[] {fx, y + 1, fz});
    }

    /** One block lower: dig (or bridge) a step down. */
    static Step stairDown(Grid g, int x, int y, int z, int fx, int fz, boolean canPlace) {
        List<int[]> breaks = new ArrayList<>();
        for (int dy = -1; dy <= 1; dy++) {
            if (g.solid(fx, y + dy, fz) || g.hazard(fx, y + dy, fz)) {
                if (!clearable(g, fx, y + dy, fz)) {
                    return Step.NONE;
                }
                breaks.add(new int[] {fx, y + dy, fz});
            }
            if (g.liquid(fx, y + dy, fz)) {
                return Step.NONE;
            }
        }
        List<int[]> places = new ArrayList<>();
        if (!g.solid(fx, y - 2, fz) || g.hazard(fx, y - 2, fz)) {
            if (!canPlace || g.liquid(fx, y - 2, fz) && g.lava(fx, y - 2, fz)) {
                return Step.NONE;
            }
            if (g.hazard(fx, y - 2, fz) || g.solid(fx, y - 2, fz)) {
                breaks.add(new int[] {fx, y - 2, fz});
            }
            places.add(new int[] {fx, y - 2, fz});
        }
        return new Step(Kind.STAIR_DOWN, breaks, places, new int[] {fx, y - 1, fz});
    }

    private static boolean blocked(Grid g, int x, int y, int z) {
        return g.solid(x, y, z) || g.hazard(x, y, z) || g.liquid(x, y, z);
    }

    /** It can be broken without letting lava in (no lava next to it). */
    static boolean clearable(Grid g, int x, int y, int z) {
        if (!g.breakable(x, y, z) || g.lava(x, y, z)) {
            return false;
        }
        int[][] around = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int[] a : around) {
            if (g.lava(x + a[0], y + a[1], z + a[2])) {
                return false;
            }
        }
        return true;
    }

    /**
     * The unit step (on one axis) towards {@code (tx, tz)} from {@code (x, z)}; {@code turn} 0 straight, 1 and 2 the
     * two sides, 3 back (when the straight way is impossible).
     */
    public static int[] direction(double x, double z, double tx, double tz, int turn) {
        double ax = tx - x;
        double az = tz - z;
        int dx;
        int dz;
        if (Math.abs(ax) >= Math.abs(az)) {
            dx = ax >= 0 ? 1 : -1;
            dz = 0;
        } else {
            dx = 0;
            dz = az >= 0 ? 1 : -1;
        }
        return switch (turn & 3) {
            case 1 -> new int[] {-dz, dx};
            case 2 -> new int[] {dz, -dx};
            case 3 -> new int[] {-dx, -dz};
            default -> new int[] {dx, dz};
        };
    }

    /** The window over which progress is judged (ticks). */
    public static final int WINDOW = 80;

    /**
     * True if the bot made no progress over one {@link #WINDOW}: it hardly moved, or its goal stayed put and it got no
     * closer to it (walking along a cliff it cannot climb). A goal within a few blocks never counts.
     *
     * @param moved        blocks the legs moved over the window
     * @param gained       blocks it got closer to the goal
     * @param goalShift    blocks the goal itself moved
     * @param goalDistance blocks to the goal now
     */
    public static boolean stuck(double moved, double gained, double goalShift, double goalDistance) {
        if (goalDistance <= 3) {
            return false;
        }
        return moved < 1.5 || goalShift < 4 && gained < 1.5;
    }
}
