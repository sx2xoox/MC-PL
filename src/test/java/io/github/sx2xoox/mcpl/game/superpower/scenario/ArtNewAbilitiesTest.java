package io.github.sx2xoox.mcpl.game.superpower.scenario;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.lang.reflect.Field;
import java.util.List;
import java.util.stream.Stream;

import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Spots;
import io.github.sx2xoox.mcpl.game.superpower.ability.Ability;
import io.github.sx2xoox.mcpl.game.superpower.ability.ActivationResult;
import io.github.sx2xoox.mcpl.game.superpower.support.Scenario;
import io.github.sx2xoox.mcpl.game.superpower.support.TestPlayer;
import io.github.sx2xoox.mcpl.game.superpower.support.world.FlatWorld;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockbukkit.mockbukkit.sound.AudioExperience;
import org.mockbukkit.mockbukkit.util.SpawnedParticle;

/** 자연·예술 계열 신규 능력: 버섯술사, 크리스탈술사, 화가. */
class ArtNewAbilitiesTest {

    private static final double EPSILON = 1.0E-6;
    /** Far away from everything the skills under test touch. */
    private static final double FAR = 14.5;

    static Stream<String> abilities() {
        return Stream.of("mycologist", "crystal", "painter");
    }

    /**
     * {@link AbilityLifecycleTest} lists the abilities in a static initializer that mocks a server of its own. This
     * class calls into it from inside running scenarios, where that initializer would fail ("Already mocking") and
     * take every test using it down when this class runs on its own, so it is loaded here first, while no server is
     * mocked.
     */
    @BeforeAll
    static void loadTheSweepsOutsideAScenario() {
        assertFalse(AbilityLifecycleTest.abilities().toList().isEmpty(), "the built-in abilities are listed");
    }

    // ------------------------------------------------------------------ helpers

    private static long displays(Scenario s) {
        return s.world.getEntities().stream().filter(entity -> entity instanceof Display).count();
    }

    /** Every visual display entity has a bounded lifetime: none is left once the effect is over. */
    private static void assertVisualsGone(Scenario s, String when) {
        assertEquals(0, displays(s), "no display entity is left " + when);
    }

    private static double speed(Player player) {
        return player.getAttribute(Attribute.MOVEMENT_SPEED).getValue();
    }

    private static double baseSpeed(Player player) {
        return player.getAttribute(Attribute.MOVEMENT_SPEED).getBaseValue();
    }

    /** No attribute modifier on {@code player} but the scenario's tank health. */
    private static void assertModifierFree(Player player, String what) {
        for (Attribute attribute : Registry.ATTRIBUTE) {
            AttributeInstance instance = player.getAttribute(attribute);
            if (instance == null) {
                continue;
            }
            for (AttributeModifier modifier : instance.getModifiers()) {
                if (!modifier.getKey().equals(Scenario.TANK)) {
                    fail(what + ": " + player.getName() + " kept " + modifier.getKey() + " on " + attribute.getKey());
                }
            }
        }
    }

    /** Hits by the owner's ability (MAGIC), ignoring melee and other uncredited damage. */
    private static List<Scenario.Hit> magicHits(Scenario s, Entity victim) {
        return s.hitsBy(s.owner, victim).stream().filter(hit -> hit.cause() == DamageCause.MAGIC).toList();
    }

    private static double magicDamage(Scenario s, Entity victim) {
        return magicHits(s, victim).stream().mapToDouble(Scenario.Hit::damage).sum();
    }

    /**
     * Displays orient through their transformation only: an entity rotation would turn the model a second time on a
     * real client.
     */
    private static void assertUnrotated(Display display, String what) {
        assertEquals(0, display.getLocation().getYaw(), 1.0E-6, what + ": display yaw");
        assertEquals(0, display.getLocation().getPitch(), 1.0E-6, what + ": display pitch");
    }

    /**
     * The model matrix of a display (translation, left rotation, scale, right rotation). MockBukkit's
     * {@code DisplayMock#getTransformation} cannot be used to read it back: it decomposes its matrix and then
     * conjugates the rotation in place, returning the <em>inverse</em> rotation as both the left and the right
     * rotation. So the matrix it keeps is read directly; the API is only the fallback.
     */
    private static Matrix4f model(Display display) {
        for (Class<?> type = display.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField("transformationMatrix");
                field.setAccessible(true);
                return new Matrix4f((Matrix4f) field.get(display));
            } catch (NoSuchFieldException e) {
                // keep looking up the hierarchy
            } catch (IllegalAccessException e) {
                break;
            }
        }
        Transformation pose = display.getTransformation();
        return new Matrix4f().translation(pose.getTranslation()).rotate(pose.getLeftRotation())
                .scale(pose.getScale()).rotate(pose.getRightRotation());
    }

    /** A point of an (unrotated) display's model, in model coordinates (a block spans 0..1), in the world. */
    private static Vector worldPoint(Display display, float x, float y, float z) {
        Vector3f point = model(display).transformPosition(new Vector3f(x, y, z));
        Location at = display.getLocation();
        return new Vector(at.getX() + point.x, at.getY() + point.y, at.getZ() + point.z);
    }

    /** Where a direction of the (unrotated) display's model points in the world, normalized. */
    private static Vector3f worldDirection(Display display, float x, float y, float z) {
        return model(display).transformDirection(new Vector3f(x, y, z)).normalize();
    }

    private static double horizontalDistance(Vector point, Location axis) {
        return Math.hypot(point.getX() - axis.getX(), point.getZ() - axis.getZ());
    }

    private static List<BlockDisplay> blockDisplays(Scenario s, Material material) {
        return s.world.getEntities().stream()
                .filter(entity -> entity instanceof BlockDisplay block && block.getBlock().getMaterial() == material)
                .map(entity -> (BlockDisplay) entity).toList();
    }

    /** Feet exactly on the floor and the body clear of every block. */
    private static void assertOnSurface(Player player, String what) {
        Location at = player.getLocation();
        assertEquals(FlatWorld.SURFACE, at.getY(), 1.0E-6, what + ": feet on the surface " + at);
        assertTrue(Spots.isFree(at.getWorld(), at, player.getWidth(), player.getHeight()),
                what + ": the body is not inside a block " + at);
    }

    /** Exactly what {@code GamblerAbility#fireBorrowed} does. */
    private static ActivationResult borrow(Scenario s, String id) {
        Ability borrowed = s.ctx().registry().create(id);
        borrowed.bind(s.owner, s.ctx(), s.ctx().paramsFor(id));
        return borrowed.onUltimate();
    }

    private static Scenario.Builder gambler() {
        // No lucky dodge / double damage: the borrowed ultimate alone decides what happens.
        return Scenario.builder("gambler").param("dodge_chance", 0).param("crit_chance", 0);
    }

    /** Puts the enemies where the skills reach them (the aimed enemy 4 blocks ahead), and aims. */
    private static void prepare(Scenario s) {
        s.layout();
        if (s.ally != null) {
            s.crossfireAlly();
        }
        s.aimAt(s.owner, s.enemy);
    }

    /** How long the active's leftovers (mines, shards, puddles) may stay before they are gone. */
    private static int activeAftermath(Scenario s, String id) {
        return switch (id) {
            case "mycologist" -> s.paramTicks("mine_arm") + s.paramTicks("mine_duration") + 60;
            case "crystal" -> s.paramTicks("shard_duration") + 60;
            default -> s.paramTicks("puddle_duration") + 60;
        };
    }

    /**
     * Kills the owner mid-skill: everything the skill created disappears with the owner's ability, the dead owner
     * deals no more damage, and nothing outlives the timed effects.
     */
    private static void assertOwnerDeathCleansUp(Scenario s, String what) {
        s.killOwner(s.enemy);
        s.ticks(1);
        for (Entity entity : s.world.getEntities()) {
            assertTrue(entity instanceof Player, what + ": the dead owner's " + entity.getType() + " is removed");
        }
        s.clearHits();
        s.ticks(300);
        for (Scenario.Hit hit : s.hits()) {
            if (s.owner.equals(hit.causer())) {
                fail(what + ": the dead owner still dealt damage: " + hit);
            }
        }
        assertEquals(0, s.h.hub.tempBlocks().size(), what);
        for (TestPlayer enemy : s.enemies()) {
            assertModifierFree(enemy, what);
            assertFalse(s.ctx().isSilenced(enemy), what);
        }
        assertModifierFree(s.owner, what);
        s.assertNoProblems();
        s.finishClean();
    }

    // ------------------------------------------------------------------ every ability

    @ParameterizedTest(name = "{0}")
    @MethodSource("abilities")
    void wholeMatchHurtsEnemiesButNeverTheOwner(String id) {
        try (Scenario s = Scenario.ffa(id)) {
            s.ticks(40);
            prepare(s);
            s.clearHits();
            s.assertSucceeded(s.active(), id + " active");
            s.ticks(60);
            assertTrue(s.damageBy(s.owner, s.enemy) > 0, id + " active hurts the aimed enemy: " + s.hits());
            assertEquals(0, s.damageTaken(s.owner), EPSILON, id + " active never hurts its owner: " + s.hits());
            assertTrue(displays(s) <= 24, id + " stays within the display budget");
            s.ticks(activeAftermath(s, id));
            assertVisualsGone(s, "after the " + id + " active");

            prepare(s);
            s.healAll();
            s.clearHits();
            s.assertSucceeded(s.ultimate(), id + " ultimate");
            s.ticks(30);
            assertTrue(displays(s) > 0, id + " ultimate shows display entities");
            assertTrue(displays(s) <= 24, id + " ultimate stays within the display budget: " + displays(s));
            s.ticks(270);
            assertTrue(s.damageBy(s.owner, s.enemy) > 0, id + " ultimate hurts the aimed enemy");
            assertEquals(0, s.damageTaken(s.owner), EPSILON, id + " ultimate never hurts its owner: " + s.hits());
            assertVisualsGone(s, "after the " + id + " ultimate");
            for (TestPlayer enemy : s.enemies()) {
                assertModifierFree(enemy, id);
            }
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("abilities")
    void teamModeNeverHarmsTheAlly(String id) {
        try (Scenario s = Scenario.teams(id)) {
            s.ticks(40);
            prepare(s);
            Location allyStart = s.ally().getLocation();
            s.clearHits();
            s.assertSucceeded(s.active(), id + " active");
            AbilityLifecycleTest.watchAlly(s, allyStart, 120);

            prepare(s);
            allyStart = s.ally().getLocation();
            s.assertSucceeded(s.ultimate(), id + " ultimate");
            AbilityLifecycleTest.watchAlly(s, allyStart, 240);
            assertModifierFree(s.ally(), id);
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("abilities")
    void ownerDiesDuringActive(String id) {
        try (Scenario s = Scenario.ffa(id)) {
            s.ticks(20);
            prepare(s);
            s.assertSucceeded(s.active(), id + " active");
            s.ticks(12);
            assertOwnerDeathCleansUp(s, id + " active");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("abilities")
    void ownerDiesDuringUltimate(String id) {
        try (Scenario s = Scenario.ffa(id)) {
            s.ticks(20);
            prepare(s);
            s.assertSucceeded(s.ultimate(), id + " ultimate");
            s.ticks(id.equals("painter") ? 30 : 16);
            assertOwnerDeathCleansUp(s, id + " ultimate");
        }
    }

    // ------------------------------------------------------------------ gambler borrows the ultimate

    @ParameterizedTest(name = "{0}")
    @MethodSource("abilities")
    void everyUltimateIsStandalone(String id) {
        try (Scenario s = Scenario.ffa(id)) {
            assertTrue(s.ability().info().ultimateStandalone(), id + " can be borrowed by the gambler");
            s.finishClean();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("abilities")
    void borrowedUltimateWorksOnAGambler(String id) {
        try (Scenario s = gambler().start()) {
            prepare(s);
            s.assertSucceeded(borrow(s, id), "borrowed " + id);
            s.ticks(300);
            assertTrue(s.damageBy(s.owner, s.enemy) + s.damageBy(s.owner, s.enemy2) > 0,
                    "the borrowed " + id + " hurts enemies: " + s.hits());
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "the borrowed " + id + " never hurts the gambler");
            assertModifierFree(s.owner, id);
            for (TestPlayer target : s.enemies()) {
                assertModifierFree(target, id);
            }
            assertVisualsGone(s, "after the borrowed " + id);
            assertEquals(0, s.h.hub.tempBlocks().size(), id);
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("abilities")
    void borrowedUltimateNeverHarmsTheGamblersAlly(String id) {
        try (Scenario s = gambler().teams().start()) {
            prepare(s);
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
    @MethodSource("abilities")
    void gamblerDyingDuringABorrowedUltimateLeavesNothingBehind(String id) {
        try (Scenario s = gambler().start()) {
            prepare(s);
            s.assertSucceeded(borrow(s, id), "borrowed " + id);
            s.ticks(16);
            assertOwnerDeathCleansUp(s, "borrowed " + id);
        }
    }

    // ------------------------------------------------------------------ 버섯술사 mycologist

    @Test
    void mycologistSporesMakeMeleeVictimsDizzyOncePerCooldown() {
        try (Scenario s = Scenario.ffa("mycologist")) {
            s.melee(s.owner, s.enemy, 1);
            assertTrue(s.enemy.hasPotionEffect(PotionEffectType.NAUSEA), "the spores make the enemy dizzy");
            assertFalse(s.enemy2.hasPotionEffect(PotionEffectType.NAUSEA), "nobody else");
            s.ticks(2);
            assertEquals(1, displays(s), "a little toadstool sprouts on its head");

            s.enemy.removePotionEffect(PotionEffectType.NAUSEA);
            s.melee(s.owner, s.enemy, 1);
            assertFalse(s.enemy.hasPotionEffect(PotionEffectType.NAUSEA), "not again before the spore cooldown");
            s.ticks(s.paramTicks("spore_cooldown"));
            s.melee(s.owner, s.enemy, 1);
            assertTrue(s.enemy.hasPotionEffect(PotionEffectType.NAUSEA), "the cooldown is over");

            s.ticks(s.paramTicks("spore_nausea") + 10);
            assertVisualsGone(s, "after the spores wore off");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.finishClean();
        }
    }

    @Test
    void mycologistToadstoolKeepsItsOwnRotationOnATurningHead() {
        try (Scenario s = Scenario.ffa("mycologist")) {
            s.place(s.enemy, 0.5, FlatWorld.SURFACE, 4.5, 77, 30);
            s.melee(s.owner, s.enemy, 1);
            s.ticks(6);
            List<Entity> shown = s.world.getEntities().stream().filter(entity -> entity instanceof Display).toList();
            assertEquals(1, shown.size(), "one toadstool");
            Display cap = (Display) shown.getFirst();
            assertUnrotated(cap, "toadstool");
            assertEquals(4.5, cap.getLocation().getZ(), 1.0E-6, "on the enemy's head");
            assertEquals(FlatWorld.SURFACE + s.enemy.getHeight(), cap.getLocation().getY(), 0.05, "on top of it");
            s.place(s.enemy, 2.5, FlatWorld.SURFACE, 5.5, -140, -20);
            s.ticks(4);
            assertUnrotated(cap, "toadstool after the head turned");
            assertEquals(2.5, cap.getLocation().getX(), 1.0E-6, "it follows the head");
            assertEquals(5.5, cap.getLocation().getZ(), 1.0E-6, "it follows the head");
            s.ticks(s.paramTicks("spore_nausea") + 10);
            s.finishClean();
        }
    }

    @Test
    void mycologistMinesArmThenBurstUnderTheAimedEnemy() {
        try (Scenario s = Scenario.ffa("mycologist")) {
            s.place(s.enemy2, FAR, FAR, 180);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "버섯 지뢰");
            s.ticks(20);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "the mines still have to grow");
            assertEquals(9, displays(s), "three mushrooms (patch, stem and cap each) in a row");
            s.ticks(20);
            List<Scenario.Hit> hits = magicHits(s, s.enemy);
            assertEquals(1, hits.size(), "the middle mine bursts under the enemy: " + s.hits());
            assertEquals(s.param("mine_damage"), hits.getFirst().damage(), EPSILON);
            assertTrue(s.enemy.hasPotionEffect(PotionEffectType.NAUSEA), "dizzy");
            assertTrue(s.enemy.hasPotionEffect(PotionEffectType.SLOWNESS), "and slowed");
            s.ticks(20);
            assertEquals(6, displays(s), "the two other mines are still waiting");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON);
            s.ticks(s.paramTicks("mine_duration") + 20);
            assertVisualsGone(s, "after the unused mines withered");
            assertEquals(s.param("mine_damage"), s.damageTaken(s.enemy), EPSILON, "withering mines hurt nobody");
            s.finishClean();
        }
    }

    @Test
    void mycologistMinesBurstOnEnemiesWalkingIntoThem() {
        try (Scenario s = Scenario.ffa("mycologist")) {
            s.place(s.enemy, FAR, FAR, 180);
            s.place(s.enemy2, -FAR, FAR, 180);
            s.aimAtFloor(s.owner, 0.5, 6.5);
            s.assertSucceeded(s.active(), "버섯 지뢰");
            s.ticks(60);
            assertEquals(0, s.damageTaken(s.enemy) + s.damageTaken(s.enemy2), EPSILON);
            // Walking onto the right-hand mine (the row runs across the view).
            s.place(s.enemy, 0.5 - s.param("mine_spread"), 6.5, 180);
            s.ticks(12);
            assertEquals(s.param("mine_damage"), magicDamage(s, s.enemy), EPSILON, "the trap springs");
            s.ticks(12);
            assertEquals(6, displays(s), "only that mine is gone");
            s.finishClean();
        }
    }

    @Test
    void mycologistMinesIgnoreTheOwnerAndAllies() {
        try (Scenario s = Scenario.teams("mycologist")) {
            s.place(s.enemy, FAR, FAR, 180);
            s.place(s.enemy2, -FAR, FAR, 180);
            s.aimAtFloor(s.owner, 0.5, 5.5);
            s.assertSucceeded(s.active(), "버섯 지뢰");
            s.ticks(40);
            Location allyStart = new Location(s.world, 0.5, FlatWorld.SURFACE, 5.5);
            s.place(s.ally(), 0.5, 5.5, 0);
            s.place(s.owner, 0.5 + s.param("mine_spread"), 5.5, 0);
            AbilityLifecycleTest.watchAlly(s, allyStart, 40);
            assertEquals(9, displays(s), "neither the owner nor the ally sets a mine off");
            // An enemy right next to the ally: the burst spares the ally.
            s.place(s.enemy, 0.5, 6.2, 180);
            AbilityLifecycleTest.watchAlly(s, allyStart, 20);
            assertEquals(s.param("mine_damage"), magicDamage(s, s.enemy), EPSILON);
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.finishClean();
        }
    }

    @Test
    void mycologistSporedEnemiesTakeMoreMineDamage() {
        try (Scenario s = Scenario.ffa("mycologist")) {
            s.place(s.enemy2, FAR, FAR, 180);
            s.melee(s.owner, s.enemy, 1);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "버섯 지뢰");
            s.ticks(40);
            assertEquals(s.param("mine_damage") * (1 + s.param("spore_bonus") / 100), magicDamage(s, s.enemy),
                    EPSILON, "the spores make the burst hurt more");
            s.finishClean();
        }
    }

    @Test
    void mycologistMinesSkipEnemiesHiddenByStealth() {
        try (Scenario s = Scenario.ffa("mycologist")) {
            s.place(s.enemy2, FAR, FAR, 180);
            s.owner.hidePlayer(s.h.hub.plugin(), s.enemy);
            s.aimAtFloor(s.owner, 0.5, 4.5);
            s.assertSucceeded(s.active(), "버섯 지뢰");
            s.ticks(60);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "an enemy the owner cannot see sneaks over the mine");
            s.owner.showPlayer(s.h.hub.plugin(), s.enemy);
            s.ticks(12);
            assertEquals(s.param("mine_damage"), magicDamage(s, s.enemy), EPSILON, "seen again: the mine bursts");
            s.finishClean();
        }
    }

    @Test
    void mycologistReplantingWithersTheOldMines() {
        try (Scenario s = Scenario.ffa("mycologist")) {
            s.place(s.enemy, FAR, FAR, 180);
            s.place(s.enemy2, -FAR, FAR, 180);
            s.aimAtFloor(s.owner, 0.5, 5.5);
            s.assertSucceeded(s.active(), "버섯 지뢰");
            s.ticks(30);
            s.readyActive();
            s.aimAtFloor(s.owner, 0.5, -5.5);
            s.assertSucceeded(s.active(), "버섯 지뢰");
            s.ticks(40);
            assertEquals(9, displays(s), "only the new row of mines is left");
            for (Entity entity : s.world.getEntities()) {
                if (entity instanceof Display) {
                    assertTrue(entity.getLocation().getZ() < 0, "the old mines withered: " + entity.getLocation());
                }
            }
            s.finishClean();
        }
    }

    @Test
    void mycologistGiantMushroomLaunchesThenStingsHarderTheLongerEnemiesStay() {
        try (Scenario s = Scenario.ffa("mycologist")) {
            double base = baseSpeed(s.enemy);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "거대 버섯");
            s.ticks(12);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "the ground only rumbles at first");
            s.ticks(1);
            for (TestPlayer target : s.enemies()) {
                assertEquals(s.param("erupt_damage"), magicDamage(s, target), EPSILON,
                        target.getName() + " is caught by the eruption");
            }
            s.ticks(3);
            assertTrue(s.enemy.getLocation().getY() > FlatWorld.SURFACE + 0.3, "and thrown up: " + s.enemy.getLocation());
            assertTrue(displays(s) >= 4, "a giant mushroom stands there");
            s.ticks(20);
            assertTrue(speed(s.enemy) < base, "the spore cloud slows");
            s.ticks(s.paramTicks("ult_duration") + 10);
            double dose = 0;
            double sting = s.param("ult_damage");
            for (int pulse = 0; pulse < s.paramTicks("ult_duration") / s.paramTicks("ult_interval"); pulse++) {
                dose += Math.min(s.param("ult_damage_max"), sting + pulse * s.param("ult_ramp"));
            }
            double expected = s.param("erupt_damage") + dose + s.param("burst_damage");
            for (TestPlayer target : s.enemies()) {
                assertEquals(expected, magicDamage(s, target), EPSILON,
                        target.getName() + ": eruption, growing stings and the final burst: " + magicHits(s, target));
                assertTrue(target.hasPotionEffect(PotionEffectType.NAUSEA), "dizzy from the spores");
            }
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.ticks(30);
            assertVisualsGone(s, "after the mushroom sank");
            for (TestPlayer target : s.enemies()) {
                assertModifierFree(target, "after the cloud");
            }
            s.finishClean();
        }
    }

    @Test
    void mycologistCloudLetsGoOfEnemiesThatLeaveAndResetsTheirDose() {
        try (Scenario s = Scenario.ffa("mycologist")) {
            s.place(s.enemy2, FAR, FAR, 180);
            double base = baseSpeed(s.enemy);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "거대 버섯");
            s.ticks(13 + 25);
            assertTrue(speed(s.enemy) < base, "slowed inside");
            s.place(s.enemy, FAR, -FAR, 0);
            s.ticks(3);
            assertEquals(base, speed(s.enemy), 1.0E-9, "the slow ends right after leaving");
            s.ticks(s.paramTicks("ult_interval") + 2);
            s.place(s.enemy, 0.5, 4.5, 180);
            s.clearHits();
            s.ticks(s.paramTicks("ult_interval"));
            List<Scenario.Hit> stings = magicHits(s, s.enemy);
            assertEquals(1, stings.size(), "one sting after coming back: " + stings);
            assertEquals(s.param("ult_damage"), stings.getFirst().damage(), EPSILON, "the dose started over");
            s.ticks(120);
            s.finishClean();
        }
    }

    @Test
    void mycologistGiantMushroomAimedAtTheSkyGrowsUnderTheOwner() {
        try (Scenario s = Scenario.ffa("mycologist")) {
            s.place(s.enemy, 0.5, 2.0, 180);
            s.place(s.enemy2, FAR, FAR, 180);
            s.place(s.owner, 0.5, FlatWorld.SURFACE, 0.5, 0, -70);
            s.assertSucceeded(s.ultimate(), "거대 버섯");
            s.ticks(14);
            assertEquals(s.param("erupt_damage"), magicDamage(s, s.enemy), EPSILON, "erupts at the owner's feet");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            for (BlockDisplay part : blockDisplays(s, Material.MUSHROOM_STEM)) {
                assertUnrotated(part, "giant stem");
                assertEquals(0.5, worldPoint(part, 0.5f, 0.5f, 0.5f).getX(), 1.0E-3, "rooted under the owner");
            }
            s.tickUntilGrounded(s.enemy, 60);
            assertOnSurface(s.enemy, "the launched enemy lands");
            s.ticks(150);
            assertVisualsGone(s, "after the mushroom sank");
            s.finishClean();
        }
    }

    @Test
    void mycologistCloudHealsTheOwnerAndAllies() {
        try (Scenario s = Scenario.teams("mycologist")) {
            prepare(s);
            s.owner.setHealth(8);
            s.ally().setHealth(8);
            s.assertSucceeded(s.ultimate(), "거대 버섯");
            s.ticks(13 + s.paramTicks("ult_duration") + 10);
            double healed = s.param("ult_heal") * (s.paramTicks("ult_duration") / s.paramTicks("ult_interval"));
            assertEquals(8 + healed, s.owner.getHealth(), EPSILON, "the owner inside the cloud is healed");
            assertEquals(8 + healed, s.ally().getHealth(), EPSILON, "and so is the ally");
            assertEquals(0, s.damageTaken(s.ally()), EPSILON);
            s.finishClean();
        }
    }

    // ------------------------------------------------------------------ 크리스탈술사 crystal

    @Test
    void crystalSpikesRunAlongTheGroundAndLaunchTheEnemiesOnTheirLine() {
        try (Scenario s = Scenario.ffa("crystal")) {
            s.place(s.enemy2, 4.5, 8.5, 180);
            s.assertSucceeded(s.active(), "크리스탈 가시");
            s.ticks(4);
            assertEquals(s.param("spike_damage"), magicDamage(s, s.enemy), EPSILON, "the spikes reach the enemy");
            s.ticks(3);
            assertTrue(s.enemy.getLocation().getY() > FlatWorld.SURFACE + 0.3, "launched: " + s.enemy.getLocation());
            assertTrue(displays(s) >= 8, "a line of crystal spikes: " + displays(s));
            assertTrue(displays(s) <= 24, "within the display budget: " + displays(s));
            s.tickUntilGrounded(s.enemy, 40);
            assertOnSurface(s.enemy, "the launched enemy lands");
            s.ticks(30);
            assertEquals(s.param("spike_damage"), magicDamage(s, s.enemy), EPSILON, "one hit per cast");
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON, "beside the line: untouched");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            assertEquals(3, displays(s), "three spikes stay behind as resonating crystals");
            s.ticks(s.paramTicks("shard_duration") + 20);
            assertVisualsGone(s, "after the crystals faded");
            s.finishClean();
        }
    }

    @Test
    void crystalSpikesStopAtWalls() {
        try (Scenario s = Scenario.ffa("crystal")) {
            s.world.wallAlongX(-2, 3, 3, 2, Material.STONE);
            s.assertSucceeded(s.active(), "크리스탈 가시");
            s.ticks(40);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "the wall stops the spikes");
            s.ticks(s.paramTicks("shard_duration") + 20);
            s.world.wallAlongX(-2, 3, 3, 2, Material.AIR);
            s.finishClean();
        }
    }

    @Test
    void crystalSpikesClimbSteps() {
        try (Scenario s = Scenario.ffa("crystal")) {
            for (int x = -1; x <= 2; x++) {
                for (int z = 3; z <= 8; z++) {
                    s.world.setBlock(x, FlatWorld.FLOOR_Y + 1, z, Material.STONE);
                }
            }
            s.place(s.enemy, 0.5, FlatWorld.SURFACE + 1, 5.5, 180, 0);
            s.assertSucceeded(s.active(), "크리스탈 가시");
            s.ticks(10);
            assertEquals(s.param("spike_damage"), magicDamage(s, s.enemy), EPSILON, "up the step and on");
            s.ticks(s.paramTicks("shard_duration") + 40);
            for (int x = -1; x <= 2; x++) {
                for (int z = 3; z <= 8; z++) {
                    s.world.setBlock(x, FlatWorld.FLOOR_Y + 1, z, Material.AIR);
                }
            }
            s.finishClean();
        }
    }

    @Test
    void crystalResonanceSpeedsUpTheCooldownNearItsCrystals() {
        try (Scenario s = Scenario.ffa("crystal")) {
            s.place(s.enemy, FAR, FAR, 180);
            s.place(s.enemy2, -FAR, FAR, 180);
            s.assertSucceeded(s.active(), "크리스탈 가시");
            s.ticks(20);
            double far = s.ctx().cooldownRemaining(s.owner);
            assertEquals(s.param("cooldown") - 1, far, 0.11, "no crystal in reach: the cooldown runs normally");
            // Next to the crystal left at the 4th spike.
            s.place(s.owner, 0.5, 6.0, 0);
            s.ticks(40);
            double near = s.ctx().cooldownRemaining(s.owner);
            double expected = far - 2 * (1 + s.param("resonance_bonus") / 100);
            assertEquals(expected, near, 0.35, "resonating: the cooldown runs faster");
            s.ticks(s.paramTicks("shard_duration") + 20);
            s.finishClean();
        }
    }

    @Test
    void crystalResonatesWithAmethystInTheWorld() {
        try (Scenario s = Scenario.ffa("crystal")) {
            s.place(s.enemy, FAR, FAR, 180);
            s.place(s.enemy2, -FAR, FAR, 180);
            s.world.setBlock(2, FlatWorld.FLOOR_Y + 1, 0, Material.AMETHYST_BLOCK);
            s.ticks(25);
            s.assertSucceeded(s.active(), "크리스탈 가시");
            s.ticks(40);
            double left = s.ctx().cooldownRemaining(s.owner);
            assertTrue(left < s.param("cooldown") - 2 - 0.8, "the amethyst block resonates: " + left);
            s.ticks(s.paramTicks("shard_duration") + 20);
            s.world.setBlock(2, FlatWorld.FLOOR_Y + 1, 0, Material.AIR);
            s.finishClean();
        }
    }

    @Test
    void crystalPrisonHoldsTheTargetThenShatters() {
        try (Scenario s = Scenario.ffa("crystal")) {
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "크리스탈 감옥");
            s.ticks(8);
            assertEquals(0, speed(s.enemy), 1.0E-9, "the prisoner cannot walk");
            assertEquals(0, s.enemy.getAttribute(Attribute.JUMP_STRENGTH).getValue(), 1.0E-9, "nor jump");
            assertTrue(displays(s) >= 7, "a cage of crystal clusters in a glass shell: " + displays(s));
            assertTrue(displays(s) <= 24, "within the display budget");
            s.enemy.teleport(s.enemy.getLocation().add(1.2, 0, 0));
            s.ticks(1);
            assertEquals(0.5, s.enemy.getLocation().getX(), 0.36, "shoved prisoners are put back in their cell");
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "no damage until it shatters");
            s.ticks(s.paramTicks("prison_duration") - 9 + 2);
            assertEquals(s.param("shatter_damage"), magicDamage(s, s.enemy), EPSILON, "the prisoner takes the burst");
            assertEquals(s.param("splash_damage"), magicDamage(s, s.enemy2), EPSILON, "and the enemy beside it");
            assertModifierFree(s.enemy, "released");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.ticks(s.paramTicks("shard_duration") + 40);
            assertVisualsGone(s, "after the prison and its crystals");
            s.finishClean();
        }
    }

    @Test
    void crystalCageIsUnrotatedAndItsClustersLeanOverThePrisoner() {
        try (Scenario s = Scenario.ffa("crystal")) {
            // A turned prisoner: its rotation must not leak into the cage.
            s.place(s.enemy, 0.5, FlatWorld.SURFACE, 4.5, 77, 25);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "크리스탈 감옥");
            s.ticks(8);
            Location axis = s.enemy.getLocation();
            int bars = 0;
            for (Entity entity : s.world.getEntities()) {
                if (entity instanceof Display display) {
                    assertUnrotated(display, "cage part " + display.getType());
                }
            }
            for (BlockDisplay cluster : blockDisplays(s, Material.AMETHYST_CLUSTER)) {
                Vector foot = worldPoint(cluster, 0.5f, 0, 0.5f);
                Vector tip = worldPoint(cluster, 0.5f, 1, 0.5f);
                if (horizontalDistance(foot, axis) < 0.4) {
                    continue; // the crown hanging over the head
                }
                bars++;
                assertEquals(FlatWorld.SURFACE, foot.getY(), 0.1, "a cluster stands on the ground");
                assertTrue(tip.getY() > foot.getY() + 1.5, "and rises upwards");
                assertTrue(horizontalDistance(tip, axis) < horizontalDistance(foot, axis) - 0.4,
                        "leaning in over the prisoner: foot " + foot + ", tip " + tip);
            }
            assertEquals(6, bars, "six clusters around the prisoner");
            s.ticks(s.paramTicks("prison_duration") + s.paramTicks("shard_duration") + 40);
            s.finishClean();
        }
    }

    @Test
    void crystalPrisonPinsAJumpingEnemyExactlyOntoTheGround() {
        try (Scenario s = Scenario.ffa("crystal")) {
            s.place(s.enemy, 0.5, FlatWorld.SURFACE + 0.6, 4.5, 180, 0);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "크리스탈 감옥");
            s.ticks(1);
            assertOnSurface(s.enemy, "pinned onto the cell floor");
            s.ticks(20);
            assertOnSurface(s.enemy, "held on the floor");
            s.ticks(s.paramTicks("prison_duration"));
            s.tickUntilGrounded(s.enemy, 60);
            assertOnSurface(s.enemy, "after the cage shattered");
            s.ticks(s.paramTicks("shard_duration") + 40);
            s.finishClean();
        }
    }

    @Test
    void crystalPrisonLiftsAnEnemySunkIntoTheFloorOntoTheSurface() {
        try (Scenario s = Scenario.ffa("crystal")) {
            // A server position a hair inside the floor, as a real client can leave it.
            s.place(s.enemy, 0.5, FlatWorld.SURFACE - 0.004, 4.5, 180, 0);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "크리스탈 감옥");
            s.ticks(1);
            assertOnSurface(s.enemy, "set onto the surface, not held in the ground");
            s.enemy.teleport(s.enemy.getLocation().add(0.8, 0, 0));
            s.ticks(1);
            assertOnSurface(s.enemy, "put back onto the surface");
            s.ticks(s.paramTicks("prison_duration") + s.paramTicks("shard_duration") + 40);
            s.finishClean();
        }
    }

    @Test
    void crystalPrisonShattersEarlyWhenThePrisonerBreaksOut() {
        try (Scenario s = Scenario.ffa("crystal")) {
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "크리스탈 감옥");
            s.ticks(6);
            s.enemy.teleport(new Location(s.world, FAR, FlatWorld.SURFACE, FAR));
            s.ticks(2);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "the escaped prisoner is far from the burst");
            assertEquals(s.param("splash_damage"), magicDamage(s, s.enemy2), EPSILON, "the cage burst at once");
            assertModifierFree(s.enemy, "broke out");
            s.ticks(s.paramTicks("shard_duration") + 40);
            assertVisualsGone(s, "after the burst");
            s.finishClean();
        }
    }

    @Test
    void crystalPrisonNeedsAVisibleAimedEnemy() {
        try (Scenario s = Scenario.ffa("crystal")) {
            s.place(s.owner, 0.5, FlatWorld.SURFACE, 0.5, 180, -30);
            assertFalse(s.ultimate().succeeded(), "nobody aimed at");
            assertEquals(100, s.ctx().gauge(s.owner), EPSILON, "a failed ultimate keeps the gauge");
            s.owner.hidePlayer(s.h.hub.plugin(), s.enemy);
            s.aimAt(s.owner, s.enemy);
            assertFalse(s.ultimate().succeeded(), "an enemy hidden by stealth cannot be aimed at");
            s.owner.showPlayer(s.h.hub.plugin(), s.enemy);
            assertEquals(0, displays(s));
            s.finishClean();
        }
    }

    // ------------------------------------------------------------------ 화가 painter

    @Test
    void painterPaintCyclesRedBlueGreen() {
        try (Scenario s = Scenario.ffa("painter")) {
            s.place(s.enemy2, FAR, FAR, 180);
            double base = baseSpeed(s.enemy);

            // Red: a splat, then burning while standing in it.
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "물감 투척 (빨강)");
            s.ticks(8);
            List<Scenario.Hit> splat = magicHits(s, s.enemy);
            assertEquals(1, splat.size(), "the glob splats on the enemy: " + s.hits());
            assertEquals(s.param("splash_damage"), splat.getFirst().damage(), EPSILON);
            assertTrue(displays(s) >= 3, "a puddle of paint: " + displays(s));
            s.clearHits();
            s.ticks(40);
            List<Scenario.Hit> burns = magicHits(s, s.enemy);
            assertEquals(4, burns.size(), "red paint burns every half second: " + burns);
            assertEquals(4 * s.param("red_damage"), magicDamage(s, s.enemy), EPSILON);
            assertEquals(base, speed(s.enemy), 1.0E-9, "red does not slow");

            // Blue: slows while standing in it.
            s.readyActive();
            s.place(s.enemy, 6.5, 6.5, 180);
            s.aimAt(s.owner, s.enemy);
            s.clearHits();
            s.assertSucceeded(s.active(), "물감 투척 (파랑)");
            s.ticks(12);
            assertEquals(s.param("splash_damage"), magicDamage(s, s.enemy), EPSILON, "blue splats as well");
            assertEquals(base * (1 - s.param("blue_slow") / 100), speed(s.enemy), 1.0E-9, "blue paint slows");
            s.place(s.enemy, -FAR, FAR, 180);
            s.ticks(3);
            assertEquals(base, speed(s.enemy), 1.0E-9, "the slow ends right after stepping out");

            // Green: heals the owner standing in it, never an enemy.
            s.readyActive();
            s.owner.setHealth(10);
            s.enemy.setHealth(30);
            s.place(s.enemy, 0.5, 1.5, 180);
            s.place(s.owner, 0.5, FlatWorld.SURFACE, 0.5, 0, 90);
            s.clearHits();
            s.assertSucceeded(s.active(), "물감 투척 (초록)");
            s.ticks(40);
            assertTrue(s.owner.getHealth() >= 10 + 3 * s.param("green_heal"), "green paint heals: " + s.owner.getHealth());
            assertEquals(30 - s.param("splash_damage"), s.enemy.getHealth(), EPSILON, "the enemy only gets splashed");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.ticks(s.paramTicks("puddle_duration") + 30);
            assertVisualsGone(s, "after the paint dried");
            for (TestPlayer target : s.enemies()) {
                assertModifierFree(target, "after the paint");
            }
            s.finishClean();
        }
    }

    @Test
    void painterInspirationSpeedsTheOwnerOnItsOwnPaint() {
        try (Scenario s = Scenario.ffa("painter")) {
            s.place(s.enemy, FAR, FAR, 180);
            s.place(s.enemy2, -FAR, FAR, 180);
            double base = speed(s.owner);
            s.aimAtFloor(s.owner, 0.5, 3.5);
            s.assertSucceeded(s.active(), "물감 투척");
            s.ticks(12);
            assertEquals(base, speed(s.owner), 1.0E-9, "not on the paint yet");
            s.place(s.owner, 0.5, 3.5, 0);
            s.ticks(6);
            assertEquals(base * (1 + s.param("inspire_speed") / 100), speed(s.owner), 1.0E-9, "inspired on its paint");
            s.place(s.owner, -6.5, -6.5, 0);
            s.ticks(s.paramTicks("inspire_linger") - 6);
            assertTrue(speed(s.owner) > base, "the inspiration lingers a moment");
            s.ticks(12);
            assertEquals(base, speed(s.owner), 1.0E-9, "and then fades");
            assertModifierFree(s.owner, "uninspired");
            s.ticks(s.paramTicks("puddle_duration") + 20);
            s.finishClean();
        }
    }

    @Test
    void painterSplashStopsAtWalls() {
        try (Scenario s = Scenario.ffa("painter")) {
            s.place(s.enemy2, FAR, FAR, 180);
            s.place(s.enemy, 0.5, 4.5, 180);
            s.world.wallAlongX(-2, 3, 3, 3, Material.STONE);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "물감 투척");
            s.ticks(40);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "the paint splats on the wall, not through it");
            s.ticks(s.paramTicks("puddle_duration") + 20);
            assertVisualsGone(s, "after the paint dried");
            s.world.wallAlongX(-2, 3, 3, 3, Material.AIR);
            s.finishClean();
        }
    }

    @Test
    void painterCanvasFacesThePainterThenLandsFaceUpOnTheZone() {
        try (Scenario s = Scenario.ffa("painter")) {
            s.place(s.enemy, FAR, FAR, 180);
            s.place(s.enemy2, -FAR, FAR, 180);
            // Facing west (-X), slightly down: the canvas must not inherit the painter's rotation.
            s.place(s.owner, 0.5, FlatWorld.SURFACE, 0.5, 90, 20);
            s.assertSucceeded(s.ultimate(), "걸작");
            s.ticks(10);
            List<BlockDisplay> canvases = blockDisplays(s, Material.WHITE_WOOL);
            assertEquals(1, canvases.size(), "one canvas");
            BlockDisplay canvas = canvases.getFirst();
            for (Entity entity : s.world.getEntities()) {
                if (entity instanceof Display display) {
                    assertUnrotated(display, "masterpiece part " + display.getType());
                }
            }
            Vector3f face = worldDirection(canvas, 0, 0, 1);
            assertTrue(face.x > 0.95, "the painted face looks back east at the painter: " + face);
            Vector middle = worldPoint(canvas, 0.5f, 0.5f, 0.5f);
            assertTrue(middle.getX() < -0.5, "the canvas stands in front of (west of) the painter: " + middle);
            assertEquals(0.5, middle.getZ(), 1.0E-3, "straight ahead");
            assertTrue(middle.getY() > FlatWorld.SURFACE + 0.9, "standing upright: " + middle);
            for (Entity entity : s.world.getEntities()) {
                if (entity instanceof ItemDisplay brush) {
                    assertTrue(worldPoint(brush, 0, 0, 0).getX() > middle.getX(), "the brush paints the front");
                }
            }
            s.ticks(16);
            face = worldDirection(canvas, 0, 0, 1);
            assertTrue(face.y > 0.95, "it fell over face up: " + face);
            middle = worldPoint(canvas, 0.5f, 0.5f, 0.5f);
            assertEquals(-2.5, middle.getX(), 0.05, "lying on the zone in front of the painter: " + middle);
            assertEquals(FlatWorld.SURFACE, middle.getY(), 0.1, "flat on the ground: " + middle);
            s.ticks(s.paramTicks("ult_duration") + 30);
            assertVisualsGone(s, "after the masterpiece dried");
            s.finishClean();
        }
    }

    @Test
    void painterMasterpieceFloodsACircleWithAllThreeColours() {
        try (Scenario s = Scenario.teams("painter")) {
            prepare(s);
            double base = baseSpeed(s.enemy);
            s.owner.setHealth(10);
            s.ally().setHealth(10);
            s.assertSucceeded(s.ultimate(), "걸작");
            s.ticks(8);
            assertTrue(displays(s) >= 5, "a framed canvas rises: " + displays(s));
            s.ticks(16);
            assertTrue(displays(s) >= 8, "with three strokes painted on it: " + displays(s));
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "nothing happens before it is slammed down");
            s.ticks(4);
            for (TestPlayer target : s.enemies()) {
                assertEquals(s.param("ult_splash"), magicDamage(s, target), EPSILON, target.getName() + " is splashed");
            }
            s.ticks(10);
            for (TestPlayer target : s.enemies()) {
                assertEquals(base * (1 - s.param("ult_slow") / 100), speed(target), 1.0E-9,
                        target.getName() + " is slowed by the blue");
            }
            s.ticks(s.paramTicks("ult_duration"));
            int pulses = s.paramTicks("ult_duration") / s.paramTicks("ult_interval");
            for (TestPlayer target : s.enemies()) {
                assertEquals(s.param("ult_splash") + pulses * s.param("ult_damage"), magicDamage(s, target), EPSILON,
                        target.getName() + " burns in the red");
            }
            double healed = pulses * s.param("ult_heal");
            assertEquals(10 + healed, s.owner.getHealth(), EPSILON, "the green heals the owner");
            assertEquals(10 + healed, s.ally().getHealth(), EPSILON, "and the ally");
            assertEquals(0, s.damageTaken(s.ally()) + s.damageTaken(s.owner), EPSILON);
            s.ticks(20);
            assertVisualsGone(s, "after the masterpiece dried");
            for (TestPlayer target : s.enemies()) {
                assertModifierFree(target, "after the masterpiece");
            }
            assertModifierFree(s.owner, "after the masterpiece");
            s.finishClean();
        }
    }

    // ================================================================== audit regressions

    /** How close to a stealthed enemy a burst, flash, trail or sound counts as marking it. */
    private static final double MARK_REACH = 0.7;

    private static boolean near(Location at, List<Location> spots, double reach) {
        for (Location spot : spots) {
            if (at.getWorld().equals(spot.getWorld()) && at.distance(spot) <= reach) {
                return true;
            }
        }
        return false;
    }

    /**
     * Runs {@code ticks} ticks and fails if anything is drawn on or played at {@code hidden}, an enemy hidden from the
     * owner by stealth (as it stood when the tick began: effects are drawn before anything moves): a particle burst
     * (one spawn of several particles) or a flash at its feet, body or eyes, a trail drawn to it, a sound the owner
     * hears there, with {@code crown} particles circling over its head, and with {@code noDisplays} a display entity
     * on it. Area effects that merely pass by (the single particles of rings and drifting motes) do not count.
     */
    private static void assertNothingMarks(Scenario s, Player hidden, int ticks, boolean crown, boolean noDisplays,
                                           String what) {
        for (int i = 0; i < ticks; i++) {
            Location feet = hidden.getLocation();
            List<Location> spots = List.of(feet.clone().add(0, 0.2, 0),
                    feet.clone().add(0, hidden.getHeight() * 0.5, 0), hidden.getEyeLocation());
            Location head = hidden.getEyeLocation().add(0, 0.35, 0);
            s.world.clearSpawnedParticles();
            s.owner.clearSounds();
            s.ticks(1);
            for (SpawnedParticle particle : s.world.getSpawnedParticles()) {
                Location at = new Location(s.world, particle.x(), particle.y(), particle.z());
                boolean burst = particle.count() > 1 || particle.particle() == Particle.FLASH;
                assertFalse(burst && near(at, spots, MARK_REACH),
                        what + ": " + particle.count() + " " + particle.particle() + " drawn on the stealthed enemy");
                assertFalse(particle.data() instanceof Particle.Trail trail && near(trail.getTarget(), spots,
                        MARK_REACH), what + ": a trail drawn to the stealthed enemy");
                assertFalse(crown && Math.abs(at.getY() - head.getY()) < 0.1
                                && Math.hypot(at.getX() - head.getX(), at.getZ() - head.getZ()) < 0.6,
                        what + ": " + particle.particle() + " circling over the stealthed enemy's head");
            }
            for (AudioExperience sound : s.owner.getHeardSounds()) {
                assertFalse(near(sound.getLocation(), spots, MARK_REACH),
                        what + ": " + sound.getSound() + " heard where the stealthed enemy hides");
            }
            if (noDisplays) {
                for (Entity entity : s.world.getEntities()) {
                    assertFalse(entity instanceof Display && near(entity.getLocation(), spots, 1.0),
                            what + ": a " + entity.getType() + " shows where the stealthed enemy is");
                }
            }
        }
    }

    /** Hides {@code enemy} from the owner, as a stealth ability does. */
    private static void stealth(Scenario s, Player enemy) {
        s.owner.hidePlayer(s.h.hub.plugin(), enemy);
    }

    @Test
    void mycologistToadstoolFallsOffAnEnemySlippingIntoStealth() {
        try (Scenario s = Scenario.ffa("mycologist")) {
            s.place(s.enemy2, FAR, FAR, 180);
            s.melee(s.owner, s.enemy, 1);
            s.ticks(4);
            assertEquals(1, displays(s), "a toadstool sprouts on the spored enemy's head");
            stealth(s, s.enemy);
            s.place(s.enemy, -6.5, 6.5, 180);
            assertNothingMarks(s, s.enemy, s.paramTicks("spore_nausea"), true, true, "포자 버섯");
            assertVisualsGone(s, "the toadstool fell off where the enemy vanished");
            s.owner.showPlayer(s.h.hub.plugin(), s.enemy);
            s.finishClean();
        }
    }

    @Test
    void mycologistMineBurstDrawsNothingOnAStealthedEnemyItCatches() {
        try (Scenario s = Scenario.ffa("mycologist")) {
            s.place(s.enemy, FAR, FAR, 180);
            s.place(s.enemy2, -FAR, FAR, 180);
            s.aimAtFloor(s.owner, 0.5, 6.5);
            s.assertSucceeded(s.active(), "버섯 지뢰");
            s.ticks(50);
            // A spored enemy slips into stealth two blocks from the right-hand mine, within its burst.
            s.melee(s.owner, s.enemy2, 1);
            stealth(s, s.enemy2);
            s.place(s.enemy2, 0.5 - s.param("mine_spread"), 8.5, 180);
            s.ticks(8);
            s.clearHits();
            // A visible enemy steps on that mine.
            s.place(s.enemy, 0.5 - s.param("mine_spread"), 6.5, 180);
            assertNothingMarks(s, s.enemy2, FUSE_AND_DIZZY, true, false, "포자 폭발");
            assertEquals(s.param("mine_damage"), magicDamage(s, s.enemy), EPSILON, "the visible enemy is caught");
            assertEquals(s.param("mine_damage") * (1 + s.param("spore_bonus") / 100), magicDamage(s, s.enemy2),
                    EPSILON, "the stealthed one is hurt all the same");
            s.owner.showPlayer(s.h.hub.plugin(), s.enemy2);
            s.finishClean();
        }
    }

    @Test
    void mycologistMinesReplantedUnderTheGiantMushroomAreNeverInvisible() {
        try (Scenario s = Scenario.ffa("mycologist")) {
            s.place(s.enemy, FAR, FAR, 180);
            s.place(s.enemy2, -FAR, FAR, 180);
            s.aimAtFloor(s.owner, 0.5, 6.5);
            s.assertSucceeded(s.active(), "버섯 지뢰");
            s.ticks(30);
            s.aimAtFloor(s.owner, -6.5, 0.5);
            s.assertSucceeded(s.ultimate(), "거대 버섯");
            s.ticks(14);
            // Re-planted while the giant mushroom stands and the old row is still wilting.
            s.readyActive();
            s.aimAtFloor(s.owner, 0.5, -6.5);
            s.assertSucceeded(s.active(), "버섯 지뢰");
            s.ticks(14);
            long grown = blockDisplays(s, Material.MUSHROOM_STEM).stream()
                    .filter(stem -> stem.getLocation().getZ() < -3).count();
            assertEquals(Math.round(s.param("mine_count")), grown,
                    "every mine of the new row can be seen: " + displays(s) + " displays");
            s.ticks(s.paramTicks("mine_duration") + 40);
            assertVisualsGone(s, "after the mines withered");
            s.finishClean();
        }
    }

    /** A mine's fuse plus the dizziness of its burst, with some slack. */
    private static final int FUSE_AND_DIZZY = 60;

    @Test
    void mycologistGiantMushroomDrawsNothingOnAStealthedEnemyInItsCloud() {
        try (Scenario s = Scenario.ffa("mycologist")) {
            s.place(s.enemy2, 2.5, 5.5, 180);
            stealth(s, s.enemy2);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "거대 버섯");
            assertNothingMarks(s, s.enemy2, 13 + s.paramTicks("ult_duration") + 12, false, false, "거대 버섯");
            assertTrue(magicDamage(s, s.enemy2) >= s.param("erupt_damage") + s.param("burst_damage"),
                    "the stealthed enemy is hurt all the same: " + magicHits(s, s.enemy2));
            s.owner.showPlayer(s.h.hub.plugin(), s.enemy2);
            s.ticks(30);
            s.finishClean();
        }
    }

    /**
     * A real client only warps the view while more than this many ticks of nausea are left, and fades the warp out
     * over them: nausea of 3 seconds or less shows nothing at all.
     */
    private static final int CLIENT_NAUSEA_FADE = 60;

    private static int nauseaTicks(Player player) {
        PotionEffect nausea = player.getPotionEffect(PotionEffectType.NAUSEA);
        return nausea == null ? 0 : nausea.getDuration();
    }

    @Test
    void mycologistNauseaLastsLongEnoughForTheClientToShowIt() {
        try (Scenario s = Scenario.ffa("mycologist")) {
            s.place(s.enemy2, FAR, FAR, 180);
            s.melee(s.owner, s.enemy, 1);
            assertTrue(nauseaTicks(s.enemy) - CLIENT_NAUSEA_FADE >= s.paramTicks("spore_nausea"),
                    "the spores warp the view for spore_nausea: " + nauseaTicks(s.enemy));

            s.enemy.removePotionEffect(PotionEffectType.NAUSEA);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "버섯 지뢰");
            for (int i = 0; i < 80 && magicDamage(s, s.enemy) == 0; i++) {
                s.ticks(1);
            }
            assertTrue(magicDamage(s, s.enemy) > 0, "the mine burst under the enemy");
            assertTrue(nauseaTicks(s.enemy) - CLIENT_NAUSEA_FADE >= s.paramTicks("mine_confuse") - 1,
                    "the burst warps the view for mine_confuse: " + nauseaTicks(s.enemy));

            s.ticks(120);
            s.enemy.removePotionEffect(PotionEffectType.NAUSEA);
            s.healAll();
            s.place(s.enemy, 0.5, 4.5, 180);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "거대 버섯");
            int longest = 0;
            for (int i = 0; i < 13 + s.paramTicks("ult_duration"); i++) {
                s.ticks(1);
                longest = Math.max(longest, nauseaTicks(s.enemy));
            }
            assertTrue(longest > s.paramTicks("ult_interval") + CLIENT_NAUSEA_FADE,
                    "a sting's nausea still warps the view when the next one comes: " + longest);
            s.ticks(30);
            s.finishClean();
        }
    }

    @Test
    void crystalSpikesDrawNothingOnAStealthedEnemyTheyCatch() {
        try (Scenario s = Scenario.ffa("crystal")) {
            // One block beside the line, where the spikes still reach.
            s.place(s.enemy2, 1.5, 8.5, 180);
            stealth(s, s.enemy2);
            s.assertSucceeded(s.active(), "크리스탈 가시");
            assertNothingMarks(s, s.enemy2, 30, false, false, "크리스탈 가시");
            assertEquals(s.param("spike_damage"), magicDamage(s, s.enemy), EPSILON, "the visible enemy is struck");
            assertEquals(s.param("spike_damage"), magicDamage(s, s.enemy2), EPSILON,
                    "the stealthed one is struck all the same");
            s.owner.showPlayer(s.h.hub.plugin(), s.enemy2);
            s.ticks(s.paramTicks("shard_duration") + 20);
            s.finishClean();
        }
    }

    @Test
    void crystalShatterDrawsNothingOnAStealthedEnemyItHits() {
        try (Scenario s = Scenario.ffa("crystal")) {
            stealth(s, s.enemy2);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "크리스탈 감옥");
            assertNothingMarks(s, s.enemy2, s.paramTicks("prison_duration") + 10, false, false, "크리스탈 감옥");
            assertEquals(s.param("splash_damage"), magicDamage(s, s.enemy2), EPSILON,
                    "the stealthed enemy beside the cage is hit all the same");
            s.owner.showPlayer(s.h.hub.plugin(), s.enemy2);
            s.ticks(s.paramTicks("shard_duration") + 40);
            s.finishClean();
        }
    }

    @Test
    void crystalPrisonBorrowedByAGamblerLetsGoWhenTheGamblerDies() {
        try (Scenario s = gambler().start()) {
            prepare(s);
            double base = baseSpeed(s.enemy);
            s.assertSucceeded(borrow(s, "crystal"), "borrowed 크리스탈 감옥");
            s.ticks(6);
            assertEquals(0, speed(s.enemy), 1.0E-9, "caged");
            s.killOwner(s.enemy2);
            s.ticks(1);
            assertEquals(0, displays(s), "the cage went with the gambler");
            assertEquals(base, speed(s.enemy), 1.0E-9, "so the prisoner walks free at once");
            assertModifierFree(s.enemy, "no root is left without a cage");
            s.finishClean();
        }
    }

    @Test
    void crystalFadingShardNeverGrowsBackFromAResonancePulse() {
        // A long cooldown keeps the resonance (and so the pulse of the nearest shard) going until the shard fades.
        try (Scenario s = Scenario.builder("crystal").param("cooldown", 40).start()) {
            s.place(s.enemy, FAR, FAR, 180);
            s.place(s.enemy2, -FAR, FAR, 180);
            // The pulse runs every half second: cast at every phase, so the fade falls on each tick of it once.
            for (int phase = 0; phase < 10; phase++) {
                while (s.ctx().currentTick() % 10 != phase) {
                    s.ticks(1);
                }
                s.place(s.owner, 0.5, 0.5, 0);
                s.readyActive();
                s.assertSucceeded(s.active(), "크리스탈 가시");
                s.ticks(16);
                // The fourth spike (1.3 + 3 blocks ahead) stays as a shard; stand next to it.
                BlockDisplay shard = blockDisplays(s, Material.AMETHYST_CLUSTER).stream()
                        .filter(display -> Math.abs(display.getLocation().getX() - 0.5) < 1.0E-6
                                && Math.abs(display.getLocation().getZ() - 4.8) < 1.0E-6)
                        .findFirst().orElseThrow();
                s.place(s.owner, 0.5, 3.0, 0);
                boolean fading = false;
                for (int i = 0; i < 200 && shard.isValid(); i++) {
                    s.ticks(1);
                    if (!shard.isValid()) {
                        break;
                    }
                    float size = model(shard).getScale(new Vector3f()).y;
                    if (size < 0.05f) {
                        fading = true;
                    } else {
                        assertFalse(fading, "phase " + phase + ": the fading shard grew back to " + size);
                    }
                }
                assertTrue(fading, "phase " + phase + ": the shard faded");
                s.ticks(20);
                assertEquals(0, displays(s), "phase " + phase + ": every crystal is gone");
            }
            s.finishClean();
        }
    }

    @Test
    void painterPaintDrawsNothingOnAStealthedEnemyInIt() {
        try (Scenario s = Scenario.ffa("painter")) {
            // Inside the puddle that will spread under the aimed enemy, but not where the glob flies.
            s.place(s.enemy2, 2.0, 5.5, 180);
            stealth(s, s.enemy2);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "물감 투척 (빨강)");
            assertNothingMarks(s, s.enemy2, 20 + s.paramTicks("puddle_duration"), false, false, "빨강 물감");
            assertTrue(magicDamage(s, s.enemy2) > s.param("splash_damage"),
                    "the stealthed enemy is splashed and burns all the same: " + magicHits(s, s.enemy2));

            s.readyActive();
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "물감 투척 (파랑)");
            assertNothingMarks(s, s.enemy2, 14, false, false, "파랑 물감");
            assertTrue(speed(s.enemy2) < baseSpeed(s.enemy2), "the stealthed enemy is slowed all the same");
            assertNothingMarks(s, s.enemy2, s.paramTicks("puddle_duration"), false, false, "파랑 물감");
            s.owner.showPlayer(s.h.hub.plugin(), s.enemy2);
            s.ticks(30);
            s.finishClean();
        }
    }

    @Test
    void painterIsInspiredOnItsPaintEvenWithoutALinger() {
        try (Scenario s = Scenario.builder("painter").param("inspire_linger", 0).start()) {
            s.place(s.enemy, FAR, FAR, 180);
            s.place(s.enemy2, -FAR, FAR, 180);
            double base = speed(s.owner);
            s.aimAtFloor(s.owner, 0.5, 3.5);
            s.assertSucceeded(s.active(), "물감 투척");
            s.ticks(12);
            s.place(s.owner, 0.5, 3.5, 0);
            s.ticks(8);
            assertEquals(base * (1 + s.param("inspire_speed") / 100), speed(s.owner), 1.0E-9,
                    "inspired while standing on its paint");
            s.place(s.owner, -6.5, -6.5, 0);
            s.ticks(8);
            assertEquals(base, speed(s.owner), 1.0E-9, "and no longer once off it");
            s.ticks(s.paramTicks("puddle_duration") + 20);
            s.finishClean();
        }
    }

    @Test
    void painterMasterpieceDrawsNothingOnAStealthedEnemyInIt() {
        try (Scenario s = Scenario.ffa("painter")) {
            s.place(s.enemy2, 3.5, 6.5, 180);
            stealth(s, s.enemy2);
            s.assertSucceeded(s.ultimate(), "걸작");
            assertNothingMarks(s, s.enemy2, 30 + s.paramTicks("ult_duration") + 10, false, false, "걸작");
            assertTrue(magicDamage(s, s.enemy2) > s.param("ult_splash"),
                    "the stealthed enemy is splashed and burns all the same: " + magicHits(s, s.enemy2));
            s.owner.showPlayer(s.h.hub.plugin(), s.enemy2);
            s.ticks(20);
            s.finishClean();
        }
    }
}
