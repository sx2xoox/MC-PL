package io.github.sx2xoox.mcpl.game.siege;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.ToIntFunction;

import org.jspecify.annotations.Nullable;

/**
 * The 공성전 score (pure, no Bukkit): team points, towers and kills of the current round, assists, deaths this round
 * (respawn delay) and every player's contribution (MVP). Teams are the indices of {@code SiegeMatch#teams()}.
 */
public final class SiegeScore {

    /** What a kill was worth. */
    public record Kill(int points, List<UUID> assisters, int assistPoints) {
    }

    private final SiegeSettings settings;
    private final ToIntFunction<UUID> teamOf;
    private final int[] points = new int[2];
    private final int[] towers = new int[2];
    private final int[] kills = new int[2];
    private final int[] roundWins = new int[2];
    private final Map<UUID, Integer> roundDeaths = new HashMap<>();
    private final Map<UUID, Integer> roundContribution = new LinkedHashMap<>();
    private final Map<UUID, Integer> totalContribution = new LinkedHashMap<>();
    /** Victim → (attacker → tick of the last hit). */
    private final Map<UUID, Map<UUID, Long>> recentHits = new HashMap<>();

    public SiegeScore(SiegeSettings settings, ToIntFunction<UUID> teamOf) {
        this.settings = settings;
        this.teamOf = teamOf;
    }

    // ------------------------------------------------------------------ queries

    public int points(int team) {
        return points[team];
    }

    public int towers(int team) {
        return towers[team];
    }

    public int kills(int team) {
        return kills[team];
    }

    public int roundWins(int team) {
        return roundWins[team];
    }

    /** Deaths of {@code player} this round (respawn delay). */
    public int roundDeaths(UUID player) {
        return roundDeaths.getOrDefault(player, 0);
    }

    /**
     * The winner when the round's time ran out: more points, then more towers destroyed, then more kills; -1 when all
     * of that is tied (overtime).
     */
    public int timeUpWinner() {
        if (points[0] != points[1]) {
            return points[0] > points[1] ? 0 : 1;
        }
        if (towers[0] != towers[1]) {
            return towers[0] > towers[1] ? 0 : 1;
        }
        if (kills[0] != kills[1]) {
            return kills[0] > kills[1] ? 0 : 1;
        }
        return -1;
    }

    /** The player with the most contribution this round (null when nobody scored). */
    public @Nullable UUID roundMvp() {
        return best(roundContribution);
    }

    /** The player with the most contribution over the whole match. */
    public @Nullable UUID matchMvp() {
        return best(totalContribution);
    }

    public int contribution(UUID player) {
        return totalContribution.getOrDefault(player, 0);
    }

    public int roundContribution(UUID player) {
        return roundContribution.getOrDefault(player, 0);
    }

    private static @Nullable UUID best(Map<UUID, Integer> map) {
        UUID best = null;
        int most = 0;
        for (Map.Entry<UUID, Integer> entry : map.entrySet()) {
            if (entry.getValue() > most) {
                most = entry.getValue();
                best = entry.getKey();
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ events

    /** {@code attacker} hit {@code victim} at {@code tick} (assists). */
    public void recordHit(UUID attacker, UUID victim, long tick) {
        if (attacker.equals(victim)) {
            return;
        }
        recentHits.computeIfAbsent(victim, id -> new HashMap<>()).put(attacker, tick);
    }

    /**
     * {@code victim} died at {@code tick}, killed by {@code killer} (null: no kill credit). Counts the death, gives the
     * killer's team the kill points and the assist points of every teammate who hit the victim lately.
     */
    public Kill kill(UUID victim, @Nullable UUID killer, long tick) {
        roundDeaths.merge(victim, 1, Integer::sum);
        Map<UUID, Long> hits = recentHits.remove(victim);
        int killerTeam = killer == null ? -1 : teamOf.applyAsInt(killer);
        if (killer == null || killerTeam < 0 || killerTeam == teamOf.applyAsInt(victim)) {
            return new Kill(0, List.of(), 0);
        }
        int value = settings.killPoints();
        kills[killerTeam]++;
        points[killerTeam] += value;
        credit(killer, value);
        List<UUID> assisters = new ArrayList<>();
        if (hits != null) {
            for (Map.Entry<UUID, Long> hit : hits.entrySet()) {
                UUID assister = hit.getKey();
                if (!assister.equals(killer) && teamOf.applyAsInt(assister) == killerTeam
                        && tick - hit.getValue() <= settings.assistTicks()) {
                    assisters.add(assister);
                }
            }
        }
        int assistPoints = assisters.size() * settings.assistPoints();
        points[killerTeam] += assistPoints;
        for (UUID assister : assisters) {
            credit(assister, settings.assistPoints());
        }
        return new Kill(value, List.copyOf(assisters), assistPoints);
    }

    /**
     * {@code team} destroyed an enemy tower; {@code topDamager} (may be null) gets the contribution.
     *
     * @return the points given
     */
    public int towerDestroyed(int team, @Nullable UUID topDamager) {
        int value = settings.towerPoints();
        points[team] += value;
        towers[team]++;
        if (topDamager != null) {
            credit(topDamager, value);
        }
        return value;
    }

    /** Points for {@code team} from anything else (jungle, objectives, overtime tests); {@code contributor} may be null. */
    public void addPoints(int team, int value, @Nullable UUID contributor) {
        points[team] += value;
        if (contributor != null && value > 0) {
            credit(contributor, value);
        }
    }

    private void credit(UUID player, int value) {
        roundContribution.merge(player, value, Integer::sum);
        totalContribution.merge(player, value, Integer::sum);
    }

    // ------------------------------------------------------------------ flow

    /** {@code team} won the round. */
    public void roundWon(int team) {
        roundWins[team]++;
    }

    /** A new round: points, towers, kills, deaths and assist hits start over. */
    public void newRound() {
        roundDeaths.clear();
        recentHits.clear();
        points[0] = points[1] = 0;
        towers[0] = towers[1] = 0;
        kills[0] = kills[1] = 0;
        roundContribution.clear();
    }
}
