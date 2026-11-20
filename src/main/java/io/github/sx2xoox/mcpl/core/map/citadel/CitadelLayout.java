package io.github.sx2xoox.mcpl.core.map.citadel;

/**
 * The plan of 폐허의 성채 (map-local block coordinates, {@code +x} east, {@code +z} south, angles measured from
 * {@code +x} towards {@code +z}, so 90° is south and 270° is north):
 *
 * <pre>
 *   r  0..30   the keep (the citadel itself, the last place to fight when the border closes)
 *   r 31..36   the ring road around the keep
 *   r 37..96   six districts of 60° each, split by six radial roads (5 wide) at 0°, 60°, ... 300°
 *   r 97..124  the broken outer curtain wall (r about 100) with gates where the roads arrive, then hills that rise
 *              outside the play area, so a player knocked past the border lands on ground
 * </pre>
 * The play area (arena radius) is {@link #PLAY_RADIUS}. The ground's top block is at {@link #GROUND_Y} everywhere
 * the districts do not reshape it; every district keeps its edges along the roads at ground level and walkable.
 */
public final class CitadelLayout {

    public static final int GROUND_Y = 64;
    public static final int PLAY_RADIUS = 105;
    public static final int BUILD_RADIUS = 124;
    public static final int MIN_Y = 40;
    public static final int MAX_Y = 140;

    public static final int KEEP_RADIUS = 30;
    public static final int RING_ROAD_OUTER = 36;
    public static final int DISTRICT_OUTER = 96;
    public static final int WALL_RADIUS = 100;
    public static final double ROAD_HALF_WIDTH = 2.5;

    /** Angles (degrees) of the six radial roads. */
    public static final int[] ROAD_ANGLES = {0, 60, 120, 180, 240, 300};

    /** The six districts, clockwise from east-southeast. */
    public enum District {
        TOWN(0, "ruined town"),
        FOREST(1, "forest and boulders"),
        LAKE(2, "lake and bridges"),
        CANYON(3, "canyon"),
        GRAVEYARD(4, "graveyard and temple"),
        HILLS(5, "hills and watchtowers");

        public final int index;
        public final String label;

        District(int index, String label) {
            this.index = index;
            this.label = label;
        }

        /** First angle of the district (degrees); it spans 60°. */
        public int startAngle() {
            return index * 60;
        }

        /** Angle of the district's middle (degrees). */
        public int centerAngle() {
            return index * 60 + 30;
        }
    }

    private CitadelLayout() {
    }

    public static double radius(int x, int z) {
        return Math.sqrt((double) x * x + (double) z * z);
    }

    /** Angle of the column in degrees, 0 (east) .. 360, clockwise seen from above (90 = south). */
    public static double angle(int x, int z) {
        double degrees = Math.toDegrees(Math.atan2(z, x));
        return degrees < 0 ? degrees + 360 : degrees;
    }

    /** Distance from the column to the center line of the radial road at {@code angle} (only on its side). */
    public static double roadDistance(int x, int z, int angle) {
        double dx = Math.cos(Math.toRadians(angle));
        double dz = Math.sin(Math.toRadians(angle));
        double along = x * dx + z * dz;
        if (along <= 0) {
            return Double.MAX_VALUE;
        }
        return Math.abs(x * dz - z * dx);
    }

    /** Distance to the nearest radial road center line. */
    public static double nearestRoadDistance(int x, int z) {
        double best = Double.MAX_VALUE;
        for (int angle : ROAD_ANGLES) {
            best = Math.min(best, roadDistance(x, z, angle));
        }
        return best;
    }

    public static boolean isKeep(int x, int z) {
        return radius(x, z) <= KEEP_RADIUS;
    }

    public static boolean isRingRoad(int x, int z) {
        double r = radius(x, z);
        return r > KEEP_RADIUS && r <= RING_ROAD_OUTER;
    }

    /** A radial road between the ring road and the districts' outer edge. */
    public static boolean isRadialRoad(int x, int z) {
        double r = radius(x, z);
        return r > RING_ROAD_OUTER && r <= DISTRICT_OUTER && nearestRoadDistance(x, z) <= ROAD_HALF_WIDTH;
    }

    /** True for the columns of {@code district}. */
    public static boolean isDistrict(District district, int x, int z) {
        double r = radius(x, z);
        if (r <= RING_ROAD_OUTER || r > DISTRICT_OUTER || nearestRoadDistance(x, z) <= ROAD_HALF_WIDTH) {
            return false;
        }
        double a = angle(x, z);
        return a >= district.startAngle() && a < district.startAngle() + 60;
    }

    /** The outer ring: wall, gates and the hills beyond (including the road ends). */
    public static boolean isOuterRing(int x, int z) {
        double r = radius(x, z);
        return r > DISTRICT_OUTER && r <= BUILD_RADIUS;
    }

    /** A point at {@code radius} along {@code angleDegrees}, rounded to a block: {x, z}. */
    public static int[] polar(double radius, double angleDegrees) {
        double a = Math.toRadians(angleDegrees);
        return new int[] {(int) Math.round(Math.cos(a) * radius), (int) Math.round(Math.sin(a) * radius)};
    }
}
