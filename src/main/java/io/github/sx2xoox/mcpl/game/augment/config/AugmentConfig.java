package io.github.sx2xoox.mcpl.game.augment.config;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import io.github.sx2xoox.mcpl.game.augment.api.AugmentInfo;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentRegistry;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentTier;
import io.github.sx2xoox.mcpl.game.augment.api.Params;
import io.github.sx2xoox.mcpl.game.augment.bonus.BonusCondition;
import io.github.sx2xoox.mcpl.util.Text;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jspecify.annotations.Nullable;

/**
 * {@code augments.yml}: every augment ({@code augments.<id>}: enabled + params) and every bonus condition
 * ({@code bonuses.<id>}: enabled + tier + params), each commented with its name and text (generated from the code).
 *
 * <p>On every {@link #load} missing entries and params are added with their defaults (and the file is saved),
 * comments are refreshed, unknown keys are kept but ignored, invalid values fall back to the defaults with a warning.
 * A file that cannot be parsed is left untouched and the previous settings stay in effect. A match keeps the
 * {@link Snapshot} it started with.
 */
public final class AugmentConfig {

    public static final String FILE_NAME = "augments.yml";
    static final String AUGMENTS = "augments";
    static final String BONUSES = "bonuses";
    static final String ENABLED = "enabled";
    static final String TIER = "tier";
    static final String PARAMS = "params";

    private static final List<String> HEADER = List.of(
            "─────────────────────────────────────────────────────────────",
            " 증강 야생전 증강 · 보너스 조건 설정",
            " enabled: false 로 끈 증강은 카드로 나오지 않고, 끈 보너스 조건은 달성해도 증강을 주지 않습니다.",
            " bonuses.<조건>.tier: 보너스로 받는 증강의 등급 (SILVER, GOLD, PRISM)",
            " params 의 수치를 바꾼 뒤 /게임 리로드 (진행 중인 경기는 다음 경기부터 적용)",
            " 단위: 시간 = 초, 거리 = 블록, 체력 = 체력 포인트 (2 = 하트 1칸), 퍼센트 = % 숫자",
            " 설명의 {이름} 은 같은 항목의 params 값으로 바뀌어 표시됩니다.",
            "─────────────────────────────────────────────────────────────");

    private final File file;
    private final Logger logger;
    private Snapshot snapshot = new Snapshot(Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
    private boolean loaded;

    public AugmentConfig(File file, Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    public File file() {
        return file;
    }

    /** The values read by the last {@link #load}. */
    public Snapshot snapshot() {
        return snapshot;
    }

    /**
     * (Re)reads the file, adds missing entries with defaults, refreshes the comments and saves if anything changed.
     *
     * @return false if the file could not be parsed (the previous settings, or the defaults, stay in effect)
     */
    public boolean load(AugmentRegistry registry) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().parseComments(true);
        boolean changed = !file.exists();
        if (file.exists()) {
            try {
                yaml.load(file);
            } catch (Exception e) {
                logger.log(Level.SEVERE, "Could not read " + file.getName() + "; keeping the "
                        + (loaded ? "previous" : "default") + " augment settings (the file is left untouched)", e);
                if (!loaded) {
                    snapshot = defaults(registry);
                }
                return false;
            }
        }
        if (!sameLines(HEADER, yaml.options().getHeader())) {
            yaml.options().setHeader(HEADER);
            changed = true;
        }
        Changes changes = new Changes(changed);

        ConfigurationSection augmentRoot = section(yaml, AUGMENTS, changes);
        Map<String, Boolean> augmentEnabled = new LinkedHashMap<>();
        Map<String, Params> augmentParams = new LinkedHashMap<>();
        for (AugmentRegistry.Entry entry : registry.all()) {
            AugmentInfo info = entry.info();
            ConfigurationSection section = entry(augmentRoot, info.id(), comments(info), changes);
            augmentEnabled.put(info.id(), enabled(section, AUGMENTS + "." + info.id(), changes));
            augmentParams.put(info.id(), params(section, info.id(), info.params(), changes));
        }

        ConfigurationSection bonusRoot = section(yaml, BONUSES, changes);
        Map<BonusCondition, Boolean> bonusEnabled = new EnumMap<>(BonusCondition.class);
        Map<BonusCondition, AugmentTier> bonusTier = new EnumMap<>(BonusCondition.class);
        Map<BonusCondition, Params> bonusParams = new EnumMap<>(BonusCondition.class);
        for (BonusCondition condition : BonusCondition.values()) {
            ConfigurationSection section = entry(bonusRoot, condition.id(), comments(condition), changes);
            bonusEnabled.put(condition, enabled(section, BONUSES + "." + condition.id(), changes));
            if (!section.isSet(TIER)) {
                section.set(TIER, condition.defaultTier().name());
                changes.mark();
            }
            AugmentTier tier = AugmentTier.parse(section.getString(TIER));
            if (tier == null) {
                logger.warning(FILE_NAME + ": " + BONUSES + "." + condition.id() + ".tier is not SILVER, GOLD or PRISM ('"
                        + section.get(TIER) + "'); using " + condition.defaultTier().name());
                tier = condition.defaultTier();
            }
            bonusTier.put(condition, tier);
            bonusParams.put(condition, params(section, condition.id(), condition.params(), changes));
        }

        snapshot = new Snapshot(augmentEnabled, augmentParams, bonusEnabled, bonusTier, bonusParams);
        loaded = true;
        if (changes.changed) {
            save(yaml);
        }
        return true;
    }

    /** Sets {@code augments.<id>.enabled} in the file and reloads (tooling and tests). */
    public void setEnabled(AugmentRegistry registry, String id, boolean value) {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        yaml.set(AUGMENTS + "." + id + "." + ENABLED, value);
        save(yaml);
        load(registry);
    }

    private static final class Changes {
        boolean changed;

        Changes(boolean changed) {
            this.changed = changed;
        }

        void mark() {
            changed = true;
        }
    }

    private static ConfigurationSection section(YamlConfiguration yaml, String key, Changes changes) {
        ConfigurationSection section = yaml.getConfigurationSection(key);
        if (section == null) {
            section = yaml.createSection(key);
            changes.mark();
        }
        return section;
    }

    private static ConfigurationSection entry(ConfigurationSection root, String id, List<String> comments,
                                              Changes changes) {
        ConfigurationSection section = root.getConfigurationSection(id);
        if (section == null) {
            section = root.createSection(id);
            changes.mark();
        }
        if (!sameLines(comments, root.getComments(id))) {
            root.setComments(id, comments);
            changes.mark();
        }
        return section;
    }

    private boolean enabled(ConfigurationSection section, String path, Changes changes) {
        if (!section.isSet(ENABLED)) {
            section.set(ENABLED, true);
            changes.mark();
            return true;
        }
        Object raw = section.get(ENABLED);
        if (raw instanceof Boolean value) {
            return value;
        }
        if (raw instanceof String text && (text.strip().equalsIgnoreCase("true") || text.strip().equalsIgnoreCase("false"))) {
            return Boolean.parseBoolean(text.strip());
        }
        logger.warning(FILE_NAME + ": " + path + ".enabled is not true/false ('" + raw + "'); treating it as true");
        return true;
    }

    private Params params(ConfigurationSection section, String id, Map<String, Double> declared, Changes changes) {
        ConfigurationSection values = section.getConfigurationSection(PARAMS);
        if (values == null) {
            values = section.createSection(PARAMS);
            changes.mark();
        }
        Map<String, Double> overrides = new LinkedHashMap<>();
        for (Map.Entry<String, Double> param : declared.entrySet()) {
            String key = param.getKey();
            double def = param.getValue();
            if (!values.isSet(key)) {
                values.set(key, def);
                changes.mark();
            } else {
                Double value = number(values.get(key));
                if (value == null) {
                    logger.warning(FILE_NAME + ": " + id + ".params." + key + " is not a number ('" + values.get(key)
                            + "'); using the default " + Params.format(def));
                } else {
                    overrides.put(key, value);
                }
            }
            List<String> inline = List.of("기본값 " + Params.format(def));
            if (!sameLines(inline, values.getInlineComments(key))) {
                values.setInlineComments(key, inline);
                changes.mark();
            }
        }
        return new Params(id, declared, overrides);
    }

    private void save(YamlConfiguration yaml) {
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                logger.warning("Could not create " + parent);
            }
            yaml.save(file);
        } catch (IOException e) {
            logger.log(Level.WARNING, "Could not save " + file, e);
        }
    }

    private static Snapshot defaults(AugmentRegistry registry) {
        Map<String, Boolean> enabled = new LinkedHashMap<>();
        Map<String, Params> params = new LinkedHashMap<>();
        for (AugmentRegistry.Entry entry : registry.all()) {
            enabled.put(entry.info().id(), true);
            params.put(entry.info().id(), entry.info().defaults());
        }
        Map<BonusCondition, Boolean> bonusEnabled = new EnumMap<>(BonusCondition.class);
        Map<BonusCondition, AugmentTier> bonusTier = new EnumMap<>(BonusCondition.class);
        Map<BonusCondition, Params> bonusParams = new EnumMap<>(BonusCondition.class);
        for (BonusCondition condition : BonusCondition.values()) {
            bonusEnabled.put(condition, true);
            bonusTier.put(condition, condition.defaultTier());
            bonusParams.put(condition, condition.defaults());
        }
        return new Snapshot(enabled, params, bonusEnabled, bonusTier, bonusParams);
    }

    private static boolean sameLines(List<String> expected, @Nullable List<String> actual) {
        if (actual == null || actual.size() != expected.size()) {
            return false;
        }
        for (int i = 0; i < expected.size(); i++) {
            String a = expected.get(i) == null ? "" : expected.get(i).strip();
            String b = actual.get(i) == null ? "" : actual.get(i).strip();
            if (!a.equals(b)) {
                return false;
            }
        }
        return true;
    }

    private static @Nullable Double number(@Nullable Object raw) {
        if (raw instanceof Number number) {
            double value = number.doubleValue();
            return Double.isFinite(value) ? value : null;
        }
        if (raw instanceof String text) {
            try {
                double value = Double.parseDouble(text.trim());
                return Double.isFinite(value) ? value : null;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /** The comment block above an augment entry (a null element is a blank line). */
    static List<String> comments(AugmentInfo info) {
        List<String> lines = new ArrayList<>();
        lines.add(null);
        lines.add(plain(info.name()) + " (" + info.id() + ") · " + info.tier().koreanName() + " · "
                + info.kind().koreanName());
        lines.add(plain(info.description()));
        return lines;
    }

    /** The comment block above a bonus condition entry. */
    static List<String> comments(BonusCondition condition) {
        List<String> lines = new ArrayList<>();
        lines.add(null);
        lines.add(condition.koreanName() + " (" + condition.id() + ")" + (condition.firstCome() ? " · 선착순 1명" : "")
                + " · 기본 등급 " + condition.defaultTier().koreanName());
        lines.add(plain(condition.description()));
        return lines;
    }

    private static String plain(String miniMessage) {
        return Text.miniMessage().stripTags(miniMessage).replace('\n', ' ');
    }

    /**
     * Immutable view of the effective settings.
     *
     * @param enabled      per augment id
     * @param params       effective params per augment id
     * @param bonusEnabled per condition
     * @param bonusTier    tier of the extra augment per condition
     * @param bonusParams  effective params per condition
     */
    public record Snapshot(Map<String, Boolean> enabled, Map<String, Params> params,
                           Map<BonusCondition, Boolean> bonusEnabled, Map<BonusCondition, AugmentTier> bonusTier,
                           Map<BonusCondition, Params> bonusParams) {

        public Snapshot {
            enabled = Collections.unmodifiableMap(new LinkedHashMap<>(enabled));
            params = Collections.unmodifiableMap(new LinkedHashMap<>(params));
            bonusEnabled = Collections.unmodifiableMap(new LinkedHashMap<>(bonusEnabled));
            bonusTier = Collections.unmodifiableMap(new LinkedHashMap<>(bonusTier));
            bonusParams = Collections.unmodifiableMap(new LinkedHashMap<>(bonusParams));
        }

        public boolean isEnabled(String augmentId) {
            return enabled.getOrDefault(augmentId, false);
        }

        /** Effective params of an augment; its defaults if this snapshot does not know it. */
        public Params params(AugmentInfo info) {
            Params configured = params.get(info.id());
            return configured != null ? configured : info.defaults();
        }

        public boolean isEnabled(BonusCondition condition) {
            return bonusEnabled.getOrDefault(condition, true);
        }

        public AugmentTier tier(BonusCondition condition) {
            return bonusTier.getOrDefault(condition, condition.defaultTier());
        }

        public Params params(BonusCondition condition) {
            Params configured = bonusParams.get(condition);
            return configured != null ? configured : condition.defaults();
        }
    }
}
