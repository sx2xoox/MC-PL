package io.github.sx2xoox.mcpl.game.superpower;

import java.util.Locale;

import org.jspecify.annotations.Nullable;

/** How abilities are handed out at the start of a match ({@code games.superpower.selection.mode}). */
public enum SelectionMode {

    /** Random, unique across players while the pool allows; rerolls during the prep phase. */
    RANDOM("무작위"),
    /** Every player picks one of three random candidates from a menu; random on timeout. */
    CHOICE("선택");

    private final String koreanName;

    SelectionMode(String koreanName) {
        this.koreanName = koreanName;
    }

    /** Korean label for chat and the sidebar. */
    public String koreanName() {
        return koreanName;
    }

    /** Parses {@code RANDOM}/{@code CHOICE} (any case) or the Korean words (무작위, 랜덤, 선택). Null if unknown. */
    public static @Nullable SelectionMode parse(@Nullable String input) {
        if (input == null) {
            return null;
        }
        String value = input.trim().toUpperCase(Locale.ROOT);
        return switch (value) {
            case "RANDOM", "무작위", "랜덤" -> RANDOM;
            case "CHOICE", "선택" -> CHOICE;
            default -> null;
        };
    }
}
