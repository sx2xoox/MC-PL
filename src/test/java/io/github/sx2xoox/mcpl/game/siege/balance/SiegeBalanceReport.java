package io.github.sx2xoox.mcpl.game.siege.balance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.github.sx2xoox.mcpl.game.siege.SiegeSettings;
import io.github.sx2xoox.mcpl.game.siege.balance.SiegeBalanceModels.Hit;
import io.github.sx2xoox.mcpl.game.siege.balance.SiegeBalanceModels.Profile;
import io.github.sx2xoox.mcpl.game.siege.balance.SiegeBalanceScores.LevelScore;
import io.github.sx2xoox.mcpl.game.siege.balance.SiegeBalanceScores.Metrics;
import io.github.sx2xoox.mcpl.game.siege.balance.SiegeBalanceScores.Result;
import io.github.sx2xoox.mcpl.game.siege.balance.SiegeBalanceScores.Score;
import io.github.sx2xoox.mcpl.game.siege.balance.SiegeBalanceSettings.Correction;
import io.github.sx2xoox.mcpl.game.siege.balance.SiegeRoles.Role;
import io.github.sx2xoox.mcpl.game.superpower.abilities.BuiltinAbilities;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityParams;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityRegistry;
import io.github.sx2xoox.mcpl.game.superpower.support.MatchHarness;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The 공성전 balance report. Two sources:
 * <ul>
 *     <li>the hand-written damage models ({@link SiegeBalanceModels}): the "perfect use" bursts of every cast, from
 *     the params, which hold the burst caps ({@link #BURST_CAP} at level 1, {@link #BURST_CAP_9} at level 9 with its
 *     perks);</li>
 *     <li>the balance lab's measurements ({@link SiegeLabData}, {@code ./gradlew siegeLab}): what the abilities really
 *     do in a test 공성전 match at levels 1, 6 and 9, scored by {@link SiegeBalanceScores} (1v1 duel model, siege and
 *     team contribution, role bands).</li>
 * </ul>
 * From them it chooses the per-ability corrections (skill, ultimate, objective) that bring every ability's total score
 * near the median and into its role's band, never past the burst caps, and writes docs/siege-balance.md and the
 * bundled {@code siege-balance.yml} ({@code ./gradlew updateSiegeBalance}); it fails while either is stale or the lab's
 * measurements are older than the params ({@link SiegeLabData#fingerprint}).
 */
class SiegeBalanceReport {

    static final double BURST_CAP = 8.0;
    /** 50% of the health of a level-9 player (20 + 8). */
    static final double BURST_CAP_9 = 14.0;
    static final double FLOOR = 0.3;
    static final double MAX_FACTOR = 1.8;
    static final double OBJECTIVE_MIN = 0.4;
    static final double OBJECTIVE_MAX = 2.5;
    static final double WINDOW = 10.0;
    static final double BURST_WINDOW = 3.0;
    static final int TOP_LEVEL = 9;
    /** The goal: every total within this share of the median (exceptions listed in the docs). */
    static final double GOAL = 0.12;
    /** The solver aims a little inside the goal. */
    static final double AIM = 0.06;
    /** Siege contribution kept within this band (1 = the pool's average) through the objective correction. */
    static final double SIEGE_MIN = 0.75;
    static final double SIEGE_MAX = 1.35;
    static final int ITERATIONS = 80;
    /**
     * A kit whose buffs of its own basic attacks ({@link SiegeBalanceScores#basicBoost}) are at least this share of its
     * damage per second (6레벨, corrections 1), and at least {@link #MELEE_KIT_MIN} a second, is a "평타 강화형": the
     * corrections only touch ability damage, so raising them would make the kit stronger without touching what made it
     * weak or strong. Its skill and ultimate
     * corrections stay at or under {@link #MELEE_KIT_MAX}; a weak one is fixed through {@link SiegeTuning} instead.
     */
    static final double MELEE_KIT_SHARE = 0.25;
    static final double MELEE_KIT_MIN = 0.4;
    static final double MELEE_KIT_MAX = 1.0;

    /** A correction chosen by hand: the solver keeps it as it is (through every {@code updateSiegeBalance}). */
    record Pinned(Correction correction, String reason) {
    }

    /**
     * Corrections decided by hand (real-match feedback the lab cannot see): the solver never moves them and
     * {@code siege-balance.yml} always carries them.
     */
    static final Map<String, Pinned> PINNED = pinned();

    private static Map<String, Pinned> pinned() {
        Map<String, Pinned> out = new LinkedHashMap<>();
        out.put("berserker", new Pinned(new Correction(0.8, 0.8, 1.0),
                "실전 피드백(보정 1에서도 매우 강함): 힘은 분노·광폭화가 키우는 평타라 보정을 올리지 않음. 돌진만 조금 낮추고 "
                        + "분노·광폭화 수치는 SiegeTuning 으로 낮춤"));
        return out;
    }

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

    /** One pool ability's model numbers and the caps they give its corrections. */
    record Row(AbilityInfo info, double cooldown, Profile raw, Profile top, double capSkill, double capUlt) {
    }

    /** Everything the report shows. */
    record Report(List<Row> rows, Map<String, Correction> corrections, Result before, Result after,
                  Map<String, SiegeRealStats.Entry> real, Map<String, Double> calibration, SiegeLabData.Table lab) {
    }

    @Test
    void siegeBalanceIsUpToDateAndWithinCaps() throws IOException {
        AbilityRegistry registry = registry();
        SiegeLabData.Table lab = SiegeLabData.read();
        assertEquals(SiegeLabData.fingerprint(registry), lab.fingerprint(),
                SiegeLabData.FILE + " was measured with other ability numbers: run ./gradlew siegeLab");
        Report report = build(registry, lab);
        double g = SiegeBalanceSettings.DEFAULT_ABILITY_DAMAGE;
        double top = SiegeBalanceScores.levelFactor(TOP_LEVEL);
        List<String> problems = new ArrayList<>();
        for (Row row : report.rows()) {
            Correction c = report.corrections().get(row.info().id());
            double l1 = g * worstBurst(row.raw(), c.skill(), c.ultimate());
            if (l1 > BURST_CAP + 1.0E-6) {
                problems.add(row.info().id() + " level 1 burst " + l1);
            }
            double l9 = g * top * worstBurst(row.top(), c.skill(), c.ultimate());
            if (l9 > BURST_CAP_9 + 1.0E-6) {
                problems.add(row.info().id() + " level 9 burst " + l9);
            }
        }
        for (Row row : report.rows()) {
            String id = row.info().id();
            Score score = report.after().score(id);
            boolean outside = Math.abs(rel(score.total(), report.after().median())) > GOAL * 100 + 1.0E-9
                    || !score.bandProblems().isEmpty();
            if (outside && !EXCEPTIONS.containsKey(id)) {
                problems.add(id + " is outside the goal (" + num(rel(score.total(), report.after().median()))
                        + "%, " + score.bandProblems() + ") without a reason in EXCEPTIONS");
            }
        }
        String docs = renderDocs(report);
        String yaml = renderYaml(report);
        if (Boolean.getBoolean("mcpl.updateDocs")) {
            Files.writeString(DOCS, docs, StandardCharsets.UTF_8);
            Files.writeString(YAML, yaml, StandardCharsets.UTF_8);
        }
        assertEquals(List.of(), problems, "bursts over the caps, abilities outside the goal");
        assertEquals(docs, Files.exists(DOCS) ? Files.readString(DOCS, StandardCharsets.UTF_8) : "",
                DOCS + " is out of date: run ./gradlew updateSiegeBalance");
        assertEquals(yaml, Files.exists(YAML) ? Files.readString(YAML, StandardCharsets.UTF_8) : "",
                YAML + " is out of date: run ./gradlew updateSiegeBalance");
    }

    @Test
    void everyPoolAbilityHasAModelPerksARoleAndLabRows() throws IOException {
        AbilityRegistry registry = registry();
        SiegeLabData.Table lab = SiegeLabData.read();
        List<String> problems = new ArrayList<>();
        for (String id : SiegeSettings.DEFAULT_POOL) {
            assertTrue(SiegeBalanceModels.all().containsKey(id), "no balance model for " + id);
            assertTrue(registry.get(id) != null, "unknown pool ability " + id);
            assertTrue(SiegeRoles.of(id) != null, "no role for " + id);
            for (int level : SiegeLabData.LEVELS) {
                assertTrue(lab.row(id, level) != null, "no lab row for " + id + " level " + level
                        + ": run ./gradlew siegeLab -Pabilities=" + id);
            }
            problems.addAll(SiegeTuning.violations(registry.get(id).info()));
        }
        for (SiegeTuning.Tune tune : SiegeTuning.all()) {
            assertTrue(SiegeSettings.DEFAULT_POOL.contains(tune.abilityId()), "tuning outside the pool: " + tune);
        }
        assertEquals(List.of(), problems, "tuning");
        assertEquals(SiegeSettings.DEFAULT_POOL.size(), SiegeBalanceModels.all().size());
        assertEquals(SiegeSettings.DEFAULT_POOL.size(), SiegeRoles.abilities().size());
    }

    // ------------------------------------------------------------------ analysis

    static Report build(AbilityRegistry registry, SiegeLabData.Table lab) {
        List<Row> rows = analyse(registry);
        List<String> ids = SiegeSettings.DEFAULT_POOL;
        Map<String, SiegeRealStats.Entry> real = SiegeRealStats.read();
        Map<String, Double> labDps = new LinkedHashMap<>();
        Result plain = SiegeBalanceScores.evaluate(lab, ids, Map.of(), Map.of());
        for (String id : ids) {
            labDps.put(id, plain.score(id).levels().get(6).metrics().dps());
        }
        Map<String, Double> calibration = SiegeRealStats.calibration(real, labDps);
        Result before = SiegeBalanceScores.evaluate(lab, ids, Map.of(), calibration);
        Map<String, Correction> corrections = solve(rows, lab, ids, calibration);
        Result after = SiegeBalanceScores.evaluate(lab, ids, corrections, calibration);
        return new Report(rows, corrections, before, after, real, calibration, lab);
    }

    /** The model numbers of every pool ability and the burst caps on its corrections. */
    static List<Row> analyse(AbilityRegistry registry) {
        double g = SiegeBalanceSettings.DEFAULT_ABILITY_DAMAGE;
        double top = SiegeBalanceScores.levelFactor(TOP_LEVEL);
        List<Row> rows = new ArrayList<>();
        SiegeBalance balance = new SiegeBalance(SiegeBalanceSettings.defaults(), new SiegeModifiers());
        for (String id : SiegeSettings.DEFAULT_POOL) {
            AbilityInfo info = registry.get(id).info();
            AbilityParams params = AbilityParams.defaults(info)
                    .withAdjuster((key, value) -> balance.param(id, 1, key, value));
            Profile raw = SiegeBalanceModels.all().get(id).apply(params);
            AbilityParams topParams = AbilityParams.defaults(info)
                    .withAdjuster((key, value) -> balance.param(id, TOP_LEVEL, key, value));
            Profile top9 = SiegeBalanceModels.all().get(id).apply(topParams);
            double capSkill = Math.min(cap(BURST_CAP, g * burst(raw.active(), 1, 0)),
                    cap(BURST_CAP_9, g * top * burst(top9.active(), 1, 0)));
            // the ultimate's bursts may hold skill hits (rocket punches, marked shots): those cap the skill too
            capSkill = Math.min(capSkill, Math.min(cap(BURST_CAP, g * burst(raw.ult(), 1, 0)),
                    cap(BURST_CAP_9, g * top * burst(top9.ult(), 1, 0))));
            double capUlt = MAX_FACTOR;
            for (double u = MAX_FACTOR; u >= FLOOR - 1.0E-9; u = round(u - 0.05)) {
                capUlt = u;
                if (g * burst(raw.ult(), capSkill, u) <= BURST_CAP + 1.0E-9
                        && g * top * burst(top9.ult(), capSkill, u) <= BURST_CAP_9 + 1.0E-9) {
                    break;
                }
            }
            rows.add(new Row(info, params.cooldownSeconds(), raw, top9, Math.max(FLOOR, capSkill), capUlt));
        }
        return rows;
    }

    private static double cap(double limit, double burst) {
        return burst < 1.0E-9 ? MAX_FACTOR : Math.min(MAX_FACTOR, round(limit / burst));
    }

    /**
     * The corrections: starting from 1, every round moves each ability's skill and ultimate factors towards the
     * median total (and into its role's damage band), its objective factor towards the siege band, never past the
     * model's and the lab's burst caps; the result is rounded down to 0.05.
     */
    static Map<String, Correction> solve(List<Row> rows, SiegeLabData.Table lab, List<String> ids,
                                         Map<String, Double> calibration) {
        Map<String, double[]> f = new LinkedHashMap<>();
        Map<String, Row> byId = new LinkedHashMap<>();
        for (Row row : rows) {
            byId.put(row.info().id(), row);
            f.put(row.info().id(), new double[] {1, 1, 1});
        }
        Map<String, Boolean> meleeKits = meleeKits(lab, ids);
        for (String id : ids) {
            limit(id, f.get(id), byId.get(id), lab, calibration);
            hold(id, f.get(id), meleeKits.get(id));
        }
        for (int iteration = 0; iteration < ITERATIONS; iteration++) {
            Result result = SiegeBalanceScores.evaluate(lab, ids, corrections(f, false), calibration);
            double median = result.median();
            for (String id : ids) {
                Score score = result.score(id);
                double[] c = f.get(id);
                double step = 1;
                double ratio = score.total() / median;
                if (Math.abs(ratio - 1) > AIM) {
                    step = Math.pow(1 / ratio, 1.4);
                }
                double dps = score.normalized().get("dps");
                Role role = score.role();
                double dpsMax = role == Role.TANK || role == Role.SUPPORT ? 0.95 : role == Role.FIGHTER ? 1.55 : 99;
                double dpsMin = role == Role.FIGHTER ? 0.55 : role == Role.MARKSMAN ? 0.9 : 0;
                if (dps > dpsMax) {
                    step = Math.min(step, dpsMax / dps);
                } else if (dps > 0 && dps < dpsMin) {
                    step = Math.max(step, dpsMin / dps);
                }
                if (role == Role.ASSASSIN) {
                    double burst = score.normalized().get("burst");
                    if (burst > 0 && burst < 1.05) {
                        step = Math.max(step, 1.05 / burst);
                    }
                }
                step = Math.max(0.85, Math.min(1.15, step));
                // a kit without damage of its own (heals, escapes, time stop) keeps 1: its factors would change nothing
                SiegeLabData.Row six = lab.row(id, 6);
                boolean damages = six.get("dmg30") + six.get("ult12") + six.get("aoe5") + six.get("tower20") > 0.5;
                if (damages) {
                    c[0] *= step;
                    c[1] *= step;
                }
                double siege = score.siege();
                if (damages && siege > 0 && (siege < SIEGE_MIN || siege > SIEGE_MAX)) {
                    double target = siege < SIEGE_MIN ? SIEGE_MIN + 0.05 : SIEGE_MAX - 0.05;
                    c[2] *= Math.max(0.85, Math.min(1.15, Math.pow(target / siege, 0.8)));
                }
                limit(id, c, byId.get(id), lab, calibration);
                hold(id, c, meleeKits.get(id));
            }
        }
        return corrections(f, true);
    }

    /** The 평타 강화형 cap ({@link #MELEE_KIT_MAX}) and the hand-picked corrections ({@link #PINNED}). */
    private static void hold(String id, double[] c, boolean meleeKit) {
        if (meleeKit) {
            c[0] = Math.min(c[0], MELEE_KIT_MAX);
            c[1] = Math.min(c[1], MELEE_KIT_MAX);
        }
        Pinned pin = PINNED.get(id);
        if (pin != null) {
            c[0] = pin.correction().skill();
            c[1] = pin.correction().ultimate();
            c[2] = pin.correction().objective();
        }
    }

    /** The share of a kit's damage per second (6레벨, corrections 1) that comes from buffing its own basic attacks. */
    static double meleeShare(SiegeLabData.Table lab, String id) {
        SiegeLabData.Row six = lab.row(id, 6);
        Metrics m = SiegeBalanceScores.metrics(six, Correction.NONE, 1.0);
        return m.dps() < 1.0E-9 ? 0 : m.basicBoost() / m.dps();
    }

    /** Whether {@code id} is a 평타 강화형 ({@link #MELEE_KIT_SHARE}, {@link #MELEE_KIT_MIN}). */
    static boolean meleeKit(SiegeLabData.Table lab, String id) {
        Metrics m = SiegeBalanceScores.metrics(lab.row(id, 6), Correction.NONE, 1.0);
        return meleeShare(lab, id) >= MELEE_KIT_SHARE - 1.0E-9 && m.basicBoost() >= MELEE_KIT_MIN - 1.0E-9;
    }

    static Map<String, Boolean> meleeKits(SiegeLabData.Table lab, List<String> ids) {
        Map<String, Boolean> out = new LinkedHashMap<>();
        for (String id : ids) {
            out.put(id, meleeKit(lab, id));
        }
        return out;
    }

    /** Holds factors to their range and to the bursts caps of the model and of the lab (levels 1 and 9). */
    private static void limit(String id, double[] c, Row row, SiegeLabData.Table lab, Map<String, Double> calibration) {
        c[0] = Math.max(FLOOR, Math.min(Math.min(MAX_FACTOR, row.capSkill()), c[0]));
        c[1] = Math.max(FLOOR, Math.min(Math.min(MAX_FACTOR, row.capUlt()), c[1]));
        c[2] = Math.max(OBJECTIVE_MIN, Math.min(OBJECTIVE_MAX, c[2]));
        for (int level : new int[] {1, TOP_LEVEL}) {
            SiegeLabData.Row r = lab.row(id, level);
            double cap = level == 1 ? BURST_CAP : BURST_CAP_9;
            double skillWindow = SiegeBalanceScores.window(r.s1(), 3);
            if (skillWindow * c[0] > cap) {
                c[0] = Math.max(FLOOR, cap / skillWindow);
            }
            double mixed = SiegeBalanceScores.window(r.s2(), 3);
            double s = Math.max(0, r.get("skill12"));
            double u = Math.max(0, r.get("ult12") - s);
            if (u > 1.0E-9 && mixed * (s * c[0] + u * c[1]) / (s + u) > cap) {
                c[1] = Math.max(FLOOR, ((s + u) * cap / mixed - s * c[0]) / u);
            }
        }
    }

    private static Map<String, Correction> corrections(Map<String, double[]> f, boolean rounded) {
        Map<String, Correction> out = new LinkedHashMap<>();
        f.forEach((id, c) -> out.put(id, rounded
                ? new Correction(Math.max(FLOOR, round(c[0])), Math.max(FLOOR, round(c[1])),
                Math.max(OBJECTIVE_MIN, round(c[2])))
                : new Correction(c[0], c[1], c[2])));
        return out;
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

    // ------------------------------------------------------------------ output

    static String renderYaml(Report report) {
        StringBuilder out = new StringBuilder();
        out.append("# 공성전 능력별 피해 보정 (자동 생성: ./gradlew updateSiegeBalance → SiegeBalanceReport). 직접 고치지 마세요.\n");
        out.append("# 서버에서 바꾸려면 config.yml 의 games.siege.balance.abilities.<능력>.skill / .ultimate / .objective 를 고칩니다.\n");
        out.append("# skill = 액티브·패시브 피해, ultimate = 궁극기 피해 (전체 배율 balance.ability-damage ")
                .append(num(SiegeBalanceSettings.DEFAULT_ABILITY_DAMAGE)).append(" 위에 곱함),\n");
        out.append("# objective = 건물·미니언·정글 몬스터에게 주는 능력 피해 (전체 배율 없음). 점수·계산: docs/siege-balance.md\n");
        out.append("abilities:\n");
        for (Row row : report.rows()) {
            String id = row.info().id();
            Correction c = report.corrections().get(id);
            if (c.equals(Correction.NONE)) {
                continue;
            }
            Score before = report.before().score(id);
            Score after = report.after().score(id);
            out.append("  # ").append(row.info().name()).append(" (").append(before.role().label()).append("): 점수 ")
                    .append(num(rel(before.total(), report.before().median()))).append("→")
                    .append(num(rel(after.total(), report.after().median()))).append("% (중앙값 대비)\n");
            Pinned pin = PINNED.get(id);
            if (pin != null) {
                out.append("  # 수동 결정(SiegeBalanceReport.PINNED, 재생성해도 유지): ").append(pin.reason()).append("\n");
            }
            out.append("  ").append(id).append(":\n");
            out.append("    skill: ").append(num(c.skill())).append("\n");
            out.append("    ultimate: ").append(num(c.ultimate())).append("\n");
            out.append("    objective: ").append(num(c.objective())).append("\n");
        }
        return out.toString();
    }

    /** {@code value} as a percentage of {@code median} minus 100 (−12 = 12% under). */
    static double rel(double value, double median) {
        return median <= 0 ? 0 : 100 * (value / median - 1);
    }

    static String signed(double value) {
        String text = num(value);
        return value > 0 ? "+" + text : text;
    }

    static String renderDocs(Report report) {
        List<Row> rows = report.rows();
        Result before = report.before();
        Result after = report.after();
        double g = SiegeBalanceSettings.DEFAULT_ABILITY_DAMAGE;
        double top = SiegeBalanceScores.levelFactor(TOP_LEVEL);
        StringBuilder out = new StringBuilder();
        out.append("# 공성전 능력 밸런스 표 (").append(rows.size()).append("종)\n\n");
        out.append("<!-- 자동 생성 문서입니다. 직접 고치지 마세요. 능력 수치·SiegeTuning·SiegePerks·SiegeRoles 를 고친 뒤 ")
                .append("`./gradlew siegeLab` (측정) → `./gradlew updateSiegeBalance` (이 문서와 siege-balance.yml) 순서로 ")
                .append("다시 만드세요. -->\n\n");
        out.append("공성전 능력 풀(`games.siege.ability-pool` 기본값)의 스킬·궁극기 밸런스입니다. 초능력전쟁은 이 표와 상관없이 ")
                .append("그대로입니다. 피해·회복 단위는 체력 포인트(20 = 하트 10칸), 시간은 초, 거리는 블록입니다. ")
                .append("평타(`BasicAttacks`)는 따로 다룹니다(docs/siege-v2.md §8).\n\n");
        renderMethod(out);
        renderSummary(out, report);
        renderTopBottom(out, report);
        renderTable(out, report);
        renderLevels(out, report);
        renderDuels(out, report);
        renderTuning(out, report);
        renderMelee(out, report);
        renderExceptions(out, report);
        renderMechanics(out, report);
        renderReal(out, report);
        renderModel(out, report, g, top);
        renderPerks(out, rows);
        return out.toString();
    }

    private static void renderMethod(StringBuilder out) {
        out.append("## 측정 방법 (밸런스 랩)\n\n");
        out.append("`SiegeBalanceLab`(`./gradlew siegeLab`)이 능력마다 1·6·9레벨에서 테스트 서버의 실제 공성전 경기(MockBukkit, ")
                .append("물리 있는 평지)를 돌려 `docs/siege-balance-lab.csv` 에 씁니다. 공격자는 스스로 움직이지 않고 매 틱 대상을 ")
                .append("조준하며, 적은 제자리에 서서 매 틱 체력이 다시 차서 죽지 않습니다. 같은 팀 1명이 공격자 뒤 2블록에 섭니다. ")
                .append("나머지는 모두 효과 없는 더미 능력입니다. 능력별 보정은 1로 두고 재며(보고서가 곱함), 공성전 전용 수치·특성은 ")
                .append("켜고 잽니다.\n\n");
        out.append("| 장면 | 내용 | 지표 |\n|---|---|---|\n");
        out.append("| 사거리 (1레벨) | 3·5·8·12·18·26블록에서 액티브 한 번 | 유효 사거리 = 최고의 절반 이상 맞는 가장 먼 거리, 결투 거리 = 최고의 90% 이상인 가장 먼 거리 |\n");
        out.append("| 결투 30초 | 쿨마다 액티브(시전 전 제자리로), 0.5초마다 대상이 공격자·아군에게 시험타 2 | 초당 피해, 대상 통제, 넉백, 공격자 이동, 회복·보호막·막은 피해·무적, 아군 지원 |\n");
        out.append("| 궁극기 12초 | 0초 궁극기 + 쿨마다 액티브, 결투 거리와 4블록 중 나은 쪽 | 궁극기 몫 = 이 장면 − 결투 첫 12초 |\n");
        out.append("| 밀집 10초 | 적 5명이 1.2블록 안에 모임, 궁극기 + 액티브 | 대상별 피해 → 광역(둘째 이후 대상 합) |\n");
        out.append("| 타워 20초 | 적 바깥 타워 5블록 앞(타워는 쏘지 않음), 궁극기 + 액티브 | 건물 피해 |\n");
        out.append("| 근접 12초 | 2.5블록에서 평타만(공격자 체력 100·50·25%로 고정) / 50%에서 먼저 궁극기 / 50%에서 쿨마다 액티브. ")
                .append("평타 피해와 능력 피해를 따로 셈, 힘·나약함 효과는 바닐라처럼 공격력에 반영(MockBukkit 보완), 치명타 끔 | ")
                .append("평타 강화(잃은 체력 비례 패시브, 액티브·궁극기의 평타 버프), 근접 흡혈, 근접에서의 궁극기·액티브 능력 피해 |\n\n");
        out.append("### 지표 (30초 싸움 기준)\n\n");
        out.append("- 궁극기는 ").append(num(SiegeBalanceScores.ULT_PERIOD)).append("초에 한 번(싸움 중 게이지 + 재충전 잠금) → 30초에 ×")
                .append(num(SiegeBalanceScores.ULT_SHARE)).append(".\n");
        out.append("- **지속딜** = (30초 스킬 피해 + 궁극기 몫) / 30초. 스킬·궁극기 몫은 근접(평타와 함께) 장면이 더 크면 그쪽(다음 적중 강화·근접 강화 능력). **폭딜** = 아무 3초 동안 가장 큰 피해(액티브 한 번, 또는 궁극기 + 액티브).\n");
        out.append("- **평타 강화** = 근접 장면에서 키트가 자기 평타에 더한 피해: (체력 100·50·25% 평균 − 100%) + (액티브 − 50%) ")
                .append("는 12초마다, (궁극기 − 50%)는 궁극기 주기마다, 싸움의 ").append(num(SiegeBalanceScores.MELEE_UPTIME * 100))
                .append("%를 근접에서 휘두른다고 봄. 지속딜에 더하고(능력 보정이 안 곱해짐), 궁극기·패시브 몫의 3초분은 폭딜에도 더함. ")
                .append("**근접 흡혈** = 그 장면의 회복 − 결투의 회복, 같은 비율로 생존에 더함.\n");
        out.append("- **통제** = 강한 통제(기절·속박·이동 잠금·공중에 뜸) ×1 + 침묵 ×").append(num(SiegeBalanceScores.SILENCE_WEIGHT))
                .append(" + 약한 통제(감속 비율·실명·점프 잠금) ×").append(num(SiegeBalanceScores.SOFT_WEIGHT)).append(" (초).\n");
        out.append("- **생존** = 자기 회복 + 보호막(흡수) + 시험타에서 막은 피해 + 늘어난 최대 체력 ×0.5 − 자해 ×0.5. **무적** = 시험타를 거의 안 받은 초.\n");
        out.append("- **이동** = 공격자가 능력으로 움직인 거리. **지원** = 아군 회복 + 보호막 + 막은 피해 + 버프 초 ×0.3.\n");
        out.append("- **광역** = 밀집 장면에서 둘째 이후 대상들이 받은 피해 합. **건물** = 타워 20초 피해(×objective).\n");
        out.append("- **웨이브** = 미니언 한 무리(체력 ").append(num(SiegeBalanceScores.WAVE_HEALTH)).append(")를 밀집 장면의 속도로 정리하는 초, ")
                .append("**정글** = 곰인형 대장(체력 ").append(num(SiegeBalanceScores.JUNGLE_HEALTH)).append(")을 지속딜로 잡는 초(배율 없는 피해 ×objective).\n\n");
        out.append("### 점수\n\n");
        out.append("- **전투력** = 1v1 결투 모델에서 다른 모든 능력을 상대로 한 평균 승률. A가 B를 잡는 시간 = B의 체력(1레벨 20, 6레벨 26, ")
                .append("9레벨 28)을 A의 처음 3초(폭딜 속도) + 이후 지속딜로 깎는 시간, B의 회복·보호막과 막은 피해(A의 딜에 비례)를 빼고, ")
                .append("B의 통제 비율(30초 중 통제 초, 최대 50%)만큼 A의 딜이 줄며, B가 사거리에서 앞서면 A는 그 차이를 초속 ")
                .append(num(SiegeBalanceScores.CLOSE_SPEED)).append("블록(이동기로 줄임)으로 좁힌 뒤에야 칩니다(최대 3초). ")
                .append("승률 = T(B→A)² / (T(A→B)² + T(B→A)²).\n");
        out.append("- **공성 기여** = 건물 ×0.5 + 웨이브 속도 ×0.3 + 정글 속도 ×0.2 (풀 평균 = 1). ")
                .append("**팀 기여** = 광역 ×0.3 + 통제 ×0.3 + 지원 ×0.4 (풀 평균 = 1, 각 지표 최대 3).\n");
        out.append("- **전체 점수** = 100 × (전투력/평균 ×").append(num(SiegeBalanceScores.W_COMBAT)).append(" + 공성 ×")
                .append(num(SiegeBalanceScores.W_SIEGE)).append(" + 팀 ×").append(num(SiegeBalanceScores.W_TEAM))
                .append("), 1·6·9레벨 평균. 목표: 모든 능력이 전체 점수 중앙값 ±").append(num(GOAL * 100)).append("% 안, 역할 대역 안.\n\n");
        out.append("### 역할 대역 (`SiegeRoles`, 1 = 풀 평균)\n\n");
        out.append("| 역할 | 대역 |\n|---|---|\n");
        for (Role role : Role.values()) {
            List<String> texts = SiegeBalanceScores.bands(role).stream().map(SiegeBalanceScores.Band::text).toList();
            out.append("| ").append(role.label()).append(" | ").append(String.join(", ", texts)).append(" |\n");
        }
        out.append("\n### 보정 규칙\n\n");
        out.append("- 공성전 피해 = 전체 배율 **×").append(num(SiegeBalanceSettings.DEFAULT_ABILITY_DAMAGE))
                .append("** × 능력별 보정(스킬/궁) × 레벨 보정(레벨마다 +")
                .append(num(SiegeBalanceSettings.DEFAULT_DAMAGE_PER_LEVEL * 100)).append("%, 9레벨 ×")
                .append(num(SiegeBalanceScores.levelFactor(TOP_LEVEL))).append(") × 아이템·버프(`SiegeModifiers`). ")
                .append("건물·미니언·정글 몬스터에게는 능력 피해 × objective 보정만.\n");
        out.append("- `SiegeBalanceReport` 가 보정을 고릅니다: 1에서 시작해 전체 점수가 중앙값 ±").append(num(AIM * 100))
                .append("% 밖이면 스킬·궁 보정을 함께 옮기고(한 번에 ±15%), 역할의 지속딜·폭딜 대역으로 밀고, 공성 기여가 ")
                .append(num(SIEGE_MIN)).append("~").append(num(SIEGE_MAX)).append(" 밖이면 objective 를 옮기기를 ")
                .append(ITERATIONS).append("번, 마지막에 0.05 단위로 내림. 보정 범위 ").append(num(FLOOR)).append("~")
                .append(num(MAX_FACTOR)).append(" (objective ").append(num(OBJECTIVE_MIN)).append("~").append(num(OBJECTIVE_MAX))
                .append(").\n");
        out.append("- 폭딜 상한(모델·랩 둘 다): 1레벨에서 어떤 시전도 3초 폭딜 ").append(num(BURST_CAP))
                .append("(체력의 40%) 이하, 9레벨(특성 포함)은 ").append(num(BURST_CAP_9)).append("(체력 28의 50%) 이하(테스트).\n");
        out.append("- 피해로 못 고치는 원인(긴 통제·무적·넉백·쿨타임)은 `SiegeTuning`(공성전 전용 수치, 아래 표)로 고칩니다.\n");
        out.append("- **평타 강화형**(지속딜의 ").append(num(MELEE_KIT_SHARE * 100))
                .append("% 이상, 초당 ").append(num(MELEE_KIT_MIN)).append(" 이상이 자기 평타 강화): 스킬·궁 보정 최대 ").append(num(MELEE_KIT_MAX))
                .append(". 보정은 능력 피해에만 곱해져 강함·약함의 원인(평타 버프)을 못 고치므로, 약하면 `SiegeTuning` 으로 고칩니다.\n");
        out.append("- **수동 결정**(`SiegeBalanceReport.PINNED`): 실전 피드백처럼 랩이 못 보는 근거로 정한 보정은 자동 산출이 덮어쓰지 않습니다(아래 표).\n\n");
    }

    private static void renderSummary(StringBuilder out, Report report) {
        out.append("## 요약\n\n");
        out.append("| | 보정 전(전부 1) | 보정 후 |\n|---|---|---|\n");
        Result[] results = {report.before(), report.after()};
        String[] labels = {"전체 점수 중앙값", "최저 (중앙값 대비)", "최고 (중앙값 대비)",
                "±" + num(GOAL * 100) + "% 밖 능력 수", "역할 대역 밖 능력 수", "1v1 승률 35~65% 밖 대결 비율 (6레벨)"};
        for (int i = 0; i < labels.length; i++) {
            out.append("| ").append(labels[i]);
            for (Result r : results) {
                out.append(" | ").append(switch (i) {
                    case 0 -> num(r.median());
                    case 1 -> signed(rel(min(r), r.median())) + "%";
                    case 2 -> signed(rel(max(r), r.median())) + "%";
                    case 3 -> Long.toString(r.scores().values().stream()
                            .filter(s -> Math.abs(rel(s.total(), r.median())) > GOAL * 100 + 1.0E-9).count());
                    case 4 -> Long.toString(r.scores().values().stream().filter(s -> !s.bandProblems().isEmpty()).count());
                    default -> num(100 * r.scores().values().stream()
                            .mapToDouble(s -> s.levels().get(6).winrateOutliers()).average().orElse(0)) + "%";
                });
            }
            out.append(" |\n");
        }
        out.append("\n역할별 평균 전체 점수 (보정 후, 중앙값 대비): ");
        List<String> parts = new ArrayList<>();
        for (Role role : Role.values()) {
            double avg = report.after().scores().values().stream().filter(s -> s.role() == role)
                    .mapToDouble(Score::total).average().orElse(0);
            parts.add(role.label() + " " + signed(rel(avg, report.after().median())) + "%");
        }
        out.append(String.join(" · ", parts)).append("\n\n");
    }

    private static double min(Result r) {
        return r.scores().values().stream().mapToDouble(Score::total).min().orElse(0);
    }

    private static double max(Result r) {
        return r.scores().values().stream().mapToDouble(Score::total).max().orElse(0);
    }

    private static void renderTopBottom(StringBuilder out, Report report) {
        Result before = report.before();
        List<Score> sorted = new ArrayList<>(before.scores().values());
        sorted.sort(Comparator.comparingDouble(Score::total).reversed());
        out.append("### 보정 전 상위·하위 10\n\n");
        out.append("| 순위 | 상위 | 점수 | 하위 | 점수 |\n|---|---|---|---|---|\n");
        for (int i = 0; i < 10 && i < sorted.size(); i++) {
            Score hi = sorted.get(i);
            Score lo = sorted.get(sorted.size() - 1 - i);
            out.append("| ").append(i + 1).append(" | ").append(name(report, hi.id())).append(" | ")
                    .append(signed(rel(hi.total(), before.median()))).append("% | ").append(name(report, lo.id()))
                    .append(" | ").append(signed(rel(lo.total(), before.median()))).append("% |\n");
        }
        out.append("\n");
    }

    private static String name(Report report, String id) {
        for (Row row : report.rows()) {
            if (row.info().id().equals(id)) {
                return row.info().name();
            }
        }
        return id;
    }

    private static void renderTable(StringBuilder out, Report report) {
        out.append("## 능력별 (6레벨 지표, 보정 후)\n\n");
        out.append("점수는 중앙값 대비 %(1·6·9레벨 평균), 전 → 후. 전투력은 6레벨 1v1 평균 승률. ")
                .append("지표 단위: 지속딜 초당, 폭딜·생존·지원·광역·건물 체력, 통제·무적 초, 이동·사거리 블록, 웨이브·정글 초.\n\n");
        out.append("| 능력 | 역할 | 점수 | 전투력 | 공성 | 팀 | 지속딜 | 폭딜 | 통제 | 생존 | 이동 | 사거리 | 지원 | 광역 | 건물 | 웨이브 | 정글 | 보정 스킬/궁/건물 | 역할 대역 |\n");
        out.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (Row row : report.rows()) {
            String id = row.info().id();
            Score b = report.before().score(id);
            Score a = report.after().score(id);
            LevelScore six = a.levels().get(6);
            Metrics m = six.metrics();
            Correction c = report.corrections().get(id);
            out.append("| ").append(row.info().name()).append(" `").append(id).append("` | ").append(a.role().label())
                    .append(" | ").append(signed(rel(b.total(), report.before().median()))).append(" → ")
                    .append(signed(rel(a.total(), report.after().median())))
                    .append(" | ").append(num(100 * six.combat())).append("%")
                    .append(" | ").append(num(six.siege())).append(" | ").append(num(six.team()))
                    .append(" | ").append(num(m.dps())).append(" | ").append(num(m.burst3()))
                    .append(" | ").append(num(m.cc())).append(" | ").append(num(m.survival()))
                    .append(" | ").append(num(m.mobility())).append(" | ").append(m.range() > 0 ? num(m.range()) : "-")
                    .append(" | ").append(num(m.support())).append(" | ").append(num(m.aoeExtra()))
                    .append(" | ").append(num(m.tower())).append(" | ").append(num(m.waveTime()))
                    .append(" | ").append(num(m.jungleTime()))
                    .append(" | ").append(num(c.skill())).append(" / ").append(num(c.ultimate())).append(" / ")
                    .append(num(c.objective()))
                    .append(" | ").append(a.bandProblems().isEmpty() ? "✓" : String.join(", ", a.bandProblems()))
                    .append(" |\n");
        }
        out.append("\n");
    }

    private static void renderLevels(StringBuilder out, Report report) {
        out.append("## 레벨별 전체 점수 (보정 후, 그 레벨 중앙값 대비 %)\n\n");
        out.append("| 능력 | 1레벨 | 6레벨 | 9레벨 | 1v1 승률 1/6/9 |\n|---|---|---|---|---|\n");
        Map<Integer, Double> medians = new LinkedHashMap<>();
        for (int level : SiegeLabData.LEVELS) {
            medians.put(level, SiegeBalanceScores.median(report.after().scores().values().stream()
                    .mapToDouble(s -> s.levels().get(level).total()).toArray()));
        }
        for (Row row : report.rows()) {
            Score a = report.after().score(row.info().id());
            out.append("| ").append(row.info().name());
            for (int level : SiegeLabData.LEVELS) {
                out.append(" | ").append(signed(rel(a.levels().get(level).total(), medians.get(level))));
            }
            out.append(" | ");
            List<String> wins = new ArrayList<>();
            for (int level : SiegeLabData.LEVELS) {
                wins.add(num(100 * a.levels().get(level).combat()));
            }
            out.append(String.join(" / ", wins)).append(" |\n");
        }
        out.append("\n");
    }

    private static void renderDuels(StringBuilder out, Report report) {
        out.append("## 1v1 결투 (6레벨, 보정 후)\n\n");
        out.append("70×70 승률표에서 35~65% 밖인 대결의 비율(높을수록 상성이 극단적), 가장 강한·약한 상대.\n\n");
        out.append("| 능력 | 극단 상성 | 가장 강한 상대 (승률) | 가장 약한 상대 (승률) |\n|---|---|---|---|\n");
        Result after = report.after();
        double[][] win = after.winrates().get(6);
        List<String> ids = after.ids();
        for (int i = 0; i < ids.size(); i++) {
            int best = -1;
            int worst = -1;
            for (int j = 0; j < ids.size(); j++) {
                if (j == i) {
                    continue;
                }
                if (best < 0 || win[i][j] > win[i][best]) {
                    best = j;
                }
                if (worst < 0 || win[i][j] < win[i][worst]) {
                    worst = j;
                }
            }
            Score s = after.score(ids.get(i));
            out.append("| ").append(name(report, ids.get(i))).append(" | ")
                    .append(num(100 * s.levels().get(6).winrateOutliers())).append("% | ")
                    .append(name(report, ids.get(worst))).append(" (").append(num(100 * win[i][worst])).append("%) | ")
                    .append(name(report, ids.get(best))).append(" (").append(num(100 * win[i][best])).append("%) |\n");
        }
        out.append("\n");
    }

    private static void renderMelee(StringBuilder out, Report report) {
        out.append("## 평타 강화형·수동 결정\n\n");
        out.append("6레벨, 보정 1 기준. 평타 강화 = 지속딜 중 자기 평타 강화 몫(초당), 비율 = 그 몫 / 지속딜. ")
                .append("평타 강화형은 스킬·궁 보정 최대 ").append(num(MELEE_KIT_MAX)).append(".\n\n");
        out.append("| 능력 | 평타 강화 (초당) | 비율 | 근접 흡혈 (30초) | 평타 강화형 | 보정 스킬/궁/건물 | 수동 결정 |\n")
                .append("|---|---|---|---|---|---|---|\n");
        SiegeLabData.Table lab = report.lab();
        int count = 0;
        for (Row row : report.rows()) {
            String id = row.info().id();
            Metrics m = SiegeBalanceScores.metrics(lab.row(id, 6), Correction.NONE, 1.0);
            double share = meleeShare(lab, id);
            Pinned pin = PINNED.get(id);
            if (share < 0.1 && m.lifesteal() < 3 && pin == null) {
                continue;
            }
            count++;
            Correction c = report.corrections().get(id);
            out.append("| ").append(row.info().name()).append(" | ").append(num(m.basicBoost())).append(" | ")
                    .append(num(100 * share)).append("% | ").append(num(m.lifesteal())).append(" | ")
                    .append(meleeKit(lab, id) ? "예" : "-").append(" | ").append(num(c.skill()))
                    .append(" / ").append(num(c.ultimate())).append(" / ").append(num(c.objective())).append(" | ")
                    .append(pin == null ? "-" : pin.reason()).append(" |\n");
        }
        if (count == 0) {
            out.append("| (없음) | | | | | | |\n");
        }
        out.append("\n");
    }

    private static void renderTuning(StringBuilder out, Report report) {
        out.append("## 공성전 전용 수치 (`SiegeTuning`)\n\n");
        out.append("공성전만 `abilities.yml` 기본값 대신 읽는 수치입니다(서버가 그 수치를 직접 고쳤으면 서버 값). ")
                .append("`games.siege.balance.tuning: false` 로 끕니다. 특성(3·6·9레벨)은 이 값에 더해집니다.\n\n");
        List<SiegeTuning.Tune> all = SiegeTuning.all();
        if (all.isEmpty()) {
            out.append("(없음)\n\n");
            return;
        }
        out.append("| 능력 | 수치 | 기본 → 공성전 | 이유 |\n|---|---|---|---|\n");
        for (SiegeTuning.Tune tune : all) {
            out.append("| ").append(name(report, tune.abilityId())).append(" | `").append(tune.key()).append("` | ")
                    .append(num(tune.from())).append(" → ").append(num(tune.value())).append(" | ").append(tune.reason())
                    .append(" |\n");
        }
        out.append("\n");
    }

    /**
     * Abilities left outside the goal on purpose: what they are good at is what the lab cannot measure (escapes,
     * stealth, a revive, shrinking), so their numbers stay and real records ({@link SiegeRealStats}) should decide.
     */
    static final Map<String, String> EXCEPTIONS = exceptions();

    private static Map<String, String> exceptions() {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("blink", "점멸(탈출·진입)과 위상 회피는 랩이 못 잼(공격자가 스스로 안 움직임). 차원 습격(다음 공격 2배)도 평타 한 대 몫이라 작게 잡힘 → 실전 기록으로 판단");
        out.put("chrono", "되감기(위치·체력 복구)는 맞고 난 뒤의 회복이라 랩의 시험타로 안 잡힘. 시간 정지는 통제로 잡힘");
        out.put("phoenix", "환생(경기당 한 번 부활)과 비행 진입은 랩에 안 잡힘. 강림 돌진은 폭딜 상한(보정 최대)");
        out.put("shadow", "은신(타워·적 시야 회피)과 등 뒤 암습은 랩의 정면 결투에 안 잡힘. 난무 피해는 폭딜 상한");
        out.put("tengu", "작게 만들기(적 근접 피해·사거리 감소)는 랩의 대상이 공격하지 않아 안 잡힘. 쿨타임은 줄임");
        out.put("gravity", "블랙홀 빨아들이기·띄우기는 이동 잠금이 아니라 통제로 안 셈. 구속·쿨타임은 늘림");
        out.put("turtle", "등껍질(뒤에서 오는 피해 −50%)·가시 반사는 정면 시험타로 안 잡힘. 궁극기 지속·쿨타임은 손봄");
        out.put("odin", "후긴과 무닌(복수 피해)은 맞아야 켜짐. 쿨타임은 줄임");
        out.put("magicalgirl", "지원 대역 밖: 하트 회복이 아군 한 명 기준이라 낮게 잡힘(실제로는 튕기며 여러 명)");
        out.put("chef", "만찬은 아군 전체의 힘 I·회복이라 팀 몫이 큼(랩은 아군 한 명): 식탁 지속·회복은 줄임, 보정 바닥");
        out.put("hunter", "지속딜 대역 경계(1.0): 지속딜의 약 3분의 1이 사냥 개시(궁극기)의 평타 강화라 능력 보정으로 못 낮춤, 점수는 목표 안");
        out.put("duelist", "능력 피해가 없어(결투 표식·일기토는 평타 강화) 보정이 아무것도 안 바꿈, 폭딜 0. 표식은 랩의 평타 강화로 재서 맞춤(20%)");
        return out;
    }

    private static void renderExceptions(StringBuilder out, Report report) {
        out.append("## 목표 밖 능력 (예외)\n\n");
        out.append("보정 후에도 전체 점수 중앙값 ±").append(num(GOAL * 100)).append("% 밖이거나 역할 대역 밖인 능력과 그 이유.\n\n");
        out.append("| 능력 | 점수 | 역할 대역 | 이유 |\n|---|---|---|---|\n");
        int count = 0;
        for (Row row : report.rows()) {
            String id = row.info().id();
            Score a = report.after().score(id);
            double r = rel(a.total(), report.after().median());
            if (Math.abs(r) > GOAL * 100 + 1.0E-9 || !a.bandProblems().isEmpty()) {
                count++;
                out.append("| ").append(row.info().name()).append(" | ").append(signed(r)).append("% | ")
                        .append(a.bandProblems().isEmpty() ? "✓" : String.join(", ", a.bandProblems())).append(" | ")
                        .append(EXCEPTIONS.getOrDefault(id, "(이유 없음: 다음 패치 대상)")).append(" |\n");
            }
        }
        if (count == 0) {
            out.append("| (없음) | | | |\n");
        }
        out.append("\n");
    }

    private static void renderMechanics(StringBuilder out, Report report) {
        out.append("## 주의할 메커니즘 (6레벨 측정)\n\n");
        out.append("넉백 ").append(num(KNOCK_LIMIT)).append("블록 넘게 밀어내기, 30초에 무적 ").append(num(INVULN_LIMIT))
                .append("초 넘게, 30초에 강한 통제 ").append(num(HARD_LIMIT)).append("초 넘게.\n\n");
        out.append("| 능력 | 넉백 | 무적 | 강한 통제 |\n|---|---|---|---|\n");
        int count = 0;
        for (Row row : report.rows()) {
            Metrics m = report.after().score(row.info().id()).levels().get(6).metrics();
            if (m.knock() > KNOCK_LIMIT || m.invuln() > INVULN_LIMIT || m.hard() > HARD_LIMIT) {
                count++;
                out.append("| ").append(row.info().name()).append(" | ").append(num(m.knock())).append(" | ")
                        .append(num(m.invuln())).append(" | ").append(num(m.hard())).append(" |\n");
            }
        }
        if (count == 0) {
            out.append("| (없음) | | | |\n");
        }
        out.append("\n");
    }

    static final double KNOCK_LIMIT = 10;
    static final double INVULN_LIMIT = 3;
    static final double HARD_LIMIT = 8;

    private static void renderReal(StringBuilder out, Report report) {
        out.append("## 실전 기록\n\n");
        if (report.real().isEmpty()) {
            out.append("서버의 `plugins/MCPL/stats/siege/totals.yml`(`/게임 공성전 통계`)을 `").append(SiegeRealStats.FILE)
                    .append("` 로 복사하고 `./gradlew updateSiegeBalance` 를 돌리면, 라운드 ").append(SiegeRealStats.MIN_ROUNDS)
                    .append("판 이상인 능력의 실전 분당 스킬 피해(중앙값 대비)를 랩 지속딜(중앙값 대비)과 비교해 랩 피해를 실전 쪽으로 ")
                    .append("옮깁니다(").append(SiegeRealStats.FULL_ROUNDS).append("판에서 최대 절반). 지금은 기록이 없습니다.\n\n");
            return;
        }
        out.append("`").append(SiegeRealStats.FILE).append("` 의 누적 기록. 보정 계수 = 랩 피해에 곱한 값.\n\n");
        out.append("| 능력 | 라운드 | 분당 스킬 피해 | 분당 건물 피해 | 승률 | 보정 계수 |\n|---|---|---|---|---|---|\n");
        report.real().forEach((id, e) -> out.append("| ").append(name(report, id)).append(" | ").append(e.rounds())
                .append(" | ").append(num(e.skillPerMinute())).append(" | ").append(num(e.structurePerMinute()))
                .append(" | ").append(num(100 * e.winRate())).append("% | ")
                .append(num(report.calibration().getOrDefault(id, 1.0))).append(" |\n"));
        out.append("\n");
    }

    private static void renderModel(StringBuilder out, Report report, double g, double top) {
        out.append("## 모델 폭딜 (상한 검사)\n\n");
        out.append("능력 설명대로 짠 피해 모델(`SiegeBalanceModels`, 전부 맞는다고 가정, 빠져나갈 수 있는 장판은 ")
                .append(num(SiegeBalanceModels.ZONE_SECONDS)).append("초만)의 3초 폭딜, 공성전 수치(전용 수치 포함)·보정 후. ")
                .append("1레벨 ").append(num(BURST_CAP)).append(" / 9레벨 ").append(num(BURST_CAP_9)).append(" 이하.\n\n");
        out.append("| 능력 | 쿨 | 1Lv 액티브 | 1Lv 궁극기 | 9Lv 최대 | 10초 피해 | 비고 |\n|---|---|---|---|---|---|---|\n");
        for (Row row : report.rows()) {
            Correction c = report.corrections().get(row.info().id());
            Profile raw = row.raw();
            out.append("| ").append(row.info().name()).append(" | ").append(num(row.cooldown()))
                    .append(" | ").append(num(g * burst(raw.active(), c.skill(), c.ultimate())))
                    .append(" | ").append(num(g * burst(raw.ult(), c.skill(), c.ultimate())))
                    .append(" | ").append(num(g * top * worstBurst(row.top(), c.skill(), c.ultimate())))
                    .append(" | ").append(num(g * tenSeconds(raw, row.cooldown(), c.skill(), c.ultimate())))
                    .append(" | ").append(raw.note()).append(" |\n");
        }
        out.append("\n");
    }

    private static void renderPerks(StringBuilder out, List<Row> rows) {
        out.append("## 3·6·9레벨 특성\n\n");
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
