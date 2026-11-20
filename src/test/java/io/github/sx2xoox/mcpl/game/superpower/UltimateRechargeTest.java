package io.github.sx2xoox.mcpl.game.superpower;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.sx2xoox.mcpl.game.superpower.support.Scenario;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.IronGolem;
import org.junit.jupiter.api.Test;

/**
 * Real ultimates cannot refill their own gauge (뇌신 chaining 천벌). The post-ultimate lock is turned off here, so
 * only the rule that an ultimate's own damage never charges its caster keeps the gauge empty.
 */
class UltimateRechargeTest {

    private static final double EPSILON = 1.0E-6;

    @Test
    void thunderJudgementCannotRechargeItself() {
        try (Scenario s = Scenario.builder("thunder")
                .configure(harness -> harness.settings().set(SuperpowerSettings.ULTIMATE_LOCK, 0)).start()) {
            s.assertSucceeded(s.ultimate(), "천벌");
            s.ticks(100);
            assertTrue(s.damageBy(s.owner, s.enemy) > 0, "천벌 hit the enemies");
            assertTrue(s.ctx().gauge(s.owner) < 5.0, "only time charged the gauge: " + s.ctx().gauge(s.owner));
            assertTrue(s.ctx().gauge(s.enemy) > 0, "the victims charge from the damage they took");
            s.finishClean();
        }
    }

    @Test
    void summonerGolemCannotRechargeItsOwnUltimate() {
        try (Scenario s = Scenario.builder("summoner")
                .configure(harness -> harness.settings().set(SuperpowerSettings.ULTIMATE_LOCK, 0)).start()) {
            s.assertSucceeded(s.ultimate(), "강철 골렘");
            s.ticks(10);
            IronGolem golem = s.world.getEntities().stream().filter(IronGolem.class::isInstance)
                    .map(IronGolem.class::cast).findFirst().orElseThrow();
            double before = s.ctx().gauge(s.owner);
            double health = s.health(s.enemy);
            double attack = golem.getAttribute(Attribute.ATTACK_DAMAGE).getValue();
            s.melee(golem, s.enemy, attack);
            assertEquals(health - attack, s.health(s.enemy), EPSILON, "the golem hits with its attack damage");
            assertEquals(before, s.ctx().gauge(s.owner), EPSILON, "golem hits do not charge the summoner");
            s.finishClean();
        }
    }
}
