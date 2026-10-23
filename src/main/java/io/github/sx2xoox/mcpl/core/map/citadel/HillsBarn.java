package io.github.sx2xoox.mcpl.core.map.citadel;

import io.github.sx2xoox.mcpl.core.map.BlockSpec;
import io.github.sx2xoox.mcpl.core.map.Blocks;
import org.bukkit.Axis;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;

/**
 * The ruined tithe barn in the meadow by the east road: stone footings under a timber frame, big doors in both gable
 * ends and a side door, slit windows, a hayloft reached by a stair, and a roof whose east end has fallen in. A haystack piled
 * against the north wall climbs onto the remaining roof, so its ridge is a perch with a way up.
 *
 * <p>Local coordinates: {@code u} east (along the ridge), {@code v} south of the barn's center; {@code y0} is the y of
 * the ground.
 */
final class HillsBarn {

    /** Half the barn's length (east-west) and width (north-south), walls included. */
    static final int HALF_LENGTH = 6;
    static final int HALF_WIDTH = 4;
    /** The roof is gone from this {@code u} eastwards. */
    private static final int COLLAPSE = 3;

    private static final BlockSpec PLANKS = BlockSpec.of(Material.SPRUCE_PLANKS);

    private final HillsSite site;
    private final int cx;
    private final int cz;
    private final int y0;

    HillsBarn(HillsSite site, int[] center) {
        this.site = site;
        this.cx = center[0];
        this.cz = center[1];
        this.y0 = site.ground(cx, cz);
    }

    /** Keeps the barn's ground (and the haystack against it) free of paths and cover. */
    void reserve() {
        for (int u = -HALF_LENGTH - 2; u <= HALF_LENGTH + 2; u++) {
            for (int v = -HALF_WIDTH - 3; v <= HALF_WIDTH + 2; v++) {
                site.mark(cx + u, cz + v, HillsSite.Use.STRUCTURE);
            }
        }
    }

    void build() {
        floor();
        walls();
        openings();
        gables();
        loft();
        roof();
        haystack();
        dressing();
    }

    private void floor() {
        for (int u = -HALF_LENGTH; u <= HALF_LENGTH; u++) {
            for (int v = -HALF_WIDTH; v <= HALF_WIDTH; v++) {
                set(u, y0, v, BlockSpec.of(site.pick(Material.COARSE_DIRT, Material.COARSE_DIRT,
                        Material.SPRUCE_PLANKS, Material.GRAVEL, Material.DIRT_PATH)));
            }
        }
    }

    /** Stone footings two blocks high, timber panels above, posts at the corners and every third block. */
    private void walls() {
        for (int u = -HALF_LENGTH; u <= HALF_LENGTH; u++) {
            for (int v = -HALF_WIDTH; v <= HALF_WIDTH; v++) {
                boolean endWall = Math.abs(u) == HALF_LENGTH;
                boolean sideWall = Math.abs(v) == HALF_WIDTH;
                if (!endWall && !sideWall) {
                    continue;
                }
                boolean post = endWall && sideWall || sideWall && u % 3 == 0 || endWall && v % 2 == 0;
                for (int y = y0 + 1; y <= y0 + 5; y++) {
                    if (post) {
                        set(u, y, v, Blocks.log(Material.SPRUCE_LOG, Axis.Y));
                    } else if (y <= y0 + 2) {
                        set(u, y, v, BlockSpec.of(site.pick(Material.COBBLESTONE, Material.MOSSY_COBBLESTONE,
                                Material.STONE_BRICKS, Material.MOSSY_STONE_BRICKS)));
                    } else if (y == y0 + 5 && site.hash(cx + u, cz + v, 121) > 0.55) {
                        continue; // a board fallen out
                    } else {
                        set(u, y, v, y == y0 + 5 ? Blocks.log(Material.STRIPPED_SPRUCE_LOG, endWall ? Axis.Z : Axis.X)
                                : PLANKS);
                    }
                }
            }
        }
    }

    /** Doors through both gable ends and the south wall; windows in the timber panels. */
    private void openings() {
        for (int side = -1; side <= 1; side += 2) {
            for (int v = -1; v <= 1; v++) {
                for (int y = y0 + 1; y <= y0 + 3; y++) {
                    clear(HALF_LENGTH * side, y, v);
                }
            }
            set(HALF_LENGTH * side, y0 + 4, -1, Blocks.stairsTop(Material.SPRUCE_STAIRS, BlockFace.NORTH));
            set(HALF_LENGTH * side, y0 + 4, 1, Blocks.stairsTop(Material.SPRUCE_STAIRS, BlockFace.SOUTH));
        }
        for (int u = 0; u <= 1; u++) {
            for (int y = y0 + 1; y <= y0 + 3; y++) {
                clear(u, y, HALF_WIDTH);
            }
        }
        // Slit windows (one high, so their sills are no ledges to get stuck on).
        int[] windows = {-4, -2, 2, 4};
        for (int u : windows) {
            clear(u, y0 + 3, -HALF_WIDTH);
            if (u != -2) {
                clear(u, y0 + 3, HALF_WIDTH);
            }
        }
    }

    /** The triangles of the gable ends under the roof (the east one mostly fallen), with a loft door in the west. */
    private void gables() {
        for (int side = -1; side <= 1; side += 2) {
            int u = HALF_LENGTH * side;
            for (int k = 0; k <= 3; k++) {
                if (side > 0 && k > 0) {
                    break;
                }
                for (int v = -(3 - k); v <= 3 - k; v++) {
                    set(u, y0 + 6 + k, v, PLANKS);
                }
            }
        }
        // The loft door in the west gable.
        clear(-HALF_LENGTH, y0 + 5, 0);
        clear(-HALF_LENGTH, y0 + 6, 0);
    }

    /** The hayloft over the west half, on a beam with two posts, and the stair up to it along the north wall. */
    private void loft() {
        for (int u = -HALF_LENGTH + 1; u <= -1; u++) {
            for (int v = -HALF_WIDTH + 1; v <= HALF_WIDTH - 1; v++) {
                if (u == -1) {
                    set(u, y0 + 4, v, Blocks.log(Material.STRIPPED_SPRUCE_LOG, Axis.Z));
                } else if (site.hash(cx + u, cz + v, 122) > -0.8) {
                    set(u, y0 + 4, v, PLANKS);
                }
            }
        }
        for (int v = -2; v <= 2; v += 4) {
            for (int y = y0 + 1; y <= y0 + 3; y++) {
                set(-1, y, v, BlockSpec.of(Material.SPRUCE_FENCE));
            }
        }
        for (int k = 0; k <= 2; k++) {
            int u = 2 - k;
            for (int y = y0 + 1; y < y0 + 1 + k; y++) {
                set(u, y, -3, PLANKS);
            }
            set(u, y0 + 1 + k, -3, Blocks.stairs(Material.SPRUCE_STAIRS, BlockFace.WEST));
        }
        // Hay left in the loft.
        set(-4, y0 + 5, -2, Blocks.log(Material.HAY_BLOCK, Axis.Y));
        set(-5, y0 + 5, -2, Blocks.log(Material.HAY_BLOCK, Axis.Y));
        set(-5, y0 + 5, 2, Blocks.log(Material.HAY_BLOCK, Axis.X));
    }

    /**
     * A steep shingled roof (eaves one block out, ridge 10 above the floor) over the west part; the east part has
     * fallen in, leaving one tie beam.
     */
    private void roof() {
        for (int u = -HALF_LENGTH - 1; u < COLLAPSE; u++) {
            for (int v = -HALF_WIDTH - 1; v <= HALF_WIDTH + 1; v++) {
                if (u == COLLAPSE - 1 && Math.abs(v) <= 2 && site.hash(cx + u, cz + v, 123) > 0.1) {
                    continue; // the broken edge
                }
                int y = y0 + 10 - Math.abs(v);
                if (v == 0) {
                    set(u, y, v, Blocks.slab(Material.SPRUCE_SLAB));
                } else {
                    Material shingle = site.hash(cx + u, cz + v, 124) > 0.55 ? Material.DARK_OAK_STAIRS
                            : Material.SPRUCE_STAIRS;
                    set(u, y, v, Blocks.stairs(shingle, v < 0 ? BlockFace.SOUTH : BlockFace.NORTH));
                }
            }
        }
        for (int v = -HALF_WIDTH + 1; v <= HALF_WIDTH - 1; v++) {
            set(COLLAPSE, y0 + 5, v, Blocks.log(Material.STRIPPED_SPRUCE_LOG, Axis.Z));
        }
        // Fallen shingles and a rafter on the floor of the open end.
        set(4, y0 + 1, -1, Blocks.slab(Material.SPRUCE_SLAB));
        set(5, y0 + 1, 2, Blocks.stairs(Material.SPRUCE_STAIRS, BlockFace.EAST));
        set(3, y0 + 1, 1, Blocks.log(Material.SPRUCE_LOG, Axis.X));
        set(4, y0 + 1, 1, Blocks.log(Material.SPRUCE_LOG, Axis.X));
    }

    /** Hay piled in steps against the north wall, up to the eaves. */
    private void haystack() {
        int[][] piles = {{-4, -6, 1}, {-3, -6, 2}, {-2, -6, 3}, {-1, -6, 4}, {0, -6, 3}, {-3, -7, 1}, {-2, -7, 2},
                {-1, -7, 2}, {0, -7, 1}};
        for (int[] pile : piles) {
            int x = cx + pile[0];
            int z = cz + pile[1];
            site.stack(x, z, y0 + pile[2], Blocks.log(Material.HAY_BLOCK, Axis.Y));
        }
    }

    private void dressing() {
        set(-4, y0 + 1, 3, Blocks.log(Material.HAY_BLOCK, Axis.Y));
        set(-3, y0 + 1, 3, Blocks.log(Material.HAY_BLOCK, Axis.Y));
        set(-5, y0 + 1, -3, BlockSpec.of(Material.BARREL).facing(BlockFace.UP));
        set(4, y0 + 1, -3, BlockSpec.of(Material.COMPOSTER));
        set(-5, y0 + 1, 0, Blocks.lantern(false, false));
        set(2, y0 + 1, 3, BlockSpec.of(Material.BARREL).facing(BlockFace.UP));
    }

    private void set(int u, int y, int v, BlockSpec spec) {
        site.set(cx + u, y, cz + v, spec);
    }

    private void clear(int u, int y, int v) {
        site.clear(cx + u, y, cz + v);
    }
}
