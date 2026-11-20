package io.github.sx2xoox.mcpl.core.combat;

import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * Per-match statistics of all participants. The hub fills kills, deaths and damage dealt/taken between participants
 * automatically (including ability damage, projectiles, summons and delayed kill credit); modes read them for
 * sidebars and results and may add custom counters through {@link PlayerStats#add(String, double)}.
 */
public final class MatchStats {

    /** Results order: more kills, then more damage dealt, then fewer deaths. */
    public static final Comparator<PlayerStats> PERFORMANCE = Comparator
            .comparingInt(PlayerStats::kills).reversed()
            .thenComparing(Comparator.comparingDouble(PlayerStats::damageDealt).reversed())
            .thenComparingInt(PlayerStats::deaths);

    private final Map<UUID, PlayerStats> stats = new LinkedHashMap<>();

    /** The stats of a player, created empty on first access. */
    public PlayerStats of(UUID playerId) {
        return stats.computeIfAbsent(playerId, PlayerStats::new);
    }

    /** The stats of a player, or null if nothing was recorded. */
    public @Nullable PlayerStats peek(UUID playerId) {
        return stats.get(playerId);
    }

    public void addKill(UUID killer) {
        of(killer).addKill();
    }

    public void addDeath(UUID victim) {
        of(victim).addDeath();
    }

    /** Records {@code amount} health points of damage from {@code attacker} to {@code victim}. */
    public void addDamage(UUID attacker, UUID victim, double amount) {
        of(attacker).addDamageDealt(amount);
        of(victim).addDamageTaken(amount);
    }

    public Map<UUID, PlayerStats> all() {
        return Collections.unmodifiableMap(stats);
    }

    /**
     * The best performer among {@code candidates} by {@link #PERFORMANCE}, or null if nobody scored a kill or dealt
     * damage.
     */
    public @Nullable UUID mvp(Collection<UUID> candidates) {
        PlayerStats best = null;
        for (UUID id : candidates) {
            PlayerStats candidate = of(id);
            if (candidate.kills() == 0 && candidate.damageDealt() <= 0) {
                continue;
            }
            if (best == null || PERFORMANCE.compare(candidate, best) < 0) {
                best = candidate;
            }
        }
        return best == null ? null : best.playerId();
    }
}
