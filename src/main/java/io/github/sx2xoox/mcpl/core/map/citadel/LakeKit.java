package io.github.sx2xoox.mcpl.core.map.citadel;

import java.util.Random;

import io.github.sx2xoox.mcpl.core.map.BlockSpec;
import io.github.sx2xoox.mcpl.core.map.Blocks;
import io.github.sx2xoox.mcpl.core.map.MapCanvas;
import org.bukkit.Axis;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;

/** Small block helpers shared by the lake district's builders: weathered stone, rubble, posts and lanterns. */
final class LakeKit {

    private LakeKit() {
    }

    /** Weathered stone bricks: mostly plain, some mossy, some cracked. */
    static BlockSpec brick(Random random) {
        int roll = random.nextInt(10);
        return BlockSpec.of(roll < 5 ? Material.STONE_BRICKS : roll < 8 ? Material.MOSSY_STONE_BRICKS
                : Material.CRACKED_STONE_BRICKS);
    }

    /** A stair of weathered stone bricks (plain or mossy). */
    static BlockSpec brickStairs(Random random, BlockFace facing) {
        return Blocks.stairs(random.nextInt(3) == 0 ? Material.MOSSY_STONE_BRICK_STAIRS
                : Material.STONE_BRICK_STAIRS, facing);
    }

    /** An upside-down stair of weathered stone bricks (arches, lintels). */
    static BlockSpec brickStairsTop(Random random, BlockFace facing) {
        return Blocks.stairsTop(random.nextInt(3) == 0 ? Material.MOSSY_STONE_BRICK_STAIRS
                : Material.STONE_BRICK_STAIRS, facing);
    }

    /** A slab of weathered stone bricks. */
    static BlockSpec brickSlab(Random random) {
        return Blocks.slab(random.nextInt(3) == 0 ? Material.MOSSY_STONE_BRICK_SLAB : Material.STONE_BRICK_SLAB);
    }

    /**
     * A low parapet piece: a stone brick slab. (Parapets avoid wall blocks: the mock server the map tests place the
     * map into cannot set wall heights.)
     */
    static BlockSpec brickRail(Random random) {
        return brickSlab(random);
    }

    /** Rough field stone: cobble, mossy cobble, stone, andesite. */
    static BlockSpec rubble(Random random) {
        int roll = random.nextInt(10);
        return BlockSpec.of(roll < 3 ? Material.COBBLESTONE : roll < 6 ? Material.MOSSY_COBBLESTONE
                : roll < 8 ? Material.STONE : Material.ANDESITE);
    }

    /** A loose piece of rubble lying on the ground: a slab or a stair of cobble or bricks. */
    static BlockSpec rubbleBit(Random random) {
        BlockFace facing = horizontal(random);
        return switch (random.nextInt(6)) {
            case 0 -> Blocks.slab(Material.COBBLESTONE_SLAB);
            case 1 -> Blocks.slab(Material.MOSSY_COBBLESTONE_SLAB);
            case 2 -> Blocks.stairs(Material.MOSSY_STONE_BRICK_STAIRS, facing);
            case 3 -> Blocks.stairs(Material.COBBLESTONE_STAIRS, facing);
            case 4 -> brickSlab(random);
            default -> Blocks.slab(Material.ANDESITE_SLAB);
        };
    }

    static BlockFace horizontal(Random random) {
        return switch (random.nextInt(4)) {
            case 0 -> BlockFace.NORTH;
            case 1 -> BlockFace.EAST;
            case 2 -> BlockFace.SOUTH;
            default -> BlockFace.WEST;
        };
    }

    /** The cardinal direction closest to the vector {@code dx, dz}. */
    static BlockFace face(double dx, double dz) {
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx >= 0 ? BlockFace.EAST : BlockFace.WEST;
        }
        return dz >= 0 ? BlockFace.SOUTH : BlockFace.NORTH;
    }

    static BlockFace opposite(BlockFace face) {
        return face.getOppositeFace();
    }

    /** A wooden post from {@code y1} up to {@code y2} (a log standing upright). */
    static void post(MapCanvas canvas, int x, int z, int y1, int y2, Material log) {
        for (int y = y1; y <= y2; y++) {
            canvas.set(x, y, z, Blocks.log(log, Axis.Y));
        }
    }

    /** A fence post with a lantern on top, standing on the block below {@code y}. */
    static void lanternPost(MapCanvas canvas, int x, int y, int z, Material fence, int height) {
        for (int i = 0; i < height; i++) {
            canvas.set(x, y + i, z, BlockSpec.of(fence));
        }
        canvas.set(x, y + height, z, Blocks.lantern(false, false));
    }

    /** A rubble heap: a low mound of stone and loose slabs around {@code x, z} on ground {@code groundY}. */
    static void rubbleHeap(MapCanvas canvas, Random random, int cx, int cz, int groundY, double radius) {
        int r = (int) Math.ceil(radius);
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                double d = Math.sqrt(dx * dx + dz * dz) + random.nextDouble() * 0.8;
                if (d > radius + 0.3 || !canvas.canWrite(cx + dx, cz + dz)) {
                    continue;
                }
                int height = d < radius * 0.45 ? 2 : d < radius * 0.8 ? 1 : 0;
                for (int i = 0; i < height; i++) {
                    canvas.set(cx + dx, groundY + 1 + i, cz + dz, rubble(random));
                }
                if (random.nextInt(3) > 0) {
                    canvas.set(cx + dx, groundY + 1 + height, cz + dz, rubbleBit(random));
                }
            }
        }
    }
}
