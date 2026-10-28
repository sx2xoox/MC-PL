package io.github.sx2xoox.mcpl.game.augment;

import java.util.List;

import org.bukkit.configuration.ConfigurationSection;

/**
 * The {@code games.augment.practice.*} settings of {@code config.yml} for 증강 연습 ({@code /게임 증강 연습}), read
 * when a practice starts. Everything else (border, choice time, rerolls, bonuses) comes from {@code games.augment}.
 *
 * @param radius            half side of the practice world's play area, in blocks
 * @param pvp               practising players can hurt each other from the start (toggle with {@code /게임 증강 대전})
 * @param dummyCount        dummies {@code /게임 증강 허수아비} places without a count
 * @param dummyHealth       health points of a dummy
 * @param dummyRefillTicks  time without a hit before a dummy is healed and put back on its spot
 */
public record AugmentPracticeSettings(int radius, boolean pvp, int dummyCount, double dummyHealth,
                                      int dummyRefillTicks) {

    public static final String RADIUS = "practice.radius";
    public static final String PVP = "practice.pvp";
    public static final String DUMMY_COUNT = "practice.dummies.count";
    public static final String DUMMY_HEALTH = "practice.dummies.health";
    public static final String DUMMY_REFILL = "practice.dummies.refill-seconds";

    public static final int MAX_DUMMIES = 10;
    public static final double MAX_DUMMY_HEALTH = 1024;

    /** Declares the defaults (with Korean comments) in {@code games.augment}. */
    public static void declareDefaults(ConfigurationSection d) {
        d.set(RADIUS, 150);
        d.setComments("practice", List.of(
                "증강 연습 (/게임 증강 연습): 혼자서도 시작할 수 있는 연습. 진짜 경기처럼 새 야생 월드를 만들고(끝나면 지움),",
                "단계는 명령어로 넘기며(/게임 증강 다음단계) 증강을 마음대로 받고 뺄 수 있습니다."));
        d.setComments(RADIUS, List.of("연습 월드의 플레이 구역 반경(블록, 32~2000). 작을수록 빨리 준비됩니다."));
        d.set(PVP, false);
        d.setComments(PVP, List.of("연습하는 플레이어끼리 공격할 수 있는지 (연습 중 /게임 증강 대전 켜기|끄기 로 바꿀 수 있습니다)"));
        d.set(DUMMY_COUNT, 3);
        d.setComments(DUMMY_COUNT, List.of("/게임 증강 허수아비 로 세우는 허수아비 수 (1~" + MAX_DUMMIES + ")"));
        d.set(DUMMY_HEALTH, 40.0);
        d.setComments(DUMMY_HEALTH, List.of("허수아비 체력 (2 = 하트 1칸, 최대 1024). 죽으면 잠시 뒤 다시 섭니다."));
        d.set(DUMMY_REFILL, 5.0);
        d.setComments(DUMMY_REFILL, List.of("마지막으로 맞은 뒤 체력이 다시 차고 제자리로 돌아갈 때까지(초)"));
    }

    /** Reads the live {@code games.augment} section; invalid values are clamped. */
    public static AugmentPracticeSettings read(ConfigurationSection s) {
        return new AugmentPracticeSettings(
                Math.max(AugmentSettings.MIN_RADIUS, Math.min(AugmentSettings.MAX_RADIUS, s.getInt(RADIUS, 150))),
                s.getBoolean(PVP, false),
                Math.max(1, Math.min(MAX_DUMMIES, s.getInt(DUMMY_COUNT, 3))),
                clamp(s.getDouble(DUMMY_HEALTH, 40.0), 1.0, MAX_DUMMY_HEALTH, 40.0),
                (int) Math.round(clamp(s.getDouble(DUMMY_REFILL, 5.0), 0.5, 60.0, 5.0) * 20.0));
    }

    private static double clamp(double value, double min, double max, double fallback) {
        return Double.isFinite(value) ? Math.max(min, Math.min(max, value)) : fallback;
    }
}
