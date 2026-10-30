package io.github.sx2xoox.mcpl.game.augment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import io.github.sx2xoox.mcpl.game.augment.api.AugmentInfo;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentTier;
import io.github.sx2xoox.mcpl.game.augment.runtime.AugmentMatch;
import io.github.sx2xoox.mcpl.game.augment.runtime.Offer;
import io.github.sx2xoox.mcpl.game.augment.runtime.OfferDialog;
import io.github.sx2xoox.mcpl.game.augment.runtime.OfferMenu;
import io.github.sx2xoox.mcpl.game.augment.support.AugmentHarness;
import io.github.sx2xoox.mcpl.game.augment.support.TestDialogs;
import io.github.sx2xoox.mcpl.game.superpower.support.TestPlayer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ObjectComponent;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.object.SpriteObjectContents;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The augment pick dialog (default {@code augments.ui: DIALOG}): layout, clicks, stale dialogs, Esc, queue. */
class AugmentDialogTest {

    private AugmentHarness h;
    private TestDialogs dialogs;
    private TestPlayer a;
    private TestPlayer b;

    @BeforeEach
    void setUp() {
        h = new AugmentHarness();
        dialogs = h.dialogs;
        List<TestPlayer> players = h.join(2);
        a = players.get(0);
        b = players.get(1);
    }

    @AfterEach
    void tearDown() {
        h.close();
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    private AugmentMatch roundOpen() {
        AugmentMatch match = h.startFarming();
        h.ticks(2 * 20 + 1);
        assertEquals(1, match.roundsPlayed());
        return match;
    }

    @Test
    void aRoundShowsTheCardsSideBySideInADialog() {
        AugmentMatch match = roundOpen();
        for (TestPlayer player : List.of(a, b)) {
            TestDialogs.Shown shown = dialogs.open(player);
            assertNotNull(shown, "everyone sees the dialog");
            org.bukkit.inventory.Inventory top = player.getOpenInventory().getTopInventory();
            assertFalse(top != null && top.getHolder() instanceof OfferMenu, "no chest");
            Offer offer = match.currentOffer(player.getUniqueId());
            OfferDialog dialog = shown.dialog;
            assertEquals(offer.id(), dialog.offerId());
            assertEquals(offer.revision(), dialog.revision());
            assertEquals(3, dialog.cards().size());
            assertEquals(3, dialog.columns(), "one column per card");
            assertEquals("증강 선택 · 실버", plain(dialog.title()));
            assertTrue(plain(dialog.header().getFirst()).contains("1번째 라운드"), plain(dialog.header().getFirst()));
            assertTrue(plain(dialog.countdown()).startsWith("5초"), plain(dialog.countdown()));
            assertEquals("다시 뽑기 (2회 남음)", plain(dialog.reroll()));
            for (int i = 0; i < 3; i++) {
                AugmentInfo info = offer.cards().get(i);
                OfferDialog.Card card = dialog.cards().get(i);
                assertEquals(info, card.info());
                assertEquals(info.name(), plain(card.name()));
                assertEquals(info.tier().color(), card.name().color(), "tier colour");
                assertTrue(plain(card.tooltip()).contains(match.config().params(info).render(info.description())),
                        "the tooltip has the full description");
                List<Component> lines = card.lines();
                assertEquals(7, lines.size(), "tier, name, kind, 3 description lines, pick");
                assertEquals("◆ 실버", plain(lines.get(0)));
                assertTrue(plain(lines.get(1)).endsWith(info.name()), plain(lines.get(1)));
                assertEquals(OfferDialog.sprite(info.icon()) != null, hasSprite(lines.get(1)),
                        "the icon is an inline sprite (a symbol for items drawn by an entity model, e.g. the shield)");
                assertEquals(info.kind().koreanName(), plain(lines.get(2)));
                String description = match.config().params(info).render(info.description());
                String visible = String.join(" ", lines.subList(3, 6).stream().map(AugmentDialogTest::plain)
                        .filter(line -> !line.isEmpty()).toList());
                String head = visible.endsWith("…") ? visible.substring(0, visible.length() - 1).strip() : visible;
                assertTrue(description.replace(" ", "").startsWith(head.replace(" ", "")), visible + " / " + description);
                assertEquals("▶ 선택", plain(lines.get(6)));
            }
        }
    }

    private static boolean hasSprite(Component component) {
        if (component instanceof ObjectComponent object && object.contents() instanceof SpriteObjectContents) {
            return true;
        }
        return component.children().stream().anyMatch(AugmentDialogTest::hasSprite);
    }

    @Test
    void theCardsAreColumnsOfTheButtonGrid() {
        AugmentMatch match = roundOpen();
        OfferDialog dialog = dialogs.open(a).dialog;
        assertNotNull(match);
        int columns = dialog.columns();
        int rows = dialog.rows();
        assertEquals(3, columns);
        assertEquals(7, rows);
        List<OfferDialog.Button> grid = dialog.grid();
        assertEquals(rows * columns + 1, grid.size(), "the card rows, then the reroll button");
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < columns; c++) {
                OfferDialog.Button button = grid.get(r * columns + c);
                OfferDialog.Card card = dialog.cards().get(c);
                assertEquals(OfferDialog.Action.pick(c), button.action(), "row " + r + " column " + c);
                assertEquals(card.lines().get(r), button.label());
                assertEquals(card.tooltip(), button.tooltip());
                assertEquals(dialog.cardWidth(), button.width());
            }
        }
        assertEquals(OfferDialog.Action.REROLL, grid.getLast().action());
        assertTrue(dialog.bodyWidth() >= 3 * dialog.cardWidth() && dialog.bodyWidth() <= 480,
                "three cards fit a small GUI");
    }

    @Test
    void moreCardsMakeNarrowerShorterColumns() {
        for (int cards = 1; cards <= 5; cards++) {
            int width = cards * OfferDialog.cardWidth(cards) + (cards - 1) * 2;
            assertTrue(width <= 480, cards + " cards fit a small GUI: " + width);
        }
        assertTrue(OfferDialog.cardWidth(5) < OfferDialog.cardWidth(4));
        assertTrue(OfferDialog.cardWidth(4) < OfferDialog.cardWidth(3));
        assertEquals(3, OfferDialog.descriptionLines(3));
        assertEquals(2, OfferDialog.descriptionLines(5), "narrow cards get fewer description lines");
    }

    @Test
    void wrapBreaksAtSpacesAndEndsCutTextWithAnEllipsis() {
        assertEquals(List.of("짧은 설명"), OfferDialog.wrap("짧은 설명", 140, 3, false));
        assertEquals(9 * 3 + 4, OfferDialog.width("가나 다", false), "Hangul 9 px, a space 4 px");
        assertEquals(6 + 2 + 3, OfferDialog.width("ail", false));
        assertEquals(7 * 2, OfferDialog.width("aa", true), "bold adds 1 px a glyph");

        String text = "최대 체력이 4 늘어나고 늘어난 만큼 바로 회복합니다. 그리고 아주 긴 설명이 계속 이어집니다.";
        List<String> lines = OfferDialog.wrap(text, 100, 2, false);
        assertEquals(2, lines.size());
        for (String line : lines) {
            assertTrue(OfferDialog.width(line, false) <= 100, line);
            assertFalse(line.startsWith(" ") || line.endsWith(" "), line);
        }
        assertEquals("최대 체력이 4 늘어나고", lines.getFirst(), "breaks at the last space that fits");
        assertEquals("늘어난 만큼 바로…", lines.getLast());
        assertTrue(lines.getLast().endsWith("…"), "cut text ends in an ellipsis");
        assertTrue(text.startsWith(String.join(" ", lines).replace("…", "")), lines.toString());

        List<String> word = OfferDialog.wrap("가나다라마바사아자차카타파하", 40, 2, false);
        assertEquals(List.of("가나다라", "마바사…"), word, "a long word breaks anywhere");
        assertEquals(List.of(), OfferDialog.wrap("  ", 40, 2, false));
    }

    @Test
    void aClickGrantsTheCardOnce() {
        AugmentMatch match = roundOpen();
        TestDialogs.Shown shown = dialogs.open(a);
        AugmentInfo wanted = match.currentOffer(a.getUniqueId()).cards().get(1);
        dialogs.click(a, OfferDialog.Action.pick(1));
        assertEquals(List.of(wanted), match.augments(a.getUniqueId()));
        assertNull(match.currentOffer(a.getUniqueId()));
        assertNull(dialogs.open(a));

        dialogs.click(a, shown, OfferDialog.Action.pick(0)); // a second click on the same (closed) dialog
        assertEquals(List.of(wanted), match.augments(a.getUniqueId()), "no double grant");
    }

    @Test
    void aRerollRedrawsTheDialogAndTheOldOneIsStale() {
        AugmentMatch match = roundOpen();
        TestDialogs.Shown before = dialogs.open(a);
        Offer offer = match.currentOffer(a.getUniqueId());
        List<AugmentInfo> oldCards = offer.cards();
        dialogs.click(a, OfferDialog.Action.REROLL);
        assertEquals(1, match.rerollsLeft(a.getUniqueId()));
        TestDialogs.Shown after = dialogs.open(a);
        assertNotNull(after, "the dialog comes back with the new cards");
        assertNotEquals(before.dialog.revision(), after.dialog.revision());
        assertEquals(offer.cards(), after.dialog.cards().stream().map(OfferDialog.Card::info).toList());
        assertEquals("다시 뽑기 (1회 남음)", plain(after.dialog.reroll()));

        int shown = dialogs.history().size();
        dialogs.click(a, before, OfferDialog.Action.pick(0)); // a click that was on its way before the reroll
        assertTrue(match.augments(a.getUniqueId()).isEmpty(), "a stale dialog picks nothing");
        assertEquals(shown + 1, dialogs.history().size(), "the current dialog is shown again");
        assertTrue(offer.cards().stream().anyMatch(card -> !oldCards.contains(card)));

        dialogs.click(a, OfferDialog.Action.REROLL);
        assertNull(dialogs.open(a).dialog.reroll(), "no reroll button without rerolls");
        dialogs.click(a, OfferDialog.Action.pick(2));
        assertEquals(List.of(offer.cards().get(2)), match.augments(a.getUniqueId()));
    }

    @Test
    void escapeKeepsTheOfferAndTheTimeoutStillGrants() {
        AugmentMatch match = roundOpen();
        List<AugmentInfo> cards = match.currentOffer(a.getUniqueId()).cards();
        TestDialogs.Shown first = dialogs.open(a);
        dialogs.escape(a);
        assertNull(dialogs.open(a));
        assertNotNull(match.currentOffer(a.getUniqueId()), "closing does not lose the offer");
        assertTrue(h.server.dispatchCommand(a, "게임 증강"));
        assertNotNull(dialogs.open(a), "/게임 증강 shows it again");
        assertEquals(first.dialog.offerId(), dialogs.open(a).dialog.offerId());

        int closes = dialogs.closes();
        h.ticks(5 * 20 + 1);
        assertEquals(1, match.augments(a.getUniqueId()).size(), "a random card after the time limit");
        assertTrue(cards.contains(match.augments(a.getUniqueId()).getFirst()));
        assertNull(dialogs.open(a), "the timeout closes the dialog");
        assertTrue(dialogs.closes() > closes);

        dialogs.click(a, first, OfferDialog.Action.pick(0)); // the old dialog after the timeout
        assertEquals(1, match.augments(a.getUniqueId()).size());
    }

    @Test
    void theCountdownIsRefreshedNearTheEnd() {
        h.settings().set(AugmentSettings.CHOICE, 12);
        AugmentMatch match = roundOpen();
        assertNotNull(match);
        int shown = dialogs.history().size();
        h.ticks(3 * 20);
        TestDialogs.Shown refreshed = dialogs.open(a);
        assertTrue(dialogs.history().size() > shown, "shown again at 10 s");
        assertTrue(plain(refreshed.dialog.countdown()).startsWith("10초"), plain(refreshed.dialog.countdown()));
    }

    @Test
    void queuedOffersShowOneAfterAnother() {
        h.noRounds();
        AugmentMatch match = h.startFarming();
        match.offer(a, AugmentTier.SILVER, Component.text("첫째"));
        match.offer(a, AugmentTier.GOLD, Component.text("둘째"));
        OfferDialog first = dialogs.open(a).dialog; // shown again when the second one queued
        assertEquals(2, first.header().size(), "the header says one more waits");
        assertTrue(plain(first.header().get(1)).contains("1"), plain(first.header().get(1)));
        dialogs.click(a, OfferDialog.Action.pick(0));
        OfferDialog second = dialogs.open(a).dialog;
        assertNotEquals(first.offerId(), second.offerId());
        assertEquals("증강 선택 · 골드", plain(second.title()));
        assertEquals(1, second.header().size());
        dialogs.click(a, OfferDialog.Action.pick(0));
        assertEquals(2, match.augments(a.getUniqueId()).size());
        assertNull(dialogs.open(a));
    }

    @Test
    void anEliminatedPlayerLosesTheDialog() {
        AugmentMatch match = roundOpen();
        assertNotNull(dialogs.open(a));
        a.setHealth(0);
        h.ticks(1);
        assertNull(match.currentOffer(a.getUniqueId()));
        assertNull(dialogs.open(a), "the dialog closes with the offers");
        int shown = dialogs.history().size();
        assertFalse(h.match() != null && h.match().openMenu(a), "spectators never get one");
        assertEquals(shown, dialogs.history().size());
    }
}
