package io.github.sx2xoox.mcpl.core.world;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;

/** {@link WorldProvider} of a real Paper server: vanilla generator, async chunk generation, folders on disk. */
public final class BukkitWorldProvider implements WorldProvider {

    @Override
    public World create(String name, long seed, boolean structures) {
        World world = new WorldCreator(name)
                .environment(World.Environment.NORMAL)
                .type(WorldType.NORMAL)
                .seed(seed)
                .generateStructures(structures)
                .createWorld();
        if (world == null) {
            throw new IllegalStateException("Could not create the world " + name);
        }
        return world;
    }

    @Override
    public CompletableFuture<?> generateChunk(World world, int chunkX, int chunkZ) {
        return world.getChunkAtAsync(chunkX, chunkZ, true);
    }

    @Override
    public boolean unload(World world) {
        return Bukkit.unloadWorld(world, false);
    }

    @Override
    public List<String> folderNames(Predicate<String> filter) {
        return WorldFiles.folderNames(filter);
    }

    @Override
    public void deleteFolders(String name) throws IOException {
        WorldFiles.deleteWorldFolders(name, WorldFiles.worldPaths(name));
    }
}
