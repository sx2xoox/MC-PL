package io.github.sx2xoox.mcpl.core.map;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleConsumer;

import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

/**
 * Places a finished {@link MapCanvas} into a world, a few milliseconds of work per tick so the server keeps running
 * smoothly (a large map takes a few seconds). Blocks are placed chunk by chunk, bottom up, without physics, so nothing
 * falls, flows or pops off while the map is being built.
 */
public final class MapBuilder {

    /** Work done per tick before the builder yields (nanoseconds). */
    static final long TICK_BUDGET_NANOS = 25_000_000L;

    private MapBuilder() {
    }

    /**
     * Starts placing {@code canvas} (map-local coordinates, center at {@code 0,0}) into {@code world}. {@code progress}
     * receives 0..1 about once a second, {@code done} runs on the main thread after the last block.
     */
    public static BukkitTask start(Plugin plugin, World world, MapCanvas canvas, DoubleConsumer progress,
                                   Runnable done) {
        Plan plan = Plan.of(canvas);
        return new BukkitRunnable() {
            private int next;
            private long lastReport;

            @Override
            public void run() {
                long deadline = System.nanoTime() + TICK_BUDGET_NANOS;
                while (next < plan.size()) {
                    plan.place(world, next++);
                    if ((next & 255) == 0 && System.nanoTime() > deadline) {
                        break;
                    }
                }
                long now = System.currentTimeMillis();
                if (next >= plan.size()) {
                    cancel();
                    progress.accept(1.0);
                    done.run();
                } else if (now - lastReport >= 1000) {
                    lastReport = now;
                    progress.accept((double) next / plan.size());
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    /** Places the whole canvas at once (tests and small maps). */
    public static void placeAll(World world, MapCanvas canvas) {
        Plan plan = Plan.of(canvas);
        for (int i = 0; i < plan.size(); i++) {
            plan.place(world, i);
        }
    }

    /** The blocks of a canvas, ordered chunk by chunk and bottom up, with one BlockData per distinct spec. */
    private static final class Plan {

        private final int[] xs;
        private final int[] ys;
        private final int[] zs;
        private final int[] kinds;
        private final List<BlockSpec> specs;
        private final BlockData[] data;

        private Plan(int[] xs, int[] ys, int[] zs, int[] kinds, List<BlockSpec> specs) {
            this.xs = xs;
            this.ys = ys;
            this.zs = zs;
            this.kinds = kinds;
            this.specs = specs;
            this.data = new BlockData[specs.size()];
        }

        static Plan of(MapCanvas canvas) {
            List<long[]> blocks = new ArrayList<>();
            Map<BlockSpec, Integer> index = new HashMap<>();
            List<BlockSpec> specs = new ArrayList<>();
            canvas.forEach((x, y, z, spec) -> {
                Integer kind = index.get(spec);
                if (kind == null) {
                    kind = specs.size();
                    specs.add(spec);
                    index.put(spec, kind);
                }
                long chunk = ((long) (x >> 4) + 4096) * 8192 + ((z >> 4) + 4096);
                blocks.add(new long[] {chunk, y, x, z, kind});
            });
            long[][] sorted = blocks.toArray(new long[0][]);
            Arrays.sort(sorted, (a, b) -> a[0] != b[0] ? Long.compare(a[0], b[0]) : Long.compare(a[1], b[1]));
            int n = sorted.length;
            int[] xs = new int[n];
            int[] ys = new int[n];
            int[] zs = new int[n];
            int[] kinds = new int[n];
            for (int i = 0; i < n; i++) {
                ys[i] = (int) sorted[i][1];
                xs[i] = (int) sorted[i][2];
                zs[i] = (int) sorted[i][3];
                kinds[i] = (int) sorted[i][4];
            }
            return new Plan(xs, ys, zs, kinds, specs);
        }

        int size() {
            return xs.length;
        }

        void place(World world, int i) {
            int kind = kinds[i];
            BlockData blockData = data[kind];
            if (blockData == null) {
                blockData = MapBlocks.toData(specs.get(kind));
                data[kind] = blockData;
            }
            world.getBlockAt(xs[i], ys[i], zs[i]).setBlockData(blockData, false);
        }
    }
}
