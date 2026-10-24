package io.github.sx2xoox.mcpl.core.map.citadel;

import java.util.Random;

import io.github.sx2xoox.mcpl.core.map.BlockSpec;
import io.github.sx2xoox.mcpl.core.map.Blocks;
import io.github.sx2xoox.mcpl.core.map.MapBlocks;
import io.github.sx2xoox.mcpl.core.map.MapCanvas;
import org.bukkit.Axis;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.jspecify.annotations.Nullable;

/**
 * The town's drawing tools: a canvas wrapper that silently skips columns the town does not own (so organic shapes may
 * brush against the roads), the district's random generator, and small helpers shared by the town's builders.
 */
final class TownPainter {

    /** Top block of the untouched ground. */
    static final int GROUND = CitadelLayout.GROUND_Y;

    private final MapCanvas canvas;
    private final Random random;

    TownPainter(MapCanvas canvas, Random random) {
        this.canvas = canvas;
        this.random = random;
    }

    MapCanvas canvas() {
        return canvas;
    }

    Random random() {
        return random;
    }

    /** True if the town may write column {@code x,z}. */
    boolean owns(int x, int z) {
        return canvas.canWrite(x, z);
    }

    // ------------------------------------------------------------------ guarded writes

    void set(int x, int y, int z, BlockSpec spec) {
        if (owns(x, z)) {
            canvas.set(x, y, z, spec);
        }
    }

    void set(int x, int y, int z, Material material) {
        set(x, y, z, BlockSpec.of(material));
    }

    void clear(int x, int y, int z) {
        set(x, y, z, BlockSpec.AIR);
    }

    /** Sets the block only where the position is air. */
    void setIfAir(int x, int y, int z, BlockSpec spec) {
        if (!isSolid(x, y, z)) {
            set(x, y, z, spec);
        }
    }

    void fill(int x1, int y1, int z1, int x2, int y2, int z2, BlockSpec spec) {
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
            for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
                for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++) {
                    set(x, y, z, spec);
                }
            }
        }
    }

    void fill(int x1, int y1, int z1, int x2, int y2, int z2, Palette palette) {
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
            for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
                for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++) {
                    set(x, y, z, palette.next(random));
                }
            }
        }
    }

    void clearBox(int x1, int y1, int z1, int x2, int y2, int z2) {
        fill(x1, y1, z1, x2, y2, z2, BlockSpec.AIR);
    }

    @Nullable BlockSpec get(int x, int y, int z) {
        return canvas.get(x, y, z);
    }

    boolean isSolid(int x, int y, int z) {
        return canvas.isSolid(x, y, z);
    }

    /** True if the block at the position is a full cube (something a ladder or a lantern can hang on). */
    boolean isFull(int x, int y, int z) {
        BlockSpec spec = canvas.get(x, y, z);
        return spec != null && MapBlocks.isFullCube(spec);
    }

    // ------------------------------------------------------------------ randomness

    boolean chance(double p) {
        return random.nextDouble() < p;
    }

    int between(int min, int max) {
        return min + random.nextInt(max - min + 1);
    }

    <T> T pick(T[] values) {
        return values[random.nextInt(values.length)];
    }

    BlockSpec pick(Material... materials) {
        return BlockSpec.of(materials[random.nextInt(materials.length)]);
    }

    // ------------------------------------------------------------------ shared details

    /** Places a wooden door whose lower half is at {@code y}. */
    void door(int x, int y, int z, Material door, BlockFace facing, boolean open) {
        if (owns(x, z)) {
            Blocks.door(canvas, x, y, z, door, facing, open);
        }
    }

    /** A two-block-tall plant (tall grass, ferns, roses, ...). */
    void tallPlant(int x, int y, int z, Material plant) {
        if (owns(x, z) && !isSolid(x, y, z) && !isSolid(x, y + 1, z)) {
            Blocks.tallPlant(canvas, x, y, z, plant);
        }
    }

    /** A log or beam along {@code axis}. */
    void log(int x, int y, int z, Material log, Axis axis) {
        set(x, y, z, Blocks.log(log, axis));
    }

    /** A ladder at {@code x,y1..y2,z} hanging on the block on the side opposite {@code facing}. */
    void ladder(int x, int y1, int y2, int z, BlockFace facing) {
        for (int y = y1; y <= y2; y++) {
            set(x, y, z, BlockSpec.of(Material.LADDER).facing(facing));
        }
    }

    /**
     * A heap of rubble on whatever is below: about {@code radius} wide and {@code height} high, made of {@code mix}.
     * Heaps grow from the top surface of each column, so they also pile on floors.
     */
    void rubble(int cx, int cz, int baseY, double radius, int height, Palette mix) {
        int r = (int) Math.ceil(radius);
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                double d = Math.sqrt(dx * dx + dz * dz) / (radius + 0.5);
                if (d > 1) {
                    continue;
                }
                int h = (int) Math.round((1 - d) * height + random.nextDouble() * 0.8 - 0.4);
                if (!isSolid(cx + dx, baseY - 1, cz + dz)) {
                    continue; // nothing to rest on (a hole in a floor)
                }
                for (int i = 0; i < h; i++) {
                    int y = baseY + i;
                    if (isSolid(cx + dx, y, cz + dz)) {
                        continue;
                    }
                    set(cx + dx, y, cz + dz, i == h - 1 && chance(0.45) ? mix.slab(random) : mix.next(random));
                }
            }
        }
    }

    /** A few loose stones, slabs and plants scattered on the ground around {@code cx,cz}. */
    void debris(int cx, int cz, int radius, int count, Palette mix) {
        for (int i = 0; i < count; i++) {
            int x = cx + between(-radius, radius);
            int z = cz + between(-radius, radius);
            int y = GROUND + 1;
            if (!owns(x, z) || isSolid(x, y, z) || !isFull(x, GROUND, z)) {
                continue;
            }
            int roll = random.nextInt(6);
            if (roll < 2) {
                set(x, y, z, mix.slab(random));
            } else if (roll < 3) {
                set(x, y, z, mix.next(random));
            } else if (roll < 4) {
                set(x, y, z, Material.SHORT_GRASS);
            } else if (roll < 5) {
                set(x, GROUND, z, pick(Material.COARSE_DIRT, Material.GRAVEL, Material.COBBLESTONE));
            } else {
                set(x, y, z, pick(Material.FERN, Material.DEAD_BUSH, Material.SHORT_GRASS));
            }
        }
    }

    /**
     * A weighted mix of full blocks with matching slabs and stairs (for weathered walls and rubble).
     */
    static final class Palette {

        private final Material[] blocks;
        private final Material[] slabs;
        private final Material[] stairs;

        Palette(Material[] blocks, Material[] slabs, Material[] stairs) {
            this.blocks = blocks;
            this.slabs = slabs;
            this.stairs = stairs;
        }

        static Palette of(Material... blocks) {
            return new Palette(blocks, new Material[0], new Material[0]);
        }

        Palette withSlabs(Material... slabs) {
            return new Palette(blocks, slabs, stairs);
        }

        Palette withStairs(Material... stairs) {
            return new Palette(blocks, slabs, stairs);
        }

        BlockSpec next(Random random) {
            return BlockSpec.of(blocks[random.nextInt(blocks.length)]);
        }

        /** A bottom slab of the mix (a full block if it has no slabs). */
        BlockSpec slab(Random random) {
            if (slabs.length == 0) {
                return next(random);
            }
            return Blocks.slab(slabs[random.nextInt(slabs.length)]);
        }

        /** A stair of the mix facing {@code facing} (a slab or block if it has no stairs). */
        BlockSpec stair(Random random, BlockFace facing) {
            if (stairs.length == 0) {
                return slab(random);
            }
            return Blocks.stairs(stairs[random.nextInt(stairs.length)], facing);
        }
    }

    // ------------------------------------------------------------------ common mixes

    static final Palette COBBLE = Palette.of(Material.COBBLESTONE, Material.COBBLESTONE, Material.COBBLESTONE,
                    Material.MOSSY_COBBLESTONE, Material.MOSSY_COBBLESTONE, Material.ANDESITE, Material.STONE)
            .withSlabs(Material.COBBLESTONE_SLAB, Material.MOSSY_COBBLESTONE_SLAB, Material.ANDESITE_SLAB)
            .withStairs(Material.COBBLESTONE_STAIRS, Material.MOSSY_COBBLESTONE_STAIRS);

    static final Palette STONE_BRICK = Palette.of(Material.STONE_BRICKS, Material.STONE_BRICKS,
                    Material.STONE_BRICKS, Material.CRACKED_STONE_BRICKS, Material.MOSSY_STONE_BRICKS,
                    Material.MOSSY_STONE_BRICKS, Material.COBBLESTONE)
            .withSlabs(Material.STONE_BRICK_SLAB, Material.MOSSY_STONE_BRICK_SLAB, Material.COBBLESTONE_SLAB)
            .withStairs(Material.STONE_BRICK_STAIRS, Material.MOSSY_STONE_BRICK_STAIRS);

    static final Palette RUBBLE = Palette.of(Material.COBBLESTONE, Material.MOSSY_COBBLESTONE,
                    Material.MOSSY_COBBLESTONE, Material.STONE_BRICKS, Material.CRACKED_STONE_BRICKS, Material.GRAVEL,
                    Material.ANDESITE, Material.COARSE_DIRT)
            .withSlabs(Material.COBBLESTONE_SLAB, Material.MOSSY_COBBLESTONE_SLAB, Material.STONE_BRICK_SLAB,
                    Material.MOSSY_STONE_BRICK_SLAB)
            .withStairs(Material.COBBLESTONE_STAIRS, Material.MOSSY_STONE_BRICK_STAIRS);
}
