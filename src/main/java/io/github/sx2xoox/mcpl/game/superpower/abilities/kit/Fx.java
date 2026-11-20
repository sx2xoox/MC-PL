package io.github.sx2xoox.mcpl.game.superpower.abilities.kit;

import java.util.concurrent.ThreadLocalRandom;

import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.jspecify.annotations.Nullable;

/** Sounds, particles, countdown text and the nausea screen warp shared by the ability visuals. */
public final class Fx {

    /** The client stops warping the view once this many or fewer ticks of nausea are left (see {@link #nausea}). */
    private static final int NAUSEA_CLIENT_FADE = 60;

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

    /**
     * Gives {@code entity} nausea (level I, with particles and icon) that keeps a real client's view warping for
     * {@code ticks}. The vanilla client only builds the nausea screen warp while the effect has MORE than 60 ticks
     * left; once 60 or fewer remain it fades the warp out within a second. So the effect is applied 60 ticks longer
     * than {@code ticks}: always use this instead of a bare {@link PotionEffectType#NAUSEA} effect, which never shows
     * at all when it is 3 s or shorter. The client also ramps the warp in slowly (about 7.5 s to full strength), so
     * a few seconds of nausea is a mild wobble, not a full spin. A longer nausea the entity already has is kept.
     * Does nothing when {@code ticks <= 0}.
     */
    public static void nausea(LivingEntity entity, long ticks) {
        if (ticks <= 0) {
            return;
        }
        int duration = (int) Math.min(Integer.MAX_VALUE, Math.min(ticks, Integer.MAX_VALUE) + NAUSEA_CLIENT_FADE);
        entity.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, duration, 0, false, true, true));
    }

    /** {@code ticks} as seconds with one decimal, rounded up ({@code 41 -> "2.1"}, {@code 40 -> "2"}). */
    public static String seconds(long ticks) {
        return AbilityInfo.formatNumber(Math.ceil(Math.max(0, ticks) / 2.0) / 10.0);
    }
}
