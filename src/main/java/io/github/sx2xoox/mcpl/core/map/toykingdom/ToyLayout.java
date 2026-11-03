package io.github.sx2xoox.mcpl.core.map.toykingdom;

import java.util.ArrayList;
import java.util.List;

import io.github.sx2xoox.mcpl.core.map.lanes.LaneSites.Lane;
import io.github.sx2xoox.mcpl.core.map.lanes.LaneSites.Side;

/**
 * The plan of 장난감 왕국 (docs/siege-v2.md §6): every number of the layout and the geometry the zones, the sites and
 * the tests share.
 *
 * <p>The map is mirror symmetric twice: across {@code x = 0} (BLUE on −x, RED on +x; block {@code x} mirrors to block
 * {@code -x}) and, for the ground plan, across {@code z = 0} (TOP lane on −z, BOTTOM lane on +z). Most functions
 * therefore take the column as {@code ax = |x|} and {@code az = |z|}.
 *
 * <pre>
 *  z=-60 ┌───────────── hedge ─────────────┐
 *        │  ╭── TOP lane (yellow) ───────╮  │   towers: outer ax=30, inner ax=74 (on the lane)
 *        │ ╱  jungle   river   jungle     ╲ │   objectives: 6 per side between the lanes
 *  z=0   │[B]   ·  ·    (dragon)   ·  ·   [R]│   castles: ax 64..96, |z| <= 18, core at ax=80
 *        │ ╲                                ╱ │
 *        │  ╰── BOTTOM lane (light blue) ─╯  │
 *  z=60  └─────────────────────────────────────┘
 *       x=-98                               x=98
 * </pre>
 */
public final class ToyLayout {

    private ToyLayout() {
    }

    /** Top of the ground everywhere (the dragon's sandbox and the river are one lower). */
    public static final int GROUND_Y = 64;
    public static final int MIN_Y = GROUND_Y - 5;
    public static final int MAX_Y = GROUND_Y + 32;
    /** Blocks the map builds on each side of the centre (the canvas). */
    public static final int BUILD_RADIUS = 106;

    // ------------------------------------------------------------------ outline

    /** Half sizes of the walkable area (a rounded rectangle) and its corner radius. */
    public static final int HALF_X = 98;
    public static final int HALF_Z = 60;
    public static final int CORNER = 18;
    /** The hedge around the walkable area, then a strip of lawn, then nothing. */
    public static final double HEDGE_WIDTH = 2.5;
    public static final double APRON_WIDTH = 6.5;

    // ------------------------------------------------------------------ castles

    /** The castle box (walls included) on {@code |x|}, and its half size on z. */
    public static final int CASTLE_FRONT = 64;
    public static final int CASTLE_BACK = 96;
    public static final int CASTLE_HALF_Z = 18;
    /** Height of the castle walls above the ground (the white top band included). */
    public static final int WALL_HEIGHT = 5;
    /** The castle zone: the box and a strip of lawn around it. */
    public static final int CASTLE_ZONE_FRONT = 62;
    public static final int CASTLE_ZONE_HALF_Z = 21;
    /** The core in the courtyard. */
    public static final int CORE_AX = 80;
    public static final int CORE_RADIUS = 6;
    /** The lane gates (north and south walls) around {@code |x| = GATE_AX}, the front gate around {@code z = 0}. */
    public static final int GATE_AX = 80;
    public static final int GATE_HALF = 3;
    /** The spawn mat behind the core, and the shop counter in the back corner. */
    public static final int SPAWN_AX1 = 88;
    public static final int SPAWN_AX2 = 93;
    public static final int SPAWN_HALF_Z = 7;
    public static final int SHOP_AX = 90;
    public static final int SHOP_Z = -9;

    // ------------------------------------------------------------------ lanes

    /**
     * The lane centre line (TOP; BOTTOM is the same with {@code z} negated) is a quarter superellipse per side: from the
     * castle's lane gate at {@code (LANE_AX, -CASTLE_HALF_Z)} it leaves the castle towards the edge, bends and runs
     * along the edge to {@code (0, -LANE_REACH)}.
     */
    public static final int LANE_AX = GATE_AX;
    public static final int LANE_REACH = 48;
    /** Road half width (yellow / light blue) and the white edge. */
    public static final double ROAD_HALF = 3.5;
    public static final double EDGE_HALF = 4.5;
    /** The lane zone: the road, its verges and the tower plazas with their candy canes. */
    public static final double LANE_ZONE_HALF = 9.5;
    /** Where the towers stand on each lane ({@code |x|}), outer first. */
    public static final int[] TOWER_AX = {30, 74};
    public static final int TOWER_RADIUS = 7;
    /** Team coloured ring around a tower plaza (where its defence zone ends). */
    public static final double RING_OUTER = 9;
    public static final double TOWER_ZONE = 11.5;
    /** Free height over towers and cores (LaneSites contract). */
    public static final int CLEARANCE = 20;

    // ------------------------------------------------------------------ middle

    /** The dragon's sandbox: sand one block down within {@code PIT_RADIUS}, a wooden frame around it. */
    public static final double PIT_RADIUS = 11;
    public static final double PIT_FRAME = 12.5;
    public static final int EPIC_RADIUS = 10;
    /** The river along {@code x = 0}: a capsule between {@code |z| = RIVER_Z1} and {@code RIVER_Z2}. */
    public static final int RIVER_Z1 = 18;
    public static final int RIVER_Z2 = 38;
    public static final double RIVER_HALF = 3.5;
    /** The river zone: the river, its banks and the sandbox. */
    public static final int RIVER_ZONE_HALF_X = 6;
    public static final int RIVER_ZONE_HALF_Z = 43;
    public static final double RIVER_ZONE_RADIUS = 16;

    // ------------------------------------------------------------------ outline helpers

    /** Signed distance to the outline of the walkable area (negative inside). */
    public static double outline(int x, int z) {
        double qx = Math.abs(x) - (HALF_X - CORNER);
        double qz = Math.abs(z) - (HALF_Z - CORNER);
        double outside = Math.hypot(Math.max(qx, 0), Math.max(qz, 0));
        return outside + Math.min(Math.max(qx, qz), 0) - CORNER;
    }

    /** True for the walkable columns (inside the hedge). */
    public static boolean isInterior(int x, int z) {
        return outline(x, z) <= 0;
    }

    /** True inside a castle box (walls included). */
    public static boolean inCastle(int x, int z) {
        int ax = Math.abs(x);
        return ax >= CASTLE_FRONT && ax <= CASTLE_BACK && Math.abs(z) <= CASTLE_HALF_Z;
    }

    /** True inside a castle's courtyard (inside the two-block walls). */
    public static boolean inCourtyard(int x, int z) {
        int ax = Math.abs(x);
        return ax >= CASTLE_FRONT + 2 && ax <= CASTLE_BACK - 2 && Math.abs(z) <= CASTLE_HALF_Z - 2;
    }

    /** The side whose half a column lies in (BLUE for x < 0, RED for x > 0, BLUE on the centre line). */
    public static Side sideOf(int x) {
        return x > 0 ? Side.RED : Side.BLUE;
    }

    /** {@code -1} for BLUE, {@code +1} for RED: multiply a BLUE-half {@code |x|} to get the real x. */
    public static int sign(Side side) {
        return side == Side.BLUE ? -1 : 1;
    }

    /** {@code -1} for TOP, {@code +1} for BOTTOM. */
    public static int sign(Lane lane) {
        return lane == Lane.TOP ? -1 : 1;
    }

    /** Distance from the map centre. */
    public static double centre(int x, int z) {
        return Math.hypot(x, z);
    }

    /** Distance to the river's centre line (a capsule along x = 0). */
    public static double river(int x, int z) {
        int az = Math.abs(z);
        double dz = az < RIVER_Z1 ? RIVER_Z1 - az : az > RIVER_Z2 ? az - RIVER_Z2 : 0;
        return Math.hypot(x, dz);
    }

    // ------------------------------------------------------------------ lanes

    /** Points along the TOP lane's centre line on the BLUE half ({@code ax, z}), from the gate to the middle. */
    static final double[][] CURVE = curve(4000);

    private static double[][] curve(int samples) {
        double[][] points = new double[samples + 1][];
        for (int i = 0; i <= samples; i++) {
            double t = Math.PI / 2 * i / samples;
            points[i] = new double[] {LANE_AX * Math.sqrt(Math.cos(t)),
                    -(CASTLE_HALF_Z + (LANE_REACH - CASTLE_HALF_Z) * Math.sqrt(Math.sin(t)))};
        }
        points[samples][0] = 0;
        return points;
    }

    /** {@code [|x|][z + BUILD_RADIUS]}: distance to the TOP lane's centre line. */
    private static final float[][] TOP_DISTANCE = distances();

    private static float[][] distances() {
        int r = BUILD_RADIUS;
        float[][] out = new float[r + 1][2 * r + 1];
        for (int ax = 0; ax <= r; ax++) {
            for (int z = -r; z <= r; z++) {
                double best = Double.MAX_VALUE;
                for (int i = 0; i < CURVE.length; i += 2) {
                    double dx = ax - CURVE[i][0];
                    double dz = z - CURVE[i][1];
                    double d = dx * dx + dz * dz;
                    if (d < best) {
                        best = d;
                    }
                }
                out[ax][z + r] = (float) Math.sqrt(best);
            }
        }
        return out;
    }

    /** Distance from a column to a lane's centre line. */
    public static double laneDistance(Lane lane, int x, int z) {
        int ax = Math.abs(x);
        int zz = lane == Lane.TOP ? z : -z;
        if (ax > BUILD_RADIUS || Math.abs(zz) > BUILD_RADIUS) {
            return Double.MAX_VALUE;
        }
        return TOP_DISTANCE[ax][zz + BUILD_RADIUS];
    }

    /** The lane a column belongs to by its half of the map (TOP for z < 0). */
    public static Lane laneOf(int z) {
        return z <= 0 ? Lane.TOP : Lane.BOTTOM;
    }

    /** The point of the TOP lane's centre line at {@code ax} on the BLUE half, as {ax, z}. */
    static double[] curveAt(double ax) {
        double[] best = CURVE[0];
        for (double[] p : CURVE) {
            if (Math.abs(p[0] - ax) < Math.abs(best[0] - ax)) {
                best = p;
            }
        }
        return best;
    }

    /** The {@code |x|, z} of the towers of the TOP lane on the BLUE half, outer first (z of BOTTOM is negated). */
    static final int[][] TOWERS = towers();

    private static int[][] towers() {
        int[][] out = new int[TOWER_AX.length][];
        for (int i = 0; i < TOWER_AX.length; i++) {
            out[i] = new int[] {TOWER_AX[i], (int) Math.round(curveAt(TOWER_AX[i])[1])};
        }
        return out;
    }

    /** Distance from a column to the nearest tower centre of its lane half. */
    public static double towerDistance(int x, int z) {
        int ax = Math.abs(x);
        int az = Math.abs(z);
        double best = Double.MAX_VALUE;
        for (int[] t : TOWERS) {
            best = Math.min(best, Math.hypot(ax - t[0], az + t[1]));
        }
        return best;
    }

    /** The unit normal of the TOP lane (pointing away from the map centre) at the tower {@code index}, as {ax, z}. */
    static double[] towerNormal(int index) {
        double ax = TOWERS[index][0];
        double[] a = curveAt(ax - 1);
        double[] b = curveAt(ax + 1);
        double tx = b[0] - a[0];
        double tz = b[1] - a[1];
        double len = Math.hypot(tx, tz);
        // Perpendicular, oriented outwards (towards larger |x| or more negative z).
        double nx = -tz / len;
        double nz = tx / len;
        if (nx * (ax) + nz * TOWERS[index][1] < 0) {
            nx = -nx;
            nz = -nz;
        }
        return new double[] {nx, nz};
    }

    /**
     * Waypoints of the TOP lane on the BLUE half ({@code |x|, z}): from the courtyard in front of the core through the
     * lane gate, the inner and the outer tower to the middle (included), at most {@code step} blocks apart.
     */
    static List<int[]> halfPath(double step) {
        List<int[]> out = new ArrayList<>();
        out.add(new int[] {LANE_AX, -(CORE_RADIUS + 3)});
        // Arc lengths of the key points along the curve (gate, inner tower, outer tower, middle).
        double[] cumulative = new double[CURVE.length];
        for (int i = 1; i < CURVE.length; i++) {
            cumulative[i] = cumulative[i - 1] + Math.hypot(CURVE[i][0] - CURVE[i - 1][0],
                    CURVE[i][1] - CURVE[i - 1][1]);
        }
        List<Double> keys = new ArrayList<>();
        keys.add(0.0);
        for (int t = TOWERS.length - 1; t >= 0; t--) {
            keys.add(cumulative[nearest(TOWERS[t][0])]);
        }
        keys.add(cumulative[CURVE.length - 1]);
        for (int k = 1; k < keys.size(); k++) {
            double from = keys.get(k - 1);
            double to = keys.get(k);
            int pieces = (int) Math.ceil((to - from) / step);
            for (int p = k == 1 ? 0 : 1; p <= pieces; p++) {
                double s = from + (to - from) * p / pieces;
                double[] point = CURVE[at(cumulative, s)];
                out.add(new int[] {(int) Math.round(point[0]), (int) Math.round(point[1])});
            }
        }
        // The tower centres exactly.
        for (int[] t : TOWERS) {
            for (int[] p : out) {
                if (Math.hypot(p[0] - t[0], p[1] - t[1]) < 2.5) {
                    p[0] = t[0];
                    p[1] = t[1];
                }
            }
        }
        return out;
    }

    private static int nearest(double ax) {
        int best = 0;
        for (int i = 0; i < CURVE.length; i++) {
            if (Math.abs(CURVE[i][0] - ax) < Math.abs(CURVE[best][0] - ax)) {
                best = i;
            }
        }
        return best;
    }

    private static int at(double[] cumulative, double s) {
        for (int i = 0; i < cumulative.length; i++) {
            if (cumulative[i] >= s - 1e-9) {
                return i;
            }
        }
        return cumulative.length - 1;
    }

    // ------------------------------------------------------------------ zones

    /** Which part of the map a column belongs to (zones, previews and audit reports). */
    public static String partOf(int x, int z) {
        if (!isInterior(x, z)) {
            return "border";
        }
        int ax = Math.abs(x);
        boolean road = laneDistance(laneOf(z), x, z) <= LANE_ZONE_HALF;
        if (ax >= CASTLE_ZONE_FRONT && Math.abs(z) <= CASTLE_ZONE_HALF_Z && (inCastle(x, z) || !road)) {
            return x < 0 ? "blue-castle" : "red-castle";
        }
        if (ax <= RIVER_ZONE_HALF_X && Math.abs(z) <= RIVER_ZONE_HALF_Z || centre(x, z) <= RIVER_ZONE_RADIUS) {
            return "river";
        }
        Lane lane = laneOf(z);
        if (laneDistance(lane, x, z) <= LANE_ZONE_HALF || towerDistance(x, z) <= TOWER_ZONE) {
            return lane == Lane.TOP ? "top-lane" : "bottom-lane";
        }
        return "jungle";
    }

    /** Minecraft yaw looking from one point to another (0 = +z). */
    public static float yaw(double x1, double z1, double x2, double z2) {
        return (float) Math.toDegrees(Math.atan2(-(x2 - x1), z2 - z1));
    }
}
