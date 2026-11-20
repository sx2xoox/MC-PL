package io.github.sx2xoox.speedrunbot.core;

/**
 * When an RSG runner would reset the seed, decided from what the bot perceived so far (never from the world): a
 * spawn with nothing to work with, or a split far behind pace. The per-seed evaluation does not stop at a reset
 * decision (so it stays comparable); the decision is recorded (tick and reason) and the RSG-style report treats the
 * attempt as ended there, which is exact because the decision only used what was known by then.
 */
public final class ResetPolicy {
    /** What the bot knows at a checkpoint. */
    public static final class State {
        public long tick;
        public boolean scanned;
        public int logsSeen;
        public boolean oceanSeen;
        /** -1 unknown (no sweep), 0 swept and no chest signal, 1 signal. */
        public int treasureSignal = -1;
        public long waterBucketTick = -1;
        public long ironTick = -1;
        public long portalSiteTick = -1;
        /** Ocean route checkpoints: treasure opened, magma ravine found (-1 not yet). */
        public long treasureTick = -1;
        /** The treasure search gave up (no candidate worth the trip, or the check found no chest). */
        public boolean treasureGaveUp;
        /** The ocean route (boat, ravine, door pocket) failed: the rest would be the slow general route. */
        public boolean oceanFailed;
        public long ravineTick = -1;
        /** A boat carried or ridden. */
        public boolean hasBoat;
    }

    private final Params p;

    public ResetPolicy(Params p) {
        this.p = p;
    }

    /** The reason to reset now, or null to play on. */
    public String decide(State s) {
        if (p.get("reset.oceanOnly") > 0) {
            // the ocean route policy: only ocean spawns with a treasure signal are played
            if (s.scanned && !s.oceanSeen) {
                return "spawn_no_ocean";
            }
            if (s.oceanSeen && s.treasureSignal == 0) {
                return "ocean_no_treasure_signal";
            }
            if (s.oceanSeen && s.oceanFailed) {
                return "ocean_route_failed";
            }
            if (s.oceanSeen && s.treasureGaveUp && s.treasureTick < 0) {
                return "ocean_no_treasure"; // the early decision found no column worth the trip (or it was not there)
            }
            if (s.oceanSeen && s.treasureSignal == 1 && s.logsSeen == 0 && !s.hasBoat && p.get("reset.oceanNoTrees") > 0) {
                return "ocean_no_trees"; // the boat (and the door) need wood: none seen during the whole first sweep
            }
            if (s.oceanSeen && s.treasureTick < 0 && s.tick > 20 * p.get("reset.treasureBySec")) {
                return "slow_treasure";
            }
            if (s.oceanSeen && s.ravineTick < 0 && s.tick > 20 * p.get("reset.ravineBySec")) {
                return "slow_ravine";
            }
            if (s.oceanSeen && s.tick > 20 * p.get("reset.netherBySec")) {
                return "slow_nether";
            }
            return null;
        }
        if (s.scanned && s.tick <= 20 * 20 && s.logsSeen == 0 && !s.oceanSeen) {
            return "spawn_no_trees";
        }
        if (s.oceanSeen && s.treasureSignal == 0 && s.logsSeen == 0) {
            return "ocean_no_treasure_no_trees";
        }
        if (s.ironTick < 0 && s.tick > 20 * p.get("reset.ironBySec")) {
            return "slow_iron";
        }
        if (s.waterBucketTick < 0 && s.tick > 20 * p.get("reset.waterBySec")) {
            return "slow_water";
        }
        if (s.portalSiteTick < 0 && s.tick > 20 * p.get("reset.portalSiteBySec")) {
            return "slow_lava";
        }
        return null;
    }
}
