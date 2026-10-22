package io.github.sx2xoox.mcpl.game.superpower.config;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityParams;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityRegistry;
import io.github.sx2xoox.mcpl.util.Text;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jspecify.annotations.Nullable;

/**
 * {@code abilities.yml}: one entry per registered ability with an {@code enabled} flag and every declared param, each
 * entry commented with the ability name and its skill descriptions (generated from {@link AbilityInfo}).
 *
 * <pre>
 * abilities:
 *   # 화염술사 (pyro) · 난이도 ★☆☆
 *   # 패시브 불꽃의 몸: ...
 *   pyro:
 *     enabled: true
 *     params:
 *       cooldown: 8.0 # 기본값 8
 * </pre>
 *
 * <p>On every {@link #load} missing entries and params are added with their defaults (and the file is saved), comments
 * are refreshed, and unknown abilities or params are kept in the file but ignored. Invalid numbers fall back to the
 * default with a warning. A file that cannot be parsed at all (a YAML syntax error) is left untouched and the previous
 * settings stay in effect. Readers get an immutable {@link Snapshot}; a match keeps the snapshot it started with.
 */
public final class AbilityConfig {

    public static final String FILE_NAME = "abilities.yml";
    static final String ROOT = "abilities";
    static final String ENABLED = "enabled";
    static final String PARAMS = "params";

    private static final List<String> HEADER = List.of(
            "─────────────────────────────────────────────────────────────",
            " 초능력전쟁 능력 설정",
            " enabled: false 로 끈 능력은 배정되지 않습니다.",
            " params 의 수치를 바꾼 뒤 /게임 리로드 (진행 중인 경기는 다음 경기부터 적용)",
            " 단위: 시간 = 초, 거리 = 블록, 피해·회복 = 체력 포인트 (20 = 하트 10칸)",
            " 설명의 {이름} 은 같은 능력의 params 값으로 바뀌어 표시됩니다.",
            "─────────────────────────────────────────────────────────────");

    private final File file;
    private final Logger logger;
    private Snapshot snapshot = new Snapshot(Map.of(), Map.of());
    /** True once the file was read successfully (a later broken file then keeps the last good settings). */
    private boolean loaded;

    public AbilityConfig(File file, Logger logger) {
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
     * (Re)reads the file, adds missing entries/params with defaults, refreshes the comments and saves the file if
     * anything changed. Never throws for a broken file: the error is logged, the file is left untouched and the
     * previous settings stay in effect (the defaults if it was never read successfully).
     *
     * @return false if the file could not be parsed
     */
    public boolean load(AbilityRegistry registry) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().parseComments(true);
        boolean changed = !file.exists();
        if (file.exists()) {
            try {
                yaml.load(file);
            } catch (Exception e) {
                logger.log(Level.SEVERE, "Could not read " + file.getName() + "; keeping the "
                        + (loaded ? "previous" : "default") + " ability settings (the file is left untouched)", e);
                snapshot = loaded ? withDefaultsFor(snapshot, registry) : defaults(registry);
                return false;
            }
        }
        Snapshot previous = loaded ? snapshot : null;
        if (!sameLines(HEADER, yaml.options().getHeader())) {
            yaml.options().setHeader(HEADER);
            changed = true;
        }
        ConfigurationSection root = yaml.getConfigurationSection(ROOT);
        if (root == null) {
            root = yaml.createSection(ROOT);
            changed = true;
        }

        Map<String, Boolean> enabled = new LinkedHashMap<>();
        Map<String, AbilityParams> params = new LinkedHashMap<>();
        for (AbilityRegistry.Entry entry : registry.all()) {
            AbilityInfo info = entry.info();
            String id = info.id();
            ConfigurationSection section = root.getConfigurationSection(id);
            if (section == null) {
                section = root.createSection(id);
                changed = true;
            }
            List<String> comments = comments(info);
            if (!sameLines(comments, root.getComments(id))) {
                root.setComments(id, comments);
                changed = true;
            }
            Boolean on = bool(section.get(ENABLED));
            if (!section.isSet(ENABLED)) {
                section.set(ENABLED, true);
                changed = true;
                on = true;
            } else if (on == null) {
                // Keep the server owner's value in the file; only this load falls back.
                boolean fallback = previous == null || !previous.enabled().containsKey(id) || previous.isEnabled(id);
                logger.warning(FILE_NAME + ": " + id + ".enabled is not true/false ('" + section.get(ENABLED)
                        + "'); treating it as " + fallback);
                on = fallback;
            }
            enabled.put(id, on);

            ConfigurationSection values = section.getConfigurationSection(PARAMS);
            if (values == null) {
                values = section.createSection(PARAMS);
                changed = true;
            }
            Map<String, Double> overrides = new LinkedHashMap<>();
            for (Map.Entry<String, Double> param : info.params().entrySet()) {
                String key = param.getKey();
                double def = param.getValue();
                if (!values.isSet(key)) {
                    values.set(key, def);
                    changed = true;
                } else {
                    Double value = number(values.get(key));
                    if (value == null) {
                        logger.warning(FILE_NAME + ": " + id + ".params." + key + " is not a number ('"
                                + values.get(key) + "'); using the default " + AbilityInfo.formatNumber(def));
                    } else {
                        overrides.put(key, value);
                    }
                }
                List<String> inline = List.of("기본값 " + AbilityInfo.formatNumber(def));
                if (!sameLines(inline, values.getInlineComments(key))) {
                    values.setInlineComments(key, inline);
                    changed = true;
                }
            }
            params.put(id, new AbilityParams(info, overrides));
        }
        snapshot = new Snapshot(enabled, params);
        loaded = true;
        if (changed) {
            save(yaml);
        }
        return true;
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

    /** Sets {@code abilities.<id>.enabled} in the file and reloads (used by tooling/tests). */
    public void setEnabled(AbilityRegistry registry, String id, boolean value) {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        yaml.set(ROOT + "." + id + "." + ENABLED, value);
        save(yaml);
        load(registry);
    }

    /** {@code kept}, plus defaults for abilities registered since it was read. */
    private static Snapshot withDefaultsFor(Snapshot kept, AbilityRegistry registry) {
        Map<String, Boolean> enabled = new LinkedHashMap<>(kept.enabled());
        Map<String, AbilityParams> params = new LinkedHashMap<>(kept.params());
        for (AbilityRegistry.Entry entry : registry.all()) {
            String id = entry.info().id();
            if (!enabled.containsKey(id) || params.get(id) == null || params.get(id).info() != entry.info()) {
                enabled.putIfAbsent(id, true);
                params.put(id, AbilityParams.defaults(entry.info()));
            }
        }
        return new Snapshot(enabled, params);
    }

    private static Snapshot defaults(AbilityRegistry registry) {
        Map<String, Boolean> enabled = new LinkedHashMap<>();
        Map<String, AbilityParams> params = new LinkedHashMap<>();
        for (AbilityRegistry.Entry entry : registry.all()) {
            enabled.put(entry.info().id(), true);
            params.put(entry.info().id(), AbilityParams.defaults(entry.info()));
        }
        return new Snapshot(enabled, params);
    }

    /** Compares comment blocks; Bukkit reads blank comment lines back as null or "". */
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

    /** {@code true}/{@code false}, also written as a string ({@code "false"}); null for anything else. */
    private static @Nullable Boolean bool(@Nullable Object raw) {
        if (raw instanceof Boolean value) {
            return value;
        }
        if (raw instanceof String text) {
            String value = text.strip();
            if (value.equalsIgnoreCase("true")) {
                return true;
            }
            if (value.equalsIgnoreCase("false")) {
                return false;
            }
        }
        return null;
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

    /** The comment block written above an ability entry (a null element is a blank line). */
    static List<String> comments(AbilityInfo info) {
        List<String> lines = new ArrayList<>();
        lines.add(null); // a blank line between entries
        lines.add(plain(info.name()) + " (" + info.id() + ") · 난이도 " + "★".repeat(info.difficulty())
                + "☆".repeat(3 - info.difficulty()));
        lines.add("패시브 " + plain(info.passive().name()) + ": " + plain(info.passive().description()));
        lines.add("액티브 " + plain(info.active().name()) + " (쿨타임 {cooldown}초): "
                + plain(info.active().description()));
        lines.add("궁극기 " + plain(info.ultimate().name()) + ": " + plain(info.ultimate().description()));
        return lines;
    }

    private static String plain(String miniMessage) {
        return Text.miniMessage().stripTags(miniMessage).replace('\n', ' ');
    }

    /**
     * Immutable view of the effective ability settings.
     *
     * @param enabled per ability id (registered abilities only)
     * @param params  effective params per ability id
     */
    public record Snapshot(Map<String, Boolean> enabled, Map<String, AbilityParams> params) {

        public Snapshot {
            enabled = Collections.unmodifiableMap(new LinkedHashMap<>(enabled));
            params = Collections.unmodifiableMap(new LinkedHashMap<>(params));
        }

        public boolean isEnabled(String id) {
            return enabled.getOrDefault(id, false);
        }

        /** Ids of the enabled abilities, in registration order. */
        public List<String> enabledIds() {
            List<String> ids = new ArrayList<>();
            enabled.forEach((id, on) -> {
                if (on) {
                    ids.add(id);
                }
            });
            return ids;
        }

        /** Effective params of a registered ability; the declared defaults if the ability is unknown here. */
        public AbilityParams params(AbilityInfo info) {
            AbilityParams configured = params.get(info.id());
            return configured != null && configured.info() == info ? configured : AbilityParams.defaults(info);
        }
    }
}
