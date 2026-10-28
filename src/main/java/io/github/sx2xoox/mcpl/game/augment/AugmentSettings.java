package io.github.sx2xoox.mcpl.game.augment;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.logging.Logger;

import io.github.sx2xoox.mcpl.game.augment.api.AugmentTier;
import org.bukkit.Difficulty;
import org.bukkit.configuration.ConfigurationSection;
import org.jspecify.annotations.Nullable;

/**
 * The {@code games.augment.*} settings of {@code config.yml}, read once per match (when it is created, since the
 * world is made right away). Durations are converted to ticks.
 *
 * @param radius               half side of the play area (the world border starts at twice this), in blocks
 * @param structures           villages, ruins, mineshafts, ... generate in the match world
 * @param difficulty           difficulty of the match world
 * @param pregenerate          generate the play area before the farming starts (smoother play, longer wait)
 * @param pregenChunksPerTick  chunk requests started per tick while pre-generating
 * @param pregenMaxWaitTicks   start anyway after this long; the rest generates while playing
 * @param farmingTicks         farming phase (no PvP) length
 * @param borderDelayTicks     fight time before the border starts shrinking
 * @param borderShrinkTicks    how long the border takes to reach its final size
 * @param borderFinalSize      final border side length in blocks
 * @param borderDamage         damage per second per block outside the border (vanilla border damage)
 * @param glowAtFinal          everyone alive glows once the border reached its final size
 * @param choiceTicks          time to pick an augment card before a random one is granted
 * @param rerolls              rerolls per player per match
 * @param rounds               timed augment rounds, in order
 * @param bonusEnabled         special achievements grant extra augments
 */
public record AugmentSettings(
        int radius,
        boolean structures,
        Difficulty difficulty,
        boolean pregenerate,
        int pregenChunksPerTick,
        long pregenMaxWaitTicks,
        long farmingTicks,
        long borderDelayTicks,
        long borderShrinkTicks,
        double borderFinalSize,
        double borderDamage,
        boolean glowAtFinal,
        long choiceTicks,
        int rerolls,
        List<Round> rounds,
        boolean bonusEnabled
) {

    public static final String RADIUS = "world.radius";
    public static final String STRUCTURES = "world.structures";
    public static final String DIFFICULTY = "world.difficulty";
    public static final String PREGENERATE = "world.pregenerate";
    public static final String PREGEN_PER_TICK = "world.pregenerate-chunks-per-tick";
    public static final String PREGEN_MAX_WAIT = "world.pregenerate-max-wait-seconds";
    public static final String FARMING = "farming-seconds";
    public static final String BORDER_DELAY = "border.start-delay-seconds";
    public static final String BORDER_SHRINK = "border.shrink-seconds";
    public static final String BORDER_FINAL = "border.final-size";
    public static final String BORDER_DAMAGE = "border.damage-per-block";
    public static final String GLOW = "border.glow-at-final";
    public static final String CHOICE = "augments.choice-seconds";
    public static final String REROLLS = "augments.rerolls";
    public static final String ROUNDS = "augments.rounds";
    public static final String BONUS = "bonus.enabled";

    public static final int MIN_RADIUS = 32;
    public static final int MAX_RADIUS = 2000;
    public static final int MAX_PER_TICK = 64;
    public static final int MAX_WAIT_SECONDS = 1800;
    public static final int MAX_PHASE_SECONDS = 7200;
    public static final int MAX_CHOICE_SECONDS = 300;
    public static final int MIN_CHOICE_SECONDS = 5;
    public static final int MAX_REROLLS = 10;
    public static final int MAX_FINAL_SIZE = 2 * MAX_RADIUS;

    /**
     * One timed augment round: at {@code atTicks} after the farming started everyone draws cards of one tier, rolled
     * with these weights.
     */
    public record Round(long atTicks, Map<AugmentTier, Integer> weights) {

        public Round {
            Map<AugmentTier, Integer> copy = new EnumMap<>(AugmentTier.class);
            weights.forEach((tier, weight) -> copy.put(tier, Math.max(0, weight)));
            if (copy.values().stream().mapToInt(Integer::intValue).sum() <= 0) {
                copy.put(AugmentTier.SILVER, 1);
            }
            weights = Map.copyOf(copy);
        }

        /** Rolls the tier of this round. */
        public AugmentTier roll(Random random) {
            int total = 0;
            for (AugmentTier tier : AugmentTier.values()) {
                total += weights.getOrDefault(tier, 0);
            }
            int pick = random.nextInt(total);
            for (AugmentTier tier : AugmentTier.values()) {
                pick -= weights.getOrDefault(tier, 0);
                if (pick < 0) {
                    return tier;
                }
            }
            return AugmentTier.SILVER;
        }

        /** The chance of a tier in percent (for announcements and docs). */
        public double percent(AugmentTier tier) {
            int total = weights.values().stream().mapToInt(Integer::intValue).sum();
            return 100.0 * weights.getOrDefault(tier, 0) / total;
        }
    }

    public AugmentSettings {
        rounds = List.copyOf(rounds);
    }

    /** These settings with another play area radius (the practice world is smaller). */
    public AugmentSettings withRadius(int newRadius) {
        return new AugmentSettings(newRadius, structures, difficulty, pregenerate, pregenChunksPerTick,
                pregenMaxWaitTicks, farmingTicks, borderDelayTicks, borderShrinkTicks, borderFinalSize, borderDamage,
                glowAtFinal, choiceTicks, rerolls, rounds, bonusEnabled);
    }

    /** The default rounds: 5, 12, 20 (fight start) and 30 minutes, leaning higher as the match goes on. */
    static List<Map<String, Object>> defaultRounds() {
        return List.of(
                round(300, 70, 25, 5),
                round(720, 40, 45, 15),
                round(1200, 20, 50, 30),
                round(1800, 10, 45, 45));
    }

    private static Map<String, Object> round(int at, int silver, int gold, int prism) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("at", at);
        out.put("silver", silver);
        out.put("gold", gold);
        out.put("prism", prism);
        return out;
    }

    /** Declares the defaults (with Korean comments) merged into {@code config.yml} → {@code games.augment}. */
    public static void declareDefaults(ConfigurationSection d) {
        d.set(RADIUS, 400);
        d.setComments("world", List.of(
                "경기 월드: 경기마다 새 야생 월드(mcpl_wild_<번호>, 무작위 시드, 오버월드)를 만들고 경기가 끝나면 지웁니다.",
                "다른 월드는 건드리지 않습니다. 서버가 꺼져 남은 월드는 다음 시작 때 지웁니다."));
        d.setComments(RADIUS, List.of("플레이 구역 반경(블록). 월드보더가 한 변 = 반경 x 2 에서 시작합니다. (32~2000)"));
        d.set(STRUCTURES, true);
        d.setComments(STRUCTURES, List.of("마을·폐광·난파선 같은 구조물 생성 (보물 상자가 여기에 있습니다)"));
        d.set(DIFFICULTY, Difficulty.NORMAL.name());
        d.setComments(DIFFICULTY, List.of("경기 월드 난이도: EASY, NORMAL, HARD (PEACEFUL 이면 몬스터가 없습니다)"));
        d.set(PREGENERATE, true);
        d.setComments(PREGENERATE, List.of(
                "파밍 시작 전에 플레이 구역의 청크를 미리 만들어 둡니다 (진행률이 보스바에 표시됩니다).",
                "끄면 바로 시작하지만 처음 몇 분 동안 서버가 무거울 수 있습니다."));
        d.set(PREGEN_PER_TICK, 8);
        d.setComments(PREGEN_PER_TICK, List.of("틱마다 요청하는 청크 수 (1~64). 서버가 버거우면 낮추세요."));
        d.set(PREGEN_MAX_WAIT, 300);
        d.setComments(PREGEN_MAX_WAIT, List.of("미리 만들기를 기다리는 최대 시간(초). 넘으면 나머지는 플레이 중에 만들어집니다."));

        d.set(FARMING, 1200);
        d.setComments(FARMING, List.of("파밍 시간(초). 이 동안은 플레이어끼리 공격할 수 없습니다 (몹·낙하·용암은 위험합니다)."));

        d.set(BORDER_DELAY, 60);
        d.setComments("border", List.of("전투 (PvP 켜짐): 월드보더가 최종 크기까지 줄어듭니다."));
        d.setComments(BORDER_DELAY, List.of("전투 시작 후 월드보더가 줄어들기 시작할 때까지(초)"));
        d.set(BORDER_SHRINK, 900);
        d.setComments(BORDER_SHRINK, List.of("최종 크기까지 줄어드는 데 걸리는 시간(초)"));
        d.set(BORDER_FINAL, 24);
        d.setComments(BORDER_FINAL, List.of("최종 월드보더 한 변의 길이(블록)"));
        d.set(BORDER_DAMAGE, 1.0);
        d.setComments(BORDER_DAMAGE, List.of("월드보더 밖에서 1초마다 받는 피해 (보더에서 1블록 멀어질 때마다, 2 = 하트 1칸)"));
        d.set(GLOW, true);
        d.setComments(GLOW, List.of("월드보더가 최종 크기가 되면 살아 있는 모두가 발광합니다 (숨어서 버티기 방지)"));

        d.set(CHOICE, 30);
        d.setComments("augments", List.of("증강 (증강 목록과 수치는 augments.yml)"));
        d.setComments(CHOICE, List.of(
                "카드 3장 중 하나를 고르는 제한 시간(초). 시간이 지나면 무작위로 하나를 받습니다. (5~300)",
                "메뉴를 닫았다면 /게임 증강 으로 다시 열 수 있습니다."));
        d.set(REROLLS, 2);
        d.setComments(REROLLS, List.of("경기당 리롤 횟수 (보이는 카드 3장을 다시 뽑기, 0~10)"));
        d.set(ROUNDS, defaultRounds());
        d.setComments(ROUNDS, List.of(
                "증강 라운드: at = 파밍 시작 후 몇 초에, silver/gold/prism = 그 라운드 등급의 가중치",
                "한 라운드에서는 모두가 같은 등급(무작위)의 카드를 받습니다. 기본: 5분, 12분, 20분(전투 시작), 30분"));

        d.set(BONUS, true);
        d.setComments("bonus", List.of("보너스 증강: 특별한 업적(첫 번째 피, 심연의 광부 …)을 달성하면 증강을 하나 더 받습니다."));
        d.setComments(BONUS, List.of("조건별 켜기/끄기, 등급, 수치는 augments.yml 의 bonuses"));
    }

    /** Reads the live section; invalid values fall back to the defaults (with a console warning). */
    public static AugmentSettings read(ConfigurationSection s, Logger logger) {
        Difficulty difficulty;
        String rawDifficulty = s.getString(DIFFICULTY, Difficulty.NORMAL.name());
        try {
            difficulty = Difficulty.valueOf(rawDifficulty.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            logger.warning("games.augment." + DIFFICULTY + ": unknown difficulty '" + rawDifficulty + "', using NORMAL");
            difficulty = Difficulty.NORMAL;
        }
        return new AugmentSettings(
                clamp(s.getInt(RADIUS, 400), MIN_RADIUS, MAX_RADIUS),
                s.getBoolean(STRUCTURES, true),
                difficulty,
                s.getBoolean(PREGENERATE, true),
                clamp(s.getInt(PREGEN_PER_TICK, 8), 1, MAX_PER_TICK),
                seconds(s.getDouble(PREGEN_MAX_WAIT, 300), 0, MAX_WAIT_SECONDS),
                seconds(s.getDouble(FARMING, 1200), 0, MAX_PHASE_SECONDS),
                seconds(s.getDouble(BORDER_DELAY, 60), 0, MAX_PHASE_SECONDS),
                seconds(s.getDouble(BORDER_SHRINK, 900), 0, MAX_PHASE_SECONDS),
                Math.max(2.0, Math.min(MAX_FINAL_SIZE, finite(s.getDouble(BORDER_FINAL, 24), 24))),
                Math.max(0.0, finite(s.getDouble(BORDER_DAMAGE, 1.0), 1.0)),
                s.getBoolean(GLOW, true),
                seconds(s.getDouble(CHOICE, 30), MIN_CHOICE_SECONDS, MAX_CHOICE_SECONDS),
                clamp(s.getInt(REROLLS, 2), 0, MAX_REROLLS),
                readRounds(s, logger),
                s.getBoolean(BONUS, true));
    }

    private static List<Round> readRounds(ConfigurationSection s, Logger logger) {
        List<Map<?, ?>> raw = s.isList(ROUNDS) ? s.getMapList(ROUNDS) : new ArrayList<Map<?, ?>>(defaultRounds());
        List<Round> rounds = new ArrayList<>();
        for (Map<?, ?> entry : raw) {
            Double at = number(entry.get("at"));
            if (at == null || at < 0) {
                logger.warning("games.augment." + ROUNDS + ": ignoring a round without a valid 'at' (" + entry + ")");
                continue;
            }
            Map<AugmentTier, Integer> weights = new EnumMap<>(AugmentTier.class);
            for (AugmentTier tier : AugmentTier.values()) {
                Double weight = number(entry.get(tier.key()));
                weights.put(tier, weight == null ? 0 : (int) Math.max(0, Math.round(weight)));
            }
            rounds.add(new Round(Math.round(Math.min(at, MAX_PHASE_SECONDS * 2.0) * 20.0), weights));
        }
        rounds.sort(Comparator.comparingLong(Round::atTicks));
        return rounds;
    }

    private static @Nullable Double number(@Nullable Object raw) {
        if (raw instanceof Number number && Double.isFinite(number.doubleValue())) {
            return number.doubleValue();
        }
        if (raw instanceof String text) {
            try {
                return Double.parseDouble(text.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static long seconds(double seconds, double min, double max) {
        double value = Math.max(min, Math.min(max, finite(seconds, min)));
        return Math.round(value * 20.0);
    }

    private static double finite(double value, double fallback) {
        return Double.isFinite(value) ? value : fallback;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
