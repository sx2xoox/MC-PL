package io.github.sx2xoox.mcpl.core.map.citadel;

import static io.github.sx2xoox.mcpl.core.map.citadel.KeepKit.FLOOR;
import static io.github.sx2xoox.mcpl.core.map.citadel.KeepKit.GROUND;

import java.util.Random;

import io.github.sx2xoox.mcpl.core.map.BlockSpec;
import io.github.sx2xoox.mcpl.core.map.Blocks;
import io.github.sx2xoox.mcpl.core.map.MapCanvas;
import org.bukkit.Axis;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;

/**
 * The keep tower in the middle of the courtyard: a square donjon of 15 by 15 blocks with corner turrets, three floors
 * of five blocks and a crenellated roof 15 blocks up.
 *
 * <ul>
 *   <li>Ground floor: wide doors east and west and a breach in the north wall; three pillars; the north-west part of
 *       the floor above fell in and its rubble makes a ramp up to the first floor.</li>
 *   <li>First floor: entered from outside by the forebuilding stair on the south face; hearth and hall.</li>
 *   <li>Second floor: open to the sky in the north-east corner, where walls and roof fell.</li>
 *   <li>Roof: battlements, turret tops, holes.</li>
 * </ul>
 * Stairs: ground to first floor along the south wall, first to second along the north wall, second to roof along the
 * east wall; a ladder in the south-east corner runs from the ground to the roof.
 */
final class KeepTower {

    /** The walls stand at {@code |x| == HALF} or {@code |z| == HALF}. */
    static final int HALF = 7;
    /** Floor blocks of the first and second floor and the roof. */
    static final int F2 = FLOOR + 4;
    static final int F3 = F2 + 5;
    static final int ROOF = F3 + 5;
    private static final int TURRET_TOP = ROOF + 3;
    /** Top of the north-west watch turret. */
    private static final int WATCH_TOP = ROOF + 9;

    private final MapCanvas canvas;
    private final Random random;
    private final int salt;

    KeepTower(MapCanvas canvas, Random random) {
        this.canvas = canvas;
        this.random = random;
        this.salt = random.nextInt();
    }

    static boolean isTurret(int x, int z) {
        return Math.abs(x) >= HALF - 1 && Math.abs(x) <= HALF + 1 && Math.abs(z) >= HALF - 1
                && Math.abs(z) <= HALF + 1;
    }

    static boolean isWall(int x, int z) {
        return !isTurret(x, z) && (Math.abs(x) == HALF && Math.abs(z) <= HALF
                || Math.abs(z) == HALF && Math.abs(x) <= HALF);
    }

    static boolean isInterior(int x, int z) {
        return Math.abs(x) < HALF && Math.abs(z) < HALF && !isTurret(x, z);
    }

    void build() {
        groundFloor();
        walls();
        turrets();
        floors();
        pilasters();
        doors();
        windows();
        stairs();
        northWestFall();
        northEastFall();
        forebuilding();
        roof();
        furnish();
        ivy();
    }

    // ------------------------------------------------------------------ shell

    private void groundFloor() {
        for (int x = -HALF; x <= HALF; x++) {
            for (int z = -HALF; z <= HALF; z++) {
                if (isInterior(x, z)) {
                    boolean border = Math.abs(x) == HALF - 1 || Math.abs(z) == HALF - 1;
                    canvas.set(x, GROUND, z, border ? BlockSpec.of(Material.POLISHED_ANDESITE)
                            : (x + z & 1) == 0 && random.nextInt(4) > 0 ? BlockSpec.of(Material.STONE_BRICKS)
                            : KeepKit.paving(random));
                }
            }
        }
    }

    private void walls() {
        for (int x = -HALF; x <= HALF; x++) {
            for (int z = -HALF; z <= HALF; z++) {
                if (!isWall(x, z)) {
                    continue;
                }
                for (int y = FLOOR; y <= ROOF + 1; y++) {
                    BlockSpec block;
                    if (y <= FLOOR + 1 || y == F2 || y == F3) {
                        block = KeepKit.darkMasonry(random);
                    } else {
                        block = KeepKit.masonry(random);
                    }
                    canvas.set(x, y, z, block);
                }
                int along = Math.abs(x) == HALF ? z : x;
                if (Math.floorMod(along, 2) == 1 && KeepKit.hash(salt + 13, x, z) < 0.8) {
                    canvas.set(x, ROOF + 2, z, KeepKit.masonry(random));
                }
            }
        }
    }

    /**
     * Square turrets on the four corners; the north-west one rises higher as the keep's old watch turret, the
     * landmark of the map, its crown broken.
     */
    private void turrets() {
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sz = -1; sz <= 1; sz += 2) {
                boolean watch = sx < 0 && sz < 0;
                for (int i = HALF - 1; i <= HALF + 1; i++) {
                    for (int j = HALF - 1; j <= HALF + 1; j++) {
                        int x = sx * i;
                        int z = sz * j;
                        boolean quoin = i == HALF + 1 && j == HALF + 1;
                        int top = TURRET_TOP;
                        if (watch) {
                            top = WATCH_TOP - (int) (KeepKit.hash(salt + 9, x, z) * 2.2) - (i + j < 2 * HALF ? 1 : 0);
                        }
                        for (int y = FLOOR; y <= top; y++) {
                            BlockSpec block = y <= FLOOR + 1 ? KeepKit.darkMasonry(random)
                                    : quoin && (y & 1) == 0 ? BlockSpec.of(Material.POLISHED_ANDESITE)
                                    : KeepKit.masonry(random);
                            canvas.set(x, y, z, block);
                        }
                        boolean outerEdge = i == HALF + 1 || j == HALF + 1;
                        boolean merlon = outerEdge && (i + j) % 2 == 0;
                        if (merlon && !watch) {
                            canvas.set(x, TURRET_TOP + 1, z, KeepKit.masonry(random));
                        }
                    }
                }
                if (watch) {
                    // Slit windows up the watch turret and a string course where it leaves the roof.
                    for (int y = ROOF + 3; y <= WATCH_TOP - 3; y += 3) {
                        canvas.set(-HALF - 1, y, -HALF, BlockSpec.AIR);
                        canvas.set(-HALF, y, -HALF - 1, BlockSpec.AIR);
                    }
                    for (int i = HALF - 1; i <= HALF + 1; i++) {
                        for (int j = HALF - 1; j <= HALF + 1; j++) {
                            if (i == HALF + 1 || j == HALF + 1) {
                                canvas.set(-i, ROOF + 2, -j, KeepKit.darkMasonry(random));
                            }
                        }
                    }
                }
            }
        }
    }

    /** Plank floors on dark oak beams; the roof of stone. */
    private void floors() {
        for (int x = -HALF + 1; x <= HALF - 1; x++) {
            for (int z = -HALF + 1; z <= HALF - 1; z++) {
                if (!isInterior(x, z)) {
                    continue;
                }
                for (int y : new int[] {F2, F3}) {
                    BlockSpec block = z % 4 == 0 ? Blocks.log(Material.DARK_OAK_LOG, Axis.X)
                            : BlockSpec.of(random.nextInt(6) == 0 ? Material.DARK_OAK_PLANKS : Material.SPRUCE_PLANKS);
                    canvas.set(x, y, z, block);
                }
                canvas.set(x, ROOF, z, random.nextInt(3) == 0 ? KeepKit.masonry(random)
                        : BlockSpec.of(Material.STONE_BRICKS));
            }
        }
    }

    /** Shallow pilasters on the east, west and north faces, running up into the parapet. */
    private void pilasters() {
        for (int s = -4; s <= 4; s += 8) {
            pilaster(HALF + 1, s);
            pilaster(-HALF - 1, s);
            pilaster(s, -HALF - 1);
        }
    }

    private void pilaster(int x, int z) {
        for (int y = FLOOR; y <= ROOF + 1; y++) {
            boolean course = y <= FLOOR + 1 || y == F2 || y == F3;
            canvas.set(x, y, z, course ? KeepKit.darkMasonry(random) : KeepKit.masonry(random));
        }
    }

    // ------------------------------------------------------------------ openings

    /** The great doors east and west: five wide, four high in the middle, with a stepped arch and dark jambs. */
    private void doors() {
        for (int side = -1; side <= 1; side += 2) {
            int x = side * HALF;
            for (int z = -2; z <= 2; z++) {
                canvas.set(x, GROUND, z, BlockSpec.of(Material.POLISHED_ANDESITE));
                KeepKit.clear(canvas, x, FLOOR, z, FLOOR + 2);
                if (Math.abs(z) == 2) {
                    canvas.set(x, FLOOR + 3, z, Blocks.stairsTop(Material.STONE_BRICK_STAIRS,
                            z < 0 ? BlockFace.NORTH : BlockFace.SOUTH));
                } else {
                    canvas.set(x, FLOOR + 3, z, BlockSpec.AIR);
                }
            }
            canvas.set(x, F2, 0, BlockSpec.of(Material.CHISELED_STONE_BRICKS));
            for (int z = -3; z <= 3; z += 6) {
                for (int y = FLOOR; y <= FLOOR + 3; y++) {
                    canvas.set(x, y, z, BlockSpec.of(y == FLOOR + 3 ? Material.CHISELED_STONE_BRICKS
                            : Material.POLISHED_ANDESITE));
                }
                // Torches beside the doors.
                canvas.set(x + side, FLOOR + 3, z, Blocks.wallTorch(false, side > 0 ? BlockFace.EAST : BlockFace.WEST));
            }
        }
    }

    /** Arrow slits on the ground floor, windows above, wider on the second floor. */
    private void windows() {
        for (int s = -1; s <= 1; s += 2) {
            // Arrow slits.
            slit(s * HALF, 5);
            slit(s * HALF, -5);
            slit(5 * s, -HALF);
            // First floor: two-wide windows each side of the middle; the south face has the door instead of one.
            for (int k = 2; k <= 3; k++) {
                opening(s * HALF, F2 + 2, k, 3);
                opening(s * HALF, F2 + 2, -k, 3);
                opening(k * s, F2 + 2, -HALF, 3);
                if (s < 0) {
                    opening(k * s, F2 + 2, HALF, 3);
                }
                // Second floor: tall windows.
                opening(s * HALF, F3 + 1, k + 1, 3);
                opening(s * HALF, F3 + 1, -k - 1, 3);
                opening(k * s, F3 + 1, HALF, 3);
            }
        }
    }

    private void slit(int x, int z) {
        canvas.set(x, FLOOR + 1, z, BlockSpec.AIR);
        canvas.set(x, FLOOR + 2, z, BlockSpec.AIR);
    }

    private void opening(int x, int y, int z, int height) {
        if (!isWall(x, z)) {
            return;
        }
        for (int dy = 0; dy < height; dy++) {
            canvas.set(x, y + dy, z, BlockSpec.AIR);
        }
    }

    // ------------------------------------------------------------------ stairs

    /** Three straight runs and a ladder; the floors above each run are opened for head room. */
    private void stairs() {
        // Ground to first floor, along the south wall, rising east.
        for (int k = 0; k <= 4; k++) {
            int x = -5 + k;
            for (int z = 5; z <= 6; z++) {
                for (int y = FLOOR; y < FLOOR + k; y++) {
                    canvas.set(x, y, z, KeepKit.masonry(random));
                }
                canvas.set(x, FLOOR + k, z, Blocks.stairs(Material.STONE_BRICK_STAIRS, BlockFace.EAST));
                if (k >= 1 && k <= 3) {
                    canvas.set(x, F2, z, BlockSpec.AIR);
                }
            }
        }
        // First to second floor, along the north wall, rising west.
        for (int k = 0; k <= 4; k++) {
            int x = 4 - k;
            for (int z = -6; z <= -5; z++) {
                for (int y = F2 + 1; y < F2 + 1 + k; y++) {
                    canvas.set(x, y, z, KeepKit.masonry(random));
                }
                canvas.set(x, F2 + 1 + k, z, Blocks.stairs(Material.SPRUCE_STAIRS, BlockFace.WEST));
                if (k >= 1 && k <= 3) {
                    canvas.set(x, F3, z, BlockSpec.AIR);
                }
            }
        }
        // Second floor to the roof, along the east wall, rising south.
        for (int k = 0; k <= 4; k++) {
            int z = -4 + k;
            for (int x = 5; x <= 6; x++) {
                for (int y = F3 + 1; y < F3 + 1 + k; y++) {
                    canvas.set(x, y, z, KeepKit.masonry(random));
                }
                canvas.set(x, F3 + 1 + k, z, Blocks.stairs(Material.STONE_BRICK_STAIRS, BlockFace.SOUTH));
                if (k >= 1 && k <= 3) {
                    canvas.set(x, ROOF, z, BlockSpec.AIR);
                }
            }
        }
        // The ladder in the south-east corner, ground to roof.
        for (int y = FLOOR; y <= ROOF; y++) {
            canvas.set(5, y, 6, BlockSpec.of(Material.LADDER).facing(BlockFace.NORTH));
        }
    }

    // ------------------------------------------------------------------ ruin

    /**
     * The first floor's north-west corner fell into the hall: its rubble is a ramp from the ground floor up to the
     * first floor, and the north wall beside it is breached at ground level.
     */
    private void northWestFall() {
        for (int x = -6; x <= -2; x++) {
            for (int z = -6; z <= -3; z++) {
                canvas.set(x, F2, z, BlockSpec.AIR);
                int top = GROUND + (x + 6);
                for (int y = FLOOR; y <= top; y++) {
                    canvas.set(x, y, z, KeepKit.rubble(random));
                }
                canvas.set(x, top + 1, z, random.nextInt(5) == 0 ? Blocks.slab(KeepKit.slab(random))
                        : Blocks.stairs(KeepKit.stairs(random), BlockFace.EAST));
            }
        }
        // Ragged edges of the hole.
        for (int x = -6; x <= -1; x++) {
            if (KeepKit.hash(salt, x, -2) > 0.5) {
                canvas.set(x, F2, -2, BlockSpec.AIR);
            }
        }
        // The breach in the north wall, east of the ramp.
        for (int x = -1; x <= 3; x++) {
            int height = 4 - Math.abs(x - 1) + (int) (KeepKit.hash(salt + 1, x, 0) * 2);
            for (int y = FLOOR; y <= FLOOR + height; y++) {
                canvas.set(x, y, -HALF, BlockSpec.AIR);
            }
        }
        // Rubble spilled out of the breach.
        KeepKit.Heap heap = new KeepKit.Heap();
        heap.mound(salt + 2, 2.5, -10.0, 2.6, 1.6);
        heap.mound(salt + 3, -1.5, -9.0, 1.8, 1.0);
        heap.place(canvas, random, true);
    }

    /** The north-east corner of the second floor lost its walls and the roof above: open to the sky. */
    private void northEastFall() {
        for (int x = 1; x <= HALF + 1; x++) {
            for (int z = -HALF - 1; z <= -2; z++) {
                double n = KeepKit.noise(salt + 4, x * 0.6, z * 0.6);
                if (isInterior(x, z) && !(x >= 5 && z >= -4)) {
                    if (z <= -3 && x >= 2 || n > 0.45) {
                        canvas.set(x, ROOF, z, BlockSpec.AIR);
                    }
                }
                boolean shell = isWall(x, z) || isTurret(x, z) || Math.abs(x) == HALF + 1 || z == -HALF - 1;
                if (!shell) {
                    continue;
                }
                double corner = Math.min(x - 1, -2 - z); // 0 at the edges of the fall, larger towards the corner
                int top = ROOF + 2 - (int) Math.round(Math.min(7.0, corner * 1.6 + n * 2.5));
                if (top < ROOF + 2) {
                    KeepKit.clear(canvas, x, Math.max(F3 + 1, top + 1), z, TURRET_TOP + 1);
                }
            }
        }
        // Fallen stone on the second floor and at the foot of the tower.
        KeepKit.Heap below = new KeepKit.Heap();
        below.mound(salt + 5, 10.0, -9.5, 2.8, 2.0);
        below.place(canvas, random, true);
        canvas.set(3, F3 + 1, -3, KeepKit.rubble(random));
        canvas.set(2, F3 + 1, -4, Blocks.slab(KeepKit.slab(random)));
        canvas.set(4, F3 + 1, -2, Blocks.stairs(KeepKit.stairs(random), BlockFace.NORTH));
    }

    // ------------------------------------------------------------------ outside

    /**
     * The forebuilding: an outside stair along the south face from the courtyard up to the first-floor door, with a
     * parapet on its outer side.
     */
    private void forebuilding() {
        for (int x = -5; x <= 4; x++) {
            int step = Math.min(x + 5, 4);
            for (int z = HALF + 1; z <= HALF + 3; z++) {
                boolean parapet = z == HALF + 3;
                int solidTop = x <= -1 && !parapet ? FLOOR + step - 1 : FLOOR + step;
                for (int y = FLOOR; y <= solidTop; y++) {
                    canvas.set(x, y, z, y <= FLOOR ? KeepKit.darkMasonry(random) : KeepKit.masonry(random));
                }
                if (parapet) {
                    canvas.set(x, solidTop + 1, z, KeepKit.coping(random));
                } else if (x <= -1) {
                    canvas.set(x, FLOOR + step, z, Blocks.stairs(Material.STONE_BRICK_STAIRS, BlockFace.EAST));
                }
            }
        }
        canvas.set(4, F2 + 1, HALF + 3, KeepKit.masonry(random));
        canvas.set(4, F2 + 2, HALF + 3, Blocks.lantern(false, false));
        canvas.set(-5, FLOOR + 1, HALF + 3, BlockSpec.of(Material.DARK_OAK_FENCE));
        canvas.set(-5, FLOOR + 2, HALF + 3, Blocks.lantern(false, false));
        // The first-floor door.
        for (int x = 1; x <= 3; x++) {
            for (int y = F2 + 1; y <= F2 + 3; y++) {
                canvas.set(x, y, HALF, BlockSpec.AIR);
            }
        }
        canvas.set(2, F2 + 4, HALF, BlockSpec.AIR);
        canvas.set(1, F2 + 4, HALF, Blocks.stairsTop(Material.STONE_BRICK_STAIRS, BlockFace.WEST));
        canvas.set(3, F2 + 4, HALF, Blocks.stairsTop(Material.STONE_BRICK_STAIRS, BlockFace.EAST));
        canvas.set(2, F3, HALF, BlockSpec.of(Material.CHISELED_STONE_BRICKS));
    }

    /** Roof holes, stairs up to the turret tops. */
    private void roof() {
        // A ragged hole over the south-west of the second floor.
        for (int x = -5; x <= -1; x++) {
            for (int z = 0; z <= 4; z++) {
                double d = KeepKit.radius(x + 3, z - 2);
                if (d < 1.6 || d < 2.6 && KeepKit.hash(salt + 12, x, z) > 0.45) {
                    canvas.set(x, ROOF, z, BlockSpec.AIR);
                }
            }
        }
        // Two steps up to each standing turret top.
        turretSteps(6, 1);
        turretSteps(-6, 1);
    }

    /** Two stairs on the roof along {@code x}, climbing towards the turret at the {@code side} (1 south) end. */
    private void turretSteps(int x, int side) {
        BlockFace facing = side > 0 ? BlockFace.SOUTH : BlockFace.NORTH;
        canvas.set(x, ROOF + 1, side * (HALF - 3), Blocks.stairs(Material.STONE_BRICK_STAIRS, facing));
        canvas.set(x, ROOF + 1, side * (HALF - 2), KeepKit.masonry(random));
        canvas.set(x, ROOF + 2, side * (HALF - 2), Blocks.stairs(Material.STONE_BRICK_STAIRS, facing));
    }

    /**
     * Ivy hanging down the outer walls from the parapet. Each strand's top is level with the parapet's top, so it
     * never makes a ledge to be stranded on, and it ends high enough to walk under; it leaves windows clear.
     */
    private void ivy() {
        for (int a = -HALF + 1; a <= HALF - 1; a++) {
            for (BlockFace face : KeepKit.sides()) {
                int x = face.getModX() != 0 ? face.getModX() * (HALF + 1) : a;
                int z = face.getModZ() != 0 ? face.getModZ() * (HALF + 1) : a;
                double n = KeepKit.noise(salt + 20, x * 0.5 + z * 0.3, z * 0.5 - x * 0.3);
                double strand = KeepKit.hash(salt + 21, x, z);
                if (n < 0.6 || strand < 0.15) {
                    continue;
                }
                int bottom = ROOF - 1 - (int) ((n - 0.6) * 14 + strand * 4.5);
                boolean clear = true;
                for (int y = FLOOR; y <= ROOF + 3 && clear; y++) {
                    clear = KeepKit.isFree(canvas, x, y, z)
                            && (y < bottom || y > ROOF + 1 || !KeepKit.isFree(canvas, x - face.getModX(), y,
                            z - face.getModZ()));
                }
                if (!clear) {
                    continue;
                }
                Material leaves = n > 0.9 ? Material.FLOWERING_AZALEA_LEAVES
                        : (a & 1) == 0 ? Material.AZALEA_LEAVES : Material.OAK_LEAVES;
                for (int y = bottom; y <= ROOF + 1; y++) {
                    canvas.set(x, y, z, Blocks.leaves(leaves));
                }
            }
        }
    }

    // ------------------------------------------------------------------ furnishing

    private void furnish() {
        // Ground floor pillars carrying the beams.
        for (int[] p : new int[][] {{3, -3}, {3, 3}, {-3, 3}}) {
            for (int y = FLOOR; y < F2; y++) {
                canvas.set(p[0], y, p[1], BlockSpec.of(y == FLOOR ? Material.CHISELED_STONE_BRICKS
                        : y == F2 - 1 ? Material.POLISHED_ANDESITE : Material.STONE_BRICKS));
            }
        }
        // A broken pillar lies in the rubble.
        canvas.set(-1, FLOOR, -3, Blocks.log(Material.POLISHED_BASALT, Axis.X));
        canvas.set(0, FLOOR, -3, Blocks.log(Material.POLISHED_BASALT, Axis.X));
        // The throne on its dais, against the east part of the south wall.
        for (int x = 1; x <= 4; x++) {
            canvas.set(x, FLOOR, 6, Blocks.slab(Material.POLISHED_ANDESITE_SLAB));
        }
        canvas.set(2, FLOOR + 1, 6, Blocks.stairs(Material.DARK_OAK_STAIRS, BlockFace.SOUTH));
        canvas.set(3, FLOOR + 1, 6, Blocks.stairs(Material.DARK_OAK_STAIRS, BlockFace.SOUTH));
        canvas.set(1, FLOOR + 1, 6, BlockSpec.of(Material.BARREL).facing(BlockFace.UP));
        // Barrels and crates by the doors.
        canvas.set(5, FLOOR, -5, BlockSpec.of(Material.BARREL).facing(BlockFace.UP));
        canvas.set(5, FLOOR, -4, BlockSpec.of(Material.BARREL).facing(BlockFace.NORTH));
        canvas.set(-5, FLOOR, 3, BlockSpec.of(Material.SPRUCE_PLANKS));
        canvas.set(-5, FLOOR + 1, 3, Blocks.trapdoor(Material.SPRUCE_TRAPDOOR, BlockFace.NORTH, false, false));
        // First floor: a cold hearth on the west wall, a lantern under the beams, a long table.
        canvas.set(-6, F2 + 1, 0, Blocks.campfire(false));
        canvas.set(-6, F2 + 1, -1, Blocks.stairs(Material.STONE_BRICK_STAIRS, BlockFace.SOUTH));
        canvas.set(-6, F2 + 1, 1, Blocks.stairs(Material.STONE_BRICK_STAIRS, BlockFace.NORTH));
        canvas.set(-6, F2 + 2, -1, BlockSpec.of(Material.STONE_BRICKS));
        canvas.set(-6, F2 + 2, 1, BlockSpec.of(Material.STONE_BRICKS));
        canvas.set(-6, F2 + 3, -1, Blocks.stairsTop(Material.STONE_BRICK_STAIRS, BlockFace.NORTH));
        canvas.set(-6, F2 + 3, 1, Blocks.stairsTop(Material.STONE_BRICK_STAIRS, BlockFace.SOUTH));
        canvas.set(-6, F2 + 3, 0, BlockSpec.of(Material.STONE_BRICKS));
        canvas.set(0, F3 - 1, 0, Blocks.lantern(false, true));
        canvas.set(2, F3 - 1, 3, Blocks.lantern(true, true));
        for (int x = 0; x <= 3; x++) {
            canvas.set(x, F2 + 1, 1, Blocks.slabTop(Material.SPRUCE_SLAB));
        }
        canvas.set(4, F2 + 1, 4, BlockSpec.of(Material.BOOKSHELF));
        canvas.set(4, F2 + 2, 4, Blocks.slab(Material.SPRUCE_SLAB));
        canvas.set(-5, F2 + 1, 4, BlockSpec.of(Material.CHISELED_BOOKSHELF).facing(BlockFace.EAST));
        // Second floor: an armoury's leftovers.
        canvas.set(-5, F3 + 1, -5, BlockSpec.of(Material.BARREL).facing(BlockFace.UP));
        canvas.set(-4, F3 + 1, -5, BlockSpec.of(Material.SMITHING_TABLE));
        canvas.set(-5, F3 + 1, 5, BlockSpec.of(Material.GRINDSTONE).facing(BlockFace.NORTH));
        canvas.set(0, ROOF - 1, 0, Blocks.lantern(false, true));
        // A roof beam fell across the broken north-east corner and sticks out over the drop.
        for (int x = 4; x <= HALF + 2; x++) {
            KeepKit.putIfFree(canvas, x, F3 + 1, -6, Blocks.log(Material.DARK_OAK_LOG, Axis.X));
        }
        // Candles on the table and the dais, cobwebs high in the corners, an old skull by the throne.
        canvas.set(0, F2 + 2, 1, BlockSpec.of(Material.CANDLE).lit(true));
        canvas.set(3, F2 + 2, 1, BlockSpec.of(Material.CANDLE).lit(true));
        canvas.set(4, FLOOR + 1, 6, BlockSpec.of(Material.WHITE_CANDLE).lit(true));
        canvas.set(4, FLOOR, 5, BlockSpec.of(Material.SKELETON_SKULL));
        for (int[] c : new int[][] {{-5, F3 - 1, 4}, {6, F3 - 1, 3}, {-6, ROOF - 1, -5}, {-5, ROOF - 1, 6},
                {6, F2 - 1, -1}}) {
            KeepKit.putIfFree(canvas, c[0], c[1], c[2], BlockSpec.of(Material.COBWEB));
        }
        // Roof: lanterns on the battlements.
        for (int[] p : new int[][] {{-HALF, -1}, {HALF, 3}, {-1, HALF}}) {
            canvas.set(p[0], ROOF + 2, p[1], KeepKit.masonry(random));
            canvas.set(p[0], ROOF + 3, p[1], Blocks.lantern(false, false));
        }
    }
}
