package io.github.sx2xoox.mcpl.game.siege.lanes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import io.github.sx2xoox.mcpl.game.siege.arena.LaneModifiers;
import io.github.sx2xoox.mcpl.game.siege.balance.SiegeModifiers;
import org.junit.jupiter.api.Test;

/** The lane modifiers and their bridge to the match's balance modifiers. */
class LaneModifiersTest {

    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();

    @Test
    void sourcesAddUpAndApplyThemselvesWithoutBalance() {
        LaneModifiers modifiers = new LaneModifiers();
        assertFalse(modifiers.bridged());
        modifiers.set(a, "shop:orb", LaneModifiers.Stat.SKILL_DAMAGE, 0.10);
        modifiers.set(a, "buff:teddy", LaneModifiers.Stat.SKILL_DAMAGE, 0.10);
        modifiers.set(b, "shop:armor", LaneModifiers.Stat.SKILL_DAMAGE_TAKEN, -0.18);
        assertEquals(1.2 * 0.82, modifiers.skillDamageFactor(a, b), 1.0E-9);
        assertEquals(1.0, modifiers.skillDamageFactor(b, a), 1.0E-9);
        modifiers.set(a, "shop:hourglass", LaneModifiers.Stat.COOLDOWN, 0.4);
        modifiers.set(a, "buff:rabbit", LaneModifiers.Stat.COOLDOWN, 0.2);
        assertEquals(LaneModifiers.MAX_COOLDOWN, modifiers.cooldownReduction(a), 1.0E-9, "capped");
        modifiers.removePrefix(a, "shop:");
        assertEquals(0.10, modifiers.total(a, LaneModifiers.Stat.SKILL_DAMAGE), 1.0E-9);
        modifiers.clear();
        assertEquals(0, modifiers.total(b, LaneModifiers.Stat.SKILL_DAMAGE_TAKEN), 1.0E-9);
    }

    @Test
    void abilityStatsGoToTheBalanceModifiers() {
        SiegeModifiers balance = new SiegeModifiers();
        LaneModifiers modifiers = new LaneModifiers(balance);
        assertTrue(modifiers.bridged());
        modifiers.set(a, "shop:orb", LaneModifiers.Stat.SKILL_DAMAGE, 0.10);
        modifiers.set(a, "shop:armor", LaneModifiers.Stat.SKILL_DAMAGE_TAKEN, -0.12);
        modifiers.set(a, "shop:hourglass", LaneModifiers.Stat.COOLDOWN, 0.10);
        modifiers.set(a, "shop:hammer", LaneModifiers.Stat.STRUCTURE_DAMAGE, 0.20);
        assertEquals(1.10, balance.factor(a, SiegeModifiers.Stat.SKILL_DAMAGE), 1.0E-9);
        assertEquals(0.88, balance.factor(a, SiegeModifiers.Stat.SKILL_DAMAGE_TAKEN), 1.0E-9);
        assertEquals(0.90, balance.factor(a, SiegeModifiers.Stat.COOLDOWN), 1.0E-9, "10 % shorter");
        assertEquals(3, balance.of(a).size(), "structure damage stays with the lane systems");
        assertEquals(1.0, balance.factor(b, SiegeModifiers.Stat.SKILL_DAMAGE), 1.0E-9, "only the buyer");

        modifiers.set(a, "shop:orb", LaneModifiers.Stat.SKILL_DAMAGE, 0);
        assertEquals(1.0, balance.factor(a, SiegeModifiers.Stat.SKILL_DAMAGE), 1.0E-9);
        modifiers.removePrefix(a, "shop:");
        assertTrue(balance.of(a).isEmpty());
        modifiers.set(b, "buff:dragon", LaneModifiers.Stat.SKILL_DAMAGE, 0.10);
        modifiers.clear();
        assertTrue(balance.of(b).isEmpty(), "a round reset clears the mirrored ones too");
    }
}
