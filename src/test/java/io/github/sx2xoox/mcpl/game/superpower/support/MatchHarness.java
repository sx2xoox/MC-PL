package io.github.sx2xoox.mcpl.game.superpower.support;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.io.IOException;
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
import io.github.sx2xoox.mcpl.game.superpower.support.world.FlatWorld;
import io.github.sx2xoox.mcpl.game.superpower.support.world.MockEntities;
import org.bukkit.attribute.Attribute;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;
import org.mockbukkit.mockbukkit.MockBukkit;

/**
 * Sets up MockBukkit with the plugin and drives superpower matches quickly (1 s reveal, 1 s prep, no loot chests).
 *
 * <p>{@link #MatchHarness()} uses MockBukkit's simple world (no block shapes, no physics). {@link #flat()} uses a
 * {@link FlatWorld} instead: real ray tracing, collisions and line of sight, and a {@link
 * io.github.sx2xoox.mcpl.game.superpower.support.world.WorldPhysics} step after every server tick.
 */
public final class MatchHarness implements AutoCloseable {

    public final TestServer server;
    public final Hub hub;
    public final SuperpowerGame game;
    private final @Nullable FlatWorld flatWorld;

    public MatchHarness() {
        this(false);
    }

    private MatchHarness(boolean flat) {
        MockEntities.TestMannequin.ignoreDropChances = false;
        server = MockBukkit.mock(new TestServer());
        if (flat) {
            flatWorld = new FlatWorld("world");
            server.addWorld(flatWorld);
        } else {
            flatWorld = null;
            server.addSimpleWorld("world");
        }
        hub = MockBukkit.load(MCPLPlugin.class).hub();
        game = (SuperpowerGame) hub.games().game(SuperpowerGame.ID);
        settings().set("selection.reveal-seconds", 1);
        settings().set("prep-seconds", 1);
        // Loot chests are random blocks in the arena: off unless a test turns them on (LootTest), so ability
        // scenarios keep a predictable arena.
        settings().set("loot.enabled", false);
        ProbeAbility.reset();
    }

    /** A harness on a {@link FlatWorld} with world physics. */
    public static MatchHarness flat() {
        return new MatchHarness(true);
    }

    /** The flat world, or null for the simple world. */
    public @Nullable FlatWorld flatWorld() {
        return flatWorld;
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

    /** Adds {@link DummyAbility} (a neutral ability for targets) to the registry and reloads {@code abilities.yml}. */
    public void registerDummy() {
        if (game.registry().get(DummyAbility.INFO.id()) == null) {
            game.registry().register(DummyAbility.INFO, DummyAbility::new);
        }
        game.abilities().load(game.registry());
    }

    /** Overrides one ability param in {@code abilities.yml} (read by the next match). */
    public void setParam(String abilityId, String key, double value) {
        File file = game.abilities().file();
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        yaml.set("abilities." + abilityId + ".params." + key, value);
        try {
            yaml.save(file);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
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

    /** Runs server ticks (in a flat world each tick is followed by the physics step). */
    public void ticks(int count) {
        for (int i = 0; i < count; i++) {
            tick();
        }
    }

    private void tick() {
        server.getScheduler().performOneTick();
        if (flatWorld != null) {
            flatWorld.physics().tick();
        }
    }

    public void tickUntil(BooleanSupplier condition, int maxTicks, String what) {
        for (int i = 0; i < maxTicks; i++) {
            if (condition.getAsBoolean()) {
                return;
            }
            tick();
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
