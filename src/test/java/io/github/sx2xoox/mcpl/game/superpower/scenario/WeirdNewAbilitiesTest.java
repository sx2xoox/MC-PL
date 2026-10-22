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
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** 정신·기묘 계열 (new): 최면술사, 인형술사, 외계인, 좀비. */
class WeirdNewAbilitiesTest {

    private static final double EPSILON = 1.0E-6;
    private static final double BASE_SPEED = 0.1;

    static Stream<String> abilities() {
        return Stream.of("hypnotist", "puppeteer", "alien", "zombie");
    }

    /** The ultimates the gambler may borrow (the zombie outbreak is built on its infection). */
    static Stream<String> standalone() {
        return Stream.of("hypnotist", "puppeteer", "alien");
    }

    private static <T extends Entity> List<T> entities(Scenario s, Class<T> type) {
        return s.world.getEntities().stream().filter(type::isInstance).map(type::cast).toList();
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

    private static double speed(Player player) {
        return player.getAttribute(Attribute.MOVEMENT_SPEED).getValue();
    }

    /**
     * The left rotation a display was given (MockBukkit may hand back the conjugate as both rotations; these abilities
     * always set an identity right rotation, so a non-identity right rotation means the conjugation happened).
     */
    private static Quaternionf rotationOf(Display display) {
        Transformation t = display.getTransformation();
        Quaternionf left = t.getLeftRotation();
        return t.getRightRotation().equals(new Quaternionf(), 1.0E-5f) ? left : left.conjugate();
    }

    private static Vector3f rotated(Display display, Vector3f local) {
        return rotationOf(display).transform(new Vector3f(local));
    }

    private static void assertUnrotated(Entity display, String what) {
        assertEquals(0, display.getLocation().getYaw(), 1.0E-3, what + ": display yaw");
        assertEquals(0, display.getLocation().getPitch(), 1.0E-3, what + ": display pitch");
    }

    // ================================================================== every ability

    /** What the active does to the aimed enemy in the default layout, so the generic sweep can check it. */
    private static boolean activeLanded(Scenario s, String id) {
        return switch (id) {
            case "hypnotist" -> isAsleep(s.enemy);
            default -> s.damageBy(s.owner, s.enemy) > 0;
        };
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
            boolean landed = false;
            for (int i = 0; i < 100; i++) {
                s.ticks(1);
                landed |= activeLanded(s, id);
            }
            assertTrue(landed, id + " active lands on the aimed enemy");
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
            s.ticks(100);
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
            s.ticks(16);
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
            s.ticks(30);
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
            assertFalse(s.ctx().isSilenced(enemy), id + ": " + enemy.getName() + " is not silenced any more");
            assertFalse(enemy.isFlying(), id + ": " + enemy.getName() + " is not flying");
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

    @ParameterizedTest(name = "{0}")
    @MethodSource("standalone")
    void borrowedUltimateWorksOnAGambler(String id) {
        try (Scenario s = gambler(false)) {
            assertTrue(s.ctx().registry().get(id).info().ultimateStandalone(), id + " is borrowable");
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(borrow(s, id), "borrowed " + id);
            boolean worked = false;
            for (int i = 0; i < 300; i++) {
                s.ticks(1);
                worked |= s.damageBy(s.owner, s.enemy) + s.damageBy(s.owner, s.enemy2) > 0;
            }
            assertTrue(worked, "the borrowed " + id + " ultimate does its thing");
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "the borrowed " + id + " never hurts the gambler");
            s.ticks(60);
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
            for (TestPlayer target : s.enemies()) {
                assertModifierFree(target, id);
                assertFalse(s.ctx().isSilenced(target), id);
            }
            s.assertNoProblems();
            s.finishClean();
        }
    }

    // ================================================================== 최면술사 hypnotist

    /** Asleep: rooted (no walking, no jumping), harmless in melee and blind. */
    private static boolean isAsleep(Player player) {
        return player.getAttribute(Attribute.MOVEMENT_SPEED).getValue() < 1.0E-9
                && player.getAttribute(Attribute.JUMP_STRENGTH).getValue() < 1.0E-9
                && player.getAttribute(Attribute.ATTACK_DAMAGE).getValue() < 1.0E-9
                && player.hasPotionEffect(PotionEffectType.BLINDNESS);
    }

    private static boolean isAwake(Player player) {
        return player.getAttribute(Attribute.MOVEMENT_SPEED).getValue() > 1.0E-9
                && player.getAttribute(Attribute.JUMP_STRENGTH).getValue() > 1.0E-9
                && player.getAttribute(Attribute.ATTACK_DAMAGE).getValue() > 1.0E-9
                && !player.hasPotionEffect(PotionEffectType.BLINDNESS);
    }

    /** Uses the hypnosis on the aimed enemy and waits until the watch has swung. */
    private static void hypnotise(Scenario s) {
        s.aimAt(s.owner, s.enemy);
        s.assertSucceeded(s.active(), "최면");
        s.ticks(s.paramTicks("channel_time") + 2);
    }

    @Test
    void hypnotistDrowsinessStacksSlowsAndWearsOff() {
        try (Scenario s = Scenario.ffa("hypnotist")) {
            s.ticks(5);
            int max = (int) s.param("drowsy_max");
            for (int i = 1; i <= max + 1; i++) {
                s.melee(s.owner, s.enemy, 1);
                s.ticks(s.paramTicks("drowsy_interval") + 1);
                int stacks = Math.min(i, max);
                assertEquals(BASE_SPEED * (1 - stacks * s.param("drowsy_slow") / 100), speed(s.enemy), EPSILON,
                        "drowsiness slows per stack (hit " + i + ")");
            }
            List<TextDisplay> labels = entities(s, TextDisplay.class);
            assertEquals(1, labels.size(), "one Z label over the drowsy enemy");
            assertUnrotated(labels.getFirst(), "label");
            assertEquals(Display.Billboard.CENTER, labels.getFirst().getBillboard());

            s.melee(s.owner, s.enemy2, 1);
            s.melee(s.owner, s.enemy2, 1);
            assertEquals(BASE_SPEED * (1 - s.param("drowsy_slow") / 100), speed(s.enemy2), EPSILON,
                    "the same enemy gains at most one stack per " + s.param("drowsy_interval") + " s");

            s.ticks(s.paramTicks("drowsy_duration") + 10);
            assertEquals(BASE_SPEED, speed(s.enemy), EPSILON, "the drowsiness wore off");
            assertModifierFree(s.enemy, "drowsiness");
            assertTrue(entities(s, TextDisplay.class).isEmpty(), "the labels are gone");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.finishClean();
        }
    }

    @Test
    void hypnotistPutsTheAimedEnemyToSleepAfterSwingingTheWatch() {
        try (Scenario s = Scenario.ffa("hypnotist")) {
            s.place(s.enemy2, 9.5, -6.5, 0);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "최면");
            assertFalse(s.active().succeeded(), "on cooldown");
            s.ticks(2);
            List<ItemDisplay> watches = entities(s, ItemDisplay.class);
            assertEquals(1, watches.size(), "the pocket watch swings in front of the hypnotist");
            assertEquals(Material.CLOCK, watches.getFirst().getItemStack().getType());
            assertTrue(isAwake(s.enemy), "nothing happens while the watch swings");
            s.ticks(s.paramTicks("channel_time"));
            assertTrue(isAsleep(s.enemy), "the wave puts the aimed enemy to sleep");
            assertTrue(isAwake(s.enemy2), "enemy2 is far away behind the hypnotist");
            assertEquals(s.param("cooldown"), s.ctx().cooldownRemaining(s.owner) + s.param("channel_time"), 0.2,
                    "the full cooldown: somebody fell asleep");

            Location before = s.enemy.getLocation();
            s.ticks(s.paramTicks("sleep_duration") - 6);
            assertTrue(isAsleep(s.enemy), "still asleep");
            assertTrue(before.distance(s.enemy.getLocation()) < 1.0E-3, "a sleeper does not move");
            s.ticks(12);
            assertTrue(isAwake(s.enemy), "woke up after " + s.param("sleep_duration") + " s");
            assertModifierFree(s.enemy, "sleep");
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "the plain hypnosis does no damage");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.ticks(30);
            assertNoDisplays(s, "watch and Z letters");
            s.finishClean();
        }
    }

    @Test
    void hypnotistDrowsinessDeepensTheSleep() {
        try (Scenario s = Scenario.ffa("hypnotist")) {
            int max = (int) s.param("drowsy_max");
            for (int i = 0; i < max; i++) {
                s.melee(s.owner, s.enemy, 1);
                s.ticks(s.paramTicks("drowsy_interval") + 1);
            }
            s.healAll();
            hypnotise(s);
            assertTrue(isAsleep(s.enemy));
            long longer = s.paramTicks("sleep_duration") + max * s.paramTicks("drowsy_sleep");
            s.ticks((int) longer - 6);
            assertTrue(isAsleep(s.enemy), "sleeps " + max + " × " + s.param("drowsy_sleep") + " s longer");
            s.ticks(10);
            assertTrue(isAwake(s.enemy), "then wakes");
            s.ticks(5);
            assertEquals(BASE_SPEED, speed(s.enemy), EPSILON, "the drowsiness was used up by the sleep");
            s.finishClean();
        }
    }

    @Test
    void hypnotistSleepersWakeOnAnyDamageAndShrugOffTheNextHypnosisForAMoment() {
        try (Scenario s = Scenario.ffa("hypnotist")) {
            s.place(s.enemy2, 2.0, 5.0, 180);
            hypnotise(s);
            assertTrue(isAsleep(s.enemy));
            assertTrue(isAsleep(s.enemy2), "the enemy next to it in the cone too");
            s.enemy.setNoDamageTicks(0);
            s.enemy.damage(1, DamageSource.builder(DamageType.GENERIC).build()); // any damage at all
            s.ticks(1);
            assertTrue(isAwake(s.enemy), "any damage wakes the sleeper");
            assertTrue(isAsleep(s.enemy2), "the other sleeper sleeps on");

            s.readyActive();
            s.clearHits();
            hypnotise(s);
            assertTrue(isAwake(s.enemy), "a woken enemy shrugs the hypnosis off for "
                    + s.param("sleep_immunity") + " s");
            s.ticks(s.paramTicks("sleep_immunity"));
            s.readyActive();
            hypnotise(s);
            assertTrue(isAsleep(s.enemy), "and can be hypnotised again afterwards");
            s.finishClean();
        }
    }

    @Test
    void hypnotistWakingBlowHitsHarder() {
        try (Scenario s = Scenario.ffa("hypnotist")) {
            hypnotise(s);
            assertTrue(isAsleep(s.enemy));
            s.clearHits();
            s.melee(s.owner, s.enemy, 2);
            assertEquals(2 + s.param("wake_bonus"), s.damageBy(s.owner, s.enemy), EPSILON, "the rude awakening");
            s.ticks(1);
            assertTrue(isAwake(s.enemy));
            s.clearHits();
            s.melee(s.owner, s.enemy, 2);
            assertEquals(2, s.damageBy(s.owner, s.enemy), EPSILON, "only the blow that wakes it");
            s.finishClean();
        }
    }

    @Test
    void hypnotistIsInterruptedByDamageWhileSwinging() {
        try (Scenario s = Scenario.ffa("hypnotist")) {
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "최면");
            s.ticks(3);
            s.melee(s.enemy2, s.owner, 1);
            s.ticks(s.paramTicks("channel_time") + 2);
            assertTrue(isAwake(s.enemy), "the wave never came");
            double left = s.ctx().cooldownRemaining(s.owner);
            assertTrue(left < s.param("cooldown") * 0.6 && left > 0, "half the cooldown is refunded: " + left);
            s.ticks(10);
            assertNoDisplays(s, "the dropped watch");
            s.finishClean();
        }
    }

    @Test
    void hypnotistWaveMissesOutsideTheConeAndBehindWalls() {
        try (Scenario s = Scenario.ffa("hypnotist")) {
            s.place(s.enemy, 0.5, -4.5, 0); // behind the hypnotist
            s.place(s.enemy2, 6.5, 0.5, 90); // to its side
            s.place(s.owner, 0.5, 0.5, 0);
            s.assertSucceeded(s.active(), "최면 at nobody");
            s.ticks(s.paramTicks("channel_time") + 2);
            assertTrue(isAwake(s.enemy) && isAwake(s.enemy2), "nobody in the cone");
            double left = s.ctx().cooldownRemaining(s.owner);
            assertTrue(left < s.param("cooldown") * 0.6, "a miss refunds half the cooldown: " + left);

            s.readyActive();
            s.layout();
            s.world.wallAlongX(-3, 3, 2, 3, Material.STONE);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "최면 into a wall");
            s.ticks(s.paramTicks("channel_time") + 2);
            assertTrue(isAwake(s.enemy), "the wall hides the watch");
            s.world.wallAlongX(-3, 3, 2, 3, Material.AIR);
            s.finishClean();
        }
    }

    @Test
    void hypnotistNeverHypnotisesOrMarksAHiddenEnemy() {
        try (Scenario s = Scenario.ffa("hypnotist")) {
            s.owner.hidePlayer(s.ctx().plugin(), s.enemy);
            s.melee(s.owner, s.enemy, 1);
            s.ticks(3);
            for (TextDisplay label : entities(s, TextDisplay.class)) {
                assertEquals(0f, label.getViewRange(), 1.0E-6f, "no Z label shows where a hidden enemy is");
            }
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "최면");
            s.ticks(s.paramTicks("channel_time") + 2);
            assertTrue(isAwake(s.enemy), "a hidden enemy is not hypnotised");
            s.owner.showPlayer(s.ctx().plugin(), s.enemy);
            s.ticks(3);
            for (TextDisplay label : entities(s, TextDisplay.class)) {
                assertTrue(label.getViewRange() > 0, "the label shows again");
            }
            s.finishClean();
        }
    }

    @Test
    void hypnotistPocketWatchHangsInFrontOfAHypnotistFacingAnyWay() {
        try (Scenario s = Scenario.ffa("hypnotist")) {
            s.place(s.owner, 0.5, 0.5, 90); // facing west (-x)
            s.place(s.enemy, -4.5, 0.5, 270);
            s.assertSucceeded(s.active(), "최면");
            s.ticks(2);
            ItemDisplay watch = entities(s, ItemDisplay.class).getFirst();
            assertUnrotated(watch, "watch");
            Location at = watch.getLocation();
            assertTrue(at.getX() < s.owner.getLocation().getX() - 0.5, "in front of a hypnotist facing west: " + at);
            Vector3f face = rotated(watch, new Vector3f(0, 0, 1));
            assertTrue(face.x() < -0.9f, "the watch face looks west, at the enemy: " + face);
            Vector3f translation = watch.getTransformation().getTranslation();
            assertTrue(translation.y() < -0.3f, "the watch hangs below its chain: " + translation);
            for (BlockDisplay chain : entities(s, BlockDisplay.class)) {
                assertUnrotated(chain, "chain");
            }
            s.ticks(s.paramTicks("channel_time") + 2);
            assertTrue(isAsleep(s.enemy), "the enemy to the west falls asleep");
            s.finishClean();
        }
    }

    @Test
    void hypnotistMassHypnosisDreamsUntilTheNightmare() {
        try (Scenario s = Scenario.ffa("hypnotist")) {
            s.assertSucceeded(s.ultimate(), "집단 최면");
            s.ticks(3);
            List<ItemDisplay> watch = entities(s, ItemDisplay.class);
            assertEquals(1, watch.size(), "the giant watch hangs over the hypnotist");
            assertTrue(watch.getFirst().getLocation().getY() > s.owner.getEyeLocation().getY() + 1, "overhead");
            assertTrue(isAwake(s.enemy) && isAwake(s.enemy2), "nobody sleeps before the chime");
            s.ticks(s.paramTicks("ult_windup"));
            for (TestPlayer enemy : s.enemies()) {
                assertTrue(isAsleep(enemy), enemy.getName() + " dreams");
            }
            s.ticks(4);
            assertEquals(2, entities(s, BlockDisplay.class).stream()
                    .filter(d -> d.getBlock().getMaterial() == Material.WHITE_STAINED_GLASS).count(),
                    "a dream bubble over each sleeper");
            assertEquals(0, s.damageTaken(s.enemy) + s.damageTaken(s.enemy2), EPSILON, "sweet dreams");

            s.ticks(s.paramTicks("ult_sleep"));
            for (TestPlayer enemy : s.enemies()) {
                assertTrue(isAwake(enemy), enemy.getName() + " woke up");
                assertEquals(s.param("nightmare_damage"), s.damageBy(s.owner, enemy), EPSILON,
                        enemy.getName() + " wakes into the nightmare");
                assertTrue(enemy.hasPotionEffect(PotionEffectType.NAUSEA), "the nightmare lingers");
            }
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.ticks(30);
            assertTrue(entities(s, BlockDisplay.class).isEmpty() && entities(s, ItemDisplay.class).isEmpty(),
                    "the watch and the burst bubbles are gone");
            assertEquals(2, entities(s, TextDisplay.class).size(), "the nightmare left them drowsy (it is a hit)");
            s.ticks(s.paramTicks("drowsy_duration"));
            assertNoDisplays(s, "watch, bubbles, Z letters and labels");
            s.finishClean();
        }
    }

    @Test
    void hypnotistNightmareStrikesTheMomentADreamerIsWoken() {
        try (Scenario s = Scenario.ffa("hypnotist")) {
            s.assertSucceeded(s.ultimate(), "집단 최면");
            s.ticks(s.paramTicks("ult_windup") + 5);
            assertTrue(isAsleep(s.enemy));
            s.melee(s.enemy2, s.enemy, 1);
            s.ticks(2);
            assertTrue(isAwake(s.enemy), "woken by the hit");
            assertEquals(s.param("nightmare_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "straight into the nightmare");
            assertTrue(isAsleep(s.enemy2), "the other one dreams on");
            s.finishClean();
        }
    }

    @Test
    void hypnotistMassHypnosisCanBeEscapedOrHiddenFrom() {
        try (Scenario s = Scenario.ffa("hypnotist")) {
            s.assertSucceeded(s.ultimate(), "집단 최면");
            s.ticks(5);
            s.place(s.enemy2, 0.5, 14.5, 180); // ran out of reach
            s.world.wallAlongX(-3, 3, 2, 4, Material.STONE); // and the enemy ducks behind a wall
            s.ticks(s.paramTicks("ult_windup"));
            assertTrue(isAwake(s.enemy), "behind the wall the watch is not seen");
            assertTrue(isAwake(s.enemy2), "out of reach");
            s.world.wallAlongX(-3, 3, 2, 4, Material.AIR);
            s.ticks(80);
            assertEquals(0, s.damageTaken(s.enemy) + s.damageTaken(s.enemy2), EPSILON, "no nightmare without a dream");
            s.finishClean();
        }
    }

    @Test
    void hypnotistGiantWatchAndDreamBubblesAreUnrotated() {
        try (Scenario s = Scenario.ffa("hypnotist")) {
            s.place(s.owner, 0.5, 0.5, 135);
            s.assertSucceeded(s.ultimate(), "집단 최면");
            for (int i = 0; i < s.paramTicks("ult_windup") + 20; i++) {
                s.ticks(1);
                for (Display display : entities(s, Display.class)) {
                    assertUnrotated(display, "display " + display.getType());
                }
            }
            ItemDisplay watch = entities(s, ItemDisplay.class).stream()
                    .filter(d -> d.getItemStack().getType() == Material.CLOCK).findFirst().orElse(null);
            assertTrue(watch == null || watch.getTransformation().getTranslation().y() < 0,
                    "the giant watch hangs below its chain");
            s.ticks(s.paramTicks("ult_sleep") + 40);
            s.finishClean();
        }
    }

    @Test
    void hypnotistSleepDoesNotReplayAStaleVelocity() {
        try (Scenario s = Scenario.ffa("hypnotist")) {
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "최면");
            s.ticks(s.paramTicks("channel_time") - 1);
            // The server's copy of a player's velocity is whatever it last sent: here a stale leap.
            s.enemy.setVelocity(new org.bukkit.util.Vector(0.8, 0.9, 0));
            s.ticks(3);
            assertTrue(isAsleep(s.enemy), "asleep");
            s.ticks(10);
            assertEquals(64, s.enemy.getLocation().getY(), 1.0E-6, "the sleeper stays on the floor");
            assertEquals(0.5, s.enemy.getLocation().getX(), 0.9, "and is not flung by a stale velocity");
            s.ticks(60);
            s.finishClean();
        }
    }

    @Test
    void hypnotistMassHypnosisNeedsSomeoneInReach() {
        try (Scenario s = Scenario.builder("hypnotist").param("ult_radius", 2).start()) {
            assertFalse(s.ultimate().succeeded(), "nobody within 2 blocks");
            assertEquals(100, s.participant(s.owner).gauge(), EPSILON, "the gauge is kept");
            s.finishClean();
        }
    }

    @Test
    void hypnotistMassHypnosisOutlastsAnActiveSleep() {
        try (Scenario s = Scenario.ffa("hypnotist")) {
            hypnotise(s);
            assertTrue(isAsleep(s.enemy));
            s.assertSucceeded(s.ultimate(), "집단 최면 on a sleeper");
            s.ticks(s.paramTicks("ult_windup") + s.paramTicks("ult_sleep") - 10);
            assertTrue(isAsleep(s.enemy), "the dream carries on the sleep");
            assertNotNull(s.enemy.getPotionEffect(PotionEffectType.BLINDNESS));
            s.ticks(20);
            assertTrue(isAwake(s.enemy));
            assertEquals(s.param("nightmare_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "one nightmare");
            s.finishClean();
        }
    }

    // ================================================================== 인형술사 puppeteer

    /** Moves {@code player} by a step, keeping its view, as a walking client would be seen by the server. */
    private static void step(Player player, double dx, double dy, double dz) {
        player.teleport(player.getLocation().add(dx, dy, dz));
    }

    private static List<BlockDisplay> blocks(Scenario s, Material material) {
        return entities(s, BlockDisplay.class).stream().filter(d -> d.getBlock().getMaterial() == material).toList();
    }

    @Test
    void puppeteerThreadTugsAtEnemiesThatRunOff() {
        try (Scenario s = Scenario.ffa("puppeteer")) {
            s.melee(s.owner, s.enemy, 1);
            s.ticks(4);
            assertEquals(BASE_SPEED, speed(s.enemy), EPSILON, "a slack thread does nothing");
            s.place(s.enemy, 0.5, s.param("leash_range") + 3, 180);
            s.ticks(3);
            assertEquals(BASE_SPEED * (1 - s.param("leash_slow") / 100), speed(s.enemy), EPSILON,
                    "the thread pulls taut beyond " + s.param("leash_range") + " blocks");
            assertEquals(BASE_SPEED, speed(s.enemy2), EPSILON, "enemy2 has no thread");
            s.place(s.enemy, 0.5, 4.5, 180);
            s.ticks(3);
            assertEquals(BASE_SPEED, speed(s.enemy), EPSILON, "slack again when it comes back");
            s.place(s.enemy, 0.5, s.param("leash_range") + 3, 180);
            s.ticks(s.paramTicks("thread_duration"));
            assertEquals(BASE_SPEED, speed(s.enemy), EPSILON, "the thread wore off");
            assertModifierFree(s.enemy, "thread");
            s.finishClean();
        }
    }

    @Test
    void puppeteerPuppetCopiesEveryStepAndJump() {
        try (Scenario s = Scenario.ffa("puppeteer")) {
            s.place(s.enemy2, 9.5, -6.5, 0);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "꼭두각시");
            assertFalse(s.active().succeeded(), "on cooldown");
            assertEquals(s.param("attach_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "the strings hook in");
            s.ticks(2);
            assertEquals(0, speed(s.enemy), EPSILON, "the puppet cannot walk by itself");
            Location start = s.enemy.getLocation();
            for (int i = 0; i < 6; i++) {
                step(s.owner, 0.2, 0, 0);
                s.ticks(1);
            }
            Location moved = s.enemy.getLocation();
            assertEquals(start.getX() + 1.2, moved.getX(), 0.25, "the puppet walks the same way: " + moved);
            assertEquals(start.getZ(), moved.getZ(), 0.05, "and only that way");
            assertEquals(start.getY(), moved.getY(), 1.0E-6, "on the ground");

            step(s.owner, 0, 0.42, 0); // the puppeteer jumps
            s.ticks(2);
            assertTrue(s.enemy.getLocation().getY() > start.getY() + 0.3, "the puppet jumps along");
            s.ticks(12);
            assertEquals(start.getY(), s.enemy.getLocation().getY(), 1.0E-6, "and lands again");

            step(s.owner, 5, 0, 0); // a teleport is no step
            s.ticks(1);
            Location before = s.enemy.getLocation();
            s.ticks(1);
            assertTrue(before.distance(s.enemy.getLocation()) < 0.05, "a teleport is not copied");
            s.ticks(s.paramTicks("puppet_duration"));
            assertEquals(s.param("attach_damage") + s.param("snap_damage"), s.damageBy(s.owner, s.enemy), EPSILON,
                    "the strings snap at the end");
            assertEquals(BASE_SPEED, speed(s.enemy), EPSILON, "free again");
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON);
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.ticks(10);
            assertNoDisplays(s, "control crosses");
            s.finishClean();
        }
    }

    @Test
    void puppeteerWalksItsPuppetIntoWalls() {
        try (Scenario s = Scenario.builder("puppeteer").param("puppet_duration", 4).start()) {
            s.world.wallAlongX(2, 2, 4, 3, Material.STONE);
            s.world.wallAlongX(2, 2, 5, 3, Material.STONE);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "꼭두각시");
            s.clearHits();
            for (int i = 0; i < 60; i++) {
                step(s.owner, 0.2, 0, 0);
                s.ticks(1);
            }
            assertTrue(s.enemy.getLocation().getX() < 2 - 0.29, "the wall stops the puppet");
            assertEquals(s.param("bump_max") * s.param("bump_damage"), s.damageBy(s.owner, s.enemy), EPSILON,
                    "it slams into the wall, at most " + s.param("bump_max") + " times");
            s.world.wallAlongX(2, 2, 4, 3, Material.AIR);
            s.world.wallAlongX(2, 2, 5, 3, Material.AIR);
            s.ticks(40);
            s.finishClean();
        }
    }

    @Test
    void puppeteerFindsAThreadedEnemyWithoutAimingAndHoldsItLonger() {
        try (Scenario s = Scenario.ffa("puppeteer")) {
            s.place(s.enemy2, 9.5, -6.5, 0);
            s.melee(s.owner, s.enemy, 1);
            s.place(s.owner, 0.5, 0.5, 90); // looking away
            assertTrue(s.ctx().findTarget(s.owner, 20) == null, "nobody aimed at");
            s.clearHits();
            s.assertSucceeded(s.active(), "꼭두각시 on the threaded enemy");
            assertEquals(s.param("attach_damage"), s.damageBy(s.owner, s.enemy), EPSILON);
            s.ticks(s.paramTicks("puppet_duration") + 4);
            assertEquals(0, speed(s.enemy), EPSILON, "a threaded puppet dangles " + s.param("thread_bonus")
                    + " s longer");
            s.ticks(s.paramTicks("thread_bonus"));
            assertEquals(BASE_SPEED, speed(s.enemy), EPSILON, "then the strings snap");
            s.finishClean();
        }
    }

    @Test
    void puppeteerNeedsSomebodyToControl() {
        try (Scenario s = Scenario.ffa("puppeteer")) {
            s.place(s.owner, 0.5, 0.5, 90);
            assertFalse(s.active().succeeded(), "nobody aimed at and nobody threaded");
            assertEquals(0, s.ctx().cooldownRemaining(s.owner), EPSILON, "no cooldown spent");
            s.owner.hidePlayer(s.ctx().plugin(), s.enemy);
            s.place(s.owner, 0.5, 0.5, 0);
            s.aimAt(s.owner, s.enemy);
            assertFalse(s.active().succeeded(), "a hidden enemy cannot be aimed at");
            s.owner.showPlayer(s.ctx().plugin(), s.enemy);
            s.finishClean();
        }
    }

    @Test
    void puppeteerControlCrossesAreUnrotatedAndTurnWithThePuppeteer() {
        try (Scenario s = Scenario.ffa("puppeteer")) {
            s.place(s.owner, 0.5, 0.5, 90); // facing west (-x)
            s.place(s.enemy, -4.5, 0.5, 270);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "꼭두각시");
            s.place(s.owner, 0.5, 0.5, 90);
            s.ticks(3);
            List<BlockDisplay> bars = blocks(s, Material.OAK_PLANKS);
            assertEquals(4, bars.size(), "a cross over the puppet and one in the puppeteer's hand");
            int across = 0;
            for (BlockDisplay bar : bars) {
                assertUnrotated(bar, "cross bar");
                Vector3f scale = bar.getTransformation().getScale();
                Vector3f axis = rotated(bar, scale.x() > scale.z() ? new Vector3f(1, 0, 0) : new Vector3f(0, 0, 1));
                if (scale.x() > scale.z()) {
                    across++;
                    assertTrue(Math.abs(axis.z()) > 0.9f, "the long bar lies across a puppeteer facing west: " + axis);
                } else {
                    assertTrue(Math.abs(axis.x()) > 0.9f, "the short bar points west: " + axis);
                }
            }
            assertEquals(2, across, "two long bars");
            BlockDisplay overhead = bars.stream().max((a, b) -> Double.compare(a.getLocation().getY(),
                    b.getLocation().getY())).orElseThrow();
            assertTrue(overhead.getLocation().getY() > s.enemy.getEyeLocation().getY(), "the cross hangs overhead");
            s.ticks(s.paramTicks("puppet_duration") + 20);
            s.finishClean();
        }
    }

    /** Ticks of the show from the cast until the finale has struck. */
    private static int showLength(Scenario s) {
        return s.paramTicks("ult_windup") + 8 + s.paramTicks("show_duration") + 4;
    }

    /** How many claps a show has: one on every beat before the finale. */
    private static int claps(Scenario s) {
        int interval = s.paramTicks("clap_interval");
        int show = s.paramTicks("show_duration");
        int count = 0;
        for (int beat = 1; beat * interval - 1 < show - 1; beat++) {
            count++;
        }
        return count;
    }

    @Test
    void puppeteerShowHoistsClapsTogetherAndDashesDown() {
        try (Scenario s = Scenario.ffa("puppeteer")) {
            s.assertSucceeded(s.ultimate(), "인형극");
            s.ticks(2);
            assertEquals(2, blocks(s, Material.OAK_PLANKS).size(), "the giant cross descends");
            assertEquals(4, blocks(s, Material.RED_WOOL).size(), "with its red knobs");
            s.ticks(s.paramTicks("ult_windup") - 2);
            assertEquals(0, s.damageTaken(s.enemy) + s.damageTaken(s.enemy2), EPSILON, "the curtain is still up");
            s.ticks(12);
            for (TestPlayer enemy : s.enemies()) {
                assertTrue(enemy.getLocation().getY() > s.param("lift_height") * 0.6 + 64,
                        enemy.getName() + " hangs in the air: " + enemy.getLocation().getY());
                assertEquals(0, speed(enemy), EPSILON, "and cannot walk");
                assertTrue(enemy.hasPotionEffect(PotionEffectType.SLOW_FALLING), "a safety net while hung");
            }
            s.ticks(showLength(s));
            double expected = claps(s) * s.param("clap_damage") + s.param("finale_damage");
            assertTrue(claps(s) >= 2, "a few claps: " + claps(s));
            for (TestPlayer enemy : s.enemies()) {
                assertEquals(expected, s.damageBy(s.owner, enemy), EPSILON, enemy.getName() + " clapped and dashed");
                assertEquals(64, enemy.getLocation().getY(), 1.0E-6, "back on the floor, exactly on its surface");
                assertTrue(Spots.isFree(s.world, enemy.getLocation(), enemy.getWidth(), enemy.getHeight()),
                        "and not in it");
                assertFalse(enemy.hasPotionEffect(PotionEffectType.SLOW_FALLING), "the safety net is gone");
                assertEquals(BASE_SPEED, speed(enemy), EPSILON);
            }
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.ticks(20);
            assertTrue(blocks(s, Material.OAK_PLANKS).isEmpty() && blocks(s, Material.RED_WOOL).isEmpty(),
                    "the cross broke apart");
            s.finishClean();
        }
    }

    @Test
    void puppeteerNeverReplaysAStaleVelocity() {
        try (Scenario s = Scenario.ffa("puppeteer")) {
            s.aimAt(s.owner, s.enemy);
            // The server's copy of a player's velocity is whatever it last sent: here a stale leap.
            s.enemy.setVelocity(new org.bukkit.util.Vector(0, 1.2, 0.9));
            s.assertSucceeded(s.active(), "꼭두각시");
            s.ticks(3);
            assertEquals(64, s.enemy.getLocation().getY(), 1.0E-6, "the puppet stays on the floor");
            assertEquals(4.5, s.enemy.getLocation().getZ(), 0.05, "and only moves as the puppeteer does");
            s.ticks(s.paramTicks("puppet_duration") + 10);
            s.finishClean();
        }
    }

    @Test
    void puppeteerShowCrossIsUnrotatedAndHangsAcrossThePuppeteersView() {
        try (Scenario s = Scenario.ffa("puppeteer")) {
            s.place(s.owner, 0.5, 0.5, 90); // facing west (-x)
            s.place(s.enemy, -4.5, 0.5, 270);
            s.place(s.enemy2, -5.5, 2.5, 270);
            s.assertSucceeded(s.ultimate(), "인형극");
            for (int i = 0; i < s.paramTicks("ult_windup") + 14; i++) {
                s.ticks(1);
                for (Display display : entities(s, Display.class)) {
                    assertUnrotated(display, "display " + display.getType());
                }
            }
            BlockDisplay across = blocks(s, Material.OAK_PLANKS).stream()
                    .filter(bar -> bar.getTransformation().getScale().x() > bar.getTransformation().getScale().z())
                    .findFirst().orElseThrow();
            Vector3f axis = rotated(across, new Vector3f(1, 0, 0));
            assertTrue(Math.abs(axis.z()) > 0.85f, "the long bar lies across a puppeteer facing west: " + axis);
            assertTrue(across.getLocation().getY() > s.enemy.getEyeLocation().getY() + 1, "high over the dolls");
            assertTrue(across.getLocation().getX() < -2, "over the stage to the west: " + across.getLocation());
            s.ticks(showLength(s) + 10);
            s.finishClean();
        }
    }

    @Test
    void puppeteerShowSlamsALoneDollIntoTheFloor() {
        try (Scenario s = Scenario.ffa("puppeteer")) {
            s.place(s.enemy2, 0.5, -15.5, 0);
            s.assertSucceeded(s.ultimate(), "인형극 for one");
            s.ticks(showLength(s) + 4);
            assertEquals(claps(s) * s.param("clap_damage") + s.param("finale_damage"), s.damageBy(s.owner, s.enemy),
                    EPSILON, "a lone doll is slammed on every beat");
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON, "enemy2 was out of reach");
            s.finishClean();
        }
    }

    @Test
    void puppeteerShowSwingsTheDollsWhereThePuppeteerLooks() {
        try (Scenario s = Scenario.builder("puppeteer").param("show_duration", 3).start()) {
            s.place(s.enemy2, 0.5, -15.5, 0);
            s.assertSucceeded(s.ultimate(), "인형극");
            s.ticks(s.paramTicks("ult_windup") + 10);
            double startX = s.enemy.getLocation().getX();
            s.place(s.owner, 0.5, 0.5, 270); // turns to look east (+x)
            s.ticks(30);
            assertTrue(s.enemy.getLocation().getX() > startX + 2, "the doll swings round to the east: "
                    + s.enemy.getLocation());
            s.ticks(60);
            s.finishClean();
        }
    }

    @Test
    void puppeteerShowCanBeEscapedDuringTheWindup() {
        try (Scenario s = Scenario.ffa("puppeteer")) {
            s.assertSucceeded(s.ultimate(), "인형극");
            s.ticks(2);
            s.place(s.enemy2, 0.5, -15.5, 0);
            s.ticks(showLength(s) + 4);
            assertTrue(s.damageBy(s.owner, s.enemy) > 0, "the enemy that stayed was hung");
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON, "the one that ran got away");
            s.finishClean();
        }
    }

    @Test
    void puppeteerShowReachesFurtherForThreadedEnemiesAndNeedsAnAudience() {
        try (Scenario s = Scenario.ffa("puppeteer")) {
            s.place(s.enemy, 0.5, s.param("ult_radius") + 3, 180);
            s.place(s.enemy2, 0.5, -4.5 - s.param("thread_reach"), 0);
            assertFalse(s.ultimate().succeeded(), "nobody within " + s.param("ult_radius") + " blocks");
            s.melee(s.owner, s.enemy, 1);
            s.clearHits();
            s.assertSucceeded(s.ultimate(), "the threaded enemy is within " + s.param("thread_reach") + " blocks");
            s.ticks(showLength(s) + 4);
            assertTrue(s.damageBy(s.owner, s.enemy) >= s.param("finale_damage"), "hung from further away");
            s.finishClean();
        }
    }

    @Test
    void puppeteerShowTakesOverTheMarionetteWithoutSnappingIt() {
        try (Scenario s = Scenario.ffa("puppeteer")) {
            s.place(s.enemy2, 0.5, -15.5, 0);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "꼭두각시");
            s.ticks(2);
            s.assertSucceeded(s.ultimate(), "인형극");
            assertFalse(s.active().succeeded(), "the active is cooling down");
            s.ticks(showLength(s) + 4);
            assertEquals(s.param("attach_damage") + claps(s) * s.param("clap_damage") + s.param("finale_damage"),
                    s.damageBy(s.owner, s.enemy), EPSILON, "the strings never snapped: the show took them over");
            assertModifierFree(s.enemy, "marionette and show");
            s.finishClean();
        }
    }

    // ================================================================== 외계인 alien

    private static boolean isAntenna(Display display) {
        return display instanceof BlockDisplay block && (block.getBlock().getMaterial() == Material.VERDANT_FROGLIGHT
                || block.getBlock().getMaterial() == Material.LIME_CONCRETE && block.getTransformation().getScale()
                .y() > 0.3f && block.getTransformation().getScale().x() < 0.05f);
    }

    /** Every display but the alien's antennae. */
    private static List<Display> nonAntennae(Scenario s) {
        return entities(s, Display.class).stream().filter(d -> !isAntenna(d)).toList();
    }

    @Test
    void alienAntennaeRideOnItsHeadOutOfItsOwnView() {
        try (Scenario s = Scenario.ffa("alien")) {
            s.ticks(3);
            List<BlockDisplay> antennae = entities(s, BlockDisplay.class);
            assertEquals(4, antennae.size(), "two stalks with glowing tips");
            assertEquals(2, blocks(s, Material.VERDANT_FROGLIGHT).size());
            for (BlockDisplay part : antennae) {
                assertUnrotated(part, "antenna");
                assertFalse(s.owner.canSee(part), "the alien does not see its own antennae");
                assertTrue(s.enemy.canSee(part), "everybody else does");
                assertEquals(s.owner.getLocation().getY() + s.owner.getHeight(), part.getLocation().getY(), 0.1,
                        "on top of the head");
            }
            s.place(s.owner, 5.5, -3.5, 90);
            s.ticks(2);
            for (BlockDisplay part : antennae) {
                assertEquals(5.5, part.getLocation().getX(), 0.05, "the antennae follow the alien");
                assertEquals(-3.5, part.getLocation().getZ(), 0.05);
                Vector3f up = rotated(part, new Vector3f(0, 1, 0));
                assertTrue(up.y() > 0.8f, "they stand upright, tilted a little: " + up);
            }
            s.finishClean();
        }
    }

    @Test
    void alienTelepathySensesHiddenEnemiesAndHitsThemHarder() {
        try (Scenario s = Scenario.ffa("alien")) {
            double bonus = 1 + s.param("sense_bonus") / 100;
            s.melee(s.owner, s.enemy, 4);
            assertEquals(4, s.damageBy(s.owner, s.enemy), EPSILON, "a visible enemy takes the plain hit");
            s.clearHits();
            s.owner.hidePlayer(s.ctx().plugin(), s.enemy);
            s.ticks(6);
            s.melee(s.owner, s.enemy, 4);
            assertEquals(4 * bonus, s.damageBy(s.owner, s.enemy), EPSILON, "a stealthed enemy is felt and hit harder");
            s.owner.showPlayer(s.ctx().plugin(), s.enemy);

            s.clearHits();
            s.enemy2.addPotionEffect(new org.bukkit.potion.PotionEffect(PotionEffectType.INVISIBILITY, 200, 0));
            s.melee(s.owner, s.enemy2, 4);
            assertEquals(4 * bonus, s.damageBy(s.owner, s.enemy2), EPSILON, "an invisible enemy too");
            s.clearHits();
            s.place(s.enemy2, 0.5, s.param("sense_range") + 4, 180);
            s.melee(s.owner, s.enemy2, 4);
            assertEquals(4, s.damageBy(s.owner, s.enemy2), EPSILON, "beyond " + s.param("sense_range") + " blocks");
            s.enemy2.removePotionEffect(PotionEffectType.INVISIBILITY);
            s.finishClean();
        }
    }

    @Test
    void alienRayGunBurnsHotterTheLongerItStaysOnOneTarget() {
        try (Scenario s = Scenario.ffa("alien")) {
            s.place(s.enemy2, 9.5, -6.5, 0);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "광선총");
            assertFalse(s.active().succeeded(), "on cooldown");
            s.ticks(2);
            assertEquals(BASE_SPEED * (1 - s.param("ray_slow") / 100), speed(s.owner), EPSILON, "slowed while firing");
            s.ticks(s.paramTicks("ray_duration") + 4);
            List<Scenario.Hit> hits = s.hitsBy(s.owner, s.enemy);
            int pulses = (int) Math.ceil(s.param("ray_duration") / s.param("ray_interval") - 1.0E-9);
            assertEquals(pulses, hits.size(), "one burn per pulse: " + hits);
            for (int i = 0; i < hits.size(); i++) {
                assertEquals(s.param("ray_damage") + i * s.param("ray_ramp"), hits.get(i).damage(), EPSILON,
                        "pulse " + i + " burns hotter");
            }
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON);
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.ticks(10);
            assertEquals(BASE_SPEED, speed(s.owner), EPSILON, "full speed again");
            assertTrue(nonAntennae(s).isEmpty(), "the gun and its beam are gone");
            s.finishClean();
        }
    }

    @Test
    void alienRayStopsAtWallsAndTheFirstEnemyButBurnsThroughTheHidden() {
        try (Scenario s = Scenario.ffa("alien")) {
            s.place(s.enemy2, 0.5, 8.5, 180); // right behind the aimed enemy
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "광선총");
            s.ticks(s.paramTicks("ray_duration") + 4);
            assertTrue(s.damageBy(s.owner, s.enemy) > 0, "the first enemy is burnt");
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON, "and shields the one behind it");

            s.readyActive();
            s.clearHits();
            s.owner.hidePlayer(s.ctx().plugin(), s.enemy);
            s.aimAt(s.owner, s.enemy2);
            s.assertSucceeded(s.active(), "광선총 through a stealthed enemy");
            s.ticks(s.paramTicks("ray_duration") + 4);
            assertTrue(s.damageBy(s.owner, s.enemy) > 0, "the hidden enemy in the beam burns");
            assertTrue(s.damageBy(s.owner, s.enemy2) > 0, "but does not stop the beam");
            s.owner.showPlayer(s.ctx().plugin(), s.enemy);

            s.readyActive();
            s.clearHits();
            s.world.wallAlongX(-3, 3, 2, 3, Material.STONE);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "광선총 into a wall");
            s.ticks(s.paramTicks("ray_duration") + 4);
            assertEquals(0, s.damageTaken(s.enemy) + s.damageTaken(s.enemy2), EPSILON, "the wall stops the beam");
            s.world.wallAlongX(-3, 3, 2, 3, Material.AIR);
            s.finishClean();
        }
    }

    @Test
    void alienRayFollowsTheAimAndPointsAlongIt() {
        try (Scenario s = Scenario.ffa("alien")) {
            s.place(s.owner, 0.5, 0.5, 90); // facing west
            s.place(s.enemy, -5.5, 0.5, 270);
            s.place(s.enemy2, 0.5, 6.5, 180);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "광선총");
            s.ticks(2);
            BlockDisplay beam = blocks(s, Material.LIME_STAINED_GLASS).stream()
                    .max((a, b) -> Float.compare(a.getTransformation().getScale().z(),
                            b.getTransformation().getScale().z())).orElseThrow();
            assertUnrotated(beam, "beam");
            Vector3f along = rotated(beam, new Vector3f(0, 0, 1));
            assertTrue(along.x() < -0.95f, "the beam runs west, to the enemy: " + along);
            assertTrue(beam.getTransformation().getScale().z() > 4, "and reaches it");
            s.ticks(6);
            double first = s.damageBy(s.owner, s.enemy);
            assertTrue(first > 0);
            s.aimAt(s.owner, s.enemy2); // swings the beam south
            s.ticks(12);
            assertTrue(s.damageBy(s.owner, s.enemy2) > 0, "the beam follows the aim");
            List<Scenario.Hit> south = s.hitsBy(s.owner, s.enemy2);
            assertEquals(s.param("ray_damage"), south.getFirst().damage(), EPSILON, "a new target starts cool");
            s.ticks(10);
            s.finishClean();
        }
    }

    /** Ticks from the cast until a caught abductee has been probed, carried and dropped. */
    private static int abductionLength(Scenario s) {
        return s.paramTicks("ufo_arrive") + 2 + 15 + s.paramTicks("probe_duration")
                + (int) Math.round(s.param("carry_distance") * 2) + 2 + 14;
    }

    @Test
    void alienUfoBeamsUpProbesCarriesOffAndDrops() {
        try (Scenario s = Scenario.ffa("alien")) {
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "UFO 납치");
            s.ticks(2);
            List<BlockDisplay> saucer = nonAntennae(s).stream().filter(BlockDisplay.class::isInstance)
                    .map(BlockDisplay.class::cast).toList();
            assertTrue(saucer.size() >= 12, "the saucer: hull, dome, pilot and rim lights: " + saucer.size());
            for (BlockDisplay part : saucer) {
                assertUnrotated(part, "saucer part");
                assertTrue(part.getLocation().getY() > 64 + 6, "it comes down from the sky");
            }
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "nothing before it arrives");
            s.ticks(s.paramTicks("ufo_arrive") + 15);
            assertTrue(s.enemy.getLocation().getY() > 64 + 2, "the enemy is beamed up: " + s.enemy.getLocation());
            assertEquals(0, speed(s.enemy), EPSILON, "and cannot walk away");
            assertTrue(s.enemy.hasPotionEffect(PotionEffectType.SLOW_FALLING), "a safety net while held");
            assertEquals(0, s.enemy2.getLocation().getY() - 64, 1.0E-6, "enemy2 was outside the beam");
            double startZ = s.enemy.getLocation().getZ();
            s.ticks(s.paramTicks("probe_duration") + 1);
            int probes = (int) Math.floor(s.param("probe_duration") / s.param("probe_interval") + 1.0E-9);
            assertEquals(probes * s.param("probe_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "probed");
            s.ticks(abductionLength(s));
            Location dropped = s.enemy.getLocation();
            assertTrue(dropped.getZ() > startZ + s.param("carry_distance") * 0.6,
                    "carried off the way the alien looks: " + dropped);
            assertEquals(64, dropped.getY(), 1.0E-6, "dropped onto the floor");
            assertEquals(probes * s.param("probe_damage") + s.param("drop_damage"), s.damageTaken(s.enemy), EPSILON,
                    "the drop hurts through the ability only (no fall damage on top): " + s.hits());
            assertFalse(s.enemy.hasPotionEffect(PotionEffectType.SLOW_FALLING), "the safety net is gone");
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON);
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.ticks(40);
            assertTrue(nonAntennae(s).isEmpty(), "the saucer flew off");
            assertModifierFree(s.enemy, "abduction");
            s.finishClean();
        }
    }

    @Test
    void alienUfoHuntsTheNearestEnemyDownButCanBeOutrun() {
        try (Scenario s = Scenario.ffa("alien")) {
            s.place(s.enemy2, 9.5, -6.5, 0);
            s.place(s.enemy, 4.5, 8.5, 180);
            s.aimAtFloor(s.owner, 0.5, 8.5); // 4 blocks beside the enemy
            s.assertSucceeded(s.ultimate(), "UFO 납치 on the ground");
            s.ticks(s.paramTicks("ufo_arrive") + 30);
            assertTrue(s.enemy.getLocation().getY() > 64 + 1, "the saucer glided over and beamed it up");
            s.ticks(abductionLength(s));
            s.finishClean();
        }
        try (Scenario s = Scenario.ffa("alien")) {
            s.place(s.enemy2, 9.5, -6.5, 0);
            s.place(s.enemy, 4.5, 8.5, 180);
            s.aimAtFloor(s.owner, 0.5, 8.5);
            s.assertSucceeded(s.ultimate(), "UFO 납치");
            double x = 4.5;
            for (int i = 0; i < s.paramTicks("ufo_arrive") + s.paramTicks("hunt_duration") + 4; i++) {
                x += 0.4; // sprinting away, faster than the saucer flies
                s.place(s.enemy, x, 8.5, 270);
                s.ticks(1);
            }
            s.ticks(40);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "outran the saucer");
            assertTrue(nonAntennae(s).isEmpty(), "the saucer left empty-handed");
            s.finishClean();
        }
    }

    @Test
    void alienUfoNeedsPreyNearTheAimedSpot() {
        try (Scenario s = Scenario.ffa("alien")) {
            s.aimAtFloor(s.owner, 0.5, -20.5);
            s.place(s.owner, 0.5, 0.5, 180);
            s.aimAtFloor(s.owner, 0.5, -20.5);
            assertFalse(s.ultimate().succeeded(), "nobody near that spot");
            s.place(s.owner, 0.5, 64, 0.5, 0, -80);
            assertFalse(s.ultimate().succeeded(), "aiming at the open sky");
            assertEquals(100, s.participant(s.owner).gauge(), EPSILON, "the gauge is kept");
            assertTrue(nonAntennae(s).isEmpty());
            s.finishClean();
        }
    }

    @Test
    void alienUfoNeverCarriesItsCatchThroughAWall() {
        try (Scenario s = Scenario.ffa("alien")) {
            s.world.wallAlongX(-6, 6, 9, 10, Material.STONE);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "UFO 납치");
            s.ticks(abductionLength(s) + s.paramTicks("probe_duration") + 20);
            assertTrue(s.damageBy(s.owner, s.enemy) > 0, "abducted");
            assertTrue(s.enemy.getLocation().getZ() < 9, "the wall stops the saucer: " + s.enemy.getLocation());
            assertEquals(64, s.enemy.getLocation().getY(), 1.0E-6, "on the floor");
            s.world.wallAlongX(-6, 6, 9, 10, Material.AIR);
            s.finishClean();
        }
    }

    // ================================================================== 좀비 zombie

    private static List<org.bukkit.entity.Zombie> zombies(Scenario s) {
        return entities(s, org.bukkit.entity.Zombie.class).stream().filter(Entity::isValid).toList();
    }

    private static boolean isInfected(Scenario s, Player player) {
        return speed(player) < BASE_SPEED * (1 - s.param("infect_slow") / 100) + EPSILON;
    }

    @Test
    void zombieOutbreakIsBuiltOnTheInfection() {
        try (Scenario s = gambler(false)) {
            assertFalse(s.ctx().registry().get("zombie").info().ultimateStandalone(), "the gambler never borrows it");
            s.finishClean();
        }
    }

    @Test
    void zombieMeleeInfectsRotsAndSlows() {
        try (Scenario s = Scenario.ffa("zombie")) {
            s.place(s.enemy2, 9.5, -6.5, 0);
            s.melee(s.owner, s.enemy, 2);
            s.clearHits();
            s.ticks(2);
            assertTrue(isInfected(s, s.enemy), "infected: a stiff gait");
            List<TextDisplay> marks = entities(s, TextDisplay.class);
            assertEquals(1, marks.size(), "a biohazard mark over the infected");
            assertUnrotated(marks.getFirst(), "mark");
            s.ticks(s.paramTicks("infect_duration") + 4);
            int rots = (int) Math.round(s.param("infect_duration"));
            List<Scenario.Hit> hits = s.hitsBy(s.owner, s.enemy);
            assertEquals(rots, hits.size(), "it rots once a second: " + hits);
            for (Scenario.Hit hit : hits) {
                assertEquals(s.param("infect_damage"), hit.damage(), EPSILON);
            }
            assertEquals(BASE_SPEED, speed(s.enemy), EPSILON, "cured");
            assertModifierFree(s.enemy, "infection");
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON, "enemy2 was far away");
            s.ticks(10);
            assertTrue(entities(s, TextDisplay.class).isEmpty(), "the mark is gone");
            s.finishClean();
        }
    }

    @Test
    void zombieInfectionJumpsOnceToAnEnemyStandingClose() {
        try (Scenario s = Scenario.builder("zombie").param("spread_duration", 10).start()) {
            s.place(s.enemy2, 2.0, 5.5, 180);
            s.melee(s.owner, s.enemy, 2);
            s.ticks(s.paramTicks("spread_interval") - 4);
            assertFalse(isInfected(s, s.enemy2), "not yet");
            s.ticks(8);
            assertTrue(isInfected(s, s.enemy2), "the infection jumped to the enemy next to it");
            s.place(s.enemy, 0.5, 14.5, 180);
            s.ticks(s.paramTicks("infect_duration"));
            assertFalse(isInfected(s, s.enemy), "the first one is cured");
            assertTrue(isInfected(s, s.enemy2), "the caught infection lasts " + s.param("spread_duration") + " s");
            s.place(s.enemy, 2.0, 3.5, 180); // back next to enemy2
            s.ticks(s.paramTicks("spread_interval") + 4);
            assertFalse(isInfected(s, s.enemy), "a caught infection does not jump on");
            s.ticks(60);
            s.finishClean();
        }
    }

    @Test
    void zombieBiteLungesInfectsAndFeedsOnTheInfected() {
        try (Scenario s = Scenario.ffa("zombie")) {
            s.place(s.enemy2, 9.5, -6.5, 0);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "물어뜯기");
            assertFalse(s.active().succeeded(), "on cooldown");
            s.h.tickUntil(() -> s.damageBy(s.owner, s.enemy) > 0, 30, "the bite");
            assertEquals(s.param("bite_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "bitten");
            assertTrue(s.owner.getLocation().getZ() > 2, "the zombie lunged at it: " + s.owner.getLocation());
            s.ticks(2);
            assertTrue(isInfected(s, s.enemy), "and infected");
            assertEquals(2, entities(s, TextDisplay.class).stream()
                    .filter(text -> Scenario.plain(text.text()).contains("▼") || Scenario.plain(text.text())
                            .contains("▲")).count(), "the jaws clamp shut");
            for (TextDisplay text : entities(s, TextDisplay.class)) {
                assertUnrotated(text, "jaw");
                assertEquals(Display.Billboard.CENTER, text.getBillboard());
            }
            s.tickUntilGrounded(s.owner, 40);
            s.ticks(10);
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "no fall damage from the lunge");

            s.owner.setHealth(10);
            s.place(s.owner, 0.5, 0.5, 0);
            s.readyActive();
            s.clearHits();
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "물어뜯기 an infected enemy");
            s.h.tickUntil(() -> s.damageBy(s.owner, s.enemy) >= s.param("bite_damage"), 30, "the second bite");
            assertEquals(s.param("bite_damage") + s.param("feed_damage"), s.hitsBy(s.owner, s.enemy).stream()
                    .mapToDouble(Scenario.Hit::damage).max().orElse(0), EPSILON, "feeds on the infected");
            assertEquals(10 + s.param("feed_heal"), s.owner.getHealth(), EPSILON, "and heals");
            s.tickUntilGrounded(s.owner, 40);
            s.finishClean();
        }
    }

    @Test
    void zombieBiteThatMissesRefundsHalfTheCooldown() {
        try (Scenario s = Scenario.ffa("zombie")) {
            s.place(s.owner, 0.5, 0.5, 180); // looking away from everyone
            s.assertSucceeded(s.active(), "물어뜯기 into thin air");
            s.ticks(40);
            assertEquals(0, s.damageTaken(s.enemy) + s.damageTaken(s.enemy2), EPSILON);
            double left = s.ctx().cooldownRemaining(s.owner);
            assertTrue(left > 0 && left < s.param("cooldown") * 0.6, "half refunded: " + left);
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "no fall damage");
            s.finishClean();
        }
    }

    @Test
    void zombieOutbreakInfectsEveryoneAroundAndRaisesTheDeadAtTheirFeet() {
        try (Scenario s = Scenario.teams("zombie")) {
            s.crossfireAlly();
            s.assertSucceeded(s.ultimate(), "좀비 아포칼립스");
            s.ticks(2);
            for (TestPlayer enemy : s.enemies()) {
                assertTrue(isInfected(s, enemy), enemy.getName() + " caught the outbreak");
            }
            assertEquals(BASE_SPEED, speed(s.ally()), EPSILON, "the ally is spared");
            assertTrue(zombies(s).isEmpty(), "the dead rise after the warning");
            assertTrue(blocks(s, Material.GREEN_TERRACOTTA).size() >= 2, "rotting arms reach out of the ground");
            for (Display display : entities(s, Display.class)) {
                assertUnrotated(display, "display " + display.getType());
            }
            for (BlockDisplay forearm : blocks(s, Material.GREEN_TERRACOTTA)) {
                assertTrue(forearm.getLocation().getY() < 64.1, "the arms grow out of the floor");
            }
            s.ticks(s.paramTicks("rise_delay") + 2);
            List<org.bukkit.entity.Zombie> risen = zombies(s);
            assertEquals(2, risen.size(), "one zombie per freshly infected enemy");
            for (org.bukkit.entity.Zombie zombie : risen) {
                assertTrue(s.ctx().isAlly(s.owner, zombie), "its own summon");
                assertFalse(s.ctx().isEnemy(s.ally(), zombie), "never an enemy of the owner's team");
                assertEquals(s.param("zombie_health"), zombie.getHealth(), EPSILON);
                assertEquals(s.param("zombie_damage"), zombie.getAttribute(Attribute.ATTACK_DAMAGE).getValue(),
                        EPSILON);
                assertEquals(Material.LEATHER_HELMET, zombie.getEquipment().getHelmet().getType(),
                        "a cap keeps it from burning in the sun");
                assertEquals(64, zombie.getLocation().getY(), 1.0E-6, "standing on the floor");
                assertTrue(s.world.getBlockAt(zombie.getLocation()).isPassable(), "not inside a block");
                double nearest = Math.min(zombie.getLocation().distance(s.enemy.getLocation()),
                        zombie.getLocation().distance(s.enemy2.getLocation()));
                assertTrue(nearest < 2.5, "it climbed out next to its host: " + nearest);
            }
            s.ticks(6);
            for (org.bukkit.entity.Zombie zombie : risen) {
                assertTrue(s.enemies().contains(zombie.getTarget()), "it hunts its host, never the ally");
            }

            s.clearHits();
            org.bukkit.entity.Zombie biter = risen.getFirst();
            s.melee(biter, s.enemy2, biter.getAttribute(Attribute.ATTACK_DAMAGE).getValue());
            assertEquals(s.param("zombie_damage"), s.damageTaken(s.enemy2), EPSILON, "a zombie bites for its damage");
            assertEquals(s.owner, s.ctx().resolveAttacker(biter), "credited to the owner");
            s.ticks(s.paramTicks("infect_duration") - 10);
            assertTrue(isInfected(s, s.enemy2), "the bite renewed the infection");

            s.ticks(s.paramTicks("zombie_duration"));
            assertTrue(zombies(s).isEmpty(), "the dead crumble after " + s.param("zombie_duration") + " s");
            assertEquals(0, s.damageTaken(s.ally()), EPSILON);
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.ticks(s.paramTicks("infect_duration") + 10);
            assertNoDisplays(s, "arms, cracks and marks");
            s.finishClean();
        }
    }

    @Test
    void zombieOutbreakRaisesMoreDeadFromTheAlreadySickUpToTheCap() {
        try (Scenario s = Scenario.ffa("zombie")) {
            s.melee(s.owner, s.enemy, 1);
            s.ticks(2);
            s.assertSucceeded(s.ultimate(), "좀비 아포칼립스");
            s.ticks(s.paramTicks("rise_delay") + 2);
            int expected = (int) (s.param("zombies_infected") + s.param("zombies_per"));
            assertEquals(expected, zombies(s).size(), "two from the sick enemy, one from the fresh one");
            s.ticks(s.paramTicks("zombie_duration") + 10);
            s.finishClean();
        }
        try (Scenario s = Scenario.builder("zombie").param("max_zombies", 1).start()) {
            s.assertSucceeded(s.ultimate(), "좀비 아포칼립스");
            s.ticks(s.paramTicks("rise_delay") + 2);
            assertEquals(1, zombies(s).size(), "at most " + s.param("max_zombies"));
            s.ticks(s.paramTicks("zombie_duration") + 10);
            s.finishClean();
        }
    }

    @Test
    void zombieOutbreakReachesInfectedEnemiesFarAwayAndNeedsSomebody() {
        try (Scenario s = Scenario.ffa("zombie")) {
            s.place(s.enemy, 0.5, s.param("ult_radius") + 4, 180);
            s.place(s.enemy2, 0.5, -s.param("horde_range") - 4, 0);
            assertFalse(s.ultimate().succeeded(), "nobody to infect or already infected");
            assertEquals(100, s.participant(s.owner).gauge(), EPSILON, "the gauge is kept");

            s.place(s.enemy, 0.5, 2.5, 180);
            s.melee(s.owner, s.enemy, 1);
            s.place(s.enemy, 0.5, s.param("ult_radius") + 4, 180);
            s.ticks(2);
            s.assertSucceeded(s.ultimate(), "the sick enemy is within " + s.param("horde_range") + " blocks");
            s.ticks(s.paramTicks("rise_delay") + 2);
            List<org.bukkit.entity.Zombie> risen = zombies(s);
            assertEquals((int) s.param("zombies_infected"), risen.size(), "they rise around the sick enemy far away");
            for (org.bukkit.entity.Zombie zombie : risen) {
                assertTrue(zombie.getLocation().distance(s.enemy.getLocation()) < 2.5);
            }
            s.ticks(s.paramTicks("zombie_duration") + 10);
            s.finishClean();
        }
    }

    @Test
    void zombieDeadNeverRiseInsideWalls() {
        try (Scenario s = Scenario.ffa("zombie")) {
            s.place(s.enemy2, 9.5, -6.5, 0);
            // A pen around the enemy with one free side to the east.
            for (int z = 3; z <= 6; z++) {
                for (int y = 64; y <= 66; y++) {
                    s.world.setBlock(-1, y, z, Material.STONE);
                }
            }
            for (int x = -1; x <= 1; x++) {
                for (int y = 64; y <= 66; y++) {
                    s.world.setBlock(x, y, 3, Material.STONE);
                    s.world.setBlock(x, y, 6, Material.STONE);
                }
            }
            s.melee(s.owner, s.enemy, 1);
            s.ticks(2);
            s.place(s.owner, 0.5, -2.5, 0);
            s.assertSucceeded(s.ultimate(), "좀비 아포칼립스");
            s.ticks(s.paramTicks("rise_delay") + 2);
            assertFalse(zombies(s).isEmpty(), "there is room on the open side");
            for (org.bukkit.entity.Zombie zombie : zombies(s)) {
                Location at = zombie.getLocation();
                assertTrue(Spots.isFree(s.world, at, 0.6, 1.95), "no zombie inside a block: " + at.toVector());
                assertTrue(at.getX() > 0, "on the open side of the pen: " + at.toVector());
            }
            s.ticks(s.paramTicks("zombie_duration") + 10);
            for (int z = 3; z <= 6; z++) {
                for (int y = 64; y <= 66; y++) {
                    s.world.setBlock(-1, y, z, Material.AIR);
                }
            }
            for (int x = -1; x <= 1; x++) {
                for (int y = 64; y <= 66; y++) {
                    s.world.setBlock(x, y, 3, Material.AIR);
                    s.world.setBlock(x, y, 6, Material.AIR);
                }
            }
            s.finishClean();
        }
    }
}
