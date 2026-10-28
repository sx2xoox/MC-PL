package io.github.sx2xoox.mcpl.game.augment.command;

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
import io.github.sx2xoox.mcpl.core.ui.Messages;
import io.github.sx2xoox.mcpl.game.augment.AugmentGame;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentInfo;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentRegistry;
import io.github.sx2xoox.mcpl.game.augment.api.AugmentTier;
import io.github.sx2xoox.mcpl.game.augment.api.Params;
import io.github.sx2xoox.mcpl.game.augment.bonus.BonusCondition;
import io.github.sx2xoox.mcpl.game.augment.config.AugmentConfig;
import io.github.sx2xoox.mcpl.game.augment.runtime.AugmentMatch;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /게임 증강} (English twin {@code /mcpl augment}):
 *
 * <pre>
 * /게임 증강                     reopen the card menu (a pick is waiting), else show my augments
 * /게임 증강 목록|list           every augment by tier (hover: text)
 * /게임 증강 정보|info &lt;증강&gt;   one augment
 * /게임 증강 보너스|bonus        the bonus conditions
 * /게임 증강 리롤|reroll         redraw the shown cards
 * </pre>
 */
public final class AugmentCommand {

    private final AugmentGame game;

    public AugmentCommand(AugmentGame game) {
        this.game = game;
    }

    private Messages messages() {
        return game.messages();
    }

    public void register(GameCommandRegistrar registrar) {
        for (String root : List.of("증강", "augment")) {
            boolean english = root.equals("augment");
            String augmentArg = english ? "augment" : "증강";
            LiteralArgumentBuilder<CommandSourceStack> node = Commands.literal(root)
                    .requires(registrar.play())
                    .executes(ctx -> open(ctx.getSource().getSender()));
            for (String name : List.of("목록", "list")) {
                node.then(Commands.literal(name).executes(ctx -> list(ctx.getSource().getSender())));
            }
            for (String name : List.of("정보", "info")) {
                node.then(Commands.literal(name)
                        .executes(ctx -> usage(ctx.getSource().getSender()))
                        .then(Commands.argument(augmentArg, StringArgumentType.greedyString())
                                .suggests(this::suggest)
                                .executes(ctx -> info(ctx.getSource().getSender(),
                                        StringArgumentType.getString(ctx, augmentArg)))));
            }
            for (String name : List.of("보너스", "bonus")) {
                node.then(Commands.literal(name).executes(ctx -> bonuses(ctx.getSource().getSender())));
            }
            for (String name : List.of("리롤", "reroll")) {
                node.then(Commands.literal(name).executes(ctx -> reroll(ctx.getSource().getSender())));
            }
            registrar.register(node);
        }
    }

    private CompletableFuture<Suggestions> suggest(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        String remaining = builder.getRemainingLowerCase().replace(" ", "");
        for (AugmentRegistry.Entry entry : game.registry().all()) {
            AugmentInfo info = entry.info();
            if (info.name().replace(" ", "").startsWith(remaining)) {
                builder.suggest(info.name());
            }
            if (info.id().startsWith(remaining)) {
                builder.suggest(info.id());
            }
        }
        return builder.buildFuture();
    }

    private int usage(CommandSender sender) {
        messages().send(sender, "command.usage-info");
        return 0;
    }

    /** Reopens the card menu if a pick is waiting, else lists the player's augments (or the help). */
    private int open(CommandSender sender) {
        AugmentMatch match = game.currentMatch();
        if (!(sender instanceof Player player) || match == null || !match.session().isParticipant(player)) {
            messages().lines("command.help").forEach(sender::sendMessage);
            return Command.SINGLE_SUCCESS;
        }
        if (match.openMenu(player)) {
            return Command.SINGLE_SUCCESS;
        }
        List<AugmentInfo> owned = match.augments(player.getUniqueId());
        if (owned.isEmpty()) {
            messages().send(player, "command.mine-none");
            return Command.SINGLE_SUCCESS;
        }
        messages().send(player, "command.mine-header", Messages.tag("count", owned.size()));
        for (AugmentInfo info : owned) {
            player.sendMessage(entry(info));
        }
        return Command.SINGLE_SUCCESS;
    }

    private int reroll(CommandSender sender) {
        AugmentMatch match = game.currentMatch();
        if (!(sender instanceof Player player) || match == null || match.currentOffer(player.getUniqueId()) == null) {
            messages().send(sender, "command.nothing-to-pick");
            return 0;
        }
        return match.reroll(player) ? Command.SINGLE_SUCCESS : 0;
    }

    private int list(CommandSender sender) {
        AugmentConfig.Snapshot snapshot = game.augments().snapshot();
        messages().sendPlain(sender, "command.list-header", Messages.tag("count", game.registry().all().size()));
        for (AugmentTier tier : AugmentTier.values()) {
            List<Component> names = new ArrayList<>();
            for (AugmentInfo info : game.registry().ofTier(tier)) {
                Component name = name(info, snapshot.params(info));
                if (!snapshot.isEnabled(info.id())) {
                    name = name.color(NamedTextColor.DARK_GRAY);
                }
                names.add(name);
            }
            sender.sendMessage(messages().get("command.list-tier", Messages.tag("tier", messages().get("tier." + tier.key())),
                    Messages.tag("augments", Component.join(JoinConfiguration.separator(Component.text(", ",
                            NamedTextColor.GRAY)), names))));
        }
        messages().sendPlain(sender, "command.list-footer");
        return Command.SINGLE_SUCCESS;
    }

    private int info(CommandSender sender, String input) {
        AugmentInfo info = game.findAugment(input);
        if (info == null) {
            messages().send(sender, "command.unknown-augment", Messages.tag("input", input));
            return 0;
        }
        AugmentConfig.Snapshot snapshot = game.augments().snapshot();
        sender.sendMessage(messages().get("command.info", Messages.tag("augment", Component.text(info.name(),
                        info.tier().color())), Messages.tag("tier", messages().get("tier." + info.tier().key())),
                Messages.tag("kind", info.kind().koreanName()), Messages.tag("id", info.id()),
                Messages.tag("description", snapshot.params(info).render(info.description())),
                Messages.tag("state", messages().get(snapshot.isEnabled(info.id()) ? "command.state-on"
                        : "command.state-off"))));
        return Command.SINGLE_SUCCESS;
    }

    private int bonuses(CommandSender sender) {
        AugmentConfig.Snapshot snapshot = game.augments().snapshot();
        messages().sendPlain(sender, "command.bonus-header");
        for (BonusCondition condition : BonusCondition.values()) {
            sender.sendMessage(messages().get(condition.firstCome() ? "command.bonus-entry-first" : "command.bonus-entry",
                    Messages.tag("condition", Component.text(condition.koreanName())),
                    Messages.tag("tier", messages().get("tier." + snapshot.tier(condition).key())),
                    Messages.tag("description", snapshot.params(condition).render(condition.description())),
                    Messages.tag("state", messages().get(snapshot.isEnabled(condition) ? "command.state-on"
                            : "command.state-off"))));
        }
        return Command.SINGLE_SUCCESS;
    }

    private Component name(AugmentInfo info, Params params) {
        return Component.text(info.name(), info.tier().color())
                .hoverEvent(HoverEvent.showText(Component.text(params.render(info.description()), NamedTextColor.GRAY)))
                .clickEvent(ClickEvent.runCommand("/게임 증강 정보 " + info.id()));
    }

    private Component entry(AugmentInfo info) {
        AugmentConfig.Snapshot snapshot = game.augments().snapshot();
        return messages().get("command.mine-entry", Messages.tag("augment", name(info, snapshot.params(info))),
                Messages.tag("description", snapshot.params(info).render(info.description())));
    }
}
