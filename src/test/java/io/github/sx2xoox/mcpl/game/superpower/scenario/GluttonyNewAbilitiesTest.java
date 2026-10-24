package io.github.sx2xoox.mcpl.game.superpower.scenario;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import io.github.sx2xoox.mcpl.game.superpower.abilities.AgwiAbility;
import io.github.sx2xoox.mcpl.game.superpower.abilities.BulgasariAbility;
import io.github.sx2xoox.mcpl.game.superpower.abilities.CentipedeAbility;
import io.github.sx2xoox.mcpl.game.superpower.abilities.MidasAbility;
import io.github.sx2xoox.mcpl.game.superpower.abilities.TaotieAbility;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Spots;
import io.github.sx2xoox.mcpl.game.superpower.ability.Ability;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import io.github.sx2xoox.mcpl.game.superpower.ability.ActivationResult;
import io.github.sx2xoox.mcpl.game.superpower.support.Scenario;
import io.github.sx2xoox.mcpl.game.superpower.support.TestPlayer;
import io.github.sx2xoox.mcpl.game.superpower.support.world.FlatWorld;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.jspecify.annotations.Nullable;

/**
 * 탐식 · 탐욕 (새 능력): 불가사리, 아귀, 미다스, 왕지네, 도철. Every ability through a whole match, the owner dying in the
 * middle of each skill, team mode with an ally in the line of fire, the standalone ultimates borrowed by a Gambler, the
 * displays keeping their own rotation at zero, and each ability's own mechanics: the gnawed armour, the chewed weapon
 * and the iron body that swallows blades and arrows (but not fire); the stolen heals, the begging bowl and the realm
 * where heals burn; the gilded armour anyone can crack, the coins and the tax; the trailing body, the walls of the
 * road and the king's ring with its gap; the mask, the bite, the swallowed shots, the copied ultimate and the digestion.
 */
class GluttonyNewAbilitiesTest {

    private static final double EPSILON = 1.0E-6;
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z0-9_]+)}");

    static Stream<String> abilities() {
        return Stream.of("bulgasari", "agwi", "midas", "centipede", "taotie");
    }

    /** The abilities whose ultimate the Gambler may borrow (the bulgasari's iron body lives in its damage hook). */
    static Stream<String> standalone() {
        return Stream.of("agwi", "midas", "centipede", "taotie");
    }

    // ------------------------------------------------------------------ helpers

    private static <T extends Entity> List<T> entities(Scenario s, Class<T> type) {
        return s.world.getEntities().stream().filter(type::isInstance).map(type::cast).toList();
    }

    private static List<BlockDisplay> blocks(Scenario s, Material material) {
        return entities(s, BlockDisplay.class).stream()
                .filter(display -> display.getBlock().getMaterial() == material).toList();
    }

    private static void assertNoDisplays(Scenario s, String what) {
        List<Display> left = entities(s, Display.class).stream()
                // The centipede's trailing body is its passive: it stays while the centipede lives.
                .filter(display -> !(s.abilityId.equals("centipede") && display instanceof BlockDisplay block
                        && isSegment(block)))
                .toList();
        assertTrue(left.isEmpty(), what + ": every display is gone: "
                + left.stream().map(d -> d.getType() + "@" + d.getLocation().toVector()).toList());
    }

    private static boolean isSegment(BlockDisplay display) {
        Material material = display.getBlock().getMaterial();
        return material == Material.NETHER_WART_BLOCK || material == Material.BLACK_CONCRETE
                || material == Material.YELLOW_TERRACOTTA;
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

    private static double modifierSum(LivingEntity entity, Attribute attribute) {
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance == null) {
            return 0;
        }
        return instance.getModifiers().stream()
                .filter(modifier -> !modifier.getKey().equals(Scenario.TANK))
                .mapToDouble(AttributeModifier::getAmount).sum();
    }

    private static boolean rooted(Player player) {
        return player.getAttribute(Attribute.MOVEMENT_SPEED).getModifiers().stream()
                .anyMatch(modifier -> modifier.getAmount() <= -1.0 + EPSILON)
                && player.getAttribute(Attribute.JUMP_STRENGTH).getModifiers().stream()
                .anyMatch(modifier -> modifier.getAmount() <= -1.0 + EPSILON);
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
            for (TestPlayer viewer : s.all()) {
                assertTrue(viewer.canSee(enemy), id + ": " + viewer.getName() + " sees " + enemy.getName());
            }
        }
        assertModifierFree(s.owner, id);
        s.assertNoProblems();
        s.finishMatch();
        s.assertNoProblems();
        s.assertCleanWorld();
    }

    /**
     * The left rotation a display's transformation was set with. The test doubles keep it as set; should a mock
     * hand it back conjugated, a probe display tells and the check still holds.
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

    /** The world direction of a local axis of a display (whose own rotation is 0). */
    private static Vector axis(Display display, float x, float y, float z) {
        Vector3f v = leftRotation(display).transform(new Vector3f(x, y, z));
        return new Vector(v.x(), v.y(), v.z());
    }

    /** The horizontal unit vector of a Minecraft yaw (0 = +z, 90 = -x). */
    private static Vector facing(double yawDegrees) {
        double yaw = Math.toRadians(yawDegrees);
        return new Vector(-Math.sin(yaw), 0, Math.cos(yaw));
    }

    /** Displays carry no rotation of their own: every orientation lives in their transformation. */
    private static void assertUnrotated(Scenario s, String what) {
        for (Display display : entities(s, Display.class)) {
            assertEquals(0, display.getLocation().getYaw(), 1.0E-3, what + ": display yaw of " + display);
            assertEquals(0, display.getLocation().getPitch(), 1.0E-3, what + ": display pitch of " + display);
        }
    }

    /** At most about two dozen displays alive per ability instance. */
    private static void assertDisplayBudget(Scenario s, String what) {
        int alive = entities(s, Display.class).size();
        assertTrue(alive <= 24, what + ": " + alive + " displays alive at once");
    }

    /** Feet exactly on the floor and a body that touches no block. */
    private static void assertStandsOnFloor(Player player, String what) {
        Location at = player.getLocation();
        assertEquals(FlatWorld.SURFACE, at.getY(), EPSILON, what + ": feet on the surface " + at);
        assertTrue(Spots.isFree(at.getWorld(), at, player.getWidth(), player.getHeight()),
                what + ": not inside a block " + at);
    }

    /** Walks {@code player} along the floor to (x, z), {@code step} blocks per tick, looking where it goes. */
    private static void walk(Scenario s, Player player, double x, double z, double step) {
        Location at = player.getLocation();
        Vector to = new Vector(x - at.getX(), 0, z - at.getZ());
        double length = to.length();
        if (length < 1.0E-6) {
            return;
        }
        Vector unit = to.clone().multiply(1 / length);
        float yaw = (float) Math.toDegrees(Math.atan2(-unit.getX(), unit.getZ()));
        int steps = (int) Math.ceil(length / step);
        for (int i = 1; i <= steps; i++) {
            double t = Math.min(length, i * step);
            s.place(player, at.getX() + unit.getX() * t, FlatWorld.SURFACE, at.getZ() + unit.getZ() * t, yaw, 0);
            s.ticks(1);
        }
    }

    /** Calls a package-private accessor of an ability (the tests live in another package). */
    @SuppressWarnings("unchecked")
    private static <T> T peek(Ability ability, String method, Object... args) {
        try {
            for (Method m : ability.getClass().getDeclaredMethods()) {
                if (m.getName().equals(method) && m.getParameterCount() == args.length) {
                    m.setAccessible(true);
                    return (T) m.invoke(ability, args);
                }
            }
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
        throw new AssertionError("no " + method + " on " + ability.getClass().getSimpleName());
    }

    private static Arrow arrowFrom(Scenario s, LivingEntity shooter, LivingEntity at) {
        Arrow arrow = s.world.spawn(at.getLocation().add(0, 1, 0), Arrow.class);
        arrow.setShooter(shooter);
        return arrow;
    }

    /** An arrow from {@code shooter} lands on {@code victim} (a plain projectile hit as the server reports it). */
    private static void shoot(Scenario s, LivingEntity shooter, LivingEntity victim, double amount) {
        Arrow arrow = arrowFrom(s, shooter, victim);
        victim.setNoDamageTicks(0);
        victim.damage(amount, DamageSource.builder(DamageType.ARROW).withCausingEntity(shooter)
                .withDirectEntity(arrow).build());
        if (arrow.isValid()) {
            arrow.remove();
        }
    }

    // ================================================================== every ability

    @Test
    void descriptionsOnlyReferenceDeclaredParams() {
        for (AbilityInfo info : List.of(BulgasariAbility.INFO, AgwiAbility.INFO, MidasAbility.INFO,
                CentipedeAbility.INFO, TaotieAbility.INFO)) {
            for (AbilityInfo.Skill skill : List.of(info.passive(), info.active(), info.ultimate())) {
                Matcher matcher = PLACEHOLDER.matcher(skill.description());
                while (matcher.find()) {
                    assertTrue(info.params().containsKey(matcher.group(1)),
                            info.id() + " " + skill.name() + " uses an undeclared param " + matcher.group());
                }
                assertFalse(skill.name().contains("TODO") || skill.description().contains("TODO"), info.id());
            }
            assertEquals(!info.id().equals("bulgasari"), info.ultimateStandalone(), info.id() + " standalone");
        }
    }

    /** What each ability needs to hit the aimed enemy in the default layout. */
    private static void prepare(Scenario s) {
        if (s.abilityId.equals("bulgasari")) {
            // The bite only reaches 3.5 blocks.
            s.place(s.enemy, 0.5, 2.8, 180f);
        }
        s.aimAt(s.owner, s.enemy);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("abilities")
    void wholeMatch(String id) {
        // The taotie's mask (its passive) is short here, so it is gone when the displays are counted.
        try (Scenario s = noGaugeGain(Scenario.builder(id)).configure(h -> h.setParam("taotie", "mask_duration", 4))
                .start()) {
            s.ticks(60);
            s.assertNoProblems();
            prepare(s);
            s.clearHits();
            s.assertSucceeded(s.active(), id + " active");
            s.ticks(100);
            if (!id.equals("centipede")) {
                assertTrue(s.damageTaken(s.enemy) > 0, id + " active hurts the aimed enemy");
            }
            assertEquals(0, s.damageTaken(s.owner), EPSILON, id + " active never hurts its owner: " + s.hits());

            AbilityLifecycleTest.prepareUltimate(s);
            s.healAll();
            s.clearHits();
            s.assertSucceeded(s.ultimate(), id + " ultimate");
            if (id.equals("bulgasari")) {
                // Something for the iron body to swallow and spit back out.
                s.melee(s.enemy, s.owner, 3);
                s.melee(s.enemy2, s.owner, 3);
            }
            s.ticks(300);
            if (!id.equals("centipede")) {
                assertTrue(s.damageTaken(s.enemy) > 0, id + " ultimate hurts the aimed enemy");
            }
            assertEquals(0, s.damageTaken(s.owner), EPSILON, id + " ultimate never hurts its owner: " + s.hits());
            s.assertNoProblems();
            assertNoDisplays(s, id);
            assertEquals(0, s.h.hub.tempBlocks().size(), id + ": temporary blocks restored");
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
            prepare(s);
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
            prepare(s);
            s.assertSucceeded(s.active(), id + " active");
            if (id.equals("centipede")) {
                walk(s, s.owner, 6.5, 0.5, 0.3);
            } else {
                // The weapon is being chewed, the bowl hangs over a head, the coins fly, the maw is open.
                s.ticks(12);
            }
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
    void ownerDiesLateInTheUltimate(String id) {
        try (Scenario s = Scenario.ffa(id)) {
            s.ticks(40);
            AbilityLifecycleTest.prepareUltimate(s);
            s.assertSucceeded(s.ultimate(), id + " ultimate");
            if (id.equals("centipede")) {
                walk(s, s.owner, 6.5, 0.5, 0.3);
            } else {
                // Iron body full of swallowed blades, realm burning, gold spreading, an enemy being digested.
                s.melee(s.enemy, s.owner, 2);
                s.ticks(30);
            }
            assertOwnerDeathCleansUp(s);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("abilities")
    void teamModeNeverHarmsTheAlly(String id) {
        try (Scenario s = Scenario.teams(id)) {
            s.ticks(40);
            s.crossfireAlly();
            prepare(s);
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

    @ParameterizedTest(name = "{0}")
    @MethodSource("abilities")
    void displaysNeverTakeTheOwnersRotation(String id) {
        try (Scenario s = Scenario.ffa(id)) {
            turnedLayout(s);
            s.assertSucceeded(s.active(), id + " active");
            for (int i = 0; i < 60; i++) {
                s.ticks(1);
                assertUnrotated(s, id + " active");
                assertDisplayBudget(s, id + " active");
            }
            s.ticks(120);
            turnedLayout(s);
            s.assertSucceeded(s.ultimate(), id + " ultimate");
            for (int i = 0; i < 200; i++) {
                s.ticks(1);
                assertUnrotated(s, id + " ultimate");
                assertDisplayBudget(s, id + " ultimate");
            }
            s.ticks(100);
            s.finishClean();
        }
    }

    /** A layout turned by 90 degrees: the owner looks west (-x), so a display taking the owner's yaw is caught. */
    private static void turnedLayout(Scenario s) {
        s.place(s.owner, 0.5, 0.5, 90);
        s.place(s.enemy, -2.5, 0.5, -90);
        s.place(s.enemy2, -5.5, 2.0, -90);
        if (s.ally != null) {
            s.place(s.ally(), 0.5, 3.5, 0);
        }
        s.aimAt(s.owner, s.enemy);
    }

    // ------------------------------------------------------------------ borrowed by the gambler

    @ParameterizedTest(name = "{0}")
    @MethodSource("standalone")
    void borrowedUltimateWorksOnAGambler(String id) {
        try (Scenario s = gambler()) {
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(borrow(s, id), "borrowed " + id);
            if (id.equals("centipede")) {
                // The gambler runs a ring around the enemy with the borrowed body.
                ringAround(s, s.enemy);
            }
            s.ticks(300);
            assertTrue(s.damageBy(s.owner, s.enemy) > 0, "the borrowed " + id + " hurts the enemy");
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "the borrowed " + id + " never hurts the gambler");
            assertModifierFree(s.owner, id);
            for (TestPlayer target : s.enemies()) {
                assertModifierFree(target, id);
                assertFalse(s.ctx().isSilenced(target), id + ": nobody stays silenced");
                assertTrue(s.owner.canSee(target), id + ": the gambler sees " + target.getName() + " again");
            }
            assertTrue(entities(s, Display.class).isEmpty(), id + ": every display is gone");
            assertEquals(0, s.h.hub.tempBlocks().size(), id + ": temporary blocks restored");
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
            s.ticks(id.equals("taotie") ? 30 : 16);
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
            for (TestPlayer target : s.enemies()) {
                for (TestPlayer viewer : s.all()) {
                    assertTrue(viewer.canSee(target), id + ": " + viewer.getName() + " sees " + target.getName());
                }
            }
            assertEquals(0, s.h.hub.tempBlocks().size(), id + ": temporary blocks restored");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    /**
     * Walks the owner in a closed square around {@code target} (along the middles of the cells two blocks out), stops
     * one cell short of where it started and steps out of the ring, so the last cell of the ring is laid too.
     */
    private static void ringAround(Scenario s, Player target) {
        // Nobody else stands on the line of the ring (a wall never rises where someone stands).
        for (TestPlayer other : s.targets()) {
            if (!other.equals(target)) {
                s.place(other, 12.5, 12.5 + s.targets().indexOf(other), 0);
            }
        }
        Location c = target.getLocation();
        double r = 2;
        s.place(s.owner, c.getX() - r, c.getZ() - r, 0);
        s.ticks(1);
        walk(s, s.owner, c.getX() - r, c.getZ() + r, 0.3);
        walk(s, s.owner, c.getX() + r, c.getZ() + r, 0.3);
        walk(s, s.owner, c.getX() + r, c.getZ() - r, 0.3);
        walk(s, s.owner, c.getX() - r + 1, c.getZ() - r, 0.3);
        walk(s, s.owner, c.getX() - r + 1, c.getZ() - r - 2, 0.3);
    }

    // ================================================================== 불가사리 bulgasari

    private static int gnaws(Scenario s, LivingEntity enemy) {
        return peek(s.ability(), "gnawsOn", enemy);
    }

    @Test
    void bulgasariGnawsArmourAndGrowsOnIron() {
        try (Scenario s = Scenario.ffa("bulgasari")) {
            s.ticks(5);
            assertNotNull(s.enemy.getEquipment().getChestplate(), "the kit armour is worn");
            double max = s.maxHealth(s.owner);
            double armor = s.param("gnaw_armor");
            s.melee(s.owner, s.enemy, 2);
            assertEquals(1, gnaws(s, s.enemy), "one gnaw");
            assertEquals(-armor, modifierSum(s.enemy, Attribute.ARMOR), EPSILON, "the armour lost a point");
            assertEquals(max + s.param("grow_health"), s.maxHealth(s.owner), EPSILON, "a little sturdier");
            assertEquals(s.param("grow_scale") / 100, modifierSum(s.owner, Attribute.SCALE), EPSILON,
                    "and a little bigger");
            s.melee(s.owner, s.enemy, 2);
            assertEquals(1, gnaws(s, s.enemy), "the same enemy only once per " + s.param("gnaw_cooldown") + " s");
            int cap = (int) s.param("gnaw_max");
            for (int i = 0; i < 9; i++) {
                s.ticks(s.paramTicks("gnaw_cooldown") + 1);
                s.melee(s.owner, i % 2 == 0 ? s.enemy : s.enemy2, 2);
            }
            assertEquals(cap, gnaws(s, s.enemy), "the gnaws on one enemy stop at the cap");
            assertEquals(-armor * cap, modifierSum(s.enemy, Attribute.ARMOR), EPSILON, "armour -" + cap);
            int meals = peek(s.ability(), "meals");
            assertEquals((int) s.param("grow_max"), meals, "the growth stops at the cap");
            assertEquals(max + s.param("grow_health") * meals, s.maxHealth(s.owner), EPSILON, "max health capped");
            assertEquals(s.param("grow_scale") / 100 * meals, modifierSum(s.owner, Attribute.SCALE), EPSILON,
                    "size capped");
            s.ticks(s.paramTicks("gnaw_duration") + 2);
            assertEquals(0, gnaws(s, s.enemy), "the gnawed armour grows back");
            assertEquals(0, modifierSum(s.enemy, Attribute.ARMOR), EPSILON, "every armour modifier is gone");
            assertEquals(max + s.param("grow_health") * meals, s.maxHealth(s.owner), EPSILON,
                    "the bulgasari keeps what it ate");
            s.finishClean();
        }
    }

    @Test
    void bulgasariFindsNothingToGnawOnBareSkin() {
        try (Scenario s = Scenario.ffa("bulgasari")) {
            s.ticks(5);
            s.enemy.getEquipment().setArmorContents(new ItemStack[4]);
            s.melee(s.owner, s.enemy, 2);
            assertEquals(0, gnaws(s, s.enemy), "no armour, nothing to gnaw");
            assertEquals(0, (int) peek(s.ability(), "meals"), "and nothing to eat");
            s.finishClean();
        }
    }

    @Test
    void bulgasariBurnsEasily() {
        try (Scenario s = Scenario.ffa("bulgasari")) {
            s.ticks(5);
            s.owner.damage(2, DamageSource.builder(DamageType.ON_FIRE).build());
            assertEquals(2 * (1 + s.param("fire_weakness") / 100), s.damageTaken(s.owner), EPSILON, "a burn");
            s.clearHits();
            s.ctx().damage(s.enemy, s.owner, 4);
            assertEquals(4, s.damageTaken(s.owner), EPSILON, "a cold ability hurts as usual");
            s.finishClean();
        }
        try (Scenario s = Scenario.builder("bulgasari").enemyAbility("pyro").start()) {
            s.ticks(5);
            s.ctx().damage(s.enemy, s.owner, 4);
            assertEquals(4 * (1 + s.param("fire_weakness") / 100), s.damageTaken(s.owner), EPSILON,
                    "the fire wielder's ability is fire");
            s.finishClean();
        }
    }

    private static List<ItemDisplay> items(Scenario s, Material material) {
        return entities(s, ItemDisplay.class).stream().filter(d -> d.getItemStack().getType() == material).toList();
    }

    @Test
    void bulgasariChewsTheBittenEnemysBlade() {
        try (Scenario s = Scenario.ffa("bulgasari")) {
            s.ticks(5);
            s.place(s.enemy, 0.5, 2.8, 180);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "칼 씹어먹기");
            assertEquals(s.param("bite_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "the bite");
            assertEquals(1, gnaws(s, s.enemy), "the bite gnaws the armour too");
            s.ticks(2);
            List<ItemDisplay> chewed = items(s, Material.IRON_SWORD);
            assertEquals(1, chewed.size(), "the enemy's sword floats over its head, being chewed");
            assertTrue(chewed.getFirst().getLocation().getY() > s.enemy.getLocation().getY() + s.enemy.getHeight(),
                    "over the head");

            s.owner.setHealth(10);
            s.clearHits();
            s.melee(s.enemy, s.owner, 5);
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "the chewed blade cannot hurt the bulgasari");
            assertEquals(10 + s.param("chew_heal"), s.health(s.owner), EPSILON, "it is food");
            s.melee(s.enemy, s.owner, 5);
            assertEquals(10 + s.param("chew_heal"), s.health(s.owner), EPSILON, "one meal per interval");
            s.melee(s.enemy2, s.owner, 3);
            assertEquals(3, s.damageBy(s.enemy2, s.owner), EPSILON, "another enemy's blade still hurts");
            s.ctx().damage(s.enemy, s.owner, 2);
            assertEquals(2, s.damageBy(s.enemy, s.owner), EPSILON, "the chewed enemy's abilities still hurt");
            s.melee(s.enemy, s.enemy2, 3);
            assertEquals(3, s.damageTaken(s.enemy2), EPSILON, "and the chewed blade still hurts everyone else");

            s.ticks(s.paramTicks("chew_duration") + 6);
            assertTrue(items(s, Material.IRON_SWORD).isEmpty(), "the chewed sword is gone");
            s.clearHits();
            s.melee(s.enemy, s.owner, 4);
            assertEquals(4, s.damageTaken(s.owner), EPSILON, "a fresh blade hurts again");
            s.finishClean();
        }
    }

    @Test
    void bulgasariChompingOnAirHalvesTheCooldown() {
        try (Scenario s = Scenario.ffa("bulgasari")) {
            s.ticks(5);
            s.place(s.enemy, 0.5, 9.5, 180);
            s.place(s.enemy2, 6.5, 9.5, 180);
            s.clearHits();
            s.assertSucceeded(s.active(), "칼 씹어먹기");
            assertTrue(s.hits().isEmpty(), "nobody was bitten");
            assertEquals(s.param("cooldown") * s.param("miss_cooldown") / 100, s.ctx().cooldownRemaining(s.owner),
                    0.06, "half the cooldown");
            s.finishClean();
        }
    }

    @Test
    void bulgasariIronBodySwallowsBladesAndArrowsButNotAbilitiesOrFire() {
        try (Scenario s = noGaugeGain(Scenario.builder("bulgasari")).start()) {
            s.ticks(5);
            s.assertSucceeded(s.ultimate(), "불가살이");
            s.ticks(2);
            assertTrue(blocks(s, Material.IRON_BLOCK).size() >= 4, "iron plates clamp around the body");
            s.owner.setHealth(10);
            s.clearHits();
            s.melee(s.enemy, s.owner, 4);
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "a blade is swallowed");
            assertEquals(10 + s.param("iron_heal"), s.health(s.owner), EPSILON, "and feeds the bulgasari");
            Arrow arrow = arrowFrom(s, s.enemy2, s.owner);
            s.owner.setNoDamageTicks(0);
            s.owner.damage(5, DamageSource.builder(DamageType.ARROW).withCausingEntity(s.enemy2)
                    .withDirectEntity(arrow).build());
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "an arrow is swallowed");
            assertFalse(arrow.isValid(), "and eaten for good");
            assertEquals(10 + s.param("iron_heal"), s.health(s.owner), EPSILON, "one meal per interval");
            s.ticks(s.paramTicks("iron_heal_interval"));
            shoot(s, s.enemy2, s.owner, 5);
            assertEquals(10 + 2 * s.param("iron_heal"), s.health(s.owner), EPSILON, "the next interval feeds again");
            assertEquals(3, (int) peek(s.ability(), "swallowed"), "three swallowed so far");

            s.ctx().damage(s.enemy, s.owner, 2);
            assertEquals(2, s.damageTaken(s.owner), EPSILON, "abilities still hurt");
            s.clearHits();
            s.owner.damage(2, DamageSource.builder(DamageType.ON_FIRE).build());
            assertEquals(2 * (1 + s.param("iron_fire") / 100), s.damageTaken(s.owner), EPSILON,
                    "fire hurts twice as much");

            s.clearHits();
            // A stale velocity the server last sent the enemy must not add to the shrapnel's push.
            for (int i = 0; i < s.paramTicks("iron_duration") && s.damageBy(s.owner, s.enemy) <= 0; i++) {
                s.place(s.enemy, 0.5, 4.5, 180);
                s.enemy.setVelocity(new Vector(0.8, 0.9, 0.8));
                s.ticks(1);
            }
            double spit = Math.min(3 * s.param("spit_damage"), s.param("spit_max"));
            assertEquals(spit, s.damageBy(s.owner, s.enemy), EPSILON, "a point of shrapnel per swallowed blow");
            assertEquals(0, s.damageBy(s.owner, s.enemy2), EPSILON, "out of the shrapnel's reach");
            Vector push = s.enemy.getVelocity();
            assertTrue(push.getZ() > 0.5, "pushed away from the bulgasari: " + push);
            assertEquals(0, push.getX(), 0.05, "straight away, not along a stale velocity: " + push);
            s.ticks(30);
            assertNoDisplays(s, "after the iron body");
            s.finishClean();
        }
    }

    // ================================================================== 아귀 agwi

    /** Lets the ghost's watch learn everyone's health (after the test changed it). */
    private static void settle(Scenario s) {
        s.ticks(2);
    }

    @Test
    void agwiSnatchesAShareOfEveryHealAround() {
        try (Scenario s = Scenario.ffa("agwi")) {
            s.ticks(5);
            s.enemy.setHealth(50);
            s.owner.setHealth(10);
            settle(s);
            s.ctx().heal(s.enemy, 10);
            s.ticks(1);
            double share = s.param("tax_share") / 100;
            assertEquals(50 + 10 * (1 - share), s.health(s.enemy), EPSILON, "the enemy only keeps the rest");
            assertEquals(10 + 10 * share, s.health(s.owner), EPSILON, "the ghost eats the share");
            s.ticks(3);
            assertEquals(50 + 10 * (1 - share), s.health(s.enemy), EPSILON, "taken once");

            // Out of reach: the heal is the enemy's own.
            s.place(s.enemy2, 0.5, 0.5 + s.param("tax_radius") + 6, 180);
            s.enemy2.setHealth(50);
            settle(s);
            s.ctx().heal(s.enemy2, 10);
            s.ticks(2);
            assertEquals(60, s.health(s.enemy2), EPSILON, "far away nobody steals");
            s.finishClean();
        }
    }

    @Test
    void agwiCatchesVanillaHealsBeforeTheyLand() {
        try (Scenario s = Scenario.ffa("agwi")) {
            s.ticks(5);
            s.enemy.setHealth(50);
            s.owner.setHealth(10);
            settle(s);
            EntityRegainHealthEvent event = new EntityRegainHealthEvent(s.enemy, 10,
                    EntityRegainHealthEvent.RegainReason.SATIATED);
            s.h.server.getPluginManager().callEvent(event);
            double share = s.param("tax_share") / 100;
            assertFalse(event.isCancelled());
            assertEquals(10 * (1 - share), event.getAmount(), EPSILON, "the regeneration is cut down");
            // ... and lands like on a server.
            s.enemy.setHealth(s.enemy.getHealth() + event.getAmount());
            assertEquals(10 + 10 * share, s.health(s.owner), EPSILON, "the ghost eats the share");
            s.ticks(3);
            assertEquals(50 + 10 * (1 - share), s.health(s.enemy), EPSILON, "never taxed twice");
            assertEquals(10 + 10 * share, s.health(s.owner), EPSILON, "and never fed twice");
            s.finishClean();
        }
    }

    @Test
    void agwiNeverReachesForAHiddenEnemy() {
        try (Scenario s = Scenario.ffa("agwi")) {
            s.ticks(5);
            s.owner.hidePlayer(s.h.hub.plugin(), s.enemy);
            s.enemy.setHealth(50);
            s.owner.setHealth(10);
            settle(s);
            s.ctx().heal(s.enemy, 10);
            s.ticks(2);
            assertEquals(60, s.health(s.enemy), EPSILON, "an enemy the ghost cannot see heals in peace");
            assertEquals(10, s.health(s.owner), EPSILON);
            s.owner.showPlayer(s.h.hub.plugin(), s.enemy);
            s.finishClean();
        }
    }

    @Test
    void agwiBowlCatchesEveryHealAndFeedsTheGhost() {
        try (Scenario s = Scenario.ffa("agwi")) {
            s.ticks(5);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "동냥 그릇");
            s.h.tickUntil(() -> s.damageBy(s.owner, s.enemy) > 0, 20, "the bowl to hit");
            assertEquals(s.param("bowl_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "the bowl hits");
            s.ticks(1);
            assertEquals(0, (double) peek(s.ability(), "bowlHolds"), EPSILON, "an empty bowl hangs on");
            assertEquals(1, items(s, Material.BOWL).size());
            assertTrue(items(s, Material.BOWL).getFirst().getLocation().getY()
                    > s.enemy.getLocation().getY() + s.enemy.getHeight(), "over the enemy's head");
            s.enemy.setHealth(50);
            s.owner.setHealth(10);
            settle(s);
            s.ctx().heal(s.enemy, 4);
            s.ticks(1);
            assertEquals(50, s.health(s.enemy), EPSILON, "the victim heals nothing");
            assertEquals(4, (double) peek(s.ability(), "bowlHolds"), EPSILON, "it all went into the bowl");
            EntityRegainHealthEvent event = new EntityRegainHealthEvent(s.enemy, 3,
                    EntityRegainHealthEvent.RegainReason.SATIATED);
            s.h.server.getPluginManager().callEvent(event);
            assertTrue(event.isCancelled(), "vanilla regeneration goes into the bowl too");
            assertEquals(7, (double) peek(s.ability(), "bowlHolds"), EPSILON);
            assertEquals(10, s.health(s.owner), EPSILON, "the ghost waits for its bowl");
            s.ticks(s.paramTicks("bowl_duration") + 14);
            assertEquals(10 + Math.min(7, s.param("bowl_max")) + s.param("bowl_bonus"), s.health(s.owner), EPSILON,
                    "the bowl comes home with its meal (capped) and a little extra");
            assertTrue(items(s, Material.BOWL).isEmpty(), "the bowl is gone");
            s.finishClean();
        }
    }

    @Test
    void agwiEmptyBowlClonksTheHead() {
        try (Scenario s = Scenario.ffa("agwi")) {
            s.ticks(5);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "동냥 그릇");
            s.h.tickUntil(() -> s.damageBy(s.owner, s.enemy) > 0, 20, "the bowl to hit");
            s.clearHits();
            s.ticks(s.paramTicks("bowl_duration") + 8);
            assertEquals(s.param("bowl_bonk"), s.damageBy(s.owner, s.enemy), EPSILON, "땡그랑");
            s.ticks(20);
            assertTrue(items(s, Material.BOWL).isEmpty());
            s.finishClean();
        }
    }

    @Test
    void agwiBowlHidesWithAHiddenVictim() {
        try (Scenario s = Scenario.ffa("agwi")) {
            s.ticks(5);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "동냥 그릇");
            s.h.tickUntil(() -> s.damageBy(s.owner, s.enemy) > 0, 20, "the bowl to hit");
            s.ticks(4);
            s.owner.hidePlayer(s.h.hub.plugin(), s.enemy);
            s.ticks(3);
            assertTrue(items(s, Material.BOWL).getFirst().getTransformation().getScale().x() < 0.01,
                    "a bowl over a hidden enemy would give it away");
            s.owner.showPlayer(s.h.hub.plugin(), s.enemy);
            s.ticks(3);
            assertTrue(items(s, Material.BOWL).getFirst().getTransformation().getScale().x() > 0.3,
                    "and comes back with it");
            s.finishClean();
        }
    }

    @Test
    void agwiBowlMissRefundsHalfTheCooldown() {
        try (Scenario s = Scenario.ffa("agwi")) {
            s.ticks(5);
            s.place(s.owner, 0.5, 0.5, 180);
            s.clearHits();
            s.assertSucceeded(s.active(), "동냥 그릇");
            s.ticks(20);
            assertTrue(s.hits().isEmpty(), "nobody was hit");
            double expected = s.param("cooldown") * (1 - s.param("bowl_refund") / 100) - 20 / 20.0;
            assertEquals(expected, s.ctx().cooldownRemaining(s.owner), 0.2, "half the cooldown came back");
            s.finishClean();
        }
    }

    @Test
    void agwiRealmTurnsHealsIntoFireAndGnaws() {
        try (Scenario s = noGaugeGain(Scenario.builder("agwi")).start()) {
            s.ticks(5);
            s.place(s.enemy2, 0.5, 0.5 + s.param("realm_radius") + 6, 180);
            s.assertSucceeded(s.ultimate(), "아귀도");
            s.enemy.setHealth(50);
            s.enemy2.setHealth(50);
            s.owner.setHealth(10);
            settle(s);
            s.clearHits();
            s.ctx().heal(s.enemy, 4);
            s.ticks(2);
            assertEquals(4, s.hitsBy(s.owner, s.enemy).stream().mapToDouble(Scenario.Hit::damage)
                    .filter(d -> d > s.param("realm_damage") + EPSILON).sum(), EPSILON, "the heal burnt as much");
            assertTrue(s.health(s.enemy) < 46 + EPSILON + s.param("realm_damage"), "and never healed: "
                    + s.health(s.enemy));
            for (int i = 0; i < 3; i++) {
                s.ctx().heal(s.enemy, 4);
                s.ticks(2);
            }
            double burnt = s.hitsBy(s.owner, s.enemy).stream().mapToDouble(Scenario.Hit::damage)
                    .filter(d -> d > s.param("realm_damage") + EPSILON).sum();
            assertEquals(s.param("realm_burn_max"), burnt, EPSILON, "the burn stops at the cap");
            s.ctx().heal(s.enemy2, 4);
            s.ticks(1);
            assertEquals(54, s.health(s.enemy2), EPSILON, "outside the realm heals work");
            s.ticks(s.paramTicks("realm_interval"));
            assertTrue(modifierSum(s.enemy, Attribute.MOVEMENT_SPEED) < 0, "the realm slows its prisoners");
            assertEquals(0, modifierSum(s.enemy2, Attribute.MOVEMENT_SPEED), EPSILON, "and nobody outside");
            double before = s.health(s.owner);
            s.ticks(s.paramTicks("realm_interval") + 1);
            assertTrue(s.health(s.owner) > before, "the ghost is fed in its realm");
            s.ticks(s.paramTicks("realm_duration"));
            s.ticks(10);
            assertNoDisplays(s, "after the realm");
            assertModifierFree(s.enemy, "after the realm");
            s.clearHits();
            s.ctx().heal(s.enemy, 4);
            s.ticks(2);
            assertTrue(s.hitsBy(s.owner, s.enemy).isEmpty(), "the realm is gone");
            s.finishClean();
        }
    }

    @Test
    void agwiRealmNeedsAnEnemyInside() {
        try (Scenario s = Scenario.ffa("agwi")) {
            s.ticks(5);
            s.place(s.enemy, 0.5, 20.5, 180);
            s.place(s.enemy2, 6.5, 20.5, 180);
            assertFalse(s.ultimate().succeeded(), "nobody within the realm");
            assertEquals(100, s.participant(s.owner).gauge(), EPSILON, "the gauge is kept");
            s.finishClean();
        }
    }

    @Test
    void agwiBorrowedRealmStillBurnsHeals() {
        try (Scenario s = gambler()) {
            s.ticks(5);
            s.assertSucceeded(borrow(s, "agwi"), "borrowed 아귀도");
            s.enemy.setHealth(50);
            s.ticks(2);
            s.clearHits();
            s.ctx().heal(s.enemy, 4);
            s.ticks(3);
            assertTrue(s.health(s.enemy) < 50 + EPSILON, "a borrowed realm watches health too");
            assertTrue(s.damageBy(s.owner, s.enemy) >= 4 - EPSILON, "and burns the heal");
            s.ticks(200);
            s.finishClean();
        }
    }

    // ================================================================== 미다스 midas

    private static Set<EquipmentSlot> gilded(Scenario s, LivingEntity enemy) {
        return peek(s.ability(), "gildedSlots", enemy);
    }

    @Test
    void midasGildsArmourThatAnyoneCanCrack() {
        try (Scenario s = Scenario.teams("midas")) {
            s.ticks(5);
            s.melee(s.owner, s.enemy, 2);
            assertEquals(Set.of(EquipmentSlot.CHEST), gilded(s, s.enemy), "the chest turns to gold first");
            assertEquals(-1, modifierSum(s.enemy, Attribute.ARMOR), EPSILON, "an iron chestplate gilded: armour -1");
            s.clearHits();
            s.melee(s.ally(), s.enemy, 4);
            assertEquals(4 + s.param("shatter_bonus"), s.damageBy(s.ally(), s.enemy), EPSILON,
                    "the ally cracks the gold for extra damage");
            assertTrue(gilded(s, s.enemy).isEmpty(), "the piece cracked off");
            assertEquals(0, modifierSum(s.enemy, Attribute.ARMOR), EPSILON, "and the armour is iron again");
            s.ticks(s.paramTicks("shatter_cooldown") + 1);
            s.melee(s.owner, s.enemy, 2);
            s.clearHits();
            s.ticks(s.paramTicks("shatter_cooldown") + 1);
            s.melee(s.owner, s.enemy, 2);
            assertEquals(2 + s.param("shatter_bonus"), s.damageBy(s.owner, s.enemy), EPSILON,
                    "the king cracks his own gold too");
            assertEquals(Set.of(EquipmentSlot.CHEST), gilded(s, s.enemy), "and gilds the next piece at once");
            s.clearHits();
            s.ctx().damage(s.ally(), s.enemy, 1);
            assertEquals(1, s.damageBy(s.ally(), s.enemy), EPSILON, "only once per " + s.param("shatter_cooldown")
                    + " s");
            s.ticks(s.paramTicks("shatter_cooldown") + 1);
            s.clearHits();
            s.ctx().damage(s.ally(), s.enemy, 1);
            assertEquals(1 + s.param("shatter_bonus"), s.damageBy(s.ally(), s.enemy), EPSILON,
                    "with anything: an ability cracks it as well");
            s.finishClean();
        }
    }

    @Test
    void midasGoldIsEveryonesTargetInFreeForAll() {
        try (Scenario s = Scenario.ffa("midas")) {
            s.ticks(5);
            s.melee(s.owner, s.enemy, 2);
            s.clearHits();
            s.melee(s.enemy2, s.enemy, 3);
            assertEquals(3 + s.param("shatter_bonus"), s.damageBy(s.enemy2, s.enemy), EPSILON,
                    "another enemy cracks the gold too");
            assertTrue(gilded(s, s.enemy).isEmpty());
            s.finishClean();
        }
    }

    @Test
    void midasGoldFadesAfterItsTime() {
        try (Scenario s = Scenario.ffa("midas")) {
            s.ticks(5);
            s.melee(s.owner, s.enemy, 2);
            assertFalse(gilded(s, s.enemy).isEmpty());
            s.ticks(s.paramTicks("gild_duration") + 2);
            assertTrue(gilded(s, s.enemy).isEmpty(), "the gold wore off");
            assertModifierFree(s.enemy, "after the gold");
            s.clearHits();
            s.melee(s.enemy2, s.enemy, 3);
            assertEquals(3, s.damageBy(s.enemy2, s.enemy), EPSILON, "nothing left to crack");
            s.finishClean();
        }
    }

    private static List<BlockDisplay> coins(Scenario s) {
        return blocks(s, Material.GOLD_BLOCK).stream()
                .filter(d -> d.getTransformation().getScale().z() < 0.1).toList();
    }

    @Test
    void midasCoinsPlateTheArmourWithoutCrackingIt() {
        try (Scenario s = Scenario.ffa("midas")) {
            s.ticks(5);
            s.place(s.enemy, 0.5, 2.5, 180);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "금화 세례");
            s.ticks(20);
            List<Scenario.Hit> hits = s.hitsBy(s.owner, s.enemy);
            assertFalse(hits.isEmpty(), "coins hit");
            assertTrue(hits.size() <= s.param("coin_max_hits"), "at most " + s.param("coin_max_hits") + " coins hurt");
            for (Scenario.Hit hit : hits) {
                assertEquals(s.param("coin_damage"), hit.damage(), EPSILON, "a coin never cracks the gold");
            }
            assertEquals(Set.of(EquipmentSlot.CHEST, EquipmentSlot.LEGS), gilded(s, s.enemy),
                    "two pieces plated at most");
            assertEquals(-3, modifierSum(s.enemy, Attribute.ARMOR), EPSILON, "chest -1, legs -2");
            assertTrue(coins(s).isEmpty(), "the coins melted into the armour");
            s.finishClean();
        }
    }

    @Test
    void midasCoinsFlyFaceFirstAlongTheirPath() {
        try (Scenario s = Scenario.ffa("midas")) {
            s.ticks(5);
            s.place(s.owner, 0.5, 0.5, 90);
            s.place(s.enemy, -12.5, 0.5, -90);
            s.place(s.enemy2, -12.5, 6.5, -90);
            s.aimAt(s.owner, s.owner.getEyeLocation().add(-10, 0, 0));
            s.assertSucceeded(s.active(), "금화 세례");
            s.ticks(4);
            List<BlockDisplay> flying = coins(s);
            assertFalse(flying.isEmpty(), "coins in flight");
            boolean straight = false;
            for (BlockDisplay coin : flying) {
                assertEquals(0, coin.getLocation().getYaw(), 1.0E-3);
                Vector face = axis(coin, 0, 0, 1);
                Vector flight = coin.getLocation().toVector().subtract(s.owner.getEyeLocation().toVector())
                        .setY(0).normalize();
                assertTrue(face.dot(flight) > 0.9, "a coin faces along its flight: " + face + " vs " + flight);
                straight |= face.dot(facing(90)) > 0.99;
            }
            assertTrue(straight, "the middle coin flies straight west");
            s.ticks(30);
            s.finishClean();
        }
    }

    @Test
    void midasGoldenTouchGildsTheFloorCursesAndCollectsTheTax() {
        try (Scenario s = noGaugeGain(Scenario.builder("midas")).start()) {
            s.ticks(5);
            s.assertSucceeded(s.ultimate(), "황금 손길");
            s.owner.setHealth(10);
            s.ticks(2);
            s.ctx().heal(s.owner, 5);
            s.ticks(1);
            assertEquals(10, s.health(s.owner), EPSILON, "cursed: no healing");
            EntityRegainHealthEvent regen = new EntityRegainHealthEvent(s.owner, 2,
                    EntityRegainHealthEvent.RegainReason.SATIATED);
            s.h.server.getPluginManager().callEvent(regen);
            assertTrue(regen.isCancelled(), "not even regeneration");
            s.clearHits();
            s.ctx().damage(s.enemy, s.owner, 5);
            assertEquals(5 * (1 - s.param("touch_reduction") / 100), s.damageTaken(s.owner), EPSILON,
                    "the golden skin");
            s.owner.setHealth(10);
            s.ticks(s.paramTicks("touch_gild_interval") * 2);
            assertTrue(gilded(s, s.enemy).size() >= 2, "the enemy on the gold is gilded piece by piece");
            assertTrue(gilded(s, s.enemy2).isEmpty(), "the enemy beyond the gold is not");
            assertFalse(blocks(s, Material.GOLD_BLOCK).isEmpty(), "the golden floor");
            s.clearHits();
            s.h.tickUntil(() -> !s.hitsBy(s.owner, s.enemy).isEmpty(), s.paramTicks("touch_duration"), "the tax");
            assertEquals(Math.min(s.param("tax_max"), 4 * s.param("tax_damage")), s.damageBy(s.owner, s.enemy),
                    EPSILON, "every piece cracks at once");
            assertTrue(gilded(s, s.enemy).isEmpty(), "and the gold is gone");
            assertEquals(10 + 4 * s.param("tax_heal"), s.health(s.owner), EPSILON,
                    "the king pockets a little health per piece");
            assertEquals(0, s.damageBy(s.owner, s.enemy2), EPSILON, "the far enemy owed nothing");
            s.ticks(20);
            assertModifierFree(s.enemy, "after the tax");
            assertNoDisplays(s, "after the golden touch");
            s.ctx().heal(s.owner, 1);
            assertEquals(10 + 4 * s.param("tax_heal") + 1, s.health(s.owner), EPSILON, "the curse lifted");
            s.finishClean();
        }
    }

    // ================================================================== 왕지네 centipede

    private static List<BlockDisplay> segments(Scenario s) {
        return blocks(s, Material.NETHER_WART_BLOCK);
    }

    private static void sendEnemiesAway(Scenario s) {
        s.place(s.enemy, 12.5, 12.5, 180);
        s.place(s.enemy2, 14.5, 12.5, 180);
    }

    private static int wallCount(Scenario s) {
        return s.h.hub.tempBlocks().size();
    }

    @Test
    void centipedeBodyTrailsAlongTheFootstepsAndBitesWhatTouchesIt() {
        try (Scenario s = Scenario.ffa("centipede")) {
            s.ticks(5);
            sendEnemiesAway(s);
            assertEquals((int) s.param("segments"), segments(s).size(), "a body of segments");
            walk(s, s.owner, 6.5, 0.5, 0.25);
            s.ticks(2);
            for (BlockDisplay segment : segments(s)) {
                assertEquals(0, segment.getLocation().getYaw(), 1.0E-3);
                assertEquals(0, segment.getLocation().getPitch(), 1.0E-3);
                assertTrue(segment.getLocation().getX() < s.owner.getLocation().getX(), "behind the head");
                assertTrue(axis(segment, 0, 0, 1).dot(new Vector(1, 0, 0)) > 0.9,
                        "each segment points towards the head: " + axis(segment, 0, 0, 1));
            }
            BlockDisplay middle = segments(s).stream()
                    .min((a, b) -> Double.compare(Math.abs(a.getLocation().getX() - 4.4),
                            Math.abs(b.getLocation().getX() - 4.4))).orElseThrow();
            s.clearHits();
            s.place(s.enemy, middle.getLocation().getX(), middle.getLocation().getZ() + 0.55, 180);
            s.ticks(3);
            assertEquals(s.param("touch_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "the legs bite");
            assertTrue(s.enemy.hasPotionEffect(PotionEffectType.POISON), "poisonous legs");
            s.ticks(4);
            assertEquals(s.param("touch_damage"), s.damageBy(s.owner, s.enemy), EPSILON,
                    "once per " + s.param("touch_cooldown") + " s");
            s.ticks(s.paramTicks("touch_cooldown"));
            assertEquals(2 * s.param("touch_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "and again");
            s.finishClean();
        }
    }

    @Test
    void centipedeRoadRaisesWallsBehindItAndSkipsWhereSomeoneStands() {
        try (Scenario s = Scenario.ffa("centipede")) {
            s.ticks(5);
            s.place(s.enemy, 12.5, 12.5, 180);
            s.place(s.enemy2, 4.5, 0.5, 180);
            s.assertSucceeded(s.active(), "지네 길");
            long start = s.ctx().currentTick();
            assertTrue(modifierSum(s.owner, Attribute.MOVEMENT_SPEED) > 0, "the long body runs faster");
            walk(s, s.owner, 8.5, 0.5, 0.3);
            s.ticks(3);
            int height = (int) s.param("wall_height");
            for (int x = 0; x <= 7; x++) {
                Material bottom = s.world.getBlockAt(x, 64, 0).getType();
                if (x == 4) {
                    assertEquals(Material.AIR, bottom, "no wall where the enemy stands");
                    continue;
                }
                assertTrue(bottom == Material.NETHER_WART_BLOCK || bottom == Material.BLACKSTONE,
                        "a body wall at x=" + x + ": " + bottom);
                assertFalse(s.world.getBlockAt(x, 64 + height - 1, 0).getType().isAir(), "walls are "
                        + height + " high");
                assertEquals(Material.AIR, s.world.getBlockAt(x, 64 + height, 0).getType(), "and no higher");
            }
            assertEquals(Material.AIR, s.world.getBlockAt(8, 64, 0).getType(), "none where the centipede stands");
            assertTrue(Spots.isFree(s.world, s.enemy2.getLocation(), 0.6, 1.8), "the enemy is not walled in");
            s.ticks((int) (start + s.paramTicks("path_duration") + 2 - s.ctx().currentTick()));
            assertEquals(0, modifierSum(s.owner, Attribute.MOVEMENT_SPEED), EPSILON, "the speed runs out");
            int laid = wallCount(s);
            walk(s, s.owner, 8.5, 6.5, 0.3);
            s.ticks(3);
            assertEquals(laid, wallCount(s), "no more walls once the road is over");
            s.ticks(s.paramTicks("wall_duration") + 10);
            assertEquals(0, wallCount(s), "the walls sink back");
            s.finishClean();
        }
    }

    @Test
    void centipedeRoadStopsAtItsLength() {
        try (Scenario s = Scenario.builder("centipede").param("wall_max", 3).start()) {
            s.ticks(5);
            sendEnemiesAway(s);
            s.assertSucceeded(s.active(), "지네 길");
            walk(s, s.owner, 9.5, 0.5, 0.3);
            s.ticks(3);
            assertEquals(3 * (int) s.param("wall_height"), wallCount(s), "three cells of wall at most");
            s.finishClean();
        }
    }

    @Test
    void centipedeWallBitesWhoeverRunsIntoIt() {
        try (Scenario s = Scenario.ffa("centipede")) {
            s.ticks(5);
            sendEnemiesAway(s);
            s.assertSucceeded(s.active(), "지네 길");
            walk(s, s.owner, 8.5, 0.5, 0.3);
            s.ticks(3);
            s.clearHits();
            // Pressed against the wall's side (touching, not inside).
            s.place(s.enemy, 3.5, 1.36, 180);
            s.ticks(2);
            assertEquals(s.param("bump_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "a bite from the wall");
            assertTrue(s.enemy.hasPotionEffect(PotionEffectType.POISON));
            s.ticks(5);
            assertEquals(s.param("bump_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "once per second");
            s.ticks(s.paramTicks("bump_cooldown"));
            assertEquals(2 * s.param("bump_damage"), s.damageBy(s.owner, s.enemy), EPSILON);
            assertFalse(s.world.getBlockAt(3, 64, 0).getType().isAir(), "the wall stands");
            s.finishClean();
        }
    }

    @Test
    void centipedeWallOpensForSomeoneCaughtInsideIt() {
        try (Scenario s = Scenario.ffa("centipede")) {
            s.ticks(5);
            sendEnemiesAway(s);
            s.assertSucceeded(s.active(), "지네 길");
            walk(s, s.owner, 8.5, 0.5, 0.3);
            s.ticks(3);
            assertFalse(s.world.getBlockAt(5, 64, 0).getType().isAir());
            // A lagging client was already there when the wall rose.
            s.place(s.enemy, 5.5, 0.5, 180);
            s.ticks(1);
            assertTrue(s.world.getBlockAt(5, 64, 0).getType().isAir(), "nobody is ever stuck in a wall");
            assertTrue(s.world.getBlockAt(5, 65, 0).getType().isAir());
            s.finishClean();
        }
    }

    @Test
    void centipedeKingRingSqueezesThenOpensAGap() {
        try (Scenario s = noGaugeGain(Scenario.builder("centipede")).start()) {
            s.ticks(5);
            s.assertSucceeded(s.ultimate(), "천 년 묵은 왕지네");
            assertEquals(s.param("ult_scale") / 100, modifierSum(s.owner, Attribute.SCALE), EPSILON, "1.3 times");
            assertTrue(modifierSum(s.owner, Attribute.MOVEMENT_SPEED) > 0, "and faster");
            s.clearHits();
            ringAround(s, s.enemy);
            int closed = peek(s.ability(), "wallBlocks");
            assertFalse(s.world.getBlockAt(-2, 66, 4).getType().isAir(), "the king's walls are 3 high");
            s.h.tickUntil(() -> s.damageBy(s.owner, s.enemy) > 0, 40, "the ring to squeeze");
            assertEquals(s.param("ring_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "squeezed");
            assertTrue(s.enemy.hasPotionEffect(PotionEffectType.POISON), "and poisoned");
            s.ticks(s.paramTicks("ring_gap") + 2);
            int open = peek(s.ability(), "wallBlocks");
            assertEquals(closed - (int) s.param("ult_wall_height"), open, "a segment caved in");
            double squeezed = s.damageBy(s.owner, s.enemy);
            s.ticks(60);
            assertEquals(squeezed, s.damageBy(s.owner, s.enemy), EPSILON, "out of the ring, nothing squeezes");
            assertTrue(squeezed <= s.param("ult_damage_max") + EPSILON);
            s.ticks(300);
            assertEquals(0, wallCount(s));
            assertModifierFree(s.owner, "after the king");
            s.finishClean();
        }
    }

    @Test
    void centipedeKingThrowsBackWhoRunsIntoItsBody() {
        try (Scenario s = noGaugeGain(Scenario.builder("centipede")).start()) {
            s.ticks(5);
            sendEnemiesAway(s);
            s.assertSucceeded(s.ultimate(), "천 년 묵은 왕지네");
            walk(s, s.owner, 8.5, 0.5, 0.3);
            s.ticks(3);
            s.clearHits();
            s.place(s.enemy, 3.5, 1.36, 180);
            // A stale velocity the server last sent the enemy must not add to the throw.
            s.enemy.setVelocity(new Vector(0.9, 0.9, -0.9));
            s.h.tickUntil(() -> s.damageBy(s.owner, s.enemy) > 0, 3, "a bump");
            assertEquals(s.param("ult_bump_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "a bump");
            Vector thrown = s.enemy.getVelocity();
            assertTrue(thrown.getZ() > 0.3, "thrown away from the wall: " + thrown);
            assertEquals(0, thrown.getX(), 0.05, "straight back: " + thrown);
            for (int i = 0; i < 4; i++) {
                s.ticks(12);
                s.place(s.enemy, 3.5, 1.36, 180);
                s.ticks(1);
            }
            assertEquals(s.param("ult_bump_damage") * s.param("ult_bump_max"), s.damageBy(s.owner, s.enemy), EPSILON,
                    "at most " + s.param("ult_bump_max") + " bumps per enemy");
            s.finishClean();
        }
    }

    // ================================================================== 도철 taotie

    private static @Nullable LivingEntity mask(Scenario s) {
        return peek(s.ability(), "maskOf");
    }

    private static List<BlockDisplay> maskParts(Scenario s) {
        return blocks(s, Material.WAXED_OXIDIZED_COPPER);
    }

    /** The taotie bites the aimed enemy (4 blocks ahead) with its active; the enemy is tasted. */
    private static void taste(Scenario s) {
        s.aimAt(s.owner, s.enemy);
        s.assertSucceeded(s.active(), "한입 맛보기");
        s.h.tickUntil(() -> s.damageBy(s.owner, s.enemy) > 0, 25, "the bite");
        s.ticks(12);
    }

    @Test
    void taotieBitesTheFirstEnemyItSucksInAndTastesIt() {
        try (Scenario s = Scenario.ffa("taotie")) {
            s.ticks(5);
            s.place(s.enemy2, 8.5, 0.5, 180);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "한입 맛보기");
            assertTrue(modifierSum(s.owner, Attribute.MOVEMENT_SPEED) < 0, "the open maw slows the taotie");
            double start = s.enemy.getLocation().getZ();
            s.h.tickUntil(() -> s.damageBy(s.owner, s.enemy) > 0, 25, "the bite");
            assertTrue(s.enemy.getLocation().getZ() < start - 1, "pulled in first");
            assertEquals(s.param("bite_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "the bite");
            Vector spat = s.enemy.getVelocity();
            assertTrue(spat.getZ() < -0.5 && spat.getY() > 0.5, "spat out behind: " + spat);
            assertEquals(s.enemy, mask(s), "the bitten enemy's face is the mask");
            s.ticks(12);
            assertEquals(1, maskParts(s).size(), "the bronze mask is on");
            assertFalse(s.owner.canSee(maskParts(s).getFirst()), "the taotie itself does not see its mask");
            assertTrue(s.enemy.canSee(maskParts(s).getFirst()), "everyone else does");
            assertEquals(1, entities(s, TextDisplay.class).size(), "the tasted name over the taotie");
            s.ticks(s.paramTicks("mask_duration"));
            assertNull(mask(s), "the mask wears off");
            s.ticks(10);
            assertTrue(maskParts(s).isEmpty());
            s.finishClean();
        }
    }

    @Test
    void taotieKnowsTheTasteOfItsMask() {
        try (Scenario s = Scenario.ffa("taotie")) {
            s.ticks(5);
            taste(s);
            s.clearHits();
            s.melee(s.owner, s.enemy, 4);
            s.melee(s.owner, s.enemy2, 4);
            assertEquals(4 * (1 + s.param("mask_bonus") / 100), s.damageBy(s.owner, s.enemy), EPSILON, "the taste");
            assertEquals(4, s.damageBy(s.owner, s.enemy2), EPSILON, "nobody else");
            s.finishClean();
        }
    }

    @Test
    void taotieMaskFollowsTheHead() {
        try (Scenario s = Scenario.ffa("taotie")) {
            s.ticks(5);
            taste(s);
            s.place(s.owner, 0.5, 0.5, 90);
            s.ticks(2);
            BlockDisplay plate = maskParts(s).getFirst();
            assertEquals(0, plate.getLocation().getYaw(), 1.0E-3);
            assertTrue(axis(plate, 0, 0, 1).dot(facing(90)) > 0.98, "facing west with the head: "
                    + axis(plate, 0, 0, 1));
            assertTrue(plate.getLocation().distance(s.owner.getEyeLocation()) < 0.05, "on the face");
            s.place(s.owner, 0.5, 0.5, 0);
            s.ticks(2);
            assertTrue(axis(plate, 0, 0, 1).dot(facing(0)) > 0.98, "and south again: " + axis(plate, 0, 0, 1));
            s.finishClean();
        }
    }

    @Test
    void taotieSwallowsShotsFromAfarWhileItsMawIsOpen() {
        try (Scenario s = Scenario.ffa("taotie")) {
            s.ticks(5);
            s.place(s.enemy, 0.5, 9.5, 180);
            s.place(s.enemy2, 0.5, -8.5, 0);
            s.place(s.owner, 0.5, 0.5, 0);
            s.assertSucceeded(s.active(), "한입 맛보기");
            s.ticks(2);
            s.clearHits();
            shoot(s, s.enemy, s.owner, 5);
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "an arrow from afar flies into the mouth");
            s.ctx().damage(s.enemy, s.owner, 3);
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "a spell from afar too");
            shoot(s, s.enemy2, s.owner, 4);
            assertEquals(4, s.damageTaken(s.owner), EPSILON, "a shot from behind is not in front of the mouth");
            s.ticks(s.paramTicks("gulp_duration"));
            s.clearHits();
            shoot(s, s.enemy, s.owner, 5);
            assertEquals(5, s.damageTaken(s.owner), EPSILON, "the mouth is shut again");
            double expected = s.param("cooldown") * s.param("miss_cooldown") / 100
                    - (s.paramTicks("gulp_duration") + 2) / 20.0;
            assertEquals(expected, s.ctx().cooldownRemaining(s.owner), 0.2, "nobody bitten: half the cooldown");
            s.finishClean();
        }
    }

    @Test
    void taotieCopiesTheUltimateOfTheFaceItWears() {
        try (Scenario s = noGaugeGain(Scenario.builder("taotie").enemyAbility("thunder")).start()) {
            s.ticks(5);
            taste(s);
            assertEquals(s.enemy, mask(s));
            AbilityLifecycleTest.prepareUltimate(s);
            s.healAll();
            s.clearHits();
            s.assertSucceeded(s.ultimate(), "the copied ultimate");
            assertNull(mask(s), "the mask is used up");
            s.ticks(100);
            assertTrue(s.damageBy(s.owner, s.enemy) > 0, "the thunder strikes the enemy, credited to the taotie");
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "never the taotie");
            assertTrue(blocks(s, Material.WAXED_CUT_COPPER).isEmpty(), "no maw: it was the thunder");
            s.ticks(100);
            s.finishClean();
        }
    }

    @Test
    void taotieSpitsOutATastelessMaskAndGetsSomeGaugeBack() {
        try (Scenario s = noGaugeGain(Scenario.builder("taotie")).start()) {
            s.ticks(5);
            taste(s);
            assertEquals(s.enemy, mask(s), "a dummy's face: its ultimate cannot be copied");
            s.place(s.enemy, 0.5, 4.5, 180);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "맛이 없다");
            s.ticks(2);
            assertEquals(s.param("tasteless_refund"), s.participant(s.owner).gauge(), EPSILON, "a quarter back");
            assertFalse(blocks(s, Material.WAXED_CUT_COPPER).isEmpty(), "the maw of the four perils instead");
            s.ticks(100);
            s.finishClean();
        }
    }

    @Test
    void taotieGreatMawSwallowsDigestsAndSpitsOut() {
        NamespacedKey absorb = new NamespacedKey("test", "absorb");
        try (Scenario s = noGaugeGain(Scenario.builder("taotie")).start()) {
            s.ticks(5);
            s.place(s.enemy2, 14.5, 0.5, 180);
            s.enemy.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 400, 1));
            s.enemy.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 400, 0));
            s.enemy.getAttribute(Attribute.MAX_ABSORPTION).addModifier(new AttributeModifier(absorb, 10,
                    AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.ANY));
            s.enemy.setAbsorptionAmount(6);
            s.match.setGauge(s.enemy, 50);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "사흉의 아가리");
            s.h.tickUntil(() -> !s.owner.canSee(s.enemy), 40, "the enemy to be swallowed");
            assertFalse(s.enemy2.canSee(s.enemy), "swallowed: nobody sees it");
            assertTrue(rooted(s.enemy), "it cannot move");
            assertTrue(s.ctx().isSilenced(s.enemy), "or use its skills");
            s.clearHits();
            s.melee(s.enemy, s.owner, 3);
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "or hit anyone from inside the belly");
            // Without world physics the spot it was spat out at stays put (the spit's launch would move it at once).
            s.world.physics().setEnabled(false);
            s.h.tickUntil(() -> s.owner.canSee(s.enemy), 40, "the enemy to be spat out");
            assertStandsOnFloor(s.enemy, "spat out of the mouth");
            assertTrue(s.enemy.getLocation().distance(s.owner.getLocation()) < 3, "right in front of the mouth");
            s.world.physics().setEnabled(true);
            assertTrue(s.enemy2.canSee(s.enemy), "everyone sees it again");
            assertFalse(s.enemy.hasPotionEffect(PotionEffectType.SPEED), "its speed melted away");
            assertTrue(s.enemy.hasPotionEffect(PotionEffectType.SLOWNESS), "its troubles did not");
            assertEquals(0, s.enemy.getAbsorptionAmount(), EPSILON, "its absorption melted away");
            assertEquals(s.param("digest_absorb_max"), s.owner.getAbsorptionAmount(), EPSILON,
                    "and fed the taotie (capped)");
            assertEquals(50 - s.param("digest_gauge"), s.participant(s.enemy).gauge(), EPSILON, "gauge eaten");
            assertEquals(s.param("spit_damage") * (1 + s.param("mask_bonus") / 100), s.damageBy(s.owner, s.enemy),
                    EPSILON, "the spit (the enemy is tasted by now: the taotie knows its taste)");
            assertTrue(s.enemy.hasPotionEffect(PotionEffectType.GLOWING), "covered in slobber");
            assertTrue(modifierSum(s.enemy, Attribute.MOVEMENT_SPEED) < 0, "slowed by it");
            assertEquals(s.enemy, mask(s), "the first one swallowed is tasted");
            s.ticks(s.paramTicks("slobber_duration") + 5);
            assertEquals(0, modifierSum(s.enemy, Attribute.MOVEMENT_SPEED), EPSILON, "the slobber dries");
            s.ticks(s.paramTicks("digest_absorb_duration") + 5);
            assertEquals(0, s.owner.getAbsorptionAmount(), EPSILON, "the eaten absorption is gone in time");
            s.enemy.getAttribute(Attribute.MAX_ABSORPTION).removeModifier(absorb);
            s.finishClean();
        }
    }

    @Test
    void taotieGreatMawNeedsPreyInFront() {
        try (Scenario s = Scenario.ffa("taotie")) {
            s.ticks(5);
            s.place(s.owner, 0.5, 0.5, 180);
            assertFalse(s.ultimate().succeeded(), "nobody in front");
            assertEquals(100, s.participant(s.owner).gauge(), EPSILON, "the gauge is kept");
            s.finishClean();
        }
    }

    @Test
    void taotieUltimateDuringTheBiteOpensTheGreatMaw() {
        try (Scenario s = Scenario.ffa("taotie")) {
            s.ticks(5);
            s.place(s.enemy, 0.5, 8.5, 180);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "한입 맛보기");
            s.ticks(3);
            s.assertSucceeded(s.ultimate(), "the small mouth gives way to the great one");
            s.ticks(100);
            assertTrue(s.damageBy(s.owner, s.enemy) > 0);
            s.finishClean();
        }
    }

    @Test
    void taotieBorrowedMawTastesNobody() {
        try (Scenario s = gambler()) {
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(borrow(s, "taotie"), "borrowed 사흉의 아가리");
            s.ticks(100);
            assertTrue(maskParts(s).isEmpty(), "no mask for a gambler");
            assertTrue(s.damageBy(s.owner, s.enemy) > 0);
            s.finishClean();
        }
    }

    // ================================================================== more real-server rules

    /** The middle of a block display's (rotated, scaled) cube in the world. */
    private static Vector center(BlockDisplay display) {
        Transformation t = display.getTransformation();
        Vector3f half = new Vector3f(t.getScale()).mul(0.5f);
        Vector3f middle = leftRotation(display).transform(half).add(t.getTranslation());
        return display.getLocation().toVector().add(new Vector(middle.x(), middle.y(), middle.z()));
    }

    @Test
    void bulgasariPlatesTurnWithTheBody() {
        try (Scenario s = Scenario.ffa("bulgasari")) {
            s.ticks(5);
            s.place(s.owner, 0.5, 0.5, 90);
            s.owner.setBodyYaw(90);
            s.assertSucceeded(s.ultimate(), "불가살이");
            s.ticks(6);
            List<BlockDisplay> plates = blocks(s, Material.IRON_BLOCK);
            assertFalse(plates.isEmpty());
            BlockDisplay front = plates.stream().filter(p -> !s.owner.canSee(p)).findFirst().orElseThrow();
            Vector offset = center(front).subtract(s.owner.getLocation().toVector()).setY(0);
            assertTrue(offset.normalize().dot(facing(90)) > 0.95, "the front plate is in front (west): " + offset);
            for (BlockDisplay plate : plates) {
                assertEquals(0, plate.getLocation().getYaw(), 1.0E-3);
                assertEquals(0, plate.getLocation().getPitch(), 1.0E-3);
            }
            s.ticks(140);
            s.finishClean();
        }
    }

    @Test
    void taotieMawOpensWhereTheTaotieLooks() {
        try (Scenario s = Scenario.ffa("taotie")) {
            s.ticks(5);
            s.place(s.owner, 0.5, 0.5, 90);
            s.place(s.enemy, -3.5, 0.5, -90);
            s.place(s.enemy2, -3.5, 6.5, -90);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "사흉의 아가리");
            s.ticks(6);
            List<BlockDisplay> jaws = blocks(s, Material.WAXED_CUT_COPPER);
            assertEquals(2, jaws.size(), "an upper and a lower jaw");
            for (BlockDisplay jaw : jaws) {
                assertEquals(0, jaw.getLocation().getYaw(), 1.0E-3);
                assertFalse(s.owner.canSee(jaw), "the maw does not block the taotie's own view");
                Vector depth = axis(jaw, 0, 0, 1).setY(0).normalize();
                assertTrue(depth.dot(facing(90)) > 0.95, "the jaw reaches west, where the taotie looks: " + depth);
                assertTrue(center(jaw).getX() < s.owner.getLocation().getX() - 0.5, "in front of the taotie");
            }
            Vector upper = axis(jaws.stream().max((a, b) -> Double.compare(center(a).getY(), center(b).getY()))
                    .orElseThrow(), 0, 0, 1);
            assertTrue(upper.getY() > 0.3, "the upper jaw opens upwards: " + upper);
            s.ticks(100);
            s.finishClean();
        }
    }

    @Test
    void taotieLetsOutAnEnemyThatFallsInsideItsBelly() {
        try (Scenario s = noGaugeGain(Scenario.builder("taotie")).start()) {
            s.ticks(5);
            s.place(s.enemy2, 14.5, 0.5, 180);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "사흉의 아가리");
            s.h.tickUntil(() -> !s.owner.canSee(s.enemy), 40, "the enemy to be swallowed");
            s.enemy.setNoDamageTicks(0);
            s.enemy.damage(10_000, DamageSource.builder(DamageType.GENERIC_KILL).build());
            assertFalse(s.session.isAlive(s.enemy), "it fell inside the belly");
            s.respawn(s.enemy);
            s.ticks(30);
            for (TestPlayer viewer : s.all()) {
                assertTrue(viewer.canSee(s.enemy), viewer.getName() + " sees the fallen enemy again");
            }
            s.assertNoProblems();
            s.ticks(60);
            s.finishClean();
        }
    }

    @Test
    void agwiBowlComesHomeWhenItsVictimFalls() {
        try (Scenario s = Scenario.ffa("agwi")) {
            s.ticks(5);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "동냥 그릇");
            s.h.tickUntil(() -> s.damageBy(s.owner, s.enemy) > 0, 20, "the bowl to hit");
            s.ticks(2);
            s.enemy.setHealth(50);
            s.ticks(2);
            s.ctx().heal(s.enemy, 3);
            s.ticks(2);
            s.owner.setHealth(10);
            s.session.eliminate(s.enemy, s.owner);
            s.ticks(20);
            assertEquals(10 + 3 + s.param("bowl_bonus"), s.health(s.owner), EPSILON,
                    "the bowl flew home with what it had");
            assertTrue(items(s, Material.BOWL).isEmpty());
            s.assertNoProblems();
        }
    }

    @Test
    void midasNeverGildsAllies() {
        try (Scenario s = Scenario.teams("midas")) {
            s.ticks(5);
            s.crossfireAlly();
            s.place(s.enemy, 0.5, 2.5, 180);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "금화 세례");
            s.ticks(20);
            assertTrue(gilded(s, s.ally()).isEmpty(), "the coins pass the ally by");
            assertModifierFree(s.ally(), "the ally's armour");
            s.finishClean();
        }
    }
}
