package io.github.sx2xoox.mcpl.game.siege.balance;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import org.jspecify.annotations.Nullable;

/**
 * The 공성전 level perks (docs/siege-v2.md §5): at levels 3, 6 and 9 every pool ability gets one small extra, written as
 * data: a declared param of the ability and a delta added to it ("화염술사 6레벨: 불바다 범위 +1"). The hosted ability
 * match reads every param through {@link #apply} with the owner's level, so no ability class needs to know about
 * levels; a perk that a param change cannot express would read {@code AbilityContext#level} in the ability itself.
 *
 * <p>No "억까": a perk only nudges an existing number, so it never adds an instant kill or a permanent effect, and the
 * caps below hold in code ({@link #capped}, also for edited tables) and in the tests ({@link #violations}):
 * <ul>
 *     <li>{@link Kind#CONTROL} (stun, root, freeze, silence, jail): at most +{@value #MAX_CONTROL_SECONDS} s per perk;</li>
 *     <li>{@link Kind#DAMAGE}: at most +25% of the configured value; {@link Kind#AREA} / {@link Kind#RANGE}: +30%;</li>
 *     <li>{@link Kind#DURATION}: at most +2 s and +50%; {@link Kind#COOLDOWN}: only shorter, at most −20%;</li>
 *     <li>{@link Kind#COUNT}: +1 (+2 for counts of 6 or more); {@link Kind#PERCENT}: +15 points; {@link Kind#HEAL}:
 *     +50%.</li>
 * </ul>
 */
public final class SiegePerks {

    /** The levels that give a perk. */
    public static final List<Integer> LEVELS = List.of(3, 6, 9);
    /** Longest a perk may extend crowd control, in seconds. */
    public static final double MAX_CONTROL_SECONDS = 0.5;

    /**
     * Param keys that hold crowd control (stuns, roots, freezes, silences, prisons, slows): a perk on one of them must
     * be {@link Kind#CONTROL}.
     */
    public static final Pattern CONTROL_KEY = Pattern.compile(
            ".*(stun|root|freeze|silence|jail_duration|slow|hold|howl_duration|discord_duration|blind|fatigue"
                    + "|lag|nausea|stagger).*");
    /** Ultimates whose whole duration is crowd control (time stop, rock prison, frost prison, the duel ring). */
    public static final Set<String> CONTROL_DURATIONS = Set.of("chrono:ult_duration", "earth:ult_duration",
            "frost:ult_duration", "duelist:ult_duration", "gravity:ult_duration", "police:jail_duration");
    /** Param keys no perk may touch: thresholds that decide kills, revives, once-per-match effects. */
    public static final Pattern FORBIDDEN_KEY = Pattern.compile(".*(execute|threshold|revive|pact).*");

    /** What a perk changes; decides its unit and its cap. */
    public enum Kind {
        DAMAGE(""), AREA("블록"), RANGE("블록"), DURATION("초"), CONTROL("초"), COOLDOWN("초"), COUNT(""),
        PERCENT("%"), HEAL("");

        private final String unit;

        Kind(String unit) {
            this.unit = unit;
        }

        public String unit() {
            return unit;
        }
    }

    /**
     * One perk.
     *
     * @param abilityId the ability
     * @param level     3, 6 or 9
     * @param key       the declared param it changes
     * @param delta     added to the param from this level on
     * @param kind      what it changes (unit and cap)
     * @param label     Korean name of the number, e.g. "불바다 범위"
     */
    public record Perk(String abilityId, int level, String key, double delta, Kind kind, String label) {
        public Perk {
            Objects.requireNonNull(abilityId, "abilityId");
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(label, "label");
        }

        /** "불바다 범위 +1블록". */
        public String text() {
            return label + " " + (delta >= 0 ? "+" : "−") + AbilityInfo.formatNumber(Math.abs(delta)) + kind.unit();
        }
    }

    private static final Map<String, List<Perk>> TABLE = new LinkedHashMap<>();

    static {
        // id, L3, L6, L9 — every pool ability (SiegeSettings.DEFAULT_POOL).
        add("police", p(3, "taser_range", 2, Kind.RANGE, "테이저 사거리"),
                p(6, "siren_radius", 4, Kind.AREA, "사이렌 범위"),
                p(9, AbilityInfo.COOLDOWN, -1, Kind.COOLDOWN, "테이저 쿨타임"));
        add("miner", p(3, "rock_radius", 0.5, Kind.AREA, "낙석 범위"),
                p(6, "rock_count", 2, Kind.COUNT, "낙석 바위 수"),
                p(9, "crash_damage", 1, Kind.DAMAGE, "수레 전복 피해"));
        add("blacksmith", p(3, "anvil_radius", 0.4, Kind.AREA, "모루 범위"),
                p(6, "temper_duration", 2, Kind.DURATION, "대장간 무기 강화 지속"),
                p(9, "anvil_damage", 1, Kind.DAMAGE, "모루 피해"));
        add("gunslinger", p(3, "shot_range", 3, Kind.RANGE, "속사 사거리"),
                p(6, "rounds", 1, Kind.COUNT, "탄창"),
                p(9, "noon_max", 2, Kind.DAMAGE, "정오의 결투 최대 피해"));
        add("chef", p(3, "pan_range", 0.5, Kind.RANGE, "프라이팬 범위"),
                p(6, "ult_heal", 2, Kind.HEAL, "만찬 즉시 회복"),
                p(9, "pan_damage", 1, Kind.DAMAGE, "프라이팬 피해"));
        add("ninja", p(3, "shuriken_range", 3, Kind.RANGE, "수리검 사거리"),
                p(6, "smoke_duration", 1.5, Kind.DURATION, "연막 지속"),
                p(9, "shuriken_count", 1, Kind.COUNT, "수리검 수"));
        add("blade", p(3, "dash_distance", 1.5, Kind.RANGE, "질풍참 거리"),
                p(6, "wave_chance", 10, Kind.PERCENT, "검기 확률"),
                p(9, "dash_damage", 1, Kind.DAMAGE, "질풍참 피해"));
        add("duelist", p(3, "challenge_range", 4, Kind.RANGE, "결투 신청 사거리"),
                p(6, "challenge_bonus", 10, Kind.PERCENT, "결투 표식 추가 피해"),
                p(9, "ult_victory_heal", 3, Kind.HEAL, "결투장 승리 회복"));
        add("archer", p(3, "rain_radius", 1, Kind.AREA, "화살비 범위"),
                p(6, "volley_arrows", 1, Kind.COUNT, "산탄 화살 수"),
                p(9, AbilityInfo.COOLDOWN, -1, Kind.COOLDOWN, "산탄 쿨타임"));
        add("sniper", p(3, "aim_time", -0.2, Kind.COOLDOWN, "정조준 시간"),
                p(6, "mark_duration", 1, Kind.DURATION, "표적 지정 지속"),
                p(9, "shot_range", 10, Kind.RANGE, "정조준 사격 사거리"));
        add("hunter", p(3, "trap_max", 1, Kind.COUNT, "곰덫 최대 수"),
                p(6, "hunt_heal", 3, Kind.HEAL, "사냥 성공 회복"),
                p(9, "trap_damage", 1, Kind.DAMAGE, "곰덫 피해"));
        add("lancer", p(3, "throw_range", 3, Kind.RANGE, "창 투척 사거리"),
                p(6, "sprint_bonus", 10, Kind.PERCENT, "돌격 자세 추가 피해"),
                p(9, "throw_damage", 1, Kind.DAMAGE, "창 투척 피해"));
        add("berserker", p(3, "charge_distance", 2, Kind.RANGE, "돌진 거리"),
                p(6, "ult_duration", 2, Kind.DURATION, "광폭화 지속"),
                p(9, "ult_lifesteal", 10, Kind.PERCENT, "광폭화 회복"));
        add("guardian", p(3, "bash_radius", 0.5, Kind.AREA, "방패 돌격 범위"),
                p(6, "ult_absorption", 4, Kind.HEAL, "불굴 흡수 체력"),
                p(9, "damage_reduction", 5, Kind.PERCENT, "철벽 피해 감소"));
        add("fighter", p(3, "grab_range", 1, Kind.RANGE, "잡기 거리"),
                p(6, "counter_bonus", 0.75, Kind.DAMAGE, "반격 추가 피해"),
                p(9, "suplex_stun", 0.25, Kind.CONTROL, "메치기 기절"));
        add("pyro", p(3, "fireball_radius", 0.4, Kind.AREA, "화염구 범위"),
                p(6, "ult_radius", 1, Kind.AREA, "불바다 범위"),
                p(9, "shots", 1, Kind.COUNT, "화염구 수"));
        add("frost", p(3, "spear_range", 5, Kind.RANGE, "얼음창 사거리"),
                p(6, "freeze_duration", 0.5, Kind.CONTROL, "얼음창 빙결"),
                p(9, "spear_damage", 1, Kind.DAMAGE, "얼음창 피해"));
        add("thunder", p(3, "radius", 0.5, Kind.AREA, "낙뢰 범위"),
                p(6, "shock_chance", 10, Kind.PERCENT, "정전기 확률"),
                p(9, "ult_strikes", 1, Kind.COUNT, "천벌 낙뢰 수"));
        add("aero", p(3, "distance", 2, Kind.RANGE, "돌풍 도약 거리"),
                p(6, "radius", 0.75, Kind.AREA, "돌풍 범위"),
                p(9, "slam_damage", 1, Kind.DAMAGE, "토네이도 내리꽂기 피해"));
        add("earth", p(3, "quake_range", 2, Kind.RANGE, "지진 거리"),
                p(6, "quake_angle", 15, Kind.AREA, "지진 부채꼴 각도(°)"),
                p(9, "quake_damage", 1, Kind.DAMAGE, "지진 피해"));
        add("hydro", p(3, "beam_range", 3, Kind.RANGE, "물대포 사거리"),
                p(6, "wet_heal", 0.5, Kind.HEAL, "젖은 몸 회복"),
                p(9, "wave_damage", 1, Kind.DAMAGE, "해일 피해"));
        add("magma", p(3, "bomb_radius", 0.5, Kind.AREA, "용암탄 범위"),
                p(6, "pool_duration", 1.5, Kind.DURATION, "용암 웅덩이 지속"),
                p(9, "erupt_damage", 1, Kind.DAMAGE, "화산 분화 피해"));
        add("light", p(3, "flash_range", 2, Kind.RANGE, "섬광 거리"),
                p(6, "ult_heal", 2, Kind.HEAL, "심판의 빛 회복"),
                p(9, "ult_radius", 1, Kind.AREA, "심판의 빛 범위"));
        add("shadow", p(3, "stealth_duration", 1.5, Kind.DURATION, "그림자 은신 지속"),
                p(6, "backstab_bonus", 10, Kind.PERCENT, "암습 추가 피해"),
                p(9, "ult_radius", 3, Kind.RANGE, "그림자 난무 범위"));
        add("vampire", p(3, "drain_range", 3, Kind.RANGE, "생명 흡수 사거리"),
                p(6, "lifesteal", 5, Kind.PERCENT, "흡혈"),
                p(9, "drain_duration", 0.5, Kind.DURATION, "생명 흡수 지속"));
        add("reaper", p(3, "spin_radius", 0.5, Kind.AREA, "낫 회전 범위"),
                p(6, "reap_heal", 3, Kind.HEAL, "영혼 수확 회복"),
                p(9, "spin_damage", 1, Kind.DAMAGE, "낫 회전 피해"));
        add("necromancer", p(3, "skull_range", 4, Kind.RANGE, "망령탄 사거리"),
                p(6, "soul_heal", 1, Kind.HEAL, "영혼 수확 회복"),
                p(9, "legion_duration", 2, Kind.DURATION, "언데드 군단 지속"));
        add("demon", p(3, "hellfire_range", 1.5, Kind.RANGE, "지옥불 거리"),
                p(6, "gate_duration", 1, Kind.DURATION, "지옥문 지속"),
                p(9, "hellfire_damage", 1, Kind.DAMAGE, "지옥불 피해"));
        add("phoenix", p(3, "glide_duration", 1, Kind.DURATION, "화염 날개 활공"),
                p(6, "dash_distance", 3, Kind.RANGE, "불사조 강림 거리"),
                p(9, "dash_damage", 1.5, Kind.DAMAGE, "불사조 강림 피해"));
        add("dragon", p(3, "breath_range", 1.5, Kind.RANGE, "브레스 거리"),
                p(6, "flight_duration", 1.5, Kind.DURATION, "용의 비상 지속"),
                p(9, "breath_damage", 0.15, Kind.DAMAGE, "브레스 피해"));
        add("werewolf", p(3, "leap_distance", 1.5, Kind.RANGE, "할퀴기 도약 거리"),
                p(6, "ult_duration", 2, Kind.DURATION, "야수화 지속"),
                p(9, "claw_damage", 1, Kind.DAMAGE, "할퀴기 피해"));
        add("dokkaebi", p(3, "wisp_range", 2, Kind.RANGE, "도깨비불 사거리"),
                p(6, "wisp_count", 1, Kind.COUNT, "도깨비불 수"),
                p(9, "club_damage", 1, Kind.DAMAGE, "뚝딱 방망이 피해"));
        add("healer", p(3, "light_radius", 2, Kind.AREA, "치유의 빛 범위"),
                p(6, "light_heal", 1.5, Kind.HEAL, "치유의 빛 회복"),
                p(9, "sanctum_duration", 2, Kind.DURATION, "성역 지속"));
        add("bard", p(3, "aura_speed", 5, Kind.PERCENT, "여행자의 노래 이동 속도"),
                p(6, "discord_radius", 2, Kind.AREA, "불협화음 범위"),
                p(9, "chorus_heal", 0.25, Kind.HEAL, "대합창 회복"));
        add("alchemist", p(3, "throw_range", 4, Kind.RANGE, "실험 물약 사거리"),
                p(6, "boon_duration", 2, Kind.DURATION, "이로운 물약 지속"),
                p(9, "splash_damage", 0.75, Kind.DAMAGE, "실험 물약 피해"));
        add("engineer", p(3, "turret_range", 3, Kind.RANGE, "포탑 사거리"),
                p(6, "turret_duration", 2, Kind.DURATION, "포탑 유지"),
                p(9, AbilityInfo.COOLDOWN, -2, Kind.COOLDOWN, "포탑 설치 쿨타임"));
        add("hacker", p(3, "glitch_range", 3, Kind.RANGE, "글리치 사거리"),
                p(6, "glitch_hops", 1, Kind.COUNT, "글리치 튕김 횟수"),
                p(9, "glitch_damage", 1, Kind.DAMAGE, "글리치 피해"));
        add("mech", p(3, "missile_range", 5, Kind.RANGE, "미사일 사거리"),
                p(6, "missile_count", 1, Kind.COUNT, "미사일 수"),
                p(9, "ult_duration", 2, Kind.DURATION, "메카 변신 지속"));
        add("bomber", p(3, "grenade_radius", 0.75, Kind.AREA, "수류탄 범위"),
                p(6, "carpet_bombs", 2, Kind.COUNT, "융단폭격 폭탄 수"),
                p(9, "grenade_damage", 1.5, Kind.DAMAGE, "수류탄 최대 피해"));
        add("gravity", p(3, "pull_range", 4, Kind.RANGE, "끌어당기기 사거리"),
                p(6, "blast_radius", 1, Kind.AREA, "블랙홀 붕괴 범위"),
                p(9, "blast_damage", 2, Kind.DAMAGE, "블랙홀 붕괴 피해"));
        add("magnet", p(3, "cone_range", 2, Kind.RANGE, "극성 거리"),
                p(6, "storm_radius", 2, Kind.AREA, "자기 폭풍 범위"),
                p(9, "polarity_damage", 0.75, Kind.DAMAGE, "극성 피해"));
        add("chrono", p(3, "rewind_seconds", 1, Kind.DURATION, "되감기 시간"),
                p(6, "cooldown_reduction", 5, Kind.PERCENT, "가속된 시간 쿨타임 감소"),
                p(9, AbilityInfo.COOLDOWN, -2, Kind.COOLDOWN, "되감기 쿨타임"));
        add("blink", p(3, "distance", 2, Kind.RANGE, "점멸 거리"),
                p(6, "dodge_chance", 5, Kind.PERCENT, "위상 회피 확률"),
                p(9, "empower_duration", 2, Kind.DURATION, "차원 습격 강화 지속"));
        add("speedster", p(3, "dash_distance", 1, Kind.RANGE, "질주 거리"),
                p(6, "dash_charges", 1, Kind.COUNT, "연속 질주 횟수"),
                p(9, "ult_duration", 1.5, Kind.DURATION, "광속 지속"));
        add("venom", p(3, "cloud_radius", 0.75, Kind.AREA, "독안개 범위"),
                p(6, "cloud_duration", 1.5, Kind.DURATION, "독안개 지속"),
                p(9, "ult_damage", 0.25, Kind.DAMAGE, "역병 피해"));
        add("spider", p(3, "web_range", 4, Kind.RANGE, "거미줄 사거리"),
                p(6, "nest_radius", 1.5, Kind.AREA, "거미 둥지 범위"),
                p(9, "root_duration", 0.25, Kind.CONTROL, "거미줄 속박"));
        add("pirate", p(3, "ball_range", 4, Kind.RANGE, "대포알 사거리"),
                p(6, "ult_duration", 1, Kind.DURATION, "유령선 포격 지속"),
                p(9, "ball_damage", 1, Kind.DAMAGE, "대포알 피해"));
        add("angler", p(3, "cast_range", 3, Kind.RANGE, "찌 사거리"),
                p(6, "ult_radius", 1, Kind.AREA, "대어 범위"),
                p(9, "reel_damage", 0.75, Kind.DAMAGE, "낚아채기 피해"));
        add("farmer", p(3, "sweep_radius", 1, Kind.AREA, "낫질 범위"),
                p(6, "harvest_steal", 5, Kind.PERCENT, "풍작 회복"),
                p(9, "pumpkin_damage", 0.75, Kind.DAMAGE, "호박 피해"));
        add("batter", p(3, "fastball_range", 5, Kind.RANGE, "강속구 사거리"),
                p(6, "crit_bonus", 10, Kind.PERCENT, "클린 히트 추가 피해"),
                p(9, "fastball_damage", 1, Kind.DAMAGE, "강속구 피해"));
    }

    private SiegePerks() {
    }

    private record Entry(int level, String key, double delta, Kind kind, String label) {
    }

    private static Entry p(int level, String key, double delta, Kind kind, String label) {
        return new Entry(level, key, delta, kind, label);
    }

    private static void add(String abilityId, Entry... entries) {
        List<Perk> perks = new ArrayList<>();
        for (Entry entry : entries) {
            perks.add(new Perk(abilityId, entry.level(), entry.key(), entry.delta(), entry.kind(), entry.label()));
        }
        TABLE.put(abilityId, Collections.unmodifiableList(perks));
    }

    /** The abilities that have perks, in table order. */
    public static Set<String> abilities() {
        return Collections.unmodifiableSet(TABLE.keySet());
    }

    /** The perks of an ability (empty if it has none), lowest level first. */
    public static List<Perk> perks(String abilityId) {
        return TABLE.getOrDefault(abilityId, List.of());
    }

    /** The perk an ability gets at exactly {@code level}, or null. */
    public static @Nullable Perk perk(String abilityId, int level) {
        for (Perk perk : perks(abilityId)) {
            if (perk.level() == level) {
                return perk;
            }
        }
        return null;
    }

    /**
     * The value an owner at {@code level} reads for {@code key}: the configured {@code value} plus every perk on that
     * key reached so far, each held to its cap ({@link #capped}).
     */
    public static double apply(String abilityId, int level, String key, double value) {
        double result = value;
        for (Perk perk : perks(abilityId)) {
            if (perk.level() <= level && perk.key().equals(key)) {
                result += capped(perk, value);
            }
        }
        return result;
    }

    /**
     * What a perk may really add to a param whose configured value is {@code base}: its delta held to the caps of its
     * kind, and a crowd-control delta on any {@link #isControlKey control key} held to
     * {@value #MAX_CONTROL_SECONDS} s whatever kind it was given. A perk on a {@link #FORBIDDEN_KEY forbidden key}
     * adds nothing.
     */
    public static double capped(Perk perk, double base) {
        double delta = perk.delta();
        double size = Math.abs(base);
        if (FORBIDDEN_KEY.matcher(perk.key()).matches()) {
            return 0;
        }
        if (isControlKey(perk.abilityId(), perk.key())) {
            return Math.min(delta, MAX_CONTROL_SECONDS);
        }
        return switch (perk.kind()) {
            case CONTROL -> Math.min(delta, MAX_CONTROL_SECONDS);
            case DAMAGE -> Math.min(delta, 0.25 * size);
            case AREA, RANGE -> Math.min(delta, 0.3 * size);
            case DURATION -> Math.min(delta, Math.min(2.0, 0.5 * size));
            case COOLDOWN -> Math.max(Math.min(delta, 0), -0.2 * size);
            case COUNT -> Math.min(delta, size >= 6 ? 2 : 1);
            case PERCENT -> Math.min(delta, 15);
            case HEAL -> Math.min(delta, 0.5 * size);
        };
    }

    /** True if {@code key} of {@code abilityId} holds crowd control ({@link #CONTROL_KEY}, {@link #CONTROL_DURATIONS}). */
    public static boolean isControlKey(String abilityId, String key) {
        return CONTROL_KEY.matcher(key).matches() || CONTROL_DURATIONS.contains(abilityId + ":" + key);
    }

    /**
     * What is wrong with the perks of {@code info} (empty when they are fine): perks on undeclared or forbidden params,
     * crowd control that is not marked {@link Kind#CONTROL}, deltas beyond their cap, levels other than
     * {@link #LEVELS} or not one perk per level.
     */
    public static List<String> violations(AbilityInfo info) {
        List<String> problems = new ArrayList<>();
        List<Perk> perks = perks(info.id());
        List<Integer> levels = perks.stream().map(Perk::level).toList();
        if (!levels.equals(LEVELS)) {
            problems.add(info.id() + ": perk levels " + levels + " are not " + LEVELS);
        }
        for (Perk perk : perks) {
            String where = info.id() + " L" + perk.level() + " " + perk.key();
            Double base = info.params().get(perk.key());
            if (base == null) {
                problems.add(where + ": not a declared param");
                continue;
            }
            if (FORBIDDEN_KEY.matcher(perk.key()).matches()) {
                problems.add(where + ": forbidden param");
            }
            if (isControlKey(info.id(), perk.key()) && perk.kind() != Kind.CONTROL) {
                problems.add(where + ": crowd control must be a CONTROL perk");
            }
            if (perk.kind() == Kind.CONTROL && perk.delta() > MAX_CONTROL_SECONDS) {
                problems.add(where + ": crowd control +" + perk.delta() + " s is over " + MAX_CONTROL_SECONDS + " s");
            }
            if (Math.abs(capped(perk, base) - perk.delta()) > 1.0E-9) {
                problems.add(where + ": delta " + perk.delta() + " is over its cap " + capped(perk, base));
            }
            if (perk.delta() == 0) {
                problems.add(where + ": zero delta");
            }
        }
        return problems;
    }

    /** "6레벨: 불바다 범위 +1블록", or null if the ability gets no perk at {@code level}. */
    public static @Nullable String describe(String abilityId, int level) {
        Perk perk = perk(abilityId, level);
        return perk == null ? null : level + "레벨: " + perk.text();
    }

    /** "화염술사 6레벨: 불바다 범위 +1블록", or null if the ability gets no perk at {@code level}. */
    public static @Nullable String describe(AbilityInfo info, int level) {
        String line = describe(info.id(), level);
        return line == null ? null : info.name() + " " + line;
    }
}
