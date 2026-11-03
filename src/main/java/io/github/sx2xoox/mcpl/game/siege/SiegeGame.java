package io.github.sx2xoox.mcpl.game.siege;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.logging.Level;

import io.github.sx2xoox.mcpl.core.Hub;
import io.github.sx2xoox.mcpl.core.arena.Arena;
import io.github.sx2xoox.mcpl.core.command.GameCommandRegistrar;
import io.github.sx2xoox.mcpl.core.config.SettingsSection;
import io.github.sx2xoox.mcpl.core.game.GameInstance;
import io.github.sx2xoox.mcpl.core.game.GameSession;
import io.github.sx2xoox.mcpl.core.game.GameStartException;
import io.github.sx2xoox.mcpl.core.game.MiniGame;
import io.github.sx2xoox.mcpl.core.map.ArenaMap;
import io.github.sx2xoox.mcpl.core.map.MapService;
import io.github.sx2xoox.mcpl.core.map.lanes.LaneSites;
import io.github.sx2xoox.mcpl.core.ui.Messages;
import io.github.sx2xoox.mcpl.game.siege.balance.SiegeBalanceSettings;
import io.github.sx2xoox.mcpl.game.siege.tower.ModelTowerLook;
import io.github.sx2xoox.mcpl.game.siege.tower.TowerLook;
import io.github.sx2xoox.mcpl.game.superpower.SuperpowerGame;
import io.github.sx2xoox.mcpl.game.superpower.basic.BasicAttackSettings;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.jspecify.annotations.Nullable;

/**
 * 초능력 공성전 — two sides (BLUE, RED) push two lanes of towers to the enemy core with the 초능력전쟁 abilities, best of
 * three rounds. See {@code docs/siege.md}. The abilities run through a hosted
 * {@link io.github.sx2xoox.mcpl.game.superpower.runtime.SuperpowerMatch}
 * ({@link io.github.sx2xoox.mcpl.game.superpower.runtime.MatchHost}); the map comes from the registered map with id
 * {@value #MAP_ID} (a {@link LaneSites}), or a plain fallback layout around the hub's arena. Features such as minions,
 * the jungle, gold and the shop plug in as {@link SiegeModule}s ({@link #addModule}).
 */
public final class SiegeGame implements MiniGame {

    public static final String ID = "siege";
    public static final String MAP_ID = "toykingdom";
    public static final String MESSAGES_FILE = "siege.yml";

    private final Hub hub;
    private final SuperpowerGame superpower;
    private final Messages messages;
    private TowerLook towerLook;
    private @Nullable LaneSites sitesOverride;
    private final List<Function<SiegeMatch, SiegeModule>> moduleFactories = new ArrayList<>();
    private @Nullable List<SettingsSection> settings;
    private @Nullable SiegeMatch current;

    public SiegeGame(Hub hub, SuperpowerGame superpower) {
        this.hub = hub;
        this.superpower = superpower;
        this.messages = new Messages(hub.plugin(), MESSAGES_FILE, hub.messages());
        this.messages.load();
        this.towerLook = new ModelTowerLook(hub.plugin(), hub.resourcePacks());
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "공성전";
    }

    @Override
    public Component displayComponent() {
        return messages.get("game.name");
    }

    @Override
    public List<String> aliases() {
        return List.of("초능력공성전", "공성", "siege");
    }

    @Override
    public Material icon() {
        return Material.END_CRYSTAL;
    }

    @Override
    public List<Component> description() {
        return messages.lines("game.description");
    }

    /** The towers are 3D models of the MCPL pack: admins hear at the start who has not loaded it. */
    @Override
    public boolean usesResourcePack() {
        return true;
    }

    /** One player may start it alone to try it out (the other team stays empty, see {@link SiegeMatch#isSolo}). */
    @Override
    public int minPlayers() {
        return 1;
    }

    @Override
    public boolean supportsTeams() {
        return true;
    }

    @Override
    public void configDefaults(ConfigurationSection defaults) {
        SiegeSettings.declareDefaults(defaults);
        SiegeProgressSettings.declareDefaults(defaults);
        SiegeBalanceSettings.declareDefaults(defaults);
        BasicAttackSettings.declareDefaults(defaults, SiegeMatch.BASIC_ATTACKS);
    }

    @Override
    public GameInstance createInstance(GameSession session) {
        SiegeMatch match = new SiegeMatch(this, session);
        current = match;
        return match;
    }

    /** The 공성전 map when it is registered and built (its own arena), else the hub's arena. */
    @Override
    public @Nullable Arena createArena() throws GameStartException {
        if (sitesOverride != null) {
            return null;
        }
        MapService maps = hub.maps();
        ArenaMap map = maps.map(MAP_ID);
        if (!(map instanceof LaneSites)) {
            return null;
        }
        World world = maps.world(map);
        if (world == null || !maps.isBuilt(map)) {
            throw new GameStartException(messages.prefixed("start.map-not-built",
                    Messages.tag("id", MAP_ID)));
        }
        return Arena.around(maps.center(map, world), map.playRadius());
    }

    /** Where the structures, spawns and lanes are in the match's arena. */
    LaneLayout layoutFor(Arena arena) {
        Location center = arena.center();
        if (sitesOverride != null) {
            return new LaneLayout(sitesOverride, arena.world(), center.getBlockX(), center.getBlockZ());
        }
        ArenaMap map = hub.maps().map(MAP_ID);
        if (map instanceof LaneSites sites && arena.world().equals(hub.maps().world(map))) {
            return new LaneLayout(sites, arena.world(), 0, 0);
        }
        return new LaneLayout(new FallbackLaneSites(center.getBlockY()), arena.world(), center.getBlockX(),
                center.getBlockZ());
    }

    /**
     * Plugs a feature into every match started from now on: {@code factory} makes the match's {@link SiegeModule} when
     * the match starts (after the abilities, before the first round).
     */
    public void addModule(Function<SiegeMatch, SiegeModule> factory) {
        moduleFactories.add(factory);
    }

    /** The modules of a starting match (a factory that fails is skipped and logged). */
    List<SiegeModule> createModules(SiegeMatch match) {
        List<SiegeModule> out = new ArrayList<>();
        for (Function<SiegeMatch, SiegeModule> factory : moduleFactories) {
            try {
                out.add(factory.apply(match));
            } catch (RuntimeException e) {
                hub.plugin().getLogger().log(Level.WARNING, "A siege module could not start", e);
            }
        }
        return out;
    }

    @Override
    public List<SettingsSection> settings() {
        if (settings == null) {
            settings = SiegeSettingEntries.create(this);
        }
        return settings;
    }

    @Override
    public void registerCommands(GameCommandRegistrar registrar) {
        new SiegeCommand(this).register(registrar);
    }

    @Override
    public void onReload() {
        messages.load();
    }

    @Override
    public void onShutdown() {
        current = null;
    }

    // =================================================================== shared state

    public Hub hub() {
        return hub;
    }

    /** The 초능력전쟁 mode whose abilities, ability settings and items this mode uses. */
    public SuperpowerGame superpower() {
        return superpower;
    }

    public Messages messages() {
        return messages;
    }

    /**
     * How towers and cores look (default: {@link ModelTowerLook}, vanilla blocks for players without the pack); used by
     * matches started afterwards.
     */
    public TowerLook towerLook() {
        return towerLook;
    }

    /** Replaces the tower look (e.g. a 3D model look with the resource pack). */
    public void towerLook(TowerLook look) {
        this.towerLook = look;
    }

    /**
     * Plays the next matches on {@code sites} around the hub's arena center instead of the 공성전 map (tests, custom
     * layouts); null to go back to the map.
     */
    public void sitesOverride(@Nullable LaneSites sites) {
        this.sitesOverride = sites;
    }

    /** The running match of this mode, or null. */
    public @Nullable SiegeMatch currentMatch() {
        SiegeMatch match = current;
        GameSession session = hub.games().session();
        if (match == null || session == null || session.instance() != match) {
            return null;
        }
        return match;
    }

    void matchEnded(SiegeMatch match) {
        if (current == match) {
            current = null;
        }
    }

    /** {@code games.siege} of {@code config.yml}. */
    public ConfigurationSection config() {
        return hub.config().gameSection(ID);
    }

    /** The configured ability pool (ids as written, lower case). */
    public List<String> abilityPool() {
        List<String> pool = new ArrayList<>();
        for (String id : config().getStringList(SiegeSettings.ABILITY_POOL)) {
            String trimmed = id.trim().toLowerCase(Locale.ROOT);
            if (!trimmed.isEmpty() && !pool.contains(trimmed)) {
                pool.add(trimmed);
            }
        }
        return pool;
    }

    /** Saves the ability pool to {@code config.yml} (the next round uses it). */
    public void abilityPool(List<String> pool) {
        hub.config().setGameValue(ID, SiegeSettings.ABILITY_POOL, List.copyOf(pool));
    }
}
