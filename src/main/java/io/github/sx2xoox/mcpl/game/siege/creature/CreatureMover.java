package io.github.sx2xoox.mcpl.game.siege.creature;

import java.util.Map;
import java.util.WeakHashMap;

import org.bukkit.Location;
import org.bukkit.entity.Mob;
import org.bukkit.util.Vector;

/**
 * Walks a creature somewhere. The default ({@link #pathfinder()}) uses Paper's
 * {@link com.destroystokyo.paper.entity.Pathfinder}; where the server has none (a test server) it steps the creature
 * straight towards the goal every {@link #tick}, so the rules can be tested.
 */
public interface CreatureMover {

    /** Blocks a stepped creature moves per tick at speed 1 (about a zombie's walk). */
    double STEP_PER_TICK = 0.2;

    /** Starts (or renews) walking to {@code to} at {@code speed} (1 = the mob's normal speed). */
    void moveTo(Mob mob, Location to, double speed);

    /** Stops walking. */
    void stop(Mob mob);

    /** Every tick for each driven creature. */
    default void tick(Mob mob) {
    }

    /** Paper's pathfinder, stepping on servers without one. */
    static CreatureMover pathfinder() {
        return new CreatureMover() {
            /** Goals of creatures the server cannot path for, with their speed. */
            private final Map<Mob, Object[]> stepping = new WeakHashMap<>();

            @Override
            public void moveTo(Mob mob, Location to, double speed) {
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
                    step(mob, to, STEP_PER_TICK * (double) goal[1]);
                    if (mob.getLocation().distanceSquared(to) < 1.0E-4) {
                        stepping.remove(mob);
                    }
                }
            }
        };
    }

    /** Moves the mob up to {@code distance} blocks straight towards {@code to}. */
    static void step(Mob mob, Location to, double distance) {
        Location from = mob.getLocation();
        Vector delta = to.toVector().subtract(from.toVector());
        double length = delta.length();
        Location next = length <= distance || length < 1.0E-6 ? to.clone()
                : from.clone().add(delta.clone().multiply(distance / length));
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
