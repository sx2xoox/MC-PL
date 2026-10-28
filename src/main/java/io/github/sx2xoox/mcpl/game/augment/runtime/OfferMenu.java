package io.github.sx2xoox.mcpl.game.augment.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import io.github.sx2xoox.mcpl.core.ui.Messages;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentInfo;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentTier;
import io.github.sx2xoox.mcpl.game.augment.api.Params;
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
 * The card menu of one offer: a 3-row chest with up to three augment cards, a reroll button and a clock. Clicks are
 * handled by the match listener, which recognises the menu by its holder.
 */
public final class OfferMenu implements InventoryHolder {

    /** Slots of the cards, left to right. */
    static final int[] CARD_SLOTS = {11, 13, 15};
    static final int REROLL_SLOT = 22;
    static final int CLOCK_SLOT = 4;
    private static final int SIZE = 27;
    /** Characters per lore line of a card description. */
    private static final int WRAP = 26;

    private final UUID owner;
    private final Offer offer;
    private final Inventory inventory;
    private final Messages messages;

    OfferMenu(UUID owner, Offer offer, Messages messages, Function<AugmentInfo, Params> params,
              int rerolls, long secondsLeft) {
        this.owner = owner;
        this.offer = offer;
        this.messages = messages;
        this.inventory = Bukkit.createInventory(this, SIZE, messages.get("offer.menu-title",
                Messages.tag("tier", tierName(messages, offer.tier())), Messages.tag("source", offer.source())));
        ItemStack filler = ItemStack.of(Material.BLACK_STAINED_GLASS_PANE);
        filler.setData(DataComponentTypes.TOOLTIP_DISPLAY, TooltipDisplay.tooltipDisplay().hideTooltip(true).build());
        for (int slot = 0; slot < SIZE; slot++) {
            inventory.setItem(slot, filler.clone());
        }
        render(params, rerolls);
        updateClock(secondsLeft);
    }

    /** (Re)draws the cards of the offer and the reroll button (after a reroll). */
    void render(Function<AugmentInfo, Params> params, int rerolls) {
        List<AugmentInfo> cards = offer.cards();
        for (int i = 0; i < CARD_SLOTS.length; i++) {
            if (i < cards.size()) {
                inventory.setItem(CARD_SLOTS[i], card(messages, cards.get(i), params.apply(cards.get(i)), true));
            } else {
                ItemStack empty = ItemStack.of(Material.GRAY_STAINED_GLASS_PANE);
                empty.setData(DataComponentTypes.TOOLTIP_DISPLAY,
                        TooltipDisplay.tooltipDisplay().hideTooltip(true).build());
                inventory.setItem(CARD_SLOTS[i], empty);
            }
        }
        inventory.setItem(REROLL_SLOT, rerollButton(rerolls));
    }

    public UUID owner() {
        return owner;
    }

    public Offer offer() {
        return offer;
    }

    /** The card in {@code rawSlot}, or null. */
    public @Nullable AugmentInfo cardAt(int rawSlot) {
        List<AugmentInfo> cards = offer.cards();
        for (int i = 0; i < CARD_SLOTS.length && i < cards.size(); i++) {
            if (CARD_SLOTS[i] == rawSlot) {
                return cards.get(i);
            }
        }
        return null;
    }

    /** The menu slot of a card (tests and tooling), or -1. */
    public int slotOf(String augmentId) {
        List<AugmentInfo> cards = offer.cards();
        for (int i = 0; i < CARD_SLOTS.length && i < cards.size(); i++) {
            if (cards.get(i).id().equals(augmentId)) {
                return CARD_SLOTS[i];
            }
        }
        return -1;
    }

    /** Shows the seconds left on the clock item. */
    void updateClock(long secondsLeft) {
        ItemStack clock = ItemStack.of(Material.CLOCK, (int) Math.max(1, Math.min(64, secondsLeft)));
        clock.setData(DataComponentTypes.ITEM_NAME, messages.get("offer.clock", Messages.tag("seconds", secondsLeft))
                .decoration(TextDecoration.ITALIC, false));
        inventory.setItem(CLOCK_SLOT, clock);
    }

    private ItemStack rerollButton(int rerolls) {
        ItemStack item = ItemStack.of(rerolls > 0 ? offer.tier().icon() : Material.BARRIER);
        item.setData(DataComponentTypes.ITEM_NAME, (rerolls > 0
                ? messages.get("offer.reroll-name", Messages.tag("count", rerolls))
                : messages.get("offer.reroll-none")).decoration(TextDecoration.ITALIC, false));
        item.setData(DataComponentTypes.LORE, ItemLore.lore(italicFree(messages.lines("offer.reroll-lore"))));
        return item;
    }

    /** A card item: the augment's icon, its name in the tier color, kind, description and a pick hint. */
    static ItemStack card(Messages messages, AugmentInfo info, Params params, boolean pickHint) {
        ItemStack item = ItemStack.of(info.icon());
        item.setData(DataComponentTypes.ITEM_NAME, name(info).decoration(TextDecoration.BOLD, true)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(messages.get("offer.card-kind", Messages.tag("tier", tierName(messages, info.tier())),
                Messages.tag("kind", info.kind().koreanName())));
        lore.add(Component.empty());
        for (String line : wrap(params.render(info.description()), WRAP)) {
            lore.add(messages.get("offer.card-line", Messages.tag("text", line)));
        }
        if (pickHint) {
            lore.add(Component.empty());
            lore.add(messages.get("offer.card-pick"));
        }
        item.setData(DataComponentTypes.LORE, ItemLore.lore(italicFree(lore)));
        item.setData(DataComponentTypes.MAX_STACK_SIZE, 1);
        if (info.tier() == AugmentTier.PRISM) {
            item.setData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        }
        item.setData(DataComponentTypes.TOOLTIP_DISPLAY, TooltipDisplay.tooltipDisplay()
                .addHiddenComponents(DataComponentTypes.ATTRIBUTE_MODIFIERS, DataComponentTypes.ENCHANTMENTS)
                .build());
        return item;
    }

    /** The augment's name in its tier color. */
    static Component name(AugmentInfo info) {
        return Component.text(info.name(), info.tier().color());
    }

    static Component tierName(Messages messages, AugmentTier tier) {
        return messages.get("tier." + tier.key());
    }

    private static List<Component> italicFree(List<Component> lines) {
        List<Component> out = new ArrayList<>(lines.size());
        for (Component line : lines) {
            out.add(line.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
        }
        return out;
    }

    /** Splits {@code text} into lines of about {@code width} characters at spaces. */
    static List<String> wrap(String text, int width) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            if (!line.isEmpty() && line.length() + 1 + word.length() > width) {
                lines.add(line.toString());
                line.setLength(0);
            }
            if (!line.isEmpty()) {
                line.append(' ');
            }
            line.append(word);
        }
        if (!line.isEmpty()) {
            lines.add(line.toString());
        }
        return lines;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
