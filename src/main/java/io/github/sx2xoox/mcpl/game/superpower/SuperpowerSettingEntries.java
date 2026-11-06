package io.github.sx2xoox.mcpl.game.superpower;

import java.util.List;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;

import io.github.sx2xoox.mcpl.core.config.HubConfig;
import io.github.sx2xoox.mcpl.core.config.Setting;
import io.github.sx2xoox.mcpl.core.config.SettingsSection;
import io.github.sx2xoox.mcpl.core.ui.Messages;
import io.github.sx2xoox.mcpl.game.superpower.loot.LootSettings;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;

/**
 * The 초능력전쟁 entries of the settings menu and {@code /게임 설정} ({@link SuperpowerGame#settings()}): match flow
 * and border, the sky (day to night, rain), effects, loot chests. Every entry reads the live {@code games.superpower}
 * section and writes through {@link HubConfig#setGameValue}, with the ranges of {@link SuperpowerSettings#read} and
 * {@link LootSettings#read}; a match reads the section when it starts, so changes apply to the next match. Texts:
 * {@code superpower.yml} → {@code settings.*}.
 */
final class SuperpowerSettingEntries {

    /** Id of the 능력 배정 방식 entry ({@link SuperpowerGame#modeSetting()}). */
    static final String MODE = "superpower.mode";

    private final HubConfig config;
    private final Messages messages;
    /** The declared defaults ({@link SuperpowerGame#configDefaults}), for the menu's "기본값" and resets. */
    private final ConfigurationSection defaults = new MemoryConfiguration();

    private SuperpowerSettingEntries(SuperpowerGame game) {
        this.config = game.hub().config();
        this.messages = game.messages();
        game.configDefaults(defaults);
    }

    static List<SettingsSection> create(SuperpowerGame game) {
        return new SuperpowerSettingEntries(game).sections(game);
    }

    private List<SettingsSection> sections(SuperpowerGame game) {
        Setting mode = Setting.builder(MODE)
                .command("선택방식", "mode")
                .icon(Material.ENCHANTING_TABLE)
                .text(messages, "settings.mode")
                .path(path(SuperpowerSettings.MODE))
                .choice(List.of(
                                new Setting.Choice.Option(SelectionMode.RANDOM.name(), List.of("무작위", "랜덤"),
                                        List.of("random")),
                                new Setting.Choice.Option(SelectionMode.CHOICE.name(), List.of("선택"),
                                        List.of("choice"))),
                        () -> game.selectionMode().name(),
                        value -> {
                            SelectionMode parsed = SelectionMode.parse(value);
                            if (parsed != null) {
                                game.setSelectionMode(parsed);
                            }
                        },
                        defaults.getString(SuperpowerSettings.MODE, SelectionMode.RANDOM.name()));
        Setting rerolls = number("superpower.rerolls", "리롤", "rerolls", Material.ENDER_EYE, "settings.rerolls",
                SuperpowerSettings.REROLLS, true)
                .range(0, SuperpowerSettings.MAX_REROLLS).steps(1, 3).unit(Setting.Unit.TIMES)
                .number(integer(SuperpowerSettings.REROLLS), store(SuperpowerSettings.REROLLS, true),
                        defaults.getInt(SuperpowerSettings.REROLLS));
        Setting prep = seconds("superpower.prep", List.of("준비시간"), List.of("prep"), Material.SHIELD,
                "settings.prep", SuperpowerSettings.PREP_SECONDS, SuperpowerSettings.MAX_PREP_SECONDS, 5, 30);
        Setting timeLimit = seconds("superpower.time-limit", List.of("제한시간"), List.of("time-limit"),
                Material.CLOCK, "settings.time-limit", SuperpowerSettings.TIME_LIMIT_SECONDS,
                SuperpowerSettings.MAX_TIME_LIMIT_SECONDS, 60, 300);
        Setting borderDelay = seconds("superpower.border-delay", List.of("자기장", "시작"), List.of("border", "delay"),
                Material.LIGHT_BLUE_STAINED_GLASS, "settings.border-delay", SuperpowerSettings.BORDER_DELAY,
                SuperpowerSettings.MAX_BORDER_SECONDS, 10, 60);
        Setting borderShrink = seconds("superpower.border-shrink", List.of("자기장", "축소"),
                List.of("border", "shrink"), Material.BLUE_STAINED_GLASS, "settings.border-shrink",
                SuperpowerSettings.BORDER_SHRINK, SuperpowerSettings.MAX_BORDER_SECONDS, 10, 60);
        Setting borderFinal = Setting.builder("superpower.border-final")
                .command(List.of("자기장", "최종"), List.of("border", "final"))
                .icon(Material.PURPLE_STAINED_GLASS)
                .text(messages, "settings.border-final")
                .path(path(SuperpowerSettings.BORDER_FINAL))
                .range(1, SuperpowerSettings.MAX_BORDER_FINAL).steps(2, 10).integer().unit(Setting.Unit.BLOCKS)
                .number(decimal(SuperpowerSettings.BORDER_FINAL), store(SuperpowerSettings.BORDER_FINAL, true),
                        defaults.getDouble(SuperpowerSettings.BORDER_FINAL));

        Setting dayNight = toggle("superpower.day-night", "밤낮", "day-night", Material.DAYLIGHT_DETECTOR,
                "settings.day-night", SuperpowerSettings.SKY_DAY_NIGHT);
        double rainFallback = defaults.getDouble(SuperpowerSettings.SKY_RAIN_CHANCE);
        Setting rainChance = Setting.builder("superpower.rain-chance")
                .command("비확률", "rain-chance")
                .icon(Material.WATER_BUCKET)
                .text(messages, "settings.rain-chance")
                .path(path(SuperpowerSettings.SKY_RAIN_CHANCE))
                .range(0, 100).steps(5, 25).integer().unit(Setting.Unit.PERCENT)
                // Shown and typed in percent, stored as 0..1 like the reader expects.
                .number(() -> 100 * SuperpowerSettings.rainChance(
                                live().getDouble(SuperpowerSettings.SKY_RAIN_CHANCE, rainFallback)),
                        value -> config.setGameValue(SuperpowerGame.ID, SuperpowerSettings.SKY_RAIN_CHANCE,
                                Math.round(value) / 100.0),
                        100 * rainFallback);
        Setting rainMin = rainSeconds("superpower.rain-min", "최소", "min", Material.LIGHT_BLUE_DYE,
                "settings.rain-min", SuperpowerSettings.SKY_RAIN_MIN);
        Setting rainMax = rainSeconds("superpower.rain-max", "최대", "max", Material.BLUE_DYE,
                "settings.rain-max", SuperpowerSettings.SKY_RAIN_MAX);

        Setting particles = Setting.builder("superpower.particles")
                .command("파티클", "particles")
                .icon(Material.BLAZE_POWDER)
                .text(messages, "settings.particles")
                .path(path(SuperpowerSettings.PARTICLE_SCALE))
                .range(SuperpowerSettings.MIN_PARTICLE_SCALE, SuperpowerSettings.MAX_PARTICLE_SCALE)
                .steps(0.25, 0.5).unit(Setting.Unit.MULTIPLIER)
                .number(decimal(SuperpowerSettings.PARTICLE_SCALE), store(SuperpowerSettings.PARTICLE_SCALE, false),
                        defaults.getDouble(SuperpowerSettings.PARTICLE_SCALE));
        Setting teamAbilities = toggle("superpower.team-abilities", "팀원능력", "team-abilities", Material.SPYGLASS,
                "settings.team-abilities", SuperpowerSettings.TEAM_ABILITIES);
        Setting announce = toggle("superpower.ultimate-announce", "궁극기공지", "ultimate-announce",
                Material.GOAT_HORN, "settings.ultimate-announce", SuperpowerSettings.ANNOUNCE_ULTIMATES);
        Setting killFeed = toggle("superpower.kill-feed", "킬로그능력", "kill-feed", Material.SKELETON_SKULL,
                "settings.kill-feed", SuperpowerSettings.KILL_FEED_ABILITY);

        Setting loot = toggle("superpower.loot", "상자", "loot", Material.CHEST, "settings.loot",
                LootSettings.ENABLED);
        Setting chests = Setting.builder("superpower.chests")
                .command("인당상자", "chests")
                .icon(Material.BARREL)
                .text(messages, "settings.chests")
                .path(path(LootSettings.CHESTS_PER_PLAYER))
                .range(0, LootSettings.MAX_CHESTS_PER_PLAYER).steps(0.5, 2).unit(Setting.Unit.COUNT)
                .number(decimal(LootSettings.CHESTS_PER_PLAYER), store(LootSettings.CHESTS_PER_PLAYER, false),
                        defaults.getDouble(LootSettings.CHESTS_PER_PLAYER));
        Setting maxChests = Setting.builder("superpower.max-chests")
                .command("상자최대", "max-chests")
                .icon(Material.ENDER_CHEST)
                .text(messages, "settings.max-chests")
                .path(path(LootSettings.CHESTS_MAX))
                .range(0, LootSettings.MAX_CHESTS).steps(1, 5).integer().unit(Setting.Unit.COUNT)
                .number(integer(LootSettings.CHESTS_MAX), store(LootSettings.CHESTS_MAX, true),
                        defaults.getInt(LootSettings.CHESTS_MAX));
        Setting supply = Setting.builder("superpower.supply")
                .command("보급", "supply")
                .icon(Material.BEACON)
                .text(messages, "settings.supply")
                .path(path(LootSettings.SUPPLY_MAX))
                .range(0, LootSettings.MAX_SUPPLY).steps(1, 2).integer().unit(Setting.Unit.COUNT)
                .number(integer(LootSettings.SUPPLY_MAX), store(LootSettings.SUPPLY_MAX, true),
                        defaults.getInt(LootSettings.SUPPLY_MAX));
        Setting supplyPlayers = number("superpower.supply-players", "보급인원", "supply-players",
                Material.PLAYER_HEAD, "settings.supply-players", LootSettings.PLAYERS_PER_SUPPLY, true)
                .range(1, LootSettings.MAX_PLAYERS_PER_SUPPLY).steps(1, 4).unit(Setting.Unit.PLAYERS)
                .number(integer(LootSettings.PLAYERS_PER_SUPPLY), store(LootSettings.PLAYERS_PER_SUPPLY, true),
                        defaults.getInt(LootSettings.PLAYERS_PER_SUPPLY));
        Setting practiceLoot = toggle("superpower.practice-loot", "연습상자", "practice-loot", Material.TARGET,
                "settings.practice-loot", LootSettings.PRACTICE);
        Setting hint = toggle("superpower.chest-hint", "상자반짝임", "chest-hint", Material.GLOWSTONE_DUST,
                "settings.chest-hint", LootSettings.NEARBY_HINT);
        Setting hintRadius = number("superpower.chest-hint-radius", "반짝임거리", "chest-hint-radius",
                Material.COMPASS, "settings.chest-hint-radius", LootSettings.NEARBY_HINT_RADIUS, true)
                .range(LootSettings.MIN_HINT_RADIUS, LootSettings.MAX_HINT_RADIUS).steps(1, 4)
                .unit(Setting.Unit.BLOCKS)
                .number(decimal(LootSettings.NEARBY_HINT_RADIUS), store(LootSettings.NEARBY_HINT_RADIUS, true),
                        defaults.getDouble(LootSettings.NEARBY_HINT_RADIUS));

        return List.of(
                new SettingsSection("superpower.match", Material.NETHER_STAR, messages, "settings.section.match",
                        List.of(mode, rerolls, prep, timeLimit, borderDelay, borderShrink, borderFinal)),
                new SettingsSection("superpower.sky", Material.SUNFLOWER, messages, "settings.section.sky",
                        List.of(dayNight, rainChance, rainMin, rainMax)),
                new SettingsSection("superpower.effects", Material.FIREWORK_ROCKET, messages,
                        "settings.section.effects", List.of(particles, teamAbilities, announce, killFeed)),
                new SettingsSection("superpower.loot", Material.BUNDLE, messages, "settings.section.loot",
                        List.of(loot, chests, maxChests, supply, supplyPlayers, practiceLoot, hint, hintRadius)));
    }

    // =================================================================== helpers

    private ConfigurationSection live() {
        return config.gameSection(SuperpowerGame.ID);
    }

    private static String path(String key) {
        return "games." + SuperpowerGame.ID + "." + key;
    }

    private Setting.Builder number(String id, String korean, String english, Material icon, String text, String key,
                                   boolean integer) {
        Setting.Builder builder = Setting.builder(id).command(korean, english).icon(icon).text(messages, text)
                .path(path(key));
        return integer ? builder.integer() : builder;
    }

    /** Whole seconds read with {@code getDouble} (like the match reader). */
    private Setting seconds(String id, List<String> korean, List<String> english, Material icon, String text,
                            String key, int max, int step, int bigStep) {
        return Setting.builder(id).command(korean, english).icon(icon).text(messages, text).path(path(key))
                .range(0, max).steps(step, bigStep).integer().unit(Setting.Unit.SECONDS)
                .number(decimal(key), store(key, true), defaults.getDouble(key));
    }

    /** {@code 비시간 최소|최대}: rain seconds in the reader's range. */
    private Setting rainSeconds(String id, String korean, String english, Material icon, String text, String key) {
        return Setting.builder(id).command(List.of("비시간", korean), List.of("rain-seconds", english)).icon(icon)
                .text(messages, text).path(path(key))
                .range(SuperpowerSettings.MIN_RAIN_SECONDS, SuperpowerSettings.MAX_RAIN_SECONDS).steps(10, 60)
                .integer().unit(Setting.Unit.SECONDS)
                .number(decimal(key), store(key, true), defaults.getDouble(key));
    }

    private Setting toggle(String id, String korean, String english, Material icon, String text, String key) {
        boolean fallback = defaults.getBoolean(key);
        return Setting.builder(id).command(korean, english).icon(icon).text(messages, text).path(path(key))
                .toggle(() -> live().getBoolean(key, fallback), value -> config.setGameValue(SuperpowerGame.ID, key,
                        value), fallback);
    }

    private DoubleSupplier decimal(String key) {
        double fallback = defaults.getDouble(key);
        return () -> live().getDouble(key, fallback);
    }

    private DoubleSupplier integer(String key) {
        int fallback = defaults.getInt(key);
        return () -> live().getInt(key, fallback);
    }

    /** Stores whole numbers as integers, so the file keeps {@code 20} rather than {@code 20.0}. */
    private DoubleConsumer store(String key, boolean whole) {
        return value -> config.setGameValue(SuperpowerGame.ID, key, whole ? (Object) (int) Math.round(value) : value);
    }
}
