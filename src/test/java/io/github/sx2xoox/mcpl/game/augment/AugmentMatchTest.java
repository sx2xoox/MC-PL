package io.github.sx2xoox.mcpl.game.augment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import io.github.sx2xoox.mcpl.core.game.GameResult;
import io.github.sx2xoox.mcpl.core.game.GameSession;
import io.github.sx2xoox.mcpl.core.game.GameState;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentInfo;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentTier;
import io.github.sx2xoox.mcpl.game.augment.runtime.AugmentMatch;
import io.github.sx2xoox.mcpl.game.augment.runtime.MatchPhase;
import io.github.sx2xoox.mcpl.game.augment.runtime.Offer;
import io.github.sx2xoox.mcpl.game.augment.runtime.OfferMenu;
import io.github.sx2xoox.mcpl.game.augment.support.AugmentHarness;
import io.github.sx2xoox.mcpl.game.superpower.support.TestPlayer;
import net.kyori.adventure.text.Component;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The flow of a 증강 야생전 match on mock worlds: world, phases, PvP gate, rounds, cards, cleanup, teams. */
class AugmentMatchTest {

    private AugmentHarness h;

    @BeforeEach
    void setUp() {
        h = new AugmentHarness();
    }

    @AfterEach
    void tearDown() {
        h.close();
    }

    @Test
    void aFreshWorldIsMadePlayedInAndDeletedAfterTheMatch() {
        List<TestPlayer> players = h.join(2);
        players.getFirst().getInventory().setItem(0, ItemStack.of(Material.DIAMOND, 3));
        GameSession session = h.start();
        World world = session.arena().world();
        assertEquals("mcpl_wild_1", world.getName());
        assertEquals(List.of("mcpl_wild_1"), h.worlds.created);
        h.ticks(1);
        assertTrue(h.worlds.chunkRequests > 0, "the play area is pre-generated during the countdown");
        assertEquals(128, world.getWorldBorder().getSize(), 1.0E-6, "the border starts at twice the radius");

        h.tickUntilPhase(MatchPhase.FARMING);
        AugmentMatch match = h.match();
        for (TestPlayer player : players) {
            assertEquals(world, player.getWorld(), "spread over the match world");
            assertEquals(GameMode.SURVIVAL, player.getGameMode());
            assertTrue(player.getInventory().isEmpty(), "empty inventory");
        }
        assertEquals(MatchPhase.FARMING, match.phase());

        session.eliminate(players.get(1), null);
        assertEquals(GameState.ENDING, session.state(), "last player standing wins");
        GameResult result = session.result();
        assertNotNull(result);
        assertTrue(result.isWinner(players.getFirst().getUniqueId()));
        h.tickUntil(() -> session.state() == GameState.CLOSED, 400, "closed");

        assertEquals(List.of("mcpl_wild_1"), h.worlds.unloaded, "the match world is unloaded");
        assertEquals(List.of("mcpl_wild_1"), h.worlds.deleted, "and deleted");
        assertNull(h.server.getWorld("mcpl_wild_1"));
        for (TestPlayer player : players) {
            assertEquals("world", player.getWorld().getName(), "everyone is back");
        }
        assertEquals(3, players.getFirst().getInventory().getItem(0).getAmount(), "inventory restored");
        assertNull(h.match());
    }

    @Test
    void theNextMatchGetsANewWorldAndAnAbortDuringTheCountdownDeletesIt() {
        h.join(2);
        GameSession first = h.start();
        h.hub.games().stop(Component.text("test"));
        assertEquals(GameState.CLOSED, first.state());
        assertEquals(List.of("mcpl_wild_1"), h.worlds.deleted, "deleted after an abort in the countdown");

        GameSession second = h.start();
        assertEquals("mcpl_wild_1", second.arena().world().getName(), "the name is free again");
        h.hub.games().stop(Component.text("test"));
    }

    @Test
    void playersWaitUnhurtWhileTheWorldIsGenerated() {
        List<TestPlayer> players = h.join(2);
        h.worlds.manualChunks = true;
        GameSession session = h.start();
        h.tickUntil(session::isRunning, 400, "running");
        h.ticks(20);
        AugmentMatch match = h.match();
        assertNotNull(match);
        assertEquals(MatchPhase.PREPARING, match.phase(), "waiting for the chunks");
        TestPlayer player = players.getFirst();
        assertEquals("world", player.getWorld().getName(), "still in the lobby");
        player.simulateDamage(5, players.get(1));
        player.damage(5, org.bukkit.damage.DamageSource.builder(org.bukkit.damage.DamageType.FALL).build());
        assertEquals(20, player.getHealth(), 1.0E-6, "nobody gets hurt while preparing");

        h.tickUntil(() -> {
            h.worlds.completeChunks();
            return match.phase() == MatchPhase.FARMING;
        }, 2000, "farming");
        assertTrue(match.wild().ready());
        assertEquals(match.wild().world(), player.getWorld());
    }

    @Test
    void theMatchStartsAnywayAfterTheLongestWait() {
        h.join(2);
        h.worlds.manualChunks = true;
        h.settings().set(AugmentSettings.PREGEN_MAX_WAIT, 2);
        h.start();
        h.tickUntilPhase(MatchPhase.FARMING);
        assertFalse(h.match().wild().ready(), "started before the pre-generation finished");
    }

    @Test
    void pvpIsOffWhileFarmingAndOnInTheFight() {
        List<TestPlayer> players = h.join(2);
        TestPlayer a = players.get(0);
        TestPlayer b = players.get(1);
        AugmentMatch match = h.startFarming();
        b.simulateDamage(4, a);
        assertEquals(20, b.getHealth(), 1.0E-6, "no PvP while farming");
        b.damage(3, org.bukkit.damage.DamageSource.builder(org.bukkit.damage.DamageType.FALL).build());
        assertEquals(17, b.getHealth(), 1.0E-6, "the world still hurts");

        h.tickUntilPhase(MatchPhase.FIGHT);
        b.simulateDamage(4, a);
        assertEquals(13, b.getHealth(), 1.0E-6, "PvP in the fight");
        assertEquals(MatchPhase.FIGHT, match.phase());

        h.ticks(20 * 3);
        assertTrue(match.borderShrinking(), "the border shrinks after its delay");
        h.ticks(20 * 5);
        assertTrue(match.borderFinal());
        h.ticks(21);
        assertTrue(a.hasPotionEffect(org.bukkit.potion.PotionEffectType.GLOWING), "everyone glows in the final zone");
    }

    @Test
    void aRoundOffersThreeCardsOfOneTierAndAClickPicks() {
        List<TestPlayer> players = h.join(2);
        h.chestMenu();
        AugmentMatch match = h.startFarming();
        h.ticks(2 * 20 + 1);
        assertEquals(1, match.roundsPlayed());
        for (TestPlayer player : players) {
            Offer offer = match.currentOffer(player.getUniqueId());
            assertNotNull(offer, "everyone gets an offer");
            assertEquals(AugmentTier.SILVER, offer.tier());
            assertEquals(3, offer.cards().size());
            assertTrue(offer.cards().stream().allMatch(card -> card.tier() == AugmentTier.SILVER));
            assertInstanceOf(OfferMenu.class, player.getOpenInventory().getTopInventory().getHolder());
        }
        TestPlayer picker = players.getFirst();
        OfferMenu menu = (OfferMenu) picker.getOpenInventory().getTopInventory().getHolder();
        AugmentInfo wanted = menu.offer().cards().get(1);
        InventoryClickEvent click = new InventoryClickEvent(picker.getOpenInventory(), InventoryType.SlotType.CONTAINER,
                menu.slotOf(wanted.id()), ClickType.LEFT, InventoryAction.PICKUP_ALL);
        h.server.getPluginManager().callEvent(click);
        assertTrue(click.isCancelled(), "cards cannot be taken");
        assertEquals(List.of(wanted), match.augments(picker.getUniqueId()));
        assertNull(match.currentOffer(picker.getUniqueId()));
        assertNotNull(match.playerTag(picker.getUniqueId()), "the kill feed and results show the augments");
    }

    @Test
    void rerollsRedrawTheCardsUntilTheyRunOut() {
        List<TestPlayer> players = h.join(2);
        h.chestMenu();
        h.settings().set(AugmentSettings.REROLLS, 1);
        AugmentMatch match = h.startFarming();
        h.ticks(2 * 20 + 1);
        TestPlayer player = players.getFirst();
        Offer offer = match.currentOffer(player.getUniqueId());
        List<AugmentInfo> before = offer.cards();
        OfferMenu menu = (OfferMenu) player.getOpenInventory().getTopInventory().getHolder();
        InventoryClickEvent click = new InventoryClickEvent(player.getOpenInventory(), InventoryType.SlotType.CONTAINER,
                22, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        h.server.getPluginManager().callEvent(click);
        assertEquals(0, match.rerollsLeft(player.getUniqueId()));
        assertNotEquals(before, offer.cards(), "new cards");
        assertTrue(offer.cards().stream().anyMatch(card -> !before.contains(card)), "at least one card is new");
        assertEquals(menu, player.getOpenInventory().getTopInventory().getHolder(), "the menu stays open");
        assertFalse(match.reroll(player), "no rerolls left");
    }

    @Test
    void anUnpickedOfferGrantsARandomCardAndClosingTheMenuCanBeUndone() {
        List<TestPlayer> players = h.join(2);
        h.chestMenu();
        AugmentMatch match = h.startFarming();
        h.ticks(2 * 20 + 1);
        TestPlayer closer = players.getFirst();
        List<AugmentInfo> cards = match.currentOffer(closer.getUniqueId()).cards();
        closer.closeInventory(InventoryCloseEvent.Reason.PLAYER);
        assertNull(match.menu(closer.getUniqueId()));
        assertTrue(h.server.dispatchCommand(closer, "게임 증강"));
        assertInstanceOf(OfferMenu.class, closer.getOpenInventory().getTopInventory().getHolder(),
                "/게임 증강 reopens the menu");

        h.ticks(5 * 20 + 1);
        for (TestPlayer player : players) {
            assertEquals(1, match.augments(player.getUniqueId()).size(), "a random card after the time limit");
            assertNull(match.currentOffer(player.getUniqueId()));
        }
        assertTrue(cards.contains(match.augments(closer.getUniqueId()).getFirst()), "one of the shown cards");
    }

    @Test
    void offersQueueAndOwnedAugmentsAreNeverOfferedAgain() {
        List<TestPlayer> players = h.join(2);
        h.noRounds();
        AugmentMatch match = h.startFarming();
        TestPlayer player = players.getFirst();
        match.offer(player, AugmentTier.GOLD, Component.text("a"));
        match.offer(player, AugmentTier.GOLD, Component.text("b"));
        assertEquals(2, match.pendingOffers(player.getUniqueId()));
        AugmentInfo first = match.currentOffer(player.getUniqueId()).cards().getFirst();
        assertTrue(match.pick(player, first));
        Offer next = match.currentOffer(player.getUniqueId());
        assertNotNull(next, "the queued offer opens next");
        assertFalse(next.cards().contains(first), "an owned augment is replaced");
        assertTrue(next.cards().size() >= 3, "three cards (more if the first pick adds a card)");
        assertTrue(next.deadline() > match.clock(), "its timer started");
    }

    @Test
    void teamsWinTogetherAndAugmentsNeverTreatTeammatesAsEnemies() {
        h.hub.config().setTeamSize(2);
        List<TestPlayer> players = h.join(4);
        h.noRounds();
        AugmentMatch match = h.startFight();
        GameSession session = match.session();
        assertTrue(session.isTeamMode());
        TestPlayer a = players.get(0);
        TestPlayer mate = session.teamOf(a).members().stream()
                .filter(id -> !id.equals(a.getUniqueId())).map(id -> (TestPlayer) h.server.getPlayer(id))
                .findFirst().orElseThrow();
        match.grant(a, h.game.registry().get("berserker_pact").info());
        mate.simulateDamage(4, a);
        assertEquals(20, mate.getHealth(), 1.0E-6, "no friendly fire");

        List<TestPlayer> enemies = players.stream().filter(p -> !session.sameTeam(p, a)).toList();
        for (TestPlayer enemy : enemies) {
            session.eliminate(enemy, a);
        }
        assertEquals(GameState.ENDING, session.state());
        assertEquals(session.teamOf(a), session.result().winningTeam());
    }

    @Test
    void eliminatedPlayersDropTheirItemsAndLoseTheirAugments() {
        List<TestPlayer> players = h.join(3);
        h.noRounds();
        AugmentMatch match = h.startFight();
        TestPlayer victim = players.getFirst();
        match.grant(victim, h.game.registry().get("iron_body").info());
        assertEquals(24, victim.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue(), 1.0E-6);
        victim.getInventory().addItem(ItemStack.of(Material.COBBLESTONE, 10));
        List<ItemStack> drops = new java.util.ArrayList<>();
        h.server.getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
            public void onDeath(org.bukkit.event.entity.PlayerDeathEvent event) {
                drops.addAll(event.getDrops());
                assertFalse(event.getKeepInventory(), "items drop in the match world");
            }
        }, h.hub.plugin());
        victim.simulateDamage(2, players.get(1));
        victim.setHealth(0);
        assertFalse(match.session().isAlive(victim));
        assertTrue(drops.stream().anyMatch(item -> item.getType() == Material.COBBLESTONE), "drops are kept");
        assertEquals(20, victim.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue(), 1.0E-6,
                "augments are removed");
        assertEquals(1, match.augments(victim.getUniqueId()).size(), "but still listed for the results");
        assertEquals(1, match.session().stats().of(players.get(1).getUniqueId()).kills());
    }

    @Test
    void enderChestsAndPortalsAreBlockedInTheMatchWorld() {
        List<TestPlayer> players = h.join(2);
        h.noRounds();
        AugmentMatch match = h.startFarming();
        TestPlayer player = players.getFirst();
        player.openInventory(player.getEnderChest());
        org.bukkit.inventory.Inventory top = player.getOpenInventory().getTopInventory();
        assertTrue(top == null || top.getType() != InventoryType.ENDER_CHEST, "the ender chest does not open");
        org.bukkit.event.world.PortalCreateEvent portal = new org.bukkit.event.world.PortalCreateEvent(List.of(),
                match.wild().world(), player, org.bukkit.event.world.PortalCreateEvent.CreateReason.FIRE);
        h.server.getPluginManager().callEvent(portal);
        assertTrue(portal.isCancelled(), "no nether portals");
    }

    @Test
    void settingsAreReadWithDefaultsAndClamps() {
        org.bukkit.configuration.MemoryConfiguration section = new org.bukkit.configuration.MemoryConfiguration();
        AugmentSettings.declareDefaults(section);
        AugmentSettings defaults = AugmentSettings.read(section, h.hub.plugin().getLogger());
        assertEquals(400, defaults.radius());
        assertEquals(1200 * 20, defaults.farmingTicks());
        assertEquals(2, defaults.rerolls());
        assertEquals(4, defaults.rounds().size());
        assertEquals(300 * 20, defaults.rounds().getFirst().atTicks());
        assertEquals(5.0, defaults.rounds().getFirst().percent(AugmentTier.PRISM), 1.0E-6);

        section.set(AugmentSettings.RADIUS, 5);
        section.set(AugmentSettings.CHOICE, 1);
        section.set(AugmentSettings.DIFFICULTY, "nonsense");
        section.set(AugmentSettings.ROUNDS, List.of(Map.of("at", 60, "gold", 1), Map.of("at", 10, "prism", 2),
                Map.of("silver", 1)));
        AugmentSettings clamped = AugmentSettings.read(section, h.hub.plugin().getLogger());
        assertEquals(AugmentSettings.MIN_RADIUS, clamped.radius());
        assertEquals(AugmentSettings.MIN_CHOICE_SECONDS * 20, clamped.choiceTicks());
        assertEquals(org.bukkit.Difficulty.NORMAL, clamped.difficulty());
        assertEquals(2, clamped.rounds().size(), "a round without 'at' is skipped");
        assertEquals(10 * 20, clamped.rounds().getFirst().atTicks(), "sorted by time");
        assertEquals(AugmentTier.PRISM, clamped.rounds().getFirst().roll(new java.util.Random(1)));
    }

    @Test
    void theModeHasSettingsEntriesAndRespondsToCommands() {
        TestPlayer user = h.join("User");
        List<String> ids = h.game.settings().stream().flatMap(section -> section.settings().stream())
                .map(io.github.sx2xoox.mcpl.core.config.Setting::id).toList();
        assertTrue(ids.containsAll(List.of("augment.farming", "augment.radius", "augment.rerolls", "augment.bonus")));
        assertTrue(h.server.dispatchCommand(user, "게임 증강 목록"));
        assertTrue(h.server.dispatchCommand(user, "mcpl augment info double_jump"));
        assertTrue(h.server.dispatchCommand(user, "게임 증강 보너스"));
        assertEquals(h.game, h.hub.games().find("증강 야생전"));
        assertEquals(h.game, h.hub.games().find("증강"));
    }

    @Test
    void lateDamageEventsDoNotBreakAStoppedMatch() {
        List<TestPlayer> players = h.join(2);
        AugmentMatch match = h.startFarming();
        h.hub.games().stop(Component.text("test"));
        players.getFirst().simulateDamage(1, players.get(1));
        h.ticks(5);
        assertNull(h.match());
        assertEquals(GameState.CLOSED, match.session().state());
        assertEquals(List.of("mcpl_wild_1"), h.worlds.deleted);
    }
}
