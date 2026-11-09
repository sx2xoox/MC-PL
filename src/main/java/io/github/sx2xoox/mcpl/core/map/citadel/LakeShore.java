package io.github.sx2xoox.mcpl.core.map.citadel;

import java.util.Random;

import io.github.sx2xoox.mcpl.core.map.BlockSpec;
import io.github.sx2xoox.mcpl.core.map.Blocks;
import io.github.sx2xoox.mcpl.core.map.MapCanvas;
import org.bukkit.Axis;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Slab;

/**
 * The smaller places around the lake: the shrine on the islet with its stepping stones, the boathouse and the
 * fishers' cottage on the west shore, the wooden lookout in the north-west corner, the ruined gazebo on the east
 * knoll and the columns of a drowned colonnade standing in the open water.
 */
final class LakeShore {

    private static final int GROUND = LakeTerrain.GROUND_Y;

    // The islet in the middle of the lake and the stones to the willow island.
    static final int ISLET_X = -55;
    static final int ISLET_Z = 44;
    // The wooden lookout in the north-west corner.
    static final int LOOKOUT_X = -88;
    static final int LOOKOUT_Z = 12;
    static final int LOOKOUT_DECK = GROUND + 6;
    // The boathouse on the north shore of the west basin.
    static final int BOATHOUSE_X = -78;
    static final int BOATHOUSE_Z = 16;
    // The fishers' cottage on the west shore.
    static final int COTTAGE_X1 = -78;
    static final int COTTAGE_Z1 = 44;
    static final int COTTAGE_X2 = -72;
    static final int COTTAGE_Z2 = 49;
    // The ruined gazebo on the east knoll.
    static final int GAZEBO_X = -37;
    static final int GAZEBO_Z = 49;

    private final MapCanvas canvas;
    private final LakeTerrain terrain;
    private final Random random;

    LakeShore(MapCanvas canvas, LakeTerrain terrain, Random random) {
        this.canvas = canvas;
        this.terrain = terrain;
        this.random = random;
    }

    /** Levels the plots of the shore buildings (before the terrain is written). */
    static void plan(LakeTerrain terrain) {
        terrain.flattenDisk(ISLET_X, ISLET_Z, 2.2, GROUND);
        terrain.flatten(LOOKOUT_X - 3, LOOKOUT_Z - 3, LOOKOUT_X + 3, LOOKOUT_Z + 3, GROUND);
        terrain.reserve(LOOKOUT_X - 4, LOOKOUT_Z - 4, LOOKOUT_X + 4, LOOKOUT_Z + 5);
        terrain.flatten(BOATHOUSE_X - 3, BOATHOUSE_Z - 3, BOATHOUSE_X + 3, BOATHOUSE_Z + 2, GROUND);
        terrain.reserve(BOATHOUSE_X - 4, BOATHOUSE_Z - 4, BOATHOUSE_X + 4, BOATHOUSE_Z + 3);
        terrain.flatten(COTTAGE_X1 - 1, COTTAGE_Z1 - 1, COTTAGE_X2 + 1, COTTAGE_Z2 + 1, GROUND);
        terrain.reserve(COTTAGE_X1 - 2, COTTAGE_Z1 - 2, COTTAGE_X2 + 2, COTTAGE_Z2 + 2);
        // The gazebo stands on a level top cut into the knoll, one step above its lowest rim.
        int rim = Integer.MAX_VALUE;
        for (int a = 0; a < 360; a += 15) {
            int[] p = CitadelLayout.polar(4.5, a);
            rim = Math.min(rim, terrain.top(GAZEBO_X + p[0], GAZEBO_Z + p[1]));
        }
        terrain.flattenDisk(GAZEBO_X, GAZEBO_Z, 3.5, Math.max(GROUND, rim + 1));
        terrain.reserveDisk(GAZEBO_X, GAZEBO_Z, 4.5);
    }

    void build() {
        isletShrine();
        steppingStones(ISLET_X - 1, ISLET_Z + 2, -58, 56);
        boathouse();
        cottage();
        lookout();
        gazebo();
        drownedColonnade();
    }

    // ------------------------------------------------------------------ drowned colonnade

    /**
     * Columns of an older, drowned building standing in the open water: cover for swimmers and a landmark. Two of
     * them still carry a piece of their lintel.
     */
    private void drownedColonnade() {
        int[][] columns = {{-59, 20, 4}, {-55, 20, 4}, {-53, 24, 1}, {-81, 30, 3}, {-81, 35, 1}, {-63, 53, 3},
                {-53, 57, 2}};
        for (int[] c : columns) {
            column(c[0], c[1], c[2]);
        }
        // The lintel between the first two columns.
        int y = LakeTerrain.WATER_Y + 4;
        if (canvas.isSolid(-59, y, 20) && canvas.isSolid(-55, y, 20)) {
            canvas.set(-58, y, 20, Blocks.stairsTop(Material.STONE_BRICK_STAIRS, BlockFace.WEST));
            canvas.set(-57, y, 20, Blocks.slabTop(Material.STONE_BRICK_SLAB));
            canvas.set(-56, y, 20, Blocks.stairsTop(Material.MOSSY_STONE_BRICK_STAIRS, BlockFace.EAST));
            canvas.set(-57, y + 1, 20, LakeKit.brickSlab(random));
        }
    }

    private void column(int x, int z, int height) {
        if (!terrain.isWater(x, z) || !terrain.isFree(x, z)) {
            return;
        }
        int bed = terrain.top(x, z);
        int top = LakeTerrain.WATER_Y + height;
        for (int y = bed + 1; y <= top; y++) {
            BlockSpec spec;
            if (y == top) {
                spec = height > 2 ? BlockSpec.of(Material.CHISELED_STONE_BRICKS) : LakeKit.rubbleBit(random);
            } else if (y <= LakeTerrain.WATER_Y) {
                spec = BlockSpec.of(random.nextBoolean() ? Material.MOSSY_STONE_BRICKS : Material.STONE_BRICKS);
            } else {
                spec = Blocks.log(Material.QUARTZ_PILLAR, Axis.Y);
            }
            canvas.set(x, y, z, spec);
        }
        // A fallen drum of the column on the bed beside it.
        if (bed + 1 < LakeTerrain.WATER_Y && terrain.isWater(x + 1, z) && random.nextBoolean()) {
            canvas.set(x + 1, terrain.top(x + 1, z) + 1, z, Blocks.log(Material.QUARTZ_PILLAR, Axis.X));
        }
        terrain.reserve(x - 1, z - 1, x + 1, z + 1);
    }

    // ------------------------------------------------------------------ islet

    /** A weathered stone lantern and a broken statue base on the islet where the walks meet. */
    private void isletShrine() {
        int x = ISLET_X + 1;
        int z = ISLET_Z - 2;
        canvas.set(x, GROUND + 1, z, BlockSpec.of(Material.CHISELED_STONE_BRICKS));
        canvas.set(x, GROUND + 2, z, BlockSpec.of(Material.STONE_BRICKS));
        canvas.set(x, GROUND + 3, z, Blocks.slab(Material.STONE_BRICK_SLAB).slab(
                Slab.Type.DOUBLE));
        canvas.set(x, GROUND + 4, z, Blocks.lantern(false, false));
        canvas.set(ISLET_X - 1, GROUND + 1, ISLET_Z - 2, BlockSpec.of(Material.MOSSY_STONE_BRICKS));
        canvas.set(ISLET_X - 1, GROUND + 2, ISLET_Z - 2, Blocks.stairs(Material.MOSSY_STONE_BRICK_STAIRS,
                BlockFace.WEST));
    }

    /** Flat stones a jump apart across the shallow water, from {@code x1, z1} to {@code x2, z2}. */
    private void steppingStones(int x1, int z1, int x2, int z2) {
        double length = Math.hypot(x2 - x1, z2 - z1);
        int count = (int) Math.round(length / 2);
        for (int i = 0; i <= count; i++) {
            double t = (double) i / count;
            int x = (int) Math.round(x1 + (x2 - x1) * t);
            int z = (int) Math.round(z1 + (z2 - z1) * t);
            if (!terrain.isWater(x, z)) {
                continue;
            }
            int wide = random.nextBoolean() ? 1 : -1;
            for (int sx : new int[] {x, x + wide}) {
                if (!terrain.isWater(sx, z)) {
                    continue;
                }
                int bed = terrain.top(sx, z);
                for (int y = bed + 1; y <= LakeTerrain.WATER_Y; y++) {
                    canvas.set(sx, y, z, y == LakeTerrain.WATER_Y ? stoneTop() : LakeKit.rubble(random));
                }
                terrain.reserve(sx, z, sx, z);
            }
        }
    }

    private BlockSpec stoneTop() {
        int roll = random.nextInt(4);
        return BlockSpec.of(roll == 0 ? Material.MOSSY_COBBLESTONE : roll == 1 ? Material.ANDESITE
                : roll == 2 ? Material.STONE : Material.MOSSY_STONE_BRICKS);
    }

    // ------------------------------------------------------------------ boathouse

    /**
     * An open boathouse on the north shore: a timber frame over the water's edge with a lean-to roof, half fallen in;
     * a pier runs out from it into the west basin.
     */
    private void boathouse() {
        int x1 = BOATHOUSE_X - 3;
        int x2 = BOATHOUSE_X + 3;
        int z1 = BOATHOUSE_Z - 3;
        int z2 = BOATHOUSE_Z + 2;
        for (int x = x1; x <= x2; x++) {
            for (int z = z1; z <= z2; z++) {
                canvas.set(x, GROUND, z, random.nextInt(4) == 0 ? BlockSpec.of(Material.COARSE_DIRT)
                        : BlockSpec.of(Material.SPRUCE_PLANKS));
            }
        }
        // Corner posts and a back wall of planks; the lake side stays open.
        for (int[] p : new int[][] {{x1, z1}, {x2, z1}, {x1, z2}, {x2, z2}, {BOATHOUSE_X, z1}}) {
            LakeKit.post(canvas, p[0], p[1], GROUND + 1, GROUND + (p[1] == z1 ? 5 : 4), Material.SPRUCE_LOG);
        }
        for (int x = x1 + 1; x < x2; x++) {
            if (x == BOATHOUSE_X) {
                continue;
            }
            for (int y = GROUND + 1; y <= GROUND + 4; y++) {
                if (y <= GROUND + 2 || random.nextInt(3) > 0) {
                    canvas.set(x, y, z1, BlockSpec.of(Material.SPRUCE_PLANKS));
                }
            }
        }
        // Side walls: half height, broken.
        for (int z = z1 + 1; z < z2; z++) {
            for (int x : new int[] {x1, x2}) {
                if (random.nextInt(4) > 0) {
                    canvas.set(x, GROUND + 1, z, BlockSpec.of(Material.SPRUCE_FENCE));
                }
            }
        }
        // Lean-to roof: high at the back, low at the front, missing over the east half. A stack of crates by the
        // west side lets players climb onto it.
        for (int x = x1 - 1; x <= x2 + 1; x++) {
            for (int z = z1 - 1; z <= z2 + 1; z++) {
                int y = GROUND + 7 - (z - (z1 - 1)) / 2;
                boolean fallen = x > BOATHOUSE_X + 1 && z > z1;
                if (fallen) {
                    continue;
                }
                canvas.set(x, y, z, Blocks.stairs(Material.SPRUCE_STAIRS, BlockFace.NORTH));
            }
        }
        boolean room = true;
        for (int step = 1; step <= 3; step++) {
            room &= terrain.isMeadow(x1 - 5 + step, z2 + 1);
        }
        for (int step = 1; step <= 3 && room; step++) {
            for (int y = GROUND + 1; y <= GROUND + step; y++) {
                canvas.set(x1 - 5 + step, y, z2 + 1, BlockSpec.of(step == 3 && y == GROUND + 3
                        ? Material.HAY_BLOCK : Material.BARREL));
            }
        }
        // Inside: barrels, a workbench, a hanging lantern, an upturned rowboat of slabs.
        canvas.set(x1 + 1, GROUND + 1, z1 + 1, BlockSpec.of(Material.BARREL));
        canvas.set(x1 + 1, GROUND + 2, z1 + 1, BlockSpec.of(Material.BARREL));
        canvas.set(x1 + 2, GROUND + 1, z1 + 1, BlockSpec.of(Material.CRAFTING_TABLE));
        canvas.set(BOATHOUSE_X, GROUND + 3, z1 + 2, Blocks.lantern(false, true));
        canvas.set(BOATHOUSE_X, GROUND + 4, z1 + 2, Blocks.log(Material.IRON_CHAIN, Axis.Y));
        for (int x = BOATHOUSE_X + 1; x <= BOATHOUSE_X + 2; x++) {
            canvas.set(x, GROUND + 1, z2 - 1, Blocks.stairs(Material.SPRUCE_STAIRS, BlockFace.EAST));
        }
        canvas.set(BOATHOUSE_X + 1, GROUND + 1, z2 - 1, Blocks.stairs(Material.SPRUCE_STAIRS, BlockFace.WEST));
        canvas.set(BOATHOUSE_X + 3, GROUND + 1, z2 + 1, BlockSpec.of(Material.BARREL));
    }

    // ------------------------------------------------------------------ cottage

    /** The roofless stone cottage of the old fishers: knee-to-head high walls, a doorway and a gap on each side. */
    private void cottage() {
        for (int x = COTTAGE_X1; x <= COTTAGE_X2; x++) {
            for (int z = COTTAGE_Z1; z <= COTTAGE_Z2; z++) {
                boolean wall = x == COTTAGE_X1 || x == COTTAGE_X2 || z == COTTAGE_Z1 || z == COTTAGE_Z2;
                if (!wall) {
                    canvas.set(x, GROUND, z, random.nextInt(3) == 0 ? BlockSpec.of(Material.COARSE_DIRT)
                            : BlockSpec.of(Material.SPRUCE_PLANKS));
                    continue;
                }
                boolean corner = (x == COTTAGE_X1 || x == COTTAGE_X2) && (z == COTTAGE_Z1 || z == COTTAGE_Z2);
                int height = corner ? 4 : 1 + random.nextInt(3) + (x == COTTAGE_X1 ? 1 : 0);
                for (int y = GROUND + 1; y <= GROUND + height; y++) {
                    canvas.set(x, y, z, corner ? Blocks.log(Material.SPRUCE_LOG, Axis.Y) : LakeKit.rubble(random));
                }
                if (!corner && height < 3 && random.nextBoolean()) {
                    canvas.set(x, GROUND + height + 1, z, LakeKit.rubbleBit(random));
                }
            }
        }
        // Doorway towards the lake (east), a broken window gap north and a collapsed corner south.
        int midZ = (COTTAGE_Z1 + COTTAGE_Z2) / 2;
        clearWall(COTTAGE_X2, midZ, COTTAGE_X2, midZ + 1);
        clearWall(COTTAGE_X1 + 2, COTTAGE_Z1, COTTAGE_X1 + 3, COTTAGE_Z1);
        clearWall(COTTAGE_X1 + 1, COTTAGE_Z2, COTTAGE_X1 + 2, COTTAGE_Z2);
        Blocks.door(canvas, COTTAGE_X2, GROUND + 1, midZ, Material.SPRUCE_DOOR, BlockFace.EAST, true);
        // A hearth, a table and a fallen roof beam.
        canvas.set(COTTAGE_X1 + 1, GROUND + 1, COTTAGE_Z1 + 1, BlockSpec.of(Material.COBBLESTONE));
        canvas.set(COTTAGE_X1 + 1, GROUND + 2, COTTAGE_Z1 + 1, Blocks.campfire(false));
        canvas.set(COTTAGE_X2 - 2, GROUND + 1, COTTAGE_Z2 - 1, BlockSpec.of(Material.SPRUCE_FENCE));
        canvas.set(COTTAGE_X2 - 2, GROUND + 2, COTTAGE_Z2 - 1, Blocks.slab(Material.SPRUCE_SLAB).slab(
                Slab.Type.TOP));
        for (int x = COTTAGE_X1 + 1; x <= COTTAGE_X2 - 2; x++) {
            canvas.set(x, GROUND + 1, midZ, Blocks.log(Material.SPRUCE_LOG, Axis.X));
        }
    }

    private void clearWall(int x1, int z1, int x2, int z2) {
        for (int x = x1; x <= x2; x++) {
            for (int z = z1; z <= z2; z++) {
                for (int y = GROUND + 1; y <= GROUND + 5; y++) {
                    canvas.clear(x, y, z);
                }
            }
        }
    }

    // ------------------------------------------------------------------ lookout

    /**
     * A wooden lookout platform (+6) on log stilts in the north-west corner, open on all sides: a stair on the south
     * side and a ladder on the north side lead up.
     */
    private void lookout() {
        int x1 = LOOKOUT_X - 2;
        int x2 = LOOKOUT_X + 2;
        int z1 = LOOKOUT_Z - 2;
        int z2 = LOOKOUT_Z + 2;
        for (int x = x1; x <= x2; x++) {
            for (int z = z1; z <= z2; z++) {
                boolean corner = (x == x1 || x == x2) && (z == z1 || z == z2);
                if (corner) {
                    LakeKit.post(canvas, x, z, GROUND + 1, LOOKOUT_DECK + 1, Material.SPRUCE_LOG);
                } else {
                    canvas.set(x, LOOKOUT_DECK, z, random.nextInt(8) == 0 ? Blocks.slab(Material.SPRUCE_SLAB)
                            : BlockSpec.of(Material.SPRUCE_PLANKS));
                    boolean edge = x == x1 || x == x2 || z == z1 || z == z2;
                    if (edge && random.nextInt(3) > 0 && !(z == z2 && x == LOOKOUT_X)
                            && !(z == z1 && x == LOOKOUT_X)) {
                        canvas.set(x, LOOKOUT_DECK + 1, z, BlockSpec.of(Material.SPRUCE_FENCE));
                    }
                }
            }
        }
        // Cross bracing under the deck.
        for (int x = x1 + 1; x < x2; x++) {
            canvas.set(x, LOOKOUT_DECK - 1, z1, Blocks.log(Material.STRIPPED_SPRUCE_LOG, Axis.X));
            canvas.set(x, LOOKOUT_DECK - 1, z2, Blocks.log(Material.STRIPPED_SPRUCE_LOG, Axis.X));
        }
        canvas.set(x1, LOOKOUT_DECK + 2, z1, Blocks.lantern(false, false));
        canvas.set(x2, LOOKOUT_DECK + 2, z2, Blocks.lantern(false, false));
        // The stair: a straight flight from the south, landing on the deck's south edge.
        for (int i = 0; i < 5; i++) {
            int z = z2 + 5 - i;
            int y = GROUND + 1 + i;
            for (int x = LOOKOUT_X - 1; x <= LOOKOUT_X; x++) {
                canvas.set(x, y, z, Blocks.stairs(Material.SPRUCE_STAIRS, BlockFace.NORTH));
                if (i > 0) {
                    canvas.set(x, y - 1, z, Blocks.stairsTop(Material.SPRUCE_STAIRS, BlockFace.SOUTH));
                }
            }
            if (i % 2 == 0 && i > 0) {
                LakeKit.post(canvas, LOOKOUT_X + 1, z, GROUND + 1, y, Material.SPRUCE_LOG);
            }
        }
        // The ladder on the north side, on a post under the deck's edge.
        LakeKit.post(canvas, LOOKOUT_X, z1, GROUND + 1, LOOKOUT_DECK - 2, Material.SPRUCE_LOG);
        for (int y = GROUND + 1; y <= LOOKOUT_DECK; y++) {
            canvas.set(LOOKOUT_X, y, z1 - 1, BlockSpec.of(Material.LADDER).facing(BlockFace.NORTH));
        }
        canvas.set(LOOKOUT_X, LOOKOUT_DECK - 1, z1, Blocks.log(Material.STRIPPED_SPRUCE_LOG, Axis.X));
    }

    // ------------------------------------------------------------------ gazebo

    /** A ring of broken columns with a fallen dome on the east knoll: cover on a low hill. */
    private void gazebo() {
        int ground = terrain.top(GAZEBO_X, GAZEBO_Z);
        // Floor: a round platform of stone bricks on the knoll's level top.
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d > 3.4 || terrain.top(GAZEBO_X + dx, GAZEBO_Z + dz) != ground) {
                    continue;
                }
                BlockSpec floor = d < 1.5 ? BlockSpec.of(Material.CHISELED_STONE_BRICKS)
                        : random.nextInt(4) == 0 ? BlockSpec.of(Material.MOSS_BLOCK) : LakeKit.brick(random);
                canvas.set(GAZEBO_X + dx, ground, GAZEBO_Z + dz, floor);
            }
        }
        // Six columns; three still carry pieces of the ring beam.
        int[][] columns = {{3, 0}, {2, 3}, {-2, 3}, {-3, 0}, {-2, -3}, {2, -3}};
        int[] heights = {4, 2, 4, 1, 3, 4};
        for (int i = 0; i < columns.length; i++) {
            int x = GAZEBO_X + columns[i][0];
            int z = GAZEBO_Z + columns[i][1];
            for (int y = ground + 1; y <= ground + heights[i]; y++) {
                canvas.set(x, y, z, y == ground + heights[i] && heights[i] == 4
                        ? BlockSpec.of(Material.CHISELED_STONE_BRICKS)
                        : Blocks.log(Material.QUARTZ_PILLAR, Axis.Y));
            }
            if (heights[i] < 4) {
                canvas.set(x, ground + heights[i] + 1, z, LakeKit.rubbleBit(random));
            }
        }
        // Lintels between the tall columns.
        canvas.set(GAZEBO_X + 3, ground + 4, GAZEBO_Z - 1, Blocks.stairsTop(Material.STONE_BRICK_STAIRS,
                BlockFace.EAST));
        canvas.set(GAZEBO_X + 3, ground + 4, GAZEBO_Z - 2, Blocks.slabTop(Material.STONE_BRICK_SLAB));
        canvas.set(GAZEBO_X + 1, ground + 4, GAZEBO_Z - 3, Blocks.slabTop(Material.STONE_BRICK_SLAB));
        canvas.set(GAZEBO_X, ground + 4, GAZEBO_Z - 3, Blocks.slabTop(Material.STONE_BRICK_SLAB));
        // The fallen dome lies in pieces beside the platform; a lantern still stands in the middle.
        LakeKit.rubbleHeap(canvas, random, GAZEBO_X - 5, GAZEBO_Z + 3, canvas.surfaceY(GAZEBO_X - 5,
                GAZEBO_Z + 3), 1.6);
        canvas.set(GAZEBO_X, ground + 1, GAZEBO_Z, BlockSpec.of(Material.CHISELED_STONE_BRICKS));
        canvas.set(GAZEBO_X, ground + 2, GAZEBO_Z, Blocks.lantern(false, false));
    }
}
