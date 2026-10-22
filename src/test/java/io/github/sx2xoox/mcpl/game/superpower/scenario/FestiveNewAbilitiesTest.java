package io.github.sx2xoox.mcpl.game.superpower.scenario;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import org.bukkit.attribute.AttributeModifier;
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

/** 겨울·축제 계열 (new): 눈사람, 산타, 디제이, 스케이터. */
class FestiveNewAbilitiesTest {

    private static final double EPSILON = 1.0E-6;

    static Stream<String> abilities() {
        return Stream.of("snowman", "santa", "dj", "skater");
    }

    /** Every ultimate of the group works on a fresh instance, so the gambler may borrow all of them. */
    static Stream<String> standalone() {
        return abilities();
    }

    // ================================================================== helpers

    private static <T extends Entity> List<T> entities(Scenario s, Class<T> type) {
        return s.world.getEntities().stream().filter(type::isInstance).map(type::cast).toList();
    }

    private static List<BlockDisplay> blocks(Scenario s, Material material) {
        return entities(s, BlockDisplay.class).stream().filter(d -> d.getBlock().getMaterial() == material).toList();
    }

    private static List<ItemDisplay> items(Scenario s, Material material) {
        return entities(s, ItemDisplay.class).stream()
                .filter(d -> d.getItemStack() != null && d.getItemStack().getType() == material).toList();
    }

    private static void assertNoDisplays(Scenario s, String what) {
        assertTrue(entities(s, Display.class).isEmpty(), what + ": every display is gone: "
                + entities(s, Display.class));
    }

    private static void assertUnrotated(Entity display, String what) {
        assertEquals(0, display.getLocation().getYaw(), 1.0E-3, what + ": display yaw");
        assertEquals(0, display.getLocation().getPitch(), 1.0E-3, what + ": display pitch");
    }

    private static void assertAllDisplaysUnrotated(Scenario s, String what) {
        for (Display display : entities(s, Display.class)) {
            assertUnrotated(display, what + " " + display.getType());
        }
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

    private static double speed(Player player) {
        return player.getAttribute(Attribute.MOVEMENT_SPEED).getValue();
    }

    private static double baseSpeed(Player player) {
        return player.getAttribute(Attribute.MOVEMENT_SPEED).getBaseValue();
    }

    private static double jump(Player player) {
        return player.getAttribute(Attribute.JUMP_STRENGTH).getValue();
    }

    /** No ultimate gauge from damage or time, so gauge numbers in a test come from the ability alone. */
    private static Scenario.Builder noGaugeGain(Scenario.Builder builder) {
        return builder.configure(h -> {
            h.settings().set("ultimate.per-damage-dealt", 0);
            h.settings().set("ultimate.per-damage-taken", 0);
            h.settings().set("ultimate.per-second", 0);
        });
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

    /** {@code local} turned by the display's own transformation rotation. */
    private static Vector rotated(Display display, Vector3f local) {
        Vector3f turned = leftRotation(display).transform(new Vector3f(local));
        return new Vector(turned.x(), turned.y(), turned.z());
    }

    /** Where a block display's model is centered, relative to the display's location. */
    private static Vector modelCenter(BlockDisplay display) {
        Transformation t = display.getTransformation();
        Vector3f center = new Vector3f(t.getTranslation())
                .add(leftRotation(display).transform(new Vector3f(t.getScale()).mul(0.5f)));
        return new Vector(center.x(), center.y(), center.z());
    }

    /** The horizontal unit vector a Minecraft yaw faces (0 is +Z, 90 is -X). */
    private static Vector facing(float yaw) {
        double radians = Math.toRadians(yaw);
        return new Vector(-Math.sin(radians), 0, Math.cos(radians));
    }

    private static double horizontalDistance(Location a, Location b) {
        return Math.hypot(a.getX() - b.getX(), a.getZ() - b.getZ());
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
            s.assertSucceeded(s.ultimate(), id + " ultimate");
            s.ticks(300);
            assertTrue(s.damageBy(s.owner, s.enemy) + s.damageBy(s.owner, s.enemy2) > 0, id + " ultimate hurts");
            assertEquals(0, s.damageTaken(s.owner), EPSILON, id + " ultimate never hurts its owner: " + s.hits());
            s.assertNoProblems();
            if (id.equals("santa")) {
                // By now the next gift is ready and waits over Santa's head for someone to get hurt.
                for (Display display : entities(s, Display.class)) {
                    assertTrue(display instanceof BlockDisplay && display.getLocation().getY()
                            > s.owner.getLocation().getY() + s.owner.getHeight(), "only the waiting gift is left");
                }
            } else {
                assertNoDisplays(s, id);
            }
            for (TestPlayer enemy : s.enemies()) {
                assertModifierFree(enemy, id);
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
            for (Attribute attribute : List.of(Attribute.MOVEMENT_SPEED, Attribute.JUMP_STRENGTH)) {
                for (AttributeModifier modifier : s.ally().getAttribute(attribute).getModifiers()) {
                    fail(id + " left " + modifier.getKey() + " on the ally");
                }
            }
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
            s.ticks(16);
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
        assertEquals(0, s.h.hub.tempBlocks().size(), id + ": temporary blocks");
        for (TestPlayer enemy : s.enemies()) {
            assertModifierFree(enemy, id);
        }
        assertModifierFree(s.owner, id);
        s.assertNoProblems();
        s.finishClean();
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
    void everyUltimateOfTheGroupIsBorrowable() {
        try (Scenario s = gambler(false)) {
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

    // ================================================================== 눈사람 snowman

    @Test
    void snowmanPacksLayersThatSlowThenFreezeTheVictimIntoASnowman() {
        try (Scenario s = noGaugeGain(Scenario.builder("snowman")).start()) {
            double base = baseSpeed(s.enemy);
            double slow = s.param("snow_slow") / 100;
            int cooldown = s.paramTicks("melee_stack_cooldown");
            s.melee(s.owner, s.enemy, 2);
            s.ticks(2);
            assertEquals(base * (1 - slow), speed(s.enemy), EPSILON, "one layer of snow");
            assertEquals(2, blocks(s, Material.SNOW_BLOCK).size(), "a mound of snow around the legs");

            s.melee(s.owner, s.enemy, 2);
            s.ticks(2);
            assertEquals(base * (1 - slow), speed(s.enemy), EPSILON, "a second punch so soon packs nothing");

            s.ticks(cooldown);
            s.melee(s.owner, s.enemy, 2);
            s.ticks(2);
            assertEquals(base * (1 - 2 * slow), speed(s.enemy), EPSILON, "two layers");
            assertFalse(s.enemy.hasPotionEffect(PotionEffectType.SLOWNESS), "a modifier, not a potion");

            s.clearHits();
            s.ticks(cooldown);
            s.melee(s.owner, s.enemy, 2);
            s.ticks(2);
            assertEquals(0, speed(s.enemy), EPSILON, "the third layer freezes it stiff");
            assertEquals(0, jump(s.enemy), EPSILON, "no jumping out of it");
            assertEquals(2 + s.param("freeze_damage"), s.damageBy(s.owner, s.enemy), EPSILON,
                    "the punch and the freeze");
            assertTrue(blocks(s, Material.SNOW_BLOCK).size() >= 3, "a snowman of snow balls");
            assertEquals(1, items(s, Material.CARROT).size(), "with a carrot nose");
            assertEquals(2, items(s, Material.COAL).size(), "and coal eyes");
            assertAllDisplaysUnrotated(s, "snowman");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);

            s.ticks(s.paramTicks("freeze_duration") + 2);
            assertEquals(base, speed(s.enemy), EPSILON, "thawed out");
            s.ticks(10);
            assertTrue(entities(s, Display.class).isEmpty(), "the snowman crumbled");

            // Immune for a while after a freeze: the snow does not stick.
            s.melee(s.owner, s.enemy, 2);
            s.ticks(2);
            assertEquals(base, speed(s.enemy), EPSILON, "freshly thawed enemies shed the snow");
            s.ticks(s.paramTicks("freeze_immunity"));
            s.melee(s.owner, s.enemy, 2);
            s.ticks(2);
            assertEquals(base * (1 - slow), speed(s.enemy), EPSILON, "the snow sticks again later");
            s.finishClean();
        }
    }

    @Test
    void snowmanLayersMeltAwayAfterAWhile() {
        try (Scenario s = Scenario.ffa("snowman")) {
            double base = baseSpeed(s.enemy);
            s.melee(s.owner, s.enemy, 2);
            s.ticks(2);
            assertTrue(speed(s.enemy) < base, "slowed");
            s.ticks(s.paramTicks("snow_duration") + 12);
            assertEquals(base, speed(s.enemy), EPSILON, "the snow melted");
            assertTrue(entities(s, Display.class).isEmpty(), "and the mound with it");
            s.finishClean();
        }
    }

    @Test
    void snowmanCarrotNosePointsTheWayTheFrozenVictimFaces() {
        try (Scenario s = Scenario.builder("snowman").param("snow_max", 1).start()) {
            for (float yaw : new float[] {90f, 180f}) {
                s.place(s.enemy, 0.5, 4.5, yaw);
                s.melee(s.owner, s.enemy, 1);
                s.ticks(6);
                List<ItemDisplay> noses = items(s, Material.CARROT);
                assertEquals(1, noses.size(), "a carrot nose (yaw " + yaw + ")");
                ItemDisplay nose = noses.getFirst();
                assertUnrotated(nose, "carrot");
                Vector tip = rotated(nose, new Vector3f(-1, -1, 0).normalize());
                assertTrue(tip.dot(facing(yaw)) > 0.95, "the carrot's tip points where the victim looks (yaw " + yaw
                        + "): " + tip);
                Vector nosePlace = nose.getLocation().toVector().subtract(s.enemy.getLocation().toVector()).setY(0);
                assertTrue(nosePlace.normalize().dot(facing(yaw)) > 0.95, "on the front of its face: " + nosePlace);
                for (BlockDisplay ball : blocks(s, Material.SNOW_BLOCK)) {
                    assertUnrotated(ball, "snow ball");
                }
                s.ticks(s.paramTicks("freeze_immunity") + 20);
            }
            s.finishClean();
        }
    }

    @Test
    void snowmanMoundNeverGivesAHiddenVictimAway() {
        try (Scenario s = Scenario.ffa("snowman")) {
            s.owner.hidePlayer(s.ctx().plugin(), s.enemy);
            s.melee(s.owner, s.enemy, 2);
            s.ticks(4);
            List<BlockDisplay> mound = blocks(s, Material.SNOW_BLOCK);
            assertFalse(mound.isEmpty(), "the snow is there");
            for (BlockDisplay piece : mound) {
                assertEquals(0f, piece.getViewRange(), 1.0E-6, "but invisible while the victim is hidden");
            }
            s.owner.showPlayer(s.ctx().plugin(), s.enemy);
            s.ticks(2);
            for (BlockDisplay piece : blocks(s, Material.SNOW_BLOCK)) {
                assertTrue(piece.getViewRange() > 0, "visible again with the victim");
            }
            s.finishClean();
        }
    }

    @Test
    void snowmanBarrageFollowsTheAimShotByShot() {
        // Slow throws, so the turn falls clearly between the second and the third snowball.
        try (Scenario s = noGaugeGain(Scenario.builder("snowman").param("ball_interval", 0.5)).start()) {
            s.place(s.enemy2, 4.5, 4.5, 180);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "눈덩이 연사");
            assertFalse(s.active().succeeded(), "on cooldown");
            int interval = s.paramTicks("ball_interval");
            // The first two snowballs fly at the aimed enemy, then the snowman turns to the other one.
            s.ticks(interval + interval / 2);
            s.aimAt(s.owner, s.enemy2);
            s.ticks(interval * 4 + 40);
            int count = (int) s.param("ball_count");
            double ball = s.param("ball_damage");
            long first = s.hitsBy(s.owner, s.enemy).stream().filter(hit -> Math.abs(hit.damage() - ball) < EPSILON)
                    .count();
            long second = s.hitsBy(s.owner, s.enemy2).stream().filter(hit -> Math.abs(hit.damage() - ball) < EPSILON)
                    .count();
            assertEquals(2, first, "two snowballs at the first enemy: " + s.hits());
            assertEquals(count - 2, second, "the rest follow the new aim: " + s.hits());
            assertTrue(s.hitsBy(s.owner, s.enemy2).stream()
                    .anyMatch(hit -> Math.abs(hit.damage() - s.param("freeze_damage")) < EPSILON),
                    "three snowballs make a snowman");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.ticks(s.paramTicks("freeze_immunity") + s.paramTicks("snow_duration"));
            assertNoDisplays(s, "snowballs, mounds and snowmen");
            s.finishClean();
        }
    }

    @Test
    void snowmanSnowballsSplatOnWalls() {
        try (Scenario s = Scenario.ffa("snowman")) {
            s.world.wallAlongX(-3, 3, 3, 4, Material.STONE);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "눈덩이 연사 into a wall");
            s.ticks(60);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "the wall takes the snow");
            assertNoDisplays(s, "snowballs");
            s.world.wallAlongX(-3, 3, 3, 4, Material.AIR);
            s.finishClean();
        }
    }

    @Test
    void snowmanGiantSnowballRollsUpEnemiesAndBurstsAtTheEnd() {
        try (Scenario s = noGaugeGain(Scenario.builder("snowman")).start()) {
            s.place(s.enemy2, 9.5, -6.5, 0);
            s.aimAt(s.owner, s.enemy);
            s.place(s.owner, 0.5, 0.5, 0);
            Location start = s.enemy.getLocation();
            s.assertSucceeded(s.ultimate(), "거대 눈덩이");
            s.ticks(3);
            assertEquals(3, blocks(s, Material.SNOW_BLOCK).size(), "the ball is being packed");
            assertAllDisplaysUnrotated(s, "giant snowball");
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "nothing during the wind-up");
            s.h.tickUntil(() -> s.damageBy(s.owner, s.enemy) > 0, 60, "the ball reaches the enemy");
            assertEquals(s.param("ult_pickup_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "rolled up");
            s.ticks(12);
            assertTrue(s.enemy.getLocation().getZ() > start.getZ() + 3, "carried along inside the ball: "
                    + s.enemy.getLocation().toVector());
            assertAllDisplaysUnrotated(s, "rolling snowball");
            s.ticks(60);
            assertEquals(s.param("ult_pickup_damage") + s.param("ult_burst_damage"), s.damageBy(s.owner, s.enemy),
                    EPSILON, "rolled up once, burst once: " + s.hits());
            assertTrue(speed(s.enemy) < baseSpeed(s.enemy), "covered in snow by the burst");
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON, "enemy2 was far from the lane");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.ticks(s.paramTicks("snow_duration") + 20);
            assertNoDisplays(s, "the burst ball");
            s.finishClean();
        }
    }

    @Test
    void snowmanGiantSnowballBurstsOnAWallWithoutPushingAnyoneThroughIt() {
        try (Scenario s = Scenario.ffa("snowman")) {
            s.world.wallAlongX(-4, 4, 9, 4, Material.STONE);
            s.place(s.enemy2, 9.5, -6.5, 0);
            s.assertSucceeded(s.ultimate(), "거대 눈덩이 into a wall");
            s.ticks(80);
            assertTrue(s.damageBy(s.owner, s.enemy) >= s.param("ult_pickup_damage") + s.param("ult_burst_damage")
                    - EPSILON, "rolled up and burst at the wall");
            assertTrue(s.enemy.getLocation().getZ() < 9, "still on this side of the wall: "
                    + s.enemy.getLocation().toVector());
            s.world.wallAlongX(-4, 4, 9, 4, Material.AIR);
            s.ticks(100);
            s.finishClean();
        }
    }

    @Test
    void snowmanGiantSnowballCanBeSidestepped() {
        try (Scenario s = Scenario.ffa("snowman")) {
            s.assertSucceeded(s.ultimate(), "거대 눈덩이");
            s.ticks(4);
            s.place(s.enemy, 6.5, 4.5, 180);
            s.place(s.enemy2, -6.5, 8.5, 180);
            s.ticks(80);
            assertEquals(0, s.damageTaken(s.enemy) + s.damageTaken(s.enemy2), EPSILON, "both stepped off the lane");
            s.finishClean();
        }
    }

    // ================================================================== 산타 santa

    @Test
    void santaGiftFliesToTheHurtSantaWithABlessing() {
        try (Scenario s = Scenario.ffa("santa")) {
            double max = s.maxHealth(s.owner);
            s.owner.setHealth(max - 8);
            s.ticks(s.paramTicks("gift_interval") - 10);
            assertEquals(max - 8, s.health(s.owner), EPSILON, "no gift before its time");
            s.ticks(40);
            assertEquals(max - 8 + s.param("gift_heal"), s.health(s.owner), EPSILON, "the gift heals");
            assertTrue(Stream.of(PotionEffectType.SPEED, PotionEffectType.REGENERATION, PotionEffectType.RESISTANCE)
                    .anyMatch(s.owner::hasPotionEffect), "and blesses");
            s.ticks(20);
            assertNoDisplays(s, "the opened gift");
            s.finishClean();
        }
    }

    @Test
    void santaKeepsTheGiftOverItsHeadUntilSomeoneIsHurt() {
        try (Scenario s = Scenario.ffa("santa")) {
            s.ticks(s.paramTicks("gift_interval") + 20);
            List<BlockDisplay> gift = entities(s, BlockDisplay.class);
            assertEquals(4, gift.size(), "a box, two ribbons and a bow over Santa's head");
            for (BlockDisplay part : gift) {
                assertUnrotated(part, "gift");
                assertTrue(part.getLocation().getY() > s.owner.getLocation().getY() + s.owner.getHeight(),
                        "above the head");
            }
            assertTrue(s.owner.getActivePotionEffects().isEmpty(), "nobody needed it yet");
            double max = s.maxHealth(s.owner);
            s.owner.setHealth(max - 6);
            s.ticks(30);
            assertEquals(max - 6 + s.param("gift_heal"), s.health(s.owner), EPSILON, "delivered once needed");
            s.finishClean();
        }
    }

    @Test
    void santaGiftGoesToTheWorstHurtAlly() {
        try (Scenario s = Scenario.teams("santa")) {
            double ownerMax = s.maxHealth(s.owner);
            double allyMax = s.maxHealth(s.ally());
            s.owner.setHealth(ownerMax * 0.8);
            s.ally().setHealth(allyMax * 0.4);
            s.ticks(s.paramTicks("gift_interval") + 30);
            assertEquals(allyMax * 0.4 + s.param("gift_heal"), s.health(s.ally()), EPSILON, "the ally needed it most");
            assertEquals(ownerMax * 0.8, s.health(s.owner), EPSILON, "Santa waits for the next one");
            s.finishClean();
        }
    }

    @Test
    void santaGiftFliesBackIntoTheSackWhenItsRecipientIsGone() {
        try (Scenario s = Scenario.teams("santa")) {
            s.ally().setHealth(s.maxHealth(s.ally()) * 0.5);
            double headroom = s.owner.getLocation().getY() + s.owner.getHeight();
            s.ticks(s.paramTicks("gift_interval") - 2);
            s.h.tickUntil(() -> entities(s, BlockDisplay.class).stream()
                    .anyMatch(part -> horizontalDistance(part.getLocation(), s.owner.getLocation()) > 0.3), 30,
                    "the gift flies off to the ally");
            s.session.eliminate(s.ally(), null);
            s.ticks(15);
            List<BlockDisplay> gift = entities(s, BlockDisplay.class);
            assertEquals(4, gift.size(), "the gift is back over Santa's head");
            for (BlockDisplay part : gift) {
                assertTrue(part.getLocation().getY() > headroom, "above the head: " + part.getLocation().toVector());
            }
            double max = s.maxHealth(s.owner);
            s.owner.setHealth(max - 5);
            s.ticks(30);
            assertEquals(max - 5 + s.param("gift_heal"), s.health(s.owner), EPSILON, "and goes to Santa instead");
            s.ticks(20);
            assertNoDisplays(s, "the opened gift");
            s.finishClean();
        }
    }

    @Test
    void santaPresentCoalHitsHarderOnTheNaughty() {
        try (Scenario s = Scenario.ffa("santa")) {
            s.place(s.enemy2, 1.7, 4.5, 180);
            s.melee(s.enemy, s.owner, 1);
            s.ticks(3);
            assertEquals(1, items(s, Material.COAL).size(), "the naughty one gets a lump of coal over its head");
            assertUnrotated(items(s, Material.COAL).getFirst(), "naughty coal");
            s.clearHits();
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "선물 상자");
            assertFalse(s.active().succeeded(), "on cooldown");
            s.ticks(3);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "still in the air");
            s.h.tickUntil(() -> s.damageTaken(s.enemy) > 0, 30, "the present lands");
            assertTrue(s.enemy.hasPotionEffect(PotionEffectType.BLINDNESS), "soot in the eyes");
            assertTrue(s.enemy2.hasPotionEffect(PotionEffectType.BLINDNESS), "soot in the eyes of enemy2");
            s.ticks(2);
            double coal = s.param("coal_damage");
            assertEquals(coal * (1 + s.param("naughty_bonus") / 100), s.damageBy(s.owner, s.enemy), EPSILON,
                    "naughty: more coal");
            assertEquals(coal, s.damageBy(s.owner, s.enemy2), EPSILON, "nice enough: plain coal");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.ticks(s.paramTicks("naughty_duration") + 10);
            assertNoDisplays(s, "present, coal lumps and the naughty mark");
            s.finishClean();
        }
    }

    @Test
    void santaPresentGivesGiftsToSantasSide() {
        try (Scenario s = Scenario.teams("santa")) {
            s.crossfireAlly();
            double allyMax = s.maxHealth(s.ally());
            s.ally().setHealth(allyMax - 10);
            s.aimAt(s.owner, s.enemy);
            Location allyStart = s.ally().getLocation();
            s.assertSucceeded(s.active(), "선물 상자");
            AbilityLifecycleTest.watchAlly(s, allyStart, 40);
            assertEquals(allyMax - 10 + s.param("throw_heal"), s.health(s.ally()), EPSILON, "the ally got a gift");
            assertTrue(s.damageBy(s.owner, s.enemy) > 0, "the enemy got coal");

            double max = s.maxHealth(s.owner);
            s.owner.setHealth(max - 10);
            s.readyActive();
            s.aimAtFloor(s.owner, 0.5, 2.0);
            s.assertSucceeded(s.active(), "선물 상자 at Santa's own feet");
            s.ticks(30);
            assertEquals(max - 10 + s.param("throw_heal"), s.health(s.owner), EPSILON, "a gift for Santa too");
            s.finishClean();
        }
    }

    @Test
    void santaPresentNeedsSomewhereToLand() {
        try (Scenario s = Scenario.ffa("santa")) {
            s.place(s.owner, 0.5, FlatWorld.SURFACE, 0.5, 0, -85);
            assertFalse(s.active().succeeded(), "thrown at the open sky");
            s.finishClean();
        }
    }

    @Test
    void santaTreeGrowsBindsEnemiesHealsAndBurstsItsStar() {
        try (Scenario s = noGaugeGain(Scenario.builder("santa")).start()) {
            double max = s.maxHealth(s.owner);
            s.owner.setHealth(max - 12);
            s.assertSucceeded(s.ultimate(), "크리스마스의 기적");
            s.ticks(4);
            assertFalse(blocks(s, Material.SPRUCE_LEAVES).isEmpty(), "the tree grows");
            assertEquals(1, blocks(s, Material.SPRUCE_LOG).size(), "on a trunk");
            assertTrue(entities(s, BlockDisplay.class).size() <= 24, "within the display budget");
            assertAllDisplaysUnrotated(s, "tree");
            BlockDisplay trunk = blocks(s, Material.SPRUCE_LOG).getFirst();
            assertEquals(2.5, trunk.getLocation().getZ(), 0.05, "in front of Santa");
            assertEquals(FlatWorld.SURFACE, trunk.getLocation().getY(), EPSILON, "standing on the floor");
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "nothing before it has grown");

            s.ticks(s.paramTicks("ult_bind_delay"));
            for (TestPlayer enemy : s.enemies()) {
                assertEquals(s.param("ult_bind_damage"), s.damageBy(s.owner, enemy), EPSILON, enemy.getName());
                assertEquals(0, speed(enemy), EPSILON, enemy.getName() + " is wrapped in string lights");
            }
            s.ticks(s.paramTicks("ult_bind") + 2);
            for (TestPlayer enemy : s.enemies()) {
                assertEquals(baseSpeed(enemy), speed(enemy), EPSILON, enemy.getName() + " is free again");
            }
            assertTrue(s.health(s.owner) > max - 12 + s.param("ult_heal") - EPSILON, "Santa is healed by the tree");
            assertTrue(s.owner.hasPotionEffect(PotionEffectType.SPEED), "and hastened");

            s.ticks(s.paramTicks("ult_duration"));
            for (TestPlayer enemy : s.enemies()) {
                assertEquals(s.param("ult_bind_damage") + s.param("ult_finale_damage"), s.damageBy(s.owner, enemy),
                        EPSILON, "bound once, then the star burst: " + enemy.getName());
            }
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.ticks(20);
            assertNoDisplays(s, "the tree");
            s.finishClean();
        }
    }

    @Test
    void santaTreeCanBeEscapedBeforeItBinds() {
        try (Scenario s = Scenario.ffa("santa")) {
            s.assertSucceeded(s.ultimate(), "크리스마스의 기적");
            s.ticks(3);
            s.place(s.enemy, 0.5, 14.5, 180);
            s.place(s.enemy2, 13.5, 2.5, 90);
            s.ticks(s.paramTicks("ult_duration") + 10);
            assertEquals(0, s.damageTaken(s.enemy) + s.damageTaken(s.enemy2), EPSILON, "they ran from the tree");
            s.finishClean();
        }
    }

    // ================================================================== 디제이 dj

    /** Ticks until the match clock is {@code phase} ticks into a beat. */
    private static void tickToPhase(Scenario s, long phase) {
        long beat = s.paramTicks("beat_interval");
        for (int i = 0; i <= beat && Math.floorMod(s.ctx().currentTick(), beat) != phase; i++) {
            s.ticks(1);
        }
        assertEquals(phase, Math.floorMod(s.ctx().currentTick(), beat), "on the wanted beat phase");
    }

    @Test
    void djPerfectDropHitsAtOnceHarderAndComesBackSooner() {
        try (Scenario s = noGaugeGain(Scenario.builder("dj")).start()) {
            s.place(s.enemy2, 9.5, -9.5, 0);
            tickToPhase(s, 0);
            s.assertSucceeded(s.active(), "베이스 드롭 on the beat");
            assertEquals(s.param("cooldown") - s.param("perfect_refund"), s.ctx().cooldownRemaining(s.owner), 0.06,
                    "a perfect drop comes back sooner");
            s.ticks(6);
            assertEquals(s.param("drop_damage") * (1 + s.param("perfect_bonus") / 100), s.damageBy(s.owner, s.enemy),
                    EPSILON, "perfect: boosted");
            List<TextDisplay> pops = entities(s, TextDisplay.class);
            assertEquals(1, pops.size(), "a judgement pops over the DJ");
            assertEquals(Display.Billboard.CENTER, pops.getFirst().getBillboard(), "facing every viewer");
            assertTrue(Scenario.plain(pops.getFirst().text()).contains("PERFECT"), "PERFECT!");
            assertAllDisplaysUnrotated(s, "drop");
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON, "out of reach");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.ticks(40);
            assertNoDisplays(s, "speakers, bars and the pop");
            s.finishClean();
        }
    }

    @Test
    void djGreatDropIsBoostedALittle() {
        try (Scenario s = Scenario.ffa("dj")) {
            tickToPhase(s, s.paramTicks("good_window"));
            s.assertSucceeded(s.active(), "베이스 드롭 slightly late");
            assertEquals(s.param("cooldown"), s.ctx().cooldownRemaining(s.owner), 0.06, "no refund for a great");
            s.ticks(6);
            assertEquals(s.param("drop_damage") * (1 + s.param("good_bonus") / 100), s.damageBy(s.owner, s.enemy),
                    EPSILON, "great: a little boost");
            s.ticks(40);
            s.finishClean();
        }
    }

    @Test
    void djMissedDropWaitsForTheNextBeatAndGoesOffPlain() {
        try (Scenario s = Scenario.ffa("dj")) {
            long beat = s.paramTicks("beat_interval");
            tickToPhase(s, beat / 2);
            s.assertSucceeded(s.active(), "베이스 드롭 off the beat");
            assertEquals(s.param("cooldown"), s.ctx().cooldownRemaining(s.owner), 0.06, "full cooldown");
            s.ticks((int) (beat - beat / 2) - 1);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "the bass waits for the beat");
            s.ticks(8);
            assertEquals(s.param("drop_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "and drops plain");
            s.ticks(40);
            s.finishClean();
        }
    }

    @Test
    void djDropDoesNotReachThroughWalls() {
        try (Scenario s = Scenario.ffa("dj")) {
            s.world.wallAlongX(-3, 3, 3, 4, Material.STONE);
            tickToPhase(s, 0);
            s.assertSucceeded(s.active(), "베이스 드롭");
            s.ticks(12);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "the wall shields the enemy");
            s.world.wallAlongX(-3, 3, 3, 4, Material.AIR);
            s.ticks(30);
            s.finishClean();
        }
    }

    @Test
    void djSpeakersFaceTheWayTheDjLooks() {
        try (Scenario s = Scenario.ffa("dj")) {
            s.place(s.owner, 0.5, 0.5, 90); // facing west (-x)
            tickToPhase(s, 0);
            s.assertSucceeded(s.active(), "베이스 드롭");
            s.ticks(3);
            List<BlockDisplay> cones = blocks(s, Material.BLACK_CONCRETE);
            assertEquals(2, cones.size(), "two woofer cones");
            for (BlockDisplay cone : cones) {
                assertUnrotated(cone, "woofer");
                Vector center = modelCenter(cone);
                assertTrue(center.getX() < -0.25 && Math.abs(center.getZ()) < 0.05,
                        "the woofer is on the speaker's west face: " + center);
            }
            for (BlockDisplay speaker : blocks(s, Material.JUKEBOX)) {
                assertUnrotated(speaker, "speaker");
                double side = speaker.getLocation().getZ() - s.owner.getLocation().getZ();
                assertTrue(Math.abs(side) > 1, "beside the DJ, not in front: " + side);
            }
            s.ticks(40);
            s.finishClean();
        }
    }

    @Test
    void djMeleeOnTheBeatHitsHarder() {
        try (Scenario s = Scenario.ffa("dj")) {
            tickToPhase(s, 0);
            s.melee(s.owner, s.enemy, 4);
            assertEquals(4 * (1 + s.param("beat_melee") / 100), s.damageBy(s.owner, s.enemy), EPSILON, "on the beat");
            s.clearHits();
            tickToPhase(s, s.paramTicks("beat_interval") / 2);
            s.melee(s.owner, s.enemy, 4);
            assertEquals(4, s.damageBy(s.owner, s.enemy), EPSILON, "off the beat");
            s.finishClean();
        }
    }

    @Test
    void djPartyMakesEnemiesDanceToEveryBeatAndHurtsOnTheDrop() {
        try (Scenario s = noGaugeGain(Scenario.builder("dj")).start()) {
            Location before = s.enemy.getLocation();
            s.assertSucceeded(s.ultimate(), "파티 타임");
            assertFalse(s.match.useUltimate(s.owner).succeeded(), "the gauge is spent");
            s.ticks(3);
            assertTrue(blocks(s, Material.SEA_LANTERN).size() == 1, "a mirror ball");
            assertEquals(4, blocks(s, Material.JUKEBOX).size(), "four speakers");
            assertTrue(entities(s, Display.class).size() <= 24, "within the display budget");
            assertAllDisplaysUnrotated(s, "party");
            long beat = s.paramTicks("beat_interval");
            s.h.tickUntil(() -> s.damageBy(s.owner, s.enemy) > 0, (int) beat + 2, "the first beat");
            assertEquals(s.param("party_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "a step");
            s.ticks(2);
            assertTrue(horizontalDistance(before, s.enemy.getLocation()) > 0.2, "made to dance");
            // Three more beats: two steps and the drop of the bar.
            s.ticks((int) (3 * beat) - 2);
            double bar = 3 * s.param("party_damage") + s.param("party_drop_damage");
            assertEquals(bar, s.damageBy(s.owner, s.enemy), EPSILON, "three steps and a drop: "
                    + s.hitsBy(s.owner, s.enemy));
            assertTrue(s.damageBy(s.owner, s.enemy2) > 0, "enemy2 dances too");

            // Every skill is a perfect at the party, even far off the beat.
            tickToPhase(s, beat / 2);
            s.clearHits();
            s.assertSucceeded(s.active(), "베이스 드롭 at the party");
            assertEquals(s.param("cooldown") - s.param("perfect_refund"), s.ctx().cooldownRemaining(s.owner), 0.06,
                    "a perfect off the beat");
            s.ticks(4);
            double drop = s.param("drop_damage") * (1 + s.param("perfect_bonus") / 100);
            assertTrue(s.hitsBy(s.owner, s.enemy).stream().anyMatch(hit -> Math.abs(hit.damage() - drop) < EPSILON),
                    "the boosted drop went off at once: " + s.hitsBy(s.owner, s.enemy));

            s.ticks(s.paramTicks("party_duration"));
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.ticks(30);
            assertNoDisplays(s, "the party is over");

            tickToPhase(s, beat / 2);
            s.readyActive();
            s.assertSucceeded(s.active(), "베이스 드롭 after the party");
            assertEquals(s.param("cooldown"), s.ctx().cooldownRemaining(s.owner), 0.06, "back to judging the beat");
            s.ticks(20);
            s.finishClean();
        }
    }

    @Test
    void djPartyHastensTheCrewOnTheFloor() {
        try (Scenario s = Scenario.teams("dj")) {
            s.crossfireAlly();
            s.assertSucceeded(s.ultimate(), "파티 타임");
            s.ticks(s.paramTicks("beat_interval") + 2);
            PotionEffect haste = s.ally().getPotionEffect(PotionEffectType.SPEED);
            assertNotNull(haste, "the ally dances faster");
            assertEquals(1, haste.getAmplifier(), "신속 II");
            assertNotNull(s.owner.getPotionEffect(PotionEffectType.SPEED), "the DJ too");
            s.ticks(s.paramTicks("party_duration") + 20);
            s.finishClean();
        }
    }

    // ================================================================== 스케이터 skater

    @Test
    void skaterGlideLaysALaneOfIceAndBumpsEnemiesAside() {
        try (Scenario s = noGaugeGain(Scenario.builder("skater")).start()) {
            s.place(s.enemy2, 9.5, -6.5, 0);
            Location start = s.owner.getLocation();
            s.assertSucceeded(s.active(), "빙판 질주");
            assertFalse(s.active().succeeded(), "on cooldown");
            s.ticks(25);
            double travelled = s.owner.getLocation().getZ() - start.getZ();
            assertTrue(travelled > s.param("glide_distance") - 1.5 && travelled < s.param("glide_distance") + 1.5,
                    "glided about " + s.param("glide_distance") + " blocks: " + travelled);
            assertEquals(s.param("glide_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "bumped once");
            assertTrue(Math.abs(s.enemy.getLocation().getX() - 0.5) > 0.5, "thrown aside: "
                    + s.enemy.getLocation().toVector());
            List<BlockDisplay> sheets = blocks(s, Material.PACKED_ICE);
            assertTrue(sheets.size() >= 5, "a lane of ice sheets: " + sheets.size());
            for (BlockDisplay sheet : sheets) {
                assertUnrotated(sheet, "ice sheet");
                Vector along = rotated(sheet, new Vector3f(0, 0, 1));
                assertTrue(Math.abs(along.getZ()) > 0.95, "laid along the glide (+z): " + along);
                Transformation t = sheet.getTransformation();
                assertTrue(sheet.getLocation().getY() + t.getTranslation().y() >= FlatWorld.SURFACE - EPSILON,
                        "on the floor, not in it");
                assertTrue(t.getScale().y() < 0.1f, "a thin sheet");
            }
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON);
            s.ticks(s.paramTicks("ice_duration") + 20);
            assertNoDisplays(s, "the melted lane");
            s.finishClean();
        }
    }

    @Test
    void skaterGlideBendsTowardsTheView() {
        try (Scenario s = Scenario.ffa("skater")) {
            s.place(s.enemy, 9.5, -6.5, 0);
            s.place(s.enemy2, 12.5, -9.5, 0);
            s.assertSucceeded(s.active(), "빙판 질주");
            s.ticks(2);
            Location turned = s.owner.getLocation();
            turned.setYaw(90); // looks west (-x)
            s.owner.teleport(turned);
            s.ticks(25);
            assertTrue(s.owner.getLocation().getX() < -2, "curved towards the view: "
                    + s.owner.getLocation().toVector());
            s.ticks(s.paramTicks("ice_duration") + 20);
            s.finishClean();
        }
    }

    @Test
    void skaterGlideStopsAtAWall() {
        try (Scenario s = Scenario.ffa("skater")) {
            s.place(s.enemy, 9.5, -6.5, 0);
            s.world.wallAlongX(-3, 3, 6, 3, Material.STONE);
            s.assertSucceeded(s.active(), "빙판 질주 at a wall");
            s.ticks(30);
            assertTrue(s.owner.getLocation().getZ() < 6 - 0.29, "stopped before the wall: "
                    + s.owner.getLocation().toVector());
            assertTrue(Spots.isFree(s.world, s.owner.getLocation(), s.owner.getWidth(), s.owner.getHeight()),
                    "not stuck in it");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.world.wallAlongX(-3, 3, 6, 3, Material.AIR);
            s.ticks(s.paramTicks("ice_duration") + 20);
            s.finishClean();
        }
    }

    @Test
    void skaterSkatesFasterOnItsIce() {
        try (Scenario s = Scenario.ffa("skater")) {
            s.place(s.enemy, 9.5, -6.5, 0);
            double base = baseSpeed(s.owner);
            s.assertSucceeded(s.active(), "빙판 질주");
            s.ticks(25);
            assertEquals(base * (1 + s.param("ice_speed") / 100), speed(s.owner), EPSILON, "on the ice");
            s.place(s.owner, 8.5, 0.5, 0);
            s.ticks(4);
            assertEquals(base, speed(s.owner), EPSILON, "off the ice");
            s.ticks(s.paramTicks("ice_duration") + 20);
            s.finishClean();
        }
    }

    @Test
    void skaterIceMakesMovingEnemiesSlipButNotStandingOnes() {
        try (Scenario s = Scenario.ffa("skater")) {
            s.place(s.enemy, 9.5, -6.5, 0);
            s.place(s.enemy2, 12.5, -9.5, 0);
            s.assertSucceeded(s.active(), "빙판 질주");
            s.ticks(25);
            // One enemy stands still on the ice, the other walks across it.
            s.place(s.enemy, 0.5, 9.5, 180);
            s.place(s.enemy2, 0.0, 6.0, 270);
            s.ticks(10);
            assertEquals(baseSpeed(s.enemy2), speed(s.enemy2), EPSILON, "standing still: no slip");
            // A step east, as a walking player's position comes in.
            Location walked = s.enemy2.getLocation().add(0.2, 0, 0);
            s.enemy2.teleport(walked);
            s.ticks(6);
            assertTrue(s.enemy2.getLocation().getX() > walked.getX() + 0.8, "slid on along its way: "
                    + s.enemy2.getLocation().toVector());
            assertTrue(Math.abs(s.enemy2.getLocation().getZ() - 6.0) < 0.05, "straight on, not sideways");
            assertTrue(speed(s.enemy2) < baseSpeed(s.enemy2), "staggering");
            assertEquals(0, jump(s.enemy2), EPSILON, "no footing to jump");
            assertTrue(Scenario.plain(entities(s, TextDisplay.class).getFirst().text()).contains("미끄덩"),
                    "a slip pop");
            assertEquals(baseSpeed(s.enemy), speed(s.enemy), EPSILON, "the one standing still keeps its footing");
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON, "slipping does not hurt");
            s.ticks(s.paramTicks("ice_duration") + 20);
            assertNoDisplays(s, "melted");
            s.finishClean();
        }
    }

    @Test
    void skaterTripleAxelLandsExactlyOnTheFloorInThreeWavesWithoutFallDamage() {
        try (Scenario s = noGaugeGain(Scenario.builder("skater")).start()) {
            double distance = s.param("axel_distance");
            s.place(s.enemy, 0.5, 0.5 + distance, 180);
            s.place(s.enemy2, 0.5 + s.param("ring_third") - 0.6, 0.5 + distance, 270);
            s.assertSucceeded(s.ultimate(), "트리플 악셀");
            s.ticks(3);
            assertTrue(s.owner.getLocation().getY() > FlatWorld.SURFACE + 0.3, "up in the air");
            assertAllDisplaysUnrotated(s, "orbiting shards");
            s.tickUntilGrounded(s.owner, 80);
            Location feet = s.owner.getLocation();
            assertEquals(FlatWorld.SURFACE, feet.getY(), EPSILON, "landed exactly on the floor");
            assertTrue(Spots.isFree(s.world, feet, s.owner.getWidth(), s.owner.getHeight()), "and not in it");
            assertEquals(distance, feet.getZ() - 0.5, 1.5, "about " + distance + " blocks ahead");
            s.ticks(14);
            assertEquals(3 * s.param("ring_damage"), s.damageBy(s.owner, s.enemy), EPSILON,
                    "all three waves at the landing spot: " + s.hitsBy(s.owner, s.enemy));
            assertEquals(s.param("ring_damage"), s.damageBy(s.owner, s.enemy2), EPSILON, "only the last wave out there");
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "no fall damage");
            List<BlockDisplay> rink = blocks(s, Material.PACKED_ICE).stream()
                    .filter(sheet -> sheet.getTransformation().getScale().x() > 5).toList();
            assertEquals(3, rink.size(), "a twelve-sided rink of three sheets");
            for (BlockDisplay sheet : rink) {
                assertUnrotated(sheet, "rink sheet");
            }
            assertTrue(entities(s, TextDisplay.class).stream().anyMatch(text -> Scenario.plain(text.text())
                    .contains("10.0")), "the judges' scores");
            s.ticks(s.paramTicks("rink_duration") + 30);
            assertNoDisplays(s, "the melted rink");
            assertModifierFree(s.owner, "no fall guard left");
            s.finishClean();
        }
    }

    @Test
    void skaterTripleAxelLeapsFreshWithoutAStaleVelocity() {
        try (Scenario s = Scenario.ffa("skater")) {
            s.ticks(2);
            s.world.physics().setEnabled(false);
            // The server's velocity of a player is what it last sent (an old knockback, here), not its motion.
            s.owner.setVelocity(new Vector(1.2, 0, -0.9));
            s.assertSucceeded(s.ultimate(), "트리플 악셀");
            Vector kick = s.owner.getVelocity();
            assertEquals(0, kick.getX(), EPSILON, "no stale sideways push: " + kick);
            assertTrue(kick.getZ() > 0, "along the facing: " + kick);
            assertTrue(kick.getY() > 0, "up: " + kick);
            s.world.physics().setEnabled(true);
            s.tickUntilGrounded(s.owner, 80);
            s.ticks(s.paramTicks("rink_duration") + 30);
            s.finishClean();
        }
    }

    @Test
    void skaterTripleAxelStaysInsideAWall() {
        try (Scenario s = Scenario.ffa("skater")) {
            s.world.wallAlongX(-3, 3, 4, 6, Material.STONE);
            s.place(s.enemy, 9.5, -6.5, 0);
            s.place(s.enemy2, 12.5, -9.5, 0);
            s.assertSucceeded(s.ultimate(), "트리플 악셀 at a wall");
            s.tickUntilGrounded(s.owner, 80);
            assertTrue(s.owner.getLocation().getZ() < 4, "did not jump through the wall: "
                    + s.owner.getLocation().toVector());
            assertEquals(FlatWorld.SURFACE, s.owner.getLocation().getY(), EPSILON, "on the floor");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.world.wallAlongX(-3, 3, 4, 6, Material.AIR);
            s.ticks(s.paramTicks("rink_duration") + 30);
            s.finishClean();
        }
    }
}
