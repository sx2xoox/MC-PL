package io.github.sx2xoox.mcpl.game.superpower.ability;

import java.util.Collection;
import java.util.List;

import net.kyori.adventure.text.Component;
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

    /**
     * Whether {@code abilities.yml} enables the ability for this match (the settings the match started with, the
     * same ones assignment uses). Abilities that pick other abilities at random (the Gambler) must skip disabled ones.
     */
    default boolean isEnabled(String abilityId) {
        return true;
    }

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
     * stopped by solid blocks, with a slightly enlarged hitbox for forgiving aim. Players hidden from the owner
     * (stealth via {@code hidePlayer}) are never returned, so aiming cannot reveal them. Null if none.
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
     * Damage that is part of the source's ultimate never charges the source's gauge (see {@link Ability#onUltimate}).
     *
     * <p>Ability damage ignores the target's invulnerability frames (rapid multi-hits all land) and applies
     * <b>no vanilla knockback</b>: abilities push targets themselves with velocities when they want to.
     * It is delivered as an {@link org.bukkit.event.entity.EntityDamageByEntityEvent} whose causing and direct
     * entity is {@code source}, so it reaches the source's {@link Ability#onDealDamage} and the target's
     * {@link Ability#onTakeDamage} hooks.
     */
    void damage(Player source, LivingEntity target, double amount);

    /** Like {@link #damage(Player, LivingEntity, double)} with an explicit damage type (affects armor/death message). */
    void damage(Player source, LivingEntity target, double amount, DamageType type);

    /** True while a {@link #damage} call is being processed (use in {@link Ability#onDealDamage} to avoid loops). */
    boolean isAbilityDamage();

    /** Heals up to max health. */
    void heal(LivingEntity target, double amount);

    /**
     * Adds to a participant's ultimate gauge (0..100, clamped). An explicit grant: unlike gauge earned from damage
     * and time, it is never blocked after an ultimate (see {@link Ability#onUltimate()}). Use it for one-off rewards
     * (a kill, an assist, a refund) and for transfers (a steal, a drain); gauge a passive charges over time or per
     * hit goes through {@link #addEarnedGauge} instead.
     */
    void addGauge(Player player, double amount);

    /**
     * Adds gauge a participant earns by playing, on top of the match's own charging from damage and time: a passive
     * that charges faster over time (per second near water, ...) or a bonus for each hit that lands. Such gains follow
     * the same rules as the match's charging (see {@link Ability#onUltimate()}): nothing is added while the
     * participant's post-ultimate recharge delay runs ({@link #isGaugeLocked}), nor from code that is part of the
     * participant's own ultimate (the ultimate method, the tasks it schedules and the hooks run for hits of the
     * entities it creates). Non-positive amounts are ignored.
     */
    void addEarnedGauge(Player player, double amount);

    /**
     * True while {@code player}'s gauge earns nothing after a successful ultimate
     * ({@code ultimate.recharge-delay-seconds}). {@link #addEarnedGauge} already drops gains meanwhile; check this to
     * skip the visuals of a passive charge, or to hold back per-hit progress that pays out gauge later.
     */
    boolean isGaugeLocked(Player player);

    /** A participant's ultimate gauge in 0..100. */
    double gauge(Player player);

    /** Shortens a participant's remaining active cooldown by {@code seconds}. */
    void reduceCooldown(Player player, double seconds);

    /** Seconds left on a participant's active cooldown, 0 when ready. */
    double cooldownRemaining(Player player);

    /**
     * Prevents {@code target} from using their active and ultimate for {@code ticks} (the longer of an existing
     * silence and the new one wins). The target sees a "침묵" indicator. Ignored for non-participants.
     */
    void silence(Player target, long ticks);

    boolean isSilenced(Player player);

    // ------------------------------------------------------------------ arena & messaging

    /** True if {@code location} is inside the arena and the current (shrinking) border. Use it for teleport destinations. */
    boolean isInsideArena(Location location);

    /** Sends a prefixed message to everyone watching the match (participants and spectators). */
    void announce(Component message);

    /**
     * Multiplier for particle counts and densities (config {@code effects.particle-scale}, clamped to 0.25..2.0).
     * Visual helpers in {@code abilities.kit} apply it; display entity counts are not scaled.
     */
    default double effectScale() {
        return 1.0;
    }

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
     * owner and it drops nothing on death. Its melee hits deal exactly its {@link Attribute#ATTACK_DAMAGE} value
     * (no vanilla randomness or difficulty scaling; on Peaceful difficulty mobs cannot hurt players at all) and
     * are routed to the owner's {@link Ability#onDealDamage} / {@link Ability#onDamageDealt} with the summon as
     * {@code event.getDamager()}.
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

    /**
     * Restores a temporary block this ability placed with {@link #placeTempBlock} before its time is up: the original
     * comes back at once and the spot can hold a new temporary block right away (setting the block back by hand would
     * leave it marked temporary until the original expiry). Only call it for blocks the ability placed itself (check
     * that the block is still what it put there: it may have expired and the spot been reused); a block with a block
     * entity (a loot chest) is never restored.
     *
     * @return false if the block is not temporary (already restored, or never placed) or has a block entity
     */
    default boolean restoreTempBlock(Block block) {
        return false;
    }

    // ------------------------------------------------------------------ terrain

    /**
     * Breaks the terrain for an explosion or a heavy impact of {@code owner}: the blocks within {@code radius} of
     * {@code center} break with their own particles and break sounds and a few flying chunks, and the battlefield
     * stays scarred until the match ends, when everything comes back at once. Abilities never touch blocks
     * themselves; this keeps every rule:
     * <ul>
     *     <li>only where the server allows it ({@code protection.ability-block-damage}, by default only in the battle
     *     map worlds) and inside the arena and the current border;</li>
     *     <li>structures (walls, pillars, rocks, trees, bridges) break, the ground is dug at most two blocks below its
     *     original surface (bowl shaped, never a pit nobody can climb out of, never through a thin floor);</li>
     *     <li>block entities, temporary blocks and blocks next to water or lava never break, nothing that a falling
     *     block rests on, nothing ever drops as an item, and attached blocks (torches, plants, doors) go with their
     *     block.</li>
     * </ul>
     * Call it after dealing the damage of the blast (cover still counts for that hit, like vanilla explosions).
     *
     * @return how many blocks broke (0 where nothing may break, or when {@code owner} is out)
     */
    int breakBlocks(Player owner, Location center, double radius);

    /**
     * {@link #breakBlocks} along the path from {@code from} to {@code to} (every block within {@code radius} of the
     * segment): a charge or a dash smashing through cover. It never digs into the floor the charge runs on: blocks
     * whose centers lie more than one block below the segment stay, so a line one block over the feet (between the
     * feet and head blocks) tunnels through walls but leaves the ground under them.
     */
    int breakBlocksAlong(Player owner, Location from, Location to, double radius);
}
