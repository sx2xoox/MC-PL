package io.github.sx2xoox.mcpl.game.superpower.abilities.kit;

import java.util.concurrent.ThreadLocalRandom;

import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityContext;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * Particle shapes for ability visuals, all scaled by {@link AbilityContext#effectScale()}.
 *
 * <p>Every shape spawns single particles (count 1, no spread) at computed points, so shapes stay crisp. Counts
 * passed in are the density at scale 1.0; {@link #n(AbilityContext, int)} applies the configured scale (never below
 * one point). {@code force} makes a particle visible from far away (up to 512 blocks) — use it for telegraphs and
 * ultimates that spectators should see, not for every spark.
 *
 * <p>Art direction lives in {@code /home/user/agent-notes/vfx-direction.md} during development: stage skills as
 * telegraph → action → impact → afterglow, layer a bright core, a palette-colored body and a themed accent.
 */
public final class Vfx {

    private static final double TAU = Math.PI * 2;
    private static final double GOLDEN_ANGLE = Math.PI * (3 - Math.sqrt(5));

    private Vfx() {
    }

    // ------------------------------------------------------------------ palette

    /** A small color palette for one ability: a bright core, the main body color and a dark edge. */
    public record Palette(Color core, Color body, Color edge) {

        public static Palette of(int core, int body, int edge) {
            return new Palette(Color.fromRGB(core), Color.fromRGB(body), Color.fromRGB(edge));
        }

        /** Dust in the body color. */
        public Particle.DustOptions dust(float size) {
            return new Particle.DustOptions(body, size);
        }

        /** Dust fading from the core to the body color. */
        public Particle.DustTransition glow(float size) {
            return new Particle.DustTransition(core, body, size);
        }

        /** Dust fading from the body to the edge color. */
        public Particle.DustTransition fade(float size) {
            return new Particle.DustTransition(body, edge, size);
        }
    }

    // ------------------------------------------------------------------ primitives

    /** {@code count} scaled by the configured effect scale, at least 1. */
    public static int n(AbilityContext ctx, int count) {
        return Math.max(1, (int) Math.round(count * ctx.effectScale()));
    }

    /** One particle at {@code at}. */
    public static <T> void dot(Location at, Particle particle, @Nullable T data, boolean force) {
        at.getWorld().spawnParticle(particle, at.getX(), at.getY(), at.getZ(), 1, 0, 0, 0, 0, data, force);
    }

    /** {@code count} (scaled) particles spread randomly in a box of half-size {@code spread} around {@code at}. */
    public static <T> void cloud(AbilityContext ctx, Location at, Particle particle, int count, double spread,
                                 double speed, @Nullable T data, boolean force) {
        at.getWorld().spawnParticle(particle, at, n(ctx, count), spread, spread, spread, speed, data, force);
    }

    /**
     * A colored mote that flies from {@code from} to {@code to} over {@code ticks} ticks ({@link Particle#TRAIL}).
     * Great for beams, drains, pulls and "energy flows into" effects.
     */
    public static void trail(Location from, Location to, Color color, int ticks, boolean force) {
        from.getWorld().spawnParticle(Particle.TRAIL, from.getX(), from.getY(), from.getZ(), 1, 0, 0, 0, 0,
                new Particle.Trail(to, color, Math.max(1, ticks)), force);
    }

    // ------------------------------------------------------------------ shapes

    /**
     * A horizontal ring at {@code center}'s height.
     *
     * @param points density at scale 1.0
     * @param phase  angle (radians) of the first point, so rings can turn over time
     */
    public static <T> void ring(AbilityContext ctx, Location center, double radius, int points, double phase,
                                Particle particle, @Nullable T data, boolean force) {
        int count = n(ctx, points);
        for (int i = 0; i < count; i++) {
            double angle = phase + i * (TAU / count);
            spawn(center.getWorld(), particle, center.getX() + Math.cos(angle) * radius, center.getY(),
                    center.getZ() + Math.sin(angle) * radius, data, force);
        }
    }

    /** Points evenly spread on a sphere surface (fibonacci lattice). */
    public static <T> void sphere(AbilityContext ctx, Location center, double radius, int points, Particle particle,
                                  @Nullable T data, boolean force) {
        int count = n(ctx, points);
        for (int i = 0; i < count; i++) {
            double y = 1 - (i + 0.5) * 2.0 / count;
            double r = Math.sqrt(Math.max(0, 1 - y * y));
            double angle = i * GOLDEN_ANGLE;
            spawn(center.getWorld(), particle, center.getX() + Math.cos(angle) * r * radius,
                    center.getY() + y * radius, center.getZ() + Math.sin(angle) * r * radius, data, force);
        }
    }

    /**
     * A vertical helix rising from {@code base}.
     *
     * @param turns  full turns over the height
     * @param strands number of intertwined strands (1 = spiral, 2 = double helix)
     */
    public static <T> void helix(AbilityContext ctx, Location base, double radius, double height, double turns,
                                 int strands, int pointsPerStrand, double phase, Particle particle,
                                 @Nullable T data, boolean force) {
        int count = n(ctx, pointsPerStrand);
        for (int s = 0; s < strands; s++) {
            double offset = phase + s * TAU / strands;
            for (int i = 0; i < count; i++) {
                double t = (double) i / Math.max(1, count - 1);
                double angle = offset + t * turns * TAU;
                spawn(base.getWorld(), particle, base.getX() + Math.cos(angle) * radius, base.getY() + t * height,
                        base.getZ() + Math.sin(angle) * radius, data, force);
            }
        }
    }

    /** A straight line of points from {@code from} to {@code to}, {@code perBlock} points per block (scaled). */
    public static <T> void line(AbilityContext ctx, Location from, Location to, double perBlock, Particle particle,
                                @Nullable T data, boolean force) {
        Vector delta = to.toVector().subtract(from.toVector());
        double length = delta.length();
        int count = Math.max(2, (int) Math.round(length * perBlock * ctx.effectScale()));
        for (int i = 0; i <= count; i++) {
            double t = (double) i / count;
            spawn(from.getWorld(), particle, from.getX() + delta.getX() * t, from.getY() + delta.getY() * t,
                    from.getZ() + delta.getZ() * t, data, force);
        }
    }

    /**
     * A horizontal arc (part of a ring) centred on {@code yawDegrees}, e.g. a sword slash or a cone edge.
     *
     * @param widthDegrees total angular width of the arc
     */
    public static <T> void arc(AbilityContext ctx, Location center, double radius, double yawDegrees,
                               double widthDegrees, int points, Particle particle, @Nullable T data, boolean force) {
        int count = n(ctx, points);
        double start = Math.toRadians(yawDegrees - widthDegrees / 2) + Math.PI / 2;
        double step = Math.toRadians(widthDegrees) / Math.max(1, count - 1);
        for (int i = 0; i < count; i++) {
            double angle = start + i * step;
            spawn(center.getWorld(), particle, center.getX() + Math.cos(angle) * radius, center.getY(),
                    center.getZ() + Math.sin(angle) * radius, data, force);
        }
    }

    /**
     * An impact burst: a few bright core particles plus a palette-colored puff and outward sparks.
     *
     * @param size roughly the radius of the burst in blocks
     */
    public static void burst(AbilityContext ctx, Location at, Palette palette, double size, Particle accent,
                             boolean force) {
        World world = at.getWorld();
        world.spawnParticle(Particle.FLASH, at, 1, 0, 0, 0, 0, palette.core(), force);
        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, at, n(ctx, 18), size * 0.45, size * 0.45, size * 0.45,
                0, palette.glow(1.4f), force);
        world.spawnParticle(Particle.DUST, at, n(ctx, 14), size * 0.6, size * 0.6, size * 0.6, 0,
                palette.dust(1.0f), force);
        world.spawnParticle(accent, at, n(ctx, 12), size * 0.3, size * 0.3, size * 0.3, 0.12 * size, null, force);
    }

    /**
     * An expanding ground shockwave: rings growing from {@code startRadius} to {@code endRadius} over
     * {@code ticks} ticks, owner-scoped (stops when the owner dies).
     */
    public static <T> void shockwave(AbilityContext ctx, Player owner, Location center, double startRadius,
                                     double endRadius, long ticks, Particle particle, @Nullable T data,
                                     boolean force) {
        Location at = center.clone();
        long runs = Math.max(1, ticks);
        Loop.repeat(ctx, owner, 0, 1, runs, i -> {
            double t = (double) (i + 1) / runs;
            double radius = startRadius + (endRadius - startRadius) * t;
            int points = (int) Math.max(8, Math.round(radius * 7));
            ring(ctx, at, radius, points, i * 0.15, particle, data, force);
            return true;
        });
    }

    /** A random point within {@code radius} of {@code center} on the horizontal plane. */
    public static Location scatter(Location center, double radius) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double angle = random.nextDouble(TAU);
        double r = Math.sqrt(random.nextDouble()) * radius;
        return center.clone().add(Math.cos(angle) * r, 0, Math.sin(angle) * r);
    }

    private static <T> void spawn(World world, Particle particle, double x, double y, double z, @Nullable T data,
                                  boolean force) {
        world.spawnParticle(particle, x, y, z, 1, 0, 0, 0, 0, data, force);
    }
}
