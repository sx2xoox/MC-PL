package io.github.sx2xoox.speedrunbot.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PieChartTest {
    @Test
    void narrowWindowNarrowsTheHorizontalFov() {
        assertEquals(102.0, PieChart.View.normal(10).hFovDeg(), 1.0);
        PieChart.View thin = new PieChart.View(70, 0.1, 10);
        assertEquals(8.0, thin.hFovDeg(), 0.3);
        // the FOV slider is clamped to what the options allow
        assertEquals(30.0, new PieChart.View(5, 1, 10).vFovDeg, 1e-9);
    }

    @Test
    void frustumTestMatchesTheViewDirection() {
        PieChart.View thin = new PieChart.View(70, 0.1, 10);
        // yaw 0 looks along +z: a chest 60 blocks ahead, 10 below the eyes, is in view; 15 blocks to the side is not
        assertTrue(PieChart.blockInFrustum(thin, 0.5, 70, 0.5, 0f, 0f, 0, 60, 60));
        assertFalse(PieChart.blockInFrustum(thin, 0.5, 70, 0.5, 0f, 0f, 15, 60, 60));
        assertFalse(PieChart.blockInFrustum(thin, 0.5, 70, 0.5, 180f, 0f, 0, 60, 60)); // behind
        // the normal wide view sees it at 15 to the side
        assertTrue(PieChart.blockInFrustum(PieChart.View.normal(10), 0.5, 70, 0.5, 0f, 0f, 15, 60, 60));
        // yaw 90 looks along -x
        assertTrue(PieChart.blockInFrustum(thin, 0.5, 70, 0.5, 90f, 0f, -80, 62, 0));
        // occlusion is not tested (the client culls block entities by frustum only), render distance is
        assertFalse(PieChart.blockInFrustum(thin, 0.5, 70, 0.5, 0f, 0f, 0, 60, 16 * 12));
        // a box straddling the edge of the view counts (any corner inside)
        double half = Math.toDegrees(Math.atan(Math.tan(Math.toRadians(35)) * 0.1));
        int side = (int) Math.floor(Math.tan(Math.toRadians(half)) * 100.0);
        assertTrue(PieChart.blockInFrustum(thin, 0.5, 60.5, 0.5, 0f, 0f, side, 60, 100));
    }

    @Test
    void sweepsFromTwoPlacesFindTheTreasureColumn() {
        int tx = 16 * 5 + 9, tz = -16 * 3 + 9, ty = 58; // a 9,9 column
        PieChart.View thin = new PieChart.View(70, 0.1, 10);
        TreasureLocator loc = new TreasureLocator();
        double[][] places = {{0.5, 0.5}, {20.5, -15.5}};
        for (double[] at : places) {
            for (int yaw = -180; yaw < 180; yaw += 6) {
                boolean hit = PieChart.blockInFrustum(thin, at[0], 66.6, at[1], yaw, 10f, tx, ty, tz);
                loc.add(new TreasureLocator.Reading(at[0], at[1], yaw, thin.hFovDeg(), hit));
            }
        }
        assertTrue(loc.hits() >= 2);
        int[] best = loc.best(0.5, 0.5, 150);
        assertNotNull(best);
        assertEquals(tx, best[0]);
        assertEquals(tz, best[1]);
    }

    @Test
    void noSignalNoGuess() {
        TreasureLocator loc = new TreasureLocator();
        loc.add(new TreasureLocator.Reading(0, 0, 0f, 8, false));
        assertEquals(null, loc.best(0, 0, 150));
    }

    @Test
    void resetPolicyUsesOnlyWhatWasSeen() {
        ResetPolicy rp = new ResetPolicy(Params.defaults().with("reset.oceanOnly", 0));
        ResetPolicy.State s = new ResetPolicy.State();
        s.scanned = true;
        s.tick = 100;
        assertEquals("spawn_no_trees", rp.decide(s));
        s.logsSeen = 5;
        assertEquals(null, rp.decide(s));
        s.tick = 20 * 300;
        assertEquals("slow_iron", rp.decide(s));
        s.ironTick = 20 * 100;
        assertEquals(null, rp.decide(s));
        s.tick = 20 * 400;
        assertEquals("slow_water", rp.decide(s));
    }

    @Test
    void oceanRoutePolicyResetsWhatIsNotAnOceanWithATreasure() {
        ResetPolicy rp = new ResetPolicy(Params.defaults());
        ResetPolicy.State s = new ResetPolicy.State();
        s.tick = 60;
        assertEquals(null, rp.decide(s)); // still scanning
        s.scanned = true;
        assertEquals("spawn_no_ocean", rp.decide(s));
        s.oceanSeen = true;
        assertEquals(null, rp.decide(s)); // sweeping
        s.treasureSignal = 0;
        assertEquals("ocean_no_treasure_signal", rp.decide(s));
        s.treasureSignal = 1;
        s.tick = 20 * 200;
        assertEquals("slow_treasure", rp.decide(s));
        s.treasureTick = 20 * 80;
        assertEquals(null, rp.decide(s));
        s.tick = 20 * 300;
        assertEquals("slow_ravine", rp.decide(s));
    }

    @Test
    void twoPitchBandsSkipDeepChestsAndKeepTheTreasure() {
        int tx = 16 * 4 + 9, tz = 16 * 2 + 9, ty = 56; // the treasure, about 76 blocks away
        int[] deep = {-40, 22, 30}; // a mineshaft chest: steep below the eyes
        PieChart.View v = new PieChart.View(30, 0.27, 10);
        TreasureLocator loc = new TreasureLocator();
        double[][] places = {{0.5, 0.5}, {-15.5, 20.5}};
        for (double[] at : places) {
            for (float pitch : new float[]{4f, 18f}) {
                for (int yaw = -180; yaw < 180; yaw += 6) {
                    boolean hit = PieChart.blockInFrustum(v, at[0], 66.6, at[1], yaw, pitch, tx, ty, tz)
                            || PieChart.blockInFrustum(v, at[0], 66.6, at[1], yaw, pitch, deep[0], deep[1], deep[2]);
                    loc.add(new TreasureLocator.Reading(at[0], 66.6, at[1], yaw, pitch, v.hFovDeg(), v.vFovDeg, hit));
                }
            }
        }
        int[] best = loc.best(0.5, 0.5, 150);
        assertNotNull(best);
        assertEquals(tx, best[0]);
        assertEquals(tz, best[1]);
    }
}
