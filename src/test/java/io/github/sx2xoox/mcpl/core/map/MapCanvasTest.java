package io.github.sx2xoox.mcpl.core.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.Axis;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.Stairs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

class MapCanvasTest {

    private ServerMock server;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void shapesFillTheRightBlocks() {
        MapCanvas canvas = new MapCanvas(20, 0, 100, 1);
        BlockSpec stone = BlockSpec.of(Material.STONE);
        canvas.fill(-1, 10, -1, 1, 10, 1, stone);
        assertEquals(9, canvas.blockCount());
        canvas.cylinder(10, 10, 20, 22, 2, stone, true);
        assertNull(canvas.get(10, 21, 10), "a hollow cylinder is empty inside");
        assertNotNull(canvas.get(12, 21, 10), "and has a wall");
        canvas.clear(0, 10, 0);
        assertFalse(canvas.isSolid(0, 10, 0), "cleared blocks are air");
        assertEquals(10, canvas.surfaceY(1, 1));
        canvas.line(-5, 30, -5, 5, 30, 5, stone);
        assertTrue(canvas.isSolid(0, 30, 0));
    }

    @Test
    void writesOutsideTheMapOrTheRegionAreRefused() {
        MapCanvas canvas = new MapCanvas(10, 0, 100, 1);
        assertThrows(IllegalArgumentException.class, () -> canvas.set(11, 5, 0, Material.STONE));
        MapCanvas east = canvas.restrictTo((x, z) -> x > 0, "east");
        east.set(3, 5, 0, Material.STONE);
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> east.set(-3, 5, 0, Material.STONE));
        assertTrue(refused.getMessage().contains("east"));
        assertNotNull(east.get(3, 5, 0));
        assertTrue(east.canWrite(4, 4));
        assertFalse(east.canWrite(-4, 4));
    }

    @Test
    void finishConnectsFencesWallsAndPanes() {
        MapCanvas canvas = new MapCanvas(10, 0, 100, 1);
        canvas.fill(0, 5, 0, 2, 5, 0, BlockSpec.of(Material.OAK_FENCE));
        canvas.set(3, 5, 0, Material.STONE);
        canvas.fill(0, 5, 3, 0, 5, 5, BlockSpec.of(Material.COBBLESTONE_WALL));
        canvas.finish();
        BlockSpec middle = canvas.get(1, 5, 0);
        assertEquals(BlockSpec.EAST | BlockSpec.WEST, middle.connections());
        assertEquals(BlockSpec.EAST | BlockSpec.WEST, canvas.get(2, 5, 0).connections(), "fences join full blocks");
        assertEquals(BlockSpec.EAST, canvas.get(0, 5, 0).connections());
        BlockSpec wallMiddle = canvas.get(0, 5, 4);
        assertEquals(BlockSpec.NORTH | BlockSpec.SOUTH, wallMiddle.connections());
        assertFalse(wallMiddle.up(), "a straight wall run shows no post");
        assertTrue(canvas.get(0, 5, 3).up(), "the end of a wall has a post");
    }

    @Test
    void specsBecomeRealBlockData() {
        BlockData stairs = MapBlocks.toData(Blocks.stairsTop(Material.STONE_BRICK_STAIRS, BlockFace.EAST));
        assertEquals(BlockFace.EAST, ((Stairs) stairs).getFacing());
        BlockData slab = MapBlocks.toData(Blocks.slabTop(Material.OAK_SLAB));
        assertEquals(Slab.Type.TOP, ((Slab) slab).getType());
        BlockData log = MapBlocks.toData(Blocks.log(Material.OAK_LOG, Axis.X));
        assertEquals(Axis.X, ((Orientable) log).getAxis());
        // Unsupported properties are skipped instead of failing.
        BlockData stone = MapBlocks.toData(BlockSpec.of(Material.STONE).facing(BlockFace.UP).axis(Axis.Z));
        assertEquals(Material.STONE, stone.getMaterial());
        // Walls convert even where the server does not implement wall sides (MockBukkit).
        assertEquals(Material.MOSSY_COBBLESTONE_WALL,
                MapBlocks.toData(BlockSpec.of(Material.MOSSY_COBBLESTONE_WALL)).getMaterial());
    }

    @Test
    void theBuilderPlacesTheCanvasInAWorld() {
        World world = server.addSimpleWorld("map");
        MapCanvas canvas = new MapCanvas(10, 0, 100, 1);
        canvas.fill(-2, 60, -2, 2, 60, 2, BlockSpec.of(Material.GRASS_BLOCK));
        canvas.set(0, 61, 0, Blocks.stairs(Material.OAK_STAIRS, BlockFace.NORTH));
        canvas.finish();
        MapBuilder.placeAll(world, canvas);
        assertEquals(Material.GRASS_BLOCK, world.getBlockAt(2, 60, -2).getType());
        assertEquals(BlockFace.NORTH, ((Stairs) world.getBlockAt(0, 61, 0).getBlockData()).getFacing());
    }

    @Test
    void shapeRulesKnowCommonBlocks() {
        assertTrue(MapBlocks.isFullCube(BlockSpec.of(Material.STONE_BRICKS)));
        assertTrue(MapBlocks.isFullCube(BlockSpec.of(Material.SNOW_BLOCK)));
        assertTrue(MapBlocks.isFullCube(BlockSpec.of(Material.SEA_LANTERN)));
        assertFalse(MapBlocks.isFullCube(BlockSpec.of(Material.OAK_STAIRS)));
        assertFalse(MapBlocks.isFullCube(Blocks.slab(Material.OAK_SLAB)));
        assertTrue(MapBlocks.isFullCube(BlockSpec.of(Material.OAK_SLAB).slab(Slab.Type.DOUBLE)));
        assertTrue(MapBlocks.isPassable(BlockSpec.of(Material.SHORT_GRASS)));
        assertTrue(MapBlocks.isPassable(BlockSpec.of(Material.POPPY)));
        assertFalse(MapBlocks.isPassable(BlockSpec.of(Material.OAK_FENCE)));
        assertFalse(MapBlocks.isPassable(BlockSpec.of(Material.RED_MUSHROOM_BLOCK)));
        assertTrue(MapBlocks.isStandable(BlockSpec.of(Material.DIRT_PATH)));
        assertEquals(MapBlocks.Family.WALL, MapBlocks.family(Material.MOSSY_STONE_BRICK_WALL));
        assertEquals(MapBlocks.Family.NONE, MapBlocks.family(Material.OAK_WALL_SIGN));
        assertEquals(MapBlocks.Family.PANE, MapBlocks.family(Material.IRON_BARS));
    }
}
