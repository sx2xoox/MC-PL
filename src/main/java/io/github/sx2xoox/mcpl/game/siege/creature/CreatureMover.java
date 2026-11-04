package io.github.sx2xoox.mcpl.game.siege.creature;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.DoubleFunction;

import io.github.sx2xoox.mcpl.game.siege.tower.Footprint;
import org.bukkit.Location;
import org.bukkit.entity.AbstractCubeMob;
import org.bukkit.entity.Mob;
import org.bukkit.util.Vector;

/**
 * Walks a creature somewhere. The default ({@link #pathfinder()}) uses Paper's
 * {@link com.destroystokyo.paper.entity.Pathfinder}; where the server has none (a test server) it steps the creature
 * straight towards the goal every {@link #tick}, so the rules can be tested.
 *
 * <p>Slimes (the 젤리) cannot be walked by the pathfinder: vanilla's cube mob move control ignores where the path
 * leads and hops along a heading only the slime's own (removed) goals set, so a pathed slime bounces off in whatever
 * direction it spawned facing. They are {@link #hop hopped} at the goal instead (along the server's path, {@link
 * #waypoint}), with their move control
 * switched off ({@link Mob#setAware}).
 *
 * <p>Buildings ({@link #pathfinder(DoubleFunction)}'s walls, the 공성전 structures' barrier squares): a goal inside one is
 * moved out of it (the pathfinder cannot reach it, a stepped creature would walk into it) and a stepped creature goes
 * round them ({@link Footprint.Box#step}) instead of through.
 */
public interface CreatureMover {

    /** Blocks a stepped creature moves per tick at speed 1 (about a zombie's walk). */
    double STEP_PER_TICK = 0.2;
    /** Ticks a hopping creature rests on the ground between hops. */
    int HOP_REST_TICKS = 4;
    /** Upward speed of a hop (a vanilla jump). */
    double HOP_LIFT = 0.42;
    /**
     * Horizontal blocks a hop launched at 1 block per tick covers before it lands (air drag 0.91 a tick over the
     * ~12 ticks of a jump).
     */
    double HOP_REACH = 7.4;

    /** Starts (or renews) walking to {@code to} at {@code speed} (1 = the mob's normal speed). */
    void moveTo(Mob mob, Location to, double speed);

    /** Stops walking. */
    void stop(Mob mob);

    /** Every tick for each driven creature. */
    default void tick(Mob mob) {
    }

    /** Paper's pathfinder (hops for slimes), stepping on servers without one. */
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
            /** Goals of hopping creatures (slimes): goal, speed, ticks until the next hop. */
            private final Map<Mob, Object[]> hopping = new WeakHashMap<>();

            @Override
            public void moveTo(Mob mob, Location wanted, double speed) {
                Location to = clear(walls.apply(halfWidth(mob)), wanted);
                try {
                    if (mob instanceof AbstractCubeMob) {
                        mob.getPathfinder().stopPathfinding(); // throws where the server has no pathfinder
                        mob.setAware(false);
                        Object[] goal = hopping.get(mob);
                        hopping.put(mob, new Object[] {to.clone(), speed, goal == null ? 0 : goal[2]});
                        return;
                    }
                    mob.getPathfinder().moveTo(to, speed);
                } catch (RuntimeException noPathfinder) {
                    stepping.put(mob, new Object[] {to.clone(), speed});
                }
            }

            @Override
            public void stop(Mob mob) {
                if (stepping.remove(mob) != null || hopping.remove(mob) != null) {
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
                Object[] hop = hopping.get(mob);
                if (hop != null && mob.isValid()) {
                    int wait = (int) hop[2];
                    if (wait > 0) {
                        hop[2] = wait - 1;
                    } else if (mob.isOnGround()) {
                        Location goal = (Location) hop[0];
                        if (hop(mob, goal, (double) hop[1], waypoint(mob, goal))) {
                            hop[2] = HOP_REST_TICKS;
                        } else {
                            hopping.remove(mob);
                        }
                    }
                }
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

    /**
     * Launches the mob into one hop towards {@code to} (facing it), far enough to keep up {@link #STEP_PER_TICK} ×
     * {@code speed} on average, never past the goal. False when it is already there.
     */
    static boolean hop(Mob mob, Location to, double speed) {
        return hop(mob, to, speed, to);
    }

    /**
     * {@link #hop(Mob, Location, double)} towards {@code toward} (the next corner of a path to {@code to}), as far as
     * the goal allows.
     */
    static boolean hop(Mob mob, Location to, double speed, Location toward) {
        Location from = mob.getLocation();
        double distance = Math.sqrt(Creatures.flatDistanceSquared(from, to));
        if (distance < 0.5) {
            return false;
        }
        Vector flat = toward.toVector().subtract(from.toVector()).setY(0);
        if (flat.lengthSquared() < 1.0E-4) {
            flat = to.toVector().subtract(from.toVector()).setY(0);
        }
        Creatures.face(mob, from.clone().add(flat));
        // one hop is ~12 ticks in the air plus the rest on the ground
        double perHop = STEP_PER_TICK * speed * (12 + HOP_REST_TICKS);
        double launch = Math.min(perHop, distance) / HOP_REACH;
        mob.setVelocity(flat.normalize().multiply(launch).setY(HOP_LIFT));
        return true;
    }

    /**
     * Where a hopping mob should head for to reach {@code to}: a point a couple of blocks along the server's path
     * (around walls and up steps), or the goal itself when there is no path.
     */
    static Location waypoint(Mob mob, Location to) {
        try {
            com.destroystokyo.paper.entity.Pathfinder.PathResult path = mob.getPathfinder().findPath(to);
            if (path != null) {
                List<Location> points = path.getPoints();
                Location from = mob.getLocation();
                for (int i = Math.min(points.size() - 1, 2); i >= 0; i--) {
                    Location point = points.get(i).clone().add(0.5, 0, 0.5);
                    if (Creatures.flatDistanceSquared(point, from) > 0.36) {
                        return point;
                    }
                }
            }
        } catch (RuntimeException noPathfinder) {
            // straight at it
        }
        return to;
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
