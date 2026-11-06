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
import io.github.sx2xoox.mcpl.core.command.MatchCommands;
import io.github.sx2xoox.mcpl.core.game.MiniGame;
import io.github.sx2xoox.mcpl.core.ui.Messages;
import io.github.sx2xoox.mcpl.game.augment.AugmentGame;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentInfo;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentRegistry;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentTier;
import io.github.sx2xoox.mcpl.game.augment.api.Params;
import io.github.sx2xoox.mcpl.game.augment.bonus.BonusCondition;
import io.github.sx2xoox.mcpl.game.augment.config.AugmentConfig;
import io.github.sx2xoox.mcpl.game.augment.runtime.AugmentMatch;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * The 증강 야생전 commands (English twins in brackets):
 *
 * <pre>
 * /게임 시작 증강 (augment)               admin: a match for everyone
 * /게임 시작 증강 연습 · 봇전 · 관전 …       practice ({@link PracticeCommand}), 봇전 / 봇 관전전 ({@link BotCommand})
 * /게임 리롤 · 내정보 · 연습 &lt;도구&gt;         card reroll, my augments, practice tools ({@link MatchCommands})
 * /게임 증강                             reopen the card menu (a pick is waiting), else show my augments
 * /게임 증강 목록|list                    every augment by tier (hover: text)
 * /게임 증강 정보|info &lt;증강&gt;            one augment
 * /게임 증강 보너스|bonus                 the bonus conditions
 * /게임 증강 관전|watch · 배속|speed        the 봇 관전전 tools ({@link SpectateCommand})
 * </pre>
 *
 * The old forms ({@code /게임 증강 리롤 · 연습 · 봇전 · 받기 …}) still work through {@link GameCommandRegistrar#moved}.
 */
public final class AugmentCommand {

    private final AugmentGame game;

    public AugmentCommand(AugmentGame game) {
        this.game = game;
    }

    private Messages messages() {
        return game.messages();
    }

    public void register(GameCommandRegistrar registrar) {
        PracticeCommand practice = new PracticeCommand(game);
        MatchCommands commands = new AugmentMatchCommands(practice);
        for (MiniGame mode : List.of(game, game.practice(), game.botGame(), game.spectateGame())) {
            registrar.matchCommands(mode, commands);
        }
        registrar.startPath(game, "증강");
        registrar.startPath(game.practice(), "증강 연습 모두");
        registrar.startPath(game.botGame(), "증강 봇전");
        registrar.startPath(game.spectateGame(), "증강 관전");
        registrar.moved("증강|augment 리롤|reroll", "리롤");
        registrar.moved("증강|augment 연습|practice", "시작 증강 연습");
        registrar.moved("증강|augment 봇전|bots 관전|spectate", "시작 증강 관전");
        registrar.moved("증강|augment 봇전|bots", "시작 증강 봇전");
        registrar.moved("증강|augment 받기|give", "연습 받기");
        registrar.moved("증강|augment 빼기|take", "연습 빼기");
        registrar.moved("증강|augment 카드|cards", "연습 카드");
        registrar.moved("증강|augment 다음단계|next", "연습 다음단계");
        registrar.moved("증강|augment 허수아비|dummies", "연습 허수아비");
        registrar.moved("증강|augment 대전|pvp", "연습 대전");
        BotCommand bots = new BotCommand(game);
        SpectateCommand spectate = new SpectateCommand(game);
        for (String root : List.of("증강", "augment")) {
            boolean english = root.equals("augment");
            String augmentArg = english ? "augment" : "증강";
            LiteralArgumentBuilder<CommandSourceStack> start = Commands.literal(root)
                    .requires(registrar.play())
                    .executes(ctx -> registrar.startMatch(ctx.getSource().getSender(), game));
            practice.addStartOptions(start, registrar, english);
            bots.addTo(start, registrar, english);
            registrar.registerStart(start);
            practice.registerTools(registrar, english, this::suggest);

            LiteralArgumentBuilder<CommandSourceStack> node = Commands.literal(root)
                    .requires(registrar.play())
                    .executes(ctx -> open(ctx.getSource().getSender()));
            for (String name : List.of("목록", "list")) {
                node.then(Commands.literal(name).executes(ctx -> list(ctx.getSource().getSender())));
            }
            for (String name : List.of("정보", "info")) {
                node.then(Commands.literal(name)
                        .executes(ctx -> usage(ctx.getSource().getSender()))
                        .then(Commands.argument(augmentArg, StringArgumentType.greedyString())
                                .suggests(this::suggest)
                                .executes(ctx -> info(ctx.getSource().getSender(),
                                        StringArgumentType.getString(ctx, augmentArg)))));
            }
            for (String name : List.of("보너스", "bonus")) {
                node.then(Commands.literal(name).executes(ctx -> bonuses(ctx.getSource().getSender())));
            }
            spectate.addTo(node, registrar, english);
            registrar.register(node);
        }
    }

    /** {@code /게임 리롤 · 내정보 · 연습 허수아비} in every 증강 mode. */
    private final class AugmentMatchCommands implements MatchCommands {

        private final PracticeCommand practice;

        AugmentMatchCommands(PracticeCommand practice) {
            this.practice = practice;
        }

        @Override
        public int reroll(Player player) {
            AugmentMatch match = game.currentMatch();
            if (match == null || match.currentOffer(player.getUniqueId()) == null) {
                messages().send(player, "command.nothing-to-pick");
                return 0;
            }
            return match.reroll(player) ? Command.SINGLE_SUCCESS : 0;
        }

        @Override
        public int myInfo(Player player) {
            AugmentMatch match = game.currentMatch();
            if (match == null || !match.session().isParticipant(player)) {
                messages().send(player, "command.mine-not-playing");
                return 0;
            }
            mine(player, match);
            return Command.SINGLE_SUCCESS;
        }

        @Override
        public int dummies(Player player, @Nullable Integer count) {
            return practice.dummies(player, count);
        }
    }

    private CompletableFuture<Suggestions> suggest(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        augmentSuggestions(game.registry(), builder.getRemaining()).forEach(builder::suggest);
        return builder.buildFuture();
    }

    /** Augment names and ids starting with {@code typed} (case and spaces ignored): the tab completion. */
    public static List<String> augmentSuggestions(AugmentRegistry registry, String typed) {
        String remaining = typed.toLowerCase(java.util.Locale.ROOT).replace(" ", "");
        List<String> out = new ArrayList<>();
        for (AugmentRegistry.Entry entry : registry.all()) {
            AugmentInfo info = entry.info();
            if (info.name().replace(" ", "").startsWith(remaining)) {
                out.add(info.name());
            }
            if (info.id().startsWith(remaining)) {
                out.add(info.id());
            }
        }
        return out;
    }

    private int usage(CommandSender sender) {
        messages().send(sender, "command.usage-info");
        return 0;
    }

    /** Reopens the card menu if a pick is waiting, else lists the player's augments (or the help). */
    private int open(CommandSender sender) {
        AugmentMatch match = game.currentMatch();
        if (!(sender instanceof Player player) || match == null || !match.session().isParticipant(player)) {
            messages().lines("command.help").forEach(sender::sendMessage);
            return Command.SINGLE_SUCCESS;
        }
        if (match.openMenu(player)) {
            return Command.SINGLE_SUCCESS;
        }
        mine(player, match);
        return Command.SINGLE_SUCCESS;
    }

    /** The player's augments. */
    private void mine(Player player, AugmentMatch match) {
        List<AugmentInfo> owned = match.augments(player.getUniqueId());
        if (owned.isEmpty()) {
            messages().send(player, "command.mine-none");
            return;
        }
        messages().send(player, "command.mine-header", Messages.tag("count", owned.size()));
        for (AugmentInfo info : owned) {
            player.sendMessage(entry(info));
        }
    }

    private int list(CommandSender sender) {
        AugmentConfig.Snapshot snapshot = game.augments().snapshot();
        messages().sendPlain(sender, "command.list-header", Messages.tag("count", game.registry().all().size()));
        for (AugmentTier tier : AugmentTier.values()) {
            List<Component> names = new ArrayList<>();
            for (AugmentInfo info : game.registry().ofTier(tier)) {
                Component name = name(info, snapshot.params(info));
                if (!snapshot.isEnabled(info.id())) {
                    name = name.color(NamedTextColor.DARK_GRAY);
                }
                names.add(name);
            }
            sender.sendMessage(messages().get("command.list-tier", Messages.tag("tier", messages().get("tier." + tier.key())),
                    Messages.tag("augments", Component.join(JoinConfiguration.separator(Component.text(", ",
                            NamedTextColor.GRAY)), names))));
        }
        messages().sendPlain(sender, "command.list-footer");
        return Command.SINGLE_SUCCESS;
    }

    private int info(CommandSender sender, String input) {
        AugmentInfo info = game.findAugment(input);
        if (info == null) {
            messages().send(sender, "command.unknown-augment", Messages.tag("input", input));
            return 0;
        }
        AugmentConfig.Snapshot snapshot = game.augments().snapshot();
        sender.sendMessage(messages().get("command.info", Messages.tag("augment", Component.text(info.name(),
                        info.tier().color())), Messages.tag("tier", messages().get("tier." + info.tier().key())),
                Messages.tag("kind", info.kind().koreanName()), Messages.tag("id", info.id()),
                Messages.tag("description", snapshot.params(info).render(info.description())),
                Messages.tag("state", messages().get(snapshot.isEnabled(info.id()) ? "command.state-on"
                        : "command.state-off"))));
        return Command.SINGLE_SUCCESS;
    }

    private int bonuses(CommandSender sender) {
        AugmentConfig.Snapshot snapshot = game.augments().snapshot();
        messages().sendPlain(sender, "command.bonus-header");
        for (BonusCondition condition : BonusCondition.values()) {
            sender.sendMessage(messages().get(condition.firstCome() ? "command.bonus-entry-first" : "command.bonus-entry",
                    Messages.tag("condition", Component.text(condition.koreanName())),
                    Messages.tag("tier", messages().get("tier." + snapshot.tier(condition).key())),
                    Messages.tag("description", snapshot.params(condition).render(condition.description())),
                    Messages.tag("state", messages().get(snapshot.isEnabled(condition) ? "command.state-on"
                            : "command.state-off"))));
        }
        return Command.SINGLE_SUCCESS;
    }

    private Component name(AugmentInfo info, Params params) {
        return Component.text(info.name(), info.tier().color())
                .hoverEvent(HoverEvent.showText(Component.text(params.render(info.description()), NamedTextColor.GRAY)))
                .clickEvent(ClickEvent.runCommand("/게임 증강 정보 " + info.id()));
    }

    private Component entry(AugmentInfo info) {
        AugmentConfig.Snapshot snapshot = game.augments().snapshot();
        return messages().get("command.mine-entry", Messages.tag("augment", name(info, snapshot.params(info))),
                Messages.tag("description", snapshot.params(info).render(info.description())));
    }
}
