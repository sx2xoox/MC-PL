package io.github.sx2xoox.mcpl.core.map.citadel;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import io.github.sx2xoox.mcpl.core.map.BlockSpec;
import io.github.sx2xoox.mcpl.core.map.Blocks;
import io.github.sx2xoox.mcpl.core.map.MapCanvas;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;

/**
 * The keep's shared palette, noise and small building helpers (see {@link KeepZone}). Every piece of the keep is built
 * from the same weathered stone, so the parts read as one ruined castle.
 */
final class KeepKit {

    /** Top block of the courtyard ground. */
    static final int GROUND = CitadelLayout.GROUND_Y;
    /** Where players stand on the courtyard ground. */
    static final int FLOOR = GROUND + 1;
    /** From this radius out to the keep's edge the ground stays (nearly) flat, open to the ring road. */
    static final double EDGE = 28.5;

    private static final BlockFace[] SIDES = {BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST};

    private KeepKit() {
    }

    // ------------------------------------------------------------------ palette

    /** Weathered castle masonry: mostly stone bricks, with mossy, cracked and patched blocks. */
    static BlockSpec masonry(Random random) {
        int roll = random.nextInt(100);
        Material material;
        if (roll < 50) {
            material = Material.STONE_BRICKS;
        } else if (roll < 66) {
            material = Material.MOSSY_STONE_BRICKS;
        } else if (roll < 81) {
            material = Material.CRACKED_STONE_BRICKS;
        } else if (roll < 89) {
            material = Material.COBBLESTONE;
        } else if (roll < 95) {
            material = Material.ANDESITE;
        } else {
            material = Material.MOSSY_COBBLESTONE;
        }
        return BlockSpec.of(material);
    }

    /** The lowest courses of a wall: damp, mossy and patched. */
    static BlockSpec footing(Random random) {
        int roll = random.nextInt(100);
        Material material;
        if (roll < 35) {
            material = Material.MOSSY_STONE_BRICKS;
        } else if (roll < 60) {
            material = Material.MOSSY_COBBLESTONE;
        } else if (roll < 80) {
            material = Material.STONE_BRICKS;
        } else if (roll < 92) {
            material = Material.COBBLESTONE;
        } else {
            material = Material.CRACKED_STONE_BRICKS;
        }
        return BlockSpec.of(material);
    }

    /** Dark masonry for plinths and string courses of the keep tower. */
    static BlockSpec darkMasonry(Random random) {
        int roll = random.nextInt(100);
        Material material;
        if (roll < 55) {
            material = Material.DEEPSLATE_BRICKS;
        } else if (roll < 80) {
            material = Material.CRACKED_DEEPSLATE_BRICKS;
        } else if (roll < 92) {
            material = Material.POLISHED_DEEPSLATE;
        } else {
            material = Material.COBBLED_DEEPSLATE;
        }
        return BlockSpec.of(material);
    }

    /** Fallen stone: rubble heaps and broken wall cores. */
    static BlockSpec rubble(Random random) {
        int roll = random.nextInt(100);
        Material material;
        if (roll < 30) {
            material = Material.COBBLESTONE;
        } else if (roll < 50) {
            material = Material.MOSSY_COBBLESTONE;
        } else if (roll < 65) {
            material = Material.CRACKED_STONE_BRICKS;
        } else if (roll < 77) {
            material = Material.STONE;
        } else if (roll < 87) {
            material = Material.ANDESITE;
        } else if (roll < 94) {
            material = Material.STONE_BRICKS;
        } else {
            material = Material.TUFF;
        }
        return BlockSpec.of(material);
    }

    /** Stairs material matching {@link #masonry}. */
    static Material stairs(Random random) {
        int roll = random.nextInt(100);
        if (roll < 50) {
            return Material.STONE_BRICK_STAIRS;
        }
        if (roll < 75) {
            return Material.MOSSY_STONE_BRICK_STAIRS;
        }
        if (roll < 90) {
            return Material.COBBLESTONE_STAIRS;
        }
        return Material.MOSSY_COBBLESTONE_STAIRS;
    }

    /** Slab material matching {@link #masonry}. */
    static Material slab(Random random) {
        int roll = random.nextInt(100);
        if (roll < 45) {
            return Material.STONE_BRICK_SLAB;
        }
        if (roll < 70) {
            return Material.MOSSY_STONE_BRICK_SLAB;
        }
        if (roll < 85) {
            return Material.COBBLESTONE_SLAB;
        }
        if (roll < 93) {
            return Material.ANDESITE_SLAB;
        }
        return Material.MOSSY_COBBLESTONE_SLAB;
    }

    /**
     * A half-block coping stone for low parapets and wall stubs. (Wall blocks are avoided: the test server cannot
     * place them.)
     */
    static BlockSpec coping(Random random) {
        return Blocks.slab(slab(random));
    }

    /** A lantern on a short wooden post standing on a stone base, with its foot at {@code y}. */
    static void lanternPost(MapCanvas canvas, int x, int y, int z) {
        canvas.set(x, y, z, BlockSpec.of(Material.CHISELED_STONE_BRICKS));
        canvas.set(x, y + 1, z, BlockSpec.of(Material.DARK_OAK_FENCE));
        canvas.set(x, y + 2, z, Blocks.lantern(false, false));
    }

    /** Worn paving of courtyards, passages and floors. */
    static BlockSpec paving(Random random) {
        int roll = random.nextInt(100);
        Material material;
        if (roll < 34) {
            material = Material.STONE_BRICKS;
        } else if (roll < 50) {
            material = Material.CRACKED_STONE_BRICKS;
        } else if (roll < 62) {
            material = Material.MOSSY_STONE_BRICKS;
        } else if (roll < 76) {
            material = Material.COBBLESTONE;
        } else if (roll < 86) {
            material = Material.POLISHED_ANDESITE;
        } else if (roll < 93) {
            material = Material.ANDESITE;
        } else {
            material = Material.MOSSY_COBBLESTONE;
        }
        return BlockSpec.of(material);
    }

    // ------------------------------------------------------------------ geometry

    static double radius(double x, double z) {
        return Math.sqrt(x * x + z * z);
    }

    /** Signed smallest difference {@code a - b} of two angles in degrees, in (-180, 180]. */
    static double angleDelta(double a, double b) {
        double d = (a - b) % 360;
        if (d > 180) {
            d -= 360;
        }
        if (d <= -180) {
            d += 360;
        }
        return d;
    }

    /** The point at {@code radius} along {@code angleDegrees} (as in {@link CitadelLayout}), unrounded: {x, z}. */
    static double[] point(double radius, double angleDegrees) {
        double a = Math.toRadians(angleDegrees);
        return new double[] {Math.cos(a) * radius, Math.sin(a) * radius};
    }

    /** Distance from {@code px, pz} to the segment from {@code a} to {@code b}. */
    static double distanceToSegment(double px, double pz, double ax, double az, double bx, double bz) {
        double dx = bx - ax;
        double dz = bz - az;
        double length2 = dx * dx + dz * dz;
        double t = length2 == 0 ? 0 : ((px - ax) * dx + (pz - az) * dz) / length2;
        t = Math.max(0, Math.min(1, t));
        return radius(px - (ax + dx * t), pz - (az + dz * t));
    }

    /** The horizontal block face closest to the direction {@code dx, dz}. */
    static BlockFace cardinal(double dx, double dz) {
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx >= 0 ? BlockFace.EAST : BlockFace.WEST;
        }
        return dz >= 0 ? BlockFace.SOUTH : BlockFace.NORTH;
    }

    static BlockFace[] sides() {
        return SIDES.clone();
    }

    // ------------------------------------------------------------------ noise

    /** Smooth value noise in [0, 1) with one lattice point per block of {@code x, z} (scale the inputs for size). */
    static double noise(int salt, double x, double z) {
        int x0 = (int) Math.floor(x);
        int z0 = (int) Math.floor(z);
        double fx = x - x0;
        double fz = z - z0;
        double sx = fx * fx * (3 - 2 * fx);
        double sz = fz * fz * (3 - 2 * fz);
        double a = hash(salt, x0, z0);
        double b = hash(salt, x0 + 1, z0);
        double c = hash(salt, x0, z0 + 1);
        double d = hash(salt, x0 + 1, z0 + 1);
        double top = a + (b - a) * sx;
        double bottom = c + (d - c) * sx;
        return top + (bottom - top) * sz;
    }

    /** A fixed pseudo-random number in [0, 1) for a lattice point. */
    static double hash(int salt, int x, int z) {
        long h = salt * 0x9E3779B97F4A7C15L + x * 0xC2B2AE3D27D4EB4FL + z * 0x165667B19E3779F9L;
        h ^= h >>> 29;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 32;
        h *= 0x94D049BB133111EBL;
        h ^= h >>> 29;
        return (h >>> 11) * 0x1.0p-53;
    }

    // ------------------------------------------------------------------ writes

    /** Sets a block if the column belongs to the keep (decoration that may spill over the edge is simply cut). */
    static void put(MapCanvas canvas, int x, int y, int z, BlockSpec spec) {
        if (canvas.canWrite(x, z)) {
            canvas.set(x, y, z, spec);
        }
    }

    /** Sets a block only where nothing is yet (air or never set). */
    static void putIfFree(MapCanvas canvas, int x, int y, int z, BlockSpec spec) {
        if (canvas.canWrite(x, z) && isFree(canvas, x, y, z)) {
            canvas.set(x, y, z, spec);
        }
    }

    static boolean isFree(MapCanvas canvas, int x, int y, int z) {
        BlockSpec spec = canvas.get(x, y, z);
        return spec == null || spec.isAir();
    }

    static void clear(MapCanvas canvas, int x, int y1, int z, int y2) {
        for (int y = y1; y <= y2; y++) {
            put(canvas, x, y, z, BlockSpec.AIR);
        }
    }

    // ------------------------------------------------------------------ heaps

    /**
     * A walkable heap: a set of columns, each filled with stone up to its own top. When placed, a column next to one
     * that is exactly one block higher gets a stair (facing the higher side) on top, so slopes can be walked up instead
     * of only jumped. Used for rubble mounds and ramps.
     */
    static final class Heap {

        private final Map<Long, Integer> tops = new HashMap<>();

        /**
         * Raises the column to at least {@code top} (the y of its highest solid block). Near the keep's edge heaps stay
         * low, so the ground towards the ring road can be walked all round.
         */
        void raise(int x, int z, int top) {
            double r = KeepKit.radius(x, z);
            int limit = r >= EDGE + 1 ? GROUND : r >= EDGE ? GROUND + 1 : Integer.MAX_VALUE;
            int clamped = Math.min(top, limit);
            if (clamped > GROUND) {
                tops.merge(key(x, z), clamped, Math::max);
            }
        }

        /** The top of a heap column, or {@code GROUND} for columns not in the heap. */
        int top(int x, int z) {
            return tops.getOrDefault(key(x, z), GROUND);
        }

        boolean contains(int x, int z) {
            return tops.containsKey(key(x, z));
        }

        /**
         * A round mound around {@code cx, cz}: {@code height} blocks high in the middle, falling off by about one block
         * per block outwards (a little ragged by {@code salt}'s noise).
         */
        void mound(int salt, double cx, double cz, double radius, double height) {
            int r = (int) Math.ceil(radius);
            for (int x = (int) Math.floor(cx) - r; x <= (int) Math.ceil(cx) + r; x++) {
                for (int z = (int) Math.floor(cz) - r; z <= (int) Math.ceil(cz) + r; z++) {
                    double d = KeepKit.radius(x - cx, z - cz);
                    double ragged = (noise(salt, x * 0.45, z * 0.45) - 0.5) * 1.4;
                    double h = Math.min(height, (radius - d) + ragged * 0.6);
                    if (h >= 0.5) {
                        raise(x, z, GROUND + (int) Math.round(h));
                    }
                }
            }
        }

        /**
         * Places the heap: fills every column from the ground up to its top (only where nothing is built yet), then
         * caps the edges of each step with stairs or slabs.
         */
        void place(MapCanvas canvas, Random random, boolean mossy) {
            for (Map.Entry<Long, Integer> entry : tops.entrySet()) {
                int x = x(entry.getKey());
                int z = z(entry.getKey());
                for (int y = FLOOR; y <= entry.getValue(); y++) {
                    putIfFree(canvas, x, y, z, mossy && random.nextInt(3) == 0
                            ? BlockSpec.of(Material.MOSSY_COBBLESTONE) : rubble(random));
                }
            }
            for (Map.Entry<Long, Integer> entry : tops.entrySet()) {
                int x = x(entry.getKey());
                int z = z(entry.getKey());
                int top = entry.getValue();
                BlockFace up = null;
                for (BlockFace face : SIDES) {
                    if (top(x + face.getModX(), z + face.getModZ()) == top + 1) {
                        up = face;
                        break;
                    }
                }
                if (KeepKit.radius(x, z) >= EDGE) {
                    continue;
                }
                if (up != null) {
                    BlockSpec cap = random.nextInt(4) == 0 ? Blocks.slab(slab(random))
                            : Blocks.stairs(stairs(random), up);
                    putIfFree(canvas, x, top + 1, z, cap);
                } else if (top > GROUND + 1 && random.nextInt(5) == 0) {
                    putIfFree(canvas, x, top + 1, z, Blocks.slab(slab(random)));
                }
            }
            // Foot steps: open ground beside a capped lowest stone gets a stair up onto it (else it is a step and a
            // half high).
            for (Map.Entry<Long, Integer> entry : tops.entrySet()) {
                int x = x(entry.getKey());
                int z = z(entry.getKey());
                if (entry.getValue() != GROUND + 1 || KeepKit.radius(x, z) >= EDGE) {
                    continue;
                }
                boolean capped = !isFree(canvas, x, FLOOR + 1, z);
                for (BlockFace face : SIDES) {
                    int nx = x + face.getModX();
                    int nz = z + face.getModZ();
                    if (contains(nx, nz) || KeepKit.radius(nx, nz) >= EDGE || !isFree(canvas, nx, FLOOR, nz)
                            || !isFree(canvas, nx, FLOOR + 1, nz) || !isFree(canvas, nx, FLOOR + 2, nz)
                            || !isFree(canvas, nx, FLOOR + 3, nz)) {
                        continue;
                    }
                    if (capped) {
                        canvas.set(nx, FLOOR, nz, Blocks.stairs(stairs(random), face.getOppositeFace()));
                    }
                }
            }
        }

        private static long key(int x, int z) {
            return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
        }

        private static int x(long key) {
            return (int) (key >> 32);
        }

        private static int z(long key) {
            return (int) key;
        }
    }
}
