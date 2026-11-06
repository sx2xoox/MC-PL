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
    public static final int MAX_Y = GROUND_Y + ToyHall.TOP + 1;
    /** Blocks the map builds on each side of the centre (the canvas): the hall's back wall reaches |x| = 109. */
    public static final int BUILD_RADIUS = 110;

    // ------------------------------------------------------------------ outline

    /** Half sizes of the walkable area (a rounded rectangle) and its corner radius. */
    public static final int HALF_X = 98;
    public static final int HALF_Z = 60;
    public static final int CORNER = 18;
    /**
     * The toy brick wall around the walkable area, then a strip of floorboards under the clockwork hall's plinth (see
     * {@link ToyHall}).
     */
    public static final double HEDGE_WIDTH = 2.5;
    public static final double APRON_WIDTH = 6.5;

    // ------------------------------------------------------------------ castles

    /** The castle box (walls included) on {@code |x|}, and its half size on z. */
    public static final int CASTLE_FRONT = 64;
    public static final int CASTLE_BACK = 96;
    public static final int CASTLE_HALF_Z = 18;
    /** Height of the castle walls above the ground (the white top band included). */
    public static final int WALL_HEIGHT = 7;
    /** The castle zone: the box and a strip of lawn around it. */
    public static final int CASTLE_ZONE_FRONT = 62;
    public static final int CASTLE_ZONE_HALF_Z = 21;
    /** The core in the courtyard: its stage (open within the radius) and the free height over it. */
    public static final int CORE_AX = 80;
    public static final int CORE_RADIUS = 10;
    public static final int CORE_CLEARANCE = 36;
    /** The lane gates (north and south walls) around {@code |x| = GATE_AX}, the front gate around {@code z = 0}. */
    public static final int GATE_AX = 80;
    public static final int GATE_HALF = 3;
    /**
     * The spawn mat in the front corner of the courtyard on each team's left, beside the shop stall and inside the front
     * gate ({@code |x|} SPAWN_AX1..SPAWN_AX2, {@code |z|} SPAWN_Z1..SPAWN_Z2 on the side's {@link #left}): every spawn
     * spot lies within the shop's range (8) of the shopkeeper, off the core's stage and its pillars. Point symmetric
     * like the shop.
     */
    public static final int SPAWN_AX1 = 66;
    public static final int SPAWN_AX2 = 70;
    public static final int SPAWN_Z1 = 8;
    public static final int SPAWN_Z2 = 14;
    /** The spawn spots on the mat ({@code |x|}, {@code |z|} on the side's left), two blocks apart. */
    public static final int[][] SPAWN_SPOTS = {{68, 11}, {66, 11}, {70, 11}, {68, 9}, {68, 13}, {66, 13},
        {70, 9}, {70, 13}};
    /**
     * The shop stall stands against the side wall on each team's left as it faces the enemy (BLUE looks to +x, so its
     * left is −z; RED looks to −x, its left is +z), between the front turret and the lane gate: the shopkeeper behind
     * the counter at ({@code SHOP_AX}, {@code SHOP_AZ}), the stall over {@code |x|} SHOP_AX1..SHOP_AX2 and
     * {@code |z|} SHOP_Z1..CASTLE_HALF_Z - 2. The castle is therefore point symmetric here, not mirror symmetric.
     */
    public static final int SHOP_AX = 73;
    public static final int SHOP_AZ = 14;
    public static final int SHOP_AX1 = 70;
    public static final int SHOP_AX2 = 76;
    public static final int SHOP_Z1 = 12;

    /** The sign of the z of a side's left (BLUE −z, RED +z): where its shop stands. */
    public static int left(Side side) {
        return sign(side);
    }

    /** True for the columns of a spawn mat (either side's; {@code frame} widens it by its one-block frame). */
    public static boolean inSpawnMat(int x, int z, boolean frame) {
        int ax = Math.abs(x);
        int az = Math.abs(z);
        int f = frame ? 1 : 0;
        return Integer.signum(z) == left(sideOf(x)) && ax >= SPAWN_AX1 - f && ax <= SPAWN_AX2 + f
                && az >= SPAWN_Z1 - f && az <= SPAWN_Z2 + f;
    }

    /** True for the columns of a shop stall (either side's). */
    public static boolean inShopStall(int x, int z) {
        int ax = Math.abs(x);
        return ax >= SHOP_AX1 && ax <= SHOP_AX2 && Integer.signum(z) == left(sideOf(x)) && Math.abs(z) >= SHOP_Z1
                && Math.abs(z) <= CASTLE_HALF_Z - 2;
    }
    /** The checkered half disc in front of the front gate (centred on the wall at {@code z = 0}). */
    public static final double FRONT_PLAZA_RADIUS = 7.5;

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
    /** Free height over towers (LaneSites contract; the core has {@link #CORE_CLEARANCE}). */
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

    // ------------------------------------------------------------------ hazards

    /**
     * The drops: in the middle of each lane the play mat ends in a cliff beyond the outer verge (|x| <= CLIFF_HALF_X):
     * no hedge, no floor, nothing but the void below. The last row before it is striped as a warning.
     */
    public static final int CLIFF_HALF_X = 12;
    /** The cliff starts this far inside the outline (the verge is 5.5 blocks wide there). */
    public static final double CLIFF_INSET = 2;

    /** True for the columns of a drop (no blocks at all in the column, up to the hall's ceiling or gate arch). */
    public static boolean inCliff(int x, int z) {
        return Math.abs(x) <= CLIFF_HALF_X && outline(x, z) > -CLIFF_INSET;
    }

    /**
     * The holes in the play mat of the middle corridor (castle front gate to the dragon's sandbox), like missing pieces
     * of a toy puzzle: on each half a triangle pointing at the sandbox, an upside down one pointing back at the castle
     * and a long diagonal strip, open to the void like the lane drops. In BLUE half coordinates ({@code |x|}, signed
     * {@code z}); RED mirrors x. They keep {@link #HOLE_DRAGON_GAP} from the dragon's leash, away from the camps'
     * leashes, the lanes and the castles, with room to walk around every one.
     */
    static final double[][] HOLE_TRIANGLE = {{22, -6}, {28, -8.5}, {28, -3.5}};
    static final double[][] HOLE_INVERTED = {{31, 2.5}, {31, 7.5}, {37, 5}};
    /** The strip: from one end to the other ({@code |x|, z}), and its half width. */
    static final double[] HOLE_STRIP = {40, -4, 45, -9};
    static final double HOLE_STRIP_HALF = 1.2;
    /** Blocks between the dragon's leash (20 around the centre) and the nearest hole. */
    public static final int HOLE_DRAGON_GAP = 1;

    /** True for the columns of a hole in the middle (no blocks at all in the column). */
    public static boolean inHole(int x, int z) {
        int ax = Math.abs(x);
        if (ax < 20 || ax > 48 || Math.abs(z) > 12) {
            return false;
        }
        return inTriangle(ax, z, HOLE_TRIANGLE) || inTriangle(ax, z, HOLE_INVERTED) || inStrip(ax, z);
    }

    private static boolean inTriangle(double px, double pz, double[][] t) {
        double d1 = cross(t[0], t[1], px, pz);
        double d2 = cross(t[1], t[2], px, pz);
        double d3 = cross(t[2], t[0], px, pz);
        boolean negative = d1 < 0 || d2 < 0 || d3 < 0;
        boolean positive = d1 > 0 || d2 > 0 || d3 > 0;
        return !(negative && positive);
    }

    private static double cross(double[] a, double[] b, double px, double pz) {
        return (b[0] - a[0]) * (pz - a[1]) - (b[1] - a[1]) * (px - a[0]);
    }

    private static boolean inStrip(double px, double pz) {
        double[] s = HOLE_STRIP;
        double dx = s[2] - s[0];
        double dz = s[3] - s[1];
        double len = Math.hypot(dx, dz);
        double along = ((px - s[0]) * dx + (pz - s[1]) * dz) / len;
        double across = Math.abs((px - s[0]) * dz - (pz - s[1]) * dx) / len;
        return along >= -0.5 && along <= len + 0.5 && across <= HOLE_STRIP_HALF;
    }

    /** True for every column open to the void: the lane drops and the holes in the middle. */
    public static boolean inDrop(int x, int z) {
        return inCliff(x, z) || inHole(x, z);
    }

    /** True for the striped warning row along the edge of a drop or a hole. */
    public static boolean onCliffEdge(int x, int z) {
        int ax = Math.abs(x);
        boolean nearCliff = ax <= CLIFF_HALF_X + 1;
        boolean nearHole = ax >= 19 && ax <= 49 && Math.abs(z) <= 13;
        if (!nearCliff && !nearHole || inDrop(x, z)) {
            return false;
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (inDrop(x + dx, z + dz)) {
                    return true;
                }
            }
        }
        return false;
    }

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

    /** True on the checkered half disc in front of a castle's front gate (outside the wall). */
    public static boolean onFrontPlaza(int x, int z) {
        int ax = Math.abs(x);
        return ax < CASTLE_FRONT && Math.hypot(ax - CASTLE_FRONT, z) <= FRONT_PLAZA_RADIUS;
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
