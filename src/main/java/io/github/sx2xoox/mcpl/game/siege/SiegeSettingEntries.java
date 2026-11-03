package io.github.sx2xoox.mcpl.game.siege;

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
 * The 공성전 entries of the settings menu and {@code /게임 설정} ({@link SiegeGame#settings()}): the main numbers of
 * the match flow, respawns, towers and levels. Every other value is in {@code config.yml} → {@code games.siege}. Texts:
 * {@code siege.yml} → {@code settings.*}.
 */
final class SiegeSettingEntries {

    private final HubConfig config;
    private final Messages messages;
    private final ConfigurationSection defaults = new MemoryConfiguration();

    private SiegeSettingEntries(SiegeGame game) {
        this.config = game.hub().config();
        this.messages = game.messages();
        game.configDefaults(defaults);
    }

    static List<SettingsSection> create(SiegeGame game) {
        return new SiegeSettingEntries(game).sections();
    }

    private List<SettingsSection> sections() {
        Setting rounds = whole("siege.rounds-to-win", List.of("공성전", "승리판수"), List.of("siege", "rounds"),
                Material.TOTEM_OF_UNDYING, "settings.rounds-to-win", SiegeSettings.ROUNDS_TO_WIN, 1, 5, 1, 1,
                Setting.Unit.COUNT);
        Setting roundTime = whole("siege.round", List.of("공성전", "판시간"), List.of("siege", "round"),
                Material.CLOCK, "settings.round", SiegeSettings.ROUND_SECONDS, 60, SiegeSettings.MAX_SECONDS, 60, 300,
                Setting.Unit.SECONDS);
        Setting roundBreak = whole("siege.round-break", List.of("공성전", "쉬는시간"), List.of("siege", "break"),
                Material.CAMPFIRE, "settings.round-break", SiegeSettings.ROUND_BREAK_SECONDS, 0, 600, 10, 60,
                Setting.Unit.SECONDS);
        Setting rerolls = whole("siege.rerolls", List.of("공성전", "리롤"), List.of("siege", "rerolls"),
                Material.ENDER_EYE, "settings.rerolls", SiegeSettings.REROLLS, 0, 10, 1, 3, Setting.Unit.TIMES);
        Setting overtime = whole("siege.overtime", List.of("공성전", "연장전"), List.of("siege", "overtime"),
                Material.FIRE_CHARGE, "settings.overtime", SiegeSettings.OVERTIME_SECONDS, 10, 900, 10, 60,
                Setting.Unit.SECONDS);
        Setting respawn = whole("siege.respawn", List.of("공성전", "부활"), List.of("siege", "respawn"),
                Material.RESPAWN_ANCHOR, "settings.respawn", SiegeSettings.RESPAWN_SECONDS, 0, 60, 1, 5,
                Setting.Unit.SECONDS);
        Setting towerScale = Setting.builder("siege.tower-per-attacker")
                .command(List.of("공성전", "타워인원배율"), List.of("siege", "tower-per-attacker"))
                .icon(Material.END_CRYSTAL)
                .text(messages, "settings.tower-per-attacker")
                .path(path(SiegeSettings.HEALTH_PER_ATTACKER))
                .range(0, 2).steps(0.05, 0.25).unit(Setting.Unit.MULTIPLIER)
                .number(decimal(SiegeSettings.HEALTH_PER_ATTACKER),
                        value -> config.setGameValue(SiegeGame.ID, SiegeSettings.HEALTH_PER_ATTACKER, value),
                        defaults.getDouble(SiegeSettings.HEALTH_PER_ATTACKER));
        Setting maxLevel = whole("siege.max-level", List.of("공성전", "최대레벨"), List.of("siege", "max-level"),
                Material.EXPERIENCE_BOTTLE, "settings.max-level", SiegeProgressSettings.MAX_LEVEL, 1, 30, 1, 3,
                Setting.Unit.COUNT);
        Setting passiveXp = whole("siege.passive-xp", List.of("공성전", "기본경험치"), List.of("siege", "passive-xp"),
                Material.GLOW_BERRIES, "settings.passive-xp", SiegeProgressSettings.XP_PASSIVE, 0, 20, 1, 5,
                Setting.Unit.COUNT);
        return List.of(
                new SettingsSection("siege.match", Material.END_CRYSTAL, messages, "settings.section.match",
                        List.of(rounds, roundTime, roundBreak, overtime, rerolls)),
                new SettingsSection("siege.battle", Material.IRON_SWORD, messages, "settings.section.battle",
                        List.of(respawn, towerScale)),
                new SettingsSection("siege.progress", Material.EXPERIENCE_BOTTLE, messages,
                        "settings.section.progress", List.of(maxLevel, passiveXp)));
    }

    private Setting whole(String id, List<String> korean, List<String> english, Material icon, String text,
                          String key, int min, int max, int step, int bigStep, Setting.Unit unit) {
        return Setting.builder(id).command(korean, english).icon(icon).text(messages, text).path(path(key))
                .range(min, max).steps(step, bigStep).integer().unit(unit)
                .number(decimal(key), store(key), defaults.getDouble(key));
    }

    private static String path(String key) {
        return "games." + SiegeGame.ID + "." + key;
    }

    private DoubleSupplier decimal(String key) {
        double fallback = defaults.getDouble(key);
        return () -> config.gameSection(SiegeGame.ID).getDouble(key, fallback);
    }

    private DoubleConsumer store(String key) {
        return value -> config.setGameValue(SiegeGame.ID, key, (int) Math.round(value));
    }
}
