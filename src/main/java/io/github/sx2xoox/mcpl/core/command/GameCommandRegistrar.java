package io.github.sx2xoox.mcpl.core.command;

import java.util.function.Predicate;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.github.sx2xoox.mcpl.core.Hub;
import io.papermc.paper.command.brigadier.CommandSourceStack;

/**
 * Lets a {@link io.github.sx2xoox.mcpl.core.game.MiniGame} add subcommands under {@code /게임} (and its aliases
 * {@code /mcpl}, {@code /mg}). Register a Korean literal and its English twin, e.g.:
 *
 * <pre>{@code
 * public void registerCommands(GameCommandRegistrar registrar) {
 *     for (String name : List.of("능력", "ability")) {
 *         registrar.register(Commands.literal(name)
 *                 .requires(registrar.play())
 *                 .then(Commands.literal("목록").executes(ctx -> { ...; return Command.SINGLE_SUCCESS; })));
 *     }
 * }
 * }</pre>
 */
public interface GameCommandRegistrar {

    Hub hub();

    /** Attaches {@code subcommand} under the root command. Names must not clash with the hub's own subcommands. */
    void register(LiteralArgumentBuilder<CommandSourceStack> subcommand);

    /** Requirement for everyone ({@value Permissions#PLAY}). */
    Predicate<CommandSourceStack> play();

    /** Requirement for admins ({@value Permissions#ADMIN}). */
    Predicate<CommandSourceStack> admin();
}
