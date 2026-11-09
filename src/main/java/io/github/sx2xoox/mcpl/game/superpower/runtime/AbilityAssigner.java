package io.github.sx2xoox.mcpl.game.superpower.runtime;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * Pure assignment logic (no Bukkit): abilities are drawn from the pool of <b>enabled</b> abilities so that no two
 * players share one while the pool allows it, and beyond that every ability is used as evenly as possible.
 */
public final class AbilityAssigner {

    private AbilityAssigner() {
    }

    /**
     * RANDOM mode: one ability per player.
     *
     * @param players players in a stable order (the result keeps it)
     * @param pool    enabled ability ids (must not be empty)
     * @param forced  admin assignments; entries not in the pool (disabled abilities) are ignored
     * @return ability id per player, in {@code players} order
     */
    public static Map<UUID, String> assign(List<UUID> players, List<String> pool, Map<UUID, String> forced,
                                           Random random) {
        requirePool(pool);
        Map<String, Integer> usage = new HashMap<>();
        Map<UUID, String> chosen = new HashMap<>();
        for (UUID player : players) {
            String id = forced.get(player);
            if (id != null && pool.contains(id)) {
                chosen.put(player, id);
                usage.merge(id, 1, Integer::sum);
            }
        }
        List<UUID> rest = new ArrayList<>();
        for (UUID player : players) {
            if (!chosen.containsKey(player)) {
                rest.add(player);
            }
        }
        Collections.shuffle(rest, random);
        for (UUID player : rest) {
            String id = leastUsed(pool, usage, Set.of(), random);
            chosen.put(player, id);
            usage.merge(id, 1, Integer::sum);
        }
        Map<UUID, String> ordered = new LinkedHashMap<>();
        for (UUID player : players) {
            ordered.put(player, chosen.get(player));
        }
        return ordered;
    }

    /**
     * CHOICE mode: {@code count} distinct candidates per player, disjoint across players while the pool allows.
     *
     * @param reserved ability ids already taken (e.g. forced players); avoided while possible
     */
    public static Map<UUID, List<String>> candidates(List<UUID> players, List<String> pool, int count,
                                                     Collection<String> reserved, Random random) {
        requirePool(pool);
        Map<String, Integer> usage = new HashMap<>();
        for (String id : reserved) {
            usage.merge(id, 1, Integer::sum);
        }
        List<UUID> order = new ArrayList<>(players);
        Collections.shuffle(order, random);
        Map<UUID, List<String>> picked = new HashMap<>();
        int perPlayer = Math.max(1, Math.min(count, pool.size()));
        for (UUID player : order) {
            List<String> mine = new ArrayList<>(perPlayer);
            Set<String> exclude = new HashSet<>();
            for (int i = 0; i < perPlayer; i++) {
                String id = leastUsed(pool, usage, exclude, random);
                if (id == null) {
                    break;
                }
                mine.add(id);
                exclude.add(id);
                usage.merge(id, 1, Integer::sum);
            }
            picked.put(player, List.copyOf(mine));
        }
        Map<UUID, List<String>> ordered = new LinkedHashMap<>();
        for (UUID player : players) {
            ordered.put(player, picked.get(player));
        }
        return ordered;
    }

    /**
     * A reroll result: any enabled ability except {@code current}, preferring ones nobody else holds.
     *
     * @return null if the pool has nothing else
     */
    public static @Nullable String reroll(String current, List<String> pool, Collection<String> heldByOthers,
                                          Random random) {
        List<String> free = new ArrayList<>();
        List<String> any = new ArrayList<>();
        for (String id : pool) {
            if (id.equals(current)) {
                continue;
            }
            any.add(id);
            if (!heldByOthers.contains(id)) {
                free.add(id);
            }
        }
        List<String> from = free.isEmpty() ? any : free;
        return from.isEmpty() ? null : from.get(random.nextInt(from.size()));
    }

    private static @Nullable String leastUsed(List<String> pool, Map<String, Integer> usage, Set<String> exclude,
                                              Random random) {
        int min = Integer.MAX_VALUE;
        List<String> best = new ArrayList<>();
        for (String id : pool) {
            if (exclude.contains(id)) {
                continue;
            }
            int used = usage.getOrDefault(id, 0);
            if (used < min) {
                min = used;
                best.clear();
            }
            if (used == min) {
                best.add(id);
            }
        }
        return best.isEmpty() ? null : best.get(random.nextInt(best.size()));
    }

    private static void requirePool(List<String> pool) {
        if (pool.isEmpty()) {
            throw new IllegalArgumentException("No enabled abilities");
        }
    }
}
