package io.github.sx2xoox.speedrunbot.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Picks the overworld route from what the bot has seen so far. Each route gets an expected time in seconds (a
 * utility = minus that time) from a small cost model whose weights are tunable params; the cheapest feasible route
 * wins. The model is deliberately simple: the tuner adjusts its numbers against measured runs.
 */
public final class RouteSelector {

    public enum Route {
        /** Dig down to the lava level (y ~ 11), mine iron on the way, cast the portal next to cave lava. */
        DIG_CAST,
        /** A lava pool is visible on the surface: mine iron where seen, cast the portal next to that pool. */
        SURFACE_CAST,
        /**
         * A shipwreck is in view: loot it, follow its treasure map, dig up the buried treasure; its iron replaces
         * mining (then the portal is cast as in the other routes).
         */
        TREASURE
    }

    /** What the bot knows when it chooses (distances in blocks, negative = not seen). */
    public static final class Observation {
        public double surfaceLavaDist = -1;
        public double waterDist = -1;
        public double lavaToWaterDist = -1;
        public int ironKnown;
        public double nearestIronDist = -1;
        public double shipDist = -1;

        public Observation ship(double d) {
            shipDist = d;
            return this;
        }

        public Observation surfaceLava(double d) {
            surfaceLavaDist = d;
            return this;
        }

        public Observation water(double d) {
            waterDist = d;
            return this;
        }

        public Observation lavaToWater(double d) {
            lavaToWaterDist = d;
            return this;
        }

        public Observation iron(int count, double nearest) {
            ironKnown = count;
            nearestIronDist = nearest;
            return this;
        }
    }

    public static final class Choice {
        public final Route route;
        public final double expectedSec;

        Choice(Route route, double expectedSec) {
            this.route = route;
            this.expectedSec = expectedSec;
        }

        @Override
        public String toString() {
            return route + "(" + Math.round(expectedSec) + "s)";
        }
    }

    private final Params p;

    public RouteSelector(Params p) {
        this.p = p;
    }

    public double expectedSeconds(Route r, Observation o) {
        double secPerBlock = p.get("route.secPerBlock");
        switch (r) {
            case DIG_CAST: {
                double water = o.waterDist >= 0 ? o.waterDist * secPerBlock : p.get("route.noWaterSec");
                return p.get("route.digBaseSec") + water;
            }
            case SURFACE_CAST: {
                if (o.surfaceLavaDist < 0 || o.surfaceLavaDist > p.get("surface.lavaMaxDist")) {
                    return Double.POSITIVE_INFINITY;
                }
                int ironMissing = Math.max(0, p.getInt("iron.needed") - o.ironKnown);
                double water = o.lavaToWaterDist >= 0 ? o.lavaToWaterDist * secPerBlock * 2 : p.get("route.noWaterSec");
                return p.get("route.surfaceBaseSec") + o.surfaceLavaDist * secPerBlock
                        + ironMissing * p.get("route.ironMissingSec") + water;
            }
            case TREASURE: {
                if (o.shipDist < 0 || o.shipDist > p.get("treasure.shipDist")) {
                    return Double.POSITIVE_INFINITY;
                }
                double water = o.waterDist >= 0 ? 0 : p.get("route.noWaterSec");
                return p.get("route.treasureBaseSec") + o.shipDist * secPerBlock + water;
            }
            default:
                return Double.POSITIVE_INFINITY;
        }
    }

    /** All routes ranked by expected time, best first; infeasible ones (infinite time) are left out. */
    public List<Choice> rank(Observation o) {
        List<Choice> out = new ArrayList<>();
        for (Route r : Route.values()) {
            double s = expectedSeconds(r, o);
            if (!Double.isInfinite(s)) {
                out.add(new Choice(r, s));
            }
        }
        Collections.sort(out, Comparator.comparingDouble(c -> c.expectedSec));
        return out;
    }

    public Choice choose(Observation o) {
        List<Choice> ranked = rank(o);
        return ranked.isEmpty() ? new Choice(Route.DIG_CAST, Double.POSITIVE_INFINITY) : ranked.get(0);
    }
}
