package io.github.sx2xoox.mcpl.game.augment.bot;

import java.util.List;
import java.util.UUID;

import io.github.sx2xoox.mcpl.core.arena.Arena;
import io.github.sx2xoox.mcpl.core.game.GameSession;
import io.github.sx2xoox.mcpl.core.ui.Messages;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.entity.EntityDamageEvent;
import org.jspecify.annotations.Nullable;

/** What the bots need from the match they play in (implemented by the 증강 야생전 match). */
public interface BotHost {

    GameSession session();

    Messages messages();

    /** The match world (bots never place or break blocks anywhere else). */
    World world();

    Arena arena();

    /** Ticks since the farming started. */
    long clock();

    /**
     * How fast the match clocks run ({@code /게임 증강 배속}, 1 normally): the farming, the gear schedule
     * ({@link #farmingProgress()}) and the border follow it already; the bots build their bases this much faster.
     * Reactions, walking and fighting stay real-time.
     */
    int timeScale();

    /**
     * 0..1, how late the fight is: 0 until PvP, then rising with the fight time until the border reaches its final
     * size (1). The bots wait, avoid and hide less as it rises, and their scouting drifts to the final zone.
     */
    double pressure();

    /** PvP is on. */
    boolean fight();

    /** 0..1 of the farming phase done (1 in the fight). */
    double farmingProgress();

    /**
     * The farming progress the bots' gear starts from: when the players' starting kit puts them in iron, the bots start
     * with the matching step of their gear schedule instead of nothing. 0 otherwise.
     */
    default double gearFloor() {
        return 0;
    }

    /** Match-clock ticks of the farming still to go (0 in the fight); real ticks are this over {@link #timeScale()}. */
    long farmingTicksLeft();

    double borderCenterX();

    double borderCenterZ();

    /** Half side of the border {@code ticksAhead} ticks from now (where it is heading). */
    double borderHalfIn(long ticksAhead);

    /** Half side of the final border. */
    double finalHalf();

    boolean borderShrinking();

    /** See {@link io.github.sx2xoox.mcpl.game.augment.api.AugmentContext#isEnemy}, for any participant id. */
    boolean isEnemy(UUID owner, @Nullable Entity entity);

    /** Enemy combatants of {@code owner} in {@code world}: players in play and bot bodies. */
    List<LivingEntity> rivals(UUID owner, World world);

    /** The participant id of a combatant's body (a player or a bot body), or null. */
    @Nullable UUID participantOf(@Nullable Entity entity);

    /** The participant credited with a damage event (a player, a bot, their projectiles), or null. */
    @Nullable UUID attackerOf(EntityDamageEvent event);

    /** A bot's body died: credit the kill, strip its augments, eliminate it, check for a winner. */
    void botDied(UUID bot, LivingEntity body, @Nullable UUID killer, @Nullable EntityDamageEvent lastDamage);
}
