package io.github.sx2xoox.mcpl.game.augment.command;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.github.sx2xoox.mcpl.core.command.GameCommandRegistrar;
import io.github.sx2xoox.mcpl.core.ui.Messages;
import io.github.sx2xoox.mcpl.game.augment.AugmentGame;
import io.github.sx2xoox.mcpl.game.augment.bot.BotController;
import io.github.sx2xoox.mcpl.game.augment.bot.BotSettings;
import io.github.sx2xoox.mcpl.game.augment.runtime.AugmentMatch;
import io.github.sx2xoox.mcpl.game.augment.runtime.MatchPhase;
import io.github.sx2xoox.mcpl.game.augment.runtime.SpectatorView;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * The 봇 관전전 tools:
 *
 * <pre>
 * /게임 증강 관전|watch                 the bots standing (click a name to follow it)
 * /게임 증강 관전|watch 다음|next       follow the next bot
 * /게임 증강 관전|watch &lt;봇&gt;            follow that bot with the spectator camera
 * /게임 증강 배속|speed [1-4]          admin, bot matches: how fast the match clocks run
 * </pre>
 */
public final class SpectateCommand {

    private final AugmentGame game;

    SpectateCommand(AugmentGame game) {
        this.game = game;
    }

    void addTo(LiteralArgumentBuilder<CommandSourceStack> node, GameCommandRegistrar registrar, boolean english) {
        String botArg = english ? "bot" : "봇";
        String scaleArg = english ? "scale" : "배속";
        node.then(Commands.literal(english ? "watch" : "관전")
                .executes(ctx -> list(ctx.getSource().getSender()))
                .then(Commands.argument(botArg, StringArgumentType.greedyString())
                        .suggests(this::suggestBots)
                        .executes(ctx -> follow(ctx.getSource().getSender(),
                                StringArgumentType.getString(ctx, botArg)))));
        node.then(Commands.literal(english ? "speed" : "배속")
                .requires(registrar.admin())
                .executes(ctx -> showScale(ctx.getSource().getSender()))
                .then(Commands.argument(scaleArg, IntegerArgumentType.integer(1, BotSettings.Spectate.MAX_TIME_SCALE))
                        .executes(ctx -> setScale(ctx.getSource().getSender(),
                                IntegerArgumentType.getInteger(ctx, scaleArg)))));
    }

    private CompletableFuture<Suggestions> suggestBots(CommandContext<CommandSourceStack> ctx,
                                                       SuggestionsBuilder builder) {
        String typed = builder.getRemaining();
        for (String word : new String[] {"다음", "next"}) {
            if (word.startsWith(typed)) {
                builder.suggest(word);
            }
        }
        AugmentMatch match = game.currentMatch();
        if (match != null && match.bots() != null) {
            for (BotController bot : match.bots().all()) {
                if (match.bots().body(bot.id()) != null && bot.name().startsWith(typed)) {
                    builder.suggest(bot.name());
                }
            }
        }
        return builder.buildFuture();
    }

    /** The 봇 관전전 running now, or null after telling the sender why not. */
    private @Nullable SpectatorView view(CommandSender sender) {
        AugmentMatch match = game.currentMatch();
        SpectatorView view = match == null ? null : match.spectators();
        if (view == null || match.phase() == MatchPhase.PREPARING) {
            game.messages().send(sender, "spectate.no-match");
            return null;
        }
        return view;
    }

    private int list(CommandSender sender) {
        SpectatorView view = view(sender);
        if (view == null) {
            return 0;
        }
        view.list().forEach(sender::sendMessage);
        return Command.SINGLE_SUCCESS;
    }

    private int follow(CommandSender sender, String input) {
        SpectatorView view = view(sender);
        if (view == null) {
            return 0;
        }
        if (!(sender instanceof Player player)) {
            game.messages().send(sender, "command.player-only");
            return 0;
        }
        String query = input.trim();
        UUID bot = query.equals("다음") || query.equalsIgnoreCase("next") ? view.next(view.followed(player))
                : view.find(query);
        if (bot == null) {
            game.messages().send(player, "spectate.unknown-bot", Messages.tag("input", query));
            return 0;
        }
        if (!view.follow(player, bot)) {
            game.messages().send(player, "spectate.cannot-follow");
            return 0;
        }
        return Command.SINGLE_SUCCESS;
    }

    private @Nullable AugmentMatch botMatch(CommandSender sender) {
        AugmentMatch match = game.currentMatch();
        if (match == null || match.bots() == null || match.isPractice()) {
            game.messages().send(sender, "spectate.scale-no-match");
            return null;
        }
        return match;
    }

    private int showScale(CommandSender sender) {
        AugmentMatch match = botMatch(sender);
        if (match == null) {
            return 0;
        }
        game.messages().send(sender, "spectate.scale-current", Messages.tag("scale", match.timeScale()));
        return Command.SINGLE_SUCCESS;
    }

    private int setScale(CommandSender sender, int scale) {
        AugmentMatch match = botMatch(sender);
        if (match == null || !match.setTimeScale(scale)) {
            return 0;
        }
        match.session().broadcast(game.messages().prefixed("spectate.scale-set", Messages.tag("scale", scale),
                Messages.tag("player", sender.name())));
        return Command.SINGLE_SUCCESS;
    }
}
