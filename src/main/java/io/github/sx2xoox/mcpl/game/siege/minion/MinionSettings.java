package io.github.sx2xoox.mcpl.game.siege.minion;

import java.util.EnumMap;
import java.util.Map;

import org.bukkit.configuration.ConfigurationSection;

/**
 * The minions of 공성전 v2 ({@code games.siege.minions.*}; docs/siege-v2.md §2). Times in ticks, distances in blocks,
 * health and damage in health points.
 *
 * @param enabled            minions spawn at all
 * @param firstWaveTicks     from the start of the fight to the first wave
 * @param intervalTicks      between waves
 * @param melee              melee minions per wave and lane
 * @param ranged             ranged minions per wave and lane
 * @param playersPerExtra    one extra melee minion per this many players of the bigger team beyond the first
 * @param maxExtra           most extra melee minions
 * @param cannonEvery        a cannon joins every this many waves (0 = never)
 * @param healthPerWave      extra health per wave already sent (0.02 = +2 % a wave)
 * @param aggroRadius        minions fight enemies this close
 * @param leash              minions never chase farther than this from the lane point they last reached
 * @param waypointReach      a waypoint this close (horizontally) counts as reached
 * @param stuckTicks         a minion that has not moved for this long re-paths; twice as long, it is put on its path
 * @param xpRadius           enemies of a dying minion this close get its experience
 * @param empoweredBonus     health and damage bonus of a team's minions while it holds the dragon buff
 * @param stats              each kind's numbers
 * @param formation          how a wave keeps its lines ({@code minions.formation.*})
 * @param shots              the archers' arrows and the cannons' shells ({@code minions.shots.*})
 */
public record MinionSettings(
        boolean enabled,
        int firstWaveTicks,
        int intervalTicks,
        int melee,
        int ranged,
        int playersPerExtra,
        int maxExtra,
        int cannonEvery,
        double healthPerWave,
        double aggroRadius,
        double leash,
        double waypointReach,
        int stuckTicks,
        double xpRadius,
        double empoweredBonus,
        Map<MinionKind, Stats> stats,
        Formation.Settings formation,
        Shots shots) {

    public static final String PATH = "minions.";

    /**
     * The numbers of one minion kind.
     *
     * @param health      health
     * @param damage      damage of a hit (on minions, players and structures)
     * @param range       hit range
     * @param attackTicks between hits
     * @param speed       walking speed (× the bodies' walk, {@code Creatures.BODY_SPEED}); a vanilla mob's ground
     *                    speed grows with its square: about 2.75 blocks a second at 1, 4.0 at 1.2, 5.4 at 1.4 (the old
     *                    baby zombie soldier's pace)
     * @param scale       the toy's size (1 = as modelled; the body and the model both)
     * @param xp          experience for nearby enemies when it dies
     * @param knockback   blocks it slides when a player's or bot's blow pushes it (0 = it stands its ground)
     */
    public record Stats(double health, double damage, double range, int attackTicks, double speed, double scale,
            int xp, double knockback) {
    }

    /**
     * The minions' visible shots (docs/siege-v2.md §2): the archer's toy arrow flies at its target and hits when it
     * gets there; the cannon lobs a shell at where its target stood, marked on the ground, that bursts on landing.
     *
     * @param arrowSpeed   blocks an arrow flies a tick
     * @param shellSpeed   blocks a shell covers a tick (along the ground)
     * @param shellArc     how high a shell's arc rises over a 6 block shot, blocks (longer shots rise higher)
     * @param splashRadius a shell hurts enemies this close to where it lands
     * @param splashShare  the damage to enemies other than the one aimed at (0.5 = half)
     */
    public record Shots(double arrowSpeed, double shellSpeed, double shellArc, double splashRadius,
            double splashShare) {

        public static final Shots DEFAULTS = new Shots(1.0, 0.45, 2.2, 1.6, 0.5);
    }

    private static final Map<MinionKind, Stats> DEFAULTS = Map.of(
            MinionKind.MELEE, new Stats(22, 2.5, 2.2, 20, 1.4, 1.0, 6, 0.35),
            MinionKind.RANGED, new Stats(15, 2.0, 7.0, 30, 1.4, 1.0, 6, 0.4),
            MinionKind.CANNON, new Stats(60, 5.0, 5.5, 40, 1.2, 1.0, 15, 0.1));

    public static MinionSettings read(ConfigurationSection config) {
        Map<MinionKind, Stats> stats = new EnumMap<>(MinionKind.class);
        for (MinionKind kind : MinionKind.values()) {
            Stats d = DEFAULTS.get(kind);
            String p = PATH + kind.key() + ".";
            stats.put(kind, new Stats(
                    config.getDouble(p + "health", d.health()),
                    config.getDouble(p + "damage", d.damage()),
                    config.getDouble(p + "range", d.range()),
                    ticks(config.getDouble(p + "attack-seconds", d.attackTicks() / 20.0)),
                    config.getDouble(p + "speed", d.speed()),
                    config.getDouble(p + "scale", d.scale()),
                    config.getInt(p + "xp", d.xp()),
                    Math.max(0, config.getDouble(p + "knockback", d.knockback()))));
        }
        return new MinionSettings(
                config.getBoolean(PATH + "enabled", true),
                ticks(config.getDouble(PATH + "first-wave-seconds", 20)),
                Math.max(20, ticks(config.getDouble(PATH + "interval-seconds", 30))),
                Math.max(0, config.getInt(PATH + "melee", 3)),
                Math.max(0, config.getInt(PATH + "ranged", 2)),
                Math.max(1, config.getInt(PATH + "players-per-extra", 3)),
                Math.max(0, config.getInt(PATH + "max-extra", 2)),
                Math.max(0, config.getInt(PATH + "cannon-every", 3)),
                config.getDouble(PATH + "health-per-wave", 0.02),
                config.getDouble(PATH + "aggro-radius", 7),
                config.getDouble(PATH + "leash", 10),
                config.getDouble(PATH + "waypoint-reach", 2),
                Math.max(10, ticks(config.getDouble(PATH + "stuck-seconds", 3))),
                config.getDouble(PATH + "xp-radius", 12),
                config.getDouble(PATH + "empowered-bonus", 0.25),
                stats,
                formation(config),
                shots(config));
    }

    private static Shots shots(ConfigurationSection config) {
        Shots d = Shots.DEFAULTS;
        String p = PATH + "shots.";
        return new Shots(
                Math.max(0.1, config.getDouble(p + "arrow-speed", d.arrowSpeed())),
                Math.max(0.05, config.getDouble(p + "shell-speed", d.shellSpeed())),
                Math.max(0, config.getDouble(p + "shell-arc", d.shellArc())),
                Math.max(0, config.getDouble(p + "splash-radius", d.splashRadius())),
                Math.clamp(config.getDouble(p + "splash-share", d.splashShare()), 0, 1));
    }

    private static Formation.Settings formation(ConfigurationSection config) {
        Formation.Settings d = Formation.Settings.DEFAULTS;
        String p = PATH + "formation.";
        return new Formation.Settings(
                Math.max(0.5, config.getDouble(p + "row-spacing", d.rowSpacing())),
                Math.max(0.5, config.getDouble(p + "file-spacing", d.fileSpacing())),
                Math.max(1, config.getInt(p + "per-row", d.perRow())),
                Math.max(0, config.getDouble(p + "max-lateral", d.maxLateral())),
                Math.max(1, config.getDouble(p + "lookahead", d.lookahead())),
                Math.max(0, config.getDouble(p + "pace-slack", d.paceSlack())),
                Math.clamp(config.getDouble(p + "slow-factor", d.slowFactor()), 0.1, 1),
                Math.clamp(config.getDouble(p + "catch-up-factor", d.catchUpFactor()), 1, 2),
                Math.max(0.5, config.getDouble(p + "melee-stand", d.meleeStand())),
                Math.max(0, config.getDouble(p + "ranged-margin", d.rangedMargin())),
                Math.max(0, config.getDouble(p + "lateral-weight", d.lateralWeight())),
                Math.max(0, config.getDouble(p + "crowd-penalty", d.crowdPenalty())),
                config.getBoolean(p + "knockback", d.knockback()),
                Math.max(0, config.getDouble(p + "brake-ticks", d.brakeTicks())),
                Math.clamp(config.getDouble(p + "brake-factor", d.brakeFactor()), 0.1, 1));
    }

    public Stats stats(MinionKind kind) {
        return stats.get(kind);
    }

    /** Extra melee minions per lane for a bigger team of {@code teamSize} players. */
    public int extra(int teamSize) {
        return Math.min(maxExtra, Math.max(0, teamSize - 1) / playersPerExtra);
    }

    static int ticks(double seconds) {
        return (int) Math.round(seconds * 20);
    }
}
