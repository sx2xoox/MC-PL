package io.github.sx2xoox.mcpl.game.superpower.scenario;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.List;
import java.util.stream.Stream;

import io.github.sx2xoox.mcpl.game.superpower.ability.Ability;
import io.github.sx2xoox.mcpl.game.superpower.ability.ActivationResult;
import io.github.sx2xoox.mcpl.game.superpower.support.Scenario;
import io.github.sx2xoox.mcpl.game.superpower.support.TestPlayer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** 기술 계열 (new): 저격수, 공학자, 연금술사, 자석술사. */
class TechNewAbilitiesTest {

    private static final double EPSILON = 1.0E-6;

    static Stream<String> abilities() {
        return Stream.of("sniper", "engineer", "alchemist", "magnet");
    }

    /** The ultimates the gambler may borrow (the sniper's mark relies on its own hooks). */
    static Stream<String> standalone() {
        return Stream.of("engineer", "alchemist", "magnet");
    }

    private static <T extends Entity> List<T> entities(Scenario s, Class<T> type) {
        return s.world.getEntities().stream().filter(type::isInstance).map(type::cast).toList();
    }

    private static double horizontalDistance(Location a, Location b) {
        return Math.hypot(a.getX() - b.getX(), a.getZ() - b.getZ());
    }

    /** Aims each skill the way a player would: the engineer at the floor in front, the others at the enemy. */
    private static void aim(Scenario s) {
        if (s.abilityId.equals("engineer")) {
            s.aimAtFloor(s.owner, 0.5, 2.5);
        } else {
            s.aimAt(s.owner, s.enemy);
        }
    }

    // ================================================================== every ability

    @ParameterizedTest(name = "{0}")
    @MethodSource("abilities")
    void wholeMatch(String id) {
        try (Scenario s = Scenario.ffa(id)) {
            s.ticks(60);
            s.assertNoProblems();

            aim(s);
            s.clearHits();
            s.assertSucceeded(s.active(), id + " active");
            s.ticks(100);
            assertTrue(s.damageTaken(s.enemy) > 0, id + " active hurts the aimed enemy");
            assertEquals(0, s.damageTaken(s.owner), EPSILON, id + " active never hurts its owner: " + s.hits());

            s.layout();
            s.healAll();
            aim(s);
            s.clearHits();
            s.assertSucceeded(s.ultimate(), id + " ultimate");
            s.ticks(300);
            if (!id.equals("sniper") && !id.equals("alchemist")) {
                assertTrue(s.damageTaken(s.enemy) + s.damageTaken(s.enemy2) > 0, id + " ultimate hurts enemies");
            }
            assertEquals(0, s.damageTaken(s.owner), EPSILON, id + " ultimate never hurts its owner: " + s.hits());
            s.assertNoProblems();
            assertTrue(entities(s, Display.class).isEmpty(), id + " leaves no display behind");
            s.finishClean();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("abilities")
    void teamModeNeverHarmsTheAlly(String id) {
        try (Scenario s = Scenario.teams(id)) {
            s.ticks(40);
            s.crossfireAlly();
            aim(s);
            Location allyStart = s.ally().getLocation();
            s.clearHits();
            s.assertSucceeded(s.active(), id + " active");
            AbilityLifecycleTest.watchAlly(s, allyStart, 100);

            s.layout();
            s.crossfireAlly();
            aim(s);
            allyStart = s.ally().getLocation();
            s.assertSucceeded(s.ultimate(), id + " ultimate");
            AbilityLifecycleTest.watchAlly(s, allyStart, 200);
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("abilities")
    void ownerDiesDuringActive(String id) {
        try (Scenario s = Scenario.ffa(id)) {
            s.ticks(40);
            aim(s);
            s.assertSucceeded(s.active(), id + " active");
            s.ticks(id.equals("engineer") ? 20 : 3); // the turret is standing and shooting
            assertOwnerDeathCleansUp(s);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("abilities")
    void ownerDiesDuringUltimate(String id) {
        try (Scenario s = Scenario.ffa(id)) {
            s.ticks(40);
            aim(s);
            s.assertSucceeded(s.ultimate(), id + " ultimate");
            s.ticks(id.equals("engineer") ? 30 : 12);
            assertOwnerDeathCleansUp(s);
        }
    }

    private static void assertOwnerDeathCleansUp(Scenario s) {
        String id = s.abilityId;
        s.killOwner(s.enemy);
        s.ticks(1);
        for (Entity entity : s.world.getEntities()) {
            assertTrue(entity instanceof Player, id + ": the dead owner's " + entity.getType()
                    + " is removed with its ability");
        }
        s.clearHits();
        s.ticks(300);
        for (Scenario.Hit hit : s.hits()) {
            if (s.owner.equals(hit.causer())) {
                fail(id + ": the dead owner still dealt damage: " + hit);
            }
        }
        assertEquals(0, s.h.hub.tempBlocks().size(), id + ": no temporary blocks");
        for (TestPlayer enemy : s.enemies()) {
            assertModifierFree(enemy, id);
            assertFalse(s.ctx().isSilenced(enemy), id);
        }
        assertModifierFree(s.owner, id);
        s.assertNoProblems();
        s.finishClean();
    }

    /** No attribute modifier but the scenario's own max health bonus. */
    private static void assertModifierFree(Player player, String id) {
        for (Attribute attribute : Registry.ATTRIBUTE) {
            AttributeInstance instance = player.getAttribute(attribute);
            if (instance == null) {
                continue;
            }
            instance.getModifiers().stream()
                    .filter(modifier -> !modifier.getKey().equals(Scenario.TANK))
                    .findFirst()
                    .ifPresent(modifier -> fail(id + ": " + player.getName() + " kept " + modifier.getKey()
                            + " on " + attribute.getKey()));
        }
    }

    // ================================================================== borrowed by the gambler

    private static Scenario gambler(boolean teams) {
        Scenario.Builder builder = Scenario.builder("gambler").param("dodge_chance", 0).param("crit_chance", 0);
        return (teams ? builder.teams() : builder).start();
    }

    /** Exactly what {@code GamblerAbility#fireBorrowed} does. */
    private static ActivationResult borrow(Scenario s, String id) {
        Ability borrowed = s.ctx().registry().create(id);
        borrowed.bind(s.owner, s.ctx(), s.ctx().paramsFor(id));
        return borrowed.onUltimate();
    }

    @Test
    void sniperMarkIsNotBorrowable() {
        try (Scenario s = gambler(false)) {
            assertFalse(s.ctx().registry().get("sniper").info().ultimateStandalone(), "the mark needs its hooks");
            for (String id : standalone().toList()) {
                assertTrue(s.ctx().registry().get(id).info().ultimateStandalone(), id);
            }
            s.finishClean();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("standalone")
    void borrowedUltimateWorksOnAGambler(String id) {
        try (Scenario s = gambler(false)) {
            s.owner.setHealth(6);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(borrow(s, id), "borrowed " + id);
            boolean worked = false;
            for (int i = 0; i < 300; i++) {
                s.ticks(1);
                worked |= switch (id) {
                    case "alchemist" -> s.owner.getHealth() >= s.maxHealth(s.owner) - EPSILON
                            && s.owner.hasPotionEffect(PotionEffectType.STRENGTH);
                    default -> s.damageBy(s.owner, s.enemy) + s.damageBy(s.owner, s.enemy2) > 0;
                };
            }
            assertTrue(worked, "the borrowed " + id + " ultimate does its thing");
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "the borrowed " + id + " never hurts the gambler");
            assertModifierFree(s.owner, id);
            assertEquals(0, s.owner.getAbsorptionAmount(), EPSILON);
            for (TestPlayer target : s.enemies()) {
                assertModifierFree(target, id);
            }
            assertTrue(entities(s, Display.class).isEmpty(), id + ": no display left");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("standalone")
    void borrowedUltimateNeverHarmsTheGamblersAlly(String id) {
        try (Scenario s = gambler(true)) {
            s.crossfireAlly();
            s.aimAt(s.owner, s.enemy);
            Location allyStart = s.ally().getLocation();
            s.clearHits();
            s.assertSucceeded(borrow(s, id), "borrowed " + id);
            AbilityLifecycleTest.watchAlly(s, allyStart, 300);
            assertModifierFree(s.ally(), id);
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("standalone")
    void gamblerDyingDuringABorrowedUltimateLeavesNothingBehind(String id) {
        try (Scenario s = gambler(false)) {
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(borrow(s, id), "borrowed " + id);
            s.ticks(id.equals("engineer") ? 30 : 6);
            s.killOwner(s.enemy);
            s.ticks(1);
            for (Entity entity : s.world.getEntities()) {
                assertTrue(entity instanceof Player, id + ": " + entity.getType() + " outlived the gambler");
            }
            s.clearHits();
            s.ticks(300);
            assertTrue(s.hits().stream().noneMatch(hit -> s.owner.equals(hit.causer())),
                    id + ": the dead gambler dealt damage: " + s.hits());
            for (TestPlayer target : s.enemies()) {
                assertModifierFree(target, id);
            }
            s.assertNoProblems();
            s.finishClean();
        }
    }

    // ================================================================== 저격수 sniper

    @Test
    void sniperAimedShotFiresAfterTheAimAndHitsTheBody() {
        try (Scenario s = Scenario.ffa("sniper")) {
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "정조준 사격");
            assertTrue(s.owner.getAttribute(Attribute.MOVEMENT_SPEED).getModifiers().stream()
                    .anyMatch(modifier -> modifier.getAmount() < 0), "slowed while aiming");
            assertFalse(entities(s, BlockDisplay.class).isEmpty(), "the aiming laser");
            assertFalse(s.active().succeeded(), "one aim at a time");
            int aim = s.paramTicks("aim_time");
            s.ticks(aim - 2);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "nothing before the aim is done");
            s.ticks(4);
            assertEquals(s.param("shot_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "a body shot, no range bonus");
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON, "a single bullet");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.ticks(20);
            assertTrue(entities(s, Display.class).isEmpty(), "laser and tracer are gone");
            assertModifierFree(s.owner, "sniper");
            s.finishClean();
        }
    }

    @Test
    void sniperHeadshotHitsHarderAndRefundsTheCooldown() {
        try (Scenario s = Scenario.ffa("sniper")) {
            s.aimAt(s.owner, s.enemy.getEyeLocation());
            s.assertSucceeded(s.active(), "정조준 사격");
            s.ticks(s.paramTicks("aim_time") + 2);
            assertEquals(s.param("shot_damage") * (1 + s.param("headshot_bonus") / 100), s.damageBy(s.owner, s.enemy),
                    EPSILON, "headshot");
            assertTrue(entities(s, TextDisplay.class).size() == 1, "헤드샷! pops up");
            double expected = s.param("cooldown") - s.param("aim_time") - s.param("headshot_refund");
            assertEquals(expected, s.ctx().cooldownRemaining(s.owner), 0.2, "the headshot refunds cooldown");
            s.finishClean();
        }
    }

    @Test
    void sniperHeadshotRefundShrinksWithTheMarkedCooldown() {
        try (Scenario s = Scenario.ffa("sniper")) {
            s.assertSucceeded(s.ultimate(), "표적 지정");
            s.aimAt(s.owner, s.enemy.getEyeLocation());
            s.assertSucceeded(s.active(), "정조준 사격");
            double cut = 1 - s.param("mark_cooldown_cut") / 100;
            s.ticks(s.paramTicks("mark_aim_time") + 2);
            assertTrue(entities(s, TextDisplay.class).stream()
                    .anyMatch(label -> Scenario.plain(label.text()).contains("헤드샷")), "a marked headshot");
            double elapsed = s.param("mark_aim_time") + 0.1;
            double expected = s.param("cooldown") * cut - elapsed - s.param("headshot_refund") * cut;
            assertEquals(expected, s.ctx().cooldownRemaining(s.owner), 0.2,
                    "the refund is cut like the cooldown, so marked headshots cannot chain every second");
            s.finishClean();
        }
    }

    @Test
    void sniperLaserNeverPointsOutAStealthedEnemy() {
        try (Scenario s = Scenario.ffa("sniper")) {
            s.owner.hidePlayer(s.ctx().plugin(), s.enemy);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "정조준 사격");
            s.ticks(3);
            BlockDisplay laser = entities(s, BlockDisplay.class).getFirst();
            double toEnemy = s.owner.getEyeLocation().distance(s.enemy.getLocation().add(0, s.enemy.getHeight() / 2, 0));
            assertTrue(laser.getTransformation().getScale().z() > toEnemy + 2,
                    "the laser runs on through the hidden enemy: " + laser.getTransformation().getScale().z());
            s.ticks(s.paramTicks("aim_time"));
            assertTrue(s.damageBy(s.owner, s.enemy) > 0, "the bullet still hits whatever is really there");
            s.owner.showPlayer(s.ctx().plugin(), s.enemy);
            s.finishClean();
        }
    }

    @Test
    void sniperRangeBonusGrowsWithDistanceButNeverForMelee() {
        try (Scenario s = Scenario.ffa("sniper")) {
            s.place(s.enemy, 0.5, 16.5, 180);
            s.place(s.enemy2, 6.5, -6.5, 0);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "정조준 사격");
            s.ticks(s.paramTicks("aim_time") + 2);
            double bonus = Math.min(s.param("bonus_max"), (16 - s.param("bonus_start")) * s.param("bonus_per_block"));
            assertEquals(s.param("shot_damage") * (1 + bonus / 100), s.damageBy(s.owner, s.enemy), EPSILON,
                    "16 blocks away");
            s.clearHits();
            s.melee(s.owner, s.enemy, 4);
            assertEquals(4, s.damageBy(s.owner, s.enemy), EPSILON, "melee gets no range bonus");
            s.finishClean();
        }
    }

    @Test
    void sniperShotPassesAlliesAndStopsAtWalls() {
        try (Scenario s = Scenario.teams("sniper")) {
            s.place(s.ally(), 0.5, 2.5, 180);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "정조준 사격");
            s.ticks(s.paramTicks("aim_time") + 2);
            assertEquals(0, s.damageTaken(s.ally()), EPSILON, "the bullet passes through the ally");
            assertTrue(s.damageBy(s.owner, s.enemy) > 0, "and hits the enemy behind");

            s.place(s.ally(), -4.5, 0.5, 0);
            s.world.wallAlongX(-1, 1, 2, 3, Material.STONE);
            s.clearHits();
            s.readyActive();
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "정조준 사격 into a wall");
            s.ticks(s.paramTicks("aim_time") + 2);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "the wall stops the bullet");
            s.world.wallAlongX(-1, 1, 2, 3, Material.AIR);
            s.finishClean();
        }
    }

    @Test
    void sniperShotGoesWhereTheCrosshairRestsAtTheEnd() {
        try (Scenario s = Scenario.ffa("sniper")) {
            s.place(s.enemy2, 4.5, 6.5, 180);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "정조준 사격");
            s.ticks(s.paramTicks("aim_time") / 2);
            s.aimAt(s.owner, s.enemy2); // the sniper swings over to another target while aiming
            s.ticks(s.paramTicks("aim_time") / 2 + 3);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "the first target escaped");
            assertTrue(s.damageBy(s.owner, s.enemy2) > 0, "the shot follows the final aim");
            s.finishClean();
        }
    }

    @Test
    void sniperSilencedMidAimFizzles() {
        try (Scenario s = Scenario.ffa("sniper")) {
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "정조준 사격");
            s.ticks(5);
            s.ctx().silence(s.owner, 40);
            s.ticks(s.paramTicks("aim_time") + 5);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "a silenced sniper never fires");
            assertTrue(entities(s, Display.class).isEmpty(), "the laser goes out");
            assertModifierFree(s.owner, "sniper");
            s.finishClean();
        }
    }

    @Test
    void sniperMarkNeedsEnemiesInRange() {
        try (Scenario s = Scenario.builder("sniper").param("mark_range", 3).start()) {
            assertFalse(s.ultimate().succeeded(), "nobody within 3 blocks");
            s.finishClean();
        }
    }

    @Test
    void sniperMarkExposesEnemiesAndSpeedsUpTheRifle() {
        try (Scenario s = Scenario.ffa("sniper")) {
            double armorBefore = s.enemy.getAttribute(Attribute.ARMOR).getValue();
            s.assertSucceeded(s.ultimate(), "표적 지정");
            for (TestPlayer target : s.enemies()) {
                assertTrue(target.hasPotionEffect(PotionEffectType.GLOWING), target.getName() + " glows");
            }
            for (TestPlayer target : s.enemies()) {
                assertTrue(target.getAttribute(Attribute.ARMOR).getModifiers().stream()
                        .anyMatch(modifier -> modifier.getAmount() == -s.param("mark_armor")),
                        target.getName() + "'s armor is shredded");
            }
            s.ticks(12);
            assertFalse(entities(s, TextDisplay.class).isEmpty(), "reticles over the targets");

            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "the rifle is ready right away");
            assertEquals(s.param("cooldown") * (1 - s.param("mark_cooldown_cut") / 100),
                    s.ctx().cooldownRemaining(s.owner), 0.1, "much shorter cooldown");
            s.ticks(s.paramTicks("mark_aim_time") + 2);
            assertEquals(s.param("shot_damage") * (1 + s.param("mark_bonus") / 100), s.damageBy(s.owner, s.enemy),
                    EPSILON, "marked targets take more damage");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);

            s.ticks(s.paramTicks("mark_duration"));
            assertEquals(armorBefore, s.enemy.getAttribute(Attribute.ARMOR).getValue(), EPSILON, "armor back");
            assertTrue(entities(s, Display.class).isEmpty(), "reticles gone");
            s.readyActive();
            s.assertSucceeded(s.active(), "정조준 사격 after the mark");
            assertEquals(s.param("cooldown"), s.ctx().cooldownRemaining(s.owner), 0.1, "normal cooldown again");
            s.ticks(40);
            s.finishClean();
        }
    }

    @Test
    void sniperMarkEndsWithTheSniper() {
        try (Scenario s = Scenario.ffa("sniper")) {
            s.assertSucceeded(s.ultimate(), "표적 지정");
            s.ticks(5);
            s.killOwner(s.enemy);
            for (TestPlayer target : s.enemies()) {
                assertFalse(target.hasPotionEffect(PotionEffectType.GLOWING), target.getName() + " stops glowing");
                assertModifierFree(target, "sniper");
            }
            s.finishClean();
        }
    }

    // ================================================================== 공학자 engineer

    @Test
    void engineerTurretShootsTheNearestVisibleEnemyUntilItExpires() {
        try (Scenario s = Scenario.ffa("engineer")) {
            s.aimAtFloor(s.owner, 0.5, 2.5);
            s.assertSucceeded(s.active(), "포탑 설치");
            s.ticks(12);
            assertTrue(entities(s, BlockDisplay.class).size() >= 5, "a turret stands");
            int life = s.paramTicks("turret_duration");
            s.ticks(life + 20);
            List<Scenario.Hit> hits = s.hitsBy(s.owner, s.enemy);
            assertTrue(hits.size() >= 3, "the turret keeps shooting: " + hits.size());
            assertTrue(hits.size() <= life / s.paramTicks("turret_interval") + 1, "at its rate: " + hits.size());
            for (Scenario.Hit hit : hits) {
                assertEquals(s.param("turret_damage"), hit.damage(), EPSILON);
            }
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON, "it sticks to the nearest enemy");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            assertTrue(entities(s, Display.class).isEmpty(), "the turret is taken down");
            s.clearHits();
            s.ticks(40);
            assertTrue(s.hits().isEmpty(), "no shots after it expired");
            s.finishClean();
        }
    }

    @Test
    void engineerTurretIgnoresEnemiesHiddenFromTheEngineer() {
        try (Scenario s = Scenario.ffa("engineer")) {
            s.owner.hidePlayer(s.ctx().plugin(), s.enemy);
            s.aimAtFloor(s.owner, 0.5, 2.5);
            s.assertSucceeded(s.active(), "포탑 설치");
            s.ticks(60);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "a stealthed enemy is not shot");
            assertTrue(s.damageBy(s.owner, s.enemy2) > 0, "the visible one is");
            s.owner.showPlayer(s.ctx().plugin(), s.enemy);
            s.finishClean();
        }
    }

    @Test
    void engineerIsGuardedNearItsTurretAndKeepsOnlyOne() {
        try (Scenario s = Scenario.ffa("engineer")) {
            s.ctx().damage(s.enemy, s.owner, 10);
            assertEquals(10, s.damageBy(s.enemy, s.owner), EPSILON, "no turret, no guard");
            s.healAll();
            s.clearHits();

            s.aimAtFloor(s.owner, 0.5, 2.5);
            s.assertSucceeded(s.active(), "포탑 설치");
            s.ticks(12);
            s.ctx().damage(s.enemy, s.owner, 10);
            assertEquals(10 * (1 - s.param("guard_reduction") / 100), s.damageBy(s.enemy, s.owner), EPSILON,
                    "guarded next to the turret");
            s.clearHits();
            s.place(s.owner, 0.5, -12.5, 0);
            s.ctx().damage(s.enemy, s.owner, 10);
            assertEquals(10, s.damageBy(s.enemy, s.owner), EPSILON, "out of the guard radius");

            s.ticks(15); // the guard panel fades
            int parts = entities(s, Display.class).size();
            s.place(s.owner, 0.5, 0.5, 0);
            s.readyActive();
            s.aimAtFloor(s.owner, -2.5, 2.5);
            s.assertSucceeded(s.active(), "a second turret");
            s.ticks(20);
            assertEquals(parts, entities(s, Display.class).size(), "the first one was taken down");
            s.finishClean();
        }
    }

    @Test
    void engineerTurretsNeverShootThroughWalls() {
        try (Scenario s = Scenario.ffa("engineer")) {
            s.place(s.enemy, 0.5, 8.5, 180);
            s.place(s.enemy2, 12.5, -12.5, 0);
            s.world.wallAlongX(-3, 3, 5, 4, Material.STONE);
            s.aimAtFloor(s.owner, 0.5, 2.5);
            s.assertSucceeded(s.active(), "포탑 설치");
            s.ticks(60);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "the gun turret has no line of sight");
            s.assertSucceeded(s.ultimate(), "레이저 포탑");
            s.ticks(80);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "neither has the laser");
            s.world.wallAlongX(-3, 3, 5, 4, Material.AIR);
            s.ticks(40);
            assertTrue(s.damageBy(s.owner, s.enemy) > 0, "once the wall is gone, the laser opens fire");
            s.finishClean();
        }
    }

    @Test
    void engineerNeedsFloorForATurret() {
        try (Scenario s = Scenario.ffa("engineer")) {
            s.place(s.owner, 0.5, 80, 0.5, 0, -60);
            assertFalse(s.active().succeeded(), "no floor in reach");
            s.place(s.owner, 0.5, 0.5, 0);
            s.finishClean();
        }
    }

    @Test
    void engineerLaserPiercesEveryEnemyAlongTheBeam() {
        try (Scenario s = Scenario.ffa("engineer")) {
            s.place(s.enemy, 0.5, 6.5, 180);
            s.place(s.enemy2, 0.5, 9.5, 180);
            s.aimAtFloor(s.owner, 0.5, 2.5);
            s.assertSucceeded(s.ultimate(), "레이저 포탑");
            s.ticks(s.paramTicks("laser_charge") + 30);
            assertTrue(s.damageBy(s.owner, s.enemy) > 0, "the nearest enemy is burned");
            assertTrue(s.damageBy(s.owner, s.enemy2) > 0, "the beam pierces to the one behind");
            s.ticks(s.paramTicks("laser_duration") + 20);
            int maxHits = s.paramTicks("laser_duration") / s.paramTicks("laser_interval") + 1;
            for (TestPlayer target : s.enemies()) {
                List<Scenario.Hit> hits = s.hitsBy(s.owner, target);
                assertTrue(hits.size() <= maxHits, target.getName() + " burned " + hits.size() + " times");
                for (Scenario.Hit hit : hits) {
                    assertEquals(s.param("laser_damage"), hit.damage(), EPSILON);
                }
            }
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            assertTrue(entities(s, Display.class).isEmpty(), "the laser turret is gone");
            s.finishClean();
        }
    }

    @Test
    void engineerLaserSweepsRoundToATargetBehindIt() {
        try (Scenario s = Scenario.ffa("engineer")) {
            s.place(s.enemy, 0.5, -6.5, 0); // behind the engineer: the beam starts out facing the other way
            s.place(s.enemy2, 30.5, 30.5, 0);
            s.aimAtFloor(s.owner, 0.5, 2.5);
            s.assertSucceeded(s.ultimate(), "레이저 포탑");
            s.ticks(25);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "the beam is still swinging round");
            s.ticks(40);
            assertTrue(s.damageBy(s.owner, s.enemy) > 0, "then it reaches the target and burns it");
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "the sweep passes over the engineer harmlessly");
            s.finishClean();
        }
    }

    // ================================================================== 연금술사 alchemist

    @Test
    void alchemistStretchesBlessingsAndShortensCurses() {
        try (Scenario s = Scenario.ffa("alchemist")) {
            s.owner.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 100, 1));
            s.owner.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 100, 0));
            s.ticks(1);
            int slow = s.owner.getPotionEffect(PotionEffectType.SLOWNESS).getDuration();
            int speed = s.owner.getPotionEffect(PotionEffectType.SPEED).getDuration();
            assertEquals(Math.round(100 * (1 - s.param("bane_reduce") / 100)), slow, 1, "curses wear off sooner");
            assertEquals(Math.round(100 * (1 + s.param("boon_extend") / 100)), speed, 1, "blessings last longer");
            assertEquals(1, s.owner.getPotionEffect(PotionEffectType.SLOWNESS).getAmplifier(), "same level");
            s.ticks(5);
            assertTrue(s.owner.getPotionEffect(PotionEffectType.SLOWNESS).getDuration() <= slow, "rescaled once");
            assertTrue(s.owner.getPotionEffect(PotionEffectType.SPEED).getDuration() <= speed, "rescaled once");
            s.finishClean();
        }
    }

    @Test
    void alchemistStretchesABlessingWithoutLosingAWeakerOneUnderIt() {
        try (Scenario s = Scenario.ffa("alchemist")) {
            double extend = 1 + s.param("boon_extend") / 100;
            s.owner.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 400, 0));
            s.ticks(1);
            s.owner.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 60, 1)); // a short speed II on top
            s.ticks(1);
            PotionEffect strong = s.owner.getPotionEffect(PotionEffectType.SPEED);
            assertEquals(1, strong.getAmplifier());
            assertEquals(Math.round(60 * extend), strong.getDuration(), 2, "the speed II is stretched");
            s.ticks((int) Math.round(60 * extend) + 5);
            PotionEffect weak = s.owner.getPotionEffect(PotionEffectType.SPEED);
            assertNotNull(weak, "the longer speed I under it was kept");
            assertEquals(0, weak.getAmplifier());
            assertTrue(weak.getDuration() <= Math.round(400 * extend), "and stretched only once: "
                    + weak.getDuration());
            s.finishClean();
        }
    }

    private static final List<PotionEffectType> BANES = List.of(PotionEffectType.POISON, PotionEffectType.SLOWNESS,
            PotionEffectType.WEAKNESS, PotionEffectType.BLINDNESS);
    private static final List<PotionEffectType> BOONS = List.of(PotionEffectType.SPEED,
            PotionEffectType.REGENERATION, PotionEffectType.RESISTANCE);

    private static long count(LivingEntity target, List<PotionEffectType> types) {
        return types.stream().filter(target::hasPotionEffect).count();
    }

    @Test
    void alchemistFlaskCursesEnemiesAndBlessesTheTeam() {
        try (Scenario s = Scenario.teams("alchemist")) {
            s.crossfireAlly();
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "실험 물약");
            assertFalse(entities(s, Display.class).isEmpty(), "a flask in the air");
            s.ticks(20);
            for (TestPlayer target : s.enemies()) {
                assertEquals(s.param("splash_damage"), s.damageBy(s.owner, target), EPSILON, target.getName());
                assertEquals(1, count(target, BANES), target.getName() + " is cursed with one brew: "
                        + target.getActivePotionEffects());
                assertEquals(0, count(target, BOONS), target.getName() + " gets no blessing");
            }
            assertEquals(1, count(s.ally(), BOONS), "the ally is blessed: " + s.ally().getActivePotionEffects());
            assertTrue(Scenario.hasNoHarmfulEffect(s.ally()), "and never cursed");
            assertEquals(0, s.damageTaken(s.ally()), EPSILON);
            assertEquals(0, count(s.owner, BOONS), "the alchemist stood outside the splash");
            assertFalse(entities(s, TextDisplay.class).isEmpty(), "labels show the brews");
            s.ticks(40);
            assertTrue(entities(s, Display.class).isEmpty(), "labels and flask are gone");
            s.finishClean();
        }
    }

    @Test
    void alchemistFlaskAtItsFeetBlessesItself() {
        try (Scenario s = Scenario.ffa("alchemist")) {
            s.aimAtFloor(s.owner, 0.5, 1.5);
            s.assertSucceeded(s.active(), "실험 물약");
            s.ticks(20);
            assertEquals(1, count(s.owner, BOONS), "blessed: " + s.owner.getActivePotionEffects());
            assertTrue(Scenario.hasNoHarmfulEffect(s.owner), "never cursed");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.finishClean();
        }
    }

    @Test
    void alchemistStoneRestoresAndEmpowersTheAlchemistAndCleansesAllies() {
        try (Scenario s = Scenario.teams("alchemist")) {
            s.place(s.ally(), 2.5, 0.5, 0);
            s.owner.setHealth(6);
            s.owner.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, 39, 0));
            s.ally().setHealth(10);
            s.ally().addPotionEffect(new PotionEffect(PotionEffectType.POISON, 200, 0));
            s.enemy.setHealth(10);
            s.enemy.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 200, 0));
            s.assertSucceeded(s.ultimate(), "현자의 돌");
            s.ticks(2);
            assertEquals(6, s.owner.getHealth(), EPSILON, "the stone is still being forged");
            assertEquals(2, entities(s, BlockDisplay.class).size(), "the gem");
            assertEquals(7, entities(s, TextDisplay.class).size(), "the planetary symbols");
            s.ticks(s.paramTicks("stone_cast") + 5);
            assertEquals(s.maxHealth(s.owner), s.owner.getHealth(), EPSILON, "fully healed");
            assertFalse(s.owner.hasPotionEffect(PotionEffectType.WITHER), "cleansed");
            assertTrue(s.owner.hasPotionEffect(PotionEffectType.STRENGTH), "strength");
            assertEquals(1, s.owner.getPotionEffect(PotionEffectType.SPEED).getAmplifier(), "speed II");
            assertTrue(s.owner.hasPotionEffect(PotionEffectType.RESISTANCE), "resistance");
            assertEquals(10 + s.param("ally_heal"), s.ally().getHealth(), EPSILON, "the ally is healed");
            assertFalse(s.ally().hasPotionEffect(PotionEffectType.POISON), "and cleansed");
            assertEquals(10, s.enemy.getHealth(), EPSILON, "enemies get nothing");
            assertTrue(s.enemy.hasPotionEffect(PotionEffectType.SLOWNESS));
            assertFalse(s.enemy.hasPotionEffect(PotionEffectType.STRENGTH));
            // The passive stretches the stone's buffs on the alchemist, and the shards orbit as long as they last.
            int buffs = (int) Math.round(s.paramTicks("stone_duration") * (1 + s.param("boon_extend") / 100));
            assertEquals(buffs, s.owner.getPotionEffect(PotionEffectType.STRENGTH).getDuration(), 25);
            s.ticks(buffs - 30);
            assertEquals(3, entities(s, BlockDisplay.class).size(), "the shards orbit while the buffs last");
            assertTrue(s.owner.hasPotionEffect(PotionEffectType.STRENGTH));
            s.ticks(50);
            assertTrue(entities(s, Display.class).isEmpty(), "the shards fade with the buffs");
            s.finishClean();
        }
    }

    // ================================================================== 자석술사 magnet

    private static int ironPieces(LivingEntity target) {
        int pieces = 0;
        for (ItemStack armor : target.getEquipment().getArmorContents()) {
            if (armor != null && armor.getType().name().startsWith("IRON_")) {
                pieces++;
            }
        }
        return pieces;
    }

    private static double polarityDamage(Scenario s, LivingEntity target, int charges) {
        return s.param("polarity_damage") * (1 + ironPieces(target) * s.param("iron_bonus") / 100)
                * (1 + charges * s.param("charge_bonus") / 100);
    }

    @Test
    void magnetRepelsTheConeAndIronMakesItStronger() {
        try (Scenario s = Scenario.ffa("magnet")) {
            s.enemy2.getEquipment().setArmorContents(new ItemStack[4]);
            assertEquals(4, ironPieces(s.enemy), "the kit is iron armor");
            assertEquals(0, ironPieces(s.enemy2));
            double enemyBefore = horizontalDistance(s.enemy.getLocation(), s.owner.getLocation());
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "극성 (N)");
            assertEquals(polarityDamage(s, s.enemy, 0), s.damageBy(s.owner, s.enemy), EPSILON, "iron: +bonus");
            assertEquals(polarityDamage(s, s.enemy2, 0), s.damageBy(s.owner, s.enemy2), EPSILON, "no iron");
            s.ticks(10);
            assertTrue(horizontalDistance(s.enemy.getLocation(), s.owner.getLocation()) > enemyBefore + 2,
                    "thrown away");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.ticks(40);
            assertTrue(entities(s, Display.class).isEmpty(), "the magnet fades");
            s.finishClean();
        }
    }

    @Test
    void magnetPushRespectsKnockbackResistanceButThePullDoesNot() {
        try (Scenario s = Scenario.ffa("magnet")) {
            AttributeInstance resistance = s.enemy.getAttribute(Attribute.KNOCKBACK_RESISTANCE);
            NamespacedKey immovable = new NamespacedKey("test", "immovable");
            resistance.addModifier(new AttributeModifier(immovable, 1.0, AttributeModifier.Operation.ADD_NUMBER));
            Location start = s.enemy.getLocation();
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "극성 (N)");
            s.ticks(10);
            assertTrue(s.damageTaken(s.enemy) > 0, "the immovable enemy is still hurt");
            assertTrue(horizontalDistance(s.enemy.getLocation(), start) < 0.5, "but not pushed");

            s.place(s.enemy, 0.5, 7.5, 180);
            s.readyActive();
            s.owner.setSneaking(true);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "극성 (S)");
            s.ticks(16);
            assertTrue(horizontalDistance(s.owner.getLocation(), s.enemy.getLocation()) < 3.5,
                    "the pull is crowd control and drags it anyway");
            s.owner.setSneaking(false);
            resistance.removeModifier(immovable);
            s.finishClean();
        }
    }

    @Test
    void magnetConeLeavesEnemiesBehindAlone() {
        try (Scenario s = Scenario.ffa("magnet")) {
            s.place(s.enemy2, 0.5, -4.5, 0);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "극성");
            assertTrue(s.damageTaken(s.enemy) > 0);
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON, "behind the magnet");
            s.finishClean();
        }
    }

    @Test
    void magnetCrouchingPullsEnemiesInFront() {
        try (Scenario s = Scenario.ffa("magnet")) {
            s.place(s.enemy, 0.5, 7.5, 180);
            s.owner.setSneaking(true);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "극성 (S)");
            s.ticks(16);
            double distance = horizontalDistance(s.owner.getLocation(), s.enemy.getLocation());
            assertTrue(distance < 3.5, "pulled in: " + distance);
            assertTrue(s.enemy.getLocation().getZ() > s.owner.getLocation().getZ(), "in front, not behind");
            assertEquals(polarityDamage(s, s.enemy, 0), s.damageBy(s.owner, s.enemy), EPSILON);
            s.owner.setSneaking(false);
            s.finishClean();
        }
    }

    @Test
    void magnetDeflectsRangedHitsAndChargesTheNextPolarity() {
        try (Scenario s = Scenario.builder("magnet").param("deflect_chance", 100).start()) {
            s.place(s.enemy, 0.5, 12.5, 180);
            s.ctx().damage(s.enemy, s.owner, 4);
            s.owner.setNoDamageTicks(0);
            s.owner.damage(4, DamageSource.builder(DamageType.ARROW).withCausingEntity(s.enemy)
                    .withDirectEntity(s.enemy).build());
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "both ranged hits slide off");
            s.place(s.enemy2, 0.5, 2.5, 180);
            s.melee(s.enemy2, s.owner, 2);
            assertTrue(s.damageTaken(s.owner) > 0, "melee is not deflected");

            s.place(s.enemy, 0.5, 4.5, 180);
            s.place(s.enemy2, 6.5, -6.5, 0);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "charged 극성");
            assertEquals(polarityDamage(s, s.enemy, 2), s.damageBy(s.owner, s.enemy), EPSILON, "two charges");
            s.clearHits();
            s.layout();
            s.place(s.enemy2, 6.5, -6.5, 0);
            s.readyActive();
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "plain 극성");
            assertEquals(polarityDamage(s, s.enemy, 0), s.damageBy(s.owner, s.enemy), EPSILON, "charges spent");
            s.finishClean();
        }
    }

    @Test
    void magnetDeflectsEveryHitOfAVolleyButShowsItCalmly() {
        try (Scenario s = Scenario.builder("magnet").param("deflect_chance", 100).start()) {
            s.place(s.enemy, 0.5, 12.5, 180);
            for (int i = 0; i < 10; i++) {
                s.ctx().damage(s.enemy, s.owner, 1);
            }
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "every hit slid off");
            assertEquals(2, entities(s, BlockDisplay.class).size(), "one deflection show (two crossed bars)");
            s.ticks(30);
            assertTrue(entities(s, Display.class).isEmpty());
            s.place(s.enemy, 0.5, 4.5, 180);
            s.place(s.enemy2, 6.5, -6.5, 0);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "fully charged 극성");
            assertEquals(polarityDamage(s, s.enemy, (int) s.param("charge_max")), s.damageBy(s.owner, s.enemy),
                    EPSILON, "every deflection still charged the field");
            s.finishClean();
        }
    }

    @Test
    void magnetStormDragsEnemiesInSmashesThemAndSlowsThem() {
        try (Scenario s = Scenario.ffa("magnet")) {
            s.place(s.enemy, 0.5, 7.5, 180);
            s.place(s.enemy2, 2.5, 8.5, 180);
            double before = horizontalDistance(s.enemy.getLocation(), s.owner.getLocation());
            s.assertSucceeded(s.ultimate(), "자기 폭풍");
            s.ticks(s.paramTicks("storm_pull") - 2);
            assertTrue(horizontalDistance(s.enemy.getLocation(), s.owner.getLocation()) < before - 2,
                    "dragged towards the magnet");
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "no damage before the crash");
            s.ticks(4);
            double max = s.param("storm_damage") + s.param("storm_crash_bonus");
            for (TestPlayer target : s.enemies()) {
                double damage = s.damageBy(s.owner, target);
                assertTrue(damage >= s.param("storm_damage") - EPSILON && damage <= max + EPSILON,
                        target.getName() + " smashed for " + damage);
                assertTrue(target.getAttribute(Attribute.MOVEMENT_SPEED).getModifiers().stream()
                        .anyMatch(modifier -> modifier.getAmount() < 0), target.getName() + " slowed");
            }
            assertEquals(0.5, s.owner.getLocation().getZ(), EPSILON, "the magnet itself does not move");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.ticks(s.paramTicks("storm_slow_duration") + 2);
            for (TestPlayer target : s.enemies()) {
                assertModifierFree(target, "magnet");
            }
            s.ticks(20);
            assertTrue(entities(s, Display.class).isEmpty(), "the storm is over");
            s.finishClean();
        }
    }

    @Test
    void magnetStormNeedsEnemiesInRange() {
        try (Scenario s = Scenario.ffa("magnet")) {
            s.place(s.enemy, 30.5, 0.5, 0);
            s.place(s.enemy2, -30.5, 0.5, 0);
            ActivationResult result = s.ultimate();
            assertFalse(result.succeeded());
            assertNotNull(result.reason());
            s.finishClean();
        }
    }

    // ================================================================== modifiers sanity

    @Test
    void sniperAimSlowIsAMultiplier() {
        try (Scenario s = Scenario.ffa("sniper")) {
            double speed = s.owner.getAttribute(Attribute.MOVEMENT_SPEED).getValue();
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "정조준 사격");
            assertEquals(speed * (1 - s.param("aim_slow") / 100),
                    s.owner.getAttribute(Attribute.MOVEMENT_SPEED).getValue(), EPSILON);
            for (AttributeModifier modifier : s.owner.getAttribute(Attribute.MOVEMENT_SPEED).getModifiers()) {
                assertEquals(AttributeModifier.Operation.MULTIPLY_SCALAR_1, modifier.getOperation());
            }
            s.ticks(s.paramTicks("aim_time") + 4);
            assertEquals(speed, s.owner.getAttribute(Attribute.MOVEMENT_SPEED).getValue(), EPSILON, "restored");
            s.finishClean();
        }
    }
}
