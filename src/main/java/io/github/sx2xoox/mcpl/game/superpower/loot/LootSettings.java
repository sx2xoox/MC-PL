package io.github.sx2xoox.mcpl.game.superpower.loot;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.jspecify.annotations.Nullable;

/**
 * The loot chest settings, {@code config.yml} → {@code games.superpower.loot.*}, read once per match like
 * {@link io.github.sx2xoox.mcpl.game.superpower.SuperpowerSettings}. Invalid values fall back to the defaults with a
 * console warning; invalid pool entries are skipped.
 *
 * @param enabled              loot chests in real matches
 * @param inPractice           loot chests in the ability practice too
 * @param chestsPerPlayer      chests per participant
 * @param chestsBase           chests on top of that
 * @param minChests            fewest chests
 * @param maxChests            most chests
 * @param referenceRadius      arena radius the counts are meant for; bigger arenas get proportionally more chests
 *                             (×{@value #MIN_SIZE_FACTOR}..×{@value #MAX_SIZE_FACTOR})
 * @param playerDistance       no chest closer than this (blocks) to where a player stands when the fight starts
 * @param normalWeight         chance weight of a normal chest
 * @param rareWeight           chance weight of a rare chest against {@code normalWeight}
 * @param normal               normal chests: item count and pool
 * @param rare                 rare chests: item count and pool
 * @param supply               supply drops: item count and pool
 * @param supplyMin            fewest supply drops when the border starts shrinking
 * @param supplyMax            most supply drops
 * @param playersPerSupply     one supply drop per this many participants (clamped to min..max)
 * @param supplyFallTicks      how long a supply drop takes to fall from the sky
 * @param crystalGauge         ultimate gauge % granted by a 궁극기 결정
 */
public record LootSettings(
        boolean enabled,
        boolean inPractice,
        double chestsPerPlayer,
        double chestsBase,
        int minChests,
        int maxChests,
        double referenceRadius,
        double playerDistance,
        int normalWeight,
        int rareWeight,
        Tier normal,
        Tier rare,
        Tier supply,
        int supplyMin,
        int supplyMax,
        int playersPerSupply,
        int supplyFallTicks,
        double crystalGauge
) {

    public static final String SECTION = "loot";
    public static final double MIN_SIZE_FACTOR = 0.5;
    public static final double MAX_SIZE_FACTOR = 2.0;
    /** Chest inventories have 27 slots. */
    public static final int MAX_ITEMS = 27;

    /**
     * The items of one kind of chest.
     *
     * @param minItems fewest pool entries per chest
     * @param maxItems most pool entries per chest
     * @param pool     the entries
     */
    public record Tier(int minItems, int maxItems, LootPool pool) {
    }

    public static final List<String> DEFAULT_NORMAL_POOL = List.of(
            "GOLDEN_APPLE 1 weight=10",
            "COOKED_BEEF 4-8 weight=14",
            "BREAD 4-6 weight=8",
            "ENDER_PEARL 1 weight=8",
            "BOW + ARROW 8-12 weight=6",
            "ARROW 6-10 weight=5",
            "SNOWBALL 8-16 weight=5",
            "POTION healing weight=8",
            "SPLASH_POTION healing weight=5",
            "POTION swiftness seconds=30 weight=7",
            "POTION strength seconds=20 weight=4");

    /** About one rare chest in three has a diamond piece (rarely two): 5 of 53 weight, 3-4 picks. */
    public static final List<String> DEFAULT_RARE_POOL = List.of(
            "DIAMOND_HELMET weight=1",
            "DIAMOND_CHESTPLATE weight=1",
            "DIAMOND_LEGGINGS weight=1",
            "DIAMOND_BOOTS weight=1",
            "DIAMOND_SWORD weight=1",
            "GOLDEN_APPLE 2 weight=10",
            "ENDER_PEARL 2 weight=8",
            "BOW + ARROW 12-16 weight=6",
            "POTION strength seconds=30 weight=6",
            "SPLASH_POTION healing level=2 weight=6",
            "ULTIMATE_CRYSTAL weight=6",
            "RECHARGE_CHARM weight=6");

    /** About every other supply drop has a diamond piece (0.7 on average): 8 of 65 weight, 4-5 picks. */
    public static final List<String> DEFAULT_SUPPLY_POOL = List.of(
            "DIAMOND_HELMET weight=2",
            "DIAMOND_CHESTPLATE weight=1",
            "DIAMOND_LEGGINGS weight=1",
            "DIAMOND_BOOTS weight=2",
            "DIAMOND_SWORD weight=2",
            "GOLDEN_APPLE 2-3 weight=12",
            "ENDER_PEARL 2-3 weight=8",
            "ULTIMATE_CRYSTAL 1-2 weight=10",
            "RECHARGE_CHARM 1-2 weight=10",
            "POTION strength seconds=45 weight=6",
            "SPLASH_POTION healing level=2 2 weight=6",
            "BOW + ARROW 16 weight=5");

    /** Settings with every default (no warnings). */
    public static LootSettings defaults(Logger logger) {
        MemoryConfiguration config = new MemoryConfiguration();
        declareDefaults(config);
        return read(config, logger);
    }

    /** The number of chests spread at the start of the fight. */
    public int chestCount(int players, double arenaRadius) {
        double size = Math.max(MIN_SIZE_FACTOR, Math.min(MAX_SIZE_FACTOR, arenaRadius / referenceRadius));
        int count = (int) Math.round((players * chestsPerPlayer + chestsBase) * size);
        return Math.max(minChests, Math.min(maxChests, count));
    }

    /** The number of supply drops for {@code players} participants still alive. */
    public int supplyCount(int players) {
        int count = (int) Math.ceil(Math.max(0, players) / (double) playersPerSupply);
        return Math.max(supplyMin, Math.min(supplyMax, count));
    }

    /** The settings of a tier. */
    public Tier tier(LootTier tier) {
        return switch (tier) {
            case NORMAL -> normal;
            case RARE -> rare;
            case SUPPLY -> supply;
        };
    }

    // =================================================================== config

    /**
     * Declares the defaults (with Korean comments) under {@code loot} of the given {@code games.superpower} defaults
     * section.
     */
    public static void declareDefaults(ConfigurationSection d) {
        String p = SECTION + ".";
        d.set(p + "enabled", true);
        d.setComments(SECTION, List.of(
                "보급 상자: 전투가 시작되면 경기장 곳곳에 상자가 나타나고, 자기장이 줄어들기 시작하면",
                "하늘에서 보급 상자가 떨어집니다. 상자는 우클릭으로 열고, 먼저 가져가는 사람이 임자입니다.",
                "모든 상자와 아이템은 경기가 끝나면 사라집니다."));
        d.setComments(p + "enabled", List.of("경기에 상자를 놓을지 여부"));
        d.set(p + "practice", false);
        d.setComments(p + "practice", List.of("능력 연습(/게임 능력 연습)에도 상자를 놓을지 여부"));

        d.set(p + "chests.per-player", 1.5);
        d.setComments(p + "chests", List.of(
                "전투 시작 때 놓는 상자 수 = (참가자 수 × per-player + base) × 경기장 크기 배율, min~max 사이",
                "경기장 크기 배율 = 경기장 반지름 ÷ reference-radius (0.5~2배)"));
        d.setComments(p + "chests.per-player", List.of("참가자 1명당 상자 수"));
        d.set(p + "chests.base", 2.0);
        d.setComments(p + "chests.base", List.of("기본으로 더하는 상자 수"));
        d.set(p + "chests.min", 3);
        d.setComments(p + "chests.min", List.of("최소 상자 수"));
        d.set(p + "chests.max", 20);
        d.setComments(p + "chests.max", List.of("최대 상자 수"));
        d.set(p + "chests.reference-radius", 60);
        d.setComments(p + "chests.reference-radius", List.of("위 수치가 맞춰진 경기장 반지름(블록)"));
        d.set(p + "chests.player-distance", 6.0);
        d.setComments(p + "chests.player-distance", List.of("전투 시작 때 플레이어가 서 있는 곳에서 최소 이만큼(블록) 떨어진 곳에만 놓습니다"));

        d.set(p + "chances.normal", 80);
        d.setComments(p + "chances", List.of("일반 상자와 희귀 상자가 나올 비율 (가중치)"));
        d.set(p + "chances.rare", 20);

        String poolHelp = "아이템 목록. 형식: \"아이템 [개수 또는 최소-최대] [weight=가중치]\" (가중치를 생략하면 "
                + LootEntry.DEFAULT_WEIGHT + ")";
        declareTier(d, LootTier.NORMAL, "2-3", DEFAULT_NORMAL_POOL, List.of(
                "일반 상자",
                "items = 상자 하나에 들어가는 목록 항목 수 (예: 2-3)",
                poolHelp,
                "  물약: \"POTION swiftness seconds=30\" (seconds = 효과 시간, level = 효과 레벨), SPLASH_POTION 은 투척용",
                "  여러 아이템을 함께: \"BOW + ARROW 8-12\"",
                "  초능력 전용: ULTIMATE_CRYSTAL (궁극기 결정), RECHARGE_CHARM (재충전 부적)"));
        declareTier(d, LootTier.RARE, "3-4", DEFAULT_RARE_POOL, List.of(
                "희귀 상자 (반짝이는 상자). 초능력 전용 아이템이 자주, 다이아몬드 장비가 가끔(기본값: 3개 중 1개꼴) 나옵니다."));
        declareTier(d, LootTier.SUPPLY, "4-5", DEFAULT_SUPPLY_POOL, List.of(
                "보급 상자: 자기장이 줄어들기 시작할 때 하늘에서 떨어지는 상자 (위치를 채팅으로 알립니다)",
                "기본값으로 두 개 중 하나꼴로 다이아몬드 장비가 들어 있습니다."));
        d.set(p + "supply.min", 1);
        d.setComments(p + "supply.min", List.of(
                "보급 상자 수 = 그때 남은 참가자 수 ÷ players-per-drop (올림), min~max 사이"));
        d.set(p + "supply.max", 2);
        d.set(p + "supply.players-per-drop", 4);
        d.set(p + "supply.fall-seconds", 2.0);
        d.setComments(p + "supply.fall-seconds", List.of("보급 상자가 하늘에서 떨어지는 시간(초)"));

        d.set(p + "ultimate-crystal.gauge", 25.0);
        d.setComments(p + "ultimate-crystal", List.of(
                "궁극기 결정: 우클릭하면 궁극기 게이지를 채웁니다.",
                "궁극기를 쓴 직후 재충전 대기 시간(ultimate.recharge-delay-seconds)과 침묵 중에는 쓸 수 없습니다.",
                "재충전 부적: 우클릭하면 액티브 스킬 쿨타임이 바로 끝납니다 (침묵 중에는 쓸 수 없음)."));
        d.setComments(p + "ultimate-crystal.gauge", List.of("채우는 게이지(%)"));
    }

    private static void declareTier(ConfigurationSection d, LootTier tier, String items, List<String> pool,
                                    List<String> comments) {
        String p = SECTION + "." + tier.key() + ".";
        d.set(p + "items", items);
        d.setComments(SECTION + "." + tier.key(), comments);
        d.set(p + "pool", new ArrayList<>(pool));
    }

    /**
     * Reads {@code loot.*} of the live {@code games.superpower} section; invalid values fall back to the defaults
     * with a warning.
     */
    public static LootSettings read(ConfigurationSection superpower, Logger logger) {
        ConfigurationSection s = superpower.getConfigurationSection(SECTION);
        if (s == null) {
            s = new MemoryConfiguration();
        }
        String path = "games.superpower." + SECTION + ".";
        int minChests = clamp(s.getInt("chests.min", 3), 0, 200);
        int maxChests = Math.max(minChests, clamp(s.getInt("chests.max", 20), 0, 200));
        int supplyMin = clamp(s.getInt("supply.min", 1), 0, 10);
        int supplyMax = Math.max(supplyMin, clamp(s.getInt("supply.max", 2), 0, 10));
        double fall = finite(s.getDouble("supply.fall-seconds", 2.0), 2.0);
        return new LootSettings(
                s.getBoolean("enabled", true),
                s.getBoolean("practice", false),
                Math.max(0, finite(s.getDouble("chests.per-player", 1.5), 1.5)),
                Math.max(0, finite(s.getDouble("chests.base", 2.0), 2.0)),
                minChests,
                maxChests,
                Math.max(1, finite(s.getDouble("chests.reference-radius", 60), 60)),
                Math.max(0, finite(s.getDouble("chests.player-distance", 6.0), 6.0)),
                clamp(s.getInt("chances.normal", 80), 0, 10_000),
                clamp(s.getInt("chances.rare", 20), 0, 10_000),
                tier(s, LootTier.NORMAL, 2, 3, DEFAULT_NORMAL_POOL, path, logger),
                tier(s, LootTier.RARE, 3, 4, DEFAULT_RARE_POOL, path, logger),
                tier(s, LootTier.SUPPLY, 4, 5, DEFAULT_SUPPLY_POOL, path, logger),
                supplyMin,
                supplyMax,
                clamp(s.getInt("supply.players-per-drop", 4), 1, 100),
                (int) Math.round(Math.max(0.5, Math.min(10.0, fall)) * 20),
                Math.max(0, Math.min(100, finite(s.getDouble("ultimate-crystal.gauge", 25.0), 25.0))));
    }

    private static Tier tier(ConfigurationSection s, LootTier tier, int defaultMin, int defaultMax,
                             List<String> defaultPool, String path, Logger logger) {
        String key = tier.key();
        int[] items = range(s.getString(key + ".items"), defaultMin, defaultMax, path + key + ".items", logger);
        List<?> lines = s.isList(key + ".pool") ? s.getList(key + ".pool") : defaultPool;
        return new Tier(items[0], items[1], LootPool.parse(lines, path + key + ".pool", logger));
    }

    /** {@code "2-3"} or {@code "3"} → {@code [min, max]} within 1..{@value #MAX_ITEMS}. */
    private static int[] range(@Nullable String text, int defaultMin, int defaultMax, String path, Logger logger) {
        if (text == null || text.isBlank()) {
            return new int[] {defaultMin, defaultMax};
        }
        String[] parts = text.trim().toLowerCase(Locale.ROOT).split("\\s*[-~]\\s*");
        try {
            int min = Integer.parseInt(parts[0].trim());
            int max = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : min;
            if (parts.length > 2 || min < 1 || max < min || max > MAX_ITEMS) {
                throw new NumberFormatException(text);
            }
            return new int[] {min, max};
        } catch (NumberFormatException e) {
            logger.warning(path + ": '" + text + "' is not a count like 2-3 (1.." + MAX_ITEMS + "), using "
                    + defaultMin + "-" + defaultMax);
            return new int[] {defaultMin, defaultMax};
        }
    }

    private static double finite(double value, double fallback) {
        return Double.isFinite(value) ? value : fallback;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
