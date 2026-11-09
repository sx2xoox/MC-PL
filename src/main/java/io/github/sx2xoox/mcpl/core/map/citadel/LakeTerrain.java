package io.github.sx2xoox.mcpl.core.map.citadel;

import java.util.List;

import io.github.sx2xoox.mcpl.core.map.BlockSpec;
import io.github.sx2xoox.mcpl.core.map.MapCanvas;
import org.bukkit.Material;

/**
 * The ground of the lake district: the lake basin with its islands, the sloping banks and the low meadow hills
 * around it. Everything is derived from one "wetness" field: positive inside the water (blocks from the shore),
 * negative on land. Banks are a band of sand, gravel, clay and mud one block below the grass, so a swimmer can always
 * step out of the water; the bed deepens gently to {@link #MAX_DEPTH} blocks.
 *
 * <p>The terrain is computed once into a height map, then written. Later builders ask it where the water is and how
 * high the ground stands.
 */
final class LakeTerrain {

    /** The water surface (the top water block). */
    static final int WATER_Y = CitadelLayout.GROUND_Y - 1;
    static final int GROUND_Y = CitadelLayout.GROUND_Y;
    static final int MAX_DEPTH = 4;

    /** Water never comes closer than this to the zone's edge (roads, ring road, outer ring). */
    private static final double WATER_EDGE_MARGIN = 7.5;
    /** Width of the bank band (blocks of land one below the grass) around the water. */
    private static final double BANK_WIDTH = 2.2;

    /** An ellipse with a noisy outline: positive inside (about blocks from the edge), negative outside. */
    record Blob(double cx, double cz, double along, double across, double rotation) {

        double inside(double x, double z) {
            double rad = Math.toRadians(rotation);
            double dx = x - cx;
            double dz = z - cz;
            double u = dx * Math.cos(rad) + dz * Math.sin(rad);
            double v = -dx * Math.sin(rad) + dz * Math.cos(rad);
            double length = Math.sqrt(u * u + v * v);
            if (length < 1e-6) {
                return Math.min(along, across);
            }
            double cu = u / length;
            double cv = v / length;
            double edge = 1 / Math.sqrt(cu * cu / (along * along) + cv * cv / (across * across));
            return edge - length;
        }
    }

    private final MapCanvas canvas;
    private final LakeNoise noise;
    private final List<Blob> basins;
    private final List<Blob> islands;
    private final List<Blob> hills;
    private final int minX;
    private final int minZ;
    private final int sizeX;
    private final int sizeZ;
    private final double[] wetness;
    private final int[] top;
    private final boolean[] inZone;
    private final boolean[] reserved;

    LakeTerrain(MapCanvas canvas, LakeNoise noise, int minX, int minZ, int maxX, int maxZ, List<Blob> basins,
                List<Blob> islands, List<Blob> hills) {
        this.canvas = canvas;
        this.noise = noise;
        this.basins = basins;
        this.islands = islands;
        this.hills = hills;
        this.minX = minX;
        this.minZ = minZ;
        this.sizeX = maxX - minX + 1;
        this.sizeZ = maxZ - minZ + 1;
        this.wetness = new double[sizeX * sizeZ];
        this.top = new int[sizeX * sizeZ];
        this.inZone = new boolean[sizeX * sizeZ];
        this.reserved = new boolean[sizeX * sizeZ];
    }

    // ------------------------------------------------------------------ queries

    /** True if the column has open lake water (the surface at {@link #WATER_Y}). */
    boolean isWater(int x, int z) {
        int i = index(x, z);
        return i >= 0 && inZone[i] && top[i] < WATER_Y;
    }

    /** Wetness: blocks into the water (positive), or minus blocks from the shore on land. */
    double wetness(int x, int z) {
        int i = index(x, z);
        return i < 0 ? -99 : wetness[i];
    }

    /** The planned top block of the ground (the bed under water). */
    int top(int x, int z) {
        int i = index(x, z);
        return i < 0 ? GROUND_Y : top[i];
    }

    /** True if the column is dry land at the meadow level or above (not bank, not water). */
    boolean isMeadow(int x, int z) {
        int i = index(x, z);
        return i >= 0 && inZone[i] && top[i] >= GROUND_Y;
    }

    /** Distance from the column to the zone's edge (roads, ring road, outer ring); negative outside. */
    static double edgeDistance(int x, int z) {
        double r = CitadelLayout.radius(x, z);
        double d = Math.min(r - CitadelLayout.RING_ROAD_OUTER, CitadelLayout.DISTRICT_OUTER - r);
        d = Math.min(d, CitadelLayout.roadDistance(x, z, 120) - CitadelLayout.ROAD_HALF_WIDTH);
        d = Math.min(d, CitadelLayout.roadDistance(x, z, 180) - CitadelLayout.ROAD_HALF_WIDTH);
        return d;
    }

    /** Sets the planned top of one column (before {@link #write}); below {@link #WATER_Y} it fills with water. */
    void setTop(int x, int z, int y) {
        int i = index(x, z);
        if (i >= 0 && inZone[i]) {
            top[i] = y;
            if (y >= GROUND_Y) {
                wetness[i] = Math.min(wetness[i], -BANK_WIDTH - 1);
            } else if (y < WATER_Y) {
                wetness[i] = Math.max(wetness[i], 1);
            }
        }
    }

    /** Flattens the planned ground of a building plot to {@code y} (call before {@link #write}). */
    void flatten(int x1, int z1, int x2, int z2, int y) {
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
            for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
                setTop(x, z, y);
            }
        }
    }

    /** Flattens a round plot to {@code y}. */
    void flattenDisk(int cx, int cz, double radius, int y) {
        int r = (int) Math.ceil(radius);
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz <= (radius + 0.5) * (radius + 0.5)) {
                    setTop(cx + dx, cz + dz, y);
                }
            }
        }
    }

    /** Marks columns as taken by a structure or path, so scattered rocks and plants keep off them. */
    void reserve(int x1, int z1, int x2, int z2) {
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
            for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
                int i = index(x, z);
                if (i >= 0) {
                    reserved[i] = true;
                }
            }
        }
    }

    /** Marks a round area as taken. */
    void reserveDisk(int cx, int cz, double radius) {
        int r = (int) Math.ceil(radius);
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz <= (radius + 0.5) * (radius + 0.5)) {
                    reserve(cx + dx, cz + dz, cx + dx, cz + dz);
                }
            }
        }
    }

    /** True if the column is in the zone and no structure or path claimed it. */
    boolean isFree(int x, int z) {
        int i = index(x, z);
        return i >= 0 && inZone[i] && !reserved[i];
    }

    /** True if the column belongs to the zone. */
    boolean inZone(int x, int z) {
        int i = index(x, z);
        return i >= 0 && inZone[i];
    }

    // ------------------------------------------------------------------ plan

    /** Computes the wetness field and the height map. */
    void plan() {
        for (int x = minX; x < minX + sizeX; x++) {
            for (int z = minZ; z < minZ + sizeZ; z++) {
                int i = index(x, z);
                inZone[i] = canvas.canWrite(x, z);
                if (!inZone[i]) {
                    wetness[i] = -99;
                    top[i] = GROUND_Y;
                    continue;
                }
                double w = wetnessAt(x, z);
                wetness[i] = w;
                top[i] = heightFor(x, z, w);
            }
        }
    }

    private double wetnessAt(int x, int z) {
        double lake = -99;
        for (Blob basin : basins) {
            lake = smoothMax(lake, basin.inside(x, z), 4);
        }
        // A wavy shore: bays and points, never a perfect ellipse.
        lake += 2.6 * noise.at(x, z, 9, 2);
        double island = -99;
        for (Blob blob : islands) {
            island = Math.max(island, blob.inside(x, z) + 1.1 * noise.at(x + 400, z, 5, 2));
        }
        double w = Math.min(lake, -island);
        // Keep the water and its banks away from the roads and the neighbours.
        return Math.min(w, edgeDistance(x, z) - WATER_EDGE_MARGIN);
    }

    private int heightFor(int x, int z, double w) {
        if (w > 0) {
            // The bed: a shallow shelf first, then deeper water.
            double d = w + 0.9 * noise.at(x - 300, z, 6, 1);
            int depth = d < 1.0 ? 1 : d < 2.8 ? 2 : d < 5.0 ? 3 : MAX_DEPTH;
            return WATER_Y - depth;
        }
        if (w > -BANK_WIDTH) {
            return WATER_Y;
        }
        // Meadow: low rolling ground and a few hills, flat again near the roads.
        double rise = 0;
        for (Blob hill : hills) {
            double inside = hill.inside(x, z) + 1.5 * noise.at(x, z + 500, 7, 2);
            if (inside > 0) {
                rise = Math.max(rise, inside * 0.55);
            }
        }
        double edge = edgeDistance(x, z);
        rise = Math.min(rise, Math.max(0, (edge - 3) * 0.6));
        rise = Math.min(rise, Math.max(0, (-w - BANK_WIDTH - 1) * 0.6));
        return GROUND_Y + (int) Math.floor(rise);
    }

    private static double smoothMax(double a, double b, double k) {
        double h = Math.max(k - Math.abs(a - b), 0) / k;
        return Math.max(a, b) + h * h * k * 0.25;
    }

    // ------------------------------------------------------------------ write

    /** Writes the planned ground: beds, water, banks, meadow surface and hills. */
    void write() {
        for (int x = minX; x < minX + sizeX; x++) {
            for (int z = minZ; z < minZ + sizeZ; z++) {
                int i = index(x, z);
                if (!inZone[i]) {
                    continue;
                }
                writeColumn(x, z, top[i], wetness[i]);
            }
        }
    }

    private void writeColumn(int x, int z, int y, double w) {
        if (y < GROUND_Y) {
            for (int clearY = y + 1; clearY <= GROUND_Y; clearY++) {
                canvas.set(x, clearY, z, clearY <= WATER_Y ? WATER : BlockSpec.AIR);
            }
            canvas.set(x, y, z, y < WATER_Y ? bedBlock(x, z, y) : bankBlock(x, z, w));
            // Clay and mud lenses under the bed, so the bed never sits on air.
            canvas.set(x, y - 1, z, y < WATER_Y - 2 ? STONE : DIRT);
            return;
        }
        for (int fillY = GROUND_Y; fillY < y; fillY++) {
            canvas.set(x, fillY, z, y - fillY > 2 ? STONE : DIRT);
        }
        canvas.set(x, y, z, meadowBlock(x, z, y, w));
    }

    private BlockSpec bedBlock(int x, int z, int y) {
        double n = noise.unit(x + 900, z, 5);
        double h = noise.hash(x, z, 11);
        if (y >= WATER_Y - 1) {
            return n < 0.35 ? GRAVEL : h < 0.12 ? CLAY : SAND;
        }
        if (n < 0.3) {
            return CLAY;
        }
        if (n > 0.72) {
            return h < 0.5 ? MUD : GRAVEL;
        }
        return h < 0.15 ? GRAVEL : h < 0.22 ? CLAY : SAND;
    }

    private BlockSpec bankBlock(int x, int z, double w) {
        double n = noise.unit(x - 700, z + 50, 7);
        double h = noise.hash(x, z, 13);
        if (n > 0.7) {
            return h < 0.6 ? MUD : COARSE_DIRT;
        }
        if (n < 0.28) {
            return h < 0.7 ? GRAVEL : COARSE_DIRT;
        }
        return h < 0.08 ? GRAVEL : h < 0.14 ? COARSE_DIRT : SAND;
    }

    private BlockSpec meadowBlock(int x, int z, int y, double w) {
        double h = noise.hash(x, z, 17);
        if (w > -BANK_WIDTH - 1.3 && y == GROUND_Y) {
            // The strip just above the bank: grass trodden into the shore.
            return h < 0.25 ? COARSE_DIRT : h < 0.35 ? ROOTED_DIRT : GRASS;
        }
        double n = noise.unit(x + 120, z - 80, 11);
        if (n > 0.74 && h < 0.5) {
            return PODZOL;
        }
        if (n < 0.2 && h < 0.3) {
            return MOSS;
        }
        return GRASS;
    }

    // ------------------------------------------------------------------ helpers

    private int index(int x, int z) {
        int lx = x - minX;
        int lz = z - minZ;
        if (lx < 0 || lz < 0 || lx >= sizeX || lz >= sizeZ) {
            return -1;
        }
        return lx * sizeZ + lz;
    }

    static final BlockSpec WATER = BlockSpec.of(Material.WATER);
    private static final BlockSpec STONE = BlockSpec.of(Material.STONE);
    private static final BlockSpec DIRT = BlockSpec.of(Material.DIRT);
    private static final BlockSpec GRASS = BlockSpec.of(Material.GRASS_BLOCK);
    private static final BlockSpec COARSE_DIRT = BlockSpec.of(Material.COARSE_DIRT);
    private static final BlockSpec ROOTED_DIRT = BlockSpec.of(Material.ROOTED_DIRT);
    private static final BlockSpec PODZOL = BlockSpec.of(Material.PODZOL);
    private static final BlockSpec MOSS = BlockSpec.of(Material.MOSS_BLOCK);
    private static final BlockSpec SAND = BlockSpec.of(Material.SAND);
    private static final BlockSpec GRAVEL = BlockSpec.of(Material.GRAVEL);
    private static final BlockSpec CLAY = BlockSpec.of(Material.CLAY);
    private static final BlockSpec MUD = BlockSpec.of(Material.MUD);
}
