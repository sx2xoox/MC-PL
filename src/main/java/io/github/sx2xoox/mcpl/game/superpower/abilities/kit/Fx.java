package io.github.sx2xoox.mcpl.game.superpower.abilities.kit;

import java.util.concurrent.ThreadLocalRandom;

import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.jspecify.annotations.Nullable;

/** Sounds, particles and countdown text shared by the ability visuals. */
public final class Fx {

    private Fx() {
    }

    /** Plays {@code sound} at {@code at} for everyone nearby, in the players category. */
    public static void sound(Location at, Sound sound, float volume, float pitch) {
        at.getWorld().playSound(at, sound, SoundCategory.PLAYERS, volume, pitch);
    }

    /** A random offset in {@code [-amount, amount)}, to vary the pitch of repeated sounds. */
    public static float jitter(float amount) {
        return (ThreadLocalRandom.current().nextFloat() * 2 - 1) * amount;
    }

    /** The middle of the entity's hitbox, a natural anchor for hit particles. */
    public static Location body(Entity entity) {
        return entity.getLocation().add(0, entity.getHeight() * 0.5, 0);
    }

    /**
     * A horizontal circle of {@code points} single particles (no spread, no speed) at {@code center}'s height.
     *
     * @param phase angle (radians) of the first point, for rings that turn over time
     * @param data  the particle's data (dust options, ...), null for particles without data
     */
    public static <T> void ring(Location center, double radius, int points, double phase, Particle particle,
                                @Nullable T data) {
        World world = center.getWorld();
        for (int i = 0; i < points; i++) {
            double angle = phase + i * (Math.PI * 2 / points);
            world.spawnParticle(particle, center.getX() + Math.cos(angle) * radius, center.getY(),
                    center.getZ() + Math.sin(angle) * radius, 1, 0, 0, 0, 0, data);
        }
    }

    /** {@code ticks} as seconds with one decimal, rounded up ({@code 41 -> "2.1"}, {@code 40 -> "2"}). */
    public static String seconds(long ticks) {
        return AbilityInfo.formatNumber(Math.ceil(Math.max(0, ticks) / 2.0) / 10.0);
    }
}
