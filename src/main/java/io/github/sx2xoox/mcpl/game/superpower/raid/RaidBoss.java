package io.github.sx2xoox.mcpl.game.superpower.raid;

import java.util.Objects;

import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.entity.EntityDamageEvent;

/**
 * A raid boss for one boss wave. A new instance is created per fight.
 *
 * <h2>Lifecycle</h2>
 * <ol>
 *     <li>{@link #bind} by the framework, then {@link #spawn}: create the body (register it with
 *     {@link RaidContext#registerHostile}), decorations and the entrance show; return the main body whose health is
 *     the boss health (the framework shows it on the boss bar).</li>
 *     <li>{@link #tick(long)} every tick while the main body is alive: run attack patterns here. Every attack must be
 *     telegraphed (ground markers, wind-up sounds) so players can dodge it; damage players only through
 *     {@link RaidContext#hurtPlayer}.</li>
 *     <li>{@link #onPhase(int)} once when health first drops below 66% (phase 2) and 33% (phase 3).</li>
 *     <li>{@link #onDeath()} when the main body dies (a death show is welcome), then {@link #cleanup()} always,
 *     also when the raid ends early.</li>
 * </ol>
 *
 * <p>Use the context for every task, entity and block so nothing outlives the fight. All methods run on the main
 * thread; exceptions are caught and logged by the framework.
 */
public abstract class RaidBoss {

    private RaidContext ctx;

    /** Called by the framework exactly once, before {@link #spawn}. */
    public final void bind(RaidContext ctx) {
        if (this.ctx != null) {
            throw new IllegalStateException("Boss already bound");
        }
        this.ctx = Objects.requireNonNull(ctx, "ctx");
    }

    protected final RaidContext ctx() {
        return ctx;
    }

    public abstract BossInfo info();

    /**
     * Spawns the boss at {@code at} (a safe spot inside the arena).
     *
     * @param healthMultiplier scaling for max health (player count and difficulty already applied)
     * @return the main body; the boss is defeated when it dies
     */
    public abstract LivingEntity spawn(Location at, double healthMultiplier);

    /** @param bossTick ticks since the boss spawned */
    public abstract void tick(long bossTick);

    /** Phase 2 at 66% health, phase 3 at 33% (each called once, in order). */
    public void onPhase(int phase) {
    }

    /** The main body takes damage (may modify or cancel, e.g. for shields). */
    public void onDamaged(EntityDamageEvent event) {
    }

    public void onDeath() {
    }

    /** Release anything not created through the context. Always called once at the end. */
    public void cleanup() {
    }
}
