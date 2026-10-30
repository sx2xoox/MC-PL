package io.github.sx2xoox.mcpl.game.augment.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import io.github.sx2xoox.mcpl.core.ui.Messages;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentInfo;
import io.github.sx2xoox.mcpl.game.augment.api.Params;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.object.ObjectContents;
import org.bukkit.Material;
import org.jspecify.annotations.Nullable;

/**
 * What the augment pick dialog of one offer shows, built from the offer alone (no Paper dialog types, so it can be
 * checked in tests). League of Legends ARAM style: the cards stand side by side as tall cards. Paper dialogs cannot
 * lay bodies out in columns, so each card is a column of the multi-action button grid ({@link #grid()}, row-major,
 * {@link #columns()} wide): row r of column c is line r of card c (tier, icon and name, kind, the description wrapped
 * into a few short lines, "▶ 선택"), and every button of a column picks that card and shows its full description as
 * the tooltip. Above the grid the header and countdown are body text; below it a reroll button while rerolls remain,
 * and a "later" exit button (also Esc). {@link PaperOfferDialogs} turns it into a Paper {@code Dialog}. It is tied to
 * the offer's {@link Offer#id()} and {@link Offer#revision()}: a click on a dialog that shows anything else is stale.
 *
 * @param offerId   the offer shown
 * @param revision  the revision of the offer's cards shown
 * @param title     "증강 선택 · 골드"
 * @param header    the source of the offer and, if more are waiting, how many
 * @param cards     the cards, left to right
 * @param countdown the seconds left (as of opening) and what happens then
 * @param reroll    the reroll button label, or null without rerolls left
 * @param cardWidth width of each card column (each of its buttons)
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
        int cardWidth
) {

    /** Seconds left at which an open dialog is shown again with the new countdown (and in red). */
    static final List<Long> REFRESH_AT = List.of(10L, 5L);
    public static final int REROLL_WIDTH = 150;
    public static final int LATER_WIDTH = 150;
    /** Horizontal gap between the columns of a multi-action dialog (vanilla grid spacing). */
    static final int COLUMN_GAP = 2;
    /** Text margin of a button (both sides together), so a line never scrolls. */
    static final int BUTTON_PADDING = 10;
    /** Width of an inline sprite (icon) and the space after it. */
    static final int ICON_WIDTH = 8 + 1 + 4;
    static final String ELLIPSIS = "…";

    public OfferDialog {
        header = List.copyOf(header);
        cards = List.copyOf(cards);
    }

    /**
     * One card: a column of the grid.
     *
     * @param info    the augment
     * @param name    the name in the tier color, bold
     * @param lines   the card's lines top to bottom, one button each
     * @param tooltip every button's tooltip: the full description
     */
    public record Card(AugmentInfo info, Component name, List<Component> lines, Component tooltip) {

        public Card {
            lines = List.copyOf(lines);
        }
    }

    /** One button of the dialog. */
    public record Button(Component label, Component tooltip, int width, Action action) {
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

    /** Number of card rows of the grid: the most lines of any card. */
    public int rows() {
        int rows = 0;
        for (Card card : cards) {
            rows = Math.max(rows, card.lines().size());
        }
        return rows;
    }

    /** Width of the body text above the grid: as wide as the cards together. */
    public int bodyWidth() {
        return Math.min(1024, columns() * cardWidth + (columns() - 1) * COLUMN_GAP);
    }

    /**
     * The buttons of the multi-action grid, row-major ({@link #columns()} per row): the card rows (a card with fewer
     * lines gets empty buttons, so the columns stay aligned), then the reroll button if any. The exit button is
     * {@link #later()}.
     */
    public List<Button> grid() {
        List<Button> buttons = new ArrayList<>();
        int rows = rows();
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cards.size(); c++) {
                Card card = cards.get(c);
                Component label = r < card.lines().size() ? card.lines().get(r) : Component.empty();
                buttons.add(new Button(label, card.tooltip(), cardWidth, Action.pick(c)));
            }
        }
        if (reroll != null) {
            buttons.add(new Button(reroll, rerollTooltip, REROLL_WIDTH, Action.REROLL));
        }
        return buttons;
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
        int width = cardWidth(offer.cards().size());
        int text = width - BUTTON_PADDING;
        int descriptionLines = descriptionLines(offer.cards().size());
        List<Card> cards = new ArrayList<>();
        for (AugmentInfo info : offer.cards()) {
            String description = params.apply(info).render(info.description());
            Component kind = Component.text(info.kind().koreanName());
            Component cardTier = OfferMenu.tierName(messages, info.tier());
            Component name = OfferMenu.name(info).decoration(TextDecoration.BOLD, true);
            Component shortName = Component.text(wrap(info.name(), text - ICON_WIDTH, 1, true).getFirst(),
                    info.tier().color()).decoration(TextDecoration.BOLD, true);

            List<Component> lines = new ArrayList<>();
            lines.add(messages.get("offer.card-tier", Messages.tag("gem", Component.text("◆", info.tier().color())),
                    Messages.tag("tier", cardTier)));
            lines.add(messages.get("offer.card-name", Messages.tag("icon", icon(info.icon(), info.tier().color())),
                    Messages.tag("name", shortName)));
            lines.add(messages.get("offer.card-kind", Messages.tag("kind", kind)));
            List<String> wrapped = wrap(description, text, descriptionLines, false);
            for (int i = 0; i < descriptionLines; i++) {
                lines.add(i < wrapped.size()
                        ? messages.get("offer.card-line", Messages.tag("line", wrapped.get(i)))
                        : Component.empty());
            }
            lines.add(messages.get("offer.card-pick"));
            cards.add(new Card(info, name, lines,
                    messages.get("offer.dialog-card-tooltip", Messages.tag("name", name),
                            Messages.tag("tier", cardTier), Messages.tag("kind", kind),
                            Messages.tag("description", Component.text(description)))));
        }
        Component countdown = messages.get(secondsLeft <= REFRESH_AT.getLast()
                ? "offer.dialog-countdown-urgent" : "offer.dialog-countdown", Messages.tag("seconds", secondsLeft));
        return new OfferDialog(offer.id(), offer.revision(), title, messages.get("offer.dialog-external-title"),
                header, cards, countdown,
                rerolls > 0 ? messages.get("offer.dialog-reroll", Messages.tag("count", rerolls)) : null,
                messages.get("offer.dialog-reroll-tooltip"),
                messages.get("offer.dialog-later"), messages.get("offer.dialog-later-tooltip"),
                width);
    }

    /** Card columns get narrower with more cards so five still fit side by side on a small GUI (about 480 wide). */
    public static int cardWidth(int cards) {
        return switch (cards) {
            case 0, 1, 2, 3 -> 150;
            case 4 -> 114;
            default -> 92;
        };
    }

    /** Lines of description on a card: fewer on narrow cards (the tooltip has it all). */
    public static int descriptionLines(int cards) {
        return cards <= 3 ? 3 : 2;
    }

    // ── icons ───────────────────────────────────────────────────────────────

    private static final Key ITEMS = Key.key("minecraft", "items");
    private static final Key BLOCKS = Key.key("minecraft", "blocks");

    /** Items without a flat texture of their own name (animated): the texture to show instead. */
    private static final Map<Material, String> ITEM_SPRITES = Map.of(
            Material.COMPASS, "item/compass_16",
            Material.RECOVERY_COMPASS, "item/recovery_compass_16",
            Material.CLOCK, "item/clock_00");
    /** Blocks whose texture is not named after the block: the face to show. */
    private static final Map<Material, String> BLOCK_SPRITES = Map.of(
            Material.FURNACE, "block/furnace_front",
            Material.TNT, "block/tnt_side",
            Material.ANCIENT_DEBRIS, "block/ancient_debris_side",
            Material.CACTUS, "block/cactus_side",
            Material.SUNFLOWER, "block/sunflower_front",
            Material.SCULK_SENSOR, "block/sculk_sensor_top");
    /** Items drawn by an entity model (no texture to show): a symbol instead. */
    private static final List<Material> NO_SPRITE = List.of(Material.SHIELD, Material.TRIDENT, Material.CHEST);

    /**
     * The icon of an augment as an inline sprite (a text object component, 1.21.9+ clients): item textures from the
     * {@code minecraft:items} atlas, block textures from {@code minecraft:blocks}. Its fallback, and the icon of items
     * without a flat texture, is a "◆" in the tier color.
     */
    static Component icon(Material material, TextColor color) {
        Component symbol = Component.text("◆", color);
        String sprite = sprite(material);
        if (sprite == null) {
            return symbol;
        }
        Key atlas = sprite.startsWith("block/") ? BLOCKS : ITEMS;
        return Component.object(ObjectContents.sprite(atlas, Key.key("minecraft", sprite))).fallback(symbol);
    }

    /**
     * The flat texture that stands for {@code material} ({@code item/diamond_sword}, {@code block/tnt_side}), or null
     * for items drawn by an entity model. Also the big card icon of the resource pack screen ({@link PackScreen}).
     */
    public static @Nullable String sprite(Material material) {
        if (NO_SPRITE.contains(material)) {
            return null;
        }
        String sprite = ITEM_SPRITES.get(material);
        if (sprite == null) {
            sprite = BLOCK_SPRITES.get(material);
        }
        if (sprite == null) {
            boolean block = material.isBlock() && blockTextured(material);
            sprite = (block ? "block/" : "item/") + material.getKey().getKey();
        }
        return sprite;
    }

    /** Blocks whose item shows the block texture (most), as opposed to blocks with an item texture of their own. */
    private static boolean blockTextured(Material material) {
        return switch (material) {
            case BREWING_STAND, CAULDRON, HOPPER, CAKE, REPEATER, COMPARATOR, CAMPFIRE, SOUL_CAMPFIRE, BELL,
                 LANTERN, SOUL_LANTERN, IRON_DOOR, OAK_DOOR, FLOWER_POT, SUGAR_CANE, KELP, NETHER_WART -> false;
            default -> true;
        };
    }

    // ── text fitting ────────────────────────────────────────────────────────

    /**
     * Wraps {@code text} into at most {@code maxLines} lines of at most {@code width} pixels in the Minecraft font
     * (estimated by {@link #width(String, boolean)}): breaks at a space where one is in the second half of the line,
     * inside a word otherwise; if text is left over, the last line ends in "…".
     */
    public static List<String> wrap(String text, int width, int maxLines, boolean bold) {
        List<String> lines = new ArrayList<>();
        String rest = text.strip();
        while (!rest.isEmpty() && lines.size() < maxLines) {
            int fit = fit(rest, width, bold);
            int cut = rest.length();
            if (fit < rest.length()) {
                int space = rest.lastIndexOf(' ', fit);
                cut = space > 0 && space >= fit / 2 ? space : Math.max(fit, 1);
            }
            lines.add(rest.substring(0, cut).stripTrailing());
            rest = rest.substring(cut).stripLeading();
        }
        if (!rest.isEmpty() && !lines.isEmpty()) {
            String last = lines.getLast();
            String cut = last.substring(0, fit(last, width - width(ELLIPSIS, bold), bold)).stripTrailing();
            lines.set(lines.size() - 1, cut + ELLIPSIS);
        }
        return lines;
    }

    /** How many leading characters of {@code text} fit in {@code width} pixels. */
    private static int fit(String text, int width, boolean bold) {
        int used = 0;
        for (int i = 0; i < text.length(); i++) {
            used += width(text.charAt(i), bold);
            if (used > width) {
                return i;
            }
        }
        return text.length();
    }

    /** Estimated width of {@code text} in pixels in the Minecraft font. */
    public static int width(String text, boolean bold) {
        int width = 0;
        for (int i = 0; i < text.length(); i++) {
            width += width(text.charAt(i), bold);
        }
        return width;
    }

    /** Estimated advance of one character: ASCII as in the default font, Hangul and other wide glyphs 9. */
    static int width(char c, boolean bold) {
        int width;
        if (c >= 0x1100 && c <= 0x11FF || c >= 0x3000 && c <= 0x9FFF || c >= 0xAC00 && c <= 0xD7A3
                || c >= 0xFF00 && c <= 0xFFEF) {
            width = 9;
        } else {
            width = switch (c) {
                case 'i', '.', ',', ':', ';', '!', '|', '\'', '·' -> 2;
                case 'l', '`' -> 3;
                case ' ', 'I', 't', '[', ']', '(', ')', '{', '}', '"', '*' -> 4;
                case 'f', 'k', '<', '>' -> 5;
                case '%', '~', '@' -> 7;
                case '…' -> 8;
                default -> 6;
            };
        }
        return bold ? width + 1 : width;
    }
}
