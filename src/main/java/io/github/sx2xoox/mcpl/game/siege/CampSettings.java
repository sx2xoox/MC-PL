package io.github.sx2xoox.mcpl.game.siege;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.bukkit.configuration.ConfigurationSection;

/**
 * The monster camps of 공성전 ({@code games.siege.camps.*}; docs/siege.md §13), read once per match. Durations are in
 * ticks, health and damage in health points, distances in blocks.
 *
 * @param enabled              camps spawn at all
 * @param healthPerExtraPlayer extra monster health per player of the bigger team beyond the first (0.25 = +25 %)
 * @param shareRadius          teammates this close to the camp share its experience
 * @param resetTicks           a fighting camp nobody hit for this long goes home with full health
 * @param tiers                timers and rewards by tier
 * @param mobs                 the monsters of each kind
 * @param redBurnTicks         레드 서버 buff: what its holder hits burns this long
 * @param blueCooldownPerSecond 블루 서버 buff: extra cooldown seconds recovered every second
 * @param blueGaugePerSecond   블루 서버 buff: extra ultimate gauge (%) every second
 */
public record CampSettings(
        boolean enabled,
        double healthPerExtraPlayer,
        double shareRadius,
        int resetTicks,
        Map<CampKind.Tier, TierSettings> tiers,
        Map<CampKind, MobSettings> mobs,
        int redBurnTicks,
        double blueCooldownPerSecond,
        double blueGaugePerSecond) {

    public static final String PATH = "camps.";
    public static final String ENABLED = PATH + "enabled";
    public static final String HEALTH_PER_EXTRA_PLAYER = PATH + "health-per-extra-player";
    public static final String SHARE_RADIUS = PATH + "share-radius";
    public static final String RESET = PATH + "reset-seconds";
    public static final String RED_BURN = PATH + "red.burn-seconds";
    public static final String BLUE_COOLDOWN = PATH + "blue.cooldown-per-second";
    public static final String BLUE_GAUGE = PATH + "blue.gauge-per-second";

    /**
     * Timers and rewards of one tier.
     *
     * @param firstTicks   from the start of the fight to the first spawn
     * @param respawnTicks from a clear to the next spawn
     * @param warningTicks the announcement before a spawn (0 = none)
     * @param xp           experience: split between the killing team's players near the camp (epic: each member)
     * @param lastHitBonus extra experience for the last hit
     * @param leashRadius  monsters pulled farther from the camp go home with full health
     * @param buffTicks    how long the team buff lasts (no buff for small camps)
     */
    public record TierSettings(int firstTicks, int respawnTicks, int warningTicks, int xp, int lastHitBonus,
            double leashRadius, int buffTicks) {
    }

    /**
     * The monsters of one kind.
     *
     * @param count  monsters in the camp
     * @param health health of each (before the team size bonus)
     * @param damage damage of each hit on a player
     * @param scale  size (the scale attribute; 1 = vanilla)
     */
    public record MobSettings(int count, double health, double damage, double scale) {
    }

    private record TierDefaults(double first, double respawn, double warning, int xp, int bonus, double leash,
            double buff) {
    }

    private record MobDefaults(int count, double health, double damage, double scale) {
    }

    private static final Map<CampKind.Tier, TierDefaults> TIER_DEFAULTS = Map.of(
            CampKind.Tier.SMALL, new TierDefaults(30, 60, 0, 45, 15, 14, 0),
            CampKind.Tier.BUFF, new TierDefaults(90, 150, 15, 100, 30, 16, 90),
            CampKind.Tier.EPIC, new TierDefaults(360, 300, 30, 300, 50, 22, 120));

    private static final Map<CampKind, MobDefaults> MOB_DEFAULTS = Map.of(
            CampKind.DRONES, new MobDefaults(3, 10, 2, 1.0),
            CampKind.RATS, new MobDefaults(3, 12, 2, 1.0),
            CampKind.SCRAP, new MobDefaults(2, 20, 3, 0.8),
            CampKind.RED, new MobDefaults(1, 90, 6, 1.5),
            CampKind.BLUE, new MobDefaults(1, 90, 6, 1.5),
            CampKind.GUARDIAN, new MobDefaults(1, 400, 10, 2.5));

    static String tierPath(CampKind.Tier tier, String key) {
        return PATH + tier.key() + "." + key;
    }

    static String mobPath(CampKind kind, String key) {
        return PATH + "mobs." + kind.id() + "." + key;
    }

    public TierSettings tier(CampKind.Tier tier) {
        return tiers.get(tier);
    }

    public MobSettings mob(CampKind kind) {
        return mobs.get(kind);
    }

    /** Reads the live section; values out of range are clamped, missing ones use the defaults. */
    public static CampSettings read(ConfigurationSection c) {
        Map<CampKind.Tier, TierSettings> tiers = new EnumMap<>(CampKind.Tier.class);
        for (CampKind.Tier tier : CampKind.Tier.values()) {
            TierDefaults d = TIER_DEFAULTS.get(tier);
            tiers.put(tier, new TierSettings(
                    ticks(c.getDouble(tierPath(tier, "first-seconds"), d.first()), 0),
                    ticks(c.getDouble(tierPath(tier, "respawn-seconds"), d.respawn()), 5),
                    ticks(c.getDouble(tierPath(tier, "warning-seconds"), d.warning()), 0),
                    clamp(c.getInt(tierPath(tier, "xp"), d.xp()), 0, 100_000),
                    clamp(c.getInt(tierPath(tier, "last-hit-bonus"), d.bonus()), 0, 100_000),
                    range(c.getDouble(tierPath(tier, "leash-radius"), d.leash()), 4, 64, d.leash()),
                    ticks(c.getDouble(tierPath(tier, "buff-seconds"), d.buff()), 0)));
        }
        Map<CampKind, MobSettings> mobs = new EnumMap<>(CampKind.class);
        for (CampKind kind : CampKind.values()) {
            MobDefaults d = MOB_DEFAULTS.get(kind);
            mobs.put(kind, new MobSettings(
                    clamp(c.getInt(mobPath(kind, "count"), d.count()), 1, 8),
                    range(c.getDouble(mobPath(kind, "health"), d.health()), 1, 2048, d.health()),
                    range(c.getDouble(mobPath(kind, "damage"), d.damage()), 0, 100, d.damage()),
                    range(c.getDouble(mobPath(kind, "scale"), d.scale()), 0.25, 4, d.scale())));
        }
        return new CampSettings(
                c.getBoolean(ENABLED, true),
                range(c.getDouble(HEALTH_PER_EXTRA_PLAYER, 0.25), 0, 5, 0.25),
                range(c.getDouble(SHARE_RADIUS, 20), 0, 128, 20),
                ticks(c.getDouble(RESET, 8), 1),
                tiers,
                mobs,
                ticks(c.getDouble(RED_BURN, 3), 0),
                range(c.getDouble(BLUE_COOLDOWN, 0.5), 0, 10, 0.5),
                range(c.getDouble(BLUE_GAUGE, 0.5), 0, 100, 0.5));
    }

    /** Health of each monster of {@code kind} when the bigger team has {@code teamSize} players. */
    public double health(CampKind kind, int teamSize) {
        return mob(kind).health() * (1 + healthPerExtraPlayer * Math.max(0, teamSize - 1));
    }

    /** Declares the defaults (with Korean comments) merged into {@code config.yml} → {@code games.siege}. */
    public static void declareDefaults(ConfigurationSection d) {
        d.setComments("camps", List.of(
                "중립 몬스터 캠프 (롤 정글몹처럼). 쓰러뜨린 팀이 경험치를 받고 정해진 시간 뒤 다시 나타납니다",
                "small = 작은 캠프(드론 둥지·변종 쥐떼·고철 골렘), buff = 버프 캠프(레드·블루 서버),",
                "epic = 에픽(메인프레임 수호자). 몬스터는 맞기 전까지 가만히 있고, 너무 멀리 끌려가면 체력을 채우고 돌아갑니다"));
        d.set(ENABLED, true);
        d.set(HEALTH_PER_EXTRA_PLAYER, 0.25);
        d.setComments(HEALTH_PER_EXTRA_PLAYER, List.of("큰 팀의 인원이 1명 늘 때마다 몬스터 체력 +이 비율 (0.25 = +25%)"));
        d.set(SHARE_RADIUS, 20);
        d.setComments(SHARE_RADIUS, List.of("작은·버프 캠프 경험치를 나눠 받는 거리(블록): 쓰러뜨린 팀에서 이 안에 있는 사람"));
        d.set(RESET, 8);
        d.setComments(RESET, List.of("싸우던 캠프를 이 시간(초) 동안 아무도 때리지 않으면 체력을 채우고 제자리로"));
        for (CampKind.Tier tier : CampKind.Tier.values()) {
            TierDefaults t = TIER_DEFAULTS.get(tier);
            d.set(tierPath(tier, "first-seconds"), t.first());
            d.set(tierPath(tier, "respawn-seconds"), t.respawn());
            d.set(tierPath(tier, "warning-seconds"), t.warning());
            d.set(tierPath(tier, "xp"), t.xp());
            d.set(tierPath(tier, "last-hit-bonus"), t.bonus());
            d.set(tierPath(tier, "leash-radius"), t.leash());
            d.set(tierPath(tier, "buff-seconds"), t.buff());
        }
        d.setComments(PATH + "small", List.of(
                "first-seconds: 싸움 시작 후 처음 나타나기까지, respawn-seconds: 쓰러진 뒤 다시 나타나기까지,",
                "warning-seconds: 나타나기 몇 초 전에 알릴지(0 = 안 알림), xp: 경험치(작은·버프 캠프는 근처 팀원끼리 나눔,",
                "에픽은 팀 모두가 받음), last-hit-bonus: 막타 추가 경험치, leash-radius: 이보다 멀리 끌려가면 초기화,",
                "buff-seconds: 팀 버프 시간(작은 캠프는 버프 없음)"));
        d.setComments(PATH + "mobs", List.of(
                "캠프별 몬스터: count = 마리 수, health = 한 마리 체력(2 = 하트 1칸), damage = 한 번 때릴 때 피해,",
                "scale = 크기(1 = 원래 크기)"));
        for (CampKind kind : CampKind.values()) {
            MobDefaults m = MOB_DEFAULTS.get(kind);
            d.set(mobPath(kind, "count"), m.count());
            d.set(mobPath(kind, "health"), m.health());
            d.set(mobPath(kind, "damage"), m.damage());
            d.set(mobPath(kind, "scale"), m.scale());
        }
        d.set(RED_BURN, 3);
        d.setComments(PATH + "red", List.of("레드 서버 버프: 힘 I, 때린 적(근접·능력)이 burn-seconds 동안 불탐"));
        d.set(BLUE_COOLDOWN, 0.5);
        d.set(BLUE_GAUGE, 0.5);
        d.setComments(PATH + "blue", List.of(
                "블루 서버 버프: 1초마다 쿨타임 cooldown-per-second 초 추가 감소, 궁극기 게이지 gauge-per-second % 추가"));
    }

    private static int ticks(double seconds, double min) {
        double clamped = Math.max(min, Math.min(SiegeSettings.MAX_SECONDS, Double.isFinite(seconds) ? seconds : min));
        return (int) Math.round(clamped * 20);
    }

    private static double range(double value, double min, double max, double fallback) {
        return Double.isFinite(value) ? Math.max(min, Math.min(max, value)) : fallback;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
