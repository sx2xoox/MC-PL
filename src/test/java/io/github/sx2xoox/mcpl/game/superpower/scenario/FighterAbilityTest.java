package io.github.sx2xoox.mcpl.game.superpower.scenario;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.sx2xoox.mcpl.game.superpower.abilities.FighterAbility;
import io.github.sx2xoox.mcpl.game.superpower.ability.Ability;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import io.github.sx2xoox.mcpl.game.superpower.ability.ActivationResult;
import io.github.sx2xoox.mcpl.game.superpower.support.Scenario;
import io.github.sx2xoox.mcpl.game.superpower.support.TestPlayer;
import io.github.sx2xoox.mcpl.game.superpower.support.world.FlatWorld;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.IronGolem;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Wolf;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 격투가 (fighter): the counter of its fighting spirit, the grab-and-suplex active (with its whiff, its fallbacks when
 * there is no room behind, and its splash) and the piledriver ultimate (dash, leap, drive, crater, K.O. banner), with
 * walls, ceilings, the void, the arena border, a target that dies or escapes mid-leap, owner death, fall damage, team
 * mode, summons and a Gambler-borrowed copy.
 */
class FighterAbilityTest {

    private static final double EPSILON = 1.0E-6;
    /** The art direction's cap on display entities alive at once per ability instance. */
    private static final long MAX_DISPLAYS = 24;
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z0-9_]+)}");
    private static final List<Attribute> CONTROL = List.of(Attribute.MOVEMENT_SPEED, Attribute.JUMP_STRENGTH,
            Attribute.ATTACK_SPEED, Attribute.GRAVITY);

    // ------------------------------------------------------------------ helpers

    private static double horizontalDistance(Location a, Location b) {
        return Math.hypot(a.getX() - b.getX(), a.getZ() - b.getZ());
    }

    private static long displays(Scenario s) {
        return s.world.getEntities().stream().filter(Display.class::isInstance).count();
    }

    private static void assertNoDisplays(Scenario s) {
        assertEquals(0, displays(s), "displays left behind: " + s.world.getEntities());
    }

    private static void assertNobodyEntombed(Scenario s) {
        for (TestPlayer player : s.all()) {
            if (s.session.isAlive(player)) {
                assertFalse(s.world.hasCollisionsIn(player.getBoundingBox().expand(-0.01)),
                        player.getName() + " was put inside a block at " + player.getLocation());
            }
        }
    }

    private static void assertNoControlModifiers(LivingEntity entity, String what) {
        for (Attribute attribute : CONTROL) {
            AttributeInstance instance = entity.getAttribute(attribute);
            if (instance == null) {
                continue;
            }
            for (AttributeModifier modifier : instance.getModifiers()) {
                fail(what + ": " + entity.getName() + " kept " + modifier.getKey() + " on " + attribute.getKey());
            }
        }
    }

    private static boolean rooted(LivingEntity entity) {
        return entity.getAttribute(Attribute.MOVEMENT_SPEED).getValue() < EPSILON;
    }

    private static boolean hasModifiers(LivingEntity entity, Attribute attribute) {
        return !entity.getAttribute(attribute).getModifiers().isEmpty();
    }

    /** Text of every text display in the world, as plain text. */
    private static List<String> banners(Scenario s) {
        return s.world.getEntities().stream().filter(TextDisplay.class::isInstance)
                .map(entity -> Scenario.plain(((TextDisplay) entity).text())).toList();
    }

    /** A gambler owner with no luck of its own, so the borrowed ultimate alone decides what happens. */
    private static Scenario gambler() {
        return Scenario.builder("gambler").param("dodge_chance", 0).param("crit_chance", 0).start();
    }

    /** Runs the fighter's ultimate on a freshly bound instance, exactly like the gambler's jackpot does. */
    private static ActivationResult borrow(Scenario s) {
        Ability borrowed = s.ctx().registry().create("fighter");
        borrowed.bind(s.owner, s.ctx(), s.ctx().paramsFor("fighter"));
        return borrowed.onUltimate();
    }

    /** Ticks until {@code victim} has taken damage from the owner (at most {@code max} ticks). */
    private static void tickUntilHurt(Scenario s, Entity victim, int max) {
        s.h.tickUntil(() -> s.damageBy(s.owner, victim) > 0, max, victim.getName() + " hurt by the owner");
    }

    /** Removes the floor (bedrock included) of the columns x1..x2, z1..z2: the void, except for {@code keep}. */
    private static void voidColumns(FlatWorld world, int x1, int x2, int z1, int z2, int keepX, int keepZ,
                                    boolean restore) {
        for (int x = x1; x <= x2; x++) {
            for (int z = z1; z <= z2; z++) {
                if (x == keepX && z == keepZ) {
                    continue;
                }
                for (int y = world.getMinHeight(); y <= FlatWorld.FLOOR_Y; y++) {
                    Material type = !restore ? Material.AIR
                            : y == world.getMinHeight() ? Material.BEDROCK : Material.STONE;
                    world.setBlock(x, y, z, type);
                }
            }
        }
    }

    private static void slab(FlatWorld world, int x1, int x2, int y, int z1, int z2, Material type) {
        for (int x = x1; x <= x2; x++) {
            for (int z = z1; z <= z2; z++) {
                world.setBlock(x, y, z, type);
            }
        }
    }

    // ------------------------------------------------------------------ the ability as a whole

    @Test
    void infoIsCompleteAndTheUltimateCanBeBorrowed() {
        AbilityInfo info = FighterAbility.INFO;
        assertEquals("fighter", info.id());
        assertEquals("격투가", info.name());
        assertTrue(info.ultimateStandalone(), "the piledriver needs no grant, tick or hook state");
        for (AbilityInfo.Skill skill : List.of(info.passive(), info.active(), info.ultimate())) {
            assertFalse(skill.name().contains("TODO") || skill.description().contains("TODO"), skill.name());
            Matcher matcher = PLACEHOLDER.matcher(skill.description());
            while (matcher.find()) {
                assertTrue(info.params().containsKey(matcher.group(1)),
                        skill.name() + " mentions an undeclared param " + matcher.group(1));
            }
        }
    }

    @Test
    void wholeMatch() {
        try (Scenario s = Scenario.ffa("fighter")) {
            s.ticks(60);
            assertEquals(s.param("knockback_resist") / 100.0,
                    s.owner.getAttribute(Attribute.KNOCKBACK_RESISTANCE).getValue(), EPSILON, "a sturdy stance");

            s.aimAt(s.owner, s.enemy);
            s.clearHits();
            s.assertSucceeded(s.active(), "잡기 메치기");
            s.ticks(100);
            assertTrue(s.damageTaken(s.enemy) > 0, "the suplex hurts the aimed enemy");
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "never the owner: " + s.hits());

            s.layout();
            s.aimAt(s.owner, s.enemy);
            s.healAll();
            s.clearHits();
            s.assertSucceeded(s.ultimate(), "파일드라이버");
            s.ticks(300);
            assertTrue(s.damageTaken(s.enemy) > 0, "the piledriver hurts the aimed enemy");
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "never the owner: " + s.hits());
            assertNoDisplays(s);
            assertEquals(0, s.h.hub.tempBlocks().size(), "temporary blocks restored");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    // ------------------------------------------------------------------ passive: fighting spirit

    @Test
    void aMeleeHitTakenTurnsTheNextMeleeHitIntoAStaggeringCounter() {
        try (Scenario s = Scenario.ffa("fighter")) {
            s.ticks(5);
            s.melee(s.enemy, s.owner, 2);
            s.melee(s.owner, s.enemy, 4);
            assertEquals(4 + s.param("counter_bonus"), s.damageBy(s.owner, s.enemy), EPSILON, "the counter hits harder");
            assertTrue(hasModifiers(s.enemy, Attribute.MOVEMENT_SPEED), "staggered: slower feet");
            assertTrue(hasModifiers(s.enemy, Attribute.ATTACK_SPEED), "staggered: slower swings");
            assertEquals(0.1 * (1 - s.param("stagger_slow") / 100.0),
                    s.enemy.getAttribute(Attribute.MOVEMENT_SPEED).getValue(), 1.0E-4);
            assertTrue(displays(s) > 0, "the 반격! pop-up and the impact star");
            assertTrue(banners(s).stream().anyMatch(text -> text.contains("반격")), "반격!: " + banners(s));

            // Used up: the next hit is a plain one.
            s.clearHits();
            s.melee(s.owner, s.enemy, 4);
            assertEquals(4, s.damageBy(s.owner, s.enemy), EPSILON, "one counter per hit taken");
            s.ticks(s.paramTicks("counter_stagger") + 2);
            assertNoControlModifiers(s.enemy, "the stagger wears off");

            // Recharging: a hit taken right after a counter does not arm a new one.
            s.clearHits();
            s.melee(s.enemy, s.owner, 2);
            s.melee(s.owner, s.enemy, 4);
            assertEquals(4, s.damageBy(s.owner, s.enemy), EPSILON, "no counter while recharging");

            // Recharged.
            s.ticks(s.paramTicks("counter_cooldown"));
            s.clearHits();
            s.melee(s.enemy, s.owner, 2);
            s.melee(s.owner, s.enemy, 4);
            assertEquals(4 + s.param("counter_bonus"), s.damageBy(s.owner, s.enemy), EPSILON, "counters again");
            s.ticks(40);
            assertNoControlModifiers(s.enemy, "the stagger wears off");
            assertNoDisplays(s);
            s.finishClean();
        }
    }

    @Test
    void theCounterWindowCloses() {
        try (Scenario s = Scenario.ffa("fighter")) {
            s.ticks(5);
            s.melee(s.enemy, s.owner, 2);
            s.ticks(s.paramTicks("counter_window") + 2);
            s.melee(s.owner, s.enemy, 4);
            assertEquals(4, s.damageBy(s.owner, s.enemy), EPSILON, "too late for a counter");
            assertNoControlModifiers(s.enemy, "no stagger");
            s.finishClean();
        }
    }

    @Test
    void onlyEnemyMeleeArmsTheCounter() {
        try (Scenario s = Scenario.teams("fighter")) {
            s.ticks(5);
            // Ability damage is not a melee hit.
            s.ctx().damage(s.enemy, s.owner, 2);
            s.melee(s.owner, s.enemy, 4);
            assertEquals(4, s.damageBy(s.owner, s.enemy), EPSILON, "ability damage does not arm the counter");
            // A teammate's swing is friendly fire, cancelled before it could arm anything.
            s.clearHits();
            s.melee(s.ally(), s.owner, 2);
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "no friendly fire");
            s.melee(s.owner, s.enemy, 4);
            assertEquals(4, s.damageBy(s.owner, s.enemy), EPSILON, "a teammate does not arm the counter");
            assertNoControlModifiers(s.enemy, "no stagger");
            s.finishClean();
        }
    }

    // ------------------------------------------------------------------ active: grab and suplex

    @Test
    void suplexLiftsTheEnemyOverTheHeadAndSlamsItBehind() {
        try (Scenario s = Scenario.ffa("fighter")) {
            s.place(s.enemy, 0.5, 3.0, 180);
            s.place(s.enemy2, 1.5, -1.5, 0); // right where the throw comes down
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "잡기 메치기");
            double highest = 0;
            boolean overhead = false;
            for (int i = 0; i < 20 && s.damageBy(s.owner, s.enemy) == 0; i++) {
                s.ticks(1);
                assertNobodyEntombed(s);
                Location at = s.enemy.getLocation();
                highest = Math.max(highest, at.getY());
                overhead |= horizontalDistance(at, s.owner.getLocation()) < 0.6
                        && at.getY() >= FlatWorld.SURFACE + s.owner.getHeight();
                if (s.damageBy(s.owner, s.enemy) == 0) {
                    assertTrue(rooted(s.owner), "the owner braces while throwing");
                }
            }
            assertTrue(overhead, "carried over the owner's head (highest " + highest + ")");
            assertEquals(s.param("suplex_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "slammed once");
            assertTrue(s.enemy.getLocation().getZ() < s.owner.getLocation().getZ(),
                    "thrown behind the owner: " + s.enemy.getLocation());
            assertTrue(rooted(s.enemy), "stunned on the mat");
            assertFalse(rooted(s.owner), "the owner is free again");
            assertEquals(s.param("suplex_splash"), s.damageBy(s.owner, s.enemy2), EPSILON, "the landing splashes");
            assertTrue(banners(s).stream().anyMatch(text -> text.contains("쾅")), "쾅!: " + banners(s));
            s.ticks(s.paramTicks("suplex_stun") + 2);
            assertNoControlModifiers(s.enemy, "the stun wears off");
            assertNoControlModifiers(s.owner, "the brace is gone");
            assertTrue(s.hitsOf(s.enemy, DamageCause.FALL).isEmpty(), "no fall damage on top: " + s.hits());
            assertFalse(s.enemy.hasPotionEffect(PotionEffectType.SLOW_FALLING), "the safety net is removed");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            assertTrue(s.ctx().cooldownRemaining(s.owner) > 0, "the cooldown runs");
            s.ticks(30);
            assertNoDisplays(s);
            s.finishClean();
        }
    }

    @Test
    void suplexWithAWallBehindSlamsInFront() {
        try (Scenario s = Scenario.ffa("fighter")) {
            s.world.wallAlongX(-3, 3, -1, 3, Material.STONE);
            s.place(s.enemy, 0.5, 3.0, 180);
            s.place(s.enemy2, 10.5, 10.5, 180);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "잡기 메치기");
            for (int i = 0; i < 25; i++) {
                s.ticks(1);
                assertNobodyEntombed(s);
            }
            assertEquals(s.param("suplex_damage"), s.damageBy(s.owner, s.enemy), EPSILON);
            assertTrue(s.enemy.getLocation().getZ() > s.owner.getLocation().getZ() + 0.8,
                    "slammed down in front instead: " + s.enemy.getLocation());
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.ticks(30);
            s.world.wallAlongX(-3, 3, -1, 3, Material.AIR);
            s.finishClean();
        }
    }

    @Test
    void suplexNeverThrowsIntoTheVoid() {
        try (Scenario s = Scenario.ffa("fighter")) {
            voidColumns(s.world, -3, 3, -5, -1, Integer.MIN_VALUE, Integer.MIN_VALUE, false);
            s.place(s.enemy, 0.5, 3.0, 180);
            s.place(s.enemy2, 10.5, 10.5, 180);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "잡기 메치기");
            s.ticks(25);
            assertEquals(s.param("suplex_damage"), s.damageBy(s.owner, s.enemy), EPSILON);
            assertTrue(s.enemy.getLocation().getZ() > s.owner.getLocation().getZ(),
                    "slammed down in front, not into the pit: " + s.enemy.getLocation());
            assertEquals(FlatWorld.SURFACE, s.enemy.getLocation().getY(), 0.3, "on the floor");
            s.ticks(30);
            voidColumns(s.world, -3, 3, -5, -1, Integer.MIN_VALUE, Integer.MIN_VALUE, true);
            s.finishClean();
        }
    }

    @Test
    void suplexWithNobodyInReachWhiffsWithoutCooldown() {
        try (Scenario s = Scenario.ffa("fighter")) {
            s.place(s.enemy, 0.5, 10.5, 180);
            s.place(s.enemy2, 10.5, 0.5, 270);
            s.place(s.owner, 0.5, 0.5, 0);
            s.ticks(2);
            ActivationResult whiff = s.active();
            assertFalse(whiff.succeeded(), "nobody to grab");
            assertEquals(0, s.ctx().cooldownRemaining(s.owner), EPSILON, "no cooldown for a whiff");
            assertTrue(s.owner.getVelocity().getY() > 0.1, "a lunge");

            // Spamming the key right away does not lunge again.
            s.ticks(8);
            assertFalse(s.active().succeeded());
            assertTrue(s.owner.getVelocity().getY() < 0.05, "no second lunge while spamming");
            s.ticks(12);
            double lunged = s.owner.getLocation().getZ() - 0.5;
            assertTrue(lunged > 0.5 && lunged < s.param("whiff_lunge") + 1.0, "a short lunge: " + lunged);

            // After a pause the next whiff lunges again.
            s.ticks(10);
            assertFalse(s.active().succeeded());
            assertTrue(s.owner.getVelocity().getY() > 0.1, "a lunge again after a pause");
            s.ticks(20);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON);
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON);
            s.ticks(20);
            assertNoDisplays(s);
            s.finishClean();
        }
    }

    @Test
    void suplexLetsGoOfAnEnemyCarriedAway() {
        try (Scenario s = Scenario.ffa("fighter")) {
            s.place(s.enemy, 0.5, 3.0, 180);
            s.place(s.enemy2, 10.5, 10.5, 180);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "잡기 메치기");
            s.ticks(5);
            s.place(s.enemy, 0.5, 15.5, 180);
            s.ticks(30);
            assertEquals(0, s.damageBy(s.owner, s.enemy), EPSILON, "no slam from afar");
            assertTrue(s.enemy.getLocation().distance(new Location(s.world, 0.5, FlatWorld.SURFACE, 15.5)) < 0.5,
                    "not dragged back");
            assertNoControlModifiers(s.owner, "the brace is released");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    // ------------------------------------------------------------------ ultimate: piledriver

    @Test
    void piledriverDashesLeapsAndDrivesTheEnemyIntoTheGround() {
        try (Scenario s = Scenario.ffa("fighter")) {
            s.place(s.enemy, 0.5, 10.5, 180);
            s.place(s.enemy2, 2.5, 11.5, 180); // next to where it comes down
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "파일드라이버");
            assertTrue(horizontalDistance(s.owner.getLocation(), s.enemy.getLocation()) < 3,
                    "dashed to the enemy: " + s.owner.getLocation());
            assertTrue(s.ctx().isSilenced(s.enemy), "a held enemy cannot use its skills");

            double ownerTop = 0;
            boolean overhead = false;
            for (int i = 0; i < 60 && s.damageBy(s.owner, s.enemy) == 0; i++) {
                s.ticks(1);
                assertNobodyEntombed(s);
                Location owner = s.owner.getLocation();
                Location enemy = s.enemy.getLocation();
                ownerTop = Math.max(ownerTop, owner.getY());
                overhead |= enemy.getY() - owner.getY() >= s.owner.getHeight() - 0.1
                        && horizontalDistance(owner, enemy) < 0.5;
            }
            assertTrue(overhead, "the enemy is carried over the owner's head");
            assertTrue(ownerTop >= FlatWorld.SURFACE + s.param("ult_height") * 0.85, "leapt high: " + ownerTop);
            assertEquals(s.param("ult_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "driven in once");
            assertEquals(1, s.hitsBy(s.owner, s.enemy).size());
            assertEquals(FlatWorld.SURFACE, s.enemy.getLocation().getY(), 0.3, "into the floor, not above it");
            assertTrue(rooted(s.enemy), "stunned");
            assertEquals(s.param("ult_splash"), s.damageBy(s.owner, s.enemy2), EPSILON, "the shockwave");
            assertTrue(displays(s) >= 10 && displays(s) <= MAX_DISPLAYS, "a crater, a star and the banner: "
                    + displays(s));
            assertTrue(banners(s).stream().anyMatch(text -> text.contains("파일드라이버")), "banner: " + banners(s));
            assertTrue(s.h.hub.tempBlocks().size() > 0, "rubble thrown up at the rim");
            assertFalse(s.enemy.hasPotionEffect(PotionEffectType.SLOW_FALLING), "the safety net is removed");

            for (int i = 0; i < 60; i++) {
                assertTrue(displays(s) <= MAX_DISPLAYS, "display budget: " + displays(s));
                s.ticks(1);
                assertNobodyEntombed(s);
            }
            assertTrue(s.hitsOf(s.owner, DamageCause.FALL).isEmpty(), "the owner takes no fall damage");
            assertTrue(s.hitsOf(s.enemy, DamageCause.FALL).isEmpty(), "the drive replaces the fall: " + s.hits());
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            assertNoControlModifiers(s.enemy, "the stun wears off");
            assertFalse(s.ctx().isSilenced(s.enemy), "the silence ends with the hold");
            s.ticks(s.paramTicks("crater_duration") + 20);
            assertNoDisplays(s);
            assertEquals(0, s.h.hub.tempBlocks().size(), "the crater closes");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void piledriverDashLandsExactlyOnTheFloor() {
        try (Scenario s = Scenario.ffa("fighter")) {
            s.place(s.enemy, 0.5, 10.5, 180);
            s.place(s.enemy2, 12.5, 0.5, 270);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "파일드라이버");
            // A dash a few thousandths into the floor makes the client sink into the ground.
            Location at = s.owner.getLocation();
            assertTrue(horizontalDistance(at, s.enemy.getLocation()) < 3, "dashed to the enemy: " + at);
            assertEquals(FlatWorld.SURFACE, at.getY(), EPSILON, "on the floor: " + at);
            assertFalse(s.world.hasCollisionsIn(s.owner.getBoundingBox().shift(0, 1.0E-3, 0)), "nothing in the body");
            s.ticks(s.paramTicks("crater_duration") + 80);
            s.finishClean();
        }
    }

    @Test
    void piledriverDebrisNeverFliesIntoOrLandsInsideABlock() {
        try (Scenario s = Scenario.ffa("fighter")) {
            // A small pen two blocks high around the drive: most debris throws would end inside its walls, where a
            // display lit like the floor (no brightness of its own) renders black.
            for (int y = FlatWorld.FLOOR_Y + 1; y <= FlatWorld.FLOOR_Y + 2; y++) {
                slab(s.world, -2, -2, y, -2, 4, Material.STONE);
                slab(s.world, 2, 2, y, -2, 4, Material.STONE);
                slab(s.world, -2, 2, y, -2, -2, Material.STONE);
                slab(s.world, -2, 2, y, 4, 4, Material.STONE);
            }
            s.place(s.enemy, 0.5, 2.0, 180); // close enough to grab without a dash
            s.place(s.enemy2, 12.5, 0.5, 270);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "파일드라이버");
            tickUntilHurt(s, s.enemy, 60);
            for (int i = 0; i < 30; i++) {
                for (Entity entity : s.world.getEntities()) {
                    if (entity instanceof Display display && display.getBrightness() == null) {
                        Location at = display.getLocation();
                        assertFalse(s.world.getBlockAt(at).getType().isSolid(),
                                "a display lit like the floor inside a block at " + at);
                    }
                }
                s.ticks(1);
            }
            s.ticks(s.paramTicks("crater_duration") + 20);
            for (int y = FlatWorld.FLOOR_Y + 1; y <= FlatWorld.FLOOR_Y + 2; y++) {
                slab(s.world, -2, 2, y, -2, 4, Material.AIR);
            }
            s.finishClean();
        }
    }

    @Test
    void piledriverThatKillsRingsTheKnockoutBell() {
        try (Scenario s = Scenario.ffa("fighter")) {
            s.place(s.enemy, 0.5, 10.5, 180);
            s.place(s.enemy2, 12.5, 0.5, 270);
            s.enemy.setHealth(5);
            s.aimAt(s.owner, s.enemy);
            Scenario.messages(s.enemy2);
            s.assertSucceeded(s.ultimate(), "파일드라이버");
            s.h.tickUntil(() -> !s.session.isAlive(s.enemy), 60, "the knockout");
            assertTrue(banners(s).stream().anyMatch(text -> text.contains("K.O.")), "K.O. banner: " + banners(s));
            assertTrue(Scenario.messages(s.enemy2).stream().anyMatch(text -> text.contains("K.O.")),
                    "the ring announcer calls it");
            s.respawn(s.enemy);
            s.ticks(80);
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.ticks(s.paramTicks("crater_duration"));
            assertNoDisplays(s);
            s.assertNoProblems();
            s.finishMatch();
            s.assertNoProblems();
            s.assertCleanWorld();
        }
    }

    @Test
    void piledriverUnderALowCeilingLeapsOnlyAsHighAsThereIsRoom() {
        try (Scenario s = Scenario.ffa("fighter")) {
            int ceiling = FlatWorld.FLOOR_Y + 7; // six blocks of room
            slab(s.world, -4, 5, ceiling, 4, 14, Material.STONE);
            s.place(s.enemy, 0.5, 10.5, 180);
            s.place(s.enemy2, 12.5, 0.5, 270);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "파일드라이버");
            double ownerTop = 0;
            for (int i = 0; i < 60; i++) {
                s.ticks(1);
                assertNobodyEntombed(s);
                ownerTop = Math.max(ownerTop, s.owner.getLocation().getY());
                assertTrue(s.enemy.getLocation().getY() + s.enemy.getHeight() <= ceiling + 1.0E-3,
                        "never through the ceiling: " + s.enemy.getLocation());
            }
            assertTrue(ownerTop > FlatWorld.SURFACE + 0.5, "still a leap: " + ownerTop);
            assertTrue(ownerTop < FlatWorld.SURFACE + s.param("ult_height") - 1, "but a lower one: " + ownerTop);
            assertEquals(s.param("ult_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "and the drive lands");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.ticks(s.paramTicks("crater_duration") + 20);
            slab(s.world, -4, 5, ceiling, 4, 14, Material.AIR);
            s.finishClean();
        }
    }

    @Test
    void piledriverInAShaftKeepsTheEnemyOnItsShoulders() {
        try (Scenario s = Scenario.ffa("fighter")) {
            // A ceiling six blocks up with a single hole right above the owner: room to leap, none beside it.
            int ceiling = FlatWorld.FLOOR_Y + 7;
            slab(s.world, -4, 4, ceiling, -4, 6, Material.STONE);
            s.world.setBlock(0, ceiling, 0, Material.AIR);
            s.place(s.enemy, 0.5, 2.5, 180); // close enough to grab without a dash
            s.place(s.enemy2, 12.5, 0.5, 270);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "파일드라이버");
            assertEquals(0.5, s.owner.getLocation().getX(), EPSILON, "no dash: the enemy was in reach");
            assertEquals(0.5, s.owner.getLocation().getZ(), EPSILON);
            double enemyTop = 0;
            for (int i = 0; i < 60 && s.damageBy(s.owner, s.enemy) == 0; i++) {
                s.ticks(1);
                assertNobodyEntombed(s);
                enemyTop = Math.max(enemyTop, s.enemy.getLocation().getY());
            }
            assertTrue(enemyTop > ceiling, "carried up through the hole: " + enemyTop);
            assertEquals(s.param("ult_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "driven down on the shoulders");
            for (int i = 0; i < 60; i++) {
                s.ticks(1);
                assertNobodyEntombed(s);
            }
            assertTrue(s.hitsOf(s.enemy, DamageCause.FALL).isEmpty(), "no fall damage on top: " + s.hits());
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.ticks(s.paramTicks("crater_duration") + 20);
            slab(s.world, -4, 4, ceiling, -4, 6, Material.AIR);
            s.finishClean();
        }
    }

    @Test
    void piledriverNeverTakesOffOverTheVoid() {
        try (Scenario s = Scenario.ffa("fighter")) {
            // The enemy stands on a lone pillar over the void: there is nowhere safe to grab it from.
            voidColumns(s.world, -4, 5, 6, 15, 0, 10, false);
            s.place(s.enemy, 0.5, 10.5, 180);
            s.place(s.enemy2, 30.5, 0.5, 270);
            s.aimAt(s.owner, s.enemy);
            Location start = s.owner.getLocation();
            ActivationResult result = s.ultimate();
            assertFalse(result.succeeded(), "no safe spot next to the enemy");
            assertEquals(100, s.participant(s.owner).gauge(), EPSILON, "the gauge is kept");
            assertTrue(s.owner.getLocation().distance(start) < 0.01, "the owner did not move");
            voidColumns(s.world, -4, 5, 6, 15, 0, 10, true);
            s.finishClean();
        }
    }

    @Test
    void piledriverStaysInsideTheArena() {
        try (Scenario s = Scenario.ffa("fighter")) {
            // Find the arena's edge along +x from the centre.
            Location center = s.session.arena().center();
            double edge = center.getX();
            while (s.ctx().isInsideArena(new Location(s.world, edge + 0.5, FlatWorld.SURFACE, center.getZ()))) {
                edge += 0.5;
            }
            double enemyX = Math.floor(edge - 1) + 0.5;
            s.place(s.enemy, enemyX, center.getZ(), 270); // facing out of the arena
            s.place(s.owner, enemyX - 10, center.getZ(), 270);
            s.place(s.enemy2, enemyX - 30, center.getZ() + 20, 0);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "파일드라이버");
            assertTrue(s.ctx().isInsideArena(s.owner.getLocation()), "dashed to a spot inside the arena");
            for (int i = 0; i < 60; i++) {
                s.ticks(1);
                assertNobodyEntombed(s);
                assertTrue(s.ctx().isInsideArena(s.owner.getLocation()), "the owner stays inside");
            }
            assertEquals(s.param("ult_damage"), s.damageBy(s.owner, s.enemy), EPSILON);
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.ticks(s.paramTicks("crater_duration") + 20);
            s.finishClean();
        }
    }

    @Test
    void piledriverReleasesAnEnemyThatEscapesMidLeap() {
        try (Scenario s = Scenario.ffa("fighter")) {
            s.place(s.enemy, 0.5, 10.5, 180);
            s.place(s.enemy2, 12.5, 0.5, 270);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "파일드라이버");
            s.ticks(10);
            assertTrue(s.owner.getLocation().getY() > FlatWorld.SURFACE + 1, "airborne");
            s.place(s.enemy, 0.5, 30.5, 180); // pulled out of the hold by something else
            s.tickUntilGrounded(s.owner, 80);
            s.ticks(30);
            assertEquals(0, s.damageBy(s.owner, s.enemy), EPSILON, "no drive without the enemy");
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "the owner lands safely: " + s.hits());
            assertNoControlModifiers(s.enemy, "nothing lingers");
            s.ticks(60);
            assertNoDisplays(s);
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void piledriverReleasesAnEnemyThatDiesMidLeap() {
        try (Scenario s = Scenario.ffa("fighter")) {
            s.place(s.enemy, 0.5, 10.5, 180);
            s.place(s.enemy2, 12.5, 0.5, 270);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "파일드라이버");
            s.ticks(10);
            s.enemy.damage(10_000, DamageSource.builder(DamageType.GENERIC_KILL).build());
            assertFalse(s.session.isAlive(s.enemy), "killed by something else");
            s.respawn(s.enemy);
            s.clearHits();
            s.tickUntilGrounded(s.owner, 80);
            s.ticks(30);
            assertTrue(s.hits().stream().noneMatch(hit -> s.owner.equals(hit.causer())), "nobody else is hit");
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "the owner lands safely: " + s.hits());
            s.ticks(60);
            assertNoDisplays(s);
            s.assertNoProblems();
            s.finishMatch();
            s.assertNoProblems();
            s.assertCleanWorld();
        }
    }

    @Test
    void piledriverNeedsAnEnemyInRange() {
        try (Scenario s = Scenario.ffa("fighter")) {
            s.place(s.enemy, 0.5, 30.5, 180);
            s.place(s.enemy2, 30.5, 0.5, 270);
            assertFalse(s.ultimate().succeeded(), "nobody within " + s.param("ult_range") + " blocks");
            assertEquals(100, s.participant(s.owner).gauge(), EPSILON, "the gauge is kept");
            s.finishClean();
        }
    }

    // ------------------------------------------------------------------ owner death and match end

    @Test
    void ownerDiesDuringTheSuplex() {
        try (Scenario s = Scenario.ffa("fighter")) {
            s.place(s.enemy, 0.5, 3.0, 180);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "잡기 메치기");
            s.ticks(6);
            assertOwnerDeathCleansUp(s);
        }
    }

    @Test
    void ownerDiesMidLeapAndTheHeldEnemyFloatsDown() {
        try (Scenario s = Scenario.ffa("fighter")) {
            s.place(s.enemy, 0.5, 10.5, 180);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "파일드라이버");
            s.ticks(10);
            assertTrue(s.enemy.getLocation().getY() > FlatWorld.SURFACE + 3, "held high in the air");
            assertOwnerDeathCleansUp(s);
            assertTrue(s.hitsOf(s.enemy, DamageCause.FALL).isEmpty(), "the dropped enemy floats down: " + s.hits());
        }
    }

    private static void assertOwnerDeathCleansUp(Scenario s) {
        s.killOwner(s.enemy2);
        s.ticks(1);
        for (Entity entity : s.world.getEntities()) {
            assertTrue(entity instanceof Player, "the dead owner's " + entity.getType() + " outlived it");
        }
        s.clearHits();
        s.ticks(300);
        assertTrue(s.hits().stream().noneMatch(hit -> s.owner.equals(hit.causer())),
                "the dead owner still dealt damage: " + s.hits());
        assertEquals(0, s.h.hub.tempBlocks().size(), "temporary blocks expire after the owner's death");
        for (TestPlayer enemy : s.enemies()) {
            assertNoControlModifiers(enemy, "after the owner's death");
            assertFalse(s.ctx().isSilenced(enemy));
        }
        assertNobodyEntombed(s);
        s.assertNoProblems();
        s.finishMatch();
        s.assertNoProblems();
        s.assertCleanWorld();
    }

    @Test
    void matchEndingRightAfterTheImpactRestoresTheWorld() {
        try (Scenario s = Scenario.ffa("fighter")) {
            s.place(s.enemy, 0.5, 10.5, 180);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "파일드라이버");
            tickUntilHurt(s, s.enemy, 60);
            assertTrue(s.h.hub.tempBlocks().size() > 0, "rubble is up");
            s.finishMatch();
            s.assertNoProblems();
            s.assertCleanWorld();
        }
    }

    // ------------------------------------------------------------------ teams and summons

    @Test
    void teamModeNeverHarmsTheAlly() {
        try (Scenario s = Scenario.teams("fighter")) {
            s.ticks(40);
            s.crossfireAlly();
            s.aimAt(s.owner, s.enemy);
            Location allyStart = s.ally().getLocation();
            s.clearHits();
            s.assertSucceeded(s.active(), "잡기 메치기");
            AbilityLifecycleTest.watchAlly(s, allyStart, 100);
            assertTrue(s.damageBy(s.owner, s.enemy) > 0, "the enemy was thrown");

            s.layout();
            s.crossfireAlly();
            s.aimAt(s.owner, s.enemy);
            allyStart = s.ally().getLocation();
            s.clearHits();
            s.assertSucceeded(s.ultimate(), "파일드라이버");
            AbilityLifecycleTest.watchAlly(s, allyStart, 200);
            assertTrue(s.damageBy(s.owner, s.enemy) > 0, "the enemy was driven in");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void grabsWorkOnEnemySummonsAndSpareAlliedOnes() {
        try (Scenario s = Scenario.builder("fighter").enemyAbility("summoner").start()) {
            s.ticks(40);
            s.assertSucceeded(s.match.useActive(s.enemy), "늑대 무리");
            s.match.setGauge(s.enemy, 100);
            s.assertSucceeded(s.match.useUltimate(s.enemy), "철골렘");
            s.ticks(30);
            List<Mob> pack = s.world.getEntities().stream()
                    .filter(entity -> entity instanceof Wolf || entity instanceof IronGolem).map(Mob.class::cast)
                    .toList();
            assertEquals(3, pack.size());
            Mob golem = pack.stream().filter(IronGolem.class::isInstance).findFirst().orElseThrow();
            s.place(s.enemy, 12.5, 12.5, 180);
            s.place(s.enemy2, -12.5, 12.5, 180);
            golem.teleport(new Location(s.world, 0.5, FlatWorld.SURFACE, 3.0));
            s.aimAt(s.owner, golem);
            s.clearHits();
            s.assertSucceeded(s.active(), "잡기 메치기 on a golem");
            s.ticks(30);
            assertTrue(s.damageBy(s.owner, golem) > 0, "an enemy summon can be thrown");
            s.place(s.owner, 0.5, 0.5, 0);
            golem.teleport(new Location(s.world, 0.5, FlatWorld.SURFACE, 6.5));
            s.ticks(2);
            s.aimAt(s.owner, golem);
            s.assertSucceeded(s.ultimate(), "파일드라이버 on a golem");
            s.ticks(80);
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.assertNoProblems();
            s.finishMatch();
            s.assertNoProblems();
            s.assertCleanWorld();
        }
        try (Scenario s = Scenario.builder("fighter").teams().allyAbility("summoner").start()) {
            s.ticks(40);
            s.crossfireAlly();
            s.assertSucceeded(s.match.useActive(s.ally()), "늑대 무리");
            s.ticks(30);
            List<Mob> pack = s.world.getEntities().stream().filter(Wolf.class::isInstance).map(Mob.class::cast)
                    .toList();
            for (Mob wolf : pack) {
                wolf.teleport(new Location(s.world, 0.5, FlatWorld.SURFACE, 2.5));
            }
            s.place(s.enemy, 0.5, 30.5, 180);
            s.place(s.enemy2, 30.5, 0.5, 180);
            s.aimAt(s.owner, pack.getFirst());
            s.clearHits();
            assertFalse(s.active().succeeded(), "an allied wolf is never grabbed");
            for (Mob wolf : pack) {
                assertEquals(0, s.damageTaken(wolf), EPSILON);
            }
            s.finishClean();
        }
    }

    // ------------------------------------------------------------------ gambler

    @Test
    void borrowedPiledriverWorksOnAGambler() {
        try (Scenario s = gambler()) {
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(borrow(s), "borrowed 파일드라이버");
            boolean hurt = false;
            boolean rubble = false;
            for (int i = 0; i < 300; i++) {
                s.ticks(1);
                assertNobodyEntombed(s);
                hurt |= s.damageBy(s.owner, s.enemy) > 0;
                rubble |= s.h.hub.tempBlocks().size() > 0;
            }
            assertTrue(hurt, "the borrowed piledriver lands");
            assertTrue(rubble, "and leaves its crater");
            assertEquals(s.ctx().paramsFor("fighter").num("ult_damage"), s.damageBy(s.owner, s.enemy), EPSILON,
                    "the fighter's numbers");
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "never hurts the gambler: " + s.hits());
            assertNoControlModifiers(s.owner, "gambler");
            for (TestPlayer target : s.enemies()) {
                assertNoControlModifiers(target, "borrowed");
                assertFalse(s.ctx().isSilenced(target));
            }
            assertNoDisplays(s);
            assertEquals(0, s.h.hub.tempBlocks().size(), "temporary blocks restored");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void gamblerDyingMidBorrowedPiledriverLeavesNothingBehind() {
        try (Scenario s = gambler()) {
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(borrow(s), "borrowed 파일드라이버");
            s.ticks(10);
            assertOwnerDeathCleansUp(s);
            assertTrue(s.hitsOf(s.enemy, DamageCause.FALL).isEmpty(), "the dropped enemy floats down: " + s.hits());
        }
    }

    /**
     * Players whose real position is their client's: every client tick each moves by the last velocity the server
     * sent it (then gravity, slow falling and drag, vanilla style, over a flat floor), and the server sees where it is
     * {@code lag} ticks late. The server also falls with what it sees, like vanilla's
     * {@code ServerPlayer#doCheckFallDamage}: each descent it hears adds to the fall distance (slow falling keeps it at
     * zero, like the harness's own physics), and the move that lands charges fall damage for it. The harness's own
     * physics is off while they run.
     */
    private static final class LaggyClients {

        /** Marks the velocity the server last sent, to tell whether a skill sent a new one during a tick. */
        private static final double MARK = 1.0E-9;
        private final Scenario s;
        private final List<Client> clients = new ArrayList<>();

        LaggyClients(Scenario s, int lag, TestPlayer... players) {
            this.s = s;
            s.world.physics().setEnabled(false);
            for (TestPlayer player : players) {
                clients.add(new Client(player, lag));
            }
        }

        /** One server tick, then one client tick of every player with whatever the server sent it. */
        void tick() {
            for (Client client : clients) {
                client.mark();
            }
            s.ticks(1);
            for (Client client : clients) {
                client.step();
            }
        }

        /** Where {@code player} really is (its client). */
        Location at(TestPlayer player) {
            for (Client client : clients) {
                if (client.player.equals(player)) {
                    return client.at.clone();
                }
            }
            throw new IllegalArgumentException(player.getName());
        }

        /** Back to the harness's physics, every player where its client really is. */
        void close() {
            for (Client client : clients) {
                Location real = client.at.clone();
                Location look = client.player.getLocation();
                real.setYaw(look.getYaw());
                real.setPitch(look.getPitch());
                client.player.teleport(real);
                client.player.setVelocity(new Vector());
            }
            s.world.physics().setEnabled(true);
        }

        private static final class Client {
            private final TestPlayer player;
            private final Deque<Location> late = new ArrayDeque<>();
            private final Location at;
            private final Vector velocity = new Vector();
            private Vector sent;
            private Vector marked = new Vector();

            Client(TestPlayer player, int lag) {
                this.player = player;
                this.at = player.getLocation();
                this.sent = player.getVelocity().clone();
                this.velocity.copy(sent);
                for (int i = 0; i < lag; i++) {
                    late.addLast(at.clone());
                }
            }

            void mark() {
                marked = sent.clone().setX(sent.getX() + MARK);
                player.setVelocity(marked);
            }

            void step() {
                Vector now = player.getVelocity();
                if (Double.compare(now.getX(), marked.getX()) != 0 || Double.compare(now.getY(), marked.getY()) != 0
                        || Double.compare(now.getZ(), marked.getZ()) != 0) {
                    sent = now.clone();
                    velocity.copy(now);
                }
                at.add(velocity);
                boolean grounded = at.getY() <= FlatWorld.SURFACE;
                if (grounded) {
                    at.setY(FlatWorld.SURFACE);
                }
                double gravity = player.hasPotionEffect(PotionEffectType.SLOW_FALLING) && velocity.getY() <= 0
                        ? 0.01 : 0.08;
                double vy = (velocity.getY() - gravity) * 0.98;
                double friction = grounded ? 0.546 : 0.91;
                velocity.setX(velocity.getX() * friction).setY(grounded && vy < 0 ? 0 : vy)
                        .setZ(velocity.getZ() * friction);
                late.addLast(at.clone());
                Location seen = late.removeFirst();
                Location look = player.getLocation();
                seen.setYaw(look.getYaw());
                seen.setPitch(look.getPitch());
                float fall = player.getFallDistance();
                player.teleport(seen);
                hear(seen.getY() - look.getY(), fall);
            }

            /** What the server makes of a move it hears that went {@code dy} up or down: the fall, and a landing. */
            private void hear(double dy, float fallBefore) {
                float fall = fallBefore;
                if (player.hasPotionEffect(PotionEffectType.SLOW_FALLING)) {
                    fall = 0;
                } else if (dy < 0) {
                    fall += (float) -dy;
                }
                if (player.getLocation().getY() <= FlatWorld.SURFACE + 1.0E-6 && fall > 0) {
                    AttributeInstance safe = player.getAttribute(Attribute.SAFE_FALL_DISTANCE);
                    int damage = (int) Math.ceil(fall - (safe != null ? safe.getValue() : 3.0));
                    fall = 0;
                    if (damage > 0) {
                        player.damage(damage, DamageSource.builder(DamageType.FALL).build());
                    }
                }
                player.setFallDistance(fall);
            }
        }
    }

    /**
     * The fighter and the held enemy play on clients the server hears from a round trip late. Steering them by those
     * lagging positions (the path's own step plus half the error the server saw) was a delayed correction: from
     * 150 ms on it flung the enemy blocks over and past the swing (3.7 blocks up, 4.3 behind instead of 1.0 at 150 ms;
     * 4.4 up and 7.4 behind at 250 ms), broke the throw and never slammed it.
     */
    @ParameterizedTest(name = "{0} ms")
    @ValueSource(ints = {0, 150, 250})
    void suplexSwingsALaggingEnemyAlongTheArcAndSlamsItWhereItLands(int ping) {
        try (Scenario s = Scenario.ffa("fighter")) {
            s.place(s.enemy, 0.5, 2.5, 180);
            s.place(s.enemy2, 10.5, 10.5, 180);
            s.owner.setPing(ping);
            s.enemy.setPing(ping);
            s.aimAt(s.owner, s.enemy);
            Location owner = s.owner.getLocation();
            s.assertSucceeded(s.active(), "잡기 메치기");
            LaggyClients clients = new LaggyClients(s, ping / 50, s.owner, s.enemy);
            double highest = FlatWorld.SURFACE;
            double farthestBack = owner.getZ();
            int landed = -1;
            int slammed = -1;
            boolean stunned = false;
            for (int i = 0; i < 30; i++) {
                clients.tick();
                Location enemy = clients.at(s.enemy);
                highest = Math.max(highest, enemy.getY());
                farthestBack = Math.min(farthestBack, enemy.getZ());
                if (landed < 0 && i > 4 && enemy.getY() <= FlatWorld.SURFACE + 1.0E-6) {
                    landed = i;
                }
                if (slammed < 0 && s.damageBy(s.owner, s.enemy) > 0) {
                    slammed = i;
                    stunned = rooted(s.enemy);
                }
            }
            Location down = clients.at(s.enemy);
            clients.close();
            double lifted = highest - FlatWorld.SURFACE;
            assertTrue(lifted >= s.owner.getHeight(), ping + " ms: swung over the owner's head: " + lifted);
            assertTrue(lifted < s.owner.getHeight() + 0.3, ping + " ms: but no higher: " + lifted);
            double behind = owner.getZ() - down.getZ();
            assertTrue(behind > 1.0 && behind < 2.0, ping + " ms: slammed down right behind the owner: " + behind);
            assertEquals(0.5, down.getX(), 0.1, ping + " ms: in line with the throw");
            assertTrue(owner.getZ() - farthestBack < behind + 0.1, ping + " ms: never flung past where it lands: "
                    + (owner.getZ() - farthestBack));
            assertEquals(s.param("suplex_damage"), s.damageBy(s.owner, s.enemy), EPSILON, ping + " ms: slammed once");
            assertTrue(landed >= 0 && slammed >= 0 && slammed <= landed + 1,
                    ping + " ms: the slam hits as the body hits the floor (landed " + landed + ", hit " + slammed
                            + ")");
            assertTrue(stunned, ping + " ms: stunned on the mat");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.ticks(40);
            assertTrue(s.hitsOf(s.enemy, DamageCause.FALL).isEmpty(), ping + " ms: no fall damage: " + s.hits());
            s.finishClean();
        }
    }

    /**
     * The piledriver with both bodies on lagging clients. Steered by where the server last heard of them, the owner
     * shot up to 11.6 blocks instead of 6 at 150 ms (16.6 at 250 ms, where the enemy was carried off to 18 blocks and
     * let go), the enemy swung around it and was driven into the floor 3.5 blocks off the spot, four ticks after it
     * hit the floor.
     */
    @ParameterizedTest(name = "{0} ms")
    @ValueSource(ints = {0, 150, 250})
    void piledriverCarriesLaggingPlayersExactlyAlongItsPath(int ping) {
        try (Scenario s = Scenario.ffa("fighter")) {
            s.place(s.enemy, 0.5, 2.5, 180); // close enough to grab without a dash
            s.place(s.enemy2, 12.5, 0.5, 270);
            s.owner.setPing(ping);
            s.enemy.setPing(ping);
            s.aimAt(s.owner, s.enemy);
            Location takeoff = s.owner.getLocation();
            s.assertSucceeded(s.ultimate(), "파일드라이버");
            LaggyClients clients = new LaggyClients(s, ping / 50, s.owner, s.enemy);
            double ownerTop = FlatWorld.SURFACE;
            double ownerDrift = 0;
            boolean overhead = false;
            int landed = -1;
            int driven = -1;
            for (int i = 0; i < 40; i++) {
                clients.tick();
                Location owner = clients.at(s.owner);
                Location enemy = clients.at(s.enemy);
                ownerTop = Math.max(ownerTop, owner.getY());
                if (driven < 0 && s.damageBy(s.owner, s.enemy) > 0) {
                    driven = i; // the owner rebounds off the body from here on
                }
                if (driven < 0) {
                    ownerDrift = Math.max(ownerDrift, horizontalDistance(owner, takeoff));
                }
                overhead |= enemy.getY() - owner.getY() >= s.owner.getHeight() - 0.1
                        && horizontalDistance(owner, enemy) < 0.5;
                if (landed < 0 && i > 8 && enemy.getY() <= FlatWorld.SURFACE + 1.0E-6) {
                    landed = i;
                }
            }
            Location down = clients.at(s.enemy);
            clients.close();
            double leap = ownerTop - FlatWorld.SURFACE;
            assertTrue(leap > s.param("ult_height") - 0.3 && leap < s.param("ult_height") + 0.3,
                    ping + " ms: the leap is " + s.param("ult_height") + " blocks high: " + leap);
            assertTrue(ownerDrift < 0.1, ping + " ms: straight up and down: " + ownerDrift);
            assertTrue(overhead, ping + " ms: the enemy rides over the owner's head");
            assertEquals(s.param("ult_damage"), s.damageBy(s.owner, s.enemy), EPSILON, ping + " ms: driven in once");
            assertEquals(1, s.hitsBy(s.owner, s.enemy).size());
            assertTrue(landed >= 0 && driven >= 0 && driven <= landed + 1,
                    ping + " ms: the drive hits as the body hits the floor (landed " + landed + ", hit " + driven
                            + ")");
            double ahead = down.getZ() - takeoff.getZ();
            assertTrue(ahead > 0.5 && ahead < 1.1, ping + " ms: driven into the floor right in front: " + ahead);
            assertEquals(0.5, down.getX(), 0.1, ping + " ms: in line with the owner");
            assertTrue(s.hitsOf(s.owner, DamageCause.FALL).isEmpty(), ping + " ms: no fall damage: " + s.hits());
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.ticks(s.paramTicks("crater_duration") + 40);
            assertTrue(s.hitsOf(s.enemy, DamageCause.FALL).isEmpty(), ping + " ms: the drive replaces the fall: "
                    + s.hits());
            s.assertNoProblems();
            s.finishClean();
        }
    }

    /**
     * With no room beside it the piledriver drives the enemy down on the owner's shoulders, and the enemy drops the
     * last couple of blocks off them after the impact. The impact lands where the lagging clients really are, while
     * the server still has a round trip of their descent to hear: with the safety net gone and nothing keeping the
     * fall at zero any more, the enemy took fall damage on top of the drive at 150 and 250 ms.
     */
    @ParameterizedTest(name = "{0} ms")
    @ValueSource(ints = {0, 150, 250})
    void piledriverOnTheShouldersOfLaggingPlayersReplacesTheFall(int ping) {
        try (Scenario s = Scenario.ffa("fighter")) {
            // A ceiling six blocks up with a single hole right above the owner: room to leap, none beside it.
            int ceiling = FlatWorld.FLOOR_Y + 7;
            slab(s.world, -4, 4, ceiling, -4, 6, Material.STONE);
            s.world.setBlock(0, ceiling, 0, Material.AIR);
            s.place(s.enemy, 0.5, 2.5, 180); // close enough to grab without a dash
            s.place(s.enemy2, 12.5, 0.5, 270);
            s.owner.setPing(ping);
            s.enemy.setPing(ping);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "파일드라이버");
            LaggyClients clients = new LaggyClients(s, ping / 50, s.owner, s.enemy);
            double enemyTop = FlatWorld.SURFACE;
            for (int i = 0; i < 60; i++) {
                clients.tick();
                enemyTop = Math.max(enemyTop, clients.at(s.enemy).getY());
            }
            Location down = clients.at(s.enemy);
            clients.close();
            assertTrue(enemyTop > ceiling, ping + " ms: carried up through the hole: " + enemyTop);
            assertEquals(FlatWorld.SURFACE, down.getY(), 1.0E-3, ping + " ms: down on the floor");
            assertEquals(s.param("ult_damage"), s.damageBy(s.owner, s.enemy), EPSILON,
                    ping + " ms: driven down on the shoulders once");
            assertTrue(s.hitsOf(s.enemy, DamageCause.FALL).isEmpty(), ping + " ms: no fall damage on top: " + s.hits());
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.ticks(s.paramTicks("crater_duration") + 20);
            slab(s.world, -4, 4, ceiling, -4, 6, Material.AIR);
            s.finishClean();
        }
    }
}
