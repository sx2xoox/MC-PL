package io.github.sx2xoox.mcpl.core.map.citadel;

import io.github.sx2xoox.mcpl.core.map.BlockSpec;
import io.github.sx2xoox.mcpl.core.map.Blocks;
import org.bukkit.Axis;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Slab;

/**
 * A small ruined hunter's lodge in a clearing: a spruce log cabin on a stone footing whose east end has caved in. The
 * front door, the collapsed east wall and the low windows all lead in and out; a woodpile against the north wall and
 * the broken walls climb onto the remaining roof.
 *
 * <p>Plan (offsets from the center): walls at {@code dx = -5..5}, {@code dz = -3..3}, the roof over
 * {@code dx = -6..1} with its ridge along x, the door in the south wall at {@code dx = -1}.
 */
final class ForestLodge {

    private static final Material LOG = Material.SPRUCE_LOG;
    private static final Material ROOF = Material.SPRUCE_STAIRS;
    private static final Material ROOF_OLD = Material.DARK_OAK_STAIRS;
    private static final int ROOF_EAST = 1;

    private final ForestSite site;
    private final int cx;
    private final int cz;
    private final int g;

    ForestLodge(ForestSite site, int cx, int cz) {
        this.site = site;
        this.cx = cx;
        this.cz = cz;
        this.g = site.ground(cx, cz);
    }

    void build() {
        for (int dx = -7; dx <= 7; dx++) {
            for (int dz = -6; dz <= 6; dz++) {
                site.mark(cx + dx, cz + dz, ForestSite.BUILT);
                site.canopy().limit(cx + dx, cz + dz, g);
            }
        }
        floor();
        walls();
        roof();
        chimney();
        interior();
        yard();
    }

    // ------------------------------------------------------------------ shell

    private void floor() {
        for (int dx = -5; dx <= 5; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                int x = cx + dx;
                int z = cz + dz;
                boolean wall = Math.abs(dx) == 5 || Math.abs(dz) == 3;
                BlockSpec spec;
                if (wall) {
                    spec = site.hash(x, z, 71) < 0.35 ? BlockSpec.of(Material.MOSSY_COBBLESTONE)
                            : BlockSpec.of(Material.COBBLESTONE);
                } else if (dx >= 2) {
                    // Under the caved-in roof: rotten boards and earth.
                    double n = site.hash(x, z, 72);
                    spec = BlockSpec.of(n < 0.4 ? Material.COARSE_DIRT : n < 0.6 ? Material.MOSS_BLOCK
                            : Material.SPRUCE_PLANKS);
                } else {
                    spec = BlockSpec.of(site.hash(x, z, 73) < 0.15 ? Material.STRIPPED_SPRUCE_WOOD
                            : Material.SPRUCE_PLANKS);
                }
                site.set(x, g, z, spec);
            }
        }
    }

    /** Height of the wall at a perimeter position: four, but broken down towards the caved-in east end. */
    private static int wallHeight(int dx, int dz) {
        if (dx == 5) {
            return switch (dz) {
                case -3 -> 3;
                case -2 -> 2;
                case 2 -> 1;
                case 3 -> 2;
                default -> 0;
            };
        }
        if (dz == 3 && dx == 4) {
            return 3;
        }
        return 4;
    }

    private void walls() {
        for (int dx = -5; dx <= 5; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                if (Math.abs(dx) != 5 && Math.abs(dz) != 3) {
                    continue;
                }
                int x = cx + dx;
                int z = cz + dz;
                int height = wallHeight(dx, dz);
                boolean corner = Math.abs(dx) == 5 && Math.abs(dz) == 3;
                for (int h = 1; h <= height; h++) {
                    BlockSpec spec;
                    if (corner) {
                        spec = Blocks.log(LOG, Axis.Y);
                    } else if (Math.abs(dz) == 3) {
                        spec = Blocks.log(h % 2 == 0 ? Material.STRIPPED_SPRUCE_LOG : LOG, Axis.X);
                    } else {
                        spec = Blocks.log(h % 2 == 0 ? Material.STRIPPED_SPRUCE_LOG : LOG, Axis.Z);
                    }
                    site.set(x, g + h, z, spec);
                }
            }
        }
        // Front door (south) and windows: south, north.
        Blocks.door(site.canvas, cx - 1, g + 1, cz + 3, Material.SPRUCE_DOOR, BlockFace.SOUTH, true);
        int[][] windows = {{-3, 3}, {2, 3}, {-2, -3}, {1, -3}};
        for (int[] w : windows) {
            site.clear(cx + w[0], g + 2, cz + w[1]);
            site.clear(cx + w[0], g + 3, cz + w[1]);
            BlockFace out = w[1] > 0 ? BlockFace.SOUTH : BlockFace.NORTH;
            if (site.chance(0.6)) {
                site.set(cx + w[0] - 1, g + 2, cz + w[1] + (w[1] > 0 ? 1 : -1),
                        Blocks.trapdoor(Material.SPRUCE_TRAPDOOR, out, true, true));
            }
        }
    }

    private void roof() {
        for (int dx = -6; dx <= ROOF_EAST; dx++) {
            int x = cx + dx;
            for (int dz = -4; dz <= 4; dz++) {
                int z = cz + dz;
                int y = g + 8 - Math.abs(dz);
                // Holes where the boards rotted through, more of them towards the collapse.
                boolean hole = Math.abs(dz) >= 1 && Math.abs(dz) <= 3 && dx >= -3
                        && site.hash(x, z, 74) < (dx >= 0 ? 0.35 : 0.12);
                if (hole) {
                    continue;
                }
                if (dz == 0) {
                    site.set(x, y, z, Blocks.log(Material.STRIPPED_SPRUCE_LOG, Axis.X));
                    continue;
                }
                Material stairs = site.hash(x, z, 75) < 0.3 ? ROOF_OLD : ROOF;
                site.set(x, y, z, Blocks.stairs(stairs, dz > 0 ? BlockFace.NORTH : BlockFace.SOUTH));
            }
        }
        // The west gable.
        for (int h = 5; h <= 7; h++) {
            for (int dz = -(7 - h); dz <= 7 - h; dz++) {
                site.set(cx - 5, g + h, cz + dz, BlockSpec.of(Material.SPRUCE_PLANKS));
            }
        }
        // A rafter still spanning the collapsed end, and a broken one leaning into it.
        for (int dz = -3; dz <= 3; dz++) {
            site.set(cx + 3, g + 5, cz + dz, Blocks.log(Material.STRIPPED_SPRUCE_LOG, Axis.Z));
        }
        site.set(cx + 4, g + 1, cz - 1, Blocks.log(Material.STRIPPED_SPRUCE_LOG, Axis.X));
        site.set(cx + 3, g + 1, cz - 1, Blocks.stairs(ROOF_OLD, BlockFace.WEST));
        site.set(cx + 2, g + 1, cz + 1, Blocks.slab(Material.SPRUCE_SLAB));
        site.set(cx + 4, g + 1, cz + 2, Blocks.stairs(ROOF, BlockFace.NORTH));
        site.set(cx + 3, g + 1, cz + 2, BlockSpec.of(Material.SPRUCE_SLAB).slab(Slab.Type.BOTTOM));
    }

    private void chimney() {
        for (int h = 1; h <= 5; h++) {
            for (int dz = -1; dz <= 1; dz++) {
                site.set(cx - 5, g + h, cz + dz, stone(cx - 5, g + h, cz + dz));
            }
        }
        for (int h = 6; h <= 9; h++) {
            site.set(cx - 5, g + h, cz, stone(cx - 5, g + h, cz));
        }
        // The hearth inside, with a cold campfire under a stone mantel (low enough to climb onto).
        for (int dz = -1; dz <= 1; dz++) {
            site.set(cx - 4, g + 1, cz + dz, dz == 0 ? Blocks.campfire(false) : stone(cx - 4, g + 1, cz + dz));
        }
        site.set(cx - 4, g + 2, cz, Blocks.stairsTop(Material.COBBLESTONE_STAIRS, BlockFace.WEST));
        site.set(cx - 4, g + 2, cz - 1, Blocks.lantern(false, false));
    }

    private BlockSpec stone(int x, int y, int z) {
        return BlockSpec.of(site.hash(x, y * 7 + z, 76) < 0.4 ? Material.MOSSY_COBBLESTONE : Material.COBBLESTONE);
    }

    // ------------------------------------------------------------------ inside and around

    private void interior() {
        int y = g + 1;
        site.set(cx - 4, y, cz - 2, Material.BARREL);
        site.set(cx - 3, y, cz - 2, Material.CRAFTING_TABLE);
        site.set(cx - 1, y, cz - 2, Material.FLETCHING_TABLE);
        site.set(cx, y, cz - 2, Material.BARREL);
        site.set(cx, y + 1, cz - 2, Blocks.lantern(false, false));
        site.set(cx + 1, y, cz - 2, Material.SMOKER);
        // A straw bed and a rug.
        site.set(cx - 3, y, cz + 2, Blocks.log(Material.HAY_BLOCK, Axis.X));
        site.set(cx - 2, y, cz + 2, Blocks.log(Material.HAY_BLOCK, Axis.X));
        for (int dx = -2; dx <= 0; dx++) {
            site.set(cx + dx, y, cz, Material.BROWN_CARPET);
        }
        // A table with stools.
        site.set(cx - 2, y, cz - 1, Material.SPRUCE_FENCE);
        site.set(cx - 2, y + 1, cz - 1, Material.SPRUCE_PRESSURE_PLATE);
        site.set(cx - 1, y, cz - 1, Blocks.stairs(Material.SPRUCE_STAIRS, BlockFace.WEST));
        // A lantern hanging from the ridge beam, cobwebs in the corners.
        site.set(cx - 2, g + 7, cz, Blocks.lantern(false, true));
        site.set(cx - 4, g + 4, cz + 2, Material.COBWEB);
        site.set(cx + 1, g + 4, cz - 2, Material.COBWEB);
    }

    private void yard() {
        // Woodpile against the north wall: steps up to the eaves.
        int[] heights = {1, 2, 3, 4};
        for (int i = 0; i < heights.length; i++) {
            int x = cx - i;
            int z = cz - 5;
            for (int h = 1; h <= heights[i]; h++) {
                Material log = site.hash(x, h, 77) < 0.3 ? Material.OAK_LOG
                        : site.hash(x, h, 78) < 0.2 ? Material.BIRCH_LOG : LOG;
                site.set(x, g + h, z, Blocks.log(log, Axis.Z));
            }
        }
        site.set(cx + 1, g + 1, cz - 5, Blocks.slab(Material.SPRUCE_SLAB));
        // The hunters' fire, gone cold, with log seats.
        int fx = cx + 2;
        int fz = cz + 7;
        site.set(fx, g + 1, fz, Blocks.campfire(false));
        site.set(fx - 2, g + 1, fz, Blocks.log(Material.SPRUCE_LOG, Axis.Z));
        site.set(fx + 2, g + 1, fz, Blocks.log(Material.SPRUCE_LOG, Axis.Z));
        site.set(fx, g + 1, fz + 2, Blocks.log(Material.SPRUCE_LOG, Axis.X));
        // Chopping block, lanterns by the door, an archery target.
        site.set(cx + 4, g + 1, cz + 5, Blocks.log(Material.STRIPPED_OAK_LOG, Axis.Y));
        site.set(cx - 2, g + 1, cz + 4, Blocks.lantern(false, false));
        site.set(cx - 7, g + 1, cz + 4, Blocks.log(Material.HAY_BLOCK, Axis.Y));
        site.set(cx - 7, g + 2, cz + 4, Material.TARGET);
        site.set(cx - 7, g + 1, cz + 5, Blocks.log(Material.HAY_BLOCK, Axis.Y));
        site.set(cx + 6, g + 1, cz - 2, Material.BARREL);
        site.set(cx + 6, g + 1, cz - 1, Material.BARREL);
        site.set(cx + 6, g + 2, cz - 2, Material.BARREL);
        // A worn yard: trodden earth around the cabin.
        for (int dx = -9; dx <= 9; dx++) {
            for (int dz = -7; dz <= 9; dz++) {
                int x = cx + dx;
                int z = cz + dz;
                if (Math.abs(dx) <= 5 && Math.abs(dz) <= 3 || !site.inZone(x, z)) {
                    continue;
                }
                double d = Math.sqrt(dx * dx / 81.0 + dz * dz / 64.0);
                double n = site.noise(x, z, 3, 79);
                if (d < 1 && n > 0.35 + d * 0.3 && site.ground(x, z) == g) {
                    site.set(x, g, z, n > 0.7 ? BlockSpec.of(Material.DIRT_PATH) : BlockSpec.of(Material.COARSE_DIRT));
                }
            }
        }
    }
}
