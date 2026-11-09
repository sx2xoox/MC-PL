package io.github.sx2xoox.mcpl.core.map.citadel;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import io.github.sx2xoox.mcpl.core.map.BlockSpec;
import io.github.sx2xoox.mcpl.core.map.MapCanvas;
import org.bukkit.Material;
import org.jspecify.annotations.Nullable;

/**
 * Shared state of the hills district build: the terrain height of every column, what each column is used for, safe
 * block writes (silently skipping columns outside the district), smooth noise, and the "lumps" (rocks, walls, logs,
 * palisades: solid stacks on the ground) that {@link #settleLumps()} makes climbable.
 */
final class HillsSite {

    /** What a column of the district is used for; later builders only decorate free columns. */
    enum Use { FREE, PATH, STRUCTURE, LUMP }

    private static final int MIN_X = 0;
    private static final int MAX_X = 97;
    private static final int MIN_Z = -97;
    private static final int MAX_Z = 0;
    private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    final MapCanvas canvas;
    final Random random;
    private final long noiseSeed;
    private final int[][] ground = new int[MAX_X - MIN_X + 1][MAX_Z - MIN_Z + 1];
    private final Use[][] use = new Use[MAX_X - MIN_X + 1][MAX_Z - MIN_Z + 1];
    private final int[][] lumpTop = new int[MAX_X - MIN_X + 1][MAX_Z - MIN_Z + 1];

    HillsSite(MapCanvas canvas, Random random) {
        this.canvas = canvas;
        this.random = random;
        this.noiseSeed = random.nextLong();
        for (int i = 0; i < ground.length; i++) {
            for (int j = 0; j < ground[i].length; j++) {
                ground[i][j] = CitadelLayout.GROUND_Y;
                use[i][j] = Use.FREE;
            }
        }
    }

    // ------------------------------------------------------------------ columns

    /** True for the columns the district may write. */
    boolean inZone(int x, int z) {
        return inBox(x, z) && canvas.canWrite(x, z);
    }

    private static boolean inBox(int x, int z) {
        return x >= MIN_X && x <= MAX_X && z >= MIN_Z && z <= MAX_Z;
    }

    /** The y of the terrain's top block (the ground is at {@link CitadelLayout#GROUND_Y} outside the district). */
    int ground(int x, int z) {
        return inBox(x, z) ? ground[x - MIN_X][z - MIN_Z] : CitadelLayout.GROUND_Y;
    }

    void setGround(int x, int z, int y) {
        if (inBox(x, z)) {
            ground[x - MIN_X][z - MIN_Z] = y;
        }
    }

    /** What the column is used for; columns outside the district count as structures (never free). */
    Use use(int x, int z) {
        return inZone(x, z) ? use[x - MIN_X][z - MIN_Z] : Use.STRUCTURE;
    }

    void mark(int x, int z, Use what) {
        if (inZone(x, z)) {
            use[x - MIN_X][z - MIN_Z] = what;
        }
    }

    /** Marks every column within {@code radius} of {@code cx, cz}. */
    void markDisk(double cx, double cz, double radius, Use what) {
        int r = (int) Math.ceil(radius);
        for (int x = (int) Math.floor(cx) - r; x <= (int) Math.ceil(cx) + r; x++) {
            for (int z = (int) Math.floor(cz) - r; z <= (int) Math.ceil(cz) + r; z++) {
                if (dist(x, z, cx, cz) <= radius) {
                    mark(x, z, what);
                }
            }
        }
    }

    boolean free(int x, int z) {
        return use(x, z) == Use.FREE;
    }

    /** True if every column within {@code radius} of {@code cx, cz} is free. */
    boolean free(int cx, int cz, double radius) {
        int r = (int) Math.ceil(radius);
        for (int x = cx - r; x <= cx + r; x++) {
            for (int z = cz - r; z <= cz + r; z++) {
                if (dist(x, z, cx, cz) <= radius && !free(x, z)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** The largest height difference between the column and its four neighbours. */
    int slope(int x, int z) {
        int g = ground(x, z);
        int max = 0;
        for (int[] side : SIDES) {
            max = Math.max(max, Math.abs(ground(x + side[0], z + side[1]) - g));
        }
        return max;
    }

    // ------------------------------------------------------------------ blocks

    void set(int x, int y, int z, BlockSpec spec) {
        if (inZone(x, z)) {
            canvas.set(x, y, z, spec);
        }
    }

    void set(int x, int y, int z, Material material) {
        set(x, y, z, BlockSpec.of(material));
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

    void clear(int x, int y, int z) {
        if (inZone(x, z)) {
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

    // ------------------------------------------------------------------ lumps

    /**
     * Records that the column holds a solid stack (rock, wall, log pile, palisade) whose top block is at {@code top}.
     * The blocks are placed by the caller.
     */
    void lump(int x, int z, int top) {
        if (!inZone(x, z)) {
            return;
        }
        int i = x - MIN_X;
        int j = z - MIN_Z;
        lumpTop[i][j] = Math.max(lumpTop[i][j], top);
        use[i][j] = Use.LUMP;
    }

    /** Places a solid stack of {@code spec} from the ground up to {@code top} and records it as a lump. */
    void stack(int x, int z, int top, BlockSpec spec) {
        if (!inZone(x, z)) {
            return;
        }
        for (int y = ground(x, z) + 1; y <= top; y++) {
            set(x, y, z, spec);
        }
        lump(x, z, top);
    }

    /**
     * Makes every lump climbable: a player can step up one block at a time, so the top of a lump nobody could reach
     * is lowered (block by block, at the place that needs the least) until a one-block step leads onto it. Rocks
     * keep their cliffs on one side and get a broken, stepped side; palisades get a breach.
     */
    void settleLumps() {
        int width = MAX_X - MIN_X + 1;
        int depth = MAX_Z - MIN_Z + 1;
        int[][] top = new int[width][depth];
        boolean[][] reached = new boolean[width][depth];
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        for (int i = 0; i < width; i++) {
            for (int j = 0; j < depth; j++) {
                if (lumpTop[i][j] > 0) {
                    top[i][j] = lumpTop[i][j];
                } else {
                    top[i][j] = ground[i][j];
                    reached[i][j] = true;
                    queue.add(new int[] {i, j});
                }
            }
        }
        while (true) {
            while (!queue.isEmpty()) {
                int[] at = queue.poll();
                for (int[] side : SIDES) {
                    int i = at[0] + side[0];
                    int j = at[1] + side[1];
                    if (i < 0 || j < 0 || i >= width || j >= depth || reached[i][j]) {
                        continue;
                    }
                    if (top[i][j] <= top[at[0]][at[1]] + 1) {
                        reached[i][j] = true;
                        queue.add(new int[] {i, j});
                    }
                }
            }
            // Lower the unreached lump column that needs the smallest cut.
            int bestI = -1;
            int bestJ = -1;
            int bestTop = Integer.MIN_VALUE;
            int bestCut = Integer.MAX_VALUE;
            for (int i = 0; i < width; i++) {
                for (int j = 0; j < depth; j++) {
                    if (reached[i][j]) {
                        continue;
                    }
                    for (int[] side : SIDES) {
                        int ni = i + side[0];
                        int nj = j + side[1];
                        if (ni < 0 || nj < 0 || ni >= width || nj >= depth || !reached[ni][nj]) {
                            continue;
                        }
                        int newTop = top[ni][nj] + 1;
                        int cut = top[i][j] - newTop;
                        if (cut < bestCut || cut == bestCut && newTop > bestTop) {
                            bestCut = cut;
                            bestTop = newTop;
                            bestI = i;
                            bestJ = j;
                        }
                    }
                }
            }
            if (bestI < 0) {
                break;
            }
            int x = bestI + MIN_X;
            int z = bestJ + MIN_Z;
            for (int y = bestTop + 1; y <= top[bestI][bestJ]; y++) {
                clear(x, y, z);
            }
            top[bestI][bestJ] = bestTop;
            lumpTop[bestI][bestJ] = bestTop;
            reached[bestI][bestJ] = true;
            queue.add(new int[] {bestI, bestJ});
        }
        fillPits(top);
    }

    /**
     * Raises the ground of free columns boxed in by lumps (a path squeezed between rocks, say) until one side is a
     * single step, so nobody who drops in is stuck there.
     */
    private void fillPits(int[][] top) {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = 1; i < top.length - 1; i++) {
                for (int j = 1; j < top[i].length - 1; j++) {
                    int x = i + MIN_X;
                    int z = j + MIN_Z;
                    if (lumpTop[i][j] > 0 || !inZone(x, z) || use[i][j] == Use.STRUCTURE) {
                        continue;
                    }
                    int lowest = Integer.MAX_VALUE;
                    for (int[] side : SIDES) {
                        lowest = Math.min(lowest, top[i + side[0]][j + side[1]]);
                    }
                    if (lowest <= top[i][j] + 1) {
                        continue;
                    }
                    for (int y = top[i][j] + 1; y < lowest; y++) {
                        set(x, y, z, Material.COBBLESTONE);
                    }
                    top[i][j] = lowest - 1;
                    lumpTop[i][j] = lowest - 1;
                    use[i][j] = Use.LUMP;
                    changed = true;
                }
            }
        }
    }

    /** The top block of the lump in the column, or the ground if there is none. */
    int lumpTop(int x, int z) {
        if (!inBox(x, z)) {
            return CitadelLayout.GROUND_Y;
        }
        int top = lumpTop[x - MIN_X][z - MIN_Z];
        return top > 0 ? top : ground(x, z);
    }

    // ------------------------------------------------------------------ randomness

    boolean chance(double p) {
        return random.nextDouble() < p;
    }

    @SafeVarargs
    final <T> T pick(T... options) {
        return options[random.nextInt(options.length)];
    }

    /** Smooth value noise in -1..1, the same for every build. */
    double noise(double x, double z, int salt) {
        int x0 = (int) Math.floor(x);
        int z0 = (int) Math.floor(z);
        double fx = x - x0;
        double fz = z - z0;
        double u = fx * fx * (3 - 2 * fx);
        double v = fz * fz * (3 - 2 * fz);
        double a = hash(x0, z0, salt);
        double b = hash(x0 + 1, z0, salt);
        double c = hash(x0, z0 + 1, salt);
        double d = hash(x0 + 1, z0 + 1, salt);
        return lerp(lerp(a, b, u), lerp(c, d, u), v);
    }

    /** Two octaves of {@link #noise}, roughly -1..1. */
    double fbm(double x, double z, int salt) {
        return (noise(x, z, salt) * 2 + noise(x * 2.1 + 17.3, z * 2.1 - 4.1, salt + 101)) / 3 * 1.4;
    }

    /** A fixed pseudo-random value in -1..1 for a column. */
    double hash(int x, int z, int salt) {
        long h = noiseSeed + x * 0x9E3779B97F4A7C15L + z * 0xC2B2AE3D27D4EB4FL + salt * 0x165667B19E3779F9L;
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        h ^= h >>> 31;
        return (h >>> 11) * 0x1.0p-53 * 2 - 1;
    }

    // ------------------------------------------------------------------ math

    static double dist(double x1, double z1, double x2, double z2) {
        return Math.sqrt((x1 - x2) * (x1 - x2) + (z1 - z2) * (z1 - z2));
    }

    static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    /** 0 below {@code edge0}, 1 above {@code edge1}, smooth in between. */
    static double smoothstep(double edge0, double edge1, double v) {
        double t = clamp((v - edge0) / (edge1 - edge0), 0, 1);
        return t * t * (3 - 2 * t);
    }

    /**
     * The offsets {u, v} of a one-block ring of {@code radius} around a center that a player cannot slip through:
     * the cells within half a block of the circle, plus a joint cell wherever two of them only touch at a corner.
     */
    static List<int[]> ring(double radius) {
        int r = (int) Math.ceil(radius) + 1;
        Set<Long> cells = new HashSet<>();
        for (int u = -r; u <= r; u++) {
            for (int v = -r; v <= r; v++) {
                double d = Math.sqrt(u * u + v * v);
                if (d >= radius - 0.5 && d < radius + 0.5) {
                    cells.add(pack(u, v));
                }
            }
        }
        Set<Long> joints = new HashSet<>();
        for (long cell : cells) {
            int u = (int) (cell >> 32);
            int v = (int) cell;
            for (int du = -1; du <= 1; du += 2) {
                for (int dv = -1; dv <= 1; dv += 2) {
                    if (!cells.contains(pack(u + du, v + dv)) || cells.contains(pack(u + du, v))
                            || cells.contains(pack(u, v + dv))) {
                        continue;
                    }
                    double a = Math.abs(Math.sqrt((u + du) * (u + du) + v * v) - radius);
                    double b = Math.abs(Math.sqrt(u * u + (v + dv) * (v + dv)) - radius);
                    joints.add(a <= b ? pack(u + du, v) : pack(u, v + dv));
                }
            }
        }
        cells.addAll(joints);
        List<int[]> out = new ArrayList<>();
        for (long cell : cells) {
            out.add(new int[] {(int) (cell >> 32), (int) cell});
        }
        out.sort((p, q) -> p[0] != q[0] ? Integer.compare(p[0], q[0]) : Integer.compare(p[1], q[1]));
        return out;
    }

    private static long pack(int u, int v) {
        return ((long) u << 32) | (v & 0xFFFFFFFFL);
    }

    /** Degrees of the direction {@code u, v} (0 = east, 90 = south), 0..360. */
    static double degrees(int u, int v) {
        double angle = Math.toDegrees(Math.atan2(v, u));
        return angle < 0 ? angle + 360 : angle;
    }

    /** Distance from a point to the segment {@code a..b}. */
    static double segmentDistance(double x, double z, double ax, double az, double bx, double bz) {
        double dx = bx - ax;
        double dz = bz - az;
        double t = clamp(((x - ax) * dx + (z - az) * dz) / (dx * dx + dz * dz), 0, 1);
        return dist(x, z, ax + dx * t, az + dz * t);
    }
}
