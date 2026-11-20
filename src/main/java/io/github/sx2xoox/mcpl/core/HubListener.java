package io.github.sx2xoox.mcpl.core;

import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent;
import io.github.sx2xoox.mcpl.core.game.GameSession;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Always-on listener: restores pending snapshots of players who are not part of a running match (after a crash, a
 * quit mid-match or a death at the moment of restoring) and keeps the rotation boss bar in sync.
 */
final class HubListener implements Listener {

    private final Hub hub;

    HubListener(Hub hub) {
        this.hub = hub;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (hub.games().session() == null) {
            restorePending(player);
        }
        hub.games().rotation().onJoin(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        hub.games().rotation().onQuit(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPostRespawn(PlayerPostRespawnEvent event) {
        Player player = event.getPlayer();
        GameSession session = hub.games().session();
        if (session == null || !session.isMember(player)) {
            restorePending(player);
        }
    }

    /** Restores the player's pending snapshot, if any (not while they are part of the running match). */
    void restorePending(Player player) {
        GameSession session = hub.games().session();
        if (session != null && session.isMember(player)) {
            return;
        }
        if (hub.snapshots().has(player.getUniqueId()) && hub.snapshots().restore(player)) {
            hub.messages().send(player, "match.restored-pending");
        }
    }
}
