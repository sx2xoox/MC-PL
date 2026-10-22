package io.github.sx2xoox.mcpl.game.superpower.scenario;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

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
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** 야생 동물 계열 (new): 문어, 곰, 독수리, 고슴도치. */
class WildNewAbilitiesTest {

    private static final double EPSILON = 1.0E-6;

    static Stream<String> abilities() {
        return Stream.of("octopus", "bear", "eagle", "hedgehog");
    }

    /** The ultimates the gambler may borrow (the bear's nap and the hedgehog's ball rely on their own hooks). */
    static Stream<String> standalone() {
        return Stream.of("octopus", "eagle");
    }

    // ================================================================== helpers

    private static <T extends Entity> List<T> entities(Scenario s, Class<T> type) {
        return s.world.getEntities().stream().filter(type::isInstance).map(type::cast).toList();
    }

    private static List<BlockDisplay> blocks(Scenario s, Material material) {
        return entities(s, BlockDisplay.class).stream().filter(d -> d.getBlock().getMaterial() == material).toList();
    }

    private static void assertNoDisplays(Scenario s, String what) {
        assertTrue(entities(s, Display.class).isEmpty(), what + ": every display is gone: "
                + entities(s, Display.class));
    }

    /** No attribute modifier but the scenario's own max health bonus. */
    private static void assertModifierFree(Player player, String what) {
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

    private static double attribute(Player player, Attribute attribute) {
        return player.getAttribute(attribute).getValue();
    }

    /** No ultimate gauge from damage or time, so gauge numbers in a test come from the ability alone. */
    private static Scenario.Builder noGaugeGain(Scenario.Builder builder) {
        return builder.configure(h -> {
            h.settings().set("ultimate.per-damage-dealt", 0);
            h.settings().set("ultimate.per-damage-taken", 0);
            h.settings().set("ultimate.per-second", 0);
        });
    }

    private static void assertUnrotated(Entity display, String what) {
        assertEquals(0, display.getLocation().getYaw(), 1.0E-3, what + ": display yaw");
        assertEquals(0, display.getLocation().getPitch(), 1.0E-3, what + ": display pitch");
    }

    /**
     * The left rotation a display's transformation was set with. Older MockBukkit hands it back conjugated, a faithful
     * test double does not: a probe display tells which, so the check holds with either.
     */
    private static Quaternionf leftRotation(Display display) {
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

    /** {@code local} turned by the display's left rotation. */
    private static Vector3f rotated(Display display, Vector3f local) {
        return leftRotation(display).transform(new Vector3f(local));
    }

    /** The world-space center of a block display's box, relative to the display's own position. */
    private static Vector3f boxCenter(BlockDisplay display) {
        Transformation t = display.getTransformation();
        return new Vector3f(t.getTranslation()).add(rotated(display, new Vector3f(t.getScale()).mul(0.5f)));
    }

    private static double horizontalDistance(Location a, Location b) {
        return Math.hypot(a.getX() - b.getX(), a.getZ() - b.getZ());
    }

    /** The horizontal unit vector a Minecraft yaw faces (0 is +Z, 90 is -X). */
    private static Vector facing(float yaw) {
        double radians = Math.toRadians(yaw);
        return new Vector(-Math.sin(radians), 0, Math.cos(radians));
    }

    /** An ability hit from {@code source} on {@code victim}, like an enemy's skill. */
    private static void abilityHit(Scenario s, Player source, Player victim, double amount) {
        s.ctx().damage(source, victim, amount);
    }

    /** Walks the hedgehog's ball along +z from {@code fromZ} to {@code toZ} at {@code step} blocks a tick. */
    private static void roll(Scenario s, double x, double fromZ, double toZ, double step) {
        int steps = (int) Math.ceil(Math.abs(toZ - fromZ) / step);
        double direction = Math.signum(toZ - fromZ);
        for (int i = 1; i <= steps; i++) {
            double z = fromZ + direction * Math.min(Math.abs(toZ - fromZ), i * step);
            Location at = new Location(s.world, x, FlatWorld.SURFACE, z, direction > 0 ? 0 : 180, 0);
            s.owner.teleport(at);
            s.owner.setVelocity(new Vector());
            s.ticks(1);
        }
    }

    /** Builds a stone pillar under (x, z) and puts {@code player} on top of it, {@code height} blocks up. */
    private static void standOnPillar(Scenario s, Player player, int x, int z, int height, float yaw) {
        for (int y = 0; y < height; y++) {
            s.world.setBlock(x, FlatWorld.FLOOR_Y + 1 + y, z, Material.STONE);
        }
        s.place(player, x + 0.5, FlatWorld.SURFACE + height, z + 0.5, yaw, 0);
    }

    private static void removePillar(Scenario s, int x, int z, int height) {
        for (int y = 0; y < height; y++) {
            s.world.setBlock(x, FlatWorld.FLOOR_Y + 1 + y, z, Material.AIR);
        }
    }

    /** Uses the ultimate the way the sweeps do, then lets the hedgehog's ball roll into the enemy. */
    private static void ultimateAndRoll(Scenario s, String id) {
        s.assertSucceeded(s.ultimate(), id + " ultimate");
        if (id.equals("hedgehog")) {
            s.ticks(2);
            roll(s, 0.5, 0.5, 4.0, 0.3);
        }
    }

    // ================================================================== every ability

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
            assertTrue(s.damageBy(s.owner, s.enemy) > 0, id + " active hurts the aimed enemy");
            assertEquals(0, s.damageTaken(s.owner), EPSILON, id + " active never hurts its owner: " + s.hits());

            s.layout();
            s.healAll();
            s.aimAt(s.owner, s.enemy);
            s.clearHits();
            ultimateAndRoll(s, id);
            s.ticks(300);
            assertTrue(s.damageBy(s.owner, s.enemy) + s.damageBy(s.owner, s.enemy2) > 0, id + " ultimate hurts");
            assertEquals(0, s.damageTaken(s.owner), EPSILON, id + " ultimate never hurts its owner: " + s.hits());
            s.assertNoProblems();
            assertNoDisplays(s, id);
            for (TestPlayer enemy : s.enemies()) {
                assertModifierFree(enemy, id);
                assertFalse(s.ctx().isSilenced(enemy), id + ": " + enemy.getName() + " is not silenced any more");
            }
            s.finishClean();
        }
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
            assertTrue(s.damageBy(s.owner, s.enemy) > 0, id + " still hits the enemy next to the ally");

            s.layout();
            s.crossfireAlly();
            s.aimAt(s.owner, s.enemy);
            allyStart = s.ally().getLocation();
            s.assertSucceeded(s.ultimate(), id + " ultimate");
            AbilityLifecycleTest.watchAlly(s, allyStart, 300);
            assertModifierFree(s.ally(), id);
            s.assertNoProblems();
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
            s.ticks(40);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), id + " ultimate");
            s.ticks(id.equals("octopus") ? 24 : 12); // the kraken's tentacles are up by then
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
        for (TestPlayer enemy : s.enemies()) {
            assertEquals(0, s.damageTaken(enemy), EPSILON, id + ": " + enemy.getName()
                    + " is not hurt by anything the dead owner left behind (a fall included): " + s.hits());
            assertModifierFree(enemy, id);
            assertFalse(s.ctx().isSilenced(enemy), id + ": " + enemy.getName() + " is not silenced any more");
        }
        assertModifierFree(s.owner, id);
        assertEquals(1.0, attribute(s.owner, Attribute.SCALE), EPSILON, id + ": normal size");
        s.assertNoProblems();
        s.finishClean();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("abilities")
    void ultimateWhileTheActiveRunsCleansUpWhenTheMatchEnds(String id) {
        try (Scenario s = Scenario.ffa(id)) {
            s.ticks(20);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), id + " active");
            s.ticks(3);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), id + " ultimate right after the active");
            s.ticks(8);
            s.assertNoProblems();
            s.finishClean();
        }
    }

    // ================================================================== borrowed by the gambler

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

    @Test
    void onlyTheKrakenAndTheSkyKingAreBorrowable() {
        try (Scenario s = gambler(false)) {
            assertFalse(s.ctx().registry().get("bear").info().ultimateStandalone(), "the nap lives in the bear's hooks");
            assertFalse(s.ctx().registry().get("hedgehog").info().ultimateStandalone(),
                    "the ball's armour lives in the hedgehog's hooks");
            for (String id : standalone().toList()) {
                assertTrue(s.ctx().registry().get(id).info().ultimateStandalone(), id);
            }
            s.finishClean();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("standalone")
    void borrowedUltimateWorksOnAGambler(String id) {
        try (Scenario s = gambler(false)) {
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(borrow(s, id), "borrowed " + id);
            boolean worked = false;
            for (int i = 0; i < 300; i++) {
                s.ticks(1);
                worked |= s.damageBy(s.owner, s.enemy) + s.damageBy(s.owner, s.enemy2) > 0;
            }
            assertTrue(worked, "the borrowed " + id + " ultimate does its thing");
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "the borrowed " + id + " never hurts the gambler");
            assertModifierFree(s.owner, id);
            for (TestPlayer target : s.enemies()) {
                assertModifierFree(target, id);
                assertFalse(s.ctx().isSilenced(target), id + ": the silence wore off");
            }
            assertNoDisplays(s, id);
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
            for (TestPlayer target : s.enemies()) {
                assertModifierFree(target, id);
                assertFalse(s.ctx().isSilenced(target), id);
            }
            s.assertNoProblems();
            s.finishClean();
        }
    }

    // ================================================================== 문어 octopus

    @Test
    void octopusInkCloudHidesItFromItsAttackerAndSpeedsItUp() {
        try (Scenario s = Scenario.builder("octopus").param("ink_chance", 100).start()) {
            double speed = attribute(s.owner, Attribute.MOVEMENT_SPEED);
            s.melee(s.enemy, s.owner, 2);
            assertFalse(s.enemy.canSee(s.owner), "vanished into the ink");
            assertFalse(s.enemy2.canSee(s.owner), "from every enemy");
            assertTrue(s.owner.hasPotionEffect(PotionEffectType.INVISIBILITY), "allies see the vanilla invisibility");
            assertEquals(speed * (1 + s.param("ink_speed") / 100), attribute(s.owner, Attribute.MOVEMENT_SPEED),
                    EPSILON, "a burst of speed to swim away");
            s.ticks(3);
            assertFalse(blocks(s, Material.BLACK_STAINED_GLASS).isEmpty(), "a blot of ink is left behind");
            s.ticks(s.paramTicks("ink_stealth") + 2);
            assertTrue(s.enemy.canSee(s.owner), "the ink clears after " + s.param("ink_stealth") + " s");
            assertFalse(s.owner.hasPotionEffect(PotionEffectType.INVISIBILITY));
            assertEquals(speed, attribute(s.owner, Attribute.MOVEMENT_SPEED), EPSILON, "the speed is gone too");

            s.melee(s.enemy, s.owner, 2);
            assertTrue(s.enemy.canSee(s.owner), "the ink sac needs " + s.param("ink_cooldown") + " s to refill");
            s.ticks(s.paramTicks("ink_cooldown"));
            s.melee(s.enemy2, s.owner, 2);
            assertFalse(s.enemy.canSee(s.owner), "refilled");
            s.melee(s.owner, s.enemy, 1);
            assertTrue(s.enemy.canSee(s.owner), "attacking out of the ink shows the octopus again");
            assertEquals(0, s.damageTaken(s.enemy) - 1, EPSILON, "the ink itself does no damage");
            s.ticks(40);
            s.finishClean();
        }
    }

    @Test
    void octopusInkOnlyAnswersEnemies() {
        try (Scenario s = Scenario.builder("octopus").param("ink_chance", 100).start()) {
            s.owner.setNoDamageTicks(0);
            s.owner.damage(2, DamageSource.builder(DamageType.FALL).build());
            assertTrue(s.enemy.canSee(s.owner), "a fall is not an attack");
            assertFalse(s.owner.hasPotionEffect(PotionEffectType.INVISIBILITY));
            s.finishClean();
        }
        try (Scenario s = Scenario.builder("octopus").param("ink_chance", 0).start()) {
            for (int i = 0; i < 5; i++) {
                s.melee(s.enemy, s.owner, 1);
                s.ticks(2);
            }
            assertTrue(s.enemy.canSee(s.owner), "no ink at 0%");
            s.finishClean();
        }
    }

    @Test
    void octopusInkShotBlindsSplashesAndLeavesASlickPuddle() {
        try (Scenario s = Scenario.ffa("octopus")) {
            s.place(s.enemy2, 9.5, -6.5, 0);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "먹물 발사");
            assertFalse(s.active().succeeded(), "on cooldown");
            s.h.tickUntil(() -> s.damageBy(s.owner, s.enemy) > 0, 20, "the blob reaches the enemy");
            assertEquals(s.param("shot_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "one splash");
            assertTrue(s.enemy.hasPotionEffect(PotionEffectType.BLINDNESS), "ink in the eyes");
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON, "enemy2 is far away");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);

            s.ticks(PUDDLE_SETTLE);
            List<BlockDisplay> plates = blocks(s, Material.BLACK_CONCRETE);
            assertEquals(2, plates.size(), "an octagon of two flat plates");
            for (BlockDisplay plate : plates) {
                assertUnrotated(plate, "puddle plate");
                assertEquals(FlatWorld.SURFACE, plate.getLocation().getY(), 1.0E-3, "on the floor, not in it");
                assertTrue(plate.getTransformation().getScale().y() < 0.05f, "flat");
                Vector3f center = boxCenter(plate);
                assertTrue(center.y() > 0 && center.y() < 0.05f, "lying on the floor: " + center.y());
            }
            double base = attribute(s.enemy, Attribute.MOVEMENT_SPEED);
            assertTrue(base < 0.1 * (1 - s.param("puddle_slow") / 100) + 1.0E-6,
                    "the enemy standing in the ink is slowed: " + base);
            s.place(s.enemy, 0.5, 9.5, 180);
            s.ticks(4);
            assertEquals(0.1, attribute(s.enemy, Attribute.MOVEMENT_SPEED), 1.0E-6, "the slow ends on dry ground");
            s.ticks(s.paramTicks("puddle_duration") + 15);
            assertTrue(blocks(s, Material.BLACK_CONCRETE).isEmpty(), "the puddle dried up");
            s.finishClean();
        }
    }

    private static final int PUDDLE_SETTLE = 6;

    @Test
    void octopusPuddleMakesMovingEnemiesSkid() {
        try (Scenario s = Scenario.ffa("octopus")) {
            s.place(s.enemy, -4.5, 9.5, 180);
            s.place(s.enemy2, 9.5, -6.5, 0);
            s.aimAtFloor(s.owner, 0.5, 6.5);
            s.assertSucceeded(s.active(), "먹물 발사 at the floor");
            s.ticks(12);
            List<BlockDisplay> plates = blocks(s, Material.BLACK_CONCRETE);
            assertEquals(2, plates.size(), "a puddle where the blob landed");
            Location puddle = plates.getFirst().getLocation();
            s.place(s.enemy, puddle.getX(), puddle.getZ(), 180);
            s.ticks(2);
            s.world.physics().setEnabled(false);
            Vector before = s.enemy.getVelocity();
            s.ticks(4);
            assertEquals(before, s.enemy.getVelocity(), "no skid without moving");
            // A step to the east: the ink carries the enemy on further than it meant.
            s.enemy.teleport(new Location(s.world, puddle.getX() + 0.4, FlatWorld.SURFACE, puddle.getZ(), 180, 0));
            s.ticks(2);
            Vector skid = s.enemy.getVelocity();
            assertTrue(skid.getX() > 0.2, "skids on in the direction it moved: " + skid);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "the puddle itself does no damage");
            s.world.physics().setEnabled(true);
            s.ticks(s.paramTicks("puddle_duration") + 15);
            s.finishClean();
        }
    }

    @Test
    void octopusInkShotSplatsOnAWallWithoutReachingThrough() {
        try (Scenario s = Scenario.ffa("octopus")) {
            s.world.wallAlongX(-3, 3, 3, 3, Material.STONE);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "먹물 발사 into a wall");
            s.ticks(20);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "the wall takes the ink: " + s.hits());
            List<BlockDisplay> plates = blocks(s, Material.BLACK_CONCRETE);
            assertEquals(2, plates.size(), "a puddle at the foot of the wall");
            assertTrue(plates.getFirst().getLocation().getZ() < 3, "on the octopus's side of the wall");
            assertEquals(FlatWorld.SURFACE, plates.getFirst().getLocation().getY(), 1.0E-3, "on the floor");
            s.ticks(s.paramTicks("puddle_duration") + 20);
            s.world.wallAlongX(-3, 3, 3, 3, Material.AIR);
            s.finishClean();
        }
    }

    @Test
    void octopusKrakenSeizesHoistsAndSlamsEveryEnemyInTheSea() {
        try (Scenario s = noGaugeGain(Scenario.builder("octopus")).start()) {
            s.assertSucceeded(s.ultimate(), "크라켄");
            assertFalse(s.ultimate().succeeded(), "one kraken at a time");
            s.ticks(s.paramTicks("kraken_delay") - 2);
            assertEquals(0, s.damageTaken(s.enemy) + s.damageTaken(s.enemy2), EPSILON, "the sea is only spreading");
            assertTrue(entities(s, BlockDisplay.class).isEmpty(), "no tentacles yet");
            s.ticks(4);
            for (TestPlayer target : s.enemies()) {
                assertEquals(s.param("grab_damage"), s.damageBy(s.owner, target), EPSILON, target.getName() + " seized");
            }
            List<BlockDisplay> segments = entities(s, BlockDisplay.class);
            assertEquals(3 * (int) s.param("tentacles"), segments.size(), "eight tentacles of three segments");
            s.ticks(8);
            for (TestPlayer target : s.enemies()) {
                assertTrue(target.getLocation().getY() > FlatWorld.SURFACE + 1.5, target.getName() + " is hoisted: "
                        + target.getLocation().getY());
            }
            for (BlockDisplay segment : entities(s, BlockDisplay.class)) {
                assertUnrotated(segment, "tentacle segment");
                assertTrue(segment.getLocation().getY() >= FlatWorld.SURFACE, "anchored in the air above the ground");
            }
            for (BlockDisplay root : blocks(s, Material.PURPLE_CONCRETE)) {
                Vector3f axis = rotated(root, new Vector3f(0, 0, 1));
                assertTrue(axis.y() > 0.5f, "a tentacle's root rises out of the ground: " + axis);
            }
            s.h.tickUntil(() -> s.damageBy(s.owner, s.enemy) > s.param("grab_damage"), 60, "the slam");
            s.ticks(2);
            for (TestPlayer target : s.enemies()) {
                assertEquals(s.param("grab_damage") + s.param("slam_damage"), s.damageBy(s.owner, target), EPSILON,
                        target.getName() + " seized and slammed (never lashed on top)");
                assertTrue(target.hasPotionEffect(PotionEffectType.SLOWNESS), target.getName() + " is dazed");
            }
            s.ticks(10);
            for (TestPlayer target : s.enemies()) {
                assertEquals(FlatWorld.SURFACE, target.getLocation().getY(), 1.0E-6,
                        target.getName() + " lies on the ground, not in it");
                assertTrue(Spots.isFree(s.world, target.getLocation(), target.getWidth(), target.getHeight()));
            }
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.ticks(40);
            assertNoDisplays(s, "the tentacles sank");
            s.finishClean();
        }
    }

    @Test
    void octopusFreeTentaclesLashWhoeverWadesIn() {
        try (Scenario s = Scenario.builder("octopus").param("tentacles", 8).start()) {
            s.place(s.enemy2, 12.5, 12.5, 0);
            s.assertSucceeded(s.ultimate(), "크라켄");
            s.ticks(s.paramTicks("kraken_delay") + 3);
            assertEquals(s.param("grab_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "the enemy is seized");
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON, "enemy2 was outside the sea");
            // enemy2 wades in right under a free tentacle before the strike.
            List<BlockDisplay> roots = blocks(s, Material.PURPLE_CONCRETE);
            Location target = null;
            for (BlockDisplay root : roots) {
                Location base = root.getLocation();
                if (horizontalDistance(base, s.enemy.getLocation()) > 2.5) {
                    Vector out = base.toVector().subtract(new Vector(0.5, base.getY(), 0.5)).setY(0).normalize();
                    target = base.clone().add(out.multiply(2.7));
                    break;
                }
            }
            if (target == null) {
                fail("a free tentacle");
            }
            s.place(s.enemy2, target.getX(), target.getZ(), 0);
            s.h.tickUntil(() -> s.damageBy(s.owner, s.enemy2) > 0, 60, "the lash");
            assertEquals(s.param("lash_damage"), s.damageBy(s.owner, s.enemy2), EPSILON, "lashed once");
            s.ticks(60);
            s.finishClean();
        }
    }

    @Test
    void octopusKrakenCanBeEscapedDuringTheWarning() {
        try (Scenario s = Scenario.ffa("octopus")) {
            s.assertSucceeded(s.ultimate(), "크라켄");
            s.ticks(3);
            s.place(s.enemy, 0.5, 16.5, 180);
            s.place(s.enemy2, 14.5, 4.5, 180);
            s.ticks(80);
            assertEquals(0, s.damageTaken(s.enemy) + s.damageTaken(s.enemy2), EPSILON, "they swam out of the sea");
            s.finishClean();
        }
    }

    @Test
    void octopusKrakenNeverSeizesAHiddenEnemyAndNeedsSomeoneInTheSea() {
        try (Scenario s = Scenario.builder("octopus").param("kraken_radius", 2).start()) {
            assertFalse(s.ultimate().succeeded(), "nobody within 2 blocks");
            s.finishClean();
        }
        try (Scenario s = Scenario.ffa("octopus")) {
            s.place(s.enemy2, 12.5, 12.5, 0);
            s.owner.hidePlayer(s.ctx().plugin(), s.enemy);
            s.assertSucceeded(s.ultimate(), "크라켄");
            s.ticks(s.paramTicks("kraken_delay") + 4);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "a tentacle seizing it would show where it hides");
            assertTrue(s.enemy.getLocation().getY() < FlatWorld.SURFACE + 0.5, "not hoisted");
            s.owner.showPlayer(s.ctx().plugin(), s.enemy);
            s.ticks(80);
            s.finishClean();
        }
    }

    // ================================================================== 곰 bear

    @Test
    void bearThickHideBluntsMeleeAndKnockback() {
        try (Scenario s = Scenario.ffa("bear")) {
            assertEquals(s.param("hide_knockback") / 100, attribute(s.owner, Attribute.KNOCKBACK_RESISTANCE), EPSILON,
                    "knockback resistance");
            s.melee(s.enemy, s.owner, 6);
            assertEquals(6 - s.param("hide_flat"), s.damageTaken(s.owner), EPSILON, "a flat bite out of the blow");
            s.clearHits();
            s.melee(s.enemy, s.owner, 1.2);
            assertEquals(s.param("hide_min"), s.damageTaken(s.owner), EPSILON, "never below the minimum");
            s.clearHits();
            s.melee(s.enemy, s.owner, 0.5);
            assertEquals(0.5, s.damageTaken(s.owner), EPSILON, "a weaker blow than the minimum is left alone");
            s.clearHits();
            abilityHit(s, s.enemy, s.owner, 4);
            assertEquals(4, s.damageTaken(s.owner), EPSILON, "skills are not melee");
            s.finishClean();
        }
    }

    @Test
    void bearPawRearsUpThenMaulsAndPinsTheFan() {
        try (Scenario s = noGaugeGain(Scenario.builder("bear")).start()) {
            s.place(s.enemy2, 0.5, -2.5, 0); // behind the bear
            s.assertSucceeded(s.active(), "곰 발바닥");
            assertFalse(s.active().succeeded(), "on cooldown");
            s.ticks(2);
            assertTrue(attribute(s.owner, Attribute.MOVEMENT_SPEED) < 0.1, "rearing up slows the bear");
            assertEquals(8, blocks(s, Material.BROWN_WOOL).size() + blocks(s, Material.BONE_BLOCK).size(),
                    "two paws with three claws each");
            s.ticks(s.paramTicks("paw_windup") - 4);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "nothing lands while the bear rears up");
            s.ticks(4);
            assertEquals(s.param("paw_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "mauled");
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON, "the bear does not maul behind itself");
            assertEquals(0, attribute(s.enemy, Attribute.MOVEMENT_SPEED), EPSILON, "pinned to the ground");
            assertEquals(0, attribute(s.enemy, Attribute.JUMP_STRENGTH), EPSILON, "cannot jump up");
            assertEquals(3, blocks(s, Material.RED_CONCRETE).size(), "claw marks across the victim");
            for (BlockDisplay mark : blocks(s, Material.RED_CONCRETE)) {
                assertUnrotated(mark, "claw mark");
                Vector3f rake = rotated(mark, new Vector3f(1, 0, 0));
                assertTrue(Math.abs(rake.z()) < 0.05f && Math.abs(rake.x()) > 0.4f && rake.y() > 0.6f,
                        "a diagonal slash across the bear's line of attack (+z): " + rake);
            }
            assertEquals(1, entities(s, TextDisplay.class).size(), "꽈당!");

            s.clearHits();
            s.melee(s.owner, s.enemy, 5);
            assertEquals(5 * (1 + s.param("downed_bonus") / 100), s.damageBy(s.owner, s.enemy), EPSILON,
                    "a downed enemy takes more from the bear's claws");
            s.ticks(s.paramTicks("paw_knockdown") + 2);
            assertEquals(0.1, attribute(s.enemy, Attribute.MOVEMENT_SPEED), EPSILON, "back on its feet");
            s.clearHits();
            s.melee(s.owner, s.enemy, 5);
            assertEquals(5, s.damageBy(s.owner, s.enemy), EPSILON, "no bonus once it stood up");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.ticks(60);
            assertNoDisplays(s, "paws, claws, gouges and stars");
            s.finishClean();
        }
    }

    @Test
    void bearPawTurnsWithTheBearAndItsPartsStayUnrotated() {
        try (Scenario s = Scenario.ffa("bear")) {
            s.place(s.owner, 0.5, 0.5, 90); // facing west (-x)
            s.place(s.enemy, -3.0, 0.5, 270);
            s.place(s.enemy2, 0.5, 4.5, 180); // to the bear's left: outside the fan
            s.assertSucceeded(s.active(), "곰 발바닥 facing west");
            s.ticks(s.paramTicks("paw_windup") + 2);
            assertEquals(s.param("paw_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "the enemy to the west");
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON, "the enemy at the bear's side is outside the fan");
            for (BlockDisplay part : entities(s, BlockDisplay.class)) {
                assertUnrotated(part, part.getBlock().getMaterial().name());
            }
            for (BlockDisplay pad : blocks(s, Material.BROWN_WOOL)) {
                Vector3f center = boxCenter(pad);
                assertTrue(center.x() < -1.0f && center.y() < 0.6f, "the paw came down in front, to the west: " + center);
            }
            for (BlockDisplay claw : blocks(s, Material.BONE_BLOCK)) {
                Vector3f along = rotated(claw, new Vector3f(0, 0, 1));
                assertTrue(along.x() < -0.5f, "the claws point west, the way the bear faces: " + along);
            }
            for (BlockDisplay gouge : blocks(s, Material.BLACK_TERRACOTTA)) {
                assertEquals(FlatWorld.SURFACE, gouge.getLocation().getY(), 1.0E-3, "gouges lie on the floor");
                Vector3f along = rotated(gouge, new Vector3f(0, 0, 1));
                assertTrue(Math.abs(along.x()) > 0.95f, "torn along the blow: " + along);
            }
            s.ticks(60);
            s.finishClean();
        }
    }

    @Test
    void bearHibernationRootsHealsBluntsAndWakesWithARoar() {
        try (Scenario s = noGaugeGain(Scenario.builder("bear")).start()) {
            s.owner.setHealth(10);
            s.assertSucceeded(s.ultimate(), "동면");
            assertFalse(s.ultimate().succeeded(), "already asleep");
            assertEquals(0, attribute(s.owner, Attribute.MOVEMENT_SPEED), EPSILON, "asleep: no moving");
            assertEquals(0, attribute(s.owner, Attribute.JUMP_STRENGTH), EPSILON, "asleep: no jumping");
            assertFalse(s.active().succeeded(), "no mauling in its sleep");
            s.ticks(3);
            assertFalse(blocks(s, Material.MOSS_CARPET).isEmpty(), "a mossy den");
            s.ticks(18);
            assertEquals(10 + s.param("sleep_heal"), s.owner.getHealth(), EPSILON, "heals every second");
            s.clearHits();
            abilityHit(s, s.enemy2, s.owner, 4);
            assertEquals(4 * (1 - s.param("sleep_reduction") / 100), s.damageTaken(s.owner), EPSILON,
                    "the winter hide takes most of it");
            s.melee(s.owner, s.enemy, 3);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "a sleeping bear does not fight");
            s.clearHits();
            s.ticks(s.paramTicks("sleep_duration"));
            assertEquals(s.param("roar_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "the wake-up roar");
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON, "enemy2 is out of the roar's reach");
            assertTrue(attribute(s.owner, Attribute.MOVEMENT_SPEED) > 0.1, "angry and fast");
            s.clearHits();
            s.place(s.enemy, 0.5, 2.0, 180);
            s.melee(s.owner, s.enemy, 5);
            assertEquals(5 * (1 + s.param("rage_melee") / 100), s.damageBy(s.owner, s.enemy), EPSILON,
                    "rage hits harder");
            s.ticks(s.paramTicks("rage_duration") + 2);
            assertEquals(0.1, attribute(s.owner, Attribute.MOVEMENT_SPEED), EPSILON, "the rage cooled off");
            assertEquals(s.param("hide_knockback") / 100, attribute(s.owner, Attribute.KNOCKBACK_RESISTANCE), EPSILON,
                    "only the thick hide is left");
            s.ticks(30);
            assertNoDisplays(s, "den, Zs and roar");
            s.finishClean();
        }
    }

    @Test
    void bearWokenRudelyRoarsHarder() {
        try (Scenario s = noGaugeGain(Scenario.builder("bear")).start()) {
            s.place(s.enemy2, 0.5, -3.5, 0);
            s.assertSucceeded(s.ultimate(), "동면");
            s.ticks(10);
            int hits = (int) s.param("wake_hits");
            for (int i = 0; i < hits; i++) {
                assertEquals(0, attribute(s.owner, Attribute.MOVEMENT_SPEED), EPSILON, "still asleep after " + i);
                s.melee(s.enemy, s.owner, 2);
                s.ticks(2);
            }
            s.ticks(2);
            assertTrue(attribute(s.owner, Attribute.MOVEMENT_SPEED) > 0.1, "woken up early, furious");
            assertEquals(s.param("rude_roar_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "the angry roar");
            assertEquals(s.param("rude_roar_damage"), s.damageBy(s.owner, s.enemy2), EPSILON, "around the bear");
            s.ticks(s.paramTicks("rage_duration") + 20);
            s.finishClean();
        }
    }

    @Test
    void bearWakesWhenItsOwnerSneaks() {
        try (Scenario s = noGaugeGain(Scenario.builder("bear")).start()) {
            s.owner.setSneaking(true);
            s.assertSucceeded(s.ultimate(), "동면 while sneaking");
            s.ticks(15);
            assertEquals(0, attribute(s.owner, Attribute.MOVEMENT_SPEED), EPSILON,
                    "the sneak that was held when it fell asleep does not wake it");
            s.owner.setSneaking(false);
            s.ticks(2);
            s.owner.setSneaking(true);
            s.ticks(2);
            assertTrue(attribute(s.owner, Attribute.MOVEMENT_SPEED) > 0.1, "a fresh sneak wakes it");
            assertEquals(s.param("roar_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "a normal roar");
            s.owner.setSneaking(false);
            s.ticks(s.paramTicks("rage_duration") + 20);
            s.finishClean();
        }
    }

    // ================================================================== 독수리 eagle

    @Test
    void eagleSpotsEnemiesFarBelowAndStrikesThemHarder() {
        try (Scenario s = Scenario.ffa("eagle")) {
            s.ticks(12);
            assertFalse(s.enemy.hasPotionEffect(PotionEffectType.GLOWING), "level with the eagle: not spotted");
            s.melee(s.owner, s.enemy, 4);
            assertEquals(4, s.damageBy(s.owner, s.enemy), EPSILON, "no bonus on level ground");

            int height = (int) s.param("eye_height") + 1;
            standOnPillar(s, s.owner, 0, 0, height, 0);
            s.ticks(12);
            assertTrue(s.enemy.hasPotionEffect(PotionEffectType.GLOWING), "spotted from up high");
            assertTrue(s.enemy2.hasPotionEffect(PotionEffectType.GLOWING), "both of them");
            s.clearHits();
            s.melee(s.owner, s.enemy, 4);
            assertEquals(4 * (1 + s.param("eye_bonus") / 100), s.damageBy(s.owner, s.enemy), EPSILON,
                    "the hawk's eye strikes harder");
            s.owner.hidePlayer(s.ctx().plugin(), s.enemy2);
            s.enemy2.removePotionEffect(PotionEffectType.GLOWING);
            s.ticks(12);
            assertFalse(s.enemy2.hasPotionEffect(PotionEffectType.GLOWING), "a hidden enemy is never revealed");
            s.owner.showPlayer(s.ctx().plugin(), s.enemy2);
            removePillar(s, 0, 0, height);
            s.tickUntilGrounded(s.owner, 200);
            s.finishClean();
        }
    }

    @Test
    void eagleSnatchesCarriesAndDropsItsPrey() {
        // Without the hawk's eye: the eagle is still high above when its prey hits the ground (that bonus is real).
        try (Scenario s = noGaugeGain(Scenario.builder("eagle").param("eye_bonus", 0)).start()) {
            s.place(s.enemy2, 9.5, -6.5, 0);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "낚아채기");
            assertFalse(s.active().succeeded(), "on cooldown");
            s.h.tickUntil(() -> s.damageBy(s.owner, s.enemy) > 0, 12, "the grab");
            assertEquals(s.param("snatch_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "talons");
            double highest = 0;
            for (int i = 0; i < s.paramTicks("carry_time"); i++) {
                s.ticks(1);
                highest = Math.max(highest, s.enemy.getLocation().getY() - FlatWorld.SURFACE);
                assertTrue(s.owner.getLocation().getY() > s.enemy.getLocation().getY(), "the eagle flies on top");
            }
            assertTrue(highest > s.param("carry_height") * 0.7, "carried up into the air: " + highest);
            s.h.tickUntil(() -> s.damageBy(s.owner, s.enemy) > s.param("snatch_damage"), 60, "the drop");
            assertEquals(s.param("snatch_damage") + s.param("drop_damage"), s.damageBy(s.owner, s.enemy), EPSILON,
                    "hurt by the drop, not by a fall on top");
            assertTrue(s.enemy.hasPotionEffect(PotionEffectType.SLOWNESS), "dazed by the drop");
            assertTrue(s.enemy.getLocation().getZ() > 6, "carried on along the eagle's view: " + s.enemy.getLocation());
            assertEquals(FlatWorld.SURFACE, s.enemy.getLocation().getY(), 1.0E-6, "landed on the ground, not in it");
            assertTrue(s.ctx().cooldownRemaining(s.owner) > s.param("cooldown") * 0.5, "no refund for a catch");
            assertTrue(s.hitsOf(s.enemy, org.bukkit.event.entity.EntityDamageEvent.DamageCause.FALL).isEmpty(),
                    "no vanilla fall damage on top");
            s.tickUntilGrounded(s.owner, 200);
            s.ticks(20);
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "the eagle lands softly");
            assertEquals(FlatWorld.SURFACE, s.owner.getLocation().getY(), 1.0E-6, "the eagle stands on the ground");
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON);
            assertNoDisplays(s, "wings folded");
            s.finishClean();
        }
    }

    @Test
    void eagleSnatchWithoutPreyRefundsPartOfTheCooldown() {
        try (Scenario s = Scenario.ffa("eagle")) {
            s.place(s.enemy, 9.5, -12.5, 0);
            s.place(s.enemy2, -9.5, -12.5, 0);
            s.place(s.owner, 0.5, 0.5, 0); // looking along +z: nobody there
            s.assertSucceeded(s.active(), "낚아채기 into the empty air");
            s.ticks((int) Math.ceil(s.param("snatch_range")) + 4);
            double expected = s.param("cooldown") * (1 - s.param("miss_refund") / 100);
            double remaining = s.ctx().cooldownRemaining(s.owner);
            assertTrue(remaining < expected + 0.05 && remaining > expected - 1.0,
                    "half the cooldown comes back: " + remaining);
            assertTrue(s.owner.getLocation().getZ() > s.param("snatch_range") * 0.7, "glided ahead: "
                    + s.owner.getLocation());
            s.tickUntilGrounded(s.owner, 200);
            s.ticks(20);
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "a soft landing");
            assertEquals(0, s.damageTaken(s.enemy) + s.damageTaken(s.enemy2), EPSILON);
            s.finishClean();
        }
    }

    @Test
    void eagleSnatchStopsAtWalls() {
        try (Scenario s = Scenario.ffa("eagle")) {
            s.world.wallAlongX(-3, 3, 3, 4, Material.STONE);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "낚아채기 into a wall");
            s.ticks(20);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "the wall is in the way");
            assertTrue(s.owner.getLocation().getZ() < 3, "the eagle stays on its side: " + s.owner.getLocation());
            s.tickUntilGrounded(s.owner, 200);
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.world.wallAlongX(-3, 3, 3, 4, Material.AIR);
            s.finishClean();
        }
    }

    @Test
    void eagleSnatchIgnoresAStaleVelocity() {
        try (Scenario s = Scenario.ffa("eagle")) {
            s.ticks(2);
            s.place(s.owner, 0.5, 0.5, 90); // facing west, nobody there
            s.world.physics().setEnabled(false);
            // A real server never reads a player's motion back: its velocity is what it last sent.
            s.owner.setVelocity(new Vector(0, 0, 1.5));
            s.assertSucceeded(s.active(), "낚아채기");
            s.ticks(1);
            Vector glide = s.owner.getVelocity();
            assertTrue(glide.getX() < -0.5, "glides west: " + glide);
            assertEquals(0, glide.getZ(), 0.05, "no stale push to the south: " + glide);
            s.world.physics().setEnabled(true);
            s.ticks(20);
            s.tickUntilGrounded(s.owner, 200);
            s.finishClean();
        }
    }

    @Test
    void eagleWingsSpreadToTheSidesAndStayUnrotated() {
        try (Scenario s = Scenario.ffa("eagle")) {
            s.place(s.enemy, 0.5, 12.5, 180);
            s.place(s.enemy2, 9.5, 12.5, 180);
            s.place(s.owner, 0.5, 0.5, 90); // facing west (-x), nobody there
            s.assertSucceeded(s.active(), "낚아채기 west");
            s.ticks(3);
            List<BlockDisplay> coverts = blocks(s, Material.BROWN_WOOL);
            assertEquals(2, coverts.size(), "two wings");
            List<BlockDisplay> primaries = blocks(s, Material.BROWN_TERRACOTTA);
            assertEquals(2, primaries.size(), "with dark primaries");
            float yaw = s.owner.getLocation().getYaw();
            Vector left = facing(yaw + 90).multiply(-1); // facing west, the left side is south (+z)
            double sides = 1;
            for (BlockDisplay wing : coverts) {
                assertUnrotated(wing, "wing");
                Vector3f center = boxCenter(wing);
                double side = center.x() * left.getX() + center.z() * left.getZ();
                assertTrue(Math.abs(side) > 0.12, "the wing spreads to a side: " + center);
                sides *= side;
                double back = -(center.x() * facing(yaw).getX() + center.z() * facing(yaw).getZ());
                assertTrue(back > -0.1, "and does not reach forward: " + center);
                assertTrue(center.y() > 1.0f, "from the shoulders: " + center);
            }
            assertTrue(sides < 0, "one wing to each side");
            double tips = 1;
            for (BlockDisplay primary : primaries) {
                assertUnrotated(primary, "primaries");
                Vector3f center = boxCenter(primary);
                double side = center.x() * left.getX() + center.z() * left.getZ();
                assertTrue(Math.abs(side) > 0.5, "the primaries reach far out: " + center);
                tips *= side;
            }
            assertTrue(tips < 0, "one wingtip to each side");
            for (BlockDisplay tail : blocks(s, Material.WHITE_WOOL)) {
                assertUnrotated(tail, "tail");
                Vector3f center = boxCenter(tail);
                assertTrue(center.x() > 0.05f, "the tail fans out behind (east): " + center);
            }
            s.ticks(30);
            s.tickUntilGrounded(s.owner, 200);
            s.finishClean();
        }
    }

    @Test
    void eagleSkyKingSoarsWithThePreyAndDivesOntoTheMarkedGround() {
        try (Scenario s = noGaugeGain(Scenario.builder("eagle")).start()) {
            s.place(s.enemy2, 7.5, -3.5, 0);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "하늘의 제왕");
            assertFalse(s.ultimate().succeeded(), "the gauge is spent");
            assertFalse(s.active().succeeded(), "no snatching while soaring");
            s.h.tickUntil(() -> s.ctx().isSilenced(s.enemy), 20, "the grab");
            s.ticks(14);
            assertTrue(s.enemy.getLocation().getY() > FlatWorld.SURFACE + s.param("ult_height") * 0.8,
                    "high in the sky: " + s.enemy.getLocation().getY());
            assertTrue(s.owner.getLocation().getY() > s.enemy.getLocation().getY(), "the eagle over its prey");
            assertEquals(0, s.damageTaken(s.enemy) + s.damageTaken(s.enemy2), EPSILON, "nothing before the dive");
            // Choose where to strike: the floor next to enemy2, keeping the aim on it while circling.
            for (int i = 0; i < 80 && s.damageBy(s.owner, s.enemy) <= 0; i++) {
                s.aimAtFloor(s.owner, 6.5, -2.5);
                s.ticks(1);
            }
            assertEquals(s.param("ult_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "slammed into the ground");
            assertEquals(s.param("impact_damage"), s.damageBy(s.owner, s.enemy2), EPSILON,
                    "enemy2 is caught by the impact");
            Location landed = s.enemy.getLocation();
            assertTrue(horizontalDistance(landed, new Location(s.world, 6.5, FlatWorld.SURFACE, -2.5)) < 1.0,
                    "driven into the aimed ground: " + landed);
            s.ticks(4);
            List<BlockDisplay> cracks = blocks(s, Material.BLACK_CONCRETE);
            assertFalse(cracks.isEmpty(), "the ground cracks");
            for (BlockDisplay crack : cracks) {
                assertUnrotated(crack, "crack");
                assertEquals(FlatWorld.SURFACE, crack.getLocation().getY(), 1.0E-3, "on the floor, not in it");
                Vector3f along = rotated(crack, new Vector3f(0, 0, 1));
                Vector3f center = boxCenter(crack);
                double outward = (along.x() * center.x() + along.z() * center.z())
                        / Math.max(1.0E-6, Math.hypot(center.x(), center.z()));
                assertTrue(outward > 0.9 && Math.abs(along.y()) < 0.05f, "radiating from the impact: " + along);
            }
            s.ticks(10);
            assertEquals(FlatWorld.SURFACE, s.enemy.getLocation().getY(), 1.0E-6, "on the ground, not in it");
            assertTrue(Spots.isFree(s.world, s.enemy.getLocation(), s.enemy.getWidth(), s.enemy.getHeight()));
            assertTrue(s.hitsOf(s.enemy, org.bukkit.event.entity.EntityDamageEvent.DamageCause.FALL).isEmpty(),
                    "no vanilla fall damage on top");
            s.tickUntilGrounded(s.owner, 200);
            s.ticks(20);
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "the eagle never hurts itself");
            assertFalse(s.ctx().isSilenced(s.enemy), "the grip (and its silence) is over");
            assertFalse(s.enemy.hasPotionEffect(PotionEffectType.SLOW_FALLING), "the safety net is taken off");
            s.ticks(40);
            assertNoDisplays(s, "wings and cracks");
            s.finishClean();
        }
    }

    @Test
    void eagleSkyKingDiveCanBeDodgedByTheBystanders() {
        try (Scenario s = Scenario.ffa("eagle")) {
            s.place(s.enemy2, 2.5, 5.5, 180);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "하늘의 제왕");
            s.h.tickUntil(() -> s.ctx().isSilenced(s.enemy), 20, "the grab");
            s.ticks(8);
            s.place(s.enemy2, 14.5, 14.5, 180); // sees the reticle and runs
            s.h.tickUntil(() -> s.damageBy(s.owner, s.enemy) > 0, 80, "the dive");
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON, "enemy2 got out of the reticle");
            s.tickUntilGrounded(s.owner, 200);
            s.finishClean();
        }
    }

    @Test
    void eagleSkyKingNeedsPreyInRange() {
        try (Scenario s = Scenario.builder("eagle").param("ult_range", 2).start()) {
            assertFalse(s.ultimate().succeeded(), "nobody within 2 blocks");
            s.finishClean();
        }
    }

    @Test
    void eagleSkyKingLetsGoOfAPreyThatBlinksAway() {
        try (Scenario s = Scenario.ffa("eagle")) {
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "하늘의 제왕");
            s.h.tickUntil(() -> s.ctx().isSilenced(s.enemy), 20, "the grab");
            s.ticks(6);
            s.place(s.enemy, 14.5, 14.5, 180); // teleported away
            s.ticks(60);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "no dive for an escaped prey: " + s.hits());
            s.tickUntilGrounded(s.owner, 300);
            s.ticks(20);
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "the eagle glides down safely");
            s.finishClean();
        }
    }

    // ================================================================== 고슴도치 hedgehog

    @Test
    void hedgehogQuillsPrickMeleeAttackersAndStickInThem() {
        try (Scenario s = Scenario.ffa("hedgehog")) {
            s.melee(s.enemy, s.owner, 2);
            s.ticks(2);
            assertEquals(s.param("thorn_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "pricked");
            assertEquals(1, blocks(s, Material.POINTED_DRIPSTONE).size(), "a quill stuck in the attacker");
            s.melee(s.enemy, s.owner, 2);
            s.ticks(2);
            assertEquals(s.param("thorn_damage"), s.damageBy(s.owner, s.enemy), EPSILON,
                    "only every " + s.param("thorn_cooldown") + " s");
            for (int i = 0; i < 5; i++) {
                s.ticks(s.paramTicks("thorn_cooldown"));
                s.melee(s.enemy, s.owner, 2);
                s.ticks(2);
            }
            assertEquals((int) s.param("quill_max"), blocks(s, Material.POINTED_DRIPSTONE).size(), "at most "
                    + s.param("quill_max") + " quills");
            for (BlockDisplay quill : blocks(s, Material.POINTED_DRIPSTONE)) {
                assertUnrotated(quill, "stuck quill");
                assertTrue(horizontalDistance(quill.getLocation(), s.enemy.getLocation()) < 1.0E-6,
                        "rides on the enemy");
            }
            s.clearHits();
            abilityHit(s, s.enemy2, s.owner, 2);
            s.ticks(2);
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON, "skills are not pricked");
            s.place(s.enemy, 3.5, 6.5, 180);
            s.ticks(3);
            for (BlockDisplay quill : blocks(s, Material.POINTED_DRIPSTONE)) {
                assertTrue(horizontalDistance(quill.getLocation(), s.enemy.getLocation()) < 1.0E-6,
                        "the quills go where the enemy goes");
            }
            s.ticks(s.paramTicks("quill_duration") + 10);
            assertTrue(blocks(s, Material.POINTED_DRIPSTONE).isEmpty(), "the quills fell out");
            s.finishClean();
        }
    }

    @Test
    void hedgehogVolleyHitsEveryoneAroundOnceAndTearsOutStuckQuills() {
        try (Scenario s = noGaugeGain(Scenario.builder("hedgehog")).start()) {
            s.place(s.enemy2, -3.5, -3.5, 45); // behind the hedgehog
            s.melee(s.enemy, s.owner, 2);
            s.ticks(s.paramTicks("thorn_cooldown") + 1);
            s.melee(s.enemy, s.owner, 2);
            s.ticks(2);
            s.clearHits();
            s.assertSucceeded(s.active(), "가시 발사");
            assertFalse(s.active().succeeded(), "on cooldown");
            s.ticks(2);
            List<BlockDisplay> flying = blocks(s, Material.POINTED_DRIPSTONE);
            assertTrue(flying.size() >= (int) s.param("quill_count"), "a ring of quills: " + flying.size());
            s.ticks(12);
            assertEquals(s.param("volley_damage") + 2 * s.param("quill_burst"), s.damageBy(s.owner, s.enemy),
                    EPSILON, "two stuck quills torn out on top");
            assertEquals(s.param("volley_damage"), s.damageBy(s.owner, s.enemy2), EPSILON, "behind it too, once");
            assertEquals(1, s.hitsBy(s.owner, s.enemy).size(), "one quill per enemy");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.ticks(20);
            assertNoDisplays(s, "quills");
            s.finishClean();
        }
    }

    @Test
    void hedgehogVolleyQuillsPointAlongTheirFlightAndStopAtWalls() {
        try (Scenario s = Scenario.ffa("hedgehog")) {
            s.world.wallAlongX(-3, 3, 3, 3, Material.STONE);
            s.place(s.enemy2, -4.5, 0.5, 270); // to the west, in the open
            s.assertSucceeded(s.active(), "가시 발사");
            s.ticks(2);
            List<BlockDisplay> quills = blocks(s, Material.POINTED_DRIPSTONE);
            assertFalse(quills.isEmpty(), "quills in flight");
            Location center = s.owner.getLocation();
            for (BlockDisplay quill : quills) {
                assertUnrotated(quill, "quill");
                Vector out = quill.getLocation().toVector().subtract(center.toVector()).setY(0);
                if (out.lengthSquared() < 0.25) {
                    continue;
                }
                Vector3f tip = rotated(quill, new Vector3f(0, 1, 0));
                double cos = (tip.x() * out.getX() + tip.z() * out.getZ()) / out.length()
                        / Math.max(1.0E-6, Math.hypot(tip.x(), tip.z()));
                assertTrue(cos > 0.9, "the quill's tip leads its flight (cos=" + cos + ")");
            }
            s.ticks(20);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "the wall shields the enemy behind it");
            assertEquals(s.param("volley_damage"), s.damageBy(s.owner, s.enemy2), EPSILON, "the open side is hit");
            s.ticks(20);
            s.world.wallAlongX(-3, 3, 3, 3, Material.AIR);
            s.finishClean();
        }
    }

    @Test
    void hedgehogBallCurlsUpRollsIntoEnemiesAndStrewsCaltrops() {
        try (Scenario s = noGaugeGain(Scenario.builder("hedgehog")).start()) {
            s.place(s.enemy2, 6.5, 12.5, 180);
            double speed = attribute(s.owner, Attribute.MOVEMENT_SPEED);
            s.assertSucceeded(s.ultimate(), "가시 공");
            assertFalse(s.ultimate().succeeded(), "already a ball");
            assertEquals(1 - 0.4, attribute(s.owner, Attribute.SCALE), EPSILON, "curled up small");
            assertEquals(speed * (1 + s.param("ball_speed") / 100), attribute(s.owner, Attribute.MOVEMENT_SPEED),
                    EPSILON, "rolls fast");
            assertTrue(attribute(s.owner, Attribute.KNOCKBACK_RESISTANCE) >= 1 - EPSILON, "cannot be pushed");
            s.ticks(2);
            List<BlockDisplay> ball = blocks(s, Material.POINTED_DRIPSTONE);
            assertEquals(8, ball.size(), "eight spikes on the ball");
            for (BlockDisplay spike : ball) {
                assertUnrotated(spike, "ball spike");
                assertFalse(s.owner.canSee(spike), "the spikes would stab into the hedgehog's own view");
                assertTrue(s.enemy.canSee(spike), "everyone else sees them");
            }
            s.clearHits();
            s.melee(s.enemy2, s.owner, 10);
            assertEquals(10 * (1 - s.param("ball_reduction") / 100), s.damageTaken(s.owner), EPSILON,
                    "the ball's spikes take the edge off");
            s.ticks(2);
            s.clearHits();

            roll(s, 0.5, 0.5, 4.0, 0.3);
            assertEquals(s.param("ball_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "bowled over");
            assertTrue(s.enemy.getVelocity().length() > 0.1 || s.enemy.getLocation().getZ() > 4.6,
                    "and knocked aside");
            assertTrue(blocks(s, Material.POINTED_DRIPSTONE).size() > 8, "caltrops strewn along the way");
            List<BlockDisplay> caltrops = blocks(s, Material.POINTED_DRIPSTONE).stream()
                    .filter(d -> d.getLocation().getY() < FlatWorld.SURFACE + 0.01).toList();
            assertFalse(caltrops.isEmpty(), "on the ground");
            for (BlockDisplay caltrop : caltrops) {
                assertEquals(FlatWorld.SURFACE, caltrop.getLocation().getY(), 1.0E-3, "on the floor, not in it");
            }
            // enemy2 walks over the first caltrop (far behind the bowled-over enemy, so nobody stepped on it yet).
            Location spike = caltrops.stream().map(Entity::getLocation)
                    .min((a, b) -> Double.compare(a.getZ(), b.getZ())).orElseThrow();
            assertTrue(spike.getZ() < 3, "the first caltrop lies well behind the enemy: " + spike);
            s.place(s.enemy2, spike.getX(), spike.getZ(), 180);
            s.ticks(4);
            assertEquals(s.param("spike_damage"), s.damageBy(s.owner, s.enemy2), EPSILON, "a caltrop snapped");
            assertEquals(0.1 * (1 - s.param("spike_slow_amount") / 100), attribute(s.enemy2, Attribute.MOVEMENT_SPEED),
                    1.0E-6, "and slows");
            s.ticks(4);
            assertEquals(s.param("spike_damage"), s.damageBy(s.owner, s.enemy2), EPSILON, "each caltrop snaps once");

            s.ticks(s.paramTicks("ball_duration"));
            assertEquals(1.0, attribute(s.owner, Attribute.SCALE), EPSILON, "uncurled");
            assertEquals(speed, attribute(s.owner, Attribute.MOVEMENT_SPEED), EPSILON, "normal speed");
            assertEquals(0, attribute(s.owner, Attribute.KNOCKBACK_RESISTANCE), EPSILON);
            s.ticks(s.paramTicks("spike_duration") + 20);
            assertNoDisplays(s, "ball and caltrops");
            s.finishClean();
        }
    }

    @Test
    void hedgehogBallHitsTheSameEnemyOnlyEverySoOftenAndAFewTimes() {
        try (Scenario s = Scenario.builder("hedgehog").param("ball_hit_interval", 0.5).start()) {
            s.place(s.enemy2, 9.5, -12.5, 0);
            s.assertSucceeded(s.ultimate(), "가시 공");
            s.ticks(2);
            for (int i = 0; i < 60; i++) {
                // The enemy keeps standing right next to the ball.
                s.place(s.enemy, 0.5, 1.2, 180);
                s.ticks(1);
            }
            assertEquals(s.param("ball_max_hits") * s.param("ball_damage"), s.damageBy(s.owner, s.enemy), EPSILON,
                    "at most " + s.param("ball_max_hits") + " hits");
            s.ticks(s.paramTicks("ball_duration"));
            s.finishClean();
        }
    }
}
