package io.github.sx2xoox.mcpl.core.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The sidebar panel against its font in the pack sources ({@code tools/assetgen/sidebar_panel.py}): every glyph it
 * writes exists with the width it assumes, the pieces of a row add up to the grey box's width, and the glyphs in front
 * of a line take no room (the text starts where it would without them).
 */
class SidebarPanelTest {

    private static final Path FONT = Path.of("src/main/resourcepack-src/assets/mcpl/font/sidebar.json");
    private static final Path TEXTURES = Path.of("src/main/resourcepack-src/assets/mcpl/textures");

    /** Client advance of every code point of the font: a space's, or a bitmap's rightmost drawn column + 1. */
    private static final Map<Integer, Integer> ADVANCE = new HashMap<>();
    /** Drawn width (image pixels) of every bitmap glyph. */
    private static final Map<Integer, Integer> DRAWN = new HashMap<>();

    @BeforeAll
    static void readFont() throws IOException {
        JsonObject font = JsonParser.parseString(Files.readString(FONT)).getAsJsonObject();
        for (JsonElement element : font.getAsJsonArray("providers")) {
            JsonObject provider = element.getAsJsonObject();
            switch (provider.get("type").getAsString()) {
                case "space" -> {
                    for (Map.Entry<String, JsonElement> e : provider.getAsJsonObject("advances").entrySet()) {
                        ADVANCE.put(e.getKey().codePointAt(0), e.getValue().getAsInt());
                    }
                }
                case "bitmap" -> {
                    String file = provider.get("file").getAsString();
                    assertTrue(file.startsWith("mcpl:"), file);
                    BufferedImage image = ImageIO.read(TEXTURES.resolve(file.substring(5)).toFile());
                    assertNotNull(image, file);
                    int height = provider.get("height").getAsInt();
                    int ascent = provider.get("ascent").getAsInt();
                    assertEquals(image.getHeight(), height, file + ": 1 image pixel = 1 GUI pixel");
                    assertTrue(ascent <= height, file + ": the client refuses an ascent above the height");
                    int cp = provider.getAsJsonArray("chars").get(0).getAsString().codePointAt(0);
                    int drawn = rightmost(image) + 1;
                    DRAWN.put(cp, drawn);
                    ADVANCE.put(cp, drawn + 1);
                }
                default -> throw new AssertionError("unexpected provider " + provider);
            }
        }
    }

    private static int rightmost(BufferedImage image) {
        for (int x = image.getWidth() - 1; x >= 0; x--) {
            for (int y = 0; y < image.getHeight(); y++) {
                if ((image.getRGB(x, y) >>> 24) != 0) {
                    return x;
                }
            }
        }
        return -1;
    }

    @Test
    void theFontHasEveryGlyphAtTheWidthTheCodeAssumes() {
        for (int i = 0; i <= 8; i++) {
            assertEquals(-(1 << i), ADVANCE.get(SidebarPanel.NEG + i));
            assertEquals(1 << i, ADVANCE.get(SidebarPanel.POS + i));
        }
        for (SidebarPanel.Style style : SidebarPanel.Style.values()) {
            assertEquals(SidebarPanel.CAP, DRAWN.get(style.codePoint(0)), style + " left cap");
            assertEquals(SidebarPanel.CAP, DRAWN.get(style.codePoint(1)), style + " right cap");
            for (int k = 0; k < SidebarPanel.TILES.length; k++) {
                int tile = SidebarPanel.TILES[SidebarPanel.TILES.length - 1 - k];
                assertEquals(tile, DRAWN.get(style.codePoint(2 + k)), style + " tile " + tile);
            }
        }
        assertEquals(SidebarPanel.KEY_WIDTH, DRAWN.get(SidebarPanel.KEY));
    }

    @Test
    void piecesCoverTheBoxAndTakeNoRoomBeforeTheText() {
        Component title = Component.text("초능력 공성전", NamedTextColor.WHITE, TextDecoration.BOLD);
        List<Component> lines = List.of(Component.text("점수 블루 12 : 8 레드"), Component.text("라운드 1 : 0"),
                Component.text("Lv.5 (32/180) 1200G"), Component.text("6레벨: 불바다 범위 +1블록"));
        SidebarPanel.Decorated panel = SidebarPanel.decorate(title, lines);
        int widest = lines.stream().mapToInt(TextWidth::of).max().orElseThrow();
        int width = SidebarPanel.width(widest, TextWidth.of(title));
        assertEquals(4, Math.floorMod(width, 8), "the box (W + 4) is filled by whole tiles");
        assertTrue(width >= widest + SidebarPanel.SLACK);

        // the title is padded to exactly W, so the client's box is W + 4 wide and starts 2 left of the text
        assertEquals(width, advance(panel.title()), "title width");
        assertEquals(lines.size(), panel.lines().size());
        for (int i = 0; i < lines.size(); i++) {
            Component line = panel.lines().get(i);
            TextComponent prefix = (TextComponent) line.children().getFirst();
            assertEquals(SidebarPanel.FONT, prefix.font());
            assertEquals(lines.get(i), line.children().get(1), "the line itself follows");
            assertEquals(0, advance(prefix), "line " + i + ": the pieces end where they began");
            assertEquals(width + 4, rowWidth(prefix.content()), "line " + i + ": one row as wide as the box");
            int style = i == lines.size() - 1 ? SidebarPanel.Style.BOTTOM.ordinal() : SidebarPanel.Style.BODY.ordinal();
            assertTrue(prefix.content().codePoints().filter(cp -> cp >= SidebarPanel.ROW && cp < SidebarPanel.KEY)
                    .allMatch(cp -> (cp - SidebarPanel.ROW) / 16 == style), "line " + i + " style");
        }
    }

    @Test
    void theWidthIsBounded() {
        assertEquals(SidebarPanel.MIN_WIDTH, SidebarPanel.width(10, 10));
        assertEquals(SidebarPanel.MAX_WIDTH, SidebarPanel.width(1000, 10));
        assertFalse(SidebarPanel.isPanelGlyph('가'));
    }

    /** Advance of a component: the panel font's glyphs by the font, the rest by {@link TextWidth}. */
    private static int advance(Component component) {
        int total = 0;
        if (component instanceof TextComponent text) {
            if (SidebarPanel.FONT.equals(text.font())) {
                total += text.content().codePoints().map(cp -> {
                    Integer a = ADVANCE.get(cp);
                    assertNotNull(a, "no glyph U+" + Integer.toHexString(cp));
                    return a;
                }).sum();
            } else {
                total += TextWidth.of(text.children(List.of()));
            }
        }
        for (Component child : component.children()) {
            total += advance(child);
        }
        return total;
    }

    /** Pixels the row pieces of {@code text} draw (spaces and the key not counted). */
    private static int rowWidth(String text) {
        return text.codePoints().filter(cp -> cp >= SidebarPanel.ROW && cp < SidebarPanel.KEY)
                .map(DRAWN::get).sum();
    }
}
