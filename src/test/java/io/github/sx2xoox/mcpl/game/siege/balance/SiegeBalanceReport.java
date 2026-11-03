package io.github.sx2xoox.mcpl.game.siege.balance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import io.github.sx2xoox.mcpl.game.siege.SiegeSettings;
import io.github.sx2xoox.mcpl.game.siege.balance.SiegeBalanceModels.Hit;
import io.github.sx2xoox.mcpl.game.siege.balance.SiegeBalanceModels.Profile;
import io.github.sx2xoox.mcpl.game.superpower.abilities.BuiltinAbilities;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityParams;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityRegistry;
import io.github.sx2xoox.mcpl.game.superpower.support.MatchHarness;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The 공성전 balance report: for every pool ability, from its {@link AbilityInfo} params through
 * {@link SiegeBalanceModels}, the biggest single hit, the 3-second burst of one active and one ultimate on one target,
 * the damage of a 10-second window of perfect use (active on cooldown + one ultimate), crowd control and reach.
 * From these it chooses the per-ability corrections on top of {@link SiegeBalanceSettings#DEFAULT_ABILITY_DAMAGE}:
 * <ol>
 *     <li>no cast's 3-second burst at level 1 above {@link #BURST_CAP} (40% of 20 health);</li>
 *     <li>10-second damage above {@link #HIGH}× the median comes down to it, below {@link #LOW}× the median goes up
 *     (at most ×{@link #MAX_BOOST}, never past the burst cap); support kits are left out.</li>
 * </ol>
 * It writes docs/siege-balance.md and the bundled {@code siege-balance.yml} ({@code ./gradlew updateSiegeBalance})
 * and fails while either is stale, or when a level-9 cast with its perks would burst above {@link #BURST_CAP_9}.
 */
class SiegeBalanceReport {

    static final double BURST_CAP = 8.0;
    /** 50% of the health of a level-9 player (20 + 8). */
    static final double BURST_CAP_9 = 14.0;
    static final double HIGH = 1.5;
    static final double LOW = 0.5;
    static final double MAX_BOOST = 1.3;
    static final double FLOOR = 0.3;
    static final double WINDOW = 10.0;
    static final double BURST_WINDOW = 3.0;
    static final int TOP_LEVEL = 9;

    private static final Path DOCS = Path.of("docs", "siege-balance.md");
    private static final Path YAML = Path.of("src", "main", "resources", SiegeBalanceSettings.RESOURCE);

    private MatchHarness h;

    @BeforeEach
    void setUp() {
        h = MatchHarness.flat();
    }

    @AfterEach
    void tearDown() {
        h.close();
    }

    /** One pool ability's numbers. */
    record Row(AbilityInfo info, double cooldown, Profile raw, double skill, double ult, Profile top,
               double topCooldown) {
    }

    @Test
    void siegeBalanceIsUpToDateAndWithinCaps() throws IOException {
        List<Row> rows = analyse(registry());
        double g = SiegeBalanceSettings.DEFAULT_ABILITY_DAMAGE;
        double top = 1 + SiegeBalanceSettings.DEFAULT_DAMAGE_PER_LEVEL * (TOP_LEVEL - 1);
        List<String> problems = new ArrayList<>();
        for (Row row : rows) {
            double l1 = g * worstBurst(row.raw(), row.skill(), row.ult());
            if (l1 > BURST_CAP + 1.0E-6) {
                problems.add(row.info().id() + " level 1 burst " + l1);
            }
            double l9 = g * top * worstBurst(row.top(), row.skill(), row.ult());
            if (l9 > BURST_CAP_9 + 1.0E-6) {
                problems.add(row.info().id() + " level 9 burst " + l9);
            }
        }
        String docs = renderDocs(rows);
        String yaml = renderYaml(rows);
        if (Boolean.getBoolean("mcpl.updateDocs")) {
            Files.writeString(DOCS, docs, StandardCharsets.UTF_8);
            Files.writeString(YAML, yaml, StandardCharsets.UTF_8);
        }
        assertEquals(List.of(), problems, "bursts over the caps");
        assertEquals(docs, Files.exists(DOCS) ? Files.readString(DOCS, StandardCharsets.UTF_8) : "",
                DOCS + " is out of date: run ./gradlew updateSiegeBalance");
        assertEquals(yaml, Files.exists(YAML) ? Files.readString(YAML, StandardCharsets.UTF_8) : "",
                YAML + " is out of date: run ./gradlew updateSiegeBalance");
    }

    @Test
    void everyPoolAbilityHasAModelAndPerks() {
        AbilityRegistry registry = registry();
        for (String id : SiegeSettings.DEFAULT_POOL) {
            assertTrue(SiegeBalanceModels.all().containsKey(id), "no balance model for " + id);
            assertTrue(registry.get(id) != null, "unknown pool ability " + id);
        }
        assertEquals(SiegeSettings.DEFAULT_POOL.size(), SiegeBalanceModels.all().size());
    }

    // ------------------------------------------------------------------ analysis

    static List<Row> analyse(AbilityRegistry registry) {
        double g = SiegeBalanceSettings.DEFAULT_ABILITY_DAMAGE;
        List<Row> rows = new ArrayList<>();
        for (String id : SiegeSettings.DEFAULT_POOL) {
            AbilityInfo info = registry.get(id).info();
            AbilityParams params = AbilityParams.defaults(info);
            Profile raw = SiegeBalanceModels.all().get(id).apply(params);
            AbilityParams topParams = params.withAdjuster((key, value) -> SiegePerks.apply(id, TOP_LEVEL, key, value));
            Profile top = SiegeBalanceModels.all().get(id).apply(topParams);
            double skill = Math.min(1, round(BURST_CAP / (g * Math.max(1.0E-9, burst(raw.active(), 1, 1)))));
            double ult = 1;
            boolean hasUlt = raw.ult().stream().anyMatch(Hit::ult);
            while (hasUlt && ult > FLOOR && g * burst(raw.ult(), skill, ult) > BURST_CAP + 1.0E-9) {
                ult = round(ult - 0.05);
            }
            // An ultimate that bursts with skill hits (rocket punches, marked shots) lowers the skill too.
            while (skill > FLOOR && g * burst(raw.ult(), skill, ult) > BURST_CAP + 1.0E-9) {
                skill = round(skill - 0.05);
            }
            rows.add(new Row(info, params.cooldownSeconds(), raw, Math.max(FLOOR, skill), ult, top,
                    topParams.cooldownSeconds()));
        }
        double median = median(rows.stream().filter(row -> !row.raw().utility())
                .mapToDouble(row -> tenSeconds(row.raw(), row.cooldown(), row.skill(), row.ult())).toArray());
        List<Row> adjusted = new ArrayList<>();
        for (Row row : rows) {
            double ten = tenSeconds(row.raw(), row.cooldown(), row.skill(), row.ult());
            double skill = row.skill();
            double ult = row.ult();
            if (!row.raw().utility() && ten > HIGH * median) {
                double f = HIGH * median / ten;
                skill = Math.max(FLOOR, round(skill * f));
                ult = Math.max(FLOOR, round(ult * f));
            } else if (!row.raw().utility() && ten > 0 && ten < LOW * median) {
                double f = LOW * median / ten;
                double boostedSkill = Math.min(MAX_BOOST, round(skill * f));
                double boostedUlt = Math.min(MAX_BOOST, round(ult * f));
                while (boostedSkill > skill && g * burst(row.raw().active(), boostedSkill, boostedUlt) > BURST_CAP) {
                    boostedSkill = round(boostedSkill - 0.05);
                }
                while (boostedUlt > ult && g * burst(row.raw().ult(), boostedSkill, boostedUlt) > BURST_CAP) {
                    boostedUlt = round(boostedUlt - 0.05);
                }
                skill = boostedSkill;
                ult = boostedUlt;
            }
            adjusted.add(new Row(row.info(), row.cooldown(), row.raw(), skill, ult, row.top(), row.topCooldown()));
        }
        return adjusted;
    }

    /** Down to a multiple of 0.05. */
    static double round(double value) {
        return Math.floor(value * 20 + 1.0E-6) / 20;
    }

    static double factor(Hit hit, double skill, double ult) {
        return hit.damage() * (hit.ult() ? ult : skill);
    }

    /** The most damage the hits deal within any {@link #BURST_WINDOW}. */
    static double burst(List<Hit> hits, double skill, double ult) {
        double best = 0;
        for (Hit start : hits) {
            double sum = 0;
            for (Hit hit : hits) {
                if (hit.t() >= start.t() - 1.0E-9 && hit.t() < start.t() + BURST_WINDOW - 1.0E-9) {
                    sum += factor(hit, skill, ult);
                }
            }
            best = Math.max(best, sum);
        }
        return best;
    }

    static double worstBurst(Profile profile, double skill, double ult) {
        return Math.max(burst(profile.active(), skill, ult), burst(profile.ult(), skill, ult));
    }

    static double maxHit(Profile profile, double skill, double ult) {
        double best = 0;
        for (Hit hit : profile.active()) {
            best = Math.max(best, factor(hit, skill, ult));
        }
        for (Hit hit : profile.ult()) {
            best = Math.max(best, factor(hit, skill, ult));
        }
        return best;
    }

    /** One ultimate at 0 and the active on cooldown from 0: every hit within {@link #WINDOW}. */
    static double tenSeconds(Profile profile, double cooldown, double skill, double ult) {
        double sum = 0;
        for (Hit hit : profile.ult()) {
            if (hit.t() < WINDOW) {
                sum += factor(hit, skill, ult);
            }
        }
        double step = Math.max(0.5, cooldown);
        for (double start = 0; start < WINDOW; start += step) {
            for (Hit hit : profile.active()) {
                if (start + hit.t() < WINDOW) {
                    sum += factor(hit, skill, ult);
                }
            }
        }
        return sum;
    }

    static double median(double[] values) {
        double[] sorted = values.clone();
        java.util.Arrays.sort(sorted);
        if (sorted.length == 0) {
            return 0;
        }
        int mid = sorted.length / 2;
        return sorted.length % 2 == 1 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2;
    }

    // ------------------------------------------------------------------ output

    static String renderYaml(List<Row> rows) {
        StringBuilder out = new StringBuilder();
        out.append("# 공성전 능력별 피해 보정 (자동 생성: ./gradlew updateSiegeBalance → SiegeBalanceReport). 직접 고치지 마세요.\n");
        out.append("# 서버에서 바꾸려면 config.yml 의 games.siege.balance.abilities.<능력>.skill / .ultimate 를 고칩니다.\n");
        out.append("# skill = 액티브·패시브 피해, ultimate = 궁극기 피해. 전체 배율(balance.ability-damage ")
                .append(num(SiegeBalanceSettings.DEFAULT_ABILITY_DAMAGE)).append(") 위에 곱합니다.\n");
        out.append("# 표와 계산 방법: docs/siege-balance.md\n");
        out.append("abilities:\n");
        double g = SiegeBalanceSettings.DEFAULT_ABILITY_DAMAGE;
        for (Row row : rows) {
            if (row.skill() == 1 && row.ult() == 1) {
                continue;
            }
            Profile raw = row.raw();
            out.append("  # ").append(row.info().name()).append(": 3초 폭딜 액티브 ")
                    .append(num(g * burst(raw.active(), 1, 1))).append("→").append(num(g * burst(raw.active(),
                            row.skill(), row.ult())))
                    .append(", 궁극기 ").append(num(g * burst(raw.ult(), 1, 1))).append("→")
                    .append(num(g * burst(raw.ult(), row.skill(), row.ult())))
                    .append(", 10초 ").append(num(g * tenSeconds(raw, row.cooldown(), 1, 1))).append("→")
                    .append(num(g * tenSeconds(raw, row.cooldown(), row.skill(), row.ult()))).append("\n");
            out.append("  ").append(row.info().id()).append(":\n");
            out.append("    skill: ").append(num(row.skill())).append("\n");
            out.append("    ultimate: ").append(num(row.ult())).append("\n");
        }
        return out.toString();
    }

    static String renderDocs(List<Row> rows) {
        double g = SiegeBalanceSettings.DEFAULT_ABILITY_DAMAGE;
        double top = 1 + SiegeBalanceSettings.DEFAULT_DAMAGE_PER_LEVEL * (TOP_LEVEL - 1);
        double[] before = rows.stream().filter(row -> !row.raw().utility())
                .mapToDouble(row -> tenSeconds(row.raw(), row.cooldown(), 1, 1)).toArray();
        double[] after = rows.stream().filter(row -> !row.raw().utility())
                .mapToDouble(row -> g * tenSeconds(row.raw(), row.cooldown(), row.skill(), row.ult())).toArray();
        double[] burstBefore = rows.stream().mapToDouble(row -> worstBurst(row.raw(), 1, 1)).toArray();
        double[] burstAfter = rows.stream().mapToDouble(row -> g * worstBurst(row.raw(), row.skill(), row.ult()))
                .toArray();
        StringBuilder out = new StringBuilder();
        out.append("# 공성전 능력 밸런스 표 (").append(rows.size()).append("종)\n\n");
        out.append("<!-- 자동 생성 문서입니다. 직접 고치지 말고 능력 수치나 SiegeBalanceModels / SiegePerks 를 고친 뒤 ")
                .append("`./gradlew updateSiegeBalance` 로 다시 만드세요. -->\n\n");
        out.append("공성전 능력 풀(`games.siege.ability-pool` 기본값)의 능력을 `AbilityInfo` 기본 수치로 계산한 표입니다. ")
                .append("초능력전쟁은 이 표와 상관없이 그대로입니다. 피해 단위는 체력 포인트(20 = 하트 10칸)입니다.\n\n");
        out.append("## 계산 방법\n\n");
        out.append("- **한 방**: 가장 큰 단일 타격. **3초 폭딜**: 액티브 한 번 / 궁극기 한 번이 한 명에게 3초 안에 주는 최대 피해")
                .append("(전부 맞는다고 가정, 빠져나갈 수 있는 장판은 ").append(num(SiegeBalanceModels.ZONE_SECONDS))
                .append("초만).\n");
        out.append("- **10초 피해**: 궁극기 한 번 + 쿨타임마다 액티브(0초부터), 10초 안에 들어가는 타격 전부.\n");
        out.append("- **통제**: 액티브 + 궁극기 한 번씩의 강한 통제(기절·속박·빙결·감옥·침묵) / 약한 통제(감속·실명) 초.\n");
        out.append("- 화상(바닐라 불), 낙하 피해, 근접 강화 패시브는 능력 피해가 아니므로 빠집니다(비고 참고).\n");
        out.append("- 공성전 피해 = 전체 배율 **×").append(num(g)).append("** × 능력별 보정(스킬/궁) × 레벨 보정 ")
                .append("(1레벨 위로 레벨마다 +").append(num(SiegeBalanceSettings.DEFAULT_DAMAGE_PER_LEVEL * 100))
                .append("%, 9레벨 ×").append(num(top)).append(") × 아이템·버프(`SiegeModifiers`).\n");
        out.append("- 보정 규칙: ① 1레벨에서 어떤 시전도 3초 폭딜 ").append(num(BURST_CAP))
                .append("(1레벨 체력의 40%) 이하 ② 10초 피해가 중앙값의 ").append(num(HIGH))
                .append("배를 넘으면 중앙값 ").append(num(HIGH)).append("배로 낮추고, ").append(num(LOW))
                .append("배 미만이면 올림(최대 ×").append(num(MAX_BOOST)).append(", 폭딜 상한 안에서) — 지원형(★지원)은 제외.\n");
        out.append("- 9레벨(3·6·9 특성 포함) 3초 폭딜은 ").append(num(BURST_CAP_9))
                .append("(9레벨 체력 28의 50%) 이하여야 합니다(테스트).\n\n");
        out.append("## 요약\n\n");
        out.append("| | 초능력전쟁 | 공성전 1레벨 |\n|---|---|---|\n");
        out.append("| 10초 피해 중앙값 (지원형 제외) | ").append(num(median(before))).append(" | ")
                .append(num(median(after))).append(" |\n");
        out.append("| 10초 피해 최대 | ").append(num(max(before))).append(" | ").append(num(max(after))).append(" |\n");
        out.append("| 3초 폭딜 중앙값 | ").append(num(median(burstBefore))).append(" | ")
                .append(num(median(burstAfter))).append(" |\n");
        out.append("| 3초 폭딜 최대 | ").append(num(max(burstBefore))).append(" | ").append(num(max(burstAfter)))
                .append(" |\n\n");
        out.append("## 능력별 (전 → 후)\n\n");
        out.append("전 = 초능력전쟁 수치, 후 = 공성전 1레벨(전체 배율 × 보정). 9Lv = 9레벨 레벨 보정 + 특성 포함 3초 폭딜.\n\n");
        out.append("| 능력 | 쿨 | 사거리 | 한 방 | 3초 폭딜 액티브 | 3초 폭딜 궁극기 | 10초 피해 | 통제 강/약 | 보정 스킬/궁 | 9Lv 폭딜 | 비고 |\n");
        out.append("|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (Row row : rows) {
            Profile raw = row.raw();
            out.append("| ").append(row.info().name()).append(" `").append(row.info().id()).append('`')
                    .append(raw.utility() ? " ★지원" : "")
                    .append(" | ").append(num(row.cooldown()))
                    .append(" | ").append(raw.range() > 0 ? num(raw.range()) : "-")
                    .append(" | ").append(pair(maxHit(raw, 1, 1), g * maxHit(raw, row.skill(), row.ult())))
                    .append(" | ").append(pair(burst(raw.active(), 1, 1), g * burst(raw.active(), row.skill(), row.ult())))
                    .append(" | ").append(pair(burst(raw.ult(), 1, 1), g * burst(raw.ult(), row.skill(), row.ult())))
                    .append(" | ").append(pair(tenSeconds(raw, row.cooldown(), 1, 1),
                            g * tenSeconds(raw, row.cooldown(), row.skill(), row.ult())))
                    .append(" | ").append(num(raw.hardCc())).append(" / ").append(num(raw.softCc()))
                    .append(" | ").append(num(row.skill())).append(" / ").append(num(row.ult()))
                    .append(" | ").append(num(g * top * worstBurst(row.top(), row.skill(), row.ult())))
                    .append(" | ").append(raw.note()).append(" |\n");
        }
        out.append("\n## 3·6·9레벨 특성\n\n");
        out.append("능력 수치 하나에 더하는 작은 강화입니다(`SiegePerks`). 통제 시간은 특성 하나에 최대 +")
                .append(num(SiegePerks.MAX_CONTROL_SECONDS)).append("초, 피해는 +25%, 지속은 +2초·+50%, 쿨타임은 −20%까지이며 ")
                .append("즉사·영구 효과는 없습니다.\n\n");
        out.append("| 능력 | 3레벨 | 6레벨 | 9레벨 |\n|---|---|---|---|\n");
        for (Row row : rows) {
            out.append("| ").append(row.info().name());
            for (int level : SiegePerks.LEVELS) {
                SiegePerks.Perk perk = SiegePerks.perk(row.info().id(), level);
                out.append(" | ").append(perk == null ? "-" : perk.text());
            }
            out.append(" |\n");
        }
        return out.toString();
    }

    private static String pair(double before, double after) {
        return num(before) + " → " + num(after);
    }

    private static double max(double[] values) {
        double best = 0;
        for (double value : values) {
            best = Math.max(best, value);
        }
        return best;
    }

    static String num(double value) {
        double rounded = Math.round(value * 100) / 100.0;
        if (Math.abs(rounded - Math.rint(rounded)) < 1.0E-9) {
            return Long.toString(Math.round(rounded));
        }
        String text = String.format(Locale.ROOT, "%.2f", rounded);
        return text.endsWith("0") ? text.substring(0, text.length() - 1) : text;
    }

    private static AbilityRegistry registry() {
        AbilityRegistry registry = new AbilityRegistry();
        BuiltinAbilities.registerAll(registry);
        return registry;
    }
}
