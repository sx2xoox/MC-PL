package io.github.sx2xoox.mcpl.core.map.siege;

import java.util.Random;

import io.github.sx2xoox.mcpl.core.map.BlockSpec;
import io.github.sx2xoox.mcpl.core.map.Blocks;
import io.github.sx2xoox.mcpl.core.map.MapCanvas;
import io.github.sx2xoox.mcpl.core.map.MapZone;
import org.bukkit.Axis;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;

/**
 * The inner wall and the inner courtyard. The wall has a gate on the main road, wall-walk stairs on both sides in the
 * north (the north flank goes over the wall) and a postern in the south. Inside: the 중앙 탑 platform, the chapel where
 * the defenders spawn after the front tower fell, an orchard in the south and three flights of stairs up to the keep
 * plateau (main, north, south).
 */
final class SiegeCourtZone implements MapZone {

    private static final int G = SiegeLayout.GROUND_Y;
    private static final int TOP = SiegeLayout.WALL_TOP;
    private static final int W1 = SiegeLayout.INNER_WALL_WEST;
    private static final int W2 = SiegeLayout.INNER_WALL_EAST;

    @Override
    public String name() {
        return "court";
    }

    @Override
    public boolean contains(int x, int z) {
        return SiegeLayout.isInterior(x, z) && x >= 25 && x < SiegeLayout.PLATEAU_EDGE;
    }

    @Override
    public void build(MapCanvas canvas) {
        Random random = canvas.random(name());
        ground(canvas, random);
        wall(canvas, random);
        gate(canvas, random);
        wallStairs(canvas, random);
        plateauStairs(canvas, random);
        SiegeGateZone.towerFloor(canvas, random, SiegeLayout.MIDDLE_X, 0, G);
        chapel(canvas, random);
        orchard(canvas, random);
        props(canvas, random);
    }

    private void ground(MapCanvas canvas, Random random) {
        for (int x = 25; x < SiegeLayout.PLATEAU_EDGE; x++) {
            for (int z = -SiegeLayout.HALF_WIDTH; z <= SiegeLayout.HALF_WIDTH; z++) {
                if (!canvas.canWrite(x, z)) {
                    continue;
                }
                boolean road = Math.abs(z) <= 3 || x > 52 && Math.abs(z) <= 21 || x > 50 && Math.abs(z) <= 5;
                if (road) {
                    canvas.set(x, G, z, SiegeKit.paving(random));
                } else if (z > -12 && z < 12 && random.nextInt(3) == 0) {
                    canvas.set(x, G, z, SiegeKit.pick(random, Material.COARSE_DIRT, Material.GRAVEL,
                            Material.MOSS_BLOCK));
                }
            }
        }
    }

    // ------------------------------------------------------------------ the wall

    private void wall(MapCanvas canvas, Random random) {
        for (int z = -SiegeLayout.HALF_WIDTH; z <= SiegeLayout.HALF_WIDTH; z++) {
            for (int x = W1; x <= W2; x++) {
                if (!canvas.canWrite(x, z)) {
                    continue;
                }
                for (int y = G + 1; y <= TOP; y++) {
                    canvas.set(x, y, z, y <= G + 2 ? SiegeKit.dark(random) : SiegeKit.masonry(random));
                }
            }
            boolean landing = z >= -22 && z <= -20;
            if (canvas.canWrite(W1, z) && !landing) {
                canvas.set(W1, TOP + 1, z, SiegeKit.masonry(random));
                if (Math.floorMod(z, 2) == 0) {
                    canvas.set(W1, TOP + 2, z, SiegeKit.masonry(random));
                }
            }
            if (canvas.canWrite(W2, z) && Math.floorMod(z, 8) == 4 && Math.abs(z) > 8) {
                canvas.set(W2, TOP + 1, z, Blocks.lantern(false, false));
            }
        }
    }

    private void gate(MapCanvas canvas, Random random) {
        for (int x = W1; x <= W2; x++) {
            for (int z = -3; z <= 3; z++) {
                for (int y = G + 1; y <= G + 5; y++) {
                    canvas.clear(x, y, z);
                }
                canvas.set(x, G + 6, z, Math.abs(z) == 3 ? Blocks.stairsTop(Material.STONE_BRICK_STAIRS,
                        z < 0 ? BlockFace.SOUTH : BlockFace.NORTH) : SiegeKit.masonry(random));
            }
            // The postern: 2 wide, 3 high.
            for (int z = SiegeLayout.POSTERN_Z - 1; z <= SiegeLayout.POSTERN_Z; z++) {
                for (int y = G + 1; y <= G + 3; y++) {
                    canvas.clear(x, y, z);
                }
            }
        }
        canvas.set(W1 - 1, G + 4, SiegeLayout.POSTERN_Z - 2, Blocks.wallTorch(false, BlockFace.WEST));
        canvas.set(W2 + 1, G + 4, SiegeLayout.POSTERN_Z + 1, Blocks.wallTorch(false, BlockFace.EAST));
        for (int z = -2; z <= 2; z++) {
            canvas.set(W2, G + 5, z, BlockSpec.of(Material.IRON_BARS));
        }
        // Gate towers on the courtyard side.
        for (int sign : new int[] {-1, 1}) {
            for (int x = W2 + 1; x <= W2 + 3; x++) {
                for (int dz = 4; dz <= 7; dz++) {
                    int z = sign * dz;
                    for (int y = G + 1; y <= TOP + 4; y++) {
                        canvas.set(x, y, z, y <= G + 2 ? SiegeKit.dark(random) : SiegeKit.masonry(random));
                    }
                    if ((x == W2 + 3 || dz == 4 || dz == 7) && Math.floorMod(x + z, 2) == 0) {
                        canvas.set(x, TOP + 5, z, SiegeKit.masonry(random));
                    }
                }
            }
            canvas.set(W2 + 4, G + 4, sign * 5, Blocks.wallTorch(false, BlockFace.EAST));
            canvas.set(W1 - 1, G + 4, sign * 4, Blocks.wallTorch(false, BlockFace.WEST));
        }
    }

    /** Stairs over the wall in the north: up from the bailey, along the wall-walk, down into the courtyard. */
    private void wallStairs(MapCanvas canvas, Random random) {
        for (int i = 0; i < 7; i++) {
            int top = G + 1 + i;
            int west = -14 - i;
            canvas.fill(W1 - 1, G + 1, west, W1 - 1, top - 1, west, SiegeKit.masonry(random));
            canvas.set(W1 - 1, top, west, Blocks.stairs(Material.STONE_BRICK_STAIRS, BlockFace.NORTH));
            int east = -23 - i;
            canvas.fill(W2 + 1, G + 1, east, W2 + 1, top - 1, east, SiegeKit.masonry(random));
            canvas.set(W2 + 1, top, east, Blocks.stairs(Material.STONE_BRICK_STAIRS, BlockFace.NORTH));
        }
        canvas.fill(W1 - 1, G + 1, -21, W1 - 1, TOP, -21, SiegeKit.masonry(random));
        canvas.fill(W2 + 1, G + 1, -30, W2 + 1, TOP, -30, SiegeKit.masonry(random));
        canvas.set(W1 - 1, G + 1, -12, Blocks.lantern(false, false));
    }

    /** Three flights up to the keep plateau. */
    private void plateauStairs(MapCanvas canvas, Random random) {
        int[][] flights = {{-4, 4}, {SiegeLayout.STAIR_NORTH_Z - 2, SiegeLayout.STAIR_NORTH_Z + 2},
                {SiegeLayout.STAIR_SOUTH_Z - 2, SiegeLayout.STAIR_SOUTH_Z + 2}};
        int steps = SiegeLayout.PLATEAU_Y - G - 1;
        for (int[] f : flights) {
            for (int i = 0; i < steps; i++) {
                int x = SiegeLayout.PLATEAU_EDGE - steps + i;
                int top = G + 1 + i;
                for (int z = f[0]; z <= f[1]; z++) {
                    canvas.fill(x, G + 1, z, x, top - 1, z, SiegeKit.masonry(random));
                    canvas.set(x, top, z, Blocks.stairs(z == f[0] || z == f[1] ? Material.MOSSY_STONE_BRICK_STAIRS
                            : Material.STONE_BRICK_STAIRS, BlockFace.EAST));
                }
            }
            // Side walls of the flight.
            for (int i = 0; i < steps; i++) {
                int x = SiegeLayout.PLATEAU_EDGE - steps + i;
                for (int z : new int[] {f[0] - 1, f[1] + 1}) {
                    canvas.fill(x, G + 1, z, x, G + 1 + i, z, SiegeKit.masonry(random));
                }
            }
            SiegeKit.lampPost(canvas, SiegeLayout.PLATEAU_EDGE - steps - 1, G, f[0] - 1, false);
            SiegeKit.lampPost(canvas, SiegeLayout.PLATEAU_EDGE - steps - 1, G, f[1] + 1, false);
        }
    }

    // ------------------------------------------------------------------ chapel

    /** The chapel (defender spawn 1): doors south, east and west; gabled roof; altar in the north. */
    private void chapel(MapCanvas canvas, Random random) {
        int x1 = SiegeLayout.CHAPEL_X1;
        int x2 = SiegeLayout.CHAPEL_X2;
        int z1 = SiegeLayout.CHAPEL_Z1;
        int z2 = SiegeLayout.CHAPEL_Z2;
        int eaves = G + 6;
        for (int x = x1; x <= x2; x++) {
            for (int z = z1; z <= z2; z++) {
                boolean edge = x == x1 || x == x2 || z == z1 || z == z2;
                boolean corner = (x == x1 || x == x2) && (z == z1 || z == z2);
                canvas.set(x, G, z, edge ? SiegeKit.dark(random) : (x + z) % 2 == 0
                        ? BlockSpec.of(Material.POLISHED_ANDESITE) : BlockSpec.of(Material.STONE_BRICKS));
                if (!edge) {
                    continue;
                }
                for (int y = G + 1; y < eaves; y++) {
                    canvas.set(x, y, z, corner ? Blocks.log(Material.STRIPPED_DARK_OAK_LOG, Axis.Y)
                            : y == G + 1 ? SiegeKit.dark(random) : SiegeKit.masonry(random));
                }
            }
        }
        // Windows (panes) on the long sides.
        for (int z = z1 + 2; z <= z2 - 2; z += 3) {
            canvas.fill(x1, G + 3, z, x1, G + 4, z, BlockSpec.of(Material.GLASS_PANE));
            canvas.fill(x2, G + 3, z, x2, G + 4, z, BlockSpec.of(Material.GLASS_PANE));
        }
        // Doors: south (2 wide), east, west.
        for (int y = G + 1; y <= G + 3; y++) {
            canvas.clear(48, y, z2);
            canvas.clear(49, y, z2);
            canvas.clear(x2, y, -27);
            canvas.clear(x2, y, -26);
            canvas.clear(x1, y, -28);
            canvas.clear(x1, y, -27);
        }
        // Gabled roof, ridge along z.
        int half = (x2 - x1) / 2;
        for (int i = 0; i <= half; i++) {
            int y = eaves + i;
            for (int z = z1 - 1; z <= z2 + 1; z++) {
                canvas.set(x1 + i, y, z, Blocks.stairs(Material.DARK_OAK_STAIRS, BlockFace.EAST));
                canvas.set(x2 - i, y, z, Blocks.stairs(Material.DARK_OAK_STAIRS, BlockFace.WEST));
            }
            // Gable ends.
            for (int x = x1 + i + 1; x <= x2 - i - 1; x++) {
                canvas.set(x, y, z1, SiegeKit.masonry(random));
                canvas.set(x, y, z2, SiegeKit.masonry(random));
            }
        }
        canvas.set((x1 + x2) / 2, eaves + 2, z2, BlockSpec.of(Material.GLASS_PANE));
        // Bell tower stub on the ridge and a light inside.
        canvas.set(48, eaves + half + 1, z2, BlockSpec.of(Material.STONE_BRICK_WALL));
        canvas.set(48, eaves + half + 2, z2, Blocks.lantern(true, false));
        // Altar and candles at the north end.
        canvas.fill(47, G + 1, z1 + 1, 50, G + 1, z1 + 1, BlockSpec.of(Material.POLISHED_DEEPSLATE));
        canvas.set(48, G + 2, z1 + 1, Material.CANDLE);
        canvas.set(49, G + 2, z1 + 1, Material.CANDLE);
        for (int z = z1 + 2; z < z2; z += 4) {
            canvas.set(46, eaves + 1, z, Blocks.lantern(true, true));
            canvas.set(51, eaves + 1, z, Blocks.lantern(true, true));
        }
        canvas.set(47, G + 3, z2 + 1, Blocks.wallTorch(false, BlockFace.SOUTH));
        canvas.set(50, G + 3, z2 + 1, Blocks.wallTorch(false, BlockFace.SOUTH));
    }

    // ------------------------------------------------------------------ orchard and props

    /** The orchard in the south: rows of fruit trees and hedges, a path from the postern. */
    private void orchard(MapCanvas canvas, Random random) {
        for (int x = 34; x <= 52; x += 6) {
            for (int z = 25; z <= 35; z += 5) {
                int tx = x + random.nextInt(2);
                int tz = z + random.nextInt(2) - (x % 12 == 4 ? 2 : 0);
                if (canvas.canWrite(tx, tz)) {
                    SiegeKit.oak(canvas, tx, G, tz, 3 + random.nextInt(2), Material.OAK_LOG,
                            random.nextInt(3) == 0 ? Material.FLOWERING_AZALEA_LEAVES : Material.OAK_LEAVES,
                            random);
                }
            }
        }
        // Hedges along the path from the postern and around the orchard.
        for (int x = 33; x <= 52; x++) {
            if (x % 5 != 2) {
                canvas.set(x, G + 1, 23, Blocks.leaves(Material.AZALEA_LEAVES));
            }
        }
        for (int x = W2 + 1; x <= 40; x++) {
            canvas.set(x, G, SiegeLayout.POSTERN_Z, SiegeKit.track(random));
            canvas.set(x, G, SiegeLayout.POSTERN_Z - 1, SiegeKit.track(random));
        }
        canvas.fill(36, G + 1, 14, 36, G + 1, 17, Blocks.leaves(Material.AZALEA_LEAVES));
        canvas.fill(48, G + 1, 13, 48, G + 1, 15, Blocks.leaves(Material.AZALEA_LEAVES));
    }

    private void props(MapCanvas canvas, Random random) {
        int cx = SiegeLayout.MIDDLE_X;
        // Low broken walls around the middle platform.
        int[][] pieces = {{cx - 9, -7, cx - 9, -5}, {cx - 9, 5, cx - 9, 7}, {cx + 9, -6, cx + 9, -4},
                {cx + 9, 4, cx + 9, 6}, {cx - 2, -9, cx + 1, -9}, {cx - 1, 9, cx + 2, 9}};
        for (int[] p : pieces) {
            for (int x = p[0]; x <= p[2]; x++) {
                for (int z = p[1]; z <= p[3]; z++) {
                    canvas.set(x, G + 1, z, SiegeKit.masonry(random));
                    if (random.nextBoolean()) {
                        canvas.set(x, G + 2, z, Blocks.slab(Material.STONE_BRICK_SLAB));
                    }
                }
            }
        }
        for (int[] l : new int[][] {{cx - 8, -9}, {cx + 8, 9}, {cx - 8, 9}, {cx + 8, -9}}) {
            SiegeKit.lampPost(canvas, l[0], G, l[1], false);
        }
        // A cart and supplies on each side.
        SiegeKit.crates(canvas, 34, G, -15, random);
        SiegeKit.crates(canvas, 40, G, -18, random);
        SiegeKit.crates(canvas, 54, G, 9, random);
        SiegeKit.crates(canvas, 35, G, 12, random);
        canvas.fill(52, G + 1, -10, 54, G + 1, -9, Blocks.slab(Material.SPRUCE_SLAB));
        canvas.fill(52, G + 2, -10, 53, G + 2, -9, BlockSpec.of(Material.HAY_BLOCK));
        // A statue on a plinth on the north side.
        canvas.fill(36, G + 1, -34, 38, G + 1, -32, SiegeKit.dark(random));
        canvas.set(37, G + 2, -33, Blocks.log(Material.POLISHED_BASALT, Axis.Y));
        canvas.set(37, G + 3, -33, Blocks.log(Material.POLISHED_BASALT, Axis.Y));
        canvas.set(37, G + 4, -33, BlockSpec.of(Material.CHISELED_STONE_BRICKS));
        canvas.set(37, G + 5, -33, Blocks.lantern(true, false));
        for (int[] t : new int[][] {{33, -37}, {57, -36}, {57, 36}}) {
            if (canvas.canWrite(t[0], t[1])) {
                SiegeKit.spruce(canvas, t[0], G, t[1], 7, random);
            }
        }
    }
}
