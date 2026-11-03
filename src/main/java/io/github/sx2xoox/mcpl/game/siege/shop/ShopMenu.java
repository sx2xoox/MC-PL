package io.github.sx2xoox.mcpl.game.siege.shop;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import io.github.sx2xoox.mcpl.core.ui.Messages;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;
import io.papermc.paper.datacomponent.item.TooltipDisplay;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * The shop chest of one player: category tabs on top (and the gold), the tab's items in the middle, the player's six
 * item slots at the bottom. Clicks go to {@link SiegeShop}, which knows the menu by its holder.
 */
public final class ShopMenu implements InventoryHolder {

    static final int SIZE = 54;
    /** The tabs, one per category. */
    static final int FIRST_TAB = 1;
    static final int GOLD_SLOT = 8;
    /** Item slots of the open tab. */
    static final int[] ITEM_SLOTS = {20, 22, 24, 29, 31, 33};
    /** The first of the owned item slots (bottom row). */
    static final int FIRST_OWNED = 46;

    private final UUID owner;
    private final Inventory inventory;
    private ShopItem.Category category = ShopItem.Category.BASIC;

    ShopMenu(UUID owner, Component title) {
        this.owner = owner;
        this.inventory = Bukkit.createInventory(this, SIZE, title);
    }

    UUID owner() {
        return owner;
    }

    public ShopItem.Category category() {
        return category;
    }

    void category(ShopItem.Category category) {
        this.category = category;
    }

    /** Draws the menu. */
    void render(Messages messages, ShopSettings settings, int gold, List<SiegeShop.Owned> owned) {
        ItemStack filler = blank(Material.WHITE_STAINED_GLASS_PANE);
        for (int slot = 0; slot < SIZE; slot++) {
            inventory.setItem(slot, filler.clone());
        }
        ShopItem.Category[] categories = ShopItem.Category.values();
        for (int i = 0; i < categories.length; i++) {
            ShopItem.Category tab = categories[i];
            ItemStack icon = named(ItemStack.of(tab.icon()),
                    messages.get("shop.category." + tab.key()), List.of());
            if (tab == category) {
                icon.setData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
            }
            inventory.setItem(FIRST_TAB + i, icon);
        }
        inventory.setItem(GOLD_SLOT, named(ItemStack.of(Material.GOLD_INGOT),
                messages.get("shop.gold", Messages.tag("balance", gold)), List.of()));
        List<ShopItem> items = category.items();
        for (int i = 0; i < items.size() && i < ITEM_SLOTS.length; i++) {
            ShopItem item = items.get(i);
            int price = settings.price(item);
            ItemStack icon = named(ItemStack.of(item.icon()), messages.get("shop.item." + item.key() + ".name"),
                    List.of());
            List<Component> lore = new ArrayList<>(messages.lines("shop.item." + item.key() + ".lore"));
            lore.add(messages.get(gold >= price ? "shop.price" : "shop.price-short", Messages.tag("price", price)));
            icon.setData(DataComponentTypes.LORE, ItemLore.lore(italicFree(lore)));
            inventory.setItem(ITEM_SLOTS[i], icon);
        }
        for (int i = 0; i < settings.slots() && i < 7; i++) {
            ItemStack icon;
            if (i < owned.size()) {
                SiegeShop.Owned slot = owned.get(i);
                icon = named(ItemStack.of(slot.item().icon(), Math.max(1, Math.min(64, slot.count()))),
                        messages.get("shop.item." + slot.item().key() + ".name"),
                        messages.lines("shop.item." + slot.item().key() + ".lore"));
            } else {
                icon = named(ItemStack.of(Material.LIGHT_GRAY_STAINED_GLASS_PANE), messages.get("shop.empty-slot"),
                        List.of());
            }
            inventory.setItem(FIRST_OWNED + i, icon);
        }
    }

    /** The tab in a slot, or null. */
    ShopItem.@Nullable Category tabAt(int slot) {
        int index = slot - FIRST_TAB;
        ShopItem.Category[] categories = ShopItem.Category.values();
        return index >= 0 && index < categories.length ? categories[index] : null;
    }

    /** The item for sale in a slot of the open tab, or null. */
    public @Nullable ShopItem itemAt(int slot) {
        List<ShopItem> items = category.items();
        for (int i = 0; i < ITEM_SLOTS.length && i < items.size(); i++) {
            if (ITEM_SLOTS[i] == slot) {
                return items.get(i);
            }
        }
        return null;
    }

    /** The menu slot of an item (switching to its tab first): tests and tooling. */
    public int slotOf(ShopItem item) {
        category = item.category();
        return ITEM_SLOTS[category.items().indexOf(item)];
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    private static ItemStack blank(Material material) {
        ItemStack item = ItemStack.of(material);
        item.setData(DataComponentTypes.TOOLTIP_DISPLAY, TooltipDisplay.tooltipDisplay().hideTooltip(true).build());
        return item;
    }

    static ItemStack named(ItemStack item, Component name, List<Component> lore) {
        item.setData(DataComponentTypes.ITEM_NAME, name.decoration(TextDecoration.ITALIC, false));
        if (!lore.isEmpty()) {
            item.setData(DataComponentTypes.LORE, ItemLore.lore(italicFree(lore)));
        }
        return item;
    }

    static List<Component> italicFree(List<Component> lines) {
        return lines.stream().map(line -> line.decoration(TextDecoration.ITALIC, false)).toList();
    }
}
