package io.github.sx2xoox.mcpl.game.augment.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;
import java.util.Random;

import org.junit.jupiter.api.Test;

/**
 * The bow aim against vanilla arrow flight (move, drag 0.99, gravity 0.05): the drag-aware solution lands where the
 * earlier drag-free estimate falls short and under-leads. Prints the hit rates by distance (the measurement in
 * docs/augment.md).
 */
class BallisticsTest {

    /** A shot within this many blocks of the aim point hits a player's body (0.6 wide, 1.8 tall, aimed at 60 %). */
    static final double HIT = 0.55;

    @Test
    void aStillTargetIsHitAtEveryRangeTheBotsShoot() {
        for (int distance = 6; distance <= 58; distance += 2) {
            for (double dy : new double[] {-8, -2, 0, 3, 10}) {
                double[] from = {0, 0, 0};
                double[] to = {distance, dy, 0};
                double[] launch = Ballistics.launch(from, to, new double[3], 1);
                assertEquals(Ballistics.ARROW_SPEED, Math.sqrt(launch[0] * launch[0] + launch[1] * launch[1]
                        + launch[2] * launch[2]), 1.0E-9);
                assertTrue(Ballistics.miss(from, launch, to, new double[3]) < 0.1,
                        "hits a still target " + distance + " out, " + dy + " up");
            }
        }
    }

    @Test
    void aRunningTargetIsLedByTheRealFlightTime() {
        double[] from = {0, 1.6, 0};
        double[] to = {40, 1, 0};
        double[] sideways = {0, 0, 0.28}; // sprinting across the line of fire
        double[] launch = Ballistics.launch(from, to, sideways, 1);
        assertTrue(Ballistics.miss(from, launch, to, sideways) < 0.2, "meets a sprinting target 40 blocks out");
        double[] old = Ballistics.launchSimple(from, to, sideways, 1);
        assertTrue(Ballistics.miss(from, old, to, sideways) > Ballistics.miss(from, launch, to, sideways));
    }

    /** Hit rates of the drag-aware aim and the earlier one over many shots (still, walking and sprinting targets). */
    @Test
    void theDragAwareAimHitsMoreLongShots() {
        Random random = new Random(7);
        StringBuilder table = new StringBuilder("| 거리 | 예전 명중 | 새 명중 | 예전 평균 빗나감 | 새 평균 빗나감 |\n|---|---|---|---|---|\n");
        int oldTotal = 0;
        int newTotal = 0;
        int shots = 0;
        for (int distance = 10; distance <= 60; distance += 10) {
            int oldHits = 0;
            int newHits = 0;
            double oldMiss = 0;
            double newMiss = 0;
            int n = 400;
            for (int i = 0; i < n; i++) {
                double[] from = {0, 1.6, 0};
                double angle = random.nextDouble() * Math.PI * 2;
                double[] to = {distance, 1.0 + (random.nextDouble() * 8 - 4), 0};
                double speed = new double[] {0, 0.215, 0.28}[random.nextInt(3)]; // still, walking, sprinting
                double[] v = {Math.cos(angle) * speed, 0, Math.sin(angle) * speed};
                double om = Ballistics.miss(from, Ballistics.launchSimple(from, to, v, 1), to, v);
                double nm = Ballistics.miss(from, Ballistics.launch(from, to, v, 1), to, v);
                oldHits += om < HIT ? 1 : 0;
                newHits += nm < HIT ? 1 : 0;
                oldMiss += om;
                newMiss += nm;
            }
            oldTotal += oldHits;
            newTotal += newHits;
            shots += n;
            table.append(String.format(Locale.ROOT, "| %d | %.0f%% | %.0f%% | %.2f | %.2f |%n", distance,
                    100.0 * oldHits / n, 100.0 * newHits / n, oldMiss / n, newMiss / n));
        }
        System.out.println("[ballistics]\n" + table);
        assertTrue(newTotal > oldTotal, "the drag-aware aim hits more");
        assertTrue(newTotal >= shots * 0.97, "and nearly every shot with a perfect lead: " + newTotal + "/" + shots);
    }
}
