package io.github.sx2xoox.mcpl.core.map.siege;

/**
 * The plan of 협곡 요새 (see {@link SiegeMap}): one long gorge, about 175 x 80 blocks between cliffs, read along
 * {@code x} from the attackers' camp in the west to the defenders' keep in the east.
 *
 * <pre>
 *   x  -86 .. -66   attackers' camp (walled yard, 3 exits)          ground 64
 *   x  -66 .. -42   siege field (hamlet ruins north, quarry south)  ground 64-66
 *   x  -42 .. -28   the river gorge: stone bridge (main), rope bridge (north), ford + tunnel (south)
 *   x  -28 .. -15   killing ground before the outer wall
 *   x  -14 .. -11   outer wall: gatehouse (main), breach (north), tunnel under it (south)
 *   x  -10 .. 25    outer bailey: 성문 탑 at x 2, guardhouse (defender spawn 0), barracks
 *   x   26 .. 29    inner wall: gate (main), wall-walk (north), postern (south)
 *   x   30 .. 59    inner courtyard: 중앙 탑 at x 42, chapel (defender spawn 1), orchard
 *   x   60 .. 88    keep plateau (ground 69): square keep with 코어 at x 74, three doors, defender spawn 2
 * </pre>
 *
 * The three lanes run in bands: north {@code z <= -12}, main {@code |z| <= 8}, south {@code z >= 12}; they meet at
 * every tower platform and split again behind it.
 */
public final class SiegeLayout {

    private SiegeLayout() {
    }

    /** Top of the valley floor; players stand at 65. */
    public static final int GROUND_Y = 64;
    /** Top of the keep plateau. */
    public static final int PLATEAU_Y = 69;
    public static final int MIN_Y = 46;
    public static final int MAX_Y = 100;
    /** Half the canvas: the cliffs reach out to here. */
    public static final int BUILD_RADIUS = 92;
    /** The canvas edge on z (the map is a strip; columns beyond stay void). */
    public static final int HALF_WIDTH = 44;

    /** Valley interior bounds before the cliff's ragged face. */
    public static final int WEST_END = -86;
    public static final int EAST_END = 90;
    public static final int SIDE = 39;

    // ------------------------------------------------------------------ the parts along x

    public static final int CAMP_FRONT = -66;
    public static final int OUTER_WALL_WEST = -14;
    public static final int OUTER_WALL_EAST = -11;
    public static final int INNER_WALL_WEST = 26;
    public static final int INNER_WALL_EAST = 29;
    public static final int PLATEAU_EDGE = 60;
    /** Top block of both curtain walls (the wall-walk floor). */
    public static final int WALL_TOP = 72;

    /** Lane bands (z). */
    public static final int LANE_MAIN = 8;
    public static final int LANE_FLANK = 12;

    // ------------------------------------------------------------------ the river gorge

    /** Water surface (top water block) of the river in the gorge. */
    public static final int WATER_TOP = 58;
    /** River bed (top solid block under the water). */
    public static final int RIVER_BED = 54;
    /** Half width of the gorge. */
    public static final int GORGE_HALF = 5;
    /** Rope bridge (north) and ford (south) crossings. */
    public static final int ROPE_BRIDGE_Z = -28;
    public static final int FORD_Z = 26;
    /** Floor (top block) of the tunnel under the killing ground and the outer wall. */
    public static final int TUNNEL_FLOOR = 58;

    /** Centre line of the gorge at {@code z} (it winds a little). */
    public static int gorgeX(int z) {
        return (int) Math.round(-35 + 2.0 * Math.sin(z / 9.0));
    }

    public static boolean inGorge(int x, int z) {
        return Math.abs(x - gorgeX(z)) <= GORGE_HALF && isInterior(x, z);
    }

    // ------------------------------------------------------------------ fixed places

    /** Tower platforms: centre column and the platform radius kept free. */
    public static final int FRONT_X = 2;
    public static final int MIDDLE_X = 42;
    public static final int CORE_X = 74;
    public static final int TOWER_RADIUS = 6;

    /** The breach in the outer wall (north lane), centre z. */
    public static final int BREACH_Z = -26;
    /** The postern in the inner wall (south lane). */
    public static final int POSTERN_Z = 20;
    /** The guardhouse behind the front tower (defender spawn 0): inner x 15..21, z 9..15. */
    public static final int GUARD_X1 = 14;
    public static final int GUARD_X2 = 22;
    public static final int GUARD_Z1 = 8;
    public static final int GUARD_Z2 = 16;
    /** The chapel north of the courtyard (defender spawn 1): inner x 45..52, z -31..-23. */
    public static final int CHAPEL_X1 = 44;
    public static final int CHAPEL_X2 = 53;
    public static final int CHAPEL_Z1 = -32;
    public static final int CHAPEL_Z2 = -22;
    /** The stairs up to the keep plateau: main, north and south (z of their middle). */
    public static final int STAIR_NORTH_Z = -17;
    public static final int STAIR_SOUTH_Z = 17;
    /** The keep: walls two blocks thick around x 63..85, z -12..12. */
    public static final int KEEP_X1 = 61;
    public static final int KEEP_X2 = 87;
    public static final int KEEP_Z = 14;
    /** Top block of the keep walls (its wall-walk floor). */
    public static final int KEEP_TOP = 79;

    // ------------------------------------------------------------------ cliffs and ground

    /** True for the valley floor (everything inside the cliffs). */
    public static boolean isInterior(int x, int z) {
        return z <= SIDE - jag(x, 17) && -z <= SIDE - jag(x, 11) && x >= WEST_END + jag(z, 23) && x <= EAST_END - jag(z, 37);
    }

    /** How far the cliff face juts in (0..2) at {@code t} along it. */
    static int jag(int t, int salt) {
        double v = Math.sin(t / 4.3 + salt) + 0.6 * Math.sin(t / 1.7 + salt * 3.1);
        return v > 0.9 ? 2 : v > 0.1 ? 1 : 0;
    }

    /** Height of the cliff top at a cliff column. */
    public static int cliffTop(int x, int z) {
        double v = Math.sin(x / 7.0 + z * 0.3) + Math.sin(x / 13.0 - z / 5.0) + 0.5 * Math.sin((x + z) / 3.1);
        return 84 + (int) Math.round(v * 1.8);
    }

    /** Top of the natural ground of an interior column (before buildings). */
    public static int ground(int x, int z) {
        if (x >= PLATEAU_EDGE) {
            return PLATEAU_Y;
        }
        // Gentle rises in the siege field, kept off the main road and the camp.
        if (x > CAMP_FRONT + 2 && x < -44 && Math.abs(z) > 6) {
            double v = Math.sin(x / 6.0) * Math.cos(z / 7.0) + 0.7 * Math.sin((x - z) / 9.0);
            return GROUND_Y + Math.max(0, (int) Math.round(v));
        }
        return GROUND_Y;
    }

    /** Minecraft yaw (0 = +z, 90 = -x) for a look from {@code x1,z1} towards {@code x2,z2}. */
    public static float yaw(double x1, double z1, double x2, double z2) {
        return (float) Math.toDegrees(Math.atan2(-(x2 - x1), z2 - z1));
    }

    /** Which part of the map a column belongs to (zones, previews and audit reports). */
    public static String partOf(int x, int z) {
        if (!isInterior(x, z)) {
            return "cliffs";
        }
        if (x < -44) {
            return "camp";
        }
        if (x < -17) {
            return "river";
        }
        if (x < 25) {
            return "gate";
        }
        if (x < PLATEAU_EDGE) {
            return "court";
        }
        return "keep";
    }
}
