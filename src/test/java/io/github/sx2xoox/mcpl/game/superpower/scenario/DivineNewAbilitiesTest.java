package io.github.sx2xoox.mcpl.game.superpower.scenario;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import io.github.sx2xoox.mcpl.game.superpower.abilities.BaldurAbility;
import io.github.sx2xoox.mcpl.game.superpower.abilities.FourGodsAbility;
import io.github.sx2xoox.mcpl.game.superpower.abilities.FourGodsAbility.Guardian;
import io.github.sx2xoox.mcpl.game.superpower.abilities.RaAbility;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Displays;
import io.github.sx2xoox.mcpl.game.superpower.ability.Ability;
import io.github.sx2xoox.mcpl.game.superpower.ability.ActivationResult;
import io.github.sx2xoox.mcpl.game.superpower.support.Scenario;
import io.github.sx2xoox.mcpl.game.superpower.support.TestPlayer;
import io.github.sx2xoox.mcpl.game.superpower.support.world.FlatWorld;
import io.papermc.paper.event.player.PlayerArmSwingEvent;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.EquipmentSlot;
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
 * 신의 무기 계열 (new): 사방신, 오딘, 제천대성, 발두르, 태양신 라. Each ability through a whole match, the owner dying in
 * the middle of each skill, team mode with an ally in the line of fire, the Gambler borrowing the standalone ultimate,
 * and the mechanics of every passive, active and ultimate (with the real-server rules: unrotated displays, exact
 * landings, fresh velocities).
 */
class DivineNewAbilitiesTest {

    static {
        // AbilityLifecycleTest (whose watchAlly is used below) lists the abilities in its static initializer with a
        // harness of its own, which MockBukkit refuses while a scenario of this class is running.
        AbilityLifecycleTest.abilities();
    }

    private static final double EPSILON = 1.0E-6;
    private static final int Y = (int) FlatWorld.SURFACE;
    private static final String RUNES = "ᚷᛟᚦᚨᛉᚱ";

    static Stream<String> abilities() {
        return Stream.of("fourgods", "odin", "wukong", "baldur", "ra");
    }

    /** The ultimates the gambler may borrow (the others rely on stances, ravens, the oath and listeners). */
    static Stream<String> standalone() {
        return Stream.of("wukong");
    }

    /** Displays an ability keeps for its passive the whole match (stance label, ravens, staff, icon, sun disc). */
    static int passiveDisplays(String id) {
        return switch (id) {
            case "fourgods" -> 1;
            case "odin" -> 10;
            case "wukong" -> 3;
            case "baldur" -> 2;
            case "ra" -> 2;
            default -> 0;
        };
    }

    static <T extends Entity> List<T> entities(Scenario s, Class<T> type) {
        return s.world.getEntities().stream().filter(type::isInstance).map(type::cast).toList();
    }

    /** Only the passive's own displays are left: every skill's display is gone. */
    static void assertOnlyPassiveDisplays(Scenario s, String what) {
        assertEquals(passiveDisplays(s.abilityId), entities(s, Display.class).size(), what
                + ": only the passive's displays are left: " + entities(s, Display.class));
    }

    /** No attribute modifier but the scenario's own max health bonus. */
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

    static void assertUnrotated(Entity display, String what) {
        assertEquals(0, display.getLocation().getYaw(), 1.0E-3, what + ": display yaw");
        assertEquals(0, display.getLocation().getPitch(), 1.0E-3, what + ": display pitch");
    }

    static void assertAllUnrotated(Scenario s, String what) {
        for (Display display : entities(s, Display.class)) {
            assertUnrotated(display, what + " " + display.getType());
        }
    }

    static void assertOnSurface(Player player, String what) {
        assertEquals(FlatWorld.SURFACE, player.getLocation().getY(), EPSILON, what + ": feet on the surface");
        assertTrue(player.getWorld().getBlockAt(player.getLocation()).getType().isAir(), what + ": in the open");
    }

    /** A wall of stone across x at block row {@code z}, {@code height} blocks tall, from {@code x1} to {@code x2}. */
    static void wall(Scenario s, int x1, int x2, int z, int height) {
        s.world.wallAlongX(x1, x2, z, height, Material.STONE);
    }

    static void clearWall(Scenario s, int x1, int x2, int z, int height) {
        s.world.wallAlongX(x1, x2, z, height, Material.AIR);
    }

    /** The modifiers a player carries on {@code attribute}, the scenario's tank bonus aside. */
    static List<AttributeModifier> modifiers(Player player, Attribute attribute) {
        return player.getAttribute(attribute).getModifiers().stream()
                .filter(modifier -> !modifier.getKey().equals(Scenario.TANK)).toList();
    }

    static boolean slowed(Player player) {
        return modifiers(player, Attribute.MOVEMENT_SPEED).stream().anyMatch(modifier -> modifier.getAmount() < 0);
    }

    static String text(TextDisplay display) {
        return Scenario.plain(display.text());
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

    /** {@code local} turned by the display's own transformation rotation. */
    static Vector rotated(Display display, float x, float y, float z) {
        Vector3f turned = leftRotation(display).transform(new Vector3f(x, y, z));
        return new Vector(turned.x(), turned.y(), turned.z());
    }

    /** What each ability needs to use its active on the aimed enemy in the default layout. */
    static void prepareActive(Scenario s) {
        s.aimAt(s.owner, s.enemy);
    }

    // ================================================================== every ability

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
            s.ticks(100);
            if (!id.equals("odin")) {
                assertTrue(s.damageBy(s.owner, s.enemy) > 0, id + " active hurts the aimed enemy: " + s.hits());
            }
            assertEquals(0, s.damageTaken(s.owner), EPSILON, id + " active never hurts its owner: " + s.hits());
            assertAllUnrotated(s, id + " after the active");

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
            assertTrue(s.damageBy(s.owner, s.enemy) + s.damageBy(s.owner, s.enemy2) > 0, id + " ultimate hurts: "
                    + s.hits());
            assertEquals(0, s.damageTaken(s.owner), EPSILON, id + " ultimate never hurts its owner: " + s.hits());
            s.assertNoProblems();
            assertOnlyPassiveDisplays(s, id);
            for (TestPlayer enemy : s.enemies()) {
                assertModifierFree(enemy, id);
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
            if (!id.equals("odin")) {
                assertTrue(s.damageBy(s.owner, s.enemy) > 0, id + " still hits the enemy next to the ally: "
                        + s.hits());
            }

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
            s.ticks(20);
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
        }
        assertModifierFree(s.owner, id);
        s.assertNoProblems();
        s.finishClean();
    }

    // ================================================================== borrowed by the gambler

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
            }
            assertTrue(entities(s, Display.class).isEmpty(), id + ": every display is gone");
            assertEquals(0, s.h.hub.tempBlocks().size(), id + ": temporary blocks");
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
            }
            s.assertNoProblems();
            s.finishClean();
        }
    }

    // ================================================================== 사방신 fourgods

    static FourGodsAbility fourGods(Scenario s) {
        return (FourGodsAbility) s.ability();
    }

    /** Uses the active (on the default layout) until the stance is {@code wanted}; then everything is reset. */
    static void advanceTo(Scenario s, Guardian wanted) {
        FourGodsAbility ability = fourGods(s);
        for (int i = 0; i < 4 && ability.stance() != wanted; i++) {
            s.layout();
            s.aimAt(s.owner, s.enemy);
            s.readyActive();
            s.assertSucceeded(s.active(), "summon " + ability.stance());
            s.ticks(100);
        }
        assertEquals(wanted, ability.stance());
        s.layout();
        s.healAll();
        s.ticks(2);
        s.readyActive();
        s.clearHits();
    }

    static double modifierSum(Player player, Attribute attribute) {
        return modifiers(player, attribute).stream().mapToDouble(AttributeModifier::getAmount).sum();
    }

    @Test
    void fourGodsStanceTurnsEastSouthWestNorthAndShowsWhatComesNext() {
        try (Scenario s = Scenario.ffa("fourgods")) {
            FourGodsAbility ability = fourGods(s);
            s.ticks(3);
            assertEquals(Guardian.DRAGON, ability.stance(), "the dragon of the east comes first");
            List<TextDisplay> labels = entities(s, TextDisplay.class);
            assertEquals(1, labels.size(), "one label over the head");
            TextDisplay label = labels.getFirst();
            assertTrue(text(label).contains("청룡") && text(label).contains("주작"), "now and next: " + text(label));
            assertTrue(label.getLocation().getY() > s.owner.getLocation().getY() + 1.8, "over the head");
            assertEquals(0.15, modifierSum(s.owner, Attribute.ATTACK_SPEED), EPSILON, "the dragon swings faster");
            assertEquals(0, modifierSum(s.owner, Attribute.MOVEMENT_SPEED), EPSILON);

            Guardian[] order = {Guardian.BIRD, Guardian.TIGER, Guardian.TORTOISE, Guardian.DRAGON};
            for (Guardian next : order) {
                s.layout();
                s.aimAt(s.owner, s.enemy);
                s.readyActive();
                s.assertSucceeded(s.active(), "summon");
                assertEquals(next, ability.stance(), "the stance turns on");
                s.ticks(100);
                // A label left behind by a teleport (the flight, the swap, the layout) reappears over the head.
                labels = entities(s, TextDisplay.class);
                assertEquals(1, labels.size(), "one label over the head");
                label = labels.getFirst();
                assertTrue(text(label).startsWith(koreanOf(next), text(label).indexOf(' ') + 1),
                        "the label follows: " + text(label));
                assertEquals(next == Guardian.DRAGON ? 0.15 : 0, modifierSum(s.owner, Attribute.ATTACK_SPEED),
                        EPSILON, "attack speed only with the dragon");
                assertEquals(next == Guardian.BIRD ? 0.15 : 0, modifierSum(s.owner, Attribute.MOVEMENT_SPEED),
                        EPSILON, "speed only with the bird");
            }
            assertUnrotated(label, "label");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    private static String koreanOf(Guardian guardian) {
        return switch (guardian) {
            case DRAGON -> "청룡";
            case BIRD -> "주작";
            case TIGER -> "백호";
            case TORTOISE -> "현무";
        };
    }

    @Test
    void fourGodsDragonSwimsForwardHitsOnceDragsAndStopsAtWalls() {
        try (Scenario s = Scenario.ffa("fourgods")) {
            s.place(s.enemy2, 7.5, 4.5, 180);
            s.ticks(2);
            s.clearHits();
            s.assertSucceeded(s.active(), "청룡");
            s.ticks(4);
            assertTrue(entities(s, BlockDisplay.class).size() >= 3, "the dragon's scales swim");
            assertAllUnrotated(s, "dragon");
            s.ticks(30);
            assertEquals(s.param("dragon_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "hit once: " + s.hits());
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON, "the enemy off the wave is spared");
            assertTrue(s.enemy.getLocation().getZ() > 4.5 + 0.8, "and dragged along: " + s.enemy.getLocation());
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.ticks(20);
            assertOnlyPassiveDisplays(s, "the dragon dissolved");

            fourGodsReset(s, Guardian.DRAGON);
            wall(s, -3, 4, 3, 3);
            s.clearHits();
            s.assertSucceeded(s.active(), "청룡 into a wall");
            s.ticks(40);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "the wall stops the wave: " + s.hits());
            clearWall(s, -3, 4, 3, 3);
            s.finishClean();
        }
    }

    /** Runs the stance round to {@code wanted} again. */
    private static void fourGodsReset(Scenario s, Guardian wanted) {
        advanceTo(s, wanted == Guardian.DRAGON ? Guardian.BIRD : wanted);
        if (wanted == Guardian.DRAGON) {
            advanceTo(s, Guardian.DRAGON);
        }
    }

    @Test
    void fourGodsBirdFliesLowAndItsPathSpeedsAlliesAndScorchesEnemies() {
        try (Scenario s = Scenario.teams("fourgods")) {
            advanceTo(s, Guardian.BIRD);
            s.place(s.ally(), 1.0, 4.0, 0);
            s.place(s.enemy, -0.3, 7.0, 180);
            s.place(s.enemy2, 9.5, 5.0, 180);
            s.clearHits();
            s.assertSucceeded(s.active(), "주작");
            s.ticks(16);
            assertTrue(s.owner.getLocation().getZ() > 8.0, "flew about ten blocks: " + s.owner.getLocation());
            assertTrue(Math.abs(s.owner.getLocation().getX() - 0.5) < 0.2, "straight ahead");
            boolean allyFast = false;
            boolean allyJumps = false;
            for (int i = 0; i < 40; i++) {
                s.ticks(1);
                allyFast |= modifierSum(s.ally(), Attribute.MOVEMENT_SPEED) > 0.2;
                allyJumps |= modifierSum(s.ally(), Attribute.JUMP_STRENGTH) > 0.1;
            }
            assertTrue(allyFast, "the ally on the feathers is quicker");
            assertTrue(allyJumps, "and jumps higher");
            s.ticks(60);
            double pulses = s.paramTicks("bird_trail") / 10.0;
            assertEquals(pulses * s.param("trail_damage"), s.damageBy(s.owner, s.enemy), EPSILON,
                    "the enemy on the path is scorched every half second: " + s.hits());
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON, "off the path, safe");
            assertTrue(s.hitsOf(s.enemy, org.bukkit.event.entity.EntityDamageEvent.DamageCause.FIRE_TICK).isEmpty()
                    && s.enemy.getFireTicks() <= 0, "not fire");
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "no fall");
            assertOnSurface(s.owner, "landed");
            s.ticks(20);
            assertModifierFree(s.ally(), "the path faded");
            assertOnlyPassiveDisplays(s, "feathers and wings");
            s.finishClean();
        }
    }

    /** The flight is measured on the steps sent to the owner, not on where the server last saw it. */
    @Test
    void fourGodsBirdFlightIsMeasuredOnTheStepsItSent() {
        try (Scenario s = Scenario.teams("fourgods")) {
            advanceTo(s, Guardian.BIRD);
            s.place(s.ally(), 0.5, 8.0, 0);
            s.world.physics().setEnabled(false);
            s.assertSucceeded(s.active(), "주작");
            s.ticks(14);
            assertEquals(0.5, s.owner.getLocation().getZ(), EPSILON, "the server never saw the owner move");
            s.ticks(10);
            assertTrue(modifierSum(s.ally(), Attribute.MOVEMENT_SPEED) > 0.2,
                    "the path is laid the whole way all the same");
            s.world.physics().setEnabled(true);
            s.ticks(100);
            assertModifierFree(s.ally(), "the path faded");
            s.finishClean();
        }
    }

    @Test
    void fourGodsTigerNeedsItsPreyThenPouncesAndStaggers() {
        try (Scenario s = Scenario.ffa("fourgods")) {
            advanceTo(s, Guardian.TIGER);
            FourGodsAbility ability = fourGods(s);
            s.place(s.owner, 0.5, Y, 0.5, 0, -80);
            assertFalse(s.active().succeeded(), "no prey in sight");
            assertEquals(Guardian.TIGER, ability.stance(), "the stance waits for the tiger");
            s.place(s.enemy, 0.5, 6.5, 180);
            s.aimAt(s.owner, s.enemy);
            s.clearHits();
            s.assertSucceeded(s.active(), "백호");
            s.ticks(3);
            assertAllUnrotated(s, "the tiger");
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "it is still in the air");
            s.ticks(5);
            assertEquals(s.param("tiger_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "the claws");
            assertEquals(-0.5, modifierSum(s.enemy, Attribute.MOVEMENT_SPEED), EPSILON, "staggers");
            assertEquals(-0.5, modifierSum(s.enemy, Attribute.ATTACK_SPEED), EPSILON, "and swings slower");
            assertEquals(Guardian.TORTOISE, ability.stance());
            s.ticks(s.paramTicks("tiger_stagger") + 2);
            assertModifierFree(s.enemy, "the stagger passes");
            s.ticks(20);
            assertOnlyPassiveDisplays(s, "the tiger is gone");
            s.finishClean();
        }
    }

    @Test
    void fourGodsTortoiseSwapsWithTheNearestAllyOrElseAnEnemy() {
        try (Scenario s = Scenario.teams("fourgods")) {
            advanceTo(s, Guardian.TORTOISE);
            s.place(s.ally(), -2.5, 0.5, 0);
            s.assertSucceeded(s.active(), "현무 rescue");
            assertEquals(-2.5, s.owner.getLocation().getX(), EPSILON, "the owner took the ally's place");
            assertEquals(0.5, s.ally().getLocation().getX(), EPSILON, "and the ally the owner's");
            assertOnSurface(s.owner, "owner");
            assertOnSurface(s.ally(), "ally");
            assertEquals(1, effectLevel(s.owner, PotionEffectType.RESISTANCE), "resistance II for the owner");
            assertEquals(1, effectLevel(s.ally(), PotionEffectType.RESISTANCE), "and for the ally");
            assertFalse(s.enemy.hasPotionEffect(PotionEffectType.SLOWNESS));
            s.ticks(2);
            assertAllUnrotated(s, "shells");
            s.finishClean();
        }
        try (Scenario s = Scenario.ffa("fourgods")) {
            advanceTo(s, Guardian.TORTOISE);
            s.place(s.enemy2, 20.5, 20.5, 180);
            s.assertSucceeded(s.active(), "현무 swap");
            assertEquals(4.5, s.owner.getLocation().getZ(), EPSILON, "swapped with the enemy");
            assertEquals(0.5, s.enemy.getLocation().getZ(), EPSILON);
            assertOnSurface(s.owner, "owner");
            assertEquals(1, effectLevel(s.owner, PotionEffectType.RESISTANCE), "only the owner is shielded");
            assertFalse(s.enemy.hasPotionEffect(PotionEffectType.RESISTANCE));
            assertEquals(1, effectLevel(s.enemy, PotionEffectType.SLOWNESS), "the enemy is slowed (구속 II)");
            assertEquals(Guardian.DRAGON, fourGods(s).stance());

            advanceTo(s, Guardian.TORTOISE);
            s.place(s.enemy, 20.5, -20.5, 0);
            s.place(s.enemy2, -20.5, 20.5, 0);
            assertFalse(s.active().succeeded(), "nobody in reach");
            assertEquals(Guardian.TORTOISE, fourGods(s).stance());
            s.finishClean();
        }
    }

    private static int effectLevel(LivingEntity entity, PotionEffectType type) {
        PotionEffect effect = entity.getPotionEffect(type);
        return effect == null ? -1 : effect.getAmplifier();
    }

    @Test
    void fourGodsStanceBonusesClawGuardAndLand() {
        try (Scenario s = Scenario.ffa("fourgods")) {
            advanceTo(s, Guardian.BIRD);
            s.owner.setNoDamageTicks(0);
            s.owner.damage(6, DamageSource.builder(DamageType.FALL).build());
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "the bird takes no fall");

            advanceTo(s, Guardian.TIGER);
            s.melee(s.owner, s.enemy, 4);
            assertEquals(4 * (1 + s.param("tiger_melee") / 100), s.damageBy(s.owner, s.enemy), EPSILON,
                    "the tiger hits harder");

            advanceTo(s, Guardian.TORTOISE);
            s.melee(s.enemy, s.owner, 4);
            assertEquals(4 * (1 - s.param("tortoise_guard") / 100), s.damageTaken(s.owner), EPSILON,
                    "the tortoise takes less");
            s.clearHits();
            s.melee(s.owner, s.enemy, 4);
            assertEquals(4, s.damageBy(s.owner, s.enemy), EPSILON, "and no claws any more");
            s.finishClean();
        }
    }

    @Test
    void fourGodsBarrierTurnsClockwiseAndCrownsTheOwnerInItsMiddle() {
        try (Scenario s = Scenario.ffa("fourgods")) {
            s.ticks(2);
            s.place(s.enemy, 5.5, 0.5, 90); // east: the dragon
            s.place(s.enemy2, 0.5, 5.5, 180); // south: the bird
            s.clearHits();
            s.assertSucceeded(s.ultimate(), "사신 결계");
            assertFalse(s.ultimate().succeeded(), "one barrier at a time");
            s.ticks(1);
            assertEquals(s.param("ult_dragon_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "the dragon's quarter");
            assertEquals(s.param("ult_bird_damage"), s.damageBy(s.owner, s.enemy2), EPSILON, "the bird's quarter");
            s.ticks(2);
            assertTrue(s.enemy.getLocation().getX() < 5.5 - 0.1, "pulled to the middle: " + s.enemy.getLocation());
            assertTrue(s.enemy2.getLocation().getY() > FlatWorld.SURFACE + 0.1, "lifted");
            assertEquals(4, entities(s, TextDisplay.class).size() - 1, "four guardians over the sides");
            assertAllUnrotated(s, "barrier");
            assertEquals(0.15, modifierSum(s.owner, Attribute.ATTACK_SPEED), EPSILON, "the dragon's speed, once");
            assertEquals(0.15, modifierSum(s.owner, Attribute.MOVEMENT_SPEED), EPSILON, "the bird's speed in the middle");
            s.clearHits();
            s.melee(s.owner, s.enemy, 4);
            assertEquals(4 * (1 + s.param("tiger_melee") / 100), s.damageBy(s.owner, s.enemy), EPSILON,
                    "the tiger's claws in the middle");

            // The first turn (1.5 s, the barrier's 30th tick): the tortoise comes to the east, the dragon to the south.
            s.ticks(s.paramTicks("ult_rotate") - 4);
            s.place(s.enemy, 5.5, 0.5, 90);
            s.place(s.enemy2, 0.5, 5.5, 180);
            s.clearHits();
            s.ticks(2);
            assertEquals(0, s.enemy.getAttribute(Attribute.JUMP_STRENGTH).getValue(), EPSILON,
                    "the tortoise's quarter: no jumping");
            assertTrue(slowed(s.enemy), "and slow");
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "but no damage");
            assertEquals(s.param("ult_dragon_damage"), s.damageBy(s.owner, s.enemy2), EPSILON,
                    "the dragon moved on to the south");

            s.place(s.owner, -5.5, 6.5, 0);
            s.ticks(2);
            assertEquals(0, modifierSum(s.owner, Attribute.MOVEMENT_SPEED), EPSILON, "out of the middle");
            assertEquals(0.15, modifierSum(s.owner, Attribute.ATTACK_SPEED), EPSILON, "the own stance stays");
            s.ticks(s.paramTicks("ult_duration") + 30);
            assertOnlyPassiveDisplays(s, "the barrier fell");
            for (TestPlayer enemy : s.enemies()) {
                assertModifierFree(enemy, "after the barrier");
            }
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.finishClean();
        }
    }

    // ================================================================== 오딘 odin

    /** Block displays (raven parts, spear parts) within {@code radius} of {@code at}. */
    static List<BlockDisplay> displaysNear(Scenario s, Location at, double radius) {
        return entities(s, BlockDisplay.class).stream()
                .filter(display -> display.getLocation().distance(at) <= radius).toList();
    }

    static Location headOf(LivingEntity entity) {
        return entity.getLocation().add(0, entity.getHeight(), 0);
    }

    @Test
    void odinMuninnRemembersTheAttackerAndHuginnTakesRevengeOnce() {
        try (Scenario s = Scenario.ffa("odin")) {
            s.ticks(3);
            assertEquals(10, entities(s, BlockDisplay.class).size(), "two ravens on the shoulders");
            assertAllUnrotated(s, "perched ravens");
            for (BlockDisplay part : entities(s, BlockDisplay.class)) {
                assertTrue(part.getLocation().distance(s.owner.getLocation().add(0, 1.44, 0)) < 0.6,
                        "on a shoulder: " + part.getLocation());
            }
            s.melee(s.enemy, s.owner, 2);
            s.ticks(20);
            assertEquals(5, displaysNear(s, headOf(s.enemy), 1.8).size(), "Muninn circles over the attacker");
            assertTrue(entities(s, TextDisplay.class).stream().anyMatch(t -> text(t).contains("기억")),
                    "'기억해 두었다'");
            assertAllUnrotated(s, "flying raven");

            s.clearHits();
            s.melee(s.owner, s.enemy, 2);
            s.ticks(8);
            assertEquals(2 + s.param("revenge_damage"), s.damageBy(s.owner, s.enemy), EPSILON,
                    "Huginn dives on the remembered: " + s.hits());
            s.clearHits();
            s.melee(s.owner, s.enemy, 2);
            s.ticks(8);
            assertEquals(2, s.damageBy(s.owner, s.enemy), EPSILON, "revenge is taken once");

            // Resting: a new blow is not remembered.
            s.melee(s.enemy, s.owner, 2);
            s.ticks(10);
            s.clearHits();
            s.melee(s.owner, s.enemy, 2);
            s.ticks(8);
            assertEquals(2, s.damageBy(s.owner, s.enemy), EPSILON, "the ravens rest");

            s.ticks(s.paramTicks("raven_rest") + 40);
            assertEquals(10, displaysNear(s, s.owner.getLocation().add(0, 1.44, 0), 0.8).size(), "home again");
            s.melee(s.enemy2, s.owner, 2);
            s.ticks(20);
            s.clearHits();
            s.melee(s.owner, s.enemy, 2);
            s.ticks(8);
            assertEquals(2, s.damageBy(s.owner, s.enemy), EPSILON, "only the remembered one is avenged");
            s.melee(s.owner, s.enemy2, 2);
            s.ticks(8);
            assertEquals(2 + s.param("revenge_damage"), s.damageBy(s.owner, s.enemy2), EPSILON, "that one is");
            s.finishClean();
        }
    }

    @Test
    void odinMemoryFadesAfterItsTime() {
        try (Scenario s = Scenario.ffa("odin")) {
            s.ticks(3);
            s.melee(s.enemy, s.owner, 2);
            s.ticks(s.paramTicks("memory_time") + 10);
            s.clearHits();
            s.melee(s.owner, s.enemy, 2);
            s.ticks(8);
            assertEquals(2, s.damageBy(s.owner, s.enemy), EPSILON, "forgotten");
            s.ticks(40);
            assertEquals(10, displaysNear(s, s.owner.getLocation().add(0, 1.44, 0), 0.8).size(),
                    "Muninn flew home");
            s.finishClean();
        }
    }

    @Test
    void odinMimirTakesHealthButNeverBelowTheFloorShowsEveryEnemyToOdinsSideAndArmsARune() {
        try (Scenario s = Scenario.teams("odin")) {
            s.ticks(3);
            s.healAll();
            double full = s.owner.getHealth();
            s.clearHits();
            s.assertSucceeded(s.active(), "미미르의 샘");
            assertEquals(full - s.param("wisdom_cost"), s.owner.getHealth(), EPSILON, "an eye's price");
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "a price, not a wound");
            s.ticks(2);
            List<TextDisplay> runes = entities(s, TextDisplay.class).stream()
                    .filter(t -> RUNES.contains(text(t).trim())).toList();
            assertEquals(2, runes.size(), "a rune over each enemy");
            for (TextDisplay rune : runes) {
                assertTrue(s.owner.canSee(rune), "Odin sees it");
                assertTrue(s.ally().canSee(rune), "so does his ally");
                assertFalse(s.enemy.canSee(rune) || s.enemy2.canSee(rune), "the enemies do not");
                assertEquals(Set.of(s.owner, s.ally()), Set.copyOf(s.seenBy(rune)), "Odin's side only");
                assertFalse(rune.isVisibleByDefault(), "hidden inside its spawn: no enemy's client ever receives it");
                assertTrue(rune.isSeeThrough(), "through walls");
                assertUnrotated(rune, "rune");
            }
            s.clearHits();
            s.melee(s.owner, s.enemy, 2);
            s.ticks(3);
            assertEquals(2 + s.param("rune_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "the rune strike");
            s.clearHits();
            s.melee(s.owner, s.enemy, 2);
            s.ticks(3);
            assertEquals(2, s.damageBy(s.owner, s.enemy), EPSILON, "once");
            s.ticks(s.paramTicks("sight_time"));
            assertTrue(entities(s, TextDisplay.class).stream().noneMatch(t -> RUNES.contains(text(t).trim())),
                    "the sight fades");

            s.owner.setHealth(3);
            s.readyActive();
            s.assertSucceeded(s.active(), "미미르의 샘 at 3");
            assertEquals(s.param("wisdom_floor"), s.owner.getHealth(), EPSILON, "never below the floor");
            s.owner.setHealth(1.5);
            s.readyActive();
            s.assertSucceeded(s.active(), "미미르의 샘 at 1.5");
            assertEquals(1.5, s.owner.getHealth(), EPSILON, "nothing is taken below it");
            s.finishClean();
        }
    }

    /** The rune ring's text displays around {@code target} (they appear when the spear is close). */
    static List<TextDisplay> ringAround(Scenario s, LivingEntity target) {
        Location body = target.getLocation().add(0, target.getHeight() / 2, 0);
        return entities(s, TextDisplay.class).stream()
                .filter(t -> RUNES.contains(text(t).trim()) && t.getLocation().distance(body) < 2.5).toList();
    }

    static Scenario gungnir() {
        Scenario s = Scenario.ffa("odin");
        s.ticks(3);
        s.place(s.enemy, 0.5, 20.5, 180);
        s.place(s.enemy2, -15.5, -15.5, 0);
        s.aimAt(s.owner, s.enemy);
        s.clearHits();
        return s;
    }

    @Test
    void odinGungnirWarnsSlowsAndStrikesTheTargetThatDoesNotParry() {
        try (Scenario s = gungnir()) {
            s.assertSucceeded(s.ultimate(), "궁니르");
            assertFalse(s.ultimate().succeeded(), "one spear at a time");
            int flash = -1;
            for (int i = 0; i < 40 && flash < 0; i++) {
                s.ticks(1);
                if (!ringAround(s, s.enemy).isEmpty()) {
                    flash = i;
                }
                for (Display display : entities(s, Display.class)) {
                    assertUnrotated(display, "spear");
                }
            }
            assertTrue(flash > 0, "the rune ring flashed");
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "not yet");
            BlockDisplay shaft = entities(s, BlockDisplay.class).stream()
                    .filter(d -> d.getBlock().getMaterial() == Material.STRIPPED_DARK_OAK_WOOD).findFirst().orElse(null);
            assertNotNull(shaft, "the spear");
            Vector along = rotated(shaft, 0, 0, 1);
            Vector toTarget = s.enemy.getLocation().add(0, 0.9, 0).toVector().subtract(shaft.getLocation().toVector())
                    .normalize();
            assertTrue(along.dot(toTarget) > 0.9, "the shaft points at its target: " + along + " vs " + toTarget);
            double distanceAtFlash = shaft.getLocation().distance(s.enemy.getLocation().add(0, 0.9, 0));
            assertTrue(distanceAtFlash <= s.param("warn_distance") + 0.8, "it flashes at the warning distance: "
                    + distanceAtFlash);
            int window = 0;
            while (s.damageTaken(s.enemy) == 0 && window < 60) {
                s.ticks(1);
                window++;
            }
            double expected = (s.param("warn_distance") - s.param("slow_distance")) / s.param("spear_speed")
                    + s.param("slow_distance") / s.param("slow_speed");
            assertEquals(expected, window / 20.0, 0.2, "about a second from the flash to the strike");
            assertEquals(s.param("spear_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "Gungnir strikes");
            s.ticks(3);
            assertTrue(s.enemy.getLocation().getZ() > 20.5 + 0.5, "and throws it back: " + s.enemy.getLocation());
            s.ticks(60);
            assertOnlyPassiveDisplays(s, "the spear faded");
            s.finishClean();
        }
    }

    @Test
    void odinGungnirIsParriedOnlyBySwingingInTheWindow() {
        try (Scenario s = gungnir()) {
            s.assertSucceeded(s.ultimate(), "궁니르");
            s.ticks(3);
            swing(s, s.enemy); // far too early: nothing to strike yet
            s.h.tickUntil(() -> !ringAround(s, s.enemy).isEmpty(), 60, "the rune ring");
            s.ticks(8);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON);
            swing(s, s.enemy);
            assertEquals(s.param("parry_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "struck aside: " + s.hits());
            assertTrue(s.enemy.hasPotionEffect(PotionEffectType.SPEED), "the worthy one runs");
            s.ticks(40);
            assertEquals(s.param("parry_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "nothing more");
            assertTrue(entities(s, TextDisplay.class).stream().noneMatch(t -> RUNES.contains(text(t).trim())));
            s.ticks(30);
            assertOnlyPassiveDisplays(s, "the spear tumbled away");
            s.finishClean();
        }
        try (Scenario s = gungnir()) {
            s.assertSucceeded(s.ultimate(), "궁니르");
            s.ticks(3);
            swing(s, s.enemy);
            s.ticks(80);
            assertEquals(s.param("spear_damage"), s.damageBy(s.owner, s.enemy), EPSILON,
                    "an early swing parries nothing");
            swing(s, s.enemy);
            assertEquals(s.param("spear_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "nor a late one");
            s.finishClean();
        }
    }

    private static void swing(Scenario s, Player player) {
        s.h.server.getPluginManager().callEvent(new PlayerArmSwingEvent(player, EquipmentSlot.HAND));
    }

    @Test
    void odinGungnirFliesOverAWallThatRisesInItsWay() {
        try (Scenario s = gungnir()) {
            s.assertSucceeded(s.ultimate(), "궁니르");
            s.ticks(3);
            wall(s, -6, 6, 9, 5);
            double highest = 0;
            for (int i = 0; i < 100 && s.damageTaken(s.enemy) == 0; i++) {
                s.ticks(1);
                for (BlockDisplay part : entities(s, BlockDisplay.class)) {
                    if (part.getBlock().getMaterial() == Material.STRIPPED_DARK_OAK_WOOD) {
                        highest = Math.max(highest, part.getLocation().getY());
                    }
                }
            }
            assertEquals(s.param("spear_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "it never misses");
            assertTrue(highest > Y + 5 - 0.5, "it went over the wall: " + highest);
            clearWall(s, -6, 6, 9, 5);
            s.finishClean();
        }
    }

    @Test
    void odinGungnirReturnsAndRefundsWhenItsTargetFallsFirst() {
        try (Scenario s = gungnir()) {
            s.assertSucceeded(s.ultimate(), "궁니르");
            s.ticks(5);
            s.session.eliminate(s.enemy, null);
            s.ticks(30);
            assertEquals(s.param("spear_refund"), s.ctx().gauge(s.owner), 1.0, "part of the gauge comes back");
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON, "nobody else is struck");
            assertOnlyPassiveDisplays(s, "the spear came home");
            s.assertNoProblems();
            s.finishMatch();
        }
        try (Scenario s = gungnir()) {
            s.assertSucceeded(s.ultimate(), "궁니르");
            s.ticks(5);
            s.owner.hidePlayer(s.h.hub.plugin(), s.enemy); // the target vanishes (an enemy's stealth)
            s.ticks(30);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "a target out of sight is not chased");
            assertEquals(s.param("spear_refund"), s.ctx().gauge(s.owner), 1.0, "and part of the gauge comes back");
            assertTrue(ringAround(s, s.enemy).isEmpty(), "no ring gives the hidden one away");
            s.owner.showPlayer(s.h.hub.plugin(), s.enemy);
            s.finishClean();
        }
    }

    // ================================================================== 제천대성 wukong

    @Test
    void wukongStaffSweepClipsTheEnemyBesideTheOneStruck() {
        try (Scenario s = Scenario.ffa("wukong")) {
            s.ticks(3);
            assertEquals(3, entities(s, BlockDisplay.class).size(), "the staff rides on the back");
            assertAllUnrotated(s, "back staff");
            s.place(s.enemy, 0.5, 2.0, 180);
            s.place(s.enemy2, 1.5, 2.3, 180);
            s.clearHits();
            s.melee(s.owner, s.enemy, 6);
            s.ticks(2);
            assertEquals(6, s.damageBy(s.owner, s.enemy), EPSILON);
            assertEquals(6 * s.param("sweep_share") / 100, s.damageBy(s.owner, s.enemy2), EPSILON,
                    "half of it for the one beside");
            s.place(s.enemy2, 6.5, 2.0, 180);
            s.clearHits();
            s.melee(s.owner, s.enemy, 6);
            s.ticks(2);
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON, "too far beside");
            s.finishClean();
        }
    }

    @Test
    void wukongStaffDrivesTheSkeweredEnemyIntoTheWallAndPinsIt() {
        try (Scenario s = Scenario.ffa("wukong")) {
            s.ticks(3);
            wall(s, -4, 5, 10, 3);
            s.place(s.enemy2, -12.5, -12.5, 0);
            s.aimAt(s.owner, s.enemy);
            s.clearHits();
            s.assertSucceeded(s.active(), "여의봉아 늘어나라");
            assertEquals(s.param("pole_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "skewered at once");
            s.ticks(2);
            assertAllUnrotated(s, "extended staff");
            s.ticks(12);
            assertEquals(s.param("pole_damage") + s.param("pin_damage"), s.damageBy(s.owner, s.enemy), EPSILON,
                    "crushed against the wall: " + s.hits());
            double z = s.enemy.getLocation().getZ();
            assertTrue(z > 8.5 && z < 10 - 0.3 + EPSILON, "at the wall, never through it: " + z);
            assertTrue(slowed(s.enemy), "staggered");
            assertEquals(-0.5, modifierSum(s.enemy, Attribute.ATTACK_SPEED), EPSILON);
            s.ticks(s.paramTicks("pin_stagger") + 20);
            assertModifierFree(s.enemy, "the stagger passes");
            assertOnlyPassiveDisplays(s, "the staff shrank back");
            clearWall(s, -4, 5, 10, 3);
            s.finishClean();
        }
    }

    /**
     * A driven player flies on its own client and the server sees it late (here: never, world physics is off): the
     * drive is judged on its own reckoning of where the steps it sent carried the enemy, so it still crushes it against
     * the wall at the right moment.
     */
    @Test
    void wukongDriveIsJudgedOnItsOwnReckoningOfTheEnemysPath() {
        try (Scenario s = Scenario.ffa("wukong")) {
            s.ticks(3);
            wall(s, -4, 5, 10, 3);
            s.place(s.enemy2, -12.5, -12.5, 0);
            s.aimAt(s.owner, s.enemy);
            s.world.physics().setEnabled(false);
            s.clearHits();
            s.assertSucceeded(s.active(), "여의봉아 늘어나라");
            s.ticks(3);
            assertEquals(s.param("pole_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "not at the wall yet");
            s.ticks(6);
            assertEquals(s.param("pole_damage") + s.param("pin_damage"), s.damageBy(s.owner, s.enemy), EPSILON,
                    "crushed where its client hits the wall: " + s.hits());
            s.world.physics().setEnabled(true);
            clearWall(s, -4, 5, 10, 3);
            s.finishClean();
        }
    }

    @Test
    void wukongStaffInTheOpenDrivesTheEnemyToItsEnd() {
        try (Scenario s = Scenario.ffa("wukong")) {
            s.ticks(3);
            s.place(s.enemy2, -12.5, -12.5, 0);
            s.aimAt(s.owner, s.enemy);
            s.clearHits();
            s.assertSucceeded(s.active(), "여의봉아 늘어나라");
            s.ticks(20);
            assertEquals(s.param("pole_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "no wall, no crush");
            double z = s.enemy.getLocation().getZ();
            assertTrue(z > 9.5 && z < 14, "driven to the staff's end: " + z);
            assertModifierFree(s.enemy, "no stagger");
            s.finishClean();
        }
    }

    @Test
    void wukongVaultsStraightUpOffTheFloorAndLandsSafely() {
        try (Scenario s = Scenario.ffa("wukong")) {
            s.ticks(3);
            s.place(s.owner, 0.5, Y, 0.5, 0, 90);
            s.owner.setVelocity(new Vector(1.5, 0, -1.2)); // what the server last sent: stale on a real server
            s.clearHits();
            s.assertSucceeded(s.active(), "장대뛰기");
            Vector sent = s.owner.getVelocity();
            assertEquals(0, sent.getX(), EPSILON, "a fresh velocity, nothing added to a stale one: " + sent);
            assertEquals(0, sent.getZ(), EPSILON, "a fresh velocity, nothing added to a stale one: " + sent);
            assertTrue(sent.getY() > 0.8, "straight up: " + sent);
            double highest = 0;
            for (int i = 0; i < 40; i++) {
                s.ticks(1);
                highest = Math.max(highest, s.owner.getLocation().getY() - FlatWorld.SURFACE);
                assertEquals(0.5, s.owner.getLocation().getX(), 0.05, "straight up");
                assertEquals(0.5, s.owner.getLocation().getZ(), 0.05, "straight up");
            }
            assertEquals(s.param("vault_height"), highest, 0.8, "about the vault height");
            s.tickUntilGrounded(s.owner, 80);
            s.ticks(2);
            assertOnSurface(s.owner, "landed");
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "no fall damage");
            s.finishClean();
        }
    }

    @Test
    void wukongVaultsBackAndUpOffAWall() {
        try (Scenario s = Scenario.ffa("wukong")) {
            s.ticks(3);
            wall(s, -3, 4, 3, 3);
            s.place(s.owner, 0.5, Y, 0.5, 0, 0);
            s.assertSucceeded(s.active(), "벽 차기");
            s.ticks(5);
            assertTrue(s.owner.getLocation().getZ() < 0.5 - 1.0, "kicked back off the wall: " + s.owner.getLocation());
            assertTrue(s.owner.getLocation().getY() > FlatWorld.SURFACE + 0.5, "and up");
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "the enemy behind the wall is never skewered");
            s.tickUntilGrounded(s.owner, 80);
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "no fall damage");
            clearWall(s, -3, 4, 3, 3);
            s.finishClean();
        }
    }

    @Test
    void wukongStaffThrustIntoTheAirRefundsHalfTheCooldown() {
        try (Scenario s = Scenario.ffa("wukong")) {
            s.ticks(3);
            s.place(s.owner, 0.5, Y, 0.5, 0, -90);
            s.assertSucceeded(s.active(), "허공");
            s.ticks(1);
            assertEquals(s.param("cooldown") * (1 - s.param("miss_refund") / 100), s.ctx().cooldownRemaining(s.owner),
                    0.15, "half the cooldown back");
            assertEquals(FlatWorld.SURFACE, s.owner.getLocation().getY(), 0.05, "and no vault");
            s.finishClean();
        }
    }

    @Test
    void wukongPillarRisesDrawsItsShadowAndTopplesOntoItsLine() {
        try (Scenario s = Scenario.ffa("wukong")) {
            s.ticks(3);
            s.place(s.enemy, 0.5, 9.5, 180);
            s.place(s.enemy2, 4.5, 9.5, 180);
            s.place(s.owner, 0.5, Y, 0.5, 0, 0);
            s.clearHits();
            s.assertSucceeded(s.ultimate(), "여의봉 천주");
            s.ticks(6);
            assertEquals(3 + 6, entities(s, BlockDisplay.class).size(), "the pillar, its bands and its shadow");
            assertAllUnrotated(s, "rising pillar");
            s.ticks(s.paramTicks("pillar_rise") - 6 + 5);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "still falling: time to step aside");
            s.ticks(10);
            assertEquals(s.param("pillar_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "crushed under it");
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON, "beside the shadow, safe");
            assertTrue(slowed(s.enemy), "flattened");
            BlockDisplay shaft = entities(s, BlockDisplay.class).stream()
                    .filter(d -> d.getBlock().getMaterial() == Material.RED_CONCRETE
                            && d.getTransformation().getScale().y() > 5).findFirst().orElse(null);
            assertNotNull(shaft, "the fallen pillar");
            Vector up = rotated(shaft, 0, 1, 0);
            assertTrue(up.getZ() > 0.95, "it lies along the line it fell on: " + up);
            assertAllUnrotated(s, "fallen pillar");
            s.ticks(s.paramTicks("pillar_flat") + 40);
            assertModifierFree(s.enemy, "back in shape");
            assertOnlyPassiveDisplays(s, "the staff shrank back");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.finishClean();
        }
    }

    // ================================================================== 발두르 baldur

    static BaldurAbility baldur(Scenario s) {
        return (BaldurAbility) s.ability();
    }

    static ItemDisplay icon(Scenario s) {
        return entities(s, ItemDisplay.class).stream()
                .filter(d -> d.getLocation().distance(headOf(s.owner)) < 1.6).findFirst().orElse(null);
    }

    @Test
    void baldurWeaknessTurnsOnItsTimerAndShowsOverHisHead() {
        try (Scenario s = Scenario.ffa("baldur")) {
            BaldurAbility ability = baldur(s);
            s.ticks(2);
            BaldurAbility.Kind first = ability.weakness();
            ItemDisplay icon = icon(s);
            assertNotNull(icon, "the weakness icon");
            assertEquals(iconOf(first), icon.getItemStack().getType());
            assertUnrotated(icon, "icon");
            s.h.tickUntil(() -> ability.weakness() != first, s.paramTicks("weak_period") + 2, "the first turn");
            BaldurAbility.Kind second = ability.weakness();
            int ticks = 0;
            while (ability.weakness() == second && ticks < 400) {
                s.ticks(1);
                ticks++;
            }
            assertEquals(s.paramTicks("weak_period"), ticks, 1, "every period");
            assertEquals(second.ordinal() == 2 ? 0 : second.ordinal() + 1, ability.weakness().ordinal(),
                    "melee, projectile, ability, melee...");
            assertEquals(iconOf(ability.weakness()), icon(s).getItemStack().getType(), "the icon follows");
            s.finishClean();
        }
    }

    private static Material iconOf(BaldurAbility.Kind kind) {
        return switch (kind) {
            case MELEE -> Material.IRON_SWORD;
            case PROJECTILE -> Material.ARROW;
            case ABILITY -> Material.ENCHANTED_BOOK;
        };
    }

    /** Waits until the weakness is {@code kind}, with at least {@code margin} ticks before it turns again. */
    static void weakTo(Scenario s, BaldurAbility.Kind kind) {
        BaldurAbility ability = baldur(s);
        s.h.tickUntil(() -> ability.weakness() == kind, 30 * 20, "weakness " + kind);
        s.ticks(1);
        s.clearHits();
    }

    @Test
    void baldurIsHurtByHisWeaknessAndBarelyByAnythingElse() {
        try (Scenario s = Scenario.ffa("baldur")) {
            double weak = 1 + s.param("weak_bonus") / 100;
            double sworn = 1 - s.param("sworn_reduction") / 100;
            weakTo(s, BaldurAbility.Kind.MELEE);
            s.melee(s.enemy, s.owner, 8);
            assertEquals(8 * weak, s.damageTaken(s.owner), EPSILON, "a blade on his weakness");
            s.healAll();
            s.clearHits();
            s.ctx().damage(s.enemy, s.owner, 10);
            assertEquals(10 * sworn, s.damageTaken(s.owner), EPSILON, "the oath holds against spells");
            s.healAll();
            s.clearHits();
            shoot(s, s.enemy, s.owner, 10);
            assertEquals(10 * sworn, s.damageTaken(s.owner), EPSILON, "and arrows");
            s.healAll();
            s.clearHits();
            s.owner.setNoDamageTicks(0);
            s.owner.damage(5, DamageSource.builder(DamageType.FALL).build());
            assertEquals(5 * sworn, s.damageTaken(s.owner), EPSILON, "and the ground");
            s.healAll();

            weakTo(s, BaldurAbility.Kind.PROJECTILE);
            shoot(s, s.enemy, s.owner, 10);
            assertEquals(10 * weak, s.damageTaken(s.owner), EPSILON, "an arrow on his weakness");
            s.healAll();
            s.clearHits();
            s.melee(s.enemy, s.owner, 8);
            assertEquals(8 * sworn, s.damageTaken(s.owner), EPSILON, "a blade is sworn off now");
            s.healAll();

            weakTo(s, BaldurAbility.Kind.ABILITY);
            s.ctx().damage(s.enemy, s.owner, 10);
            assertEquals(10 * weak, s.damageTaken(s.owner), EPSILON, "a spell on his weakness");
            s.finishClean();
        }
    }

    private static void shoot(Scenario s, Player shooter, Player victim, double amount) {
        Arrow arrow = s.world.spawn(shooter.getEyeLocation(), Arrow.class);
        arrow.setShooter(shooter);
        victim.setNoDamageTicks(0);
        victim.damage(amount, DamageSource.builder(DamageType.ARROW).withCausingEntity(shooter)
                .withDirectEntity(arrow).build());
        arrow.remove();
    }

    @Test
    void baldurRadiantLightBurstsBlindsAndSkipsTheWeakness() {
        try (Scenario s = Scenario.ffa("baldur")) {
            BaldurAbility ability = baldur(s);
            s.ticks(2);
            s.place(s.enemy, 0.5, 3.5, 180);
            s.place(s.enemy2, 0.5, 9.5, 180);
            BaldurAbility.Kind before = ability.weakness();
            s.clearHits();
            s.assertSucceeded(s.active(), "찬란한 빛");
            assertEquals(s.param("burst_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "the burst");
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON, "out of its reach");
            assertTrue(s.enemy.hasPotionEffect(PotionEffectType.BLINDNESS), "blinded");
            assertEquals((before.ordinal() + 1) % 3, ability.weakness().ordinal(), "the weakness skips on at once");
            s.ticks(2);
            assertTrue(entities(s, BlockDisplay.class).size() >= 8, "rods of light fly out");
            assertAllUnrotated(s, "rays");
            s.ticks(4);
            assertTrue(s.enemy.getLocation().getZ() > 3.5 + 0.8, "pushed away: " + s.enemy.getLocation());
            BaldurAbility.Kind skipped = ability.weakness();
            s.ticks(s.paramTicks("weak_period") - 20);
            assertEquals(skipped, ability.weakness(), "the timer started over");
            s.ticks(30);
            assertOnlyPassiveDisplays(s, "the light faded");
            s.finishClean();
        }
    }

    @Test
    void baldurMistletoeLetsOnlyItsHolderHurtHimAndPassesOnWhenItStrays() {
        try (Scenario s = Scenario.ffa("baldur")) {
            s.ticks(2);
            s.place(s.enemy2, 0.5, 26.5, 180);
            s.clearHits();
            s.assertSucceeded(s.ultimate(), "겨우살이를 쥔 자");
            assertFalse(s.ultimate().succeeded(), "one mistletoe at a time");
            s.ticks(2);
            ItemDisplay flying = entities(s, ItemDisplay.class).stream()
                    .filter(d -> d.getItemStack().getType() == Material.ARROW).findFirst().orElse(null);
            assertNotNull(flying, "the dart flies");
            assertTrue(flying.getLocation().distance(headOf(s.enemy)) > 1.0, "from the god to its holder");
            s.ticks(6);
            ItemDisplay dart = entities(s, ItemDisplay.class).stream()
                    .filter(d -> d.getItemStack().getType() == Material.ARROW
                            && d.getLocation().distance(headOf(s.enemy)) < 1.5).findFirst().orElse(null);
            assertNotNull(dart, "the only enemy in reach holds it");
            assertTrue(dart.isGlowing(), "everyone sees it");
            assertUnrotated(dart, "dart");

            s.place(s.enemy2, 0.5, 12.5, 180); // company: no duel any more
            s.melee(s.enemy2, s.owner, 8);
            s.ctx().damage(s.enemy2, s.owner, 8);
            s.owner.setNoDamageTicks(0);
            s.owner.damage(6, DamageSource.builder(DamageType.FALL).build());
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "nothing else in the world harms him");
            s.melee(s.enemy, s.owner, 4);
            assertEquals(4 * (1 + s.param("holder_bonus") / 100), s.damageTaken(s.owner), EPSILON,
                    "the mistletoe pierces, whatever the weakness");

            s.clearHits();
            s.ticks(21);
            assertEquals(s.param("aura_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "the radiance burns near him");

            s.place(s.enemy, 0.5, 31.5, 180);
            s.place(s.enemy2, 0.5, 10.5, 180);
            s.ticks(2);
            s.place(s.enemy, 0.5, 15.5, 180); // back in company, but the mistletoe has passed on
            s.ticks(1);
            s.healAll();
            s.clearHits();
            s.melee(s.enemy, s.owner, 4);
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "the stray lost it");
            s.melee(s.enemy2, s.owner, 4);
            assertEquals(4 * (1 + s.param("holder_bonus") / 100), s.damageTaken(s.owner), EPSILON,
                    "the nearest one holds it now");

            s.ticks(s.paramTicks("mistletoe_time") + 5);
            s.clearHits();
            s.melee(s.enemy, s.owner, 4);
            assertTrue(s.damageTaken(s.owner) > 0, "the oath only holds while it lasts");
            assertOnlyPassiveDisplays(s, "the dart faded");
            s.finishClean();
        }
    }

    // ================================================================== 태양신 라 ra

    static RaAbility ra(Scenario s) {
        return (RaAbility) s.ability();
    }

    @Test
    void raHastesAndHealsUnderTheOpenSkyOnly() {
        try (Scenario s = Scenario.ffa("ra")) {
            s.ticks(10);
            List<BlockDisplay> disc = entities(s, BlockDisplay.class);
            assertEquals(2, disc.size(), "the sun disc over his head");
            Vector toward = ra(s).sun().toward();
            Vector lean = disc.getFirst().getLocation().toVector().subtract(s.owner.getLocation().toVector()).setY(0);
            assertEquals(toward.getX() * 0.45, lean.getX(), 0.05, "it leans towards the sun's side");
            assertEquals(0, lean.getZ(), 0.05);
            for (BlockDisplay plate : disc) {
                assertEquals(Displays.FULL_BRIGHT, plate.getBrightness(), "bright under the sky");
                assertEquals(Display.Billboard.CENTER, plate.getBillboard());
                assertUnrotated(plate, "disc");
            }
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "태양 광선");
            s.ticks(40);
            double haste = 1 + s.param("sun_haste") / 100;
            assertEquals(s.param("cooldown") - 2 * haste, s.ctx().cooldownRemaining(s.owner), 0.2,
                    "the cooldown runs faster under the sky");
            s.owner.setHealth(10);
            s.ticks(s.paramTicks("sun_heal_period") + 6);
            assertEquals(10 + s.param("sun_heal"), s.owner.getHealth(), EPSILON, "the sun heals");

            s.world.setBlock(0, Y + 3, 0, Material.STONE);
            s.ticks(10);
            for (BlockDisplay plate : disc) {
                assertFalse(Displays.FULL_BRIGHT.equals(plate.getBrightness()), "dim under a roof");
            }
            s.readyActive();
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "태양 광선 under a roof");
            s.ticks(40);
            assertEquals(s.param("cooldown") - 2, s.ctx().cooldownRemaining(s.owner), 0.2, "no haste under a roof");
            double health = s.owner.getHealth();
            s.ticks(s.paramTicks("sun_heal_period") + 10);
            assertEquals(health, s.owner.getHealth(), EPSILON, "and no healing");
            s.world.setBlock(0, Y + 3, 0, Material.AIR);
            s.finishClean();
        }
    }

    @Test
    void raRayComesFromTheSunsSideAndBurnsTheExposed() {
        try (Scenario s = Scenario.ffa("ra")) {
            s.ticks(3);
            Vector toward = ra(s).sun().toward();
            s.aimAt(s.owner, s.enemy);
            s.clearHits();
            s.assertSucceeded(s.active(), "태양 광선");
            s.ticks(4);
            Location body = s.enemy.getLocation().add(0, s.enemy.getHeight() / 2, 0);
            Location over = body.clone().add(toward.clone().multiply(3.2));
            List<BlockDisplay> smallSun = entities(s, BlockDisplay.class).stream()
                    .filter(d -> d.getLocation().distance(over) < 0.6).toList();
            assertEquals(2, smallSun.size(), "a little sun beside the target, on the sun's side: "
                    + entities(s, BlockDisplay.class).stream().map(Entity::getLocation).toList());
            for (BlockDisplay plate : smallSun) {
                assertEquals(3, plate.getTeleportDuration(), "it glides like the moving target it hangs by");
            }
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "not yet");
            s.ticks(s.paramTicks("ray_delay"));
            assertEquals(s.param("ray_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "the sun burns");
            assertTrue(s.enemy.hasPotionEffect(PotionEffectType.GLOWING), "heatstroke: glowing");
            assertTrue(slowed(s.enemy), "heatstroke: slow");
            assertTrue(s.enemy.getFireTicks() <= 0, "heat, not fire");
            BlockDisplay beam = entities(s, BlockDisplay.class).stream()
                    .filter(d -> d.getBlock().getMaterial() == Material.YELLOW_STAINED_GLASS).findFirst().orElse(null);
            assertNotNull(beam, "the beam");
            assertTrue(rotated(beam, 0, 0, 1).dot(toward) > 0.99, "it comes down from the sun");
            assertUnrotated(beam, "beam");
            s.ticks(s.paramTicks("heat_time") + 5);
            assertModifierFree(s.enemy, "the heat passes");
            assertOnlyPassiveDisplays(s, "beam faded");
            s.finishClean();
        }
    }

    @Test
    void raRayIsStoppedByWhatShadesTheTargetOnTheSunsSide() {
        try (Scenario s = Scenario.ffa("ra")) {
            s.ticks(3);
            Vector toward = ra(s).sun().toward();
            Location shade = s.enemy.getLocation().add(0, s.enemy.getHeight() / 2, 0)
                    .add(toward.clone().multiply(3));
            s.world.setBlock(shade.getBlockX(), shade.getBlockY(), shade.getBlockZ(), Material.STONE);
            s.aimAt(s.owner, s.enemy);
            s.clearHits();
            s.assertSucceeded(s.active(), "태양 광선");
            s.ticks(s.paramTicks("ray_delay") + 4);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "safe in the shade: " + s.hits());
            assertFalse(s.enemy.hasPotionEffect(PotionEffectType.GLOWING));
            assertEquals(Material.STONE, s.world.getBlockAt(shade).getType(), "the block only scorches here");
            s.world.setBlock(shade.getBlockX(), shade.getBlockY(), shade.getBlockZ(), Material.AIR);
            s.finishClean();
        }
    }

    /** Shuts {@code player} into a one-block stone cell, shaded from every side. */
    static void cell(Scenario s, Player player, Material type) {
        int bx = player.getLocation().getBlockX();
        int bz = player.getLocation().getBlockZ();
        for (int x = bx - 1; x <= bx + 1; x++) {
            for (int z = bz - 1; z <= bz + 1; z++) {
                for (int y = Y; y <= Y + 2; y++) {
                    boolean inside = x == bx && z == bz && y < Y + 2;
                    if (!inside) {
                        s.world.setBlock(x, y, z, type);
                    }
                }
            }
        }
    }

    @Test
    void raBarqueCrossesTheSkyBurningTheLitAndSparingTheShaded() {
        try (Scenario s = Scenario.ffa("ra")) {
            s.ticks(3);
            s.place(s.enemy2, 8.5, 8.5, 180);
            cell(s, s.enemy2, Material.STONE);
            s.clearHits();
            s.assertSucceeded(s.ultimate(), "태양의 배");
            assertFalse(s.ultimate().succeeded(), "one barque at a time");
            s.ticks(3);
            BlockDisplay sun = entities(s, BlockDisplay.class).stream()
                    .filter(d -> d.getLocation().getY() > Y + 8).findFirst().orElse(null);
            assertNotNull(sun, "the second sun");
            assertTrue(sun.getLocation().getX() > 15, "rising in the east: " + sun.getLocation());
            assertAllUnrotated(s, "barque");
            s.ticks(s.paramTicks("barque_time") - 6);
            assertTrue(sun.getLocation().getX() < -15, "setting in the west: " + sun.getLocation());
            assertTrue(s.damageBy(s.owner, s.enemy) >= s.param("barque_damage") * 10, "the lit enemy burns: "
                    + s.hits());
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON, "the shaded one is safe");
            s.ticks(30);
            assertOnlyPassiveDisplays(s, "the barque set");
            assertModifierFree(s.enemy, "after the barque");
            cell(s, s.enemy2, Material.AIR);
            s.finishClean();
        }
    }

    @Test
    void raBarqueStrengthensHisSideInTheLight() {
        try (Scenario s = Scenario.teams("ra")) {
            s.ticks(3);
            s.place(s.enemy2, -12.5, -12.5, 0);
            s.assertSucceeded(s.ultimate(), "태양의 배");
            s.ticks(7);
            double bonus = 1 + s.param("barque_bonus") / 100;
            s.clearHits();
            s.melee(s.ally(), s.enemy, 4);
            assertEquals(4 * bonus, s.damageBy(s.ally(), s.enemy), EPSILON, "the ally in the light");
            s.clearHits();
            s.melee(s.owner, s.enemy, 4);
            assertEquals(4 * bonus, s.damageBy(s.owner, s.enemy), EPSILON, "and Ra");
            cell(s, s.ally(), Material.STONE);
            s.ticks(6);
            s.clearHits();
            s.melee(s.ally(), s.enemy, 4);
            assertEquals(4, s.damageBy(s.ally(), s.enemy), EPSILON, "not in the shade");
            cell(s, s.ally(), Material.AIR);
            s.ticks(s.paramTicks("barque_time"));
            s.clearHits();
            s.melee(s.owner, s.enemy, 4);
            assertEquals(4, s.damageBy(s.owner, s.enemy), EPSILON, "the sun has set");
            s.finishClean();
        }
    }

    /** Two Ra in one match (or one Ra granted again after a practice respawn) share the match's sun. */
    @Test
    void raSunIsTheSameForEveryRaOfTheMatch() {
        try (Scenario s = Scenario.ffa("ra")) {
            s.ticks(3);
            RaAbility.Sun sun = ra(s).sun();
            for (int i = 0; i < 12; i++) {
                RaAbility other = (RaAbility) s.ctx().registry().create("ra");
                other.bind(s.enemy, s.ctx(), s.ctx().paramsFor("ra"));
                assertEquals(sun, other.sun(), "one sun for the whole match");
            }
            s.finishClean();
        }
    }

    /** Only cover near the target shades it: a tower far off on the sun's side casts no shade here. */
    @Test
    void raFarTerrainOnTheSunsSideCastsNoShade() {
        try (Scenario s = Scenario.ffa("ra")) {
            s.ticks(3);
            Vector toward = ra(s).sun().toward();
            Location far = s.enemy.getLocation().add(0, s.enemy.getHeight() / 2, 0)
                    .add(toward.clone().multiply(30));
            s.world.setBlock(far.getBlockX(), far.getBlockY(), far.getBlockZ(), Material.STONE);
            s.aimAt(s.owner, s.enemy);
            s.clearHits();
            s.assertSucceeded(s.active(), "태양 광선");
            s.ticks(s.paramTicks("ray_delay") + 4);
            assertEquals(s.param("ray_damage"), s.damageBy(s.owner, s.enemy), EPSILON,
                    "a block far beyond the target shades nothing: " + s.hits());
            s.world.setBlock(far.getBlockX(), far.getBlockY(), far.getBlockZ(), Material.AIR);
            s.finishClean();
        }
    }

    // ================================================================== review regressions

    /** Where a display's (block) box is centred in the world, by its transformation. */
    static Vector boxCenter(Display display) {
        Transformation transformation = display.getTransformation();
        Vector3f half = leftRotation(display).transform(new Vector3f(transformation.getScale()).mul(0.5f));
        Vector3f translation = transformation.getTranslation();
        return display.getLocation().toVector().add(new Vector(translation.x() + half.x(),
                translation.y() + half.y(), translation.z() + half.z()));
    }

    /** The lowest and the highest world y of a block display's transformed box. */
    static double[] boxHeights(Display display) {
        Transformation transformation = display.getTransformation();
        Quaternionf rotation = leftRotation(display);
        Vector3f scale = transformation.getScale();
        double low = Double.MAX_VALUE;
        double high = -Double.MAX_VALUE;
        for (int corner = 0; corner < 8; corner++) {
            Vector3f local = new Vector3f((corner & 1) * scale.x(), ((corner >> 1) & 1) * scale.y(),
                    ((corner >> 2) & 1) * scale.z());
            double y = display.getLocation().getY() + transformation.getTranslation().y()
                    + rotation.transform(local).y();
            low = Math.min(low, y);
            high = Math.max(high, y);
        }
        return new double[] {low, high};
    }

    /** The farthest world z of a block display's transformed box. */
    static double boxMaxZ(Display display) {
        Transformation transformation = display.getTransformation();
        Quaternionf rotation = leftRotation(display);
        Vector3f scale = transformation.getScale();
        double far = -Double.MAX_VALUE;
        for (int corner = 0; corner < 8; corner++) {
            Vector3f local = new Vector3f((corner & 1) * scale.x(), ((corner >> 1) & 1) * scale.y(),
                    ((corner >> 2) & 1) * scale.z());
            far = Math.max(far, display.getLocation().getZ() + transformation.getTranslation().z()
                    + rotation.transform(local).z());
        }
        return far;
    }

    /** The perched ravens face the way Odin's body does (their models turned by the transformation alone). */
    @Test
    void odinPerchedRavensFaceWithTheBody() {
        try (Scenario s = Scenario.ffa("odin")) {
            s.place(s.owner, 0.5, Y, 0.5, -90, 0); // east
            s.owner.setBodyYaw(-90);
            s.ticks(12);
            Vector facing = new Vector(1, 0, 0);
            List<BlockDisplay> bodies = entities(s, BlockDisplay.class).stream()
                    .filter(d -> Math.abs(d.getTransformation().getScale().z() - 0.32f) < 1.0E-3).toList();
            assertEquals(2, bodies.size(), "two raven bodies");
            for (BlockDisplay body : bodies) {
                Vector forward = rotated(body, 0, 0, 1).setY(0).normalize();
                assertTrue(forward.dot(facing) > 0.85, "a perched raven looks out with Odin: " + forward);
                assertUnrotated(body, "raven");
            }
            s.finishClean();
        }
    }

    /** The staff on the back lies across the back, slanted, whichever way the sage faces. */
    @Test
    void wukongBackStaffLiesAcrossTheBack() {
        try (Scenario s = Scenario.ffa("wukong")) {
            s.place(s.owner, 0.5, Y, 0.5, -90, 0); // east
            s.owner.setBodyYaw(-90);
            s.ticks(6);
            BlockDisplay shaft = entities(s, BlockDisplay.class).stream()
                    .filter(d -> d.getBlock().getMaterial() == Material.RED_CONCRETE).findFirst().orElse(null);
            assertNotNull(shaft, "the staff on the back");
            Vector axis = rotated(shaft, 0, 0, 1);
            assertEquals(0, axis.getX(), 0.05, "across the back of an eastward body, not along it: " + axis);
            assertEquals(Math.sin(Math.toRadians(40)), Math.abs(axis.getY()), 0.05, "slanted: " + axis);
            assertTrue(boxCenter(shaft).getX() < s.owner.getLocation().getX(), "on the back, behind the body");
            s.finishClean();
        }
    }

    /** The dragon's scales swim along its wave, heads first. */
    @Test
    void fourGodsDragonScalesSwimAlongTheWave() {
        try (Scenario s = Scenario.ffa("fourgods")) {
            s.place(s.enemy, -10.5, -10.5, 0);
            s.place(s.enemy2, -12.5, 10.5, 0);
            s.place(s.owner, 0.5, Y, 0.5, -90, 0); // east
            s.ticks(2);
            s.assertSucceeded(s.active(), "청룡");
            s.ticks(5);
            List<BlockDisplay> scales = entities(s, BlockDisplay.class).stream()
                    .filter(d -> d.getBlock().getMaterial() == Material.LIGHT_BLUE_STAINED_GLASS
                            || d.getBlock().getMaterial() == Material.CYAN_STAINED_GLASS).toList();
            assertTrue(scales.size() >= 3, "the scales");
            for (BlockDisplay scale : scales) {
                Vector along = rotated(scale, 0, 0, 1);
                assertTrue(along.getX() > 0.7, "swimming east: " + along);
            }
            s.ticks(30);
            s.finishClean();
        }
    }

    /** Nothing of the passive floats over an invisible owner (it would give him away); it returns afterwards. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("abilities")
    void passiveDisplaysHideWhileTheOwnerIsConcealed(String id) {
        try (Scenario s = Scenario.ffa(id)) {
            s.ticks(5);
            assertEquals(passiveDisplays(id), entities(s, Display.class).size(), id + ": the passive shows");
            s.owner.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, 400, 0, false, false));
            s.ticks(12);
            assertTrue(entities(s, Display.class).isEmpty(), id + ": nothing floats over the invisible owner: "
                    + entities(s, Display.class));
            s.owner.removePotionEffect(PotionEffectType.INVISIBILITY);
            s.ticks(12);
            assertEquals(passiveDisplays(id), entities(s, Display.class).size(), id + ": back once he is seen");
            assertAllUnrotated(s, id);
            s.assertNoProblems();
            s.finishClean();
        }
    }

    // ------------------------------------------------------------------ 사방신

    /**
     * Facing east the wings still spread across the flight. The owner's pair flies with the steps sent, not behind
     * them (its own client runs them at once); everyone else's pair rides the body where the server sees it, gliding
     * like their view of the bird, so it never flies ahead of it.
     */
    @Test
    void fourGodsBirdWingsSpreadAcrossTheFlightAndKeepUpWithIt() {
        try (Scenario s = Scenario.ffa("fourgods")) {
            advanceTo(s, Guardian.BIRD);
            s.place(s.enemy, -10.5, -10.5, 0);
            s.place(s.enemy2, -12.5, 10.5, 0);
            s.place(s.owner, 0.5, Y, 0.5, -90, 0); // east, +X
            s.world.physics().setEnabled(false);
            s.assertSucceeded(s.active(), "주작");
            s.ticks(4);
            List<BlockDisplay> pairs = entities(s, BlockDisplay.class).stream()
                    .filter(d -> d.getBlock().getMaterial() == Material.RED_STAINED_GLASS).toList();
            assertEquals(4, pairs.size(), "a pair of wings for the owner, a pair for everyone else");
            List<BlockDisplay> wings = pairs.stream().filter(d -> s.seenBy(d).equals(List.of(s.owner))).toList();
            List<BlockDisplay> seen = pairs.stream().filter(d -> !s.seenBy(d).contains(s.owner)).toList();
            assertEquals(2, wings.size(), "the owner's own pair");
            assertEquals(2, seen.size(), "everyone else's pair");
            for (BlockDisplay wing : seen) {
                assertTrue(s.seenBy(wing).containsAll(s.enemies()), "the others see their pair");
                assertEquals(3, wing.getTeleportDuration(), "it glides like the others' view of the bird");
            }
            double seenMiddle = (boxCenter(seen.get(0)).getX() + boxCenter(seen.get(1)).getX()) / 2;
            assertEquals(s.owner.getLocation().getX(), seenMiddle, 0.4,
                    "the others' pair rides the body where the server sees it, never ahead of it");
            Vector a = boxCenter(wings.get(0));
            Vector b = boxCenter(wings.get(1));
            Vector across = a.clone().subtract(b);
            assertTrue(Math.abs(across.getZ()) > 1.0, "the wings spread to the sides of an eastward flight: " + across);
            assertTrue(Math.abs(across.getX()) < 0.3, "not one in front of the other: " + across);
            for (BlockDisplay wing : wings) {
                Vector span = rotated(wing, 1, 0, 0);
                assertTrue(Math.abs(span.getZ()) > 0.7, "each wing's span runs across the flight: " + span);
            }
            double middle = (a.getX() + b.getX()) / 2;
            assertTrue(middle > 0.5 + 2.5, "the wings fly where the steps sent carried the bird: " + middle);
            s.world.physics().setEnabled(true);
            s.ticks(40);
            s.finishClean();
        }
    }

    /** The tiger keeps its head, stripes and tail on its body while the body pitches in the leap. */
    @Test
    void fourGodsTigerPartsPitchWithTheBody() {
        try (Scenario s = Scenario.ffa("fourgods")) {
            advanceTo(s, Guardian.TIGER);
            s.place(s.enemy, 0.5, 6.5, 180);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "백호");
            boolean checked = false;
            for (int i = 0; i < 6 && !checked; i++) {
                s.ticks(1);
                BlockDisplay body = null;
                BlockDisplay head = null;
                for (BlockDisplay part : entities(s, BlockDisplay.class)) {
                    Vector3f scale = part.getTransformation().getScale();
                    if (Math.abs(scale.z() - 0.95f) < 1.0E-3) {
                        body = part;
                    } else if (Math.abs(scale.z() - 0.36f) < 1.0E-3 && Math.abs(scale.x() - 0.4f) < 1.0E-3) {
                        head = part;
                    }
                }
                if (body == null || head == null) {
                    continue;
                }
                assertEquals(2, body.getTeleportDuration(), "moved every tick, it glides two: bunched packets do not "
                        + "make it stutter");
                Vector forward = rotated(body, 0, 0, 1);
                if (Math.abs(forward.getY()) < 0.15) {
                    continue; // level: any frame would do
                }
                Vector expected = rotated(body, 0, 0.12f, 0.6f);
                Vector actual = boxCenter(head).subtract(boxCenter(body));
                assertTrue(actual.distance(expected) < 0.04, "the head stays on the pitched body: " + actual
                        + " vs " + expected);
                assertTrue(rotated(head, 0, 0, 1).dot(forward) > 0.99, "and faces where the body does");
                checked = true;
            }
            assertTrue(checked, "the leap pitched the tiger");
            s.ticks(20);
            s.finishClean();
        }
    }

    /** The tortoise swaps only with someone in sight: never through a wall. */
    @Test
    void fourGodsTortoiseNeverSwapsThroughAWall() {
        try (Scenario s = Scenario.teams("fourgods")) {
            advanceTo(s, Guardian.TORTOISE);
            s.place(s.ally(), 0.5, -3.5, 0);
            wall(s, -3, 4, -2, 3);
            s.assertSucceeded(s.active(), "현무");
            assertEquals(4.5, s.owner.getLocation().getZ(), EPSILON, "the enemy in sight, not the ally behind the wall");
            assertEquals(-3.5, s.ally().getLocation().getZ(), EPSILON, "the ally stays where it was");
            clearWall(s, -3, 4, -2, 3);
            s.finishClean();
        }
    }

    /** The dragon's quarter pulls towards the middle without flinging a close enemy past it. */
    @Test
    void fourGodsDragonQuarterPullsWithoutFlingingPastTheMiddle() {
        try (Scenario s = Scenario.ffa("fourgods")) {
            s.ticks(2);
            s.place(s.enemy, 1.3, 0.5, 90); // 0.8 east of the middle: the dragon's quarter
            s.place(s.enemy2, -12.5, 12.5, 0);
            s.assertSucceeded(s.ultimate(), "사신 결계");
            double least = Double.MAX_VALUE;
            for (int i = 0; i < 9; i++) {
                s.ticks(1);
                least = Math.min(least, s.enemy.getLocation().getX());
            }
            assertTrue(least < 1.3 - 0.2, "pulled in: " + least);
            assertTrue(least > 0.5 - 0.25, "but not flung into the west quarter: " + least);
            s.ticks(s.paramTicks("ult_duration") + 20);
            s.finishClean();
        }
    }

    // ------------------------------------------------------------------ 오딘

    /** Swinging all the time (from the throw, or in a melee fight) never parries: the parry is a timed swing. */
    @Test
    void odinGungnirCannotBeParriedBySpammingSwings() {
        try (Scenario s = gungnir()) {
            s.assertSucceeded(s.ultimate(), "궁니르");
            for (int i = 0; i < 120 && s.damageTaken(s.enemy) == 0; i++) {
                if (i % 2 == 0) {
                    swing(s, s.enemy);
                }
                s.ticks(1);
            }
            assertEquals(s.param("spear_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "spam is struck: " + s.hits());
            assertFalse(s.enemy.hasPotionEffect(PotionEffectType.SPEED), "and is not 'worthy'");
            s.ticks(40);
            assertOnlyPassiveDisplays(s, "the spear faded");
            s.finishClean();
        }
    }

    /** The first swing after the flash is the only chance: too early (before the spear slows) wastes it. */
    @Test
    void odinGungnirSwingBeforeTheSpearSlowsWastesTheParry() {
        try (Scenario s = gungnir()) {
            s.assertSucceeded(s.ultimate(), "궁니르");
            s.h.tickUntil(() -> !ringAround(s, s.enemy).isEmpty(), 60, "the rune ring");
            swing(s, s.enemy); // the ring just flashed: the spear has not slowed yet
            s.ticks(10);
            swing(s, s.enemy); // in the slow stretch, but the one chance is gone
            s.ticks(40);
            assertEquals(s.param("spear_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "struck: " + s.hits());
            s.finishClean();
        }
    }

    /** A parry right after a swing (within the lockout) fails; one after a pause in the slow stretch works. */
    @Test
    void odinGungnirParryNeedsAPauseBeforeTheSwing() {
        try (Scenario s = gungnir()) {
            s.assertSucceeded(s.ultimate(), "궁니르");
            s.h.tickUntil(() -> !ringAround(s, s.enemy).isEmpty(), 60, "the rune ring");
            s.ticks(9);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON);
            swing(s, s.enemy);
            assertEquals(s.param("parry_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "a calm, timed swing parries");
            s.finishClean();
        }
        try (Scenario s = gungnir()) {
            s.assertSucceeded(s.ultimate(), "궁니르");
            for (int i = 0; i < 60 && ringAround(s, s.enemy).isEmpty(); i++) {
                swing(s, s.enemy); // swinging away until the ring flashes
                s.ticks(1);
            }
            assertFalse(ringAround(s, s.enemy).isEmpty(), "the rune ring");
            s.ticks(7);
            swing(s, s.enemy); // in the slow stretch, but too soon after the last swing
            s.ticks(40);
            assertEquals(s.param("spear_damage"), s.damageBy(s.owner, s.enemy), EPSILON,
                    "a swing right after others is no parry: " + s.hits());
            s.finishClean();
        }
    }

    /** Cover that keeps the spear from the warning distance never skips the telegraph: the ring flashes in time. */
    @Test
    void odinGungnirFlashesInTimeEvenWhenCoverKeepsItAway() {
        try (Scenario s = Scenario.builder("odin").param("warn_distance", 1).param("slow_distance", 0.5).start()) {
            s.ticks(3);
            s.place(s.enemy, 0.5, 12.5, 180);
            s.place(s.enemy2, -15.5, -15.5, 0);
            s.aimAt(s.owner, s.enemy);
            s.clearHits();
            s.assertSucceeded(s.ultimate(), "궁니르");
            cell(s, s.enemy, Material.STONE); // shut in: the spear can come no nearer than the cell's walls
            boolean flashed = false;
            for (int i = 0; i < 160 && s.damageTaken(s.enemy) == 0; i++) {
                s.ticks(1);
                flashed |= ringAround(s, s.enemy).size() >= 3;
            }
            assertTrue(flashed, "the rune ring flashed before the strike");
            assertEquals(s.param("spear_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "it never misses");
            cell(s, s.enemy, Material.AIR);
            s.ticks(40);
            s.finishClean();
        }
    }

    /** Gungnir's own damage carries neither Mímir's rune strike nor Huginn's revenge (both wait for a real hit). */
    @Test
    void odinGungnirCarriesNeitherTheRuneStrikeNorTheRevenge() {
        try (Scenario s = gungnir()) {
            s.place(s.enemy, 0.5, 2.5, 180);
            s.melee(s.enemy, s.owner, 2); // Muninn remembers it
            s.ticks(2);
            s.assertSucceeded(s.active(), "미미르의 샘");
            s.place(s.enemy, 0.5, 20.5, 180);
            s.aimAt(s.owner, s.enemy);
            s.clearHits();
            s.assertSucceeded(s.ultimate(), "궁니르");
            s.h.tickUntil(() -> s.damageTaken(s.enemy) > 0, 80, "the strike");
            s.ticks(8);
            assertEquals(s.param("spear_damage"), s.damageBy(s.owner, s.enemy), EPSILON,
                    "only the spear's own damage: " + s.hits());
            s.place(s.enemy, 0.5, 2.5, 180);
            s.clearHits();
            s.melee(s.owner, s.enemy, 2);
            s.ticks(8);
            assertEquals(2 + s.param("rune_damage") + s.param("revenge_damage"), s.damageBy(s.owner, s.enemy),
                    EPSILON, "the rune and the revenge still wait for Odin's next blow: " + s.hits());
            s.finishClean();
        }
        try (Scenario s = gungnir()) {
            s.ticks(1);
            s.assertSucceeded(s.active(), "미미르의 샘");
            s.aimAt(s.owner, s.enemy);
            s.clearHits();
            s.assertSucceeded(s.ultimate(), "궁니르");
            // Mímir's own rune hangs over the target: the ring is the three runes around it.
            s.h.tickUntil(() -> ringAround(s, s.enemy).size() >= 3, 60, "the rune ring");
            s.ticks(9);
            swing(s, s.enemy);
            s.ticks(4);
            assertEquals(s.param("parry_damage"), s.damageBy(s.owner, s.enemy), EPSILON,
                    "a parried spear deals its parry damage, nothing more: " + s.hits());
            s.finishClean();
        }
    }

    // ------------------------------------------------------------------ 제천대성

    /** Aimed at the chest of a close enemy the ray ends in the floor: the floor is no wall to crush it against. */
    @ParameterizedTest(name = "enemy at z={0}")
    @org.junit.jupiter.params.provider.ValueSource(doubles = {2.5, 3.0, 3.5, 4.5})
    void wukongStaffNeverPinsAgainstTheFloor(double z) {
        try (Scenario s = Scenario.ffa("wukong")) {
            s.ticks(3);
            s.place(s.enemy, 0.5, z, 180);
            s.place(s.enemy2, -12.5, -12.5, 0);
            s.aimAt(s.owner, s.enemy);
            s.clearHits();
            s.assertSucceeded(s.active(), "여의봉아 늘어나라");
            s.ticks(20);
            assertEquals(s.param("pole_damage"), s.damageBy(s.owner, s.enemy), EPSILON,
                    "no wall, no crush: " + s.hits());
            assertModifierFree(s.enemy, "no stagger in the open");
            assertTrue(s.enemy.getLocation().getZ() > z + 0.5, "still driven along: " + s.enemy.getLocation());
            s.finishClean();
        }
    }

    /** The staff's shaft points where the sage aims, whatever the direction. */
    @Test
    void wukongStaffPointsWhereTheSageAims() {
        try (Scenario s = Scenario.ffa("wukong")) {
            s.ticks(3);
            s.place(s.enemy, -12.5, 12.5, 0);
            s.place(s.enemy2, -12.5, -12.5, 0);
            s.place(s.owner, 0.5, Y, 0.5, -90, -20); // east and a little up: into the air
            Vector aim = s.owner.getEyeLocation().getDirection();
            s.assertSucceeded(s.active(), "허공");
            s.ticks(2);
            BlockDisplay shaft = entities(s, BlockDisplay.class).stream()
                    .filter(d -> d.getBlock().getMaterial() == Material.RED_CONCRETE
                            && d.getTransformation().getScale().z() > 1).findFirst().orElse(null);
            assertNotNull(shaft, "the extended staff");
            assertTrue(rotated(shaft, 0, 0, 1).dot(aim) > 0.99, "along the aim: " + rotated(shaft, 0, 0, 1));
            s.ticks(20);
            s.finishClean();
        }
    }

    static BlockDisplay fallenShaft(Scenario s) {
        return entities(s, BlockDisplay.class).stream()
                .filter(d -> d.getBlock().getMaterial() == Material.RED_CONCRETE
                        && d.getTransformation().getScale().y() > 2).findFirst().orElse(null);
    }

    /** The fallen pillar lies on the ground, its whole thickness above it. */
    @Test
    void wukongFallenPillarLiesOnTheGroundNotInIt() {
        try (Scenario s = Scenario.ffa("wukong")) {
            s.ticks(3);
            s.place(s.owner, 0.5, Y, 0.5, 0, 0);
            s.assertSucceeded(s.ultimate(), "여의봉 천주");
            s.ticks(s.paramTicks("pillar_rise") + 12);
            BlockDisplay shaft = fallenShaft(s);
            assertNotNull(shaft, "the fallen pillar");
            double[] heights = boxHeights(shaft);
            assertEquals(FlatWorld.SURFACE, heights[0], 0.05, "it lies on the ground, not half in it");
            assertTrue(heights[1] > FlatWorld.SURFACE + 1.0, "its whole thickness shows: " + heights[1]);
            s.ticks(40);
            s.finishClean();
        }
    }

    /** The pillar breaks against the cover it falls on: whoever hides behind it is spared. */
    @Test
    void wukongPillarBreaksAgainstCoverAndSparesWhoIsBehindIt() {
        try (Scenario s = Scenario.ffa("wukong")) {
            s.ticks(3);
            wall(s, -3, 4, 9, 3);
            s.place(s.owner, 0.5, Y, 0.5, 0, 0);
            s.place(s.enemy, 0.5, 12.5, 180); // behind the wall, on the line
            s.place(s.enemy2, 0.5, 6.5, 180); // in front of it, on the line
            s.clearHits();
            s.assertSucceeded(s.ultimate(), "여의봉 천주");
            s.ticks(4);
            BlockDisplay plate = entities(s, BlockDisplay.class).stream()
                    .filter(d -> d.getBlock().getMaterial() == Material.BLACK_STAINED_GLASS).findFirst().orElse(null);
            assertNotNull(plate, "the shadow");
            s.ticks(s.paramTicks("pillar_rise"));
            assertTrue(boxMaxZ(plate) <= 9 + 0.05, "the shadow ends at the wall: " + boxMaxZ(plate));
            s.ticks(10);
            assertEquals(s.param("pillar_damage"), s.damageBy(s.owner, s.enemy2), EPSILON, "crushed in front of it");
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "spared behind the wall: " + s.hits());
            BlockDisplay shaft = fallenShaft(s);
            assertNotNull(shaft, "what is left of the pillar");
            assertTrue(boxMaxZ(shaft) <= 9 + 0.05, "it broke against the wall, not through it: " + boxMaxZ(shaft));
            assertTrue(boxHeights(shaft)[0] > FlatWorld.SURFACE - 0.05, "on the ground");
            assertAllUnrotated(s, "broken pillar");
            s.ticks(60);
            assertOnlyPassiveDisplays(s, "the staff shrank back");
            clearWall(s, -3, 4, 9, 3);
            s.assertNoProblems();
            s.finishClean();
        }
    }

    /** With a wall right in front there is nowhere for the pillar to fall: the ultimate is not spent. */
    @Test
    void wukongPillarNeedsRoomToFall() {
        try (Scenario s = Scenario.ffa("wukong")) {
            s.ticks(3);
            wall(s, -3, 4, 2, 3);
            s.place(s.owner, 0.5, Y, 0.5, 0, 0);
            assertFalse(s.ultimate().succeeded(), "no room");
            assertTrue(entities(s, BlockDisplay.class).size() == passiveDisplays("wukong"), "nothing raised");
            clearWall(s, -3, 4, 2, 3);
            s.finishClean();
        }
    }

    // ------------------------------------------------------------------ 발두르

    /** In a duel (the holder is the only enemy near) the holder's blows meet the oath as usual: no +50%. */
    @Test
    void baldurInADuelTheHolderMeetsTheOathAsUsual() {
        try (Scenario s = Scenario.ffa("baldur")) {
            s.place(s.enemy2, 0.5, 26.5, 180);
            weakTo(s, BaldurAbility.Kind.PROJECTILE);
            s.assertSucceeded(s.ultimate(), "겨우살이를 쥔 자");
            s.ticks(2);
            s.clearHits();
            s.melee(s.enemy, s.owner, 4);
            assertEquals(4 * (1 - s.param("sworn_reduction") / 100), s.damageTaken(s.owner), EPSILON,
                    "a blade is still sworn off in a duel: " + s.hits());
            s.healAll();
            s.clearHits();
            shoot(s, s.enemy, s.owner, 10);
            assertEquals(10 * (1 + s.param("weak_bonus") / 100), s.damageTaken(s.owner), EPSILON,
                    "and the weakness is still the weakness");
            s.healAll();
            s.clearHits();
            s.place(s.enemy2, 0.5, 12.5, 180);
            s.melee(s.enemy2, s.owner, 4);
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "anyone else is warded off");
            s.melee(s.enemy, s.owner, 4);
            assertEquals(4 * (1 + s.param("holder_bonus") / 100), s.damageTaken(s.owner), EPSILON,
                    "with company around the mistletoe pierces");
            s.finishClean();
        }
    }

    /** A holder that strays with nobody to take over keeps the mistletoe: the god is never immune to all. */
    @Test
    void baldurMistletoeStaysWithAStrayHolderNobodyCanTakeItFrom() {
        try (Scenario s = Scenario.ffa("baldur")) {
            s.ticks(2);
            s.place(s.enemy2, 0.5, -28.5, 0);
            s.assertSucceeded(s.ultimate(), "겨우살이를 쥔 자");
            s.ticks(2);
            s.place(s.enemy, 0.5, 34.5, 180);
            s.place(s.enemy2, 0.5, -36.5, 0);
            s.ticks(12);
            s.clearHits();
            s.melee(s.enemy, s.owner, 4);
            assertTrue(s.damageTaken(s.owner) > 0, "the stray still holds it: " + s.hits());
            s.finishClean();
        }
    }

    /** The rods of light fly outward from the god, each along its own direction. */
    @Test
    void baldurRodsOfLightPointOutward() {
        try (Scenario s = Scenario.ffa("baldur")) {
            s.ticks(2);
            s.assertSucceeded(s.active(), "찬란한 빛");
            s.ticks(3);
            Vector chest = s.owner.getLocation().add(0, s.owner.getHeight() * 0.55, 0).toVector();
            List<BlockDisplay> rods = entities(s, BlockDisplay.class).stream()
                    .filter(d -> d.getBlock().getMaterial() == Material.END_ROD).toList();
            assertTrue(rods.size() >= 8, "the rods");
            for (BlockDisplay rod : rods) {
                Vector out = boxCenter(rod).subtract(chest).normalize();
                assertTrue(Math.abs(rotated(rod, 0, 1, 0).dot(out)) > 0.98, "a rod lies along its ray: " + out);
            }
            s.ticks(30);
            s.finishClean();
        }
    }

    // ================================================================== smooth on a real client

    static boolean shown(Display display) {
        return display.getTransformation().getScale().x() > 0.01f;
    }

    /** The far end of a staff or beam box that starts at its display's location and runs along its own z. */
    static Vector farEnd(BlockDisplay shaft) {
        return shaft.getLocation().toVector().add(rotated(shaft, 0, 0, shaft.getTransformation().getScale().z()));
    }

    /** The degrees between two rotations. */
    static double degreesBetween(Quaternionf a, Quaternionf b) {
        double dot = Math.abs(a.x() * b.x() + a.y() * b.y() + a.z() * b.z() + a.w() * b.w());
        return Math.toDegrees(2 * Math.acos(Math.min(1.0, dot)));
    }

    /**
     * An owner teleported far (a swap, someone else's pull) has the passive's displays reappear over him at once: a
     * glide over the whole gap would streak them across.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("abilities")
    void passiveDisplaysReappearAtOnceOverATeleportedOwner(String id) {
        try (Scenario s = Scenario.ffa(id)) {
            s.ticks(5);
            Set<UUID> before = entities(s, Display.class).stream().map(Entity::getUniqueId).collect(Collectors.toSet());
            assertEquals(passiveDisplays(id), before.size(), id + ": the passive shows");
            s.place(s.owner, 12.5, 12.5, 0);
            s.ticks(1);
            List<Display> after = entities(s, Display.class);
            assertEquals(passiveDisplays(id), after.size(), id + ": the passive still shows: " + after);
            for (Display display : after) {
                assertFalse(before.contains(display.getUniqueId()), id + ": the " + display.getType()
                        + " reappeared over the owner instead of gliding across the gap");
                assertTrue(display.getLocation().distance(s.owner.getLocation()) < 3, id + ": over the owner: "
                        + display.getLocation());
            }
            s.ticks(5);
            assertEquals(passiveDisplays(id), entities(s, Display.class).size(), id + ": and it stays");
            assertAllUnrotated(s, id);
            s.assertNoProblems();
            s.finishClean();
        }
    }

    /** The dragon's scales unroll from its mouth (never stacked on one point) and glide two ticks a step. */
    @Test
    void fourGodsDragonUnrollsFromItsMouth() {
        try (Scenario s = Scenario.ffa("fourgods")) {
            s.place(s.enemy, -10.5, -10.5, 0);
            s.place(s.enemy2, -12.5, 10.5, 0);
            s.ticks(2);
            s.assertSucceeded(s.active(), "청룡");
            s.ticks(1);
            List<BlockDisplay> scales = entities(s, BlockDisplay.class).stream()
                    .filter(d -> d.getBlock().getMaterial() == Material.LIGHT_BLUE_STAINED_GLASS
                            || d.getBlock().getMaterial() == Material.CYAN_STAINED_GLASS).toList();
            assertEquals(7, scales.size(), "the head and six scales");
            for (BlockDisplay a : scales) {
                for (BlockDisplay b : scales) {
                    if (a != b && shown(a) && shown(b)) {
                        assertTrue(a.getLocation().distance(b.getLocation()) > 0.2,
                                "no two glass scales nested on one point: " + a.getLocation());
                    }
                }
            }
            assertTrue(scales.stream().anyMatch(d -> !shown(d)), "the tail still waits in the mouth, hidden");
            for (BlockDisplay scale : scales) {
                assertEquals(2, scale.getTeleportDuration(), "moved every tick, it glides two");
            }
            s.ticks(4);
            assertTrue(scales.stream().allMatch(DivineNewAbilitiesTest::shown), "the whole chain swam out");
            s.ticks(30);
            assertOnlyPassiveDisplays(s, "the dragon dissolved");
            s.finishClean();
        }
    }

    /** The new guardian's glyph rises from above the stance label, never through it. */
    @Test
    void fourGodsStanceGlyphRisesAboveTheLabel() {
        try (Scenario s = Scenario.ffa("fourgods")) {
            s.ticks(3);
            TextDisplay label = entities(s, TextDisplay.class).stream().filter(t -> text(t).contains("›")).findFirst()
                    .orElse(null);
            assertNotNull(label, "the stance label");
            s.assertSucceeded(s.active(), "청룡");
            TextDisplay glyph = entities(s, TextDisplay.class).stream()
                    .filter(t -> !text(t).contains("›") && text(t).contains("朱雀")).findFirst().orElse(null);
            assertNotNull(glyph, "the bird's glyph");
            // Text grows up from its foot: the label (scale 0.75) is about 0.2 tall.
            assertTrue(glyph.getLocation().getY() >= label.getLocation().getY() + 0.25,
                    "the glyph starts over the label: " + glyph.getLocation().getY() + " vs " + label.getLocation().getY());
            s.ticks(40);
            s.finishClean();
        }
    }

    /** Muninn circling a moving enemy faces along its circle and glides like the enemy it circles. */
    @Test
    void odinMuninnCirclesSmoothlyOverAnEnemyThatMovesUnevenly() {
        try (Scenario s = Scenario.ffa("odin")) {
            s.ticks(3);
            s.melee(s.enemy, s.owner, 2);
            s.ticks(20);
            double x = 0.5;
            // A player's server position advances 0, 1 or 2 moves a tick, as its packets arrive.
            double[] steps = {0, 0.5, 0, 0.6, 0.1, 0, 0.5, 0, 0.6, 0.2, 0, 0.5, 0, 0.6};
            Float last = null;
            BlockDisplay body = null;
            for (double step : steps) {
                x += step;
                s.place(s.enemy, x, 4.5, 180);
                s.ticks(1);
                body = displaysNear(s, headOf(s.enemy), 2.0).stream()
                        .filter(d -> Math.abs(d.getTransformation().getScale().z() - 0.32f) < 1.0E-3).findFirst()
                        .orElse(null);
                assertNotNull(body, "Muninn circles over the enemy");
                Vector forward = rotated(body, 0, 0, 1);
                float yaw = (float) Math.toDegrees(Math.atan2(-forward.getX(), forward.getZ()));
                if (last != null) {
                    float turn = Math.abs(((yaw - last) % 360 + 540) % 360 - 180);
                    assertTrue(turn < 15, "it turns along its circle, not with each uneven step: " + turn);
                }
                last = yaw;
            }
            assertEquals(3, body.getTeleportDuration(), "it glides like the enemy it circles");
            s.finishClean();
        }
    }

    /** Mímir's rune reappears at once over an enemy that was teleported, still for Odin's side only. */
    @Test
    void odinMimirRuneFollowsATeleportedEnemyAtOnce() {
        try (Scenario s = Scenario.teams("odin")) {
            s.ticks(3);
            s.assertSucceeded(s.active(), "미미르의 샘");
            s.ticks(2);
            TextDisplay rune = runeOver(s, s.enemy);
            assertNotNull(rune, "a rune over the enemy");
            assertEquals(3, rune.getTeleportDuration(), "it glides like the enemy it marks");
            s.place(s.enemy, 10.5, 10.5, 0);
            s.ticks(1);
            TextDisplay moved = runeOver(s, s.enemy);
            assertNotNull(moved, "the rune over the teleported enemy");
            assertNotEquals(rune.getUniqueId(), moved.getUniqueId(), "it reappeared there instead of gliding across");
            assertEquals(text(rune), text(moved), "the same rune");
            assertEquals(Set.of(s.owner, s.ally()), Set.copyOf(s.seenBy(moved)), "still for Odin's side only");
            assertFalse(moved.isVisibleByDefault(), "filtered inside its spawn");
            s.ticks(s.paramTicks("sight_time"));
            s.finishClean();
        }
    }

    static TextDisplay runeOver(Scenario s, LivingEntity enemy) {
        Location over = headOf(enemy).add(0, 0.7, 0);
        return entities(s, TextDisplay.class).stream()
                .filter(t -> RUNES.contains(text(t).trim()) && t.getLocation().distance(over) < 0.6).findFirst()
                .orElse(null);
    }

    /** Gungnir's spear and rune ring glide two ticks, and the shaft turns smoothly onto a target that strafes. */
    @Test
    void odinGungnirTurnsSmoothlyOntoAStrafingTarget() {
        try (Scenario s = gungnir()) {
            s.assertSucceeded(s.ultimate(), "궁니르");
            s.h.tickUntil(() -> ringAround(s, s.enemy).size() >= 3, 60, "the rune ring");
            List<TextDisplay> ring = ringAround(s, s.enemy);
            BlockDisplay shaft = entities(s, BlockDisplay.class).stream()
                    .filter(d -> d.getBlock().getMaterial() == Material.STRIPPED_DARK_OAK_WOOD).findFirst().orElse(null);
            assertNotNull(shaft, "the spear");
            double x = 0.5;
            double[] steps = {0.6, 0, -0.6, 0, 0.6, 0, -0.6, 0, 0.6, 0, -0.6, 0, 0.6, 0, -0.6, 0, 0.6, 0, -0.6, 0};
            Vector last = rotated(shaft, 0, 0, 1);
            double worst = 0;
            for (double step : steps) {
                x += step;
                s.place(s.enemy, x, 20.5, 180);
                s.ticks(1);
                if (s.damageTaken(s.enemy) > 0) {
                    break;
                }
                Vector along = rotated(shaft, 0, 0, 1);
                worst = Math.max(worst, Math.toDegrees(along.angle(last)));
                last = along;
            }
            assertTrue(worst < 25, "the shaft never swings with each step of its target: " + worst);
            for (TextDisplay rune : ring) {
                assertEquals(2, rune.getTeleportDuration(), "the ring runes glide two ticks");
            }
            assertEquals(2, shaft.getTeleportDuration(), "the spear glides two ticks");
            s.ticks(80);
            assertEquals(s.param("spear_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "it still strikes");
            s.finishClean();
        }
    }

    static BlockDisplay extendedShaft(Scenario s) {
        return entities(s, BlockDisplay.class).stream()
                .filter(d -> d.getBlock().getMaterial() == Material.RED_CONCRETE
                        && d.getTransformation().getScale().z() > 0.5f).findFirst().orElse(null);
    }

    /** Vaulting off the floor, the staff stays in the sage's hand and planted on the floor while he flies up. */
    @Test
    void wukongVaultStaffStaysBetweenTheHandAndTheFloorItPushesOff() {
        try (Scenario s = Scenario.ffa("wukong")) {
            s.ticks(3);
            s.place(s.owner, 0.5, Y, 0.5, 0, 90);
            s.assertSucceeded(s.active(), "장대뛰기");
            Vector contact = new Vector(0.5, FlatWorld.SURFACE, 0.5);
            for (int i = 0; i < 5; i++) {
                // The staff is placed on the tick, before the world moves the sage on.
                Location eye = s.owner.getEyeLocation();
                s.ticks(1);
                BlockDisplay shaft = extendedShaft(s);
                assertNotNull(shaft, "the staff is out, tick " + i);
                assertTrue(shaft.getLocation().distance(eye) < 0.8, "in the sage's hand, tick " + i + ": "
                        + shaft.getLocation() + " vs the eye " + eye);
                assertTrue(farEnd(shaft).distance(contact) < 0.1, "planted on the floor, tick " + i + ": "
                        + farEnd(shaft));
                assertEquals(3, shaft.getTeleportDuration(), "it follows the hand like the sage's own glide");
            }
            assertTrue(s.owner.getLocation().getY() > FlatWorld.SURFACE + 2, "the sage flew up");
            s.tickUntilGrounded(s.owner, 80);
            s.ticks(5);
            assertOnlyPassiveDisplays(s, "the staff came back");
            s.finishClean();
        }
    }

    /**
     * The skewering staff reaches the enemy where the server (and so every client) sees it, never the steps sent ahead
     * of it: here the server never sees the enemy move (world physics off).
     */
    @Test
    void wukongSkeweringStaffStaysOnTheEnemyAsItIsSeen() {
        try (Scenario s = Scenario.ffa("wukong")) {
            s.ticks(3);
            s.place(s.enemy2, -12.5, -12.5, 0);
            s.aimAt(s.owner, s.enemy);
            s.world.physics().setEnabled(false);
            s.assertSucceeded(s.active(), "여의봉아 늘어나라");
            for (int i = 0; i < 5; i++) {
                s.ticks(1);
                BlockDisplay shaft = extendedShaft(s);
                assertNotNull(shaft, "the staff is out, tick " + i);
                Vector far = farEnd(shaft);
                assertTrue(s.enemy.getBoundingBox().expand(0.6).contains(far), "the tip stays on the enemy, tick " + i
                        + ": " + far);
            }
            s.world.physics().setEnabled(true);
            s.ticks(30);
            assertOnlyPassiveDisplays(s, "the staff shrank back");
            s.finishClean();
        }
    }

    /** The shadow plate lies flat only as far as the ground is level: never floating past a step, nor sunk in one. */
    @Test
    void wukongShadowPlateStopsAtAStep() {
        try (Scenario s = Scenario.ffa("wukong")) {
            s.ticks(3);
            wall(s, -3, 4, 7, 1); // a one-block step across the line: no cover, the pillar falls over it
            s.place(s.owner, 0.5, Y, 0.5, 0, 0);
            s.place(s.enemy, -12.5, 12.5, 0);
            s.place(s.enemy2, -12.5, -12.5, 0);
            s.assertSucceeded(s.ultimate(), "여의봉 천주");
            s.ticks(s.paramTicks("pillar_rise"));
            BlockDisplay plate = entities(s, BlockDisplay.class).stream()
                    .filter(d -> d.getBlock().getMaterial() == Material.BLACK_STAINED_GLASS).findFirst().orElse(null);
            assertNotNull(plate, "the shadow plate");
            assertTrue(boxMaxZ(plate) <= 7 + 0.05, "it stops before the step: " + boxMaxZ(plate));
            assertTrue(boxMaxZ(plate) > 6, "and lies on the level ground up to it: " + boxMaxZ(plate));
            s.ticks(60);
            assertOnlyPassiveDisplays(s, "the staff shrank back");
            clearWall(s, -3, 4, 7, 1);
            s.finishClean();
        }
    }

    /** The weakness label stands clear under the icon, even at the top of the icon's blink. */
    @Test
    void baldurLabelStandsClearOfTheBlinkingIcon() {
        try (Scenario s = Scenario.ffa("baldur")) {
            s.ticks(2);
            ItemDisplay shown = icon(s);
            assertNotNull(shown, "the icon");
            TextDisplay label = entities(s, TextDisplay.class).stream().filter(t -> text(t).contains("약점"))
                    .findFirst().orElse(null);
            assertNotNull(label, "the label");
            double gap = shown.getLocation().getY() - label.getLocation().getY();
            // The icon is centred (half its top blink scale of 0.95 hangs under its centre); the label's text (scale
            // 0.45, a quarter block a line) grows up from its foot.
            assertTrue(gap - 0.95 / 2 >= 0.45 * 0.25 - 1.0E-6, "the label clears the blinking icon: " + gap);
            s.finishClean();
        }
    }

    static List<ItemDisplay> darts(Scenario s) {
        return entities(s, ItemDisplay.class).stream().filter(d -> d.getItemStack().getType() == Material.ARROW)
                .toList();
    }

    /** The mistletoe dart reappears at once over a holder teleported away, and flies on as it is to the next holder. */
    @Test
    void baldurMistletoeDartFollowsATeleportAtOnceAndFliesOnToTheNextHolder() {
        try (Scenario s = Scenario.ffa("baldur")) {
            s.ticks(2);
            s.place(s.enemy2, 0.5, 26.5, 180);
            s.assertSucceeded(s.ultimate(), "겨우살이를 쥔 자");
            s.ticks(8);
            assertEquals(1, darts(s).size(), "one dart");
            ItemDisplay dart = darts(s).getFirst();
            assertTrue(dart.getLocation().distance(headOf(s.enemy)) < 1.0, "over its holder");

            s.place(s.enemy, 6.5, 10.5, 180); // teleported (a swap), still within reach
            s.ticks(1);
            assertEquals(1, darts(s).size(), "still one dart");
            ItemDisplay moved = darts(s).getFirst();
            assertNotEquals(dart.getUniqueId(), moved.getUniqueId(), "it reappeared instead of gliding across");
            assertTrue(moved.getLocation().distance(headOf(s.enemy)) < 1.0, "over the holder at once");
            assertEquals(0.9f, moved.getTransformation().getScale().x(), 1.0E-3, "at full size at once");

            s.place(s.enemy, 0.5, 31.5, 180); // strays: the mistletoe passes on
            s.place(s.enemy2, 0.5, 10.5, 180);
            s.ticks(1);
            assertEquals(1, darts(s).size(), "still one dart");
            assertEquals(moved.getUniqueId(), darts(s).getFirst().getUniqueId(), "the same dart flies on");
            assertEquals(0.9f, darts(s).getFirst().getTransformation().getScale().x(), 1.0E-3,
                    "it flies on as it is, never popping small to regrow");
            s.ticks(7);
            assertTrue(moved.getLocation().distance(headOf(s.enemy2)) < 1.0, "it reached the new holder: "
                    + moved.getLocation());
            s.ticks(s.paramTicks("mistletoe_time"));
            assertOnlyPassiveDisplays(s, "the dart faded");
            s.finishClean();
        }
    }

    /** The sun disc turns on and on without spinning back: its turn wraps after exactly one full turn. */
    @Test
    void raSunDiscNeverSpinsBack() {
        try (Scenario s = Scenario.ffa("ra")) {
            s.ticks(10);
            BlockDisplay plate = entities(s, BlockDisplay.class).stream()
                    .filter(d -> d.getBlock().getMaterial() == Material.GOLD_BLOCK).findFirst().orElse(null);
            assertNotNull(plate, "the disc");
            Quaternionf last = leftRotation(plate);
            double worst = 0;
            int turns = 0;
            for (int i = 0; i < 400; i++) {
                s.ticks(1);
                Quaternionf now = leftRotation(plate);
                double step = degreesBetween(last, now);
                if (step > 0.01) {
                    turns++;
                    worst = Math.max(worst, step);
                }
                last = now;
            }
            assertTrue(turns >= 30, "the disc keeps turning: " + turns);
            assertTrue(worst <= 20, "every turn is a small step forward, never a spin back: " + worst);
            assertTrue(plate.isValid(), "the same disc all along");
            s.finishClean();
        }
    }
}
