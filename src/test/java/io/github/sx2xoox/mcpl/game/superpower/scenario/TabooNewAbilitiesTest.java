package io.github.sx2xoox.mcpl.game.superpower.scenario;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import io.github.sx2xoox.mcpl.game.superpower.SuperpowerSettings;
import io.github.sx2xoox.mcpl.game.superpower.abilities.AnubisAbility;
import io.github.sx2xoox.mcpl.game.superpower.abilities.JiangshiAbility;
import io.github.sx2xoox.mcpl.game.superpower.abilities.ProhibitorAbility;
import io.github.sx2xoox.mcpl.game.superpower.abilities.RedMaskAbility;
import io.github.sx2xoox.mcpl.game.superpower.abilities.SphinxAbility;
import io.github.sx2xoox.mcpl.game.superpower.abilities.kit.Inputs;
import io.github.sx2xoox.mcpl.game.superpower.ability.Ability;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import io.github.sx2xoox.mcpl.game.superpower.ability.ActivationResult;
import io.github.sx2xoox.mcpl.game.superpower.ability.InputKey;
import io.github.sx2xoox.mcpl.game.superpower.support.Scenario;
import io.github.sx2xoox.mcpl.game.superpower.support.TestPlayer;
import io.github.sx2xoox.mcpl.game.superpower.support.world.FlatWorld;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** 규칙 · 시험 (new): 금제술사, 스핑크스, 아누비스, 빨간 마스크, 강시. */
class TabooNewAbilitiesTest {

    private static final double EPSILON = 1.0E-6;
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z0-9_]+)}");

    static Stream<String> abilities() {
        return Stream.of("prohibitor", "sphinx", "anubis", "redmask", "jiangshi");
    }

    /** The ultimates the gambler may borrow. */
    static Stream<String> standalone() {
        return Stream.of("sphinx", "jiangshi");
    }

    /** See ShapeNewAbilitiesTest: the shared sweep's registry is initialized before any scenario runs. */
    @BeforeAll
    static void initializeSharedSweeps() {
        assertTrue(AbilityLifecycleTest.abilities().findAny().isPresent());
    }

    // ================================================================== helpers

    private static <T extends Entity> List<T> entities(Scenario s, Class<T> type) {
        return s.world.getEntities().stream().filter(type::isInstance).map(type::cast).filter(Entity::isValid)
                .toList();
    }

    private static List<TextDisplay> texts(Scenario s, String containing) {
        return entities(s, TextDisplay.class).stream()
                .filter(text -> Scenario.plain(text.text()).contains(containing))
                .toList();
    }

    /**
     * Where a flat (face-up) text display's text visibly lies: text displays are bottom-anchored, so the middle of
     * the text is half its height (11/40 of a block per line and unit scale, 10 without the margin) up its rotated
     * vertical from the origin plus translation.
     */
    private static Location flatCentre(TextDisplay text, int lines) {
        org.bukkit.util.Transformation t = text.getTransformation();
        org.joml.Vector3f up = t.getLeftRotation().transform(
                new org.joml.Vector3f(0, (float) ((lines * 10 + 1) / 80.0) * t.getScale().y, 0));
        return text.getLocation().add(t.getTranslation().x + up.x, t.getTranslation().y + up.y,
                t.getTranslation().z + up.z);
    }

    /** The middle of a display's model: a block display's cube is cornered at its translation. */
    private static org.joml.Vector3f modelCentre(Display display) {
        org.bukkit.util.Transformation t = display.getTransformation();
        org.joml.Vector3f centre = new org.joml.Vector3f(t.getTranslation());
        if (display instanceof BlockDisplay) {
            centre.add(t.getLeftRotation().transform(new org.joml.Vector3f(t.getScale()).mul(0.5f)));
        }
        return centre;
    }

    private static void assertUnrotated(Entity display, String what) {
        assertEquals(0, display.getLocation().getYaw(), 1.0E-3, what + ": display yaw");
        assertEquals(0, display.getLocation().getPitch(), 1.0E-3, what + ": display pitch");
    }

    private static void assertAllDisplaysUnrotated(Scenario s, String what) {
        for (Display display : entities(s, Display.class)) {
            assertUnrotated(display, what + " " + display.getType());
        }
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

    private static boolean slowed(Player player) {
        for (Attribute attribute : List.of(Attribute.MOVEMENT_SPEED, Attribute.JUMP_STRENGTH)) {
            for (AttributeModifier modifier : player.getAttribute(attribute).getModifiers()) {
                if (modifier.getAmount() < 0) {
                    return true;
                }
            }
        }
        return false;
    }

    private static double speedBonus(Player player) {
        double sum = 0;
        for (AttributeModifier modifier : player.getAttribute(Attribute.MOVEMENT_SPEED).getModifiers()) {
            sum += modifier.getAmount();
        }
        return sum;
    }

    private static int slack(Scenario s, Player player) {
        return s.ctx().reactionSlackTicks(player);
    }

    /** Lets the layout teleports stop counting as displacement for the input rules. */
    private static void settle(Scenario s) {
        s.ticks((int) Inputs.DISPLACED_TICKS + 2);
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

    // ================================================================== every ability

    @Test
    void descriptionsOnlyReferenceDeclaredParams() {
        for (AbilityInfo info : List.of(ProhibitorAbility.INFO, SphinxAbility.INFO, AnubisAbility.INFO,
                RedMaskAbility.INFO, JiangshiAbility.INFO)) {
            for (AbilityInfo.Skill skill : List.of(info.passive(), info.active(), info.ultimate())) {
                assertFalse(skill.name().equals("TODO"), info.id() + " is implemented");
                Matcher matcher = PLACEHOLDER.matcher(skill.description());
                while (matcher.find()) {
                    assertTrue(info.params().containsKey(matcher.group(1)),
                            info.id() + " " + skill.name() + " uses an undeclared param " + matcher.group());
                }
            }
        }
        assertFalse(ProhibitorAbility.INFO.ultimateStandalone(), "the barrier uses the violator brand and hooks");
        assertFalse(AnubisAbility.INFO.ultimateStandalone(), "the judgement reads the passive's ledger");
        assertFalse(RedMaskAbility.INFO.ultimateStandalone(), "the anger is the mask's melee hook");
        assertTrue(SphinxAbility.INFO.ultimateStandalone(), "the board runs on its own");
        assertTrue(JiangshiAbility.INFO.ultimateStandalone(), "the breath runs on its own");
        assertEquals(3, ProhibitorAbility.INFO.difficulty(), "one ★★★ per group");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("abilities")
    void alliesAreNeverJudgedOrHurt(String id) {
        try (Scenario s = Scenario.teams(id)) {
            settle(s);
            s.crossfireAlly();
            s.aimAt(s.owner, s.enemy);
            settle(s);
            Location start = s.ally().getLocation();
            // The ally does everything the rules forbid: it walks, jumps, attacks and casts.
            s.ally().setInput(io.github.sx2xoox.mcpl.game.superpower.support.TestInput.of(InputKey.FORWARD,
                    InputKey.JUMP));
            s.ally().setSprinting(true);
            s.clearHits();
            s.assertSucceeded(s.active(), id + " active");
            for (int i = 0; i < 80; i++) {
                if (i % 10 == 0) {
                    s.ally().attackAttempt(s.enemy);
                }
                s.ticks(1);
            }
            s.layout();
            s.crossfireAlly();
            s.aimAt(s.owner, s.enemy);
            start = s.ally().getLocation();
            s.assertSucceeded(s.ultimate(), id + " ultimate");
            for (int i = 0; i < 180; i++) {
                if (i % 10 == 0) {
                    s.ally().attackAttempt(s.enemy);
                    s.match.useActive(s.ally());
                }
                s.ticks(1);
                assertEquals(0, s.damageTaken(s.ally()), EPSILON, id + " never damages the ally: " + s.hits());
                assertFalse(s.ctx().isSilenced(s.ally()), id + " never silences the ally");
                assertFalse(slowed(s.ally()), id + " never slows the ally");
                assertTrue(s.ally().getLocation().distance(start) < 0.5, id + " never moves the ally");
            }
            assertAllDisplaysUnrotated(s, id);
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("standalone")
    void borrowedUltimateWorksOnAGambler(String id) {
        try (Scenario s = gambler(false)) {
            settle(s);
            assertTrue(s.ctx().registry().get(id).info().ultimateStandalone(), id + " is borrowable");
            // Something for the breath to find: both enemies keep walking.
            s.enemy.sendInput(InputKey.FORWARD);
            s.enemy2.sendInput(InputKey.FORWARD);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(borrow(s, id), "borrowed " + id);
            boolean worked = false;
            for (int i = 0; i < 300; i++) {
                s.ticks(1);
                worked |= s.damageBy(s.owner, s.enemy) + s.damageBy(s.owner, s.enemy2) > 0;
            }
            assertTrue(worked, "the borrowed " + id + " ultimate does its thing");
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "the borrowed " + id + " never hurts the gambler");
            s.ticks(80);
            assertModifierFree(s.owner, id);
            for (TestPlayer target : s.enemies()) {
                assertModifierFree(target, id);
                assertFalse(s.ctx().isSilenced(target), id + ": the silence wore off");
            }
            assertTrue(entities(s, Display.class).isEmpty(), id + ": every display is gone");
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
            s.ticks(30);
            s.killOwner(s.enemy);
            s.ticks(1);
            for (Entity entity : s.world.getEntities()) {
                assertTrue(entity instanceof Player, id + ": " + entity.getType() + " outlived the gambler");
            }
            s.clearHits();
            s.ticks(200);
            assertTrue(s.hits().stream().noneMatch(hit -> s.owner.equals(hit.causer())),
                    id + ": the dead gambler dealt damage: " + s.hits());
            s.assertNoProblems();
            s.finishClean();
        }
    }

    // ================================================================== 금제술사

    /** Casts the taboo on {@code target} and waits until its ring closed and its judging started. */
    private static void writeTaboo(Scenario s, Player target) {
        s.aimAt(s.owner, target);
        s.assertSucceeded(s.active(), "금제의 글자");
        s.ticks(s.paramTicks("draw_seconds") + 1);
    }

    private static void waitForJudging(Scenario s, Player target) {
        s.ticks(s.paramTicks("judge_grace") + slack(s, target) + 2);
    }

    @Test
    void prohibitorStrikeTabooBlowsUpOnAnAttackAndBrandsTheViolator() {
        try (Scenario s = Scenario.ffa("prohibitor")) {
            settle(s);
            writeTaboo(s, s.enemy);
            assertFalse(texts(s, "때리지 마라").isEmpty(), "standing still: the taboo is '때리지 마라', over the head");
            List<TextDisplay> glyphs = entities(s, TextDisplay.class).stream()
                    .filter(text -> text.getBillboard() == Display.Billboard.FIXED).toList();
            assertEquals(9, glyphs.size(), "eight ink letters and the seal lie flat around the feet");
            for (TextDisplay glyph : glyphs) {
                assertUnrotated(glyph, "flat letter");
                assertEquals(s.enemy, glyph.getVehicle(), "the letters ride the enemy (no lag behind its feet)");
                Location lies = flatCentre(glyph, 1);
                assertTrue(Math.hypot(lies.getX() - s.enemy.getLocation().getX(),
                        lies.getZ() - s.enemy.getLocation().getZ()) < 1.5, "around the enemy's feet: " + lies);
                assertEquals(s.enemy.getLocation().getY(), lies.getY(), 0.1, "on the ground");
                // Laid flat: the text's normal points up.
                org.joml.Vector3f normal = glyph.getTransformation().getLeftRotation()
                        .transform(new org.joml.Vector3f(0, 0, 1));
                assertTrue(normal.y > 0.99, "the letter faces the sky: " + normal);
            }
            s.clearHits();
            s.enemy.attackAttempt(s.enemy2);
            s.ticks(1);
            assertEquals(0, s.damageBy(s.owner, s.enemy), EPSILON, "an attack during the grace does not count");
            waitForJudging(s, s.enemy);
            s.enemy.attackAttempt(s.enemy2);
            s.ticks(1);
            assertEquals(s.param("break_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "the letters burst");
            assertTrue(slowed(s.enemy), "and the violator staggers");
            assertFalse(texts(s, "犯").isEmpty(), "a red '犯' hangs over the violator");
            assertEquals(s.enemy, texts(s, "犯").getFirst().getVehicle(), "it rides the violator's head");
            assertTrue(texts(s, "때리지 마라").isEmpty(), "the taboo is lifted");

            // Branded: every other hit lands harder.
            s.ticks(s.paramTicks("break_stagger") + 2);
            s.clearHits();
            s.melee(s.owner, s.enemy, 5);
            assertEquals(5 * (1 + s.param("violator_bonus") / 100), s.damageBy(s.owner, s.enemy), EPSILON,
                    "the violator takes more from the prohibitor");

            // A violator who breaks a taboo again pays the repeat price.
            s.readyActive();
            settle(s);
            s.clearHits();
            writeTaboo(s, s.enemy);
            waitForJudging(s, s.enemy);
            s.enemy.attackAttempt(s.enemy2);
            s.ticks(1);
            assertEquals(s.param("break_damage") * s.param("violator_repeat"), s.damageBy(s.owner, s.enemy), EPSILON,
                    "a repeat violation hurts ×" + s.param("violator_repeat"));
            assertAllDisplaysUnrotated(s, "prohibitor");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void prohibitorKeptTabooCostsOnlyTheInk() {
        try (Scenario s = Scenario.ffa("prohibitor")) {
            settle(s);
            writeTaboo(s, s.enemy);
            s.clearHits();
            s.ticks(s.paramTicks("taboo_duration") + slack(s, s.enemy) + 4);
            assertEquals(s.param("keep_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "a kept taboo: the ink bleeds");
            assertTrue(texts(s, "犯").isEmpty(), "nobody is branded");
            s.ticks(20);
            assertTrue(entities(s, Display.class).isEmpty(), "the letters are gone");
            s.finishClean();
        }
    }

    @Test
    void prohibitorWritesWhatTheEnemyWasDoing() {
        try (Scenario s = Scenario.ffa("prohibitor")) {
            settle(s);
            // A sprinting enemy: "don't run".
            s.enemy.setInput(io.github.sx2xoox.mcpl.game.superpower.support.TestInput.of(InputKey.FORWARD,
                    InputKey.SPRINT));
            s.enemy.setSprinting(true);
            writeTaboo(s, s.enemy);
            assertFalse(texts(s, "달리지 마라").isEmpty(), "a runner is told not to run");
            s.clearHits();
            s.ticks(2);
            assertEquals(0, s.damageBy(s.owner, s.enemy), EPSILON, "it has a moment to stop");
            waitForJudging(s, s.enemy);
            assertEquals(s.param("break_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "still running: broken");
            s.enemy.setSprinting(false);
            s.enemy.sendInput();

            // An enemy in the air: "don't jump", judged only once it has landed.
            s.readyActive();
            s.ticks(30);
            s.place(s.enemy2, 2.0, FlatWorld.SURFACE + 4, 6.5, 180, 0);
            writeTaboo(s, s.enemy2);
            assertFalse(texts(s, "뛰지 마라").isEmpty(), "an enemy in the air is told not to jump");
            s.clearHits();
            s.tickUntilGrounded(s.enemy2, 40);
            s.ticks((int) Inputs.DISPLACED_TICKS + slack(s, s.enemy2) + 2);
            assertEquals(0, s.damageBy(s.owner, s.enemy2), EPSILON, "landing is no jump");
            s.enemy2.sendInput(InputKey.JUMP);
            s.ticks(1);
            assertEquals(s.param("break_damage"), s.damageBy(s.owner, s.enemy2), EPSILON, "a jump after landing");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void prohibitorBarrierPunishesStandingStillAndBindsItsCaster() {
        try (Scenario s = Scenario.ffa("prohibitor")) {
            settle(s);
            // Crouching: "don't stop". The caster keeps walking, the enemies stand still.
            s.owner.setSneaking(true);
            s.owner.sendInput(InputKey.FORWARD, InputKey.SNEAK);
            s.clearHits();
            s.assertSucceeded(s.ultimate(), "금제 결계");
            assertFalse(texts(s, "멈추지 마라").isEmpty(), "the rule is written for everyone");
            s.ticks(s.paramTicks("barrier_duration") + 4);
            double each = s.param("barrier_damage");
            int max = (int) s.param("barrier_max");
            // The first violation brands the enemy: the later ones are repeat violations.
            assertEquals(each + each * s.param("violator_repeat") * (max - 1), s.damageBy(s.owner, s.enemy), EPSILON,
                    "standing still is punished, at most " + max + " times");
            assertEquals(max, s.hitsBy(s.owner, s.enemy).size());
            assertEquals(0, s.damageTaken(s.owner), EPSILON, "the caster kept its own rule");
            s.ticks(20);
            assertTrue(texts(s, "結界").isEmpty(), "the barrier lifted");

            // The caster stops: its own barrier shatters at once.
            s.owner.sendInput(InputKey.SNEAK);
            s.healAll();
            s.clearHits();
            s.assertSucceeded(s.ultimate(), "금제 결계 again");
            s.ticks(s.paramTicks("stop_grace") + 2 * slack(s, s.owner) + 4);
            assertFalse(texts(s, "붕괴").isEmpty(), "the barrier breaks when its caster breaks the rule");
            s.ticks(30);
            assertTrue(texts(s, "結界").isEmpty() && texts(s, "封").isEmpty(), "nothing of it is left");
            assertEquals(0, s.damageBy(s.owner, s.enemy2), EPSILON, "a broken barrier punishes nobody");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void prohibitorBarrierForbidsSkillsAndWalkingOutButNotBeingPushedOut() {
        try (Scenario s = Scenario.ffa("prohibitor")) {
            settle(s);
            s.clearHits();
            s.assertSucceeded(s.ultimate(), "금제 결계");
            assertFalse(texts(s, "능력을 쓰지 마라").isEmpty(), "standing: no skills");
            s.ticks(slack(s, s.enemy2) + 2);
            s.assertSucceeded(s.match.useActive(s.enemy2), "Blue casts as the rule appears");
            s.ticks(2);
            assertEquals(0, s.damageBy(s.owner, s.enemy2), EPSILON, "a cast within the grace is no violation");
            s.ticks(s.paramTicks("judge_grace"));
            s.assertSucceeded(s.match.useActive(s.enemy), "the enemy casts");
            s.ticks(2);
            assertEquals(s.param("barrier_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "a cast inside is punished");

            // Pushed out: exempt. Walking out: punished.
            double radius = s.param("barrier_radius");
            s.enemy2.setVelocity(new Vector(0, 0.2, 3));
            s.ticks(4);
            assertTrue(s.enemy2.getLocation().getZ() > radius, "pushed out of the barrier");
            assertEquals(0, s.damageBy(s.owner, s.enemy2), EPSILON, "being pushed out is no violation");

            s.place(s.enemy, 0.5, radius - 0.6, 0);
            settle(s);
            s.enemy.sendInput(InputKey.FORWARD);
            s.clearHits();
            for (int i = 0; i < 5 && s.enemy.getLocation().getZ() <= radius + 0.5; i++) {
                s.enemy.setClientVelocity(new Vector(0, 0, 0.5));
                s.ticks(1);
            }
            s.ticks(1);
            assertEquals(s.param("barrier_damage") * s.param("violator_repeat"), s.damageBy(s.owner, s.enemy), EPSILON,
                    "walking out is punished (a repeat violation)");

            // The caster casts: the barrier breaks.
            s.place(s.enemy, 0.5, 4.5, 180);
            s.readyActive();
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "the caster's own skill");
            s.ticks(3);
            assertFalse(texts(s, "붕괴").isEmpty(), "using a skill breaks its own 'no skills' barrier");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    // ================================================================== 스핑크스

    /** The riddle's picture from the tablet over {@code target}'s head ("[ ☀ ] ?"). */
    private static String riddlePicture(Scenario s, Player target) {
        for (TextDisplay text : entities(s, TextDisplay.class)) {
            String plain = Scenario.plain(text.text());
            if (plain.startsWith("[") && text.getLocation().distance(target.getLocation()) < 4) {
                return plain.substring(1, plain.indexOf(']')).trim();
            }
        }
        fail("no riddle tablet over " + target.getName());
        return "";
    }

    /** The flat tile showing {@code glyphOrWord}. */
    private static TextDisplay tile(Scenario s, String glyphOrWord) {
        for (TextDisplay text : entities(s, TextDisplay.class)) {
            if (text.getBillboard() == Display.Billboard.FIXED
                    && Scenario.plain(text.text()).lines().anyMatch(line -> line.trim().equals(glyphOrWord))) {
                return text;
            }
        }
        fail("no tile with " + glyphOrWord);
        return null;
    }

    @Test
    void sphinxRiddleClawsAWrongAnswerAndSparesTheRightOne() {
        try (Scenario s = Scenario.ffa("sphinx")) {
            settle(s);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "수수께끼");
            s.ticks(8);
            List<TextDisplay> tiles = entities(s, TextDisplay.class).stream()
                    .filter(text -> text.getBillboard() == Display.Billboard.FIXED).toList();
            assertEquals(3, tiles.size(), "three picture tiles rise around the enemy");
            for (TextDisplay tile : tiles) {
                double distance = tile.getLocation().distance(s.enemy.getLocation());
                assertTrue(distance > 1 && distance <= s.param("tile_distance") + 0.5, "tiles around it: " + distance);
                assertEquals(FlatWorld.SURFACE, tile.getLocation().getY(), 0.3, "on the ground");
            }
            assertEquals(3, entities(s, BlockDisplay.class).stream()
                    .filter(d -> d.getBlock().getMaterial() != Material.CHISELED_SANDSTONE).count(),
                    "a sandstone plate under each picture");
            assertTrue(entities(s, BlockDisplay.class).stream()
                    .anyMatch(d -> d.getBlock().getMaterial() == Material.CHISELED_SANDSTONE
                            && s.enemy.equals(d.getVehicle())), "the tablet rides the riddled head");

            // The passive: a riddled enemy hits softer.
            s.clearHits();
            s.melee(s.enemy, s.owner, 10);
            assertEquals(10 * (1 - s.param("riddle_guard") / 100), s.damageTaken(s.owner), EPSILON,
                    "a riddled enemy hits the sphinx softer");

            // Standing still on no tile: the claws.
            s.healAll();
            s.match.setGauge(s.owner, 0);
            s.clearHits();
            s.ticks(s.paramTicks("answer_seconds") + slack(s, s.enemy) + 10);
            assertEquals(s.param("claw_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "a wrong answer is clawed");
            assertTrue(s.ctx().isSilenced(s.enemy), "and silenced");
            assertTrue(s.ctx().gauge(s.owner) >= s.param("wrong_gauge") - EPSILON, "the sphinx feeds on mistakes");

            // The right tile: only the tablet breaks.
            s.ticks(40);
            s.readyActive();
            s.clearHits();
            s.aimAt(s.owner, s.enemy2);
            s.assertSucceeded(s.active(), "수수께끼 again");
            s.ticks(8);
            String picture = riddlePicture(s, s.enemy2);
            Location spot = tile(s, picture).getLocation();
            s.place(s.enemy2, spot.getX(), spot.getZ(), 180);
            s.ticks(s.paramTicks("answer_seconds") + slack(s, s.enemy2) + 10);
            assertEquals(s.param("right_damage"), s.damageBy(s.owner, s.enemy2), EPSILON, "the right tile is safe");
            assertFalse(s.ctx().isSilenced(s.enemy2), "no silence for a right answer");
            assertAllDisplaysUnrotated(s, "sphinx");
            s.ticks(20);
            assertTrue(entities(s, Display.class).isEmpty(), "tiles and tablets are gone");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    /** Which picture answers a riddle line (the riddles of the board). */
    private static final Map<String, String> ANSWERS = Map.of(
            "낮에만", "해", "모양을 바꾸는", "달", "반짝이는", "별", "세 발", "사람", "다리 없이", "뱀",
            "풀어 주면", "물", "물을 마시면", "불", "구름을", "산", "하늘을 나는", "새");

    private static String answerOf(String line) {
        for (Map.Entry<String, String> entry : ANSWERS.entrySet()) {
            if (line.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return "";
    }

    @Test
    void sphinxBoardPunishesEveryWrongSquareAndOedipusHumblesIt() {
        try (Scenario s = Scenario.ffa("sphinx")) {
            settle(s);
            s.clearHits();
            s.assertSucceeded(s.ultimate(), "세 번째 수수께끼");
            s.ticks(14);
            assertEquals(9, entities(s, TextDisplay.class).stream()
                    .filter(text -> text.getBillboard() == Display.Billboard.FIXED).count(), "a 3×3 board of pictures");
            // Red answers every riddle on the right square; Blue stands still.
            String answered = "";
            int moved = 0;
            for (int i = 0; i < 160; i++) {
                for (var bar : s.enemy.actionBars()) {
                    String line = Scenario.plain(bar);
                    if (line.contains("/3")) {
                        String word = answerOf(line);
                        if (!word.isEmpty() && !line.equals(answered)) {
                            answered = line;
                            Location spot = tile(s, word).getLocation();
                            s.place(s.enemy, spot.getX(), spot.getZ(), 180);
                            moved++;
                        }
                    }
                }
                s.ticks(1);
            }
            assertEquals(3, moved, "three riddles were asked");
            assertTrue(answered.contains("세 발"), "the third riddle is the famous one: " + answered);
            assertEquals(0, s.damageBy(s.owner, s.enemy), EPSILON, "every right answer is spared");
            assertEquals(s.param("oedipus_damage"), s.damageBy(s.enemy, s.owner), EPSILON,
                    "whoever solves all three humbles the sphinx");
            double blue = s.damageBy(s.owner, s.enemy2);
            assertTrue(blue >= 2 * s.param("miss_damage") - EPSILON, "standing still is wrong at least twice: " + blue);
            if (s.ctx().isSilenced(s.enemy2)) {
                assertEquals(3 * s.param("miss_damage") + s.param("all_wrong_damage"), blue, EPSILON,
                        "all three wrong: '정답은 인간이었다'");
            }
            s.ticks(30);
            assertTrue(entities(s, Display.class).isEmpty(), "the board sank");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    // ================================================================== 아누비스

    @Test
    void anubisReadsSinAndHitsTheSinfulHarder() {
        try (Scenario s = Scenario.ffa("anubis")) {
            settle(s);
            s.melee(s.enemy, s.enemy2, 4);
            s.enemy.actionBars();
            s.ticks(22);
            assertTrue(s.enemy.actionBars().stream().map(Scenario::plain).anyMatch(line -> line.contains("죄 4")),
                    "the sinner reads its sin on its action bar");
            List<BlockDisplay> scales = entities(s, BlockDisplay.class);
            assertFalse(scales.isEmpty(), "the jackal sees small scales beside the heads");
            for (BlockDisplay scale : scales) {
                assertEquals(List.of(s.owner), s.seenBy(scale), "the small scales are the jackal's alone");
                assertUnrotated(scale, "small scale");
            }
            s.clearHits();
            s.melee(s.owner, s.enemy, 5);
            assertEquals(5 * (1 + 4 * s.param("sin_bonus") / 100), s.damageBy(s.owner, s.enemy), EPSILON,
                    "+" + s.param("sin_bonus") + "% per sin");
            s.clearHits();
            s.melee(s.owner, s.enemy2, 5);
            assertEquals(5, s.damageBy(s.owner, s.enemy2), EPSILON, "the innocent take plain damage");
            s.finishClean();
        }
    }

    @Test
    void anubisWeighsLightAndHeavyHearts() {
        try (Scenario s = Scenario.ffa("anubis")) {
            settle(s);
            long verdict = s.paramTicks("weigh_delay") + 10;
            s.aimAt(s.owner, s.enemy);
            s.clearHits();
            s.assertSucceeded(s.active(), "저울질");
            s.ticks((int) verdict);
            assertEquals(s.param("light_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "a light heart");
            assertTrue(entities(s, ItemDisplay.class).stream()
                    .anyMatch(d -> d.getItemStack().getType() == Material.FEATHER && s.enemy.equals(d.getVehicle())),
                    "the feather marks it (riding its head)");

            // Under the feather its sin counts double: 3 damage dealt weigh 6, a heavy heart.
            s.melee(s.enemy, s.enemy2, 3);
            s.readyActive();
            s.healAll();
            s.clearHits();
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "저울질 again");
            s.ticks((int) verdict);
            double sin = 3 * s.param("feather_multiplier");
            double expected = Math.min(s.param("heavy_max"), s.param("heavy_base") + s.param("heavy_per_sin") * sin);
            assertEquals(expected, s.damageBy(s.owner, s.enemy), EPSILON,
                    "a heavy heart, without the passive bonus on top");
            assertTrue(slowed(s.enemy), "sand hands hold its feet");
            assertAllDisplaysUnrotated(s, "anubis");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void ammitDevoursTheGuiltyAndFeathersTheInnocent() {
        try (Scenario s = Scenario.builder("anubis")
                .configure(h -> h.settings().set(SuperpowerSettings.GAUGE_TAKEN, 0)).start()) {
            settle(s);
            s.melee(s.enemy, s.enemy2, 12);
            s.healAll();
            s.match.setGauge(s.enemy, 60);
            s.clearHits();
            s.assertSucceeded(s.ultimate(), "아미트의 심판");
            s.ticks(3);
            List<BlockDisplay> jackal = entities(s, BlockDisplay.class).stream()
                    .filter(d -> d.getBlock().getMaterial() == Material.BLACK_CONCRETE).toList();
            assertEquals(4, jackal.size(), "a black jackal head (head, snout, two ears) rises");
            jackal.forEach(part -> assertUnrotated(part, "jackal"));
            jackal.forEach(part -> assertEquals(s.owner, part.getVehicle(), "the jackal rides the god"));
            s.ticks(s.paramTicks("weigh_seconds") + 10);
            double guilty = Math.min(s.param("guilty_max"), s.param("guilty_base") + s.param("guilty_per_sin") * 12);
            assertEquals(guilty, s.damageBy(s.owner, s.enemy), EPSILON, "Ammit's jaws close on the guilty");
            assertTrue(s.ctx().gauge(s.enemy) <= 60 - s.param("gauge_loss") + 3, "the worst sinner loses gauge: "
                    + s.ctx().gauge(s.enemy));
            assertEquals(s.param("innocent_damage"), s.damageBy(s.owner, s.enemy2), EPSILON, "the innocent");
            assertTrue(entities(s, ItemDisplay.class).stream()
                    .anyMatch(d -> d.getItemStack().getType() == Material.FEATHER), "the innocent gets the feather");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.assertNoProblems();
            s.finishClean();
        }
    }

    // ================================================================== 빨간 마스크

    @Test
    void redMaskSpeedsUpBehindFleeingBacksAndPunishesTheFirstBlow() {
        try (Scenario s = Scenario.ffa("redmask")) {
            // Red turns its back (looks +Z, away from the mask at z = 0.5) and walks away.
            s.place(s.enemy, 0.5, 4.5, 0);
            s.place(s.enemy2, 6.5, 0.5, 90);
            settle(s);
            assertEquals(0, speedBonus(s.owner), EPSILON);
            s.enemy.sendInput(InputKey.FORWARD);
            s.ticks(70);
            assertTrue(speedBonus(s.owner) >= 2 * s.param("chase_step") / 100 - EPSILON,
                    "the mask speeds up while someone flees: " + speedBonus(s.owner));
            s.clearHits();
            s.melee(s.owner, s.enemy, 2);
            assertEquals(2 + s.param("flee_bonus"), s.damageBy(s.owner, s.enemy), EPSILON, "the first blow on a runner");
            s.clearHits();
            s.melee(s.owner, s.enemy, 2);
            assertEquals(2, s.damageBy(s.owner, s.enemy), EPSILON, "only the first");

            // Facing the mask while walking: nobody flees, the speed fades.
            s.enemy.setRotation(180, 0);
            s.ticks(20 * (int) Math.ceil(s.param("chase_max") / s.param("chase_fade")) + 40);
            assertEquals(0, speedBonus(s.owner), EPSILON, "the chase fades away");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void redMaskShearsCutWhoeverKeepsRunning() {
        try (Scenario s = Scenario.ffa("redmask")) {
            s.place(s.enemy, 0.5, 2.5, 180);
            settle(s);
            s.aimAt(s.owner, s.enemy);
            s.clearHits();
            s.assertSucceeded(s.active(), "도망쳐도 소용없어");
            assertEquals(s.param("snip_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "snip");
            s.ticks(2);
            assertTrue(entities(s, ItemDisplay.class).stream()
                    .anyMatch(d -> d.getItemStack().getType() == Material.SHEARS && s.enemy.equals(d.getVehicle())),
                    "red shears hang over the head (riding it)");
            // Facing the mask and walking at it: no cut.
            s.enemy.sendInput(InputKey.FORWARD);
            s.ticks(40);
            assertEquals(s.param("snip_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "facing the mask is safe");
            // Turning tail: cut every second, at most four times.
            s.enemy.setRotation(0, 0);
            s.ticks(s.paramTicks("mark_duration") + 20);
            double cuts = s.damageBy(s.owner, s.enemy) - s.param("snip_damage");
            assertTrue(cuts >= s.param("mark_damage") - EPSILON, "running from the mark is cut: " + cuts);
            assertTrue(cuts <= s.param("mark_max") * s.param("mark_damage") + EPSILON, "at most " + s.param("mark_max"));

            // Nobody in reach: half the cooldown back.
            s.readyActive();
            s.place(s.enemy, 0.5, 12.5, 180);
            s.place(s.enemy2, 8.5, 8.5, 180);
            s.aimAt(s.owner, new Location(s.world, 0.5, FlatWorld.SURFACE + 1.5, -10));
            s.clearHits();
            s.assertSucceeded(s.active(), "a snip in the air");
            s.ticks(3);
            assertTrue(s.hits().isEmpty(), "nothing hit");
            assertTrue(s.ctx().cooldownRemaining(s.owner) <= s.param("cooldown") * 0.5 + 0.2,
                    "half the cooldown back: " + s.ctx().cooldownRemaining(s.owner));
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void redMaskQuestionHasNoFreeAnswer() {
        try (Scenario s = Scenario.ffa("redmask")) {
            settle(s);
            s.clearHits();
            s.assertSucceeded(s.ultimate(), "나 예뻐?");
            assertFalse(texts(s, "나 예뻐?").isEmpty(), "the question floats over the heads");
            s.ticks(2);
            assertTrue(speedBonus(s.owner) >= s.param("ask_speed") / 100 - EPSILON, "she closes in while asking");
            assertEquals(s.owner, entities(s, ItemDisplay.class).stream()
                    .filter(d -> d.getItemStack().getType() == Material.SHEARS).findFirst().orElseThrow().getVehicle(),
                    "the shears ride her hand");
            // A camera already swinging as the question appears is no answer: Red turns away and looks down at once.
            Location look = s.enemy.getLocation();
            s.enemy.setRotation(look.getYaw() + 90, look.getPitch() + 35);
            s.ticks(s.paramTicks("answer_grace") + slack(s, s.enemy) + 2);
            assertEquals(0, s.damageBy(s.owner, s.enemy), EPSILON, "a turn within the grace answers nothing");
            // Then Red nods ("yes"), Blue crouches ("so-so").
            look = s.enemy.getLocation();
            s.enemy.setRotation(look.getYaw(), look.getPitch() + 35);
            s.enemy2.sendInput(InputKey.SNEAK);
            s.ticks(2);
            assertEquals(s.param("yes_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "\"이래도?\"");
            assertEquals(0, s.damageBy(s.owner, s.enemy2), EPSILON, "\"그저 그래요\" is safe for now");
            s.ticks(1);
            assertEquals(s.enemy2, texts(s, "?").stream().filter(d -> Scenario.plain(d.text()).equals("?"))
                    .findFirst().orElseThrow().getVehicle(), "the anger mark rides Blue's head");
            s.ticks(s.paramTicks("bleed_seconds") + 4);
            assertEquals(s.param("yes_damage") + s.param("bleed_damage") * s.param("bleed_seconds"),
                    s.damageBy(s.owner, s.enemy), EPSILON, "and the wound bleeds");
            assertTrue(speedBonus(s.owner) >= s.param("angry_speed") / 100 - EPSILON, "angry: faster");
            s.clearHits();
            s.melee(s.owner, s.enemy2, 2);
            assertEquals(2 + s.param("angry_bonus"), s.damageBy(s.owner, s.enemy2), EPSILON, "the angry blow");
            s.ticks(2);
            assertTrue(s.ctx().isSilenced(s.enemy2), "stuns");
            s.clearHits();
            s.ticks(10);
            s.melee(s.owner, s.enemy2, 2);
            assertEquals(2, s.damageBy(s.owner, s.enemy2), EPSILON, "only the next blow");

            // Silence is a "no".
            s.ticks(100);
            s.healAll();
            s.enemy2.sendInput();
            s.clearHits();
            s.assertSucceeded(s.ultimate(), "나 예뻐? again");
            s.ticks(s.paramTicks("answer_seconds") + slack(s, s.enemy) + 4);
            assertEquals(s.param("no_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "no answer is \"no\"");
            assertTrue(s.ctx().isSilenced(s.enemy), "the shears stun");
            assertAllDisplaysUnrotated(s, "redmask");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    // ================================================================== 강시

    @Test
    void jiangshiCannotRunButHopsAndThumps() {
        try (Scenario s = Scenario.ffa("jiangshi")) {
            settle(s);
            assertEquals(6, s.owner.getFoodLevel(), "held at the sprint threshold");
            s.owner.setFoodLevel(20);
            s.owner.setSprinting(true);
            s.ticks(1);
            assertEquals(6, s.owner.getFoodLevel(), "eating cannot lift it");
            assertFalse(s.owner.isSprinting(), "no running");
            assertFalse(entities(s, TextDisplay.class).stream().anyMatch(d -> s.seenBy(d).contains(s.owner)),
                    "its own talisman never covers its view");
            assertTrue(entities(s, TextDisplay.class).stream()
                    .anyMatch(d -> Scenario.plain(d.text()).contains("勅") && s.seenBy(d).contains(s.enemy)),
                    "everyone else sees the yellow talisman");

            // A hop forward lands next to Red.
            s.place(s.enemy, 0.5, 3.5, 180);
            s.place(s.owner, 0.5, 0.5, 0);
            settle(s);
            s.clearHits();
            double startZ = s.owner.getLocation().getZ();
            s.owner.sendInput(InputKey.JUMP);
            s.ticks(1);
            s.owner.sendInput();
            assertTrue(s.owner.getVelocity().getZ() > 0.1, "the hop goes where it looks: " + s.owner.getVelocity());
            s.tickUntilGrounded(s.owner, 40);
            s.ticks(2);
            double hop = s.owner.getLocation().getZ() - startZ;
            assertTrue(hop > s.param("hop_distance") * 0.6 && hop < s.param("hop_distance") * 1.4, "about "
                    + s.param("hop_distance") + " blocks: " + hop);
            assertEquals(FlatWorld.SURFACE, s.owner.getLocation().getY(), 1.0E-6, "lands on the surface");
            assertEquals(s.param("landing_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "the landing thumps");

            // Qi instead of natural regeneration.
            s.owner.setHealth(10);
            s.ticks(s.paramTicks("qi_interval") + 1);
            assertEquals(10 + s.param("qi_heal"), s.owner.getHealth(), EPSILON, "the jiangshi's qi");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void jiangshiTalismanLeavesOnlyHops() {
        try (Scenario s = Scenario.ffa("jiangshi")) {
            settle(s);
            s.aimAt(s.owner, s.enemy);
            s.clearHits();
            s.assertSucceeded(s.active(), "부적 붙이기");
            s.ticks(6);
            assertEquals(s.param("talisman_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "the talisman hits");
            assertTrue(slowed(s.enemy), "it cannot walk");
            List<TextDisplay> slips = entities(s, TextDisplay.class).stream()
                    .filter(d -> Scenario.plain(d.text()).contains("勅") && s.enemy.equals(d.getVehicle())).toList();
            assertFalse(slips.isEmpty(), "a talisman on its forehead (riding it)");
            assertEquals(2, entities(s, BlockDisplay.class).stream().filter(d -> s.enemy.equals(d.getVehicle()))
                    .count(), "and both sleeves");
            for (TextDisplay slip : slips) {
                assertFalse(s.seenBy(slip).contains(s.enemy), "not in front of its own eyes");
                assertTrue(s.seenBy(slip).contains(s.owner), "everyone else sees it");
                assertUnrotated(slip, "talisman");
            }
            s.ticks(2);
            s.enemy.sendInput(InputKey.JUMP, InputKey.FORWARD);
            s.ticks(1);
            Vector hop = s.enemy.getVelocity();
            assertTrue(Math.abs(hop.getX()) + Math.abs(hop.getZ()) > 0.05, "a hop where its keys point: " + hop);
            assertFalse(s.ctx().wasDisplaced(s.enemy, 2), "its own hop is no displacement");
            s.enemy.sendInput();
            s.ticks(s.paramTicks("talisman_seconds") + 10);
            assertFalse(slowed(s.enemy), "the talisman burns away");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void jiangshiBreathCatchesOnlyWhoMovesAtTheInhale() {
        try (Scenario s = Scenario.ffa("jiangshi")) {
            settle(s);
            s.enemy.sendInput(InputKey.FORWARD);
            s.clearHits();
            s.assertSucceeded(s.ultimate(), "숨 참아!");
            int interval = s.paramTicks("breath_interval");
            s.ticks(interval + slack(s, s.enemy) + 2);
            assertEquals(s.param("caught_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "walking at the inhale");
            assertTrue(slowed(s.enemy), "caught: the talisman");
            assertEquals(0, s.damageBy(s.owner, s.enemy2), EPSILON, "standing still passes");
            // Pushed, not walking: passes.
            s.enemy2.setVelocity(new Vector(0.6, 0.2, 0));
            s.ticks(s.paramTicks("breath_duration") + 20);
            assertEquals(0, s.damageBy(s.owner, s.enemy2), EPSILON, "being pushed is no movement");
            int max = (int) s.param("caught_max");
            assertEquals(max * s.param("caught_damage"), s.damageBy(s.owner, s.enemy), EPSILON,
                    "at most " + max + " catches");
            assertEquals(0, s.damageTaken(s.owner), EPSILON);
            s.assertNoProblems();
            s.finishClean();
        }
    }

    // ================================================================== displays on a real client (regressions)

    /** The horizontal direction (degrees, Minecraft yaw) a display's rotation turns local +Z to. */
    private static double facingYaw(Display display) {
        org.joml.Vector3f forward = display.getTransformation().getLeftRotation()
                .transform(new org.joml.Vector3f(0, 0, 1));
        return Math.toDegrees(Math.atan2(-forward.x, forward.z));
    }

    private static double angleBetween(double a, double b) {
        double d = (a - b) % 360;
        if (d > 180) {
            d -= 360;
        } else if (d < -180) {
            d += 360;
        }
        return Math.abs(d);
    }

    @Test
    void jiangshiLookRidesTheBodyTurnsWithTheHeadAndIsGoneBeforeTheRestore() {
        try (Scenario s = Scenario.ffa("jiangshi")) {
            settle(s);
            List<Display> look = entities(s, Display.class).stream().filter(d -> s.owner.equals(d.getVehicle()))
                    .toList();
            assertEquals(3, look.size(), "the talisman and both sleeves ride the jiangshi (no per-tick teleports)");
            for (Display part : look) {
                assertUnrotated(part, "mounted part");
                org.joml.Vector3f at = modelCentre(part);
                // The owner looks +Z: the parts hang in front of its face and arms, below the seat on its head.
                assertTrue(at.z > 0.2 && at.y < 0, part.getType() + " in front of the body: " + at);
            }
            List<BlockDisplay> sleeves = entities(s, BlockDisplay.class).stream()
                    .filter(d -> s.owner.equals(d.getVehicle())).toList();
            assertEquals(2, sleeves.size());
            BlockDisplay sleeve = sleeves.getFirst();
            org.bukkit.util.Transformation still = sleeve.getTransformation();
            s.ticks(3);
            assertEquals(still, sleeve.getTransformation(), "standing still, nothing is sent again");

            // A quick turn is followed, at most 60 degrees per update (never the long way through the head).
            s.owner.setRotation(170, 0);
            s.ticks(1);
            double turned = angleBetween(facingYaw(sleeve), 0);
            assertTrue(turned > 1 && turned <= 60 + 1.0E-3, "turned by at most 60 degrees: " + turned);
            assertEquals(3, sleeve.getInterpolationDuration(), "over a remote head's 3-tick lerp");
            s.ticks(4);
            assertTrue(angleBetween(facingYaw(sleeve), 170) < 1.0E-3, "then caught up: " + facingYaw(sleeve));

            // The match ends: the parts are gone before anyone is sent back (a vehicle cannot change worlds).
            List<String> carried = new java.util.ArrayList<>();
            List<Player> sent = new java.util.ArrayList<>();
            org.bukkit.event.Listener spy = new org.bukkit.event.Listener() {
                @org.bukkit.event.EventHandler
                public void onTeleport(org.bukkit.event.player.PlayerTeleportEvent event) {
                    if (s.session.state() != io.github.sx2xoox.mcpl.core.game.GameState.RUNNING) {
                        sent.add(event.getPlayer());
                        if (!event.getPlayer().getPassengers().isEmpty()) {
                            carried.add(event.getPlayer().getName());
                        }
                    }
                }
            };
            org.bukkit.Bukkit.getPluginManager().registerEvents(spy, s.h.hub.plugin());
            try {
                s.finishClean();
            } finally {
                org.bukkit.event.HandlerList.unregisterAll(spy);
            }
            assertTrue(sent.contains(s.owner), "the jiangshi was sent back");
            assertTrue(carried.isEmpty(), "nobody carried a mounted display into the restore: " + carried);
            assertTrue(s.owner.getPassengers().isEmpty());
        }
    }

    @Test
    void jiangshiThrownSlipFacesEveryoneAndAMissFluttersDownSlowly() {
        try (Scenario s = Scenario.ffa("jiangshi")) {
            settle(s);
            s.aimAt(s.owner, new Location(s.world, 0.5, FlatWorld.SURFACE + 1.62, -20));
            s.assertSucceeded(s.active(), "부적 붙이기 into the air");
            s.ticks(2);
            TextDisplay slip = entities(s, TextDisplay.class).stream()
                    .filter(d -> Scenario.plain(d.text()).contains("勅") && d.getVehicle() == null)
                    .findFirst().orElseThrow();
            assertEquals(Display.Billboard.CENTER, slip.getBillboard(), "the thrower sees the slip in flight too");
            int waited = 0;
            Location last = slip.getLocation();
            while (slip.getTeleportDuration() != 10 && waited++ < 40) {
                last = slip.getLocation();
                s.ticks(1);
            }
            assertEquals(10, slip.getTeleportDuration(), "a miss glides down slowly");
            double before = slip.getLocation().getY();
            assertEquals(last.getY(), before, 1.0E-9, "the slower glide is sent a tick before the move");
            s.ticks(1);
            assertEquals(before - 0.8, slip.getLocation().getY(), 1.0E-6, "then it drops");
            assertTrue(slip.isValid(), "and flutters before it burns away");
            s.ticks(20);
            assertFalse(slip.isValid());
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void redMaskMaskAndShearsRideHerAndShrinkBeforeTheyExpire() {
        try (Scenario s = Scenario.ffa("redmask")) {
            settle(s);
            s.assertSucceeded(s.ultimate(), "나 예뻐?");
            s.ticks(1);
            BlockDisplay mask = entities(s, BlockDisplay.class).stream()
                    .filter(d -> d.getBlock().getMaterial() == Material.WHITE_CONCRETE).findFirst().orElseThrow();
            ItemDisplay shears = entities(s, ItemDisplay.class).stream()
                    .filter(d -> d.getItemStack().getType() == Material.SHEARS).findFirst().orElseThrow();
            assertEquals(s.owner, mask.getVehicle(), "the mask rides her face");
            assertEquals(s.owner, shears.getVehicle(), "the shears ride her hand");
            assertFalse(s.seenBy(mask).contains(s.owner), "her own mask never covers her view");
            long shrunk = -1;
            long tick = 0;
            while (shears.isValid() && tick++ < 200) {
                if (shrunk < 0 && shears.getTransformation().getScale().x < 0.01f) {
                    shrunk = tick;
                }
                s.ticks(1);
            }
            assertTrue(shrunk > 0, "the shears shrink away at the end");
            // Seen shrinking the tick after shrinkAway: its 4-tick shrink then runs 3 more ticks to the end.
            assertTrue(tick - shrunk >= 3, "and are not cut off by their lifetime first: " + (tick - shrunk));
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void redMaskStunnedEnemyFleesNowhere() {
        try (Scenario s = Scenario.ffa("redmask")) {
            settle(s);
            s.clearHits();
            s.assertSucceeded(s.ultimate(), "나 예뻐?");
            // Nobody answers: "no" — the shears stun both.
            int waited = 0;
            while (!s.ctx().isSilenced(s.enemy) && waited++ < 120) {
                s.ticks(1);
            }
            assertTrue(s.ctx().isSilenced(s.enemy), "no answer: stunned");
            // Frozen, Red faces away and holds W: it goes nowhere, so it flees nowhere.
            s.enemy.setRotation(0, 0);
            s.enemy.sendInput(InputKey.FORWARD);
            s.ticks(8);
            for (int i = 0; i < 20 && s.ctx().isSilenced(s.enemy); i++) {
                assertEquals(0, speedBonus(s.owner), EPSILON, "a stunned enemy gives no chase speed");
                s.ticks(1);
            }
            s.enemy.sendInput();
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void sphinxGlyphsLieOnTheirTilesAndTheBoardIsWholeAfterARiddle() {
        try (Scenario s = Scenario.ffa("sphinx")) {
            settle(s);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "수수께끼");
            s.ticks(10);
            assertGlyphsOnPlates(s, 3, 1.3);
            // The ultimate right after the riddle: the whole board rises (nine plates, nine pictures, the title).
            s.assertSucceeded(s.ultimate(), "세 번째 수수께끼");
            s.ticks(14);
            assertGlyphsOnPlates(s, 12, 1.3);
            assertEquals(9, entities(s, BlockDisplay.class).stream()
                    .filter(d -> d.getTransformation().getScale().x > 2.5f).count(), "nine board plates");
            assertFalse(texts(s, "수수께끼").stream().filter(d -> d.getBillboard() == Display.Billboard.CENTER)
                    .toList().isEmpty(), "and its title");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    /** Every flat picture's text lies over the middle of a plate, and fits on it. */
    private static void assertGlyphsOnPlates(Scenario s, int count, double smallest) {
        List<TextDisplay> glyphs = entities(s, TextDisplay.class).stream()
                .filter(text -> text.getBillboard() == Display.Billboard.FIXED).toList();
        assertEquals(count, glyphs.size(), "pictures");
        List<BlockDisplay> plates = entities(s, BlockDisplay.class).stream()
                .filter(d -> d.getBlock().getMaterial() != Material.CHISELED_SANDSTONE
                        && d.getBlock().getMaterial() != Material.SANDSTONE).toList();
        for (TextDisplay glyph : glyphs) {
            Location lies = flatCentre(glyph, 2);
            BlockDisplay under = plates.stream()
                    .filter(plate -> Math.abs(plate.getLocation().getX() - lies.getX()) < 0.05
                            && Math.abs(plate.getLocation().getZ() - lies.getZ()) < 0.05)
                    .findFirst().orElse(null);
            assertNotNull(under, "the picture lies over the middle of a plate: " + lies);
            double size = under.getTransformation().getScale().x;
            double height = 21 / 40.0 * glyph.getTransformation().getScale().y;
            assertTrue(size >= smallest - 1.0E-6 && height <= size, "it fits on the plate: " + height + " / " + size);
        }
    }

    @Test
    void sphinxClawsCloseInOnTheVictim() {
        try (Scenario s = Scenario.ffa("sphinx")) {
            settle(s);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "수수께끼");
            s.ticks(s.paramTicks("answer_seconds") + slack(s, s.enemy) + 6);
            List<BlockDisplay> claws = entities(s, BlockDisplay.class).stream()
                    .filter(d -> d.getBlock().getMaterial() == Material.SANDSTONE).toList();
            assertEquals(3, claws.size(), "three claws burst out");
            Location feet = s.enemy.getLocation();
            for (BlockDisplay claw : claws) {
                org.bukkit.util.Transformation t = claw.getTransformation();
                org.joml.Vector3f size = t.getScale();
                org.joml.Vector3f base = new org.joml.Vector3f(t.getTranslation()).add(
                        t.getLeftRotation().transform(new org.joml.Vector3f(size.x / 2, 0, size.z / 2)));
                org.joml.Vector3f tip = new org.joml.Vector3f(t.getTranslation()).add(
                        t.getLeftRotation().transform(new org.joml.Vector3f(size.x / 2, size.y, size.z / 2)));
                Location at = claw.getLocation();
                double baseOff = Math.hypot(at.getX() + base.x - feet.getX(), at.getZ() + base.z - feet.getZ());
                double tipOff = Math.hypot(at.getX() + tip.x - feet.getX(), at.getZ() + tip.z - feet.getZ());
                assertTrue(tipOff < baseOff - 0.3, "the claw leans in over the victim: " + tipOff + " / " + baseOff);
            }
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void sphinxBoardRevealsTheAnswerOnlyAfterEveryQuestionEnded() {
        try (Scenario s = Scenario.ffa("sphinx")) {
            s.enemy.setPing(250); // a later end of Red's question (its reaction slack)
            settle(s);
            s.assertSucceeded(s.ultimate(), "세 번째 수수께끼");
            int reveals = 0;
            boolean revealed = false;
            for (int i = 0; i < 260; i++) {
                s.ticks(1);
                boolean shows = texts(s, "정답:").stream().anyMatch(d -> d.getBillboard() == Display.Billboard.CENTER);
                if (shows) {
                    assertFalse(s.ctx().hasShownPrompt(s.enemy) || s.ctx().hasShownPrompt(s.enemy2),
                            "the answer is out while a question is still on screen");
                    if (!revealed) {
                        reveals++;
                    }
                }
                revealed = shows;
            }
            assertEquals(3, reveals, "three answers came out");
            assertTrue(entities(s, Display.class).isEmpty(), "the board sank");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void prohibitorKeptTabooSoaksInWithoutSpinning() {
        try (Scenario s = Scenario.ffa("prohibitor")) {
            settle(s);
            writeTaboo(s, s.enemy);
            Map<TextDisplay, Double> yaws = new java.util.HashMap<>();
            for (int i = 0; i < s.paramTicks("taboo_duration") + 20 && s.damageBy(s.owner, s.enemy) == 0; i++) {
                for (TextDisplay glyph : entities(s, TextDisplay.class)) {
                    if (glyph.getBillboard() == Display.Billboard.FIXED && s.enemy.equals(glyph.getVehicle())
                            && glyph.getTransformation().getScale().y > 0.1f) {
                        yaws.put(glyph, facingYawOfFlat(glyph));
                    }
                }
                s.ticks(1);
            }
            assertEquals(s.param("keep_damage"), s.damageBy(s.owner, s.enemy), EPSILON, "kept");
            assertEquals(9, yaws.size(), "eight letters and the seal were watched");
            for (Map.Entry<TextDisplay, Double> entry : yaws.entrySet()) {
                TextDisplay glyph = entry.getKey();
                if (!glyph.isValid() || glyph.getTransformation().getScale().y > 0.1f) {
                    continue; // the seal shrinks away; only the soaking letters flatten
                }
                assertTrue(angleBetween(facingYawOfFlat(glyph), entry.getValue()) < 15,
                        "a letter soaks in where it lay, keeping its turn: " + facingYawOfFlat(glyph) + " vs "
                                + entry.getValue());
            }
            s.assertNoProblems();
            s.finishClean();
        }
    }

    /** The yaw of a flat text's top (its rotated vertical). */
    private static double facingYawOfFlat(TextDisplay glyph) {
        org.joml.Vector3f up = glyph.getTransformation().getLeftRotation().transform(new org.joml.Vector3f(0, 1, 0));
        return Math.toDegrees(Math.atan2(-up.x, up.z));
    }

    @Test
    void prohibitorBarrierGivesTheCasterAGraceToLetGoOfTheJump() {
        try (Scenario s = Scenario.ffa("prohibitor")) {
            s.place(s.owner, 0.5, FlatWorld.SURFACE + 10, 0.5, 0, 0);
            settle(s);
            assertFalse(s.owner.isOnGround(), "still in the air");
            s.owner.sendInput(InputKey.JUMP);
            s.ticks(1);
            s.assertSucceeded(s.ultimate(), "금제 결계 in the air");
            assertFalse(texts(s, "땅을 떠나지 마라").isEmpty(), "airborne: stay grounded");
            s.ticks(4);
            s.owner.sendInput();
            s.ticks(20);
            assertTrue(texts(s, "붕괴").isEmpty(), "a jump key let go within the grace does not shatter it");
            assertFalse(texts(s, "結界").isEmpty(), "the barrier stands");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void prohibitorBarrierCountsPunishmentsPerEnemyAcrossLeavingAndComingBack() {
        try (Scenario s = Scenario.ffa("prohibitor")) {
            settle(s);
            s.owner.setSneaking(true);
            s.owner.sendInput(InputKey.FORWARD, InputKey.SNEAK);
            s.clearHits();
            s.assertSucceeded(s.ultimate(), "금제 결계 (멈추지 마라)");
            int max = (int) s.param("barrier_max");
            for (int i = 0; i < 120 && s.hitsBy(s.owner, s.enemy).size() < max; i++) {
                s.ticks(1);
            }
            assertEquals(max, s.hitsBy(s.owner, s.enemy).size(), "punished " + max + " times");
            double radius = s.param("barrier_radius");
            s.place(s.enemy, 0.5, radius + 3, 180);
            s.ticks(3);
            s.place(s.enemy, 0.5, 4.5, 180);
            s.ticks(s.paramTicks("barrier_duration"));
            assertEquals(max, s.hitsBy(s.owner, s.enemy).size(), "walking back in starts no fresh count");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void anubisScaleRidesTheHeadAndItsWeightsDropIn() {
        try (Scenario s = Scenario.ffa("anubis")) {
            settle(s);
            s.aimAt(s.owner, s.enemy);
            s.assertSucceeded(s.active(), "저울질");
            s.ticks(2);
            List<BlockDisplay> gold = entities(s, BlockDisplay.class).stream()
                    .filter(d -> d.getBlock().getMaterial() == Material.GOLD_BLOCK && s.enemy.equals(d.getVehicle()))
                    .toList();
            assertEquals(3, gold.size(), "beam and pans ride the weighed head");
            ItemDisplay weight = null;
            for (int i = 0; i < 30 && weight == null; i++) {
                s.ticks(1);
                weight = entities(s, ItemDisplay.class).stream()
                        .filter(d -> d.getItemStack().getType() == Material.FEATHER).findFirst().orElse(null);
            }
            assertNotNull(weight, "the feather goes on the scale");
            assertEquals(s.enemy, weight.getVehicle());
            assertTrue(weight.getTransformation().getScale().x < 0.2f, "not posed in its spawn tick (no snap)");
            s.ticks(1);
            assertTrue(weight.getTransformation().getScale().x > 0.3f, "it drops onto its pan from the next tick on");
            assertTrue(texts(s, "❤").stream().allMatch(d -> d.getVehicle() == null),
                    "the heart (camera-facing) follows by itself");
            s.assertNoProblems();
            s.finishClean();
        }
    }

    @Test
    void ammitScalesFadeAtTheVerdict() {
        try (Scenario s = Scenario.ffa("anubis")) {
            settle(s);
            s.clearHits();
            s.assertSucceeded(s.ultimate(), "아미트의 심판");
            int waited = 0;
            while (s.damageBy(s.owner, s.enemy2) == 0 && waited++ < 80) {
                s.ticks(1);
            }
            assertEquals(s.param("innocent_damage"), s.damageBy(s.owner, s.enemy2), EPSILON, "the verdict");
            List<BlockDisplay> scales = entities(s, BlockDisplay.class).stream()
                    .filter(d -> d.getBlock().getMaterial() == Material.GOLD_BLOCK && d.getVehicle() != null
                            && !s.owner.equals(d.getVehicle()))
                    .toList();
            assertFalse(scales.isEmpty(), "the great scales are still there at the verdict, fading");
            scales.forEach(scale -> assertTrue(scale.getTransformation().getScale().x < 0.01f, "shrinking"));
            s.ticks(8);
            assertTrue(scales.stream().noneMatch(Entity::isValid), "then gone");
            s.assertNoProblems();
            s.finishClean();
        }
    }
}
