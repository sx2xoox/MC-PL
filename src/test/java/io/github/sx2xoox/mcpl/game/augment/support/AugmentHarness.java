package io.github.sx2xoox.mcpl.game.augment.support;

import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import io.github.sx2xoox.mcpl.MCPLPlugin;
import io.github.sx2xoox.mcpl.core.Hub;
import io.github.sx2xoox.mcpl.core.game.GameSession;
import io.github.sx2xoox.mcpl.core.game.GameStartException;
import io.github.sx2xoox.mcpl.game.augment.AugmentGame;
import io.github.sx2xoox.mcpl.game.augment.AugmentSettings;
import io.github.sx2xoox.mcpl.game.augment.runtime.AugmentMatch;
import io.github.sx2xoox.mcpl.game.augment.runtime.MatchPhase;
import io.github.sx2xoox.mcpl.game.superpower.support.TestPlayer;
import io.github.sx2xoox.mcpl.game.superpower.support.TestServer;
import io.github.sx2xoox.mcpl.game.superpower.support.world.FlatWorld;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jspecify.annotations.Nullable;
import org.mockbukkit.mockbukkit.MockBukkit;

/**
 * MockBukkit with the plugin and a {@link TestWorldProvider}, driving quick 증강 야생전 matches: the "vanilla" match
 * world is a flat mock world, farming lasts 10 s (no invulnerability, hunger as usual), one augment round at 2 s,
 * cards (dialogs, recorded by {@link #dialogs}) time out after 5 s.
 */
public final class AugmentHarness implements AutoCloseable {

    public final TestServer server;
    public final Hub hub;
    public final AugmentGame game;
    public final TestWorldProvider worlds;
    public final FlatWorld lobby;
    /** Records the augment pick dialogs (MockBukkit cannot build Paper dialogs). */
    public final TestDialogs dialogs = new TestDialogs();

    public AugmentHarness() {
        server = MockBukkit.mock(new TestServer());
        lobby = new FlatWorld("world");
        server.addWorld(lobby);
        hub = MockBukkit.load(MCPLPlugin.class).hub();
        game = (AugmentGame) hub.games().game(AugmentGame.ID);
        worlds = new TestWorldProvider(server);
        hub.tempWorlds().provider(worlds);
        game.offerDialogs(dialogs);
        ConfigurationSection s = settings();
        s.set(AugmentSettings.RADIUS, 64);
        s.set(AugmentSettings.FARMING, 10);
        s.set(AugmentSettings.CHOICE, 5);
        s.set(AugmentSettings.BORDER_DELAY, 2);
        s.set(AugmentSettings.BORDER_SHRINK, 4);
        s.set(AugmentSettings.ROUNDS, List.of(Map.of("at", 2, "silver", 1, "gold", 0, "prism", 0)));
        // most tests hurt players with augments while farming; FarmingGraceTest turns the grace on
        s.set(AugmentSettings.FARMING_INVULNERABLE, false);
        s.set(AugmentSettings.FARMING_NO_HUNGER, false);
    }

    /** Cards in the chest menu instead of the dialog ({@code augments.ui: CHEST}). */
    public void chestMenu() {
        settings().set(AugmentSettings.OFFER_UI, AugmentSettings.OfferUi.CHEST.name());
    }

    /** The live {@code games.augment} section (read when the next match is started). */
    public ConfigurationSection settings() {
        return hub.config().gameSection(AugmentGame.ID);
    }

    /** No timed rounds (bonus and augment tests grant on their own). */
    public void noRounds() {
        settings().set(AugmentSettings.ROUNDS, List.of());
    }

    public TestPlayer join(String name) {
        TestPlayer player = new TestPlayer(server, name);
        server.addPlayer(player);
        player.teleport(lobby.getSpawnLocation());
        return player;
    }

    public List<TestPlayer> join(int count) {
        List<TestPlayer> players = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            players.add(join("P" + i));
        }
        return players;
    }

    /** Overrides one param in {@code augments.yml} ({@code augments.<id>} or {@code bonuses.<id>}). */
    public void setParam(String section, String id, String key, double value) {
        File file = game.augments().file();
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        yaml.set(section + "." + id + ".params." + key, value);
        try {
            yaml.save(file);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        game.augments().load(game.registry());
    }

    public GameSession start() {
        try {
            return hub.games().start(game, null);
        } catch (GameStartException e) {
            throw new AssertionError("match did not start", e);
        }
    }

    public @Nullable AugmentMatch match() {
        return game.currentMatch();
    }

    public void ticks(int count) {
        server.getScheduler().performTicks(count);
    }

    public void tickUntil(BooleanSupplier condition, int maxTicks, String what) {
        for (int i = 0; i < maxTicks && !condition.getAsBoolean(); i++) {
            server.getScheduler().performOneTick();
        }
        if (!condition.getAsBoolean()) {
            fail("timed out waiting for " + what);
        }
    }

    public void tickUntilPhase(MatchPhase phase) {
        tickUntil(() -> match() != null && match().phase() == phase, 3000, "phase " + phase);
    }

    /** Starts a match and runs it to the farming phase. */
    public AugmentMatch startFarming() {
        start();
        tickUntilPhase(MatchPhase.FARMING);
        return match();
    }

    /** Starts a match and runs it to the fight phase. */
    public AugmentMatch startFight() {
        start();
        tickUntilPhase(MatchPhase.FIGHT);
        return match();
    }

    @Override
    public void close() {
        MockBukkit.unmock();
    }
}
