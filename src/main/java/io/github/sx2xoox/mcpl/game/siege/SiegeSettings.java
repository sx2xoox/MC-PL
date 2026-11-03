package io.github.sx2xoox.mcpl.game.siege;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import io.github.sx2xoox.mcpl.game.siege.tower.Tower;
import org.bukkit.configuration.ConfigurationSection;

/**
 * The {@code games.siege.*} settings of {@code config.yml} (see docs/siege.md), read once per match. Durations are
 * converted to ticks; points are whole numbers; structure health is in health points.
 */
public record SiegeSettings(
        int roundsToWin,
        int roundTicks,
        int roundPrepTicks,
        int roundBreakTicks,
        int overtimeTicks,
        int climaxTicks,
        int rerolls,
        List<String> abilityPool,
        int killPoints,
        int assistPoints,
        int assistTicks,
        int towerPoints,
        int respawnTicks,
        int respawnDeathsPerStep,
        int respawnStepTicks,
        int respawnMaxTicks,
        int protectionTicks,
        double spawnZoneRadius,
        double spawnZoneDamage,
        double outerHealth,
        double innerHealth,
        double coreHealth,
        double healthBaseShare,
        double healthPerAttacker,
        int regenDelayTicks,
        double regenPerSecond,
        int warningCooldownTicks,
        double towerAttackDamage,
        int towerAttackTicks,
        double towerAttackRange,
        double hitboxScale) {

    public static final String ROUNDS_TO_WIN = "rounds-to-win";
    public static final String ROUND_SECONDS = "round-seconds";
    public static final String ROUND_PREP_SECONDS = "round-prep-seconds";
    public static final String ROUND_BREAK_SECONDS = "round-break-seconds";
    public static final String OVERTIME_SECONDS = "overtime-seconds";
    public static final String CLIMAX_SECONDS = "last-minutes-seconds";
    public static final String REROLLS = "rerolls";
    public static final String ABILITY_POOL = "ability-pool";
    public static final String KILL = "points.kill";
    public static final String ASSIST = "points.assist";
    public static final String ASSIST_SECONDS = "points.assist-seconds";
    public static final String TOWER_POINTS = "points.tower";
    public static final String RESPAWN_SECONDS = "respawn.seconds";
    public static final String RESPAWN_DEATHS_PER_STEP = "respawn.deaths-per-step";
    public static final String RESPAWN_STEP_SECONDS = "respawn.step-seconds";
    public static final String RESPAWN_MAX_SECONDS = "respawn.max-seconds";
    public static final String PROTECTION_SECONDS = "respawn.protection-seconds";
    public static final String SPAWN_ZONE_RADIUS = "respawn.spawn-zone-radius";
    public static final String SPAWN_ZONE_DAMAGE = "respawn.spawn-zone-damage";
    public static final String OUTER_HEALTH = "towers.outer-health";
    public static final String INNER_HEALTH = "towers.inner-health";
    public static final String CORE_HEALTH = "towers.core-health";
    public static final String HEALTH_BASE_SHARE = "towers.health-base-share";
    public static final String HEALTH_PER_ATTACKER = "towers.health-per-attacker";
    public static final String REGEN_DELAY = "towers.regen-delay-seconds";
    public static final String REGEN_PER_SECOND = "towers.regen-per-second";
    public static final String WARNING_COOLDOWN = "towers.warning-cooldown-seconds";
    public static final String ATTACK_DAMAGE = "towers.attack-damage";
    public static final String ATTACK_SECONDS = "towers.attack-seconds";
    public static final String ATTACK_RANGE = "towers.attack-range";
    public static final String HITBOX_SCALE = "towers.hitbox-scale";

    public static final int MAX_SECONDS = 3600;
    /**
     * The default ability pool: 50 abilities that each have a 3D weapon (AbilityWeaponModels); owners change it with
     * {@code /게임 공성전 능력풀}.
     */
    public static final List<String> DEFAULT_POOL = List.of(
            // jobs and weapons
            "police", "miner", "blacksmith", "gunslinger", "chef", "ninja", "blade", "duelist", "archer", "sniper",
            "hunter", "lancer", "berserker", "guardian", "fighter",
            // elements
            "pyro", "frost", "thunder", "aero", "earth", "hydro", "magma", "light", "shadow",
            // dark and myth
            "vampire", "reaper", "necromancer", "demon", "phoenix", "dragon", "werewolf", "dokkaebi",
            // support
            "healer", "bard", "alchemist",
            // tech, space-time, venom, the sea and the field
            "engineer", "hacker", "mech", "bomber", "gravity", "magnet", "chrono", "blink", "speedster", "venom",
            "spider", "pirate", "angler", "farmer", "batter");

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
                ticks(c.getDouble(ROUND_SECONDS, 1200), 30),
                ticks(c.getDouble(ROUND_PREP_SECONDS, 20), 0),
                ticks(c.getDouble(ROUND_BREAK_SECONDS, 60), 0),
                ticks(c.getDouble(OVERTIME_SECONDS, 120), 10),
                ticks(c.getDouble(CLIMAX_SECONDS, 180), 0),
                clamp(c.getInt(REROLLS, 1), 0, 10),
                List.copyOf(pool),
                points(c, KILL, 2),
                points(c, ASSIST, 1),
                ticks(c.getDouble(ASSIST_SECONDS, 8), 0),
                points(c, TOWER_POINTS, 8),
                ticks(c.getDouble(RESPAWN_SECONDS, 6), 0),
                clamp(c.getInt(RESPAWN_DEATHS_PER_STEP, 3), 1, 100),
                ticks(c.getDouble(RESPAWN_STEP_SECONDS, 1), 0),
                ticks(c.getDouble(RESPAWN_MAX_SECONDS, 14), 0),
                ticks(c.getDouble(PROTECTION_SECONDS, 3), 0),
                Math.max(0, finite(c.getDouble(SPAWN_ZONE_RADIUS, 8), 8)),
                Math.max(0, finite(c.getDouble(SPAWN_ZONE_DAMAGE, 4), 4)),
                health(c.getDouble(OUTER_HEALTH, 1500)),
                health(c.getDouble(INNER_HEALTH, 2000)),
                health(c.getDouble(CORE_HEALTH, 2500)),
                Math.max(0, Math.min(10, finite(c.getDouble(HEALTH_BASE_SHARE, 0.25), 0.25))),
                Math.max(0, Math.min(10, finite(c.getDouble(HEALTH_PER_ATTACKER, 0.25), 0.25))),
                ticks(c.getDouble(REGEN_DELAY, 10), 0),
                fraction(c.getDouble(REGEN_PER_SECOND, 0.01)),
                ticks(c.getDouble(WARNING_COOLDOWN, 8), 0),
                Math.max(0, Math.min(100, finite(c.getDouble(ATTACK_DAMAGE, 4), 4))),
                ticks(c.getDouble(ATTACK_SECONDS, 1.5), 0.25),
                Math.max(0, Math.min(64, finite(c.getDouble(ATTACK_RANGE, 10), 10))),
                Math.max(0.5, Math.min(10.0, finite(c.getDouble(HITBOX_SCALE, 2.5), 2.5))));
    }

    /** Configured health of a structure (the health against three attackers). */
    public double structureHealth(Tower.Kind kind) {
        return switch (kind) {
            case OUTER -> outerHealth;
            case INNER -> innerHealth;
            case CORE -> coreHealth;
        };
    }

    /**
     * Health of a structure against an enemy team of {@code attackers} players: the configured health times
     * {@code health-base-share + health-per-attacker × attackers} (1.0 for three with the defaults), at least 1.
     */
    public double structureHealth(Tower.Kind kind, int attackers) {
        double factor = healthBaseShare + healthPerAttacker * Math.max(1, attackers);
        return Math.max(1, structureHealth(kind) * factor);
    }

    /**
     * Respawn delay of a player who died {@code deaths} times this round (this death included): base + one step per
     * {@link #respawnDeathsPerStep} deaths, capped at {@link #respawnMaxTicks}, at least one second.
     */
    public int respawnDelay(int deaths) {
        int steps = Math.max(0, deaths) / respawnDeathsPerStep;
        int delay = Math.min(Math.max(respawnTicks, respawnMaxTicks), respawnTicks + steps * respawnStepTicks);
        return Math.max(20, delay);
    }

    /** Declares the defaults (with Korean comments) merged into {@code config.yml} → {@code games.siege}. */
    public static void declareDefaults(ConfigurationSection d) {
        d.set(ROUNDS_TO_WIN, 2);
        d.setComments(ROUNDS_TO_WIN, List.of("이 판 수를 먼저 이긴 팀이 우승 (2 = 3판 2선승)"));
        d.set(ROUND_SECONDS, 1200);
        d.setComments(ROUND_SECONDS, List.of(
                "한 판의 제한 시간(초). 코어를 부수면 시간과 상관없이 바로 판 승리,",
                "시간이 끝나면 점수 → 부순 타워 → 처치 순으로 승자, 모두 같으면 연장전"));
        d.set(ROUND_PREP_SECONDS, 20);
        d.setComments(ROUND_PREP_SECONDS, List.of("판 시작 준비 시간(초): 규칙 안내, 능력 공개, 리롤(웅크리고 우클릭). 무적"));
        d.set(ROUND_BREAK_SECONDS, 60);
        d.setComments(ROUND_BREAK_SECONDS, List.of("판 사이 쉬는 시간(초)"));
        d.set(OVERTIME_SECONDS, 120);
        d.setComments(OVERTIME_SECONDS, List.of(
                "연장전(초): 먼저 점수를 낸 팀이 판 승리. 아무도 못 내면 건물 체력이 더 많이 남은 팀"));
        d.set(CLIMAX_SECONDS, 180);
        d.setComments(CLIMAX_SECONDS, List.of("남은 시간이 이만큼(초)이 되면 '마지막 3분' 알림 (규칙은 그대로, 0 = 끔)"));
        d.set(REROLLS, 1);
        d.setComments(REROLLS, List.of("판마다 한 사람이 능력을 다시 뽑을 수 있는 횟수 (판 준비 시간에 웅크리고 우클릭)"));
        d.set(ABILITY_POOL, DEFAULT_POOL);
        d.setComments(ABILITY_POOL, List.of(
                "공성전에서 나오는 능력 id 목록. 비어 있으면 abilities.yml 에서 켜진 모든 능력",
                "/게임 공성전 능력풀 <추가|제거|목록|초기화> [능력] 로도 바꿀 수 있습니다."));
        d.setComments("points", List.of("점수 (한 판 동안). 정글·드래곤·점령지 점수는 그 기능의 설정에 있습니다"));
        d.set(KILL, 2);
        d.setComments(KILL, List.of("적 처치"));
        d.set(ASSIST, 1);
        d.setComments(ASSIST, List.of("도움: 처치 직전 피해를 준 다른 팀원마다"));
        d.set(ASSIST_SECONDS, 8);
        d.setComments(ASSIST_SECONDS, List.of("도움으로 치는 피해의 시간(초)"));
        d.set(TOWER_POINTS, 8);
        d.setComments(TOWER_POINTS, List.of("적 타워 파괴 (코어는 파괴 즉시 판 승리)"));
        d.setComments("respawn", List.of("부활 (자기 본진에서)"));
        d.set(RESPAWN_SECONDS, 6);
        d.setComments(RESPAWN_SECONDS, List.of("기본 부활 대기 시간(초)"));
        d.set(RESPAWN_DEATHS_PER_STEP, 3);
        d.set(RESPAWN_STEP_SECONDS, 1);
        d.setComments(RESPAWN_DEATHS_PER_STEP, List.of("그 판에서 이만큼 죽을 때마다 부활 대기 +step-seconds초 (최대 max-seconds)"));
        d.set(RESPAWN_MAX_SECONDS, 14);
        d.set(PROTECTION_SECONDS, 3);
        d.setComments(PROTECTION_SECONDS, List.of("부활 직후 무적(초). 공격하면 풀립니다"));
        d.set(SPAWN_ZONE_RADIUS, 8);
        d.set(SPAWN_ZONE_DAMAGE, 4);
        d.setComments(SPAWN_ZONE_RADIUS, List.of("적 본진 부활 지점 반경(블록) 안에 들어온 적은 1초마다 spawn-zone-damage 피해"));
        d.setComments("towers", List.of("타워와 코어 (팀마다 라인당 타워 2개 + 코어 1개)"));
        d.set(OUTER_HEALTH, 1500);
        d.set(INNER_HEALTH, 2000);
        d.set(CORE_HEALTH, 2500);
        d.setComments(OUTER_HEALTH, List.of("체력 (바깥 타워·안쪽 타워·코어, 상대 팀 3명 기준)"));
        d.set(HEALTH_BASE_SHARE, 0.25);
        d.set(HEALTH_PER_ATTACKER, 0.25);
        d.setComments(HEALTH_BASE_SHARE, List.of(
                "상대 팀 인원에 따른 건물 체력 배율 = health-base-share + health-per-attacker × 상대 팀 인원",
                "(기본값: 1명 0.5배, 3명 1배, 5명 1.5배, 8명 2.25배)"));
        d.set(REGEN_DELAY, 10);
        d.set(REGEN_PER_SECOND, 0.01);
        d.setComments(REGEN_DELAY, List.of(
                "피해를 받지 않고 이 시간(초)이 지나면 초당 regen-per-second(최대 체력 비율)씩 회복",
                "적 플레이어가 건물 반경 안에 있으면 회복하지 않습니다"));
        d.set(WARNING_COOLDOWN, 8);
        d.setComments(WARNING_COOLDOWN, List.of("'우리 타워가 공격받는 중' 경고(소리·안내) 간격(초)"));
        d.set(ATTACK_DAMAGE, 4);
        d.set(ATTACK_SECONDS, 1.5);
        d.set(ATTACK_RANGE, 10);
        d.setComments(ATTACK_DAMAGE, List.of(
                "타워(코어 제외)는 attack-seconds 초마다 attack-range 블록 안의 적 하나에게 이만큼 피해 (0 = 공격 안 함)",
                "미니언이 있으면 미니언을 먼저 노립니다"));
        d.set(HITBOX_SCALE, 2.5);
        d.setComments(HITBOX_SCALE, List.of("건물 피격 판정 크기 배율 (0.5~10). 3D 건물은 크기에 맞춰 이 값을 더 키워 씁니다"));
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
