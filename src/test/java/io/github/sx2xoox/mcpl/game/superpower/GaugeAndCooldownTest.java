package io.github.sx2xoox.mcpl.game.superpower;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.sx2xoox.mcpl.game.superpower.runtime.Cooldown;
import io.github.sx2xoox.mcpl.game.superpower.runtime.UltimateGauge;
import org.junit.jupiter.api.Test;

class GaugeAndCooldownTest {

    @Test
    void gaugeIsClampedAndReportsFillingOnce() {
        UltimateGauge gauge = new UltimateGauge();
        assertFalse(gauge.add(40));
        assertEquals(40, gauge.level());
        assertEquals(0.4f, gauge.progress(), 1.0E-6);
        assertTrue(gauge.add(75), "crossing 100 fills the gauge");
        assertEquals(100.0, gauge.value());
        assertTrue(gauge.isFull());
        assertEquals(100, gauge.level());
        assertEquals(1.0f, gauge.progress(), 1.0E-6);
        assertFalse(gauge.add(10), "already full: no second notification");
        assertEquals(100.0, gauge.value());

        assertFalse(gauge.add(-250));
        assertEquals(0.0, gauge.value(), "never below zero");
        assertFalse(gauge.add(Double.NaN));
        assertFalse(gauge.add(Double.POSITIVE_INFINITY));
        assertEquals(0.0, gauge.value(), "non-finite amounts are ignored");

        gauge.set(99.99);
        assertEquals(99, gauge.level(), "the level is the whole percentage");
        assertFalse(gauge.isFull());
        gauge.reset();
        assertEquals(0.0, gauge.value());
    }

    @Test
    void cooldownUsesTheMultiplierAndCanBeReduced() {
        Cooldown cooldown = new Cooldown();
        assertTrue(cooldown.isReady(0));
        long ticks = cooldown.start(100, 8, 0.8);
        assertEquals(128, ticks, "8 s * 0.8 = 6.4 s");
        assertEquals(6.4, cooldown.remainingSeconds(100), 1.0E-9);
        assertEquals(3.4, cooldown.remainingSeconds(160), 1.0E-9);

        assertEquals(48, cooldown.reduce(160, 1.0), "reduceCooldown shortens the remaining time");
        assertEquals(0, cooldown.reduce(160, 100), "never below ready");
        assertTrue(cooldown.isReady(160));
        assertEquals(0, cooldown.reduce(160, -5));
    }

    @Test
    void invalidMultipliersCountAsOne() {
        assertEquals(200, Cooldown.ticks(10, Double.NaN));
        assertEquals(200, Cooldown.ticks(10, -2));
        assertEquals(0, Cooldown.ticks(10, 0), "a zero multiplier means no cooldown");
        assertEquals(0, Cooldown.ticks(-3, 1));
    }
}
