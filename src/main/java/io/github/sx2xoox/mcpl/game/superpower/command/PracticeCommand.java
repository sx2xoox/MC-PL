package io.github.sx2xoox.mcpl.game.superpower.command;

import java.util.ArrayList;
import java.util.List;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
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
 * The practice subcommands of {@code /게임 능력} (English twins under {@code /mcpl ability}):
 *
 * <pre>
 * /게임 능력 연습|practice [능력]       start a practice for yourself (admin), or join / switch in a running one
 * /게임 능력 변경|switch &lt;능력&gt;      practice: swap your ability
 * /게임 능력 충전|charge               practice: ultimate gauge to 100%
 * /게임 능력 쿨타임|cooldown [켜기|끄기] practice: active cooldowns on/off (admin)
 * /게임 능력 허수아비|dummies [개수]    practice: put fresh training dummies in front of you
 * </pre>
 *
 * Everything but starting a new practice only works in a running practice session; a real match refuses it.
 */
final class PracticeCommand {

    private final SuperpowerGame game;

    PracticeCommand(SuperpowerGame game) {
        this.game = game;
    }

    private Messages messages() {
        return game.messages();
    }

    void addTo(LiteralArgumentBuilder<CommandSourceStack> node, GameCommandRegistrar registrar, boolean english,
               SuggestionProvider<CommandSourceStack> abilities) {
        String abilityArg = english ? "ability" : "능력";
        String countArg = english ? "count" : "개수";
        for (String name : List.of("연습", "practice")) {
            node.then(Commands.literal(name)
                    .executes(ctx -> practice(ctx.getSource().getSender(), null))
                    .then(Commands.argument(abilityArg, StringArgumentType.greedyString())
                            .suggests(abilities)
                            .executes(ctx -> practice(ctx.getSource().getSender(),
                                    StringArgumentType.getString(ctx, abilityArg)))));
        }
        for (String name : List.of("변경", "switch")) {
            node.then(Commands.literal(name)
                    .executes(ctx -> usage(ctx.getSource().getSender(), "practice.usage-switch"))
                    .then(Commands.argument(abilityArg, StringArgumentType.greedyString())
                            .suggests(abilities)
                            .executes(ctx -> switchAbility(ctx.getSource().getSender(),
                                    StringArgumentType.getString(ctx, abilityArg)))));
        }
        for (String name : List.of("충전", "charge")) {
            node.then(Commands.literal(name).executes(ctx -> charge(ctx.getSource().getSender())));
        }
        for (String name : List.of("쿨타임", "cooldown")) {
            LiteralArgumentBuilder<CommandSourceStack> cooldown = Commands.literal(name)
                    .requires(registrar.admin())
                    .executes(ctx -> showCooldown(ctx.getSource().getSender()));
            for (String on : List.of("켜기", "on")) {
                cooldown.then(Commands.literal(on).executes(ctx -> setCooldown(ctx.getSource().getSender(), true)));
            }
            for (String off : List.of("끄기", "off")) {
                cooldown.then(Commands.literal(off)
                        .executes(ctx -> setCooldown(ctx.getSource().getSender(), false)));
            }
            node.then(cooldown);
        }
        for (String name : List.of("허수아비", "dummies")) {
            node.then(Commands.literal(name)
                    .executes(ctx -> dummies(ctx.getSource().getSender(), null))
                    .then(Commands.argument(countArg, IntegerArgumentType.integer(1, PracticeSettings.MAX_DUMMIES))
                            .executes(ctx -> dummies(ctx.getSource().getSender(),
                                    IntegerArgumentType.getInteger(ctx, countArg)))));
        }
    }

    private int usage(CommandSender sender, String key) {
        messages().send(sender, key);
        return 0;
    }

    // ------------------------------------------------------------------ /게임 능력 연습

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
            messages().send(sender, "practice.real-match");
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

    private int dummies(CommandSender sender, @Nullable Integer count) {
        if (!(sender instanceof Player player)) {
            messages().send(sender, "command.player-only");
            return 0;
        }
        SuperpowerMatch match = practiceMatch(sender);
        if (match == null) {
            return 0;
        }
        int wanted = count != null ? count
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
