package io.github.sx2xoox.mcpl.game.superpower.menu;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityParams;
import io.github.sx2xoox.mcpl.game.superpower.ui.AbilityTexts;
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
 * The CHOICE-mode menu of one player: a 3-row chest with up to three candidate abilities (icon, colored name,
 * difficulty stars and the rendered skill descriptions). Clicks are handled by the match listener; the menu itself
 * only maps slots to abilities. The listener recognises any open choice menu by its holder
 * ({@code getHolder(false)}, no snapshot), so even a menu that was just picked from stays locked until it closes.
 */
public final class ChoiceMenu implements InventoryHolder {

    /** Slots of the candidates, left to right. */
    static final int[] SLOTS = {11, 13, 15};
    private static final int SIZE = 27;

    private final UUID owner;
    private final List<String> candidates;
    private final Inventory inventory;

    /**
     * @param owner      the choosing player
     * @param candidates candidate abilities (at most three are shown)
     * @param params     effective params per ability (for rendered descriptions)
     * @param title      menu title
     * @param pickHint   lore line appended to every candidate (e.g. "클릭하여 선택")
     */
    public ChoiceMenu(UUID owner, List<AbilityInfo> candidates, Function<AbilityInfo, AbilityParams> params,
                      AbilityTexts texts, Component title, Component pickHint) {
        this.owner = owner;
        List<String> ids = new ArrayList<>();
        this.inventory = Bukkit.createInventory(this, SIZE, title);
        ItemStack filler = ItemStack.of(Material.GRAY_STAINED_GLASS_PANE);
        filler.setData(DataComponentTypes.TOOLTIP_DISPLAY, TooltipDisplay.tooltipDisplay().hideTooltip(true).build());
        for (int slot = 0; slot < SIZE; slot++) {
            inventory.setItem(slot, filler.clone());
        }
        for (int i = 0; i < candidates.size() && i < SLOTS.length; i++) {
            AbilityInfo info = candidates.get(i);
            ids.add(info.id());
            inventory.setItem(SLOTS[i], icon(info, params.apply(info), texts, pickHint));
        }
        this.candidates = List.copyOf(ids);
    }

    private static ItemStack icon(AbilityInfo info, AbilityParams params, AbilityTexts texts, Component pickHint) {
        ItemStack item = ItemStack.of(info.icon());
        item.setData(DataComponentTypes.ITEM_NAME,
                AbilityTexts.boldName(info).decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>(texts.lore(info, params, false));
        lore.add(Component.empty());
        lore.add(pickHint.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
        item.setData(DataComponentTypes.LORE, ItemLore.lore(lore));
        item.setData(DataComponentTypes.MAX_STACK_SIZE, 1);
        item.setData(DataComponentTypes.TOOLTIP_DISPLAY, TooltipDisplay.tooltipDisplay()
                .addHiddenComponents(DataComponentTypes.ATTRIBUTE_MODIFIERS, DataComponentTypes.ENCHANTMENTS)
                .build());
        return item;
    }

    public UUID owner() {
        return owner;
    }

    /** Candidate ability ids in display order. */
    public List<String> candidates() {
        return candidates;
    }

    /** The ability shown in {@code rawSlot} of the menu, or null. */
    public @Nullable String candidateAt(int rawSlot) {
        for (int i = 0; i < SLOTS.length && i < candidates.size(); i++) {
            if (SLOTS[i] == rawSlot) {
                return candidates.get(i);
            }
        }
        return null;
    }

    /** The menu slot of a candidate (for tests and tooling), or -1. */
    public int slotOf(String abilityId) {
        int index = candidates.indexOf(abilityId);
        return index < 0 ? -1 : SLOTS[index];
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
