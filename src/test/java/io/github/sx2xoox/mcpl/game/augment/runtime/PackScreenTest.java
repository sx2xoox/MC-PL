package io.github.sx2xoox.mcpl.game.augment.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.github.sx2xoox.mcpl.game.augment.api.AugmentInfo;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentRegistry;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentTier;
import io.github.sx2xoox.mcpl.game.augment.augments.BuiltinAugments;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

/** The resource pack screen's layout math: slot → card, space glyphs, glyph placement in the title, text fitting. */
class PackScreenTest {

    @Test
    void everySlotOfATallCardPicksIt() {
        for (int count = 1; count <= 3; count++) {
            Set<Integer> seen = new HashSet<>();
            for (int card = 0; card < count; card++) {
                List<Integer> slots = PackScreen.slotsOf(card, count);
                assertEquals(15, slots.size(), "3 columns x 5 rows");
                for (int slot : slots) {
                    assertEquals(card, PackScreen.cardAt(slot, count));
                    assertTrue(seen.add(slot));
                    assertTrue(slot < 45, "the bottom row holds the buttons");
                }
            }
        }
        assertEquals(List.of(3, 4, 5, 12, 13, 14, 21, 22, 23, 30, 31, 32, 39, 40, 41), PackScreen.slotsOf(0, 1),
                "one card stands in the middle");
        assertEquals(0, PackScreen.cardAt(2, 2));
        assertEquals(-1, PackScreen.cardAt(4, 2), "the middle of two cards is empty");
        assertEquals(1, PackScreen.cardAt(8, 2));
        assertEquals(2, PackScreen.cardAt(44, 3));
        assertEquals(-1, PackScreen.cardAt(45, 3));
    }

    @Test
    void fourAndFiveCardsAreRows() {
        assertTrue(PackScreen.compact(4));
        assertFalse(PackScreen.compact(3));
        for (int card = 0; card < 5; card++) {
            assertEquals(List.of(card * 9, card * 9 + 1, card * 9 + 2, card * 9 + 3, card * 9 + 4, card * 9 + 5,
                    card * 9 + 6, card * 9 + 7, card * 9 + 8), PackScreen.slotsOf(card, 5));
        }
        assertEquals(-1, PackScreen.cardAt(40, 4), "no fifth row with four cards");
    }

    @Test
    void buttonsSitInTheBottomRow() {
        for (int slot = 0; slot < PackScreen.SIZE; slot++) {
            boolean reroll = PackScreen.isReroll(slot);
            boolean later = PackScreen.isLater(slot);
            assertFalse(reroll && later);
            assertEquals(slot >= 45 && slot <= 47, reroll);
            assertEquals(slot >= 51, later);
        }
        assertEquals(49, PackScreen.CLOCK_SLOT);
    }

    @Test
    void spaceGlyphsAddUpToTheMove() {
        for (int delta = -1500; delta <= 1500; delta++) {
            String spaces = PackScreen.spaces(delta);
            int sum = 0;
            for (int i = 0; i < spaces.length(); i++) {
                sum += PackScreen.spaceAdvance(spaces.charAt(i));
            }
            assertEquals(delta, sum, "move " + delta);
            assertTrue(spaces.length() <= 10 + Math.abs(delta) / 512, "few glyphs for " + delta);
        }
    }

    @Test
    void titleGlyphsLandWhereTheArtExpectsThem() {
        List<PackScreen.CardView> cards = List.of(
                view(AugmentTier.SILVER, "날카로운 칼날", "전투", Material.IRON_SWORD),
                view(AugmentTier.GOLD, "광전사의 계약", "양날의 검", Material.GOLDEN_AXE),
                view(AugmentTier.PRISM, "불사조의 깃털", "특수", Material.FEATHER));
        Component title = PackScreen.title(cards, Component.text("2번째 라운드"), 54, true, "리롤 2", "나중에");
        List<Placed> placed = simulate(title);
        // background tiles, then per card: frame and icon
        assertEquals(List.of(-8, 88, -8, 88), xs(placed.subList(0, 4)));
        assertEquals('', placed.get(4).first());
        assertEquals(8, placed.get(4).x());
        assertEquals(8 + 10, placed.get(5).x(), "the icon is centred in the card");
        assertEquals(62, placed.get(6).x());
        assertEquals('', placed.get(6).first());
        assertEquals(116, placed.get(8).x());
        assertEquals('', placed.get(8).first());
        Placed tier = find(placed, "실버");
        assertEquals(PackScreen.lineFont(PackScreen.LINE_TIER), tier.font());
        assertEquals(34 - (PackScreen.width("실버") - 1) / 2, tier.x(), "tier name centred on the card");
        assertEquals(PackScreen.lineFont(PackScreen.LINE_NAME_1), find(placed, "광전사의").font());
        assertEquals(PackScreen.lineFont(PackScreen.LINE_NAME_2), find(placed, "계약").font());
        assertEquals(PackScreen.lineFont(PackScreen.LINE_BUTTON), find(placed, "리롤 2").font());
        Placed header = placed.getLast();
        assertEquals("2번째 라운드", header.text());
        assertEquals(88 - 54 / 2, header.x(), "the header is centred over the chest");
    }

    @Test
    void compactRowsPutNamesBesideTheItem() {
        List<PackScreen.CardView> cards = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            cards.add(view(AugmentTier.GOLD, "카드" + i, "특수", Material.DIAMOND));
        }
        List<Placed> placed = simulate(PackScreen.title(cards, Component.empty(), 0, false, "리롤 없음", "나중에"));
        for (int r = 0; r < 5; r++) {
            Placed name = find(placed, "카드" + r);
            assertEquals(30, name.x());
            assertEquals(PackScreen.lineFont(PackScreen.LINE_ROWS[r]), name.font());
        }
        assertTrue(placed.stream().anyMatch(p -> p.first() == ''), "the reroll button is greyed out");
        assertTrue(placed.stream().noneMatch(p -> PackScreen.ICON_FONT.equals(p.font())),
                "rows show the real item, not a big icon");
    }

    @Test
    void namesWrapAtSpacesIntoTwoLinesAndLongOnesAreCut() {
        assertEquals(List.of("광전사의", "계약"), PackScreen.wrap("광전사의 계약", PackScreen.CARD_TEXT_W, 2));
        assertEquals(List.of("대장장이의", "축복"), PackScreen.wrap("대장장이의 축복", PackScreen.CARD_TEXT_W, 2));
        assertEquals(List.of("질풍"), PackScreen.wrap("질풍", PackScreen.CARD_TEXT_W, 2));
        List<String> cut = PackScreen.wrap("아주 아주 긴 이름의 증강 카드입니다", PackScreen.CARD_TEXT_W, 2);
        assertEquals(2, cut.size());
        assertTrue(cut.getLast().endsWith("…"));
        for (String line : cut) {
            assertTrue(PackScreen.width(line) - 1 <= PackScreen.CARD_TEXT_W, line);
        }
    }

    @Test
    void textOutsideThePackFontsIsReplaced() {
        assertEquals("가A1·?", PackScreen.sanitize("가A1·ㄱ"));
        assertEquals("?", PackScreen.sanitize("똠"), "not a KS X 1001 syllable");
        assertEquals(9, PackScreen.advance('가'));
        assertEquals(4, PackScreen.advance(' '));
        assertEquals(2, PackScreen.advance('i'));
        assertEquals(6, PackScreen.advance('A'));
    }

    @Test
    void everyAugmentIsDrawableWithABigIcon() {
        AugmentRegistry registry = new AugmentRegistry();
        BuiltinAugments.registerAll(registry);
        for (AugmentRegistry.Entry entry : registry.all()) {
            AugmentInfo info = entry.info();
            assertEquals(info.name(), PackScreen.sanitize(info.name()), "pack fonts lack a letter of " + info.id());
            assertEquals(info.kind().koreanName(), PackScreen.sanitize(info.kind().koreanName()));
            assertNotNull(PackScreen.icon(info.icon(), info.tier()), "no big icon for " + info.id());
            String sprite = OfferDialog.sprite(info.icon());
            if (sprite != null) {
                assertTrue(PackScreen.icons().containsKey(sprite),
                        sprite + " has no glyph: run python3 -I tools/resourcepack/generate_pack.py");
            }
        }
        for (AugmentTier tier : AugmentTier.values()) {
            assertEquals(tier.koreanName(), PackScreen.sanitize(tier.koreanName()));
        }
    }

    private static PackScreen.CardView view(AugmentTier tier, String name, String kind, Material icon) {
        return new PackScreen.CardView(tier, tier.koreanName(), name, kind, PackScreen.icon(icon, tier), icon);
    }

    // ── a tiny client: advances the cursor like the font renderer ────────────────────────────────────────────────

    record Placed(int x, String text, Key font) {
        char first() {
            return text.charAt(0);
        }
    }

    private static List<Placed> simulate(Component title) {
        List<Placed> out = new ArrayList<>();
        int x = PackScreen.TITLE_X;
        for (Component child : title.children()) {
            String text = ((TextComponent) child).content();
            Key font = child.font();
            if (font == null) {
                out.add(new Placed(x, plain(child), Key.key("minecraft", "default")));
                continue;
            }
            if (font.equals(PackScreen.FONT) && text.charAt(0) >= '') {
                for (int i = 0; i < text.length(); i++) {
                    x += PackScreen.spaceAdvance(text.charAt(i));
                }
                continue;
            }
            out.add(new Placed(x, text, font));
            x += advance(text, font);
        }
        return out;
    }

    private static String plain(Component component) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(component);
    }

    private static int advance(String text, Key font) {
        char c = text.charAt(0);
        if (font.equals(PackScreen.ICON_FONT)) {
            return PackScreen.icons().values().stream().filter(g -> g.character() == c).findFirst().orElseThrow()
                    .advance();
        }
        if (font.equals(PackScreen.FONT)) {
            if (c >= '' && c <= '') {
                return 97;
            }
            if (c >= '') {
                return 81;
            }
            return 53; // cards and buttons
        }
        return PackScreen.width(text);
    }

    private static List<Integer> xs(List<Placed> placed) {
        return placed.stream().map(Placed::x).toList();
    }

    private static Placed find(List<Placed> placed, String text) {
        return placed.stream().filter(p -> p.text().equals(text)).findFirst().orElseThrow(() -> new AssertionError(
                "no " + text + " in " + placed));
    }
}
