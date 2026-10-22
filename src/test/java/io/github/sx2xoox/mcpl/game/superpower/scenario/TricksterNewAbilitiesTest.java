package io.github.sx2xoox.mcpl.game.superpower.scenario;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.List;
import java.util.stream.Stream;

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
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Skeleton;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Zombie;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffectType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 주술 · 트릭 계열 (새 능력): 사령술사, 음유시인, 도둑. Every ability through a whole match, the owner dying in the middle of
 * each skill, team mode with an ally in the line of fire, and the standalone ultimates borrowed by a Gambler.
 */
class TricksterNewAbilitiesTest {

    private static final double EPSILON = 1.0E-6;

    static Stream<String> abilities() {
        return Stream.of("necromancer", "bard", "thief");
    }

    /** The abilities whose ultimate the Gambler may borrow. */
    static Stream<String> standalone() {
        return Stream.of("necromancer", "bard");
    }

    // ------------------------------------------------------------------ helpers

    private static <T extends Entity> List<T> entities(Scenario s, Class<T> type) {
        return s.world.getEntities().stream().filter(type::isInstance).map(type::cast).toList();
    }

    private static List<Mob> undead(Scenario s) {
        return s.world.getEntities().stream().filter(e -> e instanceof Zombie || e instanceof Skeleton)
                .map(Mob.class::cast).toList();
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

    private static void assertNoDisplays(Scenario s, String what) {
        assertTrue(entities(s, Display.class).isEmpty(), what + ": every display is gone");
    }

    // ------------------------------------------------------------------ every ability

    @ParameterizedTest(name = "{0}")
    @MethodSource("abilities")
    void wholeMatch(String id) {
        try (Scenario s = noGaugeGain(Scenario.builder(id)).start()) {
            s.ticks(60);
            s.assertNoProblems();

            AbilityLifecycleTest.prepareActive(s);
            s.clearHits();
            s.match.setGauge(s.enemy, 60);
            s.assertSucceeded(s.active(), id + " active");
            s.ticks(100);
            switch (id) {
                case "thief" -> assertTrue(s.match.participant(s.enemy.getUniqueId()).gauge() < 60,
                        "the pickpocket steals gauge");
                default -> assertTrue(s.damageTaken(s.enemy) > 0, id + " active hurts the aimed enemy");
            }
            assertEquals(0, s.damageTaken(s.owner), EPSILON, id + " active never hurts its owner: " + s.hits());

            AbilityLifecycleTest.prepareUltimate(s);
            s.healAll();
            s.clearHits();
            s.match.setGauge(s.enemy, 80);
            s.assertSucceeded(s.ultimate(), id + " ultimate");
            s.ticks(300);
            switch (id) {
                case "thief" -> assertTrue(s.match.participant(s.enemy.getUniqueId()).gauge() < 80,
                        "the heist steals gauge");
                default -> assertTrue(s.damageTaken(s.enemy) > 0, id + " ultimate hurts the aimed enemy");
            }
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
            AbilityLifecycleTest.prepareActive(s);
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
            s.ticks(14);
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
            AbilityLifecycleTest.prepareActive(s);
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
            s.assertNoProblems();
            s.finishClean();
        }
    }

    // ------------------------------------------------------------------ 사령술사 necromancer

    @Test
    void necromancerSkullsHomeInOnTheAimedEnemy() {
        try (Scenario s = Scenario.ffa("necromancer")) {
            s.place(s.enemy2, 12.5, 12.5, 180);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "망령탄");
            s.ticks(2);
            assertEquals(1, entities(s, ItemDisplay.class).size(), "the first skull is in the air");
            s.ticks(60);
            assertEquals(s.param("skull_count") * s.param("skull_damage"), s.damageBy(s.owner, s.enemy), EPSILON,
                    "every skull of the volley strikes the aimed enemy");
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON);
            assertNoDisplays(s, "skulls");
            s.finishClean();
        }
    }

    @Test
    void necromancerSkullsCurveTowardsAnEnemyOffTheAim() {
        try (Scenario s = Scenario.ffa("necromancer")) {
            s.place(s.enemy, 3.5, 8.5, 180);
            s.place(s.enemy2, -14.5, 14.5, 180);
            // Aim straight ahead, past the enemy's shoulder: the skulls must bend into it.
            s.aimAt(s.owner, new Location(s.world, 0.5, 65.5, 10.5));
            s.assertSucceeded(s.active(), "망령탄");
            s.ticks(60);
            assertEquals(s.param("skull_count") * s.param("skull_damage"), s.damageBy(s.owner, s.enemy), EPSILON);
            s.finishClean();
        }
    }

    @Test
    void necromancerSkullsShatterOnWalls() {
        try (Scenario s = Scenario.ffa("necromancer")) {
            s.world.wallAlongX(-5, 6, 2, 4, Material.STONE);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "망령탄");
            s.ticks(60);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "the wall shields the enemy");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            assertNoDisplays(s, "skulls");
            s.world.wallAlongX(-5, 6, 2, 4, Material.AIR);
            s.finishClean();
        }
    }

    @Test
    void necromancerSoulsComeOnlyFromEnemiesFallingNearby() {
        try (Scenario s = Scenario.ffa("necromancer")) {
            s.place(s.enemy2, 0.5, 30.5, 180);
            s.ticks(8);
            s.owner.setHealth(10);
            s.session.eliminate(s.enemy2, null);
            s.ticks(30);
            assertEquals(10, s.owner.getHealth(), EPSILON, "an enemy falling " + 30 + " blocks away is out of reach");
            assertTrue(entities(s, ItemDisplay.class).isEmpty(), "no soul");
            s.finishClean();
        }
    }

    @Test
    void necromancerReapsSoulsFromFallenEnemiesAndEmpowersTheNextVolley() {
        try (Scenario s = Scenario.ffa("necromancer")) {
            s.ticks(8); // the passive notices the enemies around it
            s.owner.setHealth(10);
            s.session.eliminate(s.enemy2, null);
            s.ticks(8);
            assertEquals(10 + s.param("soul_heal"), s.owner.getHealth(), EPSILON, "a soul heals");
            s.ticks(20);
            assertEquals(1, entities(s, ItemDisplay.class).size(), "the stored soul circles the owner as a skull");

            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "망령탄");
            s.ticks(60);
            assertNoDisplays(s, "the soul went into the volley");
            assertEquals((s.param("skull_count") + 1) * s.param("skull_damage"), s.damageBy(s.owner, s.enemy),
                    EPSILON, "one more skull for the soul");

            s.readyActive();
            s.clearHits();
            s.assertSucceeded(s.active(), "망령탄");
            s.ticks(60);
            assertEquals(s.param("skull_count") * s.param("skull_damage"), s.damageBy(s.owner, s.enemy), EPSILON,
                    "the soul was spent");
            s.finishClean();
        }
    }

    @Test
    void necromancerLegionRisesGrabsAndHuntsThenCrumbles() {
        try (Scenario s = Scenario.teams("necromancer")) {
            s.place(s.enemy, 0.5, 3.0, 180);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "언데드 군단");
            assertTrue(undead(s).isEmpty(), "the dead rise after the skulls dive");
            s.ticks(13);
            List<Mob> legion = undead(s);
            assertEquals((int) s.param("legion_count"), legion.size(), "the legion rose: " + legion);
            assertEquals(1, entities(s, Skeleton.class).size(), "one of them is a skeleton");
            assertEquals(s.param("rise_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "grabbed once");
            assertTrue(s.enemy.hasPotionEffect(PotionEffectType.SLOWNESS), "held in place");
            for (Mob mob : legion) {
                assertTrue(s.ctx().isAlly(s.owner, mob), "its own summon is an ally");
                assertTrue(s.ctx().isEnemy(s.enemy, mob), "and an enemy of the enemies");
                assertFalse(s.ctx().isEnemy(s.ally(), mob), "but not of the owner's team");
                ItemStack cap = mob.getEquipment().getHelmet();
                assertEquals(Material.LEATHER_HELMET, cap.getType(), "a cap keeps it from burning in daylight");
                double health = s.param(mob instanceof Skeleton ? "skeleton_health" : "zombie_health");
                assertEquals(health, mob.getHealth(), EPSILON);
            }
            s.ticks(10);
            for (Mob mob : legion) {
                LivingEntity target = mob.getTarget();
                assertNotNull(target, "the undead picks a target");
                assertTrue(s.enemies().contains(target), "an enemy, never the ally: " + target.getName());
            }
            Mob zombie = entities(s, Zombie.class).getFirst();
            assertEquals(s.param("zombie_damage"), zombie.getAttribute(Attribute.ATTACK_DAMAGE).getValue(), EPSILON);
            s.clearHits();
            s.melee(zombie, s.enemy, zombie.getAttribute(Attribute.ATTACK_DAMAGE).getValue());
            assertEquals(s.param("zombie_damage"), s.damageTaken(s.enemy), EPSILON, "a zombie hits for its damage");
            assertEquals(s.owner, s.ctx().resolveAttacker(zombie), "its hits are credited to the owner");

            s.ticks(s.paramTicks("legion_duration"));
            assertTrue(undead(s).isEmpty(), "they crumble after " + s.param("legion_duration") + " s");
            assertNoDisplays(s, "legion");
            s.finishClean();
        }
    }

    @Test
    void necromancerLegionLeavesStealthedEnemiesAlone() {
        try (Scenario s = Scenario.ffa("necromancer")) {
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "언데드 군단");
            s.ticks(13);
            s.melee(s.owner, s.enemy, 1); // the enemy becomes the legion's focus
            s.ticks(10);
            List<Mob> legion = undead(s);
            assertTrue(legion.stream().allMatch(mob -> s.enemy.equals(mob.getTarget())), "the focus is hunted");

            // Stealth hides a player from every enemy player, the necromancer included.
            s.owner.hidePlayer(s.ctx().plugin(), s.enemy);
            s.ticks(10);
            for (Mob mob : legion) {
                assertFalse(s.enemy.equals(mob.getTarget()), "a stealthed enemy is not chased");
            }
            s.owner.showPlayer(s.ctx().plugin(), s.enemy);
            s.finishClean();
        }
    }

    @Test
    void necromancerLegionNeverRisesBehindOrInsideAWall() {
        try (Scenario s = Scenario.ffa("necromancer")) {
            // A wall right in front of the owner: the dead rise on the owner's side of it.
            s.world.wallAlongX(-8, 8, 1, 3, Material.STONE);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "언데드 군단");
            s.ticks(13);
            assertFalse(undead(s).isEmpty());
            for (Mob mob : undead(s)) {
                Location at = mob.getLocation();
                assertTrue(at.getZ() < 1, "on the owner's side of the wall: " + at);
                assertTrue(s.world.getBlockAt(at).isPassable(), "not inside a block: " + at);
            }
            s.world.wallAlongX(-8, 8, 1, 3, Material.AIR);
            s.finishClean();
        }
    }

    @Test
    void necromancerLegionNeedsRoomAndKeepsTheGaugeOtherwise() {
        try (Scenario s = Scenario.ffa("necromancer")) {
            // Boxed in: walls on every side, so there is no spot an undead could stand on in sight.
            s.world.wallAlongX(-3, 3, -2, 3, Material.STONE);
            s.world.wallAlongX(-3, 3, 2, 3, Material.STONE);
            for (int z = -1; z <= 1; z++) {
                for (int y = 0; y < 3; y++) {
                    s.world.setBlock(-2, FlatWorld.FLOOR_Y + 1 + y, z, Material.STONE);
                    s.world.setBlock(2, FlatWorld.FLOOR_Y + 1 + y, z, Material.STONE);
                }
            }
            s.match.setGauge(s.owner, 100);
            assertFalse(s.match.useUltimate(s.owner).succeeded(), "no room to rise");
            assertEquals(100, s.participant(s.owner).gauge(), EPSILON, "the gauge is kept");
            s.ticks(20);
            assertTrue(undead(s).isEmpty());
            assertNoDisplays(s, "no rising");
            s.world.wallAlongX(-3, 3, -2, 3, Material.AIR);
            s.world.wallAlongX(-3, 3, 2, 3, Material.AIR);
            for (int z = -1; z <= 1; z++) {
                for (int y = 0; y < 3; y++) {
                    s.world.setBlock(-2, FlatWorld.FLOOR_Y + 1 + y, z, Material.AIR);
                    s.world.setBlock(2, FlatWorld.FLOOR_Y + 1 + y, z, Material.AIR);
                }
            }
            s.finishClean();
        }
    }

    @Test
    void necromancerSoulsEmpowerTheLegionToo() {
        try (Scenario s = Scenario.ffa("necromancer")) {
            s.ticks(8);
            s.session.eliminate(s.enemy2, s.owner); // a kill of its own counts once, however it is noticed
            s.ticks(8);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "언데드 군단");
            s.ticks(13);
            assertEquals((int) s.param("legion_count") + 1, undead(s).size(), "one more undead for the soul");
            s.finishClean();
        }
    }

    // ------------------------------------------------------------------ 음유시인 bard

    private static boolean hasPositiveSpeedModifier(Player player) {
        return player.getAttribute(Attribute.MOVEMENT_SPEED).getModifiers().stream()
                .anyMatch(modifier -> modifier.getAmount() > 0);
    }

    @Test
    void bardSongQuickensTheBardAndAlliesInReach() {
        try (Scenario s = Scenario.teams("bard")) {
            s.ticks(10);
            double base = 0.1;
            assertEquals(base * (1 + s.param("aura_speed") / 100), s.owner.getAttribute(Attribute.MOVEMENT_SPEED)
                    .getValue(), EPSILON, "the bard itself");
            assertTrue(hasPositiveSpeedModifier(s.ally()), "an ally within " + s.param("aura_radius") + " blocks");
            for (TestPlayer enemy : s.enemies()) {
                assertFalse(hasPositiveSpeedModifier(enemy), "never the enemies");
            }
            s.place(s.ally(), 20.5, 0.5, 0);
            s.ticks(10);
            assertFalse(hasPositiveSpeedModifier(s.ally()), "an ally out of reach loses the song");
            s.place(s.ally(), -1.5, 0.5, 0);
            s.ticks(10);
            assertTrue(hasPositiveSpeedModifier(s.ally()), "and gets it back when it returns");
            assertEquals(1, s.ally().getAttribute(Attribute.MOVEMENT_SPEED).getModifiers().size(), "never stacked");
            s.finishClean();
        }
    }

    @Test
    void bardHumsANoteOnlyWhileTravelling() {
        try (Scenario s = Scenario.ffa("bard")) {
            for (int i = 0; i < 90; i++) {
                s.ticks(1);
                assertTrue(entities(s, TextDisplay.class).isEmpty(), "standing still, the bard only plays particles");
            }
            boolean hummed = false;
            for (int step = 0; step < 6 && !hummed; step++) {
                s.place(s.owner, 0.5 + step * 1.5, -0.5 - step * 1.5, 0);
                for (int i = 0; i < 20 && !hummed; i++) {
                    s.ticks(1);
                    hummed = !entities(s, TextDisplay.class).isEmpty();
                }
            }
            assertTrue(hummed, "walking, it hums a floating note");
            s.ticks(40);
            assertNoDisplays(s, "the hummed note fades");
            s.finishClean();
        }
    }

    @Test
    void bardDiscordWeakensAndSlowsEveryEnemyAround() {
        try (Scenario s = Scenario.ffa("bard")) {
            s.place(s.enemy2, 0.5, 14.5, 180);
            s.assertSucceeded(s.active(), "불협화음");
            assertEquals(s.param("discord_damage"), s.damageBy(s.owner, s.enemy), EPSILON);
            assertNotNull(s.enemy.getPotionEffect(PotionEffectType.WEAKNESS), "weakened");
            assertEquals(1, s.enemy.getPotionEffect(PotionEffectType.SLOWNESS).getAmplifier(), "slowness II");
            assertEquals(s.paramTicks("discord_duration"), s.enemy.getPotionEffect(PotionEffectType.SLOWNESS)
                    .getDuration(), 1);
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON, "enemy2 is out of reach");
            assertFalse(s.enemy2.hasPotionEffect(PotionEffectType.WEAKNESS));
            assertFalse(s.owner.hasPotionEffect(PotionEffectType.WEAKNESS), "the bard is spared");
            s.ticks(2);
            assertTrue(entities(s, TextDisplay.class).size() > 1, "broken notes burst out of the lute");
            s.ticks(12);
            List<TextDisplay> marks = entities(s, TextDisplay.class);
            assertEquals(1, marks.size(), "then only the sour mark is left");
            assertTrue(marks.getFirst().getLocation().distance(s.enemy.getEyeLocation()) < 1.5,
                    "over the struck enemy's head");
            s.ticks(s.paramTicks("discord_duration") + 10);
            assertNoDisplays(s, "discord");

            s.readyActive();
            s.place(s.enemy, 0.5, 14.5, 180);
            ActivationResult alone = s.active();
            assertFalse(alone.succeeded(), "nobody to play to");
            assertEquals(0, s.ctx().cooldownRemaining(s.owner), EPSILON, "no cooldown is spent");
            s.finishClean();
        }
    }

    @Test
    void bardChorusHurtsEnemiesAndHealsAndHastensAlliesOnEveryBeat() {
        try (Scenario s = Scenario.teams("bard")) {
            s.ticks(30); // the note that greeted the ally at the start has faded
            s.place(s.ally(), 2.5, 0.5, 0);
            s.place(s.enemy2, 0.5, 20.5, 180);
            s.ally().setHealth(10);
            s.assertSucceeded(s.ultimate(), "대합창");
            s.ticks(2);
            assertEquals(6, entities(s, TextDisplay.class).size(), "note glyphs circle the bard");
            List<ItemDisplay> record = entities(s, ItemDisplay.class);
            assertEquals(1, record.size(), "a record spins over the bard's head");
            assertTrue(record.getFirst().getLocation().getY() > s.owner.getEyeLocation().getY());
            assertTrue(s.ally().hasPotionEffect(PotionEffectType.SPEED), "the ally is hastened");
            assertFalse(s.owner.hasPotionEffect(PotionEffectType.STRENGTH), "not a solo with an ally around");
            int beats = s.paramTicks("chorus_duration") / s.paramTicks("chorus_interval");
            s.ticks(s.paramTicks("chorus_duration"));
            assertEquals(beats * s.param("chorus_damage"), s.damageBy(s.owner, s.enemy), EPSILON,
                    "one wave per beat");
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON, "out of reach");
            assertEquals(10 + beats * s.param("chorus_heal"), s.ally().getHealth(), EPSILON, "healed on every beat");
            assertEquals(0, s.damageTaken(s.ally()), EPSILON);
            s.ticks(30);
            assertNoDisplays(s, "chorus");
            assertFalse(s.ally().hasPotionEffect(PotionEffectType.SPEED), "the haste ends with the song");
            s.finishClean();
        }
    }

    @Test
    void bardSingsASoloWhenNoAllyIsAround() {
        try (Scenario s = Scenario.ffa("bard")) {
            s.owner.setHealth(10);
            s.assertSucceeded(s.ultimate(), "대합창");
            s.ticks(3);
            assertTrue(s.owner.hasPotionEffect(PotionEffectType.STRENGTH), "a solo empowers the bard");
            assertTrue(s.owner.hasPotionEffect(PotionEffectType.RESISTANCE));
            assertTrue(s.owner.getHealth() > 10, "and heals it");
            s.ticks(s.paramTicks("chorus_duration") + 20);
            assertFalse(s.owner.hasPotionEffect(PotionEffectType.STRENGTH), "until the song ends");
            s.finishClean();
        }
    }

    // ------------------------------------------------------------------ 도둑 thief

    private static double gauge(Scenario s, Player player) {
        return s.match.participant(player.getUniqueId()).gauge();
    }

    @Test
    void thiefStickyFingersStealGaugeWithMeleeHits() {
        try (Scenario s = noGaugeGain(Scenario.builder("thief")).start()) {
            s.match.setGauge(s.enemy, 50);
            s.melee(s.owner, s.enemy, 2);
            double steal = s.param("pick_steal");
            assertEquals(50 - steal, gauge(s, s.enemy), EPSILON);
            assertEquals(steal, gauge(s, s.owner), EPSILON, "the stolen gauge is the thief's");
            s.melee(s.owner, s.enemy, 2);
            assertEquals(50 - steal, gauge(s, s.enemy), EPSILON, "the same enemy only every "
                    + s.param("pick_interval") + " s");
            s.ticks(s.paramTicks("pick_interval"));
            s.melee(s.owner, s.enemy, 2);
            assertEquals(50 - 2 * steal, gauge(s, s.enemy), EPSILON);
            s.melee(s.owner, s.enemy2, 2);
            assertEquals(0, gauge(s, s.enemy2), EPSILON, "nothing to steal from an empty gauge");
            assertEquals(2 * steal, gauge(s, s.owner), EPSILON);
            s.ticks(40);
            assertNoDisplays(s, "coins");
            s.finishClean();
        }
    }

    @Test
    void thiefPickpocketSlipsBehindStrikesStealsAndSilences() {
        try (Scenario s = noGaugeGain(Scenario.builder("thief")).start()) {
            s.place(s.owner, 0.5, -5.5, 0);
            s.aimAt(s.owner, s.enemy);
            s.match.setGauge(s.enemy, 40);
            s.assertSucceeded(s.active(), "소매치기");
            Location at = s.owner.getLocation();
            assertTrue(at.getZ() > s.enemy.getLocation().getZ(), "behind the enemy (it faces the thief's start): " + at);
            assertTrue(at.distance(s.enemy.getLocation()) < 3, "right at its back");
            assertEquals(s.param("dash_damage"), s.damageBy(s.owner, s.enemy), EPSILON);
            assertEquals(40 - s.param("dash_steal"), gauge(s, s.enemy), EPSILON);
            assertEquals(s.param("dash_steal"), gauge(s, s.owner), EPSILON);
            assertTrue(s.ctx().isSilenced(s.enemy), "its pockets are empty for a moment");
            s.ticks(s.paramTicks("dash_silence") + 1);
            assertFalse(s.ctx().isSilenced(s.enemy));
            s.ticks(40);
            assertNoDisplays(s, "pickpocket");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);

            s.readyActive();
            s.place(s.owner, 0.5, -5.5, 180);
            s.aimAt(s.owner, new Location(s.world, 0.5, 66, -30));
            assertFalse(s.active().succeeded(), "nobody to rob");
            assertEquals(0, s.ctx().cooldownRemaining(s.owner), EPSILON);
            s.finishClean();
        }
    }

    @Test
    void thiefPickpocketFindsAnotherSideWhenTheEnemysBackIsToAWall() {
        try (Scenario s = Scenario.ffa("thief")) {
            s.place(s.enemy2, 12.5, 12.5, 180);
            s.world.wallAlongX(-4, 5, 5, 3, Material.STONE);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "소매치기");
            Location at = s.owner.getLocation();
            assertTrue(at.getZ() < 5 - 0.29, "never inside the wall: " + at);
            assertTrue(s.world.getBlockAt(at).isPassable() && s.world.getBlockAt(at.clone().add(0, 1, 0)).isPassable(),
                    "standing in free space: " + at);
            assertTrue(at.distance(s.enemy.getLocation()) < 3, "still right beside the enemy: " + at);
            assertEquals(s.param("dash_damage"), s.damageBy(s.owner, s.enemy), EPSILON);
            s.ticks(40);
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "the slip never hurts the thief");
            s.world.wallAlongX(-4, 5, 5, 3, Material.AIR);
            s.finishClean();
        }
    }

    @Test
    void thiefHeistRobsEveryoneAroundAndVanishes() {
        try (Scenario s = noGaugeGain(Scenario.builder("thief")).start()) {
            s.match.setGauge(s.enemy, 80);
            s.match.setGauge(s.enemy2, 30);
            s.assertSucceeded(s.ultimate(), "대도");
            double steal = s.param("heist_steal");
            assertEquals(80 - steal, gauge(s, s.enemy), EPSILON);
            assertEquals(0, gauge(s, s.enemy2), EPSILON, "only what it had");
            s.ticks(2);
            assertTrue(entities(s, ItemDisplay.class).size() >= 6, "loot and a ring of gold fly to the thief");
            assertTrue(s.enemy.canSee(s.owner), "still there while the loot flies over");
            s.ticks(12);
            assertEquals((steal + 30) * s.param("heist_share") / 100, gauge(s, s.owner), EPSILON,
                    "the share lands with the loot");
            assertFalse(s.enemy.canSee(s.owner), "then the thief vanishes");
            assertFalse(s.enemy2.canSee(s.owner));
            assertTrue(s.owner.hasPotionEffect(PotionEffectType.INVISIBILITY));
            s.melee(s.enemy, s.owner, 2);
            assertFalse(s.enemy.canSee(s.owner), "being hit does not break the heist's stealth");
            s.ticks(s.paramTicks("heist_invis"));
            assertTrue(s.enemy.canSee(s.owner), "visible again after " + s.param("heist_invis") + " s");
            assertFalse(s.owner.hasPotionEffect(PotionEffectType.INVISIBILITY));
            assertNoDisplays(s, "heist");
            s.finishClean();
        }
    }

    @Test
    void thiefAmbushFromStealthStealsMoreAndReveals() {
        try (Scenario s = noGaugeGain(Scenario.builder("thief")).start()) {
            s.assertSucceeded(s.ultimate(), "대도");
            s.ticks(20);
            s.match.setGauge(s.enemy, 60);
            s.match.setGauge(s.owner, 0);
            s.melee(s.owner, s.enemy, 2);
            double ambush = s.param("pick_steal") * s.param("ambush_mult");
            assertEquals(60 - ambush, gauge(s, s.enemy), EPSILON, "an ambush steals " + s.param("ambush_mult") + "x");
            assertEquals(ambush, gauge(s, s.owner), EPSILON);
            assertTrue(s.enemy.canSee(s.owner), "attacking reveals the thief");
            assertFalse(s.owner.hasPotionEffect(PotionEffectType.INVISIBILITY));
            s.finishClean();
        }
    }

    @Test
    void thiefHeistOnlyRobsEnemiesAndHidesOnlyFromThem() {
        try (Scenario s = noGaugeGain(Scenario.builder("thief").teams()).start()) {
            s.crossfireAlly();
            s.match.setGauge(s.ally(), 70);
            s.match.setGauge(s.enemy, 70);
            s.assertSucceeded(s.ultimate(), "대도");
            assertEquals(70, gauge(s, s.ally()), EPSILON, "the ally keeps its gauge");
            assertEquals(70 - s.param("heist_steal"), gauge(s, s.enemy), EPSILON);
            s.ticks(14);
            assertTrue(s.ally().canSee(s.owner), "allies still see the thief");
            assertFalse(s.enemy.canSee(s.owner), "enemies do not");
            assertEquals(0, s.damageTaken(s.ally()), EPSILON);
            s.finishClean();
        }
    }

    @Test
    void thiefStealsOnlyWhatPlayersHaveAndNeverFromAllies() {
        try (Scenario s = noGaugeGain(Scenario.builder("thief").teams()).start()) {
            s.match.setGauge(s.ally(), 60);
            s.melee(s.owner, s.ally(), 2); // friendly fire is cancelled before any hook
            assertEquals(60, gauge(s, s.ally()), EPSILON);
            assertEquals(0, gauge(s, s.owner), EPSILON);
            s.finishClean();
        }
    }

    @Test
    void thiefHeistNeedsSomeoneToRob() {
        try (Scenario s = Scenario.ffa("thief")) {
            s.place(s.enemy, 0.5, 20.5, 180);
            s.place(s.enemy2, 20.5, 0.5, 180);
            s.match.setGauge(s.owner, 100);
            assertFalse(s.match.useUltimate(s.owner).succeeded(), "nobody within " + s.param("heist_radius"));
            assertEquals(100, gauge(s, s.owner), EPSILON, "the gauge is kept");
            assertTrue(s.enemy.canSee(s.owner));
            s.finishClean();
        }
    }
}
