package io.github.sx2xoox.mcpl.core.map.siege;

import java.util.Random;

import io.github.sx2xoox.mcpl.core.map.BlockSpec;
import io.github.sx2xoox.mcpl.core.map.Blocks;
import io.github.sx2xoox.mcpl.core.map.MapCanvas;
import org.bukkit.Axis;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;

/**
 * Shared palette and small props of 협곡 요새: the fortress is grey stone brick on dark deepslate footings, the
 * attackers' camp is spruce and canvas, the gorge is layered stone.
 */
final class SiegeKit {

    private SiegeKit() {
    }

    static BlockSpec pick(Random random, Material... materials) {
        return BlockSpec.of(materials[random.nextInt(materials.length)]);
    }

    /** Fortress masonry: stone bricks, some mossy and cracked. */
    static BlockSpec masonry(Random random) {
        int roll = random.nextInt(100);
        if (roll < 55) {
            return BlockSpec.of(Material.STONE_BRICKS);
        }
        if (roll < 70) {
            return BlockSpec.of(Material.CRACKED_STONE_BRICKS);
        }
        if (roll < 84) {
            return BlockSpec.of(Material.MOSSY_STONE_BRICKS);
        }
        if (roll < 93) {
            return BlockSpec.of(Material.ANDESITE);
        }
        return BlockSpec.of(Material.COBBLESTONE);
    }

    /** Footings, plinths and trims. */
    static BlockSpec dark(Random random) {
        int roll = random.nextInt(100);
        if (roll < 55) {
            return BlockSpec.of(Material.DEEPSLATE_BRICKS);
        }
        if (roll < 80) {
            return BlockSpec.of(Material.CRACKED_DEEPSLATE_BRICKS);
        }
        if (roll < 92) {
            return BlockSpec.of(Material.POLISHED_DEEPSLATE);
        }
        return BlockSpec.of(Material.COBBLED_DEEPSLATE);
    }

    /** Fallen stone. */
    static BlockSpec rubble(Random random) {
        return pick(random, Material.COBBLESTONE, Material.COBBLESTONE, Material.MOSSY_COBBLESTONE,
                Material.CRACKED_STONE_BRICKS, Material.STONE, Material.ANDESITE, Material.STONE_BRICKS);
    }

    /** Natural rock of the gorge and the cliffs, in strata by height. */
    static BlockSpec rock(Random random, int y) {
        int band = Math.floorMod(y * 7 + random.nextInt(3), 23);
        if (band < 3) {
            return BlockSpec.of(Material.TUFF);
        }
        if (band < 6) {
            return BlockSpec.of(Material.ANDESITE);
        }
        if (band == 6) {
            return BlockSpec.of(Material.GRANITE);
        }
        return random.nextInt(9) == 0 ? BlockSpec.of(Material.COBBLESTONE) : BlockSpec.of(Material.STONE);
    }

    /** Paving of fortress roads. */
    static BlockSpec paving(Random random) {
        return pick(random, Material.STONE_BRICKS, Material.STONE_BRICKS, Material.CRACKED_STONE_BRICKS,
                Material.POLISHED_ANDESITE, Material.COBBLESTONE, Material.ANDESITE);
    }

    /** Field tracks. */
    static BlockSpec track(Random random) {
        return pick(random, Material.DIRT_PATH, Material.DIRT_PATH, Material.DIRT_PATH, Material.COARSE_DIRT,
                Material.GRAVEL, Material.PACKED_MUD);
    }

    // ------------------------------------------------------------------ props

    /** A lamp post standing on the block {@code y} (fence post, lantern on top). */
    static void lampPost(MapCanvas canvas, int x, int y, int z, boolean soul) {
        canvas.set(x, y + 1, z, Material.SPRUCE_FENCE);
        canvas.set(x, y + 2, z, Material.SPRUCE_FENCE);
        canvas.set(x, y + 3, z, Blocks.lantern(soul, false));
    }

    /** A spruce tree on the block {@code y}. */
    static void spruce(MapCanvas canvas, int x, int y, int z, int height, Random random) {
        for (int i = 1; i <= height; i++) {
            canvas.set(x, y + i, z, Blocks.log(Material.SPRUCE_LOG, Axis.Y));
        }
        BlockSpec leaves = Blocks.leaves(Material.SPRUCE_LEAVES);
        int top = y + height;
        for (int dy = -height + 3; dy <= 1; dy++) {
            int r = dy >= 0 ? (dy == 1 ? 0 : 1) : Math.min(3, (1 - dy) / 2 + (dy % 2 == 0 ? 1 : 0));
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.abs(dx) + Math.abs(dz) > r + 1 || dx == 0 && dz == 0 && dy <= 0) {
                        continue;
                    }
                    if (Math.abs(dx) == r && Math.abs(dz) == r && r > 1 && random.nextBoolean()) {
                        continue;
                    }
                    leaf(canvas, x + dx, top + dy, z + dz, leaves);
                }
            }
        }
    }

    /** A round broad-leaved tree on the block {@code y}. */
    static void oak(MapCanvas canvas, int x, int y, int z, int height, Material log, Material leaves, Random random) {
        for (int i = 1; i <= height; i++) {
            canvas.set(x, y + i, z, Blocks.log(log, Axis.Y));
        }
        int cy = y + height;
        for (int dx = -3; dx <= 3; dx++) {
            for (int dy = -1; dy <= 2; dy++) {
                for (int dz = -3; dz <= 3; dz++) {
                    double d = dx * dx / 9.0 + dy * dy / (dy > 0 ? 4.0 : 2.0) + dz * dz / 9.0;
                    if (d <= 1.0 && !(d > 0.75 && random.nextInt(3) == 0)) {
                        leaf(canvas, x + dx, cy + dy, z + dz, Blocks.leaves(leaves));
                    }
                }
            }
        }
    }

    /** A small bush on the block {@code y}. */
    static void bush(MapCanvas canvas, int x, int y, int z, Material leaves, Random random) {
        leaf(canvas, x, y + 1, z, Blocks.leaves(leaves));
        for (BlockFace face : new BlockFace[] {BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST}) {
            if (random.nextInt(3) > 0 && !canvas.isSolid(x + face.getModX(), y + 1, z + face.getModZ())
                    && canvas.isSolid(x + face.getModX(), y, z + face.getModZ())) {
                leaf(canvas, x + face.getModX(), y + 1, z + face.getModZ(), Blocks.leaves(leaves));
            }
        }
        if (random.nextBoolean()) {
            leaf(canvas, x, y + 2, z, Blocks.leaves(leaves));
        }
    }

    /** A stack of barrels and crates (spruce planks, hay) on the block {@code y}. */
    static void crates(MapCanvas canvas, int x, int y, int z, Random random) {
        canvas.set(x, y + 1, z, pick(random, Material.BARREL, Material.SPRUCE_PLANKS, Material.HAY_BLOCK));
        if (random.nextBoolean()) {
            canvas.set(x, y + 2, z, pick(random, Material.BARREL, Material.SPRUCE_PLANKS));
        }
        int side = random.nextInt(4);
        int dx = side == 0 ? 1 : side == 1 ? -1 : 0;
        int dz = side == 2 ? 1 : side == 3 ? -1 : 0;
        if (canvas.isSolid(x + dx, y, z + dz) && !canvas.isSolid(x + dx, y + 1, z + dz)) {
            canvas.set(x + dx, y + 1, z + dz, pick(random, Material.BARREL, Material.HAY_BLOCK,
                    Material.SPRUCE_SLAB));
        }
    }

    /** A boulder of rough stone half sunk into the ground at {@code y}. */
    static void boulder(MapCanvas canvas, int x, int y, int z, double rx, double ry, double rz, Random random) {
        int ix = (int) Math.ceil(rx);
        int iy = (int) Math.ceil(ry);
        int iz = (int) Math.ceil(rz);
        for (int dx = -ix; dx <= ix; dx++) {
            for (int dz = -iz; dz <= iz; dz++) {
                for (int dy = 0; dy <= iy; dy++) {
                    double n = sq(dx / (rx + 0.5)) + sq(dy / (ry + 0.5)) + sq(dz / (rz + 0.5));
                    if (n <= 1.0 && canvas.canWrite(x + dx, z + dz)) {
                        Material m = dy == iy || n > 0.8 && random.nextInt(4) == 0 ? Material.MOSSY_COBBLESTONE
                                : random.nextInt(3) == 0 ? Material.ANDESITE : Material.STONE;
                        canvas.set(x + dx, y + dy, z + dz, BlockSpec.of(m));
                    }
                }
            }
        }
    }

    /** A low wooden barricade (fence on logs) along x or z, {@code length} long, standing on {@code y}. */
    static void barricade(MapCanvas canvas, int x, int y, int z, int length, boolean alongX, Random random) {
        for (int i = 0; i < length; i++) {
            int bx = alongX ? x + i : x;
            int bz = alongX ? z : z + i;
            canvas.set(bx, y + 1, bz, Blocks.log(Material.SPRUCE_LOG, alongX ? Axis.X : Axis.Z));
            if (random.nextInt(3) > 0) {
                canvas.set(bx, y + 2, bz, Material.SPRUCE_FENCE);
            }
        }
    }

    /** A crenellated parapet along x or z: a solid course at {@code y} with merlons above on every other block. */
    static void parapet(MapCanvas canvas, int x, int y, int z, int length, boolean alongX, Random random) {
        for (int i = 0; i < length; i++) {
            int bx = alongX ? x + i : x;
            int bz = alongX ? z : z + i;
            canvas.set(bx, y, bz, masonry(random));
            if (Math.floorMod(alongX ? bx : bz, 2) == 0) {
                canvas.set(bx, y + 1, bz, masonry(random));
            }
        }
    }

    /** Short grass, ferns and flowers on grass blocks inside the box, where nothing stands yet. */
    static void meadow(MapCanvas canvas, int x1, int z1, int x2, int z2, double density, Random random) {
        for (int x = x1; x <= x2; x++) {
            for (int z = z1; z <= z2; z++) {
                if (!canvas.canWrite(x, z) || random.nextDouble() > density) {
                    continue;
                }
                int y = canvas.surfaceY(x, z);
                BlockSpec top = canvas.get(x, y, z);
                if (top == null || top.material() != Material.GRASS_BLOCK || canvas.get(x, y + 1, z) != null) {
                    continue;
                }
                int roll = random.nextInt(100);
                Material plant = roll < 55 ? Material.SHORT_GRASS : roll < 70 ? Material.FERN
                        : roll < 76 ? Material.DANDELION : roll < 82 ? Material.POPPY : roll < 87
                        ? Material.CORNFLOWER : roll < 91 ? Material.OXEYE_DAISY : roll < 95 ? Material.AZURE_BLUET
                        : Material.TALL_GRASS;
                if (plant == Material.TALL_GRASS) {
                    if (canvas.get(x, y + 2, z) == null) {
                        Blocks.tallPlant(canvas, x, y + 1, z, plant);
                    }
                    continue;
                }
                canvas.set(x, y + 1, z, plant);
            }
        }
    }

    /** Leaves where nothing stands yet and the canvas may write. */
    private static void leaf(MapCanvas canvas, int x, int y, int z, BlockSpec leaves) {
        if (canvas.canWrite(x, z) && y <= canvas.maxY()) {
            canvas.setIfEmpty(x, y, z, leaves);
        }
    }

    private static double sq(double v) {
        return v * v;
    }
}
