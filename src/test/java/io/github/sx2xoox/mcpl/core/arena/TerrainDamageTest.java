package io.github.sx2xoox.mcpl.core.arena;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.logging.Logger;

import io.github.sx2xoox.mcpl.core.config.BlockDamageScope;
import io.github.sx2xoox.mcpl.core.map.MapService;
import io.github.sx2xoox.mcpl.game.superpower.support.world.FlatWorld;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.Item;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/**
 * Terrain damage rules on a flat world (stone up to y=63, so the original surface of every column is y=63): craters
 * are bowls at most two deep that never trap anyone, structures break, and nothing breaks that must not.
 */
class TerrainDamageTest {

    private static final int TOP = FlatWorld.FLOOR_Y;
    private static final Predicate<Location> EVERYWHERE = at -> true;

    private ServerMock server;
    private FlatWorld world;
    private TempBlockService blocks;
    private File file;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = new FlatWorld("world");
        server.addWorld(world);
        Plugin plugin = MockBukkit.createMockPlugin();
        file = tempDir.resolve("tempblocks.yml").toFile();
        blocks = new TempBlockService(plugin, file);
        blocks.start();
    }

    @AfterEach
    void tearDown() {
        blocks.shutdown();
        MockBukkit.unmock();
    }

    private TerrainDamage terrain(BlockDamageScope scope, int limit) {
        return new TerrainDamage(blocks, scope, limit, w -> w.getName().startsWith(MapService.WORLD_PREFIX),
                Logger.getLogger("TerrainDamageTest"));
    }

    private TerrainDamage everywhere() {
        return terrain(BlockDamageScope.ALL_WORLDS, 20_000);
    }

    private static Location at(World world, double x, double y, double z) {
        return new Location(world, x, y, z);
    }

    private Material type(int x, int y, int z) {
        return world.getBlockAt(x, y, z).getType();
    }

    /** How many of the top two blocks of the column are gone. */
    private int depth(int x, int z) {
        int depth = 0;
        for (int y = TOP; y > TOP - 3; y--) {
            if (!world.getBlockAt(x, y, z).getType().isAir()) {
                break;
            }
            depth++;
        }
        return depth;
    }

    /** Grass on dirt in the top layers of a square around the origin (natural ground like the battle maps). */
    private void grassland(int half) {
        for (int x = -half; x <= half; x++) {
            for (int z = -half; z <= half; z++) {
                world.setBlock(x, TOP, z, Material.GRASS_BLOCK);
                for (int y = TOP - 3; y < TOP; y++) {
                    world.setBlock(x, y, z, Material.DIRT);
                }
            }
        }
    }

    // ------------------------------------------------------------------ scope and limit

    @Test
    void scopeDecidesTheWorlds() {
        FlatWorld map = new FlatWorld(MapService.WORLD_PREFIX + "test");
        server.addWorld(map);
        assertFalse(terrain(BlockDamageScope.OFF, 100).appliesTo(world));
        assertFalse(terrain(BlockDamageScope.OFF, 100).appliesTo(map));
        assertFalse(terrain(BlockDamageScope.MAP_WORLDS, 100).appliesTo(world), "ordinary worlds keep their blocks");
        assertTrue(terrain(BlockDamageScope.MAP_WORLDS, 100).appliesTo(map));
        assertTrue(terrain(BlockDamageScope.ALL_WORLDS, 100).appliesTo(world));
        assertFalse(terrain(BlockDamageScope.ALL_WORLDS, 0).appliesTo(world), "a limit of 0 breaks nothing");

        TerrainDamage maps = terrain(BlockDamageScope.MAP_WORLDS, 100);
        assertTrue(maps.blast(at(world, 0.5, TOP + 1.5, 0.5), 3, EVERYWHERE).isEmpty());
        assertEquals(Material.STONE, type(0, TOP, 0));
        assertFalse(maps.blast(at(map, 0.5, TOP + 1.5, 0.5), 3, EVERYWHERE).isEmpty(), "a map world is scarred");
        assertTrue(map.getBlockAt(0, TOP, 0).getType().isAir());
    }

    @Test
    void scopeNamesParse() {
        assertEquals(BlockDamageScope.OFF, BlockDamageScope.parse("off"));
        assertEquals(BlockDamageScope.MAP_WORLDS, BlockDamageScope.parse("map-worlds"));
        assertEquals(BlockDamageScope.ALL_WORLDS, BlockDamageScope.parse(" ALL_WORLDS "));
        assertNull(BlockDamageScope.parse("sometimes"));
    }

    @Test
    void theLimitCapsTheBlocksOfAMatch() {
        TerrainDamage terrain = terrain(BlockDamageScope.ALL_WORLDS, 5);
        int first = terrain.blast(at(world, 0.5, TOP + 1.5, 0.5), 3, EVERYWHERE).size();
        assertTrue(first > 0 && first <= 5, "at most the limit: " + first);
        int second = terrain.blast(at(world, 10.5, TOP + 1.5, 10.5), 3, EVERYWHERE).size();
        assertTrue(first + second <= 5, "the limit holds for the whole match");
        assertEquals(first + second, terrain.brokenCount());
        assertEquals(first + second, blocks.removedCount());
    }

    // ------------------------------------------------------------------ craters

    @Test
    void cratersAreBowlsAtMostTwoDeepThatNobodyIsTrappedIn() {
        grassland(8);
        List<TerrainDamage.Broken> broken = everywhere().blast(at(world, 0.5, TOP + 1.5, 0.5), 3, EVERYWHERE);
        assertFalse(broken.isEmpty());
        assertEquals(2, depth(0, 0), "two deep in the middle");
        int deepest = 0;
        for (int x = -6; x <= 6; x++) {
            for (int z = -6; z <= 6; z++) {
                deepest = Math.max(deepest, depth(x, z));
                assertEquals(Material.DIRT, type(x, TOP - 2, z), "never deeper than two at " + x + "," + z);
            }
        }
        assertEquals(2, deepest);
        assertEscapable();
        for (TerrainDamage.Broken block : broken) {
            assertTrue(block.data().getMaterial() == Material.GRASS_BLOCK
                    || block.data().getMaterial() == Material.DIRT, "only ground broke: " + block.data());
        }
        assertNoItems();
    }

    @Test
    void repeatedBlastsNeverDigDeeperThanTwoBelowTheOriginalSurface() {
        grassland(10);
        TerrainDamage terrain = everywhere();
        for (int i = 0; i < 6; i++) {
            terrain.blast(at(world, 0.5 + (i % 3) - 1, TOP + 0.5 - (i % 2), 0.5), 4, EVERYWHERE);
        }
        assertTrue(blocks.removedCount() > 0);
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                assertTrue(depth(x, z) <= 2, "at most two deep at " + x + "," + z);
                assertEquals(Material.DIRT, type(x, TOP - 2, z), "the third block stays at " + x + "," + z);
            }
        }
        assertEscapable();
    }

    @Test
    void cratersComeBackExactly() {
        grassland(6);
        BlockData[][][] before = snapshot();
        everywhere().blast(at(world, 0.5, TOP + 1.5, 0.5), 3.5, EVERYWHERE);
        assertTrue(blocks.removedCount() > 0);
        blocks.restoreRemovals();
        assertEquals(0, blocks.removedCount());
        assertSame(before, snapshot());
    }

    @Test
    void aThinFloorIsNeverDugThrough() {
        // Three blocks of floor over a cavern: only the top one may go, so two stay under the crater.
        for (int x = -6; x <= 6; x++) {
            for (int z = -6; z <= 6; z++) {
                for (int y = TOP - 10; y <= TOP - 3; y++) {
                    world.setBlock(x, y, z, Material.AIR);
                }
            }
        }
        everywhere().blast(at(world, 0.5, TOP + 1.5, 0.5), 3, EVERYWHERE);
        assertTrue(type(0, TOP, 0).isAir(), "the top block went");
        for (int x = -6; x <= 6; x++) {
            for (int z = -6; z <= 6; z++) {
                assertEquals(Material.STONE, type(x, TOP - 1, z), "two blocks stay over the cavern");
                assertEquals(Material.STONE, type(x, TOP - 2, z));
            }
        }
    }

    @Test
    void nothingOverTheVoidBreaks() {
        // A walkway with nothing below it in its column.
        for (int y = 1; y <= TOP; y++) {
            world.setBlock(20, y, 20, Material.AIR);
        }
        world.setBlock(20, TOP + 10, 20, Material.OAK_PLANKS);
        everywhere().blast(at(world, 20.5, TOP + 10.5, 20.5), 1.2, EVERYWHERE);
        assertEquals(Material.OAK_PLANKS, type(20, TOP + 10, 20));
    }

    // ------------------------------------------------------------------ structures

    @Test
    void structuresBreakFreelyWithinTheBlast() {
        world.wallAlongX(-4, 4, 3, 6, Material.STONE_BRICKS);
        Location center = at(world, 0.5, TOP + 3.5, 3.5);
        everywhere().blast(center, 2.5, EVERYWHERE);
        for (int x = -4; x <= 4; x++) {
            for (int y = TOP + 1; y <= TOP + 6; y++) {
                double distance = center.distance(at(world, x + 0.5, y + 0.5, 3.5));
                Material expected = distance <= 2.5 ? Material.AIR : Material.STONE_BRICKS;
                assertEquals(expected, type(x, y, 3), "wall at " + x + "," + y);
            }
        }
        assertEquals(Material.STONE, type(0, TOP, 3), "the ground under the wall is out of reach");
    }

    @Test
    void outsideTheArenaNothingBreaks() {
        world.wallAlongX(-4, 4, 3, 3, Material.STONE_BRICKS);
        everywhere().blast(at(world, 0.5, TOP + 2.5, 3.5), 3, at -> at.getX() < 0);
        for (int x = 0; x <= 4; x++) {
            for (int y = TOP + 1; y <= TOP + 3; y++) {
                assertEquals(Material.STONE_BRICKS, type(x, y, 3), "outside: " + x + "," + y);
            }
        }
        assertTrue(type(-1, TOP + 2, 3).isAir(), "inside it breaks");
    }

    // ------------------------------------------------------------------ never broken

    @Test
    void nothingNextToFluidsBreaks() {
        world.setBlock(1, TOP, 0, Material.WATER);
        world.setBlock(-2, TOP + 1, 0, Material.STONE_BRICKS);
        world.setBlock(-3, TOP + 1, 0, Material.LAVA);
        everywhere().blast(at(world, 0.5, TOP + 1.5, 0.5), 3, EVERYWHERE);
        assertEquals(Material.WATER, type(1, TOP, 0));
        for (BlockFace face : TerrainRules.FACES) {
            Block next = world.getBlockAt(1, TOP, 0).getRelative(face);
            if (next.getY() <= TOP) {
                assertEquals(Material.STONE, next.getType(), "next to the water: " + face);
            }
        }
        assertEquals(Material.STONE_BRICKS, type(-2, TOP + 1, 0), "next to the lava");
        assertEquals(Material.LAVA, type(-3, TOP + 1, 0));
        assertTrue(type(0, TOP, 1).isAir() || type(-1, TOP, 1).isAir(), "the rest of the crater is there");
    }

    @Test
    void nothingHoldingUpAFallingBlockBreaks() {
        for (int y = TOP + 1; y <= TOP + 3; y++) {
            world.setBlock(0, y, 4, Material.STONE_BRICKS);
        }
        world.setBlock(0, TOP + 4, 4, Material.SAND);
        everywhere().blast(at(world, 0.5, TOP + 2.5, 4.5), 1.6, EVERYWHERE);
        assertEquals(Material.SAND, type(0, TOP + 4, 4));
        assertEquals(Material.STONE_BRICKS, type(0, TOP + 3, 4), "the block under the sand stays");
    }

    @Test
    void blockEntitiesTemporaryBlocksAndWhatHoldsThemStay() {
        world.getBlockAt(1, TOP + 1, 0).setType(Material.CHEST);
        Block glass = world.getBlockAt(-1, TOP + 1, 0);
        assertTrue(blocks.place(glass, Material.GLASS.createBlockData(), 0));
        world.setBlock(0, TOP + 1, 3, Material.STONE_BRICKS);
        Block sign = world.getBlockAt(0, TOP + 1, 4);
        sign.setType(Material.OAK_WALL_SIGN);
        Directional facing = (Directional) Material.OAK_WALL_SIGN.createBlockData();
        facing.setFacing(BlockFace.SOUTH);
        sign.setBlockData(facing);

        TerrainDamage terrain = everywhere();
        terrain.blast(at(world, 0.5, TOP + 1.5, 0.5), 2.5, EVERYWHERE);
        terrain.blast(at(world, 0.5, TOP + 1.5, 3.5), 0.6, EVERYWHERE);
        assertEquals(Material.CHEST, type(1, TOP + 1, 0));
        assertEquals(Material.STONE, type(1, TOP, 0), "the ground under the chest");
        assertEquals(Material.GLASS, type(-1, TOP + 1, 0));
        assertEquals(Material.STONE, type(-1, TOP, 0), "the ground under the temporary block");
        assertEquals(Material.STONE_BRICKS, type(0, TOP + 1, 3), "the wall holding the sign");
        assertEquals(Material.OAK_WALL_SIGN, type(0, TOP + 1, 4));
        assertTrue(type(0, TOP, 0).isAir(), "the rest broke");
    }

    // ------------------------------------------------------------------ attached blocks

    @Test
    void attachedBlocksGoAndComeBackWithTheirBlock() {
        world.setBlock(0, TOP + 1, 3, Material.STONE_BRICKS);
        Block torch = world.getBlockAt(0, TOP + 1, 4);
        Directional wallTorch = (Directional) Material.WALL_TORCH.createBlockData();
        wallTorch.setFacing(BlockFace.SOUTH);
        torch.setBlockData(wallTorch);
        grassland(12);
        world.setBlock(6, TOP + 1, 0, Material.POPPY);
        Block lower = world.getBlockAt(-6, TOP + 1, 0);
        Bisected bottom = (Bisected) Material.TALL_GRASS.createBlockData();
        bottom.setHalf(Bisected.Half.BOTTOM);
        Bisected top = (Bisected) Material.TALL_GRASS.createBlockData();
        top.setHalf(Bisected.Half.TOP);
        lower.setBlockData(bottom);
        lower.getRelative(BlockFace.UP).setBlockData(top);
        BlockData[][][] before = snapshot();

        TerrainDamage terrain = everywhere();
        terrain.blast(at(world, 0.5, TOP + 1.5, 3.5), 0.6, EVERYWHERE);
        assertTrue(type(0, TOP + 1, 3).isAir(), "the wall block broke");
        assertTrue(type(0, TOP + 1, 4).isAir(), "and the torch on it went with it");
        terrain.blast(at(world, 6.5, TOP + 0.5, 0.5), 0.6, EVERYWHERE);
        assertTrue(type(6, TOP, 0).isAir() && type(6, TOP + 1, 0).isAir(), "the flower went with its ground");
        terrain.blast(at(world, -5.5, TOP + 0.5, 0.5), 0.6, EVERYWHERE);
        assertTrue(type(-6, TOP + 1, 0).isAir() && type(-6, TOP + 2, 0).isAir(), "both halves of the tall grass");
        assertNoItems();

        blocks.restoreRemovals();
        assertSame(before, snapshot());
        BlockData torchBack = world.getBlockAt(0, TOP + 1, 4).getBlockData();
        assertEquals(BlockFace.SOUTH, ((Directional) torchBack).getFacing());
        assertEquals(Bisected.Half.TOP, ((Bisected) world.getBlockAt(-6, TOP + 2, 0).getBlockData()).getHalf());
    }

    // ------------------------------------------------------------------ helpers

    private void assertNoItems() {
        for (org.bukkit.entity.Entity entity : world.getEntities()) {
            assertFalse(entity instanceof Item, "an item dropped: " + entity);
        }
    }

    private static final int SPAN = 14;

    /** The block data around the origin, from two below the surface to six above. */
    private BlockData[][][] snapshot() {
        BlockData[][][] out = new BlockData[2 * SPAN + 1][9][2 * SPAN + 1];
        for (int x = -SPAN; x <= SPAN; x++) {
            for (int y = 0; y < 9; y++) {
                for (int z = -SPAN; z <= SPAN; z++) {
                    out[x + SPAN][y][z + SPAN] = world.getBlockAt(x, TOP - 2 + y, z).getBlockData().clone();
                }
            }
        }
        return out;
    }

    private static void assertSame(BlockData[][][] expected, BlockData[][][] actual) {
        for (int x = 0; x < expected.length; x++) {
            for (int y = 0; y < expected[x].length; y++) {
                for (int z = 0; z < expected[x][y].length; z++) {
                    if (!expected[x][y][z].matches(actual[x][y][z])) {
                        fail("block at " + (x - SPAN) + "," + (TOP - 2 + y) + "," + (z - SPAN) + " is "
                                + actual[x][y][z].getAsString() + ", was " + expected[x][y][z].getAsString());
                    }
                }
            }
        }
    }

    /**
     * Every column dug below the surface leads, one block up at a time, to a column that was not dug: nobody is
     * trapped in a crater.
     */
    private void assertEscapable() {
        Map<Long, Integer> floor = new HashMap<>();
        Set<Long> dug = new HashSet<>();
        for (int x = -SPAN; x <= SPAN; x++) {
            for (int z = -SPAN; z <= SPAN; z++) {
                int depth = depth(x, z);
                floor.put(key(x, z), TOP - depth);
                if (depth > 0) {
                    dug.add(key(x, z));
                }
            }
        }
        for (long start : dug) {
            Deque<Long> queue = new ArrayDeque<>(List.of(start));
            Set<Long> seen = new HashSet<>(List.of(start));
            boolean out = false;
            while (!queue.isEmpty() && !out) {
                long column = queue.poll();
                int x = (int) (column >> 32);
                int z = (int) column;
                for (int[] step : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    long next = key(x + step[0], z + step[1]);
                    Integer height = floor.get(next);
                    if (height == null || height > floor.get(column) + 1 || !seen.add(next)) {
                        continue;
                    }
                    if (!dug.contains(next)) {
                        out = true;
                        break;
                    }
                    queue.add(next);
                }
            }
            assertTrue(out, "a pit nobody can climb out of at " + (int) (start >> 32) + "," + (int) start);
        }
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }
}
