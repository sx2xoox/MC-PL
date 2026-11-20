package io.github.sx2xoox.mcpl.core.map.citadel;

import java.util.Random;

import io.github.sx2xoox.mcpl.core.map.BlockSpec;
import io.github.sx2xoox.mcpl.core.map.Blocks;
import io.github.sx2xoox.mcpl.core.map.MapCanvas;
import org.bukkit.Axis;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;

/**
 * The ruined water mill on the lake's east shore. A stone-lined mill race leads lake water east past a big water
 * wheel (11 blocks high, half in the water) that hangs on the mill's south wall. The mill has a stone ground floor
 * and a timber upper floor (+5) reached by an inner stair and an outer stair; its half-collapsed roof (+10..+14) is
 * reached by a ladder on the north wall. From the upper floor's broken south wall one can step onto the wheel.
 */
final class LakeMill {

    // The house (walls included); its south wall is also the race's north wall.
    static final int HX1 = -46;
    static final int HX2 = -35;
    static final int HZ1 = 22;
    static final int HZ2 = 29;
    static final int FLOOR = LakeTerrain.GROUND_Y;
    static final int UPPER = FLOOR + 5;
    static final int EAVE = FLOOR + 10;

    // The race: water 3 wide, 4 deep, from the lake to a landing stair at its east end.
    static final int RACE_Z1 = 30;
    static final int RACE_Z2 = 32;
    static final int RACE_WEST = -51;
    static final int RACE_EAST = -32;
    static final int RACE_BED = LakeTerrain.WATER_Y - 4;

    // The wheel: in the race, hub level with the ground floor's head height.
    static final int WHEEL_X = -40;
    static final int WHEEL_Y = FLOOR + 1;
    static final double WHEEL_R = 5;

    private final MapCanvas canvas;
    private final LakeTerrain terrain;
    private final Random random;

    LakeMill(MapCanvas canvas, LakeTerrain terrain, Random random) {
        this.canvas = canvas;
        this.terrain = terrain;
        this.random = random;
    }

    /** Carves the race and levels the mill's plot (before the terrain is written). */
    static void plan(LakeTerrain terrain) {
        terrain.flatten(HX1 - 1, HZ1 - 2, HX2 + 2, HZ2, FLOOR);
        for (int x = RACE_WEST; x <= RACE_EAST; x++) {
            for (int z = RACE_Z1 - 1; z <= RACE_Z2 + 1; z++) {
                boolean water = z >= RACE_Z1 && z <= RACE_Z2 && x < RACE_EAST;
                if (water) {
                    terrain.setTop(x, z, RACE_BED);
                } else if (terrain.top(x, z) >= LakeTerrain.WATER_Y || x > HX1 - 3) {
                    terrain.setTop(x, z, FLOOR);
                }
            }
        }
        terrain.reserve(HX1 - 2, HZ1 - 3, HX2 + 3, RACE_Z2 + 2);
    }

    void build() {
        race();
        house();
        roof();
        collapse();
        wheel();
        outerStair();
        furnish();
    }

    // ------------------------------------------------------------------ race

    private void race() {
        for (int x = RACE_WEST; x <= RACE_EAST; x++) {
            for (int z : new int[] {RACE_Z1 - 1, RACE_Z2 + 1}) {
                boolean houseWall = z == HZ2 && x >= HX1 && x <= HX2;
                if (houseWall || terrain.isWater(x, z)) {
                    continue;
                }
                for (int y = RACE_BED; y <= FLOOR; y++) {
                    canvas.set(x, y, z, y == FLOOR && random.nextInt(4) == 0 ? BlockSpec.of(Material.MOSSY_COBBLESTONE)
                            : LakeKit.brick(random));
                }
                // A low broken parapet along the south side.
                if (z == RACE_Z2 + 1 && random.nextInt(5) < 3 && x > RACE_WEST + 4) {
                    canvas.set(x, FLOOR + 1, z, LakeKit.brickRail(random));
                }
            }
            for (int z = RACE_Z1; z <= RACE_Z2; z++) {
                canvas.set(x, RACE_BED, z, random.nextInt(3) == 0 ? BlockSpec.of(Material.GRAVEL)
                        : LakeKit.brick(random));
                canvas.set(x, RACE_BED - 1, z, BlockSpec.of(Material.STONE));
            }
        }
        // The landing stair at the east end: out of the water in two steps.
        for (int z = RACE_Z1; z <= RACE_Z2; z++) {
            for (int y = RACE_BED; y < LakeTerrain.WATER_Y; y++) {
                canvas.set(RACE_EAST, y, z, LakeKit.brick(random));
            }
            canvas.set(RACE_EAST, LakeTerrain.WATER_Y, z, LakeKit.brickStairs(random, BlockFace.EAST));
            canvas.clear(RACE_EAST, FLOOR, z);
            canvas.set(RACE_EAST + 1, FLOOR, z, LakeKit.brick(random));
        }
    }

    // ------------------------------------------------------------------ house

    private void house() {
        // Ground floor: cobble, planks and trodden dirt.
        for (int x = HX1 + 1; x < HX2; x++) {
            for (int z = HZ1 + 1; z < HZ2; z++) {
                int roll = random.nextInt(10);
                canvas.set(x, FLOOR, z, roll < 4 ? BlockSpec.of(Material.SPRUCE_PLANKS)
                        : roll < 7 ? BlockSpec.of(Material.COBBLESTONE) : roll < 9
                        ? BlockSpec.of(Material.COARSE_DIRT) : BlockSpec.of(Material.MOSSY_COBBLESTONE));
            }
        }
        for (int x = HX1; x <= HX2; x++) {
            for (int z = HZ1; z <= HZ2; z++) {
                boolean wall = x == HX1 || x == HX2 || z == HZ1 || z == HZ2;
                if (!wall) {
                    continue;
                }
                boolean corner = (x == HX1 || x == HX2) && (z == HZ1 || z == HZ2);
                int base = z == HZ2 ? RACE_BED : FLOOR - 2;
                for (int y = base; y <= EAVE; y++) {
                    canvas.set(x, y, z, wallBlock(x, y, z, corner));
                }
            }
        }
        // Upper floor with the stairwell and a collapsed corner.
        for (int x = HX1 + 1; x < HX2; x++) {
            for (int z = HZ1 + 1; z < HZ2; z++) {
                boolean stairwell = x >= HX2 - 5 && x <= HX2 - 2 && z <= HZ1 + 2;
                boolean collapsed = x <= HX1 + 3 && z >= HZ2 - 3 && !(x == HX1 + 3 && z == HZ2 - 3);
                if (stairwell || collapsed) {
                    continue;
                }
                canvas.set(x, UPPER, z, random.nextInt(9) == 0 ? Blocks.slab(Material.SPRUCE_SLAB)
                        : BlockSpec.of(random.nextInt(4) == 0 ? Material.OAK_PLANKS : Material.SPRUCE_PLANKS));
            }
        }
        // Beams under the upper floor.
        for (int x = HX1 + 1; x < HX2; x++) {
            if (canvas.isSolid(x, UPPER, HZ1 + 3)) {
                canvas.set(x, UPPER - 1, HZ1 + 3, Blocks.log(Material.STRIPPED_SPRUCE_LOG, Axis.X));
            }
        }
        openings();
        innerStair();
    }

    private BlockSpec wallBlock(int x, int y, int z, boolean corner) {
        if (y < UPPER) {
            if (corner) {
                return LakeKit.brick(random);
            }
            return LakeKit.rubble(random);
        }
        boolean post = corner || (x - HX1) % 4 == 0 && (z == HZ1 || z == HZ2)
                || (z - HZ1) % 4 == 0 && (x == HX1 || x == HX2);
        if (y == UPPER || y == EAVE - 1) {
            Axis axis = z == HZ1 || z == HZ2 ? Axis.X : Axis.Z;
            return corner ? Blocks.log(Material.SPRUCE_LOG, Axis.Y) : Blocks.log(Material.STRIPPED_SPRUCE_LOG, axis);
        }
        if (post) {
            return Blocks.log(Material.SPRUCE_LOG, Axis.Y);
        }
        if (y == EAVE) {
            return BlockSpec.of(Material.SPRUCE_PLANKS);
        }
        return random.nextInt(5) == 0 ? BlockSpec.of(Material.STRIPPED_SPRUCE_WOOD)
                : BlockSpec.of(Material.SPRUCE_PLANKS);
    }

    private void openings() {
        // Ground floor: north door, east door, and a wide opening to the quay on the lake side.
        Blocks.door(canvas, HX1 + 5, FLOOR + 1, HZ1, Material.SPRUCE_DOOR, BlockFace.NORTH, true);
        Blocks.door(canvas, HX2, FLOOR + 1, HZ1 + 4, Material.SPRUCE_DOOR, BlockFace.EAST, false);
        for (int z = HZ1 + 3; z <= HZ1 + 4; z++) {
            for (int y = FLOOR + 1; y <= FLOOR + 3; y++) {
                canvas.clear(HX1, y, z);
            }
            canvas.set(HX1, FLOOR + 4, z, Blocks.log(Material.SPRUCE_LOG, Axis.Z));
        }
        // Ground floor windows.
        for (int x : new int[] {HX1 + 2, HX1 + 7}) {
            canvas.set(x, FLOOR + 2, HZ1, BlockSpec.of(Material.SPRUCE_TRAPDOOR).facing(BlockFace.NORTH)
                    .half(Bisected.Half.TOP).open(true));
            canvas.clear(x, FLOOR + 3, HZ1);
        }
        // Upper floor: the broken south wall over the wheel, the outer stair's door and windows.
        for (int x = WHEEL_X - 3; x <= WHEEL_X + 2; x++) {
            for (int y = UPPER + 1; y <= UPPER + 3; y++) {
                canvas.clear(x, y, HZ2);
            }
        }
        canvas.set(WHEEL_X - 3, UPPER + 1, HZ2, BlockSpec.of(Material.SPRUCE_FENCE));
        canvas.set(WHEEL_X + 2, UPPER + 1, HZ2, BlockSpec.of(Material.SPRUCE_FENCE));
        for (int z = HZ1 + 4; z <= HZ1 + 5; z++) {
            for (int y = UPPER + 1; y <= UPPER + 3; y++) {
                canvas.clear(HX2, y, z);
            }
        }
        for (int x : new int[] {HX1 + 2, HX1 + 6, HX2 - 4}) {
            canvas.clear(x, UPPER + 2, HZ1);
        }
    }

    /** The lake-side gable lost its southern half with the roof above it. */
    private void collapse() {
        for (int z = HZ1 + 4; z <= HZ2 - 1; z++) {
            int from = UPPER + 1 + (HZ2 - z) / 2 + random.nextInt(2);
            for (int y = from; y <= EAVE + 5; y++) {
                canvas.clear(HX1, y, z);
            }
            if (random.nextBoolean()) {
                canvas.set(HX1, from, z, LakeKit.rubbleBit(random));
            }
        }
    }

    /** A straight stair along the north wall, climbing west to the upper floor. */
    private void innerStair() {
        for (int i = 0; i < 4; i++) {
            int x = HX2 - 2 - i;
            for (int z = HZ1 + 1; z <= HZ1 + 2; z++) {
                for (int y = FLOOR + 1; y < FLOOR + 1 + i; y++) {
                    canvas.set(x, y, z, BlockSpec.of(Material.SPRUCE_PLANKS));
                }
                canvas.set(x, FLOOR + 1 + i, z, Blocks.stairs(Material.SPRUCE_STAIRS, BlockFace.WEST));
            }
        }
        canvas.set(HX2 - 1, UPPER + 1, HZ1 + 3, BlockSpec.of(Material.SPRUCE_FENCE));
        canvas.set(HX2 - 2, UPPER + 1, HZ1 + 3, BlockSpec.of(Material.SPRUCE_FENCE));
    }

    // ------------------------------------------------------------------ roof

    private void roof() {
        int ridgeA = (HZ1 + HZ2) / 2;
        for (int x = HX1 - 1; x <= HX2 + 1; x++) {
            for (int z = HZ1 - 1; z <= HZ2 + 1; z++) {
                int fromNorth = z - (HZ1 - 1);
                int fromSouth = (HZ2 + 1) - z;
                int y = EAVE + Math.min(fromNorth, fromSouth);
                boolean collapsed = x <= HX1 + 4 && z > ridgeA;
                if (collapsed) {
                    if (x == HX1 + 2) {
                        // A bare rafter left standing.
                        canvas.set(x, y - 1, z, Blocks.log(Material.SPRUCE_LOG, Axis.Z));
                    }
                    continue;
                }
                BlockSpec spec;
                if (fromNorth == fromSouth || Math.abs(fromNorth - fromSouth) == 1 && Math.min(fromNorth,
                        fromSouth) == (HZ2 - HZ1 + 2) / 2) {
                    spec = BlockSpec.of(Material.DARK_OAK_PLANKS);
                } else {
                    BlockFace up = fromNorth < fromSouth ? BlockFace.SOUTH : BlockFace.NORTH;
                    spec = Blocks.stairs(random.nextInt(5) == 0 ? Material.SPRUCE_STAIRS : Material.DARK_OAK_STAIRS,
                            up);
                }
                canvas.set(x, y, z, spec);
                // Gable triangles under the roof at both ends.
                if ((x == HX1 || x == HX2) && z > HZ1 && z < HZ2) {
                    for (int fill = EAVE + 1; fill < y; fill++) {
                        canvas.set(x, fill, z, BlockSpec.of(Material.SPRUCE_PLANKS));
                    }
                }
            }
        }
        // The ladder to the roof, on the north wall.
        int lx = HX2 - 2;
        for (int y = FLOOR + 1; y <= EAVE; y++) {
            canvas.set(lx, y, HZ1 - 1, BlockSpec.of(Material.LADDER).facing(BlockFace.NORTH));
        }
        // A second way up: from the wheel's rim a short ladder on the south wall reaches the eave.
        for (int y = UPPER + 1; y <= EAVE; y++) {
            canvas.set(WHEEL_X + 3, y, HZ2 + 1, BlockSpec.of(Material.LADDER).facing(BlockFace.SOUTH));
        }
    }

    // ------------------------------------------------------------------ wheel

    private void wheel() {
        int r = (int) Math.ceil(WHEEL_R + 0.5);
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                double d = Math.sqrt(dx * dx + dy * dy);
                int x = WHEEL_X + dx;
                int y = WHEEL_Y + dy;
                if (y <= RACE_BED) {
                    continue;
                }
                double angle = Math.toDegrees(Math.atan2(dy, dx));
                if (d >= WHEEL_R - 0.5 && d < WHEEL_R + 0.5) {
                    canvas.set(x, y, RACE_Z1, rim(dx, dy));
                    canvas.set(x, y, RACE_Z2, rim(dx, dy));
                    double inStep = ((angle % 30) + 30) % 30;
                    if (inStep < 7 || inStep > 23) {
                        canvas.set(x, y, (RACE_Z1 + RACE_Z2) / 2, BlockSpec.of(Material.SPRUCE_PLANKS));
                    }
                } else if (d >= 0.9 && d < WHEEL_R - 0.5 && (dx == 0 || dy == 0 || Math.abs(dx) == Math.abs(dy))) {
                    BlockSpec spoke = dy == 0 ? Blocks.log(Material.SPRUCE_LOG, Axis.X)
                            : dx == 0 ? Blocks.log(Material.SPRUCE_LOG, Axis.Y)
                            : BlockSpec.of(Material.STRIPPED_SPRUCE_WOOD);
                    canvas.set(x, y, RACE_Z1, spoke);
                    canvas.set(x, y, RACE_Z2, spoke);
                }
            }
        }
        // Hub and axle through the mill wall to the gear inside.
        for (int z = HZ2 - 1; z <= RACE_Z2; z++) {
            canvas.set(WHEEL_X, WHEEL_Y, z, Blocks.log(Material.DARK_OAK_LOG, Axis.Z));
        }
        canvas.set(WHEEL_X - 1, WHEEL_Y, HZ2 - 1, Blocks.stairs(Material.DARK_OAK_STAIRS, BlockFace.EAST));
        canvas.set(WHEEL_X + 1, WHEEL_Y, HZ2 - 1, Blocks.stairs(Material.DARK_OAK_STAIRS, BlockFace.WEST));
        canvas.set(WHEEL_X, WHEEL_Y + 1, HZ2 - 1, Blocks.stairsTop(Material.DARK_OAK_STAIRS, BlockFace.NORTH));
    }

    private static BlockSpec rim(int dx, int dy) {
        Axis axis = Math.abs(dx) > Math.abs(dy) ? Axis.Y : Axis.X;
        return Blocks.log(Material.STRIPPED_SPRUCE_LOG, axis);
    }

    // ------------------------------------------------------------------ outside

    /** A wooden stair up the east wall to the upper floor's door, on posts. */
    private void outerStair() {
        int x = HX2 + 1;
        for (int i = 0; i < 4; i++) {
            int z = HZ1 + i;
            int y = FLOOR + 1 + i;
            canvas.set(x, y, z, Blocks.stairs(Material.SPRUCE_STAIRS, BlockFace.SOUTH));
            canvas.set(x + 1, y, z, Blocks.stairs(Material.SPRUCE_STAIRS, BlockFace.SOUTH));
            for (int below = FLOOR + 1; below < y; below++) {
                canvas.set(x + 1, below, z, below == y - 1 ? Blocks.stairsTop(Material.SPRUCE_STAIRS,
                        BlockFace.NORTH) : Blocks.log(Material.SPRUCE_LOG, Axis.Y));
            }
        }
        // The landing in front of the upper door.
        for (int z = HZ1 + 4; z <= HZ1 + 5; z++) {
            canvas.set(x, UPPER, z, BlockSpec.of(Material.SPRUCE_PLANKS));
            canvas.set(x + 1, UPPER, z, BlockSpec.of(Material.SPRUCE_PLANKS));
            canvas.set(x + 1, UPPER + 1, z, BlockSpec.of(Material.SPRUCE_FENCE));
        }
        LakeKit.post(canvas, x + 1, HZ1 + 5, FLOOR + 1, UPPER - 1, Material.SPRUCE_LOG);
        canvas.set(x + 1, UPPER + 2, HZ1 + 5, Blocks.lantern(false, false));
    }

    private void furnish() {
        // Millstones by the gear, sacks and barrels, hay in the corner.
        canvas.set(WHEEL_X - 2, FLOOR + 1, HZ2 - 3, BlockSpec.of(Material.SMOOTH_STONE));
        canvas.set(WHEEL_X - 3, FLOOR + 1, HZ2 - 3, BlockSpec.of(Material.SMOOTH_STONE));
        canvas.set(WHEEL_X - 2, FLOOR + 2, HZ2 - 3, Blocks.slab(Material.SMOOTH_STONE_SLAB));
        canvas.set(WHEEL_X - 3, FLOOR + 2, HZ2 - 3, Blocks.slab(Material.SMOOTH_STONE_SLAB));
        canvas.set(WHEEL_X - 1, FLOOR + 1, HZ2 - 3, BlockSpec.of(Material.GRINDSTONE));
        canvas.set(HX2 - 1, FLOOR + 1, HZ2 - 1, BlockSpec.of(Material.BARREL));
        canvas.set(HX2 - 1, FLOOR + 1, HZ2 - 2, BlockSpec.of(Material.BARREL));
        canvas.set(HX2 - 1, FLOOR + 2, HZ2 - 1, BlockSpec.of(Material.BROWN_WOOL));
        // Mooring posts on the quay in front of the lake-side opening.
        canvas.set(HX1 - 1, FLOOR + 1, HZ1 + 1, BlockSpec.of(Material.SPRUCE_FENCE));
        canvas.set(HX1 - 1, FLOOR + 1, HZ2 - 1, BlockSpec.of(Material.SPRUCE_FENCE));
        canvas.set(HX1 - 1, FLOOR + 2, HZ2 - 1, Blocks.lantern(false, false));
        canvas.set(HX1 + 1, FLOOR + 1, HZ1 + 1, BlockSpec.of(Material.HAY_BLOCK));
        canvas.set(HX1 + 2, FLOOR + 1, HZ1 + 1, BlockSpec.of(Material.HAY_BLOCK));
        canvas.set(HX1 + 1, FLOOR + 2, HZ1 + 1, BlockSpec.of(Material.HAY_BLOCK));
        // Rubble under the collapsed corner of the upper floor.
        LakeKit.rubbleHeap(canvas, random, HX1 + 2, HZ2 - 2, FLOOR, 1.3);
        // Upstairs: sacks, a lantern and broken planks.
        canvas.set(HX1 + 2, UPPER + 1, HZ1 + 2, BlockSpec.of(Material.BROWN_WOOL));
        canvas.set(HX1 + 3, UPPER + 1, HZ1 + 1, BlockSpec.of(Material.BROWN_WOOL));
        canvas.set(HX1 + 2, UPPER + 1, HZ1 + 1, BlockSpec.of(Material.BARREL));
        canvas.set(HX1 + 2, UPPER + 2, HZ1 + 1, BlockSpec.of(Material.BROWN_WOOL));
        canvas.set(HX2 - 3, EAVE - 2, HZ1 + 4, Blocks.lantern(false, true));
        for (int y = EAVE - 1; y < EAVE + 4; y++) {
            canvas.set(HX2 - 3, y, HZ1 + 4, Blocks.log(Material.IRON_CHAIN, Axis.Y));
        }
    }
}
