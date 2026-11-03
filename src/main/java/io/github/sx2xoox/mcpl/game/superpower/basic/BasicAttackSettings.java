package io.github.sx2xoox.mcpl.game.superpower.basic;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.github.sx2xoox.mcpl.game.superpower.basic.BasicAttack.Effect;
import io.github.sx2xoox.mcpl.game.superpower.basic.BasicAttack.Shape;
import org.bukkit.configuration.ConfigurationSection;
import org.jspecify.annotations.Nullable;

/**
 * The basic attack (평타) table a hosting mode hands its abilities match
 * ({@code MatchHost#basicAttacks()}): whether it is on, the global numbers and every ability's {@link BasicAttack}
 * ({@link BasicAttacks} defaults with this section's overrides). Read from a config section such as
 * {@code games.siege.basic-attacks}:
 *
 * <pre>
 * enabled: true
 * damage-scale: 1.0          # every basic attack's damage
 * bonus-reference: 5.0       # attack damage bonus (items, buffs) adds bonus/reference of each hit's damage
 * attacks:
 *   chef: {shape: combo, damage: 2.6, cooldown: 0.7, range: 3, hits: 2, interval: 4, angle: 70}
 * </pre>
 *
 * Abilities without an entry use {@link BasicAttacks#FALLBACK}; an {@code attacks.<id>} entry for such an ability
 * changes that fallback for it alone.
 *
 * @param enabled        false: the ability weapon swings like a plain sword
 * @param damageScale    factor on every basic attack's damage
 * @param bonusReference the hit damage at which a +1 attack damage bonus adds exactly +1 (a hit of {@code d} gains
 *                       {@code bonus × d / reference}); 0 ignores attack damage bonuses
 * @param attacks        every configured attack by ability id
 */
public record BasicAttackSettings(boolean enabled, double damageScale, double bonusReference,
                                  Map<String, BasicAttack> attacks) {

    public static final String ENABLED = "enabled";
    public static final String DAMAGE_SCALE = "damage-scale";
    public static final String BONUS_REFERENCE = "bonus-reference";
    public static final String ATTACKS = "attacks";
    public static final double DEFAULT_BONUS_REFERENCE = 5.0;

    /** Per-attack keys (seconds for {@code cooldown} and {@code effect-seconds}, ticks for {@code interval}). */
    public static final String SHAPE = "shape";
    public static final String DAMAGE = "damage";
    public static final String COOLDOWN = "cooldown";
    public static final String RANGE = "range";
    public static final String HITS = "hits";
    public static final String INTERVAL = "interval";
    public static final String RADIUS = "radius";
    public static final String ANGLE = "angle";
    public static final String SPEED = "speed";
    public static final String PIERCE = "pierce";
    public static final String FALLOFF = "falloff";
    public static final String EFFECT = "effect";
    public static final String EFFECT_POWER = "effect-power";
    public static final String EFFECT_SECONDS = "effect-seconds";

    public BasicAttackSettings {
        attacks = Collections.unmodifiableMap(new LinkedHashMap<>(attacks));
    }

    /** Every default, on. */
    public static BasicAttackSettings defaults() {
        return new BasicAttackSettings(true, 1.0, DEFAULT_BONUS_REFERENCE, BasicAttacks.defaults());
    }

    /** The table switched off (the ability weapon stays a plain sword). */
    public static BasicAttackSettings off() {
        return new BasicAttackSettings(false, 1.0, DEFAULT_BONUS_REFERENCE, BasicAttacks.defaults());
    }

    /** The basic attack of {@code abilityId}: its own, or the fallback. */
    public BasicAttack attack(String abilityId) {
        BasicAttack attack = attacks.get(abilityId);
        return attack != null ? attack : BasicAttacks.FALLBACK.forId(abilityId);
    }

    /** Reads {@code section} (null: every default); unknown or invalid values fall back to the defaults. */
    public static BasicAttackSettings read(@Nullable ConfigurationSection section) {
        if (section == null) {
            return defaults();
        }
        Map<String, BasicAttack> attacks = new LinkedHashMap<>(BasicAttacks.defaults());
        ConfigurationSection overrides = section.getConfigurationSection(ATTACKS);
        if (overrides != null) {
            for (String id : overrides.getKeys(false)) {
                ConfigurationSection entry = overrides.getConfigurationSection(id);
                if (entry == null) {
                    continue;
                }
                String key = id.toLowerCase(Locale.ROOT);
                BasicAttack base = attacks.getOrDefault(key, BasicAttacks.FALLBACK.forId(key));
                attacks.put(key, override(base, entry));
            }
        }
        return new BasicAttackSettings(
                section.getBoolean(ENABLED, true),
                clamp(section.getDouble(DAMAGE_SCALE, 1.0), 0, 10, 1.0),
                clamp(section.getDouble(BONUS_REFERENCE, DEFAULT_BONUS_REFERENCE), 0, 100,
                        DEFAULT_BONUS_REFERENCE),
                attacks);
    }

    private static BasicAttack override(BasicAttack base, ConfigurationSection c) {
        Shape shape = parse(Shape.class, c.getString(SHAPE), base.shape());
        Effect effect = parse(Effect.class, c.getString(EFFECT), base.effect());
        return base.with(shape,
                clamp(c.getDouble(DAMAGE, base.damage()), 0, 100, base.damage()),
                (int) Math.round(clamp(c.getDouble(COOLDOWN, base.cooldownSeconds()), 0.05, 30,
                        base.cooldownSeconds()) * 20),
                clamp(c.getDouble(RANGE, base.range()), 0.5, 64, base.range()),
                (int) clamp(c.getInt(HITS, base.hits()), 1, 10, base.hits()),
                (int) clamp(c.getInt(INTERVAL, base.interval()), 0, 40, base.interval()),
                clamp(c.getDouble(RADIUS, base.radius()), 0, 10, base.radius()),
                clamp(c.getDouble(ANGLE, base.angle()), 0, 360, base.angle()),
                clamp(c.getDouble(SPEED, base.speed()), 0.1, 5, base.speed()),
                (int) clamp(c.getInt(PIERCE, base.pierce()), 0, 20, base.pierce()),
                clamp(c.getDouble(FALLOFF, base.falloff()), 0, 2, base.falloff()),
                effect,
                clamp(c.getDouble(EFFECT_POWER, base.effectPower()), 0, 20, base.effectPower()),
                clamp(c.getDouble(EFFECT_SECONDS, base.effectSeconds()), 0, 10, base.effectSeconds()));
    }

    /**
     * Declares the defaults (with Korean comments) under {@code prefix} of a game's config defaults, e.g.
     * {@code basic-attacks} in {@code games.siege}. Each attack lists the numbers its shape and effect use.
     */
    public static void declareDefaults(ConfigurationSection d, String prefix) {
        String p = prefix + ".";
        d.setComments(prefix, List.of(
                "직업별 기본 공격 (평타, 메이플스토리식): 능력 무기 좌클릭이 그냥 칼질이 아니라 능력마다 고유한 공격이 됩니다.",
                "목록과 설명: docs/siege-v2.md §8"));
        d.set(p + ENABLED, true);
        d.setComments(p + ENABLED, List.of("false 면 능력 무기가 평범한 검처럼 휘둘러집니다"));
        d.set(p + DAMAGE_SCALE, 1.0);
        d.setComments(p + DAMAGE_SCALE, List.of("모든 평타 피해 배율 (방어구 적용 전, 레벨 보정은 따로 곱함)"));
        d.set(p + BONUS_REFERENCE, DEFAULT_BONUS_REFERENCE);
        d.setComments(p + BONUS_REFERENCE, List.of(
                "공격력 보너스(상점 검, 버프)가 평타에 붙는 비율: 한 타 피해가 이 값일 때 보너스 +1 = 피해 +1",
                "(약한 타는 그만큼 적게, 강한 타는 많이). 0 이면 공격력 보너스 무시"));
        d.setComments(p + ATTACKS, List.of(
                "능력별 평타 수치. shape: swing(휘두르기) combo(연타) thrust(찌르기) shot(사격) projectile(투사체)",
                "lob(투척) chain(연쇄) cone(부채꼴) nova(주변 파동) strike(지점 타격)",
                "damage = 한 타 피해, cooldown = 다음 평타까지(초), range = 사거리(블록), hits = 타수/발수,",
                "interval = 타 사이(틱, 지점 타격은 떨어지기까지), radius = 폭/폭발 반경, angle = 각도(도),",
                "speed = 투사체 속도(블록/틱), pierce = 한 번에 맞히는 적 수(0 = 전부), falloff = 연쇄마다 피해 배율,",
                "effect: none slow(감속) dot(지속 피해) knockback pull lifesteal heal-allies lunge(돌진) weaken(약화)",
                "effect-power = 효과 세기, effect-seconds = 효과 시간(초)"));
        for (BasicAttack attack : BasicAttacks.defaults().values()) {
            String a = p + ATTACKS + "." + attack.id() + ".";
            d.set(a + SHAPE, attack.shape().key());
            d.set(a + DAMAGE, attack.damage());
            d.set(a + COOLDOWN, attack.cooldownSeconds());
            d.set(a + RANGE, attack.range());
            Shape shape = attack.shape();
            if (attack.hits() > 1 || shape == Shape.COMBO || shape == Shape.CHAIN) {
                d.set(a + HITS, attack.hits());
            }
            if (attack.interval() > 0 || shape == Shape.STRIKE) {
                d.set(a + INTERVAL, attack.interval());
            }
            if (shape == Shape.THRUST || shape == Shape.PROJECTILE || shape == Shape.LOB || shape == Shape.CHAIN
                    || shape == Shape.STRIKE) {
                d.set(a + RADIUS, attack.radius());
            }
            if (attack.angle() > 0) {
                d.set(a + ANGLE, attack.angle());
            }
            if (shape == Shape.PROJECTILE || shape == Shape.LOB) {
                d.set(a + SPEED, attack.speed());
            }
            if (attack.pierce() > 0) {
                d.set(a + PIERCE, attack.pierce());
            }
            if (shape == Shape.CHAIN) {
                d.set(a + FALLOFF, attack.falloff());
            }
            if (attack.effect() != Effect.NONE) {
                d.set(a + EFFECT, attack.effect().key());
                d.set(a + EFFECT_POWER, attack.effectPower());
                if (attack.effect().timed()) {
                    d.set(a + EFFECT_SECONDS, attack.effectSeconds());
                }
            }
            d.setComments(p + ATTACKS + "." + attack.id(), List.of(attack.name() + ": " + attack.description()));
        }
    }

    private static <E extends Enum<E>> E parse(Class<E> type, @Nullable String value, E fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private static double clamp(double value, double min, double max, double fallback) {
        return Double.isFinite(value) ? Math.max(min, Math.min(max, value)) : fallback;
    }
}
