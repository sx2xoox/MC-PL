package io.github.sx2xoox.mcpl.game.siege.creature;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.util.Vector;

/**
 * Warnings on the ground before a creature's skill lands (docs/siege-v2.md §4): an outline of the area it will hit and
 * a second line growing from the middle to the outline as the moment comes (when they meet, it lands). Pastel dust
 * particles, seen by everyone, drawn again every couple of ticks by the skill's owner.
 */
public final class Telegraphs {

    /** A blow that hurts. */
    public static final Color DANGER = Color.fromRGB(0xFF, 0x50, 0x64);
    /** Something sticky or slowing (the jelly's puddle). */
    public static final Color STICKY = Color.fromRGB(0xFF, 0x8C, 0xBE);
    /** A shove (the dragon's gust). */
    public static final Color PUSH = Color.fromRGB(0x9E, 0xE6, 0xFF);

    /** Blocks between two dots of an outline. */
    private static final double DOT = 0.45;
    /** Height of the marks above the ground the area stands on. */
    private static final double LIFT = 0.15;

    private Telegraphs() {
    }

    /**
     * A circle of {@code radius} round {@code centre} (on its height), filled to {@code progress} (0 = just warned,
     * 1 = landing now).
     */
    public static void circle(Location centre, double radius, double progress, Color colour) {
        World world = centre.getWorld();
        ring(world, centre, radius, colour, 1.2f);
        double inner = radius * Math.clamp(progress, 0, 1);
        if (inner > 0.2) {
            ring(world, centre, inner, colour, 0.8f);
        }
    }

    /**
     * A cone from {@code apex} along {@code direction} (flattened), {@code angle} degrees wide and {@code range} long,
     * filled to {@code progress}: its two edges, its far arc and an arc moving out to it.
     */
    public static void cone(Location apex, Vector direction, double angle, double range, double progress,
            Color colour) {
        World world = apex.getWorld();
        Vector flat = direction.clone().setY(0);
        if (flat.lengthSquared() < 1.0E-6) {
            return;
        }
        double heading = Math.atan2(flat.getZ(), flat.getX());
        double half = Math.toRadians(angle / 2);
        for (double side : new double[] {-half, half}) {
            double a = heading + side;
            for (double d = 0.6; d <= range; d += DOT) {
                dot(world, apex.getX() + Math.cos(a) * d, apex.getY(), apex.getZ() + Math.sin(a) * d, colour, 1.2f);
            }
        }
        arc(world, apex, heading, half, range, colour, 1.2f);
        double reach = range * Math.clamp(progress, 0, 1);
        if (reach > 0.6) {
            arc(world, apex, heading, half, reach, colour, 0.8f);
        }
    }

    /** Whether {@code at} is inside a cone ({@link #cone}), horizontally. */
    public static boolean inCone(Location apex, Vector direction, double angle, double range, Location at) {
        Vector to = at.toVector().subtract(apex.toVector()).setY(0);
        Vector flat = direction.clone().setY(0);
        double distance = to.length();
        if (distance > range || flat.lengthSquared() < 1.0E-6) {
            return false;
        }
        if (distance < 0.8) {
            return true; // right at its mouth
        }
        return to.angle(flat) <= Math.toRadians(angle / 2) + 1.0E-6;
    }

    /** A burst as an area lands: dots all over it. */
    public static void burst(Location centre, double radius, Particle particle) {
        int count = (int) Math.min(40, 6 + radius * radius * 2);
        centre.getWorld().spawnParticle(particle, centre.clone().add(0, 0.4, 0), count, radius / 2, 0.2, radius / 2,
                0.02);
    }

    private static void ring(World world, Location centre, double radius, Color colour, float size) {
        int dots = (int) Math.max(10, Math.ceil(2 * Math.PI * radius / DOT));
        for (int i = 0; i < dots; i++) {
            double a = 2 * Math.PI * i / dots;
            dot(world, centre.getX() + Math.cos(a) * radius, centre.getY(), centre.getZ() + Math.sin(a) * radius,
                    colour, size);
        }
    }

    private static void arc(World world, Location apex, double heading, double half, double radius, Color colour,
            float size) {
        int dots = (int) Math.max(3, Math.ceil(2 * half * radius / DOT));
        for (int i = 0; i <= dots; i++) {
            double a = heading - half + 2 * half * i / dots;
            dot(world, apex.getX() + Math.cos(a) * radius, apex.getY(), apex.getZ() + Math.sin(a) * radius, colour,
                    size);
        }
    }

    private static void dot(World world, double x, double y, double z, Color colour, float size) {
        world.spawnParticle(Particle.DUST, x, y + LIFT, z, 1, 0, 0, 0, 0, new Particle.DustOptions(colour, size));
    }
}
