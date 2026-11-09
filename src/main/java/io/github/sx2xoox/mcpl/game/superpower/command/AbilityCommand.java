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
import io.github.sx2xoox.mcpl.core.command.MatchCommands;
import io.github.sx2xoox.mcpl.core.command.Permissions;
import io.github.sx2xoox.mcpl.core.config.Setting;
import io.github.sx2xoox.mcpl.core.ui.Messages;
import io.github.sx2xoox.mcpl.core.ui.SettingsEditor;
import io.github.sx2xoox.mcpl.game.superpower.SelectionMode;
import io.github.sx2xoox.mcpl.game.superpower.SuperpowerGame;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityParams;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityRegistry;
import io.github.sx2xoox.mcpl.game.superpower.config.AbilityConfig;
import io.github.sx2xoox.mcpl.game.superpower.runtime.Participant;
import io.github.sx2xoox.mcpl.game.superpower.runtime.SuperpowerMatch;
import io.github.sx2xoox.mcpl.game.superpower.ui.AbilityTexts;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * The 초능력전쟁 commands (English twins in brackets):
 *
 * <pre>
 * /게임 시작 초능력전쟁 (superpower)            admin: a match for everyone
 * /게임 시작 초능력전쟁 연습 [능력 | 모두]        ability practice (see {@link PracticeCommand})
 * /게임 연습 바꾸기|충전|쿨타임                   practice tools (with the hub's 허수아비)
 * /게임 리롤 · 내정보 · 팀                       in a match ({@link MatchCommands})
 * /게임 능력 (ability)                          reopen the CHOICE menu while a pick waits, else help
 * /게임 능력 목록|list                           all abilities (hover: skills, click: info)
 * /게임 능력 정보|info &lt;능력&gt;                    one ability (id or Korean name)
 * /게임 능력 선택|pick                           reopen the CHOICE menu (selection phase, not picked yet)
 * /게임 능력 지정|assign &lt;플레이어&gt; &lt;능력&gt;        admin: for the next match or the running reveal/prep phase
 * /게임 능력 선택방식|mode [RANDOM|CHOICE]        admin: selection mode (saved to config.yml)
 * </pre>
 *
 * The old forms ({@code /게임 능력 리롤 · 팀 · 연습 · 변경 · 충전 · 쿨타임 · 허수아비}) still work through
 * {@link GameCommandRegistrar#moved}.
 */
public final class AbilityCommand {

    private final SuperpowerGame game;
    private final PracticeCommand practice;

    public AbilityCommand(SuperpowerGame game) {
        this.game = game;
        this.practice = new PracticeCommand(game);
    }

    private Messages messages() {
        return game.messages();
    }

    public void register(GameCommandRegistrar registrar) {
        MatchCommands commands = new SuperpowerMatchCommands();
        registrar.matchCommands(game, commands);
        registrar.matchCommands(game.practice(), commands);
        registrar.startPath(game, "초능력전쟁");
        registrar.startPath(game.practice(), "초능력전쟁 연습 모두");
        registrar.moved("능력|ability 리롤|reroll", "리롤");
        registrar.moved("능력|ability 팀|team", "팀");
        registrar.moved("능력|ability 연습|practice", "시작 초능력전쟁 연습");
        registrar.moved("능력|ability 변경|switch", "연습 바꾸기");
        registrar.moved("능력|ability 충전|charge", "연습 충전");
        registrar.moved("능력|ability 쿨타임|cooldown", "연습 쿨타임");
        registrar.moved("능력|ability 허수아비|dummies", "연습 허수아비");
        for (String name : List.of("초능력전쟁", "superpower")) {
            boolean english = name.equals("superpower");
            LiteralArgumentBuilder<CommandSourceStack> start = Commands.literal(name)
                    .requires(registrar.play())
                    .executes(ctx -> registrar.startMatch(ctx.getSource().getSender(), game));
            practice.addStartOptions(start, registrar, english, this::suggestAbilities);
            registrar.registerStart(start);
            practice.registerTools(registrar, english, this::suggestAbilities);
        }
        for (String root : List.of("능력", "ability")) {
            boolean english = root.equals("ability");
            String abilityArg = english ? "ability" : "능력";
            String playerArg = english ? "player" : "플레이어";
            LiteralArgumentBuilder<CommandSourceStack> node = Commands.literal(root)
                    .requires(registrar.play())
                    .executes(ctx -> open(ctx.getSource().getSender()));
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
            for (String name : List.of("선택", "pick")) {
                node.then(Commands.literal(name).executes(ctx -> pick(ctx.getSource().getSender())));
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

    /** {@code /게임 능력}: reopens the CHOICE menu while the player still has to pick, else the help. */
    private int open(CommandSender sender) {
        SuperpowerMatch match = game.currentMatch();
        if (sender instanceof Player player && match != null && match.reopenMenu(player)) {
            return Command.SINGLE_SUCCESS;
        }
        return help(sender);
    }

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

    /** The same change as {@code /게임 설정 선택방식}: saved, logged, shown in open settings menus. */
    private int setMode(CommandSender sender, SelectionMode mode) {
        Setting.Choice setting = game.modeSetting();
        Setting.Choice.Option option = setting.options().stream()
                .filter(candidate -> candidate.value().equals(mode.name()))
                .findFirst()
                .orElseThrow();
        boolean accepted = game.hub().settings().change(sender, setting, () -> setting.set(option),
                SettingsEditor.Origin.COMMAND);
        return accepted ? Command.SINGLE_SUCCESS : 0;
    }

    private int pick(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            messages().send(sender, "command.player-only");
            return 0;
        }
        SuperpowerMatch match = game.currentMatch();
        if (match == null) {
            messages().send(sender, "reroll.no-match");
            return 0;
        }
        if (!match.reopenMenu(player)) {
            messages().send(sender, "choice.nothing-to-pick");
            return 0;
        }
        return Command.SINGLE_SUCCESS;
    }

    /** {@code /게임 리롤 · 내정보 · 팀 · 연습 허수아비} in 초능력전쟁 and 능력 연습. */
    private final class SuperpowerMatchCommands implements MatchCommands {

        @Override
        public int reroll(Player player) {
            SuperpowerMatch match = game.currentMatch();
            if (match == null) {
                messages().send(player, "reroll.no-match");
                return 0;
            }
            SuperpowerMatch.RerollResult result = match.reroll(player);
            match.reportReroll(player, result);
            return result == SuperpowerMatch.RerollResult.OK ? Command.SINGLE_SUCCESS : 0;
        }

        @Override
        public int myInfo(Player player) {
            SuperpowerMatch match = game.currentMatch();
            Participant participant = match == null ? null : match.participant(player.getUniqueId());
            String abilityId = participant == null ? null : participant.abilityId();
            if (abilityId == null) {
                messages().send(player, match != null && match.reopenMenu(player) ? "command.me-picking"
                        : "command.me-none");
                return 0;
            }
            int result = info(player, abilityId);
            if (participant.rerollsLeft() > 0) {
                messages().send(player, "command.me-rerolls", Messages.tag("count", participant.rerollsLeft()));
            }
            return result;
        }

        @Override
        public int team(Player player) {
            SuperpowerMatch match = game.currentMatch();
            if (match == null) {
                messages().send(player, "reroll.no-match");
                return 0;
            }
            String error = switch (match.showTeamAbilities(player)) {
                case OK -> null;
                case NOT_TEAM_MODE -> "command.team-not-team-mode";
                case DISABLED -> "command.team-disabled";
                case NO_TEAM -> "command.team-no-team";
                case ALONE -> "command.team-alone";
            };
            if (error != null) {
                messages().send(player, error);
                return 0;
            }
            return Command.SINGLE_SUCCESS;
        }

        @Override
        public int dummies(Player player, @Nullable Integer count) {
            return practice.dummies(player, count);
        }
    }
}
