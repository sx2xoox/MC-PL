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
 * @param skills               each monster kind's skills by name ({@code jungle.<kind>.<skill>.*})
 * @param dragonRageHealth     the dragon is enraged below this fraction of its health
 * @param dragonRageDamage     an enraged dragon's damage multiplier
 * @param dragonRageHaste      an enraged dragon's cooldown multiplier (0.6 = 40 % shorter)
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
        double dragonAttack,
        Map<ObjectiveKind, Map<String, Skill>> skills,
        double dragonRageHealth,
        double dragonRageDamage,
        double dragonRageHaste) {

    public static final String PATH = "jungle.";

    /**
     * One kind's numbers (capture points use the timers, rewards and {@code captureTicks}; monsters the rest).
     *
     * @param count        monsters in the camp
     * @param health       health of each
     * @param damage       damage of each basic attack
     * @param scale        the toy's size (1 = as modelled; the body and the model both)
     * @param speed        walking speed (× the bodies' walk)
     * @param range        basic attack range (the rabbit: how far it throws)
     * @param attackTicks  between basic attacks
     * @param firstTicks   from the start of the fight to the first appearance
     * @param respawnTicks from a clear (capture) to the next
     * @param leash        monsters pulled farther from home go home with full health
     * @param xp           experience (split between the sharers; the dragon: each member)
     * @param score        team points
     * @param buffTicks    how long the buff lasts (0 = none)
     * @param captureTicks capture time of a capture point
     * @param knockback    blocks a monster slides when a player's or bot's blow pushes it (its own small knockback;
     *                     0 = it stands its ground)
     */
    public record Kind(int count, double health, double damage, double scale, double speed, double range,
            int attackTicks, int firstTicks, int respawnTicks, double leash, int xp, int score, int buffTicks,
            int captureTicks, double knockback) {
    }

    /**
     * One monster skill ({@code jungle.<kind>.<skill>.*}; docs/siege-v2.md §4). What each number means depends on the
     * skill (see the defaults): an area's damage and radius, a cone's angle, the warning before it lands, the time
     * until it can be used again, how long its effect lasts, and one more number of its own.
     *
     * @param damage        damage to each fighter it hits
     * @param radius        radius / reach, blocks
     * @param angle         a cone's full angle, degrees
     * @param windupTicks   warning (marked on the ground, the monster charging) before it lands
     * @param cooldownTicks before it can be used again
     * @param effectTicks   how long its effect lasts (a puddle, a slow)
     * @param strength      its own number: a push in blocks, a slow's level, a speed, a health fraction
     */
    public record Skill(double damage, double radius, double angle, int windupTicks, int cooldownTicks,
            int effectTicks, double strength) {
    }

    /** Skill names. */
    public static final String GOO = "goo";
    public static final String SCATTER = "scatter";
    public static final String SLAM = "slam";
    public static final String SHOCKWAVE = "shockwave";
    public static final String THROW = "throw";
    public static final String BLINK = "blink";
    public static final String BREATH = "breath";
    public static final String TAIL = "tail";
    public static final String GUST = "gust";

    private static final Map<ObjectiveKind, Map<String, Skill>> SKILLS = Map.of(
            // a puddle where the target stood: radius, warning, cooldown, how long it stays; strength = slow level
            ObjectiveKind.JELLY, Map.of(GOO, new Skill(0, 1.8, 0, 20, 180, 100, 1)),
            // run off as far as radius for effect ticks at strength x the speed, then rush back: damage = that bite
            ObjectiveKind.MICE, Map.of(SCATTER, new Skill(2.5, 4.5, 0, 10, 220, 20, 1.7)),
            // the basic paw slam (its damage and interval are the kind's): a circle of radius ahead; strength = push
            ObjectiveKind.TEDDY, Map.of(SLAM, new Skill(0, 1.8, 0, 14, 0, 0, 0.3),
                    SHOCKWAVE, new Skill(6, 4.5, 0, 24, 200, 0, 0.8)),
            // the basic throw (the kind's damage, range, interval): keeps radius away; strength = the hand's speed,
            // effect = the slow on a hit; the blink: radius away, below strength of its health
            ObjectiveKind.RABBIT, Map.of(THROW, new Skill(0, 3.5, 0, 0, 0, 30, 0.8),
                    BLINK, new Skill(0, 7, 0, 12, 160, 0, 0.5)),
            ObjectiveKind.DRAGON, Map.of(BREATH, new Skill(10, 8, 70, 24, 160, 0, 0),
                    TAIL, new Skill(7, 5.5, 0, 20, 200, 0, 0.6),
                    GUST, new Skill(3, 8, 0, 16, 260, 0, 1.4)),
            ObjectiveKind.FOUNTAIN, Map.of(),
            ObjectiveKind.TELESCOPE, Map.of());

    private static final Map<ObjectiveKind, Kind> DEFAULTS = Map.of(
            ObjectiveKind.JELLY, new Kind(3, 18, 2, 1, 1.0, 1.8, 25, 600, 1200, 12, 30, 1, 0, 0, 0.5),
            ObjectiveKind.MICE, new Kind(4, 10, 1.5, 1, 1.4, 1.6, 15, 600, 1200, 12, 30, 1, 0, 0, 0.6),
            ObjectiveKind.TEDDY, new Kind(1, 90, 5, 1, 0.75, 2.6, 30, 1200, 3000, 14, 80, 3, 1800, 0, 0.15),
            ObjectiveKind.RABBIT, new Kind(1, 70, 3, 1, 1.3, 8, 30, 1200, 3000, 14, 80, 3, 1800, 0, 0.35),
            ObjectiveKind.FOUNTAIN, new Kind(0, 0, 0, 1, 1, 0, 0, 1200, 1800, 0, 40, 2, 0, 160, 0),
            ObjectiveKind.TELESCOPE, new Kind(0, 0, 0, 1, 1, 0, 0, 1200, 2400, 0, 40, 2, 0, 160, 0),
            ObjectiveKind.DRAGON, new Kind(1, 400, 8, 1, 0.8, 4, 40, 6000, 6000, 20, 300, 10, 2400, 0, 0));

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
                    Math.max(1, ticks(config.getDouble(p + "capture-seconds", Math.max(1, d.captureTicks()) / 20.0))),
                    Math.max(0, config.getDouble(p + "knockback", d.knockback()))));
        }
        Map<ObjectiveKind, Map<String, Skill>> skills = new EnumMap<>(ObjectiveKind.class);
        for (ObjectiveKind kind : ObjectiveKind.values()) {
            Map<String, Skill> own = new java.util.TreeMap<>();
            for (Map.Entry<String, Skill> entry : SKILLS.get(kind).entrySet()) {
                Skill d = entry.getValue();
                String p = path(kind, entry.getKey() + ".");
                own.put(entry.getKey(), new Skill(
                        config.getDouble(p + "damage", d.damage()),
                        Math.max(0, config.getDouble(p + "radius", d.radius())),
                        Math.clamp(config.getDouble(p + "angle", d.angle()), 0, 360),
                        Math.max(0, ticks(config.getDouble(p + "windup-seconds", d.windupTicks() / 20.0))),
                        Math.max(0, ticks(config.getDouble(p + "cooldown-seconds", d.cooldownTicks() / 20.0))),
                        Math.max(0, ticks(config.getDouble(p + "effect-seconds", d.effectTicks() / 20.0))),
                        config.getDouble(p + "strength", d.strength())));
            }
            skills.put(kind, Map.copyOf(own));
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
                config.getDouble(PATH + "dragon.buff-attack", 0.10),
                skills,
                Math.clamp(config.getDouble(PATH + "dragon.rage-health", 0.4), 0, 1),
                Math.max(0, config.getDouble(PATH + "dragon.rage-damage", 1.25)),
                Math.clamp(config.getDouble(PATH + "dragon.rage-cooldown", 0.6), 0.1, 1));
    }

    /** The config path of a kind's key ({@code jungle.<kind>.<key>}). */
    public static String path(ObjectiveKind kind, String key) {
        return PATH + kind.name().toLowerCase(Locale.ROOT) + "." + key;
    }

    public Kind kind(ObjectiveKind kind) {
        return kinds.get(kind);
    }

    /** A kind's skill ({@link #GOO}, {@link #SLAM}, ...); throws for a skill the kind does not have. */
    public Skill skill(ObjectiveKind kind, String name) {
        Skill skill = skills.getOrDefault(kind, Map.of()).get(name);
        if (skill == null) {
            throw new IllegalArgumentException(kind + " has no skill " + name);
        }
        return skill;
    }

    static int ticks(double seconds) {
        return (int) Math.round(seconds * 20);
    }
}
