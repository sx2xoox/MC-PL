package io.github.sx2xoox.mcpl.game.superpower.support.world;

/**
 * A test entity that remembers the strength of its last hit ({@code LivingEntity#getLastDamage}, unimplemented in
 * MockBukkit), which {@link DamageSimulation} needs for invulnerability frames.
 */
public interface Hurtable {

    double lastHurt();

    void lastHurt(double amount);
}
