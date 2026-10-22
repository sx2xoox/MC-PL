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
    private int secondsLeft;
    private int totalSeconds;

    Rotation(Hub hub, GameManager manager) {
        this.hub = hub;
        this.manager = manager;
    }

    /**
     * The id after {@code lastId} in {@code ids} that {@code isKnown}, wrapping around; the first known id when
     * {@code lastId} is null or not in the list; null if no id is known.
     */
    public static @Nullable String pickNext(List<String> ids, @Nullable String lastId, Predicate<String> isKnown) {
        if (ids.isEmpty()) {
            return null;
        }
        int start = lastId == null ? -1 : ids.indexOf(lastId);
        for (int step = 1; step <= ids.size(); step++) {
            String candidate = ids.get(Math.floorMod(start + step, ids.size()));
            if (isKnown.test(candidate)) {
                return candidate;
            }
        }
        return null;
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
        String id = pickNext(gameIds(), manager.lastGameId(), known -> manager.game(known) != null);
        return id == null ? null : manager.game(id);
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
        MiniGame next = next();
        if (next == null) {
            hub.plugin().getLogger().warning("Rotation is enabled but rotation.games contains no registered mini-game");
            return;
        }
        pendingGameId = next.id();
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
        MiniGame game = pendingGameId == null ? null : manager.game(pendingGameId);
        if (game == null) {
            game = next();
            if (game == null) {
                cancel();
                return;
            }
            pendingGameId = game.id();
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
        cancel();
        try {
            manager.start(starting, null);
        } catch (GameStartException e) {
            hub.plugin().getLogger().warning("Rotation could not start " + starting.id() + ": " + e.getMessage());
            if (manager.session() == null && isEnabled()) {
                schedule();
            }
        }
    }
}
