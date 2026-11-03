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

    /** A participant died; {@code killer} (null: no player credit) and the {@code assisters} got the kill. */
    default void playerKilled(UUID victim, @Nullable UUID killer, List<UUID> assisters) {
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

    /** The "next big objective" sidebar line (the dragon timer, a buff monster), or null for none. */
    default @Nullable Component sidebarLine(Player viewer) {
        return null;
    }
}
