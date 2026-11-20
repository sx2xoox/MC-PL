package io.github.sx2xoox.mcpl.game.superpower;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import io.github.sx2xoox.mcpl.game.superpower.runtime.AbilityAssigner;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

class AbilityAssignerTest {

    private static final Random RANDOM = new Random(7);

    private static List<String> pool(int size) {
        List<String> pool = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            pool.add("ability" + i);
        }
        return pool;
    }

    private static List<UUID> players(int count) {
        List<UUID> players = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            players.add(UUID.randomUUID());
        }
        return players;
    }

    @RepeatedTest(5)
    void abilitiesAreUniqueWhileThePoolAllows() {
        List<UUID> players = players(24);
        Map<UUID, String> result = AbilityAssigner.assign(players, pool(24), Map.of(), RANDOM);
        assertEquals(players, new ArrayList<>(result.keySet()), "player order is kept");
        assertEquals(24, new HashSet<>(result.values()).size());
    }

    @RepeatedTest(5)
    void duplicatesAreSpreadEvenlyWhenThereAreMorePlayersThanAbilities() {
        Map<UUID, String> result = AbilityAssigner.assign(players(11), pool(4), Map.of(), RANDOM);
        Map<String, Integer> usage = new HashMap<>();
        result.values().forEach(id -> usage.merge(id, 1, Integer::sum));
        assertEquals(4, usage.size());
        assertTrue(Collections.max(usage.values()) - Collections.min(usage.values()) <= 1, usage.toString());
    }

    @Test
    void forcedAssignmentsAreHonouredAndOthersAvoidThem() {
        List<UUID> players = players(3);
        List<String> pool = pool(3);
        Map<UUID, String> forced = Map.of(players.get(1), "ability2");
        Map<UUID, String> result = AbilityAssigner.assign(players, pool, forced, RANDOM);
        assertEquals("ability2", result.get(players.get(1)));
        assertEquals(3, new HashSet<>(result.values()).size(), "the forced ability is not handed out twice");
    }

    @Test
    void forcedDisabledAbilitiesAreIgnored() {
        List<UUID> players = players(2);
        Map<UUID, String> forced = Map.of(players.getFirst(), "disabled");
        Map<UUID, String> result = AbilityAssigner.assign(players, pool(2), forced, RANDOM);
        assertTrue(pool(2).containsAll(result.values()), "never an ability outside the enabled pool");
    }

    @Test
    void emptyPoolIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> AbilityAssigner.assign(players(2), List.of(), Map.of(), RANDOM));
    }

    @RepeatedTest(5)
    void choiceCandidatesAreDistinctAndDisjointAcrossPlayers() {
        List<UUID> players = players(8);
        Map<UUID, List<String>> candidates = AbilityAssigner.candidates(players, pool(24), 3, Set.of(), RANDOM);
        Set<String> all = new HashSet<>();
        for (UUID player : players) {
            List<String> mine = candidates.get(player);
            assertEquals(3, mine.size());
            assertEquals(3, new HashSet<>(mine).size(), "a player's candidates differ");
            all.addAll(mine);
        }
        assertEquals(24, all.size(), "no two players share a candidate while the pool allows it");
    }

    @Test
    void choiceCandidatesAvoidReservedAbilities() {
        Map<UUID, List<String>> candidates = AbilityAssigner.candidates(players(1), pool(4), 3, Set.of("ability0"),
                RANDOM);
        assertFalse(candidates.values().iterator().next().contains("ability0"));
    }

    @Test
    void smallPoolsGiveFewerCandidates() {
        Map<UUID, List<String>> candidates = AbilityAssigner.candidates(players(2), pool(2), 3, Set.of(), RANDOM);
        candidates.values().forEach(list -> assertEquals(2, list.size()));
    }

    @RepeatedTest(10)
    void rerollChangesTheAbilityAndPrefersFreeOnes() {
        List<String> pool = pool(4);
        String next = AbilityAssigner.reroll("ability0", pool, List.of("ability1", "ability2"), RANDOM);
        assertEquals("ability3", next);
        String any = AbilityAssigner.reroll("ability0", pool, List.of("ability1", "ability2", "ability3"), RANDOM);
        assertNotEquals("ability0", any);
        assertNull(AbilityAssigner.reroll("ability0", List.of("ability0"), List.of(), RANDOM));
    }
}
