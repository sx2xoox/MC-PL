package io.github.sx2xoox.mcpl.game.augment.bot;

import org.jspecify.annotations.Nullable;

/**
 * How fast a bot moves, without Bukkit: the walking and sprinting speeds of a player, the pathfinder speed that gives
 * them to the legs, and the fastest a closing border may move so a bot can always outrun it.
 *
 * <p>A pathing mob on ground moves {@code GROUND_FACTOR × s²} blocks a second where {@code s} is its movement speed
 * attribute times the pathfinder's speed modifier: the mob's move control sets both its speed and its forward input to
 * {@code s} ({@code Mob#setSpeed}), and the ground acceleration is input × speed, so the speed counts twice. (A player's
 * input is 1, which is why a player's speed is linear in the attribute.) The factor is
 * {@code 20 ticks × 0.98 input damping / (1 − 0.6 × 0.91 ground friction)}.
 */
public final class BotMotion {

    /** Blocks a second per squared mob speed on ordinary ground (see the class comment). */
    public static final double GROUND_FACTOR = 20 * 0.98 / (1 - 0.6 * 0.91);
    /** A walking player, blocks a second. */
    public static final double WALK_BPS = 4.317;
    /** A sprinting player, blocks a second. */
    public static final double SPRINT_BPS = 5.612;
    /** A crouch-walking player, blocks a second. */
    public static final double SNEAK_BPS = 1.31;
    /** The legs' movement speed attribute at which {@code bots.move-speed} means "as fast as a player". */
    public static final double REFERENCE_ATTRIBUTE = 0.32;
    /** Travelling further than this, a bot sprints (closer it walks, like a player lining up a hit). */
    public static final double SPRINT_FROM = 6;
    /** Swimming: horizontal blocks a tick (a player swims about 2-5 blocks a second). */
    public static final double SWIM_SPRINT = 0.2;
    public static final double SWIM_WALK = 0.11;
    /** A closing border never moves faster than this share of a bot's sprint (it can always get inside). */
    public static final double BORDER_SHARE = 0.7;

    private BotMotion() {
    }

    /** How a bot moves to where it is going. */
    public enum Gait {
        /** Creeping up on someone: a player's crouch-walk (footsteps heard only from close by). */
        SNEAK,
        /** Lining up, strafing, roaming: a player's walk. */
        WALK,
        /** Travelling far, chasing, fleeing: a player's sprint once the destination is more than a few blocks off. */
        SPRINT,
        /** Sprint all the way (the closing border: every block counts). */
        RUSH
    }

    /**
     * Blocks a second for {@code gait} over {@code distance} blocks with {@code factor} times the normal speed (the
     * body's movement speed bonuses, e.g. 질풍), for legs at {@link #REFERENCE_ATTRIBUTE}.
     */
    public static double blocksPerSecond(Gait gait, double distance, double factor) {
        double base = gait == Gait.SNEAK ? SNEAK_BPS : sprints(gait, distance) ? SPRINT_BPS : WALK_BPS;
        return base * clamp(factor, 0.3, 3.0);
    }

    /** True if {@code gait} sprints over {@code distance} blocks. */
    public static boolean sprints(Gait gait, double distance) {
        return gait == Gait.RUSH || gait == Gait.SPRINT && distance > SPRINT_FROM;
    }

    /**
     * The pathfinder speed modifier that moves legs with movement speed attribute {@code attribute} at
     * {@code blocksPerSecond} on ordinary ground.
     */
    public static double pathSpeed(double blocksPerSecond, double attribute) {
        double mobSpeed = Math.sqrt(Math.max(0, blocksPerSecond) / GROUND_FACTOR);
        return attribute > 0 ? mobSpeed / attribute : 1.0;
    }

    /** Blocks a second legs with {@code attribute} walk at pathfinder speed {@code modifier} on ordinary ground. */
    public static double groundSpeed(double attribute, double modifier) {
        double s = attribute * modifier;
        return GROUND_FACTOR * s * s;
    }

    /** The fastest the border's edge may move, blocks a second. */
    public static double maxBorderEdgeSpeed() {
        return BORDER_SHARE * SPRINT_BPS;
    }

    /**
     * The fastest the match clock may run while the border closes from {@code startSize} to {@code finalSize} in
     * {@code shrinkTicks} match ticks, so its edge never moves faster than {@link #maxBorderEdgeSpeed()}; at least 1
     * (the configured speed itself is never slowed down), at most {@code timeScale}.
     */
    public static double shrinkClockRate(double timeScale, double startSize, double finalSize, long shrinkTicks) {
        double travel = Math.max(0, startSize - finalSize) / 2;
        if (travel <= 0 || shrinkTicks <= 0) {
            return Math.max(1, timeScale);
        }
        double cap = maxBorderEdgeSpeed() * (shrinkTicks / 20.0) / travel;
        return Math.max(1, Math.min(timeScale, cap));
    }

    /** The border edge's speed, blocks a second, at clock rate {@code rate}. */
    public static double borderEdgeSpeed(double rate, double startSize, double finalSize, long shrinkTicks) {
        if (shrinkTicks <= 0) {
            return Double.POSITIVE_INFINITY;
        }
        return Math.max(0, startSize - finalSize) / 2 * rate / (shrinkTicks / 20.0);
    }

    /** What occupies a block, for {@link #freeSpot}. */
    public interface Space {
        /** True if a standing body with its feet at block {@code (x, y, z)} touches no solid block. */
        boolean free(int x, int y, int z);

        /** True if the block can be stood on. */
        boolean floor(int x, int y, int z);
    }

    /**
     * The nearest standing spot to block {@code (x, y, z)} where a body fits, preferring the same height, then up (a
     * player climbs out of a hole rather than down), within {@code range} blocks: {x, y, z} of the feet block, or null.
     */
    public static int @Nullable [] freeSpot(Space space, int x, int y, int z, int range) {
        int[] best = null;
        double bestCost = Double.POSITIVE_INFINITY;
        for (int dy = -1; dy <= range + 1; dy++) {
            for (int dx = -range; dx <= range; dx++) {
                for (int dz = -range; dz <= range; dz++) {
                    int fx = x + dx;
                    int fy = y + dy;
                    int fz = z + dz;
                    if (!space.free(fx, fy, fz) || !space.floor(fx, fy - 1, fz)) {
                        continue;
                    }
                    double cost = dx * dx + dz * dz + (dy < 0 ? 4.0 : dy * dy * 0.8);
                    if (cost < bestCost) {
                        bestCost = cost;
                        best = new int[] {fx, fy, fz};
                    }
                }
            }
        }
        return best;
    }

    private static double clamp(double value, double min, double max) {
        return Double.isFinite(value) ? Math.max(min, Math.min(max, value)) : 1.0;
    }
}
