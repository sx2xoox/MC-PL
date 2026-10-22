package io.github.sx2xoox.mcpl.game.superpower.scenario;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import io.github.sx2xoox.mcpl.game.superpower.abilities.AnglerAbility;
import io.github.sx2xoox.mcpl.game.superpower.abilities.BeekeeperAbility;
import io.github.sx2xoox.mcpl.game.superpower.abilities.ChefAbility;
import io.github.sx2xoox.mcpl.game.superpower.abilities.FarmerAbility;
import io.github.sx2xoox.mcpl.game.superpower.ability.Ability;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import io.github.sx2xoox.mcpl.game.superpower.ability.ActivationResult;
import io.github.sx2xoox.mcpl.game.superpower.support.Scenario;
import io.github.sx2xoox.mcpl.game.superpower.support.TestPlayer;
import io.github.sx2xoox.mcpl.game.superpower.support.world.FlatWorld;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.Consumable;
import io.papermc.paper.datacomponent.item.FoodProperties;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffectType;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** 직업 계열 신규 능력: 양봉가, 낚시꾼, 농부, 요리사. */
class JobNewAbilitiesTest {

    private static final double EPSILON = 1.0E-6;
    private static final int DISPLAY_BUDGET = 24;

    static Stream<String> abilities() {
        return Stream.of("beekeeper", "angler", "farmer", "chef");
    }

    // ------------------------------------------------------------------ helpers

    private static long displays(Scenario s) {
        return s.world.getEntities().stream().filter(entity -> entity instanceof Display).count();
    }

    private static <T extends Entity> List<T> entities(Scenario s, Class<T> type) {
        List<T> found = new ArrayList<>();
        for (Entity entity : s.world.getEntities()) {
            if (type.isInstance(entity)) {
                found.add(type.cast(entity));
            }
        }
        return found;
    }

    /** Every visual display entity has a bounded lifetime: none is left once the effect is over. */
    private static void assertVisualsGone(Scenario s, String when) {
        assertEquals(0, displays(s), "no display entity is left " + when);
    }

    /** Runs {@code ticks} ticks checking the display budget every tick. */
    private static void ticksWithinBudget(Scenario s, int ticks, String what) {
        for (int i = 0; i < ticks; i++) {
            s.ticks(1);
            assertTrue(displays(s) <= DISPLAY_BUDGET, what + " stays within the display budget: " + displays(s));
        }
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

    /** Hits by the owner's ability (MAGIC) on {@code victim}. */
    private static long magicHits(Scenario s, Entity victim) {
        return s.hitsBy(s.owner, victim).stream().filter(hit -> hit.cause() == DamageCause.MAGIC).count();
    }

    private static double horizontal(Location a, Location b) {
        return Math.hypot(a.getX() - b.getX(), a.getZ() - b.getZ());
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

    /** Puts the enemies where the active of {@code id} reaches them, and aims. */
    private static void prepareActive(Scenario s, String id) {
        s.layout();
        if (id.equals("chef")) {
            // The frying pan is a melee swing.
            s.place(s.enemy, 0.5, 2.5, 180);
        }
        if (s.ally != null) {
            s.crossfireAlly();
            if (id.equals("chef")) {
                s.place(s.ally(), 1.4, 2.0, 180);
            }
        }
        s.aimAt(s.owner, s.enemy);
    }

    /** Puts the enemies where the ultimate of {@code id} reaches them, and aims. */
    private static void prepareUltimate(Scenario s, String id) {
        s.layout();
        if (s.ally != null) {
            s.crossfireAlly();
        }
        s.aimAt(s.owner, s.enemy);
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

    @Test
    void infoIsComplete() {
        for (AbilityInfo info : List.of(BeekeeperAbility.INFO, AnglerAbility.INFO, FarmerAbility.INFO,
                ChefAbility.INFO)) {
            assertFalse(info.passive().name().contains("TODO"), info.id());
            assertFalse(info.active().description().contains("TODO"), info.id());
            assertFalse(info.ultimate().description().contains("TODO"), info.id());
            assertTrue(info.ultimateStandalone(), info.id() + " has a standalone ultimate");
            for (String text : List.of(info.passive().description(), info.active().description(),
                    info.ultimate().description())) {
                java.util.regex.Matcher placeholder = java.util.regex.Pattern.compile("\\{([a-z0-9_]+)}")
                        .matcher(text);
                while (placeholder.find()) {
                    assertTrue(info.params().containsKey(placeholder.group(1)),
                            info.id() + " declares {" + placeholder.group(1) + "}");
                }
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("abilities")
    void wholeMatchHurtsEnemiesButNeverTheOwner(String id) {
        try (Scenario s = Scenario.ffa(id)) {
            s.ticks(40);
            prepareActive(s, id);
            s.clearHits();
            s.assertSucceeded(s.active(), id + " active");
            ticksWithinBudget(s, 160, id + " active");
            assertTrue(s.damageBy(s.owner, s.enemy) > 0, id + " active hurts the aimed enemy: " + s.hits());
            assertEquals(0, s.damageTaken(s.owner), EPSILON, id + " active never hurts its owner: " + s.hits());
            assertVisualsGone(s, "after the " + id + " active");

            prepareUltimate(s, id);
            s.healAll();
            s.clearHits();
            s.assertSucceeded(s.ultimate(), id + " ultimate");
            s.ticks(10);
            assertTrue(displays(s) > 0, id + " ultimate shows display entities");
            ticksWithinBudget(s, 290, id + " ultimate");
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
            prepareActive(s, id);
            Location allyStart = s.ally().getLocation();
            s.clearHits();
            s.assertSucceeded(s.active(), id + " active");
            AbilityLifecycleTest.watchAlly(s, allyStart, 160);

            prepareUltimate(s, id);
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
            prepareActive(s, id);
            s.assertSucceeded(s.active(), id + " active");
            s.ticks(6);
            assertOwnerDeathCleansUp(s, id + " active");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("abilities")
    void ownerDiesDuringUltimate(String id) {
        try (Scenario s = Scenario.ffa(id)) {
            s.ticks(20);
            prepareUltimate(s, id);
            s.assertSucceeded(s.ultimate(), id + " ultimate");
            s.ticks(25);
            assertOwnerDeathCleansUp(s, id + " ultimate");
        }
    }

    // ------------------------------------------------------------------ gambler borrows the ultimate

    @ParameterizedTest(name = "{0}")
    @MethodSource("abilities")
    void borrowedUltimateWorksOnAGambler(String id) {
        try (Scenario s = gambler().start()) {
            assertTrue(s.ctx().registry().get(id).info().ultimateStandalone(), id + " can be borrowed");
            prepareUltimate(s, id);
            s.assertSucceeded(borrow(s, id), "borrowed " + id);
            ticksWithinBudget(s, 300, "borrowed " + id);
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
            prepareUltimate(s, id);
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
            prepareUltimate(s, id);
            s.assertSucceeded(borrow(s, id), "borrowed " + id);
            s.ticks(12);
            assertOwnerDeathCleansUp(s, "borrowed " + id);
        }
    }

    // ================================================================== 양봉가 beekeeper

    @Test
    void beekeeperSwarmChasesStingsAndPoisonsTheAimedEnemy() {
        try (Scenario s = Scenario.ffa("beekeeper")) {
            s.place(s.enemy, 0.5, 8.5, 180);
            s.place(s.enemy2, 6.5, -6.5, 0);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "벌떼");
            assertFalse(s.active().succeeded(), "on cooldown");
            s.ticks(1);
            long bees = entities(s, ItemDisplay.class).size();
            assertEquals(s.param("swarm_bees"), bees, "a bee display for every bee");
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "the swarm still has to fly there");
            s.h.tickUntil(() -> s.damageBy(s.owner, s.enemy) > 0, 40, "the swarm reaches the enemy");
            assertTrue(s.enemy.hasPotionEffect(PotionEffectType.POISON), "stung enemies are poisoned");
            s.ticks(s.paramTicks("sting_duration") + 5);
            long stings = Math.round(s.param("sting_duration") / s.param("sting_interval"));
            assertEquals(stings, magicHits(s, s.enemy), "a sting every " + s.param("sting_interval") + " s");
            assertEquals(stings * s.param("sting_damage"), s.damageBy(s.owner, s.enemy), EPSILON);
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON, "only the aimed enemy is stung");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.ticks(60);
            assertVisualsGone(s, "after the swarm flew home");
            s.finishClean();
        }
    }

    @Test
    void beekeeperSwarmFliesAroundAWallThatGoesUpBetween() {
        try (Scenario s = Scenario.ffa("beekeeper")) {
            s.place(s.enemy, 0.5, 7.5, 180);
            s.place(s.enemy2, 8.5, -6.5, 0);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "벌떼");
            // The enemy ducks behind a wall right after the cast.
            s.world.wallAlongX(-3, 4, 3, 3, Material.STONE);
            for (int i = 0; i < 80 && s.damageBy(s.owner, s.enemy) <= 0; i++) {
                s.ticks(1);
                for (ItemDisplay bee : entities(s, ItemDisplay.class)) {
                    assertTrue(bee.getLocation().getBlock().isPassable(), "bees never fly through blocks: "
                            + bee.getLocation());
                }
            }
            assertTrue(s.damageBy(s.owner, s.enemy) > 0, "the bees found a way around the wall");
            s.ticks(120);
            s.world.wallAlongX(-3, 4, 3, 3, Material.AIR);
            assertVisualsGone(s, "after the swarm");
            s.finishClean();
        }
    }

    @Test
    void beekeeperBeesCannotStingAnEnemyInWater() {
        try (Scenario s = Scenario.ffa("beekeeper")) {
            s.enemy.setInWater(true);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "벌떼");
            s.ticks(120);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "the enemy dived into water");
            s.enemy.setInWater(false);
            s.ticks(40);
            assertVisualsGone(s, "after the swarm gave up");
            s.finishClean();
        }
    }

    @Test
    void beekeeperSwarmNeedsAVisibleAimedEnemy() {
        try (Scenario s = Scenario.ffa("beekeeper")) {
            s.place(s.owner, 0.5, FlatWorld.SURFACE, 0.5, 180, 0);
            assertFalse(s.active().succeeded(), "nobody aimed at");
            assertEquals(0, s.ctx().cooldownRemaining(s.owner), EPSILON, "a failed skill costs no cooldown");
            s.aimAt(s.owner, s.enemy);
            s.owner.hidePlayer(s.ctx().plugin(), s.enemy);
            assertFalse(s.active().succeeded(), "a stealthed enemy is not a target");
            s.owner.showPlayer(s.ctx().plugin(), s.enemy);
            assertVisualsGone(s, "after the failed casts");
            s.finishClean();
        }
    }

    @Test
    void beekeeperHoneyDropsOnEnemyHitsAndWearsOff() {
        try (Scenario s = Scenario.builder("beekeeper").param("honey_chance", 100).param("honey_cooldown", 0)
                .start()) {
            s.melee(s.enemy, s.owner, 1);
            assertEquals(0, s.owner.getAbsorptionAmount(), EPSILON, "the drop still has to fall");
            s.ticks(8);
            assertEquals(s.param("honey_absorption"), s.owner.getAbsorptionAmount(), EPSILON, "honey absorption");
            for (int i = 0; i < 5; i++) {
                s.melee(s.enemy2, s.owner, 0.5);
                s.ticks(8);
            }
            assertEquals(s.param("honey_max"), s.owner.getAbsorptionAmount(), EPSILON, "capped by honey_max");
            s.ticks(s.paramTicks("honey_duration") + 10);
            assertEquals(0, s.owner.getAbsorptionAmount(), EPSILON, "the honey wore off");
            assertModifierFree(s.owner, "after the honey");
            assertVisualsGone(s, "after the honey drops");
            s.finishClean();
        }
        try (Scenario s = Scenario.builder("beekeeper").param("honey_chance", 0).start()) {
            s.melee(s.enemy, s.owner, 1);
            s.ticks(10);
            assertEquals(0, s.owner.getAbsorptionAmount(), EPSILON, "no honey at 0%");
            s.finishClean();
        }
    }

    @Test
    void beekeeperHoneyHasACooldown() {
        try (Scenario s = Scenario.builder("beekeeper").param("honey_chance", 100).param("honey_absorption", 1)
                .start()) {
            s.melee(s.enemy, s.owner, 1);
            s.ticks(8);
            assertEquals(1, s.owner.getAbsorptionAmount(), EPSILON);
            s.ctx().damage(s.enemy, s.owner, 0.5);
            s.ticks(8);
            assertEquals(0.5, s.owner.getAbsorptionAmount(), EPSILON, "no second drop within the cooldown");
            s.ticks(s.paramTicks("honey_cooldown"));
            s.ctx().damage(s.enemy, s.owner, 0.5);
            s.ticks(8);
            assertEquals(1, s.owner.getAbsorptionAmount(), EPSILON, "ability hits shake honey loose too");
            s.finishClean();
        }
    }

    @Test
    void beekeeperQueenStingsEveryEnemyAroundAndFeedsTheTeam() {
        try (Scenario s = Scenario.teams("beekeeper")) {
            s.place(s.ally(), 2.5, 0.5, 0);
            s.ally().setHealth(10);
            s.owner.setHealth(10);
            s.assertSucceeded(s.ultimate(), "여왕벌");
            s.ticks(2);
            assertTrue(entities(s, Display.class).size() >= 9, "a block-built queen bee");
            ticksWithinBudget(s, s.paramTicks("ult_duration") + 50, "the queen");
            long waves = (long) Math.ceil((s.paramTicks("ult_duration") - 10)
                    / (double) s.paramTicks("ult_wave_interval"));
            for (TestPlayer target : s.enemies()) {
                long stings = magicHits(s, target);
                assertTrue(stings >= (waves - 1) * s.param("ult_bees") && stings <= waves * s.param("ult_bees"),
                        target.getName() + " is stung by every wave: " + stings);
                assertEquals(stings * s.param("ult_sting_damage"), s.damageBy(s.owner, target), EPSILON);
                assertTrue(target.hasPotionEffect(PotionEffectType.POISON), target.getName() + " is poisoned");
            }
            double feeds = s.param("ult_duration") - 1;
            assertTrue(s.ally().getHealth() >= 10 + feeds * s.param("ult_heal") - EPSILON,
                    "honey heals the ally: " + s.ally().getHealth());
            assertTrue(s.owner.getHealth() >= 10 + feeds * s.param("ult_heal") - EPSILON, "and the keeper");
            assertEquals(0, s.damageTaken(s.ally()), EPSILON);
            assertTrue(Scenario.hasNoHarmfulEffect(s.ally()));
            s.ticks(20);
            assertVisualsGone(s, "after the queen left");
            s.finishClean();
        }
    }

    @Test
    void beekeeperQueenNeedsVisibleEnemiesAround() {
        try (Scenario s = Scenario.ffa("beekeeper")) {
            s.place(s.enemy, 0.5, 20.5, 180);
            s.place(s.enemy2, -20.5, 0.5, 0);
            assertFalse(s.ultimate().succeeded(), "nobody within the queen's reach");
            assertEquals(100, s.participant(s.owner).gauge(), EPSILON, "the gauge is kept");
            s.place(s.enemy, 0.5, 4.5, 180);
            s.owner.hidePlayer(s.ctx().plugin(), s.enemy);
            assertFalse(s.ultimate().succeeded(), "a stealthed enemy does not count");
            s.owner.showPlayer(s.ctx().plugin(), s.enemy);
            assertVisualsGone(s, "after the failed calls");
            s.finishClean();
        }
    }

    // ================================================================== 낚시꾼 angler

    @Test
    void anglerHooksReelsAndSlamsTheFirstEnemyInFrontOfItself() {
        try (Scenario s = Scenario.ffa("angler")) {
            s.place(s.enemy, 0.5, 9.5, 180);
            s.place(s.enemy2, 7.5, -6.5, 0);
            s.aimAt(s.owner, s.enemy);
            s.match.setGauge(s.owner, 0);
            s.assertSucceeded(s.active(), "낚아채기");
            assertFalse(s.active().succeeded(), "on cooldown");
            s.h.tickUntil(() -> s.damageBy(s.owner, s.enemy) > 0, 20, "the bobber hooks the enemy");
            assertEquals(s.param("reel_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "the hook bites");
            assertTrue(s.participant(s.owner).gauge() >= s.param("catch_gauge"), "a catch charges the gauge");
            double peak = s.enemy.getLocation().getY();
            for (int i = 0; i < 30; i++) {
                s.ticks(1);
                peak = Math.max(peak, s.enemy.getLocation().getY());
            }
            assertTrue(peak >= FlatWorld.SURFACE + s.param("reel_height") - 0.5, "reeled up into the air: " + peak);
            s.tickUntilGrounded(s.enemy, 60);
            assertEquals(s.param("reel_damage") + s.param("slam_damage"), s.damageBy(s.owner, s.enemy), EPSILON,
                    "slammed down on landing");
            assertTrue(horizontal(s.enemy.getLocation(), s.owner.getLocation()) < 3.5,
                    "landed in front of the angler: " + s.enemy.getLocation());
            assertTrue(s.enemy.getLocation().getZ() > s.owner.getLocation().getZ(), "on the side it came from");
            assertTrue(s.enemy.hasPotionEffect(PotionEffectType.SLOWNESS), "flopping on the deck");
            assertTrue(s.hitsOf(s.enemy, DamageCause.FALL).isEmpty(), "no fall damage from the ride");
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON);
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.ticks(20);
            assertVisualsGone(s, "after the line was reeled in");
            s.finishClean();
        }
    }

    @Test
    void anglerMissGivesPartOfTheCooldownBack() {
        try (Scenario s = Scenario.ffa("angler")) {
            s.place(s.owner, 0.5, FlatWorld.SURFACE, 0.5, 180, -10);
            s.assertSucceeded(s.active(), "낚아채기");
            double full = s.ctx().cooldownRemaining(s.owner);
            s.ticks(25);
            double expected = full * (1 - s.param("miss_refund") / 100) - 25 / 20.0;
            assertEquals(expected, s.ctx().cooldownRemaining(s.owner), 0.2, "half the cooldown came back");
            assertEquals(0, s.damageTaken(s.enemy) + s.damageTaken(s.enemy2), EPSILON);
            assertVisualsGone(s, "after the miss");
            s.finishClean();
        }
    }

    @Test
    void anglerBobberIsStoppedByWalls() {
        try (Scenario s = Scenario.ffa("angler")) {
            s.place(s.enemy, 0.5, 8.5, 180);
            s.aimAt(s.owner, s.enemy);
            s.world.wallAlongX(-2, 3, 4, 3, Material.STONE);
            s.assertSucceeded(s.active(), "낚아채기");
            s.ticks(40);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "the wall caught the bobber");
            s.world.wallAlongX(-2, 3, 4, 3, Material.AIR);
            assertVisualsGone(s, "after the snag");
            s.finishClean();
        }
    }

    @Test
    void anglerLuckChargesTheGaugeByWaterAndInTheRain() {
        try (Scenario s = Scenario.ffa("angler")) {
            s.ticks(19);
            double bonus = 5 * s.param("luck_gauge");
            s.match.setGauge(s.owner, 0);
            s.ticks(100);
            double dry = s.participant(s.owner).gauge();

            s.world.setBlock(2, FlatWorld.FLOOR_Y + 1, 1, Material.WATER);
            s.match.setGauge(s.owner, 0);
            s.ticks(100);
            assertEquals(dry + bonus, s.participant(s.owner).gauge(), 0.6, "by the water");
            s.world.setBlock(2, FlatWorld.FLOOR_Y + 1, 1, Material.AIR);

            s.world.setStorm(true);
            s.match.setGauge(s.owner, 0);
            s.ticks(100);
            assertEquals(dry + bonus, s.participant(s.owner).gauge(), 0.6, "in the rain");
            s.world.setBlock(0, FlatWorld.FLOOR_Y + 4, 0, Material.STONE);
            s.match.setGauge(s.owner, 0);
            s.ticks(100);
            assertEquals(dry, s.participant(s.owner).gauge(), 0.6, "but not under a roof");
            s.world.setBlock(0, FlatWorld.FLOOR_Y + 4, 0, Material.AIR);
            s.world.setStorm(false);

            s.owner.setInWater(true);
            s.match.setGauge(s.owner, 0);
            s.ticks(100);
            assertEquals(dry + bonus, s.participant(s.owner).gauge(), 0.6, "in the water");
            s.owner.setInWater(false);
            assertVisualsGone(s, "the luck has no display entities");
            s.finishClean();
        }
    }

    @Test
    void anglerBigCatchBreachesUnderTheEnemiesThenBellyFlopsOnThem() {
        try (Scenario s = Scenario.ffa("angler")) {
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "대어");
            s.ticks(s.paramTicks("ult_warning") - 1);
            assertEquals(0, s.damageTaken(s.enemy) + s.damageTaken(s.enemy2), EPSILON, "a telegraphed bite first");
            s.ticks(3);
            for (TestPlayer target : s.enemies()) {
                assertEquals(s.param("ult_burst_damage"), s.damageBy(s.owner, target), EPSILON,
                        target.getName() + " is caught in the breach");
            }
            s.ticks(3);
            assertTrue(s.enemy.getLocation().getY() > FlatWorld.SURFACE + 0.3, "tossed into the air");
            assertTrue(entities(s, Display.class).size() >= 9, "a block-built salmon");
            ticksWithinBudget(s, 30, "the leap");
            for (TestPlayer target : s.enemies()) {
                assertEquals(s.param("ult_burst_damage") + s.param("ult_slam_damage"), s.damageBy(s.owner, target),
                        EPSILON, target.getName() + " is flattened by the belly-flop");
                assertTrue(target.hasPotionEffect(PotionEffectType.SLOWNESS), target.getName() + " is slowed");
                assertTrue(s.hitsOf(target, DamageCause.FALL).isEmpty(), "no fall damage");
            }
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "the angler stays dry");
            assertEquals(0.5, s.owner.getLocation().getZ(), 1.0E-3, "and in place");
            s.ticks(60);
            assertVisualsGone(s, "after the salmon sank");
            s.finishClean();
        }
    }

    @Test
    void anglerBigCatchCanBeDodgedDuringTheTelegraph() {
        try (Scenario s = Scenario.ffa("angler")) {
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "대어");
            s.place(s.enemy, 12.5, 4.5, 180);
            s.place(s.enemy2, -12.5, 4.5, 180);
            s.ticks(s.paramTicks("ult_warning") + 60);
            assertEquals(0, s.damageTaken(s.enemy) + s.damageTaken(s.enemy2), EPSILON, "both ran out of the circle");
            s.finishClean();
        }
    }

    @Test
    void anglerBigCatchNeedsGroundInReach() {
        try (Scenario s = Scenario.ffa("angler")) {
            s.place(s.owner, 0.5, FlatWorld.SURFACE, 0.5, 180, -80);
            assertFalse(s.ultimate().succeeded(), "no ground in the sky");
            assertEquals(100, s.participant(s.owner).gauge(), EPSILON, "the gauge is kept");
            assertVisualsGone(s, "nothing spawned");
            s.finishClean();
        }
    }

    // ================================================================== 농부 farmer

    @Test
    void farmerScytheReapsTheHalfCircleInFrontAndLeavesItBleeding() {
        try (Scenario s = Scenario.ffa("farmer")) {
            s.place(s.enemy, 0.5, 2.0, 180);
            s.place(s.enemy2, 0.5, -2.5, 0);
            s.assertSucceeded(s.active(), "낫질");
            s.ticks(8);
            assertEquals(s.param("sweep_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "reaped once");
            assertTrue(displays(s) >= 3 || s.hitsBy(s.owner, s.enemy).size() == 1, "a scythe swept across");
            s.ticks(s.paramTicks("bleed_duration") + 5);
            double bleeds = Math.floor(s.param("bleed_duration") / s.param("bleed_interval"));
            assertEquals(s.param("sweep_damage") + bleeds * s.param("bleed_damage"), s.damageBy(s.owner, s.enemy),
                    EPSILON, "then bleeds");
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON, "the enemy behind is spared");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.ticks(20);
            assertVisualsGone(s, "after the swing");
            s.finishClean();
        }
    }

    @Test
    void farmerScytheTipCutsDeepest() {
        try (Scenario s = Scenario.builder("farmer").param("bleed_damage", 0).start()) {
            s.place(s.enemy, 0.5, 3.6, 180);
            s.place(s.enemy2, 0.5, -6.5, 0);
            s.assertSucceeded(s.active(), "낫질");
            s.ticks(8);
            assertEquals(s.param("sweep_damage") + s.param("sweet_bonus"), s.damageBy(s.owner, s.enemy), EPSILON,
                    "caught by the tip of the blade");
            s.finishClean();
        }
    }

    @Test
    void farmerScytheSweepsFromTheRightFlankToTheLeft() {
        try (Scenario s = Scenario.builder("farmer").param("bleed_damage", 0).start()) {
            // Looking along +z, the farmer's right is -x.
            s.place(s.enemy, -2.5, 0.8, 90);
            s.place(s.enemy2, 2.5, 0.8, -90);
            s.assertSucceeded(s.active(), "낫질");
            s.ticks(8);
            List<Scenario.Hit> right = s.hitsBy(s.owner, s.enemy);
            List<Scenario.Hit> left = s.hitsBy(s.owner, s.enemy2);
            assertEquals(1, right.size(), "the right flank is reaped");
            assertEquals(1, left.size(), "and the left flank");
            assertTrue(right.getFirst().tick() < left.getFirst().tick(), "right first, the blade sweeps leftwards");
            s.finishClean();
        }
    }

    @Test
    void farmerScytheIsStoppedByWalls() {
        try (Scenario s = Scenario.ffa("farmer")) {
            s.place(s.enemy, 0.5, 3.0, 180);
            s.world.wallAlongX(-2, 3, 2, 3, Material.STONE);
            s.assertSucceeded(s.active(), "낫질");
            s.ticks(20);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "behind the wall");
            s.world.wallAlongX(-2, 3, 2, 3, Material.AIR);
            s.finishClean();
        }
    }

    @Test
    void farmerHarvestHealsMoreWithMoreEnemiesAround() {
        try (Scenario s = Scenario.ffa("farmer")) {
            s.place(s.enemy, 0.5, 2.0, 180);
            s.place(s.enemy2, 2.0, 4.5, 180);
            s.owner.setHealth(10);
            s.melee(s.owner, s.enemy, 5);
            double steal = s.param("harvest_steal") / 100;
            assertEquals(10 + 5 * steal * 2, s.owner.getHealth(), EPSILON, "two enemies around: double harvest");
            assertTrue(displays(s) > 0, "a crop sprouts where the blow landed");

            s.place(s.enemy2, 20.5, 4.5, 180);
            s.owner.setHealth(10);
            s.ticks(12);
            s.melee(s.owner, s.enemy, 5);
            assertEquals(10 + 5 * steal, s.owner.getHealth(), EPSILON, "alone: a single harvest");
            s.ticks(30);
            assertVisualsGone(s, "after the sprouts withered");
            s.finishClean();
        }
    }

    @Test
    void farmerGreatHarvestSlowsBindsHealsAndRollsPumpkins() {
        try (Scenario s = Scenario.teams("farmer")) {
            s.place(s.ally(), 2.5, 0.5, 0);
            s.ally().setHealth(10);
            s.owner.setHealth(10);
            double enemySpeed = baseSpeed(s.enemy);
            double allySpeed = speed(s.ally());
            s.assertSucceeded(s.ultimate(), "대풍년");
            s.ticks(3);
            for (TestPlayer target : s.enemies()) {
                assertEquals(enemySpeed * (1 - s.param("ult_slow") / 100), speed(target), 1.0E-9,
                        target.getName() + " wades through the wheat");
            }
            s.ticks(s.paramTicks("ult_grow"));
            for (TestPlayer target : s.enemies()) {
                assertEquals(0, speed(target), 1.0E-9, target.getName() + " is bound by the ripe ears");
                assertEquals(0, target.getAttribute(Attribute.JUMP_STRENGTH).getValue(), 1.0E-9, "and cannot jump");
            }
            assertEquals(allySpeed, speed(s.ally()), 1.0E-9, "the ally walks freely");
            ticksWithinBudget(s, s.paramTicks("ult_root") + 2, "the field");
            assertEquals(enemySpeed * (1 - s.param("ult_slow") / 100), speed(s.enemy), 1.0E-9, "slowed again");
            ticksWithinBudget(s, s.paramTicks("ult_duration") - s.paramTicks("ult_grow") - s.paramTicks("ult_root"),
                    "the field");
            for (TestPlayer target : s.enemies()) {
                assertTrue(s.damageBy(s.owner, target) >= s.param("pumpkin_damage") - EPSILON,
                        target.getName() + " is hit by a rolling pumpkin: " + s.hitsBy(s.owner, target));
            }
            assertTrue(s.ally().getHealth() >= 10 + 4 * s.param("ult_heal") - EPSILON,
                    "the ally heals in the field: " + s.ally().getHealth());
            assertTrue(s.owner.getHealth() >= 10 + 4 * s.param("ult_heal") - EPSILON, "and the farmer");
            assertEquals(0, s.damageTaken(s.ally()), EPSILON);
            assertTrue(Scenario.hasNoHarmfulEffect(s.ally()));
            s.ticks(60);
            for (TestPlayer target : s.enemies()) {
                assertModifierFree(target, "after the harvest");
                assertEquals(enemySpeed, speed(target), 1.0E-9);
            }
            assertVisualsGone(s, "after the harvest");
            s.finishClean();
        }
    }

    @Test
    void farmerFieldLetsGoOfEnemiesThatLeaveIt() {
        try (Scenario s = Scenario.builder("farmer").param("ult_root", 0).start()) {
            double enemySpeed = speed(s.enemy);
            s.assertSucceeded(s.ultimate(), "대풍년");
            s.ticks(3);
            assertTrue(speed(s.enemy) < enemySpeed, "caught in the field");
            s.place(s.enemy, 0.5, 16.5, 180);
            s.ticks(2);
            assertEquals(enemySpeed, speed(s.enemy), 1.0E-9, "free once out of the field");
            assertModifierFree(s.enemy, "escaped");
            s.ticks(s.paramTicks("ult_duration") + 60);
            s.finishClean();
        }
    }

    @Test
    void farmerPumpkinsRollTowardsTheirTarget() {
        try (Scenario s = Scenario.builder("farmer").param("ult_root", 0).param("pumpkin_damage", 0).start()) {
            s.place(s.enemy, 0.5, 5.5, 180);
            s.place(s.enemy2, -20.5, 0.5, 0);
            s.assertSucceeded(s.ultimate(), "대풍년");
            s.ticks(s.paramTicks("ult_grow") + 6);
            List<org.bukkit.entity.BlockDisplay> pumpkins = entities(s, org.bukkit.entity.BlockDisplay.class).stream()
                    .filter(display -> display.getBlock().getMaterial() == Material.JACK_O_LANTERN).toList();
            assertEquals(1, pumpkins.size(), "a pumpkin sprouted");
            double before = horizontal(pumpkins.getFirst().getLocation(), s.enemy.getLocation());
            s.ticks(3);
            double after = horizontal(pumpkins.getFirst().getLocation(), s.enemy.getLocation());
            assertTrue(after < before - 0.5, "rolling at the enemy: " + before + " -> " + after);
            s.ticks(s.paramTicks("ult_duration") + 60);
            assertVisualsGone(s, "after the harvest");
            s.finishClean();
        }
    }

    // ================================================================== 요리사 chef

    private static @Nullable ItemStack snack(Scenario s, Player player) {
        NamespacedKey key = new NamespacedKey(s.ctx().plugin(), "chef_snack");
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && !item.isEmpty() && item.getPersistentDataContainer().has(key,
                    PersistentDataType.STRING)) {
                return item;
            }
        }
        return null;
    }

    private static int snacks(Scenario s, Player player) {
        NamespacedKey key = new NamespacedKey(s.ctx().plugin(), "chef_snack");
        int count = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && !item.isEmpty() && item.getPersistentDataContainer().has(key,
                    PersistentDataType.STRING)) {
                count += item.getAmount();
            }
        }
        return count;
    }

    private static void eat(Scenario s, Player eater, ItemStack item) {
        s.h.server.getPluginManager().callEvent(new PlayerItemConsumeEvent(eater, item, EquipmentSlot.HAND));
    }

    @Test
    void chefCooksQuickSnacksThatHealTheTeamButNotEnemies() {
        try (Scenario s = Scenario.builder("chef").teams().param("snack_interval", 1).start()) {
            assertEquals(0, snacks(s, s.owner), "nothing cooked yet");
            s.ticks(25);
            ItemStack item = snack(s, s.owner);
            assertNotNull(item, "a fresh snack in the inventory");
            FoodProperties food = item.getData(DataComponentTypes.FOOD);
            assertNotNull(food);
            assertTrue(food.canAlwaysEat(), "edible even when full");
            Consumable consumable = item.getData(DataComponentTypes.CONSUMABLE);
            assertNotNull(consumable);
            assertEquals(s.param("snack_eat_time"), consumable.consumeSeconds(), 1.0E-6, "quick to eat");
            s.ticks(100);
            assertEquals(s.param("snack_max"), snacks(s, s.owner), "at most snack_max are kept");

            s.owner.setHealth(10);
            eat(s, s.owner, item);
            assertEquals(10 + s.param("snack_heal"), s.owner.getHealth(), EPSILON, "the cook eats");
            s.ally().setHealth(10);
            eat(s, s.ally(), item);
            assertEquals(10 + s.param("snack_heal"), s.ally().getHealth(), EPSILON, "a teammate eats");
            s.enemy.setHealth(10);
            eat(s, s.enemy, item);
            assertEquals(10, s.enemy.getHealth(), EPSILON, "for an enemy it is plain food");
            eat(s, s.owner, ItemStack.of(Material.BREAD));
            assertEquals(10 + s.param("snack_heal"), s.owner.getHealth(), EPSILON, "ordinary bread does nothing");

            s.eliminateOwner(null);
            assertEquals(0, snacks(s, s.owner), "the snacks are gone with the ability");
            assertNull(snack(s, s.owner));
            s.finishClean();
        }
    }

    @Test
    void chefFryingPanWhacksStunsAndSendsEnemiesFlying() {
        try (Scenario s = Scenario.ffa("chef")) {
            s.place(s.enemy, 0.5, 2.5, 180);
            s.place(s.enemy2, 0.5, -2.5, 0);
            s.assertSucceeded(s.active(), "프라이팬");
            assertEquals(s.param("pan_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "whacked at once");
            assertEquals(0, speed(s.enemy), 1.0E-9, "stunned: cannot move");
            assertEquals(0, s.enemy.getAttribute(Attribute.JUMP_STRENGTH).getValue(), 1.0E-9, "nor jump");
            s.ticks(2);
            assertFalse(entities(s, TextDisplay.class).isEmpty(), "팅!");
            s.ticks(s.paramTicks("pan_stun"));
            assertModifierFree(s.enemy, "after the stun");
            s.ticks(10);
            assertTrue(s.enemy.getLocation().getZ() > 2.5 + 2, "sent flying: " + s.enemy.getLocation());
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON, "the enemy behind is spared");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.ticks(20);
            assertVisualsGone(s, "after the swing");
            s.finishClean();
        }
    }

    @Test
    void chefFryingPanNeedsAnEnemyInReachAndInSight() {
        try (Scenario s = Scenario.ffa("chef")) {
            assertFalse(s.active().succeeded(), "4 blocks is out of the pan's reach");
            assertEquals(0, s.ctx().cooldownRemaining(s.owner), EPSILON, "a failed skill costs no cooldown");
            s.place(s.enemy, 0.5, 2.5, 180);
            s.world.wallAlongX(-2, 3, 1, 3, Material.STONE);
            assertFalse(s.active().succeeded(), "a wall is in the way");
            s.world.wallAlongX(-2, 3, 1, 3, Material.AIR);
            assertVisualsGone(s, "nothing spawned");
            s.finishClean();
        }
    }

    @Test
    void chefFeastFeedsAndBuffsTheTeamWhileHotDishesFly() {
        try (Scenario s = Scenario.teams("chef")) {
            s.place(s.ally(), 2.5, 1.5, 0);
            s.ally().setHealth(10);
            s.owner.setHealth(10);
            s.assertSucceeded(s.ultimate(), "만찬");
            s.ticks(2);
            assertTrue(displays(s) >= 8, "a table being laid");
            s.ticks(13);
            assertTrue(s.ally().getHealth() >= 10 + s.param("ult_heal") - EPSILON, "served: " + s.ally().getHealth());
            assertTrue(s.owner.getHealth() >= 10 + s.param("ult_heal") - EPSILON, "the cook eats too");
            assertTrue(s.ally().hasPotionEffect(PotionEffectType.SPEED), "a hearty meal: speed");
            assertTrue(s.ally().hasPotionEffect(PotionEffectType.STRENGTH), "and strength");
            ticksWithinBudget(s, s.paramTicks("ult_duration"), "the feast");
            for (TestPlayer target : s.enemies()) {
                long dishes = magicHits(s, target);
                assertTrue(dishes >= 3, target.getName() + " is pelted with hot dishes: " + dishes);
                assertEquals(dishes * s.param("ult_throw_damage"), s.damageBy(s.owner, target), EPSILON);
            }
            assertTrue(s.ally().getHealth() >= 10 + s.param("ult_heal") + 4 * s.param("ult_regen") - EPSILON,
                    "and keeps eating: " + s.ally().getHealth());
            assertEquals(0, s.damageTaken(s.ally()), EPSILON);
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.ticks(30);
            assertVisualsGone(s, "after the table was cleared");
            s.finishClean();
        }
    }

    @Test
    void chefHotDishesCanBeDodged() {
        try (Scenario s = Scenario.ffa("chef")) {
            s.assertSucceeded(s.ultimate(), "만찬");
            int tableDishes = 3;
            s.h.tickUntil(() -> entities(s, ItemDisplay.class).size() > tableDishes, 60, "the first hot dish");
            // Both enemies step aside while the dishes are in the air.
            s.place(s.enemy, 5.5, 4.5, 180);
            s.place(s.enemy2, -4.0, 6.5, 180);
            s.ticks(14);
            assertEquals(0, s.damageTaken(s.enemy) + s.damageTaken(s.enemy2), EPSILON, "the dishes smashed on the "
                    + "empty ground");
            s.ticks(s.paramTicks("ult_duration") + 40);
            s.finishClean();
        }
    }
}
