package io.github.sx2xoox.mcpl.core.map.citadel;

import java.util.Random;

import io.github.sx2xoox.mcpl.core.map.BlockSpec;
import org.bukkit.Material;

/**
 * The town's ground: cobbled streets and lanes (polylines with a width), the paved market square, and trampled
 * yards. Everything stays at ground level ({@code y = 64}), only the top block changes.
 */
final class TownStreets {

    private static final int G = TownPainter.GROUND;

    /** Streets as {width, x1, z1, x2, z2, ...}. */
    static final int[][] STREETS = {
            {5, 33, 21, 46, 21, 52, 24},                    // gate street: from the town gate to the market
            {3, 54, 19, 53, 12, 53, 2},                     // north lane to the east road
            {3, 67, 19, 66, 10, 66, 2},                     // tavern lane to the east road
            {4, 68, 28, 80, 29, 88, 27},                    // east street towards the wall
            {3, 88, 27, 86, 40, 80, 50, 71, 61, 60, 71},    // wall lane along the curtain wall
            {4, 60, 36, 60, 45},                            // chapel street
            {3, 52, 34, 45, 42, 43, 50, 37, 58},            // south-west lane to the south-east road
    };

    /** The market square: {minX, minZ, maxX, maxZ}. */
    static final int[] MARKET = {52, 19, 68, 36};

    private static final Material[] STREET = {Material.COBBLESTONE, Material.COBBLESTONE, Material.COBBLESTONE,
            Material.MOSSY_COBBLESTONE, Material.GRAVEL, Material.ANDESITE, Material.DIRT_PATH, Material.STONE_BRICKS,
            Material.COARSE_DIRT};

    private static final Material[] STREET_EDGE = {Material.DIRT_PATH, Material.COARSE_DIRT, Material.GRASS_BLOCK,
            Material.MOSSY_COBBLESTONE, Material.GRAVEL};

    private static final Material[] SQUARE = {Material.STONE_BRICKS, Material.STONE_BRICKS, Material.STONE_BRICKS,
            Material.CRACKED_STONE_BRICKS, Material.MOSSY_STONE_BRICKS, Material.POLISHED_ANDESITE,
            Material.COBBLESTONE, Material.ANDESITE};

    private TownStreets() {
    }

    static void pave(TownPainter p) {
        Random random = p.random();
        for (int x = 15; x <= 97; x++) {
            for (int z = 0; z <= 85; z++) {
                if (!p.owns(x, z)) {
                    continue;
                }
                double street = streetDistance(x, z);
                double market = marketDistance(x, z);
                double noise = random.nextDouble() * 0.9;
                if (market <= 0) {
                    p.set(x, G, z, square(random, x, z));
                } else if (market < 1.2 + noise) {
                    p.set(x, G, z, BlockSpec.of(STREET[random.nextInt(STREET.length)]));
                } else if (street <= -0.6) {
                    p.set(x, G, z, BlockSpec.of(STREET[random.nextInt(STREET.length)]));
                } else if (street <= 0.4 + noise) {
                    p.set(x, G, z, BlockSpec.of(STREET_EDGE[random.nextInt(STREET_EDGE.length)]));
                } else if (random.nextDouble() < 0.025) {
                    p.set(x, G, z, BlockSpec.of(random.nextBoolean() ? Material.COARSE_DIRT : Material.ROOTED_DIRT));
                }
            }
        }
    }

    /** The square's paving: plain stone with a ring of polished andesite around the well. */
    private static BlockSpec square(Random random, int x, int z) {
        double d = Math.hypot(x - 60, z - 28);
        if (d > 4.5 && d < 5.6) {
            return BlockSpec.of(random.nextDouble() < 0.85 ? Material.POLISHED_ANDESITE : Material.ANDESITE);
        }
        if (random.nextDouble() < 0.04) {
            return BlockSpec.of(Material.GRASS_BLOCK);
        }
        return BlockSpec.of(SQUARE[random.nextInt(SQUARE.length)]);
    }

    /**
     * Signed distance to the nearest street edge: negative inside a street (how deep), positive outside.
     */
    static double streetDistance(int x, int z) {
        double best = Double.MAX_VALUE;
        for (int[] street : STREETS) {
            double half = street[0] / 2.0;
            for (int i = 1; i + 3 < street.length; i += 2) {
                double d = segmentDistance(x, z, street[i], street[i + 1], street[i + 2], street[i + 3]) - half;
                best = Math.min(best, d);
            }
        }
        return best;
    }

    /** Distance outside the market square's rounded rectangle (0 or less inside). */
    static double marketDistance(int x, int z) {
        double dx = Math.max(MARKET[0] - x, Math.max(0, x - MARKET[2]));
        double dz = Math.max(MARKET[1] - z, Math.max(0, z - MARKET[3]));
        double outside = Math.hypot(Math.max(0, dx), Math.max(0, dz));
        if (outside > 0) {
            return outside;
        }
        // cut the corners a little
        double cx = Math.min(x - MARKET[0], MARKET[2] - x);
        double cz = Math.min(z - MARKET[1], MARKET[3] - z);
        return cx + cz < 2 ? 0.5 : 0;
    }

    static double segmentDistance(double px, double pz, double x1, double z1, double x2, double z2) {
        double dx = x2 - x1;
        double dz = z2 - z1;
        double length = dx * dx + dz * dz;
        double t = length == 0 ? 0 : Math.max(0, Math.min(1, ((px - x1) * dx + (pz - z1) * dz) / length));
        return Math.hypot(px - (x1 + t * dx), pz - (z1 + t * dz));
    }
}
