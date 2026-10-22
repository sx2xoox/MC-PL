package io.github.sx2xoox.mcpl.core.ui;

import java.util.Locale;

/** Small Korean-friendly number and time formatting helpers for player-facing text. */
public final class Format {

    private Format() {
    }

    /** Whole numbers without decimals ({@code 3}), others with one decimal ({@code 1.5}). */
    public static String number(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.05) {
            return Long.toString(Math.round(value));
        }
        return String.format(Locale.ROOT, "%.1f", value);
    }

    /** Health points as hearts, e.g. {@code 7} → {@code "3.5"}. */
    public static String hearts(double healthPoints) {
        return number(healthPoints / 2.0);
    }

    /** {@code "4분 12초"}, {@code "38초"}, {@code "1시간 2분"}. */
    public static String duration(long millis) {
        long totalSeconds = Math.max(0, millis / 1000);
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        if (hours > 0) {
            return hours + "시간 " + minutes + "분";
        }
        if (minutes > 0) {
            return seconds == 0 ? minutes + "분" : minutes + "분 " + seconds + "초";
        }
        return seconds + "초";
    }

    /** {@code "m:ss"} clock, e.g. {@code 125} seconds → {@code "2:05"}. */
    public static String clock(long totalSeconds) {
        long safe = Math.max(0, totalSeconds);
        return String.format(Locale.ROOT, "%d:%02d", safe / 60, safe % 60);
    }
}
