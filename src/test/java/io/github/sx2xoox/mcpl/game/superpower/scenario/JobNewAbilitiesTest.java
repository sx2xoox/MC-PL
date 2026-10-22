package io.github.sx2xoox.mcpl.game.superpower.scenario;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.stream.Stream;

import io.github.sx2xoox.mcpl.game.superpower.ability.Ability;
import io.github.sx2xoox.mcpl.game.superpower.ability.ActivationResult;
import io.github.sx2xoox.mcpl.game.superpower.support.Scenario;
import io.github.sx2xoox.mcpl.game.superpower.support.TestPlayer;
import org.bukkit.Location;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** 직업 계열 신규 능력: 양봉가, 낚시꾼, 농부, 요리사. */
class JobNewAbilitiesTest {

    private static final double EPSILON = 1.0E-6;

    static Stream<String> abilities() {
        return Stream.of("beekeeper", "angler");
    }

    // ------------------------------------------------------------------ helpers

    private static long displays(Scenario s) {
        return s.world.getEntities().stream().filter(entity -> entity instanceof Display).count();
    }

    private static void assertVisualsGone(Scenario s, String when) {
        assertEquals(0, displays(s), "no display entity is left " + when);
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

    private static long magicHits(Scenario s, Entity victim) {
        return s.hitsBy(s.owner, victim).stream().filter(hit -> hit.cause() == DamageCause.MAGIC).count();
    }

    /** Exactly what {@code GamblerAbility#fireBorrowed} does. */
    private static ActivationResult borrow(Scenario s, String id) {
        Ability borrowed = s.ctx().registry().create(id);
        borrowed.bind(s.owner, s.ctx(), s.ctx().paramsFor(id));
        return borrowed.onUltimate();
    }

    private static Scenario.Builder gambler() {
        return Scenario.builder("gambler").param("dodge_chance", 0).param("crit_chance", 0);
    }

    private static void prepareActive(Scenario s, String id) {
        s.layout();
        if (s.ally != null) {
            s.crossfireAlly();
        }
        s.aimAt(s.owner, s.enemy);
    }

    private static void prepareUltimate(Scenario s, String id) {
        s.layout();
        if (s.ally != null) {
            s.crossfireAlly();
        }
        s.aimAt(s.owner, s.enemy);
    }

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
            prepareActive(s, id);
            s.clearHits();
            s.assertSucceeded(s.active(), id + " active");
            s.ticks(160);
            assertTrue(s.damageBy(s.owner, s.enemy) > 0, id + " active hurts the aimed enemy: " + s.hits());
            assertEquals(0, s.damageTaken(s.owner), EPSILON, id + " active never hurts its owner: " + s.hits());
            assertVisualsGone(s, "after the " + id + " active");

            prepareUltimate(s, id);
            s.healAll();
            s.clearHits();
            s.assertSucceeded(s.ultimate(), id + " ultimate");
            s.ticks(10);
            assertTrue(displays(s) > 0, id + " ultimate shows display entities");
            assertTrue(displays(s) <= 24, id + " ultimate stays within the display budget: " + displays(s));
            s.ticks(290);
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

    @ParameterizedTest(name = "{0}")
    @MethodSource("abilities")
    void borrowedUltimateWorksOnAGambler(String id) {
        try (Scenario s = gambler().start()) {
            assertTrue(s.ctx().registry().get(id).info().ultimateStandalone(), id + " can be borrowed");
            prepareUltimate(s, id);
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
}
