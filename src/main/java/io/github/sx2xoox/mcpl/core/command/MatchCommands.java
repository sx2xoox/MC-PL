package io.github.sx2xoox.mcpl.core.command;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * What the mode-independent in-match commands do in one mode ({@link GameCommandRegistrar#matchCommands}). The hub
 * calls these only while a match of that mode is the running session; it answers "no match" itself. Return a command
 * result ({@code 1} done, {@code 0} refused after telling the player why), or {@link #UNSUPPORTED} to let the hub
 * give its generic answer (for {@code 점수} a short summary, for {@code 팀} the team members).
 */
public interface MatchCommands {

    int UNSUPPORTED = -1;

    /** {@code /게임 리롤}: redraw the ability / the shown augment cards. */
    default int reroll(Player player) {
        return UNSUPPORTED;
    }

    /** {@code /게임 점수}. */
    default int score(CommandSender sender) {
        return UNSUPPORTED;
    }

    /** {@code /게임 내정보}: my ability, augments, level. */
    default int myInfo(Player player) {
        return UNSUPPORTED;
    }

    /** {@code /게임 팀}: my teammates (and what they play). */
    default int team(Player player) {
        return UNSUPPORTED;
    }

    /** {@code /게임 연습 허수아비 [개수]}: fresh training dummies ({@code count} null = the configured number). */
    default int dummies(Player player, @Nullable Integer count) {
        return UNSUPPORTED;
    }
}
