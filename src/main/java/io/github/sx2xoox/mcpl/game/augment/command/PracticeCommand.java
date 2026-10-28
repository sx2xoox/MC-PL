package io.github.sx2xoox.mcpl.game.augment.command;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.github.sx2xoox.mcpl.core.command.GameCommandRegistrar;
import io.github.sx2xoox.mcpl.core.command.Permissions;
import io.github.sx2xoox.mcpl.core.game.GameSession;
import io.github.sx2xoox.mcpl.core.game.GameStartException;
import io.github.sx2xoox.mcpl.core.game.GameState;
import io.github.sx2xoox.mcpl.core.ui.Feedback;
import io.github.sx2xoox.mcpl.core.ui.Messages;
import io.github.sx2xoox.mcpl.game.augment.AugmentGame;
import io.github.sx2xoox.mcpl.game.augment.AugmentPracticeSettings;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentInfo;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentTier;
import io.github.sx2xoox.mcpl.game.augment.runtime.AugmentMatch;
import io.github.sx2xoox.mcpl.game.augment.runtime.MatchPhase;
import io.github.sx2xoox.mcpl.game.augment.runtime.OfferMenu;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * The 증강 연습 subcommands of {@code /게임 증강} (English twins under {@code /mcpl augment}):
 *
 * <pre>
 * /게임 증강 연습|practice                  start a practice (admin) or join the running one
 * /게임 증강 받기|give &lt;증강&gt;               practice: get an augment
 * /게임 증강 빼기|take &lt;증강|전부&gt;          practice: lose one or all augments
 * /게임 증강 카드|cards [실버|골드|프리즘]     practice: a card offer now (random tier without one)
 * /게임 증강 다음단계|next                   practice: farming → fight → border shrinks → final border
 * /게임 증강 허수아비|dummies [개수]          practice: dummies in front of you
 * /게임 증강 대전|pvp [켜기|끄기]             practice: practising players can hurt each other
 * </pre>
 *
 * Everything but starting or joining only works for a player practising in a running practice.
 */
public final class PracticeCommand {

    private static final List<String> ALL = List.of("전부", "all");

    private final AugmentGame game;

    PracticeCommand(AugmentGame game) {
        this.game = game;
    }

    private Messages messages() {
        return game.messages();
    }

    void addTo(LiteralArgumentBuilder<CommandSourceStack> node, GameCommandRegistrar registrar, boolean english,
               SuggestionProvider<CommandSourceStack> augments) {
        String augmentArg = english ? "augment" : "증강";
        String countArg = english ? "count" : "개수";
        String tierArg = english ? "tier" : "등급";
        node.then(Commands.literal(english ? "practice" : "연습")
                .executes(ctx -> practice(ctx.getSource().getSender())));
        node.then(Commands.literal(english ? "give" : "받기")
                .executes(ctx -> usage(ctx.getSource().getSender(), "practice.usage-give"))
                .then(Commands.argument(augmentArg, StringArgumentType.greedyString())
                        .suggests(augments)
                        .executes(ctx -> give(ctx.getSource().getSender(),
                                StringArgumentType.getString(ctx, augmentArg)))));
        node.then(Commands.literal(english ? "take" : "빼기")
                .executes(ctx -> usage(ctx.getSource().getSender(), "practice.usage-take"))
                .then(Commands.argument(augmentArg, StringArgumentType.greedyString())
                        .suggests(this::suggestOwned)
                        .executes(ctx -> take(ctx.getSource().getSender(),
                                StringArgumentType.getString(ctx, augmentArg)))));
        node.then(Commands.literal(english ? "cards" : "카드")
                .executes(ctx -> cards(ctx.getSource().getSender(), null))
                .then(Commands.argument(tierArg, StringArgumentType.greedyString())
                        .suggests(this::suggestTiers)
                        .executes(ctx -> cards(ctx.getSource().getSender(),
                                StringArgumentType.getString(ctx, tierArg)))));
        node.then(Commands.literal(english ? "next" : "다음단계")
                .executes(ctx -> next(ctx.getSource().getSender())));
        node.then(Commands.literal(english ? "dummies" : "허수아비")
                .executes(ctx -> dummies(ctx.getSource().getSender(), null))
                .then(Commands.argument(countArg, IntegerArgumentType.integer(1, AugmentPracticeSettings.MAX_DUMMIES))
                        .executes(ctx -> dummies(ctx.getSource().getSender(),
                                IntegerArgumentType.getInteger(ctx, countArg)))));
        LiteralArgumentBuilder<CommandSourceStack> pvp = Commands.literal(english ? "pvp" : "대전")
                .executes(ctx -> showPvp(ctx.getSource().getSender()));
        pvp.then(Commands.literal(english ? "on" : "켜기").executes(ctx -> setPvp(ctx.getSource().getSender(), true)));
        pvp.then(Commands.literal(english ? "off" : "끄기").executes(ctx -> setPvp(ctx.getSource().getSender(), false)));
        node.then(pvp);
    }

    private int usage(CommandSender sender, String key) {
        messages().send(sender, key);
        return 0;
    }

    // ------------------------------------------------------------------ suggestions

    private CompletableFuture<Suggestions> suggestOwned(CommandContext<CommandSourceStack> ctx,
                                                        SuggestionsBuilder builder) {
        AugmentMatch match = game.currentMatch();
        java.util.UUID player = ctx.getSource().getSender() instanceof Player p ? p.getUniqueId() : null;
        ownedSuggestions(match, player, builder.getRemaining()).forEach(builder::suggest);
        return builder.buildFuture();
    }

    /** What {@code /게임 증강 빼기} suggests: "전부" and the augments the player owns, matching {@code typed}. */
    public static List<String> ownedSuggestions(@Nullable AugmentMatch match, java.util.@Nullable UUID player,
                                                String typed) {
        String remaining = typed.toLowerCase(java.util.Locale.ROOT).replace(" ", "");
        List<String> out = new java.util.ArrayList<>();
        for (String all : ALL) {
            if (all.startsWith(remaining)) {
                out.add(all);
            }
        }
        if (match != null && player != null) {
            for (AugmentInfo info : match.augments(player)) {
                if (!match.owns(player, info)) {
                    continue;
                }
                if (info.name().replace(" ", "").startsWith(remaining)) {
                    out.add(info.name());
                }
                if (info.id().startsWith(remaining)) {
                    out.add(info.id());
                }
            }
        }
        return out;
    }

    private CompletableFuture<Suggestions> suggestTiers(CommandContext<CommandSourceStack> ctx,
                                                        SuggestionsBuilder builder) {
        String remaining = builder.getRemainingLowerCase();
        for (AugmentTier tier : AugmentTier.values()) {
            if (tier.koreanName().startsWith(remaining)) {
                builder.suggest(tier.koreanName());
            }
            if (tier.key().startsWith(remaining)) {
                builder.suggest(tier.key());
            }
        }
        return builder.buildFuture();
    }

    // ------------------------------------------------------------------ start / join

    private int practice(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            messages().send(sender, "command.player-only");
            return 0;
        }
        GameSession session = game.hub().games().session();
        if (session == null) {
            return start(player);
        }
        if (session.game() != game.practice()) {
            messages().send(player, "practice.real-match");
            return 0;
        }
        if (session.state() == GameState.COUNTDOWN) {
            game.hub().games().setPrefersSpectating(player, false);
            messages().send(player, "practice.joined-countdown");
            return Command.SINGLE_SUCCESS;
        }
        if (session.isInPlay(player)) {
            messages().send(player, "practice.already-in");
            return 0;
        }
        if (session.state() != GameState.RUNNING || session.isAlive(player)) {
            messages().send(player, "practice.not-now");
            return 0;
        }
        game.allowPracticeJoin(player.getUniqueId(), true);
        game.hub().games().setPrefersSpectating(player, false);
        game.allowPracticeJoin(player.getUniqueId(), false);
        if (!session.isInPlay(player)) {
            messages().send(player, "practice.join-failed");
            return 0;
        }
        return Command.SINGLE_SUCCESS;
    }

    private int start(Player player) {
        if (!player.hasPermission(Permissions.ADMIN)) {
            messages().send(player, "practice.start-admin-only");
            return 0;
        }
        if (game.hub().games().prefersSpectating(player.getUniqueId())) {
            game.hub().games().setPrefersSpectating(player, false);
        }
        try {
            game.hub().games().start(game.practice(), player, List.of(player));
        } catch (GameStartException e) {
            player.sendMessage(e.feedback());
            return 0;
        }
        messages().send(player, "practice.starting");
        return Command.SINGLE_SUCCESS;
    }

    // ------------------------------------------------------------------ actions

    /** The running practice and the sender practising in it, or null after telling them why not. */
    private @Nullable AugmentMatch practising(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            messages().send(sender, "command.player-only");
            return null;
        }
        GameSession session = game.hub().games().session();
        if (session == null) {
            messages().send(sender, "practice.no-session");
            return null;
        }
        if (session.game() != game.practice()) {
            messages().send(sender, "practice.real-match");
            return null;
        }
        AugmentMatch match = game.currentMatch();
        if (match == null || match.phase() == MatchPhase.PREPARING) {
            messages().send(sender, "practice.not-started");
            return null;
        }
        if (!session.isInPlay(player)) {
            messages().send(sender, "practice.not-participant");
            return null;
        }
        return match;
    }

    private int give(CommandSender sender, String input) {
        AugmentMatch match = practising(sender);
        if (match == null) {
            return 0;
        }
        Player player = (Player) sender;
        AugmentInfo info = game.findAugment(input);
        if (info == null) {
            messages().send(sender, "command.unknown-augment", Messages.tag("input", input));
            return 0;
        }
        if (match.owns(player.getUniqueId(), info)) {
            messages().send(sender, "practice.already-owned", Messages.tag("augment", OfferMenu.name(info)));
            return 0;
        }
        return match.grant(player, info) ? Command.SINGLE_SUCCESS : 0;
    }

    private int take(CommandSender sender, String input) {
        AugmentMatch match = practising(sender);
        if (match == null) {
            return 0;
        }
        Player player = (Player) sender;
        if (ALL.contains(input.trim().toLowerCase(java.util.Locale.ROOT))) {
            int removed = match.revokeAll(player);
            messages().send(player, "practice.took-all", Messages.tag("count", removed));
            return Command.SINGLE_SUCCESS;
        }
        AugmentInfo info = game.findAugment(input);
        if (info == null) {
            messages().send(sender, "command.unknown-augment", Messages.tag("input", input));
            return 0;
        }
        if (!match.revoke(player, info)) {
            messages().send(sender, "practice.not-owned", Messages.tag("augment", OfferMenu.name(info)));
            return 0;
        }
        messages().send(player, "practice.took", Messages.tag("augment", OfferMenu.name(info)));
        Feedback.play(player, Sound.ITEM_BOOK_PAGE_TURN, 1f, 0.8f);
        return Command.SINGLE_SUCCESS;
    }

    private int cards(CommandSender sender, @Nullable String tierInput) {
        AugmentMatch match = practising(sender);
        if (match == null) {
            return 0;
        }
        AugmentTier tier;
        if (tierInput == null) {
            AugmentTier[] tiers = AugmentTier.values();
            tier = tiers[match.session().random().nextInt(tiers.length)];
        } else {
            tier = AugmentTier.parse(tierInput);
            if (tier == null) {
                messages().send(sender, "practice.unknown-tier", Messages.tag("input", tierInput));
                return 0;
            }
        }
        Player player = (Player) sender;
        int before = match.pendingOffers(player.getUniqueId());
        match.offer(player, tier, messages().get("source.practice"));
        return match.pendingOffers(player.getUniqueId()) > before ? Command.SINGLE_SUCCESS : 0;
    }

    private int next(CommandSender sender) {
        AugmentMatch match = practising(sender);
        if (match == null) {
            return 0;
        }
        AugmentMatch.PhaseStep step = match.advancePhase();
        if (step == AugmentMatch.PhaseStep.NONE) {
            messages().send(sender, "practice.next-none");
            return 0;
        }
        Component line = messages().prefixed("practice.next-" + step.name().toLowerCase(java.util.Locale.ROOT),
                Messages.tag("player", sender.name()));
        for (Player each : match.session().participants()) {
            each.sendMessage(line);
        }
        return Command.SINGLE_SUCCESS;
    }

    private int dummies(CommandSender sender, @Nullable Integer count) {
        AugmentMatch match = practising(sender);
        if (match == null) {
            return 0;
        }
        AugmentPracticeSettings practice = match.practiceSettings();
        int wanted = count != null ? count : practice != null ? practice.dummyCount() : 3;
        int placed = match.placeDummies((Player) sender, wanted);
        if (placed <= 0) {
            messages().send(sender, "practice.dummies-failed");
            return 0;
        }
        messages().send(sender, "practice.dummies-placed", Messages.tag("count", placed));
        return Command.SINGLE_SUCCESS;
    }

    private int showPvp(CommandSender sender) {
        AugmentMatch match = practising(sender);
        if (match == null) {
            return 0;
        }
        messages().send(sender, "practice.pvp-current", Messages.tag("state",
                messages().get(match.practicePvp() ? "practice.state-on" : "practice.state-off")));
        return Command.SINGLE_SUCCESS;
    }

    private int setPvp(CommandSender sender, boolean enabled) {
        AugmentMatch match = practising(sender);
        if (match == null) {
            return 0;
        }
        match.setPracticePvp(enabled);
        Component line = messages().prefixed(enabled ? "practice.pvp-on" : "practice.pvp-off",
                Messages.tag("player", sender.name()));
        for (Player each : match.session().participants()) {
            each.sendMessage(line);
        }
        return Command.SINGLE_SUCCESS;
    }
}
