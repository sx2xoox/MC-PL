package io.github.sx2xoox.speedrunbot.brain;

import net.minecraft.block.BlockState;

/** Raw block access for sight rays only (server: loaded ServerWorld chunks; client: ClientWorld). */
public interface BlockSource {
    /** The block state, or null when that chunk is not loaded (a client would not have it). */
    BlockState blockAt(int x, int y, int z);

    /** Whether the block hides what is behind it (opaque full cube). */
    boolean opaque(BlockState state, int x, int y, int z);
}
