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
 * The 공성전 score (pure, no Bukkit): team points, towers and kills of the current round, kill streaks (bounties),
 * assists, deaths this half (respawn delay) and every player's contribution (MVP). Teams are {@code 0} (A) and
 * {@code 1} (B).
 */
public final class SiegeScore {

    /** What a kill was worth. */
    public record Kill(int points, boolean bounty, int victimStreak, List<UUID> assisters, int assistPoints) {
    }

    private final SiegeSettings settings;
    private final ToIntFunction<UUID> teamOf;
    private final int[] points = new int[2];
    private final int[] towers = new int[2];
    private final int[] kills = new int[2];
    private final int[] roundWins = new int[2];
    /** Team that destroyed the last tower this round, or -1. */
    private int lastTowerTeam = -1;
    private final Map<UUID, Integer> streaks = new HashMap<>();
    private final Map<UUID, Integer> halfDeaths = new HashMap<>();
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

    public int streak(UUID player) {
        return streaks.getOrDefault(player, 0);
    }

    public int halfDeaths(UUID player) {
        return halfDeaths.getOrDefault(player, 0);
    }

    public int lastTowerTeam() {
        return lastTowerTeam;
    }

    /** The team that reached the win score, or -1. */
    public int reachedWinScore() {
        for (int team = 0; team < 2; team++) {
            if (points[team] >= settings.winScore()) {
                return team;
            }
        }
        return -1;
    }

    /**
     * The winner when the second half ran out: more points, then more towers destroyed, then more kills; -1 when all
     * of that is tied (sudden death).
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
     * {@code victim} died at {@code tick}, killed by {@code killer} (null: no kill credit). Updates the streaks and
     * deaths, gives the killer's team the kill (or bounty) points and the assisters' team the assist points.
     */
    public Kill kill(UUID victim, @Nullable UUID killer, long tick) {
        int victimStreak = streaks.getOrDefault(victim, 0);
        streaks.put(victim, 0);
        halfDeaths.merge(victim, 1, Integer::sum);
        Map<UUID, Long> hits = recentHits.remove(victim);
        int killerTeam = killer == null ? -1 : teamOf.applyAsInt(killer);
        if (killer == null || killerTeam < 0 || killerTeam == teamOf.applyAsInt(victim)) {
            return new Kill(0, false, victimStreak, List.of(), 0);
        }
        boolean bounty = victimStreak >= settings.bountyStreak();
        int value = bounty ? settings.bountyPoints() : settings.killPoints();
        streaks.merge(killer, 1, Integer::sum);
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
        return new Kill(value, bounty, victimStreak, List.copyOf(assisters), assistPoints);
    }

    /**
     * {@code team} destroyed the tower {@code towerId}; {@code topDamager} (may be null) gets the contribution.
     *
     * @return the points given
     */
    public int towerDestroyed(int team, String towerId, boolean climax, @Nullable UUID topDamager) {
        double base = settings.towerPoints(towerId);
        int value = (int) Math.round(climax ? base * settings.climaxTowerMultiplier() : base);
        points[team] += value;
        towers[team]++;
        lastTowerTeam = team;
        if (topDamager != null) {
            credit(topDamager, value);
        }
        return value;
    }

    /** {@code team} held its attackable tower for the defence time; every listed defender shares the credit. */
    public int defenceSuccess(int team, List<UUID> defenders) {
        points[team] += settings.defencePoints();
        for (UUID defender : defenders) {
            credit(defender, Math.max(1, settings.defencePoints() / Math.max(1, defenders.size())));
        }
        return settings.defencePoints();
    }

    /** Sudden death or a test: plain points for a team. */
    public void addPoints(int team, int value) {
        points[team] += value;
    }

    private void credit(UUID player, int value) {
        roundContribution.merge(player, value, Integer::sum);
        totalContribution.merge(player, value, Integer::sum);
    }

    // ------------------------------------------------------------------ flow

    /** A new half: deaths (respawn delay), streaks and assist hits start over; points are kept for the round. */
    public void newHalf() {
        halfDeaths.clear();
        streaks.clear();
        recentHits.clear();
    }

    /** {@code team} won the round. */
    public void roundWon(int team) {
        roundWins[team]++;
    }

    /** A new round: points, towers and kills start over. */
    public void newRound() {
        newHalf();
        points[0] = points[1] = 0;
        towers[0] = towers[1] = 0;
        kills[0] = kills[1] = 0;
        lastTowerTeam = -1;
        roundContribution.clear();
    }
}
