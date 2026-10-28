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
    EASY("쉬움", "easy", 18f, false),
    /** A skilled PvP player: fast reactions, strafing, crit timing, shield and apples, smart retreats. */
    NORMAL("보통", "normal", 28f, false),
    /** A top-tier PvP player: near-instant reactions, leading bow shots, perfect combos, strong bases. */
    HARD("어려움", "hard", 45f, false),
    /** Beyond human limits (still fair: no seeing through walls). */
    HELL("지옥", "hell", 70f, true);

    private final String koreanName;
    private final String key;
    private final float turnSpeed;
    private final boolean flicks;

    BotDifficulty(String koreanName, String key, float turnSpeed, boolean flicks) {
        this.koreanName = koreanName;
        this.key = key;
        this.turnSpeed = turnSpeed;
        this.flicks = flicks;
    }

    /** How fast the bot turns its camera, in degrees per tick (a player's mouse speed). */
    public float turnSpeed() {
        return turnSpeed;
    }

    /** Snaps big turns (≥ {@link BotAim#FLICK}°) in one tick, like a flick shot. */
    public boolean flicks() {
        return flicks;
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
