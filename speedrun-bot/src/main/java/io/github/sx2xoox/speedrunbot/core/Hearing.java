package io.github.sx2xoox.speedrunbot.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * What a player hears of lava: the vanilla 1.16.1 client plays lava sounds from its random display ticks, and the bot
 * hears exactly those sounds, localised only as well as a human localises a sound.
 *
 * <p>Vanilla ({@code ClientWorld#doRandomBlockDisplayTicks}, every client tick): 667 times, one random block within
 * +-16 of the player (each axis {@code nextInt(16) - nextInt(16)}) and one within +-32 get a display tick. A lava fluid
 * block with air above plays {@code block.lava.pop} with chance 1/100 and {@code block.lava.ambient} with chance 1/200
 * per display tick, volume 0.2-0.4. A sound of volume <= 1 is heard up to 16 blocks away (linear fall-off). Sounds
 * are heard through walls (Minecraft has no occlusion).
 *
 * <p>Localisation ({@link #perceive}): a human hears roughly where a sound comes from (stereo panning and loudness), not
 * the block. The bot gets the direction with a random error (sd {@link #YAW_SD} degrees left/right,
 * {@link #PITCH_SD} up/down) and the distance from the loudness with a relative error (sd {@link #DIST_SD}); it never
 * gets the block position. Simulated on the server because a headless server has no sound engine; a client port
 * would read the sound events the client plays.
 */
public final class Hearing {
    /** Answers whether a block is lava with air above it (the display-tick condition). */
    public interface LavaQuery {
        boolean lavaWithAirAbove(int x, int y, int z);
    }

    public static final int SAMPLES = 667;
    public static final double RANGE = 16.0;
    public static final double YAW_SD = 15.0;
    public static final double PITCH_SD = 25.0;
    public static final double DIST_SD = 0.3;

    /** A sound the player heard: where it really was (simulation side) and its loudness. */
    public static final class Sound {
        public final double x, y, z;
        public final float volume;

        public Sound(double x, double y, double z, float volume) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.volume = volume;
        }
    }

    /** What the player makes of a heard sound: an estimated source point (direction and distance are uncertain). */
    public static final class Heard {
        public final double x, y, z;
        public final double distance;

        public Heard(double x, double y, double z, double distance) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.distance = distance;
        }
    }

    private Hearing() {
    }

    /** One client tick of lava display ticks around the listener block; returns the sounds that are audible. */
    public static List<Sound> lavaSounds(int px, int py, int pz, double ex, double ey, double ez, LavaQuery q, Random r) {
        List<Sound> out = new ArrayList<>();
        for (int j = 0; j < SAMPLES; j++) {
            for (int range : new int[]{16, 32}) {
                int x = px + r.nextInt(range) - r.nextInt(range);
                int y = py + r.nextInt(range) - r.nextInt(range);
                int z = pz + r.nextInt(range) - r.nextInt(range);
                if (y < 0 || y > 255) {
                    continue;
                }
                // the vanilla rolls happen for every display tick; the world is only asked when one fires
                boolean pop = r.nextInt(100) == 0;
                boolean ambient = r.nextInt(200) == 0;
                if (!pop && !ambient) {
                    continue;
                }
                double sx = x + 0.5, sy = y + 0.5, sz = z + 0.5;
                double d = Math.sqrt(sq(sx - ex) + sq(sy - ey) + sq(sz - ez));
                if (d >= RANGE || !q.lavaWithAirAbove(x, y, z)) {
                    continue;
                }
                out.add(new Sound(sx, sy, sz, 0.2f + r.nextFloat() * 0.2f));
            }
        }
        return out;
    }

    /** A heard sound as the player perceives it from the ear position (ex, ey, ez). */
    public static Heard perceive(Sound s, double ex, double ey, double ez, Random r) {
        double dx = s.x - ex, dy = s.y - ey, dz = s.z - ez;
        double d = Math.max(0.5, Math.sqrt(dx * dx + dy * dy + dz * dz));
        double yaw = Math.atan2(dz, dx) + Math.toRadians(r.nextGaussian() * YAW_SD);
        double pitch = Math.asin(Math.max(-1, Math.min(1, dy / d))) + Math.toRadians(r.nextGaussian() * PITCH_SD);
        pitch = Math.max(-Math.PI / 2, Math.min(Math.PI / 2, pitch));
        double est = Math.max(1.0, d * (1 + r.nextGaussian() * DIST_SD));
        double hx = ex + Math.cos(pitch) * Math.cos(yaw) * est;
        double hz = ez + Math.cos(pitch) * Math.sin(yaw) * est;
        double hy = ey + Math.sin(pitch) * est;
        return new Heard(hx, hy, hz, est);
    }

    private static double sq(double v) {
        return v * v;
    }
}
