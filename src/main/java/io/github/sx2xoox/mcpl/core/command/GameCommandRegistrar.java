package io.github.sx2xoox.mcpl.core.command;

import java.util.function.Predicate;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.github.sx2xoox.mcpl.core.Hub;
import io.github.sx2xoox.mcpl.core.game.MiniGame;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;

/**
 * Lets a {@link MiniGame} add its commands to {@code /게임} (and its aliases {@code /mcpl}, {@code /mg}). The hub owns
 * the shape of the command; a mode plugs into these places (register a Korean literal and its English twin each):
 *
 * <ul>
 *   <li>{@link #registerStart}: {@code /게임 시작 <모드> [옵션]}, every way to start the mode;</li>
 *   <li>{@link #matchCommands}: what {@code /게임 리롤 · 점수 · 내정보 · 팀 · 연습 허수아비} do in its matches;</li>
 *   <li>{@link #registerPracticeTool}: its tools under {@code /게임 연습};</li>
 *   <li>{@link #register}: the mode's info/admin node, e.g. {@code /게임 능력 목록};</li>
 *   <li>{@link #moved}: old forms that keep working, hidden from tab completion and help.</li>
 * </ul>
 */
public interface GameCommandRegistrar {

    Hub hub();

    /**
     * Attaches the mode's info/admin node under the root ({@code /게임 능력}). Names must not clash with the hub's own
     * subcommands. Words under it that match no subcommand go to the old forms ({@link #moved}).
     */
    void register(LiteralArgumentBuilder<CommandSourceStack> subcommand);

    /**
     * Attaches a mode under {@code /게임 시작} (and {@code /mcpl start}), e.g. {@code 초능력전쟁} with its option
     * subcommands ({@code 연습 [능력]}). Use {@link #play()} on it when an option is open to everyone (joining a
     * practice), {@link #admin()} otherwise; {@link #startMatch} checks the permission itself.
     */
    void registerStart(LiteralArgumentBuilder<CommandSourceStack> node);

    /**
     * Where the old free-text {@code /게임 시작 <이름>} goes for {@code game}: the words after {@code 시작}, e.g.
     * {@code "증강 봇전"}. Modes without a path start by name as before.
     */
    void startPath(MiniGame game, String path);

    /** Adds a tool under {@code /게임 연습} ({@code /mcpl practice}); refuse it when another practice is running. */
    void registerPracticeTool(LiteralArgumentBuilder<CommandSourceStack> tool);

    /** What the common in-match commands do while a match of {@code game} runs. */
    void matchCommands(MiniGame game, MatchCommands commands);

    /**
     * An old form that keeps working, hidden from tab completion and help: {@code oldForm} is the words after the
     * root, each with {@code |} alternatives ({@code "능력|ability 리롤|reroll"}); {@code newForm} the Korean words
     * after {@code /게임} it now runs ({@code "리롤"}), followed by the rest of what was typed. The player sees a hint.
     */
    void moved(String oldForm, String newForm);

    /** Requirement for everyone ({@value Permissions#PLAY}). */
    Predicate<CommandSourceStack> play();

    /** Requirement for admins ({@value Permissions#ADMIN}). */
    Predicate<CommandSourceStack> admin();

    /**
     * Starts a match of {@code game} for everyone online, as {@code /게임 시작} always did: admins only (others are
     * told so), feedback when it cannot start. Returns the command result.
     */
    int startMatch(CommandSender sender, MiniGame game);
}
