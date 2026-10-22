package io.github.sx2xoox.mcpl.game.superpower.scenario;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import io.github.sx2xoox.mcpl.game.superpower.abilities.BuiltinAbilities;
import io.github.sx2xoox.mcpl.game.superpower.ability.Ability;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityRegistry;
import io.github.sx2xoox.mcpl.game.superpower.ability.ActivationResult;
import io.github.sx2xoox.mcpl.game.superpower.support.Scenario;
import io.github.sx2xoox.mcpl.game.superpower.support.TestPlayer;
import org.bukkit.Location;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.IronGolem;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.potion.PotionEffectType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 도박사의 잭팟: it borrows the ultimate of another ability and runs it on a freshly bound instance that never got
 * {@code onGrant}, ticks or event hooks ({@link AbilityInfo#ultimateStandalone()}). Every standalone ultimate is
 * borrowed here exactly like {@code GamblerAbility#fireBorrowed} does it, on a gambler owner, and must work, clean up
 * after itself and survive the owner's death.
 */
class GamblerJackpotTest {

    private static final double EPSILON = 1.0E-6;

    /** What a borrowed ultimate visibly does. */
    private enum Sign { DAMAGE, SLOW, SILENCE, TEMP_BLOCKS, WEAKNESS, GROWTH, ABSORPTION, GOLEM }

    private static final Map<String, Sign> SIGNS = Map.ofEntries(
            Map.entry("pyro", Sign.DAMAGE),
            Map.entry("thunder", Sign.DAMAGE),
            Map.entry("frost", Sign.TEMP_BLOCKS),
            Map.entry("aero", Sign.DAMAGE),
            Map.entry("shadow", Sign.DAMAGE),
            Map.entry("grapple", Sign.SLOW),
            Map.entry("chrono", Sign.SILENCE),
            Map.entry("titan", Sign.GROWTH),
            Map.entry("guardian", Sign.ABSORPTION),
            Map.entry("gravity", Sign.DAMAGE),
            Map.entry("earth", Sign.TEMP_BLOCKS),
            Map.entry("venom", Sign.DAMAGE),
            Map.entry("bomber", Sign.DAMAGE),
            Map.entry("summoner", Sign.GOLEM),
            Map.entry("healer", Sign.WEAKNESS),
            Map.entry("phoenix", Sign.DAMAGE));

    static Stream<String> standalone() {
        return new TreeSet<>(SIGNS.keySet()).stream();
    }

    private static Scenario gambler() {
        // No lucky dodge / double damage: the borrowed ultimate alone decides what happens.
        return Scenario.builder("gambler").param("dodge_chance", 0).param("crit_chance", 0).start();
    }

    /** Exactly what {@code GamblerAbility#fireBorrowed} does. */
    private static ActivationResult borrow(Scenario s, String id) {
        Ability borrowed = s.ctx().registry().create(id);
        borrowed.bind(s.owner, s.ctx(), s.ctx().paramsFor(id));
        return borrowed.onUltimate();
    }

    @Test
    void everyBuiltinUltimateIsClassified() {
        AbilityRegistry registry = new AbilityRegistry();
        BuiltinAbilities.registerAll(registry);
        Set<String> flagged = new TreeSet<>();
        for (AbilityRegistry.Entry entry : registry.all()) {
            if (entry.info().ultimateStandalone()) {
                flagged.add(entry.info().id());
            }
        }
        assertEquals(new TreeSet<>(SIGNS.keySet()), flagged, "the ultimates the gambler can borrow");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("standalone")
    void borrowedUltimateWorksOnAGambler(String id) {
        try (Scenario s = gambler()) {
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(borrow(s, id), "borrowed " + id);
            Set<Sign> seen = EnumSet.noneOf(Sign.class);
            for (int i = 0; i < 300; i++) {
                s.ticks(1);
                observe(s, seen);
            }
            assertTrue(seen.contains(SIGNS.get(id)), id + " should show " + SIGNS.get(id) + ", saw " + seen);
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "the borrowed " + id + " never hurts the gambler");
            assertModifierFree(s.owner, id);
            assertEquals(0, s.owner.getAbsorptionAmount(), EPSILON, id + ": no absorption left");
            assertFalse(s.owner.isFlying(), id);
            for (TestPlayer target : s.enemies()) {
                assertModifierFree(target, id);
                assertFalse(s.ctx().isSilenced(target), id);
            }
            assertEquals(0, s.h.hub.tempBlocks().size(), id + ": temporary blocks restored");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    private static void observe(Scenario s, Set<Sign> seen) {
        for (TestPlayer target : s.enemies()) {
            if (s.damageBy(s.owner, target) > 0) {
                seen.add(Sign.DAMAGE);
            }
            if (target.hasPotionEffect(PotionEffectType.SLOWNESS)) {
                seen.add(Sign.SLOW);
            }
            if (target.hasPotionEffect(PotionEffectType.WEAKNESS)) {
                seen.add(Sign.WEAKNESS);
            }
            if (s.ctx().isSilenced(target)) {
                seen.add(Sign.SILENCE);
            }
        }
        if (s.h.hub.tempBlocks().size() > 0) {
            seen.add(Sign.TEMP_BLOCKS);
        }
        if (s.owner.getAttribute(Attribute.SCALE).getValue() > 1.5) {
            seen.add(Sign.GROWTH);
        }
        if (s.owner.getAbsorptionAmount() > 0) {
            seen.add(Sign.ABSORPTION);
        }
        if (s.world.getEntities().stream().anyMatch(IronGolem.class::isInstance)) {
            seen.add(Sign.GOLEM);
        }
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

    @ParameterizedTest(name = "{0}")
    @MethodSource("standalone")
    void borrowedUltimateNeverHarmsTheGamblersAlly(String id) {
        try (Scenario s = Scenario.builder("gambler").teams().param("dodge_chance", 0).param("crit_chance", 0)
                .start()) {
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
        try (Scenario s = gambler()) {
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(borrow(s, id), "borrowed " + id);
            s.ticks(6);
            s.killOwner(s.enemy);
            s.ticks(1);
            for (Entity entity : s.world.getEntities()) {
                assertTrue(entity instanceof Player, id + ": " + entity.getType() + " outlived the gambler");
            }
            s.clearHits();
            s.ticks(300);
            assertTrue(s.hits().stream().noneMatch(hit -> s.owner.equals(hit.causer())),
                    id + ": the dead gambler dealt damage: " + s.hits());
            assertEquals(0, s.h.hub.tempBlocks().size(), id);
            for (TestPlayer target : s.enemies()) {
                assertModifierFree(target, id);
                assertFalse(s.ctx().isSilenced(target), id);
            }
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void jackpotSpinsAReelThenAnnouncesAndFiresAStandaloneUltimate() {
        try (Scenario s = gambler()) {
            Scenario.messages(s.enemy);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "잭팟");
            assertEquals(0, s.participant(s.owner).gauge(), EPSILON, "the gauge is spent");
            assertEquals(1, s.world.getEntities().stream().filter(TextDisplay.class::isInstance).count(),
                    "a slot machine reel over the gambler");
            s.ticks(s.paramTicks("jackpot_spin") + 2);
            List<String> announced = jackpotMessages(s.enemy);
            assertEquals(1, announced.size(), "the jackpot is announced to everyone: " + announced);
            assertTrue(SIGNS.keySet().stream().map(GamblerJackpotTest::abilityName)
                    .anyMatch(name -> announced.getFirst().contains("(" + name + ")")),
                    "names a standalone ability: " + announced.getFirst());
            s.ticks(300);
            assertTrue(s.world.getEntities().stream().noneMatch(TextDisplay.class::isInstance), "the reel is gone");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void jackpotOnlyBorrowsEnabledAbilities() {
        try (Scenario s = Scenario.builder("gambler").param("dodge_chance", 0).param("crit_chance", 0)
                .configure(h -> {
                    for (String id : SIGNS.keySet()) {
                        if (!id.equals("thunder")) {
                            h.game.abilities().setEnabled(h.game.registry(), id, false);
                        }
                    }
                }).start()) {
            String thunder = abilityName("thunder");
            for (int spin = 0; spin < 5; spin++) {
                Scenario.messages(s.enemy);
                s.layout();
                s.healAll();
                s.aimAt(s.owner, s.enemy);
                s.assertSucceeded(s.ultimate(), "잭팟 #" + spin);
                s.ticks(s.paramTicks("jackpot_spin") + 2);
                List<String> announced = jackpotMessages(s.enemy);
                assertEquals(1, announced.size());
                assertTrue(announced.getFirst().contains("(" + thunder + ")"),
                        "only 뇌신 is enabled, but the jackpot drew: " + announced.getFirst());
                s.ticks(120);
            }
            s.finishClean();
        }
    }

    private static List<String> jackpotMessages(TestPlayer player) {
        List<String> found = new ArrayList<>();
        for (String message : Scenario.messages(player)) {
            if (message.contains("★ 잭팟!")) {
                found.add(message);
            }
        }
        return found;
    }

    private static String abilityName(String id) {
        AbilityRegistry registry = new AbilityRegistry();
        BuiltinAbilities.registerAll(registry);
        AbilityRegistry.Entry entry = registry.get(id);
        if (entry == null) {
            throw new IllegalArgumentException(id);
        }
        return entry.info().name();
    }
}
