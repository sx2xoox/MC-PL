package io.github.sx2xoox.mcpl.game.augment.command;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.github.sx2xoox.mcpl.core.command.GameCommandRegistrar;
import io.github.sx2xoox.mcpl.core.game.GameStartException;
import io.github.sx2xoox.mcpl.core.ui.Messages;
import io.github.sx2xoox.mcpl.game.augment.AugmentGame;
import io.github.sx2xoox.mcpl.game.augment.bot.BotDifficulty;
import io.github.sx2xoox.mcpl.game.augment.bot.BotSettings;
import io.github.sx2xoox.mcpl.game.augment.runtime.AugmentMatch;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.command.CommandSender;
import org.jspecify.annotations.Nullable;

/**
 * {@code /게임 증강 봇전 [봇 수] [쉬움|보통|어려움|지옥]} (English {@code /mcpl augment bots [count] [difficulty]}):
 * an admin starts a real 증강 야생전 match where bots fill the lobby; everyone online plays (as for
 * {@code /게임 시작}). Count and difficulty may come in any order; missing ones come from {@code games.augment.bots}.
 */
public final class BotCommand {

    private final AugmentGame game;

    BotCommand(AugmentGame game) {
        this.game = game;
    }

    void addTo(LiteralArgumentBuilder<CommandSourceStack> node, GameCommandRegistrar registrar, boolean english) {
        String optionsArg = english ? "options" : "옵션";
        node.then(Commands.literal(english ? "bots" : "봇전")
                .requires(registrar.admin())
                .executes(ctx -> start(ctx.getSource().getSender(), ""))
                .then(Commands.argument(optionsArg, StringArgumentType.greedyString())
                        .suggests(this::suggest)
                        .executes(ctx -> start(ctx.getSource().getSender(),
                                StringArgumentType.getString(ctx, optionsArg)))));
    }

    private CompletableFuture<Suggestions> suggest(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        String typed = builder.getRemaining();
        int space = typed.lastIndexOf(' ');
        SuggestionsBuilder last = builder.createOffset(builder.getStart() + space + 1);
        for (String option : suggestions(typed.substring(space + 1))) {
            last.suggest(option);
        }
        return last.buildFuture();
    }

    /** What the options argument suggests for the word being typed: counts and difficulties. */
    public static List<String> suggestions(String word) {
        List<String> out = new ArrayList<>();
        for (String count : List.of("3", "5", "8", "11", "15")) {
            if (count.startsWith(word)) {
                out.add(count);
            }
        }
        for (BotDifficulty difficulty : BotDifficulty.values()) {
            if (difficulty.koreanName().startsWith(word)) {
                out.add(difficulty.koreanName());
            }
        }
        return out;
    }

    /** The parsed options: a count, a difficulty (either may be null) or an error (the bad word). */
    public record Options(@Nullable Integer count, @Nullable BotDifficulty difficulty, @Nullable String error) {

        public static Options parse(String input) {
            Integer count = null;
            BotDifficulty difficulty = null;
            for (String word : input.trim().split("\\s+")) {
                if (word.isEmpty()) {
                    continue;
                }
                BotDifficulty parsed = BotDifficulty.parse(word);
                if (parsed != null && difficulty == null) {
                    difficulty = parsed;
                    continue;
                }
                try {
                    int number = Integer.parseInt(word);
                    if (count == null && number >= 1) {
                        count = number;
                        continue;
                    }
                } catch (NumberFormatException ignored) {
                    // fall through: not a number
                }
                return new Options(count, difficulty, word);
            }
            return new Options(count, difficulty, null);
        }
    }

    private int start(CommandSender sender, String input) {
        Options options = Options.parse(input);
        if (options.error() != null) {
            game.messages().send(sender, "bots.bad-option", Messages.tag("input", options.error()));
            return 0;
        }
        BotSettings settings = game.readBotSettings();
        int count = Math.max(1, Math.min(settings.max(), options.count() != null ? options.count() : settings.count()));
        BotDifficulty difficulty = options.difficulty() != null ? options.difficulty() : settings.difficulty();
        game.requestBots(new AugmentMatch.BotSetup(settings, difficulty, count));
        try {
            game.hub().games().start(game.botGame(), sender);
        } catch (GameStartException e) {
            game.requestBots(null);
            sender.sendMessage(e.feedback());
            return 0;
        }
        game.messages().send(sender, "bots.starting", Messages.tag("count", count),
                Messages.tag("difficulty", difficulty.koreanName()));
        return Command.SINGLE_SUCCESS;
    }
}
