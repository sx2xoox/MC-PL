package io.github.sx2xoox.mcpl.game.superpower.scenario;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.sx2xoox.mcpl.game.superpower.ability.Ability;
import io.github.sx2xoox.mcpl.game.superpower.ability.ActivationResult;
import io.github.sx2xoox.mcpl.game.superpower.support.Scenario;
import io.github.sx2xoox.mcpl.game.superpower.support.world.FlatWorld;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.junit.jupiter.api.Test;

/** 근접 · 탱커 계열: 광전사, 거인, 수호자, 흡혈귀. */
class MeleeAbilitiesTest {

    private static final double EPSILON = 1.0E-6;

    private static final NamespacedKey GOLDEN_APPLE = new NamespacedKey("test", "golden_apple");

    private static long displays(Scenario s) {
        return s.world.getEntities().stream().filter(entity -> entity instanceof ItemDisplay).count();
    }

    private static long blockDisplays(Scenario s) {
        return s.world.getEntities().stream().filter(entity -> entity instanceof BlockDisplay).count();
    }

    /** Absorption hearts like a golden apple's (the max absorption is raised so they are not clamped away). */
    private static void giveAbsorption(Player player, double amount) {
        player.getAttribute(Attribute.MAX_ABSORPTION).addModifier(new AttributeModifier(GOLDEN_APPLE, amount,
                AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.ANY));
        player.setAbsorptionAmount(amount);
    }

    private static void takeAbsorption(Player player) {
        player.getAttribute(Attribute.MAX_ABSORPTION).removeModifier(GOLDEN_APPLE);
        player.setAbsorptionAmount(0);
    }

    /** Exactly what {@code GamblerAbility#fireBorrowed} does. */
    private static ActivationResult borrow(Scenario s, String id) {
        Ability borrowed = s.ctx().registry().create(id);
        borrowed.bind(s.owner, s.ctx(), s.ctx().paramsFor(id));
        return borrowed.onUltimate();
    }

    // ------------------------------------------------------------------ 광전사 berserker

    @Test
    void berserkerRageGrowsWithMissingHealth() {
        try (Scenario s = Scenario.ffa("berserker")) {
            s.melee(s.owner, s.enemy, 4);
            assertEquals(4, s.damageBy(s.owner, s.enemy), EPSILON, "no rage at full health");

            s.clearHits();
            double max = s.maxHealth(s.owner);
            double floor = s.param("rage_health");
            s.owner.setHealth(max - (max - floor) / 2);
            s.melee(s.owner, s.enemy, 4);
            assertEquals(4 * (1 + s.param("rage_max") / 100 / 2), s.damageBy(s.owner, s.enemy), EPSILON,
                    "half rage");

            s.clearHits();
            s.owner.setHealth(floor);
            s.melee(s.owner, s.enemy, 4);
            assertEquals(4 * (1 + s.param("rage_max") / 100), s.damageBy(s.owner, s.enemy), EPSILON, "full rage");
            s.finishClean();
        }
    }

    @Test
    void berserkerChargeBowlsEveryEnemyInThePathOnce() {
        try (Scenario s = Scenario.ffa("berserker")) {
            s.place(s.enemy2, 0.5, -6.5, 0);
            s.assertSucceeded(s.active(), "돌진");
            s.ticks(30);
            assertEquals(1, s.hitsBy(s.owner, s.enemy).size(), "struck once");
            assertEquals(s.param("charge_damage"), s.damageBy(s.owner, s.enemy), EPSILON);
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON, "nobody behind the charge is hit");
            assertTrue(s.owner.getLocation().getZ() > 4, "the berserker charged forward");
            assertTrue(Math.abs(s.enemy.getLocation().getX() - 0.5) > 0.3, "the enemy is bowled aside");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            assertFalse(s.active().succeeded(), "on cooldown");
            s.finishClean();
        }
    }

    @Test
    void berserkerChargeStopsAtAWall() {
        try (Scenario s = Scenario.ffa("berserker")) {
            s.place(s.enemy, 8.5, 0.5, 0);
            s.place(s.enemy2, -8.5, 0.5, 0);
            s.world.wallAlongX(-3, 3, 3, 3, Material.STONE);
            s.assertSucceeded(s.active(), "돌진");
            s.ticks(30);
            assertTrue(s.owner.getLocation().getZ() < 2.8, "stopped in front of the wall");
            assertFalse(s.world.hasCollisionsIn(s.owner.getBoundingBox().expand(-0.01)));
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "bumping a wall does not hurt");
            s.world.wallAlongX(-3, 3, 3, 3, Material.AIR);
            s.finishClean();
        }
    }

    @Test
    void berserkerFrenzyGrantsStrengthSpeedAndLifesteal() {
        try (Scenario s = Scenario.ffa("berserker")) {
            s.assertSucceeded(s.ultimate(), "광폭화");
            PotionEffect strength = s.owner.getPotionEffect(PotionEffectType.STRENGTH);
            PotionEffect speed = s.owner.getPotionEffect(PotionEffectType.SPEED);
            assertNotNull(strength);
            assertNotNull(speed);
            assertEquals(1, strength.getAmplifier(), "strength II");
            assertEquals(1, speed.getAmplifier(), "speed II");

            s.owner.setHealth(10);
            s.melee(s.owner, s.enemy, 6);
            double dealt = s.damageBy(s.owner, s.enemy);
            s.ticks(2);
            assertEquals(10 + dealt * s.param("ult_lifesteal") / 100, s.owner.getHealth(), 1.0E-6,
                    "heals " + s.param("ult_lifesteal") + "% of the melee damage");
            s.ticks(s.paramTicks("ult_duration"));
            double before = s.owner.getHealth();
            s.melee(s.owner, s.enemy, 6);
            s.ticks(2);
            assertEquals(before, s.owner.getHealth(), EPSILON, "no lifesteal after the frenzy");
            s.finishClean();
        }
    }

    @Test
    void berserkerFrenzyLifestealCountsDamageSoakedByAbsorptionHearts() {
        try (Scenario s = Scenario.ffa("berserker")) {
            s.assertSucceeded(s.ultimate(), "광폭화");
            s.owner.setHealth(10);
            giveAbsorption(s.enemy, 4);
            s.melee(s.owner, s.enemy, 6);
            double dealt = s.damageBy(s.owner, s.enemy) + 4; // the recorded final damage leaves out the absorbed 4
            assertEquals(0, s.enemy.getAbsorptionAmount(), EPSILON, "the hit broke through the absorption");
            s.ticks(2);
            assertEquals(10 + dealt * s.param("ult_lifesteal") / 100, s.owner.getHealth(), 1.0E-6,
                    "the absorbed part heals too");
            takeAbsorption(s.enemy);
            s.finishClean();
        }
    }

    // ------------------------------------------------------------------ 거인 titan

    @Test
    void titanIsBigToughAndSlow() {
        try (Scenario s = Scenario.ffa("titan")) {
            assertEquals(s.param("scale"), s.owner.getAttribute(Attribute.SCALE).getValue(), EPSILON);
            assertEquals(20 + s.param("bonus_health"), s.maxHealth(s.owner), EPSILON);
            assertEquals(20 + s.param("bonus_health"), s.owner.getHealth(), EPSILON, "starts at full health");
            assertEquals(s.param("knockback_resistance") / 100,
                    s.owner.getAttribute(Attribute.KNOCKBACK_RESISTANCE).getValue(), EPSILON);
            assertEquals(0.1 * (1 - s.param("speed_penalty") / 100),
                    s.owner.getAttribute(Attribute.MOVEMENT_SPEED).getValue(), EPSILON);
            assertEquals(1.8 * s.param("scale"), s.owner.getBoundingBox().getHeight(), 1.0E-6, "a taller hitbox");
            s.owner.damage(3, DamageSource.builder(DamageType.IN_WALL).build());
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "never suffocates");
            s.finishClean();
        }
    }

    @Test
    void titanLeapSlamsEnemiesAroundTheLandingWithoutHurtingItself() {
        try (Scenario s = Scenario.ffa("titan")) {
            s.place(s.enemy2, 0.5, -9.5, 0);
            s.assertSucceeded(s.active(), "대지 강타");
            s.ticks(3);
            assertTrue(s.owner.getLocation().getY() > FlatWorld.SURFACE + 0.5, "leaps");
            assertFalse(s.active().succeeded(), "cannot leap twice");
            s.tickUntilGrounded(s.owner, 120);
            s.ticks(2);
            assertEquals(s.param("slam_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "slammed once");
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON, "enemy2 is far away");
            assertTrue(s.enemy.getLocation().getY() > FlatWorld.SURFACE + 0.2 || s.enemy.getVelocity().getY() > 0,
                    "launched");
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "no fall damage for the titan");
            assertTrue(blockDisplays(s) > 0, "rocks erupt from the ground");
            s.ticks(40);
            assertEquals(0, blockDisplays(s), "the rocks sank back and are gone");
            assertTrue(s.hitsOf(s.enemy, DamageCause.FALL).isEmpty() || s.damageTaken(s.enemy)
                    <= s.param("slam_damage") + 1, "a short hop at most");
            s.finishClean();
        }
    }

    @Test
    void titanGiantFormGrowsGrantsBuffsAndShrinksBack() {
        try (Scenario s = Scenario.ffa("titan")) {
            double reach = s.owner.getAttribute(Attribute.ENTITY_INTERACTION_RANGE).getValue();
            s.assertSucceeded(s.ultimate(), "거대화");
            s.ticks(12);
            assertEquals(s.param("ult_scale"), s.owner.getAttribute(Attribute.SCALE).getValue(), 1.0E-6, "grown");
            assertEquals(reach + s.param("ult_reach"),
                    s.owner.getAttribute(Attribute.ENTITY_INTERACTION_RANGE).getValue(), EPSILON);
            // The server clamps knockback resistance to 1 (MockBukkit does not clamp attribute values).
            assertTrue(s.owner.getAttribute(Attribute.KNOCKBACK_RESISTANCE).getValue() >= 1.0, "knockback immune");
            assertEquals(1 + s.param("ult_damage_bonus") / 100,
                    s.owner.getAttribute(Attribute.ATTACK_DAMAGE).getValue(), EPSILON);
            assertFalse(s.ultimate().succeeded(), "already a giant");

            s.ticks(s.paramTicks("ult_duration") + 20);
            assertEquals(s.param("scale"), s.owner.getAttribute(Attribute.SCALE).getValue(), EPSILON,
                    "back to the passive size");
            assertEquals(reach, s.owner.getAttribute(Attribute.ENTITY_INTERACTION_RANGE).getValue(), EPSILON);
            assertEquals(s.param("knockback_resistance") / 100,
                    s.owner.getAttribute(Attribute.KNOCKBACK_RESISTANCE).getValue(), EPSILON);
            assertEquals(1, s.owner.getAttribute(Attribute.ATTACK_DAMAGE).getValue(), EPSILON);
            s.assertSucceeded(s.ultimate(), "거대화 again");
            s.finishClean();
        }
    }

    @Test
    void titanGrowsOnlyAsFarAsTheCeilingAllows() {
        try (Scenario s = Scenario.ffa("titan")) {
            ceiling(s, FlatWorld.FLOOR_Y + 3, Material.STONE);
            assertFalse(s.ultimate().succeeded(), "a 2 block high room: no headroom for a giant");
            ceiling(s, FlatWorld.FLOOR_Y + 3, Material.AIR);

            ceiling(s, FlatWorld.FLOOR_Y + 4, Material.STONE);
            s.assertSucceeded(s.ultimate(), "거대화 under a 3 block ceiling");
            s.ticks(12);
            double scale = s.owner.getAttribute(Attribute.SCALE).getValue();
            assertTrue(scale > s.param("scale") && 1.8 * scale <= 3.0 + 1.0E-6,
                    "grew, but never into the ceiling: " + scale);
            assertFalse(s.world.hasCollisionsIn(s.owner.getBoundingBox().expand(-0.01)));
            s.ticks(s.paramTicks("ult_duration") + 20);
            ceiling(s, FlatWorld.FLOOR_Y + 4, Material.AIR);
            s.finishClean();
        }
    }

    @Test
    void titanGrowsEvenWhenHuggingAWall() {
        try (Scenario s = Scenario.ffa("titan")) {
            // The titan's own passive cancels suffocation, so it may grow with its back against a wall.
            s.place(s.owner, 0.5, 1 - 0.3 * s.param("scale"), 0);
            s.world.wallAlongX(-2, 2, 1, 4, Material.STONE);
            s.assertSucceeded(s.ultimate(), "거대화 against a wall");
            s.ticks(12);
            assertEquals(s.param("ult_scale"), s.owner.getAttribute(Attribute.SCALE).getValue(), 1.0E-6, "grown");
            s.ticks(s.paramTicks("ult_duration") + 20);
            s.world.wallAlongX(-2, 2, 1, 4, Material.AIR);
            s.finishClean();
        }
    }

    @Test
    void titanGiantFormBorrowedByAGamblerNeedsRoomForTheWholeBody() {
        try (Scenario s = Scenario.builder("gambler").param("dodge_chance", 0).param("crit_chance", 0).start()) {
            // A borrowed copy has no suffocation guard: growing into the wall next to the gambler must fail, so the
            // jackpot moves on to another ultimate.
            s.place(s.owner, 0.5, 0.7, 0);
            s.world.wallAlongX(-2, 2, 1, 4, Material.STONE);
            assertFalse(borrow(s, "titan").succeeded(), "no room for a giant beside the wall");
            assertEquals(1.0, s.owner.getAttribute(Attribute.SCALE).getValue(), EPSILON, "did not grow");
            assertTrue(s.owner.getAttribute(Attribute.SCALE).getModifiers().isEmpty());

            s.place(s.owner, 0.5, -3.5, 0);
            s.assertSucceeded(borrow(s, "titan"), "거대화 in the open");
            s.ticks(12);
            assertEquals(s.ctx().paramsFor("titan").num("ult_scale"), s.owner.getAttribute(Attribute.SCALE).getValue(),
                    1.0E-6, "grown in the open");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.ticks((int) s.ctx().paramsFor("titan").ticks("ult_duration") + 20);
            assertEquals(1.0, s.owner.getAttribute(Attribute.SCALE).getValue(), EPSILON, "shrunk back");
            s.world.wallAlongX(-2, 2, 1, 4, Material.AIR);
            s.finishClean();
        }
    }

    private static void ceiling(Scenario s, int y, Material type) {
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                s.world.setBlock(x, y, z, type);
            }
        }
    }

    // ------------------------------------------------------------------ 수호자 guardian

    @Test
    void guardianTakesLessDamageFromEverything() {
        try (Scenario s = Scenario.ffa("guardian")) {
            s.melee(s.enemy, s.owner, 10);
            assertEquals(10 * (1 - s.param("damage_reduction") / 100), s.damageTaken(s.owner), EPSILON);
            s.finishClean();
        }
    }

    @Test
    void guardianShieldBashHitsSlowsAndKnocksBack() {
        try (Scenario s = Scenario.ffa("guardian")) {
            s.place(s.enemy, 0.5, 3.5, 180);
            double enemyZ = s.enemy.getLocation().getZ();
            s.assertSucceeded(s.active(), "방패 돌격");
            s.ticks(10);
            assertEquals(s.param("bash_damage"), s.damageBy(s.owner, s.enemy), EPSILON);
            PotionEffect slow = s.enemy.getPotionEffect(PotionEffectType.SLOWNESS);
            assertNotNull(slow, "slowed");
            assertEquals(1, slow.getAmplifier());
            s.ticks(10);
            assertTrue(s.enemy.getLocation().getZ() > enemyZ + 1, "knocked back");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.ticks(20);
            assertEquals(0, displays(s), "the charge shield faded");
            s.finishClean();
        }
    }

    @Test
    void guardianBulwarkShieldsTheOwnerAndAlliesThenEnds() {
        try (Scenario s = Scenario.teams("guardian")) {
            s.place(s.ally(), 2.5, 0.5, 0);
            s.assertSucceeded(s.ultimate(), "불굴");
            assertEquals(s.param("ult_absorption"), s.owner.getAbsorptionAmount(), EPSILON);
            PotionEffect resistance = s.owner.getPotionEffect(PotionEffectType.RESISTANCE);
            assertNotNull(resistance);
            assertEquals(1, resistance.getAmplifier(), "resistance II");
            PotionEffect allyResistance = s.ally().getPotionEffect(PotionEffectType.RESISTANCE);
            assertNotNull(allyResistance, "the nearby ally is shielded");
            assertEquals(0, allyResistance.getAmplifier(), "resistance I");
            assertFalse(s.enemy.hasPotionEffect(PotionEffectType.RESISTANCE), "enemies are not");
            assertEquals(3, displays(s), "three orbiting shields");
            assertTrue(blockDisplays(s) >= 10, "a glass dome");
            assertFalse(s.ultimate().succeeded(), "already active");

            double health = s.owner.getHealth();
            s.melee(s.enemy, s.owner, 10);
            assertEquals(health, s.owner.getHealth(), EPSILON, "the absorption takes the hit");
            assertTrue(s.owner.getAbsorptionAmount() < s.param("ult_absorption"));
            s.ticks(10);
            assertTrue(displays(s) < 3, "spent shields break");

            s.ticks(s.paramTicks("ult_duration") + 10);
            assertEquals(0, s.owner.getAbsorptionAmount(), EPSILON, "the bonus absorption is gone");
            assertTrue(s.owner.getAttribute(Attribute.MAX_ABSORPTION).getModifiers().isEmpty());
            assertEquals(0, displays(s), "the shields are gone");
            assertEquals(0, blockDisplays(s), "the dome is gone");
            s.finishClean();
        }
    }

    // ------------------------------------------------------------------ 흡혈귀 vampire

    @Test
    void vampireHealsAShareOfEveryHit() {
        try (Scenario s = Scenario.ffa("vampire")) {
            s.owner.setHealth(10);
            s.melee(s.owner, s.enemy, 5);
            s.ticks(2);
            assertEquals(10 + 5 * s.param("lifesteal") / 100, s.owner.getHealth(), EPSILON);
            s.finishClean();
        }
    }

    @Test
    void vampireDrainSiphonsTheAimedEnemyWhileInSight() {
        try (Scenario s = Scenario.ffa("vampire")) {
            s.owner.setHealth(8);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "생명 흡수");
            s.ticks(s.paramTicks("drain_duration") + 5);
            double drained = s.param("drain_damage") * s.param("drain_duration") / s.param("drain_interval");
            assertEquals(drained, s.damageBy(s.owner, s.enemy), EPSILON, "1 every 0.25 s for 2 s");
            assertEquals(8 + drained, s.owner.getHealth(), 1.0E-6, "every drop drained heals the vampire");
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON);
            s.finishClean();
        }
    }

    @Test
    void vampireDrainSnapsWhenALineOfSightIsBroken() {
        try (Scenario s = Scenario.ffa("vampire")) {
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "생명 흡수");
            s.ticks(11);
            double before = s.damageBy(s.owner, s.enemy);
            assertTrue(before >= 2, "draining");
            s.world.wallAlongX(-3, 3, 2, 3, Material.STONE);
            s.ticks(40);
            assertTrue(s.damageBy(s.owner, s.enemy) <= before + s.param("drain_damage"),
                    "the wall broke the drain");
            s.world.wallAlongX(-3, 3, 2, 3, Material.AIR);
            s.finishClean();
        }
    }

    @Test
    void vampireBloodFeastStealsEverything() {
        try (Scenario s = Scenario.ffa("vampire")) {
            s.assertSucceeded(s.ultimate(), "피의 축제");
            assertTrue(s.owner.hasPotionEffect(PotionEffectType.SPEED));
            assertTrue(blockDisplays(s) > 0, "bats circle the vampire");
            s.owner.setHealth(6);
            s.melee(s.owner, s.enemy, 6);
            s.ticks(2);
            assertEquals(6 + 6 * s.param("ult_lifesteal") / 100, s.owner.getHealth(), EPSILON);
            s.ticks(s.paramTicks("ult_duration"));
            s.owner.setHealth(6);
            s.melee(s.owner, s.enemy, 5);
            s.ticks(2);
            assertEquals(6 + 5 * s.param("lifesteal") / 100, s.owner.getHealth(), EPSILON, "back to the passive");
            s.ticks(20);
            assertEquals(0, blockDisplays(s), "the bats flew off");
            s.finishClean();
        }
    }

    @Test
    void vampireLifestealCountsDamageSoakedByAbsorptionHearts() {
        try (Scenario s = Scenario.ffa("vampire")) {
            s.owner.setHealth(10);
            giveAbsorption(s.enemy, 4);
            s.melee(s.owner, s.enemy, 5);
            assertEquals(0, s.enemy.getAbsorptionAmount(), EPSILON, "the hit broke through the absorption");
            s.ticks(2);
            assertEquals(10 + 5 * s.param("lifesteal") / 100, s.owner.getHealth(), EPSILON,
                    "all 5 damage heals, also the 4 the absorption hearts soaked up");
            takeAbsorption(s.enemy);
            s.finishClean();
        }
    }
}
