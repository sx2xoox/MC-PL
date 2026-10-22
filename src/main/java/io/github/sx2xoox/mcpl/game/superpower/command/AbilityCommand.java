package io.github.sx2xoox.mcpl.game.superpower.command;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.github.sx2xoox.mcpl.core.command.GameCommandRegistrar;
import io.github.sx2xoox.mcpl.core.command.Permissions;
import io.github.sx2xoox.mcpl.core.game.GameState;
import io.github.sx2xoox.mcpl.core.ui.Messages;
import io.github.sx2xoox.mcpl.game.superpower.SelectionMode;
import io.github.sx2xoox.mcpl.game.superpower.SuperpowerGame;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityParams;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityRegistry;
import io.github.sx2xoox.mcpl.game.superpower.config.AbilityConfig;
import io.github.sx2xoox.mcpl.game.superpower.runtime.SuperpowerMatch;
import io.github.sx2xoox.mcpl.game.superpower.ui.AbilityTexts;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /게임 능력} (English twin {@code /mcpl ability}):
 *
 * <pre>
 * /게임 능력                                   help
 * /게임 능력 목록|list                         all abilities (hover: skills, click: info)
 * /게임 능력 정보|info &lt;능력&gt;                  one ability (id or Korean name)
 * /게임 능력 지정|assign &lt;플레이어&gt; &lt;능력&gt;      admin: for the next match or the running reveal/prep phase
 * /게임 능력 선택방식|mode [RANDOM|CHOICE]      admin: selection mode (saved to config.yml)
 * /게임 능력 리롤|reroll                        reroll during the prep phase (RANDOM)
 * </pre>
 */
public final class AbilityCommand {

    private final SuperpowerGame game;

    public AbilityCommand(SuperpowerGame game) {
        this.game = game;
    }

    private Messages messages() {
        return game.messages();
    }

    public void register(GameCommandRegistrar registrar) {
        for (String root : List.of("능력", "ability")) {
            boolean english = root.equals("ability");
            String abilityArg = english ? "ability" : "능력";
            String playerArg = english ? "player" : "플레이어";
            LiteralArgumentBuilder<CommandSourceStack> node = Commands.literal(root)
                    .requires(registrar.play())
                    .executes(ctx -> help(ctx.getSource().getSender()));
            for (String name : List.of("목록", "list")) {
                node.then(Commands.literal(name).executes(ctx -> list(ctx.getSource().getSender())));
            }
            for (String name : List.of("정보", "info")) {
                node.then(Commands.literal(name)
                        .executes(ctx -> usage(ctx.getSource().getSender(), "command.usage-info"))
                        .then(Commands.argument(abilityArg, StringArgumentType.greedyString())
                                .suggests(this::suggestAbilities)
                                .executes(ctx -> info(ctx.getSource().getSender(),
                                        StringArgumentType.getString(ctx, abilityArg)))));
            }
            for (String name : List.of("지정", "assign")) {
                node.then(Commands.literal(name).requires(registrar.admin())
                        .executes(ctx -> usage(ctx.getSource().getSender(), "command.usage-assign"))
                        .then(Commands.argument(playerArg, StringArgumentType.word())
                                .suggests(this::suggestPlayers)
                                .executes(ctx -> usage(ctx.getSource().getSender(), "command.usage-assign"))
                                .then(Commands.argument(abilityArg, StringArgumentType.greedyString())
                                        .suggests(this::suggestAbilities)
                                        .executes(ctx -> assign(ctx.getSource().getSender(),
                                                StringArgumentType.getString(ctx, playerArg),
                                                StringArgumentType.getString(ctx, abilityArg))))));
            }
            for (String name : List.of("선택방식", "mode")) {
                LiteralArgumentBuilder<CommandSourceStack> mode = Commands.literal(name).requires(registrar.admin())
                        .executes(ctx -> showMode(ctx.getSource().getSender()));
                for (SelectionMode value : SelectionMode.values()) {
                    for (String literal : modeLiterals(value)) {
                        mode.then(Commands.literal(literal)
                                .executes(ctx -> setMode(ctx.getSource().getSender(), value)));
                    }
                }
                node.then(mode);
            }
            for (String name : List.of("리롤", "reroll")) {
                node.then(Commands.literal(name).executes(ctx -> reroll(ctx.getSource().getSender())));
            }
            registrar.register(node);
        }
    }

    private static List<String> modeLiterals(SelectionMode mode) {
        return switch (mode) {
            case RANDOM -> List.of("RANDOM", "random", "무작위", "랜덤");
            case CHOICE -> List.of("CHOICE", "choice", "선택");
        };
    }

    // ------------------------------------------------------------------ suggestions

    private CompletableFuture<Suggestions> suggestAbilities(CommandContext<CommandSourceStack> ctx,
                                                            SuggestionsBuilder builder) {
        String remaining = builder.getRemainingLowerCase().replace(" ", "");
        for (AbilityRegistry.Entry entry : game.registry().all()) {
            AbilityInfo info = entry.info();
            if (info.name().replace(" ", "").startsWith(remaining)) {
                builder.suggest(info.name());
            }
            if (info.id().startsWith(remaining)) {
                builder.suggest(info.id());
            }
        }
        return builder.buildFuture();
    }

    private CompletableFuture<Suggestions> suggestPlayers(CommandContext<CommandSourceStack> ctx,
                                                          SuggestionsBuilder builder) {
        String remaining = builder.getRemainingLowerCase();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getName().toLowerCase(Locale.ROOT).startsWith(remaining)) {
                builder.suggest(player.getName());
            }
        }
        return builder.buildFuture();
    }

    // ------------------------------------------------------------------ handlers

    private int help(CommandSender sender) {
        messages().lines("command.help").forEach(sender::sendMessage);
        if (sender.hasPermission(Permissions.ADMIN)) {
            messages().lines("command.help-admin").forEach(sender::sendMessage);
        }
        return Command.SINGLE_SUCCESS;
    }

    private int usage(CommandSender sender, String key) {
        messages().send(sender, key);
        return 0;
    }

    private int list(CommandSender sender) {
        AbilityConfig.Snapshot snapshot = game.abilities().snapshot();
        messages().sendPlain(sender, "command.list-header",
                Messages.tag("count", game.registry().all().size()),
                Messages.tag("enabled", snapshot.enabledIds().size()));
        for (AbilityRegistry.Entry entry : game.registry().all()) {
            AbilityInfo info = entry.info();
            AbilityParams params = snapshot.params(info);
            Component name = game.texts().nameWithCard(info, params)
                    .clickEvent(ClickEvent.runCommand("/게임 능력 정보 " + info.id()));
            sender.sendMessage(messages().get("command.list-entry",
                    Messages.tag("ability", name),
                    Messages.tag("id", info.id()),
                    Messages.tag("stars", AbilityTexts.stars(info.difficulty())),
                    Messages.tag("state", messages().get(snapshot.isEnabled(info.id())
                            ? "command.state-on" : "command.state-off"))));
        }
        messages().sendPlain(sender, "command.list-footer");
        return Command.SINGLE_SUCCESS;
    }

    private int info(CommandSender sender, String input) {
        AbilityInfo info = game.findAbility(input);
        if (info == null) {
            messages().send(sender, "command.unknown-ability", Messages.tag("input", input));
            return 0;
        }
        AbilityConfig.Snapshot snapshot = game.abilities().snapshot();
        sender.sendMessage(messages().get("command.info-header",
                Messages.tag("ability", AbilityTexts.boldName(info)),
                Messages.tag("id", info.id()),
                Messages.tag("stars", AbilityTexts.stars(info.difficulty())),
                Messages.tag("state", messages().get(snapshot.isEnabled(info.id())
                        ? "command.state-on" : "command.state-off"))));
        for (Component line : game.texts().lore(info, snapshot.params(info), true)) {
            sender.sendMessage(messages().get("command.info-line", Messages.tag("line", line)));
        }
        return Command.SINGLE_SUCCESS;
    }

    private int assign(CommandSender sender, String playerName, String input) {
        Player target = Bukkit.getPlayerExact(playerName);
        if (target == null) {
            messages().send(sender, "command.player-not-found", Messages.tag("player", playerName));
            return 0;
        }
        AbilityInfo info = game.findAbility(input);
        if (info == null) {
            messages().send(sender, "command.unknown-ability", Messages.tag("input", input));
            return 0;
        }
        if (!game.abilities().snapshot().isEnabled(info.id())) {
            messages().send(sender, "command.assign-disabled", Messages.tag("ability", AbilityTexts.name(info)));
            return 0;
        }
        SuperpowerMatch match = game.currentMatch();
        SuperpowerMatch.AssignResult result = match == null ? SuperpowerMatch.AssignResult.NOT_STARTED
                : match.assign(target, info.id());
        String key = switch (result) {
            case APPLIED -> "command.assign-applied";
            case NOT_STARTED, NOT_PARTICIPANT -> "command.assign-next";
            case TOO_LATE -> "command.assign-too-late";
        };
        if (result != SuperpowerMatch.AssignResult.APPLIED) {
            game.queueAssignment(target.getUniqueId(), info.id());
        }
        messages().send(sender, key, Messages.tag("player", target.getName()),
                Messages.tag("ability", AbilityTexts.name(info)));
        return Command.SINGLE_SUCCESS;
    }

    private int showMode(CommandSender sender) {
        messages().send(sender, "command.mode-current",
                Messages.tag("mode", game.selectionMode().name()),
                Messages.tag("name", game.selectionMode().koreanName()));
        return Command.SINGLE_SUCCESS;
    }

    private int setMode(CommandSender sender, SelectionMode mode) {
        game.setSelectionMode(mode);
        messages().send(sender, "command.mode-set", Messages.tag("mode", mode.name()),
                Messages.tag("name", mode.koreanName()));
        if (game.hub().games().session() != null && game.hub().games().session().state() != GameState.COUNTDOWN) {
            messages().send(sender, "command.applies-next-match");
        }
        return Command.SINGLE_SUCCESS;
    }

    private int reroll(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            messages().send(sender, "command.player-only");
            return 0;
        }
        SuperpowerMatch match = game.currentMatch();
        if (match == null) {
            messages().send(sender, "reroll.no-match");
            return 0;
        }
        SuperpowerMatch.RerollResult result = match.reroll(player);
        match.reportReroll(player, result);
        return result == SuperpowerMatch.RerollResult.OK ? Command.SINGLE_SUCCESS : 0;
    }
}
