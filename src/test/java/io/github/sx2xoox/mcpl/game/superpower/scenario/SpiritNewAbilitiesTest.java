package io.github.sx2xoox.mcpl.game.superpower.scenario;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import io.github.sx2xoox.mcpl.game.superpower.abilities.DemonAbility;
import io.github.sx2xoox.mcpl.game.superpower.abilities.GhostAbility;
import io.github.sx2xoox.mcpl.game.superpower.abilities.PhantomAbility;
import io.github.sx2xoox.mcpl.game.superpower.abilities.ReaperAbility;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Spots;
import io.github.sx2xoox.mcpl.game.superpower.ability.Ability;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import io.github.sx2xoox.mcpl.game.superpower.ability.ActivationResult;
import io.github.sx2xoox.mcpl.game.superpower.support.Scenario;
import io.github.sx2xoox.mcpl.game.superpower.support.TestPlayer;
import io.github.sx2xoox.mcpl.game.superpower.support.world.FlatWorld;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** 어둠·영혼 (dark & spirits): 악마, 사신, 유령, 팬텀. */
class SpiritNewAbilitiesTest {

    private static final double EPSILON = 1.0E-6;
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z0-9_]+)}");
    /** The most display entities one ability instance may keep alive at once. */
    private static final int DISPLAY_BUDGET = 24;

    static Stream<String> abilities() {
        return Stream.of("demon", "reaper", "ghost", "phantom");
    }

    /** The abilities whose ultimate the gambler may borrow. */
    static Stream<String> standalone() {
        return Stream.of("demon", "reaper", "phantom");
    }

    private static long displays(Scenario s) {
        return s.world.getEntities().stream().filter(Display.class::isInstance).count();
    }

    private static long displays(Scenario s, Class<? extends Display> type) {
        return s.world.getEntities().stream().filter(type::isInstance).count();
    }

    /** A hit with no attacker at all (a stand-in for any damage the victim takes from somewhere else). */
    private static void hurt(Player victim, double amount) {
        victim.setNoDamageTicks(0);
        victim.damage(amount, DamageSource.builder(DamageType.GENERIC).build());
    }

    /** Ticks {@code ticks} times, asserting the display budget and unrotated displays after every tick. */
    private static void ticksWithinBudget(Scenario s, int ticks) {
        for (int i = 0; i < ticks; i++) {
            s.ticks(1);
            long alive = displays(s);
            assertTrue(alive <= DISPLAY_BUDGET, s.abilityId + ": " + alive + " displays alive at once");
            assertUnrotated(s);
        }
    }

    /**
     * Every display has yaw and pitch 0: its orientation lives in its transformation only (a display moved to an
     * entity location would otherwise turn its model by that entity's rotation on top of the transformation).
     */
    private static void assertUnrotated(Scenario s) {
        for (Entity entity : s.world.getEntities()) {
            if (entity instanceof Display display) {
                assertEquals(0, display.getLocation().getYaw(), EPSILON, s.abilityId + ": display yaw " + display);
                assertEquals(0, display.getLocation().getPitch(), EPSILON, s.abilityId + ": display pitch " + display);
            }
        }
    }

    private static List<BlockDisplay> blocks(Scenario s, Material type) {
        return s.world.getEntities().stream().filter(BlockDisplay.class::isInstance).map(BlockDisplay.class::cast)
                .filter(display -> display.getBlock().getMaterial() == type).toList();
    }

    /**
     * The left rotation the ability gave {@code display} (its right rotation is always the identity).
     *
     * <p>MockBukkit's {@code DisplayMock#getTransformation} rebuilds the transformation from its matrix and then
     * conjugates the extracted rotation <em>in place</em>, so it reports the inverse rotation as both the left and the
     * right rotation. That signature (left == right, not the identity) is undone here; a fixed mock passes through.
     */
    private static Quaternionf leftRotation(Display display) {
        Transformation transformation = display.getTransformation();
        Quaternionf left = new Quaternionf(transformation.getLeftRotation());
        Quaternionf right = transformation.getRightRotation();
        boolean mirroredByMock = left.equals(right, 1.0E-6f) && !left.equals(new Quaternionf(), 1.0E-6f);
        return mirroredByMock ? left.conjugate() : left;
    }

    /** Where a display's transformation turns its local axis {@code (x, y, z)}. */
    private static Vector turned(Display display, float x, float y, float z) {
        Vector3f v = leftRotation(display).transform(new Vector3f(x, y, z));
        return new Vector(v.x(), v.y(), v.z());
    }

    /** The center of a block display's box in world space (the display entity itself is unrotated). */
    private static Vector center(BlockDisplay display) {
        Transformation transformation = display.getTransformation();
        Vector3f half = new Vector3f(transformation.getScale()).mul(0.5f);
        Vector3f c = leftRotation(display).transform(half).add(transformation.getTranslation());
        return display.getLocation().toVector().add(new Vector(c.x(), c.y(), c.z()));
    }

    /** The horizontal unit vector Minecraft's {@code yaw} faces (0 = +Z, 90 = -X). */
    private static Vector yawVector(double yaw) {
        double radians = Math.toRadians(yaw);
        return new Vector(-Math.sin(radians), 0, Math.cos(radians));
    }

    private static void assertSameDirection(Vector expected, Vector actual, String what) {
        assertTrue(expected.clone().normalize().distance(actual.clone().normalize()) < 1.0E-3,
                what + ": expected " + expected.clone().normalize() + " but was " + actual.clone().normalize());
    }

    /** Lands (or waits for) {@code player} and checks it stands exactly on the floor, inside no block. */
    private static void assertStandingOnFloor(Scenario s, Player player, String what) {
        s.tickUntilGrounded(player, 100);
        Location at = player.getLocation();
        assertEquals(FlatWorld.SURFACE, at.getY(), 1.0E-6, what + " stands exactly on the floor: " + at);
        assertTrue(Spots.isFree(s.world, at, player.getWidth(), player.getHeight()), what + " is inside no block");
    }

    /** The default layout, aimed at the enemy; the reaper's scythe needs the enemy within its reach. */
    private static void prepareActive(Scenario s) {
        s.layout();
        if (s.abilityId.equals("reaper")) {
            s.place(s.enemy, 0.5, 2.5, 180);
        }
        s.aimAt(s.owner, s.enemy);
    }

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

    // ------------------------------------------------------------------ every ability

    @Test
    void descriptionsOnlyReferenceDeclaredParams() {
        for (AbilityInfo info : List.of(DemonAbility.INFO, ReaperAbility.INFO, GhostAbility.INFO,
                PhantomAbility.INFO)) {
            for (AbilityInfo.Skill skill : List.of(info.passive(), info.active(), info.ultimate())) {
                Matcher matcher = PLACEHOLDER.matcher(skill.description());
                while (matcher.find()) {
                    assertTrue(info.params().containsKey(matcher.group(1)),
                            info.id() + " " + skill.name() + " uses an undeclared param " + matcher.group());
                }
                assertFalse(skill.name().contains("TODO") || skill.description().contains("TODO"), info.id());
            }
        }
        assertTrue(DemonAbility.INFO.ultimateStandalone(), "the hellgate runs on its own");
        assertTrue(ReaperAbility.INFO.ultimateStandalone(), "the mark runs on its own");
        assertTrue(PhantomAbility.INFO.ultimateStandalone(), "the swarm runs on its own");
        assertFalse(GhostAbility.INFO.ultimateStandalone(), "the phase needs the ghost's own hooks");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("abilities")
    void wholeMatch(String id) {
        try (Scenario s = Scenario.ffa(id)) {
            s.ticks(60);
            s.assertNoProblems();

            prepareActive(s);
            s.clearHits();
            s.assertSucceeded(s.active(), id + " active");
            ticksWithinBudget(s, 100);
            assertTrue(s.damageBy(s.owner, s.enemy) > 0, id + " active hurts the aimed enemy");
            assertEquals(0, s.damageTaken(s.owner), EPSILON, id + " active never hurts its owner: " + s.hits());
            assertEquals(0, displays(s), id + ": the active's displays are gone");

            AbilityLifecycleTest.prepareUltimate(s);
            s.healAll();
            s.clearHits();
            s.assertSucceeded(s.ultimate(), id + " ultimate");
            ticksWithinBudget(s, 300);
            assertTrue(s.damageBy(s.owner, s.enemy) > 0, id + " ultimate hurts the aimed enemy");
            assertEquals(0, s.damageTaken(s.owner), EPSILON, id + " ultimate never hurts its owner: " + s.hits());
            assertEquals(0, displays(s), id + ": every display is gone once the skills are over");
            for (TestPlayer enemy : s.enemies()) {
                assertModifierFree(enemy, id);
                assertTrue(enemy.canSee(s.owner), id + ": " + enemy.getName() + " sees the owner again");
            }
            assertModifierFree(s.owner, id);
            s.assertNoProblems();

            s.finishMatch();
            s.assertNoProblems();
            s.assertCleanWorld();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("abilities")
    void ownerDiesDuringActive(String id) {
        try (Scenario s = Scenario.ffa(id)) {
            s.ticks(40);
            prepareActive(s);
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
            AbilityLifecycleTest.prepareUltimate(s);
            s.assertSucceeded(s.ultimate(), id + " ultimate");
            s.ticks(14);
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
        assertEquals(0, s.h.hub.tempBlocks().size(), id);
        for (TestPlayer enemy : s.enemies()) {
            assertModifierFree(enemy, id);
            assertTrue(enemy.canSee(s.owner), id + ": " + enemy.getName() + " sees the dead owner again");
        }
        assertModifierFree(s.owner, id);
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
            prepareActive(s);
            s.crossfireAlly();
            s.aimAt(s.owner, s.enemy);
            Location allyStart = s.ally().getLocation();
            s.clearHits();
            s.assertSucceeded(s.active(), id + " active");
            AbilityLifecycleTest.watchAlly(s, allyStart, 100);
            assertTrue(s.damageTaken(s.enemy) > 0, id + " active still hurts the enemy");

            AbilityLifecycleTest.prepareUltimate(s);
            allyStart = s.ally().getLocation();
            s.clearHits();
            s.assertSucceeded(s.ultimate(), id + " ultimate");
            AbilityLifecycleTest.watchAlly(s, allyStart, 200);
            assertTrue(s.ally().canSee(s.owner), id + ": the ally always sees the owner");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    // ------------------------------------------------------------------ borrowed by the gambler

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

    @ParameterizedTest(name = "{0}")
    @MethodSource("standalone")
    void borrowedUltimateWorksOnAGambler(String id) {
        try (Scenario s = gambler(false)) {
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(borrow(s, id), "borrowed " + id);
            ticksWithinBudget(s, 300);
            assertTrue(s.damageBy(s.owner, s.enemy) > 0, "the borrowed " + id + " hurts the aimed enemy");
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "the borrowed " + id + " never hurts the gambler");
            assertModifierFree(s.owner, id);
            for (TestPlayer target : s.enemies()) {
                assertModifierFree(target, id);
            }
            assertEquals(0, displays(s), id + ": displays are gone");
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
            s.ticks(14);
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

    // ------------------------------------------------------------------ 악마 demon

    /** The hellfire damage the contract makes of the base damage at {@code health} (after paying the cost). */
    private static double hellfireAt(Scenario s, double health) {
        double floor = s.param("pact_floor") / 100;
        double missing = 1 - health / s.maxHealth(s.owner);
        double pact = Math.clamp(missing / (1 - floor), 0, 1);
        return s.param("hellfire_damage") * (1 + s.param("pact_max") / 100 * pact);
    }

    @Test
    void demonHellfirePaysInBloodAndBurnsTheCone() {
        try (Scenario s = Scenario.ffa("demon")) {
            s.place(s.enemy2, 0.5, -4.5, 0);
            s.aimAt(s.owner, s.enemy);
            double before = s.health(s.owner);
            s.assertSucceeded(s.active(), "지옥불");
            assertEquals(before - s.param("hellfire_cost"), s.health(s.owner), EPSILON, "two health paid");
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "a price, not damage");
            assertEquals(5, displays(s, BlockDisplay.class), "a sheet of soul fire rides the wave");
            s.ticks(10);
            assertEquals(1, s.hitsBy(s.owner, s.enemy).size(), "the wave burns once");
            assertEquals(hellfireAt(s, before - s.param("hellfire_cost")), s.damageBy(s.owner, s.enemy), EPSILON);
            assertEquals(DamageType.MAGIC, s.hitsBy(s.owner, s.enemy).getFirst().type(), "ignores armor");
            assertTrue(s.enemy.getFireTicks() > 0, "set on fire");
            assertTrue(s.enemy.getLocation().getZ() > 4.6, "pushed along the wave: " + s.enemy.getLocation());
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON, "behind the demon");
            assertEquals(0, s.enemy2.getFireTicks(), "behind the demon");
            s.ticks(20);
            assertEquals(0, displays(s), "the sheet died down");
            s.finishClean();
        }
    }

    @Test
    void demonContractGrowsWithMissingHealthForAbilitiesOnly() {
        try (Scenario s = Scenario.ffa("demon")) {
            s.owner.setHealth(6);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "지옥불");
            s.ticks(10);
            double full = s.param("hellfire_damage") * (1 + s.param("pact_max") / 100);
            assertEquals(full, s.damageBy(s.owner, s.enemy), EPSILON, "at 4 health the contract is sealed");

            s.clearHits();
            s.melee(s.owner, s.enemy2, 4);
            assertEquals(4, s.damageBy(s.owner, s.enemy2), EPSILON, "melee is not part of the contract");
            s.finishClean();
        }
    }

    @Test
    void demonCannotPayWithItsLastBlood() {
        try (Scenario s = Scenario.ffa("demon")) {
            s.aimAt(s.owner, s.enemy);
            s.owner.setHealth(s.param("hellfire_cost"));
            ActivationResult result = s.active();
            assertFalse(result.succeeded(), "nothing left to pay with");
            assertEquals(s.param("hellfire_cost"), s.health(s.owner), EPSILON, "no health taken");
            assertEquals(0, s.ctx().cooldownRemaining(s.owner), EPSILON, "no cooldown consumed");
            s.ticks(10);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON);
            s.finishClean();
        }
    }

    @Test
    void demonHellfireIsStoppedByWalls() {
        try (Scenario s = Scenario.ffa("demon")) {
            s.aimAt(s.owner, s.enemy);
            s.world.wallAlongX(-4, 5, 3, 3, Material.STONE);
            s.assertSucceeded(s.active(), "지옥불");
            s.ticks(10);
            assertEquals(0, s.damageTaken(s.enemy) + s.damageTaken(s.enemy2), EPSILON);
            s.world.wallAlongX(-4, 5, 3, 3, Material.AIR);
            s.finishClean();
        }
    }

    @Test
    void demonHellgateMarksTheGroundThenThrowsEnemiesAway() {
        try (Scenario s = Scenario.ffa("demon")) {
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "지옥문");
            s.ticks(2);
            assertEquals(7, displays(s, BlockDisplay.class), "an obsidian gate with a portal and two braziers rises");
            // The gate rises (12 ticks) before the first sigils appear, and they burn for the delay before erupting.
            s.ticks(12);
            assertEquals(9, displays(s, BlockDisplay.class), "a sigil under each enemy");
            assertEquals(0, s.damageTaken(s.enemy) + s.damageTaken(s.enemy2), EPSILON, "the sigils warn first");
            s.ticks(s.paramTicks("pillar_delay") - 2);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "still warning");
            Location before = s.enemy2.getLocation();
            s.ticks(3);
            for (TestPlayer enemy : s.enemies()) {
                assertEquals(s.param("pillar_damage"), s.damageBy(s.owner, enemy), EPSILON, enemy.getName());
            }
            s.ticks(2);
            Location gate = new Location(s.world, 0.5, FlatWorld.SURFACE, 4.5);
            assertTrue(horizontal(s.enemy2.getLocation(), gate) > horizontal(before, gate) + 0.3,
                    "thrown away from the gate: " + before + " -> " + s.enemy2.getLocation());
            ticksWithinBudget(s, 200);
            assertEquals(0, displays(s), "the gate sinks away");
            for (TestPlayer enemy : s.enemies()) {
                assertStandingOnFloor(s, enemy, enemy.getName() + " thrown by the pillars");
            }
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.finishClean();
        }
    }

    @Test
    void demonHellgatePillarsCanBeDodged() {
        try (Scenario s = Scenario.ffa("demon")) {
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "지옥문");
            s.ticks(14);
            assertEquals(9, displays(s, BlockDisplay.class), "both enemies are marked");
            s.place(s.enemy, 3.5, 7.5, 180);
            s.place(s.enemy2, 30.5, 6.5, 180);
            s.ticks(s.paramTicks("pillar_delay") + 2);
            assertEquals(0, s.damageTaken(s.enemy) + s.damageTaken(s.enemy2), EPSILON, "they left the sigils");
            s.finishClean();
        }
    }

    @Test
    void demonHellgateNeedsEnemiesNearTheGate() {
        try (Scenario s = Scenario.ffa("demon")) {
            s.place(s.enemy, 0.5, 30.5, 180);
            s.place(s.enemy2, 30.5, 0.5, 90);
            s.place(s.owner, 0.5, FlatWorld.SURFACE, 0.5, 180, 30);
            assertFalse(s.ultimate().succeeded(), "nobody to burn");
            assertEquals(100, s.participant(s.owner).gauge(), EPSILON, "the gauge is kept");
            assertEquals(0, displays(s));
            s.finishClean();
        }
    }

    private static double horizontal(Location a, Location b) {
        return Math.hypot(a.getX() - b.getX(), a.getZ() - b.getZ());
    }

    // ------------------------------------------------------------------ 사신 reaper

    @Test
    void reaperExecutesEnemiesCloseToDeath() {
        try (Scenario s = Scenario.ffa("reaper")) {
            s.melee(s.owner, s.enemy, 4);
            assertEquals(4, s.damageBy(s.owner, s.enemy), EPSILON, "a healthy enemy takes the plain hit");
            double threshold = s.maxHealth(s.enemy) * s.param("execute_threshold") / 100;
            s.enemy.setHealth(threshold - 1);
            s.clearHits();
            s.melee(s.owner, s.enemy, 4);
            assertEquals(4 * (1 + s.param("execute_bonus") / 100), s.damageBy(s.owner, s.enemy), EPSILON,
                    "a doomed enemy takes more");
            s.ticks(1);
            assertEquals(1, displays(s, BlockDisplay.class), "a slash marks the execution");
            s.ticks(20);
            assertEquals(0, displays(s));
            s.finishClean();
        }
    }

    @Test
    void reaperSpinCutsAllAroundAndDrinksTheirLife() {
        try (Scenario s = Scenario.ffa("reaper")) {
            s.place(s.enemy, 0.5, 2.5, 180);
            s.place(s.enemy2, 0.5, -2.0, 0);
            s.owner.setHealth(10);
            s.assertSucceeded(s.active(), "낫 회전");
            assertEquals(1, displays(s, ItemDisplay.class), "the scythe");
            s.ticks(12);
            for (TestPlayer enemy : s.enemies()) {
                assertEquals(1, s.hitsBy(s.owner, enemy).size(), enemy.getName() + " is cut once");
                assertEquals(s.param("spin_damage"), s.damageBy(s.owner, enemy), EPSILON, enemy.getName());
            }
            double heal = 2 * s.param("spin_damage") * s.param("spin_lifesteal") / 100;
            assertEquals(10 + heal, s.health(s.owner), EPSILON, "the stolen life heals the reaper");
            assertTrue(s.enemy.getLocation().getZ() < 2.5, "pulled in: " + s.enemy.getLocation());
            assertStandingOnFloor(s, s.enemy, "the pulled enemy");
            assertStandingOnFloor(s, s.enemy2, "the pulled enemy behind");
            s.ticks(20);
            assertEquals(0, displays(s), "the scythe vanished");
            s.finishClean();
        }
    }

    @Test
    void reaperSpinHasLimitedReach() {
        try (Scenario s = Scenario.ffa("reaper")) {
            s.place(s.enemy, 0.5, 1.0 + s.param("spin_radius") + 1, 180);
            s.assertSucceeded(s.active(), "낫 회전");
            s.ticks(20);
            assertEquals(0, s.damageTaken(s.enemy) + s.damageTaken(s.enemy2), EPSILON, "out of reach");
            s.finishClean();
        }
    }

    @Test
    void reaperMarkPaysBackWhatTheVictimSuffered() {
        try (Scenario s = Scenario.ffa("reaper")) {
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "사신의 표식");
            assertEquals(1, displays(s, ItemDisplay.class), "a scythe hangs over the victim");
            assertEquals(1, displays(s, TextDisplay.class), "the countdown");
            s.ticks(10);
            hurt(s.enemy, 10);
            s.ticks(s.paramTicks("mark_duration") - 12);
            assertEquals(0, s.damageBy(s.owner, s.enemy), EPSILON, "the knell has not ended");
            s.ticks(4);
            double blow = s.param("mark_base") + 10 * s.param("mark_ratio") / 100;
            assertEquals(blow, s.damageBy(s.owner, s.enemy), EPSILON, "base + share of what it suffered");
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON, "only the marked enemy");
            s.ticks(30);
            assertEquals(0, displays(s), "the mark is gone");
            s.finishClean();
        }
    }

    @Test
    void reaperMarkIsCapped() {
        try (Scenario s = Scenario.ffa("reaper")) {
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "사신의 표식");
            s.ticks(5);
            hurt(s.enemy, 40);
            s.ticks(s.paramTicks("mark_duration") + 5);
            assertEquals(s.param("mark_cap"), s.damageBy(s.owner, s.enemy), EPSILON);
            s.finishClean();
        }
    }

    @Test
    void reaperReapsTheSoulOfAMarkedEnemyThatFalls() {
        try (Scenario s = Scenario.ffa("reaper")) {
            s.owner.setHealth(8);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "사신의 표식");
            s.ticks(10);
            hurt(s.enemy, 10_000);
            assertFalse(s.session.isAlive(s.enemy), "the marked enemy fell");
            s.ticks(2);
            assertEquals(8 + s.param("reap_heal"), s.health(s.owner), EPSILON, "its soul heals the reaper");
            s.respawn(s.enemy);
            s.ticks(s.paramTicks("mark_duration") + 10);
            assertEquals(0, displays(s), "the mark faded");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void reaperMarkNeedsAnAimedEnemy() {
        try (Scenario s = Scenario.ffa("reaper")) {
            s.place(s.owner, 0.5, FlatWorld.SURFACE, 0.5, 180, 0);
            assertFalse(s.ultimate().succeeded(), "nobody aimed at");
            assertEquals(100, s.participant(s.owner).gauge(), EPSILON, "the gauge is kept");
            s.finishClean();
        }
    }

    // ------------------------------------------------------------------ 유령 ghost

    @Test
    void ghostBodySoftensMeleeOnly() {
        try (Scenario s = Scenario.ffa("ghost")) {
            s.melee(s.enemy, s.owner, 8);
            assertEquals(8 * (1 - s.param("ethereal_reduction") / 100), s.damageTaken(s.owner), EPSILON,
                    "the blade half passes through");
            assertEquals(2, displays(s, BlockDisplay.class), "a translucent afterimage drifts off");
            s.ticks(20);
            assertEquals(0, displays(s), "the afterimage faded");
            s.healAll();
            s.clearHits();
            hurt(s.owner, 8);
            assertEquals(8, s.damageTaken(s.owner), EPSILON, "anything but melee is not softened");
            s.finishClean();
        }
    }

    @Test
    void ghostTerrorDrivesEnemiesAwayAndDisarmsThem() {
        try (Scenario s = Scenario.ffa("ghost")) {
            s.place(s.enemy2, 0.5, -4.5, 0);
            s.aimAt(s.owner, s.enemy);
            double attack = s.enemy.getAttribute(Attribute.ATTACK_DAMAGE).getValue();
            double speed = s.enemy.getAttribute(Attribute.MOVEMENT_SPEED).getValue();
            s.assertSucceeded(s.active(), "공포");
            assertEquals(s.param("fear_damage"), s.damageBy(s.owner, s.enemy), EPSILON);
            assertEquals(0, s.enemy.getAttribute(Attribute.ATTACK_DAMAGE).getValue(), EPSILON, "no blow lands");
            assertEquals(speed * (1 - s.param("fear_slow") / 100),
                    s.enemy.getAttribute(Attribute.MOVEMENT_SPEED).getValue(), EPSILON, "slowed");
            assertEquals(1, displays(s, TextDisplay.class), "a trembling mark over the terrified enemy");
            assertEquals(5, displays(s, BlockDisplay.class), "the screaming specter");

            s.ticks(10);
            assertTrue(s.enemy.getLocation().getZ() > 5.5, "driven away: " + s.enemy.getLocation());
            s.clearHits();
            s.melee(s.enemy, s.owner, 5);
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "too terrified to strike the ghost");

            assertEquals(0, s.damageTaken(s.enemy2), EPSILON, "behind the ghost");
            assertModifierFree(s.enemy2, "ghost");
            s.ticks(s.paramTicks("fear_duration"));
            assertEquals(attack, s.enemy.getAttribute(Attribute.ATTACK_DAMAGE).getValue(), EPSILON, "brave again");
            assertEquals(speed, s.enemy.getAttribute(Attribute.MOVEMENT_SPEED).getValue(), EPSILON);
            s.melee(s.enemy, s.owner, 4);
            assertEquals(4 * (1 - s.param("ethereal_reduction") / 100), s.damageTaken(s.owner), EPSILON);
            assertStandingOnFloor(s, s.enemy, "the terrified enemy");
            s.ticks(10);
            assertEquals(0, displays(s), "the mark and the specter are gone");
            s.finishClean();
        }
    }

    @Test
    void ghostTerrorNeverRevealsAStealthedEnemy() {
        try (Scenario s = Scenario.ffa("ghost")) {
            s.place(s.enemy2, 0.5, -4.5, 0);
            s.aimAt(s.owner, s.enemy);
            s.owner.hidePlayer(s.h.hub.plugin(), s.enemy);
            s.assertSucceeded(s.active(), "공포");
            assertEquals(0, s.enemy.getAttribute(Attribute.ATTACK_DAMAGE).getValue(), EPSILON, "still terrified");
            assertEquals(0, displays(s, TextDisplay.class), "no mark over a hidden enemy");
            s.ticks(10);
            assertTrue(s.enemy.getLocation().getZ() > 5.0, "still driven away: " + s.enemy.getLocation());
            s.owner.showPlayer(s.h.hub.plugin(), s.enemy);
            s.ticks(s.paramTicks("fear_duration") + 10);
            assertEquals(0, displays(s));
            s.finishClean();
        }
    }

    @Test
    void ghostTerrorIsStoppedByWalls() {
        try (Scenario s = Scenario.ffa("ghost")) {
            s.aimAt(s.owner, s.enemy);
            s.world.wallAlongX(-4, 5, 3, 3, Material.STONE);
            s.assertSucceeded(s.active(), "공포");
            s.ticks(5);
            assertEquals(0, s.damageTaken(s.enemy) + s.damageTaken(s.enemy2), EPSILON);
            assertModifierFree(s.enemy, "ghost");
            s.world.wallAlongX(-4, 5, 3, 3, Material.AIR);
            s.finishClean();
        }
    }

    @Test
    void ghostPhaseIsUntouchableUnseenAndHarmlessUntilItBurstsOut() {
        try (Scenario s = Scenario.ffa("ghost")) {
            s.place(s.enemy, 0.5, 3.0, 180);
            s.place(s.enemy2, 0.5, 12.5, 180);
            double speed = s.owner.getAttribute(Attribute.MOVEMENT_SPEED).getValue();
            s.assertSucceeded(s.ultimate(), "유령화");
            s.ticks(2);
            assertFalse(s.enemy.canSee(s.owner), "unseen by enemies");
            assertTrue(s.owner.hasPotionEffect(PotionEffectType.INVISIBILITY));
            assertEquals(speed * (1 + s.param("phase_speed") / 100),
                    s.owner.getAttribute(Attribute.MOVEMENT_SPEED).getValue(), EPSILON, "swift");

            s.melee(s.enemy, s.owner, 6);
            hurt(s.owner, 6);
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "untouchable");
            s.melee(s.owner, s.enemy, 6);
            assertEquals(0, s.damageBy(s.owner, s.enemy), EPSILON, "a ghost cannot strike");
            assertFalse(s.active().succeeded(), "nor scream");

            s.ticks(s.paramTicks("phase_duration") + 2);
            assertTrue(s.enemy.canSee(s.owner), "seen again");
            assertFalse(s.owner.hasPotionEffect(PotionEffectType.INVISIBILITY));
            assertEquals(speed, s.owner.getAttribute(Attribute.MOVEMENT_SPEED).getValue(), EPSILON);
            assertEquals(s.param("burst_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "the burst");
            PotionEffect slow = s.enemy.getPotionEffect(PotionEffectType.SLOWNESS);
            assertNotNull(slow, "slowed by the scream");
            assertEquals(1, slow.getAmplifier());
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON, "out of reach");
            assertNull(s.enemy2.getPotionEffect(PotionEffectType.SLOWNESS));

            s.healAll();
            s.clearHits();
            s.melee(s.enemy, s.owner, 4);
            assertTrue(s.damageTaken(s.owner) > 0, "touchable again");
            s.ticks(30);
            assertEquals(0, displays(s), "the specter and shards are gone");
            s.finishClean();
        }
    }

    // ------------------------------------------------------------------ 팬텀 phantom

    @Test
    void phantomIsShieldedByNightAndHeight() {
        try (Scenario s = Scenario.ffa("phantom")) {
            double kept = 1 - s.param("night_reduction") / 100;
            s.world.setTime(6000);
            s.ticks(2);
            s.melee(s.enemy, s.owner, 10);
            assertEquals(10, s.damageTaken(s.owner), EPSILON, "on the ground by day");

            s.world.setTime(18000);
            s.healAll();
            s.clearHits();
            s.melee(s.enemy, s.owner, 10);
            assertEquals(10 * kept, s.damageTaken(s.owner), EPSILON, "by night");

            s.world.setTime(6000);
            s.world.physics().setEnabled(false);
            s.place(s.owner, 0.5, FlatWorld.SURFACE + 3, 0.5, 0, 0);
            s.healAll();
            s.clearHits();
            s.melee(s.enemy, s.owner, 10);
            assertEquals(10 * kept, s.damageTaken(s.owner), EPSILON, "high in the air");

            s.place(s.owner, 0.5, FlatWorld.SURFACE + 1, 0.5, 0, 0);
            s.healAll();
            s.clearHits();
            s.melee(s.enemy, s.owner, 10);
            assertEquals(10, s.damageTaken(s.owner), EPSILON, "a hop is not flight");
            s.world.physics().setEnabled(true);
            s.place(s.owner, 0.5, 0.5, 0);
            s.finishClean();
        }
    }

    @Test
    void phantomDiveSoarsGlaresAndFallsOnItsPrey() {
        try (Scenario s = Scenario.ffa("phantom")) {
            s.place(s.enemy, 0.5, 8.5, 180);
            s.place(s.enemy2, 20.5, 0.5, 90);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "급강하");
            double peak = s.owner.getY();
            for (int i = 0; i < 20; i++) {
                s.ticks(1);
                peak = Math.max(peak, s.owner.getY());
            }
            assertTrue(peak > FlatWorld.SURFACE + s.param("soar_height") - 1, "soared up to " + peak);
            assertEquals(1, displays(s, BlockDisplay.class), "a reticle on the prey while it glares");
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "not yet");
            s.ticks(30);
            assertEquals(1, s.hitsBy(s.owner, s.enemy).size(), "struck once");
            assertEquals(s.param("dive_damage"), s.damageBy(s.owner, s.enemy), EPSILON);
            assertTrue(s.owner.getLocation().getZ() > 5, "dived onto the prey: " + s.owner.getLocation());
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON, "far from the dive");
            assertStandingOnFloor(s, s.owner, "the diving phantom");
            assertStandingOnFloor(s, s.enemy, "the struck prey");
            s.ticks(20);
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "no fall damage from the dive: " + s.hits());
            assertEquals(0, displays(s), "the reticle is gone");
            s.finishClean();
        }
    }

    @Test
    void phantomDiveFollowsTheGazeWithoutPrey() {
        try (Scenario s = Scenario.ffa("phantom")) {
            s.place(s.enemy, 20.5, 0.5, 90);
            s.place(s.enemy2, -20.5, 0.5, 270);
            s.place(s.owner, 0.5, FlatWorld.SURFACE, 0.5, 0, 0);
            s.assertSucceeded(s.active(), "급강하");
            s.ticks(16);
            s.owner.setRotation(0, 45);
            s.ticks(30);
            assertTrue(s.owner.getLocation().getZ() > 4, "dived where it looked: " + s.owner.getLocation());
            assertStandingOnFloor(s, s.owner, "the diving phantom");
            s.ticks(20);
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "no fall damage: " + s.hits());
            s.finishClean();
        }
    }

    @Test
    void phantomSwarmSwoopsOnEveryEnemyAndSlowsThem() {
        try (Scenario s = Scenario.ffa("phantom")) {
            s.assertSucceeded(s.ultimate(), "불면의 밤");
            assertEquals(15, displays(s, BlockDisplay.class), "five phantoms of three displays each");
            s.ticks(10);
            assertEquals(0, s.damageTaken(s.enemy) + s.damageTaken(s.enemy2), EPSILON, "they gather first");
            s.ticks(14);
            for (TestPlayer enemy : s.enemies()) {
                assertEquals(s.param("swoop_damage"), s.damageBy(s.owner, enemy), EPSILON, enemy.getName());
                assertTrue(enemy.hasPotionEffect(PotionEffectType.SLOWNESS), enemy.getName() + " slowed");
            }
            ticksWithinBudget(s, s.paramTicks("night_duration"));
            int waves = (int) Math.ceil((s.paramTicks("night_duration") - 10) / (double) s.paramTicks("swoop_interval"));
            assertEquals(waves * s.param("swoop_damage"), s.damageBy(s.owner, s.enemy), EPSILON, waves + " swoops");
            s.ticks(40);
            assertEquals(0, displays(s), "the swarm left");
            s.finishClean();
        }
    }

    @Test
    void phantomSwoopCanBeDodged() {
        try (Scenario s = Scenario.ffa("phantom")) {
            s.place(s.enemy2, 0.5, 30.5, 180);
            s.assertSucceeded(s.ultimate(), "불면의 밤");
            s.ticks(10 + 8);
            s.place(s.enemy, 3.5, 4.5, 180);
            s.ticks(8);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "it stepped aside after the screech");
            s.finishClean();
        }
    }

    @Test
    void phantomNightLastsAsLongAsTheSwarm() {
        try (Scenario s = Scenario.ffa("phantom")) {
            s.world.setTime(6000);
            s.assertSucceeded(s.ultimate(), "불면의 밤");
            s.ticks(2);
            s.melee(s.enemy, s.owner, 10);
            assertEquals(10 * (1 - s.param("night_reduction") / 100), s.damageTaken(s.owner), EPSILON,
                    "the sleepless night shields the phantom by day");
            s.ticks(s.paramTicks("night_duration") + 40);
            s.healAll();
            s.clearHits();
            s.melee(s.enemy, s.owner, 10);
            assertEquals(10, s.damageTaken(s.owner), EPSILON, "day again");
            s.finishClean();
        }
    }

    @Test
    void phantomSwarmNeedsPrey() {
        try (Scenario s = Scenario.ffa("phantom")) {
            s.place(s.enemy, 0.5, 30.5, 180);
            s.place(s.enemy2, 30.5, 0.5, 90);
            assertFalse(s.ultimate().succeeded(), "nobody to hunt");
            assertEquals(100, s.participant(s.owner).gauge(), EPSILON, "the gauge is kept");
            assertEquals(0, displays(s));
            s.finishClean();
        }
    }

    // ------------------------------------------------------------------ display orientation (real clients)

    @Test
    void demonGateFacesTheDemonThroughItsTransformation() {
        try (Scenario s = Scenario.ffa("demon")) {
            s.place(s.owner, 4.5, 0.5, 0);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "지옥문");
            s.ticks(2);
            assertUnrotated(s);
            Vector toDemon = s.owner.getLocation().toVector().subtract(s.enemy.getLocation().toVector()).setY(0);
            List<BlockDisplay> portal = blocks(s, Material.NETHER_PORTAL);
            assertEquals(1, portal.size());
            assertSameDirection(toDemon, turned(portal.getFirst(), 0, 0, 1), "the portal faces the demon");
            List<BlockDisplay> posts = blocks(s, Material.CRYING_OBSIDIAN);
            assertEquals(2, posts.size());
            s.ticks(14);
            Vector across = center(posts.get(0)).subtract(center(posts.get(1))).setY(0);
            assertEquals(0, across.clone().normalize().dot(toDemon.clone().normalize()), 1.0E-3,
                    "the posts stand side by side across the demon's view");
            assertEquals(2.7, across.length(), 1.0E-3, "posts at either side of the gate");
            for (BlockDisplay sigil : blocks(s, Material.RED_STAINED_GLASS)) {
                assertEquals(0, turned(sigil, 0, 1, 0).angle(new Vector(0, 1, 0)), 1.0E-3, "sigils lie flat");
            }
            ticksWithinBudget(s, 120);
            s.finishClean();
        }
    }

    @Test
    void reaperScytheSweepsRoundInItsTransformation() {
        try (Scenario s = Scenario.ffa("reaper")) {
            s.place(s.owner, 0.5, 0.5, 30);
            s.assertSucceeded(s.active(), "낫 회전");
            ItemDisplay scythe = s.world.getEntities().stream().filter(ItemDisplay.class::isInstance)
                    .map(ItemDisplay.class::cast).findFirst().orElseThrow();
            s.ticks(1);
            Vector first = handle(scythe);
            s.ticks(2);
            Vector later = handle(scythe);
            assertUnrotated(s);
            double turnedBy = Math.toDegrees(Math.atan2(-later.getX(), later.getZ()))
                    - Math.toDegrees(Math.atan2(-first.getX(), first.getZ()));
            assertEquals(90, Math.floorMod(Math.round(turnedBy), 360), 1, "two steps of the sweep, turning right");
            Vector3f out = scythe.getTransformation().getTranslation();
            assertSameDirection(later, new Vector(out.x(), 0, out.z()), "the scythe reaches out along its handle");
            assertEquals(0, later.getY(), 1.0E-3, "the scythe sweeps flat");
            s.ticks(20);
            s.finishClean();
        }
    }

    /** The scythe's handle (the hoe sprite's diagonal) as the transformation turns it. */
    private static Vector handle(ItemDisplay scythe) {
        return turned(scythe, (float) Math.sqrt(0.5), (float) Math.sqrt(0.5), 0);
    }

    @Test
    void reaperSlashCutsAcrossTheBlow() {
        try (Scenario s = Scenario.ffa("reaper")) {
            s.place(s.enemy, 3.5, 3.5, 225);
            s.enemy.setHealth(10);
            s.aimAt(s.owner, s.enemy);
            s.melee(s.owner, s.enemy, 2);
            s.ticks(2);
            List<BlockDisplay> slash = blocks(s, Material.BLACK_CONCRETE);
            assertEquals(1, slash.size(), "the execution slash");
            assertUnrotated(s);
            Vector blow = s.enemy.getLocation().toVector().subtract(s.owner.getLocation().toVector()).setY(0);
            Vector bar = turned(slash.getFirst(), 1, 0, 0).setY(0);
            assertTrue(bar.length() > 0.5, "the bar lies mostly flat");
            assertEquals(0, bar.normalize().dot(blow.normalize()), 1.0E-3, "the bar crosses the line of the blow");
            s.ticks(20);
            s.finishClean();
        }
    }

    @Test
    void ghostSpecterAndAfterimageFaceTheirWay() {
        try (Scenario s = Scenario.ffa("ghost")) {
            s.place(s.enemy, 3.5, 4.5, 225);
            s.place(s.enemy2, -10.5, -10.5, 0);
            s.aimAt(s.owner, s.enemy);
            Vector look = s.owner.getLocation().getDirection().setY(0);
            s.assertSucceeded(s.active(), "공포");
            assertUnrotated(s);
            Vector head = new Vector();
            List<BlockDisplay> shroud = blocks(s, Material.WHITE_STAINED_GLASS);
            assertEquals(2, shroud.size(), "the specter's shroud and head");
            for (BlockDisplay glass : shroud) {
                assertSameDirection(look, turned(glass, 0, 0, 1), "the specter faces the scream");
                head.add(center(glass));
            }
            head.multiply(0.5);
            for (BlockDisplay face : blocks(s, Material.BLACK_CONCRETE)) {
                assertTrue(center(face).subtract(head).dot(look.clone().normalize()) > 0.1,
                        "eyes and mouth are on the front of the specter");
            }
            s.ticks(s.paramTicks("fear_duration") + 10);
            assertEquals(0, displays(s), "the specter and the fear mark are gone");

            s.place(s.owner, 0.5, 0.5, 45);
            s.owner.setBodyYaw(45);
            s.melee(s.enemy, s.owner, 4);
            List<BlockDisplay> image = blocks(s, Material.WHITE_STAINED_GLASS);
            assertEquals(2, image.size(), "the afterimage");
            assertUnrotated(s);
            for (BlockDisplay part : image) {
                assertSameDirection(yawVector(45), turned(part, 0, 0, 1), "the afterimage stands like the ghost");
            }
            ticksWithinBudget(s, 20);
            s.finishClean();
        }
    }

    @Test
    void phantomSwarmFliesHeadFirstAroundTheOwner() {
        try (Scenario s = Scenario.ffa("phantom")) {
            s.place(s.enemy, 0.5, 11.0, 180);
            s.place(s.enemy2, 11.0, 0.5, 90);
            s.assertSucceeded(s.ultimate(), "불면의 밤");
            s.ticks(14);
            assertUnrotated(s);
            Location owner = s.owner.getLocation();
            int orbiting = 0;
            for (BlockDisplay body : blocks(s, Material.BLUE_CONCRETE)) {
                Vector radial = body.getLocation().toVector().subtract(owner.toVector());
                double height = radial.getY();
                radial.setY(0);
                if (Math.abs(radial.length() - 2.6) > 0.15 || Math.abs(height - 4.5) > 0.4) {
                    continue; // swooping
                }
                orbiting++;
                Vector heading = turned(body, 0, 0, 1);
                assertEquals(0, heading.getY(), 1.0E-3, "an orbiting phantom flies level");
                assertEquals(0, heading.normalize().dot(radial.normalize()), 0.05, "and along its circle");
            }
            assertTrue(orbiting >= 3, "the free phantoms circle overhead: " + orbiting);
            ticksWithinBudget(s, 160);
            s.finishClean();
        }
    }

    @Test
    void phantomReticleLiesFlatWhereverTheGazeFalls() {
        try (Scenario s = Scenario.ffa("phantom")) {
            s.place(s.enemy, 0.5, 8.5, 180);
            s.place(s.enemy2, 20.5, 0.5, 90);
            s.enemy.setRotation(180, 40);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "급강하");
            boolean seen = false;
            for (int i = 0; i < 40; i++) {
                s.ticks(1);
                assertUnrotated(s);
                for (BlockDisplay reticle : blocks(s, Material.CYAN_STAINED_GLASS)) {
                    seen = true;
                    assertEquals(0, turned(reticle, 0, 1, 0).angle(new Vector(0, 1, 0)), 1.0E-3,
                            "the reticle lies flat on the ground");
                }
            }
            assertTrue(seen, "a reticle was shown");
            s.finishClean();
        }
    }
}
