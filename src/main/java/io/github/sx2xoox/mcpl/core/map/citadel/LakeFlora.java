package io.github.sx2xoox.mcpl.core.map.citadel;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import io.github.sx2xoox.mcpl.core.map.BlockSpec;
import io.github.sx2xoox.mcpl.core.map.Blocks;
import io.github.sx2xoox.mcpl.core.map.MapBlocks;
import io.github.sx2xoox.mcpl.core.map.MapCanvas;
import org.bukkit.Axis;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;

/**
 * Everything that grows or lies around the lake: willows leaning over the water, meadow oaks and birches, reeds on
 * the banks, lily pads in the shallows, seagrass on the deeper bed, flowers and grass, and the scattered cover of the
 * meadows (boulders, bushes, fallen trunks, stubs of old walls) placed so that open ground never runs much longer
 * than ten blocks without something to hide behind.
 */
final class LakeFlora {

    private static final int GROUND = LakeTerrain.GROUND_Y;
    private static final int WATER = LakeTerrain.WATER_Y;

    /** Hand-placed willows {x, z} (by the water) and meadow trees {x, z, kind: 0 oak, 1 birch}. */
    private static final int[][] WILLOWS = {
            {-76, 25}, {-59, 61}, {-62, 76}, {-47, 66}, {-85, 22},
    };
    private static final int[][] TREES = {
            {-57, 9, 0}, {-32, 28, 1}, {-83, 38, 0}, {-72, 56, 1}, {-48, 47, 0}, {-46, 58, 1},
    };

    private final MapCanvas canvas;
    private final LakeTerrain terrain;
    private final LakeNoise noise;
    private final Random random;
    private final int minX;
    private final int minZ;
    private final int maxX;
    private final int maxZ;

    LakeFlora(MapCanvas canvas, LakeTerrain terrain, LakeNoise noise, Random random, int minX, int minZ, int maxX,
              int maxZ) {
        this.canvas = canvas;
        this.terrain = terrain;
        this.noise = noise;
        this.random = random;
        this.minX = minX;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxZ = maxZ;
    }

    void build() {
        for (int[] w : WILLOWS) {
            willow(w[0], w[1]);
        }
        for (int[] t : TREES) {
            meadowTree(t[0], t[1], t[2] == 1);
        }
        cover();
        water();
        ground();
    }

    // ------------------------------------------------------------------ trees

    /** A willow: a leaning trunk, a wide flat crown and short hanging strands (always 3 blocks of headroom). */
    private void willow(int x, int z) {
        if (!terrain.isFree(x, z) || terrain.isWater(x, z)) {
            return;
        }
        int ground = canvas.surfaceY(x, z);
        int height = 5 + random.nextInt(2);
        int lean = random.nextInt(4);
        int lx = lean == 0 ? 1 : lean == 1 ? -1 : 0;
        int lz = lean == 2 ? 1 : lean == 3 ? -1 : 0;
        int tx = x;
        int tz = z;
        for (int i = 1; i <= height; i++) {
            if (i == height - 1) {
                // The trunk bends towards the water near the top.
                canvas.set(tx, ground + i, tz, Blocks.log(Material.DARK_OAK_LOG, Axis.Y));
                tx += lx;
                tz += lz;
            }
            canvas.set(tx, ground + i, tz, Blocks.log(Material.DARK_OAK_LOG, Axis.Y));
        }
        int top = ground + height;
        crownLayer(tx, top, tz, 3.0, Material.OAK_LEAVES, 0.15);
        crownLayer(tx, top + 1, tz, 2.0, Material.OAK_LEAVES, 0.1);
        crownLayer(tx, top + 2, tz, 0.9, Material.AZALEA_LEAVES, 0.3);
        // Hanging strands around the rim, never lower than three blocks above the ground.
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d < 2.2 || d > 3.2 || random.nextInt(3) == 0) {
                    continue;
                }
                int sx = tx + dx;
                int sz = tz + dz;
                if (!canvas.canWrite(sx, sz) || !canvas.isSolid(sx, top, sz)) {
                    continue;
                }
                int floor = Math.max(canvas.surfaceY(sx, sz), WATER);
                int length = 1 + random.nextInt(2);
                for (int y = top - 1; y >= top - length && y > floor + 3; y--) {
                    canvas.set(sx, y, sz, Blocks.leaves(Material.OAK_LEAVES));
                }
            }
        }
        terrain.reserve(x - 1, z - 1, x + 1, z + 1);
    }

    /** A meadow oak (round crown) or birch (tall and slim). */
    private void meadowTree(int x, int z, boolean birch) {
        if (!terrain.isFree(x, z) || !terrain.isMeadow(x, z)) {
            return;
        }
        int ground = canvas.surfaceY(x, z);
        int height = 6 + random.nextInt(2);
        Material log = birch ? Material.BIRCH_LOG : Material.OAK_LOG;
        Material leaves = birch ? Material.BIRCH_LEAVES : Material.OAK_LEAVES;
        for (int i = 1; i <= height; i++) {
            canvas.set(x, ground + i, z, Blocks.log(log, Axis.Y));
        }
        int top = ground + height;
        double r = birch ? 1.7 : 2.3;
        crownLayer(x, top - 2, z, r, leaves, 0.25);
        crownLayer(x, top - 1, z, r + 0.2, leaves, 0.12);
        crownLayer(x, top, z, r - 0.5, leaves, 0.1);
        crownLayer(x, top + 1, z, r - 1.4, leaves, 0.2);
        terrain.reserve(x - 1, z - 1, x + 1, z + 1);
    }

    private void crownLayer(int cx, int y, int cz, double radius, Material leaves, double gaps) {
        int r = (int) Math.ceil(radius);
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d > radius || d > radius - 0.8 && random.nextDouble() < gaps) {
                    continue;
                }
                int x = cx + dx;
                int z = cz + dz;
                if (canvas.canWrite(x, z) && canvas.get(x, y, z) == null) {
                    canvas.set(x, y, z, Blocks.leaves(leaves));
                }
            }
        }
    }

    // ------------------------------------------------------------------ cover

    /** Hand-placed cover where the grid below leaves narrow corners bare: {x, z, kind}. */
    private static final int[][] PLACED_COVER = {
            {-27, 32, 4}, {-30, 38, 3}, {-29, 26, 0}, {-86, 30, 0}, {-57, 71, 0}, {-33, 46, 1},
    };

    /** Boulders, bushes, fallen trunks and wall stubs on a jittered grid over the free meadow. */
    private void cover() {
        for (int[] c : PLACED_COVER) {
            if (!roomFor(c[0], c[1], 1)) {
                continue;
            }
            switch (c[2]) {
                case 0 -> boulder(c[0], c[1]);
                case 1 -> bush(c[0], c[1]);
                case 3 -> wallStub(c[0], c[1]);
                default -> waystone(c[0], c[1]);
            }
        }
        // Then fill the largest gaps first, until no meadow spot is more than COVER_GAP blocks from cover.
        int sizeX = maxX - minX + 1;
        int sizeZ = maxZ - minZ + 1;
        double[][] gap = new double[sizeX][sizeZ];
        for (double[] row : gap) {
            Arrays.fill(row, 99);
        }
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (hasHeadCover(x, z)) {
                    markCover(gap, x, z);
                }
            }
        }
        for (int round = 0; round < 400; round++) {
            int bestX = 0;
            int bestZ = 0;
            double best = COVER_GAP;
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    double g = gap[x - minX][z - minZ] + noise.hash(x, z, 53) * 0.5;
                    if (g > best && LakeTerrain.edgeDistance(x, z) >= 3.5 && roomFor(x, z, 1)) {
                        best = g;
                        bestX = x;
                        bestZ = z;
                    }
                }
            }
            if (best <= COVER_GAP) {
                break;
            }
            int kind = random.nextInt(20);
            if (kind < 7) {
                boulder(bestX, bestZ);
            } else if (kind < 13) {
                bush(bestX, bestZ);
            } else if (kind < 16) {
                fallenLog(bestX, bestZ);
            } else {
                wallStub(bestX, bestZ);
            }
            markCover(gap, bestX, bestZ);
        }
    }

    /** Largest distance from an open meadow spot to the nearest cover the fill leaves. */
    private static final double COVER_GAP = 5.5;

    /** True if something stands at head height in the column (for a player on its ground or swimming). */
    private boolean hasHeadCover(int x, int z) {
        int ground = Math.max(terrain.top(x, z), WATER);
        for (int y = ground + 2; y <= ground + 3; y++) {
            BlockSpec spec = canvas.get(x, y, z);
            if (spec != null && !spec.isAir() && !MapBlocks.isPassable(spec)) {
                return true;
            }
        }
        return false;
    }

    private void markCover(double[][] gap, int cx, int cz) {
        int r = 12;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int lx = cx + dx - minX;
                int lz = cz + dz - minZ;
                if (lx < 0 || lz < 0 || lx >= gap.length || lz >= gap[0].length) {
                    continue;
                }
                gap[lx][lz] = Math.min(gap[lx][lz], Math.sqrt(dx * dx + dz * dz));
            }
        }
    }

    private boolean roomFor(int x, int z, int r) {
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (!terrain.isFree(x + dx, z + dz) || !terrain.isMeadow(x + dx, z + dz)) {
                    return false;
                }
            }
        }
        return true;
    }

    private void boulder(int cx, int cz) {
        double rx = 1.3 + random.nextDouble() * 1.2;
        double rz = 1.2 + random.nextDouble() * 1.1;
        double ry = 1.4 + random.nextDouble() * 0.8;
        int r = (int) Math.ceil(Math.max(rx, rz));
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                double flat = (dx * dx) / (rx * rx) + (dz * dz) / (rz * rz);
                if (flat > 1) {
                    continue;
                }
                int x = cx + dx;
                int z = cz + dz;
                if (!canvas.canWrite(x, z)) {
                    continue;
                }
                int ground = canvas.surfaceY(x, z);
                int height = (int) Math.round(ry * Math.sqrt(1 - flat) + 0.2 * noise.at(x, z, 2, 1));
                for (int y = ground; y <= ground + height; y++) {
                    canvas.set(x, y, z, boulderBlock(y - ground, height));
                }
            }
        }
        terrain.reserve(cx - r, cz - r, cx + r, cz + r);
    }

    /** An old waystone: a weathered pillar with a lantern niche, on a stepped base. */
    private void waystone(int x, int z) {
        int ground = canvas.surfaceY(x, z);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                canvas.set(x + dx, ground, z + dz, LakeKit.brick(random));
            }
        }
        canvas.set(x, ground + 1, z, BlockSpec.of(Material.CHISELED_STONE_BRICKS));
        canvas.set(x, ground + 2, z, LakeKit.brick(random));
        canvas.set(x, ground + 3, z, Blocks.slab(Material.STONE_BRICK_SLAB));
        canvas.set(x + 1, ground + 1, z, Blocks.stairs(Material.STONE_BRICK_STAIRS, BlockFace.WEST));
        canvas.set(x - 1, ground + 1, z, Blocks.lantern(false, false));
        terrain.reserve(x - 2, z - 2, x + 2, z + 2);
    }

    private BlockSpec boulderBlock(int level, int height) {
        int roll = random.nextInt(10);
        if (level == height && roll < 4) {
            return BlockSpec.of(roll < 2 ? Material.MOSSY_COBBLESTONE : Material.MOSS_BLOCK);
        }
        return BlockSpec.of(roll < 4 ? Material.STONE : roll < 7 ? Material.ANDESITE : roll < 9
                ? Material.COBBLESTONE : Material.TUFF);
    }

    private void bush(int cx, int cz) {
        Material leaves = random.nextInt(3) == 0 ? Material.FLOWERING_AZALEA_LEAVES : random.nextBoolean()
                ? Material.AZALEA_LEAVES : Material.OAK_LEAVES;
        int count = 3 + random.nextInt(4);
        int x = cx;
        int z = cz;
        for (int i = 0; i < count; i++) {
            int ground = canvas.surfaceY(x, z);
            if (canvas.canWrite(x, z) && terrain.isMeadow(x, z)) {
                canvas.set(x, ground + 1, z, Blocks.leaves(leaves));
                if (i == 0) {
                    canvas.set(x, ground + 2, z, Blocks.leaves(leaves));
                }
            }
            if (random.nextBoolean()) {
                x += random.nextBoolean() ? 1 : -1;
            } else {
                z += random.nextBoolean() ? 1 : -1;
            }
        }
        terrain.reserve(cx - 2, cz - 2, cx + 2, cz + 2);
    }

    private void fallenLog(int cx, int cz) {
        boolean alongX = random.nextBoolean();
        int length = 4 + random.nextInt(3);
        Material log = random.nextInt(3) == 0 ? Material.BIRCH_LOG : random.nextBoolean() ? Material.OAK_LOG
                : Material.SPRUCE_LOG;
        for (int i = 0; i < length; i++) {
            int x = cx + (alongX ? i - length / 2 : 0);
            int z = cz + (alongX ? 0 : i - length / 2);
            if (!canvas.canWrite(x, z) || !terrain.isMeadow(x, z)) {
                continue;
            }
            int ground = canvas.surfaceY(x, z);
            canvas.set(x, ground + 1, z, Blocks.log(log, alongX ? Axis.X : Axis.Z));
            if (random.nextInt(4) == 0) {
                canvas.set(x, ground + 2, z, BlockSpec.of(random.nextBoolean() ? Material.MOSS_CARPET
                        : Material.BROWN_MUSHROOM));
            }
        }
        terrain.reserve(cx - 4, cz - 4, cx + 4, cz + 4);
    }

    /** A stub of old wall, 3-5 long, stepping from waist to head height. */
    private void wallStub(int cx, int cz) {
        boolean alongX = random.nextBoolean();
        int length = 3 + random.nextInt(3);
        for (int i = 0; i < length; i++) {
            int x = cx + (alongX ? i - length / 2 : 0);
            int z = cz + (alongX ? 0 : i - length / 2);
            if (!canvas.canWrite(x, z) || !terrain.isMeadow(x, z)) {
                continue;
            }
            int ground = canvas.surfaceY(x, z);
            int height = Math.min(1 + i, 1 + length - i);
            height = Math.min(height, 3);
            for (int y = ground + 1; y <= ground + height; y++) {
                canvas.set(x, y, z, LakeKit.brick(random));
            }
            if (height < 3 && random.nextInt(3) == 0) {
                canvas.set(x, ground + height + 1, z, LakeKit.brickSlab(random));
            }
        }
        terrain.reserve(cx - 3, cz - 3, cx + 3, cz + 3);
    }

    // ------------------------------------------------------------------ water plants

    private void water() {
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (!terrain.isWater(x, z) || !canvas.canWrite(x, z)) {
                    continue;
                }
                int bed = terrain.top(x, z);
                int depth = WATER - bed;
                double n = noise.unit(x + 40, z - 90, 6);
                double h = noise.hash(x, z, 31);
                boolean open = isWater(x, bed + 1, z);
                if (depth >= 3 && n > 0.45 && h < 0.18 && open && isWater(x, bed + 2, z)) {
                    canvas.set(x, bed + 1, z, waterlogged(Material.TALL_SEAGRASS).half(Bisected.Half.BOTTOM));
                    canvas.set(x, bed + 2, z, waterlogged(Material.TALL_SEAGRASS).half(Bisected.Half.TOP));
                } else if (depth >= 2 && n > 0.35 && h < 0.45 && open) {
                    canvas.set(x, bed + 1, z, waterlogged(Material.SEAGRASS));
                }
            }
        }
        lilyPads();
    }

    /**
     * Lily pads in calm shallows: candidate spots form clusters, and only clusters touching a bank are kept, so a
     * player on the shore can always step onto them.
     */
    private void lilyPads() {
        Set<Long> candidates = new HashSet<>();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (!terrain.isWater(x, z) || !canvas.canWrite(x, z) || !terrain.isFree(x, z)) {
                    continue;
                }
                double lily = noise.unit(x - 260, z + 30, 5);
                double h = noise.hash(x, z, 43);
                if (WATER - terrain.top(x, z) <= 2 && terrain.wetness(x, z) < 4 && lily > 0.6 && h < 0.6
                        && isWater(x, WATER, z) && isOpenAir(x, WATER + 1, z)) {
                    candidates.add(key(x, z));
                }
            }
        }
        ArrayDeque<long[]> queue = new ArrayDeque<>();
        Set<Long> placed = new HashSet<>();
        for (long k : candidates) {
            int x = (int) (k >> 32);
            int z = (int) k;
            if (touchesBank(x, z)) {
                queue.add(new long[] {x, z});
                placed.add(k);
            }
        }
        while (!queue.isEmpty()) {
            long[] at = queue.poll();
            int x = (int) at[0];
            int z = (int) at[1];
            canvas.set(x, WATER + 1, z, BlockSpec.of(Material.LILY_PAD));
            for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                long next = key(x + d[0], z + d[1]);
                if (candidates.contains(next) && placed.add(next)) {
                    queue.add(new long[] {x + d[0], z + d[1]});
                }
            }
        }
    }

    private boolean touchesBank(int x, int z) {
        for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            if (terrain.inZone(x + d[0], z + d[1]) && terrain.top(x + d[0], z + d[1]) == WATER
                    && isOpenAir(x + d[0], WATER + 1, z + d[1])) {
                return true;
            }
        }
        return false;
    }

    private boolean isOpenAir(int x, int y, int z) {
        BlockSpec spec = canvas.get(x, y, z);
        return spec == null || spec.isAir();
    }

    private static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    private static BlockSpec waterlogged(Material material) {
        // Seagrass is always full of water; flag it so the map checks treat it as water.
        return BlockSpec.of(material).waterlogged(true);
    }

    private boolean isWater(int x, int y, int z) {
        BlockSpec spec = canvas.get(x, y, z);
        return spec != null && spec.material() == Material.WATER;
    }

    // ------------------------------------------------------------------ ground plants

    private void ground() {
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (!terrain.inZone(x, z)) {
                    continue;
                }
                int y = canvas.surfaceY(x, z);
                BlockSpec top = canvas.get(x, y, z);
                if (top == null || canvas.get(x, y + 1, z) != null) {
                    continue;
                }
                Material soil = top.material();
                if (y == WATER && isBank(soil) && nextToWater(x, z)) {
                    reeds(x, z);
                } else if (soil == Material.GRASS_BLOCK || soil == Material.PODZOL || soil == Material.MOSS_BLOCK) {
                    meadowPlant(x, y, z, soil);
                }
            }
        }
    }

    private static boolean isBank(Material soil) {
        return soil == Material.SAND || soil == Material.MUD || soil == Material.COARSE_DIRT
                || soil == Material.GRAVEL || soil == Material.DIRT;
    }

    private boolean nextToWater(int x, int z) {
        return terrain.isWater(x + 1, z) || terrain.isWater(x - 1, z) || terrain.isWater(x, z + 1)
                || terrain.isWater(x, z - 1);
    }

    /** Sugar cane reeds in clumps along the banks, with tall grass between. */
    private void reeds(int x, int z) {
        Material soil = canvas.get(x, WATER, z).material();
        if (soil == Material.GRAVEL || !terrain.isFree(x, z)) {
            return;
        }
        double clump = noise.unit(x + 77, z + 13, 4);
        double h = noise.hash(x, z, 37);
        if (clump > 0.52 && h < 0.7) {
            int height = 2 + (h < 0.25 ? 1 : 0);
            for (int y = WATER + 1; y <= WATER + height; y++) {
                canvas.set(x, y, z, BlockSpec.of(Material.SUGAR_CANE));
            }
        } else if (h < 0.18 && soil != Material.SAND) {
            Blocks.tallPlant(canvas, x, WATER + 1, z, Material.TALL_GRASS);
        }
    }

    private void meadowPlant(int x, int y, int z, Material soil) {
        if (!canvas.canWrite(x, z)) {
            return;
        }
        double h = noise.hash(x, z, 41);
        double patch = noise.unit(x - 15, z + 61, 8);
        double w = terrain.wetness(x, z);
        if (h < 0.20) {
            canvas.set(x, y + 1, z, BlockSpec.of(soil == Material.PODZOL ? Material.FERN : Material.SHORT_GRASS));
        } else if (h < 0.235 && soil == Material.GRASS_BLOCK) {
            Blocks.tallPlant(canvas, x, y + 1, z, patch > 0.6 ? Material.LARGE_FERN : Material.TALL_GRASS);
        } else if (h < 0.265) {
            canvas.set(x, y + 1, z, BlockSpec.of(flower(w, patch, h)));
        } else if (h < 0.28 && patch > 0.6) {
            canvas.set(x, y + 1, z, BlockSpec.of(Material.PINK_PETALS));
        }
    }

    private static Material flower(double wetness, double patch, double h) {
        boolean first = h < 0.25;
        if (wetness > -6 && first) {
            return Material.BLUE_ORCHID;
        }
        if (patch < 0.3) {
            return first ? Material.CORNFLOWER : Material.AZURE_BLUET;
        }
        if (patch < 0.5) {
            return first ? Material.OXEYE_DAISY : Material.DANDELION;
        }
        if (patch < 0.7) {
            return first ? Material.POPPY : Material.ALLIUM;
        }
        return first ? Material.LILY_OF_THE_VALLEY : Material.RED_TULIP;
    }
}
