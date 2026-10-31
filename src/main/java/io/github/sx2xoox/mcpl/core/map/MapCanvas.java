package io.github.sx2xoox.mcpl.core.map;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Predicate;

import org.bukkit.Material;
import org.jspecify.annotations.Nullable;

/**
 * The blocks of an arena map, built in memory before they are placed in a world (see {@link MapBuilder}).
 *
 * <p>Coordinates are map-local: {@code x} and {@code z} are relative to the map center (block {@code 0,0} is the
 * center column), {@code y} is the absolute world height. Positions that were never set are air (map worlds are void).
 *
 * <p>A canvas can be {@linkplain #restrictTo restricted} to a region: the view shares the blocks but refuses writes
 * outside its columns, so the builders of different map zones can never overwrite each other. Reads are never
 * restricted.
 *
 * <p>Typical use:
 * <pre>{@code
 * canvas.fill(-3, 64, -3, 3, 64, 3, BlockSpec.of(Material.STONE_BRICKS));   // a 7x7 floor
 * canvas.cylinder(10, 0, 65, 72, 3, Blocks.of(Material.COBBLESTONE), true); // a hollow tower
 * canvas.finish();                                                         // fence/wall/pane connections
 * }</pre>
 */
public final class MapCanvas {

    /** Which columns a restricted canvas may write. */
    @FunctionalInterface
    public interface Region {
        boolean contains(int x, int z);
    }

    /** Receives every block of the canvas. */
    @FunctionalInterface
    public interface BlockVisitor {
        void visit(int x, int y, int z, BlockSpec spec);
    }

    private final Store store;
    private final @Nullable Region region;
    private final String regionName;

    /** An empty canvas for blocks with {@code |x|, |z| <= radius} and {@code minY <= y <= maxY}. */
    public MapCanvas(int radius, int minY, int maxY, long seed) {
        this(new Store(radius, minY, maxY, seed), null, "map");
    }

    private MapCanvas(Store store, @Nullable Region region, String regionName) {
        this.store = store;
        this.region = region;
        this.regionName = regionName;
    }

    /**
     * A view of this canvas that may only write columns inside {@code region} (and inside this view's own region).
     * {@code name} appears in the error when a write falls outside.
     */
    public MapCanvas restrictTo(Region region, String name) {
        Region outer = this.region;
        Region combined = outer == null ? region : (x, z) -> outer.contains(x, z) && region.contains(x, z);
        return new MapCanvas(store, combined, name);
    }

    public boolean canWrite(int x, int z) {
        return inBounds(x, store.minY, z) && (region == null || region.contains(x, z));
    }

    public int radius() {
        return store.radius;
    }

    public int minY() {
        return store.minY;
    }

    public int maxY() {
        return store.maxY;
    }

    /**
     * A random generator for {@code salt} (for example a zone name), the same for every build of the map, so maps are
     * reproducible.
     */
    public Random random(String salt) {
        return new Random(store.seed * 31 + salt.hashCode());
    }

    // ------------------------------------------------------------------ single blocks

    public void set(int x, int y, int z, BlockSpec spec) {
        checkWrite(x, y, z);
        store.put(x, y, z, spec);
    }

    public void set(int x, int y, int z, Material material) {
        set(x, y, z, BlockSpec.of(material));
    }

    /** Sets the block only where nothing has been set yet. */
    public void setIfEmpty(int x, int y, int z, BlockSpec spec) {
        if (get(x, y, z) == null) {
            set(x, y, z, spec);
        }
    }

    /** Explicitly air (carves out a block set before). */
    public void clear(int x, int y, int z) {
        set(x, y, z, BlockSpec.AIR);
    }

    /** The block at a position, null if never set (air). */
    public @Nullable BlockSpec get(int x, int y, int z) {
        if (!inBounds(x, y, z)) {
            return null;
        }
        return store.get(x, y, z);
    }

    /** True if the position holds a non-air block. */
    public boolean isSolid(int x, int y, int z) {
        BlockSpec spec = get(x, y, z);
        return spec != null && !spec.isAir();
    }

    /**
     * The y of the highest block a player can stand on in column {@code x,z} (full cubes, slabs, stairs, ...), or
     * {@code minY - 1} if there is none.
     */
    public int surfaceY(int x, int z) {
        for (int y = store.maxY; y >= store.minY; y--) {
            BlockSpec spec = get(x, y, z);
            if (spec != null && MapBlocks.isStandable(spec)) {
                return y;
            }
        }
        return store.minY - 1;
    }

    // ------------------------------------------------------------------ shapes

    /** Fills the box between two corners (inclusive, any order). */
    public void fill(int x1, int y1, int z1, int x2, int y2, int z2, BlockSpec spec) {
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
            for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
                for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++) {
                    set(x, y, z, spec);
                }
            }
        }
    }

    /** The shell of the box between two corners: four walls, no floor or roof. */
    public void walls(int x1, int y1, int z1, int x2, int y2, int z2, BlockSpec spec) {
        int minX = Math.min(x1, x2);
        int maxX = Math.max(x1, x2);
        int minZ = Math.min(z1, z2);
        int maxZ = Math.max(z1, z2);
        fill(minX, y1, minZ, maxX, y2, minZ, spec);
        fill(minX, y1, maxZ, maxX, y2, maxZ, spec);
        fill(minX, y1, minZ, minX, y2, maxZ, spec);
        fill(maxX, y1, minZ, maxX, y2, maxZ, spec);
    }

    /** Replaces every set block in the box that matches {@code what} with {@code spec}. */
    public void replace(int x1, int y1, int z1, int x2, int y2, int z2, Predicate<BlockSpec> what, BlockSpec spec) {
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
            for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
                for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++) {
                    BlockSpec current = get(x, y, z);
                    if (current != null && what.test(current)) {
                        set(x, y, z, spec);
                    }
                }
            }
        }
    }

    /** A horizontal disk (filled circle) of {@code radius} around {@code cx,cz} at height {@code y}. */
    public void disk(int cx, int y, int cz, double radius, BlockSpec spec) {
        int r = (int) Math.ceil(radius);
        double limit = (radius + 0.5) * (radius + 0.5);
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz <= limit) {
                    set(cx + dx, y, cz + dz, spec);
                }
            }
        }
    }

    /** A vertical cylinder from {@code y1} to {@code y2}; {@code hollow} keeps only its one-block-thick wall. */
    public void cylinder(int cx, int cz, int y1, int y2, double radius, BlockSpec spec, boolean hollow) {
        int r = (int) Math.ceil(radius);
        double outer = (radius + 0.5) * (radius + 0.5);
        double inner = (radius - 0.5) * (radius - 0.5);
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int d2 = dx * dx + dz * dz;
                if (d2 > outer || hollow && d2 < inner) {
                    continue;
                }
                for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++) {
                    set(cx + dx, y, cz + dz, spec);
                }
            }
        }
    }

    /** A filled ellipsoid with radii {@code rx, ry, rz}. */
    public void ellipsoid(int cx, int cy, int cz, double rx, double ry, double rz, BlockSpec spec) {
        int ix = (int) Math.ceil(rx);
        int iy = (int) Math.ceil(ry);
        int iz = (int) Math.ceil(rz);
        for (int dx = -ix; dx <= ix; dx++) {
            for (int dy = -iy; dy <= iy; dy++) {
                for (int dz = -iz; dz <= iz; dz++) {
                    double n = sq(dx / (rx + 0.5)) + sq(dy / (ry + 0.5)) + sq(dz / (rz + 0.5));
                    if (n <= 1.0) {
                        set(cx + dx, cy + dy, cz + dz, spec);
                    }
                }
            }
        }
    }

    /** A straight line of blocks between two points (inclusive). */
    public void line(int x1, int y1, int z1, int x2, int y2, int z2, BlockSpec spec) {
        int steps = Math.max(Math.abs(x2 - x1), Math.max(Math.abs(y2 - y1), Math.abs(z2 - z1)));
        for (int i = 0; i <= steps; i++) {
            double t = steps == 0 ? 0 : (double) i / steps;
            set((int) Math.round(x1 + (x2 - x1) * t), (int) Math.round(y1 + (y2 - y1) * t),
                    (int) Math.round(z1 + (z2 - z1) * t), spec);
        }
    }

    private static double sq(double v) {
        return v * v;
    }

    // ------------------------------------------------------------------ whole canvas

    /** Every non-air block (explicit air is skipped), in no particular order. */
    public void forEach(BlockVisitor visitor) {
        store.forEach((x, y, z, spec) -> {
            if (!spec.isAir()) {
                visitor.visit(x, y, z, spec);
            }
        });
    }

    /** Number of non-air blocks. */
    public int blockCount() {
        int[] count = {0};
        forEach((x, y, z, spec) -> count[0]++);
        return count[0];
    }

    /**
     * Finishes the map: computes the connections of fences, walls, panes and bars from their neighbours (as the game
     * would when they are placed by hand). Call once after everything is built; {@link MapBuilder} expects it.
     */
    public void finish() {
        List<long[]> updates = new ArrayList<>();
        Map<Long, BlockSpec> changed = new HashMap<>();
        store.forEach((x, y, z, spec) -> {
            MapBlocks.Family family = MapBlocks.family(spec.material());
            if (family == MapBlocks.Family.NONE) {
                return;
            }
            int mask = 0;
            mask |= connects(family, get(x, y, z - 1)) ? BlockSpec.NORTH : 0;
            mask |= connects(family, get(x + 1, y, z)) ? BlockSpec.EAST : 0;
            mask |= connects(family, get(x, y, z + 1)) ? BlockSpec.SOUTH : 0;
            mask |= connects(family, get(x - 1, y, z)) ? BlockSpec.WEST : 0;
            boolean up = false;
            if (family == MapBlocks.Family.WALL) {
                // Vanilla: a wall shows its post unless it runs straight through (or something sits on top).
                boolean straightNs = mask == (BlockSpec.NORTH | BlockSpec.SOUTH);
                boolean straightEw = mask == (BlockSpec.EAST | BlockSpec.WEST);
                BlockSpec above = get(x, y + 1, z);
                up = !(straightNs || straightEw) || above != null && !above.isAir();
            }
            if (mask != spec.connections() || up != spec.up()) {
                changed.put(Store.key(x, y, z), spec.connected(mask, up));
                updates.add(new long[] {x, y, z});
            }
        });
        for (long[] at : updates) {
            int x = (int) at[0];
            int y = (int) at[1];
            int z = (int) at[2];
            store.put(x, y, z, changed.get(Store.key(x, y, z)));
        }
    }

    private static boolean connects(MapBlocks.Family family, @Nullable BlockSpec neighbour) {
        if (neighbour == null || neighbour.isAir()) {
            return false;
        }
        MapBlocks.Family other = MapBlocks.family(neighbour.material());
        if (other == family) {
            return true;
        }
        if (family == MapBlocks.Family.FENCE && neighbour.material().name().endsWith("_FENCE_GATE")) {
            return true;
        }
        if (family == MapBlocks.Family.WALL && other == MapBlocks.Family.PANE
                || family == MapBlocks.Family.PANE && other == MapBlocks.Family.WALL) {
            return true;
        }
        return MapBlocks.isFullCube(neighbour);
    }

    private boolean inBounds(int x, int y, int z) {
        return Math.abs(x) <= store.radius && Math.abs(z) <= store.radius && y >= store.minY && y <= store.maxY;
    }

    private void checkWrite(int x, int y, int z) {
        if (!inBounds(x, y, z)) {
            throw new IllegalArgumentException("block " + x + "," + y + "," + z + " is outside the map (radius "
                    + store.radius + ", y " + store.minY + ".." + store.maxY + ")");
        }
        if (region != null && !region.contains(x, z)) {
            throw new IllegalStateException(regionName + " may not write column " + x + "," + z
                    + " (outside its region)");
        }
    }

    // ------------------------------------------------------------------ storage

    /** Open-addressing map from packed positions to palette indices (a million blocks fit in a few MB). */
    private static final class Store {

        private static final long EMPTY = Long.MIN_VALUE;

        final int radius;
        final int minY;
        final int maxY;
        final long seed;
        private final List<BlockSpec> palette = new ArrayList<>();
        private final Map<BlockSpec, Integer> paletteIndex = new HashMap<>();
        private long[] keys = new long[1 << 16];
        private int[] values = new int[1 << 16];
        private int size;

        Store(int radius, int minY, int maxY, long seed) {
            this.radius = radius;
            this.minY = minY;
            this.maxY = maxY;
            this.seed = seed;
            Arrays.fill(keys, EMPTY);
        }

        static long key(int x, int y, int z) {
            return ((long) (x + 32768) << 40) | ((long) (z + 32768) << 20) | (y + 32768);
        }

        void put(int x, int y, int z, BlockSpec spec) {
            Integer index = paletteIndex.get(spec);
            if (index == null) {
                index = palette.size();
                palette.add(spec);
                paletteIndex.put(spec, index);
            }
            if ((size + 1) * 2 > keys.length) {
                grow();
            }
            long key = key(x, y, z);
            int slot = slot(key, keys.length);
            while (keys[slot] != EMPTY && keys[slot] != key) {
                slot = (slot + 1) & (keys.length - 1);
            }
            if (keys[slot] == EMPTY) {
                keys[slot] = key;
                size++;
            }
            values[slot] = index;
        }

        @Nullable BlockSpec get(int x, int y, int z) {
            long key = key(x, y, z);
            int slot = slot(key, keys.length);
            while (keys[slot] != EMPTY) {
                if (keys[slot] == key) {
                    return palette.get(values[slot]);
                }
                slot = (slot + 1) & (keys.length - 1);
            }
            return null;
        }

        void forEach(BlockVisitor visitor) {
            for (int i = 0; i < keys.length; i++) {
                long key = keys[i];
                if (key == EMPTY) {
                    continue;
                }
                int x = (int) ((key >>> 40) & 0xFFFFF) - 32768;
                int z = (int) ((key >>> 20) & 0xFFFFF) - 32768;
                int y = (int) (key & 0xFFFFF) - 32768;
                visitor.visit(x, y, z, palette.get(values[i]));
            }
        }

        private void grow() {
            long[] oldKeys = keys;
            int[] oldValues = values;
            keys = new long[oldKeys.length * 2];
            values = new int[oldKeys.length * 2];
            Arrays.fill(keys, EMPTY);
            for (int i = 0; i < oldKeys.length; i++) {
                if (oldKeys[i] == EMPTY) {
                    continue;
                }
                int slot = slot(oldKeys[i], keys.length);
                while (keys[slot] != EMPTY) {
                    slot = (slot + 1) & (keys.length - 1);
                }
                keys[slot] = oldKeys[i];
                values[slot] = oldValues[i];
            }
        }

        private static int slot(long key, int length) {
            long h = key * 0x9E3779B97F4A7C15L;
            return (int) (h >>> 40) & (length - 1);
        }
    }
}
