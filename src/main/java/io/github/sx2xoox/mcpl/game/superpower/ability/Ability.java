package io.github.sx2xoox.mcpl.game.superpower.ability;

import java.util.Objects;

import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ProjectileHitEvent;

/**
 * One player's superpower for one match. A new instance is created per player per match (via the factory
 * registered in {@link AbilityRegistry}), so instance fields are the natural place for per-player state.
 *
 * <h2>Lifecycle</h2>
 * <ol>
 *     <li>{@link #bind} — done by the framework right after construction.</li>
 *     <li>{@link #onGrant()} — once, when the fight begins. Apply passives here.</li>
 *     <li>While the owner is alive and the fight is running: {@link #onTick(long)} every tick, the event hooks
 *     below, and {@link #onActive()} / {@link #onUltimate()} when the owner presses the keys and the skill is
 *     ready.</li>
 *     <li>{@link #onRevoke()} — once, when the owner is eliminated, leaves, or the match ends. Undo anything that
 *     is not managed by {@link AbilityContext} (for example players hidden with {@code hidePlayer}).</li>
 * </ol>
 *
 * <p>Everything created through the context (tasks, tracked entities, summons, tagged projectiles, modifiers) is
 * cleaned up by the framework automatically. Prefer the context helpers over raw Bukkit scheduling.
 *
 * <h2>Threading</h2>
 * All methods are called on the server main thread.
 */
public abstract class Ability {

    private Player player;
    private AbilityContext ctx;
    private AbilityParams params;

    /** Called by the framework exactly once, before any other method. */
    public final void bind(Player player, AbilityContext ctx, AbilityParams params) {
        if (this.player != null) {
            throw new IllegalStateException("Ability already bound");
        }
        this.player = Objects.requireNonNull(player, "player");
        this.ctx = Objects.requireNonNull(ctx, "ctx");
        this.params = Objects.requireNonNull(params, "params");
    }

    /** The static description of this ability type. Must return the same instance the type was registered with. */
    public abstract AbilityInfo info();

    /** The owner of this ability. */
    protected final Player player() {
        return player;
    }

    protected final AbilityContext ctx() {
        return ctx;
    }

    protected final AbilityParams params() {
        return params;
    }

    /** The owner, for framework code. */
    public final Player owner() {
        return player;
    }

    // ------------------------------------------------------------------ lifecycle

    /** The fight begins: apply passive effects (attribute modifiers, items, ...). */
    public void onGrant() {
    }

    /** The owner is eliminated or the match ends: undo anything the context does not clean up. */
    public void onRevoke() {
    }

    /**
     * Called every server tick while the owner is alive and the fight is running. Throttle expensive work, e.g.
     * {@code if (tick % 10 != 0) return;}.
     *
     * @param tick ticks since the fight began
     */
    public void onTick(long tick) {
    }

    // ------------------------------------------------------------------ skills

    /**
     * Right-click with the ability item, only called when the active cooldown is ready.
     * Return {@link ActivationResult#fail(String)} (no cooldown is consumed) if it could not be used, for example
     * when there is no target.
     */
    public abstract ActivationResult onActive();

    /**
     * F key (swap hands), only called when the ultimate gauge is full.
     * Return {@link ActivationResult#fail(String)} to keep the gauge.
     */
    public abstract ActivationResult onUltimate();

    /**
     * Multiplier applied to this ability's active cooldown, e.g. {@code 0.85} for 15% cooldown reduction.
     * Read every time a cooldown starts.
     */
    public double cooldownMultiplier() {
        return 1.0;
    }

    // ------------------------------------------------------------------ event hooks (owner only)

    /**
     * The owner damages an enemy: melee, a projectile shot by the owner, or ability damage dealt through
     * {@link AbilityContext#damage}. The damage may be modified with {@code event.setDamage(...)} or cancelled.
     * Friendly fire has already been cancelled before this is called.
     *
     * <p>Do not call {@link AbilityContext#damage} from here unconditionally: it fires this hook again. Guard
     * with {@link AbilityContext#isAbilityDamage()}.
     */
    public void onDealDamage(EntityDamageByEntityEvent event, LivingEntity victim) {
    }

    /**
     * The owner takes damage of any cause. May modify or cancel the event. To survive a lethal hit, check
     * {@code event.getFinalDamage() >= player().getHealth()} and cancel.
     */
    public void onTakeDamage(EntityDamageEvent event) {
    }

    /** A projectile tagged by this owner via {@link AbilityContext#tagProjectile} hit something. */
    public void onProjectileHit(ProjectileHitEvent event, String tag) {
    }

    /** The owner was credited with eliminating {@code victim}. */
    public void onKill(Player victim) {
    }
}
