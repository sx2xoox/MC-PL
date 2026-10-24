package io.github.sx2xoox.mcpl.core;

import java.io.File;
import java.util.logging.Level;

import io.github.sx2xoox.mcpl.core.arena.GameRuleGuard;
import io.github.sx2xoox.mcpl.core.arena.TempBlockService;
import io.github.sx2xoox.mcpl.core.combat.CombatTracker;
import io.github.sx2xoox.mcpl.core.command.GameCommand;
import io.github.sx2xoox.mcpl.core.config.HubConfig;
import io.github.sx2xoox.mcpl.core.game.GameManager;
import io.github.sx2xoox.mcpl.core.map.MapService;
import io.github.sx2xoox.mcpl.core.player.SnapshotStore;
import io.github.sx2xoox.mcpl.core.ui.Messages;
import io.github.sx2xoox.mcpl.core.ui.SettingsEditor;
import io.github.sx2xoox.mcpl.core.ui.SettingsMenuListener;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * The game hub shared by every mini-game: one object holding all core services. Created and driven by the plugin
 * ({@code MCPLPlugin#hub()}); modes receive it in their constructor or via {@code GameSession#hub()}.
 *
 * <ul>
 *     <li>{@link #config()} — typed {@code config.yml} (+ {@code games.<id>} sections)</li>
 *     <li>{@link #messages()} — {@code messages.yml} (Korean MiniMessage)</li>
 *     <li>{@link #games()} — mini-game registry, the running session, participation, rotation</li>
 *     <li>{@link #snapshots()} — persisted pre-match player state</li>
 *     <li>{@link #tempBlocks()} — temporary blocks restored automatically</li>
 *     <li>{@link #combat()} — kill credit / entity ownership</li>
 *     <li>{@link #gameRules()} — temporary world game rules</li>
 *     <li>{@link #settings()} — the in-game settings menu and {@code /게임 설정} entries</li>
 * </ul>
 */
public final class Hub {

    private final JavaPlugin plugin;
    private final HubConfig config;
    private final Messages messages;
    private final SnapshotStore snapshots;
    private final TempBlockService tempBlocks;
    private final GameRuleGuard gameRules;
    private final CombatTracker combat;
    private final MapService maps;
    private final GameManager games;
    private final GameCommand command;
    private final HubListener listener;
    private final SettingsEditor settings;
    private final SettingsMenuListener settingsListener;
    private boolean enabled;

    public Hub(JavaPlugin plugin) {
        this.plugin = plugin;
        File data = plugin.getDataFolder();
        this.config = new HubConfig(plugin);
        this.messages = new Messages(plugin, "messages.yml");
        this.snapshots = new SnapshotStore(plugin, new File(data, "snapshots"));
        this.tempBlocks = new TempBlockService(plugin, new File(data, "tempblocks.yml"));
        this.gameRules = new GameRuleGuard(plugin, new File(data, "gamerules.yml"));
        this.combat = new CombatTracker();
        this.maps = new MapService(plugin);
        this.games = new GameManager(this);
        this.command = new GameCommand(this);
        this.listener = new HubListener(this);
        this.settings = new SettingsEditor(this);
        this.settingsListener = new SettingsMenuListener(this);
    }

    /**
     * Loads files, repairs anything left over from an unclean shutdown (temporary blocks, game rules, snapshots of
     * online players), registers listeners and the {@code /게임} command. Register mini-games right after this, then
     * call {@link GameManager#startRotation()}.
     */
    public void enable() {
        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            plugin.getLogger().warning("Could not create " + plugin.getDataFolder());
        }
        config.load();
        messages.load();
        snapshots.init();
        gameRules.recover();
        gameRules.start();
        tempBlocks.recover();
        tempBlocks.start();
        Bukkit.getPluginManager().registerEvents(listener, plugin);
        Bukkit.getPluginManager().registerEvents(settingsListener, plugin);
        maps.enable();
        for (Player player : Bukkit.getOnlinePlayers()) {
            listener.restorePending(player);
        }
        command.register();
        enabled = true;
        games.enable();
    }

    /** Aborts any match and restores players, temporary blocks and game rules synchronously. */
    public void disable() {
        if (!enabled) {
            return;
        }
        enabled = false;
        try {
            settings.closeMenus();
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Could not close the settings menus", e);
        }
        try {
            games.shutdown();
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.SEVERE, "Error while stopping the running match", e);
        }
        tempBlocks.shutdown();
        gameRules.shutdown();
        maps.disable();
        HandlerList.unregisterAll(listener);
        HandlerList.unregisterAll(settingsListener);
        combat.clear();
    }

    /** {@code /게임 리로드}: re-reads {@code config.yml} and {@code messages.yml} and notifies the modes. */
    public void reload() {
        config.load();
        messages.load();
        games.reload();
        settings.refreshMenus();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public JavaPlugin plugin() {
        return plugin;
    }

    public HubConfig config() {
        return config;
    }

    public Messages messages() {
        return messages;
    }

    public GameManager games() {
        return games;
    }

    public SnapshotStore snapshots() {
        return snapshots;
    }

    public TempBlockService tempBlocks() {
        return tempBlocks;
    }

    public GameRuleGuard gameRules() {
        return gameRules;
    }

    /** The battle maps and their worlds. */
    public MapService maps() {
        return maps;
    }

    public CombatTracker combat() {
        return combat;
    }

    /** The in-game settings: the menu ({@code /게임 설정}) and the entries of the hub and every mini-game. */
    public SettingsEditor settings() {
        return settings;
    }

    /** A key in this plugin's namespace (attribute modifiers, PDC, ...). Modifiers in it are stripped on restore. */
    public NamespacedKey key(String value) {
        return new NamespacedKey(plugin, value);
    }
}
