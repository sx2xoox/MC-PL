package io.github.sx2xoox.mcpl.game.siege.stats;

import java.io.IOException;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.github.sx2xoox.mcpl.core.ui.Format;
import io.github.sx2xoox.mcpl.core.ui.Messages;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.command.CommandSender;

/**
 * {@code /게임 공성전 통계 [봇] [피해|건물|승률|KDA] [쪽]} (admins only: hidden from everyone else): the running totals,
 * one line per ability, {@value #PAGE_SIZE} to a page; {@code 통계 초기화 확인} clears them. English twin:
 * {@code /mcpl siege stats [bots] [damage|structure|winrate|kda] [page]}, {@code stats reset confirm}.
 */
public final class SiegeStatsCommand {

    /** Rows per page: with the header and the next-page line one chat screen (10 lines). */
    public static final int PAGE_SIZE = 8;

    private final Messages messages;
    private final SiegeStatsStore store;
    private final Supplier<SiegeStatsSettings> settings;
    private final Function<String, String> abilityNames;
    private final Logger logger;

    public SiegeStatsCommand(Messages messages, SiegeStatsStore store, Supplier<SiegeStatsSettings> settings,
                             Function<String, String> abilityNames, Logger logger) {
        this.messages = messages;
        this.store = store;
        this.settings = settings;
        this.abilityNames = abilityNames;
        this.logger = logger;
    }

    /** The {@code 통계} ({@code stats} when {@code english}) node, usable by {@code admin} only. */
    public LiteralArgumentBuilder<CommandSourceStack> node(boolean english, Predicate<CommandSourceStack> admin) {
        String pageArg = english ? "page" : "쪽";
        LiteralArgumentBuilder<CommandSourceStack> stats = Commands.literal(english ? "stats" : "통계").requires(admin);
        sorted(stats, english, pageArg, false, SiegeStatsReport.Sort.DAMAGE);
        for (SiegeStatsReport.Sort sort : SiegeStatsReport.Sort.values()) {
            stats.then(sorted(Commands.literal(english ? sort.english() : sort.korean()), english, pageArg, false, sort));
        }
        LiteralArgumentBuilder<CommandSourceStack> bots = Commands.literal(english ? "bots" : "봇");
        sorted(bots, english, pageArg, true, SiegeStatsReport.Sort.DAMAGE);
        for (SiegeStatsReport.Sort sort : SiegeStatsReport.Sort.values()) {
            bots.then(sorted(Commands.literal(english ? sort.english() : sort.korean()), english, pageArg, true, sort));
        }
        stats.then(bots);
        stats.then(Commands.literal(english ? "reset" : "초기화")
                .executes(ctx -> resetHint(ctx.getSource().getSender(), english))
                .then(Commands.literal(english ? "confirm" : "확인")
                        .executes(ctx -> reset(ctx.getSource().getSender()))));
        return stats;
    }

    private <T extends ArgumentBuilder<CommandSourceStack, T>> T sorted(T node, boolean english, String pageArg,
                                                                       boolean bots, SiegeStatsReport.Sort sort) {
        return node.executes(ctx -> show(ctx.getSource().getSender(), english, bots, sort, 1))
                .then(Commands.argument(pageArg, IntegerArgumentType.integer(1))
                        .executes(ctx -> show(ctx.getSource().getSender(), english, bots, sort,
                                IntegerArgumentType.getInteger(ctx, pageArg))));
    }

    /** Sends the page of the ranking. */
    int show(CommandSender sender, boolean english, boolean bots, SiegeStatsReport.Sort sort, int page) {
        SiegeStatsStore.Totals totals;
        try {
            totals = store.load();
        } catch (IOException e) {
            logger.log(Level.WARNING, "Could not read the siege balance records", e);
            messages.send(sender, "stats.failed");
            return 0;
        }
        List<SiegeStatsReport.Row> rows = SiegeStatsReport.rows(bots ? totals.bots() : totals.abilities(),
                bots ? SiegeStatsCommand::botName : abilityNames, sort, settings.get());
        if (rows.isEmpty()) {
            messages.send(sender, "stats.empty");
            return Command.SINGLE_SUCCESS;
        }
        int pages = (rows.size() + PAGE_SIZE - 1) / PAGE_SIZE;
        int shown = Math.min(page, pages);
        sender.sendMessage(messages.get(bots ? "stats.header-bots" : "stats.header",
                Messages.tag("sort", messages.get("stats.sort." + sort.key())),
                Messages.tag("page", shown), Messages.tag("pages", pages)));
        for (SiegeStatsReport.Row row : rows.subList((shown - 1) * PAGE_SIZE, Math.min(rows.size(), shown * PAGE_SIZE))) {
            sender.sendMessage(line(row));
        }
        if (shown < pages) {
            String command = "/" + (english ? "mcpl siege stats " : "게임 공성전 통계 ")
                    + (bots ? (english ? "bots " : "봇 ") : "")
                    + (english ? sort.english() : sort.korean()) + " " + (shown + 1);
            sender.sendMessage(messages.get("stats.next").clickEvent(ClickEvent.runCommand(command)));
        }
        return Command.SINGLE_SUCCESS;
    }

    private Component line(SiegeStatsReport.Row row) {
        StatTotals totals = row.totals();
        String mark = switch (row.mark()) {
            case HIGH -> "stats.mark-high";
            case LOW -> "stats.mark-low";
            case NONE -> null;
        };
        return messages.get(row.enough() ? "stats.row" : "stats.row-few",
                Messages.tag("name", row.name()),
                Messages.tag("rounds", totals.rounds()),
                Messages.tag("damage", Format.number(totals.enemyPerMinute())),
                Messages.tag("structure", Format.number(totals.structurePerMinute())),
                Messages.tag("kda", String.format(java.util.Locale.ROOT, "%.1f", totals.kda())),
                Messages.tag("win", Math.round(totals.winRate() * 100)),
                Messages.tag("mark", mark == null ? Component.empty() : messages.get(mark)));
    }

    private int resetHint(CommandSender sender, boolean english) {
        String command = english ? "/mcpl siege stats reset confirm" : "/게임 공성전 통계 초기화 확인";
        sender.sendMessage(messages.prefixed("stats.reset-confirm", Messages.tag("command", command))
                .clickEvent(ClickEvent.suggestCommand(command)));
        return Command.SINGLE_SUCCESS;
    }

    private int reset(CommandSender sender) {
        try {
            store.reset();
        } catch (IOException e) {
            logger.log(Level.WARNING, "Could not clear the siege balance records", e);
            messages.send(sender, "stats.failed");
            return 0;
        }
        messages.send(sender, "stats.reset-done");
        return Command.SINGLE_SUCCESS;
    }

    private static String botName(String id) {
        io.github.sx2xoox.mcpl.game.siege.bot.BotJob job = io.github.sx2xoox.mcpl.game.siege.bot.BotJob.parse(id);
        return job == null ? id : job.koreanName();
    }
}
