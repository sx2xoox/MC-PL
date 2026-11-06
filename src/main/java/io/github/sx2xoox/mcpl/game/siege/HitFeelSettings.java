package io.github.sx2xoox.mcpl.game.siege;

import java.util.List;

import io.github.sx2xoox.mcpl.game.siege.creature.ModelCreatureLook;
import org.bukkit.configuration.ConfigurationSection;

/**
 * The {@code games.siege.hit-feel.*} settings (타격감): how a landed hit on a creature, a structure or an enemy fighter
 * looks and sounds ({@link HitFeel}, {@link ModelCreatureLook.Reaction}). Read once per match.
 *
 * @param enabled        the whole hit feel (off = the plain flinch and sound of before)
 * @param reaction       how a creature's model takes a hit (hitstop, white / red flash, flinch size)
 * @param sparks         how many sparks fly where a blow lands, against the usual (0 = none)
 * @param soundVolume    the layered hit sound's volume (0 = silent)
 * @param soundGapTicks  the least ticks between two hit sounds on the same target (each target on its own)
 * @param confirmVolume  the attacker's own "딱" when a hit lands (0 = none)
 * @param confirmGapTicks the least ticks between two confirmations of one attacker (a sweep over many is one)
 * @param killPop        the little burst where a creature falls to its last hit
 * @param goldPop        the gold won by a last hit or a camp pops up there, for the winner only
 * @param strongFraction an ability hit that takes this share of the target's maximum health reads as a strong hit
 */
public record HitFeelSettings(
        boolean enabled,
        ModelCreatureLook.Reaction reaction,
        double sparks,
        float soundVolume,
        int soundGapTicks,
        float confirmVolume,
        int confirmGapTicks,
        boolean killPop,
        boolean goldPop,
        double strongFraction) {

    public static final String ENABLED = "hit-feel.enabled";
    public static final String HITSTOP = "hit-feel.creature.hitstop-ticks";
    public static final String STRONG_HITSTOP = "hit-feel.creature.strong-hitstop-ticks";
    public static final String FLASH_WHITE = "hit-feel.creature.flash-white-ticks";
    public static final String FLASH_RED = "hit-feel.creature.flash-red-ticks";
    public static final String INTENSITY = "hit-feel.creature.intensity";
    public static final String SPARKS = "hit-feel.sparks";
    public static final String SOUND_VOLUME = "hit-feel.sound-volume";
    public static final String SOUND_GAP = "hit-feel.sound-gap-ticks";
    public static final String CONFIRM_VOLUME = "hit-feel.confirm-volume";
    public static final String CONFIRM_GAP = "hit-feel.confirm-gap-ticks";
    public static final String KILL_POP = "hit-feel.kill-pop";
    public static final String GOLD_POP = "hit-feel.gold-pop";
    public static final String STRONG_FRACTION = "hit-feel.strong-fraction";

    public static final HitFeelSettings DEFAULTS = new HitFeelSettings(true, ModelCreatureLook.Reaction.DEFAULT, 1.0,
            1f, 2, 0.5f, 2, true, true, 0.3);

    /** Off: the models only squash and flash a little, nothing else is added. */
    public static final HitFeelSettings OFF = new HitFeelSettings(false, new ModelCreatureLook.Reaction(0, 0, 3, 0,
            0.6f), 0, 0, 2, 0, 2, false, false, 1);

    public static HitFeelSettings read(ConfigurationSection c) {
        if (!c.getBoolean(ENABLED, DEFAULTS.enabled)) {
            return OFF;
        }
        ModelCreatureLook.Reaction r = DEFAULTS.reaction;
        ModelCreatureLook.Reaction reaction = new ModelCreatureLook.Reaction(
                ticks(c, HITSTOP, r.hitstop(), 6), ticks(c, STRONG_HITSTOP, r.strongHitstop(), 8),
                ticks(c, FLASH_WHITE, r.whiteTicks(), 10), ticks(c, FLASH_RED, r.redTicks(), 10),
                (float) number(c, INTENSITY, r.intensity(), 0, 3));
        return new HitFeelSettings(true, reaction,
                number(c, SPARKS, DEFAULTS.sparks, 0, 4),
                (float) number(c, SOUND_VOLUME, DEFAULTS.soundVolume, 0, 3),
                ticks(c, SOUND_GAP, DEFAULTS.soundGapTicks, 40),
                (float) number(c, CONFIRM_VOLUME, DEFAULTS.confirmVolume, 0, 2),
                ticks(c, CONFIRM_GAP, DEFAULTS.confirmGapTicks, 40),
                c.getBoolean(KILL_POP, DEFAULTS.killPop),
                c.getBoolean(GOLD_POP, DEFAULTS.goldPop),
                number(c, STRONG_FRACTION, DEFAULTS.strongFraction, 0.01, 1));
    }

    private static int ticks(ConfigurationSection c, String key, int fallback, int max) {
        return Math.max(0, Math.min(max, c.getInt(key, fallback)));
    }

    private static double number(ConfigurationSection c, String key, double fallback, double min, double max) {
        double value = c.getDouble(key, fallback);
        return Double.isFinite(value) ? Math.max(min, Math.min(max, value)) : fallback;
    }

    /** Declares the defaults (with Korean comments) under {@code games.siege.hit-feel}. */
    public static void declareDefaults(ConfigurationSection d) {
        ModelCreatureLook.Reaction r = DEFAULTS.reaction;
        d.setComments("hit-feel", List.of("타격감: 맞힌 순간의 반응·불꽃·소리 (미니언·정글 몬스터·건물·적 플레이어/봇)"));
        d.set(ENABLED, DEFAULTS.enabled);
        d.setComments(ENABLED, List.of("false 면 아래 연출을 모두 끄고 작은 찌그러짐·번쩍임만 남김"));
        d.set(HITSTOP, r.hitstop());
        d.setComments(HITSTOP, List.of("맞은 생물 모델이 충격 자세로 멈추는 틱 (히트스톱, 0 = 없음)"));
        d.set(STRONG_HITSTOP, r.strongHitstop());
        d.setComments(STRONG_HITSTOP, List.of("치명타·콤보 마무리·큰 한 방의 히트스톱 틱"));
        d.set(FLASH_WHITE, r.whiteTicks());
        d.setComments(FLASH_WHITE, List.of("맞는 순간 하얗게 번쩍이는 틱 (최대 밝기 + 흰 테두리)"));
        d.set(FLASH_RED, r.redTicks());
        d.setComments(FLASH_RED, List.of("흰 번쩍 뒤 빨갛게 빛나는 틱"));
        d.set(INTENSITY, (double) r.intensity());
        d.setComments(INTENSITY, List.of("휘청·찌그러짐 크기 배율 (1 = 기본; 곰인형·드래곤은 작게, 쥐·젤리는 크게 자동 보정)"));
        d.set(SPARKS, DEFAULTS.sparks);
        d.setComments(SPARKS, List.of("맞은 지점의 재질별 타격 불꽃 양 배율 (0 = 끔; 플라스틱 반짝, 솜 뭉치, 젤리 방울, 금속 불꽃, 나무 조각)"));
        d.set(SOUND_VOLUME, (double) DEFAULTS.soundVolume);
        d.setComments(SOUND_VOLUME, List.of("겹쳐 나는 피격음(묵직한 저음 + 재질음 + 짧은 하이라이트) 음량 배율 (0 = 끔)"));
        d.set(SOUND_GAP, DEFAULTS.soundGapTicks);
        d.setComments(SOUND_GAP, List.of("같은 대상의 피격음 사이 최소 틱 (대상마다 따로; 다른 대상의 소리는 막지 않음)"));
        d.set(CONFIRM_VOLUME, (double) DEFAULTS.confirmVolume);
        d.setComments(CONFIRM_VOLUME, List.of("맞혔을 때 때린 사람에게만 들리는 '딱' 확인음 음량 (0 = 끔; 치명타·마무리·처치는 강조음)"));
        d.set(CONFIRM_GAP, DEFAULTS.confirmGapTicks);
        d.setComments(CONFIRM_GAP, List.of("한 사람의 확인음 사이 최소 틱 (여럿을 한 번에 맞혀도 한 번)"));
        d.set(KILL_POP, DEFAULTS.killPop);
        d.setComments(KILL_POP, List.of("막타(처치) 때 쓰러진 자리에 작은 '팡' 이펙트"));
        d.set(GOLD_POP, DEFAULTS.goldPop);
        d.setComments(GOLD_POP, List.of("막타·캠프 처치로 받은 골드를 그 자리에 '+N 골드'로 띄움 (받은 사람에게만)"));
        d.set(STRONG_FRACTION, DEFAULTS.strongFraction);
        d.setComments(STRONG_FRACTION, List.of("능력 한 방이 최대 체력의 이 비율 이상이면 강한 타격으로 연출"));
    }
}
