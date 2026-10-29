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
 * @param offerUi              how cards are shown: a dialog screen (default) or the chest menu
 * @param farmingInvulnerable  real matches: participants (players and bots) take no damage while farming
 * @param farmingNoHunger      real matches: players' food and saturation stay full while farming
 * @param startKit             what every player gets when the farming starts (empty = nothing)
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
        boolean bonusEnabled,
        OfferUi offerUi,
        boolean farmingInvulnerable,
        boolean farmingNoHunger,
        StartKit startKit
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
    public static final String OFFER_UI = "augments.ui";
    public static final String FARMING_INVULNERABLE = "farming.invulnerable";
    public static final String START_KIT = "start-kit.enabled";
    public static final String START_KIT_ITEMS = "start-kit.items";
    public static final String FARMING_NO_HUNGER = "farming.no-hunger";

    public static final int MIN_RADIUS = 32;
    public static final int MAX_RADIUS = 2000;
    public static final int MAX_PER_TICK = 64;
    public static final int MAX_WAIT_SECONDS = 1800;
    public static final int MAX_PHASE_SECONDS = 7200;
    public static final int MAX_CHOICE_SECONDS = 300;
    public static final int MIN_CHOICE_SECONDS = 5;
    public static final int MAX_REROLLS = 10;
    public static final int MAX_FINAL_SIZE = 2 * MAX_RADIUS;

    /** How a player sees and picks the cards of an offer. */
    public enum OfferUi {
        /** A dialog screen (Paper dialog API): cards side by side, like League of Legends' augment pick. */
        DIALOG,
        /** The 3-row chest menu (for clients or proxies that cannot show dialogs). */
        CHEST;

        /** The UI named {@code raw} (case-insensitive), or null. */
        public static @Nullable OfferUi parse(@Nullable String raw) {
            if (raw == null) {
                return null;
            }
            for (OfferUi ui : values()) {
                if (ui.name().equalsIgnoreCase(raw.trim())) {
                    return ui;
                }
            }
            return null;
        }
    }

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
                glowAtFinal, choiceTicks, rerolls, rounds, bonusEnabled, offerUi, farmingInvulnerable, farmingNoHunger,
                startKit);
    }

    /**
     * These settings with other phase lengths (봇 관전전 plays quicker): the augment rounds move with the farming
     * (a round at the fight start stays at the fight start), the border waits and closes as given.
     */
    public AugmentSettings withTimings(long newFarmingTicks, long newBorderDelayTicks, long newBorderShrinkTicks) {
        double ratio = farmingTicks > 0 ? (double) newFarmingTicks / farmingTicks : 1.0;
        List<Round> moved = new ArrayList<>(rounds.size());
        for (Round round : rounds) {
            moved.add(new Round(Math.round(round.atTicks() * ratio), round.weights()));
        }
        return new AugmentSettings(radius, structures, difficulty, pregenerate, pregenChunksPerTick,
                pregenMaxWaitTicks, Math.max(0, newFarmingTicks), Math.max(0, newBorderDelayTicks),
                Math.max(0, newBorderShrinkTicks), borderFinalSize, borderDamage, glowAtFinal, choiceTicks, rerolls,
                moved, bonusEnabled, offerUi, farmingInvulnerable, farmingNoHunger, startKit);
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
        d.setComments(FARMING, List.of("파밍 시간(초). 이 동안은 플레이어끼리 공격할 수 없습니다."));
        d.set(FARMING_INVULNERABLE, true);
        d.setComments("farming", List.of("파밍 시간의 규칙 (증강 야생전·봇전·봇 관전전, 연습은 제외). 전투가 시작되면 끝납니다."));
        d.setComments(FARMING_INVULNERABLE, List.of(
                "파밍 시간 동안 참가자(플레이어와 봇)는 어떤 피해도 받지 않습니다 (몹·낙하·용암·익사 포함).",
                "끄면 플레이어끼리의 공격만 막고 몹·낙하·용암은 위험합니다."));
        d.set(FARMING_NO_HUNGER, true);
        d.setComments(FARMING_NO_HUNGER, List.of("파밍 시간 동안 플레이어의 배고픔(허기·포화)이 줄지 않고 가득 찬 채로 유지됩니다."));

        d.set(START_KIT, true);
        d.setComments("start-kit", List.of(
                "시작 지급품: 파밍이 시작될 때 플레이어가 받는 아이템 (갑옷은 바로 착용). 기본은 음식만.",
                "철 흉갑 이상을 넣으면 봇도 철 장비부터 시작합니다. 경기가 끝나면 원래 인벤토리로 돌아갑니다."));
        d.setComments(START_KIT, List.of("시작 지급품 켜기/끄기 (끄면 빈손으로 시작)"));
        d.set(START_KIT_ITEMS, StartKit.DEFAULT_ITEMS);
        d.setComments(START_KIT_ITEMS, List.of("\"아이템 개수\" (개수를 빼면 1). 아이템 이름은 마인크래프트 ID (예: iron_sword, cooked_beef 32)"));

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
        d.set(OFFER_UI, OfferUi.DIALOG.name());
        d.setComments(OFFER_UI, List.of(
                "카드 선택 화면: DIALOG = 대화창(리그 오브 레전드처럼 카드가 나란히, 기본), CHEST = 상자 메뉴.",
                "대화창이 보이지 않는 클라이언트(오래된 버전, 일부 프록시)가 있다면 CHEST 로 바꾸세요."));
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
                s.getBoolean(BONUS, true),
                readOfferUi(s, logger),
                s.getBoolean(FARMING_INVULNERABLE, true),
                s.getBoolean(FARMING_NO_HUNGER, true),
                StartKit.read(s, logger));
    }

    private static OfferUi readOfferUi(ConfigurationSection s, Logger logger) {
        String raw = s.getString(OFFER_UI, OfferUi.DIALOG.name());
        OfferUi ui = OfferUi.parse(raw);
        if (ui == null) {
            logger.warning("games.augment." + OFFER_UI + ": unknown UI '" + raw + "', using DIALOG");
            return OfferUi.DIALOG;
        }
        return ui;
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

    /**
     * What every player gets when the farming starts: armor is worn, the rest goes to the inventory, in order.
     *
     * @param items the items with their amounts, in the configured order (empty when off)
     */
    public record StartKit(Map<org.bukkit.Material, Integer> items) {

        /** Plenty of food (farming is for gear, not for hunting). */
        static final List<String> DEFAULT_ITEMS = List.of("cooked_beef 64", "bread 32");

        public StartKit {
            items = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(items));
        }

        /** Nothing. */
        public static final StartKit NONE = new StartKit(Map.of());

        /** The kit gives a chestplate of iron or better: the players start in iron. */
        public boolean armored() {
            return items.keySet().stream().anyMatch(m -> m == org.bukkit.Material.IRON_CHESTPLATE
                    || m == org.bukkit.Material.DIAMOND_CHESTPLATE || m == org.bukkit.Material.NETHERITE_CHESTPLATE);
        }

        static StartKit read(ConfigurationSection s, Logger logger) {
            if (!s.getBoolean(START_KIT, true)) {
                return NONE;
            }
            List<String> raw = s.isList(START_KIT_ITEMS) ? s.getStringList(START_KIT_ITEMS) : DEFAULT_ITEMS;
            Map<org.bukkit.Material, Integer> items = new LinkedHashMap<>();
            for (String line : raw) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length == 0 || parts[0].isEmpty()) {
                    continue;
                }
                org.bukkit.Material material = org.bukkit.Material.matchMaterial(parts[0]);
                if (material == null || !material.isItem() || material.isAir()) {
                    logger.warning("games.augment." + START_KIT_ITEMS + ": unknown item '" + parts[0] + "', skipped");
                    continue;
                }
                int amount = 1;
                if (parts.length > 1) {
                    try {
                        amount = Integer.parseInt(parts[1]);
                    } catch (NumberFormatException e) {
                        logger.warning("games.augment." + START_KIT_ITEMS + ": bad amount in '" + line + "', using 1");
                    }
                }
                items.merge(material, Math.max(1, Math.min(64 * 36, amount)), Integer::sum);
            }
            return new StartKit(items);
        }
    }
}
