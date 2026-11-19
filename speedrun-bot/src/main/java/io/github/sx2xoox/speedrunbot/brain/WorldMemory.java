package io.github.sx2xoox.speedrunbot.brain;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.Fluids;
import net.minecraft.tag.BlockTags;
import net.minecraft.util.math.BlockPos;

import java.util.EnumMap;
import java.util.Map;

/**
 * Everything the bot knows about blocks: only what its perception rays reported (plus the blocks it changed itself,
 * which it also sees). Unknown cells read as {@code null}. Interesting blocks are indexed by kind.
 */
public final class WorldMemory {
    /** Raw-state ids per 16^3 section; 0 = unknown, otherwise rawId + 1. */
    private final Long2ObjectOpenHashMap<int[]> sections = new Long2ObjectOpenHashMap<>();
    private final Map<Kind, LongOpenHashSet> index = new EnumMap<>(Kind.class);
    private long writes;

    public enum Kind {
        LOG, IRON_ORE, COAL_ORE, GRAVEL, LAVA_SOURCE, WATER_SOURCE, CRAFTING_TABLE, FURNACE, CHEST, OBSIDIAN
    }

    public WorldMemory() {
        for (Kind k : Kind.values()) {
            index.put(k, new LongOpenHashSet());
        }
    }

    private static long sectionKey(int x, int y, int z) {
        return BlockPos.asLong(x >> 4, y >> 4, z >> 4);
    }

    private static int inner(int x, int y, int z) {
        return ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
    }

    public BlockState get(int x, int y, int z) {
        if (y < 0 || y > 255) {
            return y < 0 ? Blocks.BEDROCK.getDefaultState() : Blocks.AIR.getDefaultState();
        }
        int[] s = sections.get(sectionKey(x, y, z));
        if (s == null) {
            return null;
        }
        int v = s[inner(x, y, z)];
        return v == 0 ? null : Block.getStateFromRawId(v - 1);
    }

    public BlockState get(BlockPos p) {
        return get(p.getX(), p.getY(), p.getZ());
    }

    public boolean known(int x, int y, int z) {
        return get(x, y, z) != null;
    }

    public void put(int x, int y, int z, BlockState state) {
        if (y < 0 || y > 255) {
            return;
        }
        long key = sectionKey(x, y, z);
        int[] s = sections.get(key);
        if (s == null) {
            s = new int[4096];
            sections.put(key, s);
        }
        int i = inner(x, y, z);
        int v = Block.getRawIdFromState(state) + 1;
        if (s[i] == v) {
            return;
        }
        int prev = s[i];
        s[i] = v;
        writes++;
        long pos = BlockPos.asLong(x, y, z);
        if (prev != 0) {
            Kind pk = kindOf(prev - 1);
            if (pk != null) {
                index.get(pk).remove(pos);
            }
        }
        Kind k = kindOf(v - 1);
        if (k != null) {
            index.get(k).add(pos);
        }
    }

    public void put(BlockPos p, BlockState state) {
        put(p.getX(), p.getY(), p.getZ(), state);
    }

    private byte[] kindCache = new byte[0];

    private Kind kindOf(int rawId) {
        if (rawId >= kindCache.length) {
            byte[] n = new byte[Math.max(rawId + 1, kindCache.length * 2)];
            System.arraycopy(kindCache, 0, n, 0, kindCache.length);
            kindCache = n;
        }
        byte c = kindCache[rawId];
        if (c == 0) {
            Kind k = classify(Block.getStateFromRawId(rawId));
            c = (byte) (k == null ? -1 : k.ordinal() + 1);
            kindCache[rawId] = c;
        }
        return c < 0 ? null : Kind.values()[c - 1];
    }

    public static Kind classify(BlockState s) {
        Block b = s.getBlock();
        if (s.isIn(BlockTags.LOGS) && b != Blocks.CRIMSON_STEM && b != Blocks.WARPED_STEM) {
            return Kind.LOG;
        }
        if (b == Blocks.IRON_ORE) {
            return Kind.IRON_ORE;
        }
        if (b == Blocks.COAL_ORE) {
            return Kind.COAL_ORE;
        }
        if (b == Blocks.GRAVEL) {
            return Kind.GRAVEL;
        }
        if (b == Blocks.CRAFTING_TABLE) {
            return Kind.CRAFTING_TABLE;
        }
        if (b == Blocks.FURNACE) {
            return Kind.FURNACE;
        }
        if (b == Blocks.CHEST) {
            return Kind.CHEST;
        }
        if (b == Blocks.OBSIDIAN) {
            return Kind.OBSIDIAN;
        }
        FluidState f = s.getFluidState();
        if (!f.isEmpty() && f.isStill()) {
            if (f.getFluid() == Fluids.LAVA) {
                return Kind.LAVA_SOURCE;
            }
            if (f.getFluid() == Fluids.WATER && b == Blocks.WATER) {
                return Kind.WATER_SOURCE;
            }
        }
        return null;
    }

    public LongOpenHashSet of(Kind k) {
        return index.get(k);
    }

    /** Nearest indexed block of a kind (squared distance from p), or null. */
    public BlockPos nearest(Kind k, BlockPos p, double maxDist, java.util.function.LongPredicate filter) {
        long best = 0;
        double bestD = maxDist * maxDist;
        boolean found = false;
        LongIterator it = index.get(k).iterator();
        while (it.hasNext()) {
            long l = it.nextLong();
            if (filter != null && !filter.test(l)) {
                continue;
            }
            double dx = BlockPos.unpackLongX(l) - p.getX(), dy = BlockPos.unpackLongY(l) - p.getY(), dz = BlockPos.unpackLongZ(l) - p.getZ();
            double d = dx * dx + dy * dy + dz * dz;
            if (d < bestD) {
                bestD = d;
                best = l;
                found = true;
            }
        }
        return found ? BlockPos.fromLong(best) : null;
    }

    public int count(Kind k) {
        return index.get(k).size();
    }

    public long writes() {
        return writes;
    }

    public int sectionCount() {
        return sections.size();
    }
}
