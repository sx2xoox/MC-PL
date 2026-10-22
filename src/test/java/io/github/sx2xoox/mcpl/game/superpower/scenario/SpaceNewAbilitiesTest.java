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
import org.bukkit.entity.Arrow;
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

/** 기술·우주 계열 (new): 메카, 천문학자, 해커. */
class SpaceNewAbilitiesTest {

    private static final double EPSILON = 1.0E-6;

    static Stream<String> abilities() {
        return Stream.of("mech", "astronomer", "hacker");
    }

    /** The ultimates the gambler may borrow (the mech suit relies on its own hooks). */
    static Stream<String> standalone() {
        return Stream.of("astronomer", "hacker");
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

    /** No ultimate gauge from damage or time, so gauge numbers in a test come from the ability alone. */
    private static Scenario.Builder noGaugeGain(Scenario.Builder builder) {
        return builder.configure(h -> {
            h.settings().set("ultimate.per-damage-dealt", 0);
            h.settings().set("ultimate.per-damage-taken", 0);
            h.settings().set("ultimate.per-second", 0);
        });
    }

    private static double gauge(Scenario s, Player player) {
        return s.participant(player).gauge();
    }

    private static double scale(Player player) {
        return player.getAttribute(Attribute.SCALE).getValue();
    }

    private static double horizontalDistance(Location a, Location b) {
        return Math.hypot(a.getX() - b.getX(), a.getZ() - b.getZ());
    }

    /** An arrow from {@code shooter} hitting {@code victim} for {@code amount}. */
    private static void shoot(Scenario s, Player shooter, Player victim, double amount) {
        Arrow arrow = s.world.spawnArrow(shooter.getEyeLocation(), new Vector(0, 0, -1), 1, 0);
        arrow.setShooter(shooter);
        victim.setNoDamageTicks(0);
        victim.damage(amount, DamageSource.builder(DamageType.ARROW).withCausingEntity(shooter)
                .withDirectEntity(arrow).build());
        arrow.remove();
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
            if (id.equals("mech")) {
                s.ticks(5);
                s.aimAt(s.owner, s.enemy);
                s.assertSucceeded(s.active(), "rocket punch");
            }
            s.ticks(300);
            assertTrue(s.damageBy(s.owner, s.enemy) + s.damageBy(s.owner, s.enemy2) > 0, id + " ultimate hurts");
            assertEquals(0, s.damageTaken(s.owner), EPSILON, id + " ultimate never hurts its owner: " + s.hits());
            s.assertNoProblems();
            if (id.equals("astronomer")) {
                // Only the lit stars of the unfinished constellation stay, over the astronomer's head.
                assertTrue(entities(s, BlockDisplay.class).isEmpty(), "meteors, rubble and cracks are gone");
                assertTrue(entities(s, TextDisplay.class).size() < s.param("constellation_stars"),
                        "only the constellation's stars remain");
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
            if (id.equals("mech")) {
                s.ticks(5);
                s.assertSucceeded(s.active(), "rocket punch");
            }
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
            s.ticks(12);
            if (id.equals("mech")) {
                s.assertSucceeded(s.active(), "rocket punch");
                s.ticks(2);
            }
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
        assertEquals(1.0, scale(s.owner), EPSILON, id + ": normal size");
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
    void mechSuitIsNotBorrowable() {
        try (Scenario s = gambler(false)) {
            assertFalse(s.ctx().registry().get("mech").info().ultimateStandalone(), "the suit needs its hooks");
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

    // ================================================================== 메카 mech

    @Test
    void mechArmorShrugsOffKnockbackAndBluntsArrows() {
        try (Scenario s = Scenario.ffa("mech")) {
            assertEquals(s.param("armor_knockback") / 100,
                    s.owner.getAttribute(Attribute.KNOCKBACK_RESISTANCE).getValue(), EPSILON, "knockback resistance");
            shoot(s, s.enemy, s.owner, 8);
            assertEquals(8 * (1 - s.param("armor_projectile") / 100), s.damageTaken(s.owner), EPSILON,
                    "the plating blunts arrows");
            s.clearHits();
            s.melee(s.enemy, s.owner, 8);
            assertEquals(8, s.damageTaken(s.owner), EPSILON, "melee is not blunted");
            s.finishClean();
        }
    }

    @Test
    void mechMissileVolleyCurvesOntoTheAimedEnemy() {
        try (Scenario s = Scenario.ffa("mech")) {
            s.place(s.enemy, 0.5, 8.5, 180);
            s.place(s.enemy2, 9.5, -6.5, 0);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "미사일 포드");
            assertFalse(s.active().succeeded(), "on cooldown");
            s.ticks(2);
            assertTrue(entities(s, BlockDisplay.class).size() >= 6, "two missile pods rise over the shoulders");
            s.ticks(60);
            int count = (int) s.param("missile_count");
            List<Scenario.Hit> hits = s.hitsBy(s.owner, s.enemy);
            assertEquals(count, hits.size(), "every missile finds the aimed enemy: " + hits);
            for (Scenario.Hit hit : hits) {
                assertEquals(s.param("missile_damage"), hit.damage(), EPSILON);
            }
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON, "enemy2 is far away");
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "the mech is never caught in its own blasts");
            s.ticks(20);
            assertNoDisplays(s, "pods and missiles");
            s.finishClean();
        }
    }

    @Test
    void mechMissilesBlowUpOnWallsWithoutReachingThrough() {
        try (Scenario s = Scenario.ffa("mech")) {
            s.world.wallAlongX(-4, 4, 3, 7, Material.STONE);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "미사일 포드 into a wall");
            s.ticks(80);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "the wall takes the blasts: " + s.hits());
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            assertNoDisplays(s, "missiles");
            s.world.wallAlongX(-4, 4, 3, 7, Material.AIR);
            s.finishClean();
        }
    }

    @Test
    void mechMissilesSplashEveryEnemyAroundTheBlast() {
        try (Scenario s = Scenario.ffa("mech")) {
            s.place(s.enemy2, 1.7, 4.5, 180);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "미사일 포드");
            s.ticks(60);
            assertTrue(s.damageBy(s.owner, s.enemy) > 0, "the aimed enemy is hit");
            assertTrue(s.damageBy(s.owner, s.enemy2) > 0, "the enemy next to it is caught in the splash");
            s.finishClean();
        }
    }

    @Test
    void mechSuitGrowsArmorsHitsHarderAndPunches() {
        try (Scenario s = Scenario.ffa("mech")) {
            s.place(s.enemy2, 9.5, -6.5, 0);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "미사일 포드");
            s.ticks(60);
            s.clearHits();
            assertTrue(s.ctx().cooldownRemaining(s.owner) > 2, "the missiles are cooling down");

            s.assertSucceeded(s.ultimate(), "메카 변신");
            assertFalse(s.ultimate().succeeded(), "already in the suit");
            assertEquals(0, s.ctx().cooldownRemaining(s.owner), EPSILON, "the first rocket punch is ready at once");
            s.ticks(20);
            assertEquals(s.param("ult_scale"), scale(s.owner), EPSILON, "a bigger mech");
            assertTrue(s.owner.getAttribute(Attribute.KNOCKBACK_RESISTANCE).getValue() >= 1.0 - EPSILON,
                    "immune to knockback");
            List<BlockDisplay> suit = entities(s, BlockDisplay.class);
            assertTrue(suit.size() >= 12, "the suit is bolted on: " + suit.size());
            assertTrue(suit.stream().anyMatch(d -> !s.owner.canSee(d) && s.enemy.canSee(d)),
                    "the helmet is hidden from the pilot's own view only");

            s.melee(s.owner, s.enemy, 5);
            assertEquals(5 * (1 + s.param("ult_melee") / 100), s.damageBy(s.owner, s.enemy), EPSILON,
                    "steel fists hit harder");
            s.clearHits();
            s.melee(s.enemy, s.owner, 8);
            assertEquals(8 * (1 - s.param("ult_armor") / 100), s.damageTaken(s.owner), EPSILON, "the suit absorbs");
            s.clearHits();
            shoot(s, s.enemy, s.owner, 8);
            assertEquals(8 * (1 - s.param("ult_armor") / 100) * (1 - s.param("armor_projectile") / 100),
                    s.damageTaken(s.owner), EPSILON, "arrows are blunted twice");

            s.ticks(2); // the steel fist's shove lands the tick after the hit
            s.clearHits();
            s.place(s.enemy, 0.5, 6.5, 180);
            s.aimAt(s.owner, s.enemy);
            Location before = s.enemy.getLocation();
            s.assertSucceeded(s.active(), "로켓 펀치");
            assertEquals(s.param("punch_cooldown"), s.ctx().cooldownRemaining(s.owner), 0.06,
                    "the rocket punch has its own short cooldown");
            s.h.tickUntil(() -> s.damageBy(s.owner, s.enemy) > 0, 20, "the fist reaches the enemy");
            assertEquals(s.param("punch_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "rocket punch");
            s.ticks(3);
            assertTrue(s.enemy.getLocation().getZ() > before.getZ() + 0.5, "the punch sends the enemy flying");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);

            s.ticks(s.paramTicks("ult_duration") + 30);
            assertEquals(1.0, scale(s.owner), EPSILON, "back to normal size");
            assertEquals(s.param("armor_knockback") / 100,
                    s.owner.getAttribute(Attribute.KNOCKBACK_RESISTANCE).getValue(), EPSILON, "only the passive left");
            assertNoDisplays(s, "suit and fist");
            s.readyActive();
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "미사일 포드 again");
            assertEquals(s.param("cooldown"), s.ctx().cooldownRemaining(s.owner), 0.06, "the normal cooldown");
            s.ticks(60);
            s.finishClean();
        }
    }

    /**
     * The left rotation a display was given. MockBukkit 4.118 rebuilds {@code getTransformation()} from its matrix
     * and hands back the <em>conjugated</em> rotation as both the left and the right rotation (one quaternion object
     * conjugated in place). These abilities always set an identity right rotation, so a non-identity right rotation
     * means the conjugation happened and is undone here; with a correct implementation the left rotation is used as
     * it is.
     */
    private static Quaternionf rotationOf(Display display) {
        Transformation t = display.getTransformation();
        Quaternionf left = t.getLeftRotation();
        return t.getRightRotation().equals(new Quaternionf(), 1.0E-5f) ? left : left.conjugate();
    }

    /** {@code local} turned by the display's left rotation. */
    private static Vector3f rotated(Display display, Vector3f local) {
        return rotationOf(display).transform(new Vector3f(local));
    }

    /** The world-space center of a block display's box, relative to the display's own position. */
    private static Vector3f boxCenter(BlockDisplay display) {
        Transformation t = display.getTransformation();
        return new Vector3f(t.getTranslation()).add(rotated(display, new Vector3f(t.getScale()).mul(0.5f)));
    }

    private static List<BlockDisplay> blocks(Scenario s, Material material) {
        return entities(s, BlockDisplay.class).stream().filter(d -> d.getBlock().getMaterial() == material).toList();
    }

    private static void assertUnrotated(Entity display, String what) {
        assertEquals(0, display.getLocation().getYaw(), 1.0E-3, what + ": display yaw");
        assertEquals(0, display.getLocation().getPitch(), 1.0E-3, what + ": display pitch");
    }

    @Test
    void mechSuitIsBuiltInTheTransformationAndTurnsWithThePilot() {
        try (Scenario s = Scenario.ffa("mech")) {
            s.place(s.owner, 0.5, 0.5, 90); // facing west (-x)
            s.assertSucceeded(s.ultimate(), "메카 변신");
            s.ticks(25);
            for (BlockDisplay piece : entities(s, BlockDisplay.class)) {
                assertUnrotated(piece, "suit piece " + piece.getBlock().getMaterial());
            }
            float scale = (float) s.param("ult_scale");
            Vector3f core = boxCenter(blocks(s, Material.SHROOMLIGHT).getFirst());
            assertTrue(core.x() < -0.15f * scale && Math.abs(core.z()) < 0.05f,
                    "the chest core sits on the front of a pilot facing west: " + core);
            Vector3f visor = boxCenter(blocks(s, Material.ORANGE_STAINED_GLASS).getFirst());
            assertTrue(visor.x() < -0.2f * scale && Math.abs(visor.z()) < 0.05f, "so does the visor: " + visor);
            List<BlockDisplay> pads = blocks(s, Material.ORANGE_CONCRETE);
            assertEquals(2, pads.size(), "two striped shoulder pads");
            for (BlockDisplay pad : pads) {
                Vector3f center = boxCenter(pad);
                assertTrue(Math.abs(center.z()) > 0.3f * scale && Math.abs(center.x()) < 0.05f,
                        "the shoulders are to the pilot's sides: " + center);
            }

            s.place(s.owner, 0.5, 0.5, 180); // turns to face north (-z)
            s.ticks(2);
            core = boxCenter(blocks(s, Material.SHROOMLIGHT).getFirst());
            assertTrue(core.z() < -0.15f * scale && Math.abs(core.x()) < 0.05f, "the suit turns with the pilot: " + core);
            for (BlockDisplay piece : entities(s, BlockDisplay.class)) {
                assertUnrotated(piece, "suit piece after turning");
            }
            s.ticks(s.paramTicks("ult_duration") + 30);
            s.finishClean();
        }
    }

    @Test
    void mechMissilePodsSitOnTheShouldersOfAPilotFacingAnyWay() {
        try (Scenario s = Scenario.ffa("mech")) {
            s.place(s.owner, 0.5, 0.5, 90);
            s.place(s.enemy, -5.5, 0.5, 270);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "미사일 포드");
            s.ticks(4);
            List<BlockDisplay> casings = blocks(s, Material.IRON_BLOCK);
            assertEquals(2, casings.size(), "two pod casings");
            for (BlockDisplay casing : casings) {
                assertUnrotated(casing, "pod casing");
                Vector3f center = boxCenter(casing);
                Location at = casing.getLocation();
                double side = at.getZ() + center.z() - s.owner.getLocation().getZ();
                double along = at.getX() + center.x() - s.owner.getLocation().getX();
                assertTrue(Math.abs(side) > 0.25 && Math.abs(along) < 0.2,
                        "the pods are over the shoulders of a pilot facing west: side " + side + ", along " + along);
                assertTrue(center.y() > 1.5f, "above the shoulders: " + center.y());
            }
            s.ticks(60);
            assertTrue(s.damageBy(s.owner, s.enemy) > 0, "the missiles still find the enemy to the west");
            s.finishClean();
        }
    }

    @Test
    void mechMissilesAndRocketFistPointAlongTheirFlight() {
        try (Scenario s = Scenario.ffa("mech")) {
            s.place(s.owner, 0.5, 0.5, 90);
            s.place(s.enemy, -12.5, 0.5, 270);
            s.place(s.enemy2, 9.5, 9.5, 0);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "미사일 포드");
            s.ticks(6);
            BlockDisplay missile = blocks(s, Material.LIGHTNING_ROD).getFirst();
            Location before = missile.getLocation();
            s.ticks(1);
            assertUnrotated(missile, "missile");
            Vector moved = missile.getLocation().toVector().subtract(before.toVector()).normalize();
            Vector3f axis = rotated(missile, new Vector3f(0, 1, 0));
            double dot = axis.x() * moved.getX() + axis.y() * moved.getY() + axis.z() * moved.getZ();
            assertTrue(dot > 0.9, "the rod's tip leads its flight (cos=" + dot + ")");
            s.ticks(60);

            s.assertSucceeded(s.ultimate(), "메카 변신");
            s.ticks(15);
            s.aimAt(s.owner, s.enemy);
            Vector aim = s.owner.getEyeLocation().getDirection();
            s.assertSucceeded(s.active(), "로켓 펀치");
            s.ticks(2);
            BlockDisplay fist = blocks(s, Material.PISTON).getFirst();
            assertUnrotated(fist, "rocket fist");
            Vector3f face = rotated(fist, new Vector3f(0, 1, 0));
            double facing = face.x() * aim.getX() + face.y() * aim.getY() + face.z() * aim.getZ();
            assertTrue(facing > 0.98, "the fist flies face first along the aim (cos=" + facing + ")");
            s.ticks(s.paramTicks("ult_duration") + 30);
            s.finishClean();
        }
    }

    @Test
    void mechRocketPunchClangsOffWallsAndComesBack() {
        try (Scenario s = Scenario.ffa("mech")) {
            s.world.wallAlongX(-3, 3, 3, 4, Material.STONE);
            s.assertSucceeded(s.ultimate(), "메카 변신");
            s.ticks(15);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "로켓 펀치 into a wall");
            s.ticks(30);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "the wall stops the fist");
            int suit = entities(s, BlockDisplay.class).size();
            assertTrue(suit <= 12, "the fist is back on the arm: " + suit);
            s.world.wallAlongX(-3, 3, 3, 4, Material.AIR);
            s.finishClean();
        }
    }

    // ================================================================== 천문학자 astronomer

    @Test
    void astronomerStudiesFasterUnderTheNightSky() {
        try (Scenario s = Scenario.ffa("astronomer")) {
            s.world.setTime(6000);
            s.ticks(21);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "유성 by day");
            assertEquals(s.param("cooldown"), s.ctx().cooldownRemaining(s.owner), 0.06, "full cooldown by day");
            s.ticks(40);

            s.world.setTime(18000);
            s.ticks(21);
            s.readyActive();
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "유성 at night");
            assertEquals(s.param("cooldown") * (1 - s.param("night_cooldown") / 100),
                    s.ctx().cooldownRemaining(s.owner), 0.06, "shorter at night");
            s.ticks(40);
            s.world.setTime(6000);
            s.finishClean();
        }
    }

    @Test
    void astronomerMeteorsLandAfterTheWarning() {
        try (Scenario s = Scenario.ffa("astronomer")) {
            s.place(s.enemy2, 9.5, -6.5, 0);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "유성");
            assertFalse(s.active().succeeded(), "on cooldown");
            int first = s.paramTicks("meteor_delay");
            s.ticks(first - 2);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "nothing lands before the warning is over");
            assertFalse(entities(s, BlockDisplay.class).isEmpty(), "the first meteor is streaking in");
            int count = (int) s.param("meteor_count");
            s.ticks(4 + count * s.paramTicks("meteor_interval"));
            List<Scenario.Hit> hits = s.hitsBy(s.owner, s.enemy);
            assertTrue(hits.size() >= 2 && hits.size() <= count, "the meteors pound the marked spot: " + hits);
            for (Scenario.Hit hit : hits) {
                assertEquals(s.param("meteor_damage"), hit.damage(), EPSILON);
            }
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON, "enemy2 is far away");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            assertEquals(hits.size(), entities(s, TextDisplay.class).size(),
                    "one star of the constellation lit per meteor that struck");
            s.ticks(30);
            assertTrue(entities(s, BlockDisplay.class).isEmpty(), "the meteors are gone");
            s.finishClean();
        }
    }

    @Test
    void astronomerMeteorCanBeDodged() {
        try (Scenario s = Scenario.ffa("astronomer")) {
            s.place(s.enemy2, 9.5, -6.5, 0);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "유성");
            s.ticks(5);
            s.place(s.enemy, 8.5, 4.5, 180);
            s.ticks(s.paramTicks("meteor_delay") + 20);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "the enemy left the marked spot in time");
            assertTrue(entities(s, TextDisplay.class).isEmpty(), "no star for a miss");
            s.finishClean();
        }
    }

    @Test
    void astronomerConstellationPaysOutUltimateGauge() {
        try (Scenario s = noGaugeGain(Scenario.builder("astronomer").param("constellation_stars", 2)
                .param("meteor_count", 3)).start()) {
            s.place(s.enemy2, 9.5, -6.5, 0);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "유성");
            s.ticks(s.paramTicks("meteor_delay") + 3 * s.paramTicks("meteor_interval") + 4);
            assertTrue(s.damageBy(s.owner, s.enemy) >= 2 * s.param("meteor_damage"), "at least two meteors struck");
            assertEquals(s.param("constellation_gauge"), gauge(s, s.owner), EPSILON,
                    "the completed constellation grants gauge (once per completion)");
            s.ticks(20);
            assertTrue(entities(s, TextDisplay.class).size() <= 1, "the completed stars drift apart");
            s.finishClean();
        }
    }

    @Test
    void astronomerUltimateMeteorsNeverLightTheConstellation() {
        try (Scenario s = noGaugeGain(Scenario.builder("astronomer").param("constellation_stars", 1)).start()) {
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "대운석");
            s.ticks(s.paramTicks("ult_delay") + 5);
            assertTrue(s.damageBy(s.owner, s.enemy) > 0, "the great meteor struck");
            assertEquals(0, gauge(s, s.owner), EPSILON, "no gauge from the ultimate's own hits");
            s.ticks(80);
            s.finishClean();
        }
    }

    @Test
    void astronomerGreatMeteorWarnsThenCrushesAndTheShockwaveRollsOut() {
        try (Scenario s = Scenario.ffa("astronomer")) {
            s.place(s.enemy2, 0.5, 11.5, 180);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "대운석");
            s.ticks(3);
            assertTrue(entities(s, BlockDisplay.class).size() >= 6, "the meteor hangs in the sky");
            assertEquals(1, entities(s, TextDisplay.class).size(), "a countdown over the target");
            s.ticks(s.paramTicks("ult_delay") - 5);
            assertEquals(0, s.damageTaken(s.enemy) + s.damageTaken(s.enemy2), EPSILON,
                    "nothing before the meteor lands");
            s.ticks(4);
            assertEquals(s.param("ult_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "crushed at the impact");
            assertEquals(s.param("shock_damage"), s.damageBy(s.owner, s.enemy2), EPSILON, "caught by the shockwave");
            assertTrue(s.enemy2.hasPotionEffect(PotionEffectType.SLOWNESS), "the shockwave slows");
            assertFalse(s.enemy.hasPotionEffect(PotionEffectType.SLOWNESS), "the crushed enemy is launched instead");
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "the astronomer 4 blocks away is safe");
            s.ticks(80);
            assertNoDisplays(s, "meteor, rubble and cracks");
            s.finishClean();
        }
    }

    @Test
    void astronomerCometTailTrailsBackUpTheFallPath() {
        try (Scenario s = Scenario.ffa("astronomer")) {
            s.place(s.owner, 0.5, 0.5, 90); // facing west: the meteor comes in from the east, behind the astronomer
            s.place(s.enemy, -5.5, 0.5, 270);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "대운석");
            s.ticks(6);
            for (BlockDisplay piece : entities(s, BlockDisplay.class)) {
                assertUnrotated(piece, "meteor piece");
            }
            BlockDisplay tail = blocks(s, Material.WHITE_STAINED_GLASS).getFirst();
            Vector3f back = rotated(tail, new Vector3f(0, 0, 1));
            assertTrue(back.y() > 0.5f, "the tail points up the sky: " + back);
            assertTrue(back.x() > 0.2f, "towards the east, where the meteor came from: " + back);
            for (TextDisplay text : entities(s, TextDisplay.class)) {
                assertUnrotated(text, "countdown");
            }
            s.ticks(s.paramTicks("ult_delay") + 80);
            s.finishClean();
        }
    }

    @Test
    void astronomerGreatMeteorCanBeOutrun() {
        try (Scenario s = Scenario.ffa("astronomer")) {
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "대운석");
            s.ticks(10);
            s.place(s.enemy, 0.5, 20.5, 180);
            s.place(s.enemy2, 14.5, 4.5, 180);
            s.ticks(s.paramTicks("ult_delay") + 10);
            assertEquals(0, s.damageTaken(s.enemy) + s.damageTaken(s.enemy2), EPSILON, "they ran out of range");
            s.finishClean();
        }
    }

    @Test
    void astronomerNeedsGroundOrAnEnemyToAimAt() {
        try (Scenario s = Scenario.ffa("astronomer")) {
            s.place(s.owner, 0.5, 64, 0.5, 0, -80);
            assertFalse(s.active().succeeded(), "aiming at the open sky");
            assertFalse(s.ultimate().succeeded(), "aiming at the open sky");
            s.finishClean();
        }
    }

    // ================================================================== 해커 hacker

    private static Scenario hackerWithQuickDummies() {
        return Scenario.builder("hacker").configure(h -> h.setParam("dummy", "cooldown", 2)).start();
    }

    @Test
    void hackerBackdoorDelaysTheVictimsNextSkill() {
        try (Scenario s = hackerWithQuickDummies()) {
            s.melee(s.owner, s.enemy, 2);
            s.ticks(4);
            assertEquals(1, entities(s, TextDisplay.class).size(), "the victim's cooldown readout appears");
            s.assertSucceeded(s.match.useActive(s.enemy), "the victim uses its skill");
            int cooldown = s.paramTicks("backdoor_lag");
            s.h.tickUntil(() -> s.ctx().cooldownRemaining(s.enemy) <= 0, 60, "the victim's cooldown runs out");
            s.ticks(3);
            assertTrue(s.ctx().isSilenced(s.enemy), "the backdoor lags the skill as it comes back");
            assertFalse(s.ctx().isSilenced(s.enemy2), "enemy2 has no backdoor");
            s.ticks(cooldown + 2);
            assertFalse(s.ctx().isSilenced(s.enemy), "only for " + s.param("backdoor_lag") + " s");
            s.ticks(20);
            assertTrue(entities(s, TextDisplay.class).isEmpty(), "the readout is gone");

            s.melee(s.owner, s.enemy, 2);
            s.ticks(4);
            assertTrue(entities(s, TextDisplay.class).isEmpty(), "no new backdoor on the same enemy so soon");
            s.ticks(s.paramTicks("backdoor_cooldown"));
            s.melee(s.owner, s.enemy, 2);
            s.ticks(4);
            assertEquals(1, entities(s, TextDisplay.class).size(), "planted again after the cooldown");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.finishClean();
        }
    }

    @Test
    void hackerBackdoorLapsesWhenTheSkillIsNeverUsed() {
        try (Scenario s = hackerWithQuickDummies()) {
            s.melee(s.owner, s.enemy, 2);
            s.ticks(s.paramTicks("backdoor_duration") + 10);
            assertTrue(entities(s, TextDisplay.class).isEmpty(), "the unused backdoor lapsed");
            s.assertSucceeded(s.match.useActive(s.enemy), "the victim uses its skill late");
            s.ticks(60);
            assertFalse(s.ctx().isSilenced(s.enemy), "no lag from a lapsed backdoor");
            s.finishClean();
        }
    }

    @Test
    void hackerBackdoorReadoutNeverRevealsAHiddenEnemy() {
        try (Scenario s = hackerWithQuickDummies()) {
            s.owner.hidePlayer(s.ctx().plugin(), s.enemy);
            s.melee(s.owner, s.enemy, 2);
            s.ticks(6);
            assertTrue(entities(s, TextDisplay.class).isEmpty(), "no readout over a hidden enemy");
            s.owner.showPlayer(s.ctx().plugin(), s.enemy);
            s.ticks(6);
            assertEquals(1, entities(s, TextDisplay.class).size(), "it shows once the enemy is visible again");
            s.finishClean();
        }
    }

    @Test
    void hackerGlitchStuttersTheTargetAroundAndScramblesItsView() {
        try (Scenario s = Scenario.ffa("hacker")) {
            s.place(s.enemy2, 9.5, -6.5, 0);
            s.aimAt(s.owner, s.enemy);
            Location start = s.enemy.getLocation();
            s.assertSucceeded(s.active(), "글리치");
            assertFalse(s.active().succeeded(), "on cooldown");
            assertEquals(s.param("glitch_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "hacked at once");
            assertTrue(s.enemy.hasPotionEffect(PotionEffectType.NAUSEA), "its view swims");
            assertFalse(s.ctx().isSilenced(s.enemy), "no backdoor to exploit yet");
            s.ticks(4);
            assertFalse(entities(s, BlockDisplay.class).isEmpty(), "a torn afterimage where it stood");
            s.ticks(20);
            Location end = s.enemy.getLocation();
            double moved = horizontalDistance(start, end);
            assertTrue(moved > 0.5, "the enemy stuttered away: " + moved);
            assertTrue(moved <= s.param("glitch_distance") + 0.01, "but not far: " + moved);
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON);
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.ticks(20);
            assertTrue(entities(s, BlockDisplay.class).isEmpty(), "the afterimages faded");
            s.finishClean();
        }
    }

    @Test
    void hackerGlitchSetsTheTargetDownExactlyOnTheGround() {
        try (Scenario s = Scenario.ffa("hacker")) {
            // Steps around the enemy: some hops land on the floor, some on top of a block.
            for (int[] step : new int[][] {{2, 3}, {-2, 5}, {1, 7}, {-1, 2}}) {
                s.world.setBlock(step[0], 64, step[1], Material.STONE);
            }
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "글리치");
            Location last = s.enemy.getLocation();
            int hops = 0;
            for (int i = 0; i < 24; i++) {
                s.ticks(1);
                Location at = s.enemy.getLocation();
                if (at.distanceSquared(last) < 1.0E-8) {
                    continue;
                }
                last = at;
                hops++;
                double y = at.getY();
                assertTrue(Math.abs(y - FlatWorld.SURFACE) < 1.0E-6 || Math.abs(y - (FlatWorld.SURFACE + 1)) < 1.0E-6,
                        "the enemy stands exactly on a surface, not in it: y=" + y);
                assertTrue(Spots.isFree(s.world, at, s.enemy.getWidth(), s.enemy.getHeight()),
                        "and its body is clear of blocks at " + at.toVector());
            }
            assertTrue(hops >= 1, "the glitch moved the enemy");
            for (int[] step : new int[][] {{2, 3}, {-2, 5}, {1, 7}, {-1, 2}}) {
                s.world.setBlock(step[0], 64, step[1], Material.AIR);
            }
            s.finishClean();
        }
    }

    @Test
    void hackerReadoutsAndCodeRainAreUnrotatedBillboards() {
        try (Scenario s = Scenario.ffa("hacker")) {
            s.place(s.owner, 0.5, 0.5, 90);
            s.place(s.enemy, -3.5, 0.5, 270);
            s.melee(s.owner, s.enemy, 2);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "시스템 다운");
            s.ticks(5);
            List<TextDisplay> texts = entities(s, TextDisplay.class);
            assertFalse(texts.isEmpty(), "readouts, bars and code rain");
            for (TextDisplay text : texts) {
                assertUnrotated(text, "text display");
                assertEquals(Display.Billboard.CENTER, text.getBillboard(), "faces every viewer");
            }
            s.ticks(120);
            s.finishClean();
        }
    }

    @Test
    void hackerGlitchNeverPutsTheTargetThroughWalls() {
        try (Scenario s = Scenario.ffa("hacker")) {
            // A low pen around the enemy: it can be seen over, but not walked or glitched out of.
            for (int x = -1; x <= 2; x++) {
                s.world.setBlock(x, 64, 3, Material.STONE);
                s.world.setBlock(x, 64, 6, Material.STONE);
            }
            for (int z = 4; z <= 5; z++) {
                s.world.setBlock(-1, 64, z, Material.STONE);
                s.world.setBlock(2, 64, z, Material.STONE);
            }
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "글리치");
            s.ticks(30);
            Location at = s.enemy.getLocation();
            assertTrue(at.getX() > 0 && at.getX() < 2 && at.getZ() > 4 && at.getZ() < 6,
                    "the enemy is still inside the pen: " + at.toVector());
            for (int x = -1; x <= 2; x++) {
                s.world.setBlock(x, 64, 3, Material.AIR);
                s.world.setBlock(x, 64, 6, Material.AIR);
            }
            for (int z = 4; z <= 5; z++) {
                s.world.setBlock(-1, 64, z, Material.AIR);
                s.world.setBlock(2, 64, z, Material.AIR);
            }
            s.finishClean();
        }
    }

    @Test
    void hackerGlitchExploitsAPlantedBackdoor() {
        try (Scenario s = hackerWithQuickDummies()) {
            s.melee(s.owner, s.enemy, 2);
            s.ticks(4);
            s.clearHits();
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "글리치");
            assertTrue(s.ctx().isSilenced(s.enemy), "the backdoor is exploited for an instant silence");
            s.ticks(s.paramTicks("exploit_silence") + 2);
            assertFalse(s.ctx().isSilenced(s.enemy), "for " + s.param("exploit_silence") + " s");
            s.ticks(20);
            assertTrue(entities(s, TextDisplay.class).isEmpty(), "the backdoor is used up");
            s.assertSucceeded(s.match.useActive(s.enemy), "the victim uses its skill");
            s.ticks(60);
            assertFalse(s.ctx().isSilenced(s.enemy), "no lag later: the backdoor was spent");
            s.finishClean();
        }
    }

    @Test
    void hackerSystemDownSilencesDrainsAndHurtsAfterTheUpload() {
        try (Scenario s = noGaugeGain(Scenario.builder("hacker")).start()) {
            s.place(s.enemy2, 5.5, 4.5, 180);
            s.melee(s.owner, s.enemy, 2);
            s.ticks(4);
            s.clearHits();
            s.match.setGauge(s.enemy, 80);
            s.match.setGauge(s.enemy2, 80);
            s.assertSucceeded(s.ultimate(), "시스템 다운");
            s.ticks(3);
            assertTrue(entities(s, TextDisplay.class).size() >= 2 + 8, "upload bars and code rain");
            s.ticks(s.paramTicks("ult_upload") - 5);
            assertEquals(0, s.damageTaken(s.enemy) + s.damageTaken(s.enemy2), EPSILON, "still uploading");
            assertFalse(s.ctx().isSilenced(s.enemy), "still uploading");
            s.ticks(4);
            for (TestPlayer target : s.enemies()) {
                assertEquals(s.param("ult_damage"), s.damageBy(s.owner, target), EPSILON, target.getName());
                assertTrue(s.ctx().isSilenced(target), target.getName() + " is silenced");
                assertTrue(target.hasPotionEffect(PotionEffectType.NAUSEA), target.getName() + " sees noise");
            }
            assertEquals(80 - s.param("exploit_drain"), gauge(s, s.enemy), EPSILON, "the backdoor drains more");
            assertEquals(80 - s.param("ult_drain"), gauge(s, s.enemy2), EPSILON, "the plain drain");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.ticks(s.paramTicks("ult_silence") + 2);
            for (TestPlayer target : s.enemies()) {
                assertFalse(s.ctx().isSilenced(target), target.getName() + " rebooted");
            }
            s.ticks(30);
            assertNoDisplays(s, "bars and rain are gone, and the crash planted no new backdoor");
            s.finishClean();
        }
    }

    @Test
    void hackerSystemDownMissesWhoeverLeavesDuringTheUpload() {
        try (Scenario s = Scenario.ffa("hacker")) {
            s.assertSucceeded(s.ultimate(), "시스템 다운");
            s.ticks(5);
            s.place(s.enemy2, 0.5, 15.5, 180);
            s.ticks(s.paramTicks("ult_upload") + 5);
            assertTrue(s.ctx().isSilenced(s.enemy), "the enemy that stayed crashes");
            assertFalse(s.ctx().isSilenced(s.enemy2), "the one that ran escaped");
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON);
            s.finishClean();
        }
    }

    @Test
    void hackerSystemDownNeedsEnemiesInRange() {
        try (Scenario s = Scenario.builder("hacker").param("ult_radius", 2).start()) {
            assertFalse(s.ultimate().succeeded(), "nobody within 2 blocks");
            s.finishClean();
        }
    }
}
