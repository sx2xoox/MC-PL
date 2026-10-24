package io.github.sx2xoox.mcpl.game.superpower.ability;

import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;

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
import org.bukkit.util.Vector;
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

    // ------------------------------------------------------------------ prompts (one channel per player)

    /**
     * Puts {@code prompt} on {@code target}'s prompt channel for {@code owner}: shown at once if nothing is on the
     * target's screen and nothing waits (or only a lower-priority prompt is shown, which is pushed back), otherwise
     * queued by priority, then submission. See {@link Prompt} for the whole contract: the answer window starts when
     * the prompt is <em>shown</em> ({@link Prompt.Builder#onShown}), a prompt that waited too long ends
     * {@link PromptEndReason#UNSEEN} and must have no consequence, and {@link Prompt.Builder#onEnd} always runs
     * exactly once. A prompt submitted from another prompt's {@code onEnd} (the next step of a riddle) queues like any
     * other: prompts already waiting for the target keep their place.
     *
     * <p>While a prompt is shown the channel owns the target's action bar: it re-sends the prompt's line every tick,
     * after the abilities' own ticks and again at the end of the server tick, so another action-bar message (an
     * ability's status line, the border warning) can flash over it for at most one tick and never hides it. Abilities
     * that write other players' action bars can skip them while {@link #hasShownPrompt} is true.
     *
     * <p>The callbacks run like the owner's other code (an ultimate's prompt counts as the ultimate). The owner's
     * prompts end with {@link PromptEndReason#OWNER_REVOKED} when its ability is revoked, the target's with
     * {@link PromptEndReason#TARGET_LEFT} when it leaves play, all with {@link PromptEndReason#MATCH_END} at the end.
     * Some prompts end before this returns (their {@code onEnd} has run, the handle is no longer active):
     * <ul>
     *     <li>{@link PromptEndReason#TARGET_LEFT}: the target is not in play (dead, eliminated, spectating, offline;
     *     also a target that never was in play);</li>
     *     <li>{@link PromptEndReason#OWNER_REVOKED}: the owner's ability is out;</li>
     *     <li>{@link PromptEndReason#MATCH_END}: the match is ending (also from an {@code onEnd} that runs then);</li>
     *     <li>{@link PromptEndReason#UNSEEN}: more than {@code 8} prompts wait for the target and this one is the
     *     lowest-priority, newest of them, or it has {@code maxWait(0)} and the screen is busy.</li>
     * </ul>
     *
     * <p>Only players can be prompted. Practice-mode training dummies are not players: judge them some other way (or
     * not at all) so an ability still works in practice.
     *
     * @param owner  the player whose ability asks (may be {@code target} itself, for a vow or a chant of its own)
     * @param target the player who must read and react
     * @return the prompt's handle (already ended if it was refused)
     */
    PromptHandle prompt(Player owner, Player target, Prompt prompt);

    /** True while a prompt is on {@code player}'s screen (the channel owns the action bar). */
    boolean hasShownPrompt(Player player);

    /**
     * Extra ticks to allow when judging how {@code player} reacted: the server hears an input about one round trip
     * after it showed something. {@code 1 + ceil(ping / 50 ms)}, the ping clamped to 0..350 ms, so 1 to 8 ticks.
     * Add it to every reaction window and grace period (prompt answers, rules judged by input). Prompts add it to
     * their own window by default ({@link Prompt.Builder#reactionSlack}).
     */
    int reactionSlackTicks(Player player);

    // ------------------------------------------------------------------ voluntary input

    /**
     * How long a player moved by something else stays exempt from input rules (0.5 s): keys it uses from the moment
     * it was pushed, launched, pulled, knocked back or teleported until this many ticks later never count
     * ({@link #usedVoluntarily}). Riding a vehicle extends the same window every tick.
     */
    long INPUT_EXEMPT_TICKS = 10;

    /**
     * The tick ({@link #currentTick()}) {@code player}'s client last pressed {@code key} (released → held), or -1 if
     * it never did during this match. Recorded from every {@link org.bukkit.event.player.PlayerInputEvent}, so quick
     * taps between two ticks are not missed. Rules judge such voluntary input, never position or velocity (abilities
     * push players around); {@code abilities.kit.Inputs} builds the usual checks on this.
     */
    long lastPressTick(Player player, InputKey key);

    /** The tick {@code player}'s client last released {@code key} (held → released), or -1. */
    long lastReleaseTick(Player player, InputKey key);

    /**
     * True if {@code player}'s client held {@code key} at some moment from {@code sinceTick} until now at which
     * nothing exempted it from input rules. The exemptions, each covering exactly its own stretch of time (a key used
     * during one never counts later, a key used before one still counts):
     * <ul>
     *     <li>a displacement ({@link #lastDisplacedTick}) and the {@link #INPUT_EXEMPT_TICKS} after it, or riding a
     *     vehicle: every key but sneak;</li>
     *     <li>hard crowd control locking its movement ({@link #isMovementLocked}): the walking keys and sprint, from
     *     the modifier's start to its end;</li>
     *     <li>a lock of its jumping ({@link #isJumpLocked}): the jump key.</li>
     * </ul>
     * A key still held after an exemption ended counts from then on.
     *
     * @param ruleOwner the player whose rule is judged: its own locks exempt nothing (a talisman that leaves only
     *                  input-driven hops); null lets everyone's locks exempt
     */
    boolean usedVoluntarily(Player player, InputKey key, long sinceTick, @Nullable Player ruleOwner);

    /**
     * The tick {@code player} last swung an arm, or -1: every {@code PlayerArmSwingEvent}, so also the off hand, block
     * interactions (opening a chest, placing a block) and item use, not only attacks. Rules about attacking use
     * {@link #lastAttackTick}.
     */
    long lastSwingTick(Player player);

    /**
     * The tick {@code player} last attacked, or -1: a melee attack it tried on any entity
     * ({@code PrePlayerAttackEntityEvent}, also when something cancelled the hit) or a projectile it shot or threw
     * with an item (bow, crossbow, trident, snowball, ...). Projectiles its ability code launches and ability skills
     * are not attacks here (a "don't use abilities" rule watches cooldowns instead).
     */
    long lastAttackTick(Player player);

    /**
     * The tick {@code player} was last moved by something else, or -1: a velocity the server gave it (an ability's
     * push, launch or pull, kit {@code Motion}, vanilla knockback, explosions; anything at least 0.1 blocks per tick,
     * stopping a player does not count), vanilla knockback and every teleport that changed its position except its own
     * ender pearls and chorus fruit. Movement the player's <em>own</em> ability made while its code ran (a blink, a
     * dash, a leap, a rewind: hooks, tasks and prompt callbacks of that player's ability) and velocities set through
     * {@link #setVoluntaryVelocity} do not count.
     */
    long lastDisplacedTick(Player player);

    /** True if {@code player} was displaced (see {@link #lastDisplacedTick}) within the last {@code withinTicks}. */
    boolean wasDisplaced(Player player, long withinTicks);

    /**
     * Sets {@code target}'s velocity for movement its own input asked for (a hop the target made with the jump key, a
     * walk an ability redirects or reverses): unlike any other velocity change it does not count as displacement, so
     * input rules still judge the target. Everything else (pushes, pulls, knockback) uses {@code setVelocity} or kit
     * {@code Motion} as usual.
     */
    void setVoluntaryVelocity(Player target, Vector velocity);

    /**
     * True while hard crowd control applied through {@link #addModifier} locks {@code player}'s movement: a modifier
     * that cuts {@link Attribute#MOVEMENT_SPEED} by 90% or more (multiplicative or scalar), the usual stun, root, prison
     * or hold. Players under it are exempt from movement rules (and keys used meanwhile never count,
     * {@link #usedVoluntarily}).
     *
     * @param ignoring modifiers this player added do not count (a rule owner's own lock, e.g. the jiangshi talisman
     *                 that leaves only input-driven hops); null counts everyone's
     */
    boolean isMovementLocked(Player player, @Nullable Player ignoring);

    /** Like {@link #isMovementLocked} for {@link Attribute#JUMP_STRENGTH} (jump rules). */
    boolean isJumpLocked(Player player, @Nullable Player ignoring);

    // ------------------------------------------------------------------ per-viewer visibility

    /**
     * Makes {@code entity} (a display, a marker; never a player) visible only to the players {@code viewers} accepts.
     * It is hidden by default ({@code setVisibleByDefault(false)}) and shown per viewer ({@code showEntity}), so players
     * who join or rejoin never see it unless accepted; the match asks {@code viewers} again every few ticks, right away
     * when someone joins, leaves, is eliminated, respawns or joins a practice, and on {@link #refreshViewers()}, so a
     * changing answer (an ally who is eliminated, a viewer who starts spectating) is followed.
     *
     * <p><b>Call it inside the spawn consumer</b> ({@code world.spawn(at, type, entity -> ...)}; kit {@code Displays}
     * overloads that take {@code viewers} do it for you): that is the only point before the server sends the entity
     * to the players around it. Called after the spawn, every client in range has already received the entity (and
     * its text) once and is told to remove it again.
     *
     * <p>Calling it again replaces the filter. It stops when the entity is removed or the owner's ability is revoked
     * (the entity then stays as it is: tracked entities are removed with the owner anyway).
     */
    void showOnlyTo(Player owner, Entity entity, Predicate<? super Player> viewers);

    /**
     * Asks every per-viewer filter ({@link #showOnlyTo}) again right now. Call it when something a filter reads changed
     * and the next periodic check (up to a quarter of a second away) would be too late: a stealth starting (kit
     * {@code Stealth} does it for you), a team change, an ability's own state the filter looks at.
     */
    void refreshViewers();

    /**
     * True if {@code viewer} cannot see {@code target}, so nothing should give away where it is: the target is hidden
     * from the viewer ({@code hidePlayer}/{@code hideEntity}, kit {@code Stealth}), or it is invisible (effect or flag)
     * and the viewer is an opponent in play (allies and spectators still see invisible players). Never true for the
     * target itself.
     */
    boolean isConcealedFrom(LivingEntity target, Player viewer);

    /**
     * True for a player watching the match: a member of the match that is no participant still in the game (an
     * eliminated participant, a spectator, a late joiner). A participant who is only dead for a moment or waiting to
     * respawn (practice) is no spectator.
     */
    boolean isSpectator(Player player);

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
     * Restores a temporary block this ability placed with {@link #placeTempBlock} before its time is up (a wall that
     * opens early, a cleared row): the original comes back at once (with the other half of a double plant) and the spot
     * can hold a new temporary block right away (setting the block back by hand would leave it marked temporary until
     * the original expiry). Only blocks placed through {@link #placeTempBlock} that still hold what was placed can be
     * restored, so loot chests and other systems' blocks are never touched.
     *
     * @return false if the block is not such a temporary block (already restored, expired, or never placed here)
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
