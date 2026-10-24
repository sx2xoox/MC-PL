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

import com.destroystokyo.paper.event.entity.EntityKnockbackByEntityEvent;
import io.github.sx2xoox.mcpl.game.superpower.abilities.CardGamerAbility;
import io.github.sx2xoox.mcpl.game.superpower.abilities.CardGamerAbility.Card;
import io.github.sx2xoox.mcpl.game.superpower.abilities.FrogAbility;
import io.github.sx2xoox.mcpl.game.superpower.abilities.KappaAbility;
import io.github.sx2xoox.mcpl.game.superpower.abilities.TennisAbility;
import io.github.sx2xoox.mcpl.game.superpower.abilities.YagwanggwiAbility;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Motion;
import io.github.sx2xoox.mcpl.game.superpower.ability.Ability;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import io.github.sx2xoox.mcpl.game.superpower.ability.ActivationResult;
import io.github.sx2xoox.mcpl.game.superpower.ability.InputKey;
import io.github.sx2xoox.mcpl.game.superpower.ability.Prompt;
import io.github.sx2xoox.mcpl.game.superpower.support.Scenario;
import io.github.sx2xoox.mcpl.game.superpower.support.TestPlayer;
import io.github.sx2xoox.mcpl.game.superpower.support.world.FlatWorld;
import io.github.sx2xoox.mcpl.util.Text;
import io.papermc.paper.event.entity.EntityKnockbackEvent;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** 장난 · 놀이 (new): 카드 배틀러, 테니스 천재, 갓파, 야광귀, 청개구리. */
class PrankNewAbilitiesTest {

    private static final double EPSILON = 1.0E-6;
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z0-9_]+)}");

    static Stream<String> abilities() {
        return Stream.of("cardgamer", "tennis", "kappa", "yagwanggwi", "frog");
    }

    /** The ultimates the gambler may borrow. */
    static Stream<String> standalone() {
        return Stream.of("tennis");
    }

    /** See RogueNewAbilitiesTest: the shared sweeps start a mock server of their own in their static initializer. */
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

    private static void assertAllUnrotated(Scenario s, String what) {
        for (Display display : entities(s, Display.class)) {
            assertUnrotated(display, what);
        }
    }

    /** The left rotation a display was given (see WeirdNewAbilitiesTest#rotationOf). */
    private static Quaternionf rotationOf(Display display) {
        Transformation t = display.getTransformation();
        Quaternionf left = t.getLeftRotation();
        return t.getRightRotation().equals(new Quaternionf(), 1.0E-5f) ? left : left.conjugate();
    }

    private static Vector3f forward(Quaternionf rotation) {
        return rotation.transform(new Vector3f(0, 0, 1));
    }

    private static double modifierSum(LivingEntity entity, Attribute attribute) {
        double sum = 0;
        for (AttributeModifier modifier : entity.getAttribute(attribute).getModifiers()) {
            if (!modifier.getKey().equals(Scenario.TANK)) {
                sum += modifier.getAmount();
            }
        }
        return sum;
    }

    private static boolean locked(LivingEntity entity) {
        return entity.getAttribute(Attribute.MOVEMENT_SPEED).getModifiers().stream()
                .anyMatch(modifier -> modifier.getAmount() <= -1 + EPSILON);
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

    private static List<BlockDisplay> blocks(Scenario s, Material type) {
        return entities(s, BlockDisplay.class).stream().filter(d -> d.getBlock().getMaterial() == type).toList();
    }

    private static List<TextDisplay> texts(Scenario s, String containing) {
        return entities(s, TextDisplay.class).stream()
                .filter(text -> Scenario.plain(text.text()).contains(containing)).toList();
    }

    // ================================================================== every ability

    @Test
    void descriptionsOnlyReferenceDeclaredParams() {
        for (AbilityInfo info : List.of(CardGamerAbility.INFO, TennisAbility.INFO, KappaAbility.INFO,
                YagwanggwiAbility.INFO, FrogAbility.INFO)) {
            for (AbilityInfo.Skill skill : List.of(info.passive(), info.active(), info.ultimate())) {
                assertFalse(skill.name().equals("TODO"), info.id() + " is implemented");
                Matcher matcher = PLACEHOLDER.matcher(skill.description());
                while (matcher.find()) {
                    assertTrue(info.params().containsKey(matcher.group(1)),
                            info.id() + " " + skill.name() + " uses an undeclared param " + matcher.group());
                }
            }
        }
        assertTrue(TennisAbility.INFO.ultimateStandalone(), "the jump smash runs on its own");
        assertFalse(CardGamerAbility.INFO.ultimateStandalone(), "the turn plays the hand");
        assertFalse(KappaAbility.INFO.ultimateStandalone(), "the great ring needs the kappa's knockback hook");
        assertFalse(YagwanggwiAbility.INFO.ultimateStandalone(), "the night throws back the worn shoes");
        assertFalse(FrogAbility.INFO.ultimateStandalone(), "the rain needs the frog's knockback listener");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("abilities")
    void ownerDiesDuringActive(String id) {
        try (Scenario s = Scenario.ffa(id)) {
            s.ticks(40);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), id + " active");
            s.ticks(4);
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
            assertEquals(20, enemy.getFoodLevel(), id + ": " + enemy.getName() + " got its hunger back");
            assertFalse(enemy.hasPotionEffect(PotionEffectType.DARKNESS), id + ": the night is over");
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
            assertEquals(20, s.ally().getFoodLevel(), "the ally keeps its hunger");
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
            }
            assertNoDisplays(s, id);
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
            s.ticks(8);
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
            }
            assertModifierFree(s.owner, id);
            s.assertNoProblems();
            s.finishClean();
        }
    }

    // ================================================================== 카드 배틀러 cardgamer

    private static CardGamerAbility cards(Scenario s) {
        return (CardGamerAbility) s.ability();
    }

    @Test
    void cardgamerStartsWithAHandDrawsOnTimeAndKeepsTheFacesForTheOwner() {
        try (Scenario s = Scenario.ffa("cardgamer")) {
            s.ticks(2);
            assertEquals(s.param("start_hand"), cards(s).hand().size(), EPSILON, "the starting hand");
            s.ticks(s.paramTicks("draw_interval") + 1);
            assertEquals(s.param("start_hand") + 1, cards(s).hand().size(), EPSILON, "one card per interval");
            s.ticks(s.paramTicks("draw_interval") * 3);
            assertEquals(s.param("hand_max"), cards(s).hand().size(), EPSILON, "never more than a full hand");

            // The fan: backs for everyone else, faces for the owner alone, all turned by the transformation only.
            s.place(s.owner, 0.5, 0.5, 90);
            s.ticks(3);
            List<BlockDisplay> backs = blocks(s, Material.BLUE_GLAZED_TERRACOTTA);
            assertEquals(cards(s).hand().stream().filter(card -> card != Card.LEGEND).count(), backs.size(),
                    "one back per card");
            Vector facing = s.owner.getLocation().getDirection().setY(0).normalize();
            for (BlockDisplay back : backs) {
                assertUnrotated(back, "card back");
                assertTrue(s.enemy.canSee(back) && !s.owner.canSee(back),
                        "the backs are for the others: the owner sees its faces there");
                assertEquals(2, back.getTeleportDuration(), "a per-tick follower smooths over 2 ticks");
                Vector3f normal = forward(rotationOf(back));
                assertTrue(Math.abs(normal.x * facing.getX() + normal.z * facing.getZ()) > 0.95,
                        "the card stands across the back: " + normal);
                Vector offset = back.getLocation().toVector().subtract(s.owner.getLocation().toVector()).setY(0);
                assertTrue(offset.dot(facing) < -0.2, "behind the owner: " + offset);
            }
            long faces = entities(s, BlockDisplay.class).stream().filter(d -> s.owner.canSee(d) && !s.enemy.canSee(d))
                    .count();
            assertEquals(cards(s).hand().size(), faces, "the faces are the owner's alone");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void cardgamerMonsterBitesSpellHealsAndDrawsAndSneakingTurnsTheHand() {
        try (Scenario s = Scenario.ffa("cardgamer")) {
            s.ticks(5);
            cards(s).rigHand(Card.MONSTER, Card.SPELL);
            s.aimAt(s.owner, s.enemy);
            s.clearHits();
            s.assertSucceeded(s.active(), "monster card");
            s.h.tickUntil(() -> blocks(s, Material.ORANGE_TERRACOTTA).size() == 2, 10, "the jaws");
            s.place(s.enemy, 1.5, 5.5, 180);
            s.ticks(1);
            Location bitten = s.enemy.getEyeLocation().add(0, 0.1, 0);
            for (BlockDisplay jaw : blocks(s, Material.ORANGE_TERRACOTTA)) {
                assertTrue(jaw.getLocation().distance(bitten) < 1.0E-6, "the jaws stay on the head they bite");
            }
            s.ticks(10);
            assertEquals(s.param("monster_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "the jaws bite");
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON, "only the first enemy on the line");
            assertEquals(List.of(Card.SPELL), cards(s).hand(), "the monster left the hand");
            assertAllUnrotated(s, "monster");

            s.owner.setHealth(s.owner.getHealth() - 6);
            double before = s.owner.getHealth();
            cards(s).stackDeck(Card.TRAP);
            s.readyActive();
            s.assertSucceeded(s.active(), "spell card");
            assertEquals(before + s.param("spell_heal"), s.owner.getHealth(), EPSILON, "the spell heals");
            assertEquals(List.of(Card.TRAP), cards(s).hand(), "and draws one more at once");

            cards(s).rigHand(Card.MONSTER, Card.TRAP, Card.SPELL);
            s.owner.setSneaking(true);
            s.readyActive();
            s.clearHits();
            s.assertSucceeded(s.active(), "turning the hand");
            s.owner.setSneaking(false);
            assertEquals(List.of(Card.TRAP, Card.SPELL, Card.MONSTER), cards(s).hand(), "the front card went back");
            s.ticks(2);
            assertTrue(s.ctx().cooldownRemaining(s.owner) <= s.param("shuffle_cooldown") + EPSILON,
                    "turning a card is quick: " + s.ctx().cooldownRemaining(s.owner));
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "turning plays nothing");
            cards(s).rigHand();
            s.readyActive();
            assertFalse(s.active().succeeded(), "no card, no play");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void cardgamerTrapSpringsOnlyOnRunnersAndJumpers() {
        try (Scenario s = Scenario.ffa("cardgamer")) {
            s.ticks(5);
            cards(s).rigHand(Card.TRAP);
            s.assertSucceeded(s.active(), "trap card");
            assertEquals(1, cards(s).trapCount());
            s.place(s.owner, 0.5, -8.5, 0);
            s.place(s.enemy, 0.5, 2.5, 180);
            s.ticks(Math.max(s.paramTicks("trap_arm"), 10) + 3);
            s.clearHits();
            s.enemy.sendInput(InputKey.FORWARD);
            s.enemy.sendInput(InputKey.FORWARD, InputKey.SNEAK);
            s.ticks(4);
            assertEquals(1, cards(s).trapCount(), "walking and sneaking past is safe");
            assertEquals(0, s.damageTaken(s.enemy), EPSILON);

            s.enemy.sendInput(InputKey.FORWARD, InputKey.SPRINT);
            s.ticks(2);
            assertEquals(0, cards(s).trapCount(), "the trap is spent");
            assertEquals(s.param("trap_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "함정 카드 발동!");
            assertTrue(s.ctx().isSilenced(s.enemy), "silenced");
            assertTrue(locked(s.enemy), "bound in place");
            assertFalse(texts(s, "함정 카드 발동").isEmpty(), "the shout is shown");
            BlockDisplay sprung = blocks(s, Material.PURPLE_CONCRETE).stream().filter(s.enemy::canSee).findFirst()
                    .orElseThrow();
            Vector3f length = rotationOf(sprung).transform(new Vector3f(0, 0, 1));
            assertTrue(length.y > 0.99, "the card stands up by a quarter turn: " + length);
            assertTrue(sprung.getTransformation().getScale().y < 0.05f, "and keeps its thin axis");
            s.enemy.sendInput();
            s.ticks(s.paramTicks("trap_root") + 2);
            assertFalse(locked(s.enemy), "the bind wears off");

            // A second trap and a jump.
            s.layout();
            cards(s).rigHand(Card.TRAP);
            s.readyActive();
            s.assertSucceeded(s.active(), "another trap");
            s.place(s.owner, 0.5, -8.5, 0);
            s.place(s.enemy2, 1.5, 1.5, 180);
            s.ticks(15);
            s.clearHits();
            s.enemy2.sendInput(InputKey.JUMP);
            s.ticks(2);
            assertEquals(s.param("trap_damage"), s.damageBy(s.owner, s.enemy2), EPSILON, "a jump springs it too");
            s.enemy2.sendInput();
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void cardgamerLegendBeastFallsOnTheAimAfterItsTelegraph() {
        try (Scenario s = Scenario.ffa("cardgamer")) {
            s.ticks(5);
            cards(s).rigHand(Card.LEGEND);
            assertEquals(1, blocks(s, Material.GOLD_BLOCK).size(), "the legend's gold back is public");
            s.aimAt(s.owner, s.enemy);
            Location before = s.enemy.getLocation();
            s.clearHits();
            s.assertSucceeded(s.active(), "legend card");
            s.ticks(s.paramTicks("legend_delay") - 2);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "telegraphed first");
            s.ticks(4);
            assertEquals(s.param("legend_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "the beast lands");
            assertEquals(s.param("legend_damage"), s.damageBy(s.owner, s.enemy2), EPSILON, "on everyone in the ring");
            s.ticks(10);
            assertTrue(s.enemy.getLocation().distance(before) > 1.5, "and throws them away");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            assertAllUnrotated(s, "beast");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void cardgamerTurnFillsTheHandShortensTheCooldownAndEndsWithAPulse() {
        try (Scenario s = Scenario.ffa("cardgamer")) {
            s.ticks(5);
            cards(s).rigHand();
            cards(s).stackDeck(Card.MONSTER, Card.MONSTER, Card.MONSTER, Card.MONSTER, Card.MONSTER, Card.MONSTER,
                    Card.MONSTER, Card.MONSTER);
            s.aimAt(s.owner, s.enemy);
            s.clearHits();
            s.assertSucceeded(s.ultimate(), "my turn");
            assertTrue(cards(s).isTurn());
            s.ticks(14);
            assertEquals(s.param("turn_hand"), cards(s).hand().size(), EPSILON, "a full hand");
            s.assertSucceeded(s.active(), "first card");
            s.ticks((int) Math.ceil(s.param("turn_cooldown") * 20) + 1);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "second card right away");
            assertEquals(s.param("turn_hand"), cards(s).hand().size(), EPSILON, "every card is redrawn");
            s.ticks(10);
            assertEquals(2 * s.param("monster_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "two bites");
            s.ticks(s.paramTicks("turn_duration"));
            assertFalse(cards(s).isTurn(), "the turn ends");
            double pulse = Math.min(s.param("turn_end_max"), 2 * s.param("turn_end_damage"));
            assertEquals(2 * s.param("monster_damage") + pulse, s.damageBy(s.owner, s.enemy), EPSILON, "턴 종료");
            assertEquals(s.param("hand_max"), cards(s).hand().size(), EPSILON, "back to a normal hand");
            assertEquals(s.param("hand_max"), blocks(s, Material.BLUE_GLAZED_TERRACOTTA).size()
                    + blocks(s, Material.GOLD_BLOCK).size(), EPSILON, "the extra cards are gone");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void cardgamerDrawnCardGrowsFromTheChestAndTheHandHidesWithTheDuelist() {
        try (Scenario s = Scenario.ffa("cardgamer")) {
            s.ticks(2);
            cards(s).rigHand(Card.MONSTER);
            int before = cards(s).hand().size();
            s.h.tickUntil(() -> cards(s).hand().size() > before, s.paramTicks("draw_interval") + 5, "a draw");
            // The draw tick: the new card waits at the chest, tiny, so the clients have it before it grows.
            List<BlockDisplay> drawn = entities(s, BlockDisplay.class).stream()
                    .filter(d -> d.getTransformation().getScale().x < 0.05f).toList();
            assertEquals(2, drawn.size(), "its back and its face");
            Location chest = s.owner.getLocation().add(0, s.owner.getHeight() * 0.95, 0);
            for (BlockDisplay part : drawn) {
                assertTrue(part.getLocation().distance(chest) < 1.0E-6, "still at the chest");
            }
            s.ticks(1);
            for (BlockDisplay part : drawn) {
                assertTrue(part.getTransformation().getScale().x > 0.2f, "it grows into the fan");
                assertTrue(part.getLocation().distance(chest) > 0.2, "and moves there");
            }
            s.owner.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, 200, 0));
            s.ctx().refreshViewers();
            for (BlockDisplay back : blocks(s, Material.BLUE_GLAZED_TERRACOTTA)) {
                assertFalse(s.enemy.canSee(back), "an invisible duelist's hand is not seen either");
            }

            cards(s).rigHand(Card.LEGEND);
            BlockDisplay gold = blocks(s, Material.GOLD_BLOCK).getFirst();
            assertTrue(gold.isGlowing(), "a moment of gold as the legend arrives");
            s.ticks(25);
            assertFalse(gold.isGlowing(), "then no outline through walls");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void cardgamerLegendBeastComesWholeDuringATurnAndStandsOnTheGround() {
        try (Scenario s = Scenario.builder("cardgamer").param("legend_delay", 1.2).start()) {
            s.ticks(5);
            cards(s).rigHand();
            Card[] monsters = new Card[16];
            java.util.Arrays.fill(monsters, Card.MONSTER);
            cards(s).stackDeck(monsters);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.ultimate(), "my turn");
            s.ticks(14);
            cards(s).rigHand(Card.TRAP, Card.TRAP, Card.LEGEND, Card.MONSTER, Card.MONSTER, Card.MONSTER);
            int gap = (int) Math.ceil(s.param("turn_cooldown") * 20) + 1;
            s.assertSucceeded(s.active(), "a trap");
            s.ticks(gap);
            s.assertSucceeded(s.active(), "another trap");
            s.ticks(gap);
            assertEquals(2, cards(s).trapCount());
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "the legend");
            s.ticks(gap);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "a monster bites while the beast comes");
            assertEquals(6, cards(s).hand().size(), "a full hand of six");
            s.h.tickUntil(() -> blocks(s, Material.QUARTZ_BLOCK).size() == 2, 30, "the beast with both horns");
            assertEquals(4, blocks(s, Material.GOLD_BLOCK).size(), "body, head and both arms");
            s.ticks(6);
            BlockDisplay body = blocks(s, Material.GOLD_BLOCK).stream()
                    .max(java.util.Comparator.comparingDouble(d -> d.getTransformation().getScale().x)).orElseThrow();
            assertEquals(0, body.getTransformation().getTranslation().y, 0.01, "the body stands on the ring");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    // ================================================================== 테니스 천재 tennis

    private static TennisAbility tennis(Scenario s) {
        return (TennisAbility) s.ability();
    }

    private static void tickUntilState(Scenario s, String state, int max) {
        s.h.tickUntil(() -> tennis(s).ballState().equals(state), max, "the ball to be " + state);
    }

    @Test
    void tennisServeHitsComesBackAndARallyHitsHarder() {
        try (Scenario s = Scenario.ffa("tennis")) {
            s.ticks(5);
            s.aimAt(s.owner, s.enemy);
            s.clearHits();
            s.assertSucceeded(s.active(), "serve");
            tickUntilState(s, "BACK", 30);
            assertEquals(s.param("serve_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "the serve hits");
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON);
            assertEquals(1, tennis(s).myPoints(), "15 - 0");
            tickUntilState(s, "FLOAT", 30);
            assertAllUnrotated(s, "ball");
            assertFalse(texts(s, "15").isEmpty(), "the score board shows 15");

            s.layout();
            s.ticks(1);
            s.aimAt(s.owner, s.enemy);
            s.clearHits();
            s.owner.swingArm();
            s.ticks(1);
            assertEquals(1, tennis(s).rally(), "the swing keeps the rally going");
            tickUntilState(s, "BACK", 30);
            double bonus = 1 + s.param("rally_bonus") / 100;
            assertEquals(s.param("serve_damage") * bonus, s.damageBy(s.owner, s.enemy), EPSILON, "a rally hits harder");
            tickUntilState(s, "FLOAT", 30);
            s.ticks(s.paramTicks("rally_window") + 2);
            assertFalse(tennis(s).ballState().equals("FLOAT"), "an unhit ball drops");
            s.ticks(30);
            assertEquals("NONE", tennis(s).ballState());
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void tennisEnemyCanSwingTheBallBackHarmlessly() {
        try (Scenario s = Scenario.ffa("tennis")) {
            s.ticks(5);
            s.aimAt(s.owner, s.enemy);
            s.clearHits();
            s.assertSucceeded(s.active(), "serve");
            s.ticks(s.paramTicks("serve_toss"));
            s.enemy.swingArm();
            s.ticks(6);
            assertEquals(0, s.damageTaken(s.enemy), EPSILON, "returned, not hit");
            assertEquals(1, tennis(s).rivalPoints(), "0 - 15");
            assertEquals(0, tennis(s).rally());
            s.ticks(40);
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "a returned ball never hurts the owner");
            assertEquals("NONE", tennis(s).ballState(), "the broken rally ends");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void tennisGameChargesTheUltimate() {
        try (Scenario s = Scenario.builder("tennis").param("game_points", 1).start()) {
            s.ticks(5);
            s.match.setGauge(s.owner, 0);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "serve");
            tickUntilState(s, "BACK", 30);
            assertTrue(s.ctx().gauge(s.owner) >= s.param("game_gauge") - EPSILON, "게임! "
                    + s.ctx().gauge(s.owner));
            assertEquals(0, tennis(s).myPoints(), "a new game starts");
            assertFalse(texts(s, "게임").isEmpty(), "the call is shown");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void tennisJumpSmashHitsTheCourtBouncesOnAndLandsSafely() {
        try (Scenario s = Scenario.ffa("tennis")) {
            s.ticks(5);
            s.aimAt(s.owner, s.enemy);
            s.clearHits();
            s.assertSucceeded(s.ultimate(), "jump smash");
            s.ticks(6);
            assertTrue(s.owner.getLocation().getY() > FlatWorld.SURFACE + 2, "the prodigy jumps");
            for (int i = 0; i < 60; i++) {
                s.ticks(1);
            }
            double smash = s.param("smash_damage");
            double bounce = s.param("bounce_damage");
            assertEquals(smash + bounce, s.damageBy(s.owner, s.enemy), EPSILON, "the court and one bounce");
            assertEquals(smash + bounce, s.damageBy(s.owner, s.enemy2), EPSILON, "the ball skips on");
            s.tickUntilGrounded(s.owner, 60);
            s.ticks(30);
            assertEquals(FlatWorld.SURFACE, s.owner.getLocation().getY(), EPSILON, "feet on the floor");
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "no fall damage");
            assertModifierFree(s.owner, "after the landing");
            assertNoDisplays(s, "smash");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void tennisTossGoesStraightUpWhereverTheProdigyTurns() {
        try (Scenario s = Scenario.ffa("tennis")) {
            s.ticks(5);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "serve");
            s.ticks(2);
            BlockDisplay ball = blocks(s, Material.LIME_WOOL).getFirst();
            Location tossed = ball.getLocation();
            s.owner.setRotation(100, -20);
            s.ticks(2);
            assertEquals("TOSS", tennis(s).ballState());
            Location now = ball.getLocation();
            assertEquals(tossed.getX(), now.getX(), 1.0E-6, "the toss does not follow the turn: x");
            assertEquals(tossed.getZ(), now.getZ(), 1.0E-6, "z");
            assertTrue(now.getY() > tossed.getY(), "it rises");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void tennisHangingBallStaysPutWhileTheProdigyTurnsAndMoves() {
        try (Scenario s = Scenario.ffa("tennis")) {
            s.ticks(5);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "serve");
            tickUntilState(s, "FLOAT", 60);
            s.ticks(1);
            List<BlockDisplay> ball = List.of(blocks(s, Material.LIME_WOOL).getFirst(),
                    blocks(s, Material.LIME_CONCRETE_POWDER).getFirst(), blocks(s, Material.WHITE_CONCRETE).getFirst());
            List<Location> before = ball.stream().map(Entity::getLocation).toList();
            s.place(s.owner, 1.1, 0.9, 140);
            s.ticks(3);
            assertEquals("FLOAT", tennis(s).ballState());
            for (int i = 0; i < ball.size(); i++) {
                assertTrue(ball.get(i).getLocation().distance(before.get(i)) < 1.0E-6,
                        "the hanging ball stays where it came back to");
            }
            s.owner.swingArm();
            s.ticks(1);
            assertEquals(1, tennis(s).rally(), "a swing within reach still rallies it");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void tennisOnlyAFreshSwingAtAnIncomingBallReturnsIt() {
        try (Scenario s = Scenario.ffa("tennis")) {
            s.ticks(5);
            // One enemy right in front, swinging at the prodigy before the serve; one beside it, swinging after.
            s.place(s.enemy, 0.5, 2.5, 180);
            s.place(s.enemy2, 2.5, 0.5, 90);
            s.ticks(1);
            s.aimAt(s.owner, s.enemy);
            s.clearHits();
            s.assertSucceeded(s.active(), "serve");
            s.enemy.swingArm();
            s.ticks(s.paramTicks("serve_toss"));
            s.aimAt(s.enemy2, s.owner.getEyeLocation().add(0, 0.6, 0.5));
            s.enemy2.swingArm();
            s.ticks(4);
            assertEquals(0, tennis(s).rivalPoints(), "neither a stale swing nor one at a ball flying away returns it");
            assertEquals(s.param("serve_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "the serve hits");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    // ================================================================== 갓파 kappa

    private static KappaAbility kappa(Scenario s) {
        return (KappaAbility) s.ability();
    }

    @Test
    void kappaDishMakesItStrongUntilAHitFromAboveSpillsItAndItRefills() {
        try (Scenario s = Scenario.ffa("kappa")) {
            s.ticks(5);
            assertTrue(kappa(s).isPlateFull());
            List<BlockDisplay> dish = blocks(s, Material.WHITE_CONCRETE);
            assertEquals(2, dish.size(), "the dish");
            for (BlockDisplay plate : dish) {
                assertUnrotated(plate, "dish");
                assertFalse(s.owner.canSee(plate), "the dish stays out of the owner's camera");
                assertTrue(s.enemy.canSee(plate), "everyone else sees it");
            }
            s.clearHits();
            s.melee(s.owner, s.enemy, 5);
            assertEquals(5 * (1 + s.param("plate_bonus") / 100), s.damageBy(s.owner, s.enemy), EPSILON, "full dish");

            EntityKnockbackByEntityEvent knock = new EntityKnockbackByEntityEvent(s.enemy, s.owner,
                    EntityKnockbackEvent.Cause.ENTITY_ATTACK, 0.4f, new Vector(0.4, 0.36, 0));
            s.h.server.getPluginManager().callEvent(knock);
            assertEquals(0.4 * (1 + s.param("plate_knockback") / 100), knock.getKnockback().getX(), EPSILON);
            assertEquals(0.36, knock.getKnockback().getY(), EPSILON);

            // A blow from level ground keeps the water; one from above spills it.
            s.melee(s.enemy, s.owner, 1);
            assertTrue(kappa(s).isPlateFull(), "level ground");
            s.place(s.enemy, 0.5, FlatWorld.SURFACE + 2, 1.5, 180, 40);
            s.melee(s.enemy, s.owner, 1);
            assertFalse(kappa(s).isPlateFull(), "a hit from above spills the water");
            assertTrue(modifierSum(s.owner, Attribute.MOVEMENT_SPEED) < -EPSILON, "slower with an empty dish");
            s.layout();
            s.clearHits();
            s.melee(s.owner, s.enemy, 5);
            assertEquals(5 * (1 - s.param("empty_penalty") / 100), s.damageBy(s.owner, s.enemy), EPSILON, "weaker");
            s.ticks(s.paramTicks("refill_time") + 2);
            assertTrue(kappa(s).isPlateFull(), "the dish refills");
            assertModifierFree(s.owner, "refilled");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void kappaBowsBackToAPoliteEnemy() {
        try (Scenario s = Scenario.ffa("kappa")) {
            s.ticks(5);
            s.place(s.enemy, 0.5, 3.5, 180);
            s.aimAt(s.enemy, s.owner);
            s.ticks(2);
            s.enemy.sendInput(InputKey.SNEAK);
            s.ticks(2);
            assertFalse(kappa(s).isPlateFull(), "the kappa bowed back and spilled its water");
            assertTrue(locked(s.owner), "frozen in the bow");
            assertFalse(texts(s, "꾸벅").isEmpty(), "꾸벅");
            s.enemy.sendInput();
            s.ticks(s.paramTicks("bow_freeze") + 2);
            assertFalse(locked(s.owner), "the bow ends");
            s.ticks(s.paramTicks("refill_time"));
            assertTrue(kappa(s).isPlateFull());
            s.enemy.sendInput(InputKey.SNEAK);
            s.ticks(2);
            assertTrue(kappa(s).isPlateFull(), "the same enemy cannot make it bow again so soon");
            s.enemy.sendInput();
            // Crouching while looking away is no bow.
            s.place(s.enemy2, 0.5, 3.5, 0);
            s.ticks(2);
            s.enemy2.sendInput(InputKey.SNEAK);
            s.ticks(2);
            assertTrue(kappa(s).isPlateFull(), "a back turned is no bow");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void kappaBoutPunishesWhoeverLeavesTheRingFirst() {
        try (Scenario s = Scenario.ffa("kappa")) {
            s.ticks(5);
            s.aimAt(s.owner, s.enemy);
            s.clearHits();
            s.assertSucceeded(s.active(), "bout");
            assertTrue(kappa(s).inBout());
            assertEquals(12, blocks(s, Material.HAY_BLOCK).size(), "the straw ring");
            EntityKnockbackByEntityEvent knock = new EntityKnockbackByEntityEvent(s.enemy, s.owner,
                    EntityKnockbackEvent.Cause.ENTITY_ATTACK, 0.4f, new Vector(0, 0.3, 0.4));
            s.h.server.getPluginManager().callEvent(knock);
            assertEquals(0.4 * (1 + s.param("plate_knockback") / 100) * s.param("sumo_knockback"),
                    knock.getKnockback().getZ(), EPSILON, "pushes much harder in the ring");
            s.ticks(3);
            s.place(s.enemy, 0.5, 9.5, 180);
            s.ticks(2);
            assertFalse(kappa(s).inBout(), "a ring-out decides the bout");
            assertEquals(s.param("sumo_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "the loser is thrown");
            assertTrue(locked(s.enemy), "and lies on the ground");
            s.ticks(s.paramTicks("sumo_down") + 2);
            assertFalse(locked(s.enemy));

            // The kappa leaving first loses.
            s.layout();
            s.ticks(2);
            s.readyActive();
            s.aimAt(s.owner, s.enemy);
            s.clearHits();
            s.assertSucceeded(s.active(), "second bout");
            s.place(s.owner, 0.5, -6.5, 0);
            s.ticks(2);
            assertTrue(locked(s.owner), "the kappa is down");
            assertEquals(0, s.damageTaken(s.enemy), EPSILON);
            s.ticks(s.paramTicks("sumo_down") + 2);

            // Nobody leaves: both tire.
            s.layout();
            s.ticks(2);
            s.readyActive();
            s.aimAt(s.owner, s.enemy);
            s.clearHits();
            s.assertSucceeded(s.active(), "third bout");
            s.ticks(s.paramTicks("sumo_duration") + 2);
            assertEquals(s.param("draw_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "a draw tires the enemy");
            assertFalse(kappa(s).isPlateFull(), "and the kappa's water");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.ticks(20);
            assertTrue(blocks(s, Material.HAY_BLOCK).isEmpty(), "the ring is gone");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void kappaGreatRingThrowsOutTheContestants() {
        try (Scenario s = Scenario.ffa("kappa")) {
            s.ticks(5);
            s.clearHits();
            s.assertSucceeded(s.ultimate(), "great ring");
            assertTrue(modifierSum(s.owner, Attribute.KNOCKBACK_RESISTANCE) >= 1 - EPSILON, "immovable");
            s.ticks(s.paramTicks("grand_telegraph") + 2);
            s.place(s.enemy, 0.5, 9.5, 180);
            s.ticks(2);
            assertEquals(s.param("grand_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "a ring-out");
            assertTrue(locked(s.enemy), "down");
            s.place(s.enemy, 0.5, 3.5, 180);
            s.ticks(2);
            s.place(s.enemy, 0.5, 9.5, 180);
            s.ticks(2);
            assertEquals(s.param("grand_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "out of this ring for good");
            EntityKnockbackByEntityEvent knock = new EntityKnockbackByEntityEvent(s.enemy2, s.owner,
                    EntityKnockbackEvent.Cause.ENTITY_ATTACK, 0.4f, new Vector(0.4, 0.3, 0));
            s.h.server.getPluginManager().callEvent(knock);
            assertEquals(0.4 * (1 + s.param("plate_knockback") / 100) * s.param("grand_knockback"),
                    knock.getKnockback().getX(), EPSILON, "a contestant flies");
            s.ticks(s.paramTicks("grand_duration") + 20);
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON, "who stays in is never hurt by the ring");
            assertModifierFree(s.owner, "the ring is over");
            assertTrue(blocks(s, Material.HAY_BLOCK).isEmpty());
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void kappaGreatRingDuringABoutRaisesAWholeRingOfBalesAlongTheCircle() {
        try (Scenario s = Scenario.ffa("kappa")) {
            s.ticks(5);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "bout");
            s.ticks(1);
            s.assertSucceeded(s.ultimate(), "the great ring right on top of the bout");
            s.ticks(10);
            List<BlockDisplay> bales = blocks(s, Material.HAY_BLOCK);
            assertEquals(16, bales.size(), "the whole great ring, not an arc");
            Vector center = new Vector();
            for (BlockDisplay bale : bales) {
                center.add(bale.getLocation().toVector());
            }
            center.multiply(1.0 / bales.size());
            for (BlockDisplay bale : bales) {
                assertUnrotated(bale, "bale");
                Vector radial = bale.getLocation().toVector().subtract(center).setY(0).normalize();
                Vector3f along = rotationOf(bale).transform(new Vector3f(1, 0, 0));
                assertEquals(0, along.x * radial.getX() + along.z * radial.getZ(), 0.02,
                        "the bale's long side lies along the circle");
            }
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void kappaDishJumpsWithATeleportAndTipsForwardAsOne() {
        try (Scenario s = Scenario.ffa("kappa")) {
            s.ticks(5);
            List<BlockDisplay> plates = blocks(s, Material.WHITE_CONCRETE);
            assertEquals(2, plates.size());
            assertTrue(Math.abs(plates.get(0).getTransformation().getScale().y
                    - plates.get(1).getTransformation().getScale().y) > 1.0E-3, "no two faces share a plane");

            // A teleport: the dish takes duration 0 first, then jumps, then smooths again (no slide across the map).
            s.place(s.owner, 12.5, 0.5, 0);
            s.ticks(1);
            for (BlockDisplay plate : plates) {
                assertEquals(0, plate.getTeleportDuration(), "set before the move");
                assertTrue(plate.getLocation().distance(s.owner.getLocation()) > 8, "not moved yet");
            }
            s.ticks(1);
            Location head = s.owner.getLocation().add(0, s.owner.getHeight() + 0.06, 0);
            for (BlockDisplay plate : plates) {
                assertTrue(plate.getLocation().distance(head) < 1.0E-6, "on the head again");
            }
            s.ticks(1);
            for (BlockDisplay plate : plates) {
                assertEquals(3, plate.getTeleportDuration(), "smooth again");
            }

            // The bow tips the whole dish forward, every piece about the same axis.
            s.place(s.enemy, 12.5, 3.5, 180);
            s.aimAt(s.enemy, s.owner);
            s.ticks(2);
            s.enemy.sendInput(InputKey.SNEAK);
            s.ticks(2);
            assertFalse(kappa(s).isPlateFull(), "bowed");
            Vector facing = Motion.horizontalFacing(s.owner.getLocation());
            List<Display> dish = new ArrayList<>(blocks(s, Material.LIGHT_GRAY_CONCRETE));
            dish.addAll(blocks(s, Material.LIGHT_BLUE_STAINED_GLASS));
            assertEquals(3, dish.size());
            Vector3f first = rotationOf(dish.getFirst()).transform(new Vector3f(0, 1, 0));
            for (Display piece : dish) {
                Vector3f up = rotationOf(piece).transform(new Vector3f(0, 1, 0));
                assertTrue(up.x * facing.getX() + up.z * facing.getZ() > 0.5, "tipped forward: " + up);
                assertTrue(up.distance(first) < 1.0E-4, "all pieces alike");
            }
            s.enemy.sendInput();
            s.ticks(s.paramTicks("bow_freeze") + 6);
            for (Display piece : dish) {
                assertTrue(rotationOf(piece).transform(new Vector3f(0, 1, 0)).y > 0.999, "upright again");
            }
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void kappaRivalWhoNeverSawTheRuleLosesOnlyWhenPushedOut() {
        try (Scenario s = Scenario.ffa("kappa")) {
            s.ticks(30);
            // Something else holds the rival's screen: the bout's rule is never shown.
            s.ctx().prompt(s.owner, s.enemy, Prompt.builder(Text.mm("<gray>다른 일</gray>"), 200)
                    .priority(Prompt.HIGH).build());
            s.aimAt(s.owner, s.enemy);
            s.clearHits();
            s.assertSucceeded(s.active(), "bout");
            for (int i = 0; i < 8; i++) {
                s.enemy.setClientVelocity(new Vector(0, 0, 0.5));
                s.ticks(1);
            }
            assertTrue(s.enemy.getLocation().getZ() > 2.5 + s.param("sumo_radius"), "walked out of the ring");
            assertTrue(kappa(s).inBout(), "walking out of a rule it never saw is no loss");
            assertEquals(0, s.damageTaken(s.enemy), EPSILON);
            s.enemy.setVelocity(new Vector(0, 0.1, 0.4));
            s.ticks(2);
            assertFalse(kappa(s).inBout(), "pushed out, it loses");
            assertEquals(s.param("sumo_damage"), s.damageBy(s.owner, s.enemy), EPSILON);
            s.assertNoProblems();
            s.finishClean();
        }
    }

    // ================================================================== 야광귀 yagwanggwi

    private static YagwanggwiAbility ghost(Scenario s) {
        return (YagwanggwiAbility) s.ability();
    }

    @Test
    void yagwanggwiStealsShoesSoTheEnemyCannotSprintUntilItHitsBack() {
        try (Scenario s = Scenario.ffa("yagwanggwi")) {
            s.ticks(5);
            s.aimAt(s.owner, s.enemy);
            s.clearHits();
            s.assertSucceeded(s.active(), "snatch");
            s.ticks(2);
            List<BlockDisplay> arm = blocks(s, Material.LIME_STAINED_GLASS);
            assertEquals(1, arm.size(), "the glowing arm");
            assertUnrotated(arm.getFirst(), "arm");
            Vector toEnemy = s.enemy.getLocation().toVector().subtract(arm.getFirst().getLocation().toVector())
                    .normalize();
            Vector3f along = forward(rotationOf(arm.getFirst()));
            assertTrue(along.x * toEnemy.getX() + along.y * toEnemy.getY() + along.z * toEnemy.getZ() > 0.95,
                    "the arm reaches for the enemy: " + along);
            s.ticks(3);
            assertEquals(s.param("steal_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "the snatch stings");
            assertTrue(ghost(s).isBarefoot(s.enemy));
            assertEquals(1, ghost(s).pairCount());
            assertTrue(s.enemy.getFoodLevel() <= 6, "no sprinting barefoot");
            assertEquals(-s.param("barefoot_armor"), modifierSum(s.enemy, Attribute.ARMOR), EPSILON);
            assertEquals(s.param("barefoot_fall") / 100, modifierSum(s.enemy, Attribute.FALL_DAMAGE_MULTIPLIER),
                    EPSILON);
            assertEquals(-s.param("barefoot_slow") / 100, modifierSum(s.enemy, Attribute.MOVEMENT_SPEED), EPSILON);
            assertEquals(s.param("shoe_speed") / 100, modifierSum(s.owner, Attribute.MOVEMENT_SPEED), EPSILON,
                    "the ghost wears them");
            s.enemy.setSprinting(true);
            s.ticks(1);
            assertFalse(s.enemy.isSprinting(), "the sprint stops");
            s.enemy.setFoodLevel(20);
            s.ticks(1);
            assertTrue(s.enemy.getFoodLevel() <= 6, "eating does not help");

            s.ticks(s.paramTicks("barefoot_duration"));
            assertFalse(ghost(s).isBarefoot(s.enemy), "the shoes come back in time");
            assertEquals(20, s.enemy.getFoodLevel(), "hunger restored");
            assertModifierFree(s.enemy, "shod again");
            assertEquals(1, ghost(s).pairCount(), "the ghost keeps wearing the pair");

            // Hitting the ghost while barefoot takes the shoes back at once.
            s.readyActive();
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "second snatch");
            s.ticks(5);
            assertTrue(ghost(s).isBarefoot(s.enemy));
            s.melee(s.enemy, s.owner, 1);
            assertFalse(ghost(s).isBarefoot(s.enemy), "taken back");
            assertEquals(0, ghost(s).pairCount(), "every pair of that enemy is gone");
            assertModifierFree(s.enemy, "taken back");
            assertModifierFree(s.owner, "no shoes, no speed");
            assertEquals(20, s.enemy.getFoodLevel());
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void yagwanggwiNightDarkensBrushesShoesOffAndThrowsThemBackAtDawn() {
        try (Scenario s = Scenario.ffa("yagwanggwi")) {
            s.ticks(5);
            s.clearHits();
            s.assertSucceeded(s.ultimate(), "New Year's night");
            assertTrue(modifierSum(s.owner, Attribute.MOVEMENT_SPEED) >= s.param("night_speed") / 100 - EPSILON);
            s.place(s.enemy, 0.5, 1.4, 180);
            s.ticks(2);
            assertEquals(s.param("brush_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "brushed");
            assertTrue(ghost(s).isBarefoot(s.enemy));
            s.place(s.enemy, 0.5, 4.5, 180);
            s.ticks(s.paramTicks("brush_cooldown") + 2);
            assertTrue(s.enemy2.hasPotionEffect(PotionEffectType.DARKNESS), "the night darkens the enemies around");
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON, "never brushed");
            BlockDisplay worn = blocks(s, Material.HAY_BLOCK).getFirst();
            boolean thrownItself = false;
            for (int i = 0; i < s.paramTicks("night_duration") + 20; i++) {
                s.ticks(1);
                thrownItself |= worn.isValid() && worn.getTeleportDuration() == 1;
            }
            assertTrue(thrownItself, "the worn sandal itself flies back from the waist");
            assertEquals(s.param("brush_damage") + s.param("return_damage"), s.damageBy(s.owner, s.enemy), EPSILON,
                    "the pair flew back at dawn");
            assertEquals(0, ghost(s).pairCount(), "every pair thrown back");
            assertFalse(ghost(s).isBarefoot(s.enemy), "the shoes are home");
            assertFalse(s.enemy2.hasPotionEffect(PotionEffectType.DARKNESS), "dawn lifts the dark");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            assertModifierFree(s.enemy, "dawn");
            assertModifierFree(s.owner, "dawn");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void yagwanggwiWornSandalsTurnWithTheGhostAndHideWithIt() {
        try (Scenario s = Scenario.ffa("yagwanggwi")) {
            s.ticks(5);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "snatch");
            s.h.tickUntil(() -> !blocks(s, Material.HAY_BLOCK).isEmpty(), 10, "the stolen sandal");
            BlockDisplay sandal = blocks(s, Material.HAY_BLOCK).getFirst();
            assertTrue(sandal.isGlowing(), "it glows on its way to the waist");
            s.ticks(8);
            assertFalse(sandal.isGlowing(), "a worn sandal does not glow through walls");

            s.owner.setBodyYaw(90);
            s.ticks(1);
            Vector3f expected = new Quaternionf().rotateY((float) Math.toRadians(-(90 + 180)))
                    .rotateX((float) Math.toRadians(75)).transform(new Vector3f(0, 0, 1));
            Vector3f actual = rotationOf(sandal).transform(new Vector3f(0, 0, 1));
            assertTrue(actual.distance(expected) < 1.0E-3, "turned with the body at once: " + actual);

            assertTrue(s.enemy.canSee(sandal));
            s.owner.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, 200, 0));
            s.ctx().refreshViewers();
            assertFalse(s.enemy.canSee(sandal), "an invisible ghost's sandals hide with it");
            assertTrue(s.owner.canSee(sandal));
            s.assertNoProblems();
            s.finishClean();
        }
    }

    // ================================================================== 청개구리 frog

    private static FrogAbility frog(Scenario s) {
        return (FrogAbility) s.ability();
    }

    @Test
    void frogKnockbackPullsInsteadOfPushing() {
        try (Scenario s = Scenario.ffa("frog")) {
            s.ticks(5);
            EntityKnockbackEvent knock = new EntityKnockbackEvent(s.owner, EntityKnockbackEvent.Cause.ENTITY_ATTACK,
                    new Vector(0.4, 0.36, 0));
            s.h.server.getPluginManager().callEvent(knock);
            double share = s.param("reverse_share") / 100;
            assertEquals(-0.4 * share, knock.getKnockback().getX(), EPSILON, "pulled towards the attacker");
            assertTrue(knock.getKnockback().getY() >= 0, "never slammed down");
            assertFalse(texts(s, "거꾸로").isEmpty(), "거꾸로!");
            EntityKnockbackEvent blast = new EntityKnockbackEvent(s.owner, EntityKnockbackEvent.Cause.EXPLOSION,
                    new Vector(0.4, 0.36, 0));
            s.h.server.getPluginManager().callEvent(blast);
            assertEquals(0.4, blast.getKnockback().getX(), EPSILON, "explosions stay explosions");
            EntityKnockbackEvent other = new EntityKnockbackEvent(s.enemy, EntityKnockbackEvent.Cause.ENTITY_ATTACK,
                    new Vector(0.4, 0.36, 0));
            s.h.server.getPluginManager().callEvent(other);
            assertEquals(0.4, other.getKnockback().getX(), EPSILON, "only the frog itself");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void frogCroakTurnsTheEnemiesKeysAround() {
        try (Scenario s = Scenario.ffa("frog")) {
            s.ticks(5);
            s.place(s.enemy2, 6.5, 0.5, 90);
            s.aimAt(s.owner, s.enemy);
            s.clearHits();
            s.assertSucceeded(s.active(), "croak");
            s.ticks(s.paramTicks("croak_windup") + 8);
            assertEquals(s.param("croak_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "the wave hits");
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON, "outside the fan");
            assertTrue(frog(s).isReversed(s.enemy));
            assertFalse(frog(s).isReversed(s.enemy2));
            assertFalse(texts(s, "⇅").isEmpty(), "the reversed arrow");
            assertAllUnrotated(s, "croak");

            // Forward walks back: the enemy faces the frog (-z) and walks away from it (+z).
            Location before = s.enemy.getLocation();
            s.enemy.sendInput(InputKey.FORWARD);
            s.ticks(6);
            double moved = s.enemy.getLocation().getZ() - before.getZ();
            assertTrue(moved > 0.3, "forward walks backwards: " + moved);
            s.enemy.sendInput(InputKey.LEFT);
            before = s.enemy.getLocation();
            s.ticks(6);
            // Facing -z, left is -x: reversed it walks to +x.
            assertTrue(s.enemy.getLocation().getX() - before.getX() > 0.3, "left walks right");
            s.enemy.sendInput();
            s.ticks(s.paramTicks("reverse_duration"));
            assertFalse(frog(s).isReversed(s.enemy), "the spell wears off");
            assertModifierFree(s.enemy, "keys back");
            assertTrue(texts(s, "⇅").isEmpty(), "the arrow is gone");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void frogRainReversesSoakedEnemiesTicksAndSpeedsUpTheFrog() {
        try (Scenario s = Scenario.ffa("frog")) {
            s.ticks(5);
            s.place(s.enemy2, 14.5, 0.5, 90);
            s.clearHits();
            s.assertSucceeded(s.ultimate(), "monsoon");
            s.ticks(3);
            assertTrue(frog(s).isRaining());
            assertTrue(frog(s).isReversed(s.enemy), "soaked");
            assertFalse(frog(s).isReversed(s.enemy2), "outside the rain");
            assertEquals(s.param("rain_speed") / 100, modifierSum(s.owner, Attribute.MOVEMENT_SPEED), EPSILON,
                    "the frog splashes along");
            EntityKnockbackByEntityEvent knock = new EntityKnockbackByEntityEvent(s.enemy2, s.enemy,
                    EntityKnockbackEvent.Cause.ENTITY_ATTACK, 0.4f, new Vector(0.4, 0.3, 0));
            s.h.server.getPluginManager().callEvent(knock);
            assertEquals(-0.4, knock.getKnockback().getX(), EPSILON, "a soaked enemy's blow pulls");
            s.ticks(s.paramTicks("rain_duration"));
            double ticksOfDamage = Math.floor(s.param("rain_duration") / s.param("rain_interval"));
            assertEquals(ticksOfDamage * s.param("rain_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "croak, croak");
            assertEquals(0, s.damageTaken(s.enemy2), EPSILON);
            s.ticks(12);
            assertFalse(frog(s).isRaining());
            assertFalse(frog(s).isReversed(s.enemy), "dry again");
            assertModifierFree(s.owner, "after the rain");
            assertModifierFree(s.enemy, "after the rain");
            assertNotNull(s.owner);
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void frogReversedWalkNeverCancelsAPushAndTheSacStaysOutOfTheFrogsView() {
        try (Scenario s = Scenario.ffa("frog")) {
            s.ticks(5);
            s.place(s.enemy2, 6.5, 0.5, 90);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "croak");
            s.ticks(1);
            List<BlockDisplay> sac = blocks(s, Material.WHITE_STAINED_GLASS);
            assertEquals(2, sac.size(), "the throat sac");
            for (BlockDisplay part : sac) {
                assertFalse(s.owner.canSee(part), "not under the frog's own chin");
                assertTrue(s.enemy.canSee(part), "everyone else sees it swell");
            }
            s.ticks(s.paramTicks("croak_windup") + 8);
            assertTrue(frog(s).isReversed(s.enemy));
            s.enemy.sendInput(InputKey.FORWARD);
            s.ticks(2);
            double x = s.enemy.getLocation().getX();
            s.enemy.setVelocity(new Vector(1.0, 0, 0));
            s.ticks(6);
            assertTrue(s.enemy.getLocation().getX() - x > 1.6, "the push carries the enemy on: "
                    + (s.enemy.getLocation().getX() - x));
            s.enemy.sendInput();
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void frogRainCloudCoversTheRain() {
        try (Scenario s = Scenario.ffa("frog")) {
            s.ticks(5);
            Location center = s.owner.getLocation();
            s.assertSucceeded(s.ultimate(), "monsoon");
            s.ticks(12);
            List<BlockDisplay> puffs = new ArrayList<>(blocks(s, Material.GRAY_WOOL));
            puffs.addAll(blocks(s, Material.LIGHT_GRAY_WOOL));
            assertEquals(12, puffs.size(), "a centre puff and two rings");
            double radius = s.param("rain_radius");
            for (BlockDisplay puff : puffs) {
                assertUnrotated(puff, "puff");
                assertTrue(puff.getDisplayWidth() >= puff.getTransformation().getScale().x,
                        "the culling box covers the puff");
            }
            for (double share = 0; share <= 0.8 + 1.0E-9; share += 0.1) {
                for (int deg = 0; deg < 360; deg += 5) {
                    double px = center.getX() + Math.cos(Math.toRadians(deg)) * share * radius;
                    double pz = center.getZ() + Math.sin(Math.toRadians(deg)) * share * radius;
                    boolean covered = puffs.stream().anyMatch(puff -> Math.hypot(puff.getLocation().getX() - px,
                            puff.getLocation().getZ() - pz) <= puff.getTransformation().getScale().x / 2);
                    assertTrue(covered, "the cloud covers the rain at " + share + " / " + deg + "°");
                }
            }
            s.assertNoProblems();
            s.finishClean();
        }
    }
}
