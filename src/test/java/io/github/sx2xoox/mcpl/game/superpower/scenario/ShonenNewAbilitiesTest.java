package io.github.sx2xoox.mcpl.game.superpower.scenario;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.google.common.base.Function;
import java.util.stream.Stream;

import io.github.sx2xoox.mcpl.game.superpower.ability.Ability;
import io.github.sx2xoox.mcpl.game.superpower.ability.ActivationResult;
import io.github.sx2xoox.mcpl.game.superpower.support.Scenario;
import io.github.sx2xoox.mcpl.game.superpower.support.TestPlayer;
import io.github.sx2xoox.mcpl.game.superpower.support.world.FlatWorld;
import io.papermc.paper.event.entity.EntityKnockbackEvent;
import org.bukkit.Input;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.EntityDamageEvent.DamageModifier;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** 소년만화 (new): 쭉쭉이, 기공 전사, 심드렁 히어로, 중2병, 맹세의 능력자. */
class ShonenNewAbilitiesTest {

    private static final double EPSILON = 1.0E-6;

    static Stream<String> abilities() {
        return Stream.of("rubber", "kiwarrior", "boredhero", "chuuni", "oath");
    }

    /** The ultimates that work on a fresh instance, so the gambler may borrow them. */
    static Stream<String> standalone() {
        return Stream.of("kiwarrior", "boredhero", "chuuni");
    }

    /** Ultimates relying on the passive or the owner's hooks: never borrowed. */
    static Stream<String> notStandalone() {
        return Stream.of("rubber", "oath");
    }

    /** Abilities whose own rules cost the owner health (a broken vow). */
    private static final Set<String> OWNER_PAYS = Set.of("oath");

    // ================================================================== helpers

    private static <T extends Entity> List<T> entities(Scenario s, Class<T> type) {
        return s.world.getEntities().stream().filter(type::isInstance).map(type::cast).toList();
    }

    private static List<BlockDisplay> blocks(Scenario s, Material material) {
        return entities(s, BlockDisplay.class).stream().filter(d -> d.getBlock().getMaterial() == material).toList();
    }

    private static List<TextDisplay> texts(Scenario s, String containing) {
        return entities(s, TextDisplay.class).stream()
                .filter(d -> d.isValid() && Scenario.plain(d.text()).contains(containing)).toList();
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

    /** A wall of stone across the x axis at z, from x1 to x2, on the floor. */
    private static void wallAcrossX(Scenario s, int z, int x1, int x2, int height) {
        setWall(s, z, x1, x2, height, Material.STONE);
    }

    private static void clearWallAcrossX(Scenario s, int z, int x1, int x2, int height) {
        setWall(s, z, x1, x2, height, Material.AIR);
    }

    private static void setWall(Scenario s, int z, int x1, int x2, int height, Material type) {
        for (int x = x1; x <= x2; x++) {
            for (int y = 0; y < height; y++) {
                s.world.getBlockAt(x, FlatWorld.FLOOR_Y + 1 + y, z).setType(type);
            }
        }
    }

    /** Movement keys a client could hold. */
    private record Keys(boolean isForward, boolean isBackward, boolean isLeft, boolean isRight, boolean isJump,
                        boolean isSneak, boolean isSprint) implements Input {

        static final Keys NONE = new Keys(false, false, false, false, false, false, false);
        static final Keys FORWARD = new Keys(true, false, false, false, false, false, false);
        static final Keys BACKWARD = new Keys(false, true, false, false, false, false, false);
        static final Keys LEFT = new Keys(false, false, true, false, false, false, false);
        static final Keys JUMP = new Keys(false, false, false, false, true, false, false);
    }

    /** The client of {@code player} sends new input, as the server reports it. */
    private static void press(Scenario s, Player player, Keys keys) {
        s.h.server.getPluginManager().callEvent(new PlayerInputEvent(player, keys));
    }

    /** {@code player} walks along {@code velocity} (a fresh velocity every tick) for {@code ticks}. */
    private static void walk(Scenario s, Player player, Vector velocity, int ticks) {
        for (int i = 0; i < ticks; i++) {
            player.setVelocity(velocity);
            s.ticks(1);
        }
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

    /** What each ability needs to use its active on the aimed enemy in the default layout. */
    private static void prepareActive(Scenario s) {
        switch (s.abilityId) {
            case "boredhero" -> s.place(s.enemy, 0.5, 2.5, 180); // a flick reaches 3 blocks
            case "rubber" -> s.place(s.enemy, 0.5, 6.5, 180); // the band goes up between them
            default -> {
            }
        }
        if (s.abilityId.equals("rubber")) {
            s.aimAtFloor(s.owner, 0.5, 4.0);
        } else {
            s.aimAt(s.owner, s.enemy);
        }
    }

    /** What happens in the fight after the active, for {@code ticks}: the enemy walks into the band, vows are kept. */
    private static void followActive(Scenario s, int ticks) {
        switch (s.abilityId) {
            case "rubber" -> {
                s.ticks(3);
                walk(s, s.enemy, new Vector(0, 0, -0.3), 15);
                s.ticks(ticks - 18);
            }
            case "oath" -> {
                for (int i = 0; i < 3; i++) {
                    s.melee(s.owner, s.enemy, 2);
                    s.ticks(10);
                }
                s.ticks(Math.max(0, ticks - 30) + s.paramTicks("vow_duration"));
            }
            default -> s.ticks(ticks);
        }
    }

    /** What each ultimate needs to hit in the default layout. */
    private static void prepareUltimate(Scenario s, String id) {
        if (id.equals("chuuni")) {
            // Nobody may look the chanter in the face.
            s.place(s.enemy, 0.5, 4.5, 0);
            s.place(s.enemy2, 2.0, 6.5, 0);
        }
        s.aimAt(s.owner, s.enemy);
    }

    /** What happens in the fight after the ultimate, for {@code ticks}. */
    private static void followUltimate(Scenario s, String id, int ticks) {
        switch (id) {
            case "rubber" -> {
                s.ticks(5);
                s.place(s.owner, 0.5, 3.4, 0); // the ball rolls into the enemy
                s.ticks(ticks - 5);
            }
            case "oath" -> {
                s.melee(s.owner, s.enemy, 2);
                s.ticks(ticks);
            }
            default -> s.ticks(ticks);
        }
    }

    // ================================================================== every ability

    @ParameterizedTest(name = "{0}")
    @MethodSource("abilities")
    void wholeMatch(String id) {
        try (Scenario s = Scenario.ffa(id)) {
            s.ticks(60);
            s.assertNoProblems();

            prepareActive(s);
            s.clearHits();
            s.assertSucceeded(s.active(), id + " active");
            followActive(s, 100);
            assertTrue(s.damageBy(s.owner, s.enemy) > 0, id + " active hurts the aimed enemy: " + s.hits());
            if (!OWNER_PAYS.contains(id)) {
                assertEquals(0, s.damageTaken(s.owner), EPSILON, id + " active never hurts its owner: " + s.hits());
            }

            s.layout();
            s.healAll();
            prepareUltimate(s, id);
            s.clearHits();
            s.assertSucceeded(s.ultimate(), id + " ultimate");
            followUltimate(s, id, 300);
            assertTrue(s.damageBy(s.owner, s.enemy) + s.damageBy(s.owner, s.enemy2) > 0, id + " ultimate hurts: "
                    + s.hits());
            if (!OWNER_PAYS.contains(id)) {
                assertEquals(0, s.damageTaken(s.owner), EPSILON, id + " ultimate never hurts its owner: "
                        + s.hits());
            }
            s.assertNoProblems();
            // Looking away, every reading and mark fades (the bored hero's yawn comes and goes on its own).
            s.place(s.owner, 0.5, 0.5, 180);
            s.ticks(100);
            List<Display> left = entities(s, Display.class).stream()
                    .filter(d -> !(d instanceof TextDisplay text && Scenario.plain(text.text()).contains("하아암")))
                    .toList();
            assertTrue(left.isEmpty(), id + ": every display is gone: " + left);
            assertTrue(entities(s, Interaction.class).isEmpty(), id + ": no hitbox left");
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
            prepareActive(s);
            Location allyStart = s.ally().getLocation();
            s.clearHits();
            s.assertSucceeded(s.active(), id + " active");
            if (id.equals("rubber")) {
                // The ally walks through the band, the enemy walks into it.
                walk(s, s.ally(), new Vector(0, 0, 0.3), 10);
                s.place(s.ally(), 1.3, 3.0, 180);
                allyStart = s.ally().getLocation();
                walk(s, s.enemy, new Vector(0, 0, -0.3), 15);
            } else if (id.equals("oath")) {
                for (int i = 0; i < 3; i++) {
                    s.melee(s.owner, s.enemy, 2);
                }
            }
            AbilityLifecycleTest.watchAlly(s, allyStart, 100);
            assertTrue(s.damageBy(s.owner, s.enemy) > 0, id + " still hits the enemy next to the ally");

            s.layout();
            s.crossfireAlly();
            prepareUltimate(s, id);
            allyStart = s.ally().getLocation();
            s.assertSucceeded(s.ultimate(), id + " ultimate");
            if (id.equals("rubber")) {
                s.place(s.owner, 0.5, 3.4, 0);
            }
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
            prepareUltimate(s, id);
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

    @ParameterizedTest(name = "{0}")
    @MethodSource("abilities")
    void everyDisplayIsSpawnedUnrotated(String id) {
        try (Scenario s = Scenario.ffa(id)) {
            s.ticks(20);
            s.owner.setRotation(137, 20);
            prepareActive(s);
            s.owner.setRotation(s.owner.getLocation().getYaw() + 0.5f, s.owner.getLocation().getPitch());
            s.assertSucceeded(s.active(), id + " active");
            for (int i = 0; i < 40; i++) {
                s.ticks(1);
                assertAllDisplaysUnrotated(s, id + " active");
            }
            s.layout();
            prepareUltimate(s, id);
            s.assertSucceeded(s.ultimate(), id + " ultimate");
            for (int i = 0; i < 120; i++) {
                s.ticks(1);
                assertAllDisplaysUnrotated(s, id + " ultimate");
            }
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
    void borrowableUltimatesAreMarkedSo() {
        try (Scenario s = gambler(false)) {
            for (String id : standalone().toList()) {
                assertTrue(s.ctx().registry().get(id).info().ultimateStandalone(), id + " is borrowable");
            }
            for (String id : notStandalone().toList()) {
                assertFalse(s.ctx().registry().get(id).info().ultimateStandalone(), id + " is not borrowable");
            }
            s.finishClean();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("standalone")
    void borrowedUltimateWorksOnAGambler(String id) {
        try (Scenario s = gambler(false)) {
            prepareUltimate(s, id);
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
            prepareUltimate(s, id);
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
            prepareUltimate(s, id);
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

    // ================================================================== 기공 전사 kiwarrior

    @Test
    void kiwarriorReadsThePowerOfTheEnemyItLooksAtForEveryone() {
        try (Scenario s = noGaugeGain(Scenario.builder("kiwarrior")).start()) {
            s.match.setGauge(s.enemy, 42);
            s.aimAt(s.owner, s.enemy);
            s.ticks(8);
            List<TextDisplay> readings = texts(s, "전투력");
            assertEquals(1, readings.size(), "one reading over the aimed enemy");
            TextDisplay reading = readings.getFirst();
            assertTrue(Scenario.plain(reading.text()).contains("4,200"), "gauge x 100: " + Scenario.plain(
                    reading.text()));
            assertTrue(reading.getLocation().getY() > s.enemy.getLocation().getY() + s.enemy.getHeight(),
                    "over the head");
            assertEquals(s.enemy.getLocation().getX(), reading.getLocation().getX(), 0.05, "over its head");
            assertUnrotated(reading, "reading");
            assertTrue(reading.isVisibleByDefault(), "everyone sees the reading");

            // The number follows the gauge while it is shown.
            s.match.setGauge(s.enemy, 61);
            s.ticks(8);
            assertTrue(Scenario.plain(reading.text()).contains("6,100"), Scenario.plain(reading.text()));

            // Looking at the other enemy moves the reading: one at a time.
            s.aimAt(s.owner, s.enemy2);
            s.ticks(8);
            assertEquals(1, texts(s, "전투력").size(), "still one reading");
            TextDisplay moved = texts(s, "전투력").getFirst();
            assertEquals(s.enemy2.getLocation().getX(), moved.getLocation().getX(), 0.05, "over the other one now");

            // Looking away, it lingers for the scan duration, then fades.
            s.place(s.owner, 0.5, 0.5, 180);
            s.ticks(s.paramTicks("scan_duration") - 10);
            assertEquals(1, texts(s, "전투력").size(), "lingers a while");
            s.ticks(30);
            assertTrue(texts(s, "전투력").isEmpty(), "and fades");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void kiwarriorCannotMeasureAnEnemyAboutToUltAndMarksItForItsSideOnly() {
        try (Scenario s = noGaugeGain(Scenario.builder("kiwarrior").teams()).start()) {
            s.match.setGauge(s.enemy, 95);
            s.aimAt(s.owner, s.enemy);
            s.ticks(30);
            assertEquals(1, texts(s, "측정 불가").size(), "측정 불가!!");
            List<BlockDisplay> marks = blocks(s, Material.GOLD_BLOCK);
            assertEquals(1, marks.size(), "a gold mark over the enemy");
            BlockDisplay mark = marks.getFirst();
            assertTrue(mark.isGlowing(), "seen through walls");
            assertTrue(s.owner.canSee(mark), "the warrior sees it");
            assertTrue(s.ally().canSee(mark), "the ally sees it");
            assertFalse(s.enemy.canSee(mark), "the marked enemy does not");
            assertFalse(s.enemy2.canSee(mark), "nor its teammate");
            assertUnrotated(mark, "mark");

            s.ticks(s.paramTicks("scan_glow") + 10);
            assertTrue(blocks(s, Material.GOLD_BLOCK).isEmpty(), "the mark fades");
            // Still looking: no new mark within the alert cooldown.
            s.ticks(20);
            assertTrue(blocks(s, Material.GOLD_BLOCK).isEmpty(), "not marked again so soon");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void kiwarriorNeverReadsAnEnemyHiddenFromIt() {
        try (Scenario s = Scenario.ffa("kiwarrior")) {
            s.match.setGauge(s.enemy, 95);
            s.owner.hidePlayer(s.h.hub.plugin(), s.enemy);
            s.aimAt(s.owner, s.enemy);
            s.ticks(30);
            assertTrue(texts(s, "전투력").isEmpty() && texts(s, "측정 불가").isEmpty(), "no reading");
            assertTrue(blocks(s, Material.GOLD_BLOCK).isEmpty(), "no mark");
            s.owner.showPlayer(s.h.hub.plugin(), s.enemy);
            s.finishClean();
        }
    }

    @Test
    void kiwarriorChargeSlowsAndExposesTheWarrior() {
        try (Scenario s = Scenario.ffa("kiwarrior")) {
            double base = baseSpeed(s.owner);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "charge");
            s.ticks(3);
            assertEquals(base * (1 - s.param("charge_slow") / 100), speed(s.owner), EPSILON, "slowed");
            assertEquals(1, texts(s, "전투력").size(), "the warrior's own reading climbs over its head");
            s.clearHits();
            s.melee(s.enemy, s.owner, 5);
            assertEquals(5 * (1 + s.param("charge_vulnerable") / 100), s.damageTaken(s.owner), EPSILON,
                    "takes more while charging");
            assertFalse(s.active().succeeded(), "one charge at a time");
            s.ticks(s.paramTicks("charge_max") + 2);
            assertEquals(base, speed(s.owner), EPSILON, "free again after the blast left");
            s.finishClean();
        }
    }

    @Test
    void kiwarriorQuickReleaseFiresAVolleyOfSmallBlasts() {
        try (Scenario s = noGaugeGain(Scenario.builder("kiwarrior")).start()) {
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "charge");
            s.ticks(3);
            s.owner.setSneaking(true);
            s.ticks(1);
            s.clearHits();
            s.ticks(40);
            List<Scenario.Hit> hits = s.hitsBy(s.owner, s.enemy);
            assertEquals(s.param("small_count"), hits.size(), "every small blast hits: " + hits);
            for (Scenario.Hit hit : hits) {
                assertEquals(s.param("small_damage"), hit.damage(), EPSILON, "a small blast");
            }
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON, "no splash");
            s.owner.setSneaking(false);
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void kiwarriorFullChargeBurstsOverAnAreaAndBlowsEnemiesAway() {
        try (Scenario s = Scenario.ffa("kiwarrior")) {
            s.place(s.enemy2, 1.8, 5.6, 180);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "charge");
            s.clearHits();
            s.ticks(s.paramTicks("charge_max") + 30);
            assertEquals(s.param("big_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "the big blast");
            assertEquals(s.param("big_damage"), s.damageBy(s.owner, s.enemy2), EPSILON, "and its splash");
            assertTrue(s.enemy.getLocation().getZ() > 4.5 + 0.5, "blown away from the blast");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void kiwarriorBlastStopsAtWalls() {
        try (Scenario s = Scenario.ffa("kiwarrior")) {
            wallAcrossX(s, 2, -3, 4, 3);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "charge");
            s.clearHits();
            s.ticks(s.paramTicks("charge_max") + 30);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "the wall takes the blast: " + s.hits());
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "and the warrior is not hurt by its own blast");
            clearWallAcrossX(s, 2, -3, 4, 3);
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void kiwarriorEveryonesKiRootsTheWarriorAndGrowsWithHelpAndBlows() {
        try (Scenario s = noGaugeGain(Scenario.builder("kiwarrior").teams()).start()) {
            s.place(s.enemy2, 12.5, 12.5, 180);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "everyone's ki");
            s.ticks(2);
            assertEquals(0, speed(s.owner), EPSILON, "rooted while gathering");
            assertEquals(0, jump(s.owner), EPSILON, "no jumping either");
            // The ally raises its hands (it sees the warrior), the warrior takes 2 blows.
            s.ally().setSneaking(true);
            s.ticks(2);
            s.ally().setSneaking(false);
            s.melee(s.enemy2, s.owner, 2);
            s.ticks(4);
            s.clearHits();
            s.ticks(s.paramTicks("gather_time") + 40);
            double expected = s.param("ult_damage") + 2 * s.param("bonus_damage");
            assertEquals(expected, s.damageBy(s.owner, s.enemy), EPSILON, "8 + one ally + one bonus for blows");
            assertEquals(0, s.damageTaken(s.ally()), EPSILON, "never the ally");
            assertEquals(baseSpeed(s.owner), speed(s.owner), EPSILON, "free after the throw");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void kiwarriorEveryonesKiBonusIsCapped() {
        try (Scenario s = noGaugeGain(Scenario.builder("kiwarrior")).start()) {
            s.place(s.enemy2, 12.5, 12.5, 180);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "everyone's ki");
            s.ticks(2);
            for (int i = 0; i < 5; i++) {
                s.melee(s.enemy2, s.owner, 3);
                s.ticks(2);
            }
            s.healAll();
            s.clearHits();
            s.ticks(s.paramTicks("gather_time") + 40);
            double expected = s.param("ult_damage") + s.param("hurt_bonus_max") * s.param("bonus_damage");
            assertEquals(expected, s.damageBy(s.owner, s.enemy), EPSILON, "blows add at most their cap");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void kiwarriorEveryonesKiFliesSlowlyAndCanBeDodged() {
        try (Scenario s = Scenario.ffa("kiwarrior")) {
            s.place(s.enemy, 0.5, 14.5, 180);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "everyone's ki");
            s.ticks(s.paramTicks("gather_time") + 10);
            // It is still on its way: the enemy steps aside.
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "slow enough to see it coming");
            s.place(s.enemy, 9.5, 14.5, 180);
            s.ticks(100);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "dodged: " + s.hits());
            s.assertNoProblems();
            s.finishClean();
        }
    }

    // ================================================================== 심드렁 히어로 boredhero

    @Test
    void boredheroShrugsOffAnEnemyUntilItCatchesItsInterest() {
        try (Scenario s = noGaugeGain(Scenario.builder("boredhero")).start()) {
            s.clearHits();
            s.melee(s.owner, s.enemy, 10);
            assertEquals(10 * (1 - s.param("bored_weak") / 100), s.damageBy(s.owner, s.enemy), EPSILON,
                    "barely bothers to hit back");
            s.clearHits();
            s.melee(s.enemy, s.owner, 5);
            assertEquals(5 * (1 - s.param("bored_guard") / 100), s.damageTaken(s.owner), EPSILON, "shrugs it off");
            assertTrue(texts(s, "!").isEmpty(), "not interested yet");
            s.melee(s.enemy, s.owner, 5);
            s.ticks(2);
            assertFalse(texts(s, "!").isEmpty(), "…흥미가 생겼다: a ! over the enemy");
            s.clearHits();
            s.melee(s.owner, s.enemy, 10);
            assertEquals(10 * (1 + s.param("interested_bonus") / 100), s.damageBy(s.owner, s.enemy), EPSILON,
                    "interested now");
            s.clearHits();
            s.melee(s.enemy, s.owner, 5);
            assertEquals(5, s.damageTaken(s.owner), EPSILON, "and no longer shrugs it off");
            // The other enemy is still a bore.
            s.clearHits();
            s.melee(s.owner, s.enemy2, 10);
            assertEquals(10 * (1 - s.param("bored_weak") / 100), s.damageBy(s.owner, s.enemy2), EPSILON);

            s.ticks(s.paramTicks("interest_reset") + 20);
            assertTrue(texts(s, "!").isEmpty(), "bored again");
            s.clearHits();
            s.melee(s.owner, s.enemy, 10);
            assertEquals(10 * (1 - s.param("bored_weak") / 100), s.damageBy(s.owner, s.enemy), EPSILON);
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void boredheroFlickLandsLateAndShootsTheVictimStraightUp() {
        try (Scenario s = noGaugeGain(Scenario.builder("boredhero")).start()) {
            s.place(s.enemy, 0.5, 2.5, 180);
            s.aimAt(s.owner, s.enemy);
            s.clearHits();
            s.assertSucceeded(s.active(), "flick");
            double weak = 1 - s.param("bored_weak") / 100;
            assertEquals(s.param("flick_damage") * weak, s.damageBy(s.owner, s.enemy), EPSILON, "딱!");
            assertEquals(1, texts(s, "딱").size(), "딱! pops on the forehead");
            s.ticks(s.paramTicks("flick_delay") - 2);
            assertTrue(s.enemy.getLocation().getY() < FlatWorld.SURFACE + 1.0E-3, "not yet");
            s.ticks(3);
            double peak = 0;
            for (int i = 0; i < 60; i++) {
                s.ticks(1);
                peak = Math.max(peak, s.enemy.getLocation().getY() - FlatWorld.SURFACE);
                assertEquals(0.5, s.enemy.getLocation().getX(), 1.0E-6, "not pushed aside");
                assertEquals(2.5, s.enemy.getLocation().getZ(), 1.0E-6, "not pushed aside");
            }
            assertEquals(s.param("flick_height"), peak, 0.6, "shot about flick_height up");
            s.tickUntilGrounded(s.enemy, 100);
            assertEquals(FlatWorld.SURFACE, s.enemy.getLocation().getY(), 1.0E-6, "back on the floor");
            List<Scenario.Hit> falls = s.hitsBy(s.owner, s.enemy).stream()
                    .filter(hit -> hit.type() == DamageType.FALL).toList();
            assertEquals(1, falls.size(), "the fall is credited to the hero: " + s.hits());
            assertTrue(falls.getFirst().damage() <= s.param("flick_fall_max") + EPSILON, "capped");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void boredheroFlickSlamsAnInterestingEnemyIntoTheGround() {
        try (Scenario s = noGaugeGain(Scenario.builder("boredhero")).start()) {
            s.place(s.enemy, 0.5, 2.5, 180);
            s.melee(s.enemy, s.owner, 5);
            s.melee(s.enemy, s.owner, 5);
            s.ticks(2);
            s.aimAt(s.owner, s.enemy);
            s.clearHits();
            s.assertSucceeded(s.active(), "flick");
            s.ticks(s.paramTicks("flick_delay") + 1);
            double bonus = 1 + s.param("interested_bonus") / 100;
            assertEquals((s.param("flick_damage") + s.param("slam_damage")) * bonus, s.damageBy(s.owner, s.enemy),
                    EPSILON, "flick + slam");
            assertEquals(0, speed(s.enemy), EPSILON, "stunned");
            assertEquals(0, jump(s.enemy), EPSILON, "stunned");
            for (int i = 0; i < 10; i++) {
                s.ticks(1);
                assertEquals(FlatWorld.SURFACE, s.enemy.getLocation().getY(), 1.0E-6, "kept on the ground");
            }
            s.ticks(s.paramTicks("slam_stun"));
            assertEquals(baseSpeed(s.enemy), speed(s.enemy), EPSILON, "on its feet again");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void boredheroFlickNeedsAnEnemyInReach() {
        try (Scenario s = Scenario.ffa("boredhero")) {
            s.aimAt(s.owner, s.enemy);
            assertFalse(s.active().succeeded(), "4 blocks is too far for a flick");
            assertEquals(0, s.ctx().cooldownRemaining(s.owner), EPSILON, "no cooldown");
            s.finishClean();
        }
    }

    @Test
    void boredheroSeriousPunchHitsAsHardAsTheEnemyIsHealthy() {
        try (Scenario s = noGaugeGain(Scenario.builder("boredhero")).start()) {
            // Interest makes no difference to the serious punch.
            s.melee(s.enemy, s.owner, 5);
            s.melee(s.enemy, s.owner, 5);
            s.healAll();
            s.enemy.setHealth(12);
            s.aimAt(s.owner, s.enemy);
            s.clearHits();
            s.assertSucceeded(s.ultimate(), "serious");
            assertEquals(1, s.owner.getAttribute(Attribute.KNOCKBACK_RESISTANCE).getValue(), EPSILON,
                    "not pushed around while getting serious");
            assertFalse(texts(s, "이번엔 좀 세게").isEmpty(), "says it for everyone to read");
            s.ticks(s.paramTicks("serious_windup") - 2);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "takes its time");
            s.ticks(3);
            double expected = 12 * s.param("serious_share") / 100 + s.param("serious_bonus");
            assertEquals(expected, s.damageBy(s.owner, s.enemy), EPSILON, "half its health + 2");
            assertTrue(s.enemy.getVelocity().length() > 1, "sent flying");
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON, "one enemy only");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void boredheroSeriousPunchIsClamped() {
        for (double health : new double[] {4, 100}) {
            try (Scenario s = noGaugeGain(Scenario.builder("boredhero")).start()) {
                s.enemy.setHealth(health);
                s.aimAt(s.owner, s.enemy);
                s.clearHits();
                s.assertSucceeded(s.ultimate(), "serious");
                s.ticks(s.paramTicks("serious_windup") + 2);
                double expected = health < 10 ? s.param("serious_min") : s.param("serious_max");
                assertEquals(expected, s.damageBy(s.owner, s.enemy), EPSILON, "health " + health);
                s.finishClean();
            }
        }
    }

    @Test
    void boredheroSeriousPunchAtNobodyRefundsHalfTheGauge() {
        try (Scenario s = noGaugeGain(Scenario.builder("boredhero")).start()) {
            s.place(s.owner, 0.5, 0.5, 180);
            s.assertSucceeded(s.ultimate(), "serious");
            s.ticks(s.paramTicks("serious_windup") + 2);
            assertEquals(s.param("serious_refund"), s.ctx().gauge(s.owner), EPSILON, "…귀찮네");
            assertFalse(texts(s, "귀찮네").isEmpty(), "said for everyone to read");
            assertEquals(0, s.damageTaken(s.enemy) + s.damageTaken(s.enemy2), EPSILON);
            s.finishClean();
        }
    }

    @Test
    void boredheroNeverFlicksAnEnemyHiddenFromIt() {
        try (Scenario s = Scenario.ffa("boredhero")) {
            s.place(s.enemy, 0.5, 2.5, 180);
            s.owner.hidePlayer(s.h.hub.plugin(), s.enemy);
            s.aimAt(s.owner, s.enemy);
            assertFalse(s.active().succeeded(), "no forehead in sight");
            s.owner.showPlayer(s.h.hub.plugin(), s.enemy);
            s.finishClean();
        }
    }


    // ================================================================== 쭉쭉이 rubber

    private static double reach(Player player) {
        return attribute(player, Attribute.ENTITY_INTERACTION_RANGE);
    }

    private static double baseReach(Player player) {
        return player.getAttribute(Attribute.ENTITY_INTERACTION_RANGE).getBaseValue();
    }

    @Test
    void rubberArmStretchesOnlyForAWoundUpPunch() {
        try (Scenario s = Scenario.ffa("rubber")) {
            s.ticks(10);
            assertEquals(baseReach(s.owner) + s.param("stretch_reach"), reach(s.owner), EPSILON, "wound up");
            s.melee(s.owner, s.enemy, 2);
            s.ticks(1);
            assertEquals(baseReach(s.owner), reach(s.owner), EPSILON, "the meter is empty after a swing");
            assertEquals(2, blocks(s, Material.DIAMOND_BLOCK).size() + blocks(s, Material.WHITE_CONCRETE).size(),
                    "the arm shot out and snaps back");
            s.ticks(12);
            assertEquals(baseReach(s.owner) + s.param("stretch_reach"), reach(s.owner), EPSILON, "wound up again");
            assertNoDisplays(s, "the arm is back");
            // A swing at the air empties the meter too.
            s.h.server.getPluginManager().callEvent(new io.papermc.paper.event.player.PlayerArmSwingEvent(s.owner,
                    org.bukkit.inventory.EquipmentSlot.HAND));
            s.ticks(1);
            assertEquals(baseReach(s.owner), reach(s.owner), EPSILON, "swung at nothing");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void rubberStretchedArmReachesForTheVictim() {
        try (Scenario s = Scenario.ffa("rubber")) {
            s.place(s.enemy, 3.5, 3.5, 135);
            s.ticks(10);
            s.melee(s.owner, s.enemy, 2);
            s.ticks(2);
            List<BlockDisplay> arm = blocks(s, Material.DIAMOND_BLOCK);
            assertEquals(1, arm.size(), "the arm is out");
            Vector along = rotated(arm.getFirst(), new Vector3f(0, 0, 1));
            Vector toVictim = s.enemy.getLocation().toVector().subtract(s.owner.getLocation().toVector()).setY(0)
                    .normalize();
            assertTrue(Math.hypot(along.getX(), along.getZ()) > 0.9, "reaching out level: " + along);
            assertTrue(new Vector(along.getX(), 0, along.getZ()).normalize().dot(toVictim) > 0.95,
                    "towards the victim: " + along);
            assertUnrotated(arm.getFirst(), "arm");
            s.finishClean();
        }
    }

    @Test
    void rubberInflatesNextToAWall() {
        try (Scenario s = Scenario.ffa("rubber")) {
            for (int y = 0; y < 5; y++) {
                for (int z = -1; z <= 1; z++) {
                    s.world.getBlockAt(1, FlatWorld.FLOOR_Y + 1 + y, z).setType(Material.STONE);
                }
            }
            s.place(s.owner, 0.65, 0.5, 0);
            s.assertSucceeded(s.ultimate(), "a wall at the side only squeezes the ball");
            s.clearHits();
            s.ticks(40);
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "nothing suffocates the ball");
            s.finishMatch();
            for (int y = 0; y < 5; y++) {
                for (int z = -1; z <= 1; z++) {
                    s.world.getBlockAt(1, FlatWorld.FLOOR_Y + 1 + y, z).setType(Material.AIR);
                }
            }
            s.assertNoProblems();
            s.assertCleanWorld();
        }
    }

    @Test
    void rubberHardensInTheCold() {
        try (Scenario s = Scenario.builder("rubber").enemyAbility("frost").start()) {
            s.ticks(10);
            double base = baseSpeed(s.owner);
            s.ctx().damage(s.enemy, s.owner, 1);
            s.ticks(1);
            assertEquals(baseReach(s.owner), reach(s.owner), EPSILON, "no stretching while stiff");
            assertEquals(base * (1 - s.param("harden_slow") / 100), speed(s.owner), EPSILON, "stiff and slow");
            s.ticks(s.paramTicks("harden_duration") + 2);
            assertEquals(base, speed(s.owner), EPSILON, "soft again");
            assertEquals(baseReach(s.owner) + s.param("stretch_reach"), reach(s.owner), EPSILON);

            // Freezing counts as cold too, whoever causes it.
            s.owner.setNoDamageTicks(0);
            s.owner.damage(1, DamageSource.builder(DamageType.FREEZE).build());
            s.ticks(1);
            assertEquals(base * (1 - s.param("harden_slow") / 100), speed(s.owner), EPSILON, "frozen stiff");
            s.finishClean();
        }
    }

    @Test
    void rubberBodyIgnoresFallsButFliesFurtherWhenKnockedBack() {
        try (Scenario s = Scenario.ffa("rubber")) {
            s.place(s.owner, 0.5, FlatWorld.SURFACE + 8, 0.5, 0, 0);
            s.clearHits();
            s.tickUntilGrounded(s.owner, 80);
            s.ticks(2);
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "boing");

            EntityKnockbackEvent knock = new EntityKnockbackEvent(s.owner, EntityKnockbackEvent.Cause.ENTITY_ATTACK,
                    new Vector(0.4, 0.36, 0.2));
            s.h.server.getPluginManager().callEvent(knock);
            double extra = 1 + s.param("knockback_taken") / 100;
            assertEquals(0.4 * extra, knock.getKnockback().getX(), EPSILON, "further");
            assertEquals(0.2 * extra, knock.getKnockback().getZ(), EPSILON, "further");
            assertEquals(0.36, knock.getKnockback().getY(), EPSILON, "not higher");

            EntityKnockbackEvent other = new EntityKnockbackEvent(s.enemy, EntityKnockbackEvent.Cause.ENTITY_ATTACK,
                    new Vector(0.4, 0.36, 0.2));
            s.h.server.getPluginManager().callEvent(other);
            assertEquals(0.4, other.getKnockback().getX(), EPSILON, "only the rubber body");
            s.finishClean();
        }
    }

    /** A melee blow of {@code attacker} on a non-living entity, as the server reports it. */
    @SuppressWarnings("deprecation") // DamageModifier: the only way to build the event
    private static void strike(Scenario s, Player attacker, Entity target) {
        Map<DamageModifier, Double> modifiers = new EnumMap<>(DamageModifier.class);
        modifiers.put(DamageModifier.BASE, 1.0);
        Map<DamageModifier, Function<? super Double, Double>> functions = new EnumMap<>(DamageModifier.class);
        functions.put(DamageModifier.BASE, damage -> 0.0);
        s.h.server.getPluginManager().callEvent(new EntityDamageByEntityEvent(attacker, target,
                DamageCause.ENTITY_ATTACK, DamageSource.builder(DamageType.PLAYER_ATTACK).withCausingEntity(attacker)
                .withDirectEntity(attacker).build(), modifiers, functions, false));
    }

    /** {@code player} walks along {@code velocity} until the owner hurts it (at most {@code ticks}). */
    private static void walkUntilHurt(Scenario s, Player player, Vector velocity, int ticks) {
        double before = s.damageBy(s.owner, player);
        for (int i = 0; i < ticks && s.damageBy(s.owner, player) <= before; i++) {
            player.setVelocity(velocity);
            s.ticks(1);
        }
    }

    /** Puts up the band 4 blocks ahead of the owner (owner at z 0.5 looking +z, band across z 4). */
    private static void band(Scenario s) {
        s.aimAtFloor(s.owner, 0.5, 4.0);
        s.assertSucceeded(s.active(), "rubber band");
        s.ticks(3);
    }

    @Test
    void rubberBandStretchesAcrossTheLookWithArmsAndHitboxes() {
        try (Scenario s = Scenario.teams("rubber")) {
            s.place(s.enemy, 0.5, 7.5, 180);
            s.place(s.enemy2, 6.5, 7.5, 180);
            band(s);
            List<BlockDisplay> elastic = blocks(s, Material.PURPLE_CONCRETE);
            assertEquals(4, elastic.size(), "two purple strands of two halves");
            for (BlockDisplay strand : elastic) {
                Vector along = rotated(strand, new Vector3f(0, 0, 1));
                assertEquals(0, along.getZ(), 1.0E-3, "the strands run across the look: " + along);
                assertUnrotated(strand, "strand");
            }
            assertEquals(4, blocks(s, Material.DIAMOND_BLOCK).size(), "two posts and two arms");
            List<Interaction> hitboxes = entities(s, Interaction.class);
            assertFalse(hitboxes.isEmpty(), "the band can be struck");
            for (Interaction hitbox : hitboxes) {
                assertEquals(4.0, hitbox.getLocation().getZ(), 0.6, "on the band");
                assertFalse(s.owner.canSee(hitbox), "the owner never targets its own band");
                assertFalse(s.ally().canSee(hitbox), "nor does its ally");
                assertTrue(s.enemy.canSee(hitbox), "the enemy may strike it");
            }
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void rubberBandBouncesAnEnemyBackTheWayItCame() {
        try (Scenario s = noGaugeGain(Scenario.builder("rubber")).start()) {
            s.place(s.enemy, 0.5, 6.5, 180);
            band(s);
            s.clearHits();
            double base = baseSpeed(s.enemy);
            walkUntilHurt(s, s.enemy, new Vector(0, 0, -0.3), 20);
            assertEquals(s.param("snap_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "팅!");
            assertTrue(s.enemy.getVelocity().getZ() > 0.3, "thrown back the way it came");
            assertEquals(base * (1 - s.param("stagger_slow") / 100), speed(s.enemy), EPSILON, "staggering");
            assertFalse(texts(s, "팅").isEmpty(), "팅! pops at the band");
            s.tickUntilGrounded(s.enemy, 60);
            assertTrue(s.enemy.getLocation().getZ() > 4.0 + 2.5, "landed well back on its own side");
            assertEquals(FlatWorld.SURFACE, s.enemy.getLocation().getY(), EPSILON, "on the floor");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void rubberBandGivesWayAfterBouncingTheSameEnemyTwice() {
        try (Scenario s = noGaugeGain(Scenario.builder("rubber").param("band_duration", 20)).start()) {
            s.place(s.enemy, 0.5, 6.5, 180);
            band(s);
            s.clearHits();
            for (int attempt = 0; attempt < 3; attempt++) {
                s.place(s.enemy, 0.5, 6.5, 180);
                s.ticks(2);
                walkUntilHurt(s, s.enemy, new Vector(0, 0, -0.3), 14);
                s.ticks(25);
            }
            assertEquals(2 * s.param("snap_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "bounced twice only");
            assertTrue(s.enemy.getLocation().getZ() < 4.0, "the third time it slipped through");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void rubberBandLetsTheOwnerAndAlliesThrough() {
        try (Scenario s = Scenario.teams("rubber")) {
            s.place(s.enemy, 6.5, 9.5, 180);
            s.place(s.enemy2, -6.5, 9.5, 180);
            band(s);
            s.place(s.ally(), 0.5, 2.5, 0);
            s.ticks(2);
            walk(s, s.ally(), new Vector(0, 0, 0.3), 12);
            assertTrue(s.ally().getLocation().getZ() > 5, "the ally walked through");
            walk(s, s.owner, new Vector(0, 0, 0.3), 18);
            assertTrue(s.owner.getLocation().getZ() > 4.5, "the owner too");
            assertEquals(0, s.damageTaken(s.ally()) + s.damageTaken(s.owner), EPSILON);
            s.finishClean();
        }
    }

    @Test
    void rubberBandSnapsWhenStruckTwiceAndHalvesTheCooldown() {
        try (Scenario s = Scenario.ffa("rubber")) {
            s.place(s.enemy, 0.5, 5.0, 180);
            band(s);
            double before = s.ctx().cooldownRemaining(s.owner);
            Interaction part = entities(s, Interaction.class).getFirst();
            for (int i = 0; i < s.param("cut_hits"); i++) {
                strike(s, s.enemy, part);
            }
            s.ticks(1);
            assertTrue(entities(s, Interaction.class).isEmpty(), "툭: the band is gone");
            double after = s.ctx().cooldownRemaining(s.owner);
            assertEquals(before / 2, after, 0.2, "half the cooldown left");
            s.ticks(20);
            assertNoDisplays(s, "the snapped band");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void rubberBandIgnoresTheOwnersOwnBlows() {
        try (Scenario s = Scenario.ffa("rubber")) {
            band(s);
            Interaction part = entities(s, Interaction.class).getFirst();
            for (int i = 0; i < 4; i++) {
                strike(s, s.owner, part);
            }
            s.ticks(1);
            assertFalse(entities(s, Interaction.class).isEmpty(), "still up");
            s.finishClean();
        }
    }

    @Test
    void rubberBandLetGoSnapsForwardIntoTheEnemyBeyond() {
        try (Scenario s = noGaugeGain(Scenario.builder("rubber")).start()) {
            s.place(s.enemy, 0.5, 5.2, 180);
            s.place(s.enemy2, 0.5, 9.5, 180);
            band(s);
            s.clearHits();
            s.owner.setSneaking(true);
            s.ticks(2);
            assertEquals(s.param("release_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "탁!");
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON, "out of reach");
            assertTrue(s.enemy.getVelocity().getZ() > 0.2, "smacked away from the owner");
            assertTrue(entities(s, Interaction.class).isEmpty(), "let go");
            s.owner.setSneaking(false);
            s.ticks(20);
            assertNoDisplays(s, "the released band");
            s.finishClean();
        }
    }

    @Test
    void rubberBandComesLooseWhenTheOwnerWandersOff() {
        try (Scenario s = Scenario.ffa("rubber")) {
            band(s);
            s.place(s.owner, 0.5, -10.0, 0);
            s.ticks(2);
            assertTrue(entities(s, Interaction.class).isEmpty(), "too far: the band comes loose");
            s.finishClean();
        }
    }

    @Test
    void rubberBandAnchorsOnAWallAndNeedsGround() {
        try (Scenario s = Scenario.ffa("rubber")) {
            // A wall right of the band (x -2) shortens that side.
            for (int z = 2; z <= 6; z++) {
                for (int y = 0; y < 3; y++) {
                    s.world.getBlockAt(-2, FlatWorld.FLOOR_Y + 1 + y, z).setType(Material.STONE);
                }
            }
            band(s);
            List<Interaction> hitboxes = entities(s, Interaction.class);
            double lowest = hitboxes.stream().mapToDouble(h -> h.getLocation().getX()).min().orElse(0);
            assertTrue(lowest > -1.6, "the band stops at the wall: " + lowest);
            s.finishMatch();
            for (int z = 2; z <= 6; z++) {
                for (int y = 0; y < 3; y++) {
                    s.world.getBlockAt(-2, FlatWorld.FLOOR_Y + 1 + y, z).setType(Material.AIR);
                }
            }
            s.assertNoProblems();
            s.assertCleanWorld();
        }
    }

    @Test
    void rubberBallGrowsBouncesAndCannotAttack() {
        try (Scenario s = Scenario.ffa("rubber")) {
            s.place(s.enemy, 6.5, 6.5, 180);
            s.place(s.enemy2, -6.5, 6.5, 180);
            double base = baseSpeed(s.owner);
            s.assertSucceeded(s.ultimate(), "ball");
            s.ticks(2);
            assertEquals(s.param("ball_scale"), attribute(s.owner, Attribute.SCALE), EPSILON, "twice the size");
            assertEquals(base * (1 + s.param("ball_speed") / 100), speed(s.owner), EPSILON);
            assertTrue(jump(s.owner) > s.owner.getAttribute(Attribute.JUMP_STRENGTH).getBaseValue(), "bouncier");
            assertEquals(3, blocks(s, Material.CYAN_STAINED_GLASS).size(), "the rubber shell");
            for (BlockDisplay shell : blocks(s, Material.CYAN_STAINED_GLASS)) {
                assertFalse(s.owner.canSee(shell), "not in the owner's own view");
            }
            s.clearHits();
            s.melee(s.owner, s.enemy, 5);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "a ball has no fists");
            assertFalse(s.active().succeeded(), "no band either");
            s.clearHits();
            s.melee(s.enemy, s.owner, 10);
            assertEquals(10 * (1 - s.param("ball_guard") / 100), s.damageTaken(s.owner), EPSILON, "bouncy armour");

            s.h.server.getPluginManager().callEvent(new PlayerSwapHandItemsEvent(s.owner, new ItemStack(Material.AIR),
                    new ItemStack(Material.AIR)));
            s.ticks(1);
            assertEquals(1, attribute(s.owner, Attribute.SCALE), EPSILON, "F lets the air out");
            assertEquals(base, speed(s.owner), EPSILON);
            s.ticks(20);
            assertNoDisplays(s, "the deflated ball");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void rubberBallBumpsLightlyButAWallHurts() {
        try (Scenario s = noGaugeGain(Scenario.builder("rubber")).start()) {
            s.place(s.enemy2, -8.5, 8.5, 180);
            wallAcrossX(s, 6, -3, 4, 3);
            s.place(s.enemy, 0.5, 3.0, 180);
            s.assertSucceeded(s.ultimate(), "ball");
            s.ticks(2);
            s.clearHits();
            s.place(s.owner, 0.5, 1.9, 0);
            s.ticks(1);
            assertEquals(s.param("bump_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "뽀용");
            s.ticks(20);
            assertEquals(s.param("bump_damage") + s.param("crash_damage"), s.damageBy(s.owner, s.enemy), EPSILON,
                    "slammed into the wall: " + s.hits());
            assertTrue(speed(s.enemy) < baseSpeed(s.enemy) || s.enemy.getLocation().getZ() < 6, "it bounced off");
            clearWallAcrossX(s, 6, -3, 4, 3);
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void rubberBallKnocksOneEnemyIntoAnotherLikeBilliards() {
        try (Scenario s = noGaugeGain(Scenario.builder("rubber")).start()) {
            s.place(s.enemy, 0.5, 3.0, 180);
            s.place(s.enemy2, 0.5, 5.4, 180);
            s.assertSucceeded(s.ultimate(), "ball");
            s.ticks(2);
            s.clearHits();
            s.place(s.owner, 0.5, 1.9, 0);
            s.ticks(16);
            assertEquals(s.param("bump_damage") + s.param("crash_damage"), s.damageBy(s.owner, s.enemy), EPSILON,
                    "the bumped one crashed: " + s.hits());
            assertEquals(s.param("crash_damage"), s.damageBy(s.owner, s.enemy2), EPSILON, "into the other one");
            assertTrue(s.enemy2.getLocation().getZ() > 5.4 + 0.5, "which flew on");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void rubberBallBumpsTheSameEnemyOnlyOnceASecond() {
        try (Scenario s = noGaugeGain(Scenario.builder("rubber").param("bump_bounce", 0)).start()) {
            s.place(s.enemy, 0.5, 3.0, 180);
            s.place(s.enemy2, -8.5, 8.5, 180);
            s.assertSucceeded(s.ultimate(), "ball");
            s.ticks(2);
            s.clearHits();
            s.place(s.owner, 0.5, 1.9, 0);
            s.ticks(10);
            assertEquals(s.param("bump_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "once");
            s.ticks(s.paramTicks("bump_cooldown"));
            assertEquals(2 * s.param("bump_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "a second later again");
            s.finishClean();
        }
    }

    @Test
    void rubberBallNeedsRoomToInflate() {
        try (Scenario s = Scenario.ffa("rubber")) {
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    s.world.getBlockAt(x, FlatWorld.FLOOR_Y + 3, z).setType(Material.STONE);
                }
            }
            assertFalse(s.ultimate().succeeded(), "a low ceiling");
            s.finishMatch();
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    s.world.getBlockAt(x, FlatWorld.FLOOR_Y + 3, z).setType(Material.AIR);
                }
            }
            s.assertNoProblems();
            s.assertCleanWorld();
        }
    }

    // ================================================================== 중2병 chuuni

    @Test
    void chuuniEvilEyeSeesThroughAnEnemyStaredDownLongEnough() {
        try (Scenario s = noGaugeGain(Scenario.builder("chuuni")).start()) {
            s.aimAt(s.owner, s.enemy);
            s.ticks(s.paramTicks("eye_time") - 4);
            assertTrue(texts(s, "간파").isEmpty(), "not yet");
            s.ticks(6);
            assertEquals(1, texts(s, "◉").size(), "◉ 간파 over the enemy");
            TextDisplay mark = texts(s, "◉").getFirst();
            assertEquals(s.enemy.getLocation().getX(), mark.getLocation().getX(), 0.05);
            s.clearHits();
            s.melee(s.owner, s.enemy, 5);
            assertEquals(5 * (1 + s.param("eye_bonus") / 100), s.damageBy(s.owner, s.enemy), EPSILON, "seen through");
            s.ticks(1);
            s.clearHits();
            s.melee(s.owner, s.enemy, 5);
            assertEquals(5, s.damageBy(s.owner, s.enemy), EPSILON, "spent");
            s.finishClean();
        }
    }

    @Test
    void chuuniEvilEyeBonusIsCappedAndExpires() {
        try (Scenario s = noGaugeGain(Scenario.builder("chuuni")).start()) {
            s.aimAt(s.owner, s.enemy);
            s.ticks(s.paramTicks("eye_time") + 2);
            s.clearHits();
            s.melee(s.owner, s.enemy, 20);
            assertEquals(20 + s.param("eye_bonus_max"), s.damageBy(s.owner, s.enemy), EPSILON, "capped");

            s.ticks(2);
            s.place(s.owner, 0.5, 0.5, 180);
            s.aimAt(s.owner, s.enemy);
            s.ticks(s.paramTicks("eye_time") + 2);
            assertEquals(1, texts(s, "◉").size(), "seen through again");
            s.place(s.owner, 0.5, 0.5, 180);
            s.ticks(s.paramTicks("eye_window") + 5);
            assertTrue(texts(s, "◉").isEmpty(), "the insight fades");
            s.clearHits();
            s.melee(s.owner, s.enemy, 5);
            assertEquals(5, s.damageBy(s.owner, s.enemy), EPSILON, "no bonus any more");
            s.finishClean();
        }
    }

    @Test
    void chuuniLookingAwayLosesTheStare() {
        try (Scenario s = Scenario.ffa("chuuni")) {
            s.aimAt(s.owner, s.enemy);
            s.ticks(s.paramTicks("eye_time") - 6);
            s.place(s.owner, 0.5, 0.5, 180);
            s.ticks(2);
            s.aimAt(s.owner, s.enemy);
            s.ticks(s.paramTicks("eye_time") - 6);
            assertTrue(texts(s, "◉").isEmpty(), "the stare starts over");
            s.finishClean();
        }
    }

    @Test
    void chuuniNeverStaresDownAnEnemyHiddenFromIt() {
        try (Scenario s = Scenario.ffa("chuuni")) {
            s.owner.hidePlayer(s.h.hub.plugin(), s.enemy);
            s.aimAt(s.owner, s.enemy);
            s.ticks(s.paramTicks("eye_time") + 10);
            assertTrue(texts(s, "◉").isEmpty(), "nothing to see");
            s.owner.showPlayer(s.h.hub.plugin(), s.enemy);
            s.finishClean();
        }
    }

    @Test
    void chuuniStanceSwallowsHalfTheBlowsAndLetsThemOutAsBlackFlame() {
        try (Scenario s = noGaugeGain(Scenario.builder("chuuni")).start()) {
            double base = baseSpeed(s.owner);
            s.place(s.enemy2, 8.5, -6.5, 180);
            overTheHead(s);
            s.assertSucceeded(s.active(), "stance");
            s.ticks(2);
            assertEquals(base * (1 - s.param("stance_slow") / 100), speed(s.owner), EPSILON, "braced");
            List<BlockDisplay> arm = blocks(s, Material.BONE_BLOCK);
            assertEquals(1, arm.size(), "the bandaged arm before the face");
            assertFalse(s.owner.canSee(arm.getFirst()), "out of the owner's own view");
            assertTrue(s.enemy.canSee(arm.getFirst()), "for everyone else to see");
            s.clearHits();
            s.melee(s.enemy, s.owner, 4);
            double share = s.param("swallow_share") / 100;
            assertEquals(4 * (1 - share), s.damageTaken(s.owner), EPSILON, "the arm swallowed half");
            assertFalse(blocks(s, Material.CRYING_OBSIDIAN).isEmpty(), "the seal cracks");
            s.clearHits();
            s.owner.setSneaking(true);
            s.ticks(3);
            double flame = 4 * share * s.param("flame_ratio");
            assertEquals(s.param("strike_base") + flame, s.damageBy(s.owner, s.enemy), EPSILON, "흑염 해방");
            assertTrue(s.enemy.getVelocity().getZ() > 0.1, "pushed away");
            assertEquals(base, speed(s.owner), EPSILON, "free again");
            s.owner.setSneaking(false);
            s.ticks(40);
            assertNoDisplays(s, "after the strike");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void chuuniStanceFlameIsCappedAndAnEmptyArmStillStrikes() {
        try (Scenario s = noGaugeGain(Scenario.builder("chuuni")).start()) {
            s.place(s.enemy2, 8.5, -6.5, 180);
            overTheHead(s);
            s.assertSucceeded(s.active(), "stance");
            s.ticks(2);
            s.melee(s.enemy, s.owner, 12);
            s.clearHits();
            s.ticks(s.paramTicks("stance_time") + 2);
            assertEquals(s.param("strike_base") + s.param("flame_max"), s.damageBy(s.owner, s.enemy), EPSILON,
                    "capped flame");

            s.readyActive();
            s.layout();
            s.place(s.enemy2, 8.5, -6.5, 180);
            s.ticks(2);
            overTheHead(s);
            s.assertSucceeded(s.active(), "stance again");
            s.clearHits();
            s.ticks(s.paramTicks("stance_time") + 2);
            assertEquals(s.param("strike_base"), s.damageBy(s.owner, s.enemy), EPSILON, "nothing swallowed");
            assertFalse(texts(s, "봉인이 단단하군").isEmpty(), "…오늘은 봉인이 단단하군");
            s.finishClean();
        }
    }

    @Test
    void chuuniStanceStrikesOnlyTheConeInFront() {
        try (Scenario s = Scenario.ffa("chuuni")) {
            s.place(s.enemy2, 0.5, -3.5, 0);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "stance");
            s.clearHits();
            s.ticks(s.paramTicks("stance_time") + 3);
            assertTrue(s.damageBy(s.owner, s.enemy) > 0, "in front");
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON, "behind is safe");
            s.finishClean();
        }
    }

    /** The chuuni faces the enemy but looks over its head (no stare, so no evil-eye bonus). */
    private static void overTheHead(Scenario s) {
        s.aimAt(s.owner, new Location(s.world, 0.5, FlatWorld.SURFACE + 6, 4.5));
    }

    /** Turns both enemies away from the chuuni (nobody watches), the default layout otherwise. */
    private static void nobodyWatches(Scenario s) {
        s.place(s.enemy, 0.5, 4.5, 0);
        s.place(s.enemy2, 2.0, 6.5, 0);
    }

    @Test
    void chuuniChantDrawsAPathAndTheDragonFliesIt() {
        // The gaze sweeps over the enemy: no evil eye here, it would add its bonus to the dragon.
        try (Scenario s = noGaugeGain(Scenario.builder("chuuni").param("eye_time", 100)).start()) {
            nobodyWatches(s);
            s.place(s.enemy2, 0.5, 12.0, 0);
            s.aimAtFloor(s.owner, 0.5, 3.0);
            s.clearHits();
            s.assertSucceeded(s.ultimate(), "chant");
            assertEquals(1, texts(s, "봉인이여").size(), "the first line over its head");
            // Sweep the gaze along the floor to z 12.
            for (int i = 0; i < s.paramTicks("chant_time"); i++) {
                s.aimAtFloor(s.owner, 0.5, Math.min(12, 3.0 + i * 0.4));
                s.ticks(1);
            }
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "the dragon is not out yet");
            s.ticks(s.paramTicks("dragon_time") + 5);
            assertEquals(s.param("dragon_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "burnt on the way");
            assertTrue(s.enemy.hasPotionEffect(PotionEffectType.DARKNESS), "black flame darkens the sight");
            assertTrue(s.enemy.getFireTicks() <= 0, "black flame is no fire");
            assertEquals(s.param("dragon_damage") + s.param("plunge_damage"), s.damageBy(s.owner, s.enemy2),
                    EPSILON, "burnt and plunged into at the end of the path: " + s.hits());
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.ticks(20);
            assertNoDisplays(s, "after the dragon");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void chuuniDragonPathIsCappedAtTheTrailLength() {
        try (Scenario s = noGaugeGain(Scenario.builder("chuuni").param("trail_length", 6)).start()) {
            nobodyWatches(s);
            s.place(s.enemy, 0.5, 11.0, 0);
            s.place(s.enemy2, 8.5, -8.5, 0);
            s.aimAtFloor(s.owner, 0.5, 4.0);
            s.assertSucceeded(s.ultimate(), "chant");
            for (int i = 0; i < s.paramTicks("chant_time"); i++) {
                s.aimAtFloor(s.owner, 0.5, Math.min(12, 4.0 + i * 0.4));
                s.ticks(1);
            }
            s.ticks(s.paramTicks("dragon_time") + 10);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "the path ended 6 blocks out");
            s.finishClean();
        }
    }

    @Test
    void chuuniChantFailsWhenStaredAtAndGivesHalfTheGaugeBack() {
        try (Scenario s = noGaugeGain(Scenario.builder("chuuni")).start()) {
            s.aimAt(s.owner, s.enemy);
            s.clearHits();
            s.assertSucceeded(s.ultimate(), "chant");
            // Both enemies look it in the face: 0.8 s of staring between them fails it in about 0.4 s.
            s.ticks(s.paramTicks("shy_time") / 2 + 2);
            assertEquals(s.param("fail_refund"), s.ctx().gauge(s.owner), EPSILON, "half the gauge back");
            assertFalse(texts(s, "보지 마").isEmpty(), "보, 보지 마…!");
            s.ticks(s.paramTicks("chant_time") + 40);
            assertEquals(0, s.damageTaken(s.enemy) + s.damageTaken(s.enemy2), EPSILON, "no dragon");
            s.finishClean();
        }
    }

    @Test
    void chuuniChantIsNotShyBehindAWall() {
        try (Scenario s = noGaugeGain(Scenario.builder("chuuni")).start()) {
            wallAcrossX(s, 3, -4, 5, 3);
            s.place(s.enemy, 0.5, 5.5, 180);
            s.place(s.enemy2, 2.0, 6.5, 180);
            s.place(s.owner, 0.5, 0.5, 90);
            s.assertSucceeded(s.ultimate(), "chant");
            s.ticks(s.paramTicks("chant_time") - 1);
            assertEquals(0, s.ctx().gauge(s.owner), EPSILON, "the wall hides it: no refund, the chant goes on");
            assertTrue(texts(s, "보지 마").isEmpty());
            s.finishMatch();
            clearWallAcrossX(s, 3, -4, 5, 3);
            s.assertNoProblems();
            s.assertCleanWorld();
        }
    }

    @Test
    void chuuniChantBreaksWhenSilenced() {
        try (Scenario s = noGaugeGain(Scenario.builder("chuuni")).start()) {
            nobodyWatches(s);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "chant");
            s.ticks(5);
            s.ctx().silence(s.owner, 20);
            s.ticks(2);
            assertEquals(s.param("fail_refund"), s.ctx().gauge(s.owner), EPSILON, "half the gauge back");
            s.clearHits();
            s.ticks(60);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "no dragon");
            s.finishClean();
        }
    }

    @Test
    void chuuniDragonHeadPointsAlongItsFlight() {
        try (Scenario s = Scenario.ffa("chuuni")) {
            nobodyWatches(s);
            s.place(s.owner, 0.5, 0.5, -90); // looking +x
            s.place(s.enemy, 8.5, 0.5, -90); // looking away
            s.place(s.enemy2, -8.5, 8.5, 90);
            s.aimAtFloor(s.owner, 4.0, 0.5);
            s.assertSucceeded(s.ultimate(), "chant");
            for (int i = 0; i < s.paramTicks("chant_time"); i++) {
                s.aimAtFloor(s.owner, Math.min(12, 4.0 + i * 0.4), 0.5);
                s.ticks(1);
            }
            s.ticks(4);
            List<BlockDisplay> horns = blocks(s, Material.PURPLE_CONCRETE);
            assertEquals(2, horns.size(), "two horns on the head");
            for (BlockDisplay horn : horns) {
                Vector along = rotated(horn, new Vector3f(0, 0, 1));
                assertTrue(along.getX() > 0.9, "the head looks the way it flies (+x): " + along);
                assertUnrotated(horn, "horn");
            }
            s.finishClean();
        }
    }

    // ================================================================== 맹세의 능력자 oath

    @Test
    void oathThreeStrikesKeptAndFulfilled() {
        try (Scenario s = noGaugeGain(Scenario.builder("oath")).start()) {
            s.owner.setHealth(10);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "vow");
            assertEquals(1, texts(s, "세 번 벤다").size(), "the vow over its head");
            assertTrue(texts(s, "三").size() >= 2, "the band of slips");
            s.clearHits();
            double bonus = 1 + s.param("vow_bonus") / 100;
            for (int i = 0; i < s.param("strike_count"); i++) {
                s.melee(s.owner, s.enemy, 2);
                s.ticks(5);
            }
            assertEquals(3 * 2 * bonus, s.damageBy(s.owner, s.enemy), EPSILON, "every hit heavier");
            s.ticks(s.paramTicks("vow_duration"));
            assertEquals(10 + s.param("fulfil_heal"), s.owner.getHealth(), EPSILON, "성취: healed");
            assertEquals(s.param("fulfil_gauge"), s.ctx().gauge(s.owner), EPSILON, "and gauge");
            assertFalse(s.ctx().isSilenced(s.owner), "no penalty");
            s.clearHits();
            s.melee(s.owner, s.enemy, 2);
            assertEquals(2, s.damageBy(s.owner, s.enemy), EPSILON, "no vow, no bonus");
            s.ticks(20);
            assertNoDisplays(s, "after the vow");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void oathThreeStrikesBrokenBurns() {
        try (Scenario s = noGaugeGain(Scenario.builder("oath")).start()) {
            double base = baseSpeed(s.owner);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "vow");
            s.melee(s.owner, s.enemy, 2);
            s.ticks(5);
            s.melee(s.owner, s.enemy, 2);
            s.clearHits();
            s.ticks(s.paramTicks("vow_duration"));
            assertEquals(s.param("break_damage"), s.damageTaken(s.owner), EPSILON, "맹세 위반!");
            assertTrue(s.ctx().isSilenced(s.owner), "silenced");
            assertEquals(base * (1 - s.param("break_slow") / 100), speed(s.owner), EPSILON, "heavy steps");
            assertFalse(texts(s, "맹세 위반").isEmpty(), "for everyone to see");
            assertEquals(0, s.ctx().gauge(s.owner), EPSILON, "no reward");
            s.ticks(s.paramTicks("break_bind") + 2);
            assertEquals(base, speed(s.owner), EPSILON);
            assertFalse(s.ctx().isSilenced(s.owner));
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void oathKeptWithoutRealDamageIsNoAchievement() {
        try (Scenario s = noGaugeGain(Scenario.builder("oath")).start()) {
            s.owner.setHealth(10);
            s.assertSucceeded(s.active(), "vow");
            for (int i = 0; i < 3; i++) {
                s.melee(s.owner, s.enemy, 0.5);
                s.ticks(5);
            }
            s.ticks(s.paramTicks("vow_duration"));
            assertEquals(10, s.owner.getHealth(), EPSILON, "kept, but not fulfilled");
            assertEquals(0, s.ctx().gauge(s.owner), EPSILON);
            s.finishClean();
        }
    }

    @Test
    void oathPenaltyNeverKills() {
        try (Scenario s = noGaugeGain(Scenario.builder("oath")).start()) {
            s.assertSucceeded(s.active(), "vow");
            s.owner.setHealth(2);
            s.ticks(s.paramTicks("vow_duration") + 2);
            assertEquals(1, s.owner.getHealth(), EPSILON, "burnt down to one, never dead");
            assertTrue(s.session.isAlive(s.owner));
            s.finishClean();
        }
    }

    @Test
    void oathNoRetreatBreaksOnTheBackKeyNotOnBeingPushed() {
        try (Scenario s = noGaugeGain(Scenario.builder("oath")).start()) {
            s.owner.setSneaking(true);
            s.assertSucceeded(s.active(), "vow");
            s.owner.setSneaking(false);
            assertEquals(1, texts(s, "물러서지 않는다").size());
            assertEquals(1, attribute(s.owner, Attribute.KNOCKBACK_RESISTANCE), EPSILON, "unmovable");
            s.ticks(10);
            // Pushed back hard: not its doing.
            walk(s, s.owner, new Vector(0, 0, -0.4), 6);
            press(s, s.owner, Keys.LEFT);
            s.ticks(10);
            press(s, s.owner, Keys.FORWARD);
            s.ticks(10);
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "strafing and charging are fine");
            press(s, s.owner, Keys.BACKWARD);
            s.ticks(1);
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "a moment of slack for the ping");
            s.ticks(4);
            assertEquals(s.param("break_damage"), s.damageTaken(s.owner), EPSILON, "stepped back: 위반");
            assertEquals(0, attribute(s.owner, Attribute.KNOCKBACK_RESISTANCE), EPSILON, "the vow is over");
            press(s, s.owner, Keys.NONE);
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void oathNoRetreatBreaksWalkingAwayFromTheNearestEnemy() {
        try (Scenario s = noGaugeGain(Scenario.builder("oath")).start()) {
            s.owner.setSneaking(true);
            s.assertSucceeded(s.active(), "vow");
            s.owner.setSneaking(false);
            s.ticks(10);
            // Turned away from the enemy and walking forward: that is running away.
            s.place(s.owner, 0.5, 0.5, 180);
            press(s, s.owner, Keys.FORWARD);
            s.ticks(6);
            assertEquals(s.param("break_damage"), s.damageTaken(s.owner), EPSILON, "running away: 위반");
            press(s, s.owner, Keys.NONE);
            s.finishClean();
        }
    }

    @Test
    void oathStandStillIsSwornInTheAirAndJudgedAfterLanding() {
        try (Scenario s = noGaugeGain(Scenario.builder("oath")).start()) {
            s.place(s.owner, 0.5, FlatWorld.SURFACE + 1.5, 0.5, 0, 0);
            s.assertSucceeded(s.active(), "vow");
            assertEquals(1, texts(s, "움직이지 않는다").size());
            press(s, s.owner, Keys.FORWARD);
            s.ticks(2);
            press(s, s.owner, Keys.NONE);
            s.tickUntilGrounded(s.owner, 40);
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "keys in the air do not count");
            s.ticks(10);
            s.clearHits();
            double bonus = 1 + s.param("still_bonus") / 100;
            s.melee(s.owner, s.enemy, 5);
            assertEquals(5 * bonus, s.damageBy(s.owner, s.enemy), EPSILON, "standing firm hits harder");
            s.melee(s.enemy, s.owner, 10);
            assertEquals(10 * (1 - s.param("still_guard") / 100), s.damageTaken(s.owner), EPSILON, "and takes less");
            s.healAll();
            s.clearHits();
            press(s, s.owner, Keys.JUMP);
            s.ticks(5);
            assertEquals(s.param("break_damage"), s.damageTaken(s.owner), EPSILON, "jumped: 위반");
            press(s, s.owner, Keys.NONE);
            s.finishClean();
        }
    }

    @Test
    void oathStandStillKeptToTheEnd() {
        try (Scenario s = noGaugeGain(Scenario.builder("oath")).start()) {
            s.owner.setHealth(10);
            s.place(s.owner, 0.5, FlatWorld.SURFACE + 1.5, 0.5, 0, 0);
            s.assertSucceeded(s.active(), "vow");
            s.tickUntilGrounded(s.owner, 40);
            s.ticks(2);
            s.melee(s.owner, s.enemy, 5);
            s.ticks(s.paramTicks("still_duration") + 5);
            assertEquals(10 + s.param("fulfil_heal"), s.owner.getHealth(), EPSILON, "성취");
            s.finishClean();
        }
    }

    @Test
    void oathVowOnItsLifeHitsHardButLocksTheRetreat() {
        try (Scenario s = noGaugeGain(Scenario.builder("oath")).start()) {
            double base = baseSpeed(s.owner);
            s.assertSucceeded(s.ultimate(), "life vow");
            s.ticks(1);
            assertEquals(base * (1 + s.param("life_speed") / 100), speed(s.owner), EPSILON, "faster");
            assertEquals(1, attribute(s.owner, Attribute.KNOCKBACK_RESISTANCE), EPSILON, "unmovable");
            assertFalse(s.active().succeeded(), "no skills");
            s.clearHits();
            s.melee(s.owner, s.enemy, 5);
            assertEquals(5 * (1 + s.param("life_bonus") / 100), s.damageBy(s.owner, s.enemy), EPSILON, "heavy");
            s.melee(s.enemy, s.owner, 5);
            assertEquals(5 * (1 + s.param("life_vulnerable") / 100), s.damageTaken(s.owner), EPSILON, "exposed");
            press(s, s.owner, Keys.BACKWARD);
            assertEquals(0, speed(s.owner), EPSILON, "not one step back");
            press(s, s.owner, Keys.FORWARD);
            assertEquals(base * (1 + s.param("life_speed") / 100), speed(s.owner), EPSILON, "forward is fine");
            press(s, s.owner, Keys.NONE);
            s.healAll();
            s.clearHits();
            s.ticks(s.paramTicks("life_duration") + 2);
            assertEquals(s.param("life_cost"), s.damageTaken(s.owner), EPSILON, "no kill: the price");
            assertEquals(base, speed(s.owner), EPSILON);
            assertEquals(0, attribute(s.owner, Attribute.KNOCKBACK_RESISTANCE), EPSILON);
            s.ticks(20);
            assertNoDisplays(s, "after the life vow");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void oathVowOnItsLifeFulfilledByAKill() {
        try (Scenario s = noGaugeGain(Scenario.builder("oath")).start()) {
            s.assertSucceeded(s.ultimate(), "life vow");
            s.owner.setHealth(6);
            s.enemy.setHealth(2);
            s.melee(s.owner, s.enemy, 5);
            assertFalse(s.session.isAlive(s.enemy), "killed");
            assertEquals(6 + s.param("life_heal"), s.owner.getHealth(), EPSILON, "맹세 성취");
            s.clearHits();
            s.ticks(s.paramTicks("life_duration") + 2);
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "no price to pay");
            s.respawn(s.enemy);
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void oathVowOnItsLifeSupersedesARunningVow() {
        try (Scenario s = noGaugeGain(Scenario.builder("oath")).start()) {
            s.assertSucceeded(s.active(), "vow");
            s.ticks(5);
            s.assertSucceeded(s.ultimate(), "life vow");
            s.clearHits();
            s.ticks(20);
            assertTrue(texts(s, "세 번 벤다").isEmpty(), "the old vow is gone");
            assertEquals(1, texts(s, "목숨을 건 맹세").size(), "the vow on its life took its place");
            s.ticks(s.paramTicks("vow_duration"));
            assertEquals(s.param("life_cost"), s.damageTaken(s.owner), EPSILON,
                    "superseded, not broken: only the vow on its life is paid for");
            s.finishClean();
        }
    }

    // ================================================================== descriptions

    @Test
    void descriptionsNameEveryParam() {
        try (Scenario s = Scenario.ffa("kiwarrior")) {
            for (String id : abilities().toList()) {
                var info = s.ctx().registry().get(id).info();
                String all = info.passive().description() + info.active().description()
                        + info.ultimate().description();
                for (String key : info.params().keySet()) {
                    if (!key.equals("cooldown")) {
                        assertTrue(all.contains("{" + key + "}"), id + " describes " + key);
                    }
                }
                assertNotNull(info.color());
                assertFalse(info.passive().name().equals("TODO"), id + " is implemented");
            }
            s.finishClean();
        }
    }
}
