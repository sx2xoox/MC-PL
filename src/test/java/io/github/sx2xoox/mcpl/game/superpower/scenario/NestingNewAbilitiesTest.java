package io.github.sx2xoox.mcpl.game.superpower.scenario;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.sx2xoox.mcpl.game.superpower.abilities.MatryoshkaAbility;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import io.github.sx2xoox.mcpl.game.superpower.support.Scenario;
import io.github.sx2xoox.mcpl.game.superpower.support.TestPlayer;
import io.github.sx2xoox.mcpl.game.superpower.support.world.FlatWorld;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
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
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 몸 · 변신 (추가): 마트료시카. */
class NestingNewAbilitiesTest {

    private static final String ID = "matryoshka";
    private static final double EPSILON = 1.0E-6;
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z0-9_]+)}");
    /** Ticks from a crack until its halves have stood up as cover (they fly for 4 ticks). */
    private static final int COVER_TICKS = 8;

    /**
     * {@link AbilityLifecycleTest} reads the ability registry in its static initializer, which starts a mock server of
     * its own; initialize it before any scenario runs (see RogueNewAbilitiesTest).
     */
    @BeforeAll
    static void initializeSharedSweeps() {
        assertTrue(AbilityLifecycleTest.abilities().anyMatch(ID::equals), "the matryoshka is swept");
    }

    // ================================================================== helpers

    private static <T extends Entity> List<T> entities(Scenario s, Class<T> type) {
        return s.world.getEntities().stream().filter(type::isInstance).map(type::cast).toList();
    }

    /** Every display but the shell badge over the owner's head is gone. */
    private static void assertOnlyBadge(Scenario s, String what) {
        TextDisplay badge = badge(s);
        List<Display> left = entities(s, Display.class).stream().filter(d -> !d.equals(badge)).toList();
        assertTrue(left.isEmpty(), what + ": every display is gone: "
                + left.stream().map(d -> d.getType() + "@" + d.getLocation().toVector()).toList());
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

    /** Where a display's model points its +Z (the dolls' faces look along their +Z). */
    private static Vector3f front(Display display) {
        Quaternionf left = display.getTransformation().getLeftRotation();
        return left.transform(new Vector3f(0, 0, 1));
    }

    private static Scenario.Builder noGaugeGain(Scenario.Builder builder) {
        return builder.configure(h -> {
            h.settings().set("ultimate.per-damage-dealt", 0);
            h.settings().set("ultimate.per-damage-taken", 0);
            h.settings().set("ultimate.per-second", 0);
        });
    }

    private static double scale(Player player) {
        return player.getAttribute(Attribute.SCALE).getValue();
    }

    /** The shells the owner wears, read from its size. */
    private static int shells(Scenario s) {
        return (int) Math.round((scale(s.owner) - s.param("scale_core")) / s.param("scale_step"));
    }

    /** The owner's speed as a share of its base speed. */
    private static double speedShare(Player player) {
        AttributeInstance speed = player.getAttribute(Attribute.MOVEMENT_SPEED);
        return speed.getValue() / speed.getBaseValue();
    }

    /** The shell count over the owner's head (riding it, or kept over it while it cannot). */
    private static TextDisplay badge(Scenario s) {
        List<TextDisplay> badges = entities(s, TextDisplay.class).stream()
                .filter(text -> {
                    String plain = Scenario.plain(text.text());
                    return plain.contains("✿") || plain.contains("알맹이") || plain.isEmpty();
                })
                .filter(text -> s.owner.equals(text.getVehicle())
                        || text.getLocation().getY() > s.owner.getLocation().getY() + s.owner.getHeight())
                .toList();
        assertEquals(1, badges.size(), "one shell badge over the owner's head: " + badges.size());
        return badges.getFirst();
    }

    private static String badgeText(Scenario s) {
        return Scenario.plain(badge(s).text());
    }

    private static long count(String text, char glyph) {
        return text.chars().filter(c -> c == glyph).count();
    }

    /** A blow from {@code attacker} that lands whole (no invulnerability frames left). */
    private static void blow(Scenario s, Player attacker, double amount) {
        s.melee(attacker, s.owner, amount);
    }

    /** The fraction of a hit the owner takes with {@code shells} shells. */
    private static double guard(Scenario s, int shells) {
        return shells > 0 ? 1 - s.param("shell_guard") / 100 * shells : 1 + s.param("core_weakness") / 100;
    }

    /** Cracks one shell with a blow from the aimed enemy, then waits out the grace and the halves' flight. */
    private static void crackOnce(Scenario s) {
        int before = shells(s);
        s.healAll();
        blow(s, s.enemy, 10);
        assertEquals(before - 1, shells(s), "a hard blow cracks one shell");
        s.ticks(Math.max(COVER_TICKS, s.paramTicks("crack_grace") + 2));
    }

    /** Non-air blocks in the area around the origin (above the floor). */
    private static List<Block> placedBlocks(Scenario s) {
        List<Block> found = new ArrayList<>();
        for (int x = -10; x <= 10; x++) {
            for (int z = -10; z <= 14; z++) {
                for (int y = FlatWorld.FLOOR_Y + 1; y <= FlatWorld.FLOOR_Y + 6; y++) {
                    Block block = s.world.getBlockAt(x, y, z);
                    if (!block.getType().isAir()) {
                        found.add(block);
                    }
                }
            }
        }
        return found;
    }

    private static void wall(Scenario s, int z, Material type) {
        for (int x = -4; x <= 4; x++) {
            for (int y = 1; y <= 4; y++) {
                s.world.getBlockAt(x, FlatWorld.FLOOR_Y + y, z).setType(type);
            }
        }
    }

    private static List<BlockDisplay> blockDisplays(Scenario s, Material type) {
        return entities(s, BlockDisplay.class).stream()
                .filter(display -> display.getBlock().getMaterial() == type)
                .toList();
    }

    /** The lowest point of a block display's model (its unit cube posed by the transformation), in world y. */
    private static double lowest(BlockDisplay display) {
        Transformation t = display.getTransformation();
        double low = Double.MAX_VALUE;
        for (int c = 0; c < 8; c++) {
            Vector3f corner = new Vector3f(c & 1, (c >> 1) & 1, (c >> 2) & 1);
            new Quaternionf(t.getRightRotation()).transform(corner);
            corner.mul(t.getScale());
            new Quaternionf(t.getLeftRotation()).transform(corner);
            corner.add(t.getTranslation());
            low = Math.min(low, corner.y);
        }
        return display.getLocation().getY() + low;
    }

    /** The highest point of a block display's model, in world y. */
    private static double highest(BlockDisplay display) {
        Transformation t = display.getTransformation();
        double high = -Double.MAX_VALUE;
        for (int c = 0; c < 8; c++) {
            Vector3f corner = new Vector3f(c & 1, (c >> 1) & 1, (c >> 2) & 1);
            new Quaternionf(t.getRightRotation()).transform(corner);
            corner.mul(t.getScale());
            new Quaternionf(t.getLeftRotation()).transform(corner);
            corner.add(t.getTranslation());
            high = Math.max(high, corner.y);
        }
        return display.getLocation().getY() + high;
    }

    /** Minecraft's yaw (0 = south, 90 = west) of the way a display's model faces. */
    private static double facingYaw(Display display) {
        Vector3f look = front(display);
        return Math.toDegrees(Math.atan2(-look.x, look.z));
    }

    /** The angle in degrees between two display poses (their left rotations). */
    private static double turn(Quaternionf from, Quaternionf to) {
        double dot = Math.abs(from.dot(to)) / Math.sqrt(from.lengthSquared() * to.lengthSquared());
        return Math.toDegrees(2 * Math.acos(Math.min(1, dot)));
    }

    /** For {@code ticks} ticks, no block display's model reaches into the floor. */
    private static void watchFloor(Scenario s, int ticks, String what) {
        for (int i = 0; i < ticks; i++) {
            s.ticks(1);
            for (BlockDisplay display : entities(s, BlockDisplay.class)) {
                double low = lowest(display);
                assertTrue(low >= FlatWorld.SURFACE - 0.03, what + ": a " + display.getBlock().getMaterial()
                        + " model sinks " + (FlatWorld.SURFACE - low) + " into the floor at tick " + i + " ("
                        + display.getLocation().toVector() + ")");
            }
        }
    }

    private static List<String> actionBars(TestPlayer player) {
        List<String> bars = new ArrayList<>();
        Component bar;
        while ((bar = player.nextActionBar()) != null) {
            bars.add(Scenario.plain(bar));
        }
        return bars;
    }

    /** True if a lacquer-red doll display is within {@code reach} of {@code player}'s body. */
    private static boolean dollNear(Scenario s, Player player, double reach) {
        Location body = player.getLocation().add(0, player.getHeight() * 0.5, 0);
        return blockDisplays(s, Material.RED_CONCRETE).stream()
                .anyMatch(display -> display.getLocation().distance(body) <= reach);
    }

    /** Ticks until the owner has hit {@code victim}, at most {@code max} ticks; true if it did. */
    private static boolean tickUntilHit(Scenario s, Player victim, int max) {
        for (int i = 0; i < max && s.damageBy(s.owner, victim) <= 0; i++) {
            s.ticks(1);
        }
        return s.damageBy(s.owner, victim) > 0;
    }

    // ================================================================== info

    @Test
    void descriptionsOnlyReferenceDeclaredParams() {
        AbilityInfo info = MatryoshkaAbility.INFO;
        for (AbilityInfo.Skill skill : List.of(info.passive(), info.active(), info.ultimate())) {
            assertFalse(skill.name().contains("TODO"), skill.name());
            Matcher matcher = PLACEHOLDER.matcher(skill.description());
            while (matcher.find()) {
                assertTrue(info.params().containsKey(matcher.group(1)),
                        info.id() + " " + skill.name() + " uses an undeclared param " + matcher.group());
            }
        }
        assertFalse(info.ultimateStandalone(), "the shells and the golden layer live in the granted passive");
        assertEquals(2, info.difficulty());
        assertEquals("마트료시카", info.name());
    }

    // ================================================================== passive: shells

    @Test
    void startsInThreeShellsBigAndSlow() {
        try (Scenario s = Scenario.ffa(ID)) {
            s.ticks(10);
            int max = (int) s.param("shells");
            assertEquals(3, max);
            assertEquals(s.param("scale_core") + s.param("scale_step") * max, scale(s.owner), EPSILON,
                    "three shells: 1.15 times the size");
            assertEquals(1.15, scale(s.owner), EPSILON);
            assertEquals(1 - s.param("shell_slow") / 100 * max, speedShare(s.owner), EPSILON, "and slower");

            TextDisplay badge = badge(s);
            assertEquals(3, count(badgeText(s), '✿'), "three flowers over the head: " + badgeText(s));
            assertFalse(badgeText(s).contains("↻"), "nothing grows back while every shell is on");
            assertEquals(s.owner.getX(), badge.getX(), 0.3);
            assertUnrotated(badge, "badge");
            for (TestPlayer viewer : s.all()) {
                assertTrue(viewer.canSee(badge), viewer.getName() + " reads the shells");
            }
            // It rides the owner's head (every client carries it with the body), its text lifted over it.
            assertEquals(s.owner, badge.getVehicle(), "the badge rides the owner");
            assertEquals(0.75, badge.getTransformation().getTranslation().y, 1.0E-4, "floating over the head");
            s.place(s.owner, 5.5, 2.5, 0);
            s.ticks(2);
            assertEquals(5.5, badge(s).getX(), 0.3, "it goes along with a teleport");
            assertEquals(s.owner, badge(s).getVehicle(), "and stays on");
            s.finishClean();
        }
    }

    @Test
    void theBadgeRidesTheHeadAndGetsBackOnAfterItGotOff() {
        try (Scenario s = noGaugeGain(Scenario.builder(ID)).start()) {
            s.ticks(10);
            TextDisplay badge = badge(s);
            // A crack pops the badge: still lifted over the head the whole way.
            s.healAll();
            blow(s, s.enemy, 10);
            for (int i = 0; i < 10; i++) {
                s.ticks(1);
                assertEquals(s.owner, badge.getVehicle(), "on the head at tick " + i);
                assertEquals(0.75, badge.getTransformation().getTranslation().y, 1.0E-4, "lifted at tick " + i);
            }
            // Knocked off its seat (a respawn, a world change): it gets back on by itself.
            badge.leaveVehicle();
            s.ticks(1);
            assertEquals(s.owner, badge.getVehicle(), "back on the head");
            assertEquals(s.owner.getY() + s.owner.getHeight(), badge.getY(), 1.0E-6, "on top of the body");
            s.finishClean();
        }
    }

    @Test
    void shellsSoftenBlowsAndCrackUnderEnoughOfThem() {
        try (Scenario s = noGaugeGain(Scenario.builder(ID)).start()) {
            s.ticks(10);
            s.clearHits();
            blow(s, s.enemy, 3);
            assertEquals(3 * guard(s, 3), s.damageTaken(s.owner), EPSILON, "three shells take 24% off");
            assertEquals(3, shells(s), "a light blow does not crack a shell");
            String strained = badgeText(s);
            assertEquals(3, count(strained, '✿'));

            // 3 x 2.28 = 6.84 stays under 7, the fourth light blow cracks it.
            blow(s, s.enemy, 3);
            blow(s, s.enemy, 3);
            assertEquals(3, shells(s), "the strain adds up under the limit");
            blow(s, s.enemy, 3);
            assertEquals(2, shells(s), "enough strain cracks the outer shell");
            assertEquals(s.param("scale_core") + s.param("scale_step") * 2, scale(s.owner), EPSILON,
                    "one size smaller");
            assertEquals(1 - s.param("shell_slow") / 100 * 2, speedShare(s.owner), EPSILON, "and quicker");
            s.ticks(2);
            assertTrue(badgeText(s).contains("↻"), "the badge counts down to the next shell: " + badgeText(s));
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void aHardBlowCracksAShellIntoACoverTowardsTheAttacker() {
        try (Scenario s = Scenario.ffa(ID)) {
            s.ticks(10);
            s.clearHits();
            blow(s, s.enemy, 6);
            assertEquals(6 * guard(s, 3), s.damageTaken(s.owner), EPSILON, "the blow itself is still softened");
            assertEquals(2, shells(s), "6 x 0.76 = 4.56 cracks a shell at once");

            // The gold seam of the lower half stays just under its rim (no face in the plane of the rim's top).
            BlockDisplay seam = blockDisplays(s, Material.GOLD_BLOCK).getFirst();
            BlockDisplay base = blockDisplays(s, Material.RED_CONCRETE).stream()
                    .filter(part -> part.getLocation().distance(seam.getLocation()) < 1.0E-6)
                    .findFirst().orElseThrow();
            assertTrue(highest(seam) < highest(base) - 0.003, "the seam band under the rim: " + highest(seam)
                    + " vs " + highest(base));

            // Right away the halves spring off the body, out of the owner's own view.
            s.ticks(1);
            List<BlockDisplay> halves = blockDisplays(s, Material.RED_CONCRETE);
            assertFalse(halves.isEmpty(), "the halves fly off");
            for (BlockDisplay half : halves) {
                assertUnrotated(half, "half");
                assertFalse(s.owner.canSee(half), "not in the owner's face while they leave");
                assertTrue(s.enemy.canSee(half), "everyone else sees them");
                assertEquals(2, half.getTeleportDuration(), "moved every tick: the kit's smooth teleports");
            }
            TextDisplay crackWord = entities(s, TextDisplay.class).stream()
                    .filter(text -> Scenario.plain(text.text()).contains("딱")).findFirst().orElseThrow();
            assertTrue(crackWord.getY() > s.owner.getY() + s.owner.getHeight() + 0.75 + 0.3,
                    "딱! pops over the badge, not on top of it: " + (crackWord.getY() - s.owner.getY()));
            s.ticks(2);
            for (BlockDisplay half : blockDisplays(s, Material.RED_CONCRETE)) {
                assertTrue(s.owner.canSee(half), "the owner sees them once they are off its body");
            }

            s.ticks(COVER_TICKS);
            // The enemy stands 4 blocks ahead (+z): the cover stands 1.5 blocks ahead, between the two.
            Block body = s.world.getBlockAt(0, FlatWorld.FLOOR_Y + 1, 2);
            assertEquals(Material.RED_CONCRETE, body.getType(), "the doll's body");
            assertEquals(Material.RED_CONCRETE, body.getRelative(0, 1, 0).getType(), "its head on top (2 high)");
            assertEquals(Material.RED_GLAZED_TERRACOTTA, body.getRelative(-1, 0, 0).getType(), "an arm");
            assertEquals(Material.RED_GLAZED_TERRACOTTA, body.getRelative(1, 0, 0).getType(), "the other arm");
            assertTrue(s.h.hub.tempBlocks().isTemporary(body), "temporary blocks");
            assertEquals(4, s.h.hub.tempBlocks().size());
            assertTrue(body.getRelative(0, 2, 0).getType().isAir(), "nothing higher");

            // The face on the head looks at the attacker.
            List<BlockDisplay> faces = blockDisplays(s, Material.SMOOTH_QUARTZ);
            assertEquals(1, faces.size(), "a cream face painted on the head");
            BlockDisplay face = faces.getFirst();
            assertUnrotated(face, "face");
            Vector3f look = front(face);
            assertEquals(1, look.z, 1.0E-4, "the face looks south, at the attacker: " + look);
            assertEquals(2, blockDisplays(s, Material.BLACK_CONCRETE).size(), "two eyes");
            assertEquals(1, blockDisplays(s, Material.GOLD_BLOCK).size(), "a gold seam");
            assertTrue(highest(blockDisplays(s, Material.GOLD_BLOCK).getFirst()) < body.getY() + 1 - 0.003,
                    "the seam's top stays under the body block's top face (no film on top of it)");
            Location faceAt = face.getLocation();
            assertEquals(65.5, faceAt.getY(), EPSILON, "on the head block");

            // Four seconds later the doll is gone and the ground is as it was.
            s.ticks(s.paramTicks("cover_duration"));
            assertTrue(body.getType().isAir(), "the cover is gone");
            assertTrue(body.getRelative(0, 1, 0).getType().isAir());
            assertEquals(0, s.h.hub.tempBlocks().size(), "every temporary block came back");
            assertTrue(blockDisplays(s, Material.SMOOTH_QUARTZ).isEmpty(), "the paint went with it");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void theCoverFacesWhereverTheBlowCameFrom() {
        try (Scenario s = Scenario.ffa(ID)) {
            s.ticks(10);
            // The attacker is to the east (+x): the cover stands east, facing east, its arms along z.
            s.place(s.enemy, 4.5, 0.5, 90);
            blow(s, s.enemy, 6);
            s.ticks(COVER_TICKS);
            Block body = s.world.getBlockAt(2, FlatWorld.FLOOR_Y + 1, 0);
            assertEquals(Material.RED_CONCRETE, body.getType(), "1.5 blocks east of the owner");
            assertEquals(Material.RED_GLAZED_TERRACOTTA, body.getRelative(0, 0, 1).getType());
            assertEquals(Material.RED_GLAZED_TERRACOTTA, body.getRelative(0, 0, -1).getType());
            BlockDisplay face = blockDisplays(s, Material.SMOOTH_QUARTZ).getFirst();
            assertUnrotated(face, "face");
            assertEquals(1, front(face).x, 1.0E-4, "it looks east: " + front(face));
            s.finishClean();
        }
    }

    @Test
    void anArrowRaisesTheCoverOnTheSideItCameFrom() {
        try (Scenario s = Scenario.ffa(ID)) {
            s.ticks(10);
            // The archer stands north, but its arrow flies in from the east (flying west): the cover goes east.
            s.place(s.enemy, 0.5, -6.5, 0);
            Arrow arrow = s.world.spawn(s.owner.getLocation().add(0.3, 1, 0), Arrow.class);
            arrow.setShooter(s.enemy);
            arrow.setVelocity(new Vector(-1.5, 0, 0));
            s.owner.setNoDamageTicks(0);
            s.owner.damage(8, DamageSource.builder(DamageType.ARROW).withCausingEntity(s.enemy)
                    .withDirectEntity(arrow).build());
            arrow.remove();
            assertEquals(2, shells(s));
            s.ticks(COVER_TICKS);
            assertEquals(Material.RED_CONCRETE, s.world.getBlockAt(2, FlatWorld.FLOOR_Y + 1, 0).getType(),
                    "the cover stands where the arrow came from");
            assertTrue(s.world.getBlockAt(0, FlatWorld.FLOOR_Y + 1, -1).getType().isAir(), "not towards the archer");
            s.finishClean();
        }
    }

    @Test
    void anInvisibleOwnerShowsNoBadge() {
        try (Scenario s = Scenario.ffa(ID)) {
            s.ticks(10);
            s.owner.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, 100, 0));
            s.ticks(6);
            assertEquals("", badgeText(s), "the badge would give an invisible owner away");
            s.owner.removePotionEffect(PotionEffectType.INVISIBILITY);
            s.ticks(6);
            assertEquals(3, count(badgeText(s), '✿'), "back once it shows again");
            s.finishClean();
        }
    }

    @Test
    void theCoverNeverStandsInSomebody() {
        try (Scenario s = Scenario.ffa(ID)) {
            s.ticks(10);
            // The attacker right where the cover would go (and the closer spot overlaps it too).
            s.place(s.enemy, 0.5, 2.0, 180);
            s.place(s.enemy2, -0.5, 1.6, 180);
            blow(s, s.enemy, 6);
            s.ticks(COVER_TICKS);
            assertEquals(2, shells(s), "the shell cracked");
            for (Block block : placedBlocks(s)) {
                BoundingBox box = BoundingBox.of(block);
                for (TestPlayer player : s.all()) {
                    assertFalse(box.overlaps(player.getBoundingBox()), "a block in " + player.getName() + " at "
                            + block.getLocation().toVector());
                }
            }
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void aHurtWithoutAnAttackerRaisesNoCover() {
        try (Scenario s = Scenario.ffa(ID)) {
            s.ticks(10);
            s.owner.setNoDamageTicks(0);
            s.owner.damage(6, DamageSource.builder(DamageType.FALL).build());
            assertEquals(2, shells(s), "a hard fall cracks a shell too");
            s.ticks(COVER_TICKS + 4);
            assertEquals(0, s.h.hub.tempBlocks().size(), "but there is no side to put a cover on");
            assertTrue(placedBlocks(s).isEmpty());
            s.ticks(10);
            assertTrue(blockDisplays(s, Material.RED_CONCRETE).isEmpty(), "the halves rolled away and are gone");
            s.finishClean();
        }
    }

    @Test
    void theNextShellHoldsForAMomentAfterACrack() {
        try (Scenario s = Scenario.ffa(ID)) {
            s.ticks(10);
            blow(s, s.enemy, 6);
            blow(s, s.enemy, 6);
            blow(s, s.enemy2, 6);
            assertEquals(2, shells(s), "one blow, one shell: the next one holds for the grace");
            s.ticks(s.paramTicks("crack_grace") + 1);
            s.healAll();
            blow(s, s.enemy, 6);
            assertEquals(1, shells(s), "after the grace the next shell cracks");
            s.finishClean();
        }
    }

    @Test
    void theBareCoreIsSmallQuickAndFragile() {
        try (Scenario s = noGaugeGain(Scenario.builder(ID)).start()) {
            s.ticks(10);
            crackOnce(s);
            crackOnce(s);
            crackOnce(s);
            assertEquals(0, shells(s));
            assertEquals(s.param("scale_core"), scale(s.owner), EPSILON, "the little core");
            assertEquals(1 + s.param("core_speed") / 100, speedShare(s.owner), EPSILON, "quicker than ever");
            assertTrue(badgeText(s).contains("알맹이"), badgeText(s));
            s.healAll();
            s.clearHits();
            blow(s, s.enemy, 3);
            assertEquals(3 * (1 + s.param("core_weakness") / 100), s.damageTaken(s.owner), EPSILON,
                    "and it takes more");
            blow(s, s.enemy, 10);
            assertEquals(0, shells(s), "nothing left to crack");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void aQuietWhileClosesAShellOverTheOwnerAgain() {
        try (Scenario s = noGaugeGain(Scenario.builder(ID)).start()) {
            s.ticks(10);
            crackOnce(s);
            assertEquals(2, shells(s));
            int wait = s.paramTicks("regrow_delay");
            s.ticks(wait - 40);
            assertEquals(2, shells(s), "not yet");
            // A light blow (no crack) starts the quiet over.
            blow(s, s.enemy, 1);
            assertEquals(2, shells(s));
            s.ticks(wait - 20);
            assertEquals(2, shells(s), "the blow reset the wait");
            s.ticks(30);
            assertEquals(3, shells(s), "a shell closed over the owner");
            assertEquals(1.15, scale(s.owner), EPSILON);

            // The lid's halves are for the others to see, not in the owner's face.
            crackOnce(s);
            crackOnce(s);
            assertEquals(1, shells(s));
            boolean lidSeen = false;
            for (int i = 0; i < wait + 40 && shells(s) < 2; i++) {
                s.ticks(1);
            }
            assertEquals(2, shells(s), "one shell at a time");
            s.ticks(1);
            for (BlockDisplay part : blockDisplays(s, Material.RED_CONCRETE)) {
                lidSeen = true;
                assertFalse(s.owner.canSee(part), "the lid stays out of the owner's view");
                assertTrue(s.enemy.canSee(part), "the enemy watches it close");
            }
            assertTrue(lidSeen, "the lid closes over the owner");
            s.ticks(wait + 5);
            assertEquals(3, shells(s), "and then the next");
            s.ticks(wait + 5);
            assertEquals(3, shells(s), "never more than three");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void aShellOnlyGrowsBackWhereThereIsRoom() {
        try (Scenario s = noGaugeGain(Scenario.builder(ID)).start()) {
            s.ticks(10);
            crackOnce(s);
            assertEquals(2, shells(s), "1.8 blocks tall now");
            // A ceiling two blocks over the floor: 1.8 fits, 2.07 does not.
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    s.world.getBlockAt(x, FlatWorld.FLOOR_Y + 3, z).setType(Material.STONE);
                }
            }
            s.ticks(s.paramTicks("regrow_delay") + 40);
            assertEquals(2, shells(s), "no room to grow under the ceiling");
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    s.world.getBlockAt(x, FlatWorld.FLOOR_Y + 3, z).setType(Material.AIR);
                }
            }
            s.ticks(2);
            assertEquals(3, shells(s), "it grows back as soon as there is room");
            s.finishClean();
        }
    }

    @Test
    void theBadgeNeverGivesAwayAnOwnerSomebodyCannotSee() {
        try (Scenario s = Scenario.ffa(ID)) {
            s.ticks(10);
            s.enemy.hidePlayer(s.ctx().plugin(), s.owner);
            s.ticks(12);
            TextDisplay badge = badge(s);
            assertFalse(s.enemy.canSee(badge), "the enemy who cannot see the owner cannot see its badge");
            assertTrue(s.enemy2.canSee(badge));
            assertTrue(s.owner.canSee(badge));
            s.enemy.showPlayer(s.ctx().plugin(), s.owner);
            s.ticks(12);
            assertTrue(s.enemy.canSee(badge(s)), "seen again");
            s.finishClean();
        }
    }

    @Test
    void whoeverCannotSeeTheOwnerNeverSeesItsBadgeLidOrGiantDoll() {
        try (Scenario s = noGaugeGain(Scenario.builder(ID)).start()) {
            s.ticks(10);
            s.enemy.hidePlayer(s.ctx().plugin(), s.owner);
            s.ticks(1);
            assertFalse(s.enemy.canSee(badge(s)), "the badge is veiled at once, not some ticks later");
            // The giant doll comes down right onto the owner: it would show where the owner is.
            s.aimAt(s.owner, s.enemy2);
            s.assertSucceeded(s.ultimate(), "giant");
            for (int i = 0; i < s.paramTicks("drop_delay") + 12; i++) {
                s.ticks(1);
                for (BlockDisplay part : blockDisplays(s, Material.RED_CONCRETE)) {
                    assertFalse(s.enemy.canSee(part), "the giant doll is hidden from the enemy at tick " + i);
                    assertTrue(s.enemy2.canSee(part), "the others see it come down");
                    assertFalse(part.isVisibleByDefault(), "filtered inside its spawn, never sent to the enemy");
                }
            }
            s.enemy.showPlayer(s.ctx().plugin(), s.owner);
            s.ticks(1);
            assertTrue(s.enemy.canSee(badge(s)), "seen again");
            s.finishClean();
        }
    }

    @Test
    void whoeverCannotSeeTheOwnerNeverReceivesTheCrackedHalvesOrTheCrack() {
        try (Scenario s = noGaugeGain(Scenario.builder(ID)).start()) {
            s.ticks(10);
            s.enemy.hidePlayer(s.ctx().plugin(), s.owner);
            s.ticks(1);
            // The other enemy cracks a shell: its halves and the "딱" come off a body the first enemy cannot see.
            blow(s, s.enemy2, 10);
            assertEquals(2, shells(s));
            List<Display> veiled = new ArrayList<>(blockDisplays(s, Material.RED_CONCRETE));
            entities(s, TextDisplay.class).stream().filter(text -> Scenario.plain(text.text()).contains("딱"))
                    .forEach(veiled::add);
            assertTrue(veiled.size() >= 3, "two halves and the word: " + veiled.size());
            for (int i = 0; i < 4; i++) {
                for (Display display : veiled) {
                    if (display.isValid()) {
                        assertFalse(display.isVisibleByDefault(), "filtered inside its spawn: " + display.getType());
                        assertFalse(s.enemy.canSee(display), "hidden from the enemy at tick " + i);
                        assertTrue(s.enemy2.canSee(display), "the attacker sees it at tick " + i);
                    }
                }
                s.ticks(1);
            }
            s.enemy.showPlayer(s.ctx().plugin(), s.owner);
            s.ticks(1);
            for (Display display : veiled) {
                if (display.isValid()) {
                    assertTrue(s.enemy.canSee(display), "seen again as soon as the owner is: " + display.getType());
                }
            }
            s.finishClean();
        }
    }

    // ================================================================== active: a doll inside a doll

    @Test
    void theDollsHopFromEnemyToEnemyOneSizeSmallerEachTime() {
        try (Scenario s = noGaugeGain(Scenario.builder(ID)).start()) {
            s.ticks(10);
            s.aimAt(s.owner, s.enemy);
            s.clearHits();
            s.assertSucceeded(s.active(), "인형 속 인형");
            boolean slowed = false;
            // The tallest lacquer part next to the victim each time a doll strikes: one size smaller every hop.
            List<Double> sizes = new ArrayList<>();
            int seenHits = 0;
            for (int i = 0; i < 80; i++) {
                s.ticks(1);
                for (AttributeModifier modifier : s.enemy.getAttribute(Attribute.MOVEMENT_SPEED).getModifiers()) {
                    slowed |= Math.abs(modifier.getAmount() + s.param("small_slow") / 100) < EPSILON;
                }
                for (BlockDisplay part : entities(s, BlockDisplay.class)) {
                    assertUnrotated(part, "doll");
                    assertTrue(lowest(part) >= FlatWorld.SURFACE - 0.03, "no doll model reaches into the floor: "
                            + lowest(part));
                }
                List<Scenario.Hit> byOwner = s.hits().stream().filter(hit -> s.owner.equals(hit.causer())).toList();
                for (; seenHits < byOwner.size(); seenHits++) {
                    Entity victim = byOwner.get(seenHits).victim();
                    Location body = victim.getLocation().add(0, victim.getHeight() * 0.5, 0);
                    sizes.add(blockDisplays(s, Material.RED_CONCRETE).stream()
                            .filter(part -> part.getLocation().distance(body) <= 1.8)
                            .mapToDouble(part -> part.getTransformation().getScale().y)
                            .max().orElse(0));
                }
            }
            assertEquals(3, sizes.size(), "three dolls struck: " + s.hits());
            for (int k = 0; k < sizes.size(); k++) {
                assertTrue(sizes.get(k) > 0.05, "doll " + k + " is there when it strikes: " + sizes);
                if (k > 0) {
                    assertTrue(sizes.get(k) < sizes.get(k - 1) - 0.02, "each doll is smaller: " + sizes);
                }
            }
            // The big doll pops on the aimed enemy, the middle one hops to the other enemy, the small one back.
            assertEquals(s.param("big_damage") + s.param("small_damage"), s.damageBy(s.owner, s.enemy), EPSILON,
                    "big + small doll: " + s.hits());
            assertEquals(s.param("middle_damage"), s.damageBy(s.owner, s.enemy2), EPSILON, "the middle doll");
            assertTrue(slowed, "the smallest doll slows its enemy");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.ticks(s.paramTicks("small_slow_duration") + 5);
            assertModifierFree(s.enemy, "the slow wears off");
            assertOnlyBadge(s, "dolls");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void theThrownDollFacesWhereItFlies() {
        try (Scenario s = Scenario.ffa(ID)) {
            s.ticks(10);
            s.place(s.owner, 0.5, 0.5, -90);
            s.place(s.enemy, 8.5, 0.5, 90);
            s.place(s.enemy2, -8.5, 8.5, 90);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "throw east");
            s.ticks(3);
            List<BlockDisplay> faces = blockDisplays(s, Material.SMOOTH_QUARTZ);
            assertEquals(1, faces.size(), "the big doll's face");
            BlockDisplay face = faces.getFirst();
            assertUnrotated(face, "flying doll");
            Vector3f look = front(face);
            assertTrue(look.x > 0.95, "the doll faces east, where it flies: " + look);
            assertTrue(face.getX() > 1.0, "on its way");
            s.ticks(80);
            assertTrue(s.damageBy(s.owner, s.enemy) > 0, "and it lands on the enemy");
            s.finishClean();
        }
    }

    @Test
    void oneThrowNeverDealsMoreThanItsCapToOneEnemy() {
        try (Scenario s = noGaugeGain(Scenario.builder(ID)).start()) {
            s.ticks(10);
            s.place(s.enemy2, -14.5, -10.5, 0);
            s.aimAt(s.owner, s.enemy);
            s.clearHits();
            s.assertSucceeded(s.active(), "alone");
            s.ticks(80);
            assertEquals(s.param("big_damage") + s.param("middle_damage") + s.param("small_damage"),
                    s.damageBy(s.owner, s.enemy), EPSILON, "with nobody else around all three land on it");
            assertEquals(s.param("doll_cap"), s.damageBy(s.owner, s.enemy), EPSILON);

            // Crouching sheds a shell for a harder big doll: the shed bonus comes on top of the cap, so trading a
            // shell for offense pays off even against a lone enemy (every doll still lands, the slow too).
            s.readyActive();
            s.layout();
            s.place(s.enemy2, -14.5, -10.5, 0);
            s.aimAt(s.owner, s.enemy);
            s.owner.setSneaking(true);
            s.clearHits();
            s.assertSucceeded(s.active(), "shed and throw");
            assertEquals(2, shells(s), "one shell thrown");
            boolean slowed = false;
            for (int i = 0; i < 80; i++) {
                s.ticks(1);
                for (AttributeModifier modifier : s.enemy.getAttribute(Attribute.MOVEMENT_SPEED).getModifiers()) {
                    slowed |= Math.abs(modifier.getAmount() + s.param("small_slow") / 100) < EPSILON;
                }
            }
            s.owner.setSneaking(false);
            List<Scenario.Hit> hits = s.hitsBy(s.owner, s.enemy);
            assertEquals(3, hits.size(), "all three dolls land: " + hits);
            assertEquals(s.param("big_damage") + s.param("shed_bonus"), hits.getFirst().damage(), EPSILON,
                    "the shed shell hits harder");
            assertEquals(s.param("doll_cap") + s.param("shed_bonus"), s.damageBy(s.owner, s.enemy), EPSILON,
                    "the shed bonus is on top of the cap: " + hits);
            assertTrue(slowed, "the smallest doll still slows the lone enemy");
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "shedding costs a shell, not health");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void wallsStopTheDolls() {
        try (Scenario s = Scenario.ffa(ID)) {
            s.ticks(10);
            wall(s, 2, Material.STONE);
            s.aimAt(s.owner, s.enemy);
            s.clearHits();
            s.assertSucceeded(s.active(), "into the wall");
            s.ticks(80);
            assertEquals(0, s.damageBy(s.owner, s.enemy), EPSILON, "the doll bounced off the wall");
            assertEquals(0, s.damageBy(s.owner, s.enemy2), EPSILON, "and nobody behind it is reached");
            wall(s, 2, Material.AIR);
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void theDollsNeverHopToAnEnemyTheOwnerCannotSee() {
        try (Scenario s = noGaugeGain(Scenario.builder(ID)).start()) {
            s.ticks(10);
            s.owner.hidePlayer(s.ctx().plugin(), s.enemy2);
            s.aimAt(s.owner, s.enemy);
            s.clearHits();
            s.assertSucceeded(s.active(), "throw");
            s.ticks(80);
            assertEquals(0, s.damageBy(s.owner, s.enemy2), EPSILON, "the hidden enemy is never sought out");
            assertEquals(s.param("doll_cap"), s.damageBy(s.owner, s.enemy), EPSILON, "all three on the seen one");
            s.owner.showPlayer(s.ctx().plugin(), s.enemy2);
            s.finishClean();
        }
    }

    @Test
    void theDollsNeverTouchTheOwnersAlly() {
        try (Scenario s = noGaugeGain(Scenario.builder(ID).teams()).start()) {
            s.ticks(10);
            s.crossfireAlly();
            s.aimAt(s.owner, s.enemy);
            Location allyStart = s.ally().getLocation();
            s.clearHits();
            s.assertSucceeded(s.active(), "throw");
            AbilityLifecycleTest.watchAlly(s, allyStart, 80);
            assertTrue(s.damageBy(s.owner, s.enemy) + s.damageBy(s.owner, s.enemy2) > 0, "the enemies were hit");
            s.finishClean();
        }
    }

    // ================================================================== ultimate: from the beginning

    @Test
    void aGiantDollFallsOverTheOwnerKnocksEnemiesAwayAndGildsIt() {
        try (Scenario s = noGaugeGain(Scenario.builder(ID)).start()) {
            s.ticks(10);
            crackOnce(s);
            crackOnce(s);
            assertEquals(1, shells(s));
            s.layout();
            s.healAll();
            s.aimAt(s.owner, s.enemy);
            s.clearHits();
            s.assertSucceeded(s.ultimate(), "처음부터 다시");

            // The giant comes down from above (telegraphed), nothing happens before it lands.
            s.ticks(3);
            List<BlockDisplay> giant = blockDisplays(s, Material.RED_CONCRETE);
            assertFalse(giant.isEmpty(), "the giant doll");
            for (BlockDisplay part : giant) {
                assertUnrotated(part, "giant");
                assertTrue(part.getLocation().getY() > s.owner.getLocation().getY() + 3, "high above the owner");
            }
            assertEquals(0, s.damageBy(s.owner, s.enemy), EPSILON, "it has not landed yet");

            int drop = s.paramTicks("drop_delay");
            boolean landed = false;
            for (int i = 0; i < drop + 2 && !landed; i++) {
                s.ticks(1);
                landed = s.damageBy(s.owner, s.enemy) > 0;
            }
            assertTrue(landed, "it lands after the drop delay");
            assertEquals(s.param("slam_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "the enemy 4 blocks away");
            assertEquals(0, s.damageBy(s.owner, s.enemy2), EPSILON, "the one farther out is spared");
            Vector push = s.enemy.getVelocity();
            assertTrue(push.getZ() > 0.3 && push.getY() > 0, "knocked outwards (fresh velocity): " + push);
            assertEquals(4, shells(s), "every shell back plus the golden one");
            assertEquals(1.3, scale(s.owner), EPSILON, "1.3 times the size");
            assertEquals(4, count(badgeText(s), '✿'));
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "never hurts the owner");

            s.ticks(s.paramTicks("gold_duration") + 2);
            assertEquals(3, shells(s), "the gold peels off at the end");
            assertEquals(1.15, scale(s.owner), EPSILON);
            s.ticks(20);
            assertOnlyBadge(s, "giant");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void underGoldTheCrackedHalvesFlyAtTheNearestEnemy() {
        Scenario.Builder builder = noGaugeGain(Scenario.builder(ID))
                .configure(h -> h.settings().set("ultimate.per-damage-dealt", 5))
                .configure(h -> h.settings().set("ultimate.recharge-delay-seconds", 0));
        try (Scenario s = builder.start()) {
            s.ticks(10);
            s.place(s.enemy, 0.5, 6.5, 180);
            s.place(s.enemy2, -14.5, -10.5, 0);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "gold");
            s.ticks(s.paramTicks("drop_delay") + 4);
            assertEquals(4, shells(s));
            s.match.setGauge(s.owner, 0);
            s.layout();
            s.place(s.enemy2, -14.5, -10.5, 0);
            s.clearHits();

            s.healAll();
            blow(s, s.enemy, 10);
            assertEquals(3, shells(s), "the golden shell cracks first");
            s.ticks(30);
            assertEquals(2 * s.param("shard_damage"), s.damageBy(s.owner, s.enemy), EPSILON,
                    "both halves fly into the attacker: " + s.hits());
            assertEquals(0, s.h.hub.tempBlocks().size(), "no cover under gold");
            assertEquals(0, s.ctx().gauge(s.owner), EPSILON, "the halves are the ultimate's: they charge no gauge");

            s.ticks(s.paramTicks("crack_grace"));
            s.healAll();
            blow(s, s.enemy, 10);
            s.ticks(30);
            assertEquals(4 * s.param("shard_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "two more halves");

            // Four halves were all the gold had: the next shell stands as a cover again.
            s.ticks(s.paramTicks("crack_grace"));
            s.healAll();
            blow(s, s.enemy, 10);
            s.ticks(COVER_TICKS);
            assertEquals(4 * s.param("shard_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "no fifth half");
            assertTrue(s.h.hub.tempBlocks().size() > 0, "a cover again");
            assertEquals(0, s.damageBy(s.owner, s.enemy2), EPSILON, "the far enemy is out of reach");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void theGoldenHalvesNeverHitTheAlly() {
        try (Scenario s = noGaugeGain(Scenario.builder(ID).teams()).start()) {
            s.ticks(10);
            s.crossfireAlly();
            s.aimAt(s.owner, s.enemy);
            Location allyStart = s.ally().getLocation();
            s.assertSucceeded(s.ultimate(), "gold");
            AbilityLifecycleTest.watchAlly(s, allyStart, s.paramTicks("drop_delay") + 4);
            // The ally right next to the owner, the enemy farther: the halves still go to the enemy.
            s.place(s.ally(), 1.5, 1.5, 0);
            s.layout();
            s.place(s.ally(), 1.5, 1.5, 0);
            allyStart = s.ally().getLocation();
            s.clearHits();
            s.healAll();
            blow(s, s.enemy, 10);
            AbilityLifecycleTest.watchAlly(s, allyStart, 30);
            assertEquals(2 * s.param("shard_damage"), s.damageBy(s.owner, s.enemy), EPSILON);
            s.finishClean();
        }
    }

    @Test
    void theUltimateNeedsHeadroomForTheGiantShell() {
        try (Scenario s = Scenario.ffa(ID)) {
            s.ticks(10);
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    s.world.getBlockAt(x, FlatWorld.FLOOR_Y + 3, z).setType(Material.STONE);
                }
            }
            assertFalse(s.ultimate().succeeded(), "a low ceiling");
            assertEquals(100, s.ctx().gauge(s.owner), EPSILON, "the gauge is kept");
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    s.world.getBlockAt(x, FlatWorld.FLOOR_Y + 3, z).setType(Material.AIR);
                }
            }
            s.assertSucceeded(s.ultimate(), "open sky");
            s.ticks(s.paramTicks("drop_delay") + 4);
            assertFalse(s.ultimate().succeeded(), "not twice while the gold lasts");
            s.finishClean();
        }
    }

    @Test
    void theUltimateNeverHarmsOrMovesTheAlly() {
        try (Scenario s = noGaugeGain(Scenario.builder(ID).teams()).start()) {
            s.ticks(10);
            s.crossfireAlly();
            s.aimAt(s.owner, s.enemy);
            Location allyStart = s.ally().getLocation();
            s.clearHits();
            s.assertSucceeded(s.ultimate(), "gold");
            AbilityLifecycleTest.watchAlly(s, allyStart, 200);
            assertTrue(s.damageBy(s.owner, s.enemy) > 0, "the enemy was knocked away");
            s.finishClean();
        }
    }

    // ================================================================== stealth mid-flight

    @Test
    void aHoppingDollDropsAnEnemyThatVanishesMidHop() {
        try (Scenario s = noGaugeGain(Scenario.builder(ID)).start()) {
            s.ticks(10);
            s.aimAt(s.owner, s.enemy);
            s.clearHits();
            s.assertSucceeded(s.active(), "throw");
            assertTrue(tickUntilHit(s, s.enemy, 40), "the big doll popped on the aimed enemy");
            // The middle doll is on its way to the other enemy when that one vanishes (ninja, shadow, pixie...).
            s.ticks(6);
            assertEquals(0, s.damageBy(s.owner, s.enemy2), EPSILON, "still in the air");
            s.owner.hidePlayer(s.ctx().plugin(), s.enemy2);
            double closest = Double.MAX_VALUE;
            for (int i = 0; i < 60; i++) {
                s.ticks(1);
                Location body = s.enemy2.getLocation().add(0, s.enemy2.getHeight() * 0.5, 0);
                for (BlockDisplay part : blockDisplays(s, Material.RED_CONCRETE)) {
                    closest = Math.min(closest, part.getLocation().distance(body));
                }
            }
            assertEquals(0, s.damageBy(s.owner, s.enemy2), EPSILON, "the vanished enemy is not hit: " + s.hits());
            assertTrue(closest > 1.0, "no doll homes onto (and so gives away) the hidden enemy: " + closest);
            s.owner.showPlayer(s.ctx().plugin(), s.enemy2);
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void aGoldenHalfDropsAnEnemyThatVanishes() {
        try (Scenario s = noGaugeGain(Scenario.builder(ID)).start()) {
            s.ticks(10);
            s.place(s.enemy, 0.5, 6.5, 180);
            s.place(s.enemy2, -14.5, -10.5, 0);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "gold");
            s.ticks(s.paramTicks("drop_delay") + 4);
            assertEquals(4, shells(s));
            s.clearHits();
            s.healAll();
            blow(s, s.enemy, 10);
            assertEquals(3, shells(s), "the golden shell cracked");
            s.ticks(2);
            s.owner.hidePlayer(s.ctx().plugin(), s.enemy);
            double closest = Double.MAX_VALUE;
            for (int i = 0; i < 40; i++) {
                s.ticks(1);
                Location body = s.enemy.getLocation().add(0, s.enemy.getHeight() * 0.5, 0);
                for (BlockDisplay part : blockDisplays(s, Material.GOLD_BLOCK)) {
                    closest = Math.min(closest, part.getLocation().distance(body));
                }
            }
            assertEquals(0, s.damageBy(s.owner, s.enemy), EPSILON, "the halves lose the vanished enemy: "
                    + s.hits());
            assertTrue(closest > 1.0, "no golden half flies into the hidden enemy: " + closest);
            s.owner.showPlayer(s.ctx().plugin(), s.enemy);
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void theThrownDollPassesAnEnemyTheOwnerCannotSee() {
        try (Scenario s = noGaugeGain(Scenario.builder(ID)).start()) {
            s.ticks(10);
            // The hidden enemy stands right in the doll's way to the aimed one.
            s.place(s.enemy, 0.5, 8.5, 180);
            s.place(s.enemy2, 0.4, 2.5, 180);
            s.aimAt(s.owner, s.enemy);
            s.owner.hidePlayer(s.ctx().plugin(), s.enemy2);
            s.clearHits();
            s.assertSucceeded(s.active(), "throw");
            assertTrue(tickUntilHit(s, s.enemy, 40), "the doll flies on to the seen enemy");
            s.ticks(60);
            assertEquals(0, s.damageBy(s.owner, s.enemy2), EPSILON, "it never pops on the hidden one");
            s.owner.showPlayer(s.ctx().plugin(), s.enemy2);
            s.finishClean();
        }
    }

    // ================================================================== models stay above the floor

    @Test
    void noDollModelEverSinksIntoTheFloor() {
        try (Scenario s = noGaugeGain(Scenario.builder(ID)).start()) {
            s.ticks(10);
            // A hard fall: no side to stand a cover on, the halves roll away along the floor.
            s.owner.setNoDamageTicks(0);
            s.owner.damage(6, DamageSource.builder(DamageType.FALL).build());
            assertEquals(2, shells(s));
            watchFloor(s, 24, "rolled halves");
            // A blow from the enemy: the halves tumble over to the cover.
            s.healAll();
            blow(s, s.enemy, 10);
            assertEquals(1, shells(s));
            watchFloor(s, 24, "halves flying to the cover");
            // The thrown doll pops on the floor (nobody near the aimed spot), the inner ones look around.
            s.place(s.enemy, 9.5, -6.5, 0);
            s.place(s.enemy2, 12.5, -9.5, 0);
            s.aimAtFloor(s.owner, -2.5, 5.5);
            s.assertSucceeded(s.active(), "into the floor");
            watchFloor(s, 60, "doll popping on the floor");
            // A shell closes over the owner again: the lid's lower half grows up from the floor.
            s.layout();
            watchFloor(s, s.paramTicks("regrow_delay") + 20, "lid");
            assertEquals(2, shells(s), "a shell grew back");
            // The giant doll comes down, squashes on the floor and settles over the owner.
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "giant");
            watchFloor(s, s.paramTicks("drop_delay") + 20, "giant doll");
            // Golden halves fly at the attacker.
            s.healAll();
            blow(s, s.enemy, 10);
            watchFloor(s, 30, "golden halves");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void theGiantDollLandsFlatOnTheFloor() {
        try (Scenario s = noGaugeGain(Scenario.builder(ID)).start()) {
            s.ticks(10);
            s.aimAt(s.owner, s.enemy);
            s.clearHits();
            s.assertSucceeded(s.ultimate(), "giant");
            assertTrue(tickUntilHit(s, s.enemy, s.paramTicks("drop_delay") + 4), "it lands");
            // The landing squash flattens it onto the floor: its base stays on the ground, never lifts off it.
            List<BlockDisplay> giant = blockDisplays(s, Material.RED_CONCRETE).stream()
                    .filter(part -> part.getTransformation().getScale().y > 1)
                    .toList();
            assertFalse(giant.isEmpty(), "the giant doll");
            double base = giant.stream().mapToDouble(NestingNewAbilitiesTest::lowest).min().orElseThrow();
            assertEquals(FlatWorld.SURFACE, base, 0.05, "the squashed giant sits on the floor");
            s.finishClean();
        }
    }

    // ================================================================== covers

    @Test
    void aDiagonalBlowRaisesAStraightCoverAcrossIt() {
        try (Scenario s = Scenario.ffa(ID)) {
            s.ticks(10);
            // The attacker stands south-east, a little more south than east: the cover faces south.
            s.place(s.enemy, 3.5, 4.0, 135);
            blow(s, s.enemy, 6);
            s.ticks(COVER_TICKS);
            List<Block> blocks = placedBlocks(s);
            List<Block> base = blocks.stream().filter(b -> b.getY() == FlatWorld.FLOOR_Y + 1).toList();
            assertEquals(3, base.size(), "a body and two arms: " + blocks);
            int z = base.getFirst().getZ();
            for (Block block : base) {
                assertEquals(z, block.getZ(), "the body and its arms in one straight row across the blow: " + base);
            }
            BlockDisplay face = blockDisplays(s, Material.SMOOTH_QUARTZ).getFirst();
            assertEquals(1, front(face).z, 1.0E-4, "the face looks south like the row: " + front(face));
            s.finishClean();
        }
    }

    @Test
    void aCoverNeverStandsOnTheFarSideOfAWall() {
        try (Scenario s = Scenario.ffa(ID)) {
            s.ticks(10);
            // A thin wall between the owner and the cover spot (an arrow came over it from the south).
            for (int x = -3; x <= 3; x++) {
                for (int y = 1; y <= 3; y++) {
                    s.world.getBlockAt(x, FlatWorld.FLOOR_Y + y, 1).setType(Material.STONE);
                }
            }
            Arrow arrow = s.world.spawn(s.owner.getLocation().add(0, 1, 0.3), Arrow.class);
            arrow.setShooter(s.enemy);
            arrow.setVelocity(new Vector(0, 0, -1.5));
            s.owner.setNoDamageTicks(0);
            s.owner.damage(8, DamageSource.builder(DamageType.ARROW).withCausingEntity(s.enemy)
                    .withDirectEntity(arrow).build());
            arrow.remove();
            assertEquals(2, shells(s));
            s.ticks(COVER_TICKS + 4);
            assertEquals(0, s.h.hub.tempBlocks().size(), "no cover behind the wall: "
                    + placedBlocks(s).stream().filter(b -> b.getType() != Material.STONE).toList());
            for (int x = -3; x <= 3; x++) {
                for (int y = 1; y <= 3; y++) {
                    s.world.getBlockAt(x, FlatWorld.FLOOR_Y + y, 1).setType(Material.AIR);
                }
            }
            s.finishClean();
        }
    }

    @Test
    void waitingForHeadroomSaysSoOnceAndShowsItOnTheBadge() {
        try (Scenario s = noGaugeGain(Scenario.builder(ID)).start()) {
            s.ticks(10);
            crackOnce(s);
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    s.world.getBlockAt(x, FlatWorld.FLOOR_Y + 3, z).setType(Material.STONE);
                }
            }
            actionBars(s.owner);
            s.ticks(s.paramTicks("regrow_delay") + 200);
            assertEquals(2, shells(s), "no room to grow");
            List<String> cramped = actionBars(s.owner).stream().filter(bar -> bar.contains("비좁")).toList();
            assertEquals(1, cramped.size(), "the notice comes once, not over every other action bar: " + cramped);
            assertTrue(badgeText(s).contains("비좁"), "the badge says why nothing grows: " + badgeText(s));
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    s.world.getBlockAt(x, FlatWorld.FLOOR_Y + 3, z).setType(Material.AIR);
                }
            }
            s.ticks(2);
            assertEquals(3, shells(s));
            s.finishClean();
        }
    }

    // ================================================================== smooth on a real client

    @Test
    void theThrownDollLeavesTheHandSmallRocksSmoothlyAndNeverGoesThroughAWall() {
        try (Scenario s = noGaugeGain(Scenario.builder(ID)).start()) {
            s.ticks(10);
            s.place(s.owner, 0.5, 0.5, -90);
            s.place(s.enemy, 8.5, 0.5, 90);
            s.place(s.enemy2, -8.5, 8.5, 90);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "throw east");
            // Out of the owner's face: small, and an arm's length in front of its eyes.
            BlockDisplay face = blockDisplays(s, Material.SMOOTH_QUARTZ).getFirst();
            double full = 0.34 * 0.8;
            assertTrue(face.getTransformation().getScale().x < full * 0.5, "it leaves the hand small");
            assertTrue(face.getX() - s.owner.getX() > 0.6, "in front of the eyes: " + (face.getX() - s.owner.getX()));
            for (BlockDisplay part : entities(s, BlockDisplay.class)) {
                assertEquals(2, part.getTeleportDuration(), "moved every tick: the kit's smooth teleports");
            }
            s.ticks(1);
            assertEquals(full, face.getTransformation().getScale().x, 1.0E-4, "and grows to full size");
            assertEquals(2, face.getInterpolationDuration(), "over 2 ticks");
            // It rocks from side to side in steps the client blends into one sway.
            Quaternionf last = new Quaternionf(face.getTransformation().getLeftRotation());
            double biggest = 0;
            for (int i = 0; i < 12 && face.isValid(); i++) {
                s.ticks(1);
                if (s.damageBy(s.owner, s.enemy) > 0) {
                    break; // it popped open: the lid flies off
                }
                Quaternionf now = face.getTransformation().getLeftRotation();
                biggest = Math.max(biggest, turn(last, now));
                last = new Quaternionf(now);
            }
            assertTrue(biggest > 3 && biggest < 25, "a smooth rock, not a twitch: " + biggest + " degrees per pose");
            s.finishClean();
        }

        // A wall right in front of the owner: the doll leaves the hand on this side of it.
        try (Scenario s = noGaugeGain(Scenario.builder(ID)).start()) {
            s.ticks(10);
            s.place(s.owner, 0.5, 0.5, 0);
            wall(s, 1, Material.STONE);
            s.aimAtFloor(s.owner, 0.5, 6.5);
            s.assertSucceeded(s.active(), "into the wall");
            for (BlockDisplay part : entities(s, BlockDisplay.class)) {
                assertTrue(part.getZ() < 1.0, "on the owner's side of the wall: " + part.getZ());
            }
            s.ticks(20);
            wall(s, 1, Material.AIR);
            s.finishClean();
        }
    }

    @Test
    void theInnerDollsNeverSpinRoundOverTheEnemyTheyHopBackOnto() {
        try (Scenario s = noGaugeGain(Scenario.builder(ID)).start()) {
            s.ticks(10);
            // A lone enemy to the east: the middle and the small doll hop back onto it from right where it was hit.
            s.place(s.owner, 0.5, 0.5, -90);
            s.place(s.enemy, 6.5, 0.5, 90);
            s.place(s.enemy2, -14.5, -10.5, 0);
            s.aimAt(s.owner, s.enemy);
            s.clearHits();
            s.assertSucceeded(s.active(), "throw east");
            int seen = 0;
            for (int i = 0; i < 80; i++) {
                // It shuffles from side to side under the dolls.
                s.place(s.enemy, 6.5, i % 2 == 0 ? 0.65 : 0.35, 90);
                s.ticks(1);
                for (BlockDisplay face : blockDisplays(s, Material.SMOOTH_QUARTZ)) {
                    double width = face.getTransformation().getScale().x;
                    if (width < 0.1 || width > 0.26) {
                        continue; // the big doll's face, a doll still coming out, or one shrinking away
                    }
                    Vector3f look = front(face);
                    double offEast = Math.toDegrees(Math.atan2(Math.abs(look.z), look.x));
                    assertTrue(offEast < 60, "an inner doll faces the way it was thrown at tick " + i + ": " + look);
                    seen++;
                }
            }
            assertEquals(3, s.hitsBy(s.owner, s.enemy).size(), "all three dolls land on it: " + s.hits());
            assertTrue(seen > 4, "the inner dolls were watched: " + seen);
            s.finishClean();
        }
    }

    @Test
    void theGiantDollTurnsAQuarterWhateverTheMouseDoesThenFollowsTheOwner() {
        try (Scenario s = noGaugeGain(Scenario.builder(ID)).start()) {
            s.ticks(10);
            s.place(s.owner, 0.5, 0.5, 0);
            s.assertSucceeded(s.ultimate(), "giant");
            int drop = s.paramTicks("drop_delay");
            float mouse = 0;
            double lastYaw = 0;
            for (int i = 0; i < drop; i++) {
                // Flicking the camera round and round while it falls.
                mouse = (mouse + 170) % 360;
                s.owner.setRotation(mouse, 0);
                s.ticks(1);
                for (BlockDisplay face : blockDisplays(s, Material.SMOOTH_QUARTZ)) {
                    double yaw = facingYaw(face);
                    assertTrue(yaw >= lastYaw - 0.5 && yaw <= 90.5, "a steady quarter turn at tick " + i + ": "
                            + yaw + " after " + lastYaw);
                    lastYaw = yaw;
                }
            }
            assertEquals(90, lastYaw, 0.5, "it lands a quarter turned");
            List<BlockDisplay> giant = blockDisplays(s, Material.RED_CONCRETE);
            assertFalse(giant.isEmpty(), "landed");
            for (BlockDisplay part : giant) {
                assertEquals(3, part.getTeleportDuration(), "it follows the body as other clients draw it");
                assertFalse(s.owner.canSee(part), "over the owner now, out of its camera");
            }
            // It squashes and settles onto the regrown body without turning back, following the owner all along.
            double body = 1.8 * scale(s.owner);
            for (int i = 0; i < 3 + 6; i++) {
                s.place(s.owner, 0.5 + 0.2 * (i + 1), 0.5, mouse);
                s.ticks(1);
                for (BlockDisplay face : blockDisplays(s, Material.SMOOTH_QUARTZ)) {
                    assertEquals(90, facingYaw(face), 0.5, "no twist back at tick " + i);
                }
                for (BlockDisplay part : blockDisplays(s, Material.RED_CONCRETE)) {
                    assertEquals(s.owner.getX(), part.getX(), 1.0E-6, "the giant follows the owner at tick " + i);
                }
                if (i >= 2 && i < 8) {
                    // Settling (it shrinks away at the end): snugly onto the regrown body, not the old one.
                    BlockDisplay face = blockDisplays(s, Material.SMOOTH_QUARTZ).getFirst();
                    assertEquals(0.34 * body * 1.1, face.getTransformation().getScale().x, 1.0E-3,
                            "it settles onto the regrown body at tick " + i);
                }
            }
            s.finishClean();
        }
    }

    @Test
    void aRegrownShellClosesStaysOnAMomentAndFollowsTheOwner() {
        try (Scenario s = noGaugeGain(Scenario.builder(ID)).start()) {
            s.ticks(10);
            crackOnce(s);
            for (int i = 0; i < s.paramTicks("regrow_delay") + 40 && shells(s) < 3; i++) {
                s.ticks(1);
            }
            assertEquals(3, shells(s), "a shell grows back");
            double height = 1.8 * scale(s.owner);
            double width = 0.6 * scale(s.owner) * 1.25;
            List<BlockDisplay> lid = blockDisplays(s, Material.RED_CONCRETE);
            assertEquals(2, lid.size(), "the cap and the cup");
            for (BlockDisplay half : lid) {
                assertEquals(3, half.getTeleportDuration(), "it follows the body as other clients draw it");
                assertFalse(half.isVisibleByDefault(), "filtered inside its spawn");
                assertEquals(width, half.getTransformation().getScale().x, 1.0E-4, "as wide as the regrown shell");
            }
            int closed = 0;
            int lasted = 0;
            for (int i = 0; i < 30; i++) {
                // The owner walks east while it closes.
                s.place(s.owner, 0.5 + 0.15 * (i + 1), 0.5, 0);
                s.ticks(1);
                List<BlockDisplay> left = blockDisplays(s, Material.RED_CONCRETE);
                if (left.isEmpty()) {
                    break;
                }
                lasted++;
                double feet = s.owner.getY();
                BlockDisplay cup = null;
                BlockDisplay cap = null;
                for (BlockDisplay half : left) {
                    assertEquals(s.owner.getX(), half.getX(), 1.0E-6, "the lid follows the owner at tick " + i);
                    if (Math.abs(half.getY() - feet) < 1.0E-6) {
                        cup = half;
                    } else {
                        cap = half;
                    }
                }
                if (cup != null && cap != null
                        && Math.abs(cup.getTransformation().getScale().y - height / 2) < 1.0E-4
                        && Math.abs(cap.getY() - (feet + height * 0.75)) < 1.0E-4) {
                    closed++;
                }
            }
            assertTrue(closed >= 3, "the closed shell stays on a moment before it shrinks away: " + closed);
            assertTrue(lasted <= 20, "and then it is gone: " + lasted);
            s.assertNoProblems();
            s.finishClean();
        }
    }

    // ================================================================== budget & cleanup

    @Test
    void theDollsThatHitAreAlwaysThereEvenWhenCoversFillTheBudget() {
        try (Scenario s = noGaugeGain(Scenario.builder(ID)).start()) {
            s.ticks(10);
            // Three painted covers at once: north, east and west of the owner.
            for (double[] from : new double[][] {{0.5, -3.5}, {4.5, 0.5}, {-3.5, 0.5}}) {
                s.place(s.enemy, from[0], from[1], 0);
                s.healAll();
                blow(s, s.enemy, 10);
                s.ticks(s.paramTicks("crack_grace") + 2);
            }
            assertEquals(0, shells(s));
            s.ticks(18);
            assertTrue(s.h.hub.tempBlocks().size() >= 9, "three covers stand: " + s.h.hub.tempBlocks().size());
            s.layout();
            s.aimAt(s.owner, s.enemy);
            s.clearHits();
            s.assertSucceeded(s.active(), "throw");
            int most = 0;
            int checked = 0;
            double seenEnemy = 0;
            double seenEnemy2 = 0;
            for (int i = 0; i < 80; i++) {
                s.ticks(1);
                most = Math.max(most, entities(s, Display.class).size());
                for (TestPlayer victim : s.enemies()) {
                    double dealt = s.damageBy(s.owner, victim);
                    double before = victim == s.enemy ? seenEnemy : seenEnemy2;
                    if (dealt > before + EPSILON) {
                        checked++;
                        assertTrue(dollNear(s, victim, 1.8), "a doll is there when it hits " + victim.getName()
                                + " (tick " + i + ", " + entities(s, Display.class).size() + " displays)");
                    }
                    if (victim == s.enemy) {
                        seenEnemy = dealt;
                    } else {
                        seenEnemy2 = dealt;
                    }
                }
            }
            assertEquals(3, checked, "three dolls struck: " + s.hits());
            assertTrue(most <= 24, "the budget still holds: " + most);
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void displaysStayWithinTheBudget() {
        try (Scenario s = Scenario.ffa(ID)) {
            s.ticks(10);
            int most = 0;
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "throw");
            for (int i = 0; i < 120; i++) {
                if (i == 3) {
                    s.assertSucceeded(s.ultimate(), "gold");
                }
                if (i % 12 == 0) {
                    s.healAll();
                    blow(s, i % 24 == 0 ? s.enemy : s.enemy2, 10);
                }
                if (i == 60) {
                    s.readyActive();
                    s.aimAt(s.owner, s.enemy);
                    s.active();
                }
                s.ticks(1);
                most = Math.max(most, entities(s, Display.class).size());
            }
            assertTrue(most <= 24, "at most 24 displays at once: " + most);
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void ownerDyingMidThrowLeavesNothingBehind() {
        try (Scenario s = Scenario.ffa(ID)) {
            s.ticks(10);
            blow(s, s.enemy, 10);
            s.ticks(2);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "throw");
            s.ticks(6);
            assertOwnerDeathCleansUp(s);
        }
    }

    @Test
    void ownerDyingUnderTheGiantDollLeavesNothingBehind() {
        try (Scenario s = Scenario.ffa(ID)) {
            s.ticks(10);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "gold");
            s.ticks(4);
            assertOwnerDeathCleansUp(s);
        }
    }

    @Test
    void ownerDyingUnderGoldLeavesNothingBehind() {
        try (Scenario s = Scenario.ffa(ID)) {
            s.ticks(10);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "gold");
            s.ticks(s.paramTicks("drop_delay") + 4);
            blow(s, s.enemy, 10);
            s.ticks(2);
            assertOwnerDeathCleansUp(s);
        }
    }

    private static void assertOwnerDeathCleansUp(Scenario s) {
        s.killOwner(s.enemy);
        s.ticks(1);
        for (Entity entity : s.world.getEntities()) {
            assertTrue(entity instanceof Player, "the dead owner's " + entity.getType() + " is removed");
        }
        s.clearHits();
        s.ticks(300);
        for (Scenario.Hit hit : s.hits()) {
            if (s.owner.equals(hit.causer())) {
                fail("the dead owner still dealt damage: " + hit);
            }
        }
        assertEquals(0, s.h.hub.tempBlocks().size(), "the covers expired");
        for (TestPlayer enemy : s.enemies()) {
            assertModifierFree(enemy, "after the owner's death");
        }
        assertModifierFree(s.owner, "the dead owner");
        s.assertNoProblems();
        s.finishClean();
    }
}
