package io.github.sx2xoox.mcpl.core.map.citadel;

import io.github.sx2xoox.mcpl.core.map.BlockSpec;
import io.github.sx2xoox.mcpl.core.map.Blocks;
import io.github.sx2xoox.mcpl.core.map.citadel.GraveyardBuilder.Palette;
import io.github.sx2xoox.mcpl.core.map.citadel.GraveyardBuilder.Use;
import org.bukkit.Axis;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;

/**
 * The roofless cemetery chapel in the north-east, built over the catacomb's north stairwell: its nave wraps around
 * the open stairs down to the crypt. A bell still hangs in the stepped gable of the south wall (whose broken edge can
 * be climbed to the bell), the west door faces the lane, the east wall has fallen into a wide breach.
 *
 * <pre>
 *   walls x 20..30, z -87..-77; stairwell x 23..27, z -83..-78 (see {@link GraveyardCrypts})
 * </pre>
 */
final class GraveyardChapel {

    private static final int G = GraveyardBuilder.GROUND;
    static final int X1 = 20;
    static final int X2 = 30;
    static final int Z_NORTH = -87;
    static final int Z_SOUTH = -77;

    private static final Palette WALL = Palette.of(Material.STONE_BRICKS, 5, Material.MOSSY_STONE_BRICKS, 4,
            Material.CRACKED_STONE_BRICKS, 3, Material.TUFF_BRICKS, 2, Material.MOSSY_COBBLESTONE, 1);
    private static final Palette FLOOR = Palette.of(Material.STONE_BRICKS, 3, Material.MOSSY_STONE_BRICKS, 3,
            Material.CRACKED_STONE_BRICKS, 2, Material.COBBLESTONE, 1, Material.MOSS_BLOCK, 2,
            Material.GRASS_BLOCK, 2, Material.TUFF, 1);

    private final GraveyardBuilder b;

    GraveyardChapel(GraveyardBuilder builder) {
        this.b = builder;
    }

    void reserve() {
        b.mark(X1 - 1, Z_NORTH - 1, X2 + 1, Z_SOUTH, Use.STRUCTURE);
    }

    /** Height of the wall at a wall column (blocks above the ground). */
    private int height(int x, int z) {
        if (z == Z_SOUTH) {
            // The bell gable: one block higher per block towards the middle (its edge climbs like a stair).
            return 6 - Math.abs(x - 25);
        }
        if (x == X2) {
            // The east wall has mostly fallen: a breach in the middle.
            int fromBreach = Math.abs(z + 83);
            return fromBreach <= 1 ? 0 : Math.min(4, fromBreach);
        }
        if (x == X1) {
            return z >= -79 ? 3 + (-77 - z) : 5;
        }
        // North wall: high behind the altar, broken towards the east.
        return x >= 27 ? Math.max(1, 5 - (x - 26)) : 5;
    }

    void build() {
        // Floor of the nave, flush with the ground.
        for (int x = X1 + 1; x <= X2 - 1; x++) {
            for (int z = Z_NORTH + 1; z <= Z_SOUTH - 1; z++) {
                BlockSpec ground = b.get(x, G, z);
                if (b.isAir(x, G + 1, z) && ground != null && !ground.isAir()
                        && !ground.material().name().endsWith("_STAIRS")) {
                    b.set(x, G, z, FLOOR.pick(b.random));
                }
            }
        }
        // Walls.
        for (int x = X1; x <= X2; x++) {
            for (int z = Z_NORTH; z <= Z_SOUTH; z++) {
                if (x != X1 && x != X2 && z != Z_NORTH && z != Z_SOUTH) {
                    continue;
                }
                int h = height(x, z);
                boolean buttress = (x == X1 || x == X2) && (z == Z_NORTH || z == Z_SOUTH);
                for (int i = 1; i <= h; i++) {
                    b.set(x, G + i, z, buttress ? BlockSpec.of(Material.POLISHED_ANDESITE)
                            : i == 1 ? BlockSpec.of(Material.COBBLESTONE) : WALL.pick(b.random));
                }
            }
        }
        // West door with a pointed arch.
        b.clearBox(X1, G + 1, -83, X1, G + 3, -81);
        b.set(X1, G + 4, -83, Blocks.stairsTop(Material.STONE_BRICK_STAIRS, BlockFace.NORTH));
        b.set(X1, G + 4, -81, Blocks.stairsTop(Material.STONE_BRICK_STAIRS, BlockFace.SOUTH));
        b.clear(X1, G + 4, -82);
        // Tall windows in the north wall with iron bars.
        for (int x : new int[] {22, 28}) {
            for (int y = G + 2; y <= G + 4; y++) {
                if (!b.isAir(x, y, Z_NORTH)) {
                    b.set(x, y, Z_NORTH, BlockSpec.of(Material.IRON_BARS));
                }
            }
        }
        // The bell in its opening in the gable.
        b.set(25, G + 4, Z_SOUTH, BlockSpec.of(Material.BELL));
        b.clear(25, G + 5, Z_SOUTH);
        b.set(24, G + 4, Z_SOUTH, BlockSpec.of(Material.CHISELED_STONE_BRICKS));
        b.set(26, G + 4, Z_SOUTH, BlockSpec.of(Material.CHISELED_STONE_BRICKS));
        // The altar against the north wall, candles, a fallen beam, rubble from the east wall.
        b.set(24, G + 1, Z_NORTH + 1, Blocks.stairs(Material.STONE_BRICK_STAIRS, BlockFace.EAST));
        b.set(25, G + 1, Z_NORTH + 1, BlockSpec.of(Material.CHISELED_STONE_BRICKS));
        b.set(26, G + 1, Z_NORTH + 1, Blocks.stairs(Material.STONE_BRICK_STAIRS, BlockFace.WEST));
        b.set(25, G + 2, Z_NORTH + 1, BlockSpec.of(Material.WHITE_CANDLE).lit(true));
        b.set(23, G + 1, Z_NORTH + 1, BlockSpec.of(Material.CANDLE).lit(true));
        b.set(27, G + 1, Z_NORTH + 2, BlockSpec.of(Material.CANDLE).lit(true));
        b.lying(21, G + 1, -85, Axis.X, 2, Material.DARK_OAK_LOG);
        b.rubble(X2, -84, G, 2.2, GraveyardBuilder.RUBBLE);
        b.rubble(X2 + 1, -80, G, 1.5, GraveyardBuilder.RUBBLE);
        b.set(X1 + 1, G + 4, Z_NORTH + 1, BlockSpec.of(Material.COBWEB));
        b.set(X1 + 1, G + 1, -79, Blocks.lantern(true, false));
        b.set(X2 - 1, G + 1, Z_NORTH + 1, Blocks.lantern(true, false));
    }
}
