package io.github.sx2xoox.speedrunbot.core;

import java.util.Random;

/**
 * Fair-play perception: the bot only learns blocks and entities that a ray from its own eyes reaches inside the
 * camera's field of view and the client's render distance. The world itself, seeds, structure lookups and chunk data
 * are never read except through these rays (and the player's own inventory/screen, which the player sees anyway).
 */
public final class PerceptionRules {
    /** Vertical FOV of the default client (70). */
    public static final double V_FOV_DEG = 70.0;
    /** Horizontal FOV for a 16:9 window at vertical FOV 70: 2*atan(tan(35deg)*16/9) ~= 102 deg. */
    public static final double H_FOV_DEG = Math.toDegrees(2 * Math.atan(Math.tan(Math.toRadians(V_FOV_DEG / 2)) * 16.0 / 9.0));
    /**
     * Water fog: a sight ray that has passed through this many water blocks ends (the vanilla client fogs water out
     * within roughly 15-30 blocks; seen from above the surface the sea floor shows to about this depth).
     */
    public static final int WATER_VIEW_BLOCKS = 24;

    /** True when a ray that crossed {@code waterBlocks} water cells may still go on (always in relaxed mode). */
    public boolean seesThroughWater(int waterBlocks) {
        return !fair || waterBlocks <= WATER_VIEW_BLOCKS;
    }

    /** Blocks closer than this are also "seen" around the player regardless of view direction (what is at your feet). */
    public static final double NEAR_SENSE = 1.5;

    private final boolean fair;
    private final double maxRange;
    private final int renderChunks;

    /**
     * @param fair          false = omniscient comparison mode (no FOV / line of sight limit)
     * @param renderChunks  client render distance in chunks; the ray range is capped at renderChunks * 16
     * @param rayRange      max ray length the bot casts (blocks), further capped by render distance
     */
    public PerceptionRules(boolean fair, int renderChunks, double rayRange) {
        this.fair = fair;
        this.maxRange = Math.min(rayRange, renderChunks * 16.0);
        this.renderChunks = renderChunks;
    }

    public int renderChunks() {
        return renderChunks;
    }

    public boolean fair() {
        return fair;
    }

    public double maxRange() {
        return maxRange;
    }

    /** True when a point at (dx, dy, dz) from the eyes can be seen with the given look direction (ignores occlusion). */
    public boolean inView(float yaw, float pitch, double dx, double dy, double dz) {
        double d2 = dx * dx + dy * dy + dz * dz;
        if (d2 > maxRange * maxRange) {
            return false;
        }
        if (!fair || d2 <= NEAR_SENSE * NEAR_SENSE) {
            return true;
        }
        // camera basis
        double[] f = direction(yaw, pitch);
        double[] r = direction(yaw + 90f, 0f);
        double[] u = cross(r, f);
        double len = Math.sqrt(d2);
        double fz = (dx * f[0] + dy * f[1] + dz * f[2]) / len;
        if (fz <= 0) {
            return false;
        }
        double rx = (dx * r[0] + dy * r[1] + dz * r[2]) / len;
        double uy = (dx * u[0] + dy * u[1] + dz * u[2]) / len;
        double hx = Math.toDegrees(Math.atan2(Math.abs(rx), fz));
        double vy = Math.toDegrees(Math.atan2(Math.abs(uy), fz));
        return hx <= H_FOV_DEG / 2 + 1e-6 && vy <= V_FOV_DEG / 2 + 1e-6;
    }

    /**
     * A random ray direction (unit vector) inside the view frustum, uniform over the screen. In omniscient mode it is
     * uniform over the sphere.
     */
    public double[] sampleRay(float yaw, float pitch, Random rnd) {
        if (!fair) {
            double z = rnd.nextDouble() * 2 - 1;
            double a = rnd.nextDouble() * Math.PI * 2;
            double s = Math.sqrt(1 - z * z);
            return new double[]{s * Math.cos(a), z, s * Math.sin(a)};
        }
        double[] f = direction(yaw, pitch);
        double[] r = direction(yaw + 90f, 0f);
        double[] u = cross(r, f);
        double tx = Math.tan(Math.toRadians(H_FOV_DEG / 2)) * (rnd.nextDouble() * 2 - 1);
        double ty = Math.tan(Math.toRadians(V_FOV_DEG / 2)) * (rnd.nextDouble() * 2 - 1);
        double x = f[0] + r[0] * tx + u[0] * ty;
        double y = f[1] + r[1] * tx + u[1] * ty;
        double z = f[2] + r[2] * tx + u[2] * ty;
        double len = Math.sqrt(x * x + y * y + z * z);
        return new double[]{x / len, y / len, z / len};
    }

    /** Minecraft look vector: yaw 0 = +Z, pitch positive = down. */
    public static double[] direction(float yaw, float pitch) {
        double y = Math.toRadians(yaw);
        double p = Math.toRadians(pitch);
        return new double[]{-Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p)};
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }
}
