package io.github.sx2xoox.mcpl.game.superpower.scenario;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.github.sx2xoox.mcpl.core.game.GameState;
import io.github.sx2xoox.mcpl.game.superpower.ability.ActivationResult;
import io.github.sx2xoox.mcpl.game.superpower.support.Scenario;
import io.github.sx2xoox.mcpl.game.superpower.support.TestPlayer;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Entity;
import org.bukkit.entity.IronGolem;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Wolf;
import org.bukkit.potion.PotionEffectType;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Abilities meeting other participants' creations: enemy summons are valid targets that no skill may choke on, allied
 * summons are never harmed, and an owner leaving the server in the middle of its skills leaves nothing behind.
 */
class AbilityInteractionTest {

    static List<String> abilities() {
        return AbilityLifecycleTest.abilities().toList();
    }

    /** Living, valid summons (wolves and golems) in the world. */
    private static List<Mob> summons(Scenario s) {
        return s.world.getEntities().stream()
                .filter(entity -> entity instanceof Wolf || entity instanceof IronGolem)
                .filter(Entity::isValid)
                .map(Mob.class::cast)
                .toList();
    }

    /** {@code summoner} calls its wolf pack and its iron golem. */
    private static void callPack(Scenario s, Player summoner) {
        s.assertSucceeded(s.match.useActive(summoner), summoner.getName() + "'s 늑대 무리");
        s.match.setGauge(summoner, 100);
        s.assertSucceeded(s.match.useUltimate(summoner), summoner.getName() + "'s 철골렘");
    }

    // ------------------------------------------------------------------ enemy summons

    @ParameterizedTest(name = "{0}")
    @MethodSource("abilities")
    void skillsTreatEnemySummonsAsTargets(String id) {
        try (Scenario s = AbilityLifecycleTest.start(Scenario.builder(id).enemyAbility("summoner"), id)) {
            s.ticks(40);
            callPack(s, s.enemy);
            List<Mob> pack = summons(s);
            assertEquals(3, pack.size(), "two wolves and a golem around the enemy");
            for (Mob summon : pack) {
                assertTrue(s.ctx().isEnemy(s.owner, summon), "an enemy's summon is an enemy");
            }
            s.ticks(5);
            s.layout();
            s.healAll();

            AbilityLifecycleTest.prepareActive(s);
            s.clearHits();
            ActivationResult active = s.active();
            // The duelist refuses to challenge a summon (a wolf is no duellist), and the aim meets the pack first.
            if (!id.equals("duelist")) {
                s.assertSucceeded(active, id + " active among enemy summons");
            }
            s.ticks(60);
            AbilityLifecycleTest.prepareUltimate(s);
            s.assertSucceeded(s.ultimate(), id + " ultimate among enemy summons");
            s.ticks(200);
            s.assertNoProblems();

            s.finishMatch();
            s.assertNoProblems();
            s.assertCleanWorld();
        }
    }

    // ------------------------------------------------------------------ allied summons

    @ParameterizedTest(name = "{0}")
    @MethodSource("abilities")
    void skillsNeverHarmAlliedSummons(String id) {
        try (Scenario s = AbilityLifecycleTest.start(Scenario.builder(id).teams().allyAbility("summoner"), id)) {
            s.ticks(40);
            s.crossfireAlly();
            callPack(s, s.ally());
            List<Mob> pack = summons(s);
            assertEquals(3, pack.size(), "two wolves and a golem around the ally");
            for (Mob summon : pack) {
                assertTrue(s.ctx().isAlly(s.owner, summon), "a teammate's summon is an ally");
                assertFalse(s.ctx().isEnemy(s.owner, summon), "and never an enemy");
            }
            s.ticks(30); // the golem lands, everything settles
            s.layout();
            s.crossfireAlly();
            s.healAll();
            Map<Mob, Location> spots = new HashMap<>();
            for (Mob summon : pack) {
                spots.put(summon, summon.getLocation());
            }

            AbilityLifecycleTest.prepareActive(s);
            s.clearHits();
            s.assertSucceeded(s.active(), id + " active");
            watchSummons(s, pack, spots, 60);
            AbilityLifecycleTest.prepareUltimate(s);
            s.assertSucceeded(s.ultimate(), id + " ultimate");
            watchSummons(s, pack, spots, 200);
            s.assertNoProblems();

            s.finishMatch();
            s.assertNoProblems();
            s.assertCleanWorld();
        }
    }

    /** Ticks while checking that the owner's skills never hurt, debuff, ignite or move the allied summons. */
    private static void watchSummons(Scenario s, List<Mob> pack, Map<Mob, Location> spots, int ticks) {
        String id = s.abilityId;
        for (int i = 0; i < ticks; i++) {
            s.ticks(1);
            for (Mob summon : pack) {
                if (!summon.isValid()) {
                    fail(id + ": an allied " + summon.getType() + " disappeared");
                }
                assertEquals(0, s.damageTaken(summon), 1.0E-9, id + " never damages an allied "
                        + summon.getType() + ": " + s.hits());
                assertFalse(summon.getFireTicks() > 0, id + " never sets an allied summon on fire");
                for (PotionEffectType type : Scenario.HARMFUL) {
                    assertFalse(summon.hasPotionEffect(type), id + " never gives an allied summon " + type);
                }
                assertNoSlowdown(id, summon);
                assertTrue(summon.getLocation().distance(spots.get(summon)) < 0.5, id + " never moves an allied "
                        + summon.getType() + " (moved to " + summon.getLocation().toVector() + ")");
            }
        }
    }

    private static void assertNoSlowdown(String id, LivingEntity entity) {
        for (Attribute attribute : List.of(Attribute.MOVEMENT_SPEED, Attribute.JUMP_STRENGTH, Attribute.GRAVITY)) {
            AttributeInstance instance = entity.getAttribute(attribute);
            if (instance == null) {
                continue;
            }
            for (AttributeModifier modifier : instance.getModifiers()) {
                assertTrue(modifier.getAmount() >= 0, id + " never slows an allied " + entity.getType() + ": "
                        + modifier.getKey());
            }
        }
    }

    // ------------------------------------------------------------------ the match ends mid-skill

    @ParameterizedTest(name = "{0}")
    @MethodSource("abilities")
    void matchWonWhileSkillsAreInFlightCleansUp(String id) {
        try (Scenario s = AbilityLifecycleTest.start(Scenario.builder(id), id)) {
            s.ticks(40);
            castBoth(s);
            s.finishMatch();
            s.assertNoProblems();
            s.assertCleanWorld();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("abilities")
    void matchStoppedByAnAdminWhileSkillsAreInFlightCleansUp(String id) {
        try (Scenario s = AbilityLifecycleTest.start(Scenario.builder(id), id)) {
            s.ticks(40);
            castBoth(s);
            for (TestPlayer target : s.targets()) {
                target.getAttribute(Attribute.MAX_HEALTH).removeModifier(Scenario.TANK);
            }
            assertTrue(s.h.hub.games().stop(Component.text("test")), "an admin stops the match");
            assertEquals(GameState.CLOSED, s.session.state(), "stopped and restored right away");
            s.ticks(100);
            s.assertNoProblems();
            s.assertCleanWorld();
        }
    }

    /** The active, a few ticks, then the ultimate: both still running afterwards. */
    private static void castBoth(Scenario s) {
        AbilityLifecycleTest.prepareActive(s);
        s.assertSucceeded(s.active(), s.abilityId + " active");
        s.ticks(3);
        AbilityLifecycleTest.prepareUltimate(s);
        s.assertSucceeded(AbilityLifecycleTest.ultimateDuringActive(s), s.abilityId + " ultimate");
        s.ticks(5);
    }

    // ------------------------------------------------------------------ owner leaves mid-skill

    @ParameterizedTest(name = "{0}")
    @MethodSource("abilities")
    void ownerQuittingMidSkillLeavesNothingBehind(String id) {
        try (Scenario s = AbilityLifecycleTest.start(Scenario.builder(id), id)) {
            s.ticks(40);
            AbilityLifecycleTest.prepareActive(s);
            s.assertSucceeded(s.active(), id + " active");
            s.ticks(3);
            AbilityLifecycleTest.prepareUltimate(s);
            s.assertSucceeded(AbilityLifecycleTest.ultimateDuringActive(s), id + " ultimate");
            s.ticks(5);

            assertTrue(s.owner.disconnect(), "the owner leaves");
            assertFalse(s.session.isAlive(s.owner), "leaving eliminates");
            s.ticks(1);
            for (Entity entity : s.world.getEntities()) {
                assertTrue(entity instanceof Player, id + ": the owner's " + entity.getType() + " outlived it");
            }
            s.clearHits();
            s.ticks(300);
            for (Scenario.Hit hit : s.hits()) {
                if (s.owner.equals(hit.causer())) {
                    fail(id + ": the departed owner still dealt damage: " + hit);
                }
            }
            assertEquals(0, s.h.hub.tempBlocks().size(), id + ": temporary blocks expire");
            for (TestPlayer enemy : s.enemies()) {
                assertFalse(s.ctx().isSilenced(enemy), id + ": " + enemy.getName() + " is not silenced");
                assertTrue(enemy.canSee(s.owner), id + ": " + enemy.getName() + " would see the owner again");
                for (Attribute attribute : List.of(Attribute.MOVEMENT_SPEED, Attribute.JUMP_STRENGTH,
                        Attribute.GRAVITY, Attribute.SCALE)) {
                    for (AttributeModifier modifier : enemy.getAttribute(attribute).getModifiers()) {
                        fail(id + ": " + enemy.getName() + " kept " + modifier.getKey());
                    }
                }
            }
            s.assertNoProblems();

            s.finishMatch();
            assertTrue(s.owner.reconnect(), "the owner comes back");
            s.assertNoProblems();
            s.assertCleanWorld();
        }
    }
}
