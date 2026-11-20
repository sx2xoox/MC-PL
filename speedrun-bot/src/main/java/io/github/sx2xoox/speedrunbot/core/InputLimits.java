package io.github.sx2xoox.speedrunbot.core;

/**
 * Upper bounds on what the bot may do per tick, chosen to stay within what a fast human player can do with a mouse
 * and keyboard. The fake player only acts through these inputs.
 */
public final class InputLimits {
    /** Maximum yaw change per tick (degrees): 45/tick = 900 deg/s, a fast but human flick. */
    public static final float MAX_YAW_PER_TICK = 45f;
    /** Maximum pitch change per tick (degrees). */
    public static final float MAX_PITCH_PER_TICK = 35f;
    /** Survival block reach of the vanilla client (blocks from the eyes). */
    public static final double BLOCK_REACH = 4.5;
    /** Entity attack reach of the vanilla client. */
    public static final double ENTITY_REACH = 3.0;
    /** One attack (left click) or one use (right click) per tick at most. */
    public static final int CLICKS_PER_TICK = 1;
    /** Minimum ticks between two inventory/crafting screen clicks (100 ms; fast runners click at ~8-10/s). */
    public static final int GUI_CLICK_TICKS = 2;
    /** Ticks to open a screen (right click a table/furnace, or press E) before the first click. */
    public static final int GUI_OPEN_TICKS = 2;
    /** Ticks between two right clicks that use an item on a block (vanilla client itemUseCooldown = 4). */
    public static final int USE_COOLDOWN_TICKS = 4;

    private InputLimits() {
    }

    /** Wraps an angle to [-180, 180). */
    public static float wrap(float deg) {
        float d = deg % 360f;
        if (d >= 180f) {
            d -= 360f;
        }
        if (d < -180f) {
            d += 360f;
        }
        return d;
    }

    /** Moves {@code current} toward {@code target} along the shortest way by at most {@code maxStep} degrees. */
    public static float approachAngle(float current, float target, float maxStep) {
        float diff = wrap(target - current);
        if (diff > maxStep) {
            diff = maxStep;
        } else if (diff < -maxStep) {
            diff = -maxStep;
        }
        return current + diff;
    }

    public static float approachPitch(float current, float target, float maxStep) {
        float t = Math.max(-90f, Math.min(90f, target));
        float diff = t - current;
        if (diff > maxStep) {
            diff = maxStep;
        } else if (diff < -maxStep) {
            diff = -maxStep;
        }
        return current + diff;
    }

    /** Yaw (Minecraft convention: 0 = +Z, 90 = -X) looking from (dx, dz). */
    public static float yawTo(double dx, double dz) {
        return (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
    }

    /** Pitch (positive = down) looking along (dx, dy, dz). */
    public static float pitchTo(double dx, double dy, double dz) {
        double h = Math.sqrt(dx * dx + dz * dz);
        return (float) -Math.toDegrees(Math.atan2(dy, h));
    }
}
