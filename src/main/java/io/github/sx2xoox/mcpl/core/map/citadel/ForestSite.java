package io.github.sx2xoox.mcpl.core.map.citadel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import io.github.sx2xoox.mcpl.core.map.BlockSpec;
import io.github.sx2xoox.mcpl.core.map.MapBlocks;
import io.github.sx2xoox.mcpl.core.map.MapCanvas;
import org.bukkit.Material;
import org.jspecify.annotations.Nullable;

/**
 * Shared state of the forest build: writes clipped to the district, the terrain height map, what each column is used
 * for (so trees, rocks and buildings never collide), deterministic noise, and the tools that keep raised surfaces
 * walkable (every top surface a player can land on rises at most one block per column).
 */
final class ForestSite {

    static final int MIN_X = -52;
    static final int MAX_X = 52;
    static final int MIN_Z = 30;
    static final int MAX_Z = 98;
    private static final int WIDTH = MAX_X - MIN_X + 1;
    private static final int DEPTH = MAX_Z - MIN_Z + 1;

    /** Column uses: trees and rocks only go on {@link #FREE} columns. */
    static final byte FREE = 0;
    static final byte TRAIL = 1;
    static final byte CLEARING = 2;
    static final byte BUILT = 3;
    static final byte TRUNK = 4;
    static final byte ROCK = 5;
    static final byte WATER = 6;

    static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    final MapCanvas canvas;
    final Random random;
    private final long noiseSeed;
    private final int[] ground = new int[WIDTH * DEPTH];
    private final byte[] use = new byte[WIDTH * DEPTH];
    private final ForestCanopy canopy;

    ForestSite(MapCanvas canvas, Random random) {
        this.canvas = canvas;
        this.random = random;
        this.noiseSeed = random.nextLong();
        Arrays.fill(ground, CitadelLayout.GROUND_Y);
        this.canopy = new ForestCanopy(this);
    }

    ForestCanopy canopy() {
        return canopy;
    }

    // ------------------------------------------------------------------ columns

    static boolean inBox(int x, int z) {
        return x >= MIN_X && x <= MAX_X && z >= MIN_Z && z <= MAX_Z;
    }

    static int index(int x, int z) {
        return (x - MIN_X) * DEPTH + (z - MIN_Z);
    }

    static int columnX(int index) {
        return index / DEPTH + MIN_X;
    }

    static int columnZ(int index) {
        return index % DEPTH + MIN_Z;
    }

    static int columns() {
        return WIDTH * DEPTH;
    }

    /** True for the district's own columns (the only ones this build may write). */
    boolean inZone(int x, int z) {
        return inBox(x, z) && canvas.canWrite(x, z);
    }

    /**
     * Distance from the column to the district's border: the ring road, the outer edge and the two radial roads.
     * Negative outside.
     */
    static double edge(int x, int z) {
        double r = CitadelLayout.radius(x, z);
        double d = Math.min(r - CitadelLayout.RING_ROAD_OUTER, CitadelLayout.DISTRICT_OUTER - r);
        d = Math.min(d, CitadelLayout.roadDistance(x, z, 60) - CitadelLayout.ROAD_HALF_WIDTH);
        d = Math.min(d, CitadelLayout.roadDistance(x, z, 120) - CitadelLayout.ROAD_HALF_WIDTH);
        return d;
    }

    /** The y of the column's ground surface (top of the terrain, before anything stands on it). */
    int ground(int x, int z) {
        return inBox(x, z) ? ground[index(x, z)] : CitadelLayout.GROUND_Y;
    }

    void setGround(int x, int z, int y) {
        if (inBox(x, z)) {
            ground[index(x, z)] = y;
        }
    }

    byte use(int x, int z) {
        return inBox(x, z) ? use[index(x, z)] : BUILT;
    }

    void mark(int x, int z, byte kind) {
        if (inBox(x, z)) {
            use[index(x, z)] = kind;
        }
    }

    /** Marks the disk with {@code kind} where the column is still free (or always, if {@code force}). */
    void markDisk(double cx, double cz, double radius, byte kind, boolean force) {
        int r = (int) Math.ceil(radius);
        for (int x = (int) Math.floor(cx) - r; x <= (int) Math.ceil(cx) + r; x++) {
            for (int z = (int) Math.floor(cz) - r; z <= (int) Math.ceil(cz) + r; z++) {
                if (sq(x - cx) + sq(z - cz) <= radius * radius && (force || use(x, z) == FREE)) {
                    mark(x, z, kind);
                }
            }
        }
    }

    /** True if no neighbouring column holds a building, a rock or water (a root keeps that much distance). */
    boolean openAround(int x, int z) {
        for (int[] side : SIDES) {
            byte kind = use(x + side[0], z + side[1]);
            if (!inZone(x + side[0], z + side[1]) || kind == BUILT || kind == ROCK || kind == WATER) {
                return false;
            }
        }
        return true;
    }

    /** True for columns an earthen mound may cover (open ground, roots, trails). */
    boolean isMoundable(int x, int z) {
        byte kind = use(x, z);
        return inZone(x, z) && (kind == FREE || kind == TRUNK || kind == TRAIL || kind == CLEARING);
    }

    /** True if every column of the disk is inside the district and free. */
    boolean isFree(double cx, double cz, double radius) {
        int r = (int) Math.ceil(radius);
        for (int x = (int) Math.floor(cx) - r; x <= (int) Math.ceil(cx) + r; x++) {
            for (int z = (int) Math.floor(cz) - r; z <= (int) Math.ceil(cz) + r; z++) {
                if (sq(x - cx) + sq(z - cz) <= radius * radius && (!inZone(x, z) || use(x, z) != FREE)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** True if no column of the disk is used for {@code kind}. */
    boolean avoids(double cx, double cz, double radius, byte kind) {
        int r = (int) Math.ceil(radius);
        for (int x = (int) Math.floor(cx) - r; x <= (int) Math.ceil(cx) + r; x++) {
            for (int z = (int) Math.floor(cz) - r; z <= (int) Math.ceil(cz) + r; z++) {
                if (sq(x - cx) + sq(z - cz) <= radius * radius && use(x, z) == kind) {
                    return false;
                }
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ blocks

    /** Sets a block if its column belongs to the district (writes outside are silently dropped). */
    void set(int x, int y, int z, BlockSpec spec) {
        if (inZone(x, z) && y >= canvas.minY() && y <= canvas.maxY()) {
            canvas.set(x, y, z, spec);
        }
    }

    void set(int x, int y, int z, Material material) {
        set(x, y, z, BlockSpec.of(material));
    }

    void clear(int x, int y, int z) {
        if (inZone(x, z) && canvas.get(x, y, z) != null) {
            canvas.clear(x, y, z);
        }
    }

    @Nullable BlockSpec get(int x, int y, int z) {
        return canvas.get(x, y, z);
    }

    boolean isAir(int x, int y, int z) {
        BlockSpec spec = canvas.get(x, y, z);
        return spec == null || spec.isAir();
    }

    /** True for air and blocks a player walks through (plants, flowers, carpets of leaves). */
    boolean isOpen(int x, int y, int z) {
        BlockSpec spec = canvas.get(x, y, z);
        return spec == null || MapBlocks.isPassable(spec);
    }

    /** Highest non-air block of the column (below the map if none). */
    int top(int x, int z) {
        for (int y = canvas.maxY(); y >= canvas.minY(); y--) {
            if (!isAir(x, y, z)) {
                return y;
            }
        }
        return canvas.minY() - 1;
    }

    /**
     * Top of the solid stack standing on the column's ground (the ground itself if nothing solid stands on it),
     * ignoring anything above a gap, such as tree crowns.
     */
    int stackTop(int x, int z) {
        int y = ground(x, z);
        while (y < canvas.maxY() && !isOpen(x, y + 1, z)) {
            y++;
        }
        return y;
    }

    /** True if no neighbouring column's solid stack is lower than {@code y} (a carpet there adds no step). */
    boolean isLevel(int x, int z, int y) {
        for (int[] side : SIDES) {
            if (stackTop(x + side[0], z + side[1]) < y) {
                return false;
            }
        }
        return true;
    }

    /** True if nothing but air and plants stands on the column's ground. */
    boolean isBareGround(int x, int z) {
        int g = ground(x, z);
        BlockSpec at = get(x, g + 1, z);
        return at == null || at.isAir() || MapBlocks.isPassable(at) && !at.material().name().contains("DOOR");
    }

    // ------------------------------------------------------------------ randomness

    double range(double min, double max) {
        return min + random.nextDouble() * (max - min);
    }

    int range(int min, int max) {
        return min + random.nextInt(max - min + 1);
    }

    boolean chance(double p) {
        return random.nextDouble() < p;
    }

    /** Smooth value noise in [0, 1] with features about {@code scale} blocks wide; {@code salt} picks a layer. */
    double noise(double x, double z, double scale, int salt) {
        double fx = x / scale;
        double fz = z / scale;
        int ix = (int) Math.floor(fx);
        int iz = (int) Math.floor(fz);
        double tx = smooth(fx - ix);
        double tz = smooth(fz - iz);
        double a = hash(ix, iz, salt);
        double b = hash(ix + 1, iz, salt);
        double c = hash(ix, iz + 1, salt);
        double d = hash(ix + 1, iz + 1, salt);
        return lerp(lerp(a, b, tx), lerp(c, d, tx), tz);
    }

    /** A per-column random value in [0, 1) that does not depend on build order. */
    double hash(int x, int z, int salt) {
        long h = noiseSeed ^ x * 0x9E3779B97F4A7C15L ^ z * 0xC2B2AE3D27D4EB4FL ^ salt * 0x165667B19E3779F9L;
        h = (h ^ (h >>> 33)) * 0xFF51AFD7ED558CCDL;
        h = (h ^ (h >>> 33)) * 0xC4CEB9FE1A85EC53L;
        h ^= h >>> 33;
        return (h >>> 11) * 0x1.0p-53;
    }

    private static double smooth(double t) {
        return t * t * (3 - 2 * t);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    static double sq(double v) {
        return v * v;
    }

    // ------------------------------------------------------------------ walkable mounds

    /** Supplies the block that raises a column at height {@code y} ({@code top}: the new top block). */
    @FunctionalInterface
    interface Filler {
        BlockSpec at(int x, int y, int z, boolean top);
    }

    /** How high a column stands for {@link #raiseToSlope}. */
    @FunctionalInterface
    interface Surface {
        int height(int x, int z);
    }

    /**
     * Raises the columns of {@code region} (indices of {@link #index}) so that neighbouring columns of the region differ
     * by at most one block: the lowest envelope of slopes of one block per column through every column's current
     * {@code surface}. A region that touches the ground with tops at ground level becomes a walkable mound. Columns
     * in {@code fixed} are never raised (they still pull their neighbours up). Returns the number of blocks added.
     */
    int raiseToSlope(boolean[] region, boolean @Nullable [] fixed, Surface surface, Filler filler) {
        int n = columns();
        int[] top = new int[n];
        int[] height = new int[n];
        int minY = canvas.minY();
        int maxY = canvas.maxY();
        List<List<Integer>> buckets = new ArrayList<>();
        for (int y = 0; y <= maxY - minY + 1; y++) {
            buckets.add(new ArrayList<>());
        }
        for (int i = 0; i < n; i++) {
            if (!region[i]) {
                continue;
            }
            top[i] = surface.height(columnX(i), columnZ(i));
            height[i] = top[i];
            buckets.get(Math.max(0, top[i] - minY)).add(i);
        }
        for (int level = buckets.size() - 1; level > 0; level--) {
            List<Integer> bucket = buckets.get(level);
            for (int b = 0; b < bucket.size(); b++) {
                int i = bucket.get(b);
                if (height[i] - minY != level) {
                    continue;
                }
                int x = columnX(i);
                int z = columnZ(i);
                for (int[] side : SIDES) {
                    int nx = x + side[0];
                    int nz = z + side[1];
                    if (!inBox(nx, nz)) {
                        continue;
                    }
                    int j = index(nx, nz);
                    if (!region[j] || fixed != null && fixed[j] || height[j] >= height[i] - 1) {
                        continue;
                    }
                    height[j] = height[i] - 1;
                    buckets.get(level - 1).add(j);
                }
            }
        }
        int added = 0;
        for (int i = 0; i < n; i++) {
            if (!region[i] || height[i] <= top[i]) {
                continue;
            }
            int x = columnX(i);
            int z = columnZ(i);
            for (int y = top[i] + 1; y <= height[i]; y++) {
                set(x, y, z, filler.at(x, y, z, y == height[i]));
                added++;
            }
        }
        return added;
    }
}
