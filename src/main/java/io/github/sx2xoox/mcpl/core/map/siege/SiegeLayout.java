package io.github.sx2xoox.mcpl.core.map.siege;

/**
 * The plan of 네온 시티 (see {@link SiegeMap}): one district of a neon megacity at night, about 320 x 177 blocks of
 * play area inside a ring of skyscrapers, read along {@code x} from the attackers' underpass in the west to the
 * megacorp headquarters in the east.
 *
 * <pre>
 *   x -160 .. -129   고가 하부: the attackers' spawn under an elevated highway                      street y 64
 *   x -128 ..  -79   네온 슬럼: market avenue, capsule blocks, the sunken canal starts
 *   x  -78 ..  -74   방화벽: the corporate perimeter wall (gate on the avenue, breach north, culvert south)
 *   x  -73 ..  -11   방화벽 구역: 방화벽 게이트 plaza at x -50, checkpoint (defender spawn 0)
 *   x  -10 ..   71   데이터 허브: round plaza at x 30 under a ring walkway, server farm (defender spawn 1)
 *   x   72 ..  160   메가코프 본사: forecourt, grand stairs up to the podium (roof y 80), 메인프레임 at x 120,
 *                    the HQ skyscraper (lobby = defender spawn 2)
 * </pre>
 *
 * Streets running along {@code x}: the main avenue ({@code |z| <= 10}), the north street ({@code z -56..-44}) and the
 * sunken canal ({@code z 44..56}, floor y 58). Cross streets run along {@code z} at the plazas and at
 * {@link #CROSS_STREETS}. Between them lie the city blocks with the buildings. Storeys are 5 blocks: floors at
 * y 64, 69, 74 (the skybridge level), 79, 84...
 */
public final class SiegeLayout {

    private SiegeLayout() {
    }

    /** Top of the street; players stand at 65. */
    public static final int GROUND_Y = 64;
    /** Top of the sunken canal's floor. */
    public static final int CANAL_Y = 58;
    /** Top of the headquarters podium (the core's floor). */
    public static final int PODIUM_Y = 80;
    /** Height of one storey. */
    public static final int STOREY = 5;
    /** Floor of the skybridges and the ring walkway (the third storey's floor). */
    public static final int BRIDGE_Y = GROUND_Y + 2 * STOREY;
    public static final int MIN_Y = 50;
    public static final int MAX_Y = 126;
    /** Half the canvas (x reaches out to here). */
    public static final int BUILD_RADIUS = 168;
    /** Outer edge of the skyline ring on z. */
    public static final int HALF_WIDTH = 96;

    /** The play area (inside the skyline ring). */
    public static final int WEST_END = -160;
    public static final int EAST_END = 160;
    public static final int SIDE = 88;

    // ------------------------------------------------------------------ streets

    /** Main avenue: {@code |z| <= MAIN_HALF}. */
    public static final int MAIN_HALF = 10;
    /** North street. */
    public static final int NORTH_Z1 = -56;
    public static final int NORTH_Z2 = -44;
    /** The sunken canal (the floor is {@link #CANAL_Y}); ledges at ground level run along both sides. */
    public static final int CANAL_Z1 = 44;
    public static final int CANAL_Z2 = 56;
    public static final int CANAL_X1 = -127;
    public static final int CANAL_X2 = 86;
    /** The canal's water channel (one block deep). */
    public static final int CHANNEL_Z1 = 52;
    public static final int CHANNEL_Z2 = 54;
    /** Cross streets (x ranges, inclusive) besides the two plazas. */
    public static final int[][] CROSS_STREETS = {{-104, -96}, {-20, -12}, {64, 72}};

    /** Inner and outer city blocks (z ranges). */
    public static final int INNER_NORTH_Z1 = -43;
    public static final int INNER_NORTH_Z2 = -11;
    public static final int INNER_SOUTH_Z1 = 11;
    public static final int INNER_SOUTH_Z2 = 41;
    public static final int OUTER_NORTH_Z2 = -57;
    public static final int OUTER_SOUTH_Z1 = 59;

    /** Lane bands of the audit: main {@code |z| <= LANE_MAIN}, flanks {@code |z| >= LANE_FLANK}. */
    public static final int LANE_MAIN = 12;
    public static final int LANE_FLANK = 16;

    // ------------------------------------------------------------------ sections

    /** East end of the attackers' start area (everything west of it is theirs). */
    public static final int START_EAST = -129;
    /** West edge of the firewall zone (a strip of street in front of the wall belongs to it). */
    public static final int FIREWALL_WEST = -82;
    public static final int WALL_X1 = -78;
    public static final int WALL_X2 = -74;
    /** Top block of the firewall wall. */
    public static final int WALL_TOP = 80;
    public static final int HUB_WEST = -10;
    public static final int HQ_WEST = 72;

    // ------------------------------------------------------------------ fixed places

    public static final int FRONT_X = -50;
    public static final int MIDDLE_X = 30;
    public static final int CORE_X = 120;
    /** Radius of the open, flat tower platforms. */
    public static final int TOWER_RADIUS = 10;
    /** Blocks above the crystal kept free for the tower model. */
    public static final int TOWER_CLEARANCE = 22;
    /** Radius of the round plazas around the front and middle towers. */
    public static final int PLAZA_RADIUS = 16;

    /** The grand stairs up to the podium: one step per x, from x 76 (y 65) to x 91 (y 80). */
    public static final int STAIRS_X1 = 76;
    /** The headquarters podium. */
    public static final int PODIUM_X1 = 92;
    public static final int PODIUM_Z = 60;
    /** The skyscraper on the podium (its lobby is defender spawn 2). */
    public static final int SKY_X1 = 138;
    public static final int SKY_X2 = 158;
    public static final int SKY_Z = 22;
    /** North parking ramp along the podium: z range and x range (rising east). */
    public static final int RAMP_Z1 = -68;
    public static final int RAMP_Z2 = -62;
    public static final int RAMP_X1 = 94;
    /** South stair tower next to the podium. */
    public static final int STAIR_TOWER_X1 = 98;
    public static final int STAIR_TOWER_X2 = 106;
    public static final int STAIR_TOWER_Z1 = 61;
    public static final int STAIR_TOWER_Z2 = 69;

    /** Checkpoint (defender spawn 0), server farm (defender spawn 1). */
    public static final int CHECKPOINT_X1 = -36;
    public static final int CHECKPOINT_X2 = -22;
    public static final int CHECKPOINT_Z1 = 14;
    public static final int CHECKPOINT_Z2 = 30;
    public static final int FARM_X1 = 51;
    public static final int FARM_X2 = 63;
    public static final int FARM_Z1 = -40;
    public static final int FARM_Z2 = -18;

    // ------------------------------------------------------------------ predicates

    /** True for the play area (everything inside the skyline ring). */
    public static boolean isInterior(int x, int z) {
        return x >= WEST_END && x <= EAST_END && Math.abs(z) <= SIDE;
    }

    public static boolean inCanal(int x, int z) {
        return x >= CANAL_X1 && x <= CANAL_X2 && z >= CANAL_Z1 && z <= CANAL_Z2;
    }

    /** True on the podium of the headquarters (its roof is {@link #PODIUM_Y}). */
    public static boolean onPodium(int x, int z) {
        return x >= PODIUM_X1 && x <= EAST_END && Math.abs(z) <= PODIUM_Z;
    }

    /** True inside the open plaza of the front or middle tower. */
    public static boolean inPlaza(int x, int z) {
        return near(x, z, FRONT_X, 0, PLAZA_RADIUS) || near(x, z, MIDDLE_X, 0, PLAZA_RADIUS);
    }

    static boolean near(int x, int z, int cx, int cz, int radius) {
        int dx = x - cx;
        int dz = z - cz;
        return dx * dx + dz * dz <= radius * radius;
    }

    /** True on a street (avenue, north street, cross streets, canal and its ledges, plazas, forecourt). */
    public static boolean isStreet(int x, int z) {
        if (!isInterior(x, z)) {
            return false;
        }
        if (x <= START_EAST || x >= HQ_WEST && !onPodium(x, z) || inPlaza(x, z)) {
            return true;
        }
        if (Math.abs(z) <= MAIN_HALF || z >= NORTH_Z1 && z <= NORTH_Z2
                || z >= CANAL_Z1 - 2 && z <= CANAL_Z2 + 2) {
            return true;
        }
        for (int[] street : CROSS_STREETS) {
            if (x >= street[0] && x <= street[1]) {
                return true;
            }
        }
        return Math.abs(x - FRONT_X) <= 4 || Math.abs(x - MIDDLE_X) <= 4;
    }

    /** Top of the floor of a column before buildings (the street, the canal or the podium). */
    public static int ground(int x, int z) {
        if (onPodium(x, z)) {
            return PODIUM_Y;
        }
        if (inCanal(x, z)) {
            return CANAL_Y;
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
            return "skyline";
        }
        if (x < FIREWALL_WEST) {
            return "slums";
        }
        if (x < HUB_WEST) {
            return "firewall";
        }
        if (x < HQ_WEST) {
            return "hub";
        }
        return "hq";
    }
}
