package io.github.sx2xoox.mcpl.core.map.citadel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import io.github.sx2xoox.mcpl.core.map.BlockSpec;
import io.github.sx2xoox.mcpl.core.map.Blocks;
import io.github.sx2xoox.mcpl.core.map.MapCanvas;
import org.bukkit.Axis;
import org.bukkit.Material;
import org.jspecify.annotations.Nullable;

/**
 * The graveyard district's drawing kit: writes that silently skip columns outside the zone, the zone's ground height
 * map, what each column is used for (so graves and trees keep off paths and buildings), weighted palettes, smooth
 * noise and the small shapes every part of the district uses (stepped ruins, rubble cones, hanging lanterns).
 *
 * <p>Coordinates are map-local ({@code -z} is north). All randomness comes from the canvas seed.
 */
final class GraveyardBuilder {

    /** Top of the untouched ground. */
    static final int GROUND = CitadelLayout.GROUND_Y;

    /** Bounding box of the district's columns. */
    static final int MIN_X = -50;
    static final int MAX_X = 50;
    static final int MIN_Z = -97;
    static final int MAX_Z = -30;

    /** What a column is used for. */
    enum Use { FREE, PATH, STRUCTURE, GRAVE, TREE }

    final MapCanvas canvas;
    final Random random;
    private final int noiseSalt;
    private final int[] height;
    private final Use[] use;

    GraveyardBuilder(MapCanvas canvas, String name) {
        this.canvas = canvas;
        this.random = canvas.random(name);
        this.noiseSalt = canvas.random(name + "-noise").nextInt();
        int size = (MAX_X - MIN_X + 1) * (MAX_Z - MIN_Z + 1);
        this.height = new int[size];
        this.use = new Use[size];
        Arrays.fill(height, GROUND);
        Arrays.fill(use, Use.FREE);
    }

    // ------------------------------------------------------------------ zone shape

    /** True if the district may write the column. */
    boolean inside(int x, int z) {
        return x >= MIN_X && x <= MAX_X && z >= MIN_Z && z <= MAX_Z && canvas.canWrite(x, z);
    }

    /** Distance from the column to the nearest edge of the district (ring road, radial roads, outer ring). */
    static double margin(int x, int z) {
        double r = CitadelLayout.radius(x, z);
        double roads = Math.min(CitadelLayout.roadDistance(x, z, 240), CitadelLayout.roadDistance(x, z, 300));
        return Math.min(Math.min(r - CitadelLayout.RING_ROAD_OUTER, CitadelLayout.DISTRICT_OUTER - r),
                roads - CitadelLayout.ROAD_HALF_WIDTH);
    }

    private int index(int x, int z) {
        return (x - MIN_X) * (MAX_Z - MIN_Z + 1) + (z - MIN_Z);
    }

    private boolean inBox(int x, int z) {
        return x >= MIN_X && x <= MAX_X && z >= MIN_Z && z <= MAX_Z;
    }

    // ------------------------------------------------------------------ ground and column use

    /** The y of the ground's top block in the column (the grass, path or mound top). */
    int ground(int x, int z) {
        return inBox(x, z) ? height[index(x, z)] : GROUND;
    }

    /** Raises (or keeps) the ground of a column to {@code top}: dirt below, {@code surface} on top. */
    void raiseGround(int x, int z, int top, BlockSpec surface) {
        if (!inside(x, z) || top <= ground(x, z)) {
            return;
        }
        for (int y = GROUND; y < top; y++) {
            canvas.set(x, y, z, BlockSpec.of(Material.DIRT));
        }
        canvas.set(x, top, z, surface);
        height[index(x, z)] = top;
    }

    /** Records the ground top of a column that a builder reshaped itself. */
    void setGround(int x, int z, int top) {
        if (inBox(x, z)) {
            height[index(x, z)] = top;
        }
    }

    Use use(int x, int z) {
        return inBox(x, z) ? use[index(x, z)] : Use.STRUCTURE;
    }

    void mark(int x, int z, Use what) {
        if (inBox(x, z)) {
            use[index(x, z)] = what;
        }
    }

    /** Marks a box of columns (inclusive, any order). */
    void mark(int x1, int z1, int x2, int z2, Use what) {
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
            for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
                mark(x, z, what);
            }
        }
    }

    /** True if the column is inside the zone, at least {@code edge} from its border and not used yet. */
    boolean free(int x, int z, double edge) {
        return inside(x, z) && use(x, z) == Use.FREE && margin(x, z) >= edge;
    }

    // ------------------------------------------------------------------ safe writes

    void set(int x, int y, int z, BlockSpec spec) {
        if (inside(x, z)) {
            canvas.set(x, y, z, spec);
        }
    }

    void set(int x, int y, int z, Material material) {
        set(x, y, z, BlockSpec.of(material));
    }

    void clear(int x, int y, int z) {
        set(x, y, z, BlockSpec.AIR);
    }

    @Nullable BlockSpec get(int x, int y, int z) {
        return canvas.get(x, y, z);
    }

    boolean isAir(int x, int y, int z) {
        BlockSpec spec = canvas.get(x, y, z);
        return spec == null || spec.isAir();
    }

    void fill(int x1, int y1, int z1, int x2, int y2, int z2, BlockSpec spec) {
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
            for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
                for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++) {
                    set(x, y, z, spec);
                }
            }
        }
    }

    void clearBox(int x1, int y1, int z1, int x2, int y2, int z2) {
        fill(x1, y1, z1, x2, y2, z2, BlockSpec.AIR);
    }

    // ------------------------------------------------------------------ randomness

    boolean chance(double p) {
        return random.nextDouble() < p;
    }

    <T> T pick(T[] options) {
        return options[random.nextInt(options.length)];
    }

    /** Smooth value noise in [0, 1) with features about {@code scale} blocks wide. */
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

    /** A fixed pseudo-random value in [0, 1) for a position (the same on every build). */
    double hash(int x, int z, int salt) {
        long h = x * 0x9E3779B97F4A7C15L ^ z * 0xC2B2AE3D27D4EB4FL ^ (salt + noiseSalt) * 0x165667B19E3779F9L;
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        h *= 0xC4CEB9FE1A85EC53L;
        h ^= h >>> 33;
        return (h >>> 11) * 0x1.0p-53;
    }

    private static double smooth(double t) {
        return t * t * (3 - 2 * t);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    // ------------------------------------------------------------------ shapes

    /**
     * A ruined wall from {@code (x1,z1)} to {@code (x2,z2)} (a straight horizontal line) standing on the ground at
     * {@code baseY}: its top follows a broken profile that starts and ends one block high and never changes by more
     * than one block between neighbours, so its top can always be climbed like a stair.
     */
    void ruinedWall(int x1, int z1, int x2, int z2, int baseY, int maxHeight, Palette palette, int salt) {
        int steps = Math.max(Math.abs(x2 - x1), Math.abs(z2 - z1));
        int[] tops = new int[steps + 1];
        for (int i = 0; i <= steps; i++) {
            int fromEnd = Math.min(i, steps - i) + 1;
            int wanted = 1 + (int) Math.round((maxHeight - 1) * noise(i * 1.0, salt * 7.0, 3.5, salt));
            tops[i] = Math.max(1, Math.min(Math.min(maxHeight, fromEnd), wanted));
        }
        smoothProfile(tops);
        for (int i = 0; i <= steps; i++) {
            double t = steps == 0 ? 0 : (double) i / steps;
            int x = (int) Math.round(x1 + (x2 - x1) * t);
            int z = (int) Math.round(z1 + (z2 - z1) * t);
            for (int y = baseY + 1; y <= baseY + tops[i]; y++) {
                set(x, y, z, palette.pick(random));
            }
        }
    }

    /** Lowers values so neighbours differ by at most one (a profile that can be walked like a stair). */
    static void smoothProfile(int[] tops) {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = 0; i < tops.length; i++) {
                int left = i > 0 ? tops[i - 1] : Integer.MAX_VALUE;
                int right = i < tops.length - 1 ? tops[i + 1] : Integer.MAX_VALUE;
                int limit = Math.min(left, right) + 1;
                if (tops[i] > limit) {
                    tops[i] = limit;
                    changed = true;
                }
            }
        }
    }

    /**
     * A heap of rubble around {@code cx,cz} on the ground at {@code baseY}: {@code height} blocks in the middle and
     * one block lower per block outwards (so it can be climbed from every side), capped with slabs at the rim.
     */
    void rubble(int cx, int cz, int baseY, double height, Palette palette) {
        int r = (int) Math.ceil(height);
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                double d = Math.sqrt(dx * dx + dz * dz);
                double jitter = hash(cx + dx, cz + dz, 911) * 0.6;
                int h = (int) Math.floor(height - d + jitter * 0.5);
                if (h <= 0) {
                    if (h == 0 && chance(0.5) && isAir(cx + dx, baseY + 1, cz + dz)) {
                        set(cx + dx, baseY + 1, cz + dz, pick(RUBBLE_SLABS));
                    }
                    continue;
                }
                for (int y = baseY + 1; y <= baseY + h; y++) {
                    if (isAir(cx + dx, y, cz + dz)) {
                        set(cx + dx, y, cz + dz, palette.pick(random));
                    }
                }
            }
        }
    }

    /** A log lying along {@code axis} from {@code x,y,z}, {@code length} blocks (a fallen column or tree). */
    void lying(int x, int y, int z, Axis axis, int length, Material material) {
        for (int i = 0; i < length; i++) {
            int lx = axis == Axis.X ? x + i : x;
            int lz = axis == Axis.Z ? z + i : z;
            set(lx, y, lz, Blocks.log(material, axis));
        }
    }

    /** A soul lantern hanging {@code chain} blocks below the block at {@code y} (chain, then the lantern). */
    void hangingLantern(int x, int y, int z, int chain, boolean soul) {
        for (int i = 1; i <= chain; i++) {
            set(x, y - i, z, Blocks.log(Material.IRON_CHAIN, Axis.Y));
        }
        set(x, y - chain - 1, z, Blocks.lantern(soul, true));
    }

    // ------------------------------------------------------------------ palettes

    /** A weighted list of blocks. */
    static final class Palette {

        private final BlockSpec[] specs;

        private Palette(BlockSpec[] specs) {
            this.specs = specs;
        }

        /** {@code entries} alternates materials and weights: {@code of(STONE, 3, COBBLESTONE, 1)}. */
        static Palette of(Object... entries) {
            List<BlockSpec> list = new ArrayList<>();
            for (int i = 0; i < entries.length; i += 2) {
                BlockSpec spec = entries[i] instanceof Material material ? BlockSpec.of(material)
                        : (BlockSpec) entries[i];
                int weight = (Integer) entries[i + 1];
                for (int w = 0; w < weight; w++) {
                    list.add(spec);
                }
            }
            return new Palette(list.toArray(BlockSpec[]::new));
        }

        BlockSpec pick(Random random) {
            return specs[random.nextInt(specs.length)];
        }
    }

    static final Palette STONE_RUIN = Palette.of(Material.STONE_BRICKS, 6, Material.MOSSY_STONE_BRICKS, 4,
            Material.CRACKED_STONE_BRICKS, 3, Material.COBBLESTONE, 1, Material.MOSSY_COBBLESTONE, 2,
            Material.ANDESITE, 1);

    static final Palette RUBBLE = Palette.of(Material.COBBLESTONE, 3, Material.MOSSY_COBBLESTONE, 3,
            Material.CRACKED_STONE_BRICKS, 2, Material.STONE_BRICKS, 1, Material.COBBLED_DEEPSLATE, 2,
            Material.ANDESITE, 1, Material.TUFF, 1);

    static final BlockSpec[] RUBBLE_SLABS = {
            Blocks.slab(Material.COBBLESTONE_SLAB), Blocks.slab(Material.MOSSY_COBBLESTONE_SLAB),
            Blocks.slab(Material.STONE_BRICK_SLAB), Blocks.slab(Material.COBBLED_DEEPSLATE_SLAB),
            Blocks.slab(Material.ANDESITE_SLAB), Blocks.slab(Material.MOSSY_STONE_BRICK_SLAB)};
}
