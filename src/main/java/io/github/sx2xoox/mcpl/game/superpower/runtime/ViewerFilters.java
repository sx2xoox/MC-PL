package io.github.sx2xoox.mcpl.game.superpower.runtime;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

/**
 * Entities only some players may see ({@link io.github.sx2xoox.mcpl.game.superpower.ability.AbilityContext#showOnlyTo}):
 * each is hidden by default and shown per accepted viewer. The filters are asked again for every online player on
 * {@link #refreshAll()} (every few ticks), for a player who joins ({@link #joined}) and, for one entity, on
 * {@link #refresh(Entity)}; a player who rejoins is a new client, so its old shows are forgotten.
 *
 * <p>A filter is best added inside the entity's spawn consumer: the entity is not in the world yet
 * ({@code isValid()} is false until it is added) and no client has received it. Paper keeps per-viewer shows by
 * entity id, so the viewers shown there get it with its first packets and nobody else ever does. Such a spawning
 * entity keeps its filter; one still not valid at the next {@link #refreshAll()} (removed, or never added) loses it.
 */
final class ViewerFilters {

    private final Plugin plugin;
    private final AbilityGuard guard;
    private final Map<UUID, Filter> filters = new LinkedHashMap<>();

    private static final class Filter {
        final Entity entity;
        /** The ability owner the filter belongs to; null for framework entities (the prompt channel's texts). */
        final @Nullable UUID owner;
        final String abilityId;
        final Predicate<? super Player> viewers;
        /** Players the entity is currently shown to. */
        final Set<UUID> shown = new HashSet<>();
        /** The {@link #refreshAll} generation it was added in, while its entity was still spawning; -1 once valid. */
        long spawning = -1;

        Filter(Entity entity, @Nullable UUID owner, String abilityId, Predicate<? super Player> viewers) {
            this.entity = entity;
            this.owner = owner;
            this.abilityId = abilityId;
            this.viewers = viewers;
        }
    }

    /** Counts {@link #refreshAll} calls: a spawning entity is done spawning by the next one. */
    private long generation;

    ViewerFilters(Plugin plugin, AbilityGuard guard) {
        this.plugin = plugin;
        this.guard = guard;
    }

    /**
     * Shows {@code entity} only to the players {@code viewers} accepts (replacing an earlier filter of it). Works
     * inside the entity's spawn consumer (the entity is not valid yet, see the class documentation) and on an entity
     * already in the world.
     */
    void add(@Nullable UUID owner, String abilityId, Entity entity, Predicate<? super Player> viewers) {
        if (entity instanceof Player) {
            throw new IllegalArgumentException("Players are hidden with hidePlayer, not filtered per viewer");
        }
        Filter previous = filters.remove(entity.getUniqueId());
        if (entity.isVisibleByDefault()) {
            // Also forgets every player's own show/hide of it (Paper resets them), so nobody keeps an old view.
            entity.setVisibleByDefault(false);
        }
        if (entity.isDead()) {
            return;
        }
        Filter filter = new Filter(entity, owner, abilityId, viewers);
        if (!entity.isValid()) {
            filter.spawning = generation;
        }
        if (previous != null) {
            filter.shown.addAll(previous.shown);
        }
        filters.put(entity.getUniqueId(), filter);
        refresh(filter);
    }

    /** Hides {@code entity} from everyone (a filter that cannot be kept, e.g. its owner is out). */
    void hideFromAll(Entity entity) {
        if (!(entity instanceof Player) && entity.isVisibleByDefault()) {
            entity.setVisibleByDefault(false);
        }
    }

    /** Asks the filter of {@code entity} again for every online player (no-op without one). */
    void refresh(Entity entity) {
        Filter filter = filters.get(entity.getUniqueId());
        if (filter != null) {
            refresh(filter);
        }
    }

    void refreshAll() {
        generation++;
        for (Filter filter : List.copyOf(filters.values())) {
            refresh(filter);
        }
    }

    private void refresh(Filter filter) {
        Entity entity = filter.entity;
        if (entity.isValid()) {
            filter.spawning = -1;
        } else if (entity.isDead() || filter.spawning != generation) {
            filters.remove(entity.getUniqueId(), filter);
            return;
        }
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            UUID id = viewer.getUniqueId();
            if (accepts(filter, viewer)) {
                if (filter.shown.add(id)) {
                    viewer.showEntity(plugin, entity);
                }
            } else if (filter.shown.remove(id)) {
                viewer.hideEntity(plugin, entity);
            }
        }
        filter.shown.removeIf(id -> Bukkit.getPlayer(id) == null);
    }

    private boolean accepts(Filter filter, Player viewer) {
        Boolean accepted = guard.call(filter.abilityId, "viewer filter", () -> filter.viewers.test(viewer));
        return Boolean.TRUE.equals(accepted);
    }

    /** A player joined: a new client that sees none of the filtered entities until accepted again. */
    void joined(Player player) {
        UUID id = player.getUniqueId();
        for (Filter filter : List.copyOf(filters.values())) {
            filter.shown.remove(id);
            if (!filter.entity.isDead() && accepts(filter, player) && filter.shown.add(id)) {
                player.showEntity(plugin, filter.entity);
            }
        }
    }

    /** A player left: its client (and what it was shown) is gone. */
    void quit(Player player) {
        for (Filter filter : filters.values()) {
            filter.shown.remove(player.getUniqueId());
        }
    }

    /** Stops the filter of {@code entity} (its visibility stays as it is). */
    void remove(Entity entity) {
        filters.remove(entity.getUniqueId());
    }

    /** Stops every filter the owner's ability created (the entities stay as they are; tracked ones are removed). */
    void releaseOwner(UUID owner) {
        filters.values().removeIf(filter -> owner.equals(filter.owner));
    }

    void dispose() {
        filters.clear();
    }

    /** Number of live filters (diagnostics/tests). */
    int size() {
        return filters.size();
    }
}
