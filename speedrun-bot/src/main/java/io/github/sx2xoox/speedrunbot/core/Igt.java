package io.github.sx2xoox.speedrunbot.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * In-game time, matched to SpeedRunIGT for 1.16.1 RSG: the clock starts when the runner is in the world and stops at
 * the final event; there is no pause in a bot run, so IGT = server ticks between the two / 20. A tick-based clock is
 * not affected by server lag (MSPT above 50 ms only stretches real time), so it is recorded separately as MSPT.
 */
public final class Igt {
    public static final int TICKS_PER_SECOND = 20;

    private final long startTick;
    private final Map<String, Long> splits = new LinkedHashMap<>();

    public Igt(long startTick) {
        this.startTick = startTick;
    }

    public long startTick() {
        return startTick;
    }

    public long elapsedTicks(long nowTick) {
        return Math.max(0, nowTick - startTick);
    }

    /** Records a split the first time it is reached; later calls keep the first time. */
    public boolean split(String name, long nowTick) {
        if (splits.containsKey(name)) {
            return false;
        }
        splits.put(name, elapsedTicks(nowTick));
        return true;
    }

    public boolean has(String name) {
        return splits.containsKey(name);
    }

    /** Split time in ticks since the start, or -1 when not reached. */
    public long splitTicks(String name) {
        Long v = splits.get(name);
        return v == null ? -1 : v;
    }

    public Map<String, Long> splits() {
        return splits;
    }

    public static double seconds(long ticks) {
        return ticks / (double) TICKS_PER_SECOND;
    }

    /** m:ss.SSS like the SpeedRunIGT display. */
    public static String format(long ticks) {
        if (ticks < 0) {
            return "-";
        }
        long ms = ticks * 1000L / TICKS_PER_SECOND;
        long m = ms / 60000;
        long s = (ms / 1000) % 60;
        long r = ms % 1000;
        return String.format("%d:%02d.%03d", m, s, r);
    }
}
