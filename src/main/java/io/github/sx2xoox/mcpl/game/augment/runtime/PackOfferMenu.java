package io.github.sx2xoox.mcpl.game.augment.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import io.github.sx2xoox.mcpl.core.ui.Messages;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentInfo;
import io.github.sx2xoox.mcpl.game.augment.api.Params;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * The resource pack pick screen of one offer ({@code augments.ui: PACK}, for players who loaded the MCPL pack): a
 * 6-row chest whose title draws the card art ({@link PackScreen}). Every slot of a card holds an invisible hitbox item
 * with the card's description as a tooltip in the tier's style; the bottom row has the reroll and "later" buttons and
 * a clock counting down. The title shows the cards, so a reroll opens a new menu; a menu showing older cards than the
 * offer's picks nothing.
 */
public final class PackOfferMenu implements CardMenu {

    private final UUID owner;
    private final Offer offer;
    private final int revision;
    private final Messages messages;
    private final Inventory inventory;

    PackOfferMenu(UUID owner, Offer offer, Messages messages, Function<AugmentInfo, Params> params, int rerolls,
                  long secondsLeft, int queued) {
        this.owner = owner;
        this.offer = offer;
        this.revision = offer.revision();
        this.messages = messages;
        this.inventory = Bukkit.createInventory(this, PackScreen.SIZE, title(messages, offer, rerolls, queued));
        List<AugmentInfo> cards = offer.cards();
        int count = Math.min(cards.size(), OfferMenu.MAX_CARDS);
        for (int i = 0; i < count; i++) {
            AugmentInfo info = cards.get(i);
            ItemStack hitbox = hitbox(OfferMenu.card(messages, info, params.apply(info), true), info);
            List<Integer> slots = PackScreen.slotsOf(i, count);
            for (int slot : slots) {
                inventory.setItem(slot, hitbox.clone());
            }
            if (PackScreen.compact(count)) { // the row's icon is the real item
                ItemStack icon = OfferMenu.card(messages, info, params.apply(info), true);
                icon.setData(DataComponentTypes.TOOLTIP_STYLE, tooltipStyle(info));
                inventory.setItem(slots.getFirst(), icon);
            }
        }
        ItemStack reroll = blank(rerolls > 0
                ? messages.get("offer.reroll-name", Messages.tag("count", rerolls))
                : messages.get("offer.reroll-none"), messages.lines("offer.reroll-lore"));
        for (int slot : PackScreen.REROLL_SLOTS) {
            inventory.setItem(slot, reroll.clone());
        }
        ItemStack later = blank(messages.get("offer.pack-later-name"), messages.lines("offer.pack-later-lore"));
        for (int slot : PackScreen.LATER_SLOTS) {
            inventory.setItem(slot, later.clone());
        }
        updateClock(secondsLeft);
    }

    /** The title: the art of {@code offer}'s cards ({@link PackScreen#title}). */
    static Component title(Messages messages, Offer offer, int rerolls, int queued) {
        List<PackScreen.CardView> views = new ArrayList<>();
        for (AugmentInfo info : offer.cards()) {
            views.add(new PackScreen.CardView(info.tier(), info.tier().koreanName(), info.name(),
                    info.kind().koreanName(), PackScreen.icon(info.icon(), info.tier()), info.icon()));
        }
        Component header = queued > 0
                ? messages.get("offer.pack-header-queued", Messages.tag("source", offer.source()),
                Messages.tag("count", queued))
                : messages.get("offer.pack-header", Messages.tag("source", offer.source()));
        int headerWidth = OfferDialog.width(plain(header), false);
        String reroll = plain(rerolls > 0 ? messages.get("offer.pack-reroll", Messages.tag("count", rerolls))
                : messages.get("offer.pack-reroll-none"));
        return PackScreen.title(views, header, headerWidth, rerolls > 0, reroll,
                plain(messages.get("offer.pack-later")));
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    /** A card's hitbox: the card item (name, description lore) drawn invisible, with the tier's tooltip. */
    private static ItemStack hitbox(ItemStack card, AugmentInfo info) {
        card.setData(DataComponentTypes.ITEM_MODEL, PackScreen.BLANK_MODEL);
        card.setData(DataComponentTypes.TOOLTIP_STYLE, tooltipStyle(info));
        card.unsetData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE);
        return card;
    }

    private static Key tooltipStyle(AugmentInfo info) {
        return Key.key("mcpl", info.tier().key());
    }

    private static ItemStack blank(Component name, List<Component> lore) {
        ItemStack item = ItemStack.of(Material.PAPER);
        item.setData(DataComponentTypes.ITEM_MODEL, PackScreen.BLANK_MODEL);
        item.setData(DataComponentTypes.ITEM_NAME, name.decoration(TextDecoration.ITALIC, false));
        List<Component> lines = new ArrayList<>(lore.size());
        for (Component line : lore) {
            lines.add(line.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
        }
        item.setData(DataComponentTypes.LORE, ItemLore.lore(lines));
        return item;
    }

    @Override
    public UUID owner() {
        return owner;
    }

    @Override
    public Offer offer() {
        return offer;
    }

    /** The revision of the offer's cards this menu shows. */
    public int revision() {
        return revision;
    }

    @Override
    public @Nullable AugmentInfo cardAt(int rawSlot) {
        if (offer.revision() != revision) {
            return null;
        }
        List<AugmentInfo> cards = offer.cards();
        int card = PackScreen.cardAt(rawSlot, Math.min(cards.size(), OfferMenu.MAX_CARDS));
        return card < 0 ? null : cards.get(card);
    }

    @Override
    public boolean isReroll(int rawSlot) {
        return PackScreen.isReroll(rawSlot);
    }

    @Override
    public boolean isLater(int rawSlot) {
        return PackScreen.isLater(rawSlot);
    }

    @Override
    public void updateClock(long secondsLeft) {
        ItemStack clock = ItemStack.of(Material.CLOCK, (int) Math.max(1, Math.min(64, secondsLeft)));
        clock.setData(DataComponentTypes.ITEM_NAME, messages.get("offer.clock", Messages.tag("seconds", secondsLeft))
                .decoration(TextDecoration.ITALIC, false));
        inventory.setItem(PackScreen.CLOCK_SLOT, clock);
    }

    @Override
    public boolean redraw(Function<AugmentInfo, Params> params, int rerolls) {
        return false;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
