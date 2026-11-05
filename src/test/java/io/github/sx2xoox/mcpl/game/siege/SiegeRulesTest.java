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

import io.github.sx2xoox.mcpl.game.siege.tower.Tower;
import org.bukkit.configuration.MemoryConfiguration;
import org.junit.jupiter.api.Test;

/** The pure 공성전 rules: scoring, tie-breaks, respawn delays, structure health scaling and the ability assignment. */
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
        assertEquals(2, s.roundsToWin(), "best of three");
        assertEquals(20 * 60 * 20, s.roundTicks(), "20 minute rounds");
        assertEquals(60 * 20, s.roundBreakTicks(), "a minute between rounds");
        assertEquals(120 * 20, s.overtimeTicks(), "2 minute overtime");
        assertEquals(2, s.killPoints());
        assertEquals(1, s.assistPoints());
        assertEquals(8, s.towerPoints());
        assertEquals(3 * 20, s.protectionTicks());
        assertEquals(SiegeSettings.DEFAULT_POOL, s.abilityPool());
        assertEquals(70, SiegeSettings.DEFAULT_POOL.size());
        assertEquals(1, s.rerolls());
    }

    @Test
    void killsAndAssists() {
        SiegeSettings settings = defaults();
        SiegeScore score = score(settings);
        score.recordHit(A2, B1, 100);
        score.recordHit(A3, B1, 10);
        SiegeScore.Kill kill = score.kill(B1, A1, 200);
        assertEquals(2, kill.points());
        assertEquals(List.of(A2), kill.assisters(), "only hits within the assist window count");
        assertEquals(3, score.points(0), "kill + one assist");
        assertEquals(1, score.kills(0));
        assertEquals(1, score.roundDeaths(B1));

        SiegeScore.Kill selfKill = score.kill(A2, null, 3000);
        assertEquals(0, selfKill.points(), "no killer, no points");
        assertEquals(1, score.roundDeaths(A2));
        SiegeScore.Kill teamKill = score.kill(A3, A1, 3000);
        assertEquals(0, teamKill.points(), "a teammate gets nothing");
    }

    @Test
    void towersAndOtherPoints() {
        SiegeScore score = score(defaults());
        assertEquals(8, score.towerDestroyed(0, A1));
        assertEquals(8, score.towerDestroyed(0, null));
        assertEquals(16, score.points(0));
        assertEquals(2, score.towers(0));
        score.addPoints(1, 10, B1);
        assertEquals(10, score.points(1));
        assertEquals(B1, score.roundMvp(), "jungle points count for the MVP too");
    }

    @Test
    void timeUpTieBreaks() {
        SiegeScore score = score(defaults());
        assertEquals(-1, score.timeUpWinner(), "all tied: overtime");
        score.kill(B1, A1, 0);
        score.kill(A1, B1, 0);
        assertEquals(-1, score.timeUpWinner(), "same points, towers and kills");
        score.addPoints(1, 9, null);
        score.towerDestroyed(0, null);
        assertEquals(1, score.timeUpWinner(), "more points (10 : 11)");
        score.addPoints(0, 1, null);
        assertEquals(0, score.timeUpWinner(), "same points (11 : 11): more towers");
        score.newRound();
        assertEquals(0, score.roundDeaths(A1), "deaths start over every round");
        score.kill(B1, A1, 0);
        score.addPoints(1, 2, null);
        assertEquals(0, score.timeUpWinner(), "same points and towers: more kills");
        score.roundWon(0);
        assertEquals(1, score.roundWins(0));
    }

    @Test
    void respawnDelayGrowsWithDeathsUpToFourteenSeconds() {
        SiegeSettings s = defaults();
        assertEquals(120, s.respawnDelay(1), "6 s");
        assertEquals(120, s.respawnDelay(2));
        assertEquals(140, s.respawnDelay(3), "+1 s every 3 deaths");
        assertEquals(160, s.respawnDelay(6));
        assertEquals(280, s.respawnDelay(24), "14 s");
        assertEquals(280, s.respawnDelay(60), "capped at 14 s");
    }

    @Test
    void structureHealthScalesWithTheEnemyTeam() {
        SiegeSettings s = defaults();
        assertEquals(1500, s.structureHealth(Tower.Kind.OUTER, 3), 1.0E-9, "configured for three enemies");
        assertEquals(750, s.structureHealth(Tower.Kind.OUTER, 1), 1.0E-9);
        assertEquals(3000, s.structureHealth(Tower.Kind.INNER, 5), 1.0E-9);
        assertEquals(2500 * 2.25, s.structureHealth(Tower.Kind.CORE, 8), 1.0E-9);
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

    @Test
    void aSavedFirstFiveJobPoolBecomesTheCurrentDefault() {
        MemoryConfiguration old = new MemoryConfiguration();
        old.set(SiegeSettings.ABILITY_POOL, List.of("police", "miner", "blacksmith", "gunslinger", "chef"));
        assertEquals(SiegeSettings.DEFAULT_POOL, SiegeSettings.read(old).abilityPool());

        MemoryConfiguration custom = new MemoryConfiguration();
        custom.set(SiegeSettings.ABILITY_POOL, List.of("police", "miner"));
        assertEquals(List.of("police", "miner"), SiegeSettings.read(custom).abilityPool());
    }

    @Test
    void aSavedFiftyPoolBecomesTheSeventyButAnEditedOneStays() {
        List<String> fifty = new java.util.ArrayList<>(SiegeSettings.DEFAULT_POOL.subList(0, 50));
        java.util.Collections.reverse(fifty);
        MemoryConfiguration old = new MemoryConfiguration();
        old.set(SiegeSettings.ABILITY_POOL, fifty);
        assertEquals(SiegeSettings.DEFAULT_POOL, SiegeSettings.read(old).abilityPool(), "the old default, any order");

        List<String> edited = new java.util.ArrayList<>(SiegeSettings.DEFAULT_POOL.subList(0, 49));
        edited.add("minotaur");
        MemoryConfiguration custom = new MemoryConfiguration();
        custom.set(SiegeSettings.ABILITY_POOL, edited);
        assertEquals(edited, SiegeSettings.read(custom).abilityPool(), "a pool the owner changed is kept");

        List<String> fewer = new java.util.ArrayList<>(SiegeSettings.DEFAULT_POOL.subList(0, 49));
        MemoryConfiguration trimmed = new MemoryConfiguration();
        trimmed.set(SiegeSettings.ABILITY_POOL, fewer);
        assertEquals(fewer, SiegeSettings.read(trimmed).abilityPool(), "one taken out of the 50 is kept as it is");
    }
}
