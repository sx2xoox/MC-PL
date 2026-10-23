package io.github.sx2xoox.mcpl.core.command;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import java.util.function.Supplier;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.github.sx2xoox.mcpl.core.Hub;
import io.github.sx2xoox.mcpl.core.config.HubConfig;
import io.github.sx2xoox.mcpl.core.config.ProtectionSettings;
import io.github.sx2xoox.mcpl.core.config.Setting;
import io.github.sx2xoox.mcpl.core.config.SettingsSection;
import io.github.sx2xoox.mcpl.core.ui.Format;
import io.github.sx2xoox.mcpl.core.ui.Messages;
import io.github.sx2xoox.mcpl.core.ui.SettingsEditor;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /게임 설정} (English twin {@code /mcpl settings}), admin only:
 *
 * <pre>
 * /게임 설정                       players: the settings menu; console: the current values
 * /게임 설정 목록|list             the current values, one clickable line each (click = type the command)
 * /게임 설정 메뉴|menu             the settings menu
 * /게임 설정 &lt;이름&gt;               one value and how to change it
 * /게임 설정 &lt;이름&gt; &lt;값&gt;          change it (saved to config.yml at once; a running match keeps its settings)
 * </pre>
 * The names and values come from the {@link Setting}s of the hub and every mini-game, e.g. {@code 팀 2},
 * {@code 지형파괴 맵에서만}, {@code 상자 끄기}, {@code 자기장 시작 90} ({@code team 2}, {@code terrain maps},
 * {@code loot off}, {@code border delay 90}). Numbers outside the range are clamped.
 */
public final class SettingsCommand {

    private static final List<String> LIST = List.of("목록", "list");
    private static final List<String> MENU = List.of("메뉴", "menu");
    private static final List<String> ON = List.of("켜기", "on");
    private static final List<String> OFF = List.of("끄기", "off");

    private final Hub hub;

    public SettingsCommand(Hub hub) {
        this.hub = hub;
    }

    private Messages messages() {
        return hub.messages();
    }

    private SettingsEditor editor() {
        return hub.settings();
    }

    /** The {@code literal} node ({@code 설정} or {@code settings}) with every setting below it. */
    public LiteralArgumentBuilder<CommandSourceStack> build(String literal, Predicate<CommandSourceStack> admin) {
        LiteralArgumentBuilder<CommandSourceStack> node = Commands.literal(literal).requires(admin)
                .executes(ctx -> openOrList(ctx.getSource().getSender()));
        for (String name : LIST) {
            node.then(Commands.literal(name).executes(ctx -> list(ctx.getSource().getSender())));
        }
        for (String name : MENU) {
            node.then(Commands.literal(name).executes(ctx -> open(ctx.getSource().getSender())));
        }
        Set<String> taken = new HashSet<>(LIST);
        taken.addAll(MENU);
        for (SettingsSection section : editor().sections()) {
            for (Setting setting : section.settings()) {
                for (boolean english : new boolean[] {false, true}) {
                    List<String> words = english ? setting.english() : setting.korean();
                    if (!taken.add(String.join(" ", words))) {
                        hub.plugin().getLogger().warning("Setting " + setting.id() + ": the command name '"
                                + String.join(" ", words) + "' is already used; only the menu shows it");
                        continue;
                    }
                    node.then(chain(setting, words, english));
                }
            }
        }
        return node;
    }

    /** {@code words[0] -> ... -> words[n-1] -> value}; nodes of shared first words are merged by Brigadier. */
    private LiteralArgumentBuilder<CommandSourceStack> chain(Setting setting, List<String> words, boolean english) {
        LiteralArgumentBuilder<CommandSourceStack> current = Commands.literal(words.getLast())
                .executes(ctx -> show(ctx.getSource().getSender(), setting, english));
        addValues(current, setting, english);
        for (int i = words.size() - 2; i >= 0; i--) {
            List<String> prefix = words.subList(0, i + 1);
            current = Commands.literal(words.get(i))
                    .executes(ctx -> listGroup(ctx.getSource().getSender(), prefix, english))
                    .then(current);
        }
        return current;
    }

    private void addValues(LiteralArgumentBuilder<CommandSourceStack> node, Setting setting, boolean english) {
        switch (setting) {
            case Setting.Toggle toggle -> {
                for (String word : ON) {
                    node.then(Commands.literal(word).executes(ctx -> change(ctx, toggle, () -> toggle.set(true))));
                }
                for (String word : OFF) {
                    node.then(Commands.literal(word).executes(ctx -> change(ctx, toggle, () -> toggle.set(false))));
                }
            }
            case Setting.Number number -> {
                String arg = number.argumentName(english);
                if (number.integer()) {
                    node.then(Commands.argument(arg, IntegerArgumentType.integer())
                            .suggests((ctx, builder) -> suggest(number, builder))
                            .executes(ctx -> change(ctx, number,
                                    () -> number.set(IntegerArgumentType.getInteger(ctx, arg)))));
                } else {
                    node.then(Commands.argument(arg, DoubleArgumentType.doubleArg())
                            .suggests((ctx, builder) -> suggest(number, builder))
                            .executes(ctx -> change(ctx, number,
                                    () -> number.set(DoubleArgumentType.getDouble(ctx, arg)))));
                }
            }
            case Setting.Choice choice -> {
                for (Setting.Choice.Option option : choice.options()) {
                    Set<String> words = new LinkedHashSet<>(option.korean());
                    words.addAll(option.english());
                    for (String word : words) {
                        node.then(Commands.literal(word).executes(ctx -> change(ctx, choice, () -> choice.set(option))));
                    }
                }
            }
        }
    }

    /** Suggests the current value, the default and both ends of the range. */
    private static CompletableFuture<Suggestions> suggest(Setting.Number number, SuggestionsBuilder builder) {
        String remaining = builder.getRemaining();
        Set<String> values = new LinkedHashSet<>();
        for (double value : new double[] {number.value(), number.defaultValue(), number.min(), number.max()}) {
            values.add(Format.decimal(value));
        }
        for (String value : values) {
            if (value.startsWith(remaining)) {
                builder.suggest(value);
            }
        }
        return builder.buildFuture();
    }

    private int change(CommandContext<CommandSourceStack> ctx, Setting setting, Supplier<Setting.Change> action) {
        boolean accepted = editor().change(ctx.getSource().getSender(), setting, action, SettingsEditor.Origin.COMMAND);
        return accepted ? Command.SINGLE_SUCCESS : 0;
    }

    // =================================================================== output

    private int openOrList(CommandSender sender) {
        if (sender instanceof Player player) {
            editor().open(player);
            return Command.SINGLE_SUCCESS;
        }
        return list(sender);
    }

    private int open(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            messages().send(sender, "error.player-only");
            return 0;
        }
        editor().open(player);
        return Command.SINGLE_SUCCESS;
    }

    /** The usage of {@code setting}, e.g. {@code /게임 설정 상자 <켜기|끄기>}. */
    public static String usage(Setting setting, boolean english) {
        return prefix(english) + String.join(" ", english ? setting.english() : setting.korean()) + " "
                + setting.usageHint(english);
    }

    /** {@code /게임 설정 } or {@code /mcpl settings }. */
    private static String prefix(boolean english) {
        return english ? "/" + GameCommand.ALIASES.getFirst() + " settings " : "/" + GameCommand.ROOT + " 설정 ";
    }

    /** {@code /게임 설정 목록}: every section and value, each line typing its command when clicked. */
    private int list(CommandSender sender) {
        messages().sendPlain(sender, "settings.list.header");
        for (SettingsSection section : editor().sections()) {
            if (section.settings().isEmpty()) {
                continue;
            }
            messages().sendPlain(sender, "settings.list.section", Messages.tag("section", section.title()));
            for (Setting setting : section.settings()) {
                sender.sendMessage(line(setting, false));
            }
        }
        HubConfig config = hub.config();
        messages().sendPlain(sender, "settings.list.footer",
                Messages.tag("ending", config.endingSeconds()),
                Messages.tag("rotation", messages().get(config.rotationEnabled() ? "settings.on" : "settings.off")),
                Messages.tag("intermission", config.intermissionSeconds()),
                Messages.tag("radius", config.arenaRadius()),
                Messages.tag("protection", protectionSummary(config.protection())));
        return Command.SINGLE_SUCCESS;
    }

    /** The settings whose command starts with {@code prefix} (e.g. {@code /게임 설정 자기장}). */
    private int listGroup(CommandSender sender, List<String> prefix, boolean english) {
        for (SettingsSection section : editor().sections()) {
            for (Setting setting : section.settings()) {
                List<String> words = english ? setting.english() : setting.korean();
                if (words.size() > prefix.size() && words.subList(0, prefix.size()).equals(prefix)) {
                    sender.sendMessage(line(setting, english));
                }
            }
        }
        return Command.SINGLE_SUCCESS;
    }

    /** One list line: name and value; hover shows the explanation, click types the command. */
    private Component line(Setting setting, boolean english) {
        Component line = messages().get("settings.list.entry", Messages.tag("name", setting.name()),
                Messages.tag("value", setting.valueText(messages())));
        String command = prefix(english) + String.join(" ", english ? setting.english() : setting.korean()) + " ";
        return line.clickEvent(ClickEvent.suggestCommand(command)).hoverEvent(HoverEvent.showText(hover(setting,
                english)));
    }

    private Component hover(Setting setting, boolean english) {
        List<Component> lines = new ArrayList<>(setting.description());
        lines.add(Component.empty());
        lines.add(messages().get("settings.list.hover", Messages.tag("usage", usage(setting, english))));
        return Component.join(JoinConfiguration.newlines(), lines);
    }

    /** {@code /게임 설정 <이름>}: the value and how to change it. */
    private int show(CommandSender sender, Setting setting, boolean english) {
        Component message = messages().prefixed("settings.current", Messages.tag("name", setting.name()),
                Messages.tag("value", setting.valueText(messages())),
                Messages.tag("usage", usage(setting, english)));
        sender.sendMessage(message.hoverEvent(HoverEvent.showText(hover(setting, english)))
                .clickEvent(ClickEvent.suggestCommand(prefix(english)
                        + String.join(" ", english ? setting.english() : setting.korean()) + " ")));
        return Command.SINGLE_SUCCESS;
    }

    /** {@code "7/9"}: how many of the world protection switches are on. */
    static String protectionSummary(ProtectionSettings p) {
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
}
