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
        /** Vertical view: eye height, pitch (down positive) and vertical FOV; NaN eye = no vertical model. */
        public final double eyeY;
        public final float pitch;
        public final double vFovDeg;

        public Reading(double x, double z, float yaw, double hFovDeg, boolean hit) {
            this(x, Double.NaN, z, yaw, 0f, hFovDeg, 0, hit);
        }

        public Reading(double x, double eyeY, double z, float yaw, float pitch, double hFovDeg, double vFovDeg, boolean hit) {
            this.x = x;
            this.z = z;
            this.yaw = yaw;
            this.hFovDeg = hFovDeg;
            this.hit = hit;
            this.eyeY = eyeY;
            this.pitch = pitch;
            this.vFovDeg = vFovDeg;
        }
    }

    /** Heights a buried treasure chest can have (under a beach or the shallow sea floor; public generation knowledge). */
    public static final double CHEST_Y_LO = 40, CHEST_Y_HI = 66;

    private final List<Reading> readings = new ArrayList<>();
    /** Score columns by agreeing sweep places (see {@link #placeVotes}) instead of by single readings. */
    public boolean byPlace;

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
        if (angleDiff(yawTo(r.x, r.z, cx, cz), r.yaw) > r.hFovDeg / 2 + slack) {
            return false;
        }
        if (Double.isNaN(r.eyeY)) {
            return true;
        }
        // the chest's possible heights seen from here span a pitch interval; it must overlap the vertical view
        double hi = Math.toDegrees(Math.atan2(r.eyeY - CHEST_Y_LO, dist)); // lowest chest: steepest down
        double lo = Math.toDegrees(Math.atan2(r.eyeY - (CHEST_Y_HI + 1), dist));
        double vs = Math.toDegrees(Math.atan(0.71 / dist));
        return hi >= r.pitch - r.vFovDeg / 2 - vs && lo <= r.pitch + r.vFovDeg / 2 + vs;
    }

    /**
     * Agreement by sweep place: the number of places (readings taken within 3 blocks of each other) where some
     * reading that saw a chest had this column in view. Several chests (ocean ruins, wrecks, other treasures) make a
     * place's hits cover wide sectors; counting each place once keeps a sector's many hits from outweighing a column
     * that every place agrees on.
     */
    int placeVotes(double cx, double cz) {
        java.util.List<double[]> places = new java.util.ArrayList<>();
        java.util.List<Boolean> votes = new java.util.ArrayList<>();
        for (Reading r : readings) {
            int k = -1;
            for (int i = 0; i < places.size(); i++) {
                if (Math.hypot(places.get(i)[0] - r.x, places.get(i)[1] - r.z) < 3) {
                    k = i;
                    break;
                }
            }
            if (k < 0) {
                places.add(new double[]{r.x, r.z});
                votes.add(false);
                k = places.size() - 1;
            }
            if (r.hit && !votes.get(k) && inView(r, cx, cz)) {
                votes.set(k, true);
            }
        }
        int n = 0;
        for (boolean v : votes) {
            if (v) {
                n++;
            }
        }
        return n;
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
        java.util.Set<Long> ex = new java.util.HashSet<>();
        ex.add(key(exX, exZ));
        return best(x, z, range, ex);
    }

    /** Column key for the exclusion set (same packing as a block position with y 0 is not needed: plain x,z). */
    public static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    /** As {@link #best(double, double, double)}, skipping the excluded columns (keys from {@link #key}). */
    public int[] best(double x, double z, double range, java.util.Collection<Long> excluded) {
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
                if (d > range || excluded.contains(key(cx, cz))) {
                    continue;
                }
                double s = byPlace ? placeVotes(cx + 0.5, cz + 0.5) : score(cx + 0.5, cz + 0.5);
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
