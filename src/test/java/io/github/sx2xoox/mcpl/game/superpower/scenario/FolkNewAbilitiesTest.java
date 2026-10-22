package io.github.sx2xoox.mcpl.game.superpower.scenario;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import io.github.sx2xoox.mcpl.game.superpower.ability.Ability;
import io.github.sx2xoox.mcpl.game.superpower.ability.ActivationResult;
import io.github.sx2xoox.mcpl.game.superpower.support.Scenario;
import io.github.sx2xoox.mcpl.game.superpower.support.TestPlayer;
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
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** 한국 설화 계열 (new): 도깨비, 구미호, 산신령, 무당. */
class FolkNewAbilitiesTest {

    private static final double EPSILON = 1.0E-6;

    static Stream<String> abilities() {
        return Stream.of("dokkaebi", "gumiho", "sansin", "shaman");
    }

    /** The ultimates the gambler may borrow (the dokkaebi hat lives on its owner's hooks). */
    static Stream<String> standalone() {
        return Stream.of("gumiho", "sansin", "shaman");
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

    /** Displays a passive keeps on purpose: wisps, bumps, tails and the hovering golden axe. */
    private static final Set<Material> PASSIVE_BLOCKS = Set.of(Material.SOUL_FIRE, Material.PINK_TERRACOTTA,
            Material.WHITE_WOOL);

    private static void assertOnlyPassiveDisplays(Scenario s, String what) {
        for (Display display : entities(s, Display.class)) {
            boolean passive = display instanceof BlockDisplay block
                    && PASSIVE_BLOCKS.contains(block.getBlock().getMaterial())
                    || display instanceof ItemDisplay item && item.getItemStack() != null
                    && item.getItemStack().getType() == Material.GOLDEN_AXE;
            assertTrue(passive, what + ": a skill display was left behind: " + display);
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

    private static double attribute(Player player, Attribute attribute) {
        return player.getAttribute(attribute).getValue();
    }

    private static boolean hasModifier(Player player, Attribute attribute) {
        return player.getAttribute(attribute).getModifiers().stream()
                .anyMatch(modifier -> !modifier.getKey().equals(Scenario.TANK));
    }

    /** The left rotation a display was given (undoing MockBukkit's conjugation if the double did not keep it). */
    private static Quaternionf rotationOf(Display display) {
        Transformation t = display.getTransformation();
        Quaternionf left = t.getLeftRotation();
        return t.getRightRotation().equals(new Quaternionf(), 1.0E-5f) ? left : left.conjugate();
    }

    private static Vector3f rotated(Display display, Vector3f local) {
        return rotationOf(display).transform(new Vector3f(local));
    }

    /** The world-space center of a block display's box, relative to the display's own position. */
    private static Vector3f boxCenter(BlockDisplay display) {
        Transformation t = display.getTransformation();
        return new Vector3f(t.getTranslation()).add(rotated(display, new Vector3f(t.getScale()).mul(0.5f)));
    }

    /** The world position of a block display's box center. */
    private static Location worldCenter(BlockDisplay display) {
        Vector3f center = boxCenter(display);
        return display.getLocation().add(center.x(), center.y(), center.z());
    }

    private static void assertUnrotated(Entity display, String what) {
        assertEquals(0, display.getLocation().getYaw(), 1.0E-3, what + ": display yaw");
        assertEquals(0, display.getLocation().getPitch(), 1.0E-3, what + ": display pitch");
    }

    private static void assertAllUnrotated(Scenario s, String what) {
        for (Display display : entities(s, Display.class)) {
            assertUnrotated(display, what + " " + display);
        }
    }

    private static double horizontalDistance(Location a, Location b) {
        return Math.hypot(a.getX() - b.getX(), a.getZ() - b.getZ());
    }

    private static List<Scenario.Hit> magicHitsBy(Scenario s, Player attacker, Player victim) {
        return s.hitsBy(attacker, victim).stream().filter(hit -> hit.cause() == DamageCause.MAGIC).toList();
    }

    // ================================================================== every ability

    @ParameterizedTest(name = "{0}")
    @MethodSource("abilities")
    void wholeMatch(String id) {
        try (Scenario s = Scenario.ffa(id)) {
            s.ticks(60);
            s.assertNoProblems();

            s.layout();
            s.aimAt(s.owner, s.enemy);
            s.clearHits();
            s.assertSucceeded(s.active(), id + " active");
            if (id.equals("shaman")) {
                s.ticks(12);
                assertTrue(hasModifier(s.enemy, Attribute.MOVEMENT_SPEED), "the jangseung curses the enemy");
            }
            s.ticks(100);
            if (!id.equals("shaman")) {
                assertTrue(s.damageBy(s.owner, s.enemy) > 0, id + " active hurts the aimed enemy");
            }
            assertEquals(0, s.damageTaken(s.owner), EPSILON, id + " active never hurts its owner: " + s.hits());

            s.ticks(300);
            s.layout();
            s.healAll();
            s.aimAt(s.owner, s.enemy);
            s.clearHits();
            s.assertSucceeded(s.ultimate(), id + " ultimate");
            if (id.equals("dokkaebi")) {
                s.ticks(10);
                s.melee(s.owner, s.enemy, 3);
            }
            s.ticks(300);
            assertTrue(s.damageBy(s.owner, s.enemy) + s.damageBy(s.owner, s.enemy2) > 0, id + " ultimate hurts");
            assertEquals(0, s.damageTaken(s.owner), EPSILON, id + " ultimate never hurts its owner: " + s.hits());
            s.assertNoProblems();
            assertOnlyPassiveDisplays(s, id);
            assertAllUnrotated(s, id);
            for (TestPlayer enemy : s.enemies()) {
                assertModifierFree(enemy, id);
                assertTrue(enemy.canSee(s.owner), id + ": the owner is visible again");
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
            if (id.equals("dokkaebi")) {
                s.ticks(10);
                s.melee(s.owner, s.enemy, 2);
            }
            AbilityLifecycleTest.watchAlly(s, allyStart, 300);
            assertModifierFree(s.ally(), id);
            assertTrue(s.ally().canSee(s.owner), id + ": an ally always sees the owner");
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
            s.ticks(24);
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
            assertTrue(enemy.canSee(s.owner), id + ": " + enemy.getName() + " sees the dead owner again");
        }
        s.clearHits();
        s.ticks(300);
        for (Scenario.Hit hit : s.hits()) {
            if (s.owner.equals(hit.causer())) {
                fail(id + ": the dead owner still dealt damage: " + hit);
            }
        }
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
    void dokkaebiHatIsNotBorrowable() {
        try (Scenario s = gambler(false)) {
            assertFalse(s.ctx().registry().get("dokkaebi").info().ultimateStandalone(), "the hat needs its hooks");
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
            s.ticks(22);
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

    // ================================================================== 도깨비 dokkaebi

    @Test
    void dokkaebiWispsCircleThenDartAtEnemiesInRange() {
        try (Scenario s = Scenario.builder("dokkaebi").start()) {
            s.place(s.enemy, 0.5, 20.5, 180);
            s.place(s.enemy2, 20.5, 20.5, 180);
            s.ticks(12);
            List<BlockDisplay> flames = blocks(s, Material.SOUL_FIRE);
            assertEquals((int) s.param("wisp_count"), flames.size(), "the wisps circle the dokkaebi");
            for (BlockDisplay flame : flames) {
                assertUnrotated(flame, "wisp");
                double distance = horizontalDistance(flame.getLocation(), s.owner.getLocation());
                assertTrue(distance > 0.6 && distance < 1.3, "a wisp orbits at chest height: " + distance);
            }
            s.ticks(40);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "nobody in range, the wisps keep circling");

            s.place(s.enemy, 0.5, 4.5, 180);
            s.clearHits();
            s.ticks(50);
            List<Scenario.Hit> hits = s.hitsBy(s.owner, s.enemy);
            assertEquals((int) s.param("wisp_count"), hits.size(), "each wisp darts at the enemy once: " + hits);
            for (Scenario.Hit hit : hits) {
                assertEquals(s.param("wisp_damage"), hit.damage(), EPSILON);
            }
            assertEquals(2, blocks(s, Material.PINK_TERRACOTTA).size(), "each wisp raised a bump");
            assertTrue(blocks(s, Material.SOUL_FIRE).isEmpty(), "both wisps are spent");
            s.place(s.enemy, 0.5, 20.5, 180);
            s.ticks(s.paramTicks("wisp_recharge"));
            assertEquals((int) s.param("wisp_count"), blocks(s, Material.SOUL_FIRE).size(),
                    "the wisps rekindle after their recharge");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.finishClean();
        }
    }

    @Test
    void dokkaebiWispsNeverSeekAnEnemyHiddenFromTheGoblin() {
        try (Scenario s = Scenario.builder("dokkaebi").start()) {
            s.place(s.enemy2, 20.5, 20.5, 180);
            s.owner.hidePlayer(s.ctx().plugin(), s.enemy);
            s.ticks(60);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "a stealthed enemy is not given away by the wisps");
            assertEquals((int) s.param("wisp_count"), blocks(s, Material.SOUL_FIRE).size(), "they keep circling");
            s.owner.showPlayer(s.ctx().plugin(), s.enemy);
            s.ticks(40);
            assertTrue(s.damageBy(s.owner, s.enemy) > 0, "once seen, the wisps go for it");
            s.finishClean();
        }
    }

    @Test
    void dokkaebiClubSmashesTheFrontAndRaisesABump() {
        try (Scenario s = Scenario.builder("dokkaebi").param("wisp_count", 0).start()) {
            s.place(s.enemy, 0.5, 3.0, 180);
            s.place(s.enemy2, 0.5, -2.0, 0);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "뚝딱 방망이");
            assertFalse(s.active().succeeded(), "on cooldown");
            double ground = s.enemy.getLocation().getY();
            double highest = ground;
            for (int i = 0; i < 14; i++) {
                s.ticks(1);
                highest = Math.max(highest, s.enemy.getLocation().getY());
            }
            List<Scenario.Hit> hits = s.hitsBy(s.owner, s.enemy);
            assertEquals(1, hits.size(), "one smash: " + hits);
            assertEquals(s.param("club_damage"), hits.getFirst().damage(), EPSILON);
            assertTrue(highest > ground + 0.3, "the smash lifts the enemy a little: " + (highest - ground));
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON, "the enemy behind is safe");
            assertEquals(1, blocks(s, Material.PINK_TERRACOTTA).size(), "a bump on the head");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.ticks(20);
            assertTrue(blocks(s, Material.DARK_OAK_LOG).isEmpty(), "the club is put away");
            s.finishClean();
        }
    }

    @Test
    void dokkaebiClubIsRaisedBehindAndBroughtDownInFront() {
        try (Scenario s = Scenario.builder("dokkaebi").param("wisp_count", 0).start()) {
            s.place(s.owner, 0.5, 0.5, 90); // facing west (-x)
            s.place(s.enemy, -12.5, 0.5, 270);
            s.assertSucceeded(s.active(), "뚝딱 방망이");
            s.ticks(4);
            List<BlockDisplay> heads = blocks(s, Material.DARK_OAK_LOG);
            assertEquals(1, heads.size(), "the club head");
            for (BlockDisplay part : entities(s, BlockDisplay.class)) {
                assertUnrotated(part, "club part");
            }
            Location raised = worldCenter(heads.getFirst());
            assertTrue(raised.getX() > s.owner.getLocation().getX(), "raised behind the dokkaebi: " + raised);
            assertTrue(raised.getY() > s.owner.getLocation().getY() + 1.8, "over the head: " + raised);
            s.ticks(3);
            Location down = worldCenter(heads.getFirst());
            assertTrue(down.getX() < s.owner.getLocation().getX() - 0.8, "brought down in front: " + down);
            assertTrue(down.getY() < s.owner.getLocation().getY() + 1, "slammed down low: " + down);
            s.ticks(20);
            s.finishClean();
        }
    }

    @Test
    void dokkaebiThirdBumpBurstsIntoAStunWithImmunityAfter() {
        try (Scenario s = Scenario.builder("dokkaebi").param("wisp_count", 0).start()) {
            s.place(s.enemy2, 20.5, 20.5, 180);
            double speed = attribute(s.enemy, Attribute.MOVEMENT_SPEED);
            for (int i = 0; i < 2; i++) {
                s.place(s.enemy, 0.5, 3.0, 180);
                s.readyActive();
                s.aimAt(s.owner, s.enemy);
                s.assertSucceeded(s.active(), "smash " + (i + 1));
                s.ticks(10);
            }
            assertEquals(2, blocks(s, Material.PINK_TERRACOTTA).size(), "two bumps stacked");
            s.place(s.enemy, 0.5, 3.0, 180);
            s.readyActive();
            s.aimAt(s.owner, s.enemy);
            s.clearHits();
            s.assertSucceeded(s.active(), "third smash");
            s.h.tickUntil(() -> s.damageBy(s.owner, s.enemy) > 0, 12, "the third smash lands");
            s.ticks(2);
            assertEquals(s.param("club_damage") + s.param("bump_pop_damage"), s.damageBy(s.owner, s.enemy), EPSILON,
                    "the third bump bursts for extra damage");
            assertEquals(0, attribute(s.enemy, Attribute.MOVEMENT_SPEED), EPSILON, "stunned: cannot move");
            assertEquals(0, attribute(s.enemy, Attribute.JUMP_STRENGTH), EPSILON, "stunned: cannot jump");
            assertTrue(entities(s, TextDisplay.class).size() >= 1, "a 뚝딱! sign pops up");
            s.ticks(6);
            assertTrue(blocks(s, Material.PINK_TERRACOTTA).isEmpty(), "the bumps burst");

            // Right after the stun the head is still sore: no new bump.
            s.place(s.enemy, 0.5, 3.0, 180);
            s.readyActive();
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "smash while immune");
            s.ticks(8);
            assertTrue(blocks(s, Material.PINK_TERRACOTTA).isEmpty(), "no bump during the immunity");
            s.ticks(s.paramTicks("bump_stun") + s.paramTicks("bump_immunity") + 5);
            assertEquals(speed, attribute(s.enemy, Attribute.MOVEMENT_SPEED), EPSILON, "the stun wore off");
            assertModifierFree(s.enemy, "after the stun");
            s.finishClean();
        }
    }

    @Test
    void dokkaebiBumpsGoDownAfterAWhile() {
        try (Scenario s = Scenario.builder("dokkaebi").param("wisp_count", 0).param("bump_duration", 1).start()) {
            s.place(s.enemy, 0.5, 3.0, 180);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "뚝딱 방망이");
            s.ticks(10);
            assertEquals(1, blocks(s, Material.PINK_TERRACOTTA).size(), "a bump");
            s.ticks(40);
            assertTrue(blocks(s, Material.PINK_TERRACOTTA).isEmpty(), "the bump went down");
            s.finishClean();
        }
    }

    @Test
    void dokkaebiHatHidesTheGoblinAndPranksWhatItHits() {
        try (Scenario s = Scenario.builder("dokkaebi").start()) {
            s.place(s.enemy, 0.5, 20.5, 180);
            s.place(s.enemy2, 20.5, 20.5, 180);
            s.ticks(12);
            s.assertSucceeded(s.ultimate(), "도깨비 감투");
            assertFalse(s.ultimate().succeeded(), "already wearing the hat");
            assertTrue(s.enemy.canSee(s.owner), "the hat is still falling: a telegraph");
            s.ticks(8);
            assertFalse(s.enemy.canSee(s.owner), "gone from the enemies' eyes");
            assertFalse(s.enemy2.canSee(s.owner));
            assertTrue(s.owner.hasPotionEffect(PotionEffectType.INVISIBILITY), "invisible for everyone else");
            List<BlockDisplay> hat = blocks(s, Material.BLACK_CONCRETE);
            assertFalse(hat.isEmpty(), "the hat sits on the head");
            for (BlockDisplay part : hat) {
                assertFalse(s.enemy.canSee(part), "the hat does not give the dokkaebi away");
                assertFalse(s.owner.canSee(part), "nor block the dokkaebi's own view");
                assertUnrotated(part, "hat");
            }
            for (BlockDisplay flame : blocks(s, Material.SOUL_FIRE)) {
                assertEquals(0, flame.getViewRange(), EPSILON, "the wisps hide too");
            }

            s.place(s.enemy, 0.5, 2.0, 180);
            Location before = s.enemy.getLocation();
            s.enemy.setVelocity(new Vector(3, 0, 3)); // a stale velocity must never be added to
            s.clearHits();
            s.melee(s.owner, s.enemy, 3);
            assertEquals(3 + s.param("prank_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "the prank hits harder");
            assertFalse(s.enemy.canSee(s.owner), "attacking does not break the hat");
            s.ticks(1);
            Vector bounce = s.enemy.getVelocity();
            double expected = s.param("prank_knock") / 9.5;
            assertTrue(Math.hypot(bounce.getX(), bounce.getZ()) <= expected + 0.05,
                    "the bounce replaces the velocity: " + bounce);
            s.ticks(30);
            assertTrue(horizontalDistance(before, s.enemy.getLocation()) > 2.5,
                    "the victim is bounced off somewhere silly: " + s.enemy.getLocation());
            assertEquals(1, blocks(s, Material.PINK_TERRACOTTA).size(), "and gets a bump");

            s.ticks(s.paramTicks("hat_duration") + 20);
            assertTrue(s.enemy.canSee(s.owner), "the hat flew off");
            assertFalse(s.owner.hasPotionEffect(PotionEffectType.INVISIBILITY), "visible again");
            assertTrue(blocks(s, Material.BLACK_CONCRETE).isEmpty(), "the hat is gone");
            s.clearHits();
            s.melee(s.owner, s.enemy, 3);
            assertEquals(3, s.damageBy(s.owner, s.enemy), EPSILON, "no prank without the hat");
            s.finishClean();
        }
    }

    @Test
    void dokkaebiHatSlipsWhenTheGoblinIsHit() {
        try (Scenario s = Scenario.builder("dokkaebi").param("wisp_count", 0).start()) {
            s.assertSucceeded(s.ultimate(), "도깨비 감투");
            s.ticks(8);
            assertFalse(s.enemy.canSee(s.owner), "hidden");
            s.melee(s.enemy, s.owner, 1);
            assertTrue(s.enemy.canSee(s.owner), "a lucky blow knocks the hat askew");
            s.ticks(s.paramTicks("hat_slip") + 2);
            assertFalse(s.enemy.canSee(s.owner), "the hat is pulled down again");
            s.ticks(s.paramTicks("hat_duration"));
            assertTrue(s.enemy.canSee(s.owner));
            s.finishClean();
        }
    }

    // ================================================================== 구미호 gumiho

    @Test
    void gumihoHitsGrowTailsThatSpeedItUp() {
        try (Scenario s = Scenario.builder("gumiho").param("tail_max", 3).start()) {
            double speed = attribute(s.owner, Attribute.MOVEMENT_SPEED);
            for (int i = 0; i < 4; i++) {
                s.melee(s.owner, s.enemy, 1);
                s.ticks(2);
            }
            List<BlockDisplay> tails = blocks(s, Material.WHITE_WOOL);
            assertEquals(3, tails.size(), "tails up to the maximum");
            assertEquals(speed * (1 + 3 * s.param("tail_speed") / 100), attribute(s.owner, Attribute.MOVEMENT_SPEED),
                    EPSILON, "each tail makes the fox faster");
            s.ticks(6);
            for (BlockDisplay tail : tails) {
                assertUnrotated(tail, "tail");
                Vector3f along = rotated(tail, new Vector3f(0, 1, 0));
                assertTrue(along.z() < -0.2f && along.y() > 0.1f, "a tail points up and back: " + along);
            }
            s.ticks(1);
            s.finishClean();
        }
    }

    @Test
    void gumihoTailsTurnWithTheFox() {
        try (Scenario s = Scenario.builder("gumiho").start()) {
            s.place(s.owner, 0.5, 0.5, 90); // facing west (-x)
            s.place(s.enemy, -1.5, 0.5, 270);
            s.melee(s.owner, s.enemy, 1);
            s.ticks(8);
            BlockDisplay tail = blocks(s, Material.WHITE_WOOL).getFirst();
            Vector3f along = rotated(tail, new Vector3f(0, 1, 0));
            assertTrue(along.x() > 0.2f, "behind a fox facing west means east: " + along);
            assertUnrotated(tail, "tail");
            s.finishClean();
        }
    }

    @Test
    void gumihoTailsFallAwayWhenItStopsHitting() {
        try (Scenario s = Scenario.builder("gumiho").param("tail_keep", 1).param("tail_shed", 0.5).start()) {
            double speed = attribute(s.owner, Attribute.MOVEMENT_SPEED);
            for (int i = 0; i < 3; i++) {
                s.melee(s.owner, s.enemy, 1);
                s.ticks(1);
            }
            assertEquals(3, blocks(s, Material.WHITE_WOOL).size());
            s.ticks(15);
            assertEquals(3, blocks(s, Material.WHITE_WOOL).size(), "kept for a while");
            s.ticks(70);
            assertTrue(blocks(s, Material.WHITE_WOOL).isEmpty(), "they fell away one by one");
            assertEquals(speed, attribute(s.owner, Attribute.MOVEMENT_SPEED), EPSILON, "back to normal speed");
            s.finishClean();
        }
    }

    @Test
    void gumihoFoxFireSpendsEveryTailOnHomingFires() {
        try (Scenario s = Scenario.builder("gumiho").start()) {
            s.place(s.enemy2, 30.5, 30.5, 180);
            for (int i = 0; i < 4; i++) {
                s.melee(s.owner, s.enemy, 1);
                s.ticks(1);
            }
            s.clearHits();
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "여우불");
            s.ticks(4);
            for (Display fire : blocks(s, Material.WHITE_STAINED_GLASS)) {
                assertUnrotated(fire, "fox fire");
            }
            s.ticks(60);
            List<Scenario.Hit> hits = magicHitsBy(s, s.owner, s.enemy);
            assertEquals(4, hits.size(), "one fox fire per tail: " + hits);
            for (Scenario.Hit hit : hits) {
                assertEquals(s.param("fire_damage"), hit.damage(), EPSILON);
            }
            assertTrue(blocks(s, Material.WHITE_WOOL).isEmpty(), "the tails were spent");
            assertTrue(blocks(s, Material.WHITE_STAINED_GLASS).isEmpty(), "the fires are gone");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.finishClean();
        }
    }

    @Test
    void gumihoFoxFireSplitsTheMinimumBetweenEnemiesAndHomesAround() {
        try (Scenario s = Scenario.builder("gumiho").start()) {
            s.place(s.enemy2, 0.5, -5.5, 0); // behind the fox
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "여우불 without tails");
            s.ticks(80);
            assertEquals(1, magicHitsBy(s, s.owner, s.enemy).size(), "the aimed enemy gets the first fire");
            assertEquals(1, magicHitsBy(s, s.owner, s.enemy2).size(), "the second curls around to the one behind");
            s.finishClean();
        }
    }

    @Test
    void gumihoFoxFireNeedsAVisibleEnemy() {
        try (Scenario s = Scenario.builder("gumiho").start()) {
            s.place(s.enemy, 0.5, 40.5, 180);
            s.place(s.enemy2, 40.5, 0.5, 180);
            ActivationResult result = s.active();
            assertFalse(result.succeeded(), "nobody to burn");
            assertEquals(0, s.ctx().cooldownRemaining(s.owner), EPSILON, "no cooldown spent");
            s.finishClean();
        }
    }

    @Test
    void gumihoBeadDrainsLifeAndGaugeAndGrowsTails() {
        try (Scenario s = noGaugeGain(Scenario.builder("gumiho")).start()) {
            s.place(s.enemy2, 30.5, 30.5, 180);
            s.match.setGauge(s.enemy, 50);
            s.owner.setHealth(10);
            double speed = attribute(s.enemy, Attribute.MOVEMENT_SPEED);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "여우구슬");
            s.ticks(3);
            assertEquals(1, blocks(s, Material.PINK_STAINED_GLASS).size(), "the bead's glass shell");
            BlockDisplay core = blocks(s, Material.PEARLESCENT_FROGLIGHT).getFirst();
            assertTrue(core.isGlowing(), "the bead's core glows through walls");
            assertAllUnrotated(s, "bead");
            s.h.tickUntil(() -> s.damageBy(s.owner, s.enemy) > 0, 20, "the bead reaches the enemy");
            s.ticks(8);
            assertTrue(attribute(s.enemy, Attribute.MOVEMENT_SPEED) < speed, "the kiss slows the victim");
            s.ticks(80);
            assertEquals(s.param("bead_damage") + s.param("bead_drain"), s.damageBy(s.owner, s.enemy), EPSILON,
                    "the sting and the drain");
            assertEquals(50 - s.param("bead_gauge"), gauge(s, s.enemy), EPSILON, "the gauge was drained off");
            assertEquals(s.param("bead_gauge"), gauge(s, s.owner), EPSILON, "and handed to the fox");
            assertEquals(10 + s.param("bead_drain"), s.owner.getHealth(), EPSILON, "the drunk life heals the fox");
            assertEquals((int) s.param("bead_tails"), blocks(s, Material.WHITE_WOOL).size(), "new tails grew");
            assertTrue(blocks(s, Material.PINK_STAINED_GLASS).isEmpty(), "the bead is gone");
            assertEquals(speed, attribute(s.enemy, Attribute.MOVEMENT_SPEED), EPSILON, "the slow wore off");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.finishClean();
        }
    }

    @Test
    void gumihoFoxFireNeverSeeksAnEnemyHiddenFromTheFox() {
        try (Scenario s = Scenario.builder("gumiho").start()) {
            s.place(s.enemy2, 40.5, 40.5, 180);
            s.owner.hidePlayer(s.ctx().plugin(), s.enemy);
            s.aimAt(s.owner, s.enemy);
            assertFalse(s.active().succeeded(), "the only enemy in range is hidden");
            s.match.setGauge(s.owner, 100);
            assertFalse(s.match.useUltimate(s.owner).succeeded(), "and the bead cannot find it either");
            s.owner.showPlayer(s.ctx().plugin(), s.enemy);
            s.finishClean();
        }
    }

    @Test
    void gumihoBeadComesHomeWhenItsVictimFalls() {
        try (Scenario s = noGaugeGain(Scenario.builder("gumiho")).start()) {
            s.place(s.enemy2, 30.5, 30.5, 180);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "여우구슬");
            s.h.tickUntil(() -> s.damageBy(s.owner, s.enemy) > 0, 20, "the bead latches on");
            s.ticks(12);
            s.session.eliminate(s.enemy, null);
            s.ticks(60);
            assertTrue(blocks(s, Material.PINK_STAINED_GLASS).isEmpty(), "the bead came home");
            assertEquals((int) s.param("bead_tails"), blocks(s, Material.WHITE_WOOL).size(), "and grew its tails");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void gumihoBeadNeedsATarget() {
        try (Scenario s = Scenario.builder("gumiho").start()) {
            s.place(s.enemy, 0.5, 40.5, 180);
            s.place(s.enemy2, 40.5, 0.5, 180);
            s.match.setGauge(s.owner, 100);
            assertFalse(s.match.useUltimate(s.owner).succeeded(), "nobody to kiss");
            assertEquals(100, gauge(s, s.owner), EPSILON, "the gauge is kept");
            s.finishClean();
        }
    }

    // ================================================================== 산신령 sansin

    @Test
    void sansinAxeTumblesInTheFlightPlaneAndGoldHitsHard() {
        try (Scenario s = Scenario.builder("sansin").param("gold_min", 100).param("gold_max", 100)
                .param("honest_chance", 0).start()) {
            s.place(s.enemy, 0.5, 8.5, 180);
            s.place(s.enemy2, 20.5, 20.5, 180);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "금도끼 은도끼");
            assertFalse(s.active().succeeded(), "on cooldown");
            s.ticks(3);
            ItemDisplay axe = items(s, Material.IRON_AXE).getFirst();
            assertUnrotated(axe, "thrown axe");
            Vector3f normal = rotated(axe, new Vector3f(0, 0, 1));
            assertEquals(0, normal.z(), 0.05, "the blade turns in the vertical plane of its flight: " + normal);
            s.h.tickUntil(() -> s.damageBy(s.owner, s.enemy) > 0, 20, "the axe hits");
            assertEquals(s.param("axe_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "the axe itself");
            s.ticks(2);
            assertEquals(1, items(s, Material.GOLDEN_AXE).size(), "the golden axe rises");
            assertEquals(1, items(s, Material.IRON_AXE).size(), "the silver one");
            assertEquals(1, items(s, Material.STONE_AXE).size(), "and the plain one");
            assertEquals(1, entities(s, TextDisplay.class).size(), "\"이 도끼가 네 도끼냐?\"");
            s.ticks(25);
            assertEquals(s.param("axe_damage") + s.param("gold_damage"), s.damageBy(s.owner, s.enemy), EPSILON,
                    "gold: the heavy blow");
            s.ticks(30);
            assertNoDisplays(s, "axes and question");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.finishClean();
        }
    }

    @Test
    void sansinSilverSlowsAndIronShoves() {
        try (Scenario s = Scenario.builder("sansin").param("gold_min", 0).param("gold_max", 0)
                .param("honest_chance", 0).start()) {
            s.place(s.enemy2, 20.5, 20.5, 180);
            double speed = attribute(s.enemy, Attribute.MOVEMENT_SPEED);
            boolean silver = false;
            boolean iron = false;
            for (int i = 0; i < 30 && !(silver && iron); i++) {
                s.place(s.enemy, 0.5, 4.5, 180);
                s.ticks(80); // any slow of the previous throw wears off
                s.place(s.enemy, 0.5, 4.5, 180);
                s.readyActive();
                s.aimAt(s.owner, s.enemy);
                s.clearHits();
                s.assertSucceeded(s.active(), "throw " + i);
                s.ticks(22);
                assertEquals(s.param("axe_damage") + 1, s.damageBy(s.owner, s.enemy), EPSILON,
                        "silver and iron both add 1");
                boolean slowed = attribute(s.enemy, Attribute.MOVEMENT_SPEED) < speed - EPSILON;
                boolean shoved = s.enemy.getLocation().getZ() > 6.0;
                assertTrue(slowed ^ shoved, "silver slows or iron shoves, never both or neither (slowed " + slowed
                        + ", at " + s.enemy.getLocation() + ")");
                silver |= slowed;
                iron |= shoved;
            }
            assertTrue(silver && iron, "both verdicts come up");
            s.ticks(80);
            s.finishClean();
        }
    }

    @Test
    void sansinLongPreciseThrowIsSureToBeGold() {
        try (Scenario s = Scenario.builder("sansin").param("gold_min", 0).param("gold_max", 100)
                .param("honest_chance", 0).start()) {
            s.place(s.enemy, 0.5, 17.5, 180);
            s.place(s.enemy2, 20.5, -20.5, 180);
            s.aimAt(s.owner, s.enemy.getLocation().add(0, s.enemy.getHeight() * 0.5, 0));
            s.assertSucceeded(s.active(), "a long, precise throw");
            s.ticks(60);
            assertEquals(s.param("axe_damage") + s.param("gold_damage"), s.damageBy(s.owner, s.enemy), EPSILON,
                    "far and dead centre: gold every time");
            s.finishClean();
        }
    }

    @Test
    void sansinJudgementFadesWhenItsVictimFalls() {
        try (Scenario s = Scenario.builder("sansin").param("gold_min", 100).param("gold_max", 100)
                .param("honest_chance", 0).start()) {
            s.place(s.enemy2, 20.5, 20.5, 180);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "금도끼 은도끼");
            s.h.tickUntil(() -> s.damageBy(s.owner, s.enemy) > 0, 20, "the axe hits");
            s.ticks(3);
            s.session.eliminate(s.enemy, null);
            s.clearHits();
            s.ticks(40);
            assertTrue(s.hits().isEmpty(), "no verdict lands on a fallen victim: " + s.hits());
            assertNoDisplays(s, "the judgement");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void sansinAxeMissesAndStopsAtWalls() {
        try (Scenario s = Scenario.builder("sansin").start()) {
            s.world.wallAlongX(-3, 3, 3, 4, Material.STONE);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "into a wall");
            s.ticks(40);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "the wall stops the axe");
            assertNoDisplays(s, "the axe");
            s.world.wallAlongX(-3, 3, 3, 4, Material.AIR);
            s.readyActive();
            s.place(s.owner, 0.5, 0.5, 90);
            s.assertSucceeded(s.active(), "into thin air");
            s.ticks(40);
            assertEquals(0, s.damageTaken(s.enemy) + s.damageTaken(s.enemy2), EPSILON, "a miss");
            assertNoDisplays(s, "the missed axe");
            s.finishClean();
        }
    }

    @Test
    void sansinHonestyRewardsWithAbsorptionAndAGoldenPromise() {
        try (Scenario s = Scenario.builder("sansin").param("honest_chance", 100).param("gold_min", 0)
                .param("gold_max", 0).start()) {
            s.place(s.enemy2, 20.5, 20.5, 180);
            s.melee(s.enemy, s.owner, 1);
            assertEquals(s.param("honest_absorb"), s.owner.getAbsorptionAmount(), EPSILON, "a golden blessing");
            assertTrue(hasModifier(s.owner, Attribute.MAX_ABSORPTION), "room for it");
            s.ticks(6);
            List<ItemDisplay> halo = items(s, Material.GOLDEN_AXE);
            assertEquals(1, halo.size(), "a little golden axe hovers over the shoulder");
            assertUnrotated(halo.getFirst(), "halo axe");
            s.melee(s.enemy, s.owner, 1);
            assertEquals(s.param("honest_absorb") - 1, s.owner.getAbsorptionAmount(), EPSILON,
                    "no second blessing within the gap; the shield soaked the blow");

            s.aimAt(s.owner, s.enemy);
            s.clearHits();
            s.assertSucceeded(s.active(), "the promised throw");
            s.ticks(40);
            assertEquals(s.param("axe_damage") + s.param("gold_damage"), s.damageBy(s.owner, s.enemy), EPSILON,
                    "the honest one gets the golden axe even without luck");
            assertTrue(items(s, Material.GOLDEN_AXE).isEmpty(), "the promise was kept");

            s.ticks(s.paramTicks("honest_duration") + 5);
            assertFalse(hasModifier(s.owner, Attribute.MAX_ABSORPTION), "the blessing wore off");
            s.finishClean();
        }
    }

    @Test
    void sansinTigerPouncesOnTheLaneAfterTheTelegraph() {
        try (Scenario s = Scenario.builder("sansin").param("honest_chance", 0).start()) {
            s.place(s.enemy, 0.5, 7.5, 180);
            s.place(s.enemy2, 6.5, 7.5, 180);
            double speed = attribute(s.enemy, Attribute.MOVEMENT_SPEED);
            s.assertSucceeded(s.ultimate(), "산신령 강림");
            s.ticks(s.paramTicks("tiger_delay") - 2);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "nothing before the tiger is let loose");
            assertFalse(blocks(s, Material.ORANGE_CONCRETE).isEmpty(), "the tiger grows out of the mist");
            assertFalse(blocks(s, Material.LIGHT_GRAY_CONCRETE).isEmpty(), "the spirit on its cloud");
            assertAllUnrotated(s, "spirit and tiger");
            s.h.tickUntil(() -> s.damageBy(s.owner, s.enemy) > 0, 30, "the tiger pounces");
            assertEquals(s.param("tiger_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "the pounce");
            assertEquals(0, attribute(s.enemy, Attribute.MOVEMENT_SPEED), EPSILON, "pinned down");
            assertAllUnrotated(s, "running tiger");
            for (BlockDisplay part : blocks(s, Material.ORANGE_CONCRETE)) {
                assertEquals(64, part.getLocation().getY(), 1.0E-6, "the tiger runs on the ground");
            }
            s.ticks(40);
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON, "the enemy off the lane is safe");
            assertEquals(speed, attribute(s.enemy, Attribute.MOVEMENT_SPEED), EPSILON, "the pin is short");
            s.ticks(60);
            assertTrue(blocks(s, Material.ORANGE_CONCRETE).isEmpty(), "the tiger dissolved into mist");
            assertTrue(entities(s, BlockDisplay.class).isEmpty(), "and the spirit left");
            assertEquals(1, items(s, Material.GOLDEN_AXE).size(), "the spirit promised a golden axe");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.finishClean();
        }
    }

    @Test
    void sansinTigerFacesItsRunAndSteersWhereTheSansinLooks() {
        try (Scenario s = Scenario.builder("sansin").param("honest_chance", 0).start()) {
            s.place(s.owner, 0.5, 0.5, 90); // facing west (-x)
            s.place(s.enemy, 12.5, 12.5, 180);
            s.place(s.enemy2, 12.5, -12.5, 180);
            s.assertSucceeded(s.ultimate(), "산신령 강림");
            s.ticks(s.paramTicks("tiger_delay") + 2);
            BlockDisplay snout = blocks(s, Material.WHITE_CONCRETE).getFirst();
            // Every part of the tiger stands on the tiger's own position; the snout sits in front of it.
            Location tiger = snout.getLocation();
            assertTrue(worldCenter(snout).getX() < tiger.getX() - 0.5, "the tiger runs face first to the west");
            for (BlockDisplay part : blocks(s, Material.ORANGE_CONCRETE)) {
                assertEquals(0, part.getLocation().distance(tiger), 1.0E-6, "the tiger's parts move together");
            }
            s.place(s.owner, 0.5, 0.5, 180); // now looking north (-z)
            s.ticks(16);
            Location at = blocks(s, Material.ORANGE_CONCRETE).getFirst().getLocation();
            assertTrue(at.getZ() < -1.5, "the tiger turned north after the sansin's gaze: " + at);
            s.ticks(60);
            s.finishClean();
        }
    }

    @Test
    void sansinTigerStopsAtAWall() {
        try (Scenario s = Scenario.builder("sansin").param("honest_chance", 0).start()) {
            s.world.wallAlongX(-4, 4, 5, 3, Material.STONE);
            s.place(s.enemy, 0.5, 8.5, 180);
            s.assertSucceeded(s.ultimate(), "산신령 강림");
            s.ticks(80);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "the wall shields the enemy behind it");
            s.ticks(40);
            assertTrue(entities(s, BlockDisplay.class).isEmpty(), "everything faded");
            s.world.wallAlongX(-4, 4, 5, 3, Material.AIR);
            s.finishClean();
        }
    }

    // ================================================================== 무당 shaman

    @Test
    void shamanBellShakesOneAfflictionOffItselfAndEachAlly() {
        try (Scenario s = Scenario.builder("shaman").teams().param("bell_period", 1).start()) {
            s.owner.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 2000, 0));
            s.owner.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 2000, 0));
            s.ally().addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 2000, 0));
            s.enemy.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 2000, 0));
            s.h.tickUntil(() -> !s.owner.hasPotionEffect(PotionEffectType.SLOWNESS), 30, "the bell rings");
            assertTrue(s.owner.hasPotionEffect(PotionEffectType.POISON), "one affliction per ring, the worst first");
            assertFalse(s.ally().hasPotionEffect(PotionEffectType.BLINDNESS), "the ally is cleansed too");
            assertTrue(s.enemy.hasPotionEffect(PotionEffectType.POISON), "never the enemy");
            s.ticks(22);
            assertFalse(s.owner.hasPotionEffect(PotionEffectType.POISON), "the next ring takes the poison");
            s.owner.setFireTicks(200);
            s.ticks(22);
            assertEquals(0, s.owner.getFireTicks(), "with nothing else to cleanse, the bell puts out the fire");
            s.finishClean();
        }
    }

    @Test
    void shamanJangseungCursesEnemiesAndMendsAllies() {
        try (Scenario s = Scenario.builder("shaman").teams().start()) {
            s.crossfireAlly();
            double max = s.maxHealth(s.ally());
            s.ally().setHealth(max - 6);
            double attack = attribute(s.enemy, Attribute.ATTACK_DAMAGE);
            double speed = attribute(s.enemy, Attribute.MOVEMENT_SPEED);
            s.aimAtFloor(s.owner, 0.5, 4.5);
            s.assertSucceeded(s.active(), "장승");
            s.ticks(9);
            List<BlockDisplay> posts = blocks(s, Material.STRIPPED_SPRUCE_LOG);
            assertEquals(1, posts.size(), "a jangseung rises");
            assertTrue(horizontalDistance(posts.getFirst().getLocation(), new Location(s.world, 0.5, 64, 4.5)) < 0.6,
                    "where the shaman aimed: " + posts.getFirst().getLocation());
            assertEquals(64, posts.getFirst().getLocation().getY(), 1.0E-6, "standing on the floor");
            assertTrue(entities(s, TextDisplay.class).stream()
                    .anyMatch(text -> Scenario.plain(text.text()).contains("천")), "천하대장군");
            assertAllUnrotated(s, "jangseung");
            assertEquals(attack * (1 - s.param("totem_weaken") / 100), attribute(s.enemy, Attribute.ATTACK_DAMAGE),
                    EPSILON, "weaker");
            assertEquals(speed * (1 - s.param("totem_slow") / 100), attribute(s.enemy, Attribute.MOVEMENT_SPEED),
                    EPSILON, "slower (never stacking)");
            assertModifierFree(s.ally(), "the ally is never cursed");
            s.ticks(25);
            assertTrue(s.ally().getHealth() >= max - 6 + s.param("totem_heal") - EPSILON, "the ally is mended");

            s.place(s.enemy, 0.5, 20.5, 180);
            s.place(s.enemy2, 20.5, 20.5, 180);
            s.ticks(20);
            assertModifierFree(s.enemy, "the curse ends outside the reach");
            s.ticks(s.paramTicks("totem_duration") + 10);
            assertTrue(blocks(s, Material.STRIPPED_SPRUCE_LOG).isEmpty(), "it sinks back into the ground");
            s.finishClean();
        }
    }

    @Test
    void shamanKeepsAtMostTwoJangseungFacingTheWayItLooked() {
        try (Scenario s = Scenario.builder("shaman").start()) {
            s.place(s.owner, 0.5, 0.5, 90); // facing west (-x)
            for (int i = 0; i < 3; i++) {
                s.readyActive();
                s.aimAtFloor(s.owner, -2.5 - i * 2, 0.5);
                s.assertSucceeded(s.active(), "장승 " + (i + 1));
                s.ticks(3);
            }
            s.ticks(15);
            List<BlockDisplay> posts = blocks(s, Material.STRIPPED_SPRUCE_LOG);
            assertEquals((int) s.param("totem_max"), posts.size(), "the oldest one sank");
            for (BlockDisplay post : posts) {
                BlockDisplay eye = blocks(s, Material.BLACK_CONCRETE).stream()
                        .filter(e -> e.getLocation().distance(post.getLocation()) < 0.1).findFirst().orElse(null);
                assertNotNull(eye, "the post has eyes");
                assertTrue(worldCenter(eye).getX() < worldCenter(post).getX() - 0.15,
                        "the face looks west, the way the shaman faced");
            }
            s.ticks(s.paramTicks("totem_duration") + 20);
            s.finishClean();
        }
    }

    @Test
    void shamanGutMakesEnemiesInsideTakeMoreFromEverything() {
        try (Scenario s = Scenario.builder("shaman").param("gut_damage", 0).param("gut_finale", 0).start()) {
            s.place(s.enemy, 0.5, 3.5, 180);
            s.place(s.enemy2, 0.5, -3.5, 0);
            s.assertSucceeded(s.ultimate(), "굿판");
            s.ticks(18);
            assertEquals(5, blocks(s, Material.DARK_OAK_LOG).size(), "five flag poles");
            for (Material cloth : List.of(Material.BLUE_WOOL, Material.RED_WOOL, Material.YELLOW_WOOL,
                    Material.WHITE_WOOL, Material.BLACK_WOOL)) {
                assertEquals(1, blocks(s, cloth).size(), "a " + cloth + " flag");
            }
            BlockDisplay blue = blocks(s, Material.BLUE_WOOL).getFirst();
            assertTrue(blue.getLocation().getX() > 5, "the blue flag flies in the east");
            assertAllUnrotated(s, "gut");
            s.clearHits();
            s.melee(s.enemy2, s.enemy, 4);
            s.ticks(2);
            assertEquals(4 * s.param("gut_vulnerable") / 100, s.damageBy(s.owner, s.enemy), EPSILON,
                    "a blow from anyone hurts a share more");
            s.place(s.enemy, 0.5, 15.5, 180);
            s.ticks(2);
            s.clearHits();
            s.melee(s.enemy2, s.enemy, 4);
            s.ticks(2);
            assertEquals(0, s.damageBy(s.owner, s.enemy), EPSILON, "outside the ring nothing more");
            s.ticks(s.paramTicks("gut_duration") + 20);
            assertNoDisplays(s, "the gut");
            s.finishClean();
        }
    }

    @Test
    void shamanGutCutsEverySecondAndShieldsTheShamansSide() {
        try (Scenario s = Scenario.builder("shaman").teams().start()) {
            s.place(s.enemy, 0.5, 3.5, 180);
            s.place(s.enemy2, 20.5, 20.5, 180);
            double max = s.maxHealth(s.ally());
            s.ally().setHealth(max - 6);
            s.assertSucceeded(s.ultimate(), "굿판");
            s.ticks(40);
            assertTrue(s.ally().hasPotionEffect(PotionEffectType.RESISTANCE), "the ally is shielded");
            assertTrue(s.owner.hasPotionEffect(PotionEffectType.RESISTANCE), "and so is the shaman");
            assertTrue(s.ally().getHealth() > max - 6, "and healed");
            s.ticks(s.paramTicks("gut_duration") + 20);
            double vulnerable = 1 + s.param("gut_vulnerable") / 100;
            long cuts = (s.paramTicks("gut_duration") + 19) / 20;
            double expected = cuts * s.param("gut_damage") * vulnerable + s.param("gut_finale") * vulnerable;
            assertEquals(expected, s.damageBy(s.owner, s.enemy), 1.0E-3,
                    "every cut and the finale, each a share more");
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON, "outside the ring");
            assertEquals(0, s.damageTaken(s.ally()), EPSILON);
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.finishClean();
        }
    }
}
