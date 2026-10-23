package io.github.sx2xoox.mcpl.core.config;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;

/**
 * Typed, cached view of {@code config.yml}. Values are read once per {@link #load()} (startup and
 * {@code /게임 리로드}); setters write through to the file immediately.
 *
 * <p>Mini-games keep their own settings under {@code games.<id>.*}: declare defaults with
 * {@link io.github.sx2xoox.mcpl.core.game.MiniGame#configDefaults(ConfigurationSection)} and read them with
 * {@link #gameSection(String)}. Do not cache the returned section across reloads; call it again instead.
 */
public final class HubConfig {

    /** Version of the config layout written by this build. Bump it and add a step to {@link #migrate} on change. */
    public static final int CURRENT_VERSION = 1;

    public static final int MIN_RADIUS = 8;
    public static final int MAX_RADIUS = 1000;
    public static final int MAX_TEAM_SIZE = 32;
    public static final int MAX_COUNTDOWN_SECONDS = 60;
    /** Default and upper bound of {@code protection.ability-block-damage-limit}. */
    public static final int DEFAULT_BLOCK_DAMAGE_LIMIT = 20_000;
    public static final int MAX_BLOCK_DAMAGE_LIMIT = 1_000_000;

    private final JavaPlugin plugin;

    private int countdownSeconds = 5;
    private int endingSeconds = 10;
    private int teamSize;
    private boolean fireworks = true;
    private boolean rotationEnabled;
    private List<String> rotationGames = List.of();
    private int intermissionSeconds = 30;
    private String arenaWorld = "";
    private double arenaX;
    private double arenaY = 64;
    private double arenaZ;
    private int arenaRadius = 60;
    private ProtectionSettings protection = ProtectionSettings.ALL;
    private BlockDamageScope abilityBlockDamage = BlockDamageScope.MAP_WORLDS;
    private int abilityBlockDamageLimit = DEFAULT_BLOCK_DAMAGE_LIMIT;
    private boolean loadedOnce;
    private boolean writable = true;
    private boolean warnedUnwritable;
    private long lastBrokenBackup = -1;

    public HubConfig(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * (Re)loads {@code config.yml}, writing the default file on first run, migrating and adding missing keys.
     *
     * <p>A file that fails to parse (a tab, a bad indent, an unquoted {@code :}) is never overwritten: it is copied to
     * {@code config.yml.broken-<time>}, the built-in defaults (startup) or the previous values (reload) stay in effect
     * and {@link #isWritable()} is false until a later load succeeds.
     *
     * @throws IllegalStateException on a reload ({@code /게임 리로드}) of a file with a syntax error
     */
    public void load() {
        plugin.saveDefaultConfig();
        File file = new File(plugin.getDataFolder(), "config.yml");
        String parseError = probe(file);
        if (parseError != null) {
            writable = false;
            warnedUnwritable = false;
            plugin.getLogger().severe("config.yml has a syntax error and was NOT loaded: " + parseError);
            backupBroken(file);
            if (loadedOnce) {
                plugin.getLogger().severe("Keeping the previous settings. Fix config.yml and run /게임 리로드 again;"
                        + " nothing is saved to config.yml until then.");
                throw new IllegalStateException("config.yml could not be parsed: " + parseError);
            }
            plugin.getLogger().severe("Using the built-in defaults. Fix config.yml and run /게임 리로드;"
                    + " nothing is saved to config.yml until then.");
            plugin.reloadConfig(); // an empty configuration with the jar defaults attached
            read(plugin.getConfig());
            loadedOnce = true;
            return;
        }
        plugin.reloadConfig();
        writable = true;
        loadedOnce = true;
        FileConfiguration config = plugin.getConfig();
        boolean dirty = migrate(config);
        dirty |= addMissingDefaults(config);
        read(config);
        if (dirty) {
            save();
        }
    }

    /** Parses the file on its own; returns the error (with line and column), or null if it is fine or missing. */
    private static @Nullable String probe(File file) {
        if (!file.isFile()) {
            return null;
        }
        try {
            new YamlConfiguration().load(file);
            return null;
        } catch (IOException | InvalidConfigurationException e) {
            return String.valueOf(e.getMessage());
        }
    }

    private void backupBroken(File file) {
        long modified = file.lastModified();
        if (modified == lastBrokenBackup) {
            return; // this version of the file was already copied
        }
        File copy = new File(file.getParentFile(), file.getName() + ".broken-" + System.currentTimeMillis());
        try {
            Files.copy(file.toPath(), copy.toPath(), StandardCopyOption.REPLACE_EXISTING);
            lastBrokenBackup = modified;
            plugin.getLogger().severe("A copy of the broken file was saved as " + copy.getName());
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Could not copy the broken config.yml", e);
        }
    }

    /**
     * False while {@code config.yml} has a syntax error: settings then live in memory only and nothing is saved, so the
     * owner's file is never overwritten. Commands that change settings refuse while this is false.
     */
    public boolean isWritable() {
        return writable;
    }

    private boolean migrate(FileConfiguration config) {
        int version = config.getInt("config-version", 0);
        if (version > CURRENT_VERSION) {
            plugin.getLogger().warning("config.yml is from a newer MCPL version (" + version + "); using it as is.");
            return false;
        }
        if (version == CURRENT_VERSION) {
            return false;
        }
        // Future layout changes go here, e.g. if (version < 2) { rename keys; }
        config.set("config-version", CURRENT_VERSION);
        return true;
    }

    private static boolean addMissingDefaults(FileConfiguration config) {
        Configuration defaults = config.getDefaults();
        if (defaults == null) {
            return false;
        }
        boolean changed = false;
        for (String key : defaults.getKeys(true)) {
            if (config.isSet(key)) {
                continue;
            }
            if (defaults.isConfigurationSection(key)) {
                if (!config.isConfigurationSection(key)) {
                    config.createSection(key);
                    config.setComments(key, defaults.getComments(key));
                    changed = true;
                }
                continue;
            }
            config.set(key, defaults.get(key));
            config.setComments(key, defaults.getComments(key));
            changed = true;
        }
        return changed;
    }

    private void read(FileConfiguration config) {
        countdownSeconds = clamp(config.getInt("match.countdown-seconds", 5), 0, MAX_COUNTDOWN_SECONDS);
        endingSeconds = clamp(config.getInt("match.ending-seconds", 10), 0, 120);
        teamSize = clamp(config.getInt("match.team-size", 0), 0, MAX_TEAM_SIZE);
        fireworks = config.getBoolean("match.fireworks", true);

        rotationEnabled = config.getBoolean("rotation.enabled", false);
        List<String> games = new ArrayList<>();
        for (String id : config.getStringList("rotation.games")) {
            String trimmed = id.trim().toLowerCase(Locale.ROOT);
            if (!trimmed.isEmpty()) {
                games.add(trimmed);
            }
        }
        rotationGames = List.copyOf(games);
        intermissionSeconds = clamp(config.getInt("rotation.intermission-seconds", 30), 3, 3600);

        arenaWorld = config.getString("arena.world", "");
        arenaX = config.getDouble("arena.x");
        arenaY = config.getDouble("arena.y", 64);
        arenaZ = config.getDouble("arena.z");
        arenaRadius = clamp(config.getInt("arena.radius", 60), MIN_RADIUS, MAX_RADIUS);

        ConfigurationSection protectionSection = config.getConfigurationSection("protection");
        protection = protectionSection == null ? ProtectionSettings.ALL : ProtectionSettings.read(protectionSection);
        // YAML reads an unquoted OFF (or on/yes/no) as a boolean: off/false is OFF, on/true the default.
        Object scope = config.get("protection.ability-block-damage", BlockDamageScope.MAP_WORLDS.name());
        BlockDamageScope parsed = scope instanceof Boolean on
                ? on ? BlockDamageScope.MAP_WORLDS : BlockDamageScope.OFF
                : BlockDamageScope.parse(scope == null ? null : scope.toString());
        if (parsed == null) {
            plugin.getLogger().warning("Unknown protection.ability-block-damage '" + scope
                    + "' (use OFF, MAP_WORLDS or ALL_WORLDS); using MAP_WORLDS.");
            parsed = BlockDamageScope.MAP_WORLDS;
        }
        abilityBlockDamage = parsed;
        abilityBlockDamageLimit = clamp(config.getInt("protection.ability-block-damage-limit",
                DEFAULT_BLOCK_DAMAGE_LIMIT), 0, MAX_BLOCK_DAMAGE_LIMIT);
    }

    /** Writes the current in-memory configuration to disk (skipped while {@link #isWritable()} is false). */
    public void save() {
        if (!writable) {
            if (!warnedUnwritable) {
                warnedUnwritable = true;
                plugin.getLogger().warning("config.yml has a syntax error; changes are kept in memory only, not saved.");
            }
            return;
        }
        try {
            plugin.saveConfig();
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save config.yml", e);
        }
    }

    private FileConfiguration raw() {
        return plugin.getConfig();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    // ------------------------------------------------------------------ match

    /** Countdown before a match starts, in seconds (0..{@value #MAX_COUNTDOWN_SECONDS}). */
    public int countdownSeconds() {
        return countdownSeconds;
    }

    public void setCountdownSeconds(int seconds) {
        countdownSeconds = clamp(seconds, 0, MAX_COUNTDOWN_SECONDS);
        raw().set("match.countdown-seconds", countdownSeconds);
        save();
    }

    /** How long results are shown before everyone is restored, in seconds (0..120). */
    public int endingSeconds() {
        return endingSeconds;
    }

    /** Players per team, 0 = free-for-all. */
    public int teamSize() {
        return teamSize;
    }

    public void setTeamSize(int size) {
        teamSize = clamp(size, 0, MAX_TEAM_SIZE);
        raw().set("match.team-size", teamSize);
        save();
    }

    /** Whether celebration fireworks are launched at the winners. */
    public boolean fireworks() {
        return fireworks;
    }

    // ------------------------------------------------------------------ rotation

    public boolean rotationEnabled() {
        return rotationEnabled;
    }

    public void setRotationEnabled(boolean enabled) {
        rotationEnabled = enabled;
        raw().set("rotation.enabled", enabled);
        save();
    }

    /** Lower-case game ids to rotate through, in order. */
    public List<String> rotationGames() {
        return rotationGames;
    }

    public void setRotationGames(List<String> ids) {
        rotationGames = ids.stream().map(id -> id.toLowerCase(Locale.ROOT)).toList();
        raw().set("rotation.games", new ArrayList<>(rotationGames));
        save();
    }

    public int intermissionSeconds() {
        return intermissionSeconds;
    }

    // ------------------------------------------------------------------ arena

    /** The configured arena world name, empty when the arena follows the starter. */
    public String arenaWorldName() {
        return arenaWorld;
    }

    /** True if an arena center was saved (even if its world is not loaded right now). */
    public boolean hasArena() {
        return !arenaWorld.isBlank();
    }

    /** The configured arena center, or null if none is set or its world is not loaded. */
    public @Nullable Location arenaCenter() {
        if (arenaWorld.isBlank()) {
            return null;
        }
        World world = Bukkit.getWorld(arenaWorld);
        return world == null ? null : new Location(world, arenaX, arenaY, arenaZ);
    }

    /** Arena radius in blocks, used both for a configured arena and for one centered on the starter. */
    public int arenaRadius() {
        return arenaRadius;
    }

    public void setArena(Location center, int radius) {
        World world = center.getWorld();
        if (world == null) {
            throw new IllegalArgumentException("center has no world");
        }
        arenaWorld = world.getName();
        arenaX = Math.floor(center.getX()) + 0.5;
        arenaY = Math.floor(center.getY());
        arenaZ = Math.floor(center.getZ()) + 0.5;
        arenaRadius = clamp(radius, MIN_RADIUS, MAX_RADIUS);
        FileConfiguration config = raw();
        config.set("arena.world", arenaWorld);
        config.set("arena.x", arenaX);
        config.set("arena.y", arenaY);
        config.set("arena.z", arenaZ);
        config.set("arena.radius", arenaRadius);
        save();
    }

    /** Forgets the arena center; matches are then centered on whoever starts them. The radius is kept. */
    public void clearArena() {
        arenaWorld = "";
        raw().set("arena.world", "");
        save();
    }

    // ------------------------------------------------------------------ protection

    public ProtectionSettings protection() {
        return protection;
    }

    /** Where abilities may break blocks during a match (restored when it ends). */
    public BlockDamageScope abilityBlockDamage() {
        return abilityBlockDamage;
    }

    public void setAbilityBlockDamage(BlockDamageScope scope) {
        abilityBlockDamage = scope;
        raw().set("protection.ability-block-damage", scope.name());
        save();
    }

    /** At most this many blocks are broken by abilities in one match (0 = none). */
    public int abilityBlockDamageLimit() {
        return abilityBlockDamageLimit;
    }

    public void setAbilityBlockDamageLimit(int limit) {
        abilityBlockDamageLimit = clamp(limit, 0, MAX_BLOCK_DAMAGE_LIMIT);
        raw().set("protection.ability-block-damage-limit", abilityBlockDamageLimit);
        save();
    }

    // ------------------------------------------------------------------ games

    /**
     * The live {@code games.<id>} section of {@code config.yml}, created if missing. Read values each time you need
     * them (the section object is replaced on reload).
     */
    public ConfigurationSection gameSection(String gameId) {
        FileConfiguration config = raw();
        ConfigurationSection games = config.getConfigurationSection("games");
        if (games == null) {
            games = config.createSection("games");
        }
        ConfigurationSection section = games.getConfigurationSection(gameId);
        return section != null ? section : games.createSection(gameId);
    }

    /**
     * Sets {@code games.<gameId>.<path>} (null removes it) and saves. An existing key keeps its comments. Games read
     * their section when a match starts, so the value applies to the next match.
     */
    public void setGameValue(String gameId, String path, @Nullable Object value) {
        updateGameSection(gameId, section -> section.set(path, value));
    }

    /** Changes several values of {@code games.<gameId>} at once, then saves once. */
    public void updateGameSection(String gameId, Consumer<ConfigurationSection> change) {
        change.accept(gameSection(gameId));
        save();
    }

    /**
     * Adds the defaults a game declares to {@code games.<id>} without overwriting values the server owner set, then
     * saves if anything was added. Comments set on the declared keys are copied too.
     *
     * @return true if the file changed
     */
    public boolean applyGameDefaults(String gameId, Consumer<ConfigurationSection> declarer) {
        MemoryConfiguration scratch = new MemoryConfiguration();
        ConfigurationSection defaults = scratch.createSection("defaults");
        declarer.accept(defaults);
        ConfigurationSection target = gameSection(gameId);
        boolean changed = false;
        for (String key : defaults.getKeys(true)) {
            if (defaults.isConfigurationSection(key)) {
                if (!target.isConfigurationSection(key)) {
                    target.createSection(key);
                    target.setComments(key, defaults.getComments(key));
                    changed = true;
                }
                continue;
            }
            if (!target.isSet(key)) {
                target.set(key, defaults.get(key));
                target.setComments(key, defaults.getComments(key));
                changed = true;
            }
        }
        if (changed) {
            save();
        }
        return changed;
    }
}
