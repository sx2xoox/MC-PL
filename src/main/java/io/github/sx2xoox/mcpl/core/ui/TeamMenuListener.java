package io.github.sx2xoox.mcpl.core.ui;

import io.github.sx2xoox.mcpl.core.Hub;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/**
 * Keeps the team menu a menu: every click and drag while one is open is cancelled; clicks on its own slots pick a
 * team. Self-picking can be turned off ({@code match.team-choice}) while a menu is open, so it is checked per click.
 */
public final class TeamMenuListener implements Listener {

    private final Hub hub;

    public TeamMenuListener(Hub hub) {
        this.hub = hub;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof TeamMenu menu)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getClickedInventory() != menu.getInventory()) {
            return;
        }
        if (!hub.config().teamChoice()) {
            hub.messages().send(player, "team.choice-off");
            return;
        }
        menu.click(player, event.getRawSlot());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof TeamMenu) {
            event.setCancelled(true);
        }
    }
}
