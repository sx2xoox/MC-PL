package io.github.sx2xoox.mcpl.game.superpower.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;

/**
 * Resources an ability created for one owner through the context (tasks, tracked entities, summons, tagged
 * projectiles, untimed modifiers). Released together when the owner's ability is revoked; once closed, new
 * registrations are released immediately so late callbacks cannot leak anything.
 */
final class OwnerScope {

    private static final int PRUNE_THRESHOLD = 64;

    private final UUID owner;
    private final List<BukkitTask> tasks = new ArrayList<>();
    private final Map<UUID, Entity> entities = new LinkedHashMap<>();
    private final Set<NamespacedKey> modifiers = new LinkedHashSet<>();
    private boolean closed;

    OwnerScope(UUID owner, boolean closed) {
        this.owner = owner;
        this.closed = closed;
    }

    UUID owner() {
        return owner;
    }

    boolean isClosed() {
        return closed;
    }

    /** Keeps the task; returns false (and cancels it) if the scope is closed. */
    boolean addTask(BukkitTask task) {
        if (closed) {
            task.cancel();
            return false;
        }
        if (tasks.size() >= PRUNE_THRESHOLD) {
            BukkitScheduler scheduler = Bukkit.getScheduler();
            tasks.removeIf(t -> t.isCancelled()
                    || (!scheduler.isQueued(t.getTaskId()) && !scheduler.isCurrentlyRunning(t.getTaskId())));
        }
        tasks.add(task);
        return true;
    }

    /** Keeps the entity; returns false if the scope is closed (the caller removes it). */
    boolean addEntity(Entity entity) {
        if (closed) {
            return false;
        }
        if (entities.size() >= PRUNE_THRESHOLD) {
            entities.values().removeIf(Entity::isDead);
        }
        entities.put(entity.getUniqueId(), entity);
        return true;
    }

    /** Keeps an untimed modifier key; returns false if the scope is closed (the caller removes it). */
    boolean addModifier(NamespacedKey key) {
        if (closed) {
            return false;
        }
        modifiers.add(key);
        return true;
    }

    void forgetModifier(NamespacedKey key) {
        modifiers.remove(key);
    }

    List<Entity> entities() {
        return List.copyOf(entities.values());
    }

    /** Closes the scope: cancels the tasks and hands back the entities and modifiers to release. */
    Released close() {
        closed = true;
        for (BukkitTask task : tasks) {
            task.cancel();
        }
        tasks.clear();
        Released released = new Released(List.copyOf(entities.values()), List.copyOf(modifiers));
        entities.clear();
        modifiers.clear();
        return released;
    }

    record Released(List<Entity> entities, List<NamespacedKey> modifiers) {
    }
}
