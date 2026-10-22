package io.github.sx2xoox.mcpl.game.superpower.scenario;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.sx2xoox.mcpl.game.superpower.ability.Ability;
import io.github.sx2xoox.mcpl.game.superpower.support.Scenario;
import io.github.sx2xoox.mcpl.game.superpower.support.TestPlayer;
import io.github.sx2xoox.mcpl.game.superpower.support.world.FlatWorld;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

/** 기동 · 암살 계열: 순간이동자, 그림자, 갈고리술사, 시간술사. */
class MobilityAbilitiesTest {

    private static final double EPSILON = 1.0E-6;

    private static double horizontalDistance(Location a, Location b) {
        return Math.hypot(a.getX() - b.getX(), a.getZ() - b.getZ());
    }

    // ------------------------------------------------------------------ 순간이동자 blink

    @Test
    void blinkTeleportsUpToItsDistanceAlongTheLook() {
        try (Scenario s = Scenario.ffa("blink")) {
            s.place(s.owner, 0.5, 0.5, 180);
            s.assertSucceeded(s.active(), "점멸");
            Location at = s.owner.getLocation();
            assertEquals(0.5 - s.param("distance"), at.getZ(), 0.6, "blinked " + s.param("distance") + " blocks");
            assertEquals(FlatWorld.SURFACE, at.getY(), 1.0E-6, "on the floor");
            assertEquals(180, at.getYaw(), 1.0E-3, "keeps looking the same way");
            s.ticks(20);
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.finishClean();
        }
    }

    @Test
    void blinkStopsInFrontOfWallsAndNeverEntersThem() {
        try (Scenario s = Scenario.ffa("blink")) {
            s.world.wallAlongX(-3, 3, -5, 4, Material.STONE);
            s.place(s.owner, 0.5, 0.5, 180);
            s.assertSucceeded(s.active(), "점멸");
            Location at = s.owner.getLocation();
            assertTrue(at.getZ() > -4.0 && at.getZ() < -1.0, "stopped in front of the wall at " + at.getZ());
            assertFalse(s.world.hasCollisionsIn(s.owner.getBoundingBox().expand(-0.01)), "not inside a block");

            s.readyActive();
            s.place(s.owner, 0.5, -3.5, 180);
            assertFalse(s.active().succeeded(), "no room to blink when facing the wall");
            s.world.wallAlongX(-3, 3, -5, 4, Material.AIR);
            s.finishClean();
        }
    }

    @Test
    void blinkAssaultLandsBehindTheAimedEnemyAndDoublesTheNextHitOnly() {
        try (Scenario s = Scenario.ffa("blink")) {
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "차원 습격");
            Location at = s.owner.getLocation();
            assertTrue(horizontalDistance(at, s.enemy.getLocation()) < 2.5, "next to the enemy");
            assertTrue(at.getZ() > s.enemy.getLocation().getZ(), "behind it (the enemy faces -z)");
            assertFalse(s.world.hasCollisionsIn(s.owner.getBoundingBox().expand(-0.01)));

            s.melee(s.owner, s.enemy, 4);
            assertEquals(4 * s.param("empower_multiplier"), s.damageBy(s.owner, s.enemy), EPSILON, "empowered hit");
            s.melee(s.owner, s.enemy, 4);
            assertEquals(4 * s.param("empower_multiplier") + 4, s.damageBy(s.owner, s.enemy), EPSILON,
                    "only the next hit is empowered");
            s.finishClean();
        }
    }

    @Test
    void blinkEmpowermentFadesAfterItsDuration() {
        try (Scenario s = Scenario.ffa("blink")) {
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "차원 습격");
            s.ticks(s.paramTicks("empower_duration") + 2);
            s.melee(s.owner, s.enemy, 4);
            assertEquals(4, s.damageBy(s.owner, s.enemy), EPSILON);
            s.finishClean();
        }
    }

    @Test
    void blinkPhaseDodgeAvoidsProjectilesAndRefundsCooldown() {
        try (Scenario s = Scenario.builder("blink").param("dodge_chance", 100).start()) {
            s.place(s.owner, 0.5, 0.5, 180);
            s.assertSucceeded(s.active(), "점멸");
            double cooldown = s.ctx().cooldownRemaining(s.owner);
            Arrow arrow = s.world.spawnArrow(s.enemy.getEyeLocation(), new Vector(0, 0, -1), 1, 0);
            arrow.setShooter(s.enemy);
            s.owner.damage(5, DamageSource.builder(DamageType.ARROW).withCausingEntity(s.enemy)
                    .withDirectEntity(arrow).build());
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "dodged");
            assertEquals(cooldown - s.param("dodge_refund"), s.ctx().cooldownRemaining(s.owner), 0.06,
                    "blink cooldown refunded");
            Location at = s.owner.getLocation();
            s.place(s.enemy, at.getX(), at.getZ() - 1.5, 0);
            s.melee(s.enemy, s.owner, 3);
            assertEquals(3, s.damageTaken(s.owner), EPSILON, "melee is not dodged");
            arrow.remove();
            s.finishClean();
        }
    }

    @Test
    void blinkPhaseDodgeAlsoAvoidsRangedAbilityHitsButNotCloseOnes() {
        try (Scenario s = Scenario.builder("blink").param("dodge_chance", 100).start()) {
            // Ability "projectiles" (fireball, ice spear, skill arrows) arrive as ability damage from the caster.
            s.place(s.enemy, 0.5, 0.5 + s.param("dodge_range") + 2, 180);
            s.ctx().damage(s.enemy, s.owner, 4);
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "a hit cast from afar is dodged");
            s.place(s.enemy, 0.5, 2.5, 180);
            s.ctx().damage(s.enemy, s.owner, 4);
            assertEquals(4, s.damageTaken(s.owner), EPSILON, "an ability hit from up close is not");
            s.finishClean();
        }
    }

    @Test
    void blinkNeverLandsAboveLavaButWaterIsFine() {
        try (Scenario s = Scenario.ffa("blink")) {
            s.place(s.enemy, 8.5, 0.5, 0);
            s.place(s.enemy2, -8.5, 0.5, 0);
            // A lava lake wider than the blink, one block deep, starting right in front of the owner.
            fillFloor(s, -3, 3, -16, -2, Material.LAVA);
            s.place(s.owner, 0.5, 0.5, 180);
            s.assertSucceeded(s.active(), "점멸");
            Location at = s.owner.getLocation();
            assertEquals(FlatWorld.SURFACE, at.getY(), 1.0E-6, "on the shore, not hovering: " + at);
            assertFalse(FlatWorld.isIn(s.owner, Material.LAVA), "not in the lava");
            assertTrue(s.world.getBlockAt(at.getBlockX(), FlatWorld.FLOOR_Y, at.getBlockZ()).getType()
                    != Material.LAVA, "not above the lava: " + at);

            // The same lake filled with water is a fine place to land in.
            fillFloor(s, -3, 3, -16, -2, Material.WATER);
            s.readyActive();
            s.place(s.owner, 0.5, 0.5, 180);
            s.assertSucceeded(s.active(), "점멸");
            assertEquals(0.5 - s.param("distance"), s.owner.getLocation().getZ(), 0.6, "full distance over water");
            fillFloor(s, -3, 3, -16, -2, Material.STONE);
            s.place(s.owner, 0.5, 0.5, 0);
            s.finishClean();
        }
    }

    /** Replaces the top floor layer in the given block range. */
    private static void fillFloor(Scenario s, int x1, int x2, int z1, int z2, Material type) {
        for (int x = x1; x <= x2; x++) {
            for (int z = z1; z <= z2; z++) {
                s.world.setBlock(x, FlatWorld.FLOOR_Y, z, type);
            }
        }
    }

    // ------------------------------------------------------------------ 그림자 shadow

    @Test
    void shadowStealthHidesFromEnemiesButNotFromAllies() {
        try (Scenario s = Scenario.teams("shadow")) {
            s.assertSucceeded(s.active(), "그림자 은신");
            for (TestPlayer target : s.enemies()) {
                assertFalse(target.canSee(s.owner), target.getName() + " lost sight of the shadow");
            }
            assertTrue(s.ally().canSee(s.owner), "the ally still sees the shadow");
            assertTrue(s.owner.hasPotionEffect(PotionEffectType.INVISIBILITY));
            assertTrue(s.owner.hasPotionEffect(PotionEffectType.SPEED));

            // The first strike from stealth is always a backstab, and it reveals the shadow.
            s.melee(s.owner, s.enemy, 5);
            assertEquals(5 * (1 + s.param("backstab_bonus") / 100), s.damageBy(s.owner, s.enemy), EPSILON);
            for (TestPlayer target : s.enemies()) {
                assertTrue(target.canSee(s.owner), "revealed by attacking");
            }
            assertFalse(s.owner.hasPotionEffect(PotionEffectType.INVISIBILITY));
            s.finishClean();
        }
    }

    @Test
    void shadowStealthEndsWhenHitOrWhenItExpires() {
        try (Scenario s = Scenario.ffa("shadow")) {
            s.assertSucceeded(s.active(), "그림자 은신");
            s.melee(s.enemy, s.owner, 1);
            assertTrue(s.enemy.canSee(s.owner), "revealed when hit");
            assertFalse(s.owner.hasPotionEffect(PotionEffectType.INVISIBILITY));

            s.readyActive();
            s.assertSucceeded(s.active(), "그림자 은신");
            assertFalse(s.enemy.canSee(s.owner));
            s.ticks(s.paramTicks("stealth_duration") + 2);
            assertTrue(s.enemy.canSee(s.owner), "revealed when it expires");
            assertFalse(s.owner.hasPotionEffect(PotionEffectType.INVISIBILITY));
            s.finishClean();
        }
    }

    @Test
    void shadowBackstabOnlyFromBehind() {
        try (Scenario s = Scenario.ffa("shadow")) {
            s.place(s.owner, 0.5, 3.5, 0);
            s.melee(s.owner, s.enemy, 5);
            assertEquals(5, s.damageBy(s.owner, s.enemy), EPSILON, "face to face: no bonus");
            s.place(s.owner, 0.5, 5.5, 180);
            s.melee(s.owner, s.enemy, 5);
            assertEquals(5 + 5 * (1 + s.param("backstab_bonus") / 100), s.damageBy(s.owner, s.enemy), EPSILON,
                    "from behind: +" + s.param("backstab_bonus") + "%");
            s.finishClean();
        }
    }

    @Test
    void shadowDanceCutsThreeTimesThenReturnsHome() {
        try (Scenario s = Scenario.ffa("shadow")) {
            Location home = s.owner.getLocation();
            s.assertSucceeded(s.ultimate(), "그림자 난무");
            s.ticks(40);
            int strikes = (int) s.param("ult_strikes");
            int total = s.hitsBy(s.owner, s.enemy).size() + s.hitsBy(s.owner, s.enemy2).size();
            assertEquals(strikes, total, "three cuts");
            assertEquals(strikes * s.param("ult_damage"), s.damageBy(s.owner, s.enemy)
                    + s.damageBy(s.owner, s.enemy2), EPSILON);
            assertTrue(s.damageBy(s.owner, s.enemy2) > 0, "every enemy in range is cut");
            assertTrue(s.owner.getLocation().distance(home) < 0.1, "back home: " + s.owner.getLocation());
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.finishClean();
        }
    }

    @Test
    void borrowedShadowDanceNeverEndsInAFallForTheGambler() {
        // The gambler runs the dance on a borrowed instance that never gets ticks or damage hooks.
        try (Scenario s = Scenario.builder("gambler").param("dodge_chance", 0).param("crit_chance", 0).start()) {
            int height = 6;
            for (int y = 1; y <= height; y++) {
                s.world.setBlock(0, FlatWorld.FLOOR_Y + y, 4, Material.STONE);
            }
            s.place(s.enemy, 0.5, FlatWorld.SURFACE + height, 4.5, 180, 0);
            s.place(s.enemy2, 0.5, -16.5, 0);
            Ability borrowed = s.ctx().registry().create("shadow");
            borrowed.bind(s.owner, s.ctx(), s.ctx().paramsFor("shadow"));
            s.assertSucceeded(borrowed.onUltimate(), "borrowed 그림자 난무");
            // Home gets blocked: the dance ends next to the pillar top, high in the air.
            s.world.setBlock(0, FlatWorld.FLOOR_Y + 1, 0, Material.STONE);
            s.ticks(30);
            assertTrue(s.damageBy(s.owner, s.enemy) > 0, "the dance cut the enemy on the pillar");
            s.tickUntilGrounded(s.owner, 200);
            s.ticks(5);
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "no fall damage: " + s.hits());
            for (int y = 1; y <= height; y++) {
                s.world.setBlock(0, FlatWorld.FLOOR_Y + y, 4, Material.AIR);
            }
            s.world.setBlock(0, FlatWorld.FLOOR_Y + 1, 0, Material.AIR);
            s.finishClean();
        }
    }

    // ------------------------------------------------------------------ 갈고리술사 grapple

    @Test
    void grappleHookPullsTheAimedEnemyIn() {
        try (Scenario s = Scenario.ffa("grapple")) {
            s.place(s.enemy, 0.5, 9.5, 180);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "갈고리");
            s.ticks(50);
            assertEquals(s.param("pull_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "hooked");
            double distance = horizontalDistance(s.owner.getLocation(), s.enemy.getLocation());
            assertTrue(distance < 3.5, "pulled in to " + distance);
            assertTrue(s.hitsOf(s.enemy, DamageCause.FALL).isEmpty(), "no fall damage from the pull");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.finishClean();
        }
    }

    @Test
    void grappleHookStopsAtCoverRaisedDuringItsFlight() {
        try (Scenario s = Scenario.ffa("grapple")) {
            s.place(s.enemy, 0.5, 9.5, 180);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "갈고리");
            s.world.wallAlongX(-3, 3, 5, 3, Material.STONE);
            s.ticks(50);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "the hook hit the wall, not the enemy behind it");
            assertEquals(9.5, s.enemy.getLocation().getZ(), 0.1, "and nobody was dragged");
            s.world.wallAlongX(-3, 3, 5, 3, Material.AIR);
            s.finishClean();
        }
    }

    @Test
    void grappleHookStillCatchesAnEnemySeenOverLowCover() {
        try (Scenario s = Scenario.ffa("grapple")) {
            s.place(s.enemy, 0.5, 9.5, 180);
            s.world.wallAlongX(-3, 3, 8, 1, Material.STONE);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "갈고리");
            s.ticks(50);
            assertEquals(s.param("pull_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "hooked over the low wall");
            s.world.wallAlongX(-3, 3, 8, 1, Material.AIR);
            s.place(s.enemy, 0.5, 4.5, 180);
            s.finishClean();
        }
    }

    @Test
    void grappleHookReelsTheOwnerToAnAimedBlock() {
        try (Scenario s = Scenario.ffa("grapple")) {
            s.place(s.enemy, 8.5, 0.5, 0);
            s.place(s.enemy2, -8.5, 0.5, 0);
            s.world.wallAlongX(-3, 3, 12, 6, Material.STONE);
            s.place(s.owner, 0.5, FlatWorld.SURFACE, 0.5, 0, -10);
            s.assertSucceeded(s.active(), "갈고리");
            s.ticks(60);
            assertTrue(s.owner.getLocation().getZ() > 7, "reeled toward the wall: " + s.owner.getLocation());
            s.tickUntilGrounded(s.owner, 200);
            s.ticks(5);
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "no fall damage after the swing");
            s.place(s.owner, 0.5, 0.5, 0);
            s.world.wallAlongX(-3, 3, 12, 6, Material.AIR);
            s.finishClean();
        }
    }

    @Test
    void grappleNeedsSomethingToHookAndHalvesFallDamage() {
        try (Scenario s = Scenario.ffa("grapple")) {
            s.place(s.owner, 0.5, FlatWorld.SURFACE, 0.5, 0, -80);
            assertFalse(s.active().succeeded(), "nothing to hook in the sky");
            assertEquals(1 - s.param("fall_reduction") / 100,
                    s.owner.getAttribute(Attribute.FALL_DAMAGE_MULTIPLIER).getValue(), EPSILON);
            s.place(s.owner, 0.5, FlatWorld.SURFACE + 10, 0.5, 0, 0);
            s.tickUntilGrounded(s.owner, 200);
            s.ticks(2);
            assertEquals(Math.ceil((10 - 3) * (1 - s.param("fall_reduction") / 100)), s.damageTaken(s.owner),
                    EPSILON, "half the fall damage of a 10 block drop");
            s.finishClean();
        }
    }

    @Test
    void grappleChainsSlowEnemiesAndTugBackRunaways() {
        try (Scenario s = Scenario.ffa("grapple")) {
            s.assertSucceeded(s.ultimate(), "사슬 결박");
            for (TestPlayer target : s.enemies()) {
                assertTrue(target.hasPotionEffect(PotionEffectType.SLOWNESS), target.getName() + " is slowed");
            }
            s.place(s.enemy, 0.5, 9.5, 180);
            s.ticks(20);
            double distance = s.owner.getLocation().distance(s.enemy.getLocation());
            assertTrue(distance < 8, "the chain tugs the runaway back (" + distance + ")");
            s.ticks(s.paramTicks("ult_duration"));
            s.place(s.enemy, 0.5, 12.5, 180);
            s.ticks(10);
            assertEquals(12.5, s.enemy.getLocation().getZ(), 0.1, "free after the chains break");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.finishClean();
        }
    }

    // ------------------------------------------------------------------ 시간술사 chrono

    @Test
    void chronoRewindReturnsToThePastSpotAndHealth() {
        try (Scenario s = Scenario.ffa("chrono")) {
            s.ticks(80);
            double full = s.owner.getHealth();
            s.owner.setHealth(full - 8);
            s.place(s.owner, 6.5, -6.5, 90);
            s.ticks(2);
            s.assertSucceeded(s.active(), "되감기");
            Location at = s.owner.getLocation();
            assertTrue(horizontalDistance(at, new Location(s.world, 0.5, FlatWorld.SURFACE, 0.5)) < 0.5,
                    "back where it stood " + s.param("rewind_seconds") + " s ago: " + at);
            assertEquals(full, s.owner.getHealth(), EPSILON, "and as healthy as then");
            assertEquals(INFO_COOLDOWN * (1 - s.param("cooldown_reduction") / 100),
                    s.ctx().cooldownRemaining(s.owner), 0.06, "passive: shorter cooldown");
            s.finishClean();
        }
    }

    private static final double INFO_COOLDOWN =
            io.github.sx2xoox.mcpl.game.superpower.abilities.ChronoAbility.INFO.params().get("cooldown");

    @Test
    void chronoRewindNeedsSomethingToUndo() {
        try (Scenario s = Scenario.ffa("chrono")) {
            s.ticks(80);
            assertFalse(s.active().succeeded(), "standing still at full health: nothing to rewind");
            s.finishClean();
        }
    }

    @Test
    void chronoTimeStopPinsAndSilencesEnemiesInRange() {
        try (Scenario s = Scenario.ffa("chrono")) {
            s.place(s.enemy2, 0.5, 18.5, 180);
            Location pin = s.enemy.getLocation();
            s.assertSucceeded(s.ultimate(), "시간 정지");
            assertEquals(0, s.enemy.getAttribute(Attribute.MOVEMENT_SPEED).getValue(), EPSILON);
            assertEquals(0, s.enemy.getAttribute(Attribute.JUMP_STRENGTH).getValue(), EPSILON);
            assertEquals(0, s.enemy.getAttribute(Attribute.GRAVITY).getValue(), EPSILON);
            assertTrue(s.ctx().isSilenced(s.enemy), "silenced");
            assertFalse(s.match.useActive(s.enemy).succeeded(), "a stopped enemy cannot use skills");
            assertFalse(s.ctx().isSilenced(s.enemy2), "enemy2 is out of range");

            s.enemy.teleport(pin.clone().add(2, 0, 0));
            s.ticks(3);
            assertTrue(s.enemy.getLocation().distance(pin) < 0.7, "pinned back in place");
            assertNotNull(s.world.getEntities().stream().filter(e -> e instanceof org.bukkit.entity.TextDisplay)
                    .findFirst().orElse(null), "a countdown label floats over the enemy");

            s.ticks(s.paramTicks("ult_duration"));
            assertFalse(s.ctx().isSilenced(s.enemy), "time flows again");
            assertTrue(s.enemy.getAttribute(Attribute.MOVEMENT_SPEED).getModifiers().isEmpty());
            assertTrue(s.enemy.getAttribute(Attribute.GRAVITY).getModifiers().isEmpty());
            s.finishClean();
        }
    }

    @Test
    void chronoTimeStopLetsGoOfAVictimThatDies() {
        try (Scenario s = Scenario.ffa("chrono")) {
            s.place(s.enemy2, 0.5, 18.5, 180);
            s.assertSucceeded(s.ultimate(), "시간 정지");
            s.ticks(5);
            s.enemy.setNoDamageTicks(0);
            s.enemy.damage(10_000, DamageSource.builder(DamageType.GENERIC_KILL).build());
            assertFalse(s.session.isAlive(s.enemy), "the frozen enemy died");
            s.respawn(s.enemy);
            s.ticks(2);
            Location free = s.enemy.getLocation().add(3, 0, 0);
            s.enemy.teleport(free);
            s.ticks(4);
            assertTrue(s.enemy.getLocation().distance(free) < 0.1, "the spectator is not pinned: "
                    + s.enemy.getLocation());
            assertTrue(s.world.getEntities().stream().noneMatch(TextDisplay.class::isInstance),
                    "its countdown label is gone");
            assertTrue(s.enemy.getAttribute(Attribute.MOVEMENT_SPEED).getModifiers().isEmpty(), "and unfrozen");
            s.ticks(s.paramTicks("ult_duration"));
            s.finishClean();
        }
    }
}
