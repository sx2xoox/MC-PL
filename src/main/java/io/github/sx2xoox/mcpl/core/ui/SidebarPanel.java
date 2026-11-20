package io.github.sx2xoox.mcpl.core.ui;

import java.util.ArrayList;
import java.util.List;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * The 장난감 왕국 panel behind a sidebar, for players who have the MCPL pack (font {@code mcpl:sidebar}, drawn by
 * {@code tools/assetgen/sidebar_panel.py}). The client draws the sidebar as a translucent grey box (a title row, then
 * one row per line) and the text on top of it; {@link #decorate} puts, in front of the title and of every line, a row
 * piece of that font as wide as the box (left cap, fill tiles, right cap) and negative spaces back to where the text
 * starts. The pieces cover the box; the text is drawn over them.
 *
 * <p>The box is as wide as the widest text. So the panel picks a width {@code W} that fits every line
 * ({@link TextWidth} estimate + slack) and the title is padded with spaces to exactly {@code W}: the box then is
 * {@code W + 4} wide, like the pieces. Players without the pack must never get these characters (they would see
 * boxes): call this only for {@code PackAudience#hasPack} viewers.
 */
public final class SidebarPanel {

    /** The font of the panel's glyphs. */
    public static final Key FONT = Key.key("mcpl", "sidebar");
    /** Width of a row's left and right cap. */
    public static final int CAP = 8;
    /** Fill tile widths, largest first (all multiples of the art's 8 px period). */
    public static final int[] TILES = {64, 32, 16, 8};
    /** Code points: negative spaces {@code NEG + i} = -2^i, positive {@code POS + i} = +2^i ({@code i = 0..8}). */
    public static final int NEG = 0xE000;
    public static final int POS = 0xE010;
    /** Row pieces: {@code ROW + 16 * style + k} (k: 0 left cap, 1 right cap, 2.. the {@link #TILES} from 8 up). */
    public static final int ROW = 0xE100;
    /** The wind-up key on the ribbon. */
    public static final int KEY = 0xE140;
    public static final int KEY_WIDTH = 11;
    /** The narrowest and widest text area. */
    public static final int MIN_WIDTH = 92;
    public static final int MAX_WIDTH = 252;
    /** Pixels kept free right of the widest line (the width is an estimate). */
    public static final int SLACK = 4;
    /** Pixels kept between the title and the ribbon's ends. */
    public static final int TITLE_MARGIN = 7;

    /** The rows of the panel (index into the {@link #ROW} code points). */
    public enum Style {
        BANNER, BODY, BOTTOM;

        int codePoint(int k) {
            return ROW + 16 * ordinal() + k;
        }
    }

    /** A decorated sidebar: the title and the lines to show. */
    public record Decorated(Component title, List<Component> lines) {
    }

    private SidebarPanel() {
    }

    /** The title and lines with the panel behind them. */
    public static Decorated decorate(Component title, List<? extends Component> lines) {
        int titleWidth = TextWidth.of(title);
        int widest = 0;
        for (Component line : lines) {
            widest = Math.max(widest, TextWidth.of(line));
        }
        int width = width(widest, titleWidth);
        int row = width + 4;

        Glyphs head = new Glyphs();
        head.moveTo(-2);
        head.row(Style.BANNER, row);
        head.moveTo(-2 + row / 2 - KEY_WIDTH / 2);
        head.glyph(KEY, KEY_WIDTH);
        int before = Math.max(0, (width - titleWidth) / 2);
        head.moveTo(before);
        Glyphs tail = new Glyphs();
        tail.cursor = before + titleWidth;
        tail.moveTo(Math.max(tail.cursor, width));
        Component decoratedTitle = Component.textOfChildren(head.component(), title, tail.component());

        List<Component> out = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            Glyphs prefix = new Glyphs();
            prefix.moveTo(-2);
            prefix.row(i == lines.size() - 1 ? Style.BOTTOM : Style.BODY, row);
            prefix.moveTo(0);
            out.add(Component.textOfChildren(prefix.component(), lines.get(i)));
        }
        return new Decorated(decoratedTitle, List.copyOf(out));
    }

    /**
     * The text width {@code W} of the panel: fits the widest line (+ {@link #SLACK}) and the title between the ribbon
     * ends, at least {@link #MIN_WIDTH}, at most {@link #MAX_WIDTH}, and {@code W + 4 - 2 * CAP} a multiple of 8 so the
     * fill tiles fill it exactly.
     */
    public static int width(int widestLine, int titleWidth) {
        int width = Math.max(MIN_WIDTH, Math.max(widestLine + SLACK, titleWidth + 2 * TITLE_MARGIN));
        width += Math.floorMod(4 - width, 8);
        return Math.min(width, MAX_WIDTH);
    }

    /** True for a code point of the panel's font (a space or a piece). */
    public static boolean isPanelGlyph(int codePoint) {
        return codePoint >= NEG && codePoint < POS + 16 || codePoint >= ROW && codePoint <= KEY;
    }

    /** Builds a run of panel glyphs, tracking the pen position (0 = where the sidebar text starts). */
    private static final class Glyphs {
        private final StringBuilder text = new StringBuilder();
        private int cursor;
        private int pending;

        void moveTo(int x) {
            pending += x - cursor;
            cursor = x;
        }

        /** A glyph {@code width} pixels wide (the client advances one more: taken back here). */
        void glyph(int codePoint, int width) {
            flush();
            text.appendCodePoint(codePoint);
            cursor += width;
            pending = -1;
        }

        /** A whole row of {@code style}, {@code width} wide, from the pen on. */
        void row(Style style, int width) {
            glyph(style.codePoint(0), CAP);
            int rest = width - 2 * CAP;
            for (int t = 0; t < TILES.length; t++) {
                while (rest >= TILES[t]) {
                    glyph(style.codePoint(2 + TILES.length - 1 - t), TILES[t]);
                    rest -= TILES[t];
                }
            }
            glyph(style.codePoint(1), CAP);
        }

        private void flush() {
            int n = pending;
            pending = 0;
            int base = n < 0 ? NEG : POS;
            n = Math.abs(n);
            for (int i = 8; i >= 0; i--) {
                while (n >= 1 << i) {
                    text.appendCodePoint(base + i);
                    n -= 1 << i;
                }
            }
        }

        Component component() {
            flush();
            return Component.text(text.toString())
                    .font(FONT)
                    .color(NamedTextColor.WHITE)
                    .decoration(TextDecoration.BOLD, false)
                    .decoration(TextDecoration.ITALIC, false);
        }
    }
}
