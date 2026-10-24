package io.github.sx2xoox.mcpl.core.ui;

import io.github.sx2xoox.mcpl.core.Hub;
import io.github.sx2xoox.mcpl.core.command.Permissions;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/**
 * Keeps the settings menu a menu: every click and drag while one is open is cancelled (top or bottom inventory,
 * shift-clicks, number keys, double clicks), so no item can be taken out or put in; clicks on the menu's own slots
 * are passed to it. The permission is checked again on every click, so an admin who lost it cannot change anything
 * (the menu closes).
 */
public final class SettingsMenuListener implements Listener {

    private final Hub hub;

    public SettingsMenuListener(Hub hub) {
        this.hub = hub;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof SettingsMenu menu)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (event.getClickedInventory() != menu.getInventory()) {
            return; // the own inventory below the menu: nothing moves, nothing happens
        }
        if (!player.hasPermission(Permissions.ADMIN)) {
            hub.messages().send(player, "settings.no-permission");
            // Never change the view inside the click event itself.
            hub.plugin().getServer().getScheduler().runTask(hub.plugin(), () -> {
                if (SettingsEditor.openMenu(player) == menu) {
                    player.closeInventory();
                }
            });
            return;
        }
        menu.click(player, event.getRawSlot(), event.getClick());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof SettingsMenu) {
            event.setCancelled(true);
        }
    }
}
