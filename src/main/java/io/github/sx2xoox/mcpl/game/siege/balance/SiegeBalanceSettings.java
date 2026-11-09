package io.github.sx2xoox.mcpl.game.siege.balance;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * The 공성전 ability balance ({@code games.siege.balance.*}, docs/siege-v2.md §5): ability damage in 공성전 is
 * {@code ability-damage} × the ability's correction × {@code 1 + damage-per-level × (level − 1)} × the players'
 * {@link SiegeModifiers}. Normal 초능력전쟁 never reads it.
 *
 * <p>The per-ability corrections ({@code balance.abilities.<id>.skill} for the active and passive skills,
 * {@code .ultimate} for the ultimate, {@code .objective} for any ability damage on buildings, minions and monsters)
 * default to the bundled {@value #RESOURCE}, which {@code ./gradlew updateSiegeBalance} generates from the balance lab's
 * measurements ({@code ./gradlew siegeLab}) together with docs/siege-balance.md.
 *
 * @param enabled        false: abilities hit as in 초능력전쟁 (no global factor, corrections or level scaling)
 * @param abilityDamage  the global factor (0.65 = −35%)
 * @param damagePerLevel extra ability damage per level above 1 (0.075 = +7.5%, level 9 = +60%)
 * @param perks          whether the level 3/6/9 perks ({@link SiegePerks}) apply
 * @param tuning         whether the 공성전 param tuning ({@link SiegeTuning}) applies
 * @param corrections    per-ability corrections; abilities missing here use 1.0
 */
public record SiegeBalanceSettings(boolean enabled, double abilityDamage, double damagePerLevel, boolean perks,
                                   boolean tuning, Map<String, Correction> corrections) {

    /** The bundled generated corrections. */
    public static final String RESOURCE = "siege-balance.yml";
    public static final double DEFAULT_ABILITY_DAMAGE = 0.65;
    public static final double DEFAULT_DAMAGE_PER_LEVEL = 0.075;

    public static final String ENABLED = "balance.enabled";
    public static final String ABILITY_DAMAGE = "balance.ability-damage";
    public static final String DAMAGE_PER_LEVEL = "balance.damage-per-level";
    public static final String PERKS = "balance.level-perks";
    public static final String TUNING = "balance.tuning";
    public static final String ABILITIES = "balance.abilities";

    /**
     * One ability's correction: {@code skill} / {@code ultimate} on top of the global factor for hits on players,
     * {@code objective} for its hits on buildings, minions and monsters (which take no global factor).
     */
    public record Correction(double skill, double ultimate, double objective) {
        public static final Correction NONE = new Correction(1, 1, 1);

        /** The factor for a hit of the ultimate ({@code ultimate}) or of the other skills. */
        public double factor(boolean ultimate) {
            return ultimate ? this.ultimate : skill;
        }
    }

    public SiegeBalanceSettings {
        corrections = Collections.unmodifiableMap(new LinkedHashMap<>(corrections));
    }

    /** The settings with every default (the bundled corrections). */
    public static SiegeBalanceSettings defaults() {
        return new SiegeBalanceSettings(true, DEFAULT_ABILITY_DAMAGE, DEFAULT_DAMAGE_PER_LEVEL, true, true,
                bundledCorrections());
    }

    /** The correction of {@code abilityId} (1.0 / 1.0 when it has none). */
    public Correction correction(String abilityId) {
        return corrections.getOrDefault(abilityId, Correction.NONE);
    }

    /** Reads {@code games.siege}; missing values use the defaults, out-of-range ones are clamped. */
    public static SiegeBalanceSettings read(ConfigurationSection c) {
        Map<String, Correction> corrections = new LinkedHashMap<>(bundledCorrections());
        ConfigurationSection section = c.getConfigurationSection(ABILITIES);
        if (section != null) {
            for (String id : section.getKeys(false)) {
                Correction base = corrections.getOrDefault(id, Correction.NONE);
                corrections.put(id, new Correction(
                        factor(section.getDouble(id + ".skill", base.skill()), base.skill()),
                        factor(section.getDouble(id + ".ultimate", base.ultimate()), base.ultimate()),
                        factor(section.getDouble(id + ".objective", base.objective()), base.objective())));
            }
        }
        return new SiegeBalanceSettings(
                c.getBoolean(ENABLED, true),
                factor(c.getDouble(ABILITY_DAMAGE, DEFAULT_ABILITY_DAMAGE), DEFAULT_ABILITY_DAMAGE),
                Math.max(0, Math.min(1, finite(c.getDouble(DAMAGE_PER_LEVEL, DEFAULT_DAMAGE_PER_LEVEL),
                        DEFAULT_DAMAGE_PER_LEVEL))),
                c.getBoolean(PERKS, true),
                c.getBoolean(TUNING, true),
                corrections);
    }

    /** Declares the defaults (with Korean comments) merged into {@code config.yml} → {@code games.siege}. */
    public static void declareDefaults(ConfigurationSection d) {
        d.setComments("balance", List.of(
                "능력 밸런스 (공성전만, 초능력전쟁은 그대로). 능력 피해 = 능력 피해 배율 × 능력별 보정 × 레벨 보정 × 아이템·버프",
                "자세한 수치표: docs/siege-balance.md"));
        d.set(ENABLED, true);
        d.setComments(ENABLED, List.of("false 면 능력 피해를 초능력전쟁과 같게 (배율·보정·레벨 보정 없음)"));
        d.set(ABILITY_DAMAGE, DEFAULT_ABILITY_DAMAGE);
        d.setComments(ABILITY_DAMAGE, List.of("모든 능력 피해 배율 (0.65 = 35% 감소)"));
        d.set(DAMAGE_PER_LEVEL, DEFAULT_DAMAGE_PER_LEVEL);
        d.setComments(DAMAGE_PER_LEVEL, List.of("1레벨 위로 레벨마다 늘어나는 능력 피해 (0.075 = 7.5%, 9레벨 +60%)"));
        d.set(PERKS, true);
        d.setComments(PERKS, List.of("3·6·9레벨 추가 효과 (능력별 한 줄, 레벨업 때 안내)"));
        d.set(TUNING, true);
        d.setComments(TUNING, List.of(
                "공성전 전용 능력 수치 조정 (쿨타임·통제·보호막 등, 표: docs/siege-balance.md). false 면 abilities.yml 그대로"));
        YamlConfiguration bundled = bundled();
        ConfigurationSection abilities = bundled.getConfigurationSection("abilities");
        d.setComments(ABILITIES, List.of(
                "능력별 보정 (skill = 액티브·패시브, ultimate = 궁극기, objective = 건물·미니언·몬스터에게). 1 이 기본, 0.5 면 절반",
                "기본값은 ./gradlew updateSiegeBalance 가 밸런스 랩 측정값에서 계산합니다 (전체 점수를 중앙값 가까이, 폭딜 상한 안에서)"));
        if (abilities != null) {
            for (String id : abilities.getKeys(false)) {
                String path = ABILITIES + "." + id;
                d.set(path + ".skill", abilities.getDouble(id + ".skill", 1));
                d.set(path + ".ultimate", abilities.getDouble(id + ".ultimate", 1));
                d.set(path + ".objective", abilities.getDouble(id + ".objective", 1));
                List<String> comments = abilities.getComments(id);
                if (!comments.isEmpty()) {
                    d.setComments(path, comments);
                }
            }
        }
    }

    private static Map<String, Correction> bundledCorrections() {
        Map<String, Correction> corrections = new LinkedHashMap<>();
        ConfigurationSection abilities = bundled().getConfigurationSection("abilities");
        if (abilities != null) {
            for (String id : abilities.getKeys(false)) {
                corrections.put(id, new Correction(factor(abilities.getDouble(id + ".skill", 1), 1),
                        factor(abilities.getDouble(id + ".ultimate", 1), 1),
                        factor(abilities.getDouble(id + ".objective", 1), 1)));
            }
        }
        return corrections;
    }

    private static YamlConfiguration bundled() {
        YamlConfiguration yaml = new YamlConfiguration();
        try (InputStream in = SiegeBalanceSettings.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                return yaml;
            }
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                yaml.load(reader);
            }
        } catch (Exception e) {
            return new YamlConfiguration();
        }
        return yaml;
    }

    private static double factor(double value, double fallback) {
        return Math.max(0, Math.min(5, finite(value, fallback)));
    }

    private static double finite(double value, double fallback) {
        return Double.isFinite(value) ? value : fallback;
    }
}
