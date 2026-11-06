package io.github.sx2xoox.mcpl.core.map;

import java.util.List;
import java.util.Random;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.jspecify.annotations.Nullable;

/** Generates nothing: map worlds are empty until a map is built into them. */
final class VoidGenerator extends ChunkGenerator {

    private final int spawnY;

    VoidGenerator(int spawnY) {
        this.spawnY = spawnY;
    }

    @Override
    public boolean shouldGenerateNoise() {
        return false;
    }

    @Override
    public boolean shouldGenerateSurface() {
        return false;
    }

    @Override
    public boolean shouldGenerateCaves() {
        return false;
    }

    @Override
    public boolean shouldGenerateDecorations() {
        return false;
    }

    @Override
    public boolean shouldGenerateMobs() {
        return false;
    }

    @Override
    public boolean shouldGenerateStructures() {
        return false;
    }

    @Override
    public @Nullable Location getFixedSpawnLocation(World world, Random random) {
        return new Location(world, 0.5, spawnY, 0.5);
    }

    @Override
    public @Nullable BiomeProvider getDefaultBiomeProvider(WorldInfo worldInfo) {
        return new BiomeProvider() {
            @Override
            public Biome getBiome(WorldInfo info, int x, int y, int z) {
                return Biome.PLAINS;
            }

            @Override
            public List<Biome> getBiomes(WorldInfo info) {
                return List.of(Biome.PLAINS);
            }
        };
    }
}
