package io.github.sx2xoox.mcpl.game.superpower.scenario;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.sx2xoox.mcpl.game.superpower.ability.Ability;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityParams;
import io.github.sx2xoox.mcpl.game.superpower.ability.ActivationResult;
import io.github.sx2xoox.mcpl.game.superpower.support.Scenario;
import io.github.sx2xoox.mcpl.game.superpower.support.TestPlayer;
import io.github.sx2xoox.mcpl.game.superpower.support.world.FlatWorld;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.Frog;
import org.bukkit.entity.TextDisplay;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

/** 변신 계열 (새 능력): 늑대인간, 거미, 슬라임, 드래곤. */
class CreatureNewAbilitiesTest {

    private static final double EPSILON = 1.0E-6;
    private static final double BASE_SPEED = 0.1;
    private static final double BASE_JUMP = 0.42;

    private static long displays(Scenario s) {
        return s.world.getEntities().stream().filter(entity -> entity instanceof Display).count();
    }

    private static long blockDisplays(Scenario s) {
        return s.world.getEntities().stream().filter(entity -> entity instanceof BlockDisplay).count();
    }

    private static long slimes(Scenario s) {
        return s.world.getEntities().stream().filter(entity -> entity instanceof Frog).count();
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

    /** No ability modifier at all on the player's movement attributes. */
    private static void assertUntouched(Player player, String what) {
        assertTrue(player.getAttribute(Attribute.MOVEMENT_SPEED).getModifiers().isEmpty(), what + ": speed");
        assertTrue(player.getAttribute(Attribute.JUMP_STRENGTH).getModifiers().isEmpty(), what + ": jump");
        assertTrue(Scenario.hasNoHarmfulEffect(player), what + ": no harmful effect");
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

    // ================================================================== 늑대인간 werewolf

    @Test
    void werewolfIsFiercerAtNightAndHalfAsFierceByDay() {
        try (Scenario s = Scenario.ffa("werewolf")) {
            s.world.setTime(6000);
            s.ticks(21);
            double half = s.param("night_damage") / 100 / 2;
            assertEquals(1 + half, s.owner.getAttribute(Attribute.ATTACK_DAMAGE).getValue(), EPSILON, "day: half");
            assertEquals(BASE_SPEED * (1 + s.param("night_speed") / 100 / 2), speed(s.owner), EPSILON);

            s.world.setTime(18000);
            s.ticks(21);
            assertEquals(1 + s.param("night_damage") / 100, s.owner.getAttribute(Attribute.ATTACK_DAMAGE).getValue(),
                    EPSILON, "night: full");
            assertEquals(BASE_SPEED * (1 + s.param("night_speed") / 100), speed(s.owner), EPSILON);
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.world.setTime(6000);
            s.finishClean();
        }
    }

    @Test
    void werewolfClawLeapPouncesOnTheAimedEnemyAndBleedsIt() {
        try (Scenario s = Scenario.ffa("werewolf")) {
            s.place(s.enemy2, 0.5, -9.5, 0);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "할퀴기 도약");
            assertFalse(s.active().succeeded(), "on cooldown");
            s.h.tickUntil(() -> s.damageBy(s.owner, s.enemy) > 0, 40, "the pounce reaches the enemy");
            assertEquals(s.param("claw_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "clawed once");
            assertTrue(s.owner.getLocation().getZ() > 2, "the werewolf leapt forward");
            assertTrue(blockDisplays(s) > 0, "claw marks are torn across the enemy");

            s.ticks(s.paramTicks("bleed_duration") + 10);
            double ticks = Math.floor(s.param("bleed_duration") / s.param("bleed_interval"));
            assertEquals(s.param("claw_damage") + ticks * s.param("bleed_damage"), s.damageBy(s.owner, s.enemy),
                    EPSILON, "then bleeds every " + s.param("bleed_interval") + " s");
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON, "enemy2 is far away");
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "no fall damage");
            s.ticks(20);
            assertEquals(0, displays(s), "the claw marks faded");
            s.finishClean();
        }
    }

    @Test
    void werewolfClawNeverHurtsAllies() {
        try (Scenario s = Scenario.teams("werewolf")) {
            s.crossfireAlly();
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "할퀴기 도약");
            s.ticks(30 + s.paramTicks("bleed_duration"));
            assertTrue(s.damageBy(s.owner, s.enemy) >= s.param("claw_damage"), "the enemy is clawed");
            assertEquals(0, s.damageTaken(s.ally()), EPSILON, "the ally next to it is not");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            assertUntouched(s.ally(), "ally");
            s.finishClean();
        }
    }

    @Test
    void werewolfBeastFormGrowsHowlsBleedsAndFeeds() {
        try (Scenario s = Scenario.ffa("werewolf")) {
            s.world.setTime(6000);
            s.place(s.enemy2, 0.5, -14.5, 0);
            s.assertSucceeded(s.ultimate(), "야수화");
            assertFalse(s.ultimate().succeeded(), "already a beast");
            assertTrue(speed(s.enemy) < BASE_SPEED * (1 - s.param("howl_slow") / 100) + EPSILON,
                    "the howl frightens the enemy within " + s.param("howl_radius") + " blocks");
            assertEquals(BASE_SPEED, speed(s.enemy2), EPSILON, "enemy2 is too far to hear it");
            s.ticks(12);
            assertEquals(s.param("ult_scale"), scale(s.owner), 1.0E-6, "a bigger beast");
            assertEquals(1 + s.param("night_damage") / 100, s.owner.getAttribute(Attribute.ATTACK_DAMAGE).getValue(),
                    EPSILON, "the feral passive is at full strength even by day");
            assertTrue(s.world.getEntities().stream().anyMatch(entity -> entity instanceof TextDisplay),
                    "a full moon hangs over the beast");
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "the howl itself deals no damage");

            s.owner.setHealth(10);
            s.melee(s.owner, s.enemy, 6);
            assertEquals(10 + 6 * s.param("ult_lifesteal") / 100, s.owner.getHealth(), EPSILON, "claws feed");
            s.ticks(s.paramTicks("bleed_duration") + 10);
            double ticks = Math.floor(s.param("bleed_duration") / s.param("bleed_interval"));
            assertEquals(6 + ticks * s.param("bleed_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "and bleed");

            s.ticks(s.paramTicks("ult_duration") + 20);
            assertEquals(1.0, scale(s.owner), EPSILON, "back to human size");
            assertEquals(1 + s.param("night_damage") / 100 / 2,
                    s.owner.getAttribute(Attribute.ATTACK_DAMAGE).getValue(), EPSILON, "day again");
            assertEquals(BASE_SPEED * (1 + s.param("night_speed") / 100 / 2), speed(s.owner), EPSILON);
            assertEquals(BASE_SPEED, speed(s.enemy), EPSILON, "the fear wore off");
            double health = s.owner.getHealth();
            s.melee(s.owner, s.enemy, 6);
            assertEquals(health, s.owner.getHealth(), EPSILON, "no lifesteal after the form");
            assertEquals(0, displays(s), "the moon set");
            s.assertSucceeded(s.ultimate(), "야수화 again");
            s.finishClean();
        }
    }

    @Test
    void werewolfDeathMidLeapOrMidFormIsSafe() {
        try (Scenario s = Scenario.ffa("werewolf")) {
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "야수화");
            s.ticks(3);
            s.assertSucceeded(s.active(), "할퀴기 도약");
            s.ticks(1);
            s.killOwner(s.enemy);
            s.ticks(60);
            assertEquals(0, displays(s), "the moon and claw marks went with the werewolf");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    // ================================================================== 거미 spider

    @Test
    void spiderClimbsWallsAndHangsFromThemWhileSneaking() {
        try (Scenario s = Scenario.ffa("spider")) {
            s.world.wallAlongX(-2, 2, 2, 6, Material.STONE);
            s.place(s.owner, 0.5, 1.7, 0);
            s.ticks(6);
            double climbed = s.owner.getLocation().getY();
            assertTrue(climbed > FlatWorld.SURFACE + 0.6, "climbs the wall it faces: " + climbed);

            s.owner.setSneaking(true);
            s.ticks(2);
            double hanging = s.owner.getLocation().getY();
            s.ticks(10);
            assertEquals(hanging, s.owner.getLocation().getY(), 0.1, "hangs still while sneaking");
            assertEquals(0, s.owner.getFallDistance(), 1.0E-6);

            s.world.wallAlongX(-2, 2, 2, 6, Material.AIR);
            s.owner.setSneaking(false);
            s.tickUntilGrounded(s.owner, 80);
            s.ticks(5);
            assertEquals(0, s.damageTaken(s.owner), EPSILON);

            s.place(s.owner, 0.5, 0.5, 0);
            s.ticks(5);
            assertEquals(FlatWorld.SURFACE, s.owner.getLocation().getY(), 1.0E-6, "no wall, no climbing");
            s.finishClean();
        }
    }

    @Test
    void spiderWebShotBindsTheEnemyInATemporaryCobweb() {
        try (Scenario s = Scenario.ffa("spider")) {
            s.place(s.enemy2, 0.5, -9.5, 0);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "거미줄 발사");
            s.h.tickUntil(() -> s.damageBy(s.owner, s.enemy) > 0, 20, "the web reaches the enemy");
            assertEquals(s.param("web_damage"), s.damageBy(s.owner, s.enemy), EPSILON);
            assertEquals(0, speed(s.enemy), EPSILON, "bound: cannot move");
            assertEquals(0, jump(s.enemy), EPSILON, "bound: cannot jump");
            assertEquals(Material.COBWEB, s.enemy.getLocation().getBlock().getType(), "a cobweb holds the enemy");
            assertEquals(1, s.h.hub.tempBlocks().size(), "the cobweb is temporary");

            s.clearHits();
            s.melee(s.owner, s.enemy, 4);
            assertEquals(4 * (1 + s.param("web_bonus") / 100), s.damageBy(s.owner, s.enemy), EPSILON,
                    "prey in the web takes more damage");

            s.ticks(s.paramTicks("root_duration") + 20);
            assertEquals(BASE_SPEED, speed(s.enemy), EPSILON, "free again");
            assertEquals(BASE_JUMP, jump(s.enemy), EPSILON);
            assertEquals(Material.AIR, s.enemy.getLocation().getBlock().getType(), "the cobweb is gone");
            assertEquals(0, displays(s), "the web display is gone");
            s.clearHits();
            s.melee(s.owner, s.enemy, 4);
            assertEquals(4, s.damageBy(s.owner, s.enemy), EPSILON, "no bonus once free");
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON);
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.finishClean();
        }
    }

    @Test
    void spiderWebThatMissesLeavesATrapThatClearsItself() {
        try (Scenario s = Scenario.ffa("spider")) {
            s.place(s.enemy, 6.5, 6.5, 180);
            s.place(s.enemy2, -6.5, 6.5, 180);
            s.aimAtFloor(s.owner, 0.5, 3.5);
            s.assertSucceeded(s.active(), "거미줄 발사");
            s.ticks(10);
            assertEquals(1, s.h.hub.tempBlocks().size(), "a web trap is left where the shot landed");
            assertEquals(0, s.damageTaken(s.enemy), EPSILON);
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON);
            s.ticks(s.paramTicks("trap_duration") + 5);
            assertEquals(0, s.h.hub.tempBlocks().size(), "the trap clears itself");
            s.finishClean();
        }
    }

    @Test
    void spiderNestSnaresEnemiesAndSpeedsTheSpiderButSparesAllies() {
        try (Scenario s = Scenario.teams("spider")) {
            s.crossfireAlly();
            s.assertSucceeded(s.ultimate(), "거미 둥지");
            assertFalse(s.ultimate().succeeded(), "one nest at a time");
            s.ticks(3);
            double slowed = BASE_SPEED * (1 - s.param("nest_slow") / 100);
            assertEquals(slowed, speed(s.enemy), EPSILON, "the enemy in the nest is slowed");
            assertEquals(BASE_JUMP * (1 - s.param("nest_jump") / 100), jump(s.enemy), EPSILON, "and heavy-legged");
            assertEquals(BASE_SPEED * (1 + s.param("nest_speed") / 100), speed(s.owner), EPSILON,
                    "the spider runs faster on its nest");
            assertTrue(blockDisplays(s) >= 12, "silk threads lie on the ground");

            s.ticks(s.paramTicks("nest_interval") + 2);
            assertEquals(s.param("nest_damage") * (1 + s.param("web_bonus") / 100), s.damageBy(s.owner, s.enemy),
                    EPSILON, "the nest bites (prey in the web takes the spider's bonus)");
            assertEquals(0, s.damageTaken(s.ally()), EPSILON, "never the ally");
            assertUntouched(s.ally(), "ally in the nest");

            s.place(s.enemy2, 0.5, 20.5, 180);
            s.ticks(6);
            assertEquals(BASE_SPEED, speed(s.enemy2), EPSILON, "walking out frees enemy2");

            s.ticks(s.paramTicks("nest_duration") + 20);
            assertEquals(BASE_SPEED, speed(s.enemy), EPSILON, "the nest is gone");
            assertEquals(BASE_JUMP, jump(s.enemy), EPSILON);
            assertEquals(BASE_SPEED, speed(s.owner), EPSILON);
            assertEquals(0, displays(s), "the threads were pulled in");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.assertSucceeded(s.ultimate(), "거미 둥지 again");
            s.finishClean();
        }
    }

    @Test
    void spiderNestBorrowedByAGamblerWorks() {
        try (Scenario s = gambler()) {
            AbilityParams spider = s.ctx().paramsFor("spider");
            s.assertSucceeded(borrow(s, "spider"), "borrowed 거미 둥지");
            s.ticks(3);
            assertTrue(speed(s.enemy) < BASE_SPEED, "the borrowed nest snares the enemy");
            s.ticks((int) spider.ticks("nest_duration") + 20);
            assertTrue(s.damageBy(s.owner, s.enemy) > 0, "and bites it");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            assertEquals(BASE_SPEED, speed(s.enemy), EPSILON);
            assertEquals(BASE_SPEED, speed(s.owner), EPSILON);
            assertTrue(s.owner.getAttribute(Attribute.MOVEMENT_SPEED).getModifiers().isEmpty());
            assertEquals(0, displays(s));
            s.finishClean();
        }
    }

    @Test
    void spiderDeathMidShotOrMidNestIsSafe() {
        try (Scenario s = Scenario.ffa("spider")) {
            s.assertSucceeded(s.ultimate(), "거미 둥지");
            s.ticks(5);
            s.place(s.enemy, 0.5, 20.5, 180);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "거미줄 발사");
            s.ticks(1);
            s.killOwner(s.enemy2);
            s.ticks(s.paramTicks("nest_duration") + 20);
            assertEquals(0, displays(s), "the nest and the web went with the spider");
            assertEquals(BASE_SPEED, speed(s.enemy2), EPSILON, "the snare wore off");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    // ================================================================== 슬라임 slime

    @Test
    void slimeShrugsOffFallsAndBouncesBack() {
        try (Scenario s = Scenario.ffa("slime")) {
            assertEquals(s.param("knockback_resistance") / 100,
                    s.owner.getAttribute(Attribute.KNOCKBACK_RESISTANCE).getValue(), EPSILON);
            s.place(s.owner, 0.5, FlatWorld.SURFACE + 10, 0.5, 0, 0);
            s.h.tickUntil(() -> s.owner.getLocation().getY() < FlatWorld.SURFACE + 0.01, 60, "the fall");
            s.ticks(4);
            assertTrue(s.owner.getLocation().getY() > FlatWorld.SURFACE + 0.5, "bounced back up");
            s.ticks(200);
            assertEquals(FlatWorld.SURFACE, s.owner.getLocation().getY(), 1.0E-6, "settled down");
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "never hurt by a fall");

            s.owner.setSneaking(true);
            s.place(s.owner, 0.5, FlatWorld.SURFACE + 10, 0.5, 0, 0);
            s.h.tickUntil(() -> s.owner.getLocation().getY() < FlatWorld.SURFACE + 0.01, 60, "the fall");
            s.ticks(4);
            assertEquals(FlatWorld.SURFACE, s.owner.getLocation().getY(), 1.0E-6, "sneaking: no bounce");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.owner.setSneaking(false);
            s.finishClean();
        }
    }

    @Test
    void slimeSplitSendsLittleSlimesThatBurstOnEnemies() {
        try (Scenario s = Scenario.ffa("slime")) {
            s.place(s.enemy2, 0.5, -14.5, 0);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "분열");
            assertEquals(s.param("slime_count"), slimes(s), EPSILON, "little slimes split off");
            s.h.tickUntil(() -> s.damageBy(s.owner, s.enemy) > 0, 100, "a slime reaches the enemy");
            double burst = s.param("burst_damage");
            double dealt = s.damageBy(s.owner, s.enemy);
            assertEquals(0, dealt % burst, EPSILON, "whole bursts only: " + dealt);
            assertTrue(speed(s.enemy) < BASE_SPEED, "stuck in goo");
            assertTrue(slimes(s) < s.param("slime_count"), "the slime burst");

            s.ticks(s.paramTicks("slime_duration") + 20);
            assertEquals(0, slimes(s), "every slime is gone by now");
            assertTrue(s.damageBy(s.owner, s.enemy) <= burst * s.param("slime_count") + EPSILON);
            assertEquals(BASE_SPEED, speed(s.enemy), EPSILON, "the goo dried");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            assertEquals(0, displays(s), "the goo puddles seeped away");
            s.finishClean();
        }
    }

    @Test
    void slimeBurstsNeverHurtAllies() {
        try (Scenario s = Scenario.teams("slime")) {
            s.crossfireAlly();
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "분열");
            s.ticks(s.paramTicks("slime_duration") + 20);
            assertTrue(s.damageBy(s.owner, s.enemy) > 0, "the enemy is splashed");
            assertEquals(0, s.damageTaken(s.ally()), EPSILON, "the ally next to it is not");
            assertUntouched(s.ally(), "ally");
            assertEquals(0, slimes(s));
            s.finishClean();
        }
    }

    @Test
    void slimeKingGrowsAndSlamsOnEveryLanding() {
        try (Scenario s = Scenario.ffa("slime")) {
            s.place(s.enemy, 0.5, 2.5, 180);
            s.place(s.enemy2, 0.5, -14.5, 0);
            s.assertSucceeded(s.ultimate(), "킹 슬라임");
            assertFalse(s.ultimate().succeeded(), "already king");
            s.ticks(12);
            assertEquals(s.param("ult_scale"), scale(s.owner), 1.0E-6, "a giant slime");
            assertTrue(jump(s.owner) > BASE_JUMP + 0.1, "jumps higher");
            assertTrue(s.world.getEntities().stream().anyMatch(entity -> entity instanceof Display),
                    "a crown and a jelly shell");
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "no slam without a jump");

            s.owner.setVelocity(new Vector(0, 0.8, 0));
            s.ticks(3);
            s.tickUntilGrounded(s.owner, 60);
            s.ticks(2);
            assertEquals(s.param("slam_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "the landing slams");
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON, "enemy2 is out of reach");
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "no fall damage");

            s.ticks(s.paramTicks("ult_duration") + 20);
            assertEquals(1.0, scale(s.owner), EPSILON, "back to normal size");
            assertEquals(BASE_JUMP, jump(s.owner), EPSILON);
            assertTrue(s.owner.getAttribute(Attribute.SAFE_FALL_DISTANCE).getModifiers().isEmpty());
            assertEquals(0, displays(s), "the crown and shell are gone");
            s.assertSucceeded(s.ultimate(), "킹 슬라임 again");
            s.finishClean();
        }
    }

    @Test
    void slimeKingBorrowedByAGamblerWorks() {
        try (Scenario s = gambler()) {
            AbilityParams slime = s.ctx().paramsFor("slime");
            s.place(s.enemy, 0.5, 2.5, 180);
            s.assertSucceeded(borrow(s, "slime"), "borrowed 킹 슬라임");
            s.ticks(12);
            assertEquals(slime.num("ult_scale"), scale(s.owner), 1.0E-6, "the gambler grows");
            s.owner.setVelocity(new Vector(0, 0.8, 0));
            s.ticks(3);
            s.tickUntilGrounded(s.owner, 60);
            s.ticks(2);
            assertEquals(slime.num("slam_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "and slams");
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "without hurting itself");
            s.ticks((int) slime.ticks("ult_duration") + 20);
            assertEquals(1.0, scale(s.owner), EPSILON, "shrunk back");
            assertTrue(s.owner.getAttribute(Attribute.SCALE).getModifiers().isEmpty());
            assertTrue(s.owner.getAttribute(Attribute.JUMP_STRENGTH).getModifiers().isEmpty());
            assertTrue(s.owner.getAttribute(Attribute.SAFE_FALL_DISTANCE).getModifiers().isEmpty());
            assertEquals(0, displays(s));
            s.finishClean();
        }
    }

    @Test
    void slimeDeathWithSlimesOutOrAsKingIsSafe() {
        try (Scenario s = Scenario.ffa("slime")) {
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "분열");
            s.assertSucceeded(s.ultimate(), "킹 슬라임");
            s.ticks(4);
            s.killOwner(s.enemy2);
            s.ticks(20);
            assertEquals(0, slimes(s), "the little slimes went with their owner");
            assertEquals(0, displays(s));
            s.assertNoProblems();
            s.finishClean();
        }
    }

    // ================================================================== 드래곤 dragon

    @Test
    void dragonScalesShrugOffFireAndBluntArrows() {
        try (Scenario s = Scenario.ffa("dragon")) {
            s.owner.setFireTicks(100);
            s.ticks(45);
            s.owner.damage(4, DamageSource.builder(DamageType.LAVA).build());
            s.owner.damage(4, DamageSource.builder(DamageType.IN_FIRE).build());
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "fire and lava never hurt the dragon");
            assertEquals(0, s.owner.getFireTicks(), "the flames go out");

            Arrow arrow = s.world.spawnArrow(s.enemy.getEyeLocation(), new Vector(0, 0, -1), 1, 0);
            arrow.setShooter(s.enemy);
            s.owner.damage(8, DamageSource.builder(DamageType.ARROW).withCausingEntity(s.enemy)
                    .withDirectEntity(arrow).build());
            assertEquals(8 * (1 - s.param("scale_reduction") / 100), s.damageTaken(s.owner), EPSILON);
            s.clearHits();
            s.melee(s.enemy, s.owner, 8);
            assertEquals(8, s.damageTaken(s.owner), EPSILON, "melee is not blunted");
            arrow.remove();
            s.finishClean();
        }
    }

    @Test
    void dragonBreathScorchesEnemiesInTheConeWhileItLasts() {
        try (Scenario s = Scenario.ffa("dragon")) {
            s.place(s.enemy2, 0.5, -5.5, 0);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "드래곤 브레스");
            assertFalse(s.active().succeeded(), "on cooldown");
            s.ticks(s.paramTicks("breath_duration") + 10);
            double pulses = Math.ceil(s.param("breath_duration") / s.param("breath_interval"));
            assertEquals(pulses * s.param("breath_damage"), s.damageBy(s.owner, s.enemy), EPSILON,
                    "scorched every " + s.param("breath_interval") + " s");
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON, "nothing behind the dragon");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.finishClean();
        }
    }

    @Test
    void dragonBreathStopsAtWallsAndSparesAllies() {
        try (Scenario s = Scenario.teams("dragon")) {
            s.crossfireAlly();
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "드래곤 브레스");
            s.ticks(s.paramTicks("breath_duration") + 10);
            assertTrue(s.damageBy(s.owner, s.enemy) > 0, "the enemy is scorched");
            assertEquals(0, s.damageTaken(s.ally()), EPSILON, "the ally in the cone is not");
            assertUntouched(s.ally(), "ally");

            s.clearHits();
            s.readyActive();
            s.world.wallAlongX(-3, 3, 2, 3, Material.STONE);
            s.assertSucceeded(s.active(), "드래곤 브레스 into a wall");
            s.ticks(s.paramTicks("breath_duration") + 10);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "the wall blocks the breath");
            s.world.wallAlongX(-3, 3, 2, 3, Material.AIR);
            s.finishClean();
        }
    }

    @Test
    void dragonAscentDropsBreathBombsThenGlidesDownSafely() {
        try (Scenario s = Scenario.ffa("dragon")) {
            s.place(s.enemy, 0.5, 2.5, 180);
            s.place(s.enemy2, 0.5, -14.5, 0);
            s.assertSucceeded(s.ultimate(), "용의 비상");
            assertFalse(s.ultimate().succeeded(), "already flying");
            assertTrue(s.owner.getAllowFlight() && s.owner.isFlying(), "flying");
            assertEquals(0.1f * s.param("fly_speed") / 100, s.owner.getFlySpeed(), 1.0E-6);
            s.ticks(2);
            assertTrue(blockDisplays(s) >= 6, "dragon wings");
            s.ticks(s.paramTicks("bomb_interval") + 10);
            assertTrue(s.damageBy(s.owner, s.enemy) >= s.param("bomb_damage"), "a breath bomb hit the enemy below");
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON, "enemy2 is far away");

            s.ticks(s.paramTicks("flight_duration"));
            assertFalse(s.owner.isFlying(), "the wings folded");
            assertFalse(s.owner.getAllowFlight(), "flight permission restored");
            assertEquals(0.1f, s.owner.getFlySpeed(), 1.0E-6, "fly speed restored");
            s.tickUntilGrounded(s.owner, 600);
            s.ticks(40);
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "a soft landing");
            assertEquals(0, displays(s), "wings, orbs and pools are gone");
            s.finishClean();
        }
    }

    @Test
    void dragonBombsNeverHurtAllies() {
        try (Scenario s = Scenario.teams("dragon")) {
            s.place(s.enemy, 0.5, 2.5, 180);
            s.place(s.ally(), 1.5, 1.5, 180);
            s.assertSucceeded(s.ultimate(), "용의 비상");
            s.ticks(s.paramTicks("flight_duration") + 40);
            assertTrue(s.damageBy(s.owner, s.enemy) > 0, "the enemy is bombed");
            assertEquals(0, s.damageTaken(s.ally()), EPSILON, "the ally is not");
            assertUntouched(s.ally(), "ally");
            s.tickUntilGrounded(s.owner, 600);
            s.finishClean();
        }
    }

    @Test
    void dragonDeathMidFlightOrMidBreathIsSafe() {
        try (Scenario s = Scenario.ffa("dragon")) {
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "드래곤 브레스");
            s.assertSucceeded(s.ultimate(), "용의 비상");
            s.ticks(s.paramTicks("bomb_interval") + 1);
            s.killOwner(s.enemy2);
            s.ticks(40);
            assertEquals(0, displays(s), "wings and bombs went with the dragon");
            assertFalse(s.owner.getAllowFlight() && s.owner.getGameMode() != org.bukkit.GameMode.SPECTATOR,
                    "no flight left behind");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    // ================================================================== edges: walls, ceilings, border, void

    /** The farthest z (in half blocks) straight ahead of the arena center that is still inside the arena. */
    private static double arenaEdgeZ(Scenario s) {
        org.bukkit.Location center = s.session.arena().center();
        double z = 0;
        while (z < 200 && s.ctx().isInsideArena(new org.bukkit.Location(s.world, center.getX(), FlatWorld.SURFACE,
                center.getZ() + z + 0.5))) {
            z += 0.5;
        }
        return center.getZ() + z;
    }

    @Test
    void werewolfLeapStopsShortOfTheArenaBorder() {
        try (Scenario s = Scenario.ffa("werewolf")) {
            double edge = arenaEdgeZ(s);
            double x = s.session.arena().center().getX();
            s.place(s.owner, x, edge - 2, 0);
            s.place(s.enemy, x + 12, edge - 12, 0);
            s.place(s.enemy2, x - 12, edge - 12, 0);
            s.assertSucceeded(s.active(), "할퀴기 도약 towards the border");
            s.ticks(40);
            assertTrue(s.ctx().isInsideArena(s.owner.getLocation()), "the pounce lands inside the arena");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.finishClean();
        }
    }

    @Test
    void werewolfClawsDoNotReachThroughWalls() {
        try (Scenario s = Scenario.ffa("werewolf")) {
            s.place(s.enemy, 0.5, 2.6, 180);
            s.place(s.enemy2, 0.5, -12.5, 0);
            s.world.wallAlongX(-2, 2, 1, 4, Material.STONE);
            s.place(s.owner, 0.5, 0.5, 0);
            s.assertSucceeded(s.active(), "할퀴기 도약 into a wall");
            s.ticks(40);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "the enemy behind the wall is safe");
            assertFalse(s.world.hasCollisionsIn(s.owner.getBoundingBox().expand(-0.01)), "not stuck in the wall");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.world.wallAlongX(-2, 2, 1, 4, Material.AIR);
            s.finishClean();
        }
    }

    @Test
    void werewolfBeastUnderALowCeilingGrowsOnlyAsFarAsItFitsButStillHunts() {
        try (Scenario s = Scenario.ffa("werewolf")) {
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    s.world.setBlock(x, FlatWorld.FLOOR_Y + 3, z, Material.STONE);
                }
            }
            s.assertSucceeded(s.ultimate(), "야수화 under a 2 block ceiling");
            s.ticks(12);
            double grown = scale(s.owner);
            assertTrue(grown < s.param("ult_scale") && 1.8 * grown <= 2.0 + 1.0E-6,
                    "grew only as far as the ceiling allows: " + grown);
            assertFalse(s.world.hasCollisionsIn(s.owner.getBoundingBox().expand(-0.01)));
            s.owner.setHealth(10);
            s.melee(s.owner, s.enemy, 4);
            assertEquals(10 + 4 * s.param("ult_lifesteal") / 100, s.owner.getHealth(), EPSILON, "but still feeds");
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "never suffocates");
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    s.world.setBlock(x, FlatWorld.FLOOR_Y + 3, z, Material.AIR);
                }
            }
            s.finishClean();
        }
    }

    @Test
    void spiderOnlyClimbsTheWallItFaces() {
        try (Scenario s = Scenario.ffa("spider")) {
            s.world.wallAlongX(-2, 2, 2, 6, Material.STONE);
            s.place(s.owner, 0.5, 1.7, 180);
            s.ticks(6);
            assertEquals(FlatWorld.SURFACE, s.owner.getLocation().getY(), 1.0E-6, "back to the wall: no climbing");
            s.place(s.owner, 0.5, 1.7, 0);
            s.aimAtFloor(s.owner, 0.5, 2.0);
            s.ticks(6);
            assertEquals(FlatWorld.SURFACE, s.owner.getLocation().getY(), 1.0E-6, "looking at the floor: no climbing");
            s.world.wallAlongX(-2, 2, 2, 6, Material.AIR);
            s.finishClean();
        }
    }

    @Test
    void spiderWebAgainstAWallLeavesTheTrapOnTheWallFace() {
        try (Scenario s = Scenario.ffa("spider")) {
            s.place(s.enemy, 0.5, 6.5, 180);
            s.world.wallAlongX(-2, 2, 3, 4, Material.STONE);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "거미줄 발사 at a wall");
            s.ticks(10);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "the wall stops the web");
            assertEquals(1, s.h.hub.tempBlocks().size(), "a trap clings to the wall");
            boolean found = false;
            for (int x = -1; x <= 1; x++) {
                for (int y = FlatWorld.FLOOR_Y + 1; y <= FlatWorld.FLOOR_Y + 3; y++) {
                    found |= s.world.getBlockAt(x, y, 2).getType() == Material.COBWEB;
                }
            }
            assertTrue(found, "the cobweb sits right in front of the wall");
            s.ticks(s.paramTicks("trap_duration") + 5);
            s.world.wallAlongX(-2, 2, 3, 4, Material.AIR);
            s.finishClean();
        }
    }

    @Test
    void spiderNestNeedsGroundUnderTheSpider() {
        try (Scenario s = Scenario.ffa("spider")) {
            s.world.physics().setEnabled(false);
            s.place(s.owner, 0.5, FlatWorld.SURFACE + 10, 0.5, 0, 0);
            assertFalse(s.ultimate().succeeded(), "no nest in mid-air");
            assertEquals(100, s.ctx().gauge(s.owner), EPSILON, "the gauge is kept");
            assertEquals(0, displays(s));
            s.world.physics().setEnabled(true);
            s.place(s.owner, 0.5, 0.5, 0);
            s.finishClean();
        }
    }

    @Test
    void slimeLittleSlimesKilledByEnemiesSplatterHarmlessly() {
        try (Scenario s = Scenario.ffa("slime")) {
            s.place(s.enemy, 0.5, 12.5, 180);
            s.place(s.enemy2, 0.5, -14.5, 0);
            s.assertSucceeded(s.active(), "분열");
            s.ticks(2);
            assertEquals(s.param("slime_count"), slimes(s), EPSILON);
            for (org.bukkit.entity.Entity entity : s.world.getEntities()) {
                if (entity instanceof Frog carrier) {
                    assertTrue(s.ctx().isEnemy(s.enemy, carrier), "the little slimes are fair game for enemies");
                    assertFalse(s.ctx().isEnemy(s.owner, carrier), "but never for their owner");
                    carrier.setHealth(0);
                }
            }
            s.ticks(10);
            assertEquals(0, slimes(s), "all splattered");
            s.ticks(s.paramTicks("slime_duration"));
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "dead slimes do not burst");
            assertEquals(0, displays(s), "their jelly bodies are gone");
            s.finishClean();
        }
    }

    @Test
    void slimeKingUnderALowCeilingGrowsOnlyAsFarAsItFits() {
        try (Scenario s = Scenario.ffa("slime")) {
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    s.world.setBlock(x, FlatWorld.FLOOR_Y + 4, z, Material.STONE);
                }
            }
            s.assertSucceeded(s.ultimate(), "킹 슬라임 under a 3 block ceiling");
            s.ticks(12);
            double grown = scale(s.owner);
            assertTrue(grown > 1.0 && 1.8 * grown <= 3.0 + 1.0E-6, "grew, but never into the ceiling: " + grown);
            assertFalse(s.world.hasCollisionsIn(s.owner.getBoundingBox().expand(-0.01)));
            s.ticks(s.paramTicks("ult_duration") + 20);
            assertEquals(1.0, scale(s.owner), EPSILON);
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    s.world.setBlock(x, FlatWorld.FLOOR_Y + 4, z, Material.AIR);
                }
            }
            s.finishClean();
        }
    }

    @Test
    void dragonBombsOverTheVoidFizzleOut() {
        try (Scenario s = Scenario.ffa("dragon")) {
            s.place(s.enemy, 30.5, 30.5, 180);
            s.place(s.enemy2, -30.5, 30.5, 180);
            s.assertSucceeded(s.ultimate(), "용의 비상");
            // High above the ground: farther than a bomb can fall.
            s.place(s.owner, 0.5, FlatWorld.SURFACE + 60, 0.5, 0, 0);
            s.owner.setFlying(true);
            s.ticks(s.paramTicks("bomb_interval") * 2 + 10);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON);
            assertEquals(0, s.h.hub.tempBlocks().size());
            s.ticks(s.paramTicks("flight_duration"));
            assertFalse(s.owner.isFlying());
            s.tickUntilGrounded(s.owner, 2000);
            s.ticks(40);
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "a long, soft glide down");
            assertEquals(0, displays(s), "the fizzled bombs left nothing behind");
            s.finishClean();
        }
    }

    @Test
    void onlyTheSpiderAndSlimeUltimatesCanBeBorrowed() {
        try (Scenario s = gambler()) {
            assertFalse(s.ctx().registry().create("werewolf").info().ultimateStandalone(),
                    "the beast form lives in the werewolf's own hooks");
            assertFalse(s.ctx().registry().create("dragon").info().ultimateStandalone(),
                    "flight must be taken back by the dragon itself");
            assertTrue(s.ctx().registry().create("spider").info().ultimateStandalone());
            assertTrue(s.ctx().registry().create("slime").info().ultimateStandalone());
            s.finishClean();
        }
    }

    // ================================================================== shared

    @Test
    void everyCreatureAbilityCleansUpWhenTheMatchEndsMidSkill() {
        for (String id : new String[] {"werewolf", "spider", "slime", "dragon"}) {
            try (Scenario s = Scenario.ffa(id)) {
                s.aimAt(s.owner, s.enemy);
                s.assertSucceeded(s.active(), id + " active");
                s.assertSucceeded(s.ultimate(), id + " ultimate");
                s.ticks(5);
                for (TestPlayer target : s.enemies()) {
                    assertTrue(target.getHealth() > 0, id);
                }
                s.finishClean();
            }
        }
    }
}
