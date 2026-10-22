package io.github.sx2xoox.mcpl.core.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import java.util.logging.Level;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.github.sx2xoox.mcpl.core.Hub;
import io.github.sx2xoox.mcpl.core.config.HubConfig;
import io.github.sx2xoox.mcpl.core.config.ProtectionSettings;
import io.github.sx2xoox.mcpl.core.game.GameManager;
import io.github.sx2xoox.mcpl.core.game.GameSession;
import io.github.sx2xoox.mcpl.core.game.GameStartException;
import io.github.sx2xoox.mcpl.core.game.GameState;
import io.github.sx2xoox.mcpl.core.game.MiniGame;
import io.github.sx2xoox.mcpl.core.game.Rotation;
import io.github.sx2xoox.mcpl.core.ui.Format;
import io.github.sx2xoox.mcpl.core.ui.Messages;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * {@code /게임} (aliases {@code /mcpl}, {@code /mg}) built with Paper's Brigadier API. Every subcommand has a Korean
 * name and an English twin:
 *
 * <pre>
 * /게임 [도움말|help]
 * /게임 시작|start [모드]                 (admin)
 * /게임 중지|stop                         (admin)
 * /게임 목록|list
 * /게임 참가|join, /게임 관전|spectate
 * /게임 순환|rotation [켜기|on | 끄기|off | 목록|list]   (admin)
 * /게임 경기장|arena [설정|set [반경] | 초기화|reset]     (admin)
 * /게임 설정|settings [팀|team &lt;인원&gt;]             (admin)
 * /게임 리로드|reload                     (admin)
 * </pre>
 * plus whatever the registered mini-games add through {@link MiniGame#registerCommands}.
 */
public final class GameCommand {

    public static final String ROOT = "게임";
    public static final List<String> ALIASES = List.of("mcpl", "mg");

    private static final Predicate<CommandSourceStack> PLAY = source -> source.getSender().hasPermission(Permissions.PLAY);
    private static final Predicate<CommandSourceStack> ADMIN = source -> source.getSender().hasPermission(Permissions.ADMIN);

    private final Hub hub;

    public GameCommand(Hub hub) {
        this.hub = hub;
    }

    /**
     * Registers the command through the lifecycle API. The tree is rebuilt every time Paper (re)loads commands, so
     * mini-games registered during enable are included. Failures (e.g. in test environments) are logged, not thrown.
     */
    public void register() {
        try {
            hub.plugin().getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                    event.registrar().register(build(), "MCPL 미니게임 허브 (/게임 도움말)", ALIASES));
        } catch (RuntimeException | LinkageError e) {
            hub.plugin().getLogger().log(Level.WARNING, "Could not register the /" + ROOT + " command", e);
        }
    }

    /** Builds the full command tree (public for tests and tooling). */
    public LiteralCommandNode<CommandSourceStack> build() {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(ROOT)
                .requires(PLAY)
                .executes(ctx -> help(ctx.getSource().getSender()));

        for (String name : List.of("도움말", "help")) {
            root.then(Commands.literal(name).executes(ctx -> help(ctx.getSource().getSender())));
        }
        for (String name : List.of("시작", "start")) {
            String arg = name.equals("start") ? "mode" : "모드";
            root.then(Commands.literal(name).requires(ADMIN)
                    .executes(ctx -> start(ctx.getSource().getSender(), null))
                    .then(Commands.argument(arg, StringArgumentType.greedyString())
                            .suggests(this::suggestGames)
                            .executes(ctx -> start(ctx.getSource().getSender(), StringArgumentType.getString(ctx, arg)))));
        }
        for (String name : List.of("중지", "stop")) {
            root.then(Commands.literal(name).requires(ADMIN).executes(ctx -> stop(ctx.getSource().getSender())));
        }
        for (String name : List.of("목록", "list")) {
            root.then(Commands.literal(name).executes(ctx -> list(ctx.getSource().getSender())));
        }
        for (String name : List.of("참가", "join")) {
            root.then(Commands.literal(name).executes(ctx -> participate(ctx.getSource().getSender(), false)));
        }
        for (String name : List.of("관전", "spectate")) {
            root.then(Commands.literal(name).executes(ctx -> participate(ctx.getSource().getSender(), true)));
        }
        for (String name : List.of("순환", "rotation")) {
            LiteralArgumentBuilder<CommandSourceStack> rotation = Commands.literal(name).requires(ADMIN)
                    .executes(ctx -> rotationStatus(ctx.getSource().getSender()));
            for (String on : List.of("켜기", "on")) {
                rotation.then(Commands.literal(on).executes(ctx -> rotationToggle(ctx.getSource().getSender(), true)));
            }
            for (String off : List.of("끄기", "off")) {
                rotation.then(Commands.literal(off).executes(ctx -> rotationToggle(ctx.getSource().getSender(), false)));
            }
            for (String list : List.of("목록", "list")) {
                rotation.then(Commands.literal(list).executes(ctx -> rotationList(ctx.getSource().getSender())));
            }
            root.then(rotation);
        }
        for (String name : List.of("경기장", "arena")) {
            String radiusArg = name.equals("arena") ? "radius" : "반경";
            LiteralArgumentBuilder<CommandSourceStack> arena = Commands.literal(name).requires(ADMIN)
                    .executes(ctx -> arenaInfo(ctx.getSource().getSender()));
            for (String set : List.of("설정", "set")) {
                arena.then(Commands.literal(set)
                        .executes(ctx -> arenaSet(ctx.getSource(), null))
                        .then(Commands.argument(radiusArg, IntegerArgumentType.integer(HubConfig.MIN_RADIUS, HubConfig.MAX_RADIUS))
                                .executes(ctx -> arenaSet(ctx.getSource(), IntegerArgumentType.getInteger(ctx, radiusArg)))));
            }
            for (String reset : List.of("초기화", "reset")) {
                arena.then(Commands.literal(reset).executes(ctx -> arenaReset(ctx.getSource().getSender())));
            }
            root.then(arena);
        }
        for (String name : List.of("설정", "settings")) {
            String sizeArg = name.equals("settings") ? "size" : "인원";
            LiteralArgumentBuilder<CommandSourceStack> settings = Commands.literal(name).requires(ADMIN)
                    .executes(ctx -> settings(ctx.getSource().getSender()));
            for (String team : List.of("팀", "team")) {
                settings.then(Commands.literal(team)
                        .executes(ctx -> settings(ctx.getSource().getSender()))
                        .then(Commands.argument(sizeArg, IntegerArgumentType.integer(0, HubConfig.MAX_TEAM_SIZE))
                                .executes(ctx -> teamSize(ctx.getSource().getSender(), IntegerArgumentType.getInteger(ctx, sizeArg)))));
            }
            root.then(settings);
        }
        for (String name : List.of("리로드", "reload")) {
            root.then(Commands.literal(name).requires(ADMIN).executes(ctx -> reload(ctx.getSource().getSender())));
        }

        GameCommandRegistrar registrar = new GameCommandRegistrar() {
            @Override
            public Hub hub() {
                return hub;
            }

            @Override
            public void register(LiteralArgumentBuilder<CommandSourceStack> subcommand) {
                root.then(subcommand);
            }

            @Override
            public Predicate<CommandSourceStack> play() {
                return PLAY;
            }

            @Override
            public Predicate<CommandSourceStack> admin() {
                return ADMIN;
            }
        };
        for (MiniGame game : hub.games().games()) {
            try {
                game.registerCommands(registrar);
            } catch (RuntimeException e) {
                hub.plugin().getLogger().log(Level.WARNING, "Could not add the commands of " + game.id(), e);
            }
        }
        return root.build();
    }

    private CompletableFuture<Suggestions> suggestGames(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        String remaining = builder.getRemainingLowerCase();
        for (MiniGame game : hub.games().games()) {
            String korean = game.displayName().replace(" ", "");
            if (korean.toLowerCase(Locale.ROOT).startsWith(remaining)) {
                builder.suggest(korean);
            }
            if (game.id().startsWith(remaining)) {
                builder.suggest(game.id());
            }
        }
        return builder.buildFuture();
    }

    private Messages messages() {
        return hub.messages();
    }

    private GameManager games() {
        return hub.games();
    }

    // ------------------------------------------------------------------ handlers

    private int help(CommandSender sender) {
        messages().sendPlain(sender, "help.header");
        messages().lines("help.play").forEach(sender::sendMessage);
        if (sender.hasPermission(Permissions.ADMIN)) {
            messages().lines("help.admin").forEach(sender::sendMessage);
        }
        messages().sendPlain(sender, "help.footer");
        return Command.SINGLE_SUCCESS;
    }

    private int list(CommandSender sender) {
        if (games().games().isEmpty()) {
            messages().send(sender, "error.no-games");
            return 0;
        }
        GameSession session = games().session();
        MiniGame next = games().rotation().isEnabled() ? games().rotation().next() : null;
        messages().sendPlain(sender, "list.header", Messages.tag("count", games().games().size()));
        for (MiniGame game : games().games()) {
            Component status = Component.empty();
            if (session != null && session.game() == game) {
                status = messages().get("list.status-running");
            } else if (next == game) {
                status = messages().get("list.status-next");
            }
            messages().sendPlain(sender, "list.entry", Messages.tag("game", game.displayComponent()),
                    Messages.tag("id", game.id()), Messages.tag("min", game.minPlayers()),
                    Messages.tag("mode", messages().get(game.supportsTeams() ? "list.teams-yes" : "list.teams-no")),
                    Messages.tag("status", status));
            for (Component line : game.description()) {
                sender.sendMessage(messages().get("list.description", Messages.tag("line", line)));
            }
        }
        return Command.SINGLE_SUCCESS;
    }

    private int start(CommandSender sender, @Nullable String input) {
        MiniGame game = input == null ? games().defaultGame() : games().find(input);
        if (game == null) {
            if (input == null) {
                messages().send(sender, "error.choose-game");
            } else {
                messages().send(sender, "error.unknown-game", Messages.tag("input", input));
            }
            return 0;
        }
        try {
            games().start(game, sender);
            return Command.SINGLE_SUCCESS;
        } catch (GameStartException e) {
            sender.sendMessage(e.feedback());
            return 0;
        }
    }

    private int stop(CommandSender sender) {
        Component reason = messages().get("match.reason-admin", Messages.tag("player", sender.getName()));
        boolean onlyIntermission = games().session() == null && games().rotation().isWaiting();
        if (!games().stop(reason)) {
            messages().send(sender, "error.nothing-to-stop");
            return 0;
        }
        messages().send(sender, onlyIntermission ? "command.rotation-cancelled" : "command.stopped");
        return Command.SINGLE_SUCCESS;
    }

    private int participate(CommandSender sender, boolean spectate) {
        if (!(sender instanceof Player player)) {
            messages().send(sender, "error.player-only");
            return 0;
        }
        GameSession before = games().session();
        boolean wasAlive = before != null && before.isAlive(player);
        games().setPrefersSpectating(player, spectate);
        GameSession session = games().session();
        String key;
        if (session == null || session.state() == GameState.COUNTDOWN) {
            key = spectate ? "command.spectate" : "command.join";
        } else if (spectate) {
            key = session.isAlive(player) ? "command.spectate-next-match" : "command.spectate";
        } else if (session.isAlive(player)) {
            key = wasAlive ? "command.join-already" : "command.join-now";
        } else {
            key = "command.join-next-match";
        }
        messages().send(sender, key);
        return Command.SINGLE_SUCCESS;
    }

    private int rotationStatus(CommandSender sender) {
        Rotation rotation = games().rotation();
        MiniGame next = rotation.next();
        messages().send(sender, rotation.isEnabled() ? "command.rotation-status-on" : "command.rotation-status-off",
                Messages.tag("next", next == null ? messages().get("command.rotation-none") : next.displayComponent()),
                Messages.tag("seconds", hub.config().intermissionSeconds()));
        if (rotation.isWaiting()) {
            messages().send(sender, "command.rotation-waiting", Messages.tag("seconds", rotation.secondsLeft()));
        }
        return rotationList(sender);
    }

    /** True (and tells the sender) when config.yml has a syntax error, so a setting change could not be saved. */
    private boolean configLocked(CommandSender sender) {
        if (hub.config().isWritable()) {
            return false;
        }
        messages().send(sender, "error.config-broken");
        return true;
    }

    private int rotationToggle(CommandSender sender, boolean enabled) {
        if (configLocked(sender)) {
            return 0;
        }
        Rotation rotation = games().rotation();
        if (enabled && rotation.next() == null) {
            messages().send(sender, "error.rotation-empty");
            return 0;
        }
        rotation.setEnabled(enabled);
        messages().send(sender, enabled ? "command.rotation-on" : "command.rotation-off",
                Messages.tag("seconds", hub.config().intermissionSeconds()));
        return Command.SINGLE_SUCCESS;
    }

    private int rotationList(CommandSender sender) {
        Rotation rotation = games().rotation();
        List<String> ids = rotation.gameIds();
        if (ids.isEmpty()) {
            messages().send(sender, "command.rotation-list-empty");
            return Command.SINGLE_SUCCESS;
        }
        int nextIndex = rotation.nextIndex();
        List<Component> parts = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            String id = ids.get(i);
            MiniGame game = games().game(id);
            Component part = game == null ? Component.text(id + "(?)", NamedTextColor.DARK_GRAY) : game.displayComponent();
            if (game != null && i == nextIndex) {
                part = Component.text("▶", NamedTextColor.YELLOW).append(part);
            }
            parts.add(part);
        }
        Component joined = Component.join(JoinConfiguration.separator(Component.text(" → ", NamedTextColor.DARK_GRAY)), parts);
        messages().send(sender, "command.rotation-list", Messages.tag("list", joined));
        return Command.SINGLE_SUCCESS;
    }

    private int arenaInfo(CommandSender sender) {
        HubConfig config = hub.config();
        if (!config.hasArena()) {
            messages().send(sender, "command.arena-none", Messages.tag("radius", config.arenaRadius()));
            return Command.SINGLE_SUCCESS;
        }
        Location center = config.arenaCenter();
        if (center == null) {
            messages().send(sender, "error.arena-world-missing", Messages.tag("world", config.arenaWorldName()));
            return 0;
        }
        messages().send(sender, "command.arena-info", locationTags(center, config.arenaRadius()));
        return Command.SINGLE_SUCCESS;
    }

    private int arenaSet(CommandSourceStack source, @Nullable Integer radius) {
        CommandSender sender = source.getSender();
        Location location = source.getExecutor() != null ? source.getExecutor().getLocation() : null;
        if (location == null && sender instanceof Player player) {
            location = player.getLocation();
        }
        if (location == null) {
            messages().send(sender, "error.player-only");
            return 0;
        }
        if (configLocked(sender)) {
            return 0;
        }
        int value = radius == null ? hub.config().arenaRadius() : radius;
        hub.config().setArena(location, value);
        messages().send(sender, "command.arena-set", locationTags(hub.config().arenaCenter() == null ? location
                : hub.config().arenaCenter(), hub.config().arenaRadius()));
        if (games().session() != null) {
            messages().send(sender, "command.applies-next-match");
        }
        return Command.SINGLE_SUCCESS;
    }

    private static TagResolver[] locationTags(Location center, int radius) {
        return new TagResolver[] {
                Messages.tag("world", center.getWorld() == null ? "?" : center.getWorld().getName()),
                Messages.tag("x", center.getBlockX()),
                Messages.tag("y", center.getBlockY()),
                Messages.tag("z", center.getBlockZ()),
                Messages.tag("radius", radius)
        };
    }

    private int arenaReset(CommandSender sender) {
        if (configLocked(sender)) {
            return 0;
        }
        hub.config().clearArena();
        messages().send(sender, "command.arena-reset", Messages.tag("radius", hub.config().arenaRadius()));
        return Command.SINGLE_SUCCESS;
    }

    private int settings(CommandSender sender) {
        HubConfig config = hub.config();
        ProtectionSettings protection = config.protection();
        Component teams = config.teamSize() == 0 ? messages().get("command.settings-ffa")
                : messages().get("command.settings-team", Messages.tag("size", config.teamSize()));
        messages().sendPlain(sender, "command.settings",
                Messages.tag("teams", teams),
                Messages.tag("countdown", config.countdownSeconds()),
                Messages.tag("ending", config.endingSeconds()),
                Messages.tag("rotation", messages().get(config.rotationEnabled() ? "command.on" : "command.off")),
                Messages.tag("intermission", config.intermissionSeconds()),
                Messages.tag("radius", config.arenaRadius()),
                Messages.tag("protection", Component.text(protectionSummary(protection))));
        return Command.SINGLE_SUCCESS;
    }

    private static String protectionSummary(ProtectionSettings p) {
        int on = 0;
        boolean[] flags = {p.blockBreak(), p.blockPlace(), p.explosions(), p.fire(), p.entityChangeBlock(),
                p.hanging(), p.interact(), p.itemDrop(), p.entities()};
        for (boolean flag : flags) {
            if (flag) {
                on++;
            }
        }
        return on + "/" + flags.length;
    }

    private int teamSize(CommandSender sender, int size) {
        if (configLocked(sender)) {
            return 0;
        }
        hub.config().setTeamSize(size);
        if (size == 0) {
            messages().send(sender, "command.team-ffa");
        } else {
            messages().send(sender, "command.team-size", Messages.tag("size", size));
        }
        if (games().session() != null) {
            messages().send(sender, "command.applies-next-match");
        }
        return Command.SINGLE_SUCCESS;
    }

    private int reload(CommandSender sender) {
        long started = System.nanoTime();
        try {
            hub.reload();
        } catch (RuntimeException e) {
            hub.plugin().getLogger().log(Level.SEVERE, "Reload failed", e);
            messages().send(sender, "error.reload-failed");
            return 0;
        }
        messages().send(sender, "command.reloaded",
                Messages.tag("ms", Format.number((System.nanoTime() - started) / 1_000_000.0)));
        return Command.SINGLE_SUCCESS;
    }
}
