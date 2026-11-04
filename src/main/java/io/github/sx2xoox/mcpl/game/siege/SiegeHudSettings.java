package io.github.sx2xoox.mcpl.game.siege;

import java.util.List;

import org.bukkit.configuration.ConfigurationSection;

/**
 * The {@code games.siege.hud.*} settings (docs/siege.md "화면 표시"): the plates under the names, the floating damage
 * numbers, the "▶ 지금 할 일" line and the start tutorial. Read once per match.
 *
 * @param namePlates        name, health bar and level above every fighter's head
 * @param damageNumbers     floating damage numbers over hit players, structures and monsters
 * @param damageNumberRange only players this close (blocks) see a damage number
 * @param guidance          the "▶ 지금 할 일" action bar line
 * @param tutorial          three titles and a chat summary at the start of the first round
 */
public record SiegeHudSettings(
        boolean namePlates,
        boolean damageNumbers,
        double damageNumberRange,
        boolean guidance,
        boolean tutorial) {

    public static final String NAME_PLATES = "hud.name-plates";
    public static final String DAMAGE_NUMBERS = "hud.damage-numbers";
    public static final String DAMAGE_NUMBER_RANGE = "hud.damage-number-range";
    public static final String GUIDANCE = "hud.guidance";
    public static final String TUTORIAL = "hud.tutorial";

    /** Defaults (also used before a match read its section). */
    public static final SiegeHudSettings DEFAULTS = new SiegeHudSettings(true, true, 24, true, true);

    public static SiegeHudSettings read(ConfigurationSection c) {
        double range = c.getDouble(DAMAGE_NUMBER_RANGE, DEFAULTS.damageNumberRange);
        return new SiegeHudSettings(
                c.getBoolean(NAME_PLATES, DEFAULTS.namePlates),
                c.getBoolean(DAMAGE_NUMBERS, DEFAULTS.damageNumbers),
                Double.isFinite(range) ? Math.max(1, Math.min(128, range)) : DEFAULTS.damageNumberRange,
                c.getBoolean(GUIDANCE, DEFAULTS.guidance),
                c.getBoolean(TUTORIAL, DEFAULTS.tutorial));
    }

    /** Declares the defaults (with Korean comments) under {@code games.siege.hud}. */
    public static void declareDefaults(ConfigurationSection d) {
        d.setComments("hud", List.of("화면 표시 (체력은 머리 위 막대, 보스바는 남은 시간 1줄)"));
        d.set(NAME_PLATES, DEFAULTS.namePlates);
        d.setComments(NAME_PLATES, List.of("머리 위 이름·체력 막대·레벨 (바닐라 이름표 대신; 자신과 은신한 적에게는 보이지 않음)"));
        d.set(DAMAGE_NUMBERS, DEFAULTS.damageNumbers);
        d.setComments(DAMAGE_NUMBERS, List.of("떠오르는 피해 숫자 (흰색 일반, 노랑 능력, 초록 회복, 주황 건물)"));
        d.set(DAMAGE_NUMBER_RANGE, DEFAULTS.damageNumberRange);
        d.setComments(DAMAGE_NUMBER_RANGE, List.of("피해 숫자가 보이는 거리(블록)"));
        d.set(GUIDANCE, DEFAULTS.guidance);
        d.setComments(GUIDANCE, List.of("액션바에 '▶ 지금 할 일' 한 줄 안내"));
        d.set(TUTORIAL, DEFAULTS.tutorial);
        d.setComments(TUTORIAL, List.of("첫 라운드 준비 시간에 규칙 안내 (타이틀 3장 + 채팅 요약)"));
    }
}
