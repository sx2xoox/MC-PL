package io.github.sx2xoox.mcpl.game.superpower.support.world;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.mockbukkit.mockbukkit.exception.UnimplementedOperationException;

/**
 * A player double that keeps per-viewer entity visibility like Paper's {@code CraftPlayer} (MockBukkit ignores
 * {@code setVisibleByDefault}): for an entity visible by default, {@code hideEntity} hides it from this player; for an
 * entity hidden by default, {@code showEntity} shows it. Changing an entity's default resets every player's own
 * show/hide of it ({@link #resetVisibility}), exactly like the server.
 */
public interface EntityViewer {

    /** Forgets this player's own show/hide of {@code entity} (its default visibility changed). */
    void resetVisibility(Entity entity);

    /**
     * {@code CraftEntity#setVisibleByDefault}: when the default actually changes, every online player's own show/hide
     * of the entity is reset first. Entity doubles call it before storing the new default.
     */
    static void defaultChanging(Entity entity) {
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online instanceof EntityViewer viewer) {
                viewer.resetVisibility(entity);
            }
        }
    }

    /** The entity's default visibility; true for MockBukkit entities that do not implement it (never hidden). */
    static boolean visibleByDefault(Entity entity) {
        try {
            return entity.isVisibleByDefault();
        } catch (UnimplementedOperationException e) {
            return true;
        }
    }
}
