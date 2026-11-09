package io.github.sx2xoox.mcpl.game.augment.bot;

/**
 * Bow aiming without Bukkit: where to shoot a full-power arrow so it meets a moving target.
 *
 * <p>An arrow flies like vanilla's: each tick it moves by its velocity, then the velocity is multiplied by
 * {@link #DRAG} and gravity takes {@link #GRAVITY} off its vertical part. {@link #launch} solves that flight (the time
 * to the target, the lead and the pitch) by simulating it, so long shots land where a straight-line estimate without
 * drag would fall short and under-lead. {@link #launchSimple} is the earlier, drag-free estimate (kept to measure the
 * difference, {@code BallisticsTest}).
 */
public final class Ballistics {

    /** Speed of a fully drawn arrow (blocks per tick). */
    public static final double ARROW_SPEED = 3.0;
    /** Gravity on an arrow (blocks per tick squared). */
    static final double GRAVITY = 0.05;
    /** Air drag on an arrow per tick (vanilla's inertia). */
    static final double DRAG = 0.99;
    /** The longest flight solved (ticks): far beyond any bow range a bot shoots at. */
    static final int MAX_TICKS = 80;

    private Ballistics() {
    }

    /**
     * The launch velocity {x, y, z} (length {@link #ARROW_SPEED}) from {@code from} to hit a target at {@code to}
     * moving with {@code velocity} (blocks per tick). {@code skill} 0..1 is how much of the target's movement is led
     * (1 = all of it); the drop and the drag of the arrow are always compensated.
     */
    public static double[] launch(double[] from, double[] to, double[] velocity, double skill) {
        double dx = to[0] - from[0];
        double dy = to[1] - from[1];
        double dz = to[2] - from[2];
        // a target in the air falls back down: lead only half of an upward step, none of a fall (it lands)
        double vy = Math.max(0, velocity[1]) * 0.5;
        double ticks = flightTicks(Math.hypot(dx, dz), dy);
        double pitch = 0;
        for (int i = 0; i < 4; i++) {
            double px = dx + velocity[0] * ticks * skill;
            double py = dy + vy * ticks * skill;
            double pz = dz + velocity[2] * ticks * skill;
            double horizontal = Math.hypot(px, pz);
            pitch = pitchFor(horizontal, py);
            double next = ticksTo(horizontal, pitch);
            if (Double.isNaN(next) || Math.abs(next - ticks) < 0.01) {
                ticks = Double.isNaN(next) ? ticks : next;
                break;
            }
            ticks = next;
        }
        double px = dx + velocity[0] * ticks * skill;
        double pz = dz + velocity[2] * ticks * skill;
        double horizontal = Math.hypot(px, pz);
        if (horizontal < 1.0E-6) {
            return new double[] {0, dy >= 0 ? ARROW_SPEED : -ARROW_SPEED, 0};
        }
        double cos = Math.cos(pitch);
        return new double[] {px / horizontal * cos * ARROW_SPEED, Math.sin(pitch) * ARROW_SPEED,
                pz / horizontal * cos * ARROW_SPEED};
    }

    /** The flight time (ticks, fractional) of an arrow to {@code horizontal} blocks away, {@code dy} up. */
    static double flightTicks(double horizontal, double dy) {
        double t = ticksTo(horizontal, pitchFor(horizontal, dy));
        return Double.isNaN(t) ? Math.sqrt(horizontal * horizontal + dy * dy) / ARROW_SPEED : t;
    }

    /**
     * The (lower, direct) launch pitch in radians, up positive, that brings a full-power arrow {@code dy} blocks up at
     * {@code horizontal} blocks out; the flattest-falling 45° if the target is out of reach.
     */
    static double pitchFor(double horizontal, double dy) {
        if (horizontal < 1.0E-6) {
            return dy >= 0 ? Math.PI / 2 : -Math.PI / 2;
        }
        double low = -Math.PI / 2 + 0.01;
        double high = Math.PI / 4; // a direct shot at a far target is below the 45° of the farthest reach
        if (!(heightAt(horizontal, high) >= dy)) {
            // a target high up close by: steeper, as long as the arrow still gets higher at that distance
            double best = high;
            double bestHeight = heightAt(horizontal, high);
            for (double p = high + 0.035; p < Math.PI / 2 - 0.01; p += 0.035) {
                double h = heightAt(horizontal, p);
                if (Double.isNaN(h) || !Double.isNaN(bestHeight) && h <= bestHeight) {
                    break;
                }
                best = p;
                bestHeight = h;
                if (h >= dy) {
                    break;
                }
            }
            if (!(bestHeight >= dy)) {
                return best; // out of reach: as high as it gets there
            }
            low = best - 0.035;
            high = best;
        }
        // height at the target's distance rises with the pitch on the direct branch: bisect
        for (int i = 0; i < 40; i++) {
            double mid = (low + high) / 2;
            double h = heightAt(horizontal, mid);
            if (Double.isNaN(h) || h < dy) {
                low = mid;
            } else {
                high = mid;
            }
        }
        return (low + high) / 2;
    }

    /** The arrow's height when it has flown {@code horizontal} blocks out at {@code pitch} (NaN: it never gets there). */
    static double heightAt(double horizontal, double pitch) {
        double vx = Math.cos(pitch) * ARROW_SPEED;
        double vy = Math.sin(pitch) * ARROW_SPEED;
        double x = 0;
        double y = 0;
        for (int tick = 0; tick < MAX_TICKS; tick++) {
            double nx = x + vx;
            double ny = y + vy;
            if (nx >= horizontal) {
                double f = (horizontal - x) / (nx - x);
                return y + (ny - y) * f;
            }
            x = nx;
            y = ny;
            vx *= DRAG;
            vy = vy * DRAG - GRAVITY;
        }
        return Double.NaN;
    }

    /** Ticks (fractional) the arrow needs to fly {@code horizontal} blocks out at {@code pitch} (NaN: never). */
    static double ticksTo(double horizontal, double pitch) {
        double vx = Math.cos(pitch) * ARROW_SPEED;
        double x = 0;
        for (int tick = 0; tick < MAX_TICKS; tick++) {
            double nx = x + vx;
            if (nx >= horizontal) {
                return tick + (horizontal - x) / (nx - x);
            }
            x = nx;
            vx *= DRAG;
        }
        return Double.NaN;
    }

    /**
     * Where an arrow launched with {@code launch} from {@code from} passes closest to a target that starts at
     * {@code to} and moves with {@code velocity}, flying like vanilla's (moving, drag, gravity): the miss distance in
     * blocks. For the tests and the measurement of the aim.
     */
    public static double miss(double[] from, double[] launch, double[] to, double[] velocity) {
        double x = from[0];
        double y = from[1];
        double z = from[2];
        double vx = launch[0];
        double vy = launch[1];
        double vz = launch[2];
        double best = Double.POSITIVE_INFINITY;
        for (int tick = 0; tick < MAX_TICKS; tick++) {
            double nx = x + vx;
            double ny = y + vy;
            double nz = z + vz;
            // the closest approach along this tick's segment to where the target is meanwhile
            for (int s = 0; s <= 60; s++) {
                double f = s / 60.0;
                double t = tick + f;
                double ax = x + (nx - x) * f - (to[0] + velocity[0] * t);
                double ay = y + (ny - y) * f - (to[1] + velocity[1] * t);
                double az = z + (nz - z) * f - (to[2] + velocity[2] * t);
                best = Math.min(best, Math.sqrt(ax * ax + ay * ay + az * az));
            }
            x = nx;
            y = ny;
            z = nz;
            vx *= DRAG;
            vy = vy * DRAG - GRAVITY;
            vz *= DRAG;
        }
        return best;
    }

    /**
     * The earlier aim: the flight time from the straight-line distance at full speed (no drag) and the drop of that
     * time under gravity. It falls short and under-leads on long shots; kept to measure the difference and for bots
     * playing the earlier brain ({@code -Dmcpl.bots.legacy}).
     */
    public static double[] launchSimple(double[] from, double[] to, double[] velocity, double skill) {
        double dx = to[0] - from[0];
        double dy = to[1] - from[1];
        double dz = to[2] - from[2];
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double ticks = distance / ARROW_SPEED;
        for (int i = 0; i < 2; i++) {
            double px = dx + velocity[0] * ticks * skill;
            double pz = dz + velocity[2] * ticks * skill;
            ticks = Math.sqrt(px * px + dy * dy + pz * pz) / ARROW_SPEED;
        }
        double tx = dx + velocity[0] * ticks * skill;
        double ty = dy + Math.max(0, velocity[1]) * ticks * skill * 0.5 + GRAVITY * ticks * ticks / 2;
        double tz = dz + velocity[2] * ticks * skill;
        double length = Math.sqrt(tx * tx + ty * ty + tz * tz);
        if (length < 1.0E-9) {
            return new double[] {0, ARROW_SPEED, 0};
        }
        return new double[] {tx / length * ARROW_SPEED, ty / length * ARROW_SPEED, tz / length * ARROW_SPEED};
    }

    /** Turns {@code direction} by a random error of up to {@code degrees} (uniform in a cone); same length. */
    public static double[] scatter(double[] direction, double degrees, java.util.Random random) {
        if (degrees <= 0) {
            return direction.clone();
        }
        double yaw = Math.toRadians((random.nextDouble() * 2 - 1) * degrees);
        double pitch = Math.toRadians((random.nextDouble() * 2 - 1) * degrees);
        double x = direction[0] * Math.cos(yaw) - direction[2] * Math.sin(yaw);
        double z = direction[0] * Math.sin(yaw) + direction[2] * Math.cos(yaw);
        double y = direction[1] + Math.sin(pitch) * Math.hypot(x, z);
        double length = Math.sqrt(direction[0] * direction[0] + direction[1] * direction[1]
                + direction[2] * direction[2]);
        double now = Math.sqrt(x * x + y * y + z * z);
        return new double[] {x / now * length, y / now * length, z / now * length};
    }
}
