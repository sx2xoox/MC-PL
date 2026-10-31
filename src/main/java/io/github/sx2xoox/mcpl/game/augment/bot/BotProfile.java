package io.github.sx2xoox.mcpl.game.augment.bot;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.configuration.ConfigurationSection;

/**
 * How well one {@link BotDifficulty} carries out its decisions ({@code games.augment.bots.<difficulty>.*}).
 *
 * @param reactionTicks     time between decisions and before reacting to something new (ticks)
 * @param aimError          melee/bow aim error in degrees (0 = perfect)
 * @param critChance        chance a melee hit is a jump crit (x1.5)
 * @param strafeChance      chance to strafe sideways around the target each decision
 * @param bowSkill          0..1: how well bow shots lead a moving target (1 = perfect lead)
 * @param bowRange          farthest bow shot in blocks (0 = never uses the bow)
 * @param goldenApples      golden apples it gets when the fight starts
 * @param healAt            health (2 = one heart) at or below which it eats an apple / retreats to heal
 * @param gearPace          speed of the gear schedule (1 = a human's pace, 1.4 = done 40 % sooner)
 * @param gearRead          0..1: how well it reads its target's gear (1 = exactly, 0 = assumes average gear)
 * @param buildPerSecond    blocks placed per second while building the base or fortifying
 * @param baseSize          side length of the base walls (odd, 5..9)
 * @param aggression        0..1: bias towards taking fights (1 = takes every fight it can win)
 * @param shieldChance      chance to block a frontal melee hit with its shield (from the second gear step)
 * @param attackTicks       ticks between two melee swings (a fully charged sword is 12)
 * @param damageScale       multiplier of all damage it deals (1 = exactly its gear)
 * @param dodgeChance       0..1: chance to zig-zag out of the line of fire when it sees a bow drawn at it
 * @param spacing           0..1: chance to back off out of reach while its sword recharges and step in charged
 *                          (hit and back), instead of trading blows face to face
 */
public record BotProfile(int reactionTicks, double aimError, double critChance, double strafeChance, double bowSkill,
                         double bowRange, int goldenApples, double healAt, double gearPace, double gearRead,
                         double buildPerSecond, int baseSize, double aggression, double shieldChance, int attackTicks,
                         double damageScale, double dodgeChance, double spacing) {

    /** The presets, also the defaults in {@code config.yml}. */
    public static BotProfile preset(BotDifficulty difficulty) {
        return switch (difficulty) {
            case EASY -> new BotProfile(9, 6.0, 0.45, 0.45, 0.55, 24, 2, 8, 0.9, 0.7, 2.0, 5, 0.5, 0.0, 13, 1.0, 0.15, 0.0);
            case NORMAL -> new BotProfile(5, 3.0, 0.7, 0.75, 0.8, 32, 3, 10, 1.05, 0.85, 3.5, 7, 0.6, 0.25, 12, 1.0, 0.45, 0.35);
            case HARD -> new BotProfile(2, 1.0, 0.9, 0.9, 0.95, 40, 5, 12, 1.3, 0.95, 6.0, 7, 0.72, 0.45, 12, 1.05, 0.8, 0.7);
            case HELL -> new BotProfile(1, 0.3, 1.0, 1.0, 1.0, 48, 7, 13, 1.5, 1.0, 9.0, 9, 0.85, 0.6, 11, 1.15, 0.95, 0.9);
        };
    }

    static final String REACTION = "reaction-ms";
    static final String AIM = "aim-error-degrees";
    static final String CRIT = "crit-chance";
    static final String STRAFE = "strafe-chance";
    static final String BOW_SKILL = "bow-skill";
    static final String BOW_RANGE = "bow-range";
    static final String APPLES = "golden-apples";
    static final String HEAL_AT = "heal-at-health";
    static final String GEAR_PACE = "gear-pace";
    static final String GEAR_READ = "gear-read";
    static final String BUILD = "build-blocks-per-second";
    static final String BASE_SIZE = "base-size";
    static final String AGGRESSION = "aggression";
    static final String SHIELD = "shield-chance";
    static final String ATTACK = "attack-interval-ms";
    static final String DAMAGE = "damage-scale";
    static final String DODGE = "dodge-chance";
    static final String SPACING = "spacing";

    /** The values as config entries (key → value), in declaration order. */
    Map<String, Object> toConfig() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(REACTION, reactionTicks * 50);
        out.put(AIM, aimError);
        out.put(CRIT, critChance);
        out.put(STRAFE, strafeChance);
        out.put(BOW_SKILL, bowSkill);
        out.put(BOW_RANGE, bowRange);
        out.put(APPLES, goldenApples);
        out.put(HEAL_AT, healAt);
        out.put(GEAR_PACE, gearPace);
        out.put(GEAR_READ, gearRead);
        out.put(BUILD, buildPerSecond);
        out.put(BASE_SIZE, baseSize);
        out.put(AGGRESSION, aggression);
        out.put(SHIELD, shieldChance);
        out.put(ATTACK, attackTicks * 50);
        out.put(DAMAGE, damageScale);
        out.put(DODGE, dodgeChance);
        out.put(SPACING, spacing);
        return out;
    }

    static final List<String> COMMENTS = List.of(
            "reaction-ms: 판단·반응 간격(ms) · aim-error-degrees: 조준 오차(도) · crit-chance: 점프 치명타 확률",
            "strafe-chance: 좌우로 돌며 싸울 확률 · bow-skill: 움직이는 적을 앞질러 쏘는 정확도(0~1) · bow-range: 활 사거리",
            "golden-apples: 전투 시작 때 받는 황금 사과 · heal-at-health: 이 체력 이하면 사과를 먹거나 물러나 회복",
            "gear-pace: 장비가 좋아지는 속도(1 = 사람 속도) · gear-read: 상대 장비를 읽는 정확도(0~1)",
            "build-blocks-per-second: 기지·방어벽을 짓는 속도 · base-size: 기지 한 변(5~9, 홀수)",
            "aggression: 싸움을 거는 성향(0~1) · shield-chance: 정면 근접 공격을 방패로 막을 확률",
            "attack-interval-ms: 근접 공격 간격 · damage-scale: 주는 피해 배율",
            "dodge-chance: 활을 겨누는 적을 보면 지그재그로 사선을 피할 확률 · spacing: 칼이 다시 찰 때까지 사거리 밖으로 빠졌다가 들어가 치는 확률");

    /** Reads a difficulty's section; missing or invalid values fall back to the preset. */
    static BotProfile read(ConfigurationSection s, BotDifficulty difficulty) {
        BotProfile d = preset(difficulty);
        int base = (int) Math.round(clamp(s.getDouble(BASE_SIZE, d.baseSize), 5, 9, d.baseSize));
        if (base % 2 == 0) {
            base++;
        }
        return new BotProfile(
                (int) Math.max(1, Math.round(clamp(s.getDouble(REACTION, d.reactionTicks * 50), 0, 5000,
                        d.reactionTicks * 50) / 50.0)),
                clamp(s.getDouble(AIM, d.aimError), 0, 45, d.aimError),
                clamp(s.getDouble(CRIT, d.critChance), 0, 1, d.critChance),
                clamp(s.getDouble(STRAFE, d.strafeChance), 0, 1, d.strafeChance),
                clamp(s.getDouble(BOW_SKILL, d.bowSkill), 0, 1, d.bowSkill),
                clamp(s.getDouble(BOW_RANGE, d.bowRange), 0, 64, d.bowRange),
                (int) Math.round(clamp(s.getDouble(APPLES, d.goldenApples), 0, 64, d.goldenApples)),
                clamp(s.getDouble(HEAL_AT, d.healAt), 0, 40, d.healAt),
                clamp(s.getDouble(GEAR_PACE, d.gearPace), 0.1, 5, d.gearPace),
                clamp(s.getDouble(GEAR_READ, d.gearRead), 0, 1, d.gearRead),
                clamp(s.getDouble(BUILD, d.buildPerSecond), 0.2, 20, d.buildPerSecond),
                base,
                clamp(s.getDouble(AGGRESSION, d.aggression), 0, 1, d.aggression),
                clamp(s.getDouble(SHIELD, d.shieldChance), 0, 1, d.shieldChance),
                (int) Math.max(4, Math.round(clamp(s.getDouble(ATTACK, d.attackTicks * 50), 200, 5000,
                        d.attackTicks * 50) / 50.0)),
                clamp(s.getDouble(DAMAGE, d.damageScale), 0.1, 5, d.damageScale),
                clamp(s.getDouble(DODGE, d.dodgeChance), 0, 1, d.dodgeChance),
                clamp(s.getDouble(SPACING, d.spacing), 0, 1, d.spacing));
    }

    private static double clamp(double value, double min, double max, double fallback) {
        return Double.isFinite(value) ? Math.max(min, Math.min(max, value)) : fallback;
    }
}
