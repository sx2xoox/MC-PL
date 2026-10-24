package io.github.sx2xoox.mcpl.game.superpower.scenario;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import io.github.sx2xoox.mcpl.game.superpower.abilities.BansheeAbility;
import io.github.sx2xoox.mcpl.game.superpower.abilities.CassandraAbility;
import io.github.sx2xoox.mcpl.game.superpower.abilities.CharonAbility;
import io.github.sx2xoox.mcpl.game.superpower.abilities.CurseNoteAbility;
import io.github.sx2xoox.mcpl.game.superpower.abilities.YeomraAbility;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Spots;
import io.github.sx2xoox.mcpl.game.superpower.ability.Ability;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import io.github.sx2xoox.mcpl.game.superpower.ability.ActivationResult;
import io.github.sx2xoox.mcpl.game.superpower.support.Scenario;
import io.github.sx2xoox.mcpl.game.superpower.support.TestPlayer;
import io.github.sx2xoox.mcpl.game.superpower.support.world.FlatWorld;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Wolf;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 죽음 · 예언 (새 능력): 카산드라, 밴시, 빨간 글씨, 카론, 염라대왕. Every ability through a whole match, the owner dying in the
 * middle of each skill, team mode with an ally in the line of fire, the standalone ultimates borrowed by a Gambler, and
 * each ability's own mechanics: prophecies that fall where an enemy is going and fate for those who cheat them; the
 * keening, the shroud's healing ceiling and the race of the doomed; names learned in a fight, written in red and torn
 * by finding the writer; the ghost river, its current, its hands and its fare; the karma mirror's rewind along an
 * enemy's own steps and the reapers that never lose their way.
 */
class OmenNewAbilitiesTest {

    private static final double EPSILON = 1.0E-6;
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z0-9_]+)}");
    private static final double BASE_SPEED = 0.1;

    static Stream<String> abilities() {
        return Stream.of("cassandra", "banshee", "cursenote", "charon", "yeomra");
    }

    /** The abilities whose ultimate the Gambler may borrow. */
    static Stream<String> standalone() {
        return Stream.of("cassandra", "banshee", "charon", "yeomra");
    }

    /**
     * {@link AbilityLifecycleTest} reads the ability registry in its static initializer, which starts a mock server of
     * its own. The first use of its helpers below would happen inside a running scenario ("Already mocking") and break
     * that class for the whole test JVM, so it is initialized here, before any scenario runs.
     */
    @BeforeAll
    static void initializeSharedSweeps() {
        assertTrue(AbilityLifecycleTest.abilities().findAny().isPresent());
    }

    // ------------------------------------------------------------------ helpers

    private static <T extends Entity> List<T> entities(Scenario s, Class<T> type) {
        return s.world.getEntities().stream().filter(type::isInstance).map(type::cast).toList();
    }

    private static void assertNoDisplays(Scenario s, String what) {
        assertTrue(entities(s, Display.class).isEmpty(), what + ": every display is gone: "
                + entities(s, Display.class).stream().map(d -> d.getType() + "@" + d.getLocation().toVector()).toList());
    }

    private static List<BlockDisplay> blockDisplays(Scenario s, Material material) {
        return entities(s, BlockDisplay.class).stream().filter(d -> d.getBlock().getMaterial() == material).toList();
    }

    private static List<ItemDisplay> itemDisplays(Scenario s, Material material) {
        return entities(s, ItemDisplay.class).stream()
                .filter(d -> d.getItemStack() != null && d.getItemStack().getType() == material).toList();
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

    private static boolean rooted(LivingEntity entity) {
        return entity.getAttribute(Attribute.MOVEMENT_SPEED).getModifiers().stream()
                .anyMatch(modifier -> modifier.getAmount() <= -1.0 + EPSILON)
                && entity.getAttribute(Attribute.JUMP_STRENGTH).getModifiers().stream()
                .anyMatch(modifier -> modifier.getAmount() <= -1.0 + EPSILON);
    }

    private static boolean slowed(LivingEntity entity) {
        return entity.getAttribute(Attribute.MOVEMENT_SPEED).getModifiers().stream()
                .anyMatch(modifier -> modifier.getAmount() < 0);
    }

    private static double speedBonus(LivingEntity entity) {
        return entity.getAttribute(Attribute.MOVEMENT_SPEED).getModifiers().stream()
                .mapToDouble(modifier -> modifier.getAmount()).sum();
    }

    /** Hits by the owner's ability (MAGIC), ignoring melee and other uncredited damage. */
    private static double magicDamage(Scenario s, Entity victim) {
        return s.hitsBy(s.owner, victim).stream().filter(hit -> hit.cause() == DamageCause.MAGIC)
                .mapToDouble(Scenario.Hit::damage).sum();
    }

    /**
     * Displays orient through their transformation only: an entity rotation would turn the model a second time on a
     * real client.
     */
    private static void assertUnrotated(Display display, String what) {
        assertEquals(0, display.getLocation().getYaw(), 1.0E-6, what + ": display yaw");
        assertEquals(0, display.getLocation().getPitch(), 1.0E-6, what + ": display pitch");
    }

    private static void assertAllUnrotated(Scenario s, String what) {
        for (Display display : entities(s, Display.class)) {
            assertUnrotated(display, what + " " + display.getType());
        }
    }

    /** Where a direction of a display's model points in the world (its own rotation is 0, see above). */
    private static Vector3f modelDirection(Display display, float x, float y, float z) {
        Transformation t = display.getTransformation();
        return new Quaternionf(t.getLeftRotation()).transform(new Vector3f(x, y, z)).normalize();
    }

    /** Feet exactly on the floor and the body clear of every block. */
    private static void assertOnSurface(Player player, String what) {
        Location at = player.getLocation();
        assertEquals(FlatWorld.SURFACE, at.getY(), 1.0E-6, what + ": feet on the surface " + at);
        assertTrue(Spots.isFree(at.getWorld(), at, player.getWidth(), player.getHeight()),
                what + ": the body is not inside a block " + at);
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

    /** One step of a walk: {@code player} moves by (dx, dz) from where it stands, then a tick passes. */
    private static void step(Scenario s, Player player, double dx, double dz) {
        Location at = player.getLocation();
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        s.place(player, at.getX() + dx, at.getZ() + dz, yaw);
        s.ticks(1);
    }

    /** Every action bar {@code player} was sent since the last call, as plain text. */
    private static List<String> actionBars(TestPlayer player) {
        List<String> bars = new ArrayList<>();
        Component bar;
        while ((bar = player.nextActionBar()) != null) {
            bars.add(Scenario.plain(bar));
        }
        return bars;
    }

    /**
     * The first enemy calls its wolf pack (it has the summoner's ability): more enemies than the two players, standing
     * where they are put (mobs have no AI in the test world).
     */
    private static List<Wolf> callWolves(Scenario s, double[][] spots) {
        s.assertSucceeded(s.match.useActive(s.enemy), "늑대 무리");
        s.ticks(2);
        List<Wolf> wolves = entities(s, Wolf.class);
        assertEquals(spots.length, wolves.size(), "the pack");
        for (int i = 0; i < spots.length; i++) {
            wolves.get(i).teleport(new Location(s.world, spots[i][0], FlatWorld.SURFACE, spots[i][1]));
        }
        return wolves;
    }

    /** Digs (or with STONE fills back) a pit through the floor, 6 blocks deep, over x/z ranges (inclusive). */
    private static void pit(Scenario s, int minX, int maxX, int minZ, int maxZ, Material material) {
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = FlatWorld.FLOOR_Y - 5; y <= FlatWorld.FLOOR_Y; y++) {
                    s.world.setBlock(x, y, z, material);
                }
            }
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
        s.finishMatch();
        s.assertNoProblems();
        s.assertCleanWorld();
    }

    // ================================================================== every ability

    @Test
    void descriptionsOnlyReferenceDeclaredParams() {
        for (AbilityInfo info : List.of(CassandraAbility.INFO, BansheeAbility.INFO, CurseNoteAbility.INFO,
                CharonAbility.INFO, YeomraAbility.INFO)) {
            for (AbilityInfo.Skill skill : List.of(info.passive(), info.active(), info.ultimate())) {
                Matcher matcher = PLACEHOLDER.matcher(skill.description());
                while (matcher.find()) {
                    assertTrue(info.params().containsKey(matcher.group(1)),
                            info.id() + " " + skill.name() + " uses an undeclared param " + matcher.group());
                }
                assertFalse(skill.name().contains("TODO") || skill.description().contains("TODO"), info.id());
            }
        }
        assertTrue(CassandraAbility.INFO.ultimateStandalone());
        assertTrue(BansheeAbility.INFO.ultimateStandalone());
        assertFalse(CurseNoteAbility.INFO.ultimateStandalone(), "the underworld sight changes the active's writing");
        assertTrue(CharonAbility.INFO.ultimateStandalone());
        assertTrue(YeomraAbility.INFO.ultimateStandalone());
        assertEquals(3, CassandraAbility.INFO.difficulty());
    }

    /** What each active needs before it can be used on the aimed enemy: the red-ink note only writes known names. */
    private static void prepare(Scenario s) {
        s.aimAt(s.owner, s.enemy);
        if (s.abilityId.equals("cursenote")) {
            s.melee(s.owner, s.enemy, 1);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("abilities")
    void wholeMatch(String id) {
        try (Scenario s = noGaugeGain(Scenario.builder(id)).start()) {
            s.ticks(60);
            s.assertNoProblems();

            prepare(s);
            s.clearHits();
            s.assertSucceeded(s.active(), id + " active");
            s.ticks(100);
            assertTrue(magicDamage(s, s.enemy) > 0, id + " active hurts the aimed enemy");
            assertEquals(0, s.damageTaken(s.owner), EPSILON, id + " active never hurts its owner: " + s.hits());
            assertAllUnrotated(s, id);

            AbilityLifecycleTest.prepareUltimate(s);
            s.healAll();
            s.clearHits();
            s.assertSucceeded(s.ultimate(), id + " ultimate");
            if (id.equals("cursenote")) {
                // The underworld sight hurts nobody by itself: it writes faster.
                s.ticks(2);
                s.assertSucceeded(s.active(), "cursenote writes under the sight");
            }
            for (int i = 0; i < 300; i++) {
                s.ticks(1);
                if (i % 10 == 0) {
                    assertAllUnrotated(s, id + " ultimate");
                    assertTrue(entities(s, Display.class).size() <= 24, id + " keeps its displays in budget");
                }
            }
            assertTrue(magicDamage(s, s.enemy) > 0, id + " ultimate hurts the aimed enemy");
            assertEquals(0, s.damageTaken(s.owner), EPSILON, id + " ultimate never hurts its owner: " + s.hits());
            s.assertNoProblems();
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
            // The spear is falling, the shroud has landed, the countdown runs, the river flows, the mirror pulls.
            s.ticks(16);
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
            s.ticks(24);
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
            assertFalse(s.ally().hasPotionEffect(PotionEffectType.GLOWING), "the ally is never marked");
            s.assertNoProblems();

            s.finishMatch();
            s.assertNoProblems();
            s.assertCleanWorld();
        }
    }

    // ================================================================== borrowed by the gambler

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
                assertFalse(s.ctx().isSilenced(target), id + ": the silence wore off");
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
            for (TestPlayer target : s.enemies()) {
                assertModifierFree(target, id);
            }
            s.assertNoProblems();
            s.finishClean();
        }
    }

    // ================================================================== real-server rules

    @Test
    void displaysCarryNoRotationOfTheirOwnWhateverWayTheOwnerLooks() {
        for (String id : abilities().toList()) {
            try (Scenario s = noGaugeGain(Scenario.builder(id)).start()) {
                s.place(s.enemy, -3.5, 3.5, 135);
                s.place(s.enemy2, 4.5, 5.5, -120);
                s.place(s.owner, 0.5, FlatWorld.SURFACE, 0.5, -37, 12);
                s.aimAt(s.owner, s.enemy);
                if (id.equals("cursenote")) {
                    s.melee(s.owner, s.enemy, 1);
                }
                s.assertSucceeded(s.active(), id + " active");
                for (int i = 0; i < 30; i++) {
                    s.ticks(1);
                    assertAllUnrotated(s, id + " active");
                }
                s.aimAt(s.owner, s.enemy);
                s.assertSucceeded(s.ultimate(), id + " ultimate");
                for (int i = 0; i < 60; i++) {
                    s.ticks(1);
                    assertAllUnrotated(s, id + " ultimate");
                }
                s.ticks(240);
                s.finishClean();
            }
        }
    }

    /** Where the middle of a block display's (scaled, rotated) block sits, relative to the display's location. */
    private static Vector3f blockCenter(BlockDisplay display) {
        Transformation t = display.getTransformation();
        return new Quaternionf(t.getLeftRotation()).transform(new Vector3f(t.getScale()).mul(0.5f))
                .add(t.getTranslation());
    }

    @Test
    void directionalDisplaysPointTheWayTheyMean() {
        // Everyone faces east (+X, yaw -90): a model that ignored the facing would still point south (+Z).
        try (Scenario s = noGaugeGain(Scenario.builder("charon")).start()) {
            s.place(s.enemy, 0.5, 8.5, 180);
            s.place(s.enemy2, 0.5, -8.5, 0);
            s.place(s.owner, 0.5, 0.5, -90);
            s.assertSucceeded(s.active(), "스틱스 물길");
            s.ticks(12);
            List<BlockDisplay> water = blockDisplays(s, Material.CYAN_STAINED_GLASS);
            assertFalse(water.isEmpty());
            for (BlockDisplay sheet : water) {
                assertTrue(Math.abs(modelDirection(sheet, 0, 0, 1).x()) > 0.99,
                        "a sheet's length runs down the river: " + modelDirection(sheet, 0, 0, 1));
                assertEquals(s.param("channel_width"), sheet.getTransformation().getScale().x(), 0.01,
                        "and its width across it");
            }
            s.owner.setSneaking(true);
            s.assertSucceeded(s.ultimate(), "뱃삯을 내라");
            s.ticks(10);
            List<BlockDisplay> planks = blockDisplays(s, Material.DARK_OAK_PLANKS);
            BlockDisplay hull = planks.stream()
                    .max((a, b) -> Float.compare(a.getTransformation().getScale().z(),
                            b.getTransformation().getScale().z()))
                    .orElseThrow();
            assertTrue(modelDirection(hull, 0, 0, 1).x() > 0.99, "the hull lies along Charon's facing");
            BlockDisplay bow = planks.stream()
                    .filter(p -> Math.abs(p.getTransformation().getScale().x() - 0.76f) < 0.01)
                    .findFirst().orElseThrow();
            assertTrue(blockCenter(bow).x() > 0.8, "the bow is ahead of him: " + blockCenter(bow));
            s.owner.setSneaking(false);
            s.ticks(s.paramTicks("styx_duration") + 12);
            s.finishClean();
        }
        try (Scenario s = Scenario.ffa("banshee")) {
            s.place(s.enemy, 12.5, 0.5, 90);
            s.place(s.enemy2, -8.5, 8.5, 0);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "수의");
            s.ticks(2);
            BlockDisplay cloth = entities(s, BlockDisplay.class).getFirst();
            assertTrue(modelDirection(cloth, 0, 0, 1).x() > 0.7, "the cloth lies along the throw: "
                    + modelDirection(cloth, 0, 0, 1));
            s.ticks(40);
            s.finishClean();
        }
        try (Scenario s = noGaugeGain(Scenario.builder("cursenote")).start()) {
            s.place(s.enemy, 6.5, 0.5, 90);
            s.place(s.enemy2, -8.5, 8.5, 0);
            s.melee(s.owner, s.enemy, 1);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "이름 적기");
            s.ticks(6);
            Vector3f page = modelDirection(itemDisplays(s, Material.WRITABLE_BOOK).getFirst(), 0, 0, 1);
            assertTrue(page.x() > 0.3 && Math.abs(page.z()) < 0.1, "the book is turned the way the writer faces: "
                    + page);
            s.ticks(s.paramTicks("write_countdown") + 10);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "저승의 시력");
            s.ticks(10);
            for (BlockDisplay part : blockDisplays(s, Material.GRAY_STAINED_GLASS)) {
                assertTrue(modelDirection(part, 0, 0, 1).x() > 0.99, "the ink ghost faces the writer's way");
            }
            s.ticks(s.paramTicks("sight_duration") + 10);
            s.finishClean();
        }
        try (Scenario s = noGaugeGain(Scenario.builder("yeomra")).param("reaper_count", 1).start()) {
            s.place(s.enemy, 12.5, 0.5, 90);
            s.place(s.enemy2, -40.5, 40.5, 0);
            s.place(s.owner, 0.5, 0.5, -90);
            s.assertSucceeded(s.ultimate(), "저승사자 출두");
            s.ticks(14);
            for (BlockDisplay robe : blockDisplays(s, Material.BLACK_WOOL)) {
                Vector3f ahead = modelDirection(robe, 0, 0, 1);
                Location to = s.enemy.getLocation().subtract(robe.getLocation());
                double dot = (ahead.x() * to.getX() + ahead.z() * to.getZ()) / Math.hypot(to.getX(), to.getZ());
                assertTrue(dot > 0.95, "the reaper faces its target: " + ahead);
            }
            s.ticks(s.paramTicks("reaper_duration") + 20);
            s.finishClean();
        }
    }

    // ================================================================== 카산드라 cassandra

    @Test
    void cassandraProphecyFallsAtTheFeetOfAnEnemyStandingStill() {
        try (Scenario s = Scenario.ffa("cassandra")) {
            s.ticks(10);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "예언");
            s.ticks(2);
            List<BlockDisplay> stars = blockDisplays(s, Material.PURPLE_STAINED_GLASS);
            assertEquals(2, stars.size(), "two turning stars of the seal");
            for (BlockDisplay star : stars) {
                assertEquals(s.enemy.getLocation().getX(), star.getLocation().getX(), 0.05, "at its feet");
                assertEquals(s.enemy.getLocation().getZ(), star.getLocation().getZ(), 0.05, "at its feet");
                assertTrue(star.getLocation().getY() >= FlatWorld.SURFACE - EPSILON, "on the floor, not in it");
                assertTrue(star.isGlowing(), "the telegraph shows through walls");
            }
            assertEquals(1, itemDisplays(s, Material.ENDER_EYE).size(), "an open eye in the middle");
            assertAllUnrotated(s, "seal");
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "nothing yet: the prophecy takes its time");
            int delay = s.paramTicks("prophecy_delay");
            s.ticks(delay - 6);
            assertEquals(3, blockDisplays(s, Material.CUT_COPPER).size() + blockDisplays(s, Material.BONE_BLOCK).size(),
                    "the spear of fate is falling");
            assertAllUnrotated(s, "spear");
            for (BlockDisplay shaft : blockDisplays(s, Material.CUT_COPPER)) {
                Vector3f up = modelDirection(shaft, 0, 1, 0);
                assertTrue(up.y() > 0.99, "the shaft stands straight up: " + up);
            }
            s.ticks(6);
            assertEquals(s.param("prophecy_damage"), magicDamage(s, s.enemy), EPSILON, "the prophecy came true");
            assertTrue(slowed(s.enemy), "and it drags its feet");
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON, "enemy2 stood elsewhere");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            double expected = s.params().cooldownSeconds() - s.param("prophecy_delay") - s.param("hit_refund");
            assertEquals(expected, s.ctx().cooldownRemaining(s.owner), 0.2, "a hit shortens the cooldown");
            s.ticks(s.paramTicks("prophecy_slow") + 2);
            assertFalse(slowed(s.enemy));
            s.ticks(40);
            assertNoDisplays(s, "the spear fades");
            s.finishClean();
        }
    }

    @Test
    void cassandraProphecyLandsInTheWayOfAnEnemyThatKeepsRunning() {
        try (Scenario s = Scenario.ffa("cassandra")) {
            s.place(s.enemy2, -12.5, -12.5, 0);
            s.place(s.enemy, -4.5, 8.5, 270);
            // It runs east at 4 blocks a second.
            for (int i = 0; i < 8; i++) {
                step(s, s.enemy, 0.2, 0);
            }
            s.aimAt(s.owner, s.enemy);
            double x = s.enemy.getLocation().getX();
            s.assertSucceeded(s.active(), "예언");
            s.ticks(1);
            Location seal = blockDisplays(s, Material.PURPLE_STAINED_GLASS).getFirst().getLocation();
            double lead = 0.2 * s.paramTicks("prophecy_delay");
            assertEquals(x + lead, seal.getX(), 0.6, "ahead of the runner, where it will be: " + seal.toVector());
            assertEquals(8.5, seal.getZ(), 0.3);
            assertEquals(FlatWorld.SURFACE, seal.getY(), 0.05, "on the ground");
            // It keeps running straight into its fate.
            int delay = s.paramTicks("prophecy_delay");
            for (int i = 0; i < delay + 2; i++) {
                step(s, s.enemy, 0.2, 0);
            }
            assertEquals(s.param("prophecy_damage"), magicDamage(s, s.enemy), EPSILON, "running straight is doom");
            s.finishClean();
        }
    }

    @Test
    void cassandraEnemyThatStopsCheatsTheProphecyAndIsFated() {
        try (Scenario s = noGaugeGain(Scenario.builder("cassandra")).start()) {
            s.place(s.enemy2, -12.5, -12.5, 0);
            s.place(s.enemy, -4.5, 8.5, 270);
            for (int i = 0; i < 8; i++) {
                step(s, s.enemy, 0.2, 0);
            }
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "예언");
            // It stops dead: the prophecy falls ahead of it (and the seal fades).
            s.ticks(s.paramTicks("prophecy_delay") + 10);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "it changed its mind");
            assertEquals(1, itemDisplays(s, Material.ENDER_EYE).size(), "an eye of fate circles over it");
            ItemDisplay eye = itemDisplays(s, Material.ENDER_EYE).getFirst();
            assertTrue(eye.getLocation().getY() > s.enemy.getEyeLocation().getY(), "over its head");
            assertEquals(Display.Billboard.CENTER, eye.getBillboard());
            double cooldown = s.ctx().cooldownRemaining(s.owner);
            assertEquals(s.params().cooldownSeconds() - s.param("prophecy_delay") - 0.5, cooldown, 0.2,
                    "no refund on a miss");

            // The next prophecy on the fated enemy falls sooner and wider.
            s.readyActive();
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "예언");
            s.ticks(1);
            BlockDisplay star = blockDisplays(s, Material.PURPLE_STAINED_GLASS).getFirst();
            int fast = (int) Math.round(s.paramTicks("prophecy_delay") * (1 - s.param("fate_haste") / 100));
            s.ticks(6);
            assertTrue(star.getTransformation().getScale().x()
                            > (s.param("prophecy_radius") + s.param("fate_radius") - 0.05) * 1.3,
                    "a wider seal: " + star.getTransformation().getScale());
            s.ticks(fast - 8);
            assertEquals(0, magicDamage(s, s.enemy), EPSILON, "not yet");
            s.ticks(1);
            assertEquals(s.param("prophecy_damage"), magicDamage(s, s.enemy), EPSILON,
                    "fate is certain, after " + fast + " ticks instead of " + s.paramTicks("prophecy_delay"));
            s.ticks(30);
            assertTrue(itemDisplays(s, Material.ENDER_EYE).isEmpty(), "the fate was spent");
            s.finishClean();
        }
    }

    @Test
    void cassandraProphecyStopsShortOfAWall() {
        try (Scenario s = Scenario.ffa("cassandra")) {
            s.place(s.enemy2, -12.5, -12.5, 0);
            s.world.wallAlongX(-4, 4, 11, 3, Material.STONE);
            s.place(s.enemy, 0.5, 6.5, 0);
            for (int i = 0; i < 8; i++) {
                step(s, s.enemy, 0, 0.25);
            }
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "예언");
            s.ticks(1);
            Location seal = blockDisplays(s, Material.PURPLE_STAINED_GLASS).getFirst().getLocation();
            assertTrue(seal.getZ() < 11 - 0.29, "never inside or behind the wall: " + seal.toVector());
            assertTrue(seal.getZ() > 9.5, "but as far as the runner can get: " + seal.toVector());
            s.ticks(40);
            s.world.wallAlongX(-4, 4, 11, 3, Material.AIR);
            s.finishClean();
        }
    }

    @Test
    void cassandraNeverForetellsAnEnemyHiddenFromHer() {
        try (Scenario s = Scenario.ffa("cassandra")) {
            s.owner.hidePlayer(s.ctx().plugin(), s.enemy);
            s.aimAt(s.owner, s.enemy);
            assertFalse(s.active().succeeded(), "aiming at a hidden enemy finds nothing");
            assertEquals(0, s.ctx().cooldownRemaining(s.owner), EPSILON);
            s.owner.hidePlayer(s.ctx().plugin(), s.enemy2);
            s.match.setGauge(s.owner, 100);
            assertFalse(s.match.useUltimate(s.owner).succeeded(), "nobody to doom");
            assertEquals(100, gauge(s, s.owner), EPSILON, "the gauge is kept");
            assertNoDisplays(s, "no seal reveals them");
            s.owner.showPlayer(s.ctx().plugin(), s.enemy);
            s.owner.showPlayer(s.ctx().plugin(), s.enemy2);
            s.finishClean();
        }
    }

    @Test
    void cassandraFallOfTroyStrikesEveryEnemyThreeTimes() {
        try (Scenario s = noGaugeGain(Scenario.builder("cassandra")).start()) {
            s.assertSucceeded(s.ultimate(), "트로이의 멸망");
            s.ticks(3);
            assertEquals(1, itemDisplays(s, Material.ENDER_EYE).size(), "a giant eye opens over Cassandra");
            assertTrue(itemDisplays(s, Material.ENDER_EYE).getFirst().getLocation().getY()
                    > s.owner.getEyeLocation().getY() + 1);
            int windup = s.paramTicks("ult_windup");
            int interval = s.paramTicks("ult_interval");
            int seal = s.paramTicks("ult_delay");
            s.ticks(windup + 2);
            assertEquals(4, blockDisplays(s, Material.PURPLE_STAINED_GLASS).size(), "a seal under each enemy");
            assertAllUnrotated(s, "ult");
            s.ticks(interval * 2 + seal + 10);
            double each = s.param("ult_damage") * s.param("ult_waves");
            assertEquals(each, magicDamage(s, s.enemy), EPSILON, "three waves on the one standing still");
            assertEquals(each, magicDamage(s, s.enemy2), EPSILON, "and on the other");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            assertFalse(slowed(s.enemy), "the ultimate's spears only hurt");
            s.ticks(40);
            assertNoDisplays(s, "Troy has fallen");
            s.finishClean();
        }
    }

    @Test
    void cassandraFallOfTroyRunsDownAnEnemyRunningStraight() {
        try (Scenario s = noGaugeGain(Scenario.builder("cassandra")).start()) {
            s.place(s.enemy2, -14.5, -12.5, 0);
            s.place(s.enemy, -8.5, 6.5, 270);
            for (int i = 0; i < 6; i++) {
                step(s, s.enemy, 0.15, 0);
            }
            s.assertSucceeded(s.ultimate(), "트로이의 멸망");
            int total = s.paramTicks("ult_windup") + s.paramTicks("ult_interval") * 2 + s.paramTicks("ult_delay") + 6;
            for (int i = 0; i < total; i++) {
                step(s, s.enemy, 0.15, 0);
            }
            assertEquals(s.param("ult_damage") * s.param("ult_waves"), magicDamage(s, s.enemy), EPSILON,
                    "every wave fell where the runner was going");
            s.ticks(40);
            s.finishClean();
        }
    }

    @Test
    void cassandraFallOfTroyMissesAnEnemyThatKeepsChangingCourse() {
        try (Scenario s = noGaugeGain(Scenario.builder("cassandra")).start()) {
            s.place(s.enemy2, -14.5, -12.5, 0);
            s.place(s.enemy, -3.5, 6.5, 270);
            s.assertSucceeded(s.ultimate(), "트로이의 멸망");
            // A zigzag: it turns round just after every prophecy is made.
            int windup = s.paramTicks("ult_windup");
            int interval = s.paramTicks("ult_interval");
            int total = windup + interval * 3 + s.paramTicks("ult_delay") + 10;
            for (int t = 0; t < total; t++) {
                int since = t - windup - 3;
                boolean east = since < 0 || (since / interval) % 2 == 1;
                step(s, s.enemy, east ? 0.25 : -0.25, 0);
            }
            assertTrue(magicDamage(s, s.enemy) < s.param("ult_damage") * s.param("ult_waves") - EPSILON,
                    "an unpredictable runner slips some of the prophecies: " + magicDamage(s, s.enemy));
            s.ticks(40);
            s.finishClean();
        }
    }

    @Test
    void cassandraProphecyRunsDownASprintJumper() {
        try (Scenario s = Scenario.ffa("cassandra")) {
            s.place(s.enemy2, -12.5, -12.5, 0);
            s.place(s.enemy, -14.5, 8.5, 270);
            // A sprint-jump: about 7.1 blocks a second, the fastest a player runs on foot.
            double pace = 7.2 / 20;
            for (int i = 0; i < 8; i++) {
                step(s, s.enemy, pace, 0);
            }
            s.aimAt(s.owner, s.enemy);
            double x = s.enemy.getLocation().getX();
            s.assertSucceeded(s.active(), "예언");
            s.ticks(1);
            int delay = s.paramTicks("prophecy_delay");
            Location seal = blockDisplays(s, Material.PURPLE_STAINED_GLASS).getFirst().getLocation();
            assertEquals(x + pace * (delay + 1), seal.getX(), 1.0, "the seal is where the runner will be: " + seal);
            for (int i = 0; i < delay + 2; i++) {
                step(s, s.enemy, pace, 0);
            }
            assertEquals(s.param("prophecy_damage"), magicDamage(s, s.enemy), EPSILON,
                    "running straight is doom, even sprint-jumping");
            s.ticks(40);
            s.finishClean();
        }
    }

    @Test
    void cassandraFallOfTroyStrikesEnemiesSideBySideOncePerWave() {
        try (Scenario s = noGaugeGain(Scenario.builder("cassandra")).start()) {
            // A duel: two enemies a step apart, each inside the other's seal.
            s.place(s.enemy, 0.5, 6.5, 90);
            s.place(s.enemy2, 1.7, 6.5, 270);
            s.assertSucceeded(s.ultimate(), "트로이의 멸망");
            s.ticks(s.paramTicks("ult_windup") + s.paramTicks("ult_interval") * 2 + s.paramTicks("ult_delay") + 10);
            int waves = (int) s.param("ult_waves");
            double each = s.param("ult_damage") * waves;
            for (TestPlayer enemy : List.of(s.enemy, s.enemy2)) {
                assertEquals(each, magicDamage(s, enemy), EPSILON, enemy.getName() + ": one spear a wave, not two");
                assertEquals(waves, s.hitsBy(s.owner, enemy).stream().filter(hit -> hit.cause() == DamageCause.MAGIC)
                        .count(), enemy.getName() + ": a hit a wave");
            }
            s.ticks(40);
            s.finishClean();
        }
    }

    @Test
    void cassandraFallOfTroyShowsEverySealAndSpearOverManyEnemies() {
        try (Scenario s = noGaugeGain(Scenario.builder("cassandra").enemyAbility("summoner")).start()) {
            callWolves(s, new double[][] {{-6.5, 6.5}, {7.5, 6.5}});
            s.place(s.enemy, 0.5, 9.5, 180);
            s.place(s.enemy2, 0.5, -7.5, 0);
            List<LivingEntity> doomed = new ArrayList<>(entities(s, Wolf.class));
            doomed.add(s.enemy);
            doomed.add(s.enemy2);
            s.assertSucceeded(s.ultimate(), "트로이의 멸망");
            int windup = s.paramTicks("ult_windup");
            int interval = s.paramTicks("ult_interval");
            int seal = s.paramTicks("ult_delay");
            int elapsed = 0;
            for (int wave = 0; wave < (int) s.param("ult_waves"); wave++) {
                // Once the last wave's stars have shrunk away; once this wave's spears have landed.
                int start = windup + wave * interval + 1;
                for (int[] check : new int[][] {{start + 9, 0}, {start + seal + 2, 1}}) {
                    while (elapsed < check[0]) {
                        s.ticks(1);
                        elapsed++;
                        assertTrue(entities(s, Display.class).size() <= 24, "in budget");
                    }
                    Material part = check[1] == 0 ? Material.PURPLE_STAINED_GLASS : Material.CUT_COPPER;
                    for (LivingEntity enemy : doomed) {
                        Location feet = enemy.getLocation();
                        assertTrue(blockDisplays(s, part).stream().anyMatch(d -> Math.hypot(d.getLocation().getX()
                                        - feet.getX(), d.getLocation().getZ() - feet.getZ()) < 0.3),
                                "wave " + (wave + 1) + ": " + (check[1] == 0 ? "a star in the seal" : "the spear")
                                        + " of " + enemy.getName());
                    }
                }
            }
            s.ticks(60);
            s.finishClean();
        }
    }

    // ================================================================== 염라대왕 yeomra

    @Test
    void yeomraSeesWhereEveryEnemyStoodAMomentAgoAndNobodyElseDoes() {
        try (Scenario s = Scenario.ffa("yeomra")) {
            s.place(s.enemy2, -12.5, -12.5, 0);
            s.place(s.enemy, 0.5, 4.5, 270);
            int back = s.paramTicks("rewind_seconds");
            for (int i = 0; i < back + 8; i++) {
                step(s, s.enemy, 0.15, 0);
            }
            Location now = s.enemy.getLocation();
            double pastX = now.getX() - 0.15 * back;
            s.owner.particles().clear();
            s.enemy.particles().clear();
            s.enemy2.particles().clear();
            s.ticks(4);
            List<TestPlayer.SentParticle> echo = s.owner.particles().stream()
                    .filter(p -> p.particle() == org.bukkit.Particle.DUST)
                    .filter(p -> Math.hypot(p.at().getX() - pastX, p.at().getZ() - now.getZ()) < 0.75)
                    .filter(p -> p.at().getY() > FlatWorld.SURFACE + 1.2)
                    .toList();
            assertFalse(echo.isEmpty(), "the king sees a silhouette where it stood " + s.param("rewind_seconds")
                    + " s ago");
            for (TestPlayer other : List.of(s.enemy, s.enemy2)) {
                for (TestPlayer.SentParticle particle : other.particles()) {
                    for (TestPlayer.SentParticle seen : echo) {
                        assertFalse(particle.at().distanceSquared(seen.at()) < 1.0E-9,
                                other.getName() + " never sees the afterimage");
                    }
                }
            }
            s.finishClean();
        }
    }

    @Test
    void yeomraAfterimagesFollowTheEffectScaleAndOnlyTheAimedEnemyShowsItsTrail() {
        try (Scenario s = Scenario.builder("yeomra").configure(h -> h.settings().set("effects.particle-scale", 0.25))
                .start()) {
            s.place(s.enemy2, -12.5, -12.5, 0);
            s.place(s.enemy, 0.5, 4.5, 270);
            int back = s.paramTicks("rewind_seconds");
            for (int i = 0; i < back + 8; i++) {
                step(s, s.enemy, 0.15, 0);
            }
            Location now = s.enemy.getLocation();
            double pastX = now.getX() - 0.15 * back;
            s.place(s.owner, 0.5, -0.5, 180);
            s.owner.particles().clear();
            s.ticks(4);
            List<TestPlayer.SentParticle> head = s.owner.particles().stream()
                    .filter(p -> p.particle() == org.bukkit.Particle.DUST)
                    .filter(p -> Math.hypot(p.at().getX() - pastX, p.at().getZ() - now.getZ()) < 0.75)
                    .filter(p -> p.at().getY() > FlatWorld.SURFACE + 1.45)
                    .toList();
            assertFalse(head.isEmpty(), "a faint head where it stood");
            assertTrue(head.size() <= 3, "a quarter of the particles at a quarter scale: " + head.size());
            assertTrue(s.owner.particles().stream().noneMatch(p -> p.at().getY() < FlatWorld.SURFACE + 0.15),
                    "no trail of steps for an enemy not aimed at");
            s.aimAt(s.owner, s.enemy);
            s.owner.particles().clear();
            s.ticks(4);
            assertTrue(s.owner.particles().stream().anyMatch(p -> p.particle() == org.bukkit.Particle.DUST
                    && p.at().getY() < FlatWorld.SURFACE + 0.15), "the aimed enemy's way back is dotted out");
            s.finishClean();
        }
    }

    @Test
    void yeomraRewindPutsAnEnemyThatWasInTheAirDownOnTheGround() {
        try (Scenario s = Scenario.ffa("yeomra")) {
            s.place(s.enemy2, -12.5, -12.5, 0);
            int back = s.paramTicks("rewind_seconds");
            // Knocked high up two seconds ago, drifting east, then back on its feet and walking on.
            double x = -1.5;
            for (int i = 0; i < 8; i++) {
                s.place(s.enemy, x, FlatWorld.SURFACE + 5, 6.5, 270, 0);
                s.ticks(1);
                x += 0.2;
            }
            s.place(s.enemy, x, 6.5, 270);
            for (int i = 0; i < back - 4; i++) {
                step(s, s.enemy, 0.2, 0);
            }
            double from = s.enemy.getLocation().getX();
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "업경대");
            s.ticks(2);
            for (BlockDisplay part : entities(s, BlockDisplay.class)) {
                assertEquals(FlatWorld.SURFACE, part.getLocation().getY(), 1.0E-6, "the mirror stands on the ground");
            }
            s.ticks(s.paramTicks("mirror_windup") + s.paramTicks("rewind_time"));
            assertOnSurface(s.enemy, "put down under where it was, not left hanging in the air");
            assertTrue(s.enemy.getLocation().getX() < from - 6, "back where it was: " + s.enemy.getLocation());
            s.ticks(30);
            assertEquals(s.param("mirror_damage"), s.damageTaken(s.enemy), EPSILON, "judged, and no fall after it");
            s.finishClean();
        }
    }

    @Test
    void yeomraMirrorDragsAnEnemyBackAlongItsOwnSteps() {
        try (Scenario s = Scenario.ffa("yeomra")) {
            s.place(s.enemy2, -12.5, -12.5, 0);
            s.place(s.enemy, 0.5, 6.5, 270);
            int back = s.paramTicks("rewind_seconds");
            for (int i = 0; i < back + 10; i++) {
                step(s, s.enemy, 0.2, 0);
            }
            // It dashed east; the king looks at it.
            double pastX = s.enemy.getLocation().getX() - 0.2 * back;
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "업경대");
            s.ticks(2);
            assertEquals(5, entities(s, BlockDisplay.class).size(), "the karma mirror rises: glass, frame, stand, rim, base");
            assertAllUnrotated(s, "mirror");
            BlockDisplay glass = blockDisplays(s, Material.LIGHT_BLUE_STAINED_GLASS).getFirst();
            assertTrue(glass.isGlowing(), "its glass shines through walls");
            Vector3f face = modelDirection(glass, 0, 0, 1);
            assertTrue(face.x() > 0.9, "the mirror faces the enemy (east of it): " + face);
            assertTrue(glass.getLocation().getX() < pastX, "and stands behind its past spot");
            int windup = s.paramTicks("mirror_windup");
            int rewind = s.paramTicks("rewind_time");
            s.ticks(windup + rewind + 1);
            Location at = s.enemy.getLocation();
            assertEquals(pastX, at.getX(), 0.45, "back where it was " + s.param("rewind_seconds") + " s ago: " + at);
            assertEquals(6.5, at.getZ(), 0.05);
            assertOnSurface(s.enemy, "rewound");
            assertEquals(s.param("mirror_damage"), magicDamage(s, s.enemy), EPSILON, "and judged");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            assertEquals(0, s.enemy.getVelocity().getX(), EPSILON, "no dash momentum survives");
            s.ticks(40);
            assertNoDisplays(s, "the mirror sinks");
            s.finishClean();
        }
    }

    @Test
    void yeomraRewindStopsWhereThePathIsBlockedNow() {
        try (Scenario s = Scenario.ffa("yeomra")) {
            s.place(s.enemy2, -12.5, -12.5, 0);
            s.place(s.enemy, -2.5, 8.5, 270);
            int back = s.paramTicks("rewind_seconds");
            for (int i = 0; i < back + 4; i++) {
                step(s, s.enemy, 0.2, 0);
            }
            double x = s.enemy.getLocation().getX();
            int wallX = (int) Math.floor(x - 2.5);
            // A wall rose across the way it came.
            for (int y = 64; y <= 66; y++) {
                for (int z = 6; z <= 11; z++) {
                    s.world.setBlock(wallX, y, z, Material.STONE);
                }
            }
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "업경대");
            s.ticks(s.paramTicks("mirror_windup") + s.paramTicks("rewind_time") + 2);
            Location at = s.enemy.getLocation();
            assertTrue(at.getX() > wallX + 1, "stopped short of the wall: " + at);
            assertTrue(at.getX() < x - 0.3, "after coming back as far as it could: " + at);
            assertTrue(Spots.isFree(s.world, at, s.enemy.getWidth(), s.enemy.getHeight()), "never inside it");
            assertEquals(s.param("mirror_damage"), magicDamage(s, s.enemy), EPSILON);
            for (int y = 64; y <= 66; y++) {
                for (int z = 6; z <= 11; z++) {
                    s.world.setBlock(wallX, y, z, Material.AIR);
                }
            }
            s.ticks(40);
            s.finishClean();
        }
    }

    @Test
    void yeomraJudgesAnEnemyThatStoodStillForItsKarma() {
        try (Scenario s = Scenario.ffa("yeomra")) {
            s.ticks(s.paramTicks("rewind_seconds") + 5);
            Location before = s.enemy.getLocation();
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "업경대");
            s.ticks(s.paramTicks("mirror_windup") + 3);
            assertEquals(s.param("karma_damage"), magicDamage(s, s.enemy), EPSILON, "업보");
            assertTrue(s.enemy.getLocation().distance(before) < 0.05, "it stays where it stood");
            assertTrue(entities(s, TextDisplay.class).stream().anyMatch(t -> Scenario.plain(t.text()).contains("업보")),
                    "업보! pops over it");
            BlockDisplay glass = blockDisplays(s, Material.LIGHT_BLUE_STAINED_GLASS).getFirst();
            Vector3f face = modelDirection(glass, 0, 0, 1);
            assertTrue(face.z() > 0.9, "a mirror set before it, on the king's side, faces it: " + face);
            s.ticks(40);
            s.finishClean();
        }
    }

    @Test
    void yeomraReapersRiseWalkAndSeizeEnemiesThatStandStill() {
        try (Scenario s = noGaugeGain(Scenario.builder("yeomra")).start()) {
            s.place(s.enemy, 0.5, 12.5, 180);
            s.place(s.enemy2, 6.5, 12.5, 180);
            s.assertSucceeded(s.ultimate(), "저승사자 출두");
            s.ticks(18);
            List<BlockDisplay> robes = blockDisplays(s, Material.BLACK_WOOL);
            assertEquals(4, robes.size(), "two reapers in black robes");
            assertEquals(2, blockDisplays(s, Material.WHITE_CONCRETE).size(), "pale faces");
            assertEquals(4, blockDisplays(s, Material.BLACK_STAINED_GLASS).size(), "and their hats");
            assertAllUnrotated(s, "reapers");
            for (BlockDisplay robe : robes) {
                assertTrue(robe.isGlowing(), "a reaper shows through walls");
                double near = Math.min(robe.getLocation().distance(s.enemy.getLocation()),
                        robe.getLocation().distance(s.enemy2.getLocation()));
                assertTrue(near <= s.param("reaper_spawn") + 0.5, "it rose near its target: " + near);
            }
            assertTrue(entities(s, TextDisplay.class).stream().anyMatch(t -> Scenario.plain(t.text()).contains("Red")
                    || Scenario.plain(t.text()).contains("Blue")), "they call their targets by name");
            assertEquals(0, s.damageTaken(s.enemy) + s.damageTaken(s.enemy2), EPSILON, "not there yet");
            s.h.tickUntil(() -> magicDamage(s, s.enemy) > 0 && magicDamage(s, s.enemy2) > 0,
                    (int) (20 * (s.param("reaper_spawn") / s.param("reaper_speed"))) + 20, "the reapers arriving");
            assertTrue(rooted(s.enemy) || rooted(s.enemy2), "bound in the chains of the register");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.ticks(s.paramTicks("reaper_duration"));
            assertFalse(rooted(s.enemy));
            assertEquals(s.param("reaper_damage"), magicDamage(s, s.enemy), EPSILON, "a reaper takes its due once");
            assertEquals(s.param("reaper_damage"), magicDamage(s, s.enemy2), EPSILON, "one reaper each");
            assertNoDisplays(s, "they went back down");
            s.finishClean();
        }
    }

    @Test
    void yeomraEveryReaperRisesWithItsBodyEvenRightAfterTheMirror() {
        try (Scenario s = noGaugeGain(Scenario.builder("yeomra").enemyAbility("summoner")).start()) {
            callWolves(s, new double[][] {{-5.5, 12.5}, {6.5, 12.5}});
            s.place(s.enemy, 0.5, 12.5, 180);
            s.place(s.enemy2, 40.5, 40.5, 180);
            s.ticks(s.paramTicks("rewind_seconds") + 5);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "업경대");
            s.ticks(2);
            assertFalse(blockDisplays(s, Material.LIGHT_BLUE_STAINED_GLASS).isEmpty(), "the mirror stands");
            s.assertSucceeded(s.ultimate(), "저승사자 출두");
            for (int i = 0; i < 25; i++) {
                s.ticks(1);
                assertTrue(entities(s, Display.class).size() <= 24, "in budget");
            }
            assertEquals(3, blockDisplays(s, Material.WHITE_CONCRETE).size(), "three reapers, every one with a face");
            assertEquals(6, blockDisplays(s, Material.BLACK_WOOL).size(), "and its robe");
            for (BlockDisplay robe : blockDisplays(s, Material.BLACK_WOOL)) {
                assertTrue(robe.getViewRange() > 0 && robe.isGlowing(), "seen through walls");
            }
            s.ticks(s.paramTicks("reaper_duration") + 30);
            s.finishClean();
        }
    }

    @Test
    void yeomraReaperCanBeOutwalkedButNeverShakenOff() {
        try (Scenario s = noGaugeGain(Scenario.builder("yeomra")).param("reaper_count", 1).start()) {
            s.place(s.enemy2, -20.5, -20.5, 0);
            s.place(s.enemy, 8.5, 12.5, 90);
            s.assertSucceeded(s.ultimate(), "저승사자 출두");
            // It walks round and round, faster than the reaper (5 blocks a second).
            int total = s.paramTicks("reaper_duration") + 12;
            double radius = 8;
            double closest = Double.MAX_VALUE;
            for (int t = 0; t < total; t++) {
                double angle = t * 0.25 / radius;
                s.place(s.enemy, 0.5 + Math.cos(angle) * radius, 12.5 + Math.sin(angle) * radius, 0);
                s.ticks(1);
                for (BlockDisplay robe : blockDisplays(s, Material.BLACK_WOOL)) {
                    closest = Math.min(closest, robe.getLocation().distance(s.enemy.getLocation()));
                }
            }
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "never caught: " + closest);
            assertTrue(closest < 5, "but the reaper was always on its heels: " + closest);
            s.ticks(20);
            assertNoDisplays(s, "time ran out");
            s.finishClean();
        }
    }

    @Test
    void yeomraReaperWalksFasterAfterALoneTarget() {
        try (Scenario s = noGaugeGain(Scenario.builder("yeomra")).start()) {
            s.place(s.enemy, 0.5, 14.5, 180);
            s.place(s.enemy2, 40.5, 40.5, 180);
            s.assertSucceeded(s.ultimate(), "저승사자 출두");
            s.ticks(12);
            BlockDisplay robe = blockDisplays(s, Material.BLACK_WOOL).getFirst();
            double from = robe.getLocation().distance(s.enemy.getLocation());
            s.ticks(20);
            double walked = from - robe.getLocation().distance(s.enemy.getLocation());
            assertEquals(s.param("reaper_solo_speed"), walked, 0.15, "a lone target is fetched faster");
            s.ticks(s.paramTicks("reaper_duration") + 20);
            s.finishClean();
        }
    }

    @Test
    void yeomraReaperWaitsUnseenWhileItsTargetIsHidden() {
        try (Scenario s = noGaugeGain(Scenario.builder("yeomra")).param("reaper_count", 1).start()) {
            s.place(s.enemy, 0.5, 12.5, 180);
            s.place(s.enemy2, -20.5, -20.5, 0);
            s.assertSucceeded(s.ultimate(), "저승사자 출두");
            s.ticks(12);
            s.owner.hidePlayer(s.ctx().plugin(), s.enemy);
            s.ticks(2);
            BlockDisplay robe = blockDisplays(s, Material.BLACK_WOOL).getFirst();
            Location waiting = robe.getLocation();
            s.ticks(30);
            assertTrue(robe.getLocation().distance(waiting) < 0.11, "it stands still");
            assertEquals(0, robe.getViewRange(), EPSILON, "and nobody sees it");
            assertEquals(0, s.damageTaken(s.enemy), EPSILON);
            s.owner.showPlayer(s.ctx().plugin(), s.enemy);
            s.h.tickUntil(() -> magicDamage(s, s.enemy) > 0, 120, "the reaper going on");
            s.ticks(s.paramTicks("reaper_duration"));
            s.finishClean();
        }
    }

    // ================================================================== 밴시 banshee

    /** A vanilla heal (natural regeneration, a potion) as the server fires it: the event, then the health. */
    private static void regain(Scenario s, LivingEntity target, double amount) {
        EntityRegainHealthEvent event = new EntityRegainHealthEvent(target, amount,
                EntityRegainHealthEvent.RegainReason.SATIATED);
        s.h.server.getPluginManager().callEvent(event);
        if (!event.isCancelled()) {
            double max = target.getAttribute(Attribute.MAX_HEALTH).getValue();
            target.setHealth(Math.min(max, target.getHealth() + event.getAmount()));
        }
    }

    @Test
    void bansheeKeensOverAnEnemyThatTakesABeatingAndShowsItToHerSideOnly() {
        try (Scenario s = noGaugeGain(Scenario.builder("banshee").teams()).start()) {
            s.place(s.ally(), 2.5, 6.5, 180);
            // A few small blows do not add up.
            s.melee(s.ally(), s.enemy, 2);
            s.ticks(30);
            s.melee(s.ally(), s.enemy, 2);
            s.ticks(30);
            s.melee(s.ally(), s.enemy, 2);
            s.ticks(5);
            assertTrue(itemDisplays(s, Material.SOUL_LANTERN).isEmpty(), "spread-out blows are no beating");
            assertEquals(0, gauge(s, s.owner), EPSILON);
            // A beating: 6 in a second.
            s.melee(s.ally(), s.enemy, 3);
            s.ticks(10);
            s.melee(s.ally(), s.enemy, 3);
            s.ticks(2);
            List<ItemDisplay> lanterns = itemDisplays(s, Material.SOUL_LANTERN);
            assertEquals(1, lanterns.size(), "a soul lantern over the doomed");
            ItemDisplay lantern = lanterns.getFirst();
            assertTrue(lantern.getLocation().getY() > s.enemy.getEyeLocation().getY(), "over its head");
            assertTrue(s.owner.canSee(lantern), "the banshee sees it");
            assertTrue(s.ally().canSee(lantern), "and so does her ally");
            assertFalse(s.enemy.canSee(lantern), "but not the doomed");
            assertFalse(s.enemy2.canSee(lantern), "nor any other enemy");
            assertTrue(lantern.isGlowing(), "through walls");
            assertAllUnrotated(s, "lantern");
            assertEquals(s.param("keen_gauge"), gauge(s, s.owner), EPSILON, "her ultimate fills a little");
            assertTrue(s.enemy.getActivePotionEffects().isEmpty(), "no glow everyone could see");
            // Again right away: once per enemy per cooldown.
            s.melee(s.ally(), s.enemy, 3);
            s.ticks(5);
            s.melee(s.ally(), s.enemy, 3);
            s.ticks(2);
            assertEquals(s.param("keen_gauge"), gauge(s, s.owner), EPSILON, "no second wail so soon");
            s.ticks(s.paramTicks("keen_mark"));
            assertTrue(itemDisplays(s, Material.SOUL_LANTERN).isEmpty(), "the lantern goes out");
            assertEquals(0, s.damageTaken(s.ally()), EPSILON);
            s.finishClean();
        }
    }

    @Test
    void bansheeNeverKeensOverAnEnemyHiddenFromHer() {
        try (Scenario s = noGaugeGain(Scenario.builder("banshee")).start()) {
            s.owner.hidePlayer(s.ctx().plugin(), s.enemy);
            int heard = s.owner.getHeardSounds().size();
            s.melee(s.enemy2, s.enemy, 4);
            s.ticks(2);
            s.melee(s.enemy2, s.enemy, 4);
            s.ticks(3);
            assertEquals(0, gauge(s, s.owner), EPSILON, "the gauge does not tell her someone is there");
            assertTrue(itemDisplays(s, Material.SOUL_LANTERN).isEmpty());
            List<String> wails = s.owner.getHeardSounds().subList(heard, s.owner.getHeardSounds().size()).stream()
                    .map(sound -> sound.getSound())
                    .filter(name -> name.contains("allay") || name.contains("vex") || name.contains("soul_escape"))
                    .toList();
            assertEquals(List.of(), wails, "no wail at its head");
            s.owner.showPlayer(s.ctx().plugin(), s.enemy);
            s.melee(s.enemy2, s.enemy, 4);
            s.ticks(2);
            s.melee(s.enemy2, s.enemy, 4);
            s.ticks(3);
            assertEquals(s.param("keen_gauge"), gauge(s, s.owner), EPSILON, "seen again, heard again");
            s.finishClean();
        }
    }

    @Test
    void bansheeDoesNotKeenOverEnemiesFarAway() {
        try (Scenario s = noGaugeGain(Scenario.builder("banshee")).start()) {
            s.place(s.enemy, 0.5, 30.5, 180);
            s.place(s.enemy2, 2.5, 30.5, 180);
            s.melee(s.enemy2, s.enemy, 4);
            s.ticks(2);
            s.melee(s.enemy2, s.enemy, 4);
            s.ticks(3);
            assertTrue(itemDisplays(s, Material.SOUL_LANTERN).isEmpty(), "out of earshot");
            assertEquals(0, gauge(s, s.owner), EPSILON);
            s.finishClean();
        }
    }

    @Test
    void bansheeShroudPutsACeilingOnHealingButNeverTakesHealth() {
        try (Scenario s = Scenario.ffa("banshee")) {
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "수의");
            s.ticks(8);
            double max = s.maxHealth(s.enemy);
            assertEquals(s.param("shroud_damage"), magicDamage(s, s.enemy), EPSILON, "the shroud hurts");
            double ceiling = max - s.param("shroud_cap");
            assertEquals(max - s.param("shroud_damage"), s.health(s.enemy), EPSILON);
            // A direct heal above the ceiling is taken back to it.
            s.ctx().heal(s.enemy, 10);
            s.ticks(1);
            assertEquals(ceiling, s.health(s.enemy), EPSILON, "healed only up to the ceiling");
            // Vanilla healing at the ceiling is cancelled, below it cut to what fits.
            regain(s, s.enemy, 2);
            assertEquals(ceiling, s.health(s.enemy), EPSILON, "no room above the ceiling");
            s.enemy.setHealth(ceiling - 10);
            s.ticks(1);
            regain(s, s.enemy, 30);
            assertEquals(ceiling, s.health(s.enemy), EPSILON, "a big heal stops at the ceiling");
            assertEquals(max, s.maxHealth(s.enemy), EPSILON, "its maximum health is untouched");

            List<BlockDisplay> veils = blockDisplays(s, Material.WHITE_STAINED_GLASS);
            assertEquals(1, veils.size(), "a veil over its head");
            assertTrue(veils.getFirst().getLocation().getY() > s.enemy.getEyeLocation().getY());
            assertFalse(s.enemy.canSee(veils.getFirst()), "it never blurs the shrouded's own view");
            assertTrue(s.owner.canSee(veils.getFirst()));
            assertTrue(entities(s, TextDisplay.class).stream().anyMatch(t -> Scenario.plain(t.text())
                    .contains(AbilityInfo.formatNumber(ceiling))), "the ceiling is written over it");
            assertAllUnrotated(s, "veil");

            // A second shroud lowers the ceiling.
            s.readyActive();
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "수의");
            s.ticks(8);
            double lower = max - s.param("shroud_stack_cap");
            assertEquals(1, blockDisplays(s, Material.LIGHT_GRAY_STAINED_GLASS).size(), "a second veil");
            s.enemy.setHealth(lower - 5);
            s.ticks(1);
            s.ctx().heal(s.enemy, 50);
            s.ticks(1);
            assertEquals(lower, s.health(s.enemy), EPSILON, "two shrouds, a lower ceiling");

            s.ticks(s.paramTicks("shroud_duration"));
            assertTrue(entities(s, Display.class).isEmpty(), "the shroud falls away");
            s.ctx().heal(s.enemy, 50);
            s.ticks(1);
            assertEquals(max, s.health(s.enemy), EPSILON, "free to heal again");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.finishClean();
        }
    }

    @Test
    void bansheeShroudLeavesHealthAboveTheCeilingAlone() {
        try (Scenario s = Scenario.builder("banshee").param("shroud_damage", 0).start()) {
            double max = s.maxHealth(s.enemy);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "수의");
            s.ticks(8);
            assertEquals(1, blockDisplays(s, Material.WHITE_STAINED_GLASS).size(), "shrouded");
            s.ticks(5);
            assertEquals(max, s.health(s.enemy), EPSILON, "the health it has is never taken");
            s.melee(s.enemy2, s.enemy, 2);
            s.ticks(1);
            regain(s, s.enemy, 2);
            assertEquals(max - 2, s.health(s.enemy), EPSILON, "but it cannot heal the hit back");
            s.finishClean();
        }
    }

    @Test
    void bansheeShroudFliesUntilAWallAndMissesLeaveNothing() {
        try (Scenario s = Scenario.ffa("banshee")) {
            s.place(s.enemy, 0.5, 10.5, 180);
            s.world.wallAlongX(-3, 3, 5, 3, Material.STONE);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "수의");
            s.ticks(30);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "the wall caught the cloth");
            assertNoDisplays(s, "the fallen cloth fades");
            s.world.wallAlongX(-3, 3, 5, 3, Material.AIR);
            s.finishClean();
        }
    }

    @Test
    void bansheeOmenTakesTheEnemyWithTheLeastHealthAndTheSkullFollowsTheRace() {
        try (Scenario s = noGaugeGain(Scenario.builder("banshee")).start()) {
            s.enemy.setHealth(60);
            s.enemy2.setHealth(70);
            s.assertSucceeded(s.ultimate(), "누가 먼저 갈까");
            s.ticks(3);
            List<ItemDisplay> skulls = itemDisplays(s, Material.SKELETON_SKULL);
            assertEquals(1, skulls.size(), "one skull");
            ItemDisplay skull = skulls.getFirst();
            assertTrue(skull.isGlowing());
            assertEquals(s.enemy.getLocation().getX(), skull.getLocation().getX(), 0.05, "over the weakest");
            assertEquals(2, entities(s, TextDisplay.class).size(), "a countdown over each doomed");
            assertAllUnrotated(s, "omen");
            // The race: the other one takes a beating and the skull flies over.
            s.melee(s.owner, s.enemy2, 20);
            s.ticks(10);
            assertEquals(s.enemy2.getLocation().getX(), skull.getLocation().getX(), 0.05, "the skull moved");
            s.clearHits();
            s.ticks(s.paramTicks("omen_duration"));
            assertEquals(s.param("omen_take"), magicDamage(s, s.enemy2), EPSILON, "the bearer is taken");
            assertTrue(s.ctx().isSilenced(s.enemy2), "and silenced");
            assertEquals(s.param("omen_others"), magicDamage(s, s.enemy), EPSILON, "the other only shivers");
            assertFalse(s.ctx().isSilenced(s.enemy));
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.ticks(30);
            assertNoDisplays(s, "the omen is over");
            s.finishClean();
        }
    }

    @Test
    void bansheeOmenOnASingleEnemyTakesItAlone() {
        try (Scenario s = noGaugeGain(Scenario.builder("banshee")).start()) {
            s.place(s.enemy2, 40.5, 40.5, 180);
            s.assertSucceeded(s.ultimate(), "누가 먼저 갈까");
            s.ticks(s.paramTicks("omen_duration") + 2);
            assertEquals(s.param("omen_solo"), magicDamage(s, s.enemy), EPSILON, "a lonely omen");
            assertTrue(s.ctx().isSilenced(s.enemy));
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON, "far away, not doomed");
            s.ticks(30);
            s.finishClean();
        }
    }

    @Test
    void bansheeOmenLetsGoOfAnEnemyThatSlipsOutOfSight() {
        try (Scenario s = noGaugeGain(Scenario.builder("banshee")).start()) {
            s.enemy.setHealth(50);
            s.assertSucceeded(s.ultimate(), "누가 먼저 갈까");
            s.ticks(5);
            s.owner.hidePlayer(s.ctx().plugin(), s.enemy);
            s.ticks(3);
            assertEquals(1, entities(s, TextDisplay.class).size(), "its countdown is gone");
            ItemDisplay skull = itemDisplays(s, Material.SKELETON_SKULL).getFirst();
            s.ticks(10);
            assertEquals(s.enemy2.getLocation().getX(), skull.getLocation().getX(), 0.05, "the skull went on");
            s.ticks(s.paramTicks("omen_duration"));
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "out of sight, out of the race");
            assertEquals(s.param("omen_solo"), magicDamage(s, s.enemy2), EPSILON, "the last one left goes alone");
            s.owner.showPlayer(s.ctx().plugin(), s.enemy);
            s.ticks(30);
            s.finishClean();
        }
    }

    // ================================================================== 빨간 글씨 cursenote

    /** The owner-only name labels: text displays the owner sees and the enemies do not. */
    private static List<TextDisplay> nameLabels(Scenario s) {
        return entities(s, TextDisplay.class).stream()
                .filter(label -> s.owner.canSee(label) && !s.enemy.canSee(label) && !s.enemy2.canSee(label))
                .toList();
    }

    /** The public curse labels: text displays everyone sees. */
    private static List<TextDisplay> curseLabels(Scenario s) {
        return entities(s, TextDisplay.class).stream()
                .filter(label -> s.owner.canSee(label) && s.enemy.canSee(label) && s.enemy2.canSee(label))
                .toList();
    }

    @Test
    void cursenoteLearnsTheNamesOfThoseItFoughtAndShowsThemOnlyToItsOwner() {
        try (Scenario s = Scenario.ffa("cursenote")) {
            s.ticks(5);
            assertTrue(nameLabels(s).isEmpty(), "no names yet");
            s.melee(s.owner, s.enemy, 1);
            s.ticks(1);
            assertEquals(1, nameLabels(s).size(), "hitting an enemy teaches its name");
            TextDisplay label = nameLabels(s).getFirst();
            assertTrue(Scenario.plain(label.text()).contains("Red"), Scenario.plain(label.text()));
            assertTrue(label.getLocation().getY() > s.enemy.getEyeLocation().getY(), "over its head");
            assertFalse(label.isSeeThrough(), "not through walls without the ultimate");
            s.melee(s.enemy2, s.owner, 1);
            s.ticks(1);
            assertEquals(2, nameLabels(s).size(), "being hit teaches the attacker's name");
            assertAllUnrotated(s, "names");
            s.ticks(s.paramTicks("name_duration") + 2);
            assertTrue(nameLabels(s).isEmpty(), "names are forgotten after " + s.param("name_duration") + " s");
            s.finishClean();
        }
    }

    @Test
    void cursenoteForgetsTheOldestNameBeyondItsLimit() {
        try (Scenario s = Scenario.builder("cursenote").param("name_max", 1).start()) {
            s.melee(s.owner, s.enemy, 1);
            s.ticks(2);
            s.melee(s.owner, s.enemy2, 1);
            s.ticks(2);
            assertEquals(1, nameLabels(s).size());
            assertTrue(Scenario.plain(nameLabels(s).getFirst().text()).contains("Blue"), "the newest name stays");
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "이름 적기");
            s.ticks(12);
            assertTrue(Scenario.plain(curseLabels(s).getFirst().text()).contains("Blue"),
                    "the aimed name is forgotten, so the known one is written");
            s.ticks(s.paramTicks("write_countdown") - 10);
            assertEquals(s.param("write_damage"), magicDamage(s, s.enemy2), EPSILON, "the known one is cursed");
            assertEquals(0, s.damageTaken(s.enemy) - 1, EPSILON, "the forgotten enemy is safe");
            s.finishClean();
        }
    }

    @Test
    void cursenoteKeepsTheNameOfTheDuelGoingOnAndForgetsTheOneLeastLatelyFought() {
        try (Scenario s = Scenario.builder("cursenote").enemyAbility("summoner").param("name_max", 2).start()) {
            Wolf wolf = callWolves(s, new double[][] {{-3.5, 5.5}, {4.5, 5.5}}).getFirst();
            s.melee(s.owner, s.enemy, 1);
            s.ticks(12);
            s.melee(s.owner, s.enemy2, 1);
            s.ticks(12);
            // The duel with Red goes on: Blue is the name least lately fought now.
            s.melee(s.enemy, s.owner, 1);
            s.ticks(12);
            s.melee(s.owner, wolf, 1);
            s.ticks(2);
            List<String> names = nameLabels(s).stream().map(label -> Scenario.plain(label.text())).toList();
            assertEquals(2, names.size(), "two names at most: " + names);
            assertTrue(names.stream().anyMatch(name -> name.contains("Red")),
                    "the duel partner is remembered: " + names);
            assertTrue(names.stream().noneMatch(name -> name.contains("Blue")), "Blue is forgotten: " + names);
            s.finishClean();
        }
    }

    @Test
    void cursenoteUnderworldSightNeedsSomeoneToSee() {
        try (Scenario s = noGaugeGain(Scenario.builder("cursenote")).start()) {
            s.place(s.enemy, 0.5, 45.5, 180);
            s.place(s.enemy2, 45.5, 0.5, 90);
            s.match.setGauge(s.owner, 100);
            assertFalse(s.match.useUltimate(s.owner).succeeded(), "nobody within sight");
            assertEquals(100, gauge(s, s.owner), EPSILON, "the gauge is kept");
            assertTrue(blockDisplays(s, Material.GRAY_STAINED_GLASS).isEmpty(), "no ink ghost");
            s.ticks(s.paramTicks("sight_duration") + 2);
            s.place(s.enemy, 0.5, 4.5, 180);
            s.melee(s.owner, s.enemy, 1);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "the ink never ran dry");
            s.ticks(s.paramTicks("write_countdown") + 5);
            s.finishClean();
        }
    }

    @Test
    void cursenoteCannotWriteANameItDoesNotKnow() {
        try (Scenario s = Scenario.ffa("cursenote")) {
            s.aimAt(s.owner, s.enemy);
            ActivationResult result = s.active();
            assertFalse(result.succeeded(), "a stranger's name cannot be written");
            assertEquals(0, s.ctx().cooldownRemaining(s.owner), EPSILON, "no ink spent");
            assertNoDisplays(s, "nothing written");
            s.finishClean();
        }
    }

    @Test
    void cursenoteWrittenNameCountsDownAndTheInkSpills() {
        try (Scenario s = Scenario.ffa("cursenote")) {
            s.melee(s.owner, s.enemy, 1);
            s.melee(s.owner, s.enemy2, 1);
            s.clearHits();
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "이름 적기");
            s.ticks(2);
            assertEquals(1, curseLabels(s).size(), "the red name is there for everyone");
            TextDisplay curse = curseLabels(s).getFirst();
            assertTrue(curse.getLocation().getY() > s.enemy.getEyeLocation().getY(), "over the cursed head");
            assertEquals(s.enemy.getLocation().getX(), curse.getLocation().getX(), 0.05, "the aimed enemy's");
            assertEquals(1, itemDisplays(s, Material.WRITABLE_BOOK).size(), "the notebook opens");
            s.ticks(12);
            assertTrue(Scenario.plain(curse.text()).contains("Red"), "the name is written out: " + Scenario.plain(curse.text()));
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "the countdown runs");
            s.ticks(s.paramTicks("write_countdown") - 13);
            assertEquals(s.param("write_damage"), magicDamage(s, s.enemy), EPSILON, "the ink spills");
            assertTrue(rooted(s.enemy), "and fells it");
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON);
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.ticks(s.paramTicks("write_down") + 2);
            assertFalse(rooted(s.enemy), "it gets up again");
            s.ticks(30);
            assertTrue(curseLabels(s).isEmpty(), "the written name fades");
            s.finishClean();
        }
    }

    @Test
    void cursenoteCursedThatHitsTheWriterTearsThePage() {
        try (Scenario s = Scenario.ffa("cursenote")) {
            s.melee(s.owner, s.enemy, 1);
            s.clearHits();
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "이름 적기");
            s.ticks(20);
            s.melee(s.enemy, s.owner, 2);
            s.ticks(1);
            assertTrue(entities(s, TextDisplay.class).stream().anyMatch(t -> Scenario.plain(t.text())
                    .contains("작가를 찾았다")), "작가를 찾았다! over the writer");
            assertTrue(s.owner.getAttribute(Attribute.MOVEMENT_SPEED).getModifiers().stream()
                    .anyMatch(modifier -> modifier.getAmount() < 0), "the writer stumbles");
            s.ticks(s.paramTicks("write_countdown"));
            assertEquals(0, magicDamage(s, s.enemy), EPSILON, "the curse was void");
            assertFalse(rooted(s.enemy));
            assertModifierFree(s.owner, "the stumble is over");
            s.ticks(20);
            s.finishClean();
        }
    }

    @Test
    void cursenoteInkComesBackWhenAWrittenNameFalls() {
        try (Scenario s = Scenario.ffa("cursenote")) {
            s.melee(s.owner, s.enemy2, 1);
            s.aimAt(s.owner, s.enemy2);
            s.assertSucceeded(s.active(), "이름 적기");
            s.ticks(10);
            assertTrue(s.ctx().cooldownRemaining(s.owner) > 0);
            s.session.eliminate(s.enemy2, null);
            s.ticks(2);
            assertEquals(0, s.ctx().cooldownRemaining(s.owner), EPSILON, "the ink came back");
            s.finishMatch();
            s.assertNoProblems();
            s.assertCleanWorld();
        }
    }

    @Test
    void cursenoteNeverWritesOrShowsAnEnemyHiddenFromIt() {
        try (Scenario s = Scenario.ffa("cursenote")) {
            s.melee(s.owner, s.enemy, 1);
            s.ticks(1);
            s.owner.hidePlayer(s.ctx().plugin(), s.enemy);
            s.ticks(1);
            TextDisplay label = nameLabels(s).getFirst();
            assertEquals(0, label.getViewRange(), EPSILON, "its name does not give it away");
            assertFalse(s.active().succeeded(), "a hidden enemy's name cannot be written");
            s.owner.showPlayer(s.ctx().plugin(), s.enemy);
            s.ticks(1);
            assertTrue(label.getViewRange() > 0);
            s.finishClean();
        }
    }

    @Test
    void cursenoteUnderworldSightKnowsEveryoneWritesFastThenRunsDry() {
        try (Scenario s = noGaugeGain(Scenario.builder("cursenote")).start()) {
            s.place(s.enemy2, 20.5, 10.5, 180);
            s.assertSucceeded(s.ultimate(), "저승의 시력");
            s.ticks(1);
            assertEquals(2, nameLabels(s).size(), "every enemy around is known at once");
            for (TextDisplay label : nameLabels(s)) {
                assertTrue(label.isSeeThrough(), "and seen through walls");
            }
            assertEquals(2, blockDisplays(s, Material.GRAY_STAINED_GLASS).size(), "the ink ghost looms");
            assertAllUnrotated(s, "ghost");
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "이름 적기");
            assertEquals(s.param("sight_cooldown"), s.ctx().cooldownRemaining(s.owner), 0.06, "a quick pen");
            s.ticks(s.paramTicks("sight_cooldown") + 1);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "the other name (one name per enemy at a time)");
            s.ticks(2);
            assertEquals(2, curseLabels(s).size(), "two names in red");
            s.ticks(s.paramTicks("sight_countdown") - s.paramTicks("sight_cooldown") - 2);
            assertEquals(s.param("write_damage"), magicDamage(s, s.enemy), EPSILON, "the shorter countdown");
            s.ticks(s.paramTicks("sight_duration"));
            assertEquals(s.param("write_damage"), magicDamage(s, s.enemy2), EPSILON);
            s.readyActive();
            ActivationResult dry = s.active();
            assertFalse(dry.succeeded(), "the ink has run dry");
            assertTrue(blockDisplays(s, Material.GRAY_STAINED_GLASS).isEmpty(), "the ghost is gone");
            s.ticks(s.paramTicks("dry_duration"));
            s.melee(s.owner, s.enemy, 1);
            s.readyActive();
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "the ink is back");
            s.ticks(s.paramTicks("write_countdown") + 5);
            s.finishClean();
        }
    }

    // ================================================================== 카론 charon

    @Test
    void charonChannelCarriesTheEnemiesInItTowardsHim() {
        try (Scenario s = Scenario.ffa("charon")) {
            s.place(s.enemy, 0.5, 9.5, 180);
            s.place(s.enemy2, 6.5, 9.5, 180);
            s.assertSucceeded(s.active(), "스틱스 물길");
            s.ticks(8);
            List<BlockDisplay> water = blockDisplays(s, Material.CYAN_STAINED_GLASS);
            assertEquals((int) Math.ceil((s.param("channel_length") + 1) / 2), water.size(),
                    "a sheet of ghost water every two blocks");
            for (BlockDisplay sheet : water) {
                assertEquals(FlatWorld.SURFACE, sheet.getLocation().getY(), 1.0E-6, "lying on the ground");
                assertEquals(0.5, sheet.getLocation().getX(), 1.0E-6, "straight ahead");
                assertUnrotated(sheet, "water");
            }
            assertTrue(slowed(s.enemy), "drowned hands hold its legs");
            assertFalse(slowed(s.enemy2), "out of the river");
            double z = s.enemy.getLocation().getZ();
            s.ticks(20);
            double carried = z - s.enemy.getLocation().getZ();
            assertEquals(s.param("channel_current"), carried, 0.6, "carried towards Charon at the current's speed");
            assertEquals(0.5, s.enemy.getLocation().getX(), 0.01, "straight down the river");
            s.ticks(s.paramTicks("channel_duration"));
            double hits = s.param("channel_damage") * s.param("channel_max_hits");
            assertEquals(hits, magicDamage(s, s.enemy), EPSILON, "the hands drag it under, at most "
                    + s.param("channel_max_hits") + " times");
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON);
            assertTrue(s.enemy.getLocation().getZ() < 3, "it ended up at Charon's feet: " + s.enemy.getLocation());
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.ticks(12);
            assertNoDisplays(s, "the river dried up");
            assertModifierFree(s.enemy, "the hands let go");
            s.finishClean();
        }
    }

    @Test
    void charonSneakingChannelFlowsAwayFromHim() {
        try (Scenario s = Scenario.ffa("charon")) {
            s.place(s.enemy, 0.5, 5.5, 180);
            s.place(s.enemy2, 8.5, 9.5, 180);
            s.owner.setSneaking(true);
            s.assertSucceeded(s.active(), "스틱스 물길 (도주로)");
            s.owner.setSneaking(false);
            double z = s.enemy.getLocation().getZ();
            s.ticks(30);
            assertTrue(s.enemy.getLocation().getZ() > z + 1.5, "carried away from Charon: " + s.enemy.getLocation());
            assertTrue(slowed(s.enemy), "still gripped by the hands");
            s.ticks(s.paramTicks("channel_duration"));
            s.finishClean();
        }
    }

    @Test
    void charonRiverFollowsTheGroundAndEndsAtAWall() {
        try (Scenario s = Scenario.ffa("charon")) {
            s.place(s.enemy, 8.5, 12.5, 180);
            s.place(s.enemy2, -8.5, 12.5, 180);
            s.world.wallAlongX(-3, 3, 7, 3, Material.STONE);
            // A one-block step up halfway to the wall.
            for (int x = -3; x <= 3; x++) {
                for (int z = 4; z <= 6; z++) {
                    s.world.setBlock(x, FlatWorld.FLOOR_Y + 1, z, Material.STONE);
                }
            }
            s.assertSucceeded(s.active(), "스틱스 물길");
            s.ticks(8);
            List<BlockDisplay> water = blockDisplays(s, Material.CYAN_STAINED_GLASS);
            assertFalse(water.isEmpty());
            for (BlockDisplay sheet : water) {
                assertTrue(sheet.getLocation().getZ() < 7, "never through the wall: " + sheet.getLocation());
                double ground = sheet.getLocation().getZ() > 4 ? FlatWorld.SURFACE + 1 : FlatWorld.SURFACE;
                assertEquals(ground, sheet.getLocation().getY(), 1.0E-6, "over the ground it flows on: "
                        + sheet.getLocation());
            }
            assertTrue(water.stream().anyMatch(sheet -> sheet.getLocation().getY() > FlatWorld.SURFACE + 0.5),
                    "it climbs the step");
            s.ticks(s.paramTicks("channel_duration") + 12);
            s.world.wallAlongX(-3, 3, 7, 3, Material.AIR);
            for (int x = -3; x <= 3; x++) {
                for (int z = 4; z <= 6; z++) {
                    s.world.setBlock(x, FlatWorld.FLOOR_Y + 1, z, Material.AIR);
                }
            }
            s.finishClean();
        }
    }

    @Test
    void charonCurrentNeverReplaysAStaleVelocity() {
        try (Scenario s = Scenario.ffa("charon")) {
            s.place(s.enemy, 0.5, 9.5, 180);
            s.place(s.enemy2, 8.5, 9.5, 180);
            s.assertSucceeded(s.active(), "스틱스 물길");
            s.ticks(3);
            s.world.physics().setEnabled(false);
            // A real server never reads a player's motion back: its velocity is what it last sent (an old knockback,
            // here). The current must set a fresh one, straight down the river.
            s.enemy.setVelocity(new org.bukkit.util.Vector(1.2, 0.4, 0.9));
            s.ticks(2);
            org.bukkit.util.Vector tug = s.enemy.getVelocity();
            assertEquals(0, tug.getX(), EPSILON, "no stale sideways push: " + tug);
            assertTrue(tug.getZ() < 0, "carried towards Charon: " + tug);
            assertTrue(tug.getY() <= 0, "never lifted: " + tug);
            s.world.physics().setEnabled(true);
            s.ticks(s.paramTicks("channel_duration"));
            s.finishClean();
        }
    }

    @Test
    void charonCannotDrawARiverIntoAWall() {
        try (Scenario s = Scenario.ffa("charon")) {
            s.world.wallAlongX(-2, 2, 1, 3, Material.STONE);
            assertFalse(s.active().succeeded(), "no room for a river");
            assertEquals(0, s.ctx().cooldownRemaining(s.owner), EPSILON);
            assertNoDisplays(s, "nothing drawn");
            s.world.wallAlongX(-2, 2, 1, 3, Material.AIR);
            s.finishClean();
        }
    }

    @Test
    void charonAndHisAlliesGoFasterWithTheCurrentAndNobodyIsPushedOnHisSide() {
        try (Scenario s = Scenario.teams("charon")) {
            s.place(s.enemy, 8.5, 12.5, 180);
            s.place(s.enemy2, -8.5, 12.5, 180);
            s.place(s.ally(), 0.5, 6.5, 0);
            s.owner.setSneaking(true);
            s.assertSucceeded(s.active(), "스틱스 물길 (도주로)");
            s.owner.setSneaking(false);
            s.ticks(3);
            double bonus = s.param("row_speed") / 100;
            assertEquals(bonus, speedBonus(s.ally()), EPSILON, "the ally faces downstream: faster");
            assertEquals(bonus, speedBonus(s.owner), EPSILON, "and so does Charon");
            Location still = s.ally().getLocation();
            s.ticks(20);
            assertTrue(s.ally().getLocation().distance(still) < 1.0E-6, "a standing ally is never pushed");
            s.place(s.ally(), 0.5, 6.5, 180);
            s.ticks(2);
            assertEquals(0, speedBonus(s.ally()), EPSILON, "facing upstream: no faster, and no slower");
            s.place(s.ally(), 6.5, 6.5, 0);
            s.ticks(2);
            assertEquals(0, speedBonus(s.ally()), EPSILON, "out of the river");
            s.ticks(s.paramTicks("channel_duration"));
            assertModifierFree(s.owner, "the river is gone");
            assertModifierFree(s.ally(), "the river is gone");
            s.finishClean();
        }
    }

    @Test
    void charonTakesLessDamageStandingOnHisRiver() {
        try (Scenario s = Scenario.ffa("charon")) {
            s.place(s.enemy, 8.5, 12.5, 180);
            s.place(s.enemy2, -8.5, 12.5, 180);
            s.melee(s.enemy, s.owner, 10);
            assertEquals(10, s.damageTaken(s.owner), EPSILON, "on dry land");
            s.healAll();
            s.clearHits();
            s.assertSucceeded(s.active(), "스틱스 물길");
            s.ticks(2);
            s.melee(s.enemy, s.owner, 10);
            assertEquals(10 * (1 - s.param("row_guard") / 100), s.damageTaken(s.owner), EPSILON, "on his river");
            s.ticks(s.paramTicks("channel_duration"));
            s.finishClean();
        }
    }

    @Test
    void charonStyxChargesTheFareToEveryoneWhoCrossesIt() {
        try (Scenario s = noGaugeGain(Scenario.builder("charon")).start()) {
            s.place(s.enemy, 5.5, 10.5, 90);
            s.place(s.enemy2, 5.5, 14.5, 90);
            s.owner.setSneaking(true);
            s.assertSucceeded(s.ultimate(), "뱃삯을 내라");
            s.ticks(3);
            assertEquals(3, blockDisplays(s, Material.DARK_OAK_PLANKS).size(), "the boat's hull, bow and stern");
            assertEquals(1, itemDisplays(s, Material.SOUL_LANTERN).size(), "with a soul lantern on its pole");
            assertAllUnrotated(s, "styx");
            s.match.setGauge(s.enemy, 50);
            s.match.setGauge(s.enemy2, 10);
            // The rich one crosses from the east bank to the west bank.
            run(s, s.enemy, 5.5, 10.5, -4.5, 10.5, 0.4);
            s.ticks(2);
            assertEquals(50 - s.param("fare_gauge"), gauge(s, s.enemy), EPSILON, "the fare is ultimate gauge");
            // The poor one pays in health.
            run(s, s.enemy2, 5.5, 14.5, -4.5, 14.5, 0.4);
            s.ticks(2);
            assertEquals(1, s.hitsBy(s.owner, s.enemy2).stream()
                    .filter(hit -> Math.abs(hit.damage() - s.param("fare_damage")) < EPSILON).count(),
                    "no gauge, so the fare is health: " + s.hitsBy(s.owner, s.enemy2));
            assertEquals(10, gauge(s, s.enemy2), EPSILON);
            // In and out on the same bank costs nothing.
            double before = gauge(s, s.enemy);
            run(s, s.enemy, -4.5, 10.5, -1.5, 10.5, 0.4);
            run(s, s.enemy, -1.5, 10.5, -4.5, 10.5, 0.4);
            s.ticks(2);
            assertEquals(before, gauge(s, s.enemy), EPSILON, "no crossing, no fare");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.owner.setSneaking(false);
            s.ticks(s.paramTicks("styx_duration"));
            s.ticks(12);
            assertNoDisplays(s, "the Styx flowed away");
            s.finishClean();
        }
    }

    @Test
    void charonFareFromAPayerHiddenFromHimGivesNothingAway() {
        try (Scenario s = noGaugeGain(Scenario.builder("charon")).start()) {
            s.place(s.enemy, 5.5, 10.5, 90);
            s.place(s.enemy2, 5.5, 20.5, 90);
            s.owner.setSneaking(true);
            s.assertSucceeded(s.ultimate(), "뱃삯을 내라");
            s.ticks(3);
            s.match.setGauge(s.enemy, 50);
            s.owner.hidePlayer(s.ctx().plugin(), s.enemy);
            actionBars(s.owner);
            int texts = entities(s, TextDisplay.class).size();
            run(s, s.enemy, 5.5, 10.5, -4.5, 10.5, 0.4);
            s.ticks(2);
            assertEquals(50 - s.param("fare_gauge"), gauge(s, s.enemy), EPSILON, "the fare is still due");
            assertTrue(actionBars(s.owner).stream().noneMatch(bar -> bar.contains(s.enemy.getName())),
                    "Charon is not told who crossed unseen");
            assertEquals(texts, entities(s, TextDisplay.class).size(), "no fare word over the unseen payer");
            assertTrue(itemDisplays(s, Material.GOLD_NUGGET).isEmpty(), "no coin flying from it");
            s.owner.showPlayer(s.ctx().plugin(), s.enemy);
            s.owner.setSneaking(false);
            s.ticks(s.paramTicks("styx_duration") + 12);
            s.finishClean();
        }
    }

    @Test
    void charonBoatStopsBeforeTheDropAtTheEndOfTheStyx() {
        for (int ping : new int[] {0, 100}) {
            try (Scenario s = noGaugeGain(Scenario.builder("charon")).start()) {
                s.place(s.enemy, 8.5, 4.5, 180);
                s.place(s.enemy2, -8.5, 4.5, 180);
                s.owner.setPing(ping);
                // A deep pit across the way: the Styx ends on its brink (z 9.5), the drop starts at z 10.
                pit(s, -6, 6, 10, 13, Material.AIR);
                s.assertSucceeded(s.ultimate(), "뱃삯을 내라");
                s.ticks(50);
                Location at = s.owner.getLocation();
                assertOnSurface(s.owner, "ping " + ping + ": the boat stopped on the brink");
                assertTrue(at.getZ() - 0.3 < 10, "ping " + ping + ": never out over the drop: " + at);
                assertTrue(at.getZ() > 6, "ping " + ping + ": it rowed down the Styx: " + at);
                double speed = s.param("boat_speed") / 20;
                long lag = (long) Math.ceil(ping / 50.0);
                assertTrue(at.getZ() <= 9.5 - 2 * speed * lag + 1.0E-6,
                        "ping " + ping + ": short of the end by what his client may be ahead: " + at);
                assertEquals(0, s.damageTaken(s.owner), EPSILON, "ping " + ping + ": no fall");
                s.ticks(s.paramTicks("styx_duration"));
                pit(s, -6, 6, 10, 13, Material.STONE);
                s.finishClean();
            }
        }
    }

    @Test
    void charonBoatNeverRowsOffABankIntoAPit() {
        try (Scenario s = noGaugeGain(Scenario.builder("charon")).start()) {
            s.place(s.enemy, -8.5, 4.5, 180);
            s.place(s.enemy2, -8.5, 8.5, 180);
            // A pit right beside the Styx (its east bank lies at x 3.0, the pit starts at x 3).
            pit(s, 3, 7, 0, 12, Material.AIR);
            s.owner.setSneaking(true);
            s.assertSucceeded(s.ultimate(), "뱃삯을 내라");
            s.ticks(2);
            // Charon turns to the east bank and rows across.
            s.place(s.owner, 0.5, 5.5, -90);
            s.owner.setSneaking(false);
            s.ticks(30);
            Location at = s.owner.getLocation();
            assertOnSurface(s.owner, "the boat stopped at the bank");
            assertTrue(at.getX() - 0.3 < 3, "never out over the pit: " + at);
            assertTrue(at.getX() > 1.5, "it rowed across: " + at);
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.ticks(s.paramTicks("styx_duration"));
            pit(s, 3, 7, 0, 12, Material.STONE);
            s.finishClean();
        }
    }

    @Test
    void charonStyxLaunchesItsBoatEvenNextToAFlowingChannel() {
        try (Scenario s = noGaugeGain(Scenario.builder("charon")).start()) {
            s.place(s.enemy, 8.5, 4.5, 180);
            s.place(s.enemy2, -8.5, 4.5, 180);
            s.assertSucceeded(s.active(), "스틱스 물길");
            s.ticks(3);
            assertFalse(blockDisplays(s, Material.CYAN_STAINED_GLASS).isEmpty(), "the channel flows");
            s.owner.setSneaking(true);
            s.assertSucceeded(s.ultimate(), "뱃삯을 내라");
            s.ticks(3);
            assertEquals(3, blockDisplays(s, Material.DARK_OAK_PLANKS).size(), "the boat is there");
            assertEquals(1, itemDisplays(s, Material.SOUL_LANTERN).size(), "with its lantern");
            assertTrue(entities(s, Display.class).size() <= 24, "in budget");
            int styxSheets = (int) blockDisplays(s, Material.CYAN_STAINED_GLASS).stream()
                    .filter(sheet -> sheet.getTransformation().getScale().x() > s.param("channel_width") + 0.5)
                    .count();
            assertTrue(styxSheets > 0, "and the Styx's water");
            s.owner.setSneaking(false);
            s.ticks(s.paramTicks("styx_duration") + 12);
            s.finishClean();
        }
    }

    @Test
    void charonStyxDragsEnemiesDownstreamAndCharonRowsWhereHeLooks() {
        try (Scenario s = noGaugeGain(Scenario.builder("charon")).start()) {
            s.place(s.enemy, 0.5, 6.5, 180);
            s.place(s.enemy2, 12.5, 6.5, 180);
            s.owner.setSneaking(true);
            s.assertSucceeded(s.ultimate(), "뱃삯을 내라");
            s.ticks(20);
            double z = s.enemy.getLocation().getZ();
            assertTrue(z > 6.5 + 1, "dragged downstream: " + s.enemy.getLocation());
            Location boat = blockDisplays(s, Material.DARK_OAK_PLANKS).getFirst().getLocation();
            assertTrue(boat.distance(s.owner.getLocation()) < 0.05, "the boat is under Charon");
            assertTrue(s.owner.getLocation().distance(new Location(s.world, 0.5, FlatWorld.SURFACE, 0.5)) < 0.1,
                    "sneaking: no rowing");
            s.owner.setSneaking(false);
            s.ticks(10);
            double rowed = s.owner.getLocation().getZ() - 0.5;
            assertTrue(rowed > s.param("boat_speed") * 0.5 * 0.7, "rowing down the Styx: " + rowed);
            assertEquals(0.5, s.owner.getLocation().getX(), 0.01, "where Charon looks");
            assertOnSurface(s.owner, "rowing");
            s.ticks(s.paramTicks("styx_duration"));
            assertTrue(magicDamage(s, s.enemy) > 0, "the Styx hurts");
            assertTrue(magicDamage(s, s.enemy) <= s.param("styx_damage") * s.param("styx_max_hits") + EPSILON,
                    "at most " + s.param("styx_max_hits") + " times");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.ticks(12);
            assertNoDisplays(s, "boat and river are gone");
            s.finishClean();
        }
    }
}
