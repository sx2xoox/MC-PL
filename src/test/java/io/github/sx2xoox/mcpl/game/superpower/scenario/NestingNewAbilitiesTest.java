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

    /** The shell count over the owner's head. */
    private static TextDisplay badge(Scenario s) {
        List<TextDisplay> badges = entities(s, TextDisplay.class).stream()
                .filter(text -> {
                    String plain = Scenario.plain(text.text());
                    return plain.contains("✿") || plain.contains("알맹이") || plain.isEmpty();
                })
                .filter(text -> text.getLocation().getY() > s.owner.getLocation().getY() + s.owner.getHeight())
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
            // It follows the owner.
            s.place(s.owner, 5.5, 2.5, 0);
            s.ticks(2);
            assertEquals(5.5, badge(s).getX(), 0.3);
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

            // Right away the halves spring off the body, out of the owner's own view.
            s.ticks(1);
            List<BlockDisplay> halves = blockDisplays(s, Material.RED_CONCRETE);
            assertFalse(halves.isEmpty(), "the halves fly off");
            for (BlockDisplay half : halves) {
                assertUnrotated(half, "half");
                assertFalse(s.owner.canSee(half), "not in the owner's face while they leave");
                assertTrue(s.enemy.canSee(half), "everyone else sees them");
            }
            assertTrue(entities(s, TextDisplay.class).stream()
                    .anyMatch(text -> Scenario.plain(text.text()).contains("딱")), "딱!");

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

    // ================================================================== active: a doll inside a doll

    @Test
    void theDollsHopFromEnemyToEnemyOneSizeSmallerEachTime() {
        try (Scenario s = noGaugeGain(Scenario.builder(ID)).start()) {
            s.ticks(10);
            s.aimAt(s.owner, s.enemy);
            s.clearHits();
            s.assertSucceeded(s.active(), "인형 속 인형");
            boolean slowed = false;
            for (int i = 0; i < 80; i++) {
                s.ticks(1);
                for (AttributeModifier modifier : s.enemy.getAttribute(Attribute.MOVEMENT_SPEED).getModifiers()) {
                    slowed |= Math.abs(modifier.getAmount() + s.param("small_slow") / 100) < EPSILON;
                }
                for (BlockDisplay part : entities(s, BlockDisplay.class)) {
                    assertUnrotated(part, "doll");
                    assertTrue(part.getLocation().getY() >= FlatWorld.SURFACE - EPSILON, "never under the floor");
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

            // Crouching sheds a shell for a harder big doll; the cap still holds.
            s.readyActive();
            s.layout();
            s.place(s.enemy2, -14.5, -10.5, 0);
            s.aimAt(s.owner, s.enemy);
            s.owner.setSneaking(true);
            s.clearHits();
            s.assertSucceeded(s.active(), "shed and throw");
            assertEquals(2, shells(s), "one shell thrown");
            s.ticks(80);
            s.owner.setSneaking(false);
            assertEquals(s.param("doll_cap"), s.damageBy(s.owner, s.enemy), EPSILON, "capped: " + s.hits());
            assertEquals(s.param("big_damage") + s.param("shed_bonus"),
                    s.hitsBy(s.owner, s.enemy).getFirst().damage(), EPSILON, "the shed shell hits harder");
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

    // ================================================================== budget & cleanup

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
