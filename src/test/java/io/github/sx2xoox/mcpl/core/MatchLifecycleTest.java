package io.github.sx2xoox.mcpl.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.sx2xoox.mcpl.MCPLPlugin;
import io.github.sx2xoox.mcpl.core.game.GameResult;
import io.github.sx2xoox.mcpl.core.game.GameSession;
import io.github.sx2xoox.mcpl.core.game.GameStartException;
import io.github.sx2xoox.mcpl.core.game.GameState;
import io.github.sx2xoox.mcpl.core.game.MatchTeam;
import net.kyori.adventure.text.Component;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

class MatchLifecycleTest {

    private ServerMock server;
    private Hub hub;
    private FakeGame game;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        server.addSimpleWorld("world");
        MCPLPlugin plugin = MockBukkit.load(MCPLPlugin.class);
        hub = plugin.hub();
        game = new FakeGame("fake");
        hub.games().register(game);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private int countdownTicks() {
        return hub.config().countdownSeconds() * 20 + 5;
    }

    private int endingTicks() {
        return hub.config().endingSeconds() * 20 + 5;
    }

    @Test
    void fullMatchIsPlayedAndEveryoneIsRestored() throws GameStartException {
        PlayerMock alice = server.addPlayer("Alice");
        PlayerMock bob = server.addPlayer("Bob");
        WorldMock world = server.getWorld("world") instanceof WorldMock mock ? mock : null;
        assertNotNull(world);
        Location aliceStart = new Location(world, 10.5, 70, -4.5);
        alice.teleport(aliceStart);
        alice.setGameMode(GameMode.SURVIVAL);
        alice.getInventory().setItem(3, new ItemStack(Material.DIAMOND, 7));
        alice.setHealth(13);
        alice.setLevel(12);

        GameSession session = hub.games().start(game, null);
        assertSame(session, hub.games().session());
        assertEquals(GameState.COUNTDOWN, session.state());
        assertEquals(2, session.participantIds().size());
        assertThrows(GameStartException.class, () -> hub.games().start(game, null), "only one match at a time");

        server.getScheduler().performTicks(countdownTicks());
        FakeGame.FakeInstance instance = game.last();
        assertEquals(GameState.RUNNING, session.state());
        assertEquals(1, instance.starts);
        assertTrue(instance.ticks > 0);
        assertTrue(hub.snapshots().has(alice.getUniqueId()), "snapshot persisted");
        assertTrue(alice.getInventory().isEmpty(), "participants are reset");
        assertEquals(GameMode.ADVENTURE, alice.getGameMode());
        assertEquals(0, alice.getLevel());

        session.eliminate(bob, alice);
        assertEquals(GameMode.SPECTATOR, bob.getGameMode());
        assertEquals(1, session.stats().of(alice.getUniqueId()).kills());
        assertEquals(1, session.stats().of(bob.getUniqueId()).deaths());
        assertEquals(1, instance.eliminated.size());

        // the fake mode finishes as soon as one player is left
        assertEquals(GameState.ENDING, session.state());
        GameResult result = session.result();
        assertNotNull(result);
        assertTrue(result.isWinner(alice.getUniqueId()));
        assertEquals(1, instance.stops, "stop() is called when the match finishes");

        session.finish(GameResult.draw());
        assertEquals(1, instance.stops, "finishing twice is ignored");

        server.getScheduler().performTicks(endingTicks());
        assertEquals(GameState.CLOSED, session.state());
        assertNull(hub.games().session());
        assertEquals(1, instance.stops, "stop() is called exactly once");

        assertEquals(GameMode.SURVIVAL, alice.getGameMode());
        ItemStack diamonds = alice.getInventory().getItem(3);
        assertNotNull(diamonds);
        assertEquals(Material.DIAMOND, diamonds.getType());
        assertEquals(7, diamonds.getAmount());
        assertEquals(13, alice.getHealth(), 0.001);
        assertEquals(12, alice.getLevel());
        assertEquals(aliceStart.getX(), alice.getLocation().getX(), 0.001);
        assertEquals(aliceStart.getZ(), alice.getLocation().getZ(), 0.001);
        assertFalse(hub.snapshots().has(alice.getUniqueId()), "snapshot is consumed after restore");
        assertFalse(hub.snapshots().has(bob.getUniqueId()));
        assertEquals(GameMode.SURVIVAL, bob.getGameMode());
    }

    @Test
    void deathAfterAHitCreditsTheAttacker() throws GameStartException {
        PlayerMock alice = server.addPlayer("Alice");
        PlayerMock bob = server.addPlayer("Bob");
        PlayerMock carol = server.addPlayer("Carol");
        GameSession session = hub.games().start(game, null);
        server.getScheduler().performTicks(countdownTicks());
        assertEquals(GameState.RUNNING, session.state());

        bob.simulateDamage(4, alice);
        assertEquals(4, session.stats().of(alice.getUniqueId()).damageDealt(), 0.001);
        assertEquals(alice, hub.combat().lastAttacker(bob));

        bob.setHealth(0); // e.g. a fall right after the hit
        assertFalse(session.isAlive(bob), "the default outcome eliminates");
        assertEquals(1, session.stats().of(alice.getUniqueId()).kills(), "kill credited to the last attacker");
        assertEquals(1, session.stats().of(bob.getUniqueId()).deaths());
        assertEquals(GameState.RUNNING, session.state(), "two players are still alive");
        assertTrue(session.isAlive(carol));

        hub.games().stop(Component.text("test"));
    }

    @Test
    void countdownAbortsWhenPlayersLeave() throws GameStartException {
        server.addPlayer("Alice");
        PlayerMock bob = server.addPlayer("Bob");
        GameSession session = hub.games().start(game, null);

        bob.disconnect();
        server.getScheduler().performTicks(40);

        assertEquals(GameState.CLOSED, session.state());
        assertNull(hub.games().session());
        assertEquals(0, game.last().starts, "never started");
        assertEquals(1, game.last().stops, "stop() is still called exactly once");
    }

    @Test
    void notEnoughPlayersCannotStart() {
        server.addPlayer("Alone");
        assertThrows(GameStartException.class, () -> hub.games().start(game, null));
        assertNull(hub.games().session());
    }

    @Test
    void spectatorPreferenceMakesWatchers() throws GameStartException {
        PlayerMock alice = server.addPlayer("Alice");
        PlayerMock bob = server.addPlayer("Bob");
        PlayerMock carol = server.addPlayer("Carol");
        carol.setGameMode(GameMode.CREATIVE);
        hub.games().setPrefersSpectating(carol, true);

        GameSession session = hub.games().start(game, null);
        server.getScheduler().performTicks(countdownTicks());

        assertEquals(GameState.RUNNING, session.state());
        assertFalse(session.isParticipant(carol));
        assertTrue(session.isSpectator(carol));
        assertEquals(GameMode.SPECTATOR, carol.getGameMode());
        assertTrue(session.isAlive(alice) && session.isAlive(bob));

        hub.games().stop(Component.text("test"));
        assertNull(hub.games().session());
        assertEquals(GameMode.CREATIVE, carol.getGameMode(), "watchers are restored too");
        assertEquals(1, game.last().stops);
    }

    @Test
    void quittingParticipantIsEliminatedAndRestoredOnRejoin() throws GameStartException {
        PlayerMock alice = server.addPlayer("Alice");
        PlayerMock bob = server.addPlayer("Bob");
        PlayerMock carol = server.addPlayer("Carol");
        carol.getInventory().setItem(0, new ItemStack(Material.EMERALD, 3));
        GameSession session = hub.games().start(game, null);
        server.getScheduler().performTicks(countdownTicks());
        assertTrue(carol.getInventory().isEmpty());

        carol.disconnect();
        assertFalse(session.isAlive(carol.getUniqueId()));
        assertTrue(hub.snapshots().has(carol.getUniqueId()), "snapshot kept for the next join");
        assertEquals(GameState.RUNNING, session.state(), "two players are still alive");

        session.finish(GameResult.winner(alice));
        server.getScheduler().performTicks(endingTicks());
        assertNull(hub.games().session());
        assertTrue(hub.snapshots().has(carol.getUniqueId()));

        carol.reconnect();
        assertFalse(hub.snapshots().has(carol.getUniqueId()), "restored on join");
        ItemStack emeralds = carol.getInventory().getItem(0);
        assertNotNull(emeralds);
        assertEquals(Material.EMERALD, emeralds.getType());
        assertNotNull(bob);
    }

    @Test
    void teamModeSplitsParticipantsAndMirrorsTeams() throws GameStartException {
        FakeGame teamGame = new FakeGame("teamfake", true);
        hub.games().register(teamGame);
        hub.config().setTeamSize(2);
        PlayerMock[] players = {
                server.addPlayer("P1"), server.addPlayer("P2"), server.addPlayer("P3"), server.addPlayer("P4")};

        GameSession session = hub.games().start(teamGame, null);
        server.getScheduler().performTicks(countdownTicks());

        assertEquals(GameState.RUNNING, session.state());
        assertTrue(session.isTeamMode());
        assertEquals(2, session.teams().size());
        for (MatchTeam team : session.teams()) {
            assertEquals(2, team.size());
        }
        for (PlayerMock player : players) {
            MatchTeam team = session.teamOf(player);
            assertNotNull(team);
            Team mirrored = player.getScoreboard().getTeam("mcpl_" + team.id());
            assertNotNull(mirrored, "match teams are mirrored on every viewer's scoreboard");
            assertTrue(mirrored.hasEntry(player.getName()));
            assertFalse(mirrored.allowFriendlyFire());
        }
        MatchTeam first = session.teams().getFirst();
        java.util.Iterator<java.util.UUID> members = first.members().iterator();
        assertTrue(session.sameTeam(members.next(), members.next()));

        Scoreboard matchBoard = players[0].getScoreboard();
        hub.games().stop(Component.text("test"));
        assertNotSame(matchBoard, players[0].getScoreboard(), "the original scoreboard is restored");
    }

    @Test
    void gameConfigDefaultsAreMerged() {
        assertEquals(300, hub.config().gameSection("fake").getInt("round-seconds"));
    }
}
