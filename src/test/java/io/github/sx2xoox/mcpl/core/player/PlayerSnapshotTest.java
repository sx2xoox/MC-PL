package io.github.sx2xoox.mcpl.core.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.mockbukkit.mockbukkit.inventory.WorkbenchInventoryMock;
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

    private static boolean isEmpty(ItemStack item) {
        return item == null || item.isEmpty();
    }

    @Test
    void cursorAndCraftingGridAreKeptInTheSnapshot() {
        Plugin plugin = MockBukkit.createMockPlugin();
        File dir = tempDir.resolve("snapshots").toFile();
        PlayerMock player = preparedPlayer();
        WorkbenchInventoryMock table = new WorkbenchInventoryMock(null);
        player.openInventory(table);
        ItemStack[] matrix = new ItemStack[9];
        matrix[0] = new ItemStack(Material.NETHERITE_INGOT);
        matrix[4] = new ItemStack(Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE);
        table.setMatrix(matrix);
        player.setItemOnCursor(new ItemStack(Material.EMERALD, 5));

        SnapshotStore store = new SnapshotStore(plugin, dir);
        store.init();
        PlayerSnapshot snapshot = store.capture(player, "test");
        assertEquals(3, snapshot.extras().length, "cursor + two grid slots");
        assertTrue(isEmpty(player.getItemOnCursor()), "taken before the menu is closed");
        for (ItemStack item : table.getMatrix()) {
            assertTrue(isEmpty(item));
        }

        scramble(player, world); // the match reset: clears whatever the menu would have handed back
        SnapshotStore afterRestart = new SnapshotStore(plugin, dir);
        afterRestart.init();
        assertTrue(afterRestart.restore(player));
        assertRestored(player);
        assertTrue(player.getInventory().contains(Material.NETHERITE_INGOT));
        assertTrue(player.getInventory().contains(Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE));
        assertTrue(player.getInventory().containsAtLeast(new ItemStack(Material.EMERALD), 5));
    }

    @Test
    void failedWriteKeepsNothingAndWipesNothing() throws IOException {
        Plugin plugin = MockBukkit.createMockPlugin();
        Path blocked = tempDir.resolve("not-a-folder");
        Files.writeString(blocked, "x");
        SnapshotStore store = new SnapshotStore(plugin, blocked.toFile());
        PlayerMock player = preparedPlayer();
        player.setItemOnCursor(new ItemStack(Material.EMERALD, 2));

        assertThrows(SnapshotWriteException.class, () -> store.capture(player, "test"));
        assertFalse(store.has(player.getUniqueId()), "no snapshot that exists in memory only");
        assertTrue(player.getInventory().containsAtLeast(new ItemStack(Material.EMERALD), 2), "cursor stack given back");
        assertEquals(GameMode.CREATIVE, player.getGameMode());
    }

    /** Records whether the snapshot file still existed when the player data was saved. */
    private static final class SavingPlayer extends PlayerMock {
        Path snapshotFile;
        int saves;
        boolean fileExistedAtSave;

        SavingPlayer(ServerMock server) {
            super(server, "Saver");
        }

        @Override
        public void saveData() {
            saves++;
            fileExistedAtSave = snapshotFile != null && Files.exists(snapshotFile);
        }
    }

    @Test
    void playerDataIsSavedBeforeTheSnapshotFileIsDeleted() {
        Plugin plugin = MockBukkit.createMockPlugin();
        File dir = tempDir.resolve("snapshots").toFile();
        SavingPlayer player = new SavingPlayer(server);
        server.addPlayer(player);
        player.snapshotFile = dir.toPath().resolve(player.getUniqueId() + ".yml");
        SnapshotStore store = new SnapshotStore(plugin, dir);
        store.init();
        store.capture(player, "test");

        assertTrue(store.restore(player));
        assertEquals(1, player.saves);
        assertTrue(player.fileExistedAtSave, "a crash before the save still finds the snapshot");
        assertFalse(Files.exists(player.snapshotFile));
    }

    @Test
    void cancelledTeleportIsRetriedWithoutReapplyingTheRest() {
        Plugin plugin = MockBukkit.createMockPlugin();
        SnapshotStore store = new SnapshotStore(plugin, tempDir.resolve("snapshots").toFile());
        store.init();
        PlayerMock player = preparedPlayer();
        store.capture(player, "test");
        scramble(player, world);

        Listener blocker = new Listener() {
            @EventHandler
            public void onTeleport(PlayerTeleportEvent event) {
                event.setCancelled(true); // e.g. a combat-tag plugin
            }
        };
        server.getPluginManager().registerEvents(blocker, plugin);
        assertTrue(store.restore(player));
        assertFalse(store.has(player.getUniqueId()), "items and stats were applied; never applied twice");
        assertEquals(-100, player.getLocation().getX(), 0.001);
        assertEquals(30, player.getLevel());

        HandlerList.unregisterAll(blocker);
        player.getInventory().setItem(1, new ItemStack(Material.STICK)); // picked up meanwhile
        server.getScheduler().performTicks(25);
        assertEquals(5.5, player.getLocation().getX(), 0.001, "the teleport home is retried");
        assertEquals(7.5, player.getLocation().getZ(), 0.001);
        assertEquals(Material.STICK, player.getInventory().getItem(1).getType(), "only the location is retried");
    }

    @Test
    void absorptionIsRestoredExactly() throws InvalidConfigurationException {
        PlayerMock player = preparedPlayer();
        AttributeInstance maxAbsorption = player.getAttribute(Attribute.MAX_ABSORPTION);
        if (maxAbsorption != null) {
            maxAbsorption.setBaseValue(16);
        }
        player.setAbsorptionAmount(3);
        YamlConfiguration out = new YamlConfiguration();
        PlayerSnapshot.capture(player, "test").save(out);
        YamlConfiguration in = new YamlConfiguration();
        in.loadFromString(out.saveToString());
        PlayerSnapshot loaded = PlayerSnapshot.load(in);

        scramble(player, world);
        assertEquals(0, player.getAbsorptionAmount(), 0.001, "reset clears absorption");
        loaded.restore(player, "mcpl");
        assertEquals(3, player.getAbsorptionAmount(), 0.001);
        assertRestored(player);
    }
}
