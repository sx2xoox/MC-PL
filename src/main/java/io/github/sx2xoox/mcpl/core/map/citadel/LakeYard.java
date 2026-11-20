package io.github.sx2xoox.mcpl.core.map.citadel;

import java.util.Random;

import io.github.sx2xoox.mcpl.core.map.BlockSpec;
import io.github.sx2xoox.mcpl.core.map.Blocks;
import io.github.sx2xoox.mcpl.core.map.MapCanvas;
import org.bukkit.Axis;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Slab;

/**
 * The mill's yard and the district's paths: a roofless granary, an overgrown kitchen garden, a covered well, hay
 * stacks, a log pile and a broken cart (all chest-high cover around the mill), and trodden paths from the roads to the
 * bridges, the mill and the piers.
 */
final class LakeYard {

    private static final int GROUND = LakeTerrain.GROUND_Y;

    // The roofless granary north of the mill.
    static final int GRANARY_X1 = -50;
    static final int GRANARY_Z1 = 7;
    static final int GRANARY_X2 = -44;
    static final int GRANARY_Z2 = 12;
    // The kitchen garden between the granary and the lake.
    static final int GARDEN_X1 = -55;
    static final int GARDEN_Z1 = 14;
    static final int GARDEN_X2 = -49;
    static final int GARDEN_Z2 = 18;

    /** Paths as polylines of {x, z} points (from a road to a destination). */
    private static final int[][][] PATHS = {
            {{-70, 2}, {-70, 13}},                          // north road -> arch bridge
            {{-78, 2}, {-78, 11}},                          // north road -> boathouse
            {{-88, 2}, {-88, 8}},                           // north road -> lookout ladder
            {{-31, 17}, {-37, 19}, {-41, 20}},              // ring road -> mill doors
            {{-41, 13}, {-41, 20}},                         // granary -> mill
            {{-30, 50}, {-38, 45}, {-44, 44}},              // south-east road -> the walk over the islet
            {{-43, 72}, {-48, 74}, {-52, 74}},              // south-east road -> south pier
            {{-71, 61}, {-70, 56}, {-70, 51}, {-71, 47}},   // willow walk -> fishers' cottage
            {{-33, 34}, {-38, 37}, {-44, 39}},              // ring road -> mill yard south
    };

    private final MapCanvas canvas;
    private final LakeTerrain terrain;
    private final LakeNoise noise;
    private final Random random;

    LakeYard(MapCanvas canvas, LakeTerrain terrain, LakeNoise noise, Random random) {
        this.canvas = canvas;
        this.terrain = terrain;
        this.noise = noise;
        this.random = random;
    }

    static void plan(LakeTerrain terrain) {
        terrain.flatten(GRANARY_X1 - 1, GRANARY_Z1 - 1, GRANARY_X2 + 1, GRANARY_Z2 + 1, GROUND);
        terrain.reserve(GRANARY_X1 - 2, GRANARY_Z1 - 2, GRANARY_X2 + 2, GRANARY_Z2 + 2);
        terrain.flatten(GARDEN_X1, GARDEN_Z1, GARDEN_X2, GARDEN_Z2, GROUND);
        terrain.reserve(GARDEN_X1 - 1, GARDEN_Z1 - 1, GARDEN_X2 + 1, GARDEN_Z2 + 1);
    }

    /** Paths first, so the buildings and bridges can sit on them. */
    void paths() {
        for (int[][] path : PATHS) {
            for (int i = 0; i + 1 < path.length; i++) {
                stroke(path[i][0], path[i][1], path[i + 1][0], path[i + 1][1]);
            }
        }
    }

    private void stroke(int x1, int z1, int x2, int z2) {
        double length = Math.hypot(x2 - x1, z2 - z1);
        int steps = (int) Math.ceil(length * 2);
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            double px = x1 + (x2 - x1) * t;
            double pz = z1 + (z2 - z1) * t;
            double width = 1.0 + 0.45 * noise.at(px, pz, 4, 1);
            int r = (int) Math.ceil(width);
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (dx * dx + dz * dz > width * width + 0.3) {
                        continue;
                    }
                    paveColumn((int) Math.round(px) + dx, (int) Math.round(pz) + dz);
                }
            }
        }
    }

    private void paveColumn(int x, int z) {
        if (!terrain.inZone(x, z) || !terrain.isMeadow(x, z)) {
            return;
        }
        int y = terrain.top(x, z);
        BlockSpec top = canvas.get(x, y, z);
        if (top == null || !isSoil(top.material())) {
            return;
        }
        double h = noise.hash(x, z, 23);
        canvas.set(x, y, z, BlockSpec.of(h < 0.62 ? Material.DIRT_PATH : h < 0.88 ? Material.COARSE_DIRT
                : Material.GRAVEL));
        terrain.reserve(x, z, x, z);
    }

    private static boolean isSoil(Material material) {
        return material == Material.GRASS_BLOCK || material == Material.DIRT || material == Material.COARSE_DIRT
                || material == Material.PODZOL || material == Material.MOSS_BLOCK || material == Material.ROOTED_DIRT;
    }

    // ------------------------------------------------------------------ yard

    void build() {
        granary();
        garden();
        well(-39, 15);
        hayStack(-46, 18, 3);
        hayStack(-36, 37, 2);
        logPile(-49, 36);
        cart(-41, 40);
        // Lamp posts where the paths meet the mill and the walk over the islet.
        for (int[] lamp : new int[][] {{-35, 16}, {-43, 42}, {-49, 72}}) {
            if (terrain.isFree(lamp[0], lamp[1]) && terrain.isMeadow(lamp[0], lamp[1])) {
                LakeKit.lanternPost(canvas, lamp[0], terrain.top(lamp[0], lamp[1]) + 1, lamp[1],
                        Material.SPRUCE_FENCE, 2);
                terrain.reserve(lamp[0], lamp[1], lamp[0], lamp[1]);
            }
        }
    }

    /** A roofless stone granary: thick walls 2-4 high, a wide doorway east and a breach west, rubble inside. */
    private void granary() {
        for (int x = GRANARY_X1; x <= GRANARY_X2; x++) {
            for (int z = GRANARY_Z1; z <= GRANARY_Z2; z++) {
                boolean wall = x == GRANARY_X1 || x == GRANARY_X2 || z == GRANARY_Z1 || z == GRANARY_Z2;
                if (!wall) {
                    canvas.set(x, GROUND, z, random.nextInt(3) == 0 ? BlockSpec.of(Material.COBBLESTONE)
                            : BlockSpec.of(Material.COARSE_DIRT));
                    continue;
                }
                boolean corner = (x == GRANARY_X1 || x == GRANARY_X2) && (z == GRANARY_Z1 || z == GRANARY_Z2);
                // The north wall stands highest; the walls slump towards the south-west corner.
                int height = corner ? 4 : z == GRANARY_Z1 ? 3 + random.nextInt(2) : 1 + random.nextInt(3);
                for (int y = GROUND + 1; y <= GROUND + height; y++) {
                    canvas.set(x, y, z, corner ? LakeKit.brick(random) : LakeKit.rubble(random));
                }
                if (height < 4 && random.nextInt(3) == 0) {
                    canvas.set(x, GROUND + height + 1, z, LakeKit.rubbleBit(random));
                }
            }
        }
        int midZ = (GRANARY_Z1 + GRANARY_Z2) / 2;
        for (int z = midZ - 1; z <= midZ + 1; z++) {
            for (int y = GROUND + 1; y <= GROUND + 5; y++) {
                canvas.clear(GRANARY_X2, y, z);
            }
        }
        for (int x = GRANARY_X1 + 1; x <= GRANARY_X1 + 2; x++) {
            for (int y = GROUND + 1; y <= GROUND + 5; y++) {
                canvas.clear(x, y, GRANARY_Z2);
            }
        }
        // Sacks and a fallen beam inside.
        canvas.set(GRANARY_X1 + 1, GROUND + 1, GRANARY_Z1 + 1, BlockSpec.of(Material.BROWN_WOOL));
        canvas.set(GRANARY_X1 + 2, GROUND + 1, GRANARY_Z1 + 1, BlockSpec.of(Material.HAY_BLOCK));
        canvas.set(GRANARY_X1 + 1, GROUND + 2, GRANARY_Z1 + 1, BlockSpec.of(Material.HAY_BLOCK));
        for (int x = GRANARY_X1 + 2; x <= GRANARY_X2 - 2; x++) {
            canvas.set(x, GROUND + 1, GRANARY_Z2 - 1, Blocks.log(Material.SPRUCE_LOG, Axis.X));
        }
    }

    /** A fenced kitchen garden gone wild: crop rows, a scarecrow, gaps in the fence. */
    private void garden() {
        for (int x = GARDEN_X1; x <= GARDEN_X2; x++) {
            for (int z = GARDEN_Z1; z <= GARDEN_Z2; z++) {
                boolean edge = x == GARDEN_X1 || x == GARDEN_X2 || z == GARDEN_Z1 || z == GARDEN_Z2;
                if (edge) {
                    if (random.nextInt(4) > 0) {
                        canvas.set(x, GROUND + 1, z, BlockSpec.of(Material.OAK_FENCE));
                    }
                    continue;
                }
                boolean row = (z - GARDEN_Z1) % 2 == 1;
                if (row) {
                    canvas.set(x, GROUND, z, BlockSpec.of(Material.FARMLAND));
                    int roll = random.nextInt(5);
                    Material crop = roll == 0 ? Material.CARROTS : roll == 1 ? Material.POTATOES
                            : roll == 2 ? Material.BEETROOTS : roll == 3 ? Material.WHEAT : Material.SHORT_GRASS;
                    canvas.set(x, GROUND + 1, z, BlockSpec.of(crop));
                } else {
                    canvas.set(x, GROUND, z, BlockSpec.of(Material.COARSE_DIRT));
                }
            }
        }
        // Gates (gaps) on two sides, and a scarecrow.
        canvas.clear(GARDEN_X2, GROUND + 1, GARDEN_Z1 + 2);
        canvas.clear(GARDEN_X1 + 3, GROUND + 1, GARDEN_Z1);
        int sx = GARDEN_X1 + 3;
        int sz = GARDEN_Z1 + 2;
        canvas.set(sx, GROUND + 1, sz, BlockSpec.of(Material.OAK_FENCE));
        canvas.set(sx, GROUND + 2, sz, BlockSpec.of(Material.HAY_BLOCK));
        canvas.set(sx, GROUND + 3, sz, BlockSpec.of(Material.CARVED_PUMPKIN).facing(BlockFace.EAST));
    }

    /** A covered well: a stone ring with an iron grate over the water and a little roof on two posts. */
    private void well(int cx, int cz) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int x = cx + dx;
                int z = cz + dz;
                if (dx == 0 && dz == 0) {
                    canvas.set(x, GROUND - 1, z, BlockSpec.of(Material.STONE));
                    canvas.set(x, GROUND, z, LakeTerrain.WATER);
                    canvas.set(x, GROUND + 1, z, BlockSpec.of(Material.IRON_BARS));
                    continue;
                }
                canvas.set(x, GROUND, z, BlockSpec.of(Material.COBBLESTONE));
                canvas.set(x, GROUND + 1, z, dx != 0 && dz != 0 ? BlockSpec.of(Material.MOSSY_COBBLESTONE)
                        : Blocks.slab(Material.COBBLESTONE_SLAB));
            }
        }
        for (int dx : new int[] {-1, 1}) {
            canvas.set(cx + dx, GROUND + 2, cz, BlockSpec.of(Material.SPRUCE_FENCE));
            canvas.set(cx + dx, GROUND + 3, cz, BlockSpec.of(Material.SPRUCE_FENCE));
        }
        for (int dx = -1; dx <= 1; dx++) {
            canvas.set(cx + dx, GROUND + 4, cz, Blocks.slab(Material.SPRUCE_SLAB));
        }
        canvas.set(cx, GROUND + 3, cz, Blocks.lantern(false, true));
        terrain.reserve(cx - 2, cz - 2, cx + 2, cz + 2);
    }

    /** A heap of hay bales, stepped so it can be climbed. */
    private void hayStack(int cx, int cz, int height) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 0; dz++) {
                int h = Math.max(1, height - Math.abs(dx) - (dz == -1 ? 1 : 0));
                int ground = canvas.surfaceY(cx + dx, cz + dz);
                for (int y = ground + 1; y <= ground + h; y++) {
                    canvas.set(cx + dx, y, cz + dz, Blocks.log(Material.HAY_BLOCK, (dx + y) % 2 == 0 ? Axis.X
                            : Axis.Z));
                }
            }
        }
        terrain.reserve(cx - 2, cz - 2, cx + 2, cz + 1);
    }

    /** A pile of cut logs, two high with a step. */
    private void logPile(int cx, int cz) {
        for (int dx = 0; dx < 4; dx++) {
            for (int dz = 0; dz < 2; dz++) {
                int ground = canvas.surfaceY(cx + dx, cz + dz);
                int h = dz == 0 && dx < 3 ? 2 : 1;
                for (int y = ground + 1; y <= ground + h; y++) {
                    canvas.set(cx + dx, y, cz + dz, Blocks.log(random.nextInt(3) == 0 ? Material.BIRCH_LOG
                            : Material.SPRUCE_LOG, Axis.X));
                }
            }
        }
        terrain.reserve(cx - 1, cz - 1, cx + 4, cz + 2);
    }

    /** A broken hand cart tipped on one wheel, with a spilled sack. */
    private void cart(int x, int z) {
        int ground = canvas.surfaceY(x, z);
        canvas.set(x, ground + 1, z, Blocks.slab(Material.SPRUCE_SLAB).slab(
                Slab.Type.TOP));
        canvas.set(x + 1, ground + 1, z, Blocks.slab(Material.SPRUCE_SLAB).slab(
                Slab.Type.TOP));
        canvas.set(x + 2, ground + 1, z, Blocks.stairs(Material.SPRUCE_STAIRS, BlockFace.EAST)
                .half(Bisected.Half.TOP));
        canvas.set(x, ground + 1, z - 1, Blocks.trapdoor(Material.DARK_OAK_TRAPDOOR, BlockFace.SOUTH, false, true));
        canvas.set(x, ground + 1, z + 1, Blocks.trapdoor(Material.DARK_OAK_TRAPDOOR, BlockFace.NORTH, false, true));
        canvas.set(x + 1, ground + 2, z, BlockSpec.of(Material.HAY_BLOCK));
        canvas.set(x - 1, ground + 1, z, BlockSpec.of(Material.SPRUCE_FENCE));
        canvas.set(x + 3, ground + 1, z + 1, BlockSpec.of(Material.BROWN_WOOL));
        terrain.reserve(x - 2, z - 2, x + 4, z + 2);
    }
}
