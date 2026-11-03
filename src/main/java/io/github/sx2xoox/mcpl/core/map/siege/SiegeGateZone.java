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
 * The outer wall and the outer bailey. The wall has a gatehouse on the main road, a breach with stepped broken ends
 * on the north flank (climbable up to the wall-walk) and the tunnel of the south flank under it. Inside: the 성문 탑
 * platform, the guardhouse where the defenders spawn while it stands (doors west, north and east), a ruined barracks
 * in the north and a smithy yard around the tunnel stairwell in the south.
 */
final class SiegeGateZone implements MapZone {

    private static final int G = SiegeLayout.GROUND_Y;
    private static final int TOP = SiegeLayout.WALL_TOP;
    private static final int W1 = SiegeLayout.OUTER_WALL_WEST;
    private static final int W2 = SiegeLayout.OUTER_WALL_EAST;

    @Override
    public String name() {
        return "gate";
    }

    @Override
    public boolean contains(int x, int z) {
        return SiegeLayout.isInterior(x, z) && x >= -17 && x < 25;
    }

    @Override
    public void build(MapCanvas canvas) {
        Random random = canvas.random(name());
        ground(canvas, random);
        wall(canvas, random);
        gatehouse(canvas, random);
        wallStairs(canvas, random);
        platform(canvas, random);
        guardhouse(canvas, random);
        barracks(canvas, random);
        yard(canvas, random);
    }

    // ------------------------------------------------------------------ ground

    private void ground(MapCanvas canvas, Random random) {
        for (int x = -17; x < 25; x++) {
            for (int z = -SiegeLayout.HALF_WIDTH; z <= SiegeLayout.HALF_WIDTH; z++) {
                if (!canvas.canWrite(x, z)) {
                    continue;
                }
                if (Math.abs(z) <= 3 && x > W2 || Math.abs(z) <= 2) {
                    canvas.set(x, G, z, SiegeKit.paving(random));
                } else if (x > W2 && random.nextInt(4) == 0) {
                    canvas.set(x, G, z, SiegeKit.pick(random, Material.COARSE_DIRT, Material.GRAVEL,
                            Material.PACKED_MUD));
                }
            }
        }
    }

    // ------------------------------------------------------------------ the curtain wall

    /** Height of the wall's top at {@code z}: full height, falling in steps to the breach. */
    static int wallTop(int z) {
        int dz = Math.abs(z - SiegeLayout.BREACH_Z);
        return Math.max(G + 1, Math.min(TOP, G + 1 + dz - 3));
    }

    private void wall(MapCanvas canvas, Random random) {
        for (int z = -SiegeLayout.HALF_WIDTH; z <= SiegeLayout.HALF_WIDTH; z++) {
            int top = wallTop(z);
            for (int x = W1; x <= W2; x++) {
                if (!canvas.canWrite(x, z)) {
                    continue;
                }
                for (int y = G + 1; y <= top; y++) {
                    canvas.set(x, y, z, y <= G + 2 ? SiegeKit.dark(random) : SiegeKit.masonry(random));
                }
                if (top < TOP) {
                    canvas.set(x, top, z, SiegeKit.rubble(random));
                }
            }
            if (top == TOP && canvas.canWrite(W1, z)) {
                canvas.set(W1, TOP + 1, z, SiegeKit.masonry(random));
                if (Math.floorMod(z, 2) == 0) {
                    canvas.set(W1, TOP + 2, z, SiegeKit.masonry(random));
                }
                if (Math.floorMod(z, 8) == 0 && Math.abs(z) > 8) {
                    canvas.set(W2, TOP + 1, z, Blocks.lantern(false, false));
                }
            }
        }
        // Rubble spilled on both sides of the breach.
        for (int i = 0; i < 26; i++) {
            int x = W1 - 3 + random.nextInt(W2 - W1 + 7);
            int z = SiegeLayout.BREACH_Z - 5 + random.nextInt(11);
            if (x >= W1 && x <= W2 || !canvas.canWrite(x, z)) {
                continue;
            }
            canvas.set(x, G + 1, z, random.nextBoolean() ? SiegeKit.rubble(random)
                    : Blocks.slab(Material.COBBLESTONE_SLAB));
        }
    }

    /** The gatehouse: a 7-wide arch under the wall-walk, two towers jutting out towards the field. */
    private void gatehouse(MapCanvas canvas, Random random) {
        for (int x = W1; x <= W2; x++) {
            for (int z = -3; z <= 3; z++) {
                for (int y = G + 1; y <= G + 5; y++) {
                    canvas.clear(x, y, z);
                }
                canvas.set(x, G + 6, z, Math.abs(z) == 3 ? Blocks.stairsTop(Material.STONE_BRICK_STAIRS,
                        z < 0 ? BlockFace.SOUTH : BlockFace.NORTH) : SiegeKit.masonry(random));
            }
        }
        // Raised portcullis.
        for (int z = -2; z <= 2; z++) {
            canvas.set(W1, G + 5, z, BlockSpec.of(Material.IRON_BARS));
        }
        for (int sign : new int[] {-1, 1}) {
            int z1 = sign * 4;
            int z2 = sign * 7;
            for (int x = -17; x < W1; x++) {
                for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
                    for (int y = G + 1; y <= TOP + 4; y++) {
                        canvas.set(x, y, z, y <= G + 2 ? SiegeKit.dark(random) : SiegeKit.masonry(random));
                    }
                    if (x == -17 || z == z2 || z == z1) {
                        if (Math.floorMod(x + z, 2) == 0) {
                            canvas.set(x, TOP + 5, z, SiegeKit.masonry(random));
                        }
                    }
                }
            }
            // Arrow slits and a banner of light.
            canvas.set(-17, G + 8, sign * 5, BlockSpec.of(Material.IRON_BARS));
            canvas.set(-17, G + 8, sign * 6, BlockSpec.of(Material.IRON_BARS));
        }
        for (int z = -3; z <= 3; z += 6) {
            canvas.set(W2 + 1, G + 4, z, Blocks.wallTorch(false, BlockFace.EAST));
        }
    }

    /** Stairs from the bailey up to the wall-walk, south of the gate (the breach is the way up in the north). */
    private void wallStairs(MapCanvas canvas, Random random) {
        int x = W2 + 1;
        for (int i = 0; i < 7; i++) {
            int z = 8 + i;
            int top = G + 1 + i;
            canvas.fill(x, G + 1, z, x, top - 1, z, SiegeKit.masonry(random));
            canvas.set(x, top, z, Blocks.stairs(Material.STONE_BRICK_STAIRS, BlockFace.SOUTH));
        }
        canvas.fill(x, G + 1, 15, x, TOP, 15, SiegeKit.masonry(random));
        canvas.set(x + 1, G + 1, 8, Blocks.lantern(false, false));
    }

    // ------------------------------------------------------------------ the bailey

    /** The 성문 탑 platform: a paved disk with a dark ring; low cover walls just outside it. */
    private void platform(MapCanvas canvas, Random random) {
        towerFloor(canvas, random, SiegeLayout.FRONT_X, 0, G);
        int cx = SiegeLayout.FRONT_X;
        // Broken low walls around the platform, every way in left open.
        int[][] pieces = {{cx - 9, -6, cx - 9, -4}, {cx - 9, 4, cx - 9, 6}, {cx + 9, -6, cx + 9, -4},
                {cx + 9, 4, cx + 9, 6}, {cx - 3, -9, cx - 1, -9}, {cx + 2, 9, cx + 4, 9}};
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
        for (int[] l : new int[][] {{cx - 8, -8}, {cx + 8, 8}, {cx - 8, 8}, {cx + 8, -8}}) {
            SiegeKit.lampPost(canvas, l[0], G, l[1], false);
        }
    }

    /** A tower platform's floor at {@code y}: chiselled centre, polished disk, dark rim. */
    static void towerFloor(MapCanvas canvas, Random random, int cx, int cz, int y) {
        int r = SiegeLayout.TOWER_RADIUS;
        for (int dx = -r - 1; dx <= r + 1; dx++) {
            for (int dz = -r - 1; dz <= r + 1; dz++) {
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d > r + 1.2) {
                    continue;
                }
                BlockSpec spec;
                if (d < 1.5) {
                    spec = BlockSpec.of(Material.CHISELED_STONE_BRICKS);
                } else if (d > r + 0.3) {
                    spec = SiegeKit.dark(random);
                } else if (Math.abs(d - 4) < 0.5) {
                    spec = BlockSpec.of(Material.POLISHED_DEEPSLATE);
                } else {
                    spec = random.nextInt(5) == 0 ? BlockSpec.of(Material.ANDESITE)
                            : BlockSpec.of(Material.POLISHED_ANDESITE);
                }
                canvas.set(cx + dx, y, cz + dz, spec);
            }
        }
    }

    /** The guardhouse (defender spawn 0): stone, slab roof, doors west (towards the tower), north and east. */
    private void guardhouse(MapCanvas canvas, Random random) {
        int x1 = SiegeLayout.GUARD_X1;
        int x2 = SiegeLayout.GUARD_X2;
        int z1 = SiegeLayout.GUARD_Z1;
        int z2 = SiegeLayout.GUARD_Z2;
        int roof = G + 5;
        for (int x = x1; x <= x2; x++) {
            for (int z = z1; z <= z2; z++) {
                boolean edge = x == x1 || x == x2 || z == z1 || z == z2;
                boolean corner = (x == x1 || x == x2) && (z == z1 || z == z2);
                canvas.set(x, G, z, edge ? SiegeKit.dark(random) : SiegeKit.pick(random, Material.SPRUCE_PLANKS,
                        Material.SPRUCE_PLANKS, Material.STRIPPED_SPRUCE_WOOD));
                if (edge) {
                    for (int y = G + 1; y < roof; y++) {
                        canvas.set(x, y, z, corner ? Blocks.log(Material.STRIPPED_SPRUCE_LOG, Axis.Y)
                                : SiegeKit.masonry(random));
                    }
                }
                canvas.set(x, roof, z, edge ? SiegeKit.dark(random) : BlockSpec.of(Material.STONE_BRICKS));
            }
        }
        // Doors (3 wide, 3 high): west, north, east.
        for (int d = -1; d <= 1; d++) {
            for (int y = G + 1; y <= G + 3; y++) {
                canvas.clear(x1, y, 12 + d);
                canvas.clear(x2, y, 12 + d);
                canvas.clear(18 + d, y, z1);
            }
        }
        // Windows on the south side.
        for (int x = x1 + 2; x <= x2 - 2; x += 2) {
            canvas.set(x, G + 2, z2, BlockSpec.of(Material.IRON_BARS));
        }
        // Battlements on the roof (out of reach) and lights.
        for (int x = x1; x <= x2; x += 2) {
            canvas.set(x, roof + 1, z1, SiegeKit.masonry(random));
            canvas.set(x, roof + 1, z2, SiegeKit.masonry(random));
        }
        canvas.set(x1 + 1, roof - 1, z2 - 1, Blocks.lantern(false, true));
        canvas.set(x2 - 1, roof - 1, z1 + 1, Blocks.lantern(false, true));
        canvas.set(x2 - 1, roof - 1, z2 - 1, Blocks.lantern(false, true));
        canvas.set(x1 - 1, G + 3, z1, Blocks.lantern(false, false));
        // Racks along the south wall, between the windows.
        canvas.set(x1 + 1, G + 1, z2 - 1, Material.BARREL);
        canvas.set(x2 - 1, G + 1, z2 - 1, Material.BARREL);
    }

    /** A ruined barracks in the north of the bailey: walls of different heights, half a roof, bunks. */
    private void barracks(MapCanvas canvas, Random random) {
        int x1 = 4;
        int x2 = 16;
        int z1 = -34;
        int z2 = -24;
        for (int x = x1; x <= x2; x++) {
            for (int z = z1; z <= z2; z++) {
                if (!canvas.canWrite(x, z)) {
                    continue;
                }
                boolean edge = x == x1 || x == x2 || z == z1 || z == z2;
                canvas.set(x, G, z, edge ? SiegeKit.dark(random) : SiegeKit.pick(random, Material.SPRUCE_PLANKS,
                        Material.COBBLESTONE, Material.SPRUCE_PLANKS));
                if (!edge) {
                    continue;
                }
                boolean door = z == z2 && (x == 7 || x == 8 || x == 12 || x == 13) || x == x1 && (z == -29 || z == -28)
                        || x == x2 && (z == -30 || z == -29);
                if (door) {
                    continue;
                }
                int h = x > 11 ? 5 : 1 + random.nextInt(3);
                for (int y = G + 1; y <= G + h; y++) {
                    canvas.set(x, y, z, SiegeKit.masonry(random));
                }
            }
        }
        // The eastern half keeps its roof.
        for (int x = 11; x <= x2; x++) {
            for (int z = z1; z <= z2; z++) {
                if (canvas.canWrite(x, z)) {
                    canvas.set(x, G + 6, z, Blocks.slab(Material.SPRUCE_SLAB));
                }
            }
        }
        for (int z = z1 + 1; z < z2; z += 3) {
            canvas.set(15, G + 1, z, Blocks.slab(Material.SPRUCE_SLAB));
            canvas.set(5, G + 1, z, Material.WHITE_WOOL);
        }
        canvas.set(14, G + 5, -29, Blocks.lantern(false, true));
        canvas.set(8, G + 1, -30, Blocks.campfire(false));
    }

    /** The rest of the bailey: smithy by the tunnel stairwell, a well, carts, trees and lamps. */
    private void yard(MapCanvas canvas, Random random) {
        // Smithy shelter (open on two sides) east of the tunnel stairwell.
        int sx = 6;
        int sz = 24;
        for (int[] post : new int[][] {{sx, sz}, {sx + 5, sz}, {sx, sz + 5}, {sx + 5, sz + 5}}) {
            canvas.fill(post[0], G + 1, post[1], post[0], G + 3, post[1], Blocks.log(Material.SPRUCE_LOG, Axis.Y));
        }
        canvas.fill(sx, G + 4, sz, sx + 5, G + 4, sz + 5, Blocks.slab(Material.SPRUCE_SLAB));
        canvas.fill(sx + 1, G + 1, sz + 5, sx + 4, G + 2, sz + 5, SiegeKit.masonry(random));
        canvas.set(sx + 2, G + 1, sz + 4, Material.BLAST_FURNACE);
        canvas.set(sx + 3, G + 1, sz + 4, Material.ANVIL);
        canvas.set(sx + 4, G + 1, sz + 4, Material.GRINDSTONE);
        canvas.set(sx + 2, G + 3, sz + 2, Blocks.lantern(false, true));
        // A well in the north-west of the bailey.
        int wx = -4;
        int wz = -16;
        canvas.fill(wx - 1, G + 1, wz - 1, wx + 1, G + 1, wz + 1, SiegeKit.masonry(random));
        canvas.set(wx, G + 1, wz, Material.WATER_CAULDRON);
        canvas.fill(wx - 1, G + 2, wz - 1, wx - 1, G + 3, wz - 1, BlockSpec.of(Material.SPRUCE_FENCE));
        canvas.fill(wx + 1, G + 2, wz + 1, wx + 1, G + 3, wz + 1, BlockSpec.of(Material.SPRUCE_FENCE));
        canvas.fill(wx - 1, G + 4, wz - 1, wx + 1, G + 4, wz + 1, Blocks.slab(Material.SPRUCE_SLAB));
        // Carts, crates, hay: cover along the flanks.
        SiegeKit.crates(canvas, 12, G, -14, random);
        SiegeKit.crates(canvas, -7, G, -8, random);
        SiegeKit.crates(canvas, -6, G, 9, random);
        SiegeKit.crates(canvas, 12, G, 19, random);
        SiegeKit.crates(canvas, 21, G, -20, random);
        SiegeKit.crates(canvas, 0, G, 18, random);
        canvas.fill(16, G + 1, -10, 18, G + 1, -9, BlockSpec.of(Material.HAY_BLOCK));
        canvas.set(17, G + 2, -10, Material.HAY_BLOCK);
        // Trees by the walls.
        for (int[] t : new int[][] {{-7, -36}, {20, 34}, {-6, 35}, {21, -36}}) {
            if (canvas.canWrite(t[0], t[1])) {
                SiegeKit.oak(canvas, t[0], G, t[1], 5, Material.OAK_LOG, Material.OAK_LEAVES, random);
            }
        }
        for (int[] l : new int[][] {{-9, -4}, {-9, 4}, {12, -4}, {12, 4}, {22, -4}, {22, 4}, {-4, 30}, {8, -20}}) {
            SiegeKit.lampPost(canvas, l[0], G, l[1], false);
        }
    }
}
