package io.github.sx2xoox.mcpl.game.siege.balance;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;

/**
 * The 공성전 param tuning (docs/siege-balance.md "공성전 전용 수치"): numbers of an ability that 공성전 reads differently
 * from {@code abilities.yml}, because a mechanic that is fine in a free-for-all 초능력전쟁 is too strong or too weak
 * around lanes, towers and respawns (a long immunity, a push across the map, a very short cooldown). 초능력전쟁 keeps
 * every {@link AbilityInfo} default; the hosted match reads a tuned param in place of the configured one (unless the
 * server edited it in {@code abilities.yml}: then its value wins), and the level perks ({@link SiegePerks}) add to it;
 * {@code games.siege.balance.tuning: false} turns it off.
 *
 * <p>Chosen from the balance lab's measurements ({@code SiegeBalanceLab} → {@code SiegeBalanceReport}); damage is not
 * tuned here but through the per-ability corrections ({@code siege-balance.yml}).
 */
public final class SiegeTuning {

    /**
     * One tuned param.
     *
     * @param abilityId the ability
     * @param key       a declared param of it ({@link AbilityInfo#COOLDOWN} for the cooldown)
     * @param from      its declared default (a server's own value in {@code abilities.yml} is kept as it is)
     * @param value     what 공성전 reads in place of the default
     * @param reason    why, in Korean (shown in docs/siege-balance.md)
     */
    public record Tune(String abilityId, String key, double from, double value, String reason) {
        public Tune {
            Objects.requireNonNull(abilityId, "abilityId");
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(reason, "reason");
        }
    }

    private static final Map<String, Map<String, Tune>> TABLE = new LinkedHashMap<>();

    static {
        // ---- pushes: across lanes a long push drags a fighter under (or out of) a tower
        t("magnet", "push_distance", 6, 4, "척력 밀어내기가 측정 13블록: 타워 밑으로 밀어 넣기·빼내기가 너무 쉬움");
        t("hydro", "beam_push", 0.55, 0.4, "물대포가 연속으로 13블록 넘게 밀어냄: 라인 밖으로 쓸어내기 완화");
        t("pirate", "ball_knockback", 1.2, 0.8, "대포알 넉백 9블록(궁극기 포격 중첩 시 맵 끝까지): 넉백 줄임");
        t("minotaur", "gore_knockback", 5, 3.5, "뿔 받기 넉백 11블록: 타워 쪽으로 던져 넣기 완화");
        // ---- random immunity
        t("magnet", "deflect_chance", 25, 15, "30초에 7초꼴로 공격을 흘려보냄(측정): 타워·미니언 사이 무적 시간 줄임");
        // ---- control
        t("druid", "root_duration", 1.75, 1, "덩굴 속박 + 생명의 숲 속박이 30초에 6초: 피해 보정이 바닥(0.3)이어도 점수 +29%");
        t("druid", "ult_heal", 1.5, 1, "생명의 숲 아군 회복까지 겹쳐 서포터 중 최고(+26%): 회복 줄임");
        t("druid", "ult_duration", 7, 5, "생명의 숲(속박·가시·회복)이 7초 내내 이어져 회복을 줄여도 +20%: 숲 지속 줄임");
        t("shaman", "totem_slow", 25, 15, "장승 둔화가 장승 2개 × 14초 내내: 약한 통제 30초에 7초, 피해 보정 바닥에서도 +12%");
        t("gravity", "pull_slow", 1, 2, "끌어당긴 뒤 구속이 짧아 연계가 안 됨(점수 −36%): 2초");
        t("gravity", AbilityInfo.COOLDOWN, 8, 7, "끌어당기기에 피해가 없어 피해 보정으로 못 올림: 더 자주");
        t("duelist", "challenge_bonus", 30, 20,
                "결투 표식(+30%)이 평타에 붙어 피해 보정이 안 들음: 랩이 평타 강화를 따로 재니 15%면 점수 −13% → 20%");
        // ---- buffs of the kit's own basic attacks (the corrections only touch ability damage, so these are the cause)
        t("berserker", "ult_strength", 2, 0,
                "광폭화 힘 II = 공격력 +6 → 평타 ×2.2(bonus-reference 5), 분노와 곱해 ×3.3(12초 평타 +136, 랩): 힘 대신 아래 +20%");
        t("berserker", "ult_bonus", 0, 20, "광폭화 평타 강화는 근접 피해 +20%로(힘 I도 ×1.6이라 과함)");
        t("berserker", "rage_max", 50, 15,
                "분노가 평타에 곱해져 능력 보정으로 못 줄임: +50%면 분노 몫만 초당 1.6(6레벨 랩, 능력 평균 지속딜 0.9보다 큼) → +15%");
        t("berserker", "ult_lifesteal", 30, 20,
                "광폭화 평타 흡혈이 12초에 75(6레벨 랩): 근접 지속력 과다 → 20%(9레벨 특성 +10)");
        t("blacksmith", "forge_bonus", 2, 1,
                "대장간 근접 공격력 +2 = 평타 +40%(12초 평타 +38, 랩): 보정 바닥(0.3)에서도 +21% → +1");
        t("chef", "ult_duration", 8, 4,
                "만찬 힘 I(평타 ×1.6)이 식탁 내내(12초 평타 +46, 랩): 보정 바닥에서도 +47%, 5초여도 +20% → 식탁 4초");
        t("farmer", "harvest_steal", 10, 3,
                "풍작 근접 흡혈이 30초에 40(6레벨 랩, 대상 1명, 한타에선 최대 3배): 보정 바닥에서도 +32% → 3%(6레벨 특성 +5)");
        t("farmer", "ult_root", 1.5, 1, "대풍년 속박·감속으로 팀 기여 1.8(보정 바닥에서도 +33%): 이삭 속박 1초");
        t("farmer", "ult_slow", 35, 20, "대풍년 밀밭 감속이 반경 7블록 × 6초 내내: 지속딜이 전사 하한이라 피해로 못 낮춤 → 20%");
        t("farmer", "ult_duration", 6, 4, "대풍년(감속·속박·회복·호박) 팀·공성 기여가 보정 바닥에서도 +22%: 밀밭 4초");
        // ---- help that a fighter should not out-heal a support with
        t("chef", "ult_heal", 4, 2.5, "만찬 아군 회복이 서포터급(팀 기여 1.9): 즉시 회복 줄임");
        // ---- tempo of kits the lab finds weak in 공성전 (no pick, no lane pressure)
        t("earth", AbilityInfo.COOLDOWN, 10, 8, "지진이 드물어 라인 압박·통제가 약함(점수 −22%)");
        t("turtle", "ult_duration", 6, 8, "거대 등껍질이 타워 다이브를 버티기에 짧음(탱커인데 점수 −29%)");
        t("turtle", AbilityInfo.COOLDOWN, 8, 6.5, "껍질 회전(돌진·피해 감소)이 드묾: 진입·이탈을 더 자주");
        t("tengu", AbilityInfo.COOLDOWN, 8, 5, "요술 부채 피해가 작고 드묾(점수 −51%, 피해 보정 최대에도 −18%): 자주 쓰게");
        t("speedster", AbilityInfo.COOLDOWN, 5, 4, "질주 충전이 느려 폭딜 상한 안에서 피해를 더 못 올림(−14%): 충전 빠르게");
        t("miner", AbilityInfo.COOLDOWN, 10, 8, "낙석이 느리고 드물어 라인 정리·견제가 약함(점수 −20%)");
        t("hacker", AbilityInfo.COOLDOWN, 10, 8.5, "글리치 침묵이 드묾(서포터 점수 −16%)");
        t("odin", AbilityInfo.COOLDOWN, 10, 8, "미미르의 샘(다음 적중 강화)이 드물어 피해 보정 최대에도 −17%: 더 자주");
    }

    private SiegeTuning() {
    }

    private static void t(String abilityId, String key, double from, double value, String reason) {
        TABLE.computeIfAbsent(abilityId, id -> new LinkedHashMap<>())
                .put(key, new Tune(abilityId, key, from, value, reason));
    }

    /** Every tuned param, in table order. */
    public static List<Tune> all() {
        List<Tune> out = new ArrayList<>();
        TABLE.values().forEach(byKey -> out.addAll(byKey.values()));
        return Collections.unmodifiableList(out);
    }

    /** The tuned params of one ability (empty when none). */
    public static List<Tune> of(String abilityId) {
        Map<String, Tune> byKey = TABLE.get(abilityId);
        return byKey == null ? List.of() : List.copyOf(byKey.values());
    }

    /**
     * What 공성전 reads for {@code key} of {@code abilityId} when the configured value is {@code value}: the tuned value
     * while the server kept the default ({@code value} is the tune's {@link Tune#from}), else {@code value}.
     */
    public static double apply(String abilityId, String key, double value) {
        Map<String, Tune> byKey = TABLE.get(abilityId);
        Tune tune = byKey == null ? null : byKey.get(key);
        return tune != null && Math.abs(tune.from() - value) < 1.0E-9 ? tune.value() : value;
    }

    /** What is wrong with the tuning of {@code info} (empty when fine): undeclared params, non-finite or negative values. */
    public static List<String> violations(AbilityInfo info) {
        List<String> problems = new ArrayList<>();
        for (Tune tune : of(info.id())) {
            Double declared = info.params().get(tune.key());
            if (declared == null) {
                problems.add(info.id() + " " + tune.key() + ": not a declared param");
            } else if (!Double.isFinite(tune.value()) || tune.value() < 0) {
                problems.add(info.id() + " " + tune.key() + ": bad value " + tune.value());
            } else if (Math.abs(declared - tune.from()) > 1.0E-9) {
                problems.add(info.id() + " " + tune.key() + ": from " + tune.from() + " is not the default " + declared);
            } else if (Math.abs(declared - tune.value()) < 1.0E-9) {
                problems.add(info.id() + " " + tune.key() + ": same as the default");
            }
        }
        return problems;
    }
}
