package io.github.sx2xoox.mcpl.core.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.logging.Level;

import io.github.sx2xoox.mcpl.core.Hub;
import io.github.sx2xoox.mcpl.core.command.Permissions;
import io.github.sx2xoox.mcpl.core.config.BlockDamageScope;
import io.github.sx2xoox.mcpl.core.config.HubConfig;
import io.github.sx2xoox.mcpl.core.config.Setting;
import io.github.sx2xoox.mcpl.core.config.SettingsSection;
import io.github.sx2xoox.mcpl.core.game.MiniGame;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.jspecify.annotations.Nullable;

/**
 * The in-game settings: the hub's own entries plus every mini-game's ({@link MiniGame#settings()}), the change flow
 * shared by the settings menu ({@link SettingsMenu}) and {@code /게임 설정} (permission, a broken {@code config.yml},
 * feedback, refreshing open menus) and opening the menu. Owned by the {@link Hub}.
 */
public final class SettingsEditor {

    /** Where a change came from: commands answer in chat, the menu in the action bar with a sound. */
    public enum Origin {
        COMMAND,
        MENU
    }

    private final Hub hub;
    private final SettingsSection hubSection;

    public SettingsEditor(Hub hub) {
        this.hub = hub;
        this.hubSection = hubSection(hub.config(), hub.messages());
    }

    /** The hub's own section: team size, countdown, ability block damage. */
    private static SettingsSection hubSection(HubConfig config, Messages messages) {
        Setting team = Setting.builder("hub.team-size")
                .command("팀", "team")
                .icon(Material.WHITE_BANNER)
                .text(messages, "settings.hub.team")
                .path("match.team-size")
                .range(0, HubConfig.MAX_TEAM_SIZE).steps(1, 4).integer().unit(Setting.Unit.PLAYERS)
                .number(config::teamSize, value -> config.setTeamSize((int) value), 0);
        Setting countdown = Setting.builder("hub.countdown")
                .command("카운트다운", "countdown")
                .icon(Material.BELL)
                .text(messages, "settings.hub.countdown")
                .path("match.countdown-seconds")
                .range(0, HubConfig.MAX_COUNTDOWN_SECONDS).steps(1, 5).integer().unit(Setting.Unit.SECONDS)
                .number(config::countdownSeconds, value -> config.setCountdownSeconds((int) value), 5);
        Setting terrain = Setting.builder("hub.terrain")
                .command("지형파괴", "terrain")
                .icon(Material.TNT)
                .text(messages, "settings.hub.terrain")
                .path("protection.ability-block-damage")
                .choice(List.of(
                                new Setting.Choice.Option(BlockDamageScope.OFF.name(), List.of("끄기"), List.of("off")),
                                new Setting.Choice.Option(BlockDamageScope.MAP_WORLDS.name(), List.of("맵에서만"),
                                        List.of("maps", "map_worlds")),
                                new Setting.Choice.Option(BlockDamageScope.ALL_WORLDS.name(), List.of("모든월드"),
                                        List.of("all", "all_worlds"))),
                        () -> config.abilityBlockDamage().name(),
                        value -> {
                            BlockDamageScope scope = BlockDamageScope.parse(value);
                            if (scope != null) {
                                config.setAbilityBlockDamage(scope);
                            }
                        },
                        BlockDamageScope.MAP_WORLDS.name());
        return new SettingsSection("hub", Material.COMPARATOR, messages, "settings.hub.section",
                List.of(team, countdown, terrain));
    }

    /** Every section: the hub's first, then each mini-game's in registration order. */
    public List<SettingsSection> sections() {
        List<SettingsSection> sections = new ArrayList<>();
        sections.add(hubSection);
        for (MiniGame game : hub.games().games()) {
            try {
                sections.addAll(game.settings());
            } catch (RuntimeException e) {
                hub.plugin().getLogger().log(Level.WARNING, "Could not list the settings of " + game.id(), e);
            }
        }
        return sections;
    }

    /** The setting with this id, or null. */
    public @Nullable Setting find(String id) {
        for (SettingsSection section : sections()) {
            for (Setting setting : section.settings()) {
                if (setting.id().equals(id)) {
                    return setting;
                }
            }
        }
        return null;
    }

    private Messages messages() {
        return hub.messages();
    }

    /**
     * Applies a change for {@code sender}: refused without the admin permission or while {@code config.yml} cannot be
     * written; otherwise the setter saves it, the sender is told the new value (and that a running match keeps its
     * settings), the change is logged and every open settings menu shows it.
     *
     * @return true if the change was accepted (even if the value already was the requested one)
     */
    public boolean change(CommandSender sender, Setting setting, Supplier<Setting.Change> action, Origin origin) {
        if (!sender.hasPermission(Permissions.ADMIN)) {
            messages().send(sender, "settings.no-permission");
            return false;
        }
        if (!hub.config().isWritable()) {
            messages().send(sender, "error.config-broken");
            if (origin == Origin.MENU && sender instanceof Player player) {
                Feedback.play(player, Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
            }
            return false;
        }
        Setting.Change change = action.get();
        if (change.changed()) {
            hub.plugin().getLogger().info(sender.getName() + " changed " + setting.path() + " to "
                    + PlainTextComponentSerializer.plainText().serialize(setting.valueText(messages()))
                    + " (/게임 설정)");
            refreshMenus();
        }
        if (origin == Origin.MENU) {
            menuFeedback(sender, setting, change);
        } else {
            commandFeedback(sender, setting, change);
        }
        return true;
    }

    private boolean matchRunning() {
        return hub.games().session() != null;
    }

    private void commandFeedback(CommandSender sender, Setting setting, Setting.Change change) {
        Component name = setting.name();
        Component value = setting.valueText(messages());
        if (!change.changed()) {
            messages().send(sender, "settings.unchanged", Messages.tag("name", name), Messages.tag("value", value));
        } else {
            messages().send(sender, "settings.changed", Messages.tag("name", name), Messages.tag("value", value));
        }
        if (change.limit() != null && setting instanceof Setting.Number number) {
            messages().send(sender, "settings.clamped",
                    Messages.tag("min", number.format(messages(), number.min())),
                    Messages.tag("max", number.format(messages(), number.max())));
        }
        if (change.changed() && matchRunning()) {
            messages().send(sender, "command.applies-next-match");
        }
    }

    private void menuFeedback(CommandSender sender, Setting setting, Setting.Change change) {
        if (!(sender instanceof Player player)) {
            commandFeedback(sender, setting, change);
            return;
        }
        Component name = setting.name();
        Component value = setting.valueText(messages());
        if (!change.changed()) {
            String key = change.limit() == Setting.Limit.MAX ? "settings.menu.at-max"
                    : change.limit() == Setting.Limit.MIN ? "settings.menu.at-min" : "settings.menu.unchanged";
            player.sendActionBar(messages().get(key, Messages.tag("name", name), Messages.tag("value", value)));
            Feedback.play(player, Sound.BLOCK_NOTE_BLOCK_BASS, 0.7f, 0.7f);
            return;
        }
        String key = matchRunning() ? "settings.menu.changed-next-match" : "settings.menu.changed";
        player.sendActionBar(messages().get(key, Messages.tag("name", name), Messages.tag("value", value)));
        if (setting instanceof Setting.Toggle) {
            Feedback.play(player, Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f, change.direction() > 0 ? 1.6f : 0.9f);
        } else {
            Feedback.play(player, Sound.UI_BUTTON_CLICK, 0.6f, change.direction() >= 0 ? 1.3f : 0.9f);
        }
    }

    // =================================================================== menu

    /** Opens the settings menu for {@code player} (an admin). */
    public void open(Player player) {
        SettingsMenu menu = new SettingsMenu(this, player.getUniqueId());
        player.openInventory(menu.getInventory());
        Feedback.play(player, Sound.BLOCK_CHEST_OPEN, 0.5f, 1.4f);
    }

    /** Redraws every open settings menu (after a change, a reload or a match starting or ending). */
    public void refreshMenus() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            SettingsMenu menu = openMenu(player);
            if (menu != null) {
                try {
                    menu.render();
                } catch (RuntimeException e) {
                    hub.plugin().getLogger().log(Level.WARNING, "Could not redraw the settings menu", e);
                }
            }
        }
    }

    /** Closes every open settings menu (plugin disable). */
    public void closeMenus() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (openMenu(player) != null) {
                player.closeInventory();
            }
        }
    }

    /** The settings menu {@code player} is looking at, or null. */
    public static @Nullable SettingsMenu openMenu(Player player) {
        InventoryView view = player.getOpenInventory();
        Inventory top = view == null ? null : view.getTopInventory();
        return top != null && top.getHolder(false) instanceof SettingsMenu menu ? menu : null;
    }

    Hub hub() {
        return hub;
    }
}
