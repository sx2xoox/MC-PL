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

import io.github.sx2xoox.mcpl.game.superpower.abilities.HunterAbility;
import io.github.sx2xoox.mcpl.game.superpower.abilities.PirateAbility;
import io.github.sx2xoox.mcpl.game.superpower.abilities.SpeedsterAbility;
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
import org.bukkit.damage.DamageType;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 특수 계열 (새 능력): 사냥꾼, 해적, 스피드스터. Every ability through a whole match, the owner dying in the middle of each
 * skill, team mode with an ally in the line of fire, the standalone ultimates borrowed by a Gambler, and each
 * ability's own mechanics: tracks and bear traps and the hunt; plunder, the rolling cannonball and the ghost ship;
 * momentum, chained dashes with charges and light speed with its afterimages.
 */
class RogueNewAbilitiesTest {

    private static final double EPSILON = 1.0E-6;
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z0-9_]+)}");
    private static final double BASE_SPEED = 0.1;

    static Stream<String> abilities() {
        return Stream.of("hunter", "pirate", "speedster");
    }

    /** The abilities whose ultimate the Gambler may borrow. */
    static Stream<String> standalone() {
        return Stream.of("pirate", "speedster");
    }

    // ------------------------------------------------------------------ helpers

    private static <T extends Entity> List<T> entities(Scenario s, Class<T> type) {
        return s.world.getEntities().stream().filter(type::isInstance).map(type::cast).toList();
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

    private static double gauge(Scenario s, Player player) {
        return s.match.participant(player.getUniqueId()).gauge();
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

    /**
     * Walks {@code player} in a straight line, one teleport per tick, like a client sending its movement: from
     * ({@code fromX}, {@code fromZ}) to ({@code toX}, {@code toZ}) at {@code perTick} blocks a tick.
     */
    private static void run(Scenario s, Player player, double fromX, double fromZ, double toX, double toZ,
                            double perTick) {
        double dx = toX - fromX;
        double dz = toZ - fromZ;
        double length = Math.hypot(dx, dz);
        int steps = Math.max(1, (int) Math.round(length / perTick));
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        for (int i = 1; i <= steps; i++) {
            double t = (double) i / steps;
            s.place(player, fromX + dx * t, fromZ + dz * t, yaw);
            s.ticks(1);
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
            assertFalse(enemy.hasPotionEffect(PotionEffectType.GLOWING), id + ": the glow wore off");
        }
        assertModifierFree(s.owner, id);
        s.assertNoProblems();
        s.finishMatch();
        s.assertNoProblems();
        s.assertCleanWorld();
    }

    // ------------------------------------------------------------------ every ability

    @Test
    void descriptionsOnlyReferenceDeclaredParams() {
        for (AbilityInfo info : List.of(HunterAbility.INFO, PirateAbility.INFO, SpeedsterAbility.INFO)) {
            for (AbilityInfo.Skill skill : List.of(info.passive(), info.active(), info.ultimate())) {
                Matcher matcher = PLACEHOLDER.matcher(skill.description());
                while (matcher.find()) {
                    assertTrue(info.params().containsKey(matcher.group(1)),
                            info.id() + " " + skill.name() + " uses an undeclared param " + matcher.group());
                }
                assertFalse(skill.name().contains("TODO") || skill.description().contains("TODO"), info.id());
            }
        }
        assertFalse(HunterAbility.INFO.ultimateStandalone(), "the hunt's bonus damage needs the hooks");
        assertTrue(PirateAbility.INFO.ultimateStandalone());
        assertTrue(SpeedsterAbility.INFO.ultimateStandalone());
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
            switch (id) {
                case "hunter" -> {
                    s.ticks(5);
                    assertTrue(s.enemy.hasPotionEffect(PotionEffectType.GLOWING), "the prey glows");
                    assertTrue(slowed(s.enemy), "the prey drags its feet");
                    s.melee(s.owner, s.enemy, 4);
                    assertTrue(s.damageTaken(s.enemy) > 4, "the hunter hits its prey harder");
                }
                case "speedster" -> {
                    s.ticks(2);
                    run(s, s.owner, 0.5, 0.5, 0.5, 9.5, 0.6);
                }
                default -> {
                }
            }
            s.ticks(300);
            assertTrue(s.damageTaken(s.enemy) > 0, id + " ultimate hurts the aimed enemy");
            assertEquals(0, s.damageTaken(s.owner), EPSILON, id + " ultimate never hurts its owner: " + s.hits());
            s.assertNoProblems();
            assertNoDisplays(s, id);
            for (TestPlayer enemy : s.enemies()) {
                assertModifierFree(enemy, id);
            }

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
            // The trap has snapped, the ball is rolling through, the dash is over: effects still running.
            s.ticks(22);
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
            assertFalse(s.ally().hasPotionEffect(PotionEffectType.GLOWING), "the ally is never marked");
            s.assertNoProblems();

            s.finishMatch();
            s.assertNoProblems();
            s.assertCleanWorld();
        }
    }

    // ------------------------------------------------------------------ borrowed by the gambler

    @ParameterizedTest(name = "{0}")
    @MethodSource("standalone")
    void borrowedUltimateWorksOnAGambler(String id) {
        try (Scenario s = gambler()) {
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(borrow(s, id), "borrowed " + id);
            if (id.equals("speedster")) {
                // Light speed strikes by running: the gambler sprints past the enemy.
                s.ticks(2);
                run(s, s.owner, 0.5, 0.5, 0.5, 9.5, 0.6);
            }
            s.ticks(300);
            assertTrue(s.damageBy(s.owner, s.enemy) > 0, "the borrowed " + id + " hurts the enemy");
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
            s.assertNoProblems();
            s.finishClean();
        }
    }

    // ------------------------------------------------------------------ 사냥꾼 hunter

    @Test
    void hunterTracksEveryEnemyItHurtsForAWhile() {
        try (Scenario s = Scenario.ffa("hunter")) {
            s.melee(s.owner, s.enemy, 2);
            PotionEffect glow = s.enemy.getPotionEffect(PotionEffectType.GLOWING);
            assertNotNull(glow, "a tracked enemy glows");
            assertEquals(s.paramTicks("track_duration"), glow.getDuration(), 1);
            assertFalse(s.enemy2.hasPotionEffect(PotionEffectType.GLOWING), "only the one it hit");
            // The tracked enemy walks away: its prints are for the hunter's eyes only (nothing to assert on a
            // mock client), and walking must never fail.
            run(s, s.enemy, 0.5, 4.5, 6.5, 10.5, 0.25);
            s.ticks(s.paramTicks("track_duration"));
            assertFalse(s.enemy.hasPotionEffect(PotionEffectType.GLOWING), "the trail goes cold");
            s.melee(s.enemy, s.owner, 2);
            assertFalse(s.enemy.hasPotionEffect(PotionEffectType.GLOWING), "being hit tracks nobody");
            s.finishClean();
        }
    }

    @Test
    void hunterTrapLandsAtTheAimedEnemysFeetArmsAndSnapsShut() {
        try (Scenario s = Scenario.ffa("hunter")) {
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "곰덫");
            s.ticks(2);
            assertEquals(5, entities(s, Display.class).size(), "a whole trap is in the air");
            s.ticks(Math.max(1, s.paramTicks("trap_arm") - 2));
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "not armed yet");
            s.h.tickUntil(() -> s.damageTaken(s.enemy) > 0, 30, "the trap snapping");
            s.ticks(1);
            assertEquals(s.param("trap_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "snap!");
            assertEquals(DamageType.MAGIC, s.hitsBy(s.owner, s.enemy).getFirst().type());
            assertTrue(rooted(s.enemy), "held fast");
            assertTrue(s.enemy.hasPotionEffect(PotionEffectType.GLOWING), "and tracked");
            for (BlockDisplay part : entities(s, BlockDisplay.class)) {
                assertTrue(part.getLocation().distance(s.enemy.getLocation()) < 0.5, "clamped to its legs");
            }
            assertTrue(entities(s, TextDisplay.class).stream().anyMatch(label -> label.getLocation().getY()
                    > s.enemy.getEyeLocation().getY()), "철컥! pops over its head");
            s.ticks(s.paramTicks("trap_root") + 2);
            assertFalse(rooted(s.enemy), "free after " + s.param("trap_root") + " s");
            s.ticks(20);
            assertNoDisplays(s, "a sprung trap falls apart");
            assertEquals(s.param("trap_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "it only bites once");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.finishClean();
        }
    }

    @Test
    void hunterTrapLiesInWaitUntilSomeoneStepsIn() {
        try (Scenario s = Scenario.ffa("hunter")) {
            s.place(s.enemy, 8.5, 8.5, 180);
            s.place(s.enemy2, -8.5, 8.5, 180);
            s.aimAtFloor(s.owner, 0.5, 3.5);
            s.assertSucceeded(s.active(), "곰덫");
            s.ticks(80);
            assertEquals(0, s.damageTaken(s.enemy) + s.damageTaken(s.enemy2), EPSILON, "nobody stepped in");
            assertEquals(5, entities(s, Display.class).size(), "the trap waits");
            Location trap = entities(s, BlockDisplay.class).getFirst().getLocation();
            assertEquals(0.5, trap.getX(), 0.6);
            assertEquals(3.5, trap.getZ(), 0.6);
            assertEquals(FlatWorld.SURFACE, trap.getY(), 0.05, "on the floor");

            // Jumping over it is fine; walking into it is not.
            s.place(s.enemy, 0.5, FlatWorld.SURFACE + 1.5, 3.5, 0, 0);
            s.world.physics().setEnabled(false);
            s.ticks(2);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "too high above the plate");
            s.world.physics().setEnabled(true);
            run(s, s.enemy, 0.5, 6.5, 0.5, 3.5, 0.25);
            s.ticks(1);
            assertEquals(s.param("trap_damage"), s.damageBy(s.owner, s.enemy), EPSILON);
            assertTrue(rooted(s.enemy));
            s.ticks(60);
            assertNoDisplays(s, "spent");
            s.finishClean();
        }
    }

    @Test
    void hunterKeepsAtMostItsTrapLimitAndTheOldestGoesFirst() {
        try (Scenario s = Scenario.ffa("hunter")) {
            s.place(s.enemy, 10.5, 12.5, 180);
            s.place(s.enemy2, -10.5, 12.5, 180);
            int max = (int) s.param("trap_max");
            for (int i = 0; i <= max; i++) {
                s.aimAtFloor(s.owner, -3.5 + 2.5 * i, 4.5);
                s.readyActive();
                s.assertSucceeded(s.active(), "trap " + (i + 1));
                s.ticks(20);
            }
            s.ticks(10);
            assertEquals(max * 5, entities(s, Display.class).size(), "only " + max + " traps stay");
            // The first spot is empty now; the last trap catches.
            run(s, s.enemy, -3.5, 7.5, -3.5, 4.5, 0.3);
            s.ticks(2);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "the oldest trap was taken back");
            double lastX = -3.5 + 2.5 * max;
            run(s, s.enemy, lastX, 7.5, lastX, 4.5, 0.3);
            s.ticks(2);
            assertEquals(s.param("trap_damage"), s.damageTaken(s.enemy), EPSILON, "the newest trap bites");
            s.finishClean();
        }
    }

    @Test
    void hunterTrapsIgnoreAlliesAndExpire() {
        try (Scenario s = Scenario.builder("hunter").teams().param("trap_life", 3).start()) {
            s.place(s.enemy, 10.5, 12.5, 180);
            s.place(s.enemy2, -10.5, 12.5, 180);
            s.aimAtFloor(s.owner, 0.5, 3.5);
            s.assertSucceeded(s.active(), "곰덫");
            s.ticks(25);
            s.place(s.ally(), 0.5, 3.5, 0);
            s.ticks(10);
            assertEquals(0, s.damageTaken(s.ally()), EPSILON, "an ally walks right over it");
            assertFalse(rooted(s.ally()));
            assertEquals(5, entities(s, Display.class).size(), "still set");
            s.place(s.ally(), -1.5, 0.5, 0);
            s.ticks(s.paramTicks("trap_life"));
            assertNoDisplays(s, "the trap rusts away after " + s.param("trap_life") + " s");
            s.place(s.enemy, 0.5, 3.5, 0);
            s.ticks(5);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "an expired trap catches nobody");
            s.finishClean();
        }
    }

    @Test
    void hunterThrowsItsTrapShortOfAWallNeverIntoIt() {
        try (Scenario s = Scenario.ffa("hunter")) {
            s.place(s.enemy, 10.5, 12.5, 180);
            s.place(s.enemy2, -10.5, 12.5, 180);
            s.world.wallAlongX(-4, 4, 3, 3, Material.STONE);
            s.aimAt(s.owner, new Location(s.world, 0.5, FlatWorld.SURFACE + 1.0, 3.0));
            s.assertSucceeded(s.active(), "곰덫");
            s.ticks(20);
            Location trap = entities(s, BlockDisplay.class).getFirst().getLocation();
            assertTrue(trap.getZ() < 3 - 0.3, "in front of the wall: " + trap.toVector());
            assertEquals(FlatWorld.SURFACE, trap.getY(), 0.05, "on the floor");
            s.world.wallAlongX(-4, 4, 3, 3, Material.AIR);
            s.finishClean();
        }
    }

    @Test
    void hunterHuntsTheWeakestVisibleEnemyAndHitsItHarder() {
        try (Scenario s = noGaugeGain(Scenario.builder("hunter")).start()) {
            s.enemy.setHealth(80);
            s.enemy2.setHealth(50);
            s.assertSucceeded(s.ultimate(), "사냥 개시");
            s.ticks(3);
            assertTrue(s.enemy2.hasPotionEffect(PotionEffectType.GLOWING), "the weakest is the prey");
            assertTrue(slowed(s.enemy2));
            assertFalse(s.enemy.hasPotionEffect(PotionEffectType.GLOWING));
            assertFalse(slowed(s.enemy));
            double speed = BASE_SPEED * (1 + s.param("hunt_speed") / 100);
            assertEquals(speed, s.owner.getAttribute(Attribute.MOVEMENT_SPEED).getValue(), EPSILON,
                    "the hunter runs faster");
            assertEquals(4, entities(s, BlockDisplay.class).size(), "a reticle at the prey's feet");
            assertEquals(1, entities(s, TextDisplay.class).size(), "and a marker over its head");

            s.melee(s.owner, s.enemy2, 4);
            assertEquals(4 * (1 + s.param("hunt_bonus") / 100), s.damageBy(s.owner, s.enemy2), EPSILON, "bonus");
            s.melee(s.owner, s.enemy, 4);
            assertEquals(4, s.damageBy(s.owner, s.enemy), EPSILON, "no bonus on anyone else");

            s.ticks(s.paramTicks("hunt_duration"));
            assertEquals(BASE_SPEED, s.owner.getAttribute(Attribute.MOVEMENT_SPEED).getValue(), EPSILON,
                    "the hunt is over");
            assertFalse(slowed(s.enemy2));
            s.ticks(10);
            assertNoDisplays(s, "hunt");
            s.finishClean();
        }
    }

    @Test
    void hunterHuntMovesOnToTheNextPreyAndRewardsTheKill() {
        try (Scenario s = noGaugeGain(Scenario.builder("hunter")).start()) {
            s.enemy2.setHealth(30);
            s.owner.setHealth(10);
            s.assertSucceeded(s.ultimate(), "사냥 개시");
            s.ticks(5);
            assertTrue(slowed(s.enemy2), "the first prey");
            s.session.eliminate(s.enemy2, s.owner);
            assertEquals(10 + s.param("hunt_heal"), s.owner.getHealth(), EPSILON, "a successful hunt heals");
            s.ticks(4);
            assertTrue(s.enemy.hasPotionEffect(PotionEffectType.GLOWING), "the hunt moves on");
            assertTrue(slowed(s.enemy));
            s.melee(s.owner, s.enemy, 4);
            assertEquals(4 * (1 + s.param("hunt_bonus") / 100), s.damageBy(s.owner, s.enemy), EPSILON);
            s.ticks(s.paramTicks("hunt_duration") + 10);
            assertFalse(slowed(s.enemy));
            assertNoDisplays(s, "hunt");
            s.finishClean();
        }
    }

    @Test
    void hunterNeverHuntsAnEnemyHiddenFromIt() {
        try (Scenario s = Scenario.ffa("hunter")) {
            s.enemy2.setHealth(20);
            s.owner.hidePlayer(s.ctx().plugin(), s.enemy2);
            s.assertSucceeded(s.ultimate(), "사냥 개시");
            s.ticks(3);
            assertFalse(slowed(s.enemy2), "a stealthed enemy is not picked, however weak");
            assertTrue(slowed(s.enemy));
            s.owner.showPlayer(s.ctx().plugin(), s.enemy2);
            s.ticks(s.paramTicks("hunt_duration") + 10);

            s.owner.hidePlayer(s.ctx().plugin(), s.enemy);
            s.owner.hidePlayer(s.ctx().plugin(), s.enemy2);
            s.match.setGauge(s.owner, 100);
            assertFalse(s.match.useUltimate(s.owner).succeeded(), "nobody to hunt");
            assertEquals(100, gauge(s, s.owner), EPSILON, "the gauge is kept");
            s.owner.showPlayer(s.ctx().plugin(), s.enemy);
            s.owner.showPlayer(s.ctx().plugin(), s.enemy2);
            s.finishClean();
        }
    }

    // ------------------------------------------------------------------ 해적 pirate

    @Test
    void pirateKillPlundersGaugeAndReloadsTheCannon() {
        try (Scenario s = noGaugeGain(Scenario.builder("pirate")).start()) {
            s.place(s.owner, 0.5, 0.5, 180);
            s.assertSucceeded(s.active(), "대포알");
            assertTrue(s.ctx().cooldownRemaining(s.owner) > 0, "the cannon is reloading");
            s.ticks(20);
            s.session.eliminate(s.enemy2, s.owner);
            assertEquals(s.param("plunder_gauge"), gauge(s, s.owner), EPSILON, "plunder");
            assertEquals(0, s.ctx().cooldownRemaining(s.owner), EPSILON, "reloaded at once");
            s.ticks(3);
            assertTrue(entities(s, ItemDisplay.class).size() >= 4, "a fountain of gold");
            assertEquals(1, entities(s, TextDisplay.class).size(), "약탈! over the pirate");
            s.ticks(40);
            assertNoDisplays(s, "the gold is in the pirate's pocket");
            s.finishClean();
        }
    }

    @Test
    void pirateTakesAShareOfKillsItHelpedWith() {
        try (Scenario s = noGaugeGain(Scenario.builder("pirate").teams()).start()) {
            s.melee(s.owner, s.enemy2, 2);
            s.ticks(10);
            s.session.eliminate(s.enemy2, s.ally());
            s.ticks(6);
            assertEquals(s.param("assist_gauge"), gauge(s, s.owner), EPSILON, "a share of the plunder");
            s.ticks(40);
            assertNoDisplays(s, "share");
            s.finishClean();
        }
    }

    @Test
    void pirateShareNeedsARecentHit() {
        try (Scenario s = noGaugeGain(Scenario.builder("pirate").teams()).start()) {
            s.melee(s.owner, s.enemy2, 2);
            s.ticks(s.paramTicks("assist_window") + 10);
            s.session.eliminate(s.enemy2, s.ally());
            s.ticks(6);
            assertEquals(0, gauge(s, s.owner), EPSILON, "too long ago");
            s.finishClean();
        }
    }

    @Test
    void pirateCannonballRollsThroughEnemiesAndKnocksThemBack() {
        try (Scenario s = Scenario.ffa("pirate")) {
            s.place(s.enemy2, 0.5, 8.5, 180);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "대포알");
            s.ticks(1);
            assertEquals(3, entities(s, BlockDisplay.class).size(), "the cannon appears");
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "the fuse burns first");
            double enemyZ = s.enemy.getLocation().getZ();
            s.ticks(12);
            assertEquals(s.param("ball_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "the ball bowls it over");
            assertTrue(s.enemy.getLocation().getZ() > enemyZ + 0.5, "and shoves it down the line");
            s.ticks(20);
            assertEquals(s.param("ball_damage"), s.damageBy(s.owner, s.enemy2), EPSILON, "and the one behind");
            assertEquals(s.param("ball_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "each enemy once");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.ticks(80);
            assertNoDisplays(s, "cannon and ball");
            s.finishClean();
        }
    }

    @Test
    void pirateCannonballStopsDeadAtAWall() {
        try (Scenario s = Scenario.ffa("pirate")) {
            s.place(s.enemy, 0.5, 10.5, 180);
            s.place(s.enemy2, 6.5, 10.5, 180);
            s.world.wallAlongX(-4, 4, 6, 2, Material.STONE);
            s.aimAt(s.owner, new Location(s.world, 0.5, 65, 10));
            s.assertSucceeded(s.active(), "대포알");
            s.ticks(40);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "the wall shields it");
            assertNoDisplays(s, "the ball shatters on the wall");
            s.world.wallAlongX(-4, 4, 6, 2, Material.AIR);
            s.finishClean();
        }
    }

    @Test
    void pirateCannonballRollsOffALedgeAndKeepsGoing() {
        try (Scenario s = Scenario.ffa("pirate")) {
            // A two-block high deck the pirate stands on; the enemy waits on the floor below.
            for (int x = -3; x <= 3; x++) {
                for (int z = -3; z <= 2; z++) {
                    s.world.setBlock(x, FlatWorld.FLOOR_Y + 1, z, Material.STONE);
                    s.world.setBlock(x, FlatWorld.FLOOR_Y + 2, z, Material.STONE);
                }
            }
            s.place(s.owner, 0.5, FlatWorld.SURFACE + 2, 0.5, 0, 0);
            s.place(s.enemy, 0.5, 8.5, 180);
            s.place(s.enemy2, 12.5, 12.5, 180);
            s.assertSucceeded(s.active(), "대포알");
            s.ticks(40);
            assertEquals(s.param("ball_damage"), s.damageBy(s.owner, s.enemy), EPSILON,
                    "the ball dropped off the deck and rolled on");
            s.ticks(60);
            assertNoDisplays(s, "ball");
            for (int x = -3; x <= 3; x++) {
                for (int z = -3; z <= 2; z++) {
                    s.world.setBlock(x, FlatWorld.FLOOR_Y + 1, z, Material.AIR);
                    s.world.setBlock(x, FlatWorld.FLOOR_Y + 2, z, Material.AIR);
                }
            }
            s.place(s.owner, 0.5, 0.5, 0);
            s.finishClean();
        }
    }

    @Test
    void pirateCannonNeedsRoomInFront() {
        try (Scenario s = Scenario.ffa("pirate")) {
            s.world.wallAlongX(-2, 2, 1, 2, Material.STONE);
            ActivationResult blocked = s.active();
            assertFalse(blocked.succeeded(), "a wall right in front");
            assertEquals(0, s.ctx().cooldownRemaining(s.owner), EPSILON, "no cooldown spent");
            assertNoDisplays(s, "no cannon");
            s.world.wallAlongX(-2, 2, 1, 2, Material.AIR);
            s.finishClean();
        }
    }

    /** The ghost ship's own parts (the shells it fires are black iron balls). */
    private static List<BlockDisplay> shipParts(Scenario s) {
        return entities(s, BlockDisplay.class).stream()
                .filter(part -> part.getBlock().getMaterial() != Material.BLACK_CONCRETE).toList();
    }

    @Test
    void pirateGhostShipFollowsThePirateAndShellsWhereItAims() {
        try (Scenario s = Scenario.ffa("pirate")) {
            s.place(s.enemy2, 14.5, -12.5, 180);
            s.world.physics().setEnabled(false); // nobody is shoved off the crosshair
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "유령선 포격");
            s.ticks(12);
            List<BlockDisplay> ship = shipParts(s);
            assertTrue(ship.size() >= 10, "the ghost ship rose: " + ship.size());
            Location owner = s.owner.getLocation();
            for (BlockDisplay part : ship) {
                assertTrue(part.getLocation().getY() > owner.getY() + 2, "above: " + part.getLocation().toVector());
                assertTrue(part.getLocation().getZ() < owner.getZ() - 2, "behind the pirate: "
                        + part.getLocation().toVector());
            }
            assertEquals(2, entities(s, ItemDisplay.class).size(), "soul lanterns");
            s.ticks(s.paramTicks("ult_duration") + 30);
            double maxDamage = s.param("ult_max_hits") * s.param("ult_damage");
            assertEquals(maxDamage, s.damageBy(s.owner, s.enemy), EPSILON,
                    "every shell lands on the aimed enemy, capped at " + s.param("ult_max_hits") + " hits");
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON, "far from the crosshair");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.world.physics().setEnabled(true);
            s.ticks(20);
            assertNoDisplays(s, "the ship sank back into the mist");
            s.finishClean();
        }
    }

    @Test
    void pirateGhostShipKeepsUpWithThePirate() {
        try (Scenario s = Scenario.ffa("pirate")) {
            s.place(s.enemy, 14.5, 14.5, 180);
            s.place(s.enemy2, -14.5, 14.5, 180);
            s.assertSucceeded(s.ultimate(), "유령선 포격");
            s.ticks(10);
            run(s, s.owner, 0.5, 0.5, 12.5, 0.5, 0.4);
            s.ticks(10);
            Location owner = s.owner.getLocation();
            List<BlockDisplay> ship = shipParts(s);
            assertFalse(ship.isEmpty());
            for (BlockDisplay part : ship) {
                assertTrue(part.getLocation().distance(owner) < 7, "the ship follows: "
                        + part.getLocation().toVector() + " vs " + owner.toVector());
                assertTrue(part.getLocation().getX() < owner.getX() - 2, "and stays behind the pirate");
            }
            s.ticks(s.paramTicks("ult_duration") + 40);
            assertNoDisplays(s, "ship");
            s.finishClean();
        }
    }

    // ------------------------------------------------------------------ 스피드스터 speedster

    private static double speed(Player player) {
        return player.getAttribute(Attribute.MOVEMENT_SPEED).getValue();
    }

    @Test
    void speedsterMomentumBuildsWhileRunningAndResetsAfterAStop() {
        try (Scenario s = Scenario.ffa("speedster")) {
            s.place(s.enemy, 20.5, 20.5, 180);
            s.place(s.enemy2, -20.5, 20.5, 180);
            assertEquals(BASE_SPEED, speed(s.owner), EPSILON, "standing still");
            int stepTicks = s.paramTicks("momentum_step");
            double perStack = s.param("momentum_speed") / 100;
            run(s, s.owner, -12.5, -3.5, -12.5 + 0.25 * (stepTicks + 3), -3.5, 0.25);
            assertEquals(BASE_SPEED * (1 + perStack), speed(s.owner), EPSILON, "one step of momentum");
            int stacks = (int) s.param("momentum_stacks");
            double x = s.owner.getLocation().getX();
            run(s, s.owner, x, -3.5, x + 0.25 * stepTicks * (stacks + 1), -3.5, 0.25);
            assertEquals(BASE_SPEED * (1 + perStack * stacks), speed(s.owner), EPSILON, "capped at top speed");
            assertEquals(1, s.owner.getAttribute(Attribute.MOVEMENT_SPEED).getModifiers().size(), "never stacked");

            // A short stop keeps the momentum, a longer one loses it.
            s.ticks(Math.max(1, s.paramTicks("momentum_grace") / 2));
            assertEquals(BASE_SPEED * (1 + perStack * stacks), speed(s.owner), EPSILON, "a breath is fine");
            s.ticks(s.paramTicks("momentum_grace") + 4);
            assertEquals(BASE_SPEED, speed(s.owner), EPSILON, "standing still resets it");
            s.finishClean();
        }
    }

    @Test
    void speedsterDashesThroughEnemiesAndChainsItsCharges() {
        try (Scenario s = Scenario.ffa("speedster")) {
            s.place(s.enemy2, 12.5, 12.5, 180);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "질주 1");
            s.ticks(8);
            Location after = s.owner.getLocation();
            assertEquals(0.5 + s.param("dash_distance"), after.getZ(), 0.8, "dashed " + s.param("dash_distance")
                    + " blocks: " + after.toVector());
            assertEquals(0.5, after.getX(), 0.1, "straight ahead");
            assertEquals(s.param("dash_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "struck in passing");
            assertTrue(s.ctx().cooldownRemaining(s.owner) <= s.param("dash_gap") + EPSILON);

            s.place(s.owner, 0.5, 0.5, 0);
            s.place(s.enemy, 0.5, 4.5, 180);
            s.assertSucceeded(s.active(), "질주 2");
            s.ticks(8);
            assertEquals(2 * s.param("dash_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "again");
            s.place(s.owner, 0.5, 0.5, 0);
            s.assertSucceeded(s.active(), "질주 3");
            s.ticks(2);
            int charges = (int) s.param("dash_charges");
            assertEquals(3, charges, "default charges");
            double left = s.ctx().cooldownRemaining(s.owner);
            assertTrue(left > s.param("dash_gap") && left <= s.param("cooldown"),
                    "out of dashes until the next one recharges: " + left);
            s.place(s.owner, 0.5, 0.5, 0);
            assertFalse(s.active().succeeded(), "no fourth dash yet");
            s.ticks((int) Math.ceil(left * 20) + 1);
            assertEquals(0, s.ctx().cooldownRemaining(s.owner), EPSILON);
            s.assertSucceeded(s.active(), "one dash came back");
            s.ticks(2);
            assertTrue(s.ctx().cooldownRemaining(s.owner) > s.param("cooldown") - 0.5,
                    "and it was the only one: a full recharge");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.ticks(20);
            assertNoDisplays(s, "bolts");
            s.finishClean();
        }
    }

    @Test
    void speedsterDashAtTopSpeedHitsHarder() {
        try (Scenario s = Scenario.ffa("speedster")) {
            s.place(s.enemy2, 20.5, 20.5, 180);
            int stacks = (int) s.param("momentum_stacks");
            run(s, s.owner, -12.5, -4.5, -12.5 + 0.25 * s.paramTicks("momentum_step") * (stacks + 1), -4.5, 0.25);
            double x = s.owner.getLocation().getX();
            s.place(s.enemy, x, -0.5, 180);
            s.place(s.owner, x, -4.5, 0);
            s.assertSucceeded(s.active(), "질주");
            s.ticks(6);
            assertEquals(s.param("dash_damage") * (1 + s.param("momentum_dash_bonus") / 100),
                    s.damageBy(s.owner, s.enemy), EPSILON, "a top-speed dash");
            s.finishClean();
        }
    }

    @Test
    void speedsterDashStopsShortOfWallsAndNeedsRoom() {
        try (Scenario s = Scenario.ffa("speedster")) {
            s.place(s.enemy, 0.5, 10.5, 180);
            s.place(s.enemy2, 6.5, 10.5, 180);
            s.world.wallAlongX(-3, 3, 4, 3, Material.STONE);
            s.assertSucceeded(s.active(), "질주");
            s.ticks(10);
            Location at = s.owner.getLocation();
            assertTrue(at.getZ() < 4 - 0.29, "never inside the wall: " + at.toVector());
            assertTrue(at.getZ() > 1.5, "but as far as it goes: " + at.toVector());
            s.readyActive();
            s.place(s.owner, 0.5, 3.2, 0);
            assertFalse(s.active().succeeded(), "a wall right in front");
            s.world.wallAlongX(-3, 3, 4, 3, Material.AIR);
            s.finishClean();
        }
    }

    @Test
    void speedsterDashOffALedgeLandsSoftly() {
        try (Scenario s = Scenario.ffa("speedster")) {
            s.place(s.enemy, 14.5, 14.5, 180);
            s.place(s.enemy2, -14.5, 14.5, 180);
            for (int x = -2; x <= 2; x++) {
                for (int z = -2; z <= 1; z++) {
                    for (int y = 1; y <= 5; y++) {
                        s.world.setBlock(x, FlatWorld.FLOOR_Y + y, z, Material.STONE);
                    }
                }
            }
            s.place(s.owner, 0.5, FlatWorld.SURFACE + 5, 0.5, 0, 0);
            s.assertSucceeded(s.active(), "질주");
            s.tickUntilGrounded(s.owner, 80);
            assertTrue(s.owner.getLocation().getY() < FlatWorld.SURFACE + 0.5, "down on the floor");
            s.ticks(12);
            assertTrue(s.hitsOf(s.owner, DamageCause.FALL).isEmpty(), "the drop does not hurt");
            for (int x = -2; x <= 2; x++) {
                for (int z = -2; z <= 1; z++) {
                    for (int y = 1; y <= 5; y++) {
                        s.world.setBlock(x, FlatWorld.FLOOR_Y + y, z, Material.AIR);
                    }
                }
            }
            s.place(s.owner, 0.5, 0.5, 0);
            s.finishClean();
        }
    }

    @Test
    void speedsterLightSpeedAfterimagesStrikeAlongThePath() {
        try (Scenario s = Scenario.ffa("speedster")) {
            s.place(s.enemy2, 14.5, 14.5, 180);
            s.place(s.owner, -6.5, 4.5, -90);
            s.assertSucceeded(s.ultimate(), "광속");
            s.ticks(2);
            int echoes = (int) s.param("ult_echoes");
            assertEquals(echoes, entities(s, ItemDisplay.class).size(), "the runner's afterimages");
            assertTrue(speed(s.owner) >= BASE_SPEED * (1 + s.param("ult_speed") / 100) - EPSILON, "light speed");
            assertTrue(s.owner.getAttribute(Attribute.FALL_DAMAGE_MULTIPLIER).getValue() < EPSILON, "no fall damage");

            run(s, s.owner, -6.5, 4.5, 8.5, 4.5, 0.6);
            s.ticks(20);
            assertEquals((1 + echoes) * s.param("ult_damage"), s.damageBy(s.owner, s.enemy), EPSILON,
                    "struck by the runner and by every afterimage once");
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON, "off the path");

            // Standing next to the enemy strikes nothing: only running does.
            s.clearHits();
            s.place(s.owner, 1.5, 4.5, -90);
            s.ticks(30);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "standing still");

            s.ticks(s.paramTicks("ult_duration") + 20);
            assertEquals(BASE_SPEED, speed(s.owner), EPSILON, "back to normal speed");
            assertNoDisplays(s, "the afterimages merged back");
            s.ticks(60);
            assertModifierFree(s.owner, "light speed");
            s.finishClean();
        }
    }

    @Test
    void speedsterLightSpeedCapsHitsPerEnemy() {
        try (Scenario s = Scenario.ffa("speedster")) {
            s.place(s.enemy2, 14.5, 14.5, 180);
            s.place(s.owner, -6.5, 4.5, -90);
            s.assertSucceeded(s.ultimate(), "광속");
            for (int lap = 0; lap < 3; lap++) {
                run(s, s.owner, -6.5, 4.5, 7.5, 4.5, 0.7);
                run(s, s.owner, 7.5, 4.5, -6.5, 4.5, 0.7);
            }
            assertEquals(s.param("ult_max_hits") * s.param("ult_damage"), s.damageBy(s.owner, s.enemy), EPSILON,
                    "at most " + s.param("ult_max_hits") + " strikes");
            for (Scenario.Hit hit : s.hitsBy(s.owner, s.enemy)) {
                assertEquals(DamageType.MAGIC, hit.type());
            }
            s.ticks(200);
            assertNoDisplays(s, "light speed");
            s.finishClean();
        }
    }

    @Test
    void speedsterLightSpeedNeverStrikesAllies() {
        try (Scenario s = Scenario.teams("speedster")) {
            s.place(s.enemy, 14.5, 14.5, 180);
            s.place(s.enemy2, -14.5, 14.5, 180);
            s.place(s.ally(), 0.5, 4.5, 180);
            s.place(s.owner, -6.5, 4.5, -90);
            s.assertSucceeded(s.ultimate(), "광속");
            run(s, s.owner, -6.5, 4.5, 8.5, 4.5, 0.6);
            s.ticks(20);
            assertEquals(0, s.damageTaken(s.ally()), EPSILON);
            assertTrue(Scenario.hasNoHarmfulEffect(s.ally()));
            s.ticks(200);
            s.finishClean();
        }
    }

    @Test
    void speedsterTakesNoFallDamageOnlyWhileAtLightSpeed() {
        try (Scenario s = Scenario.ffa("speedster")) {
            s.assertSucceeded(s.ultimate(), "광속");
            s.ticks(5);
            s.place(s.owner, 0.5, FlatWorld.SURFACE + 8, 0.5, 0, 0);
            s.tickUntilGrounded(s.owner, 80);
            s.ticks(2);
            assertTrue(s.hitsOf(s.owner, DamageCause.FALL).isEmpty(), "light speed lands on its feet");
            s.ticks(s.paramTicks("ult_duration") + 60);
            s.place(s.owner, 0.5, FlatWorld.SURFACE + 8, 0.5, 0, 0);
            s.tickUntilGrounded(s.owner, 80);
            s.ticks(2);
            assertFalse(s.hitsOf(s.owner, DamageCause.FALL).isEmpty(), "later falls hurt as usual");
            s.finishClean();
        }
    }

    @Test
    void speedsterRechargesOnlyUpToItsCharges() {
        try (Scenario s = Scenario.ffa("speedster")) {
            s.place(s.enemy, 14.5, 14.5, 180);
            s.place(s.enemy2, -14.5, 14.5, 180);
            s.ticks(s.paramTicks("cooldown") * 3);
            int charges = (int) s.param("dash_charges");
            for (int i = 0; i < charges; i++) {
                s.place(s.owner, 0.5, 0.5, 0);
                s.assertSucceeded(s.active(), "dash " + (i + 1));
                s.ticks(s.paramTicks("dash_gap") + 1);
            }
            assertFalse(s.active().succeeded(), "a long rest never stores more than " + charges + " dashes");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.finishClean();
        }
    }

    /** Applying the same hunt twice (a refreshed gauge) replaces the first hunt without leaving marks behind. */
    @Test
    void hunterSecondHuntReplacesTheFirst() {
        try (Scenario s = Scenario.ffa("hunter")) {
            s.assertSucceeded(s.ultimate(), "사냥 개시");
            s.ticks(10);
            s.assertSucceeded(s.ultimate(), "사냥 개시 again");
            s.ticks(10);
            assertEquals(4, entities(s, BlockDisplay.class).size(), "one reticle");
            long speedMods = s.owner.getAttribute(Attribute.MOVEMENT_SPEED).getModifiers().stream()
                    .filter(modifier -> modifier.getAmount() > 0).count();
            assertEquals(1, speedMods, "one speed bonus");
            List<AttributeModifier> slows = s.enemy.getAttribute(Attribute.MOVEMENT_SPEED).getModifiers().stream()
                    .filter(modifier -> modifier.getAmount() < 0).toList();
            assertEquals(1, slows.size(), "one slow on the prey");
            s.ticks(s.paramTicks("hunt_duration") + 20);
            assertNoDisplays(s, "hunts");
            s.finishClean();
        }
    }
}
