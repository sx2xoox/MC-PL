package io.github.sx2xoox.mcpl.core.combat;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One participant's numbers for one match. Kills, deaths and damage between participants are counted by the hub;
 * modes may add their own counters with {@link #add(String, double)} (e.g. {@code "ultimates"}).
 */
public final class PlayerStats {

    private final UUID playerId;
    private int kills;
    private int deaths;
    private double damageDealt;
    private double damageTaken;
    private final Map<String, Double> custom = new LinkedHashMap<>();

    PlayerStats(UUID playerId) {
        this.playerId = playerId;
    }

    public UUID playerId() {
        return playerId;
    }

    public int kills() {
        return kills;
    }

    public int deaths() {
        return deaths;
    }

    /** Damage dealt to other participants, in health points. */
    public double damageDealt() {
        return damageDealt;
    }

    /** Damage taken from other participants, in health points. */
    public double damageTaken() {
        return damageTaken;
    }

    public void addKill() {
        kills++;
    }

    public void addDeath() {
        deaths++;
    }

    public void addDamageDealt(double amount) {
        if (amount > 0 && Double.isFinite(amount)) {
            damageDealt += amount;
        }
    }

    public void addDamageTaken(double amount) {
        if (amount > 0 && Double.isFinite(amount)) {
            damageTaken += amount;
        }
    }

    /** Adds to a mode-defined counter. */
    public void add(String key, double amount) {
        custom.merge(key, amount, Double::sum);
    }

    /** A mode-defined counter, 0 if never set. */
    public double get(String key) {
        return custom.getOrDefault(key, 0.0);
    }

    public Map<String, Double> custom() {
        return Collections.unmodifiableMap(custom);
    }
}
