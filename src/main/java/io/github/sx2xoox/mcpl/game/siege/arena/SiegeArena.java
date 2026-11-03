package io.github.sx2xoox.mcpl.game.siege.arena;

import java.util.List;
import java.util.UUID;

import io.github.sx2xoox.mcpl.core.map.lanes.LaneSites;
import io.github.sx2xoox.mcpl.core.ui.Messages;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;

/**
 * What the 공성전 v2 lane systems (minions, jungle objectives, gold and shop; docs/siege-v2.md §2–§4) need from the
 * match that runs them: the seam between {@link SiegeSystems} and the rules ({@code SiegeMatch}: sides, phases,
 * scoring, respawn, towers). The match implements it once; tests implement it with a plain world.
 *
 * <p>Everything is main thread. Coordinates of {@link #sites()} are map-relative; {@link #at} turns them into world
 * locations.
 */
public interface SiegeArena {

    JavaPlugin plugin();

    /** The 공성전 messages (siege.yml). */
    Messages messages();

    /** The map's places. */
    LaneSites sites();

    World world();

    /** The world location of a map-relative block (its centre on x/z, its bottom on y). */
    Location at(int x, int y, int z);

    /** The side of a player in the match, or null for a spectator / non-member. */
    LaneSites.@Nullable Side side(UUID player);

    /** The members of a side (also those waiting to respawn). */
    List<UUID> members(LaneSites.Side side);

    /** A side's players in play: alive, not respawning, may fight and be hit. */
    List<Player> fighters(LaneSites.Side side);

    /**
     * A side's fighters that are not players (봇전 bots) in play: minions and monsters fight them like players. Their
     * participant ids come from {@link #fighterOf}; {@link #side} knows their side.
     */
    default List<LivingEntity> extraFighters(LaneSites.Side side) {
        return List.of();
    }

    /** The participant id of the non-player fighter (a bot) whose body is {@code entity}, or null. */
    default @Nullable UUID fighterOf(@Nullable Entity entity) {
        return null;
    }

    /**
     * The non-player fighter (a bot) that caused a damage event (its causing or direct entity), or null; like
     * {@link #attacker} for players.
     */
    default @Nullable UUID fighterAttacker(EntityDamageEvent event) {
        UUID id = fighterOf(event.getDamageSource().getCausingEntity());
        return id != null ? id : fighterOf(event.getDamageSource().getDirectEntity());
    }

    /** Whether the fight of the round is running (minions walk, monsters spawn, gold flows). */
    boolean isFighting();

    /** Whether {@code player} is in play for the match (a member, alive, not respawning). */
    default boolean canFight(Player player) {
        LaneSites.Side side = side(player.getUniqueId());
        return side != null && fighters(side).contains(player);
    }

    /** Team points (docs/siege-v2.md §1). */
    void addScore(LaneSites.Side side, int points);

    /** Experience for a player (docs/siege-v2.md §5). */
    void addXp(UUID player, int xp);

    /** Keeps an entity the match created, so it is removed at the end however the match ends. */
    void track(Entity entity);

    void untrack(Entity entity);

    /** A chat line to everyone in the match. */
    void broadcast(Component message);

    /** A sound at a place (the match may space repeats, e.g. through {@code SiegeSounds}). */
    default void sound(Location at, Sound sound, float volume, float pitch) {
        at.getWorld().playSound(at, sound, volume, pitch);
    }

    /** The player who caused a damage event (melee, projectile, ability, burn they set), or null. */
    @Nullable Player attacker(EntityDamageEvent event);

    /** True while an ability's damage is being delivered ({@code AbilityContext#isAbilityDamage}). */
    boolean isAbilityDamage();

    /** Gives back {@code seconds} of the player's ability cooldown ({@code AbilityContext#reduceCooldown}). */
    void reduceCooldown(Player player, double seconds);

    /**
     * The towers and the core of {@code side} its enemies (minions, players) may hit right now (standing, not
     * shielded): their hitboxes. Empty while the rules do not provide structures.
     */
    default List<LivingEntity> structures(LaneSites.Side side) {
        return List.of();
    }

    /** Whether the entity is the hitbox of a tower or a core (of either side). */
    default boolean isStructure(Entity entity) {
        return false;
    }

    /** A minion hits a structure hitbox for {@code amount} health points (the rules apply shields and scoring). */
    default void damageStructure(LivingEntity structure, LaneSites.Side attacker, double amount) {
    }

    /**
     * Shows (or refreshes) the overhead health bar of a creature (rules agent's {@code OverheadBar}). Returns false if
     * the match has none; the systems then show the health in the creature's name.
     */
    default boolean overheadBar(LivingEntity creature, Component name) {
        return false;
    }

    /** The overhead bar of a creature is no longer needed (it died or was removed). */
    default void removeOverheadBar(LivingEntity creature) {
    }

    /** A floating damage number (rules agent's {@code DamageNumbers}); nothing by default. */
    default void damageNumber(LivingEntity victim, double amount) {
    }
}
