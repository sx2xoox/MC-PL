package io.github.sx2xoox.mcpl.game.superpower.command;

import java.util.ArrayList;
import java.util.List;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import io.github.sx2xoox.mcpl.core.command.GameCommandRegistrar;
import io.github.sx2xoox.mcpl.core.command.Permissions;
import io.github.sx2xoox.mcpl.core.game.GameSession;
import io.github.sx2xoox.mcpl.core.game.GameStartException;
import io.github.sx2xoox.mcpl.core.game.GameState;
import io.github.sx2xoox.mcpl.core.ui.Feedback;
import io.github.sx2xoox.mcpl.core.ui.Messages;
import io.github.sx2xoox.mcpl.game.superpower.PracticeSettings;
import io.github.sx2xoox.mcpl.game.superpower.SuperpowerGame;
import io.github.sx2xoox.mcpl.game.superpower.ability.AbilityInfo;
import io.github.sx2xoox.mcpl.game.superpower.runtime.Participant;
import io.github.sx2xoox.mcpl.game.superpower.runtime.SuperpowerMatch;
import io.github.sx2xoox.mcpl.game.superpower.runtime.SuperpowerMatch.PracticeResult;
import io.github.sx2xoox.mcpl.game.superpower.ui.AbilityTexts;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * 능력 연습 (English twins in brackets):
 *
 * <pre>
 * /게임 시작 초능력전쟁 연습 [능력]   (superpower practice) start a practice for yourself (admin), or join / switch
 *                                   in a running one
 * /게임 시작 초능력전쟁 연습 모두      (all) admin: a practice for everyone online
 * /게임 연습 바꾸기|switch &lt;능력&gt;     practice: swap your ability
 * /게임 연습 충전|charge              practice: ultimate gauge to 100%
 * /게임 연습 쿨타임|cooldown [켜기|끄기] practice: active cooldowns on/off (admin)
 * /게임 연습 허수아비|dummies [개수]   practice: fresh training dummies in front of you (the hub's tool)
 * </pre>
 *
 * Everything but starting a new practice only works in a running 능력 연습; anything else running refuses it.
 */
final class PracticeCommand {

    private final SuperpowerGame game;

    PracticeCommand(SuperpowerGame game) {
        this.game = game;
    }

    private Messages messages() {
        return game.messages();
    }

    /**
     * {@code 연습 [능력 | 모두]} under {@code /게임 시작 초능력전쟁}: a practice for yourself (or join / switch in the
     * running one); {@code 모두} (admin) starts it for everyone online.
     */
    void addStartOptions(LiteralArgumentBuilder<CommandSourceStack> start, GameCommandRegistrar registrar,
                         boolean english, SuggestionProvider<CommandSourceStack> abilities) {
        String abilityArg = english ? "ability" : "능력";
        start.then(Commands.literal(english ? "practice" : "연습")
                .executes(ctx -> practice(ctx.getSource().getSender(), null))
                .then(Commands.literal(english ? "all" : "모두")
                        .requires(registrar.admin())
                        .executes(ctx -> registrar.startMatch(ctx.getSource().getSender(), game.practice())))
                .then(Commands.argument(abilityArg, StringArgumentType.greedyString())
                        .suggests(abilities)
                        .executes(ctx -> practice(ctx.getSource().getSender(),
                                StringArgumentType.getString(ctx, abilityArg)))));
    }

    /** The 능력 연습 tools under {@code /게임 연습}: 바꾸기, 충전, 쿨타임 (the hub adds 허수아비). */
    void registerTools(GameCommandRegistrar registrar, boolean english,
                       SuggestionProvider<CommandSourceStack> abilities) {
        String abilityArg = english ? "ability" : "능력";
        registrar.registerPracticeTool(Commands.literal(english ? "switch" : "바꾸기")
                .executes(ctx -> usage(ctx.getSource().getSender(), "practice.usage-switch"))
                .then(Commands.argument(abilityArg, StringArgumentType.greedyString())
                        .suggests(abilities)
                        .executes(ctx -> switchAbility(ctx.getSource().getSender(),
                                StringArgumentType.getString(ctx, abilityArg)))));
        registrar.registerPracticeTool(Commands.literal(english ? "charge" : "충전")
                .executes(ctx -> charge(ctx.getSource().getSender())));
        LiteralArgumentBuilder<CommandSourceStack> cooldown = Commands.literal(english ? "cooldown" : "쿨타임")
                .requires(registrar.admin())
                .executes(ctx -> showCooldown(ctx.getSource().getSender()));
        cooldown.then(Commands.literal(english ? "on" : "켜기")
                .executes(ctx -> setCooldown(ctx.getSource().getSender(), true)));
        cooldown.then(Commands.literal(english ? "off" : "끄기")
                .executes(ctx -> setCooldown(ctx.getSource().getSender(), false)));
        registrar.registerPracticeTool(cooldown);
    }

    private int usage(CommandSender sender, String key) {
        messages().send(sender, key);
        return 0;
    }

    // ------------------------------------------------------------------ /게임 시작 초능력전쟁 연습

    private int practice(CommandSender sender, @Nullable String input) {
        if (!(sender instanceof Player player)) {
            messages().send(sender, "command.player-only");
            return 0;
        }
        AbilityInfo info = null;
        if (input != null) {
            info = game.findAbility(input);
            if (info == null) {
                messages().send(sender, "command.unknown-ability", Messages.tag("input", input));
                return 0;
            }
        }
        GameSession session = game.hub().games().session();
        if (session == null) {
            return startPractice(player, info);
        }
        if (session.game() != game.practice()) {
            messages().send(player, "practice.real-match");
            return 0;
        }
        SuperpowerMatch match = game.currentMatch();
        if (session.state() == GameState.COUNTDOWN || match == null || !match.hasStarted()) {
            // Not started yet: the ability is used when it does; join the countdown if the player was not in it.
            game.requestPractice(player.getUniqueId(), info == null ? null : info.id());
            if (!session.isParticipant(player)) {
                game.hub().games().setPrefersSpectating(player, false);
            }
            messages().send(player, "practice.queued", Messages.tag("ability", abilityName(info)));
            return Command.SINGLE_SUCCESS;
        }
        if (session.isInPlay(player)) {
            String id = info != null ? info.id() : randomOtherAbility(match, player);
            return switchTo(player, match, id);
        }
        if (session.isAlive(player)) {
            messages().send(player, "practice.not-now");
            return 0;
        }
        if (session.state() != GameState.RUNNING) {
            messages().send(player, "practice.not-running");
            return 0;
        }
        game.requestPractice(player.getUniqueId(), info == null ? null : info.id());
        game.allowPracticeJoin(player.getUniqueId(), true);
        game.hub().games().setPrefersSpectating(player, false);
        game.allowPracticeJoin(player.getUniqueId(), false);
        if (!session.isInPlay(player)) {
            game.requestPractice(player.getUniqueId(), null);
            messages().send(player, "practice.join-failed");
            return 0;
        }
        return Command.SINGLE_SUCCESS;
    }

    private int startPractice(Player player, @Nullable AbilityInfo info) {
        if (!player.hasPermission(Permissions.ADMIN)) {
            messages().send(player, "practice.start-admin-only");
            return 0;
        }
        game.requestPractice(player.getUniqueId(), info == null ? null : info.id());
        if (game.hub().games().prefersSpectating(player.getUniqueId())) {
            // Asking to practice is asking to play (the countdown drops players who chose to spectate).
            game.hub().games().setPrefersSpectating(player, false);
        }
        try {
            game.hub().games().start(game.practice(), player, List.of(player));
        } catch (GameStartException e) {
            game.requestPractice(player.getUniqueId(), null);
            player.sendMessage(e.feedback());
            return 0;
        }
        messages().send(player, "practice.starting", Messages.tag("ability", abilityName(info)));
        return Command.SINGLE_SUCCESS;
    }

    private Component abilityName(@Nullable AbilityInfo info) {
        return info == null ? messages().get("practice.random") : AbilityTexts.name(info);
    }

    private String randomOtherAbility(SuperpowerMatch match, Player player) {
        Participant participant = match.participant(player.getUniqueId());
        String current = participant == null ? null : participant.abilityId();
        List<String> pool = new ArrayList<>(game.abilities().snapshot().enabledIds());
        if (pool.isEmpty()) {
            game.registry().all().forEach(entry -> pool.add(entry.info().id()));
        }
        if (pool.size() > 1) {
            pool.remove(current);
        }
        return pool.get(match.session().random().nextInt(pool.size()));
    }

    // ------------------------------------------------------------------ practice actions

    /**
     * The running practice for a practice action, or null after telling the sender why there is none (a real match
     * refuses every practice command).
     */
    private @Nullable SuperpowerMatch practiceMatch(CommandSender sender) {
        GameSession session = game.hub().games().session();
        if (session == null) {
            messages().send(sender, "practice.no-session");
            return null;
        }
        if (session.game() != game.practice()) {
            messages().send(sender, "practice.tool-other-mode", Messages.tag("game", session.game().displayComponent()));
            return null;
        }
        SuperpowerMatch match = game.currentMatch();
        if (match == null || !match.hasStarted()) {
            messages().send(sender, "practice.not-started");
            return null;
        }
        return match;
    }

    private int switchAbility(CommandSender sender, String input) {
        if (!(sender instanceof Player player)) {
            messages().send(sender, "command.player-only");
            return 0;
        }
        AbilityInfo info = game.findAbility(input);
        if (info == null) {
            messages().send(sender, "command.unknown-ability", Messages.tag("input", input));
            return 0;
        }
        GameSession session = game.hub().games().session();
        SuperpowerMatch running = game.currentMatch();
        if (session != null && session.game() == game.practice() && (running == null || !running.hasStarted())) {
            game.requestPractice(player.getUniqueId(), info.id());
            messages().send(player, "practice.queued", Messages.tag("ability", AbilityTexts.name(info)));
            return Command.SINGLE_SUCCESS;
        }
        SuperpowerMatch match = practiceMatch(sender);
        return match == null ? 0 : switchTo(player, match, info.id());
    }

    private int switchTo(Player player, SuperpowerMatch match, String abilityId) {
        PracticeResult result = match.switchAbility(player, abilityId);
        if (result == PracticeResult.OK) {
            AbilityInfo info = game.findAbility(abilityId);
            messages().send(player, "practice.switched",
                    Messages.tag("ability", info == null ? Component.text(abilityId)
                            : AbilityTexts.name(info)));
            Feedback.play(player, Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1f, 1.3f);
            return Command.SINGLE_SUCCESS;
        }
        return report(player, result);
    }

    private int charge(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            messages().send(sender, "command.player-only");
            return 0;
        }
        SuperpowerMatch match = practiceMatch(sender);
        if (match == null) {
            return 0;
        }
        PracticeResult result = match.chargeUltimate(player);
        if (result == PracticeResult.OK) {
            messages().send(player, "practice.charged");
            return Command.SINGLE_SUCCESS;
        }
        return report(player, result);
    }

    private int showCooldown(CommandSender sender) {
        SuperpowerMatch match = practiceMatch(sender);
        if (match == null) {
            return 0;
        }
        messages().send(sender, "practice.cooldown-current", Messages.tag("state",
                messages().get(match.cooldownsEnabled() ? "practice.state-on" : "practice.state-off")));
        return Command.SINGLE_SUCCESS;
    }

    private int setCooldown(CommandSender sender, boolean enabled) {
        SuperpowerMatch match = practiceMatch(sender);
        if (match == null) {
            return 0;
        }
        match.setCooldownsEnabled(enabled);
        match.session().broadcast(messages().prefixed(enabled ? "practice.cooldown-on" : "practice.cooldown-off",
                Messages.tag("player", sender.getName())));
        return Command.SINGLE_SUCCESS;
    }

    /** {@code /게임 연습 허수아비 [개수]} (through the hub). */
    int dummies(Player player, @Nullable Integer count) {
        SuperpowerMatch match = practiceMatch(player);
        if (match == null) {
            return 0;
        }
        int wanted = count != null ? Math.min(count, PracticeSettings.MAX_DUMMIES)
                : PracticeSettings.read(game.hub().config().gameSection(game.practice().id())).dummyCount();
        PracticeResult result = match.placeDummies(player, wanted);
        if (result == PracticeResult.OK) {
            messages().send(player, "practice.dummies-placed", Messages.tag("count", match.dummiesOf(player).size()));
            return Command.SINGLE_SUCCESS;
        }
        return report(player, result);
    }

    private int report(Player player, PracticeResult result) {
        String key = switch (result) {
            case OK -> null;
            case NOT_PRACTICE -> "practice.real-match";
            case NOT_STARTED -> "practice.not-started";
            case NOT_PARTICIPANT -> "practice.not-participant";
            case UNKNOWN_ABILITY, FAILED -> "practice.failed";
        };
        if (key != null) {
            messages().send(player, key);
        }
        return 0;
    }
}
