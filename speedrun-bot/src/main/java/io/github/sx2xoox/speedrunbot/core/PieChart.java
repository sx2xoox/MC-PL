package io.github.sx2xoox.speedrunbot.core;

/**
 * The F3 debug pie chart's "blockentities" slice, as a fair sense. The vanilla 1.16 client profiles the time spent
 * rendering block entities; chests are drawn with entity models, so the slice grows when a chest is inside the camera
 * frustum within render distance, whether or not it is hidden behind terrain (the client draws block entities in
 * visible chunk sections after a frustum test only, with no occlusion test on the block entity itself). Runners read
 * it with a very narrow window to get the direction of a buried treasure. This class is that frustum test: no
 * position is ever given to the bot, only how many chests the current view frustum holds (the slice size).
 *
 * <p>The view: vertical FOV (the FOV option, 30..110) and window aspect (width / height; a tall thin window gives a
 * narrow horizontal FOV). Changing either takes a human a moment (an options click, a window resize):
 * {@link #VIEW_CHANGE_TICKS}.
 */
public final class PieChart {
    /** Ticks a view change (FOV option or window resize) costs: the bot does nothing else meanwhile. */
    public static final int VIEW_CHANGE_TICKS = 20;
    /** Narrowest FOV the options slider allows. */
    public static final double MIN_FOV = 30.0;
    public static final double MAX_FOV = 110.0;

    /** Camera settings. */
    public static final class View {
        public final double vFovDeg;
        public final double aspect;
        public final int renderChunks;

        public View(double vFovDeg, double aspect, int renderChunks) {
            this.vFovDeg = Math.max(MIN_FOV, Math.min(MAX_FOV, vFovDeg));
            this.aspect = Math.max(0.02, aspect);
            this.renderChunks = renderChunks;
        }

        /** Horizontal FOV in degrees for this window shape. */
        public double hFovDeg() {
            return Math.toDegrees(2 * Math.atan(Math.tan(Math.toRadians(vFovDeg / 2)) * aspect));
        }

        /** The default 16:9 window at FOV 70. */
        public static View normal(int renderChunks) {
            return new View(70, 16.0 / 9.0, renderChunks);
        }

        @Override
        public String toString() {
            return String.format("fov %.0f aspect %.2f (h %.1f deg)", vFovDeg, aspect, hFovDeg());
        }
    }

    private PieChart() {
    }

    /**
     * True when the block's 1x1x1 box at (bx,by,bz) is drawn by a camera at the eye with this look: its chunk within
     * the render distance (square, as the client loads chunks) and the box not wholly outside one of the four side
     * planes of the frustum or behind the camera.
     */
    public static boolean blockInFrustum(View v, double ex, double ey, double ez, float yaw, float pitch, int bx, int by, int bz) {
        int ecx = (int) Math.floor(ex) >> 4, ecz = (int) Math.floor(ez) >> 4;
        if (Math.abs((bx >> 4) - ecx) > v.renderChunks || Math.abs((bz >> 4) - ecz) > v.renderChunks) {
            return false;
        }
        double[] f = PerceptionRules.direction(yaw, pitch);
        double[] r = PerceptionRules.direction(yaw + 90f, 0f);
        double[] u = {r[1] * f[2] - r[2] * f[1], r[2] * f[0] - r[0] * f[2], r[0] * f[1] - r[1] * f[0]};
        double th = Math.tan(Math.toRadians(v.hFovDeg() / 2)), tv = Math.tan(Math.toRadians(v.vFovDeg / 2));
        // per plane: is some corner on the inside?
        boolean right = false, left = false, up = false, down = false, front = false;
        for (int k = 0; k < 8; k++) {
            double dx = bx + (k & 1) - ex, dy = by + ((k >> 1) & 1) - ey, dz = bz + ((k >> 2) & 1) - ez;
            double fz = dx * f[0] + dy * f[1] + dz * f[2];
            double rx = dx * r[0] + dy * r[1] + dz * r[2];
            double uy = dx * u[0] + dy * u[1] + dz * u[2];
            right |= rx <= fz * th;
            left |= -rx <= fz * th;
            up |= uy <= fz * tv;
            down |= -uy <= fz * tv;
            front |= fz > 0.05;
        }
        return right && left && up && down && front;
    }

    /** The slice as a player reads it: 0 none, 1 one chest, 2 a few, 3 many. */
    public static int level(int chests) {
        return chests <= 0 ? 0 : chests == 1 ? 1 : chests <= 3 ? 2 : 3;
    }
}
