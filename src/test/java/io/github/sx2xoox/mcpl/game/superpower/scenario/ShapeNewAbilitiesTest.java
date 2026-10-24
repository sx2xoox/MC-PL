package io.github.sx2xoox.mcpl.game.superpower.scenario;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import io.github.sx2xoox.mcpl.game.superpower.abilities.CirceAbility;
import io.github.sx2xoox.mcpl.game.superpower.abilities.KoscheiAbility;
import io.github.sx2xoox.mcpl.game.superpower.abilities.MagicalGirlAbility;
import io.github.sx2xoox.mcpl.game.superpower.abilities.RokurokubiAbility;
import io.github.sx2xoox.mcpl.game.superpower.abilities.TenguAbility;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Fx;
import io.github.sx2xoox.mcpl.game.superpower.ability.Ability;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
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
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Pig;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Wolf;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** 몸 · 변신 (new): 로쿠로쿠비, 텐구, 키르케, 마법소녀, 코셰이. */
class ShapeNewAbilitiesTest {

    private static final double EPSILON = 1.0E-6;
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z0-9_]+)}");

    static Stream<String> abilities() {
        return Stream.of("rokurokubi", "tengu", "circe", "magicalgirl", "koschei");
    }

    /** The ultimates the gambler may borrow. */
    static Stream<String> standalone() {
        return Stream.of("circe");
    }

    /**
     * {@link AbilityLifecycleTest} reads the ability registry in its static initializer, which starts a mock server of
     * its own; initialize it before any scenario runs (see RogueNewAbilitiesTest).
     */
    @BeforeAll
    static void initializeSharedSweeps() {
        assertTrue(AbilityLifecycleTest.abilities().findAny().isPresent());
    }

    // ================================================================== helpers

    private static <T extends Entity> List<T> entities(Scenario s, Class<T> type) {
        return s.world.getEntities().stream().filter(type::isInstance).map(type::cast).toList();
    }

    private static void assertNoDisplays(Scenario s, String what) {
        assertTrue(entities(s, Display.class).isEmpty(), what + ": every display is gone: "
                + entities(s, Display.class).stream().map(d -> d.getType() + "@" + d.getLocation().toVector()).toList());
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

    private static void assertUnrotated(Entity display, String what) {
        assertEquals(0, display.getLocation().getYaw(), 1.0E-3, what + ": display yaw");
        assertEquals(0, display.getLocation().getPitch(), 1.0E-3, what + ": display pitch");
    }

    /** The left rotation a display was given (see WeirdNewAbilitiesTest#rotationOf). */
    private static Quaternionf rotationOf(Display display) {
        Transformation t = display.getTransformation();
        Quaternionf left = t.getLeftRotation();
        return t.getRightRotation().equals(new Quaternionf(), 1.0E-5f) ? left : left.conjugate();
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

    private static double scale(LivingEntity entity) {
        return entity.getAttribute(Attribute.SCALE).getValue();
    }

    private static double speed(LivingEntity entity) {
        return entity.getAttribute(Attribute.MOVEMENT_SPEED).getValue();
    }

    private static double baseSpeed(LivingEntity entity) {
        return entity.getAttribute(Attribute.MOVEMENT_SPEED).getBaseValue();
    }

    private static List<TextDisplay> texts(Scenario s, String containing) {
        return entities(s, TextDisplay.class).stream()
                .filter(text -> Scenario.plain(text.text()).contains(containing))
                .toList();
    }

    private static List<BlockDisplay> blocks(Scenario s, Material type) {
        return entities(s, BlockDisplay.class).stream().filter(d -> d.getBlock().getMaterial() == type).toList();
    }

    /** A solid ceiling over (x1..x2, z1..z2) at {@code y} (absolute). */
    private static void ceiling(Scenario s, int x1, int x2, int z1, int z2, int y, Material type) {
        for (int x = x1; x <= x2; x++) {
            for (int z = z1; z <= z2; z++) {
                s.world.getBlockAt(x, y, z).setType(type);
            }
        }
    }

    /** Where +Z ends up under {@code rotation}. */
    private static Vector3f forward(Quaternionf rotation) {
        return rotation.transform(new Vector3f(0, 0, 1));
    }

    private static Vector3f lookOf(Player player) {
        Vector look = player.getLocation().getDirection();
        return new Vector3f((float) look.getX(), (float) look.getY(), (float) look.getZ());
    }

    // ================================================================== every ability

    @Test
    void descriptionsOnlyReferenceDeclaredParams() {
        for (AbilityInfo info : List.of(RokurokubiAbility.INFO, TenguAbility.INFO, CirceAbility.INFO,
                MagicalGirlAbility.INFO, KoscheiAbility.INFO)) {
            for (AbilityInfo.Skill skill : List.of(info.passive(), info.active(), info.ultimate())) {
                assertFalse(skill.name().equals("TODO"), info.id() + " is implemented");
                Matcher matcher = PLACEHOLDER.matcher(skill.description());
                while (matcher.find()) {
                    assertTrue(info.params().containsKey(matcher.group(1)),
                            info.id() + " " + skill.name() + " uses an undeclared param " + matcher.group());
                }
            }
        }
        assertFalse(TenguAbility.INFO.ultimateStandalone(), "the giant's stomp and bonus are the tengu's hooks");
        assertTrue(CirceAbility.INFO.ultimateStandalone(), "the banquet runs on its own");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("abilities")
    void ownerDiesDuringActive(String id) {
        try (Scenario s = Scenario.ffa(id)) {
            s.ticks(40);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), id + " active");
            s.ticks(8);
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
            assertTrue(s.owner.canSee(enemy) && s.enemy2.canSee(s.enemy) && s.enemy.canSee(s.enemy2),
                    id + ": everyone sees everyone again");
            assertTrue(enemy.canSee(s.owner), id + ": " + enemy.getName() + " sees the owner again");
        }
        assertModifierFree(s.owner, id);
        s.assertNoProblems();
        s.finishClean();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("abilities")
    void teamModeLeavesTheAllyAlone(String id) {
        try (Scenario s = Scenario.teams(id)) {
            s.ticks(30);
            s.crossfireAlly();
            s.aimAt(s.owner, s.enemy);
            Location start = s.ally().getLocation();
            s.clearHits();
            s.assertSucceeded(s.active(), id + " active");
            AbilityLifecycleTest.watchAlly(s, start, 80);
            s.layout();
            s.crossfireAlly();
            s.aimAt(s.owner, s.enemy);
            start = s.ally().getLocation();
            s.assertSucceeded(s.ultimate(), id + " ultimate");
            AbilityLifecycleTest.watchAlly(s, start, 200);
            assertTrue(s.ally().canSee(s.owner), "the ally always sees the owner");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("standalone")
    void borrowedUltimateWorksOnAGambler(String id) {
        try (Scenario s = gambler(false)) {
            assertTrue(s.ctx().registry().get(id).info().ultimateStandalone(), id + " is borrowable");
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(borrow(s, id), "borrowed " + id);
            boolean worked = false;
            for (int i = 0; i < 300; i++) {
                s.ticks(1);
                worked |= s.damageBy(s.owner, s.enemy) + s.damageBy(s.owner, s.enemy2) > 0;
            }
            assertTrue(worked, "the borrowed " + id + " ultimate does its thing");
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "the borrowed " + id + " never hurts the gambler");
            s.ticks(60);
            assertModifierFree(s.owner, id);
            for (TestPlayer target : s.enemies()) {
                assertModifierFree(target, id);
                assertFalse(s.ctx().isSilenced(target), id + ": the silence wore off");
                assertTrue(s.owner.canSee(target), id + ": " + target.getName() + " shows again");
            }
            assertNoDisplays(s, id);
            assertTrue(entities(s, Mob.class).isEmpty(), "no animal is left behind");
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
            assertTrue(s.owner.canSee(s.ally()), "the ally is never turned into an animal");
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
            s.ticks(id.equals("circe") ? 25 : 12);
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
                assertTrue(s.enemy2.canSee(s.enemy) && s.enemy.canSee(s.enemy2), id + ": nobody stays hidden");
            }
            s.assertNoProblems();
            s.finishClean();
        }
    }

    // ================================================================== 텐구 tengu

    private static TenguAbility tengu(Scenario s) {
        return (TenguAbility) s.ability();
    }

    private static List<BlockDisplay> noses(Scenario s) {
        return blocks(s, Material.RED_CONCRETE).stream()
                .filter(d -> d.getTransformation().getScale().z > d.getTransformation().getScale().x * 0.99f)
                .filter(d -> d.getLocation().distance(s.owner.getLocation()) < 0.5)
                .toList();
    }

    @Test
    void tenguNoseGrowsWithCleanHitsHitsHarderAndBreaksWhenHit() {
        try (Scenario s = Scenario.ffa("tengu")) {
            s.ticks(10);
            long interval = s.paramTicks("nose_interval");
            for (int i = 1; i <= 3; i++) {
                s.melee(s.owner, s.enemy, 1);
                s.ticks((int) interval + 1);
                assertEquals(i, tengu(s).noseStage(), "a clean hit grows the nose");
            }
            // Two hits within the interval only grow it once.
            s.melee(s.owner, s.enemy, 1);
            s.melee(s.owner, s.enemy, 1);
            assertEquals(4, tengu(s).noseStage());
            s.ticks((int) interval + 1);
            s.melee(s.owner, s.enemy, 1);
            assertEquals(4, tengu(s).noseStage(), "capped at the longest nose");

            s.clearHits();
            s.melee(s.owner, s.enemy, 5);
            double bonus = 4 * s.param("nose_bonus") / 100;
            assertEquals(5 * (1 + bonus), s.damageBy(s.owner, s.enemy), 1.0E-6, "a long nose hits harder");

            // An enemy's blow breaks it, and a full nose staggers.
            double before = speed(s.owner);
            s.melee(s.enemy, s.owner, 1);
            assertEquals(0, tengu(s).noseStage(), "broken back to the start");
            assertTrue(speed(s.owner) < before * (1 - s.param("snap_slow") / 100) + 1.0E-6, "staggered");
            s.ticks(s.paramTicks("snap_stagger") + 2);
            assertEquals(before, speed(s.owner), 1.0E-6, "the stagger passes");
            s.clearHits();
            s.melee(s.owner, s.enemy, 5);
            assertEquals(5, s.damageBy(s.owner, s.enemy), 1.0E-6, "no bonus with a broken nose");
            assertEquals(1, tengu(s).noseStage(), "and it starts growing again");
            // A fall is not an enemy.
            s.owner.setNoDamageTicks(0);
            s.owner.damage(2, DamageSource.builder(DamageType.FALL).build());
            assertEquals(1, tengu(s).noseStage(), "only enemies break the nose");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void tenguNoseSticksOutOfTheFaceWhereverItLooksAndHidesFromTheOwner() {
        try (Scenario s = Scenario.ffa("tengu")) {
            s.ticks(5);
            s.place(s.owner, 0.5, FlatWorld.SURFACE, 0.5, 90, 30);
            s.ticks(3);
            List<BlockDisplay> found = noses(s);
            assertEquals(1, found.size(), "one nose: " + entities(s, BlockDisplay.class).size());
            BlockDisplay nose = found.getFirst();
            assertUnrotated(nose, "nose");
            Vector3f along = forward(rotationOf(nose));
            Vector3f look = lookOf(s.owner);
            assertTrue(along.dot(look) > 0.99, "the nose points where the tengu looks: " + along + " vs " + look);
            Transformation t = nose.getTransformation();
            Vector3f middle = new Vector3f(t.getTranslation()).add(rotationOf(nose).transform(new Vector3f(
                    t.getScale()).mul(0.5f)));
            Vector3f fromEyes = new Vector3f(middle).sub(0, (float) s.owner.getEyeHeight(), 0);
            assertTrue(fromEyes.dot(look) > 0.15, "in front of the face: " + middle);
            assertFalse(s.owner.canSee(nose), "the owner's own nose would block its view");
            assertTrue(s.enemy.canSee(nose), "everyone else sees it");
            // It really grows.
            float before = nose.getTransformation().getScale().z;
            s.melee(s.owner, s.enemy, 1);
            s.ticks(2);
            assertTrue(nose.getTransformation().getScale().z > before + 0.05f, "a longer nose");
            // Whoever cannot see the tengu cannot see its nose.
            s.enemy2.hidePlayer(s.ctx().plugin(), s.owner);
            s.ticks(12);
            assertFalse(s.enemy2.canSee(nose), "the nose never gives a hidden tengu away");
            s.enemy2.showPlayer(s.ctx().plugin(), s.owner);
            s.ticks(12);
            assertTrue(s.enemy2.canSee(nose));
            s.finishClean();
        }
    }

    @Test
    void tenguRedFanShrinksTheEnemiesInFrontWithoutPushingThem() {
        try (Scenario s = Scenario.ffa("tengu")) {
            s.ticks(10);
            s.place(s.enemy2, 0.5, -4.5, 0); // behind the tengu
            s.aimAt(s.owner, s.enemy);
            Location before = s.enemy.getLocation();
            double attack = s.enemy.getAttribute(Attribute.ATTACK_DAMAGE).getValue();
            double reach = s.enemy.getAttribute(Attribute.ENTITY_INTERACTION_RANGE).getValue();
            s.clearHits();
            s.assertSucceeded(s.active(), "요술 부채");
            s.ticks(6);
            assertEquals(s.param("fan_damage"), s.damageBy(s.owner, s.enemy), 1.0E-6, "the fan stings");
            assertEquals(1, tengu(s).noseStage(), "a landed fan grows the nose");
            assertEquals(s.param("shrink_scale"), scale(s.enemy), 1.0E-6, "shrunk");
            assertEquals(attack * (1 - s.param("shrink_melee") / 100), s.enemy.getAttribute(Attribute.ATTACK_DAMAGE)
                    .getValue(), 1.0E-6, "weaker blows");
            assertEquals(reach - s.param("shrink_reach"), s.enemy.getAttribute(Attribute.ENTITY_INTERACTION_RANGE)
                    .getValue(), 1.0E-6, "shorter reach");
            assertEquals(1, scale(s.enemy2), 1.0E-6, "the enemy behind is not fanned");
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON);
            assertTrue(s.enemy.getLocation().distance(before) < 0.1, "the fan does not push");
            assertTrue(tengu(s).isShrunk(s.enemy));

            // More knockback for the little body.
            EntityKnockbackEvent knock = new EntityKnockbackEvent(s.enemy, EntityKnockbackEvent.Cause.ENTITY_ATTACK,
                    new Vector(0.4, 0.3, 0));
            s.h.server.getPluginManager().callEvent(knock);
            assertEquals(0.4 * (1 + s.param("shrink_knockback") / 100), knock.getKnockback().getX(), 1.0E-6);
            assertEquals(0.3, knock.getKnockback().getY(), 1.0E-6);

            for (Display display : entities(s, Display.class)) {
                assertUnrotated(display, "fan");
            }
            s.ticks(s.paramTicks("shrink_duration") + 4);
            assertEquals(1, scale(s.enemy), 1.0E-6, "back to size");
            assertModifierFree(s.enemy, "shrink over");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void tenguWhiteFanGrowsItselfAndTheAllyInFrontUntilAColumnIsTooLow() {
        try (Scenario s = Scenario.teams("tengu")) {
            s.ticks(10);
            s.place(s.ally(), 0.5, 2.5, 180);
            s.owner.setSneaking(true);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "흰 부채");
            s.ticks(6);
            assertEquals(s.param("grow_scale"), scale(s.owner), 1.0E-6, "the tengu grows");
            assertEquals(s.param("grow_scale"), scale(s.ally()), 1.0E-6, "so does the ally in front");
            assertEquals(1, scale(s.enemy), 1.0E-6, "never an enemy");
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "the white side does not hurt");
            // The ally walks under a low roof: it shrinks back at once instead of suffocating.
            ceiling(s, -2, 3, 6, 8, FlatWorld.FLOOR_Y + 3, Material.STONE);
            s.place(s.ally(), 0.5, 7.5, 180);
            s.ticks(4);
            assertEquals(1, scale(s.ally()), 1.0E-6, "no giant under a roof");
            assertEquals(s.param("grow_scale"), scale(s.owner), 1.0E-6, "the tengu in the open stays big");
            s.ticks(s.paramTicks("grow_duration"));
            assertEquals(1, scale(s.owner), 1.0E-6);
            ceiling(s, -2, 3, 6, 8, FlatWorld.FLOOR_Y + 3, Material.AIR);
            s.owner.setSneaking(false);
            s.finishClean();
        }
    }

    @Test
    void tenguLandOfLittlePeopleShrinksTheConeGrowsTheTenguAndStomps() {
        try (Scenario s = Scenario.ffa("tengu")) {
            s.ticks(10);
            s.place(s.enemy2, 2.5, 9.0, 180);
            s.aimAt(s.owner, s.enemy);
            s.clearHits();
            s.assertSucceeded(s.ultimate(), "소인국");
            s.ticks(6);
            for (TestPlayer enemy : s.enemies()) {
                assertEquals(s.param("tiny_scale"), scale(enemy), 1.0E-6, enemy.getName() + " is tiny");
            }
            assertEquals(s.param("giant_scale"), scale(s.owner), 1.0E-6, "a giant tengu");
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "the big fan itself does not hurt");

            // The giant hits little people harder.
            s.clearHits();
            s.melee(s.owner, s.enemy, 4);
            assertEquals(4 * (1 + s.param("giant_melee") / 100), s.damageBy(s.owner, s.enemy), 1.0E-6);

            // Jump and land next to the little enemy: a stomp.
            s.place(s.enemy, 0.5, 2.0, 180);
            s.place(s.owner, 0.5, FlatWorld.SURFACE + 2.5, 1.0, 0, 0);
            s.clearHits();
            s.tickUntilGrounded(s.owner, 60);
            s.ticks(2);
            assertTrue(s.damageBy(s.owner, s.enemy) >= s.param("stomp_damage") - EPSILON, "stomped: " + s.hits());
            double once = s.damageBy(s.owner, s.enemy);
            s.place(s.owner, 0.5, FlatWorld.SURFACE + 2.5, 1.0, 0, 0);
            s.tickUntilGrounded(s.owner, 60);
            s.ticks(2);
            assertEquals(once, s.damageBy(s.owner, s.enemy), 1.0E-6, "the same enemy once per cooldown");
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "the giant never hurts itself");
            s.ticks(s.paramTicks("ult_duration"));
            assertEquals(1, scale(s.owner), 1.0E-6, "the giant shrinks back");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void tenguTinyEnemyUnderALowRoofStaysSmallUntilThereIsRoom() {
        try (Scenario s = Scenario.ffa("tengu")) {
            s.ticks(10);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "소인국");
            s.ticks(6);
            assertEquals(s.param("tiny_scale"), scale(s.enemy), 1.0E-6);
            // The little enemy crawls under a one block high roof.
            ceiling(s, -1, 1, 10, 12, FlatWorld.FLOOR_Y + 2, Material.STONE);
            s.place(s.enemy, 0.5, 11.5, 180);
            s.ticks(s.paramTicks("ult_duration") + 4);
            assertEquals(s.param("tiny_scale"), scale(s.enemy), 1.0E-6, "no room to grow back: still tiny");
            s.place(s.enemy, 0.5, 4.5, 180);
            s.ticks(4);
            assertEquals(1, scale(s.enemy), 1.0E-6, "out in the open it grows back");
            ceiling(s, -1, 1, 10, 12, FlatWorld.FLOOR_Y + 2, Material.AIR);
            s.finishClean();
        }
    }

    // ================================================================== 키르케 circe

    private static CirceAbility circe(Scenario s) {
        return (CirceAbility) s.ability();
    }

    /** Throws the cup at the aimed enemy and waits until it is a pig. */
    private static void pig(Scenario s, LivingEntity target) {
        s.aimAt(s.owner, target);
        s.assertSucceeded(s.active(), "돼지가 되어라");
        s.h.tickUntil(() -> circe(s).isMorphed(target), 30, "the cup to land");
    }

    @Test
    @SuppressWarnings("removal") // the only way to hand the pig itself a blow (the mock pig cannot take damage)
    void circeCupTurnsTheFirstEnemyIntoAPigThatCannotFight() {
        try (Scenario s = Scenario.ffa("circe")) {
            s.ticks(10);
            s.clearHits();
            pig(s, s.enemy);
            assertFalse(circe(s).isMorphed(s.enemy2), "only the first enemy");
            assertFalse(s.owner.canSee(s.enemy), "the victim's body is hidden");
            assertFalse(s.enemy2.canSee(s.enemy), "from everyone");
            Entity model = circe(s).modelOf(s.enemy);
            assertNotNull(model);
            assertTrue(model instanceof Pig, "a pig takes its place");
            s.ticks(2);
            assertTrue(model.getLocation().distance(s.enemy.getLocation()) < 0.1, "and follows it");
            assertTrue(s.ctx().isSilenced(s.enemy), "no skills for a pig");
            assertEquals(baseSpeed(s.enemy) * (1 - s.param("pig_slow") / 100), speed(s.enemy), 1.0E-6, "a slow pig");
            // A pig cannot strike.
            s.clearHits();
            s.melee(s.enemy, s.owner, 5);
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "a pig's blow does nothing");
            // But it can be hurt, and stays a pig.
            s.melee(s.enemy2, s.enemy, 3);
            assertEquals(3, s.damageTaken(s.enemy), EPSILON);
            assertTrue(circe(s).isMorphed(s.enemy), "being hit does not break the spell");
            // Blows on the pig land on the hidden victim.
            s.clearHits();
            EntityDamageByEntityEvent onPig = new EntityDamageByEntityEvent(s.enemy2, model, DamageCause.ENTITY_ATTACK,
                    DamageSource.builder(DamageType.PLAYER_ATTACK).withCausingEntity(s.enemy2).withDirectEntity(s.enemy2)
                            .build(), 4);
            s.enemy.setNoDamageTicks(0);
            s.h.server.getPluginManager().callEvent(onPig);
            assertTrue(onPig.isCancelled(), "the pig itself is never hurt");
            assertEquals(4, s.damageBy(s.enemy2, s.enemy), EPSILON, "the blow reaches the victim");

            s.clearHits();
            s.ticks(s.paramTicks("pig_duration") + 2);
            assertFalse(circe(s).isMorphed(s.enemy), "the spell wears off");
            assertTrue(s.owner.canSee(s.enemy) && s.enemy2.canSee(s.enemy), "seen again");
            assertFalse(model.isValid(), "the pig is gone");
            assertEquals(s.param("unpig_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "a sore return");
            assertModifierFree(s.enemy, "unpigged");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void circeMissedCupLeavesAWinePuddleThatSlows() {
        try (Scenario s = Scenario.ffa("circe")) {
            s.ticks(10);
            s.place(s.enemy, 10.5, 10.5, 180);
            s.place(s.enemy2, -10.5, 10.5, 180);
            s.aimAtFloor(s.owner, 0.5, 5.5);
            s.assertSucceeded(s.active(), "돼지가 되어라");
            s.ticks(12);
            List<BlockDisplay> puddle = blocks(s, Material.RED_STAINED_GLASS);
            assertFalse(puddle.isEmpty(), "a puddle of wine");
            Location at = puddle.getFirst().getLocation();
            assertEquals(FlatWorld.SURFACE, at.getY(), 1.0E-6, "lying on the floor");
            assertEquals(5.5, at.getZ(), 1.5, "where the cup fell");
            for (BlockDisplay layer : puddle) {
                assertUnrotated(layer, "puddle");
            }
            s.place(s.enemy, at.getX(), at.getZ(), 180);
            s.ticks(6);
            assertTrue(speed(s.enemy) < baseSpeed(s.enemy) - 1.0E-6, "wading through wine is slow");
            assertFalse(circe(s).isMorphed(s.enemy), "a puddle is no spell");
            s.ticks(s.paramTicks("puddle_duration") + 20);
            assertEquals(baseSpeed(s.enemy), speed(s.enemy), 1.0E-6, "dried up");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void circeCupNeverLandsOnAHiddenEnemy() {
        try (Scenario s = Scenario.ffa("circe")) {
            s.ticks(10);
            s.owner.hidePlayer(s.ctx().plugin(), s.enemy);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "돼지가 되어라");
            s.ticks(30);
            assertFalse(circe(s).isMorphed(s.enemy), "a hidden enemy is not given away by a pig");
            s.owner.showPlayer(s.ctx().plugin(), s.enemy);
            s.finishClean();
        }
    }

    @Test
    void circeBeastsWillNotGoForTheirMistressAndHerBlowsHitThemTwice() {
        try (Scenario s = Scenario.builder("circe").enemyAbility("summoner").start()) {
            s.ticks(20);
            s.assertSucceeded(s.match.useActive(s.enemy), "늑대 무리");
            s.ticks(5);
            List<Wolf> wolves = entities(s, Wolf.class);
            assertFalse(wolves.isEmpty());
            Wolf wolf = wolves.getFirst();
            wolf.teleport(new Location(s.world, 2.5, FlatWorld.SURFACE, 2.5));
            EntityTargetEvent target = new EntityTargetEvent(wolf, s.owner, EntityTargetEvent.TargetReason.CLOSEST_PLAYER);
            s.h.server.getPluginManager().callEvent(target);
            assertTrue(target.isCancelled(), "a beast near its mistress will not go for her");
            // Even a beast its master sets on her cannot bite her.
            s.clearHits();
            s.melee(wolf, s.owner, 4);
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "a tamed beast's bite does nothing");
            s.melee(wolf, s.enemy2, 4);
            assertTrue(s.damageTaken(s.enemy2) > 0, "it still bites others");
            // Far away it may.
            wolf.teleport(new Location(s.world, 15.5, FlatWorld.SURFACE, 15.5));
            EntityTargetEvent far = new EntityTargetEvent(wolf, s.owner, EntityTargetEvent.TargetReason.CLOSEST_PLAYER);
            s.h.server.getPluginManager().callEvent(far);
            assertFalse(far.isCancelled(), "beyond the radius the beast is free");
            s.clearHits();
            s.melee(wolf, s.owner, 4);
            assertTrue(s.damageTaken(s.owner) > 0, "and bites");
            // Her blows hit beasts twice as hard, players as usual.
            s.clearHits();
            wolf.teleport(new Location(s.world, 1.5, FlatWorld.SURFACE, 1.5));
            s.melee(s.owner, wolf, 3);
            assertEquals(3 * s.param("beast_multiplier"), s.damageBy(s.owner, wolf), 1.0E-6);
            s.melee(s.owner, s.enemy2, 3);
            assertEquals(3, s.damageBy(s.owner, s.enemy2), 1.0E-6);
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void circeBanquetTurnsTheGuestsWhoStayedIntoAnimals() {
        try (Scenario s = Scenario.ffa("circe")) {
            s.ticks(10);
            s.place(s.enemy2, 0.5, 6.5, 180);
            s.assertSucceeded(s.ultimate(), "키르케의 연회");
            s.ticks(3);
            assertFalse(entities(s, BlockDisplay.class).isEmpty(), "the table is set");
            for (Display part : entities(s, Display.class)) {
                assertUnrotated(part, "table");
            }
            assertFalse(circe(s).isMorphed(s.enemy), "not before the toast");
            // One guest leaves the table in time.
            s.place(s.enemy2, 0.5, s.param("feast_radius") + 3, 180);
            s.ticks(s.paramTicks("feast_warning"));
            assertTrue(circe(s).isMorphed(s.enemy), "the guest who stayed is an animal");
            assertFalse(circe(s).isMorphed(s.enemy2), "the one who fled is not");
            assertTrue(circe(s).modelOf(s.enemy) instanceof Mob, "a farm animal");
            assertTrue(s.ctx().isSilenced(s.enemy));
            // The witch's side hits animals harder.
            s.clearHits();
            s.melee(s.owner, s.enemy, 4);
            assertEquals(4 * (1 + s.param("feast_bonus") / 100), s.damageBy(s.owner, s.enemy), 1.0E-6);
            s.clearHits();
            s.ticks(s.paramTicks("feast_duration") + 2);
            assertFalse(circe(s).isMorphed(s.enemy));
            assertEquals(s.param("feast_release_damage"), s.damageBy(s.owner, s.enemy), 1.0E-6, "release damage");
            assertTrue(entities(s, Mob.class).isEmpty(), "every animal is gone");
            s.ticks(60);
            assertNoDisplays(s, "banquet over");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    // ================================================================== 마법소녀 magicalgirl

    private static MagicalGirlAbility girl(Scenario s) {
        return (MagicalGirlAbility) s.ability();
    }

    /** Presses F like the client does (the ability may take the key). */
    private static void pressF(Scenario s) {
        s.h.server.getPluginManager().callEvent(new PlayerSwapHandItemsEvent(s.owner, new ItemStack(Material.AIR),
                new ItemStack(Material.AIR)));
    }

    /** The client of {@code player} sends new input, as the server reports it. */
    private static void press(Scenario s, Player player, boolean jump) {
        s.h.server.getPluginManager().callEvent(new PlayerInputEvent(player, new Keys(false, false, false, false, jump,
                false, false)));
    }

    /** Movement keys a client could hold. */
    private record Keys(boolean isForward, boolean isBackward, boolean isLeft, boolean isRight, boolean isJump,
                        boolean isSneak, boolean isSprint) implements Input {
    }

    @Test
    void magicalGirlHeartBouncesFromEnemyToTheMostHurtAllyToAnotherEnemy() {
        try (Scenario s = Scenario.teams("magicalgirl")) {
            s.ticks(10);
            s.crossfireAlly();
            s.ally().setHealth(s.ally().getHealth() - 10);
            double allyBefore = s.ally().getHealth();
            s.aimAt(s.owner, s.enemy);
            s.clearHits();
            s.assertSucceeded(s.active(), "하트 스틱");
            s.ticks(50);
            assertEquals(s.param("heart_damage"), s.damageBy(s.owner, s.enemy), 1.0E-6, "the first enemy");
            assertEquals(allyBefore + s.param("heart_heal"), s.ally().getHealth(), 1.0E-6, "then the hurt ally");
            assertEquals(s.param("bounce_damage"), s.damageBy(s.owner, s.enemy2), 1.0E-6, "then the other enemy");
            assertEquals(0, s.damageTaken(s.ally()), EPSILON);
            assertTrue(girl(s).isTied(s.ally()), "the healed ally is tied with a ribbon");
            assertFalse(blocks(s, Material.PINK_CONCRETE).isEmpty(), "with a bow on its back");
            for (Display display : entities(s, Display.class)) {
                assertUnrotated(display, "bow");
            }
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void magicalGirlAloneHealsHerselfBetweenTwoEnemiesAndNeverThroughAWall() {
        try (Scenario s = Scenario.ffa("magicalgirl")) {
            s.ticks(10);
            s.owner.setHealth(s.owner.getHealth() - 8);
            double before = s.owner.getHealth();
            s.aimAt(s.owner, s.enemy);
            s.clearHits();
            s.assertSucceeded(s.active(), "하트 스틱");
            s.ticks(60);
            assertEquals(s.param("heart_damage"), s.damageBy(s.owner, s.enemy), 1.0E-6);
            assertEquals(before + s.param("heart_heal"), s.owner.getHealth(), 1.0E-6, "the girl catches it herself");
            assertEquals(s.param("bounce_damage"), s.damageBy(s.owner, s.enemy2), 1.0E-6, "and sends it on");
            assertTrue(texts(s, "❤").isEmpty(), "the heart is gone");

            // A wall between the first enemy and the next one stops the bounce.
            s.layout();
            s.readyActive();
            for (int y = 0; y < 3; y++) {
                for (int x = -2; x <= 4; x++) {
                    s.world.getBlockAt(x, FlatWorld.FLOOR_Y + 1 + y, 5).setType(Material.STONE);
                }
            }
            s.place(s.enemy2, 2.0, 6.5, 180);
            s.aimAt(s.owner, s.enemy);
            s.clearHits();
            s.assertSucceeded(s.active(), "하트 스틱");
            s.ticks(60);
            assertEquals(s.param("heart_damage"), s.damageBy(s.owner, s.enemy), 1.0E-6);
            assertEquals(0, s.damageBy(s.owner, s.enemy2), EPSILON, "never through a wall");
            for (int y = 0; y < 3; y++) {
                for (int x = -2; x <= 4; x++) {
                    s.world.getBlockAt(x, FlatWorld.FLOOR_Y + 1 + y, 5).setType(Material.AIR);
                }
            }
            s.finishClean();
        }
    }

    @Test
    void magicalGirlRibbonTakesAShareOfTheAllysPainAndSnapsWhenFar() {
        try (Scenario s = Scenario.teams("magicalgirl")) {
            s.ticks(10);
            s.crossfireAlly();
            s.ally().setHealth(s.ally().getHealth() - 4);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "하트 스틱");
            s.ticks(50);
            assertTrue(girl(s).isTied(s.ally()));
            s.healAll();
            s.clearHits();
            s.melee(s.enemy, s.ally(), 8);
            double share = s.param("ribbon_share") / 100;
            double cushion = s.param("ribbon_cushion") / 100;
            assertEquals(8 * (1 - share), s.damageTaken(s.ally()), 1.0E-6, "the ally takes less");
            assertEquals(8 * share * (1 - cushion), s.damageBy(s.enemy, s.owner), 1.0E-6,
                    "the girl takes a cushioned share, credited to the attacker");
            // A fall is not shared.
            s.clearHits();
            s.ally().setNoDamageTicks(0);
            s.ally().damage(4, DamageSource.builder(DamageType.FALL).build());
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "only enemies' blows");
            // Too far: the ribbon snaps.
            s.place(s.ally(), 0.5, s.param("ribbon_range") + 4, 180);
            s.ticks(3);
            assertFalse(girl(s).isTied(s.ally()), "snapped");
            s.clearHits();
            s.melee(s.enemy, s.ally(), 8);
            assertEquals(8, s.damageTaken(s.ally()), 1.0E-6);
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.finishClean();
        }
    }

    @Test
    void magicalGirlTransformationCannotBeInterruptedAndPunishesTheRude() {
        try (Scenario s = Scenario.ffa("magicalgirl")) {
            s.ticks(10);
            Location start = s.owner.getLocation();
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "변신!");
            s.ticks(2);
            s.clearHits();
            s.melee(s.enemy, s.owner, 6);
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "invulnerable while transforming");
            s.ticks(2);
            assertEquals(s.param("star_damage"), s.damageBy(s.owner, s.enemy), 1.0E-6, "변신 중엔 공격 금지!");
            assertEquals(0, speed(s.enemy), 1.0E-9, "and bound");
            assertFalse(texts(s, "변신 중엔 공격 금지").isEmpty());
            s.melee(s.enemy, s.owner, 6);
            s.ticks(2);
            assertEquals(s.param("star_damage"), s.damageBy(s.owner, s.enemy), 1.0E-6, "once per attacker");
            // Nobody pushes her away.
            s.owner.setVelocity(new Vector(1.2, 0.2, 0));
            s.ticks(2);
            assertTrue(s.owner.getLocation().distance(start) < 0.5, "pinned in place");
            s.ticks(s.paramTicks("transform_time"));
            assertTrue(girl(s).isMagicalGirl(), "a magical girl now");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);

            // A faster heart stick.
            s.readyActive();
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "하트 스틱");
            assertEquals(s.param("girl_cooldown"), s.ctx().cooldownRemaining(s.owner), 0.1);
            // A second jump in mid-air.
            s.place(s.owner, 0.5, FlatWorld.SURFACE + 3, 0.5, 0, 0);
            s.ticks(1);
            press(s, s.owner, true);
            assertTrue(s.owner.getVelocity().getY() > 0.3, "the double jump");
            press(s, s.owner, false);
            press(s, s.owner, true);
            s.tickUntilGrounded(s.owner, 120);
            s.ticks(2);
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "she floats down without fall damage");
            assertTrue(s.owner.getActivePotionEffects().isEmpty() || !s.owner.hasPotionEffect(
                    PotionEffectType.SLOW_FALLING), "no slow fall left on the ground");

            // F: the finale on the aimed spot.
            s.layout();
            s.aimAtFloor(s.owner, 0.5, 4.5);
            s.clearHits();
            pressF(s);
            s.ticks(s.paramTicks("finale_delay") + 4);
            assertFalse(girl(s).isMagicalGirl(), "the show is over");
            assertEquals(s.param("finale_damage"), s.damageBy(s.owner, s.enemy), 1.0E-6, "사랑의 피날레");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.ticks(30);
            assertTrue(texts(s, "❤").isEmpty(), "the giant heart is gone");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void magicalGirlFinaleGrowsWithFriendsAndHealsThem() {
        try (Scenario s = Scenario.builder("magicalgirl").teams().configure(h -> {
            h.settings().set("ultimate.per-damage-taken", 0);
            h.settings().set("ultimate.per-second", 0);
            h.settings().set("ultimate.recharge-delay-seconds", 0);
        }).start()) {
            s.ticks(10);
            s.place(s.ally(), 1.5, 3.5, 180);
            s.ally().setHealth(s.ally().getHealth() - 8);
            double before = s.ally().getHealth();
            s.aimAtFloor(s.owner, 0.5, 4.5);
            s.clearHits();
            s.assertSucceeded(s.ultimate(), "변신!");
            s.ticks(s.paramTicks("transform_time") + s.paramTicks("girl_duration") + s.paramTicks("finale_delay")
                    + 6);
            double expected = s.param("finale_damage") + s.param("finale_ally_bonus");
            assertEquals(expected, s.damageBy(s.owner, s.enemy), 1.0E-6, "one friend nearby: +1");
            assertEquals(before + s.param("finale_heal"), s.ally().getHealth(), 1.0E-6, "the ally is healed");
            assertEquals(0, s.damageTaken(s.ally()), EPSILON);
            assertEquals(0, s.ctx().gauge(s.owner), 1.0E-6, "the finale is part of the ultimate (no gauge)");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    // ================================================================== 코셰이 koschei

    private static KoscheiAbility koschei(Scenario s) {
        return (KoscheiAbility) s.ability();
    }

    private static LivingEntity egg(Scenario s) {
        LivingEntity egg = koschei(s).eggBody();
        assertNotNull(egg, "the egg lies on the battlefield");
        return egg;
    }

    /**
     * The egg is laid at Koschei's feet when the fight starts (wherever the owner spawned); the scenario moves the
     * players afterwards, so the egg is brought back to the owner's feet.
     */
    private static LivingEntity eggAtFeet(Scenario s) {
        LivingEntity egg = egg(s);
        egg.teleport(s.owner.getLocation());
        s.ticks(2);
        return egg;
    }

    @Test
    void koscheiEggLiesAtItsFeetAndTakesTheDeathMeantForIt() {
        try (Scenario s = Scenario.ffa("koschei")) {
            s.ticks(5);
            LivingEntity egg = eggAtFeet(s);
            assertEquals(s.owner.getLocation().getY(), egg.getLocation().getY(), 0.3, "on the ground");
            assertTrue(egg.isInvisible(), "the hitbox is invisible");
            assertTrue(s.ctx().isEnemy(s.enemy, egg), "enemies can break it");
            assertEquals(s.param("egg_health"), egg.getHealth(), 1.0E-6);
            List<BlockDisplay> shell = blocks(s, Material.TURTLE_EGG);
            assertEquals(1, shell.size(), "a big egg");
            assertTrue(shell.getFirst().isGlowing(), "glowing through walls");
            assertUnrotated(shell.getFirst(), "egg");

            s.owner.setHealth(5);
            s.clearHits();
            s.melee(s.enemy, s.owner, 10);
            assertEquals(1, s.owner.getHealth(), 1.0E-6, "not below one heart");
            assertEquals(s.param("egg_health") - (10 - 4), egg.getHealth(), 1.0E-3, "the rest went into the egg");
            s.melee(s.enemy, s.owner, 3);
            assertEquals(1, s.owner.getHealth(), 1.0E-6);
            assertEquals(s.param("egg_health") - 9, egg.getHealth(), 1.0E-3);
            // The next blow breaks the egg, and the broken egg takes Koschei with it.
            s.melee(s.enemy, s.owner, 5);
            s.ticks(3);
            assertTrue(blocks(s, Material.TURTLE_EGG).isEmpty(), "the egg broke");
            assertFalse(s.session.isAlive(s.owner), "and with it, Koschei");
            s.assertNoProblems();
            s.finishMatch();
            s.assertNoProblems();
        }
    }

    @Test
    void koscheiEggFarAwayDoesNotProtect() {
        try (Scenario s = Scenario.ffa("koschei")) {
            s.ticks(5);
            eggAtFeet(s);
            s.place(s.owner, 0.5, s.param("egg_range") + 3, 0);
            s.place(s.enemy, 0.5, s.param("egg_range") + 5, 180);
            s.ticks(3);
            s.owner.setHealth(4);
            s.melee(s.enemy, s.owner, 10);
            assertFalse(s.session.isAlive(s.owner), "too far from its death to be deathless");
            s.finishMatch();
            s.assertNoProblems();
        }
    }

    @Test
    void koscheiEggBreaksUnderEnemyBlowsHurtsKoscheiAndHealsWhenLeftAlone() {
        try (Scenario s = Scenario.ffa("koschei")) {
            s.ticks(5);
            LivingEntity egg = eggAtFeet(s);
            // Falls and fire never break it, the owner cannot either.
            egg.setNoDamageTicks(0);
            egg.damage(5, DamageSource.builder(DamageType.FALL).build());
            s.melee(s.owner, egg, 5);
            assertEquals(s.param("egg_health"), egg.getHealth(), 1.0E-6, "only enemies hurt it");
            // An enemy cracks it, then leaves: it mends.
            s.melee(s.enemy, egg, 4);
            assertEquals(s.param("egg_health") - 4, egg.getHealth(), 1.0E-6);
            s.place(s.enemy, 10.5, 10.5, 180);
            s.place(s.enemy2, -10.5, 10.5, 180);
            s.ticks(s.paramTicks("egg_regen_interval") * 2 + 2);
            assertTrue(egg.getHealth() >= s.param("egg_health") - 4 + s.param("egg_regen") - 1.0E-6, "it mends");
            // Smashed: Koschei is hurt by whoever broke it.
            s.layout();
            s.clearHits();
            s.melee(s.enemy, egg, 20);
            s.ticks(3);
            assertNull(koschei(s).eggBody());
            assertEquals(s.param("break_damage"), s.damageBy(s.enemy, s.owner), 1.0E-6, "the breaker hurts Koschei");
            assertTrue(blocks(s, Material.TURTLE_EGG).isEmpty(), "the shell is gone");
            // Without its egg it is mortal.
            s.owner.setHealth(3);
            s.melee(s.enemy2, s.owner, 6);
            assertFalse(s.session.isAlive(s.owner));
            s.finishMatch();
            s.assertNoProblems();
        }
    }

    @Test
    void koscheiHidesItsDeathInAnIronChestThatBurstsOutOfTheGround() {
        try (Scenario s = Scenario.ffa("koschei")) {
            s.ticks(5);
            LivingEntity egg = eggAtFeet(s);
            s.melee(s.enemy, egg, 3);
            double health = egg.getHealth();
            s.aimAtFloor(s.owner, 0.5, 5.5);
            s.clearHits();
            s.place(s.enemy, 2.4, 5.5, 180);
            Location enemyBefore = s.enemy.getLocation();
            s.assertSucceeded(s.active(), "죽음 숨기기");
            s.ticks(12);
            assertEquals(s.param("box_damage"), s.damageBy(s.owner, s.enemy), 1.0E-6, "the chest bursts out");
            assertTrue(s.enemy.getLocation().distance(enemyBefore) > 0.5, "and throws the enemy off");
            assertSame(egg, koschei(s).eggBody(), "the same egg");
            assertEquals(health, egg.getHealth(), 1.0E-6, "moved, not mended");
            assertEquals(0.5, egg.getX(), 0.3);
            assertEquals(5.5, egg.getZ(), 0.3);
            List<BlockDisplay> chest = blocks(s, Material.IRON_BLOCK);
            assertEquals(1, chest.size(), "an iron chest holds it");
            assertTrue(chest.getFirst().getLocation().distance(egg.getLocation()) < 0.6);
            for (Display display : entities(s, Display.class)) {
                assertUnrotated(display, "chest");
            }
            assertEquals(0, s.damageTaken(s.owner), EPSILON);

            // Aiming at the sky finds no ground.
            s.readyActive();
            s.place(s.owner, 0.5, FlatWorld.SURFACE, 0.5, 0, -80);
            assertFalse(s.active().succeeded(), "no ground aimed at");
            // A broken egg is made anew.
            s.melee(s.enemy, egg, 30);
            s.ticks(3);
            assertNull(koschei(s).eggBody());
            s.readyActive();
            s.healAll();
            s.aimAtFloor(s.owner, 3.5, 3.5);
            s.assertSucceeded(s.active(), "죽음 숨기기");
            s.ticks(10);
            assertEquals(s.param("egg_health"), egg(s).getHealth(), 1.0E-6, "a fresh egg");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void koscheiLayersKeepItStandingAndHealItForEverySurvivor() {
        try (Scenario s = Scenario.ffa("koschei")) {
            s.ticks(5);
            eggAtFeet(s);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "겹겹이 숨긴 죽음");
            s.ticks(2);
            assertNull(koschei(s).eggBody(), "the egg split");
            List<LivingEntity> layers = koschei(s).layerBodies();
            assertEquals(4, layers.size(), "chest, hare, duck and egg");
            for (LivingEntity layer : layers) {
                assertEquals(s.param("layer_health"), layer.getHealth(), 1.0E-6);
                double distance = layer.getLocation().distance(s.owner.getLocation());
                assertTrue(distance <= s.param("layer_spread") + 1, "scattered around: " + distance);
                assertTrue(s.ctx().isEnemy(s.enemy, layer));
            }
            for (Display display : entities(s, Display.class)) {
                assertUnrotated(display, "layer");
            }
            // Koschei cannot fall while a layer stands, and hits harder.
            s.owner.setHealth(3);
            s.melee(s.enemy, s.owner, 10);
            assertEquals(1, s.owner.getHealth(), 1.0E-6, "deathless in layers");
            s.ticks(2);
            assertEquals(3, koschei(s).layerBodies().size(), "the nearest layer took the blow and broke");
            s.clearHits();
            s.melee(s.owner, s.enemy2, 5);
            assertEquals(5 * (1 + s.param("layers_bonus") / 100), s.damageBy(s.owner, s.enemy2), 1.0E-6);
            // One more is smashed.
            s.melee(s.enemy2, koschei(s).layerBodies().getFirst(), 10);
            s.ticks(2);
            assertEquals(2, koschei(s).layerBodies().size());
            s.ticks(s.paramTicks("layers_duration"));
            assertTrue(koschei(s).layerBodies().isEmpty(), "the layers are gone");
            assertEquals(1 + 2 * s.param("layer_heal"), s.owner.getHealth(), 1.0E-6, "two survivors heal");
            assertEquals(s.param("egg_health"), egg(s).getHealth(), 1.0E-6, "joined into one egg again");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void koscheiLayersAllBrokenBreakTheEgg() {
        try (Scenario s = Scenario.ffa("koschei")) {
            s.ticks(5);
            eggAtFeet(s);
            s.assertSucceeded(s.ultimate(), "겹겹이 숨긴 죽음");
            s.ticks(2);
            s.clearHits();
            for (LivingEntity layer : List.copyOf(koschei(s).layerBodies())) {
                s.melee(s.enemy, layer, 10);
            }
            s.ticks(3);
            assertTrue(koschei(s).layerBodies().isEmpty());
            assertNull(koschei(s).eggBody(), "no egg left");
            assertEquals(s.param("break_damage"), s.damageBy(s.enemy, s.owner), 1.0E-6, "the last breaker hurts it");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    // ================================================================== 로쿠로쿠비 rokurokubi

    private static RokurokubiAbility roku(Scenario s) {
        return (RokurokubiAbility) s.ability();
    }

    /** The owner crouches and lets go of every movement key, as the client reports it. */
    private static void crouchStill(Scenario s) {
        s.owner.setSneaking(true);
        s.h.server.getPluginManager().callEvent(new PlayerInputEvent(s.owner, new Keys(false, false, false, false,
                false, true, false)));
    }

    private static void wall(Scenario s, int z, int x1, int x2, int height, Material type) {
        for (int x = x1; x <= x2; x++) {
            for (int y = 0; y < height; y++) {
                s.world.getBlockAt(x, FlatWorld.FLOOR_Y + 1 + y, z).setType(type);
            }
        }
    }

    @Test
    void rokurokubiPeeksOverCoverAndMakesWhatItSeesGlow() {
        try (Scenario s = Scenario.ffa("rokurokubi")) {
            s.ticks(10);
            wall(s, 3, -4, 5, 2, Material.STONE);
            s.owner.hidePlayer(s.ctx().plugin(), s.enemy2);
            crouchStill(s);
            s.ticks(s.paramTicks("peek_delay") + 2);
            assertTrue(roku(s).isPeeking(), "the neck rises");
            List<BlockDisplay> neck = blocks(s, Material.WHITE_TERRACOTTA);
            assertEquals(1, neck.size(), "a pale neck column");
            assertUnrotated(neck.getFirst(), "neck");
            float top = neck.getFirst().getTransformation().getTranslation().y
                    + neck.getFirst().getTransformation().getScale().y;
            assertTrue(top > s.owner.getHeight() + s.param("peek_height") - 0.3, "about three blocks up: " + top);
            s.ticks(20);
            assertTrue(s.enemy.hasPotionEffect(PotionEffectType.GLOWING), "the enemy behind the wall glows");
            assertFalse(s.enemy2.hasPotionEffect(PotionEffectType.GLOWING), "a hidden enemy is never given away");
            // Moving (a key pressed) lowers the neck.
            s.h.server.getPluginManager().callEvent(new PlayerInputEvent(s.owner, new Keys(true, false, false, false,
                    false, true, false)));
            s.ticks(2);
            assertFalse(roku(s).isPeeking(), "walking ends the peek");
            s.ticks(6);
            assertTrue(blocks(s, Material.WHITE_TERRACOTTA).isEmpty(), "the neck is gone");
            s.owner.setSneaking(false);
            s.owner.showPlayer(s.ctx().plugin(), s.enemy2);
            wall(s, 3, -4, 5, 2, Material.AIR);
            s.finishClean();
        }
    }

    @Test
    void rokurokubiPeeksOnlyAsHighAsTheRoofAllows() {
        try (Scenario s = Scenario.ffa("rokurokubi")) {
            s.ticks(10);
            ceiling(s, -1, 1, -1, 1, FlatWorld.FLOOR_Y + 4, Material.STONE);
            crouchStill(s);
            s.ticks(s.paramTicks("peek_delay") + 10);
            assertTrue(roku(s).isPeeking());
            BlockDisplay neck = blocks(s, Material.WHITE_TERRACOTTA).getFirst();
            float top = neck.getTransformation().getTranslation().y + neck.getTransformation().getScale().y;
            assertTrue(top < FlatWorld.FLOOR_Y + 4 - FlatWorld.SURFACE + 0.1, "below the roof: " + top);
            s.owner.setSneaking(false);
            ceiling(s, -1, 1, -1, 1, FlatWorld.FLOOR_Y + 4, Material.AIR);
            s.finishClean();
        }
    }

    @Test
    void rokurokubiNeckBitesTheFirstEnemyWhileTheBodyStaysPut() {
        try (Scenario s = Scenario.ffa("rokurokubi")) {
            s.ticks(10);
            s.aimAt(s.owner, s.enemy);
            Location start = s.owner.getLocation();
            s.clearHits();
            s.assertSucceeded(s.active(), "늘어나는 목");
            s.ticks(2);
            assertEquals(0, speed(s.owner), 1.0E-9, "the body is held in place");
            boolean pointed = false;
            for (int i = 0; i < 20; i++) {
                s.ticks(1);
                for (Display display : entities(s, Display.class)) {
                    assertUnrotated(display, "neck");
                }
                for (BlockDisplay segment : blocks(s, Material.WHITE_TERRACOTTA)) {
                    Vector3f along = forward(rotationOf(segment));
                    pointed |= along.dot(lookOf(s.owner)) > 0.95;
                }
            }
            assertTrue(pointed, "the neck segments run along the gaze");
            assertEquals(s.param("bite_damage"), s.damageBy(s.owner, s.enemy), 1.0E-6, "bitten");
            assertTrue(speed(s.enemy) < baseSpeed(s.enemy) - 1.0E-6, "and slowed");
            assertEquals(0, s.damageBy(s.owner, s.enemy2), EPSILON, "only the first one");
            assertTrue(s.owner.getLocation().distance(start) < 0.1, "the body never moved");
            s.ticks(20);
            assertTrue(blocks(s, Material.WHITE_TERRACOTTA).isEmpty(), "the neck reeled in");
            assertNull(roku(s).neckTip());
            assertEquals(baseSpeed(s.owner), speed(s.owner), 1.0E-9, "free again");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void rokurokubiNeckBendsAroundACornerAndBitesHarderThere() {
        try (Scenario s = Scenario.ffa("rokurokubi")) {
            s.ticks(10);
            wall(s, 3, -4, 1, 3, Material.STONE);
            s.place(s.enemy, 0.5, 5.5, 180);
            s.place(s.enemy2, -10.5, -10.5, 180);
            assertFalse(s.owner.hasLineOfSight(s.enemy), "the enemy hides behind the wall");
            s.aimAt(s.owner, new Location(s.world, 2.8, 1.62 + FlatWorld.SURFACE, 3.5));
            s.clearHits();
            s.assertSucceeded(s.active(), "늘어나는 목");
            for (int i = 0; i < 18 && s.damageBy(s.owner, s.enemy) == 0; i++) {
                s.ticks(1);
                Location tip = roku(s).neckTip();
                if (tip != null && tip.getZ() > 3.9) {
                    // Past the corner: look the way the head must turn.
                    Location look = s.owner.getLocation();
                    look.setDirection(Fx.body(s.enemy).toVector().subtract(tip.toVector()));
                    s.owner.teleport(look);
                }
            }
            assertEquals(s.param("bite_damage") + s.param("corner_bonus"), s.damageBy(s.owner, s.enemy), 1.0E-6,
                    "bitten round the corner");
            s.ticks(20);
            wall(s, 3, -4, 1, 3, Material.AIR);
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void rokurokubiNeckStopsAtAWall() {
        try (Scenario s = Scenario.ffa("rokurokubi")) {
            s.ticks(10);
            wall(s, 2, -3, 3, 3, Material.STONE);
            s.aimAt(s.owner, s.enemy);
            s.clearHits();
            s.assertSucceeded(s.active(), "늘어나는 목");
            s.ticks(30);
            assertEquals(0, s.damageBy(s.owner, s.enemy), EPSILON, "the wall stops the head");
            assertTrue(blocks(s, Material.WHITE_TERRACOTTA).isEmpty());
            wall(s, 2, -3, 3, 3, Material.AIR);
            s.finishClean();
        }
    }

    @Test
    void rokurokubiHeadFliesBitesAndHealsWhileTheBodyStaysBehind() {
        try (Scenario s = Scenario.ffa("rokurokubi")) {
            s.ticks(10);
            Location start = s.owner.getLocation();
            s.assertSucceeded(s.ultimate(), "누케쿠비");
            s.ticks(2);
            assertTrue(roku(s).isHeadFlying());
            assertEquals(s.param("head_scale"), scale(s.owner), 1.0E-6, "a small head");
            assertTrue(s.owner.getAllowFlight() && s.owner.isFlying(), "flying");
            assertTrue(s.owner.hasPotionEffect(PotionEffectType.INVISIBILITY), "the rest of the body is gone");
            LivingEntity body = roku(s).body();
            assertNotNull(body, "the body stays behind");
            assertTrue(body.getLocation().distance(start) < 0.6);
            assertTrue(s.ctx().isEnemy(s.enemy, body), "enemies can go for it");
            assertEquals(Material.SHROOMLIGHT, ((org.bukkit.entity.Mannequin) body).getEquipment()
                    .getItem(org.bukkit.inventory.EquipmentSlot.HEAD).getType(), "a paper lantern where its head was");
            List<org.bukkit.entity.ItemDisplay> heads = entities(s, org.bukkit.entity.ItemDisplay.class);
            assertEquals(1, heads.size(), "its own head flies");
            assertFalse(s.owner.canSee(heads.getFirst()), "not in its own face");
            assertTrue(s.enemy.canSee(heads.getFirst()));
            assertUnrotated(heads.getFirst(), "head");
            s.place(s.owner, 3.5, FlatWorld.SURFACE + 2, -3.5, 90, 25);
            s.ticks(2);
            assertTrue(forward(rotationOf(heads.getFirst())).dot(lookOf(s.owner)) > 0.95,
                    "the head faces where the owner looks");
            assertEquals(s.owner.getX(), heads.getFirst().getX(), 1.0E-6, "the head is where the owner is");

            // It bites what it touches, and every bite feeds it.
            s.owner.setHealth(s.owner.getHealth() - 6);
            double before = s.owner.getHealth();
            s.clearHits();
            s.place(s.owner, 0.5, FlatWorld.SURFACE + 0.6, 4.2, 180, 0);
            s.ticks(2);
            assertEquals(s.param("head_bite"), s.damageBy(s.owner, s.enemy), 1.0E-6, "bitten");
            assertEquals(before + s.param("head_heal"), s.owner.getHealth(), 1.0E-6, "fed");
            s.ticks(s.paramTicks("head_bite_interval") + 1);
            assertEquals(2 * s.param("head_bite"), s.damageBy(s.owner, s.enemy), 1.0E-6, "again after the interval");

            // A blow on the body reaches the head, harder.
            s.place(s.owner, 6.5, FlatWorld.SURFACE + 2, 6.5, 0, 0);
            s.clearHits();
            s.melee(s.enemy2, body, 2);
            s.ticks(1);
            assertEquals(2 * s.param("body_share"), s.damageBy(s.enemy2, s.owner), 1.0E-6, "the head feels it");
            assertTrue(body.isValid() && !body.isDead(), "the body itself never dies");

            // F: the head flies home and fits (2 damage is under the limit).
            pressF(s);
            s.ticks(2);
            assertFalse(roku(s).isHeadFlying(), "back on its body");
            assertEquals(1, scale(s.owner), 1.0E-6);
            assertFalse(s.owner.isFlying());
            assertFalse(s.owner.getAllowFlight());
            assertFalse(s.owner.hasPotionEffect(PotionEffectType.INVISIBILITY));
            assertTrue(s.owner.getLocation().distance(start) < 0.6, "where the body stood");
            assertEquals(FlatWorld.SURFACE, s.owner.getLocation().getY(), 1.0E-6, "feet on the ground");
            assertFalse(body.isValid(), "the body is gone");
            assertFalse(s.ctx().isSilenced(s.owner), "it fit cleanly");
            assertTrue(entities(s, org.bukkit.entity.Mannequin.class).isEmpty());
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void rokurokubiUltimateTearsTheHeadOffAStretchedNeck() {
        try (Scenario s = Scenario.ffa("rokurokubi")) {
            s.ticks(10);
            s.aimAt(s.owner, s.enemy2);
            s.assertSucceeded(s.active(), "늘어나는 목");
            s.ticks(4);
            assertNotNull(roku(s).neckTip(), "the neck is out");
            s.assertSucceeded(s.ultimate(), "누케쿠비");
            s.ticks(1);
            assertNull(roku(s).neckTip(), "the neck snapped");
            assertTrue(blocks(s, Material.WHITE_TERRACOTTA).isEmpty(), "no neck left behind");
            assertTrue(roku(s).isHeadFlying(), "the head flies on");
            assertTrue(speed(s.owner) > 0, "the neck's hold is gone");
            pressF(s);
            s.ticks(2);
            assertFalse(roku(s).isHeadFlying());
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void rokurokubiHeadDoesNotFitABatteredOrMovedBody() {
        try (Scenario s = Scenario.ffa("rokurokubi")) {
            s.ticks(10);
            s.assertSucceeded(s.ultimate(), "누케쿠비");
            s.ticks(2);
            LivingEntity body = roku(s).body();
            assertNotNull(body);
            s.place(s.owner, 8.5, FlatWorld.SURFACE + 2, 8.5, 0, 0);
            s.melee(s.enemy, body, s.param("body_limit"));
            s.ticks(s.paramTicks("head_duration"));
            assertFalse(roku(s).isHeadFlying(), "time is up");
            assertTrue(s.ctx().isSilenced(s.owner), "a crooked head: stunned");
            assertEquals(0, speed(s.owner), 1.0E-9);
            s.ticks(s.paramTicks("misfit_stun") + 2);
            assertFalse(s.ctx().isSilenced(s.owner));

            // A moved body too.
            s.assertSucceeded(s.ultimate(), "누케쿠비");
            s.ticks(2);
            LivingEntity moved = roku(s).body();
            assertNotNull(moved);
            moved.teleport(moved.getLocation().add(s.param("body_push") + 1.5, 0, 0));
            pressF(s);
            s.ticks(2);
            assertTrue(s.ctx().isSilenced(s.owner), "a moved body does not take its head back cleanly");
            assertTrue(s.owner.getLocation().distance(moved.getLocation()) < 1.5, "it still goes home");
            s.ticks(s.paramTicks("misfit_stun") + 2);
            s.assertNoProblems();
            s.finishClean();
        }
    }
}
