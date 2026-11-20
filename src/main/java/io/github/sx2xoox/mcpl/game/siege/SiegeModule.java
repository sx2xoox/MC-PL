package io.github.sx2xoox.mcpl.game.siege;

import java.util.List;
import java.util.UUID;

import io.github.sx2xoox.mcpl.core.map.lanes.LaneSites;
import io.github.sx2xoox.mcpl.game.siege.tower.Tower;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * A feature plugged into every 공성전 match (minions, jungle, gold and shop, ...) without editing {@link SiegeMatch}:
 * register a factory once with {@link SiegeGame#addModule}; each match creates its modules when it starts and calls
 * them at the points below. Every method has a no-op default. Modules use the match's hooks ({@link SiegeMatch#addScore},
 * {@link SiegeMatch#addXp}, {@link SiegeMatch#addGold}, {@link SiegeMatch#side}, {@link SiegeMatch#layout},
 * {@link SiegeMatch#damageStructure}, {@link SiegeMatch#guidance}, {@link SiegeMatch#sounds},
 * {@link SiegeMatch#damageNumbers}, {@link OverheadBar}) and must remove everything they spawned in {@link #roundEnded}
 * and {@link #stop}.
 */
public interface SiegeModule {

    /** A round's prep began (structures are fresh, players at their spawns, abilities assigned). */
    default void roundStarted(int round) {
    }

    /** The fight of the round began (abilities usable, the clock runs). */
    default void fightStarted() {
    }

    /** Every tick of the fight; {@code fightTicks} counts from the start of the round's fight. */
    default void tick(long fightTicks) {
    }

    /** The round ended (won, or the match was decided): clear what belongs to the round. */
    default void roundEnded() {
    }

    /** The match stops: remove everything. Also called after a failed start. */
    default void stop() {
    }

    /**
     * A participant (a player or a 봇전 bot) died; {@code killer} (null: no credit; a player or a bot) and the
     * {@code assisters} got the kill.
     */
    default void playerKilled(UUID victim, @Nullable UUID killer, List<UUID> assisters) {
    }

    /**
     * A structure of the other side lost {@code dealt} health to {@code by} (after the shield and the remaining health);
     * {@code credit} is the participant (a player or a bot) who dealt it, null for a minion.
     */
    default void structureDamaged(Tower tower, LaneSites.Side by, double dealt, @Nullable UUID credit) {
    }

    /** A structure fell to {@code by}; {@code destroyer} is the player credited, if any. */
    default void structureDestroyed(Tower tower, LaneSites.Side by, @Nullable UUID destroyer) {
    }

    /** The respawn delay (ticks) of {@code player}, adjusted (e.g. a shop item that cuts it). Default: unchanged. */
    default int respawnTicks(UUID player, int ticks) {
        return ticks;
    }

    /** Living non-player things abilities may target (minions, monsters). */
    default List<LivingEntity> targets() {
        return List.of();
    }

    /** Whether {@code entity} is one of this module's targets. */
    default boolean isTarget(Entity entity) {
        return false;
    }

    /** Whether this module's target {@code entity} is an enemy of {@code player}. */
    default boolean isEnemy(Player player, Entity entity) {
        return false;
    }

    /**
     * Something {@code tower} should shoot before any player (an enemy minion in range), or null: towers prefer minions
     * (docs/siege-v2.md §2). The module damages it itself in {@link #towerShot} when the tower fires.
     */
    default @Nullable LivingEntity towerTarget(Tower tower, double range) {
        return null;
    }

    /** {@code tower} fired at the module's {@code target} for {@code damage}. */
    default void towerShot(Tower tower, LivingEntity target, double damage) {
    }

    /**
     * This module's fighters that are not players (봇전 bots) of {@code side} in play: alive and hittable. Minions,
     * monsters and towers fight them like players.
     */
    default List<LivingEntity> fighters(LaneSites.Side side) {
        return List.of();
    }

    /** The participant id of the non-player fighter whose body is {@code entity} (a bot), or null. */
    default @Nullable UUID fighterOf(@Nullable Entity entity) {
        return null;
    }

    /** The kill feed tag of a fighter this module owns ("기사" for a bot), or null. */
    default @Nullable Component fighterTag(UUID id) {
        return null;
    }

    /** The "next big objective" sidebar line (the dragon timer, a buff monster), or null for none. */
    default @Nullable Component sidebarLine(Player viewer) {
        return null;
    }
}
