package io.github.sx2xoox.mcpl.game.superpower.loot;

import io.github.sx2xoox.mcpl.core.game.GameSession;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Bukkit events of the loot (registered through the session, so it goes away with the match).
 *
 * <p>The arena protection denies container use to participants and the temp-block service denies using temporary
 * blocks; this listener runs after both ({@link EventPriority#HIGH}, also for already denied clicks) and opens the
 * chest's virtual inventory itself, so the protection stays as strict as it is.
 */
public final class LootListener implements Listener {

    private final LootManager loot;
    private final GameSession session;

    public LootListener(LootManager loot, GameSession session) {
        this.loot = loot;
        this.session = session;
    }

    /**
     * Right-click: a loot chest opens (not with the ability item in hand, and vanilla rules: not while sneaking with
     * an item), or a consumable is used.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_BLOCK && action != Action.RIGHT_CLICK_AIR) {
            return;
        }
        Player player = event.getPlayer();
        if (action == Action.RIGHT_CLICK_BLOCK) {
            Block block = event.getClickedBlock();
            LootChest chest = loot.chestAt(block);
            if (chest != null) {
                event.setUseInteractedBlock(Event.Result.DENY);
                if (loot.opensOnClick(player)) {
                    event.setUseItemInHand(Event.Result.DENY);
                    if (event.getHand() == EquipmentSlot.HAND) {
                        loot.open(player, chest);
                    }
                    return;
                }
            }
        }
        if (loot.isAbilityItem(player.getInventory().getItemInMainHand())) {
            // That click is the ability (the match listener). The client sends the off-hand use right after it, as
            // the ability item has no vanilla use: a consumable in the off hand must not go with every skill.
            return;
        }
        LootConsumables.Kind kind = loot.consumables().kindOf(event.getItem());
        EquipmentSlot hand = event.getHand();
        if (kind == null || hand == null || !session.isParticipant(player)) {
            return;
        }
        event.setUseItemInHand(Event.Result.DENY);
        loot.useConsumable(player, hand, kind);
    }

    /** Loot chests are take-only: nothing can be put in (or dropped out of) them. */
    @EventHandler(priority = EventPriority.LOW)
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (loot.chestOf(top) == null || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!allows(event, player, event.getRawSlot() >= 0 && event.getRawSlot() < top.getSize())) {
            event.setCancelled(true);
        }
    }

    private static boolean allows(InventoryClickEvent event, Player player, boolean inChest) {
        if (event.getClickedInventory() == null) {
            return true; // outside the window: the arena's item-drop rule applies
        }
        if (!inChest) {
            // Shift-clicking an own item would move it into the chest.
            return event.getAction() != InventoryAction.MOVE_TO_OTHER_INVENTORY;
        }
        return switch (event.getAction()) {
            case PICKUP_ALL, PICKUP_SOME, PICKUP_HALF, PICKUP_ONE, MOVE_TO_OTHER_INVENTORY, COLLECT_TO_CURSOR,
                 NOTHING -> true;
            case HOTBAR_SWAP -> event.getClick() == ClickType.NUMBER_KEY
                    ? isEmpty(player.getInventory().getItem(event.getHotbarButton()))
                    : isEmpty(player.getInventory().getItemInOffHand());
            default -> false;
        };
    }

    private static boolean isEmpty(@Nullable ItemStack stack) {
        return stack == null || stack.isEmpty();
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onDrag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (loot.chestOf(top) == null) {
            return;
        }
        for (int slot : event.getRawSlots()) {
            if (slot < top.getSize()) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        LootChest chest = loot.chestOf(event.getInventory());
        if (chest != null) {
            loot.closed(chest);
        }
    }

    /**
     * Whatever a participant manages to drop during the match (when the arena allows dropping items) belongs to the
     * match and is removed when it ends, so no loot stays in the world.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (session.isParticipant(event.getPlayer())) {
            session.track(event.getItemDrop());
        }
    }
}
