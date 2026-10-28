package io.github.sx2xoox.mcpl.game.augment.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import io.github.sx2xoox.mcpl.game.augment.bot.BotBrain.Behaviour;
import io.github.sx2xoox.mcpl.game.augment.bot.BotBrain.Contact;
import io.github.sx2xoox.mcpl.game.augment.bot.BotBrain.Decision;
import io.github.sx2xoox.mcpl.game.augment.bot.BotBrain.Situation;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** The bot's judgement, scenario by scenario, at every difficulty (they all think; they differ in execution). */
class BotBrainTest {

    private static final UUID A = new UUID(0, 1);
    private static final UUID B = new UUID(0, 2);
    private static final double IRON = Gear.at(0.6).score();
    private static final double DIAMOND = Gear.at(1.4).score();
    private static final double LEATHER = Gear.at(0.25).score();

    /** A situation builder with sane defaults: fighting, full health, iron gear, 3 apples, safe in the border. */
    private static final class S {
        boolean fight = true;
        double farmingLeft;
        double health = 20;
        double absorption;
        double maxHealth = 20;
        int apples = 3;
        boolean appleReady = true;
        double gear = IRON;
        boolean bow = true;
        int blocks = 64;
        double borderMargin = 100;
        boolean shrinking;
        double zoneDistance = 50;
        double zoneRadius = 12;
        boolean baseBuilt;
        boolean baseDone = true;
        double baseDistance = Double.POSITIVE_INFINITY;
        boolean baseSafe;
        boolean high;
        final List<Contact> contacts = new ArrayList<>();

        S contact(UUID id, double distance, double health, double gear, boolean fightingOther) {
            contacts.add(new Contact(id, distance, health, 20, gear, true, fightingOther, false, false));
            return this;
        }

        Situation build() {
            return new Situation(fight, farmingLeft, health, absorption, maxHealth, apples, appleReady, gear, bow,
                    blocks, borderMargin, shrinking, zoneDistance, zoneRadius, baseBuilt, baseDone, baseDistance,
                    baseSafe, high, null, null, contacts);
        }
    }

    private static Decision decide(BotDifficulty difficulty, S s) {
        return new BotBrain(BotProfile.preset(difficulty)).decide(s.build());
    }

    @ParameterizedTest
    @EnumSource(BotDifficulty.class)
    void farmingTimeLeftMeansFarmOrBuildNotFight(BotDifficulty difficulty) {
        S s = new S();
        s.fight = false;
        s.farmingLeft = 0.7;
        s.baseDone = false;
        s.contact(A, 6, 20, LEATHER, false);
        Decision d = decide(difficulty, s);
        assertEquals(Behaviour.BUILD_BASE, d.behaviour(), "builds its base while PvP is off");
        s.baseDone = true;
        assertEquals(Behaviour.FARM, decide(difficulty, s).behaviour(), "then farms, never chases anyone");
    }

    @ParameterizedTest
    @EnumSource(BotDifficulty.class)
    void lowHealthAgainstAFullHealthTargetRetreatsOrFortifies(BotDifficulty difficulty) {
        S s = new S();
        s.health = 5;
        s.contact(A, 6, 20, IRON, false);
        Decision d = decide(difficulty, s);
        assertTrue(Set.of(Behaviour.RETREAT_HEAL, Behaviour.FORTIFY).contains(d.behaviour()), d.toString());
        s.apples = 0;
        assertEquals(Behaviour.FORTIFY, decide(difficulty, s).behaviour(), "no apples and hunted: wall in");
    }

    @ParameterizedTest
    @EnumSource(BotDifficulty.class)
    void outgearedAvoidsOrKitesInsteadOfMelee(BotDifficulty difficulty) {
        S s = new S();
        s.gear = LEATHER;
        s.contact(A, 14, 20, DIAMOND, false);
        Decision d = decide(difficulty, s);
        assertNotEquals(Behaviour.ENGAGE, d.behaviour(), d.toString());
        assertTrue(Set.of(Behaviour.AVOID, Behaviour.KITE).contains(d.behaviour()), d.toString());
    }

    @ParameterizedTest
    @EnumSource(BotDifficulty.class)
    void anEvenOrWeakerTargetAloneIsEngaged(BotDifficulty difficulty) {
        S s = new S();
        s.contact(A, 5, 12, LEATHER, false);
        Decision d = decide(difficulty, s);
        assertEquals(Behaviour.ENGAGE, d.behaviour());
        assertEquals(A, d.target());
    }

    @ParameterizedTest
    @EnumSource(BotDifficulty.class)
    void oneAgainstTwoIsAvoided(BotDifficulty difficulty) {
        S s = new S();
        s.contact(A, 8, 20, IRON, false).contact(B, 10, 20, IRON, false);
        Decision d = decide(difficulty, s);
        assertNotEquals(Behaviour.ENGAGE, d.behaviour(), "no 1v2 against even players: " + d);
    }

    @ParameterizedTest
    @EnumSource(BotDifficulty.class)
    void twoEnemiesFightingAreLeftAloneThenThirdPartied(BotDifficulty difficulty) {
        S s = new S();
        s.contact(A, 20, 18, IRON, true).contact(B, 22, 17, IRON, true);
        Decision wait = decide(difficulty, s);
        assertEquals(Behaviour.THIRD_PARTY, wait.behaviour(), "waits while both are healthy");
        S later = new S();
        later.contact(A, 20, 5, IRON, true).contact(B, 22, 15, IRON, true);
        Decision strike = decide(difficulty, later);
        assertEquals(Behaviour.ENGAGE, strike.behaviour(), "strikes once one of them is weak");
        assertEquals(A, strike.target(), "the weakened one");
    }

    @ParameterizedTest
    @EnumSource(BotDifficulty.class)
    void aClosingBorderMovesItInside(BotDifficulty difficulty) {
        S s = new S();
        s.shrinking = true;
        s.borderMargin = 2;
        s.contact(A, 6, 10, LEATHER, false);
        assertEquals(Behaviour.FLEE_BORDER, decide(difficulty, s).behaviour());
        s.borderMargin = -5;
        assertEquals(Behaviour.FLEE_BORDER, decide(difficulty, s).behaviour(), "outside: get in first");
    }

    @ParameterizedTest
    @EnumSource(BotDifficulty.class)
    void lateGameWithNobodyKnownHoldsTheZone(BotDifficulty difficulty) {
        S s = new S();
        s.shrinking = true;
        s.zoneDistance = 80;
        assertEquals(Behaviour.HOLD_ZONE, decide(difficulty, s).behaviour());
        S early = new S();
        assertEquals(Behaviour.SCOUT, decide(difficulty, early).behaviour(), "early: looks for someone");
    }

    @ParameterizedTest
    @EnumSource(BotDifficulty.class)
    void huntedByManyItFallsBackToItsBase(BotDifficulty difficulty) {
        S s = new S();
        s.health = 9;
        s.apples = 0;
        s.blocks = 0;
        s.baseBuilt = true;
        s.baseSafe = true;
        s.baseDistance = 10;
        s.contact(A, 16, 20, IRON, false).contact(B, 17, 20, IRON, false);
        Decision d = decide(difficulty, s);
        assertTrue(Set.of(Behaviour.DEFEND_BASE, Behaviour.RETREAT_HEAL, Behaviour.AVOID).contains(d.behaviour()),
                d.toString());
    }

    @ParameterizedTest
    @EnumSource(BotDifficulty.class)
    void fromHighGroundItAmbushesAnApproachingTarget(BotDifficulty difficulty) {
        S s = new S();
        s.high = true;
        s.contacts.add(new Contact(A, 15, 20, 20, IRON, true, false, true, false));
        Decision d = decide(difficulty, s);
        assertTrue(Set.of(Behaviour.AMBUSH, Behaviour.ENGAGE, Behaviour.KITE).contains(d.behaviour()),
                d.toString());
        assertEquals(A, d.target());
    }

    private static Situation withPressure(Situation s, double pressure, Behaviour current, UUID target) {
        return new Situation(s.fight(), s.farmingLeft(), s.health(), s.absorption(), s.maxHealth(), s.apples(),
                s.appleReady(), s.gear(), s.hasBow(), s.blocks(), s.borderMargin(), s.borderShrinking(),
                s.zoneDistance(), s.zoneRadius(), s.baseBuilt(), s.baseDone(), s.baseDistance(), s.baseSafe(),
                s.highGround(), current, target, s.contacts(), pressure);
    }

    @ParameterizedTest
    @EnumSource(BotDifficulty.class)
    void twoEqualBotsBothEngageEachOther(BotDifficulty difficulty) {
        // symmetric: each sees the other with the same gear and health; neither may back off
        S s = new S();
        s.contacts.add(new Contact(A, 12, 20, 20, IRON, true, false, true, true));
        Decision d = decide(difficulty, s);
        assertEquals(Behaviour.ENGAGE, d.behaviour(), "an even duel is fought: " + d);
    }

    @ParameterizedTest
    @EnumSource(BotDifficulty.class)
    void lateInTheFightNobodyWaitsOrAvoidsEvenOdds(BotDifficulty difficulty) {
        BotBrain brain = new BotBrain(BotProfile.preset(difficulty));
        S crowd = new S();
        crowd.contact(A, 8, 20, IRON, false).contact(B, 10, 20, IRON, false);
        Situation late = withPressure(crowd.build(), 1.0, null, null);
        assertEquals(Behaviour.ENGAGE, brain.decide(late).behaviour(), "three equal bots: someone commits");
        S fighting = new S();
        fighting.contact(A, 20, 18, IRON, true).contact(B, 22, 17, IRON, true);
        assertEquals(Behaviour.ENGAGE, brain.decide(withPressure(fighting.build(), 1.0, null, null)).behaviour(),
                "no more waiting for a third party at the end");
    }

    @ParameterizedTest
    @EnumSource(BotDifficulty.class)
    void nobodyKnownMeansScoutingNotSittingInTheBase(BotDifficulty difficulty) {
        S s = new S();
        s.baseBuilt = true;
        s.baseSafe = true;
        s.baseDistance = 2;
        assertEquals(Behaviour.SCOUT, decide(difficulty, s).behaviour(), "an empty base is no reason to stay");
    }

    @ParameterizedTest
    @EnumSource(BotDifficulty.class)
    void anEngagedBotDoesNotFlipFlop(BotDifficulty difficulty) {
        BotBrain brain = new BotBrain(BotProfile.preset(difficulty));
        S s = new S();
        s.contact(A, 6, 20, IRON, false);
        s.contacts.add(new Contact(B, 17, 20, 20, IRON, true, false, false, false));
        Situation engaged = withPressure(s.build(), 0.3, Behaviour.ENGAGE, A);
        Decision d = brain.decide(engaged);
        assertEquals(Behaviour.ENGAGE, d.behaviour(), "stays in the duel when a second one shows up: " + d);
        assertEquals(A, d.target());
    }

    @org.junit.jupiter.api.Test
    void severalEqualBotsStartFightingWithinAFewDecisions() {
        // four equal bots that all see each other at the fight start; every decision feeds the next one's state
        int count = 4;
        UUID[] ids = new UUID[count];
        for (int i = 0; i < count; i++) {
            ids[i] = new UUID(1, i);
        }
        BotBrain brain = new BotBrain(BotProfile.preset(BotDifficulty.NORMAL));
        Decision[] current = new Decision[count];
        boolean engaged = false;
        for (int round = 0; round < 10 && !engaged; round++) {
            double pressure = round * 0.05;
            for (int me = 0; me < count; me++) {
                S s = new S();
                for (int other = 0; other < count; other++) {
                    if (other != me) {
                        s.contacts.add(new Contact(ids[other], 10 + 4 * Math.abs(other - me), 20, 20, IRON, true,
                                false, false, true));
                    }
                }
                Decision before = current[me];
                current[me] = brain.decide(withPressure(s.build(), pressure,
                        before == null ? null : before.behaviour(), before == null ? null : before.target()));
                engaged |= current[me].behaviour() == Behaviour.ENGAGE;
            }
        }
        assertTrue(engaged, "someone commits within ten decisions");
    }

    @ParameterizedTest
    @EnumSource(BotDifficulty.class)
    void outsideTheBorderGettingBackInBeatsAnyFight(BotDifficulty difficulty) {
        BotBrain brain = new BotBrain(BotProfile.preset(difficulty));
        S s = new S();
        s.shrinking = true;
        s.borderMargin = -4;
        s.contact(A, 5, 20, LEATHER, false); // an easy, healthy target right there
        assertEquals(Behaviour.FLEE_BORDER, brain.decide(withPressure(s.build(), 1.0, Behaviour.ENGAGE, A))
                .behaviour(), "even mid-duel and late in the match");
        S sure = new S();
        sure.shrinking = true;
        sure.borderMargin = -2;
        sure.contact(A, 3, 3, IRON, false);
        Decision finish = brain.decide(sure.build());
        assertEquals(Behaviour.ENGAGE, finish.behaviour(), "only a sure kill is worth a moment outside");
        assertEquals(A, finish.target());
    }

    @ParameterizedTest
    @EnumSource(BotDifficulty.class)
    void aBaseTheBorderWillSwallowIsLeft(BotDifficulty difficulty) {
        S s = new S();
        s.shrinking = true;
        s.health = 9;
        s.apples = 0;
        s.blocks = 0;
        s.baseBuilt = true;
        s.baseSafe = false; // the border closes over it soon
        s.baseDistance = 1;
        s.borderMargin = 3;
        s.contact(A, 16, 20, IRON, false).contact(B, 17, 20, IRON, false);
        Decision d = decide(difficulty, s);
        assertNotEquals(Behaviour.DEFEND_BASE, d.behaviour(), d.toString());
        assertEquals(Behaviour.FLEE_BORDER, d.behaviour(), "the border is near: inside first");
    }

    private static Situation late(Situation s, double pressure, boolean finale, int rivalsLeft, double idle) {
        return new Situation(s.fight(), s.farmingLeft(), s.health(), s.absorption(), s.maxHealth(), s.apples(),
                s.appleReady(), s.gear(), s.hasBow(), s.blocks(), s.borderMargin(), s.borderShrinking(),
                s.zoneDistance(), s.zoneRadius(), s.baseBuilt(), s.baseDone(), s.baseDistance(), s.baseSafe(),
                s.highGround(), s.current(), s.currentTarget(), s.contacts(), pressure, finale, rivalsLeft, idle);
    }

    @ParameterizedTest
    @EnumSource(BotDifficulty.class)
    void aHealthyBotThatKnowsOfAnEnemyEngagesOrPressures(BotDifficulty difficulty) {
        // a normal fight phase, a healthy bot: whatever the enemy (even, weaker, a little better geared, hurt, far,
        // only remembered), it goes for them, in melee or with the bow; a base or a shelter is never the default
        double slightlyBetter = Gear.at(0.75).score();
        double[][] cases = {
            // distance, health, gear, visible (1/0), approaching (1/0)
            {12, 20, IRON, 1, 0}, {25, 20, IRON, 1, 0}, {6, 14, IRON, 1, 1}, {40, 20, LEATHER, 1, 0},
            {10, 20, slightlyBetter, 1, 0}, {18, 8, IRON, 0, 0}, {30, 20, IRON, 0, 0}, {5, 20, IRON, 1, 1},
        };
        for (boolean base : new boolean[] {false, true}) {
            for (double[] c : cases) {
                S s = new S();
                s.baseBuilt = base;
                s.baseSafe = base;
                s.baseDistance = base ? 6 : Double.POSITIVE_INFINITY;
                s.contacts.add(new Contact(A, c[0], c[1], 20, c[2], c[3] > 0, false, c[4] > 0, false));
                for (double pressure : new double[] {0, 0.3, 0.7}) {
                    Decision d = new BotBrain(BotProfile.preset(difficulty)).decide(withPressure(s.build(), pressure,
                            null, null));
                    assertTrue(Set.of(Behaviour.ENGAGE, Behaviour.KITE).contains(d.behaviour()),
                            "case " + java.util.Arrays.toString(c) + " base " + base + " p " + pressure + ": " + d);
                    assertEquals(A, d.target());
                }
            }
        }
    }

    @ParameterizedTest
    @EnumSource(BotDifficulty.class)
    void aWeakenedEnemyRunningAwayIsChasedDown(BotDifficulty difficulty) {
        S s = new S();
        s.health = 14;
        s.contacts.add(new Contact(A, 20, 5, 20, IRON, true, false, false, false));
        Decision d = decide(difficulty, s);
        assertTrue(Set.of(Behaviour.ENGAGE, Behaviour.KITE).contains(d.behaviour()), d.toString());
        assertEquals(A, d.target());
    }

    @ParameterizedTest
    @EnumSource(BotDifficulty.class)
    void theLastFewInTheFinalZoneFightItOut(BotDifficulty difficulty) {
        BotBrain brain = new BotBrain(BotProfile.preset(difficulty));
        Set<Behaviour> passive = Set.of(Behaviour.AVOID, Behaviour.AMBUSH, Behaviour.FORTIFY, Behaviour.DEFEND_BASE,
                Behaviour.HOLD_ZONE, Behaviour.THIRD_PARTY);
        // outgeared, on high ground next to its base, with blocks: everything that used to make it hide
        S s = new S();
        s.shrinking = true;
        s.zoneDistance = 4;
        s.borderMargin = 7;
        s.gear = LEATHER;
        s.high = true;
        s.baseBuilt = true;
        s.baseSafe = true;
        s.baseDistance = 1;
        s.contacts.add(new Contact(A, 14, 20, 20, DIAMOND, true, false, true, true));
        Decision d = brain.decide(late(s.build(), 1, true, 1, 0));
        assertEquals(Behaviour.ENGAGE, d.behaviour(), "a 1v1 in the final zone is fought: " + d);
        // hurt, no apples left: still no shelter, no base
        s.health = 5;
        s.apples = 0;
        assertFalse(passive.contains(brain.decide(late(s.build(), 1, true, 1, 0)).behaviour()));
        // nobody in sight: it goes looking instead of holding a spot
        S alone = new S();
        alone.shrinking = true;
        alone.zoneDistance = 3;
        alone.borderMargin = 7;
        assertEquals(Behaviour.SCOUT, brain.decide(late(alone.build(), 1, true, 2, 0)).behaviour());
        // many still alive: the usual judgement (hurt, no apples, against a healthy one: back off)
        S crowd = new S();
        crowd.health = 5;
        crowd.apples = 0;
        crowd.contact(A, 6, 20, IRON, false);
        assertNotEquals(Behaviour.ENGAGE, brain.decide(late(crowd.build(), 0.2, true, 8, 0)).behaviour());
        assertEquals(Behaviour.ENGAGE, brain.decide(late(crowd.build(), 1, true, 1, 0)).behaviour(),
                "the same in a 1v1 showdown is fought");
        // and the border still comes first: outside, it gets back in
        S out = new S();
        out.shrinking = true;
        out.borderMargin = -2;
        out.contact(A, 6, 20, IRON, false);
        assertEquals(Behaviour.FLEE_BORDER, brain.decide(late(out.build(), 1, true, 1, 0)).behaviour());
    }

    @ParameterizedTest
    @EnumSource(BotDifficulty.class)
    void aBotThatHasNotFoughtGoesLookingForAFight(BotDifficulty difficulty) {
        BotBrain brain = new BotBrain(BotProfile.preset(difficulty));
        S s = new S();
        s.shrinking = true;
        s.zoneDistance = 10;
        s.baseBuilt = true;
        s.baseSafe = true;
        s.baseDistance = 2;
        assertEquals(Behaviour.SCOUT, brain.decide(late(s.build(), 0.5, false, 3, 1)).behaviour(),
                "mid-fight and idle: hunting, not holding a spot");
        assertEquals(Behaviour.HOLD_ZONE, brain.decide(late(s.build(), 0.0, false, 3, 0)).behaviour(),
                "right at the start of the shrink it may still head for the zone");
    }

    @org.junit.jupiter.api.Test
    void scoutingDriftsToTheZoneAsTheFightGoesOn() {
        assertTrue(BotController.scoutPull(0) > 0.2);
        assertTrue(BotController.scoutPull(1) > BotController.scoutPull(0.5));
        assertTrue(BotController.scoutPull(5) <= 0.8 + 1.0E-9);
    }

    @org.junit.jupiter.api.Test
    void gearReadingBlursTowardsTheAverage() {
        BotBrain sloppy = new BotBrain(BotProfile.preset(BotDifficulty.EASY));
        BotBrain sharp = new BotBrain(BotProfile.preset(BotDifficulty.HELL));
        assertTrue(Math.abs(sharp.perceived(DIAMOND) - DIAMOND) < Math.abs(sloppy.perceived(DIAMOND) - DIAMOND));
        assertEquals(DIAMOND, sharp.perceived(DIAMOND), 1.0E-9, "hell reads gear exactly");
    }

    @org.junit.jupiter.api.Test
    void decisionsStickALittle() {
        S s = new S();
        s.contact(A, 5, 15, IRON, false).contact(B, 5.2, 15, IRON, false);
        s.health = 20;
        Situation base = s.build();
        BotBrain brain = new BotBrain(BotProfile.preset(BotDifficulty.NORMAL));
        Decision first = brain.decide(base);
        Situation again = new Situation(base.fight(), base.farmingLeft(), base.health(), base.absorption(),
                base.maxHealth(), base.apples(), base.appleReady(), base.gear(), base.hasBow(), base.blocks(),
                base.borderMargin(), base.borderShrinking(), base.zoneDistance(), base.zoneRadius(), base.baseBuilt(),
                base.baseDone(), base.baseDistance(), base.baseSafe(), base.highGround(), first.behaviour(),
                first.target(), base.contacts());
        assertEquals(first.behaviour(), brain.decide(again).behaviour());
        assertEquals(first.target(), brain.decide(again).target());
    }

    @org.junit.jupiter.api.Test
    void everyDifficultyNumberIsConfigurable() {
        org.bukkit.configuration.MemoryConfiguration config = new org.bukkit.configuration.MemoryConfiguration();
        BotSettings.declareDefaults(config);
        BotSettings defaults = BotSettings.read(config);
        for (BotDifficulty difficulty : BotDifficulty.values()) {
            assertEquals(BotProfile.preset(difficulty), defaults.profile(difficulty), "defaults are the presets");
        }
        config.set("bots.hard.golden-apples", 9);
        config.set("bots.hard.reaction-ms", 0);
        config.set("bots.hard.base-size", 6);
        config.set(BotSettings.DIFFICULTY, "지옥");
        config.set(BotSettings.COUNT, 99);
        BotSettings read = BotSettings.read(config);
        assertEquals(9, read.profile(BotDifficulty.HARD).goldenApples());
        assertEquals(1, read.profile(BotDifficulty.HARD).reactionTicks(), "at least a tick");
        assertEquals(7, read.profile(BotDifficulty.HARD).baseSize(), "odd sizes only");
        assertEquals(BotDifficulty.HELL, read.difficulty());
        assertEquals(read.max(), read.count(), "capped");
        assertEquals("철수 봇", read.name(0));
    }

    @org.junit.jupiter.api.Test
    void harderDifficultiesExecuteBetter() {
        BotProfile easy = BotProfile.preset(BotDifficulty.EASY);
        BotProfile normal = BotProfile.preset(BotDifficulty.NORMAL);
        BotProfile hard = BotProfile.preset(BotDifficulty.HARD);
        BotProfile hell = BotProfile.preset(BotDifficulty.HELL);
        for (BotProfile[] pair : new BotProfile[][] {{easy, normal}, {normal, hard}, {hard, hell}}) {
            assertTrue(pair[0].reactionTicks() > pair[1].reactionTicks());
            assertTrue(pair[0].aimError() > pair[1].aimError());
            assertTrue(pair[0].critChance() < pair[1].critChance());
            assertTrue(pair[0].goldenApples() < pair[1].goldenApples());
            assertTrue(pair[0].buildPerSecond() < pair[1].buildPerSecond());
            assertTrue(pair[0].gearPace() < pair[1].gearPace());
        }
        assertTrue(easy.bowRange() > 0 && easy.critChance() > 0.3, "even easy bots use the bow and crit");
    }
}
