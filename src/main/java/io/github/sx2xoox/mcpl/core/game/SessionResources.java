package io.github.sx2xoox.mcpl.core.game;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.logging.Level;

import io.github.sx2xoox.mcpl.core.arena.VirtualBorder;
import io.github.sx2xoox.mcpl.core.ui.ManagedBossBar;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;

/**
 * Everything a mode creates through its {@link GameSession}: released together right after
 * {@link GameInstance#stop()}. After {@link #cleanup()} new registrations are released immediately, so late callbacks
 * cannot leak anything.
 */
final class SessionResources {

    private static final int PRUNE_THRESHOLD = 64;

    private final Plugin plugin;
    private final List<BukkitTask> tasks = new ArrayList<>();
    private final Map<UUID, Entity> entities = new LinkedHashMap<>();
    private final List<Listener> listeners = new ArrayList<>();
    private final List<ManagedBossBar> sharedBars = new ArrayList<>();
    private final List<ManagedBossBar> playerBars = new ArrayList<>();
    private final List<VirtualBorder> borders = new ArrayList<>();
    private final Deque<Runnable> cleanups = new ArrayDeque<>();
    private boolean closed;

    SessionResources(Plugin plugin) {
        this.plugin = plugin;
    }

    boolean isClosed() {
        return closed;
    }

    BukkitTask runLater(long delayTicks, Runnable task) {
        return add(Bukkit.getScheduler().runTaskLater(plugin, task, Math.max(0, delayTicks)));
    }

    BukkitTask runTimer(long delayTicks, long periodTicks, Runnable task) {
        return add(Bukkit.getScheduler().runTaskTimer(plugin, task, Math.max(0, delayTicks), Math.max(1, periodTicks)));
    }

    private BukkitTask add(BukkitTask task) {
        if (closed) {
            task.cancel();
            return task;
        }
        if (tasks.size() >= PRUNE_THRESHOLD) {
            BukkitScheduler scheduler = Bukkit.getScheduler();
            tasks.removeIf(t -> t.isCancelled()
                    || (!scheduler.isQueued(t.getTaskId()) && !scheduler.isCurrentlyRunning(t.getTaskId())));
        }
        tasks.add(task);
        return task;
    }

    <T extends Entity> T track(T entity) {
        entity.setPersistent(false);
        if (closed) {
            entity.remove();
            return entity;
        }
        if (entities.size() >= PRUNE_THRESHOLD * 4) {
            entities.values().removeIf(e -> !e.isValid() && e.isDead());
        }
        entities.put(entity.getUniqueId(), entity);
        return entity;
    }

    void untrack(Entity entity) {
        entities.remove(entity.getUniqueId());
    }

    /** Removes (and stops tracking) the tracked entities that match {@code filter}. */
    void removeTracked(Predicate<? super Entity> filter) {
        for (Iterator<Entity> it = entities.values().iterator(); it.hasNext(); ) {
            Entity entity = it.next();
            if (filter.test(entity)) {
                it.remove();
                if (entity.isValid()) {
                    runSafely(entity::remove, "entity removal");
                }
            }
        }
    }

    void registerListener(Listener listener) {
        if (closed) {
            return;
        }
        Bukkit.getPluginManager().registerEvents(listener, plugin);
        listeners.add(listener);
    }

    void unregisterListener(Listener listener) {
        if (listeners.remove(listener)) {
            HandlerList.unregisterAll(listener);
        }
    }

    ManagedBossBar sharedBar(ManagedBossBar bar) {
        if (!closed) {
            sharedBars.add(bar);
        }
        return bar;
    }

    ManagedBossBar playerBar(ManagedBossBar bar) {
        if (!closed) {
            playerBars.add(bar);
        }
        return bar;
    }

    VirtualBorder border(VirtualBorder border) {
        if (!closed) {
            borders.add(border);
        }
        return border;
    }

    void onCleanup(Runnable cleanup) {
        if (closed) {
            runSafely(cleanup, "cleanup hook");
            return;
        }
        cleanups.push(cleanup);
    }

    /** Shows shared boss bars and borders to a (new) viewer. */
    void showTo(Player viewer) {
        if (closed) {
            return;
        }
        for (ManagedBossBar bar : sharedBars) {
            bar.show(viewer);
        }
        for (VirtualBorder border : borders) {
            border.show(viewer);
        }
    }

    /**
     * Sends the borders again (not the boss bars) to a viewer whose client just received a real world border: after
     * joining (Paper sends the level's border after the join event) or changing worlds.
     */
    void showBordersTo(Player viewer) {
        if (closed) {
            return;
        }
        for (VirtualBorder border : borders) {
            border.show(viewer);
        }
    }

    /** True while the entity is tracked (created by the mode and removed when it stops). */
    boolean isTracked(Entity entity) {
        return entities.containsKey(entity.getUniqueId());
    }

    /** A viewer left the server. */
    void forget(UUID playerId) {
        sharedBars.forEach(bar -> bar.forget(playerId));
        playerBars.forEach(bar -> bar.forget(playerId));
        borders.forEach(border -> border.forget(playerId));
    }

    /** Releases everything: cleanup hooks (newest first), tasks, listeners, boss bars, borders, entities. */
    void cleanup() {
        if (closed) {
            return;
        }
        closed = true;
        while (!cleanups.isEmpty()) {
            runSafely(cleanups.pop(), "cleanup hook");
        }
        for (BukkitTask task : tasks) {
            task.cancel();
        }
        tasks.clear();
        for (Listener listener : listeners) {
            HandlerList.unregisterAll(listener);
        }
        listeners.clear();
        for (ManagedBossBar bar : sharedBars) {
            runSafely(bar::hideAll, "boss bar");
        }
        for (ManagedBossBar bar : playerBars) {
            runSafely(bar::hideAll, "boss bar");
        }
        sharedBars.clear();
        playerBars.clear();
        for (VirtualBorder border : borders) {
            runSafely(border::hideAll, "border");
        }
        borders.clear();
        for (Iterator<Map.Entry<UUID, Entity>> it = entities.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Entity> entry = it.next();
            it.remove();
            Entity entity = entry.getValue();
            Entity live = entity.isValid() ? entity : Bukkit.getEntity(entry.getKey());
            if (live != null) {
                runSafely(live::remove, "entity removal");
            }
        }
    }

    private void runSafely(Runnable runnable, String what) {
        try {
            runnable.run();
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Error while releasing " + what, e);
        }
    }
}
