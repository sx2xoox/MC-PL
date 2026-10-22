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

import io.github.sx2xoox.mcpl.game.superpower.abilities.HexerAbility;
import io.github.sx2xoox.mcpl.game.superpower.abilities.LightAbility;
import io.github.sx2xoox.mcpl.game.superpower.abilities.PsychicAbility;
import io.github.sx2xoox.mcpl.game.superpower.abilities.SonicAbility;
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
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Wolf;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** 빛과 어둠 (light / dark / mind): 빛의 사도, 음파술사, 정신술사, 주술사. */
class MysticNewAbilitiesTest {

    private static final double EPSILON = 1.0E-6;
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z0-9_]+)}");

    static Stream<String> abilities() {
        return Stream.of("light", "sonic", "psychic", "hexer");
    }

    private static double horizontalDistance(Location a, Location b) {
        return Math.hypot(a.getX() - b.getX(), a.getZ() - b.getZ());
    }

    private static long displays(Scenario s) {
        return s.world.getEntities().stream().filter(Display.class::isInstance).count();
    }

    /** A hit with no attacker at all (a stand-in for any damage the victim takes from somewhere else). */
    private static void hurt(TestPlayer victim, double amount) {
        victim.setNoDamageTicks(0);
        victim.damage(amount, DamageSource.builder(DamageType.GENERIC).build());
    }

    // ------------------------------------------------------------------ every ability

    @Test
    void descriptionsOnlyReferenceDeclaredParams() {
        for (AbilityInfo info : List.of(LightAbility.INFO, SonicAbility.INFO, PsychicAbility.INFO,
                HexerAbility.INFO)) {
            for (AbilityInfo.Skill skill : List.of(info.passive(), info.active(), info.ultimate())) {
                Matcher matcher = PLACEHOLDER.matcher(skill.description());
                while (matcher.find()) {
                    assertTrue(info.params().containsKey(matcher.group(1)),
                            info.id() + " " + skill.name() + " uses an undeclared param " + matcher.group());
                }
                assertFalse(skill.name().contains("TODO") || skill.description().contains("TODO"), info.id());
            }
            assertTrue(info.ultimateStandalone(), info.id() + " has a standalone ultimate");
        }
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
            assertTrue(s.damageTaken(s.enemy) > 0, id + " active hurts the aimed enemy");
            assertEquals(0, s.damageTaken(s.owner), EPSILON, id + " active never hurts its owner: " + s.hits());

            AbilityLifecycleTest.prepareUltimate(s);
            s.healAll();
            s.clearHits();
            s.assertSucceeded(s.ultimate(), id + " ultimate");
            s.ticks(300);
            assertTrue(s.damageTaken(s.enemy) > 0, id + " ultimate hurts the aimed enemy");
            assertEquals(0, s.damageTaken(s.owner), EPSILON, id + " ultimate never hurts its owner: " + s.hits());
            assertEquals(0, displays(s), id + ": every display is gone once the skills are over");
            for (TestPlayer enemy : s.enemies()) {
                assertFalse(s.ctx().isSilenced(enemy), id + ": silences wear off");
            }
            s.assertNoProblems();

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
    void ownerDiesDuringUltimate(String id) {
        try (Scenario s = Scenario.ffa(id)) {
            s.ticks(40);
            AbilityLifecycleTest.prepareUltimate(s);
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
            assertFalse(s.ctx().isSilenced(enemy), id + ": " + enemy.getName() + " is not silenced any more");
        }
        assertModifierFree(s.owner, id);
        s.assertNoProblems();
        s.finishMatch();
        s.assertNoProblems();
        s.assertCleanWorld();
    }

    private static void assertModifierFree(Player player, String id) {
        for (Attribute attribute : Registry.ATTRIBUTE) {
            AttributeInstance instance = player.getAttribute(attribute);
            if (instance == null) {
                continue;
            }
            instance.getModifiers().stream()
                    .filter(modifier -> !modifier.getKey().equals(Scenario.TANK))
                    .findFirst()
                    .ifPresent(modifier -> fail(id + ": " + player.getName() + " kept " + modifier.getKey()
                            + " on " + attribute.getKey()));
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
            assertTrue(s.damageTaken(s.enemy) > 0, id + " active still hurts the enemy");

            AbilityLifecycleTest.prepareUltimate(s);
            allyStart = s.ally().getLocation();
            s.assertSucceeded(s.ultimate(), id + " ultimate");
            AbilityLifecycleTest.watchAlly(s, allyStart, 200);
            s.assertNoProblems();
            s.finishClean();
        }
    }

    // ------------------------------------------------------------------ borrowed by the gambler

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

    @ParameterizedTest(name = "{0}")
    @MethodSource("abilities")
    void borrowedUltimateWorksOnAGambler(String id) {
        try (Scenario s = gambler(false)) {
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(borrow(s, id), "borrowed " + id);
            s.ticks(300);
            assertTrue(s.damageBy(s.owner, s.enemy) > 0, "the borrowed " + id + " hurts the aimed enemy");
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "the borrowed " + id + " never hurts the gambler");
            assertModifierFree(s.owner, id);
            for (TestPlayer target : s.enemies()) {
                assertModifierFree(target, id);
                assertFalse(s.ctx().isSilenced(target), id);
            }
            assertEquals(0, displays(s), id + ": displays are gone");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("abilities")
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
    @MethodSource("abilities")
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

    // ------------------------------------------------------------------ 빛의 사도 light

    @Test
    void lightIsShieldedByDayAndSeesInTheDarkAtNight() {
        try (Scenario s = Scenario.ffa("light")) {
            s.world.setTime(6000);
            s.ticks(5);
            s.melee(s.enemy, s.owner, 10);
            assertEquals(10 * (1 - s.param("day_reduction") / 100), s.damageTaken(s.owner), EPSILON, "by day");
            assertFalse(s.owner.hasPotionEffect(PotionEffectType.NIGHT_VISION));

            s.world.setTime(18000);
            s.ticks(25);
            assertTrue(s.owner.hasPotionEffect(PotionEffectType.NIGHT_VISION), "the herald glows at night");
            s.healAll();
            s.clearHits();
            s.melee(s.enemy, s.owner, 10);
            assertEquals(10, s.damageTaken(s.owner), EPSILON, "no sunlight, no shield");

            s.world.setTime(1000);
            s.ticks(6);
            assertFalse(s.owner.hasPotionEffect(PotionEffectType.NIGHT_VISION), "the night vision ends at dawn");
            s.finishClean();
        }
    }

    @Test
    void lightKeepsNightVisionItDidNotGive() {
        try (Scenario s = Scenario.ffa("light")) {
            s.world.setTime(1000);
            s.owner.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, 1200, 0));
            s.ticks(25);
            assertTrue(s.owner.hasPotionEffect(PotionEffectType.NIGHT_VISION), "a potion is not taken away");
            s.finishClean();
        }
    }

    @Test
    void lightFlashBlindsAndRevealsTheEnemiesInFront() {
        try (Scenario s = Scenario.ffa("light")) {
            s.place(s.enemy2, 0.5, -4.5, 0);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "섬광");
            assertEquals(1, s.world.getEntities().stream().filter(TextDisplay.class::isInstance).count(),
                    "a star blooms in the hand");
            assertEquals(5, s.world.getEntities().stream().filter(BlockDisplay.class::isInstance).count(),
                    "shafts of light across the cone");
            assertEquals(s.param("flash_damage"), s.damageBy(s.owner, s.enemy), EPSILON);
            PotionEffect blind = s.enemy.getPotionEffect(PotionEffectType.BLINDNESS);
            assertNotNull(blind, "blinded");
            assertEquals(s.paramTicks("flash_blind"), blind.getDuration(), "it stared right into the flash");
            assertTrue(s.enemy.hasPotionEffect(PotionEffectType.GLOWING), "revealed");

            assertEquals(0, s.damageTaken(s.enemy2), EPSILON, "behind the herald");
            assertFalse(s.enemy2.hasPotionEffect(PotionEffectType.BLINDNESS));
            assertFalse(s.enemy2.hasPotionEffect(PotionEffectType.GLOWING));
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            assertFalse(s.owner.hasPotionEffect(PotionEffectType.BLINDNESS));
            s.ticks(20);
            assertEquals(0, displays(s), "the flash star is gone");
            s.finishClean();
        }
    }

    @Test
    void lightFlashBlindsEnemiesLookingAwayForHalfAsLong() {
        try (Scenario s = Scenario.ffa("light")) {
            s.place(s.enemy, 0.5, 4.5, 0);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "섬광");
            PotionEffect blind = s.enemy.getPotionEffect(PotionEffectType.BLINDNESS);
            assertNotNull(blind);
            assertEquals(s.paramTicks("flash_blind") / 2, blind.getDuration(), "it had its back turned");
            s.finishClean();
        }
    }

    @Test
    void lightFlashIsBlockedByWalls() {
        try (Scenario s = Scenario.ffa("light")) {
            s.aimAt(s.owner, s.enemy);
            s.world.wallAlongX(-4, 5, 3, 3, Material.STONE);
            s.assertSucceeded(s.active(), "섬광");
            assertEquals(0, s.damageTaken(s.enemy) + s.damageTaken(s.enemy2), EPSILON);
            assertFalse(s.enemy.hasPotionEffect(PotionEffectType.BLINDNESS));
            s.world.wallAlongX(-4, 5, 3, 3, Material.AIR);
            s.finishClean();
        }
    }

    @Test
    void lightJudgmentSmitesEnemiesAndHealsAlliesAfterTheWarning() {
        try (Scenario s = Scenario.teams("light")) {
            s.crossfireAlly();
            s.ally().setHealth(s.maxHealth(s.ally()) - 10);
            double allyBefore = s.health(s.ally());
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "심판의 빛");
            assertEquals(3, s.world.getEntities().stream().filter(BlockDisplay.class::isInstance).count(),
                    "a thread of light and a golden star mark the spot");
            s.ticks(s.paramTicks("ult_delay") - 2);
            assertEquals(0, s.damageTaken(s.enemy) + s.damageTaken(s.enemy2), EPSILON, "dodgeable: nothing yet");
            s.ticks(4);
            for (TestPlayer enemy : s.enemies()) {
                assertEquals(s.param("ult_damage"), s.damageBy(s.owner, enemy), EPSILON, enemy.getName());
                assertEquals(s.maxHealth(enemy) - s.param("ult_damage"), s.health(enemy), EPSILON,
                        "enemies are not healed");
            }
            assertEquals(allyBefore + s.param("ult_heal"), s.health(s.ally()), EPSILON, "the ally is healed");
            assertEquals(0, s.damageTaken(s.ally()), EPSILON);
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.ticks(60);
            assertEquals(0, displays(s), "the column fades away");
            s.finishClean();
        }
    }

    @Test
    void lightJudgmentMissesEnemiesThatLeftTheCircle() {
        try (Scenario s = Scenario.ffa("light")) {
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "심판의 빛");
            s.place(s.enemy, 0.5 + s.param("ult_radius") + 2, 4.5, 180);
            s.place(s.enemy2, 0.5, 20.5, 180);
            s.ticks(s.paramTicks("ult_delay") + 4);
            assertEquals(0, s.damageTaken(s.enemy) + s.damageTaken(s.enemy2), EPSILON, "they ran in time");
            s.finishClean();
        }
    }

    @Test
    void lightJudgmentNeedsSomewhereToLand() {
        try (Scenario s = Scenario.ffa("light")) {
            s.place(s.owner, 0.5, FlatWorld.SURFACE, 0.5, 0, -90);
            assertFalse(s.ultimate().succeeded(), "aimed at the open sky");
            assertEquals(100, s.participant(s.owner).gauge(), EPSILON, "the gauge is kept");
            s.finishClean();
        }
    }

    // ------------------------------------------------------------------ 음파술사 sonic

    @Test
    void sonicFeelsEnemiesThatRunOrUseSkills() {
        try (Scenario s = Scenario.ffa("sonic")) {
            s.ticks(8);
            assertFalse(s.enemy.hasPotionEffect(PotionEffectType.GLOWING), "standing still: silent");

            s.enemy.setSprinting(true);
            s.ticks(5);
            assertTrue(s.enemy.hasPotionEffect(PotionEffectType.GLOWING), "running: felt");
            s.enemy.setSprinting(false);
            s.enemy.removePotionEffect(PotionEffectType.GLOWING);

            s.enemy2.setSneaking(true);
            s.enemy2.setSprinting(true);
            s.ticks(5);
            assertFalse(s.enemy2.hasPotionEffect(PotionEffectType.GLOWING), "sneaking: unheard");
            s.enemy2.setSprinting(false);
            s.enemy2.setSneaking(false);

            s.assertSucceeded(s.match.useActive(s.enemy2), "the enemy's skill");
            s.ticks(5);
            assertTrue(s.enemy2.hasPotionEffect(PotionEffectType.GLOWING), "a skill: felt");

            s.place(s.enemy, 0.5, 2 + s.param("sense_radius"), 180);
            s.enemy.setSprinting(true);
            s.ticks(5);
            assertFalse(s.enemy.hasPotionEffect(PotionEffectType.GLOWING), "too far away");
            s.enemy.setSprinting(false);
            assertEquals(0, s.damageTaken(s.enemy) + s.damageTaken(s.enemy2), EPSILON, "sensing does not hurt");
            s.finishClean();
        }
    }

    @Test
    void sonicHearsASkillEvenFromASneakingEnemy() {
        try (Scenario s = Scenario.ffa("sonic")) {
            s.ticks(8);
            s.enemy.setSneaking(true);
            s.assertSucceeded(s.match.useActive(s.enemy), "the enemy's skill");
            s.ticks(5);
            assertTrue(s.enemy.hasPotionEffect(PotionEffectType.GLOWING), "sneaking hides footsteps, not skills");
            s.enemy.setSneaking(false);
            s.finishClean();
        }
    }

    @Test
    void sonicBoomPiercesEveryEnemyInTheLineAndPushesThem() {
        try (Scenario s = Scenario.ffa("sonic")) {
            s.place(s.enemy2, 0.5, 9.5, 180);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "음파 폭발");
            assertEquals(3, s.world.getEntities().stream().filter(ItemDisplay.class::isInstance).count(),
                    "echo shards gather");
            s.ticks(s.paramTicks("boom_charge") - 1);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "still charging");
            s.ticks(5);
            for (TestPlayer enemy : s.enemies()) {
                assertEquals(1, s.hitsBy(s.owner, enemy).size(), enemy.getName() + " is hit once");
                assertEquals(s.param("boom_damage"), s.damageBy(s.owner, enemy), EPSILON, enemy.getName());
                assertEquals(DamageType.MAGIC, s.hitsBy(s.owner, enemy).getFirst().type(), "ignores armor");
            }
            assertTrue(s.enemy.getLocation().getZ() > 5.0, "pushed back: " + s.enemy.getLocation().getZ());
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.finishClean();
        }
    }

    @Test
    void sonicBoomReachesItsRangeOnly() {
        try (Scenario s = Scenario.ffa("sonic")) {
            s.place(s.enemy2, 0.5, 3.5 + s.param("boom_range"), 180);
            s.place(s.owner, 0.5, 0.5, 0);
            s.assertSucceeded(s.active(), "음파 폭발");
            s.ticks(s.paramTicks("boom_charge") + 10);
            assertEquals(s.param("boom_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "in range");
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON, "beyond " + s.param("boom_range") + " blocks");
            s.ticks(20);
            assertEquals(0, displays(s), "the echo shards dissolved");
            s.finishClean();
        }
    }

    @Test
    void sonicBoomCannotPassWalls() {
        try (Scenario s = Scenario.ffa("sonic")) {
            s.aimAt(s.owner, s.enemy);
            s.world.wallAlongX(-4, 5, 3, 3, Material.STONE);
            s.assertSucceeded(s.active(), "음파 폭발");
            s.ticks(20);
            assertEquals(0, s.damageTaken(s.enemy) + s.damageTaken(s.enemy2), EPSILON);
            s.world.wallAlongX(-4, 5, 3, 3, Material.AIR);
            s.finishClean();
        }
    }

    @Test
    void sonicVeilDarkensSilencesAndHurtsEveryEnemyItReaches() {
        try (Scenario s = Scenario.ffa("sonic")) {
            s.place(s.enemy2, 0.5, 4 + s.param("ult_radius"), 180);
            s.assertSucceeded(s.ultimate(), "어둠의 장막");
            assertEquals(4, s.world.getEntities().stream().filter(BlockDisplay.class::isInstance).count(),
                    "shriekers rise around the listener");
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "the shriek comes first");
            s.ticks(30);
            assertEquals(s.param("ult_damage"), s.damageBy(s.owner, s.enemy), EPSILON);
            PotionEffect darkness = s.enemy.getPotionEffect(PotionEffectType.DARKNESS);
            assertNotNull(darkness, "in the dark");
            assertTrue(darkness.getDuration() > s.paramTicks("ult_darkness") - 30
                    && darkness.getDuration() <= s.paramTicks("ult_darkness"), "for " + s.param("ult_darkness") + " s");
            assertTrue(s.ctx().isSilenced(s.enemy), "silenced");

            assertEquals(0, s.damageTaken(s.enemy2), EPSILON, "out of reach");
            assertFalse(s.enemy2.hasPotionEffect(PotionEffectType.DARKNESS));
            assertFalse(s.ctx().isSilenced(s.enemy2));
            assertFalse(s.owner.hasPotionEffect(PotionEffectType.DARKNESS));

            s.ticks(s.paramTicks("ult_silence"));
            assertFalse(s.ctx().isSilenced(s.enemy), "the silence wears off");
            assertEquals(0, displays(s), "the sculk sinks away");
            s.finishClean();
        }
    }

    @Test
    void sonicVeilNeedsAnEnemyInRange() {
        try (Scenario s = Scenario.ffa("sonic")) {
            s.place(s.enemy, 30.5, 0.5, 0);
            s.place(s.enemy2, -30.5, 0.5, 0);
            assertFalse(s.ultimate().succeeded());
            assertEquals(100, s.participant(s.owner).gauge(), EPSILON, "the gauge is kept");
            s.finishClean();
        }
    }

    // ------------------------------------------------------------------ 정신술사 psychic

    @Test
    void psychicDisruptionDelaysTheAttackersNextSkill() {
        try (Scenario s = Scenario.ffa("psychic")) {
            s.melee(s.enemy, s.owner, 2);
            assertEquals(1, s.world.getEntities().stream().filter(TextDisplay.class::isInstance).count(),
                    "a star of static over the attacker");
            s.ticks(3);
            assertTrue(s.ctx().isSilenced(s.enemy), "its skill was ready: it comes back late");
            s.ticks(s.paramTicks("disrupt_delay") + 1);
            assertFalse(s.ctx().isSilenced(s.enemy));
            s.melee(s.enemy, s.owner, 2);
            s.ticks(3);
            assertFalse(s.ctx().isSilenced(s.enemy), "once every " + s.param("disrupt_cooldown") + " s per enemy");

            s.assertSucceeded(s.match.useActive(s.enemy2), "enemy2's skill");
            s.melee(s.enemy2, s.owner, 2);
            s.ticks(5);
            assertFalse(s.ctx().isSilenced(s.enemy2), "still on cooldown: nothing yet");
            assertEquals(1, s.world.getEntities().stream().filter(TextDisplay.class::isInstance).count(),
                    "the star waits over its head");
            s.ctx().reduceCooldown(s.enemy2, 1000);
            s.ticks(3);
            assertTrue(s.ctx().isSilenced(s.enemy2), "the moment its skill would be ready");
            s.ticks(10);
            assertEquals(0, displays(s), "the star popped");
            assertEquals(0, s.damageTaken(s.enemy) + s.damageTaken(s.enemy2), EPSILON, "no damage");
            s.finishClean();
        }
    }

    @Test
    void psychicThrowLiftsTheEnemyAndHurlsItWhereThePsychicLooks() {
        try (Scenario s = Scenario.ffa("psychic")) {
            s.place(s.enemy2, -10.5, 0.5, 0);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "염동 투척");
            assertEquals(s.param("throw_damage"), s.damageBy(s.owner, s.enemy), EPSILON);
            assertTrue(s.world.getEntities().stream().anyMatch(BlockDisplay.class::isInstance), "a psychic bubble");
            s.ticks(s.paramTicks("lift_time") - 2);
            assertTrue(s.enemy.getLocation().getY() > FlatWorld.SURFACE + 0.8, "lifted: " + s.enemy.getLocation());
            assertEquals(4.5, s.enemy.getLocation().getZ(), 0.3, "held in place while lifted");
            s.ticks(30);
            double z = s.enemy.getLocation().getZ();
            assertTrue(z > 4.5 + s.param("throw_distance") - 2, "thrown along the view: " + z);
            assertEquals(0.5, s.enemy.getLocation().getX(), 0.3, "straight ahead");
            assertEquals(s.param("throw_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "no wall, no crunch");
            s.ticks(40);
            assertTrue(s.hitsOf(s.enemy, DamageCause.FALL).isEmpty(), "no fall damage");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            assertEquals(0, displays(s), "the bubble burst");
            s.finishClean();
        }
    }

    @Test
    void psychicThrowCrunchesIntoWalls() {
        try (Scenario s = Scenario.ffa("psychic")) {
            s.place(s.enemy2, -10.5, 0.5, 0);
            s.world.wallAlongX(-3, 3, 8, 4, Material.STONE);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "염동 투척");
            double crunch = s.param("throw_damage") + s.param("slam_damage");
            s.h.tickUntil(() -> s.damageBy(s.owner, s.enemy) >= crunch - EPSILON, 40, "the crunch");
            PotionEffect slow = s.enemy.getPotionEffect(PotionEffectType.SLOWNESS);
            assertNotNull(slow, "staggered");
            assertEquals(2, slow.getAmplifier());
            s.ticks(30);
            assertEquals(crunch, s.damageBy(s.owner, s.enemy), EPSILON, "one crunch");
            assertTrue(s.enemy.getLocation().getZ() < 8, "stopped by the wall");
            assertFalse(s.world.hasCollisionsIn(s.enemy.getBoundingBox().expand(-0.01)), "never inside the wall");
            s.world.wallAlongX(-3, 3, 8, 4, Material.AIR);
            s.finishClean();
        }
    }

    @Test
    void psychicThrowCrunchesIntoWallsEvenWhenTheServerSeesTheTargetLate() {
        try (Scenario s = Scenario.ffa("psychic")) {
            s.place(s.enemy2, -10.5, 0.5, 0);
            s.world.wallAlongX(-3, 3, 8, 4, Material.STONE);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "염동 투척");
            s.ticks(s.paramTicks("lift_time"));
            // A laggy client: from the release on, the server never hears where the thrown player went.
            s.world.physics().setEnabled(false);
            double crunch = s.param("throw_damage") + s.param("slam_damage");
            s.h.tickUntil(() -> s.damageBy(s.owner, s.enemy) >= crunch - EPSILON, 20,
                    "the crunch, judged on the throw's own path");
            PotionEffect slow = s.enemy.getPotionEffect(PotionEffectType.SLOWNESS);
            assertNotNull(slow, "staggered");
            s.world.physics().setEnabled(true);
            s.ticks(30);
            assertEquals(crunch, s.damageBy(s.owner, s.enemy), EPSILON, "one crunch");
            s.world.wallAlongX(-3, 3, 8, 4, Material.AIR);
            s.finishClean();
        }
    }

    @Test
    void psychicThrowCrunchesASummonIntoAWallToo() {
        try (Scenario s = Scenario.ffa("psychic")) {
            s.place(s.enemy, 8.5, 0.5, 180);
            s.place(s.enemy2, -10.5, 0.5, 0);
            Wolf wolf = s.world.spawn(new Location(s.world, 0.5, FlatWorld.SURFACE, 4.5), Wolf.class);
            wolf.getAttribute(Attribute.MAX_HEALTH).setBaseValue(40);
            wolf.setHealth(40);
            s.ctx().summon(s.enemy, wolf);
            s.world.wallAlongX(-3, 3, 8, 4, Material.STONE);
            s.aimAt(s.owner, wolf);
            s.assertSucceeded(s.active(), "염동 투척 on a summon");
            double crunch = s.param("throw_damage") + s.param("slam_damage");
            s.h.tickUntil(() -> s.damageBy(s.owner, wolf) >= crunch - EPSILON, 40, "the crunch");
            s.ticks(30);
            assertEquals(crunch, s.damageBy(s.owner, wolf), EPSILON, "one crunch");
            assertTrue(wolf.getLocation().getZ() < 8, "stopped by the wall");
            s.world.wallAlongX(-3, 3, 8, 4, Material.AIR);
            s.finishClean();
        }
    }

    @Test
    void psychicLiftHoldsItsHeightEvenWhenTheServerSeesTheTargetLate() {
        try (Scenario s = Scenario.ffa("psychic")) {
            s.place(s.enemy2, -10.5, 0.5, 0);
            s.aimAt(s.owner, s.enemy);
            // A laggy client: the server never hears that the lifted player rose.
            s.world.physics().setEnabled(false);
            s.assertSucceeded(s.active(), "염동 투척");
            double rise = 0;
            for (int i = 0; i < s.paramTicks("lift_time") - 1; i++) {
                s.ticks(1);
                rise += s.enemy.getVelocity().getY();
            }
            // The client moves by exactly the velocities it is sent: their sum is how high it really floats.
            assertTrue(rise < 1.7, "lifted about 1.6 blocks, not flung up: " + rise);
            assertTrue(rise > 1.0, "but lifted: " + rise);
            s.world.physics().setEnabled(true);
            s.ticks(60);
            s.finishClean();
        }
    }

    @Test
    void psychicThrowStopsWhenThePsychicFallsMidFlight() {
        try (Scenario s = Scenario.ffa("psychic")) {
            s.place(s.enemy2, -10.5, 0.5, 0);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "염동 투척");
            s.ticks(s.paramTicks("lift_time") + 2);
            double z = s.enemy.getLocation().getZ();
            assertTrue(z > 4.5 + 0.5, "in flight: " + z);
            s.killOwner(s.enemy2);
            s.ticks(40);
            assertTrue(s.enemy.getLocation().getZ() < z + 2, "the grip is gone, so is the throw's speed: "
                    + s.enemy.getLocation().getZ() + " (was " + z + ")");
            assertTrue(s.hitsOf(s.enemy, DamageCause.FALL).isEmpty(), "no fall damage");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void psychicThrowCrunchesTwoEnemiesTogether() {
        try (Scenario s = Scenario.ffa("psychic")) {
            s.place(s.enemy2, 0.5, 9.5, 180);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "염동 투척");
            s.h.tickUntil(() -> s.damageBy(s.owner, s.enemy2) > 0, 40, "the crunch");
            assertTrue(s.enemy.hasPotionEffect(PotionEffectType.SLOWNESS));
            assertTrue(s.enemy2.hasPotionEffect(PotionEffectType.SLOWNESS));
            s.ticks(30);
            assertEquals(s.param("throw_damage") + s.param("slam_damage"), s.damageBy(s.owner, s.enemy), EPSILON);
            assertEquals(s.param("slam_damage"), s.damageBy(s.owner, s.enemy2), EPSILON, "the other one too");
            assertTrue(s.enemy2.getLocation().getZ() > 9.8, "and it is knocked on");
            s.finishClean();
        }
    }

    @Test
    void psychicThrowNeverLeavesTheArena() {
        try (Scenario s = Scenario.ffa("psychic")) {
            double edge = 0.5;
            while (edge < 400 && s.ctx().isInsideArena(new Location(s.world, 0.5, FlatWorld.SURFACE, edge + 0.5))) {
                edge += 0.5;
            }
            assertTrue(edge < 400, "the arena has an edge");
            s.place(s.enemy2, -10.5, 0.5, 0);
            s.place(s.owner, 0.5, edge - 6, 0);
            s.place(s.enemy, 0.5, edge - 2.5, 180);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "염동 투척");
            s.ticks(60);
            assertTrue(s.ctx().isInsideArena(s.enemy.getLocation()), "thrown, but not out of the arena: "
                    + s.enemy.getLocation().getZ() + " (edge " + edge + ")");
            s.finishClean();
        }
    }

    @Test
    void psychicThrowLetsGoOfATargetThatTeleportsAway() {
        try (Scenario s = Scenario.ffa("psychic")) {
            s.place(s.enemy2, -10.5, 0.5, 0);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "염동 투척");
            s.ticks(4);
            s.place(s.enemy, 12.5, 4.5, 180);
            s.ticks(30);
            assertEquals(12.5, s.enemy.getLocation().getX(), 0.5, "not dragged back");
            assertEquals(4.5, s.enemy.getLocation().getZ(), 0.5, "not thrown either");
            assertEquals(s.param("throw_damage"), s.damageBy(s.owner, s.enemy), EPSILON);
            assertEquals(0, displays(s), "the bubble burst");
            s.finishClean();
        }
    }

    @Test
    void psychicThrowNeedsAnAimedEnemy() {
        try (Scenario s = Scenario.ffa("psychic")) {
            s.place(s.owner, 0.5, 0.5, 180);
            assertFalse(s.active().succeeded());
            s.finishClean();
        }
    }

    @Test
    void psychicCollapseScramblesEveryEnemyNearby() {
        try (Scenario s = Scenario.ffa("psychic")) {
            s.place(s.enemy2, 0.5, 4 + s.param("ult_radius"), 180);
            Location start = s.enemy.getLocation();
            s.assertSucceeded(s.ultimate(), "정신 붕괴");
            assertEquals(s.param("ult_damage"), s.damageBy(s.owner, s.enemy), EPSILON);
            assertTrue(s.enemy.hasPotionEffect(PotionEffectType.NAUSEA), "nauseous");
            assertTrue(s.ctx().isSilenced(s.enemy), "silenced");
            assertEquals(1, s.world.getEntities().stream().filter(ItemDisplay.class::isInstance).count(),
                    "a brain coral crown over the victim");
            s.ticks(s.paramTicks("ult_jolt") + 2);
            assertTrue(horizontalDistance(s.enemy.getLocation(), start) > 0.1, "it staggers");

            assertEquals(0, s.damageTaken(s.enemy2), EPSILON, "out of range");
            assertFalse(s.enemy2.hasPotionEffect(PotionEffectType.NAUSEA));
            assertFalse(s.ctx().isSilenced(s.enemy2));
            assertFalse(s.owner.hasPotionEffect(PotionEffectType.NAUSEA));

            s.ticks(s.paramTicks("ult_duration") + 10);
            assertFalse(s.ctx().isSilenced(s.enemy), "the mind comes back");
            assertEquals(0, displays(s), "the crown shatters");
            assertTrue(s.hitsOf(s.enemy, DamageCause.FALL).isEmpty(), "staggering never causes fall damage");
            s.finishClean();
        }
    }

    @Test
    void psychicCollapseNeedsAnEnemyInRange() {
        try (Scenario s = Scenario.ffa("psychic")) {
            s.place(s.enemy, 30.5, 0.5, 0);
            s.place(s.enemy2, -30.5, 0.5, 0);
            assertFalse(s.ultimate().succeeded());
            assertEquals(100, s.participant(s.owner).gauge(), EPSILON, "the gauge is kept");
            s.finishClean();
        }
    }

    // ------------------------------------------------------------------ 주술사 hexer

    @Test
    void hexerMarksStackUpAndMakeItsHitsHurtMore() {
        try (Scenario s = Scenario.ffa("hexer")) {
            double bonus = s.param("stack_bonus") / 100;
            int max = (int) s.param("max_stacks");
            double expected = 0;
            for (int i = 0; i < max + 2; i++) {
                s.melee(s.owner, s.enemy, 4);
                expected += 4 * (1 + Math.min(i, max) * bonus);
                assertEquals(expected, s.damageBy(s.owner, s.enemy), EPSILON, "hit " + (i + 1));
            }
            s.melee(s.owner, s.enemy2, 4);
            assertEquals(4, s.damageBy(s.owner, s.enemy2), EPSILON, "marks are per enemy");

            s.ticks(s.paramTicks("stack_duration") + 5);
            s.clearHits();
            s.melee(s.owner, s.enemy, 4);
            assertEquals(4, s.damageBy(s.owner, s.enemy), EPSILON, "the marks faded");
            s.finishClean();
        }
    }

    @Test
    void hexerMarksComeFromArrowsToo() {
        try (Scenario s = Scenario.ffa("hexer")) {
            Arrow arrow = s.world.spawnArrow(s.owner.getEyeLocation(), new Vector(0, 0, 1), 1, 0);
            arrow.setShooter(s.owner);
            s.enemy.setNoDamageTicks(0);
            s.enemy.damage(4, DamageSource.builder(DamageType.ARROW).withCausingEntity(s.owner)
                    .withDirectEntity(arrow).build());
            arrow.remove();
            assertEquals(4, s.damageBy(s.owner, s.enemy), EPSILON);
            s.melee(s.owner, s.enemy, 4);
            assertEquals(4 + 4 * (1 + s.param("stack_bonus") / 100), s.damageBy(s.owner, s.enemy), EPSILON,
                    "the arrow carved a mark");
            s.finishClean();
        }
    }

    @Test
    void hexerSkillDamageDoesNotCarveMarks() {
        try (Scenario s = Scenario.builder("hexer").param("doll_stacks", 0).start()) {
            s.place(s.enemy2, 0.5, -20.5, 0);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "부두 인형");
            s.clearHits();
            s.ticks(s.paramTicks("doll_duration") + 5);
            s.melee(s.owner, s.enemy, 4);
            assertEquals(4, s.damageBy(s.owner, s.enemy), EPSILON, "only melee and arrows carve marks");
            s.finishClean();
        }
    }

    @Test
    void hexerVoodooDollPassesOnTheKillingBlow() {
        try (Scenario s = Scenario.builder("hexer").param("doll_stacks", 0).start()) {
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "부두 인형");
            s.ticks(2);
            double left = s.health(s.enemy);
            hurt(s.enemy, 10_000);
            assertTrue(s.enemy.isDead() || !s.session.isAlive(s.enemy), "the victim died");
            s.ticks(2);
            assertEquals(left * s.param("doll_share") / 100, s.damageBy(s.owner, s.enemy2), EPSILON,
                    "even the killing blow is passed on");
            s.respawn(s.enemy);
            s.ticks(20);
            assertEquals(0, displays(s), "the doll crumbles with its victim");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void hexerVoodooDollPassesThePainOnToNearbyEnemies() {
        try (Scenario s = Scenario.builder("hexer").param("doll_stacks", 0).start()) {
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "부두 인형");
            assertEquals(s.param("doll_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "the first needle");
            assertEquals(1, s.world.getEntities().stream().filter(ItemDisplay.class::isInstance).count(),
                    "the doll wears the victim's head");
            assertEquals(4 + 1 + 1, s.world.getEntities().stream().filter(BlockDisplay.class::isInstance).count(),
                    "body, arms and legs, a needle and the yarn");
            s.ticks(2);
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON, "the doll's own needle is not passed on");

            hurt(s.enemy, 10);
            s.ticks(2);
            assertEquals(10 * s.param("doll_share") / 100, s.damageBy(s.owner, s.enemy2), EPSILON, "passed on");
            assertEquals(s.param("doll_damage"), s.damageBy(s.owner, s.enemy), EPSILON,
                    "no echo while others are near");

            s.ticks(s.paramTicks("doll_duration") + 5);
            s.clearHits();
            hurt(s.enemy, 10);
            s.ticks(3);
            assertEquals(0, s.damageBy(s.owner, s.enemy2), EPSILON, "the doll is gone");
            s.ticks(20);
            assertEquals(0, displays(s), "the doll unravelled");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.finishClean();
        }
    }

    @Test
    void hexerVoodooDollEchoesIntoALoneVictim() {
        try (Scenario s = Scenario.builder("hexer").param("doll_stacks", 0).start()) {
            s.place(s.enemy2, 0.5, -20.5, 0);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "부두 인형");
            s.ticks(2);
            hurt(s.enemy, 10);
            s.ticks(2);
            assertEquals(s.param("doll_damage") + 10 * s.param("doll_echo") / 100, s.damageBy(s.owner, s.enemy),
                    EPSILON, "the needle sinks deeper");
            s.ticks(3);
            assertEquals(s.param("doll_damage") + 10 * s.param("doll_echo") / 100, s.damageBy(s.owner, s.enemy),
                    EPSILON, "an echo is not echoed again");
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON);
            s.finishClean();
        }
    }

    @Test
    void hexerVoodooDollCarvesMarks() {
        try (Scenario s = Scenario.ffa("hexer")) {
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "부두 인형");
            s.clearHits();
            s.melee(s.owner, s.enemy, 4);
            assertEquals(4 * (1 + s.param("doll_stacks") * s.param("stack_bonus") / 100),
                    s.damageBy(s.owner, s.enemy), EPSILON);
            s.finishClean();
        }
    }

    @Test
    void hexerSoulChainsShareThePainOfEveryBoundEnemy() {
        try (Scenario s = Scenario.builder("hexer").param("ult_drain", 0).start()) {
            s.assertSucceeded(s.ultimate(), "영혼 사슬");
            for (TestPlayer enemy : s.enemies()) {
                assertEquals(s.param("ult_damage"), s.damageBy(s.owner, enemy), EPSILON, enemy.getName());
                PotionEffect slow = enemy.getPotionEffect(PotionEffectType.SLOWNESS);
                assertNotNull(slow, "bound");
                assertEquals(0, slow.getAmplifier(), "slowness I");
            }
            assertEquals(2, s.world.getEntities().stream().filter(BlockDisplay.class::isInstance).count(),
                    "a chain lashes out to each enemy");
            s.ticks(2);
            assertEquals(2, s.world.getEntities().stream().filter(ItemDisplay.class::isInstance).count(),
                    "a soul skull over each bound enemy");

            hurt(s.enemy, 10);
            s.ticks(2);
            assertEquals(s.param("ult_damage") + 10 * s.param("ult_share") / 100, s.damageBy(s.owner, s.enemy2),
                    EPSILON, "shared");
            s.ticks(3);
            assertEquals(s.param("ult_damage"), s.damageBy(s.owner, s.enemy), EPSILON,
                    "shared pain does not bounce back");

            s.ticks(s.paramTicks("ult_duration") + 10);
            assertEquals(0, displays(s), "the chains break");
            s.clearHits();
            hurt(s.enemy, 10);
            s.ticks(3);
            assertEquals(0, s.damageBy(s.owner, s.enemy2), EPSILON, "over");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.finishClean();
        }
    }

    @Test
    void hexerSoulChainsDrainEvenALoneEnemy() {
        try (Scenario s = Scenario.ffa("hexer")) {
            s.place(s.enemy2, 0.5, -30.5, 0);
            s.assertSucceeded(s.ultimate(), "영혼 사슬");
            s.ticks(s.paramTicks("ult_duration") + 5);
            double drains = Math.floor(s.param("ult_duration") / s.param("ult_tick"));
            assertEquals(s.param("ult_damage") + drains * s.param("ult_drain"), s.damageBy(s.owner, s.enemy),
                    EPSILON);
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON, "out of range");
            assertNull(s.enemy2.getPotionEffect(PotionEffectType.SLOWNESS));
            s.finishClean();
        }
    }

    @Test
    void hexerSoulChainsBindABigCrowdOnAParticleBudget() {
        try (Scenario s = Scenario.ffa("hexer")) {
            // The enemy's summons count as enemies too: a crowd of a dozen bound at once.
            List<Wolf> crowd = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                double angle = i * Math.PI * 2 / 10;
                Location at = new Location(s.world, 0.5 + Math.cos(angle) * 9, FlatWorld.SURFACE,
                        0.5 + Math.sin(angle) * 9);
                Wolf wolf = s.world.spawn(at, Wolf.class);
                wolf.getAttribute(Attribute.MAX_HEALTH).setBaseValue(40);
                wolf.setHealth(40);
                crowd.add(s.ctx().summon(s.enemy, wolf));
            }
            s.assertSucceeded(s.ultimate(), "영혼 사슬 on a crowd");
            s.ticks(s.paramTicks("ult_duration") + 10);
            double drains = Math.floor(s.param("ult_duration") / s.param("ult_tick"));
            for (Wolf wolf : crowd) {
                assertEquals(s.param("ult_damage") + drains * s.param("ult_drain"), s.damageBy(s.owner, wolf),
                        EPSILON, "every one of the crowd is bound and drained");
            }
            assertEquals(0, displays(s), "the chains break");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void hexerSoulChainsNeedAnEnemyInRange() {
        try (Scenario s = Scenario.ffa("hexer")) {
            s.place(s.enemy, 30.5, 0.5, 0);
            s.place(s.enemy2, -30.5, 0.5, 0);
            assertFalse(s.ultimate().succeeded());
            assertEquals(100, s.participant(s.owner).gauge(), EPSILON, "the gauge is kept");
            s.finishClean();
        }
    }
}
