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
 * The courtyard between the keep tower and the bailey: an old well, knight statues by the east road, a broken
 * colonnade on the north side, the smithy's ruin, a supply corner under a sagging canopy, a gnarled old tree, rubble
 * heaps and low wall stubs for cover, lantern posts, and grass, flowers and moss everywhere the stone lets them.
 */
final class KeepCourtyard {

    private static final Material[] FLOWERS = {Material.POPPY, Material.DANDELION, Material.OXEYE_DAISY,
            Material.CORNFLOWER, Material.AZURE_BLUET, Material.ALLIUM, Material.LILY_OF_THE_VALLEY};
    private static final Material[] BUSH_LEAVES = {Material.AZALEA_LEAVES, Material.AZALEA_LEAVES,
            Material.FLOWERING_AZALEA_LEAVES, Material.OAK_LEAVES, Material.DARK_OAK_LEAVES};

    private final MapCanvas canvas;
    private final Random random;
    private final int salt;

    KeepCourtyard(MapCanvas canvas, Random random) {
        this.canvas = canvas;
        this.random = random;
        this.salt = random.nextInt();
    }

    void build() {
        rubble();
        well(-13, 11);
        statue(14, -5, BlockFace.SOUTH, false);
        statue(14, 5, BlockFace.NORTH, true);
        fallenStatue(-14, -5);
        colonnade();
        smithy(-13, -12);
        depot(-4, 14);
        oldTree(13, 13);
        wallStubs();
        lanternPosts();
        bushes();
        plants();
    }

    // ------------------------------------------------------------------ the well

    /** A round-ish stone well with water three deep, a broken rim and a little roof on two posts. */
    private void well(int cx, int cz) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                int x = cx + dx;
                int z = cz + dz;
                boolean rim = Math.abs(dx) == 2 || Math.abs(dz) == 2;
                boolean corner = Math.abs(dx) == 2 && Math.abs(dz) == 2;
                if (corner) {
                    canvas.set(x, GROUND, z, BlockSpec.of(Material.MOSSY_COBBLESTONE));
                    continue;
                }
                if (rim) {
                    for (int y = GROUND - 3; y <= GROUND; y++) {
                        canvas.set(x, y, z, BlockSpec.of(Material.MOSSY_STONE_BRICKS));
                    }
                    boolean gap = dx == 0 && dz == 2 || dx == 1 && dz == 2;
                    if (gap) {
                        KeepKit.clear(canvas, x, FLOOR, z, FLOOR + 2); // the way out of the water
                    } else {
                        canvas.set(x, FLOOR, z, random.nextInt(3) == 0 ? BlockSpec.of(Material.MOSSY_STONE_BRICKS)
                                : BlockSpec.of(Material.STONE_BRICKS));
                    }
                } else {
                    canvas.set(x, GROUND - 3, z, BlockSpec.of(Material.MOSSY_COBBLESTONE));
                    for (int y = GROUND - 2; y <= GROUND; y++) {
                        canvas.set(x, y, z, BlockSpec.of(Material.WATER));
                    }
                }
            }
        }
        // Two posts and a beam with a lantern on a chain.
        for (int dx = -2; dx <= 2; dx += 4) {
            canvas.set(cx + dx, FLOOR + 1, cz, BlockSpec.of(Material.SPRUCE_FENCE));
            canvas.set(cx + dx, FLOOR + 2, cz, BlockSpec.of(Material.SPRUCE_FENCE));
        }
        for (int dx = -2; dx <= 2; dx++) {
            canvas.set(cx + dx, FLOOR + 3, cz, Blocks.log(Material.STRIPPED_SPRUCE_LOG, Axis.X));
            canvas.set(cx + dx, FLOOR + 4, cz - 1, Blocks.stairs(Material.SPRUCE_STAIRS, BlockFace.SOUTH));
            canvas.set(cx + dx, FLOOR + 4, cz + 1, Blocks.stairs(Material.SPRUCE_STAIRS, BlockFace.NORTH));
            canvas.set(cx + dx, FLOOR + 4, cz, Blocks.slab(Material.SPRUCE_SLAB));
        }
        canvas.set(cx, FLOOR + 2, cz, Blocks.log(Material.IRON_CHAIN, Axis.Y));
        canvas.set(cx, FLOOR + 1, cz, Blocks.lantern(false, true));
        canvas.set(cx - 1, FLOOR + 1, cz - 2, BlockSpec.of(Material.WATER_CAULDRON));
        canvas.set(cx + 2, FLOOR, cz + 2, BlockSpec.of(Material.BARREL).facing(BlockFace.UP));
    }

    // ------------------------------------------------------------------ statues

    /**
     * A knight on a pedestal, facing {@code facing}, his sword point down before him. A {@code broken} one lost his
     * head and an arm, which lie at his feet.
     */
    private void statue(int cx, int cz, BlockFace facing, boolean broken) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                boolean corner = dx != 0 && dz != 0;
                canvas.set(cx + dx, FLOOR, cz + dz, corner ? BlockSpec.of(Material.CHISELED_STONE_BRICKS)
                        : BlockSpec.of(Material.POLISHED_ANDESITE));
            }
        }
        BlockFace left = rotateLeft(facing);
        canvas.set(cx, FLOOR + 1, cz, BlockSpec.of(Material.POLISHED_ANDESITE));
        canvas.set(cx, FLOOR + 2, cz, BlockSpec.of(Material.POLISHED_ANDESITE));
        canvas.set(cx, FLOOR + 3, cz, BlockSpec.of(Material.POLISHED_ANDESITE));
        canvas.set(cx + left.getModX(), FLOOR + 3, cz + left.getModZ(),
                Blocks.stairsTop(Material.POLISHED_ANDESITE_STAIRS, left.getOppositeFace()));
        if (!broken) {
            canvas.set(cx - left.getModX(), FLOOR + 3, cz - left.getModZ(),
                    Blocks.stairsTop(Material.POLISHED_ANDESITE_STAIRS, left));
            canvas.set(cx, FLOOR + 4, cz, BlockSpec.of(Material.CHISELED_STONE_BRICKS));
            canvas.set(cx, FLOOR + 5, cz, Blocks.slab(Material.STONE_BRICK_SLAB));
        }
        // The sword, point down on the pedestal in front of him.
        int sx = cx + facing.getModX();
        int sz = cz + facing.getModZ();
        canvas.set(sx, FLOOR + 1, sz, BlockSpec.of(Material.IRON_BARS));
        canvas.set(sx, FLOOR + 2, sz, BlockSpec.of(Material.IRON_BARS));
        canvas.set(sx, FLOOR + 3, sz, Blocks.slab(Material.POLISHED_ANDESITE_SLAB));
        if (broken) {
            int hx = cx - facing.getModX() * 2 + left.getModX();
            int hz = cz - facing.getModZ() * 2 + left.getModZ();
            KeepKit.putIfFree(canvas, hx, FLOOR, hz, BlockSpec.of(Material.CHISELED_STONE_BRICKS));
            KeepKit.putIfFree(canvas, hx - left.getModX() * 2, FLOOR, hz - left.getModZ() * 2,
                    Blocks.stairs(Material.POLISHED_ANDESITE_STAIRS, left));
        }
    }

    /** A statue toppled from its pedestal north of the west road, lying in pieces. */
    private void fallenStatue(int cx, int cz) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                canvas.set(cx + dx, FLOOR, cz + dz, BlockSpec.of(dx != 0 && dz != 0 ? Material.CHISELED_STONE_BRICKS
                        : Material.POLISHED_ANDESITE));
            }
        }
        canvas.set(cx, FLOOR + 1, cz, Blocks.slab(Material.POLISHED_ANDESITE_SLAB)); // the broken feet
        // The body lies towards the east.
        canvas.set(cx + 2, FLOOR, cz - 2, Blocks.log(Material.POLISHED_BASALT, Axis.X));
        canvas.set(cx + 3, FLOOR, cz - 2, BlockSpec.of(Material.POLISHED_ANDESITE));
        canvas.set(cx + 3, FLOOR, cz - 3, Blocks.stairs(Material.POLISHED_ANDESITE_STAIRS, BlockFace.NORTH));
        canvas.set(cx + 5, FLOOR, cz - 2, BlockSpec.of(Material.CHISELED_STONE_BRICKS));
    }

    private static BlockFace rotateLeft(BlockFace face) {
        return switch (face) {
            case NORTH -> BlockFace.WEST;
            case WEST -> BlockFace.SOUTH;
            case SOUTH -> BlockFace.EAST;
            default -> BlockFace.NORTH;
        };
    }

    // ------------------------------------------------------------------ the colonnade

    /**
     * A broken arcade of tuff columns on an arc north of the keep: some still carry a stretch of lintel, some
     * are stumps, one lies across the grass.
     */
    private void colonnade() {
        int[] heights = {5, 3, 5, 5, 2, 4};
        int[][] spots = new int[heights.length][];
        for (int i = 0; i < heights.length; i++) {
            int[] p = CitadelLayout.polar(17.5, 249 + i * 8.4);
            spots[i] = p;
            int x = p[0];
            int z = p[1];
            canvas.set(x, FLOOR, z, BlockSpec.of(Material.CHISELED_TUFF_BRICKS));
            for (int y = FLOOR + 1; y < FLOOR + heights[i]; y++) {
                canvas.set(x, y, z, BlockSpec.of(Material.POLISHED_TUFF));
            }
            canvas.set(x, FLOOR + heights[i], z, BlockSpec.of(heights[i] >= 5 ? Material.CHISELED_TUFF
                    : Material.POLISHED_TUFF));
            // Plinth steps round the base.
            for (BlockFace face : KeepKit.sides()) {
                if (random.nextInt(3) > 0) {
                    KeepKit.putIfFree(canvas, x + face.getModX(), FLOOR, z + face.getModZ(),
                            Blocks.slab(Material.POLISHED_TUFF_SLAB));
                }
            }
        }
        // Lintels over the first and the third pair.
        lintel(spots[0], spots[1], FLOOR + 5);
        lintel(spots[2], spots[3], FLOOR + 5);
        // A fallen column across the grass.
        int[] p = CitadelLayout.polar(21.5, 262);
        for (int i = 0; i < 4; i++) {
            KeepKit.putIfFree(canvas, p[0] + i, FLOOR, p[1], Blocks.log(Material.POLISHED_BASALT, Axis.X));
        }
        KeepKit.putIfFree(canvas, p[0] + 4, FLOOR, p[1], BlockSpec.of(Material.CHISELED_TUFF));
    }

    private void lintel(int[] a, int[] b, int y) {
        int steps = Math.max(Math.abs(b[0] - a[0]), Math.abs(b[1] - a[1]));
        for (int i = 0; i <= steps; i++) {
            int x = (int) Math.round(a[0] + (b[0] - a[0]) * (double) i / steps);
            int z = (int) Math.round(a[1] + (b[1] - a[1]) * (double) i / steps);
            canvas.set(x, y, z, BlockSpec.of(i == 0 || i == steps ? Material.CHISELED_TUFF : Material.TUFF_BRICKS));
            if (i > 0 && i < steps) {
                KeepKit.putIfFree(canvas, x, y + 1, z, Blocks.slab(Material.TUFF_BRICK_SLAB));
            }
        }
    }

    // ------------------------------------------------------------------ the smithy

    /** The smithy's ruin: knee-high walls with gaps, a cold forge, an anvil, a quench tub, a fallen beam. */
    private void smithy(int cx, int cz) {
        int x1 = cx - 3;
        int x2 = cx + 3;
        int z1 = cz - 3;
        int z2 = cz + 2;
        for (int x = x1; x <= x2; x++) {
            for (int z = z1; z <= z2; z++) {
                boolean edge = x == x1 || x == x2 || z == z1 || z == z2;
                if (!edge) {
                    canvas.set(x, GROUND, z, random.nextInt(3) == 0 ? BlockSpec.of(Material.COARSE_DIRT)
                            : KeepKit.paving(random));
                    continue;
                }
                double n = KeepKit.noise(salt + 1, x * 0.7, z * 0.7);
                boolean doorway = z == z2 && (x == cx || x == cx + 1) || x == x2 && (z == cz - 1 || z == cz);
                if (doorway || n < 0.3) {
                    continue;
                }
                int height = 1 + (int) (n * 2.6);
                for (int y = FLOOR; y < FLOOR + height; y++) {
                    canvas.set(x, y, z, y == FLOOR ? KeepKit.footing(random) : KeepKit.masonry(random));
                }
                if (random.nextInt(3) == 0) {
                    canvas.set(x, FLOOR + height, z, Blocks.slab(KeepKit.slab(random)));
                }
            }
        }
        // Forge against the back wall.
        canvas.set(cx - 2, FLOOR, z1 + 1, BlockSpec.of(Material.BLAST_FURNACE).facing(BlockFace.SOUTH));
        canvas.set(cx - 1, FLOOR, z1 + 1, Blocks.campfire(false));
        canvas.set(cx - 1, FLOOR + 1, z1 + 1, BlockSpec.of(Material.IRON_BARS));
        canvas.set(cx, FLOOR, z1 + 1, BlockSpec.of(Material.SMOKER).facing(BlockFace.SOUTH));
        canvas.set(cx + 1, FLOOR, cz, BlockSpec.of(Material.CHIPPED_ANVIL).facing(BlockFace.EAST));
        canvas.set(cx - 2, FLOOR, cz + 1, BlockSpec.of(Material.WATER_CAULDRON));
        canvas.set(cx - 1, FLOOR, cz + 1, BlockSpec.of(Material.GRINDSTONE).facing(BlockFace.NORTH));
        // A roof beam fell across the room; the chimney stands to the north-west.
        for (int x = cx - 1; x <= cx + 2; x++) {
            KeepKit.putIfFree(canvas, x, FLOOR, z1 + 2 + (x - cx + 1) / 2, Blocks.log(Material.DARK_OAK_LOG, Axis.X));
        }
        for (int y = FLOOR; y <= FLOOR + 4; y++) {
            canvas.set(x1, y, z1, y >= FLOOR + 3 ? BlockSpec.of(Material.BRICKS) : KeepKit.masonry(random));
        }
    }

    // ------------------------------------------------------------------ the supply corner

    /** Crates, barrels and hay under a sagging canopy south of the keep; a crate stair climbs onto its roof. */
    private void depot(int x0, int z0) {
        int x1 = x0 + 5;
        int z1 = z0 + 3;
        int roof = FLOOR + 4;
        for (int[] post : new int[][] {{x0, z0}, {x1, z0}, {x0, z1}, {x1, z1}}) {
            for (int y = FLOOR; y < roof; y++) {
                canvas.set(post[0], y, post[1], BlockSpec.of(Material.SPRUCE_FENCE));
            }
        }
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                boolean sagging = x == x1 && z == z1 || x == x1 - 1 && z == z1;
                if (sagging) {
                    continue;
                }
                canvas.set(x, roof, z, Blocks.slab(x == x0 || z == z0 ? Material.DARK_OAK_SLAB
                        : Material.SPRUCE_SLAB));
            }
        }
        canvas.set(x1 - 1, roof - 1, z1, Blocks.stairsTop(Material.SPRUCE_STAIRS, BlockFace.WEST));
        canvas.set(x0 + 2, roof - 1, z0 + 1, Blocks.lantern(false, true));
        // Goods under the canopy.
        canvas.set(x0 + 1, FLOOR, z0 + 2, BlockSpec.of(Material.BARREL).facing(BlockFace.UP));
        canvas.set(x0 + 1, FLOOR, z0 + 1, BlockSpec.of(Material.BARREL).facing(BlockFace.EAST));
        canvas.set(x0 + 4, FLOOR, z0 + 2, crate());
        canvas.set(x0 + 4, FLOOR, z0 + 1, BlockSpec.of(Material.HAY_BLOCK));
        // The crate stair on the west side: one, two, three, four high, then onto the canopy.
        for (int step = 1; step <= 4; step++) {
            int z = z0 + 4 - step;
            for (int y = FLOOR; y < FLOOR + step; y++) {
                boolean topmost = y == FLOOR + step - 1;
                canvas.set(x0 - 1, y, z, topmost && step % 2 == 0 ? BlockSpec.of(Material.HAY_BLOCK)
                        : topmost && step == 3 ? BlockSpec.of(Material.BARREL).facing(BlockFace.UP) : crate());
            }
        }
        canvas.set(x0 - 1, FLOOR, z0 + 4, Blocks.slab(Material.SPRUCE_SLAB));
        // Spilled hay and a lone barrel outside.
        canvas.set(x1 + 1, FLOOR, z0, BlockSpec.of(Material.HAY_BLOCK));
        canvas.set(x1 + 2, FLOOR, z0 + 1, BlockSpec.of(Material.BARREL).facing(BlockFace.WEST));
        canvas.set(x1 + 1, FLOOR, z1 + 1, Blocks.slab(Material.SPRUCE_SLAB));
    }

    private BlockSpec crate() {
        return BlockSpec.of(random.nextInt(3) == 0 ? Material.STRIPPED_SPRUCE_WOOD : Material.SPRUCE_PLANKS);
    }

    // ------------------------------------------------------------------ the old tree

    /** A gnarled dark oak in the south-east yard, its roots heaving the ground, its crown high over the yard. */
    private void oldTree(int cx, int cz) {
        for (int dx = 0; dx <= 1; dx++) {
            for (int dz = 0; dz <= 1; dz++) {
                for (int y = FLOOR; y <= FLOOR + 5; y++) {
                    canvas.set(cx + dx, y, cz + dz, Blocks.log(Material.DARK_OAK_LOG, Axis.Y));
                }
            }
        }
        // Roots.
        canvas.set(cx - 1, FLOOR, cz, Blocks.log(Material.DARK_OAK_LOG, Axis.X));
        canvas.set(cx + 2, FLOOR, cz + 1, Blocks.log(Material.DARK_OAK_LOG, Axis.X));
        canvas.set(cx + 1, FLOOR, cz - 1, Blocks.log(Material.DARK_OAK_LOG, Axis.Z));
        canvas.set(cx, FLOOR, cz + 2, Blocks.log(Material.DARK_OAK_LOG, Axis.Z));
        for (int dx = -3; dx <= 4; dx++) {
            for (int dz = -3; dz <= 4; dz++) {
                if (KeepKit.hash(salt + 5, cx + dx, cz + dz) > 0.55 && KeepKit.isFree(canvas, cx + dx, FLOOR, cz + dz)) {
                    canvas.set(cx + dx, GROUND, cz + dz, BlockSpec.of(Material.ROOTED_DIRT));
                }
            }
        }
        // Branches.
        int top = FLOOR + 5;
        canvas.set(cx - 1, top, cz, Blocks.log(Material.DARK_OAK_LOG, Axis.X));
        canvas.set(cx - 2, top + 1, cz, Blocks.log(Material.DARK_OAK_LOG, Axis.X));
        canvas.set(cx + 2, top, cz + 1, Blocks.log(Material.DARK_OAK_LOG, Axis.X));
        canvas.set(cx + 3, top + 1, cz + 1, Blocks.log(Material.DARK_OAK_LOG, Axis.X));
        canvas.set(cx + 1, top, cz + 2, Blocks.log(Material.DARK_OAK_LOG, Axis.Z));
        canvas.set(cx + 1, top + 1, cz + 3, Blocks.log(Material.DARK_OAK_LOG, Axis.Z));
        canvas.set(cx, top + 1, cz - 1, Blocks.log(Material.DARK_OAK_LOG, Axis.Z));
        canvas.set(cx, top + 1, cz, Blocks.log(Material.DARK_OAK_LOG, Axis.Y));
        canvas.set(cx + 1, top + 1, cz + 1, Blocks.log(Material.DARK_OAK_LOG, Axis.Y));
        // The crown: a flat, lumpy dome of leaves.
        double ccx = cx + 0.5;
        double ccz = cz + 0.5;
        for (int dx = -5; dx <= 6; dx++) {
            for (int dz = -5; dz <= 6; dz++) {
                for (int dy = 0; dy <= 3; dy++) {
                    double rx = (cx + dx - ccx) / 4.1;
                    double rz = (cz + dz - ccz) / 4.1;
                    double ry = (dy - 1.0) / 2.3;
                    double n = KeepKit.noise(salt + 6, (cx + dx) * 0.5 + dy * 3.1, (cz + dz) * 0.5);
                    if (rx * rx + ry * ry + rz * rz <= 0.75 + n * 0.45) {
                        int x = cx + dx;
                        int z = cz + dz;
                        int y = top + 1 + dy;
                        Material leaves = n > 0.78 ? Material.FLOWERING_AZALEA_LEAVES
                                : n > 0.6 ? Material.AZALEA_LEAVES : Material.DARK_OAK_LEAVES;
                        KeepKit.putIfFree(canvas, x, y, z, Blocks.leaves(leaves));
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ cover

    /** Heaps of fallen stone through the courtyard: cover every ten blocks or so. */
    private void rubble() {
        double[][] heaps = {
                {19, 18, 2.6, 1.6}, {20, 42, 2.2, 1.4}, {17, 68, 2.2, 1.4}, {21, 141, 1.6, 1.0},
                {17, 197, 2.4, 1.6}, {19, 208, 1.8, 1.0}, {14, 228, 1.8, 1.1}, {20, 318, 2.4, 1.6},
                {14, 330, 2.2, 1.4}, {21, 345, 1.6, 1.0}, {13, 112, 1.6, 0.9}, {22, 278, 1.8, 1.2}};
        KeepKit.Heap heap = new KeepKit.Heap();
        for (int i = 0; i < heaps.length; i++) {
            double[] h = heaps[i];
            double[] p = KeepKit.point(h[0], h[1]);
            heap.mound(salt + 20 + i, p[0], p[1], h[2], h[3]);
        }
        heap.place(canvas, random, true);
    }

    /** Low stubs of the inner buildings' walls, knee to chest high, in short broken runs. */
    private void wallStubs() {
        int[][] runs = {
                {8, -16, 13, -16}, {13, -16, 13, -13},       // north-east: an outbuilding's corner
                {-20, 2, -20, 6}, {-21, -5, -18, -5},        // west, either side of the road
                {16, 9, 18, 9}};                              // south-east
        for (int[] run : runs) {
            int steps = Math.max(Math.abs(run[2] - run[0]), Math.abs(run[3] - run[1]));
            for (int i = 0; i <= steps; i++) {
                int x = (int) Math.round(run[0] + (run[2] - run[0]) * (double) i / steps);
                int z = (int) Math.round(run[1] + (run[3] - run[1]) * (double) i / steps);
                if (!KeepKit.isFree(canvas, x, FLOOR, z)) {
                    continue;
                }
                int height = 1 + (int) (KeepKit.hash(salt + 30, x, z) * 2.4);
                for (int y = FLOOR; y < FLOOR + height; y++) {
                    canvas.set(x, y, z, y == FLOOR ? KeepKit.footing(random) : KeepKit.masonry(random));
                }
                if (random.nextBoolean()) {
                    canvas.set(x, FLOOR + height, z, KeepKit.coping(random));
                }
            }
        }
    }

    private void lanternPosts() {
        int[][] posts = {{10, -3}, {10, 3}, {-10, -3}, {-10, 3}, {-9, 14}, {17, 0}, {-17, 0}};
        for (int[] p : posts) {
            if (!KeepKit.isFree(canvas, p[0], FLOOR, p[1])) {
                continue;
            }
            KeepKit.lanternPost(canvas, p[0], FLOOR, p[1]);
        }
    }

    // ------------------------------------------------------------------ plants

    /** Bushes along the foot of the walls and here and there in the yard: the courtyard is going wild. */
    private void bushes() {
        int count = 30;
        for (int i = 0; i < count; i++) {
            double angle = i * (360.0 / count) + random.nextDouble() * 9;
            int[] p = CitadelLayout.polar(23.2 + random.nextDouble() * 1.4, angle);
            bush(p[0], p[1]);
        }
        for (int[] p : new int[][] {{-8, 18}, {17, -6}, {-18, -8}, {6, -18}, {-17, 16}, {19, 4}}) {
            bush(p[0], p[1]);
        }
    }

    /** A clump of leaves, one block high round a taller middle (so it can be climbed). */
    private void bush(int x, int z) {
        if (!isSoil(x, z) || !KeepKit.isFree(canvas, x, FLOOR, z) || !KeepKit.isFree(canvas, x, FLOOR + 1, z)) {
            return;
        }
        Material leaves = BUSH_LEAVES[random.nextInt(BUSH_LEAVES.length)];
        canvas.set(x, FLOOR, z, Blocks.leaves(leaves));
        int around = 0;
        for (BlockFace face : KeepKit.sides()) {
            int nx = x + face.getModX();
            int nz = z + face.getModZ();
            if (random.nextInt(3) > 0 && isSoil(nx, nz) && KeepKit.isFree(canvas, nx, FLOOR, nz)
                    && KeepKit.isFree(canvas, nx, FLOOR + 1, nz)) {
                canvas.set(nx, FLOOR, nz, Blocks.leaves(random.nextInt(3) == 0 ? Material.AZALEA_LEAVES : leaves));
                around++;
            }
        }
        if (around >= 2 && random.nextBoolean()) {
            canvas.set(x, FLOOR + 1, z, Blocks.leaves(leaves));
        }
    }

    private boolean isSoil(int x, int z) {
        if (!canvas.canWrite(x, z)) {
            return false;
        }
        BlockSpec ground = canvas.get(x, GROUND, z);
        if (ground == null) {
            return false;
        }
        Material m = ground.material();
        return m == Material.GRASS_BLOCK || m == Material.MOSS_BLOCK || m == Material.PODZOL
                || m == Material.ROOTED_DIRT || m == Material.COARSE_DIRT;
    }

    /** Grass, ferns, flowers and bushes on the soil; moss carpets on moss. */
    private void plants() {
        int r = CitadelLayout.KEEP_RADIUS;
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                if (!canvas.canWrite(x, z) || !KeepKit.isFree(canvas, x, FLOOR, z)
                        || !KeepKit.isFree(canvas, x, FLOOR + 1, z)) {
                    continue;
                }
                BlockSpec ground = canvas.get(x, GROUND, z);
                if (ground == null) {
                    continue;
                }
                Material soil = ground.material();
                double n = KeepKit.noise(salt + 40, x * 0.25, z * 0.25);
                int roll = random.nextInt(1000);
                if (soil == Material.MOSS_BLOCK) {
                    if (roll < 450) {
                        canvas.set(x, FLOOR, z, BlockSpec.of(Material.MOSS_CARPET));
                    } else if (roll < 520) {
                        canvas.set(x, FLOOR, z, BlockSpec.of(Material.SHORT_GRASS));
                    }
                } else if (soil == Material.GRASS_BLOCK || soil == Material.PODZOL || soil == Material.ROOTED_DIRT) {
                    int density = (int) (120 + n * 260);
                    if (roll < density * 0.62) {
                        canvas.set(x, FLOOR, z, BlockSpec.of(Material.SHORT_GRASS));
                    } else if (roll < density * 0.72) {
                        Blocks.tallPlant(canvas, x, FLOOR, z, Material.TALL_GRASS);
                    } else if (roll < density * 0.8) {
                        canvas.set(x, FLOOR, z, BlockSpec.of(Material.FERN));
                    } else if (roll < density * 0.84) {
                        Blocks.tallPlant(canvas, x, FLOOR, z, Material.LARGE_FERN);
                    } else if (roll < density * 0.93) {
                        canvas.set(x, FLOOR, z, BlockSpec.of(FLOWERS[random.nextInt(FLOWERS.length)]));
                    } else if (roll < density) {
                        canvas.set(x, FLOOR, z, BlockSpec.of(random.nextBoolean() ? Material.BUSH
                                : Material.LEAF_LITTER));
                    }
                } else if (soil == Material.COARSE_DIRT && roll < 40) {
                    canvas.set(x, FLOOR, z, BlockSpec.of(Material.DEAD_BUSH));
                }
            }
        }
    }
}
