package io.github.sx2xoox.speedrunbot.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HearingTest {
    @Test
    void lavaIsHeardOnlyWithin16Blocks() {
        Random r = new Random(1);
        int near = 0, far = 0;
        for (int t = 0; t < 4000; t++) {
            // one pool 8 blocks east of the listener, one 20 blocks west
            List<Hearing.Sound> s = Hearing.lavaSounds(0, 64, 0, 0.5, 65.6, 0.5,
                    (x, y, z) -> y == 64 && ((x >= 6 && x <= 10 && z >= -2 && z <= 2) || (x <= -18 && x >= -22 && z >= -2 && z <= 2)), r);
            for (Hearing.Sound snd : s) {
                if (snd.x > 0) {
                    near++;
                } else {
                    far++;
                }
                assertTrue(snd.volume >= 0.2f && snd.volume <= 0.4f);
            }
        }
        assertTrue(near > 20, "a 25-block pool 8 blocks away is heard every few seconds: " + near);
        assertEquals(0, far, "beyond 16 blocks nothing is heard");
    }

    @Test
    void perceivedSoundsAreRoughButCentredOnTheSource() {
        Random r = new Random(2);
        Hearing.Sound s = new Hearing.Sound(10.5, 60.5, 0.5, 0.3f);
        double sx = 0, sz = 0, maxOff = 0;
        int n = 2000;
        for (int i = 0; i < n; i++) {
            Hearing.Heard h = Hearing.perceive(s, 0.5, 61.6, 0.5, r);
            sx += h.x;
            sz += h.z;
            maxOff = Math.max(maxOff, Math.hypot(h.x - s.x, h.z - s.z));
        }
        assertEquals(10.5, sx / n, 1.5, "averaging many hearings points near the source");
        assertEquals(0.5, sz / n, 1.0);
        assertTrue(maxOff > 3, "single hearings are rough (no block position)");
    }
}
