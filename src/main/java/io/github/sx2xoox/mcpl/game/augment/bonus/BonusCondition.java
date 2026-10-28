package io.github.sx2xoox.mcpl.game.augment.bonus;

import java.util.LinkedHashMap;
import java.util.Map;

import io.github.sx2xoox.mcpl.game.augment.api.AugmentTier;
import io.github.sx2xoox.mcpl.game.augment.api.Params;
import org.bukkit.Material;

/**
 * Special achievements that grant one extra augment (pick 1 of 3 of the condition's tier). Each counts once per
 * player per match; {@link #firstCome()} ones only for the first player of the match. The numbers and the tier can be
 * changed in {@code augments.yml} ({@code bonuses.<id>}). Detected by
 * {@link io.github.sx2xoox.mcpl.game.augment.runtime.BonusTracker}.
 */
public enum BonusCondition {

    FIRST_BLOOD("first_blood", "첫 번째 피", AugmentTier.GOLD, true, Material.REDSTONE,
            "이번 경기에서 처음으로 다른 플레이어를 처치합니다.", Map.of()),
    ABYSS_MINER("abyss_miner", "심연의 광부", AugmentTier.GOLD, false, Material.DEEPSLATE_DIAMOND_ORE,
            "Y {max_y} 아래에서 다이아몬드 광석을 캡니다.", params("max_y", -40)),
    MIRACLE_LANDING("miracle_landing", "기적의 착지", AugmentTier.SILVER, false, Material.WATER_BUCKET,
            "{min_fall}블록 이상 떨어지고도 살아남습니다: 물에 착지하거나(물 양동이 MLG), 체력이 {max_health} "
                    + "이하로 남은 채로 (체력 2 = 하트 1칸).",
            params("min_fall", 20, "max_health", 4)),
    IRON_ARTISAN("iron_artisan", "철의 장인", AugmentTier.SILVER, true, Material.IRON_CHESTPLATE,
            "가장 먼저 철 이상(철·다이아몬드·네더라이트) 갑옷 네 부위를 모두 입습니다.", Map.of()),
    TREASURE_HUNTER("treasure_hunter", "보물 사냥꾼", AugmentTier.SILVER, false, Material.CHEST,
            "자연적으로 생성된 보물 상자(마을, 폐광, 난파선, 사막 사원 …)를 처음 엽니다.", Map.of()),
    COMEBACK("comeback", "역전의 명수", AugmentTier.GOLD, false, Material.TOTEM_OF_UNDYING,
            "나보다 체력이 많던 상대(최근 {window}초 안의 교전 기준)를 처치하거나, 내 체력이 {low_health} 이하일 때"
                    + " 플레이어를 처치합니다 (체력 2 = 하트 1칸).",
            params("window", 30, "low_health", 6)),
    MONSTER_HUNTER("monster_hunter", "몬스터 사냥꾼", AugmentTier.SILVER, false, Material.ZOMBIE_HEAD,
            "파밍 시간 동안 적대적 몹을 {kills}마리 처치합니다.", params("kills", 10)),
    MAGE_PATH("mage_path", "마법사의 길", AugmentTier.SILVER, false, Material.ENCHANTING_TABLE,
            "마법 부여대에서 처음으로 아이템에 마법을 부여합니다.", Map.of());

    private final String id;
    private final String koreanName;
    private final AugmentTier defaultTier;
    private final boolean firstCome;
    private final Material icon;
    private final String description;
    private final Map<String, Double> params;

    BonusCondition(String id, String koreanName, AugmentTier defaultTier, boolean firstCome, Material icon,
                   String description, Map<String, Double> params) {
        this.id = id;
        this.koreanName = koreanName;
        this.defaultTier = defaultTier;
        this.firstCome = firstCome;
        this.icon = icon;
        this.description = description;
        this.params = params;
    }

    private static Map<String, Double> params(Object... keyValues) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            out.put((String) keyValues[i], ((Number) keyValues[i + 1]).doubleValue());
        }
        return out;
    }

    /** Stable lowercase id ({@code augments.yml}, messages). */
    public String id() {
        return id;
    }

    public String koreanName() {
        return koreanName;
    }

    /** The tier of the extra augment unless {@code augments.yml} says otherwise. */
    public AugmentTier defaultTier() {
        return defaultTier;
    }

    /** Only the first player of the match to do it gets the augment. */
    public boolean firstCome() {
        return firstCome;
    }

    public Material icon() {
        return icon;
    }

    /** Player-facing text with {@code {param}} placeholders. */
    public String description() {
        return description;
    }

    /** Declared params with their defaults (in declaration order). */
    public Map<String, Double> params() {
        return params;
    }

    public Params defaults() {
        return new Params(id, params, Map.of());
    }

    public static BonusCondition byId(String id) {
        for (BonusCondition condition : values()) {
            if (condition.id.equals(id)) {
                return condition;
            }
        }
        throw new IllegalArgumentException("Unknown bonus condition: " + id);
    }
}
