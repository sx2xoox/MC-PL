package io.github.sx2xoox.mcpl.game.siege;

import java.util.List;

import org.bukkit.configuration.ConfigurationSection;

/**
 * The in-match levels and experience of 공성전 ({@code games.siege.levels.*}, {@code xp.*}; docs/siege.md "레벨"), read
 * once per match next to {@link SiegeSettings}. Levels never lock any part of the kit (v2): they only add maximum
 * health here; ability damage scaling and the 3/6/9 perks per level are the balance's
 * ({@link io.github.sx2xoox.mcpl.game.siege.balance.SiegeBalance}).
 */
public record SiegeProgressSettings(
        boolean levelsEnabled,
        int maxLevel,
        int xpFirst,
        int xpStep,
        int healthEveryLevels,
        double healthPerStep,
        int xpPassivePerSecond,
        int xpKill,
        int xpAssist,
        int xpTowerDestroyed) {

    public static final String LEVELS_ENABLED = "levels.enabled";
    public static final String MAX_LEVEL = "levels.max";
    public static final String XP_FIRST = "levels.xp-first";
    public static final String XP_STEP = "levels.xp-step";
    public static final String HEALTH_EVERY = "levels.health-every-levels";
    public static final String HEALTH_PER_STEP = "levels.health-per-step";
    public static final String XP_PASSIVE = "xp.passive-per-second";
    public static final String XP_KILL = "xp.kill";
    public static final String XP_ASSIST = "xp.assist";
    public static final String XP_TOWER = "xp.tower-destroyed";
    /** Experience from level 1 to 2 and the more each further level needs (level 9 at about 15 minutes). */
    public static final int DEFAULT_XP_FIRST = 70;
    public static final int DEFAULT_XP_STEP = 65;

    /** Reads the live section; values out of range are clamped, missing ones use the defaults. */
    public static SiegeProgressSettings read(ConfigurationSection c) {
        int max = clamp(c.getInt(MAX_LEVEL, 9), 1, 30);
        return new SiegeProgressSettings(
                c.getBoolean(LEVELS_ENABLED, true),
                max,
                clamp(c.getInt(XP_FIRST, DEFAULT_XP_FIRST), 1, 100_000),
                clamp(c.getInt(XP_STEP, DEFAULT_XP_STEP), 0, 100_000),
                clamp(c.getInt(HEALTH_EVERY, 2), 1, 30),
                Math.max(0, Math.min(20, finite(c.getDouble(HEALTH_PER_STEP, 2), 2))),
                xp(c, XP_PASSIVE, 1),
                xp(c, XP_KILL, 30),
                xp(c, XP_ASSIST, 15),
                xp(c, XP_TOWER, 150));
    }

    /** Experience from {@code level} to the next one: {@code xp-first + xp-step × (level − 1)}. */
    public int xpToNext(int level) {
        return xpFirst + xpStep * Math.max(0, level - 1);
    }

    /** Total experience needed to reach {@code level} from level 1. */
    public int xpForLevel(int level) {
        int total = 0;
        for (int l = 1; l < Math.min(level, maxLevel); l++) {
            total += xpToNext(l);
        }
        return total;
    }

    /** The level a player with {@code xp} total experience has (1 … {@link #maxLevel}). */
    public int levelFor(int xp) {
        int level = 1;
        int need = 0;
        while (level < maxLevel) {
            need += xpToNext(level);
            if (xp < need) {
                break;
            }
            level++;
        }
        return level;
    }

    /** Extra maximum health at {@code level}: {@code health-per-step} for every {@code health-every-levels} levels. */
    public double bonusHealth(int level) {
        return levelsEnabled ? (Math.max(1, level) / healthEveryLevels) * healthPerStep : 0;
    }

    /** Declares the defaults (with Korean comments) merged into {@code config.yml} → {@code games.siege}. */
    public static void declareDefaults(ConfigurationSection d) {
        d.setComments("levels", List.of("경기 중 레벨 (라운드마다 1레벨부터). 레벨은 능력을 잠그지 않고 강화만 합니다"));
        d.set(LEVELS_ENABLED, true);
        d.setComments(LEVELS_ENABLED, List.of("false 면 레벨 없이 모두 최고 레벨로 시작 (체력 보너스 없음)"));
        d.set(MAX_LEVEL, 9);
        d.setComments(MAX_LEVEL, List.of("최대 레벨"));
        d.set(XP_FIRST, DEFAULT_XP_FIRST);
        d.set(XP_STEP, DEFAULT_XP_STEP);
        d.setComments(XP_FIRST, List.of(
                "레벨업에 필요한 경험치: 1→2 레벨은 xp-first, 그다음부터 레벨마다 xp-step 씩 더 필요",
                "(기본값: 70, 135, 200, 265 … 9레벨까지 누적 2380, 평균 약 15분)"));
        d.set(HEALTH_EVERY, 2);
        d.set(HEALTH_PER_STEP, 2);
        d.setComments(HEALTH_EVERY, List.of("이 레벨마다 최대 체력 +health-per-step (2 = 하트 1칸)"));
        d.setComments("xp", List.of("경험치 (팀 단위 보상은 그 팀 모두가 받음). 미니언·정글·드래곤 경험치는 그 기능의 설정에 있습니다"));
        d.set(XP_PASSIVE, 1);
        d.setComments(XP_PASSIVE, List.of("싸우는 동안 1초마다 모두가 받는 경험치 (뒤처진 사람도 따라오도록)"));
        d.set(XP_KILL, 30);
        d.set(XP_ASSIST, 15);
        d.setComments(XP_KILL, List.of("적 처치 / 도움 (본인만)"));
        d.set(XP_TOWER, 150);
        d.setComments(XP_TOWER, List.of("타워 파괴: 부순 팀 모두"));
    }

    private static int xp(ConfigurationSection c, String key, int fallback) {
        return clamp(c.getInt(key, fallback), 0, 100_000);
    }

    private static double finite(double value, double fallback) {
        return Double.isFinite(value) ? value : fallback;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
