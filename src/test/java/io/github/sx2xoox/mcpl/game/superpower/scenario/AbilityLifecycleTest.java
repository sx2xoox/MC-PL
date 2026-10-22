package io.github.sx2xoox.mcpl.game.superpower.scenario;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import io.github.sx2xoox.mcpl.game.superpower.ability.ActivationResult;
import io.github.sx2xoox.mcpl.game.superpower.support.Scenario;
import io.github.sx2xoox.mcpl.game.superpower.support.TestPlayer;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Every ability through a whole match on the flat test world: granted at the fight start, passive ticking, active and
 * ultimate used on enemies standing in front of the owner, the match ended and everything restored. Also the owner
 * dying in the middle of each skill, and team mode with an ally standing in the line of fire.
 *
 * <p>The illusionist is covered by {@link SpecialAbilitiesTest} (its copies cannot spawn on Paper, see the bug test
 * there).
 */
class AbilityLifecycleTest {

    /** Which skills of an ability hurt the aimed enemy in the default layout. */
    private record Expect(boolean activeHurts, boolean ultimateHurts) {
    }

    private static final Map<String, Expect> EXPECT = Map.ofEntries(
            Map.entry("pyro", new Expect(true, true)),
            Map.entry("thunder", new Expect(true, true)),
            Map.entry("frost", new Expect(true, true)),
            Map.entry("aero", new Expect(false, true)),
            Map.entry("blink", new Expect(false, false)),
            Map.entry("shadow", new Expect(false, true)),
            Map.entry("grapple", new Expect(true, false)),
            Map.entry("chrono", new Expect(false, false)),
            Map.entry("berserker", new Expect(true, false)),
            Map.entry("titan", new Expect(true, false)),
            Map.entry("guardian", new Expect(true, false)),
            Map.entry("vampire", new Expect(true, false)),
            Map.entry("gravity", new Expect(false, true)),
            Map.entry("earth", new Expect(true, false)),
            Map.entry("venom", new Expect(true, true)),
            Map.entry("mirror", new Expect(false, false)),
            Map.entry("archer", new Expect(true, true)),
            Map.entry("bomber", new Expect(true, true)),
            Map.entry("summoner", new Expect(false, true)),
            Map.entry("healer", new Expect(false, false)),
            Map.entry("pixie", new Expect(false, false)),
            Map.entry("phoenix", new Expect(false, true)),
            Map.entry("gambler", new Expect(false, false)));

    static Stream<String> abilities() {
        return Stream.of("pyro", "thunder", "frost", "aero", "blink", "shadow", "grapple", "chrono", "berserker",
                "titan", "guardian", "vampire", "gravity", "earth", "venom", "mirror", "archer", "bomber", "summoner",
                "healer", "pixie", "phoenix", "gambler");
    }

    // ------------------------------------------------------------------ helpers

    /** Sets up what an active needs to be usable (history to rewind, wounds to heal) and aims it. */
    static void prepareActive(Scenario s) {
        switch (s.abilityId) {
            case "chrono" -> {
                // Rewind needs a past: stand at the start for a while, then walk away.
                s.ticks(70);
                s.place(s.owner, 0.5, -5.5, 0);
                s.ticks(2);
            }
            case "healer" -> s.owner.setHealth(s.owner.getHealth() - 8);
            default -> {
            }
        }
        if (s.abilityId.equals("bomber")) {
            // A lobbed grenade: aim at the floor short of the enemy so it lands next to it.
            s.aimAtFloor(s.owner, 0.5, 2.5);
        } else {
            s.aimAt(s.owner, s.enemy);
        }
    }

    static void prepareUltimate(Scenario s) {
        s.layout();
        if (s.ally != null) {
            s.crossfireAlly();
        }
        s.aimAt(s.owner, s.enemy);
    }

    /** Damage the owner took from anything but the test's own setup. */
    static double ownerDamage(Scenario s) {
        return s.damageTaken(s.owner);
    }

    // ------------------------------------------------------------------ tests

    @ParameterizedTest(name = "{0}")
    @MethodSource("abilities")
    void wholeMatch(String id) {
        try (Scenario s = Scenario.ffa(id)) {
            s.ticks(60);
            s.assertNoProblems();

            prepareActive(s);
            s.clearHits();
            double enemyBefore = s.health(s.enemy);
            ActivationResult active = s.active();
            s.assertSucceeded(active, id + " active");
            s.ticks(100);
            if (EXPECT.get(id).activeHurts()) {
                assertTrue(s.damageTaken(s.enemy) > 0, id + " active hurts the aimed enemy (health "
                        + enemyBefore + " -> " + s.health(s.enemy) + ")");
            }
            if (!id.equals("gambler")) {
                assertEquals(0, ownerDamage(s), 1.0E-9, id + " active never hurts its owner: " + s.hits());
            }

            prepareUltimate(s);
            s.healAll();
            s.clearHits();
            s.assertSucceeded(s.ultimate(), id + " ultimate");
            s.ticks(300);
            if (EXPECT.get(id).ultimateHurts()) {
                assertTrue(s.damageTaken(s.enemy) > 0, id + " ultimate hurts the aimed enemy");
            }
            if (!id.equals("gambler")) {
                assertEquals(0, ownerDamage(s), 1.0E-9, id + " ultimate never hurts its owner: " + s.hits());
            }
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
            prepareUltimate(s);
            s.assertSucceeded(s.ultimate(), id + " ultimate");
            s.ticks(id.equals("gambler") ? 35 : 12); // the gambler's slot machine pays out after 1.5 s
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
        assertEquals(0, s.h.hub.tempBlocks().size(), id + ": temporary blocks expire after the owner's death");
        for (TestPlayer enemy : s.enemies()) {
            assertNoModifiersBut(enemy, Scenario.TANK.getKey());
            assertFalse(s.ctx().isSilenced(enemy), id + ": " + enemy.getName() + " is not silenced any more");
            assertFalse(enemy.isFlying(), id + ": " + enemy.getName() + " is not flying");
        }
        assertNoModifiersBut(s.owner, null);
        s.assertNoProblems();
        s.finishMatch();
        s.assertNoProblems();
        s.assertCleanWorld();
    }

    private static void assertNoModifiersBut(Player player, String allowedKey) {
        for (Attribute attribute : List.of(Attribute.MOVEMENT_SPEED, Attribute.JUMP_STRENGTH, Attribute.GRAVITY,
                Attribute.SCALE, Attribute.MAX_HEALTH, Attribute.KNOCKBACK_RESISTANCE, Attribute.ATTACK_DAMAGE,
                Attribute.MAX_ABSORPTION, Attribute.SAFE_FALL_DISTANCE, Attribute.FALL_DAMAGE_MULTIPLIER,
                Attribute.STEP_HEIGHT, Attribute.ENTITY_INTERACTION_RANGE)) {
            AttributeInstance instance = player.getAttribute(attribute);
            for (AttributeModifier modifier : instance.getModifiers()) {
                if (!modifier.getKey().getKey().equals(allowedKey)) {
                    fail(player.getName() + " kept " + modifier.getKey() + " on " + attribute.getKey());
                }
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("abilities")
    void teamModeNeverHarmsTheAlly(String id) {
        try (Scenario s = Scenario.teams(id)) {
            s.ticks(40);
            s.crossfireAlly();
            prepareActive(s);
            Location allyStart = s.ally().getLocation();
            s.clearHits();
            s.assertSucceeded(s.active(), id + " active");
            watchAlly(s, allyStart, 100);

            prepareUltimate(s);
            allyStart = s.ally().getLocation();
            s.assertSucceeded(s.ultimate(), id + " ultimate");
            watchAlly(s, allyStart, 200);
            s.assertNoProblems();

            s.finishMatch();
            s.assertNoProblems();
            s.assertCleanWorld();
        }
    }

    /** Runs {@code ticks} ticks checking every tick that nothing bad happened to the ally. */
    private static void watchAlly(Scenario s, Location start, int ticks) {
        String id = s.abilityId;
        TestPlayer ally = s.ally();
        for (int i = 0; i < ticks; i++) {
            s.ticks(1);
            assertEquals(0, s.damageTaken(ally), 1.0E-9, id + " never damages the ally: " + s.hits());
            assertTrue(Scenario.hasNoHarmfulEffect(ally), id + " never debuffs the ally: "
                    + ally.getActivePotionEffects());
            assertFalse(s.ctx().isSilenced(ally), id + " never silences the ally");
            assertEquals(0, ally.getFireTicks() > 0 ? 1 : 0, id + " never sets the ally on fire");
            Location at = ally.getLocation();
            assertTrue(at.distance(start) < 0.5, id + " never moves the ally (moved to " + at.toVector() + ")");
            for (Attribute attribute : List.of(Attribute.MOVEMENT_SPEED, Attribute.JUMP_STRENGTH,
                    Attribute.GRAVITY)) {
                for (AttributeModifier modifier : ally.getAttribute(attribute).getModifiers()) {
                    assertTrue(modifier.getAmount() >= 0, id + " never slows the ally: " + modifier.getKey());
                }
            }
        }
    }
}
