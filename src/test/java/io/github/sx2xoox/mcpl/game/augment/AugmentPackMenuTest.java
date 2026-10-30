package io.github.sx2xoox.mcpl.game.augment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import io.github.sx2xoox.mcpl.game.augment.api.AugmentInfo;
import io.github.sx2xoox.mcpl.game.augment.runtime.AugmentMatch;
import io.github.sx2xoox.mcpl.game.augment.runtime.Offer;
import io.github.sx2xoox.mcpl.game.augment.runtime.PackOfferMenu;
import io.github.sx2xoox.mcpl.game.augment.runtime.PackScreen;
import io.github.sx2xoox.mcpl.game.augment.support.AugmentHarness;
import io.github.sx2xoox.mcpl.game.superpower.support.TestPlayer;
import org.bukkit.Material;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The resource pack pick screen ({@code augments.ui: PACK}) and its dialog fallback for players without the pack. */
class AugmentPackMenuTest {

    private AugmentHarness h;

    @BeforeEach
    void setUp() {
        h = new AugmentHarness();
        h.settings().set(AugmentSettings.OFFER_UI, AugmentSettings.OfferUi.PACK.name());
    }

    @AfterEach
    void tearDown() {
        h.close();
    }

    @Test
    void playersWithThePackSeeTheCardScreenAndPickBySlot() {
        List<TestPlayer> players = h.join(2);
        TestPlayer packed = players.getFirst();
        h.game.packCheck(player -> player == packed);
        AugmentMatch match = h.startFarming();
        h.ticks(2 * 20 + 1);

        PackOfferMenu menu = assertInstanceOf(PackOfferMenu.class,
                packed.getOpenInventory().getTopInventory().getHolder());
        assertNotNull(h.dialogs.open(players.get(1)), "a player without the pack gets the dialog");
        assertNull(h.dialogs.open(packed));

        ItemStack hitbox = menu.getInventory().getItem(13);
        assertNotNull(hitbox);
        // (MockBukkit keeps no data components: the invisible model and tooltip style are not visible here)
        assertEquals(menu.offer().cards().get(1).icon(), hitbox.getType(), "the middle card's hitbox");
        assertEquals(Material.CLOCK, menu.getInventory().getItem(PackScreen.CLOCK_SLOT).getType());

        AugmentInfo wanted = menu.offer().cards().get(1);
        InventoryClickEvent click = click(packed, 40); // bottom row of the middle card
        assertTrue(click.isCancelled());
        assertEquals(List.of(wanted), match.augments(packed.getUniqueId()));
    }

    @Test
    void aRerollOpensANewScreenAndTheOldOneIsStale() {
        h.settings().set(AugmentSettings.REROLLS, 1);
        TestPlayer player = h.join(2).getFirst();
        h.game.packCheck(p -> true);
        AugmentMatch match = h.startFarming();
        h.ticks(2 * 20 + 1);
        PackOfferMenu before = (PackOfferMenu) player.getOpenInventory().getTopInventory().getHolder();
        Offer offer = before.offer();

        click(player, 46);
        assertEquals(0, match.rerollsLeft(player.getUniqueId()));
        PackOfferMenu after = (PackOfferMenu) player.getOpenInventory().getTopInventory().getHolder();
        assertNotSame(before, after, "the title draws the cards, so the screen is opened again");
        assertEquals(offer.revision(), after.revision());
        assertNull(before.cardAt(13), "the old screen shows older cards");
        assertEquals(offer.cards().getFirst(), after.cardAt(0));
    }

    @Test
    void laterClosesTheScreenWithoutPicking() {
        TestPlayer player = h.join(2).getFirst();
        h.game.packCheck(p -> true);
        AugmentMatch match = h.startFarming();
        h.ticks(2 * 20 + 1);
        click(player, 52);
        assertNull(match.cardMenu(player.getUniqueId()));
        assertNotNull(match.currentOffer(player.getUniqueId()), "still to pick");
        assertTrue(h.server.dispatchCommand(player, "게임 증강"));
        assertInstanceOf(PackOfferMenu.class, player.getOpenInventory().getTopInventory().getHolder());
    }

    private InventoryClickEvent click(TestPlayer player, int slot) {
        InventoryClickEvent click = new InventoryClickEvent(player.getOpenInventory(), InventoryType.SlotType.CONTAINER,
                slot, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        h.server.getPluginManager().callEvent(click);
        return click;
    }
}
