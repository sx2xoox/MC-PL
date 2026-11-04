package io.github.sx2xoox.mcpl.core.ui;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * An estimate of how wide (GUI pixels at scale 1) the client's default font draws a component, for layouts that must
 * fit (sidebar lines, the {@link SidebarPanel}). ASCII uses the vanilla glyph advances, Hangul and CJK the unifont
 * full width (9), anything else 9 as well; bold adds 1 per character. Text in another font counts as nothing (those
 * are this plugin's own glyphs, measured by whoever adds them). It is an estimate: keep a few pixels of slack.
 */
public final class TextWidth {

    /** Advance of a Hangul syllable (and of an unknown character). */
    public static final int WIDE = 9;

    /** Advances of the printable ASCII characters 0x20..0x7E in the vanilla font. */
    private static final int[] ASCII = new int[0x7F];

    static {
        java.util.Arrays.fill(ASCII, 6);
        String[] groups = {" 4", "! 2", "\" 4", "' 2", "( 4", ") 4", "* 4", ", 2", ". 2", ": 2", "; 2", "< 5",
            "> 5", "@ 7", "I 4", "[ 4", "] 4", "` 3", "f 5", "i 2", "k 5", "l 3", "t 4", "{ 4", "| 2", "} 4", "~ 7"};
        for (String group : groups) {
            ASCII[group.charAt(0)] = group.charAt(group.length() - 1) - '0';
        }
    }

    private TextWidth() {
    }

    /** The estimated width of {@code component} (its children included). */
    public static int of(Component component) {
        return of(component, Style.empty());
    }

    /** The estimated width of plain {@code text} ({@code bold} or not). */
    public static int of(String text, boolean bold) {
        int width = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            width += advance(cp) + (bold ? 1 : 0);
        }
        return width;
    }

    private static int of(Component component, Style parent) {
        // inherited values: the component's own win, then the parent's
        Style style = component.style().merge(parent, Style.Merge.Strategy.IF_ABSENT_ON_TARGET);
        int width = 0;
        Key font = style.font();
        if (component instanceof TextComponent text && (font == null || font.equals(Key.key("default")))) {
            width += of(text.content(), style.decoration(TextDecoration.BOLD) == TextDecoration.State.TRUE);
        }
        for (Component child : component.children()) {
            width += of(child, style);
        }
        return width;
    }

    private static int advance(int cp) {
        if (cp < 0x20) {
            return 0;
        }
        if (cp < ASCII.length) {
            return ASCII[cp];
        }
        if (cp == 0xB7) {
            return 2; // ·
        }
        return WIDE;
    }
}
