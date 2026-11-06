package io.github.sx2xoox.mcpl.core.pack;

import java.util.function.Consumer;

import org.bukkit.entity.Player;

/**
 * Who has the MCPL resource pack loaded right now. Visuals that need the pack (3D models) show them only to players
 * for whom {@link #hasPack} is true and a vanilla fallback to everyone else, and swap when a player's status changes.
 */
public interface PackAudience {

    /** Nobody has the pack (the pack is disabled): everyone sees the vanilla fallback. */
    PackAudience NONE = new PackAudience() {
        @Override
        public boolean hasPack(Player player) {
            return false;
        }

        @Override
        public Subscription subscribe(Consumer<Player> listener) {
            return () -> { };
        }
    };

    /** The player's client loaded the current pack. */
    boolean hasPack(Player player);

    /**
     * Calls {@code listener} (on the main thread) whenever a player's {@link #hasPack} may have changed: the pack
     * loaded, failed, was declined, or was replaced by a newer one.
     */
    Subscription subscribe(Consumer<Player> listener);

    /** Stops a {@link #subscribe} listener. */
    interface Subscription extends AutoCloseable {
        @Override
        void close();
    }
}
