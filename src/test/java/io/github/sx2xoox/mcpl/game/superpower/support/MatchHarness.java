package io.github.sx2xoox.mcpl.game.superpower.support;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import io.github.sx2xoox.mcpl.MCPLPlugin;
import io.github.sx2xoox.mcpl.core.Hub;
import io.github.sx2xoox.mcpl.core.game.GameSession;
import io.github.sx2xoox.mcpl.core.game.GameStartException;
import io.github.sx2xoox.mcpl.game.superpower.SuperpowerGame;
import io.github.sx2xoox.mcpl.game.superpower.runtime.MatchPhase;
import io.github.sx2xoox.mcpl.game.superpower.runtime.SuperpowerMatch;
import org.bukkit.attribute.Attribute;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;
import org.mockbukkit.mockbukkit.MockBukkit;

/** Sets up MockBukkit with the plugin and drives superpower matches quickly (1 s reveal, 1 s prep). */
public final class MatchHarness implements AutoCloseable {

    public final TestServer server;
    public final Hub hub;
    public final SuperpowerGame game;

    public MatchHarness() {
        server = MockBukkit.mock(new TestServer());
        server.addSimpleWorld("world");
        hub = MockBukkit.load(MCPLPlugin.class).hub();
        game = (SuperpowerGame) hub.games().game(SuperpowerGame.ID);
        settings().set("selection.reveal-seconds", 1);
        settings().set("prep-seconds", 1);
        ProbeAbility.reset();
    }

    /** The live {@code games.superpower} section (read by the next match). */
    public ConfigurationSection settings() {
        return hub.config().gameSection(SuperpowerGame.ID);
    }

    public TestPlayer join(String name) {
        TestPlayer player = new TestPlayer(server, name);
        server.addPlayer(player);
        return player;
    }

    public List<TestPlayer> join(int count) {
        List<TestPlayer> players = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            players.add(join("P" + i));
        }
        return players;
    }

    /** Adds {@link ProbeAbility} and {@link BrokenAbility} to the registry and reloads {@code abilities.yml}. */
    public void registerTestAbilities() {
        game.registry().register(ProbeAbility.INFO, ProbeAbility::new);
        game.registry().register(BrokenAbility.INFO, BrokenAbility::new);
        game.abilities().load(game.registry());
    }

    /**
     * Full health for everyone (after {@code replaceAbility} in tests: a passive that lowered max health, like the
     * pixie's, clamps the health, and removing it later does not give the health back).
     */
    public void healAll(List<? extends Player> players) {
        for (Player player : players) {
            player.setHealth(player.getAttribute(Attribute.MAX_HEALTH).getValue());
        }
    }

    public GameSession start() {
        try {
            return hub.games().start(game, null);
        } catch (GameStartException e) {
            throw new AssertionError("match did not start", e);
        }
    }

    public @Nullable SuperpowerMatch match() {
        return game.currentMatch();
    }

    public void ticks(int count) {
        server.getScheduler().performTicks(count);
    }

    public void tickUntil(BooleanSupplier condition, int maxTicks, String what) {
        for (int i = 0; i < maxTicks; i++) {
            if (condition.getAsBoolean()) {
                return;
            }
            server.getScheduler().performTicks(1);
        }
        if (!condition.getAsBoolean()) {
            fail("timed out waiting for " + what);
        }
    }

    public void tickUntilPhase(MatchPhase phase) {
        tickUntil(() -> match() != null && match().phase() == phase, 2000, "phase " + phase);
    }

    /** Starts a match and runs it to the fight phase. */
    public SuperpowerMatch startFight() {
        GameSession session = start();
        tickUntilPhase(MatchPhase.FIGHT);
        assertTrue(session.isRunning());
        return match();
    }

    @Override
    public void close() {
        MockBukkit.unmock();
    }
}
