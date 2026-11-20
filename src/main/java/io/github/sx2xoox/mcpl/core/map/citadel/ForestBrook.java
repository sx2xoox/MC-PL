package io.github.sx2xoox.mcpl.core.map.citadel;

import io.github.sx2xoox.mcpl.core.map.BlockSpec;
import io.github.sx2xoox.mcpl.core.map.Blocks;
import org.bukkit.Axis;
import org.bukkit.Material;

/**
 * A brook that rises in a spring pool at the foot of the crag, winds west under the hollow tree's crown and a plank
 * footbridge and ends in a reedy pond near the lodge. The water lies one block below the forest floor between low
 * shores, so a player can always wade out; it is held on every side by earth, never left to spill.
 */
final class ForestBrook {

    /** The brook's course (map-local points), from the spring to the pond. */
    static final double[][] COURSE = {
            {19, 69}, {14, 71}, {9, 74}, {5, 76.5}, {0, 78}, {-4, 79}, {-8, 80.5}, {-11, 83}, {-12.5, 85},
    };
    static final double[] SPRING = {19.5, 68.5, 2.2};
    static final double[] POND = {-13, 87, 3.4};

    private final ForestSite site;
    private final int water = CitadelLayout.GROUND_Y - 1;

    ForestBrook(ForestSite site) {
        this.site = site;
    }

    /** Distance from a column to the brook's course (for keeping the ground flat around it). */
    static double distance(double x, double z) {
        double best = Math.min(Math.hypot(x - SPRING[0], z - SPRING[1]) - SPRING[2],
                Math.hypot(x - POND[0], z - POND[1]) - POND[2]);
        for (int i = 0; i + 1 < COURSE.length; i++) {
            double[] a = COURSE[i];
            double[] b = COURSE[i + 1];
            double dx = b[0] - a[0];
            double dz = b[1] - a[1];
            double t = Math.max(0, Math.min(1, ((x - a[0]) * dx + (z - a[1]) * dz) / (dx * dx + dz * dz)));
            best = Math.min(best, Math.hypot(x - (a[0] + dx * t), z - (a[1] + dz * t)));
        }
        return best;
    }

    void build() {
        for (int x = ForestSite.MIN_X; x <= ForestSite.MAX_X; x++) {
            for (int z = ForestSite.MIN_Z; z <= ForestSite.MAX_Z; z++) {
                if (!site.inZone(x, z)) {
                    continue;
                }
                double width = 1.1 + 0.6 * site.noise(x, z, 6, 101);
                double pool = Math.min(Math.hypot(x - SPRING[0], z - SPRING[1]) - SPRING[2],
                        Math.hypot(x - POND[0], z - POND[1]) - POND[2]);
                double d = distance(x, z);
                if (d <= width) {
                    // Water, deeper in the middle of the stream and in the pools.
                    int depth = pool < -1.5 ? 3 : d < width - 0.9 || pool < 0 ? 2 : 1;
                    for (int y = water - depth + 1; y <= CitadelLayout.GROUND_Y; y++) {
                        site.set(x, y, z, y <= water ? BlockSpec.of(Material.WATER) : BlockSpec.AIR);
                    }
                    site.set(x, water - depth, z, bed(x, z));
                    site.setGround(x, z, water - depth);
                    site.mark(x, z, ForestSite.WATER);
                } else if (d <= width + 1.2) {
                    // The shore: a step down from the forest floor to the water.
                    site.set(x, CitadelLayout.GROUND_Y, z, BlockSpec.AIR);
                    site.set(x, water, z, shore(x, z));
                    site.setGround(x, z, water);
                    site.mark(x, z, ForestSite.WATER);
                }
            }
        }
        dressing();
        footbridge(0, 78);
    }

    private BlockSpec bed(int x, int z) {
        double n = site.hash(x, z, 102);
        return BlockSpec.of(n < 0.4 ? Material.GRAVEL : n < 0.7 ? Material.CLAY : n < 0.85 ? Material.MUD
                : Material.DIRT);
    }

    private BlockSpec shore(int x, int z) {
        double n = site.hash(x, z, 103);
        return BlockSpec.of(n < 0.35 ? Material.GRASS_BLOCK : n < 0.55 ? Material.MOSS_BLOCK
                : n < 0.75 ? Material.COARSE_DIRT : n < 0.88 ? Material.GRAVEL : Material.MUD);
    }

    /** Reeds, ferns and lily pads along the water, mossy stepping stones in it. */
    private void dressing() {
        for (int x = ForestSite.MIN_X; x <= ForestSite.MAX_X; x++) {
            for (int z = ForestSite.MIN_Z; z <= ForestSite.MAX_Z; z++) {
                if (!site.inZone(x, z) || site.use(x, z) != ForestSite.WATER) {
                    continue;
                }
                double n = site.hash(x, z, 104);
                BlockSpec at = site.get(x, water, z);
                boolean wet = at != null && at.material() == Material.WATER;
                if (wet) {
                    if (n < 0.14 && besideShore(x, z)) {
                        // Only where a player on the shore can step onto it.
                        site.set(x, water + 1, z, Material.LILY_PAD);
                    } else if (n > 0.97 && site.get(x, water - 1, z) != null
                            && site.get(x, water - 1, z).material() != Material.WATER) {
                        // A stepping stone in the shallows.
                        site.set(x, water, z, Material.MOSSY_COBBLESTONE);
                    }
                } else if (at != null && (at.material() == Material.GRASS_BLOCK || at.material() == Material.MUD)) {
                    if (n < 0.22) {
                        site.set(x, water + 1, z, Material.SUGAR_CANE);
                        if (n < 0.1) {
                            site.set(x, water + 2, z, Material.SUGAR_CANE);
                        }
                    } else if (n < 0.4) {
                        site.set(x, water + 1, z, Material.FERN);
                    } else if (n < 0.47) {
                        Blocks.tallPlant(site.canvas, x, water + 1, z, Material.LARGE_FERN);
                    }
                }
            }
        }
    }

    private boolean besideShore(int x, int z) {
        for (int[] side : ForestSite.SIDES) {
            BlockSpec at = site.get(x + side[0], water, z + side[1]);
            if (at != null && at.material() != Material.WATER && !at.isAir()) {
                return true;
            }
        }
        return false;
    }

    /** A plank footbridge carrying a north-south trail over the brook at {@code bx, bz}. */
    private void footbridge(int bx, int bz) {
        int y = CitadelLayout.GROUND_Y;
        for (int dz = -3; dz <= 3; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                int x = bx + dx;
                int z = bz + dz;
                if (site.use(x, z) != ForestSite.WATER) {
                    continue;
                }
                site.set(x, y, z, Blocks.slab(Material.SPRUCE_SLAB));
                // Shallow under the planks, so no swimmer gets caught beneath them.
                for (int by = CitadelLayout.GROUND_Y - 5; by < water; by++) {
                    BlockSpec at = site.get(x, by, z);
                    if (at != null && at.material() == Material.WATER) {
                        site.set(x, by, z, bed(x, z));
                    }
                }
                site.setGround(x, z, y);
            }
            for (int dx : new int[] {-2, 2}) {
                int x = bx + dx;
                int z = bz + dz;
                if (site.use(x, z) == ForestSite.WATER) {
                    site.set(x, y, z, Blocks.log(Material.STRIPPED_SPRUCE_LOG, Axis.Z));
                    site.setGround(x, z, y);
                }
            }
        }
    }
}
