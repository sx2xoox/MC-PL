package io.github.sx2xoox.mcpl.game.superpower.scenario;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import io.github.sx2xoox.mcpl.game.superpower.abilities.BatterAbility;
import io.github.sx2xoox.mcpl.game.superpower.abilities.ClownAbility;
import io.github.sx2xoox.mcpl.game.superpower.abilities.MagicianAbility;
import io.github.sx2xoox.mcpl.game.superpower.abilities.SoccerAbility;
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
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 무대 · 스포츠 계열: 마술사, 광대, 축구선수, 야구선수. Each ability through a whole match, the owner dying in the
 * middle of each skill, team mode with an ally in the line of fire, the Gambler borrowing the standalone ultimates, and
 * the mechanics of every passive, active and ultimate.
 */
class ShowNewAbilitiesTest {

    private static final double EPSILON = 1.0E-6;

    /** Which skills of an ability hurt the aimed enemy in the default layout. */
    private record Expect(boolean activeHurts, boolean ultimateHurts) {
    }

    private static final Map<String, Expect> EXPECT = Map.of(
            "magician", new Expect(true, true),
            "clown", new Expect(true, true),
            "soccer", new Expect(true, true),
            "batter", new Expect(true, true));

    static Stream<String> abilities() {
        return Stream.of("magician", "clown", "soccer", "batter");
    }

    static Stream<String> standalone() {
        return Stream.of("clown", "soccer", "batter");
    }

    private static long displays(Scenario s) {
        return s.world.getEntities().stream().filter(Display.class::isInstance).count();
    }

    private static long blockDisplays(Scenario s) {
        return s.world.getEntities().stream().filter(BlockDisplay.class::isInstance).count();
    }

    private static long textDisplays(Scenario s) {
        return s.world.getEntities().stream().filter(TextDisplay.class::isInstance).count();
    }

    private static void assertNoDisplays(Scenario s) {
        assertEquals(0, displays(s), "displays left behind: " + s.world.getEntities());
    }

    private static void assertModifierFree(Player player, String id) {
        for (Attribute attribute : Registry.ATTRIBUTE) {
            AttributeInstance instance = player.getAttribute(attribute);
            if (instance == null) {
                continue;
            }
            for (AttributeModifier modifier : instance.getModifiers()) {
                if (!modifier.getKey().equals(Scenario.TANK)) {
                    fail(id + ": " + player.getName() + " kept " + modifier.getKey() + " on " + attribute.getKey());
                }
            }
        }
    }

    private static double speedBonus(Player player) {
        double total = 0;
        for (AttributeModifier modifier : player.getAttribute(Attribute.MOVEMENT_SPEED).getModifiers()) {
            total += modifier.getAmount();
        }
        return total;
    }

    /** Feet exactly on the floor of the flat world and the body free of blocks (not sunk into the ground). */
    private static void assertStandsOnSurface(Scenario s, Player player, String what) {
        assertEquals(FlatWorld.SURFACE, player.getLocation().getY(), 1.0E-6, what + ": feet on the surface");
        BoundingBox box = player.getBoundingBox();
        BoundingBox body = new BoundingBox(box.getMinX() + 0.01, box.getMinY() + 1.0E-4, box.getMinZ() + 0.01,
                box.getMaxX() - 0.01, box.getMaxY(), box.getMaxZ() - 0.01);
        assertFalse(s.world.hasCollisionsIn(body), what + ": inside a block at " + player.getLocation());
    }

    /** Ticks until {@code player} has stood on the ground for a few ticks. */
    private static void settle(Scenario s, Player player) {
        Entity body = player; // the entity flag (Player#isOnGround is deprecated: the client reports it)
        s.h.tickUntil(() -> body.isOnGround() && player.getVelocity().getY() <= 0, 120,
                player.getName() + " landing");
        s.ticks(3);
    }

    private static List<Display> displaysOf(Scenario s, Material material) {
        return s.world.getEntities().stream().filter(BlockDisplay.class::isInstance).map(BlockDisplay.class::cast)
                .filter(display -> display.getBlock().getMaterial() == material).map(Display.class::cast).toList();
    }

    /**
     * Where the display's model turns {@code (x, y, z)}: its transformation's rotation only.
     *
     * <p>MockBukkit's {@code DisplayMock#getTransformation} conjugates the decomposed rotation in place, so it
     * reports the <em>inverse</em> left rotation (and the same quaternion as the right rotation). Every display of
     * these abilities has no right rotation, so a non-identity right rotation reveals that and is undone here; with a
     * fixed MockBukkit (identity right rotation) the left rotation is used as it is.
     */
    private static Vector axis(Display display, float x, float y, float z) {
        Transformation transformation = display.getTransformation();
        Quaternionf left = transformation.getLeftRotation();
        Quaternionf right = transformation.getRightRotation();
        if (Math.abs(Math.abs(right.w()) - 1) > 1.0E-4) {
            left.conjugate();
        }
        Vector3f turned = left.transform(new Vector3f(x, y, z));
        return new Vector(turned.x, turned.y, turned.z);
    }

    /** The displays orient through their transformation only: their own rotation stays 0. */
    private static void assertUnrotated(Scenario s, String what) {
        for (Entity entity : s.world.getEntities()) {
            if (entity instanceof Display display) {
                assertEquals(0, display.getLocation().getYaw(), EPSILON, what + ": " + display.getType() + " yaw");
                assertEquals(0, display.getLocation().getPitch(), EPSILON, what + ": " + display.getType()
                        + " pitch");
            }
        }
    }

    private static double horizontalDistance(Location a, Location b) {
        return Math.hypot(a.getX() - b.getX(), a.getZ() - b.getZ());
    }

    /** Exactly what {@code GamblerAbility#fireBorrowed} does. */
    private static ActivationResult borrow(Scenario s, String id) {
        Ability borrowed = s.ctx().registry().create(id);
        borrowed.bind(s.owner, s.ctx(), s.ctx().paramsFor(id));
        return borrowed.onUltimate();
    }

    /** A gambler owner with no luck of its own, so the borrowed ultimate alone decides what happens. */
    private static Scenario gambler() {
        return Scenario.builder("gambler").param("dodge_chance", 0).param("crit_chance", 0).start();
    }

    // ------------------------------------------------------------------ every ability of the group

    @Test
    void onlyTheMagicianKeepsItsUltimateToItself() {
        assertFalse(MagicianAbility.INFO.ultimateStandalone(), "the vanishing needs onRevoke and the attack hooks");
        assertTrue(ClownAbility.INFO.ultimateStandalone());
        assertTrue(SoccerAbility.INFO.ultimateStandalone());
        assertTrue(BatterAbility.INFO.ultimateStandalone());
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
            s.ticks(100);
            if (EXPECT.get(id).activeHurts()) {
                assertTrue(s.damageTaken(s.enemy) > 0, id + " active hurts the aimed enemy");
            }
            assertEquals(0, s.damageTaken(s.owner), EPSILON, id + " active never hurts its owner: " + s.hits());
            assertNoDisplays(s);

            s.layout();
            s.aimAt(s.owner, s.enemy);
            s.healAll();
            s.clearHits();
            s.assertSucceeded(s.ultimate(), id + " ultimate");
            s.ticks(300);
            if (EXPECT.get(id).ultimateHurts()) {
                assertTrue(s.damageTaken(s.enemy) > 0, id + " ultimate hurts the aimed enemy");
            }
            assertEquals(0, s.damageTaken(s.owner), EPSILON, id + " ultimate never hurts its owner: " + s.hits());
            assertNoDisplays(s);
            assertEquals(0, s.h.hub.tempBlocks().size(), id + ": temporary blocks restored");
            assertModifierFree(s.owner, id);
            for (TestPlayer enemy : s.enemies()) {
                assertModifierFree(enemy, id);
                assertTrue(enemy.canSee(s.owner), id + ": " + enemy.getName() + " sees the owner again");
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
            s.ticks(3);
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
        assertEquals(0, s.h.hub.tempBlocks().size(), id);
        for (TestPlayer enemy : s.enemies()) {
            assertModifierFree(enemy, id);
            assertFalse(s.ctx().isSilenced(enemy), id);
            assertTrue(enemy.canSee(s.owner), id + ": " + enemy.getName() + " sees the dead owner again");
        }
        assertModifierFree(s.owner, id);
        s.assertNoProblems();
        s.finishMatch();
        s.assertNoProblems();
        s.assertCleanWorld();
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
            AbilityLifecycleTest.watchAlly(s, allyStart, 200);
            assertTrue(s.ally().canSee(s.owner), id + ": the ally always sees the owner");
            assertModifierFree(s.ally(), id);
            s.assertNoProblems();

            s.finishMatch();
            s.assertNoProblems();
            s.assertCleanWorld();
        }
    }

    // ------------------------------------------------------------------ the gambler borrows

    @ParameterizedTest(name = "{0}")
    @MethodSource("standalone")
    void borrowedUltimateWorksOnAGambler(String id) {
        try (Scenario s = gambler()) {
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(borrow(s, id), "borrowed " + id);
            s.ticks(300);
            assertTrue(s.damageBy(s.owner, s.enemy) > 0, "the borrowed " + id + " hurts the enemy: " + s.hits());
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "the borrowed " + id + " never hurts the gambler");
            assertModifierFree(s.owner, id);
            for (TestPlayer target : s.enemies()) {
                assertModifierFree(target, id);
            }
            assertNoDisplays(s);
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("standalone")
    void borrowedUltimateNeverHarmsTheGamblersAlly(String id) {
        try (Scenario s = Scenario.builder("gambler").teams().param("dodge_chance", 0).param("crit_chance", 0)
                .start()) {
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
        try (Scenario s = gambler()) {
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
            assertModifierFree(s.owner, id);
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void aBorrowedCircusCannonStillForgivesTheGamblersFall() {
        try (Scenario s = gambler()) {
            s.place(s.enemy, 0.5, 14.5, 180);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(borrow(s, "clown"), "borrowed 서커스 대포");
            s.h.tickUntil(() -> s.damageBy(s.owner, s.enemy) > 0, 100, "the landing firework");
            assertTrue(horizontalDistance(s.owner.getLocation(), new Location(s.world, 0.5, 64, 14.5)) < 2,
                    "landed on the mark: " + s.owner.getLocation());
            s.ticks(60);
            assertTrue(s.hitsOf(s.owner, DamageCause.FALL).isEmpty(), "no fall damage: " + s.hits());
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            assertNoDisplays(s);
            s.finishClean();
        }
    }

    // ------------------------------------------------------------------ 마술사 magician

    @Test
    void magicianSidestepsAnEnemyHitThenRecharges() {
        try (Scenario s = Scenario.builder("magician").param("dodge_chance", 100).start()) {
            s.place(s.enemy, 0.5, 2.0, 180);
            Location before = s.owner.getLocation();
            s.melee(s.enemy, s.owner, 4);
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "the hit found only cards");
            assertTrue(displays(s) > 0, "a shower of cards");
            s.ticks(2);
            assertEquals(s.param("dodge_distance"), horizontalDistance(before, s.owner.getLocation()), 0.05,
                    "a step to the side");
            assertTrue(Math.abs(s.owner.getLocation().getZ() - before.getZ()) < 0.05, "sideways, not back");
            assertStandsOnSurface(s, s.owner, "the sidestep");

            s.melee(s.enemy, s.owner, 4);
            assertEquals(4, s.damageTaken(s.owner), EPSILON, "no second dodge while recharging");
            s.ticks(s.paramTicks("dodge_cooldown") + 1);
            s.melee(s.enemy, s.owner, 4);
            assertEquals(4, s.damageTaken(s.owner), EPSILON, "recharged: dodged again");
            s.ticks(30);
            assertNoDisplays(s);
            s.finishClean();
        }
    }

    @Test
    void magicianNeverDodgesFallsAndNeverWithoutLuck() {
        try (Scenario s = Scenario.builder("magician").param("dodge_chance", 100).start()) {
            s.owner.damage(3, DamageSource.builder(DamageType.FALL).build());
            assertEquals(3, s.damageTaken(s.owner), EPSILON, "a fall is no attack to dodge");
            s.finishClean();
        }
        try (Scenario s = Scenario.builder("magician").param("dodge_chance", 0).start()) {
            s.place(s.enemy, 0.5, 2.0, 180);
            Location before = s.owner.getLocation();
            for (int i = 0; i < 5; i++) {
                s.melee(s.enemy, s.owner, 2);
                s.ticks(2);
            }
            assertEquals(10, s.damageTaken(s.owner), EPSILON, "no luck, no dodge");
            assertTrue(horizontalDistance(before, s.owner.getLocation()) < 0.01);
            s.finishClean();
        }
    }

    @Test
    void magicianCardsLeaveMarksAndTheThirdCompletesTheTrick() {
        try (Scenario s = Scenario.ffa("magician")) {
            s.place(s.enemy, 0.5, 6.5, 180);
            s.place(s.enemy2, 2.3, 6.5, 180); // beside the target, out of the cards' way
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "카드 던지기");
            s.ticks(4);
            assertTrue(displays(s) > 0, "the cards fly");
            s.ticks(30);
            double cards = s.param("cards") * s.param("card_damage");
            assertEquals(cards + s.param("trick_damage"), s.damageBy(s.owner, s.enemy), EPSILON,
                    "every card and the trick: " + s.hits());
            assertEquals(s.param("trick_damage"), s.damageBy(s.owner, s.enemy2), EPSILON,
                    "the trick catches the enemy beside");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            assertFalse(s.active().succeeded(), "on cooldown");
            s.ticks(40);
            assertNoDisplays(s);
            s.finishClean();
        }
    }

    @Test
    void magicianMarksAddUpAcrossVolleysButExpire() {
        try (Scenario s = Scenario.builder("magician").param("cards", 2).start()) {
            double card = s.param("card_damage");
            s.place(s.enemy, 0.5, 6.5, 180);
            s.place(s.enemy2, 12.5, 0.5, 270);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "카드 던지기");
            s.ticks(20);
            assertEquals(2 * card, s.damageBy(s.owner, s.enemy), EPSILON, "two marks, no trick yet");
            assertEquals(1, textDisplays(s), "the suits float over the marked head");
            s.readyActive();
            s.assertSucceeded(s.active(), "카드 던지기");
            s.ticks(20);
            assertEquals(4 * card + s.param("trick_damage"), s.damageBy(s.owner, s.enemy), EPSILON,
                    "the third mark completes the trick");
            // The second card of that volley started a fresh mark; let it fade first.
            s.ticks(s.paramTicks("mark_duration") + 2);
            assertEquals(0, textDisplays(s), "the leftover mark faded");

            s.clearHits();
            s.readyActive();
            s.assertSucceeded(s.active(), "카드 던지기");
            s.ticks(20);
            s.ticks(s.paramTicks("mark_duration") + 2);
            assertEquals(0, textDisplays(s), "the marks faded");
            s.readyActive();
            s.assertSucceeded(s.active(), "카드 던지기");
            s.ticks(20);
            assertEquals(4 * card, s.damageBy(s.owner, s.enemy), EPSILON, "expired marks never complete a trick");
            s.ticks(s.paramTicks("mark_duration") + 2);
            assertNoDisplays(s);
            s.finishClean();
        }
    }

    @Test
    void magicianCardsStickInWalls() {
        try (Scenario s = Scenario.ffa("magician")) {
            s.place(s.enemy, 0.5, 8.5, 180);
            s.aimAt(s.owner, s.enemy);
            s.world.wallAlongX(-3, 3, 4, 3, Material.STONE);
            s.assertSucceeded(s.active(), "카드 던지기");
            s.ticks(10);
            assertTrue(displays(s) > 0, "the cards stick in the wall for a moment");
            s.ticks(30);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "the wall took the cards");
            assertNoDisplays(s);
            s.world.wallAlongX(-3, 3, 4, 3, Material.AIR);
            s.finishClean();
        }
    }

    @Test
    void magicianVanishingActHoldsTheEnemyThenDropsItElsewhereAndHidesTheMagician() {
        try (Scenario s = Scenario.ffa("magician")) {
            s.place(s.enemy, 0.5, 6.5, 180);
            s.place(s.enemy2, -6.5, 0.5, 90);
            s.aimAt(s.owner, s.enemy);
            Location stage = s.enemy.getLocation();
            s.assertSucceeded(s.ultimate(), "사라지는 마술");
            s.ticks(5);
            assertEquals(0, s.enemy.getAttribute(Attribute.MOVEMENT_SPEED).getValue(), EPSILON, "held");
            assertTrue(s.ctx().isSilenced(s.enemy), "no skills behind the curtain");
            assertTrue(blockDisplays(s) >= 11, "a curtain around the enemy, a hat over the magician");
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "nothing happens before the reveal");

            s.ticks(s.paramTicks("ult_hold"));
            double away = horizontalDistance(stage, s.enemy.getLocation());
            assertTrue(away >= s.param("ult_min_distance") - 0.01 && away <= s.param("ult_max_distance") + 0.01,
                    "vanished to " + s.enemy.getLocation() + ", " + away + " blocks away");
            assertFalse(s.world.hasCollisionsIn(s.enemy.getBoundingBox().expand(-0.01)), "not inside a block");
            assertEquals(s.param("ult_damage"), s.damageBy(s.owner, s.enemy), EPSILON);
            assertTrue(s.enemy.hasPotionEffect(PotionEffectType.BLINDNESS), "dazzled");
            assertFalse(s.enemy.canSee(s.owner), "the magician is gone under the hat");
            assertFalse(s.enemy2.canSee(s.owner));
            assertTrue(horizontalDistance(s.owner.getLocation(), new Location(s.world, 0.5, 64, 0.5)) < 0.01,
                    "the magician stays where it was");

            s.ticks(s.paramTicks("ult_stealth") + 2);
            assertStandsOnSurface(s, s.enemy, "the vanished enemy landed");
            assertTrue(s.enemy.canSee(s.owner), "back on stage");
            assertTrue(s.enemy2.canSee(s.owner));
            s.ticks(40);
            assertTrue(s.hitsOf(s.enemy, DamageCause.FALL).isEmpty(), "the drop is short: no fall damage");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            assertModifierFree(s.enemy, "magician");
            assertFalse(s.ctx().isSilenced(s.enemy));
            assertNoDisplays(s);
            s.finishClean();
        }
    }

    @Test
    void magicianAttackingFromTheHatEndsTheVanish() {
        try (Scenario s = Scenario.ffa("magician")) {
            s.place(s.enemy, 0.5, 6.5, 180);
            s.place(s.enemy2, 0.5, -1.5, 0);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "사라지는 마술");
            s.ticks(s.paramTicks("ult_hold") + 5);
            assertFalse(s.enemy2.canSee(s.owner), "vanished");
            s.melee(s.owner, s.enemy2, 3);
            assertTrue(s.enemy2.canSee(s.owner), "the attack gives the magician away");
            assertTrue(s.enemy.canSee(s.owner));
            s.ticks(60);
            assertNoDisplays(s);
            s.finishClean();
        }
    }

    @Test
    void magicianDyingWhileVanishedIsSeenAgain() {
        try (Scenario s = Scenario.ffa("magician")) {
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "사라지는 마술");
            s.ticks(s.paramTicks("ult_hold") + 5);
            assertFalse(s.enemy2.canSee(s.owner), "vanished");
            s.killOwner(s.enemy2);
            s.ticks(1);
            assertTrue(s.enemy.canSee(s.owner));
            assertTrue(s.enemy2.canSee(s.owner));
            s.finishMatch();
            s.assertNoProblems();
            s.assertCleanWorld();
        }
    }

    @Test
    void magicianVanishingActNeedsAnAimedEnemy() {
        try (Scenario s = Scenario.ffa("magician")) {
            s.aimAt(s.owner, new Location(s.world, 0.5, 90, 0.5));
            assertFalse(s.ultimate().succeeded(), "nobody aimed at");
            assertEquals(100, s.ctx().gauge(s.owner), EPSILON, "the gauge is kept");
            assertNoDisplays(s);
            s.finishClean();
        }
    }

    // ------------------------------------------------------------------ 광대 clown

    @Test
    void clownLaughsAndSpeedsUpAfterAKill() {
        try (Scenario s = Scenario.ffa("clown")) {
            assertFalse(s.owner.hasPotionEffect(PotionEffectType.SPEED));
            s.place(s.enemy, 0.5, 2.0, 180);
            s.enemy.setHealth(1);
            s.melee(s.owner, s.enemy, 4);
            assertFalse(s.session.isAlive(s.enemy), "the enemy fell");
            s.respawn(s.enemy);
            PotionEffect speed = s.owner.getPotionEffect(PotionEffectType.SPEED);
            assertNotNull(speed, "the clown cracks up and runs");
            assertEquals(s.param("laugh_level") - 1, speed.getAmplifier(), EPSILON);
            assertEquals(s.paramTicks("laugh_duration"), speed.getDuration(), 1);
            assertTrue(textDisplays(s) > 0, "깔깔깔!");
            s.ticks(40);
            assertNoDisplays(s);
            s.finishMatch();
            s.assertNoProblems();
            s.assertCleanWorld();
        }
    }

    @Test
    void clownJugglingBallsHitEachEnemyOncePerBall() {
        try (Scenario s = Scenario.ffa("clown")) {
            s.place(s.enemy, 0.5, 6.5, 180);
            s.place(s.enemy2, 14.5, 0.5, 270);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "저글링");
            assertEquals(2L * s.param("balls"), blockDisplays(s), "two-tone balls in the hands");
            s.ticks(s.paramTicks("ball_duration") + 10);
            double damage = s.damageBy(s.owner, s.enemy);
            assertTrue(damage >= s.param("ball_damage") - EPSILON, "a ball bonks the enemy: " + s.hits());
            assertTrue(s.hitsBy(s.owner, s.enemy).size() <= s.param("balls"), "each ball hits an enemy once");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            assertNoDisplays(s);
            s.finishClean();
        }
    }

    @Test
    void clownBallsBounceOffWallsBackPastTheClown() {
        try (Scenario s = Scenario.ffa("clown")) {
            s.place(s.enemy, 0.5, -2.0, 0); // behind the clown
            s.place(s.enemy2, 14.5, 0.5, 270);
            s.world.wallAlongX(-3, 3, 3, 3, Material.STONE);
            s.assertSucceeded(s.active(), "저글링");
            s.ticks(s.paramTicks("ball_duration") + 10);
            assertTrue(s.damageBy(s.owner, s.enemy) >= s.param("ball_damage") - EPSILON,
                    "a ball came back off the wall: " + s.hits());
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "the clown's own balls fly past it");
            assertNoDisplays(s);
            s.world.wallAlongX(-3, 3, 3, 3, Material.AIR);
            s.finishClean();
        }
    }

    @Test
    void clownCannonFliesOntoTheMarkAndTheFireworkThrowsEnemiesUp() {
        try (Scenario s = Scenario.ffa("clown")) {
            s.place(s.enemy, 0.5, 10.5, 180);
            s.place(s.enemy2, 16.5, 0.5, 270);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "서커스 대포");
            s.ticks(3);
            assertEquals(0, s.owner.getAttribute(Attribute.MOVEMENT_SPEED).getValue(), EPSILON, "loaded");
            assertTrue(blockDisplays(s) >= 6, "a striped cannon on wheels");
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "nothing hits before the landing");
            s.h.tickUntil(() -> s.damageBy(s.owner, s.enemy) > 0, 100, "the landing firework");
            assertTrue(horizontalDistance(s.owner.getLocation(), new Location(s.world, 0.5, 64, 10.5)) < 2,
                    "landed on the mark: " + s.owner.getLocation());
            assertEquals(s.param("ult_damage"), s.damageBy(s.owner, s.enemy), EPSILON);
            s.ticks(3);
            assertTrue(s.enemy.getLocation().getY() > FlatWorld.SURFACE + 0.3, "thrown up by the firework");
            PotionEffect speed = s.owner.getPotionEffect(PotionEffectType.SPEED);
            assertNotNull(speed, "a firework hit makes the clown laugh");
            s.ticks(60);
            assertStandsOnSurface(s, s.owner, "the clown's landing");
            assertTrue(s.hitsOf(s.owner, DamageCause.FALL).isEmpty(), "no fall damage after the flight");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON, "the far enemy is safe");
            assertModifierFree(s.owner, "clown");
            assertNoDisplays(s);
            s.finishClean();
        }
    }

    @Test
    void clownCannonNeedsSomewhereToLand() {
        try (Scenario s = Scenario.ffa("clown")) {
            s.aimAt(s.owner, new Location(s.world, 0.5, 120, 0.5));
            assertFalse(s.ultimate().succeeded(), "straight up there is nothing to land on");
            assertEquals(100, s.ctx().gauge(s.owner), EPSILON, "the gauge is kept");
            assertNoDisplays(s);
            assertModifierFree(s.owner, "clown");
            s.finishClean();
        }
    }

    // ------------------------------------------------------------------ 축구선수 soccer

    @Test
    void soccerDribbleBuildsSpeedWhileSprintingAndResetsOnStopping() {
        try (Scenario s = Scenario.ffa("soccer")) {
            s.owner.setSprinting(true);
            s.ticks(s.paramTicks("dribble_ramp") / 2 + 1);
            double half = speedBonus(s.owner);
            assertTrue(half > 0 && half < s.param("dribble_max") / 100.0, "speeding up: " + half);
            assertEquals(2, blockDisplays(s), "a ball rolls at the feet");
            s.ticks(s.paramTicks("dribble_ramp"));
            assertEquals(s.param("dribble_max") / 100.0, speedBonus(s.owner), EPSILON, "full speed");
            s.owner.setSprinting(false);
            s.ticks(1);
            assertEquals(0, speedBonus(s.owner), EPSILON, "stopping resets the dribble");
            s.ticks(10);
            assertNoDisplays(s);
            s.finishClean();
        }
    }

    @Test
    void soccerShotHitsAndPushesTheEnemy() {
        try (Scenario s = Scenario.ffa("soccer")) {
            s.place(s.enemy, 0.5, 6.5, 180);
            s.place(s.enemy2, 14.5, 0.5, 270);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "슈팅");
            assertEquals(2, blockDisplays(s), "the ball");
            s.ticks(15);
            assertEquals(s.param("shot_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "hit once: " + s.hits());
            assertTrue(s.enemy.getLocation().getZ() > 7.5, "pushed back: " + s.enemy.getLocation());
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.ticks(80);
            assertNoDisplays(s);
            s.finishClean();
        }
    }

    @Test
    void soccerBankShotOffAWallHitsHarder() {
        try (Scenario s = Scenario.ffa("soccer")) {
            s.place(s.enemy, 0.5, -2.0, 0); // behind the striker
            s.place(s.enemy2, 14.5, 0.5, 270);
            s.world.wallAlongX(-3, 3, 4, 2, Material.STONE);
            s.assertSucceeded(s.active(), "슈팅");
            s.ticks(30);
            assertEquals(s.param("shot_damage") * (1 + s.param("bank_bonus") / 100), s.damageBy(s.owner, s.enemy),
                    EPSILON, "a bank shot: " + s.hits());
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "the ball rolls past its striker");
            s.ticks(80);
            assertNoDisplays(s);
            s.world.wallAlongX(-3, 3, 4, 2, Material.AIR);
            s.finishClean();
        }
    }

    @Test
    void soccerBananaKickCurvesInBurnsThroughAndScores() {
        try (Scenario s = Scenario.ffa("soccer")) {
            s.place(s.enemy, 0.5, 10.5, 180);
            s.place(s.enemy2, 1.5, 10.5, 180); // shoulder to shoulder with the target
            s.aimAt(s.owner, s.enemy);
            Scenario.messages(s.enemy2);
            s.assertSucceeded(s.ultimate(), "바나나킥");
            double bend = 0;
            boolean goal = false;
            for (int i = 0; i < 40; i++) {
                s.ticks(1);
                for (Entity entity : s.world.getEntities()) {
                    if (entity instanceof BlockDisplay display
                            && display.getBlock().getMaterial() == Material.WHITE_CONCRETE
                            && display.getLocation().getZ() < 9.5) {
                        bend = Math.max(bend, Math.abs(display.getLocation().getX() - 0.5));
                    }
                    if (entity instanceof BlockDisplay display && display.getBlock().getMaterial() == Material.COBWEB) {
                        goal = true;
                    }
                }
            }
            assertTrue(bend > 1.0, "the ball curves on its way: " + bend);
            assertTrue(goal, "the net goes up");
            assertEquals(s.param("ult_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "hit once: " + s.hits());
            assertEquals(s.param("ult_damage"), s.damageBy(s.owner, s.enemy2), EPSILON, "pierced");
            assertTrue(s.enemy.getFireTicks() > 0, "on fire");
            assertTrue(Scenario.messages(s.enemy2).stream().anyMatch(text -> text.contains("골")), "goal announced");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.ticks(80);
            assertNoDisplays(s);
            s.finishClean();
        }
    }

    // ------------------------------------------------------------------ 야구선수 batter

    @Test
    void batterKnocksFarAndEveryThirdHitIsACleanHit() {
        try (Scenario s = Scenario.ffa("batter")) {
            s.place(s.enemy2, 14.5, 0.5, 270);
            s.place(s.enemy, 0.5, 2.0, 180);
            s.melee(s.owner, s.enemy, 4);
            s.ticks(30);
            double plain = s.enemy.getLocation().getZ() - 2.0;
            assertTrue(plain > s.param("bat_push") * 0.6, "knocked away: " + plain);

            s.place(s.enemy, 0.5, 2.0, 180);
            s.melee(s.owner, s.enemy, 4);
            s.ticks(5);
            s.place(s.enemy, 0.5, 2.0, 180);
            s.melee(s.owner, s.enemy, 4);
            assertEquals(8 + 4 * (1 + s.param("crit_bonus") / 100), s.damageBy(s.owner, s.enemy), EPSILON,
                    "the third hit is clean");
            s.ticks(30);
            double clean = s.enemy.getLocation().getZ() - 2.0;
            assertTrue(clean > plain * 1.5, "a clean hit sends flying: " + clean + " vs " + plain);

            // A streak that lapses starts over.
            s.clearHits();
            s.place(s.enemy, 0.5, 2.0, 180);
            s.melee(s.owner, s.enemy, 4);
            s.melee(s.owner, s.enemy, 4);
            s.ticks(s.paramTicks("count_reset") + 2);
            s.melee(s.owner, s.enemy, 4);
            assertEquals(12, s.damageBy(s.owner, s.enemy), EPSILON, "the count was reset");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.ticks(30);
            assertNoDisplays(s);
            s.finishClean();
        }
    }

    @Test
    void batterFastballStaggersLongerOnAHeadHit() {
        try (Scenario s = Scenario.ffa("batter")) {
            s.place(s.enemy, 0.5, 8.5, 180);
            s.place(s.enemy2, 14.5, 0.5, 270);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "강속구");
            s.ticks(9);
            assertEquals(s.param("fastball_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "a body hit");
            assertEquals(0, s.enemy.getAttribute(Attribute.MOVEMENT_SPEED).getValue(), EPSILON, "staggered");
            s.ticks(s.paramTicks("fastball_stun") + 2);
            assertModifierFree(s.enemy, "the stagger wears off");

            s.clearHits();
            s.layout();
            s.place(s.enemy, 0.5, 8.5, 180);
            s.readyActive();
            s.aimAt(s.owner, s.enemy.getEyeLocation());
            s.assertSucceeded(s.active(), "강속구");
            s.ticks(9);
            assertEquals(s.param("fastball_damage") + s.param("head_bonus"), s.damageBy(s.owner, s.enemy), EPSILON,
                    "a head hit");
            s.ticks(s.paramTicks("fastball_stun") + 2);
            assertEquals(0, s.enemy.getAttribute(Attribute.MOVEMENT_SPEED).getValue(), EPSILON,
                    "still staggered after a head hit");
            s.ticks(s.paramTicks("head_stun"));
            assertModifierFree(s.enemy, "the head stagger wears off");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.ticks(20);
            assertNoDisplays(s);
            s.finishClean();
        }
    }

    @Test
    void batterFastballStopsAtWalls() {
        try (Scenario s = Scenario.ffa("batter")) {
            s.place(s.enemy, 0.5, 8.5, 180);
            s.aimAt(s.owner, s.enemy);
            s.world.wallAlongX(-3, 3, 4, 3, Material.STONE);
            s.assertSucceeded(s.active(), "강속구");
            s.ticks(30);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "the wall took the ball");
            assertNoDisplays(s);
            s.world.wallAlongX(-3, 3, 4, 3, Material.AIR);
            s.finishClean();
        }
    }

    @Test
    void batterHomeRunLaunchesSkyHighAndTheLandingIsCredited() {
        try (Scenario s = Scenario.ffa("batter")) {
            s.place(s.enemy, 0.5, 3.0, 180);
            s.place(s.enemy2, 0.5, -2.0, 0); // at the batter's back
            Scenario.messages(s.enemy2);
            s.assertSucceeded(s.ultimate(), "만루 홈런");
            s.ticks(2);
            assertEquals(0, s.owner.getAttribute(Attribute.MOVEMENT_SPEED).getValue(), EPSILON, "planted");
            assertTrue(blockDisplays(s) >= 3, "a giant bat and the plate");
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "the wind-up");
            s.ticks(s.paramTicks("ult_windup"));
            assertEquals(s.param("ult_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "the swing connects");
            double peak = s.enemy.getLocation().getY();
            for (int i = 0; i < 60 && !s.enemy.isOnGround() || i < 10; i++) {
                s.ticks(1);
                peak = Math.max(peak, s.enemy.getLocation().getY());
            }
            s.ticks(3);
            assertStandsOnSurface(s, s.enemy, "the home run landing");
            double height = peak - FlatWorld.SURFACE;
            assertTrue(height > s.param("ult_height") * 0.8, "sky-high: " + height);
            assertTrue(s.enemy.getLocation().getZ() - 3.0 > s.param("ult_distance") * 0.6,
                    "far away: " + s.enemy.getLocation());
            List<Scenario.Hit> falls = s.hitsBy(s.owner, s.enemy).stream()
                    .filter(hit -> hit.type() == DamageType.FALL).toList();
            assertEquals(1, falls.size(), "the landing is the batter's: " + s.hits());
            double expected = Math.min(s.param("ult_fall_max"), height - 3);
            assertEquals(expected, falls.getFirst().damage(), 0.35, "the fall, capped");
            assertTrue(s.hitsOf(s.enemy, DamageCause.FALL).isEmpty(), "no uncredited fall damage: " + s.hits());
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON, "nobody behind the batter is hit");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            assertTrue(Scenario.messages(s.enemy2).stream().anyMatch(text -> text.contains("홈런")),
                    "the home run is announced");
            s.ticks(40);
            assertNoDisplays(s);
            assertModifierFree(s.owner, "batter");
            s.finishClean();
        }
    }

    @Test
    void batterHomeRunLandingKillIsCreditedToTheBatter() {
        try (Scenario s = Scenario.ffa("batter")) {
            s.place(s.enemy, 0.5, 3.0, 180);
            s.place(s.enemy2, 14.5, 0.5, 270);
            s.enemy.setHealth(s.param("ult_damage") + 1);
            s.assertSucceeded(s.ultimate(), "만루 홈런");
            s.h.tickUntil(() -> !s.session.isAlive(s.enemy), 120, "the fatal landing");
            assertEquals(1, s.session.stats().of(s.owner.getUniqueId()).kills(), "the batter scores the kill");
            s.respawn(s.enemy);
            s.ticks(40);
            assertNoDisplays(s);
            s.finishMatch();
            s.assertNoProblems();
            s.assertCleanWorld();
        }
    }

    @Test
    void batterHomeRunNeedsSomeoneWithinReachAndCanWhiff() {
        try (Scenario s = Scenario.ffa("batter")) {
            s.place(s.enemy, 0.5, 12.5, 180);
            s.place(s.enemy2, 14.5, 0.5, 270);
            assertFalse(s.ultimate().succeeded(), "nobody within reach");
            assertEquals(100, s.ctx().gauge(s.owner), EPSILON, "the gauge is kept");

            s.place(s.enemy, 0.5, -2.0, 0); // only behind the batter
            s.assertSucceeded(s.ultimate(), "만루 홈런");
            s.ticks(s.paramTicks("ult_windup") + 40);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "a swing at the air in front");
            assertNoDisplays(s);
            s.finishClean();
        }
    }

    // ------------------------------------------------------------------ real-client regressions

    @ParameterizedTest(name = "{0}")
    @MethodSource("abilities")
    void everyDisplayStaysUnrotatedWhileTheOwnerLooksAround(String id) {
        try (Scenario s = Scenario.ffa(id)) {
            // A diagonal target, so the owner's yaw and pitch are far from 0.
            s.place(s.enemy, 3.0, 3.5, 200);
            s.aimAt(s.owner, s.enemy);
            assertTrue(Math.abs(s.owner.getLocation().getYaw()) > 20, "the owner looks sideways");
            s.assertSucceeded(s.active(), id + " active");
            for (int i = 0; i < 60; i++) {
                s.ticks(1);
                assertUnrotated(s, id + " active");
            }
            s.layout();
            s.place(s.enemy, 3.0, 3.5, 200);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), id + " ultimate");
            assertUnrotated(s, id + " ultimate");
            for (int i = 0; i < 80; i++) {
                s.ticks(1);
                assertUnrotated(s, id + " ultimate");
            }
            s.ticks(100);
            assertNoDisplays(s);
            s.finishClean();
        }
    }

    @Test
    void batterPlateAndBatTurnWithTheBatter() {
        try (Scenario s = Scenario.ffa("batter")) {
            s.place(s.owner, 0.5, 0.5, 90); // facing west (-x)
            s.place(s.enemy, -2.5, 0.5, 270);
            s.place(s.enemy2, 14.5, 0.5, 270);
            s.assertSucceeded(s.ultimate(), "만루 홈런");
            s.ticks(3);
            Display plate = displaysOf(s, Material.WHITE_CONCRETE).getFirst();
            Vector corner = axis(plate, 1, 0, 1).normalize();
            assertTrue(corner.getX() < -0.99, "the plate's corner points where the batter faces: " + corner);
            Display bat = displaysOf(s, Material.BIRCH_PLANKS).getFirst();
            Vector along = axis(bat, 0, 0, 1);
            assertTrue(along.getX() > 0.4 && along.getZ() < -0.2 && along.getY() > 0.2,
                    "the bat is drawn back over the right shoulder (east, north, up): " + along);
            s.ticks(80);
            assertNoDisplays(s);
            s.finishClean();
        }
    }

    @Test
    void clownCannonAimsAtTheLandingOnUprightWheels() {
        try (Scenario s = Scenario.ffa("clown")) {
            s.place(s.enemy, -9.5, 0.5, 270); // west of the clown
            s.place(s.enemy2, 14.5, 8.5, 270);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "서커스 대포");
            s.ticks(3);
            Display barrel = displaysOf(s, Material.RED_CONCRETE).getFirst();
            Vector bore = axis(barrel, 0, 0, 1);
            Vector flat = bore.clone().setY(0).normalize();
            assertTrue(flat.getX() < -0.98, "the barrel points at the landing: " + bore);
            assertTrue(bore.getY() > 0.3, "and up into the air: " + bore);
            List<Display> wheels = displaysOf(s, Material.DARK_OAK_PLANKS);
            assertEquals(2, wheels.size());
            for (Display wheel : wheels) {
                Vector axle = axis(wheel, 1, 0, 0);
                assertTrue(Math.abs(axle.getX()) < 0.02 && Math.abs(axle.getZ()) > 0.98,
                        "the wheel turns on an axle across the line of fire: " + axle);
            }
            s.h.tickUntil(() -> s.damageBy(s.owner, s.enemy) > 0, 100, "the landing firework");
            settle(s, s.owner);
            assertStandsOnSurface(s, s.owner, "the clown's landing to the west");
            s.ticks(60);
            assertNoDisplays(s);
            s.finishClean();
        }
    }

    @Test
    void soccerGoalNetStandsAcrossTheShot() {
        try (Scenario s = Scenario.ffa("soccer")) {
            s.place(s.enemy, 10.5, 0.5, 90); // east of the striker
            s.place(s.enemy2, -14.5, 8.5, 270);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "바나나킥");
            s.h.tickUntil(() -> !displaysOf(s, Material.COBWEB).isEmpty(), 60, "the goal");
            s.ticks(4);
            Display net = displaysOf(s, Material.COBWEB).getFirst();
            Vector depth = axis(net, 0, 0, 1);
            assertTrue(depth.getX() > 0.7, "the net faces the shot coming from the west: " + depth);
            assertTrue(Math.abs(depth.getY()) < 1.0E-3, "and stands upright: " + depth);
            for (Display post : displaysOf(s, Material.WHITE_CONCRETE)) {
                Vector3f size = post.getTransformation().getScale();
                if (Math.max(size.x(), Math.max(size.y(), size.z())) < 1) {
                    continue; // the ball, not the frame
                }
                Vector up = axis(post, 0, 1, 0);
                assertTrue(up.getY() > 0.99, "goal frame upright: " + up);
            }
            s.ticks(80);
            assertNoDisplays(s);
            s.finishClean();
        }
    }

    @Test
    void magicianCurtainPanelsCloseAroundTheEnemy() {
        try (Scenario s = Scenario.ffa("magician")) {
            s.place(s.enemy, 4.5, 5.5, 200);
            s.aimAt(s.owner, s.enemy);
            Location stage = s.enemy.getLocation();
            s.assertSucceeded(s.ultimate(), "사라지는 마술");
            s.ticks(8);
            List<Display> panels = displaysOf(s, Material.PURPLE_WOOL);
            assertEquals(4, panels.size());
            for (Display panel : panels) {
                Vector out = panel.getLocation().toVector().subtract(stage.toVector()).setY(0).normalize();
                Vector thin = axis(panel, 0, 0, 1);
                assertTrue(Math.abs(thin.dot(out)) > 0.99, "each panel faces the enemy: " + thin + " vs " + out);
                assertTrue(axis(panel, 0, 1, 0).getY() > 0.99, "and hangs straight down");
            }
            s.ticks(80);
            assertNoDisplays(s);
            s.finishClean();
        }
    }
}
