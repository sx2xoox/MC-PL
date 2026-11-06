package io.github.sx2xoox.mcpl.core.command;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.github.sx2xoox.mcpl.core.Hub;
import io.github.sx2xoox.mcpl.core.game.GameSession;
import io.github.sx2xoox.mcpl.core.game.TeamAssigner;
import io.github.sx2xoox.mcpl.core.game.TeamPresets;
import io.github.sx2xoox.mcpl.core.ui.Messages;
import io.github.sx2xoox.mcpl.core.ui.TeamMenu;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /게임 팀} (English twin {@code /mcpl team}): without a word the own team of the running match (as before),
 * below it the team picks every team mode uses ({@link TeamPresets}):
 *
 * <pre>
 * /게임 팀 선택|choose [팀]            pick the own team (no team: the team menu); {@code match.team-choice}
 * /게임 팀 해제|unset [플레이어]       back to automatic placement (another player: admin)
 * /게임 팀 목록|list                  the picks per team
 * /게임 팀 지정|assign &lt;플레이어&gt; &lt;팀&gt;  (admin) pick a team for a player
 * /게임 팀 섞기|shuffle               (admin) split the waiting players at random
 * /게임 팀 초기화|reset               (admin) remove every pick
 * </pre>
 * Teams are named by color ({@code 빨강}, {@code 파랑}, ... or {@code red}); only the teams the coming match can use
 * are accepted ({@link TeamPresets#available()}: 공성전 빨강/파랑).
 */
public final class TeamCommand {

    private static final String TEAM_ARG = "팀";
    private static final String PLAYER_ARG = "플레이어";

    private final Hub hub;

    public TeamCommand(Hub hub) {
        this.hub = hub;
    }

    private Messages messages() {
        return hub.messages();
    }

    private TeamPresets presets() {
        return hub.teamPresets();
    }

    /** The {@code literal} node ({@code 팀} or {@code team}); {@code show} runs it without a word. */
    public LiteralArgumentBuilder<CommandSourceStack> build(String literal, Command<CommandSourceStack> show,
                                                           Predicate<CommandSourceStack> admin) {
        LiteralArgumentBuilder<CommandSourceStack> node = Commands.literal(literal).executes(show);
        for (String name : List.of("선택", "choose")) {
            node.then(Commands.literal(name)
                    .executes(ctx -> openMenu(ctx.getSource().getSender()))
                    .then(Commands.argument(TEAM_ARG, StringArgumentType.greedyString())
                            .suggests(this::suggestTeams)
                            .executes(ctx -> choose(ctx.getSource().getSender(),
                                    StringArgumentType.getString(ctx, TEAM_ARG)))));
        }
        for (String name : List.of("해제", "unset")) {
            node.then(Commands.literal(name)
                    .executes(ctx -> unsetSelf(ctx.getSource().getSender()))
                    .then(Commands.argument(PLAYER_ARG, StringArgumentType.word()).requires(admin)
                            .suggests(this::suggestPlayers)
                            .executes(ctx -> unset(ctx.getSource().getSender(),
                                    StringArgumentType.getString(ctx, PLAYER_ARG)))));
        }
        for (String name : List.of("목록", "list")) {
            node.then(Commands.literal(name).executes(ctx -> list(ctx.getSource().getSender())));
        }
        for (String name : List.of("지정", "assign")) {
            node.then(Commands.literal(name).requires(admin)
                    .then(Commands.argument(PLAYER_ARG, StringArgumentType.word())
                            .suggests(this::suggestPlayers)
                            .then(Commands.argument(TEAM_ARG, StringArgumentType.greedyString())
                                    .suggests(this::suggestTeams)
                                    .executes(ctx -> assign(ctx.getSource().getSender(),
                                            StringArgumentType.getString(ctx, PLAYER_ARG),
                                            StringArgumentType.getString(ctx, TEAM_ARG))))));
        }
        for (String name : List.of("섞기", "shuffle")) {
            node.then(Commands.literal(name).requires(admin).executes(ctx -> shuffle(ctx.getSource().getSender())));
        }
        for (String name : List.of("초기화", "reset")) {
            node.then(Commands.literal(name).requires(admin).executes(ctx -> reset(ctx.getSource().getSender())));
        }
        return node;
    }

    // ------------------------------------------------------------------ players

    private int openMenu(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            messages().send(sender, "error.player-only");
            return 0;
        }
        if (!hub.config().teamChoice()) {
            messages().send(sender, "team.choice-off");
            return 0;
        }
        if (!TeamMenu.open(hub, player)) {
            messages().send(sender, "team.closed");
            return 0;
        }
        return Command.SINGLE_SUCCESS;
    }

    private int choose(CommandSender sender, String input) {
        if (!(sender instanceof Player player)) {
            messages().send(sender, "error.player-only");
            return 0;
        }
        if (!hub.config().teamChoice()) {
            messages().send(sender, "team.choice-off");
            return 0;
        }
        TeamAssigner.Entry team = team(sender, input);
        if (team == null) {
            return 0;
        }
        presets().set(player.getUniqueId(), team.id());
        GameSession session = hub.games().session();
        boolean playing = session != null && session.isRunning() && session.teamOf(player) != null;
        messages().send(sender, playing ? "team.chosen-next" : "team.chosen",
                Messages.tag("team", team.displayName()));
        TeamMenu.refreshAll();
        return Command.SINGLE_SUCCESS;
    }

    private int unsetSelf(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            messages().send(sender, "error.player-only");
            return 0;
        }
        if (!presets().remove(player.getUniqueId())) {
            messages().send(sender, "team.not-set");
            return 0;
        }
        messages().send(sender, "team.unset-self");
        TeamMenu.refreshAll();
        return Command.SINGLE_SUCCESS;
    }

    private int list(CommandSender sender) {
        Map<UUID, String> picks = presets().all();
        if (picks.isEmpty()) {
            messages().send(sender, "team.list-empty");
            return Command.SINGLE_SUCCESS;
        }
        messages().send(sender, "team.list-header");
        Set<TeamAssigner.Entry> shown = new LinkedHashSet<>(presets().available());
        for (String id : picks.values()) {
            TeamAssigner.Entry entry = TeamAssigner.entry(id);
            if (entry != null) {
                shown.add(entry);
            }
        }
        List<TeamAssigner.Entry> ordered = new ArrayList<>(shown);
        ordered.sort(java.util.Comparator.comparingInt(TeamAssigner.palette()::indexOf));
        for (TeamAssigner.Entry team : ordered) {
            List<UUID> members = presets().members(team.id());
            List<Component> names = new ArrayList<>();
            for (UUID member : members) {
                names.add(Component.text(nameOf(member), NamedTextColor.WHITE));
            }
            messages().send(sender, "team.list-entry", Messages.tag("team", team.displayName()),
                    Messages.tag("count", members.size()),
                    Messages.tag("members", Component.join(JoinConfiguration.separator(
                            Component.text(", ", NamedTextColor.GRAY)), names)));
        }
        return Command.SINGLE_SUCCESS;
    }

    // ------------------------------------------------------------------ admins

    private int assign(CommandSender sender, String playerName, String input) {
        Player target = Bukkit.getPlayerExact(playerName);
        if (target == null) {
            messages().send(sender, "team.unknown-player", Messages.tag("input", playerName));
            return 0;
        }
        TeamAssigner.Entry team = team(sender, input);
        if (team == null) {
            return 0;
        }
        presets().set(target.getUniqueId(), team.id());
        messages().send(sender, "team.assigned", Messages.tag("player", target.getName()),
                Messages.tag("team", team.displayName()));
        if (target != sender) {
            messages().send(target, "team.assigned-you", Messages.tag("team", team.displayName()));
        }
        TeamMenu.refreshAll();
        return Command.SINGLE_SUCCESS;
    }

    private int unset(CommandSender sender, String playerName) {
        UUID id = null;
        String shownName = playerName;
        Player online = Bukkit.getPlayerExact(playerName);
        if (online != null) {
            id = online.getUniqueId();
            shownName = online.getName();
        } else {
            for (UUID picked : presets().all().keySet()) {
                if (nameOf(picked).equalsIgnoreCase(playerName)) {
                    id = picked;
                    shownName = nameOf(picked);
                }
            }
        }
        if (id == null || !presets().remove(id)) {
            messages().send(sender, "team.not-set");
            return 0;
        }
        messages().send(sender, "team.unset", Messages.tag("player", shownName));
        TeamMenu.refreshAll();
        return Command.SINGLE_SUCCESS;
    }

    private int shuffle(CommandSender sender) {
        List<Player> players = presets().waitingPlayers();
        if (players.isEmpty()) {
            messages().send(sender, "team.no-players");
            return 0;
        }
        if (!presets().shuffle(players, new java.util.Random())) {
            messages().send(sender, "team.closed");
            return 0;
        }
        messages().send(sender, "team.shuffled", Messages.tag("count", players.size()));
        for (Player player : players) {
            TeamAssigner.Entry team = TeamAssigner.entry(String.valueOf(presets().get(player.getUniqueId())));
            if (team != null && player != sender) {
                messages().send(player, "team.assigned-you", Messages.tag("team", team.displayName()));
            }
        }
        TeamMenu.refreshAll();
        return Command.SINGLE_SUCCESS;
    }

    private int reset(CommandSender sender) {
        int count = presets().clear();
        messages().send(sender, "team.reset", Messages.tag("count", count));
        TeamMenu.refreshAll();
        return Command.SINGLE_SUCCESS;
    }

    // ------------------------------------------------------------------ helpers

    /** The team the input names if it can be picked now; otherwise tells the sender why and returns null. */
    private TeamAssigner.@org.jspecify.annotations.Nullable Entry team(CommandSender sender, String input) {
        List<TeamAssigner.Entry> available = presets().available();
        if (available.isEmpty()) {
            messages().send(sender, "team.closed");
            return null;
        }
        TeamAssigner.Entry team = TeamAssigner.parse(input);
        if (team == null || !available.contains(team)) {
            List<String> names = new ArrayList<>();
            for (TeamAssigner.Entry entry : available) {
                names.add(entry.shortName());
            }
            messages().send(sender, "team.unknown", Messages.tag("input", input),
                    Messages.tag("teams", String.join(", ", names)));
            return null;
        }
        return team;
    }

    private static String nameOf(UUID id) {
        Player online = Bukkit.getPlayer(id);
        if (online != null) {
            return online.getName();
        }
        OfflinePlayer offline = Bukkit.getOfflinePlayer(id);
        return offline.getName() == null ? id.toString().substring(0, 8) : offline.getName();
    }

    private CompletableFuture<Suggestions> suggestTeams(CommandContext<CommandSourceStack> ctx,
                                                        SuggestionsBuilder builder) {
        String remaining = builder.getRemainingLowerCase();
        for (TeamAssigner.Entry entry : presets().available()) {
            if (entry.shortName().startsWith(remaining)) {
                builder.suggest(entry.shortName());
            } else if (entry.id().startsWith(remaining)) {
                builder.suggest(entry.id());
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
}
