package io.github.sx2xoox.mcpl.core.game;

import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.persistence.PersistentDataType;

/** Routes Bukkit events to the {@link GameSession}; registered only while that session exists. */
final class SessionListener implements Listener {

    private final GameSession session;

    SessionListener(GameSession session) {
        this.session = session;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onJoin(PlayerJoinEvent event) {
        session.handleJoin(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onQuit(PlayerQuitEvent event) {
        session.handleQuit(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        session.handleWorldChange(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent event) {
        session.handleDeath(event);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(PlayerRespawnEvent event) {
        session.handleRespawn(event);
    }

    @EventHandler
    public void onPostRespawn(PlayerPostRespawnEvent event) {
        session.handlePostRespawn(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamageCheck(EntityDamageEvent event) {
        if (event instanceof EntityDamageByEntityEvent byEntity && byEntity.getDamager() instanceof Firework firework
                && firework.getPersistentDataContainer().has(ResultPresenter.celebrationKey(session.hub()),
                PersistentDataType.BYTE)) {
            event.setCancelled(true);
            return;
        }
        if (event.getEntity() instanceof Player victim) {
            session.handleDamageCheck(event, victim);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamageRecord(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player victim) {
            session.handleDamageRecord(event, victim);
        }
    }
}
