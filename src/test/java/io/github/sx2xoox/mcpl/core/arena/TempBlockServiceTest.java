package io.github.sx2xoox.mcpl.core.arena;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Path;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.event.block.BlockFadeEvent;
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

    /** Plants tall grass at (x, y, z) and (x, y + 1, z); returns the lower half. */
    private Block tallGrass(int x, int z) {
        Block lower = world.getBlockAt(x, world.getMaxHeight() - 8, z);
        Block upper = lower.getRelative(BlockFace.UP);
        Bisected bottom = (Bisected) Material.TALL_GRASS.createBlockData();
        bottom.setHalf(Bisected.Half.BOTTOM);
        Bisected top = (Bisected) Material.TALL_GRASS.createBlockData();
        top.setHalf(Bisected.Half.TOP);
        lower.setBlockData(bottom);
        upper.setBlockData(top);
        upper.getRelative(BlockFace.UP).setType(Material.AIR);
        return lower;
    }

    private static void assertHalf(Block block, Bisected.Half half) {
        assertEquals(Material.TALL_GRASS, block.getType());
        assertEquals(half, ((Bisected) block.getBlockData()).getHalf());
    }

    @Test
    void doublePlantIsTakenAndRestoredWhole() {
        Block lower = tallGrass(10, 10);
        Block upper = lower.getRelative(BlockFace.UP);
        Block above = upper.getRelative(BlockFace.UP);

        assertTrue(service.place(lower, Material.COBBLESTONE.createBlockData(), 0));
        assertEquals(Material.AIR, upper.getType(), "the upper half is taken along, never left floating");
        assertTrue(service.isTemporary(upper));
        assertTrue(service.place(upper, Material.COBBLESTONE.createBlockData(), 0), "the emptied half can be used");
        assertEquals(Material.COBBLESTONE, upper.getType());
        assertFalse(service.place(upper, Material.STONE.createBlockData(), 0), "but only once");
        assertTrue(service.place(above, Material.COBBLESTONE.createBlockData(), 0));

        service.flush();
        service.restoreAll();
        assertHalf(lower, Bisected.Half.BOTTOM);
        assertHalf(upper, Bisected.Half.TOP);
        assertEquals(Material.AIR, above.getType());
        assertEquals(0, service.size());
    }

    @Test
    void doublePlantWaitsForBothHalvesToExpire() {
        Block lower = tallGrass(12, 12);
        Block upper = lower.getRelative(BlockFace.UP);
        assertTrue(service.place(upper, Material.PACKED_ICE.createBlockData(), 20));
        assertEquals(Material.AIR, lower.getType());
        assertTrue(service.place(lower, Material.PACKED_ICE.createBlockData(), 60));

        server.getScheduler().performTicks(25);
        assertEquals(Material.PACKED_ICE, upper.getType(), "kept until the other half expires");
        assertEquals(Material.PACKED_ICE, lower.getType());

        server.getScheduler().performTicks(40);
        assertHalf(lower, Bisected.Half.BOTTOM);
        assertHalf(upper, Bisected.Half.TOP);
        assertEquals(0, service.size());
    }

    @Test
    void doublePlantRecoversWholeAfterCrash() {
        Block lower = tallGrass(14, 14);
        Block upper = lower.getRelative(BlockFace.UP);
        service.place(upper, Material.COBBLESTONE.createBlockData(), 0);
        service.flush();

        TempBlockService afterCrash = new TempBlockService(plugin, file);
        afterCrash.recover();
        assertHalf(lower, Bisected.Half.BOTTOM);
        assertHalf(upper, Bisected.Half.TOP);
    }

    @Test
    void grassUnderATempBlockDoesNotFade() {
        Block grass = world.getBlockAt(20, world.getMaxHeight() - 10, 20);
        grass.setType(Material.GRASS_BLOCK);
        Block cover = grass.getRelative(BlockFace.UP);
        cover.setType(Material.AIR);
        assertTrue(service.place(cover, Material.COBBLESTONE.createBlockData(), 0));

        BlockFadeEvent fade = new BlockFadeEvent(grass, grass.getState());
        server.getPluginManager().callEvent(fade);
        assertTrue(fade.isCancelled(), "grass covered by a temporary block keeps being grass");

        service.restoreAll();
        BlockFadeEvent later = new BlockFadeEvent(grass, grass.getState());
        server.getPluginManager().callEvent(later);
        assertFalse(later.isCancelled());
    }
}
