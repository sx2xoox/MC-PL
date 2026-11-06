package io.github.sx2xoox.mcpl.game.augment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.sx2xoox.mcpl.core.pack.PackAudience;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentInfo;
import io.github.sx2xoox.mcpl.game.augment.runtime.AugmentMatch;
import io.github.sx2xoox.mcpl.game.augment.runtime.CardGlyphs;
import io.github.sx2xoox.mcpl.game.augment.runtime.Offer;
import io.github.sx2xoox.mcpl.game.augment.runtime.OfferCards;
import io.github.sx2xoox.mcpl.game.augment.support.AugmentHarness;
import io.github.sx2xoox.mcpl.game.superpower.support.TestPlayer;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The floating augment cards ({@code augments.ui: CARDS}, the default): they ride the player, only the owner sees
 * them, number keys highlight / pick, sneak + number rerolls one card, Q folds, F unfolds or confirms, the time out
 * picks one, nothing is left behind; and the pack font the generator wrote matches {@link CardGlyphs}.
 */
class AugmentCardsTest {

    private AugmentHarness h;
    private TestPlayer a;
    private TestPlayer b;
    private final Set<UUID> withPack = new HashSet<>();

    @BeforeEach
    void setUp() {
        h = new AugmentHarness();
        h.settings().set(AugmentSettings.OFFER_UI, AugmentSettings.OfferUi.CARDS.name());
        List<TestPlayer> players = h.join(2);
        a = players.get(0);
        b = players.get(1);
        withPack.add(a.getUniqueId());
        h.game.packsOverride(new PackAudience() {
            @Override
            public boolean hasPack(Player player) {
                return withPack.contains(player.getUniqueId());
            }

            @Override
            public Subscription subscribe(Consumer<Player> listener) {
                return () -> { };
            }
        });
    }

    @AfterEach
    void tearDown() {
        h.close();
    }

    private AugmentMatch roundOpen() {
        AugmentMatch match = h.startFarming();
        h.ticks(2 * 20 + 1);
        assertEquals(1, match.roundsPlayed());
        return match;
    }

    private List<TextDisplay> cardDisplays() {
        return h.server.getWorlds().stream().flatMap(w -> w.getEntitiesByClass(TextDisplay.class).stream())
                .filter(d -> d.getScoreboardTags().contains(OfferCards.TAG)).toList();
    }

    private boolean key(Player player, int slot) {
        PlayerItemHeldEvent event = new PlayerItemHeldEvent(player, 8, slot);
        h.server.getPluginManager().callEvent(event);
        return event.isCancelled();
    }

    private boolean swap(Player player) {
        PlayerSwapHandItemsEvent event = new PlayerSwapHandItemsEvent(player, new ItemStack(org.bukkit.Material.AIR),
                new ItemStack(org.bukkit.Material.AIR));
        h.server.getPluginManager().callEvent(event);
        return event.isCancelled();
    }

    private boolean drop(Player player) {
        org.bukkit.entity.Item item = player.getWorld().dropItem(player.getLocation(),
                new ItemStack(org.bukkit.Material.DIRT));
        PlayerDropItemEvent event = new PlayerDropItemEvent(player, item);
        h.server.getPluginManager().callEvent(event);
        item.remove();
        return event.isCancelled();
    }

    @Test
    void theCardsFloatInFrontOfTheOwnerOnlyAndPlayersWithoutThePackGetTheDialog() {
        AugmentMatch match = roundOpen();
        OfferCards cards = match.cards(a.getUniqueId());
        assertNotNull(cards, "a has the pack: floating cards");
        assertNull(h.dialogs.open(a), "no dialog on top of them");
        assertNotNull(h.dialogs.open(b), "b has no pack: the dialog");
        assertNull(match.cards(b.getUniqueId()));

        List<TextDisplay> displays = cardDisplays();
        assertEquals(3 * 5 + 6, displays.size(), "frame, icon, name, description, key per card + 6 screen pieces");
        for (TextDisplay display : displays) {
            assertEquals(a, display.getVehicle(), "rides the owner (follows the camera smoothly)");
            assertFalse(display.isVisibleByDefault(), "nobody else sees it");
            assertEquals(org.bukkit.entity.Display.Billboard.CENTER, display.getBillboard());
            assertFalse(display.isPersistent());
            assertTrue(display.getTransformation().getTranslation().z < 0, "ahead of the camera");
        }
        h.ticks(20);
        assertTrue(cardDisplays().stream().anyMatch(d -> d.getTransformation().getScale().x > 0.001),
                "popped in");
    }

    @Test
    void aNumberKeyHighlightsAndTheSameKeyAgainPicksIt() {
        AugmentMatch match = roundOpen();
        Offer offer = match.currentOffer(a.getUniqueId());
        AugmentInfo second = offer.cards().get(1);
        assertTrue(key(a, 1), "the held slot stays");
        assertEquals(1, match.cards(a.getUniqueId()).highlight());
        assertTrue(match.augments(a.getUniqueId()).isEmpty(), "a first press only highlights");
        assertTrue(key(a, 1));
        assertEquals(List.of(second), match.augments(a.getUniqueId()));
        assertNull(match.currentOffer(a.getUniqueId()));
        assertNull(match.cards(a.getUniqueId()));
        h.ticks(15);
        assertTrue(cardDisplays().isEmpty(), "the cards animate away and are removed");
        assertFalse(key(a, 2), "no cards: the hotbar works again");
    }

    @Test
    void fPicksTheHighlightedCardAndNeedsOne() {
        AugmentMatch match = roundOpen();
        Offer offer = match.currentOffer(a.getUniqueId());
        assertTrue(swap(a), "F without a highlight only hints");
        assertTrue(match.augments(a.getUniqueId()).isEmpty());
        key(a, 2);
        assertTrue(swap(a));
        assertEquals(List.of(offer.cards().get(2)), match.augments(a.getUniqueId()));
    }

    @Test
    void sneakAndANumberRerollsOnlyThatCard() {
        AugmentMatch match = roundOpen();
        Offer offer = match.currentOffer(a.getUniqueId());
        List<AugmentInfo> before = offer.cards();
        a.setSneaking(true);
        assertTrue(key(a, 0));
        a.setSneaking(false);
        List<AugmentInfo> after = match.currentOffer(a.getUniqueId()).cards();
        assertNotEquals(before.get(0), after.get(0), "card 1 redrawn");
        assertFalse(before.contains(after.get(0)), "not one of the shown cards");
        assertEquals(before.subList(1, 3), after.subList(1, 3), "the others stay");
        assertEquals(1, match.rerollsLeft(a.getUniqueId()));
        assertTrue(match.augments(a.getUniqueId()).isEmpty(), "a reroll picks nothing");
        h.ticks(10);
        assertEquals(21, cardDisplays().size(), "the same cards, flipped");
    }

    @Test
    void qFoldsTheCardsAndFUnfoldsThem() {
        AugmentMatch match = roundOpen();
        Offer offer = match.currentOffer(a.getUniqueId());
        key(a, 0);
        assertTrue(drop(a), "Q folds instead of dropping");
        assertNull(match.cards(a.getUniqueId()));
        assertEquals(offer, match.currentOffer(a.getUniqueId()), "the offer waits");
        h.ticks(15);
        assertTrue(cardDisplays().isEmpty());
        assertFalse(drop(a), "folded: Q drops again");
        assertFalse(key(a, 1), "folded: the hotbar works");

        assertTrue(swap(a), "F unfolds");
        OfferCards cards = match.cards(a.getUniqueId());
        assertNotNull(cards);
        assertEquals(0, cards.highlight(), "the highlight is kept");
        a.setSneaking(true);
        assertFalse(swap(a), "sneak + F stays with the augments (점멸)");
        a.setSneaking(false);
    }

    @Test
    void theTimeOutPicksAShownCardAndLeavesNothing() {
        AugmentMatch match = roundOpen();
        List<AugmentInfo> shown = match.currentOffer(a.getUniqueId()).cards();
        h.ticks(5 * 20 + 20);
        assertEquals(1, match.augments(a.getUniqueId()).size());
        assertTrue(shown.contains(match.augments(a.getUniqueId()).getFirst()));
        h.ticks(15);
        assertTrue(cardDisplays().isEmpty());
    }

    @Test
    void stoppingTheMatchRemovesEveryCard() {
        roundOpen();
        assertFalse(cardDisplays().isEmpty());
        h.hub.games().stop(net.kyori.adventure.text.Component.text("test"));
        assertTrue(cardDisplays().isEmpty());
        assertTrue(a.getPassengers().isEmpty());
    }

    @Test
    void theGeneratedFontMatchesCardGlyphs() throws IOException {
        JsonObject font = JsonParser.parseString(Files.readString(
                Path.of("src/main/resourcepack-src/assets/mcpl/font/augment_cards.json"))).getAsJsonObject();
        Map<Integer, JsonObject> glyphs = new HashMap<>();
        for (JsonElement p : font.getAsJsonArray("providers")) {
            JsonObject provider = p.getAsJsonObject();
            if (provider.get("type").getAsString().equals("bitmap")) {
                int cp = provider.getAsJsonArray("chars").get(0).getAsString().codePointAt(0);
                glyphs.put(cp, provider);
            }
        }
        for (int tier = 0; tier < 3; tier++) {
            centred(glyphs, CardGlyphs.FRAME + tier, CardGlyphs.FRAME_H);
            centred(glyphs, CardGlyphs.FRAME_LIT + tier, CardGlyphs.FRAME_H);
            centred(glyphs, CardGlyphs.RIBBON + tier, CardGlyphs.RIBBON_H);
        }
        for (int n = 0; n < 5; n++) {
            centred(glyphs, CardGlyphs.KEY + n, CardGlyphs.KEY_SIZE);
            centred(glyphs, CardGlyphs.KEY_LIT + n, CardGlyphs.KEY_SIZE);
        }
        centred(glyphs, CardGlyphs.REROLL, CardGlyphs.KEY_SIZE);
        centred(glyphs, CardGlyphs.REROLL_LIT, CardGlyphs.KEY_SIZE);
        for (int level = 0; level <= CardGlyphs.BAR_STEPS; level++) {
            centred(glyphs, CardGlyphs.BAR + level, CardGlyphs.BAR_H);
        }
        for (int i = 0; i < CardGlyphs.SMALL_KEYS.length(); i++) {
            assertNotNull(glyphs.get(CardGlyphs.SMALL_KEY + i), "small key " + CardGlyphs.SMALL_KEYS.charAt(i));
        }
        for (AugmentInfo info : h.game.registry().all().stream().map(e -> e.info()).toList()) {
            Integer cp = CardGlyphs.icons().get(info.id());
            assertNotNull(cp, info.id() + " has an icon (python3 -I tools/assetgen/augment_cards.py)");
            centred(glyphs, cp, CardGlyphs.ICON_SIZE);
            assertTrue(glyphs.get(cp).get("file").getAsString().endsWith("icon_" + info.id() + ".png"));
        }
    }

    private static void centred(Map<Integer, JsonObject> glyphs, int cp, int height) {
        JsonObject glyph = glyphs.get(cp);
        assertNotNull(glyph, Integer.toHexString(cp));
        assertEquals(height, glyph.get("height").getAsInt(), Integer.toHexString(cp));
        assertEquals(0, 4 + glyph.get("ascent").getAsInt() * 2 - height, "centred on the line: "
                + Integer.toHexString(cp));
    }
}
