package io.github.sx2xoox.mcpl.game.superpower;

import java.util.List;

import org.bukkit.configuration.ConfigurationSection;

/**
 * The {@code games.practice.*} settings of {@code config.yml} for the ability practice ({@code /게임 능력 연습}), read
 * once per practice session. Everything else (kit, gauge rates, effects) comes from {@code games.superpower}.
 *
 * @param cooldowns          active skills have their normal cooldown (toggle in game with {@code /게임 능력 쿨타임})
 * @param dummyCount         training dummies placed in front of every player
 * @param dummyDistance      distance of the first dummy from the player (blocks)
 * @param dummySpacing       distance between two dummies (blocks)
 * @param dummyHealth        health points of a dummy (refilled after {@code dummyRefillTicks} without a hit)
 * @param dummyRefillTicks   time without a hit before a dummy is healed and put back on its spot
 * @param damageNumbers      show a floating damage number for every hit on a dummy
 */
public record PracticeSettings(
        boolean cooldowns,
        int dummyCount,
        double dummyDistance,
        double dummySpacing,
        double dummyHealth,
        int dummyRefillTicks,
        boolean damageNumbers
) {

    public static final String COOLDOWNS = "cooldowns";
    public static final String DUMMY_COUNT = "dummies.count";
    public static final String DUMMY_DISTANCE = "dummies.first-distance";
    public static final String DUMMY_SPACING = "dummies.spacing";
    public static final String DUMMY_HEALTH = "dummies.health";
    public static final String DUMMY_REFILL = "dummies.refill-seconds";
    public static final String DAMAGE_NUMBERS = "dummies.damage-numbers";

    public static final int MAX_DUMMIES = 10;
    /** Vanilla's upper limit for the max health attribute. */
    public static final double MAX_DUMMY_HEALTH = 1024;

    /** Declares the defaults (with Korean comments) merged into {@code config.yml} → {@code games.practice}. */
    public static void declareDefaults(ConfigurationSection d) {
        d.set(COOLDOWNS, true);
        d.setComments(COOLDOWNS, List.of(
                "능력 연습(/게임 능력 연습)에서 액티브 쿨타임을 적용할지 (연습 중 /게임 능력 쿨타임 켜기|끄기 로도 바꿀 수 있습니다)",
                "킷, 궁극기 게이지, 파티클 등 나머지 설정은 games.superpower 를 그대로 씁니다."));
        d.set(DUMMY_COUNT, 3);
        d.setComments("dummies", List.of("훈련용 허수아비"));
        d.setComments(DUMMY_COUNT, List.of("플레이어 앞에 세우는 허수아비 수 (1~" + MAX_DUMMIES + ")"));
        d.set(DUMMY_DISTANCE, 5.0);
        d.setComments(DUMMY_DISTANCE, List.of("첫 허수아비까지의 거리(블록)"));
        d.set(DUMMY_SPACING, 3.0);
        d.setComments(DUMMY_SPACING, List.of("허수아비 사이 간격(블록). 기본값이면 5, 8, 11블록 앞에 섭니다."));
        d.set(DUMMY_HEALTH, 200.0);
        d.setComments(DUMMY_HEALTH, List.of("허수아비 체력 (2 = 하트 1칸, 최대 1024)"));
        d.set(DUMMY_REFILL, 3.0);
        d.setComments(DUMMY_REFILL, List.of("마지막으로 맞은 뒤 체력이 다시 차고 제자리로 돌아갈 때까지(초)"));
        d.set(DAMAGE_NUMBERS, true);
        d.setComments(DAMAGE_NUMBERS, List.of("허수아비가 맞을 때마다 피해량을 숫자로 띄웁니다"));
    }

    /** Reads the live section; invalid values fall back to the defaults or are clamped. */
    public static PracticeSettings read(ConfigurationSection s) {
        return new PracticeSettings(
                s.getBoolean(COOLDOWNS, true),
                Math.max(1, Math.min(MAX_DUMMIES, s.getInt(DUMMY_COUNT, 3))),
                clamp(s.getDouble(DUMMY_DISTANCE, 5.0), 1.0, 32.0, 5.0),
                clamp(s.getDouble(DUMMY_SPACING, 3.0), 0.5, 16.0, 3.0),
                clamp(s.getDouble(DUMMY_HEALTH, 200.0), 1.0, MAX_DUMMY_HEALTH, 200.0),
                (int) Math.round(clamp(s.getDouble(DUMMY_REFILL, 3.0), 0.5, 60.0, 3.0) * 20.0),
                s.getBoolean(DAMAGE_NUMBERS, true));
    }

    private static double clamp(double value, double min, double max, double fallback) {
        return Double.isFinite(value) ? Math.max(min, Math.min(max, value)) : fallback;
    }
}
