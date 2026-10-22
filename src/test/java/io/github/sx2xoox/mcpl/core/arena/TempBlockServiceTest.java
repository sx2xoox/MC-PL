package io.github.sx2xoox.mcpl.core.arena;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Path;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

class TempBlockServiceTest {

    private ServerMock server;
    private WorldMock world;
    private Plugin plugin;
    private TempBlockService service;
    private File file;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("world");
        plugin = MockBukkit.createMockPlugin();
        file = tempDir.resolve("tempblocks.yml").toFile();
        service = new TempBlockService(plugin, file);
        service.start();
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
        MockBukkit.unmock();
    }

    private Block airBlock(int x, int z) {
        Block block = world.getBlockAt(x, world.getMaxHeight() - 4, z);
        block.setType(Material.AIR);
        return block;
    }

    @Test
    void restoresAfterDuration() {
        Block block = airBlock(0, 0);
        assertTrue(service.place(block, Material.ICE.createBlockData(), 40));
        assertEquals(Material.ICE, block.getType());
        assertTrue(service.isTemporary(block));
        assertFalse(service.place(block, Material.STONE.createBlockData(), 40), "already temporary");

        server.getScheduler().performTicks(41);
        assertEquals(Material.AIR, block.getType());
        assertFalse(service.isTemporary(block));
        assertEquals(0, service.size());
    }

    @Test
    void refusesSolidBlocks() {
        Block block = world.getBlockAt(3, world.getMaxHeight() - 4, 3);
        block.setType(Material.STONE);
        assertFalse(service.place(block, Material.ICE.createBlockData(), 40));
        assertEquals(Material.STONE, block.getType());
    }

    @Test
    void restoreAllPutsEverythingBack() {
        Block a = airBlock(1, 1);
        Block b = airBlock(2, 2);
        service.place(a, Material.COBBLESTONE.createBlockData(), 0);
        service.place(b, Material.GLASS.createBlockData(), 10_000);
        assertEquals(2, service.size());

        assertEquals(2, service.restoreAll());
        assertEquals(Material.AIR, a.getType());
        assertEquals(Material.AIR, b.getType());
        assertEquals(0, service.size());
    }

    @Test
    void recoversAfterCrash() {
        Block block = airBlock(5, 5);
        service.place(block, Material.OBSIDIAN.createBlockData(), 0);
        service.flush();
        assertTrue(file.exists());

        // A crash: the service never restores, a new one starts from the file.
        TempBlockService afterCrash = new TempBlockService(plugin, file);
        afterCrash.recover();
        assertEquals(Material.AIR, block.getType());
        assertFalse(file.exists(), "nothing left to restore");
    }
}
