package io.github.sx2xoox.mcpl.game.siege;

import java.util.List;

import org.bukkit.configuration.ConfigurationSection;

/**
 * The {@code games.siege.hud.*} settings (docs/siege.md "화면 표시"): when a tower's boss bar shows, the health plates
 * under the names and the way-finding aids (pointer, beam, outline, district titles). Read once per match.
 *
 * @param towerBarRange     a tower's boss bar shows to players within this many blocks of it
 * @param towerBarLookRange ... or looking at its hitbox from at most this far
 * @param towerBarHitTicks  ... or who hit it during this time
 * @param namePlates        health bar and level under every player's name
 * @param pointer           the target tower's name, distance and direction in the timer boss bar
 * @param beam              a light beam above the target tower, for everyone
 * @param outline           a glowing frame around the target tower, for the attackers
 * @param districtTitles    a title when a player enters another district of the map
 * @param night             the map world stays at night in clear weather for the whole match (the 네온 시티 is lit
 *                          for the night); only a plugin map world, set back to noon when the match ends
 */
public record SiegeHudSettings(
        double towerBarRange,
        double towerBarLookRange,
        int towerBarHitTicks,
        boolean namePlates,
        boolean pointer,
        boolean beam,
        boolean outline,
        boolean districtTitles,
        boolean night) {

    public static final String TOWER_BAR_RANGE = "hud.tower-bar.range";
    public static final String TOWER_BAR_LOOK_RANGE = "hud.tower-bar.look-range";
    public static final String TOWER_BAR_HIT_SECONDS = "hud.tower-bar.hit-seconds";
    public static final String NAME_PLATES = "hud.name-plates";
    public static final String POINTER = "hud.pointer";
    public static final String BEAM = "hud.beam";
    public static final String OUTLINE = "hud.outline";
    public static final String DISTRICT_TITLES = "hud.district-titles";
    public static final String NIGHT = "night";
    /** Time of day of the match at night (midnight). */
    public static final long NIGHT_TIME = 18000;

    /** Defaults (also used before a match read its section). */
    public static final SiegeHudSettings DEFAULTS = new SiegeHudSettings(28, 60, 100, true, true, true, true, true, true);

    public static SiegeHudSettings read(ConfigurationSection c) {
        return new SiegeHudSettings(
                range(c.getDouble(TOWER_BAR_RANGE, DEFAULTS.towerBarRange)),
                range(c.getDouble(TOWER_BAR_LOOK_RANGE, DEFAULTS.towerBarLookRange)),
                (int) Math.round(range(c.getDouble(TOWER_BAR_HIT_SECONDS, DEFAULTS.towerBarHitTicks / 20.0)) * 20),
                c.getBoolean(NAME_PLATES, DEFAULTS.namePlates),
                c.getBoolean(POINTER, DEFAULTS.pointer),
                c.getBoolean(BEAM, DEFAULTS.beam),
                c.getBoolean(OUTLINE, DEFAULTS.outline),
                c.getBoolean(DISTRICT_TITLES, DEFAULTS.districtTitles),
                c.getBoolean(NIGHT, DEFAULTS.night));
    }

    /** Declares the defaults (with Korean comments) under {@code games.siege.hud}. */
    public static void declareDefaults(ConfigurationSection d) {
        d.set(NIGHT, DEFAULTS.night);
        d.setComments(NIGHT, List.of("경기 내내 맵을 밤(맑음, 시간 멈춤)으로. 네온 시티는 밤에 맞춰 밝혀져 있습니다 (끝나면 다시 낮)"));
        d.setComments("hud", List.of("화면 표시"));
        d.set(TOWER_BAR_RANGE, DEFAULTS.towerBarRange);
        d.setComments(TOWER_BAR_RANGE, List.of(
                "타워 체력 보스바는 그 타워에서 이 거리(블록) 안에 있거나, look-range 안에서 타워를 바라보거나,",
                "hit-seconds 초 안에 그 타워를 때린 플레이어에게만 보입니다"));
        d.set(TOWER_BAR_LOOK_RANGE, DEFAULTS.towerBarLookRange);
        d.set(TOWER_BAR_HIT_SECONDS, DEFAULTS.towerBarHitTicks / 20);
        d.set(NAME_PLATES, DEFAULTS.namePlates);
        d.setComments(NAME_PLATES, List.of("이름 아래 체력 막대와 레벨 (자신과 은신한 적에게는 보이지 않음)"));
        d.set(POINTER, DEFAULTS.pointer);
        d.setComments(POINTER, List.of("위 보스바에 목표 타워 이름·거리·방향 (▶ 방화벽 게이트 42m ↗)"));
        d.set(BEAM, DEFAULTS.beam);
        d.setComments(BEAM, List.of("목표 타워 위의 빛기둥 (멀리서도, 건물 너머로도 보임)"));
        d.set(OUTLINE, DEFAULTS.outline);
        d.setComments(OUTLINE, List.of("공격팀에게 목표 타워 둘레의 빛나는 테두리"));
        d.set(DISTRICT_TITLES, DEFAULTS.districtTitles);
        d.setComments(DISTRICT_TITLES, List.of("맵의 다른 구역에 들어가면 구역 이름 표시"));
    }

    private static double range(double value) {
        return Double.isFinite(value) ? Math.max(0, Math.min(512, value)) : 0;
    }
}
