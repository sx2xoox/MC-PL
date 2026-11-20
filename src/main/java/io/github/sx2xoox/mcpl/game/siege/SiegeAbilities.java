package io.github.sx2xoox.mcpl.game.siege;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * Who gets which ability in a 공성전 round (pure): random from the pool ({@code games.siege.ability-pool}), never twice
 * in one team, and unique over both teams while the pool has enough abilities.
 */
public final class SiegeAbilities {

    /** The pool a round draws from, and whether it had to be filled up with other enabled abilities. */
    public record Pool(List<String> ids, boolean filledUp, List<String> unknown) {
    }

    private SiegeAbilities() {
    }

    /**
     * The round's pool: the configured ids that are enabled (all enabled ones when the list is empty), filled up with
     * other enabled abilities (random) when it has fewer abilities than the biggest team has players.
     */
    public static Pool pool(List<String> configured, List<String> enabled, int biggestTeam, Random random) {
        List<String> ids = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        for (String id : configured) {
            if (enabled.contains(id)) {
                if (!ids.contains(id)) {
                    ids.add(id);
                }
            } else {
                unknown.add(id);
            }
        }
        if (configured.isEmpty()) {
            ids.addAll(enabled);
        }
        boolean filled = false;
        if (ids.size() < biggestTeam) {
            List<String> rest = new ArrayList<>(enabled);
            rest.removeAll(ids);
            Collections.shuffle(rest, random);
            for (String id : rest) {
                if (ids.size() >= biggestTeam) {
                    break;
                }
                ids.add(id);
                filled = true;
            }
        }
        return new Pool(List.copyOf(ids), filled, List.copyOf(unknown));
    }

    /** One ability per player: unique within a team, unique overall while the pool allows. */
    public static Map<UUID, String> assign(List<List<UUID>> teams, List<String> pool, Random random) {
        if (pool.isEmpty()) {
            throw new IllegalArgumentException("The ability pool is empty");
        }
        Set<String> used = new HashSet<>();
        Map<UUID, String> out = new LinkedHashMap<>();
        for (List<UUID> team : teams) {
            Set<String> teamUsed = new HashSet<>();
            for (UUID player : team) {
                String id = draw(pool, teamUsed, used, random);
                if (id == null) {
                    id = pool.get(random.nextInt(pool.size()));
                }
                out.put(player, id);
                teamUsed.add(id);
                used.add(id);
            }
        }
        return out;
    }

    /**
     * A reroll: another ability than {@code current} that no teammate holds, preferring one the other team does not
     * hold either; null when nothing is left.
     */
    public static @Nullable String reroll(String current, List<String> pool, Collection<String> teammates,
                                          Collection<String> enemies, Random random) {
        Set<String> blocked = new HashSet<>(teammates);
        blocked.add(current);
        return draw(pool, blocked, new HashSet<>(enemies), random);
    }

    private static @Nullable String draw(List<String> pool, Set<String> forbidden, Set<String> avoid, Random random) {
        List<String> fresh = new ArrayList<>();
        List<String> allowed = new ArrayList<>();
        for (String id : pool) {
            if (forbidden.contains(id)) {
                continue;
            }
            allowed.add(id);
            if (!avoid.contains(id)) {
                fresh.add(id);
            }
        }
        List<String> from = fresh.isEmpty() ? allowed : fresh;
        return from.isEmpty() ? null : from.get(random.nextInt(from.size()));
    }
}
