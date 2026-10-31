package io.github.sx2xoox.mcpl.core.map;

import io.github.sx2xoox.mcpl.core.command.Permissions;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.LeavesDecayEvent;

/**
 * Keeps map worlds exactly as built: water and lava stay where they are, nothing melts, forms, spreads, decays or
 * burns, and outside matches only admins can break or place blocks. (During a match the arena protection decides.)
 */
final class MapWorldListener implements Listener {

    private final MapService maps;

    MapWorldListener(MapService maps) {
        this.maps = maps;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    void onFlow(BlockFromToEvent event) {
        if (maps.isMapWorld(event.getBlock().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    void onFade(BlockFadeEvent event) {
        if (maps.isMapWorld(event.getBlock().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    void onForm(BlockFormEvent event) {
        if (maps.isMapWorld(event.getBlock().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    void onSpread(BlockSpreadEvent event) {
        if (maps.isMapWorld(event.getBlock().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    void onDecay(LeavesDecayEvent event) {
        if (maps.isMapWorld(event.getBlock().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    void onBurn(BlockBurnEvent event) {
        if (maps.isMapWorld(event.getBlock().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    void onIgnite(BlockIgniteEvent event) {
        if (maps.isMapWorld(event.getBlock().getWorld()) && event.getCause() == BlockIgniteEvent.IgniteCause.SPREAD) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    void onBreak(BlockBreakEvent event) {
        if (maps.isMapWorld(event.getBlock().getWorld()) && !event.getPlayer().hasPermission(Permissions.ADMIN)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    void onPlace(BlockPlaceEvent event) {
        if (maps.isMapWorld(event.getBlock().getWorld()) && !event.getPlayer().hasPermission(Permissions.ADMIN)) {
            event.setCancelled(true);
        }
    }
}
