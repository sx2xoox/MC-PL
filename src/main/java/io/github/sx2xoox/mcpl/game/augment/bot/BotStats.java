package io.github.sx2xoox.mcpl.game.augment.bot;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import io.github.sx2xoox.mcpl.game.augment.bot.BotBrain.Behaviour;

/**
 * The fight numbers of one 봇전 match for the server log: per bot and per difficulty, how many swings landed, crits,
 * arrows, damage dealt and taken, kills, where it placed and what killed it. Pure (no Bukkit) so the summary is
 * unit-tested; {@link BotManager} feeds it from the damage and death events.
 */
public final class BotStats {

    /** The counters of one bot. */
    public static final class Line {
        final String name;
        final String difficulty;
        final String personality;
        int swings;
        int connected;
        int meleeHits;
        int crits;
        int arrows;
        int arrowHits;
        int hitsTaken;
        double dealt;
        double taken;
        int kills;
        int apples;
        int place;
        String death = "";
        final Map<Behaviour, Integer> behaviours = new EnumMap<>(Behaviour.class);

        Line(String name, String difficulty, String personality) {
            this.name = name;
            this.difficulty = difficulty;
            this.personality = personality;
        }

        /** Hits it landed (melee and arrows). */
        int hits() {
            return meleeHits + arrowHits;
        }
    }

    private final Map<Object, Line> lines = new LinkedHashMap<>();

    /** Starts counting for {@code key} (its participant id). */
    public void add(Object key, String name, String difficulty, String personality) {
        lines.putIfAbsent(key, new Line(name, difficulty, personality));
    }

    /** The counters of {@code key}, or null if it is not a bot. */
    public Line line(Object key) {
        return lines.get(key);
    }

    /** A swing of its sword: whether it was on target ({@code connected}) and a crit. */
    public void swing(Object key, boolean connected, boolean crit) {
        Line l = lines.get(key);
        if (l != null) {
            l.swings++;
            if (connected) {
                l.connected++;
                if (crit) {
                    l.crits++;
                }
            }
        }
    }

    /** It loosed an arrow. */
    public void arrow(Object key) {
        Line l = lines.get(key);
        if (l != null) {
            l.arrows++;
        }
    }

    /** It ate a golden apple. */
    public void apple(Object key) {
        Line l = lines.get(key);
        if (l != null) {
            l.apples++;
        }
    }

    /**
     * {@code attacker} dealt {@code damage} to {@code victim}: {@code arrow} with a projectile, {@code melee} with a
     * direct hit (neither: fire, poison, thorns ...).
     */
    public void damage(Object attacker, Object victim, double damage, boolean arrow, boolean melee) {
        Line a = lines.get(attacker);
        if (a != null) {
            a.dealt += damage;
            if (arrow) {
                a.arrowHits++;
            } else if (melee) {
                a.meleeHits++;
            }
        }
        Line v = lines.get(victim);
        if (v != null) {
            v.hitsTaken++;
            v.taken += damage;
        }
    }

    /** {@code bot} died at {@code place} (1 = won), of {@code cause}, credited to {@code killer} (or null). */
    public void died(Object bot, int place, String cause, Object killer) {
        Line l = lines.get(bot);
        if (l != null) {
            l.place = place;
            l.death = cause;
        }
        Line k = killer == null ? null : lines.get(killer);
        if (k != null) {
            k.kills++;
        }
    }

    /** A bot standing at the end placed first. */
    public void won(Object bot) {
        Line l = lines.get(bot);
        if (l != null && l.place == 0) {
            l.place = 1;
        }
    }

    /** One behaviour sample of {@code bot} (taken every few seconds of the fight). */
    public void sample(Object bot, Behaviour behaviour) {
        Line l = lines.get(bot);
        if (l != null) {
            l.behaviours.merge(behaviour, 1, Integer::sum);
        }
    }

    /** Lines for the server log: one per difficulty (summed), then one per bot. */
    public List<String> report() {
        List<String> out = new ArrayList<>();
        Map<String, List<Line>> byDifficulty = new TreeMap<>();
        for (Line l : lines.values()) {
            byDifficulty.computeIfAbsent(l.difficulty, k -> new ArrayList<>()).add(l);
        }
        for (Map.Entry<String, List<Line>> e : byDifficulty.entrySet()) {
            out.add(e.getKey() + ": " + summary(e.getValue()));
        }
        for (Line l : lines.values()) {
            out.add(String.format(Locale.ROOT, "%s [%s·%s] place=%d kills=%d %s death=%s behaviours=%s", l.name,
                    l.difficulty, l.personality, l.place, l.kills, numbers(List.of(l)), l.death.isEmpty() ? "-"
                            : l.death, l.behaviours));
        }
        return out;
    }

    /** The summed numbers of a group of bots. */
    static String summary(List<Line> group) {
        int wins = 0;
        double place = 0;
        int kills = 0;
        int border = 0;
        for (Line l : group) {
            wins += l.place == 1 ? 1 : 0;
            place += l.place;
            kills += l.kills;
            border += l.death.equals("border") ? 1 : 0;
        }
        return String.format(Locale.ROOT, "bots=%d wins=%d avgPlace=%.1f kills=%d borderDeaths=%d %s", group.size(),
                wins, group.isEmpty() ? 0 : place / group.size(), kills, border, numbers(group));
    }

    private static String numbers(List<Line> group) {
        int swings = 0;
        int connected = 0;
        int melee = 0;
        int crits = 0;
        int arrows = 0;
        int arrowHits = 0;
        int taken = 0;
        int apples = 0;
        double dealt = 0;
        double hurt = 0;
        for (Line l : group) {
            swings += l.swings;
            connected += l.connected;
            melee += l.meleeHits;
            crits += l.crits;
            arrows += l.arrows;
            arrowHits += l.arrowHits;
            taken += l.hitsTaken;
            apples += l.apples;
            dealt += l.dealt;
            hurt += l.taken;
        }
        return String.format(Locale.ROOT,
                "swings=%d melee=%d crit=%.2f arrows=%d/%d hits/taken=%d/%d (%.2f) dmg/taken=%.0f/%.0f (%.2f) apples=%d",
                swings, melee, connected == 0 ? 0 : (double) crits / connected, arrowHits, arrows, melee + arrowHits, taken,
                taken == 0 ? 0 : (double) (melee + arrowHits) / taken, dealt, hurt, hurt == 0 ? 0 : dealt / hurt,
                apples);
    }
}
