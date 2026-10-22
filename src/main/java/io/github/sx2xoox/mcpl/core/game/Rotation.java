package io.github.sx2xoox.mcpl.core.game;

import java.util.List;
import java.util.function.Predicate;

import io.github.sx2xoox.mcpl.core.Hub;
import io.github.sx2xoox.mcpl.core.ui.Feedback;
import io.github.sx2xoox.mcpl.core.ui.ManagedBossBar;
import io.github.sx2xoox.mcpl.core.ui.Messages;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.jspecify.annotations.Nullable;

/**
 * Automatic back-to-back matches. When enabled, after a match ends the hub waits {@code rotation.intermission-seconds}
 * (with a boss bar countdown for everyone) and then starts the next mode of {@code rotation.games}. If not enough
 * players are online at that moment it keeps waiting (the boss bar says so) and starts as soon as they are.
 */
public final class Rotation {

    private final Hub hub;
    private final GameManager manager;
    private @Nullable BukkitTask task;
    private @Nullable ManagedBossBar bar;
    private @Nullable String pendingGameId;
    /** Index in {@link #gameIds()} of {@link #pendingGameId}, -1 when not waiting. */
    private int pendingIndex = -1;
    /**
     * Index in {@link #gameIds()} of the entry the rotation is at (the last match it started), -1 before the first.
     * Tracked by position, not by id, so a list that repeats a mode ({@code [a, b, a, c]}) reaches every entry.
     */
    private int position = -1;
    /** Set while the rotation itself starts a match, so {@link #onMatchStarted} knows which entry it was. */
    private int startingIndex = -1;
    private int secondsLeft;
    private int totalSeconds;

    Rotation(Hub hub, GameManager manager) {
        this.hub = hub;
        this.manager = manager;
    }

    /**
     * The id after {@code lastId} in {@code ids} that {@code isKnown}, wrapping around; the first known id when
     * {@code lastId} is null or not in the list; null if no id is known. With repeated ids, {@code lastId} means its
     * first occurrence; the rotation itself uses {@link #pickNextIndex} with its position instead.
     */
    public static @Nullable String pickNext(List<String> ids, @Nullable String lastId, Predicate<String> isKnown) {
        int index = pickNextIndex(ids, lastId == null ? -1 : ids.indexOf(lastId), isKnown);
        return index < 0 ? null : ids.get(index);
    }

    /**
     * The index of the first entry after {@code lastIndex} in {@code ids} that {@code isKnown}, wrapping around; the
     * first known entry when {@code lastIndex} is negative or out of range; -1 if no entry is known.
     */
    public static int pickNextIndex(List<String> ids, int lastIndex, Predicate<String> isKnown) {
        if (ids.isEmpty()) {
            return -1;
        }
        int start = lastIndex < 0 || lastIndex >= ids.size() ? -1 : lastIndex;
        for (int step = 1; step <= ids.size(); step++) {
            int index = Math.floorMod(start + step, ids.size());
            if (isKnown.test(ids.get(index))) {
                return index;
            }
        }
        return -1;
    }

    public boolean isEnabled() {
        return hub.config().rotationEnabled();
    }

    /** Turns rotation on or off (saved to {@code config.yml}); turning it on while idle schedules a match. */
    public void setEnabled(boolean enabled) {
        hub.config().setRotationEnabled(enabled);
        if (!enabled) {
            cancel();
        } else if (manager.session() == null && !isWaiting()) {
            schedule();
        }
    }

    /** The configured rotation list (lower-case ids, possibly containing unknown ids). */
    public List<String> gameIds() {
        return hub.config().rotationGames();
    }

    /** The mode that would be started next, or null if the list has no registered mode. */
    public @Nullable MiniGame next() {
        int index = nextIndex();
        return index < 0 ? null : manager.game(gameIds().get(index));
    }

    /** The index in {@link #gameIds()} of the entry that would be started next, or -1 if none is registered. */
    public int nextIndex() {
        return hasValidPending() ? pendingIndex : computeNextIndex();
    }

    private int computeNextIndex() {
        List<String> ids = gameIds();
        int last = position;
        if (last < 0 || last >= ids.size()) {
            // the rotation has not started a match yet (or the list shrank): continue after the last match's mode
            String lastId = manager.lastGameId();
            last = lastId == null ? -1 : ids.indexOf(lastId);
        }
        return pickNextIndex(ids, last, known -> manager.game(known) != null);
    }

    private boolean hasValidPending() {
        List<String> ids = gameIds();
        return task != null && pendingGameId != null && pendingIndex >= 0 && pendingIndex < ids.size()
                && ids.get(pendingIndex).equals(pendingGameId) && manager.game(pendingGameId) != null;
    }

    /**
     * Called by the manager when any match starts. A rotation start moves the position to its entry; a manual start of
     * exactly the mode that was next counts as that entry too; any other manual start leaves the position alone.
     */
    void onMatchStarted(MiniGame game) {
        if (startingIndex >= 0) {
            position = startingIndex;
            return;
        }
        int next = computeNextIndex();
        if (next >= 0 && gameIds().get(next).equals(game.id())) {
            position = next;
        }
    }

    /** True while counting down (or waiting for players) to the next match. */
    public boolean isWaiting() {
        return task != null;
    }

    public int secondsLeft() {
        return secondsLeft;
    }

    /** Starts the intermission with the configured length. */
    public void schedule() {
        schedule(hub.config().intermissionSeconds());
    }

    /** Starts an intermission of {@code seconds} before the next rotation mode. */
    public void schedule(int seconds) {
        cancel();
        int index = computeNextIndex();
        MiniGame next = index < 0 ? null : manager.game(gameIds().get(index));
        if (next == null) {
            hub.plugin().getLogger().warning("Rotation is enabled but rotation.games contains no registered mini-game");
            return;
        }
        pendingGameId = next.id();
        pendingIndex = index;
        totalSeconds = Math.max(1, seconds);
        secondsLeft = totalSeconds;
        bar = new ManagedBossBar(hub.messages().get("rotation.bossbar-countdown",
                Messages.tag("game", next.displayComponent()), Messages.tag("seconds", secondsLeft)),
                1f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
        for (Player player : Bukkit.getOnlinePlayers()) {
            bar.show(player);
        }
        hub.messages().send(Feedback.everyone(), "rotation.announce", Messages.tag("game", next.displayComponent()),
                Messages.tag("seconds", secondsLeft));
        task = Bukkit.getScheduler().runTaskTimer(hub.plugin(), this::second, 20L, 20L);
    }

    /** Stops the intermission (no match is started). */
    public void cancel() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (bar != null) {
            bar.hideAll();
            bar = null;
        }
        pendingGameId = null;
        pendingIndex = -1;
    }

    /** Shows the intermission boss bar to a player who joined (called by the hub). */
    public void onJoin(Player player) {
        if (bar != null) {
            bar.show(player);
        }
    }

    /** Forgets a player who left (called by the hub). */
    public void onQuit(Player player) {
        if (bar != null) {
            bar.forget(player.getUniqueId());
        }
    }

    private void second() {
        if (manager.session() != null) {
            cancel();
            return;
        }
        MiniGame game = hasValidPending() ? manager.game(pendingGameId) : null;
        if (game == null) {
            // the list changed (reload) or the mode is gone: pick again
            int index = computeNextIndex();
            game = index < 0 ? null : manager.game(gameIds().get(index));
            if (game == null) {
                cancel();
                return;
            }
            pendingGameId = game.id();
            pendingIndex = index;
        }
        ManagedBossBar current = bar;
        if (current == null) {
            cancel();
            return;
        }
        secondsLeft = Math.max(0, secondsLeft - 1);
        if (secondsLeft > 0) {
            current.name(hub.messages().get("rotation.bossbar-countdown",
                    Messages.tag("game", game.displayComponent()), Messages.tag("seconds", secondsLeft)));
            current.progress((float) secondsLeft / totalSeconds);
            current.color(BossBar.Color.YELLOW);
            if (secondsLeft <= 3) {
                Feedback.play(Feedback.players(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.6f, 1.4f);
            }
            return;
        }
        int eligible = manager.eligiblePlayers().size();
        int min = Math.max(1, game.minPlayers());
        if (eligible < min) {
            current.name(hub.messages().get("rotation.bossbar-waiting", Messages.tag("game", game.displayComponent()),
                    Messages.tag("count", eligible), Messages.tag("min", min)));
            current.progress(1f);
            current.color(BossBar.Color.WHITE);
            return;
        }
        MiniGame starting = game;
        int index = pendingIndex;
        cancel();
        startingIndex = index;
        try {
            manager.start(starting, null);
        } catch (GameStartException e) {
            startingIndex = -1;
            hub.plugin().getLogger().warning("Rotation could not start " + starting.id() + ": " + e.getMessage());
            if (manager.session() == null && isEnabled()) {
                schedule();
            }
        } finally {
            startingIndex = -1;
        }
    }
}
