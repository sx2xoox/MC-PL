package io.github.sx2xoox.mcpl;

import io.github.sx2xoox.mcpl.core.Hub;
import io.github.sx2xoox.mcpl.core.arena.TempBlockService;
import io.github.sx2xoox.mcpl.core.combat.CombatTracker;
import io.github.sx2xoox.mcpl.core.game.GameManager;
import io.github.sx2xoox.mcpl.core.map.citadel.CitadelMap;
import io.github.sx2xoox.mcpl.core.ui.Messages;
import io.github.sx2xoox.mcpl.game.augment.AugmentGame;
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
        // Built-in battle maps; a map built before is loaded again here, before the first match needs its world.
        hub.maps().register(new CitadelMap());
        registerGames(hub.games());
        // only now: the first rotation match must follow rotation.games, not the registration order
        hub.games().startRotation();
    }

    /** Registers the built-in mini-games. Add new modes here. */
    private void registerGames(GameManager games) {
        SuperpowerGame superpower = new SuperpowerGame(hub);
        games.register(superpower);
        // 능력 연습 (solo practice of the superpower abilities); not in the default rotation.
        games.register(superpower.practice());
        // 증강 야생전: a fresh survival world per match with LoL/TFT-style augments.
        AugmentGame augment = new AugmentGame(hub);
        games.register(augment);
        // 증강 연습 (solo practice of the augments in a fresh world); not in the default rotation.
        games.register(augment.practice());
        // 증강 봇전 (a real match where bots fill the lobby); not in the default rotation.
        games.register(augment.botGame());
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
