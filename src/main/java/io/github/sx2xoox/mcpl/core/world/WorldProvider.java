package io.github.sx2xoox.mcpl.core.world;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

import org.bukkit.World;

/**
 * The server side of {@link TempWorlds}: creating vanilla worlds, generating their chunks, unloading and deleting
 * them. {@link BukkitWorldProvider} does it on a real server; tests swap in a provider with flat mock worlds, since
 * MockBukkit cannot generate vanilla terrain.
 */
public interface WorldProvider {

    /**
     * Creates (or loads) the world {@code name}: vanilla overworld terrain with this seed.
     *
     * @param structures whether villages, ruins, mineshafts, ... generate
     * @throws IllegalStateException if the server could not create it
     */
    World create(String name, long seed, boolean structures);

    /** Generates (if needed) and loads one chunk without blocking the main thread; completes on the main thread. */
    CompletableFuture<?> generateChunk(World world, int chunkX, int chunkZ);

    /** Unloads the world without saving; false if the server refused (players still inside, ...). */
    boolean unload(World world);

    /** Names of the world folders on disk accepted by {@code filter} (also of worlds that are not loaded). */
    List<String> folderNames(Predicate<String> filter);

    /** Deletes the folders of the unloaded world {@code name} (a no-op if there are none). */
    void deleteFolders(String name) throws IOException;
}
