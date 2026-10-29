package io.github.sx2xoox.mcpl.game.augment.command;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.github.sx2xoox.mcpl.core.command.GameCommandRegistrar;
import io.github.sx2xoox.mcpl.core.game.GameStartException;
import io.github.sx2xoox.mcpl.core.game.MiniGame;
import io.github.sx2xoox.mcpl.core.ui.Messages;
import io.github.sx2xoox.mcpl.game.augment.AugmentGame;
import io.github.sx2xoox.mcpl.game.augment.bot.BotDifficulty;
import io.github.sx2xoox.mcpl.game.augment.bot.BotSettings;
import io.github.sx2xoox.mcpl.game.augment.runtime.AugmentMatch;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.jspecify.annotations.Nullable;

/**
 * {@code /게임 증강 봇전 [봇 수] [난이도 | 난이도별 수]} (English {@code /mcpl augment bots ...}): an admin starts a
 * real 증강 야생전 match where bots fill the lobby; everyone online plays (as for {@code /게임 시작}).
 * {@code /게임 증강 봇전 관전 ...} ({@code bots spectate}) starts a 봇 관전전 instead: only bots play, everyone watches.
 * Count and difficulty may come in any order ("5 보통", "보통 5"); several difficulties mix ("쉬움 3 어려움 2");
 * missing ones come from {@code games.augment.bots}.
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
                .executes(ctx -> start(ctx.getSource().getSender(), "", false))
                .then(Commands.literal(english ? "spectate" : "관전")
                        .executes(ctx -> start(ctx.getSource().getSender(), "", true))
                        .then(Commands.argument(optionsArg, StringArgumentType.greedyString())
                                .suggests(this::suggest)
                                .executes(ctx -> start(ctx.getSource().getSender(),
                                        StringArgumentType.getString(ctx, optionsArg), true))))
                .then(Commands.argument(optionsArg, StringArgumentType.greedyString())
                        .suggests(this::suggest)
                        .executes(ctx -> start(ctx.getSource().getSender(),
                                StringArgumentType.getString(ctx, optionsArg), false))));
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
        for (String count : List.of("2", "3", "5", "8", "15", "20", "30", "40")) {
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

    /**
     * The parsed options: a count and a difficulty (either may be null), or a mix of difficulties with their counts
     * ("쉬움 3 어려움 2", "3 쉬움 2 어려움"; a difficulty without a count is one bot), or an error (the bad word).
     */
    public record Options(@Nullable Integer count, @Nullable BotDifficulty difficulty, Map<BotDifficulty, Integer> mix,
                          @Nullable String error) {

        public Options {
            mix = Collections.unmodifiableMap(new LinkedHashMap<>(mix));
        }

        private static Options error(String word) {
            return new Options(null, null, Map.of(), word);
        }

        public static Options parse(String input) {
            List<String> words = new ArrayList<>();
            for (String word : input.trim().split("\\s+")) {
                if (!word.isEmpty()) {
                    words.add(word);
                }
            }
            List<Object> tokens = new ArrayList<>();
            int difficulties = 0;
            for (String word : words) {
                BotDifficulty parsed = BotDifficulty.parse(word);
                if (parsed != null) {
                    tokens.add(parsed);
                    difficulties++;
                    continue;
                }
                Integer number = number(word);
                if (number == null || number < 1) {
                    return error(word);
                }
                tokens.add(number);
            }
            if (difficulties <= 1) {
                Integer count = null;
                BotDifficulty difficulty = null;
                for (int i = 0; i < tokens.size(); i++) {
                    if (tokens.get(i) instanceof BotDifficulty d) {
                        difficulty = d;
                    } else if (count == null) {
                        count = (Integer) tokens.get(i);
                    } else {
                        return error(words.get(i));
                    }
                }
                return new Options(count, difficulty, Map.of(), null);
            }
            Map<BotDifficulty, Integer> mix = new LinkedHashMap<>();
            boolean countFirst = tokens.getFirst() instanceof Integer;
            for (int i = 0; i < tokens.size(); ) {
                if (countFirst) {
                    if (!(tokens.get(i) instanceof Integer n) || i + 1 >= tokens.size()
                            || !(tokens.get(i + 1) instanceof BotDifficulty d)) {
                        return error(words.get(Math.min(i + 1, words.size() - 1)));
                    }
                    mix.merge(d, n, Integer::sum);
                    i += 2;
                } else {
                    if (!(tokens.get(i) instanceof BotDifficulty d)) {
                        return error(words.get(i));
                    }
                    int n = 1;
                    if (i + 1 < tokens.size() && tokens.get(i + 1) instanceof Integer given) {
                        n = given;
                        i++;
                    }
                    mix.merge(d, n, Integer::sum);
                    i++;
                }
            }
            int total = mix.values().stream().mapToInt(Integer::intValue).sum();
            return new Options(total, null, mix, null);
        }

        private static @Nullable Integer number(String word) {
            try {
                return Integer.parseInt(word);
            } catch (NumberFormatException e) {
                return null;
            }
        }

        /** The difficulty of every bot to add, capped at {@code bots.max}; defaults from {@code settings}. */
        public List<BotDifficulty> lineup(BotSettings settings) {
            List<BotDifficulty> out = new ArrayList<>();
            if (!mix.isEmpty()) {
                mix.forEach((d, n) -> {
                    for (int i = 0; i < n && out.size() < settings.max(); i++) {
                        out.add(d);
                    }
                });
                return out;
            }
            int wanted = Math.max(1, Math.min(settings.max(), count != null ? count : settings.count()));
            return Collections.nCopies(wanted, difficulty != null ? difficulty : settings.difficulty());
        }
    }

    private int start(CommandSender sender, String input, boolean spectate) {
        Options options = Options.parse(input);
        if (options.error() != null) {
            game.messages().send(sender, spectate ? "spectate.bad-option" : "bots.bad-option",
                    Messages.tag("input", options.error()));
            return 0;
        }
        if (spectate && Bukkit.getOnlinePlayers().isEmpty()) {
            game.messages().send(sender, "spectate.nobody-online");
            return 0;
        }
        BotSettings settings = game.readBotSettings();
        List<BotDifficulty> lineup = new ArrayList<>(options.lineup(settings));
        if (spectate && lineup.size() < 2) {
            lineup.add(lineup.getFirst()); // a fight needs two
        }
        AugmentMatch.BotSetup setup = new AugmentMatch.BotSetup(settings, lineup);
        game.requestBots(setup);
        MiniGame mode = spectate ? game.spectateGame() : game.botGame();
        try {
            game.hub().games().start(mode, sender);
        } catch (GameStartException e) {
            game.requestBots(null);
            sender.sendMessage(e.feedback());
            return 0;
        }
        game.messages().send(sender, spectate ? "spectate.starting" : "bots.starting",
                Messages.tag("count", setup.count()), Messages.tag("difficulty", setup.describe()));
        return Command.SINGLE_SUCCESS;
    }
}
