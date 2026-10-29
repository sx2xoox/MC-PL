package io.github.sx2xoox.mcpl.game.augment.bot;

import java.util.HashMap;
import java.util.Map;

/**
 * How fast everyone in the fight really moves, from where they stand tick after tick. The server's velocity of a
 * walking player (and of a bot's teleported body) is about zero, so a bow that leads by it leads by nothing; a player
 * leads by the movement they see. Pure (no Bukkit): positions in, smoothed velocities (blocks per tick) out.
 */
public final class MotionTracker {

    /** Weight of the newest step in the smoothed velocity (the rest is the earlier estimate). */
    static final double SMOOTHING = 0.4;
    /** A step longer than this in one tick is a teleport, not movement: the estimate starts over. */
    static final double TELEPORT = 4;

    private record Track(double x, double y, double z, long tick, double vx, double vy, double vz) {
    }

    private final Map<Object, Track> tracks = new HashMap<>();

    /** {@code key} stands at (x, y, z) at {@code tick}. */
    public void update(Object key, double x, double y, double z, long tick) {
        Track old = tracks.get(key);
        if (old == null || tick <= old.tick()) {
            if (old == null) {
                tracks.put(key, new Track(x, y, z, tick, 0, 0, 0));
            }
            return;
        }
        long dt = tick - old.tick();
        double sx = (x - old.x()) / dt;
        double sy = (y - old.y()) / dt;
        double sz = (z - old.z()) / dt;
        if (dt > 10 || Math.sqrt(sx * sx + sy * sy + sz * sz) > TELEPORT) {
            tracks.put(key, new Track(x, y, z, tick, 0, 0, 0));
            return;
        }
        tracks.put(key, new Track(x, y, z, tick, old.vx() + (sx - old.vx()) * SMOOTHING,
                old.vy() + (sy - old.vy()) * SMOOTHING, old.vz() + (sz - old.vz()) * SMOOTHING));
    }

    /** The smoothed velocity {x, y, z} of {@code key} in blocks per tick (zero if unknown). */
    public double[] velocity(Object key) {
        Track t = tracks.get(key);
        return t == null ? new double[3] : new double[] {t.vx(), t.vy(), t.vz()};
    }

    /** Stops tracking {@code key}. */
    public void forget(Object key) {
        tracks.remove(key);
    }
}
