package io.github.sx2xoox.mcpl.game.augment.runtime;

import java.util.UUID;
import java.util.function.Function;

import io.github.sx2xoox.mcpl.game.augment.api.AugmentInfo;
import io.github.sx2xoox.mcpl.game.augment.api.Params;
import org.bukkit.inventory.InventoryHolder;
import org.jspecify.annotations.Nullable;

/**
 * An inventory screen showing the cards of one offer: the chest menu ({@link OfferMenu}, {@code augments.ui: CHEST})
 * or the resource pack screen ({@link PackOfferMenu}, {@code PACK}). The match listener recognises it by its holder.
 */
public sealed interface CardMenu extends InventoryHolder permits OfferMenu, PackOfferMenu {

    UUID owner();

    Offer offer();

    /** The card a click on {@code rawSlot} picks, or null (also for a menu showing older cards than the offer's). */
    @Nullable AugmentInfo cardAt(int rawSlot);

    boolean isReroll(int rawSlot);

    /** The slot closes the menu without picking. */
    boolean isLater(int rawSlot);

    /** Shows the seconds left. */
    void updateClock(long secondsLeft);

    /**
     * Shows the offer's current cards and rerolls left.
     *
     * @return false if the menu cannot change in place and has to be opened again (its title draws the cards)
     */
    boolean redraw(Function<AugmentInfo, Params> params, int rerolls);
}
