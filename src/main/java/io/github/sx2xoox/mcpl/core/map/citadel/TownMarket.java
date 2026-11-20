package io.github.sx2xoox.mcpl.core.map.citadel;

import io.github.sx2xoox.mcpl.core.map.BlockSpec;
import io.github.sx2xoox.mcpl.core.map.Blocks;
import org.bukkit.Axis;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;

/**
 * The market square (paved by {@link TownStreets}): a deep well with a winch beam in the middle and market stalls
 * around the edges. Each stall has a striped wool awning and a stack of crates beside it to climb onto the awning.
 */
final class TownMarket {

    private static final int G = TownPainter.GROUND;
    /** Center of the well. */
    static final int WELL_X = 60;
    static final int WELL_Z = 28;

    private TownMarket() {
    }

    static void build(TownPainter p) {
        well(p);
        // stalls: {x, z, facing (the side customers stand on), awning colours}
        stall(p, 56, 19, BlockFace.SOUTH, Material.RED_WOOL, Material.WHITE_WOOL, true);
        stall(p, 61, 19, BlockFace.SOUTH, Material.YELLOW_WOOL, Material.GREEN_WOOL, false);
        stall(p, 66, 31, BlockFace.WEST, Material.BLUE_WOOL, Material.WHITE_WOOL, true);
        stall(p, 63, 34, BlockFace.NORTH, Material.ORANGE_WOOL, Material.BROWN_WOOL, false);
        stall(p, 53, 28, BlockFace.EAST, Material.GREEN_WOOL, Material.WHITE_WOOL, false);
        clutter(p);
    }

    /** A 5x5 stone well: water from y 60 up to the rim, two posts and a beam with a hanging lantern. */
    private static void well(TownPainter p) {
        int cx = WELL_X;
        int cz = WELL_Z;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                int x = cx + dx;
                int z = cz + dz;
                boolean rim = Math.abs(dx) == 2 || Math.abs(dz) == 2;
                if (rim) {
                    p.fill(x, G - 5, z, x, G, z, TownPainter.STONE_BRICK);
                    boolean corner = Math.abs(dx) == 2 && Math.abs(dz) == 2;
                    p.set(x, G + 1, z, corner ? BlockSpec.of(Material.CHISELED_STONE_BRICKS)
                            : TownPainter.STONE_BRICK.next(p.random()));
                } else {
                    p.set(x, G - 6, z, Material.MOSSY_COBBLESTONE);
                    p.fill(x, G - 5, z, x, G + 1, z, BlockSpec.of(Material.WATER));
                }
            }
        }
        // winch beam resting on the north and south rims (a step up from the rim), a lantern in the middle
        for (int dz = -2; dz <= 2; dz++) {
            p.log(cx, G + 2, cz + dz, Material.STRIPPED_SPRUCE_LOG, Axis.Z);
        }
        p.set(cx, G + 3, cz, Blocks.lantern(false, false));
        // flower pots and a bucket-less trough on the rim corners
        p.set(cx - 2, G + 2, cz - 2, Material.POTTED_RED_TULIP);
        p.set(cx + 2, G + 2, cz + 2, Material.POTTED_FERN);
    }

    /**
     * A market stall 4 wide and 3 deep at {@code x,z} (its north-west corner), open towards {@code front}: four posts,
     * a striped awning at {@code y = 68}, a counter at the front, goods at the back and a crate stack on one side
     * that climbs to the awning.
     */
    static void stall(TownPainter p, int x, int z, BlockFace front, Material stripeA, Material stripeB,
                      boolean cratesEast) {
        boolean alongX = front == BlockFace.NORTH || front == BlockFace.SOUTH;
        int w = alongX ? 4 : 3;
        int d = alongX ? 3 : 4;
        int x2 = x + w - 1;
        int z2 = z + d - 1;
        // posts
        for (int[] c : new int[][] {{x, z}, {x2, z}, {x, z2}, {x2, z2}}) {
            p.fill(c[0], G + 1, c[1], c[0], G + 3, c[1], BlockSpec.of(Material.SPRUCE_FENCE));
        }
        // awning: stripes across the front
        for (int ix = x; ix <= x2; ix++) {
            for (int iz = z; iz <= z2; iz++) {
                int stripe = alongX ? ix - x : iz - z;
                p.set(ix, G + 4, iz, stripe % 2 == 0 ? stripeA : stripeB);
            }
        }
        // counter (front row) and goods (back row)
        Material[] goods = {Material.MELON, Material.PUMPKIN, Material.HAY_BLOCK, Material.BARREL,
                Material.COMPOSTER, Material.BEEHIVE};
        for (int ix = x; ix <= x2; ix++) {
            for (int iz = z; iz <= z2; iz++) {
                boolean corner = (ix == x || ix == x2) && (iz == z || iz == z2);
                if (corner) {
                    continue;
                }
                boolean frontRow = switch (front) {
                    case NORTH -> iz == z;
                    case SOUTH -> iz == z2;
                    case WEST -> ix == x;
                    default -> ix == x2;
                };
                boolean backRow = switch (front) {
                    case NORTH -> iz == z2;
                    case SOUTH -> iz == z;
                    case WEST -> ix == x2;
                    default -> ix == x;
                };
                if (frontRow) {
                    p.set(ix, G + 1, iz, Blocks.slabTop(Material.SPRUCE_SLAB));
                    if (p.chance(0.55)) {
                        p.set(ix, G + 2, iz, p.pick(Material.POTTED_POPPY, Material.POTTED_DANDELION,
                                Material.DECORATED_POT, Material.FLOWER_POT, Material.CANDLE));
                    } else if (p.chance(0.6)) {
                        p.set(ix, G + 2, iz, p.pick(Material.RED_CARPET, Material.WHITE_CARPET,
                                Material.BROWN_CARPET, Material.CYAN_CARPET));
                    }
                } else if (backRow) {
                    p.set(ix, G + 1, iz, p.pick(goods));
                }
            }
        }
        // a lantern under the awning
        p.set((x + x2) / 2, G + 3, (z + z2) / 2, Blocks.lantern(false, true));
        // the crate stack beside the stall: one, two and three crates high towards the back
        for (int i = 0; i < 3; i++) {
            int cx;
            int cz;
            int[] row = {0, 1, 2};
            int depth = row[i];
            if (alongX) {
                cx = cratesEast ? x2 + 1 : x - 1;
                cz = front == BlockFace.SOUTH ? z2 - depth : z + depth;
            } else {
                cz = cratesEast ? z2 + 1 : z - 1;
                cx = front == BlockFace.EAST ? x2 - depth : x + depth;
            }
            for (int h = 0; h <= i; h++) {
                p.set(cx, G + 1 + h, cz, h % 2 == 0 ? BlockSpec.of(Material.BARREL).facing(BlockFace.UP)
                        : BlockSpec.of(Material.SPRUCE_PLANKS));
            }
        }
        // a lantern standing on the awning, on the corner away from the crates
        int lx = alongX ? (cratesEast ? x : x2) : (front == BlockFace.EAST ? x : x2);
        int lz = alongX ? (front == BlockFace.SOUTH ? z : z2) : (cratesEast ? z : z2);
        p.set(lx, G + 5, lz, Blocks.lantern(false, false));
    }

    /** Carts, hay and barrels scattered over the square as low cover. */
    private static void clutter(TownPainter p) {
        TownProps.cart(p, 55, 33, BlockFace.EAST, Material.HAY_BLOCK);
        TownProps.cart(p, 65, 23, BlockFace.SOUTH, Material.BARREL);
        TownProps.hayPile(p, 66, 26);
        TownProps.barrels(p, 54, 23);
        TownProps.barrels(p, 63, 35);
    }
}
