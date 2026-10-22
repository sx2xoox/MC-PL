package io.github.sx2xoox.mcpl.game.superpower.scenario;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import io.github.sx2xoox.mcpl.game.superpower.abilities.CupidAbility;
import io.github.sx2xoox.mcpl.game.superpower.abilities.HydraAbility;
import io.github.sx2xoox.mcpl.game.superpower.abilities.MedusaAbility;
import io.github.sx2xoox.mcpl.game.superpower.abilities.MinotaurAbility;
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
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Arrow;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 신화 계열 (새 능력): 메두사, 큐피드, 미노타우로스, 히드라. Every ability through a whole match, the owner dying in the
 * middle of each skill, team mode with an ally in the line of fire, the standalone ultimates borrowed by a Gambler, the
 * real-client rules (unrotated displays, exact landings, no stale velocity) and each ability's own mechanics.
 */
class MythNewAbilitiesTest {

    private static final double EPSILON = 1.0E-6;
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z0-9_]+)}");

    static Stream<String> abilities() {
        return Stream.of("medusa", "cupid", "minotaur", "hydra");
    }

    /** The abilities whose ultimate the Gambler may borrow. */
    static Stream<String> standalone() {
        return Stream.of("medusa", "cupid", "hydra");
    }

    /** Displays a passive keeps on its owner for the whole match (hair, wings, horns, heads). */
    private static final Map<String, Integer> PASSIVE_DISPLAYS = Map.of("medusa", 5, "cupid", 6, "minotaur", 4,
            "hydra", 2);

    // ------------------------------------------------------------------ helpers

    private static <T extends Entity> List<T> entities(Scenario s, Class<T> type) {
        return s.world.getEntities().stream().filter(type::isInstance).map(type::cast).toList();
    }

    private static List<BlockDisplay> blocks(Scenario s, Material material) {
        return entities(s, BlockDisplay.class).stream().filter(d -> d.getBlock().getMaterial() == material).toList();
    }

    /** Only the displays the passive keeps for the whole match are left. */
    private static void assertOnlyPassiveDisplays(Scenario s, String id) {
        List<Display> displays = entities(s, Display.class);
        assertTrue(displays.size() <= PASSIVE_DISPLAYS.getOrDefault(id, 0), id + ": only the passive's displays stay: "
                + displays.stream().map(d -> d.getType() + "@" + d.getLocation().toVector()).toList());
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

    private static Scenario gambler(boolean teams) {
        Scenario.Builder builder = Scenario.builder("gambler").param("dodge_chance", 0).param("crit_chance", 0);
        return (teams ? builder.teams() : builder).start();
    }

    /** No ultimate gauge from damage or time, so gauge numbers in a test come from the ability alone. */
    private static Scenario.Builder noGaugeGain(Scenario.Builder builder) {
        return builder.configure(h -> {
            h.settings().set("ultimate.per-damage-dealt", 0);
            h.settings().set("ultimate.per-damage-taken", 0);
            h.settings().set("ultimate.per-second", 0);
        });
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

    private static boolean rooted(Player player) {
        return player.getAttribute(Attribute.MOVEMENT_SPEED).getModifiers().stream()
                .anyMatch(modifier -> modifier.getAmount() <= -1.0 + EPSILON)
                && player.getAttribute(Attribute.JUMP_STRENGTH).getModifiers().stream()
                .anyMatch(modifier -> modifier.getAmount() <= -1.0 + EPSILON);
    }

    private static boolean disarmed(Player player) {
        return player.getAttribute(Attribute.ATTACK_DAMAGE).getModifiers().stream()
                .anyMatch(modifier -> modifier.getAmount() <= -1.0 + EPSILON);
    }

    /** {@code player} looks along {@code yaw} (0 = +Z, 180 = -Z), level. */
    private static void face(Player player, float yaw) {
        Location at = player.getLocation();
        at.setYaw(yaw);
        at.setPitch(0);
        player.teleport(at);
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

    private static void assertUnrotated(Entity display, String what) {
        assertEquals(0, display.getLocation().getYaw(), 1.0E-3, what + ": display yaw");
        assertEquals(0, display.getLocation().getPitch(), 1.0E-3, what + ": display pitch");
    }

    /** {@code local} turned by the display's left rotation (the test doubles store it faithfully). */
    private static Vector3f rotated(Display display, Vector3f local) {
        return new Quaternionf(display.getTransformation().getLeftRotation()).transform(new Vector3f(local));
    }

    /** The center of a block display's box relative to the display's own position. */
    private static Vector3f boxCenter(BlockDisplay display) {
        Transformation t = display.getTransformation();
        Vector3f half = new Quaternionf(t.getRightRotation()).transform(new Vector3f(0.5f)).mul(t.getScale());
        return new Vector3f(t.getTranslation()).add(rotated(display, half));
    }

    /** The minotaur's ultimate hurts through its labyrinth rush: once the walls stand, rush at the enemy. */
    private static void rushIfMinotaur(Scenario s) {
        if (s.abilityId.equals("minotaur")) {
            s.ticks(25);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "미궁 돌진");
        }
    }

    // ================================================================== every ability

    @Test
    void descriptionsOnlyReferenceDeclaredParams() {
        for (AbilityInfo info : List.of(MedusaAbility.INFO, CupidAbility.INFO, MinotaurAbility.INFO,
                HydraAbility.INFO)) {
            for (AbilityInfo.Skill skill : List.of(info.passive(), info.active(), info.ultimate())) {
                Matcher matcher = PLACEHOLDER.matcher(skill.description());
                while (matcher.find()) {
                    assertTrue(info.params().containsKey(matcher.group(1)),
                            info.id() + " " + skill.name() + " uses an undeclared param " + matcher.group());
                }
                assertFalse(skill.name().contains("TODO") || skill.description().contains("TODO"), info.id());
            }
        }
        assertTrue(MedusaAbility.INFO.ultimateStandalone(), "the gorgon eye needs nothing from the passive");
        assertTrue(CupidAbility.INFO.ultimateStandalone(), "the red string needs nothing from the passive");
        assertFalse(MinotaurAbility.INFO.ultimateStandalone(), "the labyrinth rush is the minotaur's own active");
        assertTrue(HydraAbility.INFO.ultimateStandalone(), "the nine heads need nothing from the passive");
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
            assertTrue(s.damageBy(s.owner, s.enemy) > 0, id + " active hurts the aimed enemy");
            assertEquals(0, s.damageTaken(s.owner), EPSILON, id + " active never hurts its owner: " + s.hits());

            AbilityLifecycleTest.prepareUltimate(s);
            s.healAll();
            s.clearHits();
            s.assertSucceeded(s.ultimate(), id + " ultimate");
            rushIfMinotaur(s);
            s.ticks(300);
            assertTrue(s.damageBy(s.owner, s.enemy) + s.damageBy(s.owner, s.enemy2) > 0, id + " ultimate hurts");
            assertEquals(0, s.damageTaken(s.owner), EPSILON, id + " ultimate never hurts its owner: " + s.hits());
            s.assertNoProblems();
            assertOnlyPassiveDisplays(s, id);
            for (TestPlayer enemy : s.enemies()) {
                assertModifierFree(enemy, id);
                assertFalse(s.ctx().isSilenced(enemy), id + ": " + enemy.getName() + " is not silenced any more");
            }
            assertEquals(0, s.h.hub.tempBlocks().size(), id + ": every temporary block is back");
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
            if (id.equals("minotaur")) {
                AbilityLifecycleTest.watchAlly(s, allyStart, 25);
                s.aimAt(s.owner, s.enemy);
                s.assertSucceeded(s.active(), "미궁 돌진 past the ally");
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
            s.ticks(3); // mid-skill: the gaze winding up, the arrow flying, the cloth waving, the necks lashing
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
        }
        assertModifierFree(s.owner, id);
        s.assertNoProblems();
        s.finishClean();
    }

    // ================================================================== borrowed by the gambler

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
            s.ticks(25);
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
            assertEquals(0, s.h.hub.tempBlocks().size(), id + ": temporary blocks");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    // ================================================================== 메두사 medusa

    @Test
    void medusaHairWrithesForEveryoneButHerself() {
        try (Scenario s = Scenario.ffa("medusa")) {
            s.ticks(10);
            List<Display> hair = entities(s, Display.class);
            assertEquals(5, hair.size(), "five snakes in her hair");
            for (Display snake : hair) {
                assertUnrotated(snake, "snake");
                assertFalse(s.owner.canSee(snake), "Medusa does not see her own snakes (they would block her view)");
                assertTrue(s.enemy.canSee(snake), "the enemies do");
                assertTrue(snake.getLocation().distance(s.owner.getEyeLocation()) < 0.5, "on her head");
            }
            s.place(s.owner, 4.5, 2.5, 90);
            s.ticks(2);
            for (Display snake : hair) {
                assertTrue(snake.getLocation().distance(s.owner.getEyeLocation()) < 0.5, "the hair follows her");
            }
            s.finishClean();
        }
    }

    @Test
    void medusaSnakesBiteMeleeAttackersOncePerCooldown() {
        try (Scenario s = Scenario.ffa("medusa")) {
            s.place(s.enemy, 0.5, 2.0, 180);
            s.melee(s.enemy, s.owner, 2);
            s.ticks(6);
            assertEquals(s.param("snake_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "the snake bit back");
            assertTrue(s.enemy.hasPotionEffect(PotionEffectType.POISON), "with venom");
            assertFalse(s.enemy2.hasPotionEffect(PotionEffectType.POISON), "only the attacker");

            s.clearHits();
            s.melee(s.enemy, s.owner, 2);
            s.ticks(6);
            assertEquals(0, s.damageBy(s.owner, s.enemy), EPSILON, "no second bite within the cooldown");

            s.ticks(s.paramTicks("snake_cooldown"));
            s.clearHits();
            shoot(s, s.enemy2, s.owner, 2);
            s.ticks(6);
            assertEquals(0, s.damageBy(s.owner, s.enemy2), EPSILON, "arrows are not bitten");
            s.melee(s.enemy, s.owner, 2);
            s.ticks(6);
            assertEquals(s.param("snake_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "bitten again after it");
            s.ticks(10);
            assertEquals(5, entities(s, Display.class).size(), "the striking snake coiled back into the hair");
            s.finishClean();
        }
    }

    @Test
    void medusaGazeStonesOnlyThoseLookingAtHer() {
        try (Scenario s = Scenario.ffa("medusa")) {
            // enemy2 stands in the cone too, but looks away.
            s.place(s.enemy2, 1.5, 5.5, 0);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "석화의 시선");
            assertFalse(s.active().succeeded(), "on cooldown");
            int windup = s.paramTicks("gaze_windup");
            s.ticks(windup - 1);
            assertFalse(rooted(s.enemy), "nothing happens during the wind-up");
            s.ticks(2);
            assertTrue(rooted(s.enemy), "the enemy looking at Medusa is turned to stone");
            assertTrue(disarmed(s.enemy), "and cannot strike");
            assertTrue(s.ctx().isSilenced(s.enemy), "nor use skills");
            assertFalse(rooted(s.enemy2), "the one looking away is not");
            assertFalse(s.ctx().isSilenced(s.enemy2));
            assertTrue(s.enemy2.hasPotionEffect(PotionEffectType.SLOWNESS), "only its legs stiffen");
            s.ticks(3);
            assertEquals(5 + 5, entities(s, BlockDisplay.class).size(), "a statue of five stones around the enemy");

            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "no damage until the statue bursts");
            s.ticks(s.paramTicks("petrify_duration"));
            assertEquals(s.param("petrify_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "the statue bursts");
            assertFalse(rooted(s.enemy), "free again");
            assertFalse(s.ctx().isSilenced(s.enemy), "free again");
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON);
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.ticks(20);
            assertEquals(5, entities(s, Display.class).size(), "only the hair is left");
            s.finishClean();
        }
    }

    @Test
    void medusaGazeCanBeAvoidedByLookingAwayDuringTheWindup() {
        try (Scenario s = Scenario.ffa("medusa")) {
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "석화의 시선");
            s.ticks(2);
            face(s.enemy, 0); // turns its back on her
            s.ticks(s.paramTicks("gaze_windup") + 2);
            assertFalse(rooted(s.enemy), "it looked away in time");
            assertTrue(s.enemy.hasPotionEffect(PotionEffectType.SLOWNESS), "the legs still stiffen");
            s.ticks(60);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON);
            s.finishClean();
        }
    }

    @Test
    void medusaGazeFollowsWhereSheTurnsDuringTheWindupAndStopsAtWalls() {
        try (Scenario s = Scenario.ffa("medusa")) {
            s.place(s.enemy2, -4.5, 0.5, 270); // to her right side, looking at her
            s.world.wallAlongX(-3, 3, 3, 3, Material.STONE);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "석화의 시선");
            s.ticks(2);
            face(s.owner, 90); // turns to enemy2 (west)
            s.ticks(s.paramTicks("gaze_windup") + 2);
            assertTrue(rooted(s.enemy2), "the gaze goes where she looks when it fires");
            assertFalse(rooted(s.enemy), "behind her now, and behind a wall anyway");
            s.ticks(60);
            s.world.wallAlongX(-3, 3, 3, 3, Material.AIR);
            s.finishClean();
        }
    }

    @Test
    void medusaPoisonedVictimsStayStoneLonger() {
        try (Scenario s = Scenario.ffa("medusa")) {
            s.enemy.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 200, 0));
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "석화의 시선");
            s.ticks(s.paramTicks("gaze_windup") + 1);
            assertTrue(rooted(s.enemy));
            s.ticks(s.paramTicks("petrify_duration") + 1);
            assertTrue(rooted(s.enemy), "venom keeps it stone a little longer");
            assertEquals(0, s.damageTaken(s.enemy), EPSILON);
            s.ticks(s.paramTicks("venom_petrify") + 1);
            assertFalse(rooted(s.enemy));
            assertEquals(s.param("petrify_damage"), s.damageBy(s.owner, s.enemy), EPSILON);
            s.finishClean();
        }
    }

    @Test
    void medusaStatueStandsOnTheVictimFacingItsWayWithUnrotatedDisplays() {
        try (Scenario s = Scenario.ffa("medusa")) {
            s.place(s.owner, 0.5, 0.5, 270); // looks east (+X)
            s.place(s.enemy, 4.5, 0.5, 90); // looks west, at her
            s.place(s.enemy2, -6.5, 8.5, 0);
            s.assertSucceeded(s.active(), "석화의 시선");
            s.ticks(s.paramTicks("gaze_windup") + 8);
            List<BlockDisplay> arms = blocks(s, Material.ANDESITE);
            assertEquals(2, arms.size(), "two stone arms");
            for (BlockDisplay arm : arms) {
                assertUnrotated(arm, "arm");
                Vector3f center = boxCenter(arm);
                assertTrue(Math.abs(center.z()) > 0.3 && Math.abs(center.x()) < 0.05,
                        "the arms are at the sides of a victim facing west: " + center);
                assertEquals(s.enemy.getLocation().getX(), arm.getLocation().getX(), 1.0E-6, "on the victim");
            }
            BlockDisplay head = blocks(s, Material.POLISHED_ANDESITE).getFirst();
            assertTrue(boxCenter(head).y() > 1.5, "the head on top: " + boxCenter(head));
            for (BlockDisplay piece : entities(s, BlockDisplay.class)) {
                assertUnrotated(piece, piece.getBlock().getMaterial().name());
            }
            s.ticks(60);
            s.finishClean();
        }
    }

    @Test
    void medusaNeverPetrifiesOrMarksAHiddenEnemy() {
        try (Scenario s = Scenario.ffa("medusa")) {
            s.place(s.enemy2, 9.5, -6.5, 0);
            s.owner.hidePlayer(s.ctx().plugin(), s.enemy);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "석화의 시선");
            s.ticks(s.paramTicks("gaze_windup") + 4);
            assertFalse(rooted(s.enemy), "a hidden enemy is not seen, so not stared at");
            assertEquals(5, blocks(s, Material.GREEN_CONCRETE).size() + blocks(s, Material.LIME_CONCRETE).size(),
                    "only the hair");
            assertTrue(blocks(s, Material.STONE).isEmpty(), "no statue gives it away");
            s.owner.showPlayer(s.ctx().plugin(), s.enemy);
            s.finishClean();
        }
    }

    @Test
    void medusaGorgonEyeStonesThoseWhoKeepStaring() {
        try (Scenario s = noGaugeGain(Scenario.builder("medusa")).start()) {
            s.place(s.enemy2, 3.5, 6.5, 0); // looks away from her
            s.assertSucceeded(s.ultimate(), "고르곤의 눈");
            assertFalse(s.ultimate().succeeded(), "already open");
            s.ticks(2);
            List<BlockDisplay> eye = entities(s, BlockDisplay.class).stream()
                    .filter(d -> d.getBillboard() == Display.Billboard.CENTER).toList();
            assertEquals(4, eye.size(), "a billboarded eye of four layers");
            for (BlockDisplay layer : eye) {
                assertUnrotated(layer, "eye layer");
                assertTrue(layer.getLocation().getY() > s.owner.getEyeLocation().getY() + 1, "over her head");
            }
            int stare = s.paramTicks("stare_time");
            s.ticks(stare / 2);
            assertFalse(rooted(s.enemy), "still staring");
            assertFalse(blocks(s, Material.COBBLESTONE).isEmpty(), "the stone creeps up its legs");
            s.ticks(stare / 2 + 2);
            assertTrue(rooted(s.enemy), "stared long enough: stone");
            assertTrue(s.ctx().isSilenced(s.enemy));
            assertFalse(rooted(s.enemy2), "the one that looked away is spared");
            s.ticks(s.paramTicks("ult_petrify") + 2);
            assertEquals(s.param("ult_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "the statue bursts");
            assertFalse(rooted(s.enemy));
            s.ticks(30);
            assertFalse(rooted(s.enemy), "once per enemy and eye");
            assertEquals(s.param("ult_damage"), s.damageBy(s.owner, s.enemy), EPSILON);
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON);
            s.ticks(s.paramTicks("ult_duration"));
            assertEquals(5, entities(s, Display.class).size(), "the eye closed; only the hair is left");
            s.finishClean();
        }
    }

    @Test
    void medusaGorgonStoneThawsWhenTheViewerLooksAway() {
        try (Scenario s = Scenario.ffa("medusa")) {
            s.place(s.enemy2, 9.5, -6.5, 0);
            s.assertSucceeded(s.ultimate(), "고르곤의 눈");
            int stare = s.paramTicks("stare_time");
            s.ticks(stare - 4);
            float crept = blocks(s, Material.STONE).getFirst().getTransformation().getScale().y();
            assertTrue(crept > 0.1f, "the stone has crept up to the chest: " + crept);
            face(s.enemy, 0);
            s.ticks(stare);
            assertFalse(rooted(s.enemy), "it looked away just in time");
            float thawed = blocks(s, Material.STONE).getFirst().getTransformation().getScale().y();
            assertTrue(thawed < crept - 0.1f, "the stone thaws: " + crept + " -> " + thawed);
            face(s.enemy, 180);
            s.ticks(stare / 2);
            assertFalse(rooted(s.enemy), "the stare built up again from where it had thawed to");
            s.ticks(stare);
            assertTrue(rooted(s.enemy), "and caught it in the end");
            s.ticks(s.paramTicks("ult_duration"));
            s.finishClean();
        }
    }

    // ================================================================== 큐피드 cupid

    private static List<BlockDisplay> feathers(Scenario s) {
        return entities(s, BlockDisplay.class).stream()
                .filter(d -> d.getBlock().getMaterial() == Material.WHITE_WOOL
                        || d.getBlock().getMaterial() == Material.PINK_WOOL)
                .filter(d -> d.getTransformation().getScale().y() > 0.1f)
                .toList();
    }

    /** A feather's middle relative to the owner's feet. */
    private static Vector featherOffset(Scenario s, BlockDisplay feather) {
        Vector3f center = boxCenter(feather);
        return feather.getLocation().toVector().add(new Vector(center.x(), center.y(), center.z()))
                .subtract(s.owner.getLocation().toVector());
    }

    @Test
    void cupidWingsFoldOnTheGroundAndSpreadInAGlide() {
        try (Scenario s = Scenario.ffa("cupid")) {
            s.place(s.owner, 0.5, 0.5, 90); // faces west (-X): her back is to the east
            s.ticks(4);
            List<BlockDisplay> wings = feathers(s);
            assertEquals(6, wings.size(), "three feathers a wing");
            double spreadOnGround = 0;
            for (BlockDisplay feather : wings) {
                assertUnrotated(feather, "feather");
                Vector offset = featherOffset(s, feather);
                assertTrue(offset.getX() > 0.1, "folded along her back (east): " + offset);
                assertTrue(offset.getY() < 1.35, "hanging down: " + offset);
                spreadOnGround = Math.max(spreadOnGround, Math.abs(offset.getZ()));
            }
            assertFalse(s.owner.hasPotionEffect(PotionEffectType.SLOW_FALLING), "no glide on the ground");

            s.place(s.owner, 0.5, 80, 0.5, 90, 0);
            s.ticks(10);
            assertTrue(s.owner.hasPotionEffect(PotionEffectType.SLOW_FALLING), "gliding down");
            double spreadInAir = 0;
            for (BlockDisplay feather : feathers(s)) {
                assertUnrotated(feather, "feather");
                Vector offset = featherOffset(s, feather);
                assertTrue(offset.getX() > -0.1, "the wings stay behind her: " + offset);
                spreadInAir = Math.max(spreadInAir, Math.abs(offset.getZ()));
            }
            assertTrue(spreadInAir > spreadOnGround + 0.3, "spread wide: " + spreadOnGround + " -> " + spreadInAir);
            s.tickUntilGrounded(s.owner, 2000);
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "a glide never ends in fall damage");
            s.ticks(4);
            assertFalse(s.owner.hasPotionEffect(PotionEffectType.SLOW_FALLING), "the wings fold on landing");
            s.finishClean();
        }
    }

    @Test
    void cupidPlainJumpIsNoGlide() {
        try (Scenario s = Scenario.ffa("cupid")) {
            s.owner.setVelocity(new Vector(0, 0.42, 0));
            s.ticks(4);
            assertFalse(s.owner.hasPotionEffect(PotionEffectType.SLOW_FALLING), "a hop does not open the wings");
            s.finishClean();
        }
    }

    @Test
    void cupidFlapsOnceAndOnlyAgainAfterTheCooldownWithAFreshVelocity() {
        try (Scenario s = Scenario.ffa("cupid")) {
            s.place(s.owner, 0.5, 70, 0.5, 0, 0);
            s.ticks(4);
            s.world.physics().setEnabled(false);
            s.owner.setVelocity(new Vector(1.2, -0.4, -0.9)); // what the server last sent, not her motion
            s.owner.setSneaking(true);
            s.ticks(1);
            Vector beat = s.owner.getVelocity();
            assertTrue(beat.getY() > 0.5, "the wings beat her up: " + beat);
            assertEquals(0, beat.getX(), 1.0E-6, "a fresh velocity: " + beat);
            assertTrue(beat.getZ() > 0.2 && beat.getZ() < 0.5, "a little forward (south): " + beat);
            s.world.physics().setEnabled(true);
            s.owner.setSneaking(false);
            s.ticks(10);
            s.owner.setSneaking(true);
            s.world.physics().setEnabled(false);
            s.owner.setVelocity(new Vector(0, -0.3, 0));
            s.ticks(1);
            assertTrue(s.owner.getVelocity().getY() < 0, "no second beat during the cooldown");
            s.world.physics().setEnabled(true);
            s.owner.setSneaking(false);
            s.tickUntilGrounded(s.owner, 2000);
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "no fall damage after the beat");
            s.finishClean();
        }
    }

    @Test
    void cupidCrouchingOnTheGroundDoesNotFlap() {
        try (Scenario s = Scenario.ffa("cupid")) {
            s.ticks(2);
            s.owner.setSneaking(true);
            s.ticks(2);
            assertEquals(FlatWorld.SURFACE, s.owner.getLocation().getY(), EPSILON, "still on the ground");
            s.owner.setSneaking(false);
            s.finishClean();
        }
    }

    @Test
    void cupidLoveArrowCharmsItsTargetWhoCannotHurtHer() {
        try (Scenario s = Scenario.ffa("cupid")) {
            s.place(s.enemy, 0.5, 8.5, 180);
            s.place(s.enemy2, 9.5, -6.5, 0);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "사랑의 화살");
            assertFalse(s.active().succeeded(), "on cooldown");
            s.h.tickUntil(() -> s.damageBy(s.owner, s.enemy) > 0, 20, "the arrow lands");
            assertEquals(s.param("arrow_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "a plain shot from the ground");
            double start = s.enemy.getLocation().distance(s.owner.getLocation());
            s.ticks(10);
            double closer = s.enemy.getLocation().distance(s.owner.getLocation());
            assertTrue(closer < start - 0.8, "the charmed enemy strolls to Cupid: " + start + " -> " + closer);
            assertEquals(1, entities(s, TextDisplay.class).size(), "a heart over its head");

            s.melee(s.enemy, s.owner, 6);
            shoot(s, s.enemy, s.owner, 6);
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "love will not let it hurt Cupid");
            s.melee(s.enemy2, s.owner, 2);
            assertEquals(2, s.damageTaken(s.owner), EPSILON, "others still can");

            s.ticks(s.paramTicks("charm_duration") + 5);
            s.clearHits();
            s.melee(s.enemy, s.owner, 3);
            assertEquals(3, s.damageTaken(s.owner), EPSILON, "the charm wore off");
            double stopped = s.enemy.getLocation().distance(s.owner.getLocation());
            s.ticks(10);
            assertEquals(stopped, s.enemy.getLocation().distance(s.owner.getLocation()), 0.05,
                    "no longer drawn to her");
            s.ticks(10);
            assertEquals(6, entities(s, Display.class).size(), "only the wings are left");
            s.finishClean();
        }
    }

    @Test
    void cupidCharmedEnemyStopsBesideHerAndNeverGetsAStaleVelocity() {
        try (Scenario s = Scenario.ffa("cupid")) {
            s.place(s.enemy2, 9.5, -6.5, 0);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "사랑의 화살");
            s.h.tickUntil(() -> s.damageBy(s.owner, s.enemy) > 0, 20, "the arrow lands");
            s.ticks(2);
            s.world.physics().setEnabled(false);
            s.enemy.setVelocity(new Vector(1.2, 0, 0.9)); // a stale knockback the server remembers
            s.ticks(1);
            Vector walk = s.enemy.getVelocity();
            assertTrue(walk.getZ() < 0 && Math.abs(walk.getX()) < 0.05, "a fresh stroll towards Cupid: " + walk);
            assertTrue(walk.length() <= 0.25, "at a walk: " + walk);
            s.world.physics().setEnabled(true);
            s.ticks(s.paramTicks("charm_duration"));
            double gap = s.enemy.getLocation().distance(s.owner.getLocation());
            assertTrue(gap > 1.2, "it stops beside her, not on her: " + gap);
            s.finishClean();
        }
    }

    @Test
    void cupidArrowShotInTheAirHitsHarderAndCharmsLonger() {
        try (Scenario s = Scenario.ffa("cupid")) {
            s.place(s.enemy2, 9.5, -6.5, 0);
            s.place(s.owner, 0.5, 67, 0.5, 0, 0);
            s.ticks(2);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "사랑의 화살 in the air");
            s.h.tickUntil(() -> s.damageBy(s.owner, s.enemy) > 0, 20, "the arrow lands");
            assertEquals(s.param("arrow_damage") * (1 + s.param("air_bonus") / 100), s.damageBy(s.owner, s.enemy),
                    EPSILON, "the air shot");
            s.ticks(s.paramTicks("charm_duration") + 2);
            s.melee(s.enemy, s.owner, 2);
            assertEquals(0, s.hitsBy(s.enemy, s.owner).size(), "still charmed thanks to the air shot");
            s.ticks(s.paramTicks("air_charm") + 2);
            s.melee(s.enemy, s.owner, 2);
            assertEquals(1, s.hitsBy(s.enemy, s.owner).size(), "then free");
            s.tickUntilGrounded(s.owner, 400);
            s.finishClean();
        }
    }

    @Test
    void cupidArrowFliesHeartFirstAndStopsAtWalls() {
        try (Scenario s = Scenario.ffa("cupid")) {
            s.place(s.owner, 0.5, 0.5, 90);
            s.place(s.enemy, -8.5, 0.5, 270);
            s.place(s.enemy2, 9.5, 9.5, 0);
            for (int z = -1; z <= 1; z++) {
                s.world.wallAlongX(-4, -4, z, 3, Material.STONE);
            }
            s.aimAt(s.owner, s.enemy);
            Vector aim = s.owner.getEyeLocation().getDirection();
            s.assertSucceeded(s.active(), "사랑의 화살 into a wall");
            s.ticks(2);
            BlockDisplay shaft = blocks(s, Material.BIRCH_PLANKS).getFirst();
            assertUnrotated(shaft, "shaft");
            Vector3f axis = rotated(shaft, new Vector3f(0, 0, 1));
            double along = axis.x() * aim.getX() + axis.y() * aim.getY() + axis.z() * aim.getZ();
            assertTrue(along > 0.98, "the arrow points along its flight (cos=" + along + ")");
            Vector3f tip = boxCenter(blocks(s, Material.PINK_CONCRETE).getFirst());
            Vector3f tail = boxCenter(blocks(s, Material.WHITE_WOOL).stream()
                    .filter(d -> d.getTransformation().getScale().y() < 0.05f).findFirst().orElseThrow());
            assertTrue(tip.x() < tail.x(), "heart tip in front, fletching behind: " + tip + " / " + tail);
            s.ticks(40);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "the wall took the arrow");
            assertEquals(6, entities(s, Display.class).size(), "the stuck arrow faded");
            for (int z = -1; z <= 1; z++) {
                s.world.wallAlongX(-4, -4, z, 3, Material.AIR);
            }
            s.finishClean();
        }
    }

    @Test
    void cupidRedStringPullsTwoEnemiesIntoEachOther() {
        try (Scenario s = noGaugeGain(Scenario.builder("cupid")).start()) {
            s.place(s.enemy, 0.5, 6.5, 180);
            s.place(s.enemy2, 6.5, 6.5, 180);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "붉은 실");
            s.ticks(2);
            assertEquals(1, blocks(s, Material.RED_WOOL).size(), "a red cord between them");
            BlockDisplay cord = blocks(s, Material.RED_WOOL).getFirst();
            assertUnrotated(cord, "cord");
            Vector3f across = rotated(cord, new Vector3f(0, 0, 1));
            assertTrue(Math.abs(across.x()) > 0.95, "the cord runs from one to the other: " + across);
            s.ticks(s.paramTicks("thread_delay") - 4);
            assertEquals(6.0, s.enemy.getLocation().distance(s.enemy2.getLocation()), 0.05, "taut, not pulling yet");
            assertEquals(0, s.damageTaken(s.enemy) + s.damageTaken(s.enemy2), EPSILON);
            s.h.tickUntil(() -> s.damageBy(s.owner, s.enemy) > 0, 40, "the collision");
            // They met (1.3 apart) and the burst already bounced each about half a block back out.
            double gap = s.enemy.getLocation().distance(s.enemy2.getLocation());
            assertTrue(gap < 2.6, "they met: " + gap);
            assertTrue(s.enemy.getLocation().getX() > 1.5 && s.enemy2.getLocation().getX() < 5.5,
                    "halfway, both pulled");
            assertEquals(s.param("ult_damage"), s.damageBy(s.owner, s.enemy), EPSILON);
            assertEquals(s.param("ult_damage"), s.damageBy(s.owner, s.enemy2), EPSILON);
            assertTrue(s.enemy.hasPotionEffect(PotionEffectType.SLOWNESS));
            assertTrue(s.enemy2.hasPotionEffect(PotionEffectType.SLOWNESS));
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.ticks(30);
            assertEquals(6, entities(s, Display.class).size(), "cord and heart are gone");
            s.finishClean();
        }
    }

    @Test
    void cupidRedStringTiesALoneEnemyToCupid() {
        try (Scenario s = Scenario.ffa("cupid")) {
            s.place(s.enemy, 0.5, 9.5, 180);
            s.place(s.enemy2, 0.5, 23.5, 180); // too far from the first to be tied to it
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "붉은 실");
            s.h.tickUntil(() -> s.damageBy(s.owner, s.enemy) > 0, 60, "the collision");
            assertTrue(s.enemy.getLocation().getZ() < 5, "reeled in to Cupid: " + s.enemy.getLocation().toVector());
            assertEquals(s.param("ult_damage"), s.damageBy(s.owner, s.enemy), EPSILON);
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON);
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "Cupid is never caught in her own burst");
            s.finishClean();
        }
    }

    @Test
    void cupidRedStringSnapsWhenAnEndIsGone() {
        try (Scenario s = Scenario.ffa("cupid")) {
            s.place(s.enemy2, 4.5, 4.5, 180);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "붉은 실");
            s.ticks(3);
            s.session.eliminate(s.enemy2, null);
            s.ticks(60);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "no collision without its partner");
            assertTrue(blocks(s, Material.RED_WOOL).isEmpty(), "the cord snapped");
            s.finishClean();
        }
    }

    @Test
    void cupidNeedsAnAimedEnemyForTheString() {
        try (Scenario s = Scenario.ffa("cupid")) {
            face(s.owner, 0);
            s.place(s.enemy, 9.5, -6.5, 0);
            s.place(s.enemy2, -9.5, -6.5, 0);
            assertFalse(s.ultimate().succeeded(), "nobody aimed at");
            s.finishClean();
        }
    }

    // ================================================================== 미노타우로스 minotaur

    private static boolean hornsGlow(Scenario s) {
        return entities(s, BlockDisplay.class).stream()
                .filter(d -> d.getBlock().getMaterial() == Material.BONE_BLOCK)
                .allMatch(Entity::isGlowing);
    }

    @Test
    void minotaurHornsSitOnItsHeadForEveryoneElse() {
        try (Scenario s = Scenario.ffa("minotaur")) {
            s.place(s.owner, 0.5, 0.5, 90); // faces west
            s.ticks(10);
            List<BlockDisplay> horns = entities(s, BlockDisplay.class);
            assertEquals(4, horns.size(), "two horns of two parts");
            for (BlockDisplay horn : horns) {
                assertUnrotated(horn, "horn");
                assertFalse(s.owner.canSee(horn), "hidden from the minotaur's own view");
                assertTrue(s.enemy.canSee(horn));
            }
            for (BlockDisplay base : blocks(s, Material.BONE_BLOCK)) {
                Vector3f center = boxCenter(base);
                assertTrue(Math.abs(center.z()) > 0.2 && Math.abs(center.x()) < 0.15,
                        "the horns stick out of the sides of a head facing west: " + center);
            }
            for (BlockDisplay tip : blocks(s, Material.POLISHED_BLACKSTONE)) {
                Vector3f center = boxCenter(tip);
                assertTrue(center.y() > boxCenter(blocks(s, Material.BONE_BLOCK).getFirst()).y(), "tips curl up");
            }
            s.finishClean();
        }
    }

    @Test
    void minotaurRunBuildsAChargeThatTurnsTheNextBlowIntoAGore() {
        try (Scenario s = Scenario.ffa("minotaur")) {
            s.place(s.enemy, 0.5, 2.0, 180);
            s.melee(s.owner, s.enemy, 4);
            assertEquals(4, s.damageBy(s.owner, s.enemy), EPSILON, "an ordinary blow without a run");
            s.ticks(10);

            s.owner.setSprinting(true);
            s.ticks(s.paramTicks("charge_time") - 2);
            assertFalse(hornsGlow(s), "not yet");
            s.ticks(3);
            assertTrue(hornsGlow(s), "charged: the horns glow red");
            s.clearHits();
            Location before = s.enemy.getLocation();
            s.melee(s.owner, s.enemy, 4);
            assertEquals(4 * (1 + s.param("gore_bonus") / 100), s.damageBy(s.owner, s.enemy), EPSILON, "a gore");
            s.ticks(6);
            double thrown = s.enemy.getLocation().distance(before);
            assertTrue(thrown > 2, "the gore throws the enemy: " + thrown);
            s.owner.setSprinting(false);
            s.ticks(2);
            assertFalse(hornsGlow(s), "the charge is spent");
            s.place(s.enemy, 0.5, 2.0, 180);
            s.clearHits();
            s.melee(s.owner, s.enemy, 4);
            assertEquals(4, s.damageBy(s.owner, s.enemy), EPSILON, "back to ordinary blows");
            s.finishClean();
        }
    }

    @Test
    void minotaurChargeLingersBrieflyAfterTheRun() {
        try (Scenario s = Scenario.ffa("minotaur")) {
            s.owner.setSprinting(true);
            s.ticks(s.paramTicks("charge_time") + 2);
            s.owner.setSprinting(false);
            s.ticks(s.paramTicks("charge_linger") - 3);
            assertTrue(hornsGlow(s), "still charged right after stopping");
            s.ticks(6);
            assertFalse(hornsGlow(s), "gone after a moment");
            s.place(s.enemy, 0.5, 2.0, 180);
            s.melee(s.owner, s.enemy, 4);
            assertEquals(4, s.damageBy(s.owner, s.enemy), EPSILON);
            s.finishClean();
        }
    }

    @Test
    void minotaurRedClothDrivesTheEnemyOntoItsHorns() {
        try (Scenario s = Scenario.ffa("minotaur")) {
            s.place(s.enemy, 0.5, 9.5, 180);
            s.place(s.enemy2, 9.5, -6.5, 0);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "붉은 천");
            assertFalse(s.active().succeeded(), "on cooldown");
            s.ticks(4);
            assertTrue(s.enemy.getLocation().getZ() < 8.5, "the enemy charges at the cloth");
            assertEquals(2, blocks(s, Material.RED_WOOL).size() + blocks(s, Material.GOLD_BLOCK).size(),
                    "the cape and its rod");
            s.h.tickUntil(() -> s.damageBy(s.owner, s.enemy) > 0, s.paramTicks("taunt_duration"), "the horns");
            assertEquals(s.param("counter_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "gored");
            double peak = 0;
            for (int i = 0; i < 12; i++) {
                s.ticks(1);
                peak = Math.max(peak, s.enemy.getLocation().getY() - FlatWorld.SURFACE);
            }
            assertTrue(peak > 1.5, "tossed high: " + peak);
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON);
            s.tickUntilGrounded(s.enemy, 200);
            s.ticks(10);
            assertEquals(4, entities(s, Display.class).size(), "only the horns are left");
            s.finishClean();
        }
    }

    @Test
    void minotaurRedClothCountersAProvokedBlowAndBluntsIt() {
        try (Scenario s = Scenario.ffa("minotaur")) {
            s.place(s.enemy, 0.5, 6.5, 180);
            s.place(s.enemy2, 9.5, -6.5, 0);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "붉은 천");
            s.melee(s.enemy, s.owner, 6);
            assertEquals(6 * (1 - s.param("counter_block") / 100), s.damageTaken(s.owner), EPSILON,
                    "the provoked blow is blunted");
            s.ticks(2);
            assertEquals(s.param("counter_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "and countered at once");
            s.clearHits();
            s.melee(s.enemy2, s.owner, 6);
            assertEquals(6, s.damageTaken(s.owner), EPSILON, "other enemies are not blunted");
            s.finishClean();
        }
    }

    @Test
    void minotaurRedClothNeverGoresThroughAWall() {
        try (Scenario s = Scenario.ffa("minotaur")) {
            s.place(s.enemy2, 9.5, -6.5, 0);
            s.place(s.enemy, 0.5, 4.5, 180);
            s.aimAt(s.owner, s.enemy);
            s.world.wallAlongX(-3, 3, 2, 1, Material.STONE);
            s.assertSucceeded(s.active(), "붉은 천 (over a low wall)");
            s.ticks(s.paramTicks("taunt_duration") + 5);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "the wall is in the way: no gore");
            s.world.wallAlongX(-3, 3, 2, 1, Material.AIR);
            s.finishClean();
        }
    }

    @Test
    void minotaurProvokedEnemyGetsAFreshVelocity() {
        try (Scenario s = Scenario.ffa("minotaur")) {
            s.place(s.enemy, 0.5, 9.5, 180);
            s.place(s.enemy2, 9.5, -6.5, 0);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "붉은 천");
            s.world.physics().setEnabled(false);
            s.enemy.setVelocity(new Vector(1.3, 0, 1.1)); // a stale knockback the server remembers
            s.ticks(1);
            Vector charge = s.enemy.getVelocity();
            assertTrue(charge.getZ() < -0.3 && Math.abs(charge.getX()) < 0.05, "straight at the cloth: " + charge);
            assertTrue(charge.length() < 0.7, "a run, not a launch: " + charge);
            s.world.physics().setEnabled(true);
            s.ticks(40);
            s.finishClean();
        }
    }

    /** Whether a walker on the floor can get from {@code (x, z)} to beyond {@code radius} of the owner's start. */
    private static boolean canWalkOut(Scenario s, int fromX, int fromZ, int radius) {
        java.util.ArrayDeque<int[]> queue = new java.util.ArrayDeque<>();
        java.util.Set<Long> seen = new java.util.HashSet<>();
        queue.add(new int[] {fromX, fromZ});
        seen.add(((long) fromX << 32) ^ (fromZ & 0xFFFFFFFFL));
        int y = (int) FlatWorld.SURFACE;
        while (!queue.isEmpty()) {
            int[] cell = queue.poll();
            if (Math.hypot(cell[0] + 0.5, cell[1] + 0.5) > radius) {
                return true;
            }
            for (int[] step : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                int x = cell[0] + step[0];
                int z = cell[1] + step[1];
                if (s.world.getBlockAt(x, y, z).getType() != Material.AIR
                        || s.world.getBlockAt(x, y + 1, z).getType() != Material.AIR) {
                    continue;
                }
                if (seen.add(((long) x << 32) ^ (z & 0xFFFFFFFFL))) {
                    queue.add(new int[] {x, z});
                }
            }
        }
        return false;
    }

    @Test
    void minotaurLabyrinthRisesRingByRingAroundEveryoneAndAlwaysHasAWayOut() {
        // The openings are random: several labyrinths.
        for (int attempt = 0; attempt < 6; attempt++) {
            try (Scenario s = noGaugeGain(Scenario.builder("minotaur")).start()) {
                s.assertSucceeded(s.ultimate(), "미궁");
                assertFalse(s.ultimate().succeeded(), "already standing");
                s.ticks(5);
                assertEquals(0, s.h.hub.tempBlocks().size(), "first only traced on the ground");
                s.ticks(20);
                int blocks = s.h.hub.tempBlocks().size();
                assertTrue(blocks > 80, "the walls stand: " + blocks);
                int radius = (int) s.param("maze_radius") + 2;
                for (TestPlayer player : s.all()) {
                    assertTrue(io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Spots.isFree(s.world,
                            player.getLocation(), player.getWidth(), player.getHeight()),
                            player.getName() + " is never walled in a block");
                    Location at = player.getLocation();
                    assertTrue(canWalkOut(s, at.getBlockX(), at.getBlockZ(), radius),
                            player.getName() + " can always walk out of the labyrinth");
                }
                // Not only the players: from every open spot of the labyrinth there is a way out.
                int y = (int) FlatWorld.SURFACE;
                for (int x = -radius; x <= radius; x++) {
                    for (int z = -radius; z <= radius; z++) {
                        if (s.world.getBlockAt(x, y, z).getType() == Material.AIR
                                && s.world.getBlockAt(x, y + 1, z).getType() == Material.AIR) {
                            assertTrue(canWalkOut(s, x, z, radius), "no corridor is closed (" + x + ", " + z + ")");
                        }
                    }
                }
                assertTrue(s.owner.getAttribute(Attribute.MOVEMENT_SPEED).getModifiers().stream()
                        .anyMatch(modifier -> modifier.getAmount() > 0), "the bull runs faster");
                assertTrue(hornsGlow(s), "charged the whole time");
                assertEquals(0, s.ctx().cooldownRemaining(s.owner), EPSILON, "the first rush is ready at once");
                s.ticks(s.paramTicks("maze_duration"));
                assertEquals(0, s.h.hub.tempBlocks().size(), "the labyrinth sank back");
                assertFalse(hornsGlow(s));
                assertEquals(0, s.damageTaken(s.enemy) + s.damageTaken(s.enemy2), EPSILON,
                        "the walls alone hurt nobody");
                s.finishClean();
            }
        }
    }

    @Test
    void minotaurRushesThroughTheLabyrinthWallsAndGoresWhatItMeets() {
        try (Scenario s = noGaugeGain(Scenario.builder("minotaur")).start()) {
            s.place(s.enemy, 0.5, 7.5, 180);
            s.place(s.enemy2, -9.5, -9.5, 0);
            s.assertSucceeded(s.ultimate(), "미궁");
            s.ticks(25);
            // Make sure the ring right ahead has a wall in the rush's way.
            int y = (int) FlatWorld.SURFACE;
            boolean wallAhead = false;
            for (int z = 2; z <= 6; z++) {
                wallAhead |= s.world.getBlockAt(0, y, z).getType() != Material.AIR;
            }
            face(s.owner, 0);
            Location start = s.owner.getLocation();
            s.assertSucceeded(s.active(), "미궁 돌진");
            assertEquals(s.param("rush_cooldown"), s.ctx().cooldownRemaining(s.owner), 0.06,
                    "the rush has its own short cooldown");
            s.ticks(16);
            for (int z = 1; z <= 7; z++) {
                assertEquals(Material.AIR, s.world.getBlockAt(0, y, z).getType(), "smashed a way through (z=" + z
                        + ", there was a wall ahead: " + wallAhead + ")");
            }
            assertTrue(s.owner.getLocation().getZ() > start.getZ() + 6, "the bull charged on through");
            assertEquals(s.param("rush_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "and gored the enemy");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            assertEquals(FlatWorld.SURFACE, s.owner.getLocation().getY(), 1.0E-6, "on its feet on the floor");
            s.ticks(s.paramTicks("maze_duration"));
            assertEquals(0, s.h.hub.tempBlocks().size());
            s.readyActive();
            s.layout();
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "the red cloth again");
            assertEquals(s.param("cooldown"), s.ctx().cooldownRemaining(s.owner), 0.06, "with its own cooldown");
            s.ticks(40);
            s.finishClean();
        }
    }

    @Test
    void minotaurRushStopsAtRealWallsAndLeavesThemStanding() {
        try (Scenario s = Scenario.ffa("minotaur")) {
            s.place(s.enemy, 9.5, -9.5, 0);
            s.place(s.enemy2, -9.5, -9.5, 0);
            s.assertSucceeded(s.ultimate(), "미궁");
            s.ticks(25);
            face(s.owner, 270); // east, towards a real wall beyond the labyrinth's first ring
            s.world.wallAlongX(5, 5, 0, 3, Material.STONE);
            s.world.wallAlongX(5, 5, 1, 3, Material.STONE);
            s.world.wallAlongX(5, 5, -1, 3, Material.STONE);
            s.assertSucceeded(s.active(), "미궁 돌진 into a real wall");
            s.ticks(20);
            assertEquals(Material.STONE, s.world.getBlockAt(5, (int) FlatWorld.SURFACE, 0).getType(),
                    "a real wall is never broken");
            assertTrue(s.owner.getLocation().getX() < 5, "the rush stopped in front of it");
            for (int z = -1; z <= 1; z++) {
                s.world.wallAlongX(5, 5, z, 3, Material.AIR);
            }
            s.ticks(s.paramTicks("maze_duration"));
            s.finishClean();
        }
    }

    // ================================================================== 히드라 hydra

    /** A hydra whose passive darts stay quiet, so a test counts only the skill it uses. */
    private static Scenario quietHydra() {
        return Scenario.builder("hydra").param("spit_interval", 1000).start();
    }

    private static List<BlockDisplay> necks(Scenario s) {
        return blocks(s, Material.MOSS_BLOCK);
    }

    @Test
    void hydraGrowsHeadsAsItIsHurtAndLosesThemWhenHealed() {
        try (Scenario s = quietHydra()) {
            s.ticks(10);
            assertEquals(1, necks(s).size(), "one head at full health");
            s.owner.setHealth(s.maxHealth(s.owner) * s.param("head_threshold_1") / 100 - 0.5);
            s.ticks(6);
            assertEquals(2, necks(s).size(), "a second head sprouts");
            s.owner.setHealth(s.maxHealth(s.owner) * s.param("head_threshold_2") / 100 - 0.5);
            s.ticks(6);
            assertEquals(3, necks(s).size(), "a third one");
            assertEquals(6, entities(s, BlockDisplay.class).size(), "a neck and a head each");
            s.owner.setHealth(1);
            s.ticks(6);
            assertEquals(3, necks(s).size(), "never more than three");
            s.healAll();
            s.ticks(20);
            assertEquals(1, necks(s).size(), "healed, the extra heads wither away");
            s.finishClean();
        }
    }

    @Test
    void hydraHeadsGrowOnItsBackAndShouldersWithUnrotatedDisplays() {
        try (Scenario s = quietHydra()) {
            s.owner.setHealth(5);
            s.place(s.owner, 0.5, 0.5, 90); // faces west: its back is to the east
            s.ticks(20);
            List<BlockDisplay> heads = blocks(s, Material.GREEN_TERRACOTTA);
            assertEquals(3, heads.size());
            double sides = 0;
            for (BlockDisplay head : entities(s, BlockDisplay.class)) {
                assertUnrotated(head, head.getBlock().getMaterial().name());
            }
            for (BlockDisplay head : heads) {
                Vector3f center = boxCenter(head);
                assertTrue(center.x() > 0.05, "the heads stand behind it (east): " + center);
                assertTrue(center.y() > 1.7, "up on long necks: " + center);
                Vector3f facing = rotated(head, new Vector3f(0, 0, 1));
                assertTrue(facing.x() < -0.9, "each head looks where the hydra looks (west): " + facing);
                sides += center.z();
            }
            assertEquals(0, sides, 0.05, "one head to each side, one in the middle");
            s.finishClean();
        }
    }

    @Test
    void hydraHeadsSpitVenomDartsAtTheNearestEnemy() {
        try (Scenario s = Scenario.ffa("hydra")) {
            s.place(s.enemy2, 0.5, 15.5, 180); // out of the darts' range
            s.ticks(s.paramTicks("spit_interval") * 2 + 20);
            List<Scenario.Hit> hits = s.hitsBy(s.owner, s.enemy);
            assertTrue(hits.size() >= 1, "the head spat at the enemy: " + hits);
            for (Scenario.Hit hit : hits) {
                assertEquals(s.param("spit_damage"), hit.damage(), EPSILON);
            }
            assertTrue(s.enemy.hasPotionEffect(PotionEffectType.POISON) || hits.size() >= 1, "venom");
            assertEquals(0, s.damageBy(s.owner, s.enemy2), EPSILON, "out of range");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.finishClean();
        }
    }

    @Test
    void hydraDartsNeverFlyThroughWalls() {
        try (Scenario s = Scenario.ffa("hydra")) {
            s.place(s.enemy2, 0.5, 15.5, 180);
            s.world.wallAlongX(-4, 4, 2, 4, Material.STONE);
            s.ticks(s.paramTicks("spit_interval") * 2 + 20);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "the wall shields the enemy");
            s.world.wallAlongX(-4, 4, 2, 4, Material.AIR);
            s.finishClean();
        }
    }

    @Test
    void hydraBitesAsManyEnemiesAsItHasHeadsAndTheSpareHeadBitesAgain() {
        try (Scenario s = quietHydra()) {
            s.place(s.enemy, 0.5, 3.5, 180);
            s.place(s.enemy2, -2.5, 2.5, 90);
            s.owner.setHealth(5);
            s.ticks(20);
            assertEquals(3, necks(s).size());
            s.clearHits();
            s.assertSucceeded(s.active(), "머리 물기");
            assertFalse(s.active().succeeded(), "on cooldown");
            s.ticks(2);
            assertEquals(0, s.damageTaken(s.enemy) + s.damageTaken(s.enemy2), EPSILON, "the necks are still lashing");
            s.ticks(6);
            double nearest = s.damageBy(s.owner, s.enemy);
            double other = s.damageBy(s.owner, s.enemy2);
            assertEquals(s.param("bite_damage") + s.param("extra_bite_damage"), Math.max(nearest, other), EPSILON,
                    "the nearest enemy is bitten by the spare head too");
            assertEquals(s.param("bite_damage"), Math.min(nearest, other), EPSILON, "the other one once");
            assertTrue(s.enemy.hasPotionEffect(PotionEffectType.POISON));
            assertTrue(s.enemy2.hasPotionEffect(PotionEffectType.POISON));
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.ticks(10);
            assertEquals(3, necks(s).size(), "the heads snapped back");
            s.finishClean();
        }
    }

    @Test
    void hydraWithOneHeadBitesOneEnemy() {
        try (Scenario s = quietHydra()) {
            s.place(s.enemy, 0.5, 3.5, 180);
            s.place(s.enemy2, -2.5, 2.5, 90);
            s.ticks(10);
            s.assertSucceeded(s.active(), "머리 물기");
            s.ticks(10);
            assertEquals(s.param("bite_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "the nearest one");
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON, "one head, one bite");
            s.finishClean();
        }
    }

    @Test
    void hydraBitingNeckReachesForItsVictim() {
        try (Scenario s = quietHydra()) {
            s.place(s.enemy, -3.5, 0.5, 270); // to the west
            s.place(s.enemy2, 9.5, 9.5, 0);
            s.ticks(10);
            s.assertSucceeded(s.active(), "머리 물기");
            s.ticks(3);
            BlockDisplay neck = necks(s).getFirst();
            assertUnrotated(neck, "neck");
            Vector3f along = rotated(neck, new Vector3f(0, 0, 1));
            assertTrue(along.x() < -0.8, "the neck stretches west towards the victim: " + along);
            assertTrue(neck.getTransformation().getScale().z() > 2.5, "long: " + neck.getTransformation().getScale());
            s.ticks(10);
            assertTrue(necks(s).getFirst().getTransformation().getScale().z() < 1.2, "and back");
            s.finishClean();
        }
    }

    @Test
    void hydraBiteNeedsAnEnemyInReach() {
        try (Scenario s = quietHydra()) {
            s.place(s.enemy, 0.5, 12.5, 180);
            s.place(s.enemy2, 9.5, 9.5, 0);
            assertFalse(s.active().succeeded(), "nobody within reach");
            assertEquals(0, s.ctx().cooldownRemaining(s.owner), EPSILON, "no cooldown for a miss");
            s.finishClean();
        }
    }

    @Test
    void hydraNineHeadsRiseAndSpitMarkedGlobs() {
        try (Scenario s = noGaugeGain(Scenario.builder("hydra").param("spit_interval", 1000)).start()) {
            s.place(s.enemy, 0.5, 7.5, 180);
            s.place(s.enemy2, 6.5, 0.5, 90);
            s.assertSucceeded(s.ultimate(), "아홉 머리");
            s.ticks(30);
            List<BlockDisplay> giants = blocks(s, Material.GREEN_TERRACOTTA).stream()
                    .filter(d -> d.getTransformation().getScale().z() > 0.6f).toList();
            assertEquals(9, giants.size(), "nine giant heads around the hydra");
            for (BlockDisplay giant : giants) {
                assertUnrotated(giant, "giant head");
                double distance = Math.hypot(giant.getLocation().getX() - 0.5, giant.getLocation().getZ() - 0.5);
                assertTrue(distance > 2 && distance < 3.2, "in a ring around it: " + distance);
                assertEquals(FlatWorld.SURFACE, giant.getLocation().getY(), 1.0E-6, "rooted on the ground");
            }
            s.ticks(s.paramTicks("ult_duration"));
            int cap = (int) s.param("glob_max_hits");
            for (TestPlayer enemy : s.enemies()) {
                List<Scenario.Hit> hits = s.hitsBy(s.owner, enemy);
                assertTrue(hits.size() >= 2, enemy.getName() + " is spat at again and again: " + hits.size());
                assertTrue(hits.size() <= cap, "at most " + cap + " globs each: " + hits.size());
                for (Scenario.Hit hit : hits) {
                    assertEquals(s.param("glob_damage"), hit.damage(), EPSILON);
                }
            }
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "the hydra is never splashed");
            s.ticks(20);
            assertEquals(2, entities(s, Display.class).size(), "the giant heads sank; only its own head is left");
            s.finishClean();
        }
    }

    @Test
    void hydraGlobsLandWhereTheTargetStoodSoTheyCanBeDodged() {
        try (Scenario s = noGaugeGain(Scenario.builder("hydra").param("spit_interval", 1000)).start()) {
            s.place(s.enemy2, 0.5, 9.5, 180);
            s.assertSucceeded(s.ultimate(), "아홉 머리");
            s.ticks(22); // the first glob is spat at whoever stood closest at the time
            s.place(s.enemy, 0.5, 30.5, 180);
            s.place(s.enemy2, 30.5, 0.5, 90);
            s.ticks(s.paramTicks("ult_duration"));
            assertEquals(0, s.damageTaken(s.enemy) + s.damageTaken(s.enemy2), EPSILON,
                    "they ran: every glob splashed an empty spot");
            s.finishClean();
        }
    }

    @Test
    void hydraNineHeadsNeedEnemiesAround() {
        try (Scenario s = Scenario.builder("hydra").param("ult_radius", 2).start()) {
            assertFalse(s.ultimate().succeeded(), "nobody within 2 blocks");
            s.finishClean();
        }
    }

    // ================================================================== stealth and landings

    @Test
    void medusaGorgonNeverStonesOrMarksAHiddenViewer() {
        try (Scenario s = Scenario.ffa("medusa")) {
            s.place(s.enemy2, 9.5, -6.5, 0);
            s.owner.hidePlayer(s.ctx().plugin(), s.enemy);
            s.assertSucceeded(s.ultimate(), "고르곤의 눈");
            s.ticks(s.paramTicks("stare_time") * 2);
            assertFalse(rooted(s.enemy), "the eye does not see a hidden enemy");
            assertTrue(blocks(s, Material.COBBLESTONE).isEmpty(), "no creeping stone gives it away");
            s.owner.showPlayer(s.ctx().plugin(), s.enemy);
            s.ticks(s.paramTicks("ult_duration"));
            s.finishClean();
        }
    }

    @Test
    void cupidCharmHeartHidesWhileTheLoverIsHidden() {
        try (Scenario s = Scenario.ffa("cupid")) {
            s.place(s.enemy2, 9.5, -6.5, 0);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "사랑의 화살");
            s.h.tickUntil(() -> s.damageBy(s.owner, s.enemy) > 0, 20, "the arrow lands");
            s.ticks(1);
            TextDisplay heart = entities(s, TextDisplay.class).getFirst();
            assertTrue(heart.getViewRange() > 0, "shown");
            s.owner.hidePlayer(s.ctx().plugin(), s.enemy);
            s.ticks(1);
            assertEquals(0, heart.getViewRange(), EPSILON, "a hidden lover is not given away by its heart");
            s.owner.showPlayer(s.ctx().plugin(), s.enemy);
            s.ticks(1);
            assertTrue(heart.getViewRange() > 0, "shown again");
            s.finishClean();
        }
    }

    @Test
    void cupidFlapLandsExactlyOnTheFloor() {
        try (Scenario s = Scenario.ffa("cupid")) {
            s.place(s.owner, 0.5, 66, 0.5, 0, 0);
            s.ticks(3);
            s.owner.setSneaking(true);
            s.ticks(1);
            s.owner.setSneaking(false);
            s.tickUntilGrounded(s.owner, 2000);
            Location feet = s.owner.getLocation();
            assertEquals(FlatWorld.SURFACE, feet.getY(), 1.0E-6, "on the surface, not in it");
            assertTrue(io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Spots.isFree(s.world, feet,
                    s.owner.getWidth(), s.owner.getHeight()), "a free body");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.finishClean();
        }
    }

    @Test
    void minotaurNeverProvokesAHiddenEnemy() {
        try (Scenario s = Scenario.ffa("minotaur")) {
            s.place(s.enemy2, 9.5, -6.5, 0);
            s.owner.hidePlayer(s.ctx().plugin(), s.enemy);
            s.aimAt(s.owner, s.enemy);
            assertFalse(s.active().succeeded(), "the cloth cannot be waved at what it does not see");
            s.owner.showPlayer(s.ctx().plugin(), s.enemy);
            s.finishClean();
        }
    }

    @Test
    void hydraDartsNeverSeekAHiddenEnemy() {
        try (Scenario s = Scenario.ffa("hydra")) {
            s.place(s.enemy2, 0.5, 15.5, 180);
            s.owner.hidePlayer(s.ctx().plugin(), s.enemy);
            s.ticks(s.paramTicks("spit_interval") * 2 + 20);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "no dart at a hidden enemy");
            s.owner.showPlayer(s.ctx().plugin(), s.enemy);
            s.finishClean();
        }
    }

    @Test
    void everyMythAbilityCleansUpWhenTheMatchEndsMidSkill() {
        for (String id : new String[] {"medusa", "cupid", "minotaur", "hydra"}) {
            try (Scenario s = Scenario.ffa(id)) {
                s.aimAt(s.owner, s.enemy);
                s.assertSucceeded(s.active(), id + " active");
                s.ticks(2);
                s.assertSucceeded(s.ultimate(), id + " ultimate");
                s.ticks(14);
                s.finishClean();
            }
        }
    }
}
