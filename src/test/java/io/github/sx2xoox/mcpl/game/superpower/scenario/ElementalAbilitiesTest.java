package io.github.sx2xoox.mcpl.game.superpower.scenario;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import io.github.sx2xoox.mcpl.game.superpower.support.Scenario;
import io.github.sx2xoox.mcpl.game.superpower.support.TestPlayer;
import io.github.sx2xoox.mcpl.game.superpower.support.world.FlatWorld;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/** 원소 계열: 화염술사, 뇌신, 빙결술사, 바람술사. */
class ElementalAbilitiesTest {

    private static final double EPSILON = 1.0E-6;

    // ------------------------------------------------------------------ 화염술사 pyro

    @Test
    void pyroFireballVolleyHitsTheAimedEnemyThreeTimesAndBurnsIt() {
        try (Scenario s = Scenario.ffa("pyro")) {
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "화염구");
            s.ticks(20);
            List<Scenario.Hit> hits = s.hitsBy(s.owner, s.enemy);
            assertEquals(s.param("shots"), hits.stream().filter(hit -> hit.cause() == DamageCause.MAGIC).count(),
                    "every fireball bursts on the aimed enemy: " + hits);
            assertEquals(s.param("shots") * s.param("fireball_damage"), s.damageBy(s.owner, s.enemy), EPSILON);
            assertTrue(s.enemy.getFireTicks() > 0, "the enemy burns");
            assertEquals(0, s.damageBy(s.owner, s.enemy2), EPSILON, "the burst radius does not reach enemy2");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.ticks(80);
            assertTrue(s.hitsOf(s.enemy, DamageCause.FIRE_TICK).size() >= 2, "the burn keeps hurting");
            s.finishClean();
        }
    }

    @Test
    void pyroFireballStopsAtWalls() {
        try (Scenario s = Scenario.ffa("pyro")) {
            s.world.wallAlongX(-2, 3, 2, 3, Material.STONE);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "화염구");
            s.ticks(30);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "the wall shields the enemy");
            s.world.wallAlongX(-2, 3, 2, 3, Material.AIR);
            s.finishClean();
        }
    }

    @Test
    void pyroIsImmuneToFireAndIgnitesMeleeVictims() {
        try (Scenario s = Scenario.ffa("pyro")) {
            s.owner.setFireTicks(100);
            s.ticks(60);
            s.owner.damage(4, DamageSource.builder(DamageType.LAVA).build());
            s.owner.damage(4, DamageSource.builder(DamageType.IN_FIRE).build());
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "fire, burning and lava never hurt the pyro");
            assertEquals(0, s.owner.getFireTicks(), "the burning overlay is cleared");

            s.melee(s.owner, s.enemy, 2);
            assertTrue(s.enemy.getFireTicks() >= s.paramTicks("melee_burn") - 1, "melee victims burn");
            s.finishClean();
        }
    }

    @Test
    void pyroVortexFollowsTheOwnerAndBurnsEnemiesInside() {
        try (Scenario s = Scenario.ffa("pyro")) {
            s.place(s.enemy2, 0.5, -12.5, 0);
            s.assertSucceeded(s.ultimate(), "불바다");
            s.ticks(40);
            assertTrue(s.hitsBy(s.owner, s.enemy).size() >= 3, "the enemy 4 blocks away is scorched");
            assertEquals(0, s.damageBy(s.owner, s.enemy2), EPSILON, "enemy2 is out of the vortex");
            // The vortex follows the owner to enemy2.
            s.place(s.owner, 0.5, -9.5, 0);
            s.ticks(30);
            assertTrue(s.damageBy(s.owner, s.enemy2) > 0, "the vortex moved with the owner");
            s.ticks(80);
            s.clearHits();
            s.place(s.enemy, 0.5, -8.5, 180);
            s.ticks(40);
            assertTrue(s.hitsBy(s.owner, s.enemy).stream().noneMatch(hit -> hit.cause() == DamageCause.MAGIC),
                    "the vortex died down after " + s.param("ult_duration") + " s");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.finishClean();
        }
    }

    // ------------------------------------------------------------------ 뇌신 thunder

    @Test
    void thunderBoltStrikesTheAimedEnemyAndLaunchesIt() {
        try (Scenario s = Scenario.ffa("thunder")) {
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "낙뢰");
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "the bolt falls after a short warning");
            s.ticks(s.paramTicks("strike_delay") + 1);
            assertEquals(1, s.world.lightningEffects().size(), "one harmless lightning effect");
            assertEquals(s.param("damage"), s.damageBy(s.owner, s.enemy), EPSILON);
            s.ticks(3);
            assertTrue(s.enemy.getLocation().getY() > FlatWorld.SURFACE + 0.3, "the enemy is launched");
            s.ticks(40);
            assertEquals(FlatWorld.SURFACE, s.enemy.getLocation().getY(), 1.0E-3, "and lands again");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.finishClean();
        }
    }

    @Test
    void thunderBoltCanHitTheGroundButNotTheSky() {
        try (Scenario s = Scenario.ffa("thunder")) {
            s.place(s.owner, 0.5, 0.5, 180);
            s.aimAtFloor(s.owner, 0.5, -6.5);
            s.assertSucceeded(s.active(), "낙뢰 on the ground");
            s.ticks(20);
            assertEquals(1, s.world.lightningEffects().size());
            assertEquals(0, s.damageTaken(s.enemy) + s.damageTaken(s.enemy2), EPSILON, "nobody near the bolt");

            s.readyActive();
            s.place(s.owner, 0.5, FlatWorld.SURFACE, 0.5, 180, -80);
            assertFalse(s.active().succeeded(), "aiming at the sky fails");
            assertEquals(0, s.ctx().cooldownRemaining(s.owner), EPSILON, "a failed skill costs no cooldown");
            s.finishClean();
        }
    }

    @Test
    void thunderJudgmentStrikesEveryEnemyThreeTimes() {
        try (Scenario s = Scenario.ffa("thunder")) {
            s.assertSucceeded(s.ultimate(), "천벌");
            s.ticks(80);
            int strikes = (int) s.param("ult_strikes");
            for (TestPlayer target : s.enemies()) {
                assertEquals(strikes, s.hitsBy(s.owner, target).size(), target.getName() + " struck each wave");
                assertEquals(strikes * s.param("ult_damage"), s.damageBy(s.owner, target), EPSILON);
            }
            assertEquals(strikes * 2, s.world.lightningEffects().size());
            s.finishClean();
        }
    }

    @Test
    void thunderJudgmentNeedsAnEnemyInRangeAndKeepsTheGauge() {
        try (Scenario s = Scenario.ffa("thunder")) {
            s.place(s.enemy, 0.5, 24.5, 180);
            s.place(s.enemy2, 0.5, -24.5, 0);
            assertFalse(s.ultimate().succeeded(), "no enemy within " + s.param("ult_radius"));
            assertEquals(100, s.participant(s.owner).gauge(), EPSILON, "the gauge is kept");
            s.finishClean();
        }
    }

    @Test
    void thunderShockSlowsAndZapsMeleeVictims() {
        try (Scenario s = Scenario.builder("thunder").param("shock_chance", 100).start()) {
            s.melee(s.owner, s.enemy, 2);
            PotionEffect slow = s.enemy.getPotionEffect(PotionEffectType.SLOWNESS);
            assertNotNull(slow, "the shock slows");
            assertEquals(1, slow.getAmplifier(), "slowness II");
            s.ticks(2);
            assertEquals(s.param("shock_damage"), s.damageBy(s.owner, s.enemy) - 2, EPSILON, "plus the zap");
            s.finishClean();
        }
    }

    // ------------------------------------------------------------------ 빙결술사 frost

    @Test
    void frostSpearFreezesTheEnemyItHits() {
        try (Scenario s = Scenario.ffa("frost")) {
            s.aimAt(s.owner, s.enemy);
            double speed = s.enemy.getAttribute(Attribute.MOVEMENT_SPEED).getValue();
            s.assertSucceeded(s.active(), "얼음창");
            s.ticks(4);
            assertEquals(s.param("spear_damage"), s.damageBy(s.owner, s.enemy), EPSILON);
            assertEquals(speed * (1 - s.param("freeze_slow") / 100), s.enemy.getAttribute(Attribute.MOVEMENT_SPEED)
                    .getValue(), 1.0E-6, "-80% speed");
            assertEquals(0, s.enemy.getAttribute(Attribute.JUMP_STRENGTH).getValue(), 1.0E-9, "no jumping");
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON, "the spear stops at the first enemy");
            s.ticks(s.paramTicks("freeze_duration") + 2);
            assertEquals(speed, s.enemy.getAttribute(Attribute.MOVEMENT_SPEED).getValue(), 1.0E-9, "thawed");
            assertTrue(s.enemy.getAttribute(Attribute.JUMP_STRENGTH).getModifiers().isEmpty());
            s.finishClean();
        }
    }

    @Test
    void frostAbsoluteZeroImprisonsEnemiesInTemporaryIceThenShatters() {
        try (Scenario s = Scenario.ffa("frost")) {
            Location enemyAt = s.enemy.getLocation();
            s.assertSucceeded(s.ultimate(), "절대영도");
            s.ticks(1);
            assertTrue(s.h.hub.tempBlocks().size() > 0, "ice prison placed as temporary blocks");
            Location feet = s.enemy.getLocation();
            assertEquals(Material.PACKED_ICE, s.world.getBlockAt(feet.getBlockX() + 1, feet.getBlockY(),
                    feet.getBlockZ() + 1).getType(), "corner pillar of the prison");
            assertEquals(Material.AIR, s.world.getBlockAt(feet.getBlockX(), feet.getBlockY(), feet.getBlockZ())
                    .getType(), "the enemy itself is not encased");
            assertEquals(0, s.enemy.getAttribute(Attribute.MOVEMENT_SPEED).getValue(), 1.0E-9, "frozen solid");
            assertTrue(feet.distance(enemyAt) < 0.8, "centered in its cell, not moved away");
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "no damage while frozen");

            s.ticks(s.paramTicks("ult_duration") + 2);
            for (TestPlayer target : s.enemies()) {
                assertEquals(s.param("ult_damage"), s.damageBy(s.owner, target), EPSILON, "the prison shatters");
                assertTrue(target.getAttribute(Attribute.MOVEMENT_SPEED).getModifiers().isEmpty());
            }
            assertEquals(0, s.h.hub.tempBlocks().size(), "the ice melted");
            s.finishClean();
        }
    }

    @Test
    void frostChillsMeleeAttackersAndIgnoresFreezing() {
        try (Scenario s = Scenario.ffa("frost")) {
            s.melee(s.enemy, s.owner, 2);
            PotionEffect chill = s.enemy.getPotionEffect(PotionEffectType.SLOWNESS);
            assertNotNull(chill, "the attacker is chilled");
            assertEquals(0, chill.getAmplifier());
            s.owner.setNoDamageTicks(0);
            s.owner.damage(3, DamageSource.builder(DamageType.FREEZE).build());
            assertEquals(2, s.damageTaken(s.owner), EPSILON, "freezing never hurts the frost mage");
            s.finishClean();
        }
    }

    @Test
    @Disabled("BUG: Frost passive chills enemies on any ENTITY_ATTACK damage, incl. Venom's ability plague ticks")
    void frostOnlyChillsRealMeleeAttackers() {
        // Venom's plague deals ability damage with DamageType.WITHER from the venom player; Paper reports a WITHER
        // source with a direct entity as an EntityDamageByEntityEvent with cause ENTITY_ATTACK. Frost's passive
        // ("나를 근접 공격한 적") must ignore it like every other melee passive does (ctx().isAbilityDamage()).
        try (Scenario s = Scenario.builder("venom").enemyAbility("frost").start()) {
            s.assertSucceeded(s.ultimate(), "역병");
            s.ticks(30);
            assertTrue(s.damageBy(s.owner, s.enemy) > 0, "the plague hurts the frost enemies");
            assertNull(s.owner.getPotionEffect(PotionEffectType.SLOWNESS),
                    "the venom never touched the frost mage in melee, so it must not be chilled");
            s.finishClean();
        }
    }

    // ------------------------------------------------------------------ 바람술사 aero

    @Test
    void aeroGustLeapHitsEnemiesAtTakeOffAndCarriesTheOwnerForward() {
        try (Scenario s = Scenario.ffa("aero")) {
            s.place(s.enemy, 0.5, 2.5, 180);
            s.place(s.enemy2, 0.5, 30.5, 180);
            s.place(s.owner, 0.5, FlatWorld.SURFACE, 0.5, 0, -15);
            s.assertSucceeded(s.active(), "돌풍 도약");
            assertEquals(s.param("damage"), s.damageBy(s.owner, s.enemy), EPSILON, "the take-off gust hits");
            s.ticks(2);
            assertTrue(s.enemy.getVelocity().length() > 0.3 || s.enemy.getLocation().getZ() > 3,
                    "and pushes the enemy away");
            s.tickUntilGrounded(s.owner, 100);
            double travelled = s.owner.getLocation().getZ() - 0.5;
            assertTrue(travelled > s.param("distance") * 0.5, "the owner dashed forward (" + travelled + ")");
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "no fall damage");
            s.ticks(10);
            s.finishClean();
        }
    }

    @Test
    void aeroNeverTakesFallDamage() {
        try (Scenario s = Scenario.ffa("aero")) {
            s.place(s.owner, 0.5, FlatWorld.SURFACE + 20, 0.5, 0, 0);
            s.tickUntilGrounded(s.owner, 200);
            s.ticks(2);
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.finishClean();
        }
    }

    @Test
    void aeroTornadoLiftsEnemiesThenSlamsThemDown() {
        try (Scenario s = Scenario.ffa("aero")) {
            s.assertSucceeded(s.ultimate(), "토네이도");
            s.ticks(30);
            for (TestPlayer target : s.enemies()) {
                assertTrue(target.getLocation().getY() > FlatWorld.SURFACE + 3, target.getName() + " is lifted: "
                        + target.getLocation().getY());
            }
            assertEquals(FlatWorld.SURFACE, s.owner.getLocation().getY(), 1.0E-3, "the owner stays on the ground");
            s.ticks(s.paramTicks("ult_duration") + 60);
            for (TestPlayer target : s.enemies()) {
                assertEquals(FlatWorld.SURFACE, target.getLocation().getY(), 1.0E-3, target.getName() + " landed");
                assertEquals(s.param("slam_damage"), s.damageBy(s.owner, target), EPSILON, "slammed once");
                assertTrue(s.hitsOf(target, DamageCause.FALL).isEmpty(), "the slam replaces fall damage");
            }
            s.finishClean();
        }
    }
}
