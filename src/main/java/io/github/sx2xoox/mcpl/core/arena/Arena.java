package io.github.sx2xoox.mcpl.core.arena;

import java.util.Objects;
import java.util.Random;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;

/**
 * The circular play area of a match: a center and a horizontal radius in one world. All games share the arena from
 * {@code config.yml} ({@code /게임 경기장 설정}); without one, the arena is centered on whoever started the match (the
 * world spawn for the console or rotation).
 *
 * @param world  the arena world
 * @param center the center (its world is {@code world}); a copy is returned by the accessor
 * @param radius horizontal radius in blocks
 */
public record Arena(World world, Location center, int radius) {

    public Arena {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(center, "center");
        if (radius < 1) {
            throw new IllegalArgumentException("radius must be positive");
        }
        center = center.clone();
        center.setWorld(world);
    }

    /** An arena around {@code center} (which must have a world). */
    public static Arena around(Location center, int radius) {
        World world = Objects.requireNonNull(center.getWorld(), "center has no world");
        return new Arena(world, center, radius);
    }

    /** A copy of the center. */
    @Override
    public Location center() {
        return center.clone();
    }

    public double centerX() {
        return center.getX();
    }

    public double centerZ() {
        return center.getZ();
    }

    /** Diameter in blocks, handy as an initial border size. */
    public double diameter() {
        return radius * 2.0;
    }

    /** True if {@code location} is in the arena world within the radius (horizontally). */
    public boolean contains(Location location) {
        return world.equals(location.getWorld()) && horizontalDistanceSquared(location) <= (double) radius * radius;
    }

    public boolean contains(Entity entity) {
        return contains(entity.getLocation());
    }

    /** Horizontal distance from the center (ignores the world). */
    public double horizontalDistance(Location location) {
        return Math.sqrt(horizontalDistanceSquared(location));
    }

    private double horizontalDistanceSquared(Location location) {
        double dx = location.getX() - center.getX();
        double dz = location.getZ() - center.getZ();
        return dx * dx + dz * dz;
    }

    /** A uniformly random point inside {@code fraction * radius} at the center's height. */
    public Location randomPoint(Random random, double fraction) {
        double angle = random.nextDouble() * Math.PI * 2;
        double distance = Math.sqrt(random.nextDouble()) * radius * Math.max(0, Math.min(1, fraction));
        return new Location(world, center.getX() + Math.cos(angle) * distance, center.getY(),
                center.getZ() + Math.sin(angle) * distance);
    }

    /** {@code location} moved horizontally onto the arena edge if it is outside (same world assumed). */
    public Location clampInside(Location location) {
        double distance = horizontalDistance(location);
        if (distance <= radius) {
            return location.clone();
        }
        double scale = (radius - 0.5) / distance;
        Location clamped = location.clone();
        clamped.setWorld(world);
        clamped.setX(center.getX() + (location.getX() - center.getX()) * scale);
        clamped.setZ(center.getZ() + (location.getZ() - center.getZ()) * scale);
        return clamped;
    }
}
