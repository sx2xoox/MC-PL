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
 * The keep plateau at the east end, five blocks above the courtyard behind a retaining wall. The roofless square keep
 * holds the 코어 platform in its middle and the defenders' last spawn behind it; doors open west (main road), north
 * and south (the flanks), and two flights inside lead up to the wall-walk around its top.
 */
final class SiegeKeepZone implements MapZone {

    private static final int P = SiegeLayout.PLATEAU_Y;
    private static final int X1 = SiegeLayout.KEEP_X1;
    private static final int X2 = SiegeLayout.KEEP_X2;
    private static final int KZ = SiegeLayout.KEEP_Z;
    private static final int TOP = SiegeLayout.KEEP_TOP;

    @Override
    public String name() {
        return "keep";
    }

    @Override
    public boolean contains(int x, int z) {
        return SiegeLayout.isInterior(x, z) && x >= SiegeLayout.PLATEAU_EDGE;
    }

    @Override
    public void build(MapCanvas canvas) {
        Random random = canvas.random(name());
        plateau(canvas, random);
        walls(canvas, random);
        doors(canvas, random);
        inside(canvas, random);
        grounds(canvas, random);
    }

    private static boolean stairOpening(int z) {
        return Math.abs(z) <= 5 || Math.abs(z - SiegeLayout.STAIR_NORTH_Z) <= 3
                || Math.abs(z - SiegeLayout.STAIR_SOUTH_Z) <= 3;
    }

    private void plateau(MapCanvas canvas, Random random) {
        int edge = SiegeLayout.PLATEAU_EDGE;
        for (int z = -SiegeLayout.HALF_WIDTH; z <= SiegeLayout.HALF_WIDTH; z++) {
            if (!canvas.canWrite(edge, z)) {
                continue;
            }
            // Retaining wall facing the courtyard, with a low parapet except at the stairs.
            for (int y = SiegeLayout.GROUND_Y + 1; y < P; y++) {
                canvas.set(edge, y, z, y <= SiegeLayout.GROUND_Y + 2 ? SiegeKit.dark(random)
                        : SiegeKit.masonry(random));
            }
            canvas.set(edge, P, z, stairOpening(z) ? SiegeKit.paving(random) : SiegeKit.dark(random));
            if (!stairOpening(z)) {
                canvas.set(edge, P + 1, z, Math.floorMod(z, 6) == 0 ? BlockSpec.of(Material.STONE_BRICKS)
                        : BlockSpec.of(Material.STONE_BRICK_WALL));
                if (Math.floorMod(z, 6) == 0) {
                    canvas.set(edge, P + 2, z, Blocks.lantern(false, false));
                }
            }
        }
        // A paved ring around the keep and the approaches from the stairs.
        for (int x = edge + 1; x <= SiegeLayout.EAST_END; x++) {
            for (int z = -SiegeLayout.HALF_WIDTH; z <= SiegeLayout.HALF_WIDTH; z++) {
                if (!canvas.canWrite(x, z)) {
                    continue;
                }
                boolean ring = Math.abs(z) <= KZ + 3 && Math.abs(z) > KZ;
                boolean approach = Math.abs(z) <= 3 || Math.abs(Math.abs(z) - SiegeLayout.STAIR_SOUTH_Z) <= 2
                        && x <= 76;
                if (ring || approach) {
                    canvas.set(x, P, z, random.nextInt(5) == 0 ? SiegeKit.pick(random, Material.MOSSY_COBBLESTONE,
                            Material.GRAVEL) : SiegeKit.paving(random));
                }
            }
        }
    }

    private void walls(MapCanvas canvas, Random random) {
        for (int x = X1; x <= X2; x++) {
            for (int z = -KZ; z <= KZ; z++) {
                boolean outer = x == X1 || x == X2 || Math.abs(z) == KZ;
                boolean inner = !outer && (x == X1 + 1 || x == X2 - 1 || Math.abs(z) == KZ - 1);
                if (!outer && !inner) {
                    continue;
                }
                for (int y = P + 1; y <= TOP; y++) {
                    BlockSpec spec = y <= P + 2 ? SiegeKit.dark(random) : y == TOP - 2 && outer
                            ? BlockSpec.of(Material.POLISHED_DEEPSLATE) : SiegeKit.masonry(random);
                    canvas.set(x, y, z, spec);
                }
                if (outer && Math.floorMod(x + z, 2) == 0) {
                    canvas.set(x, TOP + 1, z, SiegeKit.masonry(random));
                }
            }
        }
        // Corner pillars with braziers.
        for (int x : new int[] {X1, X2}) {
            for (int z : new int[] {-KZ, KZ}) {
                canvas.set(x, TOP + 1, z, SiegeKit.dark(random));
                canvas.set(x, TOP + 2, z, SiegeKit.dark(random));
                canvas.set(x, TOP + 3, z, Blocks.lantern(true, false));
            }
        }
        // Buttresses on the outside of the long walls.
        for (int x = X1 + 4; x < X2; x += 6) {
            for (int z : new int[] {-KZ - 1, KZ + 1}) {
                if (Math.abs(x - SiegeLayout.CORE_X) <= 3) {
                    continue;
                }
                canvas.fill(x, P + 1, z, x, P + 3, z, SiegeKit.dark(random));
                canvas.set(x, P + 4, z, Blocks.stairs(Material.DEEPSLATE_BRICK_STAIRS,
                        z < 0 ? BlockFace.SOUTH : BlockFace.NORTH));
            }
        }
    }

    private void doors(MapCanvas canvas, Random random) {
        // West: the main door, 5 wide, 4 high.
        for (int x = X1; x <= X1 + 1; x++) {
            for (int z = -2; z <= 2; z++) {
                for (int y = P + 1; y <= P + 4; y++) {
                    canvas.clear(x, y, z);
                }
                canvas.set(x, P, z, BlockSpec.of(Material.POLISHED_DEEPSLATE));
            }
            canvas.set(x, P + 5, -2, Blocks.stairsTop(Material.DEEPSLATE_BRICK_STAIRS, BlockFace.SOUTH));
            canvas.set(x, P + 5, 2, Blocks.stairsTop(Material.DEEPSLATE_BRICK_STAIRS, BlockFace.NORTH));
        }
        // North and south: 5 wide, 4 high.
        int cx = SiegeLayout.CORE_X;
        for (int sign : new int[] {-1, 1}) {
            for (int dz = KZ - 1; dz <= KZ; dz++) {
                int z = sign * dz;
                for (int x = cx - 2; x <= cx + 2; x++) {
                    for (int y = P + 1; y <= P + 4; y++) {
                        canvas.clear(x, y, z);
                    }
                    canvas.set(x, P, z, BlockSpec.of(Material.POLISHED_DEEPSLATE));
                }
                canvas.set(cx - 2, P + 5, z, Blocks.stairsTop(Material.DEEPSLATE_BRICK_STAIRS, BlockFace.EAST));
                canvas.set(cx + 2, P + 5, z, Blocks.stairsTop(Material.DEEPSLATE_BRICK_STAIRS, BlockFace.WEST));
            }
            canvas.set(cx - 3, P + 4, sign * (KZ + 1), Blocks.wallTorch(true, sign < 0 ? BlockFace.NORTH
                    : BlockFace.SOUTH));
            canvas.set(cx + 3, P + 4, sign * (KZ + 1), Blocks.wallTorch(true, sign < 0 ? BlockFace.NORTH
                    : BlockFace.SOUTH));
        }
        canvas.set(X1 - 1, P + 4, -3, Blocks.wallTorch(true, BlockFace.WEST));
        canvas.set(X1 - 1, P + 4, 3, Blocks.wallTorch(true, BlockFace.WEST));
    }

    private void inside(MapCanvas canvas, Random random) {
        // Floor: dark tiles, the core platform in the middle.
        for (int x = X1 + 2; x <= X2 - 2; x++) {
            for (int z = -KZ + 2; z <= KZ - 2; z++) {
                canvas.set(x, P, z, random.nextInt(6) == 0 ? BlockSpec.of(Material.CRACKED_DEEPSLATE_TILES)
                        : (x + z) % 2 == 0 ? BlockSpec.of(Material.DEEPSLATE_TILES)
                        : BlockSpec.of(Material.POLISHED_DEEPSLATE));
            }
        }
        SiegeGateZone.towerFloor(canvas, random, SiegeLayout.CORE_X, 0, P);
        // Two flights to the wall-walk along the east wall.
        int sx = X2 - 2;
        for (int i = 0; i < TOP - P - 1; i++) {
            int top = P + 1 + i;
            for (int sign : new int[] {-1, 1}) {
                int z = sign * (4 + i);
                canvas.fill(sx, P + 1, z, sx, top - 1, z, SiegeKit.masonry(random));
                canvas.set(sx, top, z, Blocks.stairs(Material.STONE_BRICK_STAIRS,
                        sign < 0 ? BlockFace.NORTH : BlockFace.SOUTH));
            }
        }
        // Pillars with soul braziers, out of the platform.
        for (int[] p : new int[][] {{65, -9}, {65, 9}, {81, -9}, {81, 9}}) {
            canvas.fill(p[0], P + 1, p[1], p[0], P + 4, p[1], Blocks.log(Material.POLISHED_BASALT, Axis.Y));
            canvas.set(p[0], P + 5, p[1], SiegeKit.dark(random));
            canvas.set(p[0], P + 6, p[1], BlockSpec.of(Material.SOUL_CAMPFIRE).lit(true));
        }
        // Low screens covering the spawn behind the core, open in the middle.
        for (int z : new int[] {-6, -5, 5, 6}) {
            canvas.set(80, P + 1, z, SiegeKit.dark(random));
            canvas.set(80, P + 2, z, Blocks.slab(Material.DEEPSLATE_BRICK_SLAB));
        }
        // Wall lights.
        for (int z = -8; z <= 8; z += 8) {
            canvas.set(X1 + 2, P + 4, z == 0 ? -5 : z, Blocks.wallTorch(true, BlockFace.EAST));
        }
        for (int x : new int[] {66, 70, 78, 82}) {
            canvas.set(x, P + 4, -KZ + 2, Blocks.wallTorch(true, BlockFace.SOUTH));
            canvas.set(x, P + 4, KZ - 2, Blocks.wallTorch(true, BlockFace.NORTH));
        }
        // Racks and barrels in the corners.
        SiegeKit.crates(canvas, 64, P, -11, random);
        SiegeKit.crates(canvas, 64, P, 11, random);
        canvas.set(84, P + 1, -11, Material.BARREL);
        canvas.set(84, P + 1, 11, Material.BARREL);
    }

    /** The plateau outside the keep: an armoury shed and graves in the north, a garden in the south. */
    private void grounds(MapCanvas canvas, Random random) {
        // Armoury shed (north-west).
        int ax = 63;
        int az = -30;
        for (int x = ax; x <= ax + 6; x++) {
            for (int z = az; z <= az + 5; z++) {
                if (!canvas.canWrite(x, z)) {
                    continue;
                }
                boolean edge = x == ax || x == ax + 6 || z == az || z == az + 5;
                if (edge && !(z == az + 5 && (x == ax + 3 || x == ax + 2))) {
                    canvas.fill(x, P + 1, z, x, P + 3, z, (x == ax || x == ax + 6) && (z == az || z == az + 5)
                            ? Blocks.log(Material.SPRUCE_LOG, Axis.Y) : BlockSpec.of(Material.SPRUCE_PLANKS));
                }
                canvas.set(x, P + 4, z, Blocks.slab(Material.SPRUCE_SLAB));
            }
        }
        canvas.set(ax + 1, P + 1, az + 1, Material.BARREL);
        canvas.set(ax + 5, P + 1, az + 1, Material.SMITHING_TABLE);
        canvas.set(ax + 3, P + 3, az + 2, Blocks.lantern(false, true));
        // Graves of the old garrison (north-east).
        for (int x = 74; x <= 84; x += 3) {
            for (int z = -34; z <= -26; z += 4) {
                if (canvas.canWrite(x, z) && canvas.canWrite(x, z + 1)) {
                    canvas.set(x, P + 1, z, BlockSpec.of(Material.COBBLESTONE_WALL));
                    canvas.set(x, P, z + 1, BlockSpec.of(Material.COARSE_DIRT));
                }
            }
        }
        // Garden (south): hedges, flowers, two trees.
        for (int[] t : new int[][] {{66, 29}, {82, 30}, {86, -36}}) {
            if (canvas.canWrite(t[0], t[1])) {
                SiegeKit.oak(canvas, t[0], P, t[1], 5, Material.DARK_OAK_LOG, Material.DARK_OAK_LEAVES, random);
            }
        }
        for (int x = 70; x <= 78; x++) {
            if (x != 74 && canvas.canWrite(x, 24)) {
                canvas.set(x, P + 1, 24, Blocks.leaves(Material.AZALEA_LEAVES));
            }
        }
        SiegeKit.crates(canvas, 63, P, 22, random);
        SiegeKit.crates(canvas, 85, P, -21, random);
        SiegeKit.crates(canvas, 85, P, 20, random);
        canvas.fill(68, P + 1, -24, 69, P + 1, -22, SiegeKit.rubble(random));
        SiegeKit.lampPost(canvas, 63, P, -16, true);
        SiegeKit.lampPost(canvas, 63, P, 16, true);
        SiegeKit.lampPost(canvas, 85, P, -16, true);
        SiegeKit.lampPost(canvas, 85, P, 16, true);
    }
}
