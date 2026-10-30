package io.github.sx2xoox.mcpl.game.augment.runtime;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.github.sx2xoox.mcpl.game.augment.api.AugmentTier;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.jspecify.annotations.Nullable;

/**
 * Layout of the resource pack pick screen ({@code augments.ui: PACK}), pure so it can be tested: a 6-row chest whose
 * title is a row of custom-font glyphs from the MCPL resource pack (tools/resourcepack/generate_pack.py draws them).
 * The title starts at x 8 of the chest; glyphs move a cursor by their advance, so every element is reached by
 * negative/positive space glyphs from the previous one ({@link Cursor}). In order:
 * <ol>
 *   <li>the background (header "증강 선택", card wells, button bar, timer socket) over the top part of the chest;</li>
 *   <li>up to three tall cards (tier frame, big icon, tier, name in up to two lines, kind), each over 3 columns x 5 rows
 *   of slots; four or five cards are compact rows (one per slot row, the icon is the real item in column 0);</li>
 *   <li>the reroll and "later" buttons in the bottom slot row, with their labels;</li>
 *   <li>the header line (source of the offer) in the default font, last, as its width is only estimated.</li>
 * </ol>
 * Items in the slots are drawn above the title: invisible hitbox items (model {@link #BLANK_MODEL}) give each card its
 * click area and description tooltip, and the clock in {@link #CLOCK_SLOT} counts down.
 *
 * <p>The geometry here must match the generator script.
 */
public final class PackScreen {

    public static final Key FONT = Key.key("mcpl", "offer");
    public static final Key ICON_FONT = Key.key("mcpl", "offer_icon");
    /** Item model without geometry: hitbox items that only show a tooltip. */
    public static final Key BLANK_MODEL = Key.key("mcpl", "blank");

    public static final int SIZE = 54;
    public static final int CLOCK_SLOT = 49;
    static final int[] REROLL_SLOTS = {45, 46, 47};
    static final int[] LATER_SLOTS = {51, 52, 53};
    /** Rows of a tall card (the sixth row is the button bar). */
    static final int CARD_ROWS = 5;

    // geometry in GUI pixels relative to the chest (the generator uses the same numbers)
    static final int TITLE_X = 8;
    static final int BG_X = -8;
    static final int BG_TILE_W = 96;
    static final int CARD_W = 52;
    static final int STRIP_X = 8;
    static final int STRIP_HALF_W = 80;
    static final int REROLL_X = 8;
    static final int LATER_X = 116;
    static final int BUTTON_W = 52;
    /** Usable text width inside a card. */
    static final int CARD_TEXT_W = 46;
    static final int ICON_SIZE = 32;

    // text lines (fonts mcpl:line/<top>)
    static final int LINE_TIER = 22;
    static final int LINE_NAME_1 = 67;
    static final int LINE_NAME_ONE = 72;
    static final int LINE_NAME_2 = 76;
    static final int LINE_KIND = 94;
    static final int LINE_BUTTON = 112;
    static final int[] LINE_ROWS = {22, 40, 58, 76, 94};

    // private use characters of the pack
    static final char SPACE_NEG = '';
    static final char SPACE_POS = '';
    static final char BG_TILES = '';
    static final char CARD = '';
    static final char BUTTON_REROLL = '';
    static final char BUTTON_REROLL_NONE = '';
    static final char BUTTON_LATER = '';
    static final char STRIP = '';

    static final TextColor KIND_COLOR = TextColor.color(0xA8A8B8);
    static final TextColor BUTTON_COLOR = NamedTextColor.WHITE;

    /** Advances of '!'..'~' in the vanilla ASCII font minus one (measured from the 26.3 client by the generator). */
    private static final String ASCII = "135555133351515555555555511454565555555535555555555555555535355"
            + "2555554551542555555535555553136";
    private static final String SYMBOLS_HALF = "·…×";
    private static final String SYMBOLS_FULL = "◆★♥▶→";
    private static final CharsetEncoder KS_X_1001 = java.nio.charset.Charset.forName("EUC-KR").newEncoder();

    private PackScreen() {
    }

    /** One card as the screen draws it. */
    public record CardView(AugmentTier tier, String tierName, String name, String kind, @Nullable Glyph icon,
                           Material item) {
    }

    /** A glyph of the icon font and its advance. */
    public record Glyph(char character, int advance) {
    }

    // ── slots ────────────────────────────────────────────────────────────────────────────────────────────────────

    /** Cards of up to three are tall cards; more are compact rows. */
    public static boolean compact(int cards) {
        return cards > 3;
    }

    /** Card position (column of three slot columns, 0..2) of card {@code index} of {@code count} tall cards. */
    static int position(int index, int count) {
        return switch (count) {
            case 1 -> 1;
            case 2 -> index == 0 ? 0 : 2;
            default -> index;
        };
    }

    /** The card a raw slot of the top inventory picks, or -1. */
    public static int cardAt(int rawSlot, int count) {
        if (rawSlot < 0 || rawSlot >= CARD_ROWS * 9 || count <= 0) {
            return -1;
        }
        int row = rawSlot / 9;
        int column = rawSlot % 9;
        if (compact(count)) {
            return row < count ? row : -1;
        }
        int position = column / 3;
        for (int i = 0; i < count; i++) {
            if (position(i, count) == position) {
                return i;
            }
        }
        return -1;
    }

    /** The raw slots of card {@code index} (all of them pick it). */
    public static List<Integer> slotsOf(int index, int count) {
        List<Integer> slots = new ArrayList<>();
        for (int slot = 0; slot < CARD_ROWS * 9; slot++) {
            if (cardAt(slot, count) == index) {
                slots.add(slot);
            }
        }
        return slots;
    }

    public static boolean isReroll(int rawSlot) {
        return contains(REROLL_SLOTS, rawSlot);
    }

    public static boolean isLater(int rawSlot) {
        return contains(LATER_SLOTS, rawSlot);
    }

    private static boolean contains(int[] slots, int slot) {
        for (int each : slots) {
            if (each == slot) {
                return true;
            }
        }
        return false;
    }

    // ── title ────────────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * The title of the screen.
     *
     * @param header      the header line (default font), e.g. "4번째 라운드 · 다음 증강 1개"
     * @param headerWidth its estimated width in pixels
     * @param rerollLabel "리롤 2" or "리롤 없음"
     */
    public static Component title(List<CardView> cards, Component header, int headerWidth, boolean canReroll,
                                  String rerollLabel, String laterLabel) {
        Cursor cursor = new Cursor();
        for (int i = 0; i < 4; i++) {
            cursor.moveTo(BG_X + (i % 2) * BG_TILE_W);
            cursor.glyph((char) (BG_TILES + i), FONT, BG_TILE_W + 1, NamedTextColor.WHITE);
        }
        int count = Math.min(cards.size(), OfferMenu.MAX_CARDS);
        if (compact(count)) {
            for (int r = 0; r < count; r++) {
                CardView card = cards.get(r);
                for (int half = 0; half < 2; half++) {
                    cursor.moveTo(STRIP_X + half * STRIP_HALF_W);
                    cursor.glyph((char) (STRIP + r * 8 + card.tier().ordinal() * 2 + half), FONT, STRIP_HALF_W + 1,
                            NamedTextColor.WHITE);
                }
            }
        } else {
            for (int i = 0; i < count; i++) {
                CardView card = cards.get(i);
                int x = cardX(position(i, count));
                cursor.moveTo(x);
                cursor.glyph((char) (CARD + card.tier().ordinal()), FONT, CARD_W + 1, NamedTextColor.WHITE);
                Glyph icon = card.icon();
                if (icon != null) {
                    cursor.moveTo(x + (CARD_W - ICON_SIZE) / 2);
                    cursor.glyph(icon.character(), ICON_FONT, icon.advance(), NamedTextColor.WHITE);
                }
            }
        }
        cursor.moveTo(REROLL_X);
        cursor.glyph(canReroll ? BUTTON_REROLL : BUTTON_REROLL_NONE, FONT, BUTTON_W + 1, NamedTextColor.WHITE);
        cursor.moveTo(LATER_X);
        cursor.glyph(BUTTON_LATER, FONT, BUTTON_W + 1, NamedTextColor.WHITE);

        // text, after every glyph of known width
        if (compact(count)) {
            for (int r = 0; r < count; r++) {
                CardView card = cards.get(r);
                int top = LINE_ROWS[r];
                String kind = sanitize(card.kind());
                int kindX = STRIP_X + 2 * STRIP_HALF_W - 3 - (width(kind) - 1);
                String name = fit(sanitize(card.name()), kindX - 6 - 30);
                cursor.text(name, top, 30, card.tier().color());
                cursor.text(kind, top, kindX, KIND_COLOR);
            }
        } else {
            for (int i = 0; i < count; i++) {
                CardView card = cards.get(i);
                int center = cardX(position(i, count)) + CARD_W / 2;
                cursor.centred(sanitize(card.tierName()), LINE_TIER, center, card.tier().color());
                List<String> lines = wrap(sanitize(card.name()), CARD_TEXT_W, 2);
                if (lines.size() == 1) {
                    cursor.centred(lines.getFirst(), LINE_NAME_ONE, center, card.tier().color());
                } else {
                    cursor.centred(lines.get(0), LINE_NAME_1, center, card.tier().color());
                    cursor.centred(lines.get(1), LINE_NAME_2, center, card.tier().color());
                }
                cursor.centred(fit(sanitize(card.kind()), CARD_TEXT_W), LINE_KIND, center, KIND_COLOR);
            }
        }
        cursor.centred(fit(sanitize(rerollLabel), BUTTON_W - 14), LINE_BUTTON, REROLL_X + BUTTON_W / 2 + 4,
                BUTTON_COLOR);
        cursor.centred(fit(sanitize(laterLabel), BUTTON_W - 6), LINE_BUTTON, LATER_X + BUTTON_W / 2, BUTTON_COLOR);

        cursor.moveTo(88 - headerWidth / 2);
        cursor.parts.add(header);
        return Component.text().append(cursor.parts).build();
    }

    static int cardX(int position) {
        return 8 + 54 * position;
    }

    /** Places glyphs by moving a cursor with space glyphs; tracks where each element was put (tests). */
    static final class Cursor {

        final List<Component> parts = new ArrayList<>();
        /** x of every glyph or text run placed, in order. */
        final List<Integer> placed = new ArrayList<>();
        int x = TITLE_X;

        void moveTo(int target) {
            int delta = target - x;
            if (delta != 0) {
                parts.add(Component.text(spaces(delta)).font(FONT));
                x = target;
            }
        }

        void glyph(char character, Key font, int advance, TextColor color) {
            placed.add(x);
            parts.add(Component.text(String.valueOf(character), color).font(font));
            x += advance;
        }

        void text(String text, int top, int at, TextColor color) {
            if (text.isEmpty()) {
                return;
            }
            moveTo(at);
            placed.add(x);
            parts.add(Component.text(text, color).font(lineFont(top)).decoration(TextDecoration.BOLD, false));
            x += width(text);
        }

        void centred(String text, int top, int center, TextColor color) {
            text(text, top, center - (width(text) - 1) / 2, color);
        }
    }

    /** Font of the text line whose glyph tops are at GUI y {@code top}. */
    static Key lineFont(int top) {
        return Key.key("mcpl", "line/" + top);
    }

    /** Space glyphs that move the cursor by {@code delta} pixels (powers of two, at most 512 each). */
    static String spaces(int delta) {
        StringBuilder out = new StringBuilder();
        char base = delta < 0 ? SPACE_NEG : SPACE_POS;
        int left = Math.abs(delta);
        while (left >= 1024) {
            out.append((char) (base + 9));
            left -= 512;
        }
        for (int bit = 9; bit >= 0; bit--) {
            if ((left & (1 << bit)) != 0) {
                out.append((char) (base + bit));
            }
        }
        return out.toString();
    }

    /** Advance of the space glyphs of {@link #spaces} (tests). */
    static int spaceAdvance(char c) {
        if (c >= SPACE_NEG && c < SPACE_NEG + 10) {
            return -(1 << (c - SPACE_NEG));
        }
        if (c >= SPACE_POS && c < SPACE_POS + 10) {
            return 1 << (c - SPACE_POS);
        }
        throw new IllegalArgumentException("not a space glyph: " + (int) c);
    }

    // ── text in the line fonts ───────────────────────────────────────────────────────────────────────────────────

    /** The line fonts have ASCII, KS X 1001 Hangul and a few symbols; anything else becomes '?'. */
    static String sanitize(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            out.append(drawable(c) ? c : '?');
        }
        return out.toString();
    }

    static boolean drawable(char c) {
        if (c >= 0x20 && c <= 0x7E) {
            return true;
        }
        if (c >= 0xAC00 && c <= 0xD7A3) {
            synchronized (KS_X_1001) {
                return KS_X_1001.canEncode(c);
            }
        }
        return SYMBOLS_HALF.indexOf(c) >= 0 || SYMBOLS_FULL.indexOf(c) >= 0;
    }

    /** Advance of a (sanitized) text in the line fonts, in pixels. */
    static int width(String text) {
        int width = 0;
        for (int i = 0; i < text.length(); i++) {
            width += advance(text.charAt(i));
        }
        return width;
    }

    static int advance(char c) {
        if (c == ' ') {
            return 4;
        }
        if (c >= 0x21 && c <= 0x7E) {
            return ASCII.charAt(c - 0x21) - '0' + 1;
        }
        if (SYMBOLS_HALF.indexOf(c) >= 0) {
            return 5;
        }
        return 9; // Hangul, full-width symbols
    }

    /** Wraps into at most {@code maxLines} lines of at most {@code width} visible pixels; "…" if cut. */
    static List<String> wrap(String text, int width, int maxLines) {
        List<String> lines = new ArrayList<>();
        String rest = text.strip();
        while (!rest.isEmpty() && lines.size() < maxLines) {
            int fit = fitCount(rest, width);
            int cut = rest.length();
            if (fit < rest.length()) {
                int space = rest.lastIndexOf(' ', fit);
                cut = space > 0 ? space : Math.max(fit, 1);
            }
            lines.add(rest.substring(0, cut).strip());
            rest = rest.substring(cut).strip();
        }
        if (!rest.isEmpty() && !lines.isEmpty()) {
            lines.set(lines.size() - 1, fit(lines.getLast() + " " + rest, width));
        }
        return lines;
    }

    /** {@code text} cut to {@code width} visible pixels, ending in "…" when cut. */
    static String fit(String text, int width) {
        if (width(text) - 1 <= width) {
            return text;
        }
        int keep = fitCount(text, width - advance('…'));
        return text.substring(0, keep).stripTrailing() + "…";
    }

    private static int fitCount(String text, int width) {
        int used = 0;
        for (int i = 0; i < text.length(); i++) {
            used += advance(text.charAt(i));
            if (used - 1 > width) {
                return i;
            }
        }
        return text.length();
    }

    // ── icons ────────────────────────────────────────────────────────────────────────────────────────────────────

    private static @Nullable Map<String, Glyph> icons;

    /** The big icon glyph of {@code material}, falling back to the tier icon; null if the table is missing. */
    public static @Nullable Glyph icon(Material material, AugmentTier tier) {
        Map<String, Glyph> table = icons();
        String sprite = OfferDialog.sprite(material);
        Glyph glyph = sprite == null ? null : table.get(sprite);
        if (glyph == null) {
            String fallback = OfferDialog.sprite(tier.icon());
            glyph = fallback == null ? null : table.get(fallback);
        }
        return glyph;
    }

    /** The icon table written by the generator ({@code augment-pack-icons.tsv}: texture, code point, advance). */
    static synchronized Map<String, Glyph> icons() {
        if (icons == null) {
            Map<String, Glyph> table = new HashMap<>();
            try (InputStream in = PackScreen.class.getResourceAsStream("/augment-pack-icons.tsv")) {
                if (in != null) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        String[] cells = line.split("\t");
                        if (line.startsWith("#") || cells.length != 3) {
                            continue;
                        }
                        table.put(cells[0], new Glyph((char) Integer.parseInt(cells[1], 16),
                                Integer.parseInt(cells[2])));
                    }
                }
            } catch (IOException | NumberFormatException e) {
                table.clear();
            }
            icons = Map.copyOf(table);
        }
        return icons;
    }
}
