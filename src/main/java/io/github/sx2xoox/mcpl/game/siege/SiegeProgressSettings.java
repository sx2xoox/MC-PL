package io.github.sx2xoox.mcpl.game.siege;

import java.util.List;

import org.bukkit.configuration.ConfigurationSection;

/**
 * The in-match levels, experience and neutral objectives of 공성전 ({@code games.siege.levels.*}, {@code xp.*},
 * {@code objectives.*}; docs/siege.md §12–13), read once per match next to {@link SiegeSettings}. Durations are in
 * ticks, experience and health in whole numbers / health points.
 */
public record SiegeProgressSettings(
        boolean levelsEnabled,
        int maxLevel,
        int xpFirst,
        int xpStep,
        int activeLevel,
        int ultimateLevel,
        int healthEveryLevels,
        double healthPerStep,
        int xpPassivePerSecond,
        int xpKill,
        int xpAssist,
        int xpTowerDestroyed,
        int xpDefence,
        int xpNode,
        int xpMajor,
        int nodeCaptureTicks,
        int nodeRespawnTicks,
        int nodeBuffTicks,
        int majorFirstTicks,
        int majorIntervalTicks,
        int majorWarningTicks,
        int majorCaptureTicks,
        int majorBuffTicks) {

    public static final String LEVELS_ENABLED = "levels.enabled";
    public static final String MAX_LEVEL = "levels.max";
    public static final String XP_FIRST = "levels.xp-first";
    public static final String XP_STEP = "levels.xp-step";
    public static final String ACTIVE_LEVEL = "levels.active-level";
    public static final String ULTIMATE_LEVEL = "levels.ultimate-level";
    public static final String HEALTH_EVERY = "levels.health-every-levels";
    public static final String HEALTH_PER_STEP = "levels.health-per-step";
    public static final String XP_PASSIVE = "xp.passive-per-second";
    public static final String XP_KILL = "xp.kill";
    public static final String XP_ASSIST = "xp.assist";
    public static final String XP_TOWER = "xp.tower-destroyed";
    public static final String XP_DEFENCE = "xp.defence";
    public static final String XP_NODE = "xp.data-node";
    public static final String XP_MAJOR = "xp.overclock-core";
    public static final String NODE_CAPTURE = "objectives.node-capture-seconds";
    public static final String NODE_RESPAWN = "objectives.node-respawn-seconds";
    public static final String NODE_BUFF = "objectives.node-buff-seconds";
    public static final String MAJOR_FIRST = "objectives.core-first-seconds";
    public static final String MAJOR_INTERVAL = "objectives.core-interval-seconds";
    public static final String MAJOR_WARNING = "objectives.core-warning-seconds";
    public static final String MAJOR_CAPTURE = "objectives.core-capture-seconds";
    public static final String MAJOR_BUFF = "objectives.core-buff-seconds";

    /** Reads the live section; values out of range are clamped, missing ones use the defaults. */
    public static SiegeProgressSettings read(ConfigurationSection c) {
        int max = clamp(c.getInt(MAX_LEVEL, 9), 1, 30);
        return new SiegeProgressSettings(
                c.getBoolean(LEVELS_ENABLED, true),
                max,
                clamp(c.getInt(XP_FIRST, 60), 1, 100_000),
                clamp(c.getInt(XP_STEP, 40), 0, 100_000),
                clamp(c.getInt(ACTIVE_LEVEL, 2), 1, max),
                clamp(c.getInt(ULTIMATE_LEVEL, 3), 1, max),
                clamp(c.getInt(HEALTH_EVERY, 2), 1, 30),
                Math.max(0, Math.min(20, finite(c.getDouble(HEALTH_PER_STEP, 2), 2))),
                xp(c, XP_PASSIVE, 1),
                xp(c, XP_KILL, 30),
                xp(c, XP_ASSIST, 15),
                xp(c, XP_TOWER, 150),
                xp(c, XP_DEFENCE, 120),
                xp(c, XP_NODE, 100),
                xp(c, XP_MAJOR, 250),
                ticks(c.getDouble(NODE_CAPTURE, 8), 1),
                ticks(c.getDouble(NODE_RESPAWN, 90), 5),
                ticks(c.getDouble(NODE_BUFF, 30), 0),
                ticks(c.getDouble(MAJOR_FIRST, 180), 0),
                ticks(c.getDouble(MAJOR_INTERVAL, 240), 30),
                ticks(c.getDouble(MAJOR_WARNING, 30), 0),
                ticks(c.getDouble(MAJOR_CAPTURE, 15), 1),
                ticks(c.getDouble(MAJOR_BUFF, 60), 0));
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
        d.setComments("levels", List.of("경기 중 레벨 (반마다 1레벨부터). 레벨이 낮으면 능력 일부가 잠겨 있습니다"));
        d.set(LEVELS_ENABLED, true);
        d.setComments(LEVELS_ENABLED, List.of("false 면 레벨 없이 처음부터 능력 전부를 씁니다"));
        d.set(MAX_LEVEL, 9);
        d.setComments(MAX_LEVEL, List.of("최대 레벨"));
        d.set(XP_FIRST, 60);
        d.set(XP_STEP, 40);
        d.setComments(XP_FIRST, List.of(
                "레벨업에 필요한 경험치: 1→2 레벨은 xp-first, 그다음부터 레벨마다 xp-step 씩 더 필요",
                "(기본값: 60, 100, 140, 180 … 9레벨까지 누적 1600)"));
        d.set(ACTIVE_LEVEL, 2);
        d.setComments(ACTIVE_LEVEL, List.of("액티브(우클릭)를 쓸 수 있는 레벨. 패시브는 1레벨부터"));
        d.set(ULTIMATE_LEVEL, 3);
        d.setComments(ULTIMATE_LEVEL, List.of("궁극기 게이지가 차기 시작하고 궁극기(F)를 쓸 수 있는 레벨"));
        d.set(HEALTH_EVERY, 2);
        d.set(HEALTH_PER_STEP, 2);
        d.setComments(HEALTH_EVERY, List.of("이 레벨마다 최대 체력 +health-per-step (2 = 하트 1칸)"));
        d.setComments("xp", List.of("경험치 (팀 단위 보상은 그 팀 모두가 받음)"));
        d.set(XP_PASSIVE, 1);
        d.setComments(XP_PASSIVE, List.of("싸우는 동안 1초마다 모두가 받는 경험치 (뒤처진 사람도 따라오도록)"));
        d.set(XP_KILL, 30);
        d.set(XP_ASSIST, 15);
        d.setComments(XP_KILL, List.of("적 처치 / 도움 (본인만)"));
        d.set(XP_TOWER, 150);
        d.setComments(XP_TOWER, List.of("타워 파괴: 공격팀 모두"));
        d.set(XP_DEFENCE, 120);
        d.setComments(XP_DEFENCE, List.of("방어 성공: 수비팀 모두"));
        d.set(XP_NODE, 100);
        d.setComments(XP_NODE, List.of("데이터 노드 점령: 점령한 팀 모두"));
        d.set(XP_MAJOR, 250);
        d.setComments(XP_MAJOR, List.of("오버클럭 코어 점령: 점령한 팀 모두"));
        d.setComments("objectives", List.of("중립 오브젝트 (맵의 데이터 노드와 오버클럭 코어)"));
        d.set(NODE_CAPTURE, 8);
        d.setComments(NODE_CAPTURE, List.of("데이터 노드 점령에 걸리는 시간(초). 두 팀이 함께 있으면 멈춤"));
        d.set(NODE_RESPAWN, 90);
        d.setComments(NODE_RESPAWN, List.of("점령된 데이터 노드가 다시 열리기까지(초)"));
        d.set(NODE_BUFF, 30);
        d.setComments(NODE_BUFF, List.of("데이터 노드 점령 보상: 신속 I 시간(초)"));
        d.set(MAJOR_FIRST, 180);
        d.setComments(MAJOR_FIRST, List.of("반이 시작하고 오버클럭 코어가 처음 나타나기까지(초)"));
        d.set(MAJOR_INTERVAL, 240);
        d.setComments(MAJOR_INTERVAL, List.of("오버클럭 코어가 점령된 뒤 다시 나타나기까지(초)"));
        d.set(MAJOR_WARNING, 30);
        d.setComments(MAJOR_WARNING, List.of("오버클럭 코어가 나타나기 몇 초 전에 미리 알릴지"));
        d.set(MAJOR_CAPTURE, 15);
        d.setComments(MAJOR_CAPTURE, List.of("오버클럭 코어 점령에 걸리는 시간(초)"));
        d.set(MAJOR_BUFF, 60);
        d.setComments(MAJOR_BUFF, List.of("오버클럭 코어 점령 보상: 저항 I + 재생 I 시간(초)"));
    }

    private static int xp(ConfigurationSection c, String key, int fallback) {
        return clamp(c.getInt(key, fallback), 0, 100_000);
    }

    private static int ticks(double seconds, double min) {
        double clamped = Math.max(min, Math.min(SiegeSettings.MAX_SECONDS, finite(seconds, min)));
        return (int) Math.round(clamped * 20);
    }

    private static double finite(double value, double fallback) {
        return Double.isFinite(value) ? value : fallback;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
