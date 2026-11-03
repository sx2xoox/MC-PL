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
import io.github.sx2xoox.mcpl.core.command.MatchCommands;
import io.github.sx2xoox.mcpl.core.command.Permissions;
import io.github.sx2xoox.mcpl.core.game.MatchTeam;
import io.github.sx2xoox.mcpl.core.ui.Messages;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityRegistry;
import io.github.sx2xoox.mcpl.game.superpower.runtime.Participant;
import io.github.sx2xoox.mcpl.game.superpower.runtime.SuperpowerMatch;
import io.github.sx2xoox.mcpl.game.superpower.ui.AbilityTexts;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * The 공성전 commands: {@code /게임 시작 공성전} ({@code start siege}, admin), {@code /게임 리롤 · 점수 · 내정보 · 팀}
 * in a match ({@link MatchCommands}), and {@code /게임 공성전} ({@code siege}): help, the ability pool for admins
 * ({@code 능력풀 추가|제거|목록|초기화 [능력]}). The old {@code /게임 공성전 리롤 · 점수} still work.
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
        registrar.matchCommands(game, new SiegeMatchCommands());
        registrar.startPath(game, "공성전");
        registrar.moved("공성전|siege 리롤|reroll", "리롤");
        registrar.moved("공성전|siege 점수|score", "점수");
        for (String root : List.of("공성전", "siege")) {
            boolean english = root.equals("siege");
            String abilityArg = english ? "ability" : "능력";
            registrar.registerStart(Commands.literal(root)
                    .requires(registrar.admin())
                    .executes(ctx -> registrar.startMatch(ctx.getSource().getSender(), game)));
            LiteralArgumentBuilder<CommandSourceStack> node = Commands.literal(root)
                    .requires(registrar.play())
                    .executes(ctx -> help(ctx.getSource().getSender()));
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

    /** {@code /게임 리롤 · 점수 · 내정보 · 팀} in 공성전. */
    private final class SiegeMatchCommands implements MatchCommands {

        @Override
        public int reroll(Player player) {
            SiegeMatch match = game.currentMatch();
            if (match == null) {
                messages().send(player, "command.no-match");
                return 0;
            }
            SiegeMatch.RerollResult result = match.reroll(player);
            match.reportReroll(player, result);
            return result == SiegeMatch.RerollResult.OK ? Command.SINGLE_SUCCESS : 0;
        }

        @Override
        public int score(CommandSender sender) {
            SiegeMatch match = game.currentMatch();
            if (match == null || match.teams().size() < 2) {
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

        @Override
        public int myInfo(Player player) {
            SiegeMatch match = game.currentMatch();
            SuperpowerMatch abilities = match == null ? null : hostedAbilities(match);
            Participant participant = abilities == null ? null : abilities.participant(player.getUniqueId());
            AbilityRegistry.Entry entry = participant == null || participant.abilityId() == null ? null
                    : game.superpower().registry().get(participant.abilityId());
            if (match == null || entry == null) {
                messages().send(player, "command.me-none");
                return 0;
            }
            messages().send(player, "command.me", Messages.tag("ability", AbilityTexts.name(entry.info())),
                    Messages.tag("level", match.level(player.getUniqueId())),
                    Messages.tag("xp", match.experience(player.getUniqueId())),
                    Messages.tag("rerolls", match.rerollsLeft(player)));
            return Command.SINGLE_SUCCESS;
        }

        @Override
        public int team(Player player) {
            SiegeMatch match = game.currentMatch();
            SuperpowerMatch abilities = match == null ? null : hostedAbilities(match);
            if (abilities != null && abilities.showTeamAbilities(player) == SuperpowerMatch.TeamAbilitiesResult.OK) {
                return Command.SINGLE_SUCCESS;
            }
            int index = match == null ? -1 : match.teamIndex(player.getUniqueId());
            if (index < 0) {
                messages().send(player, "command.team-none");
                return 0;
            }
            MatchTeam team = match.teams().get(index);
            Component names = Component.empty();
            boolean first = true;
            for (java.util.UUID member : team.members()) {
                Component name = match.session().name(member);
                names = first ? name : names.append(Component.text(", ")).append(name);
                first = false;
            }
            messages().send(player, "command.team", Messages.tag("team", team.displayName()),
                    Messages.tag("members", names));
            return Command.SINGLE_SUCCESS;
        }

        private @Nullable SuperpowerMatch hostedAbilities(SiegeMatch match) {
            try {
                return match.abilities();
            } catch (IllegalStateException notStarted) {
                return null;
            }
        }
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
