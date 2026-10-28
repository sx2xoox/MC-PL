package io.github.sx2xoox.mcpl.game.augment.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
