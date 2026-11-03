package io.github.sx2xoox.mcpl.game.siege;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import org.bukkit.configuration.MemoryConfiguration;
import org.junit.jupiter.api.Test;

/** The pure 공성전 rules: scoring, respawn delays, tower health scaling and the ability assignment. */
class SiegeRulesTest {

    private static final UUID A1 = new UUID(0, 1);
    private static final UUID A2 = new UUID(0, 2);
    private static final UUID A3 = new UUID(0, 3);
    private static final UUID B1 = new UUID(1, 1);
    private static final UUID B2 = new UUID(1, 2);

    private static SiegeSettings defaults() {
        MemoryConfiguration config = new MemoryConfiguration();
        SiegeSettings.declareDefaults(config);
        return SiegeSettings.read(config);
    }

    private static SiegeScore score(SiegeSettings settings) {
        return new SiegeScore(settings, id -> id.getMostSignificantBits() == 0 ? 0 : 1);
    }

    @Test
    void defaultsMatchTheDesign() {
        SiegeSettings s = defaults();
        assertEquals(2, s.roundsToWin());
        assertEquals(100, s.winScore());
        assertEquals(12 * 60 * 20, s.halfTicks());
        assertEquals(2, s.killPoints());
        assertEquals(5, s.bountyPoints());
        assertEquals(5, s.bountyStreak());
        assertEquals(1, s.assistPoints());
        assertEquals(15, s.towerPoints("front"));
        assertEquals(20, s.towerPoints("middle"));
        assertEquals(30, s.towerPoints("core"));
        assertEquals(5, s.defencePoints());
        assertEquals(240 * 20, s.defenceTicks());
        assertEquals(180 * 20, s.climaxTicks());
        assertEquals(SiegeSettings.DEFAULT_POOL, s.abilityPool());
        assertEquals(1, s.rerolls());
    }

    @Test
    void killsBountiesAndAssists() {
        SiegeSettings settings = defaults();
        SiegeScore score = score(settings);
        score.recordHit(A2, B1, 100);
        score.recordHit(A3, B1, 10);
        SiegeScore.Kill kill = score.kill(B1, A1, 200);
        assertEquals(2, kill.points());
        assertEquals(List.of(A2), kill.assisters(), "only hits within the assist window count");
        assertEquals(3, score.points(0), "kill + one assist");
        assertEquals(1, score.kills(0));

        // A1 goes on a 5 kill streak: the next kill on A1 is a bounty.
        for (int i = 1; i < 5; i++) {
            score.kill(B2, A1, 1000 + i);
        }
        assertEquals(5, score.streak(A1));
        SiegeScore.Kill bounty = score.kill(A1, B1, 2000);
        assertTrue(bounty.bounty());
        assertEquals(5, bounty.points());
        assertEquals(5, score.points(1));
        assertEquals(0, score.streak(A1), "the streak ends with the death");

        SiegeScore.Kill selfKill = score.kill(A2, null, 3000);
        assertEquals(0, selfKill.points(), "no killer, no points");
        assertEquals(1, score.halfDeaths(A2));
        SiegeScore.Kill teamKill = score.kill(A3, A1, 3000);
        assertEquals(0, teamKill.points(), "a teammate gets nothing");
    }

    @Test
    void towersDefenceAndClimax() {
        SiegeScore score = score(defaults());
        assertEquals(15, score.towerDestroyed(0, "front", false, A1));
        assertEquals(30, score.towerDestroyed(0, "middle", true, A1), "총공세: x1.5");
        assertEquals(45, score.towerDestroyed(0, "core", true, null));
        assertEquals(90, score.points(0));
        assertEquals(3, score.towers(0));
        assertEquals(-1, score.reachedWinScore());
        assertEquals(5, score.defenceSuccess(1, List.of(B1, B2)));
        score.addPoints(0, 10);
        assertEquals(0, score.reachedWinScore());
        assertEquals(A1, score.roundMvp());
    }

    @Test
    void timeUpTieBreaks() {
        SiegeScore score = score(defaults());
        assertEquals(-1, score.timeUpWinner(), "all tied: sudden death");
        score.kill(B1, A1, 0);
        score.kill(A1, B1, 0);
        assertEquals(-1, score.timeUpWinner(), "same points, towers and kills");
        score.addPoints(1, 13);
        score.towerDestroyed(0, "front", false, null);
        assertEquals(0, score.timeUpWinner(), "more points (17 : 15)");
        score.addPoints(1, 2);
        assertEquals(0, score.timeUpWinner(), "same points: more towers");
        score.newRound();
        score.kill(B1, A1, 0);
        score.addPoints(1, 2);
        assertEquals(0, score.timeUpWinner(), "same points and towers: more kills");
        score.roundWon(0);
        assertEquals(1, score.roundWins(0));
    }

    @Test
    void respawnDelayGrowsWithDeathsAndShrinksInTheClimax() {
        SiegeSettings s = defaults();
        assertEquals(120, s.respawnDelay(1, false));
        assertEquals(120, s.respawnDelay(2, false));
        assertEquals(140, s.respawnDelay(3, false), "+1 s every 3 deaths");
        assertEquals(160, s.respawnDelay(6, false));
        assertEquals(240, s.respawnDelay(60, false), "capped at 12 s");
        assertEquals(80, s.respawnDelay(1, true), "총공세: -2 s");
    }

    @Test
    void towerHealthScalesWithTheAttackers() {
        SiegeSettings s = defaults();
        assertEquals(1200, s.towerHealth("front", 3), 1.0E-9, "the configured health is for three attackers");
        assertEquals(600, s.towerHealth("front", 1), 1.0E-9);
        assertEquals(1800, s.towerHealth("front", 5), 1.0E-9);
        assertEquals(1800 * 2.25, s.towerHealth("core", 8), 1.0E-9);
    }

    @Test
    void abilitiesAreUniqueInATeamAndOverallWhenThePoolAllows() {
        Random random = new Random(7);
        List<String> pool = List.of("a", "b", "c", "d", "e");
        for (int run = 0; run < 50; run++) {
            Map<UUID, String> assigned = SiegeAbilities.assign(List.of(List.of(A1, A2), List.of(B1, B2)), pool,
                    random);
            assertEquals(4, new HashSet<>(assigned.values()).size(), "enough abilities: all different");
        }
        List<String> small = List.of("a", "b", "c");
        for (int run = 0; run < 50; run++) {
            Map<UUID, String> assigned = SiegeAbilities.assign(List.of(List.of(A1, A2, A3), List.of(B1, B2)),
                    small, random);
            Set<String> teamA = Set.of(assigned.get(A1), assigned.get(A2), assigned.get(A3));
            assertEquals(3, teamA.size(), "never twice in one team");
            assertNotEquals(assigned.get(B1), assigned.get(B2));
        }
    }

    @Test
    void rerollKeepsTheTeamUniqueAndPrefersFreeAbilities() {
        Random random = new Random(3);
        List<String> pool = List.of("a", "b", "c", "d");
        for (int run = 0; run < 30; run++) {
            String next = SiegeAbilities.reroll("a", pool, List.of("b"), List.of("c"), random);
            assertEquals("d", next, "not the old one, not a teammate's, preferably not an enemy's");
        }
        assertEquals("c", SiegeAbilities.reroll("a", List.of("a", "b", "c"), List.of("b"), List.of("c"), random),
                "an enemy's ability when nothing else is left");
        assertNull(SiegeAbilities.reroll("a", List.of("a", "b"), List.of("b"), List.of(), random));
    }

    @Test
    void thePoolIsFilledUpWhenSmallerThanATeam() {
        List<String> enabled = new ArrayList<>(List.of("police", "miner", "x", "y", "z"));
        SiegeAbilities.Pool pool = SiegeAbilities.pool(List.of("police", "ghost"), enabled, 3, new Random(1));
        assertEquals(List.of("ghost"), pool.unknown());
        assertTrue(pool.filledUp());
        assertEquals(3, pool.ids().size());
        assertEquals("police", pool.ids().getFirst());
        SiegeAbilities.Pool all = SiegeAbilities.pool(List.of(), enabled, 3, new Random(1));
        assertEquals(enabled, all.ids(), "an empty list means every enabled ability");
        assertFalse(all.filledUp());
    }
}
