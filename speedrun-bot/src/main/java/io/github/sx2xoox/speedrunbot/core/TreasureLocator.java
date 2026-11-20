package io.github.sx2xoox.speedrunbot.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Finds the buried treasure's column from pie chart readings (see {@link PieChart}): each reading is "from here,
 * looking along this yaw with this horizontal FOV, a chest is / is not in view". In 1.16 a buried treasure chest
 * sits in the column at chunk offset 9,9 (x &amp; 15 == 9, z &amp; 15 == 9), so the answer is the 9,9 column that agrees
 * with the most readings (hits weigh more than misses: a miss can be a chest above or below the vertical view),
 * nearest first on ties. Two sweeps from places some blocks apart pin it down (triangulation); one sweep leaves a
 * line of candidates, of which the nearest is taken.
 */
public final class TreasureLocator {
    /** One reading. */
    public static final class Reading {
        public final double x, z;
        public final float yaw;
        public final double hFovDeg;
        public final boolean hit;

        public Reading(double x, double z, float yaw, double hFovDeg, boolean hit) {
            this.x = x;
            this.z = z;
            this.yaw = yaw;
            this.hFovDeg = hFovDeg;
            this.hit = hit;
        }
    }

    private final List<Reading> readings = new ArrayList<>();

    public void add(Reading r) {
        readings.add(r);
    }

    public int hits() {
        int n = 0;
        for (Reading r : readings) {
            if (r.hit) {
                n++;
            }
        }
        return n;
    }

    public List<Reading> readings() {
        return readings;
    }

    /** Minecraft yaw (0 = +z, 90 = -x) from (x,z) toward (tx,tz). */
    public static float yawTo(double x, double z, double tx, double tz) {
        return (float) Math.toDegrees(Math.atan2(-(tx - x), tz - z));
    }

    public static double angleDiffPublic(double a, double b) {
        return angleDiff(a, b);
    }

    static double angleDiff(double a, double b) {
        double d = (a - b) % 360;
        if (d > 180) {
            d -= 360;
        }
        if (d < -180) {
            d += 360;
        }
        return Math.abs(d);
    }

    /** True when the column centre lies inside the reading's horizontal view (plus half a block of slack). */
    static boolean inView(Reading r, double cx, double cz) {
        double dist = Math.max(1, Math.hypot(cx - r.x, cz - r.z));
        double slack = Math.toDegrees(Math.atan(0.71 / dist));
        return angleDiff(yawTo(r.x, r.z, cx, cz), r.yaw) <= r.hFovDeg / 2 + slack;
    }

    /** Agreement score of a column with all readings. */
    double score(double cx, double cz) {
        double s = 0;
        for (Reading r : readings) {
            boolean in = inView(r, cx, cz);
            if (r.hit && in) {
                s += 1;
            } else if (!r.hit && in) {
                s -= 0.25;
            }
        }
        return s;
    }

    /**
     * The best 9,9 column within {@code range} blocks of (x,z), or null when no reading saw a chest. Returns
     * {x, z, score*100, candidates with that score}.
     */
    public int[] best(double x, double z, double range) {
        return best(x, z, range, -1, -1);
    }

    /** As {@link #best(double, double, double)}, skipping the column (exX, exZ) (already dug). */
    public int[] best(double x, double z, double range, int exX, int exZ) {
        if (hits() == 0) {
            return null;
        }
        int[] best = null;
        double bestS = 0.5, bestD = Double.MAX_VALUE;
        int ties = 0;
        int x0 = (int) Math.floor((x - range) / 16) * 16 + 9, z0 = (int) Math.floor((z - range) / 16) * 16 + 9;
        for (int cx = x0; cx <= x + range; cx += 16) {
            for (int cz = z0; cz <= z + range; cz += 16) {
                double d = Math.hypot(cx + 0.5 - x, cz + 0.5 - z);
                if (d > range || (cx == exX && cz == exZ)) {
                    continue;
                }
                double s = score(cx + 0.5, cz + 0.5);
                if (s > bestS + 1e-9 || (Math.abs(s - bestS) < 1e-9 && d < bestD)) {
                    if (s > bestS + 1e-9) {
                        ties = 0;
                    }
                    bestS = s;
                    bestD = d;
                    best = new int[]{cx, cz, (int) Math.round(s * 100), 0};
                }
                if (Math.abs(s - bestS) < 1e-9) {
                    ties++;
                }
            }
        }
        if (best != null) {
            best[3] = ties;
        }
        return best;
    }

    /** Mean yaw of the hit readings taken at (about) this place, or NaN: the bearing of the strongest signal. */
    public double bearingFrom(double x, double z) {
        double sx = 0, sz = 0;
        int n = 0;
        for (Reading r : readings) {
            if (r.hit && Math.hypot(r.x - x, r.z - z) < 2) {
                sx += Math.cos(Math.toRadians(r.yaw));
                sz += Math.sin(Math.toRadians(r.yaw));
                n++;
            }
        }
        return n == 0 ? Double.NaN : Math.toDegrees(Math.atan2(sz, sx));
    }
}
