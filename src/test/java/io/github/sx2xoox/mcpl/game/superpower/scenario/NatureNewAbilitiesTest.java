package io.github.sx2xoox.mcpl.game.superpower.scenario;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.List;
import java.util.stream.Stream;

import io.github.sx2xoox.mcpl.game.superpower.ability.Ability;
import io.github.sx2xoox.mcpl.game.superpower.ability.ActivationResult;
import io.github.sx2xoox.mcpl.game.superpower.support.Scenario;
import io.github.sx2xoox.mcpl.game.superpower.support.TestPlayer;
import io.github.sx2xoox.mcpl.game.superpower.support.world.FlatWorld;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.potion.PotionEffectType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** 자연 계열 신규 능력: 수류술사, 용암술사, 식물술사, 모래술사. */
class NatureNewAbilitiesTest {

    private static final double EPSILON = 1.0E-6;

    static Stream<String> abilities() {
        return Stream.of("hydro", "magma", "druid", "sand");
    }

    // ------------------------------------------------------------------ helpers

    private static long displays(Scenario s) {
        return s.world.getEntities().stream().filter(entity -> entity instanceof Display).count();
    }

    private static <T extends Display> java.util.List<T> displaysOf(Scenario s, Class<T> type) {
        return s.world.getEntities().stream().filter(type::isInstance).map(type::cast).toList();
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
    private static org.bukkit.util.Vector modelForward(Display display) {
        org.bukkit.util.Transformation pose = display.getTransformation();
        org.joml.Quaternionf rotation = pose.getLeftRotation();
        if (Math.abs(pose.getRightRotation().w()) < 1 - 1.0E-6) {
            rotation.conjugate();
        }
        org.joml.Vector3f z = rotation.transform(new org.joml.Vector3f(0, 0, 1));
        return new org.bukkit.util.Vector(z.x(), z.y(), z.z());
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

    /** Owner and target hits by the owner's ability (MAGIC), ignoring burns and other uncredited damage. */
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
        // No lucky dodge / double damage: the borrowed ultimate alone decides what happens.
        return Scenario.builder("gambler").param("dodge_chance", 0).param("crit_chance", 0);
    }

    /** Puts the enemies where the active of {@code id} reaches them, and aims. */
    private static void prepareActive(Scenario s, String id) {
        s.layout();
        if (s.ally != null) {
            s.crossfireAlly();
        }
        s.aimAt(s.owner, s.enemy);
    }

    /** Puts the enemies where the ultimate of {@code id} reaches them, and aims. */
    private static void prepareUltimate(Scenario s, String id) {
        s.layout();
        if (s.ally != null) {
            s.crossfireAlly();
        }
        if (id.equals("magma")) {
            // Inside the eruption.
            s.place(s.enemy, 0.5, 2.5, 180);
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

    @ParameterizedTest(name = "{0}")
    @MethodSource("abilities")
    void wholeMatchHurtsEnemiesButNeverTheOwner(String id) {
        try (Scenario s = Scenario.ffa(id)) {
            s.ticks(40);
            prepareActive(s, id);
            s.clearHits();
            s.assertSucceeded(s.active(), id + " active");
            s.ticks(120);
            assertTrue(s.damageBy(s.owner, s.enemy) > 0, id + " active hurts the aimed enemy");
            assertEquals(0, s.damageTaken(s.owner), EPSILON, id + " active never hurts its owner: " + s.hits());
            assertVisualsGone(s, "after the " + id + " active");

            prepareUltimate(s, id);
            s.healAll();
            s.clearHits();
            s.assertSucceeded(s.ultimate(), id + " ultimate");
            s.ticks(10);
            assertTrue(displays(s) > 0, id + " ultimate shows display entities");
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
            AbilityLifecycleTest.watchAlly(s, allyStart, 120);

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
            s.ticks(id.equals("druid") ? 7 : 4);
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
            s.ticks(15);
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

    // ------------------------------------------------------------------ 수류술사 hydro

    @Test
    void hydroCannonKeepsHittingAndShovingTheFirstEnemy() {
        try (Scenario s = Scenario.ffa("hydro")) {
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "물대포");
            s.ticks(2);
            assertEquals(2, displays(s), "a water tube with a pressure core");
            s.ticks(s.paramTicks("beam_duration") + 2);
            long pulses = magicHits(s, s.enemy);
            assertTrue(pulses >= 4, "the jet keeps hitting the enemy it shoves: " + pulses);
            assertEquals(pulses * s.param("beam_damage"), s.damageBy(s.owner, s.enemy), EPSILON);
            assertTrue(s.enemy.getLocation().getZ() > 4.5 + 3, "shoved far back: " + s.enemy.getLocation());
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON, "the jet stops at the first enemy");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.ticks(20);
            assertVisualsGone(s, "after the jet");
            s.finishClean();
        }
    }

    @Test
    void hydroCannonStopsAtWalls() {
        try (Scenario s = Scenario.ffa("hydro")) {
            s.world.wallAlongX(-2, 3, 2, 3, Material.STONE);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "물대포");
            s.ticks(40);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "the wall shields the enemy");
            s.world.wallAlongX(-2, 3, 2, 3, Material.AIR);
            s.finishClean();
        }
    }

    @Test
    void hydroWaterSkillsSoakTheOwnerWhichSpeedsAndHealsIt() {
        try (Scenario s = Scenario.ffa("hydro")) {
            double base = speed(s.owner);
            s.owner.setHealth(10);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "물대포");
            s.ticks(6);
            assertEquals(base * (1 + s.param("wet_speed") / 100), speed(s.owner), 1.0E-9, "soaked: faster");
            s.ticks(s.paramTicks("wet_interval") + 5);
            assertTrue(s.owner.getHealth() >= 10 + s.param("wet_heal"), "soaked: healing " + s.owner.getHealth());
            s.ticks(s.paramTicks("soak_duration") + 10);
            assertEquals(base, speed(s.owner), 1.0E-9, "dry again");
            double dry = s.owner.getHealth();
            s.ticks(s.paramTicks("wet_interval") * 2);
            assertEquals(dry, s.owner.getHealth(), EPSILON, "no healing while dry");

            s.owner.setInWater(true);
            s.ticks(s.paramTicks("wet_interval") + 10);
            assertTrue(s.owner.getHealth() > dry, "water heals");
            assertTrue(speed(s.owner) > base, "and speeds up");
            s.owner.setInWater(false);
            s.ticks(6);
            assertEquals(base, speed(s.owner), 1.0E-9);
            s.finishClean();
        }
    }

    @Test
    void hydroFireNeverSticks() {
        try (Scenario s = Scenario.ffa("hydro")) {
            s.owner.setFireTicks(200);
            s.ticks(60);
            assertEquals(0, s.owner.getFireTicks(), "the fire went out");
            assertTrue(s.hitsOf(s.owner, DamageCause.FIRE_TICK).isEmpty(), "no burn damage");
            s.owner.setNoDamageTicks(0);
            s.owner.damage(2, DamageSource.builder(DamageType.ON_FIRE).build());
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "burning is ignored");
            s.owner.setNoDamageTicks(0);
            s.owner.damage(2, DamageSource.builder(DamageType.LAVA).build());
            assertEquals(2, s.damageTaken(s.owner), EPSILON, "but lava itself still hurts");
            s.finishClean();
        }
    }

    @Test
    void hydroTidalWaveSweepsEnemiesAlongAndCrashesOnThem() {
        try (Scenario s = Scenario.ffa("hydro")) {
            s.assertSucceeded(s.ultimate(), "해일");
            s.ticks(3);
            assertTrue(displays(s) >= 9, "a wall of water columns: " + displays(s));
            assertTrue(displays(s) <= 24, "within the display budget");
            s.ticks(12);
            for (TestPlayer target : s.enemies()) {
                assertEquals(s.param("wave_damage"), s.damageBy(s.owner, target), EPSILON,
                        target.getName() + " is swept up");
            }
            s.ticks(60);
            for (TestPlayer target : s.enemies()) {
                assertEquals(s.param("wave_damage") + s.param("crash_damage"), s.damageBy(s.owner, target), EPSILON,
                        target.getName() + " is smashed when the wave breaks");
                assertTrue(target.getLocation().getZ() > s.param("wave_distance") - 3,
                        target.getName() + " was carried along: " + target.getLocation());
                assertTrue(s.hitsOf(target, DamageCause.FALL).isEmpty(), "no fall damage from the ride");
            }
            assertEquals(FlatWorld.SURFACE, s.owner.getLocation().getY(), 1.0E-3, "the owner stays put");
            assertEquals(0.5, s.owner.getLocation().getZ(), 1.0E-3);
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.ticks(30);
            assertVisualsGone(s, "after the wave");
            s.finishClean();
        }
    }

    @Test
    void hydroWaveBreaksOnAWallAndSmashesItsRidersThere() {
        try (Scenario s = Scenario.ffa("hydro")) {
            s.world.wallAlongX(-8, 8, 9, 3, Material.STONE);
            s.assertSucceeded(s.ultimate(), "해일");
            s.ticks(60);
            for (TestPlayer target : s.enemies()) {
                assertEquals(s.param("wave_damage") + s.param("crash_damage"), s.damageBy(s.owner, target), EPSILON,
                        target.getName());
                assertTrue(target.getLocation().getZ() < 9, target.getName() + " stays in front of the wall");
            }
            s.ticks(30);
            assertVisualsGone(s, "after the wave broke");
            s.world.wallAlongX(-8, 8, 9, 3, Material.AIR);
            s.finishClean();
        }
    }

    @Test
    void hydroWaveNeverWashesEnemiesOutOfTheArena() {
        try (Scenario s = Scenario.builder("hydro").configure(h -> {
            h.settings().set("border.start-delay-seconds", 0);
            h.settings().set("border.shrink-seconds", 0);
            h.settings().set("border.final-diameter", 30);
        }).start()) {
            s.ticks(41);
            s.layout();
            s.healAll();
            s.clearHits();
            assertTrue(s.ctx().isInsideArena(s.enemy.getLocation()), "the enemies start inside");
            assertFalse(s.ctx().isInsideArena(new Location(s.world, 0.5, FlatWorld.SURFACE,
                    0.5 + s.param("wave_distance"))), "a full-length wave would end outside the border");
            s.assertSucceeded(s.ultimate(), "해일");
            s.ticks(80);
            for (TestPlayer target : s.enemies()) {
                assertEquals(s.param("wave_damage") + s.param("crash_damage"), s.damageBy(s.owner, target), EPSILON,
                        target.getName() + " is swept and smashed");
                assertTrue(s.ctx().isInsideArena(target.getLocation()),
                        target.getName() + " is left inside the shrunken arena: " + target.getLocation());
            }
            s.finishClean();
        }
    }

    @Test
    void hydroWaveFlankBreaksAtTheBorderEvenWhenItsMiddleHasRoom() {
        try (Scenario s = Scenario.builder("hydro").configure(h -> {
            h.settings().set("border.start-delay-seconds", 0);
            h.settings().set("border.shrink-seconds", 0);
            h.settings().set("border.final-diameter", 30);
        }).start()) {
            s.ticks(41);
            Location center = s.session.arena().center();
            double cx = center.getX();
            double cz = center.getZ();
            // A diagonal wave near the border's +x edge: its right flank runs into the border long before its middle.
            s.place(s.owner, cx + 8, cz, -45);
            s.place(s.enemy, cx + 12.5, cz - 0.5, 135);
            s.place(s.enemy2, cx - 10, cz - 10, 0);
            s.healAll();
            s.clearHits();
            assertTrue(s.ctx().isInsideArena(s.enemy.getLocation()), "the enemy starts inside");
            s.assertSucceeded(s.ultimate(), "해일");
            s.ticks(80);
            assertEquals(s.param("wave_damage") + s.param("crash_damage"), s.damageBy(s.owner, s.enemy), EPSILON,
                    "swept by the flank and smashed where it broke");
            assertTrue(s.ctx().isInsideArena(s.enemy.getLocation()),
                    "the flank never carries its rider out of the arena: " + s.enemy.getLocation());
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON);
            s.finishClean();
        }
    }

    // ------------------------------------------------------------------ 용암술사 magma

    @Test
    void magmaBombBurstsOnTheAimedEnemyAndLeavesABoilingPool() {
        try (Scenario s = Scenario.ffa("magma")) {
            s.place(s.enemy2, 6.5, -6.5, 0);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "용암탄");
            s.ticks(20);
            List<Scenario.Hit> hits = s.hitsBy(s.owner, s.enemy);
            assertFalse(hits.isEmpty(), "the bomb lands on the enemy");
            assertEquals(s.param("bomb_damage"), hits.getFirst().damage(), EPSILON, "the bomb bursts on the enemy");
            assertTrue(displays(s) >= 5, "a pool of crust, magma and a glowing heart");
            s.clearHits();
            s.ticks(40);
            long burns = magicHits(s, s.enemy);
            assertTrue(burns >= 3, "standing in the pool keeps burning: " + burns);
            assertEquals(burns * s.param("pool_damage"), s.damageBy(s.owner, s.enemy), EPSILON);
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.ticks(s.paramTicks("pool_duration"));
            s.clearHits();
            s.ticks(30);
            assertEquals(0, magicHits(s, s.enemy), "the pool cooled down");
            assertVisualsGone(s, "after the pool");
            s.finishClean();
        }
    }

    @Test
    void magmaBombQuenchesInWaterWithoutAPool() {
        try (Scenario s = Scenario.ffa("magma")) {
            s.place(s.enemy, 6.5, 4.5, 180);
            s.place(s.enemy2, 6.5, -6.5, 0);
            for (int x = -1; x <= 2; x++) {
                for (int z = 3; z <= 6; z++) {
                    s.world.setBlock(x, FlatWorld.FLOOR_Y + 1, z, Material.WATER);
                }
            }
            s.aimAtFloor(s.owner, 0.5, 4.5);
            s.assertSucceeded(s.active(), "용암탄");
            s.ticks(25);
            assertEquals(0, displays(s), "the bomb hardened in the water: no pool");
            assertEquals(0, s.damageTaken(s.enemy) + s.damageTaken(s.enemy2), EPSILON);
            for (int x = -1; x <= 2; x++) {
                for (int z = 3; z <= 6; z++) {
                    s.world.setBlock(x, FlatWorld.FLOOR_Y + 1, z, Material.AIR);
                }
            }
            s.finishClean();
        }
    }

    @Test
    void magmaPoolOnlyBurnsEnemiesStandingInIt() {
        try (Scenario s = Scenario.ffa("magma")) {
            s.place(s.enemy, 4.5, 4.5, 180);
            s.place(s.enemy2, 0.5, 9.0, 180);
            s.aimAtFloor(s.owner, 0.5, 8.5);
            s.assertSucceeded(s.active(), "용암탄");
            s.ticks(25);
            s.place(s.owner, 0.5, 8.0, 0);
            s.clearHits();
            s.ticks(40);
            assertTrue(magicHits(s, s.enemy2) >= 3, "the enemy in the pool burns");
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "the enemy beside it does not");
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "the owner can stand in its own pool");
            s.ticks(120);
            s.finishClean();
        }
    }

    @Test
    void magmaSkinResistsHeatAndBurnsMeleeAttackers() {
        try (Scenario s = Scenario.ffa("magma")) {
            s.owner.damage(4, DamageSource.builder(DamageType.LAVA).build());
            assertEquals(4 * (1 - s.param("heat_resist") / 100), s.damageTaken(s.owner), EPSILON, "lava hurts less");
            s.melee(s.enemy, s.owner, 2);
            assertTrue(s.enemy.getFireTicks() >= s.paramTicks("retaliate_burn") - 1, "the attacker burns");
            assertEquals(0, s.enemy2.getFireTicks(), "nobody else does");
            s.finishClean();
        }
    }

    @Test
    void magmaVolcanoEruptsThenRainsTelegraphedChunks() {
        try (Scenario s = Scenario.builder("magma").param("rain_aimed", 100).param("rain_interval", 0.2).start()) {
            s.place(s.enemy, 0.5, 2.5, 180);
            s.assertSucceeded(s.ultimate(), "화산");
            s.ticks(s.paramTicks("erupt_delay") - 1);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "the ground only rumbles at first");
            s.ticks(3);
            assertEquals(s.param("erupt_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "the eruption hits");
            assertEquals(0, s.damageBy(s.owner, s.enemy2), EPSILON, "enemy2 is outside the eruption");
            s.ticks(2);
            assertTrue(s.enemy.getLocation().getY() > FlatWorld.SURFACE + 0.2, "and launches");
            s.ticks(s.paramTicks("rain_duration") + s.paramTicks("rain_warning") + 10);
            for (TestPlayer target : s.enemies()) {
                assertTrue(s.damageBy(s.owner, target) >= s.param("chunk_damage"),
                        target.getName() + " is hit by lava chunks: " + s.hitsBy(s.owner, target));
            }
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "the volcano spares its owner");
            s.ticks(40);
            assertVisualsGone(s, "after the volcano");
            s.finishClean();
        }
    }

    // ------------------------------------------------------------------ 식물술사 druid

    @Test
    void druidSnareBindsAndPoisonsTheAimedEnemyAndItsNeighbours() {
        try (Scenario s = Scenario.ffa("druid")) {
            s.place(s.enemy2, 1.5, 5.5, 180);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "덩굴 속박");
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "the roots need a moment to break through");
            s.ticks(s.paramTicks("snare_delay") + 2);
            for (TestPlayer target : s.enemies()) {
                assertEquals(s.param("snare_damage"), s.damageBy(s.owner, target), EPSILON, target.getName());
                assertEquals(0, speed(target), 1.0E-9, target.getName() + " cannot move");
                assertEquals(0, target.getAttribute(Attribute.JUMP_STRENGTH).getValue(), 1.0E-9, "nor jump");
                assertTrue(target.hasPotionEffect(PotionEffectType.POISON), target.getName() + " is poisoned");
            }
            assertTrue(displays(s) > 0, "roots curl around the legs");
            s.ticks(s.paramTicks("root_duration") + 2);
            for (TestPlayer target : s.enemies()) {
                assertModifierFree(target, "after the snare");
            }
            s.ticks(10);
            assertVisualsGone(s, "after the roots let go");
            s.finishClean();
        }
    }

    @Test
    void druidSnareCanBeDodgedDuringItsTelegraph() {
        try (Scenario s = Scenario.ffa("druid")) {
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "덩굴 속박");
            s.place(s.enemy, 5.5, 4.5, 180);
            s.ticks(s.paramTicks("snare_delay") + 5);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "the enemy stepped out of the roots' reach");
            assertModifierFree(s.enemy, "dodged");
            s.finishClean();
        }
    }

    @Test
    void druidSnareNeedsAnAimedEnemy() {
        try (Scenario s = Scenario.ffa("druid")) {
            s.place(s.owner, 0.5, FlatWorld.SURFACE, 0.5, 180, 0);
            assertFalse(s.active().succeeded(), "nobody aimed at");
            assertEquals(0, s.ctx().cooldownRemaining(s.owner), EPSILON, "a failed skill costs no cooldown");
            s.finishClean();
        }
    }

    @Test
    void druidPhotosynthesisHealsOnGrassOnly() {
        try (Scenario s = Scenario.ffa("druid")) {
            s.owner.setHealth(10);
            s.ticks(s.paramTicks("regen_interval") * 2 + 10);
            assertEquals(10, s.owner.getHealth(), EPSILON, "bare stone does not feed the druid");
            s.world.setBlock(0, FlatWorld.FLOOR_Y, 0, Material.GRASS_BLOCK);
            s.ticks(s.paramTicks("regen_interval") * 2 + 10);
            assertTrue(s.owner.getHealth() >= 10 + s.param("regen_amount"), "grass heals: " + s.owner.getHealth());
            s.world.setBlock(0, FlatWorld.FLOOR_Y, 0, Material.STONE);
            s.finishClean();
        }
    }

    @Test
    void druidPhotosynthesisKeepsWorkingThroughJumps() {
        try (Scenario s = Scenario.ffa("druid")) {
            s.world.setBlock(0, FlatWorld.FLOOR_Y, 0, Material.GRASS_BLOCK);
            // Held at the top of a jump: one block over the grass.
            s.world.physics().setEnabled(false);
            s.place(s.owner, 0.5, FlatWorld.SURFACE + 1, 0.5, 0, 0);
            s.owner.setHealth(10);
            s.ticks(s.paramTicks("regen_interval") * 2 + 10);
            assertTrue(s.owner.getHealth() >= 10 + s.param("regen_amount"),
                    "hopping over grass still heals: " + s.owner.getHealth());

            s.world.setBlock(0, FlatWorld.FLOOR_Y, 0, Material.STONE);
            double health = s.owner.getHealth();
            s.ticks(s.paramTicks("regen_interval") * 2 + 10);
            assertEquals(health, s.owner.getHealth(), EPSILON, "hopping over stone does not");
            s.world.physics().setEnabled(true);
            s.tickUntilGrounded(s.owner, 100);
            s.finishClean();
        }
    }

    @Test
    void druidRootsBreakOutOfTheGroundUnderAJumpingEnemy() {
        try (Scenario s = Scenario.ffa("druid")) {
            s.world.physics().setEnabled(false);
            s.place(s.enemy, 0.5, FlatWorld.SURFACE + 1.1, 4.5, 180, 0);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "덩굴 속박");
            s.ticks(s.paramTicks("snare_delay") + 2);
            assertEquals(s.param("snare_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "caught mid-jump");
            assertTrue(s.world.getEntities().stream().filter(entity -> entity instanceof Display)
                            .allMatch(entity -> entity.getLocation().getY() < FlatWorld.SURFACE + 0.1),
                    "the roots grow from the ground, not from the air");
            s.world.physics().setEnabled(true);
            s.ticks(s.paramTicks("root_duration") + 20);
            assertVisualsGone(s, "after the roots let go");
            s.finishClean();
        }
    }

    @Test
    void druidGroveHealsTheTeamWhileItsThornsSlowAndPrickEnemies() {
        try (Scenario s = Scenario.teams("druid")) {
            s.place(s.ally(), 2.5, 0.5, 0);
            s.ally().setHealth(10);
            s.owner.setHealth(10);
            double enemySpeed = speed(s.enemy);
            s.assertSucceeded(s.ultimate(), "생명의 숲");
            s.ticks(s.paramTicks("ult_interval") * 2 + 2);
            assertTrue(s.ally().getHealth() >= 10 + 2 * s.param("ult_heal") - EPSILON, "the ally heals");
            assertTrue(s.owner.getHealth() >= 10 + 2 * s.param("ult_heal") - EPSILON, "and the druid");
            for (TestPlayer target : s.enemies()) {
                assertEquals(enemySpeed * (1 - s.param("ult_slow") / 100), speed(target), 1.0E-9,
                        target.getName() + " is slowed");
                assertEquals(2 * s.param("ult_thorns"), s.damageBy(s.owner, target), EPSILON,
                        target.getName() + " is pricked");
            }
            assertEquals(0, s.damageTaken(s.ally()), EPSILON);
            assertTrue(Scenario.hasNoHarmfulEffect(s.ally()));
            // Leaving the grove ends the slow at once.
            s.place(s.enemy, 0.5, 14.5, 180);
            s.ticks(3);
            assertEquals(enemySpeed, speed(s.enemy), 1.0E-9, "out of the grove, out of the thorns");
            s.ticks(s.paramTicks("ult_duration"));
            assertModifierFree(s.enemy2, "after the grove");
            s.ticks(20);
            assertVisualsGone(s, "after the grove withered");
            s.finishClean();
        }
    }

    // ------------------------------------------------------------------ 모래술사 sand

    @Test
    void sandStormBlindsHurtsAndShovesEnemiesInTheCone() {
        try (Scenario s = Scenario.ffa("sand")) {
            s.place(s.enemy2, 0.5, -3.5, 0);
            s.assertSucceeded(s.active(), "모래 폭풍");
            s.ticks(10);
            assertEquals(s.param("storm_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "hit once");
            assertTrue(s.enemy.hasPotionEffect(PotionEffectType.BLINDNESS), "blinded");
            assertTrue(s.enemy.getLocation().getZ() > 4.5 + 0.5, "shoved back: " + s.enemy.getLocation());
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON, "the enemy behind is spared");
            assertFalse(s.enemy2.hasPotionEffect(PotionEffectType.BLINDNESS));
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.ticks(20);
            assertVisualsGone(s, "after the storm");
            s.finishClean();
        }
    }

    @Test
    void sandStormIsStoppedByWalls() {
        try (Scenario s = Scenario.ffa("sand")) {
            s.world.wallAlongX(-3, 4, 3, 3, Material.STONE);
            s.assertSucceeded(s.active(), "모래 폭풍");
            s.ticks(10);
            assertEquals(0, s.damageTaken(s.enemy) + s.damageTaken(s.enemy2), EPSILON, "sheltered by the wall");
            s.world.wallAlongX(-3, 4, 3, 3, Material.AIR);
            s.finishClean();
        }
    }

    @Test
    void sandBodyDodgesAttacksButNotAbilities() {
        try (Scenario s = Scenario.builder("sand").param("dodge_chance", 100).start()) {
            s.melee(s.enemy, s.owner, 4);
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "the blow passes through sand");
            s.ticks(12);
            assertVisualsGone(s, "after the sand silhouette crumbled");
            s.ctx().damage(s.enemy, s.owner, 3);
            assertEquals(3, s.damageTaken(s.owner), EPSILON, "ability damage is never dodged");
            s.finishClean();
        }
        try (Scenario s = Scenario.builder("sand").param("dodge_chance", 0).start()) {
            s.melee(s.enemy, s.owner, 4);
            assertEquals(4, s.damageTaken(s.owner), EPSILON, "no dodge at 0%");
            s.finishClean();
        }
    }

    @Test
    void sandStormStreaksFlyPointFirstAlongTheCone() {
        try (Scenario s = Scenario.ffa("sand")) {
            // Facing east: a streak left in the display's own default facing (south) would be caught.
            s.place(s.owner, 0.5, 0.5, -90);
            s.place(s.enemy, 4.5, 0.5, 90);
            s.place(s.enemy2, -6.5, 6.5, 0);
            org.bukkit.util.Vector east = new org.bukkit.util.Vector(1, 0, 0);
            double spread = Math.toRadians(s.param("storm_angle") / 2 + 12);
            s.assertSucceeded(s.active(), "모래 폭풍");
            for (int tick = 0; tick < 3; tick++) {
                java.util.List<org.bukkit.entity.BlockDisplay> streaks =
                        displaysOf(s, org.bukkit.entity.BlockDisplay.class);
                assertEquals(5, streaks.size(), "sand streaks race out");
                for (org.bukkit.entity.BlockDisplay streak : streaks) {
                    assertUnturned(streak, "streak");
                    assertTrue(modelForward(streak).dot(east) > Math.cos(spread),
                            "a streak flies point first out along the cone: " + modelForward(streak));
                }
                s.ticks(1);
            }
            s.ticks(30);
            assertVisualsGone(s, "after the storm");
            s.finishClean();
        }
    }

    @Test
    void sandCrumbleSilhouetteFacesWhereTheOwnerFaces() {
        try (Scenario s = Scenario.builder("sand").param("dodge_chance", 100).start()) {
            s.place(s.owner, 0.5, 0.5, -90); // facing east
            s.place(s.enemy, 1.5, 0.5, 90);
            s.melee(s.enemy, s.owner, 4);
            java.util.List<org.bukkit.entity.BlockDisplay> ghosts = displaysOf(s, org.bukkit.entity.BlockDisplay.class);
            assertEquals(1, ghosts.size(), "a sand silhouette");
            org.bukkit.entity.BlockDisplay ghost = ghosts.getFirst();
            assertUnturned(ghost, "silhouette");
            // A thin slab, its depth along the owner's facing.
            assertEquals(1, modelForward(ghost).dot(new org.bukkit.util.Vector(1, 0, 0)), 1.0E-4,
                    "the silhouette faces where the owner faces");
            assertEquals(FlatWorld.SURFACE, ghost.getLocation().getY(), EPSILON, "standing where the owner stands");
            s.ticks(12);
            assertVisualsGone(s, "after the sand silhouette crumbled");
            s.finishClean();
        }
    }

    @Test
    void sandRunsFasterOnSand() {
        try (Scenario s = Scenario.ffa("sand")) {
            double base = baseSpeed(s.owner);
            s.ticks(6);
            assertEquals(base, speed(s.owner), 1.0E-9, "stone is not sand");
            s.world.setBlock(0, FlatWorld.FLOOR_Y, 0, Material.SAND);
            s.ticks(6);
            assertEquals(base * (1 + s.param("sand_speed") / 100), speed(s.owner), 1.0E-9, "fast on sand");
            s.world.setBlock(0, FlatWorld.FLOOR_Y, 0, Material.STONE);
            s.ticks(6);
            assertEquals(base, speed(s.owner), 1.0E-9);
            s.finishClean();
        }
    }

    @Test
    void sandSpeedHoldsThroughJumpsOverSand() {
        try (Scenario s = Scenario.ffa("sand")) {
            double base = baseSpeed(s.owner);
            double fast = base * (1 + s.param("sand_speed") / 100);
            s.world.setBlock(0, FlatWorld.FLOOR_Y, 0, Material.SAND);
            s.ticks(6);
            assertEquals(fast, speed(s.owner), 1.0E-9, "fast on sand");
            // Every hop must not drop the bonus (a toggling speed modifier pulses the player's field of view).
            s.world.physics().setEnabled(false);
            s.place(s.owner, 0.5, FlatWorld.SURFACE + 1.2, 0.5, 0, 0);
            s.ticks(11);
            assertEquals(fast, speed(s.owner), 1.0E-9, "still fast at the top of a jump");
            s.world.physics().setEnabled(true);
            s.tickUntilGrounded(s.owner, 100);
            s.world.setBlock(0, FlatWorld.FLOOR_Y, 0, Material.STONE);
            s.ticks(6);
            assertEquals(base, speed(s.owner), 1.0E-9, "off the sand");
            s.finishClean();
        }
    }

    @Test
    void sandQuicksandSwallowsDragsAndGrindsEnemies() {
        try (Scenario s = Scenario.ffa("sand")) {
            double enemySpeed = speed(s.enemy);
            double ownerBase = baseSpeed(s.owner);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "유사");
            s.ticks(2);
            for (TestPlayer target : s.enemies()) {
                assertEquals(enemySpeed * (1 - s.param("ult_slow") / 100), speed(target), 1.0E-9,
                        target.getName() + " sinks");
                assertEquals(0, target.getAttribute(Attribute.JUMP_STRENGTH).getValue(), 1.0E-9, "cannot jump");
            }
            assertNotNull(s.enemy2);
            double before = Math.hypot(s.enemy2.getLocation().getX() - 0.5, s.enemy2.getLocation().getZ() - 4.5);
            s.ticks(20);
            double after = Math.hypot(s.enemy2.getLocation().getX() - 0.5, s.enemy2.getLocation().getZ() - 4.5);
            assertTrue(after < before - 0.5, "dragged towards the middle: " + before + " -> " + after);
            assertTrue(speed(s.owner) > ownerBase, "the owner runs fast over its own quicksand");
            s.ticks(s.paramTicks("ult_duration"));
            double grinds = s.param("ult_duration") / s.param("ult_interval");
            for (TestPlayer target : s.enemies()) {
                assertEquals(grinds * s.param("ult_damage"), s.damageBy(s.owner, target), EPSILON,
                        target.getName() + " ground down the whole time");
                assertModifierFree(target, "after the quicksand");
            }
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.ticks(20);
            assertEquals(ownerBase, speed(s.owner), 1.0E-9, "the pit is gone");
            assertVisualsGone(s, "after the quicksand");
            s.finishClean();
        }
    }

    @Test
    void sandQuicksandLetsGoOfEnemiesThatGetOut() {
        try (Scenario s = Scenario.ffa("sand")) {
            double enemySpeed = speed(s.enemy);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "유사");
            s.ticks(3);
            assertTrue(speed(s.enemy) < enemySpeed, "caught");
            s.place(s.enemy, 0.5, 16.5, 180);
            s.ticks(2);
            assertEquals(enemySpeed, speed(s.enemy), 1.0E-9, "free again once out of the pit");
            assertModifierFree(s.enemy, "escaped");
            s.ticks(s.paramTicks("ult_duration") + 20);
            s.finishClean();
        }
    }

    @Test
    void sandQuicksandCannotOpenOutsideTheArena() {
        try (Scenario s = Scenario.builder("sand").configure(h -> {
            h.settings().set("border.start-delay-seconds", 0);
            h.settings().set("border.shrink-seconds", 0);
            h.settings().set("border.final-diameter", 12);
        }).start()) {
            s.ticks(41);
            s.place(s.owner, 0.5, 0.5, 0);
            s.place(s.enemy, 0.5, 14.5, 180);
            s.aimAt(s.owner, s.enemy);
            assertFalse(s.ctx().isInsideArena(s.enemy.getLocation()), "the enemy stands outside the border");
            assertFalse(s.ultimate().succeeded(), "no quicksand outside the arena");
            assertEquals(100, s.participant(s.owner).gauge(), EPSILON, "the gauge is kept");
            s.finishClean();
        }
    }

    @Test
    void sandQuicksandNeedsGround() {
        try (Scenario s = Scenario.ffa("sand")) {
            s.place(s.owner, 0.5, FlatWorld.SURFACE, 0.5, 180, -80);
            assertFalse(s.ultimate().succeeded(), "no ground in the sky");
            assertEquals(100, s.participant(s.owner).gauge(), EPSILON, "the gauge is kept");
            List<Entity> left = s.world.getEntities().stream().filter(entity -> !(entity instanceof Player)).toList();
            assertTrue(left.isEmpty(), "nothing spawned: " + left);
            s.finishClean();
        }
    }
}
