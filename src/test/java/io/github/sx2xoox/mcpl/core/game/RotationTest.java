package io.github.sx2xoox.mcpl.core.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import io.github.sx2xoox.mcpl.MCPLPlugin;
import io.github.sx2xoox.mcpl.core.FakeGame;
import io.github.sx2xoox.mcpl.core.Hub;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

class RotationTest {

    @Test
    void picksTheNextKnownGame() {
        List<String> ids = List.of("a", "b", "c");
        Set<String> known = Set.of("a", "b", "c");
        assertEquals("a", Rotation.pickNext(ids, null, known::contains));
        assertEquals("b", Rotation.pickNext(ids, "a", known::contains));
        assertEquals("c", Rotation.pickNext(ids, "b", known::contains));
        assertEquals("a", Rotation.pickNext(ids, "c", known::contains), "wraps around");
        assertEquals("a", Rotation.pickNext(ids, "unknown", known::contains));
    }

    @Test
    void skipsUnknownGames() {
        List<String> ids = List.of("a", "missing", "c");
        Set<String> known = Set.of("a", "c");
        assertEquals("c", Rotation.pickNext(ids, "a", known::contains));
        assertEquals("a", Rotation.pickNext(ids, "c", known::contains));
        assertEquals("c", Rotation.pickNext(ids, "missing", known::contains));
        assertNull(Rotation.pickNext(ids, "a", id -> false));
        assertNull(Rotation.pickNext(List.of(), null, known::contains));
    }

    @Test
    void startsTheNextGameAfterTheIntermission() throws GameStartException {
        ServerMock server = MockBukkit.mock();
        try {
            server.addSimpleWorld("world");
            Hub hub = MockBukkit.load(MCPLPlugin.class).hub();
            FakeGame first = new FakeGame("first");
            FakeGame second = new FakeGame("second");
            hub.games().register(first);
            hub.games().register(second);
            hub.config().setRotationGames(List.of("first", "second"));
            server.addPlayer("Alice");
            server.addPlayer("Bob");

            GameSession session = hub.games().start(first, null);
            hub.games().rotation().setEnabled(true);
            assertFalse(hub.games().rotation().isWaiting(), "no intermission while a match runs");
            server.getScheduler().performTicks(hub.config().countdownSeconds() * 20L + 5);
            session.finish(GameResult.draw());
            server.getScheduler().performTicks(hub.config().endingSeconds() * 20L + 5);

            assertNull(hub.games().session());
            assertTrue(hub.games().rotation().isWaiting(), "intermission after the match");
            assertEquals(second, hub.games().rotation().next());

            server.getScheduler().performTicks(hub.config().intermissionSeconds() * 20L + 25);
            GameSession next = hub.games().session();
            assertNotNull(next, "rotation started the next match");
            assertEquals(second, next.game());
            assertFalse(hub.games().rotation().isWaiting());
        } finally {
            MockBukkit.unmock();
        }
    }

    @Test
    void waitsForPlayers() {
        ServerMock server = MockBukkit.mock();
        try {
            server.addSimpleWorld("world");
            Hub hub = MockBukkit.load(MCPLPlugin.class).hub();
            hub.games().register(new FakeGame("only"));
            hub.config().setRotationGames(List.of("only"));
            server.addPlayer("Alone");
            hub.games().rotation().setEnabled(true);
            assertTrue(hub.games().rotation().isWaiting());

            server.getScheduler().performTicks(hub.config().intermissionSeconds() * 20L + 60);
            assertNull(hub.games().session(), "one player is not enough");
            assertTrue(hub.games().rotation().isWaiting(), "keeps waiting");

            server.addPlayer("Friend");
            server.getScheduler().performTicks(25);
            assertNotNull(hub.games().session(), "starts as soon as enough players are online");
        } finally {
            MockBukkit.unmock();
        }
    }
}
