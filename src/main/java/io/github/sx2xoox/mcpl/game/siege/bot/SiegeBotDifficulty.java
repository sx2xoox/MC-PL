package io.github.sx2xoox.mcpl.game.siege.bot;

import java.util.Locale;

import org.jspecify.annotations.Nullable;

/**
 * How well 공성전 봇전 bots play. Every difficulty thinks the same way ({@link SiegeBotBrain}); its
 * {@link SiegeBotSettings.Profile} ({@code games.siege.bots.profile.<key>.*}) changes how fast it reacts, how well it
 * aims, how often it uses its skill, when it backs off, whom it focuses, whether it waits for last hits and how often it
 * goes to the jungle.
 */
public enum SiegeBotDifficulty {
    EASY("쉬움", "easy"),
    NORMAL("보통", "normal"),
    HARD("어려움", "hard");

    private final String koreanName;
    private final String key;

    SiegeBotDifficulty(String koreanName, String key) {
        this.koreanName = koreanName;
        this.key = key;
    }

    public String koreanName() {
        return koreanName;
    }

    /** Config key ({@code easy}, {@code normal}, {@code hard}). */
    public String key() {
        return key;
    }

    /** Parses {@code 쉬움}/{@code easy}/{@code EASY} ...; null if unknown. */
    public static @Nullable SiegeBotDifficulty parse(@Nullable String text) {
        if (text == null) {
            return null;
        }
        String wanted = text.trim().toLowerCase(Locale.ROOT);
        for (SiegeBotDifficulty difficulty : values()) {
            if (difficulty.key.equals(wanted) || difficulty.koreanName.equals(wanted)
                    || difficulty.name().equalsIgnoreCase(wanted)) {
                return difficulty;
            }
        }
        return null;
    }
}
