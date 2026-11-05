package io.github.sx2xoox.mcpl.core.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    @Test
    void aFixedTeamCountSpreadsPlayersAndLeavesRoomForBots() {
        List<UUID> two = players(2);
        List<MatchTeam> teams = TeamAssigner.spread(two, 4, new Random(1));
        assertEquals(4, teams.size(), "exactly the asked count, even with fewer players");
        assertEquals(2, teams.stream().filter(t -> t.size() == 1).count(), "one human per team at most");
        for (int i = 0; i < 6; i++) {
            TeamAssigner.addToSmallest(teams, UUID.randomUUID()); // the bots fill the empty places
        }
        assertTrue(teams.stream().allMatch(t -> t.size() == 2), "every team ends up full");
        List<MatchTeam> ordered = TeamAssigner.spread(players(6), 3, null);
        assertEquals(2, ordered.getFirst().size());
        assertEquals(2, TeamAssigner.spread(players(3), 1, null).size(), "at least two teams");
    }

    private static MatchTeam team(List<MatchTeam> teams, String id) {
        return teams.stream().filter(t -> t.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void picksAreKeptAndTheRestBalanceAround() {
        List<UUID> players = players(6);
        Map<UUID, String> picks = new LinkedHashMap<>();
        picks.put(players.get(0), "blue");
        picks.put(players.get(1), "blue");
        picks.put(players.get(2), "red");
        TeamAssigner.Formation formation = TeamAssigner.form(players, picks, 2, 0, true, false, new Random(4));
        List<MatchTeam> teams = formation.teams();
        assertEquals(List.of("red", "blue"), teams.stream().map(MatchTeam::id).toList());
        assertTrue(team(teams, "blue").contains(players.get(0)) && team(teams, "blue").contains(players.get(1)));
        assertTrue(team(teams, "red").contains(players.get(2)));
        assertEquals(3, team(teams, "red").size(), "the players without a pick fill the smaller team");
        assertEquals(3, team(teams, "blue").size());
        assertTrue(formation.moved().isEmpty());
        assertTrue(formation.refused().isEmpty());
    }

    @Test
    void aFixedCountRefusesOtherColors() {
        List<UUID> players = players(4);
        Map<UUID, String> picks = Map.of(players.get(0), "green", players.get(1), "red");
        TeamAssigner.Formation formation = TeamAssigner.form(players, picks, 2, 3, true, false, new Random(2));
        assertEquals(2, formation.teams().size(), "the mode's count wins over the team size");
        assertEquals(Set.of(players.get(0)), formation.refused());
        assertTrue(team(formation.teams(), "red").contains(players.get(1)));
        assertEquals(2, formation.teams().getFirst().size());
        assertEquals(2, formation.teams().get(1).size());
    }

    @Test
    void lopsidedPicksAreEvenedOutUnlessBotsFill() {
        List<UUID> players = players(4);
        Map<UUID, String> picks = new LinkedHashMap<>();
        players.forEach(p -> picks.put(p, "red"));
        TeamAssigner.Formation balanced = TeamAssigner.form(players, picks, 2, 0, true, false, new Random(5));
        assertEquals(2, balanced.teams().getFirst().size());
        assertEquals(2, balanced.teams().get(1).size());
        assertEquals(2, balanced.moved().size(), "the moved pickers are reported");

        TeamAssigner.Formation free = TeamAssigner.form(players, picks, 2, 0, false, false, new Random(5));
        assertEquals(3, team(free.teams(), "red").size(), "balance off: only the empty team gets a player");
        assertEquals(1, team(free.teams(), "blue").size());

        TeamAssigner.Formation bots = TeamAssigner.form(players, picks, 2, 0, true, true, new Random(5));
        assertEquals(4, team(bots.teams(), "red").size(), "bots fill the other side");
        assertEquals(0, team(bots.teams(), "blue").size());
        assertTrue(bots.moved().isEmpty());

        List<UUID> solo = players(1);
        TeamAssigner.Formation alone = TeamAssigner.form(solo, Map.of(solo.getFirst(), "blue"), 2, 0, true, false,
                new Random(1));
        assertTrue(team(alone.teams(), "blue").contains(solo.getFirst()), "one player alone keeps the pick");
    }

    @Test
    void aTeamSizeUsesThePickedColors() {
        List<UUID> players = players(4);
        Map<UUID, String> picks = Map.of(players.get(0), "yellow", players.get(1), "purple");
        TeamAssigner.Formation formation = TeamAssigner.form(players, picks, 0, 2, true, false, new Random(3));
        assertEquals(List.of("yellow", "purple"), formation.teams().stream().map(MatchTeam::id).toList());
        assertEquals(0, formation.teams().getFirst().index());
        assertTrue(formation.teams().stream().allMatch(t -> t.size() == 2));

        Map<UUID, String> three = Map.of(players.get(0), "red", players.get(1), "blue", players.get(2), "green");
        assertEquals(3, TeamAssigner.form(players, three, 0, 2, true, false, new Random(3)).teams().size(),
                "more picked colors than the size needs: every picked team is made");
        assertTrue(TeamAssigner.form(players, picks, 0, 0, true, false, new Random(3)).teams().isEmpty(),
                "free-for-all ignores picks");
    }

    @Test
    void withoutPicksFormingMatchesTheOldSplit() {
        List<MatchTeam> fixed = TeamAssigner.form(players(5), Map.of(), 2, 0, true, false, new Random(1)).teams();
        assertEquals(2, fixed.size());
        assertEquals(5, fixed.getFirst().size() + fixed.get(1).size());
        assertTrue(Math.abs(fixed.getFirst().size() - fixed.get(1).size()) <= 1);
        List<MatchTeam> sized = TeamAssigner.form(players(10), Map.of(), 0, 3, true, false, new Random(1)).teams();
        assertEquals(4, sized.size());
        assertEquals("빨강 팀", sized.getFirst().name());
    }

    @Test
    void teamNamesAreReadAsPlayersTypeThem() {
        assertEquals("red", TeamAssigner.parse("빨강").id());
        assertEquals("blue", TeamAssigner.parse("파랑 팀").id());
        assertEquals("green", TeamAssigner.parse("GREEN").id());
        assertNull(TeamAssigner.parse("무지개"));
    }
}
