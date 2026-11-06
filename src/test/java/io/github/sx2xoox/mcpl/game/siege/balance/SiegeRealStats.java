package io.github.sx2xoox.mcpl.game.siege.balance;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Real 공성전 records for the balance report: a server's {@code plugins/MCPL/stats/siege/totals.yml}
 * ({@code SiegeStatsStore}, {@code /게임 공성전 통계}) copied to {@link #FILE}. When it is there, the report compares
 * every ability's real skill damage per minute with the lab's and calibrates the lab's damage of the abilities with
 * enough rounds towards what real players do with them (at most half way, {@link #calibration}).
 */
final class SiegeRealStats {

    static final Path FILE = Path.of("docs", "siege-stats", "totals.yml");
    /** Rounds an ability needs before its records count. */
    static final int MIN_ROUNDS = 5;
    /** Rounds at which the records count fully ({@link #MAX_WEIGHT}). */
    static final int FULL_ROUNDS = 40;
    static final double MAX_WEIGHT = 0.5;

    /** One ability's totals. */
    record Entry(int rounds, double seconds, double enemySkill, double structure, double creature, int kills,
                 int deaths, int assists, int wins) {
        double skillPerMinute() {
            return seconds > 0 ? enemySkill / seconds * 60 : 0;
        }

        double structurePerMinute() {
            return seconds > 0 ? structure / seconds * 60 : 0;
        }

        double winRate() {
            return rounds > 0 ? wins / (double) rounds : 0;
        }
    }

    private SiegeRealStats() {
    }

    /** The recorded abilities (empty without the file). */
    static Map<String, Entry> read() {
        Map<String, Entry> out = new LinkedHashMap<>();
        if (!Files.exists(FILE)) {
            return out;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(FILE.toFile());
        ConfigurationSection abilities = yaml.getConfigurationSection("abilities");
        if (abilities == null) {
            return out;
        }
        for (String id : abilities.getKeys(false)) {
            ConfigurationSection s = abilities.getConfigurationSection(id);
            if (s == null) {
                continue;
            }
            out.put(id, new Entry(s.getInt("rounds"), s.getDouble("seconds"), s.getDouble("enemy-skill"),
                    s.getDouble("structure"), s.getDouble("creature"), s.getInt("kills"), s.getInt("deaths"),
                    s.getInt("assists"), s.getInt("wins")));
        }
        return out;
    }

    /**
     * The factor on each recorded ability's lab damage: (its real skill damage per minute / the median of the recorded)
     * ÷ (its lab damage / the median of the same abilities' lab damage), to the power of a weight that grows with its
     * rounds (0 below {@link #MIN_ROUNDS}, {@link #MAX_WEIGHT} from {@link #FULL_ROUNDS}).
     *
     * @param labDps the lab's damage per second of each ability (uncorrected, level 6)
     */
    static Map<String, Double> calibration(Map<String, Entry> real, Map<String, Double> labDps) {
        Map<String, Double> out = new LinkedHashMap<>();
        double[] realValues = real.entrySet().stream()
                .filter(e -> e.getValue().rounds() >= MIN_ROUNDS && labDps.getOrDefault(e.getKey(), 0.0) > 0)
                .mapToDouble(e -> e.getValue().skillPerMinute()).toArray();
        double[] labValues = real.entrySet().stream()
                .filter(e -> e.getValue().rounds() >= MIN_ROUNDS && labDps.getOrDefault(e.getKey(), 0.0) > 0)
                .mapToDouble(e -> labDps.get(e.getKey())).toArray();
        if (realValues.length < 3) {
            return out;
        }
        double realMedian = SiegeBalanceScores.median(realValues);
        double labMedian = SiegeBalanceScores.median(labValues);
        if (realMedian <= 0 || labMedian <= 0) {
            return out;
        }
        real.forEach((id, entry) -> {
            double lab = labDps.getOrDefault(id, 0.0);
            if (entry.rounds() < MIN_ROUNDS || lab <= 0 || entry.skillPerMinute() <= 0) {
                return;
            }
            double ratio = (entry.skillPerMinute() / realMedian) / (lab / labMedian);
            double weight = MAX_WEIGHT * Math.min(1, (entry.rounds() - MIN_ROUNDS + 1)
                    / (double) (FULL_ROUNDS - MIN_ROUNDS + 1));
            out.put(id, Math.pow(Math.max(0.25, Math.min(4, ratio)), weight));
        });
        return out;
    }
}
