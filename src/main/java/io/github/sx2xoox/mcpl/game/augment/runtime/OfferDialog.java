package io.github.sx2xoox.mcpl.game.augment.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import io.github.sx2xoox.mcpl.core.ui.Messages;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentInfo;
import io.github.sx2xoox.mcpl.game.augment.api.Params;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * What the augment pick dialog of one offer shows, built from the offer alone (no Paper dialog types, so it can be
 * checked in tests): a title with the tier, a header, one icon row per card, a countdown, then the card buttons side
 * by side (one column per card), a reroll button while rerolls remain and a "later" exit button (also Esc).
 * {@link PaperOfferDialogs} turns it into a Paper {@code Dialog}. It is tied to the offer's {@link Offer#id()} and
 * {@link Offer#revision()}: a click on a dialog that shows anything else is stale.
 *
 * @param offerId     the offer shown
 * @param revision    the revision of the offer's cards shown
 * @param title       "증강 선택 · 골드"
 * @param header      the source of the offer and, if more are waiting, how many
 * @param cards       the cards, left to right
 * @param countdown   the seconds left (as of opening) and what happens then
 * @param reroll      the reroll button label, or null without rerolls left
 * @param buttonWidth width of each card button
 */
public record OfferDialog(
        long offerId,
        int revision,
        Component title,
        Component externalTitle,
        List<Component> header,
        List<Card> cards,
        Component countdown,
        @Nullable Component reroll,
        Component rerollTooltip,
        Component later,
        Component laterTooltip,
        int buttonWidth
) {

    /** Seconds left at which an open dialog is shown again with the new countdown (and in red). */
    static final List<Long> REFRESH_AT = List.of(10L, 5L);
    /** Width of the card text next to each icon and of the header lines. */
    public static final int TEXT_WIDTH = 300;
    public static final int REROLL_WIDTH = 150;
    public static final int LATER_WIDTH = 150;

    public OfferDialog {
        header = List.copyOf(header);
        cards = List.copyOf(cards);
    }

    /**
     * One card.
     *
     * @param info        the augment
     * @param icon        its icon item (name and description in the tooltip)
     * @param description the text next to the icon: the name in the tier color, the kind and the description
     * @param label       the button: the name in the tier color, bold
     * @param tooltip     the button's tooltip: the full description
     */
    public record Card(AugmentInfo info, ItemStack icon, Component description, Component label, Component tooltip) {
    }

    /** What a button of the dialog does. */
    public enum Kind {
        /** Picks {@link Action#card()}. */
        PICK,
        REROLL,
        /** Closes the dialog without picking (the exit button and Esc). */
        LATER
    }

    /** A click on the dialog. */
    public record Action(Kind kind, int card) {

        public static Action pick(int card) {
            return new Action(Kind.PICK, card);
        }

        public static final Action REROLL = new Action(Kind.REROLL, -1);
        public static final Action LATER = new Action(Kind.LATER, -1);
    }

    /** Number of columns of the button grid: one per card. */
    public int columns() {
        return Math.max(1, cards.size());
    }

    /**
     * The dialog of {@code offer}.
     *
     * @param rerolls     rerolls the player has left
     * @param secondsLeft seconds until a random card is granted
     * @param queued      offers waiting after this one
     */
    static OfferDialog of(Messages messages, Offer offer, Function<AugmentInfo, Params> params, int rerolls,
                          long secondsLeft, int queued) {
        Component tier = OfferMenu.tierName(messages, offer.tier());
        Component title = messages.get("offer.dialog-title", Messages.tag("tier", tier));
        List<Component> header = new ArrayList<>();
        header.add(messages.get("offer.dialog-header", Messages.tag("source", offer.source())));
        if (queued > 0) {
            header.add(messages.get("offer.dialog-queued", Messages.tag("count", queued)));
        }
        List<Card> cards = new ArrayList<>();
        for (AugmentInfo info : offer.cards()) {
            Params p = params.apply(info);
            Component name = OfferMenu.name(info).decoration(TextDecoration.BOLD, true);
            Component kind = Component.text(info.kind().koreanName());
            Component description = Component.text(p.render(info.description()));
            Component cardTier = OfferMenu.tierName(messages, info.tier());
            cards.add(new Card(info, OfferMenu.card(messages, info, p, false),
                    messages.get("offer.dialog-card", Messages.tag("name", name), Messages.tag("kind", kind),
                            Messages.tag("description", description)),
                    messages.get("offer.dialog-card-label", Messages.tag("name", name)),
                    messages.get("offer.dialog-card-tooltip", Messages.tag("name", name),
                            Messages.tag("tier", cardTier), Messages.tag("kind", kind),
                            Messages.tag("description", description))));
        }
        Component countdown = messages.get(secondsLeft <= REFRESH_AT.getLast()
                ? "offer.dialog-countdown-urgent" : "offer.dialog-countdown", Messages.tag("seconds", secondsLeft));
        return new OfferDialog(offer.id(), offer.revision(), title, messages.get("offer.dialog-external-title"),
                header, cards, countdown,
                rerolls > 0 ? messages.get("offer.dialog-reroll", Messages.tag("count", rerolls)) : null,
                messages.get("offer.dialog-reroll-tooltip"),
                messages.get("offer.dialog-later"), messages.get("offer.dialog-later-tooltip"),
                buttonWidth(cards.size()));
    }

    /** Card buttons get narrower with more cards so five still fit side by side on a small GUI. */
    static int buttonWidth(int cards) {
        return switch (cards) {
            case 0, 1, 2, 3 -> 130;
            case 4 -> 110;
            default -> 92;
        };
    }
}
