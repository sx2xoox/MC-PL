package io.github.sx2xoox.mcpl.game.siege;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.bukkit.configuration.ConfigurationSection;

/**
 * The {@code games.siege.*} settings of {@code config.yml} (see docs/siege.md), read once per match. Durations are
 * converted to ticks; points are whole numbers; tower health is in health points.
 */
public record SiegeSettings(
        int roundsToWin,
        int winScore,
        int halfTicks,
        int roundPrepTicks,
        int halfPrepTicks,
        int halftimeTicks,
        int roundBreakTicks,
        int suddenDeathTicks,
        int rerolls,
        List<String> abilityPool,
        int killPoints,
        int bountyPoints,
        int bountyStreak,
        int assistPoints,
        int assistTicks,
        int frontPoints,
        int middlePoints,
        int corePoints,
        int defencePoints,
        int defenceTicks,
        double defenceHeal,
        int climaxTicks,
        double climaxTowerMultiplier,
        int climaxRespawnCutTicks,
        int respawnTicks,
        int respawnDeathsPerStep,
        int respawnStepTicks,
        int respawnMaxTicks,
        int protectionTicks,
        double spawnZoneRadius,
        double spawnZoneDamage,
        double frontHealth,
        double middleHealth,
        double coreHealth,
        double healthBaseShare,
        double healthPerAttacker,
        double defenderReduction,
        double maxDefenderReduction,
        int regenDelayTicks,
        double regenPerSecond,
        int warningCooldownTicks,
        double hitboxScale) {

    public static final String ROUNDS_TO_WIN = "rounds-to-win";
    public static final String WIN_SCORE = "win-score";
    public static final String HALF_SECONDS = "half-seconds";
    public static final String ROUND_PREP_SECONDS = "round-prep-seconds";
    public static final String HALF_PREP_SECONDS = "half-prep-seconds";
    public static final String HALFTIME_SECONDS = "halftime-seconds";
    public static final String ROUND_BREAK_SECONDS = "round-break-seconds";
    public static final String SUDDEN_DEATH_SECONDS = "sudden-death-seconds";
    public static final String REROLLS = "rerolls";
    public static final String ABILITY_POOL = "ability-pool";
    public static final String KILL = "points.kill";
    public static final String BOUNTY = "points.bounty";
    public static final String BOUNTY_STREAK = "points.bounty-streak";
    public static final String ASSIST = "points.assist";
    public static final String ASSIST_SECONDS = "points.assist-seconds";
    public static final String FRONT_POINTS = "points.front-tower";
    public static final String MIDDLE_POINTS = "points.middle-tower";
    public static final String CORE_POINTS = "points.core";
    public static final String DEFENCE = "points.defence";
    public static final String DEFENCE_SECONDS = "points.defence-seconds";
    public static final String DEFENCE_HEAL = "points.defence-heal";
    public static final String CLIMAX_SECONDS = "climax.seconds";
    public static final String CLIMAX_MULTIPLIER = "climax.tower-points-multiplier";
    public static final String CLIMAX_RESPAWN_CUT = "climax.respawn-cut-seconds";
    public static final String RESPAWN_SECONDS = "respawn.seconds";
    public static final String RESPAWN_DEATHS_PER_STEP = "respawn.deaths-per-step";
    public static final String RESPAWN_STEP_SECONDS = "respawn.step-seconds";
    public static final String RESPAWN_MAX_SECONDS = "respawn.max-seconds";
    public static final String PROTECTION_SECONDS = "respawn.protection-seconds";
    public static final String SPAWN_ZONE_RADIUS = "respawn.spawn-zone-radius";
    public static final String SPAWN_ZONE_DAMAGE = "respawn.spawn-zone-damage";
    public static final String FRONT_HEALTH = "towers.front-health";
    public static final String MIDDLE_HEALTH = "towers.middle-health";
    public static final String CORE_HEALTH = "towers.core-health";
    public static final String HEALTH_BASE_SHARE = "towers.health-base-share";
    public static final String HEALTH_PER_ATTACKER = "towers.health-per-attacker";
    public static final String DEFENDER_REDUCTION = "towers.defender-reduction";
    public static final String MAX_DEFENDER_REDUCTION = "towers.max-defender-reduction";
    public static final String REGEN_DELAY = "towers.regen-delay-seconds";
    public static final String REGEN_PER_SECOND = "towers.regen-per-second";
    public static final String WARNING_COOLDOWN = "towers.warning-cooldown-seconds";
    public static final String HITBOX_SCALE = "towers.hitbox-scale";

    public static final int MAX_SECONDS = 3600;
    /** The default ability pool (경찰, 광부, 대장장이, 총잡이, 요리사); owners add more with {@code /게임 공성전 능력풀}. */
    public static final List<String> DEFAULT_POOL = List.of("police", "miner", "blacksmith", "gunslinger", "chef");

    /** Reads the live section; values out of range are clamped, missing ones use the defaults. */
    public static SiegeSettings read(ConfigurationSection c) {
        List<String> pool = new ArrayList<>();
        for (String id : c.getStringList(ABILITY_POOL)) {
            String trimmed = id.trim().toLowerCase(Locale.ROOT);
            if (!trimmed.isEmpty() && !pool.contains(trimmed)) {
                pool.add(trimmed);
            }
        }
        return new SiegeSettings(
                clamp(c.getInt(ROUNDS_TO_WIN, 2), 1, 5),
                clamp(c.getInt(WIN_SCORE, 100), 1, 10_000),
                ticks(c.getDouble(HALF_SECONDS, 720), 30),
                ticks(c.getDouble(ROUND_PREP_SECONDS, 20), 0),
                ticks(c.getDouble(HALF_PREP_SECONDS, 5), 0),
                ticks(c.getDouble(HALFTIME_SECONDS, 30), 0),
                ticks(c.getDouble(ROUND_BREAK_SECONDS, 60), 0),
                ticks(c.getDouble(SUDDEN_DEATH_SECONDS, 120), 10),
                clamp(c.getInt(REROLLS, 1), 0, 10),
                List.copyOf(pool),
                points(c, KILL, 2),
                points(c, BOUNTY, 5),
                clamp(c.getInt(BOUNTY_STREAK, 5), 1, 100),
                points(c, ASSIST, 1),
                ticks(c.getDouble(ASSIST_SECONDS, 8), 0),
                points(c, FRONT_POINTS, 15),
                points(c, MIDDLE_POINTS, 20),
                points(c, CORE_POINTS, 30),
                points(c, DEFENCE, 5),
                ticks(c.getDouble(DEFENCE_SECONDS, 240), 10),
                fraction(c.getDouble(DEFENCE_HEAL, 0.25)),
                ticks(c.getDouble(CLIMAX_SECONDS, 180), 0),
                Math.max(1.0, finite(c.getDouble(CLIMAX_MULTIPLIER, 1.5), 1.5)),
                ticks(c.getDouble(CLIMAX_RESPAWN_CUT, 2), 0),
                ticks(c.getDouble(RESPAWN_SECONDS, 6), 0),
                clamp(c.getInt(RESPAWN_DEATHS_PER_STEP, 3), 1, 100),
                ticks(c.getDouble(RESPAWN_STEP_SECONDS, 1), 0),
                ticks(c.getDouble(RESPAWN_MAX_SECONDS, 12), 0),
                ticks(c.getDouble(PROTECTION_SECONDS, 3), 0),
                Math.max(0, finite(c.getDouble(SPAWN_ZONE_RADIUS, 6), 6)),
                Math.max(0, finite(c.getDouble(SPAWN_ZONE_DAMAGE, 4), 4)),
                health(c.getDouble(FRONT_HEALTH, 1200)),
                health(c.getDouble(MIDDLE_HEALTH, 1500)),
                health(c.getDouble(CORE_HEALTH, 1800)),
                Math.max(0, Math.min(10, finite(c.getDouble(HEALTH_BASE_SHARE, 0.25), 0.25))),
                Math.max(0, Math.min(10, finite(c.getDouble(HEALTH_PER_ATTACKER, 0.25), 0.25))),
                fraction(c.getDouble(DEFENDER_REDUCTION, 0.2)),
                fraction(c.getDouble(MAX_DEFENDER_REDUCTION, 0.4)),
                ticks(c.getDouble(REGEN_DELAY, 10), 0),
                fraction(c.getDouble(REGEN_PER_SECOND, 0.01)),
                ticks(c.getDouble(WARNING_COOLDOWN, 3), 0),
                Math.max(0.5, Math.min(10.0, finite(c.getDouble(HITBOX_SCALE, 2.5), 2.5))));
    }

    /** Points for destroying the tower {@code towerId} ({@code front}, {@code middle}, {@code core}). */
    public int towerPoints(String towerId) {
        return switch (towerId) {
            case "front" -> frontPoints;
            case "middle" -> middlePoints;
            default -> corePoints;
        };
    }

    /** Configured health of the tower {@code towerId} (the health against three attackers). */
    public double towerHealth(String towerId) {
        return switch (towerId) {
            case "front" -> frontHealth;
            case "middle" -> middleHealth;
            default -> coreHealth;
        };
    }

    /**
     * Health of the tower {@code towerId} against {@code attackers} attackers: the configured health times
     * {@code health-base-share + health-per-attacker × attackers} (1.0 for three attackers with the defaults), at
     * least one health point.
     */
    public double towerHealth(String towerId, int attackers) {
        double factor = healthBaseShare + healthPerAttacker * Math.max(1, attackers);
        return Math.max(1, towerHealth(towerId) * factor);
    }

    /**
     * Respawn delay of a player who died {@code deaths} times this half (this death included), during the 총공세 or
     * not: base + one step per {@link #respawnDeathsPerStep} deaths, capped, minus the 총공세 cut.
     */
    public int respawnDelay(int deaths, boolean climax) {
        int steps = Math.max(0, deaths) / respawnDeathsPerStep;
        int delay = Math.min(Math.max(respawnTicks, respawnMaxTicks), respawnTicks + steps * respawnStepTicks);
        if (climax) {
            delay -= climaxRespawnCutTicks;
        }
        return Math.max(20, delay);
    }

    /** Declares the defaults (with Korean comments) merged into {@code config.yml} → {@code games.siege}. */
    public static void declareDefaults(ConfigurationSection d) {
        d.set(ROUNDS_TO_WIN, 2);
        d.setComments(ROUNDS_TO_WIN, List.of("이 판 수를 먼저 이긴 팀이 우승 (2 = 3판 2선승)"));
        d.set(WIN_SCORE, 100);
        d.setComments(WIN_SCORE, List.of("한 판에서 전반+후반 합계로 이 점수를 먼저 낸 팀이 바로 판 승리"));
        d.set(HALF_SECONDS, 720);
        d.setComments(HALF_SECONDS, List.of("전반·후반 길이(초)"));
        d.set(ROUND_PREP_SECONDS, 20);
        d.setComments(ROUND_PREP_SECONDS, List.of("판 시작 준비 시간(초): 능력 공개, 리롤(웅크리고 우클릭). 무적"));
        d.set(HALF_PREP_SECONDS, 5);
        d.setComments(HALF_PREP_SECONDS, List.of("후반 시작 전 준비 시간(초). 무적"));
        d.set(HALFTIME_SECONDS, 30);
        d.setComments(HALFTIME_SECONDS, List.of("하프타임(초): 공수 교대, 체력·쿨타임·게이지 초기화, 타워 복구"));
        d.set(ROUND_BREAK_SECONDS, 60);
        d.setComments(ROUND_BREAK_SECONDS, List.of("판 사이 쉬는 시간(초)"));
        d.set(SUDDEN_DEATH_SECONDS, 120);
        d.setComments(SUDDEN_DEATH_SECONDS, List.of(
                "후반이 끝났을 때 점수·타워·킬이 모두 같으면 연장전(초): 먼저 점수를 낸 팀이 승리, 아무도 못 내면 수비팀 승리"));
        d.set(REROLLS, 1);
        d.setComments(REROLLS, List.of("판마다 한 사람이 능력을 다시 뽑을 수 있는 횟수 (판 준비 시간에 웅크리고 우클릭)"));
        d.set(ABILITY_POOL, DEFAULT_POOL);
        d.setComments(ABILITY_POOL, List.of(
                "공성전에서 나오는 능력 id 목록. 비어 있으면 abilities.yml 에서 켜진 모든 능력",
                "/게임 공성전 능력풀 <추가|제거|목록|초기화> [능력] 로도 바꿀 수 있습니다."));
        d.setComments("points", List.of("점수 (한 판 동안 전반+후반 합산)"));
        d.set(KILL, 2);
        d.setComments(KILL, List.of("적 처치"));
        d.set(BOUNTY, 5);
        d.setComments(BOUNTY, List.of("현상금: 연속 처치 중인 적을 처치 (처치 점수 대신)"));
        d.set(BOUNTY_STREAK, 5);
        d.setComments(BOUNTY_STREAK, List.of("현상금이 붙는 연속 처치 수"));
        d.set(ASSIST, 1);
        d.setComments(ASSIST, List.of("도움: 처치 직전 피해를 준 다른 팀원마다"));
        d.set(ASSIST_SECONDS, 8);
        d.setComments(ASSIST_SECONDS, List.of("도움으로 치는 피해의 시간(초)"));
        d.set(FRONT_POINTS, 15);
        d.set(MIDDLE_POINTS, 20);
        d.set(CORE_POINTS, 30);
        d.setComments(FRONT_POINTS, List.of("타워 파괴 (공격팀). 코어가 부서지면 그 반은 바로 끝납니다"));
        d.set(DEFENCE, 5);
        d.setComments(DEFENCE, List.of("방어 성공: 지금 공격 가능한 타워가 아래 시간 동안 버틸 때마다 (수비팀)"));
        d.set(DEFENCE_SECONDS, 240);
        d.set(DEFENCE_HEAL, 0.25);
        d.setComments(DEFENCE_HEAL, List.of("방어 성공 때 타워가 회복하는 최대 체력 비율 (0.25 = 25%)"));
        d.setComments("climax", List.of("총공세: 각 반의 마지막 몇 분"));
        d.set(CLIMAX_SECONDS, 180);
        d.set(CLIMAX_MULTIPLIER, 1.5);
        d.setComments(CLIMAX_MULTIPLIER, List.of("총공세 중 타워 파괴 점수 배율"));
        d.set(CLIMAX_RESPAWN_CUT, 2);
        d.setComments(CLIMAX_RESPAWN_CUT, List.of("총공세 중 양 팀 부활 대기 시간 감소(초)"));
        d.setComments("respawn", List.of("부활"));
        d.set(RESPAWN_SECONDS, 6);
        d.setComments(RESPAWN_SECONDS, List.of("기본 부활 대기 시간(초)"));
        d.set(RESPAWN_DEATHS_PER_STEP, 3);
        d.set(RESPAWN_STEP_SECONDS, 1);
        d.setComments(RESPAWN_DEATHS_PER_STEP, List.of("그 반에서 이만큼 죽을 때마다 부활 대기 +step-seconds초 (최대 max-seconds)"));
        d.set(RESPAWN_MAX_SECONDS, 12);
        d.set(PROTECTION_SECONDS, 3);
        d.setComments(PROTECTION_SECONDS, List.of("부활 직후 무적(초). 공격하면 풀립니다"));
        d.set(SPAWN_ZONE_RADIUS, 6);
        d.set(SPAWN_ZONE_DAMAGE, 4);
        d.setComments(SPAWN_ZONE_RADIUS, List.of("적 부활 지점 반경(블록) 안에 들어온 적은 1초마다 spawn-zone-damage 피해"));
        d.setComments("towers", List.of("타워"));
        d.set(FRONT_HEALTH, 1200);
        d.set(MIDDLE_HEALTH, 1500);
        d.set(CORE_HEALTH, 1800);
        d.setComments(FRONT_HEALTH, List.of("타워 체력 (전방·중앙·코어, 공격팀 3명 기준)"));
        d.set(HEALTH_BASE_SHARE, 0.25);
        d.set(HEALTH_PER_ATTACKER, 0.25);
        d.setComments(HEALTH_BASE_SHARE, List.of(
                "공격팀 인원에 따른 타워 체력 배율 = health-base-share + health-per-attacker × 공격팀 인원",
                "(기본값: 1명 0.5배, 3명 1배, 5명 1.5배, 8명 2.25배)"));
        d.set(DEFENDER_REDUCTION, 0.2);
        d.set(MAX_DEFENDER_REDUCTION, 0.4);
        d.setComments(DEFENDER_REDUCTION, List.of("타워 반경 안의 수비팀 1명당 타워가 받는 피해 감소 (최대 max-defender-reduction)"));
        d.set(REGEN_DELAY, 10);
        d.set(REGEN_PER_SECOND, 0.01);
        d.setComments(REGEN_DELAY, List.of(
                "피해를 받지 않고 이 시간(초)이 지나면 초당 regen-per-second(최대 체력 비율)씩 회복",
                "공격팀이 타워 반경 안에 있으면 회복하지 않습니다"));
        d.set(WARNING_COOLDOWN, 3);
        d.setComments(WARNING_COOLDOWN, List.of("타워 피격 경고(소리·액션바) 간격(초)"));
        d.set(HITBOX_SCALE, 2.5);
        d.setComments(HITBOX_SCALE, List.of("타워 피격 판정 크기 배율 (0.5~10). 3D 타워는 타워 크기에 맞춰 이 값을 더 키워 씁니다"));
        SiegeHudSettings.declareDefaults(d);
    }

    private static int points(ConfigurationSection c, String key, int fallback) {
        return clamp(c.getInt(key, fallback), 0, 10_000);
    }

    private static int ticks(double seconds, double min) {
        double clamped = Math.max(min, Math.min(MAX_SECONDS, finite(seconds, min)));
        return (int) Math.round(clamped * 20);
    }

    private static double health(double value) {
        return Math.max(1, Math.min(1_000_000, finite(value, 1000)));
    }

    private static double fraction(double value) {
        return Math.max(0, Math.min(1, finite(value, 0)));
    }

    private static double finite(double value, double fallback) {
        return Double.isFinite(value) ? value : fallback;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
