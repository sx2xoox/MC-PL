package io.github.sx2xoox.mcpl.core.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class TeamAssignerTest {

    private static List<UUID> players(int count) {
        List<UUID> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            out.add(UUID.randomUUID());
        }
        return out;
    }

    @Test
    void freeForAllWhenSizeIsZero() {
        assertTrue(TeamAssigner.assign(players(6), 0, new Random(1)).isEmpty());
    }

    @Test
    void balancedTeamsCoverEveryone() {
        List<UUID> players = players(10);
        List<MatchTeam> teams = TeamAssigner.assign(players, 3, new Random(42));
        assertEquals(4, teams.size(), "ceil(10 / 3) teams");

        Set<UUID> seen = new HashSet<>();
        Set<String> names = new HashSet<>();
        Set<String> ids = new HashSet<>();
        int min = Integer.MAX_VALUE;
        int max = 0;
        for (MatchTeam team : teams) {
            for (UUID member : team.members()) {
                assertTrue(seen.add(member), "each player is on exactly one team");
            }
            min = Math.min(min, team.size());
            max = Math.max(max, team.size());
            names.add(team.name());
            ids.add(team.id());
        }
        assertEquals(10, seen.size());
        assertTrue(max - min <= 1, "sizes differ by at most one");
        assertEquals(4, names.size());
        assertEquals(4, ids.size());
        assertEquals("빨강 팀", teams.getFirst().name());
        assertEquals("파랑 팀", teams.get(1).name());
    }

    @Test
    void atLeastTwoTeams() {
        List<MatchTeam> teams = TeamAssigner.assign(players(4), 8, new Random(3));
        assertEquals(2, teams.size());
        assertEquals(2, teams.getFirst().size());
        assertEquals(2, teams.get(1).size());
    }

    @Test
    void lateJoinerGoesToSmallestTeam() {
        List<MatchTeam> teams = TeamAssigner.assign(players(5), 2, new Random(7));
        assertEquals(3, teams.size());
        MatchTeam smallest = teams.stream().filter(t -> t.size() == 1).findFirst().orElseThrow();
        UUID late = UUID.randomUUID();
        assertEquals(smallest, TeamAssigner.addToSmallest(teams, late));
        assertTrue(smallest.contains(late));
    }

    @Test
    void manyTeamsGetNumberedNames() {
        List<MatchTeam> teams = TeamAssigner.assign(players(40), 2, new Random(9));
        assertEquals(20, teams.size());
        assertEquals("17번 팀", teams.get(16).name());
    }
}
