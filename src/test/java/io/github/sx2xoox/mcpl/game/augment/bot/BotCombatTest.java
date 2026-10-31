package io.github.sx2xoox.mcpl.game.augment.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Leading by real movement, the difficulty ladder of the fight skills and the match's fight numbers. */
class BotCombatTest {

    @Test
    void motionIsMeasuredFromPositionsNotTheServerVelocity() {
        MotionTracker motion = new MotionTracker();
        for (int tick = 0; tick <= 20; tick++) {
            motion.update("p", tick * 0.28, 64, 0, tick); // sprinting along x: 5.6 blocks a second
        }
        double[] v = motion.velocity("p");
        assertEquals(0.28, v[0], 0.01);
        assertEquals(0, v[2], 1.0E-9);
        assertEquals(0, motion.velocity("unknown")[0], 1.0E-9);
    }

    @Test
    void aTeleportStartsTheEstimateOver() {
        MotionTracker motion = new MotionTracker();
        motion.update("p", 0, 64, 0, 0);
        motion.update("p", 0.2, 64, 0, 1);
        motion.update("p", 50, 64, 0, 2); // a pearl, a respawn: not 50 blocks a tick
        assertEquals(0, motion.velocity("p")[0], 1.0E-9);
        motion.forget("p");
        assertEquals(0, motion.velocity("p")[0], 1.0E-9);
    }

    @Test
    void aLeadByMeasuredMotionMeetsAStrafingTarget() {
        // a target 20 blocks off running sideways: the shot aims where it will be, not where it stands
        double[] still = Ballistics.launch(new double[] {0, 1.6, 0}, new double[] {20, 1, 0}, new double[3], 1);
        double[] led = Ballistics.launch(new double[] {0, 1.6, 0}, new double[] {20, 1, 0},
                new double[] {0, 0, 0.28}, 1);
        assertEquals(0, still[2], 1.0E-9);
        assertTrue(led[2] > 0.03, "leads to the side it runs to");
    }

    @Test
    void harderBotsDodgeArrowsAndSpaceTheirHitsMore() {
        BotDifficulty[] ladder = BotDifficulty.values();
        for (int i = 1; i < ladder.length; i++) {
            BotProfile easier = BotProfile.preset(ladder[i - 1]);
            BotProfile harder = BotProfile.preset(ladder[i]);
            assertTrue(harder.dodgeChance() > easier.dodgeChance(), ladder[i] + " dodges more");
            assertTrue(harder.spacing() > easier.spacing(), ladder[i] + " spaces more");
        }
        assertEquals(0, BotProfile.preset(BotDifficulty.EASY).spacing(), 1.0E-9, "easy trades blows face to face");
        assertTrue(BotProfile.preset(BotDifficulty.EASY).toConfig().containsKey("dodge-chance"));
    }

    @Test
    void theStatsSumUpEachDifficulty() {
        BotStats stats = new BotStats();
        stats.add("a", "철수", "hard", "hunter");
        stats.add("b", "영희", "normal", "tank");
        stats.swing("a", true, true);
        stats.swing("a", true, false);
        stats.swing("a", false, false);
        stats.damage("a", "b", 6, false, true);
        stats.damage("a", "b", 4, false, true);
        stats.arrow("a");
        stats.damage("a", "b", 5, true, false);
        stats.damage("b", "a", 3, false, true);
        stats.damage("x", "b", 2, false, false); // a human's fire: taken, nobody's line
        stats.apple("b");
        stats.died("b", 2, "bot", "a");
        stats.won("a");
        List<String> lines = stats.report();
        String hard = lines.stream().filter(l -> l.startsWith("hard:")).findFirst().orElseThrow();
        assertTrue(hard.contains("wins=1"), hard);
        assertTrue(hard.contains("kills=1"), hard);
        assertTrue(hard.contains("swings=3 melee=2 crit=0.50"), hard);
        assertTrue(hard.contains("arrows=1/1"), hard);
        assertTrue(hard.contains("hits/taken=3/1 (3.00)"), hard);
        String normal = lines.stream().filter(l -> l.startsWith("normal:")).findFirst().orElseThrow();
        assertTrue(normal.contains("avgPlace=2.0"), normal);
        assertTrue(normal.contains("hits/taken=1/4"), normal);
        assertTrue(normal.contains("apples=1"), normal);
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("영희 [normal·tank] place=2") && l.contains("death=bot")));
    }
}
