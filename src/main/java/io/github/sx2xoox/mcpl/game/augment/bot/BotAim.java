package io.github.sx2xoox.mcpl.game.augment.bot;

/**
 * Pure turning math of a bot's camera (no Bukkit): Minecraft yaw/pitch of a direction, wrapping angles the short way,
 * turning at a limited speed like a player moving the mouse, and the body following the head like a player's does.
 * Angles in degrees, Minecraft convention: yaw 0 = south (+z), 90 = west (-x); pitch -90 = up, 90 = down.
 */
public final class BotAim {

    /** How far a player's body may stay turned from the head before it follows (vanilla: about 50°). */
    public static final float BODY_LAG = 50f;
    /** A turn at least this large is "flicked" in one tick by bots that can ({@link BotDifficulty#flicks()}). */
    public static final float FLICK = 100f;

    private BotAim() {
    }

    /** {@code degrees} wrapped into (-180, 180]. */
    public static float wrap(float degrees) {
        float out = degrees % 360f;
        if (out > 180f) {
            out -= 360f;
        } else if (out <= -180f) {
            out += 360f;
        }
        return out;
    }

    /** The signed shortest turn from {@code from} to {@code to}, in (-180, 180]. */
    public static float delta(float from, float to) {
        return wrap(to - from);
    }

    /** The yaw facing along {@code (dx, dz)}; {@code fallback} for no horizontal direction. */
    public static float yaw(double dx, double dz, float fallback) {
        if (dx * dx + dz * dz < 1.0E-8) {
            return fallback;
        }
        return wrap((float) Math.toDegrees(Math.atan2(-dx, dz)));
    }

    /** The pitch looking along {@code (dx, dy, dz)} (up negative). */
    public static float pitch(double dx, double dy, double dz) {
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal < 1.0E-8 && Math.abs(dy) < 1.0E-8) {
            return 0f;
        }
        return clampPitch((float) Math.toDegrees(-Math.atan2(dy, horizontal)));
    }

    public static float clampPitch(float pitch) {
        return Math.max(-90f, Math.min(90f, pitch));
    }

    /**
     * Turns {@code current} towards {@code target} by at most {@code maxStep} degrees, the short way round; a turn of
     * {@link #FLICK} or more is done at once when {@code flick} (a top player's flick shot).
     */
    public static float turn(float current, float target, float maxStep, boolean flick) {
        float diff = delta(current, target);
        if (Math.abs(diff) <= maxStep || flick && Math.abs(diff) >= FLICK) {
            return wrap(target);
        }
        return wrap(current + Math.copySign(maxStep, diff));
    }

    /** {@link #turn} for the pitch (no wrapping, clamped to ±90). */
    public static float turnPitch(float current, float target, float maxStep) {
        float goal = clampPitch(target);
        float diff = goal - current;
        if (Math.abs(diff) <= maxStep) {
            return goal;
        }
        return clampPitch(current + Math.copySign(maxStep, diff));
    }

    /**
     * The body yaw after one tick: towards {@code wanted} (the walking direction, or the head when standing) at
     * {@code step} degrees per tick, but never more than {@link #BODY_LAG} away from the head.
     */
    public static float body(float body, float head, float wanted, float step) {
        float turned = turn(body, wanted, step, false);
        float off = delta(head, turned);
        if (Math.abs(off) > BODY_LAG) {
            turned = wrap(head + Math.copySign(BODY_LAG, off));
        }
        return turned;
    }
}
