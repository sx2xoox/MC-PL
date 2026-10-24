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

import io.github.sx2xoox.mcpl.game.superpower.SuperpowerSettings;
import io.github.sx2xoox.mcpl.game.superpower.abilities.ArcadeAbility;
import io.github.sx2xoox.mcpl.game.superpower.abilities.IsekaiTruckAbility;
import io.github.sx2xoox.mcpl.game.superpower.abilities.LevelerAbility;
import io.github.sx2xoox.mcpl.game.superpower.abilities.StomperAbility;
import io.github.sx2xoox.mcpl.game.superpower.abilities.TamerAbility;
import io.github.sx2xoox.mcpl.game.superpower.ability.Ability;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import io.github.sx2xoox.mcpl.game.superpower.ability.ActivationResult;
import io.github.sx2xoox.mcpl.game.superpower.ability.InputKey;
import io.github.sx2xoox.mcpl.game.superpower.support.Scenario;
import io.github.sx2xoox.mcpl.game.superpower.support.TestInput;
import io.github.sx2xoox.mcpl.game.superpower.support.TestPlayer;
import io.github.sx2xoox.mcpl.game.superpower.support.world.FlatWorld;
import net.kyori.adventure.bossbar.BossBar;
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
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 게임 속 세상 (new): 격겜 고수, 밟기 달인, 몬스터 수집가, 레벨업 헌터, 이세계 트럭. Each ability through a whole match,
 * the owner dying in the middle of each skill, team mode with an ally in the line of fire, the Gambler borrowing the
 * standalone ultimates, and the key mechanics of every passive, active and ultimate (with the real-server rules:
 * unrotated displays pointing the right way, exact landings, nothing revealing a hidden player).
 */
class GamerNewAbilitiesTest {

    private static final double EPSILON = 1.0E-6;
    private static final double Y = FlatWorld.SURFACE;
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z0-9_]+)}");

    static Stream<String> abilities() {
        return Stream.of("arcade", "stomper", "tamer", "leveler", "isekaitruck");
    }

    /** The ultimates the gambler may borrow (the others rely on the combo, the stomps and the levels). */
    static Stream<String> standalone() {
        return Stream.of("tamer", "isekaitruck");
    }

    /** Displays an ability keeps for its passive the whole match. */
    static int passiveDisplays(String id) {
        return id.equals("leveler") ? 1 : 0;
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

    static <T extends Entity> List<T> entities(Scenario s, Class<T> type) {
        return s.world.getEntities().stream().filter(type::isInstance).map(type::cast).toList();
    }

    static void assertModifierFree(Player player, String what) {
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

    static List<AttributeModifier> modifiers(Player player, Attribute attribute) {
        return player.getAttribute(attribute).getModifiers().stream()
                .filter(modifier -> !modifier.getKey().equals(Scenario.TANK)).toList();
    }

    static boolean slowed(Player player) {
        return modifiers(player, Attribute.MOVEMENT_SPEED).stream().anyMatch(modifier -> modifier.getAmount() < 0);
    }

    static void assertAllUnrotated(Scenario s, String what) {
        for (Display display : entities(s, Display.class)) {
            assertEquals(0, display.getLocation().getYaw(), 1.0E-3, what + ": " + display.getType() + " yaw");
            assertEquals(0, display.getLocation().getPitch(), 1.0E-3, what + ": " + display.getType() + " pitch");
        }
    }

    static void assertOnlyPassiveDisplays(Scenario s, String what) {
        assertEquals(passiveDisplays(s.abilityId), entities(s, Display.class).size(), what
                + ": only the passive's displays are left: " + entities(s, Display.class));
    }

    static List<TextDisplay> texts(Scenario s, String containing) {
        return entities(s, TextDisplay.class).stream()
                .filter(text -> Scenario.plain(text.text()).contains(containing)).toList();
    }

    static List<BlockDisplay> blocks(Scenario s, Material type) {
        return entities(s, BlockDisplay.class).stream().filter(d -> d.getBlock().getMaterial() == type).toList();
    }

    /**
     * The left rotation a display's transformation was set with. Older MockBukkit hands it back conjugated, a faithful
     * test double does not: a probe display tells which, so the check holds with either.
     */
    static Quaternionf leftRotation(Display display) {
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

    static Vector rotated(Display display, float x, float y, float z) {
        Vector3f turned = leftRotation(display).transform(new Vector3f(x, y, z));
        return new Vector(turned.x(), turned.y(), turned.z());
    }

    static void wall(Scenario s, int x1, int x2, int z, int height) {
        s.world.wallAlongX(x1, x2, z, height, Material.STONE);
    }

    static void clearWall(Scenario s, int x1, int x2, int z, int height) {
        s.world.wallAlongX(x1, x2, z, height, Material.AIR);
    }

    static Scenario gambler(boolean teams) {
        Scenario.Builder builder = Scenario.builder("gambler").param("dodge_chance", 0).param("crit_chance", 0);
        return (teams ? builder.teams() : builder).start();
    }

    /** Exactly what {@code GamblerAbility#fireBorrowed} does. */
    static ActivationResult borrow(Scenario s, String id) {
        Ability borrowed = s.ctx().registry().create(id);
        borrowed.bind(s.owner, s.ctx(), s.ctx().paramsFor(id));
        return borrowed.onUltimate();
    }

    /** A scenario in which nobody's gauge fills by itself (damage dealt or taken, time). */
    static Scenario noGaugeGain(String id) {
        return Scenario.builder(id).configure(h -> {
            h.settings().set(SuperpowerSettings.GAUGE_DEALT, 0);
            h.settings().set(SuperpowerSettings.GAUGE_TAKEN, 0);
            h.settings().set(SuperpowerSettings.GAUGE_SECOND, 0);
        }).start();
    }

    /** What each ability needs to use its active on the aimed enemy in the default layout. */
    static void prepareActive(Scenario s) {
        s.aimAt(s.owner, s.enemy);
    }

    // ================================================================== every ability

    @Test
    void descriptionsOnlyReferenceDeclaredParams() {
        for (AbilityInfo info : List.of(ArcadeAbility.INFO, StomperAbility.INFO, TamerAbility.INFO, LevelerAbility.INFO,
                IsekaiTruckAbility.INFO)) {
            for (AbilityInfo.Skill skill : List.of(info.passive(), info.active(), info.ultimate())) {
                assertFalse(skill.name().equals("TODO"), info.id() + " is implemented");
                Matcher matcher = PLACEHOLDER.matcher(skill.description());
                while (matcher.find()) {
                    assertTrue(info.params().containsKey(matcher.group(1)),
                            info.id() + " " + skill.name() + " uses an undeclared param " + matcher.group());
                }
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("abilities")
    void wholeMatch(String id) {
        try (Scenario s = Scenario.ffa(id)) {
            s.ticks(60);
            s.assertNoProblems();

            s.layout();
            prepareActive(s);
            s.clearHits();
            s.assertSucceeded(s.active(), id + " active");
            for (int i = 0; i < 100; i++) {
                s.ticks(1);
                if (i % 10 == 0) {
                    assertAllUnrotated(s, id + " active tick " + i);
                }
            }
            assertTrue(s.damageBy(s.owner, s.enemy) > 0, id + " active hurts the aimed enemy: " + s.hits());
            assertEquals(0, s.damageTaken(s.owner), EPSILON, id + " active never hurts its owner: " + s.hits());

            s.layout();
            s.healAll();
            s.aimAt(s.owner, s.enemy);
            s.clearHits();
            s.assertSucceeded(s.ultimate(), id + " ultimate");
            for (int i = 0; i < 300; i++) {
                s.ticks(1);
                if (i % 10 == 0) {
                    assertAllUnrotated(s, id + " ultimate tick " + i);
                }
            }
            // The raid, the candy and the round hurt nobody by themselves (their own tests cover them).
            if (standalone().anyMatch(id::equals)) {
                assertTrue(s.damageBy(s.owner, s.enemy) + s.damageBy(s.owner, s.enemy2) > 0, id + " ultimate hurts: "
                        + s.hits());
            }
            assertEquals(0, s.damageTaken(s.owner), EPSILON, id + " ultimate never hurts its owner: " + s.hits());
            s.assertNoProblems();
            assertOnlyPassiveDisplays(s, id);
            for (TestPlayer enemy : s.enemies()) {
                assertModifierFree(enemy, id);
                assertFalse(enemy.hasPotionEffect(PotionEffectType.INVISIBILITY), id + ": " + enemy.getName()
                        + " is visible again");
            }
            assertEquals(0, s.h.hub.tempBlocks().size(), id + ": no temporary blocks");
            s.finishClean();
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
            AbilityLifecycleTest.watchAlly(s, allyStart, 100);
            assertTrue(s.damageBy(s.owner, s.enemy) > 0, id + " still hits the enemy next to the ally: " + s.hits());

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
            prepareActive(s);
            s.assertSucceeded(s.active(), id + " active");
            s.ticks(6);
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
            s.ticks(25);
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
        for (TestPlayer enemy : s.enemies()) {
            assertFalse(enemy.hasPotionEffect(PotionEffectType.INVISIBILITY), id + ": " + enemy.getName()
                    + " is out of every capsule");
            assertTrue(raidBars(enemy).isEmpty(), id + ": no raid boss bar is left on " + enemy.getName());
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
        }
        assertModifierFree(s.owner, id);
        s.assertNoProblems();
        s.finishClean();
    }

    // ================================================================== borrowed by the gambler

    @ParameterizedTest(name = "{0}")
    @MethodSource("abilities")
    void onlyTheStandaloneUltimatesAreBorrowable(String id) {
        try (Scenario s = gambler(false)) {
            boolean expected = standalone().anyMatch(id::equals);
            assertEquals(expected, s.ctx().registry().get(id).info().ultimateStandalone(), id);
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
            assertTrue(worked, "the borrowed " + id + " ultimate does its thing: " + s.hits());
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "the borrowed " + id + " never hurts the gambler");
            assertModifierFree(s.owner, id);
            for (TestPlayer target : s.enemies()) {
                assertModifierFree(target, id);
                assertFalse(target.hasPotionEffect(PotionEffectType.INVISIBILITY), id + ": visible again");
            }
            assertTrue(entities(s, Display.class).isEmpty(), id + ": every display is gone");
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
            s.ticks(32);
            s.killOwner(s.enemy);
            s.ticks(1);
            for (Entity entity : s.world.getEntities()) {
                assertTrue(entity instanceof Player, id + ": " + entity.getType() + " outlived the gambler");
            }
            for (TestPlayer target : s.enemies()) {
                assertFalse(target.hasPotionEffect(PotionEffectType.INVISIBILITY), id + ": out of the capsule");
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

    // ================================================================== 레벨업 헌터 leveler

    static LevelerAbility leveler(Scenario s) {
        return (LevelerAbility) s.ability();
    }

    @Test
    void levelerLevelsUpFromDamageHealsHitsHarderAndShowsItsLevelToEveryone() {
        try (Scenario s = Scenario.ffa("leveler")) {
            s.ticks(5);
            LevelerAbility ability = leveler(s);
            assertEquals(1, ability.level());
            List<TextDisplay> tags = texts(s, "Lv.1");
            assertEquals(1, tags.size(), "the level over the head");
            assertTrue(s.seenBy(tags.getFirst()).containsAll(s.all()), "everyone reads it");

            s.owner.setHealth(10);
            s.melee(s.owner, s.enemy, s.param("xp_per_level"));
            s.ticks(2);
            assertEquals(2, ability.level(), "ten damage is a level");
            assertEquals(10 + s.param("level_heal"), s.owner.getHealth(), EPSILON, "a level-up heals");
            assertEquals(1, texts(s, "Lv.2").stream().filter(text -> !Scenario.plain(text.text()).contains("시스템"))
                    .count(), "the sign shows the new level");
            assertEquals(1, texts(s, "레벨이 올랐습니다").size(), "a status window announces it");
            assertEquals(1, modifiers(s.owner, Attribute.MOVEMENT_SPEED).size(), "faster");
            assertEquals(s.param("level_speed") / 100.0, modifiers(s.owner, Attribute.MOVEMENT_SPEED).getFirst()
                    .getAmount(), EPSILON);

            s.clearHits();
            s.melee(s.owner, s.enemy2, 5);
            assertEquals(5 * (1 + s.param("level_damage") / 100.0), s.damageBy(s.owner, s.enemy2), EPSILON,
                    "each level hits harder");

            // The cap.
            s.healAll();
            s.melee(s.owner, s.enemy, 60);
            s.ticks(2);
            assertEquals((int) s.param("max_level"), ability.level(), "never past the top level");
            assertEquals(0, ability.xp(), EPSILON, "no experience piles up at the top");
            s.finishClean();
        }
    }

    @Test
    void levelerSlashGrowsFromAForwardCutToAFullCircle() {
        try (Scenario s = Scenario.ffa("leveler")) {
            s.ticks(5);
            s.place(s.enemy, 0.5, 3.0, 180);
            s.place(s.enemy2, 0.5, -2.5, 0); // right behind the hunter
            s.place(s.owner, 0.5, 0.5, 0);
            s.clearHits();
            s.assertSucceeded(s.active(), "검기 Lv.1");
            s.ticks(10);
            assertEquals(s.param("slash_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "the cut ahead");
            assertEquals(0, s.damageBy(s.owner, s.enemy2), EPSILON, "a level-1 cut does not reach behind");
            assertFalse(texts(s, "검기").isEmpty(), "a status window names the skill");
            assertFalse(blocks(s, Material.LIGHT_BLUE_STAINED_GLASS).isEmpty(), "blue window panes stand along it");

            s.melee(s.owner, s.enemy, 50);
            s.ticks(2);
            assertEquals(5, leveler(s).level());
            s.healAll();
            s.place(s.enemy, 0.5, 6.0, 180);
            s.place(s.enemy2, 0.5, -5.0, 0);
            s.place(s.owner, 0.5, 0.5, 0);
            s.readyActive();
            s.clearHits();
            s.assertSucceeded(s.active(), "검기 Lv.5");
            s.ticks(10);
            double lv5 = s.param("slash_damage") + 4 * s.param("slash_damage_step");
            double factor = 1 + 4 * s.param("level_damage") / 100.0;
            assertEquals(lv5 * factor, s.damageBy(s.owner, s.enemy2), 1.0E-6, "the top-level cut is a full circle");
            assertEquals(lv5 * factor, s.damageBy(s.owner, s.enemy), 1.0E-6, "and reaches six blocks");

            // A wall stops it.
            wall(s, -3, 3, -2, 3);
            s.place(s.owner, 0.5, 0.5, 0);
            s.readyActive();
            s.clearHits();
            s.assertSucceeded(s.active(), "검기 behind a wall");
            s.ticks(10);
            assertEquals(0, s.damageBy(s.owner, s.enemy2), EPSILON, "nothing through the wall");
            clearWall(s, -3, 3, -2, 3);
            s.ticks(40);
            s.finishClean();
        }
    }

    private static List<BossBar> raidBars(TestPlayer player) {
        List<BossBar> bars = new ArrayList<>();
        for (BossBar bar : player.activeBossBars()) {
            if (Scenario.plain(bar.name()).contains("레이드 보스")) {
                bars.add(bar);
            }
        }
        return bars;
    }

    @Test
    void levelerRaidBossIsTheHealthiestEnemyEveryoneSeesItsBarAndHitsItHarder() {
        try (Scenario s = Scenario.ffa("leveler")) {
            s.ticks(5);
            s.enemy2.setHealth(s.enemy2.getHealth() - 10);
            s.assertSucceeded(s.ultimate(), "레이드 보스");
            s.ticks(2);
            assertEquals(s.enemy, leveler(s).raidBoss(), "the enemy with the most health left");
            for (TestPlayer player : s.all()) {
                assertEquals(1, raidBars(player).size(), player.getName() + " sees the boss bar");
            }
            assertEquals(1 + s.param("boss_scale") / 100.0, s.enemy.getAttribute(Attribute.SCALE).getValue(), EPSILON,
                    "the boss grows");
            assertEquals(s.param("boss_absorption"), s.enemy.getAbsorptionAmount(), EPSILON, "and is shielded");
            assertEquals(1, texts(s, "RAID BOSS").size(), "a sign over the boss");

            s.enemy.setAbsorptionAmount(0);
            s.clearHits();
            s.melee(s.enemy2, s.enemy, 4);
            assertEquals(4 * (1 + s.param("boss_bonus") / 100.0), s.damageBy(s.enemy2, s.enemy), EPSILON,
                    "everyone hits the boss harder");

            s.ticks(s.paramTicks("boss_duration") + 5);
            assertNull(leveler(s).raidBoss(), "the raid is over");
            for (TestPlayer player : s.all()) {
                assertTrue(raidBars(player).isEmpty(), player.getName() + "'s bar is gone");
            }
            assertModifierFree(s.enemy, "after the raid");
            assertTrue(texts(s, "RAID BOSS").isEmpty(), "the sign is gone");
            s.finishClean();
        }
    }

    @Test
    void levelerRaidBossSlayerGetsTheLegendaryDropAndTheHunterTwoLevels() {
        try (Scenario s = Scenario.builder("leveler").param("xp_per_level", 1000).start()) {
            s.ticks(5);
            s.enemy.getAttribute(Attribute.MAX_HEALTH).removeModifier(Scenario.TANK);
            s.enemy.setHealth(20);
            s.enemy2.setHealth(10);
            s.assertSucceeded(s.ultimate(), "레이드 보스");
            s.ticks(2);
            assertEquals(s.enemy, leveler(s).raidBoss());
            s.owner.setHealth(8);
            s.match.setGauge(s.owner, 0);
            s.melee(s.owner, s.enemy, 100);
            s.ticks(2);
            assertTrue(s.enemy.isDead() || !s.session.isAlive(s.enemy), "the boss fell");
            assertEquals(1 + (int) s.param("boss_levels"), leveler(s).level(), "two levels for the hunter's kill");
            assertTrue(s.owner.getHealth() >= 8 + s.param("drop_heal") - EPSILON, "the legendary drop heals");
            assertTrue(s.ctx().gauge(s.owner) >= s.param("drop_gauge") - EPSILON, "and fills the gauge");
            assertNull(leveler(s).raidBoss(), "the raid ends with its boss");
            for (TestPlayer player : List.of(s.owner, s.enemy2)) {
                assertTrue(raidBars(player).isEmpty(), "the bar is gone");
            }
            s.ticks(40);
            s.assertNoProblems();
        }
    }

    @Test
    void levelerLevelSignNeverGivesAwayAnInvisibleHunter() {
        try (Scenario s = Scenario.teams("leveler")) {
            s.ticks(5);
            TextDisplay tag = texts(s, "Lv.1").getFirst();
            s.owner.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, 200, 0, false, false));
            s.ticks(2);
            List<TestPlayer> viewers = s.seenBy(tag);
            assertFalse(viewers.contains(s.enemy) || viewers.contains(s.enemy2), "enemies cannot read it: " + viewers);
            assertTrue(viewers.contains(s.ally()), "the ally still can");
            s.owner.removePotionEffect(PotionEffectType.INVISIBILITY);
            s.ticks(8);
            assertTrue(s.seenBy(texts(s, "Lv.1").getFirst()).contains(s.enemy), "back once the hunter is seen");
            s.finishClean();
        }
    }

    // ================================================================== 이세계 트럭 isekaitruck

    static IsekaiTruckAbility truck(Scenario s) {
        return (IsekaiTruckAbility) s.ability();
    }

    @Test
    void isekaiTruckRunsTheEnemyOverTakesGaugeAndMarksItLevelOne() {
        try (Scenario s = noGaugeGain("isekaitruck")) {
            s.ticks(5);
            s.match.setGauge(s.enemy, 60);
            s.place(s.enemy2, 12.5, 12.5, 0);
            s.aimAt(s.owner, s.enemy);
            s.clearHits();
            s.assertSucceeded(s.active(), "트럭 도로");
            int before = s.paramTicks("truck_warning");
            s.ticks(before - 1);
            assertEquals(0, s.damageBy(s.owner, s.enemy), EPSILON, "nothing before the headlights' warning is over");
            s.ticks(6);
            assertEquals(s.param("truck_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "run over");
            assertEquals(60 - s.param("mark_gauge"), s.ctx().gauge(s.enemy), EPSILON, "reborn: gauge lost");
            assertTrue(truck(s).isMarked(s.enemy), "Lv.1");
            assertEquals(1, texts(s, "Lv.1").size(), "the sign over the reborn");
            Vector thrown = s.enemy.getVelocity();
            assertTrue(Math.abs(thrown.getX()) > 0.3, "thrown to the side: " + thrown);
            for (BlockDisplay part : entities(s, BlockDisplay.class)) {
                if (part.getBlock().getMaterial() == Material.OCHRE_FROGLIGHT) {
                    assertTrue(rotated(part, 0, 0, 1).getZ() > 0.99, "the truck faces down the road (+z)");
                }
            }
            s.clearHits();
            s.place(s.enemy, 10.5, 10.5, 0);
            s.melee(s.owner, s.enemy, 10);
            assertEquals(10 * (1 + s.param("mark_bonus") / 100.0), s.damageBy(s.owner, s.enemy), EPSILON,
                    "the reborn takes more from the truck's owner");
            s.ticks(s.paramTicks("mark_duration") + 2);
            assertFalse(truck(s).isMarked(s.enemy), "the rebirth wears off");
            assertTrue(texts(s, "Lv.1").isEmpty(), "and its sign with it");
            s.finishClean();
        }
    }

    @Test
    void isekaiTruckRoadHitsEachEnemyAtMostTwiceAndCrashesIntoAWall() {
        try (Scenario s = Scenario.ffa("isekaitruck")) {
            s.ticks(5);
            wall(s, -4, 4, 12, 3);
            s.place(s.enemy, 0.5, 6.5, 180);
            s.place(s.enemy2, 2.5, 10.5, 180);
            s.place(s.owner, 0.5, 0.5, 0);
            s.clearHits();
            s.assertSucceeded(s.active(), "트럭 도로");
            for (int i = 0; i < s.paramTicks("road_duration") + 20; i++) {
                s.ticks(1);
                s.place(s.enemy, 0.5, 6.5, 180); // stays on the road
                if (i % 10 == 0) {
                    s.place(s.enemy2, 2.5, 10.5, 180);
                }
            }
            assertEquals(s.param("truck_damage") * (2 + s.param("mark_bonus") / 100.0), s.damageBy(s.owner, s.enemy),
                    EPSILON, "at most two hits per enemy on one road (the second on a Lv.1)");
            assertTrue(s.damageBy(s.owner, s.enemy2) >= s.param("crash_damage") - EPSILON,
                    "the trucks crash into the wall next to the second enemy: " + s.hits());
            clearWall(s, -4, 4, 12, 3);
            s.finishClean();
        }
    }

    @Test
    void isekaiTruckFacingEastBuildsItsTruckAlongEast() {
        try (Scenario s = Scenario.ffa("isekaitruck")) {
            s.ticks(5);
            s.place(s.owner, 0.5, Y, 0.5, -90, 0);
            s.place(s.enemy, 6.5, 0.5, 90);
            s.ticks(2);
            s.assertSucceeded(s.active(), "트럭 도로 east");
            s.ticks(s.paramTicks("truck_warning") + 1);
            List<BlockDisplay> lamps = blocks(s, Material.OCHRE_FROGLIGHT);
            assertEquals(2, lamps.size(), "two headlights");
            for (BlockDisplay lamp : lamps) {
                assertTrue(rotated(lamp, 0, 0, 1).getX() > 0.99, "the truck faces east");
            }
            assertAllUnrotated(s, "east truck");
            s.ticks(10);
            assertTrue(s.damageBy(s.owner, s.enemy) > 0, "and runs east");
            s.finishClean();
        }
    }

    @Test
    void isekaiTruckRushHourSparesTheSafeSquaresAndHitsTheLanes() {
        try (Scenario s = noGaugeGain("isekaitruck")) {
            s.ticks(5);
            s.place(s.enemy, 0.5, 8.5, 180);
            s.place(s.enemy2, 0.5 + 3.25, 8.5 + 3.25, 180);
            s.aimAtFloor(s.owner, 0.5, 8.5);
            s.match.setGauge(s.enemy, 80);
            s.clearHits();
            s.assertSucceeded(s.ultimate(), "러시아워");
            for (int i = 0; i < 140; i++) {
                s.ticks(1);
                s.place(s.enemy, 0.5, 8.5, 180);
                s.place(s.enemy2, 0.5 + 3.25, 8.5 + 3.25, 180);
            }
            assertEquals(0, s.damageBy(s.owner, s.enemy2), EPSILON, "the gap between the lanes is safe");
            assertEquals(s.param("rush_damage") * (2 + s.param("mark_bonus") / 100.0), s.damageBy(s.owner, s.enemy),
                    EPSILON, "the middle of the crossing is hit by both middle lanes (the second time as a Lv.1)");
            assertEquals(80 - 2 * s.param("rush_gauge"), s.ctx().gauge(s.enemy), EPSILON, "reborn twice");
            s.ticks(20);
            assertTrue(entities(s, BlockDisplay.class).isEmpty(), "every truck and the crossing are gone");
            s.finishClean();
        }
    }

    // ================================================================== 몬스터 수집가 tamer

    static TamerAbility tamer(Scenario s) {
        return (TamerAbility) s.ability();
    }

    static Scenario tamerScenario(double escape) {
        return Scenario.builder("tamer").param("escape_full", escape).param("escape_low", escape)
                .param("mash_bonus", 0).start();
    }

    @Test
    void tamerCaptureHidesShrinksAndProtectsThePreyThenDropsItInFront() {
        try (Scenario s = tamerScenario(0)) {
            s.ticks(5);
            s.aimAt(s.owner, s.enemy);
            s.clearHits();
            s.assertSucceeded(s.active(), "포획 캡슐");
            s.h.tickUntil(() -> tamer(s).isCaptured(s.enemy), 20, "the capsule to hit");
            s.ticks(1);
            assertTrue(s.enemy.hasPotionEffect(PotionEffectType.INVISIBILITY), "inside the capsule");
            assertTrue(s.enemy.getAttribute(Attribute.SCALE).getValue() < 0.2, "shrunk into it");
            assertTrue(s.ctx().isSilenced(s.enemy), "no skills from inside");
            assertFalse(blocks(s, Material.GOLD_BLOCK).isEmpty(), "the capsule's gold rim");
            s.melee(s.enemy2, s.enemy, 5);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "nothing hurts the prisoner");
            int hold = s.paramTicks("shake_interval") * (int) s.param("shakes") + s.paramTicks("hold_time") + 20;
            s.h.tickUntil(() -> !tamer(s).isCaptured(s.enemy), hold, "the release");
            s.ticks(2);
            assertEquals(s.param("release_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "dropped out, hurt");
            assertFalse(s.enemy.hasPotionEffect(PotionEffectType.INVISIBILITY), "visible again");
            assertEquals(1.0, s.enemy.getAttribute(Attribute.SCALE).getValue(), EPSILON, "full size again");
            assertTrue(slowed(s.enemy), "staggering");
            assertTrue(s.enemy.getLocation().distance(s.owner.getLocation()) < 3, "dropped in front of the collector: "
                    + s.enemy.getLocation().toVector());
            s.ticks(40);
            s.finishClean();
        }
    }

    @Test
    void tamerSneakThrowsTheCaughtEnemyWhereItLooks() {
        try (Scenario s = tamerScenario(0)) {
            s.ticks(5);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "포획 캡슐");
            s.h.tickUntil(() -> tamer(s).isCaptured(s.enemy), 20, "the capsule to hit");
            int shakes = s.paramTicks("shake_interval") * (int) s.param("shakes");
            s.ticks(shakes + 4);
            assertTrue(tamer(s).isCaptured(s.enemy), "held after every shake");
            s.aimAtFloor(s.owner, 8.5, 9.5);
            s.owner.sendInput(InputKey.SNEAK);
            s.h.tickUntil(() -> !tamer(s).isCaptured(s.enemy), 30, "the throw");
            s.owner.sendInput(TestInput.NONE);
            Location at = s.enemy.getLocation();
            assertTrue(at.distance(new Location(s.world, 8.5, at.getY(), 9.5)) < 1.5, "thrown where it looked: "
                    + at.toVector());
            s.tickUntilGrounded(s.enemy, 40);
            assertEquals(Y, s.enemy.getLocation().getY(), EPSILON, "on the floor");
            s.finishClean();
        }
    }

    @Test
    void tamerPreyAtFullHealthCanBreakOutAndMashingJumpHelps() {
        try (Scenario s = tamerScenario(100)) {
            s.ticks(5);
            s.aimAt(s.owner, s.enemy);
            s.clearHits();
            s.assertSucceeded(s.active(), "포획 캡슐");
            s.h.tickUntil(() -> tamer(s).isCaptured(s.enemy), 20, "the capsule to hit");
            s.h.tickUntil(() -> !tamer(s).isCaptured(s.enemy), 20, "the escape");
            s.ticks(1);
            assertEquals(s.param("escape_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "barely scratched");
            assertFalse(s.enemy.hasPotionEffect(PotionEffectType.INVISIBILITY));
            s.finishClean();
        }
        try (Scenario s = Scenario.builder("tamer").param("escape_full", 0).param("escape_low", 0)
                .param("mash_bonus", 100).param("mash_max", 100).start()) {
            s.ticks(5);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "포획 캡슐");
            s.h.tickUntil(() -> tamer(s).isCaptured(s.enemy), 20, "the capsule to hit");
            s.enemy.sendInput(InputKey.JUMP);
            s.ticks(1);
            s.enemy.sendInput(TestInput.NONE);
            s.h.tickUntil(() -> !tamer(s).isCaptured(s.enemy), 14, "mashing breaks it open at the next shake");
            s.finishClean();
        }
    }

    @Test
    void tamerReadoutIsForTheCollectorAlone() {
        try (Scenario s = Scenario.teams("tamer")) {
            s.ticks(5);
            s.melee(s.owner, s.enemy, 2);
            s.ticks(2);
            List<TextDisplay> readouts = texts(s, "포획률");
            assertEquals(1, readouts.size(), "a readout over the struck enemy");
            assertEquals(List.of(s.owner), s.seenBy(readouts.getFirst()), "for the collector alone");
            s.enemy.setHealth(s.enemy.getHealth() * 0.2);
            s.ticks(6);
            assertEquals(1, texts(s, "포획 찬스").size(), "a badly hurt enemy is a catching chance");
            s.ticks(s.paramTicks("info_duration") + 2);
            assertTrue(texts(s, "포획").isEmpty(), "the readout fades");
            s.finishClean();
        }
    }

    @Test
    void tamerLegendaryCaptureSwallowsTheCrowdAndBursts() {
        try (Scenario s = tamerScenario(0)) {
            s.ticks(5);
            s.place(s.enemy2, 2.5, 5.5, 180);
            s.aimAtFloor(s.owner, 0.5, 4.5);
            s.clearHits();
            s.assertSucceeded(s.ultimate(), "전설의 포획");
            s.ticks(s.paramTicks("legend_fall") + 3);
            assertTrue(tamer(s).isCaptured(s.enemy) && tamer(s).isCaptured(s.enemy2), "both swallowed");
            assertEquals(1, blocks(s, Material.GOLD_BLOCK).size(), "one giant capsule holds them both");
            s.ticks(s.paramTicks("shake_interval") * (int) s.param("shakes") + s.paramTicks("legend_burst_delay") + 4);
            assertFalse(tamer(s).isCaptured(s.enemy) || tamer(s).isCaptured(s.enemy2), "the capsule burst");
            assertEquals(s.param("legend_damage"), s.damageBy(s.owner, s.enemy), EPSILON);
            assertEquals(s.param("legend_damage"), s.damageBy(s.owner, s.enemy2), EPSILON);
            for (TestPlayer enemy : s.enemies()) {
                assertFalse(enemy.hasPotionEffect(PotionEffectType.INVISIBILITY));
                assertEquals(1.0, enemy.getAttribute(Attribute.SCALE).getValue(), EPSILON);
            }
            s.ticks(30);
            s.finishClean();
        }
    }

    // ================================================================== 밟기 달인 stomper

    static StomperAbility stomper(Scenario s) {
        return (StomperAbility) s.ability();
    }

    /** Drops the owner from just over {@code target}'s head onto it. */
    static void dropOnto(Scenario s, Player target) {
        Location head = target.getLocation();
        s.place(s.owner, head.getX(), Y + target.getHeight() + 0.5, head.getZ(), 0, 0);
        s.ticks(1);
        s.owner.setVelocity(new Vector(0, -0.45, 0));
    }

    @Test
    void stomperStompsHeadsBouncesAndChainsHarderWithCandyHealing() {
        try (Scenario s = Scenario.ffa("stomper")) {
            s.ticks(5);
            s.place(s.enemy, 0.5, 4.5, 180);
            s.place(s.enemy2, 6.5, 4.5, 180);
            s.owner.setHealth(10);
            s.clearHits();
            dropOnto(s, s.enemy);
            s.h.tickUntil(() -> stomper(s).chain() == 1, 10, "the first stomp");
            assertEquals(s.param("stomp_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "뽁!");
            assertTrue(slowed(s.enemy), "squashed flat");
            assertTrue(s.owner.getVelocity().getY() > 0.5, "bounced up: " + s.owner.getVelocity());
            assertFalse(texts(s, "뽁").isEmpty(), "a pop sign");

            dropOnto(s, s.enemy2);
            s.h.tickUntil(() -> stomper(s).chain() == 2, 10, "the second stomp");
            assertEquals(s.param("stomp_damage") + s.param("stomp_bonus"), s.damageBy(s.owner, s.enemy2), EPSILON,
                    "a chained stomp hits harder");
            // The same head again only after a while: keep the stomper in the air meanwhile (it never lands).
            for (int i = 0; i < s.paramTicks("stomp_repeat") / 5; i++) {
                s.place(s.owner, 3.5, Y + 8, 4.5, 0, 0);
                s.ticks(5);
            }
            dropOnto(s, s.enemy);
            s.h.tickUntil(() -> stomper(s).chain() == 3, 10, "the third stomp");
            assertEquals(10 + s.param("candy_heal"), s.owner.getHealth(), EPSILON, "three in a row: a star candy heals");

            s.tickUntilGrounded(s.owner, 60);
            s.ticks(2);
            assertEquals(0, stomper(s).chain(), "the chain ends on the ground");
            s.finishClean();
        }
    }

    @Test
    void stomperTakesHalfTheFallDamage() {
        try (Scenario s = Scenario.ffa("stomper")) {
            s.ticks(5);
            s.place(s.owner, 10.5, Y + 10, 10.5, 0, 0);
            s.clearHits();
            s.tickUntilGrounded(s.owner, 80);
            s.ticks(2);
            double taken = s.damageTaken(s.owner);
            assertTrue(taken > 0, "a ten-block fall still hurts");
            assertEquals(3.5, taken, 0.6, "about half of the seven a ten-block fall deals");
            s.finishClean();
        }
    }

    @Test
    void stomperPadThrowsEnemiesUpAndCatapultsTheStomperAndAlliesSafely() {
        try (Scenario s = Scenario.teams("stomper")) {
            s.ticks(5);
            s.aimAt(s.owner, s.enemy);
            s.clearHits();
            s.assertSucceeded(s.active(), "용수철 발판");
            assertEquals(s.param("pad_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "the pad springs up under it");
            assertTrue(s.enemy.getVelocity().getY() > 0.6, "thrown into the air");
            s.ticks(12);
            List<BlockDisplay> plates = blocks(s, Material.PINK_CONCRETE);
            assertEquals(1, plates.size(), "a pink plate");
            Vector across = rotated(plates.getFirst(), 1, 0, 0);
            assertTrue(Math.abs(across.getX()) > 0.99, "it lies across the stomper's view: " + across);
            assertAllUnrotated(s, "pad");

            s.tickUntilGrounded(s.enemy, 80);
            s.place(s.enemy, 8.5, 8.5, 0);
            s.clearHits();
            s.place(s.owner, 0.5, 4.5, 0);
            s.ticks(1);
            assertTrue(s.owner.getVelocity().getY() > 0.6, "catapulted: " + s.owner.getVelocity());
            s.place(s.ally(), -0.5, 4.5, 0);
            s.ticks(1);
            assertTrue(s.ally().getVelocity().getY() > 0.6, "the ally too: " + s.ally().getVelocity());
            // Off the pad in mid-air (on it they would bounce again and again, like on a trampoline).
            s.ticks(3);
            Location high = s.owner.getLocation();
            s.place(s.owner, 6.5, high.getY(), 0.5, 0, 0);
            s.place(s.ally(), -6.5, s.ally().getLocation().getY(), 0.5, 0, 0);
            s.tickUntilGrounded(s.owner, 120);
            s.tickUntilGrounded(s.ally(), 120);
            s.ticks(3);
            assertTrue(high.getY() > Y + 2, "high up: " + high);
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "the stomper lands softly: " + s.hits());
            assertEquals(0, s.damageTaken(s.ally()), EPSILON, "and so does the ally: " + s.hits());
            assertEquals(Y, s.owner.getLocation().getY(), EPSILON, "on the floor");

            for (int i = 0; i < 2; i++) {
                s.layout();
                s.aimAtFloor(s.owner, 0.5 + 3 * i, 5.5);
                s.readyActive();
                s.assertSucceeded(s.active(), "another pad");
                s.ticks(2);
            }
            assertEquals((int) s.param("pad_max"), stomper(s).padCount(), "at most two pads");
            s.ticks(s.paramTicks("pad_duration") + 10);
            assertEquals(0, stomper(s).padCount(), "the pads fold away");
            s.finishClean();
        }
    }

    @Test
    void stomperFacingEastLaysThePadAcrossTheEast() {
        try (Scenario s = Scenario.ffa("stomper")) {
            s.ticks(5);
            s.place(s.owner, 0.5, Y, 0.5, -90, 30);
            s.ticks(2);
            s.assertSucceeded(s.active(), "용수철 발판 east");
            s.ticks(12);
            BlockDisplay plate = blocks(s, Material.PINK_CONCRETE).getFirst();
            assertTrue(Math.abs(rotated(plate, 1, 0, 0).getZ()) > 0.99, "across the view is north-south");
            assertTrue(plate.getLocation().getX() > 1.5, "east of the stomper");
            s.finishClean();
        }
    }

    @Test
    void stomperCandyIgnoresDamageDoublesStompsQuakesAndForbidsMelee() {
        try (Scenario s = Scenario.ffa("stomper")) {
            s.ticks(5);
            s.assertSucceeded(s.ultimate(), "무적 사탕");
            s.ticks(1);
            assertTrue(stomper(s).isInvincible());
            assertFalse(modifiers(s.owner, Attribute.MOVEMENT_SPEED).isEmpty(), "faster");
            assertFalse(modifiers(s.owner, Attribute.JUMP_STRENGTH).isEmpty(), "bouncier");
            s.clearHits();
            s.melee(s.enemy, s.owner, 6);
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "nothing hurts it");
            s.melee(s.owner, s.enemy, 4);
            assertEquals(0, s.damageBy(s.owner, s.enemy), EPSILON, "no hands while invincible");

            s.place(s.enemy, 0.5, 4.5, 180);
            s.place(s.enemy2, 1.5, 6.0, 180);
            dropOnto(s, s.enemy);
            s.h.tickUntil(() -> stomper(s).chain() == 1, 10, "the stomp");
            assertEquals(s.param("stomp_damage") * s.param("candy_stomp"), s.damageBy(s.owner, s.enemy), EPSILON,
                    "stomps hit twice as hard");
            s.place(s.enemy, 9.5, 9.5, 180);
            s.tickUntilGrounded(s.owner, 80);
            s.ticks(2);
            assertTrue(s.damageBy(s.owner, s.enemy2) >= s.param("quake_damage") - EPSILON,
                    "the landing shook the ground under the second enemy: " + s.hits());

            s.ticks(s.paramTicks("candy_duration"));
            assertFalse(stomper(s).isInvincible(), "the candy wears off");
            assertModifierFree(s.owner, "after the candy");
            s.clearHits();
            s.melee(s.enemy, s.owner, 2);
            assertEquals(2, s.damageTaken(s.owner), EPSILON, "hurt again");
            s.finishClean();
        }
    }

    // ================================================================== 격겜 고수 arcade

    static ArcadeAbility arcade(Scenario s) {
        return (ArcadeAbility) s.ability();
    }

    @Test
    void arcadeComboCountsHitsAndHitsHarderFromTheThirdThenResets() {
        try (Scenario s = noGaugeGain("arcade")) {
            s.ticks(5);
            s.clearHits();
            double step = s.param("combo_step") / 100.0;
            double[] expected = {2, 2, 2 * (1 + step), 2 * (1 + 2 * step)};
            for (double hit : expected) {
                double before = s.damageBy(s.owner, s.enemy);
                s.melee(s.owner, s.enemy, 2);
                assertEquals(hit, s.damageBy(s.owner, s.enemy) - before, EPSILON, "combo hit " + arcade(s).combo());
                s.ticks(2);
            }
            assertEquals(4, arcade(s).combo());
            List<TextDisplay> counter = texts(s, "HIT!");
            assertEquals(1, counter.size(), "the combo counter over the master's head");
            assertTrue(s.seenBy(counter.getFirst()).contains(s.enemy), "for everyone to see");
            s.ticks(s.paramTicks("combo_window") + 2);
            assertEquals(0, arcade(s).combo(), "a second without a hit ends it");

            s.match.setGauge(s.owner, 0);
            for (int i = 0; i < (int) s.param("excellent_hits"); i++) {
                s.melee(s.owner, s.enemy, 1);
                s.ticks(1);
            }
            assertEquals(s.param("excellent_gauge"), s.ctx().gauge(s.owner), EPSILON, "EXCELLENT!");
            assertFalse(texts(s, "EXCELLENT").isEmpty());
            s.finishClean();
        }
    }

    @Test
    void arcadeCommandsPickTheMove() {
        try (Scenario s = Scenario.ffa("arcade")) {
            s.ticks(5);
            long now = s.ctx().currentTick();
            assertEquals(ArcadeAbility.Move.JAB, arcade(s).readMove(s.owner, now), "no input: the dash jab");
            s.owner.sendInput(InputKey.SNEAK);
            s.ticks(2);
            s.owner.sendInput(InputKey.JUMP);
            s.ticks(1);
            assertEquals(ArcadeAbility.Move.UPPERCUT, arcade(s).readMove(s.owner, s.ctx().currentTick()));
            s.owner.sendInput(TestInput.NONE);
            s.ticks(2);
            s.owner.sendInput(InputKey.BACKWARD);
            s.ticks(2);
            s.owner.sendInput(InputKey.FORWARD);
            s.ticks(1);
            assertEquals(ArcadeAbility.Move.WHIRLWIND, arcade(s).readMove(s.owner, s.ctx().currentTick()));
            s.owner.sendInput(TestInput.NONE);
            s.ticks(2);
            s.owner.sendInput(InputKey.SNEAK);
            s.ticks(2);
            s.owner.sendInput(InputKey.FORWARD);
            s.ticks(1);
            assertEquals(ArcadeAbility.Move.SWEEP, arcade(s).readMove(s.owner, s.ctx().currentTick()),
                    "the latest finished command wins");
            s.owner.sendInput(TestInput.NONE);
            s.ticks(s.paramTicks("input_window") + 2);
            assertEquals(ArcadeAbility.Move.JAB, arcade(s).readMove(s.owner, s.ctx().currentTick()),
                    "old keys are forgotten");
            s.finishClean();
        }
    }

    @Test
    void arcadeUppercutRisesUntouchableLaunchesAndLandsSafely() {
        try (Scenario s = Scenario.ffa("arcade")) {
            s.ticks(5);
            s.place(s.enemy, 0.5, 1.8, 180);
            s.ticks(12);
            s.owner.sendInput(InputKey.SNEAK);
            s.ticks(1);
            s.owner.sendInput(InputKey.JUMP);
            s.ticks(1);
            s.owner.sendInput(TestInput.NONE);
            s.clearHits();
            s.assertSucceeded(s.active(), "솟구치는 어퍼");
            assertEquals(s.param("upper_damage"), s.damageBy(s.owner, s.enemy), EPSILON);
            assertTrue(s.enemy.getVelocity().getY() > 0.5, "launched");
            assertTrue(s.owner.getVelocity().getY() > 0.5, "rising");
            s.melee(s.enemy2, s.owner, 4);
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "untouchable while rising");
            s.ticks(4);
            assertTrue(s.owner.getLocation().getY() > Y + 2, "up in the air");
            s.tickUntilGrounded(s.owner, 80);
            s.ticks(2);
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "lands without fall damage: " + s.hits());
            assertEquals(Y, s.owner.getLocation().getY(), EPSILON, "on the floor");
            s.finishClean();
        }
    }

    @Test
    void arcadeWhirlwindKickDashesExactlyItsLengthAndHitsTwice() {
        try (Scenario s = Scenario.ffa("arcade")) {
            s.ticks(5);
            s.place(s.enemy, 0.5, 3.5, 180);
            s.place(s.enemy2, 8.5, 0.5, 180);
            s.ticks(12);
            s.owner.sendInput(InputKey.BACKWARD);
            s.ticks(1);
            s.owner.sendInput(InputKey.FORWARD);
            s.ticks(1);
            s.owner.sendInput(TestInput.NONE);
            s.clearHits();
            s.assertSucceeded(s.active(), "회오리 발차기");
            s.ticks(20);
            assertEquals(2, s.hitsBy(s.owner, s.enemy).size(), "two kicks: " + s.hits());
            assertEquals(0.5 + s.param("spin_distance"), s.owner.getLocation().getZ(), 0.35,
                    "the dash ends at its length, not past it");
            s.finishClean();
        }
    }

    @Test
    void arcadeSweepKnocksDownWhatIsInFrontOnly() {
        try (Scenario s = Scenario.ffa("arcade")) {
            s.ticks(5);
            s.place(s.enemy, 0.5, 3.0, 180);
            s.place(s.enemy2, 0.5, -2.5, 0);
            s.ticks(12);
            s.owner.sendInput(InputKey.SNEAK);
            s.ticks(1);
            s.owner.sendInput(InputKey.FORWARD);
            s.ticks(1);
            s.owner.sendInput(TestInput.NONE);
            s.clearHits();
            s.assertSucceeded(s.active(), "발 쓸기");
            assertEquals(s.param("sweep_damage"), s.damageBy(s.owner, s.enemy), EPSILON);
            assertEquals(0, s.damageBy(s.owner, s.enemy2), EPSILON, "nothing behind");
            assertTrue(s.ctx().isMovementLocked(s.enemy, null), "knocked down");
            assertFalse(texts(s, "DOWN").isEmpty());
            s.ticks(s.paramTicks("sweep_down") + 2);
            assertFalse(s.ctx().isMovementLocked(s.enemy, null), "back up");
            s.finishClean();
        }
    }

    @Test
    void arcadeJabDashesAtTheEnemyAndStopsAtIt() {
        try (Scenario s = Scenario.ffa("arcade")) {
            s.ticks(30);
            s.aimAt(s.owner, s.enemy);
            s.clearHits();
            s.assertSucceeded(s.active(), "대시 잽");
            s.ticks(8);
            assertEquals(s.param("jab_damage"), s.damageBy(s.owner, s.enemy), EPSILON);
            assertTrue(s.owner.getLocation().getZ() < s.enemy.getLocation().getZ(), "never through the enemy");
            s.finishClean();
        }
    }

    @Test
    void arcadeRoundPinsEveryoneToThePlaneDoublesTheComboHalvesTheCooldownAndKnocksOut() {
        try (Scenario s = Scenario.ffa("arcade")) {
            s.ticks(5);
            s.place(s.enemy, 4.5, 4.5, 180);
            s.place(s.enemy2, -2.5, 6.5, 180);
            s.place(s.owner, 0.5, 0.5, 0);
            s.ticks(12);
            s.assertSucceeded(s.ultimate(), "라운드 원");
            assertTrue(arcade(s).inRound());
            s.ticks(2);
            BlockDisplay line = blocks(s, Material.LIGHT_BLUE_CONCRETE).getFirst();
            s.ticks(8);
            assertTrue(Math.abs(rotated(line, 0, 0, 1).getZ()) > 0.99, "the stage line runs along the view");
            assertFalse(texts(s, "ROUND 1").isEmpty() && texts(s, "FIGHT").isEmpty(), "the banner");
            assertAllUnrotated(s, "stage");
            s.ticks(30);
            assertEquals(0.5, s.enemy.getLocation().getX(), 0.5, "pulled onto the plane: " + s.enemy.getLocation());
            assertEquals(0.5, s.enemy2.getLocation().getX(), 0.5, "both: " + s.enemy2.getLocation());

            s.melee(s.owner, s.enemy, 1);
            assertEquals((int) s.param("round_combo"), arcade(s).combo(), "the combo counts double");
            s.melee(s.owner, s.enemy, 1);
            s.melee(s.owner, s.enemy2, 1);
            s.readyActive();
            s.place(s.enemy, 0.5, 12.5, 180);
            s.place(s.enemy2, 0.5, -12.5, 0);
            s.assertSucceeded(s.active(), "a jab in the round");
            assertEquals(s.param("cooldown") * (1 - s.param("round_cooldown") / 100.0),
                    s.ctx().cooldownRemaining(s.owner), 0.11, "specials cool down faster");
            s.clearHits();
            s.h.tickUntil(() -> !arcade(s).inRound(), s.paramTicks("round_duration"), "the bell");
            s.ticks(1);
            assertEquals(s.param("ko_damage"), s.damageBy(s.owner, s.enemy), 0.5, "K.O. for the most beaten enemy");
            assertEquals(0, s.damageBy(s.owner, s.enemy2), EPSILON);
            s.ticks(20);
            assertTrue(blocks(s, Material.LIGHT_BLUE_CONCRETE).isEmpty(), "the stage is gone");
            s.finishClean();
        }
    }

    @Test
    void tamerFallingCollectorLetsEveryPrisonerOfTheGiantCapsuleOut() {
        try (Scenario s = tamerScenario(0)) {
            s.ticks(5);
            s.place(s.enemy2, 2.5, 5.5, 180);
            s.aimAtFloor(s.owner, 0.5, 4.5);
            s.assertSucceeded(s.ultimate(), "전설의 포획");
            s.ticks(s.paramTicks("legend_fall") + 3);
            assertTrue(tamer(s).isCaptured(s.enemy) && tamer(s).isCaptured(s.enemy2), "both swallowed");
            s.killOwner(s.enemy2);
            s.ticks(1);
            for (TestPlayer enemy : s.enemies()) {
                assertFalse(enemy.hasPotionEffect(PotionEffectType.INVISIBILITY), enemy.getName() + " is out");
                assertEquals(1.0, enemy.getAttribute(Attribute.SCALE).getValue(), EPSILON, enemy.getName()
                        + " full size");
                assertFalse(s.ctx().isMovementLocked(enemy, null), enemy.getName() + " free to move");
            }
            s.ticks(20);
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void isekaiTruckLevelOneSignNeverGivesAwayAnInvisibleVictim() {
        try (Scenario s = Scenario.ffa("isekaitruck")) {
            s.ticks(5);
            s.place(s.enemy2, 12.5, 12.5, 0); // off the road: only one reborn
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "트럭 도로");
            s.h.tickUntil(() -> truck(s).isMarked(s.enemy), 30, "the first truck");
            s.ticks(2); // the sign goes up with the rebirth's first follow step
            TextDisplay sign = texts(s, "Lv.1").getFirst();
            assertTrue(s.seenBy(sign).contains(s.owner), "the owner reads the reborn's level");
            s.enemy.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, 200, 0, false, false));
            s.ticks(8);
            for (TextDisplay shown : texts(s, "Lv.1")) {
                assertFalse(s.seenBy(shown).contains(s.owner), "nothing floats over the invisible victim for its "
                        + "enemies");
                assertFalse(s.seenBy(shown).contains(s.enemy2), "nor for the other enemy");
            }
            s.enemy.removePotionEffect(PotionEffectType.INVISIBILITY);
            s.ticks(40);
            s.finishClean();
        }
    }
}
