package io.github.sx2xoox.mcpl.game.augment.bot;

/** Bow aiming without Bukkit: where to shoot a full-power arrow so it meets a moving target. */
public final class Ballistics {

    /** Speed of a fully drawn arrow (blocks per tick). */
    public static final double ARROW_SPEED = 3.0;
    /** Gravity on an arrow (blocks per tick squared). */
    static final double GRAVITY = 0.05;

    private Ballistics() {
    }

    /**
     * The launch velocity {x, y, z} (length {@link #ARROW_SPEED}) from {@code from} to hit a target at {@code to}
     * moving with {@code velocity} (blocks per tick). {@code skill} 0..1 is how much of the target's movement is led
     * (1 = all of it); the drop of the arrow is always compensated.
     */
    public static double[] launch(double[] from, double[] to, double[] velocity, double skill) {
        double dx = to[0] - from[0];
        double dy = to[1] - from[1];
        double dz = to[2] - from[2];
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double ticks = distance / ARROW_SPEED;
        // one refinement: the lead changes the distance a little
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
