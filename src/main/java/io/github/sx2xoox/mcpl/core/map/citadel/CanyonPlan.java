package io.github.sx2xoox.mcpl.core.map.citadel;

import org.bukkit.Material;
import org.bukkit.block.BlockFace;

/**
 * The geometry of the canyon district: the gorge's winding center line with its width and floor depth along the way,
 * per-column distances to it, smooth noise, and the rock strata every cliff of the district shows.
 *
 * <p>The gorge winds as an S from the south-west (near the 180° road) to the north (near the 240° road). A position
 * along it is {@code s}, the fraction of the center line's length from the west end; its ends rise as ramps to the
 * plateau, so it never cuts a road.
 */
final class CanyonPlan {

    /** The bounding box of the district's columns (with a margin). */
    static final int MIN_X = -98;
    static final int MAX_X = -14;
    static final int MIN_Z = -86;
    static final int MAX_Z = 1;
    static final int SIZE_X = MAX_X - MIN_X + 1;
    static final int SIZE_Z = MAX_Z - MIN_Z + 1;

    static final int GROUND = CitadelLayout.GROUND_Y;

    /** Control points of the gorge's center line, west end first (a Catmull-Rom spline runs through them). */
    private static final double[][] PATH = {
        {-63, -13}, {-71, -20}, {-73, -30}, {-66, -38}, {-55, -41}, {-48, -48}, {-48, -58}, {-53, -67}
    };
    private static final int SAMPLES = 1000;

    /** How steeply the gorge's end ramps rise (blocks up per block along the gorge). */
    static final double END_RAMP_SLOPE = 0.72;

    private final long seed;
    private final double[] sampleX = new double[SAMPLES + 1];
    private final double[] sampleZ = new double[SAMPLES + 1];
    private final double[] sampleLength = new double[SAMPLES + 1];
    private final double length;

    private final float[] columnS = new float[SIZE_X * SIZE_Z];
    private final float[] columnDistance = new float[SIZE_X * SIZE_Z];
    private final boolean[] columnInner = new boolean[SIZE_X * SIZE_Z];

    CanyonPlan(long seed) {
        this.seed = seed;
        double total = 0;
        for (int i = 0; i <= SAMPLES; i++) {
            double[] p = spline((double) i / SAMPLES);
            sampleX[i] = p[0];
            sampleZ[i] = p[1];
            if (i > 0) {
                total += Math.hypot(sampleX[i] - sampleX[i - 1], sampleZ[i] - sampleZ[i - 1]);
            }
            sampleLength[i] = total;
        }
        length = total;
        for (int x = MIN_X; x <= MAX_X; x++) {
            for (int z = MIN_Z; z <= MAX_Z; z++) {
                int best = 0;
                double bestD2 = Double.MAX_VALUE;
                for (int i = 0; i <= SAMPLES; i++) {
                    double dx = x - sampleX[i];
                    double dz = z - sampleZ[i];
                    double d2 = dx * dx + dz * dz;
                    if (d2 < bestD2) {
                        bestD2 = d2;
                        best = i;
                    }
                }
                int index = index(x, z);
                double s = sampleLength[best] / length;
                columnS[index] = (float) s;
                columnDistance[index] = (float) Math.sqrt(bestD2);
                double[] inner = innerNormal(s);
                columnInner[index] = (x - sampleX[best]) * inner[0] + (z - sampleZ[best]) * inner[1] > 0;
            }
        }
    }

    // ------------------------------------------------------------------ the gorge

    /** A point of the spline through {@link #PATH} at parameter {@code u} in [0, 1]: {x, z}. */
    private static double[] spline(double u) {
        int segments = PATH.length - 1;
        double v = Math.min(u * segments, segments - 1e-9);
        int i = (int) v;
        double t = v - i;
        double[] p0 = PATH[Math.max(0, i - 1)];
        double[] p1 = PATH[i];
        double[] p2 = PATH[i + 1];
        double[] p3 = PATH[Math.min(segments, i + 2)];
        double t2 = t * t;
        double t3 = t2 * t;
        double[] out = new double[2];
        for (int k = 0; k < 2; k++) {
            out[k] = 0.5 * (2 * p1[k] + (-p0[k] + p2[k]) * t + (2 * p0[k] - 5 * p1[k] + 4 * p2[k] - p3[k]) * t2
                    + (-p0[k] + 3 * p1[k] - 3 * p2[k] + p3[k]) * t3);
        }
        return out;
    }

    /** The center line at {@code s} (the fraction of its length from the west end): {x, z}. */
    double[] center(double s) {
        double target = Math.max(0, Math.min(1, s)) * length;
        int lo = 0;
        int hi = SAMPLES;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (sampleLength[mid] < target) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        double span = sampleLength[hi] - sampleLength[lo];
        double t = span <= 0 ? 0 : (target - sampleLength[lo]) / span;
        return new double[] {sampleX[lo] + (sampleX[hi] - sampleX[lo]) * t,
            sampleZ[lo] + (sampleZ[hi] - sampleZ[lo]) * t};
    }

    /** Unit direction of the center line at {@code s} (towards growing s): {x, z}. */
    double[] tangent(double s) {
        double step = 1.5 / length;
        double[] a = center(Math.max(0, s - step));
        double[] b = center(Math.min(1, s + step));
        double dx = b[0] - a[0];
        double dz = b[1] - a[1];
        double n = Math.hypot(dx, dz);
        return new double[] {dx / n, dz / n};
    }

    /** Unit normal of the center line at {@code s} pointing to its inner side (the side facing the keep). */
    double[] innerNormal(double s) {
        double[] t = tangent(s);
        double[] c = center(s);
        double nx = -t[1];
        double nz = t[0];
        if (nx * -c[0] + nz * -c[1] < 0) {
            nx = -nx;
            nz = -nz;
        }
        return new double[] {nx, nz};
    }

    /** A point {@code offset} blocks from the center line at {@code s} ({@code inner}: towards the keep). */
    int[] beside(double s, double offset, boolean inner) {
        double[] c = center(s);
        double[] n = innerNormal(s);
        double sign = inner ? 1 : -1;
        return new int[] {(int) Math.round(c[0] + n[0] * offset * sign), (int) Math.round(c[1] + n[1] * offset * sign)};
    }

    /** The cardinal direction closest to {@code (dx, dz)}. */
    static BlockFace cardinal(double dx, double dz) {
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx >= 0 ? BlockFace.EAST : BlockFace.WEST;
        }
        return dz >= 0 ? BlockFace.SOUTH : BlockFace.NORTH;
    }

    /** Length of the center line in blocks. */
    double length() {
        return length;
    }

    /** Half the width of the gorge at the rim. */
    double halfWidth(double s) {
        double base = 6.2 + 1.0 * Math.sin(2 * Math.PI * 1.5 * s + 0.6);
        // The ends narrow into the ramps.
        return Math.min(base, 3.2 + distanceFromEnd(s) * 0.45);
    }

    /** The y of the gorge floor's top block at {@code s} (the end ramps included). */
    int floorY(double s) {
        double wave = 0.7 * Math.sin(2 * Math.PI * 2.3 * s + 1.1);
        int base = (int) Math.round(56.3 - 1.4 * Math.sin(Math.PI * s) + wave);
        int ramp = GROUND - (int) Math.floor(distanceFromEnd(s) * END_RAMP_SLOPE);
        return Math.max(base, ramp);
    }

    /** Blocks along the center line to the nearer end of the gorge. */
    double distanceFromEnd(double s) {
        return Math.min(s, 1 - s) * length;
    }

    // ------------------------------------------------------------------ columns

    /**
     * Blocks from the column to the nearest edge of the district (the ring road, the outer ring or a radial road's
     * side); features keep a margin so the edges stay open ground.
     */
    static double edgeDistance(int x, int z) {
        double r = CitadelLayout.radius(x, z);
        double roads = Math.min(CitadelLayout.roadDistance(x, z, 180), CitadelLayout.roadDistance(x, z, 240))
                - CitadelLayout.ROAD_HALF_WIDTH;
        return Math.min(Math.min(r - CitadelLayout.RING_ROAD_OUTER, CitadelLayout.DISTRICT_OUTER - r), roads);
    }

    static boolean inBox(int x, int z) {
        return x >= MIN_X && x <= MAX_X && z >= MIN_Z && z <= MAX_Z;
    }

    static int index(int x, int z) {
        return (x - MIN_X) * SIZE_Z + (z - MIN_Z);
    }

    /** Position along the gorge of the center-line point nearest to the column. */
    double s(int x, int z) {
        return inBox(x, z) ? columnS[index(x, z)] : 0;
    }

    /** Distance of the column to the center line. */
    double distance(int x, int z) {
        return inBox(x, z) ? columnDistance[index(x, z)] : 999;
    }

    /** True if the column lies on the inner side (towards the keep) of the gorge. */
    boolean inner(int x, int z) {
        return inBox(x, z) && columnInner[index(x, z)];
    }

    /** Signed distance of the column from the gorge's rim edge (negative inside the gorge), jagged by noise. */
    double rimDistance(int x, int z) {
        double s = s(x, z);
        double jag = 0.95 * noise(x / 3.7, z / 3.7, 11) + 0.45 * noise(x / 1.6, z / 1.6, 12);
        return distance(x, z) - (halfWidth(s) + jag);
    }

    // ------------------------------------------------------------------ noise

    /** Smooth value noise in [-1, 1] for {@code salt}. */
    double noise(double x, double z, int salt) {
        int x0 = (int) Math.floor(x);
        int z0 = (int) Math.floor(z);
        double fx = x - x0;
        double fz = z - z0;
        double sx = fx * fx * (3 - 2 * fx);
        double sz = fz * fz * (3 - 2 * fz);
        double a = lattice(x0, z0, salt);
        double b = lattice(x0 + 1, z0, salt);
        double c = lattice(x0, z0 + 1, salt);
        double d = lattice(x0 + 1, z0 + 1, salt);
        double top = a + (b - a) * sx;
        double bottom = c + (d - c) * sx;
        return top + (bottom - top) * sz;
    }

    private double lattice(int x, int z, int salt) {
        return (hash(x, salt, z) & 0xFFFF) / 32767.5 - 1;
    }

    /** A well mixed hash of a position (the same for every build). */
    int hash(int x, int y, int z) {
        long h = seed ^ 0x9E3779B97F4A7C15L;
        h = (h ^ x) * 0xBF58476D1CE4E5B9L;
        h = (h ^ y) * 0x94D049BB133111EBL;
        h = (h ^ z) * 0xBF58476D1CE4E5B9L;
        h ^= h >>> 31;
        h *= 0x94D049BB133111EBL;
        h ^= h >>> 29;
        return (int) (h & 0x7FFFFFFF);
    }

    /** A number in [0, 100) for the block at a position and {@code salt}. */
    int roll(int x, int y, int z, int salt) {
        return hash(x * 31 + salt, y, z * 17 - salt) % 100;
    }

    // ------------------------------------------------------------------ strata

    /** How far the rock layers are lifted at a column (they undulate gently across the district). */
    int tilt(int x, int z) {
        return (int) Math.round(1.6 * noise(x / 26.0, z / 26.0, 3) + 0.6 * noise(x / 9.0, z / 9.0, 4));
    }

    /** The rock of the layered cliffs at a position. */
    Material stratum(int x, int y, int z) {
        int level = y + tilt(x, z);
        int roll = roll(x, y, z, 7);
        return switch (band(level)) {
            case TUFF -> roll < 82 ? Material.TUFF : roll < 94 ? Material.ANDESITE : Material.STONE;
            case ANDESITE -> roll < 70 ? Material.ANDESITE : roll < 88 ? Material.STONE : Material.COBBLESTONE;
            case GRANITE -> roll < 80 ? Material.GRANITE : roll < 92 ? Material.TERRACOTTA : Material.POLISHED_GRANITE;
            case TERRACOTTA -> roll < 50 ? Material.ORANGE_TERRACOTTA : roll < 85 ? Material.TERRACOTTA
                    : Material.BROWN_TERRACOTTA;
            case RED_SANDSTONE -> roll < 60 ? Material.RED_SANDSTONE : roll < 85 ? Material.SMOOTH_RED_SANDSTONE
                    : Material.ORANGE_TERRACOTTA;
            case PALE -> roll < 55 ? Material.WHITE_TERRACOTTA : roll < 85 ? Material.LIGHT_GRAY_TERRACOTTA
                    : Material.CALCITE;
            case STONE -> roll < 72 ? Material.STONE : roll < 88 ? Material.ANDESITE : Material.COBBLESTONE;
        };
    }

    private enum Band { STONE, TUFF, ANDESITE, GRANITE, TERRACOTTA, RED_SANDSTONE, PALE }

    private static final Band[] BANDS_FROM_52 = {
        Band.STONE,          // 52
        Band.TUFF,           // 53
        Band.STONE,          // 54
        Band.ANDESITE,       // 55
        Band.TUFF,           // 56
        Band.TUFF,           // 57
        Band.ANDESITE,       // 58
        Band.ANDESITE,       // 59
        Band.RED_SANDSTONE,  // 60
        Band.GRANITE,        // 61
        Band.GRANITE,        // 62
        Band.TERRACOTTA,     // 63
        Band.ANDESITE,       // 64
        Band.ANDESITE,       // 65
        Band.STONE,          // 66
        Band.GRANITE,        // 67
        Band.GRANITE,        // 68
        Band.TERRACOTTA,     // 69
        Band.TUFF,           // 70
        Band.ANDESITE,       // 71
        Band.ANDESITE,       // 72
        Band.GRANITE,        // 73
        Band.PALE,           // 74
        Band.GRANITE,        // 75
        Band.STONE,          // 76
        Band.ANDESITE,       // 77
        Band.TUFF,           // 78
        Band.GRANITE,        // 79
    };

    private static Band band(int level) {
        if (level < 52) {
            return Band.STONE;
        }
        if (level - 52 >= BANDS_FROM_52.length) {
            return (level & 1) == 0 ? Band.ANDESITE : Band.STONE;
        }
        return BANDS_FROM_52[level - 52];
    }
}
