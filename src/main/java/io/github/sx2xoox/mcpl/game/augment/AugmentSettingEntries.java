package io.github.sx2xoox.mcpl.game.augment;

import java.util.List;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;

import io.github.sx2xoox.mcpl.core.config.HubConfig;
import io.github.sx2xoox.mcpl.core.config.Setting;
import io.github.sx2xoox.mcpl.core.config.SettingsSection;
import io.github.sx2xoox.mcpl.core.ui.Messages;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;

/**
 * The 증강 야생전 entries of the settings menu and {@code /게임 설정 증강 ...} ({@link AugmentGame#settings()}):
 * match flow and border, the match world, augments. Every entry reads the live {@code games.augment} section and
 * writes through {@link HubConfig#setGameValue} with the ranges of {@link AugmentSettings#read}; a match reads the
 * section when it is started, so changes apply to the next match. Texts: {@code augment.yml} → {@code settings.*}.
 */
final class AugmentSettingEntries {

    private static final String WORD = "증강";
    private static final String ENGLISH = "augment";

    private final HubConfig config;
    private final Messages messages;
    private final ConfigurationSection defaults = new MemoryConfiguration();

    private AugmentSettingEntries(AugmentGame game) {
        this.config = game.hub().config();
        this.messages = game.messages();
        game.configDefaults(defaults);
    }

    static List<SettingsSection> create(AugmentGame game) {
        return new AugmentSettingEntries(game).sections();
    }

    private List<SettingsSection> sections() {
        Setting farming = seconds("augment.farming", "파밍", "farming", Material.WOODEN_PICKAXE, "settings.farming",
                AugmentSettings.FARMING, AugmentSettings.MAX_PHASE_SECONDS, 60, 300);
        Setting borderDelay = seconds("augment.border-delay", "보더시작", "border-delay", Material.LIGHT_BLUE_STAINED_GLASS,
                "settings.border-delay", AugmentSettings.BORDER_DELAY, AugmentSettings.MAX_PHASE_SECONDS, 10, 60);
        Setting borderShrink = seconds("augment.border-shrink", "보더축소", "border-shrink", Material.BLUE_STAINED_GLASS,
                "settings.border-shrink", AugmentSettings.BORDER_SHRINK, AugmentSettings.MAX_PHASE_SECONDS, 60, 300);
        Setting borderFinal = number("augment.border-final", "보더최종", "border-final", Material.PURPLE_STAINED_GLASS,
                "settings.border-final", AugmentSettings.BORDER_FINAL)
                .range(2, AugmentSettings.MAX_FINAL_SIZE).steps(2, 10).integer().unit(Setting.Unit.BLOCKS)
                .number(decimal(AugmentSettings.BORDER_FINAL), store(AugmentSettings.BORDER_FINAL),
                        defaults.getDouble(AugmentSettings.BORDER_FINAL));
        Setting invulnerable = toggle("augment.farming-invulnerable", "파밍무적", "farming-invulnerable",
                Material.SHIELD, "settings.farming-invulnerable", AugmentSettings.FARMING_INVULNERABLE);
        Setting noHunger = toggle("augment.farming-no-hunger", "파밍배고픔없음", "farming-no-hunger",
                Material.COOKED_BEEF, "settings.farming-no-hunger", AugmentSettings.FARMING_NO_HUNGER);
        Setting glow = toggle("augment.glow", "발광", "glow", Material.GLOW_INK_SAC, "settings.glow",
                AugmentSettings.GLOW);

        Setting radius = number("augment.radius", "반경", "radius", Material.FILLED_MAP, "settings.radius",
                AugmentSettings.RADIUS)
                .range(AugmentSettings.MIN_RADIUS, AugmentSettings.MAX_RADIUS).steps(25, 100).integer()
                .unit(Setting.Unit.BLOCKS)
                .number(integer(AugmentSettings.RADIUS), store(AugmentSettings.RADIUS),
                        defaults.getInt(AugmentSettings.RADIUS));
        Setting structures = toggle("augment.structures", "구조물", "structures", Material.MOSSY_COBBLESTONE,
                "settings.structures", AugmentSettings.STRUCTURES);
        Setting pregenerate = toggle("augment.pregenerate", "미리생성", "pregenerate", Material.GRASS_BLOCK,
                "settings.pregenerate", AugmentSettings.PREGENERATE);

        Setting choice = seconds("augment.choice", "선택시간", "choice", Material.CLOCK, "settings.choice",
                AugmentSettings.CHOICE, AugmentSettings.MAX_CHOICE_SECONDS, 5, 30);
        Setting ui = Setting.builder("augment.ui").command(List.of(WORD, "선택화면"), List.of(ENGLISH, "ui"))
                .icon(Material.ITEM_FRAME).text(messages, "settings.ui").path(path(AugmentSettings.OFFER_UI))
                .readWhenMatchOpens()
                .choice(List.of(
                                new Setting.Choice.Option(AugmentSettings.OfferUi.DIALOG.name(),
                                        List.of("대화창", "다이얼로그"), List.of("dialog")),
                                new Setting.Choice.Option(AugmentSettings.OfferUi.CHEST.name(),
                                        List.of("상자", "메뉴"), List.of("chest"))),
                        () -> live().getString(AugmentSettings.OFFER_UI),
                        value -> config.setGameValue(AugmentGame.ID, AugmentSettings.OFFER_UI, value),
                        defaults.getString(AugmentSettings.OFFER_UI, AugmentSettings.OfferUi.DIALOG.name()));
        Setting rerolls = number("augment.rerolls", "리롤", "rerolls", Material.ENDER_EYE, "settings.rerolls",
                AugmentSettings.REROLLS)
                .range(0, AugmentSettings.MAX_REROLLS).steps(1, 3).integer().unit(Setting.Unit.TIMES)
                .number(integer(AugmentSettings.REROLLS), store(AugmentSettings.REROLLS),
                        defaults.getInt(AugmentSettings.REROLLS));
        Setting bonus = toggle("augment.bonus", "보너스", "bonus", Material.TOTEM_OF_UNDYING, "settings.bonus",
                AugmentSettings.BONUS);

        return List.of(
                new SettingsSection("augment.match", Material.AMETHYST_CLUSTER, messages, "settings.section.match",
                        List.of(farming, invulnerable, noHunger, borderDelay, borderShrink, borderFinal, glow)),
                new SettingsSection("augment.world", Material.GRASS_BLOCK, messages, "settings.section.world",
                        List.of(radius, structures, pregenerate)),
                new SettingsSection("augment.augments", Material.ENCHANTED_BOOK, messages, "settings.section.augments",
                        List.of(choice, ui, rerolls, bonus)));
    }

    // =================================================================== helpers

    private ConfigurationSection live() {
        return config.gameSection(AugmentGame.ID);
    }

    private static String path(String key) {
        return "games." + AugmentGame.ID + "." + key;
    }

    private Setting.Builder number(String id, String korean, String english, Material icon, String text, String key) {
        return Setting.builder(id).command(List.of(WORD, korean), List.of(ENGLISH, english)).icon(icon)
                .text(messages, text).path(path(key)).readWhenMatchOpens();
    }

    private Setting seconds(String id, String korean, String english, Material icon, String text, String key,
                            int max, int step, int bigStep) {
        double min = key.equals(AugmentSettings.CHOICE) ? AugmentSettings.MIN_CHOICE_SECONDS : 0;
        return number(id, korean, english, icon, text, key)
                .range(min, max).steps(step, bigStep).integer().unit(Setting.Unit.SECONDS)
                .number(decimal(key), store(key), defaults.getDouble(key));
    }

    private Setting toggle(String id, String korean, String english, Material icon, String text, String key) {
        boolean fallback = defaults.getBoolean(key);
        return Setting.builder(id).command(List.of(WORD, korean), List.of(ENGLISH, english)).icon(icon)
                .text(messages, text).path(path(key)).readWhenMatchOpens()
                .toggle(() -> live().getBoolean(key, fallback),
                        value -> config.setGameValue(AugmentGame.ID, key, value), fallback);
    }

    private DoubleSupplier decimal(String key) {
        double fallback = defaults.getDouble(key);
        return () -> live().getDouble(key, fallback);
    }

    private DoubleSupplier integer(String key) {
        int fallback = defaults.getInt(key);
        return () -> live().getInt(key, fallback);
    }

    /** Whole numbers are stored as integers, so the file keeps {@code 20} rather than {@code 20.0}. */
    private DoubleConsumer store(String key) {
        return value -> config.setGameValue(AugmentGame.ID, key, (int) Math.round(value));
    }
}
