package io.github.sx2xoox.speedrunbot.core;

import java.util.HashSet;
import java.util.Set;

/**
 * "Is this an ocean?" from the water the bot has seen, as a runner judges a spawn at a glance: an open sea is a wide
 * sheet of water at sea level (y 62 sources) that goes on in every direction, deeper than a river or a swamp, usually
 * with a sand beach. Rivers are sea-level water too but narrow; swamps are wide but one to three blocks deep; lakes
 * sit at other heights or are small. Inputs are only seen blocks (water sources and sand positions).
 */
public final class OceanSense {
    /** Sea level water surface (the top water source layer). */
    public static final int SEA_Y = 62;

    /** What was measured, and the verdict. */
    public static final class Result {
        /** Seen sea-level water sources (top layer). */
        public int surface;
        /** Surface cells with sea-level water at distance {@link #INTERIOR_R} in all eight directions: open water. */
        public int interior;
        /** Seen water sources at least {@link #DEEP_BELOW} blocks under the surface. */
        public int deep;
        /** Seen sand at the shore height (beach). */
        public int beach;
        public boolean ocean;

        @Override
        public String toString() {
            return "surface " + surface + " open " + interior + " deep " + deep + " beach " + beach + (ocean ? " -> OCEAN" : " -> no ocean");
        }
    }

    public static final int INTERIOR_R = 8;
    public static final int DEEP_BELOW = 7;

    private final int minSurface;
    private final int minInterior;
    private final int minDeep;

    public OceanSense(int minSurface, int minInterior, int minDeep) {
        this.minSurface = minSurface;
        this.minInterior = minInterior;
        this.minDeep = minDeep;
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    /**
     * @param water seen water sources as {x, y, z}
     * @param sand  seen sand blocks as {x, y, z}
     */
    public Result judge(Iterable<int[]> water, Iterable<int[]> sand) {
        Result r = new Result();
        Set<Long> top = new HashSet<>();
        for (int[] w : water) {
            if (w[1] == SEA_Y) {
                top.add(key(w[0], w[2]));
            } else if (w[1] <= SEA_Y - DEEP_BELOW && w[1] >= SEA_Y - 40) {
                r.deep++;
            }
        }
        r.surface = top.size();
        int d = INTERIOR_R, e = (int) Math.round(INTERIOR_R * 0.7071);
        int[][] probes = {{d, 0}, {-d, 0}, {0, d}, {0, -d}, {e, e}, {-e, e}, {e, -e}, {-e, -e}};
        for (long k : top) {
            int x = (int) (k >> 32), z = (int) k;
            boolean open = true;
            for (int[] o : probes) {
                if (!top.contains(key(x + o[0], z + o[1]))) {
                    open = false;
                    break;
                }
            }
            if (open) {
                r.interior++;
            }
        }
        for (int[] s : sand) {
            if (s[1] >= SEA_Y - 3 && s[1] <= SEA_Y + 4) {
                r.beach++;
            }
        }
        r.ocean = r.surface >= minSurface && r.interior >= minInterior && r.deep >= minDeep;
        return r;
    }
}
