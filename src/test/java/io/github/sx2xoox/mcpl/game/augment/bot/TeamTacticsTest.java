package io.github.sx2xoox.mcpl.game.augment.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** 봇전 팀전 teamplay rules: leader, focus, peel, the shared base and shared sightings. */
class TeamTacticsTest {

    private static final UUID A = new UUID(0, 1);
    private static final UUID B = new UUID(0, 2);
    private static final UUID C = new UUID(0, 3);
    private static final UUID HUMAN = new UUID(0, 4);
    private static final UUID E1 = new UUID(1, 1);
    private static final UUID E2 = new UUID(1, 2);

    private static TeamTactics.Mate bot(UUID id, double power, double health) {
        return new TeamTactics.Mate(id, false, true, power, health);
    }

    @Test
    void aHumanTeammateLeadsElseTheStrongestBot() {
        TeamTactics.Mate human = new TeamTactics.Mate(HUMAN, true, true, 1, 1);
        assertEquals(HUMAN, TeamTactics.leader(List.of(bot(A, 50, 1), human, bot(B, 80, 1)), null),
                "the bots follow their human");
        assertEquals(B, TeamTactics.leader(List.of(bot(A, 50, 1), bot(B, 80, 1)), null), "else the strongest bot");
        assertEquals(A, TeamTactics.leader(List.of(bot(A, 70, 1), bot(B, 80, 1)), A),
                "the current leader keeps it unless another is clearly stronger");
        assertEquals(B, TeamTactics.leader(List.of(bot(A, 40, 1), bot(B, 80, 1)), A));
        TeamTactics.Mate deadHuman = new TeamTactics.Mate(HUMAN, true, false, 1, 0);
        assertEquals(B, TeamTactics.leader(List.of(deadHuman, bot(A, 40, 1), bot(B, 80, 1)), HUMAN),
                "a fallen human hands over to the bots");
        assertNull(TeamTactics.leader(List.of(deadHuman), null));
    }

    @Test
    void theTeamFocusesTheEnemyItHitsMostLately() {
        Set<UUID> team = Set.of(A, B, C);
        Set<UUID> enemies = Set.of(E1, E2);
        List<TeamTactics.Hit> hits = List.of(new TeamTactics.Hit(A, E1, 100), new TeamTactics.Hit(B, E2, 110),
                new TeamTactics.Hit(C, E2, 115), new TeamTactics.Hit(E1, A, 116));
        assertEquals(E2, TeamTactics.focus(hits, team, enemies, Map.of(), 120), "two teammates on E2");
        assertNull(TeamTactics.focus(hits, team, enemies, Map.of(), 120 + TeamTactics.FOCUS_TICKS + 20),
                "old hits are forgotten");
        List<TeamTactics.Hit> tie = List.of(new TeamTactics.Hit(A, E1, 100), new TeamTactics.Hit(B, E2, 100));
        assertEquals(E1, TeamTactics.focus(tie, team, enemies, Map.of(E1, 0.2, E2, 0.9), 100),
                "on a tie the weaker enemy");
        assertNull(TeamTactics.focus(List.of(new TeamTactics.Hit(E1, A, 100)), team, enemies, Map.of(), 100),
                "being hit is not a focus");
    }

    @Test
    void aBotPeelsForTheTeammateInTroubleAHumanFirst() {
        List<TeamTactics.Hit> hits = List.of(new TeamTactics.Hit(E1, A, 100), new TeamTactics.Hit(E2, HUMAN, 100));
        Set<UUID> enemies = Set.of(E1, E2);
        TeamTactics.Mate human = new TeamTactics.Mate(HUMAN, true, true, 1, 0.5);
        TeamTactics.Peel peel = TeamTactics.peel(List.of(bot(A, 1, 0.4), human, bot(B, 1, 1)), hits, enemies, B, 110);
        assertNotNull(peel);
        assertEquals(HUMAN, peel.mate(), "a hurt human comes first");
        assertEquals(E2, peel.enemy());
        assertTrue(peel.human());
        TeamTactics.Peel botPeel = TeamTactics.peel(List.of(bot(A, 1, 0.4), bot(B, 1, 1)), hits, enemies, B, 110);
        assertNotNull(botPeel);
        assertEquals(E1, botPeel.enemy());
        assertNull(TeamTactics.peel(List.of(bot(A, 1, 0.4), bot(B, 1, 1)), hits, enemies, A, 110),
                "it does not peel for itself");
        assertNull(TeamTactics.peel(List.of(bot(A, 1, 0.9), bot(B, 1, 1)), hits, enemies, B, 110), "healthy");
        assertNull(TeamTactics.peel(List.of(bot(A, 1, 0.4), bot(B, 1, 1)), hits, enemies, B,
                100 + TeamTactics.PEEL_TICKS + 1), "nobody hits it any more");
    }

    @Test
    void theBestBuilderBuildsTheTeamBaseWithEveryBuildersHelp() {
        TeamTactics.SharedBase base = TeamTactics.sharedBase(List.of(new TeamTactics.Builder(A, 3.5, 100),
                new TeamTactics.Builder(B, 10.5, 250), new TeamTactics.Builder(C, 6, 150)), null);
        assertNotNull(base);
        assertEquals(B, base.architect(), "the base specialist designs and builds it");
        assertEquals(10.5 + (3.5 + 6) * TeamTactics.HELPER_SHARE, base.rate(), 1.0E-9, "combined rate");
        assertEquals(250 + Math.round(100 * TeamTactics.HELPER_SHARE) + Math.round(150 * TeamTactics.HELPER_SHARE),
                base.stock());
        assertEquals(2, base.helpers());
        TeamTactics.SharedBase kept = TeamTactics.sharedBase(List.of(new TeamTactics.Builder(A, 3.5, 100),
                new TeamTactics.Builder(B, 10.5, 250)), A);
        assertEquals(A, kept.architect(), "the architect keeps its base while it lives");
        assertNull(TeamTactics.sharedBase(List.of(), null), "no builders, no team base");
        TeamTactics.SharedBase noTrapField = TeamTactics.sharedBase(List.of(new TeamTactics.Builder(A, 9.8, 230,
                true), new TeamTactics.Builder(B, 3.5, 100, false)), null);
        assertEquals(B, noTrapField.architect(), "a trapper traps around the team base, it does not design it");
        assertEquals(A, TeamTactics.sharedBase(List.of(new TeamTactics.Builder(A, 9.8, 230, true)), null)
                .architect(), "unless it is the only builder");
    }

    @Test
    void teammatesShareOnlyWhatOneOfThemSaw() {
        Set<UUID> team = Set.of(A, B);
        List<TeamTactics.Sighting> all = List.of(new TeamTactics.Sighting(E1, A, 100),
                new TeamTactics.Sighting(E1, B, 140), new TeamTactics.Sighting(E2, C, 150),
                new TeamTactics.Sighting(E2, A, -100));
        List<TeamTactics.Sighting> shared = TeamTactics.shared(all, team, 150, BotController.MEMORY_TICKS);
        assertEquals(1, shared.size(), "E2 was seen by someone else's team (and A's sighting is too old)");
        assertEquals(B, shared.getFirst().by(), "the latest sighting");
    }
}
