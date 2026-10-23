package io.github.sx2xoox.mcpl.core.map;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameRules;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.jspecify.annotations.Nullable;

/**
 * The battle maps the plugin can build: each one gets a void world of its own ({@code mcpl_map_<id>}), built once by
 * an admin ({@code /게임 경기장 맵 <맵>}) and loaded again on every start. The rest of the server is never touched.
 *
 * <p>Map worlds are fixed at noon in clear weather, spawn no mobs and keep their blocks: fluids do not flow, leaves do
 * not decay, grass does not spread, and only admins can break or place blocks outside a match (see
 * {@link MapWorldListener}).
 */
public final class MapService {

    /** Prefix of every map world name; only worlds with it are ever deleted. */
    public static final String WORLD_PREFIX = "mcpl_map_";

    private final JavaPlugin plugin;
    private final Map<String, ArenaMap> maps = new LinkedHashMap<>();
    private final NamespacedKey versionKey;
    private final NamespacedKey idKey;
    private final MapWorldListener listener;
    private @Nullable BukkitTask building;
    private @Nullable String buildingId;

    public MapService(JavaPlugin plugin) {
        this.plugin = plugin;
        this.versionKey = new NamespacedKey(plugin, "map_version");
        this.idKey = new NamespacedKey(plugin, "map_id");
        this.listener = new MapWorldListener(this);
    }

    public void enable() {
        Bukkit.getPluginManager().registerEvents(listener, plugin);
    }

    public void disable() {
        if (building != null) {
            building.cancel();
            building = null;
            buildingId = null;
        }
        HandlerList.unregisterAll(listener);
    }

    /** Registers a map and loads its world if it was built before. */
    public void register(ArenaMap map) {
        if (maps.putIfAbsent(map.id(), map) != null) {
            throw new IllegalArgumentException("Map already registered: " + map.id());
        }
        if (hasWorldFolder(map) && Bukkit.getWorld(worldName(map)) == null) {
            try {
                load(map);
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.WARNING, "Could not load the map world " + worldName(map), e);
            }
        }
    }

    public Collection<ArenaMap> maps() {
        return maps.values();
    }

    public @Nullable ArenaMap map(String id) {
        return maps.get(id.toLowerCase(Locale.ROOT));
    }

    public static String worldName(ArenaMap map) {
        return WORLD_PREFIX + map.id();
    }

    /** True for worlds that belong to a map. */
    public boolean isMapWorld(World world) {
        return world.getName().startsWith(WORLD_PREFIX);
    }

    /** The map's world if it is loaded. */
    public @Nullable World world(ArenaMap map) {
        return Bukkit.getWorld(worldName(map));
    }

    /** True if the map's world exists and holds the current version of the map. */
    public boolean isBuilt(ArenaMap map) {
        World world = world(map);
        if (world == null) {
            return false;
        }
        Integer version = world.getPersistentDataContainer().get(versionKey, PersistentDataType.INTEGER);
        return version != null && version == map.version();
    }

    /** True while a map is being built. */
    public boolean isBuilding() {
        return building != null;
    }

    public @Nullable String buildingId() {
        return buildingId;
    }

    /** Where players stand at the center of the map. */
    public Location center(ArenaMap map, World world) {
        return new Location(world, 0.5, map.groundY() + 1, 0.5);
    }

    /**
     * Builds the map: deletes an older build, creates a fresh void world, computes the map and places it over the
     * next ticks. {@code progress} receives 0..1, {@code done} gets the finished world. Refused (returns false) while
     * another map is being built.
     */
    public boolean build(ArenaMap map, DoubleConsumer progress, Consumer<World> done) {
        if (building != null) {
            return false;
        }
        World old = world(map);
        if (old != null || hasWorldFolder(map)) {
            delete(map);
        }
        World world = load(map);
        MapCanvas canvas = map.render();
        buildingId = map.id();
        building = MapBuilder.start(plugin, world, canvas, progress, () -> {
            world.getPersistentDataContainer().set(versionKey, PersistentDataType.INTEGER, map.version());
            world.getPersistentDataContainer().set(idKey, PersistentDataType.STRING, map.id());
            world.save();
            building = null;
            buildingId = null;
            done.accept(world);
        });
        return true;
    }

    /** Loads (or creates) the map's world with the void generator and the map world rules. */
    World load(ArenaMap map) {
        World world = new WorldCreator(worldName(map))
                .generator(new VoidGenerator(map.groundY() + 1))
                .environment(World.Environment.NORMAL)
                .generateStructures(false)
                .createWorld();
        if (world == null) {
            throw new IllegalStateException("Could not create the world " + worldName(map));
        }
        applyRules(world, map);
        return world;
    }

    private void applyRules(World world, ArenaMap map) {
        world.setGameRule(GameRules.ADVANCE_TIME, false);
        world.setGameRule(GameRules.ADVANCE_WEATHER, false);
        world.setGameRule(GameRules.SPAWN_MOBS, false);
        world.setGameRule(GameRules.SPAWN_MONSTERS, false);
        world.setGameRule(GameRules.SPAWN_PHANTOMS, false);
        world.setGameRule(GameRules.SPAWN_PATROLS, false);
        world.setGameRule(GameRules.SPAWN_WANDERING_TRADERS, false);
        world.setGameRule(GameRules.MOB_GRIEFING, false);
        world.setGameRule(GameRules.RANDOM_TICK_SPEED, 0);
        world.setTime(6000);
        world.setStorm(false);
        world.setThundering(false);
        World main = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().getFirst();
        world.setDifficulty(main != null && main != world ? main.getDifficulty() : Difficulty.NORMAL);
        world.setSpawnLocation(center(map, world));
    }

    /** Unloads the map's world (moving anyone in it to the main world's spawn) and deletes its folder. */
    void delete(ArenaMap map) {
        String name = worldName(map);
        if (!name.startsWith(WORLD_PREFIX)) {
            throw new IllegalStateException("Refusing to delete " + name);
        }
        List<Path> folders = worldPaths(map);
        World world = Bukkit.getWorld(name);
        if (world != null) {
            World main = Bukkit.getWorlds().getFirst();
            for (Player player : new ArrayList<>(world.getPlayers())) {
                player.teleport(main.getSpawnLocation());
            }
            if (!Bukkit.unloadWorld(world, false)) {
                throw new IllegalStateException("Could not unload the world " + name);
            }
        }
        for (Path folder : folders) {
            // Only ever a folder named after the map world, never the main save around it.
            if (!Files.isDirectory(folder) || !name.equals(String.valueOf(folder.getFileName()))) {
                continue;
            }
            try {
                deleteRecursively(folder);
            } catch (IOException e) {
                throw new IllegalStateException("Could not delete " + folder, e);
            }
        }
    }

    /**
     * Where the map's world may live on disk. Paper 26 keeps every world as a dimension of the main save
     * ({@code <save>/dimensions/minecraft/<name>}, next to the overworld's folder); older servers kept it in
     * {@code <world container>/<name>} (Paper migrates that one when the world is loaded).
     */
    private static List<Path> worldPaths(ArenaMap map) {
        String name = worldName(map);
        List<Path> paths = new ArrayList<>(2);
        try {
            World loaded = Bukkit.getWorld(name);
            if (loaded != null) {
                paths.add(loaded.getWorldPath());
            } else if (!Bukkit.getWorlds().isEmpty()) {
                paths.add(dimensionPath(Bukkit.getWorlds().getFirst().getWorldPath(), name));
            }
        } catch (RuntimeException noWorldStorage) {
            // test servers have no world folders
        }
        try {
            paths.add(new File(Bukkit.getWorldContainer(), name).toPath());
        } catch (RuntimeException noWorldContainer) {
            // test servers have no world container
        }
        return paths;
    }

    /** The folder of the world {@code name} next to the main world's folder {@code overworld}. */
    static Path dimensionPath(Path overworld, String name) {
        return overworld.resolveSibling(name);
    }

    /** True if the map's world exists on disk (false where the server has no world folders, as in tests). */
    private static boolean hasWorldFolder(ArenaMap map) {
        return worldPaths(map).stream().anyMatch(Files::isDirectory);
    }

    private static void deleteRecursively(Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, @Nullable IOException exc) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** Ids of all registered maps, for command suggestions. */
    public List<String> ids() {
        return new ArrayList<>(maps.keySet());
    }
}
