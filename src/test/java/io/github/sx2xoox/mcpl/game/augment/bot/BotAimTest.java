package io.github.sx2xoox.mcpl.game.augment.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.Location;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

/** The bots' camera math: Minecraft angles, the short way round, turn speed, flicks and the body's lag. */
class BotAimTest {

    @Test
    void anglesWrapIntoOneTurn() {
        assertEquals(0f, BotAim.wrap(360f), 1.0E-4);
        assertEquals(180f, BotAim.wrap(-180f), 1.0E-4);
        assertEquals(-170f, BotAim.wrap(190f), 1.0E-4);
        assertEquals(10f, BotAim.wrap(730f), 1.0E-4);
        assertEquals(20f, BotAim.delta(170f, -170f), 1.0E-4, "across ±180 the short way");
        assertEquals(-20f, BotAim.delta(-170f, 170f), 1.0E-4);
    }

    @Test
    void yawAndPitchMatchMinecraft() {
        for (double[] dir : new double[][] {{0, 0, 1}, {-1, 0, 0}, {1, 0, 0}, {0, 0, -1}, {1, 0.5, 2}, {-3, -1, 1}}) {
            Location look = new Location(null, 0, 0, 0);
            look.setDirection(new Vector(dir[0], dir[1], dir[2]));
            assertEquals(0f, BotAim.delta(look.getYaw(), BotAim.yaw(dir[0], dir[2], 0f)), 1.0E-3);
            assertEquals(look.getPitch(), BotAim.pitch(dir[0], dir[1], dir[2]), 1.0E-3);
        }
        assertEquals(42f, BotAim.yaw(0, 0, 42f), "no direction: keep");
        assertEquals(-90f, BotAim.pitch(0, 1, 0), 1.0E-4, "straight up");
    }

    @Test
    void turningIsLimitedAndTakesTheShortWay() {
        assertEquals(-160f, BotAim.turn(170f, -150f, 30f, false), 1.0E-4, "through 180, not back through 0");
        assertEquals(-150f, BotAim.turn(170f, -150f, 40f, false), 1.0E-4, "arrives");
        assertEquals(20f, BotAim.turn(0f, 179f, 20f, false), 1.0E-4);
        float turned = 0f;
        int ticks = 0;
        while (BotAim.delta(turned, 180f) != 0 && ticks < 100) {
            turned = BotAim.turn(turned, 180f, BotDifficulty.EASY.turnSpeed(), false);
            ticks++;
        }
        assertEquals(10, ticks, "easy needs ten ticks for a full turn");
    }

    @Test
    void onlyFlickersSnapBigTurns() {
        assertEquals(170f, BotAim.turn(0f, 170f, 70f, true), 1.0E-4, "a flick");
        assertEquals(70f, BotAim.turn(0f, 90f, 70f, true), 1.0E-4, "small turns stay smooth");
        assertTrue(BotDifficulty.HELL.flicks());
        assertTrue(!BotDifficulty.HARD.flicks());
        assertTrue(BotDifficulty.EASY.turnSpeed() < BotDifficulty.NORMAL.turnSpeed()
                && BotDifficulty.NORMAL.turnSpeed() < BotDifficulty.HARD.turnSpeed()
                && BotDifficulty.HARD.turnSpeed() < BotDifficulty.HELL.turnSpeed());
    }

    @Test
    void pitchIsClampedAndLimited() {
        assertEquals(20f, BotAim.turnPitch(0f, 80f, 20f), 1.0E-4);
        assertEquals(90f, BotAim.turnPitch(85f, 120f, 20f), 1.0E-4);
    }

    @Test
    void theBodyFollowsTheHeadWithinFiftyDegrees() {
        assertEquals(40f, BotAim.body(0f, 90f, 90f, 10f), 1.0E-4, "dragged along by the head");
        assertEquals(5f, BotAim.body(0f, 30f, 30f, 5f), 1.0E-4, "a small offset turns slowly");
        assertEquals(-150f, BotAim.body(-150f, 170f, -150f, 30f), 1.0E-4, "within the lag across ±180");
        assertEquals(BotAim.BODY_LAG, Math.abs(BotAim.delta(BotAim.body(0f, 180f, 0f, 5f), 180f)), 1.0E-3);
    }

    @Test
    void theBorderMathFollowsVanilla() {
        assertEquals(0, BotManager.outside(5, 5, 0, 0, 10), 1.0E-9);
        assertEquals(3, BotManager.outside(13, -2, 0, 0, 10), 1.0E-9, "a square border");
        assertEquals(1, BotManager.borderDamage(0.3, 1), 1.0E-9, "at least 1");
        assertEquals(7, BotManager.borderDamage(3.6, 2), 1.0E-9, "per block out");
        assertEquals(0, BotManager.borderDamage(-1, 2), 1.0E-9, "inside the buffer");
    }

    @Test
    void bowLaunchPitchesUpForDistance() {
        double[] launch = Ballistics.launch(new double[] {0, 64, 0}, new double[] {0, 64, 40}, new double[] {0, 0, 0},
                1.0);
        assertTrue(BotAim.pitch(launch[0], launch[1], launch[2]) < 0, "aims above a far target (the arc)");
        assertEquals(0f, BotAim.delta(0f, BotAim.yaw(launch[0], launch[2], 0f)), 1.0E-3, "straight at it");
    }
}
