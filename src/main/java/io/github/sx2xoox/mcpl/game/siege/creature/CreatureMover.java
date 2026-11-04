package io.github.sx2xoox.mcpl.game.siege.creature;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.DoubleFunction;

import io.github.sx2xoox.mcpl.game.siege.tower.Footprint;
import org.bukkit.Location;
import org.bukkit.entity.Mob;
import org.bukkit.util.Vector;

/**
 * Walks a creature somewhere. The default ({@link #pathfinder()}) uses Paper's
 * {@link com.destroystokyo.paper.entity.Pathfinder}; where the server has none (a test server) it steps the creature
 * straight towards the goal every {@link #tick}, so the rules can be tested.
 *
 * <p>Every creature has the same plain walking body ({@link CreatureBodies}), so every kind is walked the same way at
 * its own speed; how a toy hops, bounces or scurries along is its model's gait, never the body's path.
 *
 * <p>Buildings ({@link #pathfinder(DoubleFunction)}'s walls, the 공성전 structures' barrier squares): a goal inside one is
 * moved out of it (the pathfinder cannot reach it, a stepped creature would walk into it) and a stepped creature goes
 * round them ({@link Footprint.Box#step}) instead of through.
 */
public interface CreatureMover {

    /** Blocks a stepped creature moves per tick at speed 1 (about a body's walk). */
    double STEP_PER_TICK = 0.2;

    /** Starts (or renews) walking to {@code to} at {@code speed} (1 = the body's normal speed). */
    void moveTo(Mob mob, Location to, double speed);

    /** Stops walking. */
    void stop(Mob mob);

    /** Every tick for each driven creature. */
    default void tick(Mob mob) {
    }

    /** Paper's pathfinder, stepping on servers without one. */
    static CreatureMover pathfinder() {
        return pathfinder(margin -> List.of());
    }

    /**
     * {@link #pathfinder()} around buildings: {@code walls} gives the ground squares no body of the given half width may
     * stand in.
     */
    static CreatureMover pathfinder(DoubleFunction<List<Footprint.Box>> walls) {
        return new CreatureMover() {
            /** Goals of creatures the server cannot path for, with their speed. */
            private final Map<Mob, Object[]> stepping = new WeakHashMap<>();

            @Override
            public void moveTo(Mob mob, Location wanted, double speed) {
                Location to = clear(walls.apply(halfWidth(mob)), wanted);
                try {
                    mob.getPathfinder().moveTo(to, speed);
                } catch (RuntimeException noPathfinder) {
                    stepping.put(mob, new Object[] {to.clone(), speed});
                }
            }

            @Override
            public void stop(Mob mob) {
                if (stepping.remove(mob) != null) {
                    return;
                }
                try {
                    mob.getPathfinder().stopPathfinding();
                } catch (RuntimeException noPathfinder) {
                    // Nothing walks on a server without a pathfinder.
                }
            }

            @Override
            public void tick(Mob mob) {
                Object[] goal = stepping.get(mob);
                if (goal != null && mob.isValid()) {
                    Location to = (Location) goal[0];
                    step(mob, to, STEP_PER_TICK * (double) goal[1], walls.apply(halfWidth(mob)));
                    if (mob.getLocation().distanceSquared(to) < 1.0E-4) {
                        stepping.remove(mob);
                    }
                }
            }
        };
    }

    /** A body's half width, the margin it keeps from walls. */
    static double halfWidth(Mob mob) {
        return Math.max(0.3, mob.getWidth() / 2);
    }

    /** {@code at} moved out of the walls ({@link Footprint#clear}). */
    static Location clear(List<Footprint.Box> walls, Location at) {
        if (walls.isEmpty()) {
            return at;
        }
        double[] clear = Footprint.clear(walls, at.getX(), at.getZ());
        Location out = at.clone();
        out.setX(clear[0]);
        out.setZ(clear[1]);
        return out;
    }

    /** Moves the mob up to {@code distance} blocks straight towards {@code to}. */
    static void step(Mob mob, Location to, double distance) {
        step(mob, to, distance, List.of());
    }

    /** Moves the mob up to {@code distance} blocks towards {@code to}, round the walls (never into one). */
    static void step(Mob mob, Location to, double distance, List<Footprint.Box> walls) {
        Location from = mob.getLocation();
        Vector delta = to.toVector().subtract(from.toVector());
        double length = delta.length();
        Location next = length <= distance || length < 1.0E-6 ? to.clone()
                : from.clone().add(delta.clone().multiply(distance / length));
        for (Footprint.Box wall : walls) {
            if (wall.contains(next.getX(), next.getZ())
                    || wall.crosses(from.getX(), from.getZ(), next.getX(), next.getZ())) {
                double[] slid = wall.step(from.getX(), from.getZ(), to.getX(), to.getZ(), distance);
                boolean free = true;
                for (Footprint.Box other : walls) {
                    free &= !other.contains(slid[0], slid[1]);
                }
                // between two walls with no way round: wait where it is
                next.setX(free ? slid[0] : from.getX());
                next.setZ(free ? slid[1] : from.getZ());
                break;
            }
        }
        Vector flat = delta.setY(0);
        if (flat.lengthSquared() > 1.0E-6) {
            next.setDirection(flat);
        } else {
            next.setYaw(from.getYaw());
            next.setPitch(0);
        }
        mob.teleport(next);
    }
}
