package io.github.sx2xoox.mcpl.game.superpower.scenario;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import io.github.sx2xoox.mcpl.core.config.BlockDamageScope;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Motion;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Spots;
import io.github.sx2xoox.mcpl.game.superpower.ability.Ability;
import io.github.sx2xoox.mcpl.game.superpower.ability.ActivationResult;
import io.github.sx2xoox.mcpl.game.superpower.support.Scenario;
import io.github.sx2xoox.mcpl.game.superpower.support.TestPlayer;
import io.github.sx2xoox.mcpl.game.superpower.support.world.FlatWorld;
import org.bukkit.Input;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 하늘 · 땅 계열 (new): 블록 퍼즐러, 기린, 아틀라스, 폴터가이스트, 요르문간드. Each ability through a whole match, the owner
 * dying in the middle of each skill, team mode with an ally in the line of fire, the Gambler borrowing the standalone
 * ultimates, and the mechanics of every passive, active and ultimate.
 */
class SkyEarthNewAbilitiesTest {

    static {
        // AbilityLifecycleTest (whose watchAlly is used below) lists the abilities in its static initializer with a
        // harness of its own, which MockBukkit refuses while a scenario of this class is running.
        AbilityLifecycleTest.abilities();
    }

    private static final double EPSILON = 1.0E-6;
    private static final int Y = (int) FlatWorld.SURFACE;

    static Stream<String> abilities() {
        return Stream.of("blockfall", "qilin", "atlas", "poltergeist", "jormungandr");
    }

    /** The ultimates the gambler may borrow (the qilin's cloud and the atlas's sky rely on hooks and listeners). */
    static Stream<String> standalone() {
        return Stream.of("blockfall", "poltergeist", "jormungandr");
    }

    static <T extends Entity> List<T> entities(Scenario s, Class<T> type) {
        return s.world.getEntities().stream().filter(type::isInstance).map(type::cast).toList();
    }

    static void assertNoDisplays(Scenario s, String what) {
        assertTrue(entities(s, Display.class).isEmpty(), what + ": every display is gone: "
                + entities(s, Display.class));
    }

    /** No attribute modifier but the scenario's own max health bonus. */
    static void assertModifierFree(Player player, String what) {
        for (Attribute attribute : Registry.ATTRIBUTE) {
            AttributeInstance instance = player.getAttribute(attribute);
            if (instance == null) {
                continue;
            }
            instance.getModifiers().stream()
                    .filter(modifier -> !modifier.getKey().equals(Scenario.TANK))
                    .findFirst()
                    .ifPresent(modifier -> fail(what + ": " + player.getName() + " kept " + modifier.getKey()
                            + " on " + attribute.getKey()));
        }
    }

    static void assertUnrotated(Entity display, String what) {
        assertEquals(0, display.getLocation().getYaw(), 1.0E-3, what + ": display yaw");
        assertEquals(0, display.getLocation().getPitch(), 1.0E-3, what + ": display pitch");
    }

    /** A wall of stone along x at block row {@code z}, {@code height} blocks tall, from {@code x1} to {@code x2}. */
    static void wall(Scenario s, int x1, int x2, int z, int height) {
        s.world.wallAlongX(x1, x2, z, height, Material.STONE);
    }

    static void clearWall(Scenario s, int x1, int x2, int z, int height) {
        s.world.wallAlongX(x1, x2, z, height, Material.AIR);
    }

    /** Puts up the scenery an ability's sweep needs (the poltergeist's uproar only hits enemies near cover). */
    static void scenery(Scenario s) {
        if (s.abilityId.equals("poltergeist")) {
            wall(s, -3, 4, 6, 2);
        }
    }

    static void clearScenery(Scenario s) {
        if (s.abilityId.equals("poltergeist")) {
            clearWall(s, -3, 4, 6, 2);
        }
    }

    // ================================================================== every ability

    @ParameterizedTest(name = "{0}")
    @MethodSource("abilities")
    void wholeMatch(String id) {
        try (Scenario s = Scenario.ffa(id)) {
            scenery(s);
            s.ticks(60);
            s.assertNoProblems();

            s.layout();
            s.aimAt(s.owner, s.enemy);
            s.clearHits();
            s.assertSucceeded(s.active(), id + " active");
            s.ticks(100);
            assertTrue(s.damageBy(s.owner, s.enemy) > 0, id + " active hurts the aimed enemy: " + s.hits());
            assertEquals(0, s.damageTaken(s.owner), EPSILON, id + " active never hurts its owner: " + s.hits());

            s.layout();
            s.healAll();
            s.aimAt(s.owner, s.enemy);
            s.clearHits();
            s.assertSucceeded(s.ultimate(), id + " ultimate");
            s.ticks(300);
            assertTrue(s.damageBy(s.owner, s.enemy) + s.damageBy(s.owner, s.enemy2) > 0, id + " ultimate hurts: "
                    + s.hits());
            assertEquals(0, s.damageTaken(s.owner), EPSILON, id + " ultimate never hurts its owner: " + s.hits());
            s.assertNoProblems();
            if (!id.equals("poltergeist")) {
                assertNoDisplays(s, id);
            }
            for (TestPlayer enemy : s.enemies()) {
                assertModifierFree(enemy, id);
            }
            assertEquals(0, s.h.hub.tempBlocks().size(), id + ": every temporary block expired");
            clearScenery(s);
            s.finishClean();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("abilities")
    void teamModeNeverHarmsTheAlly(String id) {
        try (Scenario s = Scenario.teams(id)) {
            scenery(s);
            s.ticks(40);
            s.crossfireAlly();
            s.aimAt(s.owner, s.enemy);
            Location allyStart = s.ally().getLocation();
            s.clearHits();
            s.assertSucceeded(s.active(), id + " active");
            AbilityLifecycleTest.watchAlly(s, allyStart, 100);
            assertTrue(s.damageBy(s.owner, s.enemy) > 0, id + " still hits the enemy next to the ally: " + s.hits());

            s.layout();
            s.crossfireAlly();
            s.aimAt(s.owner, s.enemy);
            allyStart = s.ally().getLocation();
            s.assertSucceeded(s.ultimate(), id + " ultimate");
            AbilityLifecycleTest.watchAlly(s, allyStart, 300);
            assertModifierFree(s.ally(), id);
            s.assertNoProblems();
            clearScenery(s);
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
            s.ticks(4);
            assertOwnerDeathCleansUp(s);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("abilities")
    void ownerDiesDuringUltimate(String id) {
        try (Scenario s = Scenario.ffa(id)) {
            scenery(s);
            s.ticks(40);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), id + " ultimate");
            s.ticks(24);
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
        assertEquals(0, s.h.hub.tempBlocks().size(), id + ": temporary blocks");
        for (TestPlayer enemy : s.enemies()) {
            assertModifierFree(enemy, id);
            assertFalse(s.ctx().isSilenced(enemy), id + ": " + enemy.getName() + " is not silenced any more");
        }
        assertModifierFree(s.owner, id);
        s.assertNoProblems();
        clearScenery(s);
        s.finishClean();
    }

    // ================================================================== borrowed by the gambler

    static Scenario gambler(boolean teams) {
        Scenario.Builder builder = Scenario.builder("gambler").param("dodge_chance", 0).param("crit_chance", 0);
        return (teams ? builder.teams() : builder).start();
    }

    /** Exactly what {@code GamblerAbility#fireBorrowed} does. */
    static ActivationResult borrow(Scenario s, String id) {
        Ability borrowed = s.ctx().registry().create(id);
        borrowed.bind(s.owner, s.ctx(), s.ctx().paramsFor(id));
        return borrowed.onUltimate();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("abilities")
    void onlyTheStandaloneUltimatesAreBorrowable(String id) {
        try (Scenario s = gambler(false)) {
            boolean expected = standalone().anyMatch(id::equals);
            assertEquals(expected, s.ctx().registry().get(id).info().ultimateStandalone(), id);
            s.finishClean();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("standalone")
    void borrowedUltimateWorksOnAGambler(String id) {
        try (Scenario s = gambler(false)) {
            if (id.equals("poltergeist")) {
                wall(s, -3, 4, 6, 2);
            }
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(borrow(s, id), "borrowed " + id);
            boolean worked = false;
            for (int i = 0; i < 300; i++) {
                s.ticks(1);
                worked |= s.damageBy(s.owner, s.enemy) + s.damageBy(s.owner, s.enemy2) > 0;
            }
            assertTrue(worked, "the borrowed " + id + " ultimate does its thing: " + s.hits());
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "the borrowed " + id + " never hurts the gambler");
            assertModifierFree(s.owner, id);
            for (TestPlayer target : s.enemies()) {
                assertModifierFree(target, id);
            }
            assertNoDisplays(s, id);
            assertEquals(0, s.h.hub.tempBlocks().size(), id + ": temporary blocks");
            s.assertNoProblems();
            if (id.equals("poltergeist")) {
                clearWall(s, -3, 4, 6, 2);
            }
            s.finishClean();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("standalone")
    void borrowedUltimateNeverHarmsTheGamblersAlly(String id) {
        try (Scenario s = gambler(true)) {
            if (id.equals("poltergeist")) {
                wall(s, -3, 4, 6, 2);
            }
            s.crossfireAlly();
            s.aimAt(s.owner, s.enemy);
            Location allyStart = s.ally().getLocation();
            s.clearHits();
            s.assertSucceeded(borrow(s, id), "borrowed " + id);
            AbilityLifecycleTest.watchAlly(s, allyStart, 300);
            assertModifierFree(s.ally(), id);
            s.assertNoProblems();
            if (id.equals("poltergeist")) {
                clearWall(s, -3, 4, 6, 2);
            }
            s.finishClean();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("standalone")
    void gamblerDyingDuringABorrowedUltimateLeavesNothingBehind(String id) {
        try (Scenario s = gambler(false)) {
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(borrow(s, id), "borrowed " + id);
            s.ticks(20);
            s.killOwner(s.enemy);
            s.ticks(1);
            for (Entity entity : s.world.getEntities()) {
                assertTrue(entity instanceof Player, id + ": " + entity.getType() + " outlived the gambler");
            }
            s.clearHits();
            s.ticks(300);
            assertTrue(s.hits().stream().noneMatch(hit -> s.owner.equals(hit.causer())),
                    id + ": the dead gambler dealt damage: " + s.hits());
            assertEquals(0, s.h.hub.tempBlocks().size(), id + ": temporary blocks");
            for (TestPlayer target : s.enemies()) {
                assertModifierFree(target, id);
            }
            s.assertNoProblems();
            s.finishClean();
        }
    }

    // ================================================================== shared hook: restoreTempBlock

    @Test
    void anAbilityCanRestoreItsTemporaryBlockEarly() {
        try (Scenario s = Scenario.ffa("blockfall")) {
            Block block = s.world.getBlockAt(5, Y, 5);
            assertFalse(s.ctx().restoreTempBlock(block), "nothing to restore yet");
            assertTrue(s.ctx().placeTempBlock(block, Material.BRICKS.createBlockData(), 200));
            assertFalse(s.ctx().placeTempBlock(block, Material.STONE.createBlockData(), 200), "already temporary");
            assertTrue(s.ctx().restoreTempBlock(block), "restored early");
            assertEquals(Material.AIR, block.getType(), "the original is back at once");
            assertEquals(0, s.h.hub.tempBlocks().size(), "nothing temporary is left");
            assertTrue(s.ctx().placeTempBlock(block, Material.STONE.createBlockData(), 200),
                    "the spot takes a new temporary block right away");
            assertFalse(s.ctx().restoreTempBlock(s.world.getBlockAt(5, Y - 1, 5)), "real blocks are not restored");
            assertEquals(Material.STONE, s.world.getBlockAt(5, Y - 1, 5).getType());
            s.finishClean();
        }
    }

    // ================================================================== 아틀라스 atlas

    @Test
    void atlasLandsAtOnceWhenLaunchedAndShakesTheGround() {
        try (Scenario s = Scenario.ffa("atlas")) {
            s.place(s.enemy, 2.0, 0.5, 90);
            s.place(s.enemy2, 10.5, 10.5, 180);
            s.ticks(2);
            s.clearHits();
            s.owner.setVelocity(new Vector(0, 1.2, 0)); // another ability throws the titan up
            double highest = s.owner.getLocation().getY();
            for (int i = 0; i < 30; i++) {
                s.ticks(1);
                highest = Math.max(highest, s.owner.getLocation().getY());
            }
            assertTrue(highest < FlatWorld.SURFACE + 0.5, "the titan never rises: " + highest);
            assertEquals(FlatWorld.SURFACE, s.owner.getLocation().getY(), EPSILON, "and stands on the ground");
            assertEquals(s.param("anchor_damage"), s.damageBy(s.owner, s.enemy), EPSILON,
                    "the landing shockwave hits the enemy next to him");
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON, "but not one far away");
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "no fall damage");

            s.place(s.enemy, 2.0, 0.5, 90);
            s.clearHits();
            s.owner.setVelocity(new Vector(0, 1.2, 0));
            s.ticks(10);
            assertEquals(0, s.damageBy(s.owner, s.enemy), EPSILON, "the shockwave has its own cooldown");
            s.ticks(s.paramTicks("anchor_cooldown"));
            s.place(s.enemy, 2.0, 0.5, 90);
            s.clearHits();
            s.owner.setVelocity(new Vector(0, 1.2, 0));
            s.ticks(10);
            assertEquals(s.param("anchor_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "and comes back");

            s.ticks(s.paramTicks("anchor_cooldown"));
            s.place(s.enemy, 2.0, 0.5, 90);
            s.clearHits();
            s.owner.setVelocity(new Vector(0, 0.42, 0)); // an ordinary jump is left alone
            s.ticks(4);
            assertTrue(s.owner.getLocation().getY() > FlatWorld.SURFACE + 0.3, "he can still jump");
            s.ticks(30);
            assertEquals(0, s.damageBy(s.owner, s.enemy), EPSILON, "and a jump shakes nothing");
            s.finishClean();
        }
    }

    @Test
    void atlasHitsFliersHarderAndHammersThemDown() {
        try (Scenario s = Scenario.ffa("atlas")) {
            s.world.physics().setEnabled(false);
            s.place(s.enemy, 0.5, FlatWorld.SURFACE + 3, 2.0, 180, 0);
            s.clearHits();
            s.melee(s.owner, s.enemy, 4);
            assertEquals(4 * (1 + s.param("air_bonus") / 100), s.damageBy(s.owner, s.enemy), EPSILON,
                    "an enemy high in the air takes more");
            s.ticks(2);
            assertTrue(s.enemy.getVelocity().getY() < -1.0, "and is hammered down: " + s.enemy.getVelocity());

            s.place(s.enemy, 0.5, 2.0, 180);
            s.clearHits();
            s.melee(s.owner, s.enemy, 4);
            assertEquals(4, s.damageBy(s.owner, s.enemy), EPSILON, "no bonus on the ground");
            s.ticks(2);
            assertTrue(s.enemy.getVelocity().getY() >= -EPSILON, "and no slam");
            s.world.physics().setEnabled(true);
            s.finishClean();
        }
    }

    @Test
    void atlasWeightOfTheSkyMakesTheGroundedHeavyAndSlamsTheAirborne() {
        try (Scenario s = Scenario.ffa("atlas")) {
            s.world.physics().setEnabled(false);
            s.place(s.enemy, 0.5, 4.5, 180);
            s.place(s.enemy2, 3.5, FlatWorld.SURFACE + 4, 2.5, 180, 0);
            s.clearHits();
            s.assertSucceeded(s.active(), "하늘의 무게");
            s.ticks(2);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "the starlight ring warns first");
            assertFalse(entities(s, TextDisplay.class).isEmpty(), "stars fall with the sky");
            for (TextDisplay star : entities(s, TextDisplay.class)) {
                assertUnrotated(star, "falling star");
            }
            s.ticks(s.paramTicks("weight_delay") + 2);
            assertEquals(s.param("weight_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "the grounded enemy");
            assertEquals(0, s.enemy.getAttribute(Attribute.JUMP_STRENGTH).getValue(), EPSILON, "cannot jump");
            assertEquals(0.1 * (1 - s.param("heavy_slow") / 100),
                    s.enemy.getAttribute(Attribute.MOVEMENT_SPEED).getValue(), 1.0E-4, "is slowed");
            assertTrue(s.enemy.getAttribute(Attribute.GRAVITY).getValue() > 0.08 + EPSILON, "falls faster");
            assertEquals(s.param("air_damage"), s.damageBy(s.owner, s.enemy2), EPSILON, "the enemy in the air");
            assertTrue(s.enemy2.getVelocity().getY() < -1.0, "is hammered down: " + s.enemy2.getVelocity());
            assertModifierFree(s.enemy2, "the airborne enemy is not made heavy");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            for (BlockDisplay shard : entities(s, BlockDisplay.class)) {
                assertUnrotated(shard, "sky shard");
            }
            s.world.physics().setEnabled(true);
            s.ticks(s.paramTicks("heavy_duration") + 20);
            assertModifierFree(s.enemy, "the weight lifts");
            assertNoDisplays(s, "stars and sky shards");
            s.finishClean();
        }
    }

    @Test
    void atlasSkyCrushesWhoeverHoldsItToTheEnd() {
        try (Scenario s = Scenario.ffa("atlas")) {
            s.place(s.enemy2, 10.5, 10.5, 180);
            s.aimAt(s.owner, s.enemy);
            s.clearHits();
            s.assertSucceeded(s.ultimate(), "하늘 떠넘기기");
            assertFalse(s.ultimate().succeeded(), "one sky at a time");
            s.ticks(4);
            assertTrue(entities(s, BlockDisplay.class).size() >= 8, "the globe flies: " + entities(s, Display.class));
            for (Display display : entities(s, Display.class)) {
                assertUnrotated(display, "globe part " + display.getType());
            }
            s.ticks(s.paramTicks("throw_time"));
            assertTrue(slowed(s.enemy), "the bearer is weighed down");
            s.ticks(21);
            assertEquals(s.param("sky_tick"), s.damageBy(s.owner, s.enemy), EPSILON, "the first second");
            s.ticks(120);
            double pulses = 0;
            for (int i = 0; i < 5; i++) {
                pulses += s.param("sky_tick") + s.param("sky_tick_growth") * i;
            }
            assertEquals(pulses + s.param("collapse_damage"), s.damageBy(s.owner, s.enemy), EPSILON,
                    "five heavier seconds, then the sky collapses on the bearer: " + s.hitsBy(s.owner, s.enemy));
            assertEquals(9, pulses + s.param("collapse_damage"), EPSILON, "at most 9 from one sky");
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON, "nobody else was near");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            assertModifierFree(s.enemy, "the weight is gone");
            s.ticks(20);
            assertNoDisplays(s, "the globe");
            s.finishClean();
        }
    }

    @Test
    void atlasSkyPassesOnByAHitOrABumpButNotStraightBack() {
        try (Scenario s = Scenario.ffa("atlas")) {
            s.place(s.enemy2, 6.5, 4.5, 180);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "하늘 떠넘기기");
            s.ticks(s.paramTicks("throw_time") + 25);
            assertTrue(slowed(s.enemy), "the enemy carries the sky");
            s.melee(s.enemy, s.enemy2, 1);
            assertTrue(slowed(s.enemy2), "a hit hands it to the other enemy");
            assertFalse(slowed(s.enemy), "and frees the giver");
            s.melee(s.enemy2, s.enemy, 1);
            assertTrue(slowed(s.enemy2), "it cannot go straight back");
            assertFalse(slowed(s.enemy));
            s.ticks(s.paramTicks("pass_immunity") + 2);
            s.place(s.enemy, 6.8, 4.5, 180); // bump into the bearer
            s.ticks(2);
            assertTrue(slowed(s.enemy), "a bump passes it on once the moment is over");
            assertFalse(slowed(s.enemy2));
            s.ticks(120);
            s.assertNoProblems();
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            assertModifierFree(s.enemy, "the sky is over");
            assertModifierFree(s.enemy2, "the sky is over");
            s.finishClean();
        }
    }

    @Test
    void atlasTakesTheSkyBackAndThrowsTheGlobe() {
        try (Scenario s = Scenario.ffa("atlas")) {
            s.place(s.enemy, 0.5, 15.5, 180);
            s.place(s.enemy2, 4.5, 16.5, 180);
            s.assertSucceeded(s.active(), "하늘의 무게");
            s.ticks(10);
            assertTrue(s.ctx().cooldownRemaining(s.owner) > 1, "the active is cooling down");
            s.place(s.enemy, 0.5, 4.5, 180);
            s.place(s.enemy2, 4.5, 6.5, 180);
            s.aimAt(s.owner, s.enemy);
            s.clearHits();
            s.assertSucceeded(s.ultimate(), "하늘 떠넘기기");
            s.ticks(s.paramTicks("throw_time") + 4);
            s.melee(s.enemy, s.owner, 1);
            assertTrue(slowed(s.enemy), "not straight back to Atlas either");
            s.ticks(s.paramTicks("pass_immunity") + 2);
            s.melee(s.enemy, s.owner, 1);
            assertFalse(slowed(s.enemy), "Atlas takes the sky back");
            assertModifierFree(s.owner, "lightly");
            assertEquals(0, s.ctx().cooldownRemaining(s.owner), EPSILON, "the globe throw is ready at once");
            s.ticks(40);
            assertEquals(s.param("sky_tick"), s.damageBy(s.owner, s.enemy), EPSILON, "no more sky on the enemy");
            s.clearHits();
            s.aimAt(s.owner, s.enemy2);
            s.assertSucceeded(s.active(), "천구 투척");
            s.ticks(16);
            assertEquals(s.param("globe_damage"), s.damageBy(s.owner, s.enemy2), EPSILON, "the globe bursts on it");
            assertEquals(0, s.damageBy(s.owner, s.enemy), EPSILON, "out of the burst");
            s.readyActive();
            s.aimAt(s.owner, s.enemy);
            s.clearHits();
            s.assertSucceeded(s.active(), "the next active is the weight of the sky again");
            s.ticks(10);
            assertEquals(s.param("weight_damage"), s.damageBy(s.owner, s.enemy), EPSILON);
            s.ticks(80);
            assertNoDisplays(s, "globe");
            s.finishClean();
        }
    }

    @Test
    void atlasSkyNeverGivesAwayABearerHiddenFromAtlas() {
        try (Scenario s = Scenario.ffa("atlas")) {
            s.place(s.enemy2, 10.5, 10.5, 180);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "하늘 떠넘기기");
            s.ticks(s.paramTicks("throw_time") + 4);
            assertTrue(slowed(s.enemy), "the enemy carries the sky");
            s.owner.hidePlayer(s.h.hub.plugin(), s.enemy); // the bearer slips into stealth
            s.ticks(2);
            for (Display display : entities(s, Display.class)) {
                assertEquals(0, display.getViewRange(), EPSILON, "the globe over a hidden bearer is not drawn: "
                        + display.getType());
            }
            s.owner.showPlayer(s.h.hub.plugin(), s.enemy);
            s.ticks(2);
            assertTrue(entities(s, Display.class).stream().allMatch(d -> d.getViewRange() > 0), "drawn again");
            s.ticks(120);
            s.finishClean();
        }
    }

    private static boolean slowed(Player player) {
        return player.getAttribute(Attribute.MOVEMENT_SPEED).getValue() < 0.1 - EPSILON;
    }

    // ================================================================== 요르문간드 jormungandr

    @Test
    void jormungandrShedsItsSkinAtLowHealth() {
        try (Scenario s = Scenario.ffa("jormungandr")) {
            s.ticks(2);
            s.owner.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 200, 0));
            s.ctx().addModifier(s.enemy, s.owner, Attribute.MOVEMENT_SPEED, -0.4,
                    AttributeModifier.Operation.MULTIPLY_SCALAR_1, 200);
            s.owner.setHealth(9);
            s.ticks(2);
            assertTrue(s.owner.hasPotionEffect(PotionEffectType.POISON), "not yet: above the threshold");
            s.owner.setFireTicks(100);
            s.owner.setHealth(7);
            s.ticks(1);
            assertFalse(s.owner.hasPotionEffect(PotionEffectType.POISON), "the poison is shed with the skin");
            assertEquals(0, s.owner.getFireTicks(), "and the fire");
            assertEquals(s.param("shed_absorption"), s.owner.getAbsorptionAmount(), EPSILON, "a fresh shield");
            assertEquals(0.1 * (1 + s.param("shed_speed") / 100),
                    s.owner.getAttribute(Attribute.MOVEMENT_SPEED).getValue(), 1.0E-4,
                    "the slow is shed too, and a burst of speed");
            assertEquals(3, entities(s, BlockDisplay.class).size(), "the empty husk lies there");
            for (Display husk : entities(s, Display.class)) {
                assertUnrotated(husk, "husk");
            }
            s.owner.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 200, 0));
            s.owner.setHealth(5);
            s.ticks(2);
            assertTrue(s.owner.hasPotionEffect(PotionEffectType.POISON), "only once per cooldown");
            s.owner.removePotionEffect(PotionEffectType.POISON);
            s.ticks(s.paramTicks("shed_absorb_duration") + 2);
            assertEquals(0, s.owner.getAbsorptionAmount(), EPSILON, "the shield fades");
            assertModifierFree(s.owner, "the speed fades");
            assertNoDisplays(s, "husk");
            s.finishClean();
        }
    }

    @Test
    void jormungandrTailSweepsTheFarHalf() {
        try (Scenario s = Scenario.ffa("jormungandr")) {
            s.world.physics().setEnabled(false);
            s.place(s.enemy, 0.5, 6.5, 180);
            s.place(s.enemy2, 0.5, 10.5, 180);
            s.aimAt(s.owner, s.enemy);
            s.clearHits();
            s.assertSucceeded(s.active(), "꼬리 휩쓸기");
            s.ticks(s.paramTicks("tail_delay") - 1);
            assertEquals(0, s.hits().size(), "the tail rises first: " + s.hits());
            s.ticks(4);
            List<BlockDisplay> tail = entities(s, BlockDisplay.class);
            assertEquals(6, tail.size(), "six tail segments");
            for (BlockDisplay segment : tail) {
                assertUnrotated(segment, "tail segment");
                Vector3f center = boxCenter(segment);
                Vector3f along = leftRotation(segment).transform(new Vector3f(0, 0, 1));
                Vector3f flat = new Vector3f(center.x(), 0, center.z()).normalize();
                assertTrue(Math.abs(new Vector3f(along.x(), 0, along.z()).normalize().dot(flat)) > 0.95,
                        "each segment lies along the tail: " + along + " at " + center);
            }
            s.ticks(s.paramTicks("tail_sweep") + 3);
            assertEquals(s.param("tail_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "the enemy at the pivot");
            assertEquals(s.param("tail_damage"), s.damageBy(s.owner, s.enemy2), EPSILON, "the enemy on the far side");
            assertTrue(slowed(s.enemy2), "and slowed");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.world.physics().setEnabled(true);
            s.ticks(20);
            assertNoDisplays(s, "tail");
            s.finishClean();
        }
    }

    @Test
    void jormungandrTailIsLowEnoughToJumpAndMissesTheNearSide() {
        try (Scenario s = Scenario.ffa("jormungandr")) {
            s.world.physics().setEnabled(false);
            s.place(s.enemy, -3.5, 3.5, 180); // between the serpent and the pivot
            s.place(s.enemy2, 0.5, FlatWorld.SURFACE + 1.2, 10.5, 180, 0); // jumping over the tail
            s.aimAtFloor(s.owner, 0.5, 6.5);
            s.clearHits();
            s.assertSucceeded(s.active(), "꼬리 휩쓸기");
            s.ticks(s.paramTicks("tail_delay") + s.paramTicks("tail_sweep") + 4);
            assertEquals(0, s.damageBy(s.owner, s.enemy2), EPSILON, "the tail is low enough to jump over");
            assertEquals(0, s.damageBy(s.owner, s.enemy), EPSILON, "and only sweeps the far half");
            s.world.physics().setEnabled(true);
            s.ticks(20);
            s.finishClean();
        }
    }

    @Test
    void jormungandrRingTurnsAnEnemyWalkingOutBackInOnTheFarSide() {
        try (Scenario s = Scenario.ffa("jormungandr")) {
            s.place(s.enemy, 0.5, 8.5, 0);
            s.place(s.enemy2, 20.5, 20.5, 180);
            s.clearHits();
            s.assertSucceeded(s.ultimate(), "미드가르드의 고리");
            assertFalse(s.ultimate().succeeded(), "one ring at a time");
            s.ticks(10);
            assertTrue(entities(s, BlockDisplay.class).size() >= 16, "the serpent's body and head");
            for (Display display : entities(s, Display.class)) {
                assertUnrotated(display, "ring part");
            }
            for (int i = 0; i < 60; i++) {
                Location at = s.enemy.getLocation();
                if (at.getZ() < 0) {
                    break;
                }
                s.enemy.teleport(at.add(0, 0, 0.3)); // walking out
                s.ticks(1);
            }
            Location at = s.enemy.getLocation();
            assertTrue(at.getZ() < -4, "walked out at the south, came back in at the north: " + at.toVector());
            assertEquals(FlatWorld.SURFACE, at.getY(), EPSILON, "feet on the ground");
            assertTrue(Spots.isFree(s.world, at, 0.6, 1.8), "in free space");
            assertEquals(0, at.getYaw(), 1.0, "facing the middle");
            assertEquals(0, s.damageBy(s.owner, s.enemy), EPSILON, "the joined space does not hurt");
            s.place(s.enemy, 0.5, 2.5, 0);
            s.ticks(200);
            assertEquals(s.param("ring_crush_damage"), s.damageBy(s.owner, s.enemy), EPSILON,
                    "the coils constrict on the enemy still inside: " + s.hitsBy(s.owner, s.enemy));
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON, "an enemy outside is left alone");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.ticks(20);
            assertNoDisplays(s, "ring");
            s.finishClean();
        }
    }

    @Test
    void jormungandrRingShovesEnemiesInwardAsItTightens() {
        try (Scenario s = Scenario.ffa("jormungandr")) {
            s.place(s.enemy, 0.5, 10.5, 180);
            s.place(s.enemy2, 20.5, 20.5, 180);
            s.clearHits();
            s.assertSucceeded(s.ultimate(), "미드가르드의 고리");
            s.h.tickUntil(() -> s.damageBy(s.owner, s.enemy) > 0, 120, "the tightening body reaches the enemy");
            assertEquals(s.param("ring_touch_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "a touch");
            assertTrue(s.enemy.getVelocity().getZ() < -0.2, "shoved inward: " + s.enemy.getVelocity());
            s.ticks(200);
            double max = s.param("ring_touch_damage") * s.param("ring_touch_max") + s.param("ring_crush_damage");
            assertTrue(s.damageBy(s.owner, s.enemy) <= max + EPSILON, "touches are capped: " + s.hits());
            assertTrue(s.hitsBy(s.owner, s.enemy).stream().anyMatch(h -> Math.abs(h.damage()
                    - s.param("ring_crush_damage")) < EPSILON), "and the crush at the end");
            s.finishClean();
        }
    }

    @Test
    void jormungandrRingLetsAlliesWalkOut() {
        try (Scenario s = Scenario.teams("jormungandr")) {
            s.place(s.ally(), 0.5, 8.5, 0);
            s.place(s.enemy, -3.5, 0.5, 0);
            s.assertSucceeded(s.ultimate(), "미드가르드의 고리");
            s.ticks(10);
            for (int i = 0; i < 25; i++) {
                s.ally().teleport(s.ally().getLocation().add(0, 0, 0.3));
                s.ticks(1);
            }
            assertTrue(s.ally().getLocation().getZ() > 14, "the ally walks out freely: " + s.ally().getLocation());
            s.ticks(200);
            assertEquals(0, s.damageTaken(s.ally()), EPSILON, "never hurt");
            assertEquals(s.param("ring_crush_damage"), s.damageBy(s.owner, s.enemy), EPSILON,
                    "the enemy inside is crushed");
            s.finishClean();
        }
    }

    // ================================================================== 기린 qilin

    /** Keys as the client reports them. */
    private record Keys(boolean isForward, boolean isBackward, boolean isLeft, boolean isRight, boolean isJump,
                        boolean isSneak, boolean isSprint) implements Input {
        static Keys jump(boolean pressed) {
            return new Keys(true, false, false, false, pressed, false, false);
        }
    }

    private static void press(Scenario s, Keys keys) {
        s.h.server.getPluginManager().callEvent(new PlayerInputEvent(s.owner, keys));
    }

    @Test
    void qilinStrikesGentlyAndHealsTheMostHurtAlly() {
        try (Scenario s = Scenario.teams("qilin")) {
            s.place(s.ally(), -1.5, 0.5, 0);
            s.ally().setHealth(s.maxHealth(s.ally()) - 4);
            double before = s.health(s.ally());
            s.clearHits();
            s.melee(s.owner, s.enemy, 5);
            assertEquals(5 * (1 - s.param("melee_penalty") / 100), s.damageBy(s.owner, s.enemy), EPSILON,
                    "the gentle beast holds back");
            assertEquals(before + s.param("heal_amount"), s.health(s.ally()), EPSILON, "and heals the hurt ally");
            s.place(s.ally(), 15.5, 0.5, 0);
            before = s.health(s.ally());
            s.melee(s.owner, s.enemy, 5);
            assertEquals(before, s.health(s.ally()), EPSILON, "only allies within reach");
            s.finishClean();
        }
    }

    @Test
    void qilinCloudStepJumpsAgainInMidAir() {
        try (Scenario s = Scenario.ffa("qilin")) {
            s.world.physics().setEnabled(false);
            s.place(s.owner, 0.5, FlatWorld.SURFACE + 2, 0.5, 0, 0);
            press(s, Keys.jump(true));
            press(s, Keys.jump(false));
            assertEquals(0, s.owner.getVelocity().getY(), EPSILON, "no cloud step without a hit first");

            s.melee(s.owner, s.enemy, 2);
            s.place(s.owner, 0.5, 0.5, 0);
            press(s, Keys.jump(true));
            press(s, Keys.jump(false));
            assertEquals(0, s.owner.getVelocity().getY(), EPSILON, "a jump from the ground is an ordinary jump");

            s.place(s.owner, 0.5, FlatWorld.SURFACE + 2, 0.5, 0, 0);
            press(s, Keys.jump(true));
            assertEquals(Motion.launchSpeed(s.param("step_height")), s.owner.getVelocity().getY(), 1.0E-6,
                    "a cloud blooms under the hooves");
            assertTrue(s.owner.getVelocity().getZ() > 0.1, "carrying it on forwards");
            assertFalse(entities(s, BlockDisplay.class).isEmpty(), "a little cloud puffs up");
            for (BlockDisplay puff : entities(s, BlockDisplay.class)) {
                assertUnrotated(puff, "cloud puff");
            }
            press(s, Keys.jump(false));
            s.owner.setVelocity(new Vector());
            press(s, Keys.jump(true));
            assertEquals(0, s.owner.getVelocity().getY(), EPSILON, "one step per charge");
            s.world.physics().setEnabled(true);

            s.clearHits();
            s.owner.setNoDamageTicks(0);
            s.owner.damage(6, DamageSource.builder(DamageType.FALL).build());
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "no fall ever hurts it");
            s.ticks(30);
            assertNoDisplays(s, "cloud puffs");
            s.finishClean();
        }
    }

    @Test
    void qilinBridgeLiftsEnemiesInItsWayAndSpeedsUpTheTeam() {
        try (Scenario s = Scenario.teams("qilin")) {
            s.place(s.enemy2, 12.5, -8.5, 0);
            s.place(s.ally(), -0.5, 6.5, 0); // on the lane
            s.aimAt(s.owner, s.enemy);
            s.clearHits();
            s.assertSucceeded(s.active(), "구름다리");
            s.h.tickUntil(() -> s.damageBy(s.owner, s.enemy) > 0, 20, "the surge reaches the enemy");
            assertEquals(s.param("bridge_damage"), s.damageBy(s.owner, s.enemy), EPSILON);
            s.ticks(3);
            assertTrue(s.enemy.getLocation().getY() > FlatWorld.SURFACE + 0.4, "and lifts it");
            assertEquals(FlatWorld.SURFACE, s.ally().getLocation().getY(), EPSILON, "the ally is not thrown");
            s.ticks(8);
            assertEquals(0.1 * (1 + s.param("bridge_speed") / 100),
                    s.ally().getAttribute(Attribute.MOVEMENT_SPEED).getValue(), 1.0E-4, "an ally on it is faster");
            s.place(s.ally(), 6.5, 6.5, 0);
            s.ticks(6);
            assertModifierFree(s.ally(), "off the bridge, back to normal");
            s.place(s.owner, 0.5, 5.5, 0);
            s.ticks(6);
            assertTrue(s.owner.getAttribute(Attribute.MOVEMENT_SPEED).getValue() > 0.1 + EPSILON, "so is the qilin");
            assertEquals(0, s.damageTaken(s.ally()), EPSILON);
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.ticks(s.paramTicks("bridge_duration") + 10);
            assertModifierFree(s.owner, "the bridge is gone");
            s.finishClean();
        }
    }

    @Test
    void qilinBridgeFillsGapsAndClimbsHalfABlockAtATime() {
        try (Scenario s = Scenario.ffa("qilin")) {
            s.place(s.enemy, 12.5, -8.5, 0);
            s.place(s.enemy2, -12.5, -8.5, 0);
            for (int x = -1; x <= 0; x++) {
                for (int z = 3; z <= 6; z++) {
                    s.world.setBlock(x, FlatWorld.FLOOR_Y, z, Material.AIR); // a pit across the way
                }
            }
            s.aimAt(s.owner, new Location(s.world, 0.5, FlatWorld.SURFACE + 1, 12.5));
            s.assertSucceeded(s.active(), "구름다리 (flat)");
            s.ticks(12);
            for (int z = 3; z <= 6; z++) {
                Material deck = s.world.getBlockAt(0, FlatWorld.FLOOR_Y, z).getType();
                assertTrue(deck == Material.WHITE_WOOL || deck == Material.SNOW_BLOCK, "a cloud deck over the pit at "
                        + z + ": " + deck);
            }
            assertEquals(Material.AIR, s.world.getBlockAt(0, Y, 4).getType(), "the flat bridge is level with the feet");
            s.ticks(s.paramTicks("bridge_duration") + 4);
            assertEquals(Material.AIR, s.world.getBlockAt(0, FlatWorld.FLOOR_Y, 4).getType(), "the deck is gone");

            s.readyActive();
            Location look = s.owner.getLocation();
            look.setPitch(-35);
            s.owner.teleport(look);
            s.assertSucceeded(s.active(), "구름다리 (up)");
            s.ticks(12);
            assertEquals(Material.SMOOTH_QUARTZ_SLAB, s.world.getBlockAt(0, Y, 1).getType(), "a half step first");
            assertTrue(cloud(s.world.getBlockAt(0, Y, 2).getType()), "then a whole block");
            assertEquals(Material.SMOOTH_QUARTZ_SLAB, s.world.getBlockAt(0, Y + 1, 3).getType(), "and so on");
            double rise = s.param("bridge_rise");
            assertTrue(cloud(s.world.getBlockAt(0, Y + (int) rise - 1, 10).getType()), "up to the top");
            assertTrue(cloud(s.world.getBlockAt(-1, Y + (int) rise - 1, 14).getType()), "two wide, level at the top");
            assertEquals(Material.AIR, s.world.getBlockAt(1, Y, 2).getType(), "and no wider");
            assertEquals(Material.AIR, s.world.getBlockAt(0, Y + (int) rise, 14).getType(), "never above the top");
            s.ticks(s.paramTicks("bridge_duration") + 4);
            assertEquals(0, s.h.hub.tempBlocks().size(), "the bridge melts away");
            for (int x = -1; x <= 0; x++) {
                for (int z = 3; z <= 6; z++) {
                    s.world.setBlock(x, FlatWorld.FLOOR_Y, z, Material.STONE);
                }
            }
            s.finishClean();
        }
    }

    private static boolean cloud(Material material) {
        return material == Material.WHITE_WOOL || material == Material.SNOW_BLOCK;
    }

    @Test
    void qilinCloudCarriesTheTeamUpAndDrumsOnTheEnemiesBelow() {
        try (Scenario s = Scenario.teams("qilin")) {
            s.place(s.enemy2, 14.5, 14.5, 0);
            s.ally().setHealth(s.maxHealth(s.ally()) - 6);
            s.clearHits();
            s.assertSucceeded(s.ultimate(), "상서로운 구름");
            int top = Y + (int) s.param("cloud_height");
            s.h.tickUntil(() -> Math.abs(s.owner.getLocation().getY() - top) < EPSILON
                    && Math.abs(s.ally().getLocation().getY() - top) < EPSILON, 60, "both riders on the cloud");
            assertTrue(cloud(s.world.getBlockAt(0, top - 1, 0).getType()), "standing on cloud blocks");
            assertTrue(cloud(s.world.getBlockAt(-2, top - 1, 0).getType()));
            assertTrue(cloud(s.world.getBlockAt(5, top - 1, 0).getType()), "the cloud spreads wide");
            assertTrue(Spots.isFree(s.world, s.owner.getLocation(), 0.6, 1.8), "nobody stuck in the cloud");
            assertTrue(slowed(s.enemy), "the enemy in the shadow is slowed");
            double healthBefore = s.health(s.ally());
            s.ticks(41);
            assertTrue(s.health(s.ally()) >= healthBefore + s.param("cloud_heal") - EPSILON, "the cloud heals");
            assertTrue(s.damageBy(s.owner, s.enemy) >= s.param("hoof_damage") - EPSILON, "hoof beats from above");
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON, "out of the shadow, out of reach");
            s.clearHits();
            s.melee(s.ally(), s.enemy, 4);
            assertEquals(4 * (1 + s.param("cloud_bonus") / 100), s.damageBy(s.ally(), s.enemy), EPSILON,
                    "riders hit harder downwards");
            s.place(s.enemy2, 3.5, top, 0.5, 0, 0);
            s.ticks(1);
            assertTrue(s.enemy2.getVelocity().getX() > 0.2, "an enemy on the cloud slips off: "
                    + s.enemy2.getVelocity());
            s.place(s.enemy2, 14.5, 14.5, 0);
            s.ticks(s.paramTicks("cloud_duration"));
            assertTrue(s.owner.hasPotionEffect(PotionEffectType.SLOW_FALLING), "everyone floats down");
            assertTrue(s.ally().hasPotionEffect(PotionEffectType.SLOW_FALLING));
            assertEquals(0, s.h.hub.tempBlocks().size(), "the cloud melts");
            assertModifierFree(s.enemy, "the shadow lifts");
            assertEquals(0, s.damageTaken(s.ally()), EPSILON, "the ally never got hurt");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.ticks(120);
            assertEquals(0, s.damageTaken(s.ally()), EPSILON, "not even landing");
            s.finishClean();
        }
    }

    @Test
    void qilinCloudStaysUnderAnyCeiling() {
        try (Scenario s = Scenario.ffa("qilin")) {
            for (int x = -3; x <= 3; x++) {
                for (int z = -3; z <= 3; z++) {
                    s.world.setBlock(x, Y + 6, z, Material.STONE);
                }
            }
            s.assertSucceeded(s.ultimate(), "상서로운 구름 (low)");
            s.h.tickUntil(() -> Math.abs(s.owner.getLocation().getY() - (Y + 4)) < EPSILON, 60,
                    "landing on the cloud one block lower, under the ceiling");
            assertTrue(cloud(s.world.getBlockAt(0, Y + 3, 0).getType()), "on cloud blocks");
            assertTrue(Spots.isFree(s.world, s.owner.getLocation(), 0.6, 1.8), "with room to stand");
            s.ticks(s.paramTicks("cloud_duration") + 120);
            for (int x = -3; x <= 3; x++) {
                for (int z = -3; z <= 3; z++) {
                    s.world.setBlock(x, Y + 6, z, Material.AIR);
                }
            }
            s.finishClean();
        }
    }

    // ================================================================== 폴터가이스트 poltergeist

    /** An arrow from {@code shooter} hitting {@code victim} for {@code amount}. */
    private static void shoot(Scenario s, Player shooter, Player victim, double amount) {
        Arrow arrow = s.world.spawnArrow(shooter.getEyeLocation(), new Vector(0, 0, -1), 1, 0);
        arrow.setShooter(shooter);
        victim.setNoDamageTicks(0);
        victim.damage(amount, DamageSource.builder(DamageType.ARROW).withCausingEntity(shooter)
                .withDirectEntity(arrow).build());
        arrow.remove();
    }

    @Test
    void poltergeistRattlesThingsLooseThatTakeRangedHitsForIt() {
        try (Scenario s = Scenario.builder("poltergeist").param("rattle_interval", 1).start()) {
            s.ticks(70);
            List<ItemDisplay> things = entities(s, ItemDisplay.class);
            assertEquals((int) s.param("orbit_max"), things.size(), "ghostly odds and ends float around it");
            for (ItemDisplay thing : things) {
                assertUnrotated(thing, "floating thing");
                assertTrue(thing.getLocation().distance(s.owner.getLocation()) < 2.5, "close around the ghost");
            }
            s.clearHits();
            shoot(s, s.enemy, s.owner, 4);
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "a floating thing takes the arrow");
            s.melee(s.enemy, s.owner, 4);
            assertEquals(4, s.damageTaken(s.owner), EPSILON, "not a melee blow");
            s.clearHits();
            s.place(s.enemy, 0.5, 9.5, 180);
            s.ctx().damage(s.enemy, s.owner, 3);
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "nor ability damage from far away");
            s.place(s.enemy, 0.5, 2.5, 180);
            s.ctx().damage(s.enemy, s.owner, 3);
            assertEquals(3, s.damageTaken(s.owner), EPSILON, "but ability damage from close by goes through");
            s.finishClean();
        }
    }

    @Test
    void poltergeistTearsRealBlocksOffTheWallsOnBattleMaps() {
        try (Scenario s = terrain(Scenario.builder("poltergeist").param("rattle_interval", 1)
                .param("grab_life_max", 2)).start()) {
            s.world.wallAlongX(-2, 2, 3, 2, Material.BRICKS);
            s.place(s.enemy, 0.5, 6.5, 180);
            s.ticks(40);
            assertTrue(s.h.hub.tempBlocks().removedCount() >= 1, "a wall block tore loose");
            assertTrue(entities(s, BlockDisplay.class).stream().anyMatch(d -> d.getBlock().getMaterial()
                    == Material.BRICKS), "and floats around the ghost");
            s.ticks(80);
            assertEquals(2, s.h.hub.tempBlocks().removedCount(), "at most grab_life_max blocks per life");
            assertEquals(Material.STONE, s.world.getBlockAt(0, FlatWorld.FLOOR_Y, 0).getType(),
                    "never the floor under the ghost");
            assertEquals(Material.STONE, s.world.getBlockAt(0, FlatWorld.FLOOR_Y, 6).getType(),
                    "nor under the enemy");
            s.finishMatch();
            assertEquals(0, s.h.hub.tempBlocks().removedCount(), "the wall comes back at the end");
            clearWall(s, -2, 2, 3, 2);
            s.assertNoProblems();
            s.assertCleanWorld();
        }
    }

    @Test
    void poltergeistHurlsFourThings() {
        try (Scenario s = Scenario.ffa("poltergeist")) {
            s.place(s.enemy2, 1.5, 4.5, 180);
            s.aimAt(s.owner, s.enemy);
            s.clearHits();
            s.assertSucceeded(s.active(), "집어던지기");
            s.ticks(3);
            for (Display thing : entities(s, Display.class)) {
                assertUnrotated(thing, "thrown thing");
            }
            s.ticks(40);
            double cap = s.param("throw_damage") * s.param("throw_max_hits");
            assertEquals(cap, s.damageBy(s.owner, s.enemy), EPSILON, "every throw lands, up to the cap: "
                    + s.hitsBy(s.owner, s.enemy));
            assertTrue(s.damageBy(s.owner, s.enemy2) > 0, "the one next to it is caught too");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            assertNoDisplays(s, "thrown things");
            s.finishClean();
        }
    }

    @Test
    void poltergeistUproarSlamsCoverIntoEnemiesNearIt() {
        try (Scenario s = Scenario.ffa("poltergeist")) {
            wall(s, -3, 4, 6, 2);
            s.place(s.enemy2, -8.5, -6.5, 0); // out in the open
            s.clearHits();
            s.assertSucceeded(s.ultimate(), "대소동");
            boolean furniture = false;
            for (int i = 0; i < 14; i++) {
                s.ticks(1);
                furniture |= !entities(s, Display.class).isEmpty();
            }
            assertTrue(furniture, "ghostly furniture comes out of the wall");
            assertEquals(s.param("chaos_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "and slams the enemy");
            assertTrue(s.enemy.getVelocity().getZ() < -0.1, "away from the wall: " + s.enemy.getVelocity());
            assertTrue(slowed(s.enemy), "staggered");
            assertTrue(s.enemy.getAttribute(Attribute.ATTACK_SPEED).getValue() < 4.0 - EPSILON, "attacks slower");
            for (int pulse = 0; pulse < 6; pulse++) {
                s.place(s.enemy, 0.5, 4.5, 180);
                s.ticks(20);
            }
            double cap = s.param("chaos_damage") * s.param("chaos_max_hits");
            assertEquals(cap, s.damageBy(s.owner, s.enemy), EPSILON, "at most chaos_max_hits slams");
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON, "the enemy in the open is safe");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.ticks(20);
            for (Display left : entities(s, Display.class)) {
                assertTrue(left.getLocation().distance(s.owner.getLocation()) < 2.5,
                        "only the passive's floating things remain, no furniture: " + left.getLocation());
            }
            clearWall(s, -3, 4, 6, 2);
            s.finishClean();
        }
    }

    @Test
    void poltergeistUproarTearsTheCoverOutOnBattleMaps() {
        try (Scenario s = terrain(Scenario.builder("poltergeist")).start()) {
            s.world.wallAlongX(-3, 4, 6, 2, Material.BRICKS);
            s.clearHits();
            s.assertSucceeded(s.ultimate(), "대소동");
            s.ticks(14);
            assertTrue(s.h.hub.tempBlocks().removedCount() > 0, "the wall tears out");
            assertEquals(Material.AIR, s.world.getBlockAt(0, Y, 6).getType(), "right where the enemy hid by it");
            assertEquals(s.param("chaos_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "and slams the enemy");
            s.ticks(120);
            s.finishMatch();
            assertEquals(0, s.h.hub.tempBlocks().removedCount(), "the wall comes back at the end");
            assertEquals(Material.BRICKS, s.world.getBlockAt(0, Y, 6).getType());
            s.world.wallAlongX(-3, 4, 6, 2, Material.AIR);
            s.assertNoProblems();
            s.assertCleanWorld();
        }
    }

    // ================================================================== 블록 퍼즐러 blockfall

    private static final List<Material> PIECES = List.of(Material.STONE_BRICKS, Material.BRICKS, Material.OAK_PLANKS,
            Material.WAXED_CUT_COPPER, Material.MOSSY_STONE_BRICKS);

    /** Every block of a piece material around the arena's middle. */
    private static List<Block> pieceBlocks(Scenario s) {
        List<Block> found = new ArrayList<>();
        for (int x = -12; x <= 12; x++) {
            for (int z = -6; z <= 20; z++) {
                for (int y = Y; y <= Y + 4; y++) {
                    Block block = s.world.getBlockAt(x, y, z);
                    if (PIECES.contains(block.getType())) {
                        found.add(block);
                    }
                }
            }
        }
        return found;
    }

    private static List<String> where(List<Block> blocks) {
        return blocks.stream().map(b -> b.getX() + "," + b.getY() + "," + b.getZ()).toList();
    }

    @Test
    void blockfallPieceDriftsDownAndCrushesTheEnemyUnderIt() {
        try (Scenario s = Scenario.ffa("blockfall")) {
            s.place(s.enemy, 0.5, 6.5, 180);
            s.place(s.enemy2, 12.5, -8.5, 0);
            s.aimAt(s.owner, s.enemy);
            Location start = s.enemy.getLocation();
            s.clearHits();
            s.assertSucceeded(s.active(), "조각 떨구기");
            s.ticks(5);
            List<BlockDisplay> piece = entities(s, BlockDisplay.class);
            assertTrue(piece.size() == 3 || piece.size() == 5, "a piece of three or five: " + piece.size());
            for (BlockDisplay block : piece) {
                assertUnrotated(block, "piece block");
                assertTrue(block.getLocation().getY() > FlatWorld.SURFACE + 1.5, "still high up");
            }
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "it drifts down slowly");
            s.ticks(s.paramTicks("drop_time"));
            assertEquals(s.param("crush_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "crushed");
            s.ticks(12);
            assertTrue(s.enemy.getLocation().distance(start) > 0.4, "and shoved aside");
            List<Block> landed = pieceBlocks(s);
            assertEquals(piece.size(), landed.size(), "the piece is real blocks now: " + landed);
            for (Block block : landed) {
                assertEquals(Y, block.getY(), "on the ground");
                assertFalse(block.getBoundingBox().overlaps(s.enemy.getBoundingBox()), "never inside anyone");
            }
            assertTrue(entities(s, BlockDisplay.class).isEmpty(), "the falling piece is gone");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.ticks(s.paramTicks("piece_duration"));
            assertTrue(pieceBlocks(s).isEmpty(), "the piece crumbles after a while");
            s.finishClean();
        }
    }

    @Test
    void blockfallSneakSlamsThePieceDownAndStuns() {
        try (Scenario s = Scenario.ffa("blockfall")) {
            s.place(s.enemy, 0.5, 6.5, 180);
            s.place(s.enemy2, 12.5, -8.5, 0);
            s.aimAt(s.owner, s.enemy);
            s.clearHits();
            s.assertSucceeded(s.active(), "조각 떨구기");
            s.ticks(3);
            s.owner.setSneaking(true);
            s.ticks(4);
            assertEquals(s.param("slam_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "slammed down at once");
            assertEquals(0, s.enemy.getAttribute(Attribute.MOVEMENT_SPEED).getValue(), EPSILON, "stunned");
            assertEquals(0, s.enemy.getAttribute(Attribute.JUMP_STRENGTH).getValue(), EPSILON);
            s.owner.setSneaking(false);
            s.ticks(s.paramTicks("slam_stun") + 2);
            assertModifierFree(s.enemy, "the stun is short");
            s.finishClean();
        }
    }

    @Test
    void blockfallTurningSlidesTheFallingPiece() {
        try (Scenario s = Scenario.ffa("blockfall")) {
            s.place(s.enemy, 12.5, -8.5, 0);
            s.place(s.enemy2, -12.5, -8.5, 0);
            s.aimAtFloor(s.owner, 0.5, 8.5);
            s.assertSucceeded(s.active(), "조각 떨구기");
            s.ticks(2);
            Location turned = s.owner.getLocation();
            turned.setYaw(30);
            s.owner.teleport(turned);
            s.ticks(30);
            // Facing south (+z), turning right slides the piece west (-x): two cells for 30 degrees.
            assertTrue(PIECES.contains(s.world.getBlockAt(-2, Y, 8).getType()), "the piece's middle slid two cells: "
                    + pieceBlocks(s));
            s.finishClean();
        }
    }

    @Test
    void blockfallStackClearsAndBlowsUp() {
        try (Scenario s = Scenario.builder("blockfall").param("stack_height", 2).param("line_radius", 6).start()) {
            s.place(s.enemy, 4.5, 8.5, 0);
            s.place(s.enemy2, -12.5, -8.5, 0);
            s.aimAtFloor(s.owner, 0.5, 8.5);
            s.assertSucceeded(s.active(), "first piece");
            s.ticks(s.paramTicks("drop_time") + 6);
            assertTrue(PIECES.contains(s.world.getBlockAt(0, Y, 8).getType()), "landed");
            s.readyActive();
            s.aimAt(s.owner, new Location(s.world, 0.5, Y + 1, 8.5));
            s.clearHits();
            s.assertSucceeded(s.active(), "second piece, onto the first");
            s.ticks(s.paramTicks("drop_time") + 2);
            assertTrue(PIECES.contains(s.world.getBlockAt(0, Y + 1, 8).getType()), "stacked on top");
            s.ticks(10);
            assertEquals(Material.AIR, s.world.getBlockAt(0, Y, 8).getType(), "the stack cleared");
            assertEquals(Material.AIR, s.world.getBlockAt(0, Y + 1, 8).getType());
            assertTrue(s.hitsBy(s.owner, s.enemy).stream().anyMatch(hit -> Math.abs(hit.damage()
                    - s.param("line_damage")) < EPSILON), "and blew up next to the enemy: " + s.hits());
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.ticks(s.paramTicks("piece_duration"));
            s.finishClean();
        }
    }

    @Test
    void blockfallNewPiecesSnapNextToTheOldOnes() {
        try (Scenario s = Scenario.builder("blockfall").param("snap_range", 6).param("line_length", 40).start()) {
            s.place(s.enemy, 12.5, -8.5, 0);
            s.place(s.enemy2, -12.5, -8.5, 0);
            s.aimAtFloor(s.owner, 6.5, 6.5);
            s.assertSucceeded(s.active(), "first piece");
            s.ticks(s.paramTicks("drop_time") + 6);
            List<Block> first = pieceBlocks(s);
            assertFalse(first.isEmpty());
            s.readyActive();
            s.aimAtFloor(s.owner, 1.5, 10.5); // five blocks from the first piece's nearest possible column
            s.assertSucceeded(s.active(), "second piece");
            s.ticks(s.paramTicks("drop_time") + 6);
            List<Block> second = new ArrayList<>(pieceBlocks(s));
            second.removeAll(first);
            assertFalse(second.isEmpty(), "the second piece landed");
            boolean touching = false;
            for (Block block : second) {
                assertEquals(first.getFirst().getY(), block.getY(), "at the same height");
                for (Block old : first) {
                    touching |= Math.abs(old.getX() - block.getX()) + Math.abs(old.getZ() - block.getZ()) == 1;
                }
            }
            assertTrue(touching, "snapped right next to the first: " + where(first) + " / " + where(second));
            s.ticks(s.paramTicks("piece_duration"));
            s.finishClean();
        }
    }

    @Test
    void blockfallAllClearFillsTheBoardThenBlowsItUp() {
        try (Scenario s = Scenario.ffa("blockfall")) {
            s.place(s.enemy2, 12.5, -8.5, 0);
            s.aimAt(s.owner, s.enemy);
            s.clearHits();
            s.assertSucceeded(s.ultimate(), "올 클리어");
            s.ticks(5);
            assertEquals(4, entities(s, BlockDisplay.class).size(), "the board's glowing frame");
            for (BlockDisplay edge : entities(s, BlockDisplay.class)) {
                assertUnrotated(edge, "frame");
                assertTrue(edge.isGlowing(), "seen by everyone");
            }
            boolean bars = false;
            for (int i = 0; i < 70; i++) {
                s.ticks(1);
                bars |= !pieceBlocks(s).isEmpty();
            }
            assertTrue(bars, "bars filled the board");
            assertEquals(s.param("bar_damage") + s.param("clear_damage"), s.damageBy(s.owner, s.enemy), EPSILON,
                    "crushed once by a bar, then all clear: " + s.hitsBy(s.owner, s.enemy));
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON, "off the board, safe");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            assertEquals(0, s.h.hub.tempBlocks().size(), "the board burst");
            s.ticks(40);
            assertNoDisplays(s, "board");
            s.finishClean();
        }
    }

    // ================================================================== helpers

    /**
     * The left rotation a display's transformation was set with. Older MockBukkit hands it back conjugated, a faithful
     * test double does not: a probe display tells which, so the check holds with either.
     */
    static Quaternionf leftRotation(Display display) {
        Quaternionf read = new Quaternionf(display.getTransformation().getLeftRotation());
        BlockDisplay probe = display.getWorld().spawn(display.getLocation(), BlockDisplay.class);
        try {
            probe.setTransformation(new Transformation(new Vector3f(), new Quaternionf().rotateY(1.0f),
                    new Vector3f(1), new Quaternionf()));
            Vector3f turned = new Quaternionf(probe.getTransformation().getLeftRotation())
                    .transform(new Vector3f(0, 0, 1));
            return turned.x() < 0 ? read.conjugate() : read;
        } finally {
            probe.remove();
        }
    }

    /** The center of a block display's box relative to the display's own position. */
    static Vector3f boxCenter(BlockDisplay display) {
        Transformation t = display.getTransformation();
        return new Vector3f(t.getTranslation()).add(leftRotation(display).transform(new Vector3f(t.getScale())
                .mul(0.5f)));
    }

    static Scenario.Builder terrain(Scenario.Builder builder) {
        return builder.configure(h -> h.hub.config().setAbilityBlockDamage(BlockDamageScope.ALL_WORLDS));
    }
}
