package io.github.sx2xoox.mcpl.core.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.sx2xoox.mcpl.MCPLPlugin;
import io.github.sx2xoox.mcpl.core.FakeGame;
import io.github.sx2xoox.mcpl.core.Hub;
import net.kyori.adventure.text.Component;
import org.bukkit.WorldBorder;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldBorderMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

/** Virtual borders must be sent again whenever the client receives the level's real border (join, world change). */
class SessionBorderTest {

    /** MockBukkit cannot create virtual borders; hand out plain border mocks. (ServerMock's ban lists are raw.) */
    @SuppressWarnings("unchecked")
    private static final class BorderServer extends ServerMock {
        @Override
        public WorldBorder createWorldBorder() {
            return new WorldBorderMock(getWorlds().getFirst());
        }
    }

    /** Counts the virtual borders sent to the client. */
    private static final class BorderPlayer extends PlayerMock {
        int shown;
        WorldBorder border;

        BorderPlayer(ServerMock server, String name) {
            super(server, name);
        }

        @Override
        public void setWorldBorder(WorldBorder border) {
            if (border != null) {
                shown++;
            }
            this.border = border;
        }

        @Override
        public WorldBorder getWorldBorder() {
            return border;
        }
    }

    @Test
    void bordersAreSentAgainAfterAWorldChangeAndAJoin() throws GameStartException {
        BorderServer server = MockBukkit.mock(new BorderServer());
        try {
            server.addSimpleWorld("world");
            WorldMock other = server.addSimpleWorld("other");
            Hub hub = MockBukkit.load(MCPLPlugin.class).hub();
            FakeGame game = new FakeGame("fake");
            hub.games().register(game);
            BorderPlayer alice = new BorderPlayer(server, "Alice");
            server.addPlayer(alice);
            server.addPlayer(new BorderPlayer(server, "Bob"));

            GameSession session = hub.games().start(game, null);
            server.getScheduler().performTicks(hub.config().countdownSeconds() * 20L + 5);
            assertEquals(GameState.RUNNING, session.state());
            session.createBorder(50);
            assertEquals(1, alice.shown);

            alice.teleport(other.getSpawnLocation()); // the client now has the other world's real border
            assertEquals(2, alice.shown, "sent again after the world change");

            BorderPlayer carol = new BorderPlayer(server, "Carol");
            server.addPlayer(carol); // joins mid-match as a spectator
            int atJoin = carol.shown;
            server.getScheduler().performTicks(2);
            assertTrue(carol.shown > atJoin, "sent again after the join packets, which carry the real border");

            hub.games().stop(Component.text("test"));
        } finally {
            MockBukkit.unmock();
        }
    }
}
