package io.github.sx2xoox.mcpl.core.command;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import java.util.logging.Level;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.github.sx2xoox.mcpl.core.Hub;
import io.github.sx2xoox.mcpl.core.config.HubConfig;
import io.github.sx2xoox.mcpl.core.game.GameManager;
import io.github.sx2xoox.mcpl.core.game.GameSession;
import io.github.sx2xoox.mcpl.core.game.GameStartException;
import io.github.sx2xoox.mcpl.core.game.GameState;
import io.github.sx2xoox.mcpl.core.game.MatchTeam;
import io.github.sx2xoox.mcpl.core.game.MiniGame;
import io.github.sx2xoox.mcpl.core.game.Rotation;
import io.github.sx2xoox.mcpl.core.map.ArenaMap;
import io.github.sx2xoox.mcpl.core.map.MapService;
import io.github.sx2xoox.mcpl.core.pack.ShaderPreset;
import io.github.sx2xoox.mcpl.core.ui.Format;
import io.github.sx2xoox.mcpl.core.ui.Messages;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * {@code /게임} (aliases {@code /mcpl}, {@code /mg}) built with Paper's Brigadier API. Every subcommand has a Korean
 * name and an English twin:
 *
 * <pre>
 * /게임 [도움말|help]                                   categorised help, filtered by permission
 * /게임 시작|start [모드 [옵션]]                          (admin; joining a practice: everyone) every start
 * /게임 중지|stop · 목록|list · 참가|join · 관전|spectate · 순환|rotation [켜기|끄기|목록]
 * /게임 리롤|reroll · 점수|score · 내정보|me · 팀|team      in a match, whatever the mode
 * /게임 연습|practice &lt;도구&gt;                           tools of the running practice (허수아비 here, the rest
 *                                                     from the modes)
 * /게임 맵|map [&lt;맵&gt; [이동|tp | 다시짓기|rebuild] | 여기|here [반경] | 해제|clear]   (admin) battle maps, arena
 * /게임 설정|settings · 리로드|reload · 리소스팩|resourcepack [확인|check | 다시받기|resend [플레이어|모두]]
 * /게임 셰이더|shader [장난감왕국|toy-kingdom | 보호막만|bubble | 끄기|off]   (admin) the pack's shader preset
 * </pre>
 * plus the mode nodes ({@code /게임 능력 …}) and options the registered mini-games add through
 * {@link MiniGame#registerCommands}. Old forms ({@code /게임 능력 연습}, {@code /게임 경기장 …}) still work: words that
 * match no subcommand go to a hidden catch-all argument that rewrites them ({@link MovedCommands}) and runs the new
 * form with a hint; it suggests nothing, so tab completion only shows the new forms.
 */
public final class GameCommand {

    public static final String ROOT = "게임";
    public static final List<String> ALIASES = List.of("mcpl", "mg");

    /** The help sections in order: {@code help.sections.<id>} in messages.yml. */
    public static final List<String> HELP_SECTIONS = List.of("start", "flow", "match", "practice", "modes", "map",
            "server");

    /** Most dummies {@code /게임 연습 허수아비} accepts; the modes cap it further. */
    public static final int MAX_DUMMIES = 10;

    private static final String REST_ARG = "명령어";
    private static final Set<String> START_WORDS = Set.of("시작", "start");

    private static final Predicate<CommandSourceStack> PLAY = source -> source.getSender().hasPermission(Permissions.PLAY);
    private static final Predicate<CommandSourceStack> ADMIN = source -> source.getSender().hasPermission(Permissions.ADMIN);

    private final Hub hub;
    private final Map<MiniGame, MatchCommands> matchCommands = new HashMap<>();
    private final Map<MiniGame, String> startPaths = new HashMap<>();
    private MovedCommands moved = new MovedCommands();
    private CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
    private int redirectDepth;

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
        matchCommands.clear();
        startPaths.clear();
        moved = new MovedCommands();
        moved.add("경기장|arena 설정|set", "맵 여기");
        moved.add("경기장|arena 초기화|reset", "맵 해제");
        moved.add("경기장|arena 맵|map", "맵");
        moved.add("경기장|arena", "맵");

        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(ROOT)
                .requires(PLAY)
                .executes(ctx -> help(ctx.getSource().getSender()));

        for (String name : List.of("도움말", "help")) {
            root.then(Commands.literal(name).executes(ctx -> help(ctx.getSource().getSender())));
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
        // ---- in a match, whatever the mode
        for (String name : List.of("리롤", "reroll")) {
            root.then(Commands.literal(name).executes(ctx -> reroll(ctx.getSource().getSender())));
        }
        for (String name : List.of("점수", "score")) {
            root.then(Commands.literal(name).executes(ctx -> score(ctx.getSource().getSender())));
        }
        for (String name : List.of("내정보", "me")) {
            root.then(Commands.literal(name).executes(ctx -> myInfo(ctx.getSource().getSender())));
        }
        for (String name : List.of("팀", "team")) {
            root.then(Commands.literal(name).executes(ctx -> team(ctx.getSource().getSender())));
        }
        for (String name : List.of("맵", "map")) {
            root.then(mapNode(name));
        }
        SettingsCommand settings = new SettingsCommand(hub);
        for (String name : List.of("설정", "settings")) {
            root.then(settings.build(name, ADMIN));
        }
        for (String name : List.of("리로드", "reload")) {
            root.then(Commands.literal(name).requires(ADMIN).executes(ctx -> reload(ctx.getSource().getSender())));
        }
        for (String name : List.of("리소스팩", "resourcepack")) {
            root.then(packNode(name));
        }
        for (String name : List.of("셰이더", "shader")) {
            root.then(shaderNode(name));
        }

        // ---- what the modes add
        List<LiteralCommandNode<CommandSourceStack>> modeNodes = new ArrayList<>();
        List<LiteralCommandNode<CommandSourceStack>> startNodes = new ArrayList<>();
        List<LiteralCommandNode<CommandSourceStack>> practiceTools = new ArrayList<>();
        GameCommandRegistrar registrar = new GameCommandRegistrar() {
            @Override
            public Hub hub() {
                return hub;
            }

            @Override
            public void register(LiteralArgumentBuilder<CommandSourceStack> subcommand) {
                modeNodes.add(subcommand.then(rest(List.of(subcommand.getLiteral()))).build());
            }

            @Override
            public void registerStart(LiteralArgumentBuilder<CommandSourceStack> node) {
                startNodes.add(node.then(rest(List.of("시작", node.getLiteral()))).build());
            }

            @Override
            public void startPath(MiniGame game, String path) {
                startPaths.put(game, path);
            }

            @Override
            public void registerPracticeTool(LiteralArgumentBuilder<CommandSourceStack> tool) {
                practiceTools.add(tool.build());
            }

            @Override
            public void matchCommands(MiniGame game, MatchCommands commands) {
                matchCommands.put(game, commands);
            }

            @Override
            public void moved(String oldForm, String newForm) {
                moved.add(oldForm, newForm);
            }

            @Override
            public Predicate<CommandSourceStack> play() {
                return PLAY;
            }

            @Override
            public Predicate<CommandSourceStack> admin() {
                return ADMIN;
            }

            @Override
            public int startMatch(CommandSender sender, MiniGame game) {
                return start(sender, game);
            }
        };
        for (MiniGame game : hub.games().games()) {
            try {
                game.registerCommands(registrar);
            } catch (RuntimeException e) {
                hub.plugin().getLogger().log(Level.WARNING, "Could not add the commands of " + game.id(), e);
            }
        }

        for (String name : List.of("시작", "start")) {
            LiteralArgumentBuilder<CommandSourceStack> start = Commands.literal(name).requires(PLAY)
                    .executes(ctx -> start(ctx.getSource().getSender(), (MiniGame) null))
                    .then(Commands.argument(REST_ARG, StringArgumentType.greedyString())
                            .suggests(this::suggestOtherGames)
                            .executes(ctx -> rest(ctx.getSource(), List.of(name),
                                    StringArgumentType.getString(ctx, REST_ARG))));
            startNodes.forEach(start::then);
            root.then(start);
        }
        for (String name : List.of("연습", "practice")) {
            LiteralArgumentBuilder<CommandSourceStack> practice = Commands.literal(name)
                    .executes(ctx -> helpSection(ctx.getSource().getSender(), "practice"))
                    .then(rest(List.of(name)));
            for (String dummies : List.of("허수아비", "dummies")) {
                String countArg = dummies.equals("dummies") ? "count" : "개수";
                practice.then(Commands.literal(dummies)
                        .executes(ctx -> dummies(ctx.getSource().getSender(), null))
                        .then(Commands.argument(countArg, IntegerArgumentType.integer(1, MAX_DUMMIES))
                                .executes(ctx -> dummies(ctx.getSource().getSender(),
                                        IntegerArgumentType.getInteger(ctx, countArg)))));
            }
            practiceTools.forEach(practice::then);
            root.then(practice);
        }
        modeNodes.forEach(root::then);
        root.then(rest(List.of()));

        LiteralCommandNode<CommandSourceStack> node = root.build();
        dispatcher = new CommandDispatcher<>();
        dispatcher.getRoot().addChild(node);
        return node;
    }

    private LiteralArgumentBuilder<CommandSourceStack> mapNode(String name) {
        boolean english = name.equals("map");
        String mapArg = english ? "map" : "맵이름";
        String radiusArg = english ? "radius" : "반경";
        LiteralArgumentBuilder<CommandSourceStack> map = Commands.literal(name).requires(ADMIN)
                .executes(ctx -> mapList(ctx.getSource().getSender()));
        for (String here : List.of("여기", "here")) {
            map.then(Commands.literal(here)
                    .executes(ctx -> arenaSet(ctx.getSource(), null))
                    .then(Commands.argument(radiusArg, IntegerArgumentType.integer(HubConfig.MIN_RADIUS, HubConfig.MAX_RADIUS))
                            .executes(ctx -> arenaSet(ctx.getSource(), IntegerArgumentType.getInteger(ctx, radiusArg)))));
        }
        for (String clear : List.of("해제", "clear")) {
            map.then(Commands.literal(clear).executes(ctx -> arenaReset(ctx.getSource().getSender())));
        }
        var mapId = Commands.argument(mapArg, StringArgumentType.word())
                .suggests(this::suggestMaps)
                .executes(ctx -> mapUse(ctx.getSource().getSender(), StringArgumentType.getString(ctx, mapArg), false));
        for (String rebuild : List.of("다시짓기", "rebuild")) {
            mapId.then(Commands.literal(rebuild).executes(ctx -> mapUse(ctx.getSource().getSender(),
                    StringArgumentType.getString(ctx, mapArg), true)));
        }
        for (String teleport : List.of("이동", "tp")) {
            mapId.then(Commands.literal(teleport).executes(ctx -> mapTeleport(ctx.getSource().getSender(),
                    StringArgumentType.getString(ctx, mapArg))));
        }
        return map.then(mapId);
    }

    private LiteralArgumentBuilder<CommandSourceStack> packNode(String name) {
        LiteralArgumentBuilder<CommandSourceStack> pack = Commands.literal(name)
                .executes(ctx -> packCheck(ctx.getSource().getSender()));
        for (String check : List.of("확인", "check")) {
            pack.then(Commands.literal(check).executes(ctx -> packCheck(ctx.getSource().getSender())));
        }
        for (String resend : List.of("다시받기", "resend")) {
            String targetArg = resend.equals("resend") ? "player" : "대상";
            pack.then(Commands.literal(resend)
                    .executes(ctx -> packResend(ctx.getSource().getSender(), null))
                    .then(Commands.argument(targetArg, StringArgumentType.greedyString()).requires(ADMIN)
                            .suggests(this::suggestPackTargets)
                            .executes(ctx -> packResend(ctx.getSource().getSender(),
                                    StringArgumentType.getString(ctx, targetArg)))));
        }
        return pack;
    }

    /** {@code /게임 셰이더 [종류|끄기]} (admin): the shader preset served with the pack; English twins by id. */
    private LiteralArgumentBuilder<CommandSourceStack> shaderNode(String name) {
        boolean english = name.equals("shader");
        LiteralArgumentBuilder<CommandSourceStack> shader = Commands.literal(name).requires(ADMIN)
                .executes(ctx -> shaderStatus(ctx.getSource().getSender()));
        for (ShaderPreset preset : ShaderPreset.values()) {
            shader.then(Commands.literal(english ? preset.id() : preset.koreanName())
                    .executes(ctx -> shaderUse(ctx.getSource().getSender(), preset)));
        }
        shader.then(Commands.literal(english ? "off" : "끄기")
                .executes(ctx -> shaderUse(ctx.getSource().getSender(), null)));
        return shader;
    }

    // ------------------------------------------------------------------ old forms (hidden)

    /**
     * The hidden catch-all under a node: whatever matches no subcommand there. It suggests nothing, so tab completion
     * lists only the real subcommands.
     */
    private RequiredArgumentBuilder<CommandSourceStack, String> rest(List<String> prefix) {
        return Commands.argument(REST_ARG, StringArgumentType.greedyString())
                .suggests((ctx, builder) -> builder.buildFuture())
                .executes(ctx -> rest(ctx.getSource(), prefix, StringArgumentType.getString(ctx, REST_ARG)));
    }

    /** Runs an old form as its new form (with a hint), starts a mode by name, or says the command is unknown. */
    private int rest(CommandSourceStack source, List<String> prefix, String typed) {
        CommandSender sender = source.getSender();
        List<String> words = new ArrayList<>(prefix);
        words.addAll(MovedCommands.split(typed));
        if (redirectDepth == 0 && words.size() > 1 && START_WORDS.contains(words.getFirst().toLowerCase(Locale.ROOT))) {
            String query = String.join(" ", words.subList(1, words.size()));
            MiniGame game = games().find(query);
            if (game == null) {
                messages().send(sender, "error.unknown-game", Messages.tag("input", query));
                return 0;
            }
            String path = startPaths.get(game);
            return path == null ? start(sender, game) : redirect(source, "시작 " + path);
        }
        List<String> target = redirectDepth == 0 ? moved.rewrite(words) : null;
        if (target == null) {
            messages().send(sender, "command.unknown", Messages.tag("input", String.join(" ", words)));
            return 0;
        }
        return redirect(source, String.join(" ", target));
    }

    /** Tells the sender the new form and runs it. */
    private int redirect(CommandSourceStack source, String command) {
        CommandSender sender = source.getSender();
        messages().send(sender, "command.moved", Messages.tag("command", "/" + ROOT + " " + command));
        redirectDepth++;
        try {
            return dispatcher.execute(ROOT + " " + command, source);
        } catch (CommandSyntaxException e) {
            messages().send(sender, "command.moved-failed", Messages.tag("reason", e.getRawMessage().getString()));
            return 0;
        } finally {
            redirectDepth--;
        }
    }

    /** {@code /게임 시작 <이름>} for modes that registered no {@code 시작} node (e.g. modes added by other code). */
    private CompletableFuture<Suggestions> suggestOtherGames(CommandContext<CommandSourceStack> ctx,
                                                             SuggestionsBuilder builder) {
        String remaining = builder.getRemainingLowerCase();
        for (MiniGame game : hub.games().games()) {
            if (startPaths.containsKey(game)) {
                continue;
            }
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
        for (String section : HELP_SECTIONS) {
            List<Component> lines = helpLines(sender, section);
            if (!lines.isEmpty()) {
                sender.sendMessage(messages().get("help.sections." + section + ".title"));
                lines.forEach(sender::sendMessage);
            }
        }
        messages().sendPlain(sender, "help.footer");
        return Command.SINGLE_SUCCESS;
    }

    private int helpSection(CommandSender sender, String section) {
        sender.sendMessage(messages().get("help.sections." + section + ".title"));
        helpLines(sender, section).forEach(sender::sendMessage);
        return Command.SINGLE_SUCCESS;
    }

    /**
     * The lines of one help section the sender may use: {@code help.sections.<id>.play}, plus {@code .admin} for
     * admins. {@code <cmd:'/게임 …'>} (optionally {@code <cmd:'/게임 … ':'shown text'>}) is a clickable command.
     */
    public List<Component> helpLines(CommandSender sender, String section) {
        List<Component> out = new ArrayList<>(messages().lines("help.sections." + section + ".play",
                commandTag(TextColor.color(0xffb347))));
        if (sender.hasPermission(Permissions.ADMIN)) {
            out.addAll(messages().lines("help.sections." + section + ".admin", commandTag(TextColor.color(0xff7f50))));
        }
        return out;
    }

    private TagResolver commandTag(TextColor color) {
        Component hover = messages().get("help.click-hint");
        return TagResolver.resolver("cmd", (args, context) -> {
            String command = args.popOr("<cmd> needs the command").value();
            String shown = args.hasNext() ? args.pop().value() : command.trim();
            return Tag.selfClosingInserting(Component.text(shown, color)
                    .clickEvent(ClickEvent.suggestCommand(command))
                    .hoverEvent(HoverEvent.showText(hover)));
        });
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

    /** {@code /게임 시작 <모드>}: admins start {@code game} (null = the next rotation mode) for everyone online. */
    private int start(CommandSender sender, @Nullable MiniGame game) {
        if (!sender.hasPermission(Permissions.ADMIN)) {
            messages().send(sender, "error.admin-only");
            return 0;
        }
        if (game == null) {
            game = games().defaultGame();
            if (game == null) {
                messages().send(sender, "error.choose-game");
                return 0;
            }
        }
        try {
            games().start(game, sender);
            return Command.SINGLE_SUCCESS;
        } catch (GameStartException e) {
            sender.sendMessage(e.feedback());
            return 0;
        }
    }

    // ------------------------------------------------------------------ in a match, whatever the mode

    /** The running session, or null after telling the sender there is none. */
    private @Nullable GameSession runningSession(CommandSender sender) {
        GameSession session = games().session();
        if (session == null) {
            messages().send(sender, "command.no-match");
        }
        return session;
    }

    private MatchCommands commandsOf(GameSession session) {
        MatchCommands commands = matchCommands.get(session.game());
        return commands != null ? commands : new MatchCommands() { };
    }

    private int reroll(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            messages().send(sender, "error.player-only");
            return 0;
        }
        GameSession session = runningSession(sender);
        if (session == null) {
            return 0;
        }
        int result = commandsOf(session).reroll(player);
        if (result == MatchCommands.UNSUPPORTED) {
            messages().send(sender, "command.reroll-none", Messages.tag("game", session.game().displayComponent()));
            return 0;
        }
        return result;
    }

    private int score(CommandSender sender) {
        GameSession session = runningSession(sender);
        if (session == null) {
            return 0;
        }
        int result = commandsOf(session).score(sender);
        if (result == MatchCommands.UNSUPPORTED) {
            messages().send(sender, "command.score-none", Messages.tag("game", session.game().displayComponent()),
                    Messages.tag("alive", session.aliveCount()), Messages.tag("total", session.participantIds().size()));
            return Command.SINGLE_SUCCESS;
        }
        return result;
    }

    private int myInfo(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            messages().send(sender, "error.player-only");
            return 0;
        }
        GameSession session = runningSession(sender);
        if (session == null) {
            return 0;
        }
        int result = commandsOf(session).myInfo(player);
        if (result == MatchCommands.UNSUPPORTED) {
            messages().send(sender, "command.me-none", Messages.tag("game", session.game().displayComponent()));
            return 0;
        }
        return result;
    }

    private int team(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            messages().send(sender, "error.player-only");
            return 0;
        }
        GameSession session = runningSession(sender);
        if (session == null) {
            return 0;
        }
        int result = commandsOf(session).team(player);
        if (result != MatchCommands.UNSUPPORTED) {
            return result;
        }
        if (!session.isTeamMode()) {
            messages().send(sender, "command.team-not-team-mode");
            return 0;
        }
        MatchTeam team = session.teamOf(player);
        if (team == null) {
            messages().send(sender, "command.team-none");
            return 0;
        }
        List<Component> names = new ArrayList<>();
        for (UUID member : team.members()) {
            Component name = session.name(member);
            names.add(session.isAlive(member) ? name : name.color(NamedTextColor.DARK_GRAY));
        }
        messages().send(sender, "command.team-members", Messages.tag("team", team.displayName()),
                Messages.tag("members", Component.join(JoinConfiguration.separator(
                        Component.text(", ", NamedTextColor.GRAY)), names)));
        return Command.SINGLE_SUCCESS;
    }

    private int dummies(CommandSender sender, @Nullable Integer count) {
        if (!(sender instanceof Player player)) {
            messages().send(sender, "error.player-only");
            return 0;
        }
        GameSession session = games().session();
        if (session == null) {
            messages().send(sender, "command.practice-none");
            return 0;
        }
        int result = commandsOf(session).dummies(player, count);
        if (result == MatchCommands.UNSUPPORTED) {
            messages().send(sender, "command.practice-tool-unsupported",
                    Messages.tag("game", session.game().displayComponent()));
            return 0;
        }
        return result;
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

    /**
     * True (and tells the sender) when a setting change could not be saved now: config.yml has a syntax error, or it
     * was edited on disk and not reloaded yet.
     */
    private boolean configLocked(CommandSender sender) {
        return !hub.settings().canSave(sender);
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

    // ------------------------------------------------------------------ battle maps

    private CompletableFuture<Suggestions> suggestMaps(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        String remaining = builder.getRemainingLowerCase();
        for (String id : hub.maps().ids()) {
            if (id.startsWith(remaining)) {
                builder.suggest(id);
            }
        }
        return builder.buildFuture();
    }

    private int mapList(CommandSender sender) {
        MapService maps = hub.maps();
        arenaInfo(sender);
        messages().send(sender, "command.map-list-header");
        for (ArenaMap map : maps.maps()) {
            String status = maps.isBuilt(map) ? "command.map-status-built"
                    : maps.world(map) != null ? "command.map-status-outdated" : "command.map-status-missing";
            messages().send(sender, "command.map-list-entry",
                    Messages.tag("id", map.id()),
                    Messages.tag("name", map.displayName()),
                    Messages.tag("size", map.playRadius() * 2),
                    Messages.tag("status", messages().get(status)),
                    Messages.tag("description", map.description()));
        }
        return Command.SINGLE_SUCCESS;
    }

    /** Builds the map if needed (or {@code rebuild}), then makes it the arena. */
    private int mapUse(CommandSender sender, String id, boolean rebuild) {
        MapService maps = hub.maps();
        ArenaMap map = maps.map(id);
        if (map == null) {
            messages().send(sender, "error.map-unknown", Messages.tag("id", id));
            return 0;
        }
        if (configLocked(sender)) {
            return 0;
        }
        if (!rebuild && maps.isBuilt(map)) {
            useMap(sender, map, maps.world(map));
            return Command.SINGLE_SUCCESS;
        }
        if (games().session() != null) {
            messages().send(sender, "error.map-match-running");
            return 0;
        }
        if (maps.isBuilding()) {
            messages().send(sender, "error.map-busy");
            return 0;
        }
        messages().send(sender, "command.map-building-start", Messages.tag("name", map.displayName()),
                Messages.tag("world", MapService.worldName(map)));
        int[] lastQuarter = {0};
        boolean started;
        try {
            started = maps.build(map, progress -> {
                int percent = (int) Math.floor(progress * 100);
                if (sender instanceof Player player) {
                    player.sendActionBar(messages().get("command.map-building-progress",
                            Messages.tag("percent", percent)));
                } else if (percent / 25 > lastQuarter[0]) {
                    lastQuarter[0] = percent / 25;
                    messages().send(sender, "command.map-building-progress", Messages.tag("percent", percent));
                }
            }, world -> {
                messages().send(sender, "command.map-built", Messages.tag("name", map.displayName()));
                useMap(sender, map, world);
            });
        } catch (RuntimeException e) {
            hub.plugin().getLogger().log(Level.SEVERE, "Could not build the map " + map.id(), e);
            messages().send(sender, "error.internal");
            return 0;
        }
        if (!started) {
            messages().send(sender, "error.map-busy");
            return 0;
        }
        return Command.SINGLE_SUCCESS;
    }

    private void useMap(CommandSender sender, ArenaMap map, @Nullable World world) {
        if (world == null) {
            messages().send(sender, "error.map-not-built", Messages.tag("name", map.displayName()),
                    Messages.tag("id", map.id()));
            return;
        }
        hub.config().setArena(hub.maps().center(map, world), map.playRadius());
        messages().send(sender, "command.map-arena-set", Messages.tag("name", map.displayName()),
                Messages.tag("radius", map.playRadius()));
        if (games().session() != null) {
            messages().send(sender, "command.applies-next-match");
        }
    }

    private int mapTeleport(CommandSender sender, String id) {
        MapService maps = hub.maps();
        ArenaMap map = maps.map(id);
        if (map == null) {
            messages().send(sender, "error.map-unknown", Messages.tag("id", id));
            return 0;
        }
        if (!(sender instanceof Player player)) {
            messages().send(sender, "error.player-only");
            return 0;
        }
        World world = maps.world(map);
        if (world == null || !maps.isBuilt(map) && maps.isBuilding() && map.id().equals(maps.buildingId())) {
            messages().send(sender, "error.map-not-built", Messages.tag("name", map.displayName()),
                    Messages.tag("id", map.id()));
            return 0;
        }
        player.teleport(maps.center(map, world));
        messages().send(sender, "command.map-teleport", Messages.tag("name", map.displayName()));
        return Command.SINGLE_SUCCESS;
    }

    private int arenaReset(CommandSender sender) {
        if (configLocked(sender)) {
            return 0;
        }
        hub.config().clearArena();
        messages().send(sender, "command.arena-reset", Messages.tag("radius", hub.config().arenaRadius()));
        return Command.SINGLE_SUCCESS;
    }

    private int packCheck(CommandSender sender) {
        hub.packCheck().check(sender);
        return Command.SINGLE_SUCCESS;
    }

    /** Resends the pack to the sender, or (admins) to {@code target}: a player name or 모두/all. */
    private int packResend(CommandSender sender, @Nullable String target) {
        if (!hub.resourcePacks().offered()) {
            messages().send(sender, "resource-pack.resend-off");
            return 0;
        }
        if (target == null) {
            if (!(sender instanceof Player player)) {
                messages().send(sender, "error.player-only");
                return 0;
            }
            hub.packCheck().resend(List.of(player));
            messages().send(sender, "resource-pack.resent-self");
            return Command.SINGLE_SUCCESS;
        }
        List<Player> targets;
        target = target.trim();
        if (target.equals("모두") || target.equalsIgnoreCase("all")) {
            targets = List.copyOf(Bukkit.getOnlinePlayers());
        } else {
            Player player = Bukkit.getPlayerExact(target);
            if (player == null) {
                messages().send(sender, "resource-pack.unknown-player", Messages.tag("input", target));
                return 0;
            }
            targets = List.of(player);
        }
        int sent = hub.packCheck().resend(targets);
        messages().send(sender, "resource-pack.resent", Messages.tag("count", sent));
        return Command.SINGLE_SUCCESS;
    }

    /** {@code /게임 셰이더}: the served preset and every choice, each clickable. */
    private int shaderStatus(CommandSender sender) {
        ShaderPreset current = hub.config().resourcePack().shaders();
        messages().send(sender, "shader.status", Messages.tag("current", current == null
                ? messages().get("shader.off-name") : Component.text(current.koreanName())));
        for (ShaderPreset preset : ShaderPreset.values()) {
            shaderEntry(sender, preset.koreanName(), messages().get(preset.messageKey()), preset == current);
        }
        shaderEntry(sender, "끄기", messages().get("shader.off-description"), current == null);
        if (!hub.config().resourcePack().selfHosted()) {
            messages().send(sender, "shader.external-url");
        }
        return Command.SINGLE_SUCCESS;
    }

    private void shaderEntry(CommandSender sender, String word, Component description, boolean current) {
        String command = "/" + ROOT + " 셰이더 " + word;
        Component name = Component.text(word, NamedTextColor.WHITE)
                .clickEvent(ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(messages().get("shader.hover", Messages.tag("command", command))));
        messages().send(sender, "shader.entry", Messages.tag("name", name), Messages.tag("description", description),
                Messages.tag("mark", current ? messages().get("shader.current-mark") : Component.empty()));
    }

    /** {@code /게임 셰이더 <종류|끄기>}: saves the preset (null = off) and sends everyone online the new pack. */
    private int shaderUse(CommandSender sender, @Nullable ShaderPreset preset) {
        Component name = preset == null ? messages().get("shader.off-name") : Component.text(preset.koreanName());
        if (hub.config().resourcePack().shaders() == preset) {
            messages().send(sender, "shader.unchanged", Messages.tag("preset", name));
            return Command.SINGLE_SUCCESS;
        }
        if (configLocked(sender)) {
            return 0;
        }
        hub.config().setResourcePackShaders(preset);
        hub.resourcePacks().reload();
        hub.plugin().getLogger().info(sender.getName() + " set resource-pack.shaders to "
                + (preset == null ? "off" : preset.id()) + " (/게임 셰이더)");
        String key = preset == null ? "shader.turned-off" : "shader.changed";
        if (hub.resourcePacks().offered()) {
            messages().send(sender, key, Messages.tag("preset", name),
                    Messages.tag("count", Bukkit.getOnlinePlayers().size()));
        } else {
            messages().send(sender, key + "-not-offered", Messages.tag("preset", name));
        }
        if (!hub.config().resourcePack().selfHosted()) {
            messages().send(sender, "shader.external-url");
        }
        return Command.SINGLE_SUCCESS;
    }

    private CompletableFuture<Suggestions> suggestPackTargets(CommandContext<CommandSourceStack> ctx,
                                                              SuggestionsBuilder builder) {
        String remaining = builder.getRemainingLowerCase();
        List<String> options = new ArrayList<>(List.of("모두", "all"));
        for (Player player : Bukkit.getOnlinePlayers()) {
            options.add(player.getName());
        }
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(remaining)) {
                builder.suggest(option);
            }
        }
        return builder.buildFuture();
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
