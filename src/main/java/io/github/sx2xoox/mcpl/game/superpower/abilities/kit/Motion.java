package io.github.sx2xoox.mcpl.game.superpower.abilities.kit;

import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

/** Velocities, knockback and the server-side ground check. */
public final class Motion {

    /** Vanilla entity gravity per tick. */
    public static final double GRAVITY = 0.08;
    /** Vanilla vertical drag per tick. */
    public static final double DRAG = 0.98;
    /** Upper bound of {@link #launchSpeed}. */
    public static final double MAX_LAUNCH = 2.5;
    /** Knockback is skipped below this share (an almost fully resistant target is not stopped in its tracks). */
    private static final double MIN_KNOCKBACK = 0.01;

    private Motion() {
    }

    /**
     * Caps {@code velocity} to {@code max} blocks per tick, scaling it in place, and returns it. A non-finite
     * velocity becomes zero.
     */
    public static Vector clamp(Vector velocity, double max) {
        double length = velocity.length();
        if (!Double.isFinite(length)) {
            return new Vector();
        }
        return length > max ? velocity.multiply(max / length) : velocity;
    }

    /** 1 minus the target's knockback resistance (clamped to 0..1), like vanilla knockback. */
    public static double knockbackFactor(LivingEntity target) {
        AttributeInstance resistance = target.getAttribute(Attribute.KNOCKBACK_RESISTANCE);
        return resistance == null ? 1.0 : 1.0 - Math.clamp(resistance.getValue(), 0.0, 1.0);
    }

    /**
     * Replaces the target's velocity with {@code velocity} reduced by its knockback resistance and capped to
     * {@code maxSpeed}. Nothing happens to (almost) fully resistant targets.
     *
     * @return whether the target was pushed
     */
    public static boolean push(LivingEntity target, Vector velocity, double maxSpeed) {
        double factor = knockbackFactor(target);
        if (factor <= MIN_KNOCKBACK) {
            return false;
        }
        target.setVelocity(clamp(velocity.clone().multiply(factor), maxSpeed));
        return true;
    }

    /**
     * The initial upward speed (blocks per tick) that makes an entity rise {@code height} blocks under vanilla
     * gravity and drag, at most {@link #MAX_LAUNCH}; 0 for a height of 0 or less.
     */
    public static double launchSpeed(double height) {
        if (height <= 0) {
            return 0;
        }
        double low = 0;
        double high = MAX_LAUNCH;
        for (int i = 0; i < 30; i++) {
            double mid = (low + high) / 2;
            if (apex(mid) < height) {
                low = mid;
            } else {
                high = mid;
            }
        }
        return high;
    }

    /** How high an entity launched upwards with {@code speed} rises under vanilla gravity and drag. */
    private static double apex(double speed) {
        double y = 0;
        double v = speed;
        for (int i = 0; i < 400 && v > 0; i++) {
            y += v;
            v = (v - GRAVITY) * DRAG;
        }
        return y;
    }

    /** {@code vector} flattened onto the horizontal plane and normalized, or zero if it has no horizontal part. */
    public static Vector horizontal(Vector vector) {
        Vector flat = new Vector(vector.getX(), 0, vector.getZ());
        return flat.lengthSquared() < 1.0E-8 ? new Vector() : flat.normalize();
    }

    /** The horizontal unit vector a location's yaw faces (independent of its pitch). */
    public static Vector horizontalFacing(Location at) {
        double yaw = Math.toRadians(at.getYaw());
        return new Vector(-Math.sin(yaw), 0, Math.cos(yaw));
    }

    /**
     * True when a solid surface is right below the entity's feet. This is a server-side check: a player's own
     * on-ground flag is client-controlled (and deprecated). Water does not count; add {@code entity.isInWater()}
     * where swimming should count as landed.
     */
    public static boolean isGrounded(Entity entity) {
        BoundingBox box = entity.getBoundingBox();
        BoundingBox below = new BoundingBox(box.getMinX() + 0.05, box.getMinY() - 0.15, box.getMinZ() + 0.05,
                box.getMaxX() - 0.05, box.getMinY() - 0.01, box.getMaxZ() - 0.05);
        return entity.getWorld().hasCollisionsIn(below);
    }
}
