package io.github.sx2xoox.mcpl.game.augment.support;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

import io.github.sx2xoox.mcpl.core.world.WorldProvider;
import io.github.sx2xoox.mcpl.game.superpower.support.world.FlatWorld;
import org.bukkit.World;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

/**
 * A {@link WorldProvider} for MockBukkit, which cannot generate vanilla terrain: "vanilla" worlds are {@link FlatWorld}s,
 * chunk generation completes at once (or when the test says so), and "folders on disk" are a set of names.
 */
public final class TestWorldProvider implements WorldProvider {

    private final ServerMock server;
    /** World folder names "on disk". */
    public final Set<String> disk = new LinkedHashSet<>();
    public final List<String> created = new ArrayList<>();
    public final List<String> unloaded = new ArrayList<>();
    public final List<String> deleted = new ArrayList<>();
    /** Chunk requests waiting for {@link #completeChunks()} while {@link #manualChunks} is on. */
    public final List<CompletableFuture<Object>> pendingChunks = new ArrayList<>();
    public int chunkRequests;
    public boolean manualChunks;
    /** Unload attempts refused before the next one succeeds. */
    public int refuseUnloads;

    public TestWorldProvider(ServerMock server) {
        this.server = server;
    }

    @Override
    public World create(String name, long seed, boolean structures) {
        FlatWorld world = new FlatWorld(name);
        server.addWorld(world);
        created.add(name);
        disk.add(name);
        return world;
    }

    @Override
    public CompletableFuture<?> generateChunk(World world, int chunkX, int chunkZ) {
        chunkRequests++;
        if (!manualChunks) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Object> future = new CompletableFuture<>();
        pendingChunks.add(future);
        return future;
    }

    /** Completes every waiting chunk request. */
    public void completeChunks() {
        for (CompletableFuture<Object> future : List.copyOf(pendingChunks)) {
            future.complete(null);
        }
        pendingChunks.clear();
    }

    @Override
    public boolean unload(World world) {
        if (refuseUnloads > 0) {
            refuseUnloads--;
            return false;
        }
        server.removeWorld((WorldMock) world);
        unloaded.add(world.getName());
        return true;
    }

    @Override
    public List<String> folderNames(Predicate<String> filter) {
        return disk.stream().filter(filter).toList();
    }

    @Override
    public void deleteFolders(String name) {
        deleted.add(name);
        disk.remove(name);
    }
}
