package io.github.sx2xoox.mcpl.game.siege.stats;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * The balance records on disk ({@code plugins/MCPL/stats/siege/}): one CSV per match ({@code 날짜시각_맵.csv}, UTF-8
 * with a BOM so spreadsheet programs read the Korean) and {@value #TOTALS_FILE}, the running totals per ability
 * ({@code abilities.<id>}) and, apart, per bot job ({@code bots.<job>}). Small synchronous writes, once per match.
 */
public final class SiegeStatsStore {

    public static final String TOTALS_FILE = "totals.yml";
    static final String ABILITIES = "abilities";
    static final String BOTS = "bots";
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    static final List<String> HEADER = List.of("라운드", "플레이어", "봇", "능력 id", "능력", "결과", "플레이(초)",
            "적 피해(스킬)", "적 피해(평타)", "적 피해(합)", "건물 피해", "미니언·정글 피해", "받은 피해", "처치", "죽음",
            "도움", "최종 레벨");

    private final Path dir;

    public SiegeStatsStore(Path dir) {
        this.dir = dir;
    }

    public Path dir() {
        return dir;
    }

    public Path totalsFile() {
        return dir.resolve(TOTALS_FILE);
    }

    /**
     * Writes the match's CSV and adds its decided rounds to the totals.
     *
     * @return the CSV written
     */
    public Path save(List<StatLine> lines, String map, LocalDateTime when) throws IOException {
        Files.createDirectories(dir);
        Path csv = writeCsv(lines, map, when);
        Totals totals = load();
        for (StatLine line : lines) {
            if (line.result() != StatLine.Result.UNFINISHED) {
                (line.bot() ? totals.bots : totals.abilities).computeIfAbsent(line.id(), id -> new StatTotals())
                        .add(line);
            }
        }
        write(totals);
        return csv;
    }

    private Path writeCsv(List<StatLine> lines, String map, LocalDateTime when) throws IOException {
        String base = FILE_TIME.format(when) + "_" + map.replaceAll("[^A-Za-z0-9_-]", "");
        Path file = dir.resolve(base + ".csv");
        for (int i = 2; Files.exists(file); i++) {
            file = dir.resolve(base + "_" + i + ".csv");
        }
        StringBuilder out = new StringBuilder("﻿");
        out.append(String.join(",", HEADER)).append("\r\n");
        for (StatLine line : lines) {
            List<String> cells = List.of(Integer.toString(line.round()), line.name(), line.bot() ? "O" : "", line.id(),
                    line.displayName(), line.result().label(), number(line.seconds()), number(line.enemySkill()),
                    number(line.enemyBasic()), number(line.enemyDamage()), number(line.structure()),
                    number(line.creature()), number(line.taken()), Integer.toString(line.kills()),
                    Integer.toString(line.deaths()), Integer.toString(line.assists()), Integer.toString(line.level()));
            out.append(String.join(",", cells.stream().map(SiegeStatsStore::cell).toList())).append("\r\n");
        }
        Files.writeString(file, out, StandardCharsets.UTF_8);
        return file;
    }

    /** The running totals (empty when nothing was recorded yet). */
    public Totals load() throws IOException {
        Totals totals = new Totals();
        Path file = totalsFile();
        if (!Files.exists(file)) {
            return totals;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
        } catch (InvalidConfigurationException e) {
            throw new IOException("Broken " + file, e);
        }
        read(yaml.getConfigurationSection(ABILITIES), totals.abilities);
        read(yaml.getConfigurationSection(BOTS), totals.bots);
        return totals;
    }

    /** Clears the running totals (the match CSVs stay). */
    public void reset() throws IOException {
        Files.deleteIfExists(totalsFile());
    }

    private void write(Totals totals) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().setHeader(List.of("공성전 실전 밸런스 누적 기록 (능력별 / 봇 직업별, 결과가 난 라운드만)",
                "초기화: /게임 공성전 통계 초기화 확인"));
        ConfigurationSection abilities = yaml.createSection(ABILITIES);
        totals.abilities.forEach((id, entry) -> entry.write(abilities.createSection(id)));
        ConfigurationSection bots = yaml.createSection(BOTS);
        totals.bots.forEach((id, entry) -> entry.write(bots.createSection(id)));
        Path file = totalsFile();
        Path temp = file.resolveSibling(TOTALS_FILE + ".tmp");
        Files.writeString(temp, yaml.saveToString(), StandardCharsets.UTF_8);
        Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private static void read(ConfigurationSection section, Map<String, StatTotals> into) {
        if (section == null) {
            return;
        }
        for (String id : section.getKeys(false)) {
            ConfigurationSection entry = section.getConfigurationSection(id);
            if (entry != null) {
                into.put(id, StatTotals.read(entry));
            }
        }
    }

    private static String number(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static String cell(String value) {
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    /** The running totals: abilities and bot jobs apart, by id. */
    public static final class Totals {
        final Map<String, StatTotals> abilities = new TreeMap<>();
        final Map<String, StatTotals> bots = new TreeMap<>();

        public Map<String, StatTotals> abilities() {
            return abilities;
        }

        public Map<String, StatTotals> bots() {
            return bots;
        }
    }
}
