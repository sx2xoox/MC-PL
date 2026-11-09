package io.github.sx2xoox.mcpl.game.superpower.raid;

import java.util.List;
import java.util.Random;
import java.util.function.Consumer;

import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityContext;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.jspecify.annotations.Nullable;

/**
 * Services a raid match offers to bosses and the wave director. One context exists per raid match.
 *
 * <p>Every resource created here (tasks, tracked entities, displays, hostiles, temp blocks) is released when the
 * raid match ends at the latest. Nothing outlives the match. All methods run on the main thread.
 */
public interface RaidContext {

    Plugin plugin();

    /**
     * The match's ability context. Use it only for the kit's visual helpers ({@code Vfx} shapes read the configured
     * effect scale from it); never use its owner-scoped helpers for boss resources.
     */
    AbilityContext abilities();

    Random random();

    /** Ticks since the raid fight began. */
    long tick();

    /** The current wave number, starting at 1. */
    int wave();

    /** Participants at the start of the raid (used for health/damage scaling). */
    int playerCount();

    /** Configured difficulty multiplier (1.0 = normal). Bosses scale damage with it. */
    double difficulty();

    /** Participants that are alive and in play right now. */
    List<Player> alivePlayers();

    Location arenaCenter();

    double arenaRadius();

    /** True if {@code location} is inside the arena (and the current border, if any). */
    boolean isInsideArena(Location location);

    // ------------------------------------------------------------------ hostiles

    /**
     * Registers a raid enemy: it becomes an enemy of every participant ({@code AbilityContext#isEnemy} is true for
     * it, so abilities can target it), is tracked and removed when the match ends, never drops items or xp, counts
     * for raid stats (kills, damage) and charges the ultimate gauge of players who damage it. Returns the entity.
     */
    <T extends LivingEntity> T registerHostile(T entity);

    /** True while {@code entity} is a registered, living raid enemy. */
    boolean isHostile(Entity entity);

    /**
     * Damages a participant on behalf of a raid enemy (credited to {@code source} in the kill feed). Does nothing for
     * non-participants or players who are not alive. {@code amount} is final damage before armor (MAGIC type
     * ignores armor; use {@code armor = true} for physical hits).
     */
    void hurtPlayer(LivingEntity source, Player target, double amount, boolean armor);

    // ------------------------------------------------------------------ resources

    BukkitTask runLater(long delayTicks, Runnable task);

    BukkitTask runTimer(long delayTicks, long periodTicks, Runnable task);

    /** Removes {@code entity} after {@code lifetimeTicks} ({@code <= 0}: when the match ends). Returns the entity. */
    <T extends Entity> T track(T entity, long lifetimeTicks);

    /**
     * Spawns a display entity with the kit's defaults (full bright, no shadow, view range 1.5, smooth teleports),
     * runs {@code setup} before it becomes visible and tracks it for {@code lifetimeTicks} ({@code <= 0}: until the
     * match ends).
     */
    <T extends Display> T display(Location at, Class<T> type, long lifetimeTicks, @Nullable Consumer<? super T> setup);

    /** Like {@code AbilityContext#placeTempBlock}: only air/replaceable blocks, restored after the duration. */
    boolean placeTempBlock(Block block, BlockData data, long durationTicks);

    // ------------------------------------------------------------------ messaging

    /** A prefixed chat message to everyone watching the raid. */
    void announce(Component message);

    /** A title to everyone watching the raid. */
    void title(Component title, Component subtitle);
}
