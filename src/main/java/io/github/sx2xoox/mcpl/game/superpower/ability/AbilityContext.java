package io.github.sx2xoox.mcpl.game.superpower.ability;

import java.util.Collection;
import java.util.List;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.jspecify.annotations.Nullable;

/**
 * Services the superpower match offers to abilities. One context exists per match and is shared by all abilities.
 *
 * <p><b>Targeting rule:</b> abilities must only harm entities for which {@link #isEnemy} is true (or use
 * {@link #enemiesNear} / {@link #findTarget}, which already filter). This keeps team mode, spectators, the
 * owner and bystander mobs safe. Vanilla area effects that hit everyone (area effect clouds, real explosions,
 * lightning damage) should be avoided in favour of applying effects to enemies explicitly.
 *
 * <p><b>Cleanup rule:</b> resources created through the "owner-scoped" helpers are released when the owner's
 * ability is revoked (elimination, quit, match end). Match-scoped helpers live until they expire or the match
 * ends. Nothing created here outlives the match.
 */
public interface AbilityContext {

    Plugin plugin();

    AbilityRegistry registry();

    /** Effective params of any registered ability (used to run a borrowed ability). */
    AbilityParams paramsFor(String abilityId);

    /** Ticks since the fight began. */
    long currentTick();

    // ------------------------------------------------------------------ participants

    /** Participants that are still alive (not eliminated, online). */
    Collection<Player> alivePlayers();

    boolean isAlive(Player player);

    /**
     * True if {@code entity} is a living, valid target for {@code owner}: an alive participant on another team
     * (everyone else in free-for-all), or a summon owned by such a participant. False for the owner, allies,
     * allied summons, spectators, eliminated players and unrelated mobs.
     */
    boolean isEnemy(Player owner, Entity entity);

    /** True for alive teammates of {@code owner} (never the owner) and their/owner's summons. Always false in FFA except own summons. */
    boolean isAlly(Player owner, Entity entity);

    /** Enemies of {@code owner} whose bounding box is within {@code radius} of {@code center}, nearest first. */
    List<LivingEntity> enemiesNear(Player owner, Location center, double radius);

    /** Alive teammates of {@code owner} (excluding the owner) within {@code radius} of {@code center}, nearest first. */
    List<Player> alliesNear(Player owner, Location center, double radius);

    /**
     * The enemy the owner is aiming at: a ray from the eyes along the view direction, up to {@code range} blocks,
     * stopped by solid blocks, with a slightly enlarged hitbox for forgiving aim. Null if none.
     */
    @Nullable LivingEntity findTarget(Player owner, double range);

    /**
     * The participant responsible for a damager entity: the player itself, the shooter of a projectile, the
     * source of primed TNT, or the owner of a summon/tracked entity. Null if not attributable to a participant.
     */
    @Nullable Player resolveAttacker(@Nullable Entity damager);

    /** The ability of an alive participant, null if none. */
    @Nullable Ability abilityOf(Player player);

    // ------------------------------------------------------------------ combat

    /**
     * Deals ability damage credited to {@code source} (kill feed, stats, ultimate gauge, kill credit) using
     * {@link DamageType#MAGIC} (ignores armor). Does nothing if {@code target} is not an enemy of {@code source}.
     */
    void damage(Player source, LivingEntity target, double amount);

    /** Like {@link #damage(Player, LivingEntity, double)} with an explicit damage type (affects armor/death message). */
    void damage(Player source, LivingEntity target, double amount, DamageType type);

    /** True while a {@link #damage} call is being processed (use in {@link Ability#onDealDamage} to avoid loops). */
    boolean isAbilityDamage();

    /** Heals up to max health. */
    void heal(LivingEntity target, double amount);

    /** Adds to a participant's ultimate gauge (0..100, clamped). */
    void addGauge(Player player, double amount);

    /** A participant's ultimate gauge in 0..100. */
    double gauge(Player player);

    /** Shortens a participant's remaining active cooldown by {@code seconds}. */
    void reduceCooldown(Player player, double seconds);

    // ------------------------------------------------------------------ owner-scoped resources

    /** Runs once after {@code delayTicks}; cancelled automatically when the owner's ability is revoked. */
    BukkitTask runLater(Player owner, long delayTicks, Runnable task);

    /** Repeats every {@code periodTicks}; cancelled automatically when the owner's ability is revoked. */
    BukkitTask runTimer(Player owner, long delayTicks, long periodTicks, Runnable task);

    /** Removes {@code entity} when the owner's ability is revoked (or the match ends). Returns the entity. */
    <T extends Entity> T track(Player owner, T entity);

    /**
     * Registers a summoned mob for {@code owner}: tracked like {@link #track}, treated as an ally of the owner
     * (and an enemy of the owner's enemies), never targets the owner's allies, its damage is credited to the
     * owner and it drops nothing on death.
     */
    <T extends LivingEntity> T summon(Player owner, T mob);

    /**
     * Tags a projectile launched for {@code owner}: its hits are delivered to the owner's
     * {@link Ability#onProjectileHit} with {@code tag}, it cannot ignite or break blocks, and it is removed when
     * the match ends. Set the shooter yourself if you launched it without {@code launchProjectile}.
     */
    <T extends Projectile> T tagProjectile(Player owner, T projectile, String tag);

    /**
     * Adds an attribute modifier to {@code target} on behalf of {@code owner}.
     *
     * @param durationTicks {@code > 0}: removed after that many ticks (even if the owner dies);
     *                      {@code <= 0}: removed when the owner's ability is revoked
     * @return the modifier key, usable with {@link #removeModifier}
     */
    NamespacedKey addModifier(Player owner, LivingEntity target, Attribute attribute, double amount,
                              AttributeModifier.Operation operation, long durationTicks);

    /** Removes a modifier added with {@link #addModifier} early. Clamps health if max health shrank. */
    void removeModifier(LivingEntity target, NamespacedKey key);

    // ------------------------------------------------------------------ match-scoped effects

    /**
     * Temporarily replaces {@code block} with {@code data} and restores the original after {@code durationTicks}
     * (or when the match ends). Only air and replaceable blocks (grass, flowers, snow layers, fluids...) without
     * block entities are replaced; returns false if the block was refused or is already temporary.
     */
    boolean placeTempBlock(Block block, BlockData data, long durationTicks);
}
