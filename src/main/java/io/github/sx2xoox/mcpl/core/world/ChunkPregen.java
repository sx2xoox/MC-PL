package io.github.sx2xoox.mcpl.core.world;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.jspecify.annotations.Nullable;

/**
 * Generates the chunks of a square area ahead of time without freezing the server: a few chunk requests are started
 * every tick ({@link WorldProvider#generateChunk}, which Paper serves off the main thread), nearest to the center
 * first, with a cap on the requests in flight. Chunks are not kept loaded; they are generated and saved, so walking
 * there later is a cheap load instead of a generation.
 */
public final class ChunkPregen {

    private final List<long[]> queue;
    private final WorldProvider provider;
    private final World world;
    private final int perTick;
    private final int maxInFlight;
    private int next;
    private int inFlight;
    private int completed;
    private int failed;
    private boolean cancelled;
    private @Nullable BukkitTask task;

    private ChunkPregen(WorldProvider provider, World world, List<long[]> queue, int perTick, int maxInFlight) {
        this.provider = provider;
        this.world = world;
        this.queue = queue;
        this.perTick = Math.max(1, perTick);
        this.maxInFlight = Math.max(this.perTick, maxInFlight);
    }

    /**
     * Starts generating every chunk that touches the square {@code centerX ± halfSide}, {@code centerZ ± halfSide}.
     *
     * @param perTick     chunk requests started per tick at most
     * @param maxInFlight requests waiting for the server at most
     */
    public static ChunkPregen start(Plugin plugin, WorldProvider provider, World world, double centerX, double centerZ,
                                    double halfSide, int perTick, int maxInFlight) {
        ChunkPregen pregen = new ChunkPregen(provider, world, chunks(centerX, centerZ, halfSide), perTick, maxInFlight);
        if (!pregen.queue.isEmpty()) {
            pregen.task = Bukkit.getScheduler().runTaskTimer(plugin, pregen::step, 1L, 1L);
        }
        return pregen;
    }

    /** The chunk coordinates {@code {x, z}} of the square, nearest to the center first. */
    static List<long[]> chunks(double centerX, double centerZ, double halfSide) {
        int minX = (int) Math.floor((centerX - halfSide) / 16.0);
        int maxX = (int) Math.floor((centerX + halfSide) / 16.0);
        int minZ = (int) Math.floor((centerZ - halfSide) / 16.0);
        int maxZ = (int) Math.floor((centerZ + halfSide) / 16.0);
        double cx = centerX / 16.0;
        double cz = centerZ / 16.0;
        List<long[]> out = new ArrayList<>();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                out.add(new long[] {x, z});
            }
        }
        out.sort(Comparator.comparingDouble(c -> (c[0] + 0.5 - cx) * (c[0] + 0.5 - cx)
                + (c[1] + 0.5 - cz) * (c[1] + 0.5 - cz)));
        return out;
    }

    private void step() {
        if (cancelled) {
            return;
        }
        int started = 0;
        while (started < perTick && inFlight < maxInFlight && next < queue.size()) {
            long[] chunk = queue.get(next++);
            started++;
            inFlight++;
            CompletableFuture<?> future;
            try {
                future = provider.generateChunk(world, (int) chunk[0], (int) chunk[1]);
            } catch (RuntimeException e) {
                inFlight--;
                completed++;
                failed++;
                continue;
            }
            future.whenComplete((chunkResult, error) -> {
                inFlight--;
                completed++;
                if (error != null) {
                    failed++;
                }
            });
        }
        if (next >= queue.size() && task != null) {
            task.cancel(); // the last requests finish on their own
            task = null;
        }
    }

    /** Stops starting new requests (requests in flight finish on their own). */
    public void cancel() {
        cancelled = true;
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    public boolean isDone() {
        return completed >= queue.size();
    }

    /** 0..1 of the chunks generated so far. */
    public double progress() {
        return queue.isEmpty() ? 1.0 : Math.min(1.0, (double) completed / queue.size());
    }

    public int total() {
        return queue.size();
    }

    public int completed() {
        return completed;
    }

    /** Requests that failed (counted as completed). */
    public int failed() {
        return failed;
    }
}
