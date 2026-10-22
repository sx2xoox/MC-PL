package io.github.sx2xoox.mcpl.game.superpower.scenario;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import io.github.sx2xoox.mcpl.game.superpower.abilities.BlacksmithAbility;
import io.github.sx2xoox.mcpl.game.superpower.abilities.GunslingerAbility;
import io.github.sx2xoox.mcpl.game.superpower.abilities.MinerAbility;
import io.github.sx2xoox.mcpl.game.superpower.abilities.PoliceAbility;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Spots;
import io.github.sx2xoox.mcpl.game.superpower.ability.Ability;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
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
import org.bukkit.block.Block;
import org.bukkit.damage.DamageType;
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

/**
 * 직업 2 (새 능력): 경찰, 광부, 대장장이, 총잡이. Every ability through a whole match, the owner dying in the middle of each
 * skill, team mode with an ally in the line of fire, the standalone ultimates borrowed by a Gambler, the displays
 * keeping their own rotation at zero, and each ability's own mechanics: the wanted list, the taser and the arrest with
 * its cell; the vein, the rockfall with its rubble and the runaway mine cart; the tempered blow, the anvil and the
 * forge; the six-shooter's cylinder, head shots and high noon.
 */
class TradeNewAbilitiesTest {

    private static final double EPSILON = 1.0E-6;
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z0-9_]+)}");

    static Stream<String> abilities() {
        return Stream.of("police", "miner", "blacksmith", "gunslinger");
    }

    /** The abilities whose ultimate the Gambler may borrow: all four. */
    static Stream<String> standalone() {
        return abilities();
    }

    // ------------------------------------------------------------------ helpers

    private static <T extends Entity> List<T> entities(Scenario s, Class<T> type) {
        return s.world.getEntities().stream().filter(type::isInstance).map(type::cast).toList();
    }

    private static List<BlockDisplay> blocks(Scenario s, Material material) {
        return entities(s, BlockDisplay.class).stream()
                .filter(display -> display.getBlock().getMaterial() == material).toList();
    }

    private static void assertNoDisplays(Scenario s, String what) {
        assertTrue(entities(s, Display.class).isEmpty(), what + ": every display is gone: "
                + entities(s, Display.class).stream().map(d -> d.getType() + "@" + d.getLocation().toVector()).toList());
    }

    /** Exactly what {@code GamblerAbility#fireBorrowed} does. */
    private static ActivationResult borrow(Scenario s, String id) {
        Ability borrowed = s.ctx().registry().create(id);
        borrowed.bind(s.owner, s.ctx(), s.ctx().paramsFor(id));
        return borrowed.onUltimate();
    }

    private static Scenario gambler() {
        return Scenario.builder("gambler").param("dodge_chance", 0).param("crit_chance", 0).start();
    }

    /** No ultimate gauge from damage or time, so gauge numbers in a test come from the ability alone. */
    private static Scenario.Builder noGaugeGain(Scenario.Builder builder) {
        return builder.configure(h -> {
            h.settings().set("ultimate.per-damage-dealt", 0);
            h.settings().set("ultimate.per-damage-taken", 0);
            h.settings().set("ultimate.per-second", 0);
        });
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

    private static boolean rooted(Player player) {
        return player.getAttribute(Attribute.MOVEMENT_SPEED).getModifiers().stream()
                .anyMatch(modifier -> modifier.getAmount() <= -1.0 + EPSILON)
                && player.getAttribute(Attribute.JUMP_STRENGTH).getModifiers().stream()
                .anyMatch(modifier -> modifier.getAmount() <= -1.0 + EPSILON);
    }

    private static boolean slowed(Player player) {
        return player.getAttribute(Attribute.MOVEMENT_SPEED).getModifiers().stream()
                .anyMatch(modifier -> modifier.getAmount() < 0);
    }

    private static double modifierSum(Player player, Attribute attribute) {
        return player.getAttribute(attribute).getModifiers().stream()
                .filter(modifier -> !modifier.getKey().equals(Scenario.TANK))
                .mapToDouble(AttributeModifier::getAmount).sum();
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
        }
        assertModifierFree(s.owner, id);
        s.assertNoProblems();
        s.finishMatch();
        s.assertNoProblems();
        s.assertCleanWorld();
    }

    /**
     * The left rotation a display's transformation was set with. The test doubles keep it as set; should a mock
     * hand it back conjugated, a probe display tells and the check still holds.
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

    /** The world direction of a local axis of a display (whose own rotation is 0). */
    private static Vector axis(Display display, float x, float y, float z) {
        Vector3f v = leftRotation(display).transform(new Vector3f(x, y, z));
        return new Vector(v.x(), v.y(), v.z());
    }

    /** The horizontal unit vector of a Minecraft yaw (0 = +z, 90 = -x). */
    private static Vector facing(double yawDegrees) {
        double yaw = Math.toRadians(yawDegrees);
        return new Vector(-Math.sin(yaw), 0, Math.cos(yaw));
    }

    /** Displays carry no rotation of their own: every orientation lives in their transformation. */
    private static void assertUnrotated(Scenario s, String what) {
        for (Display display : entities(s, Display.class)) {
            assertEquals(0, display.getLocation().getYaw(), 1.0E-3, what + ": display yaw of " + display);
            assertEquals(0, display.getLocation().getPitch(), 1.0E-3, what + ": display pitch of " + display);
        }
    }

    /** At most about two dozen displays alive per ability instance. */
    private static void assertDisplayBudget(Scenario s, String what) {
        int alive = entities(s, Display.class).size();
        assertTrue(alive <= 24, what + ": " + alive + " displays alive at once");
    }

    /** Feet exactly on the floor and a body that touches no block. */
    private static void assertStandsOnFloor(Player player, String what) {
        Location at = player.getLocation();
        assertEquals(FlatWorld.SURFACE, at.getY(), EPSILON, what + ": feet on the surface " + at);
        assertTrue(Spots.isFree(at.getWorld(), at, player.getWidth(), player.getHeight()),
                what + ": not inside a block " + at);
    }

    /** Uses the active as soon as the cooldown allows, at most {@code maxTicks} ticks of waiting. */
    private static ActivationResult activeWhenReady(Scenario s, int maxTicks) {
        for (int i = 0; i < maxTicks && s.ctx().cooldownRemaining(s.owner) > 0; i++) {
            s.ticks(1);
        }
        return s.active();
    }

    // ================================================================== every ability

    @Test
    void descriptionsOnlyReferenceDeclaredParams() {
        for (AbilityInfo info : List.of(PoliceAbility.INFO, MinerAbility.INFO, BlacksmithAbility.INFO,
                GunslingerAbility.INFO)) {
            for (AbilityInfo.Skill skill : List.of(info.passive(), info.active(), info.ultimate())) {
                Matcher matcher = PLACEHOLDER.matcher(skill.description());
                while (matcher.find()) {
                    assertTrue(info.params().containsKey(matcher.group(1)),
                            info.id() + " " + skill.name() + " uses an undeclared param " + matcher.group());
                }
                assertFalse(skill.name().contains("TODO") || skill.description().contains("TODO"), info.id());
            }
            assertTrue(info.ultimateStandalone(), info.id() + ": its ultimate needs no hooks");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("abilities")
    void wholeMatch(String id) {
        try (Scenario s = noGaugeGain(Scenario.builder(id)).start()) {
            s.ticks(60);
            s.assertNoProblems();
            s.aimAt(s.owner, s.enemy);
            s.clearHits();
            s.assertSucceeded(s.active(), id + " active");
            s.ticks(100);
            assertTrue(s.damageTaken(s.enemy) > 0, id + " active hurts the aimed enemy");
            assertEquals(0, s.damageTaken(s.owner), EPSILON, id + " active never hurts its owner: " + s.hits());

            AbilityLifecycleTest.prepareUltimate(s);
            s.healAll();
            s.clearHits();
            s.assertSucceeded(s.ultimate(), id + " ultimate");
            s.ticks(300);
            assertTrue(s.damageTaken(s.enemy) > 0, id + " ultimate hurts the aimed enemy");
            assertEquals(0, s.damageTaken(s.owner), EPSILON, id + " ultimate never hurts its owner: " + s.hits());
            s.assertNoProblems();
            assertNoDisplays(s, id);
            assertEquals(0, s.h.hub.tempBlocks().size(), id + ": temporary blocks restored");
            for (TestPlayer enemy : s.enemies()) {
                assertModifierFree(enemy, id);
            }
            assertModifierFree(s.owner, id);

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
    void ownerDiesWhileTheActiveIsInFullSwing(String id) {
        try (Scenario s = Scenario.ffa(id)) {
            s.ticks(40);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), id + " active");
            // The taser holds its victim, the rocks are falling, the anvil has landed, the shells are tumbling.
            s.ticks(18);
            assertOwnerDeathCleansUp(s);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("abilities")
    void ownerDiesDuringUltimate(String id) {
        try (Scenario s = Scenario.ffa(id)) {
            s.ticks(40);
            AbilityLifecycleTest.prepareUltimate(s);
            s.assertSucceeded(s.ultimate(), id + " ultimate");
            s.ticks(14);
            assertOwnerDeathCleansUp(s);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("abilities")
    void ownerDiesLateInTheUltimate(String id) {
        try (Scenario s = Scenario.ffa(id)) {
            s.ticks(40);
            AbilityLifecycleTest.prepareUltimate(s);
            s.assertSucceeded(s.ultimate(), id + " ultimate");
            // The prisoner sits in its cell, the cart is rolling, the hammer is up, high noon has struck.
            s.ticks(45);
            assertOwnerDeathCleansUp(s);
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

            AbilityLifecycleTest.prepareUltimate(s);
            allyStart = s.ally().getLocation();
            s.assertSucceeded(s.ultimate(), id + " ultimate");
            AbilityLifecycleTest.watchAlly(s, allyStart, 300);
            s.assertNoProblems();

            s.finishMatch();
            s.assertNoProblems();
            s.assertCleanWorld();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("abilities")
    void displaysNeverTakeTheOwnersRotation(String id) {
        try (Scenario s = Scenario.ffa(id)) {
            turnedLayout(s);
            s.assertSucceeded(s.active(), id + " active");
            for (int i = 0; i < 60; i++) {
                s.ticks(1);
                assertUnrotated(s, id + " active");
                assertDisplayBudget(s, id + " active");
            }
            s.ticks(120);
            turnedLayout(s);
            s.assertSucceeded(s.ultimate(), id + " ultimate");
            for (int i = 0; i < 200; i++) {
                s.ticks(1);
                assertUnrotated(s, id + " ultimate");
                assertDisplayBudget(s, id + " ultimate");
            }
            s.ticks(100);
            s.finishClean();
        }
    }

    /** A layout turned by 90 degrees: the owner looks west (-x), so a display taking the owner's yaw is caught. */
    private static void turnedLayout(Scenario s) {
        s.place(s.owner, 0.5, 0.5, 90);
        s.place(s.enemy, -3.5, 0.5, -90);
        s.place(s.enemy2, -5.5, 2.0, -90);
        if (s.ally != null) {
            s.place(s.ally(), 0.5, 3.5, 0);
        }
        s.aimAt(s.owner, s.enemy);
    }

    // ------------------------------------------------------------------ borrowed by the gambler

    @ParameterizedTest(name = "{0}")
    @MethodSource("standalone")
    void borrowedUltimateWorksOnAGambler(String id) {
        try (Scenario s = gambler()) {
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(borrow(s, id), "borrowed " + id);
            s.ticks(300);
            assertTrue(s.damageBy(s.owner, s.enemy) > 0, "the borrowed " + id + " hurts the enemy");
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "the borrowed " + id + " never hurts the gambler");
            assertModifierFree(s.owner, id);
            for (TestPlayer target : s.enemies()) {
                assertModifierFree(target, id);
                assertFalse(s.ctx().isSilenced(target), id + ": nobody stays silenced");
            }
            assertNoDisplays(s, id);
            assertEquals(0, s.h.hub.tempBlocks().size(), id + ": temporary blocks restored");
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
            s.ticks(16);
            s.killOwner(s.enemy);
            s.ticks(1);
            for (Entity entity : s.world.getEntities()) {
                assertTrue(entity instanceof Player, id + ": " + entity.getType() + " outlived the gambler");
            }
            s.clearHits();
            s.ticks(300);
            assertTrue(s.hits().stream().noneMatch(hit -> s.owner.equals(hit.causer())),
                    id + ": the dead gambler dealt damage: " + s.hits());
            assertModifierFree(s.owner, id);
            assertEquals(0, s.h.hub.tempBlocks().size(), id + ": temporary blocks restored");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    // ================================================================== 경찰 police

    private static double gauge(Scenario s, Player player) {
        return s.match.participant(player.getUniqueId()).gauge();
    }

    /** The red and blue lamps of a wanted suspect's light bar (the police car's lamps are bigger and lower). */
    private static List<BlockDisplay> lightBar(Scenario s, Player suspect) {
        double head = suspect.getEyeLocation().getY();
        return entities(s, BlockDisplay.class).stream()
                .filter(d -> d.getBlock().getMaterial() == Material.RED_CONCRETE
                        || d.getBlock().getMaterial() == Material.BLUE_CONCRETE)
                .filter(d -> d.getLocation().getY() > head && horizontal(d.getLocation(), suspect.getLocation()) < 0.3)
                .toList();
    }

    private static double horizontal(Location a, Location b) {
        return Math.hypot(a.getX() - b.getX(), a.getZ() - b.getZ());
    }

    @Test
    void policeSirenPutsAnEnemyUsingASkillOnTheWantedList() {
        try (Scenario s = Scenario.ffa("police")) {
            s.ticks(10);
            assertTrue(lightBar(s, s.enemy).isEmpty(), "nobody is wanted yet");
            s.assertSucceeded(s.match.useActive(s.enemy), "the enemy uses its skill");
            s.ticks(6);
            assertEquals(2, lightBar(s, s.enemy).size(), "a red and a blue lamp over the suspect");
            assertTrue(lightBar(s, s.enemy2).isEmpty(), "the quiet enemy is not wanted");
            s.melee(s.owner, s.enemy, 4);
            s.melee(s.owner, s.enemy2, 4);
            assertEquals(4 * (1 + s.param("wanted_bonus") / 100), s.damageBy(s.owner, s.enemy), EPSILON,
                    "the police hits a suspect harder");
            assertEquals(4, s.damageBy(s.owner, s.enemy2), EPSILON, "and nobody else");
            // The light bar follows the suspect.
            s.place(s.enemy, 3.5, 7.5, 180);
            s.ticks(3);
            assertEquals(2, lightBar(s, s.enemy).size(), "the lamps go along");
            s.ticks(s.paramTicks("wanted_duration"));
            assertTrue(lightBar(s, s.enemy).isEmpty(), "the warrant ran out");
            s.clearHits();
            s.melee(s.owner, s.enemy, 4);
            assertEquals(4, s.damageBy(s.owner, s.enemy), EPSILON, "no bonus after the warrant");
            s.finishClean();
        }
    }

    @Test
    void policeSirenHearsAnUltimateToo() {
        try (Scenario s = Scenario.ffa("police")) {
            s.ticks(10);
            s.match.setGauge(s.enemy2, 100);
            s.ticks(6); // the full gauge is heard first, as it fills up in a real fight
            s.assertSucceeded(s.match.useUltimate(s.enemy2), "the enemy's ultimate");
            s.ticks(6);
            assertEquals(2, lightBar(s, s.enemy2).size(), "a spent gauge gives it away");
            assertTrue(lightBar(s, s.enemy).isEmpty());
            s.finishClean();
        }
    }

    @Test
    void policeSirenOnlyHearsSkillsWithinItsRadius() {
        try (Scenario s = Scenario.ffa("police")) {
            double radius = s.param("siren_radius");
            s.place(s.enemy, 0.5, radius + 4, 180);
            s.ticks(10);
            s.assertSucceeded(s.match.useActive(s.enemy), "a skill far away");
            s.ticks(10);
            assertTrue(lightBar(s, s.enemy).isEmpty(), "out of earshot");
            s.melee(s.owner, s.enemy, 4);
            assertEquals(4, s.damageBy(s.owner, s.enemy), EPSILON);
            s.finishClean();
        }
    }

    @Test
    void policeSirenNeverGivesAHiddenSuspectAway() {
        try (Scenario s = Scenario.ffa("police")) {
            s.ticks(10);
            s.owner.hidePlayer(s.h.hub.plugin(), s.enemy);
            s.assertSucceeded(s.match.useActive(s.enemy), "a skill from stealth");
            s.ticks(8);
            assertTrue(lightBar(s, s.enemy).isEmpty(), "no light over an enemy the police cannot see");
            s.owner.showPlayer(s.h.hub.plugin(), s.enemy);
            s.ticks(3);
            assertEquals(2, lightBar(s, s.enemy).size(), "it is still wanted once it shows up");
            s.owner.hidePlayer(s.h.hub.plugin(), s.enemy);
            s.ticks(2);
            assertTrue(lightBar(s, s.enemy).isEmpty(), "and the light goes out when it hides again");
            s.owner.showPlayer(s.h.hub.plugin(), s.enemy);
            s.finishClean();
        }
    }

    @Test
    void policeTaserStunsTheFirstEnemyItReaches() {
        try (Scenario s = Scenario.ffa("police")) {
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "테이저");
            s.ticks(1);
            assertEquals(2, blocks(s, Material.YELLOW_CONCRETE).size(), "two darts in the air");
            s.h.tickUntil(() -> s.damageTaken(s.enemy) > 0, 20, "the darts to hit");
            assertEquals(s.param("taser_damage"), s.damageBy(s.owner, s.enemy), EPSILON);
            assertEquals(DamageType.MAGIC, s.hitsBy(s.owner, s.enemy).getFirst().type());
            assertTrue(rooted(s.enemy), "locked by the current");
            assertFalse(rooted(s.enemy2), "only the one it hit");
            s.ticks(s.paramTicks("taser_stun") + 1);
            assertFalse(rooted(s.enemy), "free after " + s.param("taser_stun") + " s");
            s.ticks(10);
            assertNoDisplays(s, "the darts are pulled out");
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON);
            s.finishClean();
        }
    }

    @Test
    void policeTaserHoldsAWantedSuspectLongerAndHitsItHarder() {
        try (Scenario s = Scenario.ffa("police")) {
            s.ticks(10);
            s.assertSucceeded(s.match.useActive(s.enemy), "the enemy uses its skill");
            s.ticks(6);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "테이저");
            s.h.tickUntil(() -> s.damageTaken(s.enemy) > 0, 20, "the darts to hit");
            assertEquals(s.param("taser_damage") * (1 + s.param("wanted_bonus") / 100), s.damageBy(s.owner, s.enemy),
                    EPSILON);
            s.ticks(s.paramTicks("taser_stun") + 1);
            assertTrue(rooted(s.enemy), "a suspect stays locked longer");
            s.ticks(s.paramTicks("taser_wanted_stun") - s.paramTicks("taser_stun"));
            assertFalse(rooted(s.enemy), "free after " + s.param("taser_wanted_stun") + " s");
            s.finishClean();
        }
    }

    @Test
    void policeTaserMissGivesPartOfTheCooldownBack() {
        try (Scenario s = Scenario.ffa("police")) {
            s.place(s.enemy, 8.5, 0.5, 90);
            s.place(s.enemy2, -8.5, 0.5, 270);
            s.aimAtFloor(s.owner, 0.5, 30);
            s.assertSucceeded(s.active(), "테이저");
            s.ticks(12);
            double expected = s.param("cooldown") * (1 - s.param("taser_refund") / 100) - 0.6;
            assertEquals(expected, s.ctx().cooldownRemaining(s.owner), 0.3, "half the cooldown back");
            assertEquals(0, s.damageTaken(s.enemy) + s.damageTaken(s.enemy2), EPSILON);
            s.finishClean();
        }
    }

    @Test
    void policeTaserDartsPointAlongTheShot() {
        try (Scenario s = Scenario.ffa("police")) {
            turnedLayout(s);
            s.place(s.enemy, -9.5, 0.5, -90);
            s.aimAt(s.owner, s.enemy);
            Vector shot = s.owner.getEyeLocation().getDirection();
            s.assertSucceeded(s.active(), "테이저");
            s.ticks(2);
            List<BlockDisplay> darts = blocks(s, Material.YELLOW_CONCRETE);
            assertEquals(2, darts.size());
            for (BlockDisplay dart : darts) {
                assertEquals(0, dart.getLocation().getYaw(), 1.0E-3);
                assertEquals(0, dart.getLocation().getPitch(), 1.0E-3);
                assertTrue(axis(dart, 0, 0, 1).dot(shot) > 0.98, "the dart flies point first: " + axis(dart, 0, 0, 1));
            }
            s.ticks(60);
            s.finishClean();
        }
    }

    @Test
    void policeArrestDragsJailsAndReleasesTheSuspect() {
        try (Scenario s = noGaugeGain(Scenario.builder("police")).start()) {
            s.place(s.enemy, 0.5, 10.5, 180);
            s.place(s.enemy2, 6.5, -6.5, 0);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "체포");
            s.h.tickUntil(() -> s.damageTaken(s.enemy) > 0, 30, "the cuffs to snap shut");
            assertEquals(s.param("arrest_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "철컥");
            s.h.tickUntil(() -> rooted(s.enemy), 60, "the cell to close");
            assertTrue(horizontal(s.enemy.getLocation(), s.owner.getLocation()) < 3.5,
                    "dragged up to the police: " + s.enemy.getLocation().toVector());
            assertTrue(s.ctx().isSilenced(s.enemy), "no skills in jail");
            assertTrue(s.h.hub.tempBlocks().size() >= 8, "a cell of bars: " + s.h.hub.tempBlocks().size());
            Location feet = s.enemy.getLocation();
            assertEquals(feet.getBlockX() + 0.5, feet.getX(), EPSILON, "centred in its cell");
            assertEquals(feet.getBlockZ() + 0.5, feet.getZ(), EPSILON, "centred in its cell");
            int bars = 0;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    Block block = s.world.getBlockAt(feet.getBlockX() + dx, feet.getBlockY(), feet.getBlockZ() + dz);
                    if (block.getType() == Material.IRON_BARS) {
                        bars++;
                    }
                }
            }
            assertEquals(8, bars, "bars all around the prisoner");
            assertEquals(Material.IRON_TRAPDOOR,
                    s.world.getBlockAt(feet.getBlockX(), feet.getBlockY() + 2, feet.getBlockZ()).getType(), "a lid");
            assertEquals(1, blocks(s, Material.GRAY_STAINED_GLASS).size(), "the police car is on the scene");
            s.ticks(s.paramTicks("jail_duration") + 2);
            assertFalse(rooted(s.enemy), "released");
            assertFalse(s.ctx().isSilenced(s.enemy), "may use skills again");
            assertEquals(0, s.h.hub.tempBlocks().size(), "the cell is gone");
            int shocks = (int) Math.ceil(s.param("jail_duration") / s.param("jail_shock_period"));
            assertEquals(s.param("arrest_damage") + shocks * s.param("jail_shock"), s.damageBy(s.owner, s.enemy),
                    EPSILON, "the cuffs and " + shocks + " shocks");
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON, "nobody else");
            s.ticks(40);
            assertNoDisplays(s, "the car drove off");
            s.finishClean();
        }
    }

    @Test
    void policeArrestedSuspectStaysInItsCellAndOnTheFloor() {
        try (Scenario s = Scenario.ffa("police")) {
            s.place(s.enemy, 0.7, 7.3, 180);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "체포");
            s.h.tickUntil(() -> rooted(s.enemy), 60, "the cell to close");
            s.ticks(2);
            Location cell = s.enemy.getLocation();
            for (int i = 0; i < s.paramTicks("jail_duration") - 4; i++) {
                s.ticks(1);
                assertTrue(horizontal(s.enemy.getLocation(), cell) < 0.05, "it does not drift: "
                        + s.enemy.getLocation().toVector());
            }
            assertStandsOnFloor(s.enemy, "the prisoner");
            s.ticks(60);
            s.finishClean();
        }
    }

    @Test
    void policeArrestNeedsAnAimedSuspect() {
        try (Scenario s = Scenario.ffa("police")) {
            s.place(s.enemy, 8.5, -8.5, 0);
            s.place(s.enemy2, -8.5, -8.5, 0);
            s.aimAtFloor(s.owner, 0.5, 12.5);
            ActivationResult result = s.ultimate();
            assertFalse(result.succeeded(), "nobody to arrest");
            assertEquals(100, gauge(s, s.owner), EPSILON, "the gauge is kept");
            s.owner.hidePlayer(s.h.hub.plugin(), s.enemy);
            s.place(s.enemy, 0.5, 6.5, 180);
            s.aimAt(s.owner, s.enemy);
            assertFalse(s.ultimate().succeeded(), "a hidden enemy cannot be arrested");
            s.owner.showPlayer(s.h.hub.plugin(), s.enemy);
            assertEquals(0, s.h.hub.tempBlocks().size());
            s.finishClean();
        }
    }

    @Test
    void policeSuspectVanishingBeforeTheCuffsReachItGetsAway() {
        try (Scenario s = Scenario.ffa("police")) {
            s.place(s.enemy, 0.5, 12.5, 180);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "체포");
            s.ticks(2);
            s.owner.hidePlayer(s.h.hub.plugin(), s.enemy);
            s.ticks(30);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "the cuffs closed on thin air");
            assertFalse(rooted(s.enemy), "no cell");
            assertEquals(0, s.h.hub.tempBlocks().size());
            assertTrue(entities(s, ItemDisplay.class).isEmpty(), "the cuffs do not home in on a hidden suspect");
            s.owner.showPlayer(s.h.hub.plugin(), s.enemy);
            s.ticks(20);
            assertNoDisplays(s, "nothing left");
            s.finishClean();
        }
    }

    @Test
    void policeCarParksBesideTheCellAlongTheLineToIt() {
        try (Scenario s = Scenario.ffa("police")) {
            turnedLayout(s);
            s.place(s.enemy, -9.5, 0.5, -90);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "체포");
            s.h.tickUntil(() -> rooted(s.enemy), 60, "the cell to close");
            s.ticks(14);
            BlockDisplay chassis = blocks(s, Material.BLUE_CONCRETE).stream()
                    .max((a, b) -> Float.compare(a.getTransformation().getScale().z(),
                            b.getTransformation().getScale().z()))
                    .orElseThrow();
            assertEquals(0, chassis.getLocation().getYaw(), 1.0E-3);
            assertEquals(0, chassis.getLocation().getPitch(), 1.0E-3);
            Vector length = axis(chassis, 0, 0, 1);
            assertTrue(Math.abs(length.dot(facing(90))) > 0.98, "the car is parked along the line: " + length);
            double off = Math.abs(chassis.getLocation().getZ() - s.enemy.getLocation().getZ());
            assertTrue(off > 1.5 && off < 3.5, "beside the cell, not on it: " + chassis.getLocation().toVector());
            s.ticks(100);
            s.finishClean();
        }
    }

    // ================================================================== 총잡이 gunslinger

    private static List<ItemDisplay> shells(Scenario s) {
        return entities(s, ItemDisplay.class).stream()
                .filter(display -> display.getItemStack().getType() == Material.GOLD_NUGGET).toList();
    }

    private static List<TextDisplay> labels(Scenario s, String text) {
        return entities(s, TextDisplay.class).stream()
                .filter(label -> Scenario.plain(label.text()).contains(text)).toList();
    }

    @Test
    void gunslingerFiresSixQuickShotsThenDumpsTheShellsAndReloads() {
        try (Scenario s = noGaugeGain(Scenario.builder("gunslinger")).start()) {
            int rounds = (int) s.param("rounds");
            for (int i = 1; i <= rounds; i++) {
                s.aimAt(s.owner, s.enemy);
                s.assertSucceeded(activeWhenReady(s, 20), "shot " + i);
                double left = s.ctx().cooldownRemaining(s.owner);
                if (i < rounds) {
                    assertEquals(s.param("shot_interval"), left, 0.06, "only a short gap after shot " + i);
                } else {
                    assertEquals(s.param("cooldown"), left, 0.06, "the reload after the last round");
                }
            }
            assertEquals(rounds * s.param("shot_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "six body shots");
            assertTrue(s.hitsBy(s.owner, s.enemy).stream().allMatch(hit -> hit.type() == DamageType.MAGIC));
            assertFalse(s.active().succeeded(), "no seventh round");
            s.h.tickUntil(() -> shells(s).size() == rounds, 20, "the shells to be dumped");
            s.h.tickUntil(() -> shells(s).stream().allMatch(shell -> shell.getLocation().getY()
                    < FlatWorld.SURFACE + 0.05), 30, "the shells to land");
            for (ItemDisplay shell : shells(s)) {
                assertEquals(FlatWorld.SURFACE + 0.03, shell.getLocation().getY(), 0.01, "a shell lies on the floor");
            }
            s.ticks(s.paramTicks("cooldown"));
            assertNoDisplays(s, "the shells are gone");
            s.clearHits();
            for (int i = 1; i <= rounds; i++) {
                s.aimAt(s.owner, s.enemy);
                s.assertSucceeded(activeWhenReady(s, 20), "reloaded shot " + i);
                if (i < rounds) {
                    assertEquals(s.param("shot_interval"), s.ctx().cooldownRemaining(s.owner), 0.06, "a full cylinder");
                }
            }
            assertEquals(rounds * s.param("shot_damage"), s.damageBy(s.owner, s.enemy), EPSILON);
            s.ticks(120);
            s.finishClean();
        }
    }

    @Test
    void gunslingerHeadshotHitsHarder() {
        try (Scenario s = Scenario.ffa("gunslinger")) {
            s.aimAt(s.owner, s.enemy.getEyeLocation().add(0, 0.08, 0));
            s.assertSucceeded(s.active(), "속사");
            assertEquals(s.param("shot_damage") * (1 + s.param("headshot_bonus") / 100), s.damageBy(s.owner, s.enemy),
                    EPSILON, "a head shot");
            assertEquals(1, labels(s, "헤드샷").size(), "헤드샷! pops up");
            s.clearHits();
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(activeWhenReady(s, 20), "속사");
            assertEquals(s.param("shot_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "a body shot");
            s.ticks(40);
            s.finishClean();
        }
    }

    @Test
    void gunslingerShotsStopAtWallsAndOnlyHitTheFirstEnemy() {
        try (Scenario s = Scenario.ffa("gunslinger")) {
            s.place(s.enemy2, 0.5, 8.5, 180);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "속사");
            assertEquals(s.param("shot_damage"), s.damageBy(s.owner, s.enemy), EPSILON);
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON, "the bullet stops in the first enemy");
            s.world.wallAlongX(-3, 3, 2, 3, Material.STONE);
            s.clearHits();
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(activeWhenReady(s, 20), "속사");
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "a wall stops the bullet");
            s.world.wallAlongX(-3, 3, 2, 3, Material.AIR);
            s.clearHits();
            s.place(s.enemy2, 6.5, 8.5, 180);
            s.place(s.enemy, 0.5, s.param("shot_range") + 3, 180);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(activeWhenReady(s, 20), "속사");
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "out of the revolver's range");
            s.clearHits();
            s.owner.hidePlayer(s.h.hub.plugin(), s.enemy2);
            s.place(s.enemy2, 0.5, 4.5, 180);
            s.aimAt(s.owner, s.enemy2);
            s.assertSucceeded(activeWhenReady(s, 20), "속사");
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON, "a hidden enemy cannot be aimed at");
            s.owner.showPlayer(s.h.hub.plugin(), s.enemy2);
            s.ticks(40);
            s.finishClean();
        }
    }

    @Test
    void gunslingerTopsOffTheCylinderAfterIdling() {
        try (Scenario s = Scenario.ffa("gunslinger")) {
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "shot 1");
            s.assertSucceeded(activeWhenReady(s, 20), "shot 2");
            s.ticks(s.paramTicks("idle_reload") + 2);
            int rounds = (int) s.param("rounds");
            for (int i = 1; i < rounds; i++) {
                s.assertSucceeded(activeWhenReady(s, 20), "topped-off shot " + i);
                assertEquals(s.param("shot_interval"), s.ctx().cooldownRemaining(s.owner), 0.06,
                        "the cylinder was full again (shot " + i + ")");
            }
            s.assertSucceeded(activeWhenReady(s, 20), "the sixth");
            assertEquals(s.param("cooldown"), s.ctx().cooldownRemaining(s.owner), 0.06, "now it reloads");
            s.ticks(120);
            s.finishClean();
        }
    }

    @Test
    void gunslingerTracerAndMuzzleFlashPointAlongTheShot() {
        try (Scenario s = Scenario.ffa("gunslinger")) {
            turnedLayout(s);
            s.aimAt(s.owner, s.enemy);
            Vector shot = s.owner.getEyeLocation().getDirection();
            s.assertSucceeded(s.active(), "속사");
            List<BlockDisplay> tracers = blocks(s, Material.WHITE_CONCRETE);
            List<BlockDisplay> flashes = blocks(s, Material.YELLOW_CONCRETE);
            assertEquals(1, tracers.size(), "a tracer");
            assertEquals(1, flashes.size(), "a muzzle flash");
            for (BlockDisplay display : List.of(tracers.getFirst(), flashes.getFirst())) {
                assertEquals(0, display.getLocation().getYaw(), 1.0E-3);
                assertEquals(0, display.getLocation().getPitch(), 1.0E-3);
                assertTrue(axis(display, 0, 0, 1).dot(shot) > 0.98, display.getBlock().getMaterial()
                        + " lies along the shot: " + axis(display, 0, 0, 1));
            }
            s.ticks(40);
            s.finishClean();
        }
    }

    @Test
    void gunslingerHighNoonShootsEveryLockedEnemyAtOnce() {
        try (Scenario s = noGaugeGain(Scenario.builder("gunslinger")).start()) {
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "정오의 결투");
            s.ticks(20);
            assertTrue(modifierSum(s.owner, Attribute.MOVEMENT_SPEED) < 0, "slowed while drawing");
            assertEquals(2, entities(s, TextDisplay.class).size(), "a reticle over each enemy in sight");
            assertEquals(0, s.damageTaken(s.enemy) + s.damageTaken(s.enemy2), EPSILON, "nothing before noon");
            s.h.tickUntil(() -> s.damageTaken(s.enemy) > 0, s.paramTicks("noon_draw"), "noon");
            assertEquals(s.param("noon_max"), s.damageBy(s.owner, s.enemy), EPSILON, "fully locked");
            assertEquals(s.param("noon_max"), s.damageBy(s.owner, s.enemy2), EPSILON, "both at the same instant");
            assertEquals(0, modifierSum(s.owner, Attribute.MOVEMENT_SPEED), EPSILON, "the draw is over");
            assertTrue(entities(s, TextDisplay.class).isEmpty(), "the reticles are gone");
            s.ticks(40);
            assertNoDisplays(s, "the sun set");
            s.finishClean();
        }
    }

    @Test
    void gunslingerHighNoonMissesThoseOutOfSightOrBehindCover() {
        try (Scenario s = Scenario.ffa("gunslinger")) {
            s.place(s.enemy2, 0.5, -6.5, 0);
            s.world.wallAlongX(-3, 3, 2, 3, Material.STONE);
            s.aimAtFloor(s.owner, 0.5, 8.5);
            s.assertSucceeded(s.ultimate(), "정오의 결투");
            s.ticks(10);
            assertTrue(entities(s, TextDisplay.class).isEmpty(), "nobody to lock on");
            s.ticks(s.paramTicks("noon_draw") + 10);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "behind cover");
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON, "behind the gunslinger");
            s.world.wallAlongX(-3, 3, 2, 3, Material.AIR);
            s.finishClean();
        }
    }

    @Test
    void gunslingerHighNoonLockDrainsWhenTheVictimTakesCover() {
        try (Scenario s = Scenario.ffa("gunslinger")) {
            s.place(s.enemy2, 8.5, -6.5, 0);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "정오의 결투");
            s.ticks(18);
            assertEquals(1, entities(s, TextDisplay.class).size(), "locking on");
            s.world.wallAlongX(-3, 3, 2, 3, Material.STONE);
            s.ticks(s.paramTicks("noon_draw"));
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "it got away behind the wall in time");
            s.world.wallAlongX(-3, 3, 2, 3, Material.AIR);
            s.finishClean();
        }
    }

    @Test
    void gunslingerHighNoonHitsAsHardAsTheLockFilled() {
        try (Scenario s = Scenario.ffa("gunslinger")) {
            s.place(s.enemy2, 8.5, -6.5, 0);
            s.world.wallAlongX(-3, 3, 2, 3, Material.STONE);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "정오의 결투");
            s.ticks(s.paramTicks("noon_draw") / 2);
            s.world.wallAlongX(-3, 3, 2, 3, Material.AIR);
            s.h.tickUntil(() -> s.damageTaken(s.enemy) > 0, s.paramTicks("noon_draw"), "noon");
            double damage = s.damageBy(s.owner, s.enemy);
            assertTrue(damage > s.param("noon_min") + 0.5 && damage < s.param("noon_max") - 0.5,
                    "half a draw in sight, a middling shot: " + damage);
            s.finishClean();
        }
    }

    @Test
    void gunslingerHighNoonNeverLocksOntoAHiddenEnemyAndKeepsToItsTargetLimit() {
        try (Scenario s = Scenario.builder("gunslinger").param("noon_targets", 1).start()) {
            s.owner.hidePlayer(s.h.hub.plugin(), s.enemy);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "정오의 결투");
            s.ticks(12);
            assertEquals(1, entities(s, TextDisplay.class).size(), "only the visible enemy gets a reticle");
            s.ticks(s.paramTicks("noon_draw"));
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "the hidden enemy is spared");
            assertEquals(s.param("noon_max"), s.damageBy(s.owner, s.enemy2), EPSILON);
            s.owner.showPlayer(s.h.hub.plugin(), s.enemy);
            s.match.setGauge(s.owner, 100);
            s.clearHits();
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "정오의 결투");
            s.ticks(s.paramTicks("noon_draw") + 4);
            assertEquals(1, (s.damageTaken(s.enemy) > 0 ? 1 : 0) + (s.damageTaken(s.enemy2) > 0 ? 1 : 0),
                    "one target only");
            s.finishClean();
        }
    }

    // ================================================================== 광부 miner

    private static int gemsNear(Scenario s, Player owner) {
        return (int) entities(s, ItemDisplay.class).stream()
                .filter(gem -> gem.getLocation().distance(owner.getLocation()) < 2.0).count();
    }

    @Test
    void minerHitsTireTheEnemyAndQuickenThePick() {
        try (Scenario s = Scenario.ffa("miner")) {
            int max = (int) s.param("vein_max");
            for (int i = 1; i <= max + 1; i++) {
                s.melee(s.owner, s.enemy, 2);
                PotionEffect fatigue = s.enemy.getPotionEffect(PotionEffectType.MINING_FATIGUE);
                assertNotNull(fatigue, "the enemy's arms grow heavy");
                assertEquals((int) s.param("fatigue_level") - 1, fatigue.getAmplifier());
                assertEquals(s.paramTicks("fatigue_duration"), fatigue.getDuration(), 1);
                PotionEffect haste = s.owner.getPotionEffect(PotionEffectType.HASTE);
                assertNotNull(haste, "the pick quickens");
                assertEquals(Math.min(i, max) - 1, haste.getAmplifier(), "haste stacks up to " + max);
                s.ticks(4);
                assertEquals(Math.min(i, max), gemsNear(s, s.owner), "an ore circles the miner per stack");
            }
            assertFalse(s.enemy2.hasPotionEffect(PotionEffectType.MINING_FATIGUE), "only the one it hit");
            s.melee(s.enemy2, s.owner, 2);
            assertFalse(s.enemy2.hasPotionEffect(PotionEffectType.MINING_FATIGUE), "being hit tires nobody");
            s.ticks(s.paramTicks("vein_duration") + 2);
            assertFalse(s.owner.hasPotionEffect(PotionEffectType.HASTE), "the quickening wears off");
            s.ticks(8);
            assertEquals(0, gemsNear(s, s.owner), "the ores crumble away");
            s.finishClean();
        }
    }

    @Test
    void minerRockfallBuriesTheAimedSpotUnderRocksAndRubble() {
        try (Scenario s = noGaugeGain(Scenario.builder("miner")).start()) {
            s.place(s.enemy2, 8.5, -6.5, 0);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "낙석");
            s.ticks(2);
            List<BlockDisplay> hanging = entities(s, BlockDisplay.class).stream()
                    .filter(rock -> rock.getLocation().getY() > FlatWorld.SURFACE + 5).toList();
            assertEquals((int) s.param("rock_count"), hanging.size(), "every rock hangs overhead first");
            for (BlockDisplay rock : hanging) {
                assertTrue(horizontal(rock.getLocation(), s.enemy.getLocation()) <= s.param("rock_radius") + 1.0E-6,
                        "over the aimed spot: " + rock.getLocation().toVector());
            }
            s.ticks(s.paramTicks("rock_delay") - 3);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "the rocks still hang");
            s.h.tickUntil(() -> s.damageTaken(s.enemy) > 0, 10, "the first rock");
            assertTrue(slowed(s.enemy), "staggered by the rock");
            s.ticks(30);
            double damage = s.damageBy(s.owner, s.enemy);
            assertTrue(damage >= s.param("rock_damage") - EPSILON
                    && damage <= s.param("rock_damage") * s.param("rock_max_hits") + EPSILON, "rock hits: " + damage);
            assertTrue(s.h.hub.tempBlocks().size() > 0, "rubble is left behind");
            for (TestPlayer player : List.of(s.owner, s.enemy, s.enemy2)) {
                Location feet = player.getLocation();
                assertFalse(s.h.hub.tempBlocks().isTemporary(feet.getBlock()), "nobody is buried");
                assertFalse(s.h.hub.tempBlocks().isTemporary(feet.clone().add(0, 1, 0).getBlock()));
            }
            assertEquals(0, s.damageTaken(s.owner) + s.damageTaken(s.enemy2), EPSILON);
            s.ticks(s.paramTicks("rubble_duration") + 4);
            assertEquals(0, s.h.hub.tempBlocks().size(), "the rubble is cleared");
            assertNoDisplays(s, "the dust settled");
            s.finishClean();
        }
    }

    @Test
    void minerRockfallNeedsGroundBelowTheAim() {
        try (Scenario s = Scenario.ffa("miner")) {
            s.place(s.owner, 0.5, FlatWorld.SURFACE, 0.5, 0, -90);
            assertFalse(s.active().succeeded(), "nothing but sky");
            assertEquals(0, s.ctx().cooldownRemaining(s.owner), EPSILON, "no cooldown spent");
            s.finishClean();
        }
    }

    @Test
    void minerCartScoopsEnemiesOffTheRailsAndDumpsThemAtTheEnd() {
        try (Scenario s = noGaugeGain(Scenario.builder("miner")).start()) {
            s.place(s.enemy, 0.5, 6.5, 180);
            s.place(s.enemy2, 4.5, 6.5, 180);
            s.assertSucceeded(s.ultimate(), "광산 수레");
            s.ticks(4);
            assertFalse(blocks(s, Material.RAIL).isEmpty(), "the rails click down");
            assertEquals(1, blocks(s, Material.CAULDRON).size(), "the cart waits at the start");
            s.ticks(s.paramTicks("cart_delay") - 4);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "the rails come first");
            s.h.tickUntil(() -> s.damageTaken(s.enemy) > 0, 30, "the cart to reach the enemy");
            assertEquals(s.param("cart_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "scooped up");
            s.ticks(4);
            assertTrue(s.enemy.getLocation().getZ() > 7.5, "carried down the line: " + s.enemy.getLocation().toVector());
            s.h.tickUntil(() -> s.damageTaken(s.enemy) > s.param("cart_damage") + EPSILON, 40, "the crash");
            assertEquals(s.param("cart_damage") + s.param("crash_damage"), s.damageBy(s.owner, s.enemy), EPSILON,
                    "dumped at the buffer stop");
            s.ticks(10);
            assertTrue(s.enemy.getLocation().getZ() > 14, "thrown out at the end: " + s.enemy.getLocation().toVector());
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON, "off the rails, untouched");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.ticks(80);
            assertNoDisplays(s, "the rails are taken up");
            s.finishClean();
        }
    }

    @Test
    void minerTrackEndsBeforeAWall() {
        try (Scenario s = Scenario.ffa("miner")) {
            s.world.wallAlongX(-3, 3, 9, 3, Material.STONE);
            s.place(s.enemy, 0.5, 5.5, 180);
            s.place(s.enemy2, 8.5, -6.5, 0);
            s.assertSucceeded(s.ultimate(), "광산 수레");
            s.ticks(s.paramTicks("cart_delay") + 1);
            for (BlockDisplay rail : blocks(s, Material.RAIL)) {
                Vector along = axis(rail, 0, 0, 1);
                double end = rail.getLocation().getZ() + along.getZ() * rail.getTransformation().getScale().z();
                assertTrue(end < 9 - 0.2, "no rail runs into the wall: " + end);
            }
            for (BlockDisplay stop : blocks(s, Material.RED_TERRACOTTA)) {
                assertTrue(stop.getLocation().getZ() < 9, "the buffer stop stands before the wall");
            }
            s.h.tickUntil(() -> s.damageTaken(s.enemy) > s.param("cart_damage") + EPSILON, 60, "the crash");
            s.ticks(80);
            s.world.wallAlongX(-3, 3, 9, 3, Material.AIR);
            s.finishClean();
        }
    }

    @Test
    void minerTrackFollowsTheGroundUpAStep() {
        try (Scenario s = Scenario.ffa("miner")) {
            for (int x = -3; x <= 3; x++) {
                for (int z = 6; z <= 22; z++) {
                    s.world.setBlock(x, FlatWorld.FLOOR_Y + 1, z, Material.STONE);
                }
            }
            s.place(s.enemy, 0.5, FlatWorld.SURFACE + 1, 9.5, 180, 0);
            s.place(s.enemy2, 8.5, -6.5, 0);
            s.assertSucceeded(s.ultimate(), "광산 수레");
            s.ticks(s.paramTicks("cart_delay"));
            List<BlockDisplay> rails = blocks(s, Material.RAIL);
            assertTrue(rails.stream().anyMatch(rail -> rail.getLocation().getZ() > 6), "the track climbs the step");
            for (BlockDisplay rail : rails) {
                double start = rail.getLocation().getZ();
                double end = start + rail.getTransformation().getScale().z();
                // The rails split half a track step before the edge of the step (z = 6).
                double expected = end <= 6 + 1.0E-6 ? FlatWorld.SURFACE : start >= 5.9 ? FlatWorld.SURFACE + 1
                        : Double.NaN;
                assertFalse(Double.isNaN(expected), "no rail spans the step: " + start + ".." + end);
                assertEquals(expected, rail.getLocation().getY(), EPSILON, "every rail lies on its own ground");
            }
            s.h.tickUntil(() -> s.damageTaken(s.enemy) > 0, 40, "the cart to climb up to the enemy");
            s.h.tickUntil(() -> s.damageTaken(s.enemy) > s.param("cart_damage") + EPSILON, 40, "the crash");
            s.ticks(100);
            for (int x = -3; x <= 3; x++) {
                for (int z = 6; z <= 22; z++) {
                    s.world.setBlock(x, FlatWorld.FLOOR_Y + 1, z, Material.AIR);
                }
            }
            s.finishClean();
        }
    }

    @Test
    void minerTrackRunsOverRubbleRightInFront() {
        try (Scenario s = Scenario.ffa("miner")) {
            // Rubble of the miner's own rockfall right before its feet and again a little further: bumps, not walls.
            s.world.setBlock(0, FlatWorld.FLOOR_Y + 1, 1, Material.COBBLESTONE);
            s.world.setBlock(0, FlatWorld.FLOOR_Y + 1, 2, Material.COBBLESTONE);
            s.world.setBlock(0, FlatWorld.FLOOR_Y + 1, 5, Material.TUFF);
            s.place(s.enemy, 0.5, 8.5, 180);
            s.place(s.enemy2, 8.5, -6.5, 0);
            s.assertSucceeded(s.ultimate(), "광산 수레");
            s.h.tickUntil(() -> s.damageTaken(s.enemy) > s.param("cart_damage") + EPSILON, 80,
                    "the cart to bump over the rubble, scoop the enemy and crash");
            s.ticks(100);
            s.world.setBlock(0, FlatWorld.FLOOR_Y + 1, 1, Material.AIR);
            s.world.setBlock(0, FlatWorld.FLOOR_Y + 1, 2, Material.AIR);
            s.world.setBlock(0, FlatWorld.FLOOR_Y + 1, 5, Material.AIR);
            s.finishClean();
        }
    }

    @Test
    void minerCartNeedsRoomForItsRails() {
        try (Scenario s = Scenario.ffa("miner")) {
            s.world.wallAlongX(-3, 3, 1, 3, Material.STONE);
            s.place(s.enemy, 0.5, 5.5, 180);
            ActivationResult result = s.ultimate();
            assertFalse(result.succeeded(), "a wall right in front");
            assertEquals(100, gauge(s, s.owner), EPSILON, "the gauge is kept");
            assertNoDisplays(s, "no rails");
            s.world.wallAlongX(-3, 3, 1, 3, Material.AIR);
            s.finishClean();
        }
    }

    @Test
    void minerRailsLieFlatOnTheGroundAlongTheTrack() {
        try (Scenario s = Scenario.ffa("miner")) {
            turnedLayout(s);
            s.assertSucceeded(s.ultimate(), "광산 수레");
            s.ticks(s.paramTicks("cart_delay"));
            List<BlockDisplay> rails = blocks(s, Material.RAIL);
            assertTrue(rails.size() >= 4, "a track: " + rails.size());
            for (BlockDisplay rail : rails) {
                assertEquals(0, rail.getLocation().getYaw(), 1.0E-3);
                assertEquals(0, rail.getLocation().getPitch(), 1.0E-3);
                assertEquals(FlatWorld.SURFACE, rail.getLocation().getY(), EPSILON, "on the ground");
                assertTrue(axis(rail, 0, 0, 1).dot(facing(90)) > 0.98, "along the miner's facing: "
                        + axis(rail, 0, 0, 1));
                assertTrue(rail.getLocation().getX() < 0, "laid out in front of the miner");
            }
            s.ticks(100);
            s.finishClean();
        }
    }

    // ================================================================== 대장장이 blacksmith

    @Test
    void blacksmithThirdHitIsACrushingBlowThatBreaksArmor() {
        try (Scenario s = Scenario.ffa("blacksmith")) {
            s.melee(s.owner, s.enemy, 4);
            s.melee(s.owner, s.enemy2, 4);
            s.ticks(2);
            assertEquals(4, s.damageBy(s.owner, s.enemy), EPSILON, "heating up: no bonus yet");
            assertEquals(0, modifierSum(s.enemy, Attribute.ARMOR), EPSILON);
            s.melee(s.owner, s.enemy, 4);
            s.ticks(2);
            assertEquals(8 + s.param("strike_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "the third hit strikes");
            assertTrue(s.hitsBy(s.owner, s.enemy).stream().anyMatch(hit -> hit.type() == DamageType.MAGIC),
                    "the blow ignores armor");
            assertEquals(-s.param("sunder_armor"), modifierSum(s.enemy, Attribute.ARMOR), EPSILON, "armor cracked");
            assertEquals(0, modifierSum(s.enemy2, Attribute.ARMOR), EPSILON);
            for (int i = 0; i < 3; i++) {
                s.melee(s.owner, s.enemy, 4);
                s.ticks(2);
            }
            assertEquals(-s.param("sunder_armor"), modifierSum(s.enemy, Attribute.ARMOR), EPSILON,
                    "a second blow renews the crack instead of stacking it");
            s.ticks(s.paramTicks("sunder_duration") + 2);
            assertEquals(0, modifierSum(s.enemy, Attribute.ARMOR), EPSILON, "the armor holds again");
            s.finishClean();
        }
    }

    @Test
    void blacksmithBladeCoolsWithoutHits() {
        try (Scenario s = Scenario.ffa("blacksmith")) {
            s.melee(s.owner, s.enemy, 4);
            s.melee(s.owner, s.enemy, 4);
            s.ticks(s.paramTicks("heat_decay") + 2);
            s.melee(s.owner, s.enemy, 4);
            s.ticks(2);
            assertEquals(12, s.damageBy(s.owner, s.enemy), EPSILON, "the heat was lost");
            assertEquals(0, modifierSum(s.enemy, Attribute.ARMOR), EPSILON);
            s.melee(s.owner, s.enemy, 4);
            s.melee(s.owner, s.enemy, 4);
            s.ticks(2);
            assertEquals(20 + s.param("strike_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "heated up again");
            s.melee(s.enemy, s.owner, 4);
            s.melee(s.enemy, s.owner, 4);
            s.melee(s.enemy, s.owner, 4);
            s.ticks(2);
            assertEquals(0, modifierSum(s.enemy, Attribute.ARMOR) + s.param("sunder_armor"), EPSILON,
                    "being hit heats nothing");
            s.finishClean();
        }
    }

    @Test
    void blacksmithAnvilFallsWhereItsShadowWas() {
        try (Scenario s = noGaugeGain(Scenario.builder("blacksmith")).start()) {
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "모루 낙하");
            s.ticks(2);
            List<BlockDisplay> anvils = blocks(s, Material.ANVIL);
            assertEquals(1, anvils.size());
            assertTrue(anvils.getFirst().getLocation().getY() > FlatWorld.SURFACE + 5, "high in the sky");
            assertEquals(1, blocks(s, Material.BLACK_CONCRETE).size(), "its shadow on the ground");
            s.ticks(s.paramTicks("anvil_delay") - 4);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "still falling");
            s.h.tickUntil(() -> s.damageTaken(s.enemy) > 0, 10, "the anvil to land");
            assertEquals(s.param("anvil_damage"), s.damageBy(s.owner, s.enemy), EPSILON);
            assertTrue(rooted(s.enemy), "dazed");
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON, "outside the ring");
            assertFalse(rooted(s.enemy2));
            s.ticks(3);
            BlockDisplay anvil = blocks(s, Material.ANVIL).getFirst();
            assertEquals(s.enemy.getLocation().getY() + s.enemy.getHeight() + 0.05, anvil.getLocation().getY(), 0.01,
                    "the anvil sits on the dazed enemy's head");
            s.ticks(s.paramTicks("anvil_stun"));
            assertFalse(rooted(s.enemy), "awake again");
            s.ticks(30);
            assertNoDisplays(s, "the anvil is gone");
            s.finishClean();
        }
    }

    @Test
    void blacksmithAnvilNeedsGroundBelowTheAim() {
        try (Scenario s = Scenario.ffa("blacksmith")) {
            s.place(s.owner, 0.5, FlatWorld.SURFACE, 0.5, 0, -90);
            assertFalse(s.active().succeeded(), "nothing but sky");
            assertEquals(0, s.ctx().cooldownRemaining(s.owner), EPSILON, "no cooldown spent");
            s.finishClean();
        }
    }

    @Test
    void blacksmithForgeArmsTheTeamAndPoundsTheEnemies() {
        try (Scenario s = noGaugeGain(Scenario.builder("blacksmith").teams()).start()) {
            s.place(s.ally(), 2.5, 2.5, 0);
            s.place(s.enemy, 0.5, 5.5, 180);
            s.place(s.enemy2, 0.5, 14.5, 180);
            s.assertSucceeded(s.ultimate(), "대장간");
            s.ticks(8);
            assertEquals(s.param("forge_bonus"), modifierSum(s.ally(), Attribute.ATTACK_DAMAGE), EPSILON,
                    "the ally's weapon is red-hot");
            assertEquals(s.param("forge_bonus"), modifierSum(s.owner, Attribute.ATTACK_DAMAGE), EPSILON,
                    "and the smith's");
            assertEquals(0, modifierSum(s.enemy, Attribute.ATTACK_DAMAGE), EPSILON, "never an enemy's");
            s.place(s.ally(), 10.5, -10.5, 0);
            s.ticks(8);
            assertEquals(0, modifierSum(s.ally(), Attribute.ATTACK_DAMAGE), EPSILON, "it cools outside the forge");
            s.place(s.ally(), 2.5, 2.5, 0);
            s.ticks(8);
            assertEquals(s.param("forge_bonus"), modifierSum(s.ally(), Attribute.ATTACK_DAMAGE), EPSILON,
                    "back at the forge");
            s.h.tickUntil(() -> s.damageTaken(s.enemy) > 0, s.paramTicks("hammer_period") + 4, "the first blow");
            assertEquals(s.param("hammer_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "the shockwave");
            s.ticks(4);
            assertTrue(s.enemy.getLocation().getZ() > 5.6, "pushed away from the forge");
            s.ticks(s.paramTicks("forge_duration"));
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON, "out of the forge's reach");
            assertEquals(0, s.damageTaken(s.ally()) + s.damageTaken(s.owner), EPSILON);
            assertEquals(0, modifierSum(s.ally(), Attribute.ATTACK_DAMAGE), EPSILON, "the forge cooled");
            assertEquals(0, modifierSum(s.owner, Attribute.ATTACK_DAMAGE), EPSILON);
            s.ticks(20);
            assertNoDisplays(s, "the forge is gone");
            s.finishClean();
        }
    }

    @Test
    void blacksmithHammerComesDownRightOnTheAnvil() {
        try (Scenario s = Scenario.ffa("blacksmith")) {
            turnedLayout(s);
            s.assertSucceeded(s.ultimate(), "대장간");
            s.h.tickUntil(() -> s.damageTaken(s.enemy) > 0, s.paramTicks("hammer_period") + 6, "the first blow");
            BlockDisplay anvil = blocks(s, Material.ANVIL).getFirst();
            BlockDisplay head = blocks(s, Material.IRON_BLOCK).getFirst();
            Transformation pose = head.getTransformation();
            Vector3f center = new Vector3f(pose.getTranslation())
                    .add(leftRotation(head).transform(new Vector3f(pose.getScale()).mul(0.5f)));
            Location headAt = head.getLocation().add(center.x(), center.y(), center.z());
            assertTrue(horizontal(headAt, anvil.getLocation()) < 0.3, "the head lands on the anvil: " + headAt.toVector()
                    + " vs " + anvil.getLocation().toVector());
            assertEquals(anvil.getLocation().getY() + 1.3, headAt.getY(), 0.1, "right on top of it");
            assertTrue(anvil.getLocation().getX() < s.owner.getLocation().getX() - 1, "the forge stands in front");
            s.ticks(s.paramTicks("forge_duration") + 30);
            s.finishClean();
        }
    }
}
