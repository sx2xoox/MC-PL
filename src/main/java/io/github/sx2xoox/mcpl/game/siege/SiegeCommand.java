package io.github.sx2xoox.mcpl.game.siege;

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
import io.github.sx2xoox.mcpl.core.command.Permissions;
import io.github.sx2xoox.mcpl.core.ui.Messages;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityRegistry;
import io.github.sx2xoox.mcpl.game.superpower.ui.AbilityTexts;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /게임 공성전} ({@code siege}): status and reroll for everyone, the ability pool for admins
 * ({@code 능력풀 추가|제거|목록|초기화 [능력]}).
 */
final class SiegeCommand {

    private final SiegeGame game;

    SiegeCommand(SiegeGame game) {
        this.game = game;
    }

    private Messages messages() {
        return game.messages();
    }

    void register(GameCommandRegistrar registrar) {
        for (String root : List.of("공성전", "siege")) {
            boolean english = root.equals("siege");
            String abilityArg = english ? "ability" : "능력";
            LiteralArgumentBuilder<CommandSourceStack> node = Commands.literal(root)
                    .requires(registrar.play())
                    .executes(ctx -> help(ctx.getSource().getSender()));
            for (String name : List.of("리롤", "reroll")) {
                node.then(Commands.literal(name).executes(ctx -> reroll(ctx.getSource().getSender())));
            }
            for (String name : List.of("점수", "score")) {
                node.then(Commands.literal(name).executes(ctx -> status(ctx.getSource().getSender())));
            }
            for (String name : List.of("능력풀", "pool")) {
                LiteralArgumentBuilder<CommandSourceStack> pool = Commands.literal(name).requires(registrar.admin())
                        .executes(ctx -> poolList(ctx.getSource().getSender()));
                for (String add : List.of("추가", "add")) {
                    pool.then(Commands.literal(add)
                            .executes(ctx -> usage(ctx.getSource().getSender()))
                            .then(Commands.argument(abilityArg, StringArgumentType.greedyString())
                                    .suggests(this::suggestMissing)
                                    .executes(ctx -> poolAdd(ctx.getSource().getSender(),
                                            StringArgumentType.getString(ctx, abilityArg)))));
                }
                for (String remove : List.of("제거", "remove")) {
                    pool.then(Commands.literal(remove)
                            .executes(ctx -> usage(ctx.getSource().getSender()))
                            .then(Commands.argument(abilityArg, StringArgumentType.greedyString())
                                    .suggests(this::suggestInPool)
                                    .executes(ctx -> poolRemove(ctx.getSource().getSender(),
                                            StringArgumentType.getString(ctx, abilityArg)))));
                }
                for (String list : List.of("목록", "list")) {
                    pool.then(Commands.literal(list).executes(ctx -> poolList(ctx.getSource().getSender())));
                }
                for (String reset : List.of("초기화", "reset")) {
                    pool.then(Commands.literal(reset).executes(ctx -> poolReset(ctx.getSource().getSender())));
                }
                node.then(pool);
            }
            registrar.register(node);
        }
    }

    // ------------------------------------------------------------------ suggestions

    private CompletableFuture<Suggestions> suggestMissing(CommandContext<CommandSourceStack> ctx,
                                                         SuggestionsBuilder builder) {
        List<String> pool = game.abilityPool();
        String remaining = builder.getRemainingLowerCase().replace(" ", "");
        for (AbilityRegistry.Entry entry : game.superpower().registry().all()) {
            AbilityInfo info = entry.info();
            if (pool.contains(info.id())) {
                continue;
            }
            suggest(builder, info, remaining);
        }
        return builder.buildFuture();
    }

    private CompletableFuture<Suggestions> suggestInPool(CommandContext<CommandSourceStack> ctx,
                                                        SuggestionsBuilder builder) {
        String remaining = builder.getRemainingLowerCase().replace(" ", "");
        for (String id : game.abilityPool()) {
            AbilityRegistry.Entry entry = game.superpower().registry().get(id);
            if (entry != null) {
                suggest(builder, entry.info(), remaining);
            } else if (id.startsWith(remaining)) {
                builder.suggest(id);
            }
        }
        return builder.buildFuture();
    }

    private static void suggest(SuggestionsBuilder builder, AbilityInfo info, String remaining) {
        if (info.name().replace(" ", "").startsWith(remaining)) {
            builder.suggest(info.name());
        }
        if (info.id().startsWith(remaining)) {
            builder.suggest(info.id());
        }
    }

    // ------------------------------------------------------------------ handlers

    private int help(CommandSender sender) {
        messages().lines("command.help").forEach(sender::sendMessage);
        if (sender.hasPermission(Permissions.ADMIN)) {
            messages().lines("command.help-admin").forEach(sender::sendMessage);
        }
        return Command.SINGLE_SUCCESS;
    }

    private int usage(CommandSender sender) {
        messages().send(sender, "command.usage-pool");
        return 0;
    }

    private int reroll(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            messages().send(sender, "command.player-only");
            return 0;
        }
        SiegeMatch match = game.currentMatch();
        if (match == null) {
            messages().send(sender, "command.no-match");
            return 0;
        }
        SiegeMatch.RerollResult result = match.reroll(player);
        match.reportReroll(player, result);
        return result == SiegeMatch.RerollResult.OK ? Command.SINGLE_SUCCESS : 0;
    }

    private int status(CommandSender sender) {
        SiegeMatch match = game.currentMatch();
        if (match == null) {
            messages().send(sender, "command.no-match");
            return 0;
        }
        SiegeScore score = match.score();
        messages().send(sender, "command.status", Messages.tag("round", match.round()),
                Messages.tag("team_a", match.teams().get(0).displayName()),
                Messages.tag("team_b", match.teams().get(1).displayName()),
                Messages.tag("a", score.points(0)), Messages.tag("b", score.points(1)),
                Messages.tag("rounds_a", score.roundWins(0)), Messages.tag("rounds_b", score.roundWins(1)));
        return Command.SINGLE_SUCCESS;
    }

    private int poolList(CommandSender sender) {
        List<String> pool = game.abilityPool();
        if (pool.isEmpty()) {
            messages().send(sender, "pool.list-empty");
            return Command.SINGLE_SUCCESS;
        }
        Component line = Component.empty();
        boolean first = true;
        for (String id : pool) {
            AbilityRegistry.Entry entry = game.superpower().registry().get(id);
            Component name = entry == null ? messages().get("pool.unknown-entry", Messages.tag("id", id))
                    : AbilityTexts.name(entry.info());
            line = first ? name : line.append(Component.text(", ")).append(name);
            first = false;
        }
        messages().send(sender, "pool.list", Messages.tag("count", pool.size()), Messages.tag("abilities", line));
        return Command.SINGLE_SUCCESS;
    }

    private int poolAdd(CommandSender sender, String input) {
        AbilityInfo info = game.superpower().findAbility(input);
        if (info == null) {
            messages().send(sender, "pool.unknown", Messages.tag("input", input));
            return 0;
        }
        if (!game.hub().settings().canSave(sender)) {
            return 0;
        }
        List<String> pool = new ArrayList<>(game.abilityPool());
        if (pool.contains(info.id())) {
            messages().send(sender, "pool.already", Messages.tag("ability", AbilityTexts.name(info)));
            return 0;
        }
        pool.add(info.id());
        game.abilityPool(pool);
        messages().send(sender, "pool.added", Messages.tag("ability", AbilityTexts.name(info)),
                Messages.tag("count", pool.size()));
        if (!game.superpower().abilities().snapshot().isEnabled(info.id())) {
            messages().send(sender, "pool.disabled-warning", Messages.tag("ability", AbilityTexts.name(info)));
        }
        return Command.SINGLE_SUCCESS;
    }

    private int poolRemove(CommandSender sender, String input) {
        List<String> pool = new ArrayList<>(game.abilityPool());
        AbilityInfo info = game.superpower().findAbility(input);
        String id = info != null ? info.id() : input.trim().toLowerCase(java.util.Locale.ROOT);
        if (!pool.contains(id)) {
            messages().send(sender, "pool.not-in-pool", Messages.tag("input", input));
            return 0;
        }
        if (!game.hub().settings().canSave(sender)) {
            return 0;
        }
        pool.remove(id);
        game.abilityPool(pool);
        messages().send(sender, "pool.removed", Messages.tag("ability", info != null ? AbilityTexts.name(info)
                : Component.text(id)), Messages.tag("count", pool.size()));
        return Command.SINGLE_SUCCESS;
    }

    private int poolReset(CommandSender sender) {
        if (!game.hub().settings().canSave(sender)) {
            return 0;
        }
        game.abilityPool(SiegeSettings.DEFAULT_POOL);
        messages().send(sender, "pool.reset", Messages.tag("count", SiegeSettings.DEFAULT_POOL.size()));
        return Command.SINGLE_SUCCESS;
    }
}
