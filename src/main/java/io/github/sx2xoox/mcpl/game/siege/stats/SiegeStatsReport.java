package io.github.sx2xoox.mcpl.game.siege.stats;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;

/**
 * The totals as a ranking (pure): one row per ability (or bot job) sorted by a {@link Sort}, the abilities with too few
 * rounds last, and a mark on those whose damage per minute is far from the median of the well sampled ones.
 */
public final class SiegeStatsReport {

    /** At least this many well sampled entries before anything is marked (a median of two says little). */
    public static final int MIN_FOR_MARKS = 3;

    /** What the ranking sorts by (highest first). */
    public enum Sort {
        DAMAGE("피해", "damage", StatTotals::enemyPerMinute),
        STRUCTURE("건물", "structure", StatTotals::structurePerMinute),
        WIN_RATE("승률", "winrate", StatTotals::winRate),
        KDA("KDA", "kda", StatTotals::kda);

        private final String korean;
        private final String english;
        private final ToDoubleFunction<StatTotals> value;

        Sort(String korean, String english, ToDoubleFunction<StatTotals> value) {
            this.korean = korean;
            this.english = english;
            this.value = value;
        }

        /** The command word. */
        public String korean() {
            return korean;
        }

        public String english() {
            return english;
        }

        /** The {@code stats.sort.<key>} message key part. */
        public String key() {
            return english;
        }

        double of(StatTotals totals) {
            return value.applyAsDouble(totals);
        }
    }

    /** Above or below the median by the configured share, or neither. */
    public enum Mark { HIGH, LOW, NONE }

    /** One ability's row. */
    public record Row(String id, String name, StatTotals totals, boolean enough, Mark mark) {
    }

    private SiegeStatsReport() {
    }

    /** The rows of {@code totals}, {@code names} giving each id its display name. */
    public static List<Row> rows(Map<String, StatTotals> totals, Function<String, String> names, Sort sort,
                                 SiegeStatsSettings settings) {
        List<Double> sampled = new ArrayList<>();
        totals.forEach((id, entry) -> {
            if (enough(entry, settings)) {
                sampled.add(entry.enemyPerMinute());
            }
        });
        double median = median(sampled);
        boolean marks = sampled.size() >= MIN_FOR_MARKS && median > 0;
        double share = settings.outlierPercent() / 100.0;
        List<Row> rows = new ArrayList<>();
        totals.forEach((id, entry) -> {
            boolean enough = enough(entry, settings);
            Mark mark = Mark.NONE;
            if (marks && enough) {
                double dpm = entry.enemyPerMinute();
                if (dpm >= median * (1 + share)) {
                    mark = Mark.HIGH;
                } else if (dpm <= median * (1 - share)) {
                    mark = Mark.LOW;
                }
            }
            rows.add(new Row(id, names.apply(id), entry, enough, mark));
        });
        rows.sort(Comparator.comparing((Row row) -> !row.enough())
                .thenComparing(Comparator.comparingDouble((Row row) -> sort.of(row.totals())).reversed())
                .thenComparing(Comparator.comparingInt((Row row) -> row.totals().rounds()).reversed())
                .thenComparing(Row::name));
        return rows;
    }

    static boolean enough(StatTotals entry, SiegeStatsSettings settings) {
        return entry.rounds() >= settings.minRounds() && entry.seconds() > 0;
    }

    static double median(List<Double> values) {
        if (values.isEmpty()) {
            return 0;
        }
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(null);
        int mid = sorted.size() / 2;
        return sorted.size() % 2 == 1 ? sorted.get(mid) : (sorted.get(mid - 1) + sorted.get(mid)) / 2.0;
    }
}
