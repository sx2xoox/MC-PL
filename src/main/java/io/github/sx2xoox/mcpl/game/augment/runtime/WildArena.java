package io.github.sx2xoox.mcpl.game.augment.runtime;

import io.github.sx2xoox.mcpl.core.arena.Arena;
import io.github.sx2xoox.mcpl.core.world.ChunkPregen;
import org.bukkit.World;
import org.jspecify.annotations.Nullable;

/**
 * The fresh survival world of one match: the world, the play area (centered on the world spawn) and the chunk
 * pre-generation started when the world was made (null when turned off).
 */
public record WildArena(World world, Arena arena, @Nullable ChunkPregen pregen) {

    /** True when the play area is generated (or pre-generation is off). */
    public boolean ready() {
        return pregen == null || pregen.isDone();
    }

    /** 0..1 of the play area generated. */
    public double progress() {
        return pregen == null ? 1.0 : pregen.progress();
    }
}
