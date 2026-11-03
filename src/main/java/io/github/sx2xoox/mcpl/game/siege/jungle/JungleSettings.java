package io.github.sx2xoox.mcpl.game.siege.jungle;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

import io.github.sx2xoox.mcpl.core.map.lanes.LaneSites.ObjectiveKind;
import org.bukkit.configuration.ConfigurationSection;

/**
 * The jungle objectives of 공성전 v2 ({@code games.siege.jungle.*}; docs/siege-v2.md §4). Times in ticks, distances
 * in blocks, health and damage in health points. Gold amounts are {@code Gold.Settings}.
 *
 * @param enabled              objectives appear at all
 * @param healthPerExtraPlayer extra monster health per player of the bigger team beyond the first (0.25 = +25 %)
 * @param shareRadius          the killing team's players this close share the experience (and a buff monster's buff)
 * @param resetTicks           a fighting camp nobody hit for this long goes home with full health
 * @param kinds                each kind's numbers
 * @param teddyAttack          곰인형 buff: melee damage (health points)
 * @param teddySkill           곰인형 buff: ability damage (+0.10 = +10 %)
 * @param rabbitCooldown       시계 토끼 buff: cooldown reduction (0.20 = 20 % shorter)
 * @param fountainHeal         사탕 샘물: health healed at once
 * @param fountainRegenTicks   사탕 샘물: regeneration afterwards
 * @param telescopeRevealTicks 망원경 탑: how long the enemies on that half glow
 * @param dragonSkill          dragon buff: ability damage (+0.10 = +10 %)
 * @param dragonAttack         dragon buff: melee damage (+0.10 = +10 %)
 */
public record JungleSettings(
        boolean enabled,
        double healthPerExtraPlayer,
        double shareRadius,
        int resetTicks,
        Map<ObjectiveKind, Kind> kinds,
        double teddyAttack,
        double teddySkill,
        double rabbitCooldown,
        double fountainHeal,
        int fountainRegenTicks,
        int telescopeRevealTicks,
        double dragonSkill,
        double dragonAttack) {

    public static final String PATH = "jungle.";

    /**
     * One kind's numbers (capture points use the timers, rewards and {@code captureTicks}; monsters the rest).
     *
     * @param count        monsters in the camp
     * @param health       health of each
     * @param damage       damage of each hit
     * @param scale        size
     * @param speed        pathfinder speed
     * @param range        hit range (0 = never hits: the rabbit flees)
     * @param attackTicks  between hits
     * @param firstTicks   from the start of the fight to the first appearance
     * @param respawnTicks from a clear (capture) to the next
     * @param leash        monsters pulled farther from home go home with full health
     * @param xp           experience (split between the sharers; the dragon: each member)
     * @param score        team points
     * @param buffTicks    how long the buff lasts (0 = none)
     * @param captureTicks capture time of a capture point
     */
    public record Kind(int count, double health, double damage, double scale, double speed, double range,
            int attackTicks, int firstTicks, int respawnTicks, double leash, int xp, int score, int buffTicks,
            int captureTicks) {
    }

    private static final Map<ObjectiveKind, Kind> DEFAULTS = Map.of(
            ObjectiveKind.JELLY, new Kind(3, 18, 2, 1.3, 1.0, 1.8, 25, 600, 1200, 12, 30, 1, 0, 0),
            ObjectiveKind.MICE, new Kind(4, 10, 1.5, 1.0, 1.4, 1.6, 15, 600, 1200, 12, 30, 1, 0, 0),
            ObjectiveKind.TEDDY, new Kind(1, 90, 5, 1.4, 0.9, 2.6, 30, 1200, 3000, 14, 80, 3, 1800, 0),
            ObjectiveKind.RABBIT, new Kind(1, 70, 0, 2.0, 1.3, 0, 0, 1200, 3000, 14, 80, 3, 1800, 0),
            ObjectiveKind.FOUNTAIN, new Kind(0, 0, 0, 1, 1, 0, 0, 1200, 1800, 0, 40, 2, 0, 160),
            ObjectiveKind.TELESCOPE, new Kind(0, 0, 0, 1, 1, 0, 0, 1200, 2400, 0, 40, 2, 0, 160),
            ObjectiveKind.DRAGON, new Kind(1, 400, 8, 1.25, 0.8, 4, 40, 6000, 6000, 20, 300, 10, 2400, 0));

    public static JungleSettings read(ConfigurationSection config) {
        Map<ObjectiveKind, Kind> kinds = new EnumMap<>(ObjectiveKind.class);
        for (ObjectiveKind kind : ObjectiveKind.values()) {
            Kind d = DEFAULTS.get(kind);
            String p = path(kind, "");
            kinds.put(kind, new Kind(
                    Math.max(0, config.getInt(p + "count", d.count())),
                    config.getDouble(p + "health", d.health()),
                    config.getDouble(p + "damage", d.damage()),
                    config.getDouble(p + "scale", d.scale()),
                    config.getDouble(p + "speed", d.speed()),
                    config.getDouble(p + "range", d.range()),
                    ticks(config.getDouble(p + "attack-seconds", d.attackTicks() / 20.0)),
                    ticks(config.getDouble(p + "first-seconds", d.firstTicks() / 20.0)),
                    Math.max(20, ticks(config.getDouble(p + "respawn-seconds", d.respawnTicks() / 20.0))),
                    config.getDouble(p + "leash", d.leash()),
                    config.getInt(p + "xp", d.xp()),
                    config.getInt(p + "score", d.score()),
                    ticks(config.getDouble(p + "buff-seconds", d.buffTicks() / 20.0)),
                    Math.max(1, ticks(config.getDouble(p + "capture-seconds", Math.max(1, d.captureTicks()) / 20.0)))));
        }
        return new JungleSettings(
                config.getBoolean(PATH + "enabled", true),
                config.getDouble(PATH + "health-per-extra-player", 0.25),
                config.getDouble(PATH + "share-radius", 12),
                ticks(config.getDouble(PATH + "reset-seconds", 8)),
                kinds,
                config.getDouble(PATH + "teddy.buff-attack", 2),
                config.getDouble(PATH + "teddy.buff-skill", 0.10),
                config.getDouble(PATH + "rabbit.buff-cooldown", 0.20),
                config.getDouble(PATH + "fountain.heal", 8),
                ticks(config.getDouble(PATH + "fountain.regen-seconds", 10)),
                ticks(config.getDouble(PATH + "telescope.reveal-seconds", 60)),
                config.getDouble(PATH + "dragon.buff-skill", 0.10),
                config.getDouble(PATH + "dragon.buff-attack", 0.10));
    }

    /** The config path of a kind's key ({@code jungle.<kind>.<key>}). */
    public static String path(ObjectiveKind kind, String key) {
        return PATH + kind.name().toLowerCase(Locale.ROOT) + "." + key;
    }

    public Kind kind(ObjectiveKind kind) {
        return kinds.get(kind);
    }

    static int ticks(double seconds) {
        return (int) Math.round(seconds * 20);
    }
}
