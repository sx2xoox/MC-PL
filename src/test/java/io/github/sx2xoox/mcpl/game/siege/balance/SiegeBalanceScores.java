package io.github.sx2xoox.mcpl.game.siege.balance;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.sx2xoox.mcpl.game.siege.balance.SiegeBalanceSettings.Correction;
import io.github.sx2xoox.mcpl.game.siege.balance.SiegeRoles.Role;
import org.jspecify.annotations.Nullable;

/**
 * Scores of the 공성전 pool from the balance lab's measurements ({@link SiegeLabData}) under a set of per-ability
 * corrections: per level the fight metrics, a simple 1v1 duel model between every pair (→ 전투력 = average win
 * chance), the siege contribution (buildings, waves, monsters), the team contribution (other targets hit, control,
 * help) and the weighted total; the role bands of {@link SiegeRoles}. Plain numbers, no Bukkit.
 */
final class SiegeBalanceScores {

    /** An ultimate every this many seconds in a long fight (gauge from fighting + the recharge lock). */
    static final double ULT_PERIOD = 40;
    static final double ULT_SHARE = 30 / ULT_PERIOD;
    static final double WAVE_HEALTH = 3 * 22 + 2 * 15;
    static final double JUNGLE_HEALTH = 90;
    static final double DUEL_MAX = 60;
    static final double DUEL_EXPONENT = 2;
    /** Crowd-control weights: hard (stun/root/airborne) 1, silence 0.6, soft (slow/blind fraction) 0.5. */
    static final double SILENCE_WEIGHT = 0.6;
    static final double SOFT_WEIGHT = 0.5;
    /** Blocks a second a fighter closes a gap at (sprinting). */
    static final double CLOSE_SPEED = 5;
    static final double MAX_CC_SHARE = 0.5;
    static final double W_COMBAT = 0.55;
    static final double W_SIEGE = 0.20;
    static final double W_TEAM = 0.25;
    static final double CLIP = 3;

    /** One ability's metrics at one level (corrections applied). */
    record Metrics(String id, int level, double dps, double burst3, double cc, double hard, double survival,
                   double invuln, double mobility, double range, double support, double aoeExtra, double tower,
                   double waveTime, double jungleTime, double knock, double sustainFlat, double prevented,
                   double probeDps, double health) {
    }

    /** One ability's scores at one level. */
    record LevelScore(Metrics metrics, double combat, double siege, double team, double total,
                      Map<String, Double> normalized, double winrateOutliers) {
    }

    /** One ability over the lab levels: the average total and its per-level scores. */
    record Score(String id, Role role, double total, double combat, double siege, double team,
                 Map<Integer, LevelScore> levels, Map<String, Double> normalized, double outlierShare,
                 List<String> bandProblems) {
    }

    record Result(Map<String, Score> scores, double median, Map<Integer, double[][]> winrates, List<String> ids) {
        Score score(String id) {
            return scores.get(id);
        }
    }

    private SiegeBalanceScores() {
    }

    static double levelFactor(int level) {
        return 1 + SiegeBalanceSettings.DEFAULT_DAMAGE_PER_LEVEL * (level - 1);
    }

    static double health(int level) {
        return 20 + (level / 2) * 2;
    }

    /** The damage factor of the S2 / S3 / S4 scenarios (skill and ultimate hits mixed) under a correction. */
    static double blend(SiegeLabData.Row row, Correction c) {
        double skill = Math.max(0, row.get("skill12"));
        double ult = Math.max(0, row.get("ult12") - skill);
        return skill + ult < 1.0E-9 ? c.skill() : (skill * c.skill() + ult * c.ultimate()) / (skill + ult);
    }

    static double ccOf(SiegeLabData.Row row, String suffix) {
        return row.get("hard" + suffix) + SILENCE_WEIGHT * row.get("silence" + suffix)
                + SOFT_WEIGHT * row.get("soft" + suffix);
    }

    private static double ultPart(SiegeLabData.Row row, String... prefixes) {
        double u = 0;
        double s = 0;
        for (String prefix : prefixes) {
            u += row.get(prefix + "U");
            s += row.get(prefix + "12");
        }
        return Math.max(0, u - s);
    }

    static double window(double[] perSecond, int seconds) {
        double best = 0;
        for (int start = 0; start < perSecond.length; start++) {
            double sum = 0;
            for (int i = start; i < Math.min(perSecond.length, start + seconds); i++) {
                sum += perSecond[i];
            }
            best = Math.max(best, sum);
        }
        return best;
    }

    /**
     * @param real the real-match calibration of its damage on players (1 without records: {@link SiegeRealStats})
     */
    static Metrics metrics(SiegeLabData.Row row, Correction c, double real) {
        int level = row.level();
        double g = SiegeBalanceSettings.DEFAULT_ABILITY_DAMAGE;
        double lf = levelFactor(level);
        double skill12 = Math.max(0, row.get("skill12"));
        double ultDamage = Math.max(0, row.get("ult12") - skill12);
        double b = blend(row, c);
        // at melee range a kit may do more: buffs of the next hits or of melee (미미르의 샘, 거대화), close-range hits
        double ultValue = Math.max(ultDamage, row.get("ultMelee"));
        double skill30 = Math.max(row.get("dmg30"), 2.5 * row.get("skillMelee"));
        double dps = (skill30 * c.skill() + ULT_SHARE * ultValue * c.ultimate()) / 30 * real;
        double burst = Math.max(window(row.s1(), 3) * c.skill(), window(row.s2(), 3) * b) * real;
        double cc = ccOf(row, "30") + ULT_SHARE * Math.max(0, ccOf(row, "U") - ccOf(row, "12"));
        double hard = row.get("hard30") + ULT_SHARE * Math.max(0, row.get("hardU") - row.get("hard12"));
        double flat = row.get("heal30") + row.get("shield30") + 0.5 * row.get("bonusHp")
                + ULT_SHARE * ultPart(row, "heal", "shield") - 0.5 * row.get("selfCost30");
        double prevented = row.get("prevented30") + ULT_SHARE * ultPart(row, "prevented");
        double survival = flat + prevented;
        double invuln = row.get("invuln30") + ULT_SHARE * ultPart(row, "invuln");
        double mobility = row.get("path30") + ULT_SHARE * ultPart(row, "path");
        double support = row.get("allyHeal30") + row.get("allyShield30") + row.get("allyPrevented30")
                + 0.3 * row.get("allyBuff30")
                + ULT_SHARE * (ultPart(row, "allyHeal", "allyShield", "allyPrevented")
                + 0.3 * ultPart(row, "allyBuff"));
        double aoeExtra = Math.max(0, row.get("aoe5") - row.get("aoe1")) * b;
        double tower = row.get("tower20") * c.objective();
        double raw = 1 / (g * lf);
        double waveRate = row.get("aoe5") * raw / 10 * c.objective();
        double waveTime = waveRate > 1.0E-6 ? Math.min(60, WAVE_HEALTH / waveRate) : 60;
        double jungleRate = (row.get("dmg30") + ULT_SHARE * ultDamage) * raw / 30 * c.objective();
        double jungleTime = jungleRate > 1.0E-6 ? Math.min(120, JUNGLE_HEALTH / jungleRate) : 120;
        double knock = Math.max(row.get("knock30"), row.get("knockU"));
        double probeDps = Math.max(0.1, row.get("probe") / 0.5);
        return new Metrics(row.id(), level, dps, burst, cc, hard, survival, invuln, mobility, row.get("range"),
                support, aoeExtra, tower, waveTime, jungleTime, knock, Math.max(0, flat) / 30, prevented / 30, probeDps,
                health(level));
    }

    /** Seconds {@code a} needs to kill {@code b} in the duel model. */
    static double killTime(Metrics a, Metrics b) {
        double controlled = Math.min(MAX_CC_SHARE, b.cc() / 30);
        double gap = Math.max(0, b.range() - a.range());
        double delay = Math.max(0, Math.min(3, (gap - a.mobility() / 10) / CLOSE_SPEED));
        // b's sustain: flat heals/shields, and damage prevented in proportion to what a really deals
        double rate = a.dps() * (1 - controlled);
        double prevented = Math.min(0.9 * rate, b.prevented() * rate / b.probeDps());
        double s = b.sustainFlat() + prevented;
        double k = 1 - controlled;
        double h = b.health();
        // the first 3 s of fighting at the burst's pace, then the sustained pace
        double r1 = k * Math.max(a.dps(), a.burst3() / 3);
        if (r1 > s) {
            double t = (h + r1 * delay) / (r1 - s);
            if (t <= delay + 3) {
                return Math.min(DUEL_MAX, Math.max(0.05, t));
            }
        }
        double opener = k * Math.max(3 * a.dps(), a.burst3());
        double r2 = k * a.dps();
        if (r2 > s) {
            double t = (h - opener + r2 * (delay + 3)) / (r2 - s);
            return Math.min(DUEL_MAX, Math.max(delay + 3, t));
        }
        return DUEL_MAX;
    }

    static double winChance(Metrics a, Metrics b) {
        double ab = killTime(a, b);
        double ba = killTime(b, a);
        double x = Math.pow(ba, DUEL_EXPONENT);
        double y = Math.pow(ab, DUEL_EXPONENT);
        return x / (x + y);
    }

    static Result evaluate(SiegeLabData.Table table, List<String> ids, Map<String, Correction> corrections,
                           Map<String, Double> real) {
        Map<String, Map<Integer, LevelScore>> perLevel = new LinkedHashMap<>();
        Map<Integer, double[][]> winrates = new LinkedHashMap<>();
        for (int level : SiegeLabData.LEVELS) {
            List<Metrics> metrics = new ArrayList<>();
            for (String id : ids) {
                SiegeLabData.Row row = table.row(id, level);
                metrics.add(metrics(row, corrections.getOrDefault(id, Correction.NONE), real.getOrDefault(id, 1.0)));
            }
            int n = metrics.size();
            double[][] win = new double[n][n];
            double[] combat = new double[n];
            for (int i = 0; i < n; i++) {
                double sum = 0;
                for (int j = 0; j < n; j++) {
                    win[i][j] = i == j ? 0.5 : winChance(metrics.get(i), metrics.get(j));
                    if (i != j) {
                        sum += win[i][j];
                    }
                }
                combat[i] = sum / (n - 1);
            }
            winrates.put(level, win);
            double[] tower = metrics.stream().mapToDouble(Metrics::tower).toArray();
            double[] wave = metrics.stream().mapToDouble(m -> 1 / m.waveTime()).toArray();
            double[] jungle = metrics.stream().mapToDouble(m -> 1 / m.jungleTime()).toArray();
            double[] aoe = metrics.stream().mapToDouble(Metrics::aoeExtra).toArray();
            double[] cc = metrics.stream().mapToDouble(Metrics::cc).toArray();
            double[] sup = metrics.stream().mapToDouble(Metrics::support).toArray();
            double[] dps = metrics.stream().mapToDouble(Metrics::dps).toArray();
            double[] burst = metrics.stream().mapToDouble(Metrics::burst3).toArray();
            double[] surv = metrics.stream().mapToDouble(Metrics::survival).toArray();
            double[] mob = metrics.stream().mapToDouble(Metrics::mobility).toArray();
            double[] range = metrics.stream().mapToDouble(Metrics::range).toArray();
            double meanCombat = mean(combat);
            for (int i = 0; i < n; i++) {
                Metrics m = metrics.get(i);
                Map<String, Double> norm = new LinkedHashMap<>();
                norm.put("dps", rel(dps, i));
                norm.put("burst", rel(burst, i));
                norm.put("cc", rel(cc, i));
                norm.put("surv", rel(surv, i));
                norm.put("mob", rel(mob, i));
                norm.put("range", rel(range, i));
                norm.put("sup", rel(sup, i));
                norm.put("aoe", rel(aoe, i));
                norm.put("tower", rel(tower, i));
                norm.put("wave", rel(wave, i));
                norm.put("jungle", rel(jungle, i));
                double combatN = combat[i] / meanCombat;
                double siege = 0.5 * norm.get("tower") + 0.3 * norm.get("wave") + 0.2 * norm.get("jungle");
                double team = 0.30 * norm.get("aoe") + 0.30 * norm.get("cc") + 0.40 * norm.get("sup");
                double total = 100 * (W_COMBAT * combatN + W_SIEGE * siege + W_TEAM * team);
                int outliers = 0;
                for (int j = 0; j < n; j++) {
                    if (j != i && (win[i][j] < 0.35 || win[i][j] > 0.65)) {
                        outliers++;
                    }
                }
                perLevel.computeIfAbsent(m.id(), id -> new LinkedHashMap<>()).put(level,
                        new LevelScore(m, combat[i], siege, team, total, norm, outliers / (double) (n - 1)));
            }
        }
        Map<String, Score> scores = new LinkedHashMap<>();
        for (String id : ids) {
            Map<Integer, LevelScore> levels = perLevel.get(id);
            double total = levels.values().stream().mapToDouble(LevelScore::total).average().orElse(0);
            double combat = levels.values().stream().mapToDouble(LevelScore::combat).average().orElse(0);
            double siege = levels.values().stream().mapToDouble(LevelScore::siege).average().orElse(0);
            double team = levels.values().stream().mapToDouble(LevelScore::team).average().orElse(0);
            double outliers = levels.values().stream().mapToDouble(LevelScore::winrateOutliers).average().orElse(0);
            Map<String, Double> norm = new LinkedHashMap<>();
            for (String key : levels.get(1).normalized().keySet()) {
                norm.put(key, levels.values().stream().mapToDouble(s -> s.normalized().get(key)).average().orElse(0));
            }
            Role role = SiegeRoles.of(id);
            scores.put(id, new Score(id, role, total, combat, siege, team, levels, norm, outliers,
                    bandProblems(role, norm)));
        }
        double median = median(scores.values().stream().mapToDouble(Score::total).toArray());
        return new Result(scores, median, winrates, ids);
    }

    /** A role's band: the normalized metrics (1 = the pool's average) it must reach or stay under. */
    record Band(String metric, double min, double max, String text) {
    }

    static List<Band> bands(@Nullable Role role) {
        if (role == null) {
            return List.of();
        }
        return switch (role) {
            case TANK -> List.of(new Band("defence", 1.6, 99, "생존+통제 ≥ 1.6"), new Band("dps", 0, 1.0, "지속딜 ≤ 1.0"));
            case FIGHTER -> List.of(new Band("dps", 0.45, 1.6, "지속딜 0.45~1.6"), new Band("burst", 0.3, 99, "폭딜 ≥ 0.3"));
            case ASSASSIN -> List.of(new Band("burst", 1.0, 99, "폭딜 ≥ 1.0"), new Band("surv", 0, 1.5, "생존 ≤ 1.5"));
            case MARKSMAN -> List.of(new Band("dps", 0.8, 99, "지속딜 ≥ 0.8"), new Band("range", 1.0, 99, "사거리 ≥ 1.0"),
                    new Band("surv", 0, 1.5, "생존 ≤ 1.5"));
            case MAGE -> List.of(new Band("magic", 0.9, 99, "광역+통제 ≥ 0.9"), new Band("range", 0.7, 99, "사거리 ≥ 0.7"));
            case SUPPORT -> List.of(new Band("help", 1.2, 99, "지원+통제 ≥ 1.2"), new Band("dps", 0, 1.0, "지속딜 ≤ 1.0"));
        };
    }

    static double bandValue(String metric, Map<String, Double> norm) {
        return switch (metric) {
            case "defence" -> norm.get("surv") + norm.get("cc");
            case "magic" -> Math.max(norm.get("aoe"), norm.get("cc"));
            case "help" -> norm.get("sup") + norm.get("cc");
            default -> norm.get(metric);
        };
    }

    static List<String> bandProblems(@Nullable Role role, Map<String, Double> norm) {
        List<String> out = new ArrayList<>();
        for (Band band : bands(role)) {
            double value = bandValue(band.metric(), norm);
            if (value < band.min() - 1.0E-9 || value > band.max() + 1.0E-9) {
                out.add(band.text() + " (" + SiegeBalanceReport.num(value) + ")");
            }
        }
        return out;
    }

    /** The value against the pool's mean (1 = average), clipped to {@link #CLIP}. */
    static double rel(double[] values, int i) {
        double mean = mean(values);
        return mean < 1.0E-9 ? 0 : Math.min(CLIP, values[i] / mean);
    }

    static double mean(double[] values) {
        return Arrays.stream(values).average().orElse(0);
    }

    static double median(double[] values) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        if (sorted.length == 0) {
            return 0;
        }
        int mid = sorted.length / 2;
        return sorted.length % 2 == 1 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2;
    }
}
