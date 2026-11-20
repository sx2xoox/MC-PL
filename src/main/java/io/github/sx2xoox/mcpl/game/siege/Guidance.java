package io.github.sx2xoox.mcpl.game.siege;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import io.github.sx2xoox.mcpl.core.game.GameSession;
import io.github.sx2xoox.mcpl.core.ui.Messages;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * The single "▶ 지금 할 일" line of every player (docs/siege.md "화면 표시"), in the action bar. Anything may
 * {@link #suggest} a line with a priority and a duration; the highest-priority line that has not run out wins, and when
 * none is left the match's default line for the player ({@link #fallback}) shows. The action bar is refreshed once a
 * second and at once when a suggestion takes over, so it never flickers between sources.
 */
public final class Guidance {

    /** Default line (where to go, what to hit). */
    public static final int IDLE = 0;
    /** A hint (a shield, a reroll, points scored). */
    public static final int INFO = 10;
    /** An objective worth going to now (a buff monster, the dragon). */
    public static final int OBJECTIVE = 20;
    /** Something of ours needs help (a tower under attack). */
    public static final int ALERT = 30;
    /** Act now (leave the enemy base, the core is about to fall). */
    public static final int URGENT = 40;
    /** Ticks a suggestion stays by default. */
    public static final int DEFAULT_TICKS = 60;
    private static final int REFRESH_TICKS = 20;

    private record Entry(Component line, int priority, long until) {
    }

    private final GameSession session;
    private final Messages messages;
    private final Map<UUID, Entry> suggestions = new HashMap<>();
    private final Map<UUID, Component> shown = new HashMap<>();
    private Function<Player, @Nullable Component> fallback = player -> null;
    private boolean enabled = true;

    public Guidance(GameSession session, Messages messages) {
        this.session = session;
        this.messages = messages;
    }

    /** Off: nothing is sent (suggestions are dropped). */
    public void enabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** The line shown while no suggestion is active (null: nothing). */
    public void fallback(Function<Player, @Nullable Component> fallback) {
        this.fallback = fallback;
    }

    /** Suggests {@code line} to {@code player} for {@link #DEFAULT_TICKS} ticks. */
    public void suggest(Player player, Component line, int priority) {
        suggest(player, line, priority, DEFAULT_TICKS);
    }

    /**
     * Suggests {@code line} to {@code player} for {@code ticks} ticks. It replaces the current suggestion unless that
     * one has a higher priority and has not run out.
     */
    public void suggest(Player player, Component line, int priority, int ticks) {
        if (!enabled) {
            return;
        }
        long now = session.tick();
        Entry current = suggestions.get(player.getUniqueId());
        if (current != null && current.until() > now && current.priority() > priority) {
            return;
        }
        suggestions.put(player.getUniqueId(), new Entry(line, priority, now + Math.max(1, ticks)));
        send(player, true);
    }

    /** The line {@code player} sees now (the active suggestion, else the default line), without the prefix. */
    public @Nullable Component current(Player player) {
        Entry entry = suggestions.get(player.getUniqueId());
        if (entry != null && entry.until() > session.tick()) {
            return entry.line();
        }
        return fallback.apply(player);
    }

    /** Every tick: refreshes the action bars once a second. */
    public void tick() {
        if (!enabled || session.tick() % REFRESH_TICKS != 0) {
            return;
        }
        long now = session.tick();
        suggestions.values().removeIf(entry -> entry.until() <= now);
        for (Player player : session.viewers()) {
            send(player, false);
        }
    }

    /** Forgets everything (a new round). */
    public void clear() {
        suggestions.clear();
        shown.clear();
    }

    private void send(Player player, boolean force) {
        Component line = current(player);
        if (line == null) {
            shown.remove(player.getUniqueId());
            return;
        }
        Component bar = messages.get("guide.line", Messages.tag("line", line));
        if (force || !bar.equals(shown.get(player.getUniqueId())) || session.tick() % REFRESH_TICKS == 0) {
            shown.put(player.getUniqueId(), bar);
            player.sendActionBar(bar);
        }
    }
}
