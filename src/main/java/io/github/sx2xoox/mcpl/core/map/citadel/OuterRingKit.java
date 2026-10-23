package io.github.sx2xoox.mcpl.core.map.citadel;

import java.util.Random;

import io.github.sx2xoox.mcpl.core.map.BlockSpec;
import io.github.sx2xoox.mcpl.core.map.Blocks;
import io.github.sx2xoox.mcpl.core.map.MapCanvas;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;

/**
 * Shared tools of the outer ring builders: writes that silently skip columns outside the zone, the block palettes of
 * the weathered wall, rubble and rock, and deterministic noise (per column, smooth 2D value noise).
 */
final class OuterRingKit {

    // ------------------------------------------------------------------ palettes (repeats are weights)

    /** The curtain wall and towers: mostly stone bricks, weathered towards moss and cracks. */
    static final Material[] WALL = {Material.STONE_BRICKS, Material.STONE_BRICKS, Material.STONE_BRICKS,
            Material.STONE_BRICKS, Material.STONE_BRICKS, Material.MOSSY_STONE_BRICKS, Material.MOSSY_STONE_BRICKS,
            Material.CRACKED_STONE_BRICKS, Material.CRACKED_STONE_BRICKS, Material.COBBLESTONE, Material.ANDESITE};

    /** The lowest courses of the wall: damp, mossy and patched with cobble. */
    static final Material[] WALL_BASE = {Material.MOSSY_COBBLESTONE, Material.MOSSY_COBBLESTONE,
            Material.MOSSY_STONE_BRICKS, Material.MOSSY_STONE_BRICKS, Material.COBBLESTONE, Material.STONE_BRICKS};

    /** The weathered top courses (parapets, merlons). */
    static final Material[] WALL_TOP = {Material.STONE_BRICKS, Material.STONE_BRICKS, Material.CRACKED_STONE_BRICKS,
            Material.CRACKED_STONE_BRICKS, Material.MOSSY_STONE_BRICKS, Material.ANDESITE, Material.COBBLESTONE};

    /** Paving of the wall-walk and tower floors. */
    static final Material[] WALK = {Material.STONE_BRICKS, Material.STONE_BRICKS, Material.POLISHED_ANDESITE,
            Material.POLISHED_ANDESITE, Material.CRACKED_STONE_BRICKS, Material.COBBLESTONE,
            Material.MOSSY_STONE_BRICKS};

    /** Fallen masonry: breaches, collapsed towers, scattered blocks. */
    static final Material[] RUBBLE = {Material.COBBLESTONE, Material.COBBLESTONE, Material.MOSSY_COBBLESTONE,
            Material.MOSSY_COBBLESTONE, Material.STONE, Material.ANDESITE, Material.ANDESITE,
            Material.CRACKED_STONE_BRICKS, Material.CRACKED_STONE_BRICKS, Material.STONE_BRICKS,
            Material.MOSSY_STONE_BRICKS, Material.TUFF, Material.GRAVEL};

    /** Natural rock: cliffs, crags, boulders and the rim. */
    static final Material[] ROCK = {Material.STONE, Material.STONE, Material.STONE, Material.ANDESITE,
            Material.ANDESITE, Material.TUFF, Material.TUFF, Material.COBBLESTONE, Material.MOSSY_COBBLESTONE};

    /** Gate passage floor. */
    static final Material[] PAVING = {Material.STONE_BRICKS, Material.COBBLESTONE, Material.COBBLESTONE,
            Material.MOSSY_COBBLESTONE, Material.ANDESITE, Material.CRACKED_STONE_BRICKS, Material.GRAVEL};

    /** The road beyond the gates. */
    static final Material[] ROAD = {Material.DIRT_PATH, Material.DIRT_PATH, Material.DIRT_PATH,
            Material.COARSE_DIRT, Material.COARSE_DIRT, Material.GRAVEL};

    private static final BlockFace[] CARDINALS = {BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST, BlockFace.NORTH};

    final MapCanvas canvas;
    final Random random;
    private final long seed;

    OuterRingKit(MapCanvas canvas, String salt) {
        this.canvas = canvas;
        this.random = canvas.random(salt);
        this.seed = canvas.random(salt + "-noise").nextLong();
    }

    // ------------------------------------------------------------------ guarded writes

    boolean canWrite(int x, int z) {
        return canvas.canWrite(x, z);
    }

    void set(int x, int y, int z, BlockSpec spec) {
        if (canvas.canWrite(x, z)) {
            canvas.set(x, y, z, spec);
        }
    }

    void set(int x, int y, int z, Material material) {
        set(x, y, z, BlockSpec.of(material));
    }

    void setIfEmpty(int x, int y, int z, BlockSpec spec) {
        if (canvas.canWrite(x, z) && canvas.get(x, y, z) == null) {
            canvas.set(x, y, z, spec);
        }
    }

    /** Explicit air (carves out what was there). */
    void clear(int x, int y, int z) {
        if (canvas.canWrite(x, z)) {
            canvas.clear(x, y, z);
        }
    }

    /** Carves {@code y1..y2} of a column. */
    void clearColumn(int x, int z, int y1, int y2) {
        for (int y = y1; y <= y2; y++) {
            clear(x, y, z);
        }
    }

    /** True if the position holds nothing yet (or explicit air). */
    boolean isEmpty(int x, int y, int z) {
        BlockSpec spec = canvas.get(x, y, z);
        return spec == null || spec.isAir();
    }

    // ------------------------------------------------------------------ palettes

    /** A random block of the palette (sequential randomness). */
    BlockSpec pick(Material[] palette) {
        return BlockSpec.of(palette[random.nextInt(palette.length)]);
    }

    /** A block of the palette chosen by position, independent of build order. */
    BlockSpec pick(Material[] palette, int x, int y, int z) {
        return BlockSpec.of(palette[(int) (hash(x, y, z, 11) * palette.length)]);
    }

    /** A wall block for height {@code y}: mossy near the ground, cracked near the top. */
    BlockSpec wall(int x, int y, int z) {
        if (y <= OuterRingPlan.GROUND + 2) {
            return pick(WALL_BASE, x, y, z);
        }
        if (y >= OuterRingPlan.WALK) {
            return pick(WALL_TOP, x, y, z);
        }
        return pick(WALL, x, y, z);
    }

    // ------------------------------------------------------------------ directions

    /** The cardinal direction closest to the horizontal vector {@code dx, dz}. */
    static BlockFace cardinal(double dx, double dz) {
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx >= 0 ? BlockFace.EAST : BlockFace.WEST;
        }
        return dz >= 0 ? BlockFace.SOUTH : BlockFace.NORTH;
    }

    /** The four horizontal directions. */
    static BlockFace[] cardinals() {
        return CARDINALS.clone();
    }

    /** A stair climbing towards {@code facing}. */
    static BlockSpec stairs(Material material, BlockFace facing) {
        return Blocks.stairs(material, facing);
    }

    // ------------------------------------------------------------------ noise

    /** A deterministic value in [0, 1) for a position and salt. */
    double hash(int x, int y, int z, int salt) {
        long h = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL) ^ (y * 0x165667B19E3779F9L)
                ^ (salt * 0xD6E8FEB86659FD93L);
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        h *= 0xC4CEB9FE1A85EC53L;
        h ^= h >>> 33;
        return (h >>> 11) * 0x1.0p-53;
    }

    /** A deterministic value in [0, 1) for a column and salt. */
    double hash(int x, int z, int salt) {
        return hash(x, 0, z, salt);
    }

    /** Smooth value noise in [0, 1] (grid spacing 1 in the given coordinates). */
    double noise(double x, double z, int salt) {
        int x0 = (int) Math.floor(x);
        int z0 = (int) Math.floor(z);
        double fx = smooth(x - x0);
        double fz = smooth(z - z0);
        double a = hash(x0, z0, salt);
        double b = hash(x0 + 1, z0, salt);
        double c = hash(x0, z0 + 1, salt);
        double d = hash(x0 + 1, z0 + 1, salt);
        return lerp(lerp(a, b, fx), lerp(c, d, fx), fz);
    }

    /** Two octaves of {@link #noise}, still in [0, 1]. */
    double fbm(double x, double z, int salt) {
        return (noise(x, z, salt) * 2 + noise(x * 2.1 + 17.3, z * 2.1 - 5.7, salt + 101)) / 3;
    }

    static double smooth(double t) {
        double c = Math.max(0, Math.min(1, t));
        return c * c * (3 - 2 * c);
    }

    static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }
}
