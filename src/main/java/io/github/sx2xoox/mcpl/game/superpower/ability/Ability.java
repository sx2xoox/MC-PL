package io.github.sx2xoox.mcpl.game.superpower.ability;

import java.util.Objects;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.jspecify.annotations.Nullable;

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
     *
     * <p><b>An ultimate never refills its own gauge.</b> Damage the owner deals as part of the ultimate does not
     * charge the owner's gauge: damage dealt inside this method, in tasks it schedules through
     * {@link AbilityContext#runLater}/{@link AbilityContext#runTimer} (and the tasks those schedule), in hooks
     * triggered by those hits, and by entities it creates through {@link AbilityContext#track},
     * {@link AbilityContext#summon} or {@link AbilityContext#tagProjectile} (for their whole lifetime). In addition,
     * after a successful ultimate the owner's gauge earns nothing at all (damage dealt or taken, time) for
     * {@code ultimate.recharge-delay-seconds}. Gauge an ability adds for time or hits through
     * {@link AbilityContext#addEarnedGauge} follows both rules; {@link AbilityContext#addGauge} is an explicit grant
     * and never blocked. So schedule an ultimate's delayed parts through the context (never the raw Bukkit scheduler).
     */
    public abstract ActivationResult onUltimate();

    /**
     * F key (swap hands) during the fight, before the match uses it for the ultimate, whatever the gauge (also while
     * silenced). Return true to take the key for the ability's own use, typically to end its running ultimate early:
     * the match then neither calls {@link #onUltimate} nor reports the key as denied (no "gauge not full" message or
     * sound). The default returns false: the key casts the ultimate as usual.
     */
    public boolean onUltimateKey() {
        return false;
    }

    /**
     * Multiplier applied to this ability's active cooldown, e.g. {@code 0.85} for 15% cooldown reduction.
     * Read every time a cooldown starts.
     */
    public double cooldownMultiplier() {
        return 1.0;
    }

    /**
     * A name shown instead of the owner's own name in the match's public lines while the fight runs: the kill feed,
     * the elimination titles and the ultimate announcements (오디세우스's "아무도"). Null (the default) for the owner's
     * name. Read every time such a line is shown, so it may change; never shown in the results.
     */
    public @Nullable Component publicName() {
        return null;
    }

    // ------------------------------------------------------------------ event hooks (owner only)

    /**
     * The owner damages an enemy: melee, a projectile shot by the owner, ability damage dealt through
     * {@link AbilityContext#damage}, or a hit by one of the owner's summons ({@link AbilityContext#summon}; then
     * {@code event.getDamager()} is the summon, so check the damager if an effect is meant for the owner's own
     * hits only). The damage may be modified with {@code event.setDamage(...)} or cancelled. Friendly fire has
     * already been cancelled before this is called.
     *
     * <p>The victim's {@link #onTakeDamage} runs <b>after</b> this and may still cancel the hit (a dodge, a shield,
     * a revive). Use this hook to change the damage; put on-hit side effects (poison, fire, slows, knockback,
     * follow-up hits) in {@link #onDamageDealt}, which only runs for hits that landed.
     *
     * <p>Do not call {@link AbilityContext#damage} from here unconditionally: it fires this hook again. Guard
     * with {@link AbilityContext#isAbilityDamage()}.
     */
    public void onDealDamage(EntityDamageByEntityEvent event, LivingEntity victim) {
    }

    /**
     * A hit routed to {@link #onDealDamage} landed: nothing cancelled it, including the victim's
     * {@link #onTakeDamage}. Called with the final damage, before it is applied (the victim may die right after).
     * The place for on-hit side effects. Read-only: do not modify or cancel the event. Deal follow-up damage
     * later with {@link AbilityContext#runLater}, re-checking that the victim is still valid and alive.
     */
    public void onDamageDealt(EntityDamageByEntityEvent event, LivingEntity victim) {
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
