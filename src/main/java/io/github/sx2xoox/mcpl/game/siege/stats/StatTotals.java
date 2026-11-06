package io.github.sx2xoox.mcpl.game.siege.stats;

import org.bukkit.configuration.ConfigurationSection;

/** The running totals of one ability (or bot job) over every recorded, decided round. */
public final class StatTotals {

    int rounds;
    double seconds;
    double enemySkill;
    double enemyBasic;
    double structure;
    double creature;
    double taken;
    int kills;
    int deaths;
    int assists;
    int wins;

    /** Adds a decided round. */
    void add(StatLine line) {
        rounds++;
        seconds += line.seconds();
        enemySkill += line.enemySkill();
        enemyBasic += line.enemyBasic();
        structure += line.structure();
        creature += line.creature();
        taken += line.taken();
        kills += line.kills();
        deaths += line.deaths();
        assists += line.assists();
        if (line.result() == StatLine.Result.WIN) {
            wins++;
        }
    }

    public int rounds() {
        return rounds;
    }

    public double seconds() {
        return seconds;
    }

    public double enemyDamage() {
        return enemySkill + enemyBasic;
    }

    public double enemySkill() {
        return enemySkill;
    }

    public double enemyBasic() {
        return enemyBasic;
    }

    public double structure() {
        return structure;
    }

    public double creature() {
        return creature;
    }

    public double taken() {
        return taken;
    }

    public int kills() {
        return kills;
    }

    public int deaths() {
        return deaths;
    }

    public int assists() {
        return assists;
    }

    public int wins() {
        return wins;
    }

    /** Damage to enemy players and bots per minute played (all its time over all its rounds). */
    public double enemyPerMinute() {
        return perMinute(enemyDamage());
    }

    /** Damage to structures per minute played. */
    public double structurePerMinute() {
        return perMinute(structure);
    }

    /** (kills + assists) / deaths, deaths counted as at least 1. */
    public double kda() {
        return (kills + assists) / (double) Math.max(1, deaths);
    }

    /** Rounds won / rounds, 0 … 1. */
    public double winRate() {
        return rounds == 0 ? 0 : wins / (double) rounds;
    }

    private double perMinute(double amount) {
        return seconds > 0 ? amount * 60.0 / seconds : 0;
    }

    void write(ConfigurationSection out) {
        out.set("rounds", rounds);
        out.set("seconds", round2(seconds));
        out.set("enemy-skill", round2(enemySkill));
        out.set("enemy-basic", round2(enemyBasic));
        out.set("structure", round2(structure));
        out.set("creature", round2(creature));
        out.set("taken", round2(taken));
        out.set("kills", kills);
        out.set("deaths", deaths);
        out.set("assists", assists);
        out.set("wins", wins);
    }

    static StatTotals read(ConfigurationSection in) {
        StatTotals totals = new StatTotals();
        totals.rounds = Math.max(0, in.getInt("rounds"));
        totals.seconds = finite(in.getDouble("seconds"));
        totals.enemySkill = finite(in.getDouble("enemy-skill"));
        totals.enemyBasic = finite(in.getDouble("enemy-basic"));
        totals.structure = finite(in.getDouble("structure"));
        totals.creature = finite(in.getDouble("creature"));
        totals.taken = finite(in.getDouble("taken"));
        totals.kills = Math.max(0, in.getInt("kills"));
        totals.deaths = Math.max(0, in.getInt("deaths"));
        totals.assists = Math.max(0, in.getInt("assists"));
        totals.wins = Math.max(0, in.getInt("wins"));
        return totals;
    }

    private static double finite(double value) {
        return Double.isFinite(value) ? Math.max(0, value) : 0;
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
