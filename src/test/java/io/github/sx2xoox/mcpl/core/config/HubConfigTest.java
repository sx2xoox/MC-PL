package io.github.sx2xoox.mcpl.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import io.github.sx2xoox.mcpl.MCPLPlugin;
import io.github.sx2xoox.mcpl.core.FakeGame;
import io.github.sx2xoox.mcpl.core.Hub;
import io.github.sx2xoox.mcpl.core.game.GameManager;
import io.github.sx2xoox.mcpl.game.augment.AugmentSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class HubConfigTest {

    /** A tab cannot indent YAML: the whole file fails to parse. */
    private static final String BROKEN = "match:\n\tteam-size: 2\n";

    private ServerMock server;
    private MCPLPlugin plugin;
    private Hub hub;
    private File file;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        server.addSimpleWorld("world");
        plugin = MockBukkit.load(MCPLPlugin.class);
        hub = plugin.hub();
        file = new File(plugin.getDataFolder(), "config.yml");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private int brokenCopies() {
        File[] copies = plugin.getDataFolder().listFiles((dir, name) -> name.startsWith("config.yml.broken-"));
        assertNotNull(copies);
        return copies.length;
    }

    @Test
    void aBrokenFileIsNeverOverwrittenOnReload() throws IOException {
        hub.config().setTeamSize(3);
        Files.writeString(file.toPath(), BROKEN);

        assertThrows(IllegalStateException.class, hub::reload, "/게임 리로드 reports the failure");
        assertEquals(BROKEN, Files.readString(file.toPath()), "the owner's file is kept as is");
        assertEquals(3, hub.config().teamSize(), "the previous values stay in effect");
        assertFalse(hub.config().isWritable());
        assertEquals(1, brokenCopies(), "a copy is kept aside");

        hub.config().setTeamSize(4); // in memory only
        hub.games().register(new FakeGame("late")); // would add its defaults and save
        assertEquals(BROKEN, Files.readString(file.toPath()));

        PlayerMock admin = server.addPlayer("Admin");
        admin.setOp(true);
        server.dispatchCommand(admin, "게임 설정 팀 2");
        assertEquals(4, hub.config().teamSize(), "setting commands refuse while the file is broken");
        assertEquals(BROKEN, Files.readString(file.toPath()));

        Files.writeString(file.toPath(), "match:\n  team-size: 2\n");
        hub.reload();
        assertTrue(hub.config().isWritable(), "a fixed file is loaded and saved again");
        assertEquals(2, hub.config().teamSize());
        assertTrue(Files.readString(file.toPath()).contains("config-version"), "missing keys are added back");
    }

    @Test
    void aBrokenFileAtStartupUsesTheDefaultsWithoutSaving() throws IOException {
        String broken = "arena:\n  world: 'unterminated\n";
        Files.writeString(file.toPath(), broken);
        HubConfig fresh = new HubConfig(plugin);
        fresh.load(); // startup never throws

        assertFalse(fresh.isWritable());
        assertEquals(60, fresh.arenaRadius(), "built-in defaults");
        fresh.setTeamSize(5);
        fresh.applyGameDefaults("other", defaults -> defaults.set("x", 1));
        assertEquals(broken, Files.readString(file.toPath()));
    }

    /** YAML reads an unquoted {@code OFF} as false, which must still switch the terrain damage off. */
    @Test
    void abilityBlockDamageReadsTheWordsTheCommentsSuggest() throws IOException {
        Files.writeString(file.toPath(), "protection:\n  ability-block-damage: OFF\n");
        hub.reload();
        assertEquals(BlockDamageScope.OFF, hub.config().abilityBlockDamage());

        Files.writeString(file.toPath(), "protection:\n  ability-block-damage: ALL_WORLDS\n");
        hub.reload();
        assertEquals(BlockDamageScope.ALL_WORLDS, hub.config().abilityBlockDamage());

        Files.writeString(file.toPath(), "protection:\n  ability-block-damage: on\n");
        hub.reload();
        assertEquals(BlockDamageScope.MAP_WORLDS, hub.config().abilityBlockDamage());

        Files.writeString(file.toPath(), "protection:\n  ability-block-damage: map-worlds\n");
        hub.reload();
        assertEquals(BlockDamageScope.MAP_WORLDS, hub.config().abilityBlockDamage());

        hub.config().setAbilityBlockDamage(BlockDamageScope.OFF);
        hub.reload();
        assertEquals(BlockDamageScope.OFF, hub.config().abilityBlockDamage(), "saved and read back");
    }

    @Test
    void aBrokenPlayersFileIsNotOverwritten() throws IOException {
        File players = new File(plugin.getDataFolder(), "players.yml");
        String broken = "spectators:\n\t- x\n";
        Files.writeString(players.toPath(), broken);
        GameManager manager = new GameManager(hub);
        manager.enable();

        manager.setPrefersSpectating(server.addPlayer("User"), true);
        assertEquals(broken, Files.readString(players.toPath()));
    }

    /**
     * A default that changed between versions reaches a server whose file still holds the old one, unless the owner
     * changed it: through the retired defaults the first time, through the snapshot of the last declared defaults
     * afterwards.
     */
    @Test
    void staleDefaultsFollowTheNewOnesButOwnerValuesStay() {
        HubConfig config = hub.config();
        config.updateGameSection("upgrade", s -> {
            s.set("a", 6); // an old default nobody touched (as YAML may read it: a whole number)
            s.set("b", 3); // the owner's own value
            s.set("c", 1.5);
        });
        assertTrue(config.applyGameDefaults("upgrade", d -> {
            d.set("a", 5.0);
            d.set("b", 5.0);
            d.set("c", 1.5);
            d.set("d", 2);
        }, retired -> {
            retired.set("a", 6.0);
            retired.set("b", 4.0);
        }));
        assertEquals(5.0, config.gameSection("upgrade").getDouble("a"), 1.0E-9, "the retired default moved on");
        assertEquals(3, config.gameSection("upgrade").getDouble("b"), 1.0E-9, "the owner's value stays");
        assertEquals(2, config.gameSection("upgrade").getInt("d"), "a new key is added");

        // next version: the snapshot remembers 5.0 / 5.0 / 1.5 / 2 as the last declared defaults
        config.updateGameSection("upgrade", s -> s.set("c", 9));
        config.applyGameDefaults("upgrade", d -> {
            d.set("a", 4.0);
            d.set("b", 4.0);
            d.set("c", 2.0);
            d.set("d", 3);
        }, retired -> retired.set("a", 99));
        assertEquals(4.0, config.gameSection("upgrade").getDouble("a"), 1.0E-9);
        assertEquals(3, config.gameSection("upgrade").getDouble("b"), 1.0E-9);
        assertEquals(9, config.gameSection("upgrade").getDouble("c"), 1.0E-9, "changed by the owner since");
        assertEquals(3, config.gameSection("upgrade").getInt("d"));
    }

    @Test
    void theFirstAugmentBotCapOf15MovesToTheNewDefault() {
        HubConfig config = hub.config();
        config.updateGameSection("augbots", s -> io.github.sx2xoox.mcpl.game.augment.bot.BotSettings
                .declareRetiredDefaults(s));
        config.applyGameDefaults("augbots", io.github.sx2xoox.mcpl.game.augment.bot.BotSettings::declareDefaults,
                io.github.sx2xoox.mcpl.game.augment.bot.BotSettings::declareRetiredDefaults);
        org.bukkit.configuration.ConfigurationSection s = config.gameSection("augbots");
        assertEquals(30, s.getInt(io.github.sx2xoox.mcpl.game.augment.bot.BotSettings.MAX), "untouched 15 follows");
        assertTrue(s.getStringList("bots.names").size() > 15, "the old 15 names follow too");

        config.updateGameSection("augbots2", t -> t.set(io.github.sx2xoox.mcpl.game.augment.bot.BotSettings.MAX, 20));
        config.applyGameDefaults("augbots2", io.github.sx2xoox.mcpl.game.augment.bot.BotSettings::declareDefaults,
                io.github.sx2xoox.mcpl.game.augment.bot.BotSettings::declareRetiredDefaults);
        assertEquals(20, config.gameSection("augbots2").getInt(io.github.sx2xoox.mcpl.game.augment.bot.BotSettings.MAX),
                "the owner's cap stays");
    }

    /** The augment rounds of the first versions as a server's config.yml holds them (whole numbers, one a decimal). */
    private static final String OLD_ROUNDS = """
            games:
              augrounds:
                augments:
                  ui: DIALOG
                  rounds:
                  - at: 300
                    silver: 70
                    gold: 25
                    prism: 5
                  - at: 720
                    silver: 40
                    gold: 45
                    prism: 15
                  - at: 1200.0
                    silver: 20
                    gold: 50
                    prism: 30
                  - at: 1800
                    silver: 10
                    gold: 45
                    prism: 45
            """;

    private AugmentSettings augmentSettings(String game) {
        return AugmentSettings.read(hub.config().gameSection(game), plugin.getLogger());
    }

    /** "증강 주는 시간이 예전이랑 똑같다": untouched 5/12/20/30 min rounds (and the dialog) follow the new defaults. */
    @Test
    void theFirstAugmentRoundsMoveToTheNewDefaults() throws IOException {
        Files.writeString(file.toPath(), OLD_ROUNDS);
        hub.config().load();
        assertEquals(300 * 20, augmentSettings("augrounds").rounds().getFirst().atTicks(), "as the server had it");

        hub.config().applyGameDefaults("augrounds", AugmentSettings::declareDefaults,
                AugmentSettings::declareRetiredDefaults);
        hub.config().load(); // as the file reads after a restart
        AugmentSettings settings = augmentSettings("augrounds");
        assertEquals(List.of(180L * 20, 480L * 20, 780L * 20, 1080L * 20),
                settings.rounds().stream().map(AugmentSettings.Round::atTicks).toList(), "3, 8, 13 and 18 minutes");
        assertEquals(AugmentSettings.OfferUi.CARDS, settings.offerUi());

        // an owner's own rounds stay
        Files.writeString(file.toPath(), OLD_ROUNDS.replace("at: 300", "at: 240").replace("augrounds", "augown"));
        hub.config().load();
        hub.config().applyGameDefaults("augown", AugmentSettings::declareDefaults,
                AugmentSettings::declareRetiredDefaults);
        assertEquals(240 * 20, augmentSettings("augown").rounds().getFirst().atTicks());
    }

    /**
     * A server that already ran a version with the defaults copy (which did not list the rounds as retired) kept the
     * old rounds: the retired rounds are compared once more, and only once (an owner's later pick stays).
     */
    @Test
    void retiredDefaultsAddedLaterStillReachAServerWithTheDefaultsCopy() throws IOException {
        Files.writeString(file.toPath(), OLD_ROUNDS);
        hub.config().load();
        hub.config().applyGameDefaults("augrounds", AugmentSettings::declareDefaults, null);
        assertEquals(300 * 20, augmentSettings("augrounds").rounds().getFirst().atTicks(), "the old bug");

        hub.config().applyGameDefaults("augrounds", AugmentSettings::declareDefaults,
                AugmentSettings::declareRetiredDefaults);
        assertEquals(180 * 20, augmentSettings("augrounds").rounds().getFirst().atTicks(), "fixed on the next start");
        assertEquals(AugmentSettings.OfferUi.CARDS, augmentSettings("augrounds").offerUi());

        hub.config().setGameValue("augrounds", AugmentSettings.OFFER_UI, "DIALOG");
        hub.config().applyGameDefaults("augrounds", AugmentSettings::declareDefaults,
                AugmentSettings::declareRetiredDefaults);
        assertEquals(AugmentSettings.OfferUi.DIALOG, augmentSettings("augrounds").offerUi(),
                "the owner chose the dialog again after the upgrade");
    }

    @Test
    void sameValueComparesListsOfMapsByNumberValue() {
        assertTrue(HubConfig.sameValue(List.of(Map.of("at", 300, "x", "a")), List.of(Map.of("at", 300.0, "x", "a"))));
        assertFalse(HubConfig.sameValue(List.of(Map.of("at", 300)), List.of(Map.of("at", 301))));
        assertFalse(HubConfig.sameValue(List.of(1, 2), List.of(1)));
    }
}
