package io.github.sx2xoox.mcpl.game.siege.stats;

import java.util.List;

import org.bukkit.configuration.ConfigurationSection;

/**
 * The {@code games.siege.stats.*} settings: the balance records of real matches ({@link SiegeStats}).
 *
 * @param enabled        record every match (nothing is ever shown in play; admins read them with a command)
 * @param minRounds      an ability needs this many rounds before its numbers count (fewer: shown in gray, not marked)
 * @param outlierPercent mark an ability whose damage per minute is this far (percent) from the median
 */
public record SiegeStatsSettings(boolean enabled, int minRounds, double outlierPercent) {

    public static final String ENABLED = "stats.enabled";
    public static final String MIN_ROUNDS = "stats.min-rounds";
    public static final String OUTLIER_PERCENT = "stats.outlier-percent";

    public static final SiegeStatsSettings DEFAULTS = new SiegeStatsSettings(true, 3, 40);

    public static SiegeStatsSettings read(ConfigurationSection c) {
        double percent = c.getDouble(OUTLIER_PERCENT, DEFAULTS.outlierPercent);
        return new SiegeStatsSettings(
                c.getBoolean(ENABLED, DEFAULTS.enabled),
                Math.max(1, c.getInt(MIN_ROUNDS, DEFAULTS.minRounds)),
                Double.isFinite(percent) ? Math.max(1, Math.min(1000, percent)) : DEFAULTS.outlierPercent);
    }

    /** Declares the defaults (with Korean comments) under {@code games.siege.stats}. */
    public static void declareDefaults(ConfigurationSection d) {
        d.setComments("stats", List.of("실전 밸런스 기록 (plugins/MCPL/stats/siege/; 경기 중에는 아무것도 보이지 않음,"
                + " 보기: /게임 공성전 통계)"));
        d.set(ENABLED, DEFAULTS.enabled);
        d.setComments(ENABLED, List.of("경기마다 능력별 피해·처치 등을 기록"));
        d.set(MIN_ROUNDS, DEFAULTS.minRounds);
        d.setComments(MIN_ROUNDS, List.of("이보다 적은 라운드에 나온 능력은 회색 (표본 부족, ▲▼ 없음)"));
        d.set(OUTLIER_PERCENT, DEFAULTS.outlierPercent);
        d.setComments(OUTLIER_PERCENT, List.of("분당 적 피해가 중앙값보다 이만큼(%) 높거나 낮으면 ▲/▼"));
    }
}
