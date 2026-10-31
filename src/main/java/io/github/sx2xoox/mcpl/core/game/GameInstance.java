package io.github.sx2xoox.mcpl.core.game;

import java.util.UUID;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.jspecify.annotations.Nullable;

/**
 * The logic of one match of a {@link MiniGame}, created by {@link MiniGame#createInstance(GameSession)}. The hub
 * ({@link GameSession}) owns the flow; the instance only reacts to these callbacks.
 *
 * <h2>Lifecycle guarantees</h2>
 * <ol>
 *     <li>Constructed while the session is in {@link GameState#COUNTDOWN}.</li>
 *     <li>{@link #start()} — once, when the countdown completes. At that point every participant has been
 *     snapshotted (and persisted) and reset ({@link io.github.sx2xoox.mcpl.core.player.PlayerReset}: empty
 *     inventory, full health, adventure mode), teams are assigned, everyone else online is a spectator, the arena
 *     world has immediate respawn on and the state is {@link GameState#RUNNING}. Participants are <b>not</b>
 *     teleported: spread them ({@link GameSession#spreadParticipants()}) and equip them here.</li>
 *     <li>{@link #tick()} — every server tick while RUNNING ({@link GameSession#tick()} counts from 1).</li>
 *     <li>Event callbacks below, only while RUNNING.</li>
 *     <li>{@link #stop()} — <b>exactly once</b> for every created instance, when the match leaves RUNNING
 *     ({@link GameSession#finish} or {@link GameSession#abort}) or when it is aborted during the countdown (then
 *     without {@link #start()} ever being called). Remove everything you created that is not owned by the session
 *     (see below). After stop() no other method is called.</li>
 * </ol>
 *
 * <h2>Cleanup</h2>
 * Resources created through the session are released right after {@link #stop()} automatically: tasks
 * ({@link GameSession#runLater}/{@link GameSession#runTimer}), entities ({@link GameSession#track}), listeners
 * ({@link GameSession#registerListener}), boss bars, borders, temporary blocks and cleanup hooks
 * ({@link GameSession#onCleanup}). Players are restored from their snapshots when the session closes.
 *
 * <p>Exceptions thrown from {@link #start()} or {@link #tick()} abort the match (everyone is restored); exceptions
 * from other callbacks are logged.
 */
public interface GameInstance {

    /** The countdown is over: set up the match (teleport, kits, sidebars, timers). */
    void start();

    /** Every server tick while RUNNING. Throttle expensive work ({@code if (session.tick() % 20 == 0)}). */
    default void tick() {
    }

    /**
     * An alive participant died. The vanilla death message is already suppressed; drops and dropped experience too
     * and the inventory is kept, unless {@link #dropsOnDeath()}. Stats (death, kill) are recorded and the kill feed is shown by the hub after this
     * returns.
     *
     * @param victim the participant who died
     * @param killer the participant credited with the kill (last attacker within 10 seconds, resolved through
     *               projectiles, TNT, pets and registered owners), or null
     * @param event  the death event, e.g. to change the death sound
     * @return what happens to the victim; defaults to {@link DeathOutcome#eliminate()}
     */
    default DeathOutcome onDeath(Player victim, @Nullable Player killer, PlayerDeathEvent event) {
        return DeathOutcome.eliminate();
    }

    /**
     * True if players of this match drop their items and experience where they die, like in vanilla survival (only
     * sensible in a world that is thrown away after the match). Defaults to false: nothing drops, the inventory is
     * kept.
     */
    default boolean dropsOnDeath() {
        return false;
    }

    /**
     * A participant came back after a {@link DeathOutcome.Respawn}: they are alive at the respawn location in
     * {@link org.bukkit.GameMode#ADVENTURE} with full health. Re-equip them here.
     */
    default void onRespawn(Player player) {
    }

    /**
     * A participant was eliminated (death with {@link DeathOutcome#eliminate()}, {@link GameSession#eliminate}, or
     * quitting). They are already removed from the alive set. Check win conditions here and call
     * {@link GameSession#finish(GameResult)} when the match is decided.
     *
     * @param player the eliminated player; may be offline already if they quit
     * @param killer who gets the credit, or null
     */
    default void onEliminated(Player player, @Nullable Player killer) {
    }

    /**
     * An alive participant is leaving the server (still online during the call). Clean up per-player state. The hub
     * eliminates them right after this ({@link #onEliminated} follows); their snapshot stays on disk and is restored
     * when they come back after the match.
     */
    default void onQuit(Player player) {
    }

    /**
     * A player joined (or rejoined after quitting) while RUNNING. Return true to let them play: the hub snapshots
     * and resets them, adds them to the alive set (and the smallest team) and then calls {@link #onLateJoin}.
     * Return false (default) to make them a spectator.
     *
     * @param rejoining true if the player took part in this match earlier and quit
     */
    default boolean allowLateJoin(Player player, boolean rejoining) {
        return false;
    }

    /** A late joiner was added as an alive participant (see {@link #allowLateJoin}). Teleport and equip them. */
    default void onLateJoin(Player player) {
    }

    /**
     * Short extra info shown after a player's name in the kill feed and the results, e.g. their ability name in its
     * color ({@code "☠ A(화염술사) → B(빙결술사)"}). Called for participants (also eliminated and offline ones)
     * while RUNNING, and for the results right after {@link GameSession#finish} but before {@link #stop()}, so keep
     * the data of eliminated players around. Null for nothing.
     */
    default @Nullable Component playerTag(UUID player) {
        return null;
    }

    /**
     * A name shown instead of the participant's own in the match's chat lines while RUNNING (kill feed, elimination
     * titles, announcements), e.g. an ability that hides its owner's name; null (default) for the real name. The
     * results always show the real name.
     */
    default @Nullable Component nameOverride(UUID player) {
        return null;
    }

    /**
     * A fixed number of teams for this match (at least 2), or 0 (default) for the hub's team size
     * ({@code match.team-size}). With a fixed count the hub creates exactly that many teams when the countdown ends
     * (even with fewer players: a mode fills them with {@link GameSession#addBot bots}, which join the smallest team)
     * and spreads the players evenly over them. Only used if the mode {@link MiniGame#supportsTeams() supports teams}.
     */
    default int teamCount() {
        return 0;
    }

    /**
     * Clean up everything this instance created that the session does not own. Called exactly once (see class
     * docs), possibly without {@link #start()} having been called.
     */
    void stop();
}
