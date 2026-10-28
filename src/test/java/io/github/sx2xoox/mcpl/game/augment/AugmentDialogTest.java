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
import net.kyori.adventure.text.format.TextDecoration;
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
                assertEquals(info.name(), plain(card.label()));
                assertEquals(info.tier().color(), card.label().children().isEmpty()
                        ? card.label().color() : card.label().children().getFirst().color(), "tier colour");
                assertTrue(plain(card.tooltip()).contains(match.config().params(info).render(info.description())),
                        "the tooltip has the full description");
                assertTrue(plain(card.description()).startsWith(info.name()));
                assertEquals(info.icon(), card.icon().getType());
            }
        }
    }

    @Test
    void cardLabelsAreBold() {
        AugmentMatch match = roundOpen();
        OfferDialog.Card card = dialogs.open(a).dialog.cards().getFirst();
        Component name = card.label().children().isEmpty() ? card.label() : card.label().children().getFirst();
        assertEquals(TextDecoration.State.TRUE, name.decoration(TextDecoration.BOLD));
        assertNotNull(match);
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
