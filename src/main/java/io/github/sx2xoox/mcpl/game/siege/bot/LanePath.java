package io.github.sx2xoox.mcpl.game.siege.bot;

import java.util.List;

/**
 * A lane as a bot reads it (pure, no Bukkit): the waypoints from its own base to the enemy base on the ground plane,
 * and how far along it a place is ("progress", blocks of the lane walked from the own base).
 */
public final class LanePath {

    private final List<SiegeBotBrain.Vec> points;
    /** Blocks walked up to each waypoint. */
    private final double[] walked;

    public LanePath(List<SiegeBotBrain.Vec> points) {
        if (points.isEmpty()) {
            throw new IllegalArgumentException("A lane needs a waypoint");
        }
        this.points = List.copyOf(points);
        this.walked = new double[this.points.size()];
        for (int i = 1; i < this.points.size(); i++) {
            walked[i] = walked[i - 1] + this.points.get(i - 1).distance(this.points.get(i));
        }
    }

    public List<SiegeBotBrain.Vec> points() {
        return points;
    }

    public double length() {
        return walked[walked.length - 1];
    }

    /** The progress of the lane's point nearest to {@code at}. */
    public double progress(SiegeBotBrain.Vec at) {
        return nearest(at)[0];
    }

    /** How far {@code at} is from the lane. */
    public double distance(SiegeBotBrain.Vec at) {
        return nearest(at)[1];
    }

    /** The point {@code progress} blocks along the lane (clamped to its ends). */
    public SiegeBotBrain.Vec pointAt(double progress) {
        if (points.size() == 1 || progress <= 0) {
            return points.getFirst();
        }
        for (int i = 1; i < points.size(); i++) {
            if (progress <= walked[i]) {
                double span = walked[i] - walked[i - 1];
                double t = span <= 1.0E-9 ? 0 : (progress - walked[i - 1]) / span;
                SiegeBotBrain.Vec a = points.get(i - 1);
                SiegeBotBrain.Vec b = points.get(i);
                return new SiegeBotBrain.Vec(a.x() + (b.x() - a.x()) * t, a.z() + (b.z() - a.z()) * t);
            }
        }
        return points.getLast();
    }

    /** The index of the segment {@code progress} lies on (its first waypoint). */
    public int segment(double progress) {
        for (int i = 1; i < points.size(); i++) {
            if (progress <= walked[i]) {
                return i - 1;
            }
        }
        return Math.max(0, points.size() - 2);
    }

    /** {progress, distance} of the nearest point of the lane. */
    private double[] nearest(SiegeBotBrain.Vec at) {
        if (points.size() == 1) {
            return new double[] {0, at.distance(points.getFirst())};
        }
        double best = Double.MAX_VALUE;
        double bestProgress = 0;
        for (int i = 1; i < points.size(); i++) {
            SiegeBotBrain.Vec a = points.get(i - 1);
            SiegeBotBrain.Vec b = points.get(i);
            double dx = b.x() - a.x();
            double dz = b.z() - a.z();
            double length2 = dx * dx + dz * dz;
            double t = length2 <= 1.0E-9 ? 0
                    : Math.max(0, Math.min(1, ((at.x() - a.x()) * dx + (at.z() - a.z()) * dz) / length2));
            double px = a.x() + dx * t;
            double pz = a.z() + dz * t;
            double d = Math.hypot(at.x() - px, at.z() - pz);
            if (d < best - 1.0E-9) {
                best = d;
                bestProgress = walked[i - 1] + Math.sqrt(length2) * t;
            }
        }
        return new double[] {bestProgress, best};
    }
}
