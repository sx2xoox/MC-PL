package io.github.sx2xoox.mcpl.core.arena;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

/**
 * Temporarily changes world game rules (e.g. {@code GameRules.IMMEDIATE_RESPAWN} for the arena world) and puts the
 * original values back afterwards. Originals are persisted to {@code gamerules.yml}, so a crash mid-match cannot leave
 * a world with changed rules: they are restored on the next enable (or when the world loads).
 */
public final class GameRuleGuard implements Listener {

    private record Key(UUID world, String rule) {
    }

    private final Plugin plugin;
    private final File file;
    private final Map<Key, String> originals = new LinkedHashMap<>();

    public GameRuleGuard(Plugin plugin, File file) {
        this.plugin = plugin;
        this.file = file;
    }

    public void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void shutdown() {
        restoreAll();
        HandlerList.unregisterAll(this);
    }

    /** Sets {@code rule} to {@code value}, remembering the original value the first time. */
    public <T> void set(World world, GameRule<T> rule, T value) {
        Key key = new Key(world.getUID(), rule.getKey().asString());
        if (!originals.containsKey(key)) {
            originals.put(key, String.valueOf(world.getGameRuleValue(rule)));
            save();
        }
        world.setGameRule(rule, value);
    }

    /** Restores every changed rule in loaded worlds; rules of unloaded worlds are restored when they load. */
    public void restoreAll() {
        if (originals.isEmpty()) {
            return;
        }
        originals.entrySet().removeIf(entry -> {
            World world = Bukkit.getWorld(entry.getKey().world());
            return world != null && apply(world, entry.getKey().rule(), entry.getValue());
        });
        save();
    }

    /** Loads originals left over from an unclean shutdown and restores them. */
    public void recover() {
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection worlds = yaml.getConfigurationSection("worlds");
        if (worlds != null) {
            for (String worldId : worlds.getKeys(false)) {
                ConfigurationSection rules = worlds.getConfigurationSection(worldId);
                if (rules == null) {
                    continue;
                }
                for (String rule : rules.getKeys(false)) {
                    try {
                        originals.put(new Key(UUID.fromString(worldId), rule.replace('|', ':')),
                                rules.getString(rule, ""));
                    } catch (IllegalArgumentException ignored) {
                        // malformed entry
                    }
                }
            }
        }
        if (!originals.isEmpty()) {
            plugin.getLogger().info("Restoring " + originals.size() + " game rule(s) changed before an unclean shutdown.");
        }
        restoreAll();
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        World world = event.getWorld();
        boolean changed = originals.entrySet().removeIf(entry ->
                entry.getKey().world().equals(world.getUID()) && apply(world, entry.getKey().rule(), entry.getValue()));
        if (changed) {
            save();
        }
    }

    private boolean apply(World world, String ruleKey, String value) {
        NamespacedKey key = NamespacedKey.fromString(ruleKey);
        GameRule<?> rule = key == null ? null : Registry.GAME_RULE.get(key);
        if (rule == null) {
            plugin.getLogger().warning("Unknown game rule " + ruleKey + "; cannot restore it");
            return true;
        }
        try {
            setParsed(world, rule, value);
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Could not restore game rule " + ruleKey + " in " + world.getName(), e);
        }
        return true;
    }

    private static <T> void setParsed(World world, GameRule<T> rule, String value) {
        T parsed = parse(rule.getType(), value);
        if (parsed != null) {
            world.setGameRule(rule, parsed);
        }
    }

    private static <T> @Nullable T parse(Class<T> type, String value) {
        if (type == Boolean.class) {
            return type.cast(Boolean.parseBoolean(value));
        }
        if (type == Integer.class) {
            return type.cast(Integer.parseInt(value.trim()));
        }
        return null;
    }

    private void save() {
        if (originals.isEmpty()) {
            if (file.exists() && !file.delete()) {
                plugin.getLogger().warning("Could not delete " + file);
            }
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        originals.forEach((key, value) ->
                yaml.set("worlds." + key.world() + "." + key.rule().replace(':', '|'), value));
        try {
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save " + file, e);
        }
    }
}
