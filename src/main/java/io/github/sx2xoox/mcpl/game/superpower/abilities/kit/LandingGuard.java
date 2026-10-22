package io.github.sx2xoox.mcpl.game.superpower.abilities.kit;

import org.bukkit.entity.Entity;
import org.bukkit.event.entity.EntityDamageEvent;

/**
 * Protects one landing from fall damage, for example after a teleport into mid-air or a launch.
 *
 * <p>{@link #arm} starts the protection for at most a number of ticks. {@link #update}, called from
 * {@code onTick} (every tick or every few ticks), ends it once the entity has stood on the ground (or swum) for
 * {@link #SETTLE_TICKS}: the server's ground check looks slightly below the feet and movement packets arrive late, so
 * the guard must not end the instant the ground comes in reach. {@link #absorb}, called from {@code onTakeDamage},
 * cancels the first fall damage while the guard is up and ends it.
 */
public final class LandingGuard {

    /** How long the entity must have been on the ground before the protection ends. */
    public static final long SETTLE_TICKS = 10;

    private long until = -1;
    private long groundedSince = -1;

    /** Protects the next landing for at most {@code maxTicks} from {@code now}. */
    public void arm(long now, long maxTicks) {
        until = now + Math.max(0, maxTicks);
        groundedSince = -1;
    }

    /** {@link #arm}s only if {@code entity} is neither on the ground nor in water. Returns whether it armed. */
    public boolean armIfAirborne(Entity entity, long now, long maxTicks) {
        if (Motion.isGrounded(entity) || entity.isInWater()) {
            return false;
        }
        arm(now, maxTicks);
        return true;
    }

    public boolean isArmed() {
        return until >= 0;
    }

    public void disarm() {
        until = -1;
        groundedSince = -1;
    }

    /** Ends the protection when it has expired or {@code entity} has settled on the ground. */
    public void update(Entity entity, long now) {
        if (until < 0) {
            return;
        }
        if (now > until) {
            disarm();
            return;
        }
        if (!Motion.isGrounded(entity) && !entity.isInWater()) {
            groundedSince = -1;
        } else if (groundedSince < 0) {
            groundedSince = now;
        } else if (now - groundedSince >= SETTLE_TICKS) {
            disarm();
        }
    }

    /**
     * Cancels {@code event} if it is fall damage and the guard is up. Any fall damage while armed ends the guard,
     * even an expired one.
     *
     * @return whether the event was cancelled
     */
    public boolean absorb(EntityDamageEvent event, long now) {
        if (until < 0 || event.isCancelled() || !Combat.isFall(event)) {
            return false;
        }
        boolean active = now <= until;
        disarm();
        if (active) {
            event.setCancelled(true);
        }
        return active;
    }
}
