package io.github.sx2xoox.mcpl.core.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import io.github.sx2xoox.mcpl.MCPLPlugin;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

/** A message file written by an older version follows the new bundled text, except the lines the owner edited. */
class MessagesUpgradeTest {

    private static final String FILE = "siege.yml";

    private MCPLPlugin plugin;
    private File file;
    private File snapshot;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        plugin = MockBukkit.load(MCPLPlugin.class);
        file = new File(plugin.getDataFolder(), FILE);
        snapshot = new File(new File(plugin.getDataFolder(), ".bundled"), FILE);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private String bundled(String key) {
        Messages fresh = new Messages(plugin, FILE);
        fresh.load();
        return fresh.raw(key);
    }

    @Test
    void anOldFileWithoutBookkeepingIsBackedUpAndReplaced() throws Exception {
        String current = bundled("round.started");
        Files.writeString(file.toPath(), "round:\n  started: \"old text\"\n", StandardCharsets.UTF_8);
        Files.deleteIfExists(snapshot.toPath());

        Messages messages = new Messages(plugin, FILE);
        messages.load();

        assertEquals(current, messages.raw("round.started"));
        assertTrue(new File(plugin.getDataFolder(), FILE + ".old").exists());
        assertTrue(snapshot.exists());
    }

    @Test
    void untouchedLinesFollowTheNewTextAndEditedOnesStay() throws Exception {
        String current = bundled("round.started");
        String previous = "round:\n  started: \"old text\"\n  won: \"old won\"\n";
        Files.writeString(snapshot.toPath(), previous, StandardCharsets.UTF_8);
        Files.writeString(file.toPath(), "round:\n  started: \"old text\"\n  won: \"my own won\"\n",
                StandardCharsets.UTF_8);

        Messages messages = new Messages(plugin, FILE);
        messages.load();

        assertEquals(current, messages.raw("round.started"), "an untouched line follows the new text");
        assertEquals("my own won", messages.raw("round.won"), "an edited line stays");
        assertEquals("my own won", YamlConfiguration.loadConfiguration(file).getString("round.won"));

        Messages again = new Messages(plugin, FILE);
        again.load();
        assertEquals(current, again.raw("round.started"), "still new after the next load");
    }
}
