package io.github.sx2xoox.mcpl.game.siege.balance;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import io.github.sx2xoox.mcpl.game.siege.SiegeSettings;
import io.github.sx2xoox.mcpl.game.superpower.abilities.BuiltinAbilities;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityRegistry;

/**
 * The measurements of the 공성전 balance lab ({@code SiegeBalanceLab}, {@code ./gradlew siegeLab}): one row per pool
 * ability and level (1, 6, 9), written to {@link #FILE} with a {@link #fingerprint} of every number the lab read (the
 * abilities' params as 공성전 reads them at each level, the global factors and {@link #LAB_VERSION}), so the report knows
 * when the measurements are stale.
 *
 * <p>Units: health points, seconds, blocks. Every damage number is the 공성전 damage at that level with the per-ability
 * corrections left at 1 (the report applies them); damage to buildings, minions and monsters has no balance factor.
 */
public final class SiegeLabData {

    /** Bump when the lab's scenarios or measurements change. */
    public static final int LAB_VERSION = 2;
    public static final Path FILE = Path.of("docs", "siege-balance-lab.csv");
    public static final List<Integer> LEVELS = List.of(1, 6, 9);

    /** The measured metrics, in column order. */
    public static final List<String> METRICS = List.of(
            // engagement: the distance the duels were fought at, and the farthest that still worked
            "dist", "range", "ultDist",
            // S1: active on cooldown for 30 s against one enemy
            "casts", "dmg3", "dmg10", "dmg30",
            "hard30", "silence30", "soft30", "knock30", "path30", "dashes30",
            "heal30", "shield30", "prevented30", "invuln30", "bonusHp", "selfCost30",
            "allyHeal30", "allyShield30", "allyPrevented30", "allyBuff30",
            // S2: the ultimate at 0 with the active on cooldown for 12 s; and S1's first 12 s for the difference
            "ultOk", "ult12", "skill12",
            "hardU", "silenceU", "softU", "knockU", "pathU",
            "healU", "shieldU", "preventedU", "invulnU",
            "allyHealU", "allyShieldU", "allyPreventedU", "allyBuffU",
            "hard12", "silence12", "soft12", "path12", "heal12", "shield12", "prevented12", "invuln12",
            "allyHeal12", "allyShield12", "allyPrevented12", "allyBuff12",
            // S3: five enemies in a cluster, the ultimate + active on cooldown for 10 s (sorted per enemy)
            "aoe1", "aoe3", "aoe5",
            // S4: an enemy outer tower, the ultimate + active on cooldown for 20 s
            "tower20",
            // S5: basic attacks on one enemy at 2.5 blocks for 12 s, and what the ultimate / the active on cooldown add
            "melee12", "ultMelee", "skillMelee",
            // the probe's damage on the attacker before its ability (what "prevented" is measured against)
            "probe");

    /** One row: an ability at a level. {@code s1}/{@code s2} = damage on the duel target per second of S1 / S2. */
    public record Row(String id, int level, Map<String, Double> metrics, double[] s1, double[] s2) {
        public double get(String metric) {
            Double value = metrics.get(metric);
            return value == null ? 0 : value;
        }
    }

    /** All rows by "id:level", and the fingerprint they were measured with. */
    public record Table(String fingerprint, Map<String, Row> rows) {
        public Row row(String id, int level) {
            return rows.get(id + ":" + level);
        }
    }

    private SiegeLabData() {
    }

    public static AbilityRegistry registry() {
        AbilityRegistry registry = new AbilityRegistry();
        BuiltinAbilities.registerAll(registry);
        return registry;
    }

    /**
     * A hash of what the lab depends on: per pool ability and lab level every declared param as 공성전 reads it (tuning
     * and perks), the global balance factors and the lab version.
     */
    public static String fingerprint(AbilityRegistry registry) {
        StringBuilder text = new StringBuilder("v").append(LAB_VERSION)
                .append(' ').append(SiegeBalanceSettings.DEFAULT_ABILITY_DAMAGE)
                .append(' ').append(SiegeBalanceSettings.DEFAULT_DAMAGE_PER_LEVEL).append('\n');
        SiegeBalance balance = new SiegeBalance(SiegeBalanceSettings.defaults(), new SiegeModifiers());
        for (String id : SiegeSettings.DEFAULT_POOL) {
            AbilityInfo info = registry.get(id).info();
            for (int level : LEVELS) {
                text.append(id).append(':').append(level);
                for (Map.Entry<String, Double> param : new TreeMap<>(info.params()).entrySet()) {
                    text.append(' ').append(param.getKey()).append('=')
                            .append(balance.param(id, level, param.getKey(), param.getValue()));
                }
                text.append('\n');
            }
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static Table read() throws IOException {
        return read(FILE);
    }

    public static Table read(Path file) throws IOException {
        Map<String, Row> rows = new LinkedHashMap<>();
        if (!Files.exists(file)) {
            return new Table("", rows);
        }
        String fingerprint = "";
        List<String> header = null;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.startsWith("# fingerprint ")) {
                fingerprint = line.substring("# fingerprint ".length()).trim();
                continue;
            }
            if (line.startsWith("#") || line.isBlank()) {
                continue;
            }
            String[] cells = line.split(",", -1);
            if (header == null) {
                header = List.of(cells);
                continue;
            }
            Map<String, Double> metrics = new LinkedHashMap<>();
            for (int i = 2; i < header.size() - 2; i++) {
                metrics.put(header.get(i), Double.parseDouble(cells[i]));
            }
            Row row = new Row(cells[0], Integer.parseInt(cells[1]), metrics, series(cells[header.size() - 2]),
                    series(cells[header.size() - 1]));
            rows.put(row.id() + ":" + row.level(), row);
        }
        return new Table(fingerprint, rows);
    }

    public static void write(String fingerprint, List<Row> rows) throws IOException {
        write(FILE, fingerprint, rows);
    }

    public static void write(Path file, String fingerprint, List<Row> rows) throws IOException {
        StringBuilder out = new StringBuilder();
        out.append("# 공성전 밸런스 랩 측정값 (자동 생성: ./gradlew siegeLab → SiegeBalanceLab). 직접 고치지 마세요.\n");
        out.append("# 단위: 체력·초·블록. 피해는 그 레벨의 공성전 피해(능력별 보정 1), 건물 피해는 배율 없음. 열 설명: docs/siege-balance.md\n");
        out.append("# fingerprint ").append(fingerprint).append('\n');
        out.append("id,level,").append(String.join(",", METRICS)).append(",s1,s2\n");
        for (Row row : rows) {
            out.append(row.id()).append(',').append(row.level());
            for (String metric : METRICS) {
                out.append(',').append(num(row.get(metric)));
            }
            out.append(',').append(series(row.s1())).append(',').append(series(row.s2())).append('\n');
        }
        Files.writeString(file, out.toString(), StandardCharsets.UTF_8);
    }

    private static double[] series(String cell) {
        if (cell.isBlank()) {
            return new double[0];
        }
        return Arrays.stream(cell.trim().split(" ")).mapToDouble(Double::parseDouble).toArray();
    }

    private static String series(double[] values) {
        List<String> out = new ArrayList<>();
        for (double value : values) {
            out.add(num(value));
        }
        return String.join(" ", out);
    }

    static String num(double value) {
        double rounded = Math.round(value * 1000) / 1000.0;
        if (Math.abs(rounded - Math.rint(rounded)) < 1.0E-9) {
            return Long.toString(Math.round(rounded));
        }
        return String.format(Locale.ROOT, "%.3f", rounded).replaceAll("0+$", "");
    }
}
