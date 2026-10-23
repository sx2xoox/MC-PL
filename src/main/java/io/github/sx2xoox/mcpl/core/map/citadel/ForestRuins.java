package io.github.sx2xoox.mcpl.core.map.citadel;

import java.util.ArrayList;
import java.util.List;

import io.github.sx2xoox.mcpl.core.map.BlockSpec;
import io.github.sx2xoox.mcpl.core.map.Blocks;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;

/**
 * What the forest swallowed of the citadel: the broken stone archway where the old garden path left the ring road, and
 * a crumbled wall beside it. Broken tops step down in single blocks, so they can be climbed.
 */
final class ForestRuins {

    private final ForestSite site;

    ForestRuins(ForestSite site) {
        this.site = site;
    }

    /** The archway over a path running north-south through {@code ax, az}: a tall west pillar, a broken east one. */
    void arch(int ax, int az) {
        int g = site.ground(ax, az);
        for (int dx = -6; dx <= 6; dx++) {
            for (int dz = -2; dz <= 3; dz++) {
                if (Math.abs(dx) >= 2) {
                    site.mark(ax + dx, az + dz, ForestSite.BUILT);
                }
            }
        }
        // Plinths: one block high, a step onto the pillars.
        for (int dx : new int[] {-5, -4, -3, -2, 2, 3, 4, 5}) {
            for (int dz = -1; dz <= 2; dz++) {
                boolean core = (dx == -4 || dx == -3 || dx == 3 || dx == 4) && (dz == 0 || dz == 1);
                if (!core && site.hash(ax + dx, az + dz, 81) < 0.75) {
                    site.set(ax + dx, g + 1, az + dz, plinth(dx, dz));
                }
            }
        }
        // The west pillar stands to its full height and still carries the springing of the arch.
        for (int dx = -4; dx <= -3; dx++) {
            for (int dz = 0; dz <= 1; dz++) {
                for (int y = g + 1; y <= g + 7; y++) {
                    site.set(ax + dx, y, az + dz, y == g + 1 ? BlockSpec.of(Material.CHISELED_STONE_BRICKS)
                            : brick(ax + dx, y, az + dz));
                }
            }
        }
        for (int dz = 0; dz <= 1; dz++) {
            site.set(ax - 2, g + 7, az + dz, Blocks.stairsTop(Material.STONE_BRICK_STAIRS, BlockFace.WEST));
            site.set(ax - 2, g + 8, az + dz, brick(ax - 2, g + 8, az + dz));
            site.set(ax - 1, g + 8, az + dz, Blocks.stairsTop(Material.MOSSY_STONE_BRICK_STAIRS, BlockFace.WEST));
        }
        site.set(ax - 3, g + 8, az, Blocks.slab(Material.MOSSY_STONE_BRICK_SLAB));
        // The east pillar is broken off; its top steps down to the plinth.
        int[][] east = {{3, 0, 3}, {3, 1, 2}, {4, 0, 2}, {4, 1, 1}};
        for (int[] c : east) {
            for (int h = 1; h <= c[2]; h++) {
                site.set(ax + c[0], g + h, az + c[1], h == 1 ? BlockSpec.of(Material.CHISELED_STONE_BRICKS)
                        : brick(ax + c[0], g + h, az + c[1]));
            }
        }
        // Fallen pieces of the arch, half buried in the moss.
        site.set(ax + 2, g + 1, az + 3, Blocks.stairs(Material.STONE_BRICK_STAIRS, BlockFace.EAST));
        site.set(ax + 5, g + 1, az - 2, brick(ax + 5, g + 1, az - 2));
        site.set(ax + 6, g + 1, az - 1, Blocks.slab(Material.MOSSY_STONE_BRICK_SLAB));
        site.set(ax - 6, g + 1, az + 2, Blocks.slab(Material.STONE_BRICK_SLAB));
    }

    /**
     * A crumbled garden wall from {@code x1,z1} to {@code x2,z2}: two to three blocks high with gaps, its broken ends
     * stepping down to the ground.
     */
    void wall(int x1, int z1, int x2, int z2) {
        // The wall's cells, each sharing a side with the next (a diagonal wall becomes a staircase of cells).
        List<int[]> cells = new ArrayList<>();
        int x = x1;
        int z = z1;
        cells.add(new int[] {x, z});
        while (x != x2 || z != z2) {
            double ex = x2 - x;
            double ez = z2 - z;
            if (Math.abs(ex) * Math.abs(z2 - z1) >= Math.abs(ez) * Math.abs(x2 - x1) && ex != 0) {
                x += (int) Math.signum(ex);
            } else {
                z += (int) Math.signum(ez);
            }
            cells.add(new int[] {x, z});
        }
        // Broken heights: two or three blocks, gaps, and never more than one block between neighbours, so the
        // broken ends step down to the ground.
        int salt = site.random.nextInt(1000);
        int[] heights = new int[cells.size()];
        for (int i = 0; i < heights.length; i++) {
            double n = site.noise(i, salt, 3, 82);
            int[] c = cells.get(i);
            boolean free = site.inZone(c[0], c[1]) && site.use(c[0], c[1]) == ForestSite.FREE;
            heights[i] = !free || n < 0.25 ? 0 : n < 0.45 ? 1 : n < 0.7 ? 2 : 3;
        }
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < heights.length; i++) {
                int before = i == 0 ? 0 : heights[i - 1];
                heights[i] = Math.min(heights[i], before + 1);
            }
            for (int i = heights.length - 1; i >= 0; i--) {
                int after = i == heights.length - 1 ? 0 : heights[i + 1];
                heights[i] = Math.min(heights[i], after + 1);
            }
        }
        for (int i = 0; i < cells.size(); i++) {
            int cx = cells.get(i)[0];
            int cz = cells.get(i)[1];
            if (!site.inZone(cx, cz) || site.use(cx, cz) != ForestSite.FREE) {
                continue;
            }
            int g = site.ground(cx, cz);
            for (int h = 1; h <= heights[i]; h++) {
                site.set(cx, g + h, cz, h == heights[i] && site.hash(cx, cz, 83) < 0.4
                        ? BlockSpec.of(Material.MOSSY_STONE_BRICKS) : brick(cx, g + h, cz));
            }
            if (heights[i] > 0) {
                site.mark(cx, cz, ForestSite.ROCK);
            }
        }
    }

    private BlockSpec plinth(int dx, int dz) {
        double n = site.hash(dx, dz, 85);
        return n < 0.5 ? BlockSpec.of(Material.MOSSY_STONE_BRICKS) : n < 0.8 ? BlockSpec.of(Material.STONE_BRICKS)
                : Blocks.slab(Material.MOSSY_STONE_BRICK_SLAB);
    }

    BlockSpec brick(int x, int y, int z) {
        double n = site.hash(x * 7 + y, z * 3 - y, 86);
        if (n < 0.35) {
            return BlockSpec.of(Material.MOSSY_STONE_BRICKS);
        }
        if (n < 0.55) {
            return BlockSpec.of(Material.CRACKED_STONE_BRICKS);
        }
        return BlockSpec.of(Material.STONE_BRICKS);
    }
}
