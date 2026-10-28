package io.github.sx2xoox.mcpl.game.augment.bot;

import java.util.Locale;

import org.jspecify.annotations.Nullable;

/**
 * How well bots play. Every difficulty thinks the same way (the {@link BotBrain} scores the same options); the
 * {@link BotProfile} of a difficulty changes how well the choice is carried out: reactions, aim, crits, bow leading,
 * golden apples, gear pace, how well it reads gear and how fast and how big it builds. The numbers live in
 * {@code games.augment.bots.<key>.*}.
 */
public enum BotDifficulty {
    /** A real opponent for an average player: decent aim and crits, bow, apples, a base. */
    EASY("쉬움", "easy"),
    /** A skilled PvP player: fast reactions, strafing, crit timing, shield and apples, smart retreats. */
    NORMAL("보통", "normal"),
    /** A top-tier PvP player: near-instant reactions, leading bow shots, perfect combos, strong bases. */
    HARD("어려움", "hard"),
    /** Beyond human limits (still fair: no seeing through walls). */
    HELL("지옥", "hell");

    private final String koreanName;
    private final String key;

    BotDifficulty(String koreanName, String key) {
        this.koreanName = koreanName;
        this.key = key;
    }

    public String koreanName() {
        return koreanName;
    }

    /** Config key ({@code easy}, {@code normal}, {@code hard}, {@code hell}). */
    public String key() {
        return key;
    }

    /** Parses {@code 쉬움}/{@code easy}/{@code EASY} ...; null if unknown. */
    public static @Nullable BotDifficulty parse(@Nullable String text) {
        if (text == null) {
            return null;
        }
        String wanted = text.trim().toLowerCase(Locale.ROOT);
        for (BotDifficulty difficulty : values()) {
            if (difficulty.key.equals(wanted) || difficulty.koreanName.equals(wanted)
                    || difficulty.name().equalsIgnoreCase(wanted)) {
                return difficulty;
            }
        }
        return null;
    }
}
