package io.github.sx2xoox.mcpl.game.siege.bot;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * The options of {@code /게임 시작 공성전 봇전 [팀당 인원] [쉬움|보통|어려움]}: in any order, each at most once; missing ones
 * come from {@code games.siege.bots}. {@code error} is the first word that is neither.
 */
public record SiegeBotOptions(@Nullable Integer perSide, @Nullable SiegeBotDifficulty difficulty,
                              @Nullable String error) {

    public static SiegeBotOptions parse(String input) {
        Integer perSide = null;
        SiegeBotDifficulty difficulty = null;
        for (String word : input.trim().split("\\s+")) {
            if (word.isEmpty()) {
                continue;
            }
            SiegeBotDifficulty parsed = SiegeBotDifficulty.parse(word);
            if (parsed != null && difficulty == null) {
                difficulty = parsed;
                continue;
            }
            Integer number = number(word);
            if (parsed == null && number != null && number >= 1 && perSide == null) {
                perSide = number;
                continue;
            }
            return new SiegeBotOptions(null, null, word);
        }
        return new SiegeBotOptions(perSide, difficulty, null);
    }

    private static @Nullable Integer number(String word) {
        String digits = word.endsWith("명") ? word.substring(0, word.length() - 1) : word;
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The setup these options ask for, defaults from {@code settings}; the size is capped. */
    public SiegeBots.Setup setup(SiegeBotSettings settings) {
        int size = Math.max(1, Math.min(SiegeBotSettings.MAX_PER_SIDE, perSide != null ? perSide : settings.perSide()));
        return new SiegeBots.Setup(settings, size, difficulty != null ? difficulty : settings.difficulty());
    }

    /** What the options argument suggests for the word being typed. */
    public static List<String> suggestions(String word) {
        List<String> out = new ArrayList<>();
        for (String count : List.of("1", "2", "3", "4", "5")) {
            if (count.startsWith(word)) {
                out.add(count);
            }
        }
        for (SiegeBotDifficulty difficulty : SiegeBotDifficulty.values()) {
            if (difficulty.koreanName().startsWith(word)) {
                out.add(difficulty.koreanName());
            }
        }
        return out;
    }
}
