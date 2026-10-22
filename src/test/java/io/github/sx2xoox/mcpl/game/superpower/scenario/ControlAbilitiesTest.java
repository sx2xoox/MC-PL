package io.github.sx2xoox.mcpl.game.superpower.scenario;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.sx2xoox.mcpl.game.superpower.support.Scenario;
import io.github.sx2xoox.mcpl.game.superpower.support.TestPlayer;
import io.github.sx2xoox.mcpl.game.superpower.support.world.FlatWorld;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

/** 제어 계열: 중력술사, 대지술사, 독술사, 거울술사. */
class ControlAbilitiesTest {

    private static final double EPSILON = 1.0E-6;

    private static double horizontalDistance(Location a, Location b) {
        return Math.hypot(a.getX() - b.getX(), a.getZ() - b.getZ());
    }

    // ------------------------------------------------------------------ 중력술사 gravity

    @Test
    void gravityPullDragsTheAimedEnemyInFrontAndSlowsIt() {
        try (Scenario s = Scenario.ffa("gravity")) {
            s.place(s.enemy, 0.5, 12.5, 180);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "끌어당기기");
            s.h.tickUntil(() -> s.enemy.hasPotionEffect(PotionEffectType.SLOWNESS), 40, "arrival");
            PotionEffect slow = s.enemy.getPotionEffect(PotionEffectType.SLOWNESS);
            assertEquals(1, slow.getAmplifier(), "slowness II");
            double distance = horizontalDistance(s.owner.getLocation(), s.enemy.getLocation());
            assertTrue(distance < 3.5, "pulled in front of the owner: " + distance);
            assertTrue(s.enemy.getLocation().getZ() > s.owner.getLocation().getZ(), "in front, not behind");
            s.ticks(30);
            // Never sunk into the floor. (The test physics lets a body with no velocity rest up to 0.01 above it.)
            double y = s.enemy.getLocation().getY();
            assertTrue(y >= FlatWorld.SURFACE - 1.0E-6 && y < FlatWorld.SURFACE + 0.01,
                    "the pulled enemy stands on the floor again: " + s.enemy.getLocation());
            assertFalse(s.world.hasCollisionsIn(s.enemy.getBoundingBox().expand(-1.0E-3)), "not inside a block");
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "the pull itself does not hurt");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.finishClean();
        }
    }

    @Test
    void gravityTetherPointsAtThePulledEnemyWhereverTheOwnerLooks() {
        try (Scenario s = Scenario.ffa("gravity")) {
            // Off to the side and below eye height: the owner's yaw and pitch are far from 0.
            s.place(s.enemy, 6.5, 10.5, 180);
            s.place(s.enemy2, -10.5, -10.5, 0);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "끌어당기기");
            for (int step = 0; step < 3; step++) {
                s.ticks(1);
                java.util.List<BlockDisplay> tether = s.world.getEntities().stream()
                        .filter(BlockDisplay.class::isInstance).map(BlockDisplay.class::cast)
                        .filter(display -> display.getBlock().getMaterial() == Material.AMETHYST_BLOCK).toList();
                assertEquals(1, tether.size(), "the gravity tether");
                BlockDisplay beam = tether.getFirst();
                // The tether leaves the owner's hand (an eye location, with the owner's yaw and pitch): the display
                // must not take that rotation on top of the beam's own.
                assertEquals(0, beam.getLocation().getYaw(), 1.0E-3, "tether display yaw");
                assertEquals(0, beam.getLocation().getPitch(), 1.0E-3, "tether display pitch");
                Vector toTarget = s.enemy.getLocation().add(0, s.enemy.getHeight() * 0.5, 0).toVector()
                        .subtract(beam.getLocation().toVector()).normalize();
                Vector forward = gravityBeamDirection(beam);
                assertTrue(forward.dot(toTarget) > 0.98, "the tether points at the pulled enemy: " + forward
                        + " vs " + toTarget);
            }
            s.ticks(40);
            s.finishClean();
        }
    }

    /**
     * The unit direction the beam display's transformation turns the model's +z axis to. A test double that
     * overrides {@code getTransformation()} keeps it faithfully; MockBukkit's own {@code DisplayMock} keeps only the
     * composed matrix and hands back a wrongly decomposed rotation, so its matrix is read directly instead.
     */
    private static Vector gravityBeamDirection(BlockDisplay beam) {
        try {
            Class<?> mock = org.mockbukkit.mockbukkit.entity.DisplayMock.class;
            org.joml.Matrix4f matrix;
            if (beam.getClass().getMethod("getTransformation").getDeclaringClass() != mock) {
                org.bukkit.util.Transformation shape = beam.getTransformation();
                matrix = new org.joml.Matrix4f().translation(shape.getTranslation()).rotate(shape.getLeftRotation())
                        .scale(shape.getScale()).rotate(shape.getRightRotation());
            } else {
                java.lang.reflect.Field field = mock.getDeclaredField("transformationMatrix");
                field.setAccessible(true);
                matrix = new org.joml.Matrix4f((org.joml.Matrix4fc) field.get(beam));
            }
            org.joml.Vector3f z = matrix.transformDirection(new org.joml.Vector3f(0, 0, 1)).normalize();
            return new Vector(z.x(), z.y(), z.z());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("the display's transformation", e);
        }
    }

    @Test
    void gravityPullNeedsAnAimedEnemy() {
        try (Scenario s = Scenario.ffa("gravity")) {
            s.place(s.owner, 0.5, 0.5, 180);
            assertFalse(s.active().succeeded(), "nobody aimed at");
            s.finishClean();
        }
    }

    @Test
    void gravityStrikesLiftEnemiesAndHitHarderInTheAir() {
        try (Scenario s = Scenario.ffa("gravity")) {
            s.place(s.enemy, 0.5, 2.5, 180);
            s.melee(s.owner, s.enemy, 4);
            assertEquals(4, s.damageBy(s.owner, s.enemy), EPSILON, "a grounded enemy: normal damage");
            s.ticks(3);
            assertTrue(s.enemy.getLocation().getY() > FlatWorld.SURFACE + 0.2, "lifted");
            s.melee(s.owner, s.enemy, 4);
            assertEquals(4 + 4 * (1 + s.param("air_bonus") / 100), s.damageBy(s.owner, s.enemy), EPSILON,
                    "air combo bonus");
            s.ticks(40);
            assertTrue(s.hitsOf(s.enemy, DamageCause.FALL).isEmpty(), "a small hop, no fall damage");
            s.finishClean();
        }
    }

    @Test
    void gravityBlackHoleSwallowsEnemiesThenCollapses() {
        try (Scenario s = Scenario.ffa("gravity")) {
            s.place(s.enemy, 0.5, 8.5, 180);
            s.place(s.enemy2, 4.5, 10.5, 180);
            s.aimAt(s.owner, s.enemy);
            Location center = s.enemy.getLocation();
            double before = horizontalDistance(s.enemy2.getLocation(), center);
            s.assertSucceeded(s.ultimate(), "블랙홀");
            s.ticks(20);
            assertTrue(horizontalDistance(s.enemy2.getLocation(), center) < before - 1,
                    "enemy2 is sucked toward the hole");
            assertEquals(0, s.damageTaken(s.enemy) + s.damageTaken(s.enemy2), EPSILON, "no damage before collapse");
            assertEquals(0.5, s.owner.getLocation().getZ(), EPSILON, "the owner is not pulled");
            s.ticks(s.paramTicks("ult_duration"));
            for (TestPlayer target : s.enemies()) {
                assertEquals(s.param("blast_damage"), s.damageBy(s.owner, target), EPSILON, target.getName());
            }
            s.ticks(2);
            assertTrue(horizontalDistance(s.enemy.getLocation(), center) > 0.3
                    || s.enemy.getLocation().getY() > FlatWorld.SURFACE + 0.2, "blasted away");
            s.ticks(60);
            assertTrue(s.hitsOf(s.enemy, DamageCause.FALL).isEmpty() && s.hitsOf(s.enemy2, DamageCause.FALL)
                    .isEmpty(), "no fall damage after the blast");
            s.finishClean();
        }
    }

    @Test
    void gravityBlackHoleBlastRespectsKnockbackResistance() {
        try (Scenario s = Scenario.ffa("gravity")) {
            AttributeInstance resistance = s.enemy.getAttribute(Attribute.KNOCKBACK_RESISTANCE);
            NamespacedKey immovable = new NamespacedKey("test", "immovable");
            resistance.addModifier(new AttributeModifier(immovable, 1.0, AttributeModifier.Operation.ADD_NUMBER));
            s.place(s.enemy, 0.5, 8.5, 180);
            s.place(s.enemy2, 1.5, 8.5, 180);
            s.aimAt(s.owner, s.enemy);
            Location center = s.enemy.getLocation();
            s.assertSucceeded(s.ultimate(), "블랙홀");
            s.ticks(s.paramTicks("ult_duration") + 6);
            assertEquals(s.param("blast_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "the immune enemy is hurt");
            assertTrue(horizontalDistance(s.enemy.getLocation(), center) < 1.0,
                    "but not thrown: " + horizontalDistance(s.enemy.getLocation(), center));
            assertTrue(horizontalDistance(s.enemy2.getLocation(), center) > 2.0, "enemy2 is blasted away");
            resistance.removeModifier(immovable);
            s.finishClean();
        }
    }

    @Test
    void gravityBlackHoleNeedsAnEnemyInRange() {
        try (Scenario s = Scenario.ffa("gravity")) {
            s.place(s.enemy, 20.5, 0.5, 0);
            s.place(s.enemy2, -20.5, 0.5, 0);
            s.aimAtFloor(s.owner, 0.5, 6.5);
            assertFalse(s.ultimate().succeeded());
            assertEquals(100, s.participant(s.owner).gauge(), EPSILON, "the gauge is kept");
            s.finishClean();
        }
    }

    // ------------------------------------------------------------------ 대지술사 earth

    @Test
    void earthQuakeHitsEveryEnemyInTheConeOnceAndLaunchesThem() {
        try (Scenario s = Scenario.ffa("earth")) {
            assertEquals(s.param("knockback_resist") / 100,
                    s.owner.getAttribute(Attribute.KNOCKBACK_RESISTANCE).getValue(), EPSILON, "passive");
            TestPlayer behind = s.enemy2;
            s.place(behind, 0.5, -4.5, 0);
            s.assertSucceeded(s.active(), "지진");
            s.ticks(4);
            assertTrue(s.world.getEntities().stream().anyMatch(e -> e instanceof BlockDisplay),
                    "the ground erupts (block displays)");
            assertTrue(s.world.getEntities().stream().filter(e -> e instanceof BlockDisplay)
                    .allMatch(e -> e.getLocation().getBlock().isPassable()),
                    "chunks stand in the air above the ground, so clients light them");
            s.ticks(30);
            assertEquals(1, s.hitsBy(s.owner, s.enemy).size(), "hit once");
            assertEquals(s.param("quake_damage"), s.damageBy(s.owner, s.enemy), EPSILON);
            assertEquals(0, s.damageTaken(behind), EPSILON, "only in front");
            assertTrue(s.world.getEntities().stream().noneMatch(e -> e instanceof BlockDisplay), "sunk back");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.finishClean();
        }
    }

    @Test
    void earthQuakeIsStoppedByTallWalls() {
        try (Scenario s = Scenario.ffa("earth")) {
            s.world.wallAlongX(-4, 5, 2, 2, Material.STONE);
            s.assertSucceeded(s.active(), "지진");
            s.ticks(30);
            assertEquals(0, s.damageTaken(s.enemy) + s.damageTaken(s.enemy2), EPSILON, "the wave cannot pass");
            s.world.wallAlongX(-4, 5, 2, 2, Material.AIR);
            s.finishClean();
        }
    }

    @Test
    void earthRockPrisonCagesEnemiesWithTemporaryStone() {
        try (Scenario s = Scenario.ffa("earth")) {
            s.assertSucceeded(s.ultimate(), "바위 감옥");
            assertTrue(s.h.hub.tempBlocks().size() > 0, "cages are temporary blocks");
            Location feet = s.enemy.getLocation();
            assertFalse(s.world.getBlockAt(feet.getBlockX() + 1, feet.getBlockY(), feet.getBlockZ())
                    .isPassable(), "walled in");
            assertFalse(s.world.getBlockAt(feet.getBlockX(), feet.getBlockY() + 2, feet.getBlockZ())
                    .isPassable(), "roofed");
            assertTrue(s.world.getBlockAt(feet.getBlockX(), feet.getBlockY() + 1, feet.getBlockZ() + 1)
                    .isPassable(), "with windows to attack through");
            assertFalse(s.world.hasCollisionsIn(s.enemy.getBoundingBox().expand(-0.01)), "never inside stone");
            assertEquals(0, s.enemy.getAttribute(Attribute.MOVEMENT_SPEED).getValue(), EPSILON, "cannot move");
            s.ticks(s.paramTicks("ult_duration") + 2);
            assertEquals(0, s.h.hub.tempBlocks().size(), "the cages crumble");
            assertTrue(s.enemy.getAttribute(Attribute.MOVEMENT_SPEED).getModifiers().isEmpty());
            assertEquals(0, s.damageTaken(s.enemy) + s.damageTaken(s.enemy2), EPSILON, "no suffocation");
            s.finishClean();
        }
    }

    // ------------------------------------------------------------------ 독술사 venom

    @Test
    void venomCannotBePoisonedAndPoisonsMeleeVictims() {
        try (Scenario s = Scenario.ffa("venom")) {
            s.owner.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 200, 1));
            s.ticks(11);
            assertFalse(s.owner.hasPotionEffect(PotionEffectType.POISON), "poison is shaken off");

            s.melee(s.owner, s.enemy, 2);
            PotionEffect poison = s.enemy.getPotionEffect(PotionEffectType.POISON);
            assertNotNull(poison);
            assertEquals(0, poison.getAmplifier(), "poison I");
            s.finishClean();
        }
    }

    @Test
    void venomCloudPoisonsAndHurtsEnemiesInsideOnly() {
        try (Scenario s = Scenario.ffa("venom")) {
            s.place(s.enemy2, 0.5, -8.5, 0);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "독안개");
            s.ticks(30);
            PotionEffect poison = s.enemy.getPotionEffect(PotionEffectType.POISON);
            assertNotNull(poison, "poisoned in the cloud");
            assertEquals(1, poison.getAmplifier(), "poison II");
            assertTrue(s.hitsBy(s.owner, s.enemy).size() >= 2, "hurt every " + s.param("cloud_interval") + " s");
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON, "outside the cloud");
            assertFalse(s.owner.hasPotionEffect(PotionEffectType.POISON));

            s.ticks(s.paramTicks("cloud_duration"));
            s.clearHits();
            s.ticks(40);
            assertTrue(s.hitsBy(s.owner, s.enemy).isEmpty(), "the cloud is gone");
            s.finishClean();
        }
    }

    @Test
    void venomPlagueHurtsSlowsAndSpreadsBetweenEnemies() {
        try (Scenario s = Scenario.ffa("venom")) {
            s.place(s.enemy2, 0.5, -20.5, 0);
            s.assertSucceeded(s.ultimate(), "역병");
            assertTrue(s.enemy.hasPotionEffect(PotionEffectType.SLOWNESS), "slowness I");
            PotionEffect wither = s.enemy.getPotionEffect(PotionEffectType.WITHER);
            assertNotNull(wither, "withered look");
            assertTrue(wither.getDuration() < 40, "too short to ever deal vanilla wither damage");
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON, "enemy2 is out of range");

            s.ticks(10);
            s.place(s.enemy2, 1.5, 5.5, 180); // walks up to the infected enemy
            s.ticks(25);
            assertTrue(s.damageBy(s.owner, s.enemy2) > 0, "the plague spread");
            assertTrue(s.enemy2.hasPotionEffect(PotionEffectType.SLOWNESS));

            s.ticks(s.paramTicks("ult_duration"));
            double expected = Math.floor(s.param("ult_duration") / s.param("ult_interval")) * s.param("ult_damage");
            assertTrue(s.damageBy(s.owner, s.enemy) >= expected && s.damageBy(s.owner, s.enemy)
                    <= expected + s.param("ult_damage"), "about one hit per second: " + s.damageBy(s.owner, s.enemy));
            s.clearHits();
            s.ticks(40);
            assertTrue(s.hitsBy(s.owner, s.enemy).isEmpty(), "the plague ran its course");
            s.finishClean();
        }
    }

    // ------------------------------------------------------------------ 거울술사 mirror

    @Test
    void mirrorRefractsProjectiles() {
        try (Scenario s = Scenario.ffa("mirror")) {
            Arrow arrow = s.world.spawnArrow(s.enemy.getEyeLocation(), new Vector(0, 0, -1), 1, 0);
            arrow.setShooter(s.enemy);
            s.owner.damage(8, DamageSource.builder(DamageType.ARROW).withCausingEntity(s.enemy)
                    .withDirectEntity(arrow).build());
            assertEquals(8 * (1 - s.param("projectile_reduction") / 100), s.damageTaken(s.owner), EPSILON);
            s.clearHits();
            s.melee(s.enemy, s.owner, 8);
            assertEquals(8, s.damageTaken(s.owner), EPSILON, "melee is not refracted");
            arrow.remove();
            s.finishClean();
        }
    }

    @Test
    void mirrorRefractsDistantAbilityHitsButNotCloseOnes() {
        try (Scenario s = Scenario.ffa("mirror")) {
            s.place(s.enemy, 0.5, 0.5 + s.param("ranged_distance") + 2, 180);
            s.ctx().damage(s.enemy, s.owner, 8);
            assertEquals(8 * (1 - s.param("projectile_reduction") / 100), s.damageTaken(s.owner), EPSILON,
                    "an ability fireball, spear or volley from afar is refracted");
            s.clearHits();
            s.place(s.enemy, 0.5, 2.5, 180);
            s.ctx().damage(s.enemy, s.owner, 8);
            assertEquals(8, s.damageTaken(s.owner), EPSILON, "a close ability hit is not");
            s.finishClean();
        }
    }

    @Test
    void mirrorShieldHalvesDamageAndReflectsToTheAttacker() {
        try (Scenario s = Scenario.ffa("mirror")) {
            s.assertSucceeded(s.active(), "반사막");
            s.melee(s.enemy, s.owner, 10);
            assertEquals(10 * (1 - s.param("shield_reduction") / 100), s.damageTaken(s.owner), EPSILON);
            s.ticks(2);
            assertEquals(10 * s.param("shield_reflect") / 100, s.damageBy(s.owner, s.enemy), EPSILON,
                    "reflects part of the original hit");
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON);

            s.ticks(s.paramTicks("shield_duration"));
            s.clearHits();
            s.melee(s.enemy, s.owner, 10);
            s.ticks(2);
            assertEquals(10, s.damageTaken(s.owner), EPSILON, "the shield is gone");
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "nothing reflected");
            s.finishClean();
        }
    }

    @Test
    void mirrorShieldsDoNotReflectEndlesslyBetweenTwoMirrors() {
        try (Scenario s = Scenario.builder("mirror").enemyAbility("mirror").start()) {
            s.assertSucceeded(s.active(), "반사막");
            s.assertSucceeded(s.match.useActive(s.enemy), "the enemy's 반사막");
            s.melee(s.enemy, s.owner, 10);
            s.ticks(10);
            assertEquals(1, s.hitsBy(s.owner, s.enemy).size(), "one reflection, not a ping-pong");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void mirrorWorldSwapsPlacesAndReflectsEverything() {
        try (Scenario s = Scenario.ffa("mirror")) {
            Location ownerSpot = s.owner.getLocation();
            Location enemySpot = s.enemy.getLocation();
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "거울 세계");
            assertTrue(horizontalDistance(s.owner.getLocation(), enemySpot) < 0.01, "the owner took its place");
            assertTrue(horizontalDistance(s.enemy.getLocation(), ownerSpot) < 0.01, "and the enemy the owner's");
            assertEquals(180, s.enemy.getLocation().getYaw(), 1.0E-3, "the enemy keeps its facing");

            s.melee(s.enemy, s.owner, 6);
            s.ticks(2);
            assertEquals(6, s.damageTaken(s.owner), EPSILON, "the mirror world does not reduce damage");
            assertEquals(6 * s.param("ult_reflect") / 100, s.damageBy(s.owner, s.enemy), EPSILON,
                    "and reflects all of it");
            s.ticks(s.paramTicks("ult_duration"));
            s.clearHits();
            s.melee(s.enemy, s.owner, 6);
            s.ticks(2);
            assertNull(s.hitsBy(s.owner, s.enemy).stream().findFirst().orElse(null), "over");
            s.finishClean();
        }
    }

    @Test
    void mirrorWorldNeverSwapsTheOwnerIntoAHazard() {
        try (Scenario s = Scenario.ffa("mirror")) {
            Location ownerSpot = s.owner.getLocation();
            Block web = s.enemy.getLocation().getBlock();
            web.setType(Material.COBWEB);
            s.aimAt(s.owner, s.enemy);
            assertFalse(s.ultimate().succeeded(), "the enemy stands in a cobweb");
            assertEquals(100, s.participant(s.owner).gauge(), EPSILON, "the gauge is kept");
            assertTrue(horizontalDistance(s.owner.getLocation(), ownerSpot) < 0.01, "the owner stays");
            web.setType(Material.AIR);
            s.finishClean();
        }
    }

    @Test
    void mirrorWorldNeedsAnAimedEnemy() {
        try (Scenario s = Scenario.ffa("mirror")) {
            s.place(s.owner, 0.5, 0.5, 180);
            assertFalse(s.ultimate().succeeded());
            s.finishClean();
        }
    }
}
