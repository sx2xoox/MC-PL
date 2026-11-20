package io.github.sx2xoox.speedrunbot.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class OceanSenseTest {
    private final OceanSense sense = new OceanSense(150, 15, 20);

    /** A water body: every column inside the rectangle has sources from the surface down to the floor. */
    private static void body(List<int[]> w, int x0, int z0, int x1, int z1, int floorY) {
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int y = OceanSense.SEA_Y; y > floorY; y--) {
                    w.add(new int[]{x, y, z});
                }
            }
        }
    }

    @Test
    void anOpenDeepSeaIsAnOcean() {
        List<int[]> w = new ArrayList<>();
        body(w, 0, 0, 59, 59, 44);
        OceanSense.Result r = sense.judge(w, new ArrayList<>());
        assertTrue(r.ocean, r.toString());
    }

    @Test
    void aRiverIsNarrowAndASwampIsShallow() {
        List<int[]> river = new ArrayList<>();
        body(river, 0, 0, 200, 11, 52); // 12 wide, 10 deep, long
        OceanSense.Result r = sense.judge(river, new ArrayList<>());
        assertFalse(r.ocean, r.toString());
        // a diagonal river: still no open water 8 blocks out in all directions
        List<int[]> diag = new ArrayList<>();
        for (int i = 0; i < 150; i++) {
            body(diag, i, i, i + 9, i + 1, 52);
        }
        assertFalse(sense.judge(diag, new ArrayList<>()).ocean);
        List<int[]> swamp = new ArrayList<>();
        body(swamp, 0, 0, 59, 59, 59); // wide but three deep
        OceanSense.Result s = sense.judge(swamp, new ArrayList<>());
        assertFalse(s.ocean, s.toString());
        // a lake above sea level does not count at all
        List<int[]> lake = new ArrayList<>();
        for (int[] c : swamp) {
            lake.add(new int[]{c[0], c[1] + 8, c[2]});
        }
        assertFalse(sense.judge(lake, new ArrayList<>()).ocean);
    }

    @Test
    void beachSandIsCountedAtShoreHeight() {
        List<int[]> sand = new ArrayList<>();
        sand.add(new int[]{0, 63, 0});
        sand.add(new int[]{0, 40, 0}); // sea floor sand is no beach
        assertTrue(sense.judge(new ArrayList<>(), sand).beach == 1);
    }
}
