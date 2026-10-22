package io.github.sx2xoox.mcpl.game.superpower.scenario;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.sx2xoox.mcpl.game.superpower.ability.Ability;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityParams;
import io.github.sx2xoox.mcpl.game.superpower.ability.ActivationResult;
import io.github.sx2xoox.mcpl.game.superpower.support.Scenario;
import io.github.sx2xoox.mcpl.game.superpower.support.world.FlatWorld;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.junit.jupiter.api.Test;

/** 마크 몹 계열 (새 능력): 크리퍼, 셜커, 염소, 거북이. */
class MobNewAbilitiesTest {

    private static final double EPSILON = 1.0E-6;
    private static final double BASE_SPEED = 0.1;
    private static final double BASE_JUMP = 0.42;

    private static long displays(Scenario s) {
        return s.world.getEntities().stream().filter(entity -> entity instanceof Display).count();
    }

    private static long blockDisplays(Scenario s) {
        return s.world.getEntities().stream().filter(entity -> entity instanceof BlockDisplay).count();
    }

    private static long itemDisplays(Scenario s) {
        return s.world.getEntities().stream().filter(entity -> entity instanceof ItemDisplay).count();
    }

    private static double speed(Player player) {
        return player.getAttribute(Attribute.MOVEMENT_SPEED).getValue();
    }

    private static double jump(Player player) {
        return player.getAttribute(Attribute.JUMP_STRENGTH).getValue();
    }

    private static double scale(Player player) {
        return player.getAttribute(Attribute.SCALE).getValue();
    }

    private static double knockbackResistance(Player player) {
        return player.getAttribute(Attribute.KNOCKBACK_RESISTANCE).getValue();
    }

    /** Exactly what {@code GamblerAbility#fireBorrowed} does. */
    private static ActivationResult borrow(Scenario s, String id) {
        Ability borrowed = s.ctx().registry().create(id);
        borrowed.bind(s.owner, s.ctx(), s.ctx().paramsFor(id));
        return borrowed.onUltimate();
    }

    private static Scenario gambler() {
        // No lucky dodge / double damage: the borrowed ultimate alone decides what happens.
        return Scenario.builder("gambler").param("dodge_chance", 0).param("crit_chance", 0).start();
    }

    // ================================================================== 크리퍼 creeper

    /**
     * The creeper's blast at an enemy whose hitbox is {@code distance} blocks from the blast center: linear falloff
     * from the full damage at the center to the minimum at the edge, both scaled by {@code multiplier}.
     */
    private static double creeperBlast(Scenario s, double distance, double radius, double multiplier) {
        double max = s.param("blast_damage") * multiplier;
        double min = s.param("blast_min_damage") * multiplier;
        return min + (max - min) * (1 - distance / radius);
    }

    @Test
    void creeperSwellsUpAndBlowsUpTheEnemiesAroundIt() {
        try (Scenario s = Scenario.ffa("creeper")) {
            s.place(s.enemy, 0.5, 2.5, 180);
            s.place(s.enemy2, 0.5, -9.5, 0);
            s.assertSucceeded(s.active(), "자폭");
            assertFalse(s.active().succeeded(), "on cooldown (and already fusing)");
            assertEquals(BASE_SPEED * (1 - s.param("fuse_slow") / 100), speed(s.owner), EPSILON,
                    "the hissing creeper is slowed");
            int fuse = s.paramTicks("fuse");
            s.ticks(fuse - 4);
            assertTrue(scale(s.owner) > 1.05, "the creeper swells up: " + scale(s.owner));
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "still hissing");
            s.h.tickUntil(() -> s.damageBy(s.owner, s.enemy) > 0, 10, "the blast");

            // The enemy's hitbox is 1.7 blocks from the creeper's middle.
            assertEquals(creeperBlast(s, 1.7, s.param("blast_radius"), 1), s.damageBy(s.owner, s.enemy), EPSILON);
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON, "enemy2 is far away");
            assertEquals(s.param("blast_damage") * s.param("self_damage") / 100, s.damageTaken(s.owner), EPSILON,
                    "the creeper takes a part of its own blast");
            s.ticks(10);
            assertTrue(s.enemy.getLocation().getZ() > 4.0, "the enemy was blown away: " + s.enemy.getLocation());
            assertEquals(1.0, scale(s.owner), EPSILON, "back to its normal size");
            assertEquals(BASE_SPEED, speed(s.owner), EPSILON);

            s.ticks(60);
            assertEquals(0, displays(s), "the crater and the smoke faded");
            s.finishClean();
        }
    }

    @Test
    void creeperNeverBlowsItselfUpCompletely() {
        try (Scenario s = Scenario.ffa("creeper")) {
            s.place(s.enemy, 0.5, -9.5, 0);
            s.place(s.enemy2, 6.5, -9.5, 0);
            s.owner.setHealth(1);
            s.assertSucceeded(s.active(), "자폭");
            s.ticks(s.paramTicks("fuse") + 5);
            assertEquals(1, s.owner.getHealth(), EPSILON, "its own blast never kills it");
            assertTrue(s.session.isAlive(s.owner));
            s.finishClean();
        }
    }

    @Test
    void creeperBlastSparesAlliesAndStopsAtWalls() {
        try (Scenario s = Scenario.teams("creeper")) {
            s.place(s.enemy, 0.5, 2.5, 180);
            s.crossfireAlly();
            s.place(s.enemy2, 0.5, -2.5, 0);
            s.world.wallAlongX(-3, 3, -1, 4, Material.STONE);
            s.assertSucceeded(s.active(), "자폭");
            s.ticks(s.paramTicks("fuse") + 5);
            assertTrue(s.damageBy(s.owner, s.enemy) > 0, "the enemy in the open is hit");
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON, "the wall takes the blast for enemy2");
            assertEquals(0, s.damageTaken(s.ally()), EPSILON, "the ally right next to the enemy is spared");
            assertTrue(Scenario.hasNoHarmfulEffect(s.ally()));
            assertEquals(BASE_SPEED, speed(s.ally()), EPSILON);
            s.world.wallAlongX(-3, 3, -1, 4, Material.AIR);
            s.ticks(60);
            s.finishClean();
        }
    }

    @Test
    void creeperSneakingPacksGunpowderForABiggerBlast() {
        try (Scenario s = Scenario.ffa("creeper")) {
            s.place(s.enemy, 0.5, 2.5, 180);
            s.place(s.enemy2, 0.5, -9.5, 0);
            s.owner.setSneaking(true);
            s.ticks(s.paramTicks("charge_time") / 2);
            assertEquals(1, itemDisplays(s), "a pinch of gunpowder circles the creeper per third of the charge");
            s.ticks(s.paramTicks("charge_time") / 2 + 2);
            assertEquals(3, itemDisplays(s), "fully packed");
            s.owner.setSneaking(false);

            s.assertSucceeded(s.active(), "자폭");
            s.ticks(s.paramTicks("fuse") + 5);
            double radius = s.param("blast_radius") + s.param("charge_radius");
            double multiplier = 1 + s.param("charge_damage") / 100;
            assertEquals(creeperBlast(s, 1.7, radius, multiplier), s.damageBy(s.owner, s.enemy), EPSILON,
                    "the packed gunpowder makes the blast stronger and wider");
            assertEquals(s.param("blast_damage") * multiplier * s.param("self_damage") / 100,
                    s.damageTaken(s.owner), EPSILON);
            s.ticks(10);
            assertEquals(0, itemDisplays(s), "the gunpowder was used up");

            // Packed again, then left alone: it scatters after a while.
            s.owner.setSneaking(true);
            s.ticks(s.paramTicks("charge_time") + 2);
            s.owner.setSneaking(false);
            s.ticks(s.paramTicks("charge_hold") - 5);
            assertEquals(3, itemDisplays(s), "kept for a moment");
            s.ticks(s.paramTicks("charge_time") + 25);
            assertEquals(0, itemDisplays(s), "scattered");
            s.ticks(60);
            s.finishClean();
        }
    }

    @Test
    void creeperChargedByLightningBlowsUpAgainAndAgainWithoutHurtingItself() {
        try (Scenario s = Scenario.ffa("creeper")) {
            s.place(s.enemy, 0.5, 2.5, 180);
            s.place(s.enemy2, 0.5, -9.5, 0);
            int lightning = s.world.lightningEffects().size();
            s.assertSucceeded(s.ultimate(), "충전된 크리퍼");
            assertFalse(s.ultimate().succeeded(), "already charged");
            assertTrue(s.world.lightningEffects().size() > lightning, "struck by lightning (effect only)");
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "the lightning is only a show");

            s.assertSucceeded(s.active(), "자폭");
            assertEquals(0, s.ctx().cooldownRemaining(s.owner), EPSILON, "no cooldown while charged");
            assertEquals(BASE_SPEED, speed(s.owner), EPSILON, "no slow while charged");
            s.ticks(s.paramTicks("ult_fuse") + 2);
            double radius = s.param("blast_radius") + s.param("ult_radius");
            double multiplier = 1 + s.param("ult_damage") / 100;
            double one = creeperBlast(s, 1.7, radius, multiplier);
            assertEquals(one, s.damageBy(s.owner, s.enemy), EPSILON, "a charged blast");
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "a charged creeper does not hurt itself");

            s.place(s.enemy, 0.5, 2.5, 180);
            s.assertSucceeded(s.active(), "자폭 again right away");
            s.ticks(s.paramTicks("ult_fuse") + 2);
            assertEquals(2 * one, s.damageBy(s.owner, s.enemy), EPSILON, "and again");
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON);

            s.ticks(s.paramTicks("ult_duration") + 10);
            s.assertSucceeded(s.active(), "자폭 after the charge");
            assertTrue(s.ctx().cooldownRemaining(s.owner) > 0, "the cooldown is back");
            s.ticks(s.paramTicks("fuse") + 70);
            assertEquals(0, displays(s), "the charge aura and the craters are gone");
            s.finishClean();
        }
    }

    @Test
    void creeperDeathMidFuseIsSafe() {
        try (Scenario s = Scenario.ffa("creeper")) {
            s.place(s.enemy, 0.5, 2.5, 180);
            s.assertSucceeded(s.ultimate(), "충전된 크리퍼");
            s.owner.setSneaking(true);
            s.ticks(s.paramTicks("charge_time") + 2);
            s.owner.setSneaking(false);
            s.assertSucceeded(s.active(), "자폭");
            s.ticks(3);
            s.killOwner(s.enemy);
            s.ticks(s.paramTicks("fuse") + 40);
            assertEquals(0, s.damageBy(s.owner, s.enemy), EPSILON, "a dead creeper does not blow up");
            assertEquals(0, displays(s), "the gunpowder, the aura and the bubble went with it");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    // ================================================================== 셜커 shulker

    @Test
    void shulkerShellCutsDamageButRootsItInPlace() {
        try (Scenario s = Scenario.ffa("shulker")) {
            s.place(s.enemy, 0.5, 1.5, 180);
            s.owner.setSneaking(true);
            s.ticks(10);
            assertEquals(0, speed(s.owner), EPSILON, "a shut shulker cannot walk");
            assertEquals(0, jump(s.owner), EPSILON, "or jump");
            assertEquals(1, knockbackResistance(s.owner), EPSILON, "or be knocked back");
            assertEquals(2, blockDisplays(s), "the two halves of the shell");

            s.melee(s.enemy, s.owner, 10);
            assertEquals(10 * (1 - s.param("shell_reduction") / 100), s.damageTaken(s.owner), EPSILON);

            s.owner.setSneaking(false);
            s.ticks(2);
            assertEquals(BASE_SPEED, speed(s.owner), EPSILON, "open again");
            assertEquals(BASE_JUMP, jump(s.owner), EPSILON);
            assertEquals(0, knockbackResistance(s.owner), EPSILON);
            s.clearHits();
            s.melee(s.enemy, s.owner, 10);
            assertEquals(10, s.damageTaken(s.owner), EPSILON, "no reduction out of the shell");
            s.ticks(10);
            assertEquals(0, displays(s), "the shell is put away");
            s.finishClean();
        }
    }

    @Test
    void shulkerBulletsHuntTheEnemyDownAndFloatIt() {
        try (Scenario s = Scenario.ffa("shulker")) {
            s.place(s.enemy2, 0.5, -9.5, 0);
            s.aimAt(s.owner, s.enemy);
            s.owner.setSneaking(true);
            s.ticks(10);
            s.assertSucceeded(s.active(), "유도탄 (fired from the shut shell)");
            assertFalse(s.active().succeeded(), "on cooldown");
            double total = s.param("bullet_count") * s.param("bullet_damage");
            s.h.tickUntil(() -> s.damageBy(s.owner, s.enemy) >= total - EPSILON, 100, "both bullets hit");
            assertEquals(total, s.damageBy(s.owner, s.enemy), EPSILON);
            PotionEffect floating = s.enemy.getPotionEffect(PotionEffectType.LEVITATION);
            assertTrue(floating != null && floating.getDuration() <= s.paramTicks("levitate"),
                    "floated for " + s.param("levitate") + " s");
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON);
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.owner.setSneaking(false);
            s.ticks(20);
            assertEquals(0, displays(s), "the bullets and the shell are gone");
            s.finishClean();
        }
    }

    @Test
    void shulkerBulletsNeedSomethingToHunt() {
        try (Scenario s = Scenario.ffa("shulker")) {
            s.place(s.enemy, 0.5, -6.5, 0);
            s.place(s.enemy2, 3.5, -6.5, 0);
            ActivationResult result = s.active();
            assertFalse(result.succeeded(), "nobody in front: " + result.reason());
            assertEquals(0, s.ctx().cooldownRemaining(s.owner), EPSILON, "no cooldown spent");
            assertEquals(0, displays(s));
            s.finishClean();
        }
    }

    @Test
    void shulkerBulletsPassAlliesBy() {
        try (Scenario s = Scenario.teams("shulker")) {
            s.crossfireAlly();
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "유도탄");
            s.ticks(100);
            assertTrue(s.damageBy(s.owner, s.enemy) > 0, "the enemy is hit");
            assertEquals(0, s.damageTaken(s.ally()), EPSILON, "the ally beside it is not");
            assertTrue(Scenario.hasNoHarmfulEffect(s.ally()), "and never floated");
            assertEquals(0, displays(s));
            s.finishClean();
        }
    }

    @Test
    void shulkerFortressTurretsKeepFiringAtEnemiesButNeverAtAllies() {
        try (Scenario s = Scenario.teams("shulker")) {
            s.crossfireAlly();
            s.assertSucceeded(s.ultimate(), "셜커 요새");
            s.ticks(10);
            assertTrue(blockDisplays(s) >= 3 * s.param("turret_count"), "turrets rise around the shulker");
            s.ticks(s.paramTicks("ult_duration") + s.paramTicks("bullet_life") + 20);
            double enemies = s.damageBy(s.owner, s.enemy) + s.damageBy(s.owner, s.enemy2);
            assertTrue(enemies >= 2 * s.param("ult_damage"), "the turrets keep hitting: " + enemies);
            double shots = enemies / s.param("ult_damage");
            assertEquals(Math.round(shots), shots, EPSILON, "bullet by bullet");
            assertTrue(enemies <= s.param("turret_count") * Math.ceil(s.param("ult_duration")
                    / s.param("ult_interval")) * s.param("ult_damage") + EPSILON, "never more than fired");
            assertEquals(0, s.damageTaken(s.ally()), EPSILON, "never the ally");
            assertTrue(Scenario.hasNoHarmfulEffect(s.ally()));
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            assertEquals(0, displays(s), "the turrets sank back into the ground");
            s.finishClean();
        }
    }

    @Test
    void shulkerFortressFloatsEachEnemyOnlySoLong() {
        try (Scenario s = Scenario.ffa("shulker")) {
            s.place(s.enemy2, 0.5, -30.5, 0);
            s.assertSucceeded(s.ultimate(), "셜커 요새");
            int cap = s.paramTicks("ult_max_levitate");
            int granted = 0;
            boolean floated = false;
            double before = 0;
            for (int i = 0; i < s.paramTicks("ult_duration") + s.paramTicks("bullet_life"); i++) {
                s.ticks(1);
                double now = s.damageBy(s.owner, s.enemy);
                if (now > before + EPSILON) {
                    PotionEffect effect = s.enemy.getPotionEffect(PotionEffectType.LEVITATION);
                    if (effect != null) {
                        floated = true;
                        granted += s.paramTicks("ult_levitate");
                        s.enemy.removePotionEffect(PotionEffectType.LEVITATION);
                    }
                    before = now;
                }
            }
            assertTrue(floated, "turret bullets float the enemy");
            assertTrue(s.damageBy(s.owner, s.enemy) / s.param("ult_damage") > cap / (double) s.paramTicks(
                    "ult_levitate"), "more hits than the levitation cap allows");
            assertTrue(granted <= cap, "floated at most " + cap + " ticks, got " + granted);
            s.ticks(40);
            s.finishClean();
        }
    }

    @Test
    void shulkerFortressBorrowedByAGamblerWorks() {
        try (Scenario s = gambler()) {
            AbilityParams shulker = s.ctx().paramsFor("shulker");
            s.assertSucceeded(borrow(s, "shulker"), "borrowed 셜커 요새");
            s.ticks(10);
            assertTrue(blockDisplays(s) >= 3, "turrets rise");
            s.ticks((int) shulker.ticks("ult_duration") + (int) shulker.ticks("bullet_life") + 20);
            assertTrue(s.damageBy(s.owner, s.enemy) + s.damageBy(s.owner, s.enemy2) > 0, "and fire");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            assertEquals(0, displays(s));
            s.finishClean();
        }
    }

    @Test
    void shulkerDeathMidVolleyIsSafe() {
        try (Scenario s = Scenario.ffa("shulker")) {
            s.owner.setSneaking(true);
            s.ticks(10);
            s.assertSucceeded(s.ultimate(), "셜커 요새");
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "유도탄");
            s.ticks(2);
            s.killOwner(s.enemy2);
            double dealt = s.damageBy(s.owner, s.enemy);
            s.ticks(s.paramTicks("ult_duration") + 40);
            assertEquals(dealt, s.damageBy(s.owner, s.enemy), EPSILON, "nothing fires for a dead shulker");
            assertEquals(0, displays(s), "the shell, the turrets and the bullets went with it");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    // ================================================================== 염소 goat

    @Test
    void goatJumpsHigherAndShrugsOffFalls() {
        try (Scenario s = Scenario.ffa("goat")) {
            assertEquals(BASE_JUMP * (1 + s.param("jump_boost") / 100), jump(s.owner), EPSILON);
            s.place(s.owner, 0.5, FlatWorld.SURFACE + 10.5, 0.5, 0, 0);
            s.tickUntilGrounded(s.owner, 80);
            s.ticks(2);
            // 10.5 blocks: ceil(10.5 - 3) = 8 vanilla fall damage.
            assertEquals(8 * (1 - s.param("fall_reduction") / 100), s.damageTaken(s.owner), EPSILON);
            s.finishClean();
        }
    }

    @Test
    void goatRamsTheFirstEnemyClearAcrossTheField() {
        try (Scenario s = Scenario.ffa("goat")) {
            s.place(s.enemy2, 8.5, -6.5, 0);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "박치기");
            assertFalse(s.active().succeeded(), "on cooldown");
            assertTrue(speed(s.owner) < BASE_SPEED * 0.5, "it plants its hooves for the wind-up");
            s.ticks(s.paramTicks("ram_windup") - 1);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "still winding up");
            assertTrue(itemDisplays(s) >= 2, "the horns are lowered");
            s.h.tickUntil(() -> s.damageBy(s.owner, s.enemy) > 0, 30, "the ram");
            assertEquals(s.param("ram_damage"), s.damageBy(s.owner, s.enemy), EPSILON);
            s.ticks(25);
            assertTrue(s.enemy.getLocation().getZ() > 4.5 + s.param("ram_knockback") * 0.6,
                    "sent flying: " + s.enemy.getLocation());
            assertEquals(s.param("ram_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "no wall, no extra pain");
            assertFalse(s.enemy.hasPotionEffect(PotionEffectType.SLOWNESS));
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON);
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            assertEquals(BASE_SPEED, speed(s.owner), EPSILON);
            s.ticks(20);
            assertEquals(0, displays(s), "the horns are put away");
            s.finishClean();
        }
    }

    @Test
    void goatRamIntoAWallHurtsMore() {
        try (Scenario s = Scenario.ffa("goat")) {
            s.place(s.enemy2, 8.5, -6.5, 0);
            s.world.wallAlongX(-3, 3, 7, 4, Material.STONE);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "박치기");
            double both = s.param("ram_damage") + s.param("wall_damage");
            s.h.tickUntil(() -> s.damageBy(s.owner, s.enemy) >= both - EPSILON, s.paramTicks("ram_windup") + 30,
                    "the slam into the wall");
            assertTrue(s.enemy.hasPotionEffect(PotionEffectType.SLOWNESS), "staggered");
            s.ticks(30);
            assertEquals(both, s.damageBy(s.owner, s.enemy), EPSILON, "rammed and slammed into the wall, once");
            assertTrue(s.enemy.getLocation().getZ() < 7, "it did not pass through the wall");
            s.world.wallAlongX(-3, 3, 7, 4, Material.AIR);
            s.ticks(20);
            assertEquals(0, displays(s));
            s.finishClean();
        }
    }

    @Test
    void goatRamRunsPastAlliesToTheEnemy() {
        try (Scenario s = Scenario.teams("goat")) {
            s.place(s.ally(), 0.5, 2.0, 180);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "박치기");
            s.ticks(s.paramTicks("ram_windup") + 30);
            assertEquals(s.param("ram_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "the enemy behind the ally");
            assertEquals(0, s.damageTaken(s.ally()), EPSILON, "the ally is not rammed");
            assertTrue(Scenario.hasNoHarmfulEffect(s.ally()));
            s.finishClean();
        }
    }

    @Test
    void goatLandslideSweepsEnemiesDownTheSlope() {
        try (Scenario s = Scenario.teams("goat")) {
            s.crossfireAlly();
            double allyZ = s.ally().getLocation().getZ();
            s.assertSucceeded(s.ultimate(), "산사태");
            s.ticks(s.paramTicks("slide_delay") - 2);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "the horn first, the rocks after");
            s.ticks(120);
            double damage = s.damageBy(s.owner, s.enemy);
            double waves = damage / s.param("boulder_damage");
            assertTrue(damage >= s.param("boulder_damage"), "swept by the landslide");
            assertEquals(Math.round(waves), waves, EPSILON, "once per wave");
            assertTrue(waves <= s.param("slide_waves") + EPSILON, "at most every wave");
            assertTrue(s.damageBy(s.owner, s.enemy2) >= s.param("boulder_damage"), "enemy2 too");
            assertTrue(s.enemy.getLocation().getZ() > 4.5 + 3, "carried down the slope: " + s.enemy.getLocation());
            assertEquals(0, s.damageTaken(s.ally()), EPSILON, "the ally is never hit");
            assertEquals(allyZ, s.ally().getLocation().getZ(), 1.0E-3, "or moved");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            assertEquals(0, displays(s), "the boulders crumbled");
            s.finishClean();
        }
    }

    @Test
    void goatLandslideBreaksOnWalls() {
        try (Scenario s = Scenario.ffa("goat")) {
            s.place(s.enemy, 0.5, 5.5, 180);
            s.place(s.enemy2, 2.0, 7.5, 180);
            s.world.wallAlongX(-5, 5, 3, 2, Material.STONE);
            s.assertSucceeded(s.ultimate(), "산사태");
            s.ticks(140);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "safe behind the wall");
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON);
            assertEquals(0, displays(s));
            s.world.wallAlongX(-5, 5, 3, 2, Material.AIR);
            s.finishClean();
        }
    }

    @Test
    void goatLandslideBorrowedByAGamblerWorks() {
        try (Scenario s = gambler()) {
            s.assertSucceeded(borrow(s, "goat"), "borrowed 산사태");
            s.ticks(140);
            assertTrue(s.damageBy(s.owner, s.enemy) > 0, "the rocks hit the enemy");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            assertEquals(0, displays(s));
            s.finishClean();
        }
    }

    @Test
    void goatDeathMidRamOrMidLandslideIsSafe() {
        try (Scenario s = Scenario.ffa("goat")) {
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "산사태");
            s.assertSucceeded(s.active(), "박치기");
            s.ticks(4);
            s.killOwner(s.enemy2);
            s.ticks(160);
            assertEquals(0, s.damageBy(s.owner, s.enemy), EPSILON, "neither the ram nor the rocks land");
            assertEquals(0, displays(s), "horns and boulders went with the goat");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    // ================================================================== 거북이 turtle

    @Test
    void turtleShellTakesTheStingOutOfHitsFromBehind() {
        try (Scenario s = Scenario.ffa("turtle")) {
            s.ticks(5);
            assertEquals(3, blockDisplays(s), "the turtle wears its shell");
            double reduced = 1 - s.param("back_reduction") / 100;

            s.place(s.enemy, 0.5, -1.5, 0);
            s.melee(s.enemy, s.owner, 10);
            assertEquals(10 * reduced, s.damageTaken(s.owner), EPSILON, "a stab in the back glances off");
            s.clearHits();
            s.ctx().damage(s.enemy, s.owner, 4);
            assertEquals(4 * reduced, s.damageTaken(s.owner), EPSILON, "abilities from behind too");

            s.clearHits();
            s.place(s.enemy, 0.5, 2.5, 180);
            s.melee(s.enemy, s.owner, 10);
            assertEquals(10, s.damageTaken(s.owner), EPSILON, "the belly is soft");
            s.clearHits();
            s.place(s.enemy, 3.0, 0.5, 90);
            s.melee(s.enemy, s.owner, 10);
            assertEquals(10, s.damageTaken(s.owner), EPSILON, "and so are the sides");
            s.finishClean();
        }
    }

    @Test
    void turtleSpinBowlsOverTheEnemyInItsWay() {
        try (Scenario s = Scenario.ffa("turtle")) {
            s.place(s.enemy2, -8.5, -6.5, 0);
            s.assertSucceeded(s.active(), "껍질 회전");
            assertFalse(s.active().succeeded(), "on cooldown");
            assertEquals(0.65, scale(s.owner), EPSILON, "pulled into the shell");
            s.ticks(2);
            s.melee(s.enemy2, s.owner, 10);
            assertEquals(10 * (1 - s.param("spin_reduction") / 100), s.damageTaken(s.owner), EPSILON,
                    "the spinning shell takes less damage");
            s.h.tickUntil(() -> s.damageBy(s.owner, s.enemy) > 0, 30, "the shell reaches the enemy");
            assertEquals(s.param("spin_damage"), s.damageBy(s.owner, s.enemy), EPSILON);
            s.ticks(40);
            assertEquals(s.param("spin_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "hit once");
            assertTrue(Math.abs(s.enemy.getLocation().getX() - 0.5) > 1.0, "bowled aside: " + s.enemy.getLocation());
            assertTrue(s.owner.getLocation().getZ() > 8, "the shell slid on: " + s.owner.getLocation());
            assertEquals(1.0, scale(s.owner), EPSILON, "back out of the shell");
            assertEquals(10 * (1 - s.param("spin_reduction") / 100), s.damageTaken(s.owner), EPSILON,
                    "no fall damage");
            assertEquals(3, blockDisplays(s), "the shell is back on its back");
            s.finishClean();
        }
    }

    @Test
    void turtleSpinRicochetsOffWalls() {
        try (Scenario s = Scenario.ffa("turtle")) {
            s.world.wallAlongX(-3, 3, 4, 3, Material.STONE);
            s.place(s.enemy, 0.5, -3.5, 0);
            s.place(s.enemy2, -8.5, -6.5, 0);
            s.assertSucceeded(s.active(), "껍질 회전");
            s.ticks(60);
            assertEquals(s.param("spin_damage"), s.damageBy(s.owner, s.enemy), EPSILON,
                    "it bounced off the wall and into the enemy behind it");
            assertTrue(s.owner.getLocation().getZ() < 0, "it came back: " + s.owner.getLocation());
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON);
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            assertEquals(1.0, scale(s.owner), EPSILON);
            s.world.wallAlongX(-3, 3, 4, 3, Material.AIR);
            s.finishClean();
        }
    }

    @Test
    void turtleSpinNeverHurtsAllies() {
        try (Scenario s = Scenario.teams("turtle")) {
            s.place(s.ally(), 0.5, 2.5, 180);
            s.assertSucceeded(s.active(), "껍질 회전");
            s.ticks(50);
            assertTrue(s.damageBy(s.owner, s.enemy) > 0, "the enemy is bowled over");
            assertEquals(0, s.damageTaken(s.ally()), EPSILON, "the ally in the way is not");
            assertTrue(Scenario.hasNoHarmfulEffect(s.ally()));
            s.finishClean();
        }
    }

    @Test
    void turtleGiantShellShrugsOffHitsAndThornsEveryEnemyAround() {
        try (Scenario s = Scenario.teams("turtle")) {
            s.place(s.enemy, 0.5, 2.5, 180);
            s.place(s.enemy2, -2.5, 0.5, 270);
            s.crossfireAlly();
            s.assertSucceeded(s.ultimate(), "거대 등껍질");
            assertFalse(s.ultimate().succeeded(), "already carrying it");
            assertFalse(s.active().succeeded(), "too heavy to spin");
            s.ticks(12);
            assertEquals(BASE_SPEED * (1 - s.param("ult_slow") / 100), speed(s.owner), EPSILON, "heavy");
            assertEquals(1, knockbackResistance(s.owner), EPSILON, "unmovable");
            assertTrue(blockDisplays(s) >= 12, "a giant shell: " + blockDisplays(s));

            s.melee(s.enemy, s.owner, 10);
            assertEquals(10 * (1 - s.param("ult_reduction") / 100), s.damageTaken(s.owner), EPSILON,
                    "almost nothing gets through");
            s.ticks(8);
            double thorns = 10 * s.param("ult_thorns") / 100;
            assertEquals(thorns, s.damageBy(s.owner, s.enemy), EPSILON, "the attacker is thorned");
            assertEquals(thorns, s.damageBy(s.owner, s.enemy2), EPSILON, "and so is every enemy around");
            assertEquals(0, s.damageTaken(s.ally()), EPSILON, "never the ally");
            assertTrue(Scenario.hasNoHarmfulEffect(s.ally()));

            s.ticks(s.paramTicks("ult_duration") + 10);
            assertEquals(BASE_SPEED, speed(s.owner), EPSILON, "light again");
            assertEquals(0, knockbackResistance(s.owner), EPSILON);
            assertEquals(3, blockDisplays(s), "only its own shell is left");
            s.clearHits();
            s.melee(s.enemy, s.owner, 10);
            assertEquals(10, s.damageTaken(s.owner), EPSILON);
            s.ticks(10);
            assertEquals(0, s.damageBy(s.owner, s.enemy), EPSILON, "no thorns after the ultimate");
            s.finishClean();
        }
    }

    @Test
    void turtleDeathMidSpinOrUnderTheGiantShellIsSafe() {
        try (Scenario s = Scenario.ffa("turtle")) {
            s.assertSucceeded(s.active(), "껍질 회전");
            s.ticks(3);
            s.killOwner(s.enemy2);
            s.ticks(40);
            assertEquals(0, displays(s), "the shell went with the turtle");
            s.assertNoProblems();
            s.finishClean();
        }
        try (Scenario s = Scenario.ffa("turtle")) {
            s.assertSucceeded(s.ultimate(), "거대 등껍질");
            s.ticks(5);
            s.melee(s.enemy, s.owner, 4);
            s.killOwner(s.enemy2);
            s.ticks(s.paramTicks("ult_duration") + 20);
            assertEquals(0, displays(s), "the giant shell went with the turtle");
            assertEquals(0, s.damageBy(s.owner, s.enemy), EPSILON, "a dead turtle has no thorns");
            s.assertNoProblems();
            s.finishClean();
        }
    }
}
