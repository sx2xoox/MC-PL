package io.github.sx2xoox.mcpl.game.augment.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import io.github.sx2xoox.mcpl.game.augment.bot.BotBrain.Behaviour;
import io.github.sx2xoox.mcpl.game.augment.bot.BotBrain.Contact;
import io.github.sx2xoox.mcpl.game.augment.bot.BotBrain.Decision;
import io.github.sx2xoox.mcpl.game.augment.bot.BotBrain.Situation;
import io.github.sx2xoox.mcpl.game.augment.bot.BotBrain.TeamView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** 봇전 팀전 judgement: focus fire, peeling, regrouping, numbers, and the border still first. */
class BotBrainTeamTest {

    private static final UUID A = new UUID(0, 1);
    private static final UUID B = new UUID(0, 2);
    private static final double IRON = Gear.at(0.6).score();
    private static final double DIAMOND = Gear.at(1.4).score();

    private static Situation fight(double health, double borderMargin, List<Contact> contacts) {
        return new Situation(true, 0, health, 0, 20, 3, true, IRON, true, 64, borderMargin, true, 50, 12, false,
                true, Double.POSITIVE_INFINITY, false, false, null, null, contacts, 0.3);
    }

    private static Contact enemy(UUID id, double distance, double health, double gear, boolean fightingOther) {
        return new Contact(id, distance, health, 20, gear, true, fightingOther, false, false);
    }

    private static TeamView team(int alliesNear, double leaderDistance, UUID focus, UUID peel) {
        return new TeamView(alliesNear, leaderDistance, 16, false, focus, peel, false);
    }

    private static BotBrain brain(BotDifficulty difficulty) {
        return new BotBrain(BotProfile.preset(difficulty));
    }

    @ParameterizedTest
    @EnumSource(BotDifficulty.class)
    void itJoinsTheTargetItsTeamIsHitting(BotDifficulty difficulty) {
        List<Contact> two = List.of(enemy(A, 8, 20, IRON, false), enemy(B, 9, 20, IRON, false));
        Decision alone = brain(difficulty).decide(fight(20, 100, two), team(2, 3, null, null));
        Decision focused = brain(difficulty).decide(fight(20, 100, two), team(2, 3, B, null));
        assertEquals(Behaviour.ENGAGE, focused.behaviour(), focused.toString());
        assertEquals(B, focused.target(), "focus fire on the team's target (alone it picked " + alone + ")");
    }

    @ParameterizedTest
    @EnumSource(BotDifficulty.class)
    void itPeelsTheEnemyHittingATeammateInTrouble(BotDifficulty difficulty) {
        // A is the nearer, weaker target; B is beating up its teammate
        List<Contact> two = List.of(enemy(A, 6, 14, IRON, false), enemy(B, 12, 20, IRON, false));
        Decision d = brain(difficulty).decide(fight(20, 100, two), team(1, 3, null, B));
        assertEquals(Behaviour.ENGAGE, d.behaviour(), d.toString());
        assertEquals(B, d.target(), "peels for its teammate");
    }

    @Test
    void itNeverWaitsOutItsTeammatesFight() {
        List<Contact> one = List.of(enemy(A, 15, 20, IRON, true));
        BotBrain opportunist = new BotBrain(BotProfile.preset(BotDifficulty.NORMAL),
                BotPersonality.OPPORTUNIST.traits(new Random(1)), new Random(1));
        Decision ffa = opportunist.decide(fight(20, 100, one));
        Decision team = opportunist.decide(fight(20, 100, one), team(1, 3, A, null));
        assertEquals(Behaviour.THIRD_PARTY, ffa.behaviour(), "free-for-all: let them wear each other down");
        assertNotEquals(Behaviour.THIRD_PARTY, team.behaviour(), "its teammate's fight is its own: " + team);
    }

    @ParameterizedTest
    @EnumSource(BotDifficulty.class)
    void twoVersusTwoIsNoReasonToRun(BotDifficulty difficulty) {
        List<Contact> two = List.of(enemy(A, 8, 20, IRON, false), enemy(B, 10, 20, IRON, false));
        Decision together = brain(difficulty).decide(fight(20, 100, two), team(1, 3, null, null));
        if (difficulty == BotDifficulty.EASY) {
            Decision alone = brain(difficulty).decide(fight(20, 100, two));
            assertEquals(Behaviour.AVOID, alone.behaviour(), "alone against two: " + alone);
        }
        assertTrue(together.behaviour() == Behaviour.ENGAGE || together.behaviour() == Behaviour.KITE,
                "with a teammate it fights: " + together);
    }

    @ParameterizedTest
    @EnumSource(BotDifficulty.class)
    void aStrayBotRegroupsWithItsLeader(BotDifficulty difficulty) {
        Decision near = brain(difficulty).decide(fight(20, 100, List.of()), team(0, 10, null, null));
        Decision far = brain(difficulty).decide(fight(20, 100, List.of()), team(0, 60, null, null));
        assertNotEquals(Behaviour.REGROUP, near.behaviour(), "within the cohesion radius it does its own thing");
        assertEquals(Behaviour.REGROUP, far.behaviour(), "far from its leader it goes back: " + far);
        TeamView leads = new TeamView(0, 60, 16, true, null, null, false);
        assertNotEquals(Behaviour.REGROUP, brain(difficulty).decide(fight(20, 100, List.of()), leads).behaviour(),
                "the leader follows nobody");
    }

    @Test
    void aStrayBotFightsTheEnemyInFrontOfItBeforeRegrouping() {
        Decision d = brain(BotDifficulty.NORMAL).decide(fight(20, 100, List.of(enemy(A, 5, 20, IRON, false))),
                team(0, 40, null, null));
        assertEquals(Behaviour.ENGAGE, d.behaviour(), d.toString());
    }

    @Test
    void aBuilderKeepsBuildingTheTeamBaseWhileFarming() {
        BotBrain builder = new BotBrain(BotProfile.preset(BotDifficulty.NORMAL),
                BotPersonality.FORTRESS.traits(new Random(2)), new Random(2));
        Situation farming = new Situation(false, 0.6, 20, 0, 20, 0, false, IRON, false, 64, 100, false, 50, 12,
                false, false, Double.POSITIVE_INFINITY, false, false, null, null, List.of());
        assertEquals(Behaviour.BUILD_BASE, builder.decide(farming, team(0, 80, null, null)).behaviour());
    }

    @ParameterizedTest
    @EnumSource(BotDifficulty.class)
    void theBorderStillBeatsEveryTeamReason(BotDifficulty difficulty) {
        List<Contact> contacts = new ArrayList<>(List.of(enemy(A, 6, 10, IRON, false), enemy(B, 12, 20, DIAMOND,
                true)));
        BotBrain tank = new BotBrain(BotProfile.preset(difficulty), BotPersonality.TANK.traits(new Random(3)),
                new Random(3));
        Decision d = tank.decide(fight(20, -4, contacts), new TeamView(3, 80, 16, false, A, B, true));
        assertEquals(Behaviour.FLEE_BORDER, d.behaviour(), "outside the border: back in first, " + d);
        Decision edge = tank.decide(fight(20, 1, contacts), new TeamView(3, 80, 16, false, A, B, true));
        assertEquals(Behaviour.FLEE_BORDER, edge.behaviour(), "at the edge too: " + edge);
    }

    @ParameterizedTest
    @EnumSource(BotPersonality.class)
    void farOutsideNoTeamBonusLiftsAFightOverTheBorder(BotPersonality personality) {
        // far outside where the border is heading: getting back in scores over 200, so every other option is capped
        // just under it (200 or more); the team's focus, peel and allies must not lift such a capped fight over it
        // (only a real sure kill may: an enemy within reach with almost no health)
        List<Contact> contacts = List.of(enemy(A, 5, 12, IRON, false), enemy(B, 9, 20, IRON, true));
        for (int seed = 0; seed < 6; seed++) {
            BotBrain brain = new BotBrain(BotProfile.preset(BotDifficulty.HARD), personality.traits(new Random(seed)),
                    new Random(seed));
            Decision d = brain.decide(fight(20, -12, contacts), new TeamView(3, 4, 16, false, A, A, true));
            assertEquals(Behaviour.FLEE_BORDER, d.behaviour(), personality + " far outside: back in first, " + d);
        }
        BotBrain brain = new BotBrain(BotProfile.preset(BotDifficulty.HARD), personality.traits(new Random(1)),
                new Random(1));
        Decision finish = brain.decide(fight(20, -12, List.of(enemy(A, 2, 3, IRON, false))),
                new TeamView(3, 4, 16, false, A, null, false));
        assertEquals(Behaviour.ENGAGE, finish.behaviour(), personality + ": a sure kill is still worth it, " + finish);
    }
}
