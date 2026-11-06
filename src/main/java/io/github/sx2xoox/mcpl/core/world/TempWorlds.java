package io.github.sx2xoox.mcpl.core.world;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.regex.Pattern;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.jspecify.annotations.Nullable;

/**
 * Throw-away worlds a mode creates for one match (증강 야생전: a fresh survival world per match). Each family of worlds
 * has a prefix ({@code mcpl_wild_}); its worlds are named {@code <prefix><n>} and nothing else is ever touched.
 *
 * <ul>
 *     <li>{@link #create} makes a new vanilla overworld with a random seed under the first free name;</li>
 *     <li>{@link #dispose} moves anyone left inside to the main world, unloads the world and deletes its folders
 *     (retrying for a while if the server refuses to unload it, e.g. while a player still lies dead inside);</li>
 *     <li>{@link #registerPrefix} deletes worlds of the family left over from a crash (on plugin start).</li>
 * </ul>
 * The server side goes through a {@link WorldProvider} ({@link BukkitWorldProvider} by default; tests use mock worlds).
 */
public final class TempWorlds {

    /** Every temporary world name starts with this (on top of its family prefix). */
    public static final String ROOT_PREFIX = "mcpl_";
    /** Ticks between attempts to unload a world the server refused to unload. */
    static final long RETRY_TICKS = 100;
    /** Attempts before giving up (the folder is then deleted on the next start). */
    static final int MAX_ATTEMPTS = 36;

    private final JavaPlugin plugin;
    private WorldProvider provider = new BukkitWorldProvider();
    private final Map<String, Pattern> families = new LinkedHashMap<>();
    /** Worlds waiting to be unloaded (name → attempts so far). */
    private final Map<String, Integer> pending = new LinkedHashMap<>();
    private @Nullable BukkitTask retryTask;

    public TempWorlds(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** The provider in use. */
    public WorldProvider provider() {
        return provider;
    }

    /** Replaces the provider (tests: worlds without vanilla terrain). */
    public void provider(WorldProvider provider) {
        this.provider = provider;
    }

    /**
     * Declares a family of temporary worlds and deletes its leftovers: worlds named {@code <prefix><n>} that are
     * still on disk (or loaded) from a match that never ended cleanly. Call once when the mode is created.
     *
     * @param prefix lowercase, starting with {@value #ROOT_PREFIX} and ending with {@code _}, e.g. {@code mcpl_wild_}
     * @return how many leftover worlds were deleted
     */
    public int registerPrefix(String prefix) {
        if (!prefix.startsWith(ROOT_PREFIX) || !prefix.endsWith("_") || !prefix.matches("[a-z0-9_]+")) {
            throw new IllegalArgumentException("Invalid temporary world prefix: " + prefix);
        }
        Pattern pattern = Pattern.compile(Pattern.quote(prefix) + "\\d+");
        families.put(prefix, pattern);
        return deleteLeftovers(prefix);
    }

    /** True if {@code name} is a world of a registered family ({@code <prefix><n>}). */
    public boolean isTemporary(String name) {
        for (Pattern pattern : families.values()) {
            if (pattern.matcher(name).matches()) {
                return true;
            }
        }
        return false;
    }

    public boolean isTemporary(World world) {
        return isTemporary(world.getName());
    }

    /** Deletes the worlds of the family {@code prefix} that are on disk or loaded (see {@link #registerPrefix}). */
    public int deleteLeftovers(String prefix) {
        Pattern pattern = families.get(prefix);
        if (pattern == null) {
            throw new IllegalArgumentException("Unknown temporary world prefix: " + prefix);
        }
        List<String> names = new ArrayList<>();
        for (World world : Bukkit.getWorlds()) {
            if (pattern.matcher(world.getName()).matches()) {
                names.add(world.getName());
            }
        }
        try {
            for (String name : provider.folderNames(name -> pattern.matcher(name).matches())) {
                if (!names.contains(name)) {
                    names.add(name);
                }
            }
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Could not list leftover worlds " + prefix + "*", e);
        }
        int deleted = 0;
        for (String name : names) {
            World loaded = Bukkit.getWorld(name);
            if (loaded != null) {
                dispose(loaded);
                deleted++;
                continue;
            }
            if (deleteFolders(name)) {
                deleted++;
            }
        }
        if (deleted > 0) {
            plugin.getLogger().info("Deleted " + deleted + " leftover temporary world(s) " + prefix + "*");
        }
        return deleted;
    }

    /**
     * Creates a fresh world of the family {@code prefix} under the first free name {@code <prefix><n>} (n from 1).
     *
     * @throws IllegalStateException if the server could not create it
     */
    public World create(String prefix, long seed, boolean structures) {
        if (!families.containsKey(prefix)) {
            throw new IllegalArgumentException("Unknown temporary world prefix: " + prefix);
        }
        String name = freeName(prefix);
        World world = provider.create(name, seed, structures);
        plugin.getLogger().info("Created temporary world " + name + " (seed " + seed + ")");
        return world;
    }

    private String freeName(String prefix) {
        List<String> onDisk;
        try {
            onDisk = provider.folderNames(name -> name.startsWith(prefix));
        } catch (RuntimeException e) {
            onDisk = List.of();
        }
        for (int n = 1; ; n++) {
            String name = prefix + n;
            if (Bukkit.getWorld(name) == null && !onDisk.contains(name) && !pending.containsKey(name)) {
                return name;
            }
        }
    }

    /**
     * Gets rid of a temporary world: everyone still inside is sent to the main world's spawn, the world is unloaded
     * without saving and its folders are deleted. If the server refuses to unload it (a player lies dead inside), it
     * is retried every few seconds. Ignored for worlds that are not temporary.
     *
     * @return true if the world is gone now (false: retrying later)
     */
    public boolean dispose(World world) {
        String name = world.getName();
        if (!isTemporary(name)) {
            plugin.getLogger().warning("Refusing to delete " + name + ": not a temporary world");
            return false;
        }
        if (tryDispose(name)) {
            pending.remove(name);
            return true;
        }
        pending.putIfAbsent(name, 1);
        scheduleRetries();
        return false;
    }

    /** Names of the worlds still waiting to be unloaded and deleted. */
    public List<String> pending() {
        return List.copyOf(pending.keySet());
    }

    private boolean tryDispose(String name) {
        World world = Bukkit.getWorld(name);
        if (world != null) {
            evacuate(world);
            try {
                if (!provider.unload(world)) {
                    return false;
                }
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.WARNING, "Could not unload the temporary world " + name, e);
                return false;
            }
        }
        return deleteFolders(name);
    }

    private void evacuate(World world) {
        World main = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().getFirst();
        if (main == null || main.equals(world)) {
            return;
        }
        for (Player player : new ArrayList<>(world.getPlayers())) {
            if (!player.isDead()) {
                player.teleport(main.getSpawnLocation());
            }
        }
    }

    private boolean deleteFolders(String name) {
        if (!isTemporary(name)) {
            return false;
        }
        try {
            provider.deleteFolders(name);
            plugin.getLogger().info("Deleted temporary world " + name);
            return true;
        } catch (IOException | RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Could not delete the temporary world " + name
                    + " (it is deleted on the next start)", e);
            return true; // unloaded: nothing more to retry now
        }
    }

    private void scheduleRetries() {
        if (retryTask != null || !plugin.isEnabled()) {
            return;
        }
        retryTask = Bukkit.getScheduler().runTaskTimer(plugin, this::retry, RETRY_TICKS, RETRY_TICKS);
    }

    private void retry() {
        for (String name : List.copyOf(pending.keySet())) {
            int attempts = pending.get(name);
            if (tryDispose(name)) {
                pending.remove(name);
            } else if (attempts + 1 >= MAX_ATTEMPTS) {
                pending.remove(name);
                plugin.getLogger().warning("Gave up unloading the temporary world " + name
                        + "; it is deleted on the next start");
            } else {
                pending.put(name, attempts + 1);
            }
        }
        if (pending.isEmpty() && retryTask != null) {
            retryTask.cancel();
            retryTask = null;
        }
    }

    /** Plugin disable: one last attempt for every pending world (the rest is deleted on the next start). */
    public void shutdown() {
        if (retryTask != null) {
            retryTask.cancel();
            retryTask = null;
        }
        for (String name : List.copyOf(pending.keySet())) {
            tryDispose(name);
        }
        pending.clear();
    }
}
