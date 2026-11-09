package io.github.sx2xoox.mcpl.core.map.citadel;

import io.github.sx2xoox.mcpl.core.map.BlockSpec;
import io.github.sx2xoox.mcpl.core.map.Blocks;
import org.bukkit.Axis;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;

/**
 * The watchtower on the crest of the tallest hill: a stone base room open to the north and south, a stone and timber
 * first platform (+4) reached by two outside stairs, a timber lookout (+8) reached by an inner stair, and a shingled
 * roof with a collapsed corner. Ladders and gaps in the railings are the quick ways down.
 *
 * <p>Local coordinates: {@code u} east, {@code v} south of the tower's center; {@code b} is the y of the hilltop.
 */
final class HillsWatchtower {

    private static final BlockSpec PLANKS = BlockSpec.of(Material.SPRUCE_PLANKS);
    private static final BlockSpec FENCE = BlockSpec.of(Material.SPRUCE_FENCE);
    private static final BlockSpec POST = Blocks.log(Material.SPRUCE_LOG, Axis.Y);
    private static final BlockSpec OVERHANG = Blocks.slabTop(Material.SPRUCE_SLAB);
    private static final Material ROOF = Material.SPRUCE_STAIRS;

    private final HillsSite site;
    private final int cx;
    private final int cz;
    private final int b;

    HillsWatchtower(HillsSite site, int[] center) {
        this.site = site;
        this.cx = center[0];
        this.cz = center[1];
        this.b = site.ground(cx, cz);
    }

    void build() {
        groundworks();
        baseRoom();
        platformOne();
        outerStairs();
        frame();
        platformTwo();
        innerStair();
        ladders();
        roof();
        dressing();
    }

    // ------------------------------------------------------------------ parts

    /** Wears the flat hilltop around the tower down to dirt and gravel (the terrain keeps it level). */
    private void groundworks() {
        for (int u = -8; u <= 8; u++) {
            for (int v = -8; v <= 8; v++) {
                double d = Math.sqrt(u * u + v * v);
                int x = cx + u;
                int z = cz + v;
                if (d > 8.5 || site.use(x, z) != HillsSite.Use.STRUCTURE) {
                    continue;
                }
                double worn = site.noise(x / 2.5, z / 2.5, 51) + (d < 6 ? 0.4 : -0.2);
                Material top = worn > 0.45 ? site.pick(Material.GRAVEL, Material.COBBLESTONE, Material.COARSE_DIRT)
                        : worn > 0.05 ? site.pick(Material.COARSE_DIRT, Material.DIRT_PATH, Material.GRASS_BLOCK)
                        : Material.GRASS_BLOCK;
                site.set(x, site.ground(x, z), z, top);
            }
        }
        // The floor of the base room.
        for (int u = -2; u <= 2; u++) {
            for (int v = -2; v <= 2; v++) {
                set(u, b, v, site.pick(Material.COBBLESTONE, Material.MOSSY_COBBLESTONE, Material.STONE_BRICKS,
                        Material.GRAVEL));
            }
        }
    }

    /** The stone base: walls from the ground to the first platform, arches north and south, slit windows. */
    private void baseRoom() {
        for (int u = -3; u <= 3; u++) {
            for (int v = -3; v <= 3; v++) {
                if (Math.max(Math.abs(u), Math.abs(v)) != 3) {
                    continue;
                }
                boolean corner = Math.abs(u) == 3 && Math.abs(v) == 3;
                for (int y = b + 1; y <= b + 4; y++) {
                    BlockSpec block = y == b + 1 ? BlockSpec.of(site.pick(Material.COBBLESTONE,
                            Material.MOSSY_COBBLESTONE, Material.COBBLESTONE))
                            : corner ? BlockSpec.of(Material.POLISHED_ANDESITE) : stone();
                    set(u, y, v, block);
                }
            }
        }
        for (int side = -1; side <= 1; side += 2) {
            // Arches through the north and south walls, three blocks high.
            for (int u = -1; u <= 1; u++) {
                for (int y = b + 1; y <= b + 3; y++) {
                    clear(u, y, 3 * side);
                }
            }
            set(-1, b + 3, 3 * side, Blocks.stairsTop(Material.STONE_BRICK_STAIRS, BlockFace.WEST));
            set(1, b + 3, 3 * side, Blocks.stairsTop(Material.STONE_BRICK_STAIRS, BlockFace.EAST));
            // Arrow slits in the east and west walls.
            set(3 * side, b + 2, 2 * side, BlockSpec.of(Material.IRON_BARS));
        }
    }

    /** The first platform at b+4: plank floor on the walls and a timber overhang on brackets, with a parapet. */
    private void platformOne() {
        int y = b + 4;
        for (int u = -4; u <= 4; u++) {
            for (int v = -4; v <= 4; v++) {
                int ring = Math.max(Math.abs(u), Math.abs(v));
                if (ring <= 2) {
                    set(u, y, v, PLANKS);
                } else if (ring == 4 && overhang(u, v)) {
                    set(u, y, v, OVERHANG);
                    if ((u + v) % 2 == 0 && !(Math.abs(v) == 4 && Math.abs(u) <= 1) && !(u == 2 && v == -4)) {
                        set(u, y - 1, v, Blocks.stairsTop(Material.STONE_BRICK_STAIRS, inward(u, v)));
                    }
                }
            }
        }
        // The parapet: stone merlons at the corners and every few blocks, fences between, with gaps to climb down.
        for (int u = -4; u <= 4; u++) {
            for (int v = -4; v <= 4; v++) {
                if (Math.max(Math.abs(u), Math.abs(v)) != 4 || !overhang(u, v) || parapetGap(u, v)) {
                    continue;
                }
                boolean corner = Math.abs(u) == 4 && Math.abs(v) == 4;
                set(u, y + 1, v, corner || (u + v) % 4 == 0 ? BlockSpec.of(site.pick(Material.MOSSY_STONE_BRICKS,
                        Material.STONE_BRICKS)) : FENCE);
            }
        }
        // Along the stair openings (the first platform drops onto the stairs there).
        for (int k = 1; k <= 2; k++) {
            set(-3, y + 1, k, FENCE);
            set(3, y + 1, -k, FENCE);
        }
    }

    /** True where the first platform overhangs the base (not above the outside stairs). */
    private static boolean overhang(int u, int v) {
        if (u == -4 && v > 0 || u == 4 && v < 0) {
            return false;
        }
        return true;
    }

    private static boolean parapetGap(int u, int v) {
        return u == -4 && v == 0 || u == 4 && v == 0      // the stair landings
                || (u == 2 || u == 3) && v == -4           // the ladder
                || v == 4 && (u == -1 || u == 0)           // a jump down on the south side
                || v == -4 && u == -2;                     // and one on the north side
    }

    /** Two stone stairs up the west and east sides to the first platform, two blocks wide. */
    private void outerStairs() {
        for (int side = -1; side <= 1; side += 2) {
            // West stair climbs north (v 3 -> 1), east stair climbs south (v -3 -> -1); both land at v = 0.
            BlockFace up = side < 0 ? BlockFace.NORTH : BlockFace.SOUTH;
            for (int k = 0; k <= 2; k++) {
                int v = -side * (3 - k);
                int y = b + 1 + k;
                for (int u = 4 * side; Math.abs(u) <= 5; u += side) {
                    for (int fy = b + 1; fy < y; fy++) {
                        set(u, fy, v, stone());
                    }
                    set(u, y, v, Blocks.stairs(Material.STONE_BRICK_STAIRS, up));
                }
            }
            for (int u = 4 * side; Math.abs(u) <= 5; u += side) {
                for (int fy = b + 1; fy <= b + 4; fy++) {
                    set(u, fy, 0, stone());
                }
            }
        }
    }

    /** Corner posts from the first platform to the roof, with knee braces under the lookout. */
    private void frame() {
        for (int su = -1; su <= 1; su += 2) {
            for (int sv = -1; sv <= 1; sv += 2) {
                for (int y = b + 5; y <= b + 11; y++) {
                    set(3 * su, y, 3 * sv, POST);
                }
                if (su > 0 && sv > 0) {
                    continue; // keep the head room above the inner stair
                }
                set(2 * su, b + 7, 3 * sv, Blocks.stairsTop(Material.SPRUCE_STAIRS, su > 0 ? BlockFace.EAST
                        : BlockFace.WEST));
                set(3 * su, b + 7, 2 * sv, Blocks.stairsTop(Material.SPRUCE_STAIRS, sv > 0 ? BlockFace.SOUTH
                        : BlockFace.NORTH));
            }
        }
    }

    /** The lookout at b+8: plank floor framed by beams, a fence railing with gaps. */
    private void platformTwo() {
        int y = b + 8;
        for (int u = -3; u <= 3; u++) {
            for (int v = -3; v <= 3; v++) {
                boolean edgeU = Math.abs(u) == 3;
                boolean edgeV = Math.abs(v) == 3;
                if (edgeU && edgeV) {
                    continue; // the posts
                }
                if (u == 2 && v >= 0 && v <= 2) {
                    continue; // the stair hole
                }
                if (edgeU) {
                    set(u, y, v, Blocks.log(Material.STRIPPED_SPRUCE_LOG, Axis.Z));
                } else if (edgeV) {
                    set(u, y, v, Blocks.log(Material.STRIPPED_SPRUCE_LOG, Axis.X));
                } else {
                    set(u, y, v, PLANKS);
                }
                if ((edgeU || edgeV) && !lookoutGap(u, v)) {
                    set(u, y + 1, v, FENCE);
                }
            }
        }
        for (int v = 0; v <= 2; v++) {
            set(1, y + 1, v, FENCE);
        }
    }

    private static boolean lookoutGap(int u, int v) {
        return u == -3 && v == -2       // the ladder
                || v == 3 && u == 0     // jump down to the first platform (south)
                || u == 3 && v == -1;   // and east
    }

    /** The stair from the first platform to the lookout, along the east side. */
    private void innerStair() {
        for (int k = 0; k <= 2; k++) {
            int v = 2 - k;
            for (int y = b + 5; y < b + 5 + k; y++) {
                set(2, y, v, PLANKS);
            }
            set(2, b + 5 + k, v, Blocks.stairs(Material.SPRUCE_STAIRS, BlockFace.NORTH));
        }
    }

    /** A ladder from the lookout down a post, and one from the first platform down the north wall. */
    private void ladders() {
        for (int y = b + 5; y <= b + 8; y++) {
            set(-3, y, -2, BlockSpec.of(Material.LADDER).facing(BlockFace.SOUTH));
        }
        for (int y = b + 1; y <= b + 4; y++) {
            set(2, y, -4, BlockSpec.of(Material.LADDER).facing(BlockFace.NORTH));
        }
    }

    /** A low pyramid roof of spruce shingles on the posts, its north-east corner fallen in. */
    private void roof() {
        for (int ring = 3; ring >= 0; ring--) {
            int y = b + 12 + (3 - ring);
            for (int u = -ring; u <= ring; u++) {
                for (int v = -ring; v <= ring; v++) {
                    if (Math.max(Math.abs(u), Math.abs(v)) != ring) {
                        continue;
                    }
                    if (u > 0 && v < 0 && ring >= 2 && (u + -v) >= 4 && site.hash(cx + u, cz + v, 61) > -0.3) {
                        continue; // collapsed corner
                    }
                    if (ring == 0 || Math.abs(u) == Math.abs(v)) {
                        set(u, y, v, PLANKS);
                    } else {
                        set(u, y, v, Blocks.stairs(ROOF, inward(u, v)));
                    }
                }
            }
        }
        set(0, b + 16, 0, BlockSpec.of(Material.LIGHTNING_ROD));
        set(0, b + 14, 0, Blocks.lantern(false, true));
    }

    /** Barrels, a lantern and hay bales around the tower. */
    private void dressing() {
        set(-2, b + 5, -2, BlockSpec.of(Material.BARREL).facing(BlockFace.UP));
        set(-1, b + 5, -2, BlockSpec.of(Material.FLETCHING_TABLE));
        set(-2, b + 5, 1, Blocks.lantern(false, false));
        set(-2, b + 9, 2, Blocks.lantern(false, false));
        set(0, b + 9, -2, BlockSpec.of(Material.BARREL).facing(BlockFace.UP));
        set(-2, b + 1, -2, Blocks.lantern(false, false));
        set(2, b + 1, 2, BlockSpec.of(Material.BARREL).facing(BlockFace.UP));
        set(2, b + 1, 1, BlockSpec.of(Material.BARREL).facing(BlockFace.UP));
        set(-2, b + 3, 0, Blocks.lantern(false, true));
        set(-6, b + 1, -3, Blocks.log(Material.HAY_BLOCK, Axis.Y));
        set(-6, b + 1, -4, Blocks.log(Material.HAY_BLOCK, Axis.Y));
        set(6, b + 1, 4, Blocks.campfire(false));
    }

    // ------------------------------------------------------------------ helpers

    private BlockSpec stone() {
        double roll = site.random.nextDouble();
        Material m = roll < 0.6 ? Material.STONE_BRICKS : roll < 0.8 ? Material.MOSSY_STONE_BRICKS
                : roll < 0.95 ? Material.CRACKED_STONE_BRICKS : Material.COBBLESTONE;
        return BlockSpec.of(m);
    }

    /** The horizontal face pointing from the edge cell {@code u, v} towards the tower's center. */
    private static BlockFace inward(int u, int v) {
        if (Math.abs(u) >= Math.abs(v)) {
            return u > 0 ? BlockFace.WEST : BlockFace.EAST;
        }
        return v > 0 ? BlockFace.NORTH : BlockFace.SOUTH;
    }

    private void set(int u, int y, int v, BlockSpec spec) {
        site.set(cx + u, y, cz + v, spec);
    }

    private void set(int u, int y, int v, Material material) {
        site.set(cx + u, y, cz + v, BlockSpec.of(material));
    }

    private void clear(int u, int y, int v) {
        site.clear(cx + u, y, cz + v);
    }
}
