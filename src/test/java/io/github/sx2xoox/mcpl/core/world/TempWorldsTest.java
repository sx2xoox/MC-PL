package io.github.sx2xoox.mcpl.core.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import io.github.sx2xoox.mcpl.MCPLPlugin;
import io.github.sx2xoox.mcpl.core.Hub;
import io.github.sx2xoox.mcpl.game.augment.support.TestWorldProvider;
import io.github.sx2xoox.mcpl.game.superpower.support.TestServer;
import io.github.sx2xoox.mcpl.game.superpower.support.world.FlatWorld;
import org.bukkit.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** Temporary match worlds: naming, deletion (with retries), leftovers after a crash, folders and pre-generation. */
class TempWorldsTest {

    private TestServer server;
    private Hub hub;
    private TempWorlds worlds;
    private TestWorldProvider provider;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock(new TestServer());
        server.addWorld(new FlatWorld("world"));
        hub = MockBukkit.load(MCPLPlugin.class).hub();
        worlds = new TempWorlds(hub.plugin());
        provider = new TestWorldProvider(server);
        worlds.provider(provider);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void leftoversOfTheFamilyAreDeletedAndNothingElse() {
        provider.disk.addAll(List.of("mcpl_wild_3", "mcpl_wild_12", "mcpl_wild_x", "mcpl_wild_", "mcpl_map_citadel",
                "world", "mcpl_wild_3_backup"));
        assertEquals(2, worlds.registerPrefix("mcpl_wild_"));
        assertEquals(List.of("mcpl_wild_3", "mcpl_wild_12"), provider.deleted);
        assertTrue(provider.disk.containsAll(List.of("mcpl_wild_x", "mcpl_wild_", "mcpl_map_citadel", "world",
                "mcpl_wild_3_backup")), "other folders stay");
        assertThrows(IllegalArgumentException.class, () -> worlds.registerPrefix("wild_"), "must start with mcpl_");
        assertThrows(IllegalArgumentException.class, () -> worlds.registerPrefix("mcpl_world"), "must end with _");
    }

    @Test
    void createdWorldsTakeTheFirstFreeNumberAndAreDisposedCompletely() {
        worlds.registerPrefix("mcpl_wild_");
        provider.disk.add("mcpl_wild_1"); // a folder that could not be deleted yet
        World first = worlds.create("mcpl_wild_", 42, true);
        assertEquals("mcpl_wild_2", first.getName());
        World second = worlds.create("mcpl_wild_", 43, true);
        assertEquals("mcpl_wild_3", second.getName());
        assertTrue(worlds.isTemporary(first));

        PlayerMock player = server.addPlayer();
        player.teleport(first.getSpawnLocation());
        assertTrue(worlds.dispose(first));
        assertEquals("world", player.getWorld().getName(), "moved out first");
        assertNull(server.getWorld("mcpl_wild_2"));
        assertEquals(List.of("mcpl_wild_2"), provider.deleted);
        assertFalse(worlds.dispose(server.getWorld("world")), "never a world that is not temporary");
        assertNotNull(server.getWorld("world"));
    }

    @Test
    void aRefusedUnloadIsRetriedLater() {
        worlds.registerPrefix("mcpl_wild_");
        World world = worlds.create("mcpl_wild_", 1, false);
        provider.refuseUnloads = 2;
        assertFalse(worlds.dispose(world));
        assertEquals(List.of("mcpl_wild_1"), worlds.pending());
        server.getScheduler().performTicks(TempWorlds.RETRY_TICKS);
        assertEquals(List.of("mcpl_wild_1"), worlds.pending(), "refused again");
        server.getScheduler().performTicks(TempWorlds.RETRY_TICKS);
        assertTrue(worlds.pending().isEmpty());
        assertEquals(List.of("mcpl_wild_1"), provider.deleted);
    }

    @Test
    void shutdownTriesPendingWorldsOnceMore() {
        worlds.registerPrefix("mcpl_wild_");
        World world = worlds.create("mcpl_wild_", 1, false);
        provider.refuseUnloads = 1;
        worlds.dispose(world);
        worlds.shutdown();
        assertEquals(List.of("mcpl_wild_1"), provider.deleted);
        assertTrue(worlds.pending().isEmpty());
    }

    @Test
    void worldFoldersAreListedAndOnlyExactNamesDeleted(@TempDir Path dir) throws IOException {
        Path wild = Files.createDirectories(dir.resolve("mcpl_wild_1").resolve("region"));
        Files.writeString(wild.resolve("r.0.0.mca"), "x");
        Files.createDirectories(dir.resolve("mcpl_wild_2"));
        Files.createDirectories(dir.resolve("overworld"));
        Files.writeString(dir.resolve("mcpl_wild_9"), "a file, not a world");
        assertEquals(List.of("mcpl_wild_1", "mcpl_wild_2"),
                WorldFiles.childFolders(dir, name -> name.matches("mcpl_wild_\\d+")).stream().sorted().toList());

        WorldFiles.deleteWorldFolders("mcpl_wild_1", List.of(dir.resolve("mcpl_wild_1"), dir.resolve("overworld")));
        assertFalse(Files.exists(dir.resolve("mcpl_wild_1")));
        assertTrue(Files.isDirectory(dir.resolve("overworld")), "a folder with another name is never deleted");
        assertEquals(dir.resolve("mcpl_wild_2"), WorldFiles.dimensionPath(dir.resolve("overworld"), "mcpl_wild_2"));
    }

    @Test
    void pregenerationVisitsTheSquareCenterFirstAFewChunksPerTick() {
        List<long[]> chunks = ChunkPregen.chunks(0, 0, 40);
        assertEquals(36, chunks.size(), "-3..2 in both axes");
        long[] first = chunks.getFirst();
        assertTrue(Math.abs(first[0] + 0.5) <= 1 && Math.abs(first[1] + 0.5) <= 1, "nearest to the center first");

        provider.manualChunks = true;
        ChunkPregen pregen = ChunkPregen.start(hub.plugin(), provider, server.getWorld("world"), 0, 0, 40, 4, 8);
        server.getScheduler().performOneTick();
        assertEquals(4, provider.chunkRequests, "per tick");
        server.getScheduler().performOneTick();
        server.getScheduler().performOneTick();
        assertEquals(8, provider.chunkRequests, "never more than the cap in flight");
        provider.completeChunks();
        assertEquals(8.0 / 36, pregen.progress(), 1.0E-9);
        provider.manualChunks = false;
        server.getScheduler().performTicks(20);
        assertTrue(pregen.isDone());
        assertEquals(36, pregen.completed());
    }
}
