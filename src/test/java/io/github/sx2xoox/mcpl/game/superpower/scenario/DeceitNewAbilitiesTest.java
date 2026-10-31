package io.github.sx2xoox.mcpl.game.superpower.scenario;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import io.github.sx2xoox.mcpl.game.superpower.abilities.BakuAbility;
import io.github.sx2xoox.mcpl.game.superpower.abilities.ErisAbility;
import io.github.sx2xoox.mcpl.game.superpower.abilities.MimicAbility;
import io.github.sx2xoox.mcpl.game.superpower.abilities.OdysseusAbility;
import io.github.sx2xoox.mcpl.game.superpower.abilities.SatoriAbility;
import io.github.sx2xoox.mcpl.game.superpower.ability.Ability;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import io.github.sx2xoox.mcpl.game.superpower.ability.ActivationResult;
import io.github.sx2xoox.mcpl.game.superpower.support.Scenario;
import io.github.sx2xoox.mcpl.game.superpower.support.TestPlayer;
import io.github.sx2xoox.mcpl.game.superpower.support.world.FlatWorld;
import io.papermc.paper.event.player.PlayerArmSwingEvent;
import org.bukkit.Input;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** 마음 · 속임수 (new): 사토리, 미믹, 오디세우스, 에리스, 바쿠. */
class DeceitNewAbilitiesTest {

    private static final double EPSILON = 1.0E-6;
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z0-9_]+)}");

    static Stream<String> abilities() {
        return Stream.of("satori", "mimic", "odysseus", "eris", "baku");
    }

    /** The ultimates the gambler may borrow. */
    static Stream<String> standalone() {
        return Stream.of("odysseus", "eris", "baku");
    }

    /**
     * {@link AbilityLifecycleTest} reads the ability registry in its static initializer, which starts a mock server of
     * its own; initialize it before any scenario runs (see RogueNewAbilitiesTest).
     */
    @BeforeAll
    static void initializeSharedSweeps() {
        assertTrue(AbilityLifecycleTest.abilities().findAny().isPresent());
    }

    // ================================================================== helpers

    private static <T extends Entity> List<T> entities(Scenario s, Class<T> type) {
        return s.world.getEntities().stream().filter(type::isInstance).map(type::cast).toList();
    }

    private static void assertNoDisplays(Scenario s, String what) {
        assertTrue(entities(s, Display.class).isEmpty(), what + ": every display is gone: "
                + entities(s, Display.class).stream().map(d -> d.getType() + "@" + d.getLocation().toVector()).toList());
    }

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

    private static void assertUnrotated(Entity display, String what) {
        assertEquals(0, display.getLocation().getYaw(), 1.0E-3, what + ": display yaw");
        assertEquals(0, display.getLocation().getPitch(), 1.0E-3, what + ": display pitch");
    }

    /** The left rotation a display was given (see WeirdNewAbilitiesTest#rotationOf). */
    private static Quaternionf rotationOf(Display display) {
        Transformation t = display.getTransformation();
        Quaternionf left = t.getLeftRotation();
        return t.getRightRotation().equals(new Quaternionf(), 1.0E-5f) ? left : left.conjugate();
    }

    /** Exactly what {@code GamblerAbility#fireBorrowed} does. */
    private static ActivationResult borrow(Scenario s, String id) {
        Ability borrowed = s.ctx().registry().create(id);
        borrowed.bind(s.owner, s.ctx(), s.ctx().paramsFor(id));
        return borrowed.onUltimate();
    }

    private static Scenario gambler(boolean teams) {
        Scenario.Builder builder = Scenario.builder("gambler").param("dodge_chance", 0).param("crit_chance", 0);
        return (teams ? builder.teams() : builder).start();
    }

    /** No ultimate gauge from damage or time, so gauge numbers in a test come from the abilities alone. */
    private static Scenario.Builder noGaugeGain(Scenario.Builder builder) {
        return builder.configure(h -> {
            h.settings().set("ultimate.per-damage-dealt", 0);
            h.settings().set("ultimate.per-damage-taken", 0);
            h.settings().set("ultimate.per-second", 0);
        });
    }

    private static boolean rooted(Player player) {
        return player.getAttribute(Attribute.MOVEMENT_SPEED).getValue() < 1.0E-9
                && player.getAttribute(Attribute.JUMP_STRENGTH).getValue() < 1.0E-9;
    }

    private static List<TextDisplay> texts(Scenario s, String containing) {
        return entities(s, TextDisplay.class).stream()
                .filter(text -> Scenario.plain(text.text()).contains(containing))
                .toList();
    }

    private static boolean near(Entity entity, Location at, double radius) {
        return entity.getWorld().equals(at.getWorld()) && entity.getLocation().distance(at) <= radius;
    }

    // ================================================================== every ability

    @Test
    void descriptionsOnlyReferenceDeclaredParams() {
        for (AbilityInfo info : List.of(SatoriAbility.INFO, MimicAbility.INFO, OdysseusAbility.INFO, ErisAbility.INFO,
                BakuAbility.INFO)) {
            for (AbilityInfo.Skill skill : List.of(info.passive(), info.active(), info.ultimate())) {
                Matcher matcher = PLACEHOLDER.matcher(skill.description());
                while (matcher.find()) {
                    assertTrue(info.params().containsKey(matcher.group(1)),
                            info.id() + " " + skill.name() + " uses an undeclared param " + matcher.group());
                }
            }
        }
        assertFalse(SatoriAbility.INFO.ultimateStandalone(), "the slip and the bonus are the satori's hooks");
        assertFalse(MimicAbility.INFO.ultimateStandalone(), "the pile is the mimic's own chest game");
        assertTrue(OdysseusAbility.INFO.ultimateStandalone());
        assertTrue(ErisAbility.INFO.ultimateStandalone());
        assertTrue(BakuAbility.INFO.ultimateStandalone());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("abilities")
    void ownerDiesDuringActive(String id) {
        try (Scenario s = Scenario.ffa(id)) {
            s.ticks(40);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), id + " active");
            s.ticks(8);
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
            assertTrue(enemy.canSee(s.owner), id + ": " + enemy.getName() + " sees the owner again");
        }
        assertModifierFree(s.owner, id);
        s.assertNoProblems();
        s.finishClean();
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
            s.ticks(12);
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

    // ================================================================== 사토리 satori

    /** Displays the owner sees and no enemy does. */
    private static boolean ownerOnly(Scenario s, Entity display) {
        return s.owner.canSee(display) && !s.enemy.canSee(display) && !s.enemy2.canSee(display);
    }

    /** The balloons over enemies aiming at the satori (seen by the satori alone). */
    private static List<TextDisplay> privateBalloons(Scenario s) {
        return entities(s, TextDisplay.class).stream().filter(text -> ownerOnly(s, text))
                .filter(text -> Scenario.plain(text.text()).contains("노린다")
                        || Scenario.plain(text.text()).contains("곧 온다"))
                .toList();
    }

    /** The ultimate's balloons with the heard enemies' next moves (seen by the satori's team). */
    private static List<TextDisplay> heardBalloons(Scenario s) {
        return entities(s, TextDisplay.class).stream().filter(text -> ownerOnly(s, text))
                .filter(text -> Scenario.plain(text.text()).contains("첫 공격"))
                .toList();
    }

    /** Turns both enemies away from the owner, so nobody aims at it. */
    private static void lookAway(Scenario s) {
        s.place(s.enemy, 0.5, 4.5, 0);
        s.place(s.enemy2, 2.0, 6.5, 0);
    }

    @Test
    void satoriSeesTheEnemyThatAimsAtItThroughWalls() {
        try (Scenario s = Scenario.ffa("satori")) {
            lookAway(s);
            s.ticks(20);
            assertTrue(privateBalloons(s).isEmpty(), "nobody aims at the satori yet");
            s.aimAt(s.enemy, s.owner);
            s.ticks(12);
            List<TextDisplay> balloons = privateBalloons(s);
            assertEquals(1, balloons.size(), "a balloon over the aiming enemy");
            TextDisplay balloon = balloons.getFirst();
            assertTrue(balloon.isSeeThrough(), "the balloon shows through walls");
            assertTrue(s.owner.canSee(balloon), "the satori sees it");
            assertTrue(Scenario.plain(balloon.text()).contains("노린다"), Scenario.plain(balloon.text()));
            assertTrue(balloon.getLocation().getY() > s.enemy.getLocation().getY() + s.enemy.getHeight(),
                    "over the enemy's head");
            assertEquals(s.enemy.getX(), balloon.getX(), 0.3);
            assertUnrotated(balloon, "balloon");

            // Holding the aim makes it urgent; looking away lets it go.
            s.ticks(30);
            assertTrue(Scenario.plain(privateBalloons(s).getFirst().text()).contains("곧 온다"));
            s.place(s.enemy, 0.5, 4.5, 0);
            s.ticks(20);
            assertTrue(privateBalloons(s).isEmpty(), "the balloon goes once the enemy looks away");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void satoriNeverShowsAHiddenEnemyAiming() {
        try (Scenario s = Scenario.ffa("satori")) {
            lookAway(s);
            s.ticks(20);
            s.owner.hidePlayer(s.ctx().plugin(), s.enemy);
            s.aimAt(s.enemy, s.owner);
            s.ticks(20);
            assertTrue(privateBalloons(s).isEmpty(), "a hidden enemy's aim must not give it away");
            s.owner.showPlayer(s.ctx().plugin(), s.enemy);
            s.ticks(12);
            assertEquals(1, privateBalloons(s).size());
            s.finishClean();
        }
    }

    @Test
    void satoriTakesMoreFromAccidentsOnly() {
        try (Scenario s = Scenario.ffa("satori")) {
            s.ticks(20);
            s.clearHits();
            s.owner.setNoDamageTicks(0);
            s.owner.damage(4, DamageSource.builder(DamageType.FALL).build());
            double weakness = s.param("accident_weakness") / 100.0;
            assertEquals(4 * (1 + weakness), s.damageTaken(s.owner), 1.0E-6, "a fall hurts more");
            s.clearHits();
            s.melee(s.enemy, s.owner, 4);
            assertEquals(4, s.damageTaken(s.owner), 1.0E-6, "an attack with a mind behind it does not");
            s.finishClean();
        }
    }

    /** Reads the aimed enemy's mind. */
    private static void read(Scenario s) {
        s.aimAt(s.owner, s.enemy);
        s.assertSucceeded(s.active(), "선읽기");
        s.ticks(2);
    }

    @Test
    void satoriCountersAnEnemyThatCastsWhileRead() {
        try (Scenario s = Scenario.ffa("satori")) {
            s.ticks(20);
            read(s);
            List<TextDisplay> dots = texts(s, ".");
            assertFalse(dots.isEmpty(), "the read mind wears '…'");
            assertTrue(s.enemy.canSee(dots.getFirst()) && s.enemy2.canSee(dots.getFirst()),
                    "everyone sees that the enemy is being read");
            s.clearHits();
            s.enemy.setVelocity(new Vector(0.6, 0.1, 0.4));
            s.assertSucceeded(s.match.useActive(s.enemy), "the enemy's skill");
            s.ticks(2);
            assertEquals(s.param("counter_damage"), s.damageBy(s.owner, s.enemy), 1.0E-6, "struck back");
            assertTrue(rooted(s.enemy), "stunned");
            assertTrue(s.ctx().isSilenced(s.enemy), "no skills while stunned");
            assertEquals(0, s.enemy.getVelocity().getX(), EPSILON, "its momentum dies (no stale velocity)");
            assertEquals(0, s.enemy.getVelocity().getZ(), EPSILON);
            assertFalse(texts(s, "지금 그거 쓰려고 했지").isEmpty(), "the satori says it out loud");

            // The guard: the enemy's blows do not land for a moment.
            s.clearHits();
            s.melee(s.enemy, s.owner, 5);
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "guarded");
            s.ticks(s.paramTicks("counter_guard") + 2);
            s.melee(s.enemy, s.owner, 5);
            assertEquals(5, s.damageTaken(s.owner), EPSILON, "the guard is over");
            s.ticks(s.paramTicks("counter_stun"));
            assertFalse(rooted(s.enemy), "the stun wore off");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void satoriCountersAnUltimateAndVoidsTheBlowThatTriggeredIt() {
        try (Scenario s = Scenario.ffa("satori")) {
            s.ticks(20);
            read(s);
            s.match.setGauge(s.enemy, 100);
            s.ticks(1);
            s.assertSucceeded(s.match.useUltimate(s.enemy), "the enemy's ultimate");
            s.ticks(2);
            assertEquals(s.param("counter_damage"), s.damageBy(s.owner, s.enemy), 1.0E-6, "the ultimate is read");

            s.layout();
            s.readyActive();
            s.ticks(s.paramTicks("counter_guard") + 4);
            read(s);
            s.clearHits();
            s.melee(s.enemy, s.owner, 6);
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "the blow that gave the enemy away is void");
            s.ticks(2);
            assertEquals(s.param("counter_damage"), s.damageBy(s.owner, s.enemy), 1.0E-6);
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void satoriReadThatSeesNothingRefundsTheCooldown() {
        try (Scenario s = Scenario.ffa("satori")) {
            s.ticks(20);
            read(s);
            s.ticks(s.paramTicks("read_duration") + 2);
            double read = s.paramTicks("read_duration") / 20.0;
            double expected = (s.param("cooldown") - read) * (1 - s.param("read_refund") / 100) - 4 / 20.0;
            assertEquals(expected, s.ctx().cooldownRemaining(s.owner), 0.2, "half the cooldown came back");
            assertEquals(0, s.damageTaken(s.enemy), EPSILON);
            assertFalse(rooted(s.enemy));
            s.finishClean();
        }
    }

    @Test
    void satoriReadNeedsAnEnemyInSight() {
        try (Scenario s = Scenario.ffa("satori")) {
            s.ticks(20);
            s.place(s.owner, 0.5, 0.5, 180);
            ActivationResult result = s.active();
            assertFalse(result.succeeded(), "nobody to read");
            assertEquals(0, s.ctx().cooldownRemaining(s.owner), EPSILON, "no cooldown spent");
            s.finishClean();
        }
    }

    @Test
    void satoriHearsEveryMindShowsItToTheTeamAndSlipsEachFirstBlow() {
        try (Scenario s = Scenario.teams("satori")) {
            s.ticks(20);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "다 들린다");
            s.ticks(3);
            List<TextDisplay> balloons = heardBalloons(s);
            assertEquals(2, balloons.size(), "a balloon over each heard enemy: " + balloons.size());
            for (TextDisplay balloon : balloons) {
                String text = Scenario.plain(balloon.text());
                assertTrue(text.contains("액티브") && text.contains("궁"), text);
                assertTrue(s.owner.canSee(balloon) && s.ally().canSee(balloon), "the team reads it");
                assertUnrotated(balloon, "heard balloon");
            }
            // Each enemy's first blow slips past, the second lands.
            s.clearHits();
            s.melee(s.enemy, s.owner, 4);
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "\"그럴 줄 알았어\"");
            s.melee(s.enemy2, s.owner, 4);
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "the other enemy's first blow slips too");
            s.melee(s.enemy, s.owner, 4);
            assertEquals(4, s.damageTaken(s.owner), EPSILON, "only the first one");
            // The satori hits the heard harder.
            s.clearHits();
            s.melee(s.owner, s.enemy, 5);
            assertEquals(5 * (1 + s.param("ult_bonus") / 100), s.damageBy(s.owner, s.enemy), 1.0E-6);
            s.ticks(s.paramTicks("ult_duration") + 10);
            assertTrue(heardBalloons(s).isEmpty(), "the balloons go with the ultimate");
            s.clearHits();
            s.melee(s.owner, s.enemy, 5);
            assertEquals(5, s.damageBy(s.owner, s.enemy), 1.0E-6, "no bonus afterwards");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void satoriUltimateNeedsSomeoneInReachAndSkipsTheHidden() {
        try (Scenario s = Scenario.ffa("satori")) {
            s.ticks(20);
            s.owner.hidePlayer(s.ctx().plugin(), s.enemy);
            s.owner.hidePlayer(s.ctx().plugin(), s.enemy2);
            assertFalse(s.ultimate().succeeded(), "hidden enemies are not heard");
            assertEquals(100, s.ctx().gauge(s.owner), EPSILON, "the gauge is kept");
            s.owner.showPlayer(s.ctx().plugin(), s.enemy2);
            s.assertSucceeded(s.ultimate(), "one enemy in reach");
            s.ticks(3);
            assertEquals(1, heardBalloons(s).size(), "only the visible enemy gets a balloon");
            s.owner.showPlayer(s.ctx().plugin(), s.enemy);
            s.finishClean();
        }
    }

    // ================================================================== 에리스 eris

    @Test
    void erisProfitsFromFightsSheDidNotStart() {
        try (Scenario s = Scenario.ffa("eris")) {
            s.ticks(20);
            s.clearHits();
            s.melee(s.owner, s.enemy, 5);
            assertEquals(5, s.damageBy(s.owner, s.enemy), 1.0E-6, "no bonus on a fresh enemy");
            s.melee(s.enemy2, s.enemy, 2);
            s.clearHits();
            s.melee(s.owner, s.enemy, 5);
            double bonus = s.param("opportunist_bonus") / 100;
            assertEquals(5 * (1 + bonus), s.damageBy(s.owner, s.enemy), 1.0E-6, "어부지리");
            s.ticks(s.paramTicks("opportunist_window") + 2);
            s.clearHits();
            s.melee(s.owner, s.enemy, 5);
            assertEquals(5, s.damageBy(s.owner, s.enemy), 1.0E-6, "the chance passed");
            // Its own hits never count as somebody else's.
            s.ticks(s.paramTicks("opportunist_window") + 2);
            s.clearHits();
            s.melee(s.owner, s.enemy, 5);
            assertEquals(5, s.damageBy(s.owner, s.enemy), 1.0E-6);
            s.finishClean();
        }
    }

    /** Waits until the thrown apple can be taken. */
    private static void waitForTheApple(Scenario s) {
        s.ticks(40);
    }

    @Test
    void erisAppleAtAnEnemysFeetCrownsTheFairest() {
        try (Scenario s = Scenario.ffa("eris")) {
            s.ticks(20);
            s.enemy.setHealth(s.enemy.getHealth() - 10);
            double before = s.enemy.getHealth();
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "불화의 사과");
            waitForTheApple(s);
            assertEquals(before + s.param("bait_heal"), s.enemy.getHealth(), 1.0E-6, "the bait heals");
            assertTrue(s.enemy.hasPotionEffect(PotionEffectType.GLOWING), "the fairest glows");
            List<BlockDisplay> crown = entities(s, BlockDisplay.class);
            assertEquals(9, crown.size(), "a crown of four bars, four spikes and a ruby");
            for (BlockDisplay part : crown) {
                assertUnrotated(part, "crown");
                assertTrue(part.getLocation().getY() > s.enemy.getLocation().getY() + s.enemy.getHeight(),
                        "the crown sits over the head");
            }
            assertFalse(texts(s, "가장 아름다운 자").isEmpty());
            // Everyone else hits the fairest harder.
            double bonus = s.param("fairest_bonus") / 100;
            s.clearHits();
            s.melee(s.enemy2, s.enemy, 4);
            assertEquals(4 * (1 + bonus), s.damageTaken(s.enemy), 1.0E-6, "the fairest is everyone's target");
            s.ticks(s.paramTicks("fairest_duration") + 4);
            assertTrue(entities(s, BlockDisplay.class).isEmpty(), "the crown goes");
            s.clearHits();
            s.ticks(s.paramTicks("opportunist_window") + 2);
            s.melee(s.enemy2, s.enemy, 4);
            assertEquals(4, s.damageTaken(s.enemy), 1.0E-6, "and so does the bad luck");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void erisAppleNobodyTakesBurstsIntoDiscord() {
        try (Scenario s = Scenario.ffa("eris")) {
            s.ticks(20);
            s.place(s.enemy, 2.5, 8.5, 180);
            s.place(s.enemy2, 12.5, 12.5, 180);
            s.aimAtFloor(s.owner, 0.5, 8.5);
            s.assertSucceeded(s.active(), "불화의 사과");
            s.ticks(30);
            List<ItemDisplay> apples = entities(s, ItemDisplay.class);
            assertEquals(1, apples.size(), "the apple lies there");
            ItemDisplay apple = apples.getFirst();
            assertEquals(8.5, apple.getZ(), 0.6, "where the owner aimed");
            assertEquals(FlatWorld.SURFACE + 0.3, apple.getY(), 0.05, "resting on the floor");
            assertTrue(apple.isGlowing(), "glowing");
            assertFalse(texts(s, "가장 아름다운 자에게").isEmpty(), "with its inscription");
            s.clearHits();
            s.ticks(s.paramTicks("apple_duration") + 4);
            assertEquals(s.param("burst_damage"), s.damageBy(s.owner, s.enemy), 1.0E-6, "the burst catches the enemy");
            assertEquals(0, s.damageBy(s.owner, s.enemy2), EPSILON, "but not the one far away");
            // Discord: the opportunist always applies.
            s.clearHits();
            s.melee(s.owner, s.enemy, 5);
            assertEquals(5 * (1 + s.param("opportunist_bonus") / 100), s.damageBy(s.owner, s.enemy), 1.0E-6);
            s.ticks(s.paramTicks("discord_duration") + 2);
            s.clearHits();
            s.melee(s.owner, s.enemy, 5);
            assertEquals(5, s.damageBy(s.owner, s.enemy), 1.0E-6, "the discord passed");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void erisAppleIsABiteForTheTeam() {
        try (Scenario s = Scenario.teams("eris")) {
            s.ticks(20);
            s.place(s.enemy, 10.5, 10.5, 180);
            s.place(s.enemy2, 12.5, 12.5, 180);
            s.aimAtFloor(s.owner, 0.5, 6.5);
            s.assertSucceeded(s.active(), "불화의 사과");
            s.ticks(30);
            s.ally().setHealth(s.ally().getHealth() - 6);
            double before = s.ally().getHealth();
            s.place(s.ally(), 0.5, 6.5, 0);
            s.ticks(3);
            assertEquals(before + s.param("friend_heal"), s.ally().getHealth(), 1.0E-6, "a friend's bite");
            assertFalse(s.ally().hasPotionEffect(PotionEffectType.GLOWING), "no crown for friends");
            s.ticks(5);
            assertTrue(entities(s, ItemDisplay.class).isEmpty(), "the apple is gone");
            s.clearHits();
            s.ticks(s.paramTicks("apple_duration") + 4);
            assertEquals(0, s.damageBy(s.owner, s.enemy), EPSILON, "a taken apple never bursts");
            s.finishClean();
        }
    }

    @Test
    void erisAppleIsNeverTakenByAHiddenEnemy() {
        try (Scenario s = Scenario.ffa("eris")) {
            s.ticks(20);
            s.place(s.enemy2, 12.5, 12.5, 180);
            s.aimAtFloor(s.owner, 0.5, 4.5);
            s.owner.hidePlayer(s.ctx().plugin(), s.enemy);
            s.assertSucceeded(s.active(), "불화의 사과");
            s.ticks(40);
            assertFalse(s.enemy.hasPotionEffect(PotionEffectType.GLOWING), "a hidden enemy is never crowned");
            assertEquals(1, entities(s, ItemDisplay.class).size(), "the apple still lies there");
            s.owner.showPlayer(s.ctx().plugin(), s.enemy);
            s.finishClean();
        }
    }

    @Test
    void erisDiscordChainsNeighboursHurtsThemAndPushesThemApart() {
        try (Scenario s = Scenario.ffa("eris")) {
            s.ticks(20);
            s.place(s.enemy, 0.5, 4.5, 180);
            s.place(s.enemy2, 2.5, 4.5, 180);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "불화의 씨앗");
            assertEquals(2, entities(s, ItemDisplay.class).size(), "a seed over each infected head");
            double start = s.enemy.getLocation().distance(s.enemy2.getLocation());
            s.ticks(4 + s.paramTicks("chain_period") + 2);
            assertTrue(s.damageBy(s.owner, s.enemy) > 0 && s.damageBy(s.owner, s.enemy2) > 0, "both ends hurt");
            assertTrue(s.enemy.getLocation().distance(s.enemy2.getLocation()) > start + 0.3, "and pushed apart");
            for (ItemDisplay seed : entities(s, ItemDisplay.class)) {
                assertUnrotated(seed, "seed");
            }
            // Far apart, nobody is chained any more.
            s.place(s.enemy2, 12.5, 12.5, 180);
            s.ticks(2);
            s.clearHits();
            s.ticks(s.paramTicks("chain_period") * 2 + 2);
            assertEquals(0, s.damageBy(s.owner, s.enemy) + s.damageBy(s.owner, s.enemy2), EPSILON,
                    "a lonely enemy is safe");
            // In a free for all the infected also hit each other harder.
            s.clearHits();
            s.melee(s.enemy2, s.enemy, 4);
            assertEquals(4 * (1 + s.param("ffa_bonus") / 100), s.damageTaken(s.enemy), 1.0E-6);
            s.ticks(s.paramTicks("ult_duration"));
            s.clearHits();
            s.melee(s.enemy2, s.enemy, 4);
            assertEquals(4, s.damageTaken(s.enemy), 1.0E-6, "the bonus ends with the seeds");
            assertNoDisplays(s, "discord");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void erisDiscordNeedsSomebody() {
        try (Scenario s = Scenario.ffa("eris")) {
            s.ticks(20);
            s.place(s.enemy, 0.5, 30.5, 180);
            s.place(s.enemy2, 20.5, 30.5, 180);
            assertFalse(s.ultimate().succeeded(), "nobody within reach");
            assertEquals(100, s.ctx().gauge(s.owner), EPSILON, "the gauge is kept");
            s.finishClean();
        }
    }
    // ================================================================== 미믹 mimic

    /** The mimic's (temporary) chest blocks within 12 blocks of the origin. */
    private static List<Block> chestBlocks(Scenario s) {
        List<Block> found = new ArrayList<>();
        for (int x = -12; x <= 12; x++) {
            for (int z = -12; z <= 12; z++) {
                Block block = s.world.getBlockAt(x, FlatWorld.FLOOR_Y + 1, z);
                if (block.getType() == Material.CHEST) {
                    found.add(block);
                }
            }
        }
        return found;
    }

    /** The mimic's chests other than the one it may be hiding in (an idle mimic disguises itself). */
    private static List<Block> chestsAway(Scenario s) {
        Block feet = s.owner.getLocation().getBlock();
        return chestBlocks(s).stream().filter(block -> !block.equals(feet)).toList();
    }

    /** Right-clicks a block like a client does, holding a sword (not the ability item). */
    private static void open(Scenario s, Player player, Block block) {
        player.getInventory().setHeldItemSlot(0);
        player.getInventory().setItemInMainHand(new ItemStack(Material.IRON_SWORD));
        s.h.server.getPluginManager().callEvent(new PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK,
                player.getInventory().getItemInMainHand(), block, BlockFace.UP, EquipmentSlot.HAND));
    }

    /** A punch on a block like a client does. */
    private static void punch(Scenario s, Player player, Block block) {
        s.h.server.getPluginManager().callEvent(new PlayerInteractEvent(player, Action.LEFT_CLICK_BLOCK,
                player.getInventory().getItemInMainHand(), block, BlockFace.UP, EquipmentSlot.HAND));
    }

    /** Keys pressed on the client. */
    private static Input keys(boolean forward, boolean jump) {
        return new Input() {
            @Override
            public boolean isForward() {
                return forward;
            }

            @Override
            public boolean isBackward() {
                return false;
            }

            @Override
            public boolean isLeft() {
                return false;
            }

            @Override
            public boolean isRight() {
                return false;
            }

            @Override
            public boolean isJump() {
                return jump;
            }

            @Override
            public boolean isSneak() {
                return false;
            }

            @Override
            public boolean isSprint() {
                return false;
            }
        };
    }

    /** Waits until the standing-still mimic turns into a chest. */
    private static Block disguised(Scenario s) {
        s.ticks(s.paramTicks("disguise_delay") + 12);
        Block feet = s.owner.getLocation().getBlock();
        assertEquals(Material.CHEST, feet.getType(), "the mimic became a chest");
        return feet;
    }

    @Test
    void mimicStandingStillBecomesALootLikeChest() {
        try (Scenario s = Scenario.teams("mimic")) {
            s.ticks(5);
            Block feet = disguised(s);
            assertTrue(s.h.hub.tempBlocks().isTemporary(feet), "a temporary block");
            assertFalse(s.enemy.canSee(s.owner), "enemies see only the chest");
            assertFalse(s.enemy2.canSee(s.owner));
            assertTrue(s.ally().canSee(s.owner), "the team still sees its mimic (see-through)");
            assertTrue(s.owner.hasPotionEffect(PotionEffectType.INVISIBILITY));
            // Pressing a movement key breaks it before the body even moves.
            s.h.server.getPluginManager().callEvent(new PlayerInputEvent(s.owner, keys(true, false)));
            s.ticks(4);
            assertEquals(Material.AIR, feet.getType(), "the chest goes");
            assertTrue(s.enemy.canSee(s.owner), "and the mimic is back");
            assertFalse(s.owner.hasPotionEffect(PotionEffectType.INVISIBILITY));
            // Walking also breaks it, and no disguise while moving.
            disguised(s);
            s.place(s.owner, 1.5, 0.5, 0);
            s.ticks(4);
            assertTrue(chestBlocks(s).isEmpty());
            assertTrue(s.enemy.canSee(s.owner));
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void mimicChestBitesTheEnemyThatOpensItAndAmbushes() {
        try (Scenario s = Scenario.ffa("mimic")) {
            s.ticks(5);
            Block chest = disguised(s);
            s.clearHits();
            open(s, s.enemy, chest);
            assertEquals(s.param("disguise_bite"), s.damageBy(s.owner, s.enemy), 1.0E-6, "덥석");
            assertTrue(rooted(s.enemy), "held by the tongue");
            assertTrue(s.enemy.canSee(s.owner), "the mimic jumped out");
            s.ticks(5);
            assertEquals(Material.AIR, chest.getType(), "its chest is gone");
            assertFalse(texts(s, "덥석").isEmpty(), "with a big 덥석!");
            for (BlockDisplay part : entities(s, BlockDisplay.class)) {
                assertUnrotated(part, "fang/tongue");
            }
            // The first melee blow right after jumping out is an ambush.
            s.clearHits();
            s.melee(s.owner, s.enemy, 4);
            assertEquals(4 + s.param("ambush_bonus"), s.damageBy(s.owner, s.enemy), 1.0E-6, "기습");
            s.clearHits();
            s.melee(s.owner, s.enemy, 4);
            assertEquals(4, s.damageBy(s.owner, s.enemy), 1.0E-6, "only the first");
            s.ticks(s.paramTicks("disguise_grab"));
            assertFalse(rooted(s.enemy), "let go");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void mimicTongueLashesAtTheVictim() {
        try (Scenario s = Scenario.ffa("mimic")) {
            s.ticks(5);
            Block chest = disguised(s);
            open(s, s.enemy, chest);
            s.ticks(2);
            BlockDisplay tongue = entities(s, BlockDisplay.class).stream()
                    .filter(d -> d.getBlock().getMaterial() == Material.PINK_WOOL).findFirst().orElseThrow();
            assertUnrotated(tongue, "tongue");
            Vector3f along = rotationOf(tongue).transform(new Vector3f(0, 0, 1));
            Vector toVictim = s.enemy.getLocation().toVector().subtract(chest.getLocation().toVector()).setY(0)
                    .normalize();
            assertTrue(along.x() * toVictim.getX() + along.z() * toVictim.getZ() > 0.8,
                    "the tongue points at the victim: " + along);
            s.finishClean();
        }
    }

    @Test
    void mimicChestNeverOpensForTheAbilityItemAndAPunchOnlyRevealsIt() {
        try (Scenario s = Scenario.ffa("mimic")) {
            s.ticks(5);
            Block chest = disguised(s);
            ItemStack abilityItem = s.enemy.getInventory().getItem(8);
            assertNotNull(abilityItem);
            s.enemy.getInventory().setHeldItemSlot(8);
            s.clearHits();
            s.h.server.getPluginManager().callEvent(new PlayerInteractEvent(s.enemy, Action.RIGHT_CLICK_BLOCK,
                    abilityItem, chest, BlockFace.UP, EquipmentSlot.HAND));
            assertEquals(0, s.damageBy(s.owner, s.enemy), EPSILON, "that click is the enemy's own skill");
            assertEquals(Material.CHEST, chest.getType(), "still a chest");
            punch(s, s.enemy, chest);
            assertTrue(s.enemy.canSee(s.owner), "a punch gives the mimic away");
            assertEquals(0, s.damageBy(s.owner, s.enemy), EPSILON, "without a bite");
            s.ticks(5);
            assertEquals(Material.AIR, chest.getType());
            s.finishClean();
        }
    }

    @Test
    void mimicDisguiseBreaksWhenHurtOrAttacking() {
        try (Scenario s = Scenario.ffa("mimic")) {
            s.ticks(5);
            disguised(s);
            s.melee(s.enemy, s.owner, 2);
            assertTrue(s.enemy.canSee(s.owner), "hurt: out");
            s.ticks(4);
            assertTrue(chestBlocks(s).isEmpty());
            disguised(s);
            s.melee(s.owner, s.enemy, 2);
            assertTrue(s.enemy.canSee(s.owner), "attacking: out");
            s.finishClean();
        }
    }

    @Test
    void mimicBaitBitesOnLandingStaysAndBitesWhoeverOpensOrHitsIt() {
        try (Scenario s = Scenario.ffa("mimic")) {
            s.ticks(5);
            s.aimAt(s.owner, s.enemy);
            s.clearHits();
            s.assertSucceeded(s.active(), "미끼 상자");
            s.ticks(20);
            assertEquals(s.param("land_damage"), s.damageBy(s.owner, s.enemy), 1.0E-6, "snapped at on landing");
            List<Block> baits = chestsAway(s);
            assertEquals(1, baits.size(), "the bait stays");
            Block bait = baits.getFirst();
            assertTrue(bait.getLocation().add(0.5, 0, 0.5).distance(s.enemy.getLocation()) < 2,
                    "next to the enemy (never inside it)");
            assertFalse(s.enemy.getBoundingBox().overlaps(BoundingBox.of(bait)), "nobody is stuck in the chest");
            // Opening it.
            s.ticks(s.paramTicks("land_grab"));
            s.clearHits();
            open(s, s.enemy2, bait);
            assertEquals(s.param("bait_bite"), s.damageBy(s.owner, s.enemy2), 1.0E-6, "덥석");
            assertTrue(rooted(s.enemy2));
            s.ticks(16);
            assertTrue(chestsAway(s).isEmpty(), "a bait that bit is gone");
            // Hitting one.
            s.readyActive();
            s.layout();
            s.aimAtFloor(s.owner, -3.5, 8.5);
            s.assertSucceeded(s.active(), "미끼 상자");
            s.ticks(20);
            Block second = chestsAway(s).getFirst();
            s.clearHits();
            punch(s, s.enemy, second);
            assertEquals(s.param("bait_snap"), s.damageBy(s.owner, s.enemy), 1.0E-6, "되물기");
            s.ticks(8);
            assertTrue(chestsAway(s).isEmpty(), "a hit bait snaps once and goes");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void mimicKeepsAtMostTheMaxBaitsAndTheyExpire() {
        try (Scenario s = Scenario.ffa("mimic")) {
            s.ticks(5);
            s.place(s.enemy, 12.5, 12.5, 180);
            s.place(s.enemy2, -12.5, 12.5, 180);
            for (int i = 0; i < 3; i++) {
                s.readyActive();
                s.aimAtFloor(s.owner, -3.5 + i * 3, 6.5);
                s.assertSucceeded(s.active(), "bait " + i);
                s.ticks(20);
            }
            assertEquals(s.params().integer("max_baits"), chestsAway(s).size(), "the oldest bait went");
            assertEquals(Material.AIR, s.world.getBlockAt(-4, FlatWorld.FLOOR_Y + 1, 6).getType(),
                    "the first one, to be exact");
            s.ticks(s.paramTicks("bait_duration") + 5);
            assertTrue(chestsAway(s).isEmpty(), "baits expire");
            assertEquals(chestBlocks(s).size(), s.h.hub.tempBlocks().size(), "only the idle mimic's own chest is left");
            s.finishClean();
        }
    }

    @Test
    void mimicBaitsTwinkleLikeLootChestsForTheNearby() {
        try (Scenario s = Scenario.ffa("mimic")) {
            s.ticks(5);
            s.place(s.enemy2, 15.5, 15.5, 180);
            s.aimAtFloor(s.owner, 0.5, 6.5);
            s.assertSucceeded(s.active(), "미끼 상자");
            s.ticks(20);
            s.enemy.particles().clear();
            s.enemy2.particles().clear();
            s.ticks(40);
            assertTrue(s.enemy.particles().stream().anyMatch(p -> p.particle() == Particle.WAX_OFF),
                    "a nearby enemy sees the sparkle");
            assertTrue(s.enemy2.particles().stream().noneMatch(p -> p.particle() == Particle.WAX_OFF),
                    "a far one does not");
            s.finishClean();
        }
    }

    /** The pile chests by their marker. */
    private static Block pileChest(Scenario s, String marker) {
        TextDisplay label = texts(s, marker).stream().findFirst().orElseThrow(() -> new AssertionError(
                "no " + marker + " marker"));
        Block block = label.getLocation().subtract(0, 1.25, 0).getBlock();
        assertEquals(Material.CHEST, block.getType(), marker + " chest");
        return block;
    }

    @Test
    void mimicPileHidesAmongTreasuresAndFakes() {
        try (Scenario s = Scenario.teams("mimic")) {
            s.ticks(5);
            s.place(s.enemy, 0.5, 9.5, 180);
            s.place(s.enemy2, 9.5, 9.5, 180);
            s.assertSucceeded(s.ultimate(), "보물 무더기");
            assertFalse(s.enemy.canSee(s.owner), "the mimic vanished in the smoke");
            s.ticks(14);
            int expected = 1 + s.params().integer("pile_treasures") + s.params().integer("pile_fakes");
            assertEquals(expected, chestBlocks(s).size(), "a pile of chests");
            assertEquals(Material.CHEST, s.owner.getLocation().getBlock().getType(), "the mimic is one of them");
            for (Block block : chestBlocks(s)) {
                assertTrue(block.getLocation().add(0.5, 0, 0.5).distance(s.ally().getLocation())
                        > 0.6 || !block.equals(s.ally().getLocation().getBlock()), "nobody stands in a chest");
            }
            List<TextDisplay> markers = entities(s, TextDisplay.class).stream()
                    .filter(text -> Scenario.plain(text.text()).matches(".*(보물|가짜|나).*")).toList();
            assertEquals(expected, markers.size(), "a marker on every chest");
            for (TextDisplay marker : markers) {
                assertTrue(s.owner.canSee(marker) && s.ally().canSee(marker), "the team reads the markers");
                assertFalse(s.enemy.canSee(marker) || s.enemy2.canSee(marker), "the enemies do not");
            }
            // A treasure for the curious, a fake that bites, and the mimic itself.
            s.enemy.setHealth(s.enemy.getHealth() - 6);
            double before = s.enemy.getHealth();
            open(s, s.enemy, pileChest(s, "보물"));
            assertEquals(before + s.param("treasure_heal"), s.enemy.getHealth(), 1.0E-6, "real treasure");
            s.clearHits();
            open(s, s.enemy, pileChest(s, "가짜"));
            assertEquals(s.param("fake_bite"), s.damageBy(s.owner, s.enemy), 1.0E-6, "a fake bites");
            assertFalse(s.enemy.canSee(s.owner), "the mimic is still hidden");
            s.clearHits();
            open(s, s.enemy2, pileChest(s, "나"));
            assertEquals(s.param("mimic_bite"), s.damageBy(s.owner, s.enemy2), 1.0E-6, "found it: the mimic bites");
            assertTrue(rooted(s.enemy2));
            assertTrue(s.enemy.canSee(s.owner), "and jumps out");
            assertTrue(s.owner.getAttribute(Attribute.MOVEMENT_SPEED).getModifiers().stream()
                    .anyMatch(m -> m.getAmount() > 0), "with a burst of speed");
            s.ticks(s.paramTicks("leftover") + 20);
            assertTrue(chestsAway(s).isEmpty(), "the rest of the pile goes");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void mimicLeavesThePileWhenItMovesOrTheTimeIsUp() {
        try (Scenario s = Scenario.ffa("mimic")) {
            s.ticks(5);
            s.place(s.enemy, 0.5, 12.5, 180);
            s.place(s.enemy2, 12.5, 12.5, 180);
            s.assertSucceeded(s.ultimate(), "보물 무더기");
            s.ticks(s.paramTicks("pile_duration") + 14);
            assertTrue(s.enemy.canSee(s.owner), "out after the time");
            s.ticks(s.paramTicks("leftover") + 10);
            assertTrue(chestsAway(s).isEmpty());
            s.match.setGauge(s.owner, 100);
            s.ticks(2);
            s.assertSucceeded(s.ultimate(), "again");
            s.ticks(14);
            s.h.server.getPluginManager().callEvent(new PlayerInputEvent(s.owner, keys(false, true)));
            assertTrue(s.enemy.canSee(s.owner), "a jump gets it out");
            s.ticks(s.paramTicks("leftover") + 10);
            assertTrue(chestsAway(s).isEmpty());
            s.assertNoProblems();
            s.finishClean();
        }
    }
    // ================================================================== 오디세우스 odysseus

    @Test
    void odysseusIsNobodyInTheKillFeedAndTheAnnouncements() {
        try (Scenario s = Scenario.ffa("odysseus")) {
            s.ticks(20);
            Scenario.messages(s.enemy2);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "트로이 목마");
            String announced = String.join("\n", Scenario.messages(s.enemy2));
            assertTrue(announced.contains("아무도"), "the ultimate is announced as Nobody's: " + announced);
            assertFalse(announced.contains("Owner"), announced);
            s.ticks(s.paramTicks("horse_duration") + 20);
            Scenario.messages(s.enemy2);
            s.enemy.setHealth(1);
            s.melee(s.owner, s.enemy, 6);
            assertFalse(s.session.isAlive(s.enemy), "the enemy fell");
            String feed = String.join("\n", Scenario.messages(s.enemy2));
            assertTrue(feed.contains("아무도"), "Nobody killed it: " + feed);
            assertFalse(feed.contains("Owner"), "the real name never shows: " + feed);
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void odysseusNameTagIsHiddenFromEnemiesAndReadsNobody() {
        try (Scenario s = Scenario.teams("odysseus")) {
            s.ticks(25);
            for (Player enemy : s.enemies()) {
                org.bukkit.scoreboard.Team team = enemy.getScoreboard().getEntryTeam(s.owner.getName());
                assertNotNull(team, "the owner's entry is on a team of " + enemy.getName() + "'s board");
                assertEquals(org.bukkit.scoreboard.Team.OptionStatus.NEVER,
                        team.getOption(org.bukkit.scoreboard.Team.Option.NAME_TAG_VISIBILITY), "no name tag");
            }
            org.bukkit.scoreboard.Team own = s.ally().getScoreboard().getEntryTeam(s.owner.getName());
            assertTrue(own == null || own.getOption(org.bukkit.scoreboard.Team.Option.NAME_TAG_VISIBILITY)
                    != org.bukkit.scoreboard.Team.OptionStatus.NEVER, "the team still sees the real name");
            TextDisplay tag = texts(s, "아무도").stream().findFirst().orElseThrow();
            assertTrue(s.enemy.canSee(tag) && s.enemy2.canSee(tag), "enemies read 아무도");
            assertFalse(s.owner.canSee(tag), "not the owner");
            assertFalse(s.ally().canSee(tag), "nor the team");
            assertTrue(tag.getLocation().getY() > s.owner.getLocation().getY() + s.owner.getHeight());
            assertUnrotated(tag, "name tag");
            s.killOwner(s.enemy);
            for (Player enemy : s.enemies()) {
                org.bukkit.scoreboard.Team team = enemy.getScoreboard().getEntryTeam(s.owner.getName());
                assertTrue(team == null || team.getOption(org.bukkit.scoreboard.Team.Option.NAME_TAG_VISIBILITY)
                        != org.bukkit.scoreboard.Team.OptionStatus.NEVER, "the name tag is back after the match "
                        + "lost Nobody");
            }
            s.finishClean();
        }
    }

    @Test
    void odysseusShrugsOffGlowing() {
        try (Scenario s = Scenario.ffa("odysseus")) {
            s.ticks(5);
            s.owner.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 200, 0));
            assertFalse(s.owner.hasPotionEffect(PotionEffectType.GLOWING), "no light finds Nobody");
            s.enemy.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 200, 0));
            assertTrue(s.enemy.hasPotionEffect(PotionEffectType.GLOWING), "others still glow");
            s.finishClean();
        }
    }

    @Test
    void odysseusHitsGiantsHarder() {
        try (Scenario s = Scenario.ffa("odysseus")) {
            s.ticks(5);
            s.clearHits();
            s.melee(s.owner, s.enemy, 4);
            assertEquals(4, s.damageBy(s.owner, s.enemy), 1.0E-6, "a normal-sized enemy");
            s.enemy.getAttribute(Attribute.SCALE).addModifier(new AttributeModifier(
                    new org.bukkit.NamespacedKey("test", "giant"), 0.3, AttributeModifier.Operation.ADD_NUMBER,
                    org.bukkit.inventory.EquipmentSlotGroup.ANY));
            s.clearHits();
            s.melee(s.owner, s.enemy, 4);
            assertEquals(4 * (1 + s.param("giant_bonus") / 100), s.damageBy(s.owner, s.enemy), 1.0E-6, "a giant");
            s.enemy.getAttribute(Attribute.SCALE).removeModifier(new org.bukkit.NamespacedKey("test", "giant"));
            s.finishClean();
        }
    }

    @Test
    void odysseusStakeBlindsTheFirstEnemyAndVanishesFromItAlone() {
        try (Scenario s = Scenario.ffa("odysseus")) {
            s.ticks(5);
            s.place(s.enemy, 0.5, 3.0, 180);
            s.place(s.enemy2, 0.5, 4.5, 180);
            s.aimAt(s.owner, s.enemy);
            s.clearHits();
            s.assertSucceeded(s.active(), "외눈박이의 말뚝");
            s.ticks(4);
            assertEquals(s.param("stake_damage"), s.damageBy(s.owner, s.enemy), 1.0E-6, "the first enemy");
            assertEquals(0, s.damageBy(s.owner, s.enemy2), EPSILON, "only the first");
            assertTrue(s.enemy.hasPotionEffect(PotionEffectType.BLINDNESS), "blinded");
            assertTrue(s.enemy.getPotionEffect(PotionEffectType.BLINDNESS).getDuration() <= s.paramTicks("stake_blind"),
                    "for the stake's time, not longer than the text says");
            assertFalse(s.enemy.canSee(s.owner), "Nobody is gone from its eyes");
            assertTrue(s.enemy2.canSee(s.owner), "but not from the others'");
            List<TextDisplay> marks = texts(s, "⊘");
            assertEquals(1, marks.size(), "an owner-only mark counts down over the blinded enemy");
            assertTrue(s.owner.canSee(marks.getFirst()) && !s.enemy.canSee(marks.getFirst()));
            s.ticks(s.paramTicks("vanish_duration"));
            assertTrue(s.enemy.canSee(s.owner), "seen again after the time");
            // Hitting the blinded enemy shows Nobody at once.
            s.readyActive();
            s.layout();
            s.place(s.enemy, 0.5, 3.0, 180);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "again");
            s.ticks(4);
            assertFalse(s.enemy.canSee(s.owner));
            s.melee(s.owner, s.enemy, 1);
            assertTrue(s.enemy.canSee(s.owner), "attacking gives Nobody away");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void odysseusStakeThrustsAlongTheAimAndAMissRefunds() {
        try (Scenario s = Scenario.ffa("odysseus")) {
            s.ticks(5);
            s.place(s.owner, 0.5, 0.5, 90);
            s.assertSucceeded(s.active(), "외눈박이의 말뚝");
            s.ticks(1);
            BlockDisplay shaft = entities(s, BlockDisplay.class).stream()
                    .filter(d -> d.getBlock().getMaterial() == Material.STRIPPED_BIRCH_LOG).findFirst().orElseThrow();
            assertUnrotated(shaft, "stake");
            Vector3f along = rotationOf(shaft).transform(new Vector3f(0, 0, 1));
            assertTrue(along.x() < -0.95, "yaw 90 looks west (-x): " + along);
            s.ticks(2);
            assertEquals(s.param("cooldown") * (1 - s.param("miss_refund") / 100), s.ctx().cooldownRemaining(s.owner),
                    0.25, "a miss refunds part of the cooldown");
            assertEquals(0, s.damageBy(s.owner, s.enemy) + s.damageBy(s.owner, s.enemy2), EPSILON);
            s.finishClean();
        }
    }

    /** The owner and the riders: hidden and held at the horse. */
    private static boolean inHorse(Scenario s, Player rider) {
        return !s.enemy.canSee(rider) && !s.enemy2.canSee(rider) && rider.hasPotionEffect(PotionEffectType.INVISIBILITY);
    }

    @Test
    void odysseusTrojanHorseHidesTheCrouchingTeamUntilAnEnemyHitsIt() {
        try (Scenario s = Scenario.teams("odysseus")) {
            s.ticks(5);
            s.place(s.enemy, 0.5, 9.5, 180);
            s.place(s.enemy2, 2.5, 9.5, 180);
            s.ally().setSneaking(true);
            s.aimAtFloor(s.owner, 0.5, 6.5);
            s.assertSucceeded(s.ultimate(), "트로이 목마");
            s.ally().setSneaking(false);
            s.ticks(10);
            assertTrue(inHorse(s, s.owner), "the owner climbed in");
            assertTrue(inHorse(s, s.ally()), "the crouching ally too");
            double apart = s.owner.getLocation().distance(s.ally().getLocation());
            assertTrue(apart >= 0.5 && apart < 1.6, "both in the same horse, each in a seat of its own (riders sharing "
                    + "one spot push each other out and get pulled back): " + apart);
            List<BlockDisplay> parts = entities(s, BlockDisplay.class);
            assertTrue(parts.size() >= 16, "a big wooden horse: " + parts.size());
            for (BlockDisplay part : parts) {
                assertUnrotated(part, "horse");
            }
            assertEquals(2, entities(s, org.bukkit.entity.Interaction.class).size(),
                    "something to hit, front and back along the horse");
            // A nudge inside the hull is left alone (no rubber-banding while teammates push each other).
            Location seat = s.ally().getLocation();
            s.ally().teleport(seat.clone().add(0.5, 0, 0));
            s.ticks(1);
            assertEquals(seat.getX() + 0.5, s.ally().getLocation().getX(), 1.0E-6, "still inside: not pulled back");
            // Riders take no damage; the blow opens the gift.
            s.clearHits();
            s.melee(s.enemy, s.owner, 6);
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "a rider is untouchable");
            s.ticks(2);
            assertFalse(inHorse(s, s.owner), "the door flew open");
            assertTrue(s.enemy.canSee(s.owner) && s.enemy.canSee(s.ally()), "everybody is out");
            assertEquals(s.param("burst_damage"), s.damageBy(s.owner, s.enemy), 1.0E-6, "the burst catches the enemy");
            assertTrue(s.owner.getAttribute(Attribute.MOVEMENT_SPEED).getModifiers().stream()
                    .anyMatch(m -> m.getAmount() > 0), "fresh out: faster");
            s.clearHits();
            s.melee(s.owner, s.enemy, 5);
            assertEquals(5 * (1 + s.param("charge_damage") / 100), s.damageBy(s.owner, s.enemy), 1.0E-6,
                    "and harder");
            s.ticks(30);
            assertTrue(entities(s, BlockDisplay.class).isEmpty(), "the horse fell apart");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void odysseusHorseOpensWhenTouchedOnCrouchOrWhenTimeIsUp() {
        try (Scenario s = Scenario.ffa("odysseus")) {
            s.ticks(5);
            s.place(s.enemy, 0.5, 12.5, 180);
            s.place(s.enemy2, 12.5, 12.5, 180);
            s.aimAtFloor(s.owner, 0.5, 6.5);
            s.assertSucceeded(s.ultimate(), "트로이 목마");
            s.ticks(10);
            assertTrue(inHorse(s, s.owner));
            Location horse = s.owner.getLocation();
            s.place(s.enemy, horse.getX(), horse.getZ() + 1.5, 180);
            s.ticks(2);
            assertFalse(inHorse(s, s.owner), "an enemy touched the gift");

            s.layout();
            s.place(s.enemy, 0.5, 12.5, 180);
            s.place(s.enemy2, 12.5, 12.5, 180);
            s.match.setGauge(s.owner, 100);
            s.ticks(2);
            s.aimAtFloor(s.owner, 0.5, 6.5);
            s.assertSucceeded(s.ultimate(), "again");
            s.ticks(10);
            assertTrue(inHorse(s, s.owner));
            s.owner.setSneaking(true);
            s.ticks(2);
            assertFalse(inHorse(s, s.owner), "crouching opens it");
            s.owner.setSneaking(false);

            s.layout();
            s.place(s.enemy, 0.5, 12.5, 180);
            s.place(s.enemy2, 12.5, 12.5, 180);
            s.match.setGauge(s.owner, 100);
            s.ticks(2);
            s.aimAtFloor(s.owner, 0.5, 6.5);
            s.assertSucceeded(s.ultimate(), "and again");
            s.ticks(s.paramTicks("horse_duration") - 5);
            assertTrue(inHorse(s, s.owner), "it waits");
            s.ticks(8);
            assertFalse(inHorse(s, s.owner), "until the time is up");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void odysseusRidersAreHeldInTheirSeatAndNeverReplayAStaleVelocity() {
        try (Scenario s = Scenario.ffa("odysseus")) {
            s.ticks(5);
            s.place(s.enemy, 0.5, 12.5, 180);
            s.place(s.enemy2, 12.5, 12.5, 180);
            s.aimAtFloor(s.owner, 0.5, 6.5);
            s.assertSucceeded(s.ultimate(), "트로이 목마");
            s.ticks(5);
            Location seat = s.owner.getLocation();
            s.owner.teleport(seat.clone().add(1.5, 0, 0));
            s.owner.setVelocity(new Vector(0.8, 0.2, 0.8));
            s.ticks(2);
            assertTrue(s.owner.getLocation().distance(seat) < 0.35, "back in the seat");
            assertEquals(0, s.owner.getVelocity().getX(), EPSILON, "its momentum is gone");
            assertEquals(FlatWorld.SURFACE, s.owner.getLocation().getY(), 1.0E-6, "feet on the floor");
            s.finishClean();
        }
    }

    // ================================================================== 바쿠 baku

    private static List<BlockDisplay> bubbles(Scenario s) {
        return entities(s, BlockDisplay.class).stream()
                .filter(d -> d.getBlock().getMaterial() == Material.BLACK_STAINED_GLASS).toList();
    }

    @Test
    void bakuEatsCursesAndKeepsThemAsBubbles() {
        try (Scenario s = Scenario.builder("baku").param("eat_chance", 100).start()) {
            s.ticks(5);
            s.owner.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 100, 1));
            assertFalse(s.owner.hasPotionEffect(PotionEffectType.SLOWNESS), "eaten");
            s.owner.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 100, 1));
            assertFalse(s.owner.hasPotionEffect(PotionEffectType.SLOWNESS), "a repeat follows the first roll");
            s.owner.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 100, 0));
            s.owner.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 100, 0));
            s.ticks(3);
            List<BlockDisplay> bubbles = bubbles(s);
            assertEquals(3, bubbles.size(), "one bubble per curse eaten (a repeat is no new nightmare)");
            for (BlockDisplay bubble : bubbles) {
                assertFalse(s.owner.canSee(bubble), "the bubbles never block the baku's own view");
                assertTrue(s.enemy.canSee(bubble), "everyone else sees how many nightmares it holds");
                assertUnrotated(bubble, "bubble");
                assertTrue(bubble.getLocation().getY() > s.owner.getLocation().getY() + s.owner.getHeight());
            }
            s.owner.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 100, 0));
            assertTrue(s.owner.hasPotionEffect(PotionEffectType.SPEED), "good effects are welcome");
            s.finishClean();
        }
        try (Scenario s = Scenario.builder("baku").param("eat_chance", 0).start()) {
            s.ticks(5);
            s.owner.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 100, 1));
            assertTrue(s.owner.hasPotionEffect(PotionEffectType.SLOWNESS), "at 0% nothing is eaten");
            assertTrue(bubbles(s).isEmpty());
            s.finishClean();
        }
    }

    /** Gives {@code player} absorption hearts like a golden apple's (with room for them). */
    private static void absorption(Player player, double amount) {
        player.getAttribute(Attribute.MAX_ABSORPTION).addModifier(new AttributeModifier(
                new org.bukkit.NamespacedKey("test", "absorb"), amount, AttributeModifier.Operation.ADD_NUMBER,
                org.bukkit.inventory.EquipmentSlotGroup.ANY));
        player.setAbsorptionAmount(amount);
    }

    @Test
    void bakuSucksTheGoodDreamsOutOfTheEnemiesInFront() {
        try (Scenario s = Scenario.ffa("baku")) {
            s.ticks(5);
            s.enemy.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 400, 1));
            s.enemy.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 60, 0));
            absorption(s.enemy, 6);
            double health = s.enemy.getHealth();
            s.aimAt(s.owner, s.enemy);
            s.clearHits();
            s.assertSucceeded(s.active(), "꿈 빨아들이기");
            s.ticks(1);
            BlockDisplay segment = entities(s, BlockDisplay.class).stream()
                    .filter(d -> d.getBlock().getMaterial() == Material.PURPLE_TERRACOTTA).findFirst().orElseThrow();
            assertUnrotated(segment, "trunk");
            assertTrue(rotationOf(segment).transform(new Vector3f(0, 0, 1)).z() > 0.6, "the trunk reaches forward");
            s.ticks(8);
            assertEquals(1, s.hitsBy(s.owner, s.enemy).size(), "sucked");
            assertFalse(s.enemy.hasPotionEffect(PotionEffectType.SPEED), "its speed is gone");
            assertFalse(s.enemy.hasPotionEffect(PotionEffectType.STRENGTH), "its strength too");
            PotionEffect speed = s.owner.getPotionEffect(PotionEffectType.SPEED);
            assertNotNull(speed, "the baku has it now");
            assertEquals(1, speed.getAmplifier(), "at its level");
            assertTrue(speed.getDuration() <= s.paramTicks("steal_duration"), "for at most the cap");
            assertNotNull(s.owner.getPotionEffect(PotionEffectType.STRENGTH));
            assertEquals(s.param("steal_absorption"), s.owner.getAbsorptionAmount(), 1.0E-6, "absorption, capped");
            double left = 6 - s.param("steal_absorption");
            double bite = s.param("suck_damage");
            assertEquals(Math.max(0, left - bite), s.enemy.getAbsorptionAmount(), 1.0E-6,
                    "the hearts go first, then the bite lands on what is left");
            assertEquals(health - Math.max(0, bite - left), s.enemy.getHealth(), 1.0E-6);
            assertTrue(bubbles(s).isEmpty(), "something was stolen: no nightmare");
            s.ticks(s.paramTicks("steal_duration") + 5);
            assertTrue(s.owner.getAbsorptionAmount() <= 1.0E-6, "the stolen hearts fade with their room");
            s.enemy.getAttribute(Attribute.MAX_ABSORPTION).removeModifier(new org.bukkit.NamespacedKey("test", "absorb"));
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void bakuCrouchingGivesTheStolenDreamsToTheNearestAlly() {
        try (Scenario s = Scenario.teams("baku")) {
            s.ticks(5);
            s.enemy.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 200, 0));
            s.aimAt(s.owner, s.enemy);
            s.owner.setSneaking(true);
            s.assertSucceeded(s.active(), "꿈 빨아들이기");
            s.owner.setSneaking(false);
            s.ticks(9);
            assertFalse(s.enemy.hasPotionEffect(PotionEffectType.REGENERATION));
            assertTrue(s.ally().hasPotionEffect(PotionEffectType.REGENERATION), "a gift for the ally");
            assertFalse(s.owner.hasPotionEffect(PotionEffectType.REGENERATION));
            s.finishClean();
        }
    }

    @Test
    void bakuEmptyHandedSuckSwallowsANightmareAndAWhiffRefunds() {
        try (Scenario s = Scenario.ffa("baku")) {
            s.ticks(5);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "꿈 빨아들이기");
            s.ticks(12);
            assertEquals(1, bubbles(s).size(), "nothing to steal: a nightmare instead");
            s.readyActive();
            s.place(s.owner, 0.5, 0.5, 0);
            s.place(s.enemy, 0.5, -6.5, 0);
            s.place(s.enemy2, 2.5, -6.5, 0);
            s.assertSucceeded(s.active(), "into thin air");
            s.ticks(10);
            double cooldown = s.param("cooldown");
            assertEquals((cooldown - 7 / 20.0) * (1 - s.param("whiff_refund") / 100) - 3 / 20.0,
                    s.ctx().cooldownRemaining(s.owner), 0.2, "a whiff refunds part of the cooldown");
            assertEquals(1, bubbles(s).size(), "and gains nothing");
            s.finishClean();
        }
    }

    @Test
    void bakuSpewsItsNightmaresAsDistinctCursesAndAnUnbreakableNightmare() {
        try (Scenario s = Scenario.builder("baku").param("eat_chance", 100).start()) {
            s.ticks(5);
            s.owner.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 100, 0));
            s.owner.addPotionEffect(new PotionEffect(PotionEffectType.HUNGER, 100, 0));
            s.owner.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE, 100, 0));
            assertEquals(3, bubbles(s).size() + 0, "three nightmares");
            s.place(s.enemy2, 12.5, 12.5, 180);
            s.aimAt(s.owner, s.enemy);
            s.clearHits();
            s.assertSucceeded(s.ultimate(), "악몽 토해내기");
            assertEquals(0, s.damageBy(s.owner, s.enemy), EPSILON, "telegraphed first");
            s.ticks(s.paramTicks("spew_windup") + 2);
            assertEquals(s.param("nightmare_damage") * 3, s.damageBy(s.owner, s.enemy), 1.0E-6, "1.5 per nightmare");
            int curses = 0;
            for (PotionEffectType type : List.of(PotionEffectType.SLOWNESS, PotionEffectType.WEAKNESS,
                    PotionEffectType.NAUSEA, PotionEffectType.POISON, PotionEffectType.BLINDNESS)) {
                curses += s.enemy.hasPotionEffect(type) ? 1 : 0;
            }
            assertEquals(3, curses, "one distinct curse per nightmare");
            s.enemy.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 100, 0));
            assertFalse(s.enemy.hasPotionEffect(PotionEffectType.SPEED), "an unbreakable nightmare: no good dreams");
            s.ticks(3);
            assertTrue(bubbles(s).isEmpty(), "the bubbles are spent");
            s.ticks(s.paramTicks("nightmare_lock"));
            s.enemy.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 100, 0));
            assertTrue(s.enemy.hasPotionEffect(PotionEffectType.SPEED), "the nightmare broke");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void bakuSpewsItsOwnCursesOutWithoutNightmares() {
        try (Scenario s = Scenario.builder("baku").param("eat_chance", 0).start()) {
            s.ticks(5);
            s.owner.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, 400, 0));
            s.place(s.enemy2, 12.5, 12.5, 180);
            s.aimAt(s.owner, s.enemy);
            s.clearHits();
            s.assertSucceeded(s.ultimate(), "악몽 토해내기");
            s.ticks(s.paramTicks("spew_windup") + 2);
            assertEquals(s.param("empty_damage"), s.damageBy(s.owner, s.enemy), 1.0E-6, "no nightmares: a plain blast");
            PotionEffect slow = s.enemy.getPotionEffect(PotionEffectType.SLOWNESS);
            assertNotNull(slow);
            assertEquals(1, slow.getAmplifier(), "구속 II");
            assertFalse(s.owner.hasPotionEffect(PotionEffectType.WITHER), "the baku is clean");
            PotionEffect passed = s.enemy.getPotionEffect(PotionEffectType.WITHER);
            assertNotNull(passed, "its curse went to the enemy");
            assertTrue(passed.getDuration() <= s.paramTicks("curse_duration"), "for at most the curse time");
            s.finishClean();
        }
    }

    @Test
    void bakuSpewNeedsSomebodyInFront() {
        try (Scenario s = Scenario.ffa("baku")) {
            s.ticks(5);
            s.place(s.owner, 0.5, 0.5, 180);
            assertFalse(s.ultimate().succeeded(), "nobody in the cone");
            assertEquals(100, s.ctx().gauge(s.owner), EPSILON);
            s.finishClean();
        }
    }
    // ================================================================== real-server rules

    /** The middle of a block display's (scaled, rotated) block, in world coordinates. */
    private static Vector centerOf(BlockDisplay display) {
        Transformation t = display.getTransformation();
        Vector3f half = rotationOf(display).transform(new Vector3f(t.getScale()).mul(0.5f));
        Vector3f translation = t.getTranslation();
        return display.getLocation().toVector().add(new Vector(translation.x() + half.x(),
                translation.y() + half.y(), translation.z() + half.z()));
    }

    @Test
    void odysseusHorseFacesTheWayTheOwnerLooked() {
        try (Scenario s = Scenario.ffa("odysseus")) {
            s.ticks(5);
            s.place(s.enemy, 12.5, -12.5, 180);
            s.place(s.enemy2, 12.5, 12.5, 180);
            s.place(s.owner, 0.5, 0.5, 90);
            s.aimAtFloor(s.owner, -5.5, 0.5);
            s.assertSucceeded(s.ultimate(), "트로이 목마");
            s.ticks(12);
            Location spot = s.owner.getLocation();
            assertEquals(FlatWorld.SURFACE, spot.getY(), 1.0E-6, "the riders stand on the floor");
            assertTrue(io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Spots.isFree(s.world, spot, 0.6, 1.8),
                    "in a free box (the horse is made of displays)");
            // The head is the hull part highest up; it must lie on the side the owner looked (west, -x).
            BlockDisplay head = entities(s, BlockDisplay.class).stream()
                    .filter(d -> d.getBlock().getMaterial() == Material.OAK_PLANKS)
                    .max(java.util.Comparator.comparingDouble(d -> centerOf(d).getY())).orElseThrow();
            Vector offset = centerOf(head).subtract(spot.toVector());
            assertTrue(offset.getX() < -1.0, "the head is in front, toward -x: " + offset);
            assertEquals(0, offset.getZ(), 0.2, "and not off to the side: " + offset);
            // The hitboxes follow the turned horse: one in front, one behind, along -x.
            List<org.bukkit.entity.Interaction> boxes = entities(s, org.bukkit.entity.Interaction.class);
            assertEquals(2, boxes.size());
            for (org.bukkit.entity.Interaction box : boxes) {
                Vector from = box.getLocation().toVector().subtract(spot.toVector());
                assertEquals(0, from.getZ(), 0.3, "along the horse, not across it: " + from);
                assertTrue(Math.abs(from.getX()) > 0.3, "front or back: " + from);
            }
            s.finishClean();
        }
    }

    @Test
    void mimicGrabNeverReplaysAStaleVelocityAndThePileLandsOnTheFloor() {
        try (Scenario s = Scenario.ffa("mimic")) {
            s.ticks(5);
            Block chest = disguised(s);
            s.enemy.setVelocity(new Vector(0.7, 0.1, -0.6));
            open(s, s.enemy, chest);
            assertEquals(0, s.enemy.getVelocity().getX(), EPSILON, "held: its momentum is gone");
            assertEquals(0, s.enemy.getVelocity().getZ(), EPSILON);
            s.ticks(s.paramTicks("disguise_grab") + 5);

            s.place(s.enemy, 0.5, 12.5, 180);
            s.place(s.enemy2, 12.5, 12.5, 180);
            s.assertSucceeded(s.ultimate(), "보물 무더기");
            s.ticks(2);
            assertEquals(FlatWorld.SURFACE, s.owner.getLocation().getY(), 1.0E-6, "the mimic slips onto the floor");
            assertTrue(s.ctx().isInsideArena(s.owner.getLocation()));
            for (BlockDisplay part : entities(s, BlockDisplay.class)) {
                assertUnrotated(part, "pile");
            }
            for (ItemDisplay flying : entities(s, ItemDisplay.class)) {
                assertUnrotated(flying, "flying chest");
            }
            s.finishClean();
        }
    }

    @Test
    void erisThrownAppleAndSeedsAreUnrotated() {
        try (Scenario s = Scenario.ffa("eris")) {
            s.ticks(5);
            s.place(s.owner, 0.5, 0.5, 135);
            s.aimAtFloor(s.owner, -6.5, -6.5);
            s.assertSucceeded(s.active(), "불화의 사과");
            for (int i = 0; i < 20; i++) {
                s.ticks(1);
                for (ItemDisplay apple : entities(s, ItemDisplay.class)) {
                    assertUnrotated(apple, "apple");
                }
            }
            s.finishClean();
        }
    }
    // ================================================================== the runtime hooks

    @Test
    void contextRestoresAnAbilitysOwnTemporaryBlockEarly() {
        try (Scenario s = Scenario.ffa("eris")) {
            s.ticks(5);
            Block block = s.world.getBlockAt(5, FlatWorld.FLOOR_Y + 1, 5);
            assertTrue(s.ctx().placeTempBlock(block, Material.CHEST.createBlockData(), 2000));
            assertEquals(Material.CHEST, block.getType());
            assertTrue(s.ctx().restoreTempBlock(block), "restored before its time");
            assertEquals(Material.AIR, block.getType());
            assertEquals(0, s.h.hub.tempBlocks().size());
            assertFalse(s.ctx().restoreTempBlock(block), "only temporary blocks");
            s.finishClean();
        }
    }

    @Test
    void odysseusAliasOnlyShowsWhileTheFightRuns() {
        try (Scenario s = Scenario.ffa("odysseus")) {
            s.ticks(5);
            assertEquals("아무도", Scenario.plain(s.session.publicName(s.owner.getUniqueId())),
                    "Nobody in the public lines while the fight runs");
            assertEquals("Owner", Scenario.plain(s.session.name(s.owner)),
                    "but only there: the team's HUD, the summaries and every other line keep the real name");
            assertEquals("Red", Scenario.plain(s.session.publicName(s.enemy.getUniqueId())),
                    "everybody else keeps their name");
            s.finishMatch();
            assertEquals("Owner", Scenario.plain(s.session.publicName(s.owner.getUniqueId())),
                    "the results show the real name");
            s.assertNoProblems();
            s.assertCleanWorld();
        }
    }

    @Test
    void odysseusNameTagIsHiddenInAFreeForAllToo() {
        try (Scenario s = Scenario.ffa("odysseus")) {
            s.ticks(25);
            org.bukkit.scoreboard.Team team = s.enemy.getScoreboard().getEntryTeam(s.owner.getName());
            assertNotNull(team);
            assertEquals(org.bukkit.scoreboard.Team.OptionStatus.NEVER,
                    team.getOption(org.bukkit.scoreboard.Team.Option.NAME_TAG_VISIBILITY));
            assertTrue(s.owner.getScoreboard().getEntryTeam(s.owner.getName()) == null
                    || s.owner.getScoreboard() != s.enemy.getScoreboard(), "the owner's own board is untouched");
            s.eliminateOwner(null);
            s.ticks(1);
            assertNull(s.enemy.getScoreboard().getEntryTeam(s.owner.getName()), "the tag is back");
            s.finishClean();
        }
    }

    // ================================================================== review fixes: real client sequences

    @Test
    void odysseusCrouchingNeverMakesEveryoneAGiant() {
        try (Scenario s = Scenario.ffa("odysseus")) {
            s.ticks(5);
            // A crouching body is 1.5 tall, a standing one 1.8: 1.8 >= 1.5 x 1.15 must not make a normal enemy a giant.
            s.owner.setSneaking(true);
            s.clearHits();
            s.melee(s.owner, s.enemy, 4);
            assertEquals(4, s.damageBy(s.owner, s.enemy), 1.0E-6, "crouch-hitting a normal-sized enemy: no bonus");
            s.owner.setSneaking(false);
            s.enemy.setSneaking(true);
            s.enemy.getAttribute(Attribute.SCALE).addModifier(new AttributeModifier(
                    new org.bukkit.NamespacedKey("test", "giant"), 0.3, AttributeModifier.Operation.ADD_NUMBER,
                    org.bukkit.inventory.EquipmentSlotGroup.ANY));
            s.clearHits();
            s.melee(s.owner, s.enemy, 4);
            assertEquals(4 * (1 + s.param("giant_bonus") / 100), s.damageBy(s.owner, s.enemy), 1.0E-6,
                    "a giant is a giant even while it crouches");
            s.enemy.getAttribute(Attribute.SCALE).removeModifier(new org.bukkit.NamespacedKey("test", "giant"));
            s.enemy.setSneaking(false);
            s.finishClean();
        }
    }

    private static void removeArrows(Scenario s) {
        for (Arrow arrow : entities(s, Arrow.class)) {
            arrow.remove();
        }
    }

    @Test
    void odysseusLeftoverArrowsNeverOpenTheHorse() {
        try (Scenario s = Scenario.ffa("odysseus")) {
            s.ticks(5);
            s.place(s.enemy, 0.5, 12.5, 180);
            s.place(s.enemy2, 12.5, 12.5, 180);
            // An enemy's arrow from earlier lies in the floor right where the gift will stand.
            Arrow lying = s.world.spawnArrow(new Location(s.world, 0.5, FlatWorld.SURFACE + 3, 6.0),
                    new Vector(0, -1, 0), 1.0f, 0f);
            lying.setShooter(s.enemy);
            s.ticks(20);
            assertTrue(lying.isInBlock(), "it stuck in the floor");
            s.aimAtFloor(s.owner, 0.5, 6.5);
            s.assertSucceeded(s.ultimate(), "트로이 목마");
            s.ticks(10);
            assertTrue(inHorse(s, s.owner), "an arrow lying in the ground is no hit: the gift stays shut");
            // A flying one is.
            Arrow shot = s.world.spawnArrow(new Location(s.world, 0.5, FlatWorld.SURFACE + 2, 11.5),
                    new Vector(0, 0, -1), 1.5f, 0f);
            shot.setShooter(s.enemy);
            s.ticks(6);
            assertFalse(inHorse(s, s.owner), "an arrow flying into the gift opens it");
            removeArrows(s);
            s.finishClean();
        }
    }

    @Test
    void odysseusRidingAllyGetsOffByCrouchingAgain() {
        try (Scenario s = Scenario.teams("odysseus")) {
            s.ticks(5);
            s.place(s.enemy, 0.5, 12.5, 180);
            s.place(s.enemy2, 12.5, 12.5, 180);
            s.ally().setSneaking(true);
            s.aimAtFloor(s.owner, 0.5, 6.5);
            s.assertSucceeded(s.ultimate(), "트로이 목마");
            s.ticks(10);
            assertTrue(inHorse(s, s.ally()), "the ally crouching for its own reasons was taken in");
            assertTrue(rooted(s.ally()));
            // Still holding the same crouch does nothing; a fresh one lets it off on its own.
            s.ally().setSneaking(false);
            s.ticks(2);
            s.ally().setSneaking(true);
            s.ticks(2);
            assertFalse(inHorse(s, s.ally()), "a fresh crouch gets the ally off");
            assertTrue(s.enemy.canSee(s.ally()), "seen again");
            assertFalse(rooted(s.ally()), "free to move");
            assertTrue(inHorse(s, s.owner), "the gift stays shut for the others");
            s.ticks(10);
            assertFalse(inHorse(s, s.ally()), "crouching by the horse it does not climb back in");
            s.ally().setSneaking(false);
            s.ticks(s.paramTicks("horse_duration"));
            assertFalse(inHorse(s, s.owner), "the time is up");
            assertTrue(s.owner.getAttribute(Attribute.MOVEMENT_SPEED).getModifiers().stream()
                    .anyMatch(m -> m.getAmount() > 0), "the riders burst out charged");
            assertFalse(s.ally().getAttribute(Attribute.MOVEMENT_SPEED).getModifiers().stream()
                    .anyMatch(m -> m.getAmount() > 0), "the one who got off early is not");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void odysseusAllyTooBigForTheHorseStaysOut() {
        try (Scenario s = Scenario.teams("odysseus")) {
            s.ticks(5);
            s.place(s.enemy, 0.5, 12.5, 180);
            s.place(s.enemy2, 12.5, 12.5, 180);
            // A low roof over the gift's spot: room for Nobody's body, not for a giant ally's.
            for (int x = -1; x <= 2; x++) {
                for (int z = 4; z <= 8; z++) {
                    s.world.setBlock(x, FlatWorld.FLOOR_Y + 3, z, Material.STONE);
                }
            }
            org.bukkit.NamespacedKey big = new org.bukkit.NamespacedKey("test", "big");
            s.ally().getAttribute(Attribute.SCALE).addModifier(new AttributeModifier(big, 1.0,
                    AttributeModifier.Operation.ADD_NUMBER, org.bukkit.inventory.EquipmentSlotGroup.ANY));
            s.ally().setSneaking(true);
            s.aimAtFloor(s.owner, 0.5, 6.5);
            s.assertSucceeded(s.ultimate(), "트로이 목마");
            s.ticks(10);
            assertTrue(inHorse(s, s.owner), "Nobody fits");
            assertFalse(inHorse(s, s.ally()), "the giant ally does not");
            assertFalse(rooted(s.ally()), "and is not held");
            assertFalse(FlatWorld.isIn(s.ally(), Material.STONE), "never pinned inside the roof");
            s.ally().setSneaking(false);
            s.ally().getAttribute(Attribute.SCALE).removeModifier(big);
            s.ticks(s.paramTicks("horse_duration") + 30);
            for (int x = -1; x <= 2; x++) {
                for (int z = 4; z <= 8; z++) {
                    s.world.setBlock(x, FlatWorld.FLOOR_Y + 3, z, Material.AIR);
                }
            }
            s.finishClean();
        }
    }

    @Test
    void mimicHeldRightClickBitesOnlyOnce() {
        try (Scenario s = Scenario.ffa("mimic")) {
            s.ticks(5);
            Block chest = disguised(s);
            s.clearHits();
            open(s, s.enemy, chest);
            s.ticks(1);
            open(s, s.enemy, chest); // a quick double click
            s.ticks(3);
            open(s, s.enemy, chest); // the client repeats the use every 4 ticks while the button is held
            assertEquals(s.param("disguise_bite"), s.damageBy(s.owner, s.enemy), 1.0E-6, "one disguise, one bite");
            assertEquals(1, s.hitsBy(s.owner, s.enemy).size());
            s.ticks(s.paramTicks("disguise_grab") + 5);
            // A bait the same.
            s.place(s.enemy, 12.5, 12.5, 180);
            s.place(s.enemy2, -12.5, 12.5, 180);
            s.aimAtFloor(s.owner, -3.5, 6.5);
            s.assertSucceeded(s.active(), "미끼 상자");
            s.ticks(20);
            Block bait = chestsAway(s).getFirst();
            s.clearHits();
            for (int i = 0; i < 3; i++) {
                open(s, s.enemy2, bait);
                s.ticks(4);
            }
            assertEquals(s.param("bait_bite"), s.damageBy(s.owner, s.enemy2), 1.0E-6, "one bait, one bite");
            punch(s, s.enemy2, bait);
            assertEquals(s.param("bait_bite"), s.damageBy(s.owner, s.enemy2), 1.0E-6,
                    "nor a snap on top while its lid shuts");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void mimicSwingAtSomebodyInFrontOfAChestNeverPunchesTheChest() {
        try (Scenario s = Scenario.ffa("mimic")) {
            s.ticks(5);
            s.place(s.enemy2, 0.5, 2.5, 180);
            s.place(s.enemy, 0.5, 4.0, 180);
            Block chest = disguised(s);
            Location aim = chest.getLocation().add(0.5, 0.5, 0.5);
            // The enemy swings at the player standing between it and the chest (every punch swings the arm).
            s.aimAt(s.enemy, aim);
            s.h.server.getPluginManager().callEvent(new PlayerArmSwingEvent(s.enemy, EquipmentSlot.HAND));
            assertFalse(s.enemy.canSee(s.owner), "the blow is the player's: the chest is untouched");
            assertEquals(Material.CHEST, chest.getType());
            // With nobody in the way the punch finds the chest.
            s.place(s.enemy2, 8.5, 2.5, 180);
            s.aimAt(s.enemy, aim);
            s.h.server.getPluginManager().callEvent(new PlayerArmSwingEvent(s.enemy, EquipmentSlot.HAND));
            assertTrue(s.enemy.canSee(s.owner), "a punch at the chest itself gives the mimic away");
            s.finishClean();
        }
    }

    @Test
    void mimicShootingFromInsideTheChestBreaksTheDisguise() {
        try (Scenario s = Scenario.ffa("mimic")) {
            s.ticks(5);
            disguised(s);
            // A bow shot sends no punch: the launch itself gives the mimic away.
            Arrow loosed = s.world.spawnArrow(s.owner.getEyeLocation().add(0, 0, 0.5), new Vector(0, 0, 1), 2.0f, 0f);
            loosed.setShooter(s.owner);
            s.h.server.getPluginManager().callEvent(new ProjectileLaunchEvent(loosed));
            assertTrue(s.enemy.canSee(s.owner), "shooting is attacking");
            removeArrows(s);
            // An arrow that hits gives it away as much as a fist.
            disguised(s);
            Location eye = s.owner.getEyeLocation();
            Vector toward = s.enemy.getLocation().add(0, 1.0, 0).toVector().subtract(eye.toVector()).normalize();
            Arrow arrow = s.world.spawnArrow(eye.clone().add(toward.clone().multiply(0.6)), toward, 2.0f, 0f);
            arrow.setShooter(s.owner);
            s.clearHits();
            s.ticks(5);
            assertTrue(s.damageBy(s.owner, s.enemy) > 0, "the arrow hit");
            assertTrue(s.enemy.canSee(s.owner), "an arrow from inside the chest gives the mimic away");
            removeArrows(s);
            s.finishClean();
        }
    }

    @Test
    void mimicNeverTakesAwayATemporaryBlockThatIsNoLongerItsChest() {
        try (Scenario s = Scenario.ffa("mimic")) {
            s.ticks(5);
            s.place(s.enemy, 12.5, 12.5, 180);
            s.place(s.enemy2, -12.5, 12.5, 180);
            s.aimAtFloor(s.owner, -3.5, 6.5);
            s.assertSucceeded(s.active(), "미끼 상자");
            s.ticks(20);
            Block bait = chestsAway(s).getFirst();
            // Its block comes back (as when its time is up) and another ability puts its own temporary block there.
            assertTrue(s.ctx().restoreTempBlock(bait));
            assertTrue(s.ctx().placeTempBlock(bait, Material.ICE.createBlockData(), 2000));
            s.clearHits();
            punch(s, s.enemy, bait);
            assertEquals(0, s.damageBy(s.owner, s.enemy), EPSILON, "ice is no bait");
            s.ticks(s.paramTicks("bait_duration") + 10);
            assertEquals(Material.ICE, bait.getType(), "the mimic only ever takes its own chest away");
            assertTrue(s.ctx().restoreTempBlock(bait));
            s.finishClean();
        }
    }

    @Test
    void privateDisplaysStayHiddenFromWhoeverJoinsLater() {
        try (Scenario s = Scenario.teams("mimic")) {
            s.ticks(5);
            s.place(s.enemy, 0.5, 9.5, 180);
            s.place(s.enemy2, 9.5, 9.5, 180);
            s.assertSucceeded(s.ultimate(), "보물 무더기");
            s.ticks(14);
            List<TextDisplay> markers = entities(s, TextDisplay.class).stream()
                    .filter(text -> Scenario.plain(text.text()).matches(".*(보물|가짜|나).*")).toList();
            assertFalse(markers.isEmpty());
            TestPlayer late = s.h.join("Late");
            s.ticks(1);
            for (TextDisplay marker : markers) {
                assertFalse(late.canSee(marker), "a player who joins later (a rejoining enemy) never reads the pile");
            }
            s.finishClean();
        }
        try (Scenario s = Scenario.ffa("odysseus")) {
            s.ticks(5);
            s.place(s.enemy, 0.5, 3.0, 180);
            s.place(s.enemy2, 0.5, 4.5, 180);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "외눈박이의 말뚝");
            s.ticks(4);
            TextDisplay mark = texts(s, "⊘").getFirst();
            TestPlayer late = s.h.join("Late");
            s.ticks(1);
            assertFalse(late.canSee(mark), "nor Nobody's mark over its blinded enemy");
            s.finishClean();
        }
        try (Scenario s = Scenario.ffa("satori")) {
            lookAway(s);
            s.ticks(20);
            s.aimAt(s.enemy, s.owner);
            s.ticks(12);
            TextDisplay balloon = privateBalloons(s).getFirst();
            TestPlayer late = s.h.join("Late");
            s.ticks(1);
            assertFalse(late.canSee(balloon), "nor the satori's thought balloon");
            s.finishClean();
        }
    }

    @Test
    void bakuBlindingNightmareLastsExactlyItsTime() {
        try (Scenario s = Scenario.builder("baku").param("eat_chance", 100).param("max_nightmares", 5).start()) {
            s.ticks(5);
            for (PotionEffectType curse : List.of(PotionEffectType.SLOWNESS, PotionEffectType.HUNGER,
                    PotionEffectType.MINING_FATIGUE, PotionEffectType.WEAKNESS, PotionEffectType.POISON)) {
                s.owner.addPotionEffect(new PotionEffect(curse, 100, 0));
            }
            assertEquals(5, bubbles(s).size(), "five nightmares: every curse goes out");
            s.place(s.enemy2, 12.5, 12.5, 180);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "악몽 토해내기");
            s.ticks(s.paramTicks("spew_windup") + 2);
            PotionEffect blind = s.enemy.getPotionEffect(PotionEffectType.BLINDNESS);
            assertNotNull(blind, "the blinding nightmare is among them");
            assertTrue(blind.getDuration() <= s.paramTicks("curse_blind"), "실명 " + s.param("curse_blind")
                    + "초 as the text says: " + blind.getDuration());
            s.finishClean();
        }
    }
}
