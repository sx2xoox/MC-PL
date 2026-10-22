package io.github.sx2xoox.mcpl.game.superpower.scenario;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import io.github.sx2xoox.mcpl.game.superpower.abilities.BladeAbility;
import io.github.sx2xoox.mcpl.game.superpower.abilities.DuelistAbility;
import io.github.sx2xoox.mcpl.game.superpower.abilities.LancerAbility;
import io.github.sx2xoox.mcpl.game.superpower.abilities.NinjaAbility;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Spots;
import io.github.sx2xoox.mcpl.game.superpower.ability.Ability;
import io.github.sx2xoox.mcpl.game.superpower.ability.ActivationResult;
import io.github.sx2xoox.mcpl.game.superpower.support.DummyAbility;
import io.github.sx2xoox.mcpl.game.superpower.support.Scenario;
import io.github.sx2xoox.mcpl.game.superpower.support.TestPlayer;
import io.github.sx2xoox.mcpl.game.superpower.support.world.FlatWorld;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Wolf;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 무기 계열 (new): 검성, 창술사, 닌자, 결투가. Each ability through a whole match, the owner dying in the middle of
 * each skill, team mode with an ally in the line of fire, the Gambler borrowing the standalone ultimates, and the
 * mechanics of every passive, active and ultimate.
 */
class WeaponNewAbilitiesTest {

    private static final double EPSILON = 1.0E-6;

    /** Which skills of an ability hurt the aimed enemy in the default layout. */
    private record Expect(boolean activeHurts, boolean ultimateHurts) {
    }

    private static final Map<String, Expect> EXPECT = Map.of(
            "blade", new Expect(true, true),
            "lancer", new Expect(true, true),
            "ninja", new Expect(true, false),
            "duelist", new Expect(false, false));

    static Stream<String> abilities() {
        return Stream.of("blade", "lancer", "ninja", "duelist");
    }

    static Stream<String> standalone() {
        return Stream.of("blade", "lancer");
    }

    private static long blockDisplays(Scenario s) {
        return s.world.getEntities().stream().filter(BlockDisplay.class::isInstance).count();
    }

    private static long textDisplays(Scenario s) {
        return s.world.getEntities().stream().filter(TextDisplay.class::isInstance).count();
    }

    /** Exactly what {@code GamblerAbility#fireBorrowed} does. */
    private static ActivationResult borrow(Scenario s, String id) {
        Ability borrowed = s.ctx().registry().create(id);
        borrowed.bind(s.owner, s.ctx(), s.ctx().paramsFor(id));
        return borrowed.onUltimate();
    }

    private static Scenario gambler() {
        // No lucky dodge / double damage: the borrowed ultimate alone decides what happens.
        return Scenario.builder("gambler").param("dodge_chance", 0).param("crit_chance", 0).start();
    }

    private static void assertModifierFree(Player player, String id) {
        for (Attribute attribute : Registry.ATTRIBUTE) {
            AttributeInstance instance = player.getAttribute(attribute);
            if (instance == null) {
                continue;
            }
            for (AttributeModifier modifier : instance.getModifiers()) {
                if (!modifier.getKey().equals(Scenario.TANK)) {
                    fail(id + ": " + player.getName() + " kept " + modifier.getKey() + " on " + attribute.getKey());
                }
            }
        }
    }

    private static double horizontalDistance(Location a, Location b) {
        return Math.hypot(a.getX() - b.getX(), a.getZ() - b.getZ());
    }

    private static List<BlockDisplay> blockDisplayList(Scenario s) {
        return s.world.getEntities().stream().filter(BlockDisplay.class::isInstance).map(BlockDisplay.class::cast)
                .toList();
    }

    /**
     * A display must carry no rotation of its own: its model is turned by its transformation, and a yaw or pitch of
     * the entity would turn it a second time on a real client.
     */
    private static void assertUnturned(Display display, String what) {
        assertEquals(0, display.getLocation().getYaw(), 1.0E-3, what + ": the display's own yaw");
        assertEquals(0, display.getLocation().getPitch(), 1.0E-3, what + ": the display's own pitch");
    }

    /**
     * Where the model's local +z points in the world (with the display itself unturned). MockBukkit 4.118 hands the
     * left rotation back conjugated (in the right rotation's slot too); a faithful test double returns it as it was
     * set, with no right rotation (none of these poses has one).
     */
    private static Vector modelForward(Display display) {
        Transformation pose = display.getTransformation();
        Quaternionf rotation = pose.getLeftRotation();
        if (Math.abs(pose.getRightRotation().w()) < 1 - 1.0E-6) {
            rotation.conjugate();
        }
        Vector3f z = rotation.transform(new Vector3f(0, 0, 1));
        return new Vector(z.x(), z.y(), z.z());
    }

    // ------------------------------------------------------------------ every ability

    @Test
    void onlyTheBladeAndTheLancerLendTheirUltimateToTheGambler() {
        assertTrue(BladeAbility.INFO.ultimateStandalone());
        assertTrue(LancerAbility.INFO.ultimateStandalone());
        assertFalse(NinjaAbility.INFO.ultimateStandalone(), "the smoke stealth needs onRevoke and the hooks");
        assertFalse(DuelistAbility.INFO.ultimateStandalone(), "the arena needs onRevoke and the hooks");
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
            assertEquals(0, s.damageTaken(s.owner), 1.0E-9, id + " active never hurts its owner: " + s.hits());

            s.layout();
            s.aimAt(s.owner, s.enemy);
            s.healAll();
            s.clearHits();
            s.assertSucceeded(s.ultimate(), id + " ultimate");
            s.ticks(300);
            if (EXPECT.get(id).ultimateHurts()) {
                assertTrue(s.damageTaken(s.enemy) > 0, id + " ultimate hurts the aimed enemy");
            }
            assertEquals(0, s.damageTaken(s.owner), 1.0E-9, id + " ultimate never hurts its owner: " + s.hits());
            assertEquals(0, blockDisplays(s) + textDisplays(s), id + ": every display is gone after the skills");
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
            s.ticks(12);
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
            assertFalse(s.ctx().isSilenced(enemy), id);
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
            assertTrue(s.ally().canSee(s.owner), id + ": the ally always sees the owner");
            assertModifierFree(s.ally(), id);
            s.assertNoProblems();

            s.finishMatch();
            s.assertNoProblems();
            s.assertCleanWorld();
        }
    }

    // ------------------------------------------------------------------ the gambler borrows

    @ParameterizedTest(name = "{0}")
    @MethodSource("standalone")
    void borrowedUltimateWorksOnAGambler(String id) {
        try (Scenario s = gambler()) {
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(borrow(s, id), "borrowed " + id);
            s.ticks(300);
            assertTrue(s.damageBy(s.owner, s.enemy) > 0, "the borrowed " + id + " hurts the enemy: " + s.hits());
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "the borrowed " + id + " never hurts the gambler");
            assertModifierFree(s.owner, id);
            for (TestPlayer target : s.enemies()) {
                assertModifierFree(target, id);
            }
            assertEquals(0, blockDisplays(s), id + ": the borrowed displays are gone");
            s.assertNoProblems();
            s.finishClean();
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
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void aBorrowedLancerChargeStillSkewersAndHurls() {
        try (Scenario s = gambler()) {
            s.place(s.enemy2, 0.5, -6.5, 0);
            s.assertSucceeded(borrow(s, "lancer"), "borrowed 일섬 돌격");
            s.ticks(40);
            assertEquals(2, s.hitsBy(s.owner, s.enemy).size(), "skewered, then hurled: " + s.hits());
            assertTrue(s.enemy.getLocation().getZ() > s.owner.getLocation().getZ(), "carried ahead on the spear");
            s.ticks(60);
            assertEquals(0, blockDisplays(s));
            s.finishClean();
        }
    }

    // ------------------------------------------------------------------ 검성 blade

    @Test
    void bladeSwordHitsReleaseAWaveThatCutsEveryEnemyInFront() {
        try (Scenario s = Scenario.builder("blade").param("wave_chance", 100).start()) {
            s.owner.getInventory().setItemInMainHand(ItemStack.of(Material.IRON_SWORD));
            s.place(s.enemy, 0.5, 2.5, 180);
            s.place(s.enemy2, 0.8, 4.5, 180);
            s.melee(s.owner, s.enemy, 4);
            assertEquals(0, blockDisplays(s), "the wave leaves after the swing");
            s.ticks(2);
            assertTrue(blockDisplays(s) > 0, "a blade of light rides the wave");
            s.ticks(6);
            assertEquals(2, s.hitsBy(s.owner, s.enemy).size(), "the sword, then the wave");
            assertEquals(4 + s.param("wave_damage"), s.damageBy(s.owner, s.enemy), EPSILON);
            assertEquals(s.param("wave_damage"), s.damageBy(s.owner, s.enemy2), EPSILON,
                    "the wave cuts the enemy behind as well");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.ticks(10);
            assertEquals(0, blockDisplays(s), "the wave's blade faded");
            s.finishClean();
        }
    }

    @Test
    void bladeWaveNeedsASwordAndFollowsItsChance() {
        try (Scenario s = Scenario.builder("blade").param("wave_chance", 100).start()) {
            s.owner.getInventory().setItemInMainHand(ItemStack.of(Material.STICK));
            s.place(s.enemy, 0.5, 2.5, 180);
            s.melee(s.owner, s.enemy, 4);
            s.ticks(8);
            assertEquals(1, s.hitsBy(s.owner, s.enemy).size(), "no sword, no wave");
            s.finishClean();
        }
        try (Scenario s = Scenario.builder("blade").param("wave_chance", 0).start()) {
            s.owner.getInventory().setItemInMainHand(ItemStack.of(Material.IRON_SWORD));
            s.place(s.enemy, 0.5, 2.5, 180);
            for (int i = 0; i < 5; i++) {
                s.melee(s.owner, s.enemy, 4);
                s.ticks(8);
            }
            assertEquals(5, s.hitsBy(s.owner, s.enemy).size(), "a 0% chance never releases a wave");
            s.finishClean();
        }
    }

    @Test
    void bladeWaveBreaksOnAWall() {
        try (Scenario s = Scenario.builder("blade").param("wave_chance", 100).start()) {
            s.owner.getInventory().setItemInMainHand(ItemStack.of(Material.IRON_SWORD));
            s.place(s.enemy, 0.5, 1.8, 180);
            s.place(s.enemy2, 0.5, 4.5, 180);
            s.world.wallAlongX(-2, 2, 3, 3, Material.STONE);
            s.melee(s.owner, s.enemy, 4);
            s.ticks(10);
            assertEquals(4 + s.param("wave_damage"), s.damageBy(s.owner, s.enemy), EPSILON);
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON, "the wall stops the wave");
            s.world.wallAlongX(-2, 2, 3, 3, Material.AIR);
            s.finishClean();
        }
    }

    @Test
    void bladeGaleSlashFlashesThroughAndTheCutsOpenAMomentLater() {
        try (Scenario s = Scenario.ffa("blade")) {
            s.place(s.enemy2, 0.5, -6.5, 0);
            s.assertSucceeded(s.active(), "질풍참");
            assertEquals(0.5 + s.param("dash_distance"), s.owner.getLocation().getZ(), EPSILON,
                    "dashed the whole distance in an instant");
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "the cut has not opened yet");
            assertTrue(blockDisplays(s) > 0, "a cut of light along the path");
            s.ticks(8);
            assertEquals(1, s.hitsBy(s.owner, s.enemy).size(), "cut once");
            assertEquals(s.param("dash_damage"), s.damageBy(s.owner, s.enemy), EPSILON);
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON, "nobody behind the master is cut");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            assertFalse(s.active().succeeded(), "on cooldown");
            s.ticks(30);
            assertEquals(0, blockDisplays(s), "the cut lines faded");
            s.finishClean();
        }
    }

    @Test
    void bladeSlashAndWaveNeverCutAnEnemyAtTheMastersBack() {
        try (Scenario s = Scenario.builder("blade").param("wave_chance", 100).start()) {
            s.owner.getInventory().setItemInMainHand(ItemStack.of(Material.IRON_SWORD));
            s.place(s.enemy, 0.5, 2.5, 180);
            s.place(s.enemy2, 0.5, -0.4, 0); // pressed against the master's back
            s.melee(s.owner, s.enemy, 4);
            s.ticks(8);
            assertEquals(4 + s.param("wave_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "the wave goes forward");
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON, "not backwards");

            s.clearHits();
            s.assertSucceeded(s.active(), "질풍참");
            s.ticks(8);
            assertEquals(s.param("dash_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "the slash cuts ahead");
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON, "and leaves the one behind alone");
            s.finishClean();
        }
    }

    @Test
    void bladeGaleSlashStopsBeforeAWallAndFailsWhenBlocked() {
        try (Scenario s = Scenario.ffa("blade")) {
            s.world.wallAlongX(-3, 3, 3, 3, Material.STONE);
            s.assertSucceeded(s.active(), "질풍참");
            double z = s.owner.getLocation().getZ();
            assertTrue(z > 1.5 && z < 3, "stopped in front of the wall: " + z);
            assertFalse(s.world.hasCollisionsIn(s.owner.getBoundingBox().expand(-0.01)));
            s.ticks(10);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "the enemy behind the wall is safe");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);

            s.readyActive();
            s.place(s.owner, 0.5, 2.5, 0);
            ActivationResult blocked = s.active();
            assertFalse(blocked.succeeded(), "a wall right in front: no room to dash");
            assertEquals(0, s.ctx().cooldownRemaining(s.owner), EPSILON, "a failed dash costs no cooldown");
            s.world.wallAlongX(-3, 3, 3, 3, Material.AIR);
            s.finishClean();
        }
    }

    @Test
    void bladeGaleSlashNeverEndsOverTheVoid() {
        try (Scenario s = Scenario.ffa("blade")) {
            s.place(s.enemy, 6.5, 0.5, 0);
            s.place(s.enemy2, -6.5, 0.5, 0);
            voidStrip(s, 3, 9, Material.AIR);
            s.assertSucceeded(s.active(), "질풍참");
            assertTrue(s.owner.getLocation().getZ() < 3, "the dash ends before the void");
            s.ticks(20);
            assertTrue(s.owner.getLocation().getY() > FlatWorld.FLOOR_Y, "still on the ground");
            voidStrip(s, 3, 9, Material.STONE);
            s.finishClean();
        }
    }

    @Test
    void bladeHeavenlySwordsHoverThenDiveOntoEachEnemy() {
        try (Scenario s = Scenario.ffa("blade")) {
            s.assertSucceeded(s.ultimate(), "천검");
            s.ticks(5);
            assertEquals(s.param("ult_swords") * 3, blockDisplays(s), "swords of light (three parts each) hover");
            assertEquals(0, s.damageTaken(s.enemy) + s.damageTaken(s.enemy2), EPSILON, "they hover first");
            s.ticks(150);
            int swords = (int) s.param("ult_swords");
            for (TestPlayer target : s.enemies()) {
                List<Scenario.Hit> hits = s.hitsBy(s.owner, target);
                assertEquals(swords / 2, hits.size(), target.getName() + ": the swords are shared out: " + hits);
                for (Scenario.Hit hit : hits) {
                    assertEquals(s.param("ult_damage"), hit.damage(), EPSILON);
                }
            }
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            assertEquals(0, blockDisplays(s), "the planted swords dissolved");
            s.finishClean();
        }
    }

    @Test
    void bladeSwordsFollowAMovingTargetAndPreferEnemiesInSight() {
        try (Scenario s = Scenario.ffa("blade")) {
            s.world.wallAlongX(1, 4, 5, 3, Material.STONE);
            s.place(s.enemy2, 2.5, 6.5, 180);
            assertFalse(s.owner.hasLineOfSight(s.enemy2), "enemy2 hides behind the wall");
            s.assertSucceeded(s.ultimate(), "천검");
            s.ticks(10);
            s.place(s.enemy, -2.5, 3.5, 180);
            s.ticks(150);
            assertEquals(s.param("ult_swords"), s.hitsBy(s.owner, s.enemy).size(),
                    "every sword found the visible enemy where it went");
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON, "the hidden enemy is passed over");
            s.world.wallAlongX(1, 4, 5, 3, Material.AIR);
            s.finishClean();
        }
    }

    @Test
    void bladeSwordsCanBeOutrunOnceTheirMarkIsSet() {
        try (Scenario s = Scenario.ffa("blade")) {
            s.place(s.enemy2, 40.5, 0.5, 0); // out of range: every sword goes after the runner
            double radius = 5;
            double sprint = 0.28; // blocks per tick
            s.assertSucceeded(s.ultimate(), "천검");
            for (int t = 0; t < 120; t++) {
                double angle = t * sprint / radius;
                s.place(s.enemy, 0.5 + Math.cos(angle) * radius, 0.5 + Math.sin(angle) * radius, 0);
                s.ticks(1);
            }
            assertEquals(0, s.damageTaken(s.enemy), EPSILON,
                    "a sprinting enemy gets out of every marked spot before the sword lands: " + s.hits());
            s.ticks(40);
            assertEquals(0, blockDisplays(s), "the swords dissolved");
            s.finishClean();
        }
    }

    @Test
    void bladeKeepsItsDisplaysWithinTheBudget() {
        try (Scenario s = Scenario.ffa("blade")) {
            s.place(s.enemy, 0.5, 3.0, 180);
            s.place(s.enemy2, 0.8, 4.5, 180);
            s.assertSucceeded(s.ultimate(), "천검");
            s.ticks(3);
            assertEquals(s.param("ult_swords") * 3, blockDisplays(s), "the swords themselves always appear");
            s.assertSucceeded(s.active(), "질풍참 under the swords");
            long most = 0;
            for (int i = 0; i < 12; i++) {
                most = Math.max(most, blockDisplays(s));
                s.ticks(1);
            }
            assertTrue(most <= 24, "at most 24 displays at once: " + most);
            assertEquals(s.param("dash_damage"), s.hitsBy(s.owner, s.enemy).getFirst().damage(), EPSILON,
                    "the cuts still land without their blades of light");
            assertEquals(s.param("dash_damage"), s.hitsBy(s.owner, s.enemy2).getFirst().damage(), EPSILON);
            s.ticks(150);
            assertEquals(0, blockDisplays(s));
            s.finishClean();
        }
    }

    @Test
    void bladeSwordsNeverHuntAnEnemyHiddenByStealth() {
        try (Scenario s = Scenario.ffa("blade")) {
            s.owner.hidePlayer(s.ctx().plugin(), s.enemy2);
            s.assertSucceeded(s.ultimate(), "천검");
            s.ticks(150);
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON, "no marker ring gives the stealthed enemy away");
            assertEquals(s.param("ult_swords"), s.hitsBy(s.owner, s.enemy).size(), "every sword goes to the other");
            s.owner.showPlayer(s.ctx().plugin(), s.enemy2);

            s.owner.hidePlayer(s.ctx().plugin(), s.enemy);
            s.owner.hidePlayer(s.ctx().plugin(), s.enemy2);
            assertFalse(s.ultimate().succeeded(), "only stealthed enemies around: nothing to strike");
            s.owner.showPlayer(s.ctx().plugin(), s.enemy);
            s.owner.showPlayer(s.ctx().plugin(), s.enemy2);
            s.finishClean();
        }
    }

    @Test
    void bladeGaleSlashLandsOnTheFloorAndItsCutsLieAlongThePath() {
        try (Scenario s = Scenario.ffa("blade")) {
            // Facing east: a cut left in the display's own default facing (south) would be caught.
            s.place(s.owner, 0.5, 0.5, -90);
            s.place(s.enemy, 4.5, 0.5, 90);
            s.place(s.enemy2, -6.5, 6.5, 0);
            Vector east = new Vector(1, 0, 0);
            s.assertSucceeded(s.active(), "질풍참");
            Location feet = s.owner.getLocation();
            assertEquals(0.5 + s.param("dash_distance"), feet.getX(), EPSILON, "dashed the whole way east");
            assertEquals(FlatWorld.SURFACE, feet.getY(), EPSILON, "standing exactly on the floor, not sunk into it");
            assertTrue(Spots.isFree(s.world, feet, 0.6, 1.8), "the body is clear of the floor");
            List<BlockDisplay> line = blockDisplayList(s);
            assertEquals(2, line.size(), "a cut of light along the path");
            for (BlockDisplay part : line) {
                assertUnturned(part, "cut line");
                assertEquals(1, modelForward(part).dot(east), 1.0E-4, "the cut runs along the dash");
            }
            s.ticks(6);
            assertEquals(1, s.hitsBy(s.owner, s.enemy).size(), "the cut opened on the enemy");
            List<BlockDisplay> parts = blockDisplayList(s);
            assertEquals(6, parts.size(), "the cut line and two crossing blades (with their cores)");
            for (BlockDisplay part : parts) {
                assertUnturned(part, "cut");
                assertEquals(1, Math.abs(modelForward(part).dot(east)), 1.0E-4,
                        "the cuts lie along the dash, the crossing blades stand across it");
            }
            s.ticks(30);
            s.finishClean();
        }
    }

    @Test
    void bladeWaveBladeRidesTheWaveEdgeFirst() {
        try (Scenario s = Scenario.builder("blade").param("wave_chance", 100).start()) {
            s.owner.getInventory().setItemInMainHand(ItemStack.of(Material.IRON_SWORD));
            s.place(s.owner, 0.5, 0.5, -90);
            s.place(s.enemy, 2.5, 0.5, 90);
            s.place(s.enemy2, -6.5, 6.5, 0);
            Vector east = new Vector(1, 0, 0);
            s.melee(s.owner, s.enemy, 4);
            for (int tick = 0; tick < 3; tick++) {
                s.ticks(1);
                List<BlockDisplay> blade = blockDisplayList(s);
                assertEquals(2, blade.size(), "the blade of light rides the wave");
                for (BlockDisplay part : blade) {
                    assertUnturned(part, "wave blade");
                    assertEquals(1, modelForward(part).dot(east), 1.0E-4, "it flies out along the wave");
                }
            }
            s.ticks(20);
            s.finishClean();
        }
    }

    @Test
    void bladeSwordsPointWhereTheyGo() {
        try (Scenario s = Scenario.ffa("blade")) {
            s.place(s.owner, 0.5, 0.5, 70);
            s.assertSucceeded(s.ultimate(), "천검");
            s.ticks(8);
            List<BlockDisplay> parts = blockDisplayList(s);
            assertEquals(s.param("ult_swords") * 3, parts.size(), "the swords hover");
            for (BlockDisplay part : parts) {
                assertUnturned(part, "hovering sword");
                assertTrue(modelForward(part).getY() < -0.9, "points down over the master's head: "
                        + modelForward(part));
            }
            s.h.tickUntil(() -> s.damageTaken(s.enemy) + s.damageTaken(s.enemy2) > 0, 100, "the first sword lands");
            for (BlockDisplay part : blockDisplayList(s)) {
                assertUnturned(part, "sword");
            }
            s.ticks(150);
            assertEquals(0, blockDisplays(s));
            s.finishClean();
        }
    }

    @Test
    void bladeHeavenlySwordsNeedAnEnemyInRange() {
        try (Scenario s = Scenario.ffa("blade")) {
            s.place(s.enemy, 0.5, 30.5, 180);
            s.place(s.enemy2, 30.5, 0.5, 90);
            assertFalse(s.ultimate().succeeded(), "nobody within range");
            assertEquals(100, s.participant(s.owner).gauge(), EPSILON, "the gauge is kept");
            assertEquals(0, blockDisplays(s));
            s.finishClean();
        }
    }

    // ------------------------------------------------------------------ 창술사 lancer

    @Test
    void lancerSprintingHitsStrikeHarder() {
        try (Scenario s = Scenario.ffa("lancer")) {
            s.melee(s.owner, s.enemy, 4);
            assertEquals(4, s.damageBy(s.owner, s.enemy), EPSILON, "standing still: no bonus");
            s.clearHits();
            s.owner.setSprinting(true);
            s.melee(s.owner, s.enemy, 4);
            assertEquals(4 * (1 + s.param("sprint_bonus") / 100), s.damageBy(s.owner, s.enemy), EPSILON);
            s.ticks(10);
            s.owner.setSprinting(false);
            s.finishClean();
        }
    }

    @Test
    void lancerSpearPiercesOutAndStrikesAgainOnTheWayBack() {
        try (Scenario s = Scenario.ffa("lancer")) {
            s.place(s.enemy2, 0.5, 8.5, 180);
            s.ticks(1);
            s.assertSucceeded(s.active(), "창 투척");
            s.ticks(6);
            assertEquals(s.param("throw_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "pierced on the way out");
            assertEquals(3, blockDisplays(s), "a spear of three parts in flight");
            s.ticks(60);
            for (TestPlayer target : s.enemies()) {
                assertEquals(2, s.hitsBy(s.owner, target).size(), target.getName() + " is struck out and back");
                assertEquals(s.param("throw_damage") + s.param("return_damage"), s.damageBy(s.owner, target),
                        EPSILON);
            }
            assertTrue(s.enemy.getLocation().getZ() < 4.2, "the returning spear drags the enemy along: "
                    + s.enemy.getLocation().getZ());
            assertEquals(0, blockDisplays(s), "the spear is back in hand");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.finishClean();
        }
    }

    @Test
    void lancerSpearBitesIntoAWallAndStillComesBack() {
        try (Scenario s = Scenario.ffa("lancer")) {
            s.world.wallAlongX(-3, 3, 3, 3, Material.STONE);
            s.assertSucceeded(s.active(), "창 투척");
            s.ticks(4);
            assertTrue(blockDisplays(s) > 0, "the spear stuck in the wall");
            s.ticks(40);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "the wall shields the enemy");
            assertEquals(0, blockDisplays(s), "the spear came back");
            s.world.wallAlongX(-3, 3, 3, 3, Material.AIR);
            s.finishClean();
        }
    }

    @Test
    void lancerChargeSkewersCarriesAndHurlsTheEnemy() {
        try (Scenario s = Scenario.ffa("lancer")) {
            s.place(s.enemy2, 0.5, -6.5, 0);
            s.assertSucceeded(s.ultimate(), "일섬 돌격");
            assertFalse(s.ultimate().succeeded(), "already charging");
            s.ticks(4);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "the wind-up gives a moment to react");
            assertEquals(3, blockDisplays(s), "the spear is drawn");
            assertTrue(s.owner.getLocation().getZ() < 1, "not moving yet");
            s.ticks(40);
            assertEquals(2, s.hitsBy(s.owner, s.enemy).size(), "skewered, then hurled");
            assertEquals(s.param("ult_damage") + s.param("ult_fling_damage"), s.damageBy(s.owner, s.enemy), EPSILON);
            assertTrue(s.owner.getLocation().getZ() > 0.5 + s.param("ult_distance") - 2, "charged the whole lane");
            assertTrue(s.enemy.getLocation().getZ() > s.owner.getLocation().getZ(),
                    "the enemy was carried ahead and thrown on");
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON, "nobody behind is touched");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.ticks(60);
            assertEquals(0, blockDisplays(s));
            s.finishClean();
        }
    }

    @Test
    void lancerChargeMeasuresTheBorderFromWhereItReallyStarts() {
        try (Scenario s = Scenario.ffa("lancer")) {
            Location center = s.session.arena().center();
            double z = center.getZ();
            while (z < center.getZ() + 200 && s.ctx().isInsideArena(new Location(s.world, center.getX(),
                    FlatWorld.SURFACE, z + 0.5))) {
                z += 0.5;
            }
            double edge = z;
            double x = center.getX();
            s.place(s.owner, x, edge - 20, 0);
            s.place(s.enemy, x + 12, edge - 25, 0);
            s.place(s.enemy2, x - 12, edge - 25, 0);
            s.assertSucceeded(s.ultimate(), "일섬 돌격, far from the border");
            s.ticks(3);
            s.place(s.owner, x, edge - 5, 0); // walked up to the border while aiming
            s.ticks(40);
            assertTrue(s.ctx().isInsideArena(s.owner.getLocation()),
                    "the charge stops at the border, measured from where it began: " + s.owner.getLocation());
            assertTrue(s.owner.getLocation().getZ() > edge - 1.5, "and still runs all the way up to it");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.finishClean();
        }
    }

    @Test
    void lancerDodgedSpearsNeitherDragNorSkewer() {
        try (Scenario s = Scenario.builder("lancer").enemyAbility("gambler")
                .configure(h -> h.setParam("gambler", "dodge_chance", 100)).start()) {
            Location start = s.enemy.getLocation();
            s.assertSucceeded(s.active(), "창 투척");
            s.ticks(60);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "every spear hit was dodged");
            assertTrue(horizontalDistance(s.enemy.getLocation(), start) < 0.3,
                    "a dodged returning spear drags nobody: " + s.enemy.getLocation());

            s.layout();
            s.place(s.enemy2, 0.5, -6.5, 0);
            Location before = s.enemy.getLocation();
            s.assertSucceeded(s.ultimate(), "일섬 돌격");
            s.ticks(40);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "the skewer was dodged");
            assertTrue(horizontalDistance(s.enemy.getLocation(), before) < 0.3,
                    "so the enemy is not carried off on the spear: " + s.enemy.getLocation());
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.finishClean();
        }
    }

    @Test
    void lancerChargeStopsAtAWallAndAtTheVoid() {
        try (Scenario s = Scenario.ffa("lancer")) {
            s.place(s.enemy, 6.5, 0.5, 0);
            s.place(s.enemy2, -6.5, 0.5, 0);
            s.world.wallAlongX(-3, 3, 6, 3, Material.STONE);
            s.assertSucceeded(s.ultimate(), "일섬 돌격");
            s.ticks(40);
            double z = s.owner.getLocation().getZ();
            assertTrue(z < 6 && z > 3, "stopped at the wall: " + z);
            assertFalse(s.world.hasCollisionsIn(s.owner.getBoundingBox().expand(-0.01)));
            s.world.wallAlongX(-3, 3, 6, 3, Material.AIR);

            s.place(s.owner, 0.5, 0.5, 0);
            voidStrip(s, 6, 12, Material.AIR);
            s.assertSucceeded(s.ultimate(), "일섬 돌격 towards the void");
            s.ticks(40);
            assertTrue(s.owner.getLocation().getZ() < 6, "pulled up at the edge: " + s.owner.getLocation());
            assertTrue(s.owner.getLocation().getY() > FlatWorld.FLOOR_Y, "never fell in");

            s.place(s.owner, 0.5, 5.0, 0);
            assertFalse(s.ultimate().succeeded(), "no charge straight into the void");
            voidStrip(s, 6, 12, Material.STONE);
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.finishClean();
        }
    }

    @Test
    void lancerSpearFliesPointFirst() {
        try (Scenario s = Scenario.ffa("lancer")) {
            s.place(s.owner, 0.5, FlatWorld.SURFACE, 0.5, -90, -15); // east, a little upwards
            s.place(s.enemy, -6.5, 6.5, 0);
            s.place(s.enemy2, -6.5, -6.5, 0);
            Vector heading = s.owner.getEyeLocation().getDirection();
            s.assertSucceeded(s.active(), "창 투척");
            for (int tick = 0; tick < 4; tick++) {
                List<BlockDisplay> spear = blockDisplayList(s);
                assertEquals(3, spear.size(), "a spear of three parts");
                for (BlockDisplay part : spear) {
                    assertUnturned(part, "spear");
                    assertEquals(1, modelForward(part).dot(heading), 1.0E-4, "it flies point first");
                }
                s.ticks(1);
            }
            s.ticks(60);
            s.finishClean();
        }
    }

    @Test
    void lancerChargeSpearPointsAlongTheCharge() {
        try (Scenario s = Scenario.ffa("lancer")) {
            s.place(s.owner, 0.5, 0.5, -90); // charging east
            s.place(s.enemy, 6.5, -6.5, 0);
            s.place(s.enemy2, -6.5, -6.5, 0);
            Vector east = new Vector(1, 0, 0);
            s.assertSucceeded(s.ultimate(), "일섬 돌격");
            for (int tick = 0; tick < 12; tick++) {
                List<BlockDisplay> spear = blockDisplayList(s);
                assertEquals(3, spear.size(), "the charge spear");
                for (BlockDisplay part : spear) {
                    assertUnturned(part, "charge spear");
                    assertEquals(1, modelForward(part).dot(east), 1.0E-4, "it points along the charge");
                }
                s.ticks(1);
            }
            s.ticks(60);
            s.finishClean();
        }
    }

    @Test
    void lancerChargeEndsAtItsLengthEvenWhenTheServerSeesTheLancerLate() {
        try (Scenario s = Scenario.ffa("lancer")) {
            s.place(s.enemy, 6.5, -6.5, 0);
            s.place(s.enemy2, -6.5, -6.5, 0);
            Location start = s.owner.getLocation();
            s.assertSucceeded(s.ultimate(), "일섬 돌격");
            s.ticks(7); // the wind-up
            // From the launch on, a laggy client: it moves by exactly the velocities it is sent (and slides to a halt
            // like on the ground), and the server hears where it is only two ticks later.
            int lag = 2;
            s.world.physics().setEnabled(false);
            List<Location> client = new ArrayList<>();
            Location at = start.clone();
            Vector motion = new Vector();
            for (int i = 0; i < 40; i++) {
                s.ticks(1);
                Vector sent = s.owner.getVelocity();
                if (sent.lengthSquared() > 0) {
                    motion = sent.clone();
                    s.owner.setVelocity(new Vector());
                }
                at = at.clone().add(motion.getX(), 0, motion.getZ());
                motion.multiply(0.546);
                client.add(at);
                if (client.size() > lag) {
                    s.owner.teleport(client.get(client.size() - 1 - lag));
                }
            }
            s.world.physics().setEnabled(true);
            double travelled = horizontalDistance(start, at);
            double length = s.param("ult_distance");
            assertTrue(travelled < length + 0.6, "the charge did not run on past its end: " + travelled);
            assertTrue(travelled > length - 0.5, "but ran its whole length: " + travelled);
            s.place(s.owner, 0.5, 0.5, 0);
            s.ticks(20);
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.finishClean();
        }
    }

    /**
     * 일섬 돌격 at 0, 150 and 250 ms (the server hears where the charging lancer is none, three and five ticks late).
     * The charge's length was measured on the velocities sent, but the spear point still skewered where the server
     * last heard of the lancer: an enemy near the end of the lane was never skewered (the lancer's client had long run
     * past it), the skewered enemies rode at the lagging position, behind the lancer's back, and the hurl went off
     * blocks behind the spot where the lancer really stopped.
     */
    @Test
    void lancerChargeSkewersCarriesAndHurlsWhereALaggingLancerReallyIs() {
        double stop = Double.NaN;
        for (int ping : new int[] {0, 150, 250}) {
            try (Scenario s = Scenario.ffa("lancer")) {
                String run = ping + " ms";
                s.place(s.enemy, 0.5, 4.5, 180); // early in the lane
                s.place(s.enemy2, 0.5, 14.0, 180); // near its end (the lane is 14 blocks long)
                s.owner.setPing(ping);
                Location start = s.owner.getLocation();
                LaggyClients clients = new LaggyClients(s).add(s.owner, ping / 50).add(s.enemy, 0).add(s.enemy2, 0);
                s.assertSucceeded(s.ultimate(), "일섬 돌격");
                Location hurl = null;
                Location lancerAtHurl = null;
                List<Location> enemiesAtHurl = List.of();
                for (int i = 0; i < 40; i++) {
                    Location lancer = clients.at(s.owner);
                    List<Location> enemies = List.of(clients.at(s.enemy), clients.at(s.enemy2));
                    int heard = s.owner.getHeardSounds().size();
                    clients.tick();
                    if (hurl == null) {
                        hurl = soundAt(s.owner, heard, "item.mace.smash_ground_heavy");
                        lancerAtHurl = lancer;
                        enemiesAtHurl = enemies;
                    }
                }
                Location end = clients.at(s.owner);
                clients.close();
                for (TestPlayer enemy : s.enemies()) {
                    assertEquals(2, s.hitsBy(s.owner, enemy).size(),
                            enemy.getName() + " is skewered, then hurled: " + run + ", " + s.hits());
                }
                double travelled = horizontalDistance(start, end);
                double length = s.param("ult_distance");
                assertTrue(travelled > length - 0.3 && travelled < length + 0.8,
                        "the charge runs its length (and a little glide): " + run + ", " + travelled);
                if (Double.isNaN(stop)) {
                    stop = travelled;
                } else {
                    assertEquals(stop, travelled, 0.15, "and stops at the same place whatever the ping: " + run);
                }
                assertNotNull(hurl, "the charge ends in a hurl: " + run);
                assertEquals(lancerAtHurl.getZ() + 1.6, hurl.getZ(), 0.3,
                        "right in front of the lancer as it stops: " + run + ", " + lancerAtHurl);
                for (Location enemy : enemiesAtHurl) {
                    double ahead = enemy.getZ() - lancerAtHurl.getZ();
                    assertTrue(ahead > 0.8 && ahead < 3.8,
                            "the enemies ride on the spear point, ahead of the lancer: " + run + ", " + ahead);
                }
                s.place(s.owner, 0.5, 0.5, 0);
                s.layout();
                s.ticks(60);
                assertEquals(0, s.damageTaken(s.owner), EPSILON);
                assertEquals(0, blockDisplays(s));
                s.finishClean();
            }
        }
    }

    /**
     * Charging into a wall at 0, 150 and 250 ms: the lancer's client stops at the wall while the steps sent run on past
     * it (the server sees it stand still only a round trip later). The charge stops at the wall, the hurl goes off
     * there, and an enemy behind the wall is never skewered through it.
     */
    @Test
    void lancerChargeNeverSkewersThroughAWallWhateverThePing() {
        for (int ping : new int[] {0, 150, 250}) {
            try (Scenario s = Scenario.ffa("lancer")) {
                String run = ping + " ms";
                s.place(s.enemy, 0.5, 9.5, 180); // behind the wall
                s.place(s.enemy2, -6.5, 0.5, 0);
                s.world.wallAlongX(-3, 3, 6, 3, Material.STONE);
                s.owner.setPing(ping);
                int heard = s.owner.getHeardSounds().size();
                LaggyClients clients = new LaggyClients(s).add(s.owner, ping / 50);
                s.assertSucceeded(s.ultimate(), "일섬 돌격");
                for (int i = 0; i < 40; i++) {
                    clients.tick();
                }
                Location end = clients.at(s.owner);
                clients.close();
                assertTrue(end.getZ() > 5.4 && end.getZ() < 5.75, "stopped at the wall: " + run + ", " + end);
                assertEquals(0, s.damageTaken(s.enemy), EPSILON, "nobody is skewered through the wall: " + run);
                Location hurl = soundAt(s.owner, heard, "item.mace.smash_ground_heavy");
                assertNotNull(hurl, "the charge ends in a hurl: " + run);
                assertEquals(end.getZ() + 1.6, hurl.getZ(), 0.3, "right at the wall: " + run);
                s.world.wallAlongX(-3, 3, 6, 3, Material.AIR);
                s.place(s.owner, 0.5, 0.5, 0);
                s.ticks(60);
                assertEquals(0, s.damageTaken(s.owner), EPSILON);
                s.finishClean();
            }
        }
    }

    /** Where {@code listener} heard the first sound named like {@code key} since {@code from}, null if none. */
    private static @Nullable Location soundAt(TestPlayer listener, int from, String key) {
        var heard = listener.getHeardSounds();
        for (int i = from; i < heard.size(); i++) {
            if (heard.get(i).getSound().contains(key) && heard.get(i).getLocation() != null) {
                return heard.get(i).getLocation();
            }
        }
        return null;
    }

    /**
     * Players whose real position is their client's, for the bugs a plain world simulation hides. A real server never
     * reads a player's motion back ({@code Player#getVelocity} is only what it last sent): every client tick each
     * player moves by the last velocity the server sent it (then gravity, drag and ground friction over the flat floor;
     * a wall stops it flush against it), and the server sees where it is {@code lag} ticks late. The harness's own
     * physics is off while they run.
     */
    private static final class LaggyClients {

        /** Marks the velocity the server last sent, to tell whether a skill sent a new one during a tick. */
        private static final double MARK = 1.0E-9;
        private final Scenario s;
        private final List<Client> clients = new ArrayList<>();

        /** Create it before the skill is cast: a velocity the cast sets must count as new. */
        LaggyClients(Scenario s) {
            this.s = s;
            s.world.physics().setEnabled(false);
        }

        LaggyClients add(TestPlayer player, int lag) {
            clients.add(new Client(s, player, lag));
            return this;
        }

        /** One server tick, then one client tick of every player with whatever the server sent it. */
        void tick() {
            for (Client client : clients) {
                client.mark();
            }
            s.ticks(1);
            for (Client client : clients) {
                client.step();
            }
        }

        /** Where {@code player} really is (its client). */
        Location at(TestPlayer player) {
            for (Client client : clients) {
                if (client.player.equals(player)) {
                    return client.at.clone();
                }
            }
            throw new IllegalArgumentException(player.getName());
        }

        /** Back to the harness's physics, every player where its client really is. */
        void close() {
            for (Client client : clients) {
                Location real = client.at.clone();
                Location look = client.player.getLocation();
                real.setYaw(look.getYaw());
                real.setPitch(look.getPitch());
                client.player.teleport(real);
                client.player.setVelocity(new Vector());
            }
            s.world.physics().setEnabled(true);
        }

        private static final class Client {
            private final Scenario s;
            private final TestPlayer player;
            private final Deque<Location> late = new ArrayDeque<>();
            private final Location at;
            private final Vector velocity = new Vector();
            private Vector sent = new Vector();
            private Vector marked = new Vector();

            Client(Scenario s, TestPlayer player, int lag) {
                this.s = s;
                this.player = player;
                this.at = player.getLocation();
                for (int i = 0; i < lag; i++) {
                    late.addLast(at.clone());
                }
            }

            void mark() {
                marked = sent.clone().setX(sent.getX() + MARK);
                player.setVelocity(marked);
            }

            void step() {
                Vector now = player.getVelocity();
                if (Double.compare(now.getX(), marked.getX()) != 0 || Double.compare(now.getY(), marked.getY()) != 0
                        || Double.compare(now.getZ(), marked.getZ()) != 0) {
                    sent = now.clone();
                    velocity.copy(now);
                }
                at.setY(at.getY() + velocity.getY());
                boolean grounded = at.getY() <= FlatWorld.SURFACE;
                if (grounded) {
                    at.setY(FlatWorld.SURFACE);
                }
                // One horizontal axis at a time, like the client: a wall stops the body flush against it.
                double dx = sweep(velocity.getX(), true);
                at.setX(at.getX() + dx);
                if (dx != velocity.getX()) {
                    velocity.setX(0);
                }
                double dz = sweep(velocity.getZ(), false);
                at.setZ(at.getZ() + dz);
                if (dz != velocity.getZ()) {
                    velocity.setZ(0);
                }
                double gravity = player.hasPotionEffect(PotionEffectType.SLOW_FALLING) && velocity.getY() <= 0
                        ? 0.01 : 0.08;
                double vy = (velocity.getY() - gravity) * 0.98;
                double friction = grounded ? 0.546 : 0.91;
                velocity.setX(velocity.getX() * friction).setY(grounded && vy < 0 ? 0 : vy)
                        .setZ(velocity.getZ() * friction);
                late.addLast(at.clone());
                Location seen = late.removeFirst();
                Location look = player.getLocation();
                seen.setYaw(look.getYaw());
                seen.setPitch(look.getPitch());
                player.teleport(seen);
            }

            /** How far the body gets along x (or z) towards {@code delta} before a block stops it. */
            private double sweep(double delta, boolean alongX) {
                if (delta == 0 || fits(delta, alongX)) {
                    return delta;
                }
                double free = 0;
                double blocked = Math.abs(delta);
                for (int i = 0; i < 30; i++) {
                    double mid = (free + blocked) / 2;
                    if (fits(Math.copySign(mid, delta), alongX)) {
                        free = mid;
                    } else {
                        blocked = mid;
                    }
                }
                return Math.copySign(free, delta);
            }

            private boolean fits(double delta, boolean alongX) {
                Location next = at.clone().add(alongX ? delta : 0, 0, alongX ? 0 : delta);
                return Spots.isFree(s.world, next, player.getWidth(), player.getHeight());
            }
        }
    }

    // ------------------------------------------------------------------ 닌자 ninja

    @Test
    void ninjaClimbsWallsWhileCrouchingAndVaultsOverTheTop() {
        try (Scenario s = Scenario.ffa("ninja")) {
            s.place(s.enemy, 6.5, -4.5, 0);
            s.place(s.enemy2, -6.5, -4.5, 0);
            ledge(s, Material.STONE);
            s.place(s.owner, 0.5, 0.69, 0);
            s.ticks(3);
            assertEquals(FlatWorld.SURFACE, s.owner.getLocation().getY(), EPSILON, "standing: no climbing");
            s.owner.setSneaking(true);
            s.ticks(6);
            assertTrue(s.owner.getLocation().getY() > FlatWorld.SURFACE + 1, "climbing: " + s.owner.getLocation());
            s.ticks(30);
            assertTrue(s.owner.getLocation().getY() >= FlatWorld.SURFACE + 3 - EPSILON,
                    "vaulted onto the top of the wall: " + s.owner.getLocation());
            assertTrue(s.owner.getLocation().getZ() > 1, "standing on the ledge");
            s.owner.setSneaking(false);
            s.ticks(5);
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.place(s.owner, 0.5, 0.5, 0);
            ledge(s, Material.AIR);
            s.finishClean();
        }
    }

    @Test
    void ninjaTakesHalfTheFallDamage() {
        try (Scenario s = Scenario.ffa("ninja")) {
            assertEquals(1 - s.param("fall_reduction") / 100,
                    s.owner.getAttribute(Attribute.FALL_DAMAGE_MULTIPLIER).getValue(), EPSILON);
            s.place(s.owner, 0.5, FlatWorld.SURFACE + 10, 0.5, 0, 0);
            s.tickUntilGrounded(s.owner, 200);
            s.ticks(2);
            assertEquals(Math.ceil((10 - 3) * (1 - s.param("fall_reduction") / 100)), s.damageTaken(s.owner),
                    EPSILON, "half the fall damage of a 10 block drop");
            s.finishClean();
        }
    }

    @Test
    void ninjaShurikenHitsAndMakesTheTargetBleed() {
        try (Scenario s = Scenario.builder("ninja").param("shuriken_count", 1).start()) {
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "수리검");
            assertEquals(3, blockDisplays(s), "a steel star of two plates and a red core");
            s.ticks(5);
            assertEquals(s.param("shuriken_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "the star hit");
            assertEquals(0, blockDisplays(s), "the star is spent");
            s.ticks(60);
            int drops = (int) Math.round(s.param("bleed_duration") / s.param("bleed_interval"));
            assertEquals(1 + drops, s.hitsBy(s.owner, s.enemy).size(), "the hit and every bleed drop");
            assertEquals(s.param("shuriken_damage") + drops * s.param("bleed_damage"), s.damageBy(s.owner, s.enemy),
                    EPSILON);
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON);
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.finishClean();
        }
    }

    @Test
    void ninjaDodgedStarOpensNoWound() {
        try (Scenario s = Scenario.builder("ninja").param("shuriken_count", 1).enemyAbility("gambler")
                .configure(h -> h.setParam("gambler", "dodge_chance", 100)).start()) {
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "수리검");
            s.ticks(5);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "the star was dodged");
            assertTrue(s.match.replaceAbility(s.enemy, DummyAbility.INFO.id()), "the enemy's luck runs out");
            s.ticks(60);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "and no bleeding follows a dodged star: " + s.hits());
            s.finishClean();
        }
    }

    @Test
    void ninjaShurikenFanOverlappingOneEnemyDealsLessPerStar() {
        try (Scenario s = Scenario.ffa("ninja")) {
            s.place(s.enemy, 0.5, 2.0, 180);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "수리검");
            s.ticks(4);
            double damage = s.param("shuriken_damage");
            double overlap = s.param("shuriken_overlap") / 100;
            assertEquals(3, s.hitsBy(s.owner, s.enemy).size(), "all three stars hit at point-blank range");
            assertEquals(damage * (1 + 2 * overlap), s.damageBy(s.owner, s.enemy), EPSILON);
            s.finishClean();
        }
    }

    @Test
    void ninjaShurikenBitesIntoAWall() {
        try (Scenario s = Scenario.ffa("ninja")) {
            s.world.wallAlongX(-3, 3, 3, 3, Material.STONE);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "수리검");
            s.ticks(6);
            assertEquals(9, blockDisplays(s), "three stars stuck in the wall");
            s.ticks(30);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "the wall caught them");
            assertEquals(0, blockDisplays(s), "and they faded");
            s.world.wallAlongX(-3, 3, 3, 3, Material.AIR);
            s.finishClean();
        }
    }

    @Test
    void ninjaSmokeHidesTheNinjaInsideAndTheFirstStrikeIsCritical() {
        try (Scenario s = Scenario.teams("ninja")) {
            s.assertSucceeded(s.ultimate(), "연막 습격");
            assertFalse(s.ultimate().succeeded(), "already in a smoke");
            s.ticks(1);
            for (TestPlayer target : s.enemies()) {
                assertFalse(target.canSee(s.owner), target.getName() + " lost the ninja in the smoke");
            }
            assertTrue(s.ally().canSee(s.owner), "the ally still sees the ninja");
            PotionEffect speed = s.owner.getPotionEffect(PotionEffectType.SPEED);
            assertNotNull(speed);
            assertEquals(1, speed.getAmplifier(), "speed II in the smoke");
            assertTrue(s.owner.hasPotionEffect(PotionEffectType.INVISIBILITY));

            s.melee(s.owner, s.enemy, 4);
            List<Scenario.Hit> blows = s.hitsOf(s.enemy, DamageCause.ENTITY_ATTACK);
            assertEquals(4 * (1 + s.param("ult_crit") / 100), blows.getFirst().damage(), EPSILON, "a critical ambush");
            assertTrue(s.enemy.canSee(s.owner), "the strike gives the ninja away for a moment");
            s.ticks(s.paramTicks("ult_reveal") + 2);
            assertFalse(s.enemy.canSee(s.owner), "back into the smoke");
            s.melee(s.owner, s.enemy, 4);
            assertEquals(4, s.hitsOf(s.enemy, DamageCause.ENTITY_ATTACK).get(1).damage(), EPSILON,
                    "only the first strike is critical");
            s.ticks(30);
            assertTrue(s.hitsBy(s.owner, s.enemy).size() > 2, "the ambush makes the enemy bleed");

            s.place(s.owner, 0.5, 14.5, 0);
            s.ticks(2);
            assertTrue(s.enemy.canSee(s.owner), "out of the smoke the ninja is seen");
            assertFalse(s.owner.hasPotionEffect(PotionEffectType.SPEED), "and loses the smoke's speed");
            s.place(s.owner, 0.5, 0.5, 0);
            s.ticks(2);
            assertFalse(s.enemy.canSee(s.owner), "hidden again inside");

            s.ticks(s.paramTicks("smoke_duration"));
            assertTrue(s.enemy.canSee(s.owner), "the smoke cleared");
            assertFalse(s.owner.hasPotionEffect(PotionEffectType.INVISIBILITY));
            assertFalse(s.owner.hasPotionEffect(PotionEffectType.SPEED));
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.finishClean();
        }
    }

    @Test
    void ninjaSmokeRevealsTheNinjaWhenStruck() {
        try (Scenario s = Scenario.ffa("ninja")) {
            s.assertSucceeded(s.ultimate(), "연막 습격");
            s.ticks(1);
            assertFalse(s.enemy.canSee(s.owner));
            s.melee(s.enemy, s.owner, 1);
            assertTrue(s.enemy.canSee(s.owner), "struck: revealed");
            s.ticks(s.paramTicks("ult_reveal") + 2);
            assertFalse(s.enemy.canSee(s.owner), "hidden again once the smoke closes over");
            s.ticks(s.paramTicks("smoke_duration"));
            assertTrue(s.enemy.canSee(s.owner));
            s.finishClean();
        }
    }

    @Test
    void ninjaClimbNeverReplaysAStalePush() {
        try (Scenario s = Scenario.ffa("ninja")) {
            s.place(s.enemy, 6.5, -4.5, 0);
            s.place(s.enemy2, -6.5, -4.5, 0);
            ledge(s, Material.STONE);
            s.place(s.owner, 0.5, 0.69, 0);
            s.owner.setSneaking(true);
            // On a real server a player's velocity is only the last one sent to it (its client moves it). With the
            // physics off it stays put like that here: an old knockback, long over on the client.
            s.world.physics().setEnabled(false);
            s.owner.setVelocity(new Vector(1.5, 0, 0));
            s.ticks(1);
            Vector climb = s.owner.getVelocity();
            assertEquals(0, Math.hypot(climb.getX(), climb.getZ()), EPSILON, "straight up the wall: " + climb);
            assertEquals(s.param("climb_speed") / 20, climb.getY(), EPSILON);
            s.world.physics().setEnabled(true);
            s.ticks(40);
            s.owner.setSneaking(false);
            s.place(s.owner, 0.5, 0.5, 0);
            ledge(s, Material.AIR);
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.finishClean();
        }
    }

    @Test
    void ninjaAmbushCutStandsAcrossTheBlow() {
        try (Scenario s = Scenario.ffa("ninja")) {
            s.place(s.enemy, 2.5, 0.5, 90); // east of the ninja
            s.place(s.enemy2, -6.5, 6.5, 0);
            s.assertSucceeded(s.ultimate(), "연막 습격");
            s.ticks(1);
            s.melee(s.owner, s.enemy, 4);
            List<BlockDisplay> cuts = blockDisplayList(s);
            assertEquals(2, cuts.size(), "a red X");
            for (BlockDisplay cut : cuts) {
                assertUnturned(cut, "ambush cut");
                assertEquals(1, Math.abs(modelForward(cut).dot(new Vector(1, 0, 0))), 1.0E-4,
                        "the X stands across the blow");
            }
            s.ticks(s.paramTicks("smoke_duration") + 10);
            s.finishClean();
        }
    }

    // ------------------------------------------------------------------ 결투가 duelist

    @Test
    void duelistFightsBetterOneOnOne() {
        try (Scenario s = Scenario.ffa("duelist")) {
            s.melee(s.owner, s.enemy, 10);
            s.melee(s.enemy, s.owner, 10);
            assertEquals(10, s.damageBy(s.owner, s.enemy), EPSILON, "two enemies near: no duel");
            assertEquals(10, s.damageTaken(s.owner), EPSILON);

            s.clearHits();
            s.healAll();
            s.place(s.enemy2, 0.5, 30.5, 180);
            s.melee(s.owner, s.enemy, 10);
            s.melee(s.enemy, s.owner, 10);
            assertEquals(10 * (1 + s.param("duel_offense") / 100), s.damageBy(s.owner, s.enemy), EPSILON);
            assertEquals(10 * (1 - s.param("duel_defense") / 100), s.damageTaken(s.owner), EPSILON);
            s.finishClean();
        }
    }

    @Test
    void duelistChallengeMarksOneEnemyForAWhile() {
        try (Scenario s = Scenario.ffa("duelist")) {
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "결투 신청");
            assertEquals(1, textDisplays(s), "a golden ⚔ over the challenged enemy");
            s.ticks(2);

            s.melee(s.owner, s.enemy, 10);
            assertEquals(10 * (1 + s.param("challenge_bonus") / 100), s.damageBy(s.owner, s.enemy), EPSILON);
            s.melee(s.owner, s.enemy2, 10);
            assertEquals(10, s.damageBy(s.owner, s.enemy2), EPSILON, "no bonus on the other enemy");
            s.melee(s.enemy2, s.owner, 10);
            assertEquals(10 * (1 - s.param("challenge_guard") / 100), s.damageBy(s.enemy2, s.owner), EPSILON,
                    "others are kept at bay");
            s.melee(s.enemy, s.owner, 10);
            assertEquals(10, s.damageBy(s.enemy, s.owner), EPSILON, "the challenged enemy hits as hard as ever");

            s.ticks(s.paramTicks("challenge_duration") + 10);
            assertEquals(0, textDisplays(s), "the mark faded");
            s.clearHits();
            s.melee(s.owner, s.enemy, 10);
            assertEquals(10, s.damageBy(s.owner, s.enemy), EPSILON, "the challenge is over");
            s.finishClean();
        }
    }

    @Test
    void duelistChallengeNeedsATarget() {
        try (Scenario s = Scenario.ffa("duelist")) {
            s.place(s.owner, 0.5, FlatWorld.SURFACE, 0.5, 0, -80);
            assertFalse(s.active().succeeded(), "nobody in the sky");
            assertEquals(0, s.ctx().cooldownRemaining(s.owner), EPSILON);
            assertEquals(0, textDisplays(s));
            s.finishClean();
        }
    }

    @Test
    void duelistNeverPointsOutAStealthedEnemy() {
        try (Scenario s = Scenario.ffa("duelist")) {
            s.owner.hidePlayer(s.ctx().plugin(), s.enemy);
            s.aimAt(s.owner, s.enemy);
            assertFalse(s.active().succeeded(), "an enemy hidden by stealth cannot be challenged");
            assertEquals(0, textDisplays(s));
            s.owner.showPlayer(s.ctx().plugin(), s.enemy);

            s.assertSucceeded(s.active(), "결투 신청");
            s.ticks(2);
            TextDisplay emblem = s.world.getEntities().stream().filter(TextDisplay.class::isInstance)
                    .map(TextDisplay.class::cast).findFirst().orElseThrow();
            assertEquals("⚔", Scenario.plain(emblem.text()));
            s.owner.hidePlayer(s.ctx().plugin(), s.enemy); // the challenged enemy slips into stealth
            s.ticks(4);
            assertEquals("", Scenario.plain(emblem.text()), "the emblem no longer shows where it is");
            s.melee(s.owner, s.enemy, 10);
            assertEquals(10 * (1 + s.param("challenge_bonus") / 100), s.damageBy(s.owner, s.enemy), EPSILON,
                    "the challenge itself still holds");
            s.owner.showPlayer(s.ctx().plugin(), s.enemy);
            s.ticks(4);
            assertEquals("⚔", Scenario.plain(emblem.text()), "back in sight, back over its head");
            s.finishClean();
        }
    }

    @Test
    void duelistNeverDuelsASummon() {
        try (Scenario s = Scenario.ffa("duelist")) {
            Wolf wolf = s.world.spawn(new Location(s.world, 0.5, FlatWorld.SURFACE, 2.5), Wolf.class);
            s.ctx().summon(s.enemy, wolf);
            s.aimAt(s.owner, wolf);
            assertFalse(s.active().succeeded(), "a summoned wolf is no duellist to challenge");
            s.assertSucceeded(s.ultimate(), "결투장 with the wolf in the crosshair");
            s.ticks(2);
            s.melee(s.owner, s.enemy, 10);
            assertEquals(10 * (1 + s.param("duel_offense") / 100), s.damageBy(s.owner, s.enemy), EPSILON,
                    "the duel is with the wolf's master, the nearest real fighter");
            s.ticks(s.paramTicks("ult_duration") + 10);
            s.finishClean();
        }
    }

    @Test
    void duelistArenaPicksAnEnemyItCanSee() {
        try (Scenario s = Scenario.ffa("duelist")) {
            s.owner.hidePlayer(s.ctx().plugin(), s.enemy);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "결투장");
            s.ticks(2);
            s.melee(s.owner, s.enemy2, 10);
            assertEquals(10 * (1 + s.param("duel_offense") / 100), s.damageBy(s.owner, s.enemy2), EPSILON,
                    "the visible enemy is the one in the duel");
            s.owner.showPlayer(s.ctx().plugin(), s.enemy);
            s.ticks(s.paramTicks("ult_duration") + 10);
            s.finishClean();
        }
    }

    @Test
    void duelistArenaLocksTheDuelAndShutsEveryoneElseOut() {
        try (Scenario s = Scenario.teams("duelist")) {
            s.crossfireAlly();
            s.aimAt(s.owner, s.enemy);
            Location allyStart = s.ally().getLocation();
            s.assertSucceeded(s.ultimate(), "결투장");
            assertFalse(s.ultimate().succeeded(), "already in a duel");
            s.ticks(2);
            assertEquals(20, blockDisplays(s), "a ring of golden glass");
            assertEquals(1, textDisplays(s), "a countdown over the ring");

            // Nobody outside the duel can touch the duellists, nor be touched by them.
            s.melee(s.enemy2, s.owner, 5);
            s.melee(s.owner, s.enemy2, 5);
            s.melee(s.ally(), s.enemy, 5);
            s.melee(s.enemy, s.ally(), 5);
            s.ctx().damage(s.enemy2, s.owner, 3);
            assertEquals(0, s.damageTaken(s.owner) + s.damageTaken(s.enemy2) + s.damageTaken(s.enemy)
                    + s.damageTaken(s.ally()), EPSILON, "interference is blocked both ways: " + s.hits());

            // The duel itself goes on, always one on one.
            s.melee(s.owner, s.enemy, 10);
            s.melee(s.enemy, s.owner, 10);
            assertEquals(10 * (1 + s.param("duel_offense") / 100), s.damageBy(s.owner, s.enemy), EPSILON);
            assertEquals(10 * (1 - s.param("duel_defense") / 100), s.damageTaken(s.owner), EPSILON);

            // The opponent cannot leave, other enemies are thrown out, the ally is left where it stands.
            Location center = new Location(s.world, 0.5, FlatWorld.SURFACE, 2.5);
            s.place(s.enemy, 0.5, 20.5, 180);
            s.ticks(10);
            double radius = s.param("ult_radius");
            assertTrue(horizontalDistance(s.enemy.getLocation(), center) < radius,
                    "brought back into the ring: " + s.enemy.getLocation());
            assertTrue(horizontalDistance(s.enemy2.getLocation(), center) > radius,
                    "the other enemy was thrown out: " + s.enemy2.getLocation());
            assertTrue(s.ally().getLocation().distance(allyStart) < 0.5, "the ally is not moved");

            s.ticks(s.paramTicks("ult_duration"));
            assertEquals(0, blockDisplays(s) + textDisplays(s), "the ring sank away");
            s.layout();
            s.clearHits();
            s.melee(s.enemy2, s.owner, 5);
            assertEquals(5, s.damageTaken(s.owner), EPSILON, "after the duel everyone can fight again");
            s.finishClean();
        }
    }

    @Test
    void duelistWinningTheDuelHealsAndEndsIt() {
        try (Scenario s = Scenario.ffa("duelist")) {
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "결투장");
            s.ticks(2);
            s.owner.setHealth(10);
            Scenario.messages(s.enemy2);
            s.melee(s.owner, s.enemy, 500);
            assertFalse(s.session.isAlive(s.enemy), "the opponent fell");
            s.respawn(s.enemy);
            s.ticks(2);
            assertEquals(10 + s.param("ult_victory_heal"), s.owner.getHealth(), EPSILON, "the victor is healed");
            assertTrue(Scenario.messages(s.enemy2).stream().anyMatch(message -> message.contains("승리")),
                    "the win is announced");
            s.ticks(20);
            assertEquals(0, blockDisplays(s) + textDisplays(s), "the ring burst");
            s.finishClean();
        }
    }

    @Test
    void duelistWallPushNeverPilesUpOnAStaleVelocity() {
        try (Scenario s = Scenario.ffa("duelist")) {
            s.place(s.enemy2, 30.5, 30.5, 0);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "결투장");
            s.ticks(2);
            // The ring is centered between the duellists (0.5, 2.5). The opponent leans on the wall, and the server
            // keeps seeing it there with the velocity it last sent (physics off, like a real server that hears of
            // the push's effect only a round trip later).
            s.place(s.enemy, 0.5, 2.5 + s.param("ult_radius") - 0.4, 180);
            s.world.physics().setEnabled(false);
            double fastest = 0;
            for (int i = 0; i < 5; i++) {
                s.ticks(1);
                Vector velocity = s.enemy.getVelocity();
                fastest = Math.max(fastest, Math.hypot(velocity.getX(), velocity.getZ()));
                assertTrue(velocity.getY() < 0.2, "no rising push: " + velocity);
            }
            s.world.physics().setEnabled(true);
            assertTrue(fastest > 0.3, "pushed off the wall: " + fastest);
            assertTrue(fastest < 0.55 + EPSILON, "never harder than one push: " + fastest);
            s.ticks(s.paramTicks("ult_duration") + 10);
            s.finishClean();
        }
    }

    @Test
    void duelistBringsAnEscapedOpponentBackOntoTheFloor() {
        try (Scenario s = Scenario.ffa("duelist")) {
            s.place(s.enemy2, 30.5, 30.5, 0);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "결투장");
            s.ticks(2);
            s.place(s.enemy, 0.5, 20.5, 180);
            s.ticks(1);
            Location back = s.enemy.getLocation();
            assertTrue(horizontalDistance(back, new Location(s.world, 0.5, FlatWorld.SURFACE, 2.5))
                    < s.param("ult_radius"), "brought back into the ring: " + back);
            assertEquals(FlatWorld.SURFACE, back.getY(), EPSILON, "standing exactly on the floor");
            assertTrue(Spots.isFree(s.world, back, 0.6, 1.8), "never inside a block");
            s.ticks(s.paramTicks("ult_duration") + 10);
            s.finishClean();
        }
    }

    @Test
    void duelistArenaNeedsAnEnemyInRange() {
        try (Scenario s = Scenario.ffa("duelist")) {
            s.place(s.enemy, 0.5, 30.5, 180);
            s.place(s.enemy2, 30.5, 0.5, 90);
            assertFalse(s.ultimate().succeeded(), "nobody to duel");
            assertEquals(100, s.participant(s.owner).gauge(), EPSILON, "the gauge is kept");
            assertEquals(0, blockDisplays(s));
            s.finishClean();
        }
    }

    // ------------------------------------------------------------------ helpers

    /** A ledge 3 blocks high and 4 deep, starting at z = 1 right in front of the owner. */
    private static void ledge(Scenario s, Material type) {
        for (int z = 1; z <= 4; z++) {
            s.world.wallAlongX(-2, 3, z, 3, type);
        }
    }

    /** Hollows out (AIR) or refills (STONE, bedrock at the bottom) a strip of the world from z1 to z2. */
    private static void voidStrip(Scenario s, int z1, int z2, Material type) {
        for (int x = -4; x <= 4; x++) {
            for (int z = z1; z <= z2; z++) {
                for (int y = s.world.getMinHeight(); y <= FlatWorld.FLOOR_Y; y++) {
                    Material block = type == Material.AIR ? Material.AIR
                            : y == s.world.getMinHeight() ? Material.BEDROCK : type;
                    s.world.setBlock(x, y, z, block);
                }
            }
        }
    }
}
