package io.github.sx2xoox.mcpl.core.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

class PlayerSnapshotTest {

    private ServerMock server;
    private WorldMock world;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("world");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private PlayerMock preparedPlayer() {
        PlayerMock player = server.addPlayer("Snap");
        player.teleport(new Location(world, 5.5, 80, 7.5, 90f, 10f));
        player.setGameMode(GameMode.CREATIVE);
        player.getInventory().setItem(0, new ItemStack(Material.DIAMOND_SWORD));
        player.getInventory().setItem(20, new ItemStack(Material.GOLDEN_APPLE, 4));
        player.getInventory().setHelmet(new ItemStack(Material.IRON_HELMET));
        player.getInventory().setItemInOffHand(new ItemStack(Material.SHIELD));
        player.setHealth(11);
        player.setFoodLevel(9);
        player.setLevel(30);
        return player;
    }

    private static void assertRestored(PlayerMock player) {
        assertEquals(GameMode.CREATIVE, player.getGameMode());
        assertEquals(Material.DIAMOND_SWORD, player.getInventory().getItem(0).getType());
        ItemStack apples = player.getInventory().getItem(20);
        assertNotNull(apples);
        assertEquals(4, apples.getAmount());
        assertEquals(Material.IRON_HELMET, player.getInventory().getHelmet().getType());
        assertEquals(Material.SHIELD, player.getInventory().getItemInOffHand().getType());
        assertEquals(11, player.getHealth(), 0.001);
        assertEquals(9, player.getFoodLevel());
        assertEquals(30, player.getLevel());
        assertEquals(5.5, player.getLocation().getX(), 0.001);
        assertEquals(80, player.getLocation().getY(), 0.001);
        assertEquals(7.5, player.getLocation().getZ(), 0.001);
    }

    private static void scramble(PlayerMock player, WorldMock world) {
        PlayerReset.reset(player, "mcpl");
        player.teleport(new Location(world, -100, 70, 100));
        assertTrue(player.getInventory().isEmpty());
        assertEquals(GameMode.ADVENTURE, player.getGameMode());
        assertEquals(20, player.getHealth(), 0.001);
        assertEquals(0, player.getLevel());
    }

    @Test
    void restoresInMemory() {
        PlayerMock player = preparedPlayer();
        PlayerSnapshot snapshot = PlayerSnapshot.capture(player, "test");
        scramble(player, world);
        snapshot.restore(player, "mcpl");
        assertRestored(player);
    }

    @Test
    void survivesSerialization() throws InvalidConfigurationException {
        PlayerMock player = preparedPlayer();
        YamlConfiguration out = new YamlConfiguration();
        PlayerSnapshot.capture(player, "test").save(out);

        YamlConfiguration in = new YamlConfiguration();
        in.loadFromString(out.saveToString());
        PlayerSnapshot loaded = PlayerSnapshot.load(in);

        scramble(player, world);
        loaded.restore(player, "mcpl");
        assertRestored(player);
        assertEquals("test", loaded.gameId());
    }

    @Test
    void storePersistsUntilRestored() {
        Plugin plugin = MockBukkit.createMockPlugin();
        File dir = tempDir.resolve("snapshots").toFile();
        PlayerMock player = preparedPlayer();

        SnapshotStore store = new SnapshotStore(plugin, dir);
        store.init();
        store.capture(player, "test");
        assertTrue(Files.exists(dir.toPath().resolve(player.getUniqueId() + ".yml")));
        scramble(player, world);

        // A second capture must never overwrite the original state.
        store.capture(player, "other");

        // Simulate a restart: a fresh store only knows what is on disk.
        SnapshotStore afterRestart = new SnapshotStore(plugin, dir);
        afterRestart.init();
        assertTrue(afterRestart.has(player.getUniqueId()));
        assertTrue(afterRestart.restore(player));
        assertRestored(player);
        assertFalse(afterRestart.has(player.getUniqueId()));
        assertFalse(Files.exists(dir.toPath().resolve(player.getUniqueId() + ".yml")));
    }
}
