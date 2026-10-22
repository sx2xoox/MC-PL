package io.github.sx2xoox.mcpl.game.superpower.scenario;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import io.github.sx2xoox.mcpl.game.superpower.abilities.BuilderAbility;
import io.github.sx2xoox.mcpl.game.superpower.abilities.DimensionAbility;
import io.github.sx2xoox.mcpl.game.superpower.abilities.MonkAbility;
import io.github.sx2xoox.mcpl.game.superpower.ability.Ability;
import io.github.sx2xoox.mcpl.game.superpower.ability.ActivationResult;
import io.github.sx2xoox.mcpl.game.superpower.support.Scenario;
import io.github.sx2xoox.mcpl.game.superpower.support.TestPlayer;
import io.github.sx2xoox.mcpl.game.superpower.support.world.FlatWorld;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** 격투 · 공간 · 건축 계열: 무도가, 차원술사, 건축가. */
class MiscNewAbilitiesTest {

    private static final double EPSILON = 1.0E-6;

    /** Which skills hurt the aimed enemy in the default layout. */
    private record Expect(boolean activeHurts, boolean ultimateHurts) {
    }

    private static final Map<String, Expect> EXPECT = Map.of(
            "monk", new Expect(true, true),
            "dimension", new Expect(false, true),
            "builder", new Expect(false, false));

    static Stream<String> abilities() {
        return Stream.of("monk", "dimension", "builder");
    }

    private static double horizontalDistance(Location a, Location b) {
        return Math.hypot(a.getX() - b.getX(), a.getZ() - b.getZ());
    }

    /** The skill's display entities are all gone again. */
    private static void assertNoDisplays(Scenario s) {
        assertTrue(s.world.getEntities().stream().noneMatch(Display.class::isInstance),
                "displays left behind: " + s.world.getEntities());
    }

    private static long displays(Scenario s) {
        return s.world.getEntities().stream().filter(Display.class::isInstance).count();
    }

    /** No player stands inside a solid block. */
    private static void assertNobodyEntombed(Scenario s) {
        for (TestPlayer player : s.all()) {
            assertFalse(s.world.hasCollisionsIn(player.getBoundingBox().expand(-0.01)),
                    player.getName() + " was built into a block at " + player.getLocation());
        }
    }

    private static void assertNoModifiers(Player player, String what) {
        for (Attribute attribute : List.of(Attribute.MOVEMENT_SPEED, Attribute.JUMP_STRENGTH, Attribute.GRAVITY)) {
            AttributeInstance instance = player.getAttribute(attribute);
            for (AttributeModifier modifier : instance.getModifiers()) {
                fail(what + ": " + player.getName() + " kept " + modifier.getKey() + " on " + attribute.getKey());
            }
        }
    }

    /** A gambler owner with no luck of its own, so the borrowed ultimate alone decides what happens. */
    private static Scenario gambler() {
        return Scenario.builder("gambler").param("dodge_chance", 0).param("crit_chance", 0).start();
    }

    /** Runs {@code id}'s ultimate on a freshly bound instance, exactly like the gambler's jackpot does. */
    private static ActivationResult borrow(Scenario s, String id) {
        Ability borrowed = s.ctx().registry().create(id);
        borrowed.bind(s.owner, s.ctx(), s.ctx().paramsFor(id));
        return borrowed.onUltimate();
    }

    // ------------------------------------------------------------------ every ability of the group

    @Test
    void everyUltimateCanBeBorrowed() {
        assertTrue(MonkAbility.INFO.ultimateStandalone());
        assertTrue(DimensionAbility.INFO.ultimateStandalone());
        assertTrue(BuilderAbility.INFO.ultimateStandalone());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("abilities")
    void wholeMatch(String id) {
        try (Scenario s = Scenario.ffa(id)) {
            s.ticks(60);
            s.assertNoProblems();

            s.aimAt(s.owner, s.enemy);
            s.clearHits();
            s.assertSucceeded(s.active(), id + " active");
            s.ticks(100);
            if (EXPECT.get(id).activeHurts()) {
                assertTrue(s.damageTaken(s.enemy) > 0, id + " active hurts the aimed enemy");
            }
            assertEquals(0, s.damageTaken(s.owner), EPSILON, id + " active never hurts its owner: " + s.hits());

            s.layout();
            s.aimAt(s.owner, s.enemy);
            s.healAll();
            s.clearHits();
            s.assertSucceeded(s.ultimate(), id + " ultimate");
            s.ticks(300);
            if (EXPECT.get(id).ultimateHurts()) {
                assertTrue(s.damageTaken(s.enemy) > 0, id + " ultimate hurts the aimed enemy");
            }
            assertEquals(0, s.damageTaken(s.owner), EPSILON, id + " ultimate never hurts its owner: " + s.hits());
            // The dimension gate's exit outlives both skills on purpose (it waits to be used); nothing else may.
            assertEquals(id.equals("dimension") ? 10 : 0, displays(s), id + ": displays left: " + s.world.getEntities());
            assertEquals(0, s.h.hub.tempBlocks().size(), id + ": temporary blocks restored");
            s.finishClean();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("abilities")
    void ownerDiesDuringActive(String id) {
        try (Scenario s = Scenario.ffa(id)) {
            s.ticks(40);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), id + " active");
            s.ticks(3);
            assertOwnerDeathCleansUp(s);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("abilities")
    void ownerDiesDuringUltimate(String id) {
        try (Scenario s = Scenario.ffa(id)) {
            s.ticks(40);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), id + " ultimate");
            s.ticks(8);
            assertOwnerDeathCleansUp(s);
        }
    }

    private static void assertOwnerDeathCleansUp(Scenario s) {
        String id = s.abilityId;
        s.killOwner(s.enemy);
        s.ticks(1);
        for (Entity entity : s.world.getEntities()) {
            assertTrue(entity instanceof Player, id + ": the dead owner's " + entity.getType() + " outlived it");
        }
        s.clearHits();
        s.ticks(300);
        assertTrue(s.hits().stream().noneMatch(hit -> s.owner.equals(hit.causer())),
                id + ": the dead owner still dealt damage: " + s.hits());
        assertEquals(0, s.h.hub.tempBlocks().size(), id + ": temporary blocks expire after the owner's death");
        for (TestPlayer enemy : s.enemies()) {
            assertNoModifiers(enemy, id);
            assertFalse(s.ctx().isSilenced(enemy), id);
        }
        s.assertNoProblems();
        s.finishMatch();
        s.assertNoProblems();
        s.assertCleanWorld();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("abilities")
    void teamModeNeverHarmsTheAlly(String id) {
        try (Scenario s = Scenario.teams(id)) {
            s.ticks(40);
            s.crossfireAlly();
            s.aimAt(s.owner, s.enemy);
            Location allyStart = s.ally().getLocation();
            s.clearHits();
            s.assertSucceeded(s.active(), id + " active");
            AbilityLifecycleTest.watchAlly(s, allyStart, 100);

            s.layout();
            s.crossfireAlly();
            s.aimAt(s.owner, s.enemy);
            allyStart = s.ally().getLocation();
            s.assertSucceeded(s.ultimate(), id + " ultimate");
            AbilityLifecycleTest.watchAlly(s, allyStart, 200);
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("abilities")
    void borrowedUltimateWorksOnAGambler(String id) {
        try (Scenario s = gambler()) {
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(borrow(s, id), "borrowed " + id);
            boolean built = false;
            boolean hurt = false;
            for (int i = 0; i < 300; i++) {
                s.ticks(1);
                built |= s.h.hub.tempBlocks().size() > 0;
                hurt |= s.damageBy(s.owner, s.enemy) > 0;
            }
            assertTrue(id.equals("builder") ? built : hurt, "borrowed " + id + " did its thing");
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "the borrowed " + id + " never hurts the gambler");
            assertNoModifiers(s.owner, id);
            for (TestPlayer target : s.enemies()) {
                assertNoModifiers(target, id);
            }
            assertNoDisplays(s);
            assertEquals(0, s.h.hub.tempBlocks().size(), id + ": temporary blocks restored");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("abilities")
    void gamblerDyingDuringABorrowedUltimateLeavesNothingBehind(String id) {
        try (Scenario s = gambler()) {
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(borrow(s, id), "borrowed " + id);
            s.ticks(6);
            assertOwnerDeathCleansUp(s);
        }
    }

    // ------------------------------------------------------------------ 무도가 monk

    @Test
    void monkEveryThirdComboHitStunsAndHitsHarder() {
        try (Scenario s = Scenario.ffa("monk")) {
            s.melee(s.owner, s.enemy, 4);
            s.melee(s.owner, s.enemy, 4);
            assertEquals(8, s.damageBy(s.owner, s.enemy), EPSILON);
            assertTrue(s.enemy.getAttribute(Attribute.MOVEMENT_SPEED).getModifiers().isEmpty(), "not stunned yet");
            s.melee(s.owner, s.enemy, 4);
            assertEquals(12 + s.param("combo_bonus"), s.damageBy(s.owner, s.enemy), EPSILON, "the third hit lands harder");
            assertEquals(0, s.enemy.getAttribute(Attribute.MOVEMENT_SPEED).getValue(), EPSILON, "stunned");
            assertEquals(0, s.enemy.getAttribute(Attribute.JUMP_STRENGTH).getValue(), EPSILON, "cannot jump");
            s.ticks(s.paramTicks("stun_duration") + 2);
            assertNoModifiers(s.enemy, "the stun wears off");

            // Switching targets breaks the combo.
            s.clearHits();
            s.melee(s.owner, s.enemy, 4);
            s.melee(s.owner, s.enemy2, 4);
            s.melee(s.owner, s.enemy, 4);
            assertEquals(8, s.damageBy(s.owner, s.enemy), EPSILON, "no bonus after a target switch");
            assertNoModifiers(s.enemy, "no stun after a target switch");
            // And so does waiting too long.
            s.ticks(s.paramTicks("combo_window") + 2);
            s.melee(s.owner, s.enemy, 4);
            assertEquals(12, s.damageBy(s.owner, s.enemy), EPSILON, "the combo lapsed");
            assertNoModifiers(s.enemy, "the combo lapsed");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.finishClean();
        }
    }

    @Test
    void monkPalmBlastsTheFirstEnemyFarAway() {
        try (Scenario s = Scenario.ffa("monk")) {
            s.place(s.enemy, 0.5, 8.5, 180);
            s.place(s.enemy2, 10.5, 0.5, 270);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "장풍");
            assertTrue(displays(s) >= 6, "the wind hand flies out");
            s.ticks(30);
            assertEquals(s.param("palm_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "hit once");
            assertTrue(s.enemy.getLocation().getZ() > 8.5 + s.param("palm_push") * 0.5,
                    "blown away: " + s.enemy.getLocation());
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON, "only the first enemy is hit");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.ticks(20);
            assertNoDisplays(s);
            s.finishClean();
        }
    }

    @Test
    void monkPalmBreaksOnWalls() {
        try (Scenario s = Scenario.ffa("monk")) {
            s.place(s.enemy, 0.5, 8.5, 180);
            s.aimAt(s.owner, s.enemy);
            s.world.wallAlongX(-3, 3, 5, 3, Material.STONE);
            s.assertSucceeded(s.active(), "장풍");
            s.ticks(30);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "the wall took the blast");
            assertNoDisplays(s);
            s.world.wallAlongX(-3, 3, 5, 3, Material.AIR);
            s.finishClean();
        }
    }

    @Test
    void monkPalmFliesThroughAlliesToTheEnemy() {
        try (Scenario s = Scenario.teams("monk")) {
            s.place(s.enemy, 0.5, 8.5, 180);
            s.place(s.enemy2, 10.5, 0.5, 270);
            s.place(s.ally(), 0.5, 3.5, 180);
            s.aimAt(s.owner, s.enemy);
            Location allyAt = s.ally().getLocation();
            s.assertSucceeded(s.active(), "장풍");
            s.ticks(30);
            assertEquals(s.param("palm_damage"), s.damageBy(s.owner, s.enemy), EPSILON);
            assertEquals(0, s.damageTaken(s.ally()), EPSILON);
            assertTrue(s.ally().getLocation().distance(allyAt) < 0.1, "the ally is not pushed");
            s.finishClean();
        }
    }

    @Test
    void monkHundredFistsClosesInBarragesAndLaunches() {
        try (Scenario s = Scenario.ffa("monk")) {
            s.place(s.enemy, 0.5, 9.5, 180);
            s.place(s.enemy2, 12.5, 0.5, 270);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "백열권");
            assertTrue(horizontalDistance(s.owner.getLocation(), s.enemy.getLocation()) < 3,
                    "stepped in front of the enemy: " + s.owner.getLocation());
            assertTrue(s.owner.getLocation().getZ() < s.enemy.getLocation().getZ(), "face to face");
            assertFalse(s.world.hasCollisionsIn(s.owner.getBoundingBox().expand(-0.01)));
            assertEquals(0, s.enemy.getAttribute(Attribute.MOVEMENT_SPEED).getValue(), EPSILON, "held in place");

            s.ticks(s.paramTicks("ult_duration") + 8);
            int hits = (int) s.param("ult_hits");
            assertEquals(hits + 1, s.hitsBy(s.owner, s.enemy).size(), "every punch and the finisher land");
            assertEquals(hits * s.param("ult_hit_damage") + s.param("ult_final_damage"),
                    s.damageBy(s.owner, s.enemy), EPSILON);
            assertNoModifiers(s.enemy, "let go after the finisher");
            s.ticks(10);
            assertTrue(s.enemy.getLocation().getZ() > 9.5 + s.param("ult_launch") * 0.4,
                    "launched away: " + s.enemy.getLocation());
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON);
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.ticks(30);
            assertNoDisplays(s);
            s.finishClean();
        }
    }

    @Test
    void monkHundredFistsLetsGoOfAVictimThatDies() {
        try (Scenario s = Scenario.ffa("monk")) {
            s.place(s.enemy2, 12.5, 0.5, 270);
            s.aimAt(s.owner, s.enemy);
            s.enemy.setHealth(3 * s.param("ult_hit_damage"));
            s.assertSucceeded(s.ultimate(), "백열권");
            s.ticks(s.paramTicks("ult_duration") + 10);
            assertFalse(s.session.isAlive(s.enemy), "the barrage finished the enemy");
            s.respawn(s.enemy);
            assertEquals(3, s.hitsBy(s.owner, s.enemy).size(), "no punch lands on a corpse");
            assertNoModifiers(s.enemy, "the dead enemy is let go");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.ticks(30);
            assertNoDisplays(s);
            s.assertNoProblems();
            s.finishMatch();
            s.assertNoProblems();
            s.assertCleanWorld();
        }
    }

    @Test
    void monkHundredFistsBreaksOffWhenTheTargetIsCarriedAway() {
        try (Scenario s = Scenario.ffa("monk")) {
            s.place(s.enemy2, 12.5, 0.5, 270);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "백열권");
            s.ticks(12);
            int landed = s.hitsBy(s.owner, s.enemy).size();
            assertTrue(landed > 0, "the barrage started");
            s.place(s.enemy, 0.5, 18.5, 180);
            s.ticks(s.paramTicks("ult_duration") + 10);
            assertEquals(landed, s.hitsBy(s.owner, s.enemy).size(), "no punches from afar, no finisher");
            assertNoModifiers(s.enemy, "released at once");
            s.ticks(30);
            assertNoDisplays(s);
            s.finishClean();
        }
    }

    @Test
    void monkHundredFistsNeedsAnEnemyInRange() {
        try (Scenario s = Scenario.ffa("monk")) {
            s.place(s.enemy, 0.5, 30.5, 180);
            s.place(s.enemy2, 30.5, 0.5, 270);
            assertFalse(s.ultimate().succeeded(), "nobody within " + s.param("ult_range") + " blocks");
            assertEquals(100, s.participant(s.owner).gauge(), EPSILON, "the gauge is kept");
            s.finishClean();
        }
    }

    // ------------------------------------------------------------------ 차원술사 dimension

    @Test
    void dimensionGateMarksAnExitThenCarriesTheOwnerBack() {
        try (Scenario s = Scenario.ffa("dimension")) {
            s.place(s.enemy, 8.5, 8.5, 180);
            s.place(s.enemy2, -8.5, 8.5, 180);
            s.assertSucceeded(s.active(), "차원문 (출구)");
            assertTrue(displays(s) >= 10, "the exit gate opens");
            assertEquals(s.param("mark_cooldown"), s.ctx().cooldownRemaining(s.owner), 0.06,
                    "placing the exit has a short cooldown");

            s.place(s.owner, 0.5, -12.5, 180);
            s.ticks(s.paramTicks("mark_cooldown") + 1);
            s.assertSucceeded(s.active(), "차원문 (이동)");
            Location at = s.owner.getLocation();
            assertTrue(horizontalDistance(at, new Location(s.world, 0.5, FlatWorld.SURFACE, 0.5)) < 0.1,
                    "back at the exit: " + at);
            assertEquals(FlatWorld.SURFACE, at.getY(), EPSILON);
            assertEquals(180, at.getYaw(), 1.0E-3, "keeps looking the same way");
            assertEquals(DimensionAbility.INFO.params().get("cooldown"), s.ctx().cooldownRemaining(s.owner), 0.06,
                    "travelling has the full cooldown");

            s.ticks(s.paramTicks("gate_open") + 20);
            assertNoDisplays(s);
            assertEquals(0, s.damageTaken(s.owner), EPSILON);

            // The exit was used up: the next use marks a new one.
            s.readyActive();
            s.place(s.owner, 4.5, 4.5, 0);
            s.assertSucceeded(s.active(), "차원문 (새 출구)");
            assertTrue(horizontalDistance(s.owner.getLocation(), new Location(s.world, 4.5, 64, 4.5)) < 0.1,
                    "placing does not move the owner");
            assertEquals(s.param("mark_cooldown"), s.ctx().cooldownRemaining(s.owner), 0.06);
            s.finishClean();
        }
    }

    @Test
    void dimensionSneakingMovesTheExit() {
        try (Scenario s = Scenario.ffa("dimension")) {
            s.place(s.enemy, 8.5, 8.5, 180);
            s.place(s.enemy2, -8.5, 8.5, 180);
            s.assertSucceeded(s.active(), "first exit");
            s.place(s.owner, 4.5, -4.5, 0);
            s.owner.setSneaking(true);
            s.readyActive();
            s.assertSucceeded(s.active(), "moved exit");
            s.owner.setSneaking(false);
            s.ticks(10);
            assertTrue(displays(s) <= 10, "the old exit closed: " + displays(s));
            s.place(s.owner, -6.5, -6.5, 0);
            s.readyActive();
            s.assertSucceeded(s.active(), "travel");
            assertTrue(horizontalDistance(s.owner.getLocation(), new Location(s.world, 4.5, 64, -4.5)) < 0.1,
                    "arrived at the moved exit: " + s.owner.getLocation());
            s.finishClean();
        }
    }

    @Test
    void dimensionUnusedExitClosesByItself() {
        try (Scenario s = Scenario.builder("dimension").param("gate_duration", 2).start()) {
            s.place(s.enemy, 8.5, 8.5, 180);
            s.place(s.enemy2, -8.5, 8.5, 180);
            s.assertSucceeded(s.active(), "exit");
            s.ticks(s.paramTicks("gate_duration") + 12);
            assertNoDisplays(s);
            s.place(s.owner, 4.5, -4.5, 0);
            s.assertSucceeded(s.active(), "a new exit instead of a trip to the closed one");
            assertTrue(horizontalDistance(s.owner.getLocation(), new Location(s.world, 4.5, 64, -4.5)) < 0.1);
            assertEquals(s.param("mark_cooldown"), s.ctx().cooldownRemaining(s.owner), 0.06);
            s.finishClean();
        }
    }

    @Test
    void dimensionArrivalHurtsAndThrowsEnemiesAtTheExit() {
        try (Scenario s = Scenario.ffa("dimension")) {
            s.place(s.enemy2, -8.5, 12.5, 180);
            s.place(s.enemy, 8.5, 8.5, 180);
            s.assertSucceeded(s.active(), "exit");
            s.place(s.owner, 0.5, -12.5, 180);
            s.place(s.enemy, 1.5, 1.5, 180);
            s.ticks(s.paramTicks("mark_cooldown") + 1);
            s.assertSucceeded(s.active(), "travel");
            assertEquals(s.param("burst_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "the arrival tears into it");
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON, "far enemies are safe");
            s.ticks(10);
            assertTrue(horizontalDistance(s.enemy.getLocation(), s.owner.getLocation()) > 2,
                    "thrown away from the exit: " + s.enemy.getLocation());
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.finishClean();
        }
    }

    @Test
    void dimensionGateRefusesFarOrBlockedExits() {
        try (Scenario s = Scenario.builder("dimension").param("gate_range", 10).start()) {
            s.place(s.enemy, 8.5, 8.5, 180);
            s.place(s.enemy2, -8.5, 8.5, 180);
            s.assertSucceeded(s.active(), "exit");
            s.ticks(s.paramTicks("mark_cooldown") + 1);
            s.place(s.owner, 0.5, -14.5, 0);
            assertFalse(s.active().succeeded(), "the exit is out of range");
            assertEquals(0, s.ctx().cooldownRemaining(s.owner), EPSILON, "a refused travel costs nothing");

            s.place(s.owner, 0.5, -6.5, 0);
            s.world.setBlock(0, FlatWorld.FLOOR_Y + 1, 0, Material.STONE);
            assertFalse(s.active().succeeded(), "the exit is walled in");
            s.world.setBlock(0, FlatWorld.FLOOR_Y + 1, 0, Material.AIR);
            s.assertSucceeded(s.active(), "the exit is free again");

            // No exit can be opened in mid-air.
            s.readyActive();
            s.place(s.owner, 4.5, FlatWorld.SURFACE + 6, 4.5, 0, 0);
            assertFalse(s.active().succeeded(), "no ground to stand a gate on");
            s.tickUntilGrounded(s.owner, 200);
            s.finishClean();
        }
    }

    @Test
    void dimensionEntranceCarriesAlliesButNotEnemies() {
        try (Scenario s = Scenario.teams("dimension")) {
            s.place(s.enemy, 8.5, 8.5, 180);
            s.place(s.enemy2, -8.5, 8.5, 180);
            s.place(s.ally(), 3.5, -10.5, 0);
            s.assertSucceeded(s.active(), "exit");
            s.place(s.owner, 0.5, -10.5, 0);
            s.ticks(s.paramTicks("mark_cooldown") + 1);
            s.assertSucceeded(s.active(), "travel");

            s.place(s.enemy, 0.5, -10.5, 0);
            s.ticks(3);
            assertEquals(-10.5, s.enemy.getLocation().getZ(), 0.1, "enemies cannot use the gate");
            s.place(s.enemy, 8.5, 8.5, 180);

            s.place(s.ally(), 0.5, -10.5, 0);
            s.ticks(3);
            assertTrue(horizontalDistance(s.ally().getLocation(), new Location(s.world, 0.5, 64, 0.5)) < 2,
                    "the ally followed through the gate: " + s.ally().getLocation());
            assertFalse(s.world.hasCollisionsIn(s.ally().getBoundingBox().expand(-0.01)));
            assertEquals(0, s.damageTaken(s.ally()), EPSILON);

            s.ticks(s.paramTicks("gate_open") + 20);
            assertNoDisplays(s);
            s.place(s.ally(), 0.5, -10.5, 0);
            s.ticks(3);
            assertEquals(-10.5, s.ally().getLocation().getZ(), 0.1, "the entrance closed");
            s.finishClean();
        }
    }

    @Test
    void dimensionSafetyNetCatchesOneDeadlyFall() {
        try (Scenario s = Scenario.ffa("dimension")) {
            s.place(s.enemy, 8.5, 12.5, 180);
            s.ticks(20);
            // A survivable fall is left alone.
            s.place(s.owner, 6.5, FlatWorld.SURFACE + 8, 6.5, 0, 0);
            s.tickUntilGrounded(s.owner, 200);
            s.ticks(2);
            assertEquals(5, s.damageTaken(s.owner), EPSILON, "an 8 block drop hurts as usual");
            assertTrue(horizontalDistance(s.owner.getLocation(), new Location(s.world, 6.5, 64, 6.5)) < 0.1);

            s.healAll();
            s.clearHits();
            s.place(s.owner, 0.5, 0.5, 0);
            s.ticks(20);
            s.place(s.owner, 6.5, FlatWorld.SURFACE + 40, 6.5, 0, 0);
            s.h.tickUntil(() -> s.owner.getLocation().getY() < FlatWorld.SURFACE + 0.5, 200, "the fall");
            s.ticks(3);
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "the deadly fall was folded away: " + s.hits());
            assertTrue(horizontalDistance(s.owner.getLocation(), new Location(s.world, 0.5, 64, 0.5)) < 0.1,
                    "back on the last safe ground: " + s.owner.getLocation());
            assertTrue(s.session.isAlive(s.owner));

            // Once per match.
            s.place(s.owner, 6.5, FlatWorld.SURFACE + 40, 6.5, 0, 0);
            s.h.tickUntil(() -> !s.session.isAlive(s.owner), 200, "the second fall");
            s.respawn(s.owner);
            s.finishMatch();
            s.assertNoProblems();
            s.assertCleanWorld();
        }
    }

    @Test
    void dimensionSafetyNetCountsAbsorptionHeartsOnlyOnce() {
        try (Scenario s = Scenario.ffa("dimension")) {
            s.place(s.enemy, 8.5, 12.5, 180);
            s.ticks(20);
            // Absorption hearts, as from a golden apple.
            NamespacedKey apple = new NamespacedKey("test", "apple");
            s.owner.getAttribute(Attribute.MAX_ABSORPTION).addModifier(new AttributeModifier(apple, 4,
                    AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.ANY));
            s.owner.setHealth(10);
            s.owner.setAbsorptionAmount(4);
            assertEquals(4, s.owner.getAbsorptionAmount(), EPSILON);
            // About 14 fall damage: the absorption hearts soak 4, the remaining 10 or more would kill the owner.
            s.place(s.owner, 6.5, FlatWorld.SURFACE + 17, 6.5, 0, 0);
            s.h.tickUntil(() -> s.owner.getLocation().getY() < FlatWorld.SURFACE + 0.5, 200, "the fall");
            s.ticks(3);
            assertTrue(s.session.isAlive(s.owner), "the net caught a fall that only absorption made look survivable");
            assertEquals(0, s.damageTaken(s.owner), EPSILON, s.hits().toString());
            assertTrue(horizontalDistance(s.owner.getLocation(), new Location(s.world, 0.5, 64, 0.5)) < 0.1,
                    "back on the last safe ground: " + s.owner.getLocation());
            s.owner.getAttribute(Attribute.MAX_ABSORPTION).removeModifier(apple);
            s.finishClean();
        }
    }

    @Test
    void dimensionSafetyNetCatchesTheVoidAndPrefersTheExitGate() {
        try (Scenario s = Scenario.builder("dimension").param("rescue_charges", 2).start()) {
            s.place(s.enemy, 8.5, 12.5, 180);
            s.ticks(20);
            s.owner.damage(4, DamageSource.builder(DamageType.OUT_OF_WORLD).build());
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "the void cannot touch it");

            // Below the bottom of the world it is pulled back before the void hurts, to its exit gate if it has one.
            s.place(s.owner, -4.5, -4.5, 0);
            s.assertSucceeded(s.active(), "exit");
            s.place(s.owner, 0.5, 0.5, 0);
            s.ticks(20);
            s.world.physics().setEnabled(false);
            s.place(s.owner, 6.5, s.world.getMinHeight() - 6, 6.5, 0, 0);
            s.ticks(2);
            s.world.physics().setEnabled(true);
            assertTrue(horizontalDistance(s.owner.getLocation(), new Location(s.world, -4.5, 64, -4.5)) < 0.1,
                    "rescued to the exit gate: " + s.owner.getLocation());
            assertEquals(FlatWorld.SURFACE, s.owner.getLocation().getY(), EPSILON);
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.finishClean();
        }
    }

    @Test
    void dimensionCollapseSwapsTheEnemiesInRange() {
        try (Scenario s = Scenario.ffa("dimension")) {
            Location first = s.enemy.getLocation();
            Location second = s.enemy2.getLocation();
            Location home = s.owner.getLocation();
            s.assertSucceeded(s.ultimate(), "차원 붕괴");
            assertTrue(displays(s) >= 2, "the enemies are marked");
            assertTrue(s.enemy.getLocation().distance(first) < 0.1, "nothing moves during the telegraph");
            s.ticks(s.paramTicks("ult_delay") + 2);
            assertTrue(horizontalDistance(s.enemy.getLocation(), second) < 0.1, "swapped: " + s.enemy.getLocation());
            assertTrue(horizontalDistance(s.enemy2.getLocation(), first) < 0.1, "swapped: " + s.enemy2.getLocation());
            for (TestPlayer target : s.enemies()) {
                assertEquals(s.param("ult_damage"), s.damageBy(s.owner, target), EPSILON, target.getName());
                assertTrue(target.hasPotionEffect(PotionEffectType.NAUSEA), target.getName() + " is dizzy");
                assertTrue(target.hasPotionEffect(PotionEffectType.SLOWNESS), target.getName() + " is slowed");
            }
            assertTrue(s.owner.getLocation().distance(home) < 0.1, "the owner stays");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.ticks(40);
            assertNoDisplays(s);
            s.finishClean();
        }
    }

    @Test
    void dimensionCollapseNeverSwapsAnEnemyIntoTheAir() {
        try (Scenario s = Scenario.ffa("dimension")) {
            // enemy2 is high in the air (leaping, flying...): whoever takes its place lands on the ground below.
            s.world.physics().setEnabled(false);
            s.place(s.enemy2, 2.0, FlatWorld.SURFACE + 6, 6.5, 180, 0);
            Location first = s.enemy.getLocation();
            s.assertSucceeded(s.ultimate(), "차원 붕괴");
            s.ticks(s.paramTicks("ult_delay") + 2);
            assertTrue(horizontalDistance(s.enemy.getLocation(), new Location(s.world, 2.0, 64, 6.5)) < 0.1,
                    "swapped to enemy2's spot: " + s.enemy.getLocation());
            assertEquals(FlatWorld.SURFACE, s.enemy.getLocation().getY(), EPSILON, "but on the ground under it");
            assertTrue(horizontalDistance(s.enemy2.getLocation(), first) < 0.1, "enemy2 took the enemy's spot");
            assertEquals(FlatWorld.SURFACE, s.enemy2.getLocation().getY(), EPSILON);
            s.world.physics().setEnabled(true);
            s.ticks(40);
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.finishClean();
        }
    }

    @Test
    void dimensionCollapseThrowsALoneEnemyToARandomSpot() {
        try (Scenario s = Scenario.ffa("dimension")) {
            s.place(s.enemy2, 0.5, 40.5, 180);
            Location start = s.enemy.getLocation();
            s.assertSucceeded(s.ultimate(), "차원 붕괴");
            s.ticks(s.paramTicks("ult_delay") + 2);
            Location at = s.enemy.getLocation();
            assertTrue(at.distance(start) >= Math.min(4, s.param("ult_radius") / 2) - 0.01, "thrown elsewhere: " + at);
            assertTrue(horizontalDistance(at, s.owner.getLocation()) <= s.param("ult_radius") + 0.5,
                    "within the collapse: " + at);
            assertEquals(FlatWorld.SURFACE, at.getY(), EPSILON, "on the ground");
            assertFalse(s.world.hasCollisionsIn(s.enemy.getBoundingBox().expand(-0.01)));
            assertEquals(s.param("ult_damage"), s.damageBy(s.owner, s.enemy), EPSILON);
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON, "out of range");
            assertEquals(40.5, s.enemy2.getLocation().getZ(), 0.1);
            s.finishClean();
        }
    }

    @Test
    void dimensionCollapseNeedsAnEnemyInRange() {
        try (Scenario s = Scenario.ffa("dimension")) {
            s.place(s.enemy, 0.5, 30.5, 180);
            s.place(s.enemy2, 30.5, 0.5, 270);
            assertFalse(s.ultimate().succeeded());
            assertEquals(100, s.participant(s.owner).gauge(), EPSILON, "the gauge is kept");
            s.finishClean();
        }
    }

    // ------------------------------------------------------------------ 건축가 builder

    @Test
    void builderWallRisesInFrontAndCrumblesAway() {
        try (Scenario s = Scenario.ffa("builder")) {
            s.assertSucceeded(s.active(), "즉석 방벽");
            assertTrue(displays(s) > 0, "bricks fly out");
            s.ticks(8);
            for (int x = -1; x <= 1; x++) {
                for (int y = 0; y < 2; y++) {
                    assertEquals(Material.BRICKS, s.world.getBlockAt(x, FlatWorld.FLOOR_Y + 1 + y, 2).getType(),
                            "wall block at " + x + "," + y);
                }
            }
            assertEquals(6, s.h.hub.tempBlocks().size(), "a 3 x 2 wall of temporary blocks");
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "nobody stood in the way");
            assertNoDisplays(s);
            s.ticks(s.paramTicks("wall_duration") + 5);
            assertEquals(0, s.h.hub.tempBlocks().size(), "the wall is gone");
            assertEquals(Material.AIR, s.world.getBlockAt(0, FlatWorld.FLOOR_Y + 1, 2).getType());
            s.finishClean();
        }
    }

    @Test
    void builderWallShovesAnEnemyStandingInItAndNeverEntombsAnyone() {
        try (Scenario s = Scenario.ffa("builder")) {
            s.place(s.enemy, 0.5, 2.5, 180);
            s.assertSucceeded(s.active(), "즉석 방벽");
            for (int i = 0; i < 10; i++) {
                s.ticks(1);
                assertNobodyEntombed(s);
            }
            assertEquals(s.param("wall_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "shoved");
            assertTrue(s.enemy.getLocation().getZ() > 3, "pushed out beyond the wall: " + s.enemy.getLocation());
            assertEquals(Material.BRICKS, s.world.getBlockAt(-1, FlatWorld.FLOOR_Y + 1, 2).getType());
            assertEquals(Material.BRICKS, s.world.getBlockAt(1, FlatWorld.FLOOR_Y + 1, 2).getType());
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.ticks(s.paramTicks("wall_duration") + 5);
            s.finishClean();
        }
    }

    @Test
    void builderWallFollowsTheTerrainAndNeedsRoom() {
        try (Scenario s = Scenario.ffa("builder")) {
            s.place(s.enemy, 8.5, 8.5, 180);
            // A step in front: the wall stands on top of it.
            s.world.wallAlongX(-3, 3, 2, 1, Material.STONE);
            s.assertSucceeded(s.active(), "즉석 방벽 on a step");
            s.ticks(8);
            int y = FlatWorld.FLOOR_Y + 1;
            assertEquals(Material.BRICKS, s.world.getBlockAt(0, y + 1, 2).getType(), "on top of the step");
            assertEquals(Material.BRICKS, s.world.getBlockAt(0, y + 2, 2).getType());
            s.ticks(s.paramTicks("wall_duration") + 5);
            assertEquals(Material.AIR, s.world.getBlockAt(0, y + 1, 2).getType(), "restored");
            s.world.wallAlongX(-3, 3, 2, 1, Material.AIR);

            // A cliff face in front: no room for a wall, nothing is used up.
            s.readyActive();
            s.world.wallAlongX(-3, 3, 2, 4, Material.STONE);
            assertFalse(s.active().succeeded(), "no room");
            assertEquals(0, s.ctx().cooldownRemaining(s.owner), EPSILON);
            assertEquals(0, s.h.hub.tempBlocks().size());
            s.world.wallAlongX(-3, 3, 2, 4, Material.AIR);
            s.finishClean();
        }
    }

    @Test
    void builderWallStopsArrows() {
        try (Scenario s = Scenario.ffa("builder")) {
            s.place(s.enemy, 0.5, 7.5, 180);
            s.assertSucceeded(s.active(), "즉석 방벽");
            s.ticks(8);
            Arrow arrow = s.world.spawnArrow(s.enemy.getEyeLocation().add(0, 0, -0.6), new Vector(0, 0, -1), 1.5f, 0);
            arrow.setShooter(s.enemy);
            s.ticks(20);
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "the wall caught the arrow: " + s.hits());
            assertFalse(s.world.physics().arrowImpacts().isEmpty(), "the arrow stuck in the wall");
            arrow.remove();
            s.ticks(s.paramTicks("wall_duration"));
            s.finishClean();
        }
    }

    @Test
    void builderStandsFirmOnlyOnTheGround() {
        try (Scenario s = Scenario.ffa("builder")) {
            s.melee(s.enemy, s.owner, 10);
            assertEquals(10 * (1 - s.param("stand_reduction") / 100), s.damageTaken(s.owner), EPSILON,
                    "less damage on firm ground");
            s.clearHits();
            s.place(s.owner, 0.5, FlatWorld.SURFACE + 2, 0.5, 0, 0);
            s.melee(s.enemy, s.owner, 10);
            assertEquals(10, s.damageTaken(s.owner), EPSILON, "full damage in the air");
            s.tickUntilGrounded(s.owner, 200);
            s.finishClean();
        }
    }

    @Test
    void builderFortressRisesAroundTheOwnerHealsAndComesDown() {
        try (Scenario s = Scenario.ffa("builder")) {
            s.place(s.enemy, 0.5, 2.5, 180);
            s.owner.setHealth(8);
            s.assertSucceeded(s.ultimate(), "요새 건설");
            assertEquals(s.param("ult_shove_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "the site is cleared");
            for (int i = 0; i < 30; i++) {
                s.ticks(1);
                assertNobodyEntombed(s);
            }
            int size = (int) s.param("ult_size");
            Location enemyAt = s.enemy.getLocation();
            assertTrue(Math.max(Math.abs(enemyAt.getX() - 0.5), Math.abs(enemyAt.getZ() - 0.5)) > size - 0.5,
                    "the enemy was pushed out of the fortress: " + enemyAt);
            int y = FlatWorld.FLOOR_Y + 1;
            assertEquals(Material.BRICKS, s.world.getBlockAt(size, y, 1).getType(), "a side wall");
            assertEquals(Material.BRICKS, s.world.getBlockAt(-size, y + 1, -1).getType(), "two blocks high");
            assertEquals(Material.OAK_LOG, s.world.getBlockAt(size, y + 2, size).getType(), "a corner tower");
            assertEquals(Material.OAK_SLAB, s.world.getBlockAt(size, y + 3, size).getType(), "capped");
            assertEquals(Material.AIR, s.world.getBlockAt(0, y, size).getType(), "the front gate is open");
            assertEquals(Material.AIR, s.world.getBlockAt(0, y + 1, -size).getType(), "the back gate is open");
            assertEquals(Material.OAK_PLANKS, s.world.getBlockAt(0, y + 2, size).getType(), "a lintel over the gate");
            assertEquals(Material.OAK_SLAB, s.world.getBlockAt(size - 1, y + 2, size - 1).getType(), "a corner roof");
            assertEquals(4, s.world.getEntities().stream().filter(ItemDisplay.class::isInstance).count(),
                    "a banner on every tower");

            s.ticks(s.paramTicks("ult_duration"));
            assertTrue(s.owner.getHealth() >= 8 + s.param("ult_heal") * (s.param("ult_duration") - 1.5),
                    "healed inside: " + s.owner.getHealth());
            assertEquals(0, s.h.hub.tempBlocks().size(), "the fortress comes down");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.ticks(10);
            assertNoDisplays(s);
            s.finishClean();
        }
    }

    @Test
    void builderFortressHealsAlliesInsideButNotEnemies() {
        try (Scenario s = Scenario.teams("builder")) {
            s.place(s.ally(), -1.5, -1.5, 0);
            s.place(s.enemy, 0.5, 12.5, 180);
            s.ally().setHealth(10);
            s.assertSucceeded(s.ultimate(), "요새 건설");
            s.ticks(30);
            s.enemy.setHealth(10);
            s.place(s.enemy, 1.5, 1.5, 180);
            s.ticks(s.paramTicks("ult_duration") - 40);
            assertTrue(s.ally().getHealth() > 14, "the ally recovers inside: " + s.ally().getHealth());
            assertEquals(10, s.enemy.getHealth(), EPSILON, "an enemy that walked in does not");
            s.ticks(20);
            s.finishClean();
        }
    }

    @Test
    void builderFortressCannotBeBuiltInMidAir() {
        try (Scenario s = Scenario.ffa("builder")) {
            s.place(s.owner, 0.5, FlatWorld.SURFACE + 8, 0.5, 0, 0);
            assertFalse(s.ultimate().succeeded());
            assertEquals(0, s.h.hub.tempBlocks().size());
            s.tickUntilGrounded(s.owner, 200);
            s.finishClean();
        }
    }
}
