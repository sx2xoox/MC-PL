package io.github.sx2xoox.mcpl.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import io.github.sx2xoox.mcpl.MCPLPlugin;
import io.github.sx2xoox.mcpl.core.FakeGame;
import io.github.sx2xoox.mcpl.core.Hub;
import io.github.sx2xoox.mcpl.core.game.GameManager;
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
}
