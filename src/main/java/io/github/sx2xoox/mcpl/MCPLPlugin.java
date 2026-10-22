package io.github.sx2xoox.mcpl;

import io.github.sx2xoox.mcpl.core.Hub;
import io.github.sx2xoox.mcpl.core.arena.TempBlockService;
import io.github.sx2xoox.mcpl.core.combat.CombatTracker;
import io.github.sx2xoox.mcpl.core.game.GameManager;
import io.github.sx2xoox.mcpl.core.ui.Messages;
import io.github.sx2xoox.mcpl.game.superpower.SuperpowerGame;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Plugin entry point. Creates the game {@link Hub} (shared by every mini-game) and registers the mini-games.
 *
 * <p>The plugin instance is the only static-like singleton; everything else is reached through {@link #hub()}.
 * (Not {@code final}: MockBukkit proxies the plugin class in tests.)
 */
public class MCPLPlugin extends JavaPlugin {

    private Hub hub;

    @Override
    public void onEnable() {
        hub = new Hub(this);
        hub.enable();
        registerGames(hub.games());
    }

    /** Registers the built-in mini-games. Add new modes here. */
    private void registerGames(GameManager games) {
        games.register(new SuperpowerGame(hub));
    }

    @Override
    public void onDisable() {
        if (hub != null) {
            hub.disable();
        }
    }

    /** The game hub (config, messages, games, snapshots, temp blocks, combat tracker, ...). */
    public Hub hub() {
        return hub;
    }

    public GameManager games() {
        return hub.games();
    }

    public Messages messages() {
        return hub.messages();
    }

    public TempBlockService tempBlocks() {
        return hub.tempBlocks();
    }

    public CombatTracker combat() {
        return hub.combat();
    }
}
