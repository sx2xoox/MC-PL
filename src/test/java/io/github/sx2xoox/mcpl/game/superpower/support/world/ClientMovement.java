package io.github.sx2xoox.mcpl.game.superpower.support.world;

import org.bukkit.util.Vector;

/**
 * A player double whose own movement (simulated by {@link WorldPhysics}, or a test walking it) changes its velocity
 * without the {@code PlayerVelocityEvent} a server-sent velocity fires: on a real server a player moves itself on its
 * client, and only velocities plugins and vanilla give it reach {@code PlayerVelocityEvent}.
 */
public interface ClientMovement {

    /** Sets the velocity as the client's own movement (no {@code PlayerVelocityEvent}). */
    void setClientVelocity(Vector velocity);
}
